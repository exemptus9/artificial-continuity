# MAX05 integrated release candidate

This integrates the browser/archive branch at 8ae43346ec197a726f7bad3ff137b6ec961cf9c5
and controlled context at 2d00c92b448978019eb8dbfca50b3a4c9d301b6f.
The hosted rollback reference is 724ab357b714e45d1073b1989eff370bd31b66c2.
Checking out this source does not migrate an existing browser workspace.

## Run and verify

Python 3.10+ with SQLite FTS5 runs the standard-library core. Node and Playwright
are test dependencies. Open the local page in a fresh profile.

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory site
# Open http://127.0.0.1:8765
python3 tools/release_smoke.py --destination /tmp/continuity-demo-new
python3 -m unittest discover -s tests -p 'test_*.py' -v
node --test tests/*.test.cjs
npm install --no-save --no-package-lock --ignore-scripts playwright@1.56.1
npx playwright install chromium
node tests/release-browser.cjs
```

The release check captures synthetic text with CRLF, single and double LF,
indentation, a tab, Unicode punctuation, a title and terminal three dots. It
reloads, downloads the exact original and backup, restores in a clean browser
context, imports that real export into the controlled core, repeats the import,
appends a correction, rejects a conflicting UUID, and verifies a clean core
restore. Evidence is written to `test-artifacts/release-receipt.json`.

The v0.18 Source conversation timeout reproduced with the original assertion.
The existing v0.19 hydration repair is retained, with an additional delayed
draft-read regression. Agent grants now expire after one hour by default;
owner recovery remains available. The obsolete workflow that downloaded and
committed source during CI is removed. Normal CI tests the repository checkout.

## Boundaries

Browser IndexedDB, Library journal JSON, private object files and encrypted
exports are different formats. The browser adapter preserves the whole raw
export and copies reviewed source text; inherited consent and decisions remain
inert. Each copy is atomic; the full import is resumable, not one transaction.

Both browser and core working stores remain plaintext. Use non-sensitive
fixtures until an owner-controlled encrypted device/root is verified. Export
encryption does not encrypt live storage. Keep private roots outside Git.
Lexidaemon must receive a scoped read tool, never an owner token or store mount.
The generic client is tested; actual OpenClaw registration is not installed.
No sandbox settings are changed.

## Actual device trial

Physical Android and the owner's laptop: **NOT RUN**.

1. Export the existing workspace. Use a separate profile/origin for the trial.
2. Create the exact file on the host:
   `python3 -c "import sys;sys.path.insert(0,'tools');from release_smoke import TEXT;open('fixture.txt','wb').write(TEXT.encode())"`
3. Use Add conversation to import that file as Source conversation. Reload,
   download the original and export a complete backup.
4. Restore the backup into a clean profile. Compare the downloaded restored
   original byte-for-byte with fixture.txt. Keep both exports.
5. Run `python3 tools/device_check.py device-backup.json --device-label 'actual device and browser' --receipt device-receipt.json`.

A passing receipt names exact checksums/source IDs. Device identity remains an
operator observation. It does not verify mic, Side-button or native interception.
Automatic sync and background capture are absent.

## Recovery and rollback

CAPTURE_CONTEXT.md contains backup, outbox and recovery commands. Pause producers
for backups: journal and outbox snapshots are not globally atomic. Restore into
a new directory and compare hashes before adopting it. Retain failed envelopes
and orphan objects until deliberately resolved.

Before changing an existing origin, export workspace and draft-recovery files.
Do not clear storage to update. Reverting code does not revert data; never point
an old client at newer data without a verified independent backup.

This is a review candidate, not a claim of physical-device validation,
encrypted live storage, production deployment or automatic cross-chat memory.
