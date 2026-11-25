#!/usr/bin/env python3
"""Fetch and verify pinned offline speech ingredients; the installed app never downloads a model."""
from hashlib import sha256
from pathlib import Path
from shutil import copyfileobj
from tempfile import NamedTemporaryFile
from urllib.request import urlopen
from zipfile import ZipFile
import os
import sys
import time

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build" / "speech"
CACHE = BUILD / "download"
ASSETS = ROOT / "assets" / "offline-speech"
MAVEN = "https://repo.maven.apache.org/maven2/"
MODEL = "vosk-model-small-en-us-0.15.zip"
INGREDIENTS = [
    (BUILD / "zxing-core.jar", MAVEN + "com/google/zxing/core/3.5.3/core-3.5.3.jar",
     "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82"),
    (CACHE / "jna.aar", MAVEN + "net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar",
     "7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0"),
    (CACHE / "vosk.aar", MAVEN + "com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar",
     "ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad"),
    (ASSETS / MODEL, "https://alphacephei.com/vosk/models/" + MODEL,
     "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
]
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

def checksum(path):
    digest = sha256()
    with path.open("rb") as in_file:
        for data in iter(lambda: in_file.read(1 << 20), b""):
            digest.update(data)
    return digest.hexdigest()

def fetch(path, url, expected):
    if path.exists() and checksum(path) == expected:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(3):
        tmp = None
        try:
            with NamedTemporaryFile(dir=path.parent, delete=False) as target:
                tmp = Path(target.name)
                with urlopen(url, timeout=60) as stream:
                    copyfileobj(stream, target)
            if checksum(tmp) != expected:
                raise ValueError("unexpected SHA-256 for " + path.name)
            os.replace(tmp, path)
            return
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
        finally:
            if tmp is not None and tmp.exists():
                tmp.unlink()

def unpack(aar, entry, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    with aar.open(entry) as src, dest.open("wb") as out:
        copyfileobj(src, out)

def main():
    for args in INGREDIENTS:
        fetch(*args)
    for name, lib in (("jna", "jnidispatch"), ("vosk", "vosk")):
        with ZipFile(CACHE / (name + ".aar")) as aar:
            unpack(aar, "classes.jar", BUILD / (name + ".jar"))
            for abi in ABIS:
                so = "lib" + lib + ".so"
                unpack(aar, "jni/" + abi + "/" + so, BUILD / "lib" / abi / so)
    print("Verified speech libraries and bundled English model.")

if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as e:
        sys.exit("Speech setup failed: " + str(e))
