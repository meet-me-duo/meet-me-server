const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const test = require('node:test');

// Issue #99: authorized read-only preflight. All runtime inputs are synthetic;
// Python is imported without main(), JDBC is a fake driver, and no AWS/host call is made.
const root = path.resolve(__dirname, '../..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const repository = 'meet-me-duo/meet-me-server';
const sha = 'a'.repeat(40);
const trusted = {
  number: 100,
  user: { login: 'jinhyeongpark' },
  head: { ref: 'feature/integrate-recommendations-fallback', sha, repo: { full_name: repository } },
  base: { ref: 'develop' },
};
const event = { repository, sha, actor: 'jinhyeongpark', triggeringActor: 'jinhyeongpark' };

function pythonCall(name, args) {
  const code = `import importlib.util,json,sys\ns=importlib.util.spec_from_file_location('preflight',sys.argv[1])\nm=importlib.util.module_from_spec(s)\ns.loader.exec_module(m)\npayload=json.loads(sys.stdin.read())\ntry:\n result=getattr(m,payload['name'])(*payload['args'])\n print(json.dumps({'ok':True,'result':result}))\nexcept ValueError as error:\n print(json.dumps({'ok':False,'reason':str(error)}))\n`;
  const result = cp.spawnSync('python3', ['-c', code, path.join(root, 'deploy/preflight/run-preflight.py')], {
    input: JSON.stringify({ name, args }), encoding: 'utf8', timeout: 5_000,
    env: { PATH: process.env.PATH, HOME: os.tmpdir(), PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.equal(result.status, 0, 'The importable preflight contract must execute on synthetic input without main or external commands');
  return JSON.parse(result.stdout);
}

function rejection(name, args, expected) {
  assert.deepEqual(pythonCall(name, args), { ok: false, reason: expected });
}

function successPayload(overrides = {}) {
  return {
    protocol: 'meetme-readonly-preflight-v1',
    host: { api: { healthz: 200, openapi: 403 } },
    flyway: { status: 'success', database: 'fixture_database', serverVersion: '18.0', history: [{ version: '8', script: 'V8__fixture.sql', checksum: 42, success: true }] },
    ...overrides,
  };
}

test('PR100 same repository author branch base and exact head SHA are the only trusted target', () => {
  assert.deepEqual(pythonCall('validate_target', [trusted, event]), { ok: true, result: sha });
});

test('fork wrong PR branch base author actor repository SHA and malformed SHA are rejected before any production authority', () => {
  const cases = [
    { ...trusted, number: 101 },
    { ...trusted, user: { login: 'other-author' } },
    { ...trusted, head: { ...trusted.head, ref: 'main' } },
    { ...trusted, base: { ref: 'main' } },
    { ...trusted, head: { ...trusted.head, repo: { full_name: 'fork/meet-me-server' } } },
    { ...trusted, head: { ...trusted.head, sha: 'b'.repeat(40) } },
    { ...trusted, head: { ...trusted.head, sha: 'HEAD; unsafe-command' } },
  ];
  for (const pr of cases) rejection('validate_target', [pr, event], 'UNTRUSTED_TARGET');
  for (const context of [{ ...event, actor: 'other-author' }, { ...event, triggeringActor: 'other-rerun-actor' }, { ...event, repository: 'fork/meet-me-server' }, { ...event, sha: 'b'.repeat(40) }]) {
    rejection('validate_target', [trusted, context], 'UNTRUSTED_TARGET');
  }
});

test('production preflight has manual main-only entry and validates its exact target before production authority', () => {
  const workflow = read('.github/workflows/production-preflight.yml');
  assert.match(workflow, /workflow_dispatch:/);
  assert.doesNotMatch(workflow, /^\s+(?:push|workflow_run|pull_request|pull_request_target):/m);
  assert.match(workflow, /github\.ref == 'refs\/heads\/main'/);
  assert.match(workflow, /run-preflight\.py --main --validate/);
  const validation = workflow.indexOf('run-preflight.py --main --validate');
  const credentials = workflow.indexOf('configure-aws-credentials');
  assert.ok(validation >= 0 && credentials >= 0 && validation < credentials, 'Target validation must precede AWS credential acquisition');
});

test('preflight reuses only existing production OIDC role and does not introduce stored credentials or write permissions', () => {
  const workflow = read('.github/workflows/production-preflight.yml');
  assert.match(workflow, /environment:\s*production/);
  assert.match(workflow, /id-token:\s*write/);
  assert.match(workflow, /contents:\s*read/);
  assert.match(workflow, /role-to-assume:\s*\$\{\{\s*vars\.AWS_DEPLOY_ROLE_ARN\s*}}/);
  assert.doesNotMatch(workflow, /(?:contents|pull-requests|actions|deployments):\s*write|secrets\.|aws-access-key-id|aws-secret-access-key/);
});

test('checkout pins the exact approved main SHA and sparsely fetches only preflight helpers without persisting credentials', () => {
  const workflow = read('.github/workflows/production-preflight.yml');
  assert.match(workflow, /uses:\s*actions\/checkout@/);
  assert.match(workflow, /SOURCE_SHA:\s*\$\{\{\s*github\.sha\s*}}/);
  assert.match(workflow, /ref:\s*\$\{\{\s*env\.SOURCE_SHA\s*}}/);
  assert.match(workflow, /sparse-checkout:[\s\S]*deploy\/preflight/);
  assert.match(workflow, /persist-credentials:\s*false/);
  assert.doesNotMatch(workflow, /ref:\s*(?:main|develop|feature\/)|fetch-depth:\s*0/);
});

test('preflight sources do not deploy migrate install stop services or call paid providers', () => {
  const sources = ['.github/workflows/production-preflight.yml', 'deploy/preflight/host-preflight.sh', 'deploy/preflight/run-preflight.py', 'deploy/preflight/ReadOnlyFlywayProbe.java'].map(read).join('\n');
  assert.doesNotMatch(sources, /\b(?:apt-get|apt|yum|dnf|systemctl|service)\s+(?:install|update|stop|restart|disable|enable)|docker\s+(?:compose|stop|restart|kill|rm|run|build|pull|push)|\.migrate\(|\.repair\(|deploy-release\.sh|rollback-release\.sh|api\.openai\.com|generativelanguage\.googleapis\.com/);
  assert.doesNotMatch(sources, /set\s+-[^\n]*x|set\s+-o\s+xtrace|print\([^\n]*(?:stderr|password|credential)/i);
});

test('successful SSM output preserves accurate API denial status without promoting it to HTTP success', () => {
  const output = successPayload();
  const result = pythonCall('summarize_invocation', ['Success', JSON.stringify(output), '']);
  assert.equal(result.ok, true);
  assert.equal(result.result.status, 'success');
  assert.equal(result.result.host.api.healthz, 200);
  assert.equal(result.result.host.api.openapi, 403, 'An HTTP denial must remain a denial, even when the SSM probe completed');
  assert.deepEqual(result.result.flyway.history, output.flyway.history);
});

test('all non-success SSM statuses reject stdout and stderr rather than claiming completed read checks', () => {
  for (const status of ['Pending', 'InProgress', 'Failed', 'TimedOut', 'Cancelled', 'AccessDenied']) {
    rejection('summarize_invocation', [status, JSON.stringify(successPayload()), 'synthetic confidential stderr'], 'UNTRUSTED_OUTPUT');
  }
});

test('malformed duplicate JSON wrong protocol and untrusted top-level credential fields cannot cross output boundary', () => {
  const good = JSON.stringify(successPayload());
  for (const text of ['not-json', `${good}\n${good}`, JSON.stringify(successPayload({ protocol: 'other' })), JSON.stringify(successPayload({ password: 'synthetic-confidential' })), JSON.stringify(successPayload({ stderr: 'synthetic-confidential' }))]) {
    rejection('summarize_invocation', ['Success', text, ''], 'UNTRUSTED_OUTPUT');
  }
});

test('nested credential token datasource and provider configuration fields are rejected rather than emitted', () => {
  for (const key of ['password', 'token', 'credentials', 'DATABASE_URL', 'OPENAI_API_KEY']) {
    const output = successPayload({ host: { [key]: 'synthetic-confidential' } });
    rejection('summarize_invocation', ['Success', JSON.stringify(output), ''], 'UNTRUSTED_OUTPUT');
  }
  const output = successPayload();
  output.flyway.history[0].password = 'synthetic-confidential';
  rejection('summarize_invocation', ['Success', JSON.stringify(output), ''], 'UNTRUSTED_OUTPUT');
});

test('host helper accepts exactly one base64 class payload and refuses path or shell payload arguments', () => {
  const helper = path.join(root, 'deploy/preflight/host-preflight.sh');
  for (const args of [[], ['Lw==', 'extra'], ['/tmp/untrusted.class'], ['$(unsafe-command)']]) {
    const result = cp.spawnSync('bash', [helper, ...args], { encoding: 'utf8', timeout: 5_000, env: { PATH: '/usr/bin:/bin', HOME: os.tmpdir() } });
    assert.notEqual(result.status, 0, 'Unsafe helper input must fail before container or API work');
    assert.ok(!(result.stdout + result.stderr).includes('DATABASE_PASSWORD='), 'Errors must not reveal environment values');
  }
});

const fakeDriver = String.raw`
package org.postgresql;
import java.sql.*; import java.util.*; import java.lang.reflect.*;
public final class Driver implements java.sql.Driver {
 static { try { DriverManager.registerDriver(new Driver()); } catch(Exception e) { throw new RuntimeException(e); } }
 static boolean readOnly=false, autoCommit=true, rollback=false; static int timeout=0;
 static final List<String> queries=new ArrayList<>();
 public Connection connect(String url,Properties properties) {
  return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
   switch(m.getName()) {
    case "setReadOnly": readOnly=(Boolean)a[0]; return null;
    case "setAutoCommit": autoCommit=(Boolean)a[0]; return null;
    case "isReadOnly": return readOnly;
    case "getAutoCommit": return autoCommit;
    case "rollback": rollback=true; return null;
    case "commit": throw new AssertionError("COMMIT forbidden");
    case "createStatement": return statement();
    case "close": System.err.println("JDBC_CONTRACT readOnly="+readOnly+" autoCommit="+autoCommit+" rollback="+rollback+" timeout="+timeout); return null;
    case "isClosed": return false;
    default: return defaultValue(m.getReturnType());
   }
  });
 }
 static Statement statement() {
  return (Statement)Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
   if(m.getName().equals("setQueryTimeout")) { timeout=(Integer)a[0];return null; }
   if(m.getName().equals("execute")||m.getName().equals("executeQuery")) {
    String sql=((String)a[0]).toLowerCase(Locale.ROOT); queries.add(sql);
    if(!(sql.startsWith("select")||sql.startsWith("show")||sql.startsWith("set transaction read only")||sql.startsWith("set local statement_timeout")||sql.startsWith("set local lock_timeout"))) throw new AssertionError("write SQL forbidden");
    System.err.println("JDBC_SQL "+sql);
    if("true".equals(System.getenv("FAKE_SQL_FAILURE"))&&sql.contains("flyway_schema_history")) throw new SQLException("synthetic password must not print", "42501");
    return m.getName().equals("executeQuery") ? rows(sql) : false;
   }
   return defaultValue(m.getReturnType());
  });
 }
 static ResultSet rows(String sql) {
  int[] row={0};
  return (ResultSet)Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->{
   if(m.getName().equals("next")) return row[0]++==0;
   if(m.getName().equals("getString")) {
    String key=String.valueOf(a[0]);
    if(sql.contains("transaction_read_only"))return "on";
    if(sql.contains("current_database"))return "fixture_database";
    if(sql.contains("version()")||sql.contains("server_version"))return "18.0";
    if(key.equals("version")||key.equals("1"))return "8";
    if(key.equals("script"))return "V8__fixture.sql";
    return "fixture";
   }
   if(m.getName().equals("getInt"))return 42;
   if(m.getName().equals("getBoolean"))return true;
   if(m.getName().equals("wasNull"))return false;
   return defaultValue(m.getReturnType());
  });
 }
 static Object defaultValue(Class<?> type) { if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null; }
 public boolean acceptsURL(String url){return url.startsWith("jdbc:postgresql:");} public DriverPropertyInfo[] getPropertyInfo(String u,Properties p){return new DriverPropertyInfo[0];} public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
}
`;

function javaProbe(failure) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-preflight-jdbc-'));
  try {
    fs.mkdirSync(path.join(directory, 'org/postgresql'), { recursive: true });
    const driver = path.join(directory, 'org/postgresql/Driver.java');
    fs.writeFileSync(driver, fakeDriver);
    const javaHome = process.env.JAVA_HOME || '/workspace/toolchains/jdk-17.0.20.1+1';
    const compilation = cp.spawnSync(path.join(javaHome, 'bin/javac'), ['-d', directory, driver, path.join(root, 'deploy/preflight/ReadOnlyFlywayProbe.java')], { encoding: 'utf8', timeout: 10_000 });
    assert.equal(compilation.status, 0, 'The standalone probe and synthetic JDBC driver must compile');
    const result = cp.spawnSync(path.join(javaHome, 'bin/java'), ['-cp', directory, 'ReadOnlyFlywayProbe'], {
      encoding: 'utf8', timeout: 10_000,
      env: { PATH: process.env.PATH, DATABASE_URL: 'jdbc:postgresql://fixture.invalid/fixture_database', DATABASE_USERNAME: 'fixture_user', DATABASE_PASSWORD: 'synthetic-confidential', FAKE_SQL_FAILURE: String(failure) },
    });
    assert.ok(!(result.stdout + result.stderr).includes('synthetic-confidential'), 'Credential values must not be emitted');
    assert.ok(!(result.stdout + result.stderr).includes('synthetic password must not print'), 'SQLException messages must not be emitted');
    return result;
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
}

test('actual standalone JDBC probe uses read-only transaction bounded statements and rollback with only Flyway metadata', () => {
  const result = javaProbe(false);
  assert.equal(result.status, 0, 'The synthetic metadata query must succeed');
  const output = JSON.parse(result.stdout);
  assert.equal(output.status, 'success');
  assert.equal(output.database, 'fixture_database');
  assert.deepEqual(output.history, [{ version: '8', script: 'V8__fixture.sql', checksum: 42, success: true }]);
  assert.match(result.stderr, /readOnly=true autoCommit=false rollback=true timeout=5/);
  assert.match(result.stderr, /set transaction read only/);
  assert.match(result.stderr, /show transaction_read_only/);
  assert.match(result.stderr, /set local statement_timeout\s*=\s*'?5000'?/);
  assert.match(result.stderr, /set local lock_timeout\s*=\s*'?2000'?/);
  assert.doesNotMatch(result.stderr, /\b(?:insert|update|delete|alter|drop|create table|grant|revoke)\b|raw_text|participants|submissions/);
});

test('JDBC permission denial still rolls back and reports only SQLState without credential exception text', () => {
  const result = javaProbe(true);
  assert.ok(result.stdout.trim().startsWith('{'), 'Permission denial must return fixed JSON metadata rather than a raw exception');
  const output = JSON.parse(result.stdout);
  assert.deepEqual(output, { status: 'unavailable', reason: 'SQL_42501' });
  assert.match(result.stderr, /rollback=true/);
});

test('SSM orchestration never prints raw command response or captured stderr on success or denial', () => {
  const source = read('deploy/preflight/run-preflight.py');
  assert.doesNotMatch(source, /print\([^\n]*(?:StandardOutputContent|StandardErrorContent|stderr)|console\.log|logger\.[a-z]+\([^\n]*(?:stderr|password)/);
  assert.match(source, /summarize_invocation/);
  assert.match(source, /validate_target/);
});


test('parameter checks read metadata only and host JVM starts with a cleared environment', () => {
  const runner = read('deploy/preflight/run-preflight.py');
  assert.match(runner, /describe-parameters/);
  assert.doesNotMatch(runner, /get-parameters?(?:\b|['"])/);
  assert.doesNotMatch(runner, /with-decryption|get-secret-value/);
  const host = read('deploy/preflight/host-preflight.sh');
  assert.match(host, /env\s+-i/, 'The standalone probe must not inherit ambient JVM options or classpath');
});


const knownFilePaths = [
  'guard/host-release-guard.sh', 'guard/compose.guard.yml',
  'state/installation-manifest.json', 'state/prerequisites.json',
  'state/compatible-images.tsv', 'state/phase', 'state/minimum-contract',
  'guard/meet-me-guarded-restart.service',
];
const fixtureImage = `123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me@sha256:${'b'.repeat(64)}`;

function safeHostMetadata() {
  return {
    api: { healthz: 200, openapi: 403 },
    files: Object.fromEntries(knownFilePaths.map((file, index) => [file, index === 1
      ? { exists: false }
      : { exists: true, uid: 0, mode: '0o750', regular: true, sha256: 'c'.repeat(64) }])),
    currentRelease: `/opt/meet-me/releases/${sha}-12345`,
    phase: 'READY',
    'minimum-contract': 'input_revision_v8',
    approvedImages: [`${fixtureImage}\tinput_revision_v8\t${'d'.repeat(64)}`],
    containerBefore: { present: true, running: true, pid: 4321, restartCount: 0, restartPolicy: 'no', image: fixtureImage, health: 'healthy' },
    containerAfter: { running: true, pid: 4321, restartCount: 0 },
    service: { LoadState: 'loaded', ActiveState: 'active', UnitFileState: 'enabled' },
  };
}

function assertHostRejected(host) {
  rejection('summarize_invocation', ['Success', JSON.stringify(successPayload({ host })), ''], 'UNTRUSTED_OUTPUT');
}

test('fully typed known host metadata and absent files or containers pass without arbitrary output expansion', () => {
  const host = safeHostMetadata();
  const result = pythonCall('summarize_invocation', ['Success', JSON.stringify(successPayload({ host })), '']);
  assert.equal(result.ok, true);
  assert.deepEqual(result.result.host, host);
  const absent = { files: { [knownFilePaths[0]]: { exists: false } }, containerBefore: { present: false }, containerAfter: { present: false }, service: {} };
  assert.equal(pythonCall('summarize_invocation', ['Success', JSON.stringify(successPayload({ host: absent })), '']).ok, true);
});

test('arbitrary approved-image values malformed digests review IDs and contract names cannot enter sanitized metadata', () => {
  for (const entry of [
    'arbitrary-confidential-text',
    `${fixtureImage}\tinput_revision_v8\tarbitrary-review`,
    `${fixtureImage}\tother-contract\t${'d'.repeat(64)}`,
    `${fixtureImage}\tinput_revision_v8\t${'d'.repeat(64)}\textra`,
    `not-an-ecr-image@sha256:${'b'.repeat(64)}\tinput_revision_v8\t${'d'.repeat(64)}`,
  ]) assertHostRejected({ approvedImages: [entry] });
});

test('only eight declared file paths and strict file metadata types may cross the output boundary', () => {
  assertHostRejected({ files: { 'unexpected-path': { harmlessName: 'arbitrary-confidential-text' } } });
  const file = { exists: true, uid: 0, mode: '0o600', regular: true };
  for (const invalid of [
    { ...file, harmlessName: 'arbitrary-confidential-text' },
    { exists: false, uid: 0 },
    { ...file, uid: '0' },
    { ...file, uid: true },
    { ...file, mode: 'arbitrary-confidential-text' },
    { ...file, mode: '0o888' },
    { ...file, regular: 'true' },
    { ...file, sha256: 'arbitrary-confidential-text' },
    { ...file, sha256: 'a'.repeat(63) },
  ]) assertHostRejected({ files: { [knownFilePaths[0]]: invalid } });
});

test('container before and after reject unknown nested values mistyped state and arbitrary image health or restart policy', () => {
  const before = safeHostMetadata().containerBefore;
  for (const invalid of [
    { arbitraryValue: 'arbitrary-confidential-text' },
    { ...before, harmlessName: 'arbitrary-confidential-text' },
    { ...before, running: 'true' },
    { ...before, pid: true },
    { ...before, restartCount: '0' },
    { ...before, restartPolicy: 'arbitrary-confidential-text' },
    { ...before, image: 'arbitrary-confidential-text' },
    { ...before, health: 'arbitrary-confidential-text' },
    { present: false, image: fixtureImage },
  ]) assertHostRejected({ containerBefore: invalid });
  for (const invalid of [
    { arbitraryValue: 'arbitrary-confidential-text' },
    { running: true, pid: 4321, restartCount: 0, image: fixtureImage },
    { running: true, pid: '4321', restartCount: 0 },
    { running: true, pid: 4321, restartCount: false },
  ]) assertHostRejected({ containerAfter: invalid });
});

test('service release phase and minimum contract metadata reject unrecognized fields and arbitrary text', () => {
  for (const invalid of [
    { harmlessName: 'arbitrary-confidential-text' },
    { LoadState: 'arbitrary-confidential-text' },
    { ActiveState: 'arbitrary-confidential-text' },
    { UnitFileState: 'arbitrary-confidential-text' },
  ]) assertHostRejected({ service: invalid });
  for (const field of ['currentRelease', 'phase', 'minimum-contract']) {
    assertHostRejected({ [field]: 'arbitrary-confidential-text' });
  }
  assertHostRejected({ currentRelease: `/opt/meet-me/releases/${sha};unsafe-command` });
});
