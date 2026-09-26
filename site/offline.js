/* Optional app-shell cache and explicit update controls. No workspace data uploads. */
'use strict';
let appRegistration=null,installPrompt=null,offlineProblem='',reloadAfterUpdate=false;
function refreshDeviceStatus(){const e=document.querySelector('#offlineState');if(e)e.textContent=offlineProblem||(!('serviceWorker' in navigator)?'Offline installation unavailable in this browser.':navigator.serviceWorker.controller?'Offline shell active · '+(navigator.onLine?'network available':'currently offline')+' · backups still required.':'First offline cache is being prepared. Keep this page open until active.');}
function showUpdate(){const b=document.querySelector('#applyUpdate');if(b)b.hidden=!appRegistration?.waiting;}
async function prepareOffline(){
 if(!('serviceWorker' in navigator)||!window.isSecureContext){offlineProblem='Offline installation needs a supported browser and HTTPS (or localhost).';refreshDeviceStatus();return;}
 try{appRegistration=await navigator.serviceWorker.register('./service-worker.js',{scope:'./',updateViaCache:'none'});showUpdate();appRegistration.addEventListener('updatefound',()=>{const worker=appRegistration.installing;worker?.addEventListener('statechange',()=>{showUpdate();refreshDeviceStatus();});});await navigator.serviceWorker.ready;refreshDeviceStatus();}
 catch(e){offlineProblem='Offline cache unavailable: '+e.message+'. The online workspace still works.';refreshDeviceStatus();}
}
window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();installPrompt=e;});
if('serviceWorker' in navigator)navigator.serviceWorker.addEventListener('controllerchange',()=>{refreshDeviceStatus();if(reloadAfterUpdate)location.reload();});
window.addEventListener('online',refreshDeviceStatus);window.addEventListener('offline',refreshDeviceStatus);
document.addEventListener('click',async e=>{const b=e.target.closest('[data-action]');if(!b||!['install-app','check-update','persistent-storage','apply-update'].includes(b.dataset.action))return;e.preventDefault();e.stopImmediatePropagation();const say=msg=>{const x=$('#deviceMessage');if(x)x.textContent=msg;notify(msg);};
 try{switch(b.dataset.action){
  case 'install-app':if(installPrompt){await installPrompt.prompt();const choice=await installPrompt.userChoice;installPrompt=null;say(choice.outcome==='accepted'?'Install request accepted by the browser.':'Installation was not accepted.');}else say('Use your browser menu: Install app / Add to Home screen. Available wording and support vary.');break;
  case 'check-update':if(!appRegistration){await prepareOffline();}if(appRegistration){await appRegistration.update();showUpdate();say(appRegistration.waiting?'An update is ready. Use Apply update after saving.':'Update check requested. Any available update will wait for your approval.');}break;
  case 'persistent-storage':say(navigator.storage?.persist?(await navigator.storage.persist()?'Persistent storage granted. This is not encryption or a backup.':'Browser did not grant persistent storage. Export backups regularly.'):'Storage persistence requests are unavailable.');break;
  case 'apply-update':if(busy){say('Finish saving before updating.');break;}if(!await flushDraft())break;if(!confirm('Your draft is saved. Reload this tab to apply the prepared app update? Other tabs will not be forced to reload.'))break;reloadAfterUpdate=true;if(appRegistration?.waiting)appRegistration.waiting.postMessage({type:'ACTIVATE_REVIEWED_UPDATE'});else location.reload();break;
 }}catch(err){say('Not completed: '+err.message);}},true);
prepareOffline();
