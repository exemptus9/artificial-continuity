/* User-reviewed workflows. Deterministic data operations, not model-generated decisions. */
(function(root){
'use strict';
const W=root.WorkspaceCore||(typeof require==='function'?require('./workspace-core.js'):null);
if(typeof require==='function'&&!root.OperationalCore)require('./operations-core.js');
const fail=m=>{throw new Error(m);},copy=W.copy;
const text=(x,max)=>typeof x==='string'&&x.length<=max;
const json=JSON.stringify;
const THREAD_FIELDS=['title','objective','last','next','state','waiting','open','attention'];
function threadBasis(t){return json(Object.fromEntries(THREAD_FIELDS.map(k=>[k,t[k]??null])));}
function noteBasis(s,n){const src=s.sources.find(x=>x.id===n.sourceId);return json({note:n,source:src,threadId:n.threadId});}
function pending(s,threadId='',query=''){
 const words=query.trim().toLowerCase().split(/\s+/).filter(Boolean);
 const srcs=new Map(s.sources.map(x=>[x.id,x])),threads=new Map(s.threads.map(x=>[x.id,x]));
 return s.notes.filter(n=>n.reviewState==='pending'&&(!threadId||n.threadId===threadId)).filter(n=>words.every(w=>[n.statement,n.quote,srcs.get(n.sourceId).title,threads.get(n.threadId).title].join(' ').toLowerCase().includes(w))).map(n=>{
  const src=srcs.get(n.sourceId),t=threads.get(n.threadId);
  // The queue needs the selected quotation, not a full transcript copy per row.
  return {note:copy(n),source:{id:src.id,title:src.title,speaker:src.speaker},thread:{id:t.id,title:t.title}};
 });
}
function reviewPlan(s,ids){
 s=W.validate(s);if(!Array.isArray(ids)||!ids.length||ids.length>20||new Set(ids).size!==ids.length)fail('Choose 1–20 distinct pending checkpoints.');
 return {format:'ContinuityReviewPlan/1',items:ids.map(id=>{const n=s.notes.find(x=>x.id===id);if(!n||n.reviewState!=='pending')fail('Checkpoint is no longer pending. Refresh the review desk.');return {id,basis:noteBasis(s,n)};})};
}
function applyReview(s,plan,edits,at){
 s=W.validate(s);
 if(plan?.format!=='ContinuityReviewPlan/1'||!Array.isArray(plan.items)||plan.items.length>20||!Array.isArray(edits)||!edits.length||edits.length>20)fail('Invalid review batch.');
 if(!text(at,100)||!Number.isFinite(Date.parse(at)))fail('Invalid review timestamp.');
 const ids=new Set();const next=copy(s);
 for(const edit of edits){
  if(!edit||ids.has(edit.id)||edit.accepted!==true)fail('Select and explicitly accept each checkpoint.');ids.add(edit.id);
  const n=next.notes.find(x=>x.id===edit.id),saved=plan.items.find(x=>x.id===edit.id);
  if(!n||!saved||n.reviewState!=='pending'||noteBasis(s,n)!==saved.basis)fail('The checkpoint or its source changed after preview. Review again.');
  if(!W.KINDS.includes(edit.kind)||!text(edit.statement,4000)||!edit.statement.trim())fail('Each selected checkpoint needs a valid type and interpretation (up to 4,000 characters).');
  // Preserve the imported interpretation before editing it. Source text is never changed.
  n.reviewedFrom={statement:n.statement,kind:n.kind,reviewState:n.reviewState};
  n.statement=edit.statement.trim();n.kind=edit.kind;n.reviewState='confirmed';n.reviewedAt=at;
 }
 return W.validate(next);
}
function progressPlan(s,threadId,fields,sourceId,at){
 s=W.validate(s);const t=s.threads.find(x=>x.id===threadId);if(!t)fail('Intention not found.');
 const f={progress:fields.progress,next:fields.next??'',state:fields.state??t.state,waiting:fields.waiting??''};
 if(!text(f.progress,20000)||!f.progress.trim()||!text(f.next,20000)||!W.STATES.includes(f.state)||!text(f.waiting,20000))fail('Add a progress note and use valid optional fields (up to 20,000 characters).');
 if(!text(at,100)||!Number.isFinite(Date.parse(at)))fail('Invalid work-note timestamp.');
 const body=['# Work note',`Recorded: ${at}`,'','## Progress — exact input',f.progress,'','## Next action — exact input',f.next,'','## Proposed progress state',f.state,'','## Waiting reason — exact input',f.waiting].join('\n');
 const source=W.makeSource(s,{title:('Work note · '+t.title+' · '+at.slice(0,10)).slice(0,200),text:body,threadId,speaker:'user',url:''},sourceId,at);
 source.category='work-note';
 return {format:'ContinuityProgressPlan/1',threadId,basis:threadBasis(t),fields:f,source};
}
function applyProgress(s,plan,selected){
 s=W.validate(s);const t=s.threads.find(x=>x.id===plan?.threadId);
 if(plan?.format!=='ContinuityProgressPlan/1'||!t||threadBasis(t)!==plan.basis)fail('This intention changed after preview. Reopen the progress form; your draft is retained.');
 const allowed=['last','next','state','waiting'];
 if(!Array.isArray(selected)||new Set(selected).size!==selected.length||selected.some(k=>!allowed.includes(k)))fail('Invalid field selection.');
 if(!plan.fields||!plan.source)fail('Invalid progress preview.');
 const checked=progressPlan(s,plan.threadId,plan.fields,plan.source.id,plan.source.createdAt);
 if(json(checked.source)!==json(plan.source))fail('Work-note preview does not match the original input.');
 const next=copy(s),target=next.threads.find(x=>x.id===t.id),f=checked.fields;
 if(selected.includes('last'))target.last=f.progress.trim();
 if(selected.includes('next')){if(!f.next.trim())fail('Enter the next action or leave that field unselected.');target.next=f.next.trim();}
 if(selected.includes('state'))target.state=f.state;
 if(selected.includes('waiting'))target.waiting=f.waiting.trim();
 if(['WAITING','BLOCKED'].includes(target.state)&&!target.waiting?.trim())fail('A waiting or blocked intention needs a reason. Select the waiting reason too.');
 next.sources.push(checked.source);
 if(new TextEncoder().encode(json(next)).length>15000000)fail('Workspace would exceed the portable backup limit. Export before adding more.');
 return W.validate(next);
}
function workNotes(s,threadId){return s.sources.filter(x=>x.threadId===threadId&&x.category==='work-note').slice().reverse();}
const api={pending,reviewPlan,applyReview,progressPlan,applyProgress,workNotes,threadBasis};
root.WorkflowCore=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
