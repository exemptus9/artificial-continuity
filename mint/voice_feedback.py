"""Private Mint feedback, with a cross-process guard for local synthesized speech."""
import fcntl
import json
import os
import shutil
import secrets
import subprocess
import tempfile
import threading
import time

MODES = ("Wake chime only","Wake and save tones","Wake and spoken saves","Visual only","Spoken guidance and saves")
VOLUMES = (35,65,100)
RATES = (135,175,215)
TYPED = {"save a line":"line","save a poem line":"line","save a word":"coinage",
         "save a coined word":"coinage","mark my place":"resume","bookmark my place":"resume"}
REPLIES = {
    "wake":"Ready for a command",
    "mark":"Moment saved","line":"Line captured","coinage":"Word captured","resume":"Place marked",
    "passage":"Passage saved","creative":"Creative candidate saved",
    "help":"After the wake chime, say save a line, save a word, mark my place, start a passage, or what do you hear.",
    "unclear":"Command unclear. Try again after the wake chime, or tap Keep moment.",
    "timeout":"Command window closed. Use the wake word to try again.",
    "no-passage":"No passage is open. Use the wake word and say start a passage.",
    "started":"Passage recording. Say finish passage when you are done.",
    "already-started":"Passage is already recording.",
    "finishing":"Passage ended. Saving the audio.",
    "pending":"Audio is waiting to save. Check storage in the archive.",
    "mic":"Microphone interrupted. Check the selected input. Capture will retry.",
    "storage":"Recording paused. Free storage space to resume.",
    "missed":"That bookmark missed its audio. Check the microphone and try again.",
    "decoder":"Voice recognition is unavailable. Use the overlay buttons and check the offline speech package.",
    "paused":"Laptop recording paused.",
    "last":"No confirmed laptop bookmark yet.",
    "status":"Laptop monitoring status.",
    "preview":"Sample feedback. Word captured.",
}
RECEIPTS = {"mark","line","coinage","resume","passage","creative"}
FAILURES = {"unclear","timeout","no-passage","pending","mic","storage","missed","decoder"}

def load(path, default):
    try:return json.loads(path.read_text())
    except (FileNotFoundError,ValueError):return default

def store(path, value):
    with tempfile.NamedTemporaryFile("w",dir=path.parent,delete=False) as out:
        json.dump(value,out)
    os.replace(out.name,path)

def preferences(root):
    data=load(root/"speech-options.json",{})
    return (data.get("rate") if data.get("rate") in RATES else 175,
            data.get("volume") if data.get("volume") in VOLUMES else 100)

def guarded(root):
    try:remaining=(root/"speech-hold").stat().st_mtime-time.time()
    except FileNotFoundError:return False
    return 0 < remaining <= 22

def hold(root, seconds):
    guard=root/"speech-hold";guard.touch(mode=0o600)
    until=time.time()+seconds
    os.utime(guard,(until,until))

def history(root):
    return load(root/"feedback-history.json",[])

def record(root,event,text):
    with (root/"feedback-history.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        entries=history(root);now=int(time.time()*1000)
        # A device retry every two seconds should produce one actionable alert.
        if event in ("pending","mic","storage","decoder") and any(x.get("event")==event and 0<=now-x.get("at",0)<60000 for x in entries):
            return False
        entries.insert(0,dict(at=now,event=event,text=text))
        store(root/"feedback-history.json",entries[:12])
        return True

def tone(volume,event):
    if event=="wake":first,step=660,330
    elif event in FAILURES:first,step=500,-180
    else:first,step=950,-350
    pattern=f"aevalsrc=0.10*sin(2*PI*({first}+{step}*gte(t\\,0.08))*t):d=0.19"
    return ["ffplay","-v","quiet","-nodisp","-autoexit","-volume",str(volume),"-f","lavfi","-i",pattern]

# Coalesce simultaneous receipts in this process; another overlay/worker's
# speech is serialized by flock. UI and capture threads never wait for playback.
_wait_lock=threading.Lock()
_waiting={}

def play(root,event,text,mode,rate,volume,defer=True):
    speech=(mode==MODES[4] or (mode==MODES[2] and event in RECEIPTS|{"preview"})) and event!="wake"
    speaker=(shutil.which("espeak-ng") or shutil.which("espeak")) if speech else None
    quiet=dict(stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    def chime():
        try:proc=subprocess.Popen(tone(volume,event),**quiet)
        except OSError:return "Visual feedback only"
        threading.Thread(target=proc.wait,daemon=True).start()
        return "Tone requested"
    if not speaker:return chime()
    lock=(root/"spoken-feedback.lock").open("a")
    try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except BlockingIOError:
        lock.close()
        if defer and event in RECEIPTS|FAILURES:
            key=str(root)
            ticket=secrets.token_hex(6)
            with _wait_lock:_waiting[key]=ticket
            def later():
                end=time.monotonic()+12
                while time.monotonic()<end:
                    time.sleep(.16)
                    with _wait_lock:
                        if _waiting.get(key)!=ticket:return
                    try:answer=play(root,event,text,mode,rate,volume,defer=False)
                    except OSError:break
                    if answer!="Speaker busy":break
                with _wait_lock:
                    if _waiting.get(key)==ticket:_waiting.pop(key,None)
            threading.Thread(target=later,daemon=True).start()
        return "Speaker busy"
    # Mark the gate before starting a speaker, including one started by a settings preview.
    hold(root,20)
    options=(["-s",str(rate)] if rate!=175 else [])+(["-a",str(volume)] if volume!=100 else [])
    try:proc=subprocess.Popen([speaker,*options,text],**quiet)
    except OSError:
        hold(root,.4);lock.close()
        return chime()
    def finish():
        try:
            result=proc.wait(timeout=16)
            if isinstance(result,int) and result!=0:chime()
        except subprocess.TimeoutExpired:
            proc.kill();proc.wait();chime()
        finally:
            hold(root,.55);lock.close()
    threading.Thread(target=finish,daemon=True).start()
    return "Local speech requested"

def emit(root,event,text=None,preview=None):
    if event.startswith("auto"):event="creative"
    message=text or REPLIES[event]
    if not preview:
        try:
            if not record(root,event,message):return message
        except OSError:
            pass  # Feedback storage failure must never stop the microphone.
    mode,rate,volume=preview or (load(root/"sound.json",MODES[0]),*preferences(root))
    audible=(mode in MODES and mode!="Visual only" and
             (event=="wake" or mode==MODES[4] or (mode in MODES[1:3] and event in RECEIPTS|{"preview"})))
    if not audible:return "Visual feedback only" if preview else message
    try:result=play(root,event,message,mode,rate,volume)
    except OSError:result="Visual feedback only"
    return result if preview else message
