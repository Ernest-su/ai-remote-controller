#!/usr/bin/env python3
"""Compile Android framework adapters, including the persistent input bridge, into Cargo OUT_DIR."""
import os
import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
output = pathlib.Path(sys.argv[1]); output.mkdir(parents=True, exist_ok=True)
sdk = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
android = sdk / 'platforms/android-36/android.jar'
d8 = sdk / 'build-tools/36.0.0/d8'
if not android.is_file() or not d8.is_file():
    raise SystemExit('Android platform bridge requires SDK platform 36 and build-tools 36.0.0; install missing components on the host.')
classes = output / 'classes'; classes.mkdir(exist_ok=True)
javac = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac') if os.environ.get('JAVA_HOME') else 'javac'
sources = sorted((root / 'runtime/platform/android/ernest/airc/bridge').glob('*.java'))
subprocess.run([javac, '--release', '8', '-classpath', str(android), '-d', str(classes), *map(str, sources)], check=True)
subprocess.run([str(d8), '--min-api', '26', '--lib', str(android), '--output', str(output), *map(str, classes.rglob('*.class'))], check=True)
