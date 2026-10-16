#!/usr/bin/env python3
"""Provision release keys outside the checkout; never print their contents."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SECRET_FILES = {
    "ANDROID_SIGNING_KEYSTORE_BASE64": "android-keystore.base64",
    "ANDROID_SIGNING_STORE_PASSWORD": "android-store-password.txt",
    "ANDROID_SIGNING_KEY_ALIAS": "android-key-alias.txt",
    "ANDROID_SIGNING_KEY_PASSWORD": "android-key-password.txt",
    "RUNTIME_SIGNING_PRIVATE_KEY": "runtime-signing-private.pem",
    "RUNTIME_SIGNING_KEY_PASSWORD": "runtime-key-password.txt",
}


def outside_checkout(path):
    resolved = Path(path).expanduser().resolve()
    if resolved == ROOT or ROOT in resolved.parents:
        raise ValueError("Signing material must be stored outside the checkout")
    return resolved


def create_directory(path):
    path = outside_checkout(path)
    # Refuse to replace a prior signing identity, including an empty directory.
    path.mkdir(mode=0o700, parents=True, exist_ok=False)
    return path


def private_write(path, payload):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(payload)


def command(*args, capture=False):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                          stderr=subprocess.PIPE).stdout


def generate(output):
    if not shutil.which("openssl") or not shutil.which("keytool"):
        raise ValueError("openssl and JDK keytool are required")
    folder = create_directory(output)
    password = secrets.token_urlsafe(48)
    for filename, value in (("android-store-password.txt", password),
                            ("android-key-password.txt", password),
                            ("android-key-alias.txt", "airc-release"),
                            ("runtime-key-password.txt", secrets.token_urlsafe(48))):
        private_write(folder / filename, (value + "\n").encode())
    store = folder / "android-release.p12"
    old_mask = os.umask(0o077)
    try:
        command("keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", str(store),
                "-storepass:file", str(folder / "android-store-password.txt"),
                "-keypass:file", str(folder / "android-key-password.txt"),
                "-alias", "airc-release", "-keyalg", "RSA", "-keysize", "3072",
                "-sigalg", "SHA256withRSA", "-validity", "10000",
                "-dname", "CN=Ernest-su, OU=AIRC, O=Ernest-su")
        command("keytool", "-exportcert", "-rfc", "-keystore", str(store),
                "-storepass:file", str(folder / "android-store-password.txt"),
                "-alias", "airc-release", "-file", str(folder / "android-signing-certificate.pem"))
        command("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072",
                "-aes-256-cbc", "-pass", f"file:{folder / 'runtime-key-password.txt'}",
                "-out", str(folder / "runtime-signing-private.pem"))
        command("openssl", "pkey", "-in", str(folder / "runtime-signing-private.pem"),
                "-passin", f"file:{folder / 'runtime-key-password.txt'}", "-pubout",
                "-out", str(folder / "runtime-signing-public.pem"))
    finally:
        os.umask(old_mask)
    private_write(folder / "android-keystore.base64", base64.b64encode(store.read_bytes()) + b"\n")
    cert = command("openssl", "x509", "-in", str(folder / "android-signing-certificate.pem"),
                   "-outform", "DER", capture=True)
    public = command("openssl", "pkey", "-pubin", "-in", str(folder / "runtime-signing-public.pem"),
                     "-outform", "DER", capture=True)
    info = {"repository": "Ernest-su/ai-remote-controller", "keyAlias": "airc-release",
            "androidCertificateSha256": hashlib.sha256(cert).hexdigest(),
            "runtimePublicKeySha256": hashlib.sha256(public).hexdigest(),
            "githubSecrets": SECRET_FILES}
    private_write(folder / "signing-info.json", (json.dumps(info, indent=2) + "\n").encode())
    print(f"Generated signing material in {folder}; no secrets were printed.")


def restore(output):
    # Validate the complete environment before creating any files.
    values = {name: os.environ.get(name, "") for name in SECRET_FILES}
    if any(not value.strip() for value in values.values()):
        raise ValueError("All six release signing secrets must be configured")
    try:
        store = base64.b64decode("".join(values["ANDROID_SIGNING_KEYSTORE_BASE64"].split()), validate=True)
    except ValueError:
        raise ValueError("Android keystore secret is not valid Base64") from None
    if not store or len(store) > 32 * 1024:
        raise ValueError("Unexpected Android keystore size")
    if not values["RUNTIME_SIGNING_PRIVATE_KEY"].strip().startswith("-----BEGIN ENCRYPTED PRIVATE KEY-----"):
        raise ValueError("Runtime secret must be an encrypted PKCS#8 private key")
    folder = create_directory(output)
    for name, filename in SECRET_FILES.items():
        private_write(folder / filename, (values[name].strip() + "\n").encode())
    private_write(folder / "android-release.p12", store)
    print("Restored signing material outside the checkout.")


def install_public_key(directory):
    folder = outside_checkout(directory)
    public = command("openssl", "pkey", "-pubin", "-in", str(folder / "runtime-signing-public.pem"),
                     "-outform", "DER", capture=True)
    trust = ROOT / "androidApp/src/main/kotlin/ernest/airc/ReleaseTrust.kt"
    source, count = re.subn(r'PUBLIC_KEY_DER = "[A-Za-z0-9+/=]+"',
                            f'PUBLIC_KEY_DER = "{base64.b64encode(public).decode()}"', trust.read_text())
    if count != 1:
        raise ValueError("Expected exactly one Android trust root")
    shutil.copyfile(folder / "runtime-signing-public.pem", ROOT / "docs/release-public-key.pem")
    (ROOT / "docs/android-release-certificate.pem").write_text((folder / "android-signing-certificate.pem").read_text())
    shutil.copyfile(folder / "signing-info.json", ROOT / "docs/release-signing.json")
    trust.write_text(source)
    print("Installed public keys, certificate fingerprints and the Android trust root.")


def run(directory, arguments):
    folder = outside_checkout(directory)
    if arguments[:1] == ["--"]:
        arguments = arguments[1:]
    if not arguments:
        raise ValueError("A command is required after --")
    environment = os.environ.copy()
    for name, filename in SECRET_FILES.items():
        if not (folder / filename).is_file():
            raise ValueError(f"Missing signing file: {filename}")
    environment.update({
        "AIRC_ANDROID_KEYSTORE": str(folder / "android-release.p12"),
        "AIRC_ANDROID_STORE_PASSWORD": (folder / "android-store-password.txt").read_text().strip(),
        "AIRC_ANDROID_KEY_ALIAS": (folder / "android-key-alias.txt").read_text().strip(),
        "AIRC_ANDROID_KEY_PASSWORD": (folder / "android-key-password.txt").read_text().strip(),
        "AIRC_RUNTIME_SIGNING_KEY": str(folder / "runtime-signing-private.pem"),
        "AIRC_RUNTIME_SIGNING_KEY_PASSWORD": (folder / "runtime-key-password.txt").read_text().strip(),
    })
    return subprocess.call(arguments, env=environment)


def upload(directory, repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Use an explicit OWNER/REPOSITORY")
    if not shutil.which("gh"):
        raise ValueError("Install GitHub CLI and run gh auth login before uploading secrets")
    folder = outside_checkout(directory)
    for filename in SECRET_FILES.values():
        if not (folder / filename).is_file():
            raise ValueError(f"Missing signing file: {filename}")
    for name, filename in SECRET_FILES.items():
        with (folder / filename).open("rb") as stream:
            subprocess.run(["gh", "secret", "set", name, "--repo", repository], stdin=stream,
                           stdout=subprocess.DEVNULL, check=True)
        print(f"Configured GitHub secret: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    for name in ("generate", "restore"):
        commands.add_parser(name).add_argument("--output", required=True, type=Path)
    commands.add_parser("install-public-key").add_argument("--secrets-dir", required=True, type=Path)
    runner = commands.add_parser("run")
    runner.add_argument("--secrets-dir", required=True, type=Path)
    runner.add_argument("command", nargs=argparse.REMAINDER)
    uploader = commands.add_parser("upload")
    uploader.add_argument("--secrets-dir", required=True, type=Path)
    uploader.add_argument("--repository", required=True)
    args = parser.parse_args()
    try:
        if args.operation == "generate": generate(args.output)
        elif args.operation == "restore": restore(args.output)
        elif args.operation == "install-public-key": install_public_key(args.secrets_dir)
        elif args.operation == "run": return run(args.secrets_dir, args.command)
        elif args.operation == "upload": upload(args.secrets_dir, args.repository)
    except (ValueError, FileExistsError) as error:
        parser.exit(1, f"{error}\n")
    except subprocess.CalledProcessError:
        # Command arguments/output can contain credentials; do not echo them.
        parser.exit(1, "Signing command failed; secret values were not printed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
