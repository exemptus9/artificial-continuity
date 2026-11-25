"""Durable private archive storage. Every writer reserves space before accepting bytes."""
from contextlib import contextmanager, closing
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import time
import wave
import zipfile

DAY = 86_400_000
MIB = 1024 * 1024
MAX_CHUNK = MIB
MAX_ITEMS = {"segment":128*MIB, "clip":16*MIB, "journal":16*MIB}
UID = re.compile(r"[A-Za-z0-9_-]{1,128}")
MEDIA = {"segment":("audio/mp4","audio/aac"), "clip":("application/zip",),"journal":("text/plain",)}
YEAR = 7
DEFAULT_FLOOR = 1024*MIB

class Failure(Exception):
    def __init__(self,status,detail):
        super().__init__(detail)
        self.status=status

def now():
    return time.time_ns()//1_000_000

def expiry(stamp):
    dt=datetime.fromtimestamp(stamp/1000,timezone.utc)
    try: limit=dt.replace(year=dt.year+YEAR)
    except ValueError: limit=dt.replace(year=dt.year+YEAR,day=28)
    return int(limit.timestamp()*1000)

def sha_file(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source,"sha256").hexdigest()

def sync_dir(path):
    fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)

@contextmanager
def archive_lock(root):
    root.mkdir(mode=0o700,parents=True,exist_ok=True)
    with (root/"archive.lock").open("a") as file:
        fcntl.flock(file,fcntl.LOCK_EX)
        yield

class Archive:
    def __init__(self,desk):
        self.desk=desk
        self.root=desk.ROOT
        self.path=desk.DB
        self.files=self.root/"archive-files"
        desk.setup()
        self.files.mkdir(mode=0o700,exist_ok=True)
        with self.connection() as db:
            db.executescript("""
            CREATE TABLE IF NOT EXISTS archive_uploads (
              id TEXT PRIMARY KEY, kind TEXT NOT NULL, metadata TEXT NOT NULL, sha TEXT NOT NULL, bytes INTEGER NOT NULL,
              offset INTEGER NOT NULL, state TEXT NOT NULL, file TEXT NOT NULL, created INTEGER NOT NULL,
              touched INTEGER NOT NULL, started INTEGER NOT NULL, expires INTEGER NOT NULL, journal TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX IF NOT EXISTS archive_dates ON archive_uploads(state,started DESC);
            CREATE TABLE IF NOT EXISTS archive_settings(name TEXT PRIMARY KEY, value INTEGER NOT NULL);
            """)
    @contextmanager
    def connection(self):
        with closing(sqlite3.connect(self.path,timeout=40)) as db:
            db.row_factory=sqlite3.Row
            with db: yield db
    def limit(self,quota):
        if type(quota) is not int or quota<0: raise Failure(400,"Quota must be zero or a positive number of bytes")
        with archive_lock(self.root),self.connection() as db:
            db.execute("INSERT OR REPLACE INTO archive_settings VALUES('quota',?)",(quota,))
    def capacity(self,db):
        settings=dict(db.execute("SELECT name,value FROM archive_settings").fetchall())
        quota=settings.get("quota",0); floor=settings.get("floor",DEFAULT_FLOOR)
        used,=db.execute("SELECT coalesce(sum(CASE WHEN length(audio)>0 THEN length(audio) ELSE coalesce((SELECT bytes FROM clip_original_sizes sz WHERE sz.id=clips.id),44+2*samples) END),0) FROM clips").fetchone()
        size,pending,remaining=db.execute("""SELECT
           coalesce(sum(CASE WHEN state='complete' AND kind!='clip' THEN bytes END),0),
           coalesce(sum(CASE WHEN state='pending' THEN bytes END),0),
           coalesce(sum(CASE WHEN state='pending' THEN (CASE WHEN kind='journal' THEN 2*bytes ELSE bytes END)-offset END),0) FROM archive_uploads""").fetchone()
        used+=size
        free=shutil.disk_usage(self.root).free
        available=max(0,free-floor-remaining)
        if quota: available=max(0,min(available,quota-used-pending))
        counts=dict(db.execute("SELECT kind,count(*) FROM archive_uploads WHERE state='complete' AND kind!='clip' GROUP BY kind").fetchall())
        counts["clip"]=db.execute("SELECT count(*) FROM clips").fetchone()[0]
        return dict(quotaBytes=quota,usedBytes=used,reservedBytes=pending,availableBytes=available,freeBytes=free,
                retentionYears=YEAR,floorBytes=floor,diskAvailableBytes=max(0,free-floor-remaining),counts={k:counts.get(k,0) for k in MAX_ITEMS},root=str(self.root))
    def status(self):
        with self.connection() as db: return self.capacity(db)
    def manifest(self,obj):
        if not isinstance(obj,dict): raise Failure(422,"Expected upload metadata")
        uid=obj.get("id");kind=obj.get("kind");digest=obj.get("sha256")
        if not isinstance(uid,str) or not UID.fullmatch(uid) or not isinstance(kind,str) or kind not in MAX_ITEMS:
            raise Failure(422,"Invalid upload identity")
        if not isinstance(digest,str) or not re.fullmatch("[0-9a-f]{64}",digest): raise Failure(422,"Expected lowercase SHA-256")
        size=obj.get("bytes");stamp=obj.get("startedAtMillis");duration=obj.get("durationMs")
        if type(size)!=int or not 0<size<=MAX_ITEMS[kind]: raise Failure(413,"Invalid object size")
        if type(stamp)!=int or not 0<stamp<=now()+DAY or type(duration)!=int or not 0<=duration<=32*DAY:
            raise Failure(422,"Invalid capture time or duration")
        if expiry(stamp+duration)<=now(): raise Failure(410,"Capture is past its seven-year retention date")
        media=obj.get("mediaType",MEDIA[kind][0])
        if media not in MEDIA[kind]: raise Failure(415,"Unsupported capture media type")
        data=dict(id=uid,kind=kind,sha256=digest,bytes=size,startedAtMillis=stamp,durationMs=duration,mediaType=media)
        for key in ("title","sessionId"):
            value=obj.get(key,"")
            if not isinstance(value,str) or len(value)>500: raise Failure(422,"Invalid capture description")
            data[key]=value
        return data
    @staticmethod
    def ack(row):
        return dict(ok=True,id=row["id"],offset=row["offset"],complete=row["state"]=="complete",
                    bytes=row["bytes"],sha256=row["sha"])
    def begin(self,raw):
        m=self.manifest(raw);data=json.dumps(m,sort_keys=True,separators=(",",":"));t=now()
        with archive_lock(self.root),self.connection() as db:
            row=db.execute("SELECT * FROM archive_uploads WHERE id=?",(m["id"],)).fetchone()
            if row:
                if row["metadata"]!=data: raise Failure(409,"Upload ID already has different metadata")
                return self.ack(row)
            capacity=self.capacity(db)
            disk_need=m["bytes"]*(2 if m["kind"]=="journal" else 1)
            if capacity["availableBytes"]<m["bytes"] or capacity["diskAvailableBytes"]<disk_need:
                raise Failure(507,"Archive space is fully allocated")
            if db.execute("SELECT count(*) FROM archive_uploads WHERE state='pending'").fetchone()[0]>=256:
                raise Failure(429,"Too many incomplete uploads")
            file="archive-files/"+m["id"]
            with (self.root/file).open("wb") as f: f.flush();os.fsync(f.fileno())
            sync_dir(self.files)
            db.execute("INSERT INTO archive_uploads VALUES(?,?,?,?,?,0,'pending',?,?,?,?,?,'')",
                (m["id"],m["kind"],data,m["sha256"],m["bytes"],file,t,t,m["startedAtMillis"],expiry(m["startedAtMillis"]+m["durationMs"])))
            return self.ack(db.execute("SELECT * FROM archive_uploads WHERE id=?",(m["id"],)).fetchone())
    def validate(self,path,m):
        if sha_file(path)!=m["sha256"]: raise Failure(422,"Checksum failed; restart the upload")
        if m["kind"]=="clip": return ""
        if m["kind"]=="journal":
            try: text=path.read_text(encoding="utf-8")
            except UnicodeError: raise Failure(422,"Journal is not UTF-8 text") from None
            if "\x00" in text or not text.strip(): raise Failure(422,"Empty or invalid journal")
            return text
        # Only a local raw AAC stream or local MP4 container may reach the media parser.
        fmt="mov" if m["mediaType"]=="audio/mp4" else "aac"
        args=["ffprobe","-v","error","-protocol_whitelist","file","-f",fmt,"-show_entries",
              "stream=codec_name,codec_type,channels,sample_rate","-of","json",str(path)]
        try:
            probe=subprocess.run(args,capture_output=True,timeout=35,check=True)
            data=json.loads(probe.stdout);streams=data["streams"]
            if len(streams)!=1 or streams[0].get("codec_name")!="aac" or streams[0].get("codec_type")!="audio":
                raise ValueError()
            if not (1<=int(streams[0]["channels"])<=2 and 8000<=int(streams[0]["sample_rate"])<=96000): raise ValueError()
            # ffprobe estimates raw ADTS duration from the first packets. Silence can lower
            # VBR bitrate dramatically. Validate the fully decoded audio clock instead.
            decoded=subprocess.run(["ffmpeg","-nostdin","-v","error","-xerror","-protocol_whitelist","file","-f",fmt,
                "-i",str(path),"-map","0:a:0","-progress","pipe:1","-nostats","-f","null","-"],
                stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=120,check=True)
            times=re.findall(rb"^out_time_us=(\d+)$",decoded.stdout,re.MULTILINE)
            if not times or b"progress=end" not in decoded.stdout: raise ValueError()
            duration=int(times[-1])/1000
            if duration<=0 or abs(duration-m["durationMs"])>max(2500,duration*0.035): raise ValueError()
        except FileNotFoundError: raise Failure(503,"Install ffmpeg on the archive host") from None
        except (ValueError,KeyError,subprocess.SubprocessError): raise Failure(422,"AAC stream or duration validation failed") from None
        return ""
    def put(self,uid,offset,body):
        if not UID.fullmatch(uid) or not 0<len(body)<=MAX_CHUNK: raise Failure(413,"Invalid chunk")
        with archive_lock(self.root),self.connection() as db:
            row=db.execute("SELECT * FROM archive_uploads WHERE id=?",(uid,)).fetchone()
            if not row: raise Failure(404,"Begin this upload first")
            if row["state"]=="complete": return self.ack(row)
            if offset!=row["offset"]: raise Failure(409,"Offset changed; resubmit the manifest")
            end=offset+len(body)
            if end>row["bytes"]: raise Failure(422,"Chunk exceeds the declared length")
            info=self.capacity(db)
            final_space=row["bytes"] if end==row["bytes"] and row["kind"]=="journal" else 0
            if info["freeBytes"]-info["floorBytes"]<len(body)+final_space: raise Failure(507,"Host free-space reserve reached")
            path=self.root/row["file"]
            with path.open("r+b") as out:
                out.seek(offset);out.truncate();out.write(body);out.flush();os.fsync(out.fileno())
            text=""
            if end==row["bytes"]:
                m=json.loads(row["metadata"])
                try:
                    text=self.validate(path,m)
                    if row["kind"]=="clip":
                        text=self.desk.receive_one(path,archive=self,locked=True,reserved=row["bytes"])["id"]
                except (Failure,self.desk.Issue,ValueError,KeyError,TypeError,EOFError,wave.Error,zipfile.BadZipFile) as e:
                    if isinstance(e,Failure) and e.status>=500: raise
                    with path.open("wb") as out: out.flush();os.fsync(out.fileno())
                    db.execute("UPDATE archive_uploads SET offset=0,touched=? WHERE id=?",(now(),uid))
                    db.commit()
                    if isinstance(e,Failure): raise
                    raise Failure(422,"Clip archive failed validation") from None
            state="complete" if end==row["bytes"] else "pending"
            db.execute("UPDATE archive_uploads SET offset=?,state=?,touched=?,journal=? WHERE id=?",(end,state,now(),text,uid))
            db.commit()
            if state=="complete" and row["kind"]=="clip": path.unlink(missing_ok=True)
            return self.ack(db.execute("SELECT * FROM archive_uploads WHERE id=?",(uid,)).fetchone())
    def trim(self,t=None):
        t=now() if t is None else t
        with archive_lock(self.root),self.connection() as db:
            db.execute("PRAGMA secure_delete=ON")
            stale=list(db.execute("""SELECT id,file,sha FROM archive_uploads
                WHERE expires<=? OR (state='pending' AND touched<?)""",(t,t-DAY*7)))
            for row in stale:
                db.execute("DELETE FROM archive_uploads WHERE id=?",(row['id'],))
            # A seven-year UTC anniversary accounts for leap years without using a fixed day count.
            cutoff=datetime.fromtimestamp(t/1000,timezone.utc)
            try: earliest=cutoff.replace(year=cutoff.year-YEAR)
            except ValueError: earliest=cutoff.replace(year=cutoff.year-YEAR,day=28)
            threshold=int(earliest.timestamp()*1000)+DAY
            ids=[row[0] for row in db.execute("SELECT id,started,duration FROM clips WHERE started<=?",(threshold,)) if expiry(row[1]+row[2])<=t]
            db.executemany("DELETE FROM observations WHERE clip_id=?",((uid,) for uid in ids))
            db.executemany("DELETE FROM clip_original_sizes WHERE id=?",((uid,) for uid in ids))
            db.executemany("DELETE FROM clips WHERE id=?",((uid,) for uid in ids))
            db.commit()
            if ids:db.execute("PRAGMA incremental_vacuum(2048)")
            # Also collect orphan files left by a crash around deletion.
            live={r[0] for r in db.execute("SELECT file FROM archive_uploads WHERE kind!='clip' OR state='pending'")}
            for path in self.files.iterdir():
                if str(path.relative_to(self.root)) not in live and path.is_file(): path.unlink(missing_ok=True)
            preview=self.root/"browser-previews"
            if preview.is_dir():
                expired={row["sha"] for row in stale}
                with (preview/"cache.lock").open("a") as lock:
                    fcntl.flock(lock,fcntl.LOCK_EX)
                    for file in preview.glob("*.m4a"):
                        if file.stem in expired or file.stat().st_mtime<time.time()-3600:file.unlink(missing_ok=True)
            return len(stale)+len(ids)
