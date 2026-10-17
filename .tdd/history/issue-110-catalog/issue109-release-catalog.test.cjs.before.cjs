const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const test = require('node:test');

// Issue #109 approved offline release contract: publish exact V1..V11; retain immutable V10 consumption.
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

function manifest(length = 11) {
  return {
    protocol: 'meet-me-approved-release-v1', sourceSha, ...expected,
    imageDigest: `sha256:${'b'.repeat(64)}`, releaseId: `${sourceSha}-12345-2`, archiveSha256: 'c'.repeat(64),
    schemaFingerprint: (length === 10 ? 'd' : 'e').repeat(64), guardFingerprint: '8'.repeat(64),
    migrations: Array.from({ length }, (_, i) => ({ version: String(i + 1), script: `V${i + 1}__fixture.sql`, checksum: (i + 1) * 37 })),
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
function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-109-publication-'));
  const migrations = path.join(directory, 'src/main/resources/db/migration');
  fs.mkdirSync(migrations, { recursive: true });
  for (const name of fs.readdirSync(path.join(root, 'src/main/resources/db/migration'))) {
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

test('V11 publisher derives exact eleven source migrations, Flyway checksums and source fingerprints', () => {
  const value = fixture();
  try {
    const result = publish(value);
    assert.equal(result.ok, true, 'Actual V11 source must be publishable without dropping the new migration');
    const expectedRows = Array.from({ length: 11 }, (_, i) => {
      const version = String(i + 1);
      const script = fs.readdirSync(value.migrations).find(name => name.startsWith(`V${version}__`));
      const raw = fs.readFileSync(path.join(value.migrations, script));
      return { version, script, checksum: flywayCrc(raw), sha256: hashes(raw) };
    });
    assert.deepEqual(expectedRows.map(row => row.checksum), [751905489, -717392933, -1899585498, 1674511551, -809392941, 1476091850, -273770541, 79860964, 1241531971, 40203105, -1822689491]);
    assert.deepEqual(result.result.migrations, expectedRows.map(({ sha256, ...row }) => row));
    assert.equal(result.result.schemaFingerprint, hashes(canonical(expectedRows.map(({ checksum, ...row }) => row))));
    assert.equal(result.result.guardFingerprint, hashes(canonical(guardFiles.map(name => ({ path: name, sha256: hashes(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    assert.equal(result.result.archiveSha256, hashes(fs.readFileSync(value.archive)));
    assert.equal(python('validate_manifest', [result.result, expected]).ok, true);
  } finally { value.remove(); }
});

test('publisher rejects V10-only source, duplicate versions, unknown V12 and symlinked V11', () => {
  for (const fault of ['missing', 'duplicate', 'unknown', 'symlink']) {
    const value = fixture();
    try {
      const v11 = path.join(value.migrations, 'V11__add_accounts_and_participant_ownership.sql');
      if (fault === 'missing') fs.unlinkSync(v11);
      if (fault === 'duplicate') fs.copyFileSync(v11, path.join(value.migrations, 'V11__duplicate.sql'));
      if (fault === 'unknown') fs.renameSync(v11, path.join(value.migrations, 'V12__unknown.sql'));
      if (fault === 'symlink') { fs.unlinkSync(v11); fs.symlinkSync('V1__create_core_domain.sql', v11); }
      assert.deepEqual(publish(value), { ok: false, reason: 'UNTRUSTED_RELEASE' }, fault);
    } finally { value.remove(); }
  }
});

test('manifest reader accepts immutable V10 and complete V11 only', () => {
  for (const length of [10, 11]) {
    const release = manifest(length);
    assert.deepEqual(python('validate_manifest', [release, expected]), { ok: true, result: { ...release, imageRef: `${repository}@${release.imageDigest}` } });
  }
  for (const length of [7, 9, 12]) assert.deepEqual(python('validate_manifest', [manifest(length), expected]), { ok: false, reason: 'UNTRUSTED_RELEASE' });
});

test('both complete contracts retain automatic updates without weakening exact history and fingerprints', () => {
  for (const length of [10, 11]) for (const action of ['automatic', 'preflight', 'deploy']) mode(manifest(length), ready(manifest(length)), action, false, 'AUTO_READY');
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
    for (const length of [9, 12]) denied(release, { ...ready(release), automaticContract: record(manifest(length)) }, action);
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

test('host stored record parser accepts strict V10 and V11 with synthetic read-only bytes only', () => {
  for (const length of [10, 11]) {
    const value = record(manifest(length));
    assert.deepEqual(python('_record', [value], 'host-approved-release.py', true), { ok: true, result: value });
  }
  const invalid = [record(manifest(9)), record(manifest(12))];
  for (const field of ['version', 'script', 'checksum', 'success']) {
    const value = record(manifest(11)); value.history[10][field] = { version: '12', script: 'V12__wrong.sql', checksum: true, success: false }[field]; invalid.push(value);
  }
  for (const value of invalid) assert.deepEqual(python('_record', [value], 'host-approved-release.py', true), { ok: false, reason: 'UNSAFE_HOST_RELEASE' });
});
