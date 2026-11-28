from array import array
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import laptop_capture as cap
import mic_input as mic
import recall_desk as desk


class MicrophoneTests(unittest.TestCase):
    def test_input_ids_and_source_enumeration(self):
        data=json.dumps([{"name":"alsa_input.usb-1","description":"USB microphone"},
                         {"name":"alsa_output.analog.monitor","description":"Playback"}])
        with patch.object(subprocess,"check_output",return_value=data):
            self.assertEqual(mic.device_list("pulse"),{"alsa_input.usb-1":"USB microphone"})
        with patch.object(subprocess,"check_output",return_value="null\ndefault\nplughw:CARD=USB,DEV=0\n  USB Audio\n"):
            self.assertEqual(list(mic.device_list("alsa")),["plughw:CARD=USB,DEV=0"])
        self.assertEqual(mic.arguments("pulse","alsa_input.usb-1"),["-f","pulse","-i","alsa_input.usb-1"])
        with self.assertRaises(ValueError):mic.arguments("alsa","a\nb")

    def test_raw_levels_clipping_and_quiet_duration(self):
        levels=mic.LevelWindow()
        for _ in range(3):
            levels.add(bytes(32000))
            result=levels.snapshot()
        self.assertIsNone(result["peakDb"])
        self.assertIn("Very low input",result["inputHint"])
        levels.add(array("h",[32767,-32768]*8).tobytes())
        result=levels.snapshot()
        self.assertEqual(result["clippedSamples"],16)
        self.assertEqual(result["quietMs"],0)
        self.assertIn("clipping",result["inputHint"])

    def test_ffmpeg_tone_check_and_recognition_gain(self):
        check=mic.microphone_check(["-f","lavfi","-i","sine=frequency=440:sample_rate=16000"])
        self.assertAlmostEqual(check["peakDb"],-18.1,delta=.2)
        self.assertAlmostEqual(check["rmsDb"],-21.1,delta=.2)
        raw=array("h",[10000,-32768]).tobytes()
        self.assertIs(mic.recognition_pcm(raw,0),raw)
        self.assertEqual(list(array("h",mic.recognition_pcm(raw,12))),[32767,-32768])

    def test_capture_recognition_gain_keeps_original_and_flushes_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp)
            with patch.multiple(desk,ROOT=base,DB=base/"clips.sqlite3"):
                arc=desk.archive();root=cap.folder(arc)
                cfg=dict(cap.BASE,commands=False,creativeCatches=True,recognitionGain=12)
                cap.write(root/"settings.json",cfg);(root/"enabled").touch()
                original=array("h",[800,-1000]*10000).tobytes()
                tone=base/"source.raw";tone.write_bytes(original)
                boosted=[];callback=[]
                class Decoder:
                    state={"lines":[],"partial":"","engine":"Test"}
                    def __init__(self,heard):callback.append(heard)
                    def send(self,_position,pcm):
                        boosted.extend(pcm)
                        callback[0]("I just coined a word")
                        callback[0]("Hey Recall pause recording")
                    def close(self):pass
                with patch.object(cap.desk_speech,"LiveStream",Decoder):
                    with self.assertRaisesRegex(OSError,"unavailable"):
                        cap.capture(arc,root,cfg,["-re","-f","s16le","-ar","16000","-ac","1","-i",str(tone)])
                self.assertTrue((root/"enabled").exists())
                self.assertEqual(bytes(boosted),mic.recognition_pcm(original,12))
                self.assertEqual(next(root.glob("segment-*.pcm")).read_bytes(),original)
                candidates=list(root.glob("autoword-*.pcm"))
                self.assertEqual(len(candidates),1)
                self.assertEqual(candidates[0].read_bytes(),original)
                cap.finish(arc,candidates[0],cfg)
                self.assertFalse((root/"last-keep.json").exists())
                self.assertEqual(cap.read(root/"last-candidate.json",{})["title"],"Possible coined word")
                status=cap.read(root/"status.json",{})
                self.assertEqual(status["recognitionGain"],12)
                self.assertAlmostEqual(status["peakDb"],-30.3,delta=.1)

    def test_hotkey_cli_never_starts_recorder(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp);root=base/"laptop-recorder";root.mkdir()
            env=dict(os.environ,TOTAL_RECALL_ARCHIVE_HOME=temp)
            cmd=[sys.executable,"-I",desk.__file__,"keep","coinage"]
            inactive=subprocess.run(cmd,env=env,capture_output=True,text=True)
            self.assertNotEqual(inactive.returncode,0)
            self.assertFalse((root/"enabled").exists())
            (root/"enabled").touch()
            cap.write(root/"status.json",dict(state="Recording",at=cap.storage.now(),stream="s"))
            active=subprocess.run(cmd,env=env,capture_output=True,text=True,check=True)
            self.assertIn("requested",active.stdout)
            self.assertEqual(cap.read(next(root.glob("*.request")),{}).get("kind"),"coinage")
