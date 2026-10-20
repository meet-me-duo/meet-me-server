const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const test = require('node:test');

// Independent synthetic artifact/SSM/Compose protocol. No real AWS, secrets or operating policy.
const repository = path.resolve(__dirname, '../..');
const sentinel = 'PREVIOUS_ENVIRONMENT=unchanged\n';
const flags = ['MEETME_PRECHECK_PROVIDER_ENABLED', 'MEETME_PRECHECK_DECISIONROUTING_ENABLED',
  'MEETME_PRECHECK_CLASSIFIER_ENABLED', 'MEETME_AI_BUDGET_ENABLED'];
const profiles = [
  'c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc',
  '658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307',
  '86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714',
  '0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04',
  '9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab',
];
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
function policy() {
  return { protocol: 'meetme-managed-ai-policy-v1', state: 'CONFIGURED', unit: { code: 'TEST_SUBUNIT', scale: 0 },
    roomCap: '1000', minimumConfidence: 0.73,
    operations: ['PRECHECK_CLASSIFICATION', 'PRECHECK_COUNT', 'PRECHECK_GENERATION', 'FINAL_PRIMARY', 'FINAL_FALLBACK'].map((operation, index) => ({
      operation, profileFingerprint: profiles[index], evidenceFingerprint: digest(`synthetic:${operation}`),
      maximumAmount: String(index + 3), validUntil: '2099-01-01T00:00:00Z',
    })) };
}
const aws = `#!/usr/bin/env python3
import json,sys,os
args=sys.argv[1:]
def arg(k):return args[args.index(k)+1] if k in args else None
name=arg('--name'); key=name.split('/production/',1)[-1] if name else ''
with open(os.environ['SYNTHETIC_CALLS'],'a') as f:f.write(json.dumps({'service':args[0],'operation':args[1],'name':name})+'\\n')
if args[:2]==['secretsmanager','get-secret-value']:
 print(json.dumps({'password':'SYNTHETIC_DB_SECRET'}));sys.exit(0)
if args[:2]!=['ssm','get-parameter']:sys.exit(79)
p=json.load(open(os.environ['SYNTHETIC_PARAMETERS']))
if key not in p:sys.exit(9)
r=p[key]
print(json.dumps({'Parameter':r}) if arg('--output')=='json' else r['Value'])
`;
function fixture(enabled = 'true', payload = JSON.stringify(policy()) + '\n') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'issue131-runtime-env-'));
  const release = path.join(root, 'release'); const scripts = path.join(release, 'scripts'); const bin = path.join(root, 'bin');
  fs.mkdirSync(scripts, { recursive: true }); fs.mkdirSync(bin);
  fs.copyFileSync(path.join(repository, 'deploy/scripts/render-runtime-env.sh'), path.join(scripts, 'render-runtime-env.sh'));
  fs.writeFileSync(path.join(release, 'runtime-provider-mode'), 'gemini-luna-required\n');
  fs.writeFileSync(path.join(release, 'runtime-auth-mode'), 'oauth-rs256-required\n');
  const artifact = path.join(release, 'runtime-ai-policy.json'); if (payload !== null) fs.writeFileSync(artifact, payload);
  const parameters = {
    'secret/google-client-id': ['SecureString', 'SYNTHETIC_GOOGLE_ID'], 'secret/google-client-secret': ['SecureString', 'SYNTHETIC_GOOGLE_SECRET'],
    'secret/kakao-client-id': ['SecureString', 'SYNTHETIC_KAKAO_ID'], 'secret/kakao-client-secret': ['SecureString', 'SYNTHETIC_KAKAO_SECRET'],
    'secret/openai-api-key': ['SecureString', 'SYNTHETIC_OPENAI_KEY'], 'secret/gemini-api-key': ['SecureString', 'SYNTHETIC_GEMINI_KEY'],
    'secret/typesafe-api-key': ['SecureString', 'SYNTHETIC_TYPESAFE_KEY'],
    'config/google-redirect-uri': ['String', 'https://api.fixture.invalid/api/auth/google/callback'],
    'config/kakao-redirect-uri': ['String', 'https://api.fixture.invalid/api/auth/kakao/callback'],
    'config/auth-signing-key-id': ['String', 'synthetic-kid'], 'config/auth-issuer': ['String', 'https://api.fixture.invalid'],
    'config/auth-audience': ['String', 'synthetic-web'], 'config/auth-web-callback-uri': ['String', 'https://app.fixture.invalid/auth/callback'],
    'config/allowed-origins': ['String', 'https://app.fixture.invalid'], 'config/precheck-provider-enabled': ['String', enabled],
    'config/database-secret-arn': ['String', 'synthetic-db-ref'], 'config/database-url': ['String', 'jdbc:postgresql://fixture.invalid/test'],
    'config/database-username': ['String', 'synthetic-user'], 'config/redis-host': ['String', 'fixture.invalid'],
    'config/redis-port': ['String', '6379'], 'config/redis-ssl-enabled': ['String', 'true'],
  };
  const parameterFile = path.join(root, 'parameters.json');
  fs.writeFileSync(parameterFile, JSON.stringify(Object.fromEntries(Object.entries(parameters).map(([name, [Type, Value]]) => [name, { Type, Value }]))));
  fs.writeFileSync(path.join(bin, 'aws'), aws, { mode: 0o755 });
  const output = path.join(root, '.env.runtime'); fs.writeFileSync(output, sentinel, { mode: 0o600 });
  return { root, artifact, output, render() {
    return cp.spawnSync('bash', [path.join(scripts, 'render-runtime-env.sh'), output], { encoding: 'utf8', timeout: 15_000,
      env: { PATH: `${bin}:/usr/bin:/bin`, AWS_REGION: 'synthetic-region', MEETME_PARAMETER_ROOT: '/fixture/production',
        SYNTHETIC_CALLS: path.join(root, 'calls.jsonl'), SYNTHETIC_PARAMETERS: parameterFile } });
  }, remove() { fs.rmSync(root, { recursive: true, force: true }); } };
}
function env(f) { return Object.fromEntries(fs.readFileSync(f.output, 'utf8').trimEnd().split('\n').map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)])); }
function quiet(r) { for (const value of ['SYNTHETIC_GOOGLE_SECRET', 'SYNTHETIC_KAKAO_SECRET', 'SYNTHETIC_OPENAI_KEY', 'SYNTHETIC_GEMINI_KEY', 'SYNTHETIC_TYPESAFE_KEY', 'SYNTHETIC_DB_SECRET']) assert.ok(!(r.stdout + r.stderr).includes(value)); }
function preserved(f, r) { assert.equal(r.error, undefined); assert.notEqual(r.status, 0); assert.equal(fs.readFileSync(f.output, 'utf8'), sentinel); assert.equal(fs.statSync(f.output).mode & 0o777, 0o600); quiet(r); }
function typesafeCalls(f) {
  const calls = path.join(f.root, 'calls.jsonl');
  return fs.existsSync(calls) ? fs.readFileSync(calls, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse)
    .filter(call => call.name === '/fixture/production/secret/typesafe-api-key') : [];
}

test('DX01 only explicit opt-in plus complete immutable policy emits the atomic four flags and exact artifact threshold path SHA', () => {
  const f = fixture(); try {
    const r = f.render(); assert.equal(r.error, undefined); assert.equal(r.status, 0); quiet(r);
    const values = env(f); for (const name of flags) assert.equal(values[name], 'true', name);
    assert.equal(values.MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE, '0.73');
    assert.equal(values.MEETME_AI_BUDGET_POLICYPATH, '/run/meetme-ai/runtime-ai-policy.json');
    assert.equal(values.MEETME_AI_BUDGET_POLICYSHA256, digest(fs.readFileSync(f.artifact)));
    assert.equal(values.TYPESAFE_API_KEY, 'SYNTHETIC_TYPESAFE_KEY');
    assert.equal(typesafeCalls(f).length, 1);
    assert.equal(fs.statSync(f.output).mode & 0o777, 0o600);
  } finally { f.remove(); }
});

test('DX01 sentinel plus OFF emits all four false without choosing an operating threshold', () => {
  const f = fixture('false', '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}\n'); try {
    const r = f.render(); assert.equal(r.error, undefined); assert.equal(r.status, 0); quiet(r);
    const values = env(f); for (const name of flags) assert.equal(values[name], 'false', name);
    assert.equal(values.MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE, undefined);
    assert.equal(values.TYPESAFE_API_KEY, undefined);
    assert.equal(typesafeCalls(f).length, 0);
  } finally { f.remove(); }
});

for (const kind of ['missing', 'sentinel', 'profileMissing', 'profileForeign', 'unknownField', 'duplicateThreshold', 'symlink']) {
  test(`DX01 ${kind} cannot activate or replace the previous raw environment`, () => {
    const p = policy(); if (kind === 'profileMissing') p.operations.pop();
    if (kind === 'profileForeign') p.operations[0].profileFingerprint = digest('unapproved-profile');
    if (kind === 'unknownField') p.verified = true;
    const body = kind === 'sentinel' ? '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}\n'
      : kind === 'duplicateThreshold' ? JSON.stringify(p).replace('"minimumConfidence":0.73', '"minimumConfidence":0.73,"minimumConfidence":0.74') : JSON.stringify(p);
    const f = fixture('true', kind === 'missing' ? null : body); try {
      if (kind === 'symlink') { const target = `${f.artifact}.target`; fs.renameSync(f.artifact, target); fs.symlinkSync(target, f.artifact); }
      preserved(f, f.render());
    } finally { f.remove(); }
  });
}

test('DX01 target Compose binds the exact approved policy read-only with no host-path auto creation', () => {
  const f = fixture(); try {
    const compose = path.join(path.dirname(f.artifact), 'compose.production.yml');
    fs.copyFileSync(path.join(repository, 'deploy/compose.production.yml'), compose);
    const r = cp.spawnSync('docker', ['compose', '-f', compose, 'config', '--format', 'json'],
      { env: { PATH: process.env.PATH, HOME: process.env.HOME, APP_IMAGE: 'alpine:3.22', RUNTIME_ENV_FILE: f.output, AUTH_KEYS_DIRECTORY: path.join(f.root, 'auth') }, encoding: 'utf8', timeout: 15_000 });
    assert.equal(r.error, undefined); assert.equal(r.status, 0);
    const mount = JSON.parse(r.stdout).services.app.volumes.find(row => row.target === '/run/meetme-ai/runtime-ai-policy.json');
    assert.ok(mount, 'Approved nonsecret policy must reach the actual runtime path');
    assert.equal(mount.type, 'bind'); assert.equal(mount.source, f.artifact); assert.equal(mount.read_only, true);
    assert.notEqual(mount.bind?.create_host_path, true);
    assert.match(fs.readFileSync(path.join(repository, 'deploy/compose.production.yml'), 'utf8'), /target:\s*\/run\/meetme-ai\/runtime-ai-policy.json\s*\n\s*read_only:\s*true\s*\n\s*bind:\s*\n\s*create_host_path:\s*false/);
  } finally { f.remove(); }
});
