const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc } = f;

// Reviewed SQL filenames are an independent public release specification, not production constants.
// V24..V31 are one newly published complete catalog; no intermediate release was published.
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
  'V31__allow_explicit_c5_direct_luna.sql'];
assert.equal(scripts.length, 31);

function release(count = 31) {
  const result = f.manifest();
  result.migrations = scripts.slice(0, count).map((script, i) => ({ version: String(i + 1), script, checksum: (i + 1) * 37 }));
  result.schemaFingerprint = hash(canonical(result.migrations));
  return result;
}
function fixture() {
  const value = f.currentFixture();
  for (const script of scripts) {
    const source = path.join(root, 'src/main/resources/db/migration', script);
    assert.equal(fs.existsSync(source), true, 'Reviewed SQL must exist; fixture setup is not valid catalog RED');
    fs.copyFileSync(source, path.join(value.migrations, script));
  }
  return value;
}
function pack(value, count = 31, omitted = '') {
  const result = release(count);
  result.migrations = scripts.slice(0, count).map((script, i) => ({ version: String(i + 1), script,
    checksum: crc(fs.readFileSync(path.join(value.migrations, script))) }));
  result.schemaFingerprint = hash(canonical(scripts.slice(0, count).map((script, i) => ({ version: String(i + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }))));
  result.guardFingerprint = hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
    sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) }))));
  const probe = path.join(value.directory, 'deploy/preflight/compiled'); fs.mkdirSync(probe, { recursive: true });
  fs.writeFileSync(path.join(probe, 'ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  fs.writeFileSync(path.join(value.directory, 'deploy/runtime-provider-mode'), 'gemini-luna-required\n');
  const source = String.raw`import pathlib,sys,tarfile
root=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2]);omitted=sys.argv[3]
def executable(info):
 if info.isfile() and info.name in ('scripts/render-runtime-env.sh','scripts/export-certificate.sh'):info.mode=0o755
 return info
with tarfile.open(dest,'w:gz') as archive:
 for item in sorted((root/'deploy').rglob('*')):
  if item.is_file():archive.add(item,arcname=str(item.relative_to(root/'deploy')),recursive=False,filter=executable)
 for name in sys.argv[4:]:
  if name!=omitted:archive.add(root/'src/main/resources/db/migration'/name,arcname='migrations/'+name,recursive=False)
`;
  const packed = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', source, value.directory, value.archive, omitted, ...scripts.slice(0, count)], {
    encoding: 'utf8', timeout: 10000, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(packed.status, 0, 'Archive construction failure is not catalog RED');
  assert.equal(packed.stderr, '');
  result.archiveSha256 = hash(fs.readFileSync(value.archive));
  return result;
}
const verify = (value, manifest) => f.python('_verify_package', [value.archive, manifest], 'run-approved-release.py');

// SHA-256 of already authored SQL at reviewed source 80c819a. Catalog repair must never rewrite it.
const reviewedSqlHashes = [
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

test('V31C01 the exact reviewed SQL inventory retains every historical migration byte', () => {
  assert.deepEqual(fs.readdirSync(path.join(root, 'src/main/resources/db/migration')).sort(), [...scripts].sort());
  assert.deepEqual(scripts.map(script => hash(fs.readFileSync(path.join(root, 'src/main/resources/db/migration', script)))), reviewedSqlHashes);
});

test('V31C02 current publisher binds all thirty-one actual SQL CRCs and schema SHA independently', () => {
  const value = fixture(); try {
    const actual = f.publish(value); assert.equal(actual.ok, true, 'Exact V31 publication must be admitted');
    assert.deepEqual(actual.result.migrations, scripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(actual.result.schemaFingerprint, hash(canonical(scripts.map((script, i) => ({ version: String(i + 1), script,
      sha256: hash(fs.readFileSync(path.join(value.migrations, script))) })))));
  } finally { value.remove(); }
});

test('V31C03 publication reader admits exact V31 and retains every previously published complete catalog', () => {
  for (const versions of [...f.catalogs, ...range(6).map(i => range(17 + i)), range(31)]) {
    const manifest = release();
    manifest.migrations = versions.map(n => ({ version: String(n), script: scripts[n - 1], checksum: n * 37 }));
    assert.equal(f.validate(manifest).ok, true, `Complete V${versions.at(-1)} metadata`);
  }
});

test('V31C04 host automatic contract reader restores exact V31 and all historical complete catalogs', () => {
  for (const versions of [...f.catalogs, ...range(6).map(i => range(17 + i)), range(31)]) {
    const manifest = release();
    manifest.migrations = versions.map(n => ({ version: String(n), script: scripts[n - 1], checksum: n * 37 }));
    assert.deepEqual(f.readRecord(f.record(manifest)), { ok: true, result: f.record(manifest) }, `Complete V${versions.at(-1)} host record`);
  }
});

test('V31C05 neither reader invents unpublished complete V24 through V30 catalogs', () => {
  for (let count = 24; count <= 30; count++) {
    const manifest = release(count);
    assert.deepEqual(f.validate(manifest), f.untrusted, `Unpublished V${count} manifest`);
    assert.deepEqual(f.readRecord(f.record(manifest)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' }, `Unpublished V${count} host record`);
  }
});

test('V31C06 publisher rejects every omitted new SQL and unknown renamed duplicate linked or nonfile entries', () => {
  for (const fault of [...range(8).map(i => `missing-${23 + i}`), 'unknown32', 'rename31', 'duplicate31', 'leadingZero31', 'directory31', 'symlink31']) {
    const value = fixture(); try {
      const latest = path.join(value.migrations, scripts[30]);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, scripts[Number(fault.slice(8)) - 1]));
      if (fault === 'unknown32') fs.copyFileSync(latest, path.join(value.migrations, 'V32__unknown.sql'));
      if (fault === 'rename31') fs.renameSync(latest, path.join(value.migrations, 'V31__unapproved.sql'));
      if (fault === 'duplicate31') fs.copyFileSync(latest, path.join(value.migrations, 'V31__duplicate.sql'));
      if (fault === 'leadingZero31') fs.renameSync(latest, path.join(value.migrations, 'V031__allow_explicit_c5_direct_luna.sql'));
      if (fault === 'directory31') { fs.unlinkSync(latest); fs.mkdirSync(latest); }
      if (fault === 'symlink31') { const target = path.join(value.directory, 'actual31.sql'); fs.renameSync(latest, target); fs.symlinkSync(target, latest); }
      assert.deepEqual(f.publish(value), f.untrusted, fault);
    } finally { value.remove(); }
  }
});

test('V31C07 both readers reject malformed new names versions rows and checksum types', () => {
  const faults = [...range(8).map(i => `rename-${23 + i}`), 'gap30', 'duplicate31', 'reverse', 'unknown32', 'leadingZero31', 'booleanChecksum', 'overflowChecksum', 'stringChecksum', 'extraField'];
  for (const fault of faults) {
    const manifest = release(); const last = manifest.migrations.at(-1);
    if (fault.startsWith('rename-')) manifest.migrations[Number(fault.slice(7)) - 1].script = `V${fault.slice(7)}__unapproved.sql`;
    if (fault === 'gap30') manifest.migrations.splice(29, 1);
    if (fault === 'duplicate31') manifest.migrations.push(clone(last));
    if (fault === 'reverse') manifest.migrations.reverse();
    if (fault === 'unknown32') manifest.migrations.push({ version: '32', script: 'V32__unknown.sql', checksum: 123 });
    if (fault === 'leadingZero31') last.version = '031';
    if (fault === 'booleanChecksum') last.checksum = true;
    if (fault === 'overflowChecksum') last.checksum = 2 ** 31;
    if (fault === 'stringChecksum') last.checksum = '1147';
    if (fault === 'extraField') last.ignored = true;
    assert.deepEqual(f.validate(manifest), f.untrusted, fault);
    assert.deepEqual(f.readRecord(f.record(manifest)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' }, fault);
  }
});

test('V31C08 V23 to V31 requires explicit guarded transition and exact complete checksum history', () => {
  const target = release(); const old = f.ready(release(23));
  assert.deepEqual(f.judge(target, old, 'automatic'), f.mode('TRANSITION_REQUIRED'));
  const disabled = { ...old, refreshRuleStates: ['DISABLED', 'DISABLED'] };
  assert.deepEqual(f.judge(target, disabled, 'deploy'), f.denied);
  assert.deepEqual(f.judge(target, disabled, 'deploy', true), f.mode('TRANSITION_APPROVED'));
  for (const states of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']]) {
    assert.deepEqual(f.judge(target, { ...disabled, refreshRuleStates: states }, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(target, { ...disabled, pendingCommands: 1 }, 'deploy', true), f.denied);
  assert.deepEqual(f.judge(target, { ...disabled, guardTrusted: false }, 'deploy', true), f.denied);
  for (const position of [0, 22, 23, 24, 25, 26, 27, 28, 29, 30]) {
    const facts = { ...f.ready(target), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    facts.history[position].checksum += 1;
    assert.deepEqual(f.judge(target, facts, 'deploy', true), f.denied, `Corrupt V${position + 1} history`);
  }
  assert.deepEqual(f.judge(target, f.ready(target), 'automatic'), f.mode('AUTO_READY'));
});

test('V31C09 packaged V31 binds every new SQL byte and preserves historical V23 archive reading', () => {
  const value = fixture(); try {
    const exact = pack(value); assert.equal(verify(value, exact).ok, true);
    for (let index = 23; index < 31; index++) {
      const corrupt = clone(exact); corrupt.migrations[index].checksum += 1;
      assert.equal(verify(value, corrupt).ok, false, `Wrong V${index + 1} packaged CRC`);
      const missing = pack(value, 31, scripts[index]); assert.equal(verify(value, missing).ok, false, `Omitted V${index + 1} packaged SQL`);
      pack(value);
    }
    for (const field of ['schemaFingerprint', 'guardFingerprint']) assert.equal(verify(value, { ...exact, [field]: hash('wrong binding') }).ok, false);
    const historical = pack(value, 23); assert.equal(verify(value, historical).ok, true, 'Unmodified historical V23 archive');
    const before = f.publish(value); assert.equal(before.ok, true);
    fs.appendFileSync(path.join(value.migrations, scripts[30]), '\n-- synthetic test-only checksum probe\n');
    const after = f.publish(value); assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 30), before.result.migrations.slice(0, 30));
    assert.notEqual(after.result.migrations[30].checksum, before.result.migrations[30].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
    const changed = pack(value);
    assert.equal(verify(value, { ...exact, archiveSha256: changed.archiveSha256 }).ok, false, 'Stale V31 schema cannot authorize changed SQL bytes');
    assert.equal(verify(value, changed).ok, true);
  } finally { value.remove(); }
});
