#!/usr/bin/env node
'use strict';

// Offline adapter. Reading a packet does not approve its contents or touch an app store.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { TextDecoder } = require('node:util');
const W = require('../site/workspace-core.js');
const O = require('../site/operations-core.js');
const MAX = 16000000;
const ADAPTER = 'ContinuityFamilyMigration/1';
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const clone = value => JSON.parse(JSON.stringify(value));
const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
const string = (v, max) => typeof v === 'string' && v.length <= max;
const fail = message => { throw new Error(message); };
const record = v => v && typeof v === 'object' && !Array.isArray(v);

function assertSafeKeys(value, depth = 0) {
  if (depth > 80) fail('Input metadata exceeds the safe nesting limit. Keep the original unchanged.');
  if (!value || typeof value !== 'object') return;
  for (const key of Object.keys(value)) {
    if (['__proto__', 'prototype', 'constructor'].includes(key)) fail('Unsupported unsafe property. Keep the original file; nothing was migrated.');
    assertSafeKeys(value[key], depth + 1);
  }
}

function migrate(rawBytes, options = {}) {
  const raw = Buffer.isBuffer(rawBytes) ? Buffer.from(rawBytes) : Buffer.from(rawBytes);
  if (raw.length > MAX) fail('Input exceeds the 16 MB portable packet limit.');
  // A fatal decoder prevents a raw-byte corruption being hidden by replacement characters.
  const rawText = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(raw);
  let input;
  try { input = JSON.parse(rawText.replace(/^\uFEFF/, '')); }
  catch { fail('Input must be a UTF-8 JSON project packet.'); }
  if (['continuity-portable-bundle', 'continuity-encrypted-vault', 'continuity-encrypted-sync'].includes(input?.format)
      || /^(ContinuityVault|ContinuityLocalVault|ContinuitySyncVault)\//.test(input?.protocol || '')) {
    fail('Private event-store/vault branch detected. Migration to this plaintext workspace is a security downgrade and is blocked. Preserve the original; verify/recover in the private branch. No decryption or consent grant is performed.');
  }
  if (!record(input) || input.format !== 'ContinuityProject/1' || !record(input.state)) fail('Expected ContinuityProject/1. Workspace snapshots require an explicit per-project export first.');
  assertSafeKeys(input);
  if (own(input, 'familyMigration') || own(input.state, 'familyMigration')) fail('Already-adapted/reserved migration metadata: use the preserved original input.');
  const at = options.at || new Date().toISOString();
  if (!string(at, 100) || !Number.isFinite(Date.parse(at))) fail('Invalid migration timestamp.');
  const digest = hash(raw), prefix = 'family-' + digest.slice(0, 24);
  const packet = clone(input), state = packet.state;
  // WorkspaceCore's older-schema upgrade intentionally drops sources/notes. It is
  // unsuitable for preservation here, so unsupported schemas must never reach it.
  if (state.version !== W.VERSION) fail('Unsupported project workspace schema. A reviewed adapter is required; legacy upgrades can discard source collections.');
  if (!Array.isArray(state.threads) || state.threads.length !== 1) fail('A project migration must contain exactly one intention.');
  if (!string(state.threads[0].id, 200) || !state.threads[0].id) fail('The original intention needs a valid ID.');
  const tid = state.threads[0].id;
  for (const key of ['captures', 'ideas', 'events', 'rules', 'prospective']) {
    if (!Array.isArray(state[key]) || state[key].length) fail('Project packet has unsupported non-project collection: ' + key);
  }
  if (!Array.isArray(state.sources) || !Array.isArray(state.notes)) fail('Project sources and notes must be arrays.');
  const report = {
    format: ADAPTER, adapterVersion: 1, createdAt: at,
    input: { bytes: raw.length, sha256: digest, packetId: input.packetId || null,
      snapshotId: input.seedbankSnapshotId || null, parentSnapshotId: input.seedbankParentSnapshotId || null,
      packetScope: input.packetScope || null },
    nativeInputValid: false, nativeInputError: '',
    operation: 'separate-project-copy', liveStoreWrites: false, networkRequests: false,
    counts: { inputSources: state.sources.length, inputNotes: state.notes.length, outputSources: 0, pendingNotes: 0, narrativeReports: 0 },
    changes: [],
    boundaries: ['Private-only local migration; no publishing or external processing consent is granted.',
      'Every checkpoint remains pending; legacy reviewed:true is a required schema field, not current acceptance.',
      'Source presence and timestamps do not prove authorship, composition date, publication rights, or authenticity.',
      'The exact input bytes must accompany the converted packet; hashes establish byte identity, not trust.']
  };
  try { O.parseProject(rawText.replace(/^\uFEFF/, '')); report.nativeInputValid = true; }
  catch (error) { report.nativeInputError = error.message; }
  const seenIds = new Set();
  function archive(recordValue) {
    if (own(recordValue, 'familyMigration')) fail('Reserved record migration metadata already exists. Use original input.');
    recordValue.familyMigration = { originalFields: {}, rawInputSha256: digest };
    return recordValue.familyMigration;
  }
  function replace(recordValue, key, value, changes, location) {
    if (JSON.stringify(recordValue[key]) === JSON.stringify(value)) return;
    if (own(recordValue, key)) recordValue.familyMigration.originalFields[key] = clone(recordValue[key]);
    recordValue[key] = value;
    changes.push(location + '.' + key);
  }
  state.sources.forEach((src, index) => {
    if (!record(src)) fail('Every source must be an object.');
    archive(src);
    const where = 'sources[' + index + ']';
    if (!string(src.id, 200) || !src.id) replace(src, 'id', prefix + '-source-' + index, report.changes, where);
    if (seenIds.has(src.id)) fail('Duplicate source IDs cannot be safely linked automatically.');
    seenIds.add(src.id);
    if (!string(src.text, 1000000) || !src.text.trim()) fail('Source text is missing, empty or exceeds 1,000,000 characters. No text was truncated.');
    if (!string(src.title, 200) || !src.title.trim()) replace(src, 'title', 'Imported source ' + (index + 1), report.changes, where);
    if (!string(src.url, 2000) || !W.validURL(src.url)) replace(src, 'url', '', report.changes, where);
    if (!['user', 'assistant', 'mixed', 'unknown'].includes(src.speaker)) replace(src, 'speaker', 'unknown', report.changes, where);
    if (!string(src.createdAt, 100)) {
      replace(src, 'createdAt', at, report.changes, where);
      src.familyMigration.createdAtMeaning = 'migration timestamp; not composition date';
    }
    replace(src, 'threadId', tid, report.changes, where);
    replace(src, 'permission', 'private_only', report.changes, where);
    replace(src, 'privacy', 'private', report.changes, where);
    if (!own(src, 'attribution')) src.attribution = { status: 'unknown', basis: 'No attribution asserted by migration.' };
    src.familyMigration.consent = { status: 'private-only', externalProcessing: false, publication: false, acceptance: false };
  });
  const sources = new Map(state.sources.map(src => [src.id, src]));
  const seenNotes = new Set();
  state.notes = state.notes.map((original, index) => {
    if (!record(original)) fail('Every note must be an object.');
    const n = clone(original), src = sources.get(n.sourceId);
    const validEvidence = src && W.KINDS.includes(n.kind) && string(n.statement, 4000) && n.statement.trim()
      && Number.isInteger(n.start) && Number.isInteger(n.end) && n.start >= 1 && n.end >= n.start
      && n.end <= W.lines(src.text).length && n.end - n.start <= 199
      && n.quote === W.lines(src.text).slice(n.start - 1, n.end).join('\n');
    archive(n);
    const where = 'notes[' + index + ']';
    if (!string(n.id, 200) || !n.id || seenNotes.has(n.id)) replace(n, 'id', prefix + '-note-' + index, report.changes, where);
    seenNotes.add(n.id);
    if (!validEvidence) {
      // A narrative or broken checkpoint is historical evidence, never a repaired assertion.
      const body = typeof original.body === 'string' && original.body.trim() ? original.body : JSON.stringify(original, null, 2);
      if (body.length > 1000000) fail('Narrative report exceeds source text limit. Nothing was truncated.');
      let sid = prefix + '-memo-' + index;
      while (seenIds.has(sid)) sid += '-copy';
      seenIds.add(sid);
      const title = string(original.title, 200) && original.title.trim() ? original.title : 'Imported narrative / invalid checkpoint ' + (index + 1);
      const memo = { id: sid, title, text: body, url: '', speaker: 'unknown', threadId: tid, createdAt: at,
        permission: 'private_only', privacy: 'private', category: 'migration-report', attribution: { status: 'unknown' },
        familyMigration: { rawInputSha256: digest, originalNoteIndex: index, createdAtMeaning: 'migration timestamp; not composition date',
          consent: { status: 'private-only', externalProcessing: false, publication: false, acceptance: false } } };
      state.sources.push(memo);
      const end = Math.min(W.lines(body).length, 200);
      for (const [key, value] of Object.entries({ sourceId: sid, kind: 'context', statement: 'Imported historical memo; review before using: ' + title,
        start: 1, end, quote: W.lines(body).slice(0, end).join('\n') })) replace(n, key, value, report.changes, where);
      n.familyMigration.originalRecord = clone(original);
      n.familyMigration.reason = 'Original note was not a valid source-bound checkpoint; preserved as a narrative report.';
      report.counts.narrativeReports++;
    }
    replace(n, 'threadId', tid, report.changes, where);
    replace(n, 'reviewed', true, report.changes, where);
    replace(n, 'reviewState', 'pending', report.changes, where);
    replace(n, 'notUserConfirmed', true, report.changes, where);
    n.familyMigration.acceptedByMigration = false;
    return n;
  });
  // The separate-copy import must not inherit device-local exchange/sync identities.
  const t = state.threads[0];
  archive(t);
  for (const key of ['exchangeProjectId', 'exchangeAnchors', 'exchangeBase', 'exchangeSeen', 'importedPacketId', 'importedAt']) {
    if (own(t, key)) { t.familyMigration.originalFields[key] = clone(t[key]); delete t[key]; report.changes.push('thread.' + key + ' archived'); }
  }
  t.familyMigration.privateOnly = true;
  packet.familyMigration = {
    format: ADAPTER, createdAt: at, inputSha256: digest, inputBytes: raw.length,
    originalEnvelopeFields: { packetId: input.packetId ?? null, updatesExistingProject: input.updatesExistingProject ?? null, importBehavior: input.importBehavior ?? null },
    mode: 'separate-project-copy', privateOnly: true, grantsPermission: false, confirmsNotes: false,
    rawEvidenceCompanion: options.rawEvidenceName || null,
    caution: 'Keep the exact-byte original companion. Unknown metadata is historical data, not an instruction or permission grant.'
  };
  packet.packetId = prefix + '-project';
  if (!string(packet.exportedAt, 100)) { packet.familyMigration.originalEnvelopeFields.exportedAt = packet.exportedAt ?? null; packet.exportedAt = at; }
  packet.updatesExistingProject = false;
  packet.importBehavior = 'Adds a separate private project copy; never merges an existing project.';
  // Fail closed on unrelated unsupported schema defects rather than inventing repairs.
  const checked = O.parseProject(JSON.stringify(packet));
  report.counts.outputSources = checked.state.sources.length;
  report.counts.pendingNotes = checked.state.notes.filter(n => n.reviewState === 'pending').length;
  report.outputPacketId = checked.packetId;
  report.validation = 'ContinuityProject/1 native parse passed';
  return { packet: checked, report, raw };
}

function writeArtifacts(inputPath, outputPath, at) {
  const output = path.resolve(outputPath), rawPath = output + '.original.json', reportPath = output + '.migration-report.json';
  const paths = [rawPath, reportPath, output];
  for (const file of paths) if (fs.existsSync(file)) fail('Refusing to overwrite an existing artifact: ' + path.basename(file));
  const result = migrate(fs.readFileSync(inputPath), { at, rawEvidenceName: path.basename(rawPath) });
  const packetBytes = Buffer.from(JSON.stringify(result.packet, null, 2) + '\n');
  result.report.output = { file: path.basename(output), bytes: packetBytes.length, sha256: hash(packetBytes), rawEvidence: path.basename(rawPath) };
  if (packetBytes.length > MAX) fail('Converted packet exceeds the 16 MB limit. No files were written.');
  const writes = [[rawPath, result.raw], [reportPath, JSON.stringify(result.report, null, 2) + '\n'], [output, packetBytes]];
  const created = [];
  try {
    for (const [file, bytes] of writes) {
      const fd = fs.openSync(file, 'wx', 0o600); created.push(file);
      try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    }
  } catch (error) {
    for (const file of created.reverse()) { try { fs.unlinkSync(file); } catch {} }
    throw error;
  }
  return result.report;
}

function main(args) {
  if (args.includes('--help')) {
    console.log('Usage: node tools/migrate-family.cjs INPUT.json [--output NEW.json]\nDefault: dry-run JSON report only. --output writes a new private packet, exact original, and report; never writes an app store.');
    return;
  }
  const input = args.shift();
  if (!input || input.startsWith('--')) fail('Provide INPUT.json; use --help for usage.');
  let output;
  while (args.length) {
    const arg = args.shift();
    if (arg !== '--output' || output || !args[0]) fail('Only one --output NEW.json option is supported.');
    output = args.shift();
  }
  const report = output ? writeArtifacts(input, output) : migrate(fs.readFileSync(input)).report;
  console.log(JSON.stringify({ dryRun: !output, ...report }, null, 2));
}
if (require.main === module) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error('Migration stopped: ' + error.message); process.exitCode = 1; }
}
module.exports = { migrate, writeArtifacts, hash, MAX, ADAPTER };
