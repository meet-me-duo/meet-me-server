const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc } = f;
// Public additive contract; historical fixtures intentionally retain their V17 defaults.
const script18 = 'V18__align_recommendation_selection_attendance.sql';
const scripts = [...f.scripts, script18];
function release(version = 18) {
  const value = f.manifest(range(Math.min(version, 17)));
  if (version === 18) value.migrations.push({ version: '18', script: script18, checksum: 18 * 37 });
  value.schemaFingerprint = hash(canonical(value.migrations));
  return value;
}
function schema(value, version = 18) {
  return scripts.slice(0, version).map((script, i) => ({ version: String(i + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }));
}
function packageRelease(value, version = 18, omit = '') {
  const result = release(version);
  result.migrations = scripts.slice(0, version).map((script, i) => ({ version: String(i + 1), script,
    checksum: crc(fs.readFileSync(path.join(value.migrations, script))) }));
  result.schemaFingerprint = hash(canonical(schema(value, version)));
  result.guardFingerprint = hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
    sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) }))));
  const probe = path.join(value.directory, 'deploy/preflight/compiled'); fs.mkdirSync(probe, { recursive: true });
  fs.writeFileSync(path.join(probe, 'ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  fs.writeFileSync(path.join(value.directory, 'deploy/runtime-provider-mode'), 'gemini-luna-required\n');
  const code = String.raw`import pathlib,sys,tarfile
root=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2]);omitted=sys.argv[3]
with tarfile.open(dest,'w:gz') as archive:
 for item in sorted((root/'deploy').rglob('*')):
  if item.is_file():archive.add(item,arcname=str(item.relative_to(root/'deploy')),recursive=False)
 for name in sys.argv[4:]:
  if name!=omitted:archive.add(root/'src/main/resources/db/migration'/name,arcname='migrations/'+name,recursive=False)
`;
  const packed = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', code, value.directory, value.archive, omit, ...scripts.slice(0, version)], {
    encoding: 'utf8', timeout: 5000, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(packed.status, 0, 'Local package setup failure is invalid RED');
  assert.equal(packed.stderr, '');
  result.archiveSha256 = hash(fs.readFileSync(value.archive));
  return result;
}
const verify = (value, manifest) => f.python('_verify_package', [value.archive, manifest], 'run-approved-release.py');

test('V18C01 publisher emits exactly V1 through V18 with independent SQL checksums and schema fingerprint', () => {
  assert.deepEqual(f.currentScripts, scripts);
  const value = f.currentFixture(); try {
    const result = f.publish(value); assert.equal(result.ok, true);
    assert.deepEqual(result.result.migrations, scripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(result.result.schemaFingerprint, hash(canonical(schema(value))));
    assert.equal(result.result.guardFingerprint, hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
  } finally { value.remove(); }
});

test('V18C02 current publication rejects old V17 omissions duplicate or malformed V18 and unknown V19', () => {
  for (const fault of [...range(18).map(v => `missing-${v}`), 'duplicate18', 'wrong18', 'leadingZero18', 'unknown19', 'directory18', 'symlink18']) {
    const value = f.currentFixture(); try {
      const latest = path.join(value.migrations, script18);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, scripts[Number(fault.slice(8)) - 1]));
      if (fault === 'duplicate18') fs.copyFileSync(latest, path.join(value.migrations, 'V18__duplicate.sql'));
      if (fault === 'wrong18') fs.renameSync(latest, path.join(value.migrations, 'V18__arbitrary.sql'));
      if (fault === 'leadingZero18') fs.renameSync(latest, path.join(value.migrations, 'V018__align_recommendation_selection_attendance.sql'));
      if (fault === 'unknown19') fs.copyFileSync(latest, path.join(value.migrations, 'V19__unknown.sql'));
      if (fault === 'directory18') { fs.unlinkSync(latest); fs.mkdirSync(latest); }
      if (fault === 'symlink18') { const target = path.join(value.directory, 'actual18.sql'); fs.renameSync(latest, target); fs.symlinkSync(target, latest); }
      assert.deepEqual(f.publish(value), f.untrusted, fault);
    } finally { value.remove(); }
  }
});

test('V18C03 both readers preserve historical V17 and admit exact V18 without weakening malformed-row rejection', () => {
  for (const version of [17, 18]) {
    const value = release(version);
    assert.equal(f.validate(value).ok, true);
    assert.deepEqual(f.readRecord(f.record(value)), { ok: true, result: f.record(value) });
  }
  for (const fault of ['gap17', 'duplicate18', 'reversed', 'wrong18', 'leadingZero18', 'boolean', 'overflow', 'extra', 'unknown19']) {
    const value = release(); const last = value.migrations.at(-1);
    if (fault === 'gap17') value.migrations.splice(16, 1);
    if (fault === 'duplicate18') value.migrations.push(clone(last));
    if (fault === 'reversed') value.migrations.reverse();
    if (fault === 'wrong18') last.script = 'V18__arbitrary.sql';
    if (fault === 'leadingZero18') last.version = '018';
    if (fault === 'boolean') last.checksum = true;
    if (fault === 'overflow') last.checksum = 2 ** 31;
    if (fault === 'extra') last.ignored = true;
    if (fault === 'unknown19') { last.version = '19'; last.script = 'V19__unknown.sql'; }
    assert.deepEqual(f.validate(value), f.untrusted, fault);
    assert.deepEqual(f.readRecord(f.record(value)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' }, fault);
  }
});

test('V18C04 V17 to V18 requires explicit approval both refresh rules disabled and no pending command', () => {
  const value = release(); const facts = { ...f.ready(release(17)), refreshRuleStates: ['DISABLED', 'DISABLED'] };
  assert.notEqual(value.schemaFingerprint, facts.automaticContract.schemaFingerprint);
  for (const action of ['automatic', 'preflight']) assert.deepEqual(f.judge(value, facts, action), f.mode('TRANSITION_REQUIRED'));
  assert.deepEqual(f.judge(value, facts, 'deploy'), f.denied);
  assert.deepEqual(f.judge(value, facts, 'deploy', true), f.mode('TRANSITION_APPROVED'));
  for (const states of [['ENABLED', 'ENABLED'], ['DISABLED', 'ENABLED'], ['ENABLED', 'DISABLED']])
    assert.deepEqual(f.judge(value, { ...facts, refreshRuleStates: states }, 'deploy', true), f.denied);
  assert.deepEqual(f.judge(value, { ...facts, pendingCommands: 1 }, 'deploy', true), f.denied);
});

test('V18C05 exact V18 stays automatic but approval cannot bypass V17 or V18 history tampering', () => {
  const value = release(); assert.deepEqual(f.judge(value, f.ready(value), 'automatic'), f.mode('AUTO_READY'));
  for (const index of [16, 17]) for (const fault of ['checksum', 'script', 'failed', 'gap']) {
    const facts = { ...f.ready(value), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    if (fault === 'checksum') facts.history[index].checksum += 1;
    if (fault === 'script') facts.history[index].script = `V${index + 1}__wrong.sql`;
    if (fault === 'failed') facts.history[index].success = false;
    if (fault === 'gap') { facts.history.splice(index, 1); facts.history.push({ version: '19', script: 'V19__unknown.sql', checksum: 19, success: true }); }
    assert.deepEqual(f.judge(value, facts, 'deploy', true), f.denied, `${index + 1} ${fault}`);
  }
});

test('V18C06 V18 SQL changes only its checksum and schema fingerprint while V1 through V17 stay immutable', () => {
  const value = f.currentFixture(); try {
    assert.equal(hash(fs.readFileSync(path.join(value.migrations, f.scripts[16]))), '912b8f227e6ea7c20fc05cf96d705982fb9737c5051c7c07e771494df482834b');
    const before = f.publish(value); assert.equal(before.ok, true);
    fs.appendFileSync(path.join(value.migrations, script18), '\n-- synthetic V18 byte change\n');
    const after = f.publish(value); assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 17), before.result.migrations.slice(0, 17));
    assert.notEqual(after.result.migrations[17].checksum, before.result.migrations[17].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
  } finally { value.remove(); }
});

test('V18C07 offline packages preserve V17 support and verify V18 checksum schema and complete SQL inventory', () => {
  const value = f.currentFixture(); try {
    for (const version of [17, 18]) assert.equal(verify(value, packageRelease(value, version)).ok, true, `V${version}`);
    const exact = packageRelease(value);
    const wrongChecksum = clone(exact); wrongChecksum.migrations[17].checksum += 1;
    assert.equal(verify(value, wrongChecksum).ok, false);
    fs.appendFileSync(path.join(value.migrations, script18), '\n-- package byte tamper\n');
    const repacked = packageRelease(value);
    assert.equal(verify(value, { ...exact, archiveSha256: repacked.archiveSha256 }).ok, false, 'Fresh archive hash must not conceal stale SQL checksum/schema');
    const omitted = packageRelease(value, 18, script18);
    assert.equal(verify(value, omitted).ok, false, 'Even a matching archive hash cannot excuse missing V18');
    const gap = packageRelease(value, 18, scripts[16]);
    assert.equal(verify(value, gap).ok, false, 'V18 package cannot omit historical V17');
  } finally { value.remove(); }
});

test('V18C08 actual source publishes exactly real V18 without the synthetic RED fixture', () => {
  const directory = path.join(root, 'src/main/resources/db/migration');
  assert.deepEqual(fs.readdirSync(directory).sort(), [...scripts].sort());
  const value = f.currentFixture(); try {
    const result = f.python('build_manifest', [root, value.archive, f.environment], 'publish_manifest.py');
    assert.equal(result.ok, true);
    assert.deepEqual(result.result.migrations, scripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(directory, script))) })));
  } finally { value.remove(); }
});
