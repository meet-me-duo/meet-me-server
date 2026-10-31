# Experimental intake independent test plan

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

Oracle: the current user-approved experimental contract, read with PRD FR-008/008A/008D, FR-009 and ADR-058. The experiment supersedes the older CLEAR-only receipt, full replacement choice, and three-question rules for its own opt-in path. No existing production protocol is changed by these tests. Question scope is conservatively participant plus room; this remains an explicit experimental assumption pending product confirmation.

Synthetic inputs only. No holdout fixtures, production database, paid providers, merge or deployment.

## Independent acceptance cases

| ID | Action and adversarial condition | Observable requirement |
| --- | --- | --- |
| EX-ORIGINAL-01 | Submit `목요일 7시 이후 가능. 금요일은 불가.`; answer only its AM/PM question | The original Unicode text and interpretation reference time remain byte-for-byte unchanged. The answer is a separate supplement; Friday exclusion remains available to final analysis. |
| EX-SOURCE-01 | A model cites `목요일 9시` although original says `목요일 7시` | No unsupported excerpt is published as a valid grounded question. It cannot authorize CLEAR or silently alter raw input. |
| EX-SOURCE-02 | A provider returns an excerpt from a different participant, including a guessed private address | The participant cannot receive that excerpt or invented address. No cross-participant inference is accepted as source evidence. |
| EX-SOURCE-03 | An A/B proposal replaces the whole input and removes a negation | It is not accepted as a replacement. Safe targeted supplements or a text-only question with direct answer preserve the full original. |
| EX-QUESTION-01 | First check needs clarification; answer still unresolved | Exactly one participant-facing question exists. The answer triggers exactly one recheck; the unresolved receipt is eligible for final analysis but is never marked CLEAR. |
| EX-QUESTION-02 | Retry and revision after a participant has already consumed the room question | No second question is emitted. Revisions do not reset the participant-room question budget. |
| EX-QUESTION-03 | Two callers race to publish clarification or answer it | At most one question/answer transition wins. The loser observes a defined conflict or the same idempotent outcome. Both original records survive. |
| EX-IDEMPOTENCY-01 | Repeat identical request ID and body, then reuse ID with a different answer | Exact retry replays the original outcome without a second recheck. Different-body reuse is rejected. |
| EX-REVISION-01 | Answer a prior revision after a new original is saved | The stale answer cannot overwrite the new original, consume its revision, or authorize its interpretation. |
| EX-RECEIPT-01 | Save succeeds, interpretation fails or remains unresolved | Receipt is stored independently from interpretation. Failure does not delete the original, hide receipt, or invent availability. |
| EX-FREEZE-01 | Freeze after unresolved answer, then revise or deliver a late provider result | The batch includes the frozen original, answer and unresolved status. Later writes/results cannot mutate the batch or reference time. |
| EX-FREEZE-02 | Answer races with room closure | One serialized boundary decides inclusion; final analysis receives either complete pre-answer or complete post-answer state, never a torn original/answer/status combination. |
| EX-FINAL-01 | Two participants resolve, one fails final interpretation | Successful participants remain usable by deterministic matching. The failed one has no fabricated availability and receives an independent diagnostic. |
| EX-PRIVACY-01 | Member or foreign host reads another participant's receipt/question/answer/diagnostic | Access is rejected. Knowing participant UUID, invite link or display name is insufficient proof. |
| EX-PRIVACY-02 | Host reads final failures | New host diagnostics follow their explicit protocol. Question and answer details are never leaked to other participants through room status, counts, public errors or logs. |
| EX-PLACE-01 | Time is clear, place is unresolved | Place uncertainty does not block time handling. It cannot make the time interpretation unavailable or invent a location. |

## Required evidence

Tests must first compile against agreed skeletons and fail for missing behavior. Record command, test fingerprint, source revision and intended failure before implementation. Run the same tests after implementation. For concurrency and transaction claims use real PostgreSQL, not an in-memory imitation or H2. Add explicit fault injection for duplicate question publication, raw replacement, unconditional CLEAR after answer, stale result publication and missing host/member access checks. A green in-memory contract test does not establish durable crash safety.

The separate time interpretation test suite owns exact `오전=[00:00,12:00)`, `오후=[12:00,24:00)`, full-search-range weekday meaning, `19시 이후` ending at 24:00, and 22:00–02:00 crossing midnight. Do not derive arbitrary morning/evening/night bounds from examples.

## Test contract correction before final RED

The initial public intake test fingerprint `500fe9629f588f02c1933d3cecae04fc0abe92aae24807f9933829b2f09dcc62` is superseded. The independent reviewer identified that a foreign participant may receive the existing documented 409 current-check/CAS denial as well as 403 or 404. The user contract requires denied authority and no content disclosure; it does not select a new status code. The corrected test still requires no leaked original/question and no owner revision change. Its fingerprint is `265b46b05e58eec16334e20b12327f0fbb302d72d9b7b835c8b70a1f51b81e14`. The parent must collect fresh compiled RED for this file before treating its durable original/answer preservation requirements as verified TDD evidence. The old execution remains historical evidence only.

The corrected intake test subsequently had eight requirement failures and two passing controls in `evidence-storage-privacy-red-green-2.log`; the two V2 host diagnostic cases failed assertions in the same run. Formatting then changed the intake and host diagnostic file bytes without changing assertions. The saved summary `issue107-evidence-intake-privacy.json` deliberately retains the executed, pre-format fingerprints; it must not be presented as evidence for the formatted bytes. A fresh intentional-fault RED and restored GREEN is required before replacing it with final fingerprints. Domain test bytes were unchanged, and `issue107-input-evidence-domain.json` records its fifteen original TODO failures with the exact clean-test command.

The post-close correction test also had an ordering oracle corrected: `findFrozenByVersionIds` promises frozen values, not list order. The test now compares complete submissions by frozen version ID and independently requires the same cardinality. It retains every frozen original, answer and interpretation assertion. Its revised fingerprint is `d68df36ebaf88133b12de174b391da98003ea61a3a840db044a3309aca77ba73`; the earlier failure for absent correction evidence remains historical until fresh RED validates these bytes.

## Independent fault injection result

All eleven isolated faults were detected by compiled assertions. Each modified source was restored byte for byte immediately afterward. The final restored focused run passed all 53 tests. The summary `.tdd/verification/issue107-evidence-contract.json` records source/test fingerprints and commands; `.tdd/red/issue107-evidence-current-*.json` records fresh failures for all thirteen formatted test files. Local raw logs and the exact-restore manifest are under `local-only-evidence/evidence-fi`; they are not published test data.

Faults covered evidence fingerprint collapse, original replacement, false CLEAR promotion in receipt and classification, unauthorized host diagnostics, missing post-close evidence, omitted final-provider evidence, reintroduced legacy final questions, discarded second-question safety, incorrect failure scope priority and missing frozen evidence on reload. These are synthetic contract checks, not proof of actual model semantic accuracy or external exactly-once behavior.

The older intake/privacy summary is preserved under `.tdd/history/issue107-evidence-pre-format`; no old run is attributed to new formatted bytes. The eleven-fault result describes the restored source snapshot before the separately discovered timestamp precision correction. That correction requires its own independent RED/GREEN evidence: JSON Instant nanoseconds and PostgreSQL microseconds must share a persisted reference without moving the original KST date across midnight, and exact source comparison must remain strict.

## Timestamp precision follow-up result

The final precision fixture explicitly enables the application dispatch mode for its positive runtime source-authorization check. The earlier missing-mode fixture is preserved as history; the non-null authority assertion was retained. Three compiled assertions failed when persisted-metadata normalization was removed, both production files were restored byte for byte, and all three precision tests then passed. The final test fingerprint is `8298709c60425680b59ab5fad856c30679d19c1b90e5ec9c2005f733a8b74eee`.

`.tdd/verification/issue107-evidence-precision.json` records the final precision source fingerprints and the twelfth detected fault. The implementation normalizes V2 original/answer/post-close persisted metadata to microseconds before JSON and PostgreSQL writes. Authority and deadline comparisons continue using the original clock, and source comparison remains exact. The KST pre-midnight reference remains on October 29 even when the provider claim is checked just after midnight. Independent source review found no remaining blocker in the reviewed boundaries; repository-wide gates and final native/model evaluations remain with their delegated owners.
