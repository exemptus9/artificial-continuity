'use strict';
const $ = id => document.getElementById(id);
const KINDS = {clip:'Selected clip',segment:'Audio segment',journal:'Journal entry'};
const viewInfo = {
  overview:['Archive overview','Saved audio and writing from your listening sessions.'],
  archive:['Your archive','Search saved clips, original audio segments and journal entries.'],
  activity:['Archive activity','See when recordings arrived and how much audio they cover.']
};
const state={view:'overview',days:7,kind:'all',query:'',offset:0,version:0,detailVersion:0};
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
    const form=e('form');form.method='post';form.action='/logout';
    const signOut=e('button','btn','Sign out');signOut.type='submit';form.append(signOut);box.append(form);
  }return box;}
function kindName(kind){return KINDS[kind]||'Archive item';}
function itemRow(item){const row=button('',()=>openItem(item),'row');row.dataset.itemId=item.id;row.dataset.kind=item.kind;
  const when=item.startedAtMillis?dateTime(item.startedAtMillis):'Time unavailable';const tag=item.category||kindName(item.kind);
  add(row,add(e('span',`row-icon ${item.kind}`),icon(KINDS[item.kind]?item.kind:'archive')),add(e('span','row-main'),e('span','row-title',item.title||tag),e('span','row-sub',`${tag} · ${when}`)),
    e('span','time',item.kind==='journal'? 'Text':duration(item.durationMs)),icon('right','chev'));return row;}
function eventPanel(events){if(!events||!events.length)return panel(empty('No activity in this period.','New archive receipts will appear here.'));
  return panel(...events.map(v=>{const t=e('time','',dateTime(v.atMillis));t.dateTime=new Date(v.atMillis).toISOString();return add(e('div','event'),add(e('div'),e('b','',v.label||'Archive receipt'),v.detail?e('p','',v.detail):null,t));}));}
async function dashboard(ver){const [overview,items,info]=await Promise.all([get('/api/overview',range()),get('/api/items',{kind:'clip',limit:5,offset:0}),get('/api/status')]);if(ver!==state.version)return;
  const selected=items.items||[],left=e('div'),right=e('div');add(left,heading('Latest clips',link('View archive','#archive')),panel(...(selected.length?selected.map(itemRow):[empty('No selected clips yet.','Clips appear here when your phone sends a saved moment to this archive.')])))
  add(right,heading('Workspace'),storage(info),heading('Recent activity',link('View activity','#activity')),eventPanel((overview.events||[]).slice(0,2)));
  $('view').replaceChildren(hero(overview),add(e('div','section-grid'),left,right));}
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
function makeArchive(){const form=e('form','archive-tools');form.setAttribute('role','search');const q=e('input');q.id='search';q.type='search';q.placeholder='Search your archive';q.autocomplete='off';q.value=state.query;q.setAttribute('aria-label','Search your archive');const kinds=e('select');kinds.id='kind';kinds.setAttribute('aria-label','Item type');for(const [v,label] of [['all','All items'],['clip','Selected clips'],['segment','Audio segments'],['journal','Journal entries']]){const o=e('option','',label);o.value=v;kinds.append(o);}kinds.value=state.kind;
  form.addEventListener('submit',ev=>{ev.preventDefault();clearTimeout(searchTimer);search();});q.addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(search,170);});kinds.addEventListener('change',()=>{clearTimeout(searchTimer);search();});
  function search(){state.query=q.value.trim();state.kind=kinds.value;state.offset=0;refresh();}
  add(form,add(e('label','searchbox'),icon('search'),q),kinds);const results=e('div');results.id='results';$('view').replaceChildren(form,results);}
async function archive(ver){if(!$('results'))makeArchive();const response=await get('/api/items',{kind:state.kind,q:state.query,offset:state.offset,limit});if(ver!==state.version)return;
  if(state.offset>0&&!response.items.length&&response.total>0){state.offset=Math.floor((response.total-1)/limit)*limit;return refresh();}
  const nodes=[],group=new Map();for(const item of [...response.items].sort((a,b)=>b.startedAtMillis-a.startedAtMillis)){const d=item.startedAtMillis?dateFmt(item.startedAtMillis,{weekday:'long',month:'long',day:'numeric',year:'numeric'}):'Undated';if(!group.has(d))group.set(d,[]);group.get(d).push(item);}
  for(const [d,list] of group)nodes.push(e('h2','group-label',d),panel(...list.map(itemRow)));
  if(!nodes.length)nodes.push(panel(empty(state.query?'No matches.':'No items in this view.',state.query?'Try another word or change the item type.':'Pair your phone in Total Recall Desk or import a recording to get started.')));
  const prev=button('Previous',()=>{state.offset=Math.max(0,state.offset-limit);refresh();}),next=button('Next',()=>{state.offset+=limit;refresh();});prev.disabled=state.offset===0;next.disabled=state.offset+limit>=response.total;
  const count=response.total?`${state.offset+1}–${Math.min(state.offset+response.items.length,response.total)} of ${plural(response.total,'item')}`:'0 items';const pager=add(e('div','pager'),e('span','',count),response.total>limit?add(e('div'),prev,next):null);
  $('results').replaceChildren(...nodes,pager);}
async function refresh(){const ver=++state.version;notice();$('view').setAttribute('aria-busy','true');try{if(state.view==='overview')await dashboard(ver);else if(state.view==='activity')await activity(ver);else await archive(ver);}catch(err){if(ver===state.version){notice(err.message);if(state.view==='archive'&&$('results'))$('results').replaceChildren();else $('view').replaceChildren();}}finally{if(ver===state.version)$('view').setAttribute('aria-busy','false');}}
function route(){let r=location.hash.slice(1);if(r==='content')return;if(!Object.hasOwn(viewInfo,r))r='overview';state.view=r;clearTimeout(searchTimer);$('period-wrap').hidden=r==='archive';$('crumb').textContent=r[0].toUpperCase()+r.slice(1);[$('page-title').textContent,$('subtitle').textContent]=viewInfo[r];document.title=`${viewInfo[r][0]} · Total Recall`;
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
function player(item){const audio=e('audio');audio.controls=true;audio.preload='metadata';audio.src=mediaPath(item);audio.setAttribute('aria-label','Original recording');const rates=e('select');rates.setAttribute('aria-label','Playback speed');for(const n of [.5,.75,1,1.25,1.5,2]){const o=e('option','',`${n}×`);o.value=n;o.selected=n===1;rates.append(o);}rates.onchange=()=>audio.playbackRate=Number(rates.value);
  const download=e('a','text-link','Download original');download.prepend(icon('download'));download.href=mediaPath(item)+'?download=1';download.download='';const help=e('p','status');help.hidden=true;audio.onerror=()=>{if(audio.getAttribute('src')){help.textContent='This browser could not play the file. Download the original to open it in another player.';help.hidden=false;}};
  const abort=new AbortController();modalCleanup=()=>{abort.abort();audio.pause();audio.removeAttribute('src');audio.load();};
  return [add(e('div','player'),audio,add(e('div','player-opts'),add(e('label'),'Speed ',rates),download),help),item.durationMs>0?excerptControls(item,audio,abort.signal):null];}
async function openItem(item){const serial=++state.detailVersion;const dialog=$('detail');$('detail-kind').textContent=kindName(item.kind);$('detail-body').replaceChildren(e('p','empty','Loading item…'));if(!dialog.open)dialog.showModal();
  try{const full=await get(`/api/items/${encodeURIComponent(item.kind)}/${encodeURIComponent(item.id)}`);if(serial!==state.detailVersion||!dialog.open)return;
    const d=e('h2','',full.title||full.category||kindName(full.kind));d.id='detail-title';const meta=add(e('div','detail-meta'),e('span','',dateTime(full.startedAtMillis)),e('span','','·'),e('span','',full.kind==='journal'?bytes(full.bytes):duration(full.durationMs)));
    const nodes=[d,meta];if(full.kind==='journal'){const download=e('a','btn','Download journal');download.prepend(icon('download'));download.href=mediaPath(full)+'?download=1';download.download='';nodes.push(download,contentBlock('Journal text',full.text||full.transcript||'No text supplied.'));}else{nodes.push(...player(full));if(full.reason)nodes.push(contentBlock('Why it was kept',full.reason));if(full.transcript||full.kind==='clip')nodes.push(contentBlock('Recognized speech',full.transcript||'No transcript available.'));}
    if(full.correction)nodes.push(contentBlock('Your correction',full.correction));if(full.note)nodes.push(contentBlock('Your note',full.note));
    const provenance=e('details');provenance.append(e('summary','','Source details'));const facts=[`Item ID · ${full.id}`,`Stored size · ${bytes(full.bytes)}`];if(full.sessionId)facts.push(`Session · ${full.sessionId}`);if(full.parentId)facts.push(`Original · ${full.parentId}`);if(full.startSample!=null&&full.endSample!=null)facts.push(`Source samples · ${full.startSample}–${full.endSample}`);if(full.expiresAtMillis)facts.push(`Retention until · ${dateTime(full.expiresAtMillis)}`);provenance.append(e('p','',facts.join('\n')));if(full.sha256)provenance.append(e('p','digest',`SHA-256 · ${full.sha256}`));nodes.push(add(e('div','detail-block'),provenance));$('detail-body').replaceChildren(...nodes.filter(Boolean));
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
