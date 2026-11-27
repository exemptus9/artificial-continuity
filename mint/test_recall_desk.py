import contextlib
import base64
import http.client
import ssl
import threading
import hashlib
import io
import json
import sqlite3
import tempfile
import unittest
import wave
import zipfile
from pathlib import Path
from unittest.mock import patch
import recall_desk as desk

def wav(value):
    out=io.BytesIO()
    with wave.open(out,"wb") as f:
        f.setparams((1,2,16000,0,"NONE","not compressed"))
        f.writeframes(bytes([value,0])*1600)
    return out.getvalue()

def record(uid, audio, text="I call that a hyperboliever."):
    return dict(id=uid,title="Definition",startedAtMillis=1795622400000,durationMs=100,
                category="coinage",reason="Self-definition",transcript=text,
                feedback=0,audio="audio/"+uid+".wav",sha256=hashlib.sha256(audio).hexdigest())

class TestCaptureDesk(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.root=Path(temp.name)
        p=patch.multiple(desk,ROOT=self.root,DB=self.root/"clips.sqlite3",APP=self.root/"recall_desk.py")
        p.start();self.addCleanup(p.stop)
        self.audio=wav(8);self.clip=record("capture-1",self.audio)
    def path(self,*pairs):
        output=self.root/"phone.zip"
        with zipfile.ZipFile(output,"w") as z:
            z.writestr("index.json",json.dumps(dict(schema=desk.FORMAT,clips=[c for c,_ in pairs])))
            for c,a in pairs: z.writestr(c["audio"],a)
        return output
    def test_import_original_and_restore(self):
        pair=(self.clip,self.audio)
        self.assertEqual(desk.import_zip(self.path(pair))["new"],1)
        self.assertEqual(desk.import_zip(self.path(pair))["unchanged"],1)
        with sqlite3.connect(desk.DB) as db:
            self.assertEqual(db.execute("SELECT audio FROM clips").fetchone()[0],self.audio)
        self.assertEqual(desk.query("hyperboliever")[0]["id"],"capture-1")
        self.assertEqual(desk.query("ZZZ"),[])
        self.assertEqual(desk.mcp_call({"id":"capture-1"})["sha"],self.clip["sha256"])
        self.assertIn("Source capture: capture-1",desk.evidence_text("capture-1"))
        rev=dict(self.clip,feedback=1)
        self.assertEqual(desk.import_zip(self.path((rev,self.audio)))["new_metadata_versions"],1)
        self.assertEqual(desk.query()[0]["feedback"],"1")
        self.assertEqual(desk.import_zip(self.path(pair))["unchanged"],1)
        self.assertEqual(desk.query()[0]["feedback"],"1")
        with sqlite3.connect(desk.DB) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM observations").fetchone()[0],2)
    def test_marker_wording_is_immutable_across_old_and_new_exports(self):
        original=dict(self.clip,category="Poem line",cueText="copper flickers purple")
        old=dict(original);del old["cueText"]
        desk.import_zip(self.path((old,self.audio)))
        desk.import_zip(self.path((original,self.audio)))
        legacy_edit=dict(old,note="Later note")
        desk.import_zip(self.path((legacy_edit,self.audio)))
        with sqlite3.connect(desk.DB) as db:
            stored,body=db.execute("SELECT metadata,audio FROM clips").fetchone()
            self.assertEqual(json.loads(stored)["cueText"],original["cueText"])
            self.assertEqual(json.loads(stored)["note"],"Later note")
            self.assertEqual(body,self.audio)
            self.assertTrue(any("cueText" not in json.loads(row[0]) for row in db.execute("SELECT metadata FROM observations")))
        for cue in ["different passage",None,"x"*241]:
            with self.assertRaises(desk.Issue):
                desk.import_zip(self.path((dict(original,cueText=cue),self.audio)))
        with sqlite3.connect(desk.DB) as db:
            self.assertEqual(json.loads(db.execute("SELECT metadata FROM clips").fetchone()[0])["cueText"],original["cueText"])

    def test_atomic_import_and_conflicts(self):
        other=record("capture-2",self.audio)
        with self.assertRaises(desk.Issue):
            desk.import_zip(self.path((self.clip,self.audio),(other,wav(3))))
        self.assertEqual(desk.query(),[])
        desk.import_zip(self.path((self.clip,self.audio)))
        newer=wav(3)
        with self.assertRaises(desk.Issue):
            desk.import_zip(self.path((dict(self.clip,sha256=hashlib.sha256(newer).hexdigest()),newer)))
        self.assertEqual(desk.detail("capture-1")["sha"], self.clip["sha256"])
    def test_paired_https_validates_and_acknowledges(self):
        server,code=desk.listener("127.0.0.1",port=0,test_loopback=True)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        def close():
            server.shutdown();server.server_close();thread.join()
        self.addCleanup(close)
        config=json.loads(base64.urlsafe_b64decode(code[4:]+"==="))
        archive=self.path((self.clip,self.audio)).read_bytes()
        def send(data,token):
            conn=http.client.HTTPSConnection("127.0.0.1",server.server_port,context=ssl._create_unverified_context(),timeout=4)
            try:
                conn.connect()
                self.assertEqual(config["pin"],hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest())
                conn.request("POST","/v1/import",body=data,headers={"Authorization":"Bearer "+token,"Content-Type":"application/zip"})
                res=conn.getresponse()
                return res.status,json.loads(res.read())
            finally: conn.close()
        self.assertEqual(send(archive,"bad")[0],401)
        self.assertEqual(send(b"not a ZIP",config["token"])[0],422)
        self.assertEqual(desk.query(),[])
        for i in range(2):
            status,ack=send(archive,config["token"])
            self.assertEqual(status,200)
            self.assertEqual(ack,dict(ok=True,id="capture-1",sha256=hashlib.sha256(archive).hexdigest(),audioSha256=self.clip["sha256"]))
        self.assertEqual(len(desk.query()),1)
        corrected=dict(self.clip,correction="Corrected spelling: Hyperboliever.",note="Do not replace the audio.")
        status,ack=send(self.path((corrected,self.audio)).read_bytes(),config["token"])
        self.assertEqual(status,200)
        self.assertEqual(ack["audioSha256"],self.clip["sha256"])
        self.assertEqual(desk.mcp_call({"query":"Corrected spelling"})["captures"][0]["correction"],corrected["correction"])
        self.assertEqual(desk.mcp_call({"id":"capture-1"})["transcript"],self.clip["transcript"])
        unauthorized=self.path((self.clip,self.audio),(record("second",self.audio),self.audio)).read_bytes()
        self.assertEqual(send(unauthorized,config["token"])[0],422)
        self.assertEqual(len(desk.query()),1)
    def test_concept_search_and_owner_edits(self):
        revised=dict(self.clip,correction="Hyperboliever",note="Coined at lunch; final spelling.")
        desk.import_zip(self.path((revised,self.audio)))
        self.assertEqual(desk.mcp_call({"query":"What was my neologism for taking exaggerations literally?"})["captures"][0]["id"],"capture-1")
        self.assertEqual(desk.query("lunch")[0]["correction"],"Hyperboliever")
        self.assertIn("Owner correction",desk.evidence_text("capture-1"))
    def test_opt_in_login_entry(self):
        with patch.object(desk,"AUTOSTART",self.root/"autostart/total-recall-desk.desktop"):
            self.assertFalse(desk.login_preference())
            self.assertTrue(desk.login_preference("192.168.1.77"))
            self.assertIn("desk --receive-at 192.168.1.77",desk.AUTOSTART.read_text())
            self.assertNotIn("TR1.",desk.AUTOSTART.read_text())
            self.assertTrue(desk.login_preference())
            self.assertFalse(desk.login_preference(""))
            self.assertFalse(desk.AUTOSTART.exists())
            desk.AUTOSTART.write_text("Third party")
            with self.assertRaises(desk.Issue): desk.login_preference("192.168.1.77")
            self.assertEqual(desk.AUTOSTART.read_text(),"Third party")
    def test_mcp_read_only(self):
        desk.import_zip(self.path((self.clip,self.audio)))
        stream=io.StringIO()
        reqs=[(1,"initialize",{}),(2,"tools/list",{}),(3,"tools/call",dict(name=desk.TOOL,arguments=dict(query="coinage"))),
              (4,"tools/call",dict(name="delete",arguments=dict(id="capture-1")))]
        lines="".join(json.dumps(dict(jsonrpc="2.0",id=i,method=m,params=p))+"\n" for i,m,p in reqs)
        with patch("sys.stdin",io.StringIO(lines)),contextlib.redirect_stdout(stream):
            desk.serve()
        data=[json.loads(s) for s in stream.getvalue().splitlines()]
        self.assertEqual(data[1]["result"]["tools"][0]["name"],"find_capture")
        self.assertEqual(json.loads(data[2]["result"]["content"][0]["text"])["captures"][0]["id"],"capture-1")
        self.assertEqual(data[3]["error"]["code"],-32601)
    def test_unsafe_or_bad_inputs(self):
        for bad in (dict(self.clip,id="../escape"),dict(self.clip,audio="fake"),dict(self.clip,durationMs=500)):
            with self.assertRaises(desk.Issue): desk.import_zip(self.path((bad,self.audio)))
        self.assertEqual(desk.query(),[])

if __name__=="__main__": unittest.main()
