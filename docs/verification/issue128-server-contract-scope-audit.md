# #128 server contract scope audit

Baseline: `18cdb5ddec4f9e09db50feb54337aae9516a0108`. Fixed service SHA-256: `fa872f579a51742553935eec9e4e321fac42710178e4c772ccbb0ef152e45f5c`. New independent regression test SHA-256: `8c22e9fda348eeae8483f8e2465789d9636a6f06af295b2184d3b041df9ffbe3`.

Exact product delta is a single snapshot projection condition: only an open room with effective status NEEDS_CHOICE exposes a current question. CANCELLED retains its persisted question for history/quota but returns question:null. Reload and mutation use the same snapshot path. NEEDS_CHOICE rendering remains unchanged; closed rooms already hide the question.

No command, DTO, endpoint, migration, ownership resolver, room deadline, revision/check-version CAS, accepted submission, provider, retry, worker, physical call admission or question-count SQL changes are present in this delta. Existing 500-code-point input, three-question cap, six physical execution cap and one explicit retry policies remain unchanged. All 229 Git-tracked existing test files are byte-identical to HEAD.

New independent seven-case regression: cancellation response/reload projection; fresh CREATE/CLEAR; request replay without cancelling newer head; publication history and question cap after repeated cancellations; other-participant privacy; stale candidate/check versions; closed-room fencing. Initial valid RED is CQ01 assertion failure with six baseline controls PASS, zero errors/skips. Implementer reported exact frozen seven GREEN before independent FI.

This is a narrow source/scope audit by the regression author, not a replacement for the separate final reviewer, mandatory full guard, new browser/native integration run, remote exact-HEAD CI or merge/deployment approval. No external provider/OAuth/production calls were performed. Independent FI results are recorded separately after exact source restoration.

```diff
--- before
+++ fixed
@@ -385,7 +385,7 @@
                     it.id,
                     requireNotNull(status),
                     it.version,
-                    if (open) it.question else null,
+                    if (open && status == PrecheckStatus.NEEDS_CHOICE) it.question else null,
                     open &&
                         it.status in RETRYABLE &&
                         it.retryCount == 0 &&
```

Final FI observation (2026-10-18T08:06:32Z): both intentional defects detected by actual assertion failures (projection removal CQ01; persisted question deletion CQ04 plus four history assertions). Each exact restoration passed all seven tests, errors/skips zero. All 479 src files before/after/current have identical SHA-256. Frozen source fa872f57 and test 8c22e9fd remain exact. Canonical schema-valid evidence: `.tdd/verification/issue128-cancel-question-independent-faults.json`; exact patches/frozen files/result: `.tdd/history/issue128-independent-fi/`; raw logs/XML: `.codex/tdd-evidence/issue128-independent-fi/`. Global lock released after final exact restoration. Paid/external calls zero.
