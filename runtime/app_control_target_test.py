"""Verify app controls on an explicitly selected Android target using a UUID APK and runtime only."""
import argparse
import asyncio
import json
import os
import re
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid
import zipfile

import websockets


def run(*args):
    try:
        return subprocess.check_output(list(map(str, args)), text=True, stderr=subprocess.STDOUT, timeout=60).strip()
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f"Fixture command failed: {error.output.strip()}") from error


def fixture(directory, package):
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk / 'build-tools/36.0.0'
    jar = sdk / 'platforms/android-36/android.jar'
    manifest = directory / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="fixture">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="33"/>
<uses-permission android:name="android.permission.CAMERA"/><uses-permission android:name="android.permission.INTERNET"/>
<application android:label="AIRC disposable control fixture" android:debuggable="true"><activity android:name=".MainActivity" android:exported="true"/><provider android:name=".MarkerProvider" android:authorities="{package}.marker" android:exported="true"/></application></manifest>''')
    source = directory / 'MainActivity.java'
    source.write_text(f'''package {package}; public class MainActivity extends android.app.Activity {{
@Override public void onCreate(android.os.Bundle state) {{ super.onCreate(state); getSharedPreferences("marker",0).edit().putString("value","preserved").commit(); }} }}''')
    provider = directory / 'MarkerProvider.java'
    provider.write_text(f'''package {package}; public class MarkerProvider extends android.content.ContentProvider {{
public boolean onCreate() {{ return true; }}
public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection, String[] arguments, String order) {{
android.database.MatrixCursor result = new android.database.MatrixCursor(new String[]{{"value"}});
result.addRow(new Object[]{{getContext().getSharedPreferences("marker",0).getString("value","missing")}}); return result; }}
public String getType(android.net.Uri uri) {{ return "vnd.android.cursor.item/fixture"; }}
public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {{ throw new UnsupportedOperationException(); }}
public int delete(android.net.Uri uri, String selection, String[] arguments) {{ throw new UnsupportedOperationException(); }}
public int update(android.net.Uri uri, android.content.ContentValues values, String selection, String[] arguments) {{ throw new UnsupportedOperationException(); }}
}}''')
    classes = directory / 'classes'
    classes.mkdir()
    run('javac', '-source', '8', '-target', '8', '-classpath', jar, '-d', classes, source, provider)
    dex = directory / 'dex'
    dex.mkdir()
    run(tools / 'd8', '--lib', jar, '--output', dex, *classes.rglob('*.class'))
    unsigned = directory / 'unsigned.apk'
    run(tools / 'aapt2', 'link', '--manifest', manifest, '-I', jar, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    key = directory / 'fixture.p12'
    run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=AIRC disposable fixture')
    apk = directory / 'fixture.apk'
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', apk, unsigned)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', type=Path, required=True)
    args = parser.parse_args()
    token = uuid.uuid4().hex
    package = 'ernest.airc.controlfixture.p' + token
    directory = '/data/local/tmp/airc-control-' + token
    binary = directory + '/airc-runtime'
    port = None
    installed = started = created = False
    previous_activity = None

    def adb(*command):
        return run('adb', '-s', args.serial, *command)

    async def verify():
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            counter = 0

            async def request(tool=None, arguments=None, kind='tool', success=True):
                nonlocal counter
                counter += 1
                await socket.send(json.dumps(dict(id=str(counter), protocol=1, type=kind, tool=tool, arguments=arguments or {})))
                while True:
                    response = json.loads(await asyncio.wait_for(socket.recv(), 40))
                    if response.get('id') == str(counter):
                        break
                assert response['ok'] == success, response
                return json.loads(response['result']) if success else response

            async def info():
                return await request('apps.control_info', dict(package=package))

            async def prepare(action, permission=None):
                values = dict(package=package, action=action)
                if permission:
                    values['permission'] = permission
                return await request('apps.prepare_control', values)

            async def commit(proposal):
                task = await request(arguments=dict(id=proposal['id']), kind='approve_app_control')
                for _ in range(80):
                    assert task['direction'] == 'app_control' and task['name'] == package, task
                    if task['phase'] in ('completed', 'failed', 'cancelled'):
                        break
                    await asyncio.sleep(.25)
                    task = await request('task.progress', dict(id=task['id']))
                assert task['phase'] == 'completed', task
                return task['result']

            initial = await info()
            print('Caller capabilities:', initial['state']['capabilities'], flush=True)
            camera = next(p for p in initial['state']['permissions'] if p['name'] == 'android.permission.CAMERA')
            assert camera['mutable'] and not camera['granted'], camera
            normal = next(p for p in initial['state']['permissions'] if p['name'] == 'android.permission.INTERNET')
            assert not normal['mutable'] and not normal['runtime'], normal
            await request('apps.prepare_control', dict(package=package, action='revoke', permission='android.permission.INTERNET'), success=False)
            rejected = await prepare('clear_data')
            await request(arguments=dict(id=rejected['id'], accepted=False), kind='approve_app_control')
            assert 'preserved' in adb('shell', f'content query --uri content://{package}.marker')
            await request(arguments=dict(id=rejected['id']), kind='approve_app_control', success=False)
            await commit(await prepare('grant', 'android.permission.CAMERA'))
            assert next(p for p in (await info())['state']['permissions'] if p['name'] == camera['name'])['granted']
            await commit(await prepare('revoke', 'android.permission.CAMERA'))
            assert not next(p for p in (await info())['state']['permissions'] if p['name'] == camera['name'])['granted']
            adb('shell', f'am start -W -n {package}/.MainActivity')
            assert not (await info())['state']['stopped']
            await commit(await prepare('force_stop'))
            assert (await info())['state']['stopped']
            stale = await prepare('disable')
            adb('shell', f'pm disable-user --user {initial["app"]["userId"]} {package}')
            await request(arguments=dict(id=stale['id']), kind='approve_app_control', success=False)
            await commit(await prepare('enable'))
            await commit(await prepare('disable'))
            assert not (await info())['state']['enabled']
            await commit(await prepare('enable'))
            assert 'preserved' in adb('shell', f'content query --uri content://{package}.marker')
            await commit(await prepare('clear_data'))
            assert 'value=missing' in adb('shell', f'content query --uri content://{package}.marker')
            assert (await info())['app']['package'] == package
            system_package = next(line.split(':', 1)[1] for line in adb('shell', 'pm list packages -s').splitlines() if '.' in line)
            assert (await request('apps.control_info', dict(package=system_package)))['app']['system']
            await request('apps.prepare_control', dict(package=system_package, action='clear_data'), success=False)
            print('PASS: actual permissions, rejection, replay rejection, stale state, force stop, enable/disable and data removal; original apps untouched.', flush=True)

    try:
        activity = adb('shell', 'dumpsys activity activities')
        matches = re.findall(r'(?:mResumedActivity|topResumedActivity)[^\n]*? ([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)', activity)
        previous_activity = matches[0] if matches else None
        abi = adb('shell', 'getprop ro.product.cpu.abi')
        expected = {'x86_64': 'x86_64', 'arm64-v8a': 'aarch64', 'armeabi-v7a': 'armv7'}[abi]
        assert expected in args.binary.name, f'ABI mismatch: {abi}, {args.binary.name}'
        assert f'package:{package}' not in adb('shell', f'pm list packages {package}').splitlines(), 'UUID package unexpectedly exists'
        with tempfile.TemporaryDirectory(prefix='airc-control-') as temp:
            apk = fixture(Path(temp), package)
            adb('install', str(apk)); installed = True
        adb('shell', f'am start -W -n {package}/.MainActivity')
        assert 'preserved' in adb('shell', f'content query --uri content://{package}.marker')
        adb('shell', f'mkdir -m 700 {directory}'); created = True
        adb('push', str(args.binary.resolve()), binary)
        adb('shell', f'chmod 700 {binary}')
        reply = json.loads(adb('shell', f'{binary} service start --state-dir {directory}/state --port 0 --no-mdns')); started = True
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(reply['port'])))
        asyncio.run(verify())
    finally:
        cleanup = []
        if started:
            cleanup.append(('shell', f'{binary} service stop --state-dir {directory}/state'))
        if port:
            cleanup.append(('forward', '--remove', 'tcp:' + str(port)))
        if created:
            cleanup.append(('shell', 'rm -rf -- ' + shlex.quote(directory)))
        if installed:
            cleanup.append(('uninstall', package))
        if installed and previous_activity and not previous_activity.startswith("com.android.systemui/"):
            cleanup.append(('shell', 'am start -W -n ' + shlex.quote(previous_activity)))
        failures = []
        for command in cleanup:
            try:
                adb(*command)
            except (subprocess.SubprocessError, OSError, RuntimeError) as error:
                failures.append(str(error))
        if failures:
            raise RuntimeError('Fixture cleanup failed: ' + '; '.join(failures))


if __name__ == '__main__':
    main()
