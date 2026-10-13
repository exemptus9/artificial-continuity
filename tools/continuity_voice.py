#!/usr/bin/env python3
"""Thin voice-command adapter for Continuity.

Speech recognition and speech synthesis are intentionally external adapters.
This module accepts a transcript, executes read-only commands, and *stages*
write commands for explicit confirmation. The transcript itself is never
silently promoted to canonical state.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import continuity_semantics as S

FORMAT="ContinuityVoiceResult/1"

def interpret(transcript: str) -> dict:
    raw=transcript.strip(); lower=raw.casefold()
    if not raw: raise S.ContinuityError("voice transcript is empty")
    m=re.fullmatch(r"(?:continuity\s+)?resume\s+(.+)",raw,re.I)
    if m: return {"mode":"read","operation":"resume","argument":m.group(1).strip()}
    m=re.fullmatch(r"(?:continuity\s+)?(?:show\s+)?conflicts(?:\s+for)?\s+(.+)",raw,re.I)
    if m: return {"mode":"read","operation":"conflicts","argument":m.group(1).strip()}
    m=re.fullmatch(r"(?:continuity\s+)?what\s+changed(?:\s+since\s+(.+))?",raw,re.I)
    if m: return {"mode":"read","operation":"changes","since":m.group(1).strip() if m.group(1) else None}
    m=re.fullmatch(r"(?:continuity\s+)?record\s+note\s+(.+)",raw,re.I|re.S)
    if m: return {"mode":"stage-write","operation":"record_note","text":m.group(1).strip()}
    return {"mode":"unknown","operation":None,"transcript":raw}

def resolve_project(projection: dict, query: str) -> str:
    rows=S.find_entity(projection,query)
    projects=[r for r in rows if r.get("kind")=="project"]
    if len(projects)!=1: raise S.ContinuityError("voice project reference is not unique")
    return projects[0]["id"]

def run(ledger: Path, transcript: str) -> dict:
    store=S.read_json(ledger); projection,_=S.replay(store); cmd=interpret(transcript)
    base={"format":FORMAT,"transcript":transcript,"interpretation":cmd}
    if cmd["mode"]=="read" and cmd["operation"]=="resume":
        pid=resolve_project(projection,cmd["argument"]); packet=S.resume_packet(store,pid)
        return {**base,"executed":True,"result":packet,"spoken_text":f"Resume packet ready for {packet['project']['name']}. {len(packet['next_actions'])} next actions and {len(packet['unresolved_conflicts'])} unresolved conflicts."}
    if cmd["mode"]=="read" and cmd["operation"]=="conflicts":
        pid=resolve_project(projection,cmd["argument"]); rows=S.conflicts(projection,pid)
        return {**base,"executed":True,"result":rows,"spoken_text":f"I found {len(rows)} unresolved conflicts."}
    if cmd["mode"]=="read" and cmd["operation"]=="changes":
        rows=S.query_changes(projection,cmd.get("since"))
        return {**base,"executed":True,"result":rows,"spoken_text":f"I found {len(rows)} recorded changes."}
    if cmd["mode"]=="stage-write" and cmd["operation"]=="record_note":
        return {**base,"executed":False,"requires_confirmation":True,"staged":{"kind":"capture","text":cmd["text"],"source_kind":"USER_ASSERTION","speaker":"user"},"spoken_text":"The note is staged but not committed. Confirm it in the owner interface before it becomes Continuity evidence."}
    return {**base,"executed":False,"spoken_text":"I could not map that transcript to a safe Continuity operation."}

def main(argv=None)->int:
    ap=argparse.ArgumentParser(description=__doc__); ap.add_argument("--ledger",type=Path,required=True); ap.add_argument("--text"); a=ap.parse_args(argv)
    transcript=a.text if a.text is not None else sys.stdin.read()
    try:
        print(json.dumps(run(a.ledger,transcript),ensure_ascii=False,indent=2)); return 0
    except (S.ContinuityError,OSError,ValueError,TypeError,KeyError) as exc:
        print(f"Continuity voice error: {exc}",file=sys.stderr); return 2

if __name__=="__main__": raise SystemExit(main())
