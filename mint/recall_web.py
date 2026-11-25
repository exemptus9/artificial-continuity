"""Local browser access for the archive. Pairing credentials never enter browser JavaScript."""
from contextlib import contextmanager,closing
from datetime import datetime,timezone
import hashlib
import fcntl
import shutil
import subprocess
import tempfile
import http.cookies
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
from urllib.parse import parse_qs,urlsplit

from recall_archive import DAY, Failure, UID, expiry, now

BASE_SQL="""
 SELECT id,'clip' kind,title,started startedAtMillis,duration durationMs,CASE WHEN length(audio)>0 THEN length(audio) ELSE coalesce((SELECT bytes FROM clip_original_sizes sz WHERE sz.id=clips.id),44+2*samples) END bytes,sha sha256,
 'audio/wav' mediaType,metadata,transcript content FROM clips
 UNION ALL SELECT id,kind,coalesce(nullif(json_extract(metadata,'$.title'),''),CASE kind WHEN 'segment' THEN 'Session audio' ELSE 'Session journal' END),
 started,cast(json_extract(metadata,'$.durationMs') AS integer),bytes,sha,json_extract(metadata,'$.mediaType'),metadata,journal
 FROM archive_uploads WHERE state='complete' AND kind IN ('segment','journal')
"""
def item(row,details=False):
    result=dict(row);meta=json.loads(result.pop("metadata"));content=result.pop("content")
    result["expiresAtMillis"]=expiry(result["startedAtMillis"]+result["durationMs"])
    for key in ("category","reason","parentId","startSample","endSample","sessionId","note","correction","feedback"):
        if key in meta: result[key]=meta[key]
    if result["kind"]=="clip": result["transcript"]=content if details else content[:260]
    if result["kind"]=="journal" and details: result["text"]=content
    return result

def items(arc,kind,q,limit,offset):
    if kind not in ("all","clip","segment","journal") or len(q)>500 or not 1<=limit<=200 or not 0<=offset<=10_000_000:
        raise Failure(400,"Invalid archive query")
    where=[];args=[]
    if kind!="all": where.append("kind=?");args.append(kind)
    for term in q.casefold().split()[:12]:
        where.append("instr(lower(title||' '||content||' '||coalesce(json_extract(metadata,'$.note'),'')||' '||coalesce(json_extract(metadata,'$.category'),'')||' '||coalesce(json_extract(metadata,'$.correction'),'')),?)>0")
        args.append(term)
    sql=" FROM ("+BASE_SQL+")"+(" WHERE "+" AND ".join(where) if where else "")
    with arc.connection() as db:
        total=db.execute("SELECT count(*)"+sql,args).fetchone()[0]
        rows=db.execute("SELECT *"+sql+" ORDER BY startedAtMillis DESC,id DESC LIMIT ? OFFSET ?",[*args,limit,offset])
        return {"items":[item(r) for r in rows],"total":total}

def lookup(arc,kind,uid):
    if kind not in ("clip","segment","journal") or not UID.fullmatch(uid): raise Failure(404,"Missing item")
    with arc.connection() as db:
        row=db.execute("SELECT * FROM ("+BASE_SQL+") WHERE kind=? AND id=?",(kind,uid)).fetchone()
    if not row: raise Failure(404,"Item is no longer in this archive")
    return item(row,True)

# Stable labels written by Android SelectiveEngine and Store. A single clip can have several.
CLIP_LABELS={
    "forge word":"coined", "poem line":"verse", "manual keep":"manual",
    "missed moment":"missed", "project decision":"decision", "connection":"connection", "excerpt":"excerpt"
}

def category_keys(category):
    tags={" ".join(part.casefold().split()) for part in (category or "").split("+")}
    return {CLIP_LABELS.get(tag,"other") for tag in tags if tag} or {"other"}

def overview(arc,start,end,offset):
    if not 0<=start<end or end-start>367*DAY or not -840<=offset<=840: raise Failure(400,"Invalid overview interval")
    first=((start+offset*60000)//DAY)*DAY-offset*60000
    buckets={k:0 for k in range(first,end,DAY)}
    counts={"clip":0,"segment":0,"journal":0};intervals=[];events=[]
    categories={name:{"key":name,"count":0,"latestClipId":None} for name in (*CLIP_LABELS.values(),"other")}
    with arc.connection() as db:
        # Summary uses category, identity and time only. It does not read narration or journal text.
        rows=db.execute("""
            SELECT * FROM (
              SELECT id,'clip' kind,started startedAtMillis,duration durationMs,category FROM clips
              UNION ALL SELECT id,kind,started,CAST(json_extract(metadata,'$.durationMs') AS INTEGER),''
                FROM archive_uploads WHERE state='complete' AND kind IN ('segment','journal')
            ) WHERE startedAtMillis<? AND startedAtMillis+max(1,durationMs)>? ORDER BY startedAtMillis,id
        """,(end,start))
        for r in rows:
            counts[r["kind"]]+=1
            if r["kind"]=="clip":
                for key in category_keys(r["category"]):
                    categories[key]["count"]+=1
                    categories[key]["latestClipId"]=r["id"]
            if r["kind"] in ("clip","segment"):
                a=max(start,r["startedAtMillis"]);b=min(end,r["startedAtMillis"]+r["durationMs"])
                if intervals and intervals[-1][1]>=a: intervals[-1][1]=max(b,intervals[-1][1])
                elif b>a: intervals.append([a,b])
        for r in db.execute("SELECT touched,kind,id FROM archive_uploads WHERE state='complete' AND touched>=? AND touched<? ORDER BY touched DESC LIMIT 30",(start,end)):
            events.append({"atMillis":r["touched"],"label":"Received "+r["kind"],"detail":r["id"]})
    for a,b in intervals:
        while a<b:
            bucket=((a+offset*60000)//DAY)*DAY-offset*60000
            stop=min(b,bucket+DAY);buckets[bucket]+=stop-a;a=stop
    def calendar_day(k):
        return datetime.fromtimestamp((k+offset*60000)/1000,timezone.utc).date().isoformat()
    peak=max(buckets.values(),default=0)
    peak_days=[k for k,duration in buckets.items() if peak and duration==peak]
    busiest=None if not peak_days else dict(date=calendar_day(peak_days[-1]),capturedMs=peak,tiedDays=len(peak_days))
    return dict(clipPatterns=list(categories.values()),busiestDay=busiest,capturedMs=sum(buckets.values()),clipCount=counts["clip"],segmentCount=counts["segment"],
                journalCount=counts["journal"],observedMs=None,gapCount=None,events=events,
                buckets=[dict(startMs=k,date=calendar_day(k),capturedMs=v) for k,v in buckets.items()])

def grant(root,port):
    folder=root/"web-login";folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    token=secrets.token_hex(32);name=hashlib.sha256(token.encode("ascii")).hexdigest()
    with (folder/name).open("x") as out: out.write(str(time.time()))
    for file in folder.iterdir():
        try:
            if file.stat().st_mtime<time.time()-300: file.unlink(missing_ok=True)
        except FileNotFoundError: pass
    return f"http://127.0.0.1:{port}/open/{token}"
def consume(root,token):
    if not re.fullmatch("[a-f0-9]{64}",token): return False
    path=root/"web-login"/hashlib.sha256(token.encode("ascii")).hexdigest()
    try:
        stamp=path.stat().st_mtime;path.unlink()
        return stamp>=time.time()-300
    except FileNotFoundError: return False

def seekable_preview(arc,sha,size,source_factory):
    """Cache a lossless AAC remux for random access. Downloads always use the original bytes."""
    if not re.fullmatch("[a-f0-9]{64}",sha): raise Failure(422,"Invalid original digest")
    folder=arc.root/"browser-previews";folder.mkdir(mode=0o700,exist_ok=True)
    path=folder/(sha+".m4a");limit=256*1024*1024
    with (folder/"cache.lock").open("a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if path.is_file():
            os.utime(path,None);return path.open("rb"),path.stat().st_size
        # One remux at a time; bound cache even when many ranges or browsers arrive together.
        candidates=sorted(folder.glob("*.m4a"),key=lambda f:f.stat().st_mtime)
        total=sum(f.stat().st_size for f in candidates)
        need=size+max(size//5,1024*1024)
        for old in candidates:
            if total+need<=limit and old.stat().st_mtime>=time.time()-3600:break
            total-=old.stat().st_size;old.unlink(missing_ok=True)
        if shutil.disk_usage(folder).free-arc.status()["floorBytes"]<need:
            raise Failure(507,"Insufficient preview space; download the original recording")
        with tempfile.NamedTemporaryFile(suffix=".m4a",dir=folder) as temp:
            args=["ffmpeg","-nostdin","-y","-v","error","-protocol_whitelist","file,pipe",
                  "-f","aac","-i","pipe:0","-map","0:a:0","-c:a","copy","-movflags","+faststart","-f","mp4",temp.name]
            process=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            try:
                digest=hashlib.sha256();count=0
                with closing(source_factory()) as source,process.stdin as sink:
                    while block:=source.read(256*1024):
                        count+=len(block)
                        if count>size:raise Failure(422,"Original length changed")
                        digest.update(block);sink.write(block)
                if process.wait(timeout=90) or count!=size or digest.hexdigest()!=sha:
                    raise Failure(422,"Could not verify or index the original audio")
                if os.stat(temp.name).st_size>need:raise Failure(503,"Preview exceeded its storage reservation")
                # Link a complete indexed file atomically, then open before releasing the cache lock.
                os.link(temp.name,path)
            finally:
                if process.poll() is None:process.kill();process.wait()
        return path.open("rb"),path.stat().st_size

class OwnerHandler(BaseHTTPRequestHandler):
    server_version="TotalRecall"
    def log_message(self,*args): pass
    def end_headers(self):
        self.send_header("Cache-Control","no-store")
        self.send_header("Referrer-Policy","no-referrer")
        self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("X-Frame-Options","DENY")
        self.send_header("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")
        super().end_headers()
    def emit(self,status,body,mime):
        self.send_response(status);self.send_header("Content-Type",mime);self.send_header("Content-Length",str(len(body)))
        self.end_headers()
        if self.command!="HEAD": self.wfile.write(body)
    def json(self,status,data): self.emit(status,json.dumps(data,ensure_ascii=False).encode("utf-8"),"application/json; charset=utf-8")
    def auth(self):
        cookies=http.cookies.SimpleCookie()
        try: cookies.load(self.headers.get("Cookie",""));session=cookies["recall_owner"].value
        except (http.cookies.CookieError,KeyError): return False
        with self.server.guard:return self.server.sessions.get(session,0)>time.time()
    def valid_host(self,host):
        return host in ("localhost:"+str(self.server.server_port),"127.0.0.1:"+str(self.server.server_port))
    def do_HEAD(self): self.do_GET()
    def do_GET(self):
        try:
            host=self.headers.get("Host","")
            if not self.valid_host(host):
                raise Failure(403,"Open the local archive address")
            parts=urlsplit(self.path);path=parts.path;arc=self.server.archive
            if path.startswith("/open/"):
                if self.command!="GET" or not consume(arc.root,path[6:]): raise Failure(401,"Open a fresh link in Total Recall Desk")
                session=secrets.token_urlsafe(32);t=time.time()
                with self.server.guard:
                    self.server.sessions={k:v for k,v in self.server.sessions.items() if v>t}
                    self.server.sessions[session]=t+8*3600
                self.send_response(303);self.send_header("Location","/");self.send_header("Content-Length","0")
                self.send_header("Set-Cookie","recall_owner="+session+"; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800")
                self.end_headers();return
            if not self.auth(): raise Failure(401,"Open Private archive in Total Recall Desk for a fresh sign-in")
            params=parse_qs(parts.query,max_num_fields=12);param=lambda k,d:params.get(k,[d])[0]
            if path in ("/","/app.css","/app.js"):
                name,mime={"/":("index.html","text/html; charset=utf-8"),"/app.css":("app.css","text/css; charset=utf-8"),"/app.js":("app.js","text/javascript; charset=utf-8")}[path]
                self.emit(200,(self.server.assets/name).read_bytes(),mime);return
            if path=="/api/status": return self.json(200,arc.status())
            if path=="/api/items": return self.json(200,items(arc,param("kind","all"),param("q",""),int(param("limit","50")),int(param("offset","0"))))
            if path=="/api/overview": return self.json(200,overview(arc,int(param("from","0")),int(param("to",str(now()))),int(param("tzOffset","0"))))
            route=re.fullmatch(r"/api/(items|media)/(segment|journal|clip)/([A-Za-z0-9_-]{1,128})",path)
            if not route: raise Failure(404,"Missing archive path")
            action,kind,uid=route.groups()
            if action=="items": return self.json(200,lookup(arc,kind,uid))
            self.media(arc,kind,uid,param("download","")=="1")
        except Failure as e:self.json(e.status,{"error":str(e)})
        except (BrokenPipeError,ConnectionResetError): pass
        except (ValueError,OverflowError): self.json(400,{"error":"Invalid request"})
        except (FileNotFoundError,sqlite3.Error,OSError): self.json(503,{"error":"Archive could not read the requested data"})
    def media(self,arc,kind,uid,download):
        with arc.connection() as db:
            if kind=="clip":
                r=db.execute("SELECT rowid,length(audio) n FROM clips WHERE id=?",(uid,)).fetchone()
                if not r: raise Failure(404,"Missing clip")
                stream=db.blobopen("clips","audio",r["rowid"],readonly=True);length=r["n"];mime="audio/wav"
            else:
                r=db.execute("SELECT file,bytes,sha,metadata FROM archive_uploads WHERE id=? AND kind=? AND state='complete'",(uid,kind)).fetchone()
                if not r: raise Failure(404,"Missing capture")
                length=r["bytes"];mime=json.loads(r["metadata"])["mediaType"]
                source=lambda:(arc.root/r["file"]).open("rb")
                if kind=="segment" and mime=="audio/aac" and not download:
                    return self.indexed_audio(arc,uid,r["sha"],length,source)
                stream=source()
            with stream:self.respond_media(stream,length,mime,uid,download)

    def indexed_audio(self,arc,uid,sha,size,source_factory,charge=None):
        stream,length=seekable_preview(arc,sha,size,source_factory)
        with stream:self.respond_media(stream,length,"audio/mp4",uid,False,charge)

    def respond_media(self,stream,length,mime,uid,download,charge=None):
        first=0;last=length-1;header=self.headers.get("Range")
        if header:
            m=re.fullmatch(r"bytes=(\d{0,18})-(\d{0,18})",header)
            if not m or not any(m.groups()): raise Failure(416,"Invalid byte range")
            left,right=m.groups()
            if left:first=int(left);last=min(last,int(right)) if right else last
            else:first=max(0,length-int(right))
            if first>last or first>=length:
                self.send_response(416);self.send_header("Content-Range",f"bytes */{length}")
                self.send_header("Content-Length","0");self.end_headers();return
        if charge and self.command!="HEAD":charge(last-first+1)
        self.send_response(206 if header else 200)
        self.send_header("Content-Type",mime);self.send_header("Accept-Ranges","bytes")
        self.send_header("Content-Length",str(last-first+1))
        if header:self.send_header("Content-Range",f"bytes {first}-{last}/{length}")
        if download:
            ext={"audio/wav":"wav","audio/mp4":"m4a","audio/aac":"aac","text/plain":"txt"}[mime]
            self.send_header("Content-Disposition",'attachment; filename="'+uid+'.'+ext+'"')
        self.end_headers()
        if self.command=="HEAD":return
        stream.seek(first);left=last-first+1
        while left:
            data=stream.read(min(left,256*1024))
            if not data:break
            self.wfile.write(data);left-=len(data)

def web_server(arc,assets,port=8790):
    server=ThreadingHTTPServer(("127.0.0.1",port),OwnerHandler)
    server.archive=arc;server.assets=assets;server.sessions={};server.guard=threading.Lock()
    return server
