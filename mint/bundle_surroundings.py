"""Fetch checksum-pinned audio model and offline runtimes for a release."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request
import zipfile

MODEL_URL="https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
MODEL_SHA="4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"


def cached(name,url,digest):
    cache=Path(os.environ.get("RECALL_DICTATION_CACHE",Path.home()/".cache"/"total-recall-build"))
    cache.mkdir(parents=True,exist_ok=True)
    path=cache/name
    if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==digest:return path
    with tempfile.NamedTemporaryFile(dir=cache,delete=False) as tmp:
        try:
            with urllib.request.urlopen(url,timeout=90) as src:shutil.copyfileobj(src,tmp)
            tmp.flush()
            if hashlib.sha256(Path(tmp.name).read_bytes()).hexdigest()!=digest:raise ValueError("Incorrect checksum for "+name)
            os.replace(tmp.name,path)
        finally:Path(tmp.name).unlink(missing_ok=True)
    return path


def bundle(destination):
    destination.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(cached("yamnet.tflite",MODEL_URL,MODEL_SHA),destination/"yamnet.tflite")
    for version,(url,digest) in WHEELS.items():
        wheel=cached(url.rsplit("/",1)[1],url,digest)
        with zipfile.ZipFile(wheel) as z:
            for name in z.namelist():
                # Retain the interpreter, its native dependencies, and license notices.
                if (name.startswith("ai_edge_litert/") and name.split("/")[-1] in FILES) or "license" in name.lower():
                    z.extract(name,destination/version)


FILES={"__init__.py","interpreter.py","metrics_interface.py","metrics_portable.py","_pywrap_litert_interpreter_wrapper.so","_pywrap_tensorflow_lite_metrics_wrapper.so","libLiteRt.so","libpywrap_litert_common.so"}
WHEELS={'cp311': ('https://files.pythonhosted.org/packages/0d/d1/032cd1300a9b40da97e9f93781b3c2c5f104c22157695a3bb5bd5531821f/ai_edge_litert-2.1.0-cp311-cp311-manylinux_2_27_x86_64.whl', 'dcf7d79374ab467cec19f2edadd3beb243ad17833f8c5ad59bf76712bf1449b1'), 'cp312': ('https://files.pythonhosted.org/packages/bb/16/0aface4ec3554304487d6436938684023c054828b4334f114f7db303b27a/ai_edge_litert-2.1.0-cp312-cp312-manylinux_2_27_x86_64.whl', '48d7e6e81baa3581e6b48920a73ab897301cc48aa5b89abdd35d9f06df632d4a'), 'cp313': ('https://files.pythonhosted.org/packages/96/c7/53b1f2627dbb0b307c1637e5f2cdbdc5a5f4ffecbebe75c8977d46590b6a/ai_edge_litert-2.1.0-cp313-cp313-manylinux_2_27_x86_64.whl', '41e2490cbf1d266663d451259731170b81aaa70b7dfef6d7188697e65c712c95')}

if __name__=="__main__":bundle(Path(sys.argv[1]))
