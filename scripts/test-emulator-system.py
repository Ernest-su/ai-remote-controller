#!/usr/bin/env python3
"""Observe actual lock/reboot on a root Android emulator; rejects physical devices."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid
import zipfile
import websockets
from emulator_system_observation import wait_reboot
from emulator_shutdown_observation import EmulatorContainer

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--emulator', required=True)
    parser.add_argument('--action', choices=('lock', 'reboot', 'shutdown'), default='lock')
    parser.add_argument('--container', help='Owned labelled emulator container; required for shutdown observation/recovery')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.emulator):
        parser.error('Only an explicitly selected emulator serial is accepted')
    if args.action == 'shutdown' and not args.container:
        parser.error('Shutdown requires an explicitly selected labelled emulator container')

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.emulator, *command], text=True, stderr=subprocess.PIPE, timeout=60)

    if adb('shell', 'getprop ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Refusing system action: selected device is not an emulator')
    if adb('shell', 'id -u').strip() != '0':
        raise RuntimeError('Selected emulator must already have root adbd')
    abi = adb('shell', 'getprop ro.product.cpu.abi').strip()
    if abi not in ('x86_64', 'arm64-v8a', 'armeabi-v7a'):
        raise RuntimeError('Unsupported emulator ABI')
    asset = ROOT / 'androidApp/src/main/assets/runtime' / f'airc-runtime-{abi}'
    with zipfile.ZipFile(ROOT / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk') as apk:
        if hashlib.sha256(asset.read_bytes()).digest() != hashlib.sha256(apk.read(f'assets/runtime/airc-runtime-{abi}')).digest():
            raise RuntimeError('Rebuild APK: runtime asset mismatch')
    directory = f'/data/local/tmp/airc-emulator-system-{uuid.uuid4()}'
    binary = f'{directory}/airc-runtime'
    created = copied = False
    forward = None
    original_disabled = None
    reboot_attempted = False
    shutdown_attempted = False
    container = EmulatorContainer(args.container, args.emulator) if args.action == 'shutdown' else None
    try:
        adb('shell', f'mkdir -m 700 {directory}'); created = True
        adb('push', str(asset), binary); copied = True
        adb('shell', f'chmod 700 {binary}')
        service = json.loads(adb('shell', f'{binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        assert service.get('ready') and service.get('running')
        forward = int(adb('forward', 'tcp:0', f'tcp:{service["port"]}').strip())

        async def check():
            nonlocal original_disabled, reboot_attempted, shutdown_attempted
            async with websockets.connect(f'ws://127.0.0.1:{forward}/ws', max_size=1024*1024) as socket:
                async def request(identifier, **body):
                    await socket.send(json.dumps({'id': identifier, 'protocol': 1, **body}))
                    response = json.loads(await asyncio.wait_for(socket.recv(), 30))
                    assert response['id'] == identifier and response['ok'], 'System request failed'
                    return json.loads(response['result'])

                capability = await request('capability', type='tool', tool='device.system_capabilities', arguments={})
                assert capability['runtimeUid'] == 0 and capability['permissionProbeAvailable'] is True
                state = await request('initial', type='tool', tool='device.system_state', arguments={})
                if state.get('deviceSecure') is not False:
                    raise RuntimeError('Refusing to change a credential-secured or unknown emulator state')
                if args.action == 'shutdown':
                    assert capability['rebootGranted'] is True and capability['shutdownInterfacePresent'] is True
                    proposal = await request('prepare-shutdown', type='tool', tool='device.system_action', arguments={'action': 'shutdown'})
                    assert proposal['action'] == 'shutdown' and proposal['approvalRequired'] is True and proposal['runtimeUid'] == 0
                    shutdown_attempted = True
                    try:
                        result = await request('approve-shutdown', type='approve_system', arguments={'id': proposal['id'], 'accepted': True})
                    except (websockets.exceptions.ConnectionClosed, TimeoutError):
                        result = None
                    if result is not None:
                        assert result['id'] == proposal['id'] and result['action'] == 'shutdown' and result['submitted'] is True and result['completed'] is False
                    await container.wait_shutdown()
                    print('PASS: emulator shutdown approved; kernel power-down and normal process exit independently observed')
                    return
                if args.action == 'reboot':
                    assert capability['rebootGranted'] is True and capability['rebootInterfacePresent'] is True
                    before = adb('shell', 'cat /proc/sys/kernel/random/boot_id').strip()
                    uuid.UUID(before)
                    proposal = await request('prepare-reboot', type='tool', tool='device.system_action', arguments={'action': 'reboot'})
                    assert proposal['action'] == 'reboot' and proposal['approvalRequired'] is True and proposal['runtimeUid'] == 0
                    reboot_attempted = True
                    try:
                        result = await request('approve-reboot', type='approve_system', arguments={'id': proposal['id'], 'accepted': True})
                    except (websockets.exceptions.ConnectionClosed, TimeoutError):
                        result = None  # Lost acknowledgement requires independent observation.
                    if result is not None:
                        assert result['id'] == proposal['id'] and result['action'] == 'reboot' and result['submitted'] is True and result['completed'] is False
                    await wait_reboot(adb, before)
                    print('PASS: emulator reboot approved; changed boot ID and completed boot independently observed')
                    return
                assert capability['devicePowerGranted'] is True and capability['lockInterfacePresent'] is True
                original_disabled = adb('shell', 'locksettings get-disabled').strip()
                if original_disabled not in ('true', 'false'):
                    raise RuntimeError('Unknown emulator lock screen policy')
                if original_disabled == 'true':
                    adb('shell', 'locksettings set-disabled false')
                    assert adb('shell', 'locksettings get-disabled').strip() == 'false'
                adb('shell', 'input keyevent KEYCODE_WAKEUP')
                adb('shell', 'wm dismiss-keyguard')
                for index in range(40):
                    state = await request(f'unlocked-{index}', type='tool', tool='device.system_state', arguments={})
                    if state['keyguardLocked'] is False:
                        break
                    await asyncio.sleep(.25)
                assert state['keyguardLocked'] is False, 'Need independently observed unlocked baseline'
                proposal = await request('prepare', type='tool', tool='device.system_action', arguments={'action': 'lock'})
                assert proposal['action'] == 'lock' and proposal['approvalRequired'] is True and proposal['runtimeUid'] == 0
                result = await request('approve', type='approve_system', arguments={'id': proposal['id'], 'accepted': True})
                assert result['id'] == proposal['id'] and result['action'] == 'lock' and result['submitted'] is True and result['completed'] is False
                for index in range(40):
                    state = await request(f'locked-{index}', type='tool', tool='device.system_state', arguments={})
                    if state['keyguardLocked'] is True:
                        break
                    await asyncio.sleep(.25)
                assert state['keyguardLocked'] is True, 'Submission alone is insufficient: keyguard must become locked'
                print('PASS: emulator root lock approval accepted; independently observed keyguard false -> true')

        asyncio.run(asyncio.wait_for(check(), 180))
    finally:
        try:
            try:
                if shutdown_attempted:
                    container.recover(adb)
                if reboot_attempted or shutdown_attempted:
                    if adb('shell', 'getprop ro.kernel.qemu').strip() != '1':
                        raise RuntimeError('Emulator identity changed during recovery')
                    if adb('shell', 'id -u').strip() != '0':
                        adb('root')
                        adb('wait-for-device')
                        if adb('shell', 'id -u').strip() != '0':
                            raise RuntimeError('Cannot restore root for owned service cleanup')
                adb('shell', 'input keyevent KEYCODE_WAKEUP')
                adb('shell', 'wm dismiss-keyguard')
                if original_disabled == 'true':
                    adb('shell', 'locksettings set-disabled true')
                    assert adb('shell', 'locksettings get-disabled').strip() == original_disabled
            finally:
                if copied:
                    adb('shell', f'{binary} service stop --state-dir {directory}/state')
        finally:
            try:
                if forward is not None:
                    mappings=adb('forward','--list').splitlines()
                    if any(line.split()[:2]==[args.emulator,f'tcp:{forward}'] for line in mappings):
                        adb('forward', '--remove', f'tcp:{forward}')
            finally:
                if created:
                    adb('shell', f'rm -rf -- {directory}')
                    assert not adb('shell', f'if [ -e {directory} ]; then echo remains; fi').strip()


if __name__ == '__main__':
    main()
