/* Private Messenger projections. Raw originals remain in the existing object vault. */
(function(root){
'use strict';
const A=root.ArchiveCore||(typeof require==='function'?require('./archive-core.js'):null);
const FORMAT='ContinuityLivingArchive/1', MAX_BYTES=12000000, MAX_RECORDS=20000;
// Effective consent is session-local. Serialized/imported reviews are inert until
// a fresh owner-facing review creates a new object through makeReview.
const authorizedReviews=new WeakSet();
const copy=x=>JSON.parse(JSON.stringify(x)), fail=m=>{throw new Error(m);};
const string=(x,max=1000000)=>typeof x==='string'&&x.length<=max;
const sha=x=>typeof x==='string'&&/^[a-f0-9]{64}$/i.test(x);
const list=x=>Array.isArray(x)?copy(x):[];
const canonical=x=>JSON.stringify(x&&typeof x==='object'?Array.isArray(x)?x.map(v=>JSON.parse(canonical(v))):Object.fromEntries(Object.keys(x).sort().map(k=>[k,JSON.parse(canonical(x[k]))])):x);
function safeValue(value){const pending=[[value,0]];while(pending.length){const [x,depth]=pending.pop();if(depth>32)fail('Projection nesting limit exceeded.');if(typeof x==='string'&&new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(new TextEncoder().encode(x))!==x)fail('Invalid Unicode in message metadata.');if(x&&typeof x==='object')for(const [key,v] of Object.entries(x)){if(key==='__proto__'||key==='constructor'||key==='prototype')fail('Unsafe projection metadata key.');pending.push([v,depth+1]);}}}
function validDay(value){return typeof value==='string'&&/^\d{4}-\d\d-\d\d$/.test(value)&&Number.isFinite(Date.parse(value))&&new Date(value).toISOString().slice(0,10)===value;}
function dateLabel(record){const value=record.localTimestamp||record.observedAt;return value&&validDay(value.slice(0,10))?value.slice(0,10):null;}
function timestamp(value,local=false){
 if(value===null||value===undefined||value==='')return null;
 if(!string(value,100)||!/^\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d(?:\.\d+)?)?/.test(value)||!validDay(value.slice(0,10)))return null;
 if(local&&!/(?:Z|[+-]\d\d:\d\d)$/.test(value))return Number.isFinite(Date.parse(value+'Z'))?value:null;
 return /(?:Z|[+-]\d\d:\d\d)$/.test(value)&&Number.isFinite(Date.parse(value))?value:null;
}
function normalize(value,line){
 if(!value||typeof value!=='object'||Array.isArray(value))fail('JSONL line '+line+' must be an object.');
 if(!string(value.body)||value.body.includes('\0'))fail('JSONL line '+line+' needs a bounded text body.');
 const bodyHash=A.hash(value.body);
 if(value.body_sha256!==undefined&&(!sha(value.body_sha256)||value.body_sha256.toLowerCase()!==bodyHash))fail('Message body checksum mismatch on line '+line+'.');
 const sourceHash=value.source_file_sha256||value.source_sha256||value.origin?.source_file_sha256||null;
 if(sourceHash!==null&&!sha(sourceHash))fail('Invalid source checksum on line '+line+'.');
 const id=value.id||value.source_id||value.message_id;
 if(!string(id,250)||!id.trim())fail('JSONL line '+line+' needs its preserved stable message ID.');
 const sender=string(value.sender,500)?value.sender:'unknown';
 let authorship=string(value.authorship,100)?value.authorship:'unverified';
 if(/^meta ai$/i.test(sender.trim())||/^(?:bot|ai[_ -]?generated|model[_ -]?generated)$/i.test(authorship)||value.model_generated===true||value.model_generated_content===true)authorship='AI_GENERATED';
 const flags=list(value.contamination_flags).filter(x=>string(x,500));
 if(authorship==='AI_GENERATED'&&!flags.includes('AI_GENERATED'))flags.push('AI_GENERATED');
 const original=string(value.timestamp_original,200)?value.timestamp_original:string(value.date_exported,200)?value.date_exported:null;
 const local=timestamp(value.datetime_local_unspecified_zone,true);
 const observed=timestamp(value.observed_at);
 const ordinal=value.source_ordinal===undefined?null:value.source_ordinal;
 if(ordinal!==null&&(!Number.isInteger(ordinal)||ordinal<1))fail('Invalid source ordinal on line '+line+'.');
 return {id,threadTitle:string(value.thread_title,500)?value.thread_title:'Unlabeled historical conversation',
  conversationId:string(value.conversation_id,250)?value.conversation_id:null,sender,body:value.body,bodySha256:bodyHash,
  sourceHash:sourceHash?.toLowerCase()||null,sourceFile:string(value.source_file,1000)?value.source_file:null,
  sourceOrdinal:ordinal,observedAt:observed,localTimestamp:local,timestampOriginal:original,
  timezoneProvenance:string(value.timezone_provenance,500)?value.timezone_provenance:local?'not-recorded':'unknown',
  authorship,classification:string(value.classification,100)?value.classification:'message',
  contaminationFlags:flags,links:list(value.links),attachments:list(value.attachments||value.embedded_media_refs),
  reactions:list(value.reactions),replyTo:value.reply_to??null,privacy:'private',publicationApproved:false,
  styleEligible:false,projectionLine:line,projectionSha256:A.hash(canonical(value)),raw:copy(value)};
}
function parseJSONL(raw,options={}){
 if(!string(raw,MAX_BYTES)||new TextEncoder().encode(raw).length>(options.maxBytes||MAX_BYTES))fail('Projection exceeds the browser import limit. Use the offline adapter.');
 // UTF-8 round-trip rejects unpaired surrogates rather than silently replacing them.
 if(new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(new TextEncoder().encode(raw))!==raw)fail('Invalid Unicode in projection.');
 const rows=raw.replace(/^\uFEFF/,'').split(/\r?\n/),records=[],seen=new Map();let duplicates=0;
 if(rows.length>(options.maxRecords||MAX_RECORDS)*3)fail('Too many projection lines.');
 for(let i=0;i<rows.length;i++){
  if(!rows[i].trim())continue;
  let value;try{value=JSON.parse(rows[i]);}catch{fail('Malformed JSON on line '+(i+1)+'. No partial import.');}
  safeValue(value);const record=normalize(value,i+1),prior=seen.get(record.id);
  if(prior){if(prior.projectionSha256!==record.projectionSha256)fail('Conflicting witnesses reuse message ID '+record.id+'.');duplicates++;continue;}
  records.push(record);seen.set(record.id,record);
  if(records.length>(options.maxRecords||MAX_RECORDS))fail('Too many message records. Use a scoped projection.');
 }
 if(!records.length)fail('No message records found.');
 return {format:FORMAT,sha256:A.hash(raw),records,duplicates,warnings:[
  'Imported JSONL is a derivative; retained HTML/JSON originals establish exact source structure.',
  'Body checksums checked where provided. Original-file hashes are references, not verification of unavailable original bytes.',
  'Imported publication and style permissions are ignored. Historical assertions are not current facts.',
  'Naive historical timestamps retain their recorded calendar labels; their timezone is unknown.'
 ]};
}
function validReview(review){
 if(!review||review.format!=='ContinuityMessengerReview/1'||typeof review.id!=='string'||!string(review.messageId,250)||
  !sha(review.projectionSha256)||!sha(review.bodySha256)||!Number.isInteger(review.version)||review.version<1||
  !timestamp(review.reviewedAt)||typeof review.allowStyle!=='boolean'||typeof review.excluded!=='boolean'||
  !['owner_original','quoted_or_copied','AI_GENERATED','third_party','uncertain'].includes(review.authorship)||
  !Array.isArray(review.projectIds)||review.projectIds.some(x=>!string(x,200)||!x.trim())||
  !string(review.reason,2000)||!review.reason.trim()||review.publicationApproved!==false||
  typeof review.contaminationResolved!=='boolean'||review.version===1&&review.supersedes!==null||
  review.version>1&&!string(review.supersedes,100))return false;
 const payload={...review};delete payload.id;
 return review.id==='MESSENGER.REVIEW.'+A.hash(canonical(payload));
}
function latestReviews(reviews=[]){
 const groups=new Map(),out=new Map();
 for(const review of reviews)if(validReview(review)){const group=groups.get(review.messageId)||new Map(),prior=group.get(review.id);if(!prior||authorizedReviews.has(review))group.set(review.id,review);groups.set(review.messageId,group);}
 for(const [messageId,group] of groups){
  const byVersion=new Map();let valid=true;
  for(const review of group.values()){
   if(byVersion.has(review.version)){valid=false;break;}byVersion.set(review.version,review);
   if(review.version>1){const previous=group.get(review.supersedes);if(!previous||previous.version!==review.version-1||previous.projectionSha256!==review.projectionSha256||previous.bodySha256!==review.bodySha256){valid=false;break;}}
  }
  if(!valid)continue;
  const latest=[...group.values()].sort((a,b)=>b.version-a.version)[0];
  if(latest&&authorizedReviews.has(latest))out.set(messageId,latest);
 }
 return out;
}
function matchedReview(record,map){const review=map.get(record.id);return review&&review.projectionSha256===record.projectionSha256&&review.bodySha256===record.bodySha256?review:null;}
function reviewFor(record,reviews=[]){return matchedReview(record,latestReviews(reviews));}
function summarize(records){
 const dated=records.map(dateLabel).filter(Boolean).sort(),count=key=>Object.fromEntries([...records.reduce((m,r)=>m.set(r[key],(m.get(r[key])||0)+1),new Map())].sort((a,b)=>a[0].localeCompare(b[0])));
 return {messages:records.length,conversations:new Set(records.map(r=>r.conversationId||r.threadTitle)).size,
  senders:count('sender'),authorship:count('authorship'),firstDate:dated[0]||null,lastDate:dated.at(-1)||null,
  unknownDate:records.length-dated.length,unknownTimezone:records.filter(r=>!r.observedAt).length,
  sourceHashes:[...new Set(records.map(r=>r.sourceHash).filter(Boolean))],publicationApproved:0,
  basis:'Counts describe this supplied derivative only; they do not establish authorship, current beliefs or full archive coverage.'};
}
function search(records,query='',filters={},reviews=[]){
 if(!string(query,2000))fail('Search query is too long.');
 for(const key of ['from','to'])if(filters[key]&&!validDay(filters[key]))fail('Use a valid YYYY-MM-DD date.');
 if(filters.from&&filters.to&&filters.from>filters.to)fail('Date range is reversed.');
 const terms=(query.normalize('NFC').toLocaleLowerCase().match(/[\p{L}\p{N}]+/gu)||[]);
 if(terms.length>32)fail('Use up to 32 lexical search terms.');
 const limit=filters.limit??100,offset=filters.offset??0;
 if(!Number.isInteger(limit)||limit<1||limit>1000||!Number.isInteger(offset)||offset<0)fail('Invalid pagination.');
 const reviewMap=latestReviews(reviews),selected=records.filter(r=>{
  const day=dateLabel(r),review=matchedReview(r,reviewMap);
  if(filters.from&&(!day||day<filters.from)||filters.to&&(!day||day>filters.to))return false;
  if(filters.thread&&r.threadTitle!==filters.thread&&r.conversationId!==filters.thread)return false;
  if(filters.sender&&r.sender!==filters.sender||filters.authorship&&r.authorship!==filters.authorship)return false;
  if(filters.project&&!review?.projectIds.includes(filters.project))return false;
  if(filters.excludeReviewed&&review?.excluded)return false;
  const text=(r.body+'\n'+r.threadTitle+'\n'+r.sender).normalize('NFC').toLocaleLowerCase();
  return terms.every(t=>text.includes(t));
 });
 selected.sort((a,b)=>(dateLabel(a)||'9999').localeCompare(dateLabel(b)||'9999')||
  (a.localTimestamp||a.observedAt||'').localeCompare(b.localTimestamp||b.observedAt||'')||
  (a.sourceOrdinal||0)-(b.sourceOrdinal||0)||a.id.localeCompare(b.id));
 return {records:selected.slice(offset,offset+limit),total:selected.length,offset,limit,
  retrieval:'local lexical AND search; no embedding or external model request',
  dateBasis:'Recorded calendar labels; timestamps without a timezone are not converted to UTC.'};
}
function buckets(records,unit='month'){
 if(!['year','month','day'].includes(unit))fail('Choose year, month or day.');
 const result=new Map();for(const r of records){const day=dateLabel(r),key=day?day.slice(0,unit==='year'?4:unit==='month'?7:10):'unknown';result.set(key,(result.get(key)||0)+1);}
 return [...result].sort((a,b)=>a[0].localeCompare(b[0])).map(([date,count])=>({date,count}));
}
function lens(record,reviews=[]){return {
 originalEvidence:{body:record.body,messageId:record.id,bodySha256:record.bodySha256,sourceFile:record.sourceFile,
  sourceHash:record.sourceHash,sourceOrdinal:record.sourceOrdinal,timestampOriginal:record.timestampOriginal,
  attribution:record.sender,authorship:record.authorship,basis:'Decoded message projection; original-file reference is not independent authentication.'},
 extractedFact:{statement:'This wording appears in the supplied derivative attributed to the recorded sender.',
  originalBytesVerified:false,claimsCorroborated:false,currentValidity:'not-established',publicationApproved:false},
 interpretation:reviewFor(record,reviews),generatedOutput:null};}
function makeReview(record,fields,at,ack,previous=null){
 if(ack!==true)fail('Fresh owner review is required.');
 if(!timestamp(at))fail('Review timestamp must include a timezone.');
 const authors=['owner_original','quoted_or_copied','AI_GENERATED','third_party','uncertain'];
 if(!fields||!authors.includes(fields.authorship)||!string(fields.reason,2000)||!fields.reason.trim())fail('Choose authorship and record the review reason.');
 if(!Array.isArray(fields.projectIds)||fields.projectIds.length>30||fields.projectIds.some(x=>!string(x,200)||!x.trim()))fail('Invalid explicit project links.');
 if(previous&&(!validReview(previous)||previous.messageId!==record.id||previous.projectionSha256!==record.projectionSha256))fail('Review ancestry does not match this witness.');
 const style=fields.allowStyle===true;
 if(style&&(fields.authorship!=='owner_original'||fields.excluded===true||record.authorship==='AI_GENERATED'||
  record.classification!=='message'||record.contaminationFlags.length&&fields.contaminationResolved!==true))fail('Style use requires original owner authorship and resolved contamination flags.');
 const review={format:'ContinuityMessengerReview/1',messageId:record.id,projectionSha256:record.projectionSha256,
  bodySha256:record.bodySha256,version:previous?previous.version+1:1,supersedes:previous?.id||null,
  reviewedAt:at,authorship:fields.authorship,allowStyle:style,excluded:fields.excluded===true,
  projectIds:[...new Set(fields.projectIds)],reason:fields.reason,contaminationResolved:fields.contaminationResolved===true,
  publicationApproved:false,scope:'Private retrieval/style eligibility only; not training transfer or publication consent.'};
 review.id='MESSENGER.REVIEW.'+A.hash(canonical(review));Object.freeze(review.projectIds);Object.freeze(review);authorizedReviews.add(review);return review;
}
function eligibleStyle(records,reviews=[]){const reviewMap=latestReviews(reviews);return records.filter(r=>{const v=matchedReview(r,reviewMap);return v?.allowStyle===true&&v.authorship==='owner_original'&&!v.excluded&&r.authorship!=='AI_GENERATED'&&r.classification==='message'&&(!r.contaminationFlags.length||v.contaminationResolved);});}
function compare(a,b){const left=a.body.split('\n'),right=b.body.split('\n'),rows=[];for(let i=0;i<Math.max(left.length,right.length);i++)if(left[i]!==right[i])rows.push({line:i+1,left:left[i]??null,right:right[i]??null});return {leftId:a.id,rightId:b.id,equal:a.body===b.body,leftHash:a.bodySha256,rightHash:b.bodySha256,changes:rows,basis:'Positional line comparison. Shared wording alone does not establish one work or independent authorship.'};}
function publicExport(){return {format:'ContinuityPublic/1',posts:[],basis:'Messenger originals are private. Use the existing separate public-summary review workflow.'};}
const api={FORMAT,MAX_BYTES,MAX_RECORDS,parseJSONL,summarize,search,buckets,lens,makeReview,eligibleStyle,reviewFor,compare,publicExport,dateLabel};
root.LivingArchiveCore=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
