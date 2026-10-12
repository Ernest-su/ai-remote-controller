"""Owned host service: malformed call groups have no effects or orphan history."""
import asyncio
import json
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import websockets


async def main():
    with tempfile.TemporaryDirectory(prefix='airc-call-group-') as temporary:
        directory = Path(temporary)
        requests = []
        failures = []

        class Provider(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                try:
                    request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                    requests.append(request)
                    assert not any(message.get('tool_calls') for message in request['messages'])
                    assert not any(message.get('role') == 'tool' for message in request['messages'])
                    assert not (directory / 'files' / 'must-not-exist').exists()
                    if len(requests) % 2:
                        first = {'id': 'first', 'type': 'function', 'function': {
                            'name': 'files_mkdir', 'arguments': json.dumps({
                                'root': 'inbox', 'path': 'must-not-exist'})}}
                        second = json.loads(json.dumps(first))
                        case = (len(requests) - 1) // 2
                        if case == 0:
                            second.pop('id')
                        elif case == 1:
                            second['id'] = ' '
                        elif case == 3:
                            second['id'] = 'second'
                            second['function'].pop('arguments')
                        elif case == 4:
                            second['id'] = 'second'
                            second['type'] = 'unsupported'
                        message = {'role': 'assistant', 'tool_calls': [first, second]}
                        if case == 5:
                            message = {'role': 'user', 'content': 'must not be accepted'}
                        elif case == 6:
                            message = {'role': 'assistant', 'content': 'must not be accepted', 'tool_calls': {}}
                        elif case == 7:
                            message = {'role': 'assistant', 'content': 'must not be accepted', 'tool_calls': 'wrong'}
                    else:
                        message = {'role': 'assistant', 'content': 'history remains valid'}
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
        config = directory / 'model.json'
        config.write_text(json.dumps({'baseUrl': f'http://127.0.0.1:{provider.server_port}',
                                     'apiKey': 'fixture-key', 'model': 'fixture-model'}))
        config.chmod(0o600)
        binary = Path('runtime/target/debug/airc-runtime').resolve()
        process = None
        try:
            process = await asyncio.create_subprocess_exec(
                str(binary), 'serve', '--state-dir', str(directory), '--port', '0', '--no-mdns',
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            line = (await asyncio.wait_for(process.stdout.readline(), 10)).decode()
            port = int(line.rstrip().rsplit(':', 1)[1])
            async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
                for case in range(8):
                    for recovery in (False, True):
                        await socket.send(json.dumps({'id': f'{case}-{recovery}', 'protocol': 1,
                                                      'type': 'text', 'text': 'Owned group test'}))
                        response = json.loads(await asyncio.wait_for(socket.recv(), 15))
                        assert response['ok'] is recovery, response
                        if recovery:
                            assert response['result'] == 'history remains valid'
                        else:
                            assert any(word in response['error'] for word in ('ID', 'arguments', 'type', 'role', 'tool_calls')), response
                        assert not (directory / 'files' / 'must-not-exist').exists()
            assert not failures, failures
            assert len(requests) == 16
            print('PASS: eight malformed call/message cases rejected; sixteen requests recover in same session')
        finally:
            if process is not None and process.returncode is None:
                process.terminate()
                await asyncio.wait_for(process.wait(), 10)
            provider.shutdown()
            provider.server_close()
            thread.join(timeout=5)


if __name__ == '__main__':
    asyncio.run(main())
