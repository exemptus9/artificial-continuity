# Total Recall Desk for Linux Mint

Install the Debian package, open **Total Recall Desk**, and choose **Private archive**. The archive opens in your browser on this machine. Imported clips from earlier versions appear automatically. **Open cloud archive** opens the separately hosted owner-authenticated site. Local and hosted archives show the items sent to their respective destinations. For hosted sign-in, phone pairing and transfer allowances, see the [hosted archive guide](CLOUD.md).

## Connect the phone

1. Connect both devices to your private network. Open **Pair phone** in Desk. Pick the computer's private IPv4 address and choose **Start receiver and copy code**.
2. Open the phone's Mint connection screen and scan the pairing QR or paste the code. Treat this code as a password. Trust comes from the pinned certificate and secret in the code.
3. Choose the media you want to transfer on the phone. Selected original WAV clips, optional full-session AAC segments and optional text journals are separate choices. Sending resumes when the host becomes reachable. Each completed upload returns its ID, byte count and SHA-256.
4. Select **Open desk and start receiver when I log into Mint** if you want automatic receiving. The computer must be awake, reachable and have available storage. The phone and host can also use an owner-managed private VPN, including a 100.64.0.0/10 mesh address.

In **Private archive**, search for an item, play the original or download it. Browser audio supports seeking. **Activity** shows the busiest recorded day and counts of saved clips by label. Choose **Review latest** to open a candidate from that group. Existing note, correction, original transcript, feedback and excerpt-source fields are preserved. **Import capture ZIP** accepts Android selected-clip exports. **Connect Lexi** adds a read-only selected-clip search tool to the configured OpenClaw agent `lexidaimon`. Start a new chat and ask Lexi to find a saved moment. Full-session journals and continuous recordings stay outside that tool.

## Laptop microphone and floating panel

Open **Laptop microphone** in Total Recall Desk. Drag the title bar to move the panel. **Start recording** uses the default Mint microphone; the input meter shows the measured sound level. **Keep moment** saves the available last 15, 30 or 60 seconds as a separate bookmark in Review. **Browse & play** opens all local recordings. **Stop recording** releases the microphone and finalizes the current segment. Closing or hiding the panel leaves a running recorder active. Reopen it from Desk.

**Settings** controls the sound system (PulseAudio/PipeWire or ALSA), AAC quality, segment length, bookmark length, compact mode, always-on-top and optional recording at login. The recorder runs under the signed-in user. A sleeping or powered-off laptop has no microphone coverage. Laptop clips and full-audio segments are saved on this computer independently of the phone. Automatic speech recognition and automatic creative highlights are provided by the phone. Use **Keep moment** on the laptop for passages to review.

For a second copy, enable **Send laptop audio and bookmarks to my hosted archive** and paste a hosted pairing code from the website. Completed laptop recordings are uploaded in order, with byte checks, resumable transfers and a matching SHA-256 receipt. Local originals stay in the local archive. Pending files wait for storage or connectivity. Desk's existing storage quota and free-space reserve apply. Overlapping device recordings retain their separate originals.

## Review, file and delete

Open **Review** in the private website or local archive. Listen inline and click a category to file the item. Use **Collection** to open a category, see all filed items, or revisit dismissed clips. **Inbox settings** lets you rename or add buttons and set the page size and order. Changing the button list preserves existing collections. Within an item, **Classify or edit writing** supports multiple categories, draft text, a corrected word and its recognized spelling. These are private archive collections. Copy a draft to take it to another writing app.

**Dismiss** leaves the original in Archive. To remove an item, open it, expand **Delete from this archive**, check the confirmation and click **Delete this item**. The operation removes the active original, its text and its filing from this archive. Other devices, independent excerpts, copies you exported and previous backups can retain material. The archive rejects repeat uploads of the same deleted item ID.

## Find a moment

From Overview, search for part of a line or choose **Coined words**, **Poetry & freestyle**, or **Yesterday**. In Archive, combine recognized words, notes and corrections with a clip label and the local date the item started. Results show a passage around the match when available.

Open a timestamped journal to find a line and select **Find audio here**. Choose one of the recordings covering the recognition time and press play. Playback starts with up to three seconds of lead-in. **Back to journal** restores the search and page. **Copy to writing notes** on a selected clip copies its text, correction and note for use in another app.

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
