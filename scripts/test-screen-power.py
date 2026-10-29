#!/usr/bin/env python3
"""Exercise a one-shot display-power bridge on an explicitly selected target and restore its mode."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid


def physical_modes(dump):
    modes = {}
    for section in re.split(r"(?m)^Display ", dump)[1:]:
        identity = re.match(r"(\d+)\s*\n", section)
        mode = re.search(r"(?m)^\s+powerMode=(\w+)", section)
        if identity and mode:
            modes[identity[1]] = mode[1]
    if not modes or any(mode not in ("On", "Off") for mode in modes.values()):
        raise RuntimeError("Cannot determine restorable physical display modes from SurfaceFlinger")
    return modes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--dex", required=True, type=Path, help="classes.dex from build-platform-bridge.py")
    parser.add_argument("--root", action="store_true", help="Use target su 0 sh for the same UID as a root runtime")
    args = parser.parse_args()
    if not args.dex.is_file():
        parser.error("--dex must be an existing file")

    def adb(*command, check=True):
        return subprocess.run(["adb", "-s", args.serial, *map(str, command)], check=check,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)

    def shell(command, check=True):
        if args.root:
            command = "su 0 sh -c " + shlex.quote(command)
        return adb("shell", command, check=check)

    def modes():
        return physical_modes(shell("dumpsys SurfaceFlinger").stdout)

    before = modes()
    if len(set(before.values())) != 1:
        parser.error("Physical displays have mixed modes; refusing to change them together")
    original = "on" if next(iter(before.values())) == "On" else "off"
    directory = "/data/local/tmp/airc-screen-power-" + uuid.uuid4().hex
    created = changed = False

    def invoke(operation, expected=0):
        result = shell(f"CLASSPATH={directory}/classes.dex app_process /system/bin "
                       f"ernest.airc.bridge.ScreenPowerBridge {shlex.quote(operation)}", check=False)
        if result.returncode != expected:
            raise AssertionError(f"{operation}: exit {result.returncode}, expected {expected}: {result.stdout}")
        if expected == 0:
            assert result.stdout.strip() == "{}", result.stdout
        else:
            assert "IllegalArgumentException: expected on or off" in result.stdout, result.stdout
        return result

    try:
        adb("shell", f"mkdir {directory} && chmod 700 {directory}")
        created = True
        adb("push", args.dex.resolve(), directory + "/classes.dex")
        for _ in range(2):
            for operation, expected in (("off", "Off"), ("on", "On")):
                changed = True
                invoke(operation)
                actual = modes()
                assert actual == dict.fromkeys(before, expected), actual
                print(f"PASS {operation}: exit 0, physical display modes {actual}", flush=True)
        invoke("invalid", expected=1)
        print("PASS invalid arguments: exit 1, error preserved", flush=True)
    finally:
        # Keep the restoration DEX if recovery fails, and report the failure.
        if changed:
            invoke(original)
            assert modes() == before, "Failed to restore original display modes"
        if created:
            shell("rm -rf -- " + shlex.quote(directory))
        print("Restored physical display modes: " + json.dumps(before), flush=True)


if __name__ == "__main__":
    main()
