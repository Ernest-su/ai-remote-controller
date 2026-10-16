#!/usr/bin/env python3
"""Run phone file/mirror acceptance against an owned, disposable binary target service."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shlex
import uuid
import zipfile
from instrumentation_result import require_passed

ROOT = Path(__file__).resolve().parent.parent
SUITES = {
    "lan-connection-ui": ("ernest.airc.LanConnectionUiTest", 2),
    "appearance": ("ernest.airc.AppearanceUiTest", 2),
    "management-ui": ("ernest.airc.ManagementUiTest", 9),
    "app-packages-ui": ("ernest.airc.AppPackagesUiTest", 2),
    "profile-isolation": ("ernest.airc.EncryptedProfilesIsolationTest", 3),
    "product-pages": ("ernest.airc.ProductPagesUiTest", 1),
    "controller-ui": ("ernest.airc.RemoteNavigationUiTest,ernest.airc.GamepadOrientationUiTest,ernest.airc.RemoteExecutionUiTest,ernest.airc.RemoteLayoutUiTest,ernest.airc.DeviceInformationUiTest,ernest.airc.SystemActionUiTest,ernest.airc.SystemObservationUiTest,ernest.airc.InputToolsUiTest", 35),
    "models": ("ernest.airc.ModelProfilesUiTest", 1),
    "model-sync": ("ernest.airc.ModelProfileSyncTargetTest", 1),
    "gamepad-lifecycle": ("ernest.airc.GamepadLifecycleTargetTest", 5),
    "mirror-surface-ui": ("ernest.airc.MirrorSurfaceUiTest", 3),
    "mirror-ui": ("ernest.airc.RemoteNavigationUiTest#dynamicSurfaceRecreatesWithoutStartingOfflineCapture,ernest.airc.RemoteNavigationUiTest#ascrcpyAppParametersSurviveRotationAndReturnWithoutCapture,ernest.airc.RemoteNavigationUiTest#ascrcpyReplacesLegacyPngPreviewAfterRotation", 3),
    "ascrcpy": ("ernest.airc.AscrcpyPreviewTargetTest", 4),
    "files": ("ernest.airc.FileTransferTargetTest", 1),
    "touchpad": ("ernest.airc.TouchpadTargetTest", 1),
    "screenshots": ("ernest.airc.ScreenshotSaveTargetTest", 2),
    "screenshot-share": ("ernest.airc.ScreenshotShareTargetTest", 1),
    "pickers": ("ernest.airc.FilePickerTargetTest", 1),
    "picker-success": ("ernest.airc.FilePickerSuccessTargetTest", 1),
    "mirror": ("ernest.airc.ScreenMirrorLifecycleTargetTest", 2),
    "gamepad-ui": ("ernest.airc.GamepadOrientationUiTest", 2),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phone", required=True, help="ADB serial of the authorized controller phone")
    parser.add_argument("--target", help="ADB serial of the authorized Android target")
    parser.add_argument("--host", help="Target address reachable directly from the phone")
    parser.add_argument("--with-touch-receiver", action="store_true", help="Install owned static touch receiver for focused mirror diagnostics")
    parser.add_argument("--use-installed-phone", action="store_true", help="Verify matching installed phone APK hashes instead of reinstalling")
    parser.add_argument("--suite", choices=[*SUITES, "all"], default="all")
    args = parser.parse_args()
    phone_only=args.suite in ("lan-connection-ui", "product-pages", "appearance", "management-ui", "app-packages-ui", "mirror-surface-ui", "mirror-ui", "gamepad-ui", "models", "profile-isolation", "controller-ui")
    if not phone_only and (not args.target or not args.host):
        parser.error("This suite requires --target and --host")
    if not phone_only and args.phone == args.target:
        parser.error("phone and target must be different devices")

    root_target=args.suite=="gamepad-lifecycle"

    def adb(serial, *command, timeout=60):
        try:
            return subprocess.check_output(["adb", "-s", serial, *command], cwd=ROOT,
                                           text=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            if command[:3] == ("shell", "am", "instrument"):
                subprocess.run(["adb", "-s", serial, "shell", "am", "force-stop", "ernest.airc"], cwd=ROOT, check=True, timeout=30)
            if error.stdout:
                print(error.stdout.decode(errors="replace") if isinstance(error.stdout, bytes) else error.stdout, flush=True)
            raise

    def target_shell(command):
        return adb(args.target,"shell",f"su 0 sh -c {shlex.quote(command)}" if root_target else command)

    app = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    tests = ROOT / "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
    for path in (app, tests):
        if not path.is_file():
            parser.error("Build :androidApp:assembleDebug and :androidApp:assembleDebugAndroidTest first")
    for serial in ((args.phone,) if phone_only else (args.phone,args.target)):
        if adb(serial, "get-state").strip() != "device":
            parser.error(f"Device is not ready: {serial}")
    asset=None
    if not phone_only:
        abi = adb(args.target, "shell", "getprop ro.product.cpu.abi").strip()
        if abi not in ("armeabi-v7a", "arm64-v8a", "x86_64"):
            parser.error(f"Unsupported target ABI: {abi}")
        asset = ROOT / "androidApp/src/main/assets/runtime" / f"airc-runtime-{abi}"
        with zipfile.ZipFile(app) as archive:
            embedded = archive.read(f"assets/runtime/airc-runtime-{abi}")
        if not asset.is_file() or hashlib.sha256(asset.read_bytes()).digest() != hashlib.sha256(embedded).digest():
            parser.error("Runtime asset differs from the app APK; rebuild before testing")

    selected = [value for name,value in SUITES.items() if name not in ("lan-connection-ui", "product-pages", "appearance", "management-ui", "app-packages-ui", "gamepad-lifecycle", "models", "model-sync", "profile-isolation", "controller-ui")] if args.suite == "all" else [SUITES[args.suite]]
    directory = f"/data/local/tmp/airc-phone-acceptance-{uuid.uuid4()}"
    binary = f"{directory}/airc-runtime"
    touch_selected = not phone_only and (args.suite in ("all", "touchpad", "ascrcpy", "gamepad-lifecycle") or args.with_touch_receiver)
    receiver_apk = ROOT / "mediaFixture/build/outputs/apk/debug/mediaFixture-debug.apk"
    receiver_package = "ernest.airc.mediafixture"
    if touch_selected:
        if not receiver_apk.is_file():
            parser.error("Build :mediaFixture:assembleDebug first")
        if f"package:{receiver_package}" in adb(args.target, "shell", "pm", "list", "packages", "--user", "all", receiver_package).splitlines():
            parser.error("Refusing to replace an existing target fixture package")
    receiver_installed = False
    created = copied = False
    tests_installed = False
    service=None
    try:
        if args.use_installed_phone:
            for package, apk in (("ernest.airc", app), ("ernest.airc.test", tests)):
                paths = adb(args.phone, "shell", "pm", "path", package).splitlines()
                base = next((line.removeprefix("package:") for line in paths if line.endswith("/base.apk")), None)
                if base is None:
                    raise RuntimeError(f"Required phone package is not installed: {package}")
                digest = adb(args.phone, "shell", f"sha256sum {shlex.quote(base)}").split()[0]
                if digest != hashlib.sha256(apk.read_bytes()).hexdigest():
                    raise RuntimeError(f"Installed phone APK differs from the current build: {package}")
        else:
            print(adb(args.phone, "install", "-r", str(app), timeout=120), end="", flush=True)
            print(adb(args.phone, "install", "-r", str(tests), timeout=120), end="", flush=True)
        tests_installed = True
        if not phone_only:
            adb(args.target, "shell", f"mkdir -m 700 {directory}"); created = True
            adb(args.target, "push", str(asset), binary); copied = True
            adb(args.target, "shell", f"chmod 700 {binary}")
            service = json.loads(target_shell(
                f"{binary} service start --state-dir {directory}/state --port 0 --no-mdns"))
            if not service.get("ready") or not service.get("running"):
                raise RuntimeError("Independent target service did not become ready")
            if touch_selected:
                adb(args.target, "install", str(receiver_apk)); receiver_installed = True
                adb(args.target, "shell", "am", "start", "-W", "-n", f"{receiver_package}/{'.GamepadActivity' if root_target else '.TouchActivity'}")
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                   ",".join(item[0] for item in selected)]
        if service is not None:
            for prefix in ("file", "mirror"):
                command += ["-e", f"{prefix}TargetHost", args.host,
                            "-e", f"{prefix}TargetPort", str(service["port"])]
            command += ["-e", "ascrcpyTargetHost", args.host, "-e", "ascrcpyTargetPort", args.target.rsplit(":",1)[1] if ":" in args.target else "5555"]
        command += ["ernest.airc.test/androidx.test.runner.AndroidJUnitRunner"]
        output = adb(args.phone, *command, timeout=300)
        print(output, flush=True)
        count = sum(item[1] for item in selected)
        require_passed(output, count)
    finally:
        try:
            if copied:
                target_shell(f"{binary} service stop --state-dir {directory}/state")
        finally:
            try:
                if created:
                    target_shell(f"rm -rf -- {directory}")
                    if adb(args.target, "shell", f"if [ -e {directory} ]; then echo remains; fi").strip():
                        raise RuntimeError("Owned target directory was not removed")
            finally:
                try:
                    try:
                        if receiver_installed:
                            adb(args.target, "uninstall", receiver_package)
                    finally:
                        if tests_installed:
                            adb(args.phone, "uninstall", "ernest.airc.test")
                finally:
                    adb(args.phone, "shell", "am", "start", "-n", "ernest.airc/.MainActivity")


if __name__ == "__main__":
    main()
