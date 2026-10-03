'use strict';
const { test } = require('node:test');
const a = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const W = require('../site/workspace-core.js');
const O = require('../site/operations-core.js');
const M = require('../tools/migrate-family.cjs');
const at = '2026-10-03T23:00:00Z';

function project() {
  const state = W.empty();
  state.threads = [{ id: 't', title: 'Synthetic private project', state: 'CONTINUE', objective: 'Preserve', last: '', next: 'Review', waiting: '', open: [] }];
  state.sources = [{ id: 's', title: 'Witness', text: '  First line\r\nsecond line\r\n', url: '', speaker: 'unknown', threadId: 't', createdAt: at,
    sourceOccurrences: [{ archive: 'synthetic', span: [4, 5] }], attribution: { status: 'uncertain' }, permission: 'private_only', customField: { retain: true } }];
  state.notes = [{ id: 'n', sourceId: 's', threadId: 't', kind: 'constraint', statement: 'Unconfirmed interpretation', start: 1, end: 1,
    quote: '  First line', reviewed: true, reviewState: 'confirmed' }];
  return { format: 'ContinuityProject/1', packetId: 'original', exportedAt: at, packetScope: 'update', updatesExistingProject: false,
    seedbankSnapshotId: 'v5', seedbankParentSnapshotId: 'v4', customEnvelope: { retain: true }, state };
}
const bytes = value => Buffer.from(JSON.stringify(value, null, 2) + '\r\n');
function temp(t) { const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'family-migration-')); t.after(() => fs.rmSync(dir, { recursive: true, force: true })); return dir; }

test('native packet preserves raw bytes, CRLF text, metadata and lineage while demoting all notes', () => {
  const input = project(), raw = bytes(input), original = Buffer.from(raw), out = M.migrate(raw, { at });
  a.deepEqual(raw, original); a.deepEqual(out.raw, raw);
  a.equal(out.report.nativeInputValid, true);
  a.equal(out.packet.state.sources[0].text, input.state.sources[0].text);
  a.deepEqual(out.packet.state.sources[0].sourceOccurrences, input.state.sources[0].sourceOccurrences);
  a.deepEqual(out.packet.state.sources[0].customField, { retain: true });
  a.deepEqual(out.packet.customEnvelope, { retain: true });
  a.equal(out.packet.seedbankParentSnapshotId, 'v4');
  a.equal(out.packet.state.notes[0].reviewState, 'pending');
  a.equal(out.packet.state.notes[0].notUserConfirmed, true);
  a.equal(out.packet.state.notes[0].familyMigration.originalFields.reviewState, 'confirmed');
  a.equal(out.packet.familyMigration.grantsPermission, false);
  a.equal(out.report.input.sha256, M.hash(raw));
});

test('UTF-8 BOM and whitespace survive exact evidence companion bytes', () => {
  const raw = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), bytes(project())]);
  const out = M.migrate(raw, { at }); a.deepEqual(out.raw, raw); a.equal(out.report.input.bytes, raw.length);
});

test('update is a separate copy; pending imported content excluded from handoff', () => {
  const p = M.migrate(bytes(project()), { at }).packet;
  let n = 0; const id = () => 'copy-' + ++n;
  const original = O.validate(project().state), out = O.importProject(original, p, id, at);
  a.equal(out.state.threads.length, 2); a.notEqual(out.threadId, 't');
  a.equal(out.state.sources.find(src => src.threadId === out.threadId).text, original.sources[0].text);
  a.equal(out.state.notes.find(note => note.threadId === out.threadId).reviewState, 'pending');
  const handoff = O.resumePack(out.state, out.threadId, at);
  a.ok(!handoff.includes('Unconfirmed interpretation')); a.match(handoff, /await review/);
  a.throws(() => O.importProject(out.state, p, id, at), /already imported/);
  a.equal(p.updatesExistingProject, false);
});

test('v5-shaped missing fields and narrative memo become private sources with pending context', () => {
  const p = project();
  p.state.sources = [{ id: 's', title: 'Private excerpt', text: 'Exact excerpt\n', source_ref: { drive_id: 'synthetic-id', surviving_file_timestamp: '2012-01-01' }, privacy: 'private', provenance: 'uncertain' }];
  p.state.notes = [{ id: 'memo', title: 'Narrative', body: 'This is not a confirmed decision.\r\n', private: true }];
  const out = M.migrate(bytes(p), { at }), s = out.packet.state;
  a.equal(out.report.nativeInputValid, false); a.equal(s.sources.length, 2);
  a.equal(s.sources[0].speaker, 'unknown'); a.equal(s.sources[0].createdAt, at);
  a.match(s.sources[0].familyMigration.createdAtMeaning, /not composition/);
  a.deepEqual(s.sources[0].source_ref, p.state.sources[0].source_ref);
  a.equal(s.sources[0].permission, 'private_only'); a.equal(s.sources[1].text, p.state.notes[0].body);
  a.equal(s.notes[0].reviewState, 'pending'); a.equal(s.notes[0].kind, 'context');
  a.deepEqual(s.notes[0].familyMigration.originalRecord, p.state.notes[0]);
  a.equal(out.report.counts.narrativeReports, 1);
  O.parseProject(JSON.stringify(out.packet));
});

test('invalid quoted checkpoint is preserved as report rather than repaired into authority', () => {
  const p = project(); p.state.notes[0].quote = 'fabricated quote';
  const out = M.migrate(bytes(p), { at });
  a.equal(out.packet.state.notes[0].kind, 'context');
  a.equal(out.packet.state.notes[0].familyMigration.originalRecord.quote, 'fabricated quote');
  a.match(out.packet.state.sources[1].text, /fabricated quote/);
  a.equal(out.packet.state.sources[1].speaker, 'unknown');
});

test('permission and attribution payload cannot grant publication or acceptance', () => {
  const p = project(); const source = p.state.sources[0];
  source.permission = 'publish-anywhere'; source.privacy = 'public'; source.speaker = 'certain-author'; source.url = 'javascript:alert(1)';
  const out = M.migrate(bytes(p), { at }).packet.state.sources[0];
  a.equal(out.permission, 'private_only'); a.equal(out.privacy, 'private'); a.equal(out.speaker, 'unknown'); a.equal(out.url, '');
  a.equal(out.familyMigration.originalFields.permission, 'publish-anywhere');
  a.equal(out.familyMigration.originalFields.speaker, 'certain-author');
  a.equal(out.familyMigration.consent.publication, false); a.equal(out.familyMigration.consent.externalProcessing, false);
});

test('separate copy archives device-local import and exchange identities', () => {
  const p = project(); Object.assign(p.state.threads[0], { exchangeProjectId: 'remote', exchangeSeen: ['accepted'], importedPacketId: 'old-import' });
  const t = M.migrate(bytes(p), { at }).packet.state.threads[0];
  a.equal(t.exchangeProjectId, undefined); a.equal(t.importedPacketId, undefined);
  a.deepEqual(t.familyMigration.originalFields.exchangeSeen, ['accepted']);
});

test('deterministic identity is tied to exact raw bytes, not normalized JSON', () => {
  const p = project(), one = M.migrate(bytes(p), { at }), two = M.migrate(bytes(p), { at: '2026-10-04T00:00:00Z' });
  a.equal(one.packet.packetId, two.packet.packetId);
  a.notEqual(one.packet.packetId, M.migrate(Buffer.from(JSON.stringify(p)), { at }).packet.packetId);
});

test('private branch portable and encrypted formats are blocked without downgrade or decryption', () => {
  for (const input of [{ format: 'continuity-portable-bundle', state: {}, events: [] }, { format: 'continuity-encrypted-vault' },
    { protocol: 'ContinuityVault/0.1' }, { protocol: 'ContinuityLocalVault/0.1' }, { format: 'continuity-encrypted-sync' }]) {
    a.throws(() => M.migrate(bytes(input), { at }), /security downgrade.*blocked/);
  }
});

test('unsupported scopes, duplicate source IDs, unsafe metadata and invalid UTF-8 fail closed', () => {
  const p = project(); p.state.sources.push(W.copy(p.state.sources[0])); a.throws(() => M.migrate(bytes(p), { at }), /Duplicate source/);
  const nonProject = project(); nonProject.state.captures.push({ id: 'c' }); a.throws(() => M.migrate(bytes(nonProject), { at }), /non-project/);
  a.throws(() => M.migrate(Buffer.from('{"format":"ContinuityProject/1","state":{},"__proto__":{}}'), { at }), /unsafe/);
  a.throws(() => M.migrate(Buffer.from([0xff])), /encoded data|encoding|UTF-8/i);
  a.throws(() => M.migrate(Buffer.alloc(M.MAX + 1)), /16 MB/);
  const legacy = project(); legacy.state.version = '0.10.0';
  a.throws(() => M.migrate(bytes(legacy), { at }), /legacy upgrades can discard source/);
});

test('CLI default dry-run writes no files and logs no source text', t => {
  const dir = temp(t), file = path.join(dir, 'input.json'); fs.writeFileSync(file, bytes(project()));
  const out = spawnSync(process.execPath, [path.join(__dirname, '../tools/migrate-family.cjs'), file], { encoding: 'utf8' });
  a.equal(out.status, 0, out.stderr); a.equal(JSON.parse(out.stdout).dryRun, true);
  a.deepEqual(fs.readdirSync(dir), ['input.json']); a.ok(!out.stdout.includes('First line'));
});

test('explicit output writes exact original, hash report and private-mode importable packet', t => {
  const dir = temp(t), input = path.join(dir, 'input.json'), output = path.join(dir, 'new.json'), raw = bytes(project());
  fs.writeFileSync(input, raw);
  const report = M.writeArtifacts(input, output, at);
  a.deepEqual(fs.readFileSync(output + '.original.json'), raw); a.deepEqual(fs.readFileSync(input), raw);
  a.equal(report.output.sha256, M.hash(fs.readFileSync(output)));
  a.equal(O.parseProject(fs.readFileSync(output, 'utf8')).state.notes[0].reviewState, 'pending');
  for (const file of [output, output + '.original.json', output + '.migration-report.json']) a.equal(fs.statSync(file).mode & 0o777, 0o600);
  a.throws(() => M.writeArtifacts(input, output, at), /overwrite/);
});

test('private encrypted branch never writes output or evidence copies', t => {
  const dir = temp(t), input = path.join(dir, 'vault.json'), output = path.join(dir, 'new.json');
  fs.writeFileSync(input, JSON.stringify({ protocol: 'ContinuityVault/0.1', ciphertext: 'opaque' }));
  a.throws(() => M.writeArtifacts(input, output, at), /security downgrade/);
  a.deepEqual(fs.readdirSync(dir), ['vault.json']);
});

test('existing companion collision cannot overwrite data or create a partial packet', t => {
  const dir = temp(t), input = path.join(dir, 'input.json'), output = path.join(dir, 'new.json');
  fs.writeFileSync(input, bytes(project())); fs.writeFileSync(output + '.original.json', 'existing');
  a.throws(() => M.writeArtifacts(input, output, at), /overwrite/);
  a.equal(fs.existsSync(output), false); a.equal(fs.readFileSync(output + '.original.json', 'utf8'), 'existing');
});
