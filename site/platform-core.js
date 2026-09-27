/* Shared-input validation; no network or workspace mutation. Used by app, worker and tests. */
(function(root){
'use strict';
const MAX_TEXT=1000000,MAX_TOTAL=4000000,MAX_FILES=10,MAX_QUEUE=25;
const size=s=>new TextEncoder().encode(s).length;
const fail=m=>{throw new Error(m);};
function safeURL(value){if(!value)return '';try{const u=new URL(value);if(!['https:','http:'].includes(u.protocol)||u.username||u.password)fail('Use an HTTP(S) URL without embedded credentials.');return u.href;}catch{fail('The shared URL is not a safe HTTP(S) reference. It was not opened.');}}
function prepare(input,id,at){
 if(!input||typeof input!=='object')fail('Shared input is missing.');
 if(typeof id!=='string'||!/^[a-zA-Z0-9-]{1,100}$/.test(id))fail('Invalid incoming ID.');
 const t=(s,label,max)=>{if(typeof s!=='string'||size(s)>max||s.includes('\0'))fail(label+' is invalid or too large.');return s;};
 const title=t(input.title||'','Shared title',2000),text=t(input.text||'','Shared text',MAX_TEXT),url=safeURL(t(input.url||'','Shared URL',2000));
 const files=input.files||[];if(!Array.isArray(files)||files.length>MAX_FILES)fail('Share at most '+MAX_FILES+' text files at once.');
 const parts=[];if(text.trim())parts.push({title:title.trim().slice(0,200)||'Shared text',text,url});
 else if(url)parts.push({title:title.trim().slice(0,200)||'Shared link',text:url,url});
 for(const f of files){
  if(!f||typeof f.name!=='string'||!/\.(txt|md)$/i.test(f.name))fail('Only UTF-8 .txt and .md files are supported; no PDF or image import.');
  t(f.name,'Filename',2000);const body=t(f.text,'File text',MAX_TEXT);if(!body.trim())fail('A shared file is empty.');
  parts.push({title:f.name.slice(0,200),text:body,url:'',filename:f.name});
 }
 if(!parts.length&&title.trim())parts.push({title:title.trim().slice(0,200),text:title,url:''});
 if(!parts.length)fail('No text, URL or supported file was shared.');
 const bytes=parts.reduce((n,p)=>n+size(p.text)+size(p.title)+size(p.url),0);if(bytes>MAX_TOTAL)fail('Shared content exceeds 4 MB. Split it into smaller selections.');
 return {format:'ContinuityIncoming/1',id,at,status:'PENDING',parts,bytes};
}
function validate(packet){
 if(!packet||packet.format!=='ContinuityIncoming/1'||!Array.isArray(packet.parts)||packet.parts.length>MAX_FILES+1||!['PENDING','SAVED'].includes(packet.status)||typeof packet.at!=='string')fail('Invalid incoming packet.');
 if(typeof packet.id!=='string'||!/^[a-zA-Z0-9-]{1,100}$/.test(packet.id))fail('Invalid incoming packet ID.');
 let bytes=0;for(const p of packet.parts){if(!p||typeof p.title!=='string'||!p.title.trim()||p.title.length>200||typeof p.text!=='string'||!p.text.trim()||p.text.includes('\0')||size(p.text)>MAX_TEXT||typeof p.url!=='string')fail('Invalid incoming part.');safeURL(p.url);bytes+=size(p.text)+size(p.title)+size(p.url);}
 if(!packet.parts.length||bytes>MAX_TOTAL)fail('Invalid incoming size.');return packet;
}
function importInto(state,packet,mode,threadId,makeSource,newId,at){
 validate(packet);if(!['source','capture'].includes(mode))fail('Choose how to save the incoming text.');
 if(threadId&&!state.threads.some(t=>t.id===threadId))fail('Selected intention no longer exists.');
 const marker='Incoming share '+packet.id;
 if(state.events.some(e=>e.type==='SHARED_CONTENT_SAVED'&&e.reason===marker))fail('This incoming share is already saved in this workspace.');
 const result=JSON.parse(JSON.stringify(state));let added=0,skipped=0;
 for(const p of packet.parts){
  if(mode==='source'){
   if(result.sources.some(s=>s.text===p.text)){skipped++;continue;}
   const s=makeSource(result,{title:p.title,text:p.text,url:p.url,threadId:threadId||null,speaker:'unknown'},newId(),at);s.incomingShareId=packet.id;result.sources.push(s);added++;
  }else{
   if(p.text.length>20000)fail('This text is too long for a quick capture. Save as original sources instead.');
   if(result.captures.some(c=>c.text===p.text)){skipped++;continue;}
   result.captures.push({id:newId(),type:'note',text:p.text,context:p.title+(p.url?' · '+p.url:''),at,status:'UNPROCESSED',incomingShareId:packet.id});added++;
  }
 }
 return {state:result,added,skipped,reason:marker};
}
root.PlatformCore={MAX_TEXT,MAX_TOTAL,MAX_FILES,MAX_QUEUE,size,safeURL,prepare,validate,importInto};
if(typeof module!=='undefined')module.exports=root.PlatformCore;
})(globalThis);
