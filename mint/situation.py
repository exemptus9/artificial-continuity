"""Read-only evidence views, derived from the selected original or its reviewed text."""
from array import array
from contextlib import closing, contextmanager
from functools import lru_cache
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading

from recall_archive import Failure, UID

SLOT = threading.BoundedSemaphore(1)
ENTRY = re.compile(r"^\d{4}-\d{2}-\d{2} (\d\d:\d\d:\d\d) (?:Z|[+-]\d\d:\d\d)  (.+)$")
MODEL = Path("/usr/share/pocketsphinx/model/en-us")


@lru_cache(maxsize=1)
def rules():
    rows = (Path(__file__).parent/"web/situation-rules.tsv").read_text().splitlines()
    return [(label, re.compile(pattern, re.I)) for label,pattern in (r.split("\t",1) for r in rows)]


def interpret(passages):
    found = {}
    first = None
    for text, stamp, seconds in passages:
        for line in re.split(r"(?<=[?!.])\s+|\n+", text):
            line = line.strip()
            if not line:
                continue
            first = first or dict(label="Source excerpt", quote=line[:190], stamp=stamp, seconds=seconds)
            for label, rule in rules():
                if label in found:
                    continue
                hit = rule.search(line.replace("’","'"))
                if hit:
                    start = max(0,hit.start()-55)
                    quote = ("…" if start else "") + line[start:start+190] + ("…" if start+190<len(line) else "")
                    found[label] = dict(label=label,quote=quote,stamp=stamp,seconds=seconds)
    return list(found.values()) or ([first] if first else [])


def context_for(item):
    parts = []
    if item["kind"] == "journal":
        def entries():
            for line in item.get("text","").splitlines():
                hit = ENTRY.match(line)
                if hit and not hit[2].startswith("[capture status]"):
                    yield hit[2],hit[1],None
        parts.append(("Journal entries",interpret(entries())))
    else:
        key = "correction" if item.get("correction") else "transcript"
        parts.append(("Your correction" if key=="correction" else "Recognized words",
                      interpret([(item.get(key,""),"",None)])))
    for title, text in (("Your note",item.get("note")),("Filed writing",item.get("review",{}).get("writing"))):
        if text:
            parts.append((title,interpret([(text,"",None)])))
    return [dict(source=title,items=items) for title,items in parts if items]


def story_draft(groups):
    roles={"Question":"A question came up","Possible decision":"The words suggest a decision",
           "Possible next step":"A possible next step was mentioned","Possible obstacle":"A possible obstacle came up",
           "Creative idea":"An idea surfaced","Time reference":"The timing mentioned was",
           "Needs checking":"Something was left uncertain","Source excerpt":"One passage reads"}
    seen=set()
    sentences=[]
    for group in groups:
        for cue in group["items"]:
            quote=cue["quote"]
            if quote.casefold() in seen:continue
            seen.add(quote.casefold())
            origin=group["source"]+(" at "+cue["stamp"] if cue["stamp"] else "")
            sentences.append(f'{roles.get(cue["label"],"One passage reads")} — “{quote}” ({origin}).')
            if len(sentences)==5:return " ".join(sentences)
    return " ".join(sentences)


class VoiceFocus:
    """Remove low rumble and gently lift quiet frames for a second recognition pass."""
    def __init__(self):
        self.previous=0
        self.filtered=0.
        self.gain=1.

    def process(self,frame):
        filtered=[]
        for value in frame:
            self.filtered=.965*(self.filtered+value-self.previous)
            self.previous=value
            filtered.append(self.filtered)
        peak=max(map(abs,filtered),default=0)
        target=min(10.,max(1.,7000/max(peak,1))) if peak>12 else 1.
        self.gain=min(target,.65*self.gain+.35*target)
        return array("h",(max(-32768,min(32767,round(v*self.gain))) for v in filtered)).tobytes()


@contextmanager
def media_input(arc, kind, uid):
    if hasattr(arc,"media_store"):
        info = arc.media_store.head(uid,kind=kind)
        arc.reserve_transfer("playback",info["ContentLength"])
        with closing(arc.media_store.read(uid,kind=kind)) as stream:
            yield stream
    else:
        with arc.connection() as db:
            if kind == "clip":
                row = db.execute("SELECT rowid FROM clips WHERE id=?",(uid,)).fetchone()
                if not row: raise Failure(404,"Missing clip")
                src = db.blobopen("clips","audio",row[0],readonly=True)
            else:
                row = db.execute("SELECT file FROM archive_uploads WHERE state='complete' AND kind='segment' AND id=?",(uid,)).fetchone()
                if not row: raise Failure(404,"Missing recording")
                src = (arc.root/row[0]).open("rb")
            with closing(src): yield src


def new_decoder():
    from pocketsphinx import Decoder
    config = Decoder.default_config()
    if MODEL.is_dir():
        for key, name in (("-hmm","en-us"),("-lm","en-us.lm.bin"),("-dict","cmudict-en-us.dict")):
            config.set_string(key,str(MODEL/name))
    config.set_string("-logfn",os.devnull)
    return Decoder(config)


def stamp(seconds):
    return f"{int(seconds)//60}:{int(seconds)%60:02d}"


def analyze_audio(arc, data):
    uid,kind,offset = data.get("id"),data.get("kind"),data.get("offset",0)
    if not isinstance(uid,str) or not UID.fullmatch(uid) or kind not in ("segment","clip") or type(offset)!=int or not 0<=offset<=86400:
        raise Failure(400,"Invalid recording selection")
    focused=data.get("voiceFocus",False)
    if type(focused)!=bool:raise Failure(400,"Invalid listening mode")
    if not SLOT.acquire(blocking=False): raise Failure(429,"Another recording is being analyzed. Try again shortly.")
    try:
        with arc.connection() as db:
            if kind=="clip": row=db.execute("SELECT sha FROM clips WHERE id=?",(uid,)).fetchone()
            else: row=db.execute("SELECT sha FROM archive_uploads WHERE kind='segment' AND id=? AND state='complete'",(uid,)).fetchone()
        if not row: raise Failure(404,"Missing recording")
        with tempfile.TemporaryFile() as local:
            digest=hashlib.sha256()
            with media_input(arc,kind,uid) as src:
                size=0
                while chunk:=src.read(256*1024):
                    size+=len(chunk)
                    if size>128*1024*1024:raise Failure(413,"Recording exceeds the archive item size limit")
                    local.write(chunk);digest.update(chunk)
            if digest.hexdigest()!=row[0]:raise Failure(422,"Original audio checksum mismatch.")
            local.seek(0)
            args=["ffmpeg","-nostdin","-v","error","-protocol_whitelist","file","-format_whitelist","wav,aac,mov",
                  "-ss",str(offset),"-i",f"/proc/self/fd/{local.fileno()}","-t","600.1","-ar","16000","-ac","1","-f","s16le","pipe:1"]
            try: decoder=new_decoder()
            except (ImportError,RuntimeError,OSError,ValueError) as e: raise Failure(503,"Offline English speech model is unavailable") from e
            with subprocess.Popen(args,stdin=subprocess.DEVNULL,pass_fds=(local.fileno(),),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL) as proc:
                try: result=scan(proc.stdout,decoder,offset,focused)
                finally:
                    proc.stdout.close()
                    if proc.poll() is None:
                        try:proc.wait(timeout=5)
                        except subprocess.TimeoutExpired:proc.kill();proc.wait()
                if proc.returncode:raise Failure(422,"The original audio could not be decoded.")
            from recall_review import require
            with arc.connection() as db:
                db.execute("BEGIN IMMEDIATE")
                if require(db,kind,uid)!=row[0]:raise Failure(409,"Recording changed during analysis")
                mode="voice focus" if focused else "original"
                db.execute("INSERT OR REPLACE INTO recording_evidence VALUES(?,?,?,?,?)",(kind,uid,offset,mode,json.dumps(result["cues"])))
            result["storyDraft"]=story_draft([dict(source=mode,items=result["cues"])])
            return result
    finally: SLOT.release()


def scan(stream,decoder,offset,focused=False):
    focus=VoiceFocus() if focused else None
    speech_quiet=0
    samples=active=quiet=longest=peak=phrase_start=0
    passages=[];more=False
    decoder.start_utt()
    def end_phrase():
        decoder.end_utt()
        hyp=decoder.hyp()
        if hyp and hyp.hypstr.strip():
            t=offset+phrase_start/16000
            passages.append((hyp.hypstr.strip(),stamp(t)+"–"+stamp(offset+samples/16000),t))
    while block:=stream.read(3200):
        if samples>=600*16000:
            more=True;continue
        frame=array("h",block)
        if not frame:continue
        samples+=len(frame)
        peak=max(peak,max(abs(v) for v in frame))
        if sum(v*v for v in frame)>=10738*len(frame):
            active+=len(frame);quiet=0
        else:
            quiet+=len(frame);longest=max(longest,quiet)
        speech=focus.process(frame) if focus else block
        if focus:
            enhanced=array("h",speech)
            speech_quiet=speech_quiet+len(frame) if sum(v*v for v in enhanced)<10738*len(frame) else 0
        else:speech_quiet=quiet
        decoder.process_raw(speech,False,False)
        if samples-phrase_start>=15*16000 or (samples-phrase_start>=16000 and speech_quiet>=11200):
            end_phrase();decoder.start_utt();phrase_start=samples
    end_phrase()
    if not samples:raise Failure(422,"No audio samples in this range.")
    return dict(start=offset,seconds=samples/16000,abovePercent=round(100*active/samples),
                quietSeconds=round(longest/16000,1),peakDbfs=round(20*math.log10(peak/32768),1) if peak else None,
                cues=interpret(passages),next=offset+600 if more else None)
