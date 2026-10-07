const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const root = path.resolve(__dirname, "../..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");

function deploymentCommands() {
  const source = read(".github/workflows/deploy.yml");
  const start = source.indexOf("'{commands:[");
  assert.ok(start >= 0, "SSM must retain its explicit command contract");
  const end = source.indexOf("]}')", start);
  assert.ok(end > start, "SSM command list must be complete");
  return source.slice(start, end).split("\n");
}

function extractionBoundary() {
  const commands = deploymentCommands();
  const extract = commands.findIndex((line) => /^\s*"tar\s/.test(line));
  const launch = commands.findIndex((line) => line.includes("/opt/meet-me/guard/host-release-guard.sh deploy"));
  assert.ok(extract >= 0 && launch > extract, "SSM must extract the release before fixed guard dispatch");
  return {commands, extract, launch};
}

test("SSM applies restrictive umask before creating and extracting release files", () => {
  const {commands, extract} = extractionBoundary();
  const mask = commands.findIndex((line) => /^\s*"umask\s+0?(?:27|77)"\s*,?\s*$/.test(line));
  const create = commands.findIndex((line) => line.includes("install -d"));
  assert.ok(mask >= 0 && mask < extract && (create < 0 || mask < create),
    "SSM must apply umask 027 or 077 before release creation/extraction");
});

test("SSM extraction keeps invoking root ownership rather than archived CI owner", () => {
  const {commands, extract} = extractionBoundary();
  assert.match(commands[extract], /(?:^|\s)--no-same-owner(?:\s|"|$)/,
    "Root SSM extraction must use --no-same-owner before guard root-owner validation");
  assert.doesNotMatch(commands[extract], /(?:^|\s)--same-owner(?:\s|"|$)/);
});

test("SSM extraction honors restrictive umask instead of restoring archived modes", () => {
  const {commands, extract} = extractionBoundary();
  assert.match(commands[extract], /(?:^|\s)--no-same-permissions(?:\s|"|$)/,
    "Root SSM extraction must use --no-same-permissions before fixed guard dispatch");
  assert.doesNotMatch(commands[extract], /(?:^|\s)--same-permissions(?:\s|"|$)/);
});

test("CI executes the independent deployment extraction contract", () => {
  assert.match(read(".github/workflows/ci.yml"),
    /^\s*run:\s*node\s+--test[^\n]*\.github\/scripts\/deploy-extraction-contract\.test\.cjs[^\n]*$/m,
    "CI must run the new extraction contract alongside existing deployment checks");
});
