"""Private object storage for the single-owner hosted archive. Local Desk needs no SDK.

The HTTP service calls ensure() on every complete begin/put receipt before sending it
to the phone. The database is the catalog; the bucket stores immutable originals and
the two newest verified catalog snapshots. Use a dedicated private bucket per archive.
"""
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
import base64
import fcntl
import hashlib
import json
import os
import re
import sqlite3
import tempfile
import uuid

from recall_archive import Failure, UID, now, sha_file, sync_dir


ROOT = "total-recall/v1/"
MEDIA = ROOT + "media/"
SNAPS = ROOT + "catalogs/"
SHA = r"[0-9a-f]{64}"
MEDIA_KEY = re.compile(re.escape(MEDIA) + rf"(\d{{16}})/(segment|journal)/([A-Za-z0-9_-]{{1,128}})/({SHA})")
SNAP_KEY = re.compile(re.escape(SNAPS) + rf"(\d{{8}})-({SHA})\.sqlite3")
BLOCK = 1024 * 1024
DEFAULT_QUOTA = 350_000_000_000


def _error_code(exc):
    return str(getattr(exc, "response", {}).get("Error", {}).get("Code", ""))


def _etag(obj):
    return str(obj.get("ETag", "")).strip('"')


def _checksum(sha):
    return base64.b64encode(bytes.fromhex(sha)).decode("ascii")


def _stream_digest(stream, target=None):
    digest = hashlib.sha256()
    count = 0
    while part := stream.read(BLOCK):
        digest.update(part)
        count += len(part)
        if target is not None:
            target.write(part)
    return count, digest.hexdigest()


class CloudMedia:
    def __init__(self, archive, *, client=None, bucket=None):
        self.archive = archive
        self.bucket = bucket or os.environ["TR_S3_BUCKET"]
        if client is None:
            import boto3
            from botocore.config import Config
            endpoint = os.environ["TR_S3_ENDPOINT"]
            parts = urlsplit(endpoint)
            if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
                raise ValueError("Use the HTTPS endpoint supplied by the private bucket")
            style = os.environ.get("TR_S3_STYLE", "virtual")
            if style not in {"virtual", "path"}:
                raise ValueError("TR_S3_STYLE must be virtual or path")
            client = boto3.client("s3", endpoint_url=endpoint, region_name=os.environ["TR_S3_REGION"],
                aws_access_key_id=os.environ["TR_S3_KEY_ID"], aws_secret_access_key=os.environ["TR_S3_SECRET"],
                config=Config(signature_version="s3v4", connect_timeout=8, read_timeout=90,
                    retries={"mode":"standard","total_max_attempts":3}, s3={"addressing_style":style},
                    request_checksum_calculation="when_required",response_checksum_validation="when_required"))
        self.s3 = client
        self.locks = archive.root / "cloud-locks"
        self.locks.mkdir(mode=0o700, exist_ok=True)
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS cloud_media(
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL, object_key TEXT NOT NULL UNIQUE, sha TEXT NOT NULL,
                  bytes INTEGER NOT NULL, mime TEXT NOT NULL, expires INTEGER NOT NULL,
                  verified INTEGER NOT NULL DEFAULT 0, etag TEXT NOT NULL DEFAULT '');
                CREATE INDEX IF NOT EXISTS cloud_expiry ON cloud_media(expires);
                CREATE TABLE IF NOT EXISTS cloud_identity(slot INTEGER PRIMARY KEY, instance TEXT NOT NULL);
            """)
            db.execute("INSERT OR IGNORE INTO cloud_identity VALUES(1,?)", (str(uuid.uuid4()),))
            db.execute("INSERT OR IGNORE INTO archive_settings(name,value) VALUES('quota',?)", (DEFAULT_QUOTA,))
            self.instance = db.execute("SELECT instance FROM cloud_identity WHERE slot=1").fetchone()[0]

    def db(self):
        return self.archive.connection()

    @contextmanager
    def lock(self, name):
        with (self.locks/name).open("a") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            yield

    def stripe(self, uid):
        return self.lock("media-" + hashlib.sha256(uid.encode("ascii")).hexdigest()[:2])

    def _call(self, func, **kwargs):
        try:
            return func(Bucket=self.bucket, **kwargs)
        except Exception as exc:
            raise Failure(503, "Private object storage is unavailable. Retry the transfer.") from exc

    def _head(self, key):
        try:
            return self.s3.head_object(Bucket=self.bucket, Key=key)
        except Exception as exc:
            if _error_code(exc) in {"404","NoSuchKey","NotFound"}:
                return None
            raise Failure(503, "Could not check the private storage object") from exc

    def _upload(self, path, key, sha, size, mime, metadata):
        """A key names exactly one content digest. Even concurrent retry writers supply identical bytes."""
        if size > 4 * 1024**3:
            from boto3.s3.transfer import TransferConfig
            try:
                self.s3.upload_file(str(path), self.bucket, key,
                    ExtraArgs={"ContentType":mime,"Metadata":metadata},
                    Config=TransferConfig(multipart_chunksize=32*BLOCK,max_concurrency=2))
            except Exception as exc:
                raise Failure(503,"Catalog multipart upload failed") from exc
            return
        try:
            with path.open("rb") as source:
                self.s3.put_object(Bucket=self.bucket,Key=key,Body=source,ContentLength=size,
                    ContentType=mime,Metadata=metadata,ChecksumSHA256=_checksum(sha),IfNoneMatch="*")
        except Exception as exc:
            if _error_code(exc) in {"PreconditionFailed","412","ConditionalRequestConflict","409"}:
                if self._head(key) is not None:
                    return
            raise Failure(503, "Private object upload failed. The local copy is retained.") from exc

    def _validate_head(self, head, size, sha, mime):
        if (head is None or head.get("ContentLength")!=size or head.get("Metadata",{}).get("sha256")!=sha
            or head.get("ContentType")!=mime or not _etag(head)):
            raise Failure(503, "Stored object metadata disagrees with the catalog")
        receipt = head.get("ChecksumSHA256")
        if receipt and receipt != _checksum(sha) and "-" not in receipt:
            raise Failure(503,"Stored object checksum disagrees with the catalog")

    def _verify_remote(self, key, head, size, sha, mime):
        self._validate_head(head, size, sha, mime)
        response = self._call(self.s3.get_object, Key=key, IfMatch=head["ETag"])
        try:
            with closing(response["Body"]) as body:
                got, digest = _stream_digest(body)
        except Exception as exc:
            raise Failure(503,"Could not finish verifying the stored object") from exc
        if got != size or digest != sha:
            raise Failure(503, "Stored bytes failed SHA-256 verification. The local copy is retained.")

    @staticmethod
    def _media_key(row):
        return f"{MEDIA}{row['expires']:016d}/{row['kind']}/{row['id']}/{row['sha']}"

    def ensure(self, uid):
        if not isinstance(uid,str) or not UID.fullmatch(uid):
            raise Failure(404,"Missing capture")
        with self.stripe(uid), self.db() as db:
            row=db.execute("SELECT * FROM archive_uploads WHERE id=?",(uid,)).fetchone()
            if row is None or row["state"]!="complete":
                raise Failure(409,"Original upload is incomplete")
            if row["expires"]<=now():
                raise Failure(410,"Capture has expired")
            if row["kind"]=="clip":
                return
            key=self._media_key(row)
            mime=json.loads(row["metadata"])["mediaType"]
            db.execute("""INSERT OR IGNORE INTO cloud_media(id,kind,object_key,sha,bytes,mime,expires)
                VALUES(?,?,?,?,?,?,?)""",(uid,row["kind"],key,row["sha"],row["bytes"],mime,row["expires"]))
            db.commit()  # An interrupted upload is discoverable before any network writes.
            receipt=db.execute("SELECT * FROM cloud_media WHERE id=?",(uid,)).fetchone()
            if receipt is None or receipt["object_key"]!=key:
                raise Failure(409,"Stored identity conflicts with the upload")
            local=self.archive.files/uid
            head=self._head(key)
            uploaded=head is None
            if uploaded:
                if not local.is_file() or local.stat().st_size!=row["bytes"] or sha_file(local)!=row["sha"]:
                    raise Failure(503,"Verified original is unavailable in staging and private storage")
                self._upload(local,key,row["sha"],row["bytes"],mime,
                    {"sha256":row["sha"],"archive":self.instance,"expires":str(row["expires"])})
                head=self._head(key)
            self._validate_head(head,row["bytes"],row["sha"],mime)
            if head.get("Metadata",{}).get("archive")!=self.instance:
                raise Failure(409,"Storage object belongs to a different archive instance")
            if uploaded or (not receipt["verified"]) or receipt["etag"]!=_etag(head):
                self._verify_remote(key,head,row["bytes"],row["sha"],mime)
            if row["expires"]<=now():
                raise Failure(410,"Capture expired while being verified")
            db.execute("UPDATE cloud_media SET verified=1,etag=? WHERE id=?",
                       (_etag(head),uid))
            db.commit()
            if local.exists():
                local.unlink()
                sync_dir(self.archive.files)

    def _receipt(self, uid):
        if not isinstance(uid,str) or not UID.fullmatch(uid):
            raise Failure(404,"Missing original")
        with self.db() as db:
            row=db.execute("""SELECT m.* FROM cloud_media m JOIN archive_uploads a ON a.id=m.id
                WHERE m.id=? AND m.verified=1 AND a.state='complete'""",(uid,)).fetchone()
        if row is None: raise Failure(404,"Verified original unavailable")
        if row["expires"]<=now(): raise Failure(410,"Capture has expired")
        return row

    def head(self, uid):
        row=self._receipt(uid)
        return {"ContentLength":row["bytes"],"ContentType":row["mime"],"sha256":row["sha"]}

    def read(self, uid, start=0, length=None):
        row=self._receipt(uid)
        total=row["bytes"]
        if length is None: length=total-start
        if type(start)!=int or type(length)!=int or start<0 or length<=0 or start+length>total:
            raise Failure(416,"Invalid capture range")
        args={"Key":row["object_key"],"IfMatch":'"'+row["etag"]+'"'}
        partial=start!=0 or length!=total
        if partial: args["Range"]=f"bytes={start}-{start+length-1}"
        result=self._call(self.s3.get_object,**args)
        body=result["Body"]
        if result.get("ContentLength")!=length or partial and result.get("ContentRange")!=f"bytes {start}-{start+length-1}/{total}":
            body.close()
            raise Failure(503,"Private storage returned an inconsistent byte range")
        return body

    def _objects(self, prefix):
        token=None
        seen=set()
        while True:
            args={"Prefix":prefix,"MaxKeys":1000}
            if token: args["ContinuationToken"]=token
            page=self._call(self.s3.list_objects_v2,**args)
            for item in page.get("Contents",()):
                if item["Key"].startswith(prefix): yield item["Key"]
            if not page.get("IsTruncated"): break
            token=page.get("NextContinuationToken")
            if not token or token in seen: raise Failure(503,"Storage returned invalid object pagination")
            seen.add(token)

    def _expire(self, t):
        with self.db() as db:
            due=db.execute("SELECT id,object_key FROM cloud_media WHERE expires<=? LIMIT 500",(t,)).fetchall()
        for row in due:
            with self.stripe(row["id"]), self.db() as db:
                if not MEDIA_KEY.fullmatch(row["object_key"]): raise Failure(503,"Malformed stored media key")
                self._call(self.s3.delete_object,Key=row["object_key"])
                db.execute("DELETE FROM cloud_media WHERE id=? AND object_key=? AND expires<=?",(row["id"],row["object_key"],t))
        # Includes interrupted uploads and media orphaned by restoring an older snapshot.
        # Only immutable, expired keys belonging to this archive can be collected.
        removed=0
        for key in self._objects(MEDIA):
            match=MEDIA_KEY.fullmatch(key)
            if not match: continue
            expiration=int(match[1])
            if expiration>t: break  # Keys sort by fixed-width UTC expiry.
            head=self._head(key)
            if head is None: continue
            meta=head.get("Metadata",{})
            if meta.get("archive")!=self.instance or meta.get("sha256")!=match[4] or meta.get("expires")!=str(expiration): continue
            self._call(self.s3.delete_object,Key=key)
            removed+=1
            if removed>=500: break
        return len(due)+removed

    def _snapshot_file(self,path):
        # An attached read transaction sees a consistent catalog and copies large selected WAV
        # BLOBs in SQLite. The new DB has never contained credentials or unfinished transfers.
        with closing(sqlite3.connect(path)) as snap, snap:
            snap.execute("ATTACH DATABASE ? AS src",(str(self.archive.path),))
            snap.execute("BEGIN")
            tables=("cloud_identity","cloud_media","clips","observations","archive_settings","archive_uploads")
            for table in tables:
                ddl=snap.execute("SELECT sql FROM src.sqlite_master WHERE type='table' AND name=?",(table,)).fetchone()
                if not ddl: raise Failure(503,"Incomplete archive schema for backup")
                snap.execute(ddl[0])
                cond=""
                if table=="cloud_media": cond=" WHERE verified=1"
                if table=="archive_settings": cond=" WHERE name IN ('quota','floor')"
                if table=="archive_uploads":
                    cond=" WHERE state='complete' AND (kind='clip' OR id IN (SELECT id FROM main.cloud_media))"
                snap.execute(f"INSERT INTO main.{table} SELECT * FROM src.{table}{cond}")
            for ddl, in snap.execute("""SELECT sql FROM src.sqlite_master
                WHERE type='index' AND name IN('chronology','cloud_expiry','archive_dates') AND sql IS NOT NULL""").fetchall():
                snap.execute(ddl)
            if snap.execute("PRAGMA main.quick_check").fetchone()[0]!="ok":
                raise Failure(503,"SQLite backup failed its consistency check")

    def _catalogs(self):
        # Only a commit marker written AFTER byte-for-byte verification may enter rotation.
        final=[key[:-3] for key in self._objects(SNAPS)
               if key.endswith(".ok") and SNAP_KEY.fullmatch(key[:-3])]
        return sorted(final,reverse=True)

    def _backup(self,t):
        day=datetime.fromtimestamp(t/1000,timezone.utc).strftime("%Y%m%d")
        catalogs=self._catalogs()
        if catalogs:
            newest=self._head(catalogs[0])
            if not newest or newest.get("Metadata",{}).get("archive")!=self.instance:
                raise Failure(409,"Bucket has a different catalog identity. Restore its catalog before rotating backups.")
            if SNAP_KEY.fullmatch(catalogs[0])[1]>=day:
                self._rotate(catalogs)
                return False
        with tempfile.TemporaryDirectory(dir=self.archive.root,prefix="catalog-snapshot-") as tmp:
            path=Path(tmp)/"clips.sqlite3"
            self._snapshot_file(path)
            sha=sha_file(path);size=path.stat().st_size;mime="application/vnd.sqlite3"
            key=f"{SNAPS}{day}-{sha}.sqlite3"
            self._upload(path,key,sha,size,mime,{"sha256":sha,"archive":self.instance})
            self._verify_remote(key,self._head(key),size,sha,mime)
            self._call(self.s3.put_object,Key=key+".ok",Body=sha.encode("ascii"),ContentType="text/plain",
                Metadata={"sha256":sha,"archive":self.instance})
        self._rotate(self._catalogs())
        return True

    def _rotate(self,catalogs):
        if len(catalogs)<2: return
        keep=set(catalogs[:2])
        # Only rotate objects older than both committed copies. A current interrupted
        # snapshot remains recoverable until two later snapshots have been committed.
        cutoff=SNAP_KEY.fullmatch(catalogs[1])[1]
        for key in self._objects(SNAPS):
            base=key[:-3] if key.endswith(".ok") else key
            match=SNAP_KEY.fullmatch(base)
            if not match or base in keep or match[1]>=cutoff: continue
            candidate=self._head(key)
            if candidate and candidate.get("Metadata",{}).get("archive")==self.instance:
                self._call(self.s3.delete_object,Key=key)

    def maintain(self,t=None):
        t=now() if t is None else t
        with self.lock("maintenance"):
            expired=self._expire(t)
            self.archive.trim(t)
            with self.db() as db:
                pending=db.execute("""SELECT a.id FROM archive_uploads a LEFT JOIN cloud_media m ON a.id=m.id
                    WHERE a.state='complete' AND a.kind IN('segment','journal') AND a.expires>?
                    AND COALESCE(m.verified,0)=0 ORDER BY a.started LIMIT 64""",(t,)).fetchall()
            offloaded=0; errors=[]
            for row in pending:
                try:
                    self.ensure(row["id"]);offloaded+=1
                except Failure as error:
                    errors.append(error)
            # Reconcile crash after receipt commit but before staging unlink.
            for local in self.archive.files.iterdir():
                if not local.is_file() or not UID.fullmatch(local.name): continue
                with self.db() as db:
                    verified=db.execute("SELECT verified FROM cloud_media WHERE id=?",(local.name,)).fetchone()
                if verified and verified[0]:
                    try: self.ensure(local.name)
                    except Failure as error: errors.append(error)
            made=self._backup(t)
            if errors: raise Failure(503,"Some originals await a verified offload") from errors[0]
            return {"expired":expired,"offloaded":offloaded,"snapshotCreated":made}

    def restore_latest(self, destination):
        """Explicit offline operation. Destination must be a new database path."""
        dest=Path(destination);dest.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        if dest.exists(): raise Failure(409,"Restore requires a new database path")
        catalogs=self._catalogs()
        if not catalogs: raise Failure(404,"No catalog snapshot is available")
        last_error=None
        for key in catalogs[:2]:
            try:
                head=self._head(key);sha=SNAP_KEY.fullmatch(key)[2]
                if head is None: raise Failure(503,"Stored snapshot disappeared")
                self._validate_head(head,head["ContentLength"],sha,"application/vnd.sqlite3")
                result=self._call(self.s3.get_object,Key=key,IfMatch=head["ETag"])
                with tempfile.NamedTemporaryFile(dir=dest.parent,prefix=".restoring-") as staged:
                    with closing(result["Body"]) as stream:
                        n,digest=_stream_digest(stream,staged)
                    staged.flush();os.fsync(staged.fileno())
                    if n!=head["ContentLength"] or digest!=sha: raise Failure(503,"Snapshot digest failed")
                    with closing(sqlite3.connect(Path(staged.name).as_uri()+"?mode=ro",uri=True)) as db:
                        identity=db.execute("SELECT instance FROM cloud_identity WHERE slot=1").fetchone()
                        if not identity or identity[0]!=head["Metadata"].get("archive") or db.execute("PRAGMA quick_check").fetchone()[0]!="ok":
                            raise Failure(503,"Snapshot identity or SQLite integrity failed")
                    os.link(staged.name,dest)
                    sync_dir(dest.parent)
                return key
            except (Failure,OSError,sqlite3.Error) as exc:
                last_error=exc
        raise Failure(503,"Could not restore either retained catalog snapshot") from last_error
