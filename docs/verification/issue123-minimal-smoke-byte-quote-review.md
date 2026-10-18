# Decisions single-smoke byte quote: independent sanity review

Reviewed 2026-10-18 UTC. Scope: read-only public documentation, fixed local payload metadata, plan, script and completed receipt; no keys, account HTTP, paid request, production or source/test changes by this reviewer.

**Verdict:** the proposed quote is a reasonable conservative engineering estimate for this fixed text-only, one-question, one-physical-request smoke under the existing aggregate USD1 authorization. It is not an official billed-token upper-bound proof or a server-enforced spending cap. This verdict does not enlarge the request count or authorize production activation.

## Independently checked local evidence

- Payload: `.codex/tdd-evidence/issue123-minimal-smoke/openai-smoke-request.json`, exactly 2431 bytes; SHA256 `b36db3e64454215c66ff89b89ae2f854b1cf25ecfeed06238dd8898b5b70d4a7`. Metadata only inspected/output: `gpt-6-luna`, string input, exactly one choice question/three choices, top-level keys input/model/questions. No holdout corpus reopened.
- Plan SHA256 `eecd882f9f2bc95ed0a6e83c5eac85bbd30ba1a4154241e2c8694d6542ccaf40`: endpoint `/v1/decisions`, physical request 1, retry/redirect/tools 0; no Gemini/count/fallback.
- Script `/tmp/issue123-openai-smoke.py`, SHA256 `9753c3647cccd255e1db3fda579b95b8e3d30d25685f5c356543f7aaff8a2c69`: O_EXCL consumed marker before HTTP, initial UNKNOWN_HELD with whole USD1 held, redirect disabled, single urllib open, no retry/fallback loop. Receipt remains invoice-unverified after success/failure. This is a local one-shot guard, not an account-wide billing control.

## Official facts and engineering inference

OpenAI's [tiktoken README](https://github.com/openai/tiktoken#what-is-bpe-anyway) describes reversible byte-pair encoding whose text token sequence is shorter than its input bytes. Using the entire JSON wire size rather than only the participant's short text is consequently a defensible conservative ordinary-text basis. The exact gpt-6-luna encoding was not verified locally; an unrelated fallback encoding would not establish its exact count. Exact local model token count is **N/A**.

The official [token counting guide](https://developers.openai.com/api/docs/guides/token-counting) warns that request roles/boundaries can contribute tokens absent from locally tokenized fields, and model-specific behavior can matter. Its exact counting endpoint is described for Responses, so this review does not infer Decisions compatibility or require an extra counting HTTP call. Reserving 65,536 tokens is 26.95845 times all 2431 wire bytes and leaves 63,105 tokens beyond the ordinary byte basis. That protocol allowance is an engineering margin; no official document quantifies that exact overhead bound. There is no evidence that this tiny request is charged the model's entire context window.

The [Decisions guide](https://developers.openai.com/api/docs/guides/decisions#pricing-and-availability) gives gpt-6-luna input $0.10/M, with no output/cache-read/cache-write charges, and says regional/long-context multipliers apply. The [model pricing page](https://developers.openai.com/api/docs/models/gpt-6-luna) gives long-context input 2x beyond 272K and regional processing +10%; the request selects no Fast tier. Applying long 2x even to the much smaller accounting allowance is an intentional extra margin, not a claim the threshold was reached.

Recomputed exactly: `65,536 × $0.10 / 1,000,000 × 2 × 1.1 = $0.01441792`. Remaining approved aggregate headroom: `$0.98558208`. Taxes/payment fees were not estimated as zero. This large headroom makes the single smoke a reasonable small-budget engineering decision, but does not prove every account-specific invoice charge. No credit purchase or billing setting change is included.

## Subsequent observation, not a replacement for the prior estimate

While this review was in progress, the parent executed the separately authorized smoke. The consumed receipt directly records HTTP200, attempts1, retry0, fallback0 and elapsed1011.1ms; this is one observed latency, not p50/p95. Its observed usage is input518/output0, yielding base usage `$0.0000518`, or `$0.00011396` with the same conservative 2.2 factor. Account invoice/tax/fees remain unverified and the whole-budget hold/headroom must not be silently released or reused on the assumption they are zero. The parent reports offline production-parser acceptance; this reviewer did not rerun the parser. One successful sample does not establish accuracy, thresholds or production readiness.

A timeout/unknown observation must retain its reservation and stop without automatic retry. Further diagnostic requests require a new frozen count/payload manifest sharing the same aggregate USD1 accounting; this one-shot quote must not be multiplied into an unbounded evaluation. No additional model call was made by this reviewer.
