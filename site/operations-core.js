/* Operational v0.12 additions. App version is independent of the v0.11 storage schema. */
(function(root){
'use strict';
const W=root.WorkspaceCore||(typeof require==='function'?require('./workspace-core.js'):null);
const APP_VERSION='0.20.1',baseValidate=W.validate,basePack=W.resumePack;
const fail=m=>{throw new Error(m);},copy=W.copy;
const text=(v,n=20000)=>typeof v==='string'&&v.length<=n;
const bytes=s=>new TextEncoder().encode(s).length;
function dateOK(v){if(!/^\d{4}-\d{2}-\d{2}$/.test(v||''))return false;const d=new Date(v+'T12:00:00Z');return !isNaN(d)&&d.toISOString().slice(0,10)===v;}
function today(d=new Date()){return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');}
function validate(input){
 const s=baseValidate(input);s.followups=s.followups??[];
 if(!Array.isArray(s.followups)||s.followups.length>20000)fail('Invalid follow-ups.');
 const ids=new Set(),tids=new Set(s.threads.map(t=>t.id));
 for(const f of s.followups){
  if(!f||!text(f.id,200)||!f.id||ids.has(f.id)||!tids.has(f.threadId)||!text(f.title,200)||!f.title.trim()||!text(f.reason)||!['OPEN','DONE'].includes(f.state)||!['date','resolved'].includes(f.mode)||!text(f.createdAt,100))fail('Invalid follow-up.');
  ids.add(f.id);
  if(f.mode==='date'&&!dateOK(f.date))fail('Choose a real follow-up date.');
  if(f.mode==='resolved'&&!(f.dependencyId===null&&text(f.dependencyLabel,200)&&f.dependencyLabel.trim()||tids.has(f.dependencyId)&&f.dependencyId!==f.threadId))fail('Choose another intention as the dependency.');
 }
 for(const n of s.notes){n.reviewState=n.reviewState??'confirmed';if(!['confirmed','pending'].includes(n.reviewState))fail('Invalid checkpoint review status.');}
 return s;
}
function due(s,f,day=today()){
 if(f.state==='DONE')return {ready:false,label:'Completed'};
 if(f.mode==='date')return {ready:f.date<=day,label:f.date<day?'Overdue since '+f.date:f.date===day?'Due today':'Due '+f.date};
 const d=s.threads.find(t=>t.id===f.dependencyId);
 return {ready:!!d&&d.state==='RESOLVED',label:!d?'Reconnect dependency: '+(f.dependencyLabel||'not included'):d.state==='RESOLVED'?'Dependency resolved: '+d.title:'Waiting for '+d.title};
}
function makeFollowup(s,f,id,at){const x={id,threadId:f.threadId,title:f.title.trim(),reason:(f.reason||'').trim(),state:f.state||'OPEN',mode:f.mode,date:f.mode==='date'?f.date:null,dependencyId:f.mode==='resolved'?(f.dependencyId||null):null,dependencyLabel:f.dependencyLabel||'',createdAt:at};validate({...s,followups:[...(s.followups||[]).filter(v=>v.id!==id),x]});return x;}
function planBatch(s,files){
 if(!Array.isArray(files)||!files.length||files.length>20)fail('Choose between 1 and 20 text or Markdown files.');
 let total=0;const seen=new Set(s.sources.map(x=>x.text));
 return files.map((f,i)=>{
  if(!f||!text(f.name,300)||!text(f.text,1000000))fail('Invalid text file.');
  total+=bytes(f.text);if(total>4000000)fail('This batch exceeds 4 MB. Select fewer files.');
  let problem='';
  if(!/\.(txt|md)$/i.test(f.name))problem='Use .txt or .md';
  else if(bytes(f.text)>1000000)problem='Larger than 1 MB';
  else if(!f.text.trim()||f.text.includes('\0'))problem='Empty or binary content';
  else if(seen.has(f.text))problem='Exact duplicate (already stored or earlier in batch)';
  if(!problem)seen.add(f.text);
  return {index:i,name:f.name,title:f.name.replace(/\.(txt|md)$/i,'').slice(0,200),text:f.text,problem};
 });
}
function applyBatch(s,rows,threadId,speaker,idFn,at){
 if(!['mixed','user','assistant','unknown'].includes(speaker))fail('Choose source attribution.');
 const next=copy(s),added=[];
 for(const row of rows){if(row.problem)fail('A selected file is not importable.');const src=W.makeSource(next,{title:row.title,text:row.text,url:'',speaker,threadId},idFn(),at);src.originalFileName=row.name;next.sources.push(src);added.push(src);}
 if(bytes(JSON.stringify(next))>15000000)fail('Workspace would exceed the portable backup limit. Import fewer files.');
 validate(next);return added;
}
function createProject(s,threadId,id,at){
 s=validate(s);const thread=s.threads.find(t=>t.id===threadId);if(!thread)fail('Intention not found.');
 const notes=s.notes.filter(n=>n.threadId===threadId),sourceIds=new Set(notes.map(n=>n.sourceId));
 const state=W.empty();state.threads=[copy(thread)];state.sources=s.sources.filter(x=>x.threadId===threadId||sourceIds.has(x.id)).map(x=>({...copy(x),threadId}));state.notes=copy(notes);
 state.followups=s.followups.filter(f=>f.threadId===threadId).map(f=>{const out=copy(f);if(out.mode==='resolved'){out.dependencyLabel=s.threads.find(t=>t.id===out.dependencyId)?.title||out.dependencyLabel;out.dependencyId=null;}return out;});
 return {format:'ContinuityProject/1',packetId:id,exportedAt:at,includesOriginalSources:true,state:validate(state)};
}
function parseProject(raw){
 if(typeof raw!=='string'||bytes(raw)>16000000)fail('Project packet exceeds 16 MB.');
 const p=JSON.parse(raw);
 if(p?.format!=='ContinuityProject/1'||!text(p.packetId,200)||!p.packetId||!text(p.exportedAt,100))fail('Unsupported project packet.');
 p.state=validate(p.state);const s=p.state;
 if(s.threads.length!==1||s.captures.length||s.ideas.length||s.rules.length||s.prospective.length||s.events.length)fail('A project packet must contain one intention and only its sources, checkpoints and follow-ups.');
 const tid=s.threads[0].id;
 if(s.sources.some(x=>x.threadId!==tid)||s.notes.some(x=>x.threadId!==tid)||s.followups.some(x=>x.threadId!==tid))fail('Project contains unrelated records.');
 return p;
}
function importProject(s,p,idFn,at){
 p=parseProject(JSON.stringify(p));s=validate(s);
 if(s.threads.some(t=>t.importedPacketId===p.packetId))fail('This packet was already imported. Existing work has not changed.');
 const next=copy(s),newId=idFn(),thread={...copy(p.state.threads[0]),id:newId,title:(p.state.threads[0].title.slice(0,188)+' (imported)'),importedPacketId:p.packetId,importedAt:at,attention:true};
 next.threads.push(thread);const sourceMap=new Map();
 for(const src of p.state.sources){const id=idFn();sourceMap.set(src.id,id);next.sources.push({...copy(src),id,threadId:newId,originSourceId:src.id,importedAt:at});}
 for(const n of p.state.notes)next.notes.push({...copy(n),id:idFn(),sourceId:sourceMap.get(n.sourceId),threadId:newId,reviewState:'pending',originNoteId:n.id,importedAt:at});
 for(const f of p.state.followups)next.followups.push({...copy(f),id:idFn(),threadId:newId,importedAt:at});
 if(bytes(JSON.stringify(next))>15000000)fail('Combined workspace exceeds the portable backup limit.');
 return {state:validate(next),threadId:newId};
}
function resumePack(s,id,at,options={}){
 s=validate(s);const selected=options.noteIds?new Set(options.noteIds):null,filtered=copy(s);
 filtered.notes=s.notes.filter(n=>n.threadId===id&&n.reviewState==='confirmed'&&(!selected||selected.has(n.id)));
 const used=new Set(filtered.notes.map(n=>n.sourceId));filtered.sources=s.sources.filter(x=>used.has(x.id));
 let result=basePack(filtered,id,at);
 const pending=s.notes.filter(n=>n.threadId===id&&n.reviewState==='pending').length;
 if(pending)result+='\n\n## Review boundary\n'+pending+' imported checkpoint(s) await review and were excluded from this handoff.';
 if(options.followups!==false){const fs=s.followups.filter(f=>f.threadId===id&&f.state==='OPEN');result+='\n\n## Open follow-ups\n'+(fs.length?fs.map(f=>'- '+f.title+' — '+due(s,f).label+(f.reason?' | '+f.reason:'')).join('\n'):'None recorded.');}
 return result;
}
function privacyFlags(value){const tests=[['Email-like text',/[\w.+-]+@[\w.-]+\.[a-z]{2,}/i],['Possible API token',/\b(?:sk-|ghp_|github_pat_)[a-z\d_-]{12,}/i],['Private-key block',/-----BEGIN [\w ]*PRIVATE KEY-----/],['Potential sensitive keywords',/\b(password|social security|diagnosis|medical record|account number)\b/i]];return tests.filter(([,re])=>re.test(value)).map(([name])=>name);}
W.validate=validate;W.resumePack=resumePack;
const api={APP_VERSION,dateOK,today,validate,due,makeFollowup,planBatch,applyBatch,createProject,parseProject,importProject,resumePack,privacyFlags};
root.OperationalCore=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
