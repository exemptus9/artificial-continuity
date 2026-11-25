# Selective listening on Galaxy and Mint

Install the signed Total Recall 0.10.0 APK on your Galaxy and choose **Update** if Android recognizes the earlier Total Recall test app. Open **Selective listening → Start listening** and allow the microphone. Allow notifications for lock-screen controls. The English speech model is packaged in the app. Wait for the listening status before talking. A long press on the launcher icon offers a **Listen** shortcut. You can also add **Recall listening** to the Quick Settings panel.

## Try it

Say “I decided to move the instructions to the facing page,” then pause. Check the new moment and press play to hear the original. To rescue a thought after speaking it, say **“Lexi, keep the last minute.”** For a missed selection, say **“Lexi, you missed one.”** To end a session, say **“Lexi, pause Recall”** or tap **Pause listening**. Touch controls also work in a noisy room. Start again from the phone when you want to resume.

## What the phone keeps

The microphone runs during an active session. It keeps a rolling 90 seconds of audio in RAM and transcribes English speech on the phone. Automatic selection looks for decisions, definitions, creative lines and connections. A selection includes preceding context and a short tail. The detector uses local rules and your feedback. It can miss a subtle line, mishear a coined word or occasionally select an ordinary remark.

Each saved moment has the original audio, time, recognized words and selection reason. You can search, play, correct text, add a note, vote or delete. Original audio and machine text are preserved when you correct the wording. **Worth keeping** and **Less like this** tune future choices. Ending a session discards the unsaved buffer. After an interruption or reboot, check the displayed status and start a fresh session if needed.

Recordings and transcripts are private app data. Exported ZIPs contain readable transcripts and replayable WAV files. The microphone notification allows you to pause when the conversation moves somewhere private. Continuous recognition uses battery. Try a short screen-off session on your Galaxy to see how it behaves with your battery settings and Bluetooth devices.

## Optional Mint and Lexi connection

Install the companion Debian package and launch **Total Recall Desk**. On the same private Wi-Fi network, click **Pair phone** and start the receiver. On the Galaxy, open **Connect to Mint**, scan the QR code and confirm. The phone verifies the receiver certificate and queues selected moments if the laptop is temporarily unavailable. Keep the receiver running to receive them.

On Mint, select **Connect Lexi** to expose read-only capture search to your configured OpenClaw agent. Open a fresh Lexi conversation and ask for a distinctive phrase. Clip playback remains available on Mint. Your configured agent's model provider can see passages the agent retrieves. You can also use the ZIP export and desktop import without pairing. See the [Mint companion guide](../mint/README.md) for the command-line interface and storage details.

## Rebuild and acceptance tests

See android/build.sh for the Android 36 SDK and signer settings. android/prepare-speech.py pins dependencies and model checksums. Run bash device-tests/test-engine.sh to exercise selection, replay windows, feedback and privacy controls on a host JDK. The disposable Android instrumentation procedure is in device-tests/run.sh. The source branch's GitHub Actions job builds both APKs with an ephemeral CI key and exercises the app on an Android emulator. CI keys are separate from the signed app's update identity.
