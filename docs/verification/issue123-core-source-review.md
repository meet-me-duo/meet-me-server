# Issue123 durable budget core source 독립 1차 리뷰

2026-10-18 UTC. 대상 `/workspace/issue123-durable-budget/server`, phase2 #123. **현재 판정: 최종 승인 보류. P1 없음, P2 3건(기존 공유 1건 + 독립 발견 2건).** 아래는 최초 core GREEN snapshot에 대한 source 리뷰이며 설계 freeze PASS를 취소하거나 새로운 금액/참여/마감 정책을 선택하는 문서가 아니다. provider 전송 구현, 실제 실행 fence, FI 및 변경 없는 full guard 완료 후 최종 게시 판정이 별도로 필요하다.

## 범위와 검증 근거

`DurableAiBudgetService`, `DurableAiBudgetRepository`, `JdbcDurableAiBudgetRepository`, `DurableAiBudgetPort`, `DenyAiBudgetAuthorityRegistry`, V17 SQL을 frozen `docs/ISSUE_123_DURABLE_AI_BUDGET_CONTRACT.md`, 공개 계약 및 수정된 DB01–24에 대조했다. source/tests/SQL/git/Gradle은 수정·실행하지 않았다.

`.codex/tdd-evidence/issue123-budget-first-green/manifest.json` 및 보관 XML 5개를 직접 집계했다: 52 tests / failures0 / errors0 / skipped0. 보관 test source 6개 SHA는 manifest와 일치하며 검토 시점 current 6개도 일치했다. manifest의 production 9개 SHA 또한 검토 시점 current와 일치했다. 이는 real local PG + synthetic authority의 집중 GREEN 증거이고 실제 가격, 청구 상한, production 활성화 또는 전체 기능 완료 증거가 아니다. 후속 보완 테스트가 추가되면 이 최초 snapshot을 덮어쓰지 않고 별도 RGR/FI 근거로 연결해야 한다.

## 미해결 발견

### CORE-P2-01: 다른 방 reservation을 membership 검사 전에 잠금 — 기존 공유 사항

`DurableAiBudgetService.kt:122`에서 현재 요청 방의 fence/budget을 잠근 뒤 `findCall(reservationId, lock=true)`를 호출하고, 반환 행의 room/execution membership은 그 뒤 검사한다. 잘못된 다른 방 reservation ID는 다른 방 row를 잠글 수 있어 단일 room transaction/전역 잠금 순서 DB07을 위반한다. 부모가 이미 발견했고 독립 작성자가 DB07 보완을 준비 중인 사항이다.

수용 기준: 현재 room으로 제한된 reservation query 또는 잠금 없는 immutable hint 확인 뒤 현재 room 안의 lock/recheck를 사용한다. 상대 room row를 먼저 잡아 대기시키는 실제 PG oracle에서 잘못된 요청은 그 잠금에 진입하지 않아야 하고 어느 방 balance/intent도 바뀌지 않아야 한다. 기존 CAS/현재 ownership 검증을 약화하지 않는다.

### CORE-P2-02: profile 대기 뒤 quote만 만료하면 영속 예약/hold가 생성됨

`DurableAiBudgetService.kt:49,74`는 budget 잠금 이후 한 번 얻은 `now`로 신규 quote 유효기간을 검사한다. 이후 `insertProfile`은 global structural profile의 다른 transaction insert/unique 충돌에 대기할 수 있다(`JdbcDurableAiBudgetRepository.kt:123–140`). 대기 이후 `DurableAiBudgetService.kt:93`은 execution deadline만 재검사하므로, quote.validUntil이 먼저 지나고 execution은 여전히 유효하면 신규 RESERVED 행과 full hold가 commit된다. 뒤의 claim은 만료 quote를 거부하므로 실제 HTTP 권한은 생기지 않지만, 이미 만료한 quote로 쓸 수 없는 영속 hold를 만들어 방 용량을 소모한다. DB01의 expired quote deny 및 quote matching/expiry 계약에 어긋난다.

최소 개선: 최초 신규 reservation insert 직전 fresh clock으로 새로 생성할 quote들의 validUntil을 재검사한다. 기존 reservation 조회/replay는 새 권한이 아니므로 만료했다고 원래 hold를 환급하거나 재가격/재생성하지 않는다. 독립 controlled Clock + 실제 shared profile row/unique 대기를 사용해 execution은 유효하고 quote만 만료한 경우 `reserve=null`, 새 call0/hold0을 확인한다. 향후 claim 전송 직전 fence는 별도 유지한다.

### CORE-P2-03: 누적합 overflow에서 표현 가능한 실제 bill C가 유실됨

`DurableAiBudgetService.kt:231–244`는 `C` 자체 범위 초과와 `priorSettled+C` 합계 범위 초과를 같은 분기로 처리하면서 observation의 billAmount를 항상 NULL로 남긴다. 기존 독립 oracle의 aggregate overflow 사례에서 C=20은 NUMERIC38에 정확히 저장 가능하지만 알려진 bill amount를 버린다. accepted NUMERIC38 설계의 “표현 가능한 실제 금액은 observation에 그대로 기록하고 aggregate를 축소하지 않는다”와 일치하지 않는다. 원래 settled/full hold 유지·FROZEN·false 처리는 올바르다.

최소 개선: 이 분기의 observation `billAmount`만 `bill.amount.takeIf(::amount)`로 보존한다. C 자체가 `10^38`이면 여전히 NULL/미정산이어야 한다. aggregate overflow는 기존 settled와 unresolved hold를 유지하고 FROZEN으로 기록하며 SETTLED/complete 성공으로 보고하지 않는다. 독립 PG oracle은 C=20인 aggregate overflow의 저장된 bill_amount=20과 C=`10^38`의 bill_amount=NULL을 각각 확인한다. 새로운 통화 정책, 보존 연장 또는 raw evidence 저장을 추가하지 않는다.

## 확인한 올바른 core 동작

- reserve는 같은 room budget lock 안에서 strict `settled+held+newQ < cap`, BigInteger 금액 범위, 동일 physical identity/execution/payload/profile 검증, count/gen child hold의 atomic 삽입을 수행한다. insert 실패는 transaction rollback 대상이며 이미 별도로 commit된 기존 logical admission 환급 경로를 추가하지 않았다.
- 예약 replay는 원래 row/quote/hold를 재사용하고 새 dispatch token을 발급하지 않는다. claim은 현재 registry quote와 저장 quote의 정확한 일치 및 fresh deadline을 확인하고 RESERVED CAS 한 번만 허용한다. CAS 직후 만료되면 UNKNOWN/fullhold이며 권한을 반환하지 않는다.
- usageComplete/알려진 token/성공 응답은 금액으로 간주되지 않는다. authentic late usage/bill은 기존 nonce와 room의 원래 reservation에만 붙는다. 동시 callback은 room→budget→reservation lock으로 직렬화한다. 같은 event replay는 idempotent이고 충돌은 false/FROZEN으로 거부하며 기존 proof를 덮어쓰지 않는다.
- 검증된 완전 bill만 hold를 정산한다. 표현 가능한 quote 초과 C는 그대로 기록하고 room을 동결한다. 단일 bill/합계 NUMERIC38 overflow는 SQL 쓰기 전에 탐지하여 READY rollback이나 clamp 없이 FROZEN/UNKNOWN/fullhold를 남긴다(CORE-P2-03의 알려진 C 보존 보완은 필요).
- V17은 기존 room을 HISTORICAL_UNKNOWN으로 이관하고 새 room은 같은 생성 transaction의 trigger로 UNCONFIGURED를 만든다. READY/실제 price/cap/운영 활성화 경로가 없다. room이 없어졌거나 budget row가 사라지면 lazy 재생성하지 않는다.
- global profile에는 structural profile/policy/unit 및 그 구조 hash만 남는다. payload/source/context/quote/evidence/금액/execution 지문은 room-owned reservation이며 room→budget→reservation→observation cascade다. attempt/submission RESTRICT FK를 만들지 않아 기존 30일 삭제 순서를 막지 않는다. 실제 RoomDataRetentionRepository를 거친 삭제 및 late observe/settle/reserve no-resurrection 양성/음성이 집중 GREEN에 포함된다. 살아 있는 방의 close/timeout은 hold를 지우지 않는다.
- reviewed core/JDBC/default registry에는 HTTP/SDK 호출이 없다. 현재 생산 `DenyAiBudgetAuthorityRegistry`는 두 연산 모두 즉시 null을 반환하는 순수 구현이다. 다만 registry 호출 자체는 예산 transaction 안에 있으므로 향후 대체 registry도 local/preloaded/offline이어야 한다. 이 검토는 아직 없는 live authority가 이를 준수한다고 증명하지 않는다.

## 수용 범위와 다음 시작점

실제 `JdbcAiBudgetExecutionFence.lockAndValidate`는 현재 TODO이고 core tests는 synthetic fence를 사용한다. 따라서 ownership/binding/revision/generation/closed-room kind guard의 실제 DB 결합은 이 52 GREEN이나 이 source 리뷰로 승인되지 않는다. 부모가 별도로 준비 중인 real-fence 29 RED 및 이후 구현/검증에 연결한다.

provider snapshot/physical network one-shot/5종 전송 coverage/SDK follow-up 차단/OFF legacy/ON bounded4+1/nonretryable CONFIGURATION/HTTP 동안 DB lock 해제는 아직 이번 source 리뷰 대상이 아니다. provider body 구현과 독립 RED가 준비된 뒤 검토한다. 현재 authority는 항상 DENY이고 synthetic READY만 있으므로 실제 가격·세금·billed cap·USD1 실행·제품 10원 달성을 주장할 수 없다.

다음 순서는 위 P2의 독립 supplemental RED→최소 수정→GREEN/FI와 source freeze, 실제 fence 및 provider 통합 독립 리뷰, 변경 없는 mandatory full guard이다. 그 이후에만 commit/push/표준 Draft/exact HEAD CI의 로컬 게시 가능 판정을 내린다. 미확정 실제 금액·참여·마감·금융 보존 정책은 여기서 새로 확정하지 않았다.

## 검토 snapshot 지문 및 인계

- `src/main/kotlin/com/meetme/server/shared/application/service/DurableAiBudgetService.kt`: `9fa564ae13e95b0cef8ed1d0c486aec0c621133d7958eda8c79bc3f574e18cf8`
- `src/main/kotlin/com/meetme/server/shared/application/port/output/DurableAiBudgetRepository.kt`: `39e5847d7b96989281ca1a3bc430d043a607185ef16b655a1610ca73fea83097`
- `src/main/kotlin/com/meetme/server/shared/adapter/output/persistence/JdbcDurableAiBudgetRepository.kt`: `2584914db2f47bf72f5de91f7dc9272b9e146a4cf7ff64384dbeefc6b727ec63`
- `src/main/kotlin/com/meetme/server/shared/application/port/output/DurableAiBudgetPort.kt`: `ec12c026333a5640002ad42b060d21fe67bd56f34f07f8628129db4944d6d965`
- `src/main/resources/db/migration/V17__add_durable_ai_room_budgets.sql`: `912b8f227e6ea7c20fc05cf96d705982fb9737c5051c7c07e771494df482834b`
- `src/main/kotlin/com/meetme/server/shared/adapter/output/budget/DenyAiBudgetAuthorityRegistry.kt`: `e81dfef62774a112c6075232bcd44850a75505bee6565be52d68f7c3a0b0f5c2`

부모는 CORE-P2-02/03을 기존 계약의 최소 보완으로 수용했고, 독립 작성자에게 기존 44 oracle은 보존한 채 supplemental tests를 요청했다. 알려진 CORE-P2-01과 합쳐 현재 3건은 RED 이전 미수정 상태로 유지한다. quote 만료 보완의 수용점은 profile 마지막 잠금 대기 뒤 최초 financial write 전 fresh check이며, DB commit과 remote send 사이의 시간을 물리적으로 원자화한다는 약속을 추가하지 않는다. 원본 source/테스트는 이 검토에서 수정하지 않았다. 다음 검토는 별도 요청되는 수정 후 GREEN/FI snapshot부터 재개한다.
