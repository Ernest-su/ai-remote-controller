#!/usr/bin/env python3
"""Validate the companion IME on an explicitly selected API 31+ device."""
import argparse
import base64
import hashlib
import tempfile
import time
import zipfile
from pathlib import Path
import re
import shlex
import subprocess
from instrumentation_result import require_passed

ROOT = Path(__file__).resolve().parent.parent


def physical_check(adb, shell, app, fixture, original, activity):
    """Use host ADB on OEM TVs that cannot reliably host phone instrumentation."""
    ime = "ernest.airc.ime/.RemoteInputMethod"
    enabled = shell("settings get secure enabled_input_methods")
    user = shell("am get-current-user")
    if not user.isdecimal():
        raise RuntimeError("Cannot identify Android user")
    installed_ime = installed_fixture = False
    failures = []

    def require(condition, message):
        if not condition:
            raise RuntimeError(message)

    def call(method, extra=""):
        return shell(f"content call --user {user} --uri content://ernest.airc.ime.control --method {method} {extra}")

    def status(reply):
        match = re.search(r"(?:\{|[ ,])status=([a-z_]+)(?=[,} ])", reply)
        require(match is not None, "Missing IME acknowledgement")
        return match.group(1)

    def await_ready(value):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            reply = call("status")
            require(status(reply) == "selected", "AIRC IME selection changed")
            if ("ready=true" in reply) == value:
                return
            time.sleep(0.2)
        raise RuntimeError(f"Editor readiness did not become {value}")

    def cleanup(label, action):
        try:
            action()
        except Exception as error:
            failures.append(f"{label}: {error}")

    try:
        # Extract the exact companion binary shipped with the controller, never a stale APK.
        with tempfile.TemporaryDirectory(prefix="airc-tv-ime-") as directory:
            ime_apk = Path(directory) / "airc-input-method.apk"
            with zipfile.ZipFile(app) as archive:
                ime_apk.write_bytes(archive.read("assets/airc-input-method.apk"))
            print(adb("install", "-t", str(ime_apk), timeout=120), end="", flush=True)
            installed_ime = True
            package = shell(f"pm path --user {user} ernest.airc.ime")
            remote_path = next(line.removeprefix("package:") for line in package.splitlines() if line.startswith("package:"))
            digest = shell(f"sha256sum {shlex.quote(remote_path)}").split()[0]
            require(digest == hashlib.sha256(ime_apk.read_bytes()).hexdigest(), "Installed IME digest mismatch")
        print(adb("install", str(fixture), timeout=120), end="", flush=True)
        installed_fixture = True
        require(status(call("remember")) == "remembered", "Cannot remember original IME")
        shell(f"ime enable --user {user} {ime}")
        shell(f"ime set --user {user} {ime}")
        require(status(call("status")) == "selected", "AIRC IME not selected")
        require(f"previous={original}" in call("status"), "Original IME was not retained")
        marker = "airc-physical-fixture-clipboard"
        shell(f"am start -W -n ernest.airc.mediafixture/.TextActivity --ez ime true --es clipboard {marker}")
        await_ready(True)
        print("PASS: matching IME installed; real editor focus detected", flush=True)
        text = "中文片名 🎬 %s; $(reboot) ' 字面文本"
        encoded = base64.b64encode(text.encode()).decode()
        require(status(call("commit", f"--extra text:s:{encoded}")) == "committed", "IME did not acknowledge commit")
        actual = shell("content read --uri content://ernest.airc.mediafixture.text/text")
        require(actual == text, "Fixture contents differ from synthetic Chinese/emoji/literal text")
        require(f"clipboard={marker}" in shell("content call --uri content://ernest.airc.mediafixture.text --method clipboard"),
                "IME commit changed clipboard")
        print("PASS: Chinese, emoji and shell characters received literally; clipboard unchanged", flush=True)
        shell("content call --uri content://ernest.airc.mediafixture.text --method restoreClipboard")
        shell("am force-stop ernest.airc.mediafixture")
        shell("am start -W -n ernest.airc.mediafixture/.TextActivity")
        await_ready(False)
        require(status(call("commit", f"--extra text:s:{encoded}")) == "no_editor", "Submission without editor was not rejected")
        require(shell("content read --uri content://ernest.airc.mediafixture.text/text") == text, "Rejected commit changed fixture text")
        print("PASS: editor blur detected; submission without editor rejected", flush=True)
    finally:
        if installed_fixture:
            cleanup("restore clipboard", lambda: shell("content call --uri content://ernest.airc.mediafixture.text --method restoreClipboard"))
        cleanup("restore original IME", lambda: shell(f"ime set --user {user} {shlex.quote(original)}"))
        if installed_ime:
            cleanup("disable temporary IME", lambda: shell(f"ime disable --user {user} {ime}"))
            cleanup("remove temporary IME", lambda: adb("uninstall", "ernest.airc.ime"))
        if installed_fixture:
            cleanup("remove fixture", lambda: adb("uninstall", "ernest.airc.mediafixture"))
        cleanup("restore original Activity", lambda: shell(f"am start -W -n {shlex.quote(activity)}"))
        require(not failures, "Cleanup failed: " + "; ".join(failures))
        require(shell("settings get secure default_input_method") == original, "Original IME not restored")
        require(shell("settings get secure enabled_input_methods") == enabled, "Enabled IMEs changed")
        for package in ("ernest.airc.ime", "ernest.airc.mediafixture"):
            require(f"package:{package}" not in shell(f"pm list packages --user all {package}").splitlines(), "Temporary package remains")
        print("PASS: original IME/enabled list restored; temporary packages removed; original Activity reopened", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-physical", action="store_true",
                        help="Use host ADB to test a physical TV; install only temporary IME and fixture packages")
    args = parser.parse_args()

    def adb(*command, timeout=60):
        return subprocess.check_output(["adb", "-s", args.serial, *command], cwd=ROOT, text=True, timeout=timeout)

    def shell(command):
        return adb("shell", command).strip()

    for package in ("ernest.airc.ime", "ernest.airc.mediafixture"):
        if f"package:{package}" in shell(f"pm list packages --user all {package}").splitlines():
            parser.error(f"Refusing to replace existing package: {package}")
    if int(shell("getprop ro.build.version.sdk")) < 31:
        parser.error("API 31+ device required")
    original = shell("settings get secure default_input_method")
    if not re.fullmatch(r"[A-Za-z0-9_.$/]+", original) or "/" not in original:
        parser.error("An existing restorable keyboard is required")
    resumed = shell("dumpsys activity activities")
    match = re.search(r"(?:mResumedActivity:|topResumedActivity=).*? ([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)(?: |})", resumed)
    activity = match.group(1) if match else None
    if args.allow_physical and activity is None:
        parser.error("Cannot identify foreground Activity to restore on the physical target")
    app = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    tests = ROOT / "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
    fixture = ROOT / "mediaFixture/build/outputs/apk/debug/mediaFixture-debug.apk"
    for path in ((app, fixture) if args.allow_physical else (app, tests, fixture)):
        if not path.is_file():
            parser.error("Build App, AndroidTest and mediaFixture debug APKs first")
    if args.allow_physical:
        physical_check(adb, shell, app, fixture, original, activity)
        return
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
