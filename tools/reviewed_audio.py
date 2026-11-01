"""Versioned, private reviewed-audio handoff contract. Standard library only.

Hashes bind exact UTF-8 text, not factual truth or proof that a person listened.
No Unicode normalization is performed. All object keys are fixed ASCII.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import re

SCHEMA = "total-recall-audio/continuity-handoff-v1"
MAX_BYTES = 5 * 1024 * 1024
MAX_EXCERPTS = 1000
MAX_TEXT = 20000
SAFE_INTEGER = 9007199254740991
PRIVACY = {"scope": "private", "automatic_upload": False, "includes_source_paths": False}
HEX = re.compile(r"[0-9a-f]{64}\Z")


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def excerpt_id(source_sha256, transcript_fingerprint_sha256, sequence):
    return "trae_" + digest({"source_sha256": source_sha256,
                           "transcript_fingerprint_sha256": transcript_fingerprint_sha256,
                           "sequence": sequence})


def revision_digest(excerpt):
    return digest({key: value for key, value in excerpt.items() if key != "revision_sha256"})


def bundle_digest(excerpts):
    # Export wall-clock and generator version are transport metadata, not identity.
    return digest({"schema": SCHEMA, "privacy": PRIVACY, "excerpts": excerpts})


def _object(value, keys, where):
    if type(value) is not dict or set(value) != set(keys):
        raise ValueError(where + " has missing, unexpected or invalid fields")
    return value


def _string(value, where, maximum=MAX_TEXT, *, empty=True):
    if type(value) is not str or len(value) > maximum or (not empty and not value):
        raise ValueError(where + " must be a bounded string")
    try:
        value.encode("utf-8")
    except UnicodeError as error:
        raise ValueError(where + " contains invalid Unicode") from error
    return value


def _sha(value, where):
    if type(value) is not str or HEX.fullmatch(value) is None:
        raise ValueError(where + " must be a lowercase SHA-256")


def _integer(value, where, *, minimum=0):
    if type(value) is not int or not minimum <= value <= SAFE_INTEGER:
        raise ValueError(where + " must be a nonnegative safe integer")


def _date(value, where):
    _string(value, where, 80, empty=False)
    try:
        stamp = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if stamp.tzinfo is None or "T" not in value:
            raise ValueError("Timestamp needs date, time and timezone")
    except (ValueError, OverflowError) as error:
        raise ValueError(where + " must be an ISO timestamp with a timezone") from error
    return stamp


def _state(value, where):
    _object(value, ("edited_text", "verified", "pinned", "note"), where)
    if value["edited_text"] is not None:
        _string(value["edited_text"], where + ".edited_text")
    for flag in ("verified", "pinned"):
        if type(value[flag]) is not bool:
            raise ValueError(where + "." + flag + " must be a boolean")
    _string(value["note"], where + ".note", 2000)


def validate_handoff(payload):
    _object(payload, ("schema", "exported_at", "generator", "privacy", "selection",
                      "bundle_sha256", "excerpts"), "Handoff")
    if payload["schema"] != SCHEMA:
        raise ValueError("Unrecognized audio handoff schema")
    _date(payload["exported_at"], "exported_at")
    _object(payload["generator"], ("name", "version"), "generator")
    if payload["generator"]["name"] != "Total Recall Audio Workbench":
        raise ValueError("Unrecognized generator")
    _string(payload["generator"]["version"], "generator.version", 50, empty=False)
    _object(payload["privacy"], PRIVACY, "privacy")
    # dict equality treats 0 as False, so check flag types separately.
    if (payload["privacy"] != PRIVACY
            or type(payload["privacy"]["automatic_upload"]) is not bool
            or type(payload["privacy"]["includes_source_paths"]) is not bool):
        raise ValueError("Only a private, manual, path-free handoff is supported")
    _object(payload["selection"], ("verified_only", "pinned_only", "count"), "selection")
    if (payload["selection"]["verified_only"] is not True
            or payload["selection"]["pinned_only"] is not True):
        raise ValueError("Only pinned and audio-verified excerpts may be handed off")
    excerpts = payload["excerpts"]
    if type(excerpts) is not list or not 1 <= len(excerpts) <= MAX_EXCERPTS:
        raise ValueError("Select between 1 and 1000 reviewed excerpts")
    _integer(payload["selection"]["count"], "selection.count", minimum=1)
    if payload["selection"]["count"] != len(excerpts):
        raise ValueError("Selection count does not match excerpts")
    seen = set()
    for number, item in enumerate(excerpts):
        where = "excerpt[" + str(number) + "]"
        _object(item, ("id", "revision_sha256", "source", "transcript", "segment", "text", "review"), where)
        source = _object(item["source"], ("sha256", "filename", "bytes", "duration_ms", "locator"), where + ".source")
        _sha(source["sha256"], where + ".source.sha256")
        name = _string(source["filename"], where + ".source.filename", 200, empty=False)
        if name in (".", "..") or any(c in name for c in "/\\") or any(ord(c) < 32 for c in name):
            raise ValueError("Source filename must be a basename, not a local path")
        _integer(source["bytes"], where + ".source.bytes")
        if source["duration_ms"] is not None:
            _integer(source["duration_ms"], where + ".source.duration_ms")
        if source["locator"] != "urn:sha256:" + source["sha256"]:
            raise ValueError("Source locator differs from recording hash")
        transcript = _object(item["transcript"], ("fingerprint_sha256", "engine", "model", "language", "created_at"), where + ".transcript")
        _sha(transcript["fingerprint_sha256"], where + ".transcript.fingerprint_sha256")
        for key in ("engine", "model", "language"):
            _string(transcript[key], where + ".transcript." + key, 1000, empty=False)
        _date(transcript["created_at"], where + ".transcript.created_at")
        segment = _object(item["segment"], ("sequence", "start_ms", "end_ms", "timing"), where + ".segment")
        _integer(segment["sequence"], where + ".segment.sequence", minimum=1)
        for key in ("start_ms", "end_ms"):
            _integer(segment[key], where + ".segment." + key)
        if segment["end_ms"] < segment["start_ms"]:
            raise ValueError("Excerpt end precedes its start")
        if segment["timing"] not in ("model_estimate", "supplied_transcript", "unknown"):
            raise ValueError("Unrecognized timing provenance")
        if segment["timing"] == "unknown" and (segment["start_ms"] != 0 or segment["end_ms"] != 0):
            raise ValueError("Unknown timing must retain zero placeholders")
        text = _object(item["text"], ("machine", "reviewed", "effective"), where + ".text")
        _string(text["machine"], where + ".text.machine", empty=False)
        if text["reviewed"] is not None:
            _string(text["reviewed"], where + ".text.reviewed")
        _string(text["effective"], where + ".text.effective", empty=False)
        if text["effective"] != (text["reviewed"] if text["reviewed"] is not None else text["machine"]):
            raise ValueError("Effective text differs from the saved reviewed text")
        review = _object(item["review"], ("verified_against_audio", "pinned", "note", "history"), where + ".review")
        if review["verified_against_audio"] is not True or review["pinned"] is not True:
            raise ValueError("An excerpt is not both verified and pinned")
        _string(review["note"], where + ".review.note", 2000)
        events = review["history"]
        if type(events) is not list or not 1 <= len(events) <= 1000:
            raise ValueError("A saved review history is required (up to 1000 events)")
        previous_state = previous_date = None
        for event in events:
            _object(event, ("revised_at", "old", "new"), where + ".review.history")
            stamp = _date(event["revised_at"], where + ".review.history.revised_at")
            _state(event["old"], where + ".review.history.old")
            _state(event["new"], where + ".review.history.new")
            if previous_state is not None and event["old"] != previous_state:
                raise ValueError("Review history states are not contiguous")
            if previous_date is not None and stamp < previous_date:
                raise ValueError("Review history dates are out of order")
            previous_state, previous_date = event["new"], stamp
        expected = {"edited_text": text["reviewed"], "verified": True, "pinned": True, "note": review["note"]}
        if previous_state != expected:
            raise ValueError("Final review history differs from the selected excerpt")
        expected_id = excerpt_id(source["sha256"], transcript["fingerprint_sha256"], segment["sequence"])
        if item["id"] != expected_id or item["id"] in seen:
            raise ValueError("Invalid or duplicated stable excerpt identity")
        seen.add(item["id"])
        _sha(item["revision_sha256"], where + ".revision_sha256")
        if item["revision_sha256"] != revision_digest(item):
            raise ValueError("Excerpt revision checksum mismatch")
    _sha(payload["bundle_sha256"], "bundle_sha256")
    if payload["bundle_sha256"] != bundle_digest(excerpts):
        raise ValueError("Bundle checksum mismatch")
    if len(canonical_json(payload).encode("utf-8")) > MAX_BYTES:
        raise ValueError("Handoff exceeds 5 MiB")
    return payload


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def loads_handoff(raw):
    if type(raw) not in (bytes, str):
        raise ValueError("Read handoff as UTF-8 bytes or text")
    if isinstance(raw, str):
        raw = raw.encode("utf-8")
    if len(raw) > MAX_BYTES:
        raise ValueError("Handoff exceeds 5 MiB")
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                           parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Invalid JSON number")))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ValueError("Invalid handoff JSON: " + str(error)) from error
    return validate_handoff(value)


def dumps_handoff(payload):
    validate_handoff(payload)
    raw = (json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")
    if len(raw) > MAX_BYTES:
        raise ValueError("Handoff exceeds 5 MiB; select fewer excerpts")
    return raw
