# Total Recall Desk for Linux Mint

Install the Debian package and launch **Total Recall Desk**. The recorder opens with a microphone history, developing words and the last completed phrases. The amd64 installer includes the same small English Vosk model as Android. Recognition runs offline. **Start recording** opens the selected microphone. **Stop recording** releases it and files the last passage. **More → Archive tools & phone receiver** opens the desktop archive utilities.

**Local journal** opens this laptop's entries. New installations save a journal during recording. An existing **Journal off** setting carries forward. Choose **Enable journal** on the panel to turn it on. The recorder checkpoints journal audio at pauses and after about 45 seconds of continuous input. A background worker decodes these checkpoints even when the panel is hidden. It retries interrupted files and saves recognized words with timestamps. **Journal saved** reports the latest local write. The full-audio segment clock runs independently.

**Shared journal** opens the same signed-in archive on laptop, phone and web. On the laptop choose **Connect…**, open the archive, select **Pair phone**, and paste its code in **Backup**. Turn on **Sync this laptop with my shared archive**. A new connection sends journals only until you select **Include original audio and bookmarks**. Existing audio-sharing connections keep their scope. Pending eligible files upload when your network policy allows, including after reopening the panel. **Shared** shows the time of the last verified journal receipt. On the phone choose the same hosted destination under **Archive & sync**, enable **Include closed text journals** and automatic backup. The phone's local journal remains available offline. The shared website shows completed uploads from both recorders.

If the older panel was already open during installation, close it and reopen Total Recall Desk. If the updated panel reports a previous recorder, stop recording and sign out of Mint and back in once to load the new worker. [Hosted archive access](CLOUD.md)

## Connect the phone

1. Connect both devices to your private network. Open **Pair phone** in Desk. Pick the computer's private IPv4 address and choose **Start receiver and copy code**.
2. Open the phone's Mint connection screen and scan the pairing QR or paste the code. Treat this code as a password. Trust comes from the pinned certificate and secret in the code.
3. Choose the media you want to transfer on the phone. Selected original WAV clips, optional full-session AAC segments and optional text journals are separate choices. Sending resumes when the host becomes reachable. Each completed upload returns its ID, byte count and SHA-256.
4. Select **Open desk and start receiver when I log into Mint** if you want automatic receiving. The computer must be awake, reachable and have available storage. The phone and host can also use an owner-managed private VPN, including a 100.64.0.0/10 mesh address.

In **Private archive**, search for an item, play the original or download it. Browser audio supports seeking. **Activity** shows the busiest recorded day and counts of saved clips by label. Choose **Review latest** to open a candidate from that group. Existing note, correction, original transcript, feedback and excerpt-source fields are preserved. **Import capture ZIP** accepts Android selected-clip exports. **Connect Lexi** adds a read-only selected-clip search tool to the configured OpenClaw agent `lexidaimon`. Start a new chat and ask Lexi to find a saved moment. Full-session journals and continuous recordings stay outside that tool.

## Laptop microphone and floating panel

Open **Laptop microphone** in Total Recall Desk. Drag the title bar to move the panel. **Start recording** uses the input selected in capture settings (the Mint default initially), and the input meter shows the measured sound level. **Keep moment** saves the available last 15, 30 or 60 seconds as a separate bookmark in Review. **Browse & play** opens all local recordings. **Stop recording** releases the microphone and finalizes the current segment. Closing or hiding the panel leaves a running recorder active. Reopen it from Desk.

In the expanded laptop microphone panel, **Poem line**, **Coined word** and **Pick up here** save the preceding audio with a searchable category. Use **Pick up here** before an interruption, then **Open last keep** to hear where you left off. The button shows the capture time and actual duration. Routine segment saves leave that button pointing at your intentional bookmark. The compact panel shows the recording clock, live input level and available lookback. In **More → Settings → Feedback**, choose visual feedback, wake chimes, save tones or local spoken save confirmations. A save confirmation follows a verified local write. A laptop with no local speech engine uses a tone. If a recorder was already running during the upgrade, sign out of Mint and back in once to load the new worker. The generic Keep moment action still works with the previous worker.

**Settings** controls the sound system (PulseAudio/PipeWire or ALSA), AAC quality, segment length, bookmark length, compact mode, always-on-top and optional recording at login. The recorder runs under the signed-in user. A sleeping or powered-off laptop has no microphone coverage. Laptop clips and full-audio segments are saved on this computer independently of the phone. The Android recorder offers automatic creative highlights. The recorder shows live words and recent situation cues. Use **Keep moment** on the laptop for passages to review.

In **More → Settings → Capture**, select Pulse or ALSA, then follow the system default or pin a particular external microphone. **Refresh inputs** updates the list after plugging in a device. If Mint cannot enumerate it, enter its known source ID. A pinned input that disappears retries that same input. **Check microphone** measures three seconds of unsaved audio before capture, or reports current measurements from an already running matching input. The expanded panel shows original average and peak in dBFS and flags sustained very low levels or samples reaching the digital limit. **Voice → Input gain** can boost live recognition by 6 or 12 dB for a soft delivery. Files in the archive keep their original level.

Choose **Voice check** on the expanded laptop panel to read a five-second practice line in your usual voice. During recording it taps the existing microphone stream. With the recorder off, it opens only the selected input for the check. The panel shows measured levels, the offline decoder’s actual wording, and missing or extra words. **Replay** plays the original sample. **Compare gains** runs the same five seconds at 0, +6 and +12 dB and shows how many prompted words each pass matched in order. It never applies a setting. Closing the window discards its sample. An already-running recorder continues its ordinary archive policy during the test or replay; headphones keep a replay out of its microphone.

**Voice → Keep possible creative phrases automatically** listens locally for explicit phrases such as “I coined a word,” “the opening line goes,” and “let me try that line again.” Each candidate includes the available lookback and up to five more seconds, with a 90-second pause between automatic catches. Candidates appear in Review and Archive. This option starts off, works independently of spoken commands, and leaves **Open last keep** pointing at your intentional bookmark.

To save while another app has keyboard focus, open **Settings → Panel**, select a bookmark type and choose **Copy shortcut command**. Add that command in Mint’s custom keyboard shortcuts and assign keys of your choice. For example, `total-recall-desk keep coinage` queues a coined-word bookmark from the active recorder. It cannot turn on the microphone. The panel shows the receipt once the original audio reaches the local archive.

For hands-free filing, say **“Lexi, save a line,” “Lexi, save a word,”** or **“Lexi, mark my place.”** You can also say the wake word, wait for the chime, then give the command within eight seconds. **“Lexi, status”** reports the available lookback. **“Lexi, last save”** reads the type and length of the last confirmed laptop bookmark. **“Lexi, what can I say?”** lists choices. Use **Settings → Feedback → Spoken guidance and saves** for spoken choices, misunderstood commands, passage changes and actionable failures. Choose the pace and cue volume, then try **Preview feedback**. The standard wake-chime setting stays in effect until you change it. **More → Recent recorder feedback** shows the last 12 system responses and their times. Local speech uses eSpeak when installed and falls back to tones. While a prompt plays, live recognition pauses and the original microphone stream keeps recording. Use headphones if you want your speakers' output excluded from the recording.

For a second copy, enable **Send laptop audio and bookmarks to my hosted archive** and paste a hosted pairing code from the website. Completed laptop recordings are uploaded in order, with byte checks, resumable transfers and a matching SHA-256 receipt. Local originals stay in the local archive. Pending files wait for storage or connectivity. Desk's existing storage quota and free-space reserve apply. Overlapping device recordings retain their separate originals.

## Review, file and delete

Open **Review** in the private website or local archive. Listen inline and click a category to file the item. Use **Collection** to open a category, see all filed items, or revisit dismissed clips. **Inbox settings** lets you rename or add buttons and set the page size and order. Changing the button list preserves existing collections. Within an item, **Classify or edit writing** supports multiple categories, draft text, a corrected word and its recognized spelling. These are private archive collections. Copy a draft to take it to another writing app.

Choose **One at a time** for a single card with its time, source, duration and category. **Next item** skips ahead without changing its filing. Choosing **Poem draft** or **Forge Lexicon** files the current clip and brings the next inbox item into view. **Undo filing** reverses your most recent action in that review view, including draft text automatically seeded by that action. A subsequent edit or filing of the same item on another device invalidates its undo. Your layout preference stays in this browser.

**Dismiss** leaves the original in Archive. To remove an item, open it, expand **Delete from this archive**, check the confirmation and click **Delete this item**. The operation removes the active original, its text and its filing from this archive. Other devices, independent excerpts, copies you exported and previous backups can retain material. The archive rejects repeat uploads of the same deleted item ID.

## Find a moment

From Overview, search for part of a line or choose **Coined words**, **Poetry & freestyle**, or **Yesterday**. In Archive, combine recognized words, notes and corrections with a clip label and the local date the item started. Results show a passage around the match when available.

If you remember roughly when you said something, expand **Find by approximate time**. Set the date, clock time and a five-, fifteen- or sixty-minute window. This includes recordings already underway. Selecting a recording covering that time cues its player with up to three seconds of lead-in. The form shows your browser's time zone and offers both occurrences of a repeated clock time when daylight saving time ends.

Search also covers your filed titles, writing, saved stories, recognized spellings and text from recording analysis. A result names the field containing your words. A timestamp beside **Recognized in audio** opens the source recording near that passage.

When an exact search comes up empty, **Find similar spellings** looks for a word one letter or adjacent swap away, or for a joined word written as two words. It checks up to 250 recent items within the current filters, using titles, corrections and excerpts of writing, stories and transcripts. Each suggestion shows the actual saved wording beside your query. Opening it preserves the original text. A timestamped journal result offers **Find audio at** to open a covering recording directly, or choose one when several overlap.

For a quiet clip or long-session recording, choose **Playback boost → +6 dB** or **+12 dB**. **Original** restores normal playback. Boost limits loud peaks. Select **Remember in this tab** to reuse the level as you open another recording. This changes only browser playback. Downloads, exports and microphone capture retain their original audio.

**Keep line** saves a timestamped journal passage to **Review → Saved journal lines**. Pick a category such as **Poem draft** or **Forge Lexicon**, search by either the corrected words or the first reading, then choose **Open source line** to see it highlighted in context. **Copy line** copies the wording. **Link to this line** is a private bookmark for someone already signed in to this archive. Unsave removes the bookmark. Deleting or expiring the source journal also removes its saved lines.

After transferring an edited phone journal, choose **Compare changed wording** to read revised and originally heard lines together. Search by either spelling. Opening linked audio and choosing **Back to journal** preserves your search and comparison.

Open a timestamped journal to find a line and select **Find audio here**. A single covering recording opens paused. When several recordings overlap, choose the microphone and recording you want to hear. Playback starts with up to three seconds of lead-in. **Back to journal** restores the search and page. **Copy to writing notes** on a selected clip copies its text, correction and note for use in another app.

## Long session markers

Choose **Long sessions** and select your audio and its marker index together, in either order. You can also open the audio alone and add its index later. Play it and tap **Poem line**, **Coined word** or **Follow-up** when a passage deserves another listen. You can rename, reclassify or remove a selected marker. Undo restores up to twenty recent marker removals while that recording is open. Search by marker name, type or displayed timestamp, move between results and set **Replay lead-in** from zero to fifteen seconds to hear the setup.

**Save marker index** downloads a companion JSON that you can reopen with the audio. Indexes made in a browser with secure hashing available are checked against the audio contents. For an older Android index, the player checks the size and asks you to confirm the title before merging. Merging preserves the markers you already have and skips exact duplicates. Audio and markers stay in the tab while you visit other archive views. Download the index to keep edits across a reload or after closing the tab.

## Space, retention and backup

**Storage & backup** shows available space and lets you set a quota in GiB. Zero uses the available filesystem with 1 GiB reserved. Incoming items reserve capacity, allowing interrupted transfers to resume without allocating the same bytes twice. Incomplete uploads release their reservations after seven days. When space runs out, the host returns a storage-full status and the sender keeps its pending item. A quota limits incoming transfers and never evicts a recent capture.

An active Desk, headless host or standalone receiver checks expiry on startup and hourly. Data expires seven calendar years after the end of the capture in UTC. A February 29 anniversary becomes February 28 in a non-leap year. If the host has been off, it applies overdue expiry on startup. The disk and its backups contain private recordings. Use a private account and an encrypted disk if others can access the computer.

Choose **Back up to a folder…** for an immediate verified backup or **Back up daily to a second drive** to configure daily copies to an existing folder on a second disk. Keep that disk available during backups. Restoring targets an empty directory. Backups are independent copies. Manage their retention on the backup disk.

## Headless or SSH access

On a Debian-family owner-managed machine, install the same package. Replace the example address with an address on that machine.

```sh
total-recall-desk host --bind 192.168.1.20 --port 8789 --web-port 8790
```

The host prints a private phone pairing code and a one-time browser sign-in link. The phone receiver uses TLS. The browser site binds only to 127.0.0.1. For remote browsing, forward the browser port over SSH and open the freshly printed URL on the computer running your browser.

```sh
ssh -L 8790:127.0.0.1:8790 your-user@your-server
# At the server shell
total-recall-desk open --web-port 8790 --print
```

The launch link lasts five minutes and can be used once. Its browser session lasts eight hours. Use the same remote and local port in the SSH forward. Keep the web server behind loopback.

```sh
total-recall-desk archive-status
total-recall-desk quota 500
total-recall-desk backup /media/your-user/backup/recall
total-recall-desk restore-backup /path/to/snapshot /path/to/empty-archive
```

The default archive lives under `~/.local/share/total-recall-listening`. Set `TOTAL_RECALL_ARCHIVE_HOME` to an absolute directory on your storage disk before starting the app or CLI to change it. Run the receiver under your own user account. It accepts uploads on the chosen private address. Configure the OS firewall only for the private network you intend to use.

Run `python3 -m unittest discover -s mint` from the source tree for the Mint tests.

Drag anywhere on the microphone panel, including a button, to move it. A stationary click activates the button. **More → Archive tools & phone receiver** raises the desktop archive. **Expand / Shrink** changes the panel size. **Show live words** controls the panel display. The journal setting independently controls saved text. The clock measures the current uninterrupted input stream. The Keep button shows the amount of recent audio available. If the recorder was already running during the package update, sign out and back in to load the new worker.

Open an archive item for a situation review based on its recognized speech, corrected text, writing or journal. **Analyze original recording** reads the selected audio, shows measured levels and offers timestamped speech cues that jump to playback. Refreshing the visible archive leaves playback and active forms alone.


In version 0.33 the compact overlay’s recording-status button opens the most recently filed laptop words. The overlay menu also offers **Read last saved words**. Inside a journal, **Earlier journal** and **Later journal** open adjacent entries in time order across the recorders visible in that archive.

In the overlay menu, **My words & phrases** remembers how you write a phrase the laptop repeatedly mishears. Enter the recognized phrase and the spelling you want. Replacements apply to live captions and new local journal entries, with the recognizer's original wording retained in each changed entry. Spoken commands continue to use the recognizer's own words. Personal spellings stay on this laptop.

## Sounds around you

While recording, **Sound · Possible** shows a match from the original microphone signal. The recognizer checks roughly one-second windows for patterns such as typing, speech, birds, music, and rain. It can suggest two overlapping patterns. A weaker match needs a second consecutive window. Silence or ambiguous audio can show **No clear sound match**. The label expires when fresh input stops. Sound recognition is on by default. Change it under **Settings → Capture**. With voice commands and spoken guidance enabled, say **“Lexi, what do you hear?”**

In the shared or local archive, open saved audio and select **Analyze original recording**. **Sound timeline** lists changes with playback buttons. A saved sound suggestion is searchable and stays separate from quoted speech. Each analysis covers up to ten minutes, with continuation for longer recordings. Original audio and journal transfer settings still control what reaches the shared archive. Phone and laptop classify locally, including offline. The amd64 installation bundles Google’s YAMNet model and LiteRT for Python 3.11–3.13.
