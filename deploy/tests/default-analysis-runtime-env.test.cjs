const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const test = require('node:test');

// Independent typed nonsecret SSM override. Missing leaves the artifact default in force.
// Synthetic AWS only: no ambient credentials, network, actual secret, or production policy activation.
const renderer = path.resolve(__dirname, '../scripts/render-runtime-env.sh');
const enabledName = 'config/operations-enabled';
const idsName = 'secret/operations-operator-user-ids';
const enabledKey = 'MEETME_OPERATIONS_ENABLED';
const idsKey = 'MEETME_OPERATIONS_OPERATORUSERIDS';
const syntheticId = 'abcdefab-1234-4678-9abc-0123456789ab';
const secondId = 'fedcbafe-4321-4876-9abc-ba9876543210';
const thirdId = '01234567-89ab-4cde-8fab-0123456789ab';
const previous = `${enabledKey}=true\n${idsKey}=${secondId}\nPREVIOUS_SENTINEL=unchanged\n`;
const diagnostic = 'SYNTHETIC_PRIVATE_AWS_DIAGNOSTIC';

const aws = `#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]
def arg(key):return args[args.index(key)+1] if key in args else None
name=arg('--name');short=name.removeprefix('/fixture/production/') if name else None
with open(os.environ['OPS_CALLS'],'a') as stream:
 stream.write(json.dumps({'service':args[0],'operation':args[1],'name':name,'output':arg('--output'),'query':arg('--query'),'decrypt':'--with-decryption' in args,'region':arg('--region'),'oldPreserved':open(os.environ['OPS_DEST']).read()==os.environ['OPS_PREVIOUS']})+'\\n')
if args[:2]==['secretsmanager','get-secret-value']:
 print('{"password":"synthetic-database-password"}');sys.exit(0)
if args[:2]!=['ssm','get-parameter'] or not name.startswith('/fixture/production/'):sys.exit(79)
scenario=json.load(open(os.environ['OPS_SCENARIO']))
if short in scenario:
 row=scenario[short]
 sys.stdout.buffer.write(bytes.fromhex(row['hex']));sys.stdout.buffer.flush()
 if row.get('stderr'):print(row['stderr'],file=sys.stderr)
 sys.exit(row.get('status',0))
values={'config/precheck-provider-enabled':'false','config/database-secret-arn':'synthetic-reference','config/database-url':'jdbc:postgresql://fixture.invalid/fixture','config/database-username':'synthetic_user','config/redis-host':'fixture.invalid','config/redis-port':'6379','config/redis-ssl-enabled':'true','config/allowed-origins':'https://app.fixture.invalid','secret/gemini-api-key':'synthetic-gemini'}
if short not in values:
 if short=='config/default-analysis-contract':sys.exit(78)
 if short=='config/precheck-evidence-enabled':sys.exit(78) # Optional parameter is unregistered in this independent operator fixture.
 if short.startswith('config/operations-') or short.startswith('secret/operations-'):sys.exit(78)
 if short.startswith('secret/') or short.startswith('config/'):values[short]='synthetic-auth-value'
 else:sys.exit(79)
if arg('--output')=='json':print(json.dumps({'Parameter':{'Type':'SecureString' if short.startswith('secret/') else 'String','Value':values[short]}}))
else:print(values[short])
`;

const raw = (value, status = 0, stderr = '') => ({ hex: Buffer.from(value).toString('hex'), status, stderr });
const fullName = name => `/fixture/production/${name}`;
const parameter = (Type, Value, name = enabledName) => raw(JSON.stringify({ Parameter: { Name: fullName(name), Type, Value } }));
const flag = value => parameter('String', value);
const allowlist = value => parameter('SecureString', value, idsName);

function fixture(changes = {}, { legacy = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-default-analysis-env-'));
  const release = path.join(root, 'release'); const scripts = path.join(release, 'scripts');
  const bin = path.join(root, 'bin'); const destination = path.join(root, '.env.runtime');
  fs.mkdirSync(scripts, { recursive: true }); fs.mkdirSync(bin);
  fs.copyFileSync(renderer, path.join(scripts, 'render-runtime-env.sh'));
  fs.writeFileSync(path.join(release, 'runtime-provider-mode'), 'gemini-only\n');
  if (!legacy) fs.writeFileSync(path.join(release, 'runtime-auth-mode'), 'oauth-rs256-required\n');
  fs.writeFileSync(path.join(release, 'runtime-ai-policy.json'), '{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}\n');
  fs.writeFileSync(destination, previous, { mode: 0o600 });
  fs.writeFileSync(path.join(bin, 'aws'), aws, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'mv'), '#!/bin/sh\nif [ "${OPS_RENAME_FAILURE:-0}" = 1 ]; then exit 91; fi\nexec /usr/bin/mv "$@"\n', { mode: 0o755 });
  const scenario = path.join(root, 'scenario.json'); fs.writeFileSync(scenario, JSON.stringify(changes));
  const callsPath = path.join(root, 'calls.jsonl');
  const env = { PATH: `${bin}:/usr/bin:/bin`, AWS_REGION: 'fixture-region', MEETME_PARAMETER_ROOT: '/fixture/production',
    OPS_SCENARIO: scenario, OPS_CALLS: callsPath, OPS_DEST: destination, OPS_PREVIOUS: previous };
  return { root, destination,
    render(extra = {}) { return cp.spawnSync('bash', [path.join(scripts, 'render-runtime-env.sh'), destination], { env: { ...env, ...extra }, cwd: root, encoding: 'utf8', timeout: 15_000 }); },
    read() { return fs.readFileSync(destination, 'utf8'); },
    calls() { return fs.existsSync(callsPath) ? fs.readFileSync(callsPath, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : []; },
    remove() { fs.rmSync(root, { recursive: true, force: true }); },
  };
}

function quiet(result) {
  for (const value of [syntheticId, secondId, thirdId, diagnostic, 'synthetic-database-password', 'synthetic-auth-value', 'INJECTED=true']) {
    assert.ok(!(result.stdout + result.stderr).includes(value), 'Private runtime values and raw diagnostics must not enter output');
  }
}
function successful(f, result) {
  assert.equal(result.error, undefined, 'Isolated renderer must execute without setup or timeout errors');
  assert.equal(result.status, 0); quiet(result);
  assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
  assert.ok(f.calls().every(call => call.oldPreserved), 'Resolve and validate before replacing the old environment');
}
function rejected(f, result) {
  assert.equal(result.error, undefined, 'Isolated renderer must execute without setup or timeout errors');
  assert.notEqual(result.status, 0, 'Invalid default-analysis configuration must abort rendering');
  assert.equal(f.read(), previous, 'Failure must preserve exact previous environment bytes');
  assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
  assert.deepEqual(fs.readdirSync(f.root).filter(name => name.startsWith('.env.runtime.')), []);
  quiet(result);
}
function named(f, name) { return f.calls().filter(call => call.name === `/fixture/production/${name}`); }
function exactLines(f, name) { return f.read().split('\n').filter(line => line.startsWith(`${name}=`)); }


const contractName = 'config/default-analysis-contract';
const contractKey = 'MEETME_ANALYSIS_DEFAULT_CONTRACT';
const evidenceName = 'config/precheck-evidence-enabled';
const contract = value => parameter('String', value, contractName);
const parsed = f => Object.fromEntries(f.read().trimEnd().split('\n').map(line => {
  const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)];
}));
const contractCalls = f => f.calls().filter(call => call.name === fullName(contractName));
function typedLookup(f) {
  const calls = contractCalls(f); assert.equal(calls.length, 1, 'One optional lookup, with no retry or secondary source');
  assert.equal(calls[0].service, 'ssm'); assert.equal(calls[0].operation, 'get-parameter');
  assert.equal(calls[0].output, 'json'); assert.equal(calls[0].query, null);
  assert.equal(calls[0].region, 'fixture-region');
}
function unchangedFeatureGates(values) {
  for (const key of ['MEETME_PRECHECK_PROVIDER_ENABLED', 'MEETME_PRECHECK_DECISIONROUTING_ENABLED',
    'MEETME_PRECHECK_CLASSIFIER_ENABLED', 'MEETME_PRECHECK_EVIDENCE_ENABLED', 'MEETME_AI_BUDGET_ENABLED']) {
    assert.equal(values[key], 'false', `${key} must not be activated by an analysis contract choice`);
  }
  assert.equal(values.TYPESAFE_API_KEY, undefined);
  assert.equal(values.MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE, undefined);
}
function noNewSecrets(f) {
  const names = f.calls().filter(call => call.service === 'ssm' && call.name.includes('/secret/')).map(call => call.name).sort();
  assert.deepEqual(names, ['secret/google-client-id', 'secret/google-client-secret', 'secret/kakao-client-id',
    'secret/kakao-client-secret', 'secret/gemini-api-key'].map(fullName).sort());
  assert.equal(f.calls().filter(call => call.service === 'secretsmanager').length, 1);
}

for (const value of ['LEGACY', 'DSL_STRUCTURAL_V5']) {
  test(`DAR01 exact ${value} override is passed unchanged without activating independent gates or new secrets`, () => {
    const f = fixture({ [contractName]: contract(value) }); try {
      const result = f.render(); successful(f, result); typedLookup(f);
      assert.equal(parsed(f)[contractKey], value);
      unchangedFeatureGates(parsed(f)); noNewSecrets(f);
    } finally { f.remove(); }
  });
}

for (const [name, change] of [
  ['unregistered', {}],
  ['upstream lookup failure even with malformed stdout', { [contractName]: raw('SYNTHETIC_PRIVATE_AWS_DIAGNOSTIC\x00{}{}', 9, diagnostic) }],
]) {
  test(`DAR02 ${name} omits the override so the V5 artifact default remains distinguishable`, () => {
    const f = fixture(change); try {
      const result = f.render(); successful(f, result); typedLookup(f);
      assert.equal(parsed(f)[contractKey], undefined, 'Missing is not an explicit LEGACY or V5 choice');
      unchangedFeatureGates(parsed(f)); noNewSecrets(f);
    } finally { f.remove(); }
  });
}

const valid = JSON.stringify({ Parameter: { Name: fullName(contractName), Type: 'String', Value: 'LEGACY' } });
const malformed = [
  ['foreign Name', JSON.stringify({ Parameter: { Name: fullName('config/other'), Type: 'String', Value: 'LEGACY' } })],
  ['wrong Type', JSON.stringify({ Parameter: { Name: fullName(contractName), Type: 'SecureString', Value: 'LEGACY' } })],
  ['missing Name', JSON.stringify({ Parameter: { Type: 'String', Value: 'LEGACY' } })],
  ['missing Value', JSON.stringify({ Parameter: { Name: fullName(contractName), Type: 'String' } })],
  ['malformed JSON', '{SYNTHETIC_PRIVATE_AWS_DIAGNOSTIC'],
  ['additional JSON document', valid + '{}'],
  ['raw NUL', valid + '\x00'],
  ['escaped NUL in value', JSON.stringify({ Parameter: { Name: fullName(contractName), Type: 'String', Value: 'LEGACY\x00' } })],
  ['duplicate Value', valid.replace('"Value":"LEGACY"', '"Value":"LEGACY","Value":"DSL_STRUCTURAL_V5"')],
  ['oversized response', valid + ' '.repeat(65537)],
];
for (const value of ['legacy', 'V5', ' DSL_STRUCTURAL_V5', 'LEGACY ', '', 'LEGACY\nINJECTED=true', null, true, 31, ['LEGACY']]) {
  malformed.push([`invalid enum ${JSON.stringify(value)}`, JSON.stringify({ Parameter: { Name: fullName(contractName), Type: 'String', Value: value } })]);
}
for (const [name, body] of malformed) {
  test(`DAR03 successful typed lookup with ${name} fails atomically and never discloses raw data`, () => {
    const f = fixture({ [contractName]: raw(body, 0, diagnostic) }); try {
      rejected(f, f.render()); typedLookup(f);
    } finally { f.remove(); }
  });
}

test('DAR04 historical artifact without required auth performs no contract lookup and adds no field', () => {
  const f = fixture({ [contractName]: raw('{INVALID MUST NOT BE READ') }, { legacy: true }); try {
    successful(f, f.render()); assert.equal(parsed(f)[contractKey], undefined);
    assert.deepEqual(contractCalls(f), []);
    assert.ok(!f.calls().some(call => call.name === fullName(evidenceName)));
  } finally { f.remove(); }
});

for (const state of ['unregistered', 'false']) {
  test(`DAR05 existing precheck evidence ${state} stays OFF under an explicit legacy analysis override`, () => {
    const changes = { [contractName]: contract('LEGACY') };
    if (state === 'false') changes[evidenceName] = parameter('String', 'false', evidenceName);
    const f = fixture(changes); try {
      successful(f, f.render()); assert.equal(parsed(f)[contractKey], 'LEGACY');
      assert.equal(parsed(f).MEETME_PRECHECK_EVIDENCE_ENABLED, 'false');
      assert.equal(parsed(f).MEETME_AI_BUDGET_ENABLED, 'false');
    } finally { f.remove(); }
  });
}

test('DAR06 invalid existing enabled boolean is still rejected rather than reinterpreted as an analysis enum', () => {
  const f = fixture({ [contractName]: contract('DSL_STRUCTURAL_V5'), [evidenceName]: parameter('String', 'LEGACY', evidenceName) }); try {
    rejected(f, f.render());
  } finally { f.remove(); }
});

test('DAR07 existing operator identity parser remains separate from the new analysis enum kind', () => {
  const f = fixture({ [contractName]: contract('LEGACY'), [enabledName]: flag('true'), [idsName]: allowlist(syntheticId) }); try {
    successful(f, f.render()); assert.equal(parsed(f)[contractKey], 'LEGACY');
    assert.equal(parsed(f)[enabledKey], 'true'); assert.equal(parsed(f)[idsKey], syntheticId);
    assert.equal(parsed(f).MEETME_PRECHECK_EVIDENCE_ENABLED, 'false');
    assert.equal(parsed(f).MEETME_AI_BUDGET_ENABLED, 'false');
  } finally { f.remove(); }
});

test('DAR08 a final rename failure still preserves the old environment after a valid contract lookup', () => {
  const f = fixture({ [contractName]: contract('DSL_STRUCTURAL_V5') }); try {
    rejected(f, f.render({ OPS_RENAME_FAILURE: '1' })); typedLookup(f);
  } finally { f.remove(); }
});
