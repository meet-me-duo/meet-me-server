const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc } = f;

// Reviewed public SQL inventory, independent of all production catalog constants.
const scripts = [...f.scripts,
  'V18__align_recommendation_selection_attendance.sql',
  'V19__add_confirmed_result_reviews.sql',
  'V20__add_account_room_history_index.sql',
  'V21__add_submission_input_evidence.sql',
  'V22__preserve_unverified_candidate_place.sql'];
assert.equal(scripts.length, 22);

function release(count = 22) {
  const result = f.manifest();
  result.migrations = scripts.slice(0, count).map((script, i) => ({ version: String(i + 1), script, checksum: (i + 1) * 37 }));
  result.schemaFingerprint = hash(canonical(result.migrations));
  return result;
}
function fixture() {
  const value = f.currentFixture();
  for (const script of scripts) {
    const source = path.join(root, 'src/main/resources/db/migration', script);
    assert.equal(fs.existsSync(source), true, 'Real reviewed SQL is required; missing setup is not catalog RED');
    fs.copyFileSync(source, path.join(value.migrations, script));
  }
  return value;
}
function pack(value, count = 22, omitted = '') {
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
  assert.equal(packed.status, 0, 'Archive construction failure is invalid RED');
  assert.equal(packed.stderr, '');
  result.archiveSha256 = hash(fs.readFileSync(value.archive));
  return result;
}
const verify = (value, manifest) => f.python('_verify_package', [value.archive, manifest], 'run-approved-release.py');

test('V22C01 current publication binds exactly all twenty-two actual SQL CRCs and independent schema SHA', () => {
  assert.deepEqual(fs.readdirSync(path.join(root, 'src/main/resources/db/migration')).sort(), [...scripts].sort());
  const value = fixture(); try {
    const actual = f.publish(value); assert.equal(actual.ok, true, 'Exact V22 publication must be admitted');
    assert.deepEqual(actual.result.migrations, scripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(actual.result.schemaFingerprint, hash(canonical(scripts.map((script, i) => ({ version: String(i + 1), script,
      sha256: hash(fs.readFileSync(path.join(value.migrations, script))) })))));
  } finally { value.remove(); }
});

test('V22C02 both readers admit exact V22 and retain historical complete catalogs including V20 and V21', () => {
  for (const versions of [...f.catalogs, range(18), range(19), range(20), range(21), range(22)]) {
    const manifest = release();
    manifest.migrations = versions.map(n => ({ version: String(n), script: scripts[n - 1], checksum: n * 37 }));
    assert.equal(f.validate(manifest).ok, true, `Complete V${versions.at(-1)} metadata`);
    assert.deepEqual(f.readRecord(f.record(manifest)), { ok: true, result: f.record(manifest) });
  }
});

test('V22C03 publisher rejects every omitted reviewed version and unknown renamed duplicate directory or symlink SQL', () => {
  for (const fault of [...range(22).map(n => `missing-${n}`), 'unknown23', 'rename22', 'duplicate22', 'leadingZero22', 'directory22', 'symlink22']) {
    const value = fixture(); try {
      const latest = path.join(value.migrations, scripts[21]);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, scripts[Number(fault.slice(8)) - 1]));
      if (fault === 'unknown23') fs.copyFileSync(latest, path.join(value.migrations, 'V23__unknown.sql'));
      if (fault === 'rename22') fs.renameSync(latest, path.join(value.migrations, 'V22__unapproved.sql'));
      if (fault === 'duplicate22') fs.copyFileSync(latest, path.join(value.migrations, 'V22__duplicate.sql'));
      if (fault === 'leadingZero22') fs.renameSync(latest, path.join(value.migrations, 'V022__preserve_unverified_candidate_place.sql'));
      if (fault === 'directory22') { fs.unlinkSync(latest); fs.mkdirSync(latest); }
      if (fault === 'symlink22') { const target = path.join(value.directory, 'actual22.sql'); fs.renameSync(latest, target); fs.symlinkSync(target, latest); }
      assert.deepEqual(f.publish(value), f.untrusted, fault);
    } finally { value.remove(); }
  }
});

test('V22C04 both readers refuse unknown23 gapped reversed renamed and malformed V22 metadata', () => {
  for (const fault of ['unknown23', 'gap21', 'duplicate22', 'reverse', 'rename22', 'leadingZero22', 'booleanChecksum', 'overflowChecksum', 'stringChecksum', 'extraField']) {
    const manifest = release(); const last = manifest.migrations.at(-1);
    if (fault === 'unknown23') manifest.migrations.push({ version: '23', script: 'V23__unknown.sql', checksum: 851 });
    if (fault === 'gap21') manifest.migrations.splice(20, 1);
    if (fault === 'duplicate22') manifest.migrations.push(clone(last));
    if (fault === 'reverse') manifest.migrations.reverse();
    if (fault === 'rename22') last.script = 'V22__unapproved.sql';
    if (fault === 'leadingZero22') last.version = '022';
    if (fault === 'booleanChecksum') last.checksum = true;
    if (fault === 'overflowChecksum') last.checksum = 2 ** 31;
    if (fault === 'stringChecksum') last.checksum = '814';
    if (fault === 'extraField') last.ignored = true;
    assert.deepEqual(f.validate(manifest), f.untrusted, fault);
    assert.deepEqual(f.readRecord(f.record(manifest)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' }, fault);
  }
});

test('V22C05 V21 to V22 requires explicit transition and never bypasses pending commands rules or checksum corruption', () => {
  const target = release(); const old = f.ready(release(21));
  assert.deepEqual(f.judge(target, old, 'automatic'), f.mode('TRANSITION_REQUIRED'));
  const disabled = { ...old, refreshRuleStates: ['DISABLED', 'DISABLED'] };
  assert.deepEqual(f.judge(target, disabled, 'deploy'), f.denied);
  assert.deepEqual(f.judge(target, disabled, 'deploy', true), f.mode('TRANSITION_APPROVED'));
  for (const states of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']]) {
    assert.deepEqual(f.judge(target, { ...disabled, refreshRuleStates: states }, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(target, { ...disabled, pendingCommands: 1 }, 'deploy', true), f.denied);
  assert.deepEqual(f.judge(target, { ...disabled, guardTrusted: false }, 'deploy', true), f.denied);
  for (const position of [0, 19, 20, 21]) {
    const facts = { ...f.ready(target), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    facts.history[position].checksum += 1;
    assert.deepEqual(f.judge(target, facts, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(target, f.ready(target), 'automatic'), f.mode('AUTO_READY'));
});

test('V22C06 actual V22 bytes alter only its CRC and schema binding and stale packaged bytes are rejected', () => {
  const value = fixture(); try {
    const before = f.publish(value); assert.equal(before.ok, true);
    const exact = pack(value); assert.equal(verify(value, exact).ok, true);
    const badChecksum = clone(exact); badChecksum.migrations[21].checksum += 1;
    assert.equal(verify(value, badChecksum).ok, false);
    for (const field of ['schemaFingerprint', 'guardFingerprint']) assert.equal(verify(value, { ...exact, [field]: hash('wrong binding') }).ok, false);
    const incomplete = pack(value, 22, scripts[21]); assert.equal(verify(value, incomplete).ok, false);
    fs.appendFileSync(path.join(value.migrations, scripts[21]), '\n-- synthetic checksum probe\n');
    const after = f.publish(value); assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 21), before.result.migrations.slice(0, 21));
    assert.notEqual(after.result.migrations[21].checksum, before.result.migrations[21].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
    const changed = pack(value);
    assert.equal(verify(value, { ...exact, archiveSha256: changed.archiveSha256 }).ok, false);
    assert.equal(verify(value, changed).ok, true);
  } finally { value.remove(); }
});
