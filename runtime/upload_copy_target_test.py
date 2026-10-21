"""Exercise single/chunked uploads in an owned emulator's Downloads using UUID fixtures."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import urllib.error
import urllib.request
import uuid

import websockets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--baseline-binary', type=Path)
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *map(str, command)], text=True, stderr=subprocess.STDOUT, timeout=60).strip()

    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        parser.error('An owned Android emulator is required')
    marker = {'x86_64': 'x86_64', 'arm64-v8a': 'aarch64', 'armeabi-v7a': 'armv7'}.get(adb('shell', 'getprop', 'ro.product.cpu.abi'))
    if not marker or marker not in args.binary.name:
        parser.error('Binary name must match emulator ABI')
    token = uuid.uuid4().hex
    directory = '/data/local/tmp/airc-upload-copy-' + token
    downloads = '/sdcard/Download/airc-upload-copy-' + token
    relative = 'airc-upload-copy-' + token
    started = []
    forwarded = []
    created = False
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def start(source, label):
        child = directory + '/' + label
        adb('shell', 'mkdir', '-m', '700', child)
        binary = child + '/airc-runtime'
        adb('push', source.resolve(), binary)
        adb('shell', 'chmod', '700', binary)
        assert adb('shell', 'sha256sum', binary).split()[0] == hashlib.sha256(source.read_bytes()).hexdigest()
        state = child + '/state'
        started.append((binary, state))
        reply = json.loads(adb('shell', f'{binary} service start --state-dir {state} --port 0 --no-mdns'))
        assert reply['ready'] and reply['running'], reply
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(reply['port'])))
        forwarded.append(port)
        return port

    async def verify(port, baseline=False):
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            sequence = 0

            async def request(tool, values, success=True):
                nonlocal sequence
                sequence += 1
                await socket.send(json.dumps(dict(id=str(sequence), protocol=1, type='tool', tool=tool, arguments=values)))
                while True:
                    response = json.loads(await asyncio.wait_for(socket.recv(), 30))
                    if response.get('id') == str(sequence):
                        break
                assert response['ok'] == success, response
                return json.loads(response['result']) if success else response

            async def upload_task(name, payload):
                return await request('task.start', dict(root='downloads', direction='upload', path=relative+'/'+name, sizeBytes=len(payload), sha256=hashlib.sha256(payload).hexdigest()))

            def send(task, payload, byte_range=None):
                headers = {'Content-Type': 'application/octet-stream'}
                path = task['dataPath']
                if byte_range is not None:
                    # Chunk endpoints use the same task identity and offset accounting.
                    path = path.removesuffix('/data') + '/range'
                    headers['Range'] = byte_range
                query = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=payload, method='PUT', headers=headers)
                try:
                    with opener.open(query, timeout=30) as reply:
                        return reply.status, reply.read()
                except urllib.error.HTTPError as error:
                    return error.code, error.read()

            if baseline:
                response = await request('task.start', dict(root='downloads', direction='upload', path=relative+'/baseline', sizeBytes=0, sha256=hashlib.sha256(b'').hexdigest()), success=False)
                assert 'safe atomic no-replace' in str(response), response
                print('PASS: previous binary reproduces unsupported no-replace on emulator Downloads', flush=True)
                return
            payload = ('Downloads 中文 payload\n'.encode() * 8192)
            for name, data in [('single.bin', payload), ('empty', b'')]:
                task = await upload_task(name, data)
                status, body = await asyncio.to_thread(send, task, data)
                assert status == 200 and json.loads(body)['phase'] == 'completed', (status, body)
                assert adb('shell', 'sha256sum', downloads+'/'+name).split()[0] == hashlib.sha256(data).hexdigest()
            task = await upload_task('resumed.bin', payload)
            midpoint = len(payload)//2
            status, _ = await asyncio.to_thread(send, task, payload[:midpoint], f'bytes=0-{midpoint-1}')
            assert status == 200
            progress = await request('task.progress', dict(id=task['id']))
            assert progress['bytesTransferred'] == midpoint and progress['phase'] == 'awaiting_upload', progress
            status, body = await asyncio.to_thread(send, task, payload[midpoint:], f'bytes={midpoint}-{len(payload)-1}')
            assert status == 200 and json.loads(body)['phase'] == 'completed', (status, body)
            assert adb('shell', 'sha256sum', downloads+'/resumed.bin').split()[0] == hashlib.sha256(payload).hexdigest()
            for chunked in [False, True]:
                name = 'race-' + str(chunked)
                task = await upload_task(name, b'new')
                adb('shell', f'printf original > {shlex.quote(downloads+"/"+name)}')
                status, _ = await asyncio.to_thread(send, task, b'new', 'bytes=0-2' if chunked else None)
                assert status == 400
                assert adb('shell', 'cat', downloads+'/'+name) == 'original'
                name = 'bad-' + str(chunked)
                task = await upload_task(name, b'good')
                status, _ = await asyncio.to_thread(send, task, b'bad!', 'bytes=0-3' if chunked else None)
                assert status == 400
                assert adb('shell', f'test ! -e {shlex.quote(downloads+"/"+name)} && echo absent') == 'absent'
            names = adb('shell', 'ls', '-A', downloads).splitlines()
            assert sorted(names) == sorted(['single.bin', 'empty', 'resumed.bin', 'race-False', 'race-True']), names
            print('PASS: Downloads single/empty/resumed uploads, hashes, conflicts and failed-upload cleanup', flush=True)
    try:
        adb('shell', 'mkdir', '-m', '700', directory)
        created = True
        adb('shell', 'mkdir', downloads)
        if args.baseline_binary:
            asyncio.run(verify(start(args.baseline_binary, 'baseline'), baseline=True))
        asyncio.run(verify(start(args.binary, 'current')))
    finally:
        for binary, state in started:
            adb('shell', f'{binary} service stop --state-dir {state}')
        for port in forwarded:
            adb('forward', '--remove', f'tcp:{port}')
        if created:
            adb('shell', 'rm -rf -- ' + shlex.quote(directory) + ' ' + shlex.quote(downloads))
            assert adb('shell', f'test ! -e {directory} && test ! -e {downloads} && echo removed') == 'removed'
            print('PASS: owned service, forwarding and fixture files removed', flush=True)


if __name__ == '__main__':
    main()
