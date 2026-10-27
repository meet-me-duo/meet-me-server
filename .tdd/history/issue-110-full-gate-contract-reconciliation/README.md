# Full gate contract reconciliation

Original test bytes and SHA-256 are preserved before any edit. proposal.json records the diagnosis while the first full gate was still running; correction.json records the actual final failed XML and the precise applied changes. The archived gate ran 888 cases with 4 assertion failures, 0 errors and 4 existing skips. That failed gate is retained and is not presented as a passing run; focused/final rerun is pending.

The new V14/V15 migrations change exact schema counts and maximum version. The integration V8 upgrade now applies V9,V10,V11,V14,V15 (five); no V12/V13 migration was invented. Original legacy snapshot, ownership, rows, table checks and repository rollback assertions remain unchanged. Standalone CorePersistence expects maximum15 instead of10; combined CorePersistence expects15 instead of11.

The combined operations projection test previously supplied a syntactically valid token while real #109 token verification was intentionally unconfigured, which correctly yields AUTH_UNAVAILABLE503. Its synthetic input now uses syntax-invalid Bearer, guaranteeing401 before verification. Expected401, minimal audit, no-store and secret-exclusion assertions remain unchanged. Actual invalid/expired verification semantics remain covered by the independent14-case HTTP composition tests and #109 cryptographic regressions. Existing historical RED hashes are preserved and must not be relabeled as proof for this changed input.

This is independent test/fixture reconciliation, with no production code change or test execution by its author.
