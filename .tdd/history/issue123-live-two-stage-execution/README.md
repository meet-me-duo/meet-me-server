# LIVE-KO-03 two-stage smoke harness

Prepared and javac-compiled only. The author executed no harness commands, callback processes, HTTP requests, or real models. No repository production/test files changed.

Source: `/tmp/Issue123TwoStageSmokeHarness.java`
Class directory: `/tmp`
Classpath file: `/tmp/issue123-decision-smoke-classpath.txt`
Compilation receipt: `/tmp/issue123-two-stage-harness-compile.json`

Parent execution pattern (do not print the classpath or credentials): load the classpath file as one argument, prepend `/tmp:`, and call `/tmp/meetme-jdk17/bin/java -cp <classpath> Issue123TwoStageSmokeHarness <command> ...`. Use subprocess argument arrays rather than interpolated shell text.

## Commands

- `prepare <original-A-request.json> <output-directory>`: exact-byte copies A to `a-request.json`; calls production `PrecheckProviderContract.requestBytes(request,true)` and `countRequestBytes` to create `generation-request.json`, `count-request.json`, and `manifest.json`. Each request is at most 16 KiB. Existing request/manifest files must have identical bytes; new files use CREATE_NEW. Existing consumed records, response files, callback metadata, or live report reject preparation/reentry.
- `routeA <A-response.json> [original-A-request.json]`: production strict A parser at fixture threshold 0.8; uses actual TwoStage plus a synthetic CLEAR B probe to determine `shouldGenerate`, HTTP 0. Optional request defaults to the existing minimal-smoke LIVE-KO-03 A request relative to the server directory. Its evaluation-only usage gate requires complete usage, known input in 0..65536, and output exactly 0. Failure reports `evaluationUsageGatePassed=false`, `accepted=false`, and `shouldGenerate=false`.
- `count <count-response.json>`: production strict count parser; requires integer 1..4096. No HTTP.
- `parseB <original-A-request.json> <generation-response.json>`: production B parser/usage validation plus existing input cap 4096. Returns nullable original INPUT/CANDIDATE/THOUGHT usage separately; missing usage is never replaced with zero.
- `verify <original-A-request.json> <A-response.json> <count-response.json> <B-response.json> <fresh-output-directory>`: recorded-response offline replay using actual TwoStage. Requires a generating A branch and one A/B port call. This is supplemental offline evidence. Use a fresh directory, not the live response directory.
- `run-live <original-A-request.json> <output-directory> <callback.py>`: actual production TwoStage coordinates A and, only for eligible results, count then generation. Reads no API keys and implements no HTTP itself. Parent must finish its guard/review/financial approval and freeze its execution plan before invoking this command.

## Frozen live file names and callback contract

Requests: `a-request.json`, `count-request.json`, `generation-request.json`.
Responses: **`classify-response.raw.json`, `count-response.raw.json`, `generation-response.raw.json`**.
Callback argv: `python3 <callback.py> <CLASSIFY|COUNT|GENERATE> <exact-request-path> <exact-response-path> <remainingMillis>`.
Callback stdout must contain metadata JSON only (at most 16 KiB), saved to `<response-name>.callback-metadata.json`; stderr is discarded. The callback owns secrets, exact URL/SHA checks, financial proof, O_EXCL consumed records and response writes, retry/redirect prohibition, and its HTTP/body deadline. Existing stage metadata cannot be overwritten. Each subprocess receives only the remaining original shared 15-second TwoStage budget; Java kills subprocess descendants and parent on deadline. Count parsing occurs before the sole GENERATE callback. Failure terminates without retry and writes a bounded local live report preserving observed usage and stage counts where available.

`prepare` can precede `run-live` if only identical request/manifest files exist. It does not modify the parent's `execution-plan.json`. `a-request.json` is an exact byte copy of the original source file, not reserialized JSON.

## Fixed synthetic data and limits

Case LIVE-KO-03 raw text: `10월 24일 토요일 8시부터 9시까지 가능해요.`
Reference: `2026-10-18T04:00:00Z`; zone `Asia/Seoul`; locale `ko-KR`; search `2026-10-24` through `2026-10-25`.
Check ID `00000000-0000-0000-0000-000000012303`; room `00000000-0000-0000-0000-000000012300`; owner `00000000-0000-0000-0000-000000012304`; version/generation 1.
B fixed model `gemini-3.8-flash`, LOW thinking, candidateCount 1, maxOutputTokens 2048. Production B parser retains its output/input/schema checks. Count wraps the exact production generation request bytes, not a separately reconstructed prompt.

Threshold 0.8 is the existing synthetic fixture threshold, not an operating confidence-policy decision. A complete-input/output-zero gate is a smoke-evaluation stop condition for the parent's quote assumption, not a product policy change. The input preflight count is not proof of billed input or a monetary ceiling.

This harness exercises production request builders, response contracts, and actual TwoStage routing through custom bounded callback ports. It does not exercise production SDK transport, Spring activation, or durable budget/fence authority. Its synthetic dispatch context and deny-only authority proxies make no production dispatch authorization claim. Parent callback financial gates remain independently necessary.
