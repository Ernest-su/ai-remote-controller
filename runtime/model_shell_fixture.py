"""Unscoped model shell cannot mutate an owned marker; direct expert calls remain available."""
import asyncio
import json
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import websockets


async def main():
    with tempfile.TemporaryDirectory(prefix='airc-model-shell-') as temporary:
        directory=Path(temporary)
        marker=directory/'owned-marker'
        calls=[]
        class Provider(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                calls.append(request)
                if len(calls)==1:
                    schema=next(tool for tool in request['tools'] if tool['function']['name']=='device_shell')
                    assert 'Read-only' in schema['function']['description']
                    message={'role':'assistant','tool_calls':[{'id':'mutation','type':'function','function':{'name':'device_shell','arguments':json.dumps({'command':f'touch {marker}'})}}]}
                elif len(calls)==2:
                    result=json.loads(request['messages'][-1]['content'])
                    assert result['ok'] is False and 'diagnostic-only' in result['error']
                    assert not marker.exists()
                    message={'role':'assistant','content':'model mutation rejected'}
                elif len(calls) in (3,5):
                    schema=next(tool for tool in request['tools'] if tool['function']['name']=='remote_key')
                    assert 'power' not in schema['function']['parameters']['properties']['key']['enum']
                    message={'role':'assistant','tool_calls':[{'id':'power','type':'function','function':{'name':'remote_key','arguments':json.dumps({'key':'power'})}}]}
                else:
                    result=json.loads(request['messages'][-1]['content'])
                    assert result['ok'] is False and 'direct manual control' in result['error']
                    message={'role':'assistant','content':'model power rejected'}

                data=json.dumps({'choices':[{'message':message}]}).encode()
                self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        provider=ThreadingHTTPServer(('127.0.0.1',0),Provider)
        thread=threading.Thread(target=provider.serve_forever,daemon=True);thread.start()
        (directory/'model.json').write_text(json.dumps({'baseUrl':f'http://127.0.0.1:{provider.server_port}','apiKey':'fixture-key','model':'fixture-model'}))
        (directory/'model.json').chmod(0o600)
        binary=Path('runtime/target/debug/airc-runtime').resolve()
        process=await asyncio.create_subprocess_exec(str(binary),'serve','--state-dir',str(directory),'--port','0','--no-mdns',stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL)
        try:
            line=(await asyncio.wait_for(process.stdout.readline(),10)).decode()
            port=int(line.rstrip().rsplit(':',1)[1])
            async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
                await socket.send(json.dumps({'id':'model','protocol':1,'type':'text','text':'Owned fixture mutation test'}))
                response=json.loads(await asyncio.wait_for(socket.recv(),15))
                assert response['ok'] and response['result']=='model mutation rejected'
                assert not marker.exists() and len(calls)==2
                await socket.send(json.dumps({'id':'model-power','protocol':1,'type':'text','text':'Owned power boundary test'}))
                response=json.loads(await asyncio.wait_for(socket.recv(),15))
                assert response['ok'] and response['result']=='model power rejected'
                assert not marker.exists() and len(calls)==4
                await socket.send(json.dumps({'id':'scoped-power','protocol':1,'type':'text','text':'Owned scoped power boundary test','arguments':{'allowedTools':['remote.key']}}))
                response=json.loads(await asyncio.wait_for(socket.recv(),15))
                assert response['ok'] and response['result']=='model power rejected'
                assert not marker.exists() and len(calls)==6

                await socket.send(json.dumps({'id':'direct','protocol':1,'type':'tool','tool':'device.shell','arguments':{'command':f'touch {marker}'}}))
                response=json.loads(await asyncio.wait_for(socket.recv(),10))
                assert response['ok'] and marker.exists()
            print('PASS: default model mutation/power rejected; explicit direct expert command preserved')
        finally:
            process.terminate();await asyncio.wait_for(process.wait(),10)
            provider.shutdown();provider.server_close();thread.join(timeout=5)


if __name__=='__main__':asyncio.run(main())
