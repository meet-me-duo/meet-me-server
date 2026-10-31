const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

// Issue #178: preserve the approved CI checks while allowing their two independent
// jobs to run concurrently. The public pre-change source, not the implementation,
// is the oracle. No YAML package, shell command, credential or network is needed.
const root = path.resolve(__dirname, '../..');
const frozen = require('./fixtures/ci-original-checks.json');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const boundaryCommand = 'node --test .github/scripts/ci-job-boundaries.test.cjs';

// This intentionally accepts the existing explicit block layout only. Exact
// source comparisons below reject added conditions, authority, failure masking,
// aliases and duplicate YAML keys rather than silently ignoring unknown fields.
function workflow(source) {
  const marker = '\njobs:\n';
  const at = source.indexOf(marker);
  assert.ok(at >= 0, 'CI must declare an explicit jobs mapping');
  assert.equal(source.indexOf(marker, at + marker.length), -1, 'Jobs mapping must be unique');
  const rootSource = source.slice(0, at + marker.length).trimEnd();
  const body = source.slice(at + marker.length);
  const starts = [...body.matchAll(/^  ([A-Za-z][\w-]*):\n/gm)];
  assert.ok(starts.length > 0 && body.slice(0, starts[0].index).trim() === '', 'Jobs must use explicit named blocks');
  const jobs = new Map();
  starts.forEach((match, index) => {
    assert.ok(!jobs.has(match[1]), `Duplicate job: ${match[1]}`);
    jobs.set(match[1], body.slice(match.index, starts[index + 1]?.index).trimEnd());
  });
  return { rootSource, jobs };
}

function job(workflowSource, id) {
  const source = workflowSource.jobs.get(id);
  assert.ok(source, `Required independent CI job is missing: ${id}`);
  const marker = '    steps:\n';
  const at = source.indexOf(marker);
  assert.ok(at >= 0, `${id} must have explicit steps`);
  assert.equal(source.indexOf(marker, at + marker.length), -1, `${id} steps mapping must be unique`);
  const header = source.slice(0, at).trimEnd();
  const body = source.slice(at + marker.length);
  const starts = [...body.matchAll(/^      - /gm)];
  assert.ok(starts.length > 0 && body.slice(0, starts[0].index).trim() === '', `${id} steps must be explicit blocks`);
  return { header, steps: starts.map((match, index) => body.slice(match.index, starts[index + 1]?.index).trimEnd()) };
}

function command(step) {
  const match = step.match(/^        run: (.*)$/m);
  if (!match) return null;
  if (match[1] === '|') {
    // Preserve literal block newlines, including YAML's final clip newline.
    return step.slice(match.index + match[0].length + 1).split('\n').map(line => line.slice(10)).join('\n') + '\n';
  }
  return match[1];
}

test('Issue178 baseline contains all 18 original commands including Nginx, V22 and ARM64', () => {
  assert.equal(frozen.contract, 'issue-178-ci-job-boundaries-v1');
  assert.equal(frozen.sourceRevision, '8b36fd5415bc4773e91f29fedc5ddf257ef6b12d');
  assert.equal(frozen.setupSteps.length, 2);
  assert.equal(frozen.qualityRunSteps.length, 17);
  assert.equal(frozen.originalRunCommands.length, 18);
  assert.equal(new Set(frozen.originalRunCommands).size, 18);
  assert.ok(frozen.originalRunCommands.includes('bash deploy/scripts/verify-nginx-runtime.sh'));
  assert.ok(frozen.originalRunCommands.some(run => run.includes('v22-conditional-place-release-catalog.test.cjs')));
  assert.ok(frozen.originalRunCommands.some(run => run.includes('verify-built-image.sh')));
});

test('Issue178 keeps CI identity, triggers, concurrency, read authority and exactly three jobs', () => {
  const ci = workflow(read('.github/workflows/ci.yml'));
  assert.equal(ci.rootSource, frozen.rootSource, 'The complete root CI contract must remain unchanged');
  assert.deepEqual([...ci.jobs.keys()].sort(), ['built-image', 'deployment-contracts', 'quality']);
});

test('Issue178 Quality Gate retains Java17, the exact Gradle command and its 30 minute boundary', () => {
  const quality = job(workflow(read('.github/workflows/ci.yml')), 'quality');
  assert.equal(quality.header, frozen.qualityHeader, 'Quality Gate stays unconditional, independent and without extra authority');
  assert.deepEqual(quality.steps, [...frozen.setupSteps, frozen.qualityRunSteps[0]], 'Only the existing checkout, Java17 and Gradle quality gate remain in this job');
});

test('Issue178 Deployment Contracts runs its own setup then the new boundary check and all 16 original checks', () => {
  const contracts = job(workflow(read('.github/workflows/ci.yml')), 'deployment-contracts');
  const expectedHeader = frozen.qualityHeader.replace('  quality:', '  deployment-contracts:').replace('    name: Quality Gate', '    name: Deployment Contracts');
  assert.equal(contracts.header, expectedHeader, 'Deployment Contracts must run in parallel on ubuntu-latest for 30 minutes without needs, if, environment or added permissions');
  assert.equal(contracts.steps.length, 19, 'Two setup steps, one boundary check and all 16 contracts must run');
  assert.deepEqual(contracts.steps.slice(0, 2), frozen.setupSteps, 'This job must check out source and configure Java17 independently');
  const boundaryStep = contracts.steps[2];
  assert.match(boundaryStep, /^      - name: [^\n]+\n        run: node --test \.github\/scripts\/ci-job-boundaries\.test\.cjs$/, 'The only new execution is the unconditionally blocking boundary test');
  assert.deepEqual(contracts.steps.slice(3), frozen.qualityRunSteps.slice(1), 'All original deployment checks retain their exact strings and meaningful order');
});

test('Issue178 executes each original command exactly once with only one new command', () => {
  const ci = workflow(read('.github/workflows/ci.yml'));
  const actual = [...ci.jobs.keys()].flatMap(id => job(ci, id).steps.map(command).filter(run => run !== null));
  for (const expected of frozen.originalRunCommands) {
    assert.equal(actual.filter(run => run === expected).length, 1, `Original CI command must run exactly once: ${expected}`);
  }
  assert.equal(actual.filter(run => run === boundaryCommand).length, 1, 'The new boundary check must run exactly once');
  assert.equal(actual.length, 19, 'No omitted, copied or additional execution commands');
});

test('Issue178 preserves the complete isolated ARM64 job and successful whole main CI publication gate', () => {
  const ci = workflow(read('.github/workflows/ci.yml'));
  assert.equal(ci.jobs.get('built-image'), frozen.arm64JobSource, 'ARM64 build, timeout, no-push and artifact proof stay unchanged');
  const deploy = workflow(read('.github/workflows/deploy.yml'));
  assert.equal(deploy.rootSource, frozen.publicationRootSource, 'Publication continues to be triggered by completed whole CI on main');
  assert.equal(job(deploy, 'build-publish').header, frozen.publicationGateHeader, 'Publication still requires success from the complete main push CI run');
});
