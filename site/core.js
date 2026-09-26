/* Pure data operations. Works in the browser and Node; no network or model calls. */
(function(root){
'use strict';
const STATES=['CONTINUE','PROCESSING','WAITING','BLOCKED','RESOLVED','REFERENCE','ARCHIVED'];
const copy=x=>JSON.parse(JSON.stringify(x));
function check(input){
 const x=copy(input);
 const fail=m=>{throw new Error(m)};
 const text=(v,n=20000)=>typeof v==='string'&&v.length<=n;
 if(!x||Array.isArray(x)||!['0.9.0','0.10.0'].includes(x.version))fail('Unsupported workspace version.');
 for(const k of ['threads','captures','ideas','prospective','rules','events']){
  if(!Array.isArray(x[k])||x[k].length>20000)fail('Invalid '+k+' collection.');
  const ids=new Set();
  for(const r of x[k]){
   if(!r||typeof r!=='object'||!text(r.id,200)||!r.id||ids.has(r.id))fail('Missing or duplicate ID in '+k+'.');
   ids.add(r.id);
  }
 }
 for(const t of x.threads){
  if(!text(t.title,200)||!t.title.trim()||!STATES.includes(t.state)||!['objective','last','next'].every(k=>text(t[k]))||!Array.isArray(t.open)||!t.open.every(v=>text(v,2000))||!(t.waiting==null||text(t.waiting)))fail('Invalid intention.');
  t.attention=t.attention===true;
 }
 for(const c of x.captures)if(!text(c.text)||!text(c.type,60)||!text(c.context)||!text(c.at,100)||!['UNPROCESSED','DEVELOPED','ARCHIVED'].includes(c.status))fail('Invalid capture.');
 for(const i of x.ideas)if(!text(i.title,300)||!text(i.principle)||!Array.isArray(i.evidence)||!i.evidence.every(v=>text(v,200))||!text(i.status,60))fail('Invalid idea.');
 for(const r of x.rules)if(!text(r.name,200)||!text(r.text)||!['HARD','ADVISORY'].includes(r.level))fail('Invalid rule.');
 for(const p of x.prospective)if(!text(p.title,300)||!text(p.reason)||!['ARMED','FIRED'].includes(p.status)||p.condition?.type!=='evidence_count'||!Number.isFinite(p.condition.threshold)||p.condition.threshold<0)fail('Invalid prospective-memory example.');
 for(const e of x.events)if(!text(e.type,100)||!text(e.reason)||!text(e.at,100))fail('Invalid timeline event.');
 if(!Number.isFinite(x.evidenceCount)||x.evidenceCount<0)fail('Invalid evidence counter.');
 x.version='0.10.0';
 x.draft=x.draft&&text(x.draft.text)&&text(x.draft.context)&&text(x.draft.type,60)?x.draft:{type:'friction',text:'',context:''};
 // Sync Lab is a simulation, not imported executable instructions.
 x.sync={diverged:x.sync?.diverged===true,res:{},adopted:null};
 return x;
}
function parseBackup(raw){
 if(typeof raw!=='string'||raw.length>4000000)throw new Error('Backup must be smaller than 4 MB.');
 const parsed=JSON.parse(raw);
 if(parsed?.format&&parsed.format!=='ContinuityBackup/1')throw new Error('Unsupported backup format.');
 return check(parsed?.format==='ContinuityBackup/1'?parsed.state:parsed);
}
function search(s,q){
 const skip=new Set(['the','a','an','that','my','about','find','me','i','was','on','in','to','and','of']);
 const terms=(q.toLowerCase().match(/[\p{L}\p{N}]+/gu)||[]).filter(t=>!skip.has(t));
 if(!terms.length)return [];
 const rows=[...s.threads.map(x=>({kind:'Intention',id:x.id,title:x.title,body:[x.objective,x.last,x.next,...x.open,x.waiting||''].join(' ')})),...s.captures.map(x=>({kind:'Capture',id:x.id,title:x.context||x.type,body:x.text})),...s.ideas.map(x=>({kind:'Idea',id:x.id,title:x.title,body:x.principle}))];
 return rows.filter(r=>terms.every(t=>(r.title+' '+r.body).toLowerCase().includes(t))).slice(0,50);
}
root.ContinuityCore={STATES,copy,check,parseBackup,search};
if(typeof module!=='undefined')module.exports=root.ContinuityCore;
})(typeof globalThis!=='undefined'?globalThis:this);
