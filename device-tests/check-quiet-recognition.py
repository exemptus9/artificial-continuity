"""Compare local live microphone front ends on synthetic, known English sentences (Linux/macOS)."""
import argparse
from array import array
import ctypes
import json
import math
import multiprocessing
from pathlib import Path
import random
import subprocess
import tempfile


SENTENCES = (
    "we decided to use the blue cover for the book", "please remind me to call tomorrow",
    "where did we put the keys", "the problem is we cannot open the file",
    "i need to call the printer tomorrow", "let me try a different ending",
    "we never finished the last chapter", "the garden was quiet this morning",
)
HOLDOUT = (
    "the window was open when the rain began", "i left a message just before noon",
    "that line belongs in the second verse", "the next step is to check the cable",
    "i almost forgot what i wanted to say", "we should read it out loud together",
    "the old green bicycle is behind the house", "please mark the last part for later",
)
CONDITIONS = (("Normal", 1, 0), ("Very faint", .0015, 1), ("Faint with noise", .004, 3))
MODES = ("raw", "legacy", "standard", "extended")
MODEL = None


def distance(reference, decoded):
    row = list(range(len(decoded) + 1))
    for i, word in enumerate(reference, 1):
        next_row = [i]
        for j, value in enumerate(decoded, 1):
            next_row.append(min(next_row[-1] + 1, row[j] + 1, row[j-1] + (word != value)))
        row = next_row
    return row[-1]


def configure_library(path, model):
    global MODEL, LIB
    LIB = ctypes.CDLL(str(path.resolve()))
    ptr, integer, string = ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p
    specs = {
        "set_log_level": (None, integer), "model_new": (ptr, string),
        "recognizer_new": (ptr, ptr, ctypes.c_float),
        "recognizer_accept_waveform": (integer, ptr, string, integer),
        "recognizer_result": (string, ptr), "recognizer_final_result": (string, ptr),
        "recognizer_free": (None, ptr),
    }
    for name, (result, *args) in specs.items():
        fn = getattr(LIB, "vosk_" + name)
        fn.restype, fn.argtypes = result, args
    LIB.vosk_set_log_level(-1)
    MODEL = LIB.vosk_model_new(bytes(model.resolve()))
    if not MODEL:
        raise RuntimeError("Could not open the local Vosk English model")


def decode(data):
    # Each recognizer inherits the same pristine native model/random state from the parent.
    rec = LIB.vosk_recognizer_new(MODEL, 16000)
    if not rec:
        raise RuntimeError("Could not create recognizer")
    words = []
    try:
        for pos in range(0, len(data), 3200):
            part = data[pos:pos+3200]
            if LIB.vosk_recognizer_accept_waveform(rec, part, len(part)):
                words.extend(json.loads(LIB.vosk_recognizer_result(rec))["text"].split())
        words.extend(json.loads(LIB.vosk_recognizer_final_result(rec))["text"].split())
    finally:
        LIB.vosk_recognizer_free(rec)
    return words


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--libvosk", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--holdout", action="store_true", help="Run the independent second set of sentences, voices and levels")
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    source = here.parent / "android/src/org/totalrecall/listening"
    conditions = CONDITIONS if not args.holdout else (("Normal", 1, 0), ("Very faint", .002, 1), ("Faint with noise", .003, 3))
    voices = ("rms", "slt", "awb") if args.holdout else ("slt", "kal", "awb")
    configure_library(args.libvosk, args.model)
    with tempfile.TemporaryDirectory() as classes:
        subprocess.run([args.javac, "-d", classes, source/"MicSignal.java",
                        source/"QuietSpeech.java", here/"host/MicTransform.java"], check=True)
        totals = {name: [0]*5 for name, _, _ in conditions}
        identical = {name: 0 for name, _, _ in conditions}
        for i, sentence in enumerate(HOLDOUT if args.holdout else SENTENCES):
            voice = voices[i % len(voices)]
            audio = subprocess.check_output(["ffmpeg", "-v", "error", "-f", "lavfi", "-i",
                f"flite=text={sentence}:voice={voice}", "-ar", "16000", "-ac", "1", "-f", "s16le", "-"])
            signal = array("h", bytes(64000)+audio+bytes(32000))
            for name, gain, noise in conditions:
                rng = random.Random(i)
                pcm = array("h", (round(v*gain+rng.uniform(-noise,noise)+2*noise*math.sin(2*math.pi*60*t/16000))
                                  for t,v in enumerate(signal))).tobytes()
                variants = [pcm]
                for mode in MODES[1:]:
                    variants.append(subprocess.check_output([args.java, "-Xmx32m", "-cp", classes,
                        "org.totalrecall.listening.MicTransform", mode], input=pcm))
                identical[name] += variants[1] == variants[2]
                # A fresh fork per variant also keeps comparison independent of decode order.
                with multiprocessing.get_context("fork").Pool(2, maxtasksperchild=1) as pool:
                    texts = pool.map(decode, variants, chunksize=1)
                reference = sentence.split()
                totals[name][0] += len(reference)
                for j, words in enumerate(texts, 1):
                    totals[name][j] += distance(reference, words)
        print("Condition | Reference words | Raw errors | Prior boost | Standard | Extended | Standard/prior PCM-identical sentences")
        for name,row in totals.items():
            print(" | ".join(map(str,[name,*row,identical[name]])))


if __name__ == "__main__":
    main()
