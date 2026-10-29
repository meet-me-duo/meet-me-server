const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const test = require('node:test');

// Independent administrator-runtime contract. All identities and credentials are
// synthetic; the process receives no ambient credentials or real AWS executable.
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
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-operations-env-'));
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
  assert.notEqual(result.status, 0, 'Invalid administrator configuration must abort rendering');
  assert.equal(f.read(), previous, 'Failure must preserve exact previous environment bytes');
  assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
  assert.deepEqual(fs.readdirSync(f.root).filter(name => name.startsWith('.env.runtime.')), []);
  quiet(result);
}
function named(f, name) { return f.calls().filter(call => call.name === `/fixture/production/${name}`); }
function exactLines(f, name) { return f.read().split('\n').filter(line => line.startsWith(`${name}=`)); }

test('OP01 unavailable optional activation renders explicit OFF and removes stale allowlist without reading it', () => {
  const f = fixture({ [enabledName]: raw('', 7, diagnostic), [idsName]: raw('', 7, diagnostic) });
  try { successful(f, f.render()); assert.deepEqual(exactLines(f, enabledKey), [`${enabledKey}=false`]); assert.deepEqual(exactLines(f, idsKey), []); assert.equal(named(f, enabledName).length, 1); assert.equal(named(f, idsName).length, 0); } finally { f.remove(); }
});
test('OP02 explicit false never resolves or emits the sensitive allowlist', () => {
  const f = fixture({ [enabledName]: flag('false'), [idsName]: raw('', 7, diagnostic) });
  try { successful(f, f.render()); assert.deepEqual(exactLines(f, enabledKey), [`${enabledKey}=false`]); assert.deepEqual(exactLines(f, idsKey), []); assert.equal(named(f, idsName).length, 0); } finally { f.remove(); }
});
test('OP03 true renders one canonical synthetic UUID through typed decrypted JSON with no query shortcut', () => {
  const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(syntheticId) });
  try { successful(f, f.render()); assert.deepEqual(exactLines(f, enabledKey), [`${enabledKey}=true`]); assert.deepEqual(exactLines(f, idsKey), [`${idsKey}=${syntheticId}`]);
    for (const name of [enabledName, idsName]) { const calls = named(f, name); assert.equal(calls.length, 1); assert.equal(calls[0].output, 'json'); assert.equal(calls[0].query, null); assert.equal(calls[0].region, 'fixture-region'); assert.equal(calls[0].decrypt, true); }
  } finally { f.remove(); }
});
// OPC-2: the two explicitly allowed synthetic accounts must both survive delivery.
for (const value of [`${syntheticId},${secondId}`, `${secondId},${syntheticId}`]) {
  test(`OP11 two distinct canonical UUIDs render exactly in supplied order ${value.startsWith(syntheticId) ? 'forward' : 'reverse'}`, () => {
    const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(value) });
    try { successful(f, f.render()); assert.deepEqual(exactLines(f, enabledKey), [`${enabledKey}=true`]); assert.deepEqual(exactLines(f, idsKey), [`${idsKey}=${value}`]);
      const calls = named(f, idsName); assert.equal(calls.length, 1); assert.equal(calls[0].output, 'json'); assert.equal(calls[0].query, null); assert.equal(calls[0].decrypt, true);
    } finally { f.remove(); }
  });
}
const invalidLists = [
  ['three distinct entries exceed approved two-account bound', `${syntheticId},${secondId},${thirdId}`],
  ['leading comma', `,${syntheticId}`], ['trailing comma', `${syntheticId},`],
  ['double comma', `${syntheticId},,${secondId}`], ['only comma', ','],
  ['space after comma', `${syntheticId}, ${secondId}`], ['space before comma', `${syntheticId} ,${secondId}`],
  ['tab in second entry', `${syntheticId},\t${secondId}`], ['newline in second entry', `${syntheticId},\n${secondId}`],
  ['carriage return in second entry', `${syntheticId},${secondId}\r`], ['NUL in second entry', `${syntheticId},${secondId}\u0000`],
  ['uppercase second entry', `${syntheticId},${secondId.toUpperCase()}`],
  ['short second entry', `${syntheticId},1-1-1-1-1`], ['missing second dashes', `${syntheticId},${secondId.replaceAll('-', '')}`],
  ['invalid first entry', `not-an-account,${secondId}`], ['environment injection second entry', `${syntheticId},${secondId}\nINJECTED=true`],
];
for (const [label, value] of invalidLists) test(`OP12 ${label} fails closed`, () => {
  const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(value) });
  try { rejected(f, f.render()); assert.equal(named(f, idsName).length, 1); } finally { f.remove(); }
});
test('OP13 two-account rename failure preserves the previous environment and permissions', () => {
  const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(`${syntheticId},${secondId}`) });
  try { rejected(f, f.render({ OPS_RENAME_FAILURE: '1' })); } finally { f.remove(); }
});

test('OP04 nonzero AWS exit cannot activate even if stdout contains valid true', () => {
  const body = JSON.stringify({ Parameter: { Name: fullName(enabledName), Type: 'String', Value: 'true' } });
  const f = fixture({ [enabledName]: raw(body, 7, diagnostic), [idsName]: allowlist(syntheticId) });
  try { successful(f, f.render()); assert.deepEqual(exactLines(f, enabledKey), [`${enabledKey}=false`]); assert.equal(named(f, idsName).length, 0); } finally { f.remove(); }
});

const invalidFlags = [
  ['wrong parameter type', parameter('SecureString', 'true')],
  ...['', null, true, false, 1, [], {}, 'TRUE', ' true', 'false ', 'true\n', 'true\r\n', 'tr\u0000ue'].map(value => ['invalid flag value', flag(value)]),
  ['empty success body', raw('')], ['malformed JSON', raw('{SYNTHETIC_INVALID_JSON}')],
  ['raw NUL normalization', raw(`{"Parameter":{"Name":"${fullName(enabledName)}","Type":"String","Value":"tr\u0000ue"}}`)],
];
for (const [index, [label, response]] of invalidFlags.entries()) test(`OP05.${index} ${label} aborts atomically`, () => {
  const f = fixture({ [enabledName]: response, [idsName]: allowlist(syntheticId) });
  try { rejected(f, f.render()); assert.equal(named(f, idsName).length, 0); } finally { f.remove(); }
});

const invalidAllowlists = [
  ['unavailable secret', raw('', 7, diagnostic)], ['wrong parameter type', parameter('String', syntheticId, idsName)],
  ...['', null, true, 1, [], [syntheticId], {}, `${syntheticId},${syntheticId}`,
    `${syntheticId} ${secondId}`, `${syntheticId}\n${secondId}`, `${syntheticId}\n`, `${syntheticId}\r\n`, `${syntheticId}\u0000`,
    ` ${syntheticId}`, `${syntheticId} `, syntheticId.toUpperCase(), syntheticId.replaceAll('-', ''), '1-1-1-1-1', `${syntheticId}\nINJECTED=true`]
    .map(value => ['invalid canonical UUID list', allowlist(value)]),
  ['empty success body', raw('')], ['malformed JSON', raw('{SYNTHETIC_INVALID_JSON}')],
  ['raw NUL in UUID', raw(`{"Parameter":{"Name":"${fullName(idsName)}","Type":"SecureString","Value":"${syntheticId.slice(0, 8)}\u0000${syntheticId.slice(8)}"}}`)],
];
for (const [index, [label, response]] of invalidAllowlists.entries()) test(`OP06.${index} ${label} refuses activation without replacing previous env`, () => {
  const f = fixture({ [enabledName]: flag('true'), [idsName]: response });
  try { rejected(f, f.render()); assert.equal(named(f, idsName).length, 1); } finally { f.remove(); }
});
test('OP07 final rename failure preserves old bytes and cleans sensitive temporary environment', () => {
  const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(syntheticId) });
  try { rejected(f, f.render({ OPS_RENAME_FAILURE: '1' })); } finally { f.remove(); }
});
test('OP08 markerless legacy release keeps its existing path without administrator parameter lookups', () => {
  const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(syntheticId) }, { legacy: true });
  try { successful(f, f.render()); assert.equal(named(f, enabledName).length, 0); assert.equal(named(f, idsName).length, 0); assert.deepEqual(exactLines(f, enabledKey), []); assert.deepEqual(exactLines(f, idsKey), []); } finally { f.remove(); }
});

for (const [name, Type, Value] of [[enabledName, 'String', 'true'], [idsName, 'SecureString', syntheticId]]) {
  const doc = JSON.stringify({ Parameter: { Name: fullName(name), Type, Value } });
  const invalidResponses = [
    ['missing Name', JSON.stringify({ Parameter: { Type, Value } })],
    ['different namespace', JSON.stringify({ Parameter: { Name: `/another/production/${name}`, Type, Value } })],
    ['different parameter', JSON.stringify({ Parameter: { Name: fullName(name === enabledName ? idsName : enabledName), Type, Value } })],
    ['nonstring Name', JSON.stringify({ Parameter: { Name: null, Type, Value } })],
    ['duplicate Value', `{"Parameter":{"Name":"${fullName(name)}","Type":"${Type}","Value":"unused","Value":"${Value}"}}`],
    ['duplicate Name', `{"Parameter":{"Name":"wrong","Name":"${fullName(name)}","Type":"${Type}","Value":"${Value}"}}`],
    ['duplicate root key', `{"Parameter":{},"Parameter":{"Name":"${fullName(name)}","Type":"${Type}","Value":"${Value}"}}`],
    ['multiple JSON documents', `${doc}\n${doc}`],
    ['nonstandard NaN', `{"Parameter":{"Name":"${fullName(name)}","Type":"${Type}","Value":"${Value}"},"ignored":NaN}`],
    ['oversized JSON', `${doc}${' '.repeat(65537 - Buffer.byteLength(doc))}`],
    ['raw NUL after document', `${doc}\u0000`],
  ];
  for (const [index, [label, body]] of invalidResponses.entries()) test(`OP09.${name}.${index} ${label} is rejected without normalizing an invalid successful response`, () => {
    const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(syntheticId), [name]: raw(body) });
    try { rejected(f, f.render()); if (name === enabledName) assert.equal(named(f, idsName).length, 0); } finally { f.remove(); }
  });
  test(`OP10.${name} valid single JSON document exactly at 64KiB is accepted`, () => {
    const f = fixture({ [enabledName]: flag('true'), [idsName]: allowlist(syntheticId), [name]: raw(`${doc}${' '.repeat(65536 - Buffer.byteLength(doc))}`) });
    try { successful(f, f.render()); assert.deepEqual(exactLines(f, enabledKey), [`${enabledKey}=true`]); assert.deepEqual(exactLines(f, idsKey), [`${idsKey}=${syntheticId}`]); } finally { f.remove(); }
  });
}
