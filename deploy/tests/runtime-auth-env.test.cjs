const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const test = require('node:test');

// Independent #107 runtime-readiness oracle, derived from the accepted runbook.
// Every AWS executable/value is synthetic; no credentials or network are inherited.
const repository = path.resolve(__dirname, '../..');
const oldEnvironment = 'LEGACY_SENTINEL=unchanged\n';
const marker = 'oauth-rs256-required\n';
const typesafeParameter = 'secret/typesafe-api-key';
const typesafeKey = 'fixture-typesafe$\'"\\#=opaque';
const auth = [
  ['GOOGLE_CLIENT_ID', 'secret/google-client-id', 'SecureString', 'fixture-google$client-id'],
  ['GOOGLE_CLIENT_SECRET', 'secret/google-client-secret', 'SecureString', 'fixture-google$\'"\\#secret'],
  ['KAKAO_CLIENT_ID', 'secret/kakao-client-id', 'SecureString', 'fixture-kakao$client-id'],
  ['KAKAO_CLIENT_SECRET', 'secret/kakao-client-secret', 'SecureString', 'fixture-kakao$\'"\\#secret'],
  ['GOOGLE_REDIRECT_URI', 'config/google-redirect-uri', 'String', 'https://api.fixture.invalid/api/auth/google/callback'],
  ['KAKAO_REDIRECT_URI', 'config/kakao-redirect-uri', 'String', 'https://api.fixture.invalid/api/auth/kakao/callback'],
  ['AUTH_SIGNING_KEY_ID', 'config/auth-signing-key-id', 'String', 'fixture-key-2026'],
  ['AUTH_ISSUER', 'config/auth-issuer', 'String', 'https://api.fixture.invalid'],
  ['AUTH_AUDIENCE', 'config/auth-audience', 'String', 'fixture-web'],
  ['AUTH_WEB_CALLBACK_URI', 'config/auth-web-callback-uri', 'String', 'https://app.fixture.invalid/auth/callback'],
  ['APP_ALLOWED_ORIGINS', 'config/allowed-origins', 'String', 'https://app.fixture.invalid'],
];
const defaultParameters = {
  ...Object.fromEntries(auth.map(([, name, Type, Value]) => [name, { Type, Value }])),
  [typesafeParameter]: { Type: 'SecureString', Value: typesafeKey },
};
const privatePaths = {
  AUTH_SIGNING_PRIVATE_KEY_PATH: '/run/meetme-auth/signing-private.pem',
  AUTH_VERIFICATION_JWKS_PATH: '/run/meetme-auth/verification.jwks.json',
};
// Synthetic policy values match the independent #131 bundle fixture; they are not operating configuration.
const profiles = [
  'c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc',
  '658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307',
  '86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714',
  '0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04',
  '9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab',
];
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
function syntheticManagedPolicy() {
  return { protocol: 'meetme-managed-ai-policy-v1', state: 'CONFIGURED', unit: { code: 'TEST_SUBUNIT', scale: 0 },
    roomCap: '1000', minimumConfidence: 0.73,
    operations: ['PRECHECK_CLASSIFICATION', 'PRECHECK_COUNT', 'PRECHECK_GENERATION', 'FINAL_PRIMARY', 'FINAL_FALLBACK'].map((operation, index) => ({
      operation, profileFingerprint: profiles[index], evidenceFingerprint: digest(`synthetic:${operation}`),
      maximumAmount: String(index + 3), validUntil: '2099-01-01T00:00:00Z',
    })) };
}
const awsStub = `#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]; scenario=json.load(open(os.environ['FAKE_AUTH_SCENARIO']))
def arg(name):return args[args.index(name)+1] if name in args else None
name=arg('--name'); prefix='/fixture/production/'
short=name[len(prefix):] if name and name.startswith(prefix) else None
with open(os.environ['FAKE_AUTH_CALLS'],'a') as f:
 f.write(json.dumps({'service':args[0],'operation':args[1],'name':name,'query':arg('--query'),'output':arg('--output'),'decrypt':'--with-decryption' in args,'region':arg('--region'),'old_preserved':open(os.environ['FAKE_AUTH_DEST']).read()=='LEGACY_SENTINEL=unchanged\\n'})+'\\n')
if args[:2]==['secretsmanager','get-secret-value']:
 print(json.dumps({'password':'synthetic-fresh'}));sys.exit(0)
if args[:2]!=['ssm','get-parameter'] or short is None:sys.exit(79)
if short in scenario.get('unavailable',[]):
 print('SYNTHETIC_AWS_DIAGNOSTIC '+scenario.get('diagnostic','fixture-diagnostic'),file=sys.stderr);sys.exit(7)
if short in scenario['parameters']:
 parameter=scenario['parameters'][short]
 if scenario.get('malformed')==short:print('{SYNTHETIC_INVALID_JSON}');sys.exit(0)
 if arg('--output')=='json':print(json.dumps({'Parameter':parameter}))
 else:print(parameter.get('Value',''))
 sys.exit(0)
values={'config/database-secret-arn':'fixture-secret-reference','config/database-url':'jdbc:postgresql://fixture.invalid/fixture','config/database-username':'fixture_user','config/redis-host':'fixture.invalid','config/redis-port':'6379','config/redis-ssl-enabled':'true','secret/gemini-api-key':'fixture-gemini','secret/openai-api-key':'fixture-openai'}
if short not in values:sys.exit(78)
if arg('--output')=='json':print(json.dumps({'Parameter':{'Type':'SecureString' if short.startswith('secret/') else 'String','Value':values[short]}}))
else:print(values[short])
`;

function fixture({ mode = marker, changes = {}, unavailable = [], diagnostic = 'fixture-private-diagnostic', malformed, mapLegacyDefault = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-auth-env-'));
  const release = path.join(root, 'release'); const scripts = path.join(release, 'scripts');
  const bin = path.join(root, 'bin'); const destination = path.join(root, '.env.runtime');
  fs.mkdirSync(scripts, { recursive: true }); fs.mkdirSync(bin);
  const source = fs.readFileSync(path.join(repository, 'deploy/scripts/render-runtime-env.sh'), 'utf8');
  // Default-path tests map only the historical absolute host literal into this
  // temporary logical host. No branch, command or required-release path is changed.
  fs.writeFileSync(path.join(scripts, 'render-runtime-env.sh'), mapLegacyDefault ? source.replaceAll('/opt/meet-me/current/.env.runtime', path.join(root, 'legacy-current.env')) : source);
  fs.writeFileSync(path.join(release, 'runtime-provider-mode'), 'gemini-luna-required\n');
  if (mode !== null) fs.writeFileSync(path.join(release, 'runtime-auth-mode'), mode);
  const policy = path.join(release, 'runtime-ai-policy.json');
  const enabled = changes['config/precheck-provider-enabled'];
  const payload = enabled?.Type === 'String' && enabled.Value === 'true'
    ? syntheticManagedPolicy() : { protocol: 'meetme-managed-ai-policy-v1', state: 'UNCONFIGURED' };
  fs.writeFileSync(policy, JSON.stringify(payload) + '\n');
  fs.writeFileSync(destination, oldEnvironment, { mode: 0o600 });
  fs.writeFileSync(path.join(bin, 'aws'), awsStub, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'mv'), '#!/bin/sh\nif [ "${FAKE_AUTH_RENAME_FAILURE:-0}" = 1 ]; then exit 91; fi\nexec /usr/bin/mv "$@"\n', { mode: 0o755 });
  const parameters = { ...defaultParameters, ...changes };
  fs.writeFileSync(path.join(root, 'scenario.json'), JSON.stringify({ parameters, unavailable, diagnostic, malformed }));
  const environment = {
    PATH: `${bin}:/usr/bin:/bin`, AWS_REGION: 'fixture-region', MEETME_PARAMETER_ROOT: '/fixture/production',
    FAKE_AUTH_SCENARIO: path.join(root, 'scenario.json'), FAKE_AUTH_CALLS: path.join(root, 'calls.jsonl'), FAKE_AUTH_DEST: destination,
  };
  return {
    root, release, bin, destination, environment, policy,
    mode: path.join(release, 'runtime-auth-mode'),
    render(extra = {}, args = [destination]) { return cp.spawnSync('bash', [path.join(scripts, 'render-runtime-env.sh'), ...args], { env: { ...environment, ...extra }, cwd: root, encoding: 'utf8', timeout: 15_000 }); },
    calls() { return fs.existsSync(environment.FAKE_AUTH_CALLS) ? fs.readFileSync(environment.FAKE_AUTH_CALLS, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : []; },
    read() { return fs.readFileSync(destination, 'utf8'); },
    remove() { fs.rmSync(root, { recursive: true, force: true }); },
  };
}
function quiet(result, extra = []) {
  for (const value of [...auth.map(row => row[3]), typesafeKey, 'synthetic-fresh', 'SYNTHETIC_AWS_DIAGNOSTIC', 'fixture-private-diagnostic', ...extra]) {
    assert.ok(!(result.stdout + result.stderr).includes(value), 'Runtime values and raw provider diagnostics must never enter stdout/stderr');
  }
}
function preserved(f, result, extra = []) {
  assert.equal(result.error, undefined, 'Fixture must execute without setup/timeout errors');
  assert.notEqual(result.status, 0, 'Invalid required auth must be rejected');
  assert.equal(f.read(), oldEnvironment, 'Failure must preserve old environment bytes');
  assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
  assert.deepEqual(fs.readdirSync(f.root).filter(name => name.startsWith('.env.runtime.')), [], 'Failure must remove incomplete temporary environments');
  quiet(result, extra);
}

test('AR01 required release emits every exact typed auth value and fixed file paths atomically with mode0600', () => {
  const f = fixture(); try {
    const result = f.render(); assert.equal(result.error, undefined); assert.equal(result.status, 0, 'Complete required configuration must succeed');
    const output = f.read(); const lines = Object.fromEntries(output.trimEnd().split('\n').map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    for (const [variable, , , value] of auth) assert.ok(lines[variable] === value, `Exact opaque runtime bytes required for ${variable}`);
    for (const [variable, value] of Object.entries(privatePaths)) assert.equal(lines[variable], value);
    assert.equal(lines.DATABASE_PASSWORD, 'synthetic-fresh'); assert.equal(lines.OPENAI_API_KEY, 'fixture-openai');
    assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
    assert.ok(f.calls().every(call => call.old_preserved), 'Old environment must remain intact throughout parameter collection');
    for (const [, name] of auth) {
      const calls = f.calls().filter(call => call.name === `/fixture/production/${name}`); assert.equal(calls.length, 1, 'Each auth parameter has one unambiguous fetch');
      assert.equal(calls[0].output, 'json'); assert.equal(calls[0].query, null); assert.equal(calls[0].decrypt, true); assert.equal(calls[0].region, 'fixture-region');
    }
    quiet(result);
  } finally { f.remove(); }
});

test('AR02 release-local auth declaration is used instead of caller/destination marker', () => {
  const f = fixture(); try { fs.writeFileSync(path.join(f.root, 'runtime-auth-mode'), 'legacy\n'); const result = f.render(); assert.equal(result.status, 0); assert.ok(f.read().includes('AUTH_SIGNING_KEY_ID=')); quiet(result); } finally { f.remove(); }
});

for (const [, name, Type] of auth) {
  test(`AR03 required auth refuses a missing ${name} without changing the previous environment`, () => {
    const f = fixture({ unavailable: [name] }); try { preserved(f, f.render()); } finally { f.remove(); }
  });
  test(`AR04 required auth validates JSON type and raw safe bytes for ${name}`, () => {
    for (const invalid of [{ Type: Type === 'String' ? 'SecureString' : 'String', Value: 'fixture-wrong-type' }, ...['', null, 17, [], {}, 'fixture\nINJECTED=true', 'fixture\r', 'fixture\u0000suffix', 'fixture\n', 'fixture\t', 'fixture value', 'faké'].map(Value => ({ Type, Value }))]) {
      const f = fixture({ changes: { [name]: invalid } }); try { preserved(f, f.render(), ['fixture-wrong-type', 'INJECTED=true']); } finally { f.remove(); }
    }
  });
}

test('AR05 malformed auth JSON fails quietly without publishing a partial environment', () => {
  const f = fixture({ malformed: 'secret/google-client-secret' }); try { preserved(f, f.render(), ['SYNTHETIC_INVALID_JSON']); } finally { f.remove(); }
});

test('AR06 all auth parameters unavailable is refused for a required release', () => {
  const f = fixture({ unavailable: auth.map(row => row[1]) }); try { preserved(f, f.render()); } finally { f.remove(); }
});

test('AR07 no auth marker preserves legacy renderer behavior with zero auth lookups', () => {
  const f = fixture({ mode: null, unavailable: auth.filter(row => row[0] !== 'APP_ALLOWED_ORIGINS').map(row => row[1]) }); try {
    const result = f.render(); assert.equal(result.status, 0); const output = f.read();
    assert.ok(!f.calls().some(call => auth.some(row => row[0] !== 'APP_ALLOWED_ORIGINS' && call.name?.endsWith(`/${row[1]}`))));
    assert.ok(!/^(AUTH_|GOOGLE_|KAKAO_|MEETME_RUNTIME_AUTH_MODE=)/m.test(output));
    assert.ok(!f.calls().some(call => call.name?.endsWith('/config/precheck-provider-enabled')));
    assert.ok(!output.includes('MEETME_PRECHECK_PROVIDER_ENABLED='));
    assert.ok(output.includes('DATABASE_PASSWORD=synthetic-fresh\n')); assert.ok(output.includes('OPENAI_API_KEY=fixture-openai\n')); quiet(result);
  } finally { f.remove(); }
});

test('AR08 exact required enum accepts no newline or one final newline only', () => {
  const f = fixture({ mode: 'oauth-rs256-required' }); try { assert.equal(f.render().status, 0); assert.ok(f.read().includes('AUTH_ISSUER=')); } finally { f.remove(); }
});

test('AR09 empty unknown whitespace multiline CRLF and NUL auth declarations fail closed', () => {
  for (const mode of ['', 'unknown\n', ' oauth-rs256-required', 'oauth-rs256-required\n\n', 'oauth-rs256-required\r\n', 'oauth-rs256-required\u0000', 'oauth-\u0000rs256-required\n', 'oauth-rs256-required\nlegacy\n']) {
    const f = fixture({ mode }); try { preserved(f, f.render()); } finally { f.remove(); }
  }
});

test('AR10 auth declaration symlinks dangling links nonregular and unreadable files are rejected', () => {
  for (const kind of ['symlink', 'dangling', 'directory', 'unreadable']) {
    const f = fixture(); try {
      if (kind === 'unreadable') fs.chmodSync(f.mode, 0);
      else { fs.unlinkSync(f.mode); const target = path.join(f.root, 'marker-target'); if (kind === 'directory') fs.mkdirSync(f.mode); else { if (kind === 'symlink') fs.writeFileSync(target, marker); fs.symlinkSync(target, f.mode); } }
      preserved(f, f.render());
    } finally { f.remove(); }
  }
});

test('AR11 final rename failure retains old environment and removes all temporary auth files', () => {
  const f = fixture(); try { preserved(f, f.render({ FAKE_AUTH_RENAME_FAILURE: '1' })); } finally { f.remove(); }
});

test('AR12 destination symlinks dangling links and directories are not followed or replaced', () => {
  for (const kind of ['symlink', 'dangling', 'directory']) {
    const f = fixture(); try {
      fs.unlinkSync(f.destination); const target = path.join(f.root, 'unrelated-target');
      if (kind === 'directory') fs.mkdirSync(f.destination); else { if (kind === 'symlink') fs.writeFileSync(target, oldEnvironment); fs.symlinkSync(target, f.destination); }
      const result = f.render(); assert.notEqual(result.status, 0); quiet(result);
      if (kind === 'directory') assert.ok(fs.statSync(f.destination).isDirectory());
      else { assert.ok(fs.lstatSync(f.destination).isSymbolicLink()); if (kind === 'symlink') assert.equal(fs.readFileSync(target, 'utf8'), oldEnvironment); else assert.ok(!fs.existsSync(target)); }
    } finally { f.remove(); }
  }
});

test('AR13 new release packages its required auth declaration', () => {
  const mode = path.join(repository, 'deploy/runtime-auth-mode'); assert.ok(fs.existsSync(mode), 'New release must explicitly declare required auth'); assert.equal(fs.readFileSync(mode, 'utf8'), marker);
  const workflow = fs.readFileSync(path.join(repository, '.github/workflows/deploy.yml'), 'utf8');
  const block = workflow.slice(workflow.indexOf('tar -czf release.tar.gz'), workflow.indexOf('python3 deploy/pipeline/publish_manifest.py'));
  assert.ok(block.includes('runtime-auth-mode'), 'Packaged release must carry its own auth declaration');
});

test('AR14 CI explicitly executes the independent auth runtime contract', () => {
  const ci = fs.readFileSync(path.join(repository, '.github/workflows/ci.yml'), 'utf8');
  assert.match(ci, /node\s+--test\s+deploy\/tests\/runtime-auth-env\.test\.cjs/);
});

function refreshFixture(f, includeTypesafe = false) {
  const result = cp.spawnSync('python3', [path.join(__dirname, 'runtime_auth_refresh_fixture.py')], {
    env: { ...f.environment, PATH: process.env.PATH, AUTH_TEST_AWS: path.join(f.bin, 'aws'), AUTH_TEST_POLICY: f.policy,
      AUTH_TEST_VARIABLES: JSON.stringify([...auth.map(([variable, name]) => [variable, name]), ...(includeTypesafe ? [['TYPESAFE_API_KEY', typesafeParameter]] : [])]) },
    encoding: 'utf8', timeout: 20_000,
  });
  assert.equal(result.error, undefined, 'Refresh fixture must not time out');
  assert.equal(result.status, 0, 'Installed logical-host fixture must prepare and execute normally');
  return JSON.parse(result.stdout);
}

test('AR15 real guarded DB refresh rerenders the full required auth contract before app recreation', () => {
  const f = fixture(); try {
    const report = refreshFixture(f); assert.equal(report.returncode, 0);
    assert.equal(report.auth_complete, true, 'DB password refresh must preserve every auth value');
    assert.equal(report.fixed_paths, true, 'DB refresh must retain mounted RSA/JWKS file references');
    assert.equal(report.database_updated, true); assert.equal(report.mode0600, true);
    assert.equal(report.precheck_enabled, 'false', 'Unconfigured precheck activation must remain OFF during DB refresh');
    assert.equal(report.app_recreates, 1); assert.equal(report.compose_guard_override_last, true); assert.equal(report.quiet, true);
  } finally { f.remove(); }
});

test('AR16 real guarded DB refresh refuses missing auth without recreating app or replacing old environment', () => {
  const f = fixture({ unavailable: ['secret/kakao-client-secret'] }); try {
    const report = refreshFixture(f); assert.notEqual(report.returncode, 0, 'Required auth failure must abort credential refresh');
    assert.equal(report.previous_bytes_preserved, true); assert.equal(report.database_updated, false);
    assert.equal(report.mode0600, true); assert.equal(report.app_recreates, 0); assert.equal(report.quiet, true);
  } finally { f.remove(); }
});

test('AR17 required default destination belongs to the canonical renderer release rather than current or caller', () => {
  const f = fixture({ mapLegacyDefault: true }); try {
    const result = f.render({}, []); assert.equal(result.error, undefined); assert.equal(result.status, 0);
    const ownDestination = path.join(f.release, '.env.runtime');
    assert.ok(fs.existsSync(ownDestination), 'Required release default environment must be placed next to its own declaration');
    assert.ok(fs.readFileSync(ownDestination, 'utf8').includes('AUTH_SIGNING_KEY_ID='));
    assert.ok(!fs.existsSync(path.join(f.root, 'legacy-current.env')), 'Required release must not implicitly rewrite a different current release');
    assert.equal(f.read(), oldEnvironment); quiet(result);
  } finally { f.remove(); }
});

test('AR18 marker-less default retains the historical current destination in an isolated logical host', () => {
  const f = fixture({ mode: null, mapLegacyDefault: true }); try {
    const result = f.render({}, []); assert.equal(result.error, undefined); assert.equal(result.status, 0);
    assert.ok(fs.existsSync(path.join(f.root, 'legacy-current.env'))); assert.ok(!fs.existsSync(path.join(f.release, '.env.runtime')));
    assert.equal(f.read(), oldEnvironment); quiet(result);
  } finally { f.remove(); }
});

test('AR19 symlink parents and higher ancestors are rejected before changing destination or unrelated files', () => {
  for (const higher of [false, true]) {
    const f = fixture(); try {
      const actual = path.join(f.root, 'actual'); fs.mkdirSync(path.join(actual, 'nested'), { recursive: true });
      const existing = path.join(actual, higher ? 'nested/.env.runtime' : '.env.runtime'); fs.writeFileSync(existing, oldEnvironment, { mode: 0o600 });
      const link = path.join(f.root, 'linked'); fs.symlinkSync(actual, link);
      const destination = path.join(link, higher ? 'nested/.env.runtime' : '.env.runtime');
      const result = f.render({}, [destination]); assert.equal(result.error, undefined); assert.notEqual(result.status, 0, 'A symlink in any destination ancestor must be refused');
      assert.equal(fs.readFileSync(existing, 'utf8'), oldEnvironment); assert.equal(fs.statSync(existing).mode & 0o777, 0o600);
      assert.ok(fs.lstatSync(link).isSymbolicLink()); quiet(result);
    } finally { f.remove(); }
  }
});

test('AR20 required release with no optional activation parameter emits an explicit false default', () => {
  const f = fixture(); try {
    const result = f.render(); assert.equal(result.status, 0); assert.ok(f.read().includes('MEETME_PRECHECK_PROVIDER_ENABLED=false\n'));
    const call = f.calls().find(item => item.name === '/fixture/production/config/precheck-provider-enabled');
    assert.ok(call, 'New required release must resolve the optional activation source'); assert.equal(call.output, 'json'); assert.equal(call.query, null); quiet(result);
  } finally { f.remove(); }
});

test('AR21 optional activation accepts only exact String true or false and faithfully emits each value', () => {
  for (const Value of ['true', 'false']) {
    const f = fixture({ changes: { 'config/precheck-provider-enabled': { Type: 'String', Value } } }); try {
      const result = f.render(); assert.equal(result.status, 0); assert.ok(f.read().includes(`MEETME_PRECHECK_PROVIDER_ENABLED=${Value}\n`)); quiet(result);
      const calls = f.calls().filter(call => call.name === `/fixture/production/${typesafeParameter}`);
      assert.equal(calls.length, Value === 'true' ? 1 : 0);
      assert.equal(f.read().includes(`TYPESAFE_API_KEY=${typesafeKey}\n`), Value === 'true');
    } finally { f.remove(); }
  }
});

test('AR22 optional activation lookup failure remains OFF without emitting raw AWS diagnostics', () => {
  const f = fixture({ unavailable: ['config/precheck-provider-enabled'] }); try {
    const result = f.render(); assert.equal(result.status, 0); assert.ok(f.read().includes('MEETME_PRECHECK_PROVIDER_ENABLED=false\n')); quiet(result);
  } finally { f.remove(); }
});

test('AR23 present but malformed activation values or JSON fail atomically instead of silently defaulting OFF', () => {
  const name = 'config/precheck-provider-enabled';
  for (const parameter of [{ Type: 'SecureString', Value: 'true' }, ...['', null, true, false, 1, 'TRUE', ' true', 'false ', 'true\n', 'true\r', 'tr\u0000ue'].map(Value => ({ Type: 'String', Value }))]) {
    const f = fixture({ changes: { [name]: parameter } }); try { preserved(f, f.render()); } finally { f.remove(); }
  }
  const f = fixture({ changes: { [name]: { Type: 'String', Value: 'true' } }, malformed: name });
  try { preserved(f, f.render(), ['SYNTHETIC_INVALID_JSON']); } finally { f.remove(); }
});

test('AR24 real guarded DB refresh preserves explicitly configured synthetic activation true', () => {
  const f = fixture({ changes: { 'config/precheck-provider-enabled': { Type: 'String', Value: 'true' } } }); try {
    const report = refreshFixture(f, true); assert.equal(report.returncode, 0); assert.equal(report.auth_complete, true); assert.equal(report.precheck_enabled, 'true');
    assert.equal(report.fixed_paths, true); assert.equal(report.app_recreates, 1); assert.equal(report.quiet, true);
  } finally { f.remove(); }
});

function managedTypesafeFixture(options = {}) {
  return fixture({ ...options, changes: {
    'config/precheck-provider-enabled': { Type: 'String', Value: 'true' },
    ...options.changes,
  } });
}

function typesafeCalls(f) {
  return f.calls().filter(call => call.name === `/fixture/production/${typesafeParameter}`);
}

test('AR25 managed opt-in requires its Typesafe secret and preserves old bytes on lookup failure', () => {
  const f = managedTypesafeFixture({ unavailable: [typesafeParameter], diagnostic: 'private-typesafe-missing' }); try {
    preserved(f, f.render(), ['private-typesafe-missing']);
    assert.equal(typesafeCalls(f).length, 1);
  } finally { f.remove(); }
});

test('AR26 managed Typesafe key is fetched once as typed decrypted JSON and emitted as exact opaque raw bytes', () => {
  const f = managedTypesafeFixture(); try {
    const result = f.render(); assert.equal(result.error, undefined); assert.equal(result.status, 0); quiet(result);
    const lines = f.read().split('\n').filter(line => line.startsWith('TYPESAFE_API_KEY='));
    assert.deepEqual(lines, [`TYPESAFE_API_KEY=${typesafeKey}`]);
    const calls = typesafeCalls(f); assert.equal(calls.length, 1);
    assert.equal(calls[0].service, 'ssm'); assert.equal(calls[0].operation, 'get-parameter');
    assert.equal(calls[0].output, 'json'); assert.equal(calls[0].query, null);
    assert.equal(calls[0].decrypt, true); assert.equal(calls[0].region, 'fixture-region');
    assert.ok(f.calls().every(call => call.old_preserved));
    assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
  } finally { f.remove(); }
});

const invalidTypesafeParameters = [
  ['wrong parameter type', { Type: 'String', Value: 'fixture-typesafe-wrong-type' }],
  ...[['empty', ''], ['null', null], ['number', 17], ['array', []], ['object', {}],
    ['line injection', 'typesafe\nINJECTED=true'], ['CR', 'typesafe\r'], ['NUL', 'typesafe\u0000suffix'],
    ['trailing newline', 'typesafe\n'], ['tab', 'typesafe\t'], ['space', 'typesafe key'], ['non-ASCII', 'typésafe']]
    .map(([name, Value]) => [name, { Type: 'SecureString', Value }]),
];
for (const [name, parameter] of invalidTypesafeParameters) {
  test(`AR27 invalid Typesafe ${name} is rejected quietly without replacing the old environment`, () => {
    const f = managedTypesafeFixture({ changes: { [typesafeParameter]: parameter } }); try {
      preserved(f, f.render(), ['fixture-typesafe-wrong-type', 'INJECTED=true',
        ...(typeof parameter.Value === 'string' && parameter.Value.length > 0 ? [parameter.Value] : [])]);
      assert.equal(typesafeCalls(f).length, 1);
    } finally { f.remove(); }
  });
}

test('AR27 malformed Typesafe JSON is rejected without publishing partial environment bytes', () => {
  const f = managedTypesafeFixture({ malformed: typesafeParameter }); try {
    preserved(f, f.render(), ['SYNTHETIC_INVALID_JSON']);
    assert.equal(typesafeCalls(f).length, 1);
  } finally { f.remove(); }
});

for (const kind of ['OFF', 'LEGACY']) {
  test(`AR28 ${kind} does not look up or emit a Typesafe credential`, () => {
    const f = fixture({ mode: kind === 'LEGACY' ? null : marker,
      changes: { 'config/precheck-provider-enabled': { Type: 'String', Value: kind === 'OFF' ? 'false' : 'true' } },
      unavailable: [typesafeParameter] }); try {
      const result = f.render(); assert.equal(result.error, undefined); assert.equal(result.status, 0); quiet(result);
      assert.equal(typesafeCalls(f).length, 0);
      assert.ok(!/^TYPESAFE_API_KEY=/m.test(f.read()));
    } finally { f.remove(); }
  });
}

test('AR29 valid UNCONFIGURED activation preserves required auth and enables AI without money enforcement', () => {
  const f = managedTypesafeFixture(); try {
    fs.writeFileSync(f.policy, '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}\n');
    const result = f.render(); assert.equal(result.error, undefined); assert.equal(result.status, 0); quiet(result);
    const lines = Object.fromEntries(f.read().trimEnd().split('\n').map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    for (const [variable, , , value] of auth) assert.equal(lines[variable], value);
    for (const [variable, value] of Object.entries(privatePaths)) assert.equal(lines[variable], value);
    for (const name of ['MEETME_PRECHECK_PROVIDER_ENABLED', 'MEETME_PRECHECK_DECISIONROUTING_ENABLED', 'MEETME_PRECHECK_CLASSIFIER_ENABLED']) assert.equal(lines[name], 'true');
    assert.equal(lines.MEETME_AI_BUDGET_ENABLED, 'false');
    assert.equal(lines.MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE, undefined);
    assert.equal(lines.TYPESAFE_API_KEY, typesafeKey);
    assert.equal(typesafeCalls(f).length, 1);
    assert.ok(f.calls().every(call => call.old_preserved));
    assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
  } finally { f.remove(); }
});

test('AR30 malformed UNCONFIGURED policy still rejects before any Typesafe credential lookup', () => {
  const f = managedTypesafeFixture(); try {
    fs.writeFileSync(f.policy, '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED","verified":true}\n');
    preserved(f, f.render());
    assert.equal(typesafeCalls(f).length, 0);
  } finally { f.remove(); }
});
