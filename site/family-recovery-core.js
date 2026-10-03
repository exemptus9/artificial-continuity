/* Rescue files preserve evidence. They never execute consent or replace a workspace. */
(function(root){
'use strict';
const FORMAT='ContinuityRecovery/1',MAX_FILE=16000000,MAX_DRAFTS=1000,MAX_DEPTH=30;
const encoder=new TextEncoder(),fail=m=>{throw new Error(m);};
const size=s=>encoder.encode(s).length;
const plain=x=>x!==null&&typeof x==='object'&&!Array.isArray(x)&&(Object.getPrototypeOf(x)===Object.prototype||Object.getPrototypeOf(x)===null);
const copy=x=>JSON.parse(JSON.stringify(x));
const text=(v,max)=>typeof v==='string'&&v.length<=max;
function safe(value){
 let nodes=0;const seen=new Set();
 function visit(v,depth){
  if(++nodes>250000||depth>MAX_DEPTH)fail('Recovery structure is too large or deeply nested.');
  if(v===null||typeof v==='boolean'||typeof v==='string')return;
  if(typeof v==='number'){if(!Number.isFinite(v))fail('Non-finite recovery value.');return;}
  if(typeof v!=='object'||(!Array.isArray(v)&&!plain(v))||seen.has(v))fail('Recovery must contain plain JSON data.');
  seen.add(v);
  for(const k of Object.keys(v)){
   if(['__proto__','prototype','constructor'].includes(k))fail('Unsafe property in recovery file.');
   const d=Object.getOwnPropertyDescriptor(v,k);if(!d||!('value' in d))fail('Recovery must contain data properties.');
   visit(d.value,depth+1);
  }
  seen.delete(v);
 }
 visit(value,0);
}
function keys(value,expected,label){if(!plain(value)||Object.keys(value).sort().join('|')!==expected.slice().sort().join('|'))fail('Invalid '+label+' fields.');}
function validStamp(x){return text(x,100)&&!Number.isNaN(Date.parse(x));}
function validate(value){
 safe(value);const raw=JSON.stringify(value);if(size(raw)>MAX_FILE)fail('Recovery file exceeds 16 MB. No content was omitted or saved.');
 keys(value,['format','exportedAt','origin','appVersion','workspaceRevision','workspace','drafts','incoming','snapshot'],'recovery');
 if(value.format!==FORMAT||!validStamp(value.exportedAt)||!text(value.appVersion,100)||!value.appVersion||!text(value.origin,2000))fail('Unsupported recovery metadata.');
 try{const u=new URL(value.origin);if(!['http:','https:'].includes(u.protocol)||u.origin!==value.origin)fail('Invalid recovery origin.');}catch{fail('Recovery origin must be an HTTP(S) origin.');}
 if(!Number.isSafeInteger(value.workspaceRevision)||value.workspaceRevision<1)fail('Invalid workspace revision.');
 keys(value.snapshot,['workspaceAndDrafts','incoming','atomicAcrossDatabases','scope'],'snapshot');
 if(value.snapshot.workspaceAndDrafts!=='single-readonly-transaction'||value.snapshot.incoming!=='separate-inbox-read'||value.snapshot.atomicAcrossDatabases!==false||value.snapshot.scope!=='saved-local-records-only')fail('Unsupported snapshot boundary.');
 keys(value.workspace,['format','exportedAt','state'],'embedded workspace');
 if(value.workspace.format!=='ContinuityBackup/2'||value.workspace.exportedAt!==value.exportedAt)fail('Invalid embedded workspace backup.');
 // Validate without replacing the original with a normalized/migrated copy.
 root.WorkspaceCore.backup(JSON.stringify(value.workspace));
 if(!Array.isArray(value.drafts)||value.drafts.length>MAX_DRAFTS)fail('Too many recovery drafts (maximum 1,000).');
 const seen=new Set();for(const r of value.drafts){
  keys(r,['key','value'],'draft');if(!text(r.key,500)||!r.key||seen.has(r.key)||!plain(r.value))fail('Invalid or duplicate draft key.');seen.add(r.key);
  if(Object.keys(r.value).length>200||size(JSON.stringify(r.value))>4000000)fail('Draft exceeds rescue limits.');
 }
 if(!Array.isArray(value.incoming)||value.incoming.length>25)fail('Too many pending inbox items.');
 const incoming=new Set();for(const packet of value.incoming){root.PlatformCore.validate(packet);if(packet.status!=='PENDING'||incoming.has(packet.id))fail('Recovery inbox must contain unique pending items.');incoming.add(packet.id);}
 return copy(value);
}
function parse(raw){if(typeof raw!=='string'||size(raw)>MAX_FILE)fail('Recovery file exceeds 16 MB.');let value;try{value=JSON.parse(raw);}catch{fail('Not a valid JSON recovery file.');}return validate(value);}
function make(snapshot,incoming,meta){
 if(!Array.isArray(incoming)||incoming.length>25)fail('Incoming inbox exceeds rescue limits.');
 for(const packet of incoming){safe(packet);root.PlatformCore.validate(packet);}
 return validate({format:FORMAT,exportedAt:meta.exportedAt,origin:meta.origin,appVersion:meta.appVersion,workspaceRevision:snapshot.current.revision,
  workspace:{format:'ContinuityBackup/2',exportedAt:meta.exportedAt,state:snapshot.current.state},drafts:snapshot.drafts,incoming:incoming.filter(x=>x.status==='PENDING'),
  snapshot:{workspaceAndDrafts:'single-readonly-transaction',incoming:'separate-inbox-read',atomicAcrossDatabases:false,scope:'saved-local-records-only'}});
}
function readSnapshot(db,expectedRevision){return new Promise((resolve,reject)=>{
 let current,error;const drafts=[],tx=db.transaction(['workspace','drafts'],'readonly'),q=tx.objectStore('workspace').get('current');
 q.onsuccess=()=>{current=q.result;if(!current||current.revision!==expectedRevision){error=new Error('Workspace revision changed. Load the newer saved version, then export again.');tx.abort();}};
 const cursor=tx.objectStore('drafts').openCursor();cursor.onsuccess=()=>{const c=cursor.result;if(c){drafts.push({key:c.key,value:c.value});if(drafts.length>MAX_DRAFTS){error=new Error('More than 1,000 drafts: download smaller individual drafts before rescue export.');tx.abort();return;}c.continue();}};
 tx.oncomplete=()=>resolve({current,drafts});tx.onabort=()=>reject(error||tx.error||new Error('Rescue snapshot read aborted.'));tx.onerror=()=>{};
 });}
function readRevision(db){return new Promise((resolve,reject)=>{const tx=db.transaction('workspace','readonly'),q=tx.objectStore('workspace').get('current');let result;q.onsuccess=()=>result=q.result?.revision;tx.oncomplete=()=>resolve(result);tx.onabort=()=>reject(tx.error||new Error('Revision check aborted.'));tx.onerror=()=>{};});}
async function collect(db,listInbox,expectedRevision,meta){
 const snapshot=await readSnapshot(db,expectedRevision),incoming=await listInbox();
 if(await readRevision(db)!==expectedRevision)fail('Workspace changed while reading the separate inbox. Export again after loading the newer version.');
 return make(snapshot,incoming,meta);
}
function canonical(value){if(value===null||typeof value!=='object')return JSON.stringify(value);if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical(value[k])).join(',')+'}';}
async function digest(value){if(!root.crypto?.subtle)fail('Web Crypto is unavailable. Use HTTPS or localhost.');return [...new Uint8Array(await root.crypto.subtle.digest('SHA-256',encoder.encode(canonical(value))))].map(x=>x.toString(16).padStart(2,'0')).join('');}
function draftBody(v){
 if(typeof v.sourceRawText==='string'&&typeof v.sourceText==='string'&&v.sourceRawText.replace(/\r\n|\r/g,'\n')===v.sourceText.replace(/\r\n|\r/g,'\n'))return v.sourceRawText;
 if(typeof v.captureRawText==='string'&&typeof v.text==='string'&&v.captureRawText.replace(/\r\n|\r/g,'\n')===v.text.replace(/\r\n|\r/g,'\n'))return v.captureRawText;
 for(const k of ['sourceText','text','statement','principle','workProgress','intakeText'])if(typeof v[k]==='string'&&v[k].length)return v[k];
 return canonical(v);
}
async function plan(input){
 const bundle=validate(input),hash=await digest(bundle),rows=[];
 const common={recoveryFormat:FORMAT,recoveryDigest:hash,recoveryOrigin:bundle.origin,recoveryExportedAt:bundle.exportedAt};
 for(const [i,r] of bundle.drafts.entries()){
  const value={sourceTitle:('Recovered draft · '+(r.value.sourceTitle||r.value.title||r.key)).slice(0,200),sourceText:draftBody(r.value),sourceThread:'',sourceSpeaker:'unknown',sourceURL:'',...common,
   recoveryKind:'unfinished-draft',recoveryEvidenceJSON:canonical(r)};
  rows.push({key:'rescue-family:'+hash+':draft:'+i,value});
 }
 for(const [i,packet] of bundle.incoming.entries())for(const [j,p] of packet.parts.entries()){
  rows.push({key:'rescue-family:'+hash+':incoming:'+i+':'+j,value:{sourceTitle:('Recovered incoming · '+p.title).slice(0,200),sourceText:p.text,sourceThread:'',sourceSpeaker:'unknown',sourceURL:p.url,...common,
   recoveryKind:'unreviewed-incoming',recoveryEvidenceJSON:canonical({id:packet.id,at:packet.at,status:'PENDING',partIndex:j,part:p})}});
 }
 return {digest:hash,bundle,rows};
}
// Existing artifacts are compared inside the same transaction as every add.
// A conflict or quota failure aborts the entire transaction; nothing is overwritten.
async function restore(db,input){
 const prepared=await plan(input);
 return new Promise((resolve,reject)=>{
  const tx=db.transaction('drafts','readwrite'),os=tx.objectStore('drafts');let added=0,skipped=0,error;
  for(const row of prepared.rows){const q=os.get(row.key);q.onsuccess=()=>{
   if(q.result===undefined){os.add(row.value,row.key);added++;}
   else if(canonical(q.result)===canonical(row.value))skipped++;
   else{error=new Error('A rescue key contains changed content. Nothing was restored; existing drafts were preserved.');tx.abort();}
  };}
  tx.oncomplete=()=>resolve({added,skipped,digest:prepared.digest});tx.onabort=()=>reject(error||tx.error||new Error('Draft rescue failed. No draft was replaced.'));tx.onerror=()=>{};
 });
}
root.FamilyRecoveryCore={FORMAT,MAX_FILE,MAX_DRAFTS,validate,parse,make,readSnapshot,collect,plan,restore,canonical,draftBody};
if(typeof module!=='undefined')module.exports=root.FamilyRecoveryCore;
})(globalThis);
