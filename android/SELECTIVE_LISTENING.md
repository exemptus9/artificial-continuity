# Live listening on Galaxy

Install TotalRecall-0.11.0.apk over Total Recall 0.10.0 and choose **Update**. Open the app and tap **Start listening**. Allow microphone access and, for lock-screen controls, notifications. The English recognition model is included. The listening screen shows the microphone level and current words. Wait for the microphone to come on before speaking.

## Moving between Recall and voice mode

Leave Recall listening and switch to your voice app normally. While Android gives the other app the microphone, Recall waits in the same foreground service. The main panel and notification show that microphone input is unavailable. When Android returns it, the session resumes automatically. The archive records the time of the gap. Buffered sound from before the interruption stays separate from sound arriving afterward. A manual pause cancels the wait. If the voice app gives you a transcript, select the passage and use **Share → Total Recall → Save capture** to keep the words in your phone vault. Force-stopping the app, rebooting the phone or revoking microphone permission requires starting a fresh session.

## Keeping the words or the sound

- **Live captions** are temporary. Words can change while the recognizer works out the sentence. The input meter shows microphone energy.
- **Text journal** is optional. Turn it on to keep the recognized words during listening sessions, including conversation that produces no saved audio. Review sessions, export the time-stamped text, or delete it from the journal. The initial retention period is seven days. You can change it in this screen. Turning the journal off ends new entries. The journal stays on this phone unless you export it.
- **Saved moments** contain short original audio clips with transcripts and reasons. A 90-second audio buffer stays in memory during listening. Automatic selections include a lead-in and tail. **Keep last minute** or **“Lexi, keep the last minute”** saves recent audio on demand. **“Lexi, you missed one”** reaches farther back into the available buffer. Tap or say **“Lexi, pause Recall”** to end the session.
- **Floating controls** puts a movable control over other apps when you enable Android's display-over-other-apps permission. It can show input level and temporary captions, keep a moment, or pause. The panel hides on the lock screen. You can disable it from its settings.

## Freestyle and invented words

Recall analyzes live English recognition results. It checks successive lines for rhyming sounds, internal rhyme, repeated or revised lines and rhythmic phrasing. A local pronunciation dictionary helps recognize rhymes across spellings. Coinage, spelling, definition and self-correction language help it catch a new word, even when the recognizer substitutes familiar words. A one-off unfamiliar sound can still slip past it. Speaking the spelling or definition gives it more evidence. The saved clip preserves your actual pronunciation.

In **More → Capture sensitivity**, choose how readily Recall saves a candidate. **Worth keeping** and **Less like this** on a saved moment also adjust future decisions. Try **Review today** to play recent clips and correct a word. **Open phone vault** in More returns to the older capture and control screens.

## Optional laptop connection

The existing 0.10.0 Mint companion accepts the same selected-clip format. On your private Wi-Fi, start the receiver using **Pair phone** in Total Recall Desk on Mint. On the Galaxy choose **More → Connect to Mint**, scan the QR code and confirm. On the laptop, **Connect Lexi** enables read-only retrieval by your configured OpenClaw agent. Its model provider can receive passages retrieved for your question. The full text journal stays separate from clip transfer. [Mint guide](../mint/README.md)

The voice recognizer can mishear words in noise or at freestyle speed. Reviewing the saved audio is the best way to recover the exact line.
