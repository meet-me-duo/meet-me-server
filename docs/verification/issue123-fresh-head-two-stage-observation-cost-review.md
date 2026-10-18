# HEAD4101038 fresh two-stage observation and cost audit

**PASS for observed execution/usage/hash/accounting evidence.** One fresh fixed-case run on `4101038fd421351affa2a1fcdd5741083cb1a6b6` completed A→count→generation→current production parser/actual TwoStage return within its original15-second pipeline deadline, with exactly3 HTTP attempts and no automatic retry. Independent question-semantic LTS grading is a separate owner/result; this audit does not replace it or declare population accuracy, calibrated confidence or production readiness.

Reviewed 2026-10-18T07:24:58.266155+00:00. No API/model/account calls, key-value access, Gradle, source/test/helper edits or original-record changes by this reviewer. The opaque provider signature was neither printed nor copied into tracked evidence.

## Freeze and exact records

- Executed plan SHA256 `3a9fd47f2e5da20ed69f895abcd0c045aceeaefc1e12d98d24121fd6e8028330` matches the invocation and preserved final-frozen archive. Local HEAD equals the plan's exact4101038 revision.
- Harness SHA `667fc67dccdd312abf569a5ce3d51453fa267626d57fad1496929626eab7e764` and callback SHA `3a48a878696ec16f46e8ebeb81c218a597d07b645b96389c2602cdf294c93605` match current temporary and `.tdd/history/issue123-fresh-head-two-stage/final-frozen/` copies. Source hashes for B contract7d1131…, TwoStaged5d97b… and A contractc45fa3… match the plan/current files. Oraclec69edcc…, fresh-modeb40a1d…, and pre-call cost review51b117… match their pinned files.
- The recorded guard SHA `f004c436455124ce3bdd31716be16a6b3105b6ba5787e886fa51a96b49330afd` is exit0. Its pinned source manifest SHA `9db1ce591e8d60e77277688bc1b19bf5611e0ad8d81fa106c636b85ef40220fe` contains557 files, all still byte-identical at audit. Invocation sourceUnchangedAfterInvocation=true agrees with that independent current-file check.
- Full request sizes/SHAs match the frozen plan and stage receipts: A2397B/fbc526…, count3172B/1153b46…, generation3111B/3fafbb…. They remain the prior fixed case/prompt/schema/profile. Each stage has attempts1/automaticRetries0/HTTP200 and matching raw/stored response SHA/size; storedBodySanitized=false. Each consumed record equals its callback metadata. Timestamps are ordered A finish→count start→count finish→generation start; no parallel or repeated stage is recorded.

| Actual stage | HTTP | Usage/result metadata | HTTP elapsed ms | Remaining budget at callback ms |
|---|---:|---|---:|---:|
| Decisions A | 200 | input512/output0, complete usage; raw CLEAR/confidence0.06→INDETERMINATE/LOW_CONFIDENCE at unchanged synthetic0.8 | 653.398 | 14998 |
| CountTokens | 200 | totalTokens530, valid1..4096 before generation | 228.058 | 14274 |
| GenerateContent | 200 | input530/candidate127/thought533/total1190, Standard; current parser/TwoStage returns NEEDS_CHOICE, output660 | 5934.856 | 13991 |

Fresh raw generation response SHA is `1cf9d06f2bd21dc9f3c553f5491af65870a6c1928bd498d1d25be3bbf0b25417`,3527B. Its candidates finishReasonSTOP and returned usage match the live report. Candidate127+thought533=660, and530+660=1190. Prior count530 equaling prompt530 in this observation is not a general exact-billing theorem.

## Deadline and scope

The same-JVM monotonic arithmetic independently yields `(33663754717698−33656725469324)/1000000 = 7029.248374ms`. Finish33663754717698 is earlier than cutoff33671725456084. Report accepted=true/pipelineDeadlineSatisfied=true and invocation exit0 agree. Whole JVM invocation7641.481ms includes startup/reporting and is a different clocked scope. Neither number is p50/p95 or generalized latency. HTTP timings exclude Java/Python startup/parsing overhead; they must not be substituted for the pipeline interval.

This is a new live fresh-A/count/B run at current HEAD, rather than combining old A/count with the earlier standalone60-second answer. Actual TwoStage and current contracts coordinate bounded Python HTTP callbacks. The report explicitly declines production SDK transport and production activation claims; durable financial authority/fences, real participant UI/answer/acceptance and whole-room AREA remain outside this harness. Fixture threshold0.8 was fixed before the call, not tuned or selected as operating policy. Original raw-label FAIL/STOP and all prior503/timeout/original-parser-rejection evidence remain intact.

## New known usage cost versus retained reservation

[Decisions published pricing](https://developers.openai.com/api/docs/guides/decisions#pricing-and-availability) is inputUSD0.10/M without output charges. [Gemini3.8Flash Standard pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash) uses inputUSD0.75/M and outputUSD3.75/M including thoughts through December31,2026. Decimal recomputation:

| Known subset | Base public-rate estimateUSD | Same2.2-factor estimateUSD |
|---|---:|---:|
| New Decisions:512 input/0 output | 0.0000512 | 0.00011264 |
| New Gemini:530 input/660 candidate+thought | 0.0028725 | 0.00631950 |
| **This run's known increment** | **0.0029237** | **0.00643214** |
| Prior known subset retained | 0.0027835 | 0.00612370 |
| **Combined known subset after this run** | **0.0057072** | **0.01255584** |

Gemini formula is `(530×0.75 + (127+533)×3.75)/1000000`. These amounts exclude unknown count fees/earlier failed and timed-out usage, invoice adjustments/taxes/payment fees. The2.2 factor is the previously chosen conservative accounting factor, not observed provider tariff. Do not call the known subtotal a full settled invoice.

Reservations are separate: all oldUSD1.01330944 remains held, plus new pre-reserved maximumUSD0.31854592 = **USD1.33185536** cumulative; retained fees/tax/uncertainty headroom **USD0.66814464** under aggregateUSD2. No old UNKNOWN hold is refunded, no failed generation/count is priced at zero, and no unused difference between quote and cheap observed tokens is automatically released or reused. Gemini input allowance/count full-generation proxy remain engineering estimates, not official byte-token/CountTokens-tariff or server spending-hardcap proof.

Aggregate evaluation attempts advance **12→15**: Decisions6→7, count2→3, generation4→5. Generation history now includes two completedHTTP200 responses, two503 attempts and one no-status timeout; the older response's original parser rejection/offline correction must remain separately labeled. Earlier model-metadata reads are outside these evaluation attempt counts. Known Decisions cumulative input3597/output0 is distinct from count results and known Gemini usage. No additional inference/repair/retry follows from this audit; the approved single fresh run is consumed.

## Privacy and evidence limits

The opaque thoughtSignature value was accessed only in memory for an exact-byte presence check, never emitted or saved. An exact scan of1472 tracked plus nonignored new files found0 occurrences of this fresh response's signature. Git-excluded raw response remains local; only its SHA/size and nonopaque field metadata are in this report. Consumed JSON contains no Authorization/x-goog-api-key/credential-value fields. Actual key values were not accessed, so this audit makes no blanket exact-value scan claim for secrets; helper header/redaction behavior and credential-free record structure are the verified scope. No raw response or signature-bearing fixture was added to tracking.

Reported execution evidence supports successful current-parser/actual-TwoStage return for this one fresh case under15seconds. Semantic proposal quality still follows the independently frozen oracle review; this cost audit neither quotes the actual question nor changes its answers. It does not erase old failures, establish future success rate, select product money/confidence policy, prove production durable-money operation, or authorize another paid call. Linear/current canonical state is parent-owned and was not modified here.

## Safe artifact fingerprints

| Artifact under `.codex/tdd-evidence/issue123-fresh-head-two-stage/` | SHA256 |
|---|---|
| `execution-plan.json` | `3a9fd47f2e5da20ed69f895abcd0c045aceeaefc1e12d98d24121fd6e8028330` |
| `manifest.json` | `4fa9de7820ea935e56e9ad287686e4b94726aff973c81dd51f6ccc59d342f800` |
| `CLASSIFY-consumed.json` | `dbafca3b7ada363c0eeaee28eedaecf9e0580455c2919e39a37b1a22e296e4ed` |
| `COUNT-consumed.json` | `52dbb985a2bf192b88e0d7b5b9afd04cc5d9e175ec2564166e18db04be4ef66d` |
| `GENERATE-consumed.json` | `83456fa74033c9102698afe8c3a97fddd7e5ef36e02d424ba22d39f6546056b6` |
| `live-invocation.json` | `569f01d9562c13d1f4c0b55230ac35b31de377a56c111aa8c3799d379ebc7b86` |
| `live-two-stage.json` | `94baf3fefa5e7e604054607000b5f15699d5dd8ae1c034e65affd29a3b2b6180` |

## Parent canonical record/publication follow-up

Independently compared `.tdd/verification/issue123-fresh-head-two-stage-observed.json` and `docs/ISSUE_123_FRESH_HEAD_TWO_STAGE_VALIDATION.md` with the original observed artifacts. Canonical invocation/report equal the local originals, raw/report SHAs and evaluatedHEAD/tree match, and counts/timings/Decimal costs/retained holds agree. Its pending-independent-audits state is appropriate until separate semantic and record owners finish; this reviewer does not mutate that state. The validation document correctly distinguishes actual current-parser/TwoStage under15seconds from SDK/durable-PG/UI/generalized quality claims and preserves raw-A mismatch/old failures. No record contradiction or new P1/P2 was found.

The `observed/` archive contains exactly8 byte-identical safe observation/report/metadata clones and no raw provider response. A fresh exact scan of1474 tracked/nonignored files found0 occurrences of either the actual fresh opaque signature **or responseId** value; values were kept in memory only and never emitted. All3 ignored raw response files are mode0600. The canonical record/doc/archive contain only safe question/usage/status/hash evidence. Actual API key values were not read for an exact secret-value scan; credential-header absence/allowlisted records is the independent verified boundary.

**Final evidence/cost/publication-record audit SEALED PASS; no unresolved P1/P2 in this scope.** Canonical record SHA `65686a4db288753cfdb24e0b9729114a634d63edd5464860fce30b282ba41e3e`; validation document SHA `5fd0ce29acd60974131d870ea6fa184e4b3bdedd82ffd69abd1a0c25fa98727f`. Parent owns final semantic audit references, record-only commit/push and exact published HEAD CI. Its later unchanged document guard does not become a new fresh1710-test execution by this review; this report makes no such new test-count claim. No additional paid calls are authorized or performed.
