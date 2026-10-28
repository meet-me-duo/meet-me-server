// Independent issue154 complete execution inventory; never read production constants as an oracle.
const assert = require('node:assert/strict');
const test = require('node:test');
const cp = require('node:child_process');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, hash, canonical } = f;
const historical18 = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh',
  'scripts/deploy-release.sh', 'scripts/rollback-release.sh',
  'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py',
  'pipeline/host-approved-release.py', 'pipeline/run-approved-release.py',
  'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
  'runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml', 'runtime-ai-policy.json',
].sort();
const newDependencies = ['pipeline/release_completion.py', 'pipeline/transition_orchestrator.py',
  'pipeline/approval_gate.py', 'pipeline/release_pipeline.py', 'transition-approval.json'];
const current23 = [...historical18, ...newDependencies].sort();
const gate = '{"protocol":"meet-me-transition-gate-policy-v1","state":"UNCONFIGURED"}\n';

function fixture() {
  const value = f.currentFixture();
  for (const name of newDependencies) {
    const target = path.join(value.directory, 'deploy', name); fs.mkdirSync(path.dirname(target), { recursive: true });
    if (name === 'transition-approval.json') fs.writeFileSync(target, gate);
    else fs.copyFileSync(path.join(root, 'deploy', name), target);
  }
  return value;
}

function invoke(action, value) {
  const code = String.raw`import importlib.util,json,pathlib,sys,tarfile
p=json.loads(sys.stdin.read());base=pathlib.Path(sys.argv[1]);value=p['value'];root=pathlib.Path(value['directory'])/'deploy'
def load(name):
 s=importlib.util.spec_from_file_location('issue154_archive_'+name.replace('-','_'),base/name);m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m);return m
try:
 if p['action']=='pack':
  def mode(info):
   if info.isfile() and info.name in ('scripts/render-runtime-env.sh','scripts/export-certificate.sh'):info.mode=0o755
   return info
  with tarfile.open(value['archive'],'w:gz') as archive:
   for item in sorted(root.rglob('*')):
    if item.is_file() and item.name!='.approved-release.tar.gz':archive.add(item,arcname=str(item.relative_to(root)),recursive=False,filter=mode)
  result=True
 elif p['action']=='runner':result=load('run-approved-release.py')._verify_package(pathlib.Path(value['archive']),value['manifest'])
 elif p['action']=='host':
  m=load('host-approved-release.py');m._bytes=lambda path,maximum=512*1024:pathlib.Path(path).read_bytes();m._safe=lambda path,directory=False:pathlib.Path(path)
  m._archive(root,value['manifest']);result=True
 else:raise AssertionError('bad fixture action')
 print(json.dumps({'ok':True,'result':result}))
except ValueError as error:print(json.dumps({'ok':False,'reason':str(error)}))
`;
  const r = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', code, path.join(root, 'deploy/pipeline')], {
    input: JSON.stringify({ action, value }), encoding: 'utf8', timeout: 10000,
    env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(r.status, 0, 'fixture imports/setup must work; missing behaviors must fail assertions');
  assert.equal(r.stderr, ''); return JSON.parse(r.stdout);
}

function archiveManifest(value, inventory = current23) {
  const directory = path.join(value.directory, 'deploy'), migrations = path.join(directory, 'migrations');
  fs.mkdirSync(migrations, { recursive: true });
  for (const name of f.currentScripts) fs.copyFileSync(path.join(value.migrations, name), path.join(migrations, name));
  fs.mkdirSync(path.join(directory, 'preflight/compiled'), { recursive: true });
  fs.writeFileSync(path.join(directory, 'preflight/compiled/ReadOnlyFlywayProbe.class'), Buffer.from('cafebabe', 'hex'));
  fs.writeFileSync(path.join(directory, 'runtime-provider-mode'), 'gemini-luna-required\n');
  const manifest = f.manifest();
  manifest.migrations = f.currentScripts.map((script, i) => ({ version: String(i + 1), script,
    checksum: f.crc(fs.readFileSync(path.join(value.migrations, script))) }));
  manifest.schemaFingerprint = hash(canonical(f.currentScripts.map((script, i) => ({ version: String(i + 1), script,
    sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }))));
  manifest.guardFingerprint = hash(canonical(inventory.map(name => ({ path: name,
    sha256: hash(fs.readFileSync(path.join(directory, name))) }))));
  assert.equal(invoke('pack', value).ok, true);
  manifest.archiveSha256 = hash(fs.readFileSync(value.archive));
  fs.copyFileSync(value.archive, path.join(directory, '.approved-release.tar.gz'));
  return manifest;
}

test('I154-C01 current publication fingerprints all twenty-three reviewed execution files', () => {
  const value = fixture(); try {
    const result = f.publish(value); assert.equal(result.ok, true);
    assert.equal(current23.length, 23);
    assert.equal(result.result.guardFingerprint, hash(canonical(current23.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    assert.deepEqual(result.result.migrations.map(row => row.script), f.currentScripts);
  } finally { value.remove(); }
});

test('I154-C02 new publication cannot silently fall back when any new execution dependency is missing', () => {
  for (const name of newDependencies) {
    const value = fixture(); try {
      fs.unlinkSync(path.join(value.directory, 'deploy', name));
      assert.deepEqual(f.publish(value), f.untrusted, name);
    } finally { value.remove(); }
  }
});

test('I154-C03 runner and host accept the exact new archive with all independent fingerprints', () => {
  const value = fixture(); try {
    const manifest = archiveManifest(value);
    assert.equal(invoke('runner', { ...value, manifest }).ok, true);
    assert.equal(invoke('host', { ...value, manifest }).ok, true);
  } finally { value.remove(); }
});

test('I154-C04 partial new archives cannot be admitted as historical eighteen-file contracts', () => {
  for (const omitted of newDependencies) {
    const value = fixture(); try {
      const manifest = archiveManifest(value);
      fs.unlinkSync(path.join(value.directory, 'deploy', omitted));
      assert.equal(invoke('pack', value).ok, true);
      manifest.archiveSha256 = hash(fs.readFileSync(value.archive));
      fs.copyFileSync(value.archive, path.join(value.directory, 'deploy/.approved-release.tar.gz'));
      assert.equal(invoke('runner', { ...value, manifest }).ok, false, omitted);
      assert.equal(invoke('host', { ...value, manifest }).ok, false, omitted);
    } finally { value.remove(); }
  }
});

test('I154-C05 fully historical archives remain readable with their original eighteen-file fingerprints', () => {
  const value = fixture(); try {
    for (const name of newDependencies) fs.unlinkSync(path.join(value.directory, 'deploy', name));
    const manifest = archiveManifest(value, historical18);
    assert.equal(invoke('runner', { ...value, manifest }).ok, true);
    assert.equal(invoke('host', { ...value, manifest }).ok, true);
  } finally { value.remove(); }
});
