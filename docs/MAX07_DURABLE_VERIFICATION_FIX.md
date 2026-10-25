# Total Recall 0.7.1 — saved-text verification fix

Prepared October 25, 2026 (America/New_York). Target: the 0.7.0 test APK from
commit `19339f861594c12933b07bd3d886e13563620956`, not the newer Temporal preview.
The repair is isolated from concurrent Continuity branches and production.

## Confirmed source defects

- Success called `showWizard()` and displayed PASS only near the bottom of a long
  page. No immediate result dialog or scroll-to-result existed. This explains an
  apparent no-op; it does not establish the result on the user's physical phone.
- Every note containing the nonce had to match the test hash, so a separate note
  mentioning that nonce could falsely reject an intact original.
- Starting another test did not clear the previous PASS; failures were shown as
  transient errors but were not recorded in the diagnostic test result.

## Changes

Immediate success/failure dialog, latest result beside the controls, persisted
failure guidance, reset of stale result on new preparation, stable saved-test ID
in the same atomic transaction as its capture, and exact-hash fallback for 0.7.0
records. A missing bound record cannot be substituted by a duplicate. Both text
bytes and the stored text hash must match. No original note is edited or deleted.
The diagnostic receipt omits the new internal test record ID.

The result explicitly checks a fresh encrypted-store read and SHA-256 comparison;
it does not claim to detect a force-stop or prove Android/screen restart behavior.

## Validation

`android/tests/DurableTextTests.java` covers missing setup, unsaved draft, Unicode
round trip, legacy saved records plus nonce mentions, intentional duplicates,
edited phrase, repeated preparation, deleted bound record, text corruption and
hash corruption. Run via `android/tests/run-host-tests.sh`. Build/signature and
executed test results for the exact candidate are in the delivered fix receipt.
Do not infer Android runtime or physical-phone PASS from host Java tests.

## Update and recovery

Same package (`org.continuity.recall.test`), no schema migration, versionCode 8,
versionName `0.7.1-test`. Compare the supplied APK certificate with 0.7.0 before
claiming update compatibility. Private signing material remains outside source.
Do not uninstall or clear data to fix installation; preserve a verified protected
export. Android ordinarily refuses version downgrades; code rollback requires a
new higher-version same-key build, or an independently verified backup/restore.
The original 0.7.0 branch and artifact remain untouched.

## Smallest phone check

Install the update over 0.7.0, open Test, tap Verify once. Expect a visible result
dialog. An existing saved test can pass without preparing another phrase. If the
dialog says the phrase is a draft, open Capture and save it, reopen, then Verify.
If it still fails, provide the dialog text; do not reset or delete the store.
