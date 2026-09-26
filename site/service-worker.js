/* Cache only app-shell files. No workspace text is placed in the cache. */
'use strict';
const RELEASE='0.12.0',BASE=new URL('./',self.location.href),CACHE='continuity-shell-'+RELEASE;
const ASSETS=['index.html','workspace.css?v=0.11.0','workspace-core.js?v=0.11.0','workspace-store.js?v=0.11.0','workspace.js?v=0.11.0','operations-core.js?v='+RELEASE,'operations.js?v='+RELEASE,'offline.js?v='+RELEASE,'operations.css?v='+RELEASE,'manifest.webmanifest','icon-192.png','icon-512.png'];
self.addEventListener('install',e=>e.waitUntil((async()=>{const c=await caches.open(CACHE);await c.addAll(ASSETS.map(x=>new Request(new URL(x,BASE),{cache:'reload'})));})()));
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
// Do not automatically skip waiting or reload pages while someone is writing.
self.addEventListener('message',e=>{if(e.data?.type==='ACTIVATE_REVIEWED_UPDATE')e.waitUntil(self.skipWaiting());if(e.data?.type==='VERSION')e.ports[0]?.postMessage({version:RELEASE});});
self.addEventListener('fetch',e=>{
 const url=new URL(e.request.url);if(url.origin!==BASE.origin||!url.pathname.startsWith(BASE.pathname))return;
 if(e.request.method!=='GET')return;
 if(e.request.mode==='navigate'){e.respondWith((async()=>{const c=await caches.open(CACHE);return await c.match(new URL('index.html',BASE).href)||fetch(e.request);})());return;}
 const keys=new Set(ASSETS.map(x=>new URL(x,BASE).href));
 if(keys.has(url.href))e.respondWith((async()=>{const c=await caches.open(CACHE);return await c.match(e.request)||fetch(e.request);})());
});
