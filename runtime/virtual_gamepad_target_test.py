"""Opt-in native uinput lifecycle check against an owned temporary Android root service.

Does not change the deployed service, install an APK, or claim game compatibility.
"""
import argparse
import asyncio
import json
import pathlib
import re
import shlex
import subprocess
import uuid

import websockets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', type=pathlib.Path, required=True)
    parser.add_argument('--receiver-apk', type=pathlib.Path, help='Optional owned Android event receiver APK')
    args = parser.parse_args()
    directory = '/data/local/tmp/airc-uinput-test-' + str(uuid.uuid4())
    binary = directory + '/airc-runtime'
    port = None
    started = False
    installed = False
    package = "ernest.airc.mediafixture"

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *command], text=True, timeout=30).strip()

    def root(command):
        return adb('shell', 'su 0 sh -c ' + shlex.quote(command))

    def devices():
        return root('cat /proc/bus/input/devices')

    async def verify(local_port):
        async with websockets.connect(f'ws://127.0.0.1:{local_port}/ws') as socket:
            counter = 0

            async def tool(name, arguments=None, other=None, success=True):
                nonlocal counter
                counter += 1
                channel = other or socket
                await channel.send(json.dumps(dict(id=str(counter), protocol=1, type='tool',
                                                   tool='gamepad.virtual_' + name, arguments=arguments or {})))
                result = json.loads(await asyncio.wait_for(channel.recv(), 5))
                assert result['ok'] == success, result
                return json.loads(result['result']) if success else result

            capabilities = await tool('capabilities')
            assert capabilities['supported'] and capabilities['runtimeUid'] == 0, capabilities
            session = await tool('open')
            identity = dict(id=session['id'])
            name = session['deviceName']
            await asyncio.sleep(1)
            blocks = [block for block in devices().split('\n\n') if f'N: Name="{name}"' in block]
            assert len(blocks) == 1, 'owned kernel registration missing or ambiguous'
            event = re.search(r'\bevent[0-9]+\b', blocks[0])
            assert event is not None, 'owned input event handler missing'
            node = '/dev/input/' + event.group()
            assert name in devices(), 'kernel registration missing'
            assert name in root('dumpsys input'), 'Android input registration missing'
            await tool('open', success=False)
            async with websockets.connect(f'ws://127.0.0.1:{local_port}/ws') as other:
                await tool('close', identity, other, success=False)
                await tool('update', dict(**identity, sequence=1, state={}), other, success=False)
            # Read only the event node resolved from this UUID device. timeout bounds
            # the remote observer, including if the local ADB process loses contact.
            observer = subprocess.Popen(
                ['adb', '-s', args.serial, 'shell',
                 'su 0 sh -c ' + shlex.quote('timeout 8 getevent -t ' + node)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                await asyncio.sleep(.5)
                snapshot = dict(buttons=['a', 'r1'], leftX=.5, leftY=-.25,
                                rightX=.75, rightY=-1, hatX=1, hatY=-1)
                await tool('update', dict(**identity, sequence=1, state=snapshot))
                await tool('update', dict(**identity, sequence=1, state={}), success=False)
                await tool('update', dict(**identity, sequence=2, state=dict(buttons=['power'])), success=False)
                await asyncio.sleep(.8)
                await tool('update', dict(**identity, sequence=2, state=snapshot))
                await asyncio.sleep(2.4)
                # Re-arm after deadman neutrality, then explicitly release.
                await tool('update', dict(**identity, sequence=3, state=dict(buttons=['a'])))
                await asyncio.sleep(.1)
                await tool('update', dict(**identity, sequence=4, state={}))
                output, _ = await asyncio.to_thread(observer.communicate, timeout=12)
                assert observer.returncode in (0, 124), output
                events = [(float(t), int(kind, 16), int(code, 16), int(value, 16))
                          for t, kind, code, value in re.findall(
                              r'\[\s*([0-9.]+)\]\s+(?:/dev/input/event[0-9]+:\s+)?'
                              r'([0-9a-fA-F]{4})\s+([0-9a-fA-F]{4})\s+([0-9a-fA-F]{8})', output)]
                downs = [t for t, kind, code, value in events if (kind, code, value) == (1, 304, 1)]
                ups = [t for t, kind, code, value in events if (kind, code, value) == (1, 304, 0)]
                assert len(downs) == len(ups) == 2, output
                held = ups[0] - downs[0]
                assert 2.6 <= held <= 3.3, (held, output)
                assert .07 <= ups[1] - downs[1] <= .5, output
                expected = {0: 16384, 1: 0xffffe000, 2: 24575,
                            5: 0xffff8001, 16: 1, 17: 0xffffffff}
                for code, value in expected.items():
                    assert any(k == 3 and c == code and v == value for _, k, c, v in events), (code, output)
                    assert any(k == 3 and c == code and v == 0 and downs[0] < t <= ups[0] + .01
                               for t, k, c, v in events), (code, output)
                assert any(k == 1 and c == 311 and v == 0 for _, k, c, v in events), output
                if installed:
                    journal = [json.loads(line) for line in adb('shell', f'run-as {package} cat files/gamepad-events.jsonl').splitlines()]
                    owned = [e for e in journal if e['deviceName'] == name]
                    assert owned and all(e['deviceId'] > 0 for e in owned), journal
                    keys = [e for e in owned if e['kind'] == 'key' and e['code'] == 96 and e['repeat'] == 0]
                    assert [e['action'] for e in keys] == [0, 1, 0, 1], keys
                    assert all(e['source'] & 1025 == 1025 for e in keys), keys
                    motion = [e for e in owned if e['kind'] == 'motion']
                    expected_axes = dict(x=.5, y=-.25, z=.75, rz=-1, hatX=1, hatY=-1)
                    assert any(all(abs(e[k]-v) < .02 for k,v in expected_axes.items()) for e in motion), motion
                    assert any(all(abs(e[k]) < .02 for k in expected_axes) for e in motion), motion
                    assert all(e['source'] & 16777232 == 16777232 for e in motion), motion
                    print('Android receiver observed positive device ID/name, button edges, both sticks/hats and neutrality')
                print(f'Kernel hold/heartbeat/deadman neutral observed after {held:.3f}s; both sticks and hats verified')
            finally:
                if observer.poll() is None:
                    # Remote timeout remains the cleanup guarantee; drain before
                    # removing the service and directory, never leave a reader behind.
                    try:
                        await asyncio.to_thread(observer.communicate, timeout=12)
                    except subprocess.TimeoutExpired:
                        observer.kill()
                        await asyncio.to_thread(observer.communicate)
            await tool('close', identity)
            await asyncio.sleep(.3)
            assert name not in devices(), 'explicit close left kernel device'
            session = await tool('open')
            await tool('update', dict(id=session['id'], sequence=1, state=dict(buttons=['b'])))
        await asyncio.sleep(.3)
        assert session['deviceName'] not in devices(), 'disconnect left kernel device'
        print('Root uinput kernel/Android registration, snapshots, replay/owner rejection and cleanup passed')

    try:
        if args.receiver_apk is not None:
            assert ('package:' + package) not in adb('shell', f'pm list packages --user all {package}').splitlines(), 'Refusing to replace existing fixture'
            adb('install', str(args.receiver_apk.resolve()))
            installed = True
            adb('shell', f'am start -W -n {package}/.GamepadActivity')
            for _ in range(50):
                try:
                    adb('shell', f'run-as {package} test -f files/gamepad-ready')
                    break
                except subprocess.CalledProcessError:
                    import time
                    time.sleep(.1)
            else:
                raise AssertionError('receiver did not obtain focus')
        root('mkdir ' + shlex.quote(directory))
        adb('push', str(args.binary.resolve()), binary)
        root('chmod 700 ' + shlex.quote(binary))
        reply = json.loads(root(f'{binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        started = True
        assert reply['ready'], reply
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(reply['port'])))
        asyncio.run(verify(port))
    finally:
        try:
            if started:
                root(f'{binary} service stop --state-dir {directory}/state')
        finally:
            if port is not None:
                adb('forward', '--remove', 'tcp:' + str(port))
            root('rm -rf -- ' + shlex.quote(directory))
            if installed:
                assert 'Success' in adb('uninstall', package)


if __name__ == '__main__':
    main()
