#!/usr/bin/env python3
"""Check explicit MUSIC key routing on an owned emulator using a disposable input bridge."""
import argparse
import json
from pathlib import Path
import re
import select
import shlex
import subprocess
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--dex', required=True, type=Path)
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *command], text=True, timeout=30)

    if not re.fullmatch(r'emulator-\d+', args.serial) or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        parser.error('Only an explicitly selected owned Android emulator is supported')
    if not args.dex.is_file():
        parser.error('Build the platform bridge dex first')
    directory = '/data/local/tmp/airc-volume-keys-' + uuid.uuid4().hex
    dex = directory + '/classes.dex'
    user = adb('shell', 'am', 'get-current-user').strip()
    assert user.isdigit(), user
    process = None
    original = None

    def audio(action='status', percent=None):
        extra = '' if percent is None else f' {percent}'
        output = adb('shell', f'CLASSPATH={shlex.quote(dex)} app_process /system/bin ernest.airc.bridge.AudioBridge {action} {user}{extra}')
        return json.loads(output)

    def other_streams():
        return [adb('shell', 'cmd', 'media_session', 'volume', '--stream', str(stream), '--get') for stream in (2, 4)]

    def response():
        if not select.select([process.stdout], [], [], 5)[0]:
            raise AssertionError('Input bridge response timed out')
        return json.loads(process.stdout.readline())

    sequence = 0

    def key(code, operation='key', press_id=None):
        nonlocal sequence
        sequence += 1
        request = dict(id=sequence, code=code, operation=operation)
        if press_id is not None:
            request['pressId'] = press_id
        process.stdin.write(json.dumps(request) + '\n')
        process.stdin.flush()
        result = response()
        assert result.get('ok') and result['id'] == sequence, result

    try:
        adb('shell', 'mkdir', directory)
        adb('push', str(args.dex), dex)
        original = audio()
        others = other_streams()
        audio('unmute')
        middle = (original['minVolumeIndex'] + original['maxVolumeIndex']) // 2
        assert middle > original['minVolumeIndex'] and middle + 1 < original['maxVolumeIndex'], original
        audio('set_volume', round(100 * (middle - original['minVolumeIndex']) / (original['maxVolumeIndex'] - original['minVolumeIndex'])))
        time.sleep(.2)
        assert audio()['volumeIndex'] == middle
        process = subprocess.Popen(['adb', '-s', args.serial, 'shell',
            f'CLASSPATH={shlex.quote(dex)} app_process /system/bin ernest.airc.bridge.InputBridge'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        assert response()['ready']
        key(24)
        time.sleep(.2)
        assert audio()['volumeIndex'] == middle + 1
        key(25)
        time.sleep(.2)
        assert audio()['volumeIndex'] == middle
        print('PASS +/- changes MUSIC by one index', flush=True)
        identifier = str(uuid.uuid4())
        key(24, 'down', identifier)
        time.sleep(.8)
        key(24, 'up', identifier)
        time.sleep(.2)
        held = audio()['volumeIndex']
        assert held > middle + 1, held
        time.sleep(.3)
        assert audio()['volumeIndex'] == held
        print('PASS held MUSIC adjustment repeats and release stops it', flush=True)
        identifier = str(uuid.uuid4())
        key(164, 'down', identifier)
        time.sleep(.8)
        assert audio()['muted'] is True
        key(164, 'up', identifier)
        assert audio()['muted'] is True
        key(164)
        time.sleep(.2)
        assert audio()['muted'] is False
        assert other_streams() == others
        print('PASS held mute toggles once; ringtone and alarm remain unchanged', flush=True)
    finally:
        if process is not None:
            process.stdin.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=5)
        if original is not None:
            audio('unmute')
            restored = audio('set_volume', round(100 * (original['volumeIndex'] - original['minVolumeIndex']) / (original['maxVolumeIndex'] - original['minVolumeIndex'])))
            assert restored['volumeIndex'] == original['volumeIndex'], restored
            audio('mute' if original['muted'] else 'unmute')
        adb('shell', 'rm', '-r', directory)


if __name__ == '__main__':
    main()
