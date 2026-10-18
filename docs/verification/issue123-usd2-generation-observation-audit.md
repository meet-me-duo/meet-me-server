# USD2 standalone generation observation audit

**Evidence/accounting audit PASS; paid diagnostic STOPPED after its maximum2 calls. One complete HTTP200 generation response was received, but the unchanged production parser rejected it with INVALID_RESPONSE. This is not a model-semantic FAIL or accepted pipeline/question PASS.** Reviewed 2026-10-18T06:13:49.019724+00:00. This reviewer made no further API/model/account calls, source/test/runtime changes, parser replay or signature-value disclosure. Independent question semantics and the optional-metadata compatibility research/fix are separate owners/work.

## Freeze, mocked safeguards and actual request count

Actual plan SHA `95db90eee4118232952bed08a743956f5d5356145d084df8638355a62a3f90c6` matches the pre-reviewed archive and batch receipt. Child6ddf3d…/invoker196b732…/harness757bc8… and oraclec69edcc…/mode871c42…/nonpaid-diagnosis09ec625… remain unchanged. Request3111B SHA `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44` matches both consumed records. The invocation's root gate SHA `ccf4d3cc36d58bb7d656e308f93738fa5dcb79f977de7e3c745c78380224f792` remains exit0 and bound to the556-file source manifest; all556 current files matched at this audit, before any parser fix. Current B contract SHA remains `72cfaa26b4a671fd8b87aa654d16d899a2b63c13e44c2baeae747ec9e14da2a1`.

Independent runner regression59/59 and frozen test-script SHA `0e2c532f0c8baf17dcdcea65ea13b1c793c9816dbe3b0b24a886a5b04f3d1624` remain unchanged in `.tdd/history/issue123-usd2-generation-diagnostic/independent-review/`. Those are isolated fake-I/O retry tests, not additional live requests. Their original defect replay/first correction/final GREEN history is retained; no chronological model-evaluation RED is claimed.

Exactly two new consumed records exist, each attempts1, matching the client's latest child records and batch actualGenerateAttempts2. Batch records actualNewDecisionsRequests0/actualNewCountRequests0. The first503 was eligible under the frozen list and had no Retry-After. The measured interval from first parent observation completion to second child start is **10.054662seconds**, exceeding the selected10-second gap. SecondHTTP200 made retryAllowed=false despite the parser rejection; no third dispatch occurred. No internal retry, fallback, redirect or model repair is recorded.

| New standalone generation | HTTP | Observation | HTTP elapsed ms |
|---|---:|---|---:|
| 1 | 503 | code503/statusUNAVAILABLE; provider message reports current high demand; no Retry-After; no generation usage | 548.4 |
| 2 | 200 | complete2766B response, finishReasonSTOP, reported input530/candidate188/thought366/total1084, serviceTierstandard; offline production parser INVALID_RESPONSE | 5195.5 |

Headers elapsed547.4/5194.3ms and first-body observations548.0/5194.8ms are separately preserved. Parent elapsed596.0/5258.9ms includes child/process overhead. These are individual observations, not p50/p95, timeout rates or general service performance. The second request completed within the isolated60-second diagnostic window; no fresh A/count occurred, so it is not evidence of a successful shared15-second production chain.

The new first503's sanitized allowlisted error now supports “that response reported high demand.” It does not establish the root cause of earlier503 or no-status timeout, account/service health, or an underlying server incident. Prior failures are preserved and not reclassified from the new error message.

## Response, parser compatibility and privacy boundary

The complete raw response SHA is `983e23db4fab03c1f5c41e5ca8775e8cf798b122ba5dd9507114c9a7a7cc080a`, matching both responseSha256/storedResponseSha256 and2766 recorded bytes. storedBodySanitized=false: the hash covers the actual persisted body. This audit inspected only envelope/usage/part-field metadata; it did not print/copy the raw body, question or opaque signature value. The original body remains Git-excluded. No actual signature value is placed in this report, a tracked fixture or logs; compatibility tests must use synthetic values.

The returned Part has `text` plus `thoughtSignature`; the unchanged parser's current allowlist requires exactly `{text}`. Thus it rejects at the Part shape boundary before evaluating question content. Receipt productionParserExitCode2/stdoutINVALID_RESPONSE/stderr-empty matches that behavior. This source/shape mismatch is concrete compatibility evidence; official optional-field confirmation and independent TDD remediation are separate pending checks. This audit does not infer the model question is semantically wrong, declare the full response accepted, weaken the frozen oracle, or claim that removing the signature is the approved product fix. Any subsequent offline replay/fix result must be separately labeled and hash-bound, with no extra paid call.

Generation input530 + candidate188 + thought366 = total1084; candidate+thought554 is below requested2048. Current count530 happens to equal this observed prompt count once; that is not a universal billed-input proof or replacement for prior UNKNOWN usage. Actual question semantics remain for the independent predeclared LTS02–08 reviewer. Structural parser rejection and model-semantic judgment must not be combined into one failed-accuracy sample.

## Aggregate known usage, unknown exposure and retained budget

| Scope | Base public-rate estimateUSD | Same2.2-factor estimateUSD |
|---|---:|---:|
| Known Decisions6: input3085/output0 | 0.0003085 | 0.00067870 |
| One known GeminiHTTP200: input530/output(candidate+thought)554 | 0.002475 | 0.00544500 |
| **Known combined subset** | **0.0027835** | **0.00612370** |

Gemini arithmetic is `(530×0.75 + (188+366)×3.75)/1000000`. [Official GeminiStandard pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash) includes thoughts in output; [Decisions pricing](https://developers.openai.com/api/docs/guides/decisions#pricing-and-availability) charges its input only. These are returned-usage estimates at the frozen public rates, not settled invoices or the all-provider final cost. The2.2 factor remains an extra conservative accounting margin.

Aggregate evaluation exposure is **Decisions6 + countTokens2 + generation attempts4 = twelve evaluation HTTP attempts**, excluding earlier model-metadata reads. Generation outcomes are two503 attempts, one no-status timeout, and one completeHTTP200 response currently rejected by the parser. There is one received full generation response, zero current-parser-accepted generation responses, and no claim here of completed participant clarification. Count calls are not included in Decisions input or assumed free. Older503/timeout and new503 generation billing, count fees, taxes/payment fees and final invoices remain UNKNOWN.

All earlier reserves remain held: priorUSD0.70918144 +2×USD0.152064 = **USD1.01330944**; additional fees/tax/uncertainty headroom **USD0.98669056** remains retained under aggregateUSD2. Do not refund failed/unknown attempts, release the difference to the cheap known subset, assume a503 is free, or silently reset the budget. Byte/input-token allowance and CountTokens fee coverage remain engineering margins, not proven production tariff/tokenizer or invoice caps.

The two-attempt plan is exhausted and further paid calls are STOPPED regardless of remaining numeric headroom. Existing raw oracle FAIL/STOP, earlier503 and shared15-second timeout remain intact. Any next paid request needs a new concrete bounded decision within the same accounting; this audit authorizes none. Source compatibility work/offline raw replay can proceed separately without paying again. Production activation, operating threshold, SDK/durable-PG fencing, UI acceptance, final AREA analysis, general accuracy and15-second e2e readiness remain outside this observation.

## Safe artifact fingerprints

| Artifact under `.codex/tdd-evidence/issue123-usd2-generation-diagnostic/` | SHA256 |
|---|---|
| `execution-plan.json` | `95db90eee4118232952bed08a743956f5d5356145d084df8638355a62a3f90c6` |
| `attempt1-consumed.json` | `44e46679492477e6e2d1c566206616296b707e183ad2c75debdaad23f58a9497` |
| `attempt1-client-observation.json` | `fc7d679da31c9b4b4240496ea046d453fe7905fb190947e54f1dd4e4e98a208e` |
| `attempt2-consumed.json` | `cb17349643a79ef7e603ecea2acfef6bc518a25a5663d6710c9e66667f5f7429` |
| `attempt2-client-observation.json` | `b68c1f74087310bc7ececf3e674fd9ce2bdcd380c7afa525d911902229f375ed` |
| `diagnostic-batch-invocation.json` | `a7e51005d94911f4ddb070b07af9c6078772e55977665b29cae4dd713e2466d1` |

Raw response is referenced by SHA above only; its opaque signature is intentionally not copied.
