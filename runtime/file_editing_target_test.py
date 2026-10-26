"""Validate rename and guarded text save using only UUID files on an owned Android emulator."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import urllib.request
import uuid

import websockets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', type=Path, required=True)
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *map(str, command)], text=True, stderr=subprocess.STDOUT, timeout=60).strip()

    if not args.serial.startswith('emulator-') and adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        parser.error('Select an owned emulator, not a physical target')
    abi = adb('shell', 'getprop', 'ro.product.cpu.abi')
    expected = {'x86_64': 'x86_64', 'arm64-v8a': 'arm64-v8a', 'armeabi-v7a': 'armeabi-v7a'}.get(abi)
    if expected is None or expected not in args.binary.name:
        parser.error('Binary filename must match target ABI')
    marker = 'airc-file-editing-' + uuid.uuid4().hex
    directory = '/data/local/tmp/' + marker
    downloads = '/storage/emulated/0/Download/' + marker
    binary = directory + '/airc-runtime'
    state = directory + '/state'
    port = None
    started = False
    created = False
    original = b'\xff\xfe' + 'const x = 1\r\n中文'.encode('utf-16-le')
    edited = b'\xff\xfe' + 'const x = 2\r\n中文'.encode('utf-16-le')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    async def verify():
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            count = 0

            async def request(tool=None, arguments=None, kind='tool', success=True):
                nonlocal count
                count += 1
                await socket.send(json.dumps(dict(id=str(count), protocol=1, type=kind, tool=tool, arguments=arguments or {})))
                while True:
                    reply = json.loads(await asyncio.wait_for(socket.recv(), 45))
                    if reply.get('id') == str(count):
                        break
                assert reply['ok'] == success, reply
                return json.loads(reply['result']) if success else reply

            for root, prefix, physical in [('inbox', '', state + '/files/'), ('downloads', marker + '/', downloads + '/')]:
                renamed = await request('files.rename', dict(root=root, path=prefix + 'a.txt', name='b.txt'))
                assert renamed['renamed'] and renamed['destination'] == prefix + 'b.txt'
                await request('files.rename', dict(root=root, path=prefix + 'b.txt', name='exists.txt'), success=False)
                assert adb('shell', 'sha256sum', physical + 'b.txt').split()[0] == hashlib.sha256(original).hexdigest()
                assert adb('shell', 'cat', physical + 'exists.txt') == 'preserved'
                await request('files.rename', dict(root=root, path=prefix + 'folder', name='renamed-folder'))
                assert adb('shell', 'cat', physical + 'renamed-folder/child') == 'child'
                resource = await request('files.preview', dict(root=root, path=prefix + 'b.txt'))
                read = urllib.request.Request(f'http://127.0.0.1:{port}' + resource['dataPath'], headers={'Range': f'bytes=0-{len(original)-1}'})
                with opener.open(read, timeout=15) as response:
                    assert response.status == 206 and response.read() == original
                await request('files.preview_close', dict(id=resource['id']))
                await request('files.prepare_overwrite', dict(root=root, path=prefix + 'b.txt', expectedSha256='0' * 64), success=False)
                proposal = await request('files.prepare_overwrite', dict(root=root, path=prefix + 'b.txt', expectedSha256=hashlib.sha256(original).hexdigest()))
                assert adb('shell', 'sha256sum', physical + 'b.txt').split()[0] == hashlib.sha256(original).hexdigest()
                await request(arguments=dict(id=proposal['id']), kind='approve')
                task = await request('task.start', dict(root=root, path=prefix + 'b.txt', direction='upload', sizeBytes=len(edited), sha256=hashlib.sha256(edited).hexdigest(), overwriteToken=proposal['id']))
                upload = urllib.request.Request(f'http://127.0.0.1:{port}' + task['dataPath'], data=edited, headers={'Content-Type': 'application/octet-stream'}, method='PUT')
                with opener.open(upload, timeout=30) as response:
                    result = json.load(response)
                    assert result['phase'] == 'completed' and result['sha256'] == hashlib.sha256(edited).hexdigest(), result
                assert adb('shell', 'sha256sum', physical + 'b.txt').split()[0] == hashlib.sha256(edited).hexdigest()
                await request('files.prepare_overwrite', dict(root=root, path=prefix + 'b.txt', expectedSha256=hashlib.sha256(original).hexdigest()), success=False)
                print(f'PASS {root}: file/directory rename, collision preservation, range preview, explicit overwrite and changed-source guard', flush=True)

    try:
        adb('shell', 'mkdir', directory)
        created = True
        adb('push', args.binary, binary)
        adb('shell', 'chmod', '700', binary)
        assert adb('shell', 'sha256sum', binary).split()[0] == hashlib.sha256(args.binary.read_bytes()).hexdigest()
        started = True
        service = json.loads(adb('shell', f'{binary} service start --state-dir {state} --port 0 --no-mdns'))
        assert service['ready'] and service['running'], service
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(service['port'])))
        adb('shell', 'mkdir', '-p', downloads)
        with tempfile.TemporaryDirectory(prefix='airc-text-target-') as temp:
            source = Path(temp) / 'a.txt'
            source.write_bytes(original)
            for physical in [state + '/files', downloads]:
                adb('push', source, physical + '/a.txt')
                adb('shell', f'printf preserved > {physical}/exists.txt; mkdir {physical}/folder; printf child > {physical}/folder/child')
        asyncio.run(verify())
    finally:
        if started:
            adb('shell', f'{binary} service stop --state-dir {state}')
        if port:
            adb('forward', '--remove', 'tcp:' + str(port))
        if created:
            adb('shell', 'rm -rf -- ' + shlex.quote(directory) + ' ' + shlex.quote(downloads))
            assert adb('shell', f'test ! -e {directory} && test ! -e {downloads} && echo removed') == 'removed'
            print('PASS: owned runtime/files/forwarding cleaned; existing apps and services untouched', flush=True)


if __name__ == '__main__':
    main()
