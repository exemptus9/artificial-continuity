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
 def with_review(self):
  b=copy.deepcopy(self.bundle)
  source=next(row['record'] for row in b['payload']['records'] if row['record']['source_type']=='note')
  review=copy.deepcopy(source);review['id']=str(uuid.uuid4());review['capture_method']='user-review';review['app']='memory-workbench'
  review['text']='SYNTHETIC owner interpretation: this was a hypothesis, not a confirmed fact.\nKeep e\u0301 / é exactly.'
  review['text_sha256']=G.raw_hash(review['text'].encode());review['attachments']=[]
  for prefix in ['event','observed','ingested']:
   review[prefix+'_ms']=source['event_ms']+1000
   review[prefix+'_at']=P.datetime.fromtimestamp(review[prefix+'_ms']/1000,P.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00','Z')
  review['metadata']={'source_id':source['id'],'source_sha256':C.digest(source),'review_role':'user_interpretation',
                      'epistemic_status':'inference','review_sequence':1,'original_unchanged':True}
  b['payload']['records'].append({'record':review,'record_sha256':C.digest(review),
                                 'annotation':{'project_id':'P29','important':True,'reviewed':True}})
  self.rehash(b)
  return b,source,review
 def rehash_records(self,b):
  for row in b['payload']['records']:row['record_sha256']=C.digest(row['record'])
  return self.rehash(b)
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
 def test_repeated_transfer_preserves_deletion_ledger_bytes(self):
  b=copy.deepcopy(self.bundle);b['payload']['delete_ranges']=[{'start_ms':0,'end_ms':1}];self.rehash(b)
  self.ingest(b);before=(self.store.root/'phone-deletions.json').read_bytes()
  self.ingest(b)
  self.assertEqual((self.store.root/'phone-deletions.json').read_bytes(),before)
 def test_invalid_existing_deletion_ledger_fails_before_new_capture(self):
  C.json_write(self.store.root/'phone-deletions.json',{'deleted_ids':[], 'delete_ranges':[{'start_ms':2,'end_ms':1}]})
  before=self.store.journal.read_bytes()
  with self.assertRaises(C.ContextError):self.ingest()
  self.assertEqual(self.store.journal.read_bytes(),before)
 def test_json_duplicate_keys_fail(self):
  with self.assertRaises(C.ContextError):P.parse(b'{"x":1,"x":2}')
 def test_transfer_journal_failure_is_retryable(self):
  from unittest.mock import patch
  with patch.object(C,'apply_file',side_effect=OSError('synthetic full disk')):
   with self.assertRaises(OSError):self.ingest()
  self.assertEqual(self.store.state()[1]['revision'],0);self.ingest();self.assertEqual(self.store.state()[1]['revision'],3)
 def test_owner_review_exact_text_and_provenance_roundtrip(self):
  b,source,review=self.with_review();before=self.store.state()[0]['projects'];result=self.ingest(b)
  imported=self.store.source('CAPTURE.'+review['id'],self.owner)
  self.assertEqual(imported['original_text'],review['text'])
  self.assertEqual(imported['source']['source_kind'],'UNKNOWN')
  self.assertEqual(imported['source']['speaker'],'unknown')
  self.assertEqual(imported['source']['origin']['text_role'],'owner-interpretation')
  self.assertEqual(imported['source']['origin']['review'],{
   'source_event_id':source['id'],'source_record_sha256':C.digest(source),'source_event_ms':source['event_ms'],
   'epistemic_status':'inference','role':'user_interpretation','classification_authority':'owner-supplied-unverified'})
  self.assertEqual(C.parse(self.store.object(imported['source']['origin']['event_sha256'])),review)
  self.assertEqual(self.store.state()[0]['projects'],before)
  self.assertEqual(result['omitted_review_records'],0)
  self.assertTrue(all(r['already_saved'] for r in self.ingest(b)['receipts']))
  dest=self.root/'review-restored';self.store.backup(dest);self.assertTrue(G.Store.verify_backup(dest)['verified'])
  self.assertEqual(G.Store(dest).source('CAPTURE.'+review['id'],self.owner),imported)
 def test_owner_review_missing_original_is_disclosed_and_omitted(self):
  b,source,review=self.with_review();b['payload']['records']=[r for r in b['payload']['records'] if r['record']['id']!=source['id']];self.rehash(b)
  preview=P.preview(b);self.assertEqual(preview['eligible_records'],2);self.assertEqual(preview['omitted_review_records'],1)
  result=self.ingest(b);self.assertEqual(result['omitted_review_records'],1);self.assertEqual(len(result['receipts']),2)
  self.assertFalse(any(b'SYNTHETIC owner interpretation' in p.read_bytes() for p in self.store.root.rglob('*') if p.is_file()))
 def test_owner_review_bad_source_hash_rejects_before_any_write(self):
  b,source,review=self.with_review();review['metadata']['source_sha256']='0'*64;self.rehash_records(b)
  before=self.store.journal.read_bytes()
  with self.assertRaisesRegex(C.ContextError,'hash mismatch'):self.ingest(b)
  self.assertEqual(self.store.journal.read_bytes(),before)
  self.assertFalse((self.store.root/'phone-deletions.json').exists())
 def test_owner_review_unknown_status_and_role_are_rejected(self):
  for field,value in [('epistemic_status','verified_fact'),('review_role','system_instruction')]:
   b,source,review=self.with_review();review['metadata'][field]=value;self.rehash_records(b)
   with self.assertRaises(C.ContextError):self.ingest(b)
  self.assertEqual(self.store.state()[1]['revision'],0)
 def test_owner_review_cannot_use_automatic_source(self):
  b,source,review=self.with_review();auto=next(r['record'] for r in b['payload']['records'] if r['record']['source_type']=='auto.usage')
  review['metadata']['source_id']=auto['id'];review['metadata']['source_sha256']=C.digest(auto);self.rehash_records(b)
  with self.assertRaisesRegex(C.ContextError,'relationship'):self.ingest(b)
 def test_owner_review_chaining_is_rejected(self):
  b,source,review=self.with_review();second=copy.deepcopy(review);second['id']=str(uuid.uuid4());second['metadata']['source_id']=review['id'];second['metadata']['source_sha256']=C.digest(review)
  b['payload']['records'].append({'record':second,'record_sha256':C.digest(second),'annotation':{'project_id':'P29','important':False,'reviewed':True}});self.rehash(b)
  with self.assertRaisesRegex(C.ContextError,'relationship'):self.ingest(b)
 def test_owner_review_source_tombstone_filters_interpretation(self):
  b,source,review=self.with_review();b['payload']['deleted_ids']=[source['id']];self.rehash(b)
  self.assertEqual(P.preview(b)['omitted_review_records'],1)
  result=self.ingest(b);self.assertEqual(result['omitted_review_records'],1);self.assertEqual(len(result['receipts']),2)
  self.assertEqual(self.store.search('interpretation',self.owner,'P29'),[])
 def test_old_review_export_cannot_evade_existing_source_tombstone(self):
  b,source,review=self.with_review();deleted=copy.deepcopy(b);deleted['payload']['deleted_ids']=[source['id']];self.rehash(deleted)
  self.ingest(deleted);result=self.ingest(b)
  self.assertEqual(result['omitted_review_records'],1);self.assertEqual(self.store.state()[1]['revision'],2)
  self.assertEqual(self.store.search('interpretation',self.owner,'P29'),[])
 def test_source_time_deletion_filters_later_review(self):
  b,source,review=self.with_review();b['payload']['delete_ranges']=[{'start_ms':source['event_ms'],'end_ms':source['event_ms']+1}];self.rehash(b)
  self.assertFalse(P.in_ranges(b['payload']['delete_ranges'],review['event_ms']))
  self.assertEqual(self.ingest(b)['omitted_review_records'],1)
  self.assertEqual(self.store.search('interpretation',self.owner,'P29'),[])
 def test_deleting_previously_imported_review_source_requires_destination_rebuild(self):
  b,source,review=self.with_review();self.ingest(b);deleted=copy.deepcopy(b);deleted['payload']['deleted_ids']=[source['id']];self.rehash(deleted)
  before=self.store.journal.read_bytes()
  with self.assertRaisesRegex(C.ContextError,'Deletion intersects'):self.ingest(deleted)
  self.assertEqual(before,self.store.journal.read_bytes())
 def test_existing_review_provenance_blocks_deletion_even_without_original_capture(self):
  b,source,review=self.with_review()
  self.store.accept({'format':G.FORMAT,'capture_id':review['id'],'text':review['text'],'project_id':'P29',
                    'visibility':'private','source_kind':'UNKNOWN','origin':{'review':{
                     'source_event_id':source['id'],'source_event_ms':source['event_ms']}}},True,'capture-adapter')
  b['payload']['deleted_ids']=[source['id']];self.rehash(b)
  with self.assertRaisesRegex(C.ContextError,'review sources'):self.ingest(b)
 def test_canonical_review_does_not_promote_project_or_source_authority(self):
  b,source,review=self.with_review();review['metadata']['epistemic_status']='canonical';self.rehash_records(b)
  before=self.store.state()[0]['projects'];self.ingest(b)
  imported=self.store.source('CAPTURE.'+review['id'],self.owner)
  self.assertEqual(imported['source']['source_kind'],'UNKNOWN');self.assertEqual(self.store.state()[0]['projects'],before)
  self.assertEqual(imported['source']['origin']['review']['epistemic_status'],'canonical')
 def test_review_import_still_requires_non_sensitive_gate(self):
  b,source,review=self.with_review()
  with self.assertRaises(C.ContextError):P.ingest(self.store,b,approved=True,non_sensitive=False)
  self.assertEqual(self.store.state()[1]['revision'],0)
 def test_review_record_order_cannot_commit_before_its_original(self):
  from unittest.mock import patch
  b,source,review=self.with_review();b['payload']['records'].reverse();self.rehash(b)
  original_accept=self.store.accept
  def checked_accept(req,*args):
   if req['capture_id']==review['id']:
    self.assertTrue(any(s.get('capture_id')==source['id'] for s in self.store.state()[0]['sources']))
   return original_accept(req,*args)
  with patch.object(self.store,'accept',side_effect=checked_accept):self.ingest(b)
 def test_review_malformed_status_is_a_typed_rejection(self):
  b,source,review=self.with_review();review['metadata']['epistemic_status']=['inference'];self.rehash_records(b)
  with self.assertRaises(C.ContextError):self.ingest(b)
if __name__=='__main__':unittest.main()
