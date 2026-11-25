"""Verified, content-addressed second-disk snapshots for Total Recall Desk."""
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
import stat
import tempfile
import uuid

from recall_archive import archive_lock, sync_dir

REPO = "TotalRecall-backup"
FMT = "total-recall-backup/1"
HEX = re.compile(r"[0-9a-f]{64}")
IDENT = re.compile(r"[A-Za-z0-9_-]{1,128}")
SNAP = re.compile(r"[0-9]{8}T[0-9]{12}Z-[0-9a-f]{12}")
RESERVE = 64 * 1024**2
TABLES = {
    "clips": "id started duration samples sha audio title transcript category reason metadata feedback",
    "observations": "clip_id metadata imported",
    "clip_original_sizes": "id bytes",
    "archive_uploads": "id kind metadata sha bytes offset state file created touched started expires journal",
    "archive_settings": "name value",
}

def safe(path, *, directory=False, absent=False):
    path = Path(path).expanduser()
    if ".." in path.parts:
        raise ValueError("Parent traversal is not allowed")
    path = path.absolute()
    for component in (*reversed(path.parents), path):
        if component.is_symlink():
            raise ValueError("A backup path contains a symbolic link")
    if absent and not path.exists():
        return path
    if directory and not path.is_dir():
        raise ValueError("The selected directory is unavailable")
    if not directory and not path.is_file():
        raise ValueError("Required file is unavailable: " + path.name)
    return path

def separate(a, b):
    if a == b or a in b.parents or b in a.parents:
        raise ValueError("Choose two separate directories outside one another")

def subdir(path, create=False):
    if create:
        safe(path, directory=True, absent=True).mkdir(mode=0o700, exist_ok=True)
    return safe(path, directory=True)

def rd(path):
    path = safe(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    if not stat.S_ISREG(os.fstat(fd).st_mode):
        os.close(fd)
        raise ValueError("Backup input must be a regular file")
    return os.fdopen(fd, "rb")

def read_json(path):
    with rd(path) as source:
        if os.fstat(source.fileno()).st_size > 1024**2:
            raise ValueError("Invalid backup metadata")
        result = json.load(source)
    if not isinstance(result, dict):
        raise ValueError("Invalid backup metadata")
    return result

def write_json(path, value):
    fd, name = tempfile.mkstemp(dir=path.parent, prefix=".writing-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(value, out, sort_keys=True)
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
        sync_dir(path.parent)
    finally:
        Path(name).unlink(missing_ok=True)

@contextmanager
def exclusive(path, shared=False):
    safe(path, absent=True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise ValueError("Invalid archive lock")
        fcntl.flock(fd, fcntl.LOCK_SH if shared else fcntl.LOCK_EX)
        yield
    finally:
        os.close(fd)

def policy(root):
    path = root / "backup-policy.json"
    safe(path, absent=True)
    if path.exists():
        value = read_json(path)
        if not isinstance(value.get("targets"), dict) or not re.fullmatch("[0-9a-f]{32}", str(value.get("archive"))):
            raise ValueError("Invalid local backup configuration")
        return value
    return dict(archive=uuid.uuid4().hex, targets={})

def destination(root, dest, config):
    dest = safe(dest, directory=True)
    separate(root, dest)
    base = dest / REPO
    expected = config["targets"].get(str(dest))
    if expected:
        # A disappeared removable volume may leave a perfectly valid empty mountpoint behind.
        # A known target may never be initialized again at that pathname.
        folder = subdir(base)
        marker = read_json(folder / "drive.json")
        if marker != {"format": FMT, "archive": config["archive"], "drive": expected}:
            raise ValueError("The selected backup drive is absent or has changed")
    else:
        if base.exists() or base.is_symlink():
            folder = subdir(base)
            marker = read_json(folder / "drive.json")
            if marker.get("format") != FMT or marker.get("archive") != config["archive"] or not re.fullmatch("[0-9a-f]{32}", str(marker.get("drive"))):
                raise ValueError("This backup belongs to another archive")
        else:
            # Publish the identity and structure together. A failed initialization is never trusted.
            temp = Path(tempfile.mkdtemp(dir=dest, prefix=".recall-initialize-"))
            try:
                marker = dict(format=FMT, archive=config["archive"], drive=uuid.uuid4().hex)
                write_json(temp / "drive.json", marker)
                for name in ("objects", "snapshots"):
                    (temp / name).mkdir(mode=0o700)
                sync_dir(temp)
                temp.rename(base)
                sync_dir(dest)
            finally:
                if temp.exists():
                    shutil.rmtree(temp)
            folder = subdir(base)
        config["targets"][str(dest)] = marker["drive"]
        write_json(root / "backup-policy.json", config)
    return folder

def db_read(path, immutable=True):
    safe(path)
    if not immutable:
        for suffix in ("-wal", "-shm", "-journal"):
            safe(Path(str(path) + suffix), absent=True)
    connection = sqlite3.connect(path.as_uri() + "?mode=ro" + ("&immutable=1" if immutable else ""), uri=True)
    connection.execute("PRAGMA trusted_schema=OFF")
    return connection

def validate_schema(db, published):
    objects = list(db.execute("SELECT type,name,tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"))
    tables = {name for kind, name, _ in objects if kind == "table"}
    if not {"clips", "observations"} <= tables:
        raise ValueError("Required capture tables are missing")
    for table in tables & TABLES.keys():
        columns = [r[1] for r in db.execute("PRAGMA table_info(" + table + ")")]
        if columns != TABLES[table].split():
            raise ValueError("Unrecognized capture schema")
    if published and (tables - TABLES.keys() or any(kind not in ("table", "index") or parent not in TABLES for kind, _, parent in objects)):
        raise ValueError("Unexpected objects in the snapshot database")
    return tables, objects

def sanitize(db):
    db.execute("PRAGMA trusted_schema=OFF")
    db.execute("PRAGMA secure_delete=ON")
    tables, objects = validate_schema(db, False)
    for typ in ("trigger", "view", "table"):
        for kind, name, _ in objects:
            if typ == kind and (kind != "table" or name not in TABLES):
                escaped = name.replace('"', '""')
                db.execute(f'DROP {typ} "{escaped}"')
    if "archive_uploads" in tables:
        db.execute("DELETE FROM archive_uploads WHERE state!='complete'")
        db.execute("CREATE INDEX IF NOT EXISTS recall_backup_sha ON archive_uploads(sha)")
    if "archive_settings" in tables:
        db.execute("DELETE FROM archive_settings WHERE name NOT IN ('quota','floor')")
    db.commit()
    db.execute("PRAGMA journal_mode=DELETE")
    db.execute("VACUUM")
    validate_schema(db, True)
    if db.execute("PRAGMA quick_check").fetchone() != ("ok",):
        raise ValueError("Archive database failed its integrity check")

def free(path, size, limits=None):
    limits = limits or {}
    if shutil.disk_usage(path).free - limits.get("reserveBytes", RESERVE) < size:
        raise ValueError("Insufficient free space for a complete backup or restore")

def file_hash(path):
    with rd(path) as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

def media(db):
    if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='archive_uploads'").fetchone():
        return
    for uid, kind, sha, size, name, state, off in db.execute("SELECT id,kind,sha,bytes,file,state,offset FROM archive_uploads"):
        if not isinstance(uid, str) or not IDENT.fullmatch(uid) or not isinstance(sha, str) or not HEX.fullmatch(sha) or \
                type(size) != int or size <= 0 or off != size or state != "complete" or name != "archive-files/" + uid or kind not in ("clip", "segment", "journal"):
            raise ValueError("Invalid media row in snapshot database")
        if kind != "clip":
            yield uid, sha, size

def verified_copy(source, target, sha, size):
    # Private stages and immutable objects use a checked path, nonce and atomic rename.
    with rd(source) as src:
        if os.fstat(src.fileno()).st_size != size:
            raise ValueError("Capture byte count differs from the catalog")
        fd, temp = tempfile.mkstemp(dir=target.parent, prefix=".copy-")
        try:
            with os.fdopen(fd, "wb") as out:
                shutil.copyfileobj(src, out, 1024**2)
                out.flush()
                os.fsync(out.fileno())
            if file_hash(Path(temp)) != sha:
                raise ValueError("A copied recording or catalog failed SHA-256 verification")
            os.replace(temp, target)
            sync_dir(target.parent)
        finally:
            Path(temp).unlink(missing_ok=True)

def usage(path):
    total = 0
    for base, dirs, names in os.walk(path, followlinks=False):
        for name in dirs:
            subdir(Path(base) / name)
        for name in names:
            total += safe(Path(base) / name).stat().st_size
    return total

def limits_for(repo):
    file = safe(repo/"limits.json", absent=True)
    limits = read_json(file) if file.exists() else {"reserveBytes": RESERVE, "quotaBytes": 0}
    if any(type(limits.get(k)) != int or limits[k] < 0 for k in ("reserveBytes", "quotaBytes")):
        raise ValueError("Invalid backup storage limits")
    return limits

def configure_limits(root, directory, quota_bytes=0, reserve_bytes=RESERVE):
    root = safe(root, directory=True)
    if any(type(v) != int or v < 0 for v in (quota_bytes, reserve_bytes)):
        raise ValueError("Storage limits must be nonnegative byte counts")
    with exclusive(root / "backup.lock"):
        repo = destination(root, directory, policy(root))
        with exclusive(repo / "backup.lock"):
            write_json(repo / "limits.json", dict(quotaBytes=quota_bytes, reserveBytes=reserve_bytes))

def blob(objects, sha, create=False):
    return subdir(objects / sha[:2], create=create) / sha

def snapshots(folder):
    out = []
    for p in folder.iterdir():
        if SNAP.fullmatch(p.name):
            out.append(subdir(p))
    return sorted(out, reverse=True)

def cleanup(repo):
    folder = subdir(repo / "snapshots")
    versions = snapshots(folder)
    for p in versions[2:]:
        shutil.rmtree(p)
    sync_dir(folder)
    # Indexed membership uses bounded memory even when a seven-year catalog has millions of rows.
    catalogs = []
    try:
        for p in versions[:2]:
            db = db_read(p / "clips.sqlite3")
            catalogs.append(db)
        media_dbs = [d for d in catalogs if d.execute("SELECT 1 FROM sqlite_master WHERE name='archive_uploads'").fetchone()]
        for bucket in subdir(repo / "objects").iterdir():
            if not re.fullmatch("[0-9a-f]{2}", bucket.name):
                continue
            for path in subdir(bucket).iterdir():
                if HEX.fullmatch(path.name) and not any(d.execute(
                        "SELECT 1 FROM archive_uploads WHERE sha=? AND kind IN ('segment','journal') LIMIT 1", (path.name,)).fetchone() for d in media_dbs):
                    safe(path).unlink()
    finally:
        for db in catalogs:
            db.close()

def _backup(root, dest, config):
    repo = destination(root, dest, config)
    folder = subdir(repo / "snapshots")
    objects = subdir(repo / "objects")
    with exclusive(repo / "backup.lock"):
        for old in folder.glob(".building-*"):
            shutil.rmtree(subdir(old))
        limits = limits_for(repo)
        staged = Path(tempfile.mkdtemp(dir=folder, prefix=".building-"))
        try:
            source_path = safe(root / "clips.sqlite3")
            database = staged / "clips.sqlite3"
            with archive_lock(root), closing(db_read(source_path, immutable=False)) as source:
                pages = source.execute("PRAGMA page_count").fetchone()[0] * source.execute("PRAGMA page_size").fetchone()[0]
                free(repo, pages * 2, limits)
                if limits["quotaBytes"] and usage(repo) + pages > limits["quotaBytes"]:
                    raise ValueError("Backup quota cannot fit the catalog")
                tables, objs = validate_schema(source, False)
                if source.execute("SELECT 1 FROM clips WHERE length(audio)=0 LIMIT 1").fetchone():
                    raise ValueError("This catalog has hosted originals. Use its verified object-storage snapshot.")
                # Keep unrecognized tables (potential future credentials) off the second disk.
                # Only a per-host private temporary SQLite backup can contain such rows.
                unknown = bool(tables - TABLES.keys() or any(k not in ("table", "index") for k, _, _ in objs))
                raw = None
                if unknown:
                    free(root, pages * 2)
                    fd, name = tempfile.mkstemp(dir=root, prefix=".backup-catalog-")
                    os.close(fd)
                    raw = Path(name)
                try:
                    with closing(sqlite3.connect(raw or database)) as db:
                        source.backup(db)
                        sanitize(db)
                        if raw:
                            with closing(sqlite3.connect(database)) as clean:
                                db.backup(clean)
                finally:
                    if raw:
                        raw.unlink(missing_ok=True)
            os.chmod(database, 0o600)
            with rd(database) as f:
                os.fsync(f.fileno())
            # All completed media are immutable. If source maintenance removes an item mid-copy,
            # abort the snapshot and retry from the next database view without holding up uploads.
            db_hash = file_hash(database)
            remaining = limits["quotaBytes"] - usage(repo) if limits["quotaBytes"] else None
            copied = reused = 0
            cachefile = safe(repo / "verified.sqlite3", absent=True)
            with closing(sqlite3.connect(cachefile)) as cache, closing(db_read(database)) as db:
                os.chmod(cachefile, 0o600)
                cache.execute("CREATE TABLE IF NOT EXISTS verified(sha TEXT PRIMARY KEY,inode INTEGER,size INTEGER,ctime INTEGER)")
                for uid, sha, size in media(db):
                    target = safe(blob(objects, sha, create=True), absent=True)
                    if target.exists():
                        info = target.stat()
                        fp = (info.st_ino, info.st_size, info.st_ctime_ns)
                        cached = cache.execute("SELECT inode,size,ctime FROM verified WHERE sha=?", (sha,)).fetchone()
                        if info.st_size == size and (cached == fp or file_hash(target) == sha):
                            cache.execute("INSERT OR REPLACE INTO verified VALUES(?,?,?,?)", (sha, *fp))
                            reused += 1
                            continue
                    free(repo, size, limits)
                    if remaining is not None and remaining < size:
                        raise ValueError("Backup media exceed the configured quota")
                    verified_copy(root / "archive-files" / uid, target, sha, size)
                    info = target.stat()
                    cache.execute("INSERT OR REPLACE INTO verified VALUES(?,?,?,?)", (sha, info.st_ino, info.st_size, info.st_ctime_ns))
                    if remaining is not None:
                        remaining -= size
                    copied += 1
                cache.commit()
            clock = datetime.now(timezone.utc)
            name = clock.strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:12]
            manifest = dict(format=FMT, archive=config["archive"], created=clock.isoformat(), catalogSha256=db_hash,
                            catalogBytes=database.stat().st_size, mediaCount=copied + reused)
            write_json(staged / "manifest.json", manifest)
            staged.rename(folder / name)
            sync_dir(folder)
            cleanup(repo)
            return dict(snapshot=str(folder / name), createdObjects=copied, reusedObjects=reused, mediaCount=copied+reused)
        finally:
            if staged.exists():
                shutil.rmtree(staged)

def create_backup(root, destination):
    root = safe(root, directory=True)
    with exclusive(root / "backup.lock"):
        config = policy(root)
        dest = safe(destination, directory=True)
        result = _backup(root, dest, config)
        if config.get("daily") == str(dest):
            config["lastDate"] = datetime.now(timezone.utc).date().isoformat()
            write_json(root / "backup-policy.json", config)
        return result

def configure_daily(root, directory):
    root = safe(root, directory=True)
    with exclusive(root / "backup.lock"):
        config = policy(root)
        if directory is None:
            config.pop("daily", None)
        else:
            dest = safe(directory, directory=True)
            destination(root, dest, config)
            config["daily"] = str(dest)
        config.pop("lastDate", None)
        write_json(root / "backup-policy.json", config)

def run_daily(root):
    root = safe(root, directory=True)
    with exclusive(root / "backup.lock"):
        config = policy(root)
        today = datetime.now(timezone.utc).date().isoformat()
        if not config.get("daily") or config.get("lastDate") == today:
            return None
        if config["daily"] not in config["targets"]:
            raise ValueError("Scheduled drive identity is missing")
        result = _backup(root, safe(config["daily"], directory=True), config)
        config["lastDate"] = today
        write_json(root / "backup-policy.json", config)
        return result

def restore_backup(snapshot_dir, empty_destination):
    snapshot = safe(snapshot_dir, directory=True)
    dest = safe(empty_destination, directory=True, absent=True)
    parent = safe(dest.parent, directory=True)
    if not SNAP.fullmatch(snapshot.name) or snapshot.parent.name != "snapshots":
        raise ValueError("Choose a named snapshot directory")
    repo = subdir(snapshot.parent.parent)
    separate(repo, dest)
    if dest.exists() and any(dest.iterdir()):
        raise ValueError("Restore requires an empty destination")
    with exclusive(repo / "backup.lock", shared=True):
        marker = read_json(repo / "drive.json")
        m = read_json(snapshot / "manifest.json")
        if m.get("format") != FMT or marker.get("format") != FMT or m.get("archive") != marker.get("archive") or not re.fullmatch("[0-9a-f]{32}", str(m.get("archive"))) or \
                not HEX.fullmatch(str(m.get("catalogSha256"))) or type(m.get("catalogBytes")) != int or m["catalogBytes"] <= 0:
            raise ValueError("Invalid backup identity or catalog manifest")
        stage = Path(tempfile.mkdtemp(dir=parent, prefix=".recall-restore-"))
        try:
            free(parent, m["catalogBytes"])
            catalog = stage / "clips.sqlite3"
            verified_copy(snapshot / "clips.sqlite3", catalog, m["catalogSha256"], m["catalogBytes"])
            with closing(db_read(catalog)) as db:
                validate_schema(db, True)
                if db.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                    raise ValueError("Backup database integrity failure")
                for rowid, expected in db.execute("SELECT rowid,sha FROM clips"):
                    with db.blobopen("clips", "audio", rowid, readonly=True) as audio:
                        check = hashlib.sha256()
                        while block := audio.read(1024**2):
                            check.update(block)
                        if check.hexdigest() != expected:
                            raise ValueError("Selected WAV bytes failed verification")
                out = subdir(stage / "archive-files", create=True)
                count = 0
                objects = subdir(repo / "objects")
                for uid, sha, size in media(db):
                    free(parent, size)
                    verified_copy(blob(objects, sha), out / uid, sha, size)
                    count += 1
                if count != m.get("mediaCount"):
                    raise ValueError("Backup media inventory is incomplete")
            # Pairing tokens, auth, schedules and credentials are deliberately outside this catalog.
            write_json(stage / "backup-policy.json", dict(archive=m["archive"], targets={}))
            sync_dir(stage)
            os.replace(stage, dest)
            sync_dir(parent)
            return dict(destination=str(dest), mediaCount=count, catalogSha256=m["catalogSha256"])
        finally:
            if stage.exists():
                shutil.rmtree(stage)

def daily_destination(root):
    """Return the configured folder even when its external disk is temporarily offline."""
    return policy(safe(root, directory=True)).get("daily")
