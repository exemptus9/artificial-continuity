import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'tools'))
import proof_of_progress as P

class ProofTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name)
        (self.root/'receipt.json').write_text('{"test":"synthetic"}')
        self.event = {'id':'test-event','kind':'software','status':'VERIFIED','project_id':'P01',
                      'verified_at':'2026-01-01T00:00:00Z','summary':'PRIVATE Person confidential@example.test $100 medical details',
                      'scope':'synthetic private scope','metrics':{'python_tests':7},
                      'sources':[{'path':'receipt.json','sha256':hashlib.sha256((self.root/'receipt.json').read_bytes()).hexdigest()}]}
    def data(self, event=None): return {'format':P.FORMAT,'events':[event or self.event]}
    def policy(self,event=None):
        e=event or self.event
        return {'format':'ContinuityPublicPolicy/1','events':{e['id']:{'event_sha256':P.digest(e),'metrics':['python_tests']}}}
    def test_public_contains_only_templates_and_counts(self):
        private,public,n=P.render(P.validate(self.data(),self.root),self.policy())
        self.assertIn('confidential@example.test',private); self.assertIn('**7**',public);self.assertEqual(n,1)
        for secret in ['PRIVATE','confidential@example.test','$100','medical','test-event','P01','receipt.json']:
            self.assertNotIn(secret,public)
    def test_no_policy_defaults_to_no_public_events(self):
        self.assertEqual(P.render(self.data(),{'format':'ContinuityPublicPolicy/1','events':{}})[2],0)
    def test_record_cannot_approve_itself(self):
        self.event['public_approved']=True
        self.assertEqual(P.render(self.data(),{'format':'ContinuityPublicPolicy/1','events':{}})[2],0)
    def test_changed_record_requires_policy_review(self):
        policy=self.policy();self.event['metrics']['python_tests']=8
        with self.assertRaisesRegex(ValueError,'stale'):P.render(self.data(),policy)
    def test_protected_category_cannot_be_exported(self):
        for kind in ['medical','legal','financial','housing','employment','correspondence']:
            self.event['kind']=kind
            with self.assertRaises(ValueError):P.render(self.data(),self.policy())
    def test_prepared_claim_cannot_be_exported_as_verified(self):
        self.event['status']='PREPARED'
        with self.assertRaises(ValueError):P.render(self.data(),self.policy())
    def test_source_tampering_fails(self):
        (self.root/'receipt.json').write_text('changed')
        with self.assertRaisesRegex(ValueError,'hash mismatch'):P.validate(self.data(),self.root)
    def test_traversal_and_symlink_fail(self):
        original=copy.deepcopy(self.event)
        self.event['sources'][0]['path']='../receipt.json'
        with self.assertRaises(ValueError):P.validate(self.data(),self.root)
        self.event=original;(self.root/'linked.json').symlink_to(self.root/'receipt.json');self.event['sources'][0]['path']='linked.json'
        with self.assertRaises(ValueError):P.validate(self.data(),self.root)
    def test_numeric_injection_and_bool_fail(self):
        for value in ['7 private@example.test',True,-1,10**10]:
            self.event['metrics']['python_tests']=value
            with self.assertRaises(ValueError):P.validate(self.data(),self.root)
    def test_duplicate_event_ids_fail(self):
        with self.assertRaises(ValueError):P.validate({'format':P.FORMAT,'events':[self.event,self.event]},self.root)
    def test_superseded_event_not_reported_twice(self):
        self.event['superseded_by']='new-event'
        self.assertEqual(P.render(self.data(),self.policy())[2],0)
    def test_deterministic_round_trip(self):
        for name,obj in [('e.json',self.data()),('p.json',self.policy())]:(self.root/name).write_text(json.dumps(obj))
        a=P.generate(self.root/'e.json',self.root/'p.json',self.root,self.root/'out1')
        b=P.generate(self.root/'e.json',self.root/'p.json',self.root,self.root/'out2')
        self.assertEqual(a,b)
        for name in a['files']:self.assertEqual((self.root/'out1'/name).read_bytes(),(self.root/'out2'/name).read_bytes())

if __name__=='__main__':unittest.main()
