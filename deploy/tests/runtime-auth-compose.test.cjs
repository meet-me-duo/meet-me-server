const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const test = require('node:test');

// Real local Docker/Compose with a cached Alpine image and synthetic files only.
// No pull, network, application image, /opt path, real key or production service.
const repository = path.resolve(__dirname, '../..');
const image = 'alpine:3.22';
const compose = path.join(repository, 'deploy/compose.production.yml');
const opaqueSecret = 'synthetic$client-\'"\\#secret';
function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-auth-mount-'));
  const keys = path.join(root, 'keys'); fs.mkdirSync(keys);
  fs.writeFileSync(path.join(keys, 'signing-private.pem'), 'SYNTHETIC_PRIVATE_FILE\n');
  fs.writeFileSync(path.join(keys, 'verification.jwks.json'), '{"keys":[],"fixture":true}\n');
  const runtime = path.join(root, 'runtime.env'); fs.writeFileSync(runtime, `DATABASE_PASSWORD=synthetic-only\nGOOGLE_CLIENT_SECRET=${opaqueSecret}\nKAKAO_CLIENT_SECRET=${opaqueSecret}\nAUTH_SIGNING_PRIVATE_KEY_PATH=/run/meetme-auth/signing-private.pem\nAUTH_VERIFICATION_JWKS_PATH=/run/meetme-auth/verification.jwks.json\n`, { mode: 0o600 });
  const project = `meetme-auth-contract-${process.pid}-${Math.random().toString(16).slice(2, 8)}`;
  const environment = { PATH: process.env.PATH, HOME: process.env.HOME, APP_IMAGE: image, RUNTIME_ENV_FILE: runtime, AUTH_KEYS_DIRECTORY: keys };
  const run = (args, extra = {}) => cp.spawnSync('docker', ['compose', '--project-name', project, '-f', compose, ...args], { env: { ...environment, ...extra }, encoding: 'utf8', timeout: 20_000 });
  function ownership(uid, gid, restricted) {
    // Bind only this freshly-created temporary directory; no host paths outside it.
    const result = cp.spawnSync('docker', ['run', '--rm', '--pull=never', '--network=none', '--mount', `type=bind,source=${keys},target=/fixture`, image, 'sh', '-c', `chown -R ${uid}:${gid} /fixture && chmod ${restricted ? '700' : '755'} /fixture && chmod ${restricted ? '400' : '600'} /fixture/*`], { encoding: 'utf8', timeout: 20_000 });
    assert.equal(result.error, undefined, 'Local Docker fixture must execute'); assert.equal(result.status, 0, 'Synthetic fixture ownership preparation must succeed');
  }
  return { root, keys, run, ownership, remove() { run(['down', '--remove-orphans']); ownership(process.getuid(), process.getgid(), false); fs.rmSync(root, { recursive: true, force: true }); } };
}

test('AC01 production Compose declares a required read-only long bind without automatic host-path creation', () => {
  const f = fixture(); try {
    const result = f.run(['config', '--format', 'json']); assert.equal(result.error, undefined); assert.equal(result.status, 0);
    const app = JSON.parse(result.stdout).services.app; const mount = (app.volumes ?? []).find(item => item.target === '/run/meetme-auth');
    assert.ok(mount, 'RSA/JWKS directory must be explicitly mounted into the app');
    assert.equal(mount.type, 'bind'); assert.equal(mount.source, f.keys); assert.equal(mount.read_only, true);
    // Compose normalizes explicit false by omitting it from config JSON. Keep
    // authored false mandatory and test its real missing-path behavior in AC03.
    assert.notEqual(mount.bind?.create_host_path, true);
    assert.equal(app.read_only, true); assert.equal(app.restart, 'no');
    const file = fs.readFileSync(compose, 'utf8'); assert.ok(file.includes('${AUTH_KEYS_DIRECTORY:-/opt/meet-me/auth}'), 'Default host path must match the accepted runbook');
    assert.match(file, /target:\s*\/run\/meetme-auth\s*\n\s*read_only:\s*true\s*\n\s*bind:\s*\n\s*create_host_path:\s*false/, 'Auth bind must explicitly refuse host-path creation');
    const dockerfile = fs.readFileSync(path.join(repository, 'Dockerfile'), 'utf8'); assert.match(dockerfile, /^USER 10001:10001$/m, 'Production app uses the existing fixed UID/GID');
  } finally { f.remove(); }
});

test('AC02 actual UID10001 reads owner-only synthetic key files but cannot write into the read-only bind', () => {
  const f = fixture(); try {
    f.ownership(10001, 10001, true);
    const result = f.run(['run', '--rm', '--no-deps', '--pull', 'never', '--user', '10001:10001', '--entrypoint', 'sh', 'app', '-c', 'test "$(id -u):$(id -g)" = 10001:10001 && test "$(cat /run/meetme-auth/signing-private.pem)" = SYNTHETIC_PRIVATE_FILE && test -r /run/meetme-auth/verification.jwks.json && ! touch /run/meetme-auth/forbidden 2>/dev/null && ! sh -c ": > /run/meetme-auth/signing-private.pem" 2>/dev/null']);
    assert.equal(result.error, undefined, 'Actual container must execute without harness timeout');
    assert.equal(result.status, 0, 'UID10001 must read the provided files while every bind write is rejected');
    assert.equal(result.stdout, ''); assert.ok(!result.stderr.includes('SYNTHETIC_PRIVATE_FILE'));
    // Return synthetic fixture ownership before the non-root host inspects bytes.
    f.ownership(process.getuid(), process.getgid(), false);
    assert.equal(fs.readFileSync(path.join(f.keys, 'signing-private.pem'), 'utf8'), 'SYNTHETIC_PRIVATE_FILE\n');
    assert.ok(!fs.existsSync(path.join(f.keys, 'forbidden')));
  } finally { f.remove(); }
});

test('AC03 missing auth bind path is refused without creating a host directory or running the app command', () => {
  const f = fixture(); try {
    const missing = path.join(f.root, 'missing-auth');
    const result = f.run(['run', '--rm', '--no-deps', '--pull', 'never', '--entrypoint', 'sh', 'app', '-c', 'printf APP_EXECUTED'], { AUTH_KEYS_DIRECTORY: missing });
    assert.equal(result.error, undefined); assert.notEqual(result.status, 0, 'A missing operator-provisioned bind must prevent startup');
    assert.ok(!result.stdout.includes('APP_EXECUTED'), 'App command must not run when the mount is absent');
    assert.ok(!fs.existsSync(missing), 'Compose must never create an empty auth directory automatically');
  } finally { f.remove(); }
});

test('AC04 non-root runtime cannot read wrongly-owned synthetic private files', () => {
  const f = fixture(); try {
    f.ownership(0, 0, true);
    const result = f.run(['run', '--rm', '--no-deps', '--pull', 'never', '--user', '10001:10001', '--entrypoint', 'sh', 'app', '-c', 'test ! -r /run/meetme-auth/signing-private.pem && test ! -r /run/meetme-auth/verification.jwks.json']);
    assert.equal(result.error, undefined); assert.equal(result.status, 0, 'Synthetic owner-only permissions must not be bypassed by UID10001');
  } finally { f.remove(); }
});

test('AC05 raw Compose environment preserves opaque OAuth secrets and mounted file path references', () => {
  const f = fixture(); try {
    const result = f.run(['run', '--rm', '--no-deps', '--pull', 'never', '--user', '10001:10001', '--entrypoint', 'printenv', 'app', 'GOOGLE_CLIENT_SECRET', 'KAKAO_CLIENT_SECRET', 'AUTH_SIGNING_PRIVATE_KEY_PATH', 'AUTH_VERIFICATION_JWKS_PATH']);
    assert.equal(result.error, undefined); assert.equal(result.status, 0);
    const expected = `${opaqueSecret}\n${opaqueSecret}\n/run/meetme-auth/signing-private.pem\n/run/meetme-auth/verification.jwks.json\n`;
    assert.ok(result.stdout === expected, 'Opaque OAuth values and path references must pass through raw env_file byte-for-byte');
    assert.ok(!result.stderr.includes(opaqueSecret));
  } finally { f.remove(); }
});

test('AC06 CI executes the actual isolated auth mount contract', () => {
  const ci = fs.readFileSync(path.join(repository, '.github/workflows/ci.yml'), 'utf8');
  assert.ok(ci.includes('deploy/tests/runtime-auth-compose.test.cjs'), 'Required mount verification must be part of CI');
});
