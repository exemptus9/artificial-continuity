"""Native Java-generated synthetic fixtures through real Continuity implementation."""
import copy,json,sys,tempfile,threading,unittest,uuid,urllib.error
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
import context_store as C
import capture_context as G
import context_client as CLIENT
import phone_archive as P
FIX=Path(__file__).parent/'fixtures'/'phone'

class PhoneArchiveTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
  baseline={'projects':[{'id':'P29','name':'Synthetic Total Recall','state':'ACTIVE','confidence':'C','sources':[]},{'id':'P28','name':'Synthetic Continuity','state':'ACTIVE','confidence':'C','sources':[]}],'sources':[]}
  self.original=self.root/'seed.json';C.json_write(self.original,C.init_store(baseline));self.store=G.Store.initialize(self.root/'core',self.original)
  self.bundle=P.decrypt((FIX/'synthetic-phone.trbackup.json').read_bytes(),'SYNTHETIC passphrase 🧠');self.owner=G.Principal('owner')
 def tearDown(self):self.tmp.cleanup()
 def ingest(self,bundle=None):return P.ingest(self.store,bundle or self.bundle,approved=True,non_sensitive=True)
 def rehash(self,b):b['payload_sha256']=C.digest(b['payload']);return b
 def test_java_python_encryption_and_canonical_interop(self):
  self.assertEqual(P.preview(self.bundle)['eligible_records'],3)
  self.assertEqual(self.bundle,P.parse((FIX/'synthetic-phone.json').read_bytes()))
 def test_integration_exact_text_ids_and_original_files(self):
  result=self.ingest()
  for receipt,row in zip(result['receipts'],self.bundle['payload']['records']):
   e=row['record'];got=self.store.source(receipt['source_id'],self.owner)
   self.assertEqual(got['source']['capture_id'],e['id'])
   self.assertEqual(C.parse(self.store.object(got['source']['origin']['event_sha256'])),e)
   if e['text'].strip():self.assertEqual(got['original_text'],e['text'])
   for a in e['attachments']:self.assertEqual(G.raw_hash(self.store.object(a['sha256'])),a['sha256'])
 def test_repeated_export_ingestion_stable_identity(self):
  self.ingest();b=copy.deepcopy(self.bundle);b['payload']['exported_ms']+=1;self.rehash(b)
  result=self.ingest(b);self.assertTrue(all(r['already_saved'] for r in result['receipts']));self.assertEqual(self.store.state()[1]['revision'],3)
 def test_same_text_intentional_duplicate_is_new_source(self):
  self.ingest();b=copy.deepcopy(self.bundle);r=b['payload']['records'][0];r['record']['id']=str(uuid.uuid4());r['record_sha256']=C.digest(r['record']);self.rehash(b);self.ingest(b);self.assertEqual(self.store.state()[1]['revision'],4)
 def test_import_never_mutates_project_state(self):
  before=self.store.state()[0]['projects'];self.ingest();self.assertEqual(before,self.store.state()[0]['projects'])
 def test_sensitive_copy_blocked(self):
  with self.assertRaises(C.ContextError):P.ingest(self.store,self.bundle,approved=True,non_sensitive=False)
  self.assertEqual(self.store.state()[1]['revision'],0)
 def test_wrong_password_rejected(self):
  with self.assertRaises(Exception):P.decrypt((FIX/'synthetic-phone.trbackup.json').read_bytes(),'wrong')
 def test_corrupt_archive_rejected_before_write(self):
  b=copy.deepcopy(self.bundle);b['payload']['records'][0]['record']['text']='changed'
  with self.assertRaises(C.ContextError):self.ingest(b)
  self.assertEqual(self.store.state()[1]['revision'],0)
 def test_corrupt_attachment_rejected(self):
  b=copy.deepcopy(self.bundle);h=next(iter(b['payload']['blobs']));b['payload']['blobs'][h]='AAAA';self.rehash(b)
  with self.assertRaises(C.ContextError):self.ingest(b)
 def test_path_traversal_rejected(self):
  b=copy.deepcopy(self.bundle);b['payload']['blobs']['../path']='AA==';self.rehash(b)
  with self.assertRaises(C.ContextError):self.ingest(b)
 def test_duplicate_record_id_rejected(self):
  b=copy.deepcopy(self.bundle);b['payload']['records'].append(b['payload']['records'][0]);self.rehash(b)
  with self.assertRaises(C.ContextError):self.ingest(b)
 def test_unknown_schema_rejected(self):
  b=copy.deepcopy(self.bundle);b['payload']['format']='TotalRecallArchive/99';self.rehash(b)
  with self.assertRaises(C.ContextError):self.ingest(b)
 def test_automatic_payload_filtered_before_persistence(self):
  b=copy.deepcopy(self.bundle);r=next(x for x in b['payload']['records'] if x['record']['source_type']=='auto.usage');r['record']['metadata']['notification_text']='SYNTHETIC FORBIDDEN';r['record_sha256']=C.digest(r['record']);self.rehash(b)
  with self.assertRaises(C.ContextError):self.ingest(b)
  self.assertFalse(any(b'SYNTHETIC FORBIDDEN' in p.read_bytes() for p in self.store.root.rglob('*') if p.is_file()))
 def test_real_context_http_owner_retrieval_and_agent_denial(self):
  result=self.ingest();server=G.server(self.store,{'owner':'o'*40,'agent':'a'*40},frozenset({'P29'}),port=0);t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
  try:
   port=server.server_port;row=next(x for x in self.bundle['payload']['records'] if x['record']['source_type']=='note');sid='CAPTURE.'+row['record']['id']
   data=CLIENT.retrieve('source',sid,port=port,token='o'*40)
   self.assertEqual(data['original_text'],row['record']['text'])
   self.assertEqual(CLIENT.retrieve('search','SYNTHETIC',project='P29',port=port,token='a'*40)['results'],[])
   with self.assertRaises(urllib.error.HTTPError):CLIENT.retrieve('source',sid,port=port,token='a'*40)
   with self.assertRaises(urllib.error.HTTPError):CLIENT.retrieve('source',sid,port=port,token='x'*40)
   with self.assertRaises(urllib.error.HTTPError):CLIENT.retrieve('project','P28',port=port,token='a'*40)
  finally:server.shutdown();server.server_close();t.join()
 def test_hostile_text_remains_inert_source(self):
  self.ingest();rows=self.store.search('disclose secrets',self.owner,'P29');self.assertEqual(len(rows),1);self.assertEqual(rows[0]['trust'],'untrusted-source-data')
 def test_clean_backup_restore_and_exact_originals(self):
  result=self.ingest();dest=self.root/'restored';self.store.backup(dest);self.assertTrue(G.Store.verify_backup(dest)['verified']);restored=G.Store(dest)
  for r in result['receipts']:self.assertEqual(restored.source(r['source_id'],self.owner),self.store.source(r['source_id'],self.owner))
  for row in self.bundle['payload']['records']:
   e=row['record'];self.assertEqual(C.parse(restored.object(C.digest(e))),e)
  self.assertTrue((dest/'phone-deletions.json').exists())
 def test_deletion_ledger_blocks_reimport_of_old_export(self):
  b=copy.deepcopy(self.bundle);id_=b['payload']['records'][0]['record']['id'];b['payload']['deleted_ids']=[id_];self.rehash(b);self.ingest(b);self.ingest();self.assertEqual(self.store.state()[1]['revision'],2)
 def test_destination_cannot_claim_remote_deletion(self):
  self.ingest();b=copy.deepcopy(self.bundle);b['payload']['deleted_ids']=[b['payload']['records'][0]['record']['id']];self.rehash(b)
  with self.assertRaisesRegex(C.ContextError,'Deletion intersects'):self.ingest(b)
 def test_json_duplicate_keys_fail(self):
  with self.assertRaises(C.ContextError):P.parse(b'{"x":1,"x":2}')
 def test_transfer_journal_failure_is_retryable(self):
  from unittest.mock import patch
  with patch.object(C,'apply_file',side_effect=OSError('synthetic full disk')):
   with self.assertRaises(OSError):self.ingest()
  self.assertEqual(self.store.state()[1]['revision'],0);self.ingest();self.assertEqual(self.store.state()[1]['revision'],3)
if __name__=='__main__':unittest.main()
