const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc } = f;

// Public accepted V32 release contract. Historical V1..V31 SQL digests are copied
// from the previously reviewed catalog, never calculated as their own oracle.
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
  'V32__add_operator_room_reanalysis.sql'];
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
  '72b6e90537f5f05624445351b54980a97eccca794a7de3be91547d2f2ed9f1c0'];

function release(count = 32) {
  const result = f.manifest();
  result.migrations = scripts.slice(0, count).map((script, i) => ({ version: String(i + 1), script, checksum: (i + 1) * 37 }));
  result.schemaFingerprint = hash(canonical(result.migrations));
  return result;
}
function fixture() {
  // The stable V17 fixture is historical; currentFixture may advance separately.
  const value = f.fixture();
  for (const [index, name] of scripts.entries()) {
    const source = path.join(root, 'src/main/resources/db/migration', name);
    if (fs.existsSync(source)) fs.copyFileSync(source, path.join(value.migrations, name));
    else {
      assert.equal(index, 31, 'Historical SQL absence is a fixture error, never valid RED');
      // The new not-yet-authored SQL is synthetic only inside this isolated fixture.
      fs.writeFileSync(path.join(value.migrations, name), '-- isolated synthetic V32 catalog fixture\nSELECT 32;\n');
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
function pack(value, count = 32, omitted = '') {
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

test('V32C01 exact additive SQL inventory preserves all thirty-one reviewed migration bytes', () => {
  const directory = path.join(root, 'src/main/resources/db/migration');
  assert.deepEqual(fs.readdirSync(directory).sort(), [...scripts].sort(), 'The only new migration is reviewed V32');
  assert.deepEqual(scripts.slice(0, 31).map(name => hash(fs.readFileSync(path.join(directory, name)))), historicalSqlHashes);
});

test('V32C02 actual publisher binds every current SQL CRC, SQL SHA and operational guard SHA independently', () => {
  const value = fixture(); try {
    const actual = f.publish(value);
    assert.equal(actual.ok, true, 'Exact V32 publication is now required');
    assert.deepEqual(actual.result.migrations, scripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(actual.result.schemaFingerprint, hash(canonical(scripts.map((script, i) => ({ version: String(i + 1), script,
      sha256: hash(fs.readFileSync(path.join(value.migrations, script))) })))));
    assert.equal(actual.result.guardFingerprint, hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
  } finally { value.remove(); }
});

test('V32C03 both metadata readers admit V32 and preserve every historically published complete catalog', () => {
  const catalogs = [...f.catalogs, ...range(6).map(i => range(17 + i)), range(31), range(32)];
  for (const versions of catalogs) {
    const manifest = release();
    manifest.migrations = versions.map(n => ({ version: String(n), script: scripts[n - 1], checksum: n * 37 }));
    assert.equal(f.validate(manifest).ok, true, `Complete V${versions.at(-1)} publication metadata`);
    assert.deepEqual(f.readRecord(f.record(manifest)), { ok: true, result: f.record(manifest) }, `Complete V${versions.at(-1)} host history`);
  }
});

test('V32C04 neither reader invents unpublished V24 through V30 or future V33 complete catalogs', () => {
  for (let count = 24; count <= 30; count++) bothReject(release(count), `Unpublished V${count}`);
  const future = release(); future.migrations.push({ version: '33', script: 'V33__unapproved.sql', checksum: 1221 });
  bothReject(future, 'Unknown future migration');
});

test('V32C05 publisher rejects V31-only sources and missing, renamed, duplicate, linked, nonfile or future SQL', () => {
  const faults = ['v31-only', 'missing31', 'renamed32', 'duplicate32', 'leadingZero32', 'directory32', 'symlink32', 'unknown33'];
  for (const fault of faults) {
    const value = fixture(); try {
      const last = path.join(value.migrations, scripts[31]);
      if (fault === 'v31-only') fs.unlinkSync(last);
      if (fault === 'missing31') fs.unlinkSync(path.join(value.migrations, scripts[30]));
      if (fault === 'renamed32') fs.renameSync(last, path.join(value.migrations, 'V32__unapproved.sql'));
      if (fault === 'duplicate32') fs.copyFileSync(last, path.join(value.migrations, 'V32__duplicate.sql'));
      if (fault === 'leadingZero32') fs.renameSync(last, path.join(value.migrations, 'V032__add_operator_room_reanalysis.sql'));
      if (fault === 'directory32') { fs.unlinkSync(last); fs.mkdirSync(last); }
      if (fault === 'symlink32') {
        const target = path.join(value.directory, 'actual32.sql'); fs.renameSync(last, target); fs.symlinkSync(target, last);
      }
      if (fault === 'unknown33') fs.copyFileSync(last, path.join(value.migrations, 'V33__unapproved.sql'));
      assert.deepEqual(f.publish(value), f.untrusted, fault);
    } finally { value.remove(); }
  }
});

test('V32C06 both readers reject gaps, reordered or duplicate rows and malformed V32 fields', () => {
  for (const fault of ['missing31', 'duplicate32', 'reversed', 'leadingZero32', 'booleanChecksum', 'stringChecksum', 'overflowChecksum', 'extraField']) {
    const manifest = release(); const last = manifest.migrations.at(-1);
    if (fault === 'missing31') manifest.migrations.splice(30, 1);
    if (fault === 'duplicate32') manifest.migrations.push(clone(last));
    if (fault === 'reversed') manifest.migrations.reverse();
    if (fault === 'leadingZero32') last.version = '032';
    if (fault === 'booleanChecksum') last.checksum = true;
    if (fault === 'stringChecksum') last.checksum = '1184';
    if (fault === 'overflowChecksum') last.checksum = 2 ** 31;
    if (fault === 'extraField') last.ignored = true;
    bothReject(manifest, fault);
  }
});

test('V32C07 exact V32 archive binds SQL and guard bytes while preserving historical V31 package reading', () => {
  const value = fixture(); try {
    const original = pack(value); assert.equal(verifyPackage(value, original).ok, true, 'Exact current V32 archive');
    const incorrectChecksum = clone(original); incorrectChecksum.migrations[31].checksum += 1;
    assert.equal(verifyPackage(value, incorrectChecksum).ok, false, 'Wrong V32 packaged CRC');
    const omitted = pack(value, 32, scripts[31]);
    assert.equal(verifyPackage(value, omitted).ok, false, 'Missing V32 packaged SQL');
    pack(value);
    for (const field of ['schemaFingerprint', 'guardFingerprint']) {
      assert.equal(verifyPackage(value, { ...original, [field]: hash('untrusted binding') }).ok, false, field);
    }
    const historical = pack(value, 31);
    assert.equal(verifyPackage(value, historical).ok, true, 'Historical V31 remains readable');
  } finally { value.remove(); }
});

test('V32C08 changed V32 SQL changes only its CRC and schema digest and cannot reuse stale archive authorization', () => {
  const value = fixture(); try {
    const before = f.publish(value); assert.equal(before.ok, true);
    const oldPackage = pack(value);
    fs.appendFileSync(path.join(value.migrations, scripts[31]), '\n-- isolated test-only byte integrity probe\n');
    const after = f.publish(value); assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 31), before.result.migrations.slice(0, 31));
    assert.notEqual(after.result.migrations[31].checksum, before.result.migrations[31].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
    const current = pack(value);
    assert.equal(verifyPackage(value, { ...oldPackage, archiveSha256: current.archiveSha256 }).ok, false);
    assert.equal(verifyPackage(value, current).ok, true);
  } finally { value.remove(); }
});

test('V32C09 V31 to V32 requires guarded explicit transition and intact prior history', () => {
  const target = release(); const old = f.ready(release(31));
  assert.deepEqual(f.judge(target, old, 'automatic'), f.mode('TRANSITION_REQUIRED'));
  assert.deepEqual(f.judge(target, old, 'automatic', true), f.mode('TRANSITION_REQUIRED'), 'Boolean alone never bypasses automatic classification');
  assert.deepEqual(f.judge(target, old, 'preflight'), f.mode('TRANSITION_REQUIRED'));
  const paused = { ...old, refreshRuleStates: ['DISABLED', 'DISABLED'] };
  assert.deepEqual(f.judge(target, paused, 'deploy'), f.denied);
  assert.deepEqual(f.judge(target, paused, 'deploy', true), f.mode('TRANSITION_APPROVED'));
  for (const refreshRuleStates of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']]) {
    assert.deepEqual(f.judge(target, { ...paused, refreshRuleStates }, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(target, { ...paused, pendingCommands: 1 }, 'deploy', true), f.denied);
  for (const position of [0, 13, 22, 30]) {
    const changed = clone(paused); changed.history[position].checksum += 1;
    assert.deepEqual(f.judge(target, changed, 'deploy', true), f.denied, `Changed V${position + 1} history`);
  }
  assert.deepEqual(f.judge(target, f.ready(target), 'automatic'), f.mode('AUTO_READY'), 'Only an already verified exact V32 host is automatic');
  const changedGuard = clone(target); changedGuard.guardFingerprint = hash('different reviewed guard');
  assert.deepEqual(f.judge(changedGuard, f.ready(target), 'automatic'), f.mode('TRANSITION_REQUIRED'));
});

test('V32C10 publication data advertises the one operator POST and complete typed request receipt and room CAS metadata', () => {
  const spec = JSON.parse(fs.readFileSync(path.join(root, 'deploy/public-api-contract.json'), 'utf8'));
  const operation = spec.operations.filter(item => item.path === '/api/operations/rooms/{roomId}/analyses' && item.method === 'post');
  assert.equal(operation.length, 1, 'Published public API must include the new POST exactly once');
  const schemas = Object.values(spec.schemaProperties);
  const expectedRequest = ['request_id', 'source_analysis_id', 'expected_state_version'].sort();
  const expectedReceipt = ['request_id', 'source_analysis_id', 'analysis_id', 'accepted_state_version', 'accepted_at', 'analysis_contract_version'].sort();
  assert.ok(schemas.some(fields => canonical([...fields].sort()) === canonical(expectedRequest)), 'Exact public typed request fields');
  assert.ok(schemas.some(fields => canonical([...fields].sort()) === canonical(expectedReceipt)), 'Exact public typed receipt fields');
  for (const field of ['active_analysis_id', 'state_version', 'current_analysis_confirmed', 'has_open_revision_round']) {
    assert.ok(spec.schemaProperties.RoomSummaryResponse.includes(field), `Operator room CAS metadata: ${field}`);
  }
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
test('V32C11 additive catalog gate preserves original commands three jobs authority and protected deployment entry', () => {
  const frozen = require('./fixtures/ci-original-checks.json');
  const ci = workflow(fs.readFileSync(path.join(root, '.github/workflows/ci.yml'), 'utf8'));
  assert.equal(ci.rootSource, frozen.rootSource);
  assert.deepEqual([...ci.jobs.keys()].sort(), ['built-image', 'deployment-contracts', 'quality']);
  assert.equal(job(ci, 'quality').header, frozen.qualityHeader);
  assert.deepEqual(job(ci, 'quality').steps, [...frozen.setupSteps, frozen.qualityRunSteps[0]]);
  assert.equal(ci.jobs.get('built-image'), frozen.arm64JobSource);
  const contracts = job(ci, 'deployment-contracts');
  assert.equal(contracts.header, frozen.qualityHeader.replace('  quality:', '  deployment-contracts:').replace('    name: Quality Gate', '    name: Deployment Contracts'));
  const added = 'node --test .github/scripts/v32-operator-reanalysis-release-catalog.test.cjs';
  const addedSteps = contracts.steps.filter(step => command(step) === added);
  assert.equal(addedSteps.length, 1, 'New gate is unconditional and blocking exactly once');
  assert.match(addedSteps[0], /^      - name: [^\n]+\n        run: node --test \.github\/scripts\/v32-operator-reanalysis-release-catalog\.test\.cjs$/);
  const commands = [...ci.jobs.keys()].flatMap(id => job(ci, id).steps.map(command).filter(value => value !== null));
  const prior = [...frozen.originalRunCommands, 'node --test .github/scripts/ci-job-boundaries.test.cjs',
    'node --test .github/scripts/v23-account-room-hide-release-catalog.test.cjs',
    'node --test .github/scripts/v31-analysis-release-catalog.test.cjs',
    'node --test deploy/tests/default-analysis-runtime-env.test.cjs'];
  for (const expected of [...prior, added]) assert.equal(commands.filter(actual => actual === expected).length, 1, expected);
  assert.equal(commands.length, prior.length + 1, 'No existing gate omitted or duplicated');
  const deploy = workflow(fs.readFileSync(path.join(root, '.github/workflows/deploy.yml'), 'utf8'));
  assert.equal(deploy.rootSource, frozen.publicationRootSource);
  assert.equal(job(deploy, 'build-publish').header, frozen.publicationGateHeader);
  const approval = job(deploy, 'transition-approval');
  assert.match(approval.header, /needs\.approved-release\.outputs\.mode == 'TRANSITION_REQUIRED'/);
  assert.match(approval.header, /environment: \$\{\{ needs\.approved-release\.outputs\.gate_environment \}\}/);
  assert.match(approval.header, /timeout-minutes: 5/);
  assert.doesNotMatch(approval.header, /id-token: write/);
  const gate = JSON.parse(fs.readFileSync(path.join(root, 'deploy/transition-approval.json'), 'utf8'));
  assert.equal(gate.state, 'CONFIGURED'); assert.equal(gate.environment.name, 'production-transition-approval');
  assert.equal(gate.gateJobName, 'Approve production transition');
  assert.ok(gate.reviewerIds.length > 0);
});
