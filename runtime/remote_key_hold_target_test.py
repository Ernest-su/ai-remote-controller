#!/usr/bin/env python3
"""Verify actual Android key edges/repeats with an owned runtime and disposable receiver."""
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

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--receiver-apk', type=Path, required=True)
    args = parser.parse_args()
    package = 'ernest.airc.inputfixture'
    directory = '/data/local/tmp/airc-key-hold-' + str(uuid.uuid4())
    binary = directory + '/airc-runtime'

    def adb(*command, timeout=30):
        return subprocess.check_output(['adb', '-s', args.serial, *command], timeout=timeout, stderr=subprocess.STDOUT).decode()

    def shell(command):
        return adb('shell', command)

    if f'package:{package}' in adb('shell', 'pm', 'list', 'packages', '--user', 'all', package).splitlines():
        parser.error('Refusing to replace an existing input receiver')
    abi = shell('getprop ro.product.cpu.abi').strip()
    if abi not in ('x86_64', 'arm64-v8a', 'armeabi-v7a') or args.binary.name != f'airc-runtime-{abi}':
        parser.error('Supply the runtime asset matching the target ABI')
    if not args.binary.is_file() or not args.receiver_apk.is_file():
        parser.error('Build the matching runtime and inputFixture APK first')
    activity = shell('dumpsys activity activities')
    match = re.search(r'(?:mResumedActivity|topResumedActivity).*? ([\w.]+/[\w.$]+)', activity)
    original = match.group(1) if match else None
    installed = created = paused = False
    service = None
    forward = None

    def events():
        output = shell('content read --uri content://ernest.airc.inputfixture.journal/events')
        return [json.loads(line) for line in output.splitlines() if line.strip()]

    async def received(code, since):
        deadline = time.monotonic() + 5
        while True:
            result = [event for event in events()[since:] if event['code'] == code]
            if result and result[-1]['action'] == 1:
                return result
            if time.monotonic() >= deadline:
                raise AssertionError(f'No actual UP for code {code}: {result}')
            await asyncio.sleep(.05)

    async def exercise(port):
        nonlocal paused
        uri = f'ws://127.0.0.1:{port}/ws'
        async with websockets.connect(uri) as socket:
            sequence = 0

            async def edge(key, action, identifier, expect_ok=True):
                nonlocal sequence
                sequence += 1
                await socket.send(json.dumps(dict(id=str(sequence), protocol=1, type='tool', tool='remote.key',
                                                 arguments=dict(key=key, action=action, pressId=identifier))))
                reply = json.loads(await asyncio.wait_for(socket.recv(), 5))
                assert reply['id'] == str(sequence), reply
                assert reply['ok'] == expect_ok, reply
                return reply

            start = len(events()); sequence += 1
            await socket.send(json.dumps(dict(id=str(sequence), protocol=1, type='tool', tool='remote.key', arguments=dict(key='menu'))))
            assert json.loads(await socket.recv())['ok']
            short = await received(82, start)
            assert [(event['action'], event['repeat']) for event in short] == [(0, 0), (1, 0)], short
            assert short[0]['downTime'] == short[1]['downTime']
            print('PASS finite accessibility/queued short tap remains one DOWN/UP pair', flush=True)

            for key, code in [('menu', 82), ('ok', 23), ('back', 4), ('right', 22), ('delete', 67), ('1', 8)]:
                identifier = str(uuid.uuid4()); start = len(events())
                await edge(key, 'down', identifier)
                for _ in range(4):
                    await asyncio.sleep(.2)
                    await edge(key, 'keep', identifier)
                await edge(key, 'up', identifier)
                result = await received(code, start)
                assert result[0]['action'] == 0 and result[0]['repeat'] == 0, result
                assert len({event['downTime'] for event in result}) == 1, result
                repeats = result[1:-1]
                assert repeats and all(event['action'] == 0 for event in repeats), result
                assert [event['repeat'] for event in repeats] == list(range(1, len(repeats) + 1)), result
                assert repeats[0]['flags'] & 128 and all(not event['flags'] & 128 for event in repeats[1:]), result
                assert all(event['source'] == 257 for event in result), result
                count = len(events()); await asyncio.sleep(.15); assert len(events()) == count
                print(f'PASS actual {key}: DOWN, {len(repeats)} repeats with LONG_PRESS, UP and stable downTime', flush=True)

            identifier = str(uuid.uuid4()); start = len(events())
            await edge('up', 'down', identifier)
            await edge('up', 'cancel', identifier)
            assert (await received(19, start))[-1]['flags'] & 32
            print('PASS explicit cancellation delivers canceled UP', flush=True)

            # Freeze only our verified runtime. Its Java child must independently release the hold.
            identifier = str(uuid.uuid4()); start = len(events())
            await edge('menu', 'down', identifier)
            pid = int(service['pid'])
            shell(f'test "$(readlink /proc/{pid}/exe)" = {shlex.quote(binary)} && kill -STOP {pid}')
            paused = True
            result = await received(82, start)
            assert result[-1]['flags'] & 32, result
            elapsed = result[-1]['eventTime'] - result[0]['eventTime']
            assert 1400 <= elapsed <= 2200, elapsed
            shell(f'kill -CONT {pid}'); paused = False
            await asyncio.sleep(.2)
            await edge('menu', 'keep', identifier, expect_ok=False)
            print(f'PASS independent Java deadman cancels within {elapsed}ms; keep cannot resurrect expired DOWN', flush=True)

            identifier = str(uuid.uuid4()); start = len(events())
            await edge('down', 'down', identifier)
        assert (await received(20, start))[-1]['flags'] & 32
        print('PASS WebSocket disconnect delivers canceled UP', flush=True)
        async with websockets.connect(uri) as stopping:
            start = len(events())
            await stopping.send(json.dumps(dict(id='shutdown', protocol=1, type='tool', tool='remote.key',
                arguments=dict(key='left', action='down', pressId=str(uuid.uuid4())))))
            assert json.loads(await stopping.recv())['ok']
            shell(f'{binary} service stop --state-dir {directory}/state')
            assert (await received(21, start))[-1]['flags'] & 32
        print('PASS service shutdown cancels remaining held key', flush=True)

    try:
        print(adb('install', str(args.receiver_apk), timeout=120), end='', flush=True); installed = True
        shell(f'mkdir -m 700 {directory}'); created = True
        adb('push', str(args.binary), binary)
        service = json.loads(shell(f'chmod 700 {binary} && {binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        assert service.get('ready') and service.get('running'), service
        shell(f'am start -W -n {package}/.InputActivity')
        deadline = time.monotonic() + 10
        while True:
            try:
                output = shell('content read --uri content://ernest.airc.inputfixture.journal/ready')
                if output.strip():
                    raise RuntimeError('Receiver readiness provider did not return the empty ready file')
                break
            except (subprocess.CalledProcessError, RuntimeError):
                assert time.monotonic() < deadline, 'Receiver did not gain focus'
                time.sleep(.1)
        forward = int(adb('forward', 'tcp:0', f'tcp:{service["port"]}').strip())
        asyncio.run(exercise(forward))
    finally:
        if paused and service:
            shell(f'kill -CONT {int(service["pid"])}')
        try:
            if created:
                shell(f'if [ -x {binary} ]; then {binary} service stop --state-dir {directory}/state; fi')
                shell(f'rm -rf -- {directory}')
        finally:
            if forward:
                adb('forward', '--remove', f'tcp:{forward}')
            if installed:
                adb('uninstall', package)
            if original:
                resumed = re.findall(r'(?:mResumedActivity|topResumedActivity).*? ([\w.]+/[\w.$]+)',
                                     shell('dumpsys activity activities'))
                # Closing the receiver normally reveals the original task, including
                # non-exported system dialogs which shell must not try to relaunch.
                if original not in resumed:
                    shell('am start -n ' + shlex.quote(original))


if __name__ == '__main__':
    main()
