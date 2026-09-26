# Continuity v0.10.0 — usable local workspace

Date: 2026-09-26. Builds on the published v0.9 Live Lab; historical architecture documents describe a broader project and must not be read as capabilities of the hosted workspace.

## Implemented

Create and edit intentions, objectives, next actions, open questions, progress states and waiting reasons. Explicit Needs attention flag remains set after reading. Capture drafts are written to browser storage on input. Captures become editable ideas without replacing their source. Search matches all non-stopword query terms across actual records and returns no result when nothing matches. Backup import validates structure, previews counts, asks for replacement confirmation and keeps a pre-import recovery copy. Current and old v0.9 JSON exports are accepted. The existing browser storage key is retained. Session-only undo preserves the activity log. Corrupt stored data is not silently replaced; quota failures show an unsaved warning. A best-effort stale-tab check pauses conflicting writes; this is not a transactional multi-writer protocol.

## Honest limits

No remote model, automatic device sync, encrypted vault, authenticated backend or cryptographically verified event store. Sync, prospective-memory and policy panels are labelled simulations. Demo data is not verified personal project status. Browser storage can be lost; export regularly. Undo only covers the current session's last 20 changes. Legacy sync demo selections are reset during validation; intentions, captures, ideas and the activity log are retained. No patent/novelty claim is made.

## Validation

10 Node tests pass: migration, malformed input, duplicate IDs, supported versions, backup round trip, raw-export compatibility, honest empty search, multiword search, size rejection and text preservation.

20 Chromium DOM checks pass using source injection and an in-memory storage fixture: rendering, editable intentions, fixture restoration, persistent attention flag, draft restoration, idea editing, original-source retention, search, import preview/rejection/recovery, inert markup, explicit merge selection, undo, stale-write detection, 390px mobile overflow, corruption preservation, quota warning and absence of JavaScript errors.

The browser environment blocks URL navigation, including localhost. These are NOT live-site or native-storage end-to-end tests. The GitHub Pages workflow independently runs syntax and Node tests before publishing.

Run data tests: `node --test tests/core.test.cjs`.
