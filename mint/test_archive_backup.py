import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import wave
from unittest.mock import patch
import archive_backup as backup
import recall_desk as desk

def sha(data):
    return hashlib.sha256(data).hexdigest()

class ArchiveBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        path = Path(self.temp.name)
        self.root, self.drive, self.restore = path / "live", path / "drive", path / "restore"
        self.root.mkdir()
        self.drive.mkdir()
        self.db = self.root / "clips.sqlite3"
        p = patch.multiple(desk, ROOT=self.root, DB=self.db, APP=self.root/"recall_desk.py")
        p.start()
        self.addCleanup(p.stop)
        desk.archive()
        stream = io.BytesIO()
        with wave.open(stream, "wb") as out:
            out.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            out.writeframes(b"\x22\x03" * 1600)
        self.wav = stream.getvalue()
        self.audio = b"Compressed segment original bytes."
        self.journal = b"11:37 Come back to the rhyme."
        self.secret = "fake-auth-token-must-stay-on-this-computer"
        (self.root / "pairing-token").write_text(self.secret)
        with sqlite3.connect(self.db) as db:
            db.execute("INSERT INTO clips VALUES(?,100,100,1600,?,?, 'My correction','Transcript','verse','rhyme',?,?)",
                       ("selected", sha(self.wav), self.wav, '{"note":"pencil edit"}', "keep"))
            db.execute("INSERT INTO observations VALUES('selected','old metadata','today')")
            db.execute("CREATE TABLE future_credentials(secret TEXT)")
            db.execute("INSERT INTO future_credentials VALUES(?)", (self.secret,))
            for uid, kind, data, state in (("sound","segment",self.audio,"complete"), ("words","journal",self.journal,"complete"),
                      ("partial","journal",self.secret.encode(),"pending"), ("selected-ack","clip",b"already imported","complete")):
                file = "archive-files/" + uid
                size = len(data)
                db.execute("INSERT INTO archive_uploads VALUES(?,?,?,?,?,?,?,?,0,0,0,0,?)",
                           (uid,kind,"{}",sha(data),size,size if state=="complete" else 0,state,file,""))
                if kind != "clip":
                    (self.root / file).write_bytes(data)

    def snapshot(self):
        return Path(backup.create_backup(self.root, self.drive)["snapshot"])

    def test_roundtrip_and_incremental_rotation(self):
        first = self.snapshot()
        second = backup.create_backup(self.root, self.drive)
        self.assertEqual((second["createdObjects"],second["reusedObjects"]), (0,2))
        third = self.snapshot()
        self.assertFalse(first.exists())
        self.assertTrue(Path(second["snapshot"]).is_dir())
        self.assertEqual(len(backup.snapshots(third.parent)), 2)
        self.assertEqual(len(list((third.parent.parent/"objects").glob("*/*"))), 2)
        self.restore.mkdir()
        backup.restore_backup(third,self.restore)
        self.assertEqual((self.restore/"archive-files/sound").read_bytes(), self.audio)
        self.assertEqual((self.restore/"archive-files/words").read_bytes(), self.journal)
        self.assertFalse((self.restore/"archive-files/partial").exists())
        self.assertFalse((self.restore/"pairing-token").exists())
        with sqlite3.connect(self.restore/"clips.sqlite3") as db:
            self.assertEqual(db.execute("SELECT audio,title,feedback,metadata FROM clips").fetchone(),
                             (self.wav,"My correction","keep",'{"note":"pencil edit"}'))
            self.assertEqual(db.execute("SELECT COUNT(*) FROM observations").fetchone(),(1,))
            self.assertEqual(db.execute("SELECT COUNT(*) FROM archive_uploads").fetchone(),(3,))
        for f in self.drive.rglob("*"):
            if f.is_file():
                self.assertNotIn(self.secret.encode(),f.read_bytes())

    def test_daily_drive_identity_missing_and_replace(self):
        backup.configure_daily(self.root,self.drive)
        self.assertEqual(backup.daily_destination(self.root),str(self.drive))
        actual = self.drive.with_name("temporarily-removed")
        self.drive.rename(actual)
        self.drive.mkdir()  # Empty underlying mountpoint must remain untouched.
        with self.assertRaises((OSError,ValueError)):
            backup.run_daily(self.root)
        self.assertEqual(list(self.drive.iterdir()),[])
        self.drive.rmdir()
        actual.rename(self.drive)
        self.assertTrue(backup.run_daily(self.root))
        self.assertIsNone(backup.run_daily(self.root))
        backup.configure_daily(self.root,None)
        self.assertIsNone(backup.daily_destination(self.root))

    def test_damaged_changed_media_and_partial_copy(self):
        snap = self.snapshot()
        source = self.root/"archive-files/new"
        source.write_bytes(b"broken")
        with sqlite3.connect(self.db) as db:
            db.execute("INSERT INTO archive_uploads VALUES('new','segment','{}',?,6,6,'complete','archive-files/new',0,0,0,0,'')",(sha(b"proper"),))
        with self.assertRaises(ValueError):
            self.snapshot()
        self.assertEqual(backup.snapshots(snap.parent),[snap])
        self.assertEqual(list(snap.parent.glob(".building-*")),[])
        blob = snap.parent.parent/"objects"/sha(self.audio)[:2]/sha(self.audio)
        blob.write_bytes(b"x"*len(self.audio))
        self.restore.mkdir()
        with self.assertRaises(ValueError):
            backup.restore_backup(snap,self.restore)
        self.assertEqual(list(self.restore.iterdir()),[])
        self.assertFalse(list(self.restore.parent.glob(".recall-restore-*")))

    def test_bad_catalog_symlinks_nested_targets_and_quota(self):
        with self.assertRaises(ValueError):
            backup.create_backup(self.root,self.root/"archive-files")
        snap = self.snapshot()
        alias=self.temp.name+"/via-link"
        Path(alias).symlink_to(self.drive,target_is_directory=True)
        with self.assertRaises(ValueError):
            backup.create_backup(self.root,Path(alias))
        (snap/"clips.sqlite3").write_bytes(b"damaged database")
        with self.assertRaises(ValueError):
            backup.restore_backup(snap,self.restore)
        self.assertFalse(self.restore.exists())
        backup.configure_limits(self.root,self.drive,quota_bytes=1)
        with self.assertRaisesRegex(ValueError,"quota"):
            self.snapshot()

    def test_malicious_manifest_and_symlink_restore(self):
        snap = self.snapshot()
        symlink=snap.parent.parent/"objects"/sha(self.journal)[:2]/sha(self.journal)
        symlink.unlink()
        symlink.symlink_to(self.root/"archive-files/words")
        with self.assertRaises(ValueError):
            backup.restore_backup(snap,self.restore)
        symlink.unlink()
        symlink.write_bytes(self.journal)
        # Recomputed manifest checksum alone cannot authorize an escaping media path.
        catalog = snap/"clips.sqlite3"
        with sqlite3.connect(catalog) as db:
            db.execute("UPDATE archive_uploads SET file='../secret' WHERE id='words'")
        info=json.loads((snap/"manifest.json").read_text())
        info.update(catalogSha256=sha(catalog.read_bytes()),catalogBytes=catalog.stat().st_size)
        (snap/"manifest.json").write_text(json.dumps(info))
        with self.assertRaisesRegex(ValueError,"Invalid media"):
            backup.restore_backup(snap,self.restore)
        self.assertFalse(self.restore.exists())

if __name__ == "__main__":
    unittest.main()
