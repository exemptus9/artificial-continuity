import io
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import laptop_capture as laptop
import recall_archive
import recall_desk as desk
import recall_review as review
import recall_web as web
from test_cloud_host import TestS3
from cloud_media import CloudMedia

class OwnerWorkflow(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup)
        root=Path(tmp.name);paths=patch.multiple(desk,ROOT=root,DB=root/"clips.sqlite3")
        paths.start();self.addCleanup(paths.stop);self.arc=desk.archive();self.root=laptop.folder(self.arc)
    def stage(self,mark=False,stamp=None):
        uid=("mark" if mark else "segment")+"-"+str(stamp or recall_archive.now()-1000)+"-123abc"
        path=self.root/(uid+".pcm");path.write_bytes(b"\x00\x01"*16000)
        laptop.finish(self.arc,path,laptop.BASE)
        return ("desk-keep-" if mark else "laptop-")+uid.split("-",1)[1]
    def test_live_ffmpeg_capture_mark_archive_and_stop(self):
        (self.root/"enabled").touch()
        (self.root/"bookmark").touch()
        def end():
            limit=time.monotonic()+10
            while time.monotonic()<limit:
                if (self.root/"status.json").exists():time.sleep(.4);break
                time.sleep(.05)
            (self.root/"enabled").unlink(missing_ok=True)
        thread=threading.Thread(target=end);thread.start()
        laptop.capture(self.arc,self.root,laptop.BASE,["-re","-f","lavfi","-i","sine=frequency=550:sample_rate=16000"])
        thread.join();files=list(self.root.glob("*.pcm"));self.assertEqual(len(files),2)
        for file in files:laptop.finish(self.arc,file,laptop.BASE)
        self.assertEqual(self.arc.status()["counts"],dict(segment=1,journal=0,clip=1))
        self.assertEqual(review.inbox(self.arc,"inbox",0)["total"],1)
    def test_story_conflict_search_delete(self):
        uid=self.stage(True)
        fields=dict(kind="clip",id=uid,action="story",body="Working on the lighthouse illustration.",revision=0)
        self.assertEqual(review.classify(self.arc,fields)["revision"],1)
        with self.assertRaises(recall_archive.Failure):review.classify(self.arc,fields)
        item=web.lookup(self.arc,"clip",uid)
        self.assertEqual(item["story"]["body"],fields["body"])
        self.assertEqual(web.items(self.arc,"all","lighthouse",10,0)["total"],1)
        review.delete(self.arc,dict(kind="clip",id=uid,sha256=item["sha256"]))
        with self.arc.connection() as db:self.assertEqual(db.execute("SELECT count(*) FROM item_stories").fetchone()[0],0)

    def test_undo_restores_labels_and_refuses_stale_receipt(self):
        uid=self.stage(True)
        item=dict(kind="clip",id=uid)
        with self.arc.connection() as db:
            db.execute("UPDATE clips SET transcript=? WHERE id=?",("Synthetic starting line",uid))
        filed=review.classify(self.arc,dict(item,action="add",label="Poem draft"))
        self.assertEqual(filed["writing"],"Synthetic starting line")
        undone=review.classify(self.arc,dict(item,action="undo",before=filed["undo"]))
        self.assertEqual((undone["state"],undone["labels"],undone["writing"]),("inbox",[],""))
        with self.assertRaises(recall_archive.Failure):
            review.classify(self.arc,dict(item,action="undo",before=filed["undo"]))
        review.classify(self.arc,dict(item,action="add",label="Forge Lexicon"))
        dismissed=review.classify(self.arc,dict(item,action="dismiss"))
        restored=review.classify(self.arc,dict(item,action="undo",before=dismissed["undo"]))
        self.assertEqual((restored["state"],restored["labels"]),("filed",["Forge Lexicon"]))
        first=review.classify(self.arc,dict(item,action="add",label="To do"))
        review.classify(self.arc,dict(item,action="remove",label="To do"))
        with self.assertRaises(recall_archive.Failure):
            review.classify(self.arc,dict(item,action="undo",before=first["undo"]))
        with self.assertRaises(recall_archive.Failure):
            review.classify(self.arc,dict(item,action="undo",before={"state":"dismissed"}))
        latest=review.classify(self.arc,dict(item,action="add",label="Poem draft"))
        review.classify(self.arc,dict(item,action="edit",name="New name",writing="New draft",heard="one"))
        with self.assertRaises(recall_archive.Failure):
            review.classify(self.arc,dict(item,action="undo",before=latest["undo"]))
        self.assertEqual(web.lookup(self.arc,"clip",uid)["review"]["writing"],"New draft")

    def test_results_explain_owner_and_timed_audio_matches(self):
        uid=self.stage(True)
        item=dict(kind="clip",id=uid)
        review.classify(self.arc,dict(item,action="edit",name="Suffralchemy",writing="A silver word",heard="suffer alchemy"))
        review.classify(self.arc,dict(item,action="story",body="Out walking near the library",revision=0))
        cue=dict(quote="Luminous field recording",seconds=.5,stamp="0:00",label="Source excerpt")
        with self.arc.connection() as db:
            db.execute("INSERT INTO recording_evidence VALUES(?,?,?,?,?)",("clip",uid,0,"original",json.dumps([cue])))
        for query,field in (("silver","Filed writing"),("library","Recording story"),("suffer","Recognized spelling"),("luminous","Recognized in audio")):
            result=web.items(self.arc,"all",query,5,0)["items"][0]
            self.assertEqual(result["match"]["field"],field)
            self.assertEqual(result["title"],"Suffralchemy")
            self.assertNotIn("filedWriting",result)
        self.assertEqual(result["match"]["seconds"],.5)

    def test_similar_spelling_requires_source_words_and_current_filters(self):
        uid=self.stage(True)
        ref=dict(kind="clip",id=uid)
        review.classify(self.arc,dict(ref,action="edit",name="Suffralchemy",writing="A word smith remembers",heard=""))
        for word in ("suffralchmey","suffrralchemy","suffalchemy","suffralchemi"):
            res=web.items(self.arc,"clip",word+" remembers",10,0,device="laptop",similar=True)
            self.assertEqual(res["total"],0)
            near=res["similar"]["items"]
            self.assertEqual(len(near),1)
            self.assertEqual((near[0]["id"],near[0]["similar"]["found"]),(uid,"Suffralchemy"))
        joined=web.items(self.arc,"clip","wordsmith remembers",10,0,similar=True)["similar"]["items"]
        self.assertEqual(joined[0]["similar"]["found"],"word smith")
        self.assertNotIn("similar",web.items(self.arc,"clip","suffralchemy",10,0,similar=True))
        self.assertNotIn("similar",web.items(self.arc,"clip","suffralchmey",10,0))
        for query,device in (("suffralchmey absent","all"),("suffralchmey","other"),("unrelated","all")):
            self.assertEqual(web.items(self.arc,"clip",query,10,0,device=device,similar=True)["similar"]["items"],[])
        with patch.object(web,"SPELLING_LIMIT",0):
            capped=web.items(self.arc,"clip","suffralchmey",10,0,similar=True)["similar"]
            self.assertTrue(capped["limited"]);self.assertEqual(capped["checked"],0)
        self.assertIsNone(web.journal_match("2026-12-12 10:03:20 -05:00  [capture status] blue clock",["clock"]))
        stamp=web.journal_match("2026-12-12 10:03:20 -05:00  Blue clock",["clock"])
        self.assertEqual(stamp["at"],1797087800000)

    def test_quiet_pruning_preserves_marks_old_spools_and_audible_frames(self):
        stamp=recall_archive.now()-4000
        for i,(prefix,peak) in enumerate((("quietcheck",4),("quietcheck",7),("mark",0),("segment",0))):
            path=self.root/f"{prefix}-{stamp+i}-abc.pcm"
            path.write_bytes(bytes(64000)+peak.to_bytes(2,"little"))
            laptop.finish(self.arc,path,laptop.BASE)
            self.assertFalse(path.exists())
        self.assertEqual(self.arc.status()["counts"],dict(clip=1,segment=2,journal=0))
        self.assertEqual(laptop.read(self.root/"last-omission.json",{})["at"],stamp)

    def test_wifi_fallback_and_strict_policy(self):
        laptop.write(self.root/"settings.json",dict(network="Prefer Wi-Fi for 1 hour"))
        with patch.object(laptop,"on_wifi",return_value=False):
            self.assertTrue(laptop.wait_for_wifi(self.root,"a"))
            laptop.write(self.root/"wifi-wait.json",dict(since=time.time()-3601))
            self.assertFalse(laptop.wait_for_wifi(self.root,"b"))
            laptop.write(self.root/"settings.json",dict(network="Wi-Fi only"))
            self.assertTrue(laptop.wait_for_wifi(self.root,"b"))
        with patch.object(laptop,"on_wifi",return_value=True):self.assertFalse(laptop.wait_for_wifi(self.root,"b"))

    def test_review_delete_and_prevent_reimport(self):
        uid=self.stage(True);self.assertEqual(review.inbox(self.arc,"inbox",0)["total"],1)
        item=dict(kind="clip",id=uid)
        review.classify(self.arc,dict(item,action="add",label="Forge Lexicon"))
        review.classify(self.arc,dict(item,action="add",label="Poem draft"))
        review.classify(self.arc,dict(item,action="edit",name="Foremeaning",writing="A meaning that arrives early.",heard="four meaning"))
        self.assertEqual(review.inbox(self.arc,"label/Poem draft",0)["items"][0]["review"]["name"],"Foremeaning")
        self.assertEqual(web.items(self.arc,"all","Foremeaning",10,0)["total"],1)
        review.classify(self.arc,dict(item,action="dismiss"));self.assertEqual(review.inbox(self.arc,"dismissed",0)["total"],1)
        review.classify(self.arc,dict(item,action="reopen"));self.assertEqual(review.inbox(self.arc,"inbox",0)["total"],1)
        review.save_options(self.arc,dict(labels=["A","B"],order="oldest",batch=5))
        row=web.lookup(self.arc,"clip",uid);self.assertEqual(row["filingOptions"]["labels"],["A","B"])
        self.assertTrue(review.delete(self.arc,dict(item,sha256=row["sha256"]))["deleted"])
        self.assertEqual(review.inbox(self.arc,"inbox",0)["total"],0)
        with self.assertRaises(recall_archive.Failure):web.lookup(self.arc,"clip",uid)
    def test_remote_segments_bookmarks_retry_and_delete(self):
        segment=self.stage();clip=self.stage(True)
        remote_root=self.arc.root/"remote";remote_root.mkdir()
        with patch.multiple(desk,ROOT=remote_root,DB=remote_root/"clips.sqlite3"):
            remote=desk.archive();remote.media_store=CloudMedia(remote,client=TestS3(),bucket="test")
            done=threading.Event();laptop.write(self.root/"pair.json","local-test")
            completed=[]
            def send(token,method,path,data,headers):
                if method=="POST":return remote.begin(json.loads(data))
                uid=path.split("/")[-1];r=remote.put(uid,int(headers["X-Upload-Offset"]),data)
                if r["complete"]:
                    remote.media_store.ensure(uid);completed.append(uid)
                    if len(completed)==2:done.set()
                return r
            with patch.object(laptop,"http",send):laptop.push(self.arc,self.root,done)
            for kind,uid in (("segment",segment),("clip",clip)):
                row=web.lookup(remote,kind,uid)
                self.assertTrue(remote.media_store.head(uid,kind=kind)["ContentLength"])
                review.delete(remote,dict(kind=kind,id=uid,sha256=row["sha256"]))
                with self.assertRaises(recall_archive.Failure):remote.media_store.head(uid,kind=kind)
            self.assertEqual(remote.media_store.s3.objects,{})
        self.assertEqual(self.arc.status()["counts"]["segment"],1)
