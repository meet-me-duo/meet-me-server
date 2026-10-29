const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, range, hash, canonical, clone, crc } = f;

// Approved public inventory, independent of every production Python catalog constant.
// Historical V17/V18/V19 manifests remain reader contracts; current publication also requires the reviewed V20.
const script18 = 'V18__align_recommendation_selection_attendance.sql';
const script19 = 'V19__add_confirmed_result_reviews.sql';
const scripts = [...f.scripts, script18, script19];
assert.equal(scripts.length, 19);
const currentScripts = [...scripts, 'V20__add_account_room_history_index.sql'];
const historicalGuard18 = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh',
  'scripts/deploy-release.sh', 'scripts/rollback-release.sh',
  'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py',
  'pipeline/host-approved-release.py', 'pipeline/run-approved-release.py',
  'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
  'runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml', 'runtime-ai-policy.json',
].sort();
const pipelineDependencies = ['pipeline/release_completion.py', 'pipeline/transition_orchestrator.py',
  'pipeline/approval_gate.py', 'pipeline/release_pipeline.py', 'transition-approval.json'];
const currentData = 'public-api-contract.json';

function fixture() {
  const value = f.fixture();
  for (const script of currentScripts) {
    const source = path.join(root, 'src/main/resources/db/migration', script);
    assert.equal(fs.existsSync(source), true, 'Every approved migration must exist; no SQL or missing behavior is synthesized');
    fs.copyFileSync(source, path.join(value.migrations, script));
  }
  fs.copyFileSync(path.join(root, 'deploy/runtime-ai-policy.json'), path.join(value.directory, 'deploy/runtime-ai-policy.json'));
  for (const name of [...pipelineDependencies, currentData]) {
    fs.copyFileSync(path.join(root, 'deploy', name), path.join(value.directory, 'deploy', name));
  }
  return value;
}

function release(version = 19) {
  const result = f.manifest();
  result.migrations = scripts.slice(0, version).map((script, index) => ({ version: String(index + 1), script, checksum: (index + 1) * 37 }));
  result.schemaFingerprint = hash(canonical(result.migrations));
  return result;
}

function schema(value, version = 19) {
  return currentScripts.slice(0, version).map((script, index) => ({ version: String(index + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }));
}

function packageRelease(value, version = 19, omitted = '') {
  const result = release(version);
  result.migrations = scripts.slice(0, version).map((script, index) => ({ version: String(index + 1), script,
    checksum: crc(fs.readFileSync(path.join(value.migrations, script))) }));
  result.schemaFingerprint = hash(canonical(schema(value, version)));
  const guardInventory = version === 18 ? historicalGuard18 : f.currentGuardFiles;
  result.guardFingerprint = hash(canonical(guardInventory.map(name => ({ path: name,
    sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) }))));
  const probe = path.join(value.directory, 'deploy/preflight/compiled'); fs.mkdirSync(probe, { recursive: true });
  fs.writeFileSync(path.join(probe, 'ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  fs.writeFileSync(path.join(value.directory, 'deploy/runtime-provider-mode'), 'gemini-luna-required\n');
  const source = String.raw`import pathlib,sys,tarfile
root=pathlib.Path(sys.argv[1]);dest=pathlib.Path(sys.argv[2]);omitted=sys.argv[3];legacy=sys.argv[4]=='18'
new_dependencies={'pipeline/release_completion.py','pipeline/transition_orchestrator.py','pipeline/approval_gate.py','pipeline/release_pipeline.py','transition-approval.json','public-api-contract.json'}
def helper_mode(info):
 if info.isfile() and info.name in ('scripts/render-runtime-env.sh','scripts/export-certificate.sh'):info.mode=0o755
 return info
with tarfile.open(dest,'w:gz') as archive:
 for item in sorted((root/'deploy').rglob('*')):
  relative=str(item.relative_to(root/'deploy'))
  if item.is_file() and relative!=omitted and (not legacy or relative not in new_dependencies):archive.add(item,arcname=relative,recursive=False,filter=helper_mode)
 for name in sys.argv[5:]:
  if name!=omitted:archive.add(root/'src/main/resources/db/migration'/name,arcname='migrations/'+name,recursive=False)
`;
  const packed = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', source, value.directory, value.archive, omitted, String(version), ...scripts.slice(0, version)], {
    encoding: 'utf8', timeout: 10000, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(packed.status, 0, 'Archive setup/import/runtime failures are invalid RED');
  assert.equal(packed.stderr, '');
  result.archiveSha256 = hash(fs.readFileSync(value.archive));
  return result;
}
const verify = (value, manifest) => f.python('_verify_package', [value.archive, manifest], 'run-approved-release.py');

function assertHistorical18Package(value, manifest) {
  const listed = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c',
    'import json,sys,tarfile\nwith tarfile.open(sys.argv[1],"r:gz") as archive:print(json.dumps(archive.getnames()))', value.archive], {
    encoding: 'utf8', timeout: 10000, env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(listed.status, 0); assert.equal(listed.stderr, '');
  const members = JSON.parse(listed.stdout);
  for (const name of [...pipelineDependencies, currentData]) assert.equal(members.includes(name), false, `Historical18 must not contain ${name}`);
  assert.equal(historicalGuard18.length, 18);
  assert.equal(manifest.guardFingerprint, hash(canonical(historicalGuard18.map(name => ({ path: name,
    sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
}

function rejectHostArchiveHashBeforeCatalog(value, manifest) {
  const directory = path.join(value.directory, 'host-bad-archive-hash'); fs.mkdirSync(directory);
  fs.copyFileSync(value.archive, path.join(directory, '.approved-release.tar.gz'));
  const source = String.raw`import importlib.util,json,pathlib,sys
module=pathlib.Path(sys.argv[1]);release=pathlib.Path(sys.argv[2]);reads=[]
s=importlib.util.spec_from_file_location('v19_host_archive_boundary',module);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
# Replace only privileged host byte transport with bounded reads of this owned fixture.
# The hash refusal and its ordering before real catalog/schema logic remain production code.
def fixture_bytes(path,maximum=512*1024):
 p=pathlib.Path(path);assert p.resolve().is_relative_to(release.resolve()) and p.is_file() and not p.is_symlink()
 data=p.read_bytes();assert len(data)<=maximum;reads.append(str(p.relative_to(release)));return data
m._bytes=fixture_bytes
try:
 m._archive(release,json.loads(sys.stdin.read()));result={'ok':True,'reads':reads}
except ValueError as error:result={'ok':False,'reason':str(error),'reads':reads}
print(json.dumps(result))
`;
  const result = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', source,
    path.join(root, 'deploy/pipeline/host-approved-release.py'), directory], {
    input: JSON.stringify(manifest), encoding: 'utf8', timeout: 10000,
    env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(result.status, 0, 'Host archive hash fixture must execute without host commands or credentials');
  assert.equal(result.stderr, '');
  assert.deepEqual(JSON.parse(result.stdout), { ok: false, reason: 'UNSAFE_HOST_RELEASE', reads: ['.approved-release.tar.gz'] });
}

test('V19R01 current publisher retains V19 in the exact latest inventory with independent checksums and both fingerprints', () => {
  const directory = path.join(root, 'src/main/resources/db/migration');
  assert.deepEqual(fs.readdirSync(directory).sort(), [...currentScripts].sort());
  const value = fixture(); try {
    const built = f.publish(value);
    assert.equal(built.ok, true, 'The complete current publication including V19 and V20 is supported');
    assert.deepEqual(built.result.migrations, currentScripts.map((script, index) => ({ version: String(index + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(built.result.schemaFingerprint, hash(canonical(schema(value, 20))));
    assert.equal(built.result.guardFingerprint, hash(canonical(f.currentGuardFiles.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    const actual = f.python('build_manifest', [root, value.archive, f.environment], 'publish_manifest.py');
    assert.equal(actual.ok, true);
    assert.deepEqual(actual.result.migrations, built.result.migrations);
    assert.equal(actual.result.schemaFingerprint, built.result.schemaFingerprint);
  } finally { value.remove(); }
});

test('V19R02 current publication refuses every omission old complete V19 unknown additions and malformed V19 names or file kinds', () => {
  for (const fault of [...range(20).map(n => `missing-${n}`), 'duplicate19', 'wrong19', 'leadingZero19', 'unknown20', 'extra', 'directory19', 'symlink19']) {
    const value = fixture(); try {
      const latest = path.join(value.migrations, script19);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, currentScripts[Number(fault.slice(8)) - 1]));
      if (fault === 'duplicate19') fs.copyFileSync(latest, path.join(value.migrations, 'V19__duplicate.sql'));
      if (fault === 'wrong19') fs.renameSync(latest, path.join(value.migrations, 'V19__unapproved.sql'));
      if (fault === 'leadingZero19') fs.renameSync(latest, path.join(value.migrations, 'V019__add_confirmed_result_reviews.sql'));
      if (fault === 'unknown20') fs.copyFileSync(latest, path.join(value.migrations, 'V20__unknown.sql'));
      if (fault === 'extra') fs.writeFileSync(path.join(value.migrations, 'notes.sql'), '-- unapproved');
      if (fault === 'directory19') { fs.unlinkSync(latest); fs.mkdirSync(latest); }
      if (fault === 'symlink19') { const target = path.join(value.directory, 'actual19.sql'); fs.renameSync(latest, target); fs.symlinkSync(target, latest); }
      assert.deepEqual(f.publish(value), f.untrusted, fault);
    } finally { value.remove(); }
  }
});

test('V19R03 both metadata readers retain all historical complete catalogs and additionally accept exact V19', () => {
  for (const versions of [...f.catalogs, range(18), range(19)]) {
    const result = release(); result.migrations = versions.map(version => ({ version: String(version), script: scripts[version - 1], checksum: version * 37 }));
    assert.equal(f.validate(result).ok, true, `V${versions.at(-1)} complete catalog`);
    assert.deepEqual(f.readRecord(f.record(result)), { ok: true, result: f.record(result) });
  }
});

test('V19R04 readers reject gapped duplicate reversed renamed unknown V20 and malformed V19 metadata without a range wildcard', () => {
  for (const fault of ['gap18', 'duplicate19', 'reversed', 'wrong19', 'leadingZero19', 'booleanChecksum', 'overflowChecksum', 'stringChecksum', 'extraField', 'unknown20']) {
    const result = release(); const last = result.migrations.at(-1);
    if (fault === 'gap18') result.migrations.splice(17, 1);
    if (fault === 'duplicate19') result.migrations.push(clone(last));
    if (fault === 'reversed') result.migrations.reverse();
    if (fault === 'wrong19') last.script = 'V19__unapproved.sql';
    if (fault === 'leadingZero19') last.version = '019';
    if (fault === 'booleanChecksum') last.checksum = true;
    if (fault === 'overflowChecksum') last.checksum = 2 ** 31;
    if (fault === 'stringChecksum') last.checksum = '703';
    if (fault === 'extraField') last.ignored = true;
    if (fault === 'unknown20') result.migrations.push({ version: '20', script: 'V20__unknown.sql', checksum: 740 });
    assert.deepEqual(f.validate(result), f.untrusted, fault);
    assert.deepEqual(f.readRecord(f.record(result)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' }, fault);
  }
  for (const field of ['schemaFingerprint', 'guardFingerprint', 'archiveSha256']) {
    const result = release(); result[field] = 'NOT-A-SHA256';
    assert.deepEqual(f.validate(result), f.untrusted, field);
    if (field !== 'archiveSha256') assert.deepEqual(f.readRecord(f.record(result)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' }, field);
  }
});

test('V19R05 V18 to V19 still requires explicit approval two disabled refresh rules and no pending commands', () => {
  const target = release(); const facts = { ...f.ready(release(18)), refreshRuleStates: ['DISABLED', 'DISABLED'] };
  assert.notEqual(target.schemaFingerprint, facts.automaticContract.schemaFingerprint);
  for (const action of ['automatic', 'preflight']) assert.deepEqual(f.judge(target, facts, action), f.mode('TRANSITION_REQUIRED'));
  assert.deepEqual(f.judge(target, facts, 'deploy'), f.denied);
  assert.deepEqual(f.judge(target, facts, 'deploy', true), f.mode('TRANSITION_APPROVED'));
  for (const states of [['ENABLED', 'ENABLED'], ['DISABLED', 'ENABLED'], ['ENABLED', 'DISABLED']]) {
    assert.deepEqual(f.judge(target, { ...facts, refreshRuleStates: states }, 'deploy', true), f.denied);
  }
  assert.deepEqual(f.judge(target, { ...facts, pendingCommands: 1 }, 'deploy', true), f.denied);
});

test('V19R06 unchanged V19 is automatic but approval cannot bypass historical or new row corruption and fingerprint drift', () => {
  const target = release(); assert.deepEqual(f.judge(target, f.ready(target), 'automatic'), f.mode('AUTO_READY'));
  for (const index of [0, 16, 17, 18]) for (const fault of ['checksum', 'script', 'failed', 'gap']) {
    const facts = { ...f.ready(target), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    if (fault === 'checksum') facts.history[index].checksum += 1;
    if (fault === 'script') facts.history[index].script = `V${index + 1}__wrong.sql`;
    if (fault === 'failed') facts.history[index].success = false;
    if (fault === 'gap') { facts.history.splice(index, 1); facts.history.push({ version: '20', script: 'V20__unknown.sql', checksum: 740, success: true }); }
    assert.deepEqual(f.judge(target, facts, 'deploy', true), f.denied, `${index + 1} ${fault}`);
  }
  for (const field of ['schemaFingerprint', 'guardFingerprint']) {
    const facts = f.ready(target); facts.automaticContract[field] = hash('different approved contract');
    assert.deepEqual(f.judge(target, facts, 'automatic'), f.mode('TRANSITION_REQUIRED'));
    assert.deepEqual(f.judge(target, facts, 'deploy'), f.denied);
  }
});

test('V19R07 V19 bytes change only the new row and schema fingerprint while V1 through V18 and guard remain unchanged', () => {
  const value = fixture(); try {
    const before = f.publish(value); assert.equal(before.ok, true);
    fs.appendFileSync(path.join(value.migrations, script19), '\n-- synthetic review checksum change\n');
    const after = f.publish(value); assert.equal(after.ok, true);
    assert.deepEqual(after.result.migrations.slice(0, 18), before.result.migrations.slice(0, 18));
    assert.notEqual(after.result.migrations[18].checksum, before.result.migrations[18].checksum);
    assert.notEqual(after.result.schemaFingerprint, before.result.schemaFingerprint);
    assert.equal(after.result.guardFingerprint, before.result.guardFingerprint);
  } finally { value.remove(); }
});

test('V19R08 offline packages accept exact historical18 and current19 but reject missing SQL or stale hashes even after repacking', () => {
  const value = fixture(); try {
    for (const version of [18, 19]) {
      const manifest = packageRelease(value, version);
      if (version === 18) assertHistorical18Package(value, manifest);
      assert.equal(verify(value, manifest).ok, true, `V${version}`);
    }
    const exact = packageRelease(value);
    for (const field of ['schemaFingerprint', 'guardFingerprint']) {
      const tampered = clone(exact); tampered[field] = hash('wrong immutable fingerprint');
      assert.equal(verify(value, tampered).ok, false, field);
    }
    // The raw archive digest belongs to the real host/driver boundary, not _verify_package's SQL/guard contract.
    rejectHostArchiveHashBeforeCatalog(value, { ...exact, archiveSha256: hash('wrong immutable archive digest') });
    const badChecksum = clone(exact); badChecksum.migrations[18].checksum += 1;
    assert.equal(verify(value, badChecksum).ok, false);
    fs.appendFileSync(path.join(value.migrations, script19), '\n-- synthetic package tamper\n');
    const changed = packageRelease(value);
    assert.equal(verify(value, { ...exact, archiveSha256: changed.archiveSha256 }).ok, false);
    for (const omitted of [scripts[0], script18, script19]) {
      const incomplete = packageRelease(value, 19, omitted);
      assert.equal(verify(value, incomplete).ok, false, omitted);
    }
  } finally { value.remove(); }
});

test('V19R09 current packages refuse every omitted pipeline dependency and readonly API data with refreshed archive hashes', () => {
  const value = fixture(); try {
    for (const omitted of [...pipelineDependencies, currentData]) {
      assert.equal(verify(value, packageRelease(value, 19, omitted)).ok, false, omitted);
    }
  } finally { value.remove(); }
});
