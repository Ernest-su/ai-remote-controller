"""Verify AAC MIME routing and actual silent playback on an owned Android emulator.

Requires Python av + websockets and the disposable mediaFixture APK. Only UUID-owned
files/services and an initially absent fixture package are created and removed.
"""
import argparse
import asyncio
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid

import av
import websockets


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--binary',type=Path,required=True)
    parser.add_argument('--fixture-apk',type=Path,required=True)
    args=parser.parse_args()

    def adb(*command):
        return subprocess.check_output(['adb','-s',args.serial,*map(str,command)],text=True,stderr=subprocess.STDOUT,timeout=60).strip()

    if adb('shell','getprop','ro.kernel.qemu')!='1':
        parser.error('An owned Android emulator is required')
    package='ernest.airc.mediafixture'
    if f'package:{package}' in adb('shell','pm','list','packages','--user','all',package).splitlines():
        parser.error('Refusing to replace an existing fixture package')
    marker={'x86_64':'x86_64','arm64-v8a':'aarch64','armeabi-v7a':'armv7'}.get(adb('shell','getprop','ro.product.cpu.abi'))
    if not marker or marker not in args.binary.name:
        parser.error('Binary name does not match emulator ABI')
    token=uuid.uuid4().hex
    directory='/data/local/tmp/airc-aac-'+token
    downloads='/sdcard/Download/airc-aac-'+token
    relative='airc-aac-'+token
    binary=directory+'/airc-runtime'
    state=directory+'/state'
    created=installed=started=False
    port=None

    async def verify():
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            number=0
            async def request(tool,arguments):
                nonlocal number
                number+=1
                await socket.send(json.dumps(dict(id=str(number),protocol=1,type='tool',tool=tool,arguments=arguments)))
                while True:
                    response=json.loads(await asyncio.wait_for(socket.recv(),30))
                    if response.get('id')==str(number):break
                assert response['ok'],response
                return json.loads(response['result'])

            async def playing():
                for _ in range(60):
                    result=await request('media.status',{})
                    own=[s for s in result['sessions'] if s['packageName']==package]
                    if any(s['state']==3 and (s.get('positionMs') or 0)>500 for s in own):return
                    assert not any(s['state']==7 for s in own),own
                    await asyncio.sleep(.25)
                raise AssertionError('Fixture did not report active decoded AAC playback')

            for name in ['silent.aac','UPPER.AAC']:
                values=dict(root='downloads',path=relative+'/'+name)
                players=await request('media.players',values)
                assert players['mimeType']=='audio/aac' and package in players['packages'],players
                adb('shell','am','force-stop',package)
                opened=await request('media.open',dict(**values,mimeType='audio/aac',package=package))
                assert opened['launched'] and not opened['playbackVerified'] and opened['transport']=='file',opened
                await playing()
            # The same MIME must pass HTTP probing and private-file publication too.
            stream=await request('media.players',dict(root='inbox',path='silent.aac',transport='http'))
            assert stream['mimeType']=='audio/aac' and stream['transport']=='http',stream
            local=await request('media.players',dict(root='inbox',path='silent.aac',transport='local-copy'))
            assert local['mimeType']=='audio/aac' and package in local['packages'],local
            task=await request('media.publish',dict(root='inbox',path='silent.aac'))
            copy=None
            try:
                for _ in range(100):
                    if task['phase'] in ['completed','failed','cancelled']:break
                    await asyncio.sleep(.05)
                    task=await request('task.progress',dict(id=task['id']))
                assert task['phase']=='completed',task
                copy=task['result']
                assert copy['path'].endswith('.aac'),copy
                adb('shell','am','force-stop',package)
                opened=await request('media.open',dict(root=copy['root'],path=copy['path'],mimeType='audio/aac',package=package))
                assert opened['launched'] and opened['transport']=='file',opened
                await playing()
            finally:
                if copy:
                    await request('media.release',dict(id=copy['id']))
                    assert adb('shell',f'test ! -e /sdcard/Download/{shlex.quote(copy["path"])} && echo removed')=='removed'
            print('PASS: AAC/uppercase MIME routing, HTTP probe, local-copy publication and decoded MediaPlayer playback',flush=True)
    try:
        adb('shell','mkdir','-m','700',directory)
        created=True
        adb('shell','mkdir','-p',downloads)
        adb('push',args.binary.resolve(),binary)
        adb('shell','chmod','700',binary)
        assert adb('shell','sha256sum',binary).split()[0]==hashlib.sha256(args.binary.read_bytes()).hexdigest()
        assert 'Success' in adb('install',args.fixture_apk.resolve())
        installed=True
        adb('shell','pm','grant',package,'android.permission.READ_EXTERNAL_STORAGE')
        started=True
        reply=json.loads(adb('shell',f'{binary} service start --state-dir {state} --port 0 --no-mdns'))
        assert reply['running'] and reply['ready'],reply
        port=int(adb('forward','tcp:0','tcp:'+str(reply['port'])))
        adb('shell','mkdir','-p',state+'/files')
        with tempfile.TemporaryDirectory(prefix='airc-aac-') as temp:
            audio=Path(temp)/'silent.aac'
            with av.open(str(audio),'w',format='adts') as container:
                stream=container.add_stream('aac',rate=44100)
                stream.layout='mono'
                stream.bit_rate=32000
                for index in range(20*44100//1024):
                    frame=av.AudioFrame(format='fltp',layout='mono',samples=1024)
                    frame.sample_rate=44100
                    frame.pts=index*1024
                    frame.time_base=Fraction(1,44100)
                    for plane in frame.planes:plane.update(bytes(plane.buffer_size))
                    for packet in stream.encode(frame):container.mux(packet)
                for packet in stream.encode(None):container.mux(packet)
            for target in [downloads+'/silent.aac',downloads+'/UPPER.AAC',state+'/files/silent.aac']:
                adb('push',audio,target)
        asyncio.run(verify())
    finally:
        if installed:
            adb('shell','am','force-stop',package)
            assert 'Success' in adb('uninstall',package)
        if started:adb('shell',f'{binary} service stop --state-dir {state}')
        if port:adb('forward','--remove',f'tcp:{port}')
        if created:
            adb('shell','rm -rf -- '+shlex.quote(directory)+' '+shlex.quote(downloads))
            assert adb('shell',f'test ! -e {directory} && test ! -e {downloads} && echo removed')=='removed'
            print('PASS: owned files, fixture app, service and ADB forwarding removed',flush=True)


if __name__=='__main__':main()
