import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import capture_context
import context_store as C


class AgentProjectCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "private-store"
        projects = [
            {"id": "P1", "name": "Granted project", "state": "READY", "confidence": "B",
             "category": "software", "priority": "P2", "summary": "Visible only to the grant.",
             "next_action": "Verify retrieval."},
            {"id": "P2", "name": "Unrelated project", "state": "DORMANT", "confidence": "C",
             "category": "private", "priority": "P4", "summary": "Must not be disclosed.",
             "next_action": "No access."},
        ]
        baseline = {"projects": projects, "sources": []}
        envelope = C.init_store(baseline)
        self.root.mkdir(mode=0o700)
        (self.root / "Context_Store.json").write_bytes(C.canonical_bytes(envelope))
        self.store = capture_context.Store(self.root)
        self.agent_token = "a" * 40
        self.owner_token = "o" * 40
        self.httpd = capture_context.server(
            self.store,
            {"owner": self.owner_token, "agent": self.agent_token},
            frozenset({"P1"}),
            port=0,
        )
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.httpd.server_port}/v1/projects"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, token):
        req = urllib.request.Request(
            self.url,
            headers={"Authorization": "Bearer " + token, "Accept": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=3) as response:
            return json.loads(response.read().decode("utf-8"))

    def test_agent_catalog_contains_only_explicitly_granted_project(self):
        result = self.request(self.agent_token)
        self.assertEqual([p["id"] for p in result["projects"]], ["P1"])
        self.assertEqual(result["scope"], "explicit-agent-grants")
        self.assertNotIn("Must not be disclosed.", json.dumps(result))

    def test_owner_token_cannot_use_agent_catalog_endpoint(self):
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.request(self.owner_token)
        self.assertEqual(raised.exception.code, 403)


if __name__ == "__main__":
    unittest.main()
