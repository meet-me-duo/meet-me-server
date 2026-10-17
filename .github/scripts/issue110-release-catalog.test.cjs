const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const test = require('node:test');

// Independent issue #110 contract tests. These only import pure Python validators with synthetic
// bytes and temporary local fixtures. They never execute a host command, Docker, AWS or deployment.
// V12/V13 are reserved by other issues: neither a placeholder nor a contiguous V1..V15 catalog is valid.
const root = path.resolve(__dirname, '../..');
const repository = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me';
const sourceSha = 'a'.repeat(40);
const expected = { buildRunId: '12345', buildRunAttempt: '2', imageRepository: repository };
const oldImage = `${repository}@sha256:${'7'.repeat(64)}`;
const approvedSources = [
  [1, 'create_core_domain', 'f840adc70a59de4f7085f419f4063c34f5ed96e87a54b2aa4f052c15df023515'],
  [2, 'add_anonymous_room_lifecycle', '75535992de90d7eedc1700487503418a300df6ca668eb18d517646517ebd40e7'],
  [3, 'add_submission_gemini_pipeline', 'a1d7723e2461dbc6e483040f24fe9df8efc5d088b9ddc00ffaf1cdc842f12371'],
  [4, 'add_matching_results', '14f90ca0642a4ee02242d334ebe542503907b509ee1a4fdfcc45f6c33905fe9d'],
  [5, 'remove_meeting_duration', '7127e1ccc1284865cc3b0ea38be3582a1870f7d3ec05e3700d4577d1084c3146'],
  [6, 'add_reliable_outbox_processing', '526c1cca68d23c1ee9fe4fad6c7cf0937eb53df5e306ee75b327223855f8af7a'],
  [7, 'resume_delayed_matching_from_structuring', 'd252715c33435f88498e8e0131ea5b35e6e081e63fdc91fab67b7f1b6235c747'],
  [8, 'add_input_revision_rounds', '04ac022f09473e4d95128c765b2e274b7d1d1abb0fee4b722f56119638d22a92'],
  [9, 'add_time_recommendations', 'a6fc046611fb3cafa96d51f87de09687b3fdecc403988d211b7d86c4fad0585d'],
  [10, 'add_bounded_analysis_invocations', '694940de9100b58bc4a1ca2889d5d44fbd86221dead6805082b2c7999ee002ab'],
  [11, 'add_accounts_and_participant_ownership', '0431b2c18a5f03c341d3d4c4d1f3456e169db610cb5bd92e3681978623b3b92c'],
  [14, 'add_operator_access_audits', 'cddce45ef10fc2eac99cb739a1b631f41634b2cd06905e19919692844ca40196'],
  [15, 'add_parser_transport_observations', 'fcbcd040cb232ca400cbfb24b944f8289428032daf6ce386c35dbc119f9b9f98'],
].map(([version, suffix, sha256]) => ({ version: String(version), script: `V${version}__${suffix}.sql`, sha256 }));
const guardFiles = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh', 'scripts/deploy-release.sh',
  'scripts/rollback-release.sh', 'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py', 'pipeline/host-approved-release.py',
  'pipeline/run-approved-release.py', 'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
].sort();
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const canonical = value => JSON.stringify(value, (_key, item) => item && !Array.isArray(item) && typeof item === 'object'
  ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b))) : item);
const clone = value => JSON.parse(JSON.stringify(value));
const sourceBytes = row => fs.readFileSync(path.join(root, 'src/main/resources/db/migration', row.script));
// Independent JS byte-wise CRC32, with Flyway's UTF-8 BOM and readLine newline semantics.
function crc(raw) {
  let result = 0xffffffff;
  for (const byte of Buffer.from(raw.toString('utf8').replace(/^\uFEFF/, '').replace(/\r\n|\r|\n/g, ''), 'utf8')) {
    result ^= byte;
    for (let bit = 0; bit < 8; bit++) result = (result >>> 1) ^ ((result & 1) ? 0xedb88320 : 0);
  }
  return (result ^ 0xffffffff) | 0;
}
const rows = (integrated = true) => approvedSources.filter(row => integrated || row.version !== '11')
  .map(({ version, script }) => ({ version, script, checksum: crc(sourceBytes({ script })) }));
const history = release => release.migrations.map(row => ({ ...row, success: true }));
function manifest(integrated = true) {
  return { protocol: 'meet-me-approved-release-v1', sourceSha, ...expected,
    imageDigest: `sha256:${'b'.repeat(64)}`, releaseId: `${sourceSha}-12345-2`, archiveSha256: 'c'.repeat(64),
    schemaFingerprint: 'd'.repeat(64), guardFingerprint: 'e'.repeat(64), migrations: rows(integrated),
    origin: { repository: 'meet-me-duo/meet-me-server', ref: 'refs/heads/main', workflow: 'CI', event: 'push',
      conclusion: 'success', ciRunId: '67890', ciSha: sourceSha } };
}
function legacy(version) {
  const value = manifest(); value.migrations = value.migrations.filter(row => Number(row.version) <= version);
  value.schemaFingerprint = String(version === 10 ? 1 : 2).repeat(64); return value;
}
function record(release) {
  return { protocol: 'meet-me-automatic-release-v1', schemaFingerprint: release.schemaFingerprint,
    guardFingerprint: release.guardFingerprint, history: history(release), currentImage: oldImage,
    sourceSha: '9'.repeat(40), buildRunId: '100', buildRunAttempt: '1' };
}
function ready(release) {
  return { history: history(release), phase: 'READY', currentImage: oldImage, guardTrusted: true,
    automaticContract: record(release), refreshRuleStates: ['ENABLED', 'ENABLED'], pendingCommands: 0 };
}
function python(name, args, module = 'approved_release.py', hostRecord = false) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-110-offline-'));
  const fixture = path.join(directory, 'synthetic-record.json'); fs.writeFileSync(fixture, '{}');
  // Windows has no fcntl. A stub only permits importing the Unix-only host module for _record;
  // no callable lock or host function is supplied, so this cannot execute host operations.
  const code = `import importlib.util,json,sys,pathlib,os,types\nif sys.platform=='win32' and sys.argv[1].endswith('host-approved-release.py'):sys.modules['fcntl']=types.ModuleType('fcntl')\nsys.path.insert(0,str(pathlib.Path(sys.argv[1]).parent))\ns=importlib.util.spec_from_file_location('offline_contract',sys.argv[1])\nm=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)\np=json.loads(sys.stdin.read())\nif p['hostRecord']:\n m.CONTRACT=pathlib.Path(sys.argv[2])\n m._bytes=lambda *_:json.dumps(p['args'][0]).encode('utf-8')\ntry:\n result=m._record() if p['hostRecord'] else getattr(m,p['name'])(*p['args'])\n print(json.dumps({'ok':True,'result':result}))\nexcept ValueError as error:\n print(json.dumps({'ok':False,'reason':str(error)}))\n`;
  try {
    const executable = process.env.PYTHON_EXECUTABLE ?? 'python3';
    const result = cp.spawnSync(executable, ['-c', code, path.join(root, 'deploy/pipeline', module), fixture], {
      input: JSON.stringify({ name, args, hostRecord }), encoding: 'utf8', timeout: 5_000,
      env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, HOME: directory, PYTHONDONTWRITEBYTECODE: '1' },
    });
    assert.equal(result.status, 0, 'Pure offline validator must execute without setup errors');
    assert.equal(result.stderr, '', 'Pure offline validator must not disclose diagnostics');
    return JSON.parse(result.stdout);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
}
const validate = release => python('validate_manifest', [release, expected]);
const judge = (release, facts, action, approved = false) => python('validate_deploy_facts', [release, facts, action, approved]);
const mode = value => ({ ok: true, result: { mode: value } });
const denied = { ok: false, reason: 'UNSAFE_RELEASE_STATE' };
const untrusted = { ok: false, reason: 'UNTRUSTED_RELEASE' };
function fixture(integrated = true) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-110-publication-'));
  const migrations = path.join(directory, 'src/main/resources/db/migration'); fs.mkdirSync(migrations, { recursive: true });
  for (const row of approvedSources.filter(row => integrated || row.version !== '11')) fs.writeFileSync(path.join(migrations, row.script), sourceBytes(row));
  for (const name of guardFiles) {
    const target = path.join(directory, 'deploy', name); fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(root, 'deploy', name), target);
  }
  const archive = path.join(directory, 'release.tar.gz'); fs.writeFileSync(archive, 'synthetic offline release archive');
  return { directory, migrations, archive, remove() { fs.rmSync(directory, { recursive: true, force: true }); } };
}
const environment = { SOURCE_SHA: sourceSha, CI_SHA: sourceSha, CI_RUN_ID: '67890', BUILD_RUN_ID: '12345',
  BUILD_RUN_ATTEMPT: '2', ECR_REPOSITORY_URL: repository, IMAGE_DIGEST: `sha256:${'b'.repeat(64)}` };
const publish = value => python('build_manifest', [value.directory, value.archive, environment], 'publish_manifest.py');

test('110 immutable actual109 V1..V11 and approved V14/V15 source bytes remain exact', () => {
  const actual = fs.readdirSync(path.join(root, 'src/main/resources/db/migration')).sort();
  assert.deepEqual(actual, approvedSources.map(row => row.script).sort());
  // Git canonical LF pins preserve immutable source across autocrlf worktrees; release hashes below
  // intentionally bind the actual raw source bytes used by that publication.
  for (const row of approvedSources) assert.equal(hash(sourceBytes(row).toString('utf8').replace(/\r\n/g, '\n')), row.sha256, row.script);
  assert.deepEqual(rows().slice(0, 11).map(row => row.checksum), [751905489, -717392933, -1899585498,
    1674511551, -809392941, 1476091850, -273770541, 79860964, 1241531971, 40203105, -1822689491]);
  assert.deepEqual(rows().slice(-2).map(row => row.checksum), [1718407698, -1216068646]);
});

test('110 publisher derives exact standalone and actual109 sparse catalogs with independent fingerprints', () => {
  for (const integrated of [false, true]) {
    const value = fixture(integrated);
    try {
      const result = publish(value); assert.equal(result.ok, true, 'Approved V14/V15 source catalog must publish');
      const sourceCatalog = approvedSources.filter(row => integrated || row.version !== '11')
        .map(({ version, script }) => ({ version, script, sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }));
      assert.deepEqual(result.result.migrations, rows(integrated));
      assert.equal(result.result.schemaFingerprint, hash(canonical(sourceCatalog)));
      assert.equal(result.result.guardFingerprint, hash(canonical(guardFiles.map(name => ({ path: name,
        sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
      assert.equal(result.result.archiveSha256, hash(fs.readFileSync(value.archive)));
      assert.equal(validate(result.result).ok, true);
    } finally { value.remove(); }
  }
});

test('110 source catalog fails closed for missing members, duplicate versions, wrong names and reserved versions', () => {
  for (const fault of ['missing14', 'missing15', 'duplicate14', 'renamed1', 'renamed11', 'renamed14', 'renamed15',
    'reserved12', 'reserved13', 'unknown16', 'extraText', 'directory14']) {
    const value = fixture();
    try {
      const p = version => path.join(value.migrations, approvedSources.find(row => row.version === String(version)).script);
      if (fault.startsWith('missing')) fs.unlinkSync(p(Number(fault.slice(7))));
      if (fault === 'duplicate14') fs.copyFileSync(p(14), path.join(value.migrations, 'V14__duplicate.sql'));
      if (fault.startsWith('renamed')) { const version = Number(fault.slice(7)); fs.renameSync(p(version), path.join(value.migrations, `V${version}__wrong_name.sql`)); }
      if (fault === 'reserved12') fs.writeFileSync(path.join(value.migrations, 'V12__reserved.sql'), '-- forbidden placeholder');
      if (fault === 'reserved13') fs.writeFileSync(path.join(value.migrations, 'V13__reserved.sql'), '-- forbidden placeholder');
      if (fault === 'unknown16') fs.writeFileSync(path.join(value.migrations, 'V16__unknown.sql'), '-- unapproved source');
      if (fault === 'extraText') fs.writeFileSync(path.join(value.migrations, 'unapproved.txt'), 'unapproved source');
      if (fault === 'directory14') { fs.unlinkSync(p(14)); fs.mkdirSync(p(14)); }
      assert.deepEqual(publish(value), untrusted, fault);
    } finally { value.remove(); }
  }
});

test('110 publisher rejects migration and guard symlinks rather than fingerprinting their targets', () => {
  for (const target of ['migration', 'guard']) {
    const value = fixture();
    try {
      const name = target === 'migration' ? path.join(value.migrations, 'V14__add_operator_access_audits.sql')
        : path.join(value.directory, 'deploy/pipeline/approved_release.py');
      const destination = path.join(value.directory, 'synthetic-target'); fs.copyFileSync(name, destination);
      fs.unlinkSync(name); fs.symlinkSync(destination, name);
      assert.deepEqual(publish(value), untrusted, target);
    } finally { value.remove(); }
  }
});

for (const target of ['migration-directory', 'source-ancestor', 'guard-ancestor', 'repository-root']) {
  test(`110 publisher rejects a symlinked ${target} even when every leaf file is ordinary and exact`, () => {
    const value = fixture();
    try {
      let publication = value;
      if (target === 'repository-root') {
        // Keep both the real tree and its alias inside the original temporary fixture. Cleanup
        // only removes that original fixture; it never recursively deletes an external target.
        const actual = path.join(value.directory, 'actual-repository'); fs.mkdirSync(actual);
        for (const entry of fs.readdirSync(value.directory)) {
          if (entry !== 'actual-repository') fs.renameSync(path.join(value.directory, entry), path.join(actual, entry));
        }
        const alias = path.join(value.directory, 'linked-repository'); fs.symlinkSync(actual, alias, 'dir');
        publication = { ...value, directory: alias, archive: path.join(actual, 'release.tar.gz') };
      } else {
        const relative = { 'migration-directory': 'src/main/resources/db/migration',
          'source-ancestor': 'src/main', 'guard-ancestor': 'deploy/pipeline' }[target];
        const original = path.join(value.directory, relative), actual = `${original}-actual`;
        fs.renameSync(original, actual); fs.symlinkSync(actual, original, 'dir');
      }
      assert.deepEqual(publish(publication), untrusted,
        'Exact ordinary leaf bytes do not authorize following source or guard directory links');
    } finally { value.remove(); }
  });
}

test('110 byte changes independently alter schema, guard and archive fingerprints and block automatic reuse', () => {
  const value = fixture();
  try {
    const original = publish(value); assert.equal(original.ok, true);
    const filename = path.join(value.migrations, 'V15__add_parser_transport_observations.sql');
    const raw = fs.readFileSync(filename); fs.appendFileSync(filename, '\n-- synthetic reviewed-source change\n');
    const schemaChanged = publish(value); assert.equal(schemaChanged.ok, true);
    assert.notEqual(schemaChanged.result.schemaFingerprint, original.result.schemaFingerprint);
    assert.notEqual(schemaChanged.result.migrations.at(-1).checksum, original.result.migrations.at(-1).checksum);
    fs.writeFileSync(filename, raw);
    fs.appendFileSync(path.join(value.directory, 'deploy/pipeline/publish_manifest.py'), '\n# synthetic guard change\n');
    const guardChanged = publish(value); assert.equal(guardChanged.ok, true);
    assert.equal(guardChanged.result.schemaFingerprint, original.result.schemaFingerprint);
    assert.notEqual(guardChanged.result.guardFingerprint, original.result.guardFingerprint);
    for (const changed of [schemaChanged.result, guardChanged.result]) {
      // Keep observed history exact to isolate the fingerprint approval boundary.
      const facts = ready(changed); facts.automaticContract.schemaFingerprint = original.result.schemaFingerprint;
      facts.automaticContract.guardFingerprint = original.result.guardFingerprint;
      for (const action of ['automatic', 'preflight']) assert.deepEqual(judge(changed, facts, action), mode('TRANSITION_REQUIRED'));
      assert.deepEqual(judge(changed, facts, 'deploy'), denied);
    }
    fs.appendFileSync(value.archive, ' changed archive bytes');
    const archiveChanged = publish(value); assert.equal(archiveChanged.ok, true);
    assert.notEqual(archiveChanged.result.archiveSha256, guardChanged.result.archiveSha256);
  } finally { value.remove(); }
});

test('110 readers retain immutable V10/V11 and accept only complete approved sparse new catalogs', () => {
  for (const release of [legacy(10), legacy(11), manifest(false), manifest(true)]) {
    assert.equal(validate(release).ok, true);
    assert.deepEqual(python('_record', [record(release)], 'host-approved-release.py', true), { ok: true, result: record(release) });
  }
  const invalid = [];
  for (const integrated of [false, true]) {
    const release = manifest(integrated);
    for (const fault of ['missing14', 'missing15', 'duplicate', 'reversed', 'reserved12', 'reserved13', 'unknown16', 'wrongName', 'boolChecksum']) {
      const value = clone(release);
      if (fault.startsWith('missing')) value.migrations = value.migrations.filter(row => row.version !== fault.slice(7));
      if (fault === 'duplicate') value.migrations[value.migrations.length - 1] = clone(value.migrations.at(-2));
      if (fault === 'reversed') value.migrations.reverse();
      if (fault.startsWith('reserved')) value.migrations.at(-1).version = fault.slice(8);
      if (fault === 'unknown16') value.migrations.at(-1).version = '16';
      if (fault === 'wrongName') value.migrations.at(-1).script = 'V15__arbitrary.sql';
      if (fault === 'boolChecksum') value.migrations.at(-1).checksum = true;
      invalid.push(value);
    }
  }
  for (const value of invalid) {
    assert.deepEqual(validate(value), untrusted);
    assert.deepEqual(python('_record', [record(value)], 'host-approved-release.py', true), { ok: false, reason: 'UNSAFE_HOST_RELEASE' });
  }
});

test('110 exact complete records retain automatic updates for both sparse catalogs', () => {
  for (const integrated of [false, true]) for (const action of ['automatic', 'preflight', 'deploy']) {
    const release = manifest(integrated); assert.deepEqual(judge(release, ready(release), action), mode('AUTO_READY'));
  }
});

test('110 legacy V10/V11 to integrated V14/V15 requires explicit approval and both automation rules paused', () => {
  const release = manifest();
  for (const oldVersion of [10, 11]) {
    const facts = ready(legacy(oldVersion));
    for (const action of ['automatic', 'preflight']) assert.deepEqual(judge(release, facts, action), mode('TRANSITION_REQUIRED'));
    assert.deepEqual(judge(release, facts, 'deploy'), denied);
    assert.deepEqual(judge(release, facts, 'deploy', true), denied);
    for (const states of [['DISABLED', 'ENABLED'], ['ENABLED', 'DISABLED']])
      assert.deepEqual(judge(release, { ...facts, refreshRuleStates: states }, 'deploy', true), denied);
    assert.deepEqual(judge(release, { ...facts, refreshRuleStates: ['DISABLED', 'DISABLED'] }, 'deploy', true), mode('TRANSITION_APPROVED'));
  }
});

test('110 first transition accepts only exact successful prefixes including the V14 to V15 step', () => {
  for (const integrated of [false, true]) {
    const release = manifest(integrated);
    const facts = { ...ready(release), automaticContract: null, refreshRuleStates: ['DISABLED', 'DISABLED'] };
    for (let count = 7; count <= release.migrations.length; count++) {
      const candidate = { ...facts, history: history(release).slice(0, count) };
      assert.deepEqual(judge(release, candidate, 'deploy', true), mode('TRANSITION_APPROVED'));
      assert.deepEqual(judge(release, candidate, 'deploy'), denied);
    }
    const invalid = [history(release).slice(0, 6), history(release).filter(row => row.version !== '14')];
    for (const key of ['version', 'script', 'checksum', 'success']) {
      const changed = history(release); changed.at(-1)[key] = { version: '16', script: 'V15__tampered.sql', checksum: 123, success: false }[key]; invalid.push(changed);
    }
    for (const observed of invalid) for (const action of ['automatic', 'preflight', 'deploy'])
      assert.deepEqual(judge(release, { ...facts, history: observed }, action, true), denied);
  }
});

test('110 never removes V11 or reorders an already applied sparse history during integration', () => {
  const standalone = manifest(false), integrated = manifest();
  assert.deepEqual(judge(standalone, { ...ready(legacy(11)), refreshRuleStates: ['DISABLED', 'DISABLED'] }, 'deploy', true), denied);
  // V11 cannot be inserted retroactively after a host already applied V14/V15 without it.
  assert.deepEqual(judge(integrated, { ...ready(standalone), refreshRuleStates: ['DISABLED', 'DISABLED'] }, 'deploy', true), denied);
});

test('110 approval cannot bypass history tampering, failed V14/V15, unknown rows or pending work', () => {
  const release = manifest();
  const mutations = [];
  for (const version of ['14', '15']) for (const key of ['checksum', 'script', 'success']) {
    const facts = ready(release), row = facts.history.find(item => item.version === version);
    row[key] = { checksum: row.checksum + 1, script: `V${version}__tampered.sql`, success: false }[key]; mutations.push(facts);
  }
  const reserved = ready(release); reserved.history.push({ version: '12', script: 'V12__reserved.sql', checksum: 1, success: true }); mutations.push(reserved);
  const pending = ready(release); pending.pendingCommands = 1; mutations.push(pending);
  const failedRecord = ready(release); failedRecord.automaticContract.history.at(-1).success = false; mutations.push(failedRecord);
  const malformedRecord = ready(release); malformedRecord.automaticContract.history.splice(-2, 1); mutations.push(malformedRecord);
  for (const facts of mutations) for (const action of ['automatic', 'preflight', 'deploy'])
    assert.deepEqual(judge(release, { ...facts, refreshRuleStates: ['DISABLED', 'DISABLED'] }, action, true), denied);
});

test('110 changing either fingerprint requires a new explicit transition even with exact successful history', () => {
  for (const field of ['schemaFingerprint', 'guardFingerprint']) {
    const release = manifest(), facts = ready(release); facts.automaticContract[field] = '0'.repeat(64);
    for (const action of ['automatic', 'preflight']) assert.deepEqual(judge(release, facts, action), mode('TRANSITION_REQUIRED'));
    assert.deepEqual(judge(release, facts, 'deploy'), denied);
    assert.deepEqual(judge(release, { ...facts, refreshRuleStates: ['DISABLED', 'DISABLED'] }, 'deploy', true), mode('TRANSITION_APPROVED'));
  }
});

test('110 CI keeps the new sparse-catalog boundary in the committed quality gate', () => {
  const workflow = fs.readFileSync(path.join(root, '.github/workflows/ci.yml'), 'utf8');
  assert.match(workflow, /node --test[^\r\n]*issue110-release-catalog\.test\.cjs/);
});
