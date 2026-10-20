"""Validate bounded file-link reads on an explicitly selected Android emulator; only UUID files are changed."""
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
        parser.error('Use an owned Android emulator; this fixture does not select a physical target')
    abi = adb('shell', 'getprop', 'ro.product.cpu.abi')
    marker = {'x86_64': 'x86_64', 'arm64-v8a': 'aarch64', 'armeabi-v7a': 'armv7'}.get(abi)
    if marker is None or marker not in args.binary.name:
        parser.error(f'Binary name does not match target ABI: {abi}')
    directory = '/data/local/tmp/airc-file-links-' + uuid.uuid4().hex
    binary = directory + '/airc-runtime'
    state = directory + '/state'
    inbox = state + '/files'
    port = None
    created = started = False
    payload = 'linked fixture 中文\n'.encode()

    def link(target, name):
        return adb('shell', 'ln -s ' + shlex.quote(target) + ' ' + shlex.quote(inbox + '/' + name))

    async def verify():
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            count = 0

            async def request(tool=None, arguments=None, kind='tool', success=True):
                nonlocal count
                count += 1
                await socket.send(json.dumps(dict(id=str(count), protocol=1, type=kind, tool=tool, arguments=arguments or {})))
                while True:
                    response = json.loads(await asyncio.wait_for(socket.recv(), 30))
                    if response.get('id') == str(count):
                        break
                assert response['ok'] == success, response
                return json.loads(response['result']) if success else response

            async def tool(name, path='', **values):
                return await request(name, dict(root='inbox', path=path, **values))

            async def finished(task):
                for _ in range(100):
                    if task['phase'] in ('ready', 'completed', 'failed', 'cancelled'):
                        assert task['phase'] in ('ready', 'completed'), task
                        return task
                    await asyncio.sleep(0.05)
                    task = await request('task.progress', dict(id=task['id']))
                raise AssertionError('Task did not finish')

            def read(path, ranged=False):
                query = urllib.request.Request(f'http://127.0.0.1:{port}{path}', headers={'Range': f'bytes=0-{len(payload)-1}'} if ranged else {})
                with opener.open(query, timeout=20) as response:
                    return response.read()

            page = await tool('files.list')
            entries = {entry['name']: entry for entry in page['entries']}
            assert not page['readOnly']
            assert entries['linked-dir']['kind'] == 'directory' and entries['linked-dir']['symlink']
            assert entries['linked-file.txt']['kind'] == 'file' and entries['linked-file.txt']['symlink']
            for name, reason in [('broken', 'link_missing'), ('cycle', 'link_loop'), ('outside', 'link_outside_root')]:
                assert entries[name]['linkError'] == reason, entries[name]
            assert (await tool('files.list', 'linked-dir'))['readOnly']
            info = await tool('files.stat', 'linked-file.txt')
            assert info['linkTarget'] == 'real/payload.txt' and info['sizeBytes'] == len(payload)
            assert info['targetName'] == 'payload.txt'
            preview = await tool('files.preview', 'linked-file.txt')
            assert read(preview['dataPath'], ranged=True) == payload
            # A preview must stay on the opened inode after its link is replaced with an escape.
            adb('shell', 'rm', inbox + '/linked-file.txt')
            link(directory + '/outside/keep.txt', 'linked-file.txt')
            assert read(preview['dataPath'], ranged=True) == payload
            await request('files.preview', dict(root='inbox', path='linked-file.txt'), success=False)
            await request('files.preview_close', dict(id=preview['id']))
            download = await finished(await request('task.start', dict(root='inbox', direction='download', path='linked-dir/payload.txt')))
            assert download['sha256'] == hashlib.sha256(payload).hexdigest()
            assert read(download['dataPath']) == payload
            for name, values in [
                ('files.mkdir', dict(path='linked-dir/new')),
                ('task.start', dict(direction='upload', path='linked-dir/new', sizeBytes=0, sha256=hashlib.sha256(b'').hexdigest())),
                ('files.prepare_overwrite', dict(path='linked-file.txt')),
                ('storage.analyze', dict(path='linked-dir')),
            ]:
                await request(name, dict(root='inbox', **values), success=False)
            await request('files.stat', dict(root='full', path='proc/self/fd/0'), success=False)
            proposal = await tool('files.delete', 'linked-dir', recursive=False)
            assert proposal['symlink'] and not proposal['directory']
            await finished(await request(arguments=dict(id=proposal['id'], accepted=True), kind='approve'))
            assert not any(entry['name'] == 'linked-dir' for entry in (await tool('files.list'))['entries'])
            assert (await tool('files.stat', 'real/payload.txt'))['sizeBytes'] == len(payload)
            assert adb('shell', 'cat', directory + '/outside/keep.txt') == 'outside-preserved'
            print('PASS: Android bounded link listing/stat/preview/range/download, pinned preview after retarget, rejected writes/scans/magic links, and link-only deletion', flush=True)

    try:
        adb('shell', 'mkdir', '-m', '700', directory)
        created = True
        adb('push', args.binary.resolve(), binary)
        adb('shell', 'chmod', '700', binary)
        digest = adb('shell', 'sha256sum', binary).split()[0]
        assert digest == hashlib.sha256(args.binary.read_bytes()).hexdigest()
        started = True
        reply = json.loads(adb('shell', f'{binary} service start --state-dir {state} --port 0 --no-mdns'))
        assert reply['ready'] and reply['running'], reply
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(reply['port'])))
        adb('shell', 'mkdir', '-p', inbox + '/real', directory + '/outside')
        with tempfile.TemporaryDirectory(prefix='airc-file-links-') as temp:
            source = Path(temp) / 'payload.txt'
            source.write_bytes(payload)
            adb('push', source, inbox + '/real/payload.txt')
            source.write_bytes(b'outside-preserved')
            adb('push', source, directory + '/outside/keep.txt')
        for target, name in [('real', 'linked-dir'), ('real/payload.txt', 'linked-file.txt'), ('missing', 'broken'), ('cycle', 'cycle'), (directory + '/outside/keep.txt', 'outside')]:
            link(target, name)
        asyncio.run(verify())
    finally:
        failures = []
        cleanup = []
        if started:
            cleanup.append(('shell', f'{binary} service stop --state-dir {state}'))
        if port:
            cleanup.append(('forward', '--remove', f'tcp:{port}'))
        if created:
            cleanup.append(('shell', 'rm -rf -- ' + shlex.quote(directory)))
        for command in cleanup:
            try:
                adb(*command)
            except (subprocess.SubprocessError, OSError) as error:
                failures.append(str(error))
        if failures:
            raise RuntimeError('Fixture cleanup failed: ' + '; '.join(failures))
        if created:
            assert adb('shell', f'test ! -e {directory} && echo removed') == 'removed'
            print('PASS: isolated runtime, owned files and ADB forwarding removed; existing apps/services untouched', flush=True)


if __name__ == '__main__':
    main()
