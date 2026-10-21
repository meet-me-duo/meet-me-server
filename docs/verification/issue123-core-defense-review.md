# Issue123 core defense 독립 source 재검토

2026-10-18 UTC. `/workspace/issue123-durable-budget/server`. **기존 CORE-P2-01/02/03은 현재 source에서 모두 충족하여 source 발견 CLOSED로 판정한다.** 실행 검증의 최종 닫힘은 진행 중인64 GREEN과 이후 FI/unchanged guard 근거를 기다린다. 이 문서는 64 성공이나 전체 기능/게시 PASS를 주장하지 않는다. 최초 발견은 `/tmp/issue123-core-source-review.md`에 그대로 보존한다.

## 세 발견의 최소 보완

| 발견 | 현재 source 근거 | 판정 |
| --- | --- | --- |
| CORE-P2-01 다른 방 row 선잠금 | `DurableAiBudgetService.kt:127–129`가 잠금 없는 hint를 조회하고 exact execution(따라서 room 포함)과 call을 비교한 뒤만 reservation FOR UPDATE를 호출한다. 이후 기존 execution/call/epoch/RESERVED 검사, registry quote 재확인, fresh deadline/CAS를 유지한다. `JdbcDurableAiBudgetRepository.findCall(lock=false)`는 일반 SELECT라 foreign row lock을 기다리지 않는다. | source CLOSED. 자신의 방 budget lock 뒤 다른 방 reservation row를 잠그는 경로가 제거됐다. |
| CORE-P2-02 profile wait 후 quote 만료 | `DurableAiBudgetService.kt:93–98`가 모든 profile insert/wait 뒤 최초 reservation/financial write 전에 한 번 얻은 fresh `beforeInsert`로 execution deadline과 **신규 quote만** validUntil을 확인한다. 기존 reservation replay의 quote/hold를 재가격하거나 만료 환급하지 않는다. | source CLOSED. 이미 만료한 새 quote로 쓸 수 없는 hold를 만드는 경로가 제거됐다. |
| CORE-P2-03 aggregate overflow의 known C 유실 | `DurableAiBudgetService.kt:239–253`의 overflow event에 `billAmount=bill.amount.takeIf(::amount)`가 추가됐다. C=20이면 그대로 observation에 저장하고 합계는 기존 settled/full hold/FROZEN/UNKNOWN/false로 유지한다. C 자체가10^38이면 여전히 NULL이며 clamp·SETTLED·성공이 아니다. | source CLOSED. 알려진 표현 가능 C와 표현 불가 aggregate를 구분한다. |

수정 diff는 위 두 service 보완 receipt와 current를 대조했다. 새 금액/통화/참여/마감/보존 정책, 새 취소/환급, 자동 retry/claim을 추가하지 않는다. 최초 financial write 전 검사와 실제 remote send/DB commit의 물리적 원자성을 혼동하지 않는다.

## 추가 default authority 구성 보완

`DenyAiBudgetAuthorityRegistry` 자신에 Component+ConditionalOnMissingBean을 붙이는 구성을 제거하고, 별도 `AiBudgetAuthorityConfiguration`의 `@Bean @ConditionalOnMissingBean(AiBudgetAuthorityRegistryPort::class)`으로 기본 DENY를 만든다. registry 자체는 quote/completeBill 모두 즉시 null인 순수 구현을 유지한다. 실제 price/profile/activation seed가 아니다.

`DurableAiBudgetPort.kt`의 authority port에는 budget transaction 안의 resolver가 offline/preloaded이며 HTTP/SDK/live count를 수행하면 안 된다는 계약을 명시했다. 현재 default 구현도 이 조건을 만족한다. 이 source 보완은 전체 Spring app 또는 미래 live authority의 동작까지 검증했다는 뜻이 아니다.

## 독립 RED 근거와 보존

- `.tdd/red/issue123-budget-accounting-defense.json`은 새로운 accounting 파일의 두 요구 실패를 명시한다. 보관 sealed XML을 직접 읽었다: tests3/failures2/errors0/skipped0. quote wait는 execution이 아직 유효한데 null 대신 reservation을 반환하는 assertion, known C20은 bill_amount NULL이라는 assertion으로 실패했다. 개별10^38 bill NULL/no-clipping control은 PASS였다. 실제 PG global profile ACCESS EXCLUSIVE lock을 확인한 뒤 clock을 quote expiry까지만 이동시켜 source의 정확한 누락을 분리했다. 전체 동시 실행의 다른 real-fence DI setup 실패는 이 두 RED로 세지 않는다.
- `.tdd/red/issue123-budget-foreign-scope.json` 및 raw `issue123-budget-foreign-scope.log`의 최신 보완 oracle은 긍정 claim용 synthetic authority를 명시한 뒤 실제 independent PG connection으로 foreign row를 잠근다. own-room 요청이 그 잠금이 유지되는 동안 null로 끝나야 하는 `assertDoesNotThrow(pending.get(...))`가 TimeoutException을 감싼 AssertionFailedError로 실패했다. 이는 의도한 lock-order 누락이다. peer lock 해제 후 기존 소유자의 정상 claim 양성을 확인한다.
- 초기 sealed foreign XML의 실패는 positive control용 authority 부재로 null이 나온 것이므로 유효 foreign-lock RED로 세지 않는다. 그 이력과 최신 보정 oracle/log를 구분한다. 최신 source/test SHA는 아래에 기록했다.
- `.tdd/red/issue123-budget-default-authority.json`와 같은64 실행 raw log는 production budget adapter ComponentScan에서 default registry bean 부재를 확인한 assertion RED다. 새 oracle은 startupFailure0 및 registry 정확히1개/타입DENY를 요구한다. 실제 가격 부재는 required port 부재로 application을 깨는 이유가 아니며 DENY bean으로 표현한다.
- 최초52의 test source6개 SHA를 다시 비교해 모두 그대로임을 확인했다. 신규 독립 tests는 기존44 money/8 initial oracle을 덮어쓰지 않는다. 현재64 실행은 부모 인계 시 진행 중이므로 GREEN 수치·오류/skip0을 추정하지 않는다.

## 정확 검토 snapshot

Source SHA256:

- `DurableAiBudgetService.kt`: `9c2fb6df1d10d010e2644f11ba1476c30f599174dc1f534519d3099448fcc246`
- `AiBudgetAuthorityConfiguration.kt`: `77b8f95a3353daa96537f68648b6da33d8639c47d27506b82da6a8a0dd7a37f8`
- `DenyAiBudgetAuthorityRegistry.kt`: `750669862cb6099d5cbca3f386c581950d20338625649cd2b643cf8af6011074`
- `DurableAiBudgetPort.kt`: `1dad629ddba6d9920c3096c1962e1df46383a22833494e872ef066f7e8d1a0f6`

New independent test SHA256(current와 각 RED metadata 일치):

- `Issue123BudgetAccountingDefensePostgresTest.kt`: `d20c75055d23af5f2d39f824a859fe6be19b45029d0241fbce6b8d92fe44c1a0`
- `Issue123BudgetForeignScopePostgresTest.kt`: `4af665c7addc2f69a8a901d95ba18bd5f1f01c3cf6b2a8323859ddd3e64352f3`
- `Issue123BudgetCoveragePostgresTest.kt`(foreign RED metadata의 보조 범위, 이번 기능 검토는 하지 않음): `48f5cc01843568a7abb41fda97fe32aa3832a7a6f9e78e3516e930e947da8a29`
- `Issue123DefaultBudgetAuthorityCompositionTest.kt`: `17575e4e98a36e12c53114b2f02c618db41cde1e65e2a5ab47b27eae60f7afa5`

Evidence SHA256:

- accounting RED JSON: `11592d9374b66ac23979b47768a0cef6010e385c18bf780ed7e22a26ccf385e4`
- foreign RED JSON: `36683a918e750b795d790318dead25724b113a59d372513121c4eb68fd84e989`
- default authority RED JSON: `f2195b7e407339222793f33d9c084ee3e863bc87556870b8a3d53850ccf2eca3`
- sealed accounting XML: `fa28737edc025a4e411796b5418dd525d5ee53686c4ebf83f38d6440c4943563`
- accounting raw log: `b5a1ed9e5c295aea0d7a55db70a721380fd4b7e025fddbc57b87b8ed63352870`
- latest foreign/default authority raw log(동일64 실행): `78107b1a96d2165871ced46d910185feec0197467d67e5503ecc45d248775dc7`

## 남은 범위

64 GREEN receipt/정확 current SHA 확인 및 독립 FI/전체 unchanged mandatory guard는 다음 증거다. 실제 DB fence, managed composition/context와 worker/orchestration은 별도 진행 중이며 이번 세 source 발견의 닫힘으로 승인하지 않는다. actual billing authority/가격/세금/실제 cap·운영 활성화 미구성/default DENY, 실제 paid call0이라는 한계도 유지한다.

이 검토는 source/tests/git/branch/SQL 변경0, Gradle 실행0의 read-only 검토다.
