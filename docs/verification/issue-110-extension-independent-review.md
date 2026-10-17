# Issue 110 extension independent review

Review role: `/root/independent_review`, separated from the production implementer.
This reviewer did not run Gradle, formatters, browser tests, providers, AWS, SSM,
or operational queries. The root owns execution and final evidence collection.

## Reviewed boundaries

- Explicit invocation/attempt context follows durable admission; legacy requests
  remain unlinked. Admission expiry records false/no calls without parser entry.
- Both provider adapters use per-session OkHttp listeners and preserve their
  no-retry, no-redirect and deadline settings. Listener code reads monotonic time
  and numeric status without request/response/exception content.
- A settled HTTP error is a completed local lifecycle. Parser success, provider
  receipt, publication and billing are separate facts.
- V15 connects metadata to the exact invocation/attempt/provider. The JDBC writer
  serializes repeats on the existing attempt, validates bounded facts, compares
  identical snapshots and writes parent/children atomically. Attempt deletion
  cascades to metadata.
- Async storage uses a bounded single daemon worker with rejection on saturation
  or shutdown, defensive collection copying, and no exception-content logging.
  A separate bounded datasource avoids consuming business-pool acquisition time.
- Operator queries gate identity/allowlist before business reads, select safe
  columns, retain historical run versions, isolate invocation attempts, preserve
  unknown usage and expose known-only subtotals.
- Controller/service/filter composition attempts a durable audit on every normal
  service path. Audit failure produces 503 before releasing protected output;
  pre-controller errors use the filter's audit path. No-store covers errors.
- Audit cleanup is scheduled, configurable, bounded, and strictly older than the
  cutoff. Its records have no business/account foreign key dependency.
- DTO/OpenAPI, web validation and the view distinguish unknown, known zero,
  non-dispatch, complete local totals and partial local subtotals. HTTP request
  count remains unknown.

## Findings addressed during review

1. Transport coverage originally used returned attempt rows as its denominator.
   It now conservatively accounts for recorded admission counters and requires a
   nonempty, equal admission/attempt/observed set before claiming exact totals.
2. Synchronous telemetry persistence inside parser completion could consume the
   parser budget and cause a provider retry. The async wrapper now only enqueues
   on the parser thread. Storage failure/drop remains unknown; the existing
   monotonic publication fence remains in force.

No additional P1/P2 issue was found in the source reviewed after these changes.
This is source review, not a claim that the final quality gate has passed.

## Independent checks prepared

New tests cover adapter failure lifecycle, processor context, actual
controller/filter audit composition, scheduling, PostgreSQL query transport
projection/OpenAPI, concurrent repeats, datasource budgets, async queue behavior,
web validation, UI and browser drilldown at desktop/mobile sizes. The original
two observation test bytes remain unchanged. The previous operator query v1
bytes and RED are preserved in `.tdd/history/issue-110-operator-query-v1/`.

`verify_operator_mutations.py` prepares eight faults: allowlist bypass, broader
invocation scope, current-version-only history, null-to-zero, audit bypass,
retention cutoff inclusion, private failure echo, and false HTTP-success status.
It requires explicit root execution after GREEN, preserves original source
bytes and all repository Kotlin test fingerprints, restores in `finally`, and
rejects compilation/setup failures as evidence of a killed fault. At the last
read-only preflight all eight anchors matched exactly once.

Final GREEN, full gate and mutation results must be attached by the root. Tests
added after an implementation already existed (notably pool configuration
assertions) must not be described as preceding that implementation's RED.

## Web final evidence review

The reviewer read the sealed web evidence and raw reports without rerunning or
changing product code, tests, or build output. The final evidence is
`meet-me-web/docs/verification/issue-110-web-final.json`; raw local reports are
under workspace `verification/web-v2*` and the web verification log directory.

- Raw Vitest reports confirm 353 passed, zero failed. Typecheck, lint with zero
  warnings, build, OpenAPI generation and generated-contract checks exited 0.
- Raw Playwright reports confirm 395 passed, one existing conditional skip,
  zero unexpected and zero flaky cases, with retries disabled. All four new
  transport cases (two scenarios on desktop/mobile) passed on their first run.
  The mobile cases explicitly use 320px width; overflow checks passed.
- Browser source and dist before/after maps are identical and still match the
  current files. All 14 sealed final file hashes match current files. All copied
  mutation test fingerprints match the current originals.
- The compiled transport RED reports six missing-behavior failures and four
  passes; the later focused GREEN reports 45 passes and zero failures.
- Seven isolated web faults produced 13 intended assertion failures, while the
  unchanged control passed 32/32. The raw mutation report SHA matches the sealed
  evidence. The failures concern the intended validation, unknown/zero, stale
  state, private-field, subtotal and transport-status behaviors rather than
  compilation or environment setup. Real source hashes remained unchanged.
- The actual local server OpenAPI export SHA matches the sealed evidence. All
  three operator paths and ten referenced DTO schemas in the web snapshot match
  that export. Comparing the pre-change snapshot from Git HEAD confirms that
  unrelated paths, schemas, other components and top-level metadata are preserved.
- Browser routes intercept API requests and use named synthetic private-value
  markers. They do not contain real provider responses or account credentials.

No unresolved issue was found in these web evidence checks. These results cover
the local web implementation with API response doubles: actual backend/browser
integration and actual account-109 integration remain false, as the manifest
states. This section does not claim the final server quality gate or server
mutation checks have completed.

## Server mutation evidence review

The reviewer separately checked `verification/mutant-operator-v2-summary.json`
and all eight archived runner records, logs, original bytes and JUnit XML files.
All eight deliberate faults were detected, with 16 assertion failures in total.
For each fault, original-byte SHA, reconstructed mutated-byte SHA, archived XML
SHA, recorded failure names/types and raw runner outcomes agree. All failures
are `AssertionFailedError`, with zero errors/skips and no compilation failure.
The runner records offline execution without forwarded cloud/provider credentials
or live opt-ins, and confirms exact source restoration and unchanged tests.

This validates the eight mutation results, not the final server GREEN/full gate.
The root's subsequent replay of missing behavior against formatted test
fingerprints is a separate evidence step; it must not be described as the
original pre-implementation RED. Temporary replay skeletons are not counted as
unresolved product defects by this review.

## Account 109 handoff

The unapplied patch and isolated contract probe preserve stable account UUIDs,
null only for absent identity, invalid authentication as 401, infrastructure
failure as 503, and no lookup on authentication failure. The probe uses explicit
contract doubles, not actual 109 source. `AuthFailure.code` remains an accessor
assumption until actual 109 code is available. No common account types were
added to production or repository test sources by this review.

Probe classes, including its test-only component, must not contaminate the final
normal build. Use an isolated build directory, or remove the injected sources
and clean/recompile before the final gate and verify no probe classes remain.
Actual 109 integration, operator registration and operational enablement remain
outside this local completion claim.
