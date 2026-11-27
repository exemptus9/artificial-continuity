# Live listening on Galaxy · 0.28.1

Install `TotalRecall-0.28.1.apk` over the earlier Total Recall app and choose **Update**. Open the app, tap **Start listening**, allow microphone access and wait for the input meter. Allow notifications for the ongoing listening controls. Speech recognition works offline with the included English model.

## Teach a coined word from your own line

If the journal is enabled, open **Full journal**, tap a passage and edit the words the phone misheard. **Teach this spelling** extracts the part you changed. Check both fields, narrow the phrase if needed, then tap **Save spelling**. Choose **Save correction** in the passage editor if you also want to keep the edited line. **Compare with original recognition** lets you revisit the recognizer’s wording. Manage or forget a learned phrase under **My words & phrases**.

## Record a long session

Open **Long sessions → Start long session**, then speak when the timer advances. **Mark this point** places a bookmark at the audio position. **Finish & save session** keeps the complete audio, including pauses within your speech. A microphone interruption or storage limit finishes the available recording. Starting from idle releases the microphone after finishing. Starting during selective listening returns you to your existing listening session. The phone keeps this audio locally until you export or delete it. Separately enabled full-audio archiving follows its own backup settings.

During listening, say **“Lexi, start a long session”**, **“Lexi, mark this point”**, or **“Lexi, finish the session”**. The active lock-screen card has Mark point and Finish session. The saved recording's menu opens playback, markers, renaming, audio export and marker-index export. In the private web archive choose **Long sessions** and open the two exports.

## Keep a moment

Speak naturally. In the default mode, a 90-second rolling buffer holds recent audio in memory. Recall saves selected clips with a lead-in and tail. Say **“Lexi, keep the last minute”** or tap **Keep last minute**. **“Lexi, you missed one”** reaches farther back in the available buffer. **“Lexi, pause Recall”** ends the session. Voice commands work while listening is active.

**Live captions** show what the recognizer is hearing. Wording can change during an utterance. Open a saved moment and choose **Playback & trim** to hear the original, mark the part you want and **Save excerpt…**. The new clip keeps its link to the original. If you are still recording, playback offers **Pause & play**. Tap **Start listening** afterward to begin a fresh session.

## Use the floating controls

On the listening screen, open **Floating controls**, grant display-over-other-apps access and enable the panel. Drag any control to move it. In a tall panel, scroll the body or drag the header. The panel hides on lock and appears again after unlock during an active session.

- **Writing setups.** Choose **Poet** for six caption lines, the available-audio readout and a hold that saves a poem line. **Word collector** uses four lines, saved/flagged totals and a hold for a Forge word. **Restore my settings** brings back your three choices from before the first setup. The presets respect your caption visibility and microphone and backup choices.
- **Ultra mini.** Between events, the top line shows elapsed listening time and the seconds of recent audio available to save (↶). The trace shows microphone activity. Read the developing words beneath it or choose the between-phrase readout in settings. Tap the readout to open your journal. Hold it to save recent audio. **Ultra mini hold** chooses Recent audio, Forge word, Poem line or Project decision. The default keeps recent audio.
- **Medium.** Tap **Save** as soon as a line comes to mind, or **Pause** to release the mic. Tap **↗** to expand.
- **Large.** **Save as…** opens Forge word, Poem line and Project decision. The category you tap saves up to 60 seconds of available audio. During a long session, a successful labeled save also places a bookmark with an eight-second lead-in, limited by the start of the session. **Start session**, **Mark point** and **Finish session** work on the panel. The long-session audio clock and marker count stay above the scrolling controls while a microphone interruption or a service acknowledgment takes the headline. **Open last save** shows the most recent local clip’s category, time and ID and opens its recording.
- **Readouts.** **Live** shows a provisional recognition and **Heard** introduces completed words. Choose two, four or six visible caption lines in settings. The large panel reports input level, elapsed session time, available rewind audio, word/flag/save totals and situation cues. The extra detail setting shows the reported mic route when available, plus journal and full-audio settings, the paired archive state and the last verified receipt for that destination. **More** also shows the reason for the last clip when captions are on.
- **Close or hide.** **×** asks before ending recording and closing the app. **Go back** keeps listening. **More → Hide** dismisses the controls. **Lock screen controls & sound** lets you check notification access or put **Mark missed** on the listening card. Android controls which actions are available on a locked phone.

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

## Start continuous backup

After pairing, open **Archive & sync → Set up continuous backup**. The screen shows your actual destination, the AAC bitrate, phone-space limits, hourly transfer limits and which other content is already selected. To keep recording as the phone fills, check **Free oldest phone copies when space is tight, only after a verified server receipt**. Leave it unchecked to keep acknowledged copies on the phone. Confirm **Enable continuous backup**. Tap **Start listening** on the listening screen if the microphone session is currently stopped. During an active session, full-audio capture starts after confirmation.

This setup enables local full-session AAC, includes pending and future full-audio segments in transfers, and enables automatic batches. It preserves your clip, journal, network and charging choices. Any previously queued transfer approvals are replaced. A new installation defaults to Wi-Fi and charging with up to 128 MiB and 256 items per hourly batch. Existing transfer settings carry forward on upgrade.

## Choose what to send

1. **Transfer choices** controls **Selected clips, timestamps, transcripts and edits**, **Full audio segments** and **Include closed text journals**. Including journals asks for separate confirmation. Change **Batch limit** and **Items per batch** to adjust recurring capacity. Changing a choice stops existing batches.
2. **Preview transfer → Queue this batch** sends one batch under your regular limits. **Set up automatic transfers → Enable automatic transfers** permits regular batches of the selected content, even when full audio is off. Android can defer work until Wi-Fi or charging returns.
3. **Preview all pending** prepares a one-time catch-up of up to 1 GiB or 4,096 items. The screen shows the exact selected bytes and the total pending on the phone. For example, an 800 MB backlog can fit in one approval. It checks available server space before unlocking **Queue this backlog**. Your Wi-Fi and charging choices still apply. If more is pending than fits, review the next batch after this one completes.
4. Open **Archive & sync** to check progress or choose **Check server space**. **Stop queued and automatic transfers** cancels pending work on the phone. Interrupted transfers can resume from their acknowledged offsets. Keep the app installed and reopen it after a force-stop.

A completed receipt matches the original item ID, byte count and SHA-256. Moving to a different destination makes retained phone audio eligible for the new server. Previously removed phone copies stay on their original server. The hosted archive has a seven-year retention policy and a 1 TB (1,000 GB) media allowance. At 32 kbps, seven years of round-the-clock audio takes about 884 GB before overhead and WAV highlights. [Hosted storage and recovery](../mint/CLOUD.md)

## Freestyle and invented words

Recall checks successive recognized lines for rhyming sounds, internal rhyme, repeated or revised lines and rhythmic phrasing. A local pronunciation dictionary helps with rhymes across spellings. Saying or spelling a new word and giving its definition helps the detector catch it. A fast or unfamiliar line can slip through. Use the keep command while the words you want are still in the buffer, and use the saved audio to correct the transcript.

In **More → Capture sensitivity**, choose how readily Recall saves a candidate. **Worth keeping** and **Less like this** on a saved moment adjust future decisions. **Review today** opens recent clips. **More → Open phone vault** returns to the original capture and control screens.

## Earlier Mint clip connections

An existing **More → Connect to Mint** pairing can continue sending selected clips independently. To use the batch limits above for those clips, open **Archive & sync → Move saved clips to these controls**. On Mint, **Connect Lexi** enables read-only selected-clip retrieval for your configured OpenClaw agent. Its model provider can receive passages retrieved for a question. [Mint guide](../mint/README.md)

## Review a situation

Open a saved moment to see cues supported by its words. Edited transcripts and personal notes have separate labels. Tap **Analyze original recording** to scan the audio locally. For a complete session, open **Archive & sync → Browse recordings & analysis**, choose a recording, and tap **Analyze recording**. Quiet time includes all sound below the indicated threshold. English speech recognition is approximate.

Automatic backup follows the paired destination, selected content, network, power and per-batch limits. **Transfer choices → Automatic batch spacing** offers 5, 15 or 60 minutes. Re-enable automatic backup after changing a choice. The archive shows its most recent verified receipt.

## Calibrate your voice and room

While the microphone is receiving audio, open **Live now → Remember voices → Teach my voice**. Speak a sentence on your own and pause. Check the speaker and edit **What I actually said** before you use the sample. Add two more samples from your usual microphone positions. The phone stores a speaker embedding and the short wording correction you confirmed. The wording applies to future finalized lines matched to **Me** while context correction is on. **My voice corrections** lets you forget individual entries.

Under **Capture choices**, choose **Learn room noise · 2 seconds** while only the background is audible. The live English decoder uses the resulting filter. Capture keeps the original audio. Learn again after a pause or a microphone change, or turn off reduction at any time.

The expanded overlay shows **Now** and **Last**. These are interpretations of recent words and voice activity. After 45 seconds without a fresh cue the latest one moves to **Last**. Open **Live & saved** to inspect the source wording.

## Record and review your own words

Choose **Record my words** on the large panel or **Record my words & edit** on the recorder. Speak after the timer starts. **Finish & edit** opens the saved take. A take also closes at one minute or when usable microphone input ends. **Listen to original** offers to pause the live microphone for playback. Edit **What I actually said**, then **Save corrected words**. You can copy or share the wording or share the original WAV separately. Takes appear in **Saved on phone → Recent saved clips** even with the journal switched off.

Tap a passage in **Full journal** to correct, copy or share its wording. **Compare with original recognition** and session text exports retain the earlier reading.

## Listen with the phone locked

Expand Total Recall’s ongoing notification to see microphone reception, registration of words, saves, flags, capture settings, and backup status. **Save last min** keeps recent audio. **Start take** begins a new one-minute recording and becomes **Finish take**. **Pause** releases the microphone. The paused notification offers **Resume** and a tap-through to the recorder. Android and your lock-screen settings determine the controls you see and whether authentication is required.

Open **Live & saved → Lock screen & sound** for transcript and situation previews, Android notification settings, and sound controls. The rising wake sound means Recall is ready for your command. The falling save sound follows a requested clip or voice take reaching local storage. Optional spoken receipts say “Clip stored” or “Take stored.” Use **Test wake** and **Test save** to set your cue level. Audio follows media volume and output routing.
