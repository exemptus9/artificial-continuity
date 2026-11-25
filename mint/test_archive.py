from contextlib import ExitStack,closing
from datetime import datetime,timezone
import base64
import hashlib
import http.client
import io
import json
from pathlib import Path
import sqlite3
import ssl
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.parse import urlsplit
import zipfile
import recall_archive as store
import recall_desk as desk
import recall_web as web
from test_recall_desk import record,wav

class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name)
        self.patch=patch.multiple(desk,ROOT=root,DB=root/"clips.sqlite3",APP=root/"recall_desk.py")
        self.patch.start();self.addCleanup(self.patch.stop)
        self.arc=desk.archive();self.root=root
    def upload(self,id,kind,data,*,duration=1000,**kw):
        return dict(id=id,kind=kind,startedAtMillis=store.now()-15000,durationMs=duration,
                    bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),**kw)
    def server(self,instance):
        thread=threading.Thread(target=instance.serve_forever,daemon=True);thread.start()
        def end(): instance.shutdown();thread.join();instance.server_close()
        self.addCleanup(end);return instance
    def test_capacity_durable_resume_rejection_and_expiry(self):
        content=b"private transcript\n"*12000
        meta=self.upload("journal-1","journal",content)
        self.arc.limit(len(content)+10)
        self.assertEqual(self.arc.begin(meta)["offset"],0)
        self.assertEqual(self.arc.status()["reservedBytes"],len(content))
        with self.assertRaisesRegex(store.Failure,"allocated"):self.arc.begin(self.upload("new","journal",b"e"*11))
        with self.assertRaisesRegex(store.Failure,"different metadata"):self.arc.begin(dict(meta,title="new"))
        self.arc.put(meta["id"],0,content[:1200])
        restarted=desk.archive()
        self.assertEqual(restarted.begin(meta)["offset"],1200)
        with self.assertRaisesRegex(store.Failure,"Offset"):restarted.put(meta["id"],0,b"a")
        path=self.arc.files/"journal-1"
        with path.open("ab") as output:output.write(b"left over after interrupted process")
        with self.assertRaisesRegex(store.Failure,"Checksum"):
            restarted.put(meta["id"],1200,bytes(len(content)-1200))
        self.assertEqual(restarted.begin(meta)["offset"],0)
        ack=restarted.put(meta["id"],0,content)
        self.assertEqual(ack,dict(ok=True,id="journal-1",bytes=len(content),offset=len(content),complete=True,sha256=meta["sha256"]))
        self.assertEqual(ack,restarted.begin(meta));self.assertEqual(restarted.status()["usedBytes"],len(content))
        self.assertEqual(restarted.status()["reservedBytes"],0)
        deadline=store.expiry(meta["startedAtMillis"]+meta["durationMs"])
        self.assertEqual(restarted.trim(deadline-1),0);self.assertTrue(path.exists())
        self.assertEqual(restarted.trim(deadline),1);self.assertFalse(path.exists())
        self.assertEqual(restarted.status()["counts"]["journal"],0)
        ms=lambda text:int(datetime.fromisoformat(text).replace(tzinfo=timezone.utc).timestamp()*1000)
        self.assertEqual(store.expiry(ms("2024-02-29T12:30:31")),ms("2031-02-28T12:30:31"))
    def test_legacy_clip_provenance_and_resumable_zip(self):
        data=wav(5);src=record("child-clip",data);src.update(parentId="parent-clip",startSample=225,endSample=1825)
        output=io.BytesIO()
        with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as z:
            z.writestr("index.json",json.dumps({"schema":desk.FORMAT,"clips":[src]}));z.writestr(src["audio"],data)
        packed=output.getvalue()
        meta=self.upload("child-revision-1","clip",packed)
        self.arc.begin(meta)
        self.assertTrue(self.arc.put(meta["id"],0,packed)["complete"])
        self.assertEqual(self.arc.begin(meta)["offset"],len(packed))
        self.assertFalse((self.arc.files/meta["id"]).exists())
        self.assertEqual(desk.detail(src["id"])["parentId"],"parent-clip")
        item=web.lookup(self.arc,"clip","child-clip")
        self.assertEqual((item["parentId"],item["endSample"]-item["startSample"]),("parent-clip",1600))
    def test_creative_patterns_use_saved_labels_and_union_of_audio(self):
        first=(store.now()//store.DAY-3)*store.DAY
        def add_clip(db,uid,tag,at,duration):
            # Deliberately misleading synthetic content must have no effect on category counts.
            db.execute("INSERT INTO clips VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                       (uid,at,duration,0,"0"*64,b"", "Poem line","Forge word",tag,"Manual keep","{}","none"))
        with self.arc.connection() as db:
            add_clip(db,"a","Forge word + Poem line",first+1000,4000)
            add_clip(db,"b","  forge WORD + Forge word + Manual keep  ",first+2000,4000)
            add_clip(db,"c","Missed moment + Project decision + Connection",first+3000,1000)
            add_clip(db,"d","Excerpt + Future label",first+3500,20)
            add_clip(db,"outside","Forge word",first-3000,1000)
            add_clip(db,"day-two","",first+store.DAY+1000,7000)
        o=web.overview(self.arc,first,first+store.DAY*2,0)
        grouped={g["key"]:(g["count"],g["latestClipId"]) for g in o["clipPatterns"]}
        self.assertEqual(grouped,{"coined":(2,"b"),"verse":(1,"a"),"manual":(1,"b"),"missed":(1,"c"),
                    "decision":(1,"c"),"connection":(1,"c"),"excerpt":(1,"d"),"other":(2,"day-two")})
        self.assertEqual((o["clipCount"],o["capturedMs"]),(5,12000))
        self.assertEqual(o["busiestDay"],dict(date=o["buckets"][1]["date"],capturedMs=7000,tiedDays=1))
        with self.arc.connection() as db:db.execute("UPDATE clips SET duration=5000 WHERE id='day-two'")
        tied=web.overview(self.arc,first,first+2*store.DAY,0)
        self.assertEqual(tied["busiestDay"],dict(date=tied["buckets"][1]["date"],capturedMs=5000,tiedDays=2))
        empty=web.overview(self.arc,first-2*store.DAY,first-store.DAY,0)
        self.assertIsNone(empty["busiestDay"])
        self.assertFalse(any(g["count"] for g in empty["clipPatterns"]))
    def test_raw_adts_silence_validated_with_decoded_duration(self):
        path=self.root/"vbr.aac"
        subprocess.run(["ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=440:duration=2",
                    "-af","apad=pad_dur=118","-ar","16000","-ac","1","-c:a","aac","-b:a","32k","-f","adts",str(path)],check=True)
        data=path.read_bytes()
        meta=self.upload("silence-vbr","segment",data,duration=120000,mediaType="audio/aac")
        self.arc.begin(meta)
        self.assertTrue(self.arc.put(meta["id"],0,data)["complete"])
        stream,indexed_bytes=web.seekable_preview(self.arc,meta["sha256"],len(data),lambda:path.open("rb"))
        with stream:
            preview=Path(stream.name)
            self.assertEqual(preview.stat().st_size,indexed_bytes)
            duration_data=subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration","-of","json",str(preview)])
            self.assertAlmostEqual(float(json.loads(duration_data)["format"]["duration"]),120,delta=.25)
            late=subprocess.check_output(["ffmpeg","-v","error","-ss","110","-i",str(preview),"-t","1","-ar","16000","-ac","1","-f","s16le","-"])
            self.assertEqual(len(late),32000)
        same,size=web.seekable_preview(self.arc,meta["sha256"],len(data),lambda:self.fail("cache reread the original"))
        same.close();self.assertEqual(size,indexed_bytes)
        self.assertEqual(hashlib.sha256((self.arc.root/"archive-files/silence-vbr").read_bytes()).hexdigest(),meta["sha256"])
        short=dict(meta,id="duration-mismatch",durationMs=10000)
        self.arc.begin(short)
        with self.assertRaisesRegex(store.Failure,"duration validation"):
            self.arc.put(short["id"],0,data)
    def test_real_tls_status_media_interrupted_upload_and_browser(self):
        file=self.root/"tone.m4a"
        subprocess.run(["ffmpeg","-loglevel","error","-f","lavfi","-i","sine=frequency=320:duration=2",
                    "-ar","16000","-ac","1","-c:a","aac",str(file)],check=True)
        data=file.read_bytes();meta=self.upload("segment-a","segment",data,duration=2000,sessionId="session1")
        server,code=desk.listener("127.0.0.1",0,test_loopback=True);self.server(server)
        spec=json.loads(base64.urlsafe_b64decode(code[4:]+"==="))
        def transfer(method,path,body=None,header=None,token=None):
            conn=http.client.HTTPSConnection("127.0.0.1",server.server_port,context=ssl._create_unverified_context(),timeout=15)
            try:
                conn.connect();self.assertEqual(spec["pin"],hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest())
                headers={"Authorization":"Bearer "+(spec["token"] if token is None else token),**(header or {})}
                conn.request(method,path,body=body,headers=headers);r=conn.getresponse();return r.status,json.loads(r.read())
            finally:conn.close()
        self.assertEqual(transfer("GET","/v2/status",token="wrong")[0],401)
        self.assertEqual(transfer("GET","/v2/status")[1]["retentionYears"],7)
        manifest=lambda:transfer("POST","/v2/uploads",json.dumps(meta),{"Content-Type":"application/json"})
        self.assertFalse(manifest()[1]["complete"])
        def put(offset,chunk):return transfer("PUT","/v2/uploads/"+meta["id"],chunk,{"Content-Type":"application/octet-stream","X-Upload-Offset":str(offset)})
        self.assertEqual(put(0,data[:512])[1]["offset"],512)
        self.assertEqual(put(0,data[:512])[0],409)
        self.assertEqual(manifest()[1]["offset"],512)
        code,receipt=put(512,data[512:]);self.assertEqual(code,200);self.assertTrue(receipt["complete"])
        portal=self.server(web.web_server(self.arc,Path(__file__).parent/"web",0));port=portal.server_port
        def get(path,**headers):
            with closing(http.client.HTTPConnection("127.0.0.1",port,timeout=5)) as con:
                con.request("GET",path,headers=headers);response=con.getresponse()
                return response.status,dict(response.getheaders()),response.read()
        self.assertEqual(get("/api/status")[0],401)
        owner=urlsplit(web.grant(self.root,port)).path
        code,h,_=get(owner);self.assertEqual(code,303)
        cookie=h["Set-Cookie"].split(";")[0];self.assertIn("HttpOnly",h["Set-Cookie"]);self.assertEqual(get(owner)[0],401)
        self.assertEqual(get("/api/status",Cookie=cookie,Host="public.example.org")[0],403)
        code,h,body=get("/api/media/segment/segment-a",Cookie=cookie,Range="bytes=18-54")
        self.assertEqual((code,h["Content-Range"],body),(206,f"bytes 18-54/{len(data)}",data[18:55]))
        self.assertEqual(get("/api/media/segment/segment-a",Cookie=cookie,Range="bytes=-18")[2],data[-18:])
        self.assertEqual(get("/api/media/segment/segment-a",Cookie=cookie,Range="bytes=999999999-")[0],416)
        code,h,body=get("/api/media/segment/segment-a?download=1",Cookie=cookie)
        self.assertEqual(data,body);self.assertIn("attachment",h["Content-Disposition"])
        listing=json.loads(get("/api/items?kind=segment",Cookie=cookie)[2]);self.assertEqual(listing["total"],1)
        overview=json.loads(get(f"/api/overview?from={meta['startedAtMillis']-5000}&to={store.now()}",Cookie=cookie)[2])
        self.assertEqual(overview["capturedMs"],2000)
        self.assertIsNone(overview["gapCount"])
        bad=self.upload("malformed","segment",b"arbitrary bytes",duration=1000)
        self.arc.begin(bad)
        with self.assertRaisesRegex(store.Failure,"validation"):self.arc.put("malformed",0,b"arbitrary bytes")
        self.assertEqual(self.arc.status()["counts"]["segment"],1)
