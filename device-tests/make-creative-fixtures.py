#!/usr/bin/env python3
"""Generate anonymous speech fixtures with FFmpeg/Flite, mono 16 kHz PCM."""
import gzip
from pathlib import Path
import struct
import subprocess
import tempfile

ASSETS = Path(__file__).resolve().parent / 'assets'
SCRIPTS = {
    'verse': ['I carry a spark into the night', 'I open my hands to catch the light',
              'I follow the birds into their flight', 'I paint the sky in black and white'],
    'address': ['The editor said keep that word for the next page.', 'Hey Recall.', 'Save that line.'],
    'coin': ['I call it glimbershine.', 'That is a word I made up for the light that stays after dark.'],
}

def choice_fixture(source):
    # Each turn is independently playable for save, cancel and timeout checks.
    with (ASSETS / 'creative-choice.pcm.gz').open('wb') as out:
        with gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=0) as archive:
            for phrase in ('The room has a paper lantern.', 'Hey Recall save as.', 'Line.', 'Cancel.'):
                source.write_text(phrase)
                audio = subprocess.check_output(['ffmpeg', '-hide_banner', '-loglevel', 'error',
                    '-f', 'lavfi', '-i', f'flite=textfile={source}:voice=slt', '-ar', '16000',
                    '-ac', '1', '-f', 's16le', '-'])
                archive.write(struct.pack('>I', len(audio)))
                archive.write(audio)
            unclear = ASSETS.parents[1] / 'android/res/raw/recall_unclear.ogg'
            cue = subprocess.check_output(['ffmpeg', '-hide_banner', '-loglevel', 'error',
                '-i', str(unclear), '-ar', '16000', '-ac', '1', '-f', 's16le', '-'])
            archive.write(struct.pack('>I', len(cue)))
            archive.write(cue)

def main():
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / 'line.txt'
        choice_fixture(source)
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
