import json, sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
import continuity_semantics as S
T0='2026-10-04T03:10:50+00:00'; T1='2026-10-05T13:57:36+00:00'; T2='2026-10-07T17:29:00+00:00'; T3='2026-10-07T17:39:45+00:00'
s=S.init_ledger('2026-10-04T00:00:00+00:00')
def add(r):
 global s
 h=S.replay(s)[1]; s,_=S.append(s,r,h['store_sha256'],True)
def src(i,u,t): add(S.record('source_recorded','REC.'+i,{'id':i,'locator':u,'observed_at':t},at=t))
src('SRC.MAIN_PREMERGE','https://github.com/exemptus9/artificial-continuity/tree/724ab357b714e45d1073b1989eff370bd31b66c2',T0)
src('SRC.PR5','https://github.com/exemptus9/artificial-continuity/pull/5',T1)
src('SRC.MAIN_CURRENT','https://github.com/exemptus9/artificial-continuity/commit/ea41a077e753f90fb78de8e2e406b5f5af05f052',T1)
src('SRC.SEMANTIC_PR9','https://github.com/exemptus9/artificial-continuity/pull/9',T3)
add(S.record('entity_recorded','REC.PROJECT',{'id':'PROJECT.artificial-continuity','kind':'project','name':'Artificial Continuity','aliases':['Continuity']},['SRC.MAIN_CURRENT'],at=T1))
add(S.record('entity_recorded','REC.CONTEXT.SYSTEM',{'id':'SYSTEM.controlled-context','kind':'system','name':'Controlled Context Core'},['SRC.PR5','SRC.MAIN_CURRENT'],at=T1))
add(S.record('relationship_recorded','REC.REL.IMPLEMENTS',{'id':'REL.PROJECT_IMPLEMENTS_CONTEXT','subject_id':'PROJECT.artificial-continuity','predicate':'implements','object_id':'SYSTEM.controlled-context','status':'canonical'},['SRC.PR5','SRC.MAIN_CURRENT'],at=T1))
add(S.record('claim_recorded','REC.CLAIM.OLD',{'id':'CLAIM.CONTEXT_ON_MAIN.OLD','subject_id':'PROJECT.artificial-continuity','predicate':'controlled_context_on_main','value':False,'status':'observed','origin':'source-observed','confidence':1.0,'authority':'repository-evidence','valid_from':T0,'valid_to':T1},['SRC.MAIN_PREMERGE'],at=T0))
add(S.record('claim_recorded','REC.CLAIM.NEW',{'id':'CLAIM.CONTEXT_ON_MAIN.NEW','subject_id':'PROJECT.artificial-continuity','predicate':'controlled_context_on_main','value':True,'status':'observed','origin':'source-observed','confidence':1.0,'authority':'repository-evidence','valid_from':T1},['SRC.PR5','SRC.MAIN_CURRENT'],at=T1))
add(S.record('claim_resolution','REC.RESOLVE.CONTEXT',{'winner':'CLAIM.CONTEXT_ON_MAIN.NEW','superseded':['CLAIM.CONTEXT_ON_MAIN.OLD'],'reason':'PR #5 merged into main.'},['SRC.PR5','SRC.MAIN_CURRENT'],at=T1))
add(S.record('claim_recorded','REC.OBJECTIVE',{'id':'CLAIM.OBJECTIVE','subject_id':'PROJECT.artificial-continuity','predicate':'objective','value':'Continuity is the product; the model is a replaceable reasoning engine.','status':'canonical','origin':'source-observed','confidence':1.0,'authority':'repository-doctrine'},['SRC.MAIN_CURRENT'],at=T2))
add(S.record('decision_recorded','REC.DECISION.ENGINE',{'id':'DECISION.REPLACEABLE_ENGINE','project_id':'PROJECT.artificial-continuity','key':'reasoning_engine','value':'replaceable','status':'canonical'},['SRC.MAIN_CURRENT'],at=T2))
add(S.record('artifact_recorded','REC.ARTIFACT.CONTEXT',{'id':'ART.CONTEXT_CORE','project_id':'PROJECT.artificial-continuity','name':'Controlled capture/context core','locator':'https://github.com/exemptus9/artificial-continuity/pull/5','status':'released'},['SRC.PR5','SRC.MAIN_CURRENT'],at=T1))
add(S.record('work_state_set','REC.WORK.RELEASED',{'id':'WORK.CONTEXT_CORE','project_id':'PROJECT.artificial-continuity','summary':'Controlled text capture/store/FTS5/scoped retrieval/client/backup','state':'released','evidence_refs':['SRC.PR5','SRC.MAIN_CURRENT']},['SRC.PR5','SRC.MAIN_CURRENT'],at=T1))
add(S.record('work_state_set','REC.WORK.SEMANTICS',{'id':'WORK.SEMANTIC_LAYER','project_id':'PROJECT.artificial-continuity','summary':'Implement provenance-aware claims, canonical temporal state, conflicts, and Resume Packets','state':'implemented','evidence_refs':['SRC.SEMANTIC_PR9']},['SRC.SEMANTIC_PR9'],at=T2))
p1=S.resume_packet(s,'PROJECT.artificial-continuity')
add(S.record('artifact_recorded','REC.ARTIFACT.RESUME',{'id':'ART.RESUME_RECEIPT','project_id':'PROJECT.artificial-continuity','name':'Fresh-agent resume receipt','locator':'continuity:test:resume-receipt','status':'verified'},['SRC.SEMANTIC_PR9'],actor='fresh-agent',at=T2))
add(S.record('event_recorded','REC.EVENT.RESUMED',{'project_id':'PROJECT.artificial-continuity','event_type':'resumed_by_fresh_agent','details':{'resume_revision':p1['ledger']['revision'],'artifact_id':'ART.RESUME_RECEIPT'}},['SRC.SEMANTIC_PR9'],actor='fresh-agent',at=T2))
root=Path(__file__).resolve().parent
(root/'continuity_public_vertical_slice_ledger.json').write_text(json.dumps(s,indent=2,ensure_ascii=False)+'\n')
packet=S.resume_packet(s,'PROJECT.artificial-continuity')
(root/'continuity_public_resume.json').write_text(json.dumps(packet,indent=2,ensure_ascii=False)+'\n')
(root/'continuity_public_resume.md').write_text(S.resume_markdown(packet),encoding='utf-8')
print(json.dumps(S.replay(s)[1],indent=2))
