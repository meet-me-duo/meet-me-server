const assert = require('node:assert/strict');
const test = require('node:test');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, crc, hash, canonical } = f;

// Public reviewed inventory, independent of the Python publisher/reader constants.
const scripts = [...f.scripts,
  'V18__align_recommendation_selection_attendance.sql',
  'V19__add_confirmed_result_reviews.sql',
  'V20__add_account_room_history_index.sql',
  'V21__add_submission_input_evidence.sql'];
const currentScripts = [...scripts, 'V22__preserve_unverified_candidate_place.sql'];

function fixture() {
  const value = f.currentFixture();
  for (const script of currentScripts.slice(17)) {
    const source = path.join(root, 'src/main/resources/db/migration', script);
    assert.equal(fs.existsSync(source), true, 'Missing SQL is test setup failure, not valid catalog RED');
    fs.copyFileSync(source, path.join(value.migrations, script));
  }
  return value;
}

test('V21C01 current publication retains V21 in all twenty-two real SQL files and exact independent checksums', () => {
  assert.deepEqual(fs.readdirSync(path.join(root, 'src/main/resources/db/migration')).sort(), [...currentScripts].sort());
  const value = fixture();
  try {
    const actual = f.publish(value);
    assert.equal(actual.ok, true, 'The exact additive V22 catalog must be recognized');
    assert.deepEqual(actual.result.migrations, currentScripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    const schema = currentScripts.map((script, i) => ({ version: String(i + 1), script,
      sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }));
    assert.equal(actual.result.schemaFingerprint, hash(canonical(schema)));
  } finally { value.remove(); }
});

test('V21C02 current publisher rejects every missing reviewed version and unknown twenty-three', () => {
  for (const missing of currentScripts) {
    const value = fixture();
    try {
      fs.unlinkSync(path.join(value.migrations, missing));
      assert.deepEqual(f.publish(value), f.untrusted, missing);
    } finally { value.remove(); }
  }
  const value = fixture();
  try {
    fs.copyFileSync(path.join(value.migrations, scripts.at(-1)), path.join(value.migrations, 'V23__unknown.sql'));
    assert.deepEqual(f.publish(value), f.untrusted);
  } finally { value.remove(); }
});

test('V21C03 exact V21 and historical V20 metadata remain independently readable', () => {
  for (const count of [20, 21]) {
    const manifest = f.manifest();
    manifest.migrations = scripts.slice(0, count).map((script, i) => ({ version: String(i + 1), script, checksum: (i + 1) * 37 }));
    manifest.schemaFingerprint = hash(canonical(manifest.migrations));
    assert.equal(f.validate(manifest).ok, true, `Complete V${count} catalog`);
    assert.deepEqual(f.readRecord(f.record(manifest)), { ok: true, result: f.record(manifest) });
  }
});
