#!/usr/bin/env python3
"""Prepare HTTPS publication files locally, without uploading or publishing."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import base64
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="HTTPS directory that will host the files")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "dist/runtime")
    parser.add_argument("--min-app-version-code", type=int, default=1)
    parser.add_argument("--max-app-version-code", type=int)
    parser.add_argument("--signing-key", type=pathlib.Path, required=True, help="RSA private key kept outside the repository/APK")
    args = parser.parse_args()
    address = urlparse(args.base_url)
    if address.scheme != "https" or not address.hostname or address.username or address.password or address.query or address.fragment:
        parser.error("base URL must be an HTTPS directory without credentials, query or fragment")
    if args.min_app_version_code < 1 or (args.max_app_version_code is not None and args.max_app_version_code < args.min_app_version_code):
        parser.error("invalid app compatibility range")
    source = ROOT / "androidApp/src/main/assets/runtime"
    manifest = json.loads((source / "manifest.json").read_text())
    verified = []
    for asset in manifest["assets"]:
        if not re.fullmatch(r"[a-zA-Z0-9_-][a-zA-Z0-9_.-]*", asset["file"]):
            parser.error("invalid asset filename")
        binary = source / asset["file"]
        data = binary.read_bytes()
        if len(data) != asset["size"] or hashlib.sha256(data).hexdigest() != asset["sha256"]:
            parser.error("built asset failed size/hash validation")
        verified.append((asset, binary))
    for asset, binary in verified:
        asset["url"] = args.base_url.rstrip("/") + "/" + asset["file"]
    manifest["minAppVersionCode"] = args.min_app_version_code
    if args.max_app_version_code is not None:
        manifest["maxAppVersionCode"] = args.max_app_version_code
    payload = json.dumps(manifest, indent=2).encode() + b"\n"
    expected = subprocess.check_output(["openssl", "pkey", "-pubin", "-in", str(ROOT / "docs/release-public-key.pem"), "-outform", "DER"])
    trust_source = (ROOT / "androidApp/src/main/kotlin/ernest/airc/ReleaseTrust.kt").read_text()
    pinned = re.search(r'PUBLIC_KEY_DER = "([A-Za-z0-9+/=]+)"', trust_source)
    if pinned is None or base64.b64decode(pinned.group(1)) != expected:
        parser.error("publication public key does not match the APK's compiled trust root")
    actual = subprocess.check_output(["openssl", "pkey", "-in", str(args.signing_key), "-pubout", "-outform", "DER"])
    if actual != expected:
        parser.error("signing key does not match the APK release trust root")
    signature = subprocess.check_output(["openssl", "dgst", "-sha256", "-sign", str(args.signing_key)], input=payload)
    args.output.mkdir(parents=True, exist_ok=True)
    for asset, binary in verified:
        shutil.copyfile(binary, args.output / asset["file"])
    (args.output / "manifest.json").write_bytes(payload)
    (args.output / "manifest.json.sig").write_bytes(signature)
    subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(ROOT / "docs/release-public-key.pem"), "-signature", str(args.output / "manifest.json.sig"), str(args.output / "manifest.json")], check=True)
    print(f"Prepared {len(verified)} verified assets and signed compatibility manifest in {args.output}")


if __name__ == "__main__":
    main()
