#!/usr/bin/env python3
"""Run local acceptance checks; Android device/provider checks remain separate."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--python', default=sys.executable, help='Python with websockets and zeroconf installed')
    parser.add_argument('--scope', choices=('full', 'ui', 'client', 'runtime', 'commit'), default='full',
                        help='Select development checks, or commit for the pre-commit documentation check')
    parser.add_argument('--build-apk', action='store_true', help='Also build app/test APKs for ui or client scope')
    parser.add_argument('--release-fixture', type=Path, help='Existing signed manifest; required for full gate')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    fixture = args.release_fixture.resolve() if args.release_fixture else None
    if args.scope == 'full' and fixture is None:
        parser.error('--release-fixture is required for the full gate; use --scope for focused development checks')
    if fixture is not None:
        for path in (fixture, Path(str(fixture) + '.sig')):
            if not path.is_file():
                parser.error(f'missing signed fixture: {path}')
    if args.build_apk and args.scope not in ('ui', 'client'):
        parser.error('--build-apk is only used with --scope ui or client')

    def run(command):
        print('Running: ' + ' '.join(map(str, command)), flush=True)
        subprocess.run(command, cwd=root, check=True)

    if args.scope == 'commit':
        run([args.python, '-m', 'mkdocs', 'build', '--strict'])
        return
    if args.scope in ('ui', 'client'):
        tasks = [':desktopApp:jvmTest', '--tests', '*UiTest', '--tests', '*AppearanceTest', ':androidApp:testDebugUnitTest'] if args.scope == 'ui' else [':shared:jvmTest', ':client:test', ':androidApp:testDebugUnitTest']
        if args.build_apk:
            tasks += [':androidApp:assembleDebug', ':androidApp:assembleDebugAndroidTest']
        command = ['./gradlew', *tasks, '--no-daemon']
        if fixture is not None:
            command.append(f'-PaircReleaseFixture={fixture}')
        run(command)
        print('Focused checks passed. Device/provider checks were not run; inspect reports for conditional skips.')
        return

    run([args.python, '-c', 'import websockets, zeroconf'])
    for operation in ('test', 'build'):
        run(['cargo', operation, '--locked', '--manifest-path', 'runtime/Cargo.toml'])
    run(['cargo', 'clippy', '--locked', '--manifest-path', 'runtime/Cargo.toml', '--all-targets', '--', '-D', 'warnings'])
    if args.scope == 'runtime':
        for script in ('smoke_test.py', 'e2e_test.py'):
            run([args.python, f'runtime/{script}'])
        print('Host runtime checks passed. Android devices and real providers were not exercised.')
        return
    run(['./gradlew', ':shared:jvmTest', ':client:test', ':desktopApp:jvmTest', ':androidApp:testDebugUnitTest', ':androidApp:assembleDebug', ':androidApp:assembleDebugAndroidTest', f'-PaircReleaseFixture={fixture}', '--no-daemon'])
    for script in ('smoke_test.py', 'e2e_test.py'):
        run([args.python, f'runtime/{script}'])
    apk = root / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'
    with zipfile.ZipFile(apk) as archive:
        manifest = json.loads(archive.read('assets/runtime/manifest.json'))
        assert {asset['abi'] for asset in manifest['assets']} == {'arm64-v8a', 'armeabi-v7a', 'x86_64'}
        for asset in manifest['assets']:
            binary = archive.read('assets/runtime/' + asset['file'])
            assert len(binary) == asset['size'], asset['abi']
            assert hashlib.sha256(binary).hexdigest() == asset['sha256'], asset['abi']
        assert archive.testzip() is None
    print('Local checks passed; 3 APK ABI assets verified. Physical devices and real providers were not exercised.')


if __name__ == '__main__':
    main()
