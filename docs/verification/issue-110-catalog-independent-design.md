# Issue #110 release catalog and Nginx: independent test design

The test author wrote tests and this evidence, without changing product code or executing Node, Gradle, Docker, production commands, AWS or SSM. Actual RED/GREEN, defect injections and runtime experiments are owned by the implementation session and remain to be recorded.

## Approved catalog

The exact new catalogs are V1 through V10 plus V14/V15 for standalone #110, and V1 through V11 plus V14/V15 for actual #109 integration. V12/V13 belong to other issues and must not be synthesized or accepted. Existing immutable V10/V11 manifest and stored-record readers remain compatible. Existing V1 through V11 migration content remains immutable.

`.github/scripts/issue110-release-catalog.test.cjs` has 17 tests covering source members, exact file names, Flyway readLine/UTF-8 CRC32, raw-byte schema fingerprints, guard fingerprints, archive digest, manifest and stored-record readers, successful ordered history prefixes, failed/tampered history, pending commands, both refresh rules paused and explicit transition approval. Automatic/preflight flows must return `TRANSITION_REQUIRED` for a new schema or guard fingerprint. Deployment without explicit approval remains denied. Only an exact complete matching stored contract may return `AUTO_READY`.

Four independently added publisher regressions cover a symlinked migration directory, `src/main` ancestor, `deploy/pipeline` guard ancestor, and repository-root alias. All target leaf files remain ordinary and contain approved bytes. Every real tree/link lives inside the test's original temporary fixture, and cleanup removes only that fixture without following symlink targets. These tests were authored before the corresponding product fix and have not been executed by the test author.

Minimal product change: define the approved sparse catalogs in the already fingerprinted `approved_release.py` and reuse their validation from publisher and host record parsing. Enumerate the complete migration directory, reject unknown entries, symlinks and non-files, and enforce exact new file names/order. Preserve the existing trust/approval checks and legacy V10/V11 reader contract. Observed history must match an exact candidate prefix; stored automatic records must be complete. This needs no additional external service or release-bundle module.

The tests reject retroactively inserting V11 after a standalone host already applied V14/V15. That history is not a prefix of the integrated release. Integration must be prepared before a production transition; these tests perform no transition.

Source changes on a separately reviewed revision derive a different schema fingerprint. That alone cannot authorize automatic reuse of an old contract. Historical source immutability is checked separately with pinned SHA-256 values. Historical pins normalize CRLF to Git's canonical LF, while publisher fingerprint assertions independently hash actual publication bytes. This makes immutable-source checks consistent across Windows and Linux without changing the release fingerprint contract.

The independent JS CRC32 oracle is cross-checked against the unchanged eleven #109 expected CRC values. A separate byte-wise PowerShell oracle produced V14 `1718407698` and V15 `-1216068646`; neither value came from the product publisher. The new tests assert these two values too.

## Source-derived expectation supersession

Exact original worktree test bytes were copied before modification to `.tdd/history/issue-110-catalog/`:

- `approved-release-pipeline.test.cjs.before.cjs`: SHA-256 `442ba36487d60f6beb62a02da6b1bb3a6265bad0804dd0b77590a4b302471f3a`
- `issue109-release-catalog.test.cjs.before.cjs`: SHA-256 `63fda2c71269bab1b409db9ec98be36b6dd92c8476fcf164b69c91de2342b10e`

`original-sha256.json` records both. The local `.gitattributes` marks these historical files `-text` so Git does not normalize the preserved original CRLF bytes.

Only source-derived publisher expectations changed: current publication contains thirteen exact migrations, with the old eleven CRCs unchanged as the prefix. The missing-source test now removes V14, because removing V11 alone is a valid standalone catalog. Duplicate V11, reserved V12 and symlink V11 denials remain. All legacy V10/V11 reader, stored-record, history, approval and denial assertions remain unchanged. No tests were skipped or removed.

Test-harness portability changes select `process.env.PYTHON_EXECUTABLE ?? 'python3'`. Windows `_record` tests supply an empty `types.ModuleType('fcntl')` only while importing the Unix host module; it supplies no lock or host callable and tests invoke only `_record` with synthetic read bytes. Native production locking and host I/O are never exercised or replaced by this offline harness.

Suggested defect injections after GREEN: arbitrary versions; unknown file names; missing symlink rejection; boolean checksum; failed history; bypassed schema fingerprint; bypassed guard fingerprint; bypassed explicit approval; one enabled refresh rule. Matching assertions must fail and product bytes must be restored after each run.

## Nginx privacy contract

`.github/scripts/issue110-nginx-privacy.test.cjs` has six static source tests. Each provider callback must use its exact path and `error_log /dev/null crit`, while retaining all existing rate, upstream, forwarding header and timeout settings. The ordinary HTTPS proxy retains its forwarding policy. Access summaries permit only timestamp, request ID, method, URI path, status and timing variables; raw query/request, header, cookie and body variables are absent. Callback routes keep the safe summary access format. Inline proxy settings avoid adding a hidden include dependency. CI must run both new test files.

The implementation session extended the explicit privacy specification to HTTP request parsing errors before location selection: native error logs cannot selectively redact query values. Therefore the HTTP-wide error destination must be `/dev/null crit`; every HTTP child override must use that same sink. Top-level `error_log /dev/stderr warn` preserves startup/configuration diagnostics outside HTTP request processing. Safe JSON access summaries remain the request ID/status/timing evidence. The independent tests were updated before implementation/RED for this additional scope.

The previous five-test source is preserved at `.tdd/history/issue-110-nginx-privacy/issue110-nginx-privacy.test.cjs.before.cjs`, SHA-256 `5db28792132796d919718a5c00c8febc376c38d42e09921cda314b3122402703`; its local `-text` attribute preserves exact original bytes. Its old assertion requiring HTTP-level stderr diagnostics and no TLS override was superseded by the expanded parse-error privacy requirement. Callback and safe-summary assertions remain in force.

These source checks do not prove Nginx runtime logging behavior. The implementation session must separately reproduce upstream failures with synthetic code/state values and verify actual logs before and after the change. No production configuration change is authorized by these tests.
