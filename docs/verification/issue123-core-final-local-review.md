# Issue123 core/fence 최종 로컬 범위 독립 리뷰

2026-10-18 UTC. `/workspace/issue123-durable-budget/server`, broad #123 phase2. **검토한 core/fence/FI4 및 실제 PG9+관통1 범위는 로컬 PASS. 미해결 신규 P1/P2 없음.** provider FI5/통합108 최종복구/전체 unchanged mandatory guard/최종 게시 리뷰는 아직 이번 판정에 포함하지 않는다. commit/push/Draft/exact HEAD CI 또는 전체 제품/운영 활성화 승인으로 확장하지 않는다.

## 이전 source 판정과 결합

`/tmp/issue123-core-source-review.md`의 CORE-P2-01/02/03은 `/tmp/issue123-core-defense-review.md`에서 최소 source 수정으로 닫았고, 이후94 집중 GREEN과 이번 FI 검증으로 연결했다. foreign reservation row는 immutable unlocked hint로 자신의 scope를 확인한 뒤만 잠그고, profile 마지막 대기 뒤 신규 quote의 fresh expiry를 검사하며, aggregate overflow의 알려진 표현 가능 C는 observation에 보존한다. 원래 full hold/FROZEN/strict cap/immutable replay를 유지한다.

`/tmp/issue123-execution-fence-source-review.md`의 real ownership/current revision/binding expiry·정상 CLOSED final·실제runVersion0·provider ordinal·15/27·최소2 frozen owner/정확 natural inputs·coverage epoch 판정은 current source와 같은 SHA다. V17의 structural-only global profile과 room-owned evidence cascade/기존30일 삭제, historical UNKNOWN/default DENY도 앞선 source review와 동일하다. 새 참여/마감·가격/통화·보존 정책을 결정하지 않았다.

## core/fence FI4 직접 검증

정본 `.tdd/verification/issue123-budget-core-fence-fi.json`은 요구한7개 필드를 갖고 source3/test11 current SHA가 모두 일치한다. `.tdd/history/issue123-budget-core-fence-fi/manifest.json`과 raw `.codex/tdd-evidence/issue123-budget-core-fence-fi/`의 각 XML/log SHA, 실제 assertion 유형·메시지·합계를 직접 대조했다.

| 변이 | 독립 oracle의 실제 탐지 | 정확 복구 확인 |
| --- | --- | --- |
| strict `< B`를 equality 허용으로 변경 | expected null 대신 reservation, 1 assertion FAIL/errors0 | 같은 oracle1 PASS |
| application RESERVED guard와 SQL CAS를 함께 제거 | 기존 consumed claim이2회 허용됨, 1 assertion FAIL/errors0 | 같은 oracle1 PASS |
| timeout/UNKNOWN hold 자동 환급 | expected30 대신0, 1 assertion FAIL/errors0 | 같은 oracle1 PASS |
| 실제 ownership binding 검사 우회 | ACCOUNT_CLAIM인데 current authority 반환, parameter13 중1 assertion FAIL/errors0 | parameter13 PASS |

baseline94 및 모든 복구 뒤 final94는 각각 failures0/errors0/skips0이었다. tracked exact patch4를 current source 문자열에 **메모리에서만** 적용해 manifest mutantSHA와 재구성 비교했고4개 모두 일치했다. 복구 SHA는 current source와 일치한다. 원본 source/test inventory475는 before=after=current이며 원본 제품에 변이를 가한 흔적을 이번 실행 근거에 포함하지 않는다. source/tests/Gradle 실행·편집은 이 reviewer가 수행하지 않았다.

이는 post-implementation fault injection이다. 이를 원래 chronological RED로 소급하지 않는다. 기존 중복 parameter ID를30개 고유 method+case ID로 바꾼 canonical RED는 metadata 식별자 정합성 보완이며, current test c678…와 원 XML/stack·원본 canonical history를 유지한다. current canonical은7필드, failedTests30개/unique30개다.

## 실제 PG9와 whole-room1

`.codex/tdd-evidence/issue123-dispatch-pg-whole-current/exit`/receipt exit0 및 XML2개를 직접 합산했다: **final lifecycle9 + whole-room1 = tests10/failures0/errors0/skips0**. source-before/source-after475가 같고 current와 전부 일치한다. real PostgreSQL·현재 durable service/fence/실제 provider adapter + loopback HTTP이며 가격·coverage·complete bill은 synthetic seam이다.

확인한 oracle 의미:

- 실제 final primary가 invocation/runVersion/attempt와 같은 방 원장에 기록되고, 실제 primary4+fallback1이 다섯 물리 요청·다섯 hold를 같은 account에 누적한다.
- HOST retry/PENDING duplicate, 바뀐 correction의 새 batch, DLQ replay와 failure-row 삭제가 기존 UNKNOWN hold를 초기화하지 않는다. fresh logical invocation이 새 room budget이 되지 않는다.
- strict equality budget denial은 provider HTTP0/추가 reservation0/nonretryable 안전 지연이며 이미 commit된 logical attempt를 임의 환급하지 않는다. ON unsupported missing fallback/repository는 I/O를 열지 않는다. fallback bean의 빈 key가 정상 primary를 미리 막지 않는 양성이 포함된다.
- 실제 API 제출과 Worker의 A→count→B 세 요청, 둘째 참여자의 A CLEAR 한 요청 뒤 expected2 자동 마감, frozen batch 전체의 final SDK 한 요청을 실행했다. 원문과 accepted version cohort가 유지되고 같은 room·같은 epoch·원장1개에 execution3개/physical reservation5개, **held50 TEST_SUBUNIT/settled0**이 남는다. token usage를 bill로 바꾸지 않는다. final은 MATCHING까지이며 실제 matching 완료/배포/품질 완료를 뜻하지 않는다.

관통1은 요구대로 새 코드 분기 없이 기존 기능을 묶은 INITIAL_PASS이며 chronological RED가 있었다고 만들지 않는다. TM06 최초 실제 실행에서는 fixture SQL의 존재하지 않는 outbox `created_at`으로 setup 실패가 났다. 원본/결과를 `.tdd/history/issue123-dispatch-integration-outbox-fixture/`에 보존하고 정본 V1의 `occurred_at`으로 한 token만 교정했으며 expectation/product는 바꾸지 않았다. 현재 final lifecycle test SHA는352f…다. 이 setup 오류를 제품 RED/FI 탐지로 세지 않는다. 부모/author가 준비 중인 corrected TM06의 reconstructed assertion supplement/FI5는 완료 후 별도 최종 provider 리뷰에 연결해야 하며 지금 성공으로 선반영하지 않는다.

## 실제 Decisions 진단과 기능/비용 제한

`docs/ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md` 및 `.tdd/verification/issue123-live-decisions-diagnostic.json`의 정합성을 읽었다. 별도 실제 Decisions 요청은4회이고 smoke+진단의 네 HTTP200/strict production parser offline 통과와 의미 평가 결과를 구분한다. 사전 NEEDS_REVIEW 사례에 raw CLEAR/confidence0.06이 나온 것은 **의미 불일치/STOP**이며 마지막 제외·부정 사례는 미호출이다. threshold0/가정0.1의 offline 검사는 운영 threshold 선택이나 원래 raw 정답을 PASS로 바꾸지 않는다. 생성 B/최종 실모델 호출은 해당 진단에서0이다.

이4회는 본 PG/FI cycle의 external0과 별도이며 이전 총1회 상태를 최신 사실로 대체한다. 이 문서 리뷰에서는 추가 호출·key/계정/운영 조회를 하지 않았다. 가격 환산은 invoice/계정별 tax/fee verified가 아니고 token/byte 견적이 server-enforced cap이라는 주장도 없다. 독립 비용 리뷰를 대체하거나 새로운 가격 권위를 승인하지 않는다. 네 사례로 정확도/p50/p95·전체2단계 의미 평가·운영 준비 완료를 추론하지 않는다. raw mismatch STOP·운영 threshold TBD가 보존되어 현재 문서 간 새 모순은 발견하지 못했다.

## 다음 게시 판정 전 남은 조건

provider FI5·수정 fixture의 정직한 reconstructed supplement·최종108 복구 결과와 source/test freeze, unchanged mandatory full guard, 필수 상태 문서와 표준 PR6절, 정확 HEAD CI는 다음 최종 publication 리뷰에서 확인한다. 지금 core 로컬 PASS만으로 그 조건을 생략하지 않는다. 운영 기본OFF/미구성 authority DENY, 실제 금액/세금/FX/참여 UX/운영 threshold TBD와 develop/main Decisions 미병합·미배포 경계를 유지한다.

## exact SHA256 근거

- core source `src/main/kotlin/com/meetme/server/shared/adapter/output/persistence/JdbcAiBudgetExecutionFence.kt`: `9a816338c3187805fe5bab6aefa053a0af5add45a1a04954e9e01e8538eeadd8`
- core source `src/main/kotlin/com/meetme/server/shared/adapter/output/persistence/JdbcDurableAiBudgetRepository.kt`: `2584914db2f47bf72f5de91f7dc9272b9e146a4cf7ff64384dbeefc6b727ec63`
- core source `src/main/kotlin/com/meetme/server/shared/application/service/DurableAiBudgetService.kt`: `9c2fb6df1d10d010e2644f11ba1476c30f599174dc1f534519d3099448fcc246`
- `src/main/resources/db/migration/V17__add_durable_ai_room_budgets.sql`: `912b8f227e6ea7c20fc05cf96d705982fb9737c5051c7c07e771494df482834b`
- `src/test/kotlin/com/meetme/server/coordination/adapter/input/web/Issue123FinalDispatchLifecyclePostgresTest.kt`: `352f83b3d518b29cecba6668776893d94e23684e48ed4572d200afe62e2776b3`
- `src/test/kotlin/com/meetme/server/coordination/adapter/input/web/Issue123WholeRoomBudgetPostgresTest.kt`: `f3b9756c890f8133b3877de325331c7fd583ce9e017520abb1850fbeee560356`
- `.tdd/verification/issue123-budget-core-fence-fi.json`: `1b14aab07d4e7e63910918caae9037aa7668d0308c6425fb2e8a0fd8a03c2a53`
- `.tdd/history/issue123-budget-core-fence-fi/manifest.json`: `81574a831345fc8de7f01c257081db3956f6dd8fe4519b7562915b2729f9121e`
- `.tdd/red/issue123-real-execution-fence.json`: `37ecd7552ae8fc184a36d62f50e2a7f4b05334639bb775960c1bc3698681a2d6`
- `.codex/tdd-evidence/issue123-dispatch-pg-whole-current/receipt.json`: `76ee3e23f122877037279257aae0dbe9e6bc0b2ac065d5a1e9829437a0406257`
- `.codex/tdd-evidence/issue123-dispatch-pg-whole-current/source-after.json`: `d91c0955ffcc79c99056db5c072380e8784368ac34b676d0e5d08342fb305885`
- `.tdd/history/issue123-dispatch-integration-outbox-fixture/correction.json`: `5e9cbcbdfcf5e68efcef1b200aaf3b7ebf08ae2c3ec97923523b2567a07b4b1e`
- `docs/ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md`: `11ab695ac9fcfcfb87ba63a0f956b6717f8cf2153ed6e51c66b11474f7cd205d`
- `.tdd/verification/issue123-live-decisions-diagnostic.json`: `d682fdbdf5c67168042d9b2beb29ace56faa5646156e6361419f279d604ea79a`

core/fence 테스트 11개의 current SHA는 위 FI canonical/manifest와 모두 일치함을 직접 확인했으며 중복 표기는 생략했다.
