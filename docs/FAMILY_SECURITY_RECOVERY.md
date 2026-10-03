# Storage, failure recovery and consent

This is an inspected implementation description, not an independent security audit. Public means public application code/hosting, not intentionally public user records. Private hosting controls access to the page, but does not by itself encrypt its client database.

## Storage profiles remain explicit

| Storage | Protection and limits |
|---|---|
| Current browser workspace | Plaintext IndexedDB `artificial-continuity-workspace`: workspace, drafts and recovery stores. Same-origin code, a compromised extension/browser or an unlocked profile may read it. |
| Incoming share inbox | Plaintext IndexedDB `continuity-incoming-shares`; separate database. Pending content is not an accepted source. |
| Current portable encrypted files | `ContinuitySealedBackup/1`: PBKDF2-SHA256 600,000 iterations; AES-256-GCM; random 16-byte salt and 12-byte IV; authenticated header; passphrase 12–1,024 characters. Protects the file, not the working database, screen or clipboard. |
| Earlier private snapshot | App v0.8.8 encrypted event store. Local vault uses PBKDF2-SHA256 310,000 iterations and AES-GCM; portable private export defaults to 210,000 iterations and a different password rule. These are separate formats. Source recovery is not recovery of the user's vault or password. |
| Earlier Intent Integrity extension | Plaintext `chrome.storage.local`, broad page permissions and bounded logs/queue. It is retained as prototype evidence, not enabled by the capture slice. |

Browser quotas, eviction, profile resets, device loss and clearing site data can remove all local copies. Requesting persistent storage is advisory, not a backup guarantee. Origins and browser profiles are separate stores: two pages with the same branding do not share state. Cached code is not a data backup.

## Consent boundaries

The capture form explains that typing saves a plaintext local recovery draft. A fresh checkbox approves committing the original source and linked capture. It does not approve a checkpoint, publication, model transmission, microphone recording or cross-app operation. The checkbox has no draft field. Reload and rescue do not restore acceptance.

The app-local pointer guard cancels a moved Save target rather than guessing a replacement target or replaying the click. Keyboard activation remains supported. This guard controls only this app's Save action.

An incoming share or imported text can contain adversarial instructions. It is displayed as inert text, preserved as evidence and never executed as policy. Imported consent statements and historic user approvals are historical records only. A new external operation would require a new scope/payload preview and explicit authority.

OS keyboard dictation is outside the app's privacy boundary. Calling its text a manual transcript does not establish local speech processing, microphone source, speaker identity or audio retention. No audio provider is contacted by this slice.

## Recovery workflow

1. Before changing origins, browsers, profiles or versions, stop concurrent editing. Save important forms or export a rescue file that includes their drafts. Keep all original exports.
2. Use the rescue page for workspace plus unfinished drafts and incoming items. Plain export is readable private material. Encrypted rescue export uses the existing sealed-file format; losing the passphrase makes it unrecoverable here.
3. In the destination, choose the file and inspect the recovery preview. Rescue import restores inert source drafts under separate keys. It neither replaces the workspace nor accepts incoming items as facts.
4. If a whole-workspace restore is intended, explicitly extract the embedded standard backup, then use the existing Backup & history restore preview. For combining histories use a reviewed project transfer, not whole-workspace replacement.
5. Open the recovered draft text, review it and save it deliberately. Compare source text, counts and pending/confirmed status against the old environment. Only then consider deleting any old copy.

The workspace and drafts are read together in one IndexedDB transaction. Incoming shares live in another database, so the rescue file is not a globally atomic cross-database snapshot. Pause intake during export and inspect counts. A concurrent or failed capture does not become an accepted record merely because a rescue file contains its draft. A browser download event does not prove that a file was retained on the user's device.

## Failure behavior

| Failure | Required behavior / recovery |
|---|---|
| Draft persistence fails | Show an unsaved warning, retain visible text and stop capture commit; copy/export the visible original before leaving. Sudden process death before autosave can still lose keystrokes. |
| Workspace save fails or storage quota is hit | Transaction aborts. Do not display Saved or clear the capture draft. Export existing content and preserve the visible text before resolving space. |
| Another tab saves first | Revision check rejects stale mutation. Preserve the draft, load the newer workspace and review again. This is optimistic conflict detection, not automatic merging. |
| Commit succeeds but draft cleanup fails | State is already saved. Retained idempotency token lets a retry finish without a second source/capture. Editing the remaining draft starts a new capture; it does not overwrite the original. |
| Export is malformed, changed or wrong password | Validate/decrypt before any write. Existing workspace remains unchanged. No password reset or bypass exists. |
| Rescue imported twice | Deterministic rescue keys skip identical artifacts. Conflicting rescue content aborts the transaction; never overwrite the earlier draft. |
| Pre-import rollback needed | Existing `before-import` slot retains the immediately prior workspace. A later import can overwrite that slot. It is not multi-version history; keep downloaded backups. |
| Lost browser storage without export | No recovery promise. Server code and Git history do not contain local private records. |
| Service worker missing / wrong profile | Do not assume offline/share behavior. Use explicit paste/file input, verify worker control, and keep the source content before trying the OS share path. |
| Older code opens newer export | Preserve the file untouched; use the matching release in a separate profile. Additive fields may be retained by old validators but new invariants are enforced only by the new release. |

## Migration gates

Never silently decrypt a private-vault bundle into the plaintext client. First verify it in the preserved private implementation, keep the encrypted original and prepare a reviewable field projection. Any future plaintext transfer must explicitly explain the downgrade before the user chooses it. This release's adapter refuses private vault formats.

Do not replay historical event chains, consent events, triggers, provider configurations or extension logs as current authority. Preserve them as evidence and make new records through current explicit review. Native Android integration is a future adapter with its own device tests and permissions.

## What hashes mean

SHA-256 values bind an audit record to the bytes inspected and detect changes. They do not certify a timestamp, authorship, claim truth, lack of malware, secure deletion or absence of other copies. Export authentication is stronger than a bare checksum for detecting ciphertext modification, but a weak passphrase, compromised runtime or plaintext export can still expose content.

## Platform references

Checked 2026-10-03: [Chrome Web Share Target](https://developer.chrome.com/docs/capabilities/web-apis/web-share-target), [MDN Storage API](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API), [MDN Web Share Target manifest field](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Manifest/Reference/share_target), [MDN Web Crypto encrypt](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/encrypt). These describe platform APIs, not certification of this app or proof that a particular phone path works.
