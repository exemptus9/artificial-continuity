/* Startup barrier: all classic feature scripts must register before storage opens.
   Without this, a fast IndexedDB read can render an unknown deep link as Now
   while a later script is still downloading. No stored data is changed here. */
(function(){
'use strict';
const ready=document.readyState==='loading'
 ?new Promise(resolve=>document.addEventListener('DOMContentLoaded',resolve,{once:true}))
 :Promise.resolve();
const open=WorkspaceStorage.openDB;
WorkspaceStorage.openDB=async function(...args){await ready;return open(...args);};
})();
