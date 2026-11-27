from array import array
import os
from pathlib import Path
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import laptop_capture as capture
import live_words
import recall_desk as desk
import voice_feedback as feedback


class VoiceFeedbackTests(unittest.TestCase):
    def test_actionable_failures_coalesce_and_preview_is_private(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t)
            capture.write(root/"sound.json","Visual only")
            for _ in range(3):feedback.emit(root,"storage")
            feedback.emit(root,"no-passage")
            for _ in range(20):feedback.emit(root,"wake")
            self.assertEqual(len(feedback.history(root)),12)
            feedback.emit(root,"pending")
            feedback.emit(root,"pending")
            self.assertEqual(sum(e["event"]=="pending" for e in feedback.history(root)),1)
            before=feedback.history(root)
            self.assertEqual(feedback.emit(root,"coinage","Sample",preview=("Visual only",135,35)),"Visual feedback only")
            self.assertEqual(feedback.history(root),before)
            capture.write(root/"speech-options.json",{"rate":10000,"volume":-4})
            self.assertEqual(feedback.preferences(root),(175,100))

    def test_grammar_rescue_preserves_unrelated_or_negative_speech(self):
        class Decoder:
            def __init__(self,words,score,prob):
                self.result=SimpleNamespace(hypstr=words,best_score=score,prob=prob)
            def set_search(self,name):self.search=name
            def start_utt(self):pass
            def process_raw(self,*args):pass
            def end_utt(self):pass
            def hyp(self):return self.result
        def parsed(candidate,heard,score=.7):
            decoder=Decoder(candidate,score,.45)
            return live_words.checked_wake(decoder,"ordinary",bytes(100),heard,True)
        self.assertEqual(parsed("save a word","good lord"),"save a word")
        self.assertEqual(parsed("keep the last minute","keep the curtains closed",.48),"keep the curtains closed")
        self.assertEqual(parsed("pause recording","never pause recording"),"never pause recording")

    def test_serial_speech_guard_and_deferred_confirmation(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t)
            fake=root/"local-voice"
            fake.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\" >> \"$RECALL_SPEECH_TEST\"\nsleep .25\n")
            fake.chmod(0o700)
            capture.write(root/"sound.json","Spoken guidance and saves")
            capture.write(root/"speech-options.json",dict(rate=135,volume=35))
            with patch.dict(os.environ,RECALL_SPEECH_TEST=str(root/"output")),patch.object(feedback.shutil,"which",return_value=str(fake)):
                feedback.emit(root,"help")
                self.assertTrue(feedback.guarded(root))
                feedback.emit(root,"line")
                deadline=time.monotonic()+3
                while time.monotonic()<deadline:
                    if (root/"output").exists() and "Line captured" in (root/"output").read_text() and not feedback.guarded(root):break
                    time.sleep(.05)
                self.assertFalse(feedback.guarded(root))
                played=(root/"output").read_text()
                self.assertIn("-s\n135\n-a\n35",played)
                self.assertIn("After the wake chime",played)
                self.assertIn("Line captured",played)

    def test_typed_voice_keeps_and_prompt_exclusion_preserve_raw_audio(self):
        with tempfile.TemporaryDirectory() as t:
            base=Path(t)
            with patch.multiple(desk,ROOT=base,DB=base/"clips.sqlite3"):
                arc=desk.archive();root=capture.folder(arc);(root/"enabled").touch()
                capture.write(root/"sound.json","Visual only")
                pcm=array("h",[1300,-600]*32000).tobytes()
                audio=base/"source.pcm";audio.write_bytes(pcm)
                callbacks=[];positions=[];commands=[]
                class Decoder:
                    def __init__(self,root,name,on_final):callbacks.append(on_final)
                    def close(self):pass
                class Feed:
                    def __init__(self,root,name="captions"):self.name=name
                    def send(self,position,block):
                        if self.name!="voice-control.fifo":return
                        positions.append((position,len(block)))
                        if not commands:
                            callbacks[0]("Lexi save a line")
                            callbacks[0]("finish passage")
                            # Reproduce our own prompt reaching the microphone. The raw PCM continues.
                            feedback.hold(root,.35)
                            callbacks[0]("Lexi pause recording")
                            commands.append("first")
                        elif len(commands)==1:
                            callbacks[0]("Lexi mark my place");commands.append("second")
                    def close(self):pass
                with patch.object(capture.live_words,"LiveWords",Decoder),patch.object(capture.live_words,"WordFeed",Feed):
                    with self.assertRaisesRegex(OSError,"unavailable"):
                        capture.capture(arc,root,capture.BASE,["-re","-f","s16le","-ar","16000","-ac","1","-i",str(audio)])
                self.assertTrue((root/"enabled").exists())
                self.assertEqual(next(root.glob("segment-*.pcm")).read_bytes(),pcm)
                self.assertTrue(any(a+n!=b for (a,n),(b,_) in zip(positions,positions[1:])))
                for kind in ("line","resume"):
                    files=list(root.glob(kind+"-*.pcm"));self.assertEqual(len(files),1)
                    capture.finish(arc,files[0],capture.BASE)
                events=[e["event"] for e in feedback.history(root)]
                self.assertIn("no-passage",events)
                self.assertIn("line",events)
                self.assertIn("resume",events)
                self.assertNotIn("paused",events)

if __name__=="__main__":unittest.main()
