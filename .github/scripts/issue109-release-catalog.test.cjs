const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const test = require('node:test');

// Historical #109 V10/V11 contracts and current #107+#109 V1..V11+V13 catalog; preserve approval fences.
// No AWS, Docker, production host read, deployment, credentials or service mutation is performed.
const root = path.resolve(__dirname, '../..');
const repository = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me';
const sourceSha = 'a'.repeat(40);
const oldImage = `${repository}@sha256:${'7'.repeat(64)}`;
const expected = { buildRunId: '12345', buildRunAttempt: '2', imageRepository: repository };
const guardFiles = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh', 'scripts/deploy-release.sh',
  'scripts/rollback-release.sh', 'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py', 'pipeline/host-approved-release.py',
  'pipeline/run-approved-release.py', 'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
].sort();
const hashes = value => crypto.createHash('sha256').update(value).digest('hex');
const canonical = value => JSON.stringify(value, (_key, item) => item && !Array.isArray(item) && typeof item === 'object'
  ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b))) : item);
const copy = value => JSON.parse(JSON.stringify(value));
const v11Catalog = Array.from({ length: 11 }, (_, i) => i + 1);
const currentCatalog = [...v11Catalog, 13];

function python(name, args, module = 'approved_release.py', hostRecord = false) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-109-release-read-'));
  const fixture = path.join(directory, 'synthetic-record.json');
  fs.writeFileSync(fixture, '{}');
  const code = `import importlib.util,json,sys,pathlib\nsys.path.insert(0,str(pathlib.Path(sys.argv[1]).parent))\ns=importlib.util.spec_from_file_location('offline_contract',sys.argv[1])\nm=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)\np=json.loads(sys.stdin.read())\nif p['hostRecord']:\n m.CONTRACT=pathlib.Path(sys.argv[2])\n m._bytes=lambda *_:json.dumps(p['args'][0]).encode('utf-8')\ntry:\n result=m._record() if p['hostRecord'] else getattr(m,p['name'])(*p['args'])\n print(json.dumps({'ok':True,'result':result}))\nexcept ValueError as error:\n print(json.dumps({'ok':False,'reason':str(error)}))\n`;
  try {
    const result = cp.spawnSync('python3', ['-c', code, path.join(root, 'deploy/pipeline', module), fixture], {
      input: JSON.stringify({ name, args, hostRecord }), encoding: 'utf8', timeout: 5_000,
      env: { PATH: process.env.PATH, HOME: directory, PYTHONDONTWRITEBYTECODE: '1' },
    });
    assert.equal(result.status, 0, 'Offline contract must execute without parser/setup failures or external commands');
    assert.equal(result.stderr, '', 'Offline validation must not emit metadata diagnostics');
    return JSON.parse(result.stdout);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
}

function manifest(catalog = 11) {
  const versions = Array.isArray(catalog) ? catalog : Array.from({ length: catalog }, (_, i) => i + 1);
  return {
    protocol: 'meet-me-approved-release-v1', sourceSha, ...expected,
    imageDigest: `sha256:${'b'.repeat(64)}`, releaseId: `${sourceSha}-12345-2`, archiveSha256: 'c'.repeat(64),
    schemaFingerprint: versions.length <= 11 ? (versions.length === 10 ? 'd' : 'e').repeat(64) : hashes(canonical(versions)), guardFingerprint: '8'.repeat(64),
    migrations: versions.map(version => ({ version: String(version), script: `V${version}__fixture.sql`, checksum: version * 37 })),
    origin: { repository: 'meet-me-duo/meet-me-server', ref: 'refs/heads/main', workflow: 'CI', event: 'push', conclusion: 'success', ciRunId: '67890', ciSha: sourceSha },
  };
}
const history = release => release.migrations.map(row => ({ ...row, success: true }));
function record(release) {
  return { protocol: 'meet-me-automatic-release-v1', schemaFingerprint: release.schemaFingerprint,
    guardFingerprint: release.guardFingerprint, history: history(release), currentImage: oldImage,
    sourceSha: '9'.repeat(40), buildRunId: '100', buildRunAttempt: '1' };
}
function ready(release) {
  return { history: history(release), phase: 'READY', currentImage: oldImage, guardTrusted: true,
    automaticContract: record(release), refreshRuleStates: ['ENABLED', 'ENABLED'], pendingCommands: 0 };
}
function denied(release, facts, action = 'deploy', approved = true) {
  assert.deepEqual(python('validate_deploy_facts', [release, facts, action, approved]), { ok: false, reason: 'UNSAFE_RELEASE_STATE' });
}
function mode(release, facts, action, approved, value) {
  assert.deepEqual(python('validate_deploy_facts', [release, facts, action, approved]), { ok: true, result: { mode: value } });
}
function fixture(versions = null) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-109-publication-'));
  const migrations = path.join(directory, 'src/main/resources/db/migration');
  fs.mkdirSync(migrations, { recursive: true });
  for (const name of fs.readdirSync(path.join(root, 'src/main/resources/db/migration'))) {
    if (versions && !versions.includes(Number(name.match(/^V([1-9][0-9]*)__/)?.[1]))) continue;
    fs.copyFileSync(path.join(root, 'src/main/resources/db/migration', name), path.join(migrations, name));
  }
  for (const name of guardFiles) {
    const target = path.join(directory, 'deploy', name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(root, 'deploy', name), target);
  }
  const archive = path.join(directory, 'release.tar.gz');
  fs.writeFileSync(archive, 'synthetic offline publication archive bytes');
  return { directory, migrations, archive, remove() { fs.rmSync(directory, { recursive: true, force: true }); } };
}
const environment = { SOURCE_SHA: sourceSha, CI_SHA: sourceSha, CI_RUN_ID: '67890', BUILD_RUN_ID: '12345', BUILD_RUN_ATTEMPT: '2', ECR_REPOSITORY_URL: repository, IMAGE_DIGEST: `sha256:${'b'.repeat(64)}` };
const publish = value => python('build_manifest', [value.directory, value.archive, environment], 'publish_manifest.py');

// Independent JS byte-wise CRC32 oracle, not the publisher's Python checksum helper.
function flywayCrc(raw) {
  const bytes = Buffer.from(raw.toString('utf8').replace(/^\uFEFF/, '').replace(/\r\n|\r|\n/g, ''), 'utf8');
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) | 0;
}

test('publisher preserves historical V11 and exact current V11 plus V13 checksums and fingerprints', () => {
  for (const catalog of [v11Catalog, null]) {
    const value = fixture(catalog);
    try {
      const result = publish(value);
      assert.equal(result.ok, true, 'Both historical V11 and the complete combined source must be publishable');
      const versions = catalog ?? currentCatalog;
      const expectedRows = versions.map(versionNumber => {
        const version = String(versionNumber);
        const script = fs.readdirSync(value.migrations).find(name => name.startsWith(`V${version}__`));
        const raw = fs.readFileSync(path.join(value.migrations, script));
        return { version, script, checksum: flywayCrc(raw), sha256: hashes(raw) };
      });
      const historicalChecksums = [751905489, -717392933, -1899585498, 1674511551, -809392941, 1476091850, -273770541, 79860964, 1241531971, 40203105, -1822689491];
      assert.deepEqual(expectedRows.slice(0, 11).map(row => row.checksum), historicalChecksums);
      if (catalog === null) assert.equal(expectedRows[11].checksum, -67069369, 'V13 bytes have an independent fixed checksum');
      assert.deepEqual(result.result.migrations, expectedRows.map(({ sha256, ...row }) => row));
      assert.equal(result.result.schemaFingerprint, hashes(canonical(expectedRows.map(({ checksum, ...row }) => row))));
      assert.equal(result.result.guardFingerprint, hashes(canonical(guardFiles.map(name => ({ path: name, sha256: hashes(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
      assert.equal(result.result.archiveSha256, hashes(fs.readFileSync(value.archive)));
      assert.equal(python('validate_manifest', [result.result, expected]).ok, true);
    } finally { value.remove(); }
  }
});

test('publisher retains V10 compatibility and rejects missing required prefix duplicates malformed names and symlinks', () => {
  const legacy = fixture(Array.from({ length: 10 }, (_, i) => i + 1));
  try {
    const result = publish(legacy);
    assert.equal(result.ok, true, 'Generic publisher keeps immutable V10 source compatibility');
    assert.deepEqual(result.result.migrations.map(row => row.version), Array.from({ length: 10 }, (_, i) => String(i + 1)));
  } finally { legacy.remove(); }
  for (const fault of ['missing-prefix', 'duplicate', 'noncanonical', 'symlink']) {
    const value = fixture();
    try {
      const v11 = path.join(value.migrations, 'V11__add_accounts_and_participant_ownership.sql');
      if (fault === 'missing-prefix') fs.unlinkSync(path.join(value.migrations, 'V5__remove_meeting_duration.sql'));
      if (fault === 'duplicate') fs.copyFileSync(v11, path.join(value.migrations, 'V11__duplicate.sql'));
      if (fault === 'noncanonical') fs.renameSync(v11, path.join(value.migrations, 'V012__noncanonical.sql'));
      if (fault === 'symlink') { fs.unlinkSync(v11); fs.symlinkSync('V1__create_core_domain.sql', v11); }
      assert.deepEqual(publish(value), { ok: false, reason: 'UNTRUSTED_RELEASE' }, fault);
    } finally { value.remove(); }
  }
});

test('manifest reader accepts immutable V10 V11 and current sparse catalog while rejecting incomplete prefixes', () => {
  for (const catalog of [10, 11, 12, currentCatalog]) {
    const release = manifest(catalog);
    assert.deepEqual(python('validate_manifest', [release, expected]), { ok: true, result: { ...release, imageRef: `${repository}@${release.imageDigest}` } });
  }
  for (const length of [7, 9]) assert.deepEqual(python('validate_manifest', [manifest(length), expected]), { ok: false, reason: 'UNTRUSTED_RELEASE' });
});

test('both complete contracts retain automatic updates without weakening exact history and fingerprints', () => {
  for (const catalog of [10, 11, currentCatalog]) for (const action of ['automatic', 'preflight', 'deploy']) mode(manifest(catalog), ready(manifest(catalog)), action, false, 'AUTO_READY');
  const release = manifest(11);
  for (const key of ['schemaFingerprint', 'guardFingerprint']) {
    const facts = ready(release); facts.automaticContract[key] = '0'.repeat(64);
    for (const action of ['automatic', 'preflight']) mode(release, facts, action, false, 'TRANSITION_REQUIRED');
  }
  const changedImage = ready(release); changedImage.currentImage = `${repository}@${release.imageDigest}`;
  mode(release, changedImage, 'automatic', false, 'TRANSITION_REQUIRED');
});

test('V10 approved host requires explicit V11 transition and never silently upgrades automatically', () => {
  const release = manifest(11), facts = ready(manifest(10));
  for (const action of ['automatic', 'preflight']) mode(release, facts, action, false, 'TRANSITION_REQUIRED');
  denied(release, facts, 'deploy', false);
  denied(release, facts, 'deploy', true);
  facts.refreshRuleStates = ['DISABLED', 'DISABLED'];
  mode(release, facts, 'deploy', true, 'TRANSITION_APPROVED');
});

test('V11 first transition accepts only successful contiguous exact V7 through V11 history prefixes', () => {
  const release = manifest(11);
  const base = { ...ready(release), automaticContract: null, refreshRuleStates: ['DISABLED', 'DISABLED'] };
  for (const length of [7, 8, 9, 10, 11]) mode(release, { ...base, history: history(release).slice(0, length) }, 'deploy', true, 'TRANSITION_APPROVED');
  const invalid = [history(release).slice(0, 6), [...history(release), { version: '12', script: 'V12__fixture.sql', checksum: 1, success: true }]];
  for (const field of ['version', 'script', 'checksum', 'success']) {
    const rows = history(release); rows[7][field] = { version: '9', script: 'V8__different.sql', checksum: 999, success: false }[field]; invalid.push(rows);
  }
  for (const rows of invalid) denied(release, { ...base, history: rows });
  for (const states of [['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']]) denied(release, { ...base, refreshRuleStates: states });
  denied(release, base, 'deploy', false);
  denied(release, { ...base, pendingCommands: 1 });
});

test('V11 complete records cannot bypass failed history, checksum tampering or pending commands', () => {
  const release = manifest(11);
  for (const action of ['automatic', 'preflight', 'deploy']) {
    denied(release, { ...ready(release), pendingCommands: 1 }, action);
    const badChecksum = ready(release); badChecksum.history[10].checksum++;
    denied(release, badChecksum, action);
    const unsuccessful = ready(release); unsuccessful.history[10].success = false;
    denied(release, unsuccessful, action);
    denied(release, { ...ready(release), automaticContract: record(manifest(9)) }, action);
  }
  // A complete different catalog is a transition, not malformed metadata; never auto-approve it.
  for (const catalog of [12, currentCatalog]) {
    const different = { ...ready(release), automaticContract: record(manifest(catalog)) };
    for (const action of ['automatic', 'preflight']) mode(release, different, action, false, 'TRANSITION_REQUIRED');
    denied(release, different, 'deploy', false);
    denied(release, different, 'deploy', true);
    different.refreshRuleStates = ['DISABLED', 'DISABLED'];
    mode(release, different, 'deploy', true, 'TRANSITION_APPROVED');
  }
});

test('V11 manifest still rejects untrusted publication identities and malformed row catalogs', () => {
  const release = manifest(11), invalid = [];
  for (const field of ['repository', 'ref', 'workflow', 'event', 'conclusion', 'ciSha']) invalid.push({ ...release, origin: { ...release.origin, [field]: 'untrusted' } });
  for (const field of ['buildRunId', 'buildRunAttempt', 'imageRepository']) invalid.push({ ...release, [field]: 'untrusted' });
  const duplicate = copy(release); duplicate.migrations[10] = duplicate.migrations[9]; invalid.push(duplicate);
  const checksum = copy(release); checksum.migrations[10].checksum = true; invalid.push(checksum);
  for (const value of invalid) assert.deepEqual(python('validate_manifest', [value, expected]), { ok: false, reason: 'UNTRUSTED_RELEASE' });
});

test('host stored record parser accepts historical and current complete catalogs with synthetic read-only bytes only', () => {
  for (const catalog of [10, 11, 12, currentCatalog]) {
    const value = record(manifest(catalog));
    assert.deepEqual(python('_record', [value], 'host-approved-release.py', true), { ok: true, result: value });
  }
  const invalid = [record(manifest(9))];
  for (const field of ['version', 'script', 'checksum', 'success']) {
    const value = record(manifest(11)); value.history[10][field] = { version: '12', script: 'V12__wrong.sql', checksum: true, success: false }[field]; invalid.push(value);
  }
  for (const value of invalid) assert.deepEqual(python('_record', [value], 'host-approved-release.py', true), { ok: false, reason: 'UNSAFE_HOST_RELEASE' });
});
