# Experimental evidence persistence boundary

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

The isolated experiment starts at server main `40aea5139525b9ed2cba34428f0072cc42e15820`. It adds nullable `input_evidence` JSONB to candidate and submission-version rows in V21. Existing rows are not backfilled, and the database default remains `PRE_SUBMIT_V1`. Selecting `PRE_SUBMIT_V2` is an explicit application decision for new experimental rooms. Neither the migration nor the release-catalog update deploys or enables the experiment.

The original text, original revision and interpretation reference time remain distinct from one scoped answer. The received submission version stores its own complete evidence snapshot. Reading an older frozen version must return that snapshot even after a participant changes their current candidate or submission. Candidate room text accounting includes the original and the finalized answer once; question metadata is not counted as participant text, while the JSONB storage and provider request byte limits remain bounded.

Personal dispatch authorization binds the persisted candidate evidence as well as the raw text and existing room/owner/CAS/deadline context. Final authorization binds the **frozen submission-version evidence**, never a mutable current candidate or its pending evidence. A V2 natural-language input without evidence, invalid source span, mismatched original anchor or pending final interpretation cannot authorize a provider call. V1 inputs with null evidence keep their existing fingerprint bytes.

V21 extends the exact migration catalog and both metadata readers additively. Historical V1–V20 SQL bytes, sparse historical catalogs, package verification and approval guards remain in place. Current-publication probes now enumerate exactly V1–V21; historical package/reader probes retain their old version inventories. Before-images of the affected current-inventory test metadata are retained under `.tdd/history/issue107-v21-catalog/`. A previous artifact that recognizes only V20 is not a compatible writer for a V21 database; rollback must preserve V21 and use a compatible build rather than downgrade or erase evidence.

## Verification evidence

- `local-only-evidence/v21-catalog-red.log`: three meaningful catalog failures before implementing V21 publication/reader support. They detect a rejected complete V21, acceptance of a missing V21, and a reader that does not know V21.
- `local-only-evidence/v21-catalog-green.log`: 28 current and historical catalog tests passed.
- `local-only-evidence/v21-all-catalog-green.log`: 120 catalog, package, reader and approval compatibility tests passed, with zero failures or skips. These are offline tests and do not execute AWS or a deployment.
- The first storage fixture attempt did not enable Flyway and failed while truncating nonexistent tables. It is invalid RED evidence. The fixture now explicitly enables Flyway; subsequent semantic storage and fence results are recorded by the coordinating test run.
- `local-only-evidence/evidence-storage-privacy-red-green-2.log`: corrected storage fixture executed three cases, with two meaningful assertion failures showing that candidate/version evidence was read back as null and one V21 compatibility control passing. The exact test fingerprint and command are in `.tdd/red/issue107-evidence-persistence.json`; the local original log is `.codex/tdd-evidence/issue107-evidence-persistence.log`.
- `local-only-evidence/evidence-correction-red-core-green.log`: the same three storage cases passed after persistence implementation. The enclosing run had seventeen tests and two other requirement failures, so it is not reported as a successful full gate.
- The independent fence cases passed in the coordinating run after their two meaningful authorization assertion failures and one control pass. Final independent review found no missing evidence field or incorrect candidate-versus-version source binding.
- Independent `EvidenceExecutionFencePostgresTest` covers exact personal evidence, missing V2 evidence, changed/omitted answers, and frozen-version evidence instead of pending-candidate evidence. `EvidencePersistencePostgresTest` covers null legacy evidence, historical-version round trips, candidate round trips and original-plus-answer text admission.

This record does not claim production migration, operating configuration, real-model semantic accuracy or live provider success.

## Historical fixture reconciliation after the full gate

The first complete gate executed 2,311 tests across 284 suites and reported six failures, zero errors and five skips. Four failures belonged to these historical migration fixtures; they are not treated as a successful full gate or as proof of a production migration defect.

- `AccountRoomHistoryIndexBudgetPostgresTest` failed two assertions because an untargeted upgrade from V19 now applies both V20 and V21, while its V20 DDL safety oracle expects exactly one migration. Both the pooled migration and the helper now explicitly target V20. All actual DDL timeout, PostgreSQL `55P03`, rollback, unchanged business/history/index, valid-index and retry assertions are retained.
- `AccountRoomHistoryIndexMigrationPostgresTest` failed the same one-versus-two migration assertion. Only its historical V19→V20 preservation case now explicitly targets V20. Its fresh-latest control still applies the complete current catalog. Full JSON equality, ownership-constraint and user-leading-index assertions remain intact.
- `IntegratedLegacyUpgradePostgresTest` failed exact business snapshots because V21 adds `input_evidence: null` to six legacy submission-version JSON objects. The V8-column snapshot now projects out only that newly introduced column. A separate assertion requires every legacy version's evidence to remain null. Every preexisting business column and all frozen-cohort, ownership, confirmation, revision-round, attempt and unknown-budget assertions remain unchanged.

The independent test designer reviewed these changes before implementation. Original test bytes, failed XML files and SHA-256/count summaries are preserved locally under `.codex/tdd-evidence/issue107-v21-historical-fixtures/`. These edits affect tests only; production source, previous migrations and fallback behavior are unchanged. Focused and full rerun results follow in the coordinating verification record.
