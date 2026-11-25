# Live listening on Galaxy · 0.12.0

Install `TotalRecall-0.12.0.apk` over the earlier Total Recall app and choose **Update**. Open the app, tap **Start listening**, allow microphone access and wait for the input meter. Allow notifications for the ongoing listening controls. Speech recognition works offline with the included English model.

## Keep a moment

Speak naturally. In the default mode, a 90-second rolling buffer holds recent audio in memory. Recall saves selected clips with a lead-in and tail. Say **“Lexi, keep the last minute”** or tap **Keep last minute**. **“Lexi, you missed one”** reaches farther back in the available buffer. **“Lexi, pause Recall”** ends the session. Voice commands work while listening is active.

**Live captions** show what the recognizer is hearing. Wording can change during an utterance. Open a saved moment and choose **Playback & trim** to hear the original, mark the part you want and **Save excerpt…**. The new clip keeps its link to the original. If you are still recording, playback offers **Pause & play**. Tap **Start listening** afterward to begin a fresh session.

**Floating controls** on the listening screen lets you enable a movable panel with level, live captions, keep and pause controls. Grant Android's display-over-other-apps permission when prompted. The panel hides on the lock screen.

## Change apps or use voice mode

Leave Recall listening and open your voice app. If Android gives that app exclusive microphone access, Recall shows **Waiting for microphone**. Leave the session running. It retries automatically and starts a fresh audio buffer when input returns. The gap marks a period with no usable input. If the other app offers a transcript, **Share → Total Recall → Save capture** can preserve the passage you select in the phone vault.

Tapping **Pause** ends the wait. After a force-stop, reboot or microphone-permission change, reopen Recall, grant microphone access if needed, then tap **Start listening**.

## Keep full audio or a text journal

- **Full audio.** On the listening screen, open **Archive & sync → Full audio & phone storage → Enable full audio on this phone**, then confirm **Enable full audio**. Recall keeps AAC segments of up to two minutes while listening. The default is 32 kbps with a 2 GiB phone budget and a 512 MiB free-space floor. You can also choose 24 or 48 kbps. Open **Browse phone audio**, select a segment and choose **Play** or **Export AAC**.
- **Space on your phone.** Enable **Free space from oldest phone copies after a verified server receipt** to reclaim older acknowledged segments when the phone budget or free-space floor is reached. Pending segments stay local. When space runs out, full-audio capture can have a gap. Open **Archive & sync** to see the pending count, storage and gaps.
- **Text journal.** Open **Text journal** on the listening screen to save the recognized words throughout a listening session, including speech outside selected clips. The default local journal retention is seven days. The journal screen lets you change it, review sessions, export text and delete a session. Transferring closed journal sessions requires a separate choice below.

## Connect your archive

Open **Archive & sync → Connect website or Mint**. For a later change of destination, choose **Change archive**.

- **Website.** Tap **Open archive website**, enter your private owner key, then choose **Pair phone → Reveal pairing code**. On the same phone, tap **Copy pairing code**, return to Recall, paste it, and tap **Pair archive → Connect website**. With the website open on a second screen, choose **Scan pairing code** in Recall. The phone shows the destination before connecting. [Archive website](https://total-recall-archive-production.up.railway.app)
- **Mint.** In **Total Recall Desk** on the laptop, choose **Pair phone**. Put the devices on the same private network or your configured private VPN. Scan the QR code in Recall, or paste the code and tap **Pair archive**. Confirm **Pair receiver**. Keep the receiver running when you want to transfer.

## Choose what to send

1. Open **Transfer choices**. **Selected clips, timestamps, transcripts and edits** is the default. Select **Full audio segments** to upload retained AAC. Select **Include closed text journals** and confirm **Include journals** to upload transcripts for completed sessions. Changing a choice stops existing batches.
2. For a single batch, tap **Preview transfer**. Check the destination, item counts, exact bytes, and phone and server capacity. Tap **Queue this batch**. For regular transfers, choose **Set up automatic transfers → Enable automatic transfers**. The defaults require Wi-Fi and charging, with up to 32 MiB and 64 items per batch. Automatic batches run at most hourly after a completed batch. Android can delay a run.
3. To check progress, open **Archive & sync**. **Check server space** shows available capacity. **Stop queued and automatic transfers** cancels pending work on the phone. An interrupted upload can resume from the last acknowledged byte after you approve another batch or the active job retries.

A completed receipt matches the original item ID, byte count and SHA-256. Moving to a different destination makes retained phone audio eligible for the new server. Previously removed phone copies stay on their original server. The hosted archive has a seven-year retention policy and capacity limits. At 32 kbps, seven years of round-the-clock audio takes about 884 GB before overhead. [Hosted storage and recovery](../mint/CLOUD.md)

## Freestyle and invented words

Recall checks successive recognized lines for rhyming sounds, internal rhyme, repeated or revised lines and rhythmic phrasing. A local pronunciation dictionary helps with rhymes across spellings. Saying or spelling a new word and giving its definition helps the detector catch it. A fast or unfamiliar line can slip through. Use the keep command while the words you want are still in the buffer, and use the saved audio to correct the transcript.

In **More → Capture sensitivity**, choose how readily Recall saves a candidate. **Worth keeping** and **Less like this** on a saved moment adjust future decisions. **Review today** opens recent clips. **More → Open phone vault** returns to the original capture and control screens.

## Earlier Mint clip connections

An existing **More → Connect to Mint** pairing can continue sending selected clips independently. To use the batch limits above for those clips, open **Archive & sync → Move saved clips to these controls**. On Mint, **Connect Lexi** enables read-only selected-clip retrieval for your configured OpenClaw agent. Its model provider can receive passages retrieved for a question. [Mint guide](../mint/README.md)
