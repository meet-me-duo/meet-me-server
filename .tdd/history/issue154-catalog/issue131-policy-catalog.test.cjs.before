const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, hash, canonical } = f;
// Independent fixed inventories: policy18 is unrelated to immutable SQL V1..V17.
const historical14 = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh',
  'scripts/deploy-release.sh', 'scripts/rollback-release.sh',
  'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py',
  'pipeline/host-approved-release.py', 'pipeline/run-approved-release.py',
  'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
].sort();
const historical17 = [...historical14, 'runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml'].sort();
const current18 = [...historical17, 'runtime-ai-policy.json'].sort();
const sentinel = '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}\n';
function fixture(inventory = current18) {
  const value = f.fixture();
  if (inventory.length === 18) fs.writeFileSync(path.join(value.directory, 'deploy/runtime-ai-policy.json'), sentinel);
  if (inventory.length === 14) fs.unlinkSync(path.join(value.directory, 'deploy/runtime-auth-mode'));
  const migrations = path.join(value.directory, 'deploy/migrations'); fs.mkdirSync(migrations, { recursive: true });
  for (const name of f.scripts) fs.copyFileSync(path.join(value.migrations, name), path.join(migrations, name));
  fs.mkdirSync(path.join(value.directory, 'deploy/preflight/compiled'), { recursive: true });
  fs.writeFileSync(path.join(value.directory, 'deploy/preflight/compiled/ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  fs.writeFileSync(path.join(value.directory, 'deploy/runtime-provider-mode'), 'gemini-luna-required\n');
  return value;
}
function invoke(action, value = {}) {
  const code = String.raw`import importlib.util,json,pathlib,sys,tarfile
p=json.loads(sys.stdin.read());base=pathlib.Path(sys.argv[1]);action=p['action'];v=p['value']
def load(name):
 s=importlib.util.spec_from_file_location('policy_catalog_'+name.replace('-','_'),base/name);m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m);return m
try:
 if action=='lists':result={n:list(getattr(load(n),k)) for n,k in [('publish_manifest.py','GUARD_FILES'),('run-approved-release.py','CONTRACT_FILES'),('host-approved-release.py','BUNDLE')]}
 elif action=='pack':
  directory=pathlib.Path(v['directory'])/'deploy'
  def helper_mode(info):
   if info.isfile() and info.name in ('scripts/render-runtime-env.sh','scripts/export-certificate.sh'):info.mode=0o755
   return info
  with tarfile.open(v['archive'],'w:gz') as a:
   for item in sorted(directory.rglob('*')):
    if item.name!='.approved-release.tar.gz' and (item.is_file() or item.is_symlink()):a.add(item,arcname=str(item.relative_to(directory)),recursive=False,filter=helper_mode)
  result=True
 elif action=='runner':load('run-approved-release.py')._verify_package(pathlib.Path(v['archive']),v['manifest']);result=True
 elif action in ('hostBundle','hostArchive'):
  m=load('host-approved-release.py');release=pathlib.Path(v['directory'])/'deploy'
  # Substitute privileged host file transport only; production catalog/fingerprint/schema selection remains real.
  m._bytes=lambda p,maximum=512*1024:pathlib.Path(p).read_bytes();m._safe=lambda p,directory=False:pathlib.Path(p)
  if action=='hostBundle':result=sorted(m._release_bundle(release))
  else:m._archive(release,v['manifest']);result=True
 else:raise AssertionError('Unexpected test harness action')
 print(json.dumps({'ok':True,'result':result}))
except ValueError as error:print(json.dumps({'ok':False,'reason':str(error)}))
`;
  const result = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', code, path.join(root, 'deploy/pipeline')], {
    input: JSON.stringify({ action, value }), encoding: 'utf8', timeout: 10000,
    env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(result.status, 0, 'Imports/setup/timeouts are invalid RED');
  assert.equal(result.stderr, '', 'Offline catalog checks must not emit raw metadata');
  return JSON.parse(result.stdout);
}
function release(value, inventory) {
  const result = f.manifest();
  result.migrations = f.scripts.map((script, i) => ({ version: String(i + 1), script, checksum: f.crc(fs.readFileSync(path.join(value.migrations, script))) }));
  result.schemaFingerprint = hash(canonical(f.scripts.map((script, i) => ({ version: String(i + 1), script, sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }))));
  result.guardFingerprint = hash(canonical(inventory.map(name => ({ path: name, sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) }))));
  assert.deepEqual(invoke('pack', value), { ok: true, result: true });
  result.archiveSha256 = hash(fs.readFileSync(value.archive));
  fs.copyFileSync(value.archive, path.join(value.directory, 'deploy/.approved-release.tar.gz'));
  return result;
}
test('PC01 current publisher runner and host each declare the same independently fixed eighteen files', () => {
  assert.equal(historical14.length, 14); assert.equal(historical17.length, 17); assert.equal(current18.length, 18);
  const lists = invoke('lists'); assert.equal(lists.ok, true);
  for (const list of Object.values(lists.result)) assert.deepEqual([...list].sort(), current18);
});
test('PC02 current publication fingerprints exact policy bytes without changing the approved V1 through V19 SQL catalog', () => {
  const value = f.currentFixture(); try {
    const result = f.publish(value); assert.equal(result.ok, true);
    assert.equal(result.result.guardFingerprint, hash(canonical(current18.map(name => ({ path: name, sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    assert.deepEqual(result.result.migrations.map(row => row.script), f.currentScripts);
    const previous = result.result; fs.appendFileSync(path.join(value.directory, 'deploy/runtime-ai-policy.json'), '\n');
    const after = f.publish(value); assert.equal(after.ok, true); assert.notEqual(after.result.guardFingerprint, previous.guardFingerprint);
    assert.equal(after.result.schemaFingerprint, previous.schemaFingerprint);
  } finally { value.remove(); }
});
test('PC03 new publication rejects a missing policy while historical readers remain separate', () => {
  const value = f.currentFixture(); try { fs.unlinkSync(path.join(value.directory, 'deploy/runtime-ai-policy.json')); assert.deepEqual(f.publish(value), f.untrusted); } finally { value.remove(); }
});
test('PC04 new publication rejects malformed duplicate-key oversized and unknown-field policy bytes', () => {
  for (const raw of ['[]', '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED","state":"UNCONFIGURED"}', '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED","extra":1}', ' '.repeat(65537)]) {
    const value = f.currentFixture(); try { fs.writeFileSync(path.join(value.directory, 'deploy/runtime-ai-policy.json'), raw); assert.deepEqual(f.publish(value), f.untrusted); } finally { value.remove(); }
  }
});
test('PC05 current policy must be a real regular file with no source symlink', () => {
  const value = f.currentFixture(); try {
    const file = path.join(value.directory, 'deploy/runtime-ai-policy.json'), actual = path.join(value.directory, 'policy-source');
    fs.renameSync(file, actual); fs.symlinkSync(actual, file);
    assert.deepEqual(f.publish(value), f.untrusted);
  } finally { value.remove(); }
});
for (const [name, inventory] of [['current18', current18], ['historical17', historical17], ['historical14', historical14]]) {
  test('PC06 package and host accept exact ' + name + ' bytes with independent schema and runtime fingerprints', () => {
    const value = fixture(inventory); try {
      const manifest = release(value, inventory);
      assert.deepEqual(invoke('runner', { ...value, manifest }), { ok: true, result: true });
      assert.deepEqual(invoke('hostBundle', value), { ok: true, result: inventory });
      assert.deepEqual(invoke('hostArchive', { ...value, manifest }), { ok: true, result: true });
    } finally { value.remove(); }
  });
}
test('PC07 policy mutation after approved fingerprints fails both package and installed archive readers', () => {
  const value = fixture(); try {
    const manifest = release(value, current18);
    fs.appendFileSync(path.join(value.directory, 'deploy/runtime-ai-policy.json'), '\n');
    assert.deepEqual(invoke('pack', value), { ok: true, result: true });
    manifest.archiveSha256 = hash(fs.readFileSync(value.archive)); fs.copyFileSync(value.archive, path.join(value.directory, 'deploy/.approved-release.tar.gz'));
    assert.equal(invoke('runner', { ...value, manifest }).ok, false);
    assert.equal(invoke('hostArchive', { ...value, manifest }).ok, false);
  } finally { value.remove(); }
});
