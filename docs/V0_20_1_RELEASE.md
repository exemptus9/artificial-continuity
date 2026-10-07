# Continuity 0.20.1 — guided intentions and ChatGPT exchange

This release completes PR #10's browser usability work. It is not a direct ChatGPT connector or API integration. Publication is established by a successful main deployment plus the live verification receipt, not by this file's existence.

## Included

- Plain-language intention labels and examples distinguishing obstacles, prerequisites, actual pending replies, and controllable actions.
- Guided Prepare → Ask → Review workflow, with friendly field names in the approval screen.
- Full-answer paste when it contains exactly one JSON code block; raw reply JSON remains supported. Unsupported operations, unknown requests, stale writes, ambiguous code blocks, and unselected edits remain rejected.
- An accessible mobile guide at `help.html`, linked from the application and included in the offline shell. The guide includes a worked example, every field and state, error recovery, and safe update steps.
- App/cache version 0.20.1; storage schema remains 0.11.0. No data migration, credential connection, or personal intention edits are performed by publishing this release.
- Reproducible tracked-source snapshots and pinned real 0.20.0 → 0.20.1 browser upgrade tests.

## Update

Use the same browser/profile as your existing workspace. Save work and export a backup. Device & drafts → Check for app update → App update ready · save & apply. Confirm the reload; verify v0.20.1. Do not clear browser data.

## Verification gates

Run all Node data tests, Python tests, and the existing browser suites. `tests/update-browser.cjs` additionally requires `CONTINUITY_BASELINE_SITE` pointing to the actual 0.20.0 `site/` at commit `ea41a077e753f90fb78de8e2e406b5f5af05f052`. It seeds only synthetic records, installs the old shell, offers but does not force an update, verifies exact record/draft preservation, and opens the new guide offline.

After main deployment, `tools/verify_live_release.py` compares live asset SHA-256 values against the checked-out release. `tests/live-release-browser.cjs` exercises the published handoff with synthetic, browser-local data and records screenshots. Evidence is in the corresponding GitHub Actions artifacts. These checks do not establish native Android app-switcher or share-picker behavior on the owner's physical phone.

## Privacy and authority

No private employment records, legal details, original poems, credentials, or chat transcripts are published as examples. Suggestions are not automatically accepted. Source evidence remains separate from current form fields. Guide examples are explicitly fictional.
