# Final numeric-resource guard fault injection

The original repository was never mutated. A standard temporary project used copied final main/resources, classifier6 and unchanged Gemini3 test files, the original build/wrapper and shared dependency cache. All Gradle calls held `/tmp/meetme-gradle.lock`. Final Contract SHA is c45fa35de6c7079b13d81f5a1c4d5193b4939a08dd2f3d3b511fc8b390b25334.

The copied160-test baseline passed. Removing only the precision/scale guard caused16 genuine assertion failures in the frozen34 numeric tests, with18 PASS controls and0 compile/setup errors. After restoring the guard byte-for-byte, all160 tests passed again. The original classifier4 source files and9 test/fixture files retain their fingerprints. Exact patch, final source/test hashes, copied full-main inventory and outcomes are in manifest.json. Raw log/XML/source snapshots remain local under `.codex/tdd-evidence/issue107-decision-numeric-fi/`. Canonical seven-field record: `../../verification/issue107-decision-numeric-resource-fi.json`.

This new guard-specific fault injection supplements the earlier six historical classifier mutations. Their original source hashes and logs were not rewritten. The intermediate whitespace-only Contract change is recorded separately in the earlier RED history. The160 current-tree GREEN also has its own unchanged-before/after hash receipt in `../issue107-decision-numeric-resource/green.json`.

No actual OOM-scale input was executed: all numeric regressions use inexpensive exponent/precision1001 and boundary1000 values. No production/default parser constraint was relaxed; an explicitly injected mapper exists only in this new test's precision-boundary cases. External/paid calls0. Root owns the final unrestricted quality gate and exact-HEAD CI.
