"""Synthetic shell checks; no Android device or user data is accessed."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'android/instrumentation/run-iteration.sh'


class RecallSeedPreflightTests(unittest.TestCase):
    def invoke(self, package_result, package_exit=0):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            adb = root / 'platform-tools/adb'
            adb.parent.mkdir()
            adb.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$TEST_ADB_LOG"
case "$*" in
  'shell getprop ro.kernel.qemu') echo 1 ;;
  'shell pm list packages --user 0 org.continuity.recall.test')
    printf '%s\\n' "$TEST_PACKAGES"; exit "$TEST_PACKAGE_EXIT" ;;
  *) exit 77 ;;
esac
''')
            adb.chmod(0o700)
            env = {**os.environ, 'ANDROID_HOME': str(root), 'RECALL_SYNTHETIC_DEVICE': 'YES',
                   'TEST_ADB_LOG': str(root / 'calls'), 'TEST_PACKAGES': package_result,
                   'TEST_PACKAGE_EXIT': str(package_exit)}
            result = subprocess.run(['bash', str(SCRIPT)], env=env, capture_output=True, text=True)
            calls = (root / 'calls').read_text().splitlines()
            return result, calls

    def test_installed_package_is_refused_before_install(self):
        result, calls = self.invoke('package:org.continuity.recall.test')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('refusing synthetic upgrade test', result.stderr)
        self.assertFalse(any(c.startswith('install') for c in calls))

    def test_package_query_failure_aborts_before_install(self):
        result, calls = self.invoke('', package_exit=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c.startswith('install') for c in calls))

    def test_similarly_named_package_does_not_trigger_false_match(self):
        result, calls = self.invoke('package:org.continuity.recall.test.other')
        self.assertNotEqual(result.returncode, 0)  # fake adb stops at the first install
        self.assertTrue(any(c.startswith('install') for c in calls))


if __name__ == '__main__':
    unittest.main()
