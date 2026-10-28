import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import release_smoke


class ReleaseSmokeReceiptTests(unittest.TestCase):
    def test_optional_browser_check_is_not_reported_as_passed_when_not_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            receipt = release_smoke.run(Path(tmp) / 'run')
            self.assertIsNone(receipt['repeat_import_idempotent'])
            self.assertIsNone(receipt['browser_import'])

    def test_browser_import_retry_is_verified(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / 'browser.json'
            fixture.write_text(json.dumps({'format': 'ContinuityBackup/2', 'state': {
                'version': '0.11.0', 'sources': [{'id': 'sample', 'text': 'Synthetic source'}]
            }}))
            receipt = release_smoke.run(Path(tmp) / 'run', fixture)
            self.assertTrue(receipt['repeat_import_idempotent'])
            self.assertIsNotNone(receipt['browser_import'])

    def test_empty_browser_export_does_not_claim_retry_verification(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / 'empty.json'
            fixture.write_text(json.dumps({'format': 'ContinuityBackup/2', 'state': {
                'version': '0.11.0', 'sources': []
            }}))
            receipt = release_smoke.run(Path(tmp) / 'run', fixture)
            self.assertIsNone(receipt['repeat_import_idempotent'])

    def test_optimized_python_cannot_skip_failed_retrieval(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / 'run'
            program = ("import capture_context as G, release_smoke as R; "
                       "G.Store.search = lambda *a, **k: []; "
                       "R.run(" + repr(str(destination)) + ")")
            result = subprocess.run([sys.executable, '-O', '-c', program],
                                    env={**os.environ, 'PYTHONPATH': str(Path(release_smoke.__file__).parent)},
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0, result.stderr)
            self.assertFalse((destination / 'RECEIPT.json').exists())


if __name__ == '__main__':
    unittest.main()
