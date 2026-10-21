# Independent review: live Korean Decisions diagnostic

Reviewed 2026-10-18T04:57:19.112341+00:00. Read-only audit; no additional HTTP/model/key access, source/test edits or corpus reread.

**Result: semantic FAIL / STOP is preserved.** The new diagnostic executed three cases: two matched their predeclared oracle; LIVE-KO-03 returned raw CLEAR at confidence0.06 against predeclared NEEDS_REVIEW. LIVE-KO-04 (negation/exception) was not executed. Together with the initial independent absolute-time smoke there were exactly four observed inference requests, not five. A prepared fourth request file is not evidence of execution.

## Oracle, prompt and receipt integrity

- Independent oracle file `/tmp/issue123-live-korean-cases.json` remains byte-identical to its pre-call freeze: SHA256 `47390e74ed16237ce3dd9e9fb0002d304827c22e4592a8965f3151a8fcfd2552`. All four expected labels/categories agree with the predeclared call manifest. No post-result oracle edit was found.
- All four prepared request hashes/byte lengths match the manifest; request model and entire questions/instructions/choices objects are unchanged from the first single smoke. Canonical question-object SHA256 `9ab0f62d32067e8ac8bbdeee0a5f60c41a85a1f7b8b97f949a29cc787b116bce`. Only the input/context sample changes. Reasons/oracle prose are not inserted in request input.
- Each of the three executed additional results exactly matches its individual consumed receipt and raw JSON. HTTP200, one attempt and offline production parser exit0/usageComplete=true are recorded for each. The initial raw response and parser response SHA also match. Parser acceptance validates structure/usage; it does not establish semantic correctness.
- Predeclared stopOn includes semantic mismatch. The first mismatch is the last executed case, and LIVE-KO-04 has neither consumed receipt nor raw response. This supports compliance with STOP without retry/fallback. The batch marker remains STARTED_UNKNOWN_HELD while the separate summary records terminal STOP; it must not be presented as a settled/released durable-budget ledger.

## Observed subset

| Case | Predeclared | Raw choice | Confidence | Input tokens | One observed latency ms |
|---|---|---|---:|---:|---:|
| LIVE-KO-01 | CLEAR | CLEAR | 0.88 | 518 | 201.9 |
| LIVE-KO-02 | CLEAR | CLEAR | 0.79 | 513 | 196.2 |
| LIVE-KO-03 | NEEDS_REVIEW | CLEAR | 0.06 | 512 | 203.6 |
| LIVE-KO-04 | CLEAR | NOT EXECUTED | — | — | — |

The initial absolute-time smoke recorded CLEAR/confidence0.83, input518/output0, 1011.1ms. New subset inputs518/513/512 and latencies201.9/196.2/203.6ms. No distributional accuracy or p50/p95 claim is supported by these selected, non-random, stopped cases.

Low confidence0.06 is consistent with the agreed need to send uncertain classifier results to second-stage review. It does not erase the raw-label mismatch, prove calibration, select an operational threshold, or demonstrate an actual second-stage generation/correction success. Offline parser threshold0 was deliberately validation-only. No second-stage or final analysis HTTP was executed in this diagnostic.

## Cost/accounting scope

- Observed scope: four Decisions inference HTTP requests total, aggregate input2061/output0; retries0, fallback0, count API0, Gemini inference0, serial concurrency1 per the manifest/receipts. No extra evaluation request was made by this reviewer.
- Base input usage estimate: `2061 × $0.10 / 1,000,000 = $0.0002061`; conservative2.2-factor estimate `$0.00045342`. Output-token charge is absent for this Decisions endpoint. These are usage-based estimates, not settled invoices.
- Pre-call maximum was five total inference requests at `$0.01441792` engineering allowance each: planned API reserve `$0.07208960`, additional-cost headroom `$0.92791040`, within the same aggregate USD1. One planned request was not dispatched. Whole USD1 remains held; tax/payment fees are unverified and were not assumed zero. Do not silently refund/reuse unknown headroom or reset the aggregate budget.
- Files here are isolated smoke receipts/manifests, not proof of production PostgreSQL durable reservation/managed fencing. No production activation, merge, full evaluation, money policy or spend-hardcap proof follows.

## Fingerprints audited

| Artifact | SHA256 |
|---|---|
| `.codex/tdd-evidence/issue123-minimal-smoke/korean-diagnostic-summary.json` | `22b53cd2db3f1152a1856a03e1b49b86d9ab95e6d14e179659a054f6b8547645` |
| `.codex/tdd-evidence/issue123-minimal-smoke/korean-four-case-manifest.json` | `4e6f54b07ed5af6f58ec5160dc465552f9c949567fb9d7a7fcd8e8d269677b37` |
| `.codex/tdd-evidence/issue123-minimal-smoke/korean-four-case-batch-consumed.json` | `f312f48150c1436f485c2d7723bdea947f5837ddedfcb21ef9ee6298fbf98f8e` |
| `.codex/tdd-evidence/issue123-minimal-smoke/openai-smoke-single-request-consumed.json` | `2809869e0949cdf852f7daf1d42781d2c7be455d04a3aed795cf1298c8e08a7f` |
| `.codex/tdd-evidence/issue123-minimal-smoke/openai-smoke-production-parser.json` | `ca648f2a7ca166e7dfcf5845ab22c8a410480a99d7f457be1796414c5cf4dabf` |
| `LIVE-KO-01-response.raw.json` | `c472c6da304e8d8f8deab6ef0f271e7e169d976cc44c8b79746796b185e86b1e` |
| `LIVE-KO-02-response.raw.json` | `8110f1d0d2b56429429b005224b1e4f98837ef2e320af925966d4a0355c4cb13` |
| `LIVE-KO-03-response.raw.json` | `7f6d7d7267a016e5f4b08426421936725122f674e6158e030abde4b1bd01609c` |
| `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt` | `c45fa35de6c7079b13d81f5a1c4d5193b4939a08dd2f3d3b511fc8b390b25334` |
| `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierConfiguration.kt` | `d9259f6c974b0494907cef262db4c6366e0a93a86293225b767beca8fdd9a265` |
| `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierOptions.kt` | `70c8acff13ead4f9e8df0150c8f61060abf1c6d0b4146ba783a328f463d1cd1e` |
| `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierRuntimeProperties.kt` | `3514921d100d99a2e01097ee4f818139764c0cd8563299869fd067482c2f45fa` |
| `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/OpenAiAmbiguityClassifier.kt` | `1f33e12211eb9b33b96b70ada710e5a0b3da1b0e0ca4fda7c72c704cc60853fe` |

Current contract SHA equals the source SHA in the offline parser receipt. Other source hashes above are current read-only fingerprints; the request/receipt audit alone does not prove which transport binary executed live. No independent prompt/model change or retuning is claimed or requested. No prior corpus or holdout was reopened.
