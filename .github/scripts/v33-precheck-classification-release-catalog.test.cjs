const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc } = f;

// Public accepted V33 release contract. Historical V1..V32 digests are fixed
// from the previously published inventory, never calculated as their own oracle.
const scripts = [...f.scripts,
  'V18__align_recommendation_selection_attendance.sql',
  'V19__add_confirmed_result_reviews.sql',
  'V20__add_account_room_history_index.sql',
  'V21__add_submission_input_evidence.sql',
  'V22__preserve_unverified_candidate_place.sql',
  'V23__add_account_room_archive_hidden_at.sql',
  'V24__add_versioned_schedule_ir.sql',
  'V25__add_ir_execution_bindings.sql',
  'V26__add_final_analysis_routes.sql',
  'V27__add_approved_terms_final_analysis_route.sql',
  'V28__add_context_mode_final_analysis_route.sql',
  'V29__add_structural_final_analysis_route.sql',
  'V30__add_c5_residual_luna_policy.sql',
  'V31__allow_explicit_c5_direct_luna.sql',
  'V32__add_operator_room_reanalysis.sql',
  'V33__record_precheck_classifications.sql'];
// V33 reader/archive defaults remain historical; only current publisher fixtures advance.
const currentScripts = [...scripts, 'V34__add_stream_analysis_retry_schedule.sql'];
const historicalSqlHashes = [
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
  '912b8f227e6ea7c20fc05cf96d705982fb9737c5051c7c07e771494df482834b',
  '67061520a8fb4a96412ec1b82e7b6a4d07a4ab7745187d0fc1c9c759b45eec27',
  'b5e55df55f97f754239653252862e6631c570f1a724b05b063d8320826d5109f',
  '99062f8dfb8bbca178c204be4eb5a297e99868f4193b290dbb3f4261d711cc8a',
  'e641594b3637bb1979030da05336ae1aa3307e3d7a6f6f813fde7673bd34f3ed',
  '2e2aa1711a33676ea41801b64f93c0ed2d946105797724a288399097ffc6fcd6',
  'b7f80b12785446d8241f7c42ca73acb0599ea3f874e1d1293097ee929c6d9e31',
  'a0a6ddd539a11d6aa6270735c42cde27e9b5212b960fbc6207e306f497da2535',
  'f327bfc4db87298549f21f89760832ca0207a3c2eebd9acb459655ec5d93ef04',
  'aa724525bce54432fc41142ddc10a1c249c2af3fc3b9e40644042449ab9265d8',
  'd9a42f418db962d7c88830274344b6720104dc9b8ae4e88c0f78043ce125e8b2',
  'faaad0d8a20fc30e85f06f16494ad258d336346e63a9b27ea8b55db5bb53b4a7',
  'df28c55c09940de630df153715a96419d9ef99ae2ea3ed0d577162e5d9aa70c2',
  'd9c23577e2b4503743fd9419f833ff8082a7879bcd325ca3b8154517d286b636',
  '72b6e90537f5f05624445351b54980a97eccca794a7de3be91547d2f2ed9f1c0',
  '7af43aaf9ec949255f3fc4f653d7574bc8550a262475e15eeec4c723b1d2f939'];

function release(count = 33) {
  const result = f.manifest();
  result.migrations = scripts.slice(0, count).map((script, i) => ({ version: String(i + 1), script, checksum: (i + 1) * 37 }));
  result.schemaFingerprint = hash(canonical(result.migrations));
  return result;
}
function fixture() {
  // The stable V17 fixture is historical; currentFixture may advance separately.
  const value = f.fixture();
  for (const [index, name] of currentScripts.entries()) {
    const source = path.join(root, 'src/main/resources/db/migration', name);
    if (fs.existsSync(source)) fs.copyFileSync(source, path.join(value.migrations, name));
    else {
      assert.equal(index, 33, 'Historical SQL absence is a fixture error, never valid RED');
      // The new not-yet-authored SQL is synthetic only inside this isolated fixture.
      fs.writeFileSync(path.join(value.migrations, name), '-- isolated synthetic V34 catalog fixture\nSELECT 34;\n');
    }
  }
  for (const name of f.currentGuardFiles) {
    const target = path.join(value.directory, 'deploy', name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(root, 'deploy', name), target);
  }
  fs.copyFileSync(path.join(root, 'deploy/public-api-contract.json'), path.join(value.directory, 'deploy/public-api-contract.json'));
  return value;
}
function pack(value, count = 33, omitted = '') {
  const names = scripts.slice(0, count);
  const result = release(count);
  result.migrations = names.map((script, i) => ({ version: String(i + 1), script,
    checksum: crc(fs.readFileSync(path.join(value.migrations, script))) }));
  result.schemaFingerprint = hash(canonical(names.map((script, i) => ({ version: String(i + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }))));
  result.guardFingerprint = hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
    sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) }))));
  const compiled = path.join(value.directory, 'deploy/preflight/compiled');
  fs.mkdirSync(compiled, { recursive: true });
  fs.writeFileSync(path.join(compiled, 'ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  fs.writeFileSync(path.join(value.directory, 'deploy/runtime-provider-mode'), 'gemini-luna-required\n');
  const program = String.raw`import pathlib,sys,tarfile
root=pathlib.Path(sys.argv[1]);archive=pathlib.Path(sys.argv[2]);omitted=sys.argv[3]
def permissions(info):
 if info.isfile() and info.name in ('scripts/render-runtime-env.sh','scripts/export-certificate.sh'):info.mode=0o755
 return info
with tarfile.open(archive,'w:gz') as tar:
 for item in sorted((root/'deploy').rglob('*')):
  if item.is_file():tar.add(item,arcname=str(item.relative_to(root/'deploy')),recursive=False,filter=permissions)
 for name in sys.argv[4:]:
  if name!=omitted:tar.add(root/'src/main/resources/db/migration'/name,arcname='migrations/'+name,recursive=False)
`;
  const built = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', program, value.directory, value.archive, omitted, ...names], {
    encoding: 'utf8', timeout: 10000,
    env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(built.status, 0, 'Archive construction errors are not valid catalog RED');
  assert.equal(built.stderr, '');
  result.archiveSha256 = hash(fs.readFileSync(value.archive));
  return result;
}
const verifyPackage = (value, manifest) => f.python('_verify_package', [value.archive, manifest], 'run-approved-release.py');
const rejectedRecord = { ok: false, reason: 'UNSAFE_HOST_RELEASE' };
function bothReject(manifest, message) {
  assert.deepEqual(f.validate(manifest), f.untrusted, `${message}: publication reader`);
  assert.deepEqual(f.readRecord(f.record(manifest)), rejectedRecord, `${message}: host reader`);
}

test('V33C01 current inventory advances through V34 and preserves the historical V1 to V32 byte contract', () => {
  const directory = path.join(root, 'src/main/resources/db/migration');
  assert.deepEqual(fs.readdirSync(directory).sort(), [...currentScripts].sort());
  assert.deepEqual(scripts.slice(0, 32).map(name => hash(fs.readFileSync(path.join(directory, name)))), historicalSqlHashes);
});

test('V33C02 actual publisher binds all current thirty-four SQL checksums schema bytes and operational guards', () => {
  const value = fixture(); try {
    const actual = f.publish(value); assert.equal(actual.ok, true, 'Exact current V34 publication must be supported');
    assert.deepEqual(actual.result.migrations, currentScripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(actual.result.schemaFingerprint, hash(canonical(currentScripts.map((script, i) => ({ version: String(i + 1), script,
      sha256: hash(fs.readFileSync(path.join(value.migrations, script))) })))));
    assert.equal(actual.result.guardFingerprint, hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
  } finally { value.remove(); }
});

test('V33C03 both readers admit the new V33 catalog and preserve complete historical catalogs including V32', () => {
  for (const versions of [...f.catalogs, ...range(6).map(i => range(17 + i)), range(31), range(32), range(33)]) {
    const value = release(); value.migrations = versions.map(n => ({ version: String(n), script: scripts[n - 1], checksum: n * 37 }));
    assert.equal(f.validate(value).ok, true, `Complete V${versions.at(-1)} publication metadata`);
    assert.deepEqual(f.readRecord(f.record(value)), { ok: true, result: f.record(value) }, `Complete V${versions.at(-1)} host history`);
  }
});

test('V33C04 neither reader invents unpublished intermediate or unknown future complete catalogs', () => {
  for (let n = 24; n <= 30; n++) bothReject(release(n), `Unpublished V${n}`);
  const future = release(); future.migrations.push({ version: '34', script: 'V34__unapproved.sql', checksum: 1258 });
  bothReject(future, 'Unapproved V34 name');
});

test('V33C05 current publisher rejects V32-only and omitted malformed duplicate linked directory or future migrations', () => {
  for (const fault of ['v32-only', ...range(32).map(n => `missing-${n}`), 'renamed33', 'duplicate33', 'leadingZero33', 'directory33', 'symlink33', 'future34']) {
    const value = fixture(); try {
      const latest = path.join(value.migrations, scripts[32]);
      if (fault === 'v32-only') fs.unlinkSync(latest);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, scripts[Number(fault.slice(8)) - 1]));
      if (fault === 'renamed33') fs.renameSync(latest, path.join(value.migrations, 'V33__unapproved.sql'));
      if (fault === 'duplicate33') fs.copyFileSync(latest, path.join(value.migrations, 'V33__duplicate.sql'));
      if (fault === 'leadingZero33') fs.renameSync(latest, path.join(value.migrations, 'V033__record_precheck_classifications.sql'));
      if (fault === 'directory33') { fs.unlinkSync(latest); fs.mkdirSync(latest); }
      if (fault === 'symlink33') { const target = path.join(value.directory, 'actual33.sql'); fs.renameSync(latest, target); fs.symlinkSync(target, latest); }
      if (fault === 'future34') fs.copyFileSync(latest, path.join(value.migrations, 'V34__unapproved.sql'));
      assert.deepEqual(f.publish(value), f.untrusted, fault);
    } finally { value.remove(); }
  }
});

test('V33C06 metadata readers reject malformed checksums names ordering gaps and diagnostic payloads as authority', () => {
  for (const fault of ['missing32', 'duplicate33', 'reversed', 'leadingZero33', 'renamed33', 'booleanChecksum', 'stringChecksum', 'overflowChecksum', 'diagnosticField']) {
    const value = release(); const latest = value.migrations.at(-1);
    if (fault === 'missing32') value.migrations.splice(31, 1);
    if (fault === 'duplicate33') value.migrations.push(clone(latest));
    if (fault === 'reversed') value.migrations.reverse();
    if (fault === 'leadingZero33') latest.version = '033';
    if (fault === 'renamed33') latest.script = 'V33__unapproved.sql';
    if (fault === 'booleanChecksum') latest.checksum = true;
    if (fault === 'stringChecksum') latest.checksum = '1221';
    if (fault === 'overflowChecksum') latest.checksum = 2 ** 31;
    if (fault === 'diagnosticField') latest.classification_snapshot = { version: 1 };
    bothReject(value, fault);
  }
});

test('V33C07 exact V33 archives bind migration bytes and retain historical V32 archive reading', () => {
  const value = fixture(); try {
    const actual = pack(value); assert.equal(verifyPackage(value, actual).ok, true);
    const corrupt = clone(actual); corrupt.migrations[32].checksum += 1;
    assert.equal(verifyPackage(value, corrupt).ok, false, 'Wrong V33 packaged CRC');
    const missing = pack(value, 33, scripts[32]); assert.equal(verifyPackage(value, missing).ok, false);
    pack(value);
    for (const field of ['schemaFingerprint', 'guardFingerprint']) assert.equal(verifyPackage(value, { ...actual, [field]: hash('incorrect binding') }).ok, false);
    assert.equal(verifyPackage(value, pack(value, 32)).ok, true, 'Previously published V32 remains readable');
  } finally { value.remove(); }
});

test('V33C08 new diagnostic SQL changes only its CRC and schema fingerprint and cannot reuse stale archive authorization', () => {
  const value = fixture(); try {
    const before = f.publish(value); assert.equal(before.ok, true);
    const oldPackage = pack(value);
    fs.appendFileSync(path.join(value.migrations, scripts[32]), '\n-- isolated test-only V33 integrity probe\n');
    const after = f.publish(value); assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 32), before.result.migrations.slice(0, 32));
    assert.notEqual(after.result.migrations[32].checksum, before.result.migrations[32].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
    const current = pack(value);
    assert.equal(verifyPackage(value, { ...oldPackage, archiveSha256: current.archiveSha256 }).ok, false);
    assert.equal(verifyPackage(value, current).ok, true);
  } finally { value.remove(); }
});

test('V33C09 V32 to V33 requires protected transition and intact already published migration history', () => {
  const current = release(); const old = f.ready(release(32));
  assert.deepEqual(f.judge(current, old, 'automatic'), f.mode('TRANSITION_REQUIRED'));
  assert.deepEqual(f.judge(current, old, 'automatic', true), f.mode('TRANSITION_REQUIRED'));
  assert.deepEqual(f.judge(current, old, 'preflight'), f.mode('TRANSITION_REQUIRED'));
  const paused = { ...old, refreshRuleStates: ['DISABLED', 'DISABLED'] };
  assert.deepEqual(f.judge(current, paused, 'deploy'), f.denied);
  assert.deepEqual(f.judge(current, paused, 'deploy', true), f.mode('TRANSITION_APPROVED'));
  for (const refreshRuleStates of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']]) {
    assert.deepEqual(f.judge(current, { ...paused, refreshRuleStates }, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(current, { ...paused, pendingCommands: 1 }, 'deploy', true), f.denied);
  for (const index of [0, 13, 30, 31]) {
    const changed = clone(paused); changed.history[index].checksum += 1;
    assert.deepEqual(f.judge(current, changed, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(current, f.ready(current), 'automatic'), f.mode('AUTO_READY'));
});

function workflow(source) {
  const marker = '\njobs:\n'; const start = source.indexOf(marker);
  assert.ok(start >= 0); assert.equal(source.indexOf(marker, start + marker.length), -1);
  const tail = source.slice(start + marker.length); const starts = [...tail.matchAll(/^  ([a-z][a-z0-9-]*):\n/gm)];
  return { rootSource: source.slice(0, start + marker.length).trimEnd(),
    jobs: new Map(starts.map((match, i) => [match[1], tail.slice(match.index, starts[i + 1]?.index).trimEnd()])) };
}
function job(ci, id) {
  const source = ci.jobs.get(id); const marker = '    steps:\n'; const start = source.indexOf(marker);
  assert.ok(start >= 0); const tail = source.slice(start + marker.length); const starts = [...tail.matchAll(/^      - /gm)];
  return { header: source.slice(0, start).trimEnd(),
    steps: starts.map((match, i) => tail.slice(match.index, starts[i + 1]?.index).trimEnd()) };
}
function command(step) {
  const match = step.match(/^        run: (.*)$/m); if (!match) return null;
  if (match[1] !== '|') return match[1];
  return step.slice(match.index + match[0].length + 1).split('\n').map(line => line.slice(10)).join('\n') + '\n';
}
test('V33C10 the additive V33 gate preserves every prior CI command job boundary and protected production entry', () => {
  const frozen = require('./fixtures/ci-original-checks.json');
  const ci = workflow(fs.readFileSync(path.join(root, '.github/workflows/ci.yml'), 'utf8'));
  assert.equal(ci.rootSource, frozen.rootSource);
  assert.deepEqual([...ci.jobs.keys()].sort(), ['built-image', 'deployment-contracts', 'quality']);
  assert.equal(job(ci, 'quality').header, frozen.qualityHeader.replace('    timeout-minutes: 30', '    timeout-minutes: 40'));
  assert.deepEqual(job(ci, 'quality').steps, [...frozen.setupSteps, frozen.qualityRunSteps[0]]);
  assert.equal(ci.jobs.get('built-image'), frozen.arm64JobSource);
  const contracts = job(ci, 'deployment-contracts');
  assert.equal(contracts.header, frozen.qualityHeader.replace('  quality:', '  deployment-contracts:').replace('    name: Quality Gate', '    name: Deployment Contracts'));
  const next = 'node --test .github/scripts/v33-precheck-classification-release-catalog.test.cjs';
  const additions = contracts.steps.filter(step => command(step) === next);
  assert.equal(additions.length, 1);
  assert.match(additions[0], /^      - name: [^\n]+\n        run: node --test \.github\/scripts\/v33-precheck-classification-release-catalog\.test\.cjs$/);
  const prior = [...frozen.originalRunCommands, 'node --test .github/scripts/ci-job-boundaries.test.cjs',
    'node --test .github/scripts/v23-account-room-hide-release-catalog.test.cjs',
    'node --test .github/scripts/v31-analysis-release-catalog.test.cjs',
    'node --test deploy/tests/default-analysis-runtime-env.test.cjs',
    'node --test .github/scripts/v32-operator-reanalysis-release-catalog.test.cjs',
    'node --test .github/scripts/v34-stream-retry-release-catalog.test.cjs'];
  const actual = [...ci.jobs.keys()].flatMap(id => job(ci, id).steps.map(command).filter(value => value !== null));
  for (const expected of [...prior, next]) assert.equal(actual.filter(value => value === expected).length, 1, expected);
  assert.equal(actual.length, prior.length + 1);
  const deploy = workflow(fs.readFileSync(path.join(root, '.github/workflows/deploy.yml'), 'utf8'));
  assert.equal(deploy.rootSource, frozen.publicationRootSource);
  assert.equal(job(deploy, 'build-publish').header, frozen.publicationGateHeader);
  const gate = job(deploy, 'transition-approval');
  assert.match(gate.header, /needs\.approved-release\.outputs\.mode == 'TRANSITION_REQUIRED'/);
  assert.match(gate.header, /environment: \$\{\{ needs\.approved-release\.outputs\.gate_environment \}\}/);
  assert.match(gate.header, /timeout-minutes: 5/);
  assert.doesNotMatch(gate.header, /id-token: write/);
});
