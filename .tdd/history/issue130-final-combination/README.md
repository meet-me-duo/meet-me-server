# Historical managed-close regression reproduction

This archive records an isolated test-only combination. It is **not compiled or executed by this PR132/base398 tree**, and does not modify PR133. The `.kt.txt` suffix intentionally prevents accidental treatment as a live test. Do not claim this archive proves deployment readiness.

The independent oracle preceded the test. The exact test was run on server base `6046baf66e49e9382248cd3fb8adb0a8879a7e40` plus only `AmbiguityClassificationContract.kt` from `9d9c934fd8f999f0bd4a1723bd8987b9efd86b93` (SHA `bbb36cd731377fccbf0a01e9ee5202e51f62a1790f8dac175ac404f740c54283`). Existing491 source/test files were unchanged relative to this declared combination; only this new test was added. Prior69 focused tests and final3 new cases were separate runs.

To reproduce, create a disposable checkout/worktree at that exact base, overlay that one source file from the specified revision, copy `Issue130ManagedClosePostgresTest.kt.txt` to `src/test/kotlin/com/meetme/server/coordination/adapter/input/web/Issue130ManagedClosePostgresTest.kt`, and verify SHA `6e12a36a139970eeefb55517c3176650053191272182d57774a2d90d04a2d635`. Do not copy the whole current production tree. The base must include existing `CorrectionRoundPostgresFixture`, `DurableAnalysisInvocationPostgresTest.ControlledTime`/`DurableClock`, `Issue131CanonicalTlsProxy`, and account principal fixture; no new framework or operating credentials are required.

Prerequisites: JDK17, Docker/Testcontainers PostgreSQL18, project Gradle wrapper/dependencies, and a shared execution lock if concurrent agents run. Blank real provider keys before running:

```sh
OPENAI_API_KEY='' GEMINI_API_KEY='' JAVA_HOME=/path/to/jdk17 \
  flock /tmp/meetme-gradle.lock ./gradlew --no-daemon --console=plain --max-workers=2 \
  test --tests '*Issue130ManagedClosePostgresTest'
```

The test creates an explicit synthetic policy, synthetic account fixture and local generated TLS proxy. It uses actual public CREATE→transactional READY→own precheck→worker/TwoStage/strictparser/physicalgate/PG ledger. The threecases cover accepted member counts0/1/2, early409/confirmedclose, previous accepted version UUID cohort, pending preservation and no late spending. Synthetic held amounts0/3/15 and threshold0.73 are test fixtures, not approved operating prices/caps/thresholds. No direct READY seed, final-analysis worker/model execution, browser, actual OAuth or paid provider call is claimed.

Final3/3 PASS is an INITIAL_PASS regression, not chronological productRED. The first compile attempt failed on nullable SQL UUID sets (newtest0), with stale69 XML explicitly excluded. After fixture type correction, initial3 and format-only rerun3 passed. The sanitized receipt keeps the exact final receipt/XML hashes; raw logs/XML stay local. Model semantic failures, web create500 OpenAPI drift, UI and deployment limitations remain unchanged. Neither `.tdd/history` inventory treatment nor a passing reproduction expands operational approval.
