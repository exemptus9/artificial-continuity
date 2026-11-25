'use strict';
const $ = id => document.getElementById(id);
const KINDS = {clip:'Selected clip',segment:'Audio segment',journal:'Journal entry'};
const viewInfo = {
  overview:['Archive overview','Saved audio and writing from your listening sessions.'],
  review:['Review inbox','Listen to a moment and file it with one click.'],
  archive:['Your archive','Search saved clips, original audio segments and journal entries.'],
  activity:['Archive activity','See when recordings arrived and how much audio they cover.']
};
const state={view:'overview',days:7,kind:'all',query:'',category:'',device:'all',date:'',offset:0,version:0,detailVersion:0};
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
    const form=e('form');form.method='post';form.action='/logout';
    const signOut=e('button','btn','Sign out');signOut.type='submit';form.append(signOut);box.append(form);
  }return box;}
const reviewView={shelf:'inbox',offset:0,feedback:''};
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
    try{await ownerAction('/api/review/file',{kind:item.kind,id:item.id,action,label});await after(label||action);}
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
  const data=await get('/api/review',{shelf:reviewView.shelf,offset:reviewView.offset});
  if(ver!==state.version)return;
  if(!data.items.length&&reviewView.offset){reviewView.offset=0;return refresh();}
  const holder=e('div'),shelf=e('select');shelf.setAttribute('aria-label','Review collection');
  for(const [key,name] of [['inbox','Needs review'],['filed','Filed'],['dismissed','Dismissed'],...data.shelves.map(name=>['label/'+name,name])]){
    const o=e('option','',name);o.value=key;shelf.append(o);}
  shelf.value=reviewView.shelf;shelf.onchange=()=>{reviewView.shelf=shelf.value;reviewView.offset=0;reviewView.feedback='';refresh();};
  holder.append(add(e('div','review-top'),add(e('label'),'Collection ',shelf),e('span','',plural(data.total,'item'))));
  if(reviewView.feedback){const msg=e('p','section-note',reviewView.feedback);msg.setAttribute('role','status');holder.append(msg);}
  if(!data.items.length)holder.append(panel(empty(reviewView.shelf==='inbox'?'You’re caught up.':'Nothing filed here yet.',
    reviewView.shelf==='inbox'?'Selected clips and laptop bookmarks appear here after they reach this archive.':'Open an archive item to file it here.')));
  for(const item of data.items){
    const card=e('article','panel review-card');
    const open=button(item.review.name||item.title||kindName(item.kind),()=>openItem(item),'review-title');
    add(card,e('p','row-sub',dateTime(item.startedAtMillis)+' · '+(item.category||kindName(item.kind))),open);
    if(item.review.writing||item.snippet)card.append(e('p','review-snippet',(item.review.writing||item.snippet).slice(0,400)));
    if(item.kind!=='journal'){
      const audio=e('audio');audio.controls=true;audio.preload='none';audio.src=mediaPath(item);audio.setAttribute('aria-label','Listen to '+item.title);
      audio.onplay=()=>{for(const other of holder.querySelectorAll('audio'))if(other!==audio)other.pause();};card.append(audio);
    }
    card.append(reviewButtons(item,data.options,async label=>{
      reviewView.feedback=label==='dismiss'?'Dismissed. The original stays in Archive.':label==='reopen'?'Returned to the inbox.':'Updated '+label+'.';
      await refresh();
    }));holder.append(card);
  }
  const pager=e('div','quick-find');
  if(reviewView.offset)pager.append(button('Previous items',()=>{reviewView.offset=Math.max(0,reviewView.offset-data.options.batch);refresh();}));
  if(reviewView.offset+data.items.length<data.total)pager.append(button('Next items',()=>{reviewView.offset+=data.options.batch;refresh();}));
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
  const message=e('p','section-note','Removes this item, its filing and its original media from this archive. Other devices, independent excerpts and earlier backups can keep copies.');
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
function itemRow(item){const row=button('',()=>openItem(item),'row');row.dataset.itemId=item.id;row.dataset.kind=item.kind;
  const when=item.startedAtMillis?dateTime(item.startedAtMillis):'Time unavailable';const tag=item.category||kindName(item.kind);
  add(row,add(e('span',`row-icon ${item.kind}`),icon(KINDS[item.kind]?item.kind:'archive')),add(e('span','row-main'),e('span','row-title',item.title||tag),e('span','row-sub',`${tag} · ${when}`)),
    e('span','time',item.kind==='journal'? 'Text':duration(item.durationMs)),icon('right','chev'));if(item.match)row.querySelector('.row-main').append(e('span','match-preview',item.match.field+' · '+item.match.text));return row;}
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
function dayBounds(value) {
  if (!value) return {};
  const start=new Date(value+'T00:00:00'),end=new Date(start);
  end.setDate(end.getDate()+1);
  return {from:start.getTime(),to:end.getTime()};
}
function findArchive(filters={}) {
  Object.assign(state,{kind:'all',category:'',device:'all',date:'',query:'',offset:0},filters);
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
  function apply(){
    state.query=q.value.trim();state.kind=kinds.value;state.device=device.value;state.category=cats.value;state.date=date.value;state.offset=0;refresh();
  }
  form.onsubmit=ev=>{ev.preventDefault();clearTimeout(searchTimer);apply();};
  q.oninput=()=>{clearTimeout(searchTimer);searchTimer=setTimeout(apply,180);};
  kinds.onchange=()=>{if(kinds.value!=='clip'&&kinds.value!=='all')cats.value='';apply();};
  cats.onchange=()=>{if(cats.value)kinds.value='clip';apply();};date.onchange=apply;device.onchange=apply;
  const filters=add(e('div','find-filters'),add(e('label'),'Item type',kinds),add(e('label'),'Clip label',cats),add(e('label'),'Recorded on',device),add(e('label'),'Date started',date));
  const clear=button('Clear filters',()=>{clearTimeout(searchTimer);findArchive();},'text-link');
  add(form,add(e('label','searchbox'),icon('search'),q),filters,clear);
  const results=e('div');results.id='results';$('view').replaceChildren(form,results);
}
async function archive(ver){if(!$('results'))makeArchive();const response=await get('/api/items',{kind:state.kind,q:state.query,category:state.category,device:state.device,...dayBounds(state.date),offset:state.offset,limit});if(ver!==state.version)return;
  if(state.offset>0&&!response.items.length&&response.total>0){state.offset=Math.floor((response.total-1)/limit)*limit;return refresh();}
  const nodes=[],group=new Map();for(const item of [...response.items].sort((a,b)=>b.startedAtMillis-a.startedAtMillis)){const d=item.startedAtMillis?dateFmt(item.startedAtMillis,{weekday:'long',month:'long',day:'numeric',year:'numeric'}):'Undated';if(!group.has(d))group.set(d,[]);group.get(d).push(item);}
  for(const [d,list] of group)nodes.push(e('h2','group-label',d),panel(...list.map(itemRow)));
  if(!nodes.length)nodes.push(panel(empty(state.query?'No matches.':'No items in this view.',state.query?'Try another word or change the item type.':'Clear filters to look across the archive. New items appear after your phone transfers them.')));
  const prev=button('Previous',()=>{state.offset=Math.max(0,state.offset-limit);refresh();}),next=button('Next',()=>{state.offset+=limit;refresh();});prev.disabled=state.offset===0;next.disabled=state.offset+limit>=response.total;
  const count=response.total?`${state.offset+1}–${Math.min(state.offset+response.items.length,response.total)} of ${plural(response.total,'item')}`:'0 items';const pager=add(e('div','pager'),e('span','',count),response.total>limit?add(e('div'),prev,next):null);
  $('results').replaceChildren(...nodes,pager);}
async function refresh(){const ver=++state.version;notice();$('view').setAttribute('aria-busy','true');try{if(state.view==='overview')await dashboard(ver);else if(state.view==='activity')await activity(ver);else if(state.view==='review')await reviewPage(ver);else await archive(ver);}catch(err){if(ver===state.version){notice(err.message);if(state.view==='archive'&&$('results'))$('results').replaceChildren();else $('view').replaceChildren();}}finally{if(ver===state.version)$('view').setAttribute('aria-busy','false');}}
function route(){let r=location.hash.slice(1);if(r==='content')return;if(!Object.hasOwn(viewInfo,r))r='overview';state.view=r;clearTimeout(searchTimer);$('period-wrap').hidden=['archive','review'].includes(r);$('crumb').textContent=r[0].toUpperCase()+r.slice(1);[$('page-title').textContent,$('subtitle').textContent]=viewInfo[r];document.title=`${viewInfo[r][0]} · Total Recall`;
  for(const a of document.querySelectorAll('[data-nav]')){if(a.dataset.nav===r)a.setAttribute('aria-current','page');else a.removeAttribute('aria-current');}
  $('view').replaceChildren(e('p','empty','Loading…'));refresh();}
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
function player(item,at){const audio=e('audio');audio.controls=true;audio.preload='metadata';audio.src=mediaPath(item);if(Number.isFinite(at))audio.addEventListener('loadedmetadata',()=>{
    audio.currentTime=Math.max(0,Math.min((at-item.startedAtMillis)/1000-3,audio.duration||0));
  },{once:true});audio.setAttribute('aria-label','Original recording');const rates=e('select');rates.setAttribute('aria-label','Playback speed');for(const n of [.5,.75,1,1.25,1.5,2]){const o=e('option','',`${n}×`);o.value=n;o.selected=n===1;rates.append(o);}rates.onchange=()=>audio.playbackRate=Number(rates.value);
  const download=e('a','text-link','Download original');download.prepend(icon('download'));download.href=mediaPath(item)+'?download=1';download.download='';const help=e('p','status');help.hidden=true;audio.onerror=()=>{if(audio.getAttribute('src')){help.textContent='This browser could not play the file. Download the original to open it in another player.';help.hidden=false;}};
  const abort=new AbortController();modalCleanup=()=>{abort.abort();audio.pause();audio.removeAttribute('src');audio.load();};
  return [add(e('div','player'),audio,add(e('div','player-opts'),add(e('label'),'Speed ',rates),download),help),item.durationMs>0?excerptControls(item,audio,abort.signal):null];}
// The journal export uses the phone's local date plus its UTC offset at recognition.
function journalLines(text) {
  const result=[],pattern=/^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) (Z|[+-]\d{2}:\d{2})  (.+)$/;
  for(const line of text.split('\n')){
    const m=pattern.exec(line);if(!m)continue;
    const at=Date.parse(m[1]+'T'+m[2]+m[3]);
    if(Number.isFinite(at))result.push({at,text:m[4],status:m[4].startsWith('[capture status]')});
  }
  return result;
}
function journalReader(full,saved={}){
  const raw=full.text||'',lines=journalLines(raw);
  if(!lines.length)return contentBlock('Journal text',raw||'No text supplied.');
  const box=e('section','detail-block journal-reader'),q=e('input');q.type='search';q.value=saved.query??state.query;q.placeholder='Find words in this journal';q.setAttribute('aria-label','Find words in this journal');
  const count=e('p','section-note'),results=e('div'),pages=e('div','quick-find');
  const batch=20;let start=saved.start||0,version=0;
  function draw(){
    const revision=++version,terms=q.value.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean);
    const rows=lines.filter(row=>terms.every(term=>row.text.toLocaleLowerCase().includes(term)));
    count.textContent=rows.length?((start+1)+'–'+Math.min(start+batch,rows.length)+' of '+plural(rows.length,'line')):'No matching lines.';
    results.replaceChildren();pages.replaceChildren();
    for(const line of rows.slice(start,start+batch)){
      const entry=e('article','journal-line');
      const stamp=e('time','row-sub',dateTime(line.at));stamp.dateTime=new Date(line.at).toISOString();
      const choices=e('div','line-actions');
      add(entry,stamp,e('p','',line.text),choices);results.append(entry);
      if(line.status)continue;
      const locate=button('Find audio here',async()=>{
        locate.disabled=true;
        try{
          const match=await get('/api/recordings-at',{at:line.at});
          if(revision!==version||!box.isConnected)return;
          choices.replaceChildren();
          if(!match.items.length){choices.append(e('span','section-note','No archived recording covers this timestamp.'));return;}
          for(const recording of match.items){
            const btn=button('Open '+(recording.kind==='segment'?'session audio':recording.title||'selected clip'),()=>openItem(recording,{at:line.at,journal:full,reader:{query:q.value,start}}),'btn');
            choices.append(btn);
          }
          if(match.more)choices.append(e('span','section-note','Showing the first 12 overlapping recordings.'));
        }catch(error){if(box.isConnected){locate.disabled=false;choices.append(e('span','status',error.message));}}
      },'text-link');choices.append(locate);
    }
    if(start>0)pages.append(button('Previous lines',()=>{start=Math.max(0,start-batch);draw();}));
    if(start+batch<rows.length)pages.append(button('More lines',()=>{start+=batch;draw();}));
  }
  q.oninput=()=>{start=0;draw();};draw();
  const original=e('details');original.append(e('summary','','Full journal text'));
  original.ontoggle=()=>{if(original.open&&original.childElementCount===1)original.append(e('p','raw-journal',raw));};
  return add(box,e('h3','','Journal text'),q,e('p','section-note','Timestamps mark when speech was recognized. Linked audio opens with a short lead-in.'),count,results,pages,original);
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
async function openItem(item,jump={}){if(modalCleanup){modalCleanup();modalCleanup=null;}const serial=++state.detailVersion;const dialog=$('detail');$('detail-kind').textContent=kindName(item.kind);$('detail-body').replaceChildren(e('p','empty','Loading item…'));if(!dialog.open)dialog.showModal();
  try{const full=await get(`/api/items/${encodeURIComponent(item.kind)}/${encodeURIComponent(item.id)}`);if(serial!==state.detailVersion||!dialog.open)return;
    const d=e('h2','',full.review?.name||full.title||full.category||kindName(full.kind));d.id='detail-title';const meta=add(e('div','detail-meta'),e('span','',dateTime(full.startedAtMillis)),e('span','','·'),e('span','',full.kind==='journal'?bytes(full.bytes):duration(full.durationMs)));
    const nodes=[d,meta];if(jump.journal){nodes.push(button('Back to journal',()=>openItem(jump.journal,{reader:jump.reader}),'text-link'),e('p','section-note','Press play to listen near the recognized line.'));}if(full.kind==='journal'){const download=e('a','btn','Download journal');download.prepend(icon('download'));download.href=mediaPath(full)+'?download=1';download.download='';nodes.push(download,journalReader(full,jump.reader));}else{nodes.push(...player(full,jump.at));if(full.reason)nodes.push(contentBlock('Why it was kept',full.reason));if(full.transcript||full.kind==='clip')nodes.push(contentBlock('Recognized speech',full.transcript||'No transcript available.'));}
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
  const help=e('p','pair-hint','In Total Recall on your phone, open the archive connection and tap Scan pairing code. On the same phone, copy and paste the code instead. Anyone with the code can send captures to this archive.');
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
