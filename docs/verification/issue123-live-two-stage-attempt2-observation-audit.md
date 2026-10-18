# #123 attempt2 actual observation audit

**Evidence audit PASS; diagnostic FAIL_PROVIDER_OR_STRUCTURAL / TIMEOUT / STOP.** Reviewed 2026-10-18T05:48:55.645128+00:00. The sole separately authorized attempt2 took the legitimate low-confidence route and reached the generation callback, but produced no captured successful generation response, usage or question. Actual question generation/semantic success remains unverified. No additional calls, oracle edits, runtime changes or source/test changes were performed by this reviewer.

## Integrity and counts

Plan SHA `b4fe8fd30e5b61b87d259cce1ab6a4099220d445910ba5617c2511d9354a0e28`, oraclec69edcc…, harness757bc8…, callback42d164…, invokerba940d… and the three current production contract hashes match their pre-execution freezes. A2397/count3172/generation3111 byte payload hashes match the plan and corresponding consumed records. The recorded full-gate SHA `ccf4d3cc36d58bb7d656e308f93738fa5dcb79f977de7e3c745c78380224f792` is still exit0, bound to its source-manifest SHA; all556 source files remain unchanged. Prior attempt1 report SHA `c40069061fa634fd5e54ec66ac392e147c2c895c557d0d7951116fed16f996f1` and original raw diagnostic summary SHA `22b53cd2db3f1152a1856a03e1b49b86d9ab95e6d14e179659a054f6b8547645` remain unchanged.

Exactly three new per-stage consumed records exist, each attempts1, matching invocation actualNewHttpAttempts3. A/count response hashes and callback metadata match their successful receipts. Generation has only its initial UNKNOWN_HELD consumed record and an empty metadata file. This is attempted-dispatch/unknown-exposure evidence; it does not prove remote receipt or billed execution of every attempt. No retry/fallback/continuation/extra count or successful generation is recorded.

| Attempt2 stage | HTTP status observed | Result | Observed HTTP elapsed ms |
|---|---:|---|---:|
| Decisions A | 200 | input512/output0; raw CLEAR/confidence0.06, normalized INDETERMINATE/LOW_CONFIDENCE at unchanged synthetic0.8 | 394.6 |
| Gemini countTokens | 200 | totalTokens530, valid1..4096 | 245.3 |
| Gemini GenerateContent | **UNKNOWN** | child did not return a captured successful body/usage; consumed state UNKNOWN_HELD | **UNKNOWN** |

The generation consumed record contains startedAt `2026-10-18T05:47:03.449454+00:00`, request SHA and attempts1 only. It has **no httpStatus, finishedAt, elapsedHttpMs or responseSha256**. Do not invent status0/503, completion time or per-generation latency, and do not replace the initial marker with a retrospective completed response. No generation-response.raw.json exists; generation callback metadata is zero bytes. This is absence of captured evidence, not proof of empty provider output or zero consumption.

## Deadline and result interpretation

The actual pipeline report and JVM stdout both return TIMEOUT/accepted=false. Invoker exit2 and wholeJvmInvocationElapsedMs15538.3 are recorded; that total includes JVM startup/reporting and is not a per-generation or successful pipeline latency. The outer25-second watchdog did not report outerTimeout. The frozen Java callback code enforces the remaining shared15-second deadline and forcibly kills child/descendants on its wait timeout; the unfinished generation marker/empty metadata are consistent with that path. No precise child completion time or server-side cancellation acknowledgment was captured. Killing the local process does not prove remote work/billing was canceled.

Unlike attempt1's concrete HTTP503 mapped by the wrapper to CONFIGURATION, attempt2's failure is a local shared-budget TIMEOUT with **unobserved generation HTTP status**. Preserve these as two distinct failures. A normalized LOW_CONFIDENCE and count530 demonstrate the eligible branch/input admission only; count is not billed generation usage. No B question, sourceExcerpt or A/B proposals exists to grade LTS02–08. The old raw-label mismatch remains FAIL/STOP, the negation case remains unexecuted, and the existing fake-generator regression tests remain separate evidence. No calibration/operational threshold, final AREA/UI/accepted submission or real two-model semantic PASS follows.

## Aggregate accounting and stop

- Observed/unknown-attempt scope across the original diagnostic plus both bounded follow-ups: **Decisions6, countTokens2, generation attempts2; ten evaluation HTTP attempts/exposures**. Earlier model-metadata reads are separate. There are zero captured successful generation responses and no additional evaluation call by this auditor. Do not call ten successful/billed model inferences.
- Known Decisions input sequence518/518/513/512/512/512 sums3085; reported output sum0. Published-rate known-provider subtotal isUSD0.0003085; conservative2.2-factor subtotalUSD0.00067870. These amounts are contained in retained reservations, not added again or refunded against them. Count530 twice is not included in Decisions usage and does not establish Gemini invoice amounts.
- Gemini count/generation costs remain UNKNOWN, including attempt1 HTTP503 and attempt2 unfinished generation. Missing usage, local kill and timeout establish no zero charge or refund. Taxes/payment fees and final invoices remain unverified.
- Cumulative held estimate remains **USD0.70918144**; fees/tax/uncertainty headroom **USD0.29081856** remains retained within the same approvedUSD1. The estimate keeps the original full reservations, including failed/unknown exposure. It is not an official tokenizer/tariff hardcap or production durable-money proof.
- A further identical full fresh-A/count/generation estimateUSD0.31854592 would produce **USD1.02772736**, exceedingUSD1 byUSD0.02772736. No further same-scope dependent call/batch is authorized or supported by this accounting. Stop; preserve every original receipt and unknown hold. A timeout retry must not be silently treated as a continuation of an unconsumed one-shot plan.

The blockers to a successful real clarification demonstration are concrete external503 in attempt1 and no completed generation within the shared deadline in attempt2. This audit makes no inference about unobserved generator semantic quality, p50/p95, generalized accuracy or production readiness. It does not authorize another call or a timeout/prompt/threshold/budget-policy change.

## Artifact fingerprints

| Artifact under `.codex/tdd-evidence/issue123-live-two-stage-attempt2/` | SHA256 |
|---|---|
| `execution-plan.json` | `b4fe8fd30e5b61b87d259cce1ab6a4099220d445910ba5617c2511d9354a0e28` |
| `CLASSIFY-consumed.json` | `dd32893bfbfb3c9c4ab8255072b7099407a8db45ac64e5dd90b6312cbc55eda5` |
| `COUNT-consumed.json` | `e23288127caa4b470fac33048314fd4edcad3bdfc2ec5d5080a0ed275aeafd9e` |
| `GENERATE-consumed.json` | `03bfe8cd00aac71791fc73fb5952c08cadb872ad54d78f8bceb3e8322f221a77` |
| `classify-response.raw.json` | `7f6d7d7267a016e5f4b08426421936725122f674e6158e030abde4b1bd01609c` |
| `count-response.raw.json` | `21100ab895229cd890a807d9a7b16876e8466beaf335f7c015684099dcc8b306` |
| `generation-response.raw.json.callback-metadata.json` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `live-invocation.json` | `37842fcc4ba0e77d7308922958410e6b14c0ceb258df4817dc22097795bde37d` |
| `live-two-stage.json` | `7d08df1e1b0600b2ccd2d8b43ef6dd241806448967f0720976ee00cc9488ee94` |
| `stdout.json` | `e89cd9d0dc86e94d903a3614914e931685b63d34e01c8c3fa16e79b19b9c189d` |
| `stderr.txt` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
