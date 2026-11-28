'use strict';
const $ = id => document.getElementById(id);
const KINDS = {clip:'Selected clip',segment:'Audio segment',journal:'Journal entry'};
const viewInfo = {
  sessions:['Long session player','Open an exported recording and jump to the lines you marked.'],
  overview:['Archive overview','Saved audio and writing from your listening sessions.'],
  'saved-lines':['Saved journal lines','Return to the words you kept and hear what came before them.'],
  review:['Review inbox','Listen to a moment and file it with one click.'],
  archive:['Your archive','Search saved clips, original audio segments and journal entries.'],
  activity:['Archive activity','See when recordings arrived and how much audio they cover.']
};
const state={view:'overview',days:7,kind:'all',query:'',category:'',device:'all',date:'',clock:'',fold:0,radius:15,offset:0,version:0,detailVersion:0};
const limit=25;
let searchTimer, modalCleanup;
const dateFmt=(value,options)=>new Intl.DateTimeFormat(undefined,options).format(new Date(value));
const day=value=>dateFmt(value,{month:'short',day:'numeric'});
const dateTime=value=>dateFmt(value,{month:'short',day:'numeric',year:'numeric',hour:'numeric',minute:'2-digit'});
const number=value=>new Intl.NumberFormat().format(value);
function duration(ms){
  const s=Math.max(0,Math.round(Number(ms||0)/1000));
  if(s>=3600)return `${Math.floor(s/3600)}h ${Math.floor(s%3600/60)}m`;
  if(s>=60)return `${Math.floor(s/60)}m ${s%60}s`;
  return `${s}s`;
}
function bytes(n){if(!Number.isFinite(n))return 'Unavailable';let unit=0;const units=['B','KiB','MiB','GiB','TiB'];while(n>=1024&&unit<units.length-1){n/=1024;unit++;}return `${number(Math.round(n*(n<10?10:1))/(n<10?10:1))} ${units[unit]}`;}
function e(tag,cls,text){const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=String(text);return node;}
function add(parent,...nodes){parent.append(...nodes.filter(v=>v!=null));return parent;}
function icon(name,cls=''){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');if(cls)svg.setAttribute('class',cls);svg.setAttribute('aria-hidden','true');const ref=document.createElementNS(svg.namespaceURI,'use');ref.setAttribute('href','#i-'+name);svg.append(ref);return svg;}
function button(label,fn,cls='btn'){const b=e('button',cls,label);b.type='button';b.addEventListener('click',fn);return b;}
function link(label,where,cls='text-link'){const a=e('a',cls);a.href=where;return add(a,label,icon('arrow'));}
function empty(title,text){return add(e('div','empty'),e('strong','',title),text);}
function heading(title,action){return add(e('div','section-head'),e('h2','',title),action);}
function panel(...nodes){return add(e('div','panel'),...nodes);}
function plural(n,s){return `${number(n)} ${s}${n===1?'':'s'}`;}
function qs(params){return new URLSearchParams(params).toString();}
async function get(path,params){const res=await fetch(path+(params?'?'+qs(params):''),{credentials:'same-origin',cache:'no-store'});if(!res.ok)throw Error(res.status===401||res.status===403?'Your session expired. Reopen the archive to sign in.':`Archive request failed (HTTP ${res.status}). Try refreshing.`);return res.json();}
function notice(text=''){$('message').textContent=text;$('message').hidden=!text;}
function range(){const now=new Date(),start=new Date(now);start.setHours(0,0,0,0);start.setDate(start.getDate()-state.days+1);return {from:start.getTime(),to:now.getTime()+1,tzOffset:-now.getTimezoneOffset()};}
function intervalGraph(summary){
  const box=e('div','chart'),bars=e('div','bars');
  const buckets=summary.buckets||[],max=Math.max(1,...buckets.map(b=>b.capturedMs||0));
  bars.setAttribute('role','img');bars.setAttribute('aria-label',`Archived audio per day. ${plural(buckets.filter(b=>b.capturedMs>0).length,'day')} with audio in this period.`);
  for(const item of buckets){const wrap=e('div','bar-wrap');wrap.title=`${day(item.startMs)} · ${duration(item.capturedMs)}`;
    const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 30 100');svg.setAttribute('preserveAspectRatio','none');
    const bar=document.createElementNS(svg.namespaceURI,'rect');const h=item.capturedMs>0?Math.max(1,Math.round(100*item.capturedMs/max)):0;
    Object.entries({x:0,y:100-h,width:30,height:h,rx:2}).forEach(([k,v])=>bar.setAttribute(k,v));svg.append(bar);wrap.append(svg);bars.append(wrap);}
  const axis=e('div','chart-axis');if(buckets.length)add(axis,e('span','',day(buckets[0].startMs)),e('span','',day(buckets[buckets.length-1].startMs)));
  return add(box,bars,axis,e('p','chart-caption','Each bar shows archived audio that day. Overlapping recordings count once.'));
}
function hero(summary){const tile=e('article','hero'),stat=e('div');add(stat,e('div','eyebrow','Time captured'),e('div','big',duration(summary.capturedMs)),e('p','sub','Audio held in your archive'));
  add(tile,add(e('div','hero-top'),stat,button('Daily totals',()=>showDaily(summary),'hero-meta')),intervalGraph(summary));
  const metric=e('article','metric');const foot=e('div','metric-foot');for(const [label,val] of [['Segments',summary.segmentCount],['Journal entries',summary.journalCount]])add(foot,add(e('span'),e('b','',number(val)),label));
  add(metric,add(e('div','metric-top'),e('span','eyebrow','Selected clips'),icon('clip')),add(e('div','amount'),e('div','number',number(summary.clipCount))),foot);
  return add(e('div','hero-grid'),tile,metric);
}
function showDaily(o){state.detailVersion++;const dialog=$('detail');$('detail-kind').textContent='Captured audio';
  const title=e('h2','','Daily totals');title.id='detail-title';const list=e('dl','daily-totals');for(const b of [...o.buckets].reverse()){add(list,e('dt','',dateFmt(b.startMs,{weekday:'short',month:'short',day:'numeric',year:'numeric'})),e('dd','',duration(b.capturedMs)));}
  $('detail-body').replaceChildren(title,list);dialog.showModal();}
function storage(info){const box=e('div','panel storage');add(box,add(e('div','storage-top'),icon('drive'),'Archive storage'));
  const msg=e('p');add(msg,e('b','',bytes(info.usedBytes)),' used',info.quotaBytes?` of ${bytes(info.quotaBytes)}`:'');box.append(msg);
  if(info.quotaBytes>0){const p=e('progress');p.max=info.quotaBytes;p.value=info.usedBytes;p.setAttribute('aria-label','Archive quota used');box.append(p);}
  const hosted=info.phonePairing===true;
  $('location-label').textContent=hosted?'Hosted archive':'This computer';
  if(hosted&&Number.isFinite(info.transferCapBytes)&&Number.isFinite(info.remainingArchiveBytes)){
    box.append(e('p','',`${bytes(info.remainingArchiveBytes)} remaining in the archive.`));
    const details=e('details'),summary=e('summary','','Monthly transfers and storage cost');
    const gigabytes=n=>`${number(Math.round(n/1e7)/100)} GB`;
    add(details,summary,e('p','',`Uploaded ${gigabytes(info.monthUploadBytes)} of ${gigabytes(info.transferCapBytes)}. Played or downloaded ${gigabytes(info.monthPlaybackBytes)} of ${gigabytes(info.transferCapBytes)}. Both reset on the first of the month, UTC.`),
      e('p','','These limits cover media transfers. Your phone queues remaining uploads until capacity is available.'));
    const storageCost=new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(info.usedBytes/1e9*0.015);
    details.append(e('p','',`About ${storageCost}/month for this much archived media at $0.015/GB. Website hosting, catalog copies and service traffic are billed separately.`));
    box.append(details);
  }else if(Number.isFinite(info.availableBytes))box.append(e('p','',`${bytes(info.availableBytes)} available for new items.`));
  if(info.retentionYears)box.append(e('p','',`${info.retentionYears}-year retention from the recording date.`));
  if(hosted){
    box.append(button('Pair phone',openPairing,'btn pair-action'));
    const installer=e('a','btn','Download Android app');installer.href='/downloads/android';box.append(installer);
    const mint=e('a','btn','Download Linux Mint app');mint.href='/downloads/mint';box.append(mint);
    const roster=e('a','btn','Early-access requests');roster.href='/early-access/roster#join';box.append(roster);
    const form=e('form');form.method='post';form.action='/logout';
    const signOut=e('button','btn','Sign out');signOut.type='submit';form.append(signOut);box.append(form);
  }return box;}
function singleReviewPreferred(){
  try{return localStorage.getItem('recall-review-layout')==='single';}catch{return false;}
}
const reviewView={shelf:'inbox',offset:0,feedback:'',single:singleReviewPreferred(),undo:null};
async function ownerAction(path,data){
  const reply=await fetch(path,{method:'POST',credentials:'same-origin',cache:'no-store',
    headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
  const result=await reply.json();
  if(!reply.ok)throw Error(result.error||'Could not save the change.');
  return result;
}
function reviewButtons(item,options,after){
  const out=e('div','quick-find review-buttons'),status=e('span','status');
  status.setAttribute('role','status');
  async function change(action,label){
    const all=out.querySelectorAll('button');for(const b of all)b.disabled=true;
    try{
      const result=await ownerAction('/api/review/file',{kind:item.kind,id:item.id,action,label});
      await after(action,label,result);
    }
    catch(err){status.textContent=err.message;}
    finally{for(const b of all)b.disabled=false;}
  }
  for(const label of new Set([...options.labels,...item.review.labels])){
    const on=item.review.labels.includes(label);
    const b=button(label,()=>change(on?'remove':'add',label),'btn'+(on?' chosen':''));
    b.setAttribute('aria-pressed',String(on));out.append(b);
  }
  const action=item.review.state==='inbox'?'dismiss':'reopen';
  out.append(button(action==='dismiss'?'Dismiss':'Return to inbox',()=>change(action),'text-link'),status);
  return out;
}
function reviewSettings(options){
  const details=e('details','panel review-options');details.append(e('summary','','Inbox settings'));
  const labels=e('textarea');labels.value=options.labels.join('\n');labels.rows=5;
  const count=e('select'),order=e('select');
  for(const n of [3,5,10,20]){const o=e('option','',String(n));o.value=n;count.append(o);}count.value=options.batch;
  for(const [v,name] of [['newest','Newest first'],['oldest','Oldest first']]){const o=e('option','',name);o.value=v;order.append(o);}order.value=options.order;
  const status=e('p','status');status.setAttribute('role','status');
  const save=button('Save inbox settings',async()=>{
    try{await ownerAction('/api/review/options',{labels:labels.value.split('\n').map(x=>x.trim()).filter(Boolean),batch:Number(count.value),order:order.value});
      reviewView.offset=0;reviewView.feedback='Inbox settings saved.';await refresh();}
    catch(err){status.textContent=err.message;}
  });
  return add(details,add(e('label'),'One-click categories (one per line)',labels),
    add(e('div','find-filters'),add(e('label'),'Items per page',count),add(e('label'),'Review order',order)),save,status);
}
async function reviewPage(ver){
  const {shelf:sourceShelf,offset:sourceOffset}=reviewView;
  const data=await get('/api/review',{shelf:sourceShelf,offset:sourceOffset});
  if(ver!==state.version)return;
  const step=reviewView.single?1:data.options.batch;
  if(!data.items.length&&reviewView.offset){
    reviewView.offset=Math.max(0,Math.floor((data.total-1)/step)*step);return refresh();
  }
  const holder=e('div'),shelf=e('select');shelf.setAttribute('aria-label','Review collection');
  for(const [key,name] of [['inbox','Needs review'],['filed','Filed'],['dismissed','Dismissed'],...data.shelves.map(name=>['label/'+name,name])]){
    const o=e('option','',name);o.value=key;shelf.append(o);}
  shelf.value=reviewView.shelf;shelf.onchange=()=>{reviewView.shelf=shelf.value;reviewView.offset=0;reviewView.feedback='';refresh();};
  const layout=button('One at a time',()=>{
    reviewView.single=!reviewView.single;
    if(!reviewView.single)reviewView.offset=Math.floor(reviewView.offset/data.options.batch)*data.options.batch;
    try{localStorage.setItem('recall-review-layout',reviewView.single?'single':'list');}catch{}
    refresh();
  },'btn layout-mode');
  layout.setAttribute('aria-pressed',String(reviewView.single));
  const position=reviewView.single&&data.total?'Item '+(reviewView.offset+1)+' of '+number(data.total):plural(data.total,'item');
  holder.append(add(e('div','review-top'),add(e('label'),'Collection ',shelf),layout,e('span','',position)));
  const kept=e('a','text-link','Saved journal lines');kept.href='#saved-lines';
  holder.querySelector('.review-top').append(kept);
  if(reviewView.feedback){
    const msg=e('span','',reviewView.feedback);msg.setAttribute('role','status');
    const receipt=reviewView.undo;
    const undo=receipt?button('Undo filing',async()=>{
      undo.disabled=true;
      try{
        await ownerAction('/api/review/file',{kind:receipt.kind,id:receipt.id,action:'undo',before:receipt.before});
        reviewView.shelf=receipt.shelf;reviewView.offset=receipt.offset;reviewView.undo=null;
        reviewView.feedback='Filing undone.';await refresh();
      }catch(err){msg.textContent=err.message;undo.disabled=false;}
    },'text-link'):null;
    holder.append(add(e('div','review-feedback'),msg,undo));
  }
  if(!data.items.length)holder.append(panel(empty(reviewView.shelf==='inbox'?'You’re caught up.':'Nothing filed here yet.',
    reviewView.shelf==='inbox'?'Selected clips and laptop bookmarks appear here after they reach this archive.':'Open an archive item to file it here.')));
  for(const item of (reviewView.single?data.items.slice(0,1):data.items)){
    const card=e('article','panel review-card');
    const open=button(item.review.name||item.title||kindName(item.kind),()=>openItem(item),'review-title');
    const device=/^(?:desk-keep-|laptop-)\d/.test(item.id)?'Mint laptop':'Phone or imported';
    const facts=[dateTime(item.startedAtMillis),item.category||kindName(item.kind),device];
    if(item.kind!=='journal')facts.push(duration(item.durationMs));
    add(card,e('p','row-sub',facts.join(' · ')),open);
    if(item.review.writing||item.snippet)card.append(e('p','review-snippet',(item.review.writing||item.snippet).slice(0,400)));
    if(item.kind!=='journal'){
      const audio=e('audio');audio.controls=true;audio.preload='none';audio.src=mediaPath(item);audio.setAttribute('aria-label','Listen to '+item.title);
      audio.onplay=()=>{for(const other of holder.querySelectorAll('audio'))if(other!==audio)other.pause();};card.append(audio);
    }
    card.append(reviewButtons(item,data.options,async(action,label,result)=>{
      reviewView.feedback=action==='dismiss'?'Dismissed. The original stays in Archive.':action==='reopen'?'Returned to the inbox.':action==='remove'?'Removed '+label+'.':'Filed under '+label+'.';
      reviewView.undo=result.undo?{kind:item.kind,id:item.id,before:result.undo,shelf:sourceShelf,offset:sourceOffset}:null;
      await refresh();
      if(state.view==='review'&&reviewView.single)$('view').querySelector('.review-title')?.focus();
    }));holder.append(card);
  }
  const pager=e('div','quick-find');
  if(reviewView.offset)pager.append(button(reviewView.single?'Previous item':'Previous items',()=>{reviewView.offset=Math.max(0,reviewView.offset-step);refresh();}));
  if(reviewView.offset+step<data.total)pager.append(button(reviewView.single?'Next item':'Next items',()=>{reviewView.offset+=step;refresh();}));
  add(holder,pager,reviewSettings(data.options));$('view').replaceChildren(holder);
}
function filedWork(full){
  const details=e('details','detail-block');details.append(e('summary','','Classify or edit writing'));details.open=full.review.state==='filed';
  const work=full.review,title=e('input'),heard=e('input'),draft=e('textarea'),status=e('p','status');
  title.value=work.name;heard.value=work.heard;draft.value=work.writing;draft.rows=4;
  draft.placeholder=full.correction||full.transcript||'Write a draft or next action';title.maxLength=180;heard.maxLength=180;draft.maxLength=40000;
  const buttons=reviewButtons(full,full.filingOptions,async()=>{const fresh=await get('/api/items/'+full.kind+'/'+encodeURIComponent(full.id));
    Object.assign(full,{review:fresh.review});const next=filedWork(full);next.open=true;
    next.querySelector('input').value=title.value;next.querySelectorAll('input')[1].value=heard.value;next.querySelector('textarea').value=draft.value;
    details.replaceWith(next);});
  const save=button('Save writing',async()=>{
    try{full.review=await ownerAction('/api/review/file',{kind:full.kind,id:full.id,action:'edit',name:title.value,heard:heard.value,writing:draft.value});
      status.textContent='Writing saved.';}
    catch(err){status.textContent=err.message;}
  });
  const seed=button('Use recognized text',()=>{if(!draft.value)draft.value=full.correction||full.transcript||'';draft.focus();},'text-link');
  const copy=button('Copy draft',async()=>{try{await navigator.clipboard.writeText([title.value,draft.value].filter(Boolean).join('\n\n'));status.textContent='Draft copied.';}
    catch{draft.focus();draft.select();status.textContent='Draft selected. Use Copy.';}});
  return add(details,buttons,add(e('label'),'Title or corrected word',title),add(e('label'),'Recognized spelling or phrase (optional)',heard),
    e('p','section-note','Searching for a corrected word also looks for its saved recognized spelling.'),add(e('label'),'Draft, definition or next action',draft),seed,
    add(e('div','quick-find'),save,copy),status);
}
function deletion(full){
  const details=e('details','detail-block delete-box');details.append(e('summary','','Delete from this archive'));
  const message=e('p','section-note',full.kind==='journal'?'Removes this journal and its saved lines from this archive. Earlier backups can keep copies.':'Removes this item, its filing and its original media from this archive. Other devices, independent excerpts and earlier backups can keep copies.');
  const status=e('p','status'),confirm=e('input');confirm.type='checkbox';
  const remove=button('Delete this item',async()=>{
    remove.disabled=true;
    try{await ownerAction('/api/archive/delete',{kind:full.kind,id:full.id,sha256:full.sha256});
      $('detail').close();await refresh();notice('Deleted from this archive.');}
    catch(err){status.textContent=err.message;remove.disabled=!confirm.checked;}
  },'btn danger');remove.disabled=true;confirm.onchange=()=>remove.disabled=!confirm.checked;
  return add(details,message,add(e('label','delete-confirm'),confirm,' I want to delete this item.'),remove,status);
}
function kindName(kind){return KINDS[kind]||'Archive item';}
function itemRow(item){
  const timed=item.kind!=='journal'&&Number.isFinite(item.match?.seconds);
  const instant=state.view==='archive'?localInstants(state.date,state.clock)[state.fold]:null;
  const covers=item.kind!=='journal'&&instant>=item.startedAtMillis&&instant<item.startedAtMillis+item.durationMs;
  const target=timed?{at:item.startedAtMillis+item.match.seconds*1000,match:item.match}:covers?{at:instant,clock:true}:{};
  if(item.kind==='journal'&&item.similar)target.reader={query:['Transcript','Originally heard'].includes(item.match.field)?item.similar.found:''};
  if(item.kind==='journal'&&Number.isInteger(item.match?.line)){
    const query=item.match.field==='Saved journal line'?'':item.similar?.found||state.query;
    target.reader={query,sourceLine:item.match.line};
  }
  const row=button('',()=>openItem(item,target),'row');row.dataset.itemId=item.id;row.dataset.kind=item.kind;
  const when=item.startedAtMillis?dateTime(item.startedAtMillis):'Time unavailable';const tag=item.category||kindName(item.kind);
  add(row,add(e('span',`row-icon ${item.kind}`),icon(KINDS[item.kind]?item.kind:'archive')),add(e('span','row-main'),e('span','row-title',item.title||tag),e('span','row-sub',`${tag} · ${when}`)),
    e('span','time',item.kind==='journal'? 'Text':duration(item.durationMs)),icon('right','chev'));if(item.match){
    const main=row.querySelector('.row-main');
    if(item.similar)main.append(e('span','match-preview','You typed “'+item.similar.typed+'” · saved as “'+item.similar.found+'”'));
    main.append(e('span','match-preview',[item.match.field,item.match.stamp,item.match.text].filter(Boolean).join(' · ')));
  }
  if(item.kind==='journal' && item.preview && !item.match)row.querySelector('.row-main').append(e('span','match-preview',item.preview));
  if(item.kind!=='journal'||!Number.isFinite(item.match?.at))return row;
  const at=item.match.at;
  const reader=target.reader||{query:item.similar?.found||state.query,start:0};
  const label='Find audio at '+dateFmt(at,{hour:'numeric',minute:'2-digit'});
  return add(e('div','journal-result'),row,journalAudio(label,item,at,reader));
}
function journalAudio(label,journal,at,reader){
  const actions=e('div','result-audio'),status=e('span','section-note');status.setAttribute('role','status');
  const choose=recording=>openItem(recording,{at,journal,reader});
  const cue=button(label,async()=>{
    cue.disabled=true;status.textContent='Checking recorded coverage…';
    try{
      const matches=await get('/api/recordings-at',{at,journal:journal.id});
      if(!actions.isConnected)return;
      if(matches.items.length===1){status.textContent='';cue.disabled=false;choose(matches.items[0]);return;}
      if(!matches.items.length){status.textContent='No archived audio covers this journal time.';return;}
      cue.remove();status.textContent=matches.more?'Choose from the first 12 recordings covering this line.':'Choose the recording to hear.';
      for(const rec of matches.items){
        const from=rec.device+' · '+(rec.title||kindName(rec.kind))+' · '+dateTime(rec.startedAtMillis);
        actions.append(button(from,()=>choose(rec),'btn'));
      }
    }catch(err){if(actions.isConnected){status.textContent=err.message;cue.disabled=false;}}
  },'text-link');
  return add(actions,cue,status);
}
function eventPanel(events){if(!events||!events.length)return panel(empty('No activity in this period.','New archive receipts will appear here.'));
  return panel(...events.map(v=>{const t=e('time','',dateTime(v.atMillis));t.dateTime=new Date(v.atMillis).toISOString();return add(e('div','event'),add(e('div'),e('b','',v.label||'Archive receipt'),v.detail?e('p','',v.detail):null,t));}));}
async function dashboard(ver){const [overview,items,info]=await Promise.all([get('/api/overview',range()),get('/api/items',{kind:'clip',limit:5,offset:0}),get('/api/status')]);if(ver!==state.version)return;
  const selected=items.items||[],left=e('div'),right=e('div');add(left,heading('Latest clips',link('View archive','#archive')),panel(...(selected.length?selected.map(itemRow):[empty('No selected clips yet.','Clips appear here when your phone sends a saved moment to this archive.')])))
  add(right,heading('Workspace'),storage(info),heading('Recent activity',link('View activity','#activity')),eventPanel((overview.events||[]).slice(0,2)));
  $('view').replaceChildren(discovery(),hero(overview),add(e('div','section-grid'),left,right));}
function metric(label,value){return add(e('div'),e('p','',label),e('strong','',value));}
const patternNames={coined:'Coined-word candidates',verse:'Poetry and freestyle candidates',manual:'Manual keeps',
  missed:'Missed moments',decision:'Project decisions',connection:'Connections',excerpt:'Excerpts',other:'Other labels'};
function clipPatterns(summary){
  const panel=e('div','panel pattern-panel');
  if(!summary.clipCount)return add(panel,empty('No selected clips in this period.','Counts appear when a saved clip reaches the archive.'));
  const list=e('ul','pattern-list');
  for(const group of summary.clipPatterns||[]){
    if(!group.count&&!['coined','verse','manual'].includes(group.key))continue;
    const label=patternNames[group.key]||'Other labels',row=e('li');
    const numbers=add(e('div'),e('strong','',number(group.count)),e('span','',label));
    row.append(numbers);
    if(group.latestClipId){
      const review=button('Review latest',()=>openItem({id:group.latestClipId,kind:'clip'}),'text-link');
      review.setAttribute('aria-label',`Review latest · ${label}`);row.append(review);
    }
    list.append(row);
  }
  return add(panel,list,e('p','section-note','Creative labels are suggestions for review. A clip can carry more than one label.'));
}
function busiest(summary){
  const box=e('div','panel peak-day'),peak=summary.busiestDay;
  if(!peak)return add(box,empty('No archived audio in this period.','A day total appears once audio arrives.'));
  const stamp=new Date(peak.date+'T12:00:00Z');
  const label=dateFmt(stamp,{weekday:'long',month:'short',day:'numeric',timeZone:'UTC'});
  add(box,e('b','',label),e('strong','',duration(peak.capturedMs)),e('span','','of archived audio, with overlaps counted once.'));
  if(peak.tiedDays>1)box.append(e('p','section-note',`${plural(peak.tiedDays,'day')} tied. The most recent is shown.`));
  return box;
}
async function activity(ver){const o=await get('/api/overview',range());if(ver!==state.version)return;
  const report=add(e('div','report'),metric('Archived audio',duration(o.capturedMs)),metric('Observed listening',o.observedMs==null?'Unavailable':duration(o.observedMs)),metric('Reported gaps',o.gapCount==null?'Unavailable':number(o.gapCount)));
  const note=e('p','section-note','Listening time and gap counts require session reports from the recorder. Empty time on the chart carries no microphone status.');
  const patterns=add(e('div','section-grid'),add(e('section'),heading('Saved clip patterns'),clipPatterns(o)),
    add(e('section'),heading('Busiest recorded day'),busiest(o)));
  $('view').replaceChildren(report,patterns,heading('Latest archive receipts'),eventPanel(o.events),note);}
function localDate(date) {
  const pad=n=>String(n).padStart(2,'0');
  return date.getFullYear()+'-'+pad(date.getMonth()+1)+'-'+pad(date.getDate());
}
function localInstants(date,clock){
  if(!date||!clock)return [];
  const wall=Date.parse(date+'T'+clock+'Z');
  if(!Number.isFinite(wall))return [];
  // A repeated wall time may have two UTC offsets. A skipped wall time has none.
  const offsets=new Set([-1,0,1].map(d=>new Date(wall+d*86400000).getTimezoneOffset()));
  return Array.from(offsets,m=>{
    const instant=wall+m*60000,actual=new Date(instant);
    return actual.getTimezoneOffset()===m?instant:null;
  }).filter(n=>n!==null).sort((a,b)=>a-b);
}
function dayBounds(value,clock,radius,fold) {
  if (!value) return {};
  if(clock){
    const options=localInstants(value,clock);
    if(!options.length)throw Error('The clock skipped this time on that date. Choose a different local time.');
    const instant=options[fold]??options[0],margin=radius*60000;
    return {from:instant-margin,to:instant+margin+1,overlap:1};
  }
  const start=new Date(value+'T00:00:00'),end=new Date(start);
  end.setDate(end.getDate()+1);
  return {from:start.getTime(),to:end.getTime()};
}
function findArchive(filters={}) {
  Object.assign(state,{kind:'all',category:'',device:'all',date:'',clock:'',fold:0,radius:15,query:'',offset:0},filters);
  if(location.hash==='#archive'){makeArchive();refresh();}else location.hash='archive';
}
function discovery() {
  const yesterday=new Date();yesterday.setDate(yesterday.getDate()-1);
  const form=e('form','find-form');const q=e('input');q.type='search';q.placeholder='A word or phrase you remember';q.setAttribute('aria-label','Find a remembered phrase');
  const submit=e('button','btn primary','Find');submit.type='submit';
  form.onsubmit=event=>{event.preventDefault();findArchive({query:q.value.trim()});};
  const choices=add(e('div','quick-find'),
    button('Coined words',()=>findArchive({kind:'clip',category:'coined'}),'btn'),
    button('Poetry & freestyle',()=>findArchive({kind:'clip',category:'verse'}),'btn'),
    button('Yesterday',()=>findArchive({date:localDate(yesterday)}),'btn'));
  return add(e('section','panel find-panel'),e('h2','','Find a moment'),add(form,q,submit),choices);
}
function makeArchive(){
  const form=e('form','find-panel panel');form.setAttribute('role','search');
  const q=e('input');q.id='search';q.type='search';q.placeholder='A word or phrase you remember';q.autocomplete='off';q.value=state.query;q.setAttribute('aria-label','Search your archive');
  function chooser(label,options,value) {
    const select=e('select');select.setAttribute('aria-label',label);
    for(const [key,name] of options){const opt=e('option','',name);opt.value=key;select.append(opt);}
    select.value=value;return select;
  }
  const kinds=chooser('Item type',[['all','All items'],['clip','Selected clips'],['segment','Audio segments'],['journal','Journal entries']],state.kind);
  const device=chooser('Recorded on',[['all','All sources'],['laptop','Mint laptop'],['other','Phone or imported']],state.device);
  const cats=chooser('Clip label',[['','All labels'],...Object.entries(patternNames)],state.category);
  const date=e('input');date.type='date';date.setAttribute('aria-label','Date started');date.value=state.date;
  const clock=e('input');clock.type='time';clock.value=state.clock;
  const radius=chooser('Search window',[['5','Within 5 minutes'],['15','Within 15 minutes'],['60','Within an hour']],String(state.radius));
  radius.disabled=!clock.value;
  const occurrence=e('select'),which=add(e('label'),'Which occurrence?',occurrence);
  function updateOccurrence(){
    const choices=localInstants(date.value,clock.value);
    occurrence.replaceChildren();
    for(const [i,n] of choices.entries()){
      const opt=e('option','',dateFmt(n,{hour:'numeric',minute:'2-digit',timeZoneName:'short'}));
      opt.value=String(i);occurrence.append(opt);
    }
    which.hidden=choices.length<2;occurrence.value=String(Math.min(state.fold,Math.max(0,choices.length-1)));
  }
  updateOccurrence();
  const around=e('details','time-search');around.open=Boolean(clock.value);
  const zone=Intl.DateTimeFormat().resolvedOptions().timeZone||'local time';
  add(around,e('summary','','Find by approximate time'),add(e('div','find-filters'),
    add(e('label'),'Time you remember',clock),which,add(e('label'),'Search window',radius)),
    e('p','section-note','Includes recordings already in progress. Times are shown in '+zone+'.'));
  function apply(){
    state.clock=clock.value;state.radius=Number(radius.value);state.fold=Number(occurrence.value)||0;
    state.query=q.value.trim();state.kind=kinds.value;state.device=device.value;state.category=cats.value;state.date=date.value;state.offset=0;refresh();
  }
  form.onsubmit=ev=>{ev.preventDefault();clearTimeout(searchTimer);apply();};
  q.oninput=()=>{clearTimeout(searchTimer);searchTimer=setTimeout(apply,180);};
  kinds.onchange=()=>{if(kinds.value!=='clip'&&kinds.value!=='all')cats.value='';apply();};
  cats.onchange=()=>{if(cats.value)kinds.value='clip';apply();};date.onchange=()=>{if(!date.value){clock.value='';radius.disabled=true;}state.fold=0;updateOccurrence();apply();};device.onchange=apply;
  clock.onchange=()=>{if(clock.value&&!date.value)date.value=localDate(new Date());radius.disabled=!clock.value;state.fold=0;updateOccurrence();apply();};
  occurrence.onchange=radius.onchange=apply;
  const filters=add(e('div','find-filters'),add(e('label'),'Item type',kinds),add(e('label'),'Clip label',cats),add(e('label'),'Recorded on',device),add(e('label'),'Date started',date));
  const clear=button('Clear filters',()=>{clearTimeout(searchTimer);findArchive();},'text-link');
  add(form,add(e('label','searchbox'),icon('search'),q),filters,around,clear);
  const results=e('div');results.id='results';$('view').replaceChildren(form,results);
}
function similarSearch(ver,params){
  const section=e('section','panel review-card');
  const status=e('p','section-note');status.setAttribute('role','status');
  const list=e('div');
  const tryIt=button('Find similar spellings',async()=>{
    tryIt.disabled=true;status.textContent='Checking saved words…';
    try{
      const result=await get('/api/items',{...params,similar:1});
      if(ver!==state.version||!section.isConnected)return;
      if(result.total){refresh();return;}
      const suggestions=result.similar;
      if(!suggestions)throw Error('Similar spelling search is unavailable on this archive.');
      const count=plural(suggestions.checked,'item');
      status.textContent=(suggestions.items.length?'Possible spellings found. ':'No similar spelling found. ')+
        'Checked titles and writing, including transcript excerpts, in '+(suggestions.limited?'the latest ':'')+count+' within your filters.';
      list.replaceChildren(...suggestions.items.map(itemRow));
    }catch(err){if(ver===state.version){status.textContent=err.message;tryIt.disabled=false;}}
  });
  return add(section,tryIt,status,list);
}
async function archive(ver){if(!$('results'))makeArchive();
  const params={kind:state.kind,q:state.query,category:state.category,device:state.device,...dayBounds(state.date,state.clock,state.radius,state.fold),offset:state.offset,limit};
  const response=await get('/api/items',params);if(ver!==state.version)return;
  if(state.offset>0&&!response.items.length&&response.total>0){state.offset=Math.floor((response.total-1)/limit)*limit;return refresh();}
  const nodes=[],group=new Map();for(const item of [...response.items].sort((a,b)=>b.startedAtMillis-a.startedAtMillis)){const d=item.startedAtMillis?dateFmt(item.startedAtMillis,{weekday:'long',month:'long',day:'numeric',year:'numeric'}):'Undated';if(!group.has(d))group.set(d,[]);group.get(d).push(item);}
  for(const [d,list] of group)nodes.push(e('h2','group-label',d),panel(...list.map(itemRow)));
  if(!nodes.length)nodes.push(panel(empty(state.query?'No matches.':'No items in this view.',state.query?'Try another word or change the item type.':'Clear filters to look across the archive. New items appear after your phone transfers them.')));
  if(!response.total&&state.query.split(/\s+/).some(word=>word.length>=4))nodes.push(similarSearch(ver,params));
  const prev=button('Previous',()=>{state.offset=Math.max(0,state.offset-limit);refresh();}),next=button('Next',()=>{state.offset+=limit;refresh();});prev.disabled=state.offset===0;next.disabled=state.offset+limit>=response.total;
  const count=response.total?`${state.offset+1}–${Math.min(state.offset+response.items.length,response.total)} of ${plural(response.total,'item')}`:'0 items';const pager=add(e('div','pager'),e('span','',count),response.total>limit?add(e('div'),prev,next):null);
  $('results').replaceChildren(...nodes,pager);}
async function refresh(){const ver=++state.version;notice();$('view').setAttribute('aria-busy','true');try{if(state.view==='sessions'){if(!$('session-audio'))sessionPlayer();}else if(state.view==='overview')await dashboard(ver);else if(state.view==='activity')await activity(ver);else if(state.view==='review')await reviewPage(ver);else if(state.view==='saved-lines')await savedLinesPage(ver);else await archive(ver);}catch(err){if(ver===state.version){notice(err.message);if(state.view==='archive'&&$('results'))$('results').replaceChildren();else $('view').replaceChildren();}}finally{if(ver===state.version)$('view').setAttribute('aria-busy','false');}}
let sessionCleanup;
function sessionPlayer(){
  // Only this tab holds the File and its working index. Changing views pauses playback.
  const desk=sessionPlayer.desk||={
    file:null,title:'',points:[],selected:null,position:0,lead:5,query:'',page:0,
    revision:0,exported:0,fingerprint:null,removed:[],
    warn(event){event.preventDefault();event.returnValue='';}
  };
  const types=['Moment','Poem line','Coined word','Follow-up','Project decision','Connection'];
  const maxPoints=20000,pageSize=24;
  const audio=e('audio'),files=e('input'),index=e('input'),search=e('input'),lead=e('select');
  const note=e('p','status'),receipt=e('p','section-note'),record=e('h2'),list=e('div'),summary=e('p','row-sub');
  const editor=e('div','find-filters'),name=e('input'),type=e('select'),pending=e('div','quick-find');
  const library=e('section','panel review-card'),toolbox=e('section','panel review-card');
  const enhanced=playbackEnhancement(audio);
  let sourceURL='',generation=0,importVersion=0,exporting=false;
  const stamp=ms=>{
    const seconds=Math.floor(ms/1000),hh=Math.floor(seconds/3600),mm=Math.floor(seconds/60)%60,ss=seconds%60;
    return (hh?hh+':':'')+String(mm).padStart(hh?2:1,'0')+':'+String(ss).padStart(2,'0');
  };
  const dirty=()=>desk.revision!==desk.exported;
  function updateReceipt(){
    receipt.textContent=desk.file?(dirty()?'Marker changes are in this tab. Download the index to keep them.':'Markers stay in this tab until you close it.'): '';
    if(dirty())window.addEventListener('beforeunload',desk.warn);else window.removeEventListener('beforeunload',desk.warn);
  }
  function changed(){desk.revision++;updateReceipt();}
  function matching(){const q=desk.query.trim().toLocaleLowerCase();return desk.points.filter(p=>(p.label+' '+p.type+' '+stamp(p.ms)).toLocaleLowerCase().includes(q));}
  function showEditor(){
    const p=desk.selected;editor.hidden=!p;
    if(!p)return;
    name.value=p.label;type.replaceChildren();
    for(const v of new Set([...types,p.type])){const opt=e('option','',v);opt.value=v;type.append(opt);}
    type.value=p.type;
  }
  function render(){
    const filtered=matching(),pages=Math.max(1,Math.ceil(filtered.length/pageSize));
    desk.page=Math.max(0,Math.min(pages-1,desk.page));const start=desk.page*pageSize;
    list.replaceChildren();
    for(const p of filtered.slice(start,start+pageSize)){
      const row=button(stamp(p.ms)+' · '+p.label+(p.type==='Moment'||p.type===p.label?'':' · '+p.type),()=>select(p,true),p===desk.selected?'btn primary':'btn');
      row.setAttribute('aria-pressed',String(p===desk.selected));list.append(row);
    }
    summary.textContent=filtered.length?(desk.query?filtered.length+' of '+plural(desk.points.length,'marker'):plural(desk.points.length,'marker'))+
      (pages>1?' · '+(start+1)+'–'+Math.min(start+pageSize,filtered.length):''):(desk.query?'No matching markers.':'No markers yet.');
    previousPage.disabled=!desk.page;nextPage.disabled=desk.page+1>=pages;pager.hidden=pages===1;
    const i=filtered.indexOf(desk.selected);
    previous.disabled=!filtered.length||i===0;next.disabled=!filtered.length||i===filtered.length-1;
    navigation.hidden=!filtered.length;download.disabled=!desk.file||exporting||(!desk.points.length&&!dirty());
    undo.hidden=!desk.removed.length;
    if(!undo.hidden)undo.textContent='Undo removal at '+stamp(desk.removed[desk.removed.length-1].point.ms);
    for(const b of marking.querySelectorAll('button'))b.disabled=audio.readyState<1||!desk.file;
  }
  function select(p,play){
    const focusedRow=list.contains(document.activeElement);
    desk.selected=p;const ix=matching().indexOf(p);if(ix>=0)desk.page=Math.floor(ix/pageSize);
    showEditor();render();
    if(focusedRow)list.querySelector('[aria-pressed="true"]')?.focus({preventScroll:true});
    if(!play)return;
    if(audio.readyState<1){note.textContent='Load playable audio to hear this marker. You can still edit its label.';return;}
    if(Number.isFinite(audio.duration)&&p.ms/1000>audio.duration+0.25){note.textContent='This marker falls beyond the playable audio.';return;}
    const at=Math.max(0,p.ms/1000-desk.lead);
    try{audio.currentTime=at;audio.play().catch(()=>{if(sourceURL)note.textContent='Press Play to hear the selected marker.';});
      note.textContent='Playing from '+stamp(Math.round(at*1000))+' · '+p.label+' at '+stamp(p.ms)+'.';
    }catch{note.textContent='This browser could not seek to the marker.';}
  }
  const remove=button('Remove selected marker',()=>{
    const pos=desk.points.indexOf(desk.selected);
    if(pos<0)return;
    const [point]=desk.points.splice(pos,1);
    desk.removed.push({point,pos});if(desk.removed.length>20)desk.removed.shift();
    desk.selected=null;changed();showEditor();render();undo.focus();
    note.textContent='Removed '+point.label+' at '+stamp(point.ms)+'.';
  });
  const undo=button('Undo removal',()=>{
    if(!desk.removed.length)return;
    if(desk.points.length>=maxPoints){note.textContent='Remove another marker before restoring this one.';return;}
    const {point,pos}=desk.removed.pop();
    desk.points.splice(pos,0,point);desk.points.sort((a,b)=>a.ms-b.ms);
    desk.query='';search.value='';changed();select(point,false);name.focus();
    note.textContent='Restored '+point.label+' at '+stamp(point.ms)+'.';
  });
  function navigate(step){
    const matches=matching(),ix=matches.indexOf(desk.selected);
    const p=matches[ix<0?(step>0?0:matches.length-1):ix+step];if(p)select(p,true);
  }
  const previous=button('Previous marker',()=>navigate(-1)),next=button('Next marker',()=>navigate(1));
  const navigation=add(e('div','quick-find'),previous,next);
  const previousPage=button('Previous page',()=>{desk.page--;render();}),nextPage=button('Next page',()=>{desk.page++;render();});
  const pager=add(e('div','quick-find'),previousPage,nextPage);
  function mark(category){
    if(!desk.file||audio.readyState<1)return;
    if(desk.points.length>=maxPoints){note.textContent='This index already has 20,000 markers.';return;}
    const p={ms:Math.round(audio.currentTime*1000),type:category,label:category==='Moment'?'Marker '+(desk.points.length+1):category};
    desk.points.push(p);desk.points.sort((a,b)=>a.ms-b.ms);
    desk.query='';search.value='';changed();select(p,false);note.textContent='Marked '+category+' at '+stamp(p.ms)+'.';
  }
  const marking=add(e('div','quick-find'),...types.slice(0,4).map((t,i)=>button(i?t:'Mark current position',()=>mark(t),i?'btn':'btn primary')));
  // Bound resident memory while checking every audio byte, including in multi-hour sessions.
  async function fingerprint(file,active=()=>file===desk.file){
    if(!window.crypto?.subtle)return null;
    const size=4*1024*1024,parts=new Uint8Array(Math.ceil(file.size/size)*32);
    for(let off=0,i=0;off<file.size;off+=size,i++){
      const data=await file.slice(off,off+size).arrayBuffer();
      parts.set(new Uint8Array(await crypto.subtle.digest('SHA-256',data)),i*32);
      if(!active())throw Error('Recording changed.');
    }
    const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',parts));
    return 'sha256-chunks-4m-v1:'+Array.from(digest,b=>b.toString(16).padStart(2,'0')).join('');
  }
  function digestFor(file){
    if(!desk.fingerprint)desk.fingerprint=fingerprint(file).catch(err=>{if(desk.file===file)desk.fingerprint=null;throw err;});
    return desk.fingerprint;
  }
  const download=button('Save marker index',async()=>{
    const file=desk.file,ver=generation;if(!file||exporting)return;
    exporting=true;render();note.textContent='Checking audio and preparing the index…';
    try{
      const digest=await digestFor(file);if(generation!==ver||desk.file!==file)return;
      const data={format:'total-recall-markers-1',title:desk.title,audioBytes:file.size,markers:desk.points.map(p=>({ms:p.ms,label:p.label,category:p.type}))};
      if(digest)data.audioFingerprint=digest;
      const url=URL.createObjectURL(new Blob([JSON.stringify(data)],{type:'application/json'})),a=e('a');
      a.href=url;a.download=file.name+'.markers.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),60000);
      desk.exported=desk.revision;updateReceipt();note.textContent='Index download requested · '+plural(data.markers.length,'marker')+(digest?' · bound to this audio.':' · matched by size only.');
    }catch{if(generation===ver)note.textContent='Could not prepare the index. Your markers remain in this tab.';}
    finally{if(generation===ver){exporting=false;render();}}
  });
  function merge(data){
    const key=p=>JSON.stringify([p.ms,p.label,p.type]),seen=new Set(desk.points.map(key)),fresh=[];
    for(const m of data.markers){const p={ms:m.ms,label:m.label,type:m.category||'Moment'},k=key(p);if(!seen.has(k)){fresh.push(p);seen.add(k);}}
    if(desk.points.length+fresh.length>maxPoints){note.textContent='Merging would exceed 20,000 markers.';return;}
    const hadEdits=dirty(),hadPoints=desk.points.length>0;
    if(!hadPoints&&!hadEdits&&typeof data.title==='string')desk.title=data.title.slice(0,200)||desk.title;
    desk.points.push(...fresh);desk.points.sort((a,b)=>a.ms-b.ms);
    if(fresh.length&&(hadPoints||hadEdits))changed();
    pending.replaceChildren();record.textContent=desk.title;render();
    note.textContent=plural(fresh.length,'marker')+' added · '+plural(data.markers.length-fresh.length,'duplicate')+' skipped.';
  }
  async function openIndex(chosen,file,replace){
    // Validate both files before changing the playing recording or asking to discard any edits.
    const job=++importVersion,active=()=>job===importVersion;
    pending.replaceChildren();note.textContent='Reading '+chosen.name+'…';
    try{
      if(chosen.size>16*1024*1024)throw Error('Index exceeds 16 MiB.');
      const data=JSON.parse(await chosen.text());if(!active())return;
      if(data?.format!=='total-recall-markers-1')throw Error('Choose a Total Recall marker index.');
      if(!Number.isSafeInteger(data.audioBytes)||data.audioBytes<0)throw Error('The index has no valid audio size.');
      if(data.audioBytes!==file.size)throw Error('Index and audio do not match. '+chosen.name+' expects '+number(data.audioBytes)+' bytes. '+file.name+' has '+number(file.size)+'.');
      if(!Array.isArray(data.markers)||data.markers.length>maxPoints||!data.markers.every(p=>p&&Number.isSafeInteger(p.ms)&&p.ms>=0&&
        typeof p.label==='string'&&p.label.length<=160&&(p.category==null||(typeof p.category==='string'&&p.category.length<=42))))throw Error('Invalid marker index.');
      // A newly selected, identical audio file can safely reuse edits and playback position.
      let digest=null,same=desk.file===file;
      if(data.audioFingerprint!==undefined||(replace&&desk.file?.size===file.size)){
        note.textContent='Checking audio contents…';
        digest=await (same?digestFor(file):fingerprint(file,active));if(!active())return;
        if(data.audioFingerprint!==undefined&&!digest)throw Error('Open this archive on HTTPS or localhost to verify the audio fingerprint.');
        if(data.audioFingerprint!==undefined&&data.audioFingerprint!==digest)throw Error('Audio fingerprint could not be matched. Existing markers are intact. '+chosen.name+' belongs to different audio than '+file.name+'.');
        if(!same&&digest&&desk.file?.size===file.size){same=digest===await digestFor(desk.file);if(!active())return;}
      }
      const apply=()=>{
        if(!active())return;
        if(replace&&!same&&!replaceFile(file,digest)){pending.replaceChildren();note.textContent='Kept your current recording and markers.';return;}
        merge(data);
      };
      if(data.audioFingerprint!==undefined){apply();}
      else{
        note.textContent='This older index matches the file size only. Check both names before merging.';
        pending.replaceChildren(e('span','',file.name+' · '+String(data.title||chosen.name).slice(0,200)+' · '+plural(data.markers.length,'marker')),
          button('Merge these markers',apply),
          button('Cancel',()=>{pending.replaceChildren();note.textContent='Index left unopened.';}));
      }
    }catch(err){if(active())note.textContent=err instanceof SyntaxError?'Invalid JSON index.':err.message;}
  }
  index.onchange=()=>{
    const chosen=index.files[0];index.value='';
    if(chosen&&desk.file)openIndex(chosen,desk.file,false);
  };
  function loadFile(){
    if(sourceURL)URL.revokeObjectURL(sourceURL);sourceURL='';
    audio.pause();audio.removeAttribute('src');
    if(desk.file){sourceURL=URL.createObjectURL(desk.file);audio.src=sourceURL;}else audio.load();
    enhanced.restore();
    record.textContent=desk.file?desk.title:'Open a long recording';
    library.hidden=toolbox.hidden=!desk.file;updateReceipt();showEditor();render();
  }
  function replaceFile(file,digest=null){
    if(dirty()&&!confirm('Discard the marker changes for this recording? Cancel to download its index first.'))return false;
    generation++;importVersion++;exporting=false;
    Object.assign(desk,{file,title:file?.name||'',points:[],selected:null,position:0,query:'',page:0,revision:0,exported:0,fingerprint:digest?Promise.resolve(digest):null,removed:[]});
    search.value='';pending.replaceChildren();note.textContent='';loadFile();return true;
  }
  files.type=index.type='file';files.multiple=true;files.accept='audio/*,.aac,.m4a,.json,application/json';index.accept='.json,application/json';
  files.onchange=()=>{
    const chosen=Array.from(files.files);files.value='';if(!chosen.length)return;
    importVersion++;pending.replaceChildren();
    const isIndex=f=>f.type==='application/json'||/\.json$/i.test(f.name);
    const indexes=chosen.filter(isIndex),recordings=chosen.filter(f=>!isIndex(f));
    if(recordings.length>1||indexes.length>1){note.textContent='Choose one recording and up to one marker index at a time.';return;}
    const file=recordings[0],idx=indexes[0];
    if(file&&!file.type.startsWith('audio/')&&!/\.(aac|m4a|wav|mp3|flac|ogg|oga|opus|webm|mp4|aif|aiff|amr|3gp)$/i.test(file.name)){
      note.textContent='Choose an audio recording. '+file.name+' was left unopened.';return;
    }
    if(!file&&!desk.file){note.textContent='Select the audio together with its marker index, or open the audio first.';return;}
    if(idx)openIndex(idx,file||desk.file,Boolean(file));
    else replaceFile(file);
  };
  audio.id='session-audio';audio.controls=true;audio.preload='metadata';audio.setAttribute('aria-label','Session recording');
  audio.onloadedmetadata=()=>{if(!sourceURL||audio.currentSrc!==sourceURL)return;if(desk.position>0){try{audio.currentTime=desk.position;}catch{}}render();};
  audio.onerror=()=>{if(sourceURL)note.textContent='This browser could not play the audio. You can still open and edit the marker index.';render();};
  name.maxLength=160;name.oninput=()=>{const p=desk.selected;if(!p)return;const label=name.value.trim()||p.type;
    if(label!==p.label){p.label=label;changed();render();}
  };
  name.onblur=()=>{if(desk.selected)name.value=desk.selected.label;};
  type.onchange=()=>{if(desk.selected){desk.selected.type=type.value;changed();render();}};
  add(editor,add(e('label'),'Selected marker name',name),add(e('label'),'Marker type',type),remove);
  search.type='search';search.value=desk.query;search.placeholder='Name, type or timestamp';search.oninput=()=>{desk.query=search.value;desk.page=0;render();};
  for(const n of [0,2,5,10,15]){const o=e('option','',n?n+' seconds':'At the marker');o.value=n;lead.append(o);}
  lead.value=desk.lead;lead.onchange=()=>{desk.lead=Number(lead.value);};
  note.setAttribute('role','status');list.className='quick-find';
  add(toolbox,audio,enhanced.controls,enhanced.message,marking,editor,add(e('div','quick-find'),download,button('Close recording',()=>replaceFile(null))),receipt);
  add(library,heading('Markers'),add(e('div','find-filters'),add(e('label'),'Find a marker',search),add(e('label'),'Replay lead-in',lead)),
    navigation,summary,list,pager,add(e('label'),'Open matching marker index',index));
  const guide=e('details','detail-block');add(guide,e('summary','','Recording on Android'),
    e('p','','Start under Long sessions, or say “Lexi, start a long session” while listening. Say “Lexi, mark this point” as you go and “Lexi, finish the session” to finish. Export the audio and optional marker index from the saved recording.'));
  $('view').replaceChildren(add(e('section','panel review-card'),record,add(e('label'),'Open audio from device and optional marker index',files),
    e('p','section-note','Select the recording and its .markers.json file together. Both stay in this tab.'),note,pending),toolbox,undo,library,guide);
  loadFile();
  sessionCleanup=()=>{generation++;importVersion++;if(desk.file&&audio.readyState>=1)desk.position=audio.currentTime;
    sourceURL&&URL.revokeObjectURL(sourceURL);sourceURL='';audio.pause();enhanced.close();audio.removeAttribute('src');audio.load();};
}

function route(){let r=location.hash.slice(1);if(r==='content')return;
  const clipLink=/^clip\/([A-Za-z0-9_-]{1,128})$/.exec(r);
  const lineLink=/^journal\/([A-Za-z0-9_-]{1,128})\/line\/(\d{1,8})$/.exec(r);
  const journalLink=/^journal\/([A-Za-z0-9_-]{1,128})$/.exec(r);
  if(clipLink||lineLink||journalLink){r='archive';}
  if(r==='journal'){state.kind='journal';state.query='';state.device='all';state.date='';state.clock='';state.fold=0;state.category='';state.offset=0;r='archive';}if(!Object.hasOwn(viewInfo,r))r='overview';if(sessionCleanup){sessionCleanup();sessionCleanup=null;}state.view=r;clearTimeout(searchTimer);$('period-wrap').hidden=['archive','review','sessions','saved-lines'].includes(r);$('crumb').textContent=r==='saved-lines'?'Saved lines':r[0].toUpperCase()+r.slice(1);[$('page-title').textContent,$('subtitle').textContent]=viewInfo[r];document.title=`${viewInfo[r][0]} · Total Recall`;
  const journalView=location.hash==='#journal';
  if(journalView){
    $('page-title').textContent='Full journal';
    $('subtitle').textContent='Words from your connected recorders, newest first.';
    $('crumb').textContent='Journal';document.title='Full journal · Total Recall';
  }
  for(const a of document.querySelectorAll('[data-nav]')){if(a.dataset.nav===(journalView?'journal':r==='saved-lines'?'review':r))a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');}
  $('view').replaceChildren(e('p','empty','Loading…'));refresh();
  if(clipLink)openItem({kind:'clip',id:clipLink[1]});
  else if(journalLink)openItem({kind:'journal',id:journalLink[1]});
  else if(lineLink)openItem({kind:'journal',id:lineLink[1]},{reader:{query:'',sourceLine:Number(lineLink[2])}});
  else if($('detail').open)$('detail').close();
}
function contentBlock(title,text,css=''){return add(e('section','detail-block'),e('h3','',title),e('p',css,text));}
function mediaPath(item){return `/api/media/${encodeURIComponent(item.kind)}/${encodeURIComponent(item.id)}`;}
function audioHeader(format,channels,rate,bits,size){const out=new Uint8Array(44);const v=new DataView(out.buffer);const str=(offset,s)=>{for(let i=0;i<s.length;i++)v.setUint8(offset+i,s.charCodeAt(i));};const stride=channels*bits/8;str(0,'RIFF');v.setUint32(4,36+size,true);str(8,'WAVE');str(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,format,true);v.setUint16(22,channels,true);v.setUint32(24,rate,true);v.setUint32(28,rate*stride,true);v.setUint16(32,stride,true);v.setUint16(34,bits,true);str(36,'data');v.setUint32(40,size,true);return out;}
function wavInfo(buf){const dv=new DataView(buf),read=(i)=>String.fromCharCode(...new Uint8Array(buf,i,4));if(buf.byteLength<44||read(0)!=='RIFF'||read(8)!=='WAVE')return null;
  let format,channels,rate,bits;for(let p=12;p+8<=buf.byteLength;){const name=read(p),size=dv.getUint32(p+4,true);p+=8;
    if(name==='fmt '&&size>=16&&p+16<=buf.byteLength){format=dv.getUint16(p,true);channels=dv.getUint16(p+2,true);rate=dv.getUint32(p+4,true);bits=dv.getUint16(p+14,true);}
    if(name==='data'&&format===1&&channels>0&&channels<=8&&rate>0&&[8,16,24,32].includes(bits))return {format,channels,rate,bits,size,offset:p};p+=size+(size%2);}return null;}
async function fetchBytes(path,start,end,signal){const res=await fetch(path,{headers:{Range:`bytes=${start}-${end}`},signal,credentials:'same-origin'});if(!res.ok)throw Error(`Audio request failed (HTTP ${res.status}).`);let buf=await res.arrayBuffer();if(res.status===200)buf=buf.slice(start,end+1);return buf;}
async function excerptBlob(item,from,to,signal){const url=mediaPath(item),initial=await fetchBytes(url,0,65535,signal),src=wavInfo(initial);
  if(src){const frameBytes=src.channels*src.bits/8;const first=Math.min(Math.floor(src.size/frameBytes),Math.round(from*src.rate)),last=Math.min(Math.floor(src.size/frameBytes),Math.round(to*src.rate));if(last<=first)throw Error('This range contains no audio samples.');
    const data=await fetchBytes(url,src.offset+first*frameBytes,src.offset+last*frameBytes-1,signal);if(data.byteLength!==(last-first)*frameBytes)throw Error('The requested range was incomplete.');return new Blob([audioHeader(src.format,src.channels,src.rate,src.bits,data.byteLength),data],{type:'audio/wav'});}
  // Browser decoders need the whole encoded source. Avoid a large allocation on phones.
  if(item.durationMs>1200000||item.bytes>96*1024*1024)throw Error('For this large compressed source, use the phone editor or download the original.');
  const res=await fetch(url,{signal});if(!res.ok)throw Error('Could not retrieve the original audio.');const ctx=new AudioContext({sampleRate:16000});let decoded;
  try{decoded=await ctx.decodeAudioData(await res.arrayBuffer());}finally{await ctx.close();}
  signal.throwIfAborted();const rate=decoded.sampleRate,first=Math.min(decoded.length,Math.round(from*rate)),last=Math.min(decoded.length,Math.round(to*rate)),count=last-first;
  if(count<1)throw Error('This range contains no audio samples.');const channels=decoded.numberOfChannels,sites=Array.from({length:channels},(_,i)=>decoded.getChannelData(i));const payload=new Uint8Array(count*channels*2),out=new DataView(payload.buffer);
  let pos=0;for(let frame=first;frame<last;frame++){for(const channel of sites){const value=Math.max(-1,Math.min(1,channel[frame]));out.setInt16(pos,Math.round(value*(value<0?32768:32767)),true);pos+=2;}}
  return new Blob([audioHeader(1,channels,rate,16,payload.byteLength),payload],{type:'audio/wav'});}
function excerptControls(item,audio,signal){const details=e('details'),summary=e('summary','','Make an excerpt');details.append(summary,e('p','hint','Set a start and end time in seconds. Downloading creates a separate WAV file.'));
  const secs=item.durationMs/1000,inputs=[];const fields=e('div','trim');for(const [label,value] of [['Start (seconds)',0],['End (seconds)',secs]]){const n=e('input');n.type='number';n.min='0';n.step='0.01';n.max=String(secs);n.value=String(value);const field=e('label','',label);add(field,n);fields.append(field);inputs.push(n);}const [a,b]=inputs;
  const message=e('div','status');message.setAttribute('role','status');let boundary=null,timer;
  function stopPreview(){if(timer)clearTimeout(timer);timer=null;boundary=null;}
  function check(){if(boundary==null)return;if(audio.currentTime>=boundary-.02){audio.pause();stopPreview();return;}clearTimeout(timer);timer=setTimeout(check,Math.max(25,Math.min(300,(boundary-audio.currentTime)*1000/Math.max(.25,audio.playbackRate))));}
  audio.addEventListener('timeupdate',check);audio.addEventListener('pause',stopPreview);signal.addEventListener('abort',stopPreview,{once:true});
  function values(){const x=a.valueAsNumber,y=b.valueAsNumber;if(!(x>=0&&y>x&&y<=secs+.001))throw Error(`Choose an end after the start and within ${secs.toFixed(2)} seconds.`);return [x,y];}
  const preview=button('Preview range',async()=>{try{const [from,to]=values();message.textContent='';audio.currentTime=from;await audio.play();boundary=to;check();}catch(err){message.textContent=err.message;}});
  const save=button('Download excerpt',async()=>{try{const [from,to]=values();save.disabled=true;message.textContent='Preparing WAV…';const blob=await excerptBlob(item,from,to,signal);if(signal.aborted)return;const url=URL.createObjectURL(blob),l=e('a');l.href=url;l.download=`${item.id}-${from.toFixed(2)}s-${to.toFixed(2)}s.wav`;l.click();setTimeout(()=>URL.revokeObjectURL(url),60000);message.textContent='Excerpt ready for download.';}catch(err){if(!signal.aborted)message.textContent=err.message;}finally{save.disabled=false;}},'btn primary');
  return add(e('div','detail-block'),add(details,fields,add(e('div','actions'),preview,save),message));}
// A remembered level lasts only for this page's open tab.
let tabPlaybackBoost = null;
function playbackEnhancement(audio, allowVoiceFocus = false) {
  const boost = e('select'), remember = e('input'), message = e('small', 'section-note');
  for (const db of [0, 6, 12]) {
    const opt = e('option', '', db ? '+' + db + ' dB' : 'Original');
    opt.value = String(db); boost.append(opt);
  }
  boost.setAttribute('aria-label', 'Playback boost');
  remember.type = 'checkbox';
  const boostLabel = add(e('label'), 'Playback boost ', boost);
  const rememberLabel = add(e('label'), remember, ' Remember in this tab');
  const focus = button('Voice focus', () => { focused = !focused; apply(); });
  focus.setAttribute('aria-pressed', 'false');
  const controls = add(e('div', 'player-opts'), boostLabel, rememberLabel);
  if (allowVoiceFocus) controls.append(focus);
  message.setAttribute('role', 'status'); message.hidden = true;
  let ctx, source, highpass, voiceGain, voiceCompressor, gain, ceiling;
  let focused = false, disposed = false, revision = 0;
  const Audio = window.AudioContext || window.webkitAudioContext;
  function tell(text) { message.textContent = text; message.hidden = !text; }
  function labels() {
    focus.textContent = focused ? 'Voice focus on' : 'Voice focus';
    focus.setAttribute('aria-pressed', String(focused));
  }
  function route() {
    if (!source) return;
    source.disconnect(); voiceCompressor.disconnect();
    const dest = Number(boost.value) ? gain : ctx.destination;
    gain.gain.value = 10 ** (Number(boost.value) / 20);
    if (focused) { source.connect(highpass); voiceCompressor.connect(dest); }
    else source.connect(dest);
  }
  function nodes() {
    if (source) return;
    highpass = ctx.createBiquadFilter(); highpass.type = 'highpass'; highpass.frequency.value = 90;
    voiceGain = ctx.createGain(); voiceGain.gain.value = 3;
    voiceCompressor = ctx.createDynamicsCompressor(); voiceCompressor.threshold.value = -12; voiceCompressor.ratio.value = 8;
    highpass.connect(voiceGain).connect(voiceCompressor);
    gain = ctx.createGain();
    ceiling = ctx.createWaveShaper();
    // Linear for quiet audio. Curve the top quarter smoothly to a fixed peak below full scale.
    ceiling.curve = Float32Array.from({length:2049}, (_,i) => {
      const x = i/1024-1, level = Math.abs(x);
      if (level <= .75) return x;
      const t = (level-.75)/.25;
      return Math.sign(x)*(.75+.25*(t-t**3/3));
    });
    gain.connect(ceiling).connect(ctx.destination);
    // Attach last, after resume and node creation succeed. Until then native playback works.
    source = ctx.createMediaElementSource(audio);
  }
  async function apply() {
    const serial = ++revision;
    if (disposed) return;
    labels();
    if (!focused && boost.value === '0' && (!source || ctx.state === 'running')) { route(); tell(''); return; }
    try {
      ctx ||= new Audio();
      if (ctx.state !== 'running') await ctx.resume();
      if (serial !== revision || disposed) return;
      if (ctx.state !== 'running') throw Error('Audio unavailable');
      nodes(); route(); tell('');
    } catch {
      if (serial !== revision || disposed) return;
      focused = false; boost.value = '0'; labels();
      if (source) { source.disconnect(); source.connect(ctx.destination); }
      else if (ctx) { ctx.close().catch(()=>{}); ctx = null; }
      if (remember.checked) tabPlaybackBoost = 0;
      tell('Playback processing is unavailable. Use the original audio level.');
    }
  }
  function restore() {
    ++revision; focused = false; labels(); tell('');
    remember.checked = tabPlaybackBoost !== null;
    boost.value = String(tabPlaybackBoost || 0);
    if (source) route();
    setAvailable();
  }
  function setAvailable() {
    boost.disabled = remember.disabled = focus.disabled = !Audio || Boolean(audio.error);
    if (!Audio) tell('This browser supports original-level playback only.');
  }
  function onError() {
    ++revision; focused = false; boost.value = '0'; labels(); route(); setAvailable();
  }
  function onPlay() { if (focused || boost.value !== '0' || ctx?.state === 'suspended' && source) apply(); }
  boost.onchange = () => { if (remember.checked) tabPlaybackBoost = Number(boost.value); apply(); };
  remember.onchange = () => { tabPlaybackBoost = remember.checked ? Number(boost.value) : null; };
  audio.addEventListener('play', onPlay);
  audio.addEventListener('error', onError);
  audio.addEventListener('loadedmetadata', setAvailable);
  restore();
  return {controls, message, restore, close() {
    disposed = true; ++revision;
    audio.removeEventListener('play', onPlay); audio.removeEventListener('error', onError);
    audio.removeEventListener('loadedmetadata', setAvailable);
    for (const node of [source, highpass, voiceGain, voiceCompressor, gain, ceiling]) { if (node) node.disconnect(); }
    if (ctx) ctx.close().catch(()=>{});
  }};
}

function player(item,at){const audio=e('audio');audio.controls=true;audio.preload='metadata';audio.src=mediaPath(item);if(Number.isFinite(at))audio.addEventListener('loadedmetadata',()=>{
    const moment=(at-item.startedAtMillis)/1000;
    if(!Number.isFinite(audio.duration)||moment<0||moment>audio.duration){
      help.textContent='The timestamp falls outside the playable recording. Press Play to hear the available audio.';help.hidden=false;return;
    }
    try{audio.currentTime=Math.max(0,moment-3);}catch{help.textContent='This browser could not seek to that time. Use the player to listen.';help.hidden=false;}
  },{once:true});audio.setAttribute('aria-label','Original recording');const rates=e('select');rates.setAttribute('aria-label','Playback speed');for(const n of [.5,.75,1,1.25,1.5,2]){const o=e('option','',`${n}×`);o.value=n;o.selected=n===1;rates.append(o);}rates.onchange=()=>audio.playbackRate=Number(rates.value);
  const download=e('a','text-link','Download original');download.prepend(icon('download'));download.href=mediaPath(item)+'?download=1';download.download='';const help=e('p','status');help.hidden=true;audio.onerror=()=>{if(audio.getAttribute('src')){help.textContent='This browser could not play the file. Download the original to open it in another player.';help.hidden=false;}};
  const enhanced=playbackEnhancement(audio,true);
  const abort=new AbortController();modalCleanup=()=>{abort.abort();audio.pause();enhanced.close();audio.removeAttribute('src');audio.load();};
  return [add(e('div','player'),audio,add(e('div','player-opts'),add(e('label'),'Speed ',rates),download),enhanced.controls,enhanced.message,help),item.durationMs>0?excerptControls(item,audio,abort.signal):null];}
// The journal export uses the phone's local date plus its UTC offset at recognition.
// Compare matching waveforms in two separated windows. Repetitive or weak matches keep
// their independent channels and clock alignment rather than being summed as speech.
function matchMics(a,b,rate){
  const n=Math.min(a.length,b.length),span=Math.round(.30*rate),margin=Math.round(.30*rate);
  if(n<rate*2)return null;
  function measure(center){
    let strongest=-1,begin=center;
    for(let start=center;start+span+margin<n&&start<center+rate*.35;start+=Math.round(rate*.04)){
      let e=0;for(let j=0;j<span;j+=13){const k=start+j;e+=(a[k]-a[k-1])**2;}
      if(e>strongest){strongest=e;begin=start;}
    }
    if(strongest<1e-5)return null;
    function score(lag,stride){
      let ab=0,aa=0,bb=0;
      for(let j=0;j<span;j+=stride){const k=begin+j,l=k+lag,x=a[k]-a[k-1],y=b[l]-b[l-1];aa+=x*x;bb+=y*y;ab+=x*y;}
      return ab/Math.sqrt(aa*bb||1);
    }
    let peaks=[],best=0,lag=0;
    for(let d=-margin;d<=margin;d++){const r=score(d,53);peaks.push(r);if(Math.abs(r)>Math.abs(best)){best=r;lag=d;}}
    let other=0;for(let i=0;i<peaks.length;i++)if(Math.abs(i-margin-lag)>rate*.01)other=Math.max(other,Math.abs(peaks[i]));
    const fine=score(lag,5);return Math.abs(fine)>.48&&Math.abs(best)>other+.06?{lag,sign:Math.sign(fine)}:null;
  }
  const first=measure(Math.max(margin+1,Math.floor(n*.17))),last=measure(Math.min(n-margin-span-1,Math.floor(n*.66)));
  if(!first||!last||first.sign!==last.sign||Math.abs(first.lag-last.lag)>rate*.0015)return null;
  return {lag:Math.round((first.lag+last.lag)/2),sign:first.sign};
}
function micWave(a,b,match,mode){
  const lag=match?.lag||0,begin=Math.max(0,-lag),end=Math.min(a.length,b.length-lag);
  const channels=mode==='stereo'?2:1,pcm=new ArrayBuffer((end-begin)*channels*2),out=new DataView(pcm);
  let offset=0;function sample(x){out.setInt16(offset,Math.round(Math.max(-1,Math.min(1,x))*32767),true);offset+=2;}
  for(let i=begin;i<end;i++){
    const left=a[i],right=b[i+lag];
    if(mode==='stereo'){sample(left);sample(right);}
    else sample(mode==='first'?left:mode==='second'?right:(left+right*match.sign)/2);
  }
  return new Blob([audioHeader(1,channels,16000,16,pcm.byteLength),pcm],{type:'audio/wav'});
}
function micComparison(full,primary){
  const box=e('details','detail-block'),summary=e('summary','','Compare microphones'),options=e('div'),status=e('p','section-note');
  const controller=new AbortController(),signal=controller.signal,previous=modalCleanup;
  let preview=null,objectUrl=null,base=null,second=null,match=null;
  function release(){if(preview){preview.pause();preview.removeAttribute('src');preview.load();}if(objectUrl){URL.revokeObjectURL(objectUrl);objectUrl=null;}}
  modalCleanup=()=>{controller.abort();release();if(previous)previous();};
  box.append(summary,status,options);
  box.ontoggle=async()=>{
    if(!box.open||box.dataset.loaded)return;box.dataset.loaded='1';status.textContent='Looking for another microphone during this recording…';
    try{
      const source=await get('/api/overlaps',{kind:full.kind,id:full.id});if(signal.aborted)return;
      status.textContent=source.items.length?'Choose an overlap to compare up to 20 seconds near the playhead.':'No recording from the other device overlaps this one in this archive.';
      for(const peer of source.items){
        const label=peer.device+' · '+dateTime(peer.startedAtMillis)+' · '+duration(peer.overlapMs);
        const select=button(label,async()=>{
          options.querySelectorAll('button').forEach(x=>x.disabled=true);release();status.textContent='Reading both recordings and checking their waveforms…';
          try{
            const start=Math.max(full.startedAtMillis,peer.startedAtMillis),end=Math.min(full.startedAtMillis+full.durationMs,peer.startedAtMillis+peer.durationMs);
            const at=Math.max(start,Math.min(full.startedAtMillis+(primary?.currentTime||0)*1000,end-20000)),seconds=Math.min(20,(end-at)/1000);
            async function track(item){
              const t=(at-item.startedAtMillis)/1000,blob=await excerptBlob(item,t,t+seconds,signal);
              const ctx=new OfflineAudioContext(1,Math.max(1,Math.round(seconds*16000)),16000);
              const decoded=await ctx.decodeAudioData(await blob.arrayBuffer());
              const src=ctx.createBufferSource();src.buffer=decoded;src.connect(ctx.destination);src.start();
              return (await ctx.startRendering()).getChannelData(0);
            }
            [base,second]=await Promise.all([track(full),track(peer)]);if(signal.aborted)return;
            match=matchMics(base,second,16000);
            status.textContent=match?'Matching sound in two windows · relative offset '+Math.round(match.lag/16)+' ms.':'Clock-aligned only · a common sound could not be verified.';
            options.replaceChildren();const mode=e('select');mode.setAttribute('aria-label','Microphone playback');
            for(const [key,name] of [['stereo','Both mics · left and right'],['first','This recording only'],['second','Other microphone only'],...(match?[['blend','Blend aligned microphones']]:[])]){
              const o=e('option','',name);o.value=key;mode.append(o);}
            preview=e('audio');preview.controls=true;preview.setAttribute('aria-label','Microphone comparison');
            preview.onplay=()=>primary?.pause();
            const download=e('a','btn','Download comparison WAV');
            function render(){release();objectUrl=URL.createObjectURL(micWave(base,second,match,mode.value));preview.src=objectUrl;download.href=objectUrl;download.download='TotalRecall-'+mode.value+'-'+Math.round(at)+'.wav';}
            mode.onchange=render;render();options.append(mode,preview,download,button('Choose another overlap',()=>{release();options.replaceChildren();delete box.dataset.loaded;box.ontoggle();}));
          }catch(err){if(!signal.aborted){status.textContent=err.message;options.querySelectorAll('button').forEach(x=>x.disabled=false);}}
        });options.append(select);
      }
    }catch(err){if(!signal.aborted){status.textContent=err.message;delete box.dataset.loaded;}}
  };return box;
}

const savedLineView={query:'',label:'',offset:0};
function journalTime(at){return dateFmt(at,{month:'short',day:'numeric',year:'numeric',hour:'numeric',minute:'2-digit',second:'2-digit'});}
async function savedLinesPage(ver){
  const data=await get('/api/saved-lines',{q:savedLineView.query,label:savedLineView.label,offset:savedLineView.offset});
  if(ver!==state.version)return;
  if(savedLineView.offset && !data.items.length){savedLineView.offset=Math.max(0,Math.floor((data.total-1)/25)*25);return refresh();}
  const box=e('section'),form=e('form','find-form'),search=e('input'),filter=e('select');
  search.type='search';search.value=savedLineView.query;search.maxLength=500;search.placeholder='Find words…';search.setAttribute('aria-label','Search saved lines');
  filter.setAttribute('aria-label','Saved line category');
  filter.add(new Option('All categories',''));
  for(const name of new Set([...data.labels,savedLineView.label].filter(Boolean)))filter.add(new Option(name,name));
  filter.value=savedLineView.label;
  function apply(){savedLineView.query=search.value.trim();savedLineView.label=filter.value;savedLineView.offset=0;refresh();}
  filter.onchange=apply;form.onsubmit=ev=>{ev.preventDefault();apply();};
  const submit=e('button','btn','Search');submit.type='submit';
  add(form,search,filter,submit);box.append(form);
  const back=e('a','text-link','Review inbox');back.href='#review';
  box.append(add(e('div','review-top'),e('p','section-note',plural(data.total,'saved line')),back));
  for(const line of data.items){
    const journal={kind:'journal',id:line.id},reader={query:'',sourceLine:line.line};
    const card=e('article','panel review-card');
    add(card,e('span','row-sub',[journalTime(line.at),line.label,line.title].filter(Boolean).join(' · ')),e('p','review-snippet',line.wording));
    if(line.original){
      const original=e('details');add(original,e('summary','','Originally heard'),e('p','review-snippet',line.original));card.append(original);
    }
    const source=button('Open source line',()=>openItem(journal,{reader}),'text-link');
    card.append(add(e('div','line-actions'),source,journalAudio('Find audio here',journal,line.at,reader)));
    box.append(card);
  }
  if(!data.total)box.append(panel(empty(savedLineView.query||savedLineView.label?'No matching saved lines.':'No lines saved yet.',
    savedLineView.query||savedLineView.label?'Change the search or category.':'Open a timestamped journal and choose Keep line beside a passage.')));
  const pages=e('div','quick-find');
  if(savedLineView.offset)pages.append(button('Previous saved lines',()=>{savedLineView.offset-=25;refresh();}));
  if(savedLineView.offset+25<data.total)pages.append(button('More saved lines',()=>{savedLineView.offset+=25;refresh();}));
  box.append(pages);$('view').replaceChildren(box);
}
function journalKeep(full,line){
  const controls=e('div','quick-find'),feedback=e('span','section-note');feedback.setAttribute('role','status');
  const source=full.savedLines||(full.savedLines={});
  function draw(){
    const current=Object.hasOwn(source,line.number)?source[line.number]:null;
    controls.replaceChildren();
    async function change(wanted){
      for(const el of controls.querySelectorAll('button,select'))el.disabled=true;
      feedback.textContent='Saving…';
      try{
        const result=await ownerAction('/api/review/file',{kind:'journal',id:full.id,action:'keep-line',sourceSha:full.sha256,
          line:line.number,previous:current,label:wanted});
        if(result.label===null)delete source[line.number];else source[line.number]=result.label;
        if(state.view==='saved-lines')refresh();
        feedback.textContent=result.label===null?'Removed from saved lines.':result.label?'Filed under '+result.label+'.':'Line saved.';
      }catch(err){feedback.textContent=err.message;}
      draw();
    }
    if(current===null)controls.append(button('Keep line',()=>change(''),'text-link'));
    else{
      const select=e('select');select.setAttribute('aria-label','File this saved line');select.add(new Option('Saved line',''));
      for(const name of new Set([...full.filingOptions.labels,current].filter(Boolean)))select.add(new Option(name,name));
      select.value=current;select.onchange=()=>change(select.value);
      const link=e('a','text-link','Link to this line');link.href='/#journal/'+encodeURIComponent(full.id)+'/line/'+line.number;
      const copy=button('Copy line',async()=>{
        try{await navigator.clipboard.writeText(line.text);feedback.textContent='Wording copied.';}
        catch{const text=e('textarea');text.readOnly=true;text.value=line.text;text.setAttribute('aria-label','Wording to copy');controls.append(text);text.focus();text.select();feedback.textContent='Wording selected. Use Copy.';}
      },'text-link');
      controls.append(select,copy,link,button('Unsave line',()=>change(null),'text-link'));
    }
    controls.append(feedback);
  }
  draw();return controls;
}
function journalLines(text) {
  const result=[],pattern=/^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) (Z|[+-]\d{2}:\d{2})  (.+)$/;
  let last=null;
  let number=-1;
  for(const raw of text.split('\n')){
    number++;const line=raw.replace(/\r$/,'');
    const m=pattern.exec(line);
    if(!m){
      if(last && !last.status && line.startsWith('  Originally heard · '))last.original=line.slice('  Originally heard · '.length);
      last=null;
      continue;
    }
    const at=Date.parse(m[1]+'T'+m[2]+m[3]);
    last=Number.isFinite(at)?{at,number,text:m[4],original:'',status:m[4].startsWith('[capture status]')}:null;
    if(last)result.push(last);
  }
  return result;
}
function journalNavigation(id){
  const nav=e('nav','quick-find');
  nav.setAttribute('aria-label','Adjacent journal entries');
  async function load(){
    nav.replaceChildren();
    try{
      const adjacent=await get('/api/journal-neighbors',{id});
      for(const [key,label] of [['earlier','Earlier journal'],['later','Later journal']]){
        const entry=adjacent[key];
        if(!entry)continue;
        const source=entry.id.startsWith('laptop-')?'Mint':'Phone or imported';
        const link=e('a','text-link',label+' · '+source+' · '+journalTime(entry.startedAtMillis));
        link.href='/#journal/'+encodeURIComponent(entry.id);nav.append(link);
      }
    }catch{nav.append(button('Retry adjacent journals',load,'text-link'));}
  }
  load();return nav;
}
function journalReader(full,saved={}){
  const raw=full.text||'',lines=journalLines(raw);
  if(!lines.length)return contentBlock('Journal text',raw||'No text supplied.');
  const box=e('section','detail-block journal-reader'),q=e('input');q.type='search';q.value=saved.query??state.query;q.placeholder='Find words in this journal';q.setAttribute('aria-label','Find words in this journal');
  const count=e('p','section-note'),results=e('div'),pages=e('div','quick-find');
  const batch=20;let start=saved.start||0,version=0,changedOnly=Boolean(saved.changedOnly);
  const missingSource=Number.isInteger(saved.sourceLine)&&!lines.some(line=>line.number===saved.sourceLine && !line.status);
  const compare=button('',()=>{changedOnly=!changedOnly;start=0;draw();});
  compare.hidden=!lines.some(row=>row.original);
  let initial=true;
  function draw(){
    const revision=++version,terms=q.value.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean);
    compare.textContent=changedOnly?'Show all lines':'Compare changed wording';
    compare.setAttribute('aria-pressed',String(changedOnly));
    const rows=lines.filter(row=>(!changedOnly||row.original) &&
      terms.every(term=>(row.text+' '+row.original).toLocaleLowerCase().includes(term)));
    if(initial && !Number.isInteger(saved.start) && Number.isInteger(saved.sourceLine)){
      const index=rows.findIndex(line=>line.number===saved.sourceLine);
      if(index>=0)start=Math.floor(index/batch)*batch;
    }
    initial=false;
    count.textContent=rows.length?((start+1)+'–'+Math.min(start+batch,rows.length)+' of '+plural(rows.length,'line')):'No matching lines.';
    results.replaceChildren();pages.replaceChildren();
    for(const line of rows.slice(start,start+batch)){
      const entry=e('article','journal-line');
      if(line.number===saved.sourceLine){
        entry.classList.add('source-line');entry.tabIndex=-1;entry.setAttribute('aria-label','Selected source line');
        if(!box.isConnected)requestAnimationFrame(()=>{if(entry.isConnected){entry.focus({preventScroll:true});entry.scrollIntoView({block:'nearest'});}});
      }
      const stamp=e('time','row-sub',journalTime(line.at));stamp.dateTime=new Date(line.at).toISOString();
      const choices=e('div','line-actions');
      add(entry,stamp,e('p','',line.text));
      if(line.original){
        const first=e('details');first.open=changedOnly||terms.some(t=>line.original.toLocaleLowerCase().includes(t));
        add(first,e('summary','','Originally heard'),e('p','',line.original));entry.append(first);
      }
      entry.append(choices);results.append(entry);
      if(line.status)continue;
      const reader={query:q.value,start,changedOnly,sourceLine:line.number};
      choices.append(journalKeep(full,line),journalAudio('Find audio here',full,line.at,reader));
    }
    if(start>0)pages.append(button('Previous lines',()=>{start=Math.max(0,start-batch);draw();}));
    if(start+batch<rows.length)pages.append(button('More lines',()=>{start+=batch;draw();}));
  }
  q.oninput=()=>{start=0;draw();};draw();
  const original=e('details');original.append(e('summary','','Full journal text'));
  original.ontoggle=()=>{if(original.open&&original.childElementCount===1)original.append(e('p','raw-journal',raw));};
  const kept=e('a','text-link','Saved journal lines');kept.href='#saved-lines';
  kept.onclick=()=>{if($('detail').open)$('detail').close();if(state.view==='saved-lines')refresh();};
  return add(box,e('h3','','Journal text'),missingSource?e('p','section-note','This source line is unavailable in the journal.'):null,q,add(e('div','quick-find'),compare,kept),e('p','section-note','Timestamps mark when speech was recognized. Linked audio opens with a short lead-in.'),count,results,pages,original);
}
function writingNote(full){
  const text=[full.title||'Untitled clip',dateTime(full.startedAtMillis),full.category,
    full.transcript&&('Recognized speech\n'+full.transcript),full.correction&&('Your correction\n'+full.correction),
    full.note&&('Your note\n'+full.note)].filter(Boolean).join('\n\n');
  const box=e('div','detail-block'),status=e('span','status');status.setAttribute('role','status');
  const copy=button('Copy to writing notes',async()=>{
    try{await navigator.clipboard.writeText(text);status.textContent='Copied. Paste it into your writing app.';}
    catch{let field=box.querySelector('textarea');if(!field){field=e('textarea','pair-code');field.readOnly=true;field.value=text;field.setAttribute('aria-label','Text for your writing notes');box.append(field);}field.focus();field.select();status.textContent='Text selected. Use Copy to take it with you.';}
  });
  return add(box,copy,status);
}
function evidenceRows(items,audio){
  const group=e('div','situation-evidence');
  for(const cue of items){
    const row=e('div','detail-block'),name=e('strong','',cue.label);
    row.append(name);
    if(audio && Number.isFinite(cue.seconds)){
      row.append(button('Play at '+cue.stamp,()=>{audio.currentTime=cue.seconds;audio.play().catch(()=>notice('Use the player to start playback.'));},'text-link'));
    }else if(cue.stamp)row.append(e('span','row-sub','  '+cue.stamp));
    row.append(e('p','',cue.sound?cue.quote:'“'+cue.quote+'”'));group.append(row);
  }
  return group;
}
function storyPanel(full){
  const box=e('section','detail-block'),body=e('textarea'),status=e('p','status');
  const story=full.story||{body:'',revision:0,draft:''};
  let draft=story.draft;
  body.rows=4;body.maxLength=12000;body.value=story.body||draft;body.placeholder='Add what was happening and why it mattered.';
  status.setAttribute('role','status');
  status.textContent=story.body?'Your saved account':draft?'Suggested from source words · edit before saving':'Add your own context, or analyze the recording for a suggested account.';
  const suggestion=e('p','section-note');suggestion.hidden=true;
  const view=button('Show suggested account',()=>{suggestion.hidden=!suggestion.hidden;suggestion.textContent=suggestion.hidden?'':draft;view.textContent=suggestion.hidden?'Show suggested account':'Hide suggested account';});
  view.hidden=!draft;
  const save=button('Save story',async()=>{
    save.disabled=true;
    try{const r=await ownerAction('/api/review/file',{kind:full.kind,id:full.id,action:'story',body:body.value,revision:story.revision});
      story.revision=r.revision;story.body=r.body;status.textContent=r.body?'Story saved and searchable.':'Saved story cleared.';}
    catch(err){status.textContent=err.message;}finally{save.disabled=false;}
  });
  add(box,e('h3','','Recording story'),add(e('label'),'Your account',body),add(e('div','actions'),save,view),suggestion,status);
  return {box,update(text){
    if(!text)return;
    const clean=!story.body && (!body.value || body.value===draft);
    draft=text;if(!suggestion.hidden)suggestion.textContent=draft;view.hidden=false;
    if(clean){body.value=draft;status.textContent='Suggested from recognized words · edit before saving';}
  }};
}
function situationPanel(full,audio,story){
  const groups=full.situation||[];
  if(!groups.length&&!audio)return null;
  const box=e('section','detail-block');
  box.append(e('h3','','Situation'));
  for(const group of groups){
    const cues=group.items.filter(cue=>cue.label!=='Source excerpt');
    if(cues.length && cues[0].sound){
      const saved=e('details');saved.append(e('summary','',group.source+' · '+cues.length+' sound changes'),evidenceRows(cues,audio));box.append(saved);
    }else if(cues.length)box.append(e('p','row-sub',group.source),evidenceRows(cues,audio));
    else box.append(e('p','section-note',group.source+' · no explicit situation cue found.'));
  }
  if(!audio)return box;
  const report=e('div'),status=e('p','section-note');status.setAttribute('role','status');
  const focused=e('input');focused.type='checkbox';
  const mode=add(e('label'),focused,' Try voice-focused transcription for quiet speech');
  let offset=0;
  focused.onchange=()=>{offset=0;review.hidden=false;review.textContent='Analyze original recording';};
  const review=button('Analyze original recording',async()=>{
    review.disabled=true;focused.disabled=true;status.textContent='Listening for sound patterns and transcribing English on this archive…';
    try{
      const r=await ownerAction('/api/analyze-recording',{kind:full.kind,id:full.id,offset,voiceFocus:focused.checked});
      if(!box.isConnected)return;
      story.update(r.storyDraft);
      report.append(e('strong','',(focused.checked?'Voice focus · ':'Original · ')+duration(r.start*1000)+' – '+duration((r.start+r.seconds)*1000)),
        e('p','',r.abovePercent+'% of audio above −50 dBFS · longest quiet stretch '+r.quietSeconds+' s · peak '+(r.peakDbfs==null?'silence':r.peakDbfs+' dBFS')),
        r.cues.length?evidenceRows(r.cues,audio):e('p','section-note','No words recognized in this range.'));
      status.textContent='Speech and playback times are approximate. Background noise counts toward the sound level.';
      if(r.sounds?.length){
        const patterns=e('details');patterns.open=true;
        patterns.append(e('summary','','Sound timeline · '+r.sounds.length),evidenceRows(r.sounds.slice(0,8),audio));
        if(r.sounds.length>8){const rest=e('details');rest.append(e('summary','','More sound changes · '+(r.sounds.length-8)),evidenceRows(r.sounds.slice(8),audio));patterns.append(rest);}
        report.append(patterns);
      }else if('soundAvailable' in r){
        report.append(e('p','section-note',r.soundAvailable?'No clear environmental sound match in this range.':'Sound recognition unavailable for this analysis.'));
      }
      if(r.next==null)review.hidden=true;
      else{offset=r.next;review.textContent='Analyze next 10 minutes';}
    }catch(err){status.textContent=err.message;}
    finally{review.disabled=false;focused.disabled=false;}
  });
  return add(box,mode,review,status,report);
}
async function openItem(item,jump={}){if(modalCleanup){modalCleanup();modalCleanup=null;}const serial=++state.detailVersion;const dialog=$('detail');$('detail-kind').textContent=kindName(item.kind);$('detail-body').replaceChildren(e('p','empty','Loading item…'));if(!dialog.open)dialog.showModal();
  try{const full=await get(`/api/items/${encodeURIComponent(item.kind)}/${encodeURIComponent(item.id)}`);if(serial!==state.detailVersion||!dialog.open)return;
    const d=e('h2','',full.review?.name||full.title||full.category||kindName(full.kind));d.id='detail-title';const meta=add(e('div','detail-meta'),e('span','',dateTime(full.startedAtMillis)),e('span','','·'),e('span','',full.kind==='journal'?bytes(full.bytes):duration(full.durationMs)));
    const nodes=[d,meta];
    if(jump.clock)nodes.push(e('p','section-note','Playback is cued near your remembered time, '+dateTime(jump.at)+'.'));
    if(jump.match)nodes.push(e('p','match-context','Recognized at '+jump.match.stamp+' · “'+jump.match.text+'”'),e('p','section-note','Playback starts up to three seconds before this passage.'));
    if(jump.journal){nodes.push(button('Back to journal',()=>openItem(jump.journal,{reader:jump.reader}),'text-link'),e('p','section-note','Press play to listen near the recognized line.'));}if(full.kind==='journal'){const download=e('a','btn','Download journal');download.prepend(icon('download'));download.href=mediaPath(full)+'?download=1';download.download='';nodes.push(journalNavigation(full.id),journalReader(full,jump.reader),download);}else{nodes.push(...player(full,jump.at));if(full.reason)nodes.push(contentBlock('Why it was kept',full.reason));if(full.transcript||full.kind==='clip')nodes.push(contentBlock('Recognized speech',full.transcript||'No transcript available.'));}
    const playerNode=nodes.find(node=>node?.classList?.contains('player'));
    if(playerNode)nodes.push(micComparison(full,playerNode.querySelector("audio")));
    const story=storyPanel(full);nodes.push(story.box,situationPanel(full,playerNode?.querySelector('audio'),story));
    if(full.correction)nodes.push(contentBlock('Your correction',full.correction));if(full.note)nodes.push(contentBlock('Your note',full.note));
    if(full.kind==='clip')nodes.push(writingNote(full));
    if(full.review)nodes.push(filedWork(full));
    const provenance=e('details');provenance.append(e('summary','','Source details'));const facts=[`Item ID · ${full.id}`,`Stored size · ${bytes(full.bytes)}`];if(full.sessionId)facts.push(`Session · ${full.sessionId}`);if(full.parentId)facts.push(`Original · ${full.parentId}`);if(full.startSample!=null&&full.endSample!=null)facts.push(`Source samples · ${full.startSample}–${full.endSample}`);if(full.expiresAtMillis)facts.push(`Retention until · ${dateTime(full.expiresAtMillis)}`);provenance.append(e('p','',facts.join('\n')));if(full.sha256)provenance.append(e('p','digest',`SHA-256 · ${full.sha256}`));nodes.push(add(e('div','detail-block'),provenance));$('detail-body').replaceChildren(...nodes.filter(Boolean),deletion(full));
  }catch(err){if(serial===state.detailVersion)$('detail-body').replaceChildren(e('p','notice',err.message));}}
$('close').onclick=()=>$('detail').close();$('detail').addEventListener('close',()=>{state.detailVersion++;if(modalCleanup){modalCleanup();modalCleanup=null;}$('detail-body').replaceChildren();});
$('period').onchange=()=>{state.days=Number($('period').value);refresh();};$('refresh').onclick=refresh;window.addEventListener('hashchange',route);$('today').textContent=dateFmt(new Date(),{weekday:'short',month:'short',day:'numeric'});route();

async function openPairing(){
  const dialog=$('detail'),serial=++state.detailVersion;
  $('detail-kind').textContent='Phone connection';
  const title=e('h2','','Pair your phone');title.id='detail-title';
  const help=e('p','pair-hint','In Total Recall on your phone, open the archive connection and tap Scan pairing code. On the same phone, paste the code instead. On Mint, open Connect in the microphone panel and paste the code in Backup. Anyone with the code can send captures to this archive.');
  const message=e('p','status');message.setAttribute('role','status');const reveal=button('Reveal pairing code',async()=>{
    try{reveal.disabled=true;message.textContent='Loading…';const {code}=await get('/api/phone-pairing');if(serial!==state.detailVersion||!dialog.open)return;
      if(typeof code!=='string'||!code.startsWith('TR1.'))throw Error('Pairing code unavailable.');
      const field=e('textarea','pair-code');field.readOnly=true;field.spellcheck=false;field.setAttribute('aria-label','Private phone pairing code');field.value=code;
      const copy=button('Copy pairing code',async()=>{try{await navigator.clipboard.writeText(field.value);message.textContent='Copied. Paste the code into the phone app.';}catch{field.focus();field.select();message.textContent='Code selected. Use your browser’s Copy command.';}},'btn primary');
      const qr=e('img','pair-qr');qr.alt='Private phone pairing QR code';qr.src='/api/phone-qr.png';qr.onerror=()=>qr.remove();
      reveal.replaceWith(qr,field,copy);message.textContent='';
    }catch(err){if(serial===state.detailVersion){message.textContent=err.message;reveal.disabled=false;}}
  },'btn primary');
  $('detail-body').replaceChildren(title,help,reveal,message);if(!dialog.open)dialog.showModal();
}
window.addEventListener('pagehide',()=>{if($('detail').open)$('detail').close();$('detail-body').replaceChildren();});

// Refresh light, read-only views while they're visible. Leave review edits, playback,
// text selection and focused result rows in place.
function updateVisible(){
  if(document.hidden || $('detail').open || $('view').getAttribute('aria-busy')==='true' ||
     state.view==='review' || document.getSelection()?.toString() ||
     $('view').contains(document.activeElement))return;
  refresh();
}
window.setInterval(()=>{if(location.hash==='#journal' && state.offset===0)updateVisible();},5000);
window.setInterval(()=>{if(location.hash!=='#journal')updateVisible();},60000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)updateVisible();});
