# V17 historical upgrade oracle correction

The first full guard executed1684 tests:1679 passed,1 failed,4 skipped,0 errors. Its single failure expected0 rows in ai_room_budgets after upgrading3 V8 rooms. The frozen V17 contract requires one HISTORICAL_UNKNOWN marker per historical room; this was an oracle contract error, not missing production behavior or genuine product RED.

The corrected existing test requires exactly3 HISTORICAL_UNKNOWN rows, each with null epoch/cap/unit/policy/coverage and held0/settled0; reservations, observations and global profiles remain0. Every business snapshot, auth, frozen cohort, legacy attempt, confirmation and OPEN/CONSUMED revision-round assertion outside this one block remains byte-identical. No production SQL or Kotlin changed; no tests were added.

The original test bytes/SHA and exact diff are tracked here. Full guard raw log,212 original XML files and source/hash snapshots were copied before another Gradle run under `.codex/tdd-evidence/issue123-legacy-upgrade-oracle-correction/`. The corrected2-test scope and full ktlintCheck passed on the first run in26s with unchanged source inventory. This is REGRESSION_INITIAL_PASS; no reconstructed or chronological missing-behavior RED is claimed. Root owns the mandatory full-guard rerun.

Earlier V17 catalog supersession records preserve their own historical fingerprints. This correction adds a later explicit supersession for IntegratedLegacyUpgradePostgresTest only and does not rewrite those earlier results. The pre-execution actual-evaluation oracle at /tmp/issue123-live-two-stage-oracle.json remains unchanged (SHA c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64); this task performed no actual API calls.
