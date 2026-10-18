#!/usr/bin/env python3
"""Validate the companion IME on an explicitly selected, owned API 31+ emulator."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
from instrumentation_result import require_passed

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()

    def adb(*command, timeout=60):
        return subprocess.check_output(["adb", "-s", args.serial, *command], cwd=ROOT, text=True, timeout=timeout)

    def shell(command):
        return adb("shell", command).strip()

    for package in ("ernest.airc.ime", "ernest.airc.mediafixture"):
        if f"package:{package}" in shell(f"pm list packages --user all {package}").splitlines():
            parser.error(f"Refusing to replace existing package: {package}")
    if int(shell("getprop ro.build.version.sdk")) < 31:
        parser.error("API 31+ emulator required")
    original = shell("settings get secure default_input_method")
    if not re.fullmatch(r"[A-Za-z0-9_.$/]+", original) or "/" not in original:
        parser.error("An existing restorable keyboard is required")
    resumed = shell("dumpsys activity activities")
    match = re.search(r"mResumedActivity:.*? ([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+) ", resumed)
    activity = match.group(1) if match else None
    app = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    tests = ROOT / "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
    fixture = ROOT / "mediaFixture/build/outputs/apk/debug/mediaFixture-debug.apk"
    for path in (app, tests, fixture):
        if not path.is_file():
            parser.error("Build App, AndroidTest and mediaFixture debug APKs first")
    installed_fixture = installed_tests = False
    try:
        print(adb("install", "-r", str(app), timeout=120), end="", flush=True)
        print(adb("install", "-r", str(tests), timeout=120), end="", flush=True)
        installed_tests = True
        print(adb("install", str(fixture), timeout=120), end="", flush=True)
        installed_fixture = True
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                     "ernest.airc.TvInputMethodTargetTest", "-e", "ownedImeTarget", "true",
                     "ernest.airc.test/androidx.test.runner.AndroidJUnitRunner", timeout=180)
        print(output, flush=True)
        require_passed(output, 1)
    finally:
        try:
            if installed_fixture:
                shell("content call --uri content://ernest.airc.mediafixture.text --method restoreClipboard")
            shell(f"ime set {shlex.quote(original)}")
            if "package:ernest.airc.ime" in shell("pm list packages --user all ernest.airc.ime").splitlines():
                shell("ime disable ernest.airc.ime/.RemoteInputMethod")
                adb("uninstall", "ernest.airc.ime")
        finally:
            if installed_fixture:
                adb("uninstall", "ernest.airc.mediafixture")
            if installed_tests:
                adb("uninstall", "ernest.airc.test")
            if activity:
                shell(f"am start -W -n {activity}")


if __name__ == "__main__":
    main()
