'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const Sync = require('../site/sync-engine.js');

test('read-only recheck detects drift without rewriting historical verification or canonical task',async()=>{const x=await setup();await x.engine.run(x.prepared.id);const before=await x.store.load();x.fixture.set({...x.fixture.get(),summary:'Later provider change'});const check=await x.engine.recheck(x.prepared.id),after=await x.store.load();assert.equal(check.status,'CONFLICT');assert.equal(after.outbox[x.prepared.id].status,'VERIFIED');assert.equal(x.fixture.writes(),1);assert.deepEqual(after.entities[ENTITY].lifecycle,before.entities[ENTITY].lifecycle);assert.equal(after.conflicts.at(-1).type,'PROVIDER_DRIFT');assert.equal((await Sync.verifyReceipt(await x.engine.receipt())).valid,true);});

test('expired access on recheck is waiting, not deletion or a second write',async()=>{const x=await setup();await x.engine.run(x.prepared.id);x.fixture.adapter.read=async()=>{throw codeError('AUTH_EXPIRED');};const check=await x.engine.recheck(x.prepared.id);assert.equal(check.status,'WAITING');assert.equal(check.errorCode,'AUTH_EXPIRED');assert.equal(x.fixture.writes(),1);assert.ok((await x.store.load()).entities[ENTITY]);});

const TIME = '2026-10-08T06:00:00.000Z';
const PROVIDER = 'synthetic-table';
const EXTERNAL = 'synthetic-row-001';
const ENTITY = 'ent_synthetic_001';
const copy = value => JSON.parse(JSON.stringify(value));
const codeError = code => Object.assign(new Error(code), { code });
const rejectsCode = (fn, code) => assert.rejects(fn, error => error.code === code);
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; };
function claim(overrides = {}) {
  const base = { entityId: ENTITY, kind: 'task', privacy: 'PUBLIC',
    source: { provider: PROVIDER, externalId: EXTERNAL, revision: 'synthetic-rev-1', revisionOrdinal: 1,
      ref: 'fixture://synthetic/task/001', observedAt: TIME },
    data: { title: 'Synthetic application follow-up', status: 'DRAFT', summary: 'Fictional receipt observed', internalText: 'Synthetic source text excluded from public projection' },
    lifecycle: { task: 'DRAFT' } };
  return { ...base, ...overrides, source: { ...base.source, ...(overrides.source || {}) }, data: overrides.data || base.data };
}
function adapterFixture(initial, hooks = {}) {
  let projection = copy(initial), revision = 1;
  const calls = [];
  const adapter = {
    capabilities: { updateExisting: true },
    async read(externalId) {
      calls.push({ action: 'read', externalId });
      if (hooks.read) return hooks.read({ externalId, projection: copy(projection), revision, calls });
      return { externalId, revision: `synthetic-rev-${revision}`, sourceRef: 'fixture://synthetic/provider/001', projection: copy(projection) };
    },
    async update(externalId, patch, options) {
      calls.push({ action: 'update', externalId, patch: copy(patch), options: copy(options) });
      if (await Sync.sha256Hex(projection) !== options.expectedHash) throw codeError('PRECONDITION_FAILED');
      projection = { ...projection, ...copy(patch) }; revision++;
      if (hooks.update) await hooks.update({ externalId, patch, options, get: () => copy(projection), set: value => { projection=copy(value); }, calls });
      return { acknowledged: true }; // An acknowledgement alone must never establish verification.
    }
  };
  return { adapter, calls, get: () => copy(projection), set: value => { projection=copy(value); }, writes: () => calls.filter(c=>c.action==='update').length };
}
async function setup(hooks = {}, options = {}) {
  let instant = TIME;
  const store = options.store || Sync.createMemoryStore();
  const data = options.claim || claim();
  const fixture = adapterFixture(data.data, hooks);
  const engine = Sync.createSyncEngine({ store, adapters: { [PROVIDER]: fixture.adapter }, clock: () => instant });
  const observed = await engine.observe(data);
  const prepared = await engine.prepareUpdate({ entityId: observed.entityId, provider: PROVIDER, externalId: EXTERNAL,
    expectedHash: await Sync.sha256Hex(data.data), patch: options.patch || { status: 'ACKNOWLEDGED' }, sourceEvidenceId: observed.evidenceId });
  return { store, engine, fixture, observed, prepared, data, advance: ms => { instant=new Date(Date.parse(instant)+ms).toISOString(); }, clock: () => instant };
}
async function rehashReceipt(packet) {
  packet.stateHash = await Sync.sha256Hex(packet.state);
  const { contentHash, ...body } = packet;
  packet.contentHash = await Sync.sha256Hex(body);
  return packet;
}

test('a duplicate source observation creates no duplicate entity, evidence, journal entry or store write', async () => {
  const store=Sync.createMemoryStore(), engine=Sync.createSyncEngine({store,clock:()=>TIME});
  const first=await engine.observe(claim()), before=await store.load();
  const second=await engine.observe(claim()), after=await store.load();
  assert.equal(second.duplicate,true); assert.equal(second.entityId,first.entityId); assert.equal(second.evidenceId,first.evidenceId);
  assert.deepEqual(after.entities,before.entities); assert.deepEqual(after.evidence,before.evidence); assert.deepEqual(after.journal,before.journal);
  assert.equal(after.revision,before.revision,'a replay must not persist a new store revision');
});

test('the same prepared update has one identity and repeated verified execution writes the provider once', async () => {
  const x=await setup(); const before=await x.store.load();
  const repeated=await x.engine.prepareUpdate({entityId:ENTITY,provider:PROVIDER,externalId:EXTERNAL,expectedHash:await Sync.sha256Hex(x.data.data),patch:{status:'ACKNOWLEDGED'},sourceEvidenceId:x.observed.evidenceId});
  assert.equal(repeated.id,x.prepared.id); assert.equal(Object.keys((await x.store.load()).outbox).length,1);
  assert.deepEqual((await x.store.load()).journal,before.journal);
  assert.equal((await x.store.load()).revision,before.revision);
  const first=await x.engine.run(x.prepared.id), second=await x.engine.run(x.prepared.id);
  assert.equal(first.status,'VERIFIED'); assert.deepEqual(second,first); assert.equal(x.fixture.writes(),1);
  assert.equal((await x.store.load()).entities[ENTITY].lifecycle.task,'DRAFT','a verified provider write is not task completion');
});

test('repreparing a logical update after provider hash changed reuses its original operation and performs zero additional writes',async()=>{
  const x=await setup(),verified=await x.engine.run(x.prepared.id),before=await x.store.load();
  const repeated=await x.engine.prepareUpdate({entityId:ENTITY,provider:PROVIDER,externalId:EXTERNAL,expectedHash:await Sync.sha256Hex(x.fixture.get()),patch:{status:'ACKNOWLEDGED'},sourceEvidenceId:x.observed.evidenceId});
  assert.equal(repeated.id,verified.id);assert.equal(repeated.status,'VERIFIED');assert.equal(Object.keys((await x.store.load()).outbox).length,1);
  assert.equal((await x.store.load()).revision,before.revision);assert.deepEqual((await x.store.load()).journal,before.journal);
  await x.engine.run(repeated.id);assert.equal(x.fixture.writes(),1);
});

test('restart after provider apply and before acknowledgement reconciles readback without another write', async () => {
  const applied=deferred(), release=deferred();
  const x=await setup({update:async()=>{applied.resolve();await release.promise;}});
  const interrupted=x.engine.run(x.prepared.id); await applied.promise;
  const persisted=await x.store.load(); assert.equal(persisted.outbox[x.prepared.id].phase,'APPLYING');
  assert.equal(x.fixture.get().status,'ACKNOWLEDGED');
  x.advance(31000);
  const resumedStore=Sync.createMemoryStore(persisted);
  const resumed=Sync.createSyncEngine({store:resumedStore,adapters:{[PROVIDER]:x.fixture.adapter},clock:x.clock});
  const result=await resumed.recover();
  assert.equal(result[0].status,'VERIFIED'); assert.equal(result[0].verificationMethod,'RECOVERED_READBACK');
  assert.equal(x.fixture.writes(),1);
  assert.deepEqual(x.fixture.calls.map(c=>c.action),['read','update','read','read']);
  release.resolve(); await interrupted;
});

test('an applied write whose response timed out is queried before retry and is never duplicated', async () => {
  let first=true;const x=await setup({update:async()=>{if(first){first=false;throw codeError('TIMEOUT');}}});
  const uncertain=await x.engine.run(x.prepared.id); assert.equal(uncertain.status,'WAITING');
  assert.equal(uncertain.phase,'APPLYING'); assert.equal(x.fixture.writes(),1);
  const resumed=Sync.createSyncEngine({store:x.store,adapters:{[PROVIDER]:x.fixture.adapter},clock:x.clock});
  const verified=await resumed.run(x.prepared.id);assert.equal(verified.status,'VERIFIED');
  assert.equal(x.fixture.writes(),1); assert.deepEqual(x.fixture.calls.map(c=>c.action),['read','update','read','read']);
});

test('a retry after pre-apply provider failure queries the destination before attempting another write', async () => {
  const x=await setup(); let attempts=0;
  const real=x.fixture.adapter.update;
  x.fixture.adapter.update=async(...args)=>{attempts++;if(attempts===1){x.fixture.calls.push({action:'update-failed'});throw codeError('OFFLINE');}return real(...args);};
  assert.equal((await x.engine.run(x.prepared.id)).errorCode,'OFFLINE');
  const after=await x.engine.run(x.prepared.id);assert.equal(after.status,'VERIFIED');
  assert.deepEqual(x.fixture.calls.map(c=>c.action),['read','update-failed','read','update','read']);
});

test('readback that differs from the requested patch remains waiting and cannot become verified evidence', async () => {
  const x=await setup({update:async({get,set})=>set({...get(),status:'DRAFT'})});
  const result=await x.engine.run(x.prepared.id);assert.equal(result.status,'WAITING');assert.equal(result.errorCode,'READBACK_DIFFERENT');
  const state=await x.store.load();assert.equal(state.entities[ENTITY].lifecycle.task,'DRAFT');
  assert.equal(Object.values(state.evidence).filter(e=>e.verification.status==='VERIFIED').length,0);
});

test('a matching patch with another projected field changed is a conflict, not a verified write', async () => {
  const x=await setup({update:async({get,set})=>set({...get(),title:'Concurrent synthetic edit'})});
  const result=await x.engine.run(x.prepared.id);assert.equal(result.status,'CONFLICT');assert.equal(result.errorCode,'READBACK_CHANGED');
  assert.equal(x.fixture.writes(),1);assert.equal(result.verificationEvidenceId,undefined);
  assert.equal((await x.store.load()).entities[ENTITY].data.title,x.data.data.title);
});

test('changed destination before apply is fenced and causes zero writes', async () => {
  const x=await setup(); x.fixture.set({...x.fixture.get(),title:'Another operator changed this'});
  const result=await x.engine.run(x.prepared.id);assert.equal(result.status,'CONFLICT');assert.equal(result.errorCode,'DESTINATION_CHANGED');assert.equal(x.fixture.writes(),0);
});

test('already equal baseline is independently read back and requires zero provider writes', async () => {
  const x=await setup({}, {patch:{status:'DRAFT'}});
  const result=await x.engine.run(x.prepared.id);assert.equal(result.status,'VERIFIED');assert.equal(result.verificationMethod,'ALREADY_EQUAL_READBACK');
  assert.equal(x.fixture.writes(),0);assert.deepEqual(x.fixture.calls.map(c=>c.action),['read','read']);
});

for(const failure of ['AUTH_EXPIRED','OFFLINE','ACCESS_DENIED','SCHEMA_CHANGED']) {
  test(`${failure} preserves original records, pending operation and evidence`,async()=>{
    const x=await setup({read:async()=>{throw codeError(failure);}}),before=await x.store.load();
    const result=await x.engine.run(x.prepared.id),after=await x.store.load();
    assert.equal(result.status,'WAITING');assert.equal(result.errorCode,failure);assert.equal(x.fixture.writes(),0);
    assert.deepEqual(after.entities,before.entities);assert.deepEqual(after.evidence,before.evidence);assert.equal(Object.keys(after.outbox).length,1);
  });
}

test('missing destination does not silently create a new provider record',async()=>{
  const x=await setup({read:async()=>null});const result=await x.engine.run(x.prepared.id);
  assert.equal(result.status,'WAITING');assert.equal(result.errorCode,'NOT_FOUND');assert.equal(x.fixture.writes(),0);
});

test('concurrent execution from separate engines sharing a store cannot issue duplicate writes',async()=>{
  const x=await setup(),other=Sync.createSyncEngine({store:x.store,adapters:{[PROVIDER]:x.fixture.adapter},clock:x.clock});
  await Promise.all([x.engine.run(x.prepared.id),other.run(x.prepared.id)]);
  assert.equal(x.fixture.writes(),1);assert.equal((await x.store.load()).outbox[x.prepared.id].status,'VERIFIED');
});

test('private and restricted claims require encrypted storage before any durable mutation',async()=>{
  for(const privacy of ['PRIVATE','RESTRICTED']) {
    const store=Sync.createMemoryStore(),engine=Sync.createSyncEngine({store,clock:()=>TIME});
    await rejectsCode(()=>engine.observe(claim({privacy})),'ENCRYPTED_STORAGE_REQUIRED');
    assert.deepEqual(await store.load(),Sync.emptySyncState());
  }
});

test('malformed sources, unknown schema fields, unsafe keys and secret-shaped synthetic values are rejected',async()=>{
  const bad=[claim({kind:'not-an-entity-kind'}),claim({privacy:'OPEN'}),claim({source:{observedAt:'tomorrow'}}),
    {...claim(),unexpected:'silently ignored?'},claim({source:{extra:'unknown source field'}}),
    claim({data:{title:'Synthetic',apiKey:'synthetic-noncredential'}}),
    claim({data:{title:'ghp_SYNTHETIC_NOT_REAL_CREDENTIAL_000000'}}),
    claim({data:JSON.parse('{"title":"Synthetic","__proto__":{"polluted":true}}')})];
  for(const input of bad){const store=Sync.createMemoryStore(),engine=Sync.createSyncEngine({store,clock:()=>TIME});await assert.rejects(()=>engine.observe(input));assert.deepEqual(await store.load(),Sync.emptySyncState());}
  assert.throws(()=>Sync.createMemoryStore({...Sync.emptySyncState(),schemaVersion:999}),/INVALID_SYNC_STATE/);
});

test('source observations cannot assert task completion, verification or publication authority',async()=>{
  for(const lifecycle of [{task:'COMPLETE'},{verification:'VERIFIED'},{publication:'PUBLISHED'}]){
    const store=Sync.createMemoryStore(),engine=Sync.createSyncEngine({store,clock:()=>TIME});
    await rejectsCode(()=>engine.observe(claim({lifecycle})),'UNSUPPORTED_OBSERVED_TRANSITION');assert.equal((await store.load()).journal.length,0);
  }
});

test('stale divergent observations preserve canonical content and retain conflict evidence',async()=>{
  const store=Sync.createMemoryStore(),engine=Sync.createSyncEngine({store,clock:()=>TIME});
  await engine.observe(claim({source:{revision:'synthetic-rev-5',revisionOrdinal:5}}));
  const result=await engine.observe(claim({source:{revision:'synthetic-rev-2',revisionOrdinal:2},data:{title:'Old synthetic title',status:'DRAFT'}}));
  const state=await store.load();assert.equal(result.conflict,true);assert.equal(state.conflicts[0].type,'STALE_REVISION');assert.equal(state.entities[ENTITY].data.title,'Synthetic application follow-up');assert.equal(state.entities[ENTITY].evidenceIds.length,2);
});

test('stale same-content source revision cannot move the provider revision fence backward',async()=>{
  const store=Sync.createMemoryStore(),engine=Sync.createSyncEngine({store,clock:()=>TIME});
  await engine.observe(claim({source:{revision:'synthetic-rev-5',revisionOrdinal:5}}));
  await engine.observe(claim({source:{revision:'synthetic-rev-2',revisionOrdinal:2}}));
  const entry=(await store.load()).entities[ENTITY].sourceRevisions[Sync.canonicalJson([PROVIDER,EXTERNAL])];
  assert.equal(entry.ordinal,5);assert.equal(entry.revision,'synthetic-rev-5');
});

test('public export requires explicit entity grant and exposes only the approved fields and scope',async()=>{
  const x=await setup();const second=await x.engine.observe(claim({entityId:'ent_synthetic_002',source:{externalId:'synthetic-row-002'},data:{title:'Excluded entity',summary:'Must not appear'}}));
  await rejectsCode(()=>x.engine.exportContext({entityIds:[ENTITY],purpose:'public'}),'RECORD_PUBLICATION_GRANT_REQUIRED');
  await rejectsCode(()=>x.engine.grantPublication({entityId:ENTITY,fields:['internalText'],expiresAt:'2026-10-09T06:00:00Z'}),'INVALID_PUBLICATION_GRANT');
  const grant=await x.engine.grantPublication({entityId:ENTITY,fields:['title','summary'],expiresAt:'2026-10-09T06:00:00Z'});
  const packet=await x.engine.exportContext({entityIds:[ENTITY],purpose:'public',grants:[grant.id]});
  assert.equal(packet.records.length,1);assert.equal(packet.records[0].id,ENTITY);assert.deepEqual(Object.keys(packet.records[0].data).sort(),['summary','title']);
  assert.equal(JSON.stringify(packet).includes('internalText'),false);assert.equal(JSON.stringify(packet).includes(second.entityId),false);assert.equal(packet.checkpoint,null);
});

test('publication grant expires and is fenced by the exact entity revision approved by its owner',async()=>{
  const x=await setup();const grant=await x.engine.grantPublication({entityId:ENTITY,fields:['title'],expiresAt:'2026-10-09T06:00:00Z'});
  await x.engine.transitionEntity({entityId:ENTITY,dimension:'task',to:'READY',evidenceId:x.observed.evidenceId});
  await rejectsCode(()=>x.engine.exportContext({entityIds:[ENTITY],purpose:'public',grants:[grant.id]}),'RECORD_PUBLICATION_GRANT_REQUIRED');
  const fresh=await x.engine.grantPublication({entityId:ENTITY,fields:['title'],expiresAt:'2026-10-09T06:00:00Z'});x.advance(86401000);
  await rejectsCode(()=>x.engine.exportContext({entityIds:[ENTITY],purpose:'public',grants:[fresh.id]}),'RECORD_PUBLICATION_GRANT_REQUIRED');
});

test('a public field grant cannot smuggle an object or oversized source body through its field name',async()=>{
  for(const summary of [{unapprovedSource:'Synthetic nested source'},'x'.repeat(2001)]) {
    const x=await setup({}, {claim:claim({data:{title:'Synthetic',status:'DRAFT',summary}})});
    const grant=await x.engine.grantPublication({entityId:ENTITY,fields:['summary'],expiresAt:'2026-10-09T06:00:00Z'});
    await rejectsCode(()=>x.engine.exportContext({entityIds:[ENTITY],purpose:'public',grants:[grant.id]}),'NON_SCALAR_PUBLIC_FIELD');
  }
});

test('verified mirror write evidence cannot establish an external outcome or a completed task',async()=>{
  const x=await setup(),op=await x.engine.run(x.prepared.id);
  await rejectsCode(()=>x.engine.transitionEntity({entityId:ENTITY,dimension:'external',to:'SUCCEEDED',evidenceId:op.verificationEvidenceId}),'EXTERNAL_OUTCOME_EVIDENCE_REQUIRED');
  for(const to of ['READY','SUBMITTED','ACKNOWLEDGED'])await x.engine.transitionEntity({entityId:ENTITY,dimension:'task',to,evidenceId:x.observed.evidenceId});
  await rejectsCode(()=>x.engine.transitionEntity({entityId:ENTITY,dimension:'task',to:'VERIFIED',evidenceId:op.verificationEvidenceId}),'EXTERNAL_OUTCOME_EVIDENCE_REQUIRED');
  const state=await x.store.load();assert.equal(state.entities[ENTITY].lifecycle.external,'UNKNOWN');assert.equal(state.entities[ENTITY].lifecycle.task,'ACKNOWLEDGED');
});

test('canonical revision changed after preparation blocks provider execution before any read or write',async()=>{
  const x=await setup();await x.engine.transitionEntity({entityId:ENTITY,dimension:'task',to:'READY',evidenceId:x.observed.evidenceId});
  const op=await x.engine.run(x.prepared.id);assert.equal(op.status,'CONFLICT');assert.equal(op.errorCode,'CANONICAL_CHANGED');assert.equal(x.fixture.calls.length,0);
});

test('canonical change while a provider read is in flight is fenced immediately before apply',async()=>{
  const entered=deferred(),release=deferred();
  const x=await setup({read:async({externalId,projection,revision})=>{entered.resolve();await release.promise;return {externalId,projection,revision:`synthetic-rev-${revision}`,sourceRef:'fixture://synthetic/provider/001'};}});
  const pending=x.engine.run(x.prepared.id);await entered.promise;
  await x.engine.transitionEntity({entityId:ENTITY,dimension:'task',to:'READY',evidenceId:x.observed.evidenceId});release.resolve();
  const op=await pending;assert.equal(op.status,'CONFLICT');assert.equal(op.errorCode,'CANONICAL_CHANGED');assert.equal(x.fixture.writes(),0);
});

test('journal alteration, deletion and reordering fail integrity verification',async()=>{
  const x=await setup();await x.engine.run(x.prepared.id);const state=await x.store.load();
  for(const change of [entries=>{entries[0].after.contentHash='0'.repeat(64);},entries=>{entries.splice(1,1);},entries=>{[entries[0],entries[1]]=[entries[1],entries[0]];}]){
    const entries=copy(state.journal);change(entries);await rejectsCode(()=>Sync.verifyJournal(entries),'JOURNAL_INTEGRITY_FAILURE');
  }
  assert.equal((await x.engine.verifyJournal()).valid,true);
});

test('receipt alteration fails its digest and rehashed fabricated verification fails its evidence boundary',async()=>{
  const x=await setup();await x.engine.run(x.prepared.id);const receipt=await x.engine.receipt();
  assert.equal((await Sync.verifyReceipt(receipt)).externalTruthVerified,false);
  const tampered=copy(receipt);tampered.state.entities[ENTITY].data.title='Altered after receipt';
  await rejectsCode(()=>Sync.verifyReceipt(tampered),'RECEIPT_INTEGRITY_FAILURE');
  const fabricated=copy(receipt),op=fabricated.state.outbox[x.prepared.id];
  fabricated.state.evidence[op.verificationEvidenceId].verification.status='UNVERIFIED';
  await rehashReceipt(fabricated);await rejectsCode(()=>Sync.verifyReceipt(fabricated),'UNSUPPORTED_VERIFIED_RECEIPT');
});

test('recomputed receipt envelope cannot replace an original observed source payload',async()=>{
  const x=await setup(),receipt=await x.engine.receipt();
  const replaced=copy(receipt);replaced.state.evidence[x.observed.evidenceId].data.title='Synthetic substituted source body';
  await rehashReceipt(replaced);await rejectsCode(()=>Sync.verifyReceipt(replaced),'REPLACED_SOURCE_EVIDENCE');
  const replacedAndRehashed=copy(replaced);replacedAndRehashed.state.evidence[x.observed.evidenceId].contentHash=await Sync.sha256Hex(replacedAndRehashed.state.evidence[x.observed.evidenceId].data);
  await rehashReceipt(replacedAndRehashed);await rejectsCode(()=>Sync.verifyReceipt(replacedAndRehashed),'UNSUPPORTED_OBSERVATION_JOURNAL');
});

test('recomputed receipt envelope cannot legitimize malformed lifecycle, entity identity or version',async()=>{
  const x=await setup(),receipt=await x.engine.receipt();
  for(const corrupt of [entity=>{entity.lifecycle.task='INVENTED_COMPLETE';},entity=>{entity.id='ent_other_synthetic';},entity=>{entity.version=-1;},entity=>{delete entity.lifecycle.external;}]){
    const packet=copy(receipt);corrupt(packet.state.entities[ENTITY]);await rehashReceipt(packet);
    await rejectsCode(()=>Sync.verifyReceipt(packet),'INVALID_RECEIPT_ENTITY');
  }
});

test('recomputed receipt envelope cannot point a provider mapping at a missing identity',async()=>{
  const x=await setup(),receipt=await x.engine.receipt();
  for(const key of [Sync.canonicalJson([PROVIDER,EXTERNAL]),'malformed provider mapping']){
    const packet=copy(receipt);packet.state.providerMaps[key]='ent_missing_synthetic';await rehashReceipt(packet);
    await rejectsCode(()=>Sync.verifyReceipt(packet),'INVALID_PROVIDER_MAP');
  }
});

test('receipt import remains evidence rather than authority to overwrite canonical state',async()=>{
  const source=await setup();const receipt=await source.engine.receipt();
  const store=Sync.createMemoryStore(),engine=Sync.createSyncEngine({store,clock:()=>TIME});
  await engine.observe(claim({data:{title:'Owner canonical title',status:'DRAFT'}}));
  const first=await engine.importReceipt(receipt),beforeReplay=await store.load(),second=await engine.importReceipt(receipt),state=await store.load();
  assert.equal(first.externalTruthVerified,false);assert.equal(second.duplicate,true);assert.equal(state.entities[ENTITY].data.title,'Owner canonical title');
  assert.equal(state.conflicts.length,1);assert.equal(state.journal.filter(e=>e.operation==='IMPORT_RECEIPT').length,1);
  assert.equal(state.revision,beforeReplay.revision);
});

const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { randomBytes } = require('node:crypto');
async function durableFixture(t, options = {}) {
  const { createEncryptedFileStore } = await import('../tools/sync-file-store.mjs');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(),'continuity-sync-synthetic-'));
  t.after(()=>fs.rm(directory,{recursive:true,force:true}));
  const file = path.join(directory,'synthetic.encrypted.json');
  const key = randomBytes(32);
  const store = createEncryptedFileStore({path:file,key,...options});
  return {directory,file,key,store,createEncryptedFileStore};
}

test('encrypted store preserves private records across fresh instances without plaintext or key disclosure',async t=>{
  const x=await durableFixture(t),engine=Sync.createSyncEngine({store:x.store,clock:()=>TIME});
  const record=claim({privacy:'PRIVATE',data:{title:'SYNTHETIC_PRIVATE_CONTENT_SENTINEL',status:'DRAFT'}});
  await engine.observe(record);const original=await x.store.load();
  const restarted=x.createEncryptedFileStore({path:x.file,key:x.key});assert.equal(restarted.secure,true);assert.deepEqual(await restarted.load(),original);
  const encrypted=await fs.readFile(x.file,'utf8');assert.equal(encrypted.includes('SYNTHETIC_PRIVATE_CONTENT_SENTINEL'),false);assert.equal(encrypted.includes(ENTITY),false);assert.equal(encrypted.includes(x.key.toString('hex')),false);
  assert.equal((await fs.stat(x.file)).mode&0o077,0,'encrypted state must be owner-only readable');
});

test('wrong encryption key cannot read or mutate an existing store and leaves its bytes intact',async t=>{
  const x=await durableFixture(t);await x.store.mutate(state=>{state.inbox.synthetic={value:'original'};});
  const original=await fs.readFile(x.file);
  const wrong=x.createEncryptedFileStore({path:x.file,key:randomBytes(32)});
  await rejectsCode(()=>wrong.load(),'STORE_AUTHENTICATION_OR_INTEGRITY_FAILED');
  await rejectsCode(()=>wrong.mutate(state=>{state.inbox.synthetic={value:'overwrite'};}),'STORE_AUTHENTICATION_OR_INTEGRITY_FAILED');
  assert.deepEqual(await fs.readFile(x.file),original);assert.equal((await x.store.load()).inbox.synthetic.value,'original');
  await assert.rejects(fs.access(x.file+'.lock'),error=>error.code==='ENOENT');
});

test('ciphertext, authentication tag and nonce tampering are rejected before records are returned',async t=>{
  const x=await durableFixture(t);await x.store.mutate(state=>{state.inbox.synthetic={value:'original'};});
  const original=await fs.readFile(x.file,'utf8');
  for(const field of ['ciphertext','tag','iv']) {
    const envelope=JSON.parse(original),bytes=Buffer.from(envelope[field],'base64');bytes[0]^=1;envelope[field]=bytes.toString('base64');
    await fs.writeFile(x.file,JSON.stringify(envelope));
    await rejectsCode(()=>x.store.load(),'STORE_AUTHENTICATION_OR_INTEGRITY_FAILED');
  }
  await fs.writeFile(x.file,original);assert.equal((await x.store.load()).inbox.synthetic.value,'original');
});

test('cross-instance durable mutations serialize and retain every concurrent change',async t=>{
  const x=await durableFixture(t);
  const stores=Array.from({length:8},()=>x.createEncryptedFileStore({path:x.file,key:x.key,lockTimeoutMs:2000}));
  await Promise.all(stores.map((store,index)=>store.mutate(state=>{state.inbox[`synthetic-${index}`]={value:index};})));
  const state=await x.store.load();assert.equal(state.revision,8);assert.equal(Object.keys(state.inbox).length,8);
  for(let index=0;index<8;index++)assert.equal(state.inbox[`synthetic-${index}`].value,index);
  assert.deepEqual((await fs.readdir(x.directory)).sort(),['synthetic.encrypted.json']);
});

test('durable compare-and-set revision rejects stale writers without losing their saved predecessor',async t=>{
  const x=await durableFixture(t),other=x.createEncryptedFileStore({path:x.file,key:x.key});
  const base=await x.store.load();await x.store.mutate(state=>{state.inbox.synthetic={value:'newer'};},{expectedRevision:base.revision});
  await rejectsCode(()=>other.mutate(state=>{state.inbox.synthetic={value:'stale'};},{expectedRevision:base.revision}),'STORE_REVISION_CONFLICT');
  assert.equal((await other.load()).inbox.synthetic.value,'newer');assert.equal((await other.load()).revision,1);
});

test('held durable lock times out safely and a later mutation succeeds after owner release',async t=>{
  const x=await durableFixture(t),other=x.createEncryptedFileStore({path:x.file,key:x.key,lockTimeoutMs:30});
  const entered=deferred(),release=deferred();
  const held=x.store.mutate(async state=>{entered.resolve();await release.promise;state.inbox.first={value:1};});
  await entered.promise;
  try { await rejectsCode(()=>other.mutate(state=>{state.inbox.second={value:2};}),'STORE_LOCKED'); }
  finally { release.resolve();await held; }
  await other.mutate(state=>{state.inbox.second={value:2};});
  const state=await other.load();assert.deepEqual(state.inbox,{first:{value:1},second:{value:2}});assert.equal(state.revision,2);
});

test('durable state refuses an invalid journal before replacing its authenticated predecessor',async t=>{
  const x=await durableFixture(t),engine=Sync.createSyncEngine({store:x.store,clock:()=>TIME});
  await engine.observe(claim({privacy:'PRIVATE'}));const original=await fs.readFile(x.file);
  await rejectsCode(()=>x.store.mutate(state=>{state.journal[0].after.contentHash='0'.repeat(64);}),'JOURNAL_INTEGRITY_FAILURE');
  assert.deepEqual(await fs.readFile(x.file),original);assert.equal((await engine.verifyJournal()).valid,true);
});

test('two fresh durable engines share an operation lease and issue one provider write',async t=>{
  const d=await durableFixture(t),x=await setup({}, {store:d.store,claim:claim({privacy:'PRIVATE'})});
  const otherStore=d.createEncryptedFileStore({path:d.file,key:d.key});
  const other=Sync.createSyncEngine({store:otherStore,adapters:{[PROVIDER]:x.fixture.adapter},clock:x.clock});
  await Promise.all([x.engine.run(x.prepared.id),other.run(x.prepared.id)]);
  assert.equal(x.fixture.writes(),1);assert.equal((await otherStore.load()).outbox[x.prepared.id].status,'VERIFIED');
  assert.equal((await Sync.verifyReceipt(await other.receipt())).externalTruthVerified,false);
});

test('duplicate observations in durable storage do not rewrite encrypted bytes or revision',async t=>{
  const x=await durableFixture(t),engine=Sync.createSyncEngine({store:x.store,clock:()=>TIME});
  const original=claim({privacy:'PRIVATE'});await engine.observe(original);
  const bytes=await fs.readFile(x.file),before=await x.store.load();await engine.observe(original);
  assert.deepEqual(await fs.readFile(x.file),bytes);assert.equal((await x.store.load()).revision,before.revision);
});
