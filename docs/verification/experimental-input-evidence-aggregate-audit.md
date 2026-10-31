# Independent experimental input evidence audit

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

The independent review found no remaining blocker in the reviewed implementation and fixture boundaries. The final repository-wide gate and authorized live evaluation remain separate completion steps; this audit does not claim live model quality or external exactly-once delivery.

## Core verification

Eleven isolated functional faults and one timestamp precision fault were detected by compiled assertions. Every modified production source was restored byte for byte. The restored functional run passed 53 tests, and the restored precision run passed three tests, including actual PostgreSQL source authorization with the application dispatch mode enabled. The eleven-fault snapshot precedes the precision correction; the separate precision summary records the final versions of those two source files.

- Functional evidence: [issue107-evidence-contract.json](../../.tdd/verification/issue107-evidence-contract.json).
- Precision evidence: [issue107-evidence-precision.json](../../.tdd/verification/issue107-evidence-precision.json).
- Detailed test scope and oracle corrections: [independent test plan](experimental-intake-independent-test-plan.md).

The faults covered collapsed evidence fingerprints, original replacement, false CLEAR promotion in receipt and classification, unauthorized host diagnostics, lost post-close evidence, omitted final-provider evidence, reintroduced final questions, discarded second-question safety, incorrect failure scope, missing frozen evidence on reload, and nanosecond/microsecond drift. Exact source fencing was retained; no tolerance or missing-column production fallback was introduced.

Normal low confidence and model uncertainty produce distinct unresolved reasons; a post-answer review remains unresolved without another generation. The corresponding refusal and timeout controls continue to raise technical failures. They do not become semantic receipts or CLEAR. Unresolved received input remains available for final analysis with its original and answer, while a failed interpretation never invents availability.

## Historical fixtures and native API metadata

Independent source review approved the following corrections without weakening the original business assertions:

- PC06 explicitly uses a test-only pre-V21 store to prepare V17/V18 history. The same two-of-five selection is rejected with SQLSTATE 23514 at V17 and accepted at V18 inside rollback-only probes with foreign-key validation. All V18 snapshot assertions remain. The test then applies and validates V21 before explicitly selecting the current production repository and running the original HTTP result, selection, idempotency and count assertions. The historical store rejects V2 evidence and never probes columns or catches schema errors as a fallback.
- V20 index budget fixtures now target V20 explicitly. Their finite lock/statement budgets, 55P03 failure, rollback, migration history, index state, retry and ownership assertions remain intact. The fresh latest-schema control still covers V21.
- The V8-to-V21 snapshot comparison excludes only the newly added nullable `submission_versions.input_evidence` key and separately requires every historical value of that field to remain null. Existing original values, frozen membership, attempts and confirmation checks remain unchanged.
- I154 still compares the exact native API projection. Protocol and every operation are unchanged. Published metadata adds only the two approved evidence schemas, evidence on candidate/accepted views, question answer mode, and the final answer/failure scope on host diagnostics. The exact comparison test was not relaxed.

The five affected suites passed all nine focused cases with zero failures, errors or skips. Supporting output is `local-only-evidence/evidence-fullgate-fixture-focused-2.log`; the preserved XML is `local-only-evidence/full-gate-fixture-focused-xml`.

No new gold fixtures or live model calls were created by this review. The final full gate must pass and the source must be frozen before the delegated live evaluation proceeds.
