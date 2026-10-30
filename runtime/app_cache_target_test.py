#!/usr/bin/env python3
"""Verify cache-only cleanup on an explicitly selected runtime using a new disposable app.

Installs only a uniquely named fixture, writes its own markers, and uninstalls it afterward.
No existing application's cache is modified.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid

import websockets


def fixture(directory, package):
    sdk = Path(os.environ['ANDROID_HOME'])
    build = sdk / 'build-tools/36.0.0'
    manifest = directory / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="fixture">
<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="28"/>
<application android:label="AIRC cache verification" android:debuggable="true" android:hasCode="false"/></manifest>''')
    apk = directory / 'fixture.apk'
    subprocess.run([str(build / 'aapt2'), 'link', '--manifest', str(manifest), '-I', str(sdk / 'platforms/android-36/android.jar'), '-o', str(apk)], check=True, capture_output=True)
    key = directory / 'fixture.p12'
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storepass', 'android', '-keypass', 'android', '-alias', 'fixture', '-keyalg', 'RSA', '-validity', '2', '-dname', 'CN=AIRC cache verification'], check=True, capture_output=True)
    subprocess.run([str(build / 'apksigner'), 'sign', '--ks', str(key), '--ks-pass', 'pass:android', str(apk)], check=True, capture_output=True)
    return apk


async def verify(url, apk, package):
    directory = '/data/local/tmp/airc-cache-verification-' + uuid.uuid4().hex
    install_requested = created = False
    async with websockets.connect(url, proxy=None) as socket:
        sequence = 0

        async def request(tool=None, arguments=None, kind='tool', ok=True):
            nonlocal sequence
            sequence += 1
            message = dict(id=str(sequence), protocol=1, type=kind, arguments=arguments or {})
            if tool:
                message['tool'] = tool
            await socket.send(json.dumps(message))
            response = json.loads(await asyncio.wait_for(socket.recv(), 60))
            assert response['id'] == str(sequence) and response['ok'] == ok, response
            if not ok:
                return response
            value = response['result']
            return value if tool == 'device.shell' else json.loads(value)

        async def shell(command):
            return (await request('device.shell', dict(command=command))).strip()

        async def package_command(command, label):
            log = directory + '/' + label + '.log'
            code = directory + '/' + label + '.code'
            ready = directory + '/' + label + '.started'
            job = 'echo ready > ' + ready + '; ' + command + ' > ' + log + ' 2>&1; printf %s $? > ' + code
            # device.shell kills its process group on completion. Detach and wait for startup.
            launch = 'setsid sh -c ' + shlex.quote(job) + ' </dev/null >/dev/null 2>&1 &'
            await shell(launch + ' for i in 1 2 3 4 5; do [ ! -f ' + ready + ' ] || exit 0; sleep 1; done; exit 1')
            for _ in range(120):
                done = await shell('if [ -f ' + code + ' ]; then cat ' + code + '; else echo pending; fi')
                if done != 'pending':
                    output = await shell('cat ' + log)
                    assert done == '0', output
                    return output
                await asyncio.sleep(1)
            raise AssertionError('Package operation did not finish: ' + label)

        async def own(command):
            return await shell(f'run-as {package} sh -c {shlex.quote(command)}')

        async def data_hashes():
            result = {}
            for path in ('files/owned', 'shared_prefs/owned.xml', 'databases/owned.db'):
                result[path] = hashlib.sha256(base64.b64decode(await own('base64 ' + path))).hexdigest()
            return result

        async def prepare():
            return await request('apps.clear_cache', dict(package=package))

        async def task_finished(task):
            task_id = task['id']
            for _ in range(100):
                assert task['id'] == task_id and task['direction'] == 'app_cache_clear' and task['name'] == package, task
                if task['phase'] in ('completed', 'failed', 'cancelled'):
                    assert task['phase'] == 'completed', task
                    return task['result']
                await asyncio.sleep(.3)
                task = await request('task.progress', dict(id=task_id))
            raise AssertionError('Cache cleanup task did not finish')

        try:
            capability = await request('apps.cache_capabilities')
            assert capability['supported'] and capability['runtimeUid'] == 0, capability
            assert not await shell(f'pm list packages {package}'), 'Refusing to replace existing app'
            await shell('mkdir ' + directory)
            created = True
            encoded = base64.b64encode(apk.read_bytes()).decode()
            for start in range(0, len(encoded), 12000):
                await shell('printf %s ' + shlex.quote(encoded[start:start + 12000]) + ' >> ' + directory + '/fixture.b64')
            remote = directory + '/fixture.apk'
            await shell(f'base64 -d {directory}/fixture.b64 > {remote}; chmod 644 {remote}')
            assert hashlib.sha256(base64.b64decode(await shell('base64 ' + remote))).hexdigest() == hashlib.sha256(apk.read_bytes()).hexdigest()
            install_requested = True
            assert 'Success' in await package_command('pm install ' + remote, 'install')
            await own('mkdir -p cache code_cache files shared_prefs databases; printf cache-marker > cache/owned; printf code-cache-marker > code_cache/owned; printf data-marker > files/owned; printf prefs-marker > shared_prefs/owned.xml; printf database-marker > databases/owned.db')
            hashes = await data_hashes()
            rejected = await prepare()
            assert rejected['cacheOnly'] and rejected['app']['package'] == package, rejected
            await request(arguments=dict(id=rejected['id'], accepted=False), kind='approve_cache')
            assert await own('cat cache/owned') == 'cache-marker'
            await request(arguments=dict(id=rejected['id']), kind='approve_cache', ok=False)
            print('PASS prepare/cancel does not delete cache; rejected proposal cannot be replayed', flush=True)
            proposal = await prepare()
            result = await task_finished(await request(arguments=dict(id=proposal['id']), kind='approve_cache'))
            assert result['package'] == package and result['cacheOnly'] and result['completed'], result
            await own('test ! -e cache/owned && test ! -e code_cache/owned')
            assert await data_hashes() == hashes
            await request(arguments=dict(id=proposal['id']), kind='approve_cache', ok=False)
            await request('apps.clear_cache', dict(package='com.android.settings'), ok=False)
            await request('apps.clear_cache', dict(package=package, data=True), ok=False)
            print('PASS real PackageManager callback; cache/code_cache removed; files/preferences/database hashes unchanged', flush=True)
            print('PASS system app, additional fields and duplicate approval rejected', flush=True)
            print(json.dumps(dict(capabilities=capability, result=result), ensure_ascii=False), flush=True)
        finally:
            if install_requested and ('package:' + package) in (await shell('pm list packages ' + package)).splitlines():
                assert 'Success' in await package_command('pm uninstall ' + package, 'uninstall')
            if created:
                await shell('rm -rf ' + directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True, help='Explicit target ws://host:port/ws; root service required')
    args = parser.parse_args()
    package = 'ernest.airc.cacheverification.p' + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix='airc-cache-fixture-') as directory:
        asyncio.run(verify(args.url, fixture(Path(directory), package), package))


if __name__ == '__main__':
    main()
