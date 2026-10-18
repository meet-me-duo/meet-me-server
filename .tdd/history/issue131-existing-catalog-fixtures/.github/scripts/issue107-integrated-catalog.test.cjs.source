const assert = require('node:assert/strict');
const test = require('node:test');
const f = require('./fixtures/integrated-release-catalog.cjs');
const { fs, path, root, scripts, guardFiles, range, catalogs, hash, canonical, clone, crc,
  manifest, history, record, ready, fixture, publish, validate, readRecord, judge, mode, untrusted, denied } = f;

test('C01 immutable V1 through V15 SQL remains byte-identical to the separately reviewed PRs', () => {
  const expected = [
    'f840adc70a59de4f7085f419f4063c34f5ed96e87a54b2aa4f052c15df023515',
    '75535992de90d7eedc1700487503418a300df6ca668eb18d517646517ebd40e7',
    'a1d7723e2461dbc6e483040f24fe9df8efc5d088b9ddc00ffaf1cdc842f12371',
    '14f90ca0642a4ee02242d334ebe542503907b509ee1a4fdfcc45f6c33905fe9d',
    '7127e1ccc1284865cc3b0ea38be3582a1870f7d3ec05e3700d4577d1084c3146',
    '526c1cca68d23c1ee9fe4fad6c7cf0937eb53df5e306ee75b327223855f8af7a',
    'd252715c33435f88498e8e0131ea5b35e6e081e63fdc91fab67b7f1b6235c747',
    '04ac022f09473e4d95128c765b2e274b7d1d1abb0fee4b722f56119638d22a92',
    'a6fc046611fb3cafa96d51f87de09687b3fdecc403988d211b7d86c4fad0585d',
    '694940de9100b58bc4a1ca2889d5d44fbd86221dead6805082b2c7999ee002ab',
    '0431b2c18a5f03c341d3d4c4d1f3456e169db610cb5bd92e3681978623b3b92c',
    'fce24fe4bb215ef2d40d0b2eddb2efd1fc98d57162681d5050236824a3cd11da',
    'b6ea047b7aa87486941b06d5cfbd4873449821ba6d684a8f52946053aa53e3ff',
    'cddce45ef10fc2eac99cb739a1b631f41634b2cd06905e19919692844ca40196',
    'fcbcd040cb232ca400cbfb24b944f8289428032daf6ce386c35dbc119f9b9f98',
  ];
  scripts.slice(0, 15).forEach((name, i) => assert.equal(hash(fs.readFileSync(path.join(root, 'src/main/resources/db/migration', name))), expected[i], name));
});

test('C02 publisher emits exactly canonical V1 through V17 in numeric order with independent checksums and fingerprints', () => {
  const value = fixture();
  try {
    const built = publish(value); assert.equal(built.ok, true, 'The final approved V17 source must be publishable');
    const catalog = scripts.map((script, i) => ({ version: String(i + 1), script, sha256: hash(fs.readFileSync(path.join(value.migrations, script))) }));
    assert.deepEqual(built.result.migrations, scripts.map((script, i) => ({ version: String(i + 1), script,
      checksum: crc(fs.readFileSync(path.join(value.migrations, script))) })));
    assert.equal(built.result.schemaFingerprint, hash(canonical(catalog)));
    assert.equal(built.result.guardFingerprint, hash(canonical(guardFiles.map(name => ({ path: name,
      sha256: hash(fs.readFileSync(path.join(value.directory, 'deploy', name))) })))));
    assert.equal(built.result.archiveSha256, hash(fs.readFileSync(value.archive)));
    assert.equal(validate(built.result).ok, true);
  } finally { value.remove(); }
});

test('C03 publisher rejects every omitted version plus duplicates unknown V18 noncanonical names and extra entries', () => {
  for (const fault of [...range(17).map(i => `missing-${i}`), 'duplicate17', 'unknown18', 'wrong1', 'wrong17', 'leadingZero', 'extra', 'directory']) {
    const value = fixture();
    try {
      const last = path.join(value.migrations, scripts[16]);
      if (fault.startsWith('missing-')) fs.unlinkSync(path.join(value.migrations, scripts[Number(fault.slice(8)) - 1]));
      if (fault === 'duplicate17') fs.copyFileSync(last, path.join(value.migrations, 'V17__duplicate.sql'));
      if (fault === 'unknown18') fs.copyFileSync(last, path.join(value.migrations, 'V18__unknown.sql'));
      if (fault.startsWith('wrong')) { const version = Number(fault.slice(5)); fs.renameSync(path.join(value.migrations, scripts[version - 1]), path.join(value.migrations, `V${version}__arbitrary.sql`)); }
      if (fault === 'leadingZero') fs.renameSync(last, path.join(value.migrations, 'V017__add_durable_ai_room_budgets.sql'));
      if (fault === 'extra') fs.writeFileSync(path.join(value.migrations, 'unapproved.txt'), 'synthetic');
      if (fault === 'directory') { fs.unlinkSync(last); fs.mkdirSync(last); }
      assert.deepEqual(publish(value), untrusted, fault);
    } finally { value.remove(); }
  }
});

test('C04 publisher rejects migration guard archive and ancestor symlinks despite matching target bytes', () => {
  for (const fault of ['migration', 'guard', 'archive', 'migration-directory', 'source-ancestor', 'guard-ancestor', 'repository-root']) {
    const value = fixture();
    try {
      let input = value;
      if (fault === 'repository-root') {
        const actual = path.join(value.directory, 'real'); fs.mkdirSync(actual);
        for (const name of fs.readdirSync(value.directory)) if (name !== 'real') fs.renameSync(path.join(value.directory, name), path.join(actual, name));
        const alias = path.join(value.directory, 'alias'); fs.symlinkSync(actual, alias, 'dir');
        input = { ...value, directory: alias, archive: path.join(actual, 'release.tar.gz') };
      } else {
        const relative = { migration: `src/main/resources/db/migration/${scripts[16]}`, guard: 'deploy/pipeline/approved_release.py',
          archive: 'release.tar.gz', 'migration-directory': 'src/main/resources/db/migration', 'source-ancestor': 'src/main', 'guard-ancestor': 'deploy/pipeline' }[fault];
        const target = path.join(value.directory, relative), actual = `${target}-real`;
        fs.renameSync(target, actual); fs.symlinkSync(actual, target, fs.statSync(actual).isDirectory() ? 'dir' : 'file');
      }
      assert.deepEqual(publish(input), untrusted, fault);
    } finally { value.remove(); }
  }
});

test('C05 schema guard and archive bytes independently change their fingerprints while first sixteen migrations remain fixed', () => {
  const value = fixture();
  try {
    const original = publish(value); assert.equal(original.ok, true);
    const last = path.join(value.migrations, scripts[16]); const raw = fs.readFileSync(last);
    fs.appendFileSync(last, '\n-- synthetic V17-only change\n');
    const changed = publish(value); assert.equal(changed.ok, true);
    assert.deepEqual(changed.result.migrations.slice(0, 16), original.result.migrations.slice(0, 16));
    assert.notEqual(changed.result.migrations[16].checksum, original.result.migrations[16].checksum);
    assert.notEqual(changed.result.schemaFingerprint, original.result.schemaFingerprint);
    assert.equal(changed.result.guardFingerprint, original.result.guardFingerprint);
    fs.writeFileSync(last, raw); fs.appendFileSync(path.join(value.directory, 'deploy/scripts/deploy-release.sh'), '\n# synthetic guard change\n');
    const guard = publish(value); assert.equal(guard.ok, true);
    assert.equal(guard.result.schemaFingerprint, original.result.schemaFingerprint);
    assert.notEqual(guard.result.guardFingerprint, original.result.guardFingerprint);
    fs.appendFileSync(value.archive, 'changed'); const archive = publish(value); assert.equal(archive.ok, true);
    assert.notEqual(archive.result.archiveSha256, guard.result.archiveSha256);
  } finally { value.remove(); }
});

test('C06 manifest and host-record readers preserve seven complete historical catalogs and the final V17 catalog', () => {
  for (const versions of catalogs) {
    const release = manifest(versions);
    assert.equal(validate(release).ok, true, `Historical or final exact inventory ${versions.join(',')}`);
    assert.deepEqual(readRecord(record(release)), { ok: true, result: record(release) });
  }
});

test('C07 readers reject unapproved intermediate catalogs arbitrary sparse sets reordered rows and malformed V17 names or values', () => {
  const invalid = [range(13), range(15), [...range(12), 14, 15], [...range(10), 13], [...range(11), 16]].map(manifest);
  for (const fault of ['reversed', 'duplicate', 'unknown18', 'wrongName', 'leadingZero', 'boolChecksum', 'overflowChecksum', 'extraField']) {
    const value = manifest();
    if (fault === 'reversed') value.migrations.reverse();
    if (fault === 'duplicate') value.migrations[16] = clone(value.migrations[15]);
    if (fault === 'unknown18') { value.migrations[16].version = '18'; value.migrations[16].script = 'V18__unknown.sql'; }
    if (fault === 'wrongName') value.migrations[16].script = 'V17__arbitrary.sql';
    if (fault === 'leadingZero') value.migrations[16].version = '017';
    if (fault === 'boolChecksum') value.migrations[16].checksum = true;
    if (fault === 'overflowChecksum') value.migrations[16].checksum = 2 ** 31;
    if (fault === 'extraField') value.migrations[16].ignored = true;
    invalid.push(value);
  }
  for (const value of invalid) {
    assert.deepEqual(validate(value), untrusted);
    assert.deepEqual(readRecord(record(value)), { ok: false, reason: 'UNSAFE_HOST_RELEASE' });
  }
});

test('C08 every unchanged complete historical and final contract retains AUTO_READY for exact history image and fingerprints', () => {
  for (const versions of catalogs) for (const action of ['automatic', 'preflight', 'deploy']) {
    const release = manifest(versions); assert.deepEqual(judge(release, ready(release), action), mode('AUTO_READY'));
  }
});

test('C09 final V17 first transition requires explicit approval both disabled rules zero pending work and exact successful prefix', () => {
  const release = manifest();
  for (let length = 7; length <= 17; length++) {
    const facts = { ...ready(release), automaticContract: null, history: history(release).slice(0, length), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    for (const action of ['automatic', 'preflight']) assert.deepEqual(judge(release, facts, action), mode('TRANSITION_REQUIRED'));
    assert.deepEqual(judge(release, facts, 'deploy'), denied);
    assert.deepEqual(judge(release, facts, 'deploy', true), mode('TRANSITION_APPROVED'));
    for (const states of [['ENABLED', 'ENABLED'], ['ENABLED', 'DISABLED'], ['DISABLED', 'ENABLED']])
      assert.deepEqual(judge(release, { ...facts, refreshRuleStates: states }, 'deploy', true), denied);
    assert.deepEqual(judge(release, { ...facts, pendingCommands: 1 }, 'deploy', true), denied);
  }
});

test('C10 approval never inserts omitted lower migrations into an already-applied sparse history', () => {
  const release = manifest();
  for (const versions of catalogs.slice(3, 6)) for (const action of ['automatic', 'preflight', 'deploy']) {
    const facts = { ...ready(manifest(versions)), refreshRuleStates: ['DISABLED', 'DISABLED'] };
    assert.deepEqual(judge(release, facts, action, true), denied);
  }
});

test('C11 final history tampering failure minimum prefix and malformed automatic records always fail closed', () => {
  const release = manifest(); const mutations = [];
  for (const index of [15, 16]) for (const field of ['checksum', 'script', 'success']) {
    const facts = ready(release); facts.history[index][field] = { checksum: 99, script: `V${index + 1}__wrong.sql`, success: false }[field]; mutations.push(facts);
  }
  const short = ready(release); short.history = short.history.slice(0, 6); mutations.push(short);
  const gap = ready(release); gap.history.splice(11, 1); mutations.push(gap);
  const malformed = ready(release); malformed.automaticContract.history.splice(11, 1); mutations.push(malformed);
  const failed = ready(release); failed.automaticContract.history[15].success = false; mutations.push(failed);
  for (const facts of mutations) for (const action of ['automatic', 'preflight', 'deploy'])
    assert.deepEqual(judge(release, { ...facts, refreshRuleStates: ['DISABLED', 'DISABLED'] }, action, true), denied);
});

test('C12 final publication identity and exact schema guard image contracts cannot silently become automatic approval', () => {
  for (const field of ['schemaFingerprint', 'guardFingerprint']) {
    const release = manifest(), facts = ready(release); facts.automaticContract[field] = '0'.repeat(64);
    for (const action of ['automatic', 'preflight']) assert.deepEqual(judge(release, facts, action), mode('TRANSITION_REQUIRED'));
    assert.deepEqual(judge(release, facts, 'deploy'), denied);
  }
  const release = manifest(), facts = ready(release); facts.currentImage = `${f.repository}@sha256:${'6'.repeat(64)}`;
  assert.deepEqual(judge(release, facts, 'automatic'), mode('TRANSITION_REQUIRED'));
  for (const field of ['repository', 'ref', 'workflow', 'event', 'conclusion', 'ciSha']) {
    const invalid = manifest(); invalid.origin[field] = 'untrusted'; assert.deepEqual(validate(invalid), untrusted);
  }
  for (const field of ['sourceSha', 'buildRunId', 'buildRunAttempt', 'imageRepository', 'schemaFingerprint', 'guardFingerprint', 'archiveSha256']) {
    const invalid = manifest(); invalid[field] = 'untrusted'; assert.deepEqual(validate(invalid), untrusted);
  }
});

// New independent V17 contract is included by the existing CI entrypoint exactly once.
require('./issue123-v17-catalog.test.cjs');
