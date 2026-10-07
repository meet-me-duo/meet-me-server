const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const test = require('node:test');

// Issue #99: release build publication and explicitly approved consumption.
// Only source contracts and pure Python functions run on synthetic metadata.
const root = path.resolve(__dirname, '../..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const repository = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me';
const sourceSha = 'a'.repeat(40);
const imageDigest = `sha256:${'b'.repeat(64)}`;
const archiveSha256 = 'c'.repeat(64);

function pythonCall(name, args, module = 'approved_release.py') {
  const code = `import importlib.util,json,sys,pathlib\nsys.path.insert(0,str(pathlib.Path(sys.argv[1]).parent))\ns=importlib.util.spec_from_file_location('pipeline_contract',sys.argv[1])\nm=importlib.util.module_from_spec(s)\nsys.modules[s.name]=m\ns.loader.exec_module(m)\npayload=json.loads(sys.stdin.read())\ntry:\n result=getattr(m,payload['name'])(*payload['args'])\n print(json.dumps({'ok':True,'result':result}))\nexcept ValueError as error:\n print(json.dumps({'ok':False,'reason':str(error)}))\n`;
  const result = cp.spawnSync('python3', ['-c', code, path.join(root, 'deploy/pipeline', module)], {
    input: JSON.stringify({ name, args }), encoding: 'utf8', timeout: 5_000,
    env: { PATH: process.env.PATH, HOME: os.tmpdir(), PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(result.status, 0, 'Pure release validation must execute without main, credentials or external commands');
  assert.equal(result.stderr, '', 'Manifest validation must not emit raw metadata or diagnostics');
  return JSON.parse(result.stdout);
}

function archiveFixture(entries) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-release-archive-'));
  const archive = path.join(directory, 'release.tar.gz');
  const code = `import io,json,sys,tarfile\nwith tarfile.open(sys.argv[1],'w:gz') as archive:\n for entry in json.loads(sys.stdin.read()):\n  item=tarfile.TarInfo(entry['name']);kind=entry.get('kind','file')\n  data=entry.get('content','fixture').encode()\n  if kind=='dir':item.type=tarfile.DIRTYPE\n  elif kind=='symlink':item.type=tarfile.SYMTYPE;item.linkname=entry.get('link','target')\n  elif kind=='hardlink':item.type=tarfile.LNKTYPE;item.linkname=entry.get('link','target')\n  elif kind=='fifo':item.type=tarfile.FIFOTYPE\n  else:item.size=len(data)\n  archive.addfile(item,io.BytesIO(data) if kind=='file' else None)\n`;
  const created = cp.spawnSync('python3', ['-c', code, archive], { input: JSON.stringify(entries), encoding: 'utf8', timeout: 5_000, env: { PATH: process.env.PATH, PYTHONDONTWRITEBYTECODE: '1' } });
  assert.equal(created.status, 0, 'Synthetic tar metadata must be constructible without external services');
  return { archive, remove() { fs.rmSync(directory, { recursive: true, force: true }); } };
}

function job(name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = read('.github/workflows/deploy.yml').match(new RegExp(`^  ${escaped}:\\s*\\n([\\s\\S]*?)(?=^  [A-Za-z][\\w-]*:|$(?![\\s\\S]))`, 'm'));
  assert.ok(match, 'Automatic publication and manual release consumption must have separate gated jobs');
  return match[0];
}

const migrations = Array.from({ length: 10 }, (_, index) => ({ version: String(index + 1), script: `V${index + 1}__fixture.sql`, checksum: (index + 1) * 37 }));
const manifest = {
  protocol: 'meet-me-approved-release-v1', sourceSha, buildRunId: '12345', buildRunAttempt: '2',
  imageRepository: repository, imageDigest, releaseId: `${sourceSha}-12345-2`, archiveSha256,
  schemaFingerprint: 'd'.repeat(64), guardFingerprint: 'e'.repeat(64), migrations,
  origin: { repository: 'meet-me-duo/meet-me-server', ref: 'refs/heads/main', workflow: 'CI', event: 'push', conclusion: 'success', ciRunId: '67890', ciSha: sourceSha },
};
const expected = { buildRunId: '12345', buildRunAttempt: '2', imageRepository: repository };
const history = migrations.map(row => ({ ...row, success: true }));
const oldImage = `${repository}@sha256:${'f'.repeat(64)}`;
const record = {
  protocol: 'meet-me-automatic-release-v1', schemaFingerprint: manifest.schemaFingerprint,
  guardFingerprint: manifest.guardFingerprint, history, currentImage: oldImage,
  sourceSha: '9'.repeat(40), buildRunId: '100', buildRunAttempt: '1',
};
const ready = { history, phase: 'READY', currentImage: oldImage, guardTrusted: true, automaticContract: record, refreshRuleStates: ['ENABLED', 'ENABLED'], pendingCommands: 0 };
const first = { history: history.slice(0, 7), phase: 'UNINSTALLED', currentImage: oldImage, guardTrusted: false, automaticContract: null, refreshRuleStates: ['DISABLED', 'DISABLED'], pendingCommands: 0 };
const copy = value => JSON.parse(JSON.stringify(value));
function rejected(name, args, reason) { assert.deepEqual(pythonCall(name, args), { ok: false, reason }); }

test('immutable main release metadata binds run attempt SHA digest repository archive and exact migration catalog', () => {
  assert.deepEqual(pythonCall('validate_manifest', [manifest, expected]), { ok: true, result: { ...manifest, imageRef: `${repository}@${imageDigest}` } });
  const invalid = [
    { ...manifest, extra: 'unexpected' }, { ...manifest, buildRunId: '12346' }, { ...manifest, buildRunAttempt: '1' },
    { ...manifest, buildRunId: true }, { ...manifest, buildRunAttempt: '0' }, { ...manifest, sourceSha: 'HEAD;command' },
    { ...manifest, imageRepository: `${repository}-other` }, { ...manifest, imageDigest: 'latest' },
    { ...manifest, releaseId: `${sourceSha}-12345` }, { ...manifest, archiveSha256: '../archive' },
    { ...manifest, schemaFingerprint: 'bad' }, { ...manifest, guardFingerprint: 'bad' },
    { ...manifest, migrations: migrations.slice(0, 9) }, { ...manifest, migrations: [...migrations].reverse() },
    { ...manifest, migrations: [{ ...migrations[0], checksum: true }, ...migrations.slice(1)] },
    { ...manifest, migrations: [{ ...migrations[0], checksum: 2147483648 }, ...migrations.slice(1)] },
    { ...manifest, migrations: [{ ...migrations[0], script: 'V1__../../unsafe.sql' }, ...migrations.slice(1)] },
  ];
  for (const field of ['repository', 'ref', 'workflow', 'event', 'conclusion', 'ciRunId', 'ciSha']) {
    invalid.push({ ...manifest, origin: { ...manifest.origin, [field]: field === 'ciSha' ? '0'.repeat(40) : 'untrusted' } });
  }
  invalid.push({ ...manifest, origin: { ...manifest.origin, extra: 'unexpected' } });
  for (const value of invalid) rejected('validate_manifest', [value, expected], 'UNTRUSTED_RELEASE');
  for (const field of ['buildRunId', 'buildRunAttempt', 'imageRepository']) rejected('validate_manifest', [manifest, { ...expected, [field]: 'different' }], 'UNTRUSTED_RELEASE');
  const fixture = archiveFixture([{ name: 'scripts', kind: 'dir' }, { name: 'scripts/fixture.sh', content: 'fixture\n' }]);
  try {
    const head = cp.spawnSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8', env: { PATH: process.env.PATH } });
    assert.equal(head.status, 0);
    const sha = head.stdout.trim();
    const built = pythonCall('build_manifest', [root, fixture.archive, { IMAGE_DIGEST: imageDigest, CI_RUN_ID: '67890', CI_SHA: sha, SOURCE_SHA: sha, BUILD_RUN_ID: '12345', BUILD_RUN_ATTEMPT: '2', ECR_REPOSITORY_URL: repository }], 'publish_manifest.py');
    assert.equal(built.ok, true, 'The publisher must derive release metadata from the actual source catalog');
    assert.equal(built.result.sourceSha, sha);
    assert.equal(built.result.archiveSha256, crypto.createHash('sha256').update(fs.readFileSync(fixture.archive)).digest('hex'));
    assert.deepEqual(built.result.migrations.map(row => row.version), migrations.map(row => row.version));
    assert.deepEqual(built.result.migrations.map(row => row.checksum), [751905489, -717392933, -1899585498, 1674511551, -809392941, 1476091850, -273770541, 79860964, 1241531971, 40203105]);
    assert.match(built.result.schemaFingerprint, /^[0-9a-f]{64}$/);
    assert.match(built.result.guardFingerprint, /^[0-9a-f]{64}$/);
    assert.equal(pythonCall('validate_manifest', [built.result, expected]).ok, true);
  } finally { fixture.remove(); }
});

test('a trusted unchanged schema and guard contract permits automatic image updates while mismatches request transition', () => {
  for (const action of ['preflight', 'automatic', 'deploy']) {
    assert.deepEqual(pythonCall('validate_deploy_facts', [manifest, ready, action]), { ok: true, result: { mode: 'AUTO_READY' } });
    rejected('validate_deploy_facts', [manifest, { ...ready, pendingCommands: 1 }, action], 'UNSAFE_RELEASE_STATE');
  }
  const mismatched = [
    { ...ready, guardTrusted: false }, { ...ready, phase: 'V8_STARTED' }, { ...ready, automaticContract: null },
    { ...ready, currentImage: `${repository}@${imageDigest}` }, { ...ready, history: history.slice(0, 8) },
    { ...ready, automaticContract: { ...record, schemaFingerprint: '0'.repeat(64) } },
    { ...ready, automaticContract: { ...record, guardFingerprint: '0'.repeat(64) } },
  ];
  for (const facts of mismatched) for (const action of ['preflight', 'automatic']) {
    assert.deepEqual(pythonCall('validate_deploy_facts', [manifest, facts, action]), { ok: true, result: { mode: 'TRANSITION_REQUIRED' } });
  }
});

test('first transition needs explicit approval observed disabled rules drained commands and an exact successful history prefix', () => {
  for (const length of [7, 8, 9, 10]) {
    assert.deepEqual(pythonCall('validate_deploy_facts', [manifest, { ...first, history: history.slice(0, length) }, 'deploy', true]), { ok: true, result: { mode: 'TRANSITION_APPROVED' } });
  }
  rejected('validate_deploy_facts', [manifest, first, 'deploy', false], 'UNSAFE_RELEASE_STATE');
  rejected('validate_deploy_facts', [manifest, first, 'deploy', 'true'], 'UNSAFE_RELEASE_STATE');
  const invalid = [
    { ...first, refreshRuleStates: ['ENABLED', 'DISABLED'] }, { ...first, refreshRuleStates: ['DISABLED', 'ENABLED'] },
    { ...first, refreshRuleStates: ['DISABLED'] }, { ...first, pendingCommands: 1 }, { ...first, pendingCommands: false },
    { ...first, history: history.slice(0, 6) }, { ...first, history: [...history, { version: '11', script: 'V11__fixture.sql', checksum: 1, success: true }] },
    { ...first, history: [{ ...history[0], success: false }, ...history.slice(1, 7)] },
    { ...first, history: [{ ...history[0], checksum: 999 }, ...history.slice(1, 7)] },
    { ...first, history: [...history.slice(1, 7), history[0]] }, { ...first, fabricatedLegacyDrained: true },
  ];
  for (const facts of invalid) rejected('validate_deploy_facts', [manifest, facts, 'deploy', true], 'UNSAFE_RELEASE_STATE');
});

test('main success publishes automatically while manual actions consume a validated existing run and attempt without rebuild', () => {
  const build = job('build-publish');
  const consume = job('approved-release');
  for (const clause of ["github.event_name == 'workflow_run'", "github.event.workflow_run.conclusion == 'success'", "github.event.workflow_run.event == 'push'", "github.event.workflow_run.head_branch == 'main'"]) assert.ok(build.includes(clause), 'Only normal successful main CI may publish automatically');
  assert.match(build, /docker\/build-push-action@/);
  assert.doesNotMatch(build, /ssm\s+send-command|install-host-release-guard|host-release-guard\.sh deploy|systemctl/);
  assert.match(consume, /github\.event_name == 'workflow_dispatch'/);
  assert.match(consume, /github\.ref == 'refs\/heads\/main'/);
  assert.match(consume, /github\.event_name == 'workflow_run'/, 'Same-contract releases must remain automatic after successful main publication');
  assert.match(consume, /needs(?:\.build-publish|\[['"]build-publish['"]\])\.result\s*==\s*'success'/);
  assert.match(consume, /['"]automatic['"]/, 'The automatic path must pass automatic action to the release validator');
  assert.match(consume, /needs:\s*\[?build-publish/);
  assert.match(consume, /run-approved-release\.py/);
  assert.doesNotMatch(consume, /build-push-action|setup-buildx|setup-qemu|amazon-ecr-login/);
  const workflow = read('.github/workflows/deploy.yml');
  for (const input of ['action:', 'build_run_id:', 'build_run_attempt:', 'approve_first_transition:']) assert.ok(workflow.includes(input), 'Manual consumption must name the immutable publication and explicit transition choice');
  assert.match(workflow, /options:\s*\n\s+- preflight\s*\n\s+- deploy/);
});

test('the driver verifies archive bytes before restricted host extraction and guard mutation follows observed approval', () => {
  const context = { repository: 'meet-me-duo/meet-me-server', ref: 'refs/heads/main', eventName: 'workflow_run', sourceSha };
  const event = { repository: { full_name: context.repository }, workflow_run: { name: 'CI', event: 'push', conclusion: 'success', head_branch: 'main', head_sha: sourceSha, head_repository: { full_name: context.repository } } };
  const driverCall = (name, args) => pythonCall(name, args, 'run-approved-release.py');
  assert.deepEqual(driverCall('validate_workflow_event', [event, context]), { ok: true, result: sourceSha });
  assert.deepEqual(driverCall('validate_workflow_event', [{ repository: event.repository }, { ...context, eventName: 'workflow_dispatch' }]), { ok: true, result: sourceSha });
  const invalidEvents = [
    [event, { ...context, repository: 'fork/meet-me-server' }], [event, { ...context, eventName: 'push' }],
    [event, { ...context, sourceSha: 'HEAD;command' }], [event, { ...context, eventName: 'workflow_dispatch', ref: 'refs/heads/develop' }],
    [{ ...event, repository: { full_name: 'fork/meet-me-server' } }, context],
  ];
  for (const field of ['name', 'event', 'conclusion', 'head_branch', 'head_sha']) invalidEvents.push([{ ...event, workflow_run: { ...event.workflow_run, [field]: 'untrusted' } }, context]);
  invalidEvents.push([{ ...event, workflow_run: { ...event.workflow_run, head_repository: { full_name: 'fork/meet-me-server' } } }, context]);
  for (const args of invalidEvents) assert.deepEqual(driverCall('validate_workflow_event', args), { ok: false, reason: 'UNTRUSTED_WORKFLOW' });
  const validArchive = archiveFixture([{ name: 'scripts', kind: 'dir' }, { name: 'scripts/fixture.sh' }]);
  try { assert.deepEqual(driverCall('validate_archive', [validArchive.archive]), { ok: true, result: null }); } finally { validArchive.remove(); }
  const invalidArchives = [
    [{ name: '/absolute' }], [{ name: '../escape' }], [{ name: 'scripts/../../escape' }],
    [{ name: 'link', kind: 'symlink', link: '/outside' }], [{ name: 'link', kind: 'hardlink', link: 'scripts/fixture.sh' }],
    [{ name: 'pipe', kind: 'fifo' }], [{ name: 'duplicate' }, { name: 'duplicate' }],
  ];
  for (const entries of invalidArchives) {
    const fixture = archiveFixture(entries);
    try { assert.deepEqual(driverCall('validate_archive', [fixture.archive]), { ok: false, reason: 'UNTRUSTED_ARCHIVE' }); } finally { fixture.remove(); }
  }
  const driver = read('deploy/pipeline/run-approved-release.py');
  const host = read('deploy/pipeline/host-approved-release.py');
  const reader = read('deploy/preflight/host-preflight.sh');
  assert.match(driver, /validate_manifest/);
  assert.match(driver, /buildRunAttempt|BUILD_RUN_ATTEMPT/);
  assert.match(driver, /archiveSha256/);
  assert.match(driver, /sha256/);
  assert.match(driver, /host-preflight\.sh/);
  assert.match(driver, /ReadOnlyFlywayProbe/);
  assert.match(driver, /executionTimeout/);
  const deployFunction = host.match(/^def deploy\([\s\S]*?(?=^def |$(?![\s\S]))/m);
  assert.ok(deployFunction, 'The explicit deployment function must retain its own approval boundary');
  const validation = deployFunction[0].indexOf('validate_deploy_facts(');
  const installation = deployFunction[0].indexOf('install-host-release-guard.sh');
  assert.ok(validation >= 0 && installation > validation, 'Observed fact validation must precede the explicit deployment installer');
  assert.match(host, /archiveSha256/);
  assert.match(driver + host, /--no-same-owner/);
  assert.match(driver + host, /--no-same-permissions/);
  assert.match(driver + host, /umask|0o0?27|0o0?77/);
  assert.match(host, /compatible-images\.tsv/);
  assert.match(host, /automaticContract|automatic-release/);
  assert.match(host, /os\.replace|replace\(/);
  assert.doesNotMatch(reader, /install-host-release-guard|host-release-guard\.sh deploy|systemctl\s+(stop|restart|enable|disable)|docker\s+(stop|restart)|\.migrate\(/);
});

test('publication is immutable and manual read-only preflight preserves existing production authority without pause writes', () => {
  const workflow = read('.github/workflows/deploy.yml');
  const preflight = read('.github/workflows/production-preflight.yml');
  const pipeline = ['deploy/pipeline/approved_release.py', 'deploy/pipeline/publish_manifest.py', 'deploy/pipeline/run-approved-release.py', 'deploy/pipeline/host-approved-release.py'].map(read).join('\n');
  assert.match(workflow, /s3api put-object[\s\S]*?--if-none-match\s+["']\*["']/);
  assert.match(workflow, /publish_manifest\.py/);
  assert.match(workflow, /runtime-provider-mode/);
  assert.match(workflow, /deploy\/pipeline|pipeline/);
  assert.match(preflight, /workflow_dispatch:/);
  assert.doesNotMatch(preflight, /^\s+(?:pull_request|pull_request_target|push|workflow_run):/m);
  assert.match(preflight, /github\.ref == 'refs\/heads\/main'/);
  assert.match(preflight, /run-preflight\.py\s+--main/);
  for (const source of [workflow, preflight]) {
    assert.match(source, /environment:\s*production/);
    assert.match(source, /contents:\s*read/);
    assert.match(source, /id-token:\s*write/);
    assert.match(source, /role-to-assume:\s*\$\{\{\s*vars\.AWS_DEPLOY_ROLE_ARN\s*}}/);
    assert.doesNotMatch(source, /(?:actions|contents|pull-requests|deployments):\s*write|actions:\s*read|aws-access-key-id|aws-secret-access-key|secrets\./);
  }
  assert.doesNotMatch(workflow + preflight + pipeline, /disable-rule|enable-rule|events:DisableRule|iam:\*|put-role-policy|set-repository|protection-rules/);
});
