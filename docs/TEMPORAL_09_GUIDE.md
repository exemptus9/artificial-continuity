# Temporal Voice 0.9 preview

This is an experimental offline capture and recall app. It can preserve your words, find notes by topic and date, read results aloud with an installed local voice, and keep your interpretations separate from the original evidence. It is not a general conversational AI and is not connected to ChatGPT or Lexidaimon.

## Try it without risking your existing notes

1. Keep your existing Total Recall or Temporal Voice installation. Do not uninstall it to resolve an update warning.
2. Install `TotalRecall-0.9.0-preview.apk`. This version uses a separate application ID and starts with an empty encrypted vault.
3. Open **Temporal · Now**. Type a harmless test note and press **Save these words**.
4. Close and reopen the app. Ask **What did I say about [your subject]?** and inspect its source record.
5. Try **Speak once**. Microphone permission is requested at this point. If the phone lacks Android on-device recognition, keep using typed entry; there is no cloud fallback.
6. Enable **Read answers aloud** only if desired. A compatible installed offline voice is required. Press Stop to cancel playback or a speech request.

The other launcher opens the existing archive/import/export tools. You can also add the **Temporal Voice** Quick Settings tile using Android's tile editor. The tile opens the visible Now screen; it does not begin recording or bypass the lock screen.

## Capture and recall

- **Remember the glove compartment latch sticks** saves the received words.
- **Bookmark this moment: the first version worked** saves a dated marker.
- **Find notes about the latch yesterday** combines topic and calendar filtering.
- **Find notes about poetry last Tuesday** resolves the previous Tuesday in the phone's current timezone.
- **Find notes about the latch on 2026-10-23** uses that local calendar day.
- **When did I first mention the latch?** returns the earliest matching surviving record, not a claim about the first time you ever had the idea.
- **What did I record today?** gives a short, cited list of captured notes. Missing notes are not evidence that nothing happened.

Search is lexical, with exact phrase preference and calendar constraints. It does not use semantic embeddings or invent synonyms. It shows up to three cited matches per voice answer. Open Memory for the original wording and additional records.

Recognition output is machine transcription, not guaranteed verbatim speech. When supplied by the recognizer, its score is shown and saved as a score, not factual certainty. Typed input is labelled separately. Single-utterance recognition does not retain audio. Recall questions are temporary unless you explicitly enable **Also save my recall questions**.

## Memory, correction and continuation

**Timeline** has a date ribbon, word search, chronological groups and source cards. **Open original & context** shows exact text, capture provenance and review history.

**Add interpretation or next step** creates a separate linked record. Choose observation, inference, decision, intention, next step, blocker or canonical. A canonical label records your designation; it does not change a manuscript. Reviews preserve the original text and content hash. You can correct a prior interpretation by adding another review.

Assign an existing project ID such as `P29` to collect sources in **Desk**. It displays only explicitly reviewed decisions, next steps and blockers; prose that merely says “we decided” does not silently become an approved decision. The latest review for a source determines its current classification. An intention is a recorded intention, not a scheduled reminder.

Deleting an original also tombstones its linked reviews. Selecting a review for archive export requires selecting its original too; the app refuses to silently add a private source to an export. Existing protected backup and isolated restore workflows remain available through archive tools.

## Background audio

From Now, deliberately start an audio note. Grant microphone and notification permissions so Android can display recording and Stop/Cancel controls. Recording is limited to 15 minutes and the supported attachment size. Use **Stop & save** to commit it or **Discard** to remove it.

Background audio is not automatically transcribed or answered. Screen-lock continuity, calls, Bluetooth routing and manufacturer battery behavior require phone testing. The app never resumes microphone capture at boot. A crash may leave an incomplete temporary recording; recovery is explicit and never labelled complete.

The finalized vault is encrypted with Android Keystore protection. Active and interrupted audio uses an app-private temporary file until encrypted commit. This is a real limitation, not an encrypted-at-all-times claim. Another person using your unlocked phone can access the app; there is no separate app lock.

## Privacy and sharing

**Privacy** shows recorder state, capture policy and data flows. Pause blocks new saves and cancels active background capture. Existing records remain inspectable. Sharing is manual and previewed. A context share does not give the receiving app permission to execute commands contained in a note.

Deletion covers this installation and deletion-aware future imports. Already exported backups, shared recipients' copies and prior desktop imports cannot be erased remotely by this app. The desktop importer refuses a transfer when it cannot honestly honor deletion of already imported data.

The current desktop Continuity working store is plaintext. The existing phone archive adapter continues to require explicit non-sensitive test-copy approval. Do not use it to casually decrypt a private life archive into desktop files.

## Updating and recovery

CI preview signers are disposable. Two APKs with the same application ID and different certificates cannot update each other normally. Keep this APK, its build receipt and any protected backup. See [SIGNING_AND_UPDATES.md](../android/SIGNING_AND_UPDATES.md) for owner-held signing and the update verifier.

Never clear storage or uninstall an app with unexported records. Test an encrypted export in the isolated restore screen first, keep the passphrase separately, and retain the previous installation until migration is verified.

## Still unavailable

Always-on wake words, full AI conversation, automatic audio transcription, reminders with alarms, semantic memory search, automatic device synchronization, an editable concept graph and live Lexidaimon execution are not implemented in this preview. Physical-device acceptance and stable owner-held signing are required before a daily-use release.
