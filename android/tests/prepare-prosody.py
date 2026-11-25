"""Create the offline rhyme index deterministically from the pinned CMUdict wheel."""
from pathlib import Path
from hashlib import sha256
from zipfile import ZipFile
from io import BytesIO
import gzip
import re
import subprocess
import sys

BASE = Path(__file__).resolve().parents[1]
CACHE = BASE / "build/speech/download"
WHEEL = CACHE / "cmudict-1.1.3-py3-none-any.whl"
WHEEL_HASH = "e4d421341bf9fa774bcded8e7d6c5d73a1bf8f88edbe129207713850abac4995"
OUTPUT = BASE / "assets/listening/cmudict-prosody.tsv.gz"

def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    if not WHEEL.exists():
        subprocess.run([sys.executable, "-m", "pip", "download", "--only-binary=:all:", "--no-deps",
                        "--dest", str(CACHE), "cmudict==1.1.3"], check=True)
    data = WHEEL.read_bytes()
    if sha256(data).hexdigest() != WHEEL_HASH:
        raise ValueError("CMUdict wheel SHA-256 mismatch")
    words = {}
    with ZipFile(BytesIO(data)) as z:
        license_file = BASE / "assets/licenses/CMUdict-LICENSE.txt"
        license_file.parent.mkdir(parents=True, exist_ok=True)
        license_file.write_bytes(z.read("cmudict/data/LICENSE"))
        for line in z.read("cmudict/data/cmudict.dict").decode("utf-8").splitlines():
            fields = line.partition("#")[0].split()
            if len(fields) < 2 or not re.fullmatch("[a-z][a-z']*", fields[0]): continue
            word, phones = fields[0], fields[1:]
            vowels = [i for i, ph in enumerate(phones) if ph[-1].isdigit()]
            if not vowels: continue
            stressed = [i for i in vowels if phones[i][-1] in "12"]
            start = (stressed or vowels)[-1]
            rhyme = "-".join(re.sub("[012]", "", ph) for ph in phones[start:])
            words[word] = (str(len(vowels)), rhyme)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("wb") as out, gzip.GzipFile(filename="", mode="wb", fileobj=out, mtime=0) as gz:
        for word, (count, rhyme) in sorted(words.items()):
            gz.write(f"{word}\t{count}\t{rhyme}\n".encode("ascii"))
    print(f"CMUdict: {len(words)} English spellings; {len(OUTPUT.read_bytes())} bytes compressed")

if __name__ == "__main__": main()
