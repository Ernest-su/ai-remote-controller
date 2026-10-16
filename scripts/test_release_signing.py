import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


signing = module('release_signing', 'release_signing.py')
release = module('build_release', 'build-release.py')


class SigningTests(unittest.TestCase):
    def test_output_must_be_outside_checkout_and_cannot_replace_existing_directory(self):
        with self.assertRaises(ValueError):
            signing.create_directory(signing.ROOT / 'build/private-keys')
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileExistsError):
                signing.create_directory(directory)

    def test_missing_or_malformed_secrets_create_no_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'restored'
            with patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
                signing.restore(output)
            self.assertFalse(output.exists())
            values = {name: 'fixture' for name in signing.SECRET_FILES}
            with patch.dict(os.environ, values, clear=True), self.assertRaises(ValueError):
                signing.restore(output)
            self.assertFalse(output.exists())
            values['ANDROID_SIGNING_KEYSTORE_BASE64'] = base64.b64encode(b'fixture').decode()
            values['RUNTIME_SIGNING_PRIVATE_KEY'] = '-----BEGIN PRIVATE KEY-----'
            with patch.dict(os.environ, values, clear=True), self.assertRaises(ValueError):
                signing.restore(output)
            self.assertFalse(output.exists())

    def test_generated_material_restores_without_logging_secret_values(self):
        with tempfile.TemporaryDirectory() as directory:
            source, restored = Path(directory) / 'source', Path(directory) / 'restored'
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                signing.generate(source)
                values = {name: (source / filename).read_text().strip() for name, filename in signing.SECRET_FILES.items()}
                with patch.dict(os.environ, values, clear=True):
                    signing.restore(restored)
            self.assertTrue((source / 'android-release.p12').read_bytes() == (restored / 'android-release.p12').read_bytes())
            for value in values.values():
                self.assertFalse(value in output.getvalue())
            for folder in (source, restored):
                self.assertEqual(0o700, stat.S_IMODE(folder.stat().st_mode))
                for path in folder.iterdir():
                    self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            with patch.object(signing.subprocess, 'call', return_value=0) as execute:
                self.assertEqual(0, signing.run(restored, ['--', 'fixture-command']))
                self.assertEqual(['fixture-command'], execute.call_args.args[0])
                environment = execute.call_args.kwargs['env']
                self.assertTrue(environment['AIRC_ANDROID_STORE_PASSWORD'] == values['ANDROID_SIGNING_STORE_PASSWORD'])
                self.assertTrue(environment['AIRC_RUNTIME_SIGNING_KEY_PASSWORD'] == values['RUNTIME_SIGNING_KEY_PASSWORD'])
                self.assertEqual(str(restored / 'android-release.p12'), environment['AIRC_ANDROID_KEYSTORE'])

    def test_upload_uses_stdin_instead_of_secret_command_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for filename in signing.SECRET_FILES.values():
                (folder / filename).write_text('fixture-secret-content')
            with patch.object(signing.shutil, 'which', return_value='/usr/bin/gh'), patch.object(signing.subprocess, 'run') as execute, contextlib.redirect_stdout(io.StringIO()):
                signing.upload(folder, 'owner/repo')
            self.assertEqual(6, execute.call_count)
            for call in execute.call_args_list:
                self.assertNotIn('fixture-secret-content', ' '.join(call.args[0]))
                self.assertIn('stdin', call.kwargs)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'runtime').mkdir()
        (self.root / 'androidApp').mkdir()
        (self.root / 'docs').mkdir()
        (self.root / 'runtime/Cargo.toml').write_text('[package]\nversion = "1.2.3"\n')
        (self.root / 'androidApp/build.gradle.kts').write_text('versionName = "1.2.3"\nversionCode = 7\n')
        self.root_patch = patch.object(release, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def test_release_versions_and_tag_are_bound(self):
        self.assertEqual(7, release.validate_version('v1.2.3'))
        for tag in ('v1.2.4', 'master', 'v1.2.3; echo fixture', '../v1.2.3'):
            with self.assertRaises(ValueError): release.validate_version(tag)
        (self.root / 'androidApp/build.gradle.kts').write_text('versionName = "1.2.4"\nversionCode = 7\n')
        with self.assertRaises(ValueError): release.validate_version('v1.2.3')

    def test_apk_certificate_and_each_runtime_are_verified(self):
        import hashlib
        directory = self.root / 'release'
        directory.mkdir()
        assets = []
        apk = self.root / 'fixture.apk'
        (self.root / 'ascrcpy').mkdir()
        with zipfile.ZipFile(apk, 'w') as archive:
            for source, name in (('LICENSE', 'LICENSE'), ('THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md'),
                                 ('ascrcpy/LICENSE', 'LICENSE-Apache-2.0.txt')):
                (self.root / source).write_bytes(b'fixture license')
                archive.writestr('assets/licenses/' + name, b'fixture license')
            for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
                name = 'airc-runtime-' + abi
                data = abi.encode()
                assets.append(dict(abi=abi, file=name, size=len(data), sha256=hashlib.sha256(data).hexdigest()))
                archive.writestr('assets/runtime/' + name, data)
                (directory / name).write_bytes(data)
        (directory / 'manifest.json').write_text(json.dumps(dict(assets=assets)))
        fingerprint = 'a' * 64
        (self.root / 'docs/release-signing.json').write_text(json.dumps(dict(androidCertificateSha256=fingerprint)))
        with patch.object(release.subprocess, 'check_output', return_value='Signer #1 certificate SHA-256 digest: ' + fingerprint), contextlib.redirect_stdout(io.StringIO()):
            release.verify_apk(apk, directory, self.root)
            (directory / assets[0]['file']).write_bytes(b'tampered')
            with self.assertRaises(ValueError): release.verify_apk(apk, directory, self.root)
        with patch.object(release.subprocess, 'check_output', return_value='Signer #1 certificate SHA-256 digest: ' + 'b' * 64):
            with self.assertRaises(ValueError): release.verify_apk(apk, directory, self.root)


if __name__ == '__main__':
    unittest.main()
