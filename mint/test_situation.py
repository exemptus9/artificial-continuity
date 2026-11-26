import io
import unittest
from types import SimpleNamespace
from situation import context_for,scan


class SituationTest(unittest.TestCase):
    def test_correction_and_note_stay_distinct(self):
        result=context_for(dict(kind="clip",transcript="I need to go.",correction="We decided to stay.",note="What if we write a poem?",review={}))
        self.assertEqual([r["source"] for r in result],["Your correction","Your note"])
        self.assertEqual(result[0]["items"][0]["quote"],"We decided to stay.")
        self.assertEqual(result[0]["items"][0]["label"],"Possible decision")
        self.assertEqual({r["label"] for r in result[1]["items"]},{"Question","Creative idea"})

    def test_journal_timestamps_and_status(self):
        journal="2026-11-26 14:01:00 Z  [capture status] We need to reconnect\n2026-11-26 14:02:00 Z  Waiting for the proof."
        row=context_for(dict(kind="journal",text=journal))[0]["items"]
        self.assertEqual([(r["stamp"],r["label"]) for r in row],[("14:02:00","Possible obstacle")])
        self.assertEqual(context_for({"kind":"segment"}),[])

    def test_quiet_recording_and_partial_frame(self):
        decoder=SimpleNamespace(start_utt=lambda:None,end_utt=lambda:None,hyp=lambda:None,process_raw=lambda *_:None)
        result=scan(io.BytesIO(bytes(33000)),decoder,600)
        self.assertEqual((result["start"],result["abovePercent"],result["peakDbfs"],result["cues"]),(600,0,None,[]))
        self.assertEqual(result["seconds"],16500/16000)
