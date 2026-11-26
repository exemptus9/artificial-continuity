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
import recall_review
import situation
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

def search_preview(row, terms):
    if not terms:
        return None
    meta=json.loads(row["metadata"])
    for label,value in (("Transcript",row["content"]),("Correction",meta.get("correction")),("Note",meta.get("note"))):
        if not isinstance(value,str): continue
        for term in terms:
            hit=re.search(re.escape(term),value,re.IGNORECASE)
            if hit:
                left=max(0,hit.start()-52)
                right=min(len(value),max(hit.end()+90,left+155))
                snippet=" ".join(value[left:right].split())
                return {"field":label,"text":("…" if left else "")+snippet+("…" if right<len(value) else "")}
    return None

def items(arc,kind,q,limit,offset,category="",start=None,end=None,device="all"):
    if kind not in ("all","clip","segment","journal") or len(q)>500 or not 1<=limit<=200 or not 0<=offset<=10_000_000:
        raise Failure(400,"Invalid archive query")
    if category and category not in {*CLIP_LABELS.values(),"other"}:raise Failure(400,"Unknown clip category")
    if (start is None)!=(end is None) or start is not None and not 0<=start<end<=253402300799999:
        raise Failure(400,"Invalid date filter")
    where=[];args=[]
    laptop="(a.kind='segment' AND a.id GLOB 'laptop-[0-9]*' OR a.kind='clip' AND a.id GLOB 'desk-keep-[0-9]*')"
    if device not in ("all","laptop","other"):raise Failure(400,"Invalid device filter")
    if device!="all":where.append(laptop if device=="laptop" else "NOT "+laptop)
    if kind!="all": where.append("a.kind=?");args.append(kind)
    if start is not None:
        where.append("startedAtMillis>=? AND startedAtMillis<?");args.extend((start,end))
    if category:
        where.append("a.kind='clip' AND has_clip_label(json_extract(metadata,'$.category'),?)")
        args.append(category)
    terms=q.casefold().split()[:12]
    with arc.connection() as db:
        aliases=[(r[0].casefold(),r[1].casefold()) for r in db.execute("SELECT DISTINCT name,heard FROM owner_reviews WHERE name!='' AND heard!='' LIMIT 10000")] if terms else []
    for term in terms:
        where.append("instr(fold_text(title||' '||content||' '||coalesce(json_extract(metadata,'$.note'),'')||' '||coalesce(json_extract(metadata,'$.category'),'')||' '||coalesce(json_extract(metadata,'$.correction'),'')),?)>0")
        variants=[heard for name,heard in aliases if name==term][:8]
        prepared=where.pop()
        parts=[prepared for _ in [term,*variants]]
        parts.append("instr(fold_text(coalesce(rv.name,'')||' '||coalesce(rv.writing,'')||' '||coalesce(rv.labels,'')||' '||coalesce(st.body,'')),?)>0 OR EXISTS(SELECT 1 FROM recording_evidence ev,json_each(ev.cues) ec WHERE ev.id=a.id AND ev.kind=a.kind AND instr(fold_text(json_extract(ec.value,'$.quote')),?)>0)")
        where.append("("+ " OR ".join(parts)+")")
        args.extend((term,*variants,term,term))
    sql=" FROM ("+BASE_SQL+") a LEFT JOIN owner_reviews rv ON rv.kind=a.kind AND rv.id=a.id LEFT JOIN item_stories st ON st.kind=a.kind AND st.id=a.id"+(" WHERE "+" AND ".join(where) if where else "")
    with arc.connection() as db:
        if category: db.create_function("has_clip_label",2,lambda value,wanted: wanted in category_keys(value),deterministic=True)
        if terms: db.create_function("fold_text",1,str.casefold,deterministic=True)
        total=db.execute("SELECT count(*)"+sql,args).fetchone()[0]
        rows=db.execute("SELECT a.*"+sql+" ORDER BY startedAtMillis DESC,a.id DESC,a.kind LIMIT ? OFFSET ?",[*args,limit,offset])
        matches=[]
        for row in rows:
            found=item(row)
            snippet=search_preview(row,terms)
            if snippet: found["match"]=snippet
            matches.append(found)
        return {"items":matches,"total":total}

def recordings_at(arc,instant):
    if not 0<instant<=253402300799999:raise Failure(400,"Invalid recording time")
    with arc.connection() as db:
        rows=db.execute("""SELECT id,kind,title,started AS startedAtMillis,duration AS durationMs FROM (
                SELECT id,'clip' AS kind,title,started,duration FROM clips WHERE started<=? AND started+duration>?
                UNION ALL
                SELECT id,'segment',coalesce(nullif(json_extract(metadata,'$.title'),''),'Continuous audio'),started,
                       cast(json_extract(metadata,'$.durationMs') AS INTEGER) AS duration
                FROM archive_uploads WHERE state='complete' AND kind='segment' AND started<=? AND
                     started+cast(json_extract(metadata,'$.durationMs') AS INTEGER)>?)
            ORDER BY CASE kind WHEN 'segment' THEN 0 ELSE 1 END,started DESC,id LIMIT 13""",(instant,)*4).fetchall()
        return {"items":[dict(row) for row in rows[:12]],"more":len(rows)>12}

def lookup(arc,kind,uid):
    if kind not in ("clip","segment","journal") or not UID.fullmatch(uid): raise Failure(404,"Missing item")
    with arc.connection() as db:
        row=db.execute("SELECT * FROM ("+BASE_SQL+") WHERE kind=? AND id=?",(kind,uid)).fetchone()
    if not row: raise Failure(404,"Item is no longer in this archive")
    result=item(row,True)
    with arc.connection() as db:
        result["review"]=recall_review.review(db,kind,uid);result["filingOptions"]=recall_review.options(db)
        story=db.execute("SELECT body,rev FROM item_stories WHERE kind=? AND id=?",(kind,uid)).fetchone()
        result["story"]=dict(body=story[0],revision=story[1]) if story else dict(body="",revision=0)
        evidence=list(db.execute("SELECT offset,mode,cues FROM recording_evidence WHERE kind=? AND id=? ORDER BY offset,mode",(kind,uid)))
    groups=situation.context_for(result)
    for offset,mode,cues in evidence:
        if json.loads(cues):groups.append(dict(source="Recording · "+mode+" · "+situation.stamp(offset),items=json.loads(cues)))
    result["situation"]=groups
    result["story"]["draft"]=situation.story_draft(groups)
    return result

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
    def do_POST(self):
        try:
            host=self.headers.get("Host","")
            if not self.valid_host(host) or not self.auth():raise Failure(401,"Owner sign-in required")
            scheme="https" if hasattr(self.server,"public_host") else "http"
            if self.headers.get("Origin")!=scheme+"://"+host:raise Failure(403,"Use your archive page")
            if self.headers.get_content_type()!="application/json":raise Failure(415,"Expected JSON")
            sizes=self.headers.get_all("Content-Length") or []
            if len(sizes)!=1 or not re.fullmatch(r"[0-9]{1,6}",sizes[0]) or int(sizes[0])>200000 or self.headers.get("Transfer-Encoding"):raise Failure(413,"Invalid request size")
            op={"/api/analyze-recording":situation.analyze_audio,"/api/review/file":recall_review.classify,"/api/review/options":recall_review.save_options,"/api/archive/delete":recall_review.delete}.get(self.path)
            if not op:raise Failure(404,"Unknown action")
            data=json.loads(self.rfile.read(int(sizes[0])))
            if not isinstance(data,dict):raise Failure(400,"Expected an object")
            self.json(200,op(self.server.archive,data))
        except Failure as err:self.json(err.status,{"error":str(err)})
        except (UnicodeError,ValueError,TypeError):self.json(400,{"error":"Invalid request"})
        except (OSError,sqlite3.Error):self.json(503,{"error":"Archive busy or storage unavailable. Retry."})
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
            if path=="/api/review":return self.json(200,recall_review.inbox(arc,param("shelf","inbox"),int(param("offset","0"))))
            if path=="/api/items":
                start=param("from",None);end=param("to",None)
                return self.json(200,items(arc,param("kind","all"),param("q",""),int(param("limit","50")),
                    int(param("offset","0")),param("category",""),int(start) if start is not None else None,int(end) if end is not None else None,param("device","all")))
            if path=="/api/recordings-at":return self.json(200,recordings_at(arc,int(param("at","0"))))
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
