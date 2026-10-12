#!/usr/bin/env python3
"""Prepare the pinned, unmodified scrcpy server in generated build assets."""
import hashlib
from pathlib import Path
import sys
import urllib.request

VERSION = '4.0'
SHA256 = '84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a'
URL = f'https://github.com/Genymobile/scrcpy/releases/download/v{VERSION}/scrcpy-server-v{VERSION}'
output = Path(sys.argv[1]) / f'scrcpy-server-v{VERSION}'
if not output.is_file() or hashlib.sha256(output.read_bytes()).hexdigest() != SHA256:
    with urllib.request.urlopen(URL, timeout=60) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit('scrcpy server checksum or size mismatch')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.tmp')
    temporary.write_bytes(data)
    temporary.replace(output)
