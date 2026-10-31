const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc } = f;

// Independent candidate inventory. Never import production catalog constants.
// Copy this test into the archive checkout's .github/scripts/ for execution.
const script18 = 'V18__align_recommendation_selection_attendance.sql';
const script19 = 'V19__add_confirmed_result_reviews.sql';
const script20 = 'V20__add_account_room_history_index.sql';
const scripts = [...f.scripts, script18, script19, script20];
assert.equal(scripts.length, 20);
const currentScripts = [...scripts, 'V21__add_submission_input_evidence.sql', 'V22__preserve_unverified_candidate_place.sql'];

function release(version = 20) {
  const result = f.manifest();
  result.migrations = scripts.slice(0, version).map((script, i) => ({ version: String(i + 1), script, checksum: (i + 1) * 37 }));
  result.schemaFingerprint = hash(canonical(result.migrations));
  return result;
}
function fixture() {
  const value = f.currentFixture();
  for (const script of scripts.slice(17)) {
    const source = path.join(root, 'src/main/resources/db/migration', script);
    assert.equal(fs.existsSync(source), true, 'Publication tests require real reviewed migration bytes; missing test setup is not a catalog RED');
    fs.copyFileSync(source, path.join(value.migrations, script));
  }
  fs.copyFileSync(path.join(root, 'deploy/runtime-ai-policy.json'), path.join(value.directory, 'deploy/runtime-ai-policy.json'));
  return value;
}
function schema(value, version = 22) {
  return currentScripts.slice(0, version).map((script, i) => ({ version: String(i + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }));
}
function packageRelease(value, version = 20, omitted = '') {
  const result = release(version);
  result.migrations = scripts.slice(0, version).map((script, i) => ({ version: String(i + 1), script,
    checksum: crc(fs.readFileSync(path.join(value.migrations, script))) }));
  result.schemaFingerprint = hash(canonical(schema(value, version)));
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
  const packed = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', source, value.directory, value.archive, omitted, ...scripts.slice(0, version)], {
    encoding: 'utf8', timeout: 10000, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(packed.status, 0, 'Archive setup/import/runtime failure is invalid RED');
  assert.equal(packed.stderr, '');
  result.archiveSha256 = hash(fs.readFileSync(value.archive));
  return result;
}
const verify = (value, manifest) => f.python('_verify_package', [value.archive, manifest], 'run-approved-release.py');

test('V20R01 actual candidate publisher retains V20 in exact V22 and independently binds CRC SQL SHA and guard SHA', () => {
  assert.deepEqual(fs.readdirSync(path.join(root, 'src/main/resources/db/migration')).sort(), [...currentScripts].sort());
  const value = fixture(); try {
    const built = f.publish(value); assert.equal(built.ok, true, 'Current exact V22 candidate publication must be supported');
    assert.deepEqual(built.result.migrations, currentScripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(built.result.schemaFingerprint, hash(canonical(schema(value))));
    assert.equal(built.result.guardFingerprint, hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    const actual = f.python('build_manifest', [root, value.archive, f.environment], 'publish_manifest.py');
    assert.equal(actual.ok, true);
    assert.deepEqual(actual.result.migrations, built.result.migrations);
    assert.equal(actual.result.schemaFingerprint, built.result.schemaFingerprint);
  } finally { value.remove(); }
});

test('V20R02 current publication rejects any missing version and malformed V20 or unknown V21 identity', () => {
  for (const fault of [...range(22).map(n => `missing-${n}`), 'duplicate20', 'wrong20', 'leadingZero20', 'unknown21', 'extra', 'directory20', 'symlink20']) {
    const value = fixture(); try {
      const latest = path.join(value.migrations, script20);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, currentScripts[Number(fault.slice(8)) - 1]));
      if (fault === 'duplicate20') fs.copyFileSync(latest, path.join(value.migrations, 'V20__duplicate.sql'));
      if (fault === 'wrong20') fs.renameSync(latest, path.join(value.migrations, 'V20__unapproved.sql'));
      if (fault === 'leadingZero20') fs.renameSync(latest, path.join(value.migrations, 'V020__add_account_room_history_index.sql'));
      if (fault === 'unknown21') fs.copyFileSync(latest, path.join(value.migrations, 'V21__unknown.sql'));
      if (fault === 'extra') fs.writeFileSync(path.join(value.migrations, 'notes.sql'), '-- unapproved');
      if (fault === 'directory20') { fs.unlinkSync(latest); fs.mkdirSync(latest); }
      if (fault === 'symlink20') { const target = path.join(value.directory, 'actual20.sql'); fs.renameSync(latest, target); fs.symlinkSync(target, latest); }
      assert.deepEqual(f.publish(value), f.untrusted, fault);
    } finally { value.remove(); }
  }
});

test('V20R03 both readers retain every historical complete catalog including19 and accept exact20', () => {
  for (const versions of [...f.catalogs, range(18), range(19), range(20)]) {
    const value = release();
    value.migrations = versions.map(n => ({ version: String(n), script: scripts[n - 1], checksum: n * 37 }));
    assert.equal(f.validate(value).ok, true, `V${versions.at(-1)} exact metadata`);
    assert.deepEqual(f.readRecord(f.record(value)), { ok: true, result: f.record(value) });
  }
});

test('V20R04 readers reject unknown21 gapped duplicate reversed renamed and malformed V20 metadata', () => {
  for (const fault of ['gap19', 'duplicate20', 'reversed', 'wrong20', 'leadingZero20', 'booleanChecksum', 'overflowChecksum', 'stringChecksum', 'extraField', 'unknown21']) {
    const value = release(); const last = value.migrations.at(-1);
    if (fault === 'gap19') value.migrations.splice(18, 1);
    if (fault === 'duplicate20') value.migrations.push(clone(last));
    if (fault === 'reversed') value.migrations.reverse();
    if (fault === 'wrong20') last.script = 'V20__unapproved.sql';
    if (fault === 'leadingZero20') last.version = '020';
    if (fault === 'booleanChecksum') last.checksum = true;
    if (fault === 'overflowChecksum') last.checksum = 2 ** 31;
    if (fault === 'stringChecksum') last.checksum = '740';
    if (fault === 'extraField') last.ignored = true;
    if (fault === 'unknown21') value.migrations.push({ version: '21', script: 'V21__unknown.sql', checksum: 777 });
    assert.deepEqual(f.validate(value), f.untrusted, fault);
    assert.deepEqual(f.readRecord(f.record(value)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' }, fault);
  }
});

test('V20R05 nineteen to twenty requires transition and retains explicit approval disabled rules and pending-command refusals', () => {
  const target = release(); const old = f.ready(release(19));
  assert.notEqual(target.schemaFingerprint, old.automaticContract.schemaFingerprint);
  for (const action of ['automatic', 'preflight']) for (const approved of [false, true]) {
    assert.deepEqual(f.judge(target, old, action, approved), f.mode('TRANSITION_REQUIRED'));
  }
  const facts = { ...old, refreshRuleStates: ['DISABLED', 'DISABLED'] };
  assert.deepEqual(f.judge(target, facts, 'deploy'), f.denied);
  assert.deepEqual(f.judge(target, facts, 'deploy', true), f.mode('TRANSITION_APPROVED'));
  for (const states of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']]) {
    assert.deepEqual(f.judge(target, { ...facts, refreshRuleStates: states }, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(target, { ...facts, pendingCommands: 1 }, 'deploy', true), f.denied);
  assert.deepEqual(f.judge(target, { ...facts, guardTrusted: false }, 'deploy', true), f.denied);
});

test('V20R06 exact ready20 is automatic but approval never bypasses old or new history corruption', () => {
  const target = release(); assert.deepEqual(f.judge(target, f.ready(target), 'automatic'), f.mode('AUTO_READY'));
  for (const i of [0, 18, 19]) for (const fault of ['checksum', 'script', 'failed', 'gap']) {
    const facts = { ...f.ready(target), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    if (fault === 'checksum') facts.history[i].checksum += 1;
    if (fault === 'script') facts.history[i].script = `V${i + 1}__wrong.sql`;
    if (fault === 'failed') facts.history[i].success = false;
    if (fault === 'gap') {
      facts.history.splice(i, 1);
      facts.history.push({ version: '21', script: 'V21__unknown.sql', checksum: 777, success: true });
    }
    assert.deepEqual(f.judge(target, facts, 'deploy', true), f.denied, `${i + 1} ${fault}`);
  }
  for (const field of ['schemaFingerprint', 'guardFingerprint']) {
    const facts = f.ready(target); facts.automaticContract[field] = hash('different installed contract');
    assert.deepEqual(f.judge(target, facts, 'automatic'), f.mode('TRANSITION_REQUIRED'));
    assert.deepEqual(f.judge(target, facts, 'deploy'), f.denied);
  }
});

test('V20R07 changing only new migration bytes changes its CRC and schema fingerprint while historical bytes and guard stay fixed', () => {
  const value = fixture(); try {
    const before = f.publish(value); assert.equal(before.ok, true);
    fs.appendFileSync(path.join(value.migrations, script20), '\n-- synthetic checksum probe\n');
    const after = f.publish(value); assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 19), before.result.migrations.slice(0, 19));
    assert.notEqual(after.result.migrations[19].checksum, before.result.migrations[19].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
  } finally { value.remove(); }
});

test('V20R08 offline packages retain historical19 and admit20 but reject V20 omission checksum or stale fingerprints', () => {
  const value = fixture(); try {
    for (const version of [19, 20]) assert.equal(verify(value, packageRelease(value, version)).ok, true);
    const exact = packageRelease(value);
    const badChecksum = clone(exact); badChecksum.migrations[19].checksum += 1;
    assert.equal(verify(value, badChecksum).ok, false);
    for (const field of ['schemaFingerprint', 'guardFingerprint']) {
      assert.equal(verify(value, { ...exact, [field]: hash('wrong fingerprint') }).ok, false);
    }
    const incomplete = packageRelease(value, 20, script20);
    assert.equal(verify(value, incomplete).ok, false);
    fs.appendFileSync(path.join(value.migrations, script20), '\n-- synthetic repack tamper\n');
    const changed = packageRelease(value);
    assert.equal(verify(value, { ...exact, archiveSha256: changed.archiveSha256 }).ok, false);
  } finally { value.remove(); }
});
