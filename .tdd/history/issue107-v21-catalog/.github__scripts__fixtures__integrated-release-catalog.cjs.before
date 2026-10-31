// Independent public-contract fixture. Never derive the approved inventory from production constants.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../..');
const scripts = [
  'V1__create_core_domain.sql', 'V2__add_anonymous_room_lifecycle.sql',
  'V3__add_submission_gemini_pipeline.sql', 'V4__add_matching_results.sql',
  'V5__remove_meeting_duration.sql', 'V6__add_reliable_outbox_processing.sql',
  'V7__resume_delayed_matching_from_structuring.sql', 'V8__add_input_revision_rounds.sql',
  'V9__add_time_recommendations.sql', 'V10__add_bounded_analysis_invocations.sql',
  'V11__add_accounts_and_participant_ownership.sql', 'V12__add_guest_reaccess.sql',
  'V13__add_submission_clarifications.sql', 'V14__add_operator_access_audits.sql',
  'V15__add_parser_transport_observations.sql', 'V16__add_pre_submission_checks.sql',
  'V17__add_durable_ai_room_budgets.sql',
];
// Current publication is the explicitly approved complete V19 catalog; scripts/manifest keep historical V17 defaults.
const currentScripts = [...scripts, 'V18__align_recommendation_selection_attendance.sql', 'V19__add_confirmed_result_reviews.sql', 'V20__add_account_room_history_index.sql'];
const guardFiles = [
  'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh',
  'scripts/deploy-release.sh', 'scripts/rollback-release.sh',
  'scripts/refresh-database-credential.sh', 'compose.guard.yml',
  'meet-me-guarded-restart.service', 'pipeline/approved_release.py',
  'pipeline/host-approved-release.py', 'pipeline/run-approved-release.py',
  'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
  'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
  'runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml',
].sort();
// Issue154 current publication adds the complete execution bundle; historical seventeen-file fixture stays frozen.
const currentGuardFiles = [...guardFiles, 'runtime-ai-policy.json', 'pipeline/release_completion.py',
  'pipeline/transition_orchestrator.py', 'pipeline/approval_gate.py', 'pipeline/release_pipeline.py',
  'transition-approval.json'].sort();
const range = n => Array.from({ length: n }, (_, i) => i + 1);
const catalogs = [range(10), range(11), range(12), [...range(11), 13],
  [...range(10), 14, 15], [...range(11), 14, 15], range(16), range(17)];
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const canonical = value => JSON.stringify(value, (_key, item) => item && !Array.isArray(item) && typeof item === 'object'
  ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b))) : item);
const clone = value => JSON.parse(JSON.stringify(value));
// Flyway's CRC32 oracle is intentionally JS, independent from Python's implementation.
function crc(raw) {
  let value = 0xffffffff;
  for (const byte of Buffer.from(raw.toString('utf8').replace(/^\uFEFF/, '').replace(/\r\n|\r|\n/g, ''), 'utf8')) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
  }
  return (value ^ 0xffffffff) | 0;
}
const repository = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me';
const sourceSha = 'a'.repeat(40);
const expected = { buildRunId: '12345', buildRunAttempt: '2', imageRepository: repository };
const oldImage = `${repository}@sha256:${'7'.repeat(64)}`;
const environment = { SOURCE_SHA: sourceSha, CI_SHA: sourceSha, CI_RUN_ID: '67890',
  BUILD_RUN_ID: '12345', BUILD_RUN_ATTEMPT: '2', ECR_REPOSITORY_URL: repository, IMAGE_DIGEST: `sha256:${'b'.repeat(64)}` };
function rows(versions = range(17)) {
  return versions.map(version => ({ version: String(version), script: scripts[version - 1], checksum: version * 37 }));
}
function manifest(versions = range(17)) {
  return { protocol: 'meet-me-approved-release-v1', sourceSha, ...expected,
    imageDigest: environment.IMAGE_DIGEST, releaseId: `${sourceSha}-12345-2`, archiveSha256: 'c'.repeat(64),
    schemaFingerprint: 'd'.repeat(64), guardFingerprint: 'e'.repeat(64), migrations: rows(versions),
    origin: { repository: 'meet-me-duo/meet-me-server', ref: 'refs/heads/main', workflow: 'CI',
      event: 'push', conclusion: 'success', ciRunId: '67890', ciSha: sourceSha } };
}
const history = release => release.migrations.map(row => ({ ...row, success: true }));
const record = release => ({ protocol: 'meet-me-automatic-release-v1', schemaFingerprint: release.schemaFingerprint,
  guardFingerprint: release.guardFingerprint, history: history(release), currentImage: oldImage,
  sourceSha: '9'.repeat(40), buildRunId: '100', buildRunAttempt: '1' });
const ready = release => ({ history: history(release), phase: 'READY', currentImage: oldImage, guardTrusted: true,
  automaticContract: record(release), refreshRuleStates: ['ENABLED', 'ENABLED'], pendingCommands: 0 });
function python(name, args, module = 'approved_release.py', hostRecord = false) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-integrated-catalog-read-'));
  const marker = path.join(directory, 'synthetic-record.json'); fs.writeFileSync(marker, '{}');
  const code = `import importlib.util,json,sys,pathlib,types\nif sys.platform=='win32' and sys.argv[1].endswith('host-approved-release.py'):sys.modules['fcntl']=types.ModuleType('fcntl')\nsys.path.insert(0,str(pathlib.Path(sys.argv[1]).parent))\ns=importlib.util.spec_from_file_location('offline_integrated',sys.argv[1]);m=importlib.util.module_from_spec(s);sys.modules[s.name]=m;s.loader.exec_module(m)\np=json.loads(sys.stdin.read())\nif p['hostRecord']:\n m.CONTRACT=pathlib.Path(sys.argv[2]);m._bytes=lambda *_:json.dumps(p['args'][0]).encode('utf-8')\ntry:\n result=m._record() if p['hostRecord'] else getattr(m,p['name'])(*p['args'])\n print(json.dumps({'ok':True,'result':result}))\nexcept ValueError as error:\n print(json.dumps({'ok':False,'reason':str(error)}))\n`;
  try {
    const result = cp.spawnSync(process.env.PYTHON_EXECUTABLE ?? 'python3', ['-c', code, path.join(root, 'deploy/pipeline', module), marker], {
      input: JSON.stringify({ name, args, hostRecord }), encoding: 'utf8', timeout: 5_000,
      env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, HOME: directory, PYTHONDONTWRITEBYTECODE: '1' },
    });
    assert.equal(result.status, 0, 'Offline Python must execute; syntax/import/timeouts are invalid RED');
    assert.equal(result.stderr, '', 'Offline metadata parsing must not emit diagnostics');
    return JSON.parse(result.stdout);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
}
function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-integrated-publication-'));
  const migrations = path.join(directory, 'src/main/resources/db/migration'); fs.mkdirSync(migrations, { recursive: true });
  for (const name of scripts) {
    const source = path.join(root, 'src/main/resources/db/migration', name);
    // Only new V17 may be unimplemented during independent RED; never synthesize historical SQL.
    if (fs.existsSync(source)) fs.copyFileSync(source, path.join(migrations, name));
    else {
      assert.equal(name, scripts[16], 'Only the new, not-yet-authored V17 may be synthetic');
      fs.writeFileSync(path.join(migrations, name), '-- synthetic test-only V17\r\nSELECT 17;\r\n');
    }
  }
  for (const name of guardFiles) {
    const target = path.join(directory, 'deploy', name); fs.mkdirSync(path.dirname(target), { recursive: true });
    const source = path.join(root, 'deploy', name);
    // New marker may be unimplemented during independent RED; synthetic bytes stay in this temp fixture.
    if (name === 'runtime-auth-mode' && !fs.existsSync(source)) fs.writeFileSync(target, 'oauth-rs256-required\n');
    else fs.copyFileSync(source, target);
  }
  // Transport helpers exist outside historical guard-fingerprint inventories too.
  for (const name of ['scripts/render-runtime-env.sh', 'scripts/export-certificate.sh']) {
    const target = path.join(directory, 'deploy', name); fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(root, 'deploy', name), target); fs.chmodSync(target, 0o755);
  }
  const archive = path.join(directory, 'release.tar.gz'); fs.writeFileSync(archive, 'synthetic offline archive');
  return { directory, migrations, archive, remove() { fs.rmSync(directory, { recursive: true, force: true }); } };
}
function currentFixture() {
  const value = fixture();
  for (const name of currentScripts.slice(17)) {
    const source = path.join(root, 'src/main/resources/db/migration', name);
    if (fs.existsSync(source)) fs.copyFileSync(source, path.join(value.migrations, name));
    else {
      assert.equal(name, currentScripts[17], 'Approved V19 must use the real review migration');
      fs.writeFileSync(path.join(value.migrations, name), '-- synthetic test-only V18\nSELECT 18;\n');
    }
  }
  fs.copyFileSync(path.join(root, 'deploy/runtime-ai-policy.json'), path.join(value.directory, 'deploy/runtime-ai-policy.json'));
  // Public API shape is archive-bound data, not a writer compatibility guard.
  fs.copyFileSync(path.join(root, 'deploy/public-api-contract.json'), path.join(value.directory, 'deploy/public-api-contract.json'));
  for (const name of currentGuardFiles.filter(name => !guardFiles.includes(name) && name !== 'runtime-ai-policy.json')) {
    const target = path.join(value.directory, 'deploy', name); fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(root, 'deploy', name), target);
  }
  return value;
}
const publish = value => python('build_manifest', [value.directory, value.archive, environment], 'publish_manifest.py');
const validate = value => python('validate_manifest', [value, expected]);
const readRecord = value => python('_record', [value], 'host-approved-release.py', true);
const judge = (release, facts, action, approved = false) => python('validate_deploy_facts', [release, facts, action, approved]);
const mode = value => ({ ok: true, result: { mode: value } });
const untrusted = { ok: false, reason: 'UNTRUSTED_RELEASE' };
const denied = { ok: false, reason: 'UNSAFE_RELEASE_STATE' };
module.exports = { fs, path, root, scripts, currentScripts, guardFiles, currentGuardFiles, range, catalogs, hash, canonical, clone, crc,
  expected, repository, sourceSha, oldImage, environment, rows, manifest, history, record, ready,
  python, fixture, currentFixture, publish, validate, readRecord, judge, mode, untrusted, denied };
