"""Local captions from the recorder's PCM stream. Speech remains in panel memory."""
import os
from pathlib import Path
import select
import stat
import struct
import threading
import time


HEADER = struct.Struct("!QI")
MODEL_DIR = Path("/usr/share/pocketsphinx/model/en-us")


class WordFeed:
    """Nonblocking audio tap. A closed or slow overlay never delays capture."""
    def __init__(self, root, name="live-words.fifo"):
        self.path = root / name
        self.fd = None
        self.retry = 0

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def send(self, position, pcm):
        if self.fd is None and time.monotonic() < self.retry:
            return
        try:
            if self.fd is None:
                self.fd = os.open(self.path, os.O_WRONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
                if not stat.S_ISFIFO(os.fstat(self.fd).st_mode):
                    raise OSError("Live words pipe unavailable")
            # Each framed write fits in Linux PIPE_BUF and succeeds or drops whole.
            for start in range(0, len(pcm), 4000):
                part = pcm[start:start+4000]
                os.write(self.fd, HEADER.pack(position+start, len(part)) + part)
        except BlockingIOError:
            pass
        except OSError:
            self.close()
            self.retry = time.monotonic() + .4


class LiveWords:
    def __init__(self, root, name="live-words.fifo", on_final=None):
        self.on_final=on_final
        self.path = root / name
        self.line = "Starting offline recognition…"
        self.done = threading.Event()
        self.path.unlink(missing_ok=True)
        os.mkfifo(self.path, 0o600)
        self.fd = os.open(self.path, os.O_RDWR | os.O_NONBLOCK)
        self.thread = threading.Thread(target=self.listen, daemon=True)
        self.thread.start()

    def close(self):
        self.done.set()
        self.path.unlink(missing_ok=True)
        self.line = ""
        self.thread.join(timeout=2)

    def packets(self):
        buf = bytearray()
        while not self.done.is_set():
            if not select.select([self.fd], [], [], .25)[0]:
                yield None, None
                continue
            buf.extend(os.read(self.fd, 65536))
            while len(buf) >= HEADER.size:
                pos, size = HEADER.unpack_from(buf)
                end = HEADER.size + size
                if len(buf) < end:
                    break
                yield pos, bytes(buf[HEADER.size:end])
                del buf[:end]

    def listen(self):
        from array import array
        try:
            from pocketsphinx import Decoder
            models = MODEL_DIR
            settings = Decoder.default_config()
            for key, name in (("-hmm", "en-us"), ("-lm", "en-us.lm.bin"), ("-dict", "cmudict-en-us.dict")):
                settings.set_string(key, str(models / name))
            settings.set_string("-logfn", os.devnull)
            decoder = Decoder(settings)
            self.line = "Listening for words…"
            decoder.start_utt()
            previous = None
            quiet = length = 0
            last_text = last_result = 0
            for position, pcm in self.packets():
                if pcm is None:
                    if time.monotonic() - last_text > 5:
                        self.line = "Waiting for microphone audio…"
                    continue
                if previous != position:
                    decoder.end_utt()
                    decoder.start_utt()
                    quiet = length = 0
                    self.line = "Listening for words…"
                previous = position + len(pcm)
                decoder.process_raw(pcm, False, False)
                length += len(pcm)
                samples = array("h", pcm)
                energy = sum(n*n for n in samples[::8]) / max(1, len(samples[::8]))
                quiet = quiet + len(pcm) if energy < 180**2 else 0
                now = time.monotonic()
                end = length >= 32000*15 or (length >= 32000 and quiet >= 22400)
                if end:
                    decoder.end_utt()
                if end or now - last_result >= .24:
                    hyp = decoder.hyp()
                    if hyp and hyp.hypstr.strip():
                        self.line = " ".join(hyp.hypstr.split()[-22:])
                        last_text = now
                        if end and self.on_final and not self.done.is_set():self.on_final(hyp.hypstr.strip())
                    elif now - last_text > 5:
                        self.line = "Listening for words…"
                    last_result = now
                if end:
                    decoder.start_utt()
                    quiet = length = 0
        except (ImportError, RuntimeError, OSError, ValueError) as exc:
            self.line = "Live words unavailable · " + (str(exc) or type(exc).__name__).splitlines()[0][:72]
        finally:
            os.close(self.fd)
