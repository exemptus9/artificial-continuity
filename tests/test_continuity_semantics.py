import json
import tempfile
import subprocess
import unittest
from pathlib import Path
import sys

TOOLS=Path(__file__).resolve().parents[1]/"tools"
sys.path.insert(0,str(TOOLS))
import continuity_semantics as S
import continuity_voice as V

T0="2026-10-04T03:10:50+00:00"
T1="2026-10-05T13:57:36+00:00"
T2="2026-10-07T17:29:00+00:00"
T3="2026-10-07T17:39:45+00:00"

class SemanticTests(unittest.TestCase):
    def setUp(self):
        self.store=S.init_ledger("2026-10-04T00:00:00+00:00")
    def add(self,r):
        _,h=S.replay(self.store); self.store,changed=S.append(self.store,r,h["store_sha256"],True); self.assertTrue(changed)
    def source(self,sid,locator,at):
        self.add(S.record("source_recorded","REC."+sid,{"id":sid,"locator":locator,"observed_at":at},at=at))
    def seed(self):
        self.source("SRC.MAIN_PREMERGE","https://github.com/exemptus9/artificial-continuity/tree/724ab357b714e45d1073b1989eff370bd31b66c2",T0)
        self.source("SRC.PR5","https://github.com/exemptus9/artificial-continuity/pull/5",T1)
        self.source("SRC.MAIN_CURRENT","https://github.com/exemptus9/artificial-continuity/commit/ea41a077e753f90fb78de8e2e406b5f5af05f052",T1)
        self.source("SRC.SEMANTIC_PR9","https://github.com/exemptus9/artificial-continuity/pull/9",T3)
        self.add(S.record("entity_recorded","REC.PROJECT",{"id":"PROJECT.artificial-continuity","kind":"project","name":"Artificial Continuity","aliases":["Continuity"]},["SRC.MAIN_CURRENT"],at=T1))
        self.add(S.record("entity_recorded","REC.CONTEXT.SYSTEM",{"id":"SYSTEM.controlled-context","kind":"system","name":"Controlled Context Core"},["SRC.PR5","SRC.MAIN_CURRENT"],at=T1))
        self.add(S.record("relationship_recorded","REC.REL.IMPLEMENTS",{"id":"REL.PROJECT_IMPLEMENTS_CONTEXT","subject_id":"PROJECT.artificial-continuity","predicate":"implements","object_id":"SYSTEM.controlled-context","status":"canonical"},["SRC.PR5","SRC.MAIN_CURRENT"],at=T1))
        self.add(S.record("claim_recorded","REC.CLAIM.OLD",{"id":"CLAIM.CONTEXT_ON_MAIN.OLD","subject_id":"PROJECT.artificial-continuity","predicate":"controlled_context_on_main","value":False,"status":"observed","origin":"source-observed","confidence":1.0,"valid_from":T0,"valid_to":T1},["SRC.MAIN_PREMERGE"],at=T0))
        self.add(S.record("claim_recorded","REC.CLAIM.NEW",{"id":"CLAIM.CONTEXT_ON_MAIN.NEW","subject_id":"PROJECT.artificial-continuity","predicate":"controlled_context_on_main","value":True,"status":"observed","origin":"source-observed","confidence":1.0,"valid_from":T1},["SRC.PR5","SRC.MAIN_CURRENT"],at=T1))
        self.add(S.record("claim_resolution","REC.RESOLVE.CONTEXT",{"winner":"CLAIM.CONTEXT_ON_MAIN.NEW","superseded":["CLAIM.CONTEXT_ON_MAIN.OLD"],"reason":"PR #5 merged into main; preserve earlier state as historical."},["SRC.PR5","SRC.MAIN_CURRENT"],at=T1))
        self.add(S.record("claim_recorded","REC.OBJECTIVE",{"id":"CLAIM.OBJECTIVE","subject_id":"PROJECT.artificial-continuity","predicate":"objective","value":"Continuity is the product; the model is a replaceable reasoning engine.","status":"canonical","origin":"source-observed","confidence":1.0},["SRC.MAIN_CURRENT"],at=T2))
        self.add(S.record("decision_recorded","REC.DECISION.REPLACEABLE",{"id":"DECISION.REPLACEABLE_ENGINE","project_id":"PROJECT.artificial-continuity","key":"reasoning_engine","value":"replaceable","status":"canonical"},["SRC.MAIN_CURRENT"],at=T2))
        self.add(S.record("artifact_recorded","REC.ARTIFACT.CONTEXT",{"id":"ART.CONTEXT_CORE","project_id":"PROJECT.artificial-continuity","name":"Controlled capture/context core","locator":"https://github.com/exemptus9/artificial-continuity/pull/5","status":"released"},["SRC.PR5","SRC.MAIN_CURRENT"],at=T1))
        self.add(S.record("work_state_set","REC.WORK.RELEASED",{"id":"WORK.CONTEXT_CORE","project_id":"PROJECT.artificial-continuity","summary":"Controlled text capture/store/FTS5/scoped retrieval/client/backup","state":"released","evidence_refs":["SRC.PR5","SRC.MAIN_CURRENT"]},["SRC.PR5","SRC.MAIN_CURRENT"],at=T1))
        self.add(S.record("work_state_set","REC.WORK.SEMANTICS",{"id":"WORK.SEMANTIC_LAYER","project_id":"PROJECT.artificial-continuity","summary":"Implement provenance-aware claims, canonical temporal state, conflicts, and resume packets","state":"implemented","evidence_refs":["SRC.SEMANTIC_PR9"]},["SRC.SEMANTIC_PR9"],at=T2))
    def test_temporal_supersession_preserves_history(self):
        self.seed(); p,_=S.replay(self.store)
        self.assertEqual(p["claims"]["CLAIM.CONTEXT_ON_MAIN.OLD"]["status"],"superseded")
        self.assertEqual(p["claims"]["CLAIM.CONTEXT_ON_MAIN.NEW"]["status"],"canonical")
        self.assertFalse(S.conflicts(p,"PROJECT.artificial-continuity"))
        resolution_index=next(i for i,e in enumerate(self.store["events"],1) if e["record"]["id"]=="REC.RESOLVE.CONTEXT")
        earlier,_=S.replay(self.store,resolution_index-1)
        self.assertEqual(earlier["claims"]["CLAIM.CONTEXT_ON_MAIN.OLD"]["status"],"observed")
    def test_conflict_is_visible_until_resolved(self):
        self.source("SRC.A","https://example.invalid/a",T0); self.source("SRC.B","https://example.invalid/b",T1)
        self.add(S.record("entity_recorded","REC.P",{"id":"P","kind":"project","name":"P"},["SRC.A"],at=T0))
        self.add(S.record("claim_recorded","REC.C1",{"id":"C1","subject_id":"P","predicate":"x","value":1,"status":"observed","origin":"source-observed"},["SRC.A"],at=T0))
        self.add(S.record("claim_recorded","REC.C2",{"id":"C2","subject_id":"P","predicate":"x","value":2,"status":"observed","origin":"source-observed"},["SRC.B"],at=T1))
        p,_=S.replay(self.store); self.assertEqual(len(S.conflicts(p,"P")),1)
    def test_duplicate_entity_ingestion_is_rejected(self):
        self.source("SRC.A","https://example.invalid/a",T0)
        self.add(S.record("entity_recorded","REC.P1",{"id":"P","kind":"project","name":"P"},["SRC.A"],at=T0))
        with self.assertRaises(S.ContinuityError):
            self.add(S.record("entity_recorded","REC.P2",{"id":"P","kind":"project","name":"Renamed by duplicate"},["SRC.A"],at=T1))

    def test_rename_keeps_stable_identity(self):
        self.source("SRC.A","https://example.invalid/a",T0)
        self.add(S.record("entity_recorded","REC.P",{"id":"P","kind":"project","name":"Old"},["SRC.A"],at=T0))
        self.add(S.record("entity_renamed","REC.RENAME",{"entity_id":"P","name":"New"},["SRC.A"],at=T1))
        p,_=S.replay(self.store); self.assertEqual(S.find_entity(p,"Old")[0]["id"],"P"); self.assertEqual(S.find_entity(p,"New")[0]["id"],"P")
    def test_relationship_and_provenance_are_retrievable(self):
        self.seed(); p,_=S.replay(self.store)
        rel=p["relationships"]["REL.PROJECT_IMPLEMENTS_CONTEXT"]
        self.assertEqual(rel["predicate"],"implements")
        prov=S.get_provenance(p,"REL.PROJECT_IMPLEMENTS_CONTEXT")
        self.assertEqual(prov["object_type"],"relationships")
        self.assertIn("SRC.PR5",{x["id"] for x in prov["sources"]})

    def test_verified_work_requires_evidence(self):
        self.source("SRC.A","https://example.invalid/a",T0)
        self.add(S.record("entity_recorded","REC.P",{"id":"P","kind":"project","name":"P"},["SRC.A"],at=T0))
        with self.assertRaises(S.ContinuityError):
            self.add(S.record("work_state_set","REC.W",{"id":"W","project_id":"P","summary":"x","state":"verified","evidence_refs":[]},[],at=T1))
    def test_resume_closed_loop_fresh_agent_and_reingest(self):
        self.seed(); packet=S.resume_packet(self.store,"PROJECT.artificial-continuity")
        self.assertIn("WORK.CONTEXT_CORE",{x["work_id"] for x in packet["do_not_redo"]})
        self.assertIn("WORK.SEMANTIC_LAYER",{x["work_id"] for x in packet["next_actions"]})
        fresh_input=json.loads(json.dumps(packet))
        self.assertEqual(fresh_input["project"]["id"],"PROJECT.artificial-continuity")
        self.add(S.record("artifact_recorded","REC.ARTIFACT.RESUME",{"id":"ART.RESUME_RECEIPT","project_id":"PROJECT.artificial-continuity","name":"Fresh-agent resume receipt","locator":"continuity:test:resume-receipt","status":"verified"},["SRC.SEMANTIC_PR9"],actor="fresh-agent",at=T2))
        self.add(S.record("event_recorded","REC.EVENT.RESUMED",{"project_id":"PROJECT.artificial-continuity","event_type":"resumed_by_fresh_agent","details":{"resume_revision":fresh_input["ledger"]["revision"],"artifact_id":"ART.RESUME_RECEIPT"}},["SRC.SEMANTIC_PR9"],actor="fresh-agent",at=T2))
        p,h=S.replay(self.store); self.assertIn("ART.RESUME_RECEIPT",p["artifacts"]); self.assertGreater(h["revision"],fresh_input["ledger"]["revision"])
        packet2=S.resume_packet(self.store,"PROJECT.artificial-continuity")
        self.assertIn("ART.RESUME_RECEIPT",{a["id"] for a in packet2["artifacts"]})
    def test_duplicate_id_and_stale_write_fail(self):
        self.source("SRC.A","https://example.invalid/a",T0); _,h=S.replay(self.store)
        r=S.record("entity_recorded","REC.P",{"id":"P","kind":"project","name":"P"},["SRC.A"],at=T0)
        self.store,_=S.append(self.store,r,h["store_sha256"],True)
        same,changed=S.append(self.store,r,S.replay(self.store)[1]["store_sha256"],True); self.assertFalse(changed); self.assertEqual(same,self.store)
        with self.assertRaises(S.ContinuityError): S.append(self.store,S.record("event_recorded","REC.X",{"project_id":"P","event_type":"x"},["SRC.A"],at=T1),h["store_sha256"],True)
    def test_export_import_reconstruction(self):
        self.seed(); exported=json.loads(json.dumps(self.store)); p1,h1=S.replay(self.store); p2,h2=S.replay(exported); self.assertEqual(p1,p2); self.assertEqual(h1,h2)
    def test_missing_source_is_rejected(self):
        with self.assertRaises(S.ContinuityError):
            self.add(S.record("entity_recorded","REC.P",{"id":"P","kind":"project","name":"P"},["SRC.MISSING"],at=T0))
    def test_partial_and_interrupted_work_resume_instead_of_becoming_done(self):
        self.source("SRC.A","https://example.invalid/a",T0)
        self.add(S.record("entity_recorded","REC.P",{"id":"P","kind":"project","name":"P"},["SRC.A"],at=T0))
        self.add(S.record("work_state_set","REC.W.START",{"id":"W","project_id":"P","summary":"Finish the vertical slice","state":"started","evidence_refs":[]},["SRC.A"],at=T0))
        self.add(S.record("event_recorded","REC.INTERRUPT",{"project_id":"P","event_type":"workflow_interrupted","details":{"reason":"session ended after partial implementation"}},["SRC.A"],at=T1))
        packet=S.resume_packet(self.store,"P")
        self.assertEqual(packet["last_verified_accomplishment"],None)
        self.assertIn("W",{x["work_id"] for x in packet["next_actions"]})
        self.assertNotIn("W",{x["work_id"] for x in packet["do_not_redo"]})
    def test_cli_verifies_serialized_ledger(self):
        self.seed()
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"ledger.json"; S.atomic_json(path,self.store,False)
            cp=subprocess.run([sys.executable,str(TOOLS/"continuity_cli.py"),"verify",str(path)],capture_output=True,text=True)
            self.assertEqual(cp.returncode,0,cp.stderr); self.assertTrue(json.loads(cp.stdout)["valid"])

    def test_resume_has_machine_and_human_readable_forms(self):
        self.seed(); packet=S.resume_packet(self.store,"PROJECT.artificial-continuity")
        md=S.resume_markdown(packet)
        self.assertEqual(packet["format"],"ContinuityResumePacket/1")
        self.assertIn("# Resume — Artificial Continuity",md)
        self.assertIn("## Do not redo",md); self.assertIn("WORK.CONTEXT_CORE",md)
        self.assertIn("## Next actions",md); self.assertIn("WORK.SEMANTIC_LAYER",md)

    def test_voice_reads_and_stages_writes(self):
        self.seed()
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"ledger.json"; S.atomic_json(path,self.store,False)
            result=V.run(path,"Continuity resume Continuity"); self.assertTrue(result["executed"]); self.assertEqual(result["result"]["project"]["id"],"PROJECT.artificial-continuity")
            staged=V.run(path,"Continuity record note Voice should not silently become canonical"); self.assertFalse(staged["executed"]); self.assertTrue(staged["requires_confirmation"])

if __name__=="__main__": unittest.main()
