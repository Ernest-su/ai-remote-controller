#!/usr/bin/env python3
"""Build and verify signed Release attachments locally, without publishing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def validate_version(tag):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", tag):
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH, optionally with a prerelease suffix")
    cargo = tomllib.loads((ROOT / "runtime/Cargo.toml").read_text())["package"]["version"]
    android = (ROOT / "androidApp/build.gradle.kts").read_text()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', android).group(1)
    code = int(re.search(r'versionCode\s*=\s*(\d+)', android).group(1))
    if tag != "v" + cargo or version != cargo:
        raise ValueError("Tag, Android versionName and runtime Cargo version must match")
    ime = (ROOT / "tvInputMethod/build.gradle.kts").read_text()
    ime_version = re.search(r'versionName\s*=\s*"([^"]+)"', ime).group(1)
    ime_code = int(re.search(r'versionCode\s*=\s*(\d+)', ime).group(1))
    if ime_version != version or ime_code != code:
        raise ValueError("TV input method versionName and versionCode must match the Android controller")
    return code


def verify_apk(apk, directory, sdk):
    tools = sdk / "build-tools/36.0.0/apksigner"
    result = subprocess.check_output([str(tools), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", result)
    expected = json.loads((ROOT / "docs/release-signing.json").read_text())["androidCertificateSha256"]
    if certificate is None or certificate.group(1).lower() != expected:
        raise ValueError("APK signing certificate differs from the published certificate fingerprint")
    manifest = json.loads((directory / "manifest.json").read_text())
    if {asset["abi"] for asset in manifest["assets"]} != {"arm64-v8a", "armeabi-v7a", "x86_64"} or len(manifest["assets"]) != 3:
        raise ValueError("Expected exactly three runtime ABIs")
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("Invalid APK ZIP content")
        for source, name in (("LICENSE", "LICENSE"), ("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"),
                             ("ascrcpy/LICENSE", "LICENSE-Apache-2.0.txt")):
            if archive.read("assets/licenses/" + name) != (ROOT / source).read_bytes():
                raise ValueError("APK license text differs from the source distribution")
        for asset in manifest["assets"]:
            data = archive.read("assets/runtime/" + asset["file"])
            if len(data) != asset["size"] or hashlib.sha256(data).hexdigest() != asset["sha256"]:
                raise ValueError("APK runtime differs from the signed release manifest")
            if (directory / asset["file"]).read_bytes() != data:
                raise ValueError("Published runtime differs from the APK runtime")
    print("Verified APK certificate and all three embedded/published runtime assets.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "Ernest-su/ai-remote-controller"))
    parser.add_argument("--output", type=Path, default=ROOT / "dist/release")
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    try:
        code = validate_version(args.tag)
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
            raise ValueError("Invalid GitHub repository")
        if args.validate_only:
            print(f"Validated {args.tag} (Android versionCode {code})")
            return
        for name in ("AIRC_ANDROID_KEYSTORE", "AIRC_ANDROID_STORE_PASSWORD", "AIRC_ANDROID_KEY_ALIAS", "AIRC_ANDROID_KEY_PASSWORD", "AIRC_RUNTIME_SIGNING_KEY", "AIRC_RUNTIME_SIGNING_KEY_PASSWORD"):
            if not os.environ.get(name):
                raise ValueError(f"Missing signing environment variable: {name}")
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=True)
        if any(output.iterdir()):
            raise ValueError("Use an empty release output directory to avoid publishing stale files")
        def run(*command):
            subprocess.run(command, cwd=ROOT, check=True)
        run("python3", "scripts/build-runtime.py")
        run("python3", "scripts/prepare-runtime-release.py", "--base-url",
            f"https://github.com/{args.repository}/releases/download/{args.tag}",
            "--output", str(output), "--signing-key", os.environ["AIRC_RUNTIME_SIGNING_KEY"],
            "--min-app-version-code", str(code), "--max-app-version-code", str(code))
        run("./gradlew", ":androidApp:testDebugUnitTest", "--tests", "ernest.airc.ReleaseTrustFixtureTest",
            ":androidApp:assembleRelease", f"-PaircReleaseFixture={output / 'manifest.json'}", "--no-daemon")
        apk = ROOT / "androidApp/build/outputs/apk/release/androidApp-release.apk"
        sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
        verify_apk(apk, output, sdk)
        shutil.copyfile(apk, output / f"airc-{args.tag}.apk")
        for source, name in (("LICENSE", "LICENSE"), ("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"),
                             ("ascrcpy/LICENSE", "LICENSE-Apache-2.0.txt"),
                             ("docs/release-public-key.pem", "release-public-key.pem"),
                             ("docs/android-release-certificate.pem", "android-release-certificate.pem")):
            shutil.copyfile(ROOT / source, output / name)
        checksums = "".join(f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}\n"
                            for file in sorted(output.iterdir()) if file.is_file())
        (output / "SHA256SUMS").write_text(checksums)
        print(f"Prepared verified release attachments in {output}; nothing was uploaded.")
    except (ValueError, KeyError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
