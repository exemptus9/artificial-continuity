"""Opt-in Mint microphone recorder, local segment spool and floating controls."""
from array import array
import base64
from contextlib import closing
import fcntl
import hashlib
import http.client
import io
import json
import math
import os
from pathlib import Path
import re
import secrets
import selectors
import queue
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import wave
import webbrowser
import zipfile

import situation
import live_words
import voice_feedback
import voice_check
import mic_input
import speech_context
import recall_archive as storage

HOST="total-recall-archive-production.up.railway.app"
RATE=16000
BYTES_PER_SECOND=2*RATE
BASE={"source":"pulse","device":"default","recognitionGain":0,"creativeCatches":False,"quality":32,"seconds":60,"keep":30,"journal":False,"commands":True,"prune":False,"network":"Any network"}
# The spool prefix travels with the original PCM through an interrupted save.
# Keep legacy mark/passage prefixes readable for already-running recorders.
KEEP_TYPES = {
    "mark": ("Laptop bookmark", "Manual keep"),
    "passage": ("Spoken passage", "Poem line"),
    "line": ("Poem line", "Poem line"),
    "coinage": ("Coined word", "Forge Lexicon"),
    "resume": ("Pick up here", "Resume point"),
}

CREATIVE_CUES = {
    "autoword": ("Possible coined word","Forge Lexicon",re.compile(r"\b(?:i (?:just )?(?:coined|invented) (?:a|the|this) (?:new )?(?:word|term)|my (?:new|coined) word)\b")),
    "autoline": ("Possible poem line","Poem line",re.compile(r"\b(?:here(?:'s| is) (?:a|the) (?:refrain|poem|stanza)|the (?:opening line|next (?:stanza|verse)) (?:is|goes)|a line for (?:a|the|my) poem)\b")),
    "autorevision": ("Possible draft revision","Draft revision",re.compile(r"\b(?:let me (?:try|say) that line again|a better (?:line|ending) (?:is|would be)|replace the last line)\b")),
}
KEEP_TYPES.update({kind:(title,category) for kind,(title,category,_) in CREATIVE_CUES.items()})

def creative_cue(words):
    text=words.casefold().replace("’","'")
    return next((key for key,(_,_,pattern) in CREATIVE_CUES.items() if pattern.search(text)),None)

class CreativeTail:
    """A short trailing window appended to the optional creative bookmark on disk."""
    def __init__(self,root,kind,recent,now):
        start=now-round(len(recent)*1000/BYTES_PER_SECOND)
        self.path=root/f"{kind}-{start}-{secrets.token_hex(6)}.pcm.part"
        self.file=self.path.open("xb")
        self.file.write(recent)
        self.remaining=5*BYTES_PER_SECOND

    def add(self,block):
        count=min(len(block),self.remaining)
        self.file.write(block[:count])
        self.remaining-=count
        return self.remaining==0

    def finish(self):
        self.file.flush();os.fsync(self.file.fileno());self.file.close()
        self.path.rename(self.path.with_suffix(""))

SOUND_MODES=voice_feedback.MODES
sound_cue=voice_feedback.emit

def request_keep(root, kind):
    if kind not in ("mark","line","coinage","resume"):
        raise ValueError("Unknown bookmark type")
    status = read(root/"status.json", {})
    age = storage.now() - status.get("at", 0)
    if (not (root/"enabled").exists() or status.get("state") != "Recording"
            or not 0 <= age < 6500 or not status.get("stream")):
        raise ValueError("Waiting for live laptop audio")
    # Unique files preserve two different catches made within the same audio block.
    request_id = secrets.token_hex(8)
    write(root/f"keep-{request_id}.request", {
        "stream": status["stream"], "kind": kind, "at": storage.now()
    })
    return request_id

def collect_keeps(root, stream_id, ring, end_ms, lookback):
    """Save up to the click time, using only samples from this microphone stream."""
    for request in sorted(root.glob("keep-*.request")):
        data = read(request, {})
        stamp = data.get("at")
        kind = data.get("kind")
        missed = None
        if (data.get("stream") != stream_id or kind not in KEEP_TYPES
                or type(stamp) is not int or end_ms - stamp > 6500):
            missed = "Microphone changed before this moment could be kept"
        elif stamp > end_ms + 1000:
            missed = "Laptop clock changed before this moment could be kept"
        else:
            # A just-clicked control can be a few milliseconds ahead of this block.
            available_end = max(0, len(ring) - max(0, end_ms - stamp) * BYTES_PER_SECOND // 1000)
            count = min(lookback * BYTES_PER_SECOND, available_end)
            if count:
                start = min(stamp, end_ms) - round(count * 1000 / BYTES_PER_SECOND)
                identifier = request.stem.split("-", 1)[1]
                path = root/f"{kind}-{start}-{identifier}.pcm.part"
                with path.open("wb") as output:
                    output.write(memoryview(ring)[available_end-count:available_end])
                    output.flush()
                    os.fsync(output.fileno())
                path.rename(path.with_suffix(""))
            else:
                missed = "No buffered audio was available at this moment"
        if missed:
            sound_cue(root,"missed")
            write(root/"missed-keep.json", {"at": storage.now(), "text": missed})
        request.unlink(missing_ok=True)

def folder(arc):
    path=arc.root/"laptop-recorder"
    path.mkdir(mode=0o700,exist_ok=True)
    return path
def write(path,value):
    with tempfile.NamedTemporaryFile("w",dir=path.parent,delete=False) as f:
        json.dump(value,f);f.flush()
        if path.name not in ("status.json","sync.json"):os.fsync(f.fileno())
    os.replace(f.name,path)
def read(path,default):
    try:return json.loads(path.read_text())
    except (FileNotFoundError,ValueError):return default
def config(path):
    raw=read(path/"settings.json",{})
    cfg={k:raw[k] if raw.get(k) in choices else BASE[k] for k,choices in {
        "creativeCatches":(True,False),"recognitionGain":(0,6,12),"source":("pulse","alsa"),"quality":(24,32,48),"seconds":(30,60,120),"keep":(15,30,60),"prune":(True,False),"journal":(True,False),"commands":(True,False),"network":("Any network","Wi-Fi only","Prefer Wi-Fi for 1 hour")}.items()}
    device = raw.get("device")
    cfg["device"] = device if mic_input.valid_device(device) else "default"
    return cfg
def credentials(code):
    if not code.startswith("TR1.") or len(code)>1024:raise ValueError("Paste the pairing code from the hosted archive.")
    try:obj=json.loads(base64.urlsafe_b64decode(code[4:]+"==="))
    except (ValueError,UnicodeError):raise ValueError("Invalid pairing code.") from None
    if not isinstance(obj,dict) or obj.get("url")!="https://"+HOST or obj.get("tls")!="public-ca" or not re.fullmatch("[a-f0-9]{64}",str(obj.get("token",""))):
        raise ValueError("Use Pair phone on your hosted archive to obtain this device credential.")
    return obj["token"]
def http(token,method,route,body=None,headers=None):
    h={"Authorization":"Bearer "+token,**(headers or {})}
    with closing(http.client.HTTPSConnection(HOST,timeout=25)) as conn:
        conn.request(method,route,body,h);r=conn.getresponse();raw=r.read(65536)
        if r.status!=200:raise OSError("Hosted archive returned HTTP "+str(r.status))
        return json.loads(raw)
def stage_record(arc,uid,path,meta):
    """The same validated, resumable protocol used by phone uploads."""
    receipt=arc.begin(meta)
    with path.open("rb") as src:
        src.seek(receipt["offset"])
        while not receipt["complete"]:
            block=src.read(storage.MAX_CHUNK)
            if not block:raise ValueError("Incomplete staging file")
            receipt=arc.put(uid,receipt["offset"],block)
    if receipt["sha256"]!=meta["sha256"]:raise ValueError("Original checksum mismatch")
def on_wifi():
    try:
        route=subprocess.check_output(["ip","-j","route","get", "1.1.1.1"],timeout=3)
        device=json.loads(route)[0]["dev"]
        return (Path("/sys/class/net")/device/"wireless").is_dir()
    except (OSError,KeyError,ValueError,IndexError,subprocess.SubprocessError):
        return False

def wait_for_wifi(root,uid):
    policy=config(root)["network"]
    if policy=="Any network" or on_wifi():return False
    if policy=="Wi-Fi only":return True
    ticket=read(root/"wifi-wait.json",{})
    if "since" not in ticket:
        ticket={"since":time.time()};write(root/"wifi-wait.json",ticket)
    return time.time()-ticket["since"]<3600

def limit_notice(root,kind,text):
    path=root/(kind+"-limit.json")
    if not text:
        path.unlink(missing_ok=True);return
    previous=read(path,{})
    if previous.get("text")==text and time.time()-previous["at"]<3600:return
    write(path,{"text":text,"at":time.time()})
    if shutil.which("notify-send"):
        try:subprocess.run(["notify-send","-u","critical","Total Recall needs attention",text],timeout=3,check=False,stderr=subprocess.DEVNULL)
        except (OSError,subprocess.SubprocessError):pass

def push(arc,root,done):
    ledger=root/"sent.sqlite3"
    with closing(sqlite3.connect(ledger)) as db:
        db.execute("CREATE TABLE IF NOT EXISTS sent(id TEXT PRIMARY KEY)")
        db.execute("ATTACH DATABASE ? AS arc",(str(arc.path),))
        while not done.is_set():
            token=read(root/"pair.json","")
            if not token:
                limit_notice(root,"upload","")
                done.wait(2);continue
            row=db.execute("""SELECT a.id,a.metadata,a.file FROM arc.archive_uploads a LEFT JOIN sent s ON s.id=a.id
                WHERE s.id IS NULL AND a.state='complete' AND (a.kind IN ('segment','journal') AND a.id GLOB 'laptop-*' OR a.kind='clip' AND a.id GLOB 'desk-keep-*') ORDER BY a.started LIMIT 1""").fetchone()
            if not row:
                (root/"wifi-wait.json").unlink(missing_ok=True);limit_notice(root,"upload","")
                if read(root/"sync.json","")!="Caught up":write(root/"sync.json","Caught up")
                done.wait(5);continue
            uid,meta,path=row;manifest=json.loads(meta)
            if wait_for_wifi(root,uid):
                write(root/"sync.json","Waiting for preferred Wi-Fi");done.wait(20);continue
            try:
                remote_id=uid
                if manifest["kind"]=="clip":
                    saved=db.execute("SELECT metadata,audio FROM arc.clips WHERE id=?",(uid,)).fetchone()
                    if not saved:continue
                    cm=json.loads(saved[0]);src=io.BytesIO()
                    with zipfile.ZipFile(src,"w") as z:
                        z.writestr(zipfile.ZipInfo("index.json"),json.dumps({"schema":arc.desk.FORMAT,"clips":[cm]}))
                        z.writestr(zipfile.ZipInfo(cm["audio"]),saved[1])
                    checksum=hashlib.sha256(src.getbuffer()).hexdigest()
                    remote_id=uid+"-"+checksum[:24]
                    manifest.update(id=remote_id,sha256=checksum,bytes=src.tell())
                else:src=(arc.root/path).open("rb")
                with src:
                    receipt=http(token,"POST","/v2/uploads",json.dumps(manifest).encode(),{"Content-Type":"application/json"})
                    while not receipt["complete"]:
                        if read(root/"pair.json","")!=token or done.is_set() or wait_for_wifi(root,uid):break
                        offset=receipt["offset"]
                        if type(offset)!=int or not 0<=offset<manifest["bytes"]:raise OSError("Invalid upload offset")
                        src.seek(offset);block=src.read(storage.MAX_CHUNK)
                        if not block:raise OSError("Missing local bytes")
                        reply=http(token,"PUT","/v2/uploads/"+remote_id,block,{"Content-Type":"application/octet-stream","X-Upload-Offset":str(offset)})
                        if reply.get("offset")!=offset+len(block):raise OSError("Transfer did not advance")
                        receipt=reply
                if receipt["complete"] and (receipt["id"],receipt["bytes"],receipt["sha256"])==(remote_id,manifest["bytes"],manifest["sha256"]):
                    db.execute("INSERT OR IGNORE INTO sent VALUES(?)",(uid,));db.commit()
                    limit_notice(root,"upload","");write(root/"sync.json","Last upload verified")
                elif receipt["complete"]:raise OSError("Receipt mismatch")
            except (OSError,ValueError,KeyError,http.client.HTTPException) as e:
                if "HTTP 410" in str(e):
                    db.execute("INSERT OR IGNORE INTO sent VALUES(?)",(uid,));db.commit()
                else:
                    if any(code in str(e) for code in ("HTTP 429","HTTP 507")):
                        limit_notice(root,"upload","Hosted upload limit reached. Open the archive to check storage and the monthly allowance.")
                    write(root/"sync.json","Upload waiting · "+str(e)[:90]);done.wait(20)
def finish(arc,path,cfg):
    size=path.stat().st_size//2*2
    if not size:path.unlink();return
    start=int(path.stem.split("-")[1]);ms=round(size*1000/BYTES_PER_SECOND)
    if path.name.split("-")[0] in ("quietcheck","quietjournal") and ms>=2000:
        with path.open("rb") as src:
            silent=all(all(-4<=v<=4 for v in array("h",chunk)) for chunk in iter(lambda:src.read(65536),b""))
        if silent:
            with closing(sqlite3.connect(path.parent/"quiet.sqlite3")) as db,db:
                db.execute("CREATE TABLE IF NOT EXISTS omitted(id TEXT PRIMARY KEY,start INTEGER,duration INTEGER)")
                db.execute("INSERT OR IGNORE INTO omitted VALUES(?,?,?)",(path.stem,start,ms))
            path.unlink()
            write(path.parent/"last-omission.json",{"at":start,"duration":ms})
            return
    prefix=path.name.split("-",1)[0]
    mark=prefix in KEEP_TYPES
    title,category=KEEP_TYPES.get(prefix,("Laptop microphone",""))
    uid=("desk-keep-" if mark else "laptop-")+path.stem.split("-",1)[1]
    with tempfile.TemporaryDirectory(dir=path.parent) as tmp:
        if mark:
            wav=Path(tmp)/"clip.wav"
            with wave.open(str(wav),"wb") as out,path.open("rb") as src:
                out.setparams((1,2,RATE,0,"NONE",""));out.writeframes(src.read(size))
            archive=Path(tmp)/"clip.zip"
            meta=dict(id=uid,title=title,startedAtMillis=start,durationMs=ms,sha256=storage.sha_file(wav),
                audio="audio/"+uid+".wav",category=category,reason="Possible creative phrase heard by the offline recognizer" if prefix in CREATIVE_CUES else "Passage requested by voice" if prefix=="passage" else "Requested on laptop",transcript="")
            with zipfile.ZipFile(archive,"w") as z:
                # A restarted ingest must produce the very same transfer bytes.
                z.writestr(zipfile.ZipInfo(meta["audio"]),wav.read_bytes())
                z.writestr(zipfile.ZipInfo("index.json"),json.dumps({"schema":arc.desk.FORMAT,"clips":[meta]}))
            file=archive;kind="clip";mime="application/zip"
        else:
            file=Path(tmp)/"segment.aac"
            subprocess.run(["ffmpeg","-nostdin","-loglevel","error","-f","s16le","-ar",str(RATE),"-ac","1","-i",str(path),
                "-c:a","aac","-b:a",str(cfg["quality"])+"k","-f","adts",str(file)],check=True,timeout=60)
            kind="segment";mime="audio/aac"
        stage_record(arc,uid,file,dict(id=uid,kind=kind,title=title,
            sessionId="Mint laptop",startedAtMillis=start,durationMs=ms,bytes=file.stat().st_size,sha256=storage.sha_file(file),mediaType=mime))
        if path.name.split("-")[0] in ("journal","quietjournal"):
            lines=[]
            def phrase(seconds,text):
                stamp=time.strftime("%Y-%m-%d %H:%M:%S Z",time.gmtime(start/1000+seconds))
                polished=speech_context.correct(text)
                lines.append(stamp+"  "+polished)
                if polished!=text:lines.append("  Originally heard · "+text)
            with path.open("rb") as pcm:situation.scan(pcm,situation.new_decoder(),0,True,phrase)
            if lines:
                text=Path(tmp)/"journal.txt";text.write_text("Total Recall · Mint microphone\n"+"\n".join(lines)+"\n",encoding="utf-8")
                journal_id="laptop-journal-"+path.stem.split("-",1)[1]
                stage_record(arc,journal_id,text,dict(id=journal_id,kind="journal",sessionId="Mint laptop",title="Laptop speech journal",startedAtMillis=start,durationMs=ms,bytes=text.stat().st_size,sha256=storage.sha_file(text),mediaType="text/plain"))
    receipt = dict(at=start, duration=ms, kind=kind, id=uid, title=title, savedAt=storage.now())
    if mark:
        # Segment rollover must never replace the link to a deliberately kept clip.
        receipt_file = path.parent/("last-candidate.json" if prefix in CREATIVE_CUES else "last-keep.json")
        previous = read(receipt_file, {})
        if start + ms >= previous.get("at", 0) + previous.get("duration", 0):
            write(receipt_file, receipt)
    write(path.parent/"last.json", receipt)
    path.unlink()
    if mark:
        event="creative" if prefix in CREATIVE_CUES else prefix
        detailed=read(path.parent/"sound.json",SOUND_MODES[0])==SOUND_MODES[4]
        text=f"{voice_feedback.REPLIES[event]}. {ms/1000:.1f} seconds on this laptop." if detailed else None
        sound_cue(path.parent,event,text)
def room(arc,root):
    reserved=sum(x.stat().st_size for x in root.glob("*.pcm*") if x.is_file())
    capacity=arc.status()
    return reserved<64*1024**2 and capacity["availableBytes"]>8*1024**2 and capacity["freeBytes"]>capacity["floorBytes"]+16*1024**2
def ingest(arc,root,done):
    while True:
        files=sorted(root.glob("*.pcm"),key=lambda p:(p.name.split("-",1)[0] not in KEEP_TYPES,p.name))
        if not files:
            if done.is_set():return
            done.wait(.4);continue
        try:
            finish(arc,files[0],config(root))
            (root/"save-error.json").unlink(missing_ok=True)
        except Exception as e:
            write(root/"save-error.json","Waiting to save · "+str(e)[:100])
            sound_cue(root,"pending")
            if done.wait(4):return
def capture(arc,root,cfg,input_args):
    """Capture one uninterrupted stream; rollover never reopens the microphone."""
    args=["ffmpeg","-nostdin","-hide_banner","-loglevel","error",*input_args,"-vn","-ar",str(RATE),"-ac","1","-f","s16le","pipe:1"]
    proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    ring=bytearray();tail=b"";segment=None;seg_bytes=0;start=0;report=0;last_input=time.monotonic()
    feed=live_words.WordFeed(root)
    voice_feed=live_words.WordFeed(root,"voice-control.fifo")
    practice=live_words.WordFeed(root,voice_check.FIFO)
    requests=queue.SimpleQueue();passage=None;passage_start=0
    candidate=None;last_candidate=float("-inf")
    counter=0;awake=0;event="";event_at=0;cue="";cue_at=0;last_cue=""
    def flush_passage():
        nonlocal passage,passage_start
        if passage:
            path=root/f"passage-{passage_start}-{secrets.token_hex(6)}.pcm.part"
            path.write_bytes(passage);path.rename(path.with_suffix(""))
        passage=None
    def heard(words):
        nonlocal counter,awake,event,event_at,cue,cue_at,last_cue,last_candidate
        if not (root/"enabled").exists() or voice_feedback.guarded(root):return
        counter+=len(words.split());cmd=words.lower().strip()
        if cfg.get("creativeCatches") and time.monotonic()-last_candidate>=90:
            kind=creative_cue(words)
            if kind:
                requests.put(kind);last_candidate=time.monotonic()
        matched=re.match(r"^(?:hey recall|total recall|hey lexi|lexi)(?: |$)",cmd)
        if cfg.get("commands",True):
            if matched:
                awake=time.monotonic()+8;cmd=cmd[matched.end():]
                event="Wake heard · ready for a command";event_at=time.monotonic()
                sound_cue(root,"wake")
            if cmd in ("save that","save this","keep that","remember that","keep the last minute","save the last minute","you missed one"):
                (root/"bookmark").touch(mode=0o600);event="Save requested · recent audio";event_at=time.monotonic();awake=0
            elif cmd in ("finish passage","finish the poem","finish the story","finish the draft"):
                requests.put("finish");awake=0
            elif cmd and time.monotonic()<awake:
                if cmd in voice_feedback.TYPED:
                    requests.put(voice_feedback.TYPED[cmd]);event="Bookmark requested"
                elif cmd in ("what can i say","help","help me"):
                    event=sound_cue(root,"help")
                elif cmd in ("status","how much can i save"):
                    recent=min(cfg["keep"],len(ring)//BYTES_PER_SECOND)
                    event=sound_cue(root,"status",f"Laptop microphone active. {recent} seconds available to keep.")
                elif cmd in ("what did you save","last save"):
                    saved=read(root/"last-keep.json",{})
                    label=saved.get("title","")
                    duration=saved.get("duration",0)
                    if label not in (t for t,_ in KEEP_TYPES.values()):label="Bookmark"
                    event=sound_cue(root,"last",f"{label} saved on this laptop, {duration/1000:.1f} seconds." if saved else None)
                elif cmd in ("pause recall","pause recording","stop listening"):
                    (root/"enabled").unlink(missing_ok=True);event=sound_cue(root,"paused")
                elif cmd in ("start a passage","start a poem","start a story","start a draft"):
                    requests.put("start");event="Passage requested"
                else:event=sound_cue(root,"unclear")
                awake=0;event_at=time.monotonic()
        if cmd:
            found=situation.interpret([(speech_context.correct(words),"",None)])
            if found and found[0]["label"]!="Source excerpt":
                label=found[0]["label"]
                if label!=cue:last_cue, cue=cue,label
                cue_at=time.monotonic()
    heard.command_open=lambda: time.monotonic()<awake
    voice=live_words.LiveWords(root,"voice-control.fifo",heard) if cfg.get("commands",True) or cfg.get("creativeCatches") else None
    stream_id=secrets.token_hex(12)
    stream_pos=secrets.randbits(61)
    began=time.monotonic()
    levels=mic_input.LevelWindow()
    reported_recognition_error=False
    def seal():
        nonlocal segment,seg_bytes
        if segment:
            segment.flush();os.fsync(segment.fileno());path=Path(segment.name);segment.close();segment=None
            path.rename(path.with_suffix(""));seg_bytes=0
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(proc.stdout,selectors.EVENT_READ)
            while (root/"enabled").exists():
                if not selector.select(.5):
                    if time.monotonic()-last_input>6:raise OSError("No microphone input; retrying")
                    continue
                block=tail+os.read(proc.stdout.fileno(),8192)
                if len(block)<=len(tail):raise OSError("Microphone unavailable; retrying")
                tail=block[len(block)//2*2:];block=block[:len(block)//2*2]
                now=time.time_ns()//1000000
                if (segment and abs(now-(start+seg_bytes*1000/BYTES_PER_SECOND))>4000) or time.monotonic()-last_input>3:
                    if candidate:candidate.finish();candidate=None
                    seal();flush_passage();ring.clear()
                    stream_id=secrets.token_hex(12);stream_pos=secrets.randbits(61)
                    levels=mic_input.LevelWindow()
                last_input=time.monotonic()
                if segment is None:
                    if not room(arc,root):raise OSError("Storage low; waiting for space")
                    start=now-round(len(block)*1000/BYTES_PER_SECOND)
                    prefix="quietcheck" if cfg.get("prune") else "segment"
                    if cfg.get("journal"):prefix="quietjournal" if cfg.get("prune") else "journal"
                    segment=(root/f"{prefix}-{start}-{secrets.token_hex(6)}.pcm.part").open("xb")
                levels.add(block)
                practice.send(stream_pos,block)
                recognized = mic_input.recognition_pcm(block,cfg.get("recognitionGain",0))
                if not voice_feedback.guarded(root):
                    if voice:voice_feed.send(stream_pos,recognized)
                    feed.send(stream_pos,recognized)
                # A skipped position restarts the recognition phrase after our speaker is quiet.
                stream_pos+=len(block)
                segment.write(block);seg_bytes+=len(block);ring.extend(block)
                # Leave room for the block that arrives immediately after a click.
                del ring[:max(0,len(ring)-(cfg["keep"]+1)*BYTES_PER_SECOND)]
                collect_keeps(root,stream_id,ring,now,cfg["keep"])
                if passage is not None:
                    passage.extend(block)
                    if len(passage)>=30*BYTES_PER_SECOND:
                        flush_passage();passage=bytearray();passage_start=now
                if candidate and candidate.add(block):
                    candidate.finish();candidate=None
                if awake and time.monotonic()>=awake:
                    awake=0;event=sound_cue(root,"timeout");event_at=time.monotonic()
                while not requests.empty():
                    cmd=requests.get()
                    if cmd in ("line","coinage","resume"):
                        recent=ring[-cfg["keep"]*BYTES_PER_SECOND:]
                        path=root/f"{cmd}-{now-round(len(recent)*1000/BYTES_PER_SECOND)}-{secrets.token_hex(8)}.pcm.part"
                        with path.open("xb") as out:
                            out.write(recent);out.flush();os.fsync(out.fileno())
                        path.rename(path.with_suffix(""))
                        continue
                    if cmd in CREATIVE_CUES and candidate is None:
                        candidate=CreativeTail(root,cmd,ring[-cfg["keep"]*BYTES_PER_SECOND:],now)
                        event="Possible creative phrase · keeping five more seconds";event_at=time.monotonic()
                    elif cmd=="start" and passage is None:
                        passage=bytearray(ring[-4*BYTES_PER_SECOND:]);passage_start=now-round(len(passage)*1000/BYTES_PER_SECOND)
                        event=sound_cue(root,"started");event_at=time.monotonic()
                    elif cmd=="start":
                        event=sound_cue(root,"already-started");event_at=time.monotonic()
                    elif cmd=="finish" and passage is None:
                        event=sound_cue(root,"no-passage");event_at=time.monotonic()
                    elif cmd=="finish" and passage is not None:
                        flush_passage();event=sound_cue(root,"finishing");event_at=time.monotonic()
                if (root/"bookmark").exists():
                    (root/"bookmark").unlink(missing_ok=True)
                    recent=ring[-cfg["keep"]*BYTES_PER_SECOND:]
                    mark=root/f"mark-{now-round(len(recent)*1000/BYTES_PER_SECOND)}-{secrets.token_hex(6)}.pcm.part"
                    mark.write_bytes(recent);mark.rename(mark.with_suffix(""))
                if time.monotonic()-report>.45:
                    measured=levels.snapshot()
                    recognition_error=getattr(voice,"line","")
                    if not recognition_error.startswith("Live words unavailable"):recognition_error=""
                    if recognition_error and not reported_recognition_error:
                        sound_cue(root,"decoder");reported_recognition_error=True
                    write(root/"status.json",dict(state="Recording",stream=stream_id,words=counter,voice=(event if time.monotonic()-event_at<8 else ""),cue=("Capturing passage" if passage is not None else cue if time.monotonic()-cue_at<45 else ""),lastCue=(cue if passage is not None or time.monotonic()-cue_at>=45 else last_cue),at=now,system=cfg["source"],device=cfg.get("device","default"),recognitionGain=cfg.get("recognitionGain",0),recognitionError=recognition_error,practiceStream=True,promptSpeaking=voice_feedback.guarded(root),commandWindow=max(0,math.ceil(awake-time.monotonic())),**measured,seconds=round(seg_bytes/BYTES_PER_SECOND),elapsed=int(time.monotonic()-began),available=min(cfg["keep"],len(ring)//BYTES_PER_SECOND)))
                    report=time.monotonic()
                    if config(root)!=cfg:return
                if seg_bytes>=cfg["seconds"]*BYTES_PER_SECOND:seal()
    finally:
        feed.close();voice_feed.close();practice.close()
        if voice:voice.close()
        proc.terminate()
        try:proc.wait(timeout=3)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()
        proc.stdout.close();seal();flush_passage()
        if candidate:candidate.finish()
def worker(arc):
    os.umask(0o077);root=folder(arc)
    with (root/"worker.lock").open("a") as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:return
        for partial in root.glob("*.pcm.part"):partial.rename(partial.with_suffix(""))
        done=threading.Event()
        writer=threading.Thread(target=ingest,args=(arc,root,done));writer.start()
        sender=threading.Thread(target=push,args=(arc,root,done),daemon=True);sender.start()
        threading.Thread(target=arc.desk.maintenance,args=(arc,done),daemon=True).start()
        try:
            while True:
                if not (root/"enabled").exists():
                    limit_notice(root,"capture","")
                    if read(root/"status.json",{}).get("state")!="Stopped":write(root/"status.json",dict(state="Stopped",level=0,at=storage.now()))
                    time.sleep(1);continue
                cfg=config(root);source=cfg["source"]
                write(root/"status.json",dict(state="Opening microphone",at=storage.now(),level=0))
                try:
                    if not room(arc,root):raise OSError("Storage low; waiting for space")
                    limit_notice(root,"capture","")
                    capture(arc,root,cfg,mic_input.arguments(source,cfg["device"]))
                except (OSError,subprocess.SubprocessError) as e:
                    sound_cue(root,"storage" if "Storage low" in str(e) else "mic")
                    if "Storage low" in str(e):limit_notice(root,"capture","Recording paused at the storage limit. Review free space in the archive.")
                    write(root/"status.json",dict(state=str(e)[:90],level=0,system=source,device=cfg["device"],at=storage.now()));time.sleep(2)
        finally:
            done.set();writer.join();write(root/"status.json",dict(state="Stopped",level=0,at=storage.now()))
def running(root):
    with (root/"worker.lock").open("a") as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);return False
        except BlockingIOError:return True
def start(arc):
    root=folder(arc)
    if not (root/"enabled").exists():(root/"bookmark").unlink(missing_ok=True)
    (root/"enabled").touch(mode=0o600)
    subprocess.Popen([sys.executable,"-I",str(Path(arc.desk.__file__).resolve()),"laptop-worker"],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
def stop(arc):
    root=folder(arc);(root/"enabled").unlink(missing_ok=True);(root/"bookmark").unlink(missing_ok=True)

def panel(arc):
    import tkinter as tk
    from tkinter import ttk,messagebox
    import recall_web
    root=folder(arc);window=tk.Tk();window.title("Total Recall · Laptop");window.resizable(False,False)
    # A second click in the desktop launcher should raise the existing panel.
    lock=(root/"panel.lock").open("a")
    try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except BlockingIOError:(root/"raise-panel").touch();window.destroy();lock.close();return
    prefs=read(root/"panel.json",{})
    top=tk.BooleanVar(value=prefs.get("top",True));compact=tk.BooleanVar(value=prefs.get("compact",True))
    ultra=tk.BooleanVar(value=prefs.get("ultra",False))
    window.attributes("-topmost",top.get())
    location=read(root/"position.json",{})
    if all(type(location.get(k))==int for k in ("x","y")):
        x=max(0,min(location["x"],window.winfo_screenwidth()-300));y=max(0,min(location["y"],window.winfo_screenheight()-150))
        window.geometry(f"+{x}+{y}")
    window.configure(background="#153d34")
    box=ttk.Frame(window,padding=(4,2));box.pack(fill="both",expand=True)
    header=ttk.Frame(box);header.pack(fill="x");title=ttk.Label(header,text="Laptop microphone",font=("Sans",12,"bold"));title.pack(side="left")
    state=tk.StringVar(value="Stopped");state_label=ttk.Label(box,textvariable=state,wraplength=300);state_label.pack(anchor="w")
    meter=ttk.Progressbar(box,maximum=100,length=240);meter.pack(fill="x",pady=4)
    info=tk.StringVar();summary=ttk.Label(box,textvariable=info,wraplength=300);summary.pack(anchor="w")
    controls=ttk.Frame(box);controls.pack(fill="x",pady=3)
    words_view=tk.StringVar()
    captions=ttk.Label(box,textvariable=words_view,wraplength=240)
    reader=None
    show_words=tk.BooleanVar(value=prefs.get("words",False))
    def toggle():
        if (root/"enabled").exists():stop(arc)
        else:start(arc)
        tick(False)
    toggle_btn=ttk.Button(controls,text="Start recording",command=toggle);toggle_btn.pack(side="left")
    pending = None
    def mark(kind="mark"):
        nonlocal pending
        try:
            status=read(root/"status.json",{})
            if "stream" not in status and kind=="mark" and (root/"enabled").exists():
                # Keep working while an earlier installed recorder is running.
                (root/"bookmark").touch(mode=0o600)
                pending=("legacy",storage.now())
            else:
                pending=(request_keep(root,kind),storage.now())
        except (ValueError,OSError) as exc:
            state.set(str(exc))
            return
        tick(False)
    keep=ttk.Button(controls,text="Keep moment",command=mark);keep.pack(side="left",padx=4)
    def open_app():
        subprocess.Popen([sys.executable,"-I",str(Path(arc.desk.__file__).resolve()),"desk"],start_new_session=True)

    server=None
    def archive(view="archive"):
        nonlocal server
        if server is None:
            server=recall_web.web_server(arc,Path(__file__).parent/"web",0)
            threading.Thread(target=server.serve_forever,daemon=True).start()
        webbrowser.open(recall_web.grant(arc.root,server.server_port)+"#"+view)
    more=ttk.Frame(box)
    typed_buttons=[]
    for kind,label in (("line","Poem line"),("coinage","Coined word"),("resume","Pick up here")):
        button=ttk.Button(more,text=label,width=11,command=lambda kind=kind:mark(kind))
        button.pack(side="left",padx=(0,2));typed_buttons.append(button)
    last_kept=tk.StringVar()
    def reopen_keep():
        receipt=read(root/"last-keep.json",{})
        if receipt.get("id"):archive("clip/"+receipt["id"])
    reopen=ttk.Button(box,textvariable=last_kept,command=reopen_keep)
    # Recover an earlier install's latest intentional capture, including voice passages.
    if not (root/"last-keep.json").exists():
        with arc.connection() as db:
            clip=db.execute("""SELECT id,title,started,duration FROM clips
                WHERE id GLOB 'desk-keep-*' AND reason!='Possible creative phrase heard by the offline recognizer' ORDER BY started+duration DESC LIMIT 1""").fetchone()
        if clip:write(root/"last-keep.json",dict(id=clip[0],title=clip[1],at=clip[2],duration=clip[3]))

    ttk.Button(controls,text="Full journal",command=lambda:archive("journal")).pack(side="left")

    login=Path(os.environ.get("XDG_CONFIG_HOME",Path.home()/".config"))/"autostart/total-recall-microphone.desktop"
    def settings():
        dlg=tk.Toplevel(window);dlg.title("Laptop capture settings");dlg.transient(window)
        tabs=ttk.Notebook(dlg);tabs.pack(fill="both",expand=True,padx=8,pady=8)
        capture_settings,voice_settings,feedback_settings,panel_settings,backup_settings=[ttk.Frame(tabs,padding=10) for _ in range(5)]
        for tab,name in ((capture_settings,"Capture"),(voice_settings,"Voice"),(feedback_settings,"Feedback"),(panel_settings,"Panel"),(backup_settings,"Backup")):
            tabs.add(tab,text=name)
        p=capture_settings
        c=config(root);vars={}
        backend=tk.StringVar(value=c["source"])
        ttk.Label(p,text="Microphone system").pack(anchor="w")
        system_choice=ttk.Combobox(p,textvariable=backend,values=("pulse","alsa"),state="readonly")
        system_choice.pack(fill="x")
        default_label="Follow Mint’s default input"
        device=tk.StringVar(value=default_label if c["device"]=="default" else c["device"])
        options={default_label:"default"}
        ttk.Label(p,text="Microphone").pack(anchor="w")
        source_choice=ttk.Combobox(p,textvariable=device,values=(default_label,),width=40)
        source_choice.pack(fill="x")
        rows=ttk.Frame(p);rows.pack(fill="x",pady=2)
        mic_note=tk.StringVar()
        ttk.Label(p,textvariable=mic_note,wraplength=370).pack(anchor="w")
        pending_jobs=queue.SimpleQueue()
        generation=0
        def background_job(job,then):
            def run():
                try:result=job()
                except (OSError,ValueError,subprocess.SubprocessError) as exc:result=exc
                pending_jobs.put((then,result))
            threading.Thread(target=run,daemon=True).start()
        timer_id=None
        def ui_results():
            nonlocal timer_id
            while not pending_jobs.empty():
                then,result=pending_jobs.get()
                then(result)
            timer_id=dlg.after(150,ui_results)
        def selected_device():
            return options.get(device.get(),device.get().strip())
        def refresh_devices(reset=False):
            nonlocal generation
            generation+=1;seq=generation
            if reset:
                device.set(default_label)
                options.clear();options[default_label]="default"
                source_choice.configure(values=(default_label,))
            system=backend.get()
            def show_devices(found):
                if seq!=generation:return
                if isinstance(found,Exception):
                    mic_note.set("Device list unavailable. Use the system default or enter a known source ID.")
                    return
                current=selected_device()
                options.clear();options[default_label]="default"
                for identifier,label in found.items():
                    if identifier!="default":
                        display=label if label not in options else f"{label} · {identifier}"
                        options[display]=identifier
                source_choice.configure(values=tuple(options))
                device.set(next((key for key,value in options.items() if value==current),current))
                if not check_active[0]:
                    mic_note.set("A pinned microphone waits for that input if disconnected.")
            background_job(lambda:mic_input.device_list(system),show_devices)
        check_active=[False]
        def check():
            system=backend.get();identifier=selected_device()
            try:args=mic_input.arguments(system,identifier)
            except ValueError as exc:mic_note.set(str(exc));return
            if (root/"enabled").exists():
                status=read(root/"status.json",{})
                if (status.get("state")=="Recording" and 0<=storage.now()-status.get("at",0)<6500
                    and status.get("system")==system and status.get("device")==identifier and "rmsDb" in status):
                    mic_note.set("Live · "+mic_input.report(status)+" · "+(status["inputHint"] or "Receiving audio"))
                else:mic_note.set("Save the selected input and wait for live levels, or stop recording to test separately.")
                return
            check_active[0]=True
            test.configure(state="disabled")
            mic_note.set("Speak for three seconds. This microphone check saves no audio.")
            def checked(result):
                check_active[0]=False;test.configure(state="normal")
                if (system,identifier)!=(backend.get(),selected_device()):return
                if isinstance(result,Exception):
                    mic_note.set("Input check failed. Check the microphone and selected input.")
                else:mic_note.set(mic_input.report(result)+" · "+(result["inputHint"] or "Received test audio"))
            background_job(lambda:mic_input.microphone_check(args),checked)
        ttk.Button(rows,text="Refresh inputs",command=refresh_devices).pack(side="left")
        test=ttk.Button(rows,text="Check microphone",command=check);test.pack(side="left",padx=3)
        system_choice.bind("<<ComboboxSelected>>",lambda _event:refresh_devices(True))
        def stop_jobs(event):
            if event.widget is dlg and timer_id is not None:dlg.after_cancel(timer_id)
        dlg.bind("<Destroy>",stop_jobs,add="+")
        refresh_devices();ui_results()
        for name,label,opts in (("quality","AAC quality (kbps)",(24,32,48)),("seconds","Segment length (seconds)",(30,60,120)),("keep","Keep moment lookback (seconds)",(15,30,60))):
            ttk.Label(p,text=label).pack(anchor="w");v=tk.StringVar(value=str(c[name]));vars[name]=v
            ttk.Combobox(p,textvariable=v,values=opts,state="readonly").pack(fill="x")
        p=backup_settings
        network=tk.StringVar(value=c["network"]);ttk.Label(p,text="Hosted backup network").pack(anchor="w")
        ttk.Combobox(p,textvariable=network,values=("Any network","Wi-Fi only","Prefer Wi-Fi for 1 hour"),state="readonly").pack(fill="x")
        p=voice_settings
        journal=tk.BooleanVar(value=c["journal"]);commands=tk.BooleanVar(value=c["commands"])
        ttk.Checkbutton(p,text="Save a text journal for new microphone segments",variable=journal).pack(anchor="w")
        ttk.Checkbutton(p,text="Listen for voice commands while recording",variable=commands).pack(anchor="w")
        ttk.Label(p,text="Say “Hey Recall” or “Lexi,” then “save a line,” “save a word,” “mark my place,” “status,” “last save,” or “what can I say.” You have eight seconds after the wake acknowledgement. Say “start a passage” after waking, then “finish passage” at the end. “Save that” also works directly.",wraplength=370).pack(anchor="w")
        automatic=tk.BooleanVar(value=c["creativeCatches"])
        ttk.Checkbutton(p,text="Keep possible creative phrases automatically",variable=automatic).pack(anchor="w")
        ttk.Label(p,text="Offline speech cues such as “I coined a word” or “let me try that line again” save recent audio and up to five seconds after recognition. Review these in the archive.",wraplength=370).pack(anchor="w")
        gain=tk.StringVar(value=next(label for label,value in mic_input.GAIN_CHOICES.items() if value==c["recognitionGain"]))
        ttk.Label(p,text="Input gain for live words and voice commands").pack(anchor="w")
        ttk.Combobox(p,textvariable=gain,values=tuple(mic_input.GAIN_CHOICES),state="readonly").pack(fill="x")
        ttk.Label(p,text="The microphone meter and saved audio use the original level.",wraplength=370).pack(anchor="w")
        p=feedback_settings
        sounds=tk.StringVar(value=read(root/"sound.json",SOUND_MODES[0]))
        ttk.Label(p,text="Feedback mode").pack(anchor="w")
        ttk.Combobox(p,textvariable=sounds,values=SOUND_MODES,state="readonly").pack(fill="x")
        rate_value,volume_value=voice_feedback.preferences(root)
        rate=tk.StringVar(value=str(rate_value));volume=tk.StringVar(value=str(volume_value))
        for label,v,opts in (("Speech pace (words per minute)",rate,voice_feedback.RATES),("Cue volume (%)",volume,voice_feedback.VOLUMES)):
            ttk.Label(p,text=label).pack(anchor="w")
            ttk.Combobox(p,textvariable=v,values=opts,state="readonly").pack(fill="x")
        ttk.Label(p,text="Spoken guidance adds status, command choices and errors. Only the live recognizer pauses during spoken prompts. Headphones keep prompts out of the original audio.",wraplength=360).pack(anchor="w",pady=3)
        preview_kind=tk.StringVar(value="Saved word")
        choices={"Wake acknowledgement":"wake","Saved word":"coinage","Unclear command":"unclear"}
        ttk.Label(p,text="Try the selected settings").pack(anchor="w")
        ttk.Combobox(p,textvariable=preview_kind,values=tuple(choices),state="readonly").pack(fill="x")
        preview_result=tk.StringVar()
        def preview():
            sample=choices[preview_kind.get()]
            result=sound_cue(root,sample,"Sample. "+voice_feedback.REPLIES[sample],preview=(sounds.get(),int(rate.get()),int(volume.get())))
            preview_result.set(result+" · "+voice_feedback.REPLIES[sample])
        ttk.Button(p,text="Preview feedback",command=preview).pack(anchor="w",pady=3)
        ttk.Label(p,textvariable=preview_result,wraplength=360).pack(anchor="w")
        p=capture_settings
        prune=tk.BooleanVar(value=c["prune"])
        ttk.Checkbutton(p,text="Skip near-silent blocks in new recordings",variable=prune).pack(anchor="w")
        p=panel_settings
        at_login=tk.BooleanVar(value=login.exists())
        for text,var in (("Keep panel above other windows",top),("Compact panel",compact),("Start recording and show panel when I log in",at_login)):
            ttk.Checkbutton(p,text=text,variable=var).pack(anchor="w")
        ttk.Label(p,text="Bookmark while another app has focus").pack(anchor="w",pady=(8,1))
        shortcut=tk.StringVar(value="line")
        ttk.Combobox(p,textvariable=shortcut,values=("moment","line","coinage","resume"),state="readonly").pack(fill="x")
        copy_label=tk.StringVar(value="Copy shortcut command")
        def copy_shortcut():
            dlg.clipboard_clear();dlg.clipboard_append("total-recall-desk keep "+shortcut.get())
            copy_label.set("Command copied")
        ttk.Button(p,textvariable=copy_label,command=copy_shortcut).pack(anchor="w",pady=3)
        ttk.Label(p,text="Paste it as a custom command in Mint’s Keyboard settings and assign your preferred keys. It works while laptop recording is active.",wraplength=370).pack(anchor="w")
        p=backup_settings
        paired=bool(read(root/"pair.json",""));share=tk.BooleanVar(value=paired)
        ttk.Checkbutton(p,text="Send laptop audio, saved journals and bookmarks to my hosted archive",variable=share).pack(anchor="w",pady=4)
        entry=ttk.Entry(p,width=39,show="•");entry.pack(fill="x")
        ttk.Label(p,text="For hosted backup, paste the website’s Pair phone code here once. Eligible laptop recordings already on disk will also upload.",wraplength=360).pack(anchor="w")
        def save():
            try:
                new={key:int(v.get()) for key,v in vars.items()}
                new.update(source=backend.get(),device=selected_device(),recognitionGain=mic_input.GAIN_CHOICES[gain.get()],creativeCatches=automatic.get())
                mic_input.arguments(new["source"],new["device"])
                new["journal"]=journal.get();new["commands"]=commands.get();new["prune"]=prune.get();new["network"]=network.get()
                if share.get():
                    if entry.get().strip():write(root/"pair.json",credentials(entry.get().strip()))
                    elif not paired:raise ValueError("Enter the hosted pairing code.")
                else:(root/"pair.json").unlink(missing_ok=True)
                write(root/"sound.json",sounds.get())
                write(root/"speech-options.json",dict(rate=int(rate.get()),volume=int(volume.get())))
                write(root/"settings.json",new);write(root/"panel.json",dict(top=top.get(),compact=compact.get(),ultra=ultra.get() and compact.get(),words=show_words.get()))
                if at_login.get():
                    login.parent.mkdir(parents=True,exist_ok=True)
                    login.write_text("[Desktop Entry]\nType=Application\nName=Total Recall laptop microphone\nExec=total-recall-desk laptop-login\nX-GNOME-Autostart-enabled=true\n")
                else:login.unlink(missing_ok=True)
                if share.get() and not running(root):
                    subprocess.Popen([sys.executable,"-I",str(Path(arc.desk.__file__).resolve()),"laptop-worker"],start_new_session=True,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                window.attributes("-topmost",top.get())
                layout()
                # The capture loop notices a configuration change and seals its current segment.
                dlg.destroy()
            except (ValueError,OSError) as ex:messagebox.showerror("Capture settings",str(ex),parent=dlg)
        ttk.Button(dlg,text="Save settings",command=save).pack(anchor="e",padx=10,pady=(0,8))
    menu=tk.Menu(window,tearoff=False)
    check_window=None
    def show_voice_check():
        nonlocal check_window
        if check_window is not None and check_window.winfo_exists():check_window.lift();return
        check_window=voice_check.panel(window,root,config,read)
    ttk.Button(header,text="Voice check",command=show_voice_check).pack(side="left",padx=3)
    menu.add_command(label="Voice check",command=show_voice_check)
    menu.add_command(label="Open recorder",command=open_app)
    menu.add_command(label="Browse & play",command=archive)
    menu.add_command(label="Open last kept audio",command=reopen_keep)
    menu.add_command(label="Review saved moments",command=lambda:archive("review"))
    menu.add_checkbutton(label="Show live words",variable=show_words,command=lambda:(layout(),tick(False)))
    def recent_feedback():
        dlg=tk.Toplevel(window);dlg.title("Recent recorder feedback");dlg.transient(window)
        pane=ttk.Frame(dlg,padding=10);pane.pack(fill="both")
        entries=voice_feedback.history(root)
        for entry in entries:
            stamp=time.strftime("%H:%M:%S",time.localtime(entry["at"]/1000))
            ttk.Label(pane,text=stamp+"  "+entry["text"],wraplength=400).pack(anchor="w",pady=2)
        if not entries:ttk.Label(pane,text="No recorder feedback yet").pack(anchor="w")
        ttk.Button(pane,text="Close",command=dlg.destroy).pack(anchor="e",pady=(4,0))
    menu.add_command(label="Recent recorder feedback",command=recent_feedback)
    menu.add_command(label="Settings",command=settings)
    def smallest():
        ultra.set(True);compact.set(True);layout();tick(False)
    def end_session():
        if messagebox.askyesno("End recording and close?", "Stop the laptop microphone and close this panel? Saved recordings and journal entries will remain.",parent=window):
            stop(arc);close()
    menu.add_command(label="Ultra mini panel",command=smallest)
    menu.add_command(label="End recording & close",command=end_session)
    menu.add_command(label="Hide panel",command=window.iconify)
    def show_menu():
        try:menu.tk_popup(menu_btn.winfo_rootx(),menu_btn.winfo_rooty()+menu_btn.winfo_height())
        finally:menu.grab_release()
    menu_btn=ttk.Button(header,text="More",width=5,command=show_menu)
    mode=ttk.Button(header,text="↙",command=lambda:resize(),width=3)
    mini=ttk.Frame(box);mini_state=tk.StringVar(value="Journal")
    mini_read=ttk.Button(mini,textvariable=mini_state,command=lambda:archive("journal"),width=13,takefocus=True)
    mini_read.pack(side="left")
    mini_toggle=ttk.Button(mini,text="Start",width=5,command=toggle);mini_toggle.pack(side="left")
    mini_keep=ttk.Button(mini,text="Save",width=5,command=mark);mini_keep.pack(side="left")
    ttk.Button(mini,text="↗",width=3,command=lambda:resize()).pack(side="left")
    compact_facts=tk.StringVar()
    mini_detail=ttk.Label(box,textvariable=compact_facts,font=("Sans",9),wraplength=285)
    def layout():
        for w in (header,state_label,meter,summary,captions,controls,more,mini,mini_detail,reopen):w.pack_forget()
        if compact.get():
            for child in mini.winfo_children():child.pack_forget()
            for child in mini.winfo_children():
                if not ultra.get() or child not in (mini_toggle,mini_keep):child.pack(side="left")
            mini.pack(fill="x")
            if not ultra.get():mini_detail.pack(anchor="w",padx=3)
        else:
            header.pack(fill="x");mode.pack(side="right");menu_btn.pack(side="right")
            state_label.pack(anchor="w");meter.pack(fill="x",pady=1)
            if show_words.get():captions.pack(fill="x")
            controls.pack(fill="x",pady=1);more.pack(fill="x",pady=1)
            reopen.pack(fill="x",pady=1);summary.pack(anchor="w")
        write(root/"panel.json",dict(top=top.get(),compact=compact.get(),ultra=ultra.get() and compact.get(),words=show_words.get()))
    def resize():
        if ultra.get():ultra.set(False);compact.set(True)
        else:compact.set(not compact.get())
        layout();tick(False)
    timer=None
    def tick(repeat=True):
        nonlocal timer,reader,pending
        if (root/"raise-panel").exists():
            (root/"raise-panel").unlink(missing_ok=True);window.deiconify();window.lift()
        s=read(root/"status.json",{});last=read(root/"last.json",{})
        active=(root/"enabled").exists();fresh=0<=storage.now()-s.get("at",0)<6500
        status=s.get("state","Starting microphone") if active and fresh else "Waiting for recorder" if active else "Stopped"
        ready=status=="Recording"
        if ready:
            elapsed=s.get("elapsed",0)
            state.set(f"Recording  ·  {elapsed//60}:{elapsed%60:02}  ·  mic {s.get('level',0)}%")
        else:state.set(status)
        if ready and show_words.get() and "available" not in s:
            words_view.set("Sign out and back in to load the updated recorder for live words.")
        elif ready and show_words.get():
            if reader is None:
                try:reader=live_words.LiveWords(root)
                except OSError as e:words_view.set("Live words unavailable · "+str(e))
            if reader:words_view.set(reader.line)
        else:
            if reader:reader.close();reader=None
            words_view.set("Words paused" if not ready else "")
        cfg=config(root)
        available=s.get("available",cfg["keep"])
        duration=f"{available} s" if available else "<1 s"
        keep.configure(text="Keep "+duration if ready else "Keep moment")
        meter["value"]=s.get("level",0) if active and fresh else 0
        keep.configure(state="normal" if active and fresh and status=="Recording" else "disabled")
        toggle_btn.configure(text="Stop recording" if active else "Start recording")
        for btn in typed_buttons:
            btn.configure(state="normal" if ready and s.get("stream") else "disabled")
        receipt=read(root/"last-keep.json",{})
        if receipt:
            clock=time.strftime("%H:%M:%S",time.localtime((receipt["at"]+receipt["duration"])/1000))
            last_label=f"{receipt['title']} · {receipt['duration']/1000:.1f} s · {clock}"
            last_kept.set("Open last keep  ·  "+last_label)
        else:last_kept.set("No kept moment yet")
        reopen.configure(state="normal" if receipt else "disabled")
        menu.entryconfigure("Open last kept audio",state="normal" if receipt else "disabled")
        missed=read(root/"missed-keep.json",{})
        feedback=""
        if pending:
            token,when=pending
            if (receipt.get("id","").endswith("-"+token)
                    or (token=="legacy" and receipt.get("savedAt",0)>=when)):
                pending=None
            elif missed.get("at",0)>=when:
                pending=None
            elif not active:
                feedback="Check the archive for the pending keep"
                if storage.now()-when>12000:pending=None
            elif storage.now()-when>12000:
                pending=None
                feedback="Keep still unconfirmed · check archive"
            else:feedback="Saving recent audio…"
        if not feedback and missed and storage.now()-missed.get("at",0)<8000:feedback=missed["text"]
        if not feedback and receipt and storage.now()-receipt.get("savedAt",0)<8000:
            feedback="Saved · "+receipt["title"]
        saved=("Last saved "+time.strftime("%H:%M:%S",time.localtime(last["at"]/1000))+" · "+str(round(last["duration"]/1000))+" s") if last else "No laptop audio saved yet"
        if last.get("kind")=="clip":saved+=" · bookmark"
        sync=read(root/"sync.json","Waiting for transfer") if (root/"pair.json").exists() else "Saved on this laptop"
        segment=(f"Segment {s.get('seconds',0)} / {cfg['seconds']} s  ·  " if ready else "")
        omit=read(root/"last-omission.json",{})
        if omit.get("at",0)>last.get("at",0):saved="Quiet block skipped · "+str(round(omit["duration"]/1000))+" s"
        facts=(str(s.get("words",0))+" words · Now · "+(s.get("cue") or "Waiting for context")+"\nLast · "+(s.get("lastCue") or "No earlier cue")) if ready and (cfg["commands"] or cfg["creativeCatches"]) else ""
        source=""
        if s.get("system") and (ready or active):
            name="Mint default" if s.get("device")=="default" else s.get("device","")
            source=f"{s['system'].upper()} · {name}"
            if ready and "rmsDb" in s:source+="\n"+mic_input.report(s)
        info.set("\n".join(filter(None,(source,s.get("inputHint","") if ready else "",s.get("recognitionError","") if ready else "",s.get("voice","") if ready else "",facts,segment+saved,read(root/"save-error.json",sync)))))
        alerts=[read(root/(kind+"-limit.json"),{}).get("text","") for kind in ("capture","upload")]
        if any(alerts):info.set("\n".join(filter(None,alerts)))
        candidate=read(root/"last-candidate.json",{})
        if candidate and storage.now()-candidate.get("savedAt",0)<8000 and not feedback:
            feedback="Candidate saved · "+candidate["title"]
        if ready and s.get("promptSpeaking"):
            info.set("Speaking · live recognition paused; original recording continues\n"+info.get())
        elif ready and s.get("commandWindow"):
            info.set(f"Command window · {s['commandWindow']} s left\n"+info.get())
        if feedback:info.set(feedback+"\n"+info.get())
        if ready and "stream" not in s:
            info.set(info.get()+"\nSign out and back in once to enable Poem line, Coined word and Pick up here on the updated recorder.")
        mini_toggle.configure(text="Pause" if active else "Start")
        mini_keep.configure(state="normal" if ready else "disabled")
        elapsed=s.get("elapsed",0)
        mini_state.set(f"REC {elapsed//60}:{elapsed%60:02}" if ready else "Opening mic" if active else "Mic off")
        detail=next((alert for alert in alerts if alert), "")
        if not detail:detail=read(root/"save-error.json","") or feedback or (s.get("voice","") if ready else "")
        if not detail and active and not ready:detail=status
        if not detail and ready:
            detail=(f"Command · {s['commandWindow']} s left" if s.get("commandWindow") else
                    "Speaking · recording continues" if s.get("promptSpeaking") else s.get("inputHint","") or s.get("recognitionError",""))
        if not detail and ready and show_words.get() and reader:detail=reader.line
        if not detail:detail=(s.get("cue") if ready else "") or ("Last · "+receipt["title"] if receipt else "No kept moments")
        compact_facts.set((f"Mic {s.get('level',0)}% · Keep {duration} · " if ready else "")+detail)
        if active and not ready and not status.startswith(("Starting","Opening","Waiting for recorder")):
            mini_state.set("Mic issue")
        if ultra.get() and feedback:mini_state.set(feedback[:13])
        if repeat:timer=window.after(550,tick)
    layout()
    # First bindtag sees pointer events before Tk button bindings. Once dragging
    # starts, suppress release so moving a Stop or Keep button never invokes it.
    gesture={}
    tag="RecallOverlayDrag"
    def begin(e):
        gesture.update(x=e.x_root,y=e.y_root,left=window.winfo_x(),top=window.winfo_y(),moving=False)
    def move(e):
        if not gesture:return
        dx=e.x_root-gesture["x"];dy=e.y_root-gesture["y"]
        if not gesture["moving"] and abs(dx)+abs(dy)<7:return
        gesture["moving"]=True
        x=max(0,min(gesture["left"]+dx,window.winfo_screenwidth()-window.winfo_width()))
        y=max(0,min(gesture["top"]+dy,window.winfo_screenheight()-window.winfo_height()))
        window.geometry(f"+{x}+{y}")
        return "break"
    def release(e):
        move(e)
        dragged=gesture.get("moving",False);gesture.clear()
        if dragged:
            if isinstance(e.widget,ttk.Button):e.widget.state(["!pressed"])
            write(root/"position.json",dict(x=window.winfo_x(),y=window.winfo_y()))
            return "break"
    for event,fn in (("<ButtonPress-1>",begin),("<B1-Motion>",move),("<ButtonRelease-1>",release)):
        window.bind_class(tag,event,fn)
    def tag_tree(w):
        w.bindtags((tag,)+w.bindtags())
        for child in w.winfo_children():tag_tree(child)
    tag_tree(window)
    def close():
        if reader:reader.close()
        if timer is not None:window.after_cancel(timer)
        write(root/"position.json",dict(x=window.winfo_x(),y=window.winfo_y()))
        if server:server.shutdown();server.server_close()
        window.destroy()
    window.protocol("WM_DELETE_WINDOW",close);tick();window.mainloop();lock.close()
