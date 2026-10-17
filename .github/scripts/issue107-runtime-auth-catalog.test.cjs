const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, hash, canonical, clone, publish, ready, record, judge, mode, denied } = f;

// Independent public release contract: historical14 is read-only; current17 is the only publisher.
// No constant is imported from production to build the expected catalog.
const historical14 = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh',
  'scripts/deploy-release.sh', 'scripts/rollback-release.sh',
  'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py',
  'pipeline/host-approved-release.py', 'pipeline/run-approved-release.py',
  'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
].sort();
assert.equal(historical14.length, 14, 'Frozen pre-runtime-auth oracle must remain fourteen files');
const additions = ['runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml'];
const current17 = [...historical14, ...additions].sort();
const requiredMode = 'oauth-rs256-required';
function py(action, value = {}) {
  const source = String.raw`import importlib.util,json,pathlib,sys
p=json.loads(sys.stdin.read());base=pathlib.Path(sys.argv[1]);action=p['action'];value=p['value']
def load(name):
 s=importlib.util.spec_from_file_location('runtime_auth_'+name.replace('-','_'),base/name);m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m);return m
try:
 if action=='lists':
  result={name:list(getattr(load(name),field)) for name,field in [('publish_manifest.py','GUARD_FILES'),('run-approved-release.py','CONTRACT_FILES'),('host-approved-release.py','BUNDLE')]}
 elif action=='pack':
  import tarfile
  directory=pathlib.Path(value['directory'])/'deploy'
  with tarfile.open(value['archive'],'w:gz') as archive:
   for item in sorted(directory.rglob('*')):
    if item.name != '.approved-release.tar.gz' and (item.is_file() or item.is_symlink()):archive.add(item,arcname=str(item.relative_to(directory)),recursive=False)
  result=True
 elif action=='runner':
  load('run-approved-release.py')._verify_package(pathlib.Path(value['archive']),value['manifest']);result=True
 elif action in ('installed','hostArchive'):
  m=load('host-approved-release.py');release=pathlib.Path(value['directory'])/'deploy';m.ROOT=pathlib.Path(value['directory'])/'host';m.LAUNCHER=m.ROOT/'guard/host-release-guard.sh'
  # Local file reads replace only the privileged filesystem/host-unit transport boundary.
  # Catalog selection, marker parsing, archive checks and fingerprints remain production code.
  m._bytes=lambda p,maximum=512*1024:pathlib.Path(p).read_bytes()
  m._safe=lambda p,directory=False:pathlib.Path(p)
  m._unit=lambda raw:None
  if action=='installed':result=m._installed_catalog(release)
  else:m._archive(release,value['manifest']);result=True
 else:raise AssertionError('Unknown independent harness action')
 print(json.dumps({'ok':True,'result':result}))
except ValueError as error:print(json.dumps({'ok':False,'reason':str(error)}))
`;
  const result = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', source, path.join(root, 'deploy/pipeline')], {
    input: JSON.stringify({ action, value }), encoding: 'utf8', timeout: 10_000,
    env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(result.status, 0, 'Offline adapter harness imports and fixture setup must succeed; runtime/setup errors are invalid RED');
  assert.equal(result.stderr, '', 'Offline release contract must not emit raw metadata');
  return JSON.parse(result.stdout);
}
function fixture(current = true) {
  const value = f.fixture();
  for (const name of additions.filter(name => name !== 'runtime-auth-mode')) {
    const dest = path.join(value.directory, 'deploy', name); fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(path.join(root, 'deploy', name), dest);
  }
  if (current) fs.writeFileSync(path.join(value.directory, 'deploy/runtime-auth-mode'), requiredMode);
  else fs.rmSync(path.join(value.directory, 'deploy/runtime-auth-mode'), { force: true });
  fs.writeFileSync(path.join(value.directory, 'deploy/runtime-provider-mode'), 'gemini-luna-required\n');
  const migrations = path.join(value.directory, 'deploy/migrations'); fs.mkdirSync(migrations, { recursive: true });
  for (const name of f.scripts) fs.copyFileSync(path.join(value.migrations, name), path.join(migrations, name));
  const probe = path.join(value.directory, 'deploy/preflight/compiled'); fs.mkdirSync(probe, { recursive: true });
  fs.writeFileSync(path.join(probe, 'ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  for (const [name, dest] of Object.entries({
    'scripts/host-release-guard.sh': 'host-release-guard.sh',
    'scripts/install-host-release-guard.sh': 'install-host-release-guard.sh',
    'compose.guard.yml': 'compose.guard.yml',
    'meet-me-guarded-restart.service': 'meet-me-guarded-restart.service',
  })) {
    const target = path.join(value.directory, 'host/guard', dest);fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(value.directory, 'deploy', name), target);
  }
  return value;
}
function fingerprint(value, names = current17) {
  return hash(canonical(names.map(name => ({ path: name, sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) }))));
}
function release(value, names = current17) {
  const result = f.manifest();
  result.migrations = f.scripts.map((script, i) => ({ version: String(i + 1), script,
    checksum: f.crc(fs.readFileSync(path.join(value.migrations, script))) }));
  result.schemaFingerprint = hash(canonical(f.scripts.map((script, i) => ({ version: String(i + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }))));
  result.guardFingerprint = fingerprint(value, names);
  pack(value);result.archiveSha256 = hash(fs.readFileSync(value.archive));return result;
}
function pack(value) {
  assert.deepEqual(py('pack', value), { ok: true, result: true });
  fs.copyFileSync(value.archive, path.join(value.directory, 'deploy/.approved-release.tar.gz'));
}
function assertRejected(value, label) { assert.equal(value.ok, false, label); }

test('RA01 publisher runner and host pin the same exact current seventeen-file approval catalog', () => {
  const result = py('lists');assert.equal(result.ok, true);
  for (const [name, list] of Object.entries(result.result)) assert.deepEqual([...list].sort(), current17, name);
});

test('RA02 publisher fingerprints all seventeen including runtime auth mode renderer and compose', () => {
  const value = fixture();try {
    const built = publish(value);assert.equal(built.ok, true);
    assert.equal(built.result.guardFingerprint, fingerprint(value));
    assert.notEqual(built.result.guardFingerprint, fingerprint(value, historical14));
  } finally { value.remove(); }
});

test('RA03 each runtime approval byte independently changes the fingerprint and cannot reuse automatic approval', () => {
  for (const name of additions) {
    const value = fixture();try {
      const before = publish(value);assert.equal(before.ok, true);
      fs.appendFileSync(path.join(value.directory, 'deploy', name), '\n');
      const after = publish(value);assert.equal(after.ok, true, name);
      assert.notEqual(after.result.guardFingerprint, before.result.guardFingerprint, name);
      const facts = ready(before.result);
      assert.deepEqual(judge(after.result, facts, 'automatic'), mode('TRANSITION_REQUIRED'));
      assert.deepEqual(judge(after.result, facts, 'deploy'), denied);
    } finally { value.remove(); }
  }
});

test('RA04 current publisher rejects each omitted or linked runtime approval file', () => {
  for (const name of additions) for (const fault of ['missing', 'symlink']) {
    const value = fixture();try {
      const target = path.join(value.directory, 'deploy', name);
      if (fault === 'missing') fs.unlinkSync(target);
      else { fs.renameSync(target, `${target}.actual`);fs.symlinkSync(`${target}.actual`, target); }
      assertRejected(publish(value), `${fault}:${name}`);
    } finally { value.remove(); }
  }
});

test('RA05 present unknown malformed or legacy auth markers cannot publish a new release', () => {
  for (const marker of ['', 'legacy', 'oauth-rs256-required\r\n', 'oauth-rs256-required\n\n', 'oauth-rs256-required\0', 'oauth-rs256-required-extra']) {
    const value = fixture();try {
      fs.writeFileSync(path.join(value.directory, 'deploy/runtime-auth-mode'), marker);
      assertRejected(publish(value), JSON.stringify(marker));
    } finally { value.remove(); }
  }
});

test('RA06 runner accepts current seventeen and rejects fourteen-fingerprint downgrade when auth marker is present', () => {
  const value = fixture();try {
    const current = release(value);assert.deepEqual(py('runner', { ...value, manifest: current }), { ok: true, result: true });
    assertRejected(py('runner', { ...value, manifest: { ...current, guardFingerprint: fingerprint(value, historical14) } }), 'present marker plus historical14 fingerprint');
  } finally { value.remove(); }
});

test('RA07 runner reads the exact markerless historical fourteen without retroactively requiring auth configuration', () => {
  const value = fixture(false);try {
    const old = release(value, historical14);assert.deepEqual(py('runner', { ...value, manifest: old }), { ok: true, result: true });
    fs.writeFileSync(path.join(value.directory, 'deploy/runtime-auth-mode'), requiredMode);
    const hybrid = { ...old };pack(value);hybrid.archiveSha256 = hash(fs.readFileSync(value.archive));
    assertRejected(py('runner', { ...value, manifest: hybrid }), 'marker insertion cannot retain old14 approval');
  } finally { value.remove(); }
});

test('RA08 runner rejects current marker removal unknown variants and every changed runtime file even with refreshed archive hash', () => {
  for (const fault of ['remove', 'unknown', ...additions]) {
    const value = fixture();try {
      const approved = release(value);const target = path.join(value.directory, 'deploy/runtime-auth-mode');
      if (fault === 'remove') fs.unlinkSync(target);
      else if (fault === 'unknown') fs.writeFileSync(target, 'unknown');
      else fs.appendFileSync(path.join(value.directory, 'deploy', fault), '\n');
      pack(value);approved.archiveSha256 = hash(fs.readFileSync(value.archive));
      assertRejected(py('runner', { ...value, manifest: approved }), fault);
    } finally { value.remove(); }
  }
});

test('RA09 installed host catalog reads exact historical14 and current17 while rejecting unknown marker', () => {
  for (const current of [false, true]) {
    const value = fixture(current);try {
      const actual = py('installed', value);assert.equal(actual.ok, true);
      assert.deepEqual(actual.result.map(row => row.path), current ? current17 : historical14);
      assert.equal(hash(canonical(actual.result)), fingerprint(value, current ? current17 : historical14));
      fs.writeFileSync(path.join(value.directory, 'deploy/runtime-auth-mode'), 'unknown');
      assertRejected(py('installed', value), 'unknown installed auth marker');
    } finally { value.remove(); }
  }
});

test('RA10 host archive verifies historical14 and current17 and refuses mixed fingerprint approvals', () => {
  for (const current of [false, true]) {
    const value = fixture(current);try {
      const approved = release(value, current ? current17 : historical14);
      assert.deepEqual(py('hostArchive', { ...value, manifest: approved }), { ok: true, result: true });
      const changed = { ...approved, guardFingerprint: current ? fingerprint(value, historical14) : '0'.repeat(64) };
      assertRejected(py('hostArchive', { ...value, manifest: changed }), 'mixed/unknown guard catalog');
    } finally { value.remove(); }
  }
});

test('RA11 auth guard transition still requires explicit approval two disabled rules and zero pending commands', () => {
  const value = fixture();try {
    const next = release(value), old = { ...next, guardFingerprint: fingerprint(value, historical14) };
    const facts = { ...ready(old), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    assert.deepEqual(judge(next, facts, 'automatic'), mode('TRANSITION_REQUIRED'));
    assert.deepEqual(judge(next, facts, 'deploy'), denied);
    assert.deepEqual(judge(next, facts, 'deploy', true), mode('TRANSITION_APPROVED'));
    for (const states of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']])
      assert.deepEqual(judge(next, { ...facts, refreshRuleStates: states }, 'deploy', true), denied);
    assert.deepEqual(judge(next, { ...facts, pendingCommands: 1 }, 'deploy', true), denied);
    const changed = clone(facts);changed.history[0].checksum++;assert.deepEqual(judge(next, changed, 'deploy', true), denied);
    changed.history = clone(facts.history);changed.history[15].success = false;assert.deepEqual(judge(next, changed, 'deploy', true), denied);
  } finally { value.remove(); }
});

test('RA12 V16 and original migration inventory remain immutable and auth approval cannot insert sparse history', () => {
  assert.equal(hash(fs.readFileSync(path.join(root, 'src/main/resources/db/migration/V16__add_pre_submission_checks.sql'))), '65dd8b5831ced11f62bf00eb21fc5eb61bc34356c7d99da2228b8fdd80d8358e');
  assert.deepEqual(fs.readdirSync(path.join(root, 'src/main/resources/db/migration')).sort(), [...f.scripts].sort());
  const value = fixture();try {
    const next = release(value);
    for (const versions of f.catalogs.slice(3, 6)) {
      const old = f.manifest(versions), facts = { ...ready(old), refreshRuleStates: ['DISABLED', 'DISABLED'] };
      assert.deepEqual(judge(next, facts, 'deploy', true), denied);
    }
  } finally { value.remove(); }
});


test('RA13 readers reject self-consistent unknown guard subsets and supersets instead of discovering files', () => {
  for (const names of [...additions.map(omit => current17.filter(name => name !== omit)), [...current17, 'extra-unapproved-contract']]) {
    const value = fixture();try {
      fs.writeFileSync(path.join(value.directory, 'deploy/extra-unapproved-contract'), 'synthetic unapproved contract');
      const variant = release(value, names);
      assertRejected(py('runner', { ...value, manifest: variant }), names.join(','));
      assertRejected(py('hostArchive', { ...value, manifest: variant }), names.join(','));
    } finally { value.remove(); }
  }
});

test('RA14 invalid auth marker stays rejected even when archive and seventeen-file hashes are self-consistent', () => {
  for (const marker of ['unknown', 'oauth-rs256-required\r\n', 'oauth-rs256-required\0']) {
    const value = fixture();try {
      fs.writeFileSync(path.join(value.directory, 'deploy/runtime-auth-mode'), marker);
      const variant = release(value);
      assertRejected(py('runner', { ...value, manifest: variant }), JSON.stringify(marker));
      assertRejected(py('hostArchive', { ...value, manifest: variant }), JSON.stringify(marker));
    } finally { value.remove(); }
  }
});
