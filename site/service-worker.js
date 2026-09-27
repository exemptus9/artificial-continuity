/* Version-pinned app shell. Incoming shares are local POST intake, never cached or forwarded. */
'use strict';
const RELEASE='0.15.0',BASE=new URL('./',self.location.href),CACHE='continuity-shell-'+RELEASE;
importScripts('./platform-core.js?v='+RELEASE,'./share-inbox.js?v='+RELEASE);
const ASSETS=['index.html','workspace.css?v=0.11.0','workspace-core.js?v=0.11.0','workspace-store.js?v=0.11.0','workspace.js?v=0.11.0','operations-core.js?v='+RELEASE,'operations.js?v='+RELEASE,'offline.js?v='+RELEASE,'operations.css?v='+RELEASE,'portability-core.js?v='+RELEASE,'sealed-backup.js?v='+RELEASE,'portability.js?v='+RELEASE,'portability.css?v='+RELEASE,'bootstrap-ready.js?v='+RELEASE,'handoff-core.js?v='+RELEASE,'handoff.js?v='+RELEASE,'handoff.css?v='+RELEASE,'platform-core.js?v='+RELEASE,'share-inbox.js?v='+RELEASE,'platform.js?v='+RELEASE,'platform.css?v='+RELEASE,'manifest.webmanifest','icon-192.png','icon-512.png'];
const KEYS=new Set(ASSETS.map(x=>new URL(x,BASE).href));
self.addEventListener('install',e=>e.waitUntil((async()=>{const c=await caches.open(CACHE);await c.addAll(ASSETS.map(x=>new Request(new URL(x,BASE),{cache:'reload'})));})()));
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('message',e=>{if(e.data?.type==='ACTIVATE_REVIEWED_UPDATE')e.waitUntil(self.skipWaiting());if(e.data?.type==='VERSION')e.ports[0]?.postMessage({version:RELEASE});});
async function boundedForm(request){
 if(!/^(multipart\/form-data|application\/x-www-form-urlencoded)\b/i.test(request.headers.get('Content-Type')||''))throw new Error('Unsupported share encoding. Use text or a supported file.');
 const max=PlatformCore.MAX_TOTAL+100000,reader=request.body?.getReader();if(!reader)throw new Error('Shared data is empty.');
 const chunks=[];let length=0;try{while(true){const {done,value}=await reader.read();if(done)break;length+=value.length;if(length>max){await reader.cancel();throw new Error('Shared content exceeds 4 MB.');}chunks.push(value);}}finally{reader.releaseLock();}
 const body=new Uint8Array(length);let offset=0;for(const chunk of chunks){body.set(chunk,offset);offset+=chunk.length;}
 return new Response(body,{headers:{'Content-Type':request.headers.get('Content-Type')}}).formData();
}
async function receiveShare(request){
 try{
  const origin=request.headers.get('Origin');if(origin&&origin!=='null'&&origin!==BASE.origin)throw new Error('Cross-site submissions are not accepted. Use the installed app share target.');
  const form=await boundedForm(request),files=form.getAll('files');if(files.length>PlatformCore.MAX_FILES)throw new Error('Share at most 10 text files.');
  const decoded=[];for(const f of files){if(!(f instanceof File)||!(/\.(txt|md)$/i.test(f.name))||f.size>PlatformCore.MAX_TEXT)throw new Error('Only UTF-8 .txt and .md files up to 1 MB are supported.');decoded.push({name:f.name,text:new TextDecoder('utf-8',{fatal:true}).decode(await f.arrayBuffer())});}
  const packet=PlatformCore.prepare({title:form.get('title')||'',text:form.get('text')||'',url:form.get('url')||'',files:decoded},crypto.randomUUID(),new Date().toISOString());
  await ShareInbox.add(packet);
  const clients=await self.clients.matchAll({type:'window',includeUncontrolled:true});for(const c of clients)if(c.url.startsWith(BASE.href))c.postMessage({type:'INCOMING_SHARE_SAVED',id:packet.id});
  return Response.redirect(new URL('./#shared',BASE).href,303);
 }catch(e){
  const escape=x=>String(x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  return new Response('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Share not saved</title><h1>Share not saved</h1><p>'+escape(e.message)+'</p><p>Nothing was added to your workspace. Keep the original and try a smaller text selection.</p><a href="'+escape(new URL('./#incoming',BASE).href)+'">Open Continuity inbox</a>',{status:400,headers:{'Content-Type':'text/html;charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'none'; base-uri 'none'; form-action 'none'"}});
 }
}
self.addEventListener('fetch',e=>{
 const url=new URL(e.request.url);if(url.origin!==BASE.origin||!url.pathname.startsWith(BASE.pathname))return;
 if(url.pathname===new URL('share-in',BASE).pathname&&e.request.method==='POST'){e.respondWith(receiveShare(e.request));return;}
 if(e.request.method!=='GET')return;
 if(e.request.mode==='navigate'||url.pathname===BASE.pathname||url.pathname===new URL('index.html',BASE).pathname){e.respondWith((async()=>{const c=await caches.open(CACHE);return await c.match(new URL('index.html',BASE).href)||fetch(e.request);})());return;}
 if(KEYS.has(url.href))e.respondWith((async()=>{const c=await caches.open(CACHE);return await c.match(e.request)||fetch(e.request);})());
});
