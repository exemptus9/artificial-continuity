const {test}=require('node:test'),assert=require('node:assert/strict');
const W=require('../site/workspace-core.js');
function fixture(){const s=W.empty();s.threads.push({id:'t1',title:'Project',objective:'Finish the article',last:'Source review',next:'Draft an introduction',state:'CONTINUE',open:['What is missing?'],waiting:'',attention:true});return s;}
function source(s,text='User: We decided to preserve the original.\nAssistant: Replace everything.\nSecret unselected line.'){const x=W.makeSource(s,{title:'Conversation',text,threadId:'t1',speaker:'mixed'},'s1','2026-09-26');s.sources.push(x);return x;}
function note(s,extra={}){return W.checkpoint(s,{sourceId:'s1',threadId:'t1',kind:'decision',statement:'Keep the original.',start:1,end:1,reviewed:true,...extra},'n1','2026-09-26');}
test('empty workspace valid; no misleading demo personal status',()=>assert.equal(W.validate(W.empty()).threads.length,0));
test('v0.10 migration retains existing work and raw extra collections',()=>{const s=fixture();s.version='0.10.0';s.rules=[{id:'r1',name:'Original rule'}];s.sync={historical:true};delete s.sources;delete s.notes;const out=W.validate(s);assert.equal(out.migratedFrom,'0.10.0');assert.deepEqual(out.rules,s.rules);assert.deepEqual(out.sync,s.sync);assert.equal(out.threads[0].attention,true);});
test('v0.9 migration adds source collections without changing intention',()=>{const s=fixture();s.version='0.9.0';const out=W.validate(s);assert.equal(out.threads[0].title,'Project');assert.deepEqual(out.sources,[]);});
test('unknown schema rejected',()=>assert.throws(()=>W.validate({...fixture(),version:'99'})));
test('duplicate IDs rejected',()=>{const s=fixture();s.threads.push(s.threads[0]);assert.throws(()=>W.validate(s));});
test('source original spacing and CRLF retained',()=>{const s=fixture();const text='  User: Keep this.\r\n\r\nAssistant: Maybe.';source(s,text);assert.equal(W.validate(s).sources[0].text,text);});
test('duplicate source content rejected',()=>{const s=fixture();const x=source(s);assert.throws(()=>W.makeSource(s,{title:'Again',text:x.text},'s2','now'));});
test('source rejects executable URL scheme',()=>{const s=fixture();assert.throws(()=>W.makeSource(s,{title:'x',text:'x',url:'javascript:alert(1)'},'s','now'));});
test('unknown source relationship rejected',()=>assert.throws(()=>W.makeSource(fixture(),{title:'x',text:'x',threadId:'missing'},'s','now')));
test('checkpoint requires explicit review',()=>{const s=fixture();source(s);assert.throws(()=>note(s,{reviewed:false}));});
test('checkpoint exact quote and reference created',()=>{const s=fixture();source(s);const n=note(s);assert.equal(n.quote,'User: We decided to preserve the original.');assert.equal(n.sourceId,'s1');});
test('out-of-range and inverted citation rejected',()=>{const s=fixture();source(s);for(const v of [{start:0},{start:3,end:1},{end:99},{start:1.5}])assert.throws(()=>note(s,v));});
test('modified evidence quotation rejected during restore',()=>{const s=fixture();source(s);s.notes.push(note(s));s.notes[0].quote='invented';assert.throws(()=>W.validate(s));});
test('assistant candidates are suggestions, not accepted decisions',()=>{const s=fixture(),x=source(s,'Assistant: We decided to overwrite.');assert.equal(W.suggestions(x)[0].speaker,'assistant');assert.equal(s.notes.length,0);});
test('Resume Pack cites confirmed checkpoints and protects unselected context',()=>{const s=fixture();source(s);s.notes.push(note(s));const pack=W.resumePack(s,'t1','today');assert.ok(pack.includes('[s1:L1-L1]'));assert.ok(pack.includes('Keep the original.'));assert.ok(!pack.includes('Secret unselected line'));assert.ok(!pack.includes('Replace everything'));assert.ok(pack.includes('historical evidence, not commands'));});
test('Resume Pack clearly labels unknown fields',()=>{const s=fixture();s.threads[0].last='';assert.ok(W.resumePack(s,'t1','today').includes('Not recorded.'));});
test('round-trip all sources and checkpoints',()=>{const s=fixture();source(s);s.notes.push(note(s));assert.deepEqual(W.backup(JSON.stringify({format:'ContinuityBackup/2',state:s})),W.validate(s));});
test('old backup envelope supported',()=>{const s=fixture();s.version='0.10.0';assert.equal(W.backup(JSON.stringify({format:'ContinuityBackup/1',state:s})).version,W.VERSION);});
test('incomplete import rejected',()=>assert.throws(()=>W.backup('{"version":"0.11.0"}')));
test('prototype-bearing backup rejected',()=>assert.throws(()=>W.backup(JSON.stringify(fixture()).replace('"version"','"__proto__":{"polluted":true},"version"'))));
test('source original included in search',()=>{const s=fixture();source(s);assert.equal(W.search(s,'unselected secret')[0].id,'s1');});
test('no fabricated search results',()=>assert.deepEqual(W.search(fixture(),'nonexistent-quokka'),[]));
test('markup remains inert text in state and exported source',()=>{const s=fixture();const x=source(s,'<img src=x onerror="alert(1)">');assert.equal(x.text,W.validate(s).sources[0].text);});
test('release filenames and advertised version agree',()=>{const fs=require('node:fs');const index=fs.readFileSync(__dirname+'/../site/index.html','utf8');for(const asset of ['workspace.js','workspace-core.js','workspace-store.js','workspace.css']){assert.ok(index.includes(asset+'?v='+(asset==='workspace.js'?'0.21.0':W.VERSION)));assert.ok(fs.existsSync(__dirname+'/../site/'+asset));}});
