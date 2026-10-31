"""Verify schedule editing and physical display-off on an explicitly owned emulator."""
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
    parser.add_argument('--binary', required=True, type=Path)
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *command], text=True, timeout=40).strip()

    if not re.fullmatch(r'emulator-\d+', args.serial) or adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        parser.error('Only an explicitly selected owned emulator is supported')
    if adb('shell', 'id', '-u') != '0':
        parser.error('Start the owned emulator with root ADB before running this fixture')
    if not args.binary.is_file():
        parser.error('Build a matching Android runtime binary first')
    directory = '/data/local/tmp/airc-schedule-screen-' + uuid.uuid4().hex
    binary = directory + '/airc-runtime'
    port = None
    bridge = None
    created = started = False

    async def verify():
        sequence = 0

        async def call(socket, kind, arguments, tool=None):
            nonlocal sequence
            sequence += 1
            await socket.send(json.dumps(dict(id=str(sequence), protocol=1, type=kind, arguments=arguments, **({'tool':tool} if tool else {}))))
            reply = json.loads(await asyncio.wait_for(socket.recv(), 30))
            assert reply['id'] == str(sequence) and reply['ok'], reply
            return json.loads(reply['result'])

        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            async def screen(on):
                return await call(socket, 'tool', {'on':on}, 'screen.power')

            try:
                await screen(True)
                task = await call(socket, 'schedule_save', dict(action='pause', kind='delay', delaySeconds=900, confirmed=True))
                task_id = task['id']
                await call(socket, 'schedule_toggle', dict(id=task_id, enabled=False))
                edited = await call(socket, 'schedule_save', dict(id=task_id, action='screen_off', kind='delay', delaySeconds=60, confirmed=True))
                assert edited['id'] == task_id and not edited['enabled'] and edited['delaySeconds'] == 60, edited
                tasks = (await call(socket, 'schedule_list', {}))['tasks']
                assert len(tasks) == 1 and tasks[0]['action'] == 'screen_off', tasks
                accepted = await call(socket, 'schedule_run_once', dict(id=task_id, confirmed=True))
                assert accepted == dict(id=task_id, submitted=True), accepted
                deadline = time.monotonic() + 15
                while True:
                    result = (await call(socket, 'schedule_list', {}))['tasks'][0]
                    if result['lastStatus'] != 'running':
                        break
                    assert time.monotonic() < deadline, result
                    await asyncio.sleep(.2)
                assert result['lastStatus'] == 'submitted' and not result['enabled'] and result['nextAt'] == edited['nextAt'], result
                assert (await call(socket, 'tool', {}, 'screen.power'))['on'] is False
                display = adb('shell', 'dumpsys', 'SurfaceFlinger')
                assert re.search(r'powerMode=Off\b', display), 'Physical display was not OFF'
                assert 'mWakefulness=Awake' in adb('shell', 'dumpsys', 'power')
                print('PASS edited paused task ran once; physical display OFF and shared screen.power mode updated.', flush=True)
                await screen(True)
                await call(socket, 'schedule_toggle', dict(id=task_id, enabled=True, confirmed=True))
                print('Waiting for the original future plan with the controller disconnected.', flush=True)
            except BaseException:
                await screen(True)
                raise
        await asyncio.sleep(max(0, edited['nextAt'] + 2 - time.time()))
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            try:
                result = (await call(socket, 'schedule_list', {}))['tasks'][0]
                assert result['lastStatus'] == 'submitted' and not result['enabled'], result
                assert result['lastAt'] == edited['nextAt'], result
                assert (await call(socket, 'tool', {}, 'screen.power'))['on'] is False
                assert re.search(r'powerMode=Off\b', adb('shell', 'dumpsys', 'SurfaceFlinger'))
                print('PASS target timer physically switched display OFF without a controller.', flush=True)
            finally:
                await call(socket, 'tool', {'on':True}, 'screen.power')
            await call(socket, 'schedule_delete', {'id':task_id})

    try:
        adb('shell', f'mkdir -m 700 {directory}')
        created = True
        adb('push', str(args.binary.resolve()), binary)
        adb('shell', f'chmod 700 {binary}')
        reply = json.loads(adb('shell', f'{binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        started = True
        bridges = adb('shell', f'ls {directory}/state/platform/android-*.dex').splitlines()
        assert len(bridges) == 1, bridges
        bridge = bridges[0]
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(reply['port'])))
        asyncio.run(verify())
    finally:
        errors = []
        commands = []
        if started:
            # Restore through the same bridge even if the RPC connection failed during verification.
            if bridge:
                commands.append(('shell', f'CLASSPATH={shlex.quote(bridge)} app_process /system/bin ernest.airc.bridge.ScreenPowerBridge on'))
            commands.append(('shell', f'{binary} service stop --state-dir {directory}/state'))
        if port:
            commands.append(('forward', '--remove', 'tcp:' + str(port)))
        if created:
            commands.append(('shell', f'rm -rf -- {shlex.quote(directory)}'))
        for command in commands:
            try:
                adb(*command)
            except Exception as error:
                errors.append(str(error))
        if errors:
            raise RuntimeError('Owned fixture cleanup failed: ' + '; '.join(errors))


if __name__ == '__main__':
    main()
