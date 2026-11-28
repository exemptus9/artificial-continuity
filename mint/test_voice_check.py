from array import array
import os
from pathlib import Path
import threading
import time
import tempfile
import unittest
from unittest.mock import patch

import laptop_capture as cap
import live_words
import mic_input
import recall_desk as desk
import voice_check as check


class VoiceCheckTests(unittest.TestCase):
    def test_normalized_word_alignment_retains_misses_extras_and_order(self):
        self.assertEqual(check.compare("I wrote a line about the rain","I WROTE a lime about the rain!"),
                         (6,7,["line"],["lime"]))
        self.assertEqual(check.compare("blue silver blue","blue blue silver"),(2,3,["blue"],["blue"]))

    def test_stopped_ffmpeg_check_leaves_no_recording_or_archive_and_cancel_releases_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            src=["-f","lavfi","-i","sine=frequency=400:sample_rate=16000"]
            sample=check.capture_sample(root,src,False,threading.Event(),lambda _:None)
            self.assertEqual(len(sample),check.TARGET)
            levels=mic_input.LevelWindow();levels.add(sample)
            self.assertAlmostEqual(levels.snapshot()["peakDb"],-18.1,delta=.2)
            stop=threading.Event()
            timer=threading.Timer(.15,stop.set);timer.start()
            t=time.monotonic()
            with self.assertRaises(InterruptedError):
                check.capture_sample(root,["-re",*src],False,stop,lambda _:None)
            timer.join()
            self.assertLess(time.monotonic()-t,1.5)
            self.assertEqual([p.name for p in root.iterdir()],["voice-check.lock"])

    def test_live_check_reuses_unbroken_raw_mic_stream(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory)
            with patch.multiple(desk,ROOT=base,DB=base/"clips.sqlite3"):
                arc=desk.archive();root=cap.folder(arc)
                cfg=dict(cap.BASE,commands=False,recognitionGain=12)
                cap.write(root/"settings.json",cfg);(root/"enabled").touch()
                original=array("h",[613,-1227]*64000).tobytes()
                source=base/"raw-source";source.write_bytes(original)
                capture_error=[]
                def run():
                    try:cap.capture(arc,root,cfg,["-re","-f","s16le","-ar","16000","-ac","1","-i",str(source)])
                    except OSError as exc:capture_error.append(str(exc))
                thread=threading.Thread(target=run);thread.start()
                try:
                    limit=time.monotonic()+3
                    while not cap.read(root/"status.json",{}).get("practiceStream"):
                        self.assertLess(time.monotonic(),limit);time.sleep(.05)
                    # The active path must never call ffmpeg for a second microphone.
                    with patch.object(check.subprocess,"Popen",side_effect=AssertionError("Second microphone opened")):
                        sample=check.capture_sample(root,[],True,threading.Event(),lambda _:None)
                    self.assertEqual(sample,original[:check.TARGET])
                    self.assertTrue((root/"enabled").exists())
                    self.assertFalse((root/check.FIFO).exists())
                finally:(root/"enabled").unlink();thread.join(3)
                self.assertFalse(thread.is_alive());self.assertFalse(capture_error)
                self.assertTrue(next(root.glob("segment-*.pcm")).read_bytes().startswith(original[:check.TARGET]))

    def test_live_gap_is_reported_without_stitching(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/"enabled").touch()
            def send():
                limit=time.monotonic()+2
                while not (root/check.FIFO).exists():
                    if time.monotonic()>limit:return
                    time.sleep(.02)
                with (root/check.FIFO).open("wb",buffering=0) as out:
                    out.write(live_words.HEADER.pack(100,80)+bytes(80))
                    out.write(live_words.HEADER.pack(900,80)+bytes(80))
            writer=threading.Thread(target=send);writer.start()
            with self.assertRaisesRegex(OSError,"gap"):
                check.capture_sample(root,[],True,threading.Event(),lambda _:None)
            writer.join();self.assertFalse((root/check.FIFO).exists())
