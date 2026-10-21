"""Verify target-owned timers with a disposable MediaSession/launcher APK on an explicit owned target."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
import time
import uuid
import zipfile
import websockets
from app_control_target_test import run


def fixture(directory, package):
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk / 'build-tools/36.0.0'
    jar = sdk / 'platforms/android-36/android.jar'
    manifest = directory / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1">
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="33"/>
<application android:label="AIRC disposable timer fixture"><activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
<provider android:name=".MarkerProvider" android:authorities="{package}.marker" android:exported="true" android:permission="android.permission.DUMP"/></application></manifest>''')
    source = directory / 'MainActivity.java'
    source.write_text(f'''package {package}; public class MainActivity extends android.app.Activity {{
private android.media.session.MediaSession session;
private void state(int value) {{ session.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(6).setState(value,0,value==3?1:0).build()); }}
@Override public void onCreate(android.os.Bundle saved) {{super.onCreate(saved);android.widget.TextView text=new android.widget.TextView(this);text.setText("AIRC timer fixture");setContentView(text);
session=new android.media.session.MediaSession(this,"AIRC timer fixture");session.setFlags(3);session.setCallback(new android.media.session.MediaSession.Callback() {{ public void onPause() {{android.content.SharedPreferences p=getSharedPreferences("marker",0);p.edit().putInt("pauses",p.getInt("pauses",0)+1).commit();state(2);}} }});state(3);session.setActive(true);}}
@Override public void onDestroy() {{if(session!=null)session.release();super.onDestroy();}}
}}''')
    provider = directory / 'MarkerProvider.java'
    provider.write_text(f'''package {package}; public class MarkerProvider extends android.content.ContentProvider {{
public boolean onCreate(){{return true;}}
public android.database.Cursor query(android.net.Uri uri,String[] projection,String selection,String[] arguments,String order){{android.database.MatrixCursor result=new android.database.MatrixCursor(new String[]{{"pauses","volume","maximum","muted"}});android.media.AudioManager audio=(android.media.AudioManager)getContext().getSystemService("audio");result.addRow(new Object[]{{getContext().getSharedPreferences("marker",0).getInt("pauses",0),audio.getStreamVolume(3),audio.getStreamMaxVolume(3),audio.isStreamMute(3)}});return result;}}
public android.os.Bundle call(String method,String arg,android.os.Bundle extras){{if(!"restore".equals(method))throw new IllegalArgumentException();String[] values=arg.split(",");android.media.AudioManager audio=(android.media.AudioManager)getContext().getSystemService("audio");audio.setStreamVolume(3,Integer.parseInt(values[0]),0);audio.adjustStreamVolume(3,Boolean.parseBoolean(values[1])?-100:100,0);return new android.os.Bundle();}}
public String getType(android.net.Uri uri){{return "vnd.android.cursor.item/fixture";}}
public android.net.Uri insert(android.net.Uri uri,android.content.ContentValues v){{throw new UnsupportedOperationException();}}
public int delete(android.net.Uri uri,String s,String[] a){{throw new UnsupportedOperationException();}}
public int update(android.net.Uri uri,android.content.ContentValues v,String s,String[] a){{throw new UnsupportedOperationException();}}
}}''')
    classes = directory / 'classes'; classes.mkdir()
    dex = directory / 'dex'; dex.mkdir()
    run('javac', '-source', '8', '-target', '8', '-classpath', jar, '-d', classes, source, provider)
    run(tools / 'd8', '--lib', jar, '--output', dex, *classes.rglob('*.class'))
    unsigned = directory / 'unsigned.apk'
    run(tools / 'aapt2', 'link', '--manifest', manifest, '-I', jar, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    key = directory / 'fixture.p12'
    run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'fixture', '-keyalg', 'RSA', '-validity', '2', '-dname', 'CN=AIRC disposable timer fixture')
    apk = directory / 'fixture.apk'
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', apk, unsigned)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', required=True, type=Path)
    args = parser.parse_args()
    token = uuid.uuid4().hex
    package = 'ernest.airc.timerfixture.p' + token
    directory = '/data/local/tmp/airc-timer-' + token
    binary = directory + '/airc-runtime'
    port = None; installed = created = started = False
    def adb(*command): return run('adb', '-s', args.serial, *command)
    previous = None
    original_audio = None

    async def verify():
        counter = 0
        async def request(socket, kind, arguments):
            nonlocal counter
            counter += 1
            await socket.send(json.dumps(dict(id=str(counter), protocol=1, type=kind, arguments=arguments)))
            reply = json.loads(await asyncio.wait_for(socket.recv(), 40))
            assert reply['id'] == str(counter) and reply['ok'], reply
            return json.loads(reply['result'])
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            tasks = []
            for action, seconds in [('pause', 60), ('open_app', 70), ('stop_app', 80)]:
                values = dict(action=action, kind='delay', delaySeconds=seconds, confirmed=True)
                if action != 'pause': values['package'] = package
                tasks.append(await request(socket, 'schedule_save', values))
            audio_tasks = []
            for action, seconds in [('set_volume', 90), ('mute', 100), ('mute', 110), ('unmute', 120), ('unmute', 130)]:
                values = dict(action=action, kind='delay', delaySeconds=seconds, confirmed=True)
                if action == 'set_volume': values['volumePercent'] = 40
                audio_tasks.append(await request(socket, 'schedule_save', values))
            print('Saved target timers; controller WebSocket is now disconnected.', flush=True)
        # The controller disconnects before each scheduled action executes.
        delay = max(0, tasks[0]['nextAt'] + 3 - time.time())
        await asyncio.sleep(delay)
        marker = adb('shell', f'content query --uri content://{package}.marker')
        if not re.search(r'pauses=[1-9][0-9]*', marker):
            async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
                diagnostic = await request(socket, 'schedule_list', {})
            raise AssertionError(f'Pause callback missing: {marker}; target records: {diagnostic}')
        print('PASS: pause delivered to the fixture MediaSession without a controller.', flush=True)
        adb('shell', 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME')
        await asyncio.sleep(max(0, tasks[1]['nextAt'] + 3 - time.time()))
        foreground = adb('shell', 'dumpsys activity activities')
        assert any(package in line for line in foreground.splitlines() if 'mResumedActivity:' in line or 'topResumedActivity=' in line), foreground[-2000:]
        print('PASS: scheduled launcher opened the fixture application.', flush=True)
        await asyncio.sleep(max(0, tasks[2]['nextAt'] + 5 - time.time()))
        dump = adb('shell', f'dumpsys package {package}')
        assert re.search(r'User 0:.*stopped=true', dump), dump[-2000:]
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            result = await request(socket, 'schedule_list', {})
            assert {t['id'] for t in result['tasks']} == {t['id'] for t in tasks + audio_tasks}, result
            assert all(t['lastStatus'] == 'submitted' and not t['enabled'] for t in result['tasks'] if t['id'] in {x['id'] for x in tasks}), result
        print('PASS: scheduled force-stop and target-owned execution records.', flush=True)
        for task in audio_tasks:
            await asyncio.sleep(max(0, task['nextAt'] + 3 - time.time()))
            marker = adb('shell', f'content query --uri content://{package}.marker')
            volume = int(re.search(r'volume=(\d+)', marker)[1])
            maximum = int(re.search(r'maximum=(\d+)', marker)[1])
            muted = re.search(r'muted=(true|false)', marker)[1] == 'true'
            if task['action'] == 'set_volume': assert volume == int(maximum * .4 + .5), marker
            else: assert muted == (task['action'] == 'mute'), marker
            print(f"PASS: {task['action']} without a controller; {marker.strip()}", flush=True)
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            result = await request(socket, 'schedule_list', {})
            assert all(t['lastStatus'] == 'submitted' and not t['enabled'] for t in result['tasks']), result
            assert next(t for t in result['tasks'] if t['action']=='set_volume')['volumePercent'] == 40, result

    try:
        activities = adb('shell', 'dumpsys activity activities')
        matches = re.findall(r'(?:mResumedActivity|topResumedActivity)[^\n]*? ([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)', activities)
        previous = matches[0] if matches else None
        abi = adb('shell', 'getprop ro.product.cpu.abi')
        assert {'x86_64':'x86_64','arm64-v8a':'aarch64','armeabi-v7a':'armv7'}[abi] in args.binary.name
        assert f'package:{package}' not in adb('shell', f'pm list packages {package}').splitlines()
        with tempfile.TemporaryDirectory(prefix='airc-timer-') as temp:
            apk = fixture(Path(temp), package)
            adb('install', str(apk)); installed = True
        adb('shell', f'am start -W -n {package}/.MainActivity')
        original_audio = adb('shell', f'content query --uri content://{package}.marker')
        adb('shell', f'mkdir -m 700 {directory}'); created = True
        adb('push', str(args.binary.resolve()), binary)
        adb('shell', f'chmod 700 {binary}')
        reply = json.loads(adb('shell', f'{binary} service start --state-dir {directory}/state --port 0 --no-mdns')); started = True
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(reply['port'])))
        asyncio.run(verify())
    finally:
        commands = []
        if started: commands.append(('shell', f'{binary} service stop --state-dir {directory}/state'))
        if port: commands.append(('forward', '--remove', 'tcp:' + str(port)))
        if created: commands.append(('shell', 'rm -rf -- ' + shlex.quote(directory)))
        if installed and original_audio:
            index = re.search(r'volume=(\d+)', original_audio)[1]
            muted = re.search(r'muted=(true|false)', original_audio)[1]
            commands.append(('shell', f'content call --uri content://{package}.marker --method restore --arg {index},{muted}'))
        if installed: commands.append(('uninstall', package))
        if installed and previous and not previous.startswith('com.android.systemui/'):
            commands.append(('shell', 'am start -W -n ' + shlex.quote(previous)))
        errors = []
        for command in commands:
            try: adb(*command)
            except Exception as error: errors.append(str(error))
        if errors: raise RuntimeError('Fixture cleanup failed: ' + '; '.join(errors))

if __name__ == '__main__': main()
