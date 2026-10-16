#!/usr/bin/env python3
"""Phone-to-target latency and real receiver delivery; owned fixture/runtime only."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import zipfile

import websockets

from instrumentation_result import require_passed

ROOT = Path(__file__).resolve().parent.parent


async def helper_pid(host, port):
    async with websockets.connect(f'ws://{host}:{port}/ws') as socket:
        await socket.send(json.dumps({'id':'parent-lifecycle','protocol':1,'type':'tool',
            'tool':'remote.key','arguments':{'key':'down'}}))
        reply = json.loads(await socket.recv())
        assert reply['ok'], reply
        return json.loads(reply['result'])['bridgePid']


async def host_benchmark(host, port, runtime_pid):
    async with websockets.connect(f'ws://{host}:{port}/ws') as socket:
        pending = {}
        async def receive():
            async for text in socket:
                reply = json.loads(text)
                future = pending.pop(reply['id'], None)
                if future is None or future.done():
                    continue
                if reply['ok']:
                    future.set_result(reply['result'])
                else:
                    future.set_exception(RuntimeError(reply['error']))
        reader = asyncio.create_task(receive())
        sequence = 0
        async def request(tool, arguments):
            nonlocal sequence
            sequence += 1
            identifier = str(sequence)
            future = asyncio.get_running_loop().create_future()
            pending[identifier] = future
            await socket.send(json.dumps({'id':identifier,'protocol':1,'type':'tool','tool':tool,'arguments':arguments}))
            return await asyncio.wait_for(future, 15)
        async def key(name):
            return json.loads(await request('remote.key', {'key':name}))
        try:
            child = (await key('down'))['bridgePid']
            slow = asyncio.create_task(request('device.shell', {'command':'sleep 2; printf control-complete'}))
            await asyncio.sleep(.15)
            start = time.perf_counter()
            await key('left')
            separate_ms = (time.perf_counter()-start)*1000
            assert not slow.done(), 'Key waited for the slow tool'
            assert await slow == 'control-complete'
            times, injections = [], []
            peak = 0
            async def measured(index):
                nonlocal peak
                start = time.perf_counter()
                peak = max(peak, len(pending)+1)
                reply = await key(['down','left','right','up','ok'][index%5])
                assert reply['bridgePid'] == child and reply['released']
                times.append((time.perf_counter()-start)*1000)
                injections.append(reply['injectionMicros']/1000)
            start = time.perf_counter()
            tasks = []
            for index in range(300):
                await asyncio.sleep(max(0,start+index*.1-time.perf_counter()))
                tasks.append(asyncio.create_task(measured(index)))
            await asyncio.gather(*tasks)
            tail_ms = (time.perf_counter()-start)*1000
            command = f'parent=$(sed -n \'s/^PPid:[[:space:]]*//p\' /proc/{child}/status); [ "$parent" = {runtime_pid} ] && kill -9 {child} && echo child-stopped'
            assert 'child-stopped' in await request('device.shell', {'command':command})
            try:
                await key('left')
            except RuntimeError:
                pass
            else:
                raise AssertionError('Failed key was replayed automatically')
            recovered = await key('right')
            assert recovered['bridgePid'] != child
            times.sort();injections.sort()
            return dict(measurementOrigin='host',samples=300,rateHz=10,durationSeconds=30,
                p50Ms=times[150],p95Ms=times[284],maxMs=times[-1],peakInFlight=peak,
                isolatedLaneMs=separate_ms,injectionP95Ms=injections[284],tailMs=tail_ms,
                recoveredBridgePid=recovered['bridgePid'])
        finally:
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)
            for future in pending.values():
                future.cancel()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phone')
    parser.add_argument('--host-only', action='store_true', help='Measure host-to-target; does not replace phone acceptance')
    parser.add_argument('--target', required=True)
    parser.add_argument('--host', required=True, help='Target address reachable from the phone and test host')
    parser.add_argument('--use-installed-phone', action='store_true', help='Verify current App/test APK hashes and avoid reinstalling them')
    args = parser.parse_args()
    if not args.host_only and not args.phone:
        parser.error('--phone is required without --host-only')
    assert args.phone != args.target

    def adb(serial, *command, timeout=90):
        return subprocess.check_output(['adb', '-s', serial, *command], text=True, timeout=timeout)

    def shell(command):
        return adb(args.target, 'shell', command)

    package = 'ernest.airc.inputfixture'
    assert f'package:{package}' not in shell(f'pm list packages {package}').splitlines(), 'Refusing to replace an existing input fixture'
    foreground = shell('dumpsys activity activities')
    resumed = re.search(r'mResumedActivity:.*?\bu\d+ ([\w.$]+/[\w.$]+)', foreground)
    original = resumed.group(1) if resumed else None
    directory = '/data/local/tmp/airc-input-test-' + str(uuid.uuid4())
    binary = directory + '/airc-runtime'
    abi = shell('getprop ro.product.cpu.abi').strip()
    asset = ROOT / f'androidApp/src/main/assets/runtime/airc-runtime-{abi}'
    app = ROOT / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'
    tests = ROOT / 'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk'
    receiver = ROOT / 'inputFixture/build/outputs/apk/debug/inputFixture-debug.apk'
    with zipfile.ZipFile(app) as archive:
        assert hashlib.sha256(archive.read(f'assets/runtime/airc-runtime-{abi}')).digest() == hashlib.sha256(asset.read_bytes()).digest()
    installed = created = tests_installed = False
    service = None
    try:
        print(adb(args.target, 'install', str(receiver)), flush=True); installed = True
        shell(f'mkdir -m 700 {directory}'); created = True
        adb(args.target, 'push', str(asset), binary)
        service = json.loads(shell(f'chmod 700 {binary} && {binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        shell(f'am start -W -n {package}/.InputActivity')
        deadline = time.monotonic() + 10
        while shell(f'run-as {package} sh -c \'test -f files/ready && echo ready\'').strip() != 'ready':
            assert time.monotonic() < deadline, 'Receiver did not gain focus'
            time.sleep(.1)
        if args.host_only:
            metrics = asyncio.run(host_benchmark(args.host, service['port'], service['pid']))
            output = None
        elif args.use_installed_phone:
            for name, apk in (('ernest.airc', app), ('ernest.airc.test', tests)):
                paths = adb(args.phone, 'shell', 'pm', 'path', name).splitlines()
                base = next(line.removeprefix('package:') for line in paths if line.endswith('/base.apk'))
                digest = adb(args.phone, 'shell', 'sha256sum ' + shlex.quote(base)).split()[0]
                assert digest == hashlib.sha256(apk.read_bytes()).hexdigest(), f'Installed APK differs: {name}'
        else:
            print(adb(args.phone, 'install', '-r', str(app)), flush=True)
            print(adb(args.phone, 'install', '-r', str(tests)), flush=True)
        if not args.host_only:
            tests_installed = True
            output = adb(args.phone, 'shell', 'am', 'instrument', '-w', '-r',
                     '-e', 'class', 'ernest.airc.RemoteKeyPerformanceTargetTest',
                     '-e', 'inputTargetHost', args.host, '-e', 'inputTargetPort', str(service['port']),
                     '-e', 'inputRuntimePid', str(service['pid']),
                     'ernest.airc.test/androidx.test.runner.AndroidJUnitRunner', timeout=150)
            print(output, flush=True)
            require_passed(output, 1)
            match = re.search(r'^INSTRUMENTATION_RESULT: inputBenchmark=(.+)$', output, re.MULTILINE)
            assert match, 'Missing latency measurements'
            metrics = json.loads(match.group(1))
            metrics['measurementOrigin'] = 'phone'
        deadline = time.monotonic() + 5
        while True:
            events = [json.loads(line) for line in shell(f'run-as {package} cat files/events.jsonl').splitlines()]
            if len(events) >= 606:
                break
            assert time.monotonic() < deadline, f'Incomplete actual delivery: {len(events)}'
            time.sleep(.1)
        expected = [(20, 0), (20, 1), (21, 0), (21, 1)]
        for index in range(300):
            code = [20, 21, 22, 19, 23][index % 5]
            expected.extend([(code, 0), (code, 1)])
        actual = [(event['code'], event['action']) for event in events]
        assert actual[:604] == expected, 'Actual key count/order differed from accepted requests'
        assert actual[604:] in ([(22, 0), (22, 1)], [(21, 1), (22, 0), (22, 1)]), f'Failed key replayed or recovery delivery invalid: {actual[604:]}'
        assert all(event['source'] == 257 and event['repeat'] == 0 for event in events), 'Input source/repeat semantics changed'
        for index in range(0, 604, 2):
            assert events[index]['downTime'] == events[index + 1]['downTime']
        shell(f'{binary} service stop --state-dir {directory}/state')
        service = None
        child = metrics['recoveredBridgePid']
        assert not shell(f'if [ -d /proc/{child} ]; then echo present; fi').strip(), 'Input helper survived service shutdown'
        print('PASS actual receiver: 302 ordered down/up pairs, recovery without DOWN replay; child reaped', flush=True)
        service = json.loads(shell(f'{binary} service start --state-dir {directory}/state --port 0 --no-mdns'))
        child = asyncio.run(helper_pid(args.host, service['port']))
        shell(f'[ "$(readlink /proc/{service["pid"]}/exe)" = {shlex.quote(binary)} ] && kill -9 {service["pid"]}')
        service = None
        deadline = time.monotonic() + 5
        while shell(f'if [ -d /proc/{child} ]; then echo present; fi').strip():
            assert time.monotonic() < deadline, 'Input helper survived abrupt parent death'
            time.sleep(.1)
        print('PASS abrupt runtime termination: private stdin EOF ended the input helper', flush=True)
        print('MEASUREMENTS ' + json.dumps(metrics), flush=True)
        assert metrics['p95Ms'] <= 100, 'Warm round-trip P95 exceeded 100 ms'
        assert metrics.get('tailMs', 0) <= 30200, 'Input queue accumulated after final tap'
    finally:
        try:
            # Also stop after partial startup/JSON parsing failure. Never delete a
            # running service's state merely because its start reply was lost.
            if created:
                shell(f'if [ -x {binary} ]; then {binary} service stop --state-dir {directory}/state; fi')
                shell(f'rm -rf -- {shlex.quote(directory)}')
        finally:
            if installed:
                adb(args.target, 'uninstall', package)
            if original:
                shell('am start -n ' + shlex.quote(original))
            if tests_installed:
                adb(args.phone, 'uninstall', 'ernest.airc.test')
            if args.phone:
                adb(args.phone, 'shell', 'am', 'start', '-n', 'ernest.airc/.MainActivity')


if __name__ == '__main__':
    main()
