# Independent runtime boundary fault injection

Six isolated mutations were applied sequentially after the79-test baseline GREEN. Every mutation produced an assertion failure with no compilation/setup errors; every production file was restored byte-for-byte immediately afterward. The final unchanged79 tests passed again. Frozen61 and legacy18 test bytes never changed. Exact one-purpose diffs are retained in this directory; raw logs/XML and source snapshots are local under `.codex/tdd-evidence/issue-107-runtime-input-gate-fi/`. `manifest.json` links hashes and outcomes; canonical schema record is `../../verification/issue-107-runtime-input-gate-fi.json`.

These are deliberate post-implementation fault injections, not new chronological RED claims. Gradle test-source selection was limited to the provider package via the preserved init file; main production compilation remained complete. Actual external/paid calls0: canonical URL tests intercept in memory, transport tests use literal loopback, external legacy guard tests deny ProxySelector before routing. Billing equivalence and live API support remain unverified.

The exact source-set init file is retained as `provider-only.init.gradle`; the recorded commands used its `/tmp/meetme-provider-only.init.gradle` copy. Copy it to that location to reproduce the focused selection.
