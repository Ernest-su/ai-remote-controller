#!/usr/bin/env python3
"""Build disposable signed base/config APKs; never included in product assets."""
import os
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
def main():
    sdk=Path(os.environ['ANDROID_HOME'])
    tools=sdk/'build-tools/36.0.0'
    output=ROOT/'build/split-fixtures'
    output.mkdir(parents=True,exist_ok=True)
    key=output/'fixture.p12'
    if not key.exists():
        subprocess.run(['keytool','-genkeypair','-keystore',str(key),'-storepass','android','-keypass','android','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=AIRC disposable split fixture'],check=True,stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(dir=output) as temp:
        temp=Path(temp)
        for version in (1,2):
            for split in ('base','config.en'):
                manifest=temp/'AndroidManifest.xml'
                split_attr='' if split=='base' else f' split="{split}"'
                manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ernest.airc.splitfixture" android:versionCode="{version}" android:versionName="fixture-{version}"{split_attr}><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="28"/><application android:label="AIRC split fixture" android:hasCode="false" android:debuggable="true"/></manifest>')
                unsigned=temp/'unsigned.apk'
                subprocess.run([str(tools/'aapt2'),'link','--manifest',str(manifest),'-I',str(sdk/'platforms/android-36/android.jar'),'-o',str(unsigned)],check=True)
                destination=output/f'{split}-v{version}.apk'
                subprocess.run([str(tools/'apksigner'),'sign','--ks',str(key),'--ks-pass','pass:android','--key-pass','pass:android','--out',str(destination),str(unsigned)],check=True)
    print('Built four disposable split fixture APKs')
if __name__=='__main__':main()
