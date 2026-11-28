"""Local S3 protocol tests. Install boto3 and moto[s3], then run python -m unittest test_cloud_media."""
from contextlib import closing
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import zipfile
from test_recall_desk import wav, record
from unittest import TestCase, skipUnless, mock

try:
    import boto3
    from moto import mock_aws
except ImportError:
    boto3=mock_aws=None

import recall_archive
import recall_desk as desk
from cloud_media import CloudMedia, MEDIA, SNAPS


@skipUnless(mock_aws and boto3,"boto3 + moto[s3] required for offline S3 protocol tests")
class PrivateStorage(TestCase):
    def setUp(self):
        context=mock_aws()
        context.start();self.addCleanup(context.stop)
        work=tempfile.TemporaryDirectory();self.addCleanup(work.cleanup)
        self.path=Path(work.name)
        for name,value in {"ROOT":self.path,"DB":self.path/"clips.sqlite3"}.items():
            change=mock.patch.object(desk,name,value);change.start();self.addCleanup(change.stop)
        self.arc=recall_archive.Archive(desk)
        self.client=boto3.client("s3",region_name="us-east-1")
        self.bucket="private-recall-test";self.client.create_bucket(Bucket=self.bucket)
        self.cloud=CloudMedia(self.arc,client=self.client,bucket=self.bucket)
        self.stamp=recall_archive.now()
        self.data=b"Record the light through the window.\n"*600

    def upload(self,uid="journal-id"):
        manifest={"id":uid,"kind":"journal","bytes":len(self.data),"sha256":hashlib.sha256(self.data).hexdigest(),
            "startedAtMillis":self.stamp,"durationMs":1000,"mediaType":"text/plain"}
        self.assertFalse(self.arc.begin(manifest)["complete"])
        self.assertTrue(self.arc.put(uid,0,self.data)["complete"])
        return manifest

    def wav_transfer(self, uid="kept", revision=0):
        audio=wav(35);clip=record(uid,audio)
        clip.update(startedAtMillis=self.stamp,title="WAV revision "+str(revision))
        out=io.BytesIO()
        with zipfile.ZipFile(out,"w") as z:
            z.writestr(clip["audio"],audio);z.writestr("index.json",json.dumps({"schema":desk.FORMAT,"clips":[clip]}))
        data=out.getvalue();digest=hashlib.sha256(data).hexdigest();upload="rev-"+digest[:24]
        raw=dict(kind="clip",id=upload,bytes=len(data),sha256=digest,startedAtMillis=self.stamp,durationMs=100,mediaType="application/zip")
        self.arc.begin(raw);self.arc.put(upload,0,data)
        return raw,data,audio

    def keys(self,prefix=MEDIA):
        return sorted(self.cloud._objects(prefix))

    def test_commit_replay_read_quota(self):
        raw=self.upload()
        size=self.arc.status()["usedBytes"]
        self.cloud.ensure(raw["id"])
        self.assertFalse((self.arc.files/raw["id"]).exists())
        self.assertEqual(self.arc.status()["usedBytes"],size)
        self.assertEqual(self.arc.status()["quotaBytes"],1_000_000_000_000)
        restarted=CloudMedia(self.arc,client=self.client,bucket=self.bucket)
        self.assertTrue(self.arc.begin(raw)["complete"])
        restarted.ensure(raw["id"])
        self.assertEqual(restarted.head(raw["id"])["ContentType"],"text/plain")
        with closing(restarted.read(raw["id"],13,41)) as body:
            self.assertEqual(body.read(),self.data[13:54])
        with closing(restarted.read(raw["id"])) as body:
            self.assertEqual(body.read(),self.data)
        for start,length in [(1,0),(-1,10),(len(self.data),1)]:
            with self.assertRaises(recall_archive.Failure): restarted.read(raw["id"],start,length)
        self.assertEqual(len(self.keys()),1)

    def test_put_failure_preserves_stage_and_resumes(self):
        self.upload()
        with mock.patch.object(self.client,"put_object",side_effect=IOError("unavailable")):
            with self.assertRaises(recall_archive.Failure): self.cloud.ensure("journal-id")
        self.assertEqual((self.arc.files/"journal-id").read_bytes(),self.data)
        with self.assertRaises(recall_archive.Failure): self.cloud.read("journal-id")
        self.cloud.ensure("journal-id")
        self.assertFalse((self.arc.files/"journal-id").exists())

    def test_false_hash_and_replay_missing_object(self):
        self.upload()
        native=self.client.get_object
        def wrong(**args):
            reply=native(**args)
            reply["Body"].close();reply["Body"]=io.BytesIO(b"x"*len(self.data))
            return reply
        with mock.patch.object(self.client,"get_object",side_effect=wrong):
            with self.assertRaises(recall_archive.Failure): self.cloud.ensure("journal-id")
        self.assertTrue((self.arc.files/"journal-id").is_file())
        with self.cloud.db() as db:
            self.assertEqual(db.execute("SELECT verified FROM cloud_media").fetchone()[0],0)
        self.cloud.ensure("journal-id")
        self.client.delete_object(Bucket=self.bucket,Key=self.keys()[0])
        with self.assertRaises(recall_archive.Failure): self.cloud.ensure("journal-id")

    def test_range_is_checked(self):
        self.upload();self.cloud.ensure("journal-id")
        native=self.client.get_object
        def lose_range(**args):
            args.pop("Range",None)
            return native(**args)
        with mock.patch.object(self.client,"get_object",side_effect=lose_range):
            with self.assertRaises(recall_archive.Failure): self.cloud.read("journal-id",1,7)

    def test_expiry_and_orphans_are_scoped(self):
        self.upload();self.cloud.ensure("journal-id")
        key=self.keys()[0];deadline=int(key[len(MEDIA):].split("/")[0])
        orphan=key.replace("/journal-id/","/orphan/")
        head=self.client.head_object(Bucket=self.bucket,Key=key)
        for suffix,metadata in [(orphan,head["Metadata"]),(orphan.replace("/orphan/","/other/"),dict(head["Metadata"],archive="different"))]:
            self.client.put_object(Bucket=self.bucket,Key=suffix,Body=self.data,Metadata=metadata)
        self.client.put_object(Bucket=self.bucket,Key="unknown/keep-me",Body=b"foreign")
        self.cloud._expire(deadline-1);self.assertIn(key,self.keys())
        self.arc.trim(deadline);self.cloud._expire(deadline)
        self.assertEqual(self.keys(),[orphan.replace("/orphan/","/other/")])
        self.assertIn("unknown/keep-me",self.keys("unknown/"))

    def test_crash_left_staging_and_snapshot_rotation_and_restore(self):
        self.upload();self.cloud.ensure("journal-id")
        (self.arc.files/"journal-id").write_bytes(self.data)  # crash after verified ledger commit
        pending=dict(id="pending",kind="journal",bytes=4,sha256=hashlib.sha256(b"Wait").hexdigest(),
            startedAtMillis=self.stamp,durationMs=0,mediaType="text/plain")
        self.arc.begin(pending);self.arc.put("pending",0,b"W")
        with self.arc.connection() as db:
            db.executemany("INSERT INTO journal_keeps VALUES(?,?,?,?,?,?)",[
                ("journal-id",0,self.stamp,"A retained line","","Poem draft"),
                ("pending",0,self.stamp,"Unfinished line","",""),
            ])
            db.execute("CREATE TABLE secret_tokens(value TEXT)")
            db.execute("INSERT INTO secret_tokens VALUES('a-sensitive-token')")
            db.execute("CREATE TABLE cloud_transfer_month(month TEXT,kind TEXT,bytes INTEGER)")
            db.execute("INSERT INTO cloud_transfer_month VALUES('2026-11','playback',1701)")
        raw,_,audio=self.wav_transfer()
        self.cloud.ensure(raw["id"])
        for day in (0,1,2):
            self.assertTrue(self.cloud.maintain(self.stamp+day*recall_archive.DAY)["snapshotCreated"])
        self.assertFalse((self.arc.files/"journal-id").exists())
        self.assertFalse(self.cloud.maintain(self.stamp+2*recall_archive.DAY)["snapshotCreated"])
        self.assertEqual(len([k for k in self.keys(SNAPS) if k.endswith(".sqlite3")]),2)
        dest=self.path/"restored.sqlite3"
        self.cloud.restore_latest(dest)
        with closing(sqlite3.connect(dest)) as db:
            self.assertEqual(db.execute("SELECT id,wording,label FROM journal_keeps").fetchall(),[("journal-id","A retained line","Poem draft")])
            self.assertEqual(db.execute("SELECT length(audio) FROM clips WHERE id='kept'").fetchone(),(0,))
            self.assertEqual(db.execute("SELECT bytes FROM cloud_wavs WHERE id='kept' AND verified=1").fetchone(),(len(audio),))
            self.assertEqual(db.execute("SELECT bytes FROM cloud_transfer_month").fetchone(),(1701,))
            self.assertEqual(db.execute("SELECT count(*) FROM archive_uploads").fetchone()[0],2)
            self.assertEqual(db.execute("SELECT journal FROM archive_uploads WHERE id='journal-id'").fetchone()[0].encode(),self.data)
            self.assertEqual(db.execute("SELECT count(*) FROM cloud_media WHERE verified=1").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT name FROM sqlite_master WHERE name='secret_tokens'").fetchall(),[])
        self.assertNotIn(b"a-sensitive-token",dest.read_bytes())
        with mock.patch.object(desk,"DB",dest):
            recovered=CloudMedia(recall_archive.Archive(desk),client=self.client,bucket=self.bucket)
            with closing(recovered.read("kept",kind="clip")) as remote:
                self.assertEqual(remote.read(),audio)
        with self.assertRaises(recall_archive.Failure): self.cloud.restore_latest(dest)

    def test_unverified_catalog_never_becomes_a_restore_point(self):
        self.upload();self.cloud.ensure("journal-id")
        with mock.patch.object(self.cloud,"_verify_remote",side_effect=recall_archive.Failure(503,"bad replica")):
            with self.assertRaises(recall_archive.Failure): self.cloud.maintain()
        self.assertEqual(self.cloud._catalogs(),[])
        with self.assertRaises(recall_archive.Failure): self.cloud.restore_latest(self.path/"absent.sqlite3")
        self.assertTrue(self.cloud.maintain()["snapshotCreated"])

    def test_bad_newest_snapshot_falls_back(self):
        self.upload();self.cloud.ensure("journal-id")
        self.cloud.maintain(self.stamp);self.cloud.maintain(self.stamp+recall_archive.DAY)
        newest,older=self.cloud._catalogs()
        head=self.client.head_object(Bucket=self.bucket,Key=newest)
        self.client.put_object(Bucket=self.bucket,Key=newest,Body=b"bad",Metadata=head["Metadata"],ContentType="application/vnd.sqlite3")
        self.assertEqual(self.cloud.restore_latest(self.path/"fallback.sqlite3"),older)

    def test_selected_wav_offloads_and_revision_deduplicates(self):
        raw,_,audio=self.wav_transfer();self.cloud.ensure(raw["id"])
        before=self.arc.status()["usedBytes"]
        self.assertEqual(before,len(audio))
        self.assertEqual(self.cloud.head("kept",kind="clip")["ContentLength"],len(audio))
        with closing(self.cloud.read("kept",17,91,kind="clip")) as media:
            self.assertEqual(media.read(),audio[17:108])
        with self.arc.connection() as db:
            self.assertEqual(db.execute("SELECT audio FROM clips").fetchone()[0],b"")
            self.assertEqual(db.execute("SELECT bytes FROM clip_original_sizes").fetchone()[0],len(audio))
        revision,_,_=self.wav_transfer(revision=1);self.cloud.ensure(revision["id"])
        self.assertEqual(before,self.arc.status()["usedBytes"])
        self.assertEqual(len(self.keys()),1)
        self.assertEqual(desk.detail("kept")["title"],"WAV revision 1")

    def test_failed_wav_retains_blob_and_repairs_remote_loss(self):
        raw,data,audio=self.wav_transfer()
        with mock.patch.object(self.cloud,"_verify_remote",side_effect=recall_archive.Failure(503,"bad")):
            with self.assertRaises(recall_archive.Failure): self.cloud.ensure(raw["id"])
        with self.arc.connection() as db:
            self.assertEqual(db.execute("SELECT audio FROM clips").fetchone()[0],audio)
        self.cloud.ensure(raw["id"])
        self.client.delete_object(Bucket=self.bucket,Key=self.keys()[0])
        with self.assertRaises(recall_archive.Failure): self.cloud.ensure(raw["id"])
        restarted=self.arc.begin(raw);self.assertFalse(restarted["complete"]);self.assertEqual(restarted["offset"],0)
        self.arc.put(raw["id"],0,data);self.cloud.ensure(raw["id"])
        with closing(self.cloud.read("kept",kind="clip")) as restored:self.assertEqual(restored.read(),audio)

    def test_kind_namespaces_are_independent(self):
        self.upload("kept");self.cloud.ensure("kept")
        raw,_,audio=self.wav_transfer("kept");self.cloud.ensure(raw["id"])
        with closing(self.cloud.read("kept",kind="clip")) as f:self.assertEqual(f.read(),audio)
        with closing(self.cloud.read("kept",kind="journal")) as f:self.assertEqual(f.read(),self.data)
        with self.assertRaises(recall_archive.Failure): self.cloud.read("kept",kind="segment")
