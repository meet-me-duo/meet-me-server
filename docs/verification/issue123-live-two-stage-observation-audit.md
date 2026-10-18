# #123 actual two-stage observation audit

**Audit PASS; diagnostic result FAIL_PROVIDER_OR_STRUCTURAL / STOP, not successful question generation.** Reviewed 2026-10-18T05:40:01.027775+00:00. The concrete blocker is the single actual Gemini GenerateContent **HTTP503**. No retry, repair call, oracle/prompt/threshold change or additional model call was performed by this reviewer. No B question/proposals were observed, so generation semantic quality cannot be evaluated.

## Exact execution and freeze evidence

The fixed plan remains SHA256 `368bafb75f2c36f0980501ea5b7b8a9669f5b8eb5e6705c49e4b6659b9aeb746`; oracle remains `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`. Current Java757bc8…, callbacke029335… and final invokeraf2ba832… match their frozen archive hashes; the three production contract source hashes match the plan. The actual invocation records the same plan and full-gate SHA `ccf4d3cc36d58bb7d656e308f93738fa5dcb79f977de7e3c745c78380224f792`. That full-gate receipt directly verifies exit0/fresh JUnit, its source-manifest file SHA matches the gate, and all556 recorded source files still match current bytes. Thus the pre-execution guard condition was satisfied after the earlier failed attempts, rather than inferred from the old exit2 receipt.

All three complete request byte lengths/hashes remain the frozen A2397/count3172/generation3111 values. Each stage has exactly one O_EXCL consumed record and matching callback metadata. A and count raw-body hashes/sizes match those receipts. Three stage records are total new evaluation HTTP attempts; there is one invocation marker, invocation exit2, and no repeat stage file. Failed generation is preserved as `STOP_HTTP_FAILURE_UNKNOWN_HELD`.

| Stage | HTTP | Observed result | HTTP elapsed ms |
|---|---:|---|---:|
| Fresh Decisions A | 200 | raw CLEAR/confidence0.06, CLEAR probability0.37; normalized INDETERMINATE/LOW_CONFIDENCE at existing synthetic0.8; known input512/output0, usageComplete=true | 855.0 |
| Gemini countTokens | 200 | totalTokens530; valid1..4096, generation admitted | 225.9 |
| Gemini GenerateContent | 503 | no captured generation response/usage/question; STOP_UNKNOWN_HELD | 347.8 |

Whole JVM invocation elapsed2177.7ms includes startup/callback/processing overhead. HTTP timings are per-call observations, not p50/p95 or a successful two-model completion latency. Count530 is the tokenizer result, not billed generation prompt usage or an additional Decisions input amount. The generation request was attempted with candidate1/2048/LOW but no returned generation contract or semantic answer was available.

The fresh A raw response happens to be byte-identical to the historical LIVE-KO-03 response, SHA `7f6d7d7267a016e5f4b08426421936725122f674e6158e030abde4b1bd01609c`; it has a separate new timestamped HTTP200 receipt under the pre-reviewed fresh-call callback. This does not replace the original record or establish model determinism. Actual TwoStage's observed low-confidence branch legitimately reached count and the sole generation attempt. Production SDK transport, Spring activation, durable PG money authority/fences and participant UI/acceptance remain outside this harness.

## Error and semantic classification

Java reports `failureCode=CONFIGURATION` because its generic callback wrapper maps nonzero callback exit/missing successful response to that typed local failure. The specific underlying evidence is GENERATE-consumed.json HTTP503. Do not report configuration/credential invalidity as the proven root cause, confuse this with parser rejection of a returned question, or treat wrapper CONFIGURATION as the provider's own error code.

The callback HTTPError path closes the response without persisting its body. Therefore no generation-response.raw.json or B usage exists; this establishes **no captured body/usage evidence**, not proof the provider returned an empty body or consumed zero tokens. No B response SHA is invented. `returnedBUsage=null` and the held unknown exposure are correct.

Original four-call diagnostic summary SHA remains `22b53cd2db3f1152a1856a03e1b49b86d9ab95e6d14e179659a054f6b8547645`; independent original oracle remains `47390e74ed16237ce3dd9e9fb0002d304827c22e4592a8965f3151a8fcfd2552`. Its raw-label mismatch is still FAIL/STOP and LIVE-KO-04 is still unexecuted. Fresh A normalization/routing can be observed; LTS02–08 generation/question criteria are unassessable after503, not semantic PASS. The two earlier recorded-response regression tests used fake generators and do not substitute for this failed actual generation.

## Combined accounting

- Aggregate observed evaluation scope: Decisions5 actual requests, countTokens1, GenerateContent1 actual failed attempt: seven evaluation HTTP attempts, excluding the separately recorded earlier model-metadata reads. No retry/fallback/continuation/count repeat is recorded. This is operation count, not a claim seven paid successful inferences.
- Known Decisions input2573/output0 = old2061 + fresh512. Published input-rate estimate `2573×USD0.10/1000000 = USD0.0002573`; conservative2.2-factor estimateUSD0.00056606. These are the **known Decisions subtotal**, not all-provider settled cost.
- Gemini count/generation charges remain UNKNOWN. CountTokens tariff naming uncertainty was not resolved by this response; count530 is not proof of a zero fee. A503 and absent captured usage do not establish free/refunded generation. No fee/tax amount or credit refund is assumed.
- PriorUSD0.07208960 reservation remains intact. Additional AUSD0.01441792 and count/generationUSD0.30412800 produce cumulative held estimateUSD0.39063552. Additional fees/tax/uncertainty headroomUSD0.60936448 remains retained in the same approvedUSD1. Do not reduce reservation to cheap known usage, reset the budget or automatically reuse unknown exposure.

The outcome is a concrete external503 blocker to this one predeclared real-generation demonstration. It does not prove generator semantic failure, question quality, threshold calibration, billing cap, production readiness or general accuracy. Any future retry is a separately chosen bounded action with the same aggregate accounting; this audit authorizes no additional request. No source/test/runtime/Secret/operating change was made by this reviewer.

## Observed artifact fingerprints

| Artifact under `.codex/tdd-evidence/issue123-live-two-stage/` | SHA256 |
|---|---|
| `CLASSIFY-consumed.json` | `d68c7be105ef46c311491e8895f46768f1ff36d63774db7765cb21283a036f95` |
| `COUNT-consumed.json` | `22795559112fd26bb371e52a8e1be044d39cad24200c502896207435eca2161f` |
| `GENERATE-consumed.json` | `317d321856cf259fca6d6ad3671564f4ae3955178929da5c4b7522f5a4471dcc` |
| `classify-response.raw.json` | `7f6d7d7267a016e5f4b08426421936725122f674e6158e030abde4b1bd01609c` |
| `count-response.raw.json` | `21100ab895229cd890a807d9a7b16876e8466beaf335f7c015684099dcc8b306` |
| `generation-response.raw.json.callback-metadata.json` | `e5e417dc4d1ba6e72e6532ddd7eb8c4b7735a5b5dadc4374ff83e91c0ef2ba55` |
| `live-invocation.json` | `b3c1d05c75837cb073cff1a5fae7280055a2fefc1b8f4d62d01611512ee1668a` |
| `live-two-stage.json` | `c40069061fa634fd5e54ec66ac392e147c2c895c557d0d7951116fed16f996f1` |
| `stdout.json` | `08eb919cf01d687cfd4a1d343fa0c3758fb68e5e4f502b58e7b7d2dff1200c98` |
| `stderr.txt` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
