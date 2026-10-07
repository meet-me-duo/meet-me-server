const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const root = path.resolve(__dirname, "../..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");

test("release package includes durable guard, private Compose override and staged boot unit", () => {
  const workflow = read(".github/workflows/deploy.yml");
  const start = workflow.indexOf("tar -czf release.tar.gz");
  const end = workflow.indexOf("aws s3api put-object", start);
  assert.ok(start >= 0 && end > start, "The full guard package must precede immutable S3 publication");
  const packageBlock = workflow.slice(start, end);
  for (const artifact of ["compose.production.yml", "compose.guard.yml", "meet-me-guarded-restart.service", "scripts"]) {
    assert.ok(packageBlock.includes(artifact), `Missing packaged artifact: ${artifact}`);
  }
});

test("SSM deployment dispatch uses fixed installed host authority through the validated host driver", () => {
  const source = read("deploy/pipeline/host-approved-release.py");
  assert.ok(source.includes("/opt/meet-me/guard/host-release-guard.sh"));
  assert.match(source, /["']deploy["']/);
  assert.match(source, /validate_deploy_facts/);
  assert.doesNotMatch(source, /release_directory\/scripts\/deploy-release\.sh|\[.*deploy-release\.sh/);
});

test("all canonical lifecycle wrappers delegate to the same installed guard", () => {
  const expected = {"deploy-release.sh": "deploy", "rollback-release.sh": "rollback", "refresh-database-credential.sh": "refresh"};
  for (const [name, action] of Object.entries(expected)) {
    const source = read(`deploy/scripts/${name}`);
    assert.ok(source.includes("/opt/meet-me/guard/host-release-guard.sh"));
    assert.ok(source.includes(`exec "$launcher" ${action}`));
    assert.doesNotMatch(source, /\bdocker\s+(?:compose|run|start|exec)\b/);
  }
});

test("boot artifact enters guarded restart and app has no autonomous Docker restart", () => {
  assert.match(read("deploy/meet-me-guarded-restart.service"), /^ExecStart=\/opt\/meet-me\/guard\/host-release-guard\.sh restart$/m);
  const app = read("deploy/compose.production.yml").split("  app:")[1].split("  nginx:")[0];
  assert.match(app, /^\s+restart:\s*["']?no["']?\s*$/m);
  const override = read("deploy/compose.guard.yml");
  assert.ok(override.includes("${APP_IMAGE:?APP_IMAGE is required}"));
  assert.match(override, /restart:\s*["']no["']/);
});

test("CI runs both independent guard suites and static deployment contracts", () => {
  const ci = read(".github/workflows/ci.yml");
  assert.ok(ci.includes("python3 -m unittest discover -s deploy/tests -p 'test_v8_release_guard*.py'"));
  assert.ok(ci.includes("deploy-guard-contract.test.cjs"), "CI must execute the new static contract test file");
});
