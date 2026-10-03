/* Total Recall text intake + bounded Intent Integrity guard, inside Continuity. */
'use strict';
const CF=CaptureFamilyCore;
let captureFamilyEpoch=0;
function guardCaptureSave(button,onCancel){
 let down=null,allowed=null;
 const point=(e,target)=>({target,pointerId:e.pointerId,rect:target?.getBoundingClientRect()});
 button.addEventListener('pointerdown',e=>{down=point(e,button);allowed=null;});
 const release=e=>{if(!down)return;allowed=CF.stableAction(down,point(e,e.target.closest?.('#saveFamilyCapture')));down=null;if(!allowed)onCancel();};
 document.addEventListener('pointerup',release,true);
 const cancel=()=>{if(down){down=null;allowed=false;onCancel();}};
 button.addEventListener('pointercancel',cancel);
 button.addEventListener('click',e=>{if(e.detail===0){allowed=null;return;}if(allowed!==true){e.preventDefault();e.stopImmediatePropagation();onCancel();}allowed=null;},true);
 // This listener is bounded to the lifetime of this capture form.
 const observer=new MutationObserver(()=>{if(!button.isConnected){document.removeEventListener('pointerup',release,true);observer.disconnect();}});
 observer.observe(document.querySelector('main'),{childList:true,subtree:true});
}
capture=async function(){
 const epoch=++captureFamilyEpoch;
 title('Quick capture','Keep the original. Find it again. Decide what it means later.',`<div class="grid capture-family"><section class="card"><form id="captureForm"><p class="meta">As you type, an unencrypted recovery draft is saved in this browser. The checkbox below approves adding the reviewed text to your workspace.</p>${select('type','Type',[['note','Note'],['friction','UX friction'],['idea','Idea'],['observation','Observation'],['question','Question']],'note')}${select('captureMethod','Input method',[['text','Typed or pasted text'],['manual-transcript','Transcript supplied by me'],['intent-observation','Interaction I observed']],'text')}${field('context','App / context','',false,200)}${field('text','Original text','',true)}${select('captureSpeaker','Attribution I can support',[['unknown','Unknown / not established'],['user','My own words'],['assistant','AI / assistant words'],['mixed','Mixed conversation']],'unknown')}<details><summary>Observation time (optional)</summary>${field('captureObservedAt','When it happened, including timezone','',false,100)}<p class="meta">Example: 2026-10-03T14:30:00-04:00. This is your supplied timestamp, not independently verified.</p></details><input type="hidden" id="captureToken" name="captureToken"><input type="hidden" id="captureRawText" name="captureRawText"><input type="hidden" id="captureInputBasis" name="captureInputBasis" value="browser-editor-text"><p class="draft-status"></p><p id="captureTextStatus" class="meta"></p><label class="check"><input type="checkbox" id="captureConsent"><span>I reviewed this text and want its original source and linked capture saved in this browser.</span></label><p role="alert" class="error"></p><button id="saveFamilyCapture" class="primary" type="submit">Save reviewed capture</button><p class="meta">No microphone access or background recording. Transcript means text you supply. The Save target check operates only on this button; it cannot intercept Android or other apps.</p></form></section><section class="card"><h2>Capture inbox</h2><p class="meta">Captures are unreviewed evidence. Saving never accepts a decision or updates an intention.</p><div class="toolbar">${btn('search','Find saved words')}</div>${[...S.captures].reverse().map(c=>`<article class="item"><div class="row"><b>${esc(c.context||c.type)}</b>${tag(c.status)}</div><pre class="capture-original">${esc(c.text)}</pre><p class="meta">${esc(c.at)}${c.sourceId?' · source version '+esc(c.sourceVersion):' · legacy capture, no source link'}</p><div class="toolbar">${c.sourceId?btn('source','Read original & review',c.sourceId):''}${c.status==='UNPROCESSED'?btn('develop','Develop into idea',c.id)+btn('archive','Archive',c.id):''}</div></article>`).join('')||'<p class="muted">Your captured text will appear here.</p>'}</section></div>`);
 const form=$('#captureForm'),editor=$('#text'),raw=$('#captureRawText'),token=$('#captureToken'),basis=$('#captureInputBasis'),consent=$('#captureConsent');editor.required=true;
 // Run before the standard draft listener so it saves the exact preserved string.
 form.addEventListener('input',e=>{
  if(e.target!==consent){consent.checked=false;if(e.target===editor){const next=CF.reconcileText(raw.value,editor.value);if(next!==raw.value&&basis.value==='clipboard-text/plain')basis.value='edited-clipboard-text';raw.value=next;}}
  $('#captureTextStatus').textContent='Source will retain '+raw.value.length+' characters from this app input. Earlier apps or keyboard dictation may already have altered the text.';
 });
 editor.addEventListener('paste',e=>{
  const data=e.clipboardData;if(!data||!Array.from(data.types).includes('text/plain'))return;
  const inserted=data.getData('text/plain'),start=editor.selectionStart,end=editor.selectionEnd,current=CF.reconcileText(raw.value,editor.value),next=CF.spliceText(current,start,end,inserted);
  if(next.length>20000){e.preventDefault();formError(form,new Error('Paste would exceed 20,000 characters. Nothing was pasted.'));return;}
  e.preventDefault();raw.value=next;editor.value=CF.editorText(next);basis.value=CF.editorText(current).length===end-start?'clipboard-text/plain':'edited-clipboard-text';const pos=start+CF.editorText(inserted).length;editor.setSelectionRange(pos,pos);editor.dispatchEvent(new Event('input',{bubbles:true}));
 });
 await draft(form,'capture');if(epoch!==captureFamilyEpoch||route!=='capture'||!form.isConnected)return;
 consent.checked=false;
 if(!token.value)token.value=uid();
 raw.value=CF.reconcileText(raw.value,editor.value);
 if(!basis.value)basis.value='browser-editor-text';
 const saved=S.captures.find(c=>c.captureToken===token.value);
 if(saved)form.querySelector('[role=alert]').textContent='This draft already has a saved capture. Retry finishes draft cleanup without duplicating it; edit to start a new capture.';
 form.addEventListener('input',e=>{
  if(e.target===consent)return;
  const previous=S.captures.find(c=>c.captureToken===token.value);
  if(previous){token.value=uid();form.querySelector('[role=alert]').textContent='Editing starts a new capture. The previously saved original remains.';form.dispatchEvent(new Event('input',{bubbles:true}));}
 });
 guardCaptureSave($('#saveFamilyCapture'),()=>formError(form,new Error('Save target moved or changed during the pointer press. Nothing was saved. Review and tap again; no action is replayed.')));
 let submitting=false;
 form.onsubmit=async e=>{
  e.preventDefault();if(busy||submitting)return;
  let locked=null;
  try{
   const plan=CF.prepare({text:CF.reconcileText(raw.value,editor.value),type:$('#type').value,context:$('#context').value,method:$('#captureMethod').value,speaker:$('#captureSpeaker').value,observedAt:$('#captureObservedAt').value,inputBasis:basis.value},token.value,now());
   const reviewedConsent=consent.checked;
   if(!reviewedConsent)throw new Error('Review the text and check the local-saving consent box first.');
   raw.value=plan.fields.text;
   const draftValue=Object.fromEntries(new FormData(form));
   submitting=true;locked=[...form.elements].map(control=>({control,disabled:control.disabled}));locked.forEach(({control})=>control.disabled=true);form.setAttribute('aria-busy','true');
   // Persist the idempotency token before committing the workspace. If draft
   // deletion later fails, reloading this draft cannot duplicate the capture.
   draftJob={key:'capture',value:draftValue};draftDirty=true;
   if(!await flushDraft())throw new Error('Recovery draft could not be saved. Copy the original text before leaving, then retry.');
   if(!form.isConnected||route!=='capture'||epoch!==captureFamilyEpoch)throw new Error('Capture view changed. Your draft is retained; return and review it before saving.');
   const preview=CF.apply(S,plan,reviewedConsent);
   const committed=preview.alreadySaved||await mutate('CAPTURE_SOURCE_SAVED','Saved original text and an unreviewed linked capture.',s=>Object.assign(s,CF.apply(s,plan,reviewedConsent).state));
   if(!committed)return;
   // Delete only this persisted capture draft. A user may have navigated during
   // the transaction; never flush or clear a different form's recovery draft.
   try{await store.draft(draftKey('capture'),null);}catch(err){consent.checked=false;formError(form,new Error('Capture IS SAVED, but draft cleanup failed: '+err.message+'. Retry cleanup after reviewing again; the saved source will not duplicate.'));return;}
   consent.checked=false;if(form.isConnected&&route==='capture'&&epoch===captureFamilyEpoch)await navigate('sources',preview.sourceId);notify(preview.alreadySaved?'Existing saved capture recovered; duplicate avoided.':'Original text saved. Search can retrieve it; no decision was accepted.');
  }catch(err){if(form.isConnected)formError(form,err);else notify(err.message);}
  finally{if(locked){locked.forEach(({control,disabled})=>control.disabled=disabled);form.setAttribute('aria-busy','false');consent.checked=false;}submitting=false;}
  };
};
// Search already indexes sources and captures. Open a linked original directly.
document.addEventListener('click',async e=>{
 const b=e.target.closest('[data-action="result"][data-kind="capture"]');if(!b)return;const c=S?.captures.find(x=>x.id===b.dataset.id);if(!c?.sourceId)return;
 e.preventDefault();e.stopImmediatePropagation();if(!busy)await navigate('sources',c.sourceId);
},true);
const sourceBeforeCaptureFamily=sourceView;
sourceView=function(id){
 sourceBeforeCaptureFamily(id);const src=S.sources.find(x=>x.id===id),p=src?.captureOrigin;if(!p)return;
 const block=document.createElement('p');block.className='meta capture-provenance';block.textContent=`Capture source v${p.sourceVersion} · ${p.method} · attribution: ${src.speaker} · saved ${p.receivedAt}${p.observedAt?' · observation time supplied: '+p.observedAt:''}. Original retained; decisions require separate review.`;$('#main .toolbar')?.after(block);
};
