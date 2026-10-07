const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const test = require('node:test');

// Issue #99: release-local required provider mode and opaque runtime secret delivery.
// AWS, Java and rename failures are isolated stubs. No live account, credential or service is touched.
const repository = path.resolve(__dirname, '../..');
const modeName = 'MEETME_RUNTIME_PROVIDER_MODE';
const opaqueKey = 'fixture$\'"\\#opaque-+=/key';
const oldEnvironment = 'LEGACY_SENTINEL=unchanged\n';

const awsStub = `#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]
scenario=json.load(open(os.environ['FAKE_AWS_SCENARIO']))
def argument(name):
 return args[args.index(name)+1] if name in args else None
name=argument('--name')
with open(os.environ['FAKE_AWS_CALLS'],'a') as calls:
 calls.write(json.dumps({'service':args[0],'operation':args[1],'name':name,'query':argument('--query'),'output':argument('--output'),'decrypt':'--with-decryption' in args})+'\\n')
if args[:2]==['secretsmanager','get-secret-value']:
 print(json.dumps({'password':'fixture$database-password'}));sys.exit(0)
if args[:2]!=['ssm','get-parameter']:
 sys.exit(77)
if name and name.endswith('/secret/openai-api-key'):
 if scenario.get('awsFailure'):
  print('SYNTHETIC AWS FAILURE '+scenario['key'],file=sys.stderr);sys.exit(9)
 print(json.dumps({'Parameter':{'Type':scenario.get('type','SecureString'),'Value':scenario['key']}}));sys.exit(0)
values={'database-secret-arn':'fixture-secret-reference','database-url':'jdbc:postgresql://fixture.invalid/fixture','database-username':'fixture_user','redis-host':'fixture.invalid','redis-port':'6379','redis-ssl-enabled':'true','allowed-origins':'https://fixture.invalid','gemini-api-key':'fixture-gemini'}
key=name.rsplit('/',1)[-1] if name else ''
if key not in values: sys.exit(78)
print(values[key])
`;

function fixture(marker = 'gemini-luna-required\n', scenario = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-provider-env-'));
  const release = path.join(root, 'release');
  const scripts = path.join(release, 'scripts');
  const bin = path.join(root, 'bin');
  const destinationDirectory = path.join(root, 'destination');
  for (const directory of [scripts, bin, destinationDirectory]) fs.mkdirSync(directory, { recursive: true });
  fs.copyFileSync(path.join(repository, 'deploy/scripts/render-runtime-env.sh'), path.join(scripts, 'render-runtime-env.sh'));
  fs.copyFileSync(path.join(repository, 'deploy/container-entrypoint.sh'), path.join(root, 'container-entrypoint.sh'));
  if (marker !== null) fs.writeFileSync(path.join(release, 'runtime-provider-mode'), marker);
  fs.writeFileSync(path.join(bin, 'aws'), awsStub, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'java'), '#!/bin/sh\nprintf "java\\n" >> "$FAKE_JAVA_CALLS"\n', { mode: 0o755 });
  fs.writeFileSync(path.join(bin, 'mv'), '#!/bin/sh\nif [ "${FAKE_MV_FAIL:-0}" = 1 ]; then exit 91; fi\nexec /usr/bin/mv "$@"\n', { mode: 0o755 });
  fs.writeFileSync(path.join(root, 'scenario.json'), JSON.stringify({ key: opaqueKey, ...scenario }));
  const destination = path.join(destinationDirectory, '.env.runtime');
  fs.writeFileSync(destination, oldEnvironment, { mode: 0o600 });
  const environment = {
    PATH: `${bin}:/usr/bin:/bin`, AWS_REGION: 'fixture-region',
    MEETME_PARAMETER_ROOT: '/fixture/custom-root',
    FAKE_AWS_SCENARIO: path.join(root, 'scenario.json'),
    FAKE_AWS_CALLS: path.join(root, 'aws.calls'),
    FAKE_JAVA_CALLS: path.join(root, 'java.calls'),
  };
  return {
    root, release, destination, environment,
    mode: path.join(release, 'runtime-provider-mode'),
    render(extra = {}) {
      return cp.spawnSync('bash', [path.join(scripts, 'render-runtime-env.sh'), destination], {
        cwd: destinationDirectory, env: { ...environment, ...extra }, encoding: 'utf8', timeout: 10_000,
      });
    },
    enter(argument = 'server', extra = {}) {
      return cp.spawnSync('sh', [path.join(root, 'container-entrypoint.sh'), argument], {
        env: { ...environment, ...extra }, encoding: 'utf8', timeout: 5_000,
      });
    },
    calls() {
      const file = environment.FAKE_AWS_CALLS;
      return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : [];
    },
    javaCalls() { return fs.existsSync(environment.FAKE_JAVA_CALLS) ? fs.readFileSync(environment.FAKE_JAVA_CALLS, 'utf8').trim().split('\n') : []; },
    remove() { fs.rmSync(root, { recursive: true, force: true }); },
  };
}

function assertQuiet(result, values = [opaqueKey, 'SYNTHETIC AWS FAILURE', 'fixture$database-password']) {
  for (const value of values) assert.ok(!(result.stdout + result.stderr).includes(value), 'Secret values and AWS diagnostics must not enter output');
}

function assertPreserved(f, result) {
  assert.notEqual(result.status, 0, 'Invalid required runtime configuration must fail');
  assert.equal(fs.readFileSync(f.destination, 'utf8'), oldEnvironment, 'Failure must preserve the prior environment bytes');
  assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600, 'Failure must preserve restrictive environment permissions');
  assertQuiet(result);
}

test('required release fetches SecureString under custom parameter root and preserves printable opaque key into raw environment', () => {
  const f = fixture();
  try {
    const result = f.render();
    assert.equal(result.status, 0, 'Required release with a valid SecureString must render successfully');
    const rendered = fs.readFileSync(f.destination, 'utf8');
    assert.ok(rendered.includes(`${modeName}=gemini-luna-required\n`));
    assert.ok(rendered.includes(`OPENAI_API_KEY=${opaqueKey}\n`), 'Opaque printable bytes must not be expanded or quoted away');
    assert.ok(rendered.includes('DATABASE_PASSWORD=fixture$database-password\n'), 'Existing database secret fidelity remains required');
    assert.equal(fs.statSync(f.destination).mode & 0o777, 0o600);
    const call = f.calls().find(c => c.name === '/fixture/custom-root/secret/openai-api-key');
    assert.ok(call, 'Required mode must fetch its exact release-local OpenAI parameter');
    assert.equal(call.output, 'json');
    assert.equal(call.query, null);
    assert.equal(call.decrypt, true);
    assertQuiet(result);
  } finally { f.remove(); }
});

test('renderer reads its own release marker rather than destination directory or caller working directory', () => {
  const f = fixture();
  try {
    fs.writeFileSync(path.join(path.dirname(f.destination), 'runtime-provider-mode'), 'gemini-only\n');
    const result = f.render();
    assert.equal(result.status, 0);
    assert.ok(f.calls().some(c => c.name && c.name.endsWith('/secret/openai-api-key')));
    assert.ok(fs.readFileSync(f.destination, 'utf8').includes(`${modeName}=gemini-luna-required\n`));
  } finally { f.remove(); }
});

for (const marker of [null, 'gemini-only', 'gemini-only\n']) {
  test(`legacy or explicit Gemini-only layout never requests OpenAI (${marker === null ? 'absent' : JSON.stringify(marker)})`, () => {
    const f = fixture(marker, { awsFailure: true });
    try {
      const result = f.render();
      assert.equal(result.status, 0, 'Legacy rendering must succeed without any OpenAI authority');
      assert.ok(!f.calls().some(c => c.name && c.name.endsWith('/secret/openai-api-key')));
      const rendered = fs.readFileSync(f.destination, 'utf8');
      assert.ok(rendered.includes(`${modeName}=gemini-only\n`));
      assert.ok(!rendered.includes('OPENAI_API_KEY='));
      assertQuiet(result);
    } finally { f.remove(); }
  });
}

test('required marker accepts exact enum with no newline or one final newline', () => {
  const f = fixture('gemini-luna-required');
  try { assert.equal(f.render().status, 0); } finally { f.remove(); }
});

test('malformed empty multiline whitespace and non-enum release markers fail before replacing existing environment', () => {
  for (const marker of ['', 'unknown\n', 'gemini-luna-required\n\n', 'gemini-luna-required\r\n', ' gemini-luna-required\n', 'gemini-only\ngemini-luna-required\n']) {
    const f = fixture(marker);
    try { assertPreserved(f, f.render()); } finally { f.remove(); }
  }
});

test('symlink dangling symlink unreadable and nonregular release mode markers are rejected', () => {
  for (const kind of ['symlink', 'dangling', 'unreadable', 'directory']) {
    const f = fixture();
    try {
      if (kind === 'unreadable') fs.chmodSync(f.mode, 0);
      else {
        fs.unlinkSync(f.mode);
        if (kind === 'directory') fs.mkdirSync(f.mode);
        else {
          const target = path.join(f.root, 'marker-target');
          if (kind === 'symlink') fs.writeFileSync(target, 'gemini-luna-required\n');
          fs.symlinkSync(target, f.mode);
        }
      }
      assertPreserved(f, f.render());
    } finally { f.remove(); }
  }
});

test('wrong Parameter type empty non-string and non-printable values fail without clobbering environment', () => {
  const scenarios = [
    { type: 'String' }, { type: 'StringList' }, { key: '' }, { key: null }, { key: 42 },
    { key: ' ' }, { key: 'fixture\nINJECTED=1' }, { key: 'fixture\n' },
    { key: 'fixture\r' }, { key: 'fixture\t' }, { key: 'fixture\u0000suffix' }, { key: 'faké' },
  ];
  for (const scenario of scenarios) {
    const f = fixture('gemini-luna-required\n', scenario);
    try { assertPreserved(f, f.render()); } finally { f.remove(); }
  }
});

test('AWS required-key failure hides stderr and preserves existing environment byte-for-byte', () => {
  const f = fixture('gemini-luna-required\n', { awsFailure: true });
  try { assertPreserved(f, f.render()); } finally { f.remove(); }
});

test('failed final same-directory atomic rename preserves existing environment and permissions', () => {
  const f = fixture();
  try { assertPreserved(f, f.render({ FAKE_MV_FAIL: '1' })); } finally { f.remove(); }
});

test('destination symlink dangling symlink and directory are rejected instead of following or replacing them', () => {
  for (const kind of ['symlink', 'dangling', 'directory']) {
    const f = fixture();
    try {
      fs.unlinkSync(f.destination);
      const target = path.join(f.root, 'unrelated-target');
      if (kind === 'directory') fs.mkdirSync(f.destination);
      else {
        if (kind === 'symlink') fs.writeFileSync(target, oldEnvironment, { mode: 0o600 });
        fs.symlinkSync(target, f.destination);
      }
      const result = f.render();
      assert.notEqual(result.status, 0, 'Unsafe destination must fail without being followed');
      if (kind === 'directory') assert.ok(fs.statSync(f.destination).isDirectory());
      else {
        assert.ok(fs.lstatSync(f.destination).isSymbolicLink());
        if (kind === 'symlink') assert.equal(fs.readFileSync(target, 'utf8'), oldEnvironment);
        else assert.ok(!fs.existsSync(target));
      }
      assertQuiet(result);
    } finally { f.remove(); }
  }
});

for (const argument of ['server', 'migrate', 'true']) {
  test(`required entrypoint rejects absent empty unsafe key and invalid mode before launching ${argument}`, () => {
    const cases = [
      { [modeName]: 'gemini-luna-required' },
      { [modeName]: 'gemini-luna-required', OPENAI_API_KEY: '' },
      { [modeName]: 'gemini-luna-required', OPENAI_API_KEY: ' ' },
      { [modeName]: 'gemini-luna-required', OPENAI_API_KEY: 'fixture\nINJECTED=1' },
      { [modeName]: 'gemini-luna-required', OPENAI_API_KEY: 'fixture\r' },
      { [modeName]: 'gemini-luna-required', OPENAI_API_KEY: 'fixture\t' },
      { [modeName]: 'gemini-luna-required', OPENAI_API_KEY: 'faké' },
      { [modeName]: 'unknown', OPENAI_API_KEY: opaqueKey },
    ];
    for (const config of cases) {
      const f = fixture();
      try {
        const result = f.enter(argument, config);
        assert.notEqual(result.status, 0, 'Required key and mode must be validated before any executable');
        assert.deepEqual(f.javaCalls(), [], 'Invalid runtime configuration must not enter server or migration Java');
        assertQuiet(result);
      } finally { f.remove(); }
    }
  });
}

for (const argument of ['server', 'migrate']) {
  test(`legacy entrypoint default and valid required opaque key both launch ${argument}`, () => {
    for (const config of [{}, { [modeName]: 'gemini-only' }, { [modeName]: 'gemini-luna-required', OPENAI_API_KEY: opaqueKey }]) {
      const f = fixture();
      try {
        assert.equal(f.enter(argument, config).status, 0);
        assert.deepEqual(f.javaCalls(), ['java']);
      } finally { f.remove(); }
    }
  });
}

test('new production artifact requires release mode file and CI runs this independent runtime contract', () => {
  assert.ok(fs.existsSync(path.join(repository, 'deploy/runtime-provider-mode')), 'A new release must declare its required provider mode');
  const marker = fs.readFileSync(path.join(repository, 'deploy/runtime-provider-mode'), 'utf8');
  assert.equal(marker, 'gemini-luna-required\n');
  const deploy = fs.readFileSync(path.join(repository, '.github/workflows/deploy.yml'), 'utf8');
  const packageBlock = deploy.slice(deploy.indexOf('tar -czf release.tar.gz'), deploy.indexOf('aws s3 cp release.tar.gz'));
  assert.ok(packageBlock.includes('runtime-provider-mode'), 'Every new release must carry its provider requirement');
  const ci = fs.readFileSync(path.join(repository, '.github/workflows/ci.yml'), 'utf8');
  assert.match(ci, /node\s+--test\s+deploy\/tests\/runtime-provider-env\.test\.cjs/);
  const compose = fs.readFileSync(path.join(repository, 'deploy/compose.production.yml'), 'utf8');
  assert.match(compose, /env_file:[\s\S]*format:\s*raw/, 'Provider mode and opaque key must pass through the existing raw env_file contract');
});
