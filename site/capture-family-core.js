/* A source-preserving capture adapter for the existing Continuity workspace. */
(function(root){
'use strict';
const W=root.WorkspaceCore||(typeof require==='function'?require('./workspace-core.js'):null);
const APP_VERSION='0.18.0',FORMAT='ContinuityCaptureSource/1',PLAN='ContinuityCapturePlan/1';
const METHODS=['text','manual-transcript','intent-observation'],SPEAKERS=['unknown','user','assistant','mixed'];
const baseValidate=W.validate,baseBackup=W.backup,copy=W.copy;
const fail=m=>{throw new Error(m);};
const text=(v,n=20000)=>typeof v==='string'&&v.length<=n;
const tokenOK=v=>typeof v==='string'&&/^[a-zA-Z0-9_-]{1,100}$/.test(v);
function timestamp(v){
 if(typeof v!=='string'||v.length>100)return false;
 const m=v.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?(?:Z|[+-](\d{2}):(\d{2}))$/);if(!m)return false;
 const y=Number(m[1]),month=Number(m[2]),day=Number(m[3]),days=[31,(y%4===0&&(y%100!==0||y%400===0))?29:28,31,30,31,30,31,31,30,31,30,31];
 return month>=1&&month<=12&&day>=1&&day<=days[month-1]&&Number(m[4])<24&&Number(m[5])<60&&Number(m[6]||0)<60&&Number(m[7]||0)<24&&Number(m[8]||0)<60&&Number.isFinite(Date.parse(v));
}
function fields(input){
 if(!input||!text(input.text)||!input.text.trim()||input.text.includes('\0'))fail('Enter nonempty text, up to 20,000 characters.');
 if(!text(input.type||'note',60)||!text(input.context||'',200))fail('Capture type or context is too long.');
 const method=input.method||'text',speaker=input.speaker||'unknown',observedAt=input.observedAt||null,inputBasis=input.inputBasis||'browser-editor-text';
 if(!METHODS.includes(method)||!SPEAKERS.includes(speaker))fail('Choose a supported capture method and attribution.');
 if(observedAt!==null&&!timestamp(observedAt))fail('Observation time must include a date, time and timezone, for example 2026-10-03T14:30:00-04:00.');
 if(!['browser-editor-text','clipboard-text/plain','edited-clipboard-text'].includes(inputBasis))fail('Invalid text provenance.');
 return {text:input.text,type:input.type||'note',context:input.context||'',method,speaker,observedAt,inputBasis};
}
function prepare(input,token,at){
 const f=fields(input);if(!tokenOK(token)||!timestamp(at))fail('Invalid capture token or capture timestamp.');
 const sourceId='capture-source-'+token,captureId='capture-'+token;
 const source={id:sourceId,title:(f.context.trim()||f.type+' capture').slice(0,200),text:f.text,url:'',speaker:f.speaker,threadId:null,createdAt:at,category:'capture-original',captureOrigin:{format:FORMAT,sourceVersion:1,appVersion:APP_VERSION,method:f.method,observedAt:f.observedAt,receivedAt:at,inputBasis:f.inputBasis,reviewState:'unreviewed',consentRecord:{scope:'local-original-and-capture',confirmedAt:at}}};
 const capture={id:captureId,type:f.type,text:f.text,context:f.context,at,status:'UNPROCESSED',sourceId,sourceVersion:1,captureToken:token,reviewState:'unreviewed'};
 return {format:PLAN,fields:f,token,at,source,capture};
}
function validate(input){
 const s=baseValidate(input),sources=new Map(s.sources.map(x=>[x.id,x])),tokens=new Set();
 for(const src of s.sources){
  const p=src.captureOrigin;if(p===undefined)continue;
  if(!p||p.format!==FORMAT||p.sourceVersion!==1||p.appVersion!==APP_VERSION||!METHODS.includes(p.method)||!timestamp(p.receivedAt)||(p.observedAt!==null&&!timestamp(p.observedAt))||!['browser-editor-text','clipboard-text/plain','edited-clipboard-text'].includes(p.inputBasis)||p.reviewState!=='unreviewed'||p.consentRecord?.scope!=='local-original-and-capture'||!timestamp(p.consentRecord?.confirmedAt))fail('Invalid capture source provenance.');
 }
 for(const c of s.captures){
  if(c.captureToken===undefined)continue;
  const src=sources.get(c.sourceId);
  if(!tokenOK(c.captureToken)||tokens.has(c.captureToken)||!src||src.text!==c.text||src.captureOrigin?.format!==FORMAT||c.sourceVersion!==src.captureOrigin.sourceVersion||c.reviewState!=='unreviewed')fail('Invalid capture source link or duplicate capture token.');
  tokens.add(c.captureToken);
 }
 return s;
}
function sameCapture(saved,source,plan){
 const f=plan.fields,p=source?.captureOrigin;
 return saved.text===f.text&&saved.type===f.type&&saved.context===f.context&&source?.text===f.text&&source?.speaker===f.speaker&&p?.method===f.method&&p?.observedAt===f.observedAt&&p?.inputBasis===f.inputBasis;
}
function apply(state,plan,consent){
 if(consent!==true)fail('Confirm local saving after reviewing this capture. Consent is never restored from a draft.');
 if(plan?.format!==PLAN)fail('Prepare the capture again.');
 const expected=prepare(plan.fields,plan.token,plan.at);
 if(JSON.stringify(expected)!==JSON.stringify(plan))fail('The capture preview changed. Prepare it again.');
 const next=validate(state),saved=next.captures.find(c=>c.captureToken===plan.token);
 if(saved){
  if(!sameCapture(saved,next.sources.find(x=>x.id===saved.sourceId),plan))fail('This capture token already saved different text. Start a new capture; the original is retained.');
  return {state:next,sourceId:saved.sourceId,captureId:saved.id,alreadySaved:true};
 }
 if(next.sources.some(x=>x.id===plan.source.id)||next.captures.some(x=>x.id===plan.capture.id))fail('Capture ID collision. Nothing was replaced.');
 next.sources.push(copy(plan.source));next.captures.push(copy(plan.capture));
 if(next.draft)next.draft={type:'friction',text:'',context:''};
 if(new TextEncoder().encode(JSON.stringify(next)).length>15000000)fail('Workspace would exceed the portable backup limit. Export and reduce it first.');
 return {state:validate(next),sourceId:plan.source.id,captureId:plan.capture.id,alreadySaved:false};
}
// HTML textareas expose LF endings. Keep the plain-text paste separately and map
// edits into it so untouched CRLF/CR endings remain exact in the saved source.
function editorText(raw){return raw.replace(/\r\n|\r/g,'\n');}
function rawOffset(raw,offset){let visible=0,i=0;while(i<raw.length&&visible<offset){if(raw[i]==='\r'&&raw[i+1]==='\n')i++;i++;visible++;}return i;}
function spliceText(raw,start,end,insertion){return raw.slice(0,rawOffset(raw,start))+insertion+raw.slice(rawOffset(raw,end));}
function reconcileText(raw,edited){
 const old=editorText(raw);if(old===edited)return raw;
 let first=0,last=0;while(first<old.length&&first<edited.length&&old[first]===edited[first])first++;
 while(last<old.length-first&&last<edited.length-first&&old[old.length-1-last]===edited[edited.length-1-last])last++;
 return spliceText(raw,first,old.length-last,edited.slice(first,edited.length-last));
}
function stableAction(start,end){
 if(!start||!end||start.target!==end.target||start.pointerId!==end.pointerId)return false;
 return ['left','top','width','height'].every(k=>Number.isFinite(start.rect?.[k])&&Number.isFinite(end.rect?.[k])&&Math.abs(start.rect[k]-end.rect[k])<=0.5);
}
function backup(raw){return validate(baseBackup(raw));}
W.validate=validate;W.backup=backup;
const api={APP_VERSION,FORMAT,METHODS,fields,prepare,apply,validate,sameCapture,editorText,spliceText,reconcileText,stableAction};
root.CaptureFamilyCore=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
