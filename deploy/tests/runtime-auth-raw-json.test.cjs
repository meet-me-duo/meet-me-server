const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const test = require('node:test');

// Literal NUL in the AWS executable's byte stream is malformed JSON. It must
// never be silently removed by shell command substitution before validation.
// Distinct from the original50's valid JSON containing escaped \u0000 values.
const renderer = path.resolve(__dirname, '../scripts/render-runtime-env.sh');
const oldEnvironment = 'PREVIOUS_AUTH_ENV=preserve-exactly\n';
const aws = `#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:];name=args[args.index('--name')+1] if '--name' in args else ''
short=name.removeprefix('/fixture/production/')
scenario=json.load(open(os.environ['RAW_JSON_SCENARIO']))
if args[:2]==['secretsmanager','get-secret-value']:
 print('{"password":"synthetic-database"}');sys.exit(0)
if args[:2]!=['ssm','get-parameter']:sys.exit(79)
if short==scenario['name']:
 sys.stdout.buffer.write(bytes.fromhex(scenario['hex']));sys.exit(0)
values={'secret/openai-api-key':'synthetic-openai','secret/google-client-id':'synthetic-google-id','secret/google-client-secret':'synthetic-google-secret','secret/kakao-client-id':'synthetic-kakao-id','secret/kakao-client-secret':'synthetic-kakao-secret','config/google-redirect-uri':'https://api.fixture.invalid/api/auth/google/callback','config/kakao-redirect-uri':'https://api.fixture.invalid/api/auth/kakao/callback','config/auth-signing-key-id':'synthetic-kid','config/auth-issuer':'https://api.fixture.invalid','config/auth-audience':'synthetic-web','config/auth-web-callback-uri':'https://app.fixture.invalid/auth/callback','config/allowed-origins':'https://app.fixture.invalid','config/precheck-provider-enabled':'false','config/database-secret-arn':'synthetic-secret-reference','config/database-url':'jdbc:postgresql://fixture.invalid/fixture','config/database-username':'synthetic_user','config/redis-host':'fixture.invalid','config/redis-port':'6379','config/redis-ssl-enabled':'true','secret/gemini-api-key':'synthetic-gemini'}
if short not in values:sys.exit(78)
if '--output' in args and args[args.index('--output')+1]=='json':
 print(json.dumps({'Parameter':{'Type':'SecureString' if short.startswith('secret/') else 'String','Value':values[short]}}))
else:print(values[short])
`;

function runMalformed(name, raw) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-auth-raw-json-'));
  try {
    const scripts = path.join(root, 'release/scripts'); const bin = path.join(root, 'bin');
    fs.mkdirSync(scripts, { recursive: true }); fs.mkdirSync(bin);
    fs.copyFileSync(renderer, path.join(scripts, 'render-runtime-env.sh'));
    fs.writeFileSync(path.join(root, 'release/runtime-auth-mode'), 'oauth-rs256-required\n');
    fs.writeFileSync(path.join(root, 'release/runtime-provider-mode'), 'gemini-luna-required\n');
    fs.writeFileSync(path.join(bin, 'aws'), aws, { mode: 0o755 });
    const destination = path.join(root, '.env.runtime'); fs.writeFileSync(destination, oldEnvironment, { mode: 0o600 });
    const scenario = path.join(root, 'raw.json'); fs.writeFileSync(scenario, JSON.stringify({ name, hex: Buffer.from(raw).toString('hex') }));
    const result = cp.spawnSync('bash', [path.join(scripts, 'render-runtime-env.sh'), destination], { env: { PATH: `${bin}:/usr/bin:/bin`, MEETME_PARAMETER_ROOT: '/fixture/production', AWS_REGION: 'synthetic-region', RAW_JSON_SCENARIO: scenario }, encoding: 'utf8', timeout: 15_000 });
    assert.equal(result.error, undefined, 'Synthetic byte-stream fixture must execute normally');
    assert.notEqual(result.status, 0, 'Malformed raw JSON must fail before Bash can erase NUL bytes');
    assert.equal(fs.readFileSync(destination, 'utf8'), oldEnvironment, 'Malformed stream must preserve the previous environment exactly');
    assert.equal(fs.statSync(destination).mode & 0o777, 0o600);
    for (const value of ['synthetic-secr', 'synthetic-google-secret', 'synthetic-kakao-secret', raw]) assert.ok(!(result.stdout + result.stderr).includes(value), 'Neither raw nor transformed private values may enter output');
    assert.deepEqual(fs.readdirSync(root).filter(item => item.startsWith('.env.runtime.')), []);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}

test('RN01 literal NUL inside a required client secret JSON string is rejected without normalization', () => {
  runMalformed('secret/google-client-secret', '{"Parameter":{"Type":"SecureString","Value":"synthetic-secr\u0000et"}}');
});

test('RN02 literal NUL inside optional tru-NUL-e must never enable the provider', () => {
  runMalformed('config/precheck-provider-enabled', '{"Parameter":{"Type":"String","Value":"tru\u0000e"}}');
});

test('RN03 literal NUL inside Parameter Type must never turn into a valid String type', () => {
  runMalformed('config/precheck-provider-enabled', '{"Parameter":{"Type":"Str\u0000ing","Value":"true"}}');
});

test('RN04 literal NUL after a JSON document is rejected rather than trimmed into a valid secret response', () => {
  runMalformed('secret/google-client-secret', '{"Parameter":{"Type":"SecureString","Value":"synthetic-google-secret"}}\u0000');
});
