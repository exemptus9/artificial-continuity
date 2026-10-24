#!/usr/bin/env python3
"""Compare signed APKs before an update. Never installs, removes, or clears data."""
import argparse
import os
from pathlib import Path
import re
import subprocess


def describe(apk, sdk):
    tools = Path(sdk) / 'build-tools/36.0.0'
    signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signature)
    if len(certs) != 1:
        raise ValueError('Expected exactly one verified APK signer; lineage/multiple signers need manual review')
    badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match:
        raise ValueError('APK package information unavailable')
    return match[1], int(match[2]), match[3], certs[0].lower()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--installed-apk', type=Path, help='An APK exported/pulled from the currently installed app')
    args = parser.parse_args()
    sdk = os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        parser.error('Set ANDROID_SDK_ROOT to the SDK containing build-tools 36.0.0')
    try:
        candidate = describe(args.candidate, sdk)
        print(f'Package: {candidate[0]}\nVersion: {candidate[2]} ({candidate[1]})\nCertificate SHA-256: {candidate[3]}')
        if not args.installed_apk:
            print('Signature verified. Upgrade compatibility is UNVERIFIED without the installed APK.')
            return
        existing = describe(args.installed_apk, sdk)
        if candidate[0] != existing[0]:
            raise ValueError('Different package: separate installation, not an in-place update')
        if candidate[3] != existing[3]:
            raise ValueError('Different signing certificate: DO NOT uninstall to force an update; first export and verify a protected backup')
        if candidate[1] <= existing[1]:
            raise ValueError('Candidate versionCode must be greater than the installed version for this update check')
        print('Package, signing certificate, and version are compatible. Backup/restore and device acceptance remain separate gates.')
    except (ValueError, subprocess.CalledProcessError, OSError) as exc:
        parser.exit(1, f'Update check failed: {exc}\n')


if __name__ == '__main__':
    main()
