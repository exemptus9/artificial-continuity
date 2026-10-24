#!/usr/bin/env python3
"""Identify the exact build input, and reject source mutation while compiling."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
NS = '{http://schemas.android.com/apk/res/android}'


def inputs():
    paths = [ROOT / 'AndroidManifest.xml', ROOT / 'build.sh', ROOT / 'build-metadata.py']
    for folder in ('src', 'res'):
        paths.extend(p for p in (ROOT / folder).rglob('*') if p.is_file())
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(paths)}


def source_hash(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def main():
    files = inputs()
    if sys.argv[1] == 'prepare':
        manifest = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
        version = manifest.get(NS + 'versionName')
        package = manifest.get('package')
        if not re.fullmatch(r'[A-Za-z0-9._-]+', version or ''):
            raise SystemExit('Unsafe/missing Android versionName')
        kind = os.environ.get('RECALL_SIGNING_KIND', 'unverified-local')
        if kind == 'ephemeral-test' and 'preview' not in package.split('.'):
            # Versioned preview segment (e.g. preview09) is also deliberately isolated.
            if not any(part.startswith('preview') for part in package.split('.')):
                raise SystemExit('Ephemeral signing is permitted only for an isolated preview package')
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        dirty = bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=ROOT, text=True).strip())
        evidence = {
            'format': 'TotalRecallBuildEvidence/1', 'version': version,
            'version_code': int(manifest.get(NS + 'versionCode')), 'package': package,
            'source_commit': commit, 'working_tree_dirty': dirty,
            'source_sha256': source_hash(files), 'input_sha256': files,
            'signing_kind': kind, 'android_platform': 36, 'build_tools': '36.0.0',
            'verification': 'APK compilation and signature checks only; device acceptance is separate',
        }
        (ROOT / 'build/build-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        label = commit + ('-dirty' if dirty else '')
        (ROOT / 'build/generated/org/continuity/recall/BuildInfo.java').write_text(
            'package org.continuity.recall; public final class BuildInfo {\n'
            f' public static final String VERSION={json.dumps(version)};\n'
            f' public static final String COMMIT={json.dumps(label)};\n'
            f' public static final String SOURCE_SHA256={json.dumps(evidence["source_sha256"])};\n'
            '}\n')
    elif sys.argv[1] == 'verify':
        evidence = json.loads((ROOT / 'build/build-evidence.json').read_text())
        if evidence['input_sha256'] != files:
            raise SystemExit('Source files changed during compilation. Build discarded; rebuild from a stable tree.')
        apk = ROOT / 'build' / f'TotalRecall-{evidence["version"]}.apk'
        evidence['apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        signature = (ROOT / 'build/signature.txt').read_text()
        match = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)', signature)
        if not match:
            raise SystemExit('Missing verified signing certificate digest')
        evidence['signer_certificate_sha256'] = match.group(1).lower()
        (ROOT / 'build/build-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    else:
        raise SystemExit('Expected prepare or verify')


if __name__ == '__main__':
    main()
