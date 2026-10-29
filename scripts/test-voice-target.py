#!/usr/bin/env python3
"""Test virtual microphone with synthetic PCM on an explicitly selected Android target.

Creates a UUID runtime, sends the optional voice key, and checks assistant routing/cleanup.
No speech is recorded and no PCM is saved. Requires: uv run --with websockets.
"""
import argparse
import asyncio
import hashlib
import json
import math
import re
from pathlib import Path
import shlex
import struct
import subprocess
import time
import uuid

import websockets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--package', default='com.konka.bestlauncher')
    parser.add_argument('--key', type=int, default=1017)
    parser.add_argument('--phone', help='Optional explicitly selected physical controller; installs matching App/test APKs')
    parser.add_argument('--host', help='Target address reachable from the phone (required with --phone)')
    args = parser.parse_args()
    if args.phone and (not args.host or args.phone == args.serial):
        parser.error('--phone requires --host and must differ from target')

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *map(str, command)], text=True, stderr=subprocess.STDOUT, timeout=30).strip()

    abi = adb('shell', 'getprop', 'ro.product.cpu.abi')
    if abi not in args.binary.name:
        parser.error('Binary filename must match the selected target ABI')
    if not 0 <= args.key <= 65535:
        parser.error('key must be 0..65535')
    marker = 'airc-voice-' + uuid.uuid4().hex
    directory = '/data/local/tmp/' + marker
    binary = directory + '/airc-runtime'
    state = directory + '/state'
    port = None
    started = False
    created = False

    def policies():
        audio = adb('shell', 'dumpsys', 'audio')
        return audio.split('Audio policies:', 1)[1].split('Events log: dynamic policy', 1)[0].strip()

    before = policies()

    def active_recorders():
        audio = adb('shell', 'dumpsys', 'audio')
        return audio.split('RecordActivityMonitor dump', 1)[1].split('Events log: recording activity', 1)[0].count('active? true')
    initial_recorders = active_recorders()

    async def cleaned():
        for _ in range(30):
            if await asyncio.to_thread(policies) == before and await asyncio.to_thread(active_recorders) == initial_recorders:
                return
            await asyncio.sleep(.1)
        raise AssertionError('Temporary audio policy was not cleaned')

    async def connect(key=None):
        socket = await websockets.connect(f'ws://127.0.0.1:{port}/voice', proxy=None)
        await socket.send(json.dumps(dict(packageName=args.package, keyCode=args.key if key is None else key)))
        reply = json.loads(await asyncio.wait_for(socket.recv(), 8))
        assert reply.get('ready'), reply
        return socket

    async def pcm(socket, seconds, inspect=False):
        until = time.monotonic() + seconds
        frame = 0
        observed = False
        while time.monotonic() < until:
            samples = [int(6000 * math.sin(2 * math.pi * 997 * (frame + i) / 16000)) for i in range(320)]
            frame += 320
            await socket.send(struct.pack('<320h', *samples))
            if inspect and not observed and frame >= 16000:
                # Keep feeding while dumpsys runs; blocking ADB must not create a stream gap.
                observed = True
                async def check_route():
                    dump = await asyncio.to_thread(adb, 'shell', 'dumpsys', 'media.audio_flinger')
                    current = dump.split('Historical Thread Log', 1)[0]
                    assert 'AUDIO_DEVICE_IN_REMOTE_SUBMIX' in current, 'No live submix recording route'
                    audio = await asyncio.to_thread(adb, 'shell', 'dumpsys', 'audio')
                    active = audio.split('RecordActivityMonitor dump', 1)[1].split('Events log: recording activity', 1)[0]
                    assert args.package in active and 'active? true' in active and 'silenced:false' in active, active
                route = asyncio.create_task(check_route())
            await asyncio.sleep(.02)
        if inspect:
            assert observed
            await route

    def verify_phone():
        from instrumentation_result import require_passed
        def phone(*command):
            return subprocess.check_output(['adb', '-s', args.phone, *map(str, command)], text=True, stderr=subprocess.STDOUT, timeout=45)
        root = Path(__file__).resolve().parents[1]
        phone('install', '-r', root / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk')
        phone('install', '-r', root / 'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk')
        was_granted = 'android.permission.RECORD_AUDIO: granted=true' in phone('shell', 'dumpsys', 'package', 'ernest.airc')
        command = ['adb', '-s', args.phone, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'ernest.airc.LiveVoiceInputTargetTest', '-e', 'voiceHost', args.host,
                   '-e', 'voicePort', str(target_port), '-e', 'voicePackage', args.package,
                   '-e', 'voiceKey', str(args.key), 'ernest.airc.test/androidx.test.runner.AndroidJUnitRunner']
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        routed = False
        try:
            deadline = time.monotonic() + 40
            while process.poll() is None and time.monotonic() < deadline:
                audio = adb('shell', 'dumpsys', 'audio')
                active = audio.split('RecordActivityMonitor dump', 1)[1].split('Events log: recording activity', 1)[0]
                if args.package in active and 'active? true' in active and 'silenced:false' in active:
                    session = re.search(r'session:(\d+)', active).group(1)
                    flinger = adb('shell', 'dumpsys', 'media.audio_flinger').split('Historical Thread Log', 1)[0]
                    for line in flinger.splitlines():
                        fields = line.split()
                        if len(fields) >= 12 and fields[0] == 'yes' and fields[3] == session and int(fields[11], 16) > 0:
                            routed = 'AUDIO_DEVICE_IN_REMOTE_SUBMIX' in flinger
                time.sleep(.1)
            output, _ = process.communicate(timeout=5)
            print(output, flush=True)
            require_passed(output, 1)
            assert routed, 'Phone test did not coincide with live assistant PCM/submix frames'
            print('PASS: physical phone button + AudioRecord + WLAN PCM + assistant submix frames + release', flush=True)
        finally:
            if process.poll() is None:
                process.kill(); process.communicate()
                phone('shell', 'am', 'force-stop', 'ernest.airc')
            if not was_granted:
                phone('shell', 'pm', 'revoke', 'ernest.airc', 'android.permission.RECORD_AUDIO')

    async def verify():
        def target_recording_events():
            audio = adb('shell', 'dumpsys', 'audio')
            events = audio.split('Events log: recording activity received by AudioService', 1)[1].split('AudioDeviceBroker:', 1)[0]
            return [line for line in events.splitlines() if args.package in line]
        previous_events = target_recording_events()
        socket = await connect()
        await socket.send('stop')
        assert json.loads(await socket.recv()).get('stopped')
        await socket.close()
        await cleaned()
        assert target_recording_events() == previous_events, 'Cancelled setup triggered a delayed voice key'
        print('PASS: cancel before first PCM does not trigger the voice key', flush=True)

        socket = await connect()
        try:
            async with websockets.connect(f'ws://127.0.0.1:{port}/voice', proxy=None) as other:
                assert 'already in use' in json.loads(await other.recv())['error']
            await pcm(socket, 2, inspect=True)
            await socket.send('stop')
            assert json.loads(await socket.recv()).get('stopped')
        finally:
            await socket.close()
        await cleaned()
        print('PASS: 16kHz PCM, assistant active on submix, exclusive session, explicit stop/cleanup', flush=True)

        socket = await connect()
        await pcm(socket, .6)
        socket.transport.abort()
        await cleaned()
        print('PASS: abrupt disconnect releases key and unregisters audio policy', flush=True)

        for kind in ['idle', 'odd-frame', 'rate-limit']:
            socket = await connect(0)
            try:
                if kind == 'odd-frame':
                    await socket.send(b'odd')
                elif kind == 'rate-limit':
                    for _ in range(10):
                        await socket.send(bytes(3200))
                reply = json.loads(await asyncio.wait_for(socket.recv(), 6))
                assert 'error' in reply, reply
            finally:
                await socket.close()
            await cleaned()
            print(f'PASS: {kind} stops and cleans the session', flush=True)

        async with websockets.connect(f'ws://127.0.0.1:{port}/voice', proxy=None) as socket:
            await socket.send(json.dumps(dict(packageName='com.app;reboot', keyCode=args.key)))
            assert 'error' in json.loads(await socket.recv())
        await cleaned()
        print('PASS: invalid settings rejected before audio routing/key injection', flush=True)

        if args.phone:
            await asyncio.to_thread(verify_phone)
            await cleaned()

        socket = await connect()
        try:
            await pcm(socket, .6)
            await asyncio.to_thread(adb, 'shell', f'{binary} service stop --state-dir {state}')
            await cleaned()
            print('PASS: runtime shutdown allows bridge EOF cleanup and key release', flush=True)
        finally:
            await socket.close()


    try:
        adb('shell', 'mkdir', directory)
        created = True
        adb('push', args.binary, binary)
        adb('shell', 'chmod', '700', binary)
        assert adb('shell', 'sha256sum', binary).split()[0] == hashlib.sha256(args.binary.read_bytes()).hexdigest()
        service = json.loads(adb('shell', f'{binary} service start --state-dir {state} --port 0 --no-mdns'))
        started = True
        assert service['ready'] and service['running'], service
        target_port = service['port']
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(target_port)))
        asyncio.run(verify())
    finally:
        if started:
            adb('shell', f'{binary} service stop --state-dir {state}')
        if port:
            adb('forward', '--remove', 'tcp:' + str(port))
        if created:
            adb('shell', 'rm -rf -- ' + shlex.quote(directory))
        assert policies() == before, 'Audio policy cleanup differs from initial state'
        print('PASS: owned runtime, directory and forwarding removed', flush=True)


if __name__ == '__main__':
    main()
