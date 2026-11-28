"""Prepare pinned redistributable upstream files when building the amd64 installer."""
import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request
import zipfile

MODEL = "vosk-model-small-en-us-0.15"
WHEEL = "vosk-0.3.45-py3-none-manylinux_2_12_x86_64.manylinux2010_x86_64.whl"
ROOT = Path(__file__).resolve().parent


def source(cache, name, url, expected, fallback=None):
    local = cache / name
    if not local.is_file() and fallback and fallback.is_file():
        local = fallback
    if not local.is_file():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as out:
            try:
                with urllib.request.urlopen(url, timeout=90) as src:
                    shutil.copyfileobj(src, out)
                out.flush()
                if hashlib.sha256(Path(out.name).read_bytes()).hexdigest() != expected:
                    raise ValueError("Unexpected checksum for " + name)
                os.replace(out.name, local)
            finally:
                Path(out.name).unlink(missing_ok=True)
    if hashlib.sha256(local.read_bytes()).hexdigest() != expected:
        raise ValueError("Unexpected checksum for " + name)
    return zipfile.ZipFile(local)


def bundle(destination):
    cache = Path(os.environ.get("RECALL_DICTATION_CACHE", Path.home()/".cache"/"total-recall-build"))
    cache.mkdir(parents=True, exist_ok=True)
    destination.mkdir(parents=True)
    distro = Path("/usr/share/common-licenses/Apache-2.0")
    shutil.copyfile(distro, destination/"LICENSE-Apache-2.0")
    with source(cache,WHEEL,"https://files.pythonhosted.org/packages/fc/ca/83398cfcd557360a3d7b2d732aee1c5f6999f68618d1645f38d53e14c9ff/"+WHEEL,
                "25e025093c4399d7278f543568ed8cc5460ac3a4bf48c23673ace1e25d26619f") as wheel:
        (destination/"libvosk.so").write_bytes(wheel.read("vosk/libvosk.so"))
    with source(cache,MODEL+".zip","https://alphacephei.com/vosk/models/"+MODEL+".zip",
                "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",ROOT.parent/"android/assets/offline-speech"/(MODEL+".zip")) as model:
        for member in model.infolist():
            rel = Path(member.filename).relative_to(MODEL)
            if member.is_dir(): continue
            if ".." in rel.parts: raise ValueError("Invalid model path")
            path = destination / "model" / rel
            path.parent.mkdir(parents=True,exist_ok=True)
            with model.open(member) as src, path.open("wb") as out:
                shutil.copyfileobj(src,out)


if __name__ == "__main__":
    bundle(Path(sys.argv[1]))
