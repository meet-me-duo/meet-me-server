# Independent cost sanity review: one standalone real two-stage diagnostic

2026-10-18 UTC. Public official documentation and local code read only. No model/account HTTP, keys, settings, source/tests or production operations by this reviewer. Existing raw semantic FAIL/STOP remains unchanged.

**Verdict:** the parent's selected single diagnostic plan is a reasonable conservative engineering estimate within the existing combined USD1 authorization, subject to freezing/checking the actual complete payloads before dispatch. It is not an official Gemini byte-token bound, CountTokens tariff proof, settled invoice or production cost hardcap. No new policy/flag/price registry activation follows. This review itself executes nothing.

## Official evidence and its limits

- Current local model is `gemini-3.8-flash` (`src/main/resources/application.yml:40`). The [official pricing table](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash) lists Standard inputUSD0.75/M and outputUSD3.75/M including thinking through December31,2026. These current rates do not assume free-tier eligibility. Priority and future prices differ; this plan is Standard, no tools/grounding/cache purchases.
- The [GenerateContent-specific thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens) explicitly describes infrastructure cutoff including thoughts, with MAX_TOKENS/truncated or empty response possible. Thus candidate1/maxOutputTokens2048/LOW can bound combined output under this request contract; LOW is not thinking-off or a success guarantee. This evidence is for GenerateContent itself, not borrowed only from Interactions.
- The [official Gemini tokens guide](https://ai.google.dev/gemini-api/docs/tokens) gives an approximate four-character/token description, not a universal UTF8-byte upper-bound theorem. OpenAI tiktoken's byte-pair property must not be presented as a Gemini-specific guarantee. No exact local Gemini tokenizer count was established.
- The [CountTokens REST reference](https://ai.google.dev/api/tokens) documents `:countTokens`, model tokenizer output and a `generateContentRequest` accepting prompt plus steering information. Its simple example itself shows count10 versus generation prompt11, so count alone is not treated as exact billed-input proof. Freeze/count the complete generation request including system instructions/schema, not only participant text. The wrapper must identify the same model/request, without unplanned content references or tools.
- The [billing FAQ](https://ai.google.dev/gemini-api/docs/billing#is-gettokens-billed) says GetTokens is unbilled. That naming does not explicitly resolve the REST CountTokens tariff here. Accordingly this plan does **not** price the actual count operation at zero. Reserving a full generation quote for it is a deliberately pessimistic accounting proxy, not an asserted official CountTokens maximum price.

## Selected engineering allowance and independently recomputed amounts

Assumptions to verify on the frozen actual files: each entire count/generation wire payload is less than16KiB, text-only, bounded instructions/schema, no media/files/URL resolution/search/maps/tools/cache/multi-turn histories. Use16384 tokens as a conservative byte-based ordinary-text proxy plus65536 tokens of extra framing/tokenization margin, for81920 input tokens per operation. For this small explicit text payload the margin is practically conservative, but its exact numeric bound is an engineering judgment; reviewed official docs do not prove it. Do not substitute this proxy into production as proven billed caps. Actual payload sizes/hashes were still being prepared when this report was written.

| Reservation item | Arithmetic | USD |
|---|---|---:|
| One Gemini full-generation base quote | (81920×0.75 + 2048×3.75)/1000000 | 0.06912 |
| Each Gemini operation with engineering margin | 0.06912×2.2 | 0.152064 |
| Count1 + generation1, both charged as full generation | 0.152064×2 | 0.304128 |
| New Decisions A1, previous frozen allowance/rate method | 65536×0.10/1000000×2.2 | 0.01441792 |
| Additional maximum token/operation estimate | 0.304128+0.01441792 | 0.31854592 |
| Prior reservation retained in full | unchanged maximum-five diagnostic hold; do not reduce to cheap actual usage | 0.07208960 |
| Cumulative reserved estimate | 0.31854592+0.07208960 | **0.39063552** |
| Additional fees/tax/uncertainty headroom retained | 1−0.39063552 | **0.60936448** |

Gemini's2.2 multiplier here is an additional chosen safety factor, not a claimed official Gemini long-context/regional multiplier. The CountTokens proxy even includes an output allowance although counting does not generate a model answer. Neither reserve nor observed usage settles invoice/taxes/payment fees. The remainingUSD0.60936448 is retained for uncertainty, not assumed zero or available for automatic retries. No credit purchase, auto-reload or billing change is included. A required top-up/account-setting change is outside this frozen one-shot plan.

## Execution/review boundary for the parent

- Freeze a new one-shot manifest for maximum3 additional physical requests: Decisions1, Gemini CountTokens1, GenerateContent1; serial concurrency1, redirects/retry/fallback0. Existing inference4 and previous reservation remain in the same aggregate USD1 ledger; do not silently resume the stopped old batch or erase its failure.
- Verify complete wire byte lengths/hashes and candidate1/2048-total-output/LOW/Standard with no hidden extra calls before any dispatch. If actual payload exceeds the stated scope or requires a different model/tier/settings, this quote needs revision before the changed request.
- Keep the existing count<=4096 preflight rule and shared deadline/no-retry behavior. If count fails, is missing/invalid or exceeds the gate, generation is not sent; still retain unknown costs. The4096 gate may lower actual usage but was not used to understate this reservation. An HTTP success may produce MAX_TOKENS/invalid output and fail the diagnostic; do not retry automatically.
- A new classifier response may differ from the old low-confidence recording. Record its actual raw label/confidence and route according to the predeclared isolated test rule; do not retrofit the oracle or threshold to force the second stage. Synthetic-generator regressions remain separate from this proposed actual generation call.
- Record each physical attempt and returned usage separately; preserve missing/late/unknown cost and reserve after timeout. Stop on contract/semantic mismatch or usage outside the allowance, with no follow-up inference to repair the result. Capture observations, not model-accuracy/p50/p95 claims.

There is no official input-byte/CountTokens-price proof sufficient to label the amount an absolute worst-case billing guarantee. There is also no official reason to bill a tiny fixed text request as the full model context. The combination of bounded explicit payload, huge tokenization/framing allowance, full-generation reserve for count,2.2 added Gemini margin and over60% aggregate headroom supports this narrowly scoped practical estimate while keeping the unresolved invoice risks visible. Operational activation, product10won policy and durable production budget correctness remain separate.
