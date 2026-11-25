#!/usr/bin/env python3
"""Exercise Android's archive codec and verified transfer against the actual installed Mint service."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
RECEIPTS = ROOT / "device-tests/build/receipts"
PROBE = "org.totalrecall.smoke/org.totalrecall.listening.ArchiveProbe"

def phase(adb, name, pairing=None):
    arguments = [*adb, "shell", "am", "instrument", "-w", "-e", "phase", name]
    if pairing:
        if not re.fullmatch(r"TR1\.[A-Za-z0-9_=-]+", pairing):
            raise RuntimeError("Receiver returned an invalid private pairing code")
        arguments.extend(["-e", "pair", pairing])
    # The credential is ephemeral. It is never written to the receipt, log, or command echo.
    result = subprocess.run([*arguments, PROBE], capture_output=True, text=True, timeout=200)
    report = result.stdout + result.stderr
    if pairing:
        report = report.replace(pairing, "[private code]")
    (RECEIPTS / f"continuous-{name}.txt").write_text(report)
    print(report, flush=True)
    if result.returncode or f"outcome=PASS_{name}" not in report:
        raise RuntimeError(f"Android archive {name} phase failed")
    return dict(re.findall(r"^INSTRUMENTATION_RESULT: (\w+)=(.*)$", report, re.MULTILINE))

def main():
    args = argparse.ArgumentParser()
    args.add_argument("--desk", type=Path, default=ROOT / "mint/recall_desk.py")
    opts = args.parse_args()
    RECEIPTS.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ["ANDROID_SDK_ROOT"])
    adb = [str(sdk / "platform-tools/adb"), "-s", os.environ["ANDROID_SERIAL"]]
    if subprocess.check_output([*adb, "shell", "getprop", "ro.boot.qemu"], text=True).strip() != "1":
        raise RuntimeError("A disposable emulator is required")
    phase(adb, "codec")
    with tempfile.TemporaryDirectory(prefix="recall-archive-acceptance-") as tmp:
        home = Path(tmp)
        ready = home / "private-ready.json"
        env = dict(os.environ, HOME=str(home), TOTAL_RECALL_ARCHIVE_HOME=str(home / "vault"))
        log = home / "receiver.log"
        with log.open("w") as output:
            receiver = subprocess.Popen([sys.executable, str(opts.desk), "host", "--bind", "127.0.0.1",
                                         "--advertise", "10.0.2.2", "--port", "8789", "--web-port", "8790",
                                         "--ready-file", str(ready)], env=env, stdout=output, stderr=output)
        try:
            until = time.monotonic() + 25
            while not ready.is_file() and receiver.poll() is None and time.monotonic() < until:
                time.sleep(.2)
            if not ready.is_file():
                raise RuntimeError("Mint receiver did not create its private ready file")
            config = json.loads(ready.read_text())
            received = phase(adb, "transfer", config["pairingCode"])
            browser = next((path for name in ("google-chrome", "chromium", "chromium-browser")
                            if (path := shutil.which(name))), None)
            if not browser:
                raise RuntimeError("Chrome or Chromium is required for the browser acceptance step")
            owner_grant = home / "browser-grant"
            owner_grant.write_text(config["webUrl"])
            owner_grant.chmod(0o600)
            cmd = [sys.executable, str(ROOT / "mint/test_web_ui.py"), "--url-file", str(owner_grant),
                   "--expected-id", received["segment"], "--sha256", received["sha256"], "--chromium", browser, "--screenshots", str(RECEIPTS / "portal")]
            # Capture browser errors without disclosing the one-use local access URL.
            p = subprocess.run(cmd, text=True, capture_output=True, timeout=150)
            report = (p.stdout + p.stderr).replace(config["webUrl"], "[private owner URL]")
            (RECEIPTS / "continuous-browser.txt").write_text(report)
            print(report, flush=True)
            if p.returncode:
                raise RuntimeError("Browser did not verify Android's original segment")
        finally:
            receiver.terminate()
            try: receiver.wait(timeout=8)
            except subprocess.TimeoutExpired: receiver.kill(); receiver.wait()

if __name__ == "__main__":
    main()
