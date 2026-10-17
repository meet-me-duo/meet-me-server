# Issue 110 migration runner expectation correction

Scope: actual-account-109-integration. Original test bytes are preserved in DatabaseMigrationRunnerTest.before.kt. expectation-change.json records original/current SHA-256 and the exact migration inventory with checksums.

The migration count changes from 11 to 13, and highest version from 11 to 15, because V14 and V15 were added. V12 and V13 remain reserved for other issues. Only these two expectation literals changed; every other assertion and byte is preserved. This is a schema-driven independent test correction, not historical RED evidence. The test author did not run the test or modify product code.
