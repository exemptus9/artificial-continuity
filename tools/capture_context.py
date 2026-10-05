#!/usr/bin/env python3
"""Additive, loopback-only capture/context bridge for Continuity.

Canonical project/source metadata remains in context_store.py's existing
ContinuityContext/1 journal. Exact input objects are separate immutable files.
SQLite/FTS5 is a disposable index. No model, shell, remote fetch, automatic
publication, private-vault decryption, or browser synchronization is performed.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import sqlite3
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import context_store as C

FORMAT = "ContinuityCaptureRequest/1"
SOURCE_FORMAT = "ContinuityCaptureRecord/1"
MAX_TEXT_BYTES = 1_000_000
MAX_HTTP_BYTES = 2_000_000
CLASSES = {"SOURCE_FACT", "USER_ASSERTION", "MODEL_INFERENCE", "MODEL_SUMMARY", "HYPOTHESIS", "UNKNOWN"}
REQUEST_FIELDS = {"format", "capture_id", "text", "title", "project_id", "visibility", "source_kind", "speaker", "observed_at", "device_id", "mechanism", "source_app", "source_url", "origin"}
NAMESPACE = uuid.UUID("032c099e-c6fc-49e0-b3aa-af15c908052a")
SHA = re.compile(r"^[0-9a-f]{64}$")

def now():
    return datetime.now(timezone.utc).isoformat(timespec="microseconds")

def raw_hash(data):
    return hashlib.sha256(data).hexdigest()

def bounded_text(value, name, maximum=200):
    if not isinstance(value, str) or len(value) > maximum or "\x00" in value:
        raise C.ContextError(f"Invalid {name}.")
    return value

def private_directory(path):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise C.ContextError("Private storage must not traverse symlinks.")
    if any((p / ".git").exists() for p in (path, *path.parents)):
        raise C.ContextError("Store private data outside source repositories.")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)
    return path

@dataclass(frozen=True)
class Principal:
    role: str
    projects: frozenset = frozenset()

    def allows_source(self, source):
        if self.role == "owner":
            return True
        return (self.role == "agent" and source.get("visibility") == "internal"
                and source.get("project_id") in self.projects)

class Store:
    def __init__(self, root):
        self.root = private_directory(root)
        self.journal = self.root / "Context_Store.json"
        self.objects = private_directory(self.root / "objects")
        self.outbox = private_directory(self.root / "outbox")
        self.index = self.root / "search.sqlite"
        if self.journal.is_symlink() or self.index.is_symlink():
            raise C.ContextError("Refusing symlink state/index files.")
        self.state()

    @staticmethod
    def initialize(root, original):
        raw = Path(original).read_bytes()
        C.load_store(C.parse(raw))
        root = private_directory(root)
        objects = private_directory(root / "objects")
        digest = raw_hash(raw)
        target = root / "Context_Store.json"
        if target.exists():
            raise C.ContextError("Existing journal is never replaced by init.")
        C.atomic_write(objects / digest, raw, replace=False)
        C.atomic_write(target, raw, replace=False)
        C.json_write(root / "intake.json", {"format": "ContinuityIntake/1", "original_journal_sha256": digest, "imported_at": now()}, replace=False)
        return Store(root)

    def state(self):
        return C.load_store(C.read_json(self.journal))

    def object(self, digest):
        if not isinstance(digest, str) or not SHA.fullmatch(digest):
            raise C.ContextError("Invalid object checksum.")
        path = self.objects / digest
        if path.is_symlink() or not path.is_file():
            raise C.ContextError("Original object missing or unsafe; restore it before continuing.")
        raw = path.read_bytes()
        if raw_hash(raw) != digest:
            raise C.ContextError("Original object checksum mismatch.")
        return raw

    def put_object(self, raw):
        digest = raw_hash(raw)
        path = self.objects / digest
        if path.exists():
            if self.object(digest) != raw:
                raise C.ContextError("Object collision.")
        else:
            C.atomic_write(path, raw, replace=False)
        return digest

    def request(self, value):
        if not isinstance(value, dict) or not set(value) <= REQUEST_FIELDS or value.get("format") != FORMAT:
            raise C.ContextError("Unsupported capture request fields/format.")
        try:
            cid = str(uuid.UUID(value["capture_id"]))
        except (KeyError, ValueError, TypeError, AttributeError) as exc:
            raise C.ContextError("Provide a stable UUID capture_id before sending.") from exc
        text = value.get("text")
        if not isinstance(text, str) or not text.strip() or "\x00" in text:
            raise C.ContextError("Capture requires nonempty text.")
        try:
            raw = text.encode("utf-8")
        except UnicodeError as exc:
            raise C.ContextError("Capture must be valid UTF-8.") from exc
        if len(raw) > MAX_TEXT_BYTES:
            raise C.ContextError("Text exceeds the 1 MB capture limit; retain it as a file.")
        visibility = value.get("visibility", "private")
        if visibility not in {"private", "internal"}:
            raise C.ContextError("Capture cannot approve public/curated visibility.")
        kind = value.get("source_kind", "UNKNOWN")
        if kind not in CLASSES:
            raise C.ContextError("Invalid source_kind.")
        observed = value.get("observed_at")
        if observed is not None:
            C.timestamp(observed)
        project = value.get("project_id")
        if project is not None and not C._id(project):
            raise C.ContextError("Invalid project ID.")
        speaker = value.get("speaker", "unknown")
        if speaker not in {"user", "assistant", "mixed", "unknown"}:
            raise C.ContextError("Invalid supplied speaker classification.")
        origin = value.get("origin", {})
        if not isinstance(origin, dict) or len(C.canonical_bytes(origin)) > 16_000:
            raise C.ContextError("Origin metadata must be a small JSON object.")
        source_url = bounded_text(value.get("source_url", ""), "source_url", 2000)
        if source_url and urlsplit(source_url).scheme not in {"http", "https"}:
            raise C.ContextError("Source URL must be HTTP(S); it is recorded, never fetched.")
        result = {"format": FORMAT, "capture_id": cid, "text": text,
                  "title": bounded_text(value.get("title", "Untitled capture"), "title"),
                  "project_id": project, "visibility": visibility, "source_kind": kind,
                  "speaker": speaker, "observed_at": observed, "origin": origin,
                  "source_url": source_url}
        for name in ("device_id", "mechanism", "source_app"):
            result[name] = bounded_text(value.get(name, "unknown"), name)
        return result

    def spool(self, request):
        value = self.request(request)
        path = self.outbox / (value["capture_id"] + ".json")
        if path.exists():
            if C.read_json(path) != value:
                raise C.ContextError("Outbox UUID already contains different input.")
        else:
            C.json_write(path, value, replace=False)
        return {"capture_id": value["capture_id"], "queued": True, "consent_to_ingest": False}

    def accept(self, request, approved=False, actor="owner"):
        if not approved:
            raise C.ContextError("Fresh approval for this private copy is required.")
        value = self.request(request)
        if actor not in {"owner", "capture-adapter"}:
            raise C.ContextError("Agents cannot write canonical project state or approve source facts.")
        if actor == "capture-adapter" and (value["visibility"] != "private" or value["source_kind"] == "SOURCE_FACT"):
            raise C.ContextError("Capture adapters may append private, unverified input only.")
        data, head = self.state()
        if value["project_id"] is not None and value["project_id"] not in {p["id"] for p in data["projects"]}:
            raise C.ContextError("Unknown project; use the unsorted inbox or a registered project.")
        sid = "CAPTURE." + value["capture_id"]
        request_hash = C.digest(value)
        existing = next((s for s in data["sources"] if s["id"] == sid), None)
        if existing is not None:
            if existing.get("request_sha256") != request_hash:
                raise C.ContextError("Capture UUID conflict; original retained.")
            self.object(existing["sha256"])
            return {"source_id": sid, "capture_id": value["capture_id"], "sha256": existing["sha256"], "journal_committed": True, "already_saved": True, "indexed": False}
        raw = value["text"].encode("utf-8")
        digest = self.put_object(raw)  # A failed journal write leaves a recoverable orphan, never false success.
        received = now()
        source = {k: v for k, v in value.items() if k not in {"format", "text", "title"}}
        source.update({"id": sid, "format": SOURCE_FORMAT, "name": value["title"],
                       "locator": "continuity-object:sha256:" + digest, "media_type": "text/plain; charset=utf-8",
                       "sha256": digest, "byte_count": len(raw), "request_sha256": request_hash,
                       "received_at": received, "creator_role": actor,
                       "review_state": "unreviewed", "consent_state": "private-copy-authorized",
                       "publication_status": "unapproved", "approved_for_publication_at": None,
                       "verification": "Exact input preserved; truth, authorship, observation time and imported consent are not independently verified."})
        proposal = {"id": "CAPTURE_EVENT." + value["capture_id"], "at": received, "actor": actor,
                    "note": "CAPTURE_CREATED: append original input; no project status or decision changed.",
                    "evidence_grade": "C", "source_refs": [sid], "source_records": [source], "updates": [], "additions": []}
        candidate, _ = C.append(C.read_json(self.journal), proposal, head["store_sha256"], True)
        if len(json.dumps(candidate, ensure_ascii=False, indent=2).encode("utf-8")) + 1 > C.MAX_BYTES:
            raise C.ContextError("Journal would exceed 32 MB; retain the outbox/object and migrate deliberately.")
        C.apply_file(self.journal, proposal, head["store_sha256"], True)
        # Acknowledgement concerns authoritative storage, never falsely promises index success.
        return {"source_id": sid, "capture_id": value["capture_id"], "sha256": digest,
                "journal_committed": True, "already_saved": False, "indexed": False}

    def flush(self, approved=False):
        if not approved:
            raise C.ContextError("Explicitly approve outbox ingestion.")
        results = []
        for path in sorted(self.outbox.glob("*.json")):
            if path.is_symlink():
                raise C.ContextError("Unsafe outbox file.")
            receipt = self.accept(C.read_json(path), True)
            path.unlink()  # Removal occurs only after the verified authoritative commit.
            results.append(receipt)
        return results

    def rebuild_index(self):
        with C.locked(self.index):
            data, head = self.state()
            tmp = self.root / ("search-build-" + str(uuid.uuid4()) + ".sqlite")
            descriptor = os.open(tmp, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
            try:
                with sqlite3.connect(tmp) as db:
                    db.executescript("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL); CREATE TABLE records(id TEXT PRIMARY KEY,project_id TEXT,visibility TEXT NOT NULL,received_at TEXT NOT NULL,source_kind TEXT NOT NULL,sha256 TEXT NOT NULL); CREATE INDEX record_scope ON records(project_id,visibility,received_at); CREATE VIRTUAL TABLE search USING fts5(id UNINDEXED,title,body,tokenize='unicode61');")
                    count = 0
                    for source in data["sources"]:
                        if source.get("format") != SOURCE_FORMAT:
                            continue
                        raw = self.object(source["sha256"])
                        if len(raw) != source["byte_count"]:
                            raise C.ContextError("Source size mismatch.")
                        db.execute("INSERT INTO records VALUES(?,?,?,?,?,?)", (source["id"], source["project_id"], source["visibility"], source["received_at"], source["source_kind"], source["sha256"]))
                        db.execute("INSERT INTO search VALUES(?,?,?)", (source["id"], source["name"], raw.decode("utf-8")))
                        count += 1
                    db.execute("INSERT INTO meta VALUES('journal_sha256',?)", (head["store_sha256"],))
                if self.state()[1]["store_sha256"] != head["store_sha256"]:
                    raise C.ContextError("Journal moved while indexing; retry without changing originals.")
                with tmp.open("rb") as f:
                    os.fsync(f.fileno())
                os.replace(tmp, self.index)
                descriptor = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
                return {"indexed": count, "journal_sha256": head["store_sha256"]}
            finally:
                tmp.unlink(missing_ok=True)

    def ensure_index(self):
        head = self.state()[1]["store_sha256"]
        try:
            if self.index.is_symlink():
                raise C.ContextError("Unsafe index path.")
            with sqlite3.connect(f"file:{self.index}?mode=ro", uri=True) as db:
                indexed = db.execute("SELECT value FROM meta WHERE key='journal_sha256'").fetchone()
                if indexed and indexed[0] == head and db.execute("PRAGMA quick_check").fetchone()[0] == "ok":
                    return
        except sqlite3.DatabaseError:
            pass
        self.rebuild_index()

    def search(self, query, principal, project_id=None, limit=20, since=None, until=None):
        if principal.role not in {"owner", "agent"}:
            raise C.ContextError("This credential cannot retrieve context.")
        if not isinstance(query, str) or not 0 < len(query) <= 2000 or type(limit) is not int or not 1 <= limit <= 100:
            raise C.ContextError("Invalid query or limit.")
        terms = re.findall(r"\w+", query, flags=re.UNICODE)
        if not terms:
            return []
        if len(terms) > 32:
            raise C.ContextError("Use at most 32 lexical terms.")
        for stamp in (since, until):
            if stamp is not None:
                C.timestamp(stamp)
        self.ensure_index()
        # Escape every term; imported text and caller FTS syntax cannot become SQL/commands.
        match = " AND ".join('"' + t.replace('"', '""') + '"' for t in terms)
        clauses, params = ["search MATCH ?"], [match]
        if principal.role == "agent":
            if not principal.projects:
                return []
            clauses += ["r.visibility='internal'", "r.project_id IN (" + ",".join("?" for _ in principal.projects) + ")"]
            params.extend(sorted(principal.projects))
        if project_id is not None:
            clauses.append("r.project_id=?"); params.append(project_id)
        for stamp, operator in ((since, ">="), (until, "<=")):
            if stamp is not None:
                clauses.append("julianday(r.received_at)" + operator + "julianday(?)"); params.append(stamp)
        params.append(limit)
        sql = "SELECT r.id,search.title,snippet(search,2,'','', ' … ',24),r.project_id,r.visibility,r.source_kind,r.sha256 FROM search JOIN records r ON search.id=r.id WHERE " + " AND ".join(clauses) + " ORDER BY bm25(search),r.id LIMIT ?"
        with sqlite3.connect(f"file:{self.index}?mode=ro", uri=True) as db:
            rows = db.execute(sql, params).fetchall()
        # Reauthorize against the live journal; an old index never grants access.
        data, head = self.state(); sources = {s["id"]: s for s in data["sources"]}
        results = []
        for row in rows:
            source = sources.get(row[0])
            if source is None or not principal.allows_source(source):
                continue
            original = self.object(source["sha256"]).decode("utf-8")
            # Excerpts and metadata come from verified originals/live journal,
            # not potentially stale or poisoned index content.
            position = original.casefold().find(terms[0].casefold())
            start = max(0, position - 80)
            results.append({"source_id": source["id"], "title": source["name"],
                            "excerpt": original[start:start + 600], "project_id": source["project_id"],
                            "visibility": source["visibility"], "source_kind": source["source_kind"],
                            "sha256": source["sha256"], "retrieved_journal": head["store_sha256"],
                            "trust": "untrusted-source-data"})
        return results

    def source(self, sid, principal, include_text=True):
        data, _ = self.state()
        source = next((s for s in data["sources"] if s["id"] == sid), None)
        if source is None or not principal.allows_source(source):
            raise C.ContextError("Source unavailable in this permission scope.")
        result = {"source": source, "trust": "untrusted-source-data", "historical_metadata_is_not_authorization": True}
        if include_text and source.get("format") == SOURCE_FORMAT:
            result["original_text"] = self.object(source["sha256"]).decode("utf-8")
        return result

    def project(self, pid, principal, revision=None):
        if principal.role not in {"owner", "agent"} or (principal.role == "agent" and pid not in principal.projects):
            raise C.ContextError("Project unavailable in this permission scope.")
        store = C.read_json(self.journal)
        if revision is not None:
            if type(revision) is not int or not 0 <= revision <= len(store["events"]):
                raise C.ContextError("Invalid journal revision; pre-baseline history is not known.")
            store["events"] = store["events"][:revision]
        data, head = C.load_store(store)
        project = next((p for p in data["projects"] if p["id"] == pid), None)
        if project is None:
            raise C.ContextError("Project unavailable in this permission scope.")
        allowed_sources = [s for s in data["sources"] if principal.allows_source(s)]
        packet = C.project_brief({**data, "sources": allowed_sources}, project, head)
        return {"format": "ContinuityAIContext/1", "project": project, "context": head,
                "handoff": packet[:12000], "truncated": len(packet) > 12000,
                "permission_basis": "owner" if principal.role == "owner" else "explicit-startup-project-grant",
                "trust": "untrusted-source-data", "historical_dates_before_baseline": "unknown"}

    def backup(self, destination):
        destination = private_directory(destination)
        if destination == self.root or self.root in destination.parents:
            raise C.ContextError("Backup must be outside the working data directory.")
        with C.locked(self.journal):
            raw = self.journal.read_bytes(); data, head = C.load_store(C.parse(raw))
            manifest = {"format": "ContinuityObjectBackup/1", "journal_sha256": head["store_sha256"], "files": {"Context_Store.json": raw_hash(raw)}, "encrypted": False}
            C.atomic_write(destination / "Context_Store.json", raw, replace=False)
            # Include all objects, history, initial intake witness and pending outbox.
            for directory in ("objects", "history", "outbox"):
                for path in sorted((self.root / directory).glob("*")):
                    if path.is_symlink() or not path.is_file():
                        raise C.ContextError("Unsafe backup member.")
                    content = path.read_bytes()
                    if directory == "objects" and raw_hash(content) != path.name:
                        raise C.ContextError("Corrupt object detected during backup.")
                    relative = directory + "/" + path.name
                    private_directory(destination / directory)
                    C.atomic_write(destination / relative, content, replace=False)
                    manifest["files"][relative] = raw_hash(content)
            for name in ("intake.json", "phone-deletions.json"):
                path = self.root / name
                if path.exists():
                    content = path.read_bytes(); C.atomic_write(destination / name, content, replace=False)
                    manifest["files"][name] = raw_hash(content)
            # Outbox has a separate writer: pause capture/spooling for a complete snapshot.
            manifest["outbox_snapshot"] = "not-atomic-with-journal; pause producers"
            C.json_write(destination / "MANIFEST.json", manifest, replace=False)
            return manifest

    @staticmethod
    def verify_backup(directory):
        directory = Path(directory)
        manifest = C.read_json(directory / "MANIFEST.json")
        if manifest.get("format") != "ContinuityObjectBackup/1":
            raise C.ContextError("Invalid backup format.")
        for name, digest in manifest["files"].items():
            p = directory / name
            if Path(name).is_absolute() or ".." in Path(name).parts or p.is_symlink() or any(parent.is_symlink() for parent in p.parents) or not p.is_file() or raw_hash(p.read_bytes()) != digest:
                raise C.ContextError("Backup member missing, unsafe or changed.")
        data, head = C.load_store(C.read_json(directory / "Context_Store.json"))
        if head["store_sha256"] != manifest["journal_sha256"]:
            raise C.ContextError("Backup journal identity changed.")
        for s in data["sources"]:
            if s.get("format") == SOURCE_FORMAT:
                name = "objects/" + s["sha256"]
                if manifest["files"].get(name) != s["sha256"]:
                    raise C.ContextError("Backup omits an authoritative capture object.")
        return {"verified": True, "files": len(manifest["files"]), "context": head, "encrypted": False}

    def browser_preview(self, raw):
        value = C.parse(raw)
        if not isinstance(value, dict):
            raise C.ContextError("Expected a browser export object.")
        format_ = value.get("format")
        if format_ in {"ContinuityVault/0.1", "ContinuitySealedBackup/1", "continuity-portable-bundle", "continuity-encrypted-vault"}:
            raise C.ContextError("Encrypted/private-profile migration is blocked; do not downgrade its security.")
        if format_ == "ContinuityRecovery/1":
            state = value.get("workspace", {}).get("state")
        elif format_ in {"ContinuityBackup/1", "ContinuityBackup/2"}:
            state = value.get("state")
        else:
            raise C.ContextError("Unsupported browser format; raw intake needs its own reviewed adapter.")
        if not isinstance(state, dict) or state.get("version") != "0.11.0" or not isinstance(state.get("sources"), list):
            raise C.ContextError("Expected a 0.11.0 source-preserving workspace.")
        seen = set()
        for s in state["sources"]:
            if not isinstance(s, dict) or not isinstance(s.get("id"), str) or s["id"] in seen or not isinstance(s.get("text"), str) or not s["text"].strip():
                raise C.ContextError("Invalid or duplicate source; no migration started.")
            seen.add(s["id"])
            supplied = s.get("textSha256")
            if supplied is not None and supplied != raw_hash(s["text"].encode("utf-8")):
                raise C.ContextError("Browser source checksum mismatch.")
        return {"format": format_, "archive_sha256": raw_hash(raw), "sources": state["sources"],
                "preserved_unindexed": {"drafts": len(value.get("drafts", [])), "incoming": len(value.get("incoming", [])), "captures": len(state.get("captures", [])), "notes": len(state.get("notes", [])), "projects": len(state.get("threads", []))},
                "no_consent_or_project_status_imported": True}

    def import_browser(self, raw, project_id, approved=False):
        preview = self.browser_preview(raw)
        if not approved:
            raise C.ContextError("Review preview and approve this plaintext private copy first.")
        archive = self.put_object(raw)
        receipts = []
        # Each source is its own atomic/idempotent event. Failure is resumable,
        # not falsely advertised as all-or-nothing across the entire import.
        for s in preview["sources"]:
            capture_id = str(uuid.uuid5(NAMESPACE, archive + ":" + s["id"]))
            request = {"format": FORMAT, "capture_id": capture_id, "text": s["text"],
                       "title": s.get("title", "Imported browser source")[:200], "project_id": project_id,
                       "visibility": "private", "source_kind": "UNKNOWN", "speaker": "unknown",
                       "mechanism": "reviewed-browser-export", "origin": {"archive_sha256": archive,
                       "origin_source_id": s["id"], "adapter": "browser-source-copy/1",
                       "imported_metadata_is_inert": True}}
            receipts.append(self.accept(request, True))
        return {k: v for k, v in preview.items() if k != "sources"} | {"copied_sources": len(receipts), "receipts": receipts, "publication_approved": False}

def server(store, tokens, agent_projects=frozenset(), port=8766, agent_ttl_seconds=3600):
    if not tokens.get("owner") or any(not isinstance(t, str) or len(t) < 32 for t in tokens.values()) or len(set(tokens.values())) != len(tokens):
        raise C.ContextError("Provide distinct tokens of at least 32 characters; owner token is required.")
    roles = {role: Principal(role, frozenset(agent_projects) if role == "agent" else frozenset()) for role in tokens}
    if not set(tokens) <= {"owner", "agent", "capture"}:
        raise C.ContextError("Unsupported credential role.")
    if type(agent_ttl_seconds) is not int or not 1 <= agent_ttl_seconds <= 86400:
        raise C.ContextError("Agent grant lifetime must be 1 to 86400 seconds.")
    agent_deadline = time.monotonic() + agent_ttl_seconds
    class Handler(BaseHTTPRequestHandler):
        server_version = "ContinuityContext/1"
        def log_message(self, *_):
            pass  # Never log authorization, query strings, URLs or captured contents.
        def send(self, code, value):
            raw = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers(); self.wfile.write(raw)
        def principal(self):
            expected = f"127.0.0.1:{self.server.server_port}"
            if self.headers.get("Host") != expected or self.headers.get("Origin") is not None:
                return None
            supplied = self.headers.get("Authorization", "")
            for role, token in tokens.items():
                if hmac.compare_digest(supplied, "Bearer " + token):
                    if role == "agent" and time.monotonic() >= agent_deadline:
                        return None
                    return roles[role]
            return None
        def do_GET(self):
            principal = self.principal()
            if principal is None:
                self.send(401, {"error": "Authentication or request-origin rejected."}); return
            parsed = urlsplit(self.path); q = parse_qs(parsed.query)
            try:
                if parsed.path == "/v1/health":
                    if principal.role != "owner":
                        self.send(200, {"service": "available"}); return
                    data, head = store.state()
                    self.send(200, {"context": head, "pending_outbox": len(list(store.outbox.glob('*.json'))), "object_store": "plaintext", "index": "derived", "publications_enabled": False}); return
                if parsed.path == "/v1/search":
                    result = store.search(q.get("q", [""])[0], principal, q.get("project_id", [None])[0], int(q.get("limit", ["20"])[0]), q.get("since", [None])[0], q.get("until", [None])[0])
                    self.send(200, {"results": result, "retrieval": "lexical-fts5", "trust": "untrusted-source-data"}); return
                if parsed.path.startswith("/v1/sources/"):
                    self.send(200, store.source(parsed.path[len('/v1/sources/'):], principal)); return
                if parsed.path.startswith("/v1/projects/"):
                    revision = int(q["revision"][0]) if "revision" in q else None
                    self.send(200, store.project(parsed.path[len('/v1/projects/'):], principal, revision)); return
                self.send(404, {"error": "No such operation."})
            except (C.ContextError, ValueError, KeyError, TypeError, OSError, sqlite3.DatabaseError):
                self.send(400, {"error": "Request unavailable, invalid, stale or integrity check failed."})
        def do_POST(self):
            principal = self.principal()
            if principal is None:
                self.send(401, {"error": "Authentication or request-origin rejected."}); return
            if principal.role not in {"owner", "capture"} or self.path != "/v1/captures":
                self.send(403, {"error": "Credential cannot perform this operation."}); return
            try:
                if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type", "").split(';')[0] != "application/json":
                    raise C.ContextError("Only bounded JSON bodies are supported.")
                count = int(self.headers.get("Content-Length", "0"))
                if not 0 < count <= MAX_HTTP_BYTES:
                    raise C.ContextError("Invalid body length.")
                raw = self.rfile.read(count)
                if len(raw) != count:
                    raise C.ContextError("Incomplete request.")
                value = C.parse(raw)
                if not isinstance(value, dict) or set(value) != {"request", "authorize_private_copy"} or value["authorize_private_copy"] is not True:
                    raise C.ContextError("Fresh private-copy authorization required.")
                result = store.accept(value["request"], True, "owner" if principal.role == "owner" else "capture-adapter")
                self.send(200 if result["already_saved"] else 201, result)
            except (C.ContextError, ValueError, KeyError, TypeError, OSError):
                self.send(409, {"error": "Capture not acknowledged; retain the original/outbox and retry after review."})
        def setup(self):
            super().setup(); self.connection.settimeout(10)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.daemon_threads = True
    return httpd

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("init"); p.add_argument("--context", type=Path, required=True)
    p = sub.add_parser("spool"); p.add_argument("request", type=Path)
    p = sub.add_parser("capture"); p.add_argument("request", type=Path); p.add_argument("--approve", action="store_true")
    p = sub.add_parser("flush"); p.add_argument("--approve", action="store_true")
    sub.add_parser("index")
    p = sub.add_parser("search"); p.add_argument("query"); p.add_argument("--project")
    p = sub.add_parser("source"); p.add_argument("id")
    p = sub.add_parser("project"); p.add_argument("id"); p.add_argument("--revision", type=int)
    p = sub.add_parser("backup"); p.add_argument("destination", type=Path)
    p = sub.add_parser("verify-backup"); p.add_argument("directory", type=Path)
    p = sub.add_parser("import-browser"); p.add_argument("file", type=Path); p.add_argument("--project", required=True); p.add_argument("--approve", action="store_true")
    p = sub.add_parser("serve"); p.add_argument("--port", type=int, default=8766); p.add_argument("--agent-project", action="append", default=[]); p.add_argument("--agent-ttl-seconds", type=int, default=3600)
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            s = Store.initialize(args.root, args.context); result = s.state()[1]
        elif args.command == "verify-backup":
            result = Store.verify_backup(args.directory)
        else:
            s = Store(args.root); owner = Principal("owner")
            if args.command == "spool": result = s.spool(C.read_json(args.request))
            elif args.command == "capture": result = s.accept(C.read_json(args.request), args.approve)
            elif args.command == "flush": result = s.flush(args.approve)
            elif args.command == "index": result = s.rebuild_index()
            elif args.command == "search": result = s.search(args.query, owner, args.project)
            elif args.command == "source": result = s.source(args.id, owner)
            elif args.command == "project": result = s.project(args.id, owner, args.revision)
            elif args.command == "backup": result = s.backup(args.destination)
            elif args.command == "import-browser":
                raw = args.file.read_bytes()
                if args.approve: result = s.import_browser(raw, args.project, True)
                else: result = {k: v for k, v in s.browser_preview(raw).items() if k != "sources"}
            elif args.command == "serve":
                tokens = {role: os.environ[var] for role, var in (("owner", "CONTINUITY_OWNER_TOKEN"), ("agent", "CONTINUITY_AGENT_TOKEN"), ("capture", "CONTINUITY_CAPTURE_TOKEN")) if os.environ.get(var)}
                service = server(s, tokens, frozenset(args.agent_project), args.port, args.agent_ttl_seconds)
                print("Controlled context listening on loopback; no public publication or remote access enabled.", flush=True)
                try: service.serve_forever()
                finally: service.server_close()
                return 0
        print(json.dumps(result, ensure_ascii=False, indent=2)); return 0
    except (C.ContextError, OSError, sqlite3.DatabaseError, ValueError, KeyError, UnicodeError) as exc:
        print(f"Continuity capture/context error: {exc}", file=sys.stderr); return 2

if __name__ == "__main__":
    raise SystemExit(main())
