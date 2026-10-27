# #123 Phase 2 게시 준비 기록 독립 리뷰

2026-10-18 UTC. 읽기 전용 검토. worktree `/workspace/issue123-durable-budget/server`, branch `feature/issue-123-durable-ai-budget`, HEAD `e83d5280c7c236c99d64165893632920882527fe`. 구현자/문서 owner root와 분리된 reviewer `/root/baseline_merge_review`. 이 파일 외 저장소 수정0, src/test/history 수정0, Gradle/모델/운영/원격 접근0. 아래는 문서 상태/현재 지시의 P2만이며 제품 결함이나 새 정책 결정을 주장하지 않는다.

## P2-R1 — 현재 Worklog의 다음 시작점이 freeze/최초 RED로 되돌아감

- 위치: `docs/ISSUE_107_PRE_SUBMIT_WORKLOG.md:5`.
- 현재 문구: 계약/독립 명세를 “freeze 중”, “다음 시작점은 컴파일 skeleton→독립 실제 PG/loopback RED”.
- 대조: `implementation_plan.md:8`은 이미 freeze 완료다. Worklog `:155–168`은 catalog RED/GREEN/FI, actual SQL catalog72, 원장52·dispatch78/기존provider387 GREEN과 후속 P2/managed/fence 통합 cycle 진행을 기록한다. 맨 위 단락은 스스로 최신 상태라고 선언하므로 재개 담당자가 이미 끝난 freeze·첫 RED를 다시 수행할 수 있다.
- 최소 보정: 맨 위 current 단락만 최신 다음 시작점(미완료 managed/fence·후속 방어 RED/GREEN→FI/독립 최종 리뷰/전체 guard)으로 갱신한다. 아래 시간순 기록은 당시 이력으로 보존한다. catalog72/FI3나 Phase1 full1479를 새 Phase2 전체 guard 성공으로 합산하지 않는다.
- 관련 비차단 명료화: `implementation_plan.md:13–22`의 “현재 #107 … 준비” 및 “영속 원장/전체 dispatch 연결 미구현”은 **PR122 봉인 당시 제외 범위**라고 한정하면 Phase2 원장 부분 GREEN과 혼동하지 않는다. Phase1 완료 checkbox/지문/CI를 취소하거나 Phase2 구현 완료로 옮기지 않는다.

## P2-R2 — Architecture의 목표 managed/fence 계약이 현재 완료 동작처럼 표현됨

- 위치: `docs/ARCHITECTURE.md:589–591`.
- 현재 문구: ON은 다섯 실제 HTTP 모두 managed gate 사용, worker/processor 문맥 전달 및 fence 재검증, accepted bounded production composition 지원을 조건 없이 현재형으로 설명한다.
- 대조 snapshot: `shared/adapter/output/aihttp/AiHttpDispatchConfiguration.kt:16`의 mode bean은 `TODO("TDD RED: OR01")`; `shared/adapter/output/persistence/JdbcAiBudgetExecutionFence.kt:26`은 `TODO("TDD RED: DB06")`다. Worklog `:166–168`도 실제 PG fence·worker/processor·managed constructor는 다음 통합 cycle이며 전체 gate/최종 리뷰/게시 미완료로 명시한다. 실제 본문과 GREEN가 추가되면 그 정확 source 증거로 상태를 갱신해야 한다.
- 최소 보정: #123 Architecture 문단 앞에 “다음은 확정 목표 계약이며 managed/fence 연결과 production composition 검증은 진행 중”이라는 상태 한 줄을 둔다. 현재 완료한 V17/ledger 단위와 아직 미완료한 연결·회귀/최종 guard를 구분한다. ADR-055와 구현 계약의 **결정/요구사항** 문장은 그대로 유지해도 되며, 그 선언을 구현 완료나 운영 활성화로 승격하지 않는다.

## 충돌 없음으로 확인한 경계

- Phase1 PR122/e83는 #124의 기본 OFF adapter/bridge·합성 검증 범위로 봉인되고 Draft·미병합이며, 별도 Phase2 branch가 해당 base를 상속한다. 이 상속은 새 Phase2 exact HEAD CI 완료나 PR122 수정/병합을 의미하지 않는다. Phase2 commit/push/Draft/정확 HEAD CI 체크는 아직 미완료다.
- 계약/ADR055/Architecture는 enforcement OFF 중 기존 final 동작을 보존하고 그 호출을 계상 완료로 간주하지 않는다. ON의 신뢰 quote/권한 요구와 생산 registry DENY, 과거/HISTORICAL_UNKNOWN·신규 UNCONFIGURED·coverage 없는 초기화 거절 방향이 일치한다. V17 이후 생성 여부만으로 spent0/fresh가 되지 않는다.
- 살아 있는 방의 timeout/마감과 기존30일 실제 room 삭제를 구분한다. room-owned ledger cascade/늦은 관측의 재생성0, 공유 profile의 비PII 구조정보 한정도 일치한다. 장기 회계 보관소나 tombstone/보존 연장을 새로 도입하지 않는다.
- 운영 B/통화·FX/세금/가격·billed cap·confidence·소진 UX·보호된 최종 분석 몫·과거 UNKNOWN 해제는 TBD다. 평가 전체 합산 USD1 승인은 비용/권한 gate 조건부이며 실제 모델0. 제품10원 충족·운영 활성화·Decisions develop/main 병합·배포 완료를 주장하지 않는다.
- 500 codepoints와 기존 방/질문/실행/재시도 한도·개인15초/실행30초·final60/Gemini15/Luna27/완료3초·last CLEAR/소유권/publish fencing·최종 whole-room AREA/결정론적 matching을 보존한다. 새 금액/참여·마감 정책을 확정한 문구는 확인하지 못했다.

## 검토 snapshot 지문

파일은 병렬 작성 중이므로 아래 지문 시점의 검토다. 실제 게시 전 root 최종 문서와 최종 source에 다시 연결해야 한다.

| 파일 | SHA-256 |
| --- | --- |
| implementation_plan.md | 775619f33ad088a30debbf7e23a99331fb2b2f0947a94b925e4a3eccd181e673 |
| docs/ISSUE_123_DURABLE_AI_BUDGET_CONTRACT.md | e7f9bf50f49a3cd42a7c5b0b04abe02c9b84f6bf2ad68814ec644fba20e9123c |
| docs/ADR.md | 1716bcc00a50cc5a0279fb2c0582a650a89e8e1d6843fab7759da6a569b2836b |
| docs/ARCHITECTURE.md | 7dbbe039bf1baffe3bfe8992a4c4e0e7f29c962fb888ba4f98d4d6d7f9546c88 |
| docs/ISSUE_107_PRE_SUBMIT_WORKLOG.md | db8d0ae6f4e42b5b9d2dc26da31035a22bb4895e17ba38071a7b7c7d17939994 |
| src/main/kotlin/com/meetme/server/shared/adapter/output/aihttp/AiHttpDispatchConfiguration.kt | cc922379a5ceb1780c68025788ac7c44babe1ec976511338f29de63a70678d44 |
| src/main/kotlin/com/meetme/server/shared/adapter/output/persistence/JdbcAiBudgetExecutionFence.kt | c3ba67a6e62f388bc73e69314c3143bb390fc6405438b7b8b562646fcfedcf82 |

판정: 위 P2 상태 문구 두 곳 보정 후 기록 일관성 재확인. 제품/전체 구현·게시 readiness PASS는 아직 판정하지 않는다. root가 문서를 수정하고 마지막 source/GREEN/full gate에 근거해 현재 상태를 마무리한다.
