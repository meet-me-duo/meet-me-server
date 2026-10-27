# Fresh HEAD4101038 single two-stage diagnostic: independent cost review

**PASS — the selected maximum reservation fits the already approved aggregateUSD2; no new cost/scope P1/P2 identified.** Reviewed 2026-10-18T07:04:40.034448+00:00. Local HEAD is exactly `4101038fd421351affa2a1fcdd5741083cb1a6b6`; worktree was clean before this new review document. This is a pre-call cost/scope assessment, not an execution-runner seal, official invoice bound or new authorization request. Parent owns the existing explicit single-fresh-case execution approval and new frozen runner. Reviewer made no paid/model/account/metadata calls, key access, source/test changes or original-record edits.

## Independently recomputed maximum

| Item | Formula/basis | Reserved estimateUSD |
|---|---|---:|
| All previous known/UNKNOWN exposure retained | existing cumulative hold, no refunds | 1.01330944 |
| Fresh Decisions A, maximum1 | 65536×0.10/1000000×2.2 | 0.01441792 |
| Gemini count, maximum1 | same full-generation proxy below, including2.2 margin | 0.15206400 |
| Gemini generation, maximum1 | ((81920×0.75 + 2048×3.75)/1000000)×2.2 | 0.15206400 |
| New maximum additional reserve | A+count+generation | **0.31854592** |
| Maximum aggregate reserved estimate | 1.01330944+0.31854592 | **1.33185536** |
| Retained fees/tax/uncertainty headroom | 2.00−1.33185536 | **0.66814464** |

Arithmetic used Decimal values, independently of prior hand-written totals. The old attempt2 plan SHA `b4fe8fd30e5b61b87d259cce1ab6a4099220d445910ba5617c2511d9354a0e28` confirms the same additional reserve basisUSD0.31854592. Its old prior/cumulative fields are historical; they must not replace the new priorUSD1.01330944. Known cheap usage is already included within holds, neither added again nor used to refund unknown operations. No503/timeout/count fee or final invoice/tax/payment charge is assumed zero.

## Official facts versus conservative assumptions

- [Official Decisions pricing](https://developers.openai.com/api/docs/guides/decisions#pricing-and-availability), rechecked2026-10-18: gpt-6-luna inputUSD0.10/M with no output/cache-read/cache-write charges; regional/long-context premiums apply. The previous65536-token single-question allowance and2.2 factor are retained. This is not a server input/spend cap or exact local tokenizer count.
- [Official Gemini3.8Flash Standard pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash), rechecked2026-10-18: inputUSD0.75/M and outputUSD3.75/M including thoughts through December31,2026. No free-tier assumption, tools/search/maps/cache/storage or other tier is included. [GenerateContent-specific thinking documentation](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens) describes a thought-inclusive infrastructure cutoff:2048 covers thoughts and visible output, but may truncate output and does not guarantee successful completion within15seconds. LOW is not thinking-off.
- The81920-token Gemini input allowance remains a deliberately large engineering proxy for explicit small text-only wire size plus framing/tokenization margin. Neither the [Gemini token guide](https://ai.google.dev/gemini-api/docs/tokens) nor the prior observed count530 proves a universal byte→billed-token bound. The2.2 Gemini factor is additional chosen safety margin, not an asserted official long-context/regional multiplier.
- Count is reserved as an entire full-generation operation including output allowance and2.2 factor. The [billing FAQ](https://ai.google.dev/gemini-api/docs/billing#is-gettokens-billed) names GetTokens as unbilled; this review does not silently equate that wording to proven zero-price REST CountTokens. The proxy/headroom is a practical conservative estimate, not a published CountTokens maximum fee. Unknown count/generation/invoice exposure stays held.

## Same-input and scope checks

The current `PrecheckProviderContract.requestBytes` function is byte-identical to HEAD's parent; function SHA `23a81a08901a0262afd95a888a8b76e1aeae8f282d3413c835eb4c81b4f28a12`. The response-metadata compatibility correction does not itself enlarge the generation prompt, schema or monetary profile. Current original oracle remains SHA `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`; existing synthetic0.8 fixture remains SHA `e8bd75698e37cc4b9cbeb9c625656045ad3a1e6590dcdecf8a5568dc4f9bb197`, fixed before new output. It is not an operating confidence policy.

The parent-approved scope is one fresh fixed LIVE-KO03 run on HEAD4101038: actual Decisions A→only eligible normal branch→count1..4096→generation→current parser/actual TwoStage, maximum3 new physical HTTP attempts, serial1, shared15-second absolute deadline, automatic retry/redirect/fallback/continuation0. Keep production flagsOFF and raw500/output2048/LOW/candidate1/ownership/closing rules unchanged. Fresh confident CLEAR stops with count/generation0 and remains that case's semantic failure; refusal/API/invalid response stops, never forces B. No retry to rescue a semantic or compatibility failure is included.

Before dispatch root must pin the actual new complete payload sizes/SHAs and current runner/source/HEAD/guard; prior small-wire basis must be verified rather than inferred from participant text alone. No new payload has been executed or certified by this cost review. Preserve all old raw FAIL/STOP,503,TIMEOUT, parser-rejection and offline-correction records. One new success would not relabel those old failures or establish population accuracy/calibrated threshold/p50/p95. Existing evaluation counts12 are history; the maximum15 after this run is not an observed count until actual receipts exist.

Do not borrow a previous60-second standalone result as a fresh15-second success. This requested run must supply its own current-head live routing/deadline/parser/usage evidence; SDK transport/durable-PG/production activation claims depend on what its concrete harness actually exercises. Existing exact-HEAD CI success was reported by the parent and is not independently re-queried by this cost-only review. No production settings, secrets, billing changes, real users or deployment are authorized by this document. If the new frozen scope changes model/tier/payload/caps/call count or requires another retry, recalculate before that changed dispatch without releasing old unknown holds.
