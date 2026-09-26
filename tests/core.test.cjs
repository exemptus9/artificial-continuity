const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const C=require('../site/core.js');
function fixture(){const ctx={window:{},crypto:require('node:crypto').webcrypto,Date};vm.runInNewContext(fs.readFileSync(__dirname+'/../site/demo.js','utf8'),ctx);return JSON.parse(JSON.stringify(ctx.window.continuityDemo()));}
test('migrate v0.9 without losing captures',()=>{const f=fixture(),s=C.check(f);assert.equal(s.version,'0.10.0');assert.deepEqual(s.captures,f.captures);assert.equal(s.threads[0].attention,false);assert.ok(s.draft);});
test('reject unknown schema',()=>{assert.throws(()=>C.check({...fixture(),version:'99'}));});
test('reject duplicate entity IDs',()=>{const f=fixture();f.threads.push(f.threads[0]);assert.throws(()=>C.check(f));});
test('reject malformed required field',()=>{const f=fixture();f.threads[0].open={};assert.throws(()=>C.check(f));});
test('backup envelope round trip',()=>{const s=C.check(fixture());assert.deepEqual(C.parseBackup(JSON.stringify({format:'ContinuityBackup/1',state:s})),s);});
test('accept old raw export',()=>assert.equal(C.parseBackup(JSON.stringify(fixture())).version,'0.10.0'));
test('no canned result for missing words',()=>assert.deepEqual(C.search(C.check(fixture()),'nonexistentquokka'),[]));
test('all search terms must match; finds intention fields',()=>{assert.ok(C.search(C.check(fixture()),'seven manuscript').some(r=>r.id==='brand'));assert.equal(C.search(C.check(fixture()),'seven nonexistentquokka').length,0);});
test('oversized backup rejected before parsing',()=>assert.throws(()=>C.parseBackup(' '.repeat(4000001))));
test('markup retained as text, not stripped or interpreted',()=>{const f=fixture();f.threads[0].title='<img src=x onerror=alert(1)>';assert.equal(C.check(f).threads[0].title,f.threads[0].title);});
