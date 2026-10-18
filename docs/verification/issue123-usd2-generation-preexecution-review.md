# USD2 standalone generation diagnostic pre-execution review

**ACCEPTED after the review fixes below; no unresolved P1/P2.** Reviewed 2026-10-18T06:09:24.670335+00:00. Root may invoke this exact frozen, at-most-two-generation diagnostic under the latest aggregateUSD2 authorization while its checked hashes/gates remain unchanged. This reviewer made no real HTTP/model/account/key calls, actual subprocess/kill/sleep operations, production/source/test/runtime changes or model-result observations. Only isolated test fixtures and review evidence were written. The new diagnostic directory had no consumed attempt or batch-invocation marker at final review.

## Final freeze independently checked

- Plan SHA256 `95db90eee4118232952bed08a743956f5d5356145d084df8638355a62a3f90c6` matches local/archive copies.
- Child SHA `6ddf3d992d496a28d803e313ce50188f2a02587d9421e837d7f9d5e2f27974de` and invoker SHA `196b73223f9bd4597e689252e7257a68cd7b997bb39e188cc54076a058985e78` match temporary and immutable archive copies. Harness757bc8… is unchanged and used only for offline production B parsing in this runner.
- Fixed generation request remains3111 bytes/SHA `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44`, candidate1/maxOutputTokens2048/LOW. No new A/count, tools, media, cache, prompt/profile/oracle edit or additional case.
- Original semantic oraclec69edcc…, standalone-mode871c42… and nonpaid-diagnosis09ec625… hashes match the plan. Mode is B-only60-second observation with prior A/count evidence; original LTS02–08 question requirements remain unchanged and fresh-chain LTS01 is explicitly not exercised.
- Full-gate SHA `ccf4d3cc36d58bb7d656e308f93738fa5dcb79f977de7e3c745c78380224f792` remains exit0, its source-manifest hash matches, and all556 source files still match current bytes. These are independent real-file checks; the isolated behavioral tests below use synthetic gate fixtures and are not substituted for this check.

## P2 findings and independent resolution

The first runner let a body/network timeout overwrite known HTTP200/permanent statuses into retry eligibility, in both child exception and parent hard-kill paths. It also accepted negative/fraction/exponent Retry-After values as floats. Parent corrected both: known headers constrain eligibility to the explicit transient status list; invalid Retry-After stops. Independent full-body isolated tests then found one remaining empty-header issue: `Retry-After: ''` was treated as absent. Parent changed the absence check to raw is None; empty/blank is invalid. The final same test set passes59/59.

[RFC9110§10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3) defines Retry-After as HTTP-date or non-negative decimal integer seconds. The final parser accepts bounded ASCII digits or a parseable timezone-bearing date; invalid/empty/oversized or required delay>10 causes STOP. Final child and parent both preserve known HTTP200/permanent status precedence even when the body stalls or the child is killed. Absent status can remain eligible only as the declared network/timeout uncertainty, with its reserve retained. No model call was made while these review findings were open.

Immutable evidence under `.tdd/history/issue123-usd2-generation-diagnostic/independent-review/`:

| Evidence | Result |
|---|---|
| `initial-ast-gap.json` | original exact timeout-handler decision reproduction; HTTP/key/network0 |
| `original-replay.json` | original saved runner,37 controls PASS/22 observed requirement mismatches |
| `first-fix.json` | first corrected runner58PASS/1 empty-Retry-After mismatch |
| `final.json` | final runner59PASS/0FAIL |
| `isolated-runner-tests.py` | frozen independent expectation/source SHA `0e2c532f0c8baf17dcdcea65ea13b1c793c9816dbe3b0b24a886a5b04f3d1624` |

Method: full child and invoker bodies are AST-relocated only for ROOT/BASE/pinned fixture plan into temporary synthetic directories; env is cleared and contains only fake values; urllib/Popen/javac/kill/sleep are mocked. Test scope covers known200/403/503/no-status, body/connect timeouts, parent hard kill, Retry-After absent/10/>10/date/invalid/empty, one10-second gap and at-most-two process attempts. No real network, keys, model, processes or ten-second wait occurs. Original37/22 replay was performed after the initial correction and is **not chronological TDD RED**. Test expectations/script were unchanged between replay, first-fix and final runs. The fake I/O matrix verifies retry decisions, not actual provider networking or all production gate implementation behavior.

## Concrete execution boundaries

The final frozen mode explicitly selects transient408/429/500/502/503/504 or transport/timeout uncertainty as the optional second-attempt predicate, with at least10seconds separation. This is the selected broader predicate versus the earlier503-only proposal; it remains within the same maximum2 standalone-generation amount. The earlier diagnosis's possible60-second Retry-After wait is not selected: final plan/mode require STOP above10 or on invalid values. This is isolated evaluation control, not a new product retry policy.

Each child has one HTTP POST/no redirect/internal retry/fallback. Parent owns the60-second absolute hard process-group deadline, which also bounds stalled body reads. O_EXCL batch/per-attempt markers prevent accidental reentry. Child persists headers/status/body progress atomically as they arrive; bounded allowlisted error/header metadata redacts actual environment key values before persistence. Unobserved response/usage/status stays UNKNOWN after kill; keys are header-only, never argv/query/logs. First observed HTTP200 stops regardless of body/parser/semantic success; permanent/local/structural failures stop. The optional second attempt exists only for the declared transient predicate and stops after its outcome; no third call or model repair is permitted.

Production B contract parsing is offline with provider keys blanked. Actual semantic PASS still needs an independent complete-response LTS02–08 review: structural accepted JSON, valid focused AM/PM question and proposals/context preservation. CLEAR/NEEDS_EDIT/unsafe question/invalid output is not a reason to call again. Recorded A confidence0.06/fixture0.8 and the prior count530 can support later **offline** bridge replay only. This runner does not establish a fresh live A→count→B chain, product15-second completion, production SDK/durable-PG money/fences, participant UI/acceptance or final AREA analysis. Actual generation beyond15seconds may aid diagnosis but cannot be called a successful15-second product flow.

## Budget and retained limitations

Independent exact arithmetic: prior heldUSD0.70918144 + at most2×USD0.152064 = maximum cumulativeUSD1.01330944; remaining fees/tax/uncertainty headroomUSD0.98669056 under the newly approved aggregateUSD2. Existing unknown holds/known spend are retained; no refund, double counting or zero-fee assumption. Additional A/count0. Current prior evaluation counts remain10 until new actual receipts exist; maximum12 is not an observed count yet.

The81920-token input allowance and2.2 safety margin remain engineering estimates, not official Gemini byte-token/CountTokens tariff or final-invoice proof. Fixed Standard rate/output2048 are unchanged. No billing-setting/credit purchase, secret or operating policy activation is included. Raw original FAIL/STOP, HTTP503 and shared15-second TIMEOUT remain historical failures even if this standalone attempt later returns a good question. No calibrated confidence, generalized accuracy, p50/p95 or production readiness is approved by this review.
