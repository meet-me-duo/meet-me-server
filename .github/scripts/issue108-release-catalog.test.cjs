const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const test = require('node:test');

// Independent #108 additive V12 contract. Only pure Python readers and synthetic local files are used.
// No AWS, production path, Docker, credentials, deployment, host command or configuration mutation.
const root = path.resolve(__dirname, '../..');
const repository = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me';
const sourceSha = 'a'.repeat(40);
const expected = { buildRunId: '12345', buildRunAttempt: '2', imageRepository: repository };
const oldImage = `${repository}@sha256:${'7'.repeat(64)}`;
const guardFiles = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh', 'scripts/deploy-release.sh',
  'scripts/rollback-release.sh', 'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py', 'pipeline/host-approved-release.py',
  'pipeline/run-approved-release.py', 'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
].sort();
const oldChecksums = [751905489, -717392933, -1899585498, 1674511551, -809392941, 1476091850, -273770541, 79860964, 1241531971, 40203105, -1822689491];
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const clone = value => JSON.parse(JSON.stringify(value));
const canonical = value => JSON.stringify(value, (_key, item) => item && !Array.isArray(item) && typeof item === 'object'
  ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b))) : item);

function python(name, args, module = 'approved_release.py', hostRecord = false) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-108-catalog-read-'));
  const recordFile = path.join(directory, 'synthetic-record.json');
  fs.writeFileSync(recordFile, '{}');
  const code = `import importlib.util,json,sys,pathlib\nsys.path.insert(0,str(pathlib.Path(sys.argv[1]).parent))\ns=importlib.util.spec_from_file_location('issue108_offline_contract',sys.argv[1])\nm=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)\np=json.loads(sys.stdin.read())\nif p['hostRecord']:\n m.CONTRACT=pathlib.Path(sys.argv[2])\n m._bytes=lambda *_:json.dumps(p['args'][0]).encode('utf-8')\n m._run=lambda *_args,**_kwargs:(_ for _ in ()).throw(AssertionError('HOST_COMMAND_FORBIDDEN_IN_OFFLINE_TEST'))\ntry:\n result=m._record() if p['hostRecord'] else getattr(m,p['name'])(*p['args'])\n print(json.dumps({'ok':True,'result':result}))\nexcept ValueError as error:\n print(json.dumps({'ok':False,'reason':str(error)}))\n`;
  try {
    const response = cp.spawnSync('python3', ['-c', code, path.join(root, 'deploy/pipeline', module), recordFile], {
      input: JSON.stringify({ name, args, hostRecord }), encoding: 'utf8', timeout: 5_000,
      env: { PATH: process.env.PATH, HOME: directory, PYTHONDONTWRITEBYTECODE: '1' },
    });
    assert.equal(response.status, 0, 'Pure public contracts must run without setup errors or external commands');
    assert.equal(response.stderr, '', 'No private metadata diagnostics may escape offline validation');
    return JSON.parse(response.stdout);
  } finally { fs.rmSync(directory, { force: true, recursive: true }); }
}

function manifest(length = 12) {
  return {
    protocol: 'meet-me-approved-release-v1', sourceSha, ...expected,
    imageDigest: `sha256:${'b'.repeat(64)}`, releaseId: `${sourceSha}-12345-2`, archiveSha256: 'c'.repeat(64),
    schemaFingerprint: String(length % 10).repeat(64), guardFingerprint: '8'.repeat(64),
    migrations: Array.from({ length }, (_, i) => ({ version: String(i + 1), script: `V${i + 1}__fixture.sql`, checksum: (i + 1) * 37 })),
    origin: { repository: 'meet-me-duo/meet-me-server', ref: 'refs/heads/main', workflow: 'CI', event: 'push', conclusion: 'success', ciRunId: '67890', ciSha: sourceSha },
  };
}
const history = release => release.migrations.map(row => ({ ...row, success: true }));
const stored = release => ({
  protocol: 'meet-me-automatic-release-v1', schemaFingerprint: release.schemaFingerprint,
  guardFingerprint: release.guardFingerprint, history: history(release), currentImage: oldImage,
  sourceSha: '9'.repeat(40), buildRunId: '100', buildRunAttempt: '1',
});
const ready = release => ({
  history: history(release), phase: 'READY', currentImage: oldImage, guardTrusted: true,
  automaticContract: stored(release), refreshRuleStates: ['ENABLED', 'ENABLED'], pendingCommands: 0,
});
const outcome = (release, facts, action, approval = false) => python('validate_deploy_facts', [release, facts, action, approval]);
const denied = (release, facts, action = 'deploy', approval = true) =>
  assert.deepEqual(outcome(release, facts, action, approval), { ok: false, reason: 'UNSAFE_RELEASE_STATE' });
const mode = (release, facts, action, approval, value) =>
  assert.deepEqual(outcome(release, facts, action, approval), { ok: true, result: { mode: value } });

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-108-catalog-publication-'));
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
  fs.writeFileSync(archive, 'synthetic immutable release archive');
  return { directory, migrations, archive, remove() { fs.rmSync(directory, { force: true, recursive: true }); } };
}
const environment = { SOURCE_SHA: sourceSha, CI_SHA: sourceSha, CI_RUN_ID: '67890', BUILD_RUN_ID: '12345', BUILD_RUN_ATTEMPT: '2', ECR_REPOSITORY_URL: repository, IMAGE_DIGEST: `sha256:${'b'.repeat(64)}` };
const publish = value => python('build_manifest', [value.directory, value.archive, environment], 'publish_manifest.py');

// Independent byte-wise JS CRC32 implements Flyway's signed line-normalized UTF-8 checksum.
function crc(raw) {
  const bytes = Buffer.from(raw.toString('utf8').replace(/^\uFEFF/, '').replace(/\r\n|\r|\n/g, ''), 'utf8');
  let value = 0xffffffff;
  for (const byte of bytes) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  }
  return (value ^ 0xffffffff) | 0;
}
function sourceRows(value) {
  return Array.from({ length: 12 }, (_, i) => {
    const version = String(i + 1);
    const matches = fs.readdirSync(value.migrations).filter(name => name.startsWith(`V${version}__`));
    assert.equal(matches.length, 1, 'Independent source catalog must contain each version exactly once');
    const raw = fs.readFileSync(path.join(value.migrations, matches[0]));
    return { version, script: matches[0], checksum: crc(raw), sha256: hash(raw) };
  });
}

test('108 R01 publisher emits exactly V1 through V12 with independent Flyway checksums and fingerprints', () => {
  const value = fixture();
  try {
    const result = publish(value);
    assert.equal(result.ok, true, 'The complete current V12 source must be publishable');
    const rows = sourceRows(value);
    assert.deepEqual(rows.slice(0, 11).map(row => row.checksum), oldChecksums, 'Existing eleven migration checksums remain immutable');
    assert.equal(rows[11].script, 'V12__add_guest_reaccess.sql');
    assert.deepEqual(result.result.migrations, rows.map(({ sha256, ...row }) => row));
    assert.equal(result.result.schemaFingerprint, hash(canonical(rows.map(({ checksum, ...row }) => row))));
    assert.equal(result.result.guardFingerprint, hash(canonical(guardFiles.map(name => ({ path: name, sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    assert.equal(result.result.archiveSha256, hash(fs.readFileSync(value.archive)));
    assert.equal(python('validate_manifest', [result.result, expected]).ok, true);
  } finally { value.remove(); }
});

test('108 R02 V12 SQL byte changes update source fingerprint and Flyway checksum without changing V1 through V11', () => {
  const value = fixture();
  try {
    const first = publish(value);
    assert.equal(first.ok, true);
    fs.appendFileSync(path.join(value.migrations, 'V12__add_guest_reaccess.sql'), '\n-- independent V12 fingerprint boundary\n');
    const changed = publish(value);
    assert.equal(changed.ok, true);
    assert.deepEqual(first.result.migrations.slice(0, 11), changed.result.migrations.slice(0, 11));
    assert.notEqual(first.result.migrations[11].checksum, changed.result.migrations[11].checksum);
    assert.notEqual(first.result.schemaFingerprint, changed.result.schemaFingerprint);
    assert.equal(changed.result.migrations[11].checksum, sourceRows(value)[11].checksum);
    assert.equal(first.result.guardFingerprint, changed.result.guardFingerprint);
  } finally { value.remove(); }
});

test('108 R03 guard bytes are fingerprinted independently of the exact twelve-schema fingerprint', () => {
  const value = fixture();
  try {
    const first = publish(value);
    assert.equal(first.ok, true);
    fs.appendFileSync(path.join(value.directory, 'deploy/scripts/deploy-release.sh'), '\n# synthetic fingerprint mutation\n');
    const changed = publish(value);
    assert.equal(changed.ok, true);
    assert.notEqual(first.result.guardFingerprint, changed.result.guardFingerprint);
    assert.equal(first.result.schemaFingerprint, changed.result.schemaFingerprint);
  } finally { value.remove(); }
});

test('108 R04 publisher rejects missing V12 missing predecessors duplicate versions and unknown V13', () => {
  for (const fault of ['missing-v12', 'missing-v11', 'duplicate-v12', 'unknown-v13']) {
    const value = fixture();
    try {
      const v12 = path.join(value.migrations, 'V12__add_guest_reaccess.sql');
      if (fault === 'missing-v12') fs.unlinkSync(v12);
      if (fault === 'missing-v11') fs.unlinkSync(path.join(value.migrations, 'V11__add_accounts_and_participant_ownership.sql'));
      if (fault === 'duplicate-v12') fs.copyFileSync(v12, path.join(value.migrations, 'V12__duplicate.sql'));
      if (fault === 'unknown-v13') fs.copyFileSync(v12, path.join(value.migrations, 'V13__unknown.sql'));
      assert.deepEqual(publish(value), { ok: false, reason: 'UNTRUSTED_RELEASE' }, fault);
    } finally { value.remove(); }
  }
});

test('108 R05 publisher rejects symlinked V12 guard files and publication archive', () => {
  for (const fault of ['v12', 'guard', 'archive']) {
    const value = fixture();
    try {
      if (fault === 'v12') {
        const target = path.join(value.migrations, 'V12__add_guest_reaccess.sql');
        fs.unlinkSync(target); fs.symlinkSync('V1__create_core_domain.sql', target);
      }
      if (fault === 'guard') {
        const target = path.join(value.directory, 'deploy/scripts/deploy-release.sh');
        fs.unlinkSync(target); fs.symlinkSync('rollback-release.sh', target);
      }
      if (fault === 'archive') { fs.unlinkSync(value.archive); fs.symlinkSync(path.join(value.migrations, 'V1__create_core_domain.sql'), value.archive); }
      assert.deepEqual(publish(value), { ok: false, reason: 'UNTRUSTED_RELEASE' }, fault);
    } finally { value.remove(); }
  }
});

test('108 R06 manifest reader accepts exact V10 V11 and V12 while rejecting unsupported or malformed catalogs', () => {
  for (const length of [10, 11, 12]) {
    const release = manifest(length);
    assert.deepEqual(python('validate_manifest', [release, expected]), { ok: true, result: { ...release, imageRef: `${repository}@${release.imageDigest}` } });
  }
  const invalid = [manifest(7), manifest(9), manifest(13)];
  for (const field of ['version', 'script', 'checksum']) {
    const release = manifest();
    release.migrations[11][field] = { version: '13', script: 'V12__../../unexpected.sql', checksum: true }[field];
    invalid.push(release);
  }
  const duplicate = manifest(); duplicate.migrations[11] = duplicate.migrations[10]; invalid.push(duplicate);
  const gap = manifest(); gap.migrations.splice(7, 1); invalid.push(gap);
  for (const release of invalid) assert.deepEqual(python('validate_manifest', [release, expected]), { ok: false, reason: 'UNTRUSTED_RELEASE' });
});

test('108 R07 host stored-record reader accepts V12 and retains V10 V11 without reading host state', () => {
  for (const length of [10, 11, 12]) {
    const record = stored(manifest(length));
    assert.deepEqual(python('_record', [record], 'host-approved-release.py', true), { ok: true, result: record });
  }
  const invalid = [stored(manifest(9)), stored(manifest(13))];
  for (const field of ['version', 'script', 'checksum', 'success']) {
    const record = stored(manifest());
    record.history[11][field] = { version: '13', script: 'V11__wrong.sql', checksum: true, success: false }[field];
    invalid.push(record);
  }
  for (const record of invalid) assert.deepEqual(python('_record', [record], 'host-approved-release.py', true), { ok: false, reason: 'UNSAFE_HOST_RELEASE' });
});

test('108 R08 unchanged complete V12 contract permits AUTO_READY and preserves V10 V11 behavior', () => {
  for (const length of [10, 11, 12]) for (const action of ['automatic', 'preflight', 'deploy']) {
    const release = manifest(length);
    mode(release, ready(release), action, false, 'AUTO_READY');
  }
});

test('108 R09 V11 to V12 never silently transitions and requires explicit drained disabled-rule approval', () => {
  const release = manifest(12), facts = ready(manifest(11));
  for (const action of ['automatic', 'preflight']) mode(release, facts, action, false, 'TRANSITION_REQUIRED');
  denied(release, facts, 'deploy', false);
  denied(release, facts, 'deploy', true);
  facts.refreshRuleStates = ['DISABLED', 'DISABLED'];
  mode(release, facts, 'deploy', true, 'TRANSITION_APPROVED');
  for (const states of [['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']]) denied(release, { ...facts, refreshRuleStates: states });
  denied(release, { ...facts, pendingCommands: 1 });
});

test('108 R10 absent or mismatched automatic contract demands transition and cannot become automatic readiness', () => {
  const release = manifest();
  const variants = [{ ...ready(release), automaticContract: null }];
  for (const field of ['schemaFingerprint', 'guardFingerprint']) {
    const facts = ready(release); facts.automaticContract[field] = '0'.repeat(64); variants.push(facts);
  }
  for (const facts of variants) {
    for (const action of ['automatic', 'preflight']) mode(release, facts, action, false, 'TRANSITION_REQUIRED');
    denied(release, facts, 'deploy', false);
    denied(release, facts, 'deploy', true);
    mode(release, { ...facts, refreshRuleStates: ['DISABLED', 'DISABLED'] }, 'deploy', true, 'TRANSITION_APPROVED');
  }
});

test('108 R11 explicit V12 transition retains successful contiguous V7 through V12 prefix policy', () => {
  const release = manifest();
  const base = { ...ready(release), automaticContract: null, refreshRuleStates: ['DISABLED', 'DISABLED'] };
  for (const length of [7, 8, 9, 10, 11, 12]) {
    mode(release, { ...base, history: history(release).slice(0, length) }, 'deploy', true, 'TRANSITION_APPROVED');
  }
  denied(release, base, 'deploy', false);
  denied(release, base, 'deploy', 'true');
  denied(release, { ...base, phase: 'UNKNOWN' });
  denied(release, { ...base, guardTrusted: false });
});

test('108 R12 failed history gaps unknown V13 wrong scripts and wrong checksums remain fail closed for every action', () => {
  const release = manifest();
  const badHistories = [history(release).slice(0, 6), history(manifest(13))];
  const gap = history(release); gap.splice(8, 1); badHistories.push(gap);
  for (const field of ['version', 'script', 'checksum', 'success']) {
    const rows = history(release); rows[11][field] = { version: '13', script: 'V12__tampered.sql', checksum: 1, success: false }[field];
    badHistories.push(rows);
  }
  for (const rows of badHistories) for (const action of ['automatic', 'preflight', 'deploy']) {
    denied(release, { ...ready(release), history: rows }, action);
  }
});

test('108 R13 READY is bound to exact observed image history and both fingerprints', () => {
  const release = manifest();
  const variants = [{ ...ready(release), phase: 'V8_STARTED' }, { ...ready(release), currentImage: `${repository}@${release.imageDigest}` }];
  for (const field of ['schemaFingerprint', 'guardFingerprint']) {
    const facts = ready(release); facts.automaticContract[field] = '0'.repeat(64); variants.push(facts);
  }
  const shortHistory = ready(release); shortHistory.history = history(release).slice(0, 11); variants.push(shortHistory);
  for (const facts of variants) for (const action of ['automatic', 'preflight']) mode(release, facts, action, false, 'TRANSITION_REQUIRED');
});

test('108 R14 new V12 acceptance never loosens publication identity fingerprint shape or successful-history types', () => {
  const release = manifest(), invalid = [];
  for (const field of ['repository', 'ref', 'workflow', 'event', 'conclusion', 'ciSha']) invalid.push({ ...release, origin: { ...release.origin, [field]: 'untrusted' } });
  for (const field of ['buildRunId', 'buildRunAttempt', 'imageRepository', 'schemaFingerprint', 'guardFingerprint']) invalid.push({ ...release, [field]: 'untrusted' });
  for (const value of invalid) assert.deepEqual(python('validate_manifest', [value, expected]), { ok: false, reason: 'UNTRUSTED_RELEASE' });
  for (const action of ['automatic', 'preflight', 'deploy']) denied(release, { ...ready(release), pendingCommands: 1 }, action);
  for (const field of ['checksum', 'success']) {
    const facts = ready(release); facts.automaticContract.history[11][field] = { checksum: true, success: 'true' }[field];
    denied(release, facts);
  }
});
