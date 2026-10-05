/* Reviewed, file-based project updates. No relay, last-write-wins, or peer authentication. */
(function(root){
'use strict';
const W=root.WorkspaceCore||(typeof require==='function'?require('./workspace-core.js'):null);
const O=root.OperationalCore||(typeof require==='function'?require('./operations-core.js'):null);
const FIELDS=['title','objective','last','next','open','state','waiting'];
const FORMAT='ContinuityExchange/1',MAX=16000000,utf=new TextEncoder();
const copy=W.copy,fail=m=>{throw new Error(m);},text=(x,n=200)=>typeof x==='string'&&x.length<=n;
function canonical(x){if(x===null||typeof x!=='object')return JSON.stringify(x);if(Array.isArray(x))return '['+x.map(canonical).join(',')+']';return '{'+Object.keys(x).sort().map(k=>JSON.stringify(k)+':'+canonical(x[k])).join(',')+'}';}
async function digest(x){if(!root.crypto?.subtle)fail('Use HTTPS or localhost with Web Crypto available.');return [...new Uint8Array(await root.crypto.subtle.digest('SHA-256',utf.encode(canonical(x))))].map(n=>n.toString(16).padStart(2,'0')).join('');}
function fields(t){return Object.fromEntries(FIELDS.map(k=>[k,copy(t[k]??(k==='open'?[]:''))]));}
function checkedFields(f){const s=W.empty();s.threads=[{...f,id:'check',attention:false}];W.validate(s);if(Object.keys(f).sort().join('|')!==[...FIELDS].sort().join('|'))fail('Unsupported intention field.');return copy(f);}
function captureProvenance(src){
 if(src.captureOrigin===undefined)return {};
 // The capture extension validates these records before snapshotting. Never
 // turn a historical local-consent receipt into an import authorization, or
 // export arbitrary sender metadata alongside the selected source.
 if(!root.CaptureFamilyCore)fail('This source requires the capture provenance extension. Use a current family release or a full backup.');
 const p=src.captureOrigin;
 return {captureOrigin:{format:p.format,sourceVersion:p.sourceVersion,appVersion:p.appVersion,method:p.method,observedAt:p.observedAt,receivedAt:p.receivedAt,inputBasis:p.inputBasis,reviewState:p.reviewState,consentRecord:{scope:p.consentRecord.scope,confirmedAt:p.consentRecord.confirmedAt}}};
}
function metadata(s){for(const t of s.threads){if(t.exchangeProjectId!==undefined&&(!text(t.exchangeProjectId)||!t.exchangeProjectId))fail('Invalid project exchange identity.');
 if(t.exchangeAnchors!==undefined){if(!Array.isArray(t.exchangeAnchors)||t.exchangeAnchors.length>24)fail('Invalid exchange anchors.');for(const a of t.exchangeAnchors){if(!/^[a-f0-9]{64}$/.test(a.hash))fail('Invalid exchange anchor hash.');checkedFields(a.fields);}}
 if(t.exchangeBase!==undefined&&t.exchangeBase!==null){if(!/^[a-f0-9]{64}$/.test(t.exchangeBase.hash))fail('Invalid exchange baseline.');checkedFields(t.exchangeBase.fields);}
 if(t.exchangeSeen!==undefined&&(!Array.isArray(t.exchangeSeen)||t.exchangeSeen.length>2000||t.exchangeSeen.some(id=>!text(id)||!id)))fail('Invalid exchange receipts.');
 }return s;}
const prior=W.validate;W.validate=input=>metadata(prior(input));
async function snapshot(s,threadId){
 s=W.validate(s);const t=s.threads.find(t=>t.id===threadId);if(!t)fail('Intention not found.');
 const projectId=t.exchangeProjectId||t.id,state=W.empty(),sourceMap=new Map(),unique=new Map();
 state.threads=[{...fields(t),id:projectId,attention:false}];
 const ns=s.notes.filter(n=>n.threadId===threadId),used=new Set(ns.map(n=>n.sourceId));
 for(const src of s.sources.filter(x=>x.threadId===threadId||used.has(x.id))){
  const content={title:src.title,text:src.text,url:src.url,speaker:src.speaker,...captureProvenance(src)};
  const id='src-'+await digest(content);sourceMap.set(src.id,id);
  if(!unique.has(id)){unique.set(id,true);state.sources.push({...content,id,threadId:projectId,createdAt:src.createdAt});}
 }
 const notes=new Set();for(const n of ns){const content={sourceId:sourceMap.get(n.sourceId),kind:n.kind,statement:n.statement,start:n.start,end:n.end,quote:n.quote};const id='note-'+await digest(content);if(!notes.has(id)){notes.add(id);state.notes.push({...content,id,threadId:projectId,createdAt:n.createdAt||'',reviewed:true,reviewState:n.reviewState||'confirmed'});}}
 const follows=new Set();state.followups=[];for(const f of (s.followups||[]).filter(f=>f.threadId===threadId)){
  const content={title:f.title,reason:f.reason,state:f.state,mode:f.mode,date:f.date||null,dependencyId:null,dependencyLabel:f.mode==='resolved'?(s.threads.find(t=>t.id===f.dependencyId)?.title||f.dependencyLabel||'External intention'):''};
  const id='follow-'+await digest(content);if(!follows.has(id)){follows.add(id);state.followups.push({...content,id,threadId:projectId,createdAt:f.createdAt});}
 }
 for(const k of ['sources','notes','followups'])state[k].sort((a,b)=>a.id.localeCompare(b.id));
 return {state:O.validate(state),sourceMap};
}
async function makePacket(s,threadId,packetId,at){
 if(!text(packetId)||!packetId||!text(at,100))fail('Invalid packet identifier or date.');
 const t=s.threads.find(t=>t.id===threadId);if(!t)fail('Intention not found.');const {state}=await snapshot(s,threadId),f=fields(state.threads[0]);
 const base=t.exchangeBase?copy(t.exchangeBase):{hash:await digest(f),fields:f};
 const p={format:FORMAT,packetId,createdAt:at,projectId:state.threads[0].id,base,state,snapshotHash:await digest(state)};
 if(utf.encode(JSON.stringify(p)).length>MAX)fail('Project exceeds the 16 MB exchange limit.');return p;
}
function anchor(t,a){t.exchangeAnchors=[...(t.exchangeAnchors||[]).filter(x=>x.hash!==a.hash),copy(a)].slice(-24);}
async function parse(raw){
 if(typeof raw!=='string'||utf.encode(raw).length>MAX)fail('Project exchange exceeds 16 MB.');let p;try{p=JSON.parse(raw);}catch{fail('Not a valid project exchange JSON file.');}
 if(!p||Array.isArray(p)||p.format!==FORMAT||!text(p.packetId)||!p.packetId||!text(p.projectId)||!p.projectId||!text(p.createdAt,100)||!/^[a-f0-9]{64}$/.test(p.snapshotHash))fail('Unsupported project exchange. Use Phone ↔ desktop on both browsers.');
 if(!p.base||!/^[a-f0-9]{64}$/.test(p.base.hash))fail('Invalid exchange baseline.');checkedFields(p.base.fields);
 const valid=O.parseProject(JSON.stringify({format:'ContinuityProject/1',packetId:p.packetId,exportedAt:p.createdAt,state:p.state})).state;
 if(p.state.threads[0].id!==p.projectId)fail('Project identity does not match its contents.');
 if(await digest(p.state)!==p.snapshotHash||await digest(p.base.fields)!==p.base.hash)fail('File integrity check failed. Nothing was imported.');
 // Rebuild a whitelist projection. Sender-supplied local metadata and extra fields are never accepted.
 const rebuilt=(await snapshot(valid,p.projectId)).state;
 if(canonical(rebuilt)!==canonical(valid))fail('Unsupported or noncanonical project records.');
 return {format:FORMAT,packetId:p.packetId,createdAt:p.createdAt,projectId:p.projectId,base:copy(p.base),state:valid,snapshotHash:p.snapshotHash};
}
async function rememberExport(s,threadId,p){
 p=await parse(JSON.stringify(p));const fresh=await snapshot(s,threadId);
 if(canonical(fresh.state)!==canonical(p.state))fail('The project changed while preparing the file. Prepare it again.');
 const next=copy(s),t=next.threads.find(t=>t.id===threadId);t.exchangeProjectId=p.projectId;
 anchor(t,{hash:await digest(fields(p.state.threads[0])),fields:fields(p.state.threads[0])});
 return W.validate(next);
}
async function plan(s,p,targetId=null){
 s=W.validate(s);p=await parse(JSON.stringify(p));
 if(s.threads.some(t=>(t.exchangeSeen||[]).includes(p.packetId)))fail('This exchange was already applied. Existing work is unchanged.');
 const matches=s.threads.filter(t=>(t.exchangeProjectId||t.id)===p.projectId);
 const t=targetId?s.threads.find(t=>t.id===targetId):matches.length===1?matches[0]:null;
 if(matches.length>1&&!targetId)fail('More than one local copy matches. Choose a target explicitly.');
 if(t&&(t.exchangeProjectId||t.id)!==p.projectId)fail('This file belongs to a different project.');
 const local=t?await snapshot(s,t.id):null,baseKnown=!!t&&(t.exchangeAnchors||[]).some(a=>a.hash===p.base.hash&&canonical(a.fields)===canonical(p.base.fields));
 const incoming=fields(p.state.threads[0]),current=t?fields(t):null;
 const rows=t?FIELDS.map(key=>{const a=current[key],b=p.base.fields[key],c=incoming[key],same=canonical(a)===canonical(c);
  const status=same?'same':!baseKnown?'review':canonical(c)===canonical(b)?'local-only':canonical(a)===canonical(b)?'incoming-only':'conflict';
  return {key,local:copy(a),base:copy(b),incoming:copy(c),status};}):[];
 const newRecords={};for(const k of ['sources','notes','followups']){const seen=new Set(local?.state[k].map(x=>x.id)||[]);newRecords[k]=p.state[k].filter(x=>!seen.has(x.id));}
 return {packet:p,targetId:t?.id||null,basis:JSON.stringify(s),baseKnown,rows,newRecords,sourceMap:local?[...local.sourceMap]:[],localSnapshot:local?.state||null};
}
async function apply(s,preview,choices,options,idFn,at){
 if(JSON.stringify(W.validate(s))!==preview.basis)fail('Workspace changed after the preview. Review the file again before applying.');
 const verified=await plan(s,preview.packet,preview.targetId);
 const next=copy(s),p=verified.packet;let t=verified.targetId?next.threads.find(x=>x.id===verified.targetId):null;
 if(!t){t={...fields(p.state.threads[0]),id:idFn(),attention:true,exchangeProjectId:p.projectId};next.threads.push(t);}
 else for(const r of verified.rows){if(['same','local-only'].includes(r.status))continue;if(!['local','incoming'].includes(choices[r.key]))fail('Choose a value for every changed field.');if(choices[r.key]==='incoming')t[r.key]=copy(r.incoming);}
 if(['WAITING','BLOCKED'].includes(t.state)&&!t.waiting.trim())fail('A waiting or blocked intention needs a reason. Review the state and waiting fields together.');
 const map=new Map(verified.sourceMap.map(([local,remote])=>[remote,local]));
 const counts={sources:0,notes:0,followups:0};
 if(options.evidence===true){for(const src of verified.newRecords.sources){const id=idFn();map.set(src.id,id);next.sources.push({...copy(src),id,threadId:t.id,exchangeOrigin:src.id,importedAt:at});counts.sources++;}
 for(const n of verified.newRecords.notes){const sourceId=map.get(n.sourceId);if(!sourceId)fail('Source required by checkpoint is missing.');next.notes.push({...copy(n),id:idFn(),threadId:t.id,sourceId,reviewState:'pending',exchangeOrigin:n.id,importedAt:at});counts.notes++;}}
 if(options.followups===true){for(const f of verified.newRecords.followups){next.followups.push({...copy(f),id:idFn(),threadId:t.id,exchangeOrigin:f.id,importedAt:at});counts.followups++;}}
 t.exchangeProjectId=p.projectId;t.exchangeBase={fields:fields(p.state.threads[0]),hash:await digest(fields(p.state.threads[0]))};anchor(t,t.exchangeBase);
 t.exchangeSeen=[...(t.exchangeSeen||[]),p.packetId];if(t.exchangeSeen.length>2000)fail('Exchange receipt limit reached. Export a backup before starting a new project copy.');
 if(utf.encode(JSON.stringify(next)).length>15000000)fail('Workspace would exceed the 15 MB portable backup budget.');
 return {state:W.validate(next),threadId:t.id,counts};
}
const api={FORMAT,FIELDS,MAX,canonical,digest,fields,snapshot,makePacket,parse,rememberExport,plan,apply};root.ProjectExchange=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
