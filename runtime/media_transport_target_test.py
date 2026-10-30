#!/usr/bin/env python3
"""Exercise selected-session media transport on an owned emulator with two silent fixture sessions."""
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
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="33"/>
<application android:label="AIRC playback fixture"><activity android:name=".MainActivity" android:exported="true"/></application></manifest>''')
    source = directory / 'MainActivity.java'
    source.write_text(f'''package {package};
public class MainActivity extends android.app.Activity {{
private final android.media.session.MediaSession[] sessions=new android.media.session.MediaSession[2];
private final int[] states={{2,2}}, tracks={{1,1}}; private final long[] positions={{1000,1000}};
private void report(int i) {{
 sessions[i].setMetadata(new android.media.MediaMetadata.Builder().putString("android.media.metadata.TITLE","Track "+tracks[i])
  .putString("android.media.metadata.ARTIST","Fixture artist").putString("android.media.metadata.ALBUM","Fixture album").putLong("android.media.metadata.DURATION",60000).build());
 sessions[i].setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(i==0?310:512).setState(states[i],positions[i],states[i]==3?1:0).build());
}}
@Override public void onCreate(android.os.Bundle saved) {{super.onCreate(saved);
 android.widget.TextView text=new android.widget.TextView(this);text.setText("AIRC silent transport fixture");setContentView(text);
 for(int i=0;i<2;i++){{final int n=i;sessions[i]=new android.media.session.MediaSession(this,i==0?"primary":"secondary");sessions[i].setFlags(3);
 sessions[i].setCallback(new android.media.session.MediaSession.Callback() {{
 public void onPlay(){{states[n]=3;report(n);}} public void onPause(){{states[n]=2;report(n);}}
 public void onSkipToNext(){{tracks[n]++;report(n);}} public void onSkipToPrevious(){{tracks[n]=Math.max(1,tracks[n]-1);report(n);}}
 public void onSeekTo(long p){{positions[n]=p;report(n);}}
 }});report(i);sessions[i].setActive(true);}}
}}
@Override public void onDestroy(){{for(android.media.session.MediaSession s:sessions)if(s!=null)s.release();super.onDestroy();}}
}}''')
    classes = directory / 'classes'; classes.mkdir()
    dex = directory / 'dex'; dex.mkdir()
    run('javac', '--release', '8', '-classpath', jar, '-d', classes, source)
    run(tools / 'd8', '--lib', jar, '--output', dex, *classes.rglob('*.class'))
    unsigned = directory / 'unsigned.apk'
    run(tools / 'aapt2', 'link', '--manifest', manifest, '-I', jar, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a') as archive:
        archive.write(dex / 'classes.dex', 'classes.dex')
    key = directory / 'fixture.p12'
    run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'fixture', '-keyalg', 'RSA', '-validity', '2', '-dname', 'CN=AIRC playback fixture')
    apk = directory / 'fixture.apk'
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--out', apk, unsigned)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--binary', required=True, type=Path)
    args = parser.parse_args()
    def adb(*command): return run('adb', '-s', args.serial, *command)
    if not re.fullmatch(r'emulator-\d+', args.serial) or adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        parser.error('An explicitly selected owned Android emulator is required')
    abi = adb('shell', 'getprop', 'ro.product.cpu.abi')
    if args.binary.name != f'airc-runtime-{abi}': parser.error('Supply the runtime matching the emulator ABI')
    token = uuid.uuid4().hex
    package = 'ernest.airc.playbackfixture.p' + token
    directory = '/data/local/tmp/airc-playback-' + token
    binary = directory + '/airc-runtime'
    state = directory + '/state'
    installed = created = started = False
    port = None
    activities = adb('shell', 'dumpsys', 'activity', 'activities')
    current = re.search(r'(?:mResumedActivity|topResumedActivity).*? ([\w.]+/[\w.$]+)', activities)
    original = current[1] if current else None

    async def verify():
        async with websockets.connect(f'ws://127.0.0.1:{port}/ws') as socket:
            sequence = 0
            async def request(tool, arguments, ok=True):
                nonlocal sequence
                sequence += 1
                await socket.send(json.dumps(dict(id=str(sequence), protocol=1, type='tool', tool=tool, arguments=arguments)))
                result = json.loads(await asyncio.wait_for(socket.recv(), 30))
                assert result['id'] == str(sequence) and result['ok'] == ok, result
                return json.loads(result['result']) if ok else result
            async def sessions():
                result = await request('media.status', {})
                assert result['transportAvailable'], result
                return {s['sessionTag']: s for s in result['sessions'] if s['packageName'] == package}
            async def observed(tag, field, expected):
                for _ in range(20):
                    values = await sessions()
                    if values.get(tag, {}).get(field) == expected: return values
                    await asyncio.sleep(.1)
                raise AssertionError((tag, field, expected, values))
            values = await observed('primary', 'state', 2)
            assert set(values) == {'primary', 'secondary'}, values
            assert values['primary']['appName'] == 'AIRC playback fixture'
            assert values['primary']['artist'] == 'Fixture artist' and values['primary']['actions'] == 310
            for command, field, expected in [('play', 'state', 3), ('pause', 'state', 2), ('next', 'description', 'Track 2'), ('previous', 'description', 'Track 1')]:
                result = await request('media.command', dict(package=package, sessionTag='primary', command=command))
                assert result['submitted'] and not result['playbackVerified'] and result['sessionTag'] == 'primary', result
                values = await observed('primary', field, expected)
                assert values['secondary']['state'] == 2 and values['secondary']['description'] == 'Track 1', values
            print('PASS selected-session play/pause/next/previous and friendly metadata; second session unchanged', flush=True)
            await request('media.command', dict(package=package, command='play'), ok=False)
            await request('media.command', dict(package=package, sessionTag='missing', command='play'), ok=False)
            for command, expected in [('play',3),('play',3),('pause',2),('pause',2)]:
                await request('media.command',dict(package=package,sessionTag='secondary',command=command))
                values=await observed('secondary','state',expected)
                assert values['primary']['state']==2,values
            await request('media.command',dict(package=package,sessionTag='secondary',command='next'),ok=False)
            print('PASS toggle-only selected session play/pause; duplicate direction does not invert state',flush=True)
            await request('media.command', dict(package=package, sessionTag='primary', command='toggle'), ok=False)
            seek_start=time.perf_counter()
            seek_reply=await request('media.seek', dict(package=package, sessionTag='primary', positionMs=12000))
            assert seek_reply['session']['sessionTag']=='primary' and seek_reply['session']['positionMs']==12000,seek_reply
            print('Seek acknowledgement with observed position milliseconds: '+str(round((time.perf_counter()-seek_start)*1000,1)),flush=True)
            values = await observed('primary', 'positionMs', 12000)
            assert values['secondary']['positionMs'] == 1000
            print('PASS ambiguous/missing/unsupported commands rejected; seek remains session-bound', flush=True)
            samples=[]
            for _ in range(5):
                start=time.perf_counter(); await request('media.status', {}); samples.append(round((time.perf_counter()-start)*1000,1))
            print('Warm persistent media.status milliseconds: '+json.dumps(samples),flush=True)
            original_audio=await request('media.volume',{})
            try:
                applied=await request('media.volume',dict(percent=40))
                assert applied['verified'] and applied['stream']=='media',applied
                samples=[]
                for _ in range(5):
                    start=time.perf_counter();await request('media.volume',{});samples.append(round((time.perf_counter()-start)*1000,1))
                print('Warm persistent media.volume milliseconds: '+json.dumps(samples),flush=True)
            finally:
                percent=round(100*(original_audio['volumeIndex']-original_audio['minVolumeIndex'])/(original_audio['maxVolumeIndex']-original_audio['minVolumeIndex']))
                restored=await request('media.volume',dict(percent=percent))
                assert restored['volumeIndex']==original_audio['volumeIndex'],restored
                if restored['muted'] != original_audio['muted']:
                    dex=adb('shell',f'ls {state}/platform/android-*.dex')
                    user=adb('shell','am','get-current-user')
                    action='mute' if original_audio['muted'] else 'unmute'
                    adb('shell',f'CLASSPATH={shlex.quote(dex)} app_process /system/bin ernest.airc.bridge.AudioBridge {action} {user}')
                assert (await request('media.volume',{}))['muted']==original_audio['muted']
    try:
        adb('shell', 'mkdir', '-m', '700', directory); created = True
        adb('push', args.binary.resolve(), binary); adb('shell', 'chmod', '700', binary)
        with tempfile.TemporaryDirectory(prefix='airc-playback-fixture-') as temp:
            apk = fixture(Path(temp), package)
            assert 'Success' in adb('install', apk)
            installed = True
        adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
        started = True
        service = json.loads(adb('shell', f'{binary} service start --state-dir {state} --port 0 --no-mdns'))
        assert service['ready'] and service['running'], service
        port = int(adb('forward', 'tcp:0', 'tcp:' + str(service['port'])))
        asyncio.run(verify())
    finally:
        try:
            if installed: adb('uninstall', package)
        finally:
            try:
                if started: adb('shell', f'{binary} service stop --state-dir {state}')
            finally:
                if port: adb('forward', '--remove', f'tcp:{port}')
                if created: adb('shell', f'rm -rf -- {shlex.quote(directory)}')
                if original: adb('shell', 'am', 'start', '-W', '-n', original)


if __name__ == '__main__':
    main()
