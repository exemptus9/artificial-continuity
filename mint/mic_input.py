"""Linux microphone selection, raw input measurements and recognition-only gain."""
from array import array
import json
import math
import subprocess

DEFAULT_DEVICE = "default"
GAIN_CHOICES = {"Original level":0, "Soft voice (+6 dB)":6, "Very soft voice (+12 dB)":12}
RATE = 16000


def valid_device(value):
    return isinstance(value, str) and 0 < len(value) <= 256 and all(c.isprintable() for c in value)


def arguments(system, device):
    if system not in ("pulse","alsa") or not valid_device(device):
        raise ValueError("Choose a valid microphone")
    return ["-f",system,"-i",device]


def device_list(system):
    """Return stable input IDs plus display names. The user can also enter an ID."""
    choices = {}
    if system == "pulse":
        data = subprocess.check_output(["pactl","--format=json","list","sources"],timeout=3,
                                       stderr=subprocess.DEVNULL, text=True)
        for item in json.loads(data):
            name = item.get("name","")
            if not valid_device(name) or name.endswith(".monitor") or item.get("properties",{}).get("device.class") == "monitor":
                continue
            choices[name] = item.get("description") or name
    elif system == "alsa":
        data = subprocess.check_output(["arecord","-L"],timeout=3,stderr=subprocess.DEVNULL,text=True)
        for row in data.splitlines():
            if row.strip() and not row[0].isspace() and row.startswith(("plughw:", "hw:", "sysdefault:")) and valid_device(row):
                choices[row] = row
    return choices


def recognition_pcm(pcm, gain):
    """Boost just the recognition copy. Preserve timing and keep all samples in range."""
    if gain not in (6,12):
        return pcm
    multiplier = 10 ** (gain / 20)
    return array("h",(max(-32768,min(32767,round(v * multiplier))) for v in memoryview(pcm).cast("h"))).tobytes()


def dbfs(amplitude):
    return round(20*math.log10(amplitude/32768),1) if amplitude else None


def db_text(value):
    return "−∞" if value is None else f"{value:g}"


class LevelWindow:
    def __init__(self):
        self.count = self.energy = self.peak = self.clipped = self.quiet_ms = 0

    def add(self, pcm):
        samples = memoryview(pcm).cast("h")
        if not samples:
            return
        self.count += len(samples)
        self.energy += sum(v*v for v in samples)
        self.peak = max(self.peak, -min(samples), max(samples))
        self.clipped += sum(v <= -32768 or v >= 32767 for v in samples)

    def snapshot(self):
        rms = math.sqrt(self.energy/max(self.count,1))
        duration = round(self.count*1000/RATE)
        if rms < 130:  # Roughly -48 dBFS. Silence can be entirely normal between words.
            self.quiet_ms += duration
        else:
            self.quiet_ms = 0
        near_rail = self.clipped >= max(2, self.count*0.001)
        hint = ("Input clipping · lower microphone level" if near_rail else
                "Very low input · check mic if speaking" if self.quiet_ms >= 3000 else "")
        result = dict(rmsDb=dbfs(rms),peakDb=dbfs(self.peak),level=min(100, round(rms*100/5500)),
                      quietMs=self.quiet_ms,inputHint=hint,clippedSamples=self.clipped)
        self.count = self.energy = self.peak = self.clipped = 0
        return result


def microphone_check(input_args):
    process = subprocess.run(["ffmpeg","-nostdin","-hide_banner","-v","error",*input_args,
                              "-t","3","-vn","-ar",str(RATE),"-ac","1","-f","s16le","pipe:1"],
                             capture_output=True,timeout=8)
    if process.returncode or len(process.stdout)<RATE*2:
        raise OSError("Input check failed. Check the device, backend and microphone permission.")
    levels = LevelWindow()
    levels.add(process.stdout)
    return levels.snapshot()


def report(result):
    return f"Average {db_text(result['rmsDb'])} dBFS · peak {db_text(result['peakDb'])} dBFS"
