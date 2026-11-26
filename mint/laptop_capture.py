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
import recall_archive as storage

HOST="total-recall-archive-production.up.railway.app"
RATE=16000
BYTES_PER_SECOND=2*RATE
BASE={"source":"pulse","quality":32,"seconds":60,"keep":30,"journal":False,"commands":True,"prune":False,"network":"Any network"}
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
    return {k:raw[k] if raw.get(k) in choices else BASE[k] for k,choices in {
        "source":("pulse","alsa"),"quality":(24,32,48),"seconds":(30,60,120),"keep":(15,30,60),"prune":(True,False),"journal":(True,False),"commands":(True,False),"network":("Any network","Wi-Fi only","Prefer Wi-Fi for 1 hour")}.items()}
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
    passage=path.name.startswith("passage-");mark=passage or path.name.startswith("mark-")
    uid=("desk-keep-" if mark else "laptop-")+path.stem.split("-",1)[1]
    with tempfile.TemporaryDirectory(dir=path.parent) as tmp:
        if mark:
            wav=Path(tmp)/"clip.wav"
            with wave.open(str(wav),"wb") as out,path.open("rb") as src:
                out.setparams((1,2,RATE,0,"NONE",""));out.writeframes(src.read(size))
            archive=Path(tmp)/"clip.zip"
            meta=dict(id=uid,title="Spoken passage" if passage else "Laptop bookmark",startedAtMillis=start,durationMs=ms,sha256=storage.sha_file(wav),
                audio="audio/"+uid+".wav",category="Poem line" if passage else "Manual keep",reason="Passage requested by voice" if passage else "Requested on laptop",transcript="")
            with zipfile.ZipFile(archive,"w") as z:
                z.write(wav,meta["audio"]);z.writestr("index.json",json.dumps({"schema":arc.desk.FORMAT,"clips":[meta]}))
            file=archive;kind="clip";mime="application/zip"
        else:
            file=Path(tmp)/"segment.aac"
            subprocess.run(["ffmpeg","-nostdin","-loglevel","error","-f","s16le","-ar",str(RATE),"-ac","1","-i",str(path),
                "-c:a","aac","-b:a",str(cfg["quality"])+"k","-f","adts",str(file)],check=True,timeout=60)
            kind="segment";mime="audio/aac"
        stage_record(arc,uid,file,dict(id=uid,kind=kind,title="Laptop bookmark" if mark else "Laptop microphone",
            sessionId="Mint laptop",startedAtMillis=start,durationMs=ms,bytes=file.stat().st_size,sha256=storage.sha_file(file),mediaType=mime))
        if path.name.split("-")[0] in ("journal","quietjournal"):
            lines=[]
            def phrase(seconds,text):
                stamp=time.strftime("%Y-%m-%d %H:%M:%S Z",time.gmtime(start/1000+seconds))
                lines.append(stamp+"  "+text)
            with path.open("rb") as pcm:situation.scan(pcm,situation.new_decoder(),0,True,phrase)
            if lines:
                text=Path(tmp)/"journal.txt";text.write_text("Total Recall · Mint microphone\n"+"\n".join(lines)+"\n",encoding="utf-8")
                journal_id="laptop-journal-"+path.stem.split("-",1)[1]
                stage_record(arc,journal_id,text,dict(id=journal_id,kind="journal",sessionId="Mint laptop",title="Laptop speech journal",startedAtMillis=start,durationMs=ms,bytes=text.stat().st_size,sha256=storage.sha_file(text),mediaType="text/plain"))
    path.unlink();write(path.parent/"last.json",{"at":start,"duration":ms,"kind":kind})
def room(arc,root):
    reserved=sum(x.stat().st_size for x in root.glob("*.pcm*") if x.is_file())
    capacity=arc.status()
    return reserved<64*1024**2 and capacity["availableBytes"]>8*1024**2 and capacity["freeBytes"]>capacity["floorBytes"]+16*1024**2
def ingest(arc,root,done):
    while True:
        files=sorted(root.glob("*.pcm"))
        if not files:
            if done.is_set():return
            done.wait(.4);continue
        try:
            finish(arc,files[0],config(root))
            (root/"save-error.json").unlink(missing_ok=True)
        except Exception as e:
            write(root/"save-error.json","Waiting to save · "+str(e)[:100])
            if done.wait(4):return
def capture(arc,root,cfg,input_args):
    """Capture one uninterrupted stream; rollover never reopens the microphone."""
    args=["ffmpeg","-nostdin","-hide_banner","-loglevel","error",*input_args,"-vn","-ar",str(RATE),"-ac","1","-f","s16le","pipe:1"]
    proc=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    ring=bytearray();tail=b"";segment=None;seg_bytes=0;start=0;report=0;last_input=time.monotonic()
    feed=live_words.WordFeed(root)
    voice_feed=live_words.WordFeed(root,"voice-control.fifo")
    requests=queue.SimpleQueue();passage=None;passage_start=0
    counter=0;awake=0;event="";event_at=0;cue="";cue_at=0
    def flush_passage():
        nonlocal passage,passage_start
        if passage:
            path=root/f"passage-{passage_start}-{secrets.token_hex(6)}.pcm.part"
            path.write_bytes(passage);path.rename(path.with_suffix(""))
        passage=None
    def heard(words):
        nonlocal counter,awake,event,event_at,cue,cue_at
        if not (root/"enabled").exists():return
        counter+=len(words.split());cmd=words.lower().strip()
        matched=re.match(r"^(?:hey recall|total recall|hey lexi|lexi)(?: |$)",cmd)
        if matched:
            awake=time.monotonic()+8;cmd=cmd[matched.end():]
            event="Wake heard · ready for a command";event_at=time.monotonic()
            try:
                subprocess.Popen(["ffplay","-v","quiet","-nodisp","-autoexit","-f","lavfi","-i","aevalsrc=0.10*sin(2*PI*(660+330*gte(t\\,0.08))*t):d=0.17"],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            except OSError:pass
        if cmd in ("save that","save this","keep that","remember that","keep the last minute","save the last minute","you missed one"):
            (root/"bookmark").touch(mode=0o600);event="Save requested · recent audio";event_at=time.monotonic();awake=0
        elif cmd in ("finish passage","finish the poem","finish the story","finish the draft"):
            requests.put("finish");awake=0
        elif cmd and time.monotonic()<awake:
            if cmd in ("pause recall","pause recording","stop listening"):
                (root/"enabled").unlink(missing_ok=True);event="Pause heard"
            elif cmd in ("start a passage","start a poem","start a story","start a draft"):
                requests.put("start");event="Passage requested"
            else:event="Command unclear · try save that"
            awake=0;event_at=time.monotonic()
        if cmd:
            found=situation.interpret([(words,"",None)])
            if found and found[0]["label"]!="Source excerpt":cue=found[0]["label"];cue_at=time.monotonic()
    voice=live_words.LiveWords(root,"voice-control.fifo",heard) if cfg.get("commands",True) else None
    stream_pos=secrets.randbits(61)
    began=time.monotonic()
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
                if segment and abs(now-(start+seg_bytes*1000/BYTES_PER_SECOND))>4000:seal();flush_passage();ring.clear()
                if time.monotonic()-last_input>3:seal();flush_passage();ring.clear()
                last_input=time.monotonic()
                if segment is None:
                    if not room(arc,root):raise OSError("Storage low; waiting for space")
                    start=now-round(len(block)*1000/BYTES_PER_SECOND)
                    prefix="quietcheck" if cfg.get("prune") else "segment"
                    if cfg.get("journal"):prefix="quietjournal" if cfg.get("prune") else "journal"
                    segment=(root/f"{prefix}-{start}-{secrets.token_hex(6)}.pcm.part").open("xb")
                if voice:voice_feed.send(stream_pos,block)
                feed.send(stream_pos,block)
                stream_pos+=len(block)
                segment.write(block);seg_bytes+=len(block);ring.extend(block)
                del ring[:max(0,len(ring)-cfg["keep"]*BYTES_PER_SECOND)]
                if passage is not None:
                    passage.extend(block)
                    if len(passage)>=30*BYTES_PER_SECOND:
                        flush_passage();passage=bytearray();passage_start=now
                while not requests.empty():
                    cmd=requests.get()
                    if cmd=="start" and passage is None:
                        passage=bytearray(ring[-4*BYTES_PER_SECOND:]);passage_start=now-round(len(passage)*1000/BYTES_PER_SECOND)
                    elif cmd=="finish" and passage is not None:
                        flush_passage();event="Passage sent for saving";event_at=time.monotonic()
                if (root/"bookmark").exists():
                    (root/"bookmark").unlink(missing_ok=True)
                    mark=root/f"mark-{now-round(len(ring)*1000/BYTES_PER_SECOND)}-{secrets.token_hex(6)}.pcm.part"
                    mark.write_bytes(ring);mark.rename(mark.with_suffix(""))
                if time.monotonic()-report>.45:
                    samples=memoryview(block).cast("h")
                    rms=math.sqrt(sum(int(x)*int(x) for x in samples)/max(1,len(samples)))
                    write(root/"status.json",dict(state="Recording",words=counter,voice=(event if time.monotonic()-event_at<8 else ""),cue=("Capturing passage" if passage is not None else cue if time.monotonic()-cue_at<90 else ""),at=now,level=min(100,round(rms*100/5500)),seconds=round(seg_bytes/BYTES_PER_SECOND),elapsed=int(time.monotonic()-began),available=len(ring)//BYTES_PER_SECOND))
                    report=time.monotonic()
                    if config(root)!=cfg:return
                if seg_bytes>=cfg["seconds"]*BYTES_PER_SECOND:seal()
    finally:
        feed.close();voice_feed.close()
        if voice:voice.close()
        proc.terminate()
        try:proc.wait(timeout=3)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()
        proc.stdout.close();seal();flush_passage()
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
                    capture(arc,root,cfg,["-f",source,"-i","default"])
                except (OSError,subprocess.SubprocessError) as e:
                    if "Storage low" in str(e):limit_notice(root,"capture","Recording paused at the storage limit. Review free space in the archive.")
                    write(root/"status.json",dict(state=str(e)[:90],level=0,at=storage.now()));time.sleep(2)
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
    top=tk.BooleanVar(value=prefs.get("top",True));compact=tk.BooleanVar(value=prefs.get("compact",False))
    window.attributes("-topmost",top.get())
    location=read(root/"position.json",{})
    if all(type(location.get(k))==int for k in ("x","y")):
        x=max(0,min(location["x"],window.winfo_screenwidth()-300));y=max(0,min(location["y"],window.winfo_screenheight()-150))
        window.geometry(f"+{x}+{y}")
    window.configure(background="#153d34")
    box=ttk.Frame(window,padding=10);box.pack(fill="both",expand=True)
    header=ttk.Frame(box);header.pack(fill="x");title=ttk.Label(header,text="Laptop microphone",font=("Sans",12,"bold"));title.pack(side="left")
    state=tk.StringVar(value="Stopped");ttk.Label(box,textvariable=state,wraplength=335).pack(anchor="w")
    meter=ttk.Progressbar(box,maximum=100,length=310);meter.pack(fill="x",pady=4)
    info=tk.StringVar();summary=ttk.Label(box,textvariable=info,wraplength=340);summary.pack(anchor="w")
    controls=ttk.Frame(box);controls.pack(fill="x",pady=3)
    words_view=tk.StringVar()
    captions=ttk.Label(box,textvariable=words_view,wraplength=310)
    reader=None
    show_words=tk.BooleanVar(value=prefs.get("words",False))
    def toggle():
        if (root/"enabled").exists():stop(arc)
        else:start(arc)
        tick(False)
    toggle_btn=ttk.Button(controls,text="Start recording",command=toggle);toggle_btn.pack(side="left")
    def mark():
        (root/"bookmark").touch(mode=0o600);state.set("Mark requested · saving recent audio")
    keep=ttk.Button(controls,text="Keep moment",command=mark);keep.pack(side="left",padx=4)
    def open_app():
        subprocess.Popen([sys.executable,"-I",str(Path(arc.desk.__file__).resolve()),"desk"],start_new_session=True)
    ttk.Button(controls,text="Open app",command=open_app).pack(side="left",padx=2)
    server=None
    def archive(view="archive"):
        nonlocal server
        if server is None:
            server=recall_web.web_server(arc,Path(__file__).parent/"web",0)
            threading.Thread(target=server.serve_forever,daemon=True).start()
        webbrowser.open(recall_web.grant(arc.root,server.server_port)+"#"+view)
    more=ttk.Frame(box);more.pack(fill="x")
    ttk.Button(controls,text="Full journal",command=lambda:archive("journal")).pack(side="left")
    ttk.Button(more,text="Browse & play",command=archive).pack(side="left")
    login=Path(os.environ.get("XDG_CONFIG_HOME",Path.home()/".config"))/"autostart/total-recall-microphone.desktop"
    def settings():
        dlg=tk.Toplevel(window);dlg.title("Laptop capture settings");dlg.transient(window);p=ttk.Frame(dlg,padding=12);p.pack()
        c=config(root);vars={}
        for name,label,opts in (("source","Microphone system",("pulse","alsa")),("quality","AAC quality (kbps)",(24,32,48)),("seconds","Segment length (seconds)",(30,60,120)),("keep","Keep moment lookback (seconds)",(15,30,60))):
            ttk.Label(p,text=label).pack(anchor="w");v=tk.StringVar(value=str(c[name]));vars[name]=v
            ttk.Combobox(p,textvariable=v,values=opts,state="readonly").pack(fill="x")
        ttk.Label(p,text="Pulse uses Mint’s selected microphone. Keep moment saves the available audio before your click. Closing the panel leaves capture running. Stop recording releases the microphone.",wraplength=360).pack(anchor="w",pady=4)
        network=tk.StringVar(value=c["network"]);ttk.Label(p,text="Hosted backup network").pack(anchor="w")
        ttk.Combobox(p,textvariable=network,values=("Any network","Wi-Fi only","Prefer Wi-Fi for 1 hour"),state="readonly").pack(fill="x")
        journal=tk.BooleanVar(value=c["journal"]);commands=tk.BooleanVar(value=c["commands"])
        ttk.Checkbutton(p,text="Save a text journal for new microphone segments",variable=journal).pack(anchor="w")
        ttk.Checkbutton(p,text="Listen for voice commands while recording",variable=commands).pack(anchor="w")
        ttk.Label(p,text="Say Hey Recall or Total Recall for the wake chime. Say save that, keep that, remember that, or you missed one to bookmark recent audio. Say Hey Recall, start a passage before a poem or story. Say finish passage at the end, or Hey Recall, pause recording to release the mic.",wraplength=370).pack(anchor="w")
        prune=tk.BooleanVar(value=c["prune"])
        ttk.Checkbutton(p,text="Skip near-silent blocks in new recordings",variable=prune).pack(anchor="w")
        at_login=tk.BooleanVar(value=login.exists())
        for text,var in (("Keep panel above other windows",top),("Compact panel",compact),("Start recording and show panel when I log in",at_login)):
            ttk.Checkbutton(p,text=text,variable=var).pack(anchor="w")
        paired=bool(read(root/"pair.json",""));share=tk.BooleanVar(value=paired)
        ttk.Checkbutton(p,text="Send laptop audio, saved journals and bookmarks to my hosted archive",variable=share).pack(anchor="w",pady=4)
        entry=ttk.Entry(p,width=39,show="•");entry.pack(fill="x")
        ttk.Label(p,text="For hosted backup, paste the website’s Pair phone code here once. Eligible laptop recordings already on disk will also upload.",wraplength=360).pack(anchor="w")
        def save():
            try:
                new={key:(v.get() if key=="source" else int(v.get())) for key,v in vars.items()}
                new["journal"]=journal.get();new["commands"]=commands.get();new["prune"]=prune.get();new["network"]=network.get()
                if share.get():
                    if entry.get().strip():write(root/"pair.json",credentials(entry.get().strip()))
                    elif not paired:raise ValueError("Enter the hosted pairing code.")
                else:(root/"pair.json").unlink(missing_ok=True)
                write(root/"settings.json",new);write(root/"panel.json",dict(top=top.get(),compact=compact.get(),words=show_words.get()))
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
        ttk.Button(p,text="Save settings",command=save).pack(pady=5)
    ttk.Button(more,text="Review",command=lambda:archive("review")).pack(side="left",padx=4)
    ttk.Button(more,text="Settings",command=settings).pack(side="left",padx=4)
    ttk.Button(more,text="Hide panel",command=window.iconify).pack(side="left")
    def layout():
        mode.configure(text="Expand" if compact.get() else "Shrink")
        word_btn.configure(text="Hide words" if show_words.get() else "Live words")
        summary.pack_forget();more.pack_forget();captions.pack_forget()
        if not compact.get():
            summary.pack(before=controls,anchor="w")
            more.pack(fill="x")
        if show_words.get():captions.pack(before=controls,fill="x",pady=3)
        write(root/"panel.json",dict(top=top.get(),compact=compact.get(),words=show_words.get()))
    def resize():
        compact.set(not compact.get());layout()
    def toggle_words():
        show_words.set(not show_words.get());layout();tick(False)
    mode=ttk.Button(header,command=resize,width=7);mode.pack(side="right")
    word_btn=ttk.Button(more,command=toggle_words);word_btn.pack(side="left",padx=2)
    timer=None
    def tick(repeat=True):
        nonlocal timer,reader
        if (root/"raise-panel").exists():
            (root/"raise-panel").unlink(missing_ok=True);window.deiconify();window.lift()
        s=read(root/"status.json",{});last=read(root/"last.json",{})
        active=(root/"enabled").exists();fresh=storage.now()-s.get("at",0)<6500
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
        keep.configure(text="Keep "+str(s.get("available",config(root)["keep"]))+" s" if ready else "Keep moment")
        meter["value"]=s.get("level",0) if active and fresh else 0
        keep.configure(state="normal" if active and fresh and status=="Recording" else "disabled")
        toggle_btn.configure(text="Stop recording" if active else "Start recording")
        saved=("Last saved "+time.strftime("%H:%M:%S",time.localtime(last["at"]/1000))+" · "+str(round(last["duration"]/1000))+" s") if last else "No laptop audio saved yet"
        if last.get("kind")=="clip":saved+=" · bookmark"
        sync=read(root/"sync.json","Waiting for transfer") if (root/"pair.json").exists() else "Saved on this laptop"
        segment=(f"Segment {s.get('seconds',0)} / {config(root)['seconds']} s  ·  " if ready else "")
        omit=read(root/"last-omission.json",{})
        if omit.get("at",0)>last.get("at",0):saved="Quiet block skipped · "+str(round(omit["duration"]/1000))+" s"
        facts=(str(s.get("words",0))+" words · "+(s.get("cue") or "Waiting for context")) if ready and config(root)["commands"] else ""
        info.set("\n".join(filter(None,(s.get("voice","") if ready else "",facts,segment+saved,read(root/"save-error.json",sync)))))
        alerts=[read(root/(kind+"-limit.json"),{}).get("text","") for kind in ("capture","upload")]
        if any(alerts):info.set("\n".join(filter(None,alerts)))
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
