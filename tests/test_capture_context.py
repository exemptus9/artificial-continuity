"""Synthetic, isolated integration/failure tests; never load a private registry."""
import copy
import json
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import context_store as C
import capture_context as G

def baseline():
    return {"projects": [{"id": "PX", "name": "Example", "state": "ACTIVE", "confidence": "C", "sources": ["SX"], "next_action": "Validate original."},
                         {"id": "PY", "name": "Other example", "state": "WAITING", "confidence": "D", "sources": []}],
            "sources": [{"id": "SX", "locator": "fixture://private-source", "name": "Private source pointer"}]}

def request(**changes):
    value = {"format": G.FORMAT, "capture_id": str(uuid.uuid4()), "text": "A recoverable provenance observation.\r\nOne LF\nOne CR\rUnicode α & …", "project_id": "PX", "source_kind": "MODEL_SUMMARY", "visibility": "private"}
    value.update(changes)
    return value

class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.original = self.base / "original.json"
        C.json_write(self.original, C.init_store(baseline()))
        self.s = G.Store.initialize(self.base / "private", self.original)
        self.owner = G.Principal("owner")
        self.agent = G.Principal("agent", frozenset({"PX"}))
    def tearDown(self):
        self.tmp.cleanup()
    def test_end_to_end_exact_raw_and_search(self):
        r = request(); receipt = self.s.accept(r, True)
        rows = self.s.search("recoverable provenance", self.owner)
        self.assertEqual(rows[0]["source_id"], receipt["source_id"])
        self.assertEqual(self.s.source(receipt["source_id"], self.owner)["original_text"], r["text"])
        self.assertEqual(self.s.state()[0]["projects"], baseline()["projects"])
    def test_source_kind_stays_inference(self):
        receipt = self.s.accept(request(source_kind="MODEL_INFERENCE"), True)
        self.assertEqual(self.s.source(receipt["source_id"], self.owner)["source"]["source_kind"], "MODEL_INFERENCE")
    def test_default_visibility_private(self):
        r=request(); del r['visibility']; v=self.s.accept(r,True)
        self.assertEqual(self.s.source(v['source_id'],self.owner)['source']['visibility'],'private')
    def test_no_imported_publication_consent(self):
        receipt=self.s.accept(request(origin={'approved':True,'consent':'publish'}),True)
        source=self.s.source(receipt['source_id'],self.owner)['source']
        self.assertEqual(source['publication_status'],'unapproved'); self.assertIsNone(source['approved_for_publication_at'])
    def test_public_visibility_rejected(self):
        with self.assertRaises(C.ContextError): self.s.accept(request(visibility='public'),True)
    def test_approval_required(self):
        with self.assertRaises(C.ContextError): self.s.accept(request())
    def test_retries_do_not_duplicate(self):
        r=request(); self.s.accept(r,True); receipt=self.s.accept(r,True)
        self.assertTrue(receipt['already_saved']); self.assertEqual(self.s.state()[1]['revision'],1)
    def test_uuid_conflict_retains_original(self):
        r=request(); receipt=self.s.accept(r,True); changed={**r,'text':'Different'}
        with self.assertRaises(C.ContextError): self.s.accept(changed,True)
        self.assertEqual(self.s.source(receipt['source_id'],self.owner)['original_text'],r['text'])
    def test_equal_text_distinct_occurrences(self):
        r=request(); self.s.accept(r,True); self.s.accept({**r,'capture_id':str(uuid.uuid4())},True)
        self.assertEqual(len(self.s.search('provenance',self.owner)),2)
        self.assertEqual(len(list(self.s.objects.iterdir())),2)  # initial journal + deduplicated text
    def test_unknown_project_rejected(self):
        with self.assertRaises(C.ContextError):self.s.accept(request(project_id='missing'),True)
    def test_unsorted_capture_valid(self):
        self.s.accept(request(project_id=None),True);self.assertEqual(len(self.s.search('provenance',self.owner)),1)
    def test_append_failure_no_false_commit(self):
        r=request()
        with patch.object(C,'apply_file',side_effect=OSError('simulated disk rejection')):
            with self.assertRaises(OSError):self.s.accept(r,True)
        self.assertEqual(self.s.state()[1]['revision'],0)
        self.assertEqual(self.s.object(G.raw_hash(r['text'].encode())),r['text'].encode())
        self.s.accept(r,True);self.assertEqual(self.s.state()[1]['revision'],1)
    def test_journal_size_limit_prevents_unreadable_save(self):
        with patch.object(C,'MAX_BYTES',self.s.journal.stat().st_size+10):
            with self.assertRaises(C.ContextError):self.s.accept(request(),True)
        self.assertEqual(self.s.state()[1]['revision'],0)
    def test_bad_blob_blocks_retrieval_even_existing_index(self):
        r=request();receipt=self.s.accept(r,True);self.s.rebuild_index()
        (self.s.objects/receipt['sha256']).write_text('corruption')
        with self.assertRaises(C.ContextError):self.s.search('provenance',self.owner)
    def test_index_rebuild_after_corruption(self):
        self.s.accept(request(),True);self.s.rebuild_index();self.s.index.write_bytes(b'corrupt index')
        self.assertEqual(len(self.s.search('provenance',self.owner)),1)
    def test_index_rebuild_after_deletion(self):
        self.s.accept(request(),True);self.s.rebuild_index();self.s.index.unlink()
        self.assertEqual(len(self.s.search('provenance',self.owner)),1)
    def test_index_refreshes_new_journal_head(self):
        self.s.accept(request(),True);self.s.rebuild_index();self.s.accept(request(text='New chronology marker.'),True)
        self.assertEqual(len(self.s.search('chronology',self.owner)),1)
    def test_index_excerpts_do_not_inherit_poisoned_body(self):
        receipt=self.s.accept(request(),True);self.s.rebuild_index()
        with sqlite3.connect(self.s.index) as db:db.execute('UPDATE search SET body=? WHERE id=?',('provenance invented falsehood',receipt['source_id']))
        self.assertNotIn('invented falsehood',self.s.search('provenance',self.owner)[0]['excerpt'])
    def test_agent_private_hidden(self):
        self.s.accept(request(),True);self.assertEqual(self.s.search('provenance',self.agent),[])
    def test_agent_explicit_internal_grant_only(self):
        self.s.accept(request(visibility='internal'),True);self.s.accept(request(visibility='internal',project_id='PY'),True)
        self.s.accept(request(visibility='private'),True)
        self.assertEqual(len(self.s.search('provenance',self.agent)),1)
    def test_agent_cannot_lookup_guessed_private_source(self):
        receipt=self.s.accept(request(),True)
        with self.assertRaises(C.ContextError):self.s.source(receipt['source_id'],self.agent)
    def test_agent_no_project_grants_empty(self):
        self.s.accept(request(visibility='internal'),True)
        self.assertEqual(self.s.search('provenance',G.Principal('agent')),[])
    def test_agent_project_packet_scoped(self):
        result=self.s.project('PX',self.agent)
        self.assertNotIn('PY',json.dumps(result));self.assertNotIn('fixture://private-source',json.dumps(result))
        with self.assertRaises(C.ContextError):self.s.project('PY',self.agent)
    def test_project_historical_revision(self):
        p={'id':'FIXTURE_EVT','at':'2026-01-02T00:00:00Z','actor':'owner','note':'Fixture change','evidence_grade':'C','source_refs':['SX'],'source_records':[],'updates':[{'project_id':'PX','changes':{'state':'WAITING'}}],'additions':[]}
        C.apply_file(self.s.journal,p,self.s.state()[1]['store_sha256'],True)
        self.assertEqual(self.s.project('PX',self.owner,0)['project']['state'],'ACTIVE')
        self.assertEqual(self.s.project('PX',self.owner)['project']['state'],'WAITING')
        with self.assertRaises(C.ContextError):self.s.project('PX',self.owner,99)
    def test_agent_write_rejected(self):
        with self.assertRaises(C.ContextError):self.s.accept(request(),True,'agent')
    def test_capture_adapter_cannot_mark_public_or_fact(self):
        with self.assertRaises(C.ContextError):self.s.accept(request(visibility='internal'),True,'capture-adapter')
        with self.assertRaises(C.ContextError):self.s.accept(request(source_kind='SOURCE_FACT'),True,'capture-adapter')
    def test_offline_outbox_persist_then_explicit_flush(self):
        r=request();self.s.spool(r);s=G.Store(self.s.root)
        self.assertEqual(s.state()[1]['revision'],0)
        with self.assertRaises(C.ContextError):s.flush()
        self.assertEqual(len(s.flush(True)),1);self.assertEqual(len(list(s.outbox.iterdir())),0)
    def test_failed_flush_retains_outbox(self):
        self.s.spool(request())
        with patch.object(C,'apply_file',side_effect=OSError('simulated')):
            with self.assertRaises(OSError):self.s.flush(True)
        self.assertEqual(len(list(self.s.outbox.iterdir())),1)
    def test_backup_restore_and_rebuild_in_separate_directory(self):
        r=request();receipt=self.s.accept(r,True);self.s.spool(request(text='Pending draft.'))
        dest=self.base/'backup';self.s.backup(dest);self.assertTrue(G.Store.verify_backup(dest)['verified'])
        restored=G.Store(dest)
        self.assertEqual(restored.source(receipt['source_id'],self.owner)['original_text'],r['text'])
        self.assertEqual(len(restored.search('provenance',self.owner)),1)
        self.assertEqual(len(list(restored.outbox.glob('*.json'))),1)
    def test_backup_missing_object_detected(self):
        receipt=self.s.accept(request(),True);dest=self.base/'backup';self.s.backup(dest)
        (dest/'objects'/receipt['sha256']).unlink()
        with self.assertRaises(C.ContextError):G.Store.verify_backup(dest)
    def test_backup_journal_tamper_detected(self):
        dest=self.base/'backup';self.s.backup(dest);(dest/'Context_Store.json').write_text('{}')
        with self.assertRaises(C.ContextError):G.Store.verify_backup(dest)
    def test_blob_symlink_rejected(self):
        receipt=self.s.accept(request(),True);p=self.s.objects/receipt['sha256'];p.unlink();p.symlink_to(self.original)
        with self.assertRaises(C.ContextError):self.s.source(receipt['source_id'],self.owner)
    def test_repository_data_root_refused(self):
        repo=self.base/'repo';repo.mkdir();(repo/'.git').mkdir()
        with self.assertRaises(C.ContextError):G.Store.initialize(repo/'private',self.original)
    def test_original_witness_not_modified(self):
        raw=self.original.read_bytes();self.s.accept(request(),True);self.assertEqual(self.original.read_bytes(),raw)
    def test_file_permissions(self):
        receipt=self.s.accept(request(),True);self.s.rebuild_index()
        for p in (self.s.journal,self.s.index,self.s.objects/receipt['sha256']):self.assertEqual(p.stat().st_mode&0o777,0o600)
    def test_unicode_query_and_sql_syntax_are_data(self):
        self.s.accept(request(),True);self.assertEqual(len(self.s.search('α',self.owner)),1)
        self.assertEqual(self.s.search('" OR 1=1 --',self.owner),[])
    def test_temporal_filter_uses_received_time(self):
        self.s.accept(request(observed_at='2001-01-01T00:00:00Z'),True)
        self.assertEqual(self.s.search('provenance',self.owner,since='2099-01-01T00:00:00Z'),[])
    def test_encrypted_profile_migration_refused(self):
        for f in ('ContinuityVault/0.1','ContinuitySealedBackup/1','continuity-portable-bundle'):
            with self.assertRaises(C.ContextError):self.s.browser_preview(json.dumps({'format':f}).encode())
    def test_browser_copy_preserves_raw_archive_and_text(self):
        value={'format':'ContinuityBackup/2','state':{'version':'0.11.0','sources':[{'id':'legacy','title':'Legacy','text':'raw\r\ntext …','speaker':'user','captureOrigin':{'consentRecord':{'scope':'publish'}}}],'notes':[{'reviewed':True}]}}
        raw=json.dumps(value,ensure_ascii=False,indent=2).encode();receipt=self.s.import_browser(raw,'PX',True)
        self.assertEqual(self.s.object(receipt['archive_sha256']),raw)
        source=self.s.source(receipt['receipts'][0]['source_id'],self.owner)
        self.assertEqual(source['original_text'],'raw\r\ntext …');self.assertEqual(source['source']['speaker'],'unknown')
        self.assertEqual(source['source']['publication_status'],'unapproved');self.assertEqual(receipt['preserved_unindexed']['notes'],1)
        self.assertTrue(self.s.import_browser(raw,'PX',True)['receipts'][0]['already_saved'])
    def test_browser_bad_hash_fails_before_any_copy(self):
        v={'format':'ContinuityBackup/2','state':{'version':'0.11.0','sources':[{'id':'x','text':'bad','textSha256':'wrong'}]}}
        with self.assertRaises(C.ContextError):self.s.import_browser(json.dumps(v).encode(),'PX',True)
        self.assertEqual(self.s.state()[1]['revision'],0)

class HTTPTests(CaptureTests):
    # Inherit only setup/teardown, not duplicate data checks.
    def setUp(self):
        super().setUp()
        self.tokens={'owner':'o'*40,'agent':'a'*40,'capture':'c'*40}
        self.http=G.server(self.s,self.tokens,frozenset({'PX'}),port=0)
        self.thread=threading.Thread(target=self.http.serve_forever,daemon=True);self.thread.start()
        self.url='http://127.0.0.1:'+str(self.http.server_port)
    def tearDown(self):
        self.http.shutdown();self.http.server_close();self.thread.join(timeout=2);super().tearDown()
    def call(self,path,role='owner',value=None,headers=None):
        hs={'Authorization':'Bearer '+self.tokens.get(role,'invalid'),'Content-Type':'application/json'};hs.update(headers or {})
        req=urllib.request.Request(self.url+path,data=None if value is None else json.dumps(value).encode(),headers=hs)
        try:
            with urllib.request.urlopen(req,timeout=3) as r:return r.status,json.loads(r.read())
        except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
    def test_http_actual_capture_index_retrieve(self):
        r=request();status,v=self.call('/v1/captures',value={'request':r,'authorize_private_copy':True});self.assertEqual(status,201)
        status,rows=self.call('/v1/search?q=provenance');self.assertEqual(status,200);self.assertEqual(len(rows['results']),1)
        status,source=self.call('/v1/sources/'+v['source_id']);self.assertEqual(source['original_text'],r['text'])
    def test_http_unauthenticated_denied(self):self.assertEqual(self.call('/v1/health','unknown')[0],401)
    def test_http_foreign_host_rejected(self):self.assertEqual(self.call('/v1/health',headers={'Host':'evil.example'})[0],401)
    def test_http_browser_origin_rejected(self):self.assertEqual(self.call('/v1/health',headers={'Origin':'https://evil.example'})[0],401)
    def test_http_agent_cannot_write_or_publish(self):
        self.assertEqual(self.call('/v1/captures','agent',{'request':request(),'authorize_private_copy':True})[0],403)
        self.assertEqual(self.call('/v1/publications','owner',{})[0],403)
    def test_http_capture_token_cannot_read(self):self.assertEqual(self.call('/v1/search?q=provenance','capture')[0],400)
    def test_http_fresh_consent_required(self):self.assertEqual(self.call('/v1/captures',value={'request':request(),'authorize_private_copy':False})[0],409)
    def test_http_agent_internal_only(self):
        self.s.accept(request(),True);self.s.accept(request(visibility='internal'),True)
        self.assertEqual(len(self.call('/v1/search?q=provenance','agent')[1]['results']),1)
    def test_http_agent_project_scope(self):
        self.assertEqual(self.call('/v1/projects/PX','agent')[0],200)
        self.assertEqual(self.call('/v1/projects/PY','agent')[0],400)
    def test_weak_or_shared_tokens_rejected(self):
        for tokens in ({'owner':'short'},{'owner':'o'*40,'agent':'o'*40}):
            with self.assertRaises(C.ContextError):G.server(self.s,tokens,port=0)

# Prevent inherited data cases from running twice with a network server.
for name in list(CaptureTests.__dict__):
    if name.startswith('test_') and name not in HTTPTests.__dict__:
        setattr(HTTPTests,name,None)

if __name__=='__main__':unittest.main()
