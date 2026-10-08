/* One transactional workspace document; optimistic revision check prevents stale-tab overwrites. */
(function(root){
'use strict';
const NAME='artificial-continuity-workspace',LEGACY='continuity-live-lab/0.9';
function openDB(factory=root.indexedDB){return new Promise((resolve,reject)=>{
 if(!factory){reject(new Error('IndexedDB is unavailable. No data has been changed.'));return;}
 let settled=false;const req=factory.open(NAME,1);
 req.onupgradeneeded=()=>{for(const s of ['workspace','recovery','drafts'])if(!req.result.objectStoreNames.contains(s))req.result.createObjectStore(s);};
 req.onblocked=()=>{settled=true;reject(new Error('Close older Continuity tabs, then reload to open storage.'));};
 req.onerror=()=>reject(req.error);
 req.onsuccess=()=>{if(settled){req.result.close();return;}const db=req.result;db.onversionchange=()=>db.close();resolve(db);};
});}
class Store{
 constructor(db){this.db=db;}
 read(store,key){return new Promise((resolve,reject)=>{const tx=this.db.transaction(store,'readonly');let value;const q=tx.objectStore(store).get(key);q.onsuccess=()=>value=q.result;tx.oncomplete=()=>resolve(value);tx.onabort=()=>reject(tx.error||new Error('Read aborted.'));tx.onerror=()=>{};});}
 initialize(state,legacyRaw=null){return new Promise((resolve,reject)=>{const tx=this.db.transaction(['workspace','recovery'],'readwrite');let result;const os=tx.objectStore('workspace');const q=os.get('current');q.onsuccess=()=>{result=q.result;if(!result){result={revision:1,state};os.put(result,'current');if(legacyRaw!==null)tx.objectStore('recovery').put(legacyRaw,'legacy');}};tx.oncomplete=()=>resolve(result);tx.onabort=()=>reject(tx.error||new Error('Initialization aborted.'));tx.onerror=()=>{};});}
 commit(expected,state,recovery=false){return new Promise((resolve,reject)=>{
 const tx=this.db.transaction(['workspace','recovery'],'readwrite');const os=tx.objectStore('workspace');let result,error;const q=os.get('current');
 q.onsuccess=()=>{const current=q.result;if(!current||current.revision!==expected){error=new Error('Another tab saved newer work. Reload before applying this change; your form draft is retained.');error.name='ConflictError';tx.abort();return;}result={revision:expected+1,state};if(recovery)tx.objectStore('recovery').put(current,'before-import');os.put(result,'current');};
 tx.oncomplete=()=>resolve(result);tx.onabort=()=>reject(error||tx.error||new Error('Save aborted.'));tx.onerror=()=>{};
 });}
 draft(key,value){return new Promise((resolve,reject)=>{const tx=this.db.transaction('drafts','readwrite');const os=tx.objectStore('drafts');value===null?os.delete(key):os.put(value,key);tx.oncomplete=()=>resolve();tx.onabort=()=>reject(tx.error||new Error('Draft save aborted.'));tx.onerror=()=>{};});}
 commitSyncCheckpoint(expected,cipher){return new Promise((resolve,reject)=>{const tx=this.db.transaction('recovery','readwrite'),os=tx.objectStore('recovery');let error;const q=os.get('sync-sealed-checkpoint');q.onsuccess=()=>{if((q.result||null)!==expected){error=new Error('Another tab saved newer synchronization evidence. Unlock the saved checkpoint before replacing it.');tx.abort();return;}if(typeof cipher!=='string'||cipher.length>24000000){error=new Error('Invalid encrypted checkpoint.');tx.abort();return;}os.put(cipher,'sync-sealed-checkpoint');};tx.oncomplete=()=>resolve();tx.onabort=()=>reject(error||tx.error||new Error('Checkpoint was not saved.'));tx.onerror=()=>{};});}
 close(){this.db.close();}
}
root.WorkspaceStorage={NAME,LEGACY,openDB,Store};if(typeof module!=='undefined')module.exports=root.WorkspaceStorage;
})(globalThis);
