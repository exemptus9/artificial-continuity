/* Unreviewed OS shares stay in a bounded, local inbox. They never enter the workspace automatically. */
(function(root){
'use strict';
const NAME='continuity-incoming-shares';
function open(){return new Promise((resolve,reject)=>{const r=root.indexedDB.open(NAME,1);r.onupgradeneeded=()=>r.result.createObjectStore('items',{keyPath:'id'});r.onerror=()=>reject(r.error);r.onblocked=()=>reject(new Error('Incoming inbox blocked. Close older app windows.'));r.onsuccess=()=>{r.result.onversionchange=()=>r.result.close();resolve(r.result);};});}
async function run(mode,operate){const db=await open();try{return await new Promise((resolve,reject)=>{const tx=db.transaction('items',mode);let value,error;operate(tx.objectStore('items'),v=>{value=v;},e=>{error=e;tx.abort();});tx.oncomplete=()=>resolve(value);tx.onabort=()=>reject(error||tx.error||new Error('Incoming inbox transaction failed.'));tx.onerror=()=>{};});}finally{db.close();}}
async function add(item){root.PlatformCore.validate(item);return run('readwrite',(os,set,abort)=>{const n=os.count();n.onsuccess=()=>{if(n.result>=root.PlatformCore.MAX_QUEUE){abort(new Error('Incoming inbox is full (25). Save/export and remove older items before sharing more.'));return;}os.add(item);set(item.id);};});}
async function list(){return run('readonly',(os,set)=>{const q=os.getAll();q.onsuccess=()=>set(q.result.sort((a,b)=>b.at.localeCompare(a.at)));});}
async function get(id){return run('readonly',(os,set)=>{const q=os.get(id);q.onsuccess=()=>set(q.result);});}
async function markSaved(id){return run('readwrite',(os,set)=>{const q=os.get(id);q.onsuccess=()=>{if(q.result){os.put({...q.result,status:'SAVED'});set(true);}};});}
async function remove(id){return run('readwrite',(os,set)=>{os.delete(id);set(true);});}
root.ShareInbox={NAME,add,list,get,markSaved,remove};if(typeof module!=='undefined')module.exports=root.ShareInbox;
})(globalThis);
