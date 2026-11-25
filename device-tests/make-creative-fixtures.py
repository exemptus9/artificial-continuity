#!/usr/bin/env python3
"""Generate the two anonymous test performances with FFmpeg/Flite, mono 16 kHz PCM."""
import gzip
from pathlib import Path
import subprocess
import tempfile

ASSETS = Path(__file__).resolve().parent / 'assets'
SCRIPTS = {
    'verse': ['I carry a spark into the night', 'I open my hands to catch the light',
              'I follow the birds into their flight', 'I paint the sky in black and white'],
    'coin': ['I call it glimbershine.', 'That is a word I made up for the light that stays after dark.'],
}

def main():
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / 'line.txt'
        for name, lines in SCRIPTS.items():
            result = bytearray()
            for line in lines:
                if result:
                    result += bytes(32000)  # one second between phrases
                source.write_text(line)
                result += subprocess.check_output(['ffmpeg', '-hide_banner', '-loglevel', 'error',
                    '-f', 'lavfi', '-i', f'flite=textfile={source}:voice=slt', '-ar', '16000',
                    '-ac', '1', '-f', 's16le', '-'])
            with (ASSETS / f'creative-{name}.pcm.gz').open('wb') as out:
                with gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=0) as stream:
                    stream.write(result)
            print(name, len(result) // 2, 'samples')

if __name__ == '__main__':
    main()
