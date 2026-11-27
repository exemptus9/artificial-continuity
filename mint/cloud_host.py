"""Owner-only public HTTPS origin. Railway terminates TLS; LAN serving remains separate."""
import base64
from collections import OrderedDict
from datetime import datetime, timezone
import hashlib
import hmac
from http.cookies import SimpleCookie, CookieError
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import struct
import threading
import time
import tempfile
from urllib.parse import parse_qs, urlsplit
import zlib

import recall_archive
import recall_desk
import recall_web
import early_access

COOKIE = "__Host-total_recall"
DAY = 86400
TRANSFER_CAP = 25_000_000_000
MEDIA_CAP = 1_000_000_000_000
KEY = re.compile(r"[0-9a-f]{64}")
PREVIOUS_APK = ("TotalRecall-0.12.0.apk",56_121_560,"7fc15a16dcb448a601bea9334147be96ab6f3166419546e2c1d38626557457b1")
PRIOR_STORY_APK = ("TotalRecall-0.14.0.apk",56129834,"83c346764d3553ad58d8f49c14b6bedfff4102e8c547837ff15dfb2121b2ef1f")
LAST_APK = ("TotalRecall-0.15.0.apk",56138026,"c4383289901e275f0a4102877dd4e0f1db87234e3345d7373f720c799d84e48e")
RESUME_APK = ("TotalRecall-0.16.0.apk",56138026,"427cfca878e980e95e49b645957f4e6ee3e20e77c016daa9553aa81c4d1b3e55")
SPEAKER_APK = ("TotalRecall-0.17.0.apk",70027652,"da51ecdbb9ec9db9a67b0b4c98badfdaa594da884ff3839e42d5db545c6232dd")
PASSAGE_APK = ("TotalRecall-0.18.0.apk",70035844,"734ae0915ee467b9e91b4867e6664bd15b7b5b1e029392d0f62b6617cddff2b0")
APK_NAME = "TotalRecall-0.19.0.apk"
APK_SIZE = 70044036
APK_SHA = "b69eb035739c0a84793857d7c585e124885ad0cd002ed305d6f8d7802df16cd7"
APK_MIME = "application/vnd.android.package-archive"
APK_KEY = "total-recall/installers/"+APK_SHA+"/"+APK_NAME

def login_page(failed=False):
    prompt = "That key did not match. Try the owner key from your private access file." if failed else "Use the owner key from your private access file."
    return ("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>Total Recall · Sign in</title><style>
    *{box-sizing:border-box}body{margin:0;min-height:100svh;background:#f7f5ec;color:#18382e;font:1rem/1.5 system-ui;display:grid;place-items:center;padding:24px}
    main{width:min(100%,27rem)}header{display:flex;align-items:center;gap:12px;font:bold 1.3rem Georgia,serif}svg{width:38px;height:38px}h1{font:normal clamp(2.5rem,9vw,4rem)/1 Georgia,serif;letter-spacing:-.04em;margin:48px 0 14px}p{color:#496459}
    label{display:block;font-weight:600;margin:26px 0 6px}input,button{width:100%;padding:14px;border-radius:10px;font:inherit}
    input{border:1px solid #80988b;background:white;color:#172d25}button{margin-top:12px;background:#164c3d;color:white;border:0;font-weight:650;cursor:pointer}
    :focus-visible{outline:3px solid #b17728;outline-offset:3px}</style><main><header><svg viewBox="0 0 64 64" aria-hidden="true" fill="none" stroke="#196348" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"><path d="M41 11 A23 23 0 1 0 54 37 M17 32h5l5-9 5 18 6-17 4 8h6"/><circle cx="50" cy="19" r="4" fill="#ac5b26" stroke="none"/></svg>Total Recall</header><h1>Your archive</h1><p>Listen to saved audio, find a clip, or connect your phone.</p><form method="post" action="/login"><label for="key">Owner key</label><input id="key" name="key" type="password" autocomplete="current-password" minlength="20" maxlength="160" required><p>"""+prompt+"""</p><button>Open private archive</button></form></main></html>""").encode()

def qr_png(code):
    bits=recall_desk.qr_bits(code)
    if bits is None: raise recall_archive.Failure(503,"QR unavailable. Copy the pairing code instead.")
    width,modules=bits; border=4; scale=5; size=(width+border*2)*scale
    pixels=bytearray()
    for y in range(-border,width+border):
        row=bytearray([0])
        for x in range(-border,width+border):
            dark=0<=y<width and 0<=x<width and modules[y*width+x]&1
            row.extend(bytes([0 if dark else 255])*scale)
        pixels.extend(row*scale)
    def chunk(kind,data):
        return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data)&0xffffffff)
    return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",size,size,8,0,0,0,0))+chunk(b"IDAT",zlib.compress(bytes(pixels)))+chunk(b"IEND",b"")

class HostedArchive(recall_archive.Archive):
    def __init__(self,desk,media_factory):
        super().__init__(desk)
        self.limit(MEDIA_CAP)
        with self.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS cloud_transfer_month (month TEXT,kind TEXT,bytes INTEGER NOT NULL,PRIMARY KEY(month,kind))")
        self.media_store=media_factory(self)

    def reserve_transfer(self,kind,n):
        month=datetime.now(timezone.utc).strftime("%Y-%m")
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            amount=db.execute("SELECT bytes FROM cloud_transfer_month WHERE month=? AND kind=?",(month,kind)).fetchone()
            if n<0 or n+(amount[0] if amount else 0)>TRANSFER_CAP:
                raise recall_archive.Failure(429,"Monthly "+kind+" allowance reached. The phone keeps untransferred audio.")
            db.execute("""INSERT INTO cloud_transfer_month VALUES(?,?,?) ON CONFLICT(month,kind)
                          DO UPDATE SET bytes=bytes+excluded.bytes""",(month,kind,n))

    def status(self):
        value=super().status()
        value.pop("root",None)
        with self.connection() as db:
            month=datetime.now(timezone.utc).strftime("%Y-%m")
            transfers=dict(db.execute("SELECT kind,bytes FROM cloud_transfer_month WHERE month=?",(month,)).fetchall())
        remaining=max(0,value["quotaBytes"]-value["usedBytes"]-value["reservedBytes"])
        upload_remaining=max(0,TRANSFER_CAP-transfers.get("upload",0))
        value.update(remainingArchiveBytes=remaining,stagingAvailableBytes=value["diskAvailableBytes"],
                     availableBytes=min(remaining,upload_remaining) if value["diskAvailableBytes"] else 0,
                     hosted=True,phonePairing=True,transferCapBytes=TRANSFER_CAP,monthUploadBytes=transfers.get("upload",0),
                     monthPlaybackBytes=transfers.get("playback",0))
        return value

    def verified_receipt(self,receipt):
        if receipt.get("complete"):
            try: self.media_store.ensure(receipt["id"])
            except recall_archive.Failure: raise
            except Exception: raise recall_archive.Failure(503,"Awaiting verified object storage. Retry this transfer.") from None
        return receipt

    def begin(self,data): return self.verified_receipt(super().begin(data))
    def put(self,uid,offset,body):
        self.reserve_transfer("upload",len(body))
        return self.verified_receipt(super().put(uid,offset,body))

class HostedHandler(recall_web.OwnerHandler):
    # Reuse the same bounded manifest/chunk protocol as the desktop receiver.
    authorized=recall_desk.TransferHandler.authorized
    length=recall_desk.TransferHandler.length
    upload=recall_desk.TransferHandler.upload
    def reply(self,code,value): self.json(code,value)

    def valid_host(self,host): return host.lower()==self.server.public_host

    def end_headers(self):
        for key,value in (
            ("Cache-Control","private, no-store"),("Strict-Transport-Security","max-age=31536000"),
            ("Referrer-Policy","same-origin"),("X-Content-Type-Options","nosniff"),
            ("X-Frame-Options","DENY"),("X-Robots-Tag","index, follow" if urlsplit(self.path).path=="/early-access" else "noindex, nofollow"),
            ("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'self'")):
            self.send_header(key,value)
        BaseHTTPRequestHandler.end_headers(self)

    def auth(self):
        cookie=SimpleCookie()
        try:
            cookie.load(self.headers.get("Cookie",""))
            stamp,nonce,mac=cookie[COOKIE].value.split(".")
            if not (stamp.isdigit() and len(stamp)<=12 and re.fullmatch("[a-f0-9]{32}",nonce) and KEY.fullmatch(mac)): return False
            if not time.time()<int(stamp)<=time.time()+31*DAY:return False
            want=hmac.digest(self.server.session_key,(stamp+"."+nonce).encode(),"sha256").hex()
            return hmac.compare_digest(want,mac)
        except (KeyError,ValueError,CookieError):return False

    def redirects(self,value):
        self.send_response(303);self.send_header("Location","/");self.send_header("Content-Length","0")
        self.send_header("Set-Cookie",value);self.end_headers()

    def get_pair(self):
        raw=json.dumps({"url":"https://"+self.server.public_host,"token":self.server.token,"tls":"public-ca"},separators=(",",":")).encode()
        return "TR1."+base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    def apk_path(self,name=APK_NAME,digest=APK_SHA):
        # This capability can download one specific release; archive credentials never enter the link.
        tag=hmac.digest(self.server.session_key,("download-apk/"+digest).encode(),"sha256").hex()[:32]
        return "/downloads/"+tag+"/"+name

    def download_apk(self,release=None):
        media=self.server.archive.media_store
        try:
            choices=[release] if release else [(APK_NAME,APK_SIZE,APK_SHA),PASSAGE_APK,SPEAKER_APK,RESUME_APK,LAST_APK,PRIOR_STORY_APK,PREVIOUS_APK]
            for name,size,digest in choices:
                key="total-recall/installers/"+digest+"/"+name
                head=media._head(key)
                if head and head["ContentLength"]==size and head.get("Metadata",{}).get("sha256")==digest:break
            else:return self.json(503,{"error":"The Android installer is not available yet."})
        except Exception:
            return self.json(503,{"error":"The installer is temporarily unavailable."})

        # Keep a stable origin, filename, byte count and validator across download-manager retries.
        etag='"'+digest+'"'
        header=self.headers.get("Range") if self.headers.get("If-Range",etag)==etag else None
        first,last=0,size-1
        if header is not None:
            match=re.fullmatch(r"bytes=(\d{0,18})-(\d{0,18})",header.strip())
            if match and any(match.groups()):
                left,right=match.groups()
                first=int(left) if left else max(0,size-int(right))
                last=min(size-1,int(right)) if left and right else size-1
            else:first=size
            if first>last:
                self.send_response(416);self.send_header("Content-Range",f"bytes */{size}")
                self.send_header("Content-Length","0");self.end_headers();return
        length=last-first+1
        stream=None
        if self.command!="HEAD":
            try:
                params={"Range":f"bytes={first}-{last}"} if header is not None else {}
                obj=media._call(media.s3.get_object,Key=key,**params)
                stream=obj["Body"]
                if obj["ContentLength"]!=length or (header is not None and obj.get("ContentRange")!=f"bytes {first}-{last}/{size}"):
                    raise ValueError("Unexpected installer range")
            except Exception:
                if stream is not None:stream.close()
                return self.json(503,{"error":"The installer is temporarily unavailable."})
        try:
            self.send_response(206 if header is not None else 200)
            self.send_header("Content-Type",APK_MIME)
            self.send_header("Content-Disposition",'attachment; filename="'+name+'"')
            self.send_header("Accept-Ranges","bytes")
            self.send_header("ETag",etag)
            self.send_header("Content-Length",str(length))
            if header is not None:self.send_header("Content-Range",f"bytes {first}-{last}/{size}")
            self.end_headers()
            while stream is not None and length:
                block=stream.read(min(length,256*1024))
                if not block:break
                self.wfile.write(block);length-=len(block)
        except (OSError,ConnectionError):
            self.close_connection=True
        finally:
            if stream is not None:stream.close()

    def install_apk(self):
        if not self.auth() or self.headers.get("Origin")!="https://"+self.server.public_host:
            return self.json(403,{"error":"Owner sign-in required."})
        try:
            if self.length(APK_SIZE)!=APK_SIZE:return self.json(422,{"error":"Unexpected installer size."})
            media=self.server.archive.media_store
            with tempfile.TemporaryFile() as out:
                remaining=APK_SIZE;digest=hashlib.sha256()
                while remaining:
                    data=self.rfile.read(min(remaining,256*1024))
                    if not data:return self.json(400,{"error":"Incomplete installer."})
                    out.write(data);digest.update(data);remaining-=len(data)
                if digest.hexdigest()!=APK_SHA:return self.json(422,{"error":"Wrong release checksum."})
                out.seek(0)
                media.s3.put_object(Bucket=media.bucket,Key=APK_KEY,Body=out,ContentLength=APK_SIZE,
                    ContentType=APK_MIME,ContentDisposition='attachment; filename="'+APK_NAME+'"',Metadata={"sha256":APK_SHA})
        except recall_archive.Failure as err:return self.json(err.status,{"error":str(err)})
        except Exception:return self.json(503,{"error":"Could not store the installer."})
        return self.json(200,{"path":self.apk_path()})

    def do_GET(self):
        path=urlsplit(self.path).path
        if path=="/healthz": return self.json(200,{"ok":True})
        if not self.valid_host(self.headers.get("Host","")): return self.json(403,{"error":"Unrecognized origin"})
        if path in ("/early-access","/early-access/received"):
            content=early_access.SAVED if path.endswith("/received") else early_access.FORM
            return self.emit(200,early_access.page(self.server.assets,content),"text/html; charset=utf-8")
        if path==self.apk_path():return self.download_apk((APK_NAME,APK_SIZE,APK_SHA))
        for old in (PASSAGE_APK,SPEAKER_APK,RESUME_APK,LAST_APK,PRIOR_STORY_APK,PREVIOUS_APK):
            if path==self.apk_path(old[0],old[2]):return self.download_apk(old)
        if path=="/v2/status": return recall_desk.TransferHandler.do_GET(self)
        if path.startswith("/open/"): return self.json(404,{"error":"Unknown route"})
        if not self.auth():
            if path in ("/","/login"):return self.emit(200,login_page(),"text/html; charset=utf-8")
            return self.json(401,{"error":"Sign in to your archive."})
        if path=="/downloads/android":return self.download_apk()
        if path=="/api/early-access":
            return self.json(200,{"signups":early_access.roster(self.server.archive.root)})
        if path=="/api/android-installer":return self.json(200,{"path":self.apk_path()})
        if path=="/api/phone-pairing":return self.json(200,{"code":self.get_pair(),"origin":"https://"+self.server.public_host})
        if path=="/api/phone-qr.png":
            try:return self.emit(200,qr_png(self.get_pair()),"image/png")
            except recall_archive.Failure as e:return self.json(e.status,{"error":str(e)})
        if path=="/logout":
            return self.emit(200,b'<!doctype html><html lang="en"><title>Total Recall</title><form method="post" action="/logout"><button>Sign out of Total Recall</button></form></html>',"text/html; charset=utf-8")
        return super().do_GET()

    def do_POST(self):
        if not self.valid_host(self.headers.get("Host","")):return self.json(403,{"error":"Unrecognized origin"})
        if self.path=="/v2/uploads":return self.upload()
        origin=self.headers.get("Origin")
        # Some browsers redact Origin on a form when the owner page suppresses referrers.
        local_form=(origin=="null" and self.path in ("/login","/logout")
                    and self.headers.get("Sec-Fetch-Site")=="same-origin"
                    and self.headers.get("Sec-Fetch-Mode")=="navigate")
        if origin and origin!="https://"+self.server.public_host and not local_form:
            return self.json(403,{"error":"Use the archive sign-in page."})
        if self.path=="/early-access":
            if origin!="https://"+self.server.public_host:return self.json(403,{"error":"Use the invitation form."})
            if self.headers.get_content_type()!="application/x-www-form-urlencoded":return self.json(415,{"error":"Use the invitation form."})
            try:size=self.length(1800)
            except recall_archive.Failure as e:return self.json(e.status,{"error":str(e)})
            if not self.server.signup_limiter(self.headers.get("X-Real-IP",self.client_address[0])[:100]):
                return self.json(429,{"error":"Too many attempts. Try again later."})
            if not early_access.register(self.rfile.read(size),self.server.archive.root):
                form='<p role="alert">Enter a valid email and check the invitation box.</p>'+early_access.FORM
                return self.emit(400,early_access.page(self.server.assets,form),"text/html; charset=utf-8")
            self.send_response(303);self.send_header("Location","/early-access/received#join")
            self.send_header("Content-Length","0");self.end_headers();return
        if self.path in ("/api/review/file","/api/review/options","/api/archive/delete","/api/analyze-recording"):return super().do_POST()
        if self.path=="/api/storage-check":
            if not self.auth():return self.json(401,{"error":"Sign in to your archive."})
            if origin!="https://"+self.server.public_host:return self.json(403,{"error":"Use your archive origin."})
            try: self.server.archive.media_store.verify_storage()
            except Exception:return self.json(503,{"error":"Bucket round trip failed."})
            return self.json(200,{"verified":True})
        if self.path=="/logout":
            return self.redirects(COOKIE+"=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0")
        if self.path!="/login":return self.json(404,{"error":"Unknown route"})
        if self.headers.get_content_type()!="application/x-www-form-urlencoded":return self.json(415,{"error":"Use the sign-in form."})
        try: size=self.length(1024)
        except recall_archive.Failure as e:return self.json(e.status,{"error":str(e)})
        ip=self.headers.get("X-Real-IP",self.client_address[0])[:100]
        if not self.server.login_allowed(ip):return self.json(429,{"error":"Too many attempts. Try again in a few minutes."})
        try: key=parse_qs(self.rfile.read(size).decode("ascii"),max_num_fields=2).get("key",[""])[0]
        except (UnicodeError,ValueError):key=""
        if not hmac.compare_digest(hashlib.sha256(key.encode()).hexdigest(),self.server.owner_hash):
            return self.emit(403,login_page(True),"text/html; charset=utf-8")
        stamp=str(int(time.time())+30*DAY);msg=stamp+"."+secrets.token_hex(16)
        signed=msg+"."+hmac.digest(self.server.session_key,msg.encode(),"sha256").hex()
        return self.redirects(COOKIE+"="+signed+"; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age="+str(30*DAY))

    def do_PUT(self):
        if not self.valid_host(self.headers.get("Host","")):return self.json(403,{"error":"Unrecognized origin"})
        if self.path=="/api/android-installer":return self.install_apk()
        return self.upload()

    def media(self,arc,kind,uid,download):
        info=arc.media_store.head(uid,kind=kind)
        total=info["ContentLength"]
        mime=info["ContentType"]
        if kind=="segment" and mime=="audio/aac" and not download:
            return self.indexed_audio(arc,uid,info["sha256"],total,
                lambda:arc.media_store.read(uid,kind=kind),lambda n:arc.reserve_transfer("playback",n))
        first,last=0,total-1;header=self.headers.get("Range")
        if header:
            m=re.fullmatch(r"bytes=(\d{0,18})-(\d{0,18})",header)
            if not m or not any(m.groups()):raise recall_archive.Failure(416,"Invalid byte range")
            left,right=m.groups()
            if left:first=int(left);last=min(last,int(right)) if right else last
            else:first=max(0,total-int(right))
            if first>last or first>=total:
                self.send_response(416);self.send_header("Content-Range",f"bytes */{total}");self.send_header("Content-Length","0");self.end_headers();return
        length=last-first+1
        if self.command!="HEAD":arc.reserve_transfer("playback",length)
        stream=None
        try:
            if self.command!="HEAD":
                try: stream=arc.media_store.read(uid,first,length,kind=kind)
                except Exception:raise recall_archive.Failure(503,"Original audio is temporarily unavailable.") from None
            self.send_response(206 if header else 200)
            self.send_header("Content-Type",mime);self.send_header("Accept-Ranges","bytes");self.send_header("Content-Length",str(length))
            if header:self.send_header("Content-Range",f"bytes {first}-{last}/{total}")
            if download:
                suffix={"audio/wav":"wav","audio/aac":"aac","audio/mp4":"m4a","text/plain":"txt"}[mime]
                self.send_header("Content-Disposition",f'attachment; filename="{uid}.{suffix}"')
            self.end_headers()
            remaining=length
            while stream is not None and remaining:
                data=stream.read(min(remaining,256*1024))
                if not data:break
                self.wfile.write(data);remaining-=len(data)
        finally:
            if stream is not None:stream.close()

class CloudServer(ThreadingHTTPServer):
    daemon_threads=True
    def __init__(self,address,handler,archive,public_host,owner_hash,phone_token,session_key):
        self.archive=archive;self.public_host=public_host;self.owner_hash=owner_hash;self.token=phone_token
        self.session_key=bytes.fromhex(session_key);self.assets=Path(__file__).resolve().parent/"web"
        self.guard=threading.Lock();self.attempts=OrderedDict();self.aggregate_start=0;self.aggregate_count=0;self.slots=threading.BoundedSemaphore(24)
        from types import SimpleNamespace
        signup=SimpleNamespace(guard=threading.Lock(),attempts=OrderedDict(),aggregate_start=0,aggregate_count=0)
        self.signup_limiter=lambda identity:CloudServer.login_allowed(signup,identity)
        super().__init__(address,handler)
    def login_allowed(self,identity):
        now=time.monotonic()
        with self.guard:
            if now-self.aggregate_start>=300:self.aggregate_start,self.aggregate_count=now,0
            if self.aggregate_count>=180:return False
            self.aggregate_count+=1
            start,count=self.attempts.get(identity,(now,0))
            if now-start>=300:start,count=now,0
            if count>=12:return False
            self.attempts[identity]=start,count+1
            self.attempts.move_to_end(identity)
            while len(self.attempts)>4096:self.attempts.popitem(last=False)
            return True
    def process_request(self,sock,addr):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(sock);return
        sock.settimeout(65)
        try:super().process_request(sock,addr)
        except BaseException:self.slots.release();raise
    def process_request_thread(self,*args):
        try:super().process_request_thread(*args)
        finally:self.slots.release()

def main():
    from cloud_media import CloudMedia
    os.umask(0o077)
    host=os.environ["TR_PUBLIC_HOST"]
    values=[os.environ[name] for name in ("TR_OWNER_KEY_SHA256","TR_PHONE_TOKEN","TR_SESSION_KEY")]
    if not all(KEY.fullmatch(v) for v in values) or not re.fullmatch(r"[a-z0-9-]+\.up\.railway\.app",host):raise SystemExit("Archive credentials or public hostname missing.")
    arc=HostedArchive(recall_desk,CloudMedia)
    server=CloudServer(("0.0.0.0",int(os.getenv("PORT","8080"))),HostedHandler,arc,host,*values)
    def maintenance():
        while True:
            try:
                arc.media_store.maintain()
            except Exception as error:print("Archive maintenance deferred:",type(error).__name__,flush=True)
            time.sleep(3600)
    threading.Thread(target=maintenance,daemon=True).start()
    print("Private archive ready",flush=True)
    server.serve_forever()

if __name__=="__main__":main()
