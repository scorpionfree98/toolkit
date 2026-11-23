"""Offline packaging checks. No device certificate, network or business endpoint is used."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/prepare-runtime.py'


class RuntimePolicyTest(unittest.TestCase):
    def execute(self, script=SCRIPT, source=None):
        env = dict(os.environ)
        env.pop('CAPTURE_RUNTIME_ARCHIVE', None)
        if source is not None:
            env['CAPTURE_RUNTIME_ARCHIVE'] = str(source)
        return subprocess.run([sys.executable, str(script)], env=env, capture_output=True, text=True)

    def test_committed_runtime_is_pinned_and_contains_no_private_keys(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Validated embedded runtime', result.stdout)

    def test_corrupt_archive_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix='capture-policy-') as directory:
            invalid = Path(directory) / 'invalid.tar.gz'
            invalid.write_bytes(b'not the approved runtime')
            result = self.execute(source=invalid)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('SHA256 mismatch', result.stderr)

    def test_missing_archive_fails_without_download(self):
        with tempfile.TemporaryDirectory(prefix='capture-policy-') as directory:
            script = Path(directory) / 'scripts/prepare-runtime.py'
            script.parent.mkdir()
            shutil.copyfile(SCRIPT, script)
            result = self.execute(script=script)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Missing verified runtime', result.stderr)

    def test_gradle_expanded_or_missing_runtime_is_rejected_in_apk(self):
        with tempfile.TemporaryDirectory(prefix='capture-policy-') as directory:
            apk = Path(directory) / 'missing-runtime.apk'
            with zipfile.ZipFile(apk, 'w') as package:
                package.writestr('assets/embedded/runtime.tar', b'not pinned gzip bytes')
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/verify-apk.py'), str(apk)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)

    def test_asset_verifier_requires_existing_apk(self):
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/verify-apk.py'), '/missing/capture-test.apk'], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)


if __name__ == '__main__':
    unittest.main()
