#!/usr/bin/env python3
"""Android binary model guard with owned input shim; never inject real power keys."""
import argparse
import asyncio
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import threading
import uuid
import zipfile
import websockets

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True)
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.target, *command], text=True, timeout=60)

    def root(command):
        return adb('shell', f'su 0 sh -c {shlex.quote(command)}')

    assert adb('get-state').strip() == 'device'
    assert root('id -u').strip() == '0'
    status_command = '/data/local/tmp/airc/airc-runtime service status --state-dir /data/local/tmp/airc/state'
    before = json.loads(adb('shell', status_command))
    abi = adb('shell', 'getprop ro.product.cpu.abi').strip()
    assert abi in ('armeabi-v7a', 'arm64-v8a', 'x86_64')
    asset = ROOT / 'androidApp/src/main/assets/runtime' / f'airc-runtime-{abi}'
    with zipfile.ZipFile(ROOT / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk') as archive:
        assert hashlib.sha256(asset.read_bytes()).digest() == hashlib.sha256(archive.read(f'assets/runtime/airc-runtime-{abi}')).digest()
    directory = f'/data/local/tmp/airc-power-guard-{uuid.uuid4()}'
    binary = f'{directory}/airc-runtime'
    marker = f'{directory}/input-called'
    calls = []
    failures = []

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                calls.append(request)
                if len(calls) % 2:
                    schema = next(tool for tool in request['tools'] if tool['function']['name'] == 'remote_key')
                    assert 'power' not in schema['function']['parameters']['properties']['key']['enum']
                    message = {'role': 'assistant', 'tool_calls': [{'id': 'power', 'type': 'function', 'function': {'name': 'remote_key', 'arguments': '{"key":"power"}'}}]}
                else:
                    result = json.loads(request['messages'][-1]['content'])
                    assert result['ok'] is False and 'direct manual control' in result['error']
                    message = {'role': 'assistant', 'content': 'power rejected'}
                data = json.dumps({'choices': [{'message': message}]}).encode()
                self.send_response(200)
            except Exception as error:
                failures.append(repr(error))
                data = b'{}'
                self.send_response(500)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    provider = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
    thread = threading.Thread(target=provider.serve_forever, daemon=True)
    thread.start()
    created = copied = False
    reverse = forward = None
    try:
        reverse = int(adb('reverse', 'tcp:0', f'tcp:{provider.server_port}').strip())
        adb('shell', f'mkdir -m 700 {directory}')
        created = True
        adb('push', str(asset), binary)
        copied = True
        with tempfile.TemporaryDirectory(prefix='airc-power-guard-') as temporary:
            local = Path(temporary)
            (local / 'input').write_text(f'#!/system/bin/sh\nprintf called >> {marker}\nprintf shim\n')
            (local / 'model.json').write_text(json.dumps({'baseUrl': f'http://127.0.0.1:{reverse}', 'apiKey': 'fixture-only', 'model': 'fixture'}))
            adb('push', str(local / 'input'), f'{directory}/input')
            adb('push', str(local / 'model.json'), f'{directory}/model.json')
        root(f'mkdir -m 700 {directory}/bin {directory}/state && mv {directory}/input {directory}/bin/input && mv {directory}/model.json {directory}/state/model.json && chmod 700 {binary} {directory}/bin/input && chmod 600 {directory}/state/model.json')
        service = json.loads(root(f'PATH={directory}/bin:/system/bin:/system/xbin {binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        assert service['ready']
        forward = int(adb('forward', 'tcp:0', f'tcp:{service["port"]}').strip())

        async def check():
            async with websockets.connect(f'ws://127.0.0.1:{forward}/ws') as socket:
                async def request(identifier, **body):
                    await socket.send(json.dumps({'id': identifier, 'protocol': 1, **body}))
                    response = json.loads(await asyncio.wait_for(socket.recv(), 30))
                    assert response['ok'], response.get('error')
                    return response['result']

                resolved = await request('shim-path', type='tool', tool='device.shell', arguments={'command': 'command -v input'})
                assert resolved.strip() == f'{directory}/bin/input'
                for index, scope in enumerate(({}, {'allowedTools': ['remote.key']})):
                    assert await request(str(index), type='text', text='Owned power boundary fixture', arguments=scope) == 'power rejected'
                    assert root(f'if [ -e {marker} ]; then echo called; fi').strip() == ''
                assert len(calls) == 4 and not failures, failures

        asyncio.run(check())
        print('PASS: Android binary rejects default/scoped model power; owned input shim never called')
    finally:
        try:
            if copied:
                root(f'{binary} service stop --state-dir {directory}/state')
        finally:
            try:
                try:
                    if forward is not None:
                        adb('forward', '--remove', f'tcp:{forward}')
                finally:
                    try:
                        if reverse is not None:
                            adb('reverse', '--remove', f'tcp:{reverse}')
                    finally:
                        if created:
                            root(f'rm -rf -- {directory}')
                            assert not root(f'if [ -e {directory} ]; then echo remains; fi').strip()
            finally:
                provider.shutdown()
                provider.server_close()
                thread.join(timeout=5)
                after = json.loads(adb('shell', status_command))
                assert (before.get('running'), before.get('pid')) == (after.get('running'), after.get('pid'))
                print('Owned resources removed; original main service state unchanged')


if __name__ == '__main__':
    main()
