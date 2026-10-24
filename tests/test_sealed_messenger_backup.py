"""Synthetic bytes only; optional AEAD dependency is not needed by stdlib CI."""
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import seal_messenger_backup as S


@unittest.skipIf(S.AESGCM is None, 'Optional cryptography package absent; encryption feature unavailable.')
class SealedMessengerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.source = self.root / 'synthetic.zip'; self.raw = b'PK\x03\x04Synthetic private archive\x00\r\n\xf0\x9f\x98\x80'
        self.source.write_bytes(self.raw); self.sha = hashlib.sha256(self.raw).hexdigest()
        self.sealed = self.root / 'archive.sealed.json'; self.key = self.root / 'separate-recovery' / 'key.json'

    def tearDown(self):
        self.tmp.cleanup()

    def seal(self, **kw):
        return S.seal(self.source, self.sealed, self.key, self.sha, create_key=True, **kw)

    def test_roundtrip_exact_hash_and_private_key_permissions(self):
        result = self.seal(); out = self.root / 'restored.zip'
        opened = S.open_backup(self.sealed, out, self.key)
        self.assertTrue(result['encrypted']); self.assertTrue(opened['authentication_verified'])
        self.assertEqual(out.read_bytes(), self.raw); self.assertEqual(opened['plaintext_sha256'], self.sha)
        self.assertEqual(stat.S_IMODE(self.key.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(out.stat().st_mode), 0o600)
        self.assertNotIn('key_b64', self.sealed.read_text())
        self.assertNotIn(base64.b64encode(self.raw).decode(), self.sealed.read_text())

    def test_every_encryption_uses_fresh_nonce_and_new_keys(self):
        self.seal(); first = json.loads(self.sealed.read_text())
        second = self.root / 'second.sealed.json'
        S.seal(self.source, second, self.key, self.sha)
        other = json.loads(second.read_text())
        self.assertNotEqual(first['nonce_b64'], other['nonce_b64']); self.assertNotEqual(first['ciphertext_b64'], other['ciphertext_b64'])
        key2 = self.root / 'other.key.json'; S.generate_key(key2)
        self.assertNotEqual(json.loads(self.key.read_text())['key_b64'], json.loads(key2.read_text())['key_b64'])

    def test_wrong_key_rejected_without_plaintext_output(self):
        self.seal(); wrong = self.root / 'wrong.json'; S.generate_key(wrong)
        out = self.root / 'forbidden.zip'
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, out, wrong)
        self.assertFalse(out.exists())
        # Match the public key ID but replace secret bytes: authenticate, do not
        # merely rely on the ID mismatch to reject an incorrect recovery key.
        changed = json.loads(self.key.read_text()); changed['key_b64'] = base64.b64encode(b'\x07' * 32).decode()
        wrong.write_text(json.dumps(changed)); wrong.chmod(0o600)
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, out, wrong)
        self.assertFalse(out.exists())

    def test_ciphertext_nonce_and_every_authenticated_metadata_tamper_rejected(self):
        self.seal(); original = json.loads(self.sealed.read_text())
        mutations = {'format': 'Other/1', 'algorithm': 'AES-128-GCM', 'version': 2,
                     'key_id': 'f' * 32, 'plaintext_sha256': '0' * 64, 'plaintext_size': len(self.raw) - 1,
                     'nonce_b64': base64.b64encode(b'\0' * 12).decode()}
        data = bytearray(base64.b64decode(original['ciphertext_b64'])); data[0] ^= 1
        mutations['ciphertext_b64'] = base64.b64encode(data).decode()
        for field, value in mutations.items():
            with self.subTest(field=field):
                changed = {**original, field: value}; self.sealed.write_text(json.dumps(changed))
                out = self.root / 'not-created.zip'
                with self.assertRaises(S.BackupError): S.open_backup(self.sealed, out, self.key)
                self.assertFalse(out.exists())

    def test_bad_source_checksum_fails_before_creating_key_or_sealed_file(self):
        with self.assertRaises(S.BackupError): S.seal(self.source, self.sealed, self.key, '0' * 64, create_key=True)
        self.assertFalse(self.key.exists()); self.assertFalse(self.sealed.exists())

    def test_existing_destinations_and_symlinks_never_overwritten(self):
        self.seal(); original_key = self.key.read_bytes()
        with self.assertRaises(S.BackupError): self.seal()
        self.assertEqual(original_key, self.key.read_bytes())
        out = self.root / 'existing'; out.write_bytes(b'keep')
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, out, self.key)
        self.assertEqual(out.read_bytes(), b'keep')
        out = self.root / 'symlink'; out.symlink_to(self.root / 'missing')
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, out, self.key)
        self.assertFalse((self.root / 'missing').exists())
        source_link = self.root / 'source-link'; source_link.symlink_to(self.source)
        with self.assertRaises(S.BackupError): S.seal(source_link, self.root / 'new', self.key, self.sha)
        key_link = self.root / 'key-link'; key_link.symlink_to(self.key)
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, self.root / 'new', key_link)

    def test_key_permissions_and_duplicate_json_fields_are_rejected(self):
        self.seal(); self.key.chmod(0o644)
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, self.root / 'new', self.key)
        self.key.chmod(0o600)
        self.sealed.write_text('{"format":"one","format":"two"}')
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, self.root / 'new', self.key)

    def test_size_limit_and_invalid_base64_rejected(self):
        with patch.object(S, 'MAX_PLAINTEXT', len(self.raw) - 1):
            with self.assertRaises(S.BackupError): self.seal()
        self.seal(); value = json.loads(self.sealed.read_text()); value['nonce_b64'] = 'not base64'
        self.sealed.write_text(json.dumps(value))
        with self.assertRaises(S.BackupError): S.open_backup(self.sealed, self.root / 'new', self.key)
        # Canonical padding bits are required even when a lenient decoder would
        # produce the same raw key bytes from an alternate textual spelling.
        metadata = json.loads(self.key.read_text()); alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
        encoded = metadata['key_b64']; index = alphabet.index(encoded[-2])
        metadata['key_b64'] = encoded[:-2] + alphabet[index | 1] + '='
        self.key.write_text(json.dumps(metadata))
        with self.assertRaises(S.BackupError): S._key(self.key)

    def test_cli_never_prints_recovery_key_and_errors_are_generic(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            S.main(['seal', '--input', str(self.source), '--output', str(self.sealed), '--new-key-file', str(self.key), '--expect-sha256', self.sha])
        key_b64 = json.loads(self.key.read_text())['key_b64']
        self.assertNotIn(key_b64, stdout.getvalue())
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit):
            S.main(['open', '--input', str(self.root / 'missing-sensitive-name'), '--output', str(self.root / 'new'), '--key-file', str(self.key)])
        self.assertNotIn('missing-sensitive-name', stderr.getvalue()); self.assertNotIn(key_b64, stderr.getvalue())

    def test_optional_dependency_absence_is_explicit_and_nonmutating(self):
        with patch.object(S, 'AESGCM', None):
            with self.assertRaisesRegex(S.BackupError, 'Optional encryption'):
                self.seal()
        self.assertFalse(self.key.exists()); self.assertFalse(self.sealed.exists())


if __name__ == '__main__':
    unittest.main()
