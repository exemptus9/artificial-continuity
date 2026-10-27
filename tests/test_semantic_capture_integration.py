"""Real public-source bridge test across the existing capture core and semantic layer."""
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS=Path(__file__).resolve().parents[1]/"tools"
sys.path.insert(0,str(TOOLS))

import context_store as C
import capture_context as G
import continuity_semantics as S


class SemanticCaptureIntegrationTests(unittest.TestCase):
    def test_repository_readme_bytes_flow_into_semantic_provenance(self):
        repo=Path(__file__).resolve().parents[1]
        raw=(repo/"README.md").read_text(encoding="utf-8")
        self.assertIn("The model is a replaceable reasoning engine.",raw)

        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            original=root/"original-context.json"
            baseline={
                "projects":[{"id":"PC","name":"Artificial Continuity","state":"ACTIVE","confidence":"A","sources":[]}],
                "sources":[]
            }
            C.json_write(original,C.init_store(baseline))
            captured=G.Store.initialize(root/"private",original)
            req={
                "format":G.FORMAT,
                "capture_id":"11111111-1111-4111-8111-111111111111",
                "text":raw,
                "title":"Repository README source",
                "project_id":"PC",
                "visibility":"private",
                "source_kind":"SOURCE_FACT",
                "speaker":"unknown",
                "observed_at":"2026-10-07T17:58:00+00:00",
                "source_url":"https://github.com/exemptus9/artificial-continuity/blob/main/README.md",
                "origin":{"repository":"exemptus9/artificial-continuity","path":"README.md"}
            }
            receipt=captured.accept(req,True,actor="owner")
            exact=captured.source(receipt["source_id"],G.Principal("owner"))
            self.assertEqual(exact["original_text"],raw)
            self.assertEqual(receipt["sha256"],hashlib.sha256(raw.encode("utf-8")).hexdigest())

            sem=S.init_ledger("2026-10-07T17:58:00+00:00")
            def add(record):
                nonlocal sem
                head=S.replay(sem)[1]
                sem,changed=S.append(sem,record,head["store_sha256"],True)
                self.assertTrue(changed)

            add(S.record("source_recorded","REC.README.SOURCE",{
                "id":"SRC.README_CAPTURE",
                "locator":"continuity-source:"+receipt["source_id"],
                "captured_sha256":receipt["sha256"],
                "capture_source_id":receipt["source_id"],
                "observed_at":"2026-10-07T17:58:00+00:00"
            },at="2026-10-07T17:58:00+00:00"))
            add(S.record("entity_recorded","REC.README.PROJECT",{
                "id":"PROJECT.artificial-continuity","kind":"project","name":"Artificial Continuity","aliases":["Continuity"]
            },["SRC.README_CAPTURE"],at="2026-10-07T17:58:01+00:00"))
            add(S.record("claim_recorded","REC.README.CLAIM",{
                "id":"CLAIM.README.OBJECTIVE",
                "subject_id":"PROJECT.artificial-continuity",
                "predicate":"objective",
                "value":"Continuity is the product; the model is a replaceable reasoning engine.",
                "status":"canonical",
                "origin":"source-observed",
                "confidence":1.0,
                "authority":"repository-doctrine"
            },["SRC.README_CAPTURE"],at="2026-10-07T17:58:02+00:00"))

            projection,_=S.replay(sem)
            provenance=S.get_provenance(projection,"CLAIM.README.OBJECTIVE")
            self.assertEqual(provenance["sources"][0]["captured_sha256"],receipt["sha256"])
            packet=S.resume_packet(sem,"PROJECT.artificial-continuity")
            self.assertEqual(packet["objective"],"Continuity is the product; the model is a replaceable reasoning engine.")
            self.assertEqual(packet["evidence"][0]["capture_source_id"],receipt["source_id"])


if __name__=="__main__":
    unittest.main()
