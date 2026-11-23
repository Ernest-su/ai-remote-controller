#!/usr/bin/env python3
"""Real uinput mouse delivery; owned runtime/receiver only, restores target Activity."""
import argparse
import asyncio
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import websockets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--receiver-apk', type=Path, required=True)
    parser.add_argument('--right-button',choices=['secondary','back'],default='secondary',help='Explicit expected target firmware mapping; never auto-detect a passing expectation')
    args = parser.parse_args()
    package = 'ernest.airc.inputfixture'
    directory = '/data/local/tmp/airc-mouse-' + str(uuid.uuid4())
    binary = directory + '/airc-runtime'
    def adb(*command, timeout=45):
        return subprocess.check_output(['adb','-s',args.serial,*command],stderr=subprocess.STDOUT,timeout=timeout).decode()
    def shell(command):
        return adb('shell',command)
    if shell('id -u').strip() != '0':
        parser.error('Explicit root ADB target required; this script does not change root configuration')
    if f'package:{package}' in adb('shell','pm','list','packages','--user','all',package).splitlines():
        parser.error('Refusing to replace an existing receiver')
    abi = shell('getprop ro.product.cpu.abi').strip()
    if args.binary.name != f'airc-runtime-{abi}' or not args.binary.is_file() or not args.receiver_apk.is_file():
        parser.error('Build the matching ABI runtime and inputFixture first')
    match = re.search(r'(?:mResumedActivity|topResumedActivity).*? ([\w.]+/[\w.$]+)',shell('dumpsys activity activities'))
    original = match.group(1) if match else None
    installed = created = False
    forward = None
    def events():
        return [json.loads(line) for line in shell('content read --uri content://ernest.airc.inputfixture.journal/events').splitlines() if line.strip()]
    async def wait_events(since, predicate):
        deadline = time.monotonic()+6
        while True:
            found = [e for e in (await asyncio.to_thread(events))[since:] if e.get('kind')=='mouse']
            if predicate(found): return found
            assert time.monotonic()<deadline, f'Missing actual mouse delivery: {found}'
            await asyncio.sleep(.05)
    async def exercise(port):
        uri=f'ws://127.0.0.1:{port}/ws'
        serial=0
        call_lock=asyncio.Lock()
        async def call(ws,tool,arguments,ok=True):
            nonlocal serial
            async with call_lock:
                serial+=1
                await ws.send(json.dumps(dict(id=str(serial),protocol=1,type='tool',tool=tool,arguments=arguments)))
                reply=json.loads(await asyncio.wait_for(ws.recv(),5))
                assert reply['ok']==ok, reply
                return json.loads(reply['result']) if ok else reply
        async with websockets.connect(uri) as ws:
            assert (await call(ws,'mouse.capabilities',{}))['supported']
            opened=await call(ws,'mouse.open',{}); identifier=opened['id']; seq=0
            await asyncio.sleep(.5)
            held=0
            frame_lock=asyncio.Lock()
            async def frame(dx=0,dy=0,wheel=0,buttons=None):
                nonlocal seq,held
                async with frame_lock:
                    seq+=1
                    if buttons is not None: held=buttons
                    return await call(ws,'mouse.update',dict(id=identifier,sequence=seq,frame=dict(dx=dx,dy=dy,wheel=wheel,buttons=held)))
            async def keepalive():
                while True:
                    await asyncio.sleep(.25)
                    await frame()
            heartbeat=asyncio.create_task(keepalive())
            # Place this virtual pointer inside the receiver before testing any clicks.
            await frame(-2048,-2048); await frame(100,100)
            await wait_events(0,lambda ev:any(e['action'] in (7,9) for e in ev))
            for mask in (1,2,4):
                start=len(await asyncio.to_thread(events)); await frame(buttons=mask); await frame(buttons=0)
                if mask==2 and args.right_button=='back':
                    delivered=await wait_events(start,lambda ev:any(e['buttons']==8 for e in ev) and any(e['buttons']==0 for e in ev))
                    keys=[e for e in (await asyncio.to_thread(events))[start:] if e.get('code')==4 and e['source']&8194==8194]
                    assert [e['action'] for e in keys]==[0,1], f'Expected actual mouse BACK down/up: {keys}'
                    print('PASS firmware right-button mapping: native BACK down/up and mouse button mask 8',flush=True)
                else:
                    delivered=await wait_events(start,lambda ev:any(e['action']==12 and e['button']==mask for e in ev))
                    assert any(e['action']==11 and e['button']==mask for e in delivered), delivered
                assert all(e['source']&8194==8194 and e['deviceName']==opened['deviceName'] for e in delivered), delivered
                print(f'PASS actual mouse button {mask}: verified target mapping from registered device',flush=True)
            start=len(await asyncio.to_thread(events)); await frame(buttons=1); await frame(20,15,buttons=1); await frame(buttons=0)
            await wait_events(start,lambda ev:any(e['action']==2 and e['buttons']==1 for e in ev) and any(e['action']==1 for e in ev))
            print('PASS actual left-button drag',flush=True)
            start=len(await asyncio.to_thread(events)); await frame(wheel=1); await frame(wheel=-1)
            await wait_events(start,lambda ev:any(e['action']==8 and e['wheel']>0 for e in ev) and any(e['action']==8 and e['wheel']<0 for e in ev))
            print('PASS actual native wheel in both directions',flush=True)
            start=len(await asyncio.to_thread(events))
            await call(ws,'mouse.update',dict(id=identifier,sequence=seq,frame=dict(dx=50,dy=0,wheel=0,buttons=1)),ok=False)
            await asyncio.sleep(.15); assert len(await asyncio.to_thread(events))==start,'duplicate frame replayed input'
            async with websockets.connect(uri) as other:
                await call(other,'mouse.open',{},ok=False)
                await call(other,'mouse.close',dict(id=identifier),ok=False)
            print('PASS duplicate sequence and foreign owner rejected',flush=True)
            start=len(await asyncio.to_thread(events)); await frame(buttons=1)
            heartbeat.cancel()
            try: await heartbeat
            except asyncio.CancelledError: pass
        await wait_events(start,lambda ev:any(e['action']==1 and e['buttons']==0 for e in ev))
        print('PASS disconnect releases held button',flush=True)
        async with websockets.connect(uri) as ws:
            opened=await call(ws,'mouse.open',{}); identifier=opened['id']; await asyncio.sleep(.5)
            await call(ws,'mouse.update',dict(id=identifier,sequence=1,frame=dict(dx=0,dy=0,wheel=0,buttons=4)))
            start=len(await asyncio.to_thread(events))
            await wait_events(start,lambda ev:any(e['action']==12 and e['button']==4 for e in ev))
            await call(ws,'mouse.update',dict(id=identifier,sequence=2,frame=dict(dx=10,dy=0,wheel=0,buttons=4)),ok=False)
            print('PASS deadman releases and expires session; late input cannot revive it',flush=True)
    try:
        shell(f'mkdir -m 700 {directory}'); created=True
        adb('push',str(args.binary),binary)
        adb('install',str(args.receiver_apk)); installed=True
        service=json.loads(shell(f'chmod 700 {binary} && {binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        assert service.get('ready') and service.get('running'),service
        shell(f'am start -W -n {package}/.InputActivity')
        deadline=time.monotonic()+10
        while True:
            try:
                output=shell('content read --uri content://ernest.airc.inputfixture.journal/ready')
                if output.strip(): raise subprocess.CalledProcessError(1,'readiness provider')
                break
            except subprocess.CalledProcessError:
                assert time.monotonic()<deadline,'receiver did not gain focus'; time.sleep(.1)
        forward=int(adb('forward','tcp:0',f'tcp:{service["port"]}').strip())
        asyncio.run(exercise(forward))
    finally:
        try:
            if created:
                shell(f'if [ -x {binary} ]; then {binary} service stop --state-dir {directory}/state; fi')
                shell(f'rm -rf -- {directory}')
        finally:
            if forward: adb('forward','--remove',f'tcp:{forward}')
            if installed: adb('uninstall',package)
            if original:
                resumed=re.findall(r'(?:mResumedActivity|topResumedActivity).*? ([\w.]+/[\w.$]+)',shell('dumpsys activity activities'))
                if original not in resumed: shell('am start -n '+shlex.quote(original))


if __name__ == '__main__':
    main()
