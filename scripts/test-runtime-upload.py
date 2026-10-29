#!/usr/bin/env python3
"""Compare ADB sync uploads from a selected controller to explicit targets, in private directories."""
import argparse
import json
import ipaddress
from pathlib import Path
import subprocess
import time
from instrumentation_result import require_passed

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phone', required=True)
    parser.add_argument('--host', action='append', required=True, help='Explicit target IP, repeatable')
    parser.add_argument('--root', action='store_true', help='Use existing authorized su for decoding')
    parser.add_argument('--compression-only', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for host in args.host:
        ipaddress.ip_address(host)
    args.output.mkdir(parents=True, exist_ok=True)
    def adb(*command, timeout=180):
        return subprocess.check_output(['adb', '-s', args.phone, *command], cwd=ROOT, text=True, timeout=timeout)
    assert adb('get-state').strip() == 'device'
    for apk in ('androidApp/build/outputs/apk/debug/androidApp-debug.apk',
                'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk'):
        print(adb('install', '-r', apk), end='', flush=True)
    rows = []
    for host in args.host:
        command = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'ernest.airc.RuntimeUploadTargetTest',
                   '-e', 'uploadHost', host, '-e', 'uploadRoot', str(args.root).lower(),
                   '-e', 'uploadCompressionOnly', str(args.compression_only).lower(),
                   'ernest.airc.test/androidx.test.runner.AndroidJUnitRunner']
        started = time.monotonic()
        output = adb(*command, timeout=1800)
        (args.output / f'{host}.log').write_text(output)
        require_passed(output, 1)
        for line in output.splitlines():
            prefix = 'INSTRUMENTATION_STATUS: stream=UPLOAD_RESULT '
            if line.startswith(prefix):
                row = json.loads(line[len(prefix):]); rows.append(row)
                print(json.dumps(row, ensure_ascii=False), flush=True)
        print(f'{host}: complete in {time.monotonic() - started:.1f}s', flush=True)
    (args.output / 'results.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
