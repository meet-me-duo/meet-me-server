# #123 durable AI budget — 독립 설계 리뷰

> **최종 설계 freeze: PASS. 미해결 P1/P2 없음. 수정 V17 schema의 방 소유권·30일 cascade·structural-only global profile과 NUMERIC38의 FROZEN/UNKNOWN overflow 처리도 수용했다.** 아래 구현 acceptance는 이후 RED/GREEN/FI에서 확인할 항목이며 설계 동결 보류 조건이 아니다. 앞선 발견·수정 요청은 역사 이력으로 보존했고, 최신 root 계약이 우선한다. 구현/source·실제 비용 상한·운영 활성화 승인으로 확장하지 않는다.

2026-10-18 UTC. 기준 서버 PR122 HEAD `e83d5280c7c236c99d64165893632920882527fe`. source/tests/SQL/git 변경0, Gradle 실행 및 실제 공급자/운영/비밀값 접근0. 파일과 Git 상태는 read-only로 대조했고 이 `/tmp` 리뷰만 작성했다. parent가 확인한 정확 HEAD CI39093790134 및 Lifecycle39094821627 성공을 다음 설계의 기반으로 사용하며 이 reviewer가 새 CI를 실행했다고 주장하지 않는다.

검토: `/tmp/issue123-durable-budget-implementation-design.md`, `issue123-durable-budget-test-spec.md`, `issue123-all-ai-dispatch-integration-design.md`, 추가 `issue123-all-ai-dispatch-test-spec.md`, 승인 `docs/ISSUE_107_SHARED_AI_BUDGET_DESIGN.md`, 관련 프로젝트 지침 및 실제 retention/claim/provider port 코드. 후속 `/tmp/issue123-budget-public-contract.kt`·note, `/tmp/issue123-dispatch-public-contract.kt` 및 새 worktree `/workspace/issue123-durable-budget/server`의 `docs/ISSUE_123_DURABLE_AI_BUDGET_CONTRACT.md`도 대조했다.

## 최종 판정

**설계 freeze 판정: PASS. 현재 미해결 P1/P2 없음.** 최소 budget 공개 port·default-DENY registry·synthetic epoch/authority와 전체5종 physical gate 설계는 독립 실제PG/loopback TDD를 시작하기에 충분하다. 이 판정은 구현/source 승인·테스트 통과·실제 과금 상한·운영 활성화 승인이 아니다. 두 문서 모순 및 claim 원자성 용어는 parent의 새 계약에서 보정됐다. 아래 조건은 기존 계약을 구현·검증하는 acceptance이며 새로운 금액·참여·마감·장기 보존 정책을 정할 이유가 아니다.

#124는 이미 PR122의 phase1 기본 OFF adapter/bridge 범위이며 durable persistence가 아니다. 현재 phase2는 broad #123 후속이고 새 PR을 `Closes #124`로 만들면 안 된다. 최초 인계의 narrow 표기를 durable 하위 범위로 오독한 이 리뷰의 문구는 여기서 정정했다.

parent의 후속 minimum(기존30일 room 삭제+cascade, 살아 있는 room의 UNKNOWN 유지, OFF 미계상 이력 UNKNOWN, 생산 금액/quote/활성화 경로0)을 검토했고 기존 정책과 양립한다고 회신했다. 새 root 계약의 DB06/20/21 보정도 확인했다. 이 minimum과 budget public signature를 oracle에 반영하면 합성/격리 PostgreSQL TDD를 시작하는 데 새 사용자 정책 결정은 필요하지 않다. 실제 사용 가능 profile·금액·가격/cap이 없으므로 새 ON 경로는 모두 DENY다.

## budget 공개 port 대조

이 절의 temporal/phase·OFF 초안 문구 보정 요청은 초기 검토 이력이며, 현재 root 계약과 pre-RED oracle 보정으로 반영 완료했다.

`DurableAiBudgetPort.reserve/claim/observe/settle/snapshot`의 분리는 stage-level prototype과 물리 권한을 구분한다. quote/bill authority는 별도 registry이고 생산 resolver는 항상 null, 활성화/UNKNOWN 해제 controller는 없다. synthetic epoch/policy/quote fixture로만 양성 금액 계약을 검증하는 최소 범위는 적절하다. profile/policy/payload/evidence hash의 신뢰 registry/불변 snapshot 결속이면 기술적 immutable binding을 표현할 수 있으며, 현재 실제 가격 formula·billing authority를 새로 구현할 필요는 없다. 해시나 `Verified`라는 타입명 자체가 실제 청구 근거라는 주장은 금지한다.

공개 DTO만 복사해 실제 adapter로 permit을 주입하는 경로가 없어야 한다. adapter가 실제 준비된 body에서 spec을 만들고 스스로 claim CAS를 수행하며, replay에는 새 permit을 반환하지 않는 계약이 적절하다. provider 제안의 opaque prepared group/exchange·application-call guard·network-exchange one-shot 조합은 same-Call followup과 SDK second Call을 구분해 막을 수 있는 경계다.

budget port 동결 시 두 temporal/phase 문구를 명시하면 충분하다. `lockAndValidate(request, at)`에 lock 전 낡은 at을 전달하고 그대로 검증해서는 안 된다. 마지막 budget lock 뒤 effective deadline을 fresh clock으로 재검사하며 auth binding·room·claim 중 더 이른 만료가 있으면 포함하거나 별도로 fresh temporal 검사를 해야 한다. 잠금으로 보호되는 ownership/version과 시간이 지남에 따라 만료되는 자격을 구분한다. 또한 DB10의 closed-room 거부는 precheck에 적용하며, 정상 final은 CLOSED 방의 active STRUCTURING run을 대상으로 기존 `ActiveRunLock`/invocation fence를 유지한다. generic room-is-open 조건으로 final을 차단하지 않는다.

DB24의 OFF에서 HTTP0이라는 초안도 phase별로 보정해야 한다. 새 budget OFF는 legacy final을 차단하지 않고 기존 precheck OFF/default-DENY를 그대로 보존한다. 새 budget ON에서는 금액/quote/authority 없으면 모든 해당 물리 경로가 DENY다. AD01과 일치하는 trace equivalence/양성 final 대조를 oracle로 삼는다.

## 과거 발견 P2와 해결된 계약 보정 이력

이하 당시의 모순·동결 요청을 삭제하지 않고 남긴다. 현재는 root minimum과 `ISSUE_123_DURABLE_AI_BUDGET_CONTRACT.md` 및 pre-RED oracle 보정으로 해결됐으며 미해결 blocker가 아니다.

### P2-1 해결 이력: 새 lineage와 OFF 기간 미계상 비용

구현안은 room 생성 transaction에서만 fresh lineage를 만들고 lazy zero bootstrap을 금지하지만 DB20에는 `concurrent lazy bootstrap`가 있다. 둘 중 공개 계약을 하나로 고정해야 한다. 최소안은 새 room 생성과 budget provenance 초기화를 같은 transaction에 두고, 기존 room의 임의 lazy zero 생성을 금지하는 것이다. 멱등 재조회는 이미 검증된 같은 lineage를 반환할 뿐 새 금액/권한을 주지 않는다.

더 중요하게, V17 이후 생성 사실만으로 과거 비용0이 증명되지는 않는다. 새 flag OFF는 기존 final/direct-generator 호출을 보존하므로 OFF→ON, ON→OFF→ON 사이에 미계상 호출이 있을 수 있다. 어떤 epoch가 완전히 관측됐는지 증거가 없으면 HISTORICAL_UNKNOWN/DENY로 격리해야 한다. 운영 활성화/이력 재구성 경로를 현재 제공하지 않고 synthetic coverage epoch만 격리 fixture로 주는 parent minimum은 안전하다. 테스트는 migration 시점이나 empty attempt 목록을 fresh 증거로 대체하지 않아야 한다.

### P2-2 해결 이력: UNKNOWN 보존과 실제30일 삭제의 충돌

DB21의 `cleanup must not erase UNKNOWN holds`를 무조건 적용하면 현재 room 삭제를 막거나 새 금융 기록의 보존 기간을 연장하게 된다. 살아 있는 room의 close/timeout/lease 만료·회복은 hold를 지우지 않는다는 계약과, 승인된 retention이 실제 room을 삭제하는 경우를 구분해야 한다.

현재 PRD FR-109D 및 `DataRetentionConfiguration`은 기존30일 기준을 유지하며, `RoomDataRetentionRepository.deleteExpiredRooms`는 CLOSED의 closed_at/COLLECTING의 created_at cutoff를 사용해 room을 잠그고 같은 transaction에서 종속 데이터를 삭제한다. **동일 삭제 시점에 새 room-owned budget/reservation/usage/금융 근거도 cascade 삭제하는 안이 가장 작은 정책 유지다.** 이를 위해 새 보존 기간·무기한 tombstone·UNKNOWN이라는 이유의 room 삭제 유예를 도입할 필요는 없다. 더 긴 청구 증거 보존을 원한다면 별도 정책 결정 대상이다.

삭제 뒤 late observation은 NOT_FOUND/no-op 중 고정된 결과로 끝내고 budget/room 재생성·dispatch·product publish0을 검증해야 한다. room 생존 중 late observation은 기존 원장만 갱신하며 현재 결과는 건드리지 않는다. request/source/context/room-specific hash를 shared global profile 표로 옮겨 retention을 우회해서도 안 된다.

새 FK/append-only 규칙이 retention 순서를 막지 않아야 한다. 현재 cleanup은 `coordination_attempts`와 submission versions 등을 `meeting_rooms`보다 먼저 삭제한다. 단지 최상위 room FK에 CASCADE만 붙이고 child execution FK는 RESTRICT로 두면 중간 삭제에서 실패할 수 있다. room-owned ledger를 관련 execution 삭제 전에 같은 transaction에서 제거하거나 retention과 양립하는 FK 순서를 설계하고, room2 데이터 보존·rollback·UNKNOWN 포함 실제 PG cleanup을 검증해야 한다. append-only는 생존 중 임의 재작성 금지이며 승인된 전체 room 삭제를 막는 DELETE trigger가 되어서는 안 된다.

### 해결 이력: 기존 logical claim과 새 물리 권한의 원자성

DB06의 `claim/budget writes rollback`과 integration §6의 `이미 커밋된 logical admission을 budget deny로 환급하지 않는다`는 claim의 범위가 다르면 양립한다. 기존6회/4+1 logical admission과 새 reservation/hold/dispatch CAS를 별도 용어로 고정해야 한다.

최소안은 이미 커밋된 기존 logical claim/횟수는 보존하고, 새 physical reservation·hold·권한 CAS의 해당 transaction만 완전히 rollback하도록 하는 것이다. 이 경우 실패한 reservation은 실행 가능한 권한이 아니며 기존 claim을 가졌다는 사실로 HTTP를 보낼 수 없다. 비용 거부/DB 오류는 내부 nonretryable 종결로 전달하고 추가 primary/fallback을 만들지 않는다. 소유권/현재 version/마감은 기존 방식으로 다시 검사한다. 더 큰 claim+budget 단일 transaction을 택할 수도 있지만 과거 큐 admission까지 되돌리는 oracle이나 부분 commit을 허용해서는 안 된다. 정확한 public signature와 호출자가 어떤 transaction을 소유하는지 RED 전에 합의해야 한다.

## dispatch 동결값과 구현 acceptance 조건

root가 확정한 최종 계약이 미갱신 제안 주석보다 우선한다. FINAL generation은 `invocation.runVersion`이며 제안의 generation0 두 문구는 oracle로 사용하지 않는다. 실제 denial은 기존 `CONFIGURATION`의 nonretryable mapping으로 종결하고 새 공개 enum/UX를 추가하지 않는다. final SDK snapshot ceiling은32MiB의 내부 구조 자원 상한이다. 원래500/10000·final32768/256KiB·시간 계약과 별개이며 입력 token/청구량 hard cap으로 주장하지 않는다. default OFF와 선택적 legacy constructor seam은 그대로이며 실제 managed Spring 5종은 같은 gate를 주입한다.

root가 추가 동결한 ON 범위도 기존 계약과 일치한다. ON은 영속 invocation repository와 fallback bean이 있는 기존 production bounded4+1 구성을 지원한다. 둘 중 누락된 과거 test/unbounded constructor seam은 I/O 전에 nonretryable CONFIGURATION으로 거부하고, OFF에서는 기존 nullable/unbounded seam을 그대로 보존한다. 이는 지원하지 않는 내부 구성의 fail-closed이지 새로운 참여/마감 정책이나 사용자 승인 항목이 아니다. 실제 `lunaParser`는 조건 없는 Component이며 key가 비어 있어도 bean은 존재하고, key 검사는 fallback parse 진입 시 수행된다. 따라서 **fallback bean 누락과 OpenAI key 공백을 혼동해 정상 Gemini primary를 미리 차단하면 안 된다.** primary가 정상 완료하면 미사용 fallback의 key/quote를 새 선행 조건으로 삼지 않는다. ON 양성 bounded primary, missing-bean/repository 음성, OFF legacy trace를 각각 독립 검증하면 된다.

다음은 독립 integration oracle과 최종 source 리뷰에서 확인할 조건이다.

1. **실제로 보낸 body가 불변 snapshot과 같음:** lazy SDK도 실제 serializer의 whole JSON을 bounded capture한 뒤 그 immutable bytes를 송신해야 한다. hash를 읽은 뒤 mutable/nondeterministic `writeTo`를 다시 평가해 다른 body를 보내는 경로는 허용하지 않는다. 변경 시도는 quote conflict/HTTP0 또는 정확히 고정 bytes 송신으로 끝나야 한다. same-Call followup과 SDK second Call은 각각 기존 permit 재사용0이다. reject는 연결 전이며 network interceptor만의 관측을 zero-network 증거로 삼지 않는다.
2. **mandatory observation 실패를 성공으로 숨기지 않음:** `GatedAiHttpExchange.observe=false`/예외는 full UNKNOWN hold를 유지하고 현재 제품 성공/CLEAR로 전환하지 않는다. 이는 기존 TS08/DB11의 계약이며 새 제출 정책이 아니다. stale/deleted room은 기존 product fence를 유지하고 late accounting이 room을 재생성하지 않는다.
3. **실제 모든 managed adapter의 ON 경계:** classifier/count/gen/final Gemini/final Luna 각각 실제 wire 양성·음성, direct runtime/SDK/nullable context/factory 누락을 검증한다. 생산 코드에서 새 manual unguarded constructor를 만드는 방식으로 global ON을 빠져나가지 않는다. 기존 test/OFF seam을 삭제하거나 모든 기존 테스트를 새 금융정책에 맞춰 다시 쓰는 방식도 필요하지 않다.
4. **유효시각과 phase fence:** 마지막 budget lock wait 뒤 effective expiry/현재 binding을 fresh하게 검사한다. precheck CLOSED 차단과 정상 CLOSED/STRUCTURING final 허용을 각각 양성·음성으로 검증한다. after-CAS/송신 전의 불가피한 외부 간격에서는 zero-bill/exactly-once를 주장하지 않고 UNKNOWN/기존 게시 fence를 유지한다.

예산 oracle의 DB06/10/20/21/24 보정은 RED 전에 기록됐다. `.tdd/history/issue123-budget-oracle-corrections/manifest.json`의 original SHA `9add11fc…`, revised SHA `f4e0d24f…`를 보관 파일과 직접 비교해 일치함을 확인했다. 이 보정은 사후 테스트 약화가 아니다. 예전 초안 자체는 역사 기록으로 보존한다.

## parent가 자율적으로 고정 가능한 상세 결정

- 정확 class/table/column 이름, bounded exact integer 저장형과 overflow fail-closed, 합성 `B=100` 무차원 fixture. 기술 저장 상한은 실제 KRW/USD quantum·FX·가격·금액 설정과 구분한다. `spent + unresolved holds + Q < B`의 strict 비교 및 proven zero와 unknown 구분은 유지한다.
- 물리 identity `(room, logical execution, generation/runVersion, phase, ordinal)`와 immutable payload/context/profile/quote 결속; count/gen 별도 child leg 또는 parent 선예약 방식 중 이중 차감 없는 내부 구현. 최종 Gemini에는 새 count 요청을 넣지 않는다.
- pre-existing stage port의 ON translation/replacement, 기본 OFF switch의 이름, 누락 typed context의 ON 차단 및 OFF legacy 동작 보존. Phase1 fake `verified`/`dispatchGranted`를 생산 금융 권한으로 승격하지 않는다.
- DB room→기존 run/check/admission→budget 잠금 순서와 fresh clock 재검사. 외부 HTTP 동안 transaction/lock0. precheck shared15초 및 final60/Gemini15/Luna27/completion3초를 재지급하지 않는다.
- 실제 전송 전 CAS와 process-local 단일 사용 capability를 결속하는 기술 구현. 단순 상태 조회나 data class 복사로 권한을 복원할 수 없어야 한다. DISPATCH_INTENT 이후 프로세스 재시작은 UNKNOWN이며 공급자 exactly-once가 아니다.
- SDK 직렬화와 실제 body를 한 번 고정해 quote와 비교하는 seam, `.execute`/연결 전 거부와 실제 write 경계의 별도 검증. same-Call followup과 SDK second Call 각각 fresh 권한 없이 차단한다. 새 endpoint/profile/개인 cap을 final에 복사하지 않는다.
- 금융 증거가 미완료이면 complete usage도 observation-only/full hold 유지. 최초 구현의 complete physical-call proof만 settle, partial component는 별도 기록, conflict/over-quote는 보수 격리하는 기술 계약. `C>Q`를 Q/B로 자르지 않고 기록하며 추가 admission을 차단한다.
- typed safe usage/failure DTO, 고정 nonretryable budget reason, late/duplicate evidence의 멱등 규칙, 고카디널리티/원문/키 없는 지표. 실제 정산 authority·운영 조정 기능을 임의 추가하지 않는다.
- V17은 원격 migration inventory 재확인 뒤 예약하고 V1..V16 bytes와 기존 historical catalog를 보존한다. migration17과 auth guard catalog17은 서로 다른 개수다. 기존 CI/test 기대를 낮추지 않는다.

## pre-DDL 스키마 후속 재검토

parent가 발견한 global profile 표의 payload/quote hash 잔존 P2를 반영한 `/tmp/issue123-budget-schema-contract.md`를 다시 읽었다. **수정 스키마의 privacy/삭제 경계는 PASS이고 새 미해결 P1/P2는 없다.** 실제 DDL/GREEN을 확인한 판정은 아니며 아래 NUMERIC38 실패 처리가 구현 acceptance다.

- `ai_budget_profiles`는 canonical structural `profile_fingerprint`, `policy_fingerprint`, `unit_code`, `unit_scale`과 이 네 값에서 만든 structural PK만 남긴다. 이 canonical profile/policy에는 room·execution·epoch·source/context/원문 payload에서 파생된 값이 들어가지 않아야 한다. 실제 quote 권한은 여전히 default-DENY trusted registry이며 global row 존재로 허가하지 않는다.
- per-request `quote_fingerprint`, `whole_payload_fingerprint`, source/context hash, evidence hash, maximum amount·유효시각·execution/owner/dispatch ID는 모두 `ai_call_reservations`의 room-owned snapshot에만 둔다. 따라서 raw text가 없다는 이유만으로 파생 payload hash를 영구 global metadata라고 취급하는 오류를 제거했다. 관측/청구 evidence도 child observation에만 남긴다.
- `meeting_rooms → ai_room_budgets → ai_call_reservations → ai_call_observations`는 CASCADE이며 attempt/submission에 삭제를 막는 FK를 추가하지 않는다. 현재 cleanup이 attempts/submission을 먼저 지워도 마지막 room 삭제가 새 원장 전체를 지울 수 있다. 실제 PG에서 UNKNOWN/SETTLED/USAGE/BILL 모두 제거·room2 보존·실패 rollback·late record0 재생성을 확인해야 한다. append-only 보호가 retention cascade를 막아서는 안 된다.
- V17은 기존 room을 HISTORICAL_UNKNOWN으로 먼저 seed한 뒤 `meeting_rooms AFTER INSERT` trigger로 새 room의 UNCONFIGURED row를 같은 transaction에 만든다. 생성 경로를 놓친 lazy bootstrap 없이 direct SQL fixture에도 동일하게 적용하는 기술적 최소안이다. FK/trigger는 READY나 known-zero 권한을 만들지 않는다. 동일 room 생성 시 application이 중복 budget을 별도 삽입하지 않도록 ownership을 통일한다.

### NUMERIC38의 의미와 실제 bill 범위 초과

`NUMERIC(38,0)`은 explicit unit을 쓰는 내부 저장 자원 상한이며 실제 통화·금액·FX를 고르는 정책이 아니다. 현재 실제 authority/가격 설정이 없어 운영 quote는 항상 DENY다. quote/cap/hold 범위 밖 값은 dispatch 전에 거부한다.

실제 trusted bill 자체가38자리를 넘거나, bill은 개별 범위 안이지만 `settled + C`가 저장 범위를 넘는 경우에는 **SQL bind/update overflow를 일으키기 전에** BigInteger로 판정해야 한다. 같은 정상 transaction으로 room을 FROZEN으로 만들고, 해당 full hold를 유지하며, opaque evidence/event fingerprint와 고정 ACCOUNTING/out-of-range 사유를 기록한다. 저장 불가능한 bill_amount는 NULL/미정산으로 두고0·최대38자리값으로 바꾸거나 SETTLED/complete-bill 성공으로 보고하지 않는다. 원래 settled/quote/evidence는 보존한다. 표현 가능한 실제 금액은 observation에 그대로 기록하고 aggregate를 축소하지 않는다.

SQL overflow 예외로 전체 transaction이 rollback되어 room이 READY인 채 남거나, event insert 실패로 freeze까지 취소되는 구현은 이 acceptance를 충족하지 않는다. 독립 oracle에는 최소 actualC=`10^38`과 개별 C는38자리지만 aggregate 합이 범위를 넘는 사례를 포함해야 한다. duplicate/out-of-range evidence는 새 dispatch나 hold 해제를 만들지 않아야 한다. 이 상태는 실제 합산 금액을 완전히 표현했다는 주장이 아니라 명시적인 FROZEN/UNKNOWN이며 후속 reconciliation·장기 보존 권한을 새로 만들지 않는다. larger/unbounded numeric으로 넓히는 대안도 가능하지만, parent가 고정한 NUMERIC38+정직한 동결/미정산 처리는 현재 최소 범위에 충분하다.

## 실제 정책/활성화 차단 사항

실제 B·통화/unit/환율·세금·최종 분석 몫 보호, 검증된 공급자 billed cap/가격·증거 승인 경로, historical 비용을 해소할 근거와 운영 권한, 소진/UNKNOWN 시 참여/재시도/공개 UX, 기존30일을 넘어선 금융 보존은 이 리뷰로 확정하지 않는다. 부재는 새 ON dispatch DENY와 실제 최대비용 주장 금지로 표현한다. 기본 OFF에서 기존 서비스가 계상 없이 보존된다는 사실도 숨기지 않는다. 합성 quote/기존 USD1 평가 승인은 제품10원 요구가 달성됐다는 증거가 아니다.

## 다음 시작점

root 동결값과 위 acceptance를 독립 작성자에게 전달 → compile-valid skeleton/최소 실제PG·loopback assertion RED → 역할 분리 구현/GREEN·FI. 현재 production authority는 quote/bill null이며 테스트 fixture 외 READY/실제 profile을 공급하지 않는다. source/test/migration 편집·Gradle·게시·운영 작업은 이 reviewer가 수행하지 않았다. 최종 implementation/source 승인과 PR/exact CI 판정은 이후 실제 RED/GREEN/FI/전체 guard 검토 단계다.
