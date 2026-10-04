import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import context_store as C
import capture_context as G
import context_client as L

class ClientTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); p=Path(self.tmp.name)
        original=p/'original.json';C.json_write(original,C.init_store({'projects':[{'id':'PX','name':'Synthetic project','state':'READY','confidence':'C'}],'sources':[]}))
        self.s=G.Store.initialize(p/'private',original)
        self.http=G.server(self.s,{'owner':'o'*40,'agent':'a'*40},frozenset({'PX'}),0)
        self.thread=threading.Thread(target=self.http.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):
        self.http.shutdown();self.http.server_close();self.thread.join(timeout=2);self.tmp.cleanup()
    def test_actual_agent_client_reads_context(self):
        result=L.retrieve('project','PX',port=self.http.server_port,token='a'*40)
        self.assertEqual(result['project']['id'],'PX')
        self.assertEqual(result['trust'],'untrusted-source-data')
    def test_client_has_no_write_operation(self):
        with self.assertRaises(C.ContextError):L.retrieve('capture','PX',port=self.http.server_port,token='a'*40)
    def test_client_invalid_id_rejected(self):
        with self.assertRaises(C.ContextError):L.retrieve('source','../outside',port=self.http.server_port,token='a'*40)
    def test_client_refuses_credential_redirect(self):
        with self.assertRaises(C.ContextError):L.NoRedirect().redirect_request(None,None,302,'redirect',{},'https://foreign.example')

if __name__=='__main__':unittest.main()
