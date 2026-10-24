# Preview signing, installation and recovery

Temporal Voice 0.9 is experimental. The package `org.continuity.recall.temporal.preview09` installs separately from the earlier 0.8 preview and Total Recall. Its vault starts empty. Do not uninstall an earlier app to try this build. Use a protected export and a verified restore when moving records between packages.

## Signing identities

The automated preview workflow generates a disposable signing key for each run. A successful build proves compilation and signature validity; it does not establish an owner-controlled release identity or physical-device readiness. Different CI runs normally have different signing certificates and cannot update one another in place.

`android/build.sh` accepts an existing private keystore, `RECALL_KEY_ALIAS` (default `recall-test`), `RECALL_KEY_PASSWORD`, and `RECALL_SIGNING_KIND` (`ephemeral-test`, `owner-managed`, or `unverified-local`). The signing label is a disclosure, not proof of key ownership. CI does not consume any owner secrets. Keep private keys and their password backups outside this repository, and retain them in two protected owner-controlled locations. Losing a key prevents normal in-place updates. Never send it inside a source or APK download package.

For a stable owner build, create a key on the owner's trusted computer, using keytool's interactive password prompts:

```bash
keytool -genkeypair -alias temporal-owner -keystore /private/temporal-owner.p12 \
  -storetype PKCS12 -keyalg RSA -keysize 3072 -validity 10000
```

After storing the password securely, build with a JDK 17 and SDK platform/build-tools 36:

```bash
export RECALL_KEYSTORE=/private/temporal-owner.p12
export RECALL_KEY_ALIAS=temporal-owner
export RECALL_SIGNING_KIND=owner-managed
read -rs -p 'Signing password: ' RECALL_KEY_PASSWORD
export RECALL_KEY_PASSWORD
bash android/build.sh
unset RECALL_KEY_PASSWORD
```

The build records version, source commit, dirty-tree status, every build input hash, APK SHA-256, and signing-certificate SHA-256 in `android/build/build-evidence.json`. It cleans compiler output before each build and rejects input changes during compilation. A dirty source tree is explicitly labeled. This is verifiable input evidence, not a claim of byte-for-byte reproducibility across machines or SDK revisions.

## Before installing or updating

1. Export a protected backup from the existing installation and verify restoration in a separate test installation. Keep the backup password safely; the Android Keystore key does not migrate between app packages or survive uninstall.
2. Check the download hash from a trusted release record with `sha256sum --check SHA256SUMS` in the download folder.
3. Verify the APK signature and identity:

   ```bash
   python3 android/verify-update.py android/build/TotalRecall-0.9.0-preview.apk
   ```

4. For an in-place upgrade, compare with a copy of the currently installed APK:

   ```bash
   python3 android/verify-update.py candidate.apk --installed-apk previous.apk
   ```

   It refuses different package IDs, different certificates, and non-increasing version codes. The script never installs or deletes anything. `previous.apk` must actually correspond to the installed application; a similarly named download is not enough.

5. Install the isolated preview through Android's package installer only after reviewing its name and permissions. Start with synthetic notes. The optional microphone permission is requested when you choose speech or recording; no capture should begin merely from installation, reboot, or a Quick Settings tile.

## Recovery and limits

If Android says an update conflicts with the existing package, stop. Do not use uninstall as an update workaround while there are unexported records. Keep using the old app until a compatible signer or verified export/restore path is available.

Keep the prior APK, certificate fingerprint, source commit, and protected backup. A downgrade may be rejected by Android and may not understand newer data, so rollback means a reviewed restoration in a compatible separate test installation, not blindly replacing application data. This release preserves `TotalRecallStore/1`; archived originals are not rewritten by the new memory workbench.

Physical-device acceptance is still required for speech availability, Bluetooth/headset routing, screen lock, background microphone controls, process death, large text/TalkBack, battery use, and owner-specific backups. Neither signing nor emulator testing substitutes for those checks.

## What the automated evidence proves

The preview workflow runs the host archive, memory, lifecycle and transfer tests before compilation. A second job installs that exact uploaded APK and its matching signed test APK on a disposable Android 36 emulator. The synthetic runners exercise archive persistence/restore, app-switch collection and pause boundaries, typed note capture and source-backed recall, linked corrections, the Continuity Desk, privacy controls, large text/landscape, and a separate force-stop/restart check. Each test job fails on failed assertions.

The test runner refuses non-emulator serials and requires `RECALL_SYNTHETIC_DEVICE=YES`; it deliberately wipes the synthetic vault. Never run the Java instrumentation directly against a device containing personal data. For a disposable emulator that is already fully booted:

```bash
export ANDROID_SERIAL=emulator-5554
export RECALL_SYNTHETIC_DEVICE=YES
bash android/instrumentation/run-device-tests.sh
```

The device artifact includes pass/fail receipts and native Android Activity renderings from synthetic data. The renderings use the actual Android views while keeping the app's secure-window flag; they are not screenshots of a physical phone. Real speech recognition, acoustic interruption, headset routing, battery consumption, and Samsung screen-lock behavior remain separate physical tests. Inspect the actual workflow result and receipt; the existence of a workflow file is not evidence that it passed.
