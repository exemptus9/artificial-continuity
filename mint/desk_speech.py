"""Offline streaming dictation for the desktop recorder and durable PCM spool."""
import ctypes
import json
import os
from pathlib import Path
import queue
import threading
import time
from array import array

import live_words
import speech_context

ASSETS = Path(__file__).resolve().parent / "dictation"
_model = None
_lock = threading.Lock()


def native_model():
    global _model
    with _lock:
        if _model is not None:
            return _model
        lib = ctypes.CDLL(str(ASSETS / "libvosk.so"))
        pointer, string, integer = ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int
        for name, returns, args in (
            ("set_log_level", None, [integer]), ("model_new", pointer, [string]),
            ("recognizer_new", pointer, [pointer, ctypes.c_float]),
            ("recognizer_accept_waveform", integer, [pointer, string, integer]),
            ("recognizer_set_words", None, [pointer, integer]),
            ("recognizer_result", string, [pointer]), ("recognizer_partial_result", string, [pointer]),
            ("recognizer_final_result", string, [pointer]), ("recognizer_free", None, [pointer])):
            fn = getattr(lib, "vosk_" + name)
            fn.argtypes, fn.restype = args, returns
        lib.vosk_set_log_level(-1)
        model = lib.vosk_model_new(os.fsencode(ASSETS / "model"))
        if not model:
            raise RuntimeError("The installed English model could not be opened")
        _model = lib, model
        return _model


class Dictation:
    def __init__(self):
        self.ptr = None
        self.partial = ""
        self.consumed = self.begin = self.quiet = self.preview_bytes = 0
        if ASSETS.is_dir():
            self.lib, model = native_model()
            self.ptr = self.lib.vosk_recognizer_new(model, 16000.0)
            if not self.ptr:
                raise RuntimeError("Offline recognizer could not start")
            self.lib.vosk_recognizer_set_words(self.ptr, 1)
            self.engine = "Vosk · English"
        else:
            import situation
            self.decoder = situation.new_decoder()
            self.decoder.start_utt()
            self.engine = "PocketSphinx · English"

    def result(self, data):
        words = data.get("text", "").strip()
        timing = data.get("result") or []
        start = timing[0]["start"] if timing else self.begin / 32000
        self.begin = self.consumed
        self.partial = ""
        return [(start, words)] if words else []

    def feed(self, pcm):
        self.consumed += len(pcm)
        self.preview_bytes += len(pcm)
        if self.ptr:
            accepted = self.lib.vosk_recognizer_accept_waveform(self.ptr, pcm, len(pcm))
            if accepted < 0:
                raise RuntimeError("Offline decoding failed")
            if accepted:
                return self.result(json.loads(self.lib.vosk_recognizer_result(self.ptr)))
            if self.preview_bytes >= 4800:
                self.partial = json.loads(self.lib.vosk_recognizer_partial_result(self.ptr)).get("partial", "")
        else:
            self.decoder.process_raw(pcm, False, False)
            samples = array("h", pcm)[::8]
            soft = sum(s*s for s in samples) < len(samples)*180**2
            self.quiet = self.quiet + len(pcm) if soft else 0
            if self.quiet >= 22400 or self.consumed-self.begin >= 480000:
                result = self.finish()
                self.decoder.start_utt()
                self.quiet = 0
                return result
            if self.preview_bytes >= 4800:
                hyp = self.decoder.hyp()
                self.partial = hyp.hypstr.strip() if hyp else ""
        if self.preview_bytes >= 4800:
            self.preview_bytes = 0
        return []

    def finish(self):
        if self.ptr:
            return self.result(json.loads(self.lib.vosk_recognizer_final_result(self.ptr)))
        self.decoder.end_utt()
        hyp = self.decoder.hyp()
        return self.result({"text": hyp.hypstr if hyp else ""})

    def close(self):
        if self.ptr:
            self.lib.vosk_recognizer_free(self.ptr)
            self.ptr = None


def transcribe(stream, gain=0):
    import mic_input
    decoder = Dictation()
    try:
        while block := stream.read(4096):
            yield from decoder.feed(mic_input.recognition_pcm(block, gain))
        yield from decoder.finish()
    finally:
        decoder.close()


class LiveStream:
    """The capture thread writes originals regardless of live decoding speed."""
    def __init__(self, on_final):
        self.audio = queue.Queue(maxsize=192)
        self.on_final = on_final
        self.done = threading.Event()
        self.state = {"engine":"Loading offline English…", "partial":"", "lines":[], "gaps":0}
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def send(self, position, pcm):
        try:
            self.audio.put_nowait((time.monotonic(), position, pcm))
        except queue.Full:
            # Drain stale preview audio. The durable journal reads the complete PCM spool.
            while True:
                try: self.audio.get_nowait()
                except queue.Empty: break
            self.audio.put_nowait((time.monotonic(), position, pcm))

    def run(self):
        decoder = None
        expected = None
        command_audio = bytearray()
        command_decoder = command_search = None
        display_words = getattr(self.on_final, "display_words", speech_context.correct)
        command_available = getattr(self.on_final, "commands_enabled", False)
        try:
            decoder = Dictation()
            self.state = {**self.state, "engine":decoder.engine}
            while not self.done.is_set():
                try: arrival, pos, pcm = self.audio.get(timeout=.2)
                except queue.Empty: continue
                if time.monotonic()-arrival > 2.5:
                    # An old caption or voice command is misleading. The journal still has these samples.
                    if expected is None: expected=pos
                    continue
                if expected is not None and pos != expected:
                    decoder.close()
                    decoder = Dictation()
                    command_audio.clear()
                    self.state = {**self.state, "partial":"", "gaps":self.state["gaps"]+1}
                expected = pos+len(pcm)
                if command_available:
                    command_audio.extend(pcm)
                    del command_audio[:-192000]
                finals = decoder.feed(pcm)
                lines = self.state["lines"]
                for _, words in finals:
                    lines = (lines+[display_words(words)])[-3:]
                    command = words
                    window = getattr(self.on_final, "command_open", lambda:False)()
                    near_wake = words.lower().startswith(("lex","alex","hey","total recall"))
                    if command_available and (window or near_wake):
                        try:
                            if command_decoder is None:
                                import situation
                                command_decoder = situation.new_decoder()
                                command_search = live_words.configure_wake(command_decoder)
                            command = live_words.checked_wake(command_decoder, command_search, command_audio, words, window)
                        except (ImportError, AttributeError, RuntimeError, ValueError, OSError):
                            command_available = False
                    command_audio.clear()
                    if not self.done.is_set():
                        self.on_final(command)
                self.state = {**self.state, "lines":lines, "partial":display_words(decoder.partial)}
        except (ImportError, OSError, RuntimeError, ValueError) as exc:
            self.state = {**self.state, "error":str(exc)[:100], "partial":""}
        finally:
            if decoder: decoder.close()

    def close(self):
        self.done.set()
        self.thread.join(timeout=5)
