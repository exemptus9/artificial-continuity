/* Opt-in rescue UI. Loaded after workspace/platform modules. */
(function(){
'use strict';
const R=FamilyRecoveryCore,previousRender=render,previousDevice=device,previousBackup=backup;
let generation=0;
async function recoveryScreen(){
 const ticket=++generation;let candidate=null,sealed=null,importGeneration=0;
 title('Recovery files','Keep saved sources, unfinished drafts and pending incoming text together.',`<section class="card"><h2>Create a rescue copy</h2><p>Includes the saved workspace, all saved form drafts in this browser profile, and pending incoming shares. This tab’s draft is flushed first. Other tabs may still have unsaved typing.</p><p class="callout warning">The workspace and drafts are read in one transaction. The incoming inbox is read separately: this is not a globally atomic snapshot. Local browser data remains unencrypted.</p><div class="toolbar"><button id="recoveryPlain">Download readable rescue JSON</button></div>${field('recoveryPass','File passphrase (12+ characters)','',false,1024)}${field('recoveryPassAgain','Repeat file passphrase','',false,1024)}<button id="recoveryEncrypted" class="primary">Download encrypted rescue file</button><p class="meta">Passphrases are never drafted. Encryption protects this downloaded file only. A lost passphrase cannot be reset. Download completion must be checked in your device’s Files/Downloads app.</p><p id="recoveryExportStatus" role="status"></p></section><section class="card"><h2>Open a rescue file</h2><label for="recoveryFile">Recovery JSON or encrypted file · 16 MB plain / 24 MB sealed maximum</label><input id="recoveryFile" type="file" accept=".json,application/json"><div id="recoveryUnlockArea" hidden>${field('recoveryUnlock','Passphrase to open file','',false,1024)}<button id="recoveryDecrypt">Decrypt for preview</button></div><p id="recoveryImportStatus" role="status"></p><div id="recoveryPreview"></div></section><section class="card"><h2>What recovery changes</h2><p>Import preview writes nothing. Restore adds separate source drafts pending review; it does not replace the workspace, enqueue incoming shares, link projects, or accept checkpoints. Original field values, including any old review flags, remain inert JSON evidence. Review and save each recovered draft in Device & drafts.</p><p>Repeated import of the same file skips identical rescue artifacts. A changed rescue key aborts the restore. Keep the original file until you verify the recovered text. This file has integrity checks when encrypted, but neither format proves who authored its contents.</p><p>Saved workspace history is extracted as a separate ContinuityBackup/2 file. Its use in Backup & history is an explicit, potentially destructive workspace replacement; preview and export your current work there first.</p></section>`);
 for(const id of ['recoveryPass','recoveryPassAgain','recoveryUnlock']){$('#'+id).type='password';$('#'+id).autocomplete='off';}
 const active=()=>route==='recovery'&&ticket===generation;
 const setMessage=(id,message)=>{if(active()&&$('#'+id))$('#'+id).textContent=message;};
 async function exportRescue(encrypted){
  const pass=$('#recoveryPass').value,repeat=$('#recoveryPassAgain').value;$('#recoveryPass').value='';$('#recoveryPassAgain').value='';
  if(encrypted&&pass!==repeat){setMessage('recoveryExportStatus','Passphrases do not match. Nothing exported.');return;}
  if(!encrypted&&!confirm('This readable file contains original sources, unfinished drafts and pending shared text. Download without encryption?'))return;
  const buttons=[$('#recoveryPlain'),$('#recoveryEncrypted')];buttons.forEach(b=>b.disabled=true);
  try{
   if(busy)throw new Error('Finish the current save before exporting.');
   if(!await flushDraft())throw new Error('This tab has unsaved typing. Copy it before leaving; rescue export was stopped.');
   const packet=await R.collect(store.db,()=>ShareInbox.list(),revision,{exportedAt:now(),origin:location.origin,appVersion:globalThis.CaptureFamilyCore?.APP_VERSION||'0.18.0'});
   const raw=JSON.stringify(packet),payload=encrypted?await SealedBackup.seal(raw,pass):raw;
   if(!active())return;
   download(payload,'continuity-rescue-'+packet.exportedAt.slice(0,10)+(encrypted?'-encrypted':'-READABLE')+'.json');
   setMessage('recoveryExportStatus',`Download requested: ${packet.drafts.length} saved drafts, ${packet.incoming.length} pending shares, workspace revision ${packet.workspaceRevision}. Check Downloads before clearing any storage.`);
  }catch(e){setMessage('recoveryExportStatus','Not exported: '+e.message);}
  finally{buttons.forEach(b=>{if(b.isConnected)b.disabled=false;});}
 }
 $('#recoveryPlain').onclick=()=>exportRescue(false);$('#recoveryEncrypted').onclick=()=>exportRescue(true);
 async function preview(raw,run){
  const checked=R.parse(raw),prepared=await R.plan(checked);if(!active()||run!==importGeneration)return;candidate=checked;
  setMessage('recoveryImportStatus',`Validated preview: ${checked.drafts.length} unfinished drafts, ${checked.incoming.length} pending shares, ${checked.workspace.state.sources.length} saved sources. Nothing written.`);
  $('#recoveryUnlockArea').hidden=true;
  $('#recoveryPreview').innerHTML=`<p class="meta">From ${esc(checked.origin)} · release ${esc(checked.appVersion)} · ${esc(checked.exportedAt)} · workspace revision ${checked.workspaceRevision}. Origin/version labels are file claims, not verified identity.</p><p>${prepared.rows.length} separate source draft(s) can be rescued. Consent and project links require fresh review.</p><div class="toolbar"><button id="recoveryRestore" class="primary" ${prepared.rows.length?'':'disabled'}>Add separate rescue drafts</button><button id="recoveryWorkspace">Extract saved workspace backup</button><button id="recoveryEvidence">Download original draft/inbox JSON</button></div><p id="recoveryRestoreStatus" role="status"></p><details><summary>Inspect first draft / incoming text</summary><pre id="recoveryText"></pre></details>`;
  $('#recoveryText').textContent=prepared.rows[0]?.value.sourceText.slice(0,4000)||'No unfinished text in this file.';
  $('#recoveryRestore').onclick=async()=>{const button=$('#recoveryRestore');button.disabled=true;try{if(!candidate)return;if(busy)throw new Error('Finish the current save first.');const result=await R.restore(store.db,candidate);setMessage('recoveryRestoreStatus',`Added ${result.added} separate draft(s); ${result.skipped} identical rescue draft(s) already present. Open Device & drafts to review. Saved workspace and incoming inbox unchanged.`);}catch(e){setMessage('recoveryRestoreStatus','Not restored: '+e.message);}finally{if(button.isConnected)button.disabled=false;}};
  $('#recoveryWorkspace').onclick=()=>{if(candidate)download(JSON.stringify(candidate.workspace),'continuity-rescue-workspace-READABLE.json');};
  $('#recoveryEvidence').onclick=()=>{if(candidate)download(JSON.stringify({format:'ContinuityRecoveryEvidence/1',exportedAt:candidate.exportedAt,origin:candidate.origin,drafts:candidate.drafts,incoming:candidate.incoming},null,2),'continuity-rescue-original-drafts-READABLE.json');};
 }
 $('#recoveryFile').onchange=async e=>{
  const run=++importGeneration;candidate=null;sealed=null;$('#recoveryPreview').textContent='';$('#recoveryUnlockArea').hidden=true;
  try{const f=e.target.files[0];if(!f)return;if(f.size>SealedBackup.MAX_FILE)throw new Error('File exceeds 24 MB.');const raw=await f.text();if(!active()||run!==importGeneration)return;const value=JSON.parse(raw);
   if(value?.format===SealedBackup.FORMAT){SealedBackup.inspect(raw);sealed=raw;$('#recoveryUnlockArea').hidden=false;setMessage('recoveryImportStatus','Encrypted file selected. Decrypt locally to preview. Nothing saved.');}
   else await preview(raw,run);
  }catch(e){if(run===importGeneration)setMessage('recoveryImportStatus','Rejected: '+e.message);}
 };
 $('#recoveryDecrypt').onclick=async()=>{const button=$('#recoveryDecrypt'),pass=$('#recoveryUnlock').value,run=importGeneration;$('#recoveryUnlock').value='';button.disabled=true;try{await preview(await SealedBackup.open(sealed,pass),run);}catch(e){if(run===importGeneration)setMessage('recoveryImportStatus','Not opened: '+e.message);}finally{if(button.isConnected)button.disabled=false;}};
}
device=async function(){await previousDevice();if(route!=='device')return;const target=[...document.querySelectorAll('#main .meta')].find(e=>e.textContent.startsWith('Drafts are not part'));if(target)target.textContent='Ordinary workspace backups exclude unsaved drafts. Recovery files include saved drafts and pending incoming shares. Restore creates separate drafts for review.';};
backup=async function(){await previousBackup();if(route==='backup')$('#main').insertAdjacentHTML('afterbegin','<section class="card"><h2>Need unfinished work too?</h2><p>Standard workspace backups omit saved form drafts and pending incoming shares. Recovery files include these alongside the workspace.</p><button data-nav="recovery">Open Recovery files</button></section>');};
render=async function(){if(!S)return;if(route==='recovery')return recoveryScreen();generation++;return previousRender();};
const nav=document.createElement('button');nav.dataset.nav='recovery';nav.textContent='Recovery files';document.querySelector('nav [data-nav="backup"]')?.after(nav);
})();
