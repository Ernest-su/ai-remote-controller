#!/usr/bin/env python3
"""Cross-compile device binaries with the Android NDK and bundle a hash manifest.

ANDROID_NDK_HOME overrides the newest NDK in ANDROID_HOME/ndk.
By default build all three supported ABIs; --abi can narrow a local test build.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android26-clang"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi26-clang"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android26-clang"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", action="append", choices=TARGETS)
    args = parser.parse_args()
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/tmp/nl2sh-android-sdk")))
    ndk = os.environ.get("ANDROID_NDK_HOME")
    if not ndk:
        versions = sorted((sdk / "ndk").glob("*"), key=lambda p: tuple(int(v) for v in p.name.split(".")))
        if not versions:
            sys.exit("No NDK installed; set ANDROID_NDK_HOME or install an NDK with android sdk.")
        ndk = str(versions[-1])
    host = "linux-x86_64" if sys.platform.startswith("linux") else "darwin-x86_64"
    toolchain = pathlib.Path(ndk) / "toolchains/llvm/prebuilt" / host / "bin"
    output = ROOT / "androidApp/src/main/assets/runtime"
    output.mkdir(parents=True, exist_ok=True)
    cargo_env = os.environ.copy()
    # A bind-mounted workspace may contain host build scripts linked against a newer glibc.
    # Keep container builds separate while preserving an explicit Cargo target directory.
    if not cargo_env.get("CARGO_TARGET_DIR") and (pathlib.Path("/.dockerenv").exists() or pathlib.Path("/run/.containerenv").exists()):
        cargo_env["CARGO_TARGET_DIR"] = str(ROOT / "runtime/target/android-container")
    metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--format-version", "1", "--no-deps", "--manifest-path", str(ROOT / "runtime/Cargo.toml")], env=cargo_env, text=True))
    target_directory = pathlib.Path(metadata["target_directory"])
    assets = []
    for abi in args.abi or TARGETS:
        target, compiler = TARGETS[abi]
        if not (toolchain / compiler).is_file():
            sys.exit(f"Missing NDK compiler: {toolchain / compiler}")
        subprocess.run(["rustup", "target", "add", target], check=True)
        env = cargo_env.copy()
        env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(toolchain / compiler)
        env["CC_" + target.replace("-", "_")] = str(toolchain / compiler)
        env["AR_" + target.replace("-", "_")] = str(toolchain / "llvm-ar")
        env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
        subprocess.run(["cargo", "build", "--locked", "--release", "--manifest-path", str(ROOT / "runtime/Cargo.toml"), "--target", target], env=env, check=True)
        name = f"airc-runtime-{abi}"
        binary = target_directory / target / "release/airc-runtime"
        shutil.copyfile(binary, output / name)
        assets.append({"abi": abi, "file": name, "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(), "size": binary.stat().st_size})
    version = metadata["packages"][0]["version"]
    manifest = {"version": version, "protocol": 1, "assets": assets}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Bundled {len(assets)} runtime ABI(s), version {version}")


if __name__ == "__main__":
    main()
