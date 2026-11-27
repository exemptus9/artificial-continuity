import io
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

import laptop_capture as laptop
import recall_archive as storage
import recall_desk as desk


class LaptopKeeps(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        base = Path(directory.name)
        paths = patch.multiple(desk, ROOT=base, DB=base/"recall.sqlite3")
        paths.start()
        self.addCleanup(paths.stop)
        self.arc = desk.archive()
        self.root = laptop.folder(self.arc)
        self.end = storage.now()
        self.stream = "laptop-stream"
        (self.root/"enabled").touch()
        laptop.write(self.root/"status.json", dict(at=self.end-1, stream=self.stream, state="Recording"))

    def test_click_boundary_two_distinct_keeps_and_receipt(self):
        # A new sound after the click must not turn up in either bookmark.
        first_second = b"\x13\x00" * laptop.RATE
        next_second = b"\x63\x00" * laptop.RATE
        with patch.object(storage, "now", return_value=self.end):
            line = laptop.request_keep(self.root, "line")
            coinage = laptop.request_keep(self.root, "coinage")
        laptop.collect_keeps(self.root, self.stream, first_second+next_second, self.end+1000, 1)
        keeps = list(self.root.glob("*.pcm"))
        self.assertEqual(len(keeps), 2)
        for path in keeps:
            self.assertEqual(path.read_bytes(), first_second)
            laptop.finish(self.arc, path, laptop.BASE)
        self.assertFalse(list(self.root.glob("*.request")))
        for identifier,category in ((line,"Poem line"),(coinage,"Forge Lexicon")):
            uid = f"desk-keep-{self.end-1000}-{identifier}"
            self.assertEqual(desk.detail(uid)["category"], category)
            with self.arc.connection() as db:
                audio = db.execute("SELECT audio FROM clips WHERE id=?",(uid,)).fetchone()[0]
            with wave.open(io.BytesIO(audio)) as original:
                self.assertEqual(original.readframes(original.getnframes()), first_second)
        receipt = laptop.read(self.root/"last-keep.json", {})
        self.assertIn(receipt["id"].split("-")[-1], (line, coinage))
        self.assertEqual(receipt["duration"], 1000)
        segment = self.root/f"segment-{self.end}-123abc.pcm"
        segment.write_bytes(next_second)
        laptop.finish(self.arc, segment, laptop.BASE)
        self.assertEqual(receipt, laptop.read(self.root/"last-keep.json", {}))

    def test_restart_and_staleness_cannot_mark_different_audio(self):
        laptop.request_keep(self.root, "resume")
        laptop.collect_keeps(self.root, "new-stream", bytes(32000), self.end, 15)
        self.assertFalse(list(self.root.glob("*.pcm")))
        self.assertIn("changed", laptop.read(self.root/"missed-keep.json", {})["text"])
        with patch.object(storage, "now", return_value=self.end+8000):
            with self.assertRaises(ValueError):
                laptop.request_keep(self.root,"line")
        (self.root/"enabled").unlink()
        with self.assertRaises(ValueError):
            laptop.request_keep(self.root,"line")

    def test_retry_with_same_timestamp_and_bytes_is_idempotent(self):
        source = self.root/f"resume-{self.end-1000}-a1b2c3d4.pcm"
        for _ in range(2):
            source.write_bytes(bytes(laptop.BYTES_PER_SECOND))
            laptop.finish(self.arc, source, laptop.BASE)
        self.assertEqual(self.arc.status()["counts"]["clip"], 1)
        self.assertEqual(laptop.read(self.root/"last-keep.json", {})["title"], "Pick up here")

    def test_spoken_receipt_falls_back_to_tone_and_quiet_is_silent(self):
        with patch.object(laptop.subprocess, "Popen") as spawn, patch.object(laptop.shutil, "which", return_value=None):
            laptop.sound_cue(self.root, "line")
            self.assertFalse(spawn.called)
            laptop.write(self.root/"sound.json","Wake and spoken saves")
            laptop.sound_cue(self.root, "resume")
            self.assertEqual(spawn.call_args.args[0][0], "ffplay")
            with patch.object(laptop.shutil, "which", return_value="/usr/bin/espeak-ng"):
                laptop.sound_cue(self.root, "coinage")
                self.assertEqual(spawn.call_args.args[0], ["/usr/bin/espeak-ng","Word captured"])
            laptop.write(self.root/"sound.json","Visual only")
            spawn.reset_mock()
            laptop.sound_cue(self.root,"wake")
            self.assertFalse(spawn.called)
