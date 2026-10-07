const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const root = path.resolve(__dirname, "../..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");

function deploymentCommands() {
  const driver = read("deploy/pipeline/run-approved-release.py");
  const host = read("deploy/pipeline/host-approved-release.py");
  assert.match(driver, /validate_manifest/, "The relocated SSM command contract must retain manifest validation");
  assert.match(host, /\/opt\/meet-me\/guard\/host-release-guard\.sh/, "Extracted release dispatch must retain fixed host authority");
  return (driver + "\n" + host).split("\n");
}

function extractionBoundary() {
  const commands = deploymentCommands();
  const extract = commands.findIndex((line) => line.includes("--no-same-owner"));
  assert.ok(extract >= 0, "The relocated SSM extraction must retain restrictive tar options");
  return {commands, extract};
}

test("SSM applies restrictive umask before creating and extracting release files", () => {
  const {commands, extract} = extractionBoundary();
  const mask = commands.findIndex((line) => /umask\s+0?(?:27|77)\b/.test(line));
  assert.ok(mask >= 0 && mask < extract,
    "SSM must apply umask 027 or 077 before release creation/extraction");
});

test("SSM extraction keeps invoking root ownership rather than archived CI owner", () => {
  const {commands, extract} = extractionBoundary();
  assert.match(commands.join("\n"), /--no-same-owner(?:\s|["']|$)/,
    "Root SSM extraction must use --no-same-owner before guard root-owner validation");
  assert.doesNotMatch(commands.join("\n"), /(?:^|\s|["'])--same-owner(?:\s|["']|$)/);
});

test("SSM extraction honors restrictive umask instead of restoring archived modes", () => {
  const {commands, extract} = extractionBoundary();
  assert.match(commands.join("\n"), /--no-same-permissions(?:\s|["']|$)/,
    "Root SSM extraction must use --no-same-permissions before fixed guard dispatch");
  assert.doesNotMatch(commands.join("\n"), /(?:^|\s|["'])--same-permissions(?:\s|["']|$)/);
});

test("CI executes the independent deployment extraction contract", () => {
  assert.match(read(".github/workflows/ci.yml"),
    /^\s*run:\s*node\s+--test[^\n]*\.github\/scripts\/deploy-extraction-contract\.test\.cjs[^\n]*$/m,
    "CI must run the new extraction contract alongside existing deployment checks");
});
