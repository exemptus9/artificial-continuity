#!/usr/bin/env python3
"""Check that every spoken cue's recognition guard covers its actual packaged recording."""
import array
import math
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
source = (root / "android/src/org/totalrecall/listening/CaptureCues.java").read_text()
cues = re.findall(r'^\s*[A-Z_]+\("([a-z_]+)",(\d+),"[^"]+"\)[,;]', source, re.M)
if not cues:
    raise ValueError("Missing spoken cue declarations")
for name, declared in cues:
    path = root / f"android/res/raw/recall_{name}.ogg"
    seconds = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries",
                                             "format=duration", "-of", "csv=p=0", path]))
    if abs(1000 * seconds - int(declared)) > 2:
        raise ValueError(f"{name} recognition guard is {declared} ms for {seconds:.3f} s recording")
    pcm = array.array("h")
    pcm.frombytes(subprocess.check_output(["ffmpeg", "-v", "error", "-i", path, "-ar",
                                            "16000", "-ac", "1", "-f", "s16le", "-"]))
    if not pcm or math.sqrt(sum(v*v for v in pcm) / len(pcm)) < 64:
        raise ValueError(f"{name} spoken recording was silent or empty")
print(f"{len(cues)} audible spoken cues matched declared recognition guard durations within 2 ms")
