#!/usr/bin/env python3
"""Optional Android/Termux microphone + TTS adapter for Continuity.

Requires the Termux:API app and termux-api package. This adapter deliberately
keeps speech recognition outside canonical state: the recognized transcript is
passed through continuity_voice's safe command boundary. Read operations may
execute; write-like speech remains staged and requires later owner confirmation.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

import continuity_voice as V
import continuity_semantics as S


def final_transcript(stdout: str) -> str:
    """Termux may emit partial matches; use the last non-empty candidate."""
    rows=[line.strip() for line in stdout.splitlines() if line.strip()]
    if not rows:
        raise S.ContinuityError("speech recognizer returned no transcript")
    return rows[-1]


def require_command(name: str) -> str:
    path=shutil.which(name)
    if not path:
        raise S.ContinuityError(f"{name} is unavailable; install/configure Termux:API first")
    return path


def listen() -> str:
    exe=require_command("termux-speech-to-text")
    cp=subprocess.run([exe],capture_output=True,text=True,timeout=120)
    if cp.returncode:
        raise S.ContinuityError("Termux speech recognition failed")
    return final_transcript(cp.stdout)


def speak(text: str) -> None:
    exe=require_command("termux-tts-speak")
    cp=subprocess.run([exe,text],capture_output=True,text=True,timeout=120)
    if cp.returncode:
        raise S.ContinuityError("Termux text-to-speech failed")


def main(argv=None) -> int:
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ledger",type=Path,required=True)
    ap.add_argument("--no-speak",action="store_true",help="Print response without TTS playback")
    a=ap.parse_args(argv)
    try:
        transcript=listen()
        result=V.run(a.ledger,transcript)
        print(json.dumps(result,ensure_ascii=False,indent=2))
        if not a.no_speak and result.get("spoken_text"):
            speak(result["spoken_text"])
        return 0
    except (S.ContinuityError,OSError,subprocess.SubprocessError,ValueError,TypeError,KeyError) as exc:
        print(f"Continuity Termux voice error: {exc}",file=sys.stderr)
        return 2


if __name__=="__main__":
    raise SystemExit(main())
