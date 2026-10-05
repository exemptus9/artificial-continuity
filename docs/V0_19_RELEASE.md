# Continuity v0.19 — private archive execution candidate

This release candidate extends the v0.18 capture/recovery branch. It does not replace the encrypted v0.8.8 profile or migrate any personal browser database remotely.

## Working slice

Enable **Private archive → Enable automatic private indexing** once on a browser. Later local source saves commit exact originals and rule-derived private candidates in the same existing optimistic IndexedDB transaction. Existing sources can be indexed explicitly. Review creates a new interpretation version with source ranges, reason, status and multiple project links. Source text is never replaced by the interpretation.

Search uses ranked lexical matches and a small explicit concept dictionary, not an embedding service. Current-state retrieval hides superseded interpretations unless requested. Context Capsules contain selected interpretations and quoted ranges, not all source text. Open-loop candidates remain distinguishable from confirmed tasks. Daily Genesis is a private, manually generated journal using UTC observed/indexed dates, not proof of a complete day.

Public drafting starts blank. A separately written payload is previewed and linted, then requires fresh affirmative approval. Export includes only allowlisted public fields. Source IDs, private URLs, raw text and internal metadata are never automatically projected. Public edits invalidate the content digest; item supersession or local revocation withholds it. Imported backups reset publication approval and automatic capture opt-in. The privacy linter is heuristic and is not a proof that text is nonidentifying.

## Import and preservation

The bounded new importer accepts UTF-8 text, supported ChatGPT mapping JSON, and legacy topic-report JSON up to 750 KB. It preserves original UTF-8 text, BOM and newline bytes plus an integrity digest. Selected ChatGPT branches also get readable transcripts with node IDs; import receipts retain conversation/message IDs and coverage relative to supplied files. Other branches and non-text descriptors stay in the original JSON; media itself is not recovered. Re-imports preserve changed witnesses rather than overwrite them.

The original transcript-only importer remains available and explicitly labelled. It does not retain the complete original export. Multi-gigabyte ZIP streaming, deleted chats, account-wide access and automatic account ingestion are not implemented. No missing-calendar-day percentage is invented.

Legacy reports enter as private candidates needing primary evidence. A historical A/B/C grade, publication flag, date or assistant summary is not upgraded into original testimony.

## Existing storage and migration

Storage schema remains 0.11.0 with the additive `archive` object `ContinuityArchive/1`. Use this release on readers of archive-aware backups. Existing raw sources and prior capture-origin producer version 0.18.0 remain intact. Full Backup/2 and Recovery/1 files preserve archive records. The original workspace, drafts and incoming queue remain plaintext. Existing sealed exports protect files only. Before switching origin/profile/device, retain an encrypted rescue file and verify a separate-profile restore.

Archive-aware selective Project/1 and Exchange/1 transfers are not implemented: exporting a workspace containing archive genealogy through those paths is refused with instructions to use full backup/recovery. Do not silently downgrade into an older client. Existing legacy project workflows remain unchanged before archive activation. This restriction is a deliberate compatibility boundary, not synchronization.

## Public surface correction

The public Proof of Progress page is narrowed to already public project/policy descriptions. Private Drive links, broad historical claims and personal topic payloads are removed from the current page. It reads no workspace database and has no script. The service worker now only serves the private app shell for its actual root/index navigation, not every sibling public page. Same-origin paths are still not an authentication boundary. Git history and prior downloads are not erased by this correction.

## Tests

Run `node --test tests/*.test.cjs`, then existing browser suites plus `node tests/archive-browser.cjs`. Browser tests use synthetic source text, fresh profiles, real IndexedDB, WebCrypto, service workers, 390px viewports and print-to-PDF. They do not certify a physical Android device, microphone route, Side button, screen lock or native share picker.

See the execution report for actual run outcomes. Do not count the existence of these tests as a passing result.
