#!/usr/bin/env python3
"""Local audio-to-Continuity adapter. Private, unreviewed, and offline by default."""
from __future__ import annotations

import argparse
import importlib.metadata
import json
import math
from pathlib import Path
import sys
import tempfile
import uuid

import capture_context as G
import context_store as C

FORMAT = "ContinuityAudioTranscript/1"
NAMESPACE = uuid.UUID("1d1c6758-7187-46d6-8b41-c784de1856df")


def initialize(root):
    """Initialize a separate empty inbox; never replace an existing journal."""
    with tempfile.TemporaryDirectory() as directory:
        baseline = Path(directory) / "baseline.json"
        C.json_write(baseline, C.init_store({"projects": [
            {"id": "AUDIO.INBOX", "name": "Audio review inbox", "state": "ACTIVE",
             "confidence": "D", "sources": [], "next_action": "Review imported transcripts against original audio."}
        ], "sources": []}))
        return G.Store.initialize(root, baseline)


def validate_transcript(value):
    if not isinstance(value, dict) or not isinstance(value.get("segments"), list):
        raise C.ContextError("Transcript requires a segments array.")
    segments = value["segments"]
    if not segments or len(segments) > 50000:
        raise C.ContextError("Expected 1–50,000 segments; empty speech is not a transcript.")
    previous = -1.0
    for segment in segments:
        if not isinstance(segment, dict):
            raise C.ContextError("Invalid segment.")
        start, end, text = (segment.get(k) for k in ("start", "end", "text"))
        if (any(type(v) not in (int, float) or not math.isfinite(v) for v in (start, end))
                or start < 0 or end < start or start < previous):
            raise C.ContextError("Timestamps must be finite, nonnegative, ordered seconds.")
        if not isinstance(text, str) or not text.strip() or "\x00" in text:
            raise C.ContextError("Each segment needs nonempty text.")
        previous = start
    return segments


def timestamp(seconds):
    milliseconds = round(seconds * 1000)
    hours, milliseconds = divmod(milliseconds, 3600000)
    minutes, milliseconds = divmod(milliseconds, 60000)
    seconds, milliseconds = divmod(milliseconds, 1000)
    return f"{hours:02}:{minutes:02}:{seconds:02}.{milliseconds:03}"


def ingest(store, audio, transcript_raw, *, approved=False, project=None, title=None,
           mechanism="imported-transcript", settings=None, observed_at=None, expected_audio=None):
    if not approved:
        raise C.ContextError("Use --approve for this local private copy.")
    if len(transcript_raw) > 10_000_000:
        raise C.ContextError("Transcript JSON exceeds 10 MB.")
    value = json.loads(transcript_raw)
    segments = validate_transcript(value)
    text = "\n".join(f"[{timestamp(s['start'])} → {timestamp(s['end'])}] {s['text']}" for s in segments)
    if len(text.encode("utf-8")) > G.MAX_TEXT_BYTES:
        raise C.ContextError("Rendered transcript exceeds 1 MB; split deliberately before importing.")
    audio = Path(audio)
    audio_hash = store.put_file(audio, expected=expected_audio)
    transcript_hash = store.put_object(transcript_raw)
    origin = {
        "format": FORMAT, "audio_sha256": audio_hash,
        "audio_object": "continuity-object:sha256:" + audio_hash,
        "audio_bytes": (store.objects / audio_hash).stat().st_size, "audio_name": audio.name,
        "transcript_sha256": transcript_hash,
        "transcript_object": "continuity-object:sha256:" + transcript_hash,
        "segments": len(segments), "timestamp_units": "seconds-from-audio-start",
        "settings": settings or {}, "audio_transcript_alignment_verified": False,
        "caution": "Unreviewed transcript. Supplied text, timing and speaker identity may be wrong. Hashes establish preserved bytes, not truth."
    }
    identity = C.digest({"audio": audio_hash, "transcript": transcript_hash,
                         "project": project, "title": title or audio.name,
                         "mechanism": mechanism, "settings": settings or {},
                         "observed_at": observed_at})
    request = {
        "format": G.FORMAT, "capture_id": str(uuid.uuid5(NAMESPACE, identity)),
        "text": text, "title": (title or audio.name)[:200], "project_id": project,
        "visibility": "private", "source_kind": "UNKNOWN", "speaker": "unknown",
        "observed_at": observed_at, "mechanism": mechanism,
        "source_app": "Continuity audio adapter", "origin": origin,
    }
    receipt = store.accept(request, approved=True, actor="capture-adapter")
    return {**receipt, "audio_sha256": audio_hash, "transcript_sha256": transcript_hash,
            "review_state": "unreviewed", "segments": len(segments), "publication_approved": False}


def transcribe(store, audio, *, approved=False, model="base.en", device="cpu",
               compute_type="int8", language="en", allow_download=False,
               project=None, title=None, observed_at=None):
    if not approved:
        raise C.ContextError("Use --approve before copying or transcribing audio.")
    try:
        from faster_whisper import WhisperModel
    except ImportError as exc:
        raise C.ContextError("Install optional faster-whisper in a virtual environment; see docs/AUDIO_INGESTION.md.") from exc
    audio = Path(audio)
    original_hash = store.put_file(audio)
    preserved = store.objects / original_hash
    engine = WhisperModel(model, device=device, compute_type=compute_type,
                          local_files_only=not allow_download)
    output, info = engine.transcribe(str(preserved), language=language or None,
                                     beam_size=5, vad_filter=True)
    segments = [{"start": s.start, "end": s.end, "text": s.text} for s in output]
    settings = {"engine": "faster-whisper", "version": importlib.metadata.version("faster-whisper"),
                "model": model, "device": device, "compute_type": compute_type,
                "language": info.language, "beam_size": 5, "vad_filter": True,
                "model_weights_sha256": None}
    # Model name/version is recorded; exact downloaded model weights are not attested.
    raw = json.dumps({"format": FORMAT, "segments": segments, "settings": settings},
                     ensure_ascii=False, allow_nan=False).encode("utf-8")
    # Fail if the input changed, even though inference used its preserved original.
    if G.file_hash(audio) != original_hash:
        raise C.ContextError("Input changed during transcription; preserved audio retained, no transcript committed.")
    return ingest(store, audio, raw, approved=True, project=project, title=title,
                  mechanism="machine-transcription", settings=settings, observed_at=observed_at,
                  expected_audio=original_hash)


def verify_audio(store, source_id):
    source = store.source(source_id, G.Principal("owner"))["source"]
    origin = source.get("origin", {})
    if origin.get("format") != FORMAT:
        raise C.ContextError("Not an audio transcript source.")
    for field in ("audio_sha256", "transcript_sha256"):
        digest = origin.get(field)
        if not isinstance(digest, str) or not G.SHA.fullmatch(digest):
            raise C.ContextError("Invalid audio provenance checksum.")
        path = store.objects / digest
        if path.is_symlink() or not path.is_file() or G.file_hash(path) != digest:
            raise C.ContextError("Missing or changed audio/transcript object.")
    return {"source_id": source_id, "preserved_bytes_verified": True,
            "audio_transcript_alignment_verified": False, "truth_verified": False}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, required=True)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("init")
    for command in ("import", "transcribe"):
        c = sub.add_parser(command)
        c.add_argument("audio", type=Path)
        c.add_argument("--approve", action="store_true")
        c.add_argument("--project"); c.add_argument("--title"); c.add_argument("--observed-at")
        if command == "import":
            c.add_argument("transcript", type=Path)
        else:
            c.add_argument("--model", default="base.en")
            c.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
            c.add_argument("--compute-type", default="int8")
            c.add_argument("--language", default="en")
            c.add_argument("--allow-download", action="store_true")
    c = sub.add_parser("verify"); c.add_argument("source_id")
    args = p.parse_args(argv)
    try:
        if args.command == "init":
            result = initialize(args.root).state()[1]
        else:
            store = G.Store(args.root)
            if args.command == "verify":
                result = verify_audio(store, args.source_id)
            else:
                common = {"approved": args.approve, "project": args.project, "title": args.title,
                          "observed_at": args.observed_at}
                if args.command == "import":
                    if args.transcript.stat().st_size > 10_000_000:
                        raise C.ContextError("Transcript JSON exceeds 10 MB.")
                    result = ingest(store, args.audio, args.transcript.read_bytes(), **common)
                else:
                    result = transcribe(store, args.audio, model=args.model, device=args.device,
                                        compute_type=args.compute_type, language=args.language,
                                        allow_download=args.allow_download, **common)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (C.ContextError, OSError, ValueError, RuntimeError) as exc:
        print(f"Audio operation not completed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
