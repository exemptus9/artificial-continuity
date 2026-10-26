# Total Recall: protect unfinished captures during diagnostics

This patch builds on Total Recall 0.8 source `29218f0b3ad1bb26a59a4a69d4907c41f88fef55`. It does not replace the existing original-key installer.

## Confirmed defect and repair

Preparing a diagnostic phrase replaced the text in Capture even when an unfinished draft already existed. The editor then persisted the replacement. The new guard returns to Capture with an immediate explanation when text or selected files are pending. It leaves the prior diagnostic result and record binding untouched. The core preparation routine independently rejects a nonempty draft before changing any state, including whitespace-only and Unicode drafts. No note is committed, deleted, or rewritten by the guard.

## Verification

The Java core was compiled with Eclipse ECJ 3.42.0 targeting Java 8 and executed on the installed OpenJDK 17 JRE: 165 checks passed (35 core, 12 durable-text, 43 memory workbench, 55 temporal, 20 iteration). The new regressions verify byte-equivalent state after blocked preparation and successful verification of the prior bound record. Compilation of Android Activity code and the three added native UI assertions require the Android SDK/synthetic workflow; they are not established by host tests. Physical-phone testing remains separate.

An isolated tracked-only worktree is used for Python regression testing. Exact results are recorded in the run receipt.

## Installer and update boundary

The previously delivered 0.8 APK has the retained original test certificate. The repository contains no signing secret. Synthetic CI builds use a different disposable key and must never be offered as an in-place update to the owner's installation. Do not uninstall, clear data, or overwrite the existing installer. A later owner-key build with an appropriate version code is needed for an installable update containing this repair.
