"""Generate the fixed, synthetic archive dictation replay. Requires ffmpeg with flite."""
import argparse
import json
import math
import random
import re
import subprocess
import sys
from array import array
from pathlib import Path

PHRASES = [
    "marisol put the cyan folder beside the metronome",
    "the mnemonic for this constellation is a silver carousel",
    "do not replace the second vowel in the coda",
    "i could not carry fourteen lanterns so i took four",
    "meet me at six fifteen and bring exactly two copies",
    "the valve is closed unless the green indicator turns blue",
    "i wrote a palindrome on the margin of the almanac",
    "the archivist found thirty seven brittle photographs",
    "please do not erase the recording after noon",
    "the second speaker said willow hollow very softly",
    "we carried the compass beyond the quarry at dawn",
    "her surname is calder and the small town is called fenwick",
]

FRESH_PHRASES = [
    "i need sixteen red folders not sixty", "a blackbird landed behind the garden shed",
    "do not move the meeting before three thirty", "my latest poem keeps the echo exactly as spoken",
    "we can hear the ferry beyond the harbor wall", "i called the little opening a silver window",
    "the first speaker heard moonlight the second heard noon light", "remember to ask for nine maps and ninety labels",
    "say the phrase without replacing the last word", "at seven fifteen the blue lamp went out",
]


def synthesize(phrase, voice):
    pcm = subprocess.check_output(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
        f"flite=text={phrase}:voice={voice}", "-ar", "16000", "-ac", "1", "-f", "s16le", "-"])
    samples = array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    return samples

def render(samples, condition, seed):
    level = {"clean": -23, "faint": -51, "fan": -39}[condition]
    target = 32768 * 10 ** (level / 20)
    gain = target / math.sqrt(sum(x * x for x in samples) / len(samples))
    noise = target * 10 ** (-8 / 20) if condition == "fan" else 0
    rng = random.Random(seed)
    result = array("h")
    for i in range(3200 + len(samples) + 14400):
        j = i - 3200
        speech = samples[j] * gain if 0 <= j < len(samples) else 0
        # The independent unit-variance rumble and broadband terms sum to unit noise power.
        room = (math.sin(i * 2 * math.pi * 92 / 16000) + rng.uniform(-1, 1) * math.sqrt(1.5)) if noise else 0
        result.append(round(min(32767, max(-32768, speech + room * noise))))
    if sys.byteorder != "little":
        result.byteswap()
    return result.tobytes()

def fresh_audio(samples, condition, seed):
    import numpy as np
    signal = np.asarray(samples, dtype=float)
    target = 32768 * 10 ** ({"clean": -25, "faint": -54, "fan": -37}[condition] / 20)
    signal = np.pad(signal * target / np.sqrt(np.mean(signal**2)), (4800, 19200))
    if condition == "fan":
        rng = np.random.default_rng(812 + seed)
        t = np.arange(len(signal)) / 16000
        air = np.convolve(rng.normal(size=len(signal) + 5), np.ones(6) / 6, "valid")
        room = (.5 * np.sin(t*2*np.pi*142) + .25*np.sin(t*2*np.pi*284) +
                air*(1+.18*np.sin(t*2*np.pi*.7)) + .28*rng.normal(size=len(signal)))
        signal += room * (target * 10**(-6/20) / np.sqrt(np.mean(room**2)))
    return np.clip(np.rint(signal), -32768, 32767).astype("<i2").tobytes()


def generate(root, fresh=False, sample=False):
    root.mkdir(parents=True, exist_ok=True)
    cases = []
    voices = ["awb", "kal", "slt"] if fresh else ["slt", "rms", "kal16"]
    for voice_index, voice in enumerate(voices):
        for i, phrase in enumerate(FRESH_PHRASES if fresh else PHRASES):
            if sample and i % 6 != voice_index:
                continue
            samples = synthesize(phrase, voice)
            for condition in ["clean", "faint", "fan"]:
                name = f"{voice}-{i:02d}-{condition}.pcm"
                (root / name).write_bytes(fresh_audio(samples, condition, i) if fresh else render(samples, condition, 173 + i))
                cases.append(dict(file=name, expected=phrase, condition=condition, voice=voice))
    (root / "cases.json").write_text(json.dumps(cases, indent=2))

def tokens(text):
    return re.findall(r"[a-z]+(?:'[a-z]+)*|[0-9]+", text.lower())

def distance(reference, heard):
    previous = list(range(len(heard) + 1))
    for i, target in enumerate(reference, 1):
        row = [i]
        for j, actual in enumerate(heard, 1):
            row.append(min(row[-1] + 1, previous[j] + 1, previous[j-1] + (target != actual)))
        previous = row
    return previous[-1]

def compare(before, after, subset=False):
    def read(path):
        return {r["file"]: r for r in (json.loads(line) for line in path.read_text().splitlines())}
    old, new = read(before), read(after)
    if not old or old.keys() != new.keys() or (not subset and len(old) not in (len(PHRASES)*9, len(FRESH_PHRASES)*9)):
        raise ValueError("Incomplete or mismatched holdout")
    rows = []
    for name, first in old.items():
        last = new[name]
        if any(first[key] != last[key] for key in ("condition", "expected", "voice")):
            raise ValueError("Mismatched recording metadata for " + name)
        source = [" ".join(r["first"] for r in run["readings"]).strip() for run in (first, last)]
        reference = tokens(first["expected"])
        output = [" ".join(r["shown"] for r in run["readings"]) for run in (first, last)]
        first_err = [distance(reference, tokens(text)) for text in source]
        old_err, new_err = [distance(reference, tokens(text)) for text in output]
        rows.append(dict(file=name, condition=first["condition"], words=len(reference), first=first_err[0],
            old=old_err, new=new_err, identical_source=source[0]==source[1], changed=output[0]!=output[1],
            prevented=old_err>first_err[0] and new_err<=first_err[1], withheld=old_err<first_err[0] and new_err>=first_err[1],
            reviewed=bool(any(r["hint"] for r in last["readings"])), retried=last["compared"]>0))
    def summarize(items):
        words=sum(r["words"] for r in items)
        return dict(cases=len(items), reference_words=words,
            errors={k:sum(r[k] for r in items) for k in ["first","old","new"]},
            different_source=sum(not r["identical_source"] for r in items),
            changed=sum(r["changed"] for r in items), new_better=sum(r["new"]<r["old"] for r in items),
            new_worse=sum(r["new"]>r["old"] for r in items), overcorrections_prevented=sum(r["prevented"] for r in items),
            improvements_withheld=sum(r["withheld"] for r in items), review_flags=sum(r["reviewed"] for r in items),
            flagged_inaccurate=sum(r["reviewed"] and r["new"]>0 for r in items),
            flagged_exact=sum(r["reviewed"] and r["new"]==0 for r in items),
            retries=sum(r["retried"] for r in items))
    return {group:summarize(rows if group=="all" else [r for r in rows if r["condition"]==group])
        for group in ["all","clean","faint","fan"]}

if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    commands=parser.add_subparsers(dest="mode",required=True)
    generator=commands.add_parser("generate")
    generator.add_argument("directory",type=Path)
    generator.add_argument("--fresh", action="store_true", help="Use the reserved scripts and alternate fan mix (NumPy required).")
    generator.add_argument("--sample", action="store_true", help="Use 18 fixed examples balanced across voices and conditions.")
    scoring=commands.add_parser("compare")
    scoring.add_argument("previous",type=Path)
    scoring.add_argument("current",type=Path)
    scoring.add_argument("--subset",action="store_true",help="Compare explicitly chosen matching subsets.")
    args=parser.parse_args()
    if args.mode=="generate":
        generate(args.directory,args.fresh,args.sample)
    else:
        print(json.dumps(compare(args.previous,args.current,args.subset),indent=2))
