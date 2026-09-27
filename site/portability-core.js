/* Selected-chat import. No account access, no model, no network calls. */
(function(root){
'use strict';
const W=root.WorkspaceCore||(typeof require==='function'?require('./workspace-core.js'):null);
const bytes=s=>new TextEncoder().encode(s).length,copy=W.copy;
const fail=m=>{throw new Error(m);},own=(o,k)=>Object.prototype.hasOwnProperty.call(o,k);
const isText=(s,max=20000)=>typeof s==='string'&&s.length<=max;
const MAX_FILE=20000000,MAX_CHATS=10000,MAX_NODES=100000;
const baseValidate=W.validate,baseSuggestions=W.suggestions;
function timestamp(v){if(typeof v==='number'&&Number.isFinite(v)){const d=new Date(v*1000);if(!isNaN(d)&&d.getUTCFullYear()>=1970&&d.getUTCFullYear()<2200)return d.toISOString();}return null;}
function parseArchive(raw){
 if(typeof raw!=='string'||bytes(raw)>MAX_FILE)fail('Select an extracted JSON file up to 20 MB, not the ZIP archive.');
 let value;try{value=JSON.parse(raw);}catch{fail('This is not valid JSON. Extract conversations.json from your export first.');}
 const chats=Array.isArray(value)?value:value&&Array.isArray(value.conversations)?value.conversations:null;
 if(!chats||!chats.length||chats.length>MAX_CHATS)fail('Expected 1–10,000 conversations with mapping/current_node records.');
 let totalNodes=0;
 const records=chats.map((chat,index)=>{
  const row={index,title:isText(chat?.title,2000)?chat.title.slice(0,200):'Untitled conversation',conversationId:isText(chat?.id,200)?chat.id:isText(chat?.conversation_id,200)?chat.conversation_id:'',date:timestamp(chat?.update_time)||timestamp(chat?.create_time),problem:'',branches:[],defaultTip:null};
  if(!chat||typeof chat!=='object'||!chat.mapping||typeof chat.mapping!=='object'||Array.isArray(chat.mapping)){row.problem='Unsupported conversation shape (mapping is missing).';return {...row,chat:null};}
  const keys=Object.keys(chat.mapping);totalNodes+=keys.length;if(totalNodes>MAX_NODES)fail('Export contains too many message nodes. Use a smaller selected export.');
  if(!keys.length||keys.length>20000){row.problem='Conversation has no nodes or exceeds 20,000 nodes.';return {...row,chat:null};}
  if(keys.some(k=>!k||k.length>200||['__proto__','constructor','prototype'].includes(k))){row.problem='Invalid message-node identifiers.';return {...row,chat:null};}
  const parents=new Set();for(const k of keys){const p=chat.mapping[k]?.parent;if(typeof p==='string')parents.add(p);}
  const tips=keys.filter(k=>!parents.has(k));
  const current=typeof chat.current_node==='string'&&own(chat.mapping,chat.current_node)?chat.current_node:null;
  if(current&&!tips.includes(current))tips.unshift(current);
  if(tips.length>200){row.problem='More than 200 branches; split this conversation before import.';return {...row,chat:null};}
  row.branches=tips.map(id=>({id,role:isText(chat.mapping[id]?.message?.author?.role,40)?chat.mapping[id].message.author.role:'node',current:id===current}));
  row.defaultTip=current||(tips.length===1?tips[0]:null);
  if(!tips.length)row.problem='No branch tip found; the conversation may contain a cycle.';
  return {...row,chat};
 });
 return {records,count:records.length};
}
function catalog(archive){return archive.records.map(({chat,...row})=>row);}
function transcript(archive,index,tip){
 const r=archive.records[index];if(!r||r.problem||!r.chat)fail(r?.problem||'Conversation not found.');
 if(!isText(tip,200)||!r.branches.some(b=>b.id===tip))fail('Choose an explicit conversation branch.');
 const mapping=r.chat.mapping,path=[],seen=new Set();let node=tip;
 while(node!==null){
  if(typeof node!=='string'||!own(mapping,node))fail('Broken parent chain. This branch was not imported.');
  if(seen.has(node))fail('Cyclic parent chain. This branch was not imported.');seen.add(node);
  const item=mapping[node];if(!item||typeof item!=='object'||Array.isArray(item))fail('Malformed message node.');
  path.push({id:node,item});if(path.length>20000)fail('Conversation exceeds the message limit.');
  if(item.parent===null||item.parent===undefined)node=null;else node=item.parent;
 }
 path.reverse();
 const output=[],messages=[];let skipped=0,omitted=0,characters=0;
 for(const {id,item} of path){
  const m=item.message;if(!m)continue;
  const role=m.author?.role;
  if(!['user','assistant'].includes(role)||m.metadata?.is_visually_hidden_from_conversation===true||(role==='assistant'&&m.channel!=null&&!['final','all'].includes(m.channel))){skipped++;continue;}
  const content=m.content;let pieces=[];
  if(Array.isArray(content?.parts)){
   for(const p of content.parts){if(typeof p==='string')pieces.push(p);else{pieces.push('[Non-text content omitted from this text import]');omitted++;}}
  }else if(typeof content?.text==='string')pieces=[content.text];
  else{pieces=['[Unsupported or non-text message content omitted]'];omitted++;}
  const body=pieces.join('\n');characters+=body.length;if(characters>1000000)fail('Selected branch exceeds 1 MB. Save a shorter excerpt instead.');
  const date=timestamp(m.create_time),start=output.length+1;
  output.push((role==='user'?'User':'Assistant')+':'+(date?' ['+date+']':''));
  output.push(...W.lines(body));const end=output.length;
  messages.push({nodeId:id,role,start,end,at:date});output.push('');
 }
 if(!messages.length)fail('This branch has no visible user/assistant messages.');
 const text=output.join('\n');if(bytes(text)>1000000)fail('Selected branch exceeds 1 MB. Save a shorter excerpt instead.');
 return {index,title:r.title||'Untitled conversation',conversationId:r.conversationId,tip,text,messages,skipped,omitted,totalNodes:path.length,otherNodes:Object.keys(mapping).length-path.length,date:r.date};
}
function planImport(s,archive,selections,options,idFn,at){
 if(!Array.isArray(selections)||!selections.length||selections.length>20)fail('Select 1–20 conversations per import.');
 if(!['unlinked','existing','separate'].includes(options?.mode))fail('Choose how to organize the imported sources.');
 if(options.mode==='existing'&&!s.threads.some(t=>t.id===options.threadId))fail('Select an existing intention.');
 if(!isText(options.fileName,300))fail('Invalid source filename.');
 const next=copy(s),added=[],duplicates=[],chosen=new Set();let total=0;
 for(const selection of selections){
  if(!Number.isInteger(selection.index)||chosen.has(selection.index))fail('Choose each conversation once per batch.');chosen.add(selection.index);
  const t=transcript(archive,selection.index,selection.tip);total+=bytes(t.text);if(total>4000000)fail('Selected transcript text exceeds 4 MB. Import fewer conversations.');
  if(next.sources.some(x=>x.text===t.text)){duplicates.push(t.title);continue;}
  let threadId=options.mode==='existing'?options.threadId:null;
  if(options.mode==='separate'){threadId=idFn();next.threads.push({id:threadId,title:t.title,objective:'',last:'Imported selected conversation text. Decisions have not been reviewed.',next:'Review the source and confirm the checkpoints worth carrying forward.',open:[],waiting:'',state:'CONTINUE',attention:true,updatedAt:at});}
  const source=W.makeSource(next,{title:t.title,text:t.text,url:'',speaker:'mixed',threadId},idFn(),at);
  source.originalFileName=options.fileName;
  source.chatImport={format:'ChatGPTMapping/1',conversationId:t.conversationId,branchTip:t.tip,messages:t.messages,skippedMessages:t.skipped,omittedParts:t.omitted,excludedNodes:t.otherNodes};
  next.sources.push(source);added.push(source.id);
 }
 if(bytes(JSON.stringify(next))>15000000)fail('The workspace would exceed its portable backup limit. Import fewer conversations.');
 return {state:validate(next),added,duplicates};
}
function validate(input){
 const s=baseValidate(input);
 for(const src of s.sources){const m=src.chatImport;if(m==null)continue;
  if(!m||m.format!=='ChatGPTMapping/1'||!isText(m.conversationId,200)||!isText(m.branchTip,200)||!m.branchTip||!Array.isArray(m.messages)||!m.messages.length||m.messages.length>20000)fail('Invalid imported conversation provenance.');
  for(const k of ['skippedMessages','omittedParts','excludedNodes'])if(!Number.isSafeInteger(m[k])||m[k]<0)fail('Invalid import omission count.');
  let last=0;const ls=W.lines(src.text),ids=new Set();
  for(const v of m.messages){if(!v||!isText(v.nodeId,200)||!v.nodeId||ids.has(v.nodeId)||!['user','assistant'].includes(v.role)||!Number.isInteger(v.start)||!Number.isInteger(v.end)||v.start<=last||v.end<v.start||v.end>ls.length||!(v.at===null||isText(v.at,100)))fail('Invalid imported message range.');
   const expected=(v.role==='user'?'User':'Assistant')+':'+(v.at?' ['+v.at+']':'');if(ls[v.start-1]!==expected)fail('Message attribution does not match its transcript header.');ids.add(v.nodeId);last=v.end;
  }
 }
 return s;
}
function suggestions(src){return baseSuggestions(src).map(p=>{const m=src.chatImport?.messages?.find(m=>p.start>=m.start&&p.start<=m.end);return m?{...p,speaker:m.role}:p;});}
function dossier(s,id){
 const thread=s.threads.find(t=>t.id===id);if(!thread)fail('Intention not found.');
 const notes=s.notes.filter(n=>n.threadId===id),refs=new Set(notes.map(n=>n.sourceId));
 return {thread,sources:s.sources.filter(x=>x.threadId===id||refs.has(x.id)),confirmed:notes.filter(n=>n.reviewState!=='pending'),pending:notes.filter(n=>n.reviewState==='pending'),followups:(s.followups||[]).filter(f=>f.threadId===id)};
}
W.validate=validate;W.suggestions=suggestions;
const api={MAX_FILE,parseArchive,catalog,transcript,planImport,validate,suggestions,dossier};root.PortabilityCore=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
