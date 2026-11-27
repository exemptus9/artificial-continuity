"""Host application contracts on loopback behind a simulated HTTPS terminator.
Only object storage is replaced. Requests exercise the shipped HTTP handlers, SQLite transactions
and archive validation. The S3 backend has a separate object-protocol test suite.
"""
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import io
import json
from pathlib import Path
import secrets
import tempfile
import threading
import time
import unittest
import wave
import zipfile
from unittest.mock import patch
from urllib.parse import urlencode

import cloud_host
from cloud_media import CloudMedia
import recall_archive
import recall_desk

HOST = "test-owner.up.railway.app"

class MissingObject(Exception):
    response = {"Error":{"Code":"NoSuchKey"}}

class TestS3:
    """Small stand-in for object-storage I/O. The real CloudMedia validates bytes and receipts."""
    def __init__(self):
        self.objects = {}
        self.available = True
        self.ranges = []
        self.checks = []

    def head_object(self, *, Bucket, Key):
        if not self.available:
            raise OSError("test bucket offline")
        if Key not in self.objects:
            raise MissingObject()
        return dict(self.objects[Key][1])

    def put_object(self, *, Bucket, Key, Body, ContentType="application/octet-stream", Metadata=None, **kwargs):
        if not self.available:
            raise OSError("test bucket offline")
        data = Body.read() if hasattr(Body,"read") else Body
        if kwargs.get("ContentLength",len(data)) != len(data):
            raise ValueError("Incomplete test upload")
        head = dict(ContentType=ContentType, Metadata=Metadata or {}, ContentLength=len(data),
                    ETag='"'+hashlib.sha256(data).hexdigest()+'"')
        self.objects[Key] = (data,head)
        if "/diagnostics/" in Key:
            self.checks.append(("put",Key))
        return dict(ETag=head["ETag"])

    def get_object(self, *, Bucket, Key, Range=None, **kwargs):
        head = self.head_object(Bucket=Bucket,Key=Key)
        data = self.objects[Key][0]
        if Range:
            first,last = map(int,Range.split("=")[1].split("-"))
            result = data[first:last+1]
            head.update(ContentLength=len(result), ContentRange=f"bytes {first}-{last}/{len(data)}")
            self.ranges.append((first,len(result)))
        else:
            result = data
        if "/diagnostics/" in Key:
            self.checks.append(("get",Key))
        return dict(head,Body=io.BytesIO(result))

    def delete_object(self, *, Bucket, Key, **kwargs):
        self.objects.pop(Key,None)
        if "/diagnostics/" in Key:
            self.checks.append(("delete",Key))
        return {}

class HostedArchiveTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        paths = patch.multiple(recall_desk, ROOT=self.root, DB=self.root/"clips.sqlite3", APP=self.root/"recall_desk.py")
        paths.start()
        self.addCleanup(paths.stop)
        self.bucket = TestS3()
        self.factory = lambda arc: CloudMedia(arc,client=self.bucket,bucket="disposable-test-bucket")
        self.archive = cloud_host.HostedArchive(recall_desk, self.factory)
        with self.archive.connection() as db:
            db.execute("INSERT INTO archive_settings VALUES('floor',0)")
        self.owner_key, self.phone_key, session_key = (secrets.token_hex(32) for _ in range(3))
        owner_hash = hashlib.sha256(self.owner_key.encode()).hexdigest()
        self.server = cloud_host.CloudServer(("127.0.0.1",0),cloud_host.HostedHandler,self.archive,HOST,
                                            owner_hash,self.phone_key,session_key)
        self.thread = threading.Thread(target=lambda:self.server.serve_forever(poll_interval=0.02),daemon=True)
        self.thread.start()
        def close():
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=3)
        self.addCleanup(close)
        self.bearer = {"Authorization":"Bearer "+self.phone_key}
        self.origin = {"Origin":"https://"+HOST,"Content-Type":"application/x-www-form-urlencoded"}

    def request(self, method, path, body=None, headers=None):
        h = {"Host":HOST, **(headers or {})}
        conn = http.client.HTTPConnection("127.0.0.1",self.server.server_port,timeout=3)
        try:
            conn.request(method,path,body,headers=h)
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_public_invitation_and_private_roster(self):
        status,_,page=self.request("GET","/early-access")
        self.assertEqual(status,200)
        self.assertIn(b"Request an invitation",page)
        self.assertNotIn(b".apk",page)
        for private_path in ("/api/early-access","/early-access/roster","/downloads/android"):
            self.assertEqual(self.request("GET",private_path)[0],401)
        self.assertEqual(self.request("POST","/early-access","email=test%40example.com",self.origin)[0],400)
        for email in ("person@example.com","PERSON@example.com"):
            status,headers,_=self.request("POST","/early-access",urlencode({"email":email,"invite":"yes"}),self.origin)
            self.assertEqual((status,headers["Location"]),(303,"/early-access/received#join"))
        rows=json.loads(self.request("GET","/api/early-access",headers=self.login())[2])["signups"]
        self.assertEqual([row["email"] for row in rows],["person@example.com"])

    def test_browser_installer_sign_in_returns_to_requested_package(self):
        browser = {"Accept": "text/html,application/xhtml+xml"}
        for path in cloud_host.INSTALLER_NAMES:
            status, headers, form = self.request("GET", path, headers=browser)
            self.assertEqual((status, headers["Content-Type"]), (200, "text/html; charset=utf-8"))
            self.assertIn(('name="installer" value="'+path+'"').encode(), form)
            self.assertIn(b"Sign in and download", form)
            self.assertEqual(self.request("GET", path, headers={**browser, "Range": "bytes=10-"})[0], 401)
            status, _, retry_form = self.request("POST", "/login", urlencode({"key":"wrong", "installer":path}), self.origin)
            self.assertEqual(status, 403)
            self.assertIn(('name="installer" value="'+path+'"').encode(), retry_form)
            status, headers, _ = self.request("POST", "/login", urlencode({"key":self.owner_key, "installer":path}), self.origin)
            self.assertEqual((status, headers["Location"]), (303, path))
        status, headers, _ = self.request("POST", "/login", urlencode({"key":self.owner_key, "installer":"//elsewhere.invalid"}), self.origin)
        self.assertEqual((status, headers["Location"]), (303, "/"))

    def test_mint_installer_requires_sign_in_and_returns_the_package(self):
        package = self.root / "total-recall-desk_0.28.0_all.deb"
        package.write_bytes(b"!<arch>\nfixture")
        with patch.object(cloud_host, "MINT_INSTALLER", package):
            path = "/downloads/mint"
            self.assertEqual(self.request("GET", path)[0], 401)
            cookie = self.login()
            status, headers, data = self.request("GET", path, headers=cookie)
            self.assertEqual((status, data), (200, package.read_bytes()))
            self.assertIn(package.name, headers["Content-Disposition"])
            status, headers, data = self.request("HEAD", path, headers=cookie)
            self.assertEqual((status, int(headers["Content-Length"]), data), (200, package.stat().st_size, b""))
            package.unlink()
            self.assertEqual(self.request("GET", path, headers=cookie)[0], 503)

    def test_installer_resumes_without_redirect_or_signin(self):
        payload=b"0123456789abcdefghij";digest=hashlib.sha256(payload).hexdigest()
        name="TotalRecall-fixture.apk";release=(name,len(payload),digest)
        key="total-recall/installers/"+digest+"/"+name
        self.bucket.put_object(Bucket="fixture",Key=key,Body=payload,Metadata={"sha256":digest})
        import hmac
        tag=hmac.digest(self.server.session_key,("download-apk/"+digest).encode(),"sha256").hex()[:32]
        path=f"/downloads/{tag}/{name}"
        with patch.object(cloud_host,"PREVIOUS_APK",release):
            status,h,data=self.request("HEAD",path)
            self.assertEqual((status,h["Content-Length"],data),(200,"20",b""))
            self.assertIn(name,h["Content-Disposition"]);self.assertNotIn("Location",h)
            etag=h["ETag"]
            halves=[]
            for interval,expected in (("0-7","0-7"),("8-","8-19"),("-2","18-19")):
                status,h,data=self.request("GET",path,headers={"Range":"bytes="+interval,"If-Range":etag})
                self.assertEqual((status,h["Content-Range"]),(206,"bytes "+expected+"/20"))
                halves.append(data)
            self.assertEqual(halves[0]+halves[1],payload);self.assertEqual(halves[2],payload[-2:])
            status,_,data=self.request("GET",path,headers={"Range":"bytes=10-","If-Range":'"old"'})
            self.assertEqual((status,data),(200,payload))
            for invalid in ("bytes=20-","bytes=-0","bytes=7-2","bytes=0-1,4-5"):
                status,h,_=self.request("GET",path,headers={"Range":invalid})
                self.assertEqual((status,h["Content-Range"]),(416,"bytes */20"))

    def login(self):
        status,h,_ = self.request("POST","/login",urlencode({"key":self.owner_key}),self.origin)
        self.assertEqual(status,303)
        self.assertEqual(h["Location"],"/")
        for flag in ("Secure","HttpOnly","SameSite=Strict","Path=/"):
            self.assertIn(flag,h["Set-Cookie"])
        return {"Cookie":h["Set-Cookie"].split(";",1)[0]}

    def manifest(self, uid, data):
        return dict(id=uid,kind="journal",sha256=hashlib.sha256(data).hexdigest(),bytes=len(data),
                    startedAtMillis=recall_archive.now(),durationMs=1000,mediaType="text/plain")

    def begin(self, info, auth=None):
        return self.request("POST","/v2/uploads",json.dumps(info),{"Content-Type":"application/json",
                            **(self.bearer if auth is None else auth)})

    def put(self, uid, data, offset=0):
        return self.request("PUT","/v2/uploads/"+uid,data,{**self.bearer,"Content-Type":"application/octet-stream",
                                                           "X-Upload-Offset":str(offset)})

    def saved(self, uid, data):
        status,_,b = self.begin(self.manifest(uid,data))
        self.assertEqual(status,200,b)
        self.assertFalse(json.loads(b)["complete"])
        status,_,b = self.put(uid,data)
        self.assertEqual(status,200,b)
        ack = json.loads(b)
        self.assertTrue(ack["complete"])
        self.assertEqual(ack["sha256"],hashlib.sha256(data).hexdigest())

    def test_owner_sign_in_and_paired_phone_scope(self):
        self.assertEqual(json.loads(self.request("GET","/healthz")[2]),{"ok":True})
        status,h,body = self.request("GET","/")
        self.assertEqual(status,200)
        self.assertIn(b"Owner key",body)
        self.assertIn("no-store",h["Cache-Control"])
        for key in (self.phone_key,self.owner_key):
            self.assertNotIn(key.encode(),body)
        for path in ("/api/phone-pairing","/api/items","/api/phone-qr.png"):
            self.assertEqual(self.request("GET",path)[0],401)
            self.assertEqual(self.request("GET",path,headers=self.bearer)[0],401)
        self.assertEqual(self.request("POST","/login","key=incorrect",self.origin)[0],403)
        self.assertEqual(self.request("GET","/v2/status")[0],401)
        self.assertEqual(self.request("GET","/v2/status",headers=self.bearer)[0],200)
        owner = self.login()
        status,h,body = self.request("GET","/api/phone-pairing",headers=owner)
        self.assertEqual(status,200)
        encoded = json.loads(body)["code"]
        self.assertTrue(encoded.startswith("TR1."))
        p = json.loads(base64.urlsafe_b64decode(encoded[4:]+"==="))
        self.assertEqual(p,{"url":"https://"+HOST,"tls":"public-ca","token":self.phone_key})
        self.assertNotIn(self.owner_key.encode(),body)
        self.assertEqual(self.begin(self.manifest("a",b"note"),owner)[0],401)
        self.assertEqual(self.request("GET","/api/phone-pairing",headers={**owner,"Host":"different.invalid"})[0],403)
        self.assertEqual(self.request("POST","/logout",b"",{**owner,**self.origin,"Origin":"https://different.invalid"})[0],403)
        status,h,_ = self.request("POST","/logout",b"",{**owner,**self.origin})
        self.assertEqual(status,303)
        self.assertIn("Max-Age=0",h["Set-Cookie"])
        self.assertEqual(self.request("GET","/api/items")[0],401)

    def test_expired_session_and_login_throttles(self):
        owner=self.login()
        with patch.object(cloud_host.time,"time",return_value=time.time()+31*cloud_host.DAY):
            self.assertEqual(self.request("GET","/api/items",headers=owner)[0],401)
        now=time.monotonic()
        self.server.attempts["127.0.0.1"]=(now,12)
        self.assertEqual(self.request("POST","/login",urlencode({"key":self.owner_key}),self.origin)[0],429)
        self.server.attempts.clear()
        self.server.aggregate_start,self.server.aggregate_count=now,180
        self.assertEqual(self.request("POST","/login",urlencode({"key":self.owner_key}),self.origin)[0],429)

    def test_private_browser_form_with_redacted_origin(self):
        body=urlencode({"key":self.owner_key})
        headers={"Content-Type":"application/x-www-form-urlencoded","Origin":"null",
                 "Sec-Fetch-Mode":"navigate","Sec-Fetch-Site":"same-origin"}
        self.assertEqual(self.request("POST","/login",body,headers)[0],303)
        headers["Sec-Fetch-Site"]="cross-site"
        self.assertEqual(self.request("POST","/login",body,headers)[0],403)

    def test_owner_review_and_delete_scopes(self):
        owner=self.login();data=b"Disposable hosted journal"
        manifest=self.manifest("owner-review-delete",data)
        self.begin(manifest);self.put(manifest["id"],data)
        edits=json.dumps(dict(kind="journal",id=manifest["id"],action="add",label="Forge Lexicon"))
        route="/api/review/file"
        auth={**owner,**self.origin,"Content-Type":"application/json"}
        self.assertEqual(self.request("POST",route,edits,{**self.bearer,**self.origin,"Content-Type":"application/json"})[0],401)
        self.assertEqual(self.request("POST",route,edits,{**auth,"Origin":"https://unrelated.invalid"})[0],403)
        self.assertEqual(self.request("POST",route,edits,auth)[0],200)
        self.assertEqual(json.loads(self.request("GET","/api/review?shelf=filed",headers=owner)[2])["total"],1)
        deletion=json.dumps(dict(kind="journal",id=manifest["id"],sha256=manifest["sha256"]))
        self.assertEqual(self.request("POST","/api/archive/delete",deletion,auth)[0],200)
        self.assertEqual(self.request("GET","/api/media/journal/"+manifest["id"],headers=owner)[0],404)
        self.assertEqual(self.begin(manifest)[0],410)
        self.assertEqual(self.request("POST","/api/archive/delete",deletion,auth)[0],200)

    def test_storage_preflight_and_owner_round_trip(self):
        path = "/api/storage-check"
        self.assertEqual(self.request("POST",path,b"",self.origin)[0],401)
        self.assertEqual(self.request("POST",path,b"",{**self.origin,**self.bearer})[0],401)
        owner = self.login()
        self.assertEqual(self.request("POST",path,b"",owner)[0],403)
        self.assertEqual(self.request("POST",path,b"",{**owner,"Origin":"https://elsewhere.invalid"})[0],403)
        status,_,body=self.request("POST",path,b"",{**owner,**self.origin})
        self.assertEqual((status,json.loads(body)),(200,{"verified":True}))
        self.assertEqual([op for op,key in self.bucket.checks],["put","get","get","delete"])
        self.assertEqual(len({key for op,key in self.bucket.checks}),1)
        self.assertEqual(self.bucket.objects,{})
        status,_,body=self.request("GET","/v2/status",headers=self.bearer)
        self.assertEqual(status,200)
        info=json.loads(body)
        self.assertNotIn("root",info)
        self.assertGreater(info["stagingAvailableBytes"],0)
        self.assertEqual(info["remainingArchiveBytes"],cloud_host.MEDIA_CAP)
        self.assertEqual(info["availableBytes"],min(cloud_host.TRANSFER_CAP,cloud_host.MEDIA_CAP))

    def test_original_selected_wav_verified_then_served_to_owner(self):
        stream=io.BytesIO()
        with wave.open(stream,"wb") as wav:
            wav.setparams((1,2,16000,0,"NONE","uncompressed"))
            wav.writeframes(b"\x01\x00"*1600)
        original=stream.getvalue()
        meta=dict(id="selected-1",title="Kept note",category="Manual keep",reason="Owner selected",
                  startedAtMillis=recall_archive.now(),durationMs=100,transcript="Sample utterance",
                  audio="audio/selected-1.wav",sha256=hashlib.sha256(original).hexdigest())
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,"w") as z:
            z.writestr("index.json",json.dumps({"schema":recall_desk.FORMAT,"clips":[meta]}))
            z.writestr(meta["audio"],original)
        archive=stream.getvalue()
        info=dict(self.manifest("selection-revision-1",archive),kind="clip",mediaType="application/zip")
        self.assertEqual(self.begin(info)[0],200)
        status,_,body=self.put(info["id"],archive)
        self.assertEqual(status,200,body)
        self.assertTrue(json.loads(body)["complete"])
        with self.archive.connection() as db:
            self.assertEqual(db.execute("SELECT length(audio) FROM clips WHERE id=?", (meta["id"],)).fetchone()[0],0)
        self.assertIn(original,[data for data,_ in self.bucket.objects.values()])
        path="/api/media/clip/"+meta["id"]
        self.assertEqual(self.request("GET",path,headers=self.bearer)[0],401)
        owner=self.login()
        status,h,body=self.request("GET",path+"?download=1",headers=owner)
        self.assertEqual((status,h["Content-Type"],body),(200,"audio/wav",original))
        self.assertEqual(self.archive.status()["usedBytes"],len(original))

    def test_capture_content_private_and_media_range_budget(self):
        data = b"A synthetic private journal about the river and the evening."
        self.saved("journal-1",data)
        owner = self.login()
        for headers in ({},self.bearer):
            for route in ("/api/items/journal/journal-1","/api/media/journal/journal-1"):
                status,_,out = self.request("GET",route,headers=headers)
                self.assertEqual(status,401)
                self.assertNotIn(data,out)
        self.assertEqual(json.loads(self.request("GET","/api/items/journal/journal-1",headers=owner)[2])["text"],data.decode())
        before = self.archive.status()["monthPlaybackBytes"]
        with patch.object(cloud_host,"TRANSFER_CAP",before+12):
            status,h,b = self.request("HEAD","/api/media/journal/journal-1",headers=owner)
            self.assertEqual((status,h["Content-Length"],b),(200,str(len(data)),b""))
            self.assertEqual(self.archive.status()["monthPlaybackBytes"],before)
            headers={**owner,"Range":"bytes=2-13"}
            status,h,b=self.request("GET","/api/media/journal/journal-1?download=1",headers=headers)
            self.assertEqual((status,b),(206,data[2:14]))
            self.assertEqual(h["Content-Range"],f"bytes 2-13/{len(data)}")
            self.assertIn("attachment",h["Content-Disposition"])
            self.assertEqual(self.bucket.ranges,[(2,12)])
            self.assertEqual(self.request("GET","/api/media/journal/journal-1",headers=headers)[0],429)
            self.assertEqual(self.request("GET","/api/media/journal/journal-1",headers={**owner,"Range":"bytes=999-"})[0],416)
            self.assertEqual(self.archive.status()["monthPlaybackBytes"],before+12)
        self.assertEqual(self.request("GET","/api/items?limit=201",headers=owner)[0],400)

    def test_storage_gate_and_resume_after_unverified_replica(self):
        data=b"Unverified transfers must remain on the phone."
        info=self.manifest("verify-me",data)
        self.archive.limit(len(data)-1)
        self.assertEqual(self.begin(info)[0],507)
        self.archive.limit(1024)
        info_too_large=dict(info,bytes=recall_archive.MAX_ITEMS["journal"]+1)
        self.assertEqual(self.begin(info_too_large)[0],413)
        self.assertEqual(self.begin(info)[0],200)
        self.bucket.available=False
        status,_,body=self.put("verify-me",data)
        self.assertEqual(status,503)
        self.assertNotIn(b'"complete": true',body)
        self.assertEqual(self.begin(info)[0],503)
        self.bucket.available=True
        status,_,b=self.begin(info)
        self.assertEqual(status,200)
        self.assertTrue(json.loads(b)["complete"])
        self.assertIn(data,[obj[0] for obj in self.bucket.objects.values()])
        self.assertEqual(self.archive.status()["monthUploadBytes"],len(data))

    def test_upload_budget_partial_resume_and_utc_persistence(self):
        data=b"123456789"
        info=self.manifest("metered",data)
        self.assertEqual(self.begin(info)[0],200)
        with patch.object(cloud_host,"TRANSFER_CAP",5):
            self.assertEqual(self.put("metered",data[:4])[0],200)
            status,_,b=self.put("metered",data[4:],offset=4)
            self.assertEqual(status,429)
            self.assertNotIn(data,b)
            receipt=json.loads(self.begin(info)[2])
            self.assertEqual((receipt["offset"],receipt["complete"]),(4,False))
            self.assertEqual(self.archive.status()["monthUploadBytes"],4)
        self.assertEqual(self.put("metered",data[4:],offset=4)[0],200)
        again=cloud_host.HostedArchive(recall_desk,self.factory)
        self.assertEqual(again.status()["monthUploadBytes"],len(data))
        with again.connection() as db:
            month = db.execute("SELECT month FROM cloud_transfer_month WHERE kind='upload'").fetchone()[0]
            self.assertEqual(month,datetime.now(timezone.utc).strftime("%Y-%m"))
            db.execute("INSERT INTO cloud_transfer_month VALUES('2000-01','playback',999999)")
        self.assertEqual(again.status()["monthPlaybackBytes"],0)
        with patch.object(cloud_host,"TRANSFER_CAP",9):
            def reserve(_):
                try:
                    again.reserve_transfer("playback",5)
                    return 200
                except recall_archive.Failure as e:
                    return e.status
            with ThreadPoolExecutor(2) as pool:
                self.assertEqual(sorted(pool.map(reserve,[0,1])),[200,429])
            self.assertEqual(again.status()["monthPlaybackBytes"],5)

if __name__ == "__main__":
    unittest.main()
