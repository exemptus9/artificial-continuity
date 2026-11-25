# Bring selected moments to Mint

After installing the desktop package, open **Total Recall Desk** from the Mint applications menu.

**Pair over Wi-Fi.** On Mint click **Pair phone**, choose the laptop’s private Wi-Fi address and click **Start receiver and copy code**. On your Galaxy open Mint pairing inside Total Recall and scan the QR (or paste its private code). Both devices must be on a network where the phone can reach TCP port 8789 on that laptop. Keep the desk window open while receiving. Android pins the laptop’s TLS certificate and sends each selected capture ZIP with its private pairing credential. The receipt identifies the stored capture and verifies its audio and transfer checksums. To stop, click **Phone connection → Stop receiver**. On the same network subsequent sessions reuse the same pairing credentials. For hands-free startup tick **Open desk and start receiver when I log into Mint**. This opens the Desk and receiver at your next login. Untick it in **Phone connection** to remove startup. The saved address must be assigned to the laptop; pair with its new address after a network change.

**Or use a file.** On the phone, select the moments you want to transfer and export their ZIP. Copy it to Mint with USB or your chosen private file transfer. The ZIP contains playable recordings and transcripts. You control which ZIP leaves the phone.

When using the standalone source, open Mint Terminal in this folder and run:

```bash
python3 recall_desk.py
```

1. Click **Import capture ZIP**. The desktop verifies the audio checksums and commits the whole import together.
2. Search by a distinctive word, title, or category. Select the result and click **Play original**.
3. Click **Connect Lexi** once to enable **totalrecall__find_capture** for the configured Lexidaimon agent. In a new Lexi chat say “Use my Total Recall captures to find the word I coined about believing exaggerations literally.” Lexi can retrieve captured text and metadata. A model provider used by Lexi can receive the matching passages when you ask. Original audio stays in the private Mint archive.
4. **Export text for Continuity** writes the selected timestamp, provenance, checksum and transcript to a text file. Import this file through Continuity’s existing text intake. The private audio-bearing ZIP is your portable audio archive.

The GUI reads original WAVs with ALSA or PulseAudio. Optional pairing uses OpenSSL and can display the QR with libqrencode4. Mint needs Python 3.11+ for original-audio storage. The desktop uses Python’s standard Tk bindings; if unavailable, these terminal commands also work:

```bash
python3 recall_desk.py import ~/Downloads/your-phone-export.zip
python3 recall_desk.py search "my coined word"
python3 recall_desk.py connect
```

Imports land in your user-private `~/.local/share/total-recall-listening/clips.sqlite3`. The database is plaintext within your user account. A repeated import skips existing recordings. Newly observed metadata versions are retained with the original audio.
