# Independent follow-up audit

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

This follow-up uses synthetic fixtures, mock SDK factories and offline runner tests only. It makes no paid provider calls, reads no sealed inputs or gold, and leaves the original c945 evaluation outputs, manifests and runner unchanged. The original null-status CONFIGURATION causes remain unknown. Reproducing an observation-clobber path does not diagnose those calls.

## Technical classification and authority

The independent Gemini contracts cover five primary failure kinds, successful SDK response followed by observation rejection, initial source denial, and an actual `AiHttpDispatchGate` monotonic deadline expiry. The initial seven-case RED had four assertion failures and three controls passing. The real-expiry case was added later. Reversing only the adapter to its original c945 bytes caused five assertion failures in the final eight cases, with three controls passing. The fixed adapter was restored byte for byte and all eight passed.

The production change is confined to preserving an already classified primary exception when later observation fails. Successful responses with rejected observation still fail as CONFIGURATION. Initial source/configuration denial remains nonretryable and prevents SDK creation. Existing bounded fallback, total deadline and current source/authority checks remain separate regression controls; preserving TIMEOUT grants no authority for a subsequent call.

## Stop boundaries

Independent safety contracts distinguish typed common technical failure and account/network HTTP outcomes from per-input semantic rejection, raw ambiguous 4xx and normal empty overlap. The first barrier stays latched and metadata excludes provider messages, bodies and participant text. The driver and worker share the same safety bean.

The additional admission RED executed eight cases: six failed and two existing-authority controls passed. It reproduced both a second SDK admission at the actual fixture factory after SERVER and a worker blocked by the driver's whole-action monitor. After implementation, all 44 safety/admission cases passed. Removing safety only at the actual fixture factory failed the second-SDK counter assertion while the other 43 cases passed. Restoring the whole-action monitor failed only the worker-progress assertion. Both files were restored byte for byte and all 44 passed again. The original JDBC authority runs before safety/admission; null or thrown authority never becomes a grant.

A separate completion boundary now stops further rooms when application analysis remains unfinished. INVALID_RESPONSE alone does not create a provider outage barrier; an ANALYSIS_DELAYED room or unfinished public views produce INCOMPLETE_ANALYSIS. The actual driver calls this check after reading candidate/recommendation views and before selection or another room. Four completed public states, including NO_MATCH and READY_WITH_WARNINGS, remain eligible with successful public views, independently of options count or gold.

## Cross-process evidence

The new runner validates a complete development continuation report before key checks, output creation or a Gradle invocation for another split. Commit, source and configuration hashes must match; a missing/stale report, technical barrier, unsuccessful process or incomplete application analysis prevents continuation. Completed semantic failures and zero recommendations remain eligible independently of any gold comparison.

Three initial runner faults were independently detected: skipping the actual main prerequisite call, ignoring the prior process result, and ignoring incomplete analysis. Each restored exactly and the eight offline test methods passed. The first main-bypass attempt exposed an incomplete synthetic path fixture and is not counted as detection; the corrected fixture reaches a meaningful assertion.

The later outcome contract additionally requires an evaluation exit result distinct from raw Gradle status. Its actual-main mocks reproduced Gradle exit0 incorrectly reporting success for STOP, incomplete or missing reports. The final continuation report requires both the raw process status and evaluation status to be integer0. All twelve offline methods pass. Five faults were detected on the final runner bytes: main prerequisite bypass, ignored process failure, ignored incomplete analysis, ignored evaluation failure, and actual main returning raw Gradle status. Every fault restored exactly. These mocks launch no actual subprocess.

## Primary-only diagnostic preparation

The new diagnostic is default OFF and has a separate explicit mode flag. Its planner only writes an approved=false manifest and cannot launch Gradle or models. A dirty tree marks the plan unready. Prechecks are explicit CLEAR test doubles, recorded as TEST_DOUBLE; this supplies no semantic quality evidence. The actual JDBC source authority remains first, every same-call revalidation checks it again, and only one distinct FINAL_PRIMARY identity can be admitted. Jev, Luna and all other operation grants are excluded. The existing full-blind fallback control passes.

The fifteen mode contracts pass. Removing its operation restriction caused four assertion failures; removing the one-call cap caused three. Both restored exactly and all fifteen passed. An additional real local HTTP/PG/Redis test with empty provider keys verifies three finished synthetic prechecks, three received/frozen V2 versions, one original nonmock JDBC authority with the resolved safety wrapper, exactly one Gemini CONFIGURATION attempt, zero SDK client lifecycle calls and zero OpenAI attempts. Returning the raw JDBC bean instead of the actual Spring diagnostic wrapper caused an assertion failure after successful context startup. The wrapper restored exactly and the application contract passed again.

## Evidence and limits

Machine-readable RED/FI metadata is under `.tdd/red/issue107-followup-independent-contracts.json`, `.tdd/red/issue107-primary-only-smoke.json`, `.tdd/red/issue107-completion-boundary.json`, and `.tdd/verification/issue107-followup-*.json`. The aggregate `.tdd/verification/issue107-followup-final-independent-audit.json` records final hashes, commands, suite counts and exact-restore outcomes. Raw logs are under `local-only-evidence/followup-*`; offline runner tests and logs are in `local-only-evidence/harness-safety-followup-20261029`.

The final seven-suite independent baseline passed all 74 cases with zero failures, errors or skips (`followup-completion-final-restored-green.log`). All twelve distinct follow-up fault injections were detected and restored exactly. The twelve offline Python methods also pass on the final runner bytes. The serialized Gradle slot was handed to model_contracts for the overall quality gate and commit guard; those results are not claimed complete in this independent checkpoint. No real-model availability, successful fallback or new semantic evaluation is claimed.


## Final diagnostic-boundary checkpoint

After the earlier74-case checkpoint, read-only review found that a diagnostic flow exception could skip its artifact, polling could ignore the shared barrier, and an approval was not tied to one output/claim. The whole baseline gate passed292 suites/2386 cases (2380 pass,6 intentional skips,0 failures/errors) before this final patch. That result is preserved as baseline1, not presented as verification of the later changes.

Seven new independent cases and the strengthened real application case reached valid TODO RED8/8. The recorder now writes one safe artifact from finally, preserves the original flow exception when snapshot collection fails, and excludes exception messages/raw response or room payloads. The actual HTTP flow uses shared-safety polling; its empty-key CONFIGURATION failure stops the flow and retains safe evidence. All existing frozen-version, source-authority, Gemini-attempt and zero-SDK/Luna SQL checks remain. The planner requires an output directory and emits an approved=false plan with a UUID. A permanent write-once claim binds that plan to the approved output and survives a failed flow.

Four additional faults were detected by explicit assertions: omitted report write (4 failures), bypassed actual HTTP polling binding (1 application-case failure), omitted output equality (1 failure), and reused manifest claim (1 failure). Both changed source files restored byte for byte. The final independent baseline passed8 suites/81 cases with0 failures/errors/skips. The offline prerequisite/outcome/planner tests pass14 methods; all5 earlier cross-process faults were refreshed on final runner bytes and restored exactly. These refreshed faults are not counted twice: the follow-up has16 distinct fault injections (12 earlier+4 final).

Initial executed RED hashes remain in their original record; the strengthened current assertions have separate intentional-fault RED hashes in `.tdd/red/issue107-primary-last-boundaries-current.json`. The final sorted14 Kotlin-source/test hashes and5 offline-file hashes, exact commands, results and whole-gate distinction are in `.tdd/verification/issue107-followup-final81-independent-audit.json`. All source/test/runner changes are frozen and the serialized slot was handed to model_contracts for the unchanged original guard/full gate and local commit. Those final overall results remain pending at this independent checkpoint. Additional paid calls remain0; approval readiness remains false and original c945 artifacts remain unchanged, and sealed inputs/gold were not read.
