const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc, fixture, publish,
  manifest, history, record, ready, validate, readRecord, judge, mode, denied, untrusted } = f;

// Independent frozen public contract. Migration17 and approval-file17 are different inventories.
const scripts = [
  'V1__create_core_domain.sql', 'V2__add_anonymous_room_lifecycle.sql',
  'V3__add_submission_gemini_pipeline.sql', 'V4__add_matching_results.sql',
  'V5__remove_meeting_duration.sql', 'V6__add_reliable_outbox_processing.sql',
  'V7__resume_delayed_matching_from_structuring.sql', 'V8__add_input_revision_rounds.sql',
  'V9__add_time_recommendations.sql', 'V10__add_bounded_analysis_invocations.sql',
  'V11__add_accounts_and_participant_ownership.sql', 'V12__add_guest_reaccess.sql',
  'V13__add_submission_clarifications.sql', 'V14__add_operator_access_audits.sql',
  'V15__add_parser_transport_observations.sql', 'V16__add_pre_submission_checks.sql',
  'V17__add_durable_ai_room_budgets.sql',
];
const oldCatalogs = [range(10), range(11), range(12), [...range(11), 13],
  [...range(10), 14, 15], [...range(11), 14, 15], range(16)];
const catalogs = [...oldCatalogs, range(17)];
const approvedPaths = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh',
  'scripts/deploy-release.sh', 'scripts/rollback-release.sh',
  'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py',
  'pipeline/host-approved-release.py', 'pipeline/run-approved-release.py',
  'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
  'runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml',
].sort();
const currentApprovedPaths = [...approvedPaths, 'runtime-ai-policy.json'].sort();
const currentExecutionPaths = [...currentApprovedPaths, 'pipeline/release_completion.py', 'pipeline/transition_orchestrator.py',
  'pipeline/approval_gate.py', 'pipeline/release_pipeline.py', 'transition-approval.json'].sort();
const originalSqlHashes = [
  'f840adc70a59de4f7085f419f4063c34f5ed96e87a54b2aa4f052c15df023515',
  '75535992de90d7eedc1700487503418a300df6ca668eb18d517646517ebd40e7',
  'a1d7723e2461dbc6e483040f24fe9df8efc5d088b9ddc00ffaf1cdc842f12371',
  '14f90ca0642a4ee02242d334ebe542503907b509ee1a4fdfcc45f6c33905fe9d',
  '7127e1ccc1284865cc3b0ea38be3582a1870f7d3ec05e3700d4577d1084c3146',
  '526c1cca68d23c1ee9fe4fad6c7cf0937eb53df5e306ee75b327223855f8af7a',
  'd252715c33435f88498e8e0131ea5b35e6e081e63fdc91fab67b7f1b6235c747',
  '04ac022f09473e4d95128c765b2e274b7d1d1abb0fee4b722f56119638d22a92',
  'a6fc046611fb3cafa96d51f87de09687b3fdecc403988d211b7d86c4fad0585d',
  '694940de9100b58bc4a1ca2889d5d44fbd86221dead6805082b2c7999ee002ab',
  '0431b2c18a5f03c341d3d4c4d1f3456e169db610cb5bd92e3681978623b3b92c',
  'fce24fe4bb215ef2d40d0b2eddb2efd1fc98d57162681d5050236824a3cd11da',
  'b6ea047b7aa87486941b06d5cfbd4873449821ba6d684a8f52946053aa53e3ff',
  'cddce45ef10fc2eac99cb739a1b631f41634b2cd06905e19919692844ca40196',
  'fcbcd040cb232ca400cbfb24b944f8289428032daf6ce386c35dbc119f9b9f98',
  '65dd8b5831ced11f62bf00eb21fc5eb61bc34356c7d99da2228b8fdd80d8358e',
];
function sourceCatalog(value) {
  return scripts.map((script, i) => ({ version: String(i + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }));
}
function packageRelease(value, versions = range(17)) {
  const release = manifest(versions);
  release.migrations = versions.map(version => ({ version: String(version), script: scripts[version - 1],
    checksum: crc(fs.readFileSync(path.join(value.migrations, scripts[version - 1]))) }));
  release.schemaFingerprint = hash(canonical(versions.map(version => ({ version: String(version), script: scripts[version - 1],
    sha256: hash(fs.readFileSync(path.join(value.migrations, scripts[version - 1]))) }))));
  release.guardFingerprint = hash(canonical(approvedPaths.map(name => ({ path: name,
    sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) }))));
  const code = String.raw`import pathlib,sys,tarfile
root=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2]);names=sys.argv[3:]
def helper_mode(info):
 if info.isfile() and info.name in ('scripts/render-runtime-env.sh','scripts/export-certificate.sh'):info.mode=0o755
 return info
with tarfile.open(dest,'w:gz') as a:
 for item in sorted((root/'deploy').rglob('*')):
  if item.is_file():a.add(item,arcname=str(item.relative_to(root/'deploy')),recursive=False,filter=helper_mode)
 for name in names:a.add(root/'src/main/resources/db/migration'/name,arcname='migrations/'+name,recursive=False)
`;
  const probe = path.join(value.directory, 'deploy/preflight/compiled');fs.mkdirSync(probe, { recursive: true });
  fs.writeFileSync(path.join(probe, 'ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  fs.writeFileSync(path.join(value.directory, 'deploy/runtime-provider-mode'), 'gemini-luna-required\n');
  const result = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', code, value.directory, value.archive, ...versions.map(v => scripts[v - 1])], {
    encoding: 'utf8', timeout: 5_000, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(result.status, 0, 'Local tar fixture creation must succeed; setup failure is invalid RED');
  assert.equal(result.stderr, '');release.archiveSha256 = hash(fs.readFileSync(value.archive));return release;
}
const verifyPackage = (value, release) => f.python('_verify_package', [value.archive, release], 'run-approved-release.py');
function omitPackageMember(value, name) {
  const code = String.raw`import io,pathlib,sys,tarfile
source=pathlib.Path(sys.argv[1]);temporary=source.with_suffix('.omitted');omitted=sys.argv[2]
with tarfile.open(source,'r:gz') as before,tarfile.open(temporary,'w:gz') as after:
 for item in before.getmembers():
  if item.name!=omitted:after.addfile(item,io.BytesIO(before.extractfile(item).read()) if item.isfile() else None)
temporary.replace(source)
`;
  const result = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', code, value.archive, name], {
    encoding: 'utf8', timeout: 5_000, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(result.status, 0, 'Omission fixture must be constructible; setup errors are invalid RED');
  assert.equal(result.stderr, '');
}

test('VC01 V1 through V16 immutable SQL and separate seventeen-file runtime approval oracle remain fixed', () => {
  assert.deepEqual(f.scripts, scripts);
  assert.deepEqual(f.guardFiles, approvedPaths);
  for (let i = 0; i < 16; i++) assert.equal(hash(fs.readFileSync(path.join(root, 'src/main/resources/db/migration', scripts[i]))), originalSqlHashes[i], scripts[i]);
});

test('VC02 current publisher accepts exactly V1 through V18 with independent SQL CRC and both inventory fingerprints', () => {
  const value = f.currentFixture();try {
    const result = publish(value);assert.equal(result.ok, true, 'Frozen additive V18 must be accepted by current publisher');
    assert.deepEqual(result.result.migrations, f.currentScripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(result.result.schemaFingerprint, hash(canonical(f.currentScripts.map((script, i) => ({ version: String(i + 1), script, sha256: hash(fs.readFileSync(path.join(value.migrations, script))) })))));
    assert.equal(result.result.guardFingerprint, hash(canonical(currentExecutionPaths.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    assert.equal(result.result.archiveSha256, hash(fs.readFileSync(value.archive)));
  } finally { value.remove(); }
});

test('VC03 publisher rejects each omission duplicate unknown18 noncanonical17 and unapproved physical inventory entry', () => {
  for (const fault of [...range(17).map(v => `missing-${v}`), 'duplicate17', 'unknown18', 'wrong17', 'leadingZero', 'extra', 'directory']) {
    const value = f.currentFixture();try {
      const last = path.join(value.migrations, scripts[16]);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, scripts[Number(fault.slice(8)) - 1]));
      if (fault === 'duplicate17') fs.copyFileSync(last, path.join(value.migrations, 'V17__duplicate.sql'));
      if (fault === 'unknown18') fs.copyFileSync(last, path.join(value.migrations, 'V18__unknown.sql'));
      if (fault === 'wrong17') fs.renameSync(last, path.join(value.migrations, 'V17__arbitrary.sql'));
      if (fault === 'leadingZero') fs.renameSync(last, path.join(value.migrations, 'V017__add_durable_ai_room_budgets.sql'));
      if (fault === 'extra') fs.writeFileSync(path.join(value.migrations, 'unapproved.txt'), 'synthetic');
      if (fault === 'directory') { fs.unlinkSync(last);fs.mkdirSync(last); }
      assert.deepEqual(publish(value), untrusted, fault);
    } finally { value.remove(); }
  }
});

test('VC04 V17 and its source ancestors cannot be symlinks even when target SQL bytes match', () => {
  for (const relative of [`src/main/resources/db/migration/${scripts[16]}`, 'src/main/resources/db/migration', 'src/main']) {
    const value = f.currentFixture();try {
      const target = path.join(value.directory, relative), actual = `${target}-actual`;
      fs.renameSync(target, actual);fs.symlinkSync(actual, target, fs.statSync(actual).isDirectory() ? 'dir' : 'file');
      assert.deepEqual(publish(value), untrusted, relative);
    } finally { value.remove(); }
  }
});

test('VC05 V17-only byte changes preserve all prior sixteen checksums and distinguish SQL from runtime fingerprints', () => {
  const value = f.currentFixture();try {
    const before = publish(value);assert.equal(before.ok, true);
    fs.appendFileSync(path.join(value.migrations, scripts[16]), '\n-- isolated V17 change\n');
    const after = publish(value);assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 16), before.result.migrations.slice(0, 16));
    assert.notEqual(after.result.migrations[16].checksum, before.result.migrations[16].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
    fs.appendFileSync(path.join(value.directory, 'deploy/scripts/render-runtime-env.sh'), '\n# isolated renderer change\n');
    const changedGuard = publish(value);assert.equal(changedGuard.ok, true);
    assert.equal(changedGuard.result.schemaFingerprint, after.result.schemaFingerprint);
    assert.notEqual(changedGuard.result.guardFingerprint, after.result.guardFingerprint);
  } finally { value.remove(); }
});

test('VC06 both readers preserve every exact historical catalog including V16 and admit only new contiguous V17', () => {
  for (const versions of catalogs) {
    const release = manifest(versions);
    assert.equal(validate(release).ok, true, `Manifest exact approved inventory ${versions.join(',')}`);
    assert.deepEqual(readRecord(record(release)), { ok: true, result: record(release) });
  }
});

test('VC07 both readers reject sparse gaps unknown18 malformed old16 or new17 names and invalid checksums', () => {
  const values = [range(13), range(15), [...range(12), 14, 15], [...range(11), 16], [...range(15), 17], [...range(16), 18]].map(manifest);
  for (const value of values) for (const row of value.migrations) if (row.version === '18') row.script = 'V18__unknown.sql';
  for (const index of [15, 16]) for (const fault of ['wrongName', 'boolean', 'overflow', 'extra']) {
    const release = manifest();
    if (fault === 'wrongName') release.migrations[index].script = `V${index + 1}__arbitrary.sql`;
    if (fault === 'boolean') release.migrations[index].checksum = true;
    if (fault === 'overflow') release.migrations[index].checksum = 2 ** 31;
    if (fault === 'extra') release.migrations[index].extra = true;
    values.push(release);
  }
  for (const value of values) {
    assert.deepEqual(validate(value), untrusted);
    assert.deepEqual(readRecord(record(value)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' });
  }
  const failure = record(manifest());failure.history[16].success = false;
  assert.deepEqual(readRecord(failure), { ok: false, reason: 'UNSAFE_HOST_RELEASE' });
});

test('VC08 approved V16 host state requires explicit transition to V17 and cannot reuse automatic approval', () => {
  const current = manifest(), facts = { ...ready(manifest(range(16))), refreshRuleStates: ['DISABLED', 'DISABLED'] };
  for (const action of ['automatic', 'preflight']) assert.deepEqual(judge(current, facts, action), mode('TRANSITION_REQUIRED'));
  assert.deepEqual(judge(current, facts, 'deploy'), denied);
  assert.deepEqual(judge(current, facts, 'deploy', true), mode('TRANSITION_APPROVED'));
  for (const states of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']])
    assert.deepEqual(judge(current, { ...facts, refreshRuleStates: states }, 'deploy', true), denied);
  assert.deepEqual(judge(current, { ...facts, pendingCommands: 1 }, 'deploy', true), denied);
});

test('VC09 V17 approval preserves every successful prefix and denies old16 new17 history tamper gaps and sparse insertion', () => {
  const release = manifest();
  for (let length = 7; length <= 17; length++) {
    const facts = { ...ready(release), automaticContract: null, history: history(release).slice(0, length), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    assert.deepEqual(judge(release, facts, 'deploy', true), mode('TRANSITION_APPROVED'), `valid prefix ${length}`);
  }
  for (const index of [15, 16]) for (const field of ['script', 'checksum', 'success']) {
    const facts = { ...ready(release), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    facts.history[index][field] = { script: `V${index + 1}__wrong.sql`, checksum: 99, success: false }[field];
    assert.deepEqual(judge(release, facts, 'deploy', true), denied);
  }
  const short = ready(release);short.history = short.history.slice(0, 6);
  const gap = ready(release);gap.history.splice(15, 1);
  const malformed = ready(release);malformed.automaticContract.history.splice(15, 1);
  for (const facts of [short, gap, malformed, ...oldCatalogs.slice(3, 6).map(v => ready(manifest(v)))])
    assert.deepEqual(judge(release, { ...facts, refreshRuleStates: ['DISABLED', 'DISABLED'] }, 'deploy', true), denied);
});

test('VC10 exact V17 unchanged contract stays automatic while schema guard image or publication identity mismatch fails closed', () => {
  const release = manifest();assert.deepEqual(judge(release, ready(release), 'automatic'), mode('AUTO_READY'));
  for (const field of ['schemaFingerprint', 'guardFingerprint']) {
    const facts = ready(release);facts.automaticContract[field] = '0'.repeat(64);
    assert.deepEqual(judge(release, facts, 'automatic'), mode('TRANSITION_REQUIRED'));
    assert.deepEqual(judge(release, facts, 'deploy'), denied);
  }
  const facts = ready(release);facts.currentImage = `${f.repository}@sha256:${'6'.repeat(64)}`;
  assert.deepEqual(judge(release, facts, 'automatic'), mode('TRANSITION_REQUIRED'));
  for (const field of ['sourceSha', 'buildRunId', 'buildRunAttempt', 'imageRepository']) {
    const invalid = manifest();invalid[field] = 'untrusted';assert.deepEqual(validate(invalid), untrusted);
  }
});

test('VC11 offline package verifies historical V16 and current V17 and refuses SQL byte checksum or missing-row tamper', () => {
  const value = fixture();try {
    for (const versions of [range(16), range(17)]) {
      const release = packageRelease(value, versions);assert.equal(verifyPackage(value, release).ok, true);
    }
    const exact = packageRelease(value);
    const wrongChecksum = clone(exact);wrongChecksum.migrations[16].checksum += 1;
    assert.equal(verifyPackage(value, wrongChecksum).ok, false);
    fs.appendFileSync(path.join(value.migrations, scripts[16]), '\n-- package tamper\n');
    const repacked = packageRelease(value);const stale = { ...exact, archiveSha256: repacked.archiveSha256 };
    assert.equal(verifyPackage(value, stale).ok, false, 'Refreshing archive hash cannot conceal stale SQL CRC/schema');
    omitPackageMember(value, `migrations/${scripts[16]}`);
    assert.equal(verifyPackage(value, repacked).ok, false, 'V17 package cannot omit its last SQL');
    const validAgain = packageRelease(value);
    omitPackageMember(value, 'scripts/render-runtime-env.sh');
    assert.equal(verifyPackage(value, validAgain).ok, false, 'Current auth17 marker cannot excuse a missing renderer');
  } finally { value.remove(); }
});

test('VC12 actual source must contain real V18 and publish without a synthetic SQL fallback', () => {
  const directory = path.join(root, 'src/main/resources/db/migration');
  assert.deepEqual(fs.readdirSync(directory).sort(), [...f.currentScripts].sort(), 'Actual-source gate requires real exact V18 SQL');
  const archive = path.join(require('node:os').tmpdir(), `issue123-actual-${process.pid}-${Date.now()}.tar.gz`);
  fs.writeFileSync(archive, 'synthetic archive; SQL MUST be actual source');
  try {
    const result = f.python('build_manifest', [root, archive, f.environment], 'publish_manifest.py');
    assert.equal(result.ok, true);
    assert.deepEqual(result.result.migrations, f.currentScripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(directory, script))) })));
  } finally { fs.rmSync(archive, { force: true }); }
});
