import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import laptop_capture as cap
import recall_desk as desk


class DesktopJournalTest(unittest.TestCase):
    def test_utterances_survive_restart_and_retries_and_arrive_in_shared_journal(self):
        with tempfile.TemporaryDirectory() as temp:
            local=Path(temp)/"local";remote=Path(temp)/"host";local.mkdir();remote.mkdir()
            with patch.multiple(desk,ROOT=remote,DB=remote/"clips.sqlite3"):
                hosted=desk.archive()
            with patch.multiple(desk,ROOT=local,DB=local/"clips.sqlite3"):
                arc=desk.archive()
                root=cap.folder(arc)
                self.assertTrue(cap.config(root)["journal"])
                cap.write(root/"settings.json",{"journal":False})
                self.assertFalse(cap.config(root)["journal"])
                clock=cap.storage.now()
                writer=cap.JournalSpool(root,6)
                original=(b"\x10\x10"*16000)*2
                writer.add(original,clock,False)
                writer.add(bytes(32000),clock+2000,False)
                spool=next(root.glob("dict6-*.pcm"))
                self.assertEqual(spool.read_bytes(),original+bytes(32000))
                def decode(stream,gain):
                    self.assertEqual(gain,6)
                    self.assertEqual(stream.read(),spool.read_bytes())
                    return [(0.5,"the sun shines in")]
                with patch.object(cap.desk_speech,"transcribe",side_effect=decode) as decoder:
                    with patch.object(cap,"stage_record",side_effect=OSError("transfer interrupted")):
                        with self.assertRaises(OSError):cap.file_journal(arc,spool,clock,3000,6)
                    self.assertTrue(spool.with_suffix(".journal-ready").exists())
                    cap.file_journal(arc,spool,clock,3000,6)
                    cap.finish(arc,spool,cap.BASE)
                    self.assertEqual(decoder.call_count,1)
                cap.write(root/"shared-scope.json","journals")
                cap.write(root/"pair.json","test token")
                done=threading.Event()
                def transfer(token,method,route,body,headers):
                    self.assertEqual(token,"test token")
                    if method=="POST": return hosted.begin(json.loads(body))
                    return hosted.put(route.split("/")[-1],int(headers["X-Upload-Offset"]),body)
                # An audio segment and a phone journal already reside on their respective devices.
                audio=root/f"segment-{clock}-abcdef.pcm"
                audio.write_bytes(original)
                cap.finish(arc,audio,cap.BASE)
                phone=root/"phone.txt";phone.write_text("2026-01-01 12:00:00 Z  my phone remembers\n")
                cap.stage_record(hosted,"phone-line",phone,dict(id="phone-line",kind="journal",sessionId="phone",title="Phone journal",startedAtMillis=clock,durationMs=2000,bytes=phone.stat().st_size,sha256=cap.storage.sha_file(phone),mediaType="text/plain"))
                with patch.object(cap,"http",side_effect=transfer):
                    # Run one complete upload, then exit when the queue is empty.
                    done.wait=lambda timeout:done.set()
                    cap.push(arc,root,done)
                with hosted.connection() as db:
                    rows=db.execute("SELECT id,journal FROM archive_uploads ORDER BY id").fetchall()
                self.assertEqual(len(rows),2)
                self.assertIn("the sun shines in",rows[0]["journal"])
                self.assertEqual(rows[1]["id"],"phone-line")
                self.assertTrue(cap.read(root/"shared-journal-receipt.json",{})["id"].startswith("laptop-journal-"))
                self.assertFalse(spool.exists())
