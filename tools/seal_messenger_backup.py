#!/usr/bin/env python3
"""Optional authenticated encryption of a portable backup; no working-store changes.

Requires the optional `cryptography` package. Keys are read from owner-private
files, never argv/environment/password prompts. Recovery keys must be delivered
separately from encrypted packets. All outputs are new, owner-private files.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import stat

try:
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # Ordinary archive tooling and stdlib CI remain usable.
    AESGCM = None
    InvalidTag = Exception

FORMAT = 'ContinuitySealedMessengerBackup/1'
KEY_FORMAT = 'ContinuityMessengerBackupRecoveryKey/1'
ALGORITHM = 'AES-256-GCM'
VERSION = 1
MAX_PLAINTEXT = 64 * 1024 * 1024
MAX_ENVELOPE = ((MAX_PLAINTEXT + 16 + 2) // 3) * 4 + 4096
MAX_KEY_FILE = 4096
META_FIELDS = ('algorithm', 'format', 'key_id', 'plaintext_sha256', 'plaintext_size', 'version')
ENVELOPE_FIELDS = set(META_FIELDS) | {'nonce_b64', 'ciphertext_b64'}
KEY_FIELDS = {'format', 'algorithm', 'key_id', 'key_b64'}
FAILURE = 'Backup operation failed. Check the selected files, key, permissions, checksum and new output path.'


class BackupError(ValueError):
    pass


def _fail():
    raise BackupError(FAILURE)


def _require_crypto():
    if AESGCM is None:
        raise BackupError('Optional encryption requires the cryptography package. No operation was performed.')


def _path(value):
    path = Path(value).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        _fail()
    return path


def _read(path, maximum, private_key=False):
    path = _path(path)
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(descriptor, 'rb') as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > maximum:
            _fail()
        if private_key and info.st_mode & 0o077:
            _fail()
        raw = source.read(maximum + 1)
        final = os.fstat(source.fileno())
    if len(raw) > maximum or len(raw) != info.st_size or (final.st_size, final.st_mtime_ns) != (info.st_size, info.st_mtime_ns):
        _fail()
    return raw


def _new_file(path, raw):
    path = _path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    _path(path.parent)
    if path.exists():
        _fail()
    temporary = path.with_name('.sealed-tmp-' + secrets.token_hex(16))
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(raw); output.flush(); os.fsync(output.fileno())
        # link is atomic and refuses every existing destination, including a
        # dangling symlink; replacement and partially-written outputs are absent.
        os.link(temporary, path)
        if hasattr(os, 'O_DIRECTORY'):
            fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
    finally:
        temporary.unlink(missing_ok=True)


def _canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail()
        result[key] = value
    return result


def _parse(raw):
    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=_pairs,
                           parse_constant=lambda _: _fail())
        _canonical(value)
        return value
    except (ValueError, UnicodeError, TypeError, RecursionError):
        _fail()


def _b64(value, size=None):
    if not isinstance(value, str):
        _fail()
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, UnicodeError):
        _fail()
    if base64.b64encode(decoded).decode('ascii') != value or size is not None and len(decoded) != size:
        _fail()
    return decoded


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _hash(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value):
        _fail()
    return value


def _key(path):
    value = _parse(_read(path, MAX_KEY_FILE, private_key=True))
    if not isinstance(value, dict) or set(value) != KEY_FIELDS or value['format'] != KEY_FORMAT or value['algorithm'] != ALGORITHM:
        _fail()
    if not isinstance(value['key_id'], str) or not re.fullmatch('[0-9a-f]{32}', value['key_id']):
        _fail()
    return value, _b64(value['key_b64'], 32)


def generate_key(path):
    _require_crypto()
    key = secrets.token_bytes(32)
    value = {'format': KEY_FORMAT, 'algorithm': ALGORITHM, 'key_id': secrets.token_hex(16),
             'key_b64': base64.b64encode(key).decode('ascii')}
    _new_file(path, _canonical(value) + b'\n')
    return value, key


def seal(source, destination, key_path, expected_sha256, create_key=False):
    _require_crypto()
    if len({_path(source), _path(destination), _path(key_path)}) != 3:
        _fail()
    raw = _read(source, MAX_PLAINTEXT)
    digest = _sha(raw)
    if not hmac.compare_digest(digest, _hash(expected_sha256)):
        _fail()
    # Preflight destination before creating a recovery key. The final write
    # independently rejects a concurrent destination creation.
    target = _path(destination)
    if target.exists():
        _fail()
    key_meta, key = generate_key(key_path) if create_key else _key(key_path)
    metadata = {'format': FORMAT, 'version': VERSION, 'algorithm': ALGORITHM,
                'key_id': key_meta['key_id'], 'plaintext_size': len(raw), 'plaintext_sha256': digest}
    nonce = secrets.token_bytes(12)
    encrypted = AESGCM(key).encrypt(nonce, raw, _canonical(metadata))
    envelope = {**metadata, 'nonce_b64': base64.b64encode(nonce).decode('ascii'),
                'ciphertext_b64': base64.b64encode(encrypted).decode('ascii')}
    _new_file(target, _canonical(envelope) + b'\n')
    return {'format': FORMAT, 'encrypted': True, 'plaintext_bytes': len(raw),
            'plaintext_sha256': digest, 'sealed_sha256': _sha(_read(target, MAX_ENVELOPE)),
            'recovery_key_printed': False, 'working_store_encryption_changed': False}


def open_backup(source, destination, key_path):
    _require_crypto()
    envelope = _parse(_read(source, MAX_ENVELOPE))
    if not isinstance(envelope, dict) or set(envelope) != ENVELOPE_FIELDS:
        _fail()
    if envelope['format'] != FORMAT or envelope['algorithm'] != ALGORITHM or type(envelope['version']) is not int or envelope['version'] != VERSION:
        _fail()
    size = envelope['plaintext_size']
    if type(size) is not int or not 0 <= size <= MAX_PLAINTEXT:
        _fail()
    expected = _hash(envelope['plaintext_sha256'])
    key_meta, key = _key(key_path)
    if not isinstance(envelope['key_id'], str) or not re.fullmatch('[0-9a-f]{32}', envelope['key_id']) or not hmac.compare_digest(key_meta['key_id'], envelope['key_id']):
        _fail()
    nonce = _b64(envelope['nonce_b64'], 12); ciphertext = _b64(envelope['ciphertext_b64'])
    if len(ciphertext) != size + 16:
        _fail()
    metadata = {k: envelope[k] for k in META_FIELDS}
    try:
        raw = AESGCM(key).decrypt(nonce, ciphertext, _canonical(metadata))
    except (InvalidTag, ValueError):
        _fail()
    if len(raw) != size or not hmac.compare_digest(_sha(raw), expected):
        _fail()
    _new_file(destination, raw)
    return {'decrypted': True, 'plaintext_bytes': len(raw), 'plaintext_sha256': expected,
            'authentication_verified': True, 'new_destination_only': True}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    enc = sub.add_parser('seal'); enc.add_argument('--input', required=True); enc.add_argument('--output', required=True)
    keys = enc.add_mutually_exclusive_group(required=True)
    keys.add_argument('--key-file'); keys.add_argument('--new-key-file')
    enc.add_argument('--expect-sha256', required=True)
    dec = sub.add_parser('open'); dec.add_argument('--input', required=True); dec.add_argument('--output', required=True); dec.add_argument('--key-file', required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'seal':
            result = seal(args.input, args.output, args.new_key_file or args.key_file, args.expect_sha256, bool(args.new_key_file))
        else:
            result = open_backup(args.input, args.output, args.key_file)
        print(json.dumps(result, sort_keys=True))
    except BackupError as exc:
        parser.exit(2, str(exc) + '\n')
    except (OSError, ValueError, UnicodeError, TypeError, OverflowError, RecursionError):
        parser.exit(2, FAILURE + '\n')


if __name__ == '__main__':
    main()
