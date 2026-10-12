#!/usr/bin/env python3
"""Run isolated JVM tool acceptance; does not execute lock/reboot/shutdown."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SUITES = {
    "AppCacheTargetTest": 2,
    "AppInstallationTargetTest": 1,
    "SplitApkTargetTest": 1,
    "AppUninstallTargetTest": 1,
    "ApkInspectionTargetTest": 1,
    "AppLabelsTargetTest": 1,
    "FileManagementTargetTest": 1,
    "DirectoryCreationTargetTest": 1,
    "DeviceInformationTargetTest": 1,
    "RemoteTextTargetTest": 1,
    "GamepadTargetTest": 1,
    "DownloadFaultTargetTest": 2,
    "UploadFaultTargetTest": 4,
    "LargeTransferTargetTest": 1,
    "LargePublicationTargetTest": 1,
    "StorageAnalysisTargetTest": 1,
    "StandardMediaTargetTest": 1,
    "ImageMediaTargetTest": 1,
    "PublicationRecoveryTest": 1,
    "SystemActionsTargetTest": 1,
    "SystemStateTargetTest": 1,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, help="Authorized target ADB serial; root required for cache/system probes")
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(["adb", "-s", args.target, *command], text=True, timeout=60)

    assert adb("get-state").strip() == "device"
    abi = adb("shell", "getprop ro.product.cpu.abi").strip()
    assert abi in ("armeabi-v7a", "arm64-v8a", "x86_64")
    asset = ROOT / "androidApp/src/main/assets/runtime" / f"airc-runtime-{abi}"
    apk = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    with zipfile.ZipFile(apk) as archive:
        assert hashlib.sha256(asset.read_bytes()).digest() == hashlib.sha256(archive.read(f"assets/runtime/airc-runtime-{abi}")).digest(), "Rebuild APK: runtime asset mismatch"

    def main_status():
        return json.loads(adb("shell", "if [ -x /data/local/tmp/airc/airc-runtime ]; then /data/local/tmp/airc/airc-runtime service status --state-dir /data/local/tmp/airc/state; else echo '{}'; fi"))

    before = main_status()
    command = ["./gradlew", "--offline", "--no-daemon", ":desktopApp:jvmTest", f"-PaircTargetSerial={args.target}"]
    for name in SUITES:
        command += ["--tests", f"ernest.airc.desktop.{name}"]
    try:
        subprocess.run(command, cwd=ROOT, check=True)
        for name, count in SUITES.items():
            report = ROOT / "desktopApp/build/test-results/jvmTest" / f"TEST-ernest.airc.desktop.{name}.xml"
            root = ET.parse(report).getroot()
            assert int(root.get("tests", "0")) == count, f"{name}: unexpected test count"
            assert all(int(root.get(key, "0")) == 0 for key in ("failures", "errors", "skipped")), f"{name}: requires every case to pass"
        print(f"PASS: {sum(SUITES.values())} isolated target tool cases; zero skips")
    finally:
        after = main_status()
        assert (before.get("running"), before.get("pid")) == (after.get("running"), after.get("pid")), "Original main service changed"
        print("Original main service running/PID unchanged")


if __name__ == "__main__":
    main()
