"""Owned host server: malicious provider tool choice cannot bypass a request tool scope."""
import asyncio
import json
import pathlib
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.request
import websockets

async def main():
    with tempfile.TemporaryDirectory(prefix='airc-model-scope-') as temporary:
        directory=pathlib.Path(temporary)
        marker=directory/'must-not-exist'
        calls=[]
        class Provider(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                size=int(self.headers['Content-Length']);assert size<=1024*1024
                request=json.loads(self.rfile.read(size));calls.append(request)
                assert [tool['function']['name'] for tool in request['tools']]==['device_info']
                if len(calls)==1:
                    message={'role':'assistant','tool_calls':[{'id':'denied','type':'function','function':{'name':'device_shell','arguments':json.dumps({'command':f'touch {marker}'})}}]}
                else:
                    result=json.loads(request['messages'][-1]['content'])
                    assert result['ok'] is False and 'outside this request scope' in result['error']
                    message={'role':'assistant','content':'scope enforced'}
                data=json.dumps({'choices':[{'message':message}]}).encode()
                self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        provider=ThreadingHTTPServer(('127.0.0.1',0),Provider)
        thread=threading.Thread(target=provider.serve_forever,daemon=True);thread.start()
        config={'baseUrl':f'http://127.0.0.1:{provider.server_port}','apiKey':'fixture-key','model':'fixture-model'}
        (directory/'model.json').write_text(json.dumps(config));(directory/'model.json').chmod(0o600)
        binary=pathlib.Path('runtime/target/debug/airc-runtime').resolve()
        process=await asyncio.create_subprocess_exec(str(binary),'serve','--state-dir',str(directory),'--port','0','--no-mdns',stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL)
        try:
            line=(await asyncio.wait_for(process.stdout.readline(),10)).decode()
            port=int(line.rstrip().rsplit(':',1)[1])
            status=json.load(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(f'http://127.0.0.1:{port}/status'))
            assert status['textToolScope'] is True
            async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
                await socket.send(json.dumps({'id':'scope','protocol':1,'type':'text','progress':True,'text':'Readonly fixture','arguments':{'allowedTools':['device.info']}}))
                while True:
                    response=json.loads(await asyncio.wait_for(socket.recv(),10))
                    if response.get('type')=='assistant.state':
                        assert response['phase']!='tool','Rejected tool must not be reported as executed'
                        continue
                    assert response['ok'] and response['result']=='scope enforced',response
                    break
            assert len(calls)==2 and not marker.exists()
            print('request scope: schema filtered; out-of-scope shell blocked; no side effect')
        finally:
            process.terminate();await asyncio.wait_for(process.wait(),10)
            provider.shutdown();provider.server_close();thread.join(timeout=5)

if __name__=='__main__': asyncio.run(main())
