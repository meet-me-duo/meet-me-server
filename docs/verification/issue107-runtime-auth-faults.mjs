import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Independent fault injection: copy production and frozen tests to /tmp, mutate
// only that copy, restore exact bytes after each fault, then rerun its same oracle.
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'meetme-auth-independent-fi-'));
const logs = path.join(repository, '.codex/tdd-evidence/issue107-runtime-auth-fi');
fs.mkdirSync(logs, { recursive: true });
for (const file of ['deploy', 'Dockerfile', '.github/workflows/ci.yml']) {
  fs.mkdirSync(path.dirname(path.join(scratch, file)), { recursive: true });
  fs.cpSync(path.join(repository, file), path.join(scratch, file), { recursive: true });
}
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const tracked = ['deploy/scripts/render-runtime-env.sh', 'deploy/scripts/host-release-guard.sh', 'deploy/compose.production.yml', 'deploy/runtime-auth-mode', 'deploy/tests/runtime-auth-env.test.cjs', 'deploy/tests/runtime-auth-compose.test.cjs', 'deploy/tests/runtime-auth-raw-json.test.cjs', 'deploy/tests/runtime_auth_refresh_fixture.py'];
const before = Object.fromEntries(tracked.map(file => [file, sha(fs.readFileSync(path.join(repository, file)))]));
const replaceOnce = (source, from, to) => { assert.equal(source.split(from).length, 2, 'Fault anchor must occur once'); return source.replace(from, to); };
const renderer = 'deploy/scripts/render-runtime-env.sh';
const faults = [
  { id: 'atomic-publication-bypassed', file: renderer, test: 'deploy/tests/runtime-auth-env.test.cjs', pattern: '^AR11 ', mutate: source => replaceOnce(source, 'mv -T "$temporary" "$destination"', 'cp "$temporary" "$destination"') },
  { id: 'ancestor-symlink-check-removed', file: renderer, test: 'deploy/tests/runtime-auth-env.test.cjs', pattern: '^AR19 ', mutate: source => replaceOnce(source, '  while [[ "$entry" != / && "$entry" != . ]]; do\n    [[ ! -L "$entry" ]] || runtime_fail \'Runtime environment destination is unsafe\'\n    entry="$(dirname -- "$entry")"\n  done', '  : # injected omission of ancestor validation') },
  { id: 'aws-diagnostic-masking-removed', file: renderer, test: 'deploy/tests/runtime-auth-env.test.cjs', pattern: '^AR03 required auth refuses a missing secret/google-client-secret ', mutate: source => {
    const start = source.indexOf('get_auth_parameter() {'); assert.ok(start >= 0);
    const end = source.indexOf('\n}\n', start); const body = source.slice(start, end);
    return source.slice(0, start) + replaceOnce(body, '--with-decryption --output json 2>/dev/null |', '--with-decryption --output json |') + source.slice(end);
  } },
  { id: 'auth-bind-writable', file: 'deploy/compose.production.yml', test: 'deploy/tests/runtime-auth-compose.test.cjs', pattern: '^AC02 ', mutate: source => replaceOnce(source, 'target: /run/meetme-auth\n        read_only: true', 'target: /run/meetme-auth\n        read_only: false') },
  { id: 'configured-activation-dropped', file: renderer, test: 'deploy/tests/runtime-auth-env.test.cjs', pattern: '^AR21 ', mutate: source => replaceOnce(source, 'precheck_provider_enabled="$precheck_parameter"', 'precheck_provider_enabled=false') },
  { id: 'refresh-auth-lost-after-render', file: 'deploy/scripts/host-release-guard.sh', test: 'deploy/tests/runtime-auth-env.test.cjs', pattern: '^AR15 ', mutate: source => {
    const start = source.indexOf('guard_refresh() {'); const end = source.indexOf('\n}\n', start); assert.ok(start >= 0 && end > start);
    const body = source.slice(start, end);
    return source.slice(0, start) + replaceOnce(body, '  "$release/scripts/render-runtime-env.sh" "$release/.env.runtime"', '  "$release/scripts/render-runtime-env.sh" "$release/.env.runtime"\n  sed -i \'/^AUTH_/d; /^GOOGLE_/d; /^KAKAO_/d\' "$release/.env.runtime"') + source.slice(end);
  } },
];
function run(fault, phase) {
  const result = spawnSync('node', ['--test', '--test-reporter=tap', `--test-name-pattern=${fault.pattern}`, fault.test], { cwd: scratch, env: { PATH: process.env.PATH, HOME: process.env.HOME }, encoding: 'utf8', timeout: 60_000 });
  fs.writeFileSync(path.join(logs, `${fault.id}-${phase}.log`), result.stdout + result.stderr);
  assert.equal(result.error, undefined, 'Fault oracle must execute without setup timeout');
  const summary = name => Number(result.stdout.match(new RegExp(`^# ${name} (\\d+)$`, 'm'))?.[1] ?? -1);
  return { exit: result.status, tests: summary('tests'), passed: summary('pass'), failed: summary('fail'), cancelled: summary('cancelled') };
}
const results = [];
for (const fault of faults) {
  const file = path.join(scratch, fault.file); const original = fs.readFileSync(file);
  let mutant; let restored;
  try {
    fs.writeFileSync(file, fault.mutate(original.toString('utf8')));
    mutant = run(fault, 'mutant'); assert.notEqual(mutant.exit, 0); assert.ok(mutant.failed > 0); assert.equal(mutant.cancelled, 0);
  } finally { fs.writeFileSync(file, original); }
  assert.equal(sha(fs.readFileSync(file)), sha(original));
  restored = run(fault, 'restored'); assert.equal(restored.exit, 0); assert.equal(restored.failed, 0); assert.ok(restored.passed > 0);
  results.push({ id: fault.id, oracle: fault.test, pattern: fault.pattern, mutant, restored, exactRestore: true });
  console.log(JSON.stringify({ status: 'FAULT_DETECTED_AND_RESTORED', id: fault.id }));
}
for (const [file, expected] of Object.entries(before)) {
  assert.equal(sha(fs.readFileSync(path.join(repository, file))), expected, 'Original working-tree bytes must stay unchanged');
  assert.equal(sha(fs.readFileSync(path.join(scratch, file))), expected, 'Every copied test and production file must be restored');
}
const report = { status: 'PASS', scope: 'independent-scratch-only-auth-infrastructure-fault-injection', at: new Date().toISOString(), faults: results, sourceAndTestSha256: before, originalProductTreeMutated: false, testExpectationChanges: false, allCopiedTrackedBytesRestored: true, actualAwsCalls: 0, actualOAuthProviderCalls: 0, actualModelCalls: 0, productionAccess: false, logs: '.codex/tdd-evidence/issue107-runtime-auth-fi' };
fs.writeFileSync(path.join(repository, '.tdd/verification/issue107-runtime-auth-faults.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ status: 'PASS', faults: results.length, originalProductTreeMutated: false }));
