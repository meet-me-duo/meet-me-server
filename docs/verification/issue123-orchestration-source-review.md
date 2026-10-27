# Issue123 orchestration 독립 source 리뷰

2026-10-18 UTC. `/workspace/issue123-durable-budget/server`. **이번 신규 managed/mode/Worker/Processor source 범위에서 신규 P1/P2 없음, source 검토 PASS.** 실제 DB fence·PG9·whole-room1·FI·unchanged full guard는 별도 미확인으로 남기며 전체 기능/최종 게시 PASS로 확장하지 않는다. single Decisions smoke의 USD1 scope 확인 대기는 코드 검증과 독립이고 이 검토에서 실제 추론을 수행하지 않는다.

## 검토 및 검증 근거

receipt의 source6개와 지원 `AiDispatchModePort`를 frozen core/dispatch contract 및 AD/OR/TM/PV에 대조했다. `.tdd/history/issue123-dispatch-orchestration-focused-green/receipt.json`과 raw `.codex/tdd-evidence/issue123-orchestration-focused-green/xml/`9개를 직접 확인했다. 합계는 **98 tests / failures0 / errors0 / skipped0**이며 기존80 + managed13 + worker3 + legacy2다. source6/test11의 current SHA는 receipt와 모두 일치했다. receipt에 PG test source SHA가 있더라도 그 PG9가 이번 GREEN에서 실행됐다는 뜻은 아니다.

`.tdd/red/issue123-dispatch-integration.json`과 raw RED XML4개도 확인했다: managed13 중5 assertion FAIL, worker3 중1 FAIL, legacy2 PASS, final PG9 중6 FAIL, errors/skip0. 최초 compile/setup 실패 기록을 이 요구 RED와 섞지 않았다. focused98의 검증 범위는 이 RED27 전체가 아니라 managed/worker/legacy18과 기존80이다.

## 확인한 구현 경계

- `AiDispatchModePort`는 application/output의 `isEnabled()`만 가진 순수 포트다. Worker/Processor는 adapter·HTTP·Spring property·금액 authority 타입을 가져오지 않는다. `AiHttpDispatchConfiguration`의 기본 구현은 gate와 동일한 `AiDispatchGateProperties.enabled`를 읽는다. default OFF와 legacy nullable constructor seam을 유지한다.
- managed final Gemini/Luna는 별도 nonnull gate 인자를 가진 @Autowired 생성자를 사용한다. required gate가 없으면 managed startup이 실패하고 nullable legacy 생성자로 몰래 선택되지 않는다. direct OFF/test seam은 유지된다. managed personal factory는 global ON인데 gate가 없으면 호출 전 CONFIGURATION provider를 반환하여 legacy HTTP를 열지 않는다.
- 기본 gate ON/OFF는 기존 provider/routing/classifier flags의 의미를 대체하지 않는다. 관리되는 다섯 경로의 같은 ON gate/deny-before-connect 및 명시 OFF switch는 focused Spring tests에서 검사한다. 새 mode는 가격/READY를 만들지 않는다.
- Worker는 ON일 때만 locked claim의 check.id/generation/version/ownerToken, 정확 raw fingerprint와 candidate.referenceAt/zone/locale/search range를 context로 전달한다. 일반 createdAt으로 기존 해석 anchor를 덮어쓰지 않는다. 남은 시간은 기존30초 execution에서 completion reserve3초를 뺀 제한 및 claimedAt+15초의 최소값이다. local monotonic cutoff도 그 남은 시간으로 만들며 단계별 새15초를 지급하지 않는다. OFF/ABSENT는 dispatchContext=null과 기존 timeout 경로다. 기존 validate/publish/rejectPublication/last CLEAR 경로를 바꾸지 않는다. 이 worker test의 locked claim은 mock이므로 실제 auth/PG proof가 아니다.
- Processor는 ON에서 invocationRepository 또는 fallback bean이 없으면 run/논리 claim/provider I/O 이전 nonretryable CONFIGURATION으로 거부한다. fallback의 API key 값은 이 검사에 포함하지 않으므로 존재하지만 키가 빈 fallback bean 때문에 정상 Gemini primary를 미리 차단하지 않는다. 실제 fallback 진입 시 그 adapter의 기존 key 검증은 유지된다.
- Processor context는 실제 claim된 `invocation.id/runVersion/ownerToken`을 사용하고 generation/version 모두 실제 runVersion이다.0을 거부하는 새 조건이나 hardcoded0 대체가 없다. exact frozen input_ref/raw 순서 및 locale/해석 referenceDate를 기존 FrozenSubmissionReader/anchor 경로에서 읽고 shared fingerprint로 묶는다. context는 provider payload에 덧붙이지 않는다.
- physical attemptId는 실제 삽입·admission된 attempt.id다. primary ordinal은 현재 invocation 안에서 admittedAttempts1..4이고 과거 run의 전역 attempt.number와 분리된다. fallback ordinal은1이다. 새 invocation/version을 새 room account로 만드는 코드가 없다.
- ON에서는 provider attempt 시작을 DB attempt admission 전에 monotonic으로 잡고, admission 후 `min(original provider deadline, attempt start + Gemini15/Luna27)`로 재계산한다. admission 잠금 대기가 각 leg의 시간을 소비한다. 전체60초/fallback27초/완료reserve3초 및 기존4+1 재시도·비retryable CONFIGURATION의 즉시 안전 지연 분기는 유지한다. OFF는 기존 provider deadline/nullable fallback·invocation seam 동작이다. 반환 성공 이후의 current run/publication fence와 late usage 기록도 기존 경로를 유지한다.
- Processor에는 새로운 HTTP/가격 계산 권한·원장 정산 호출이 없다. 기존 Gemini 비용 metric estimate는 durable settle/완전 bill proof로 재사용하지 않는다. raw/질문/답변/secret을 새 로그에 쓰지 않는다. managed constructors 보완 외 adapter wire body/usage/one-shot 관련 변경은 앞선 별도 source review에서 관리한다.

## 남은 한계와 다음 단계

실제 execution fence 구현, persisted auth/binding/current check·run/attempt와 source/context 재계산, 정상 CLOSED final 및 CLOSED personal 거부, historical/untracked epoch, final9 PG lifecycle와 whole-room1는 이번 focused98의 증거가 아니다. 이 부분과 all-path 실제 PG/loopback 결합을 다음 독립 리뷰에서 확인해야 한다. 특히 실제0 runVersion 및 후속 invocation의 global attempt7→provider ordinal1, 빈 fallback key로 정상 primary 가능 등은 source 의미를 확인했지만 해당 PG 양성 GREEN을 아직 주장하지 않는다.

새 default budget authority/실제 가격·cap·운영 threshold는 여전히 미구성 DENY이며 실제 비용 상한·정확도·p50/p95 또는 단일 smoke 실행 완료가 아니다. 새 확정 정책을 추가하지 않았다. FI 및 변경 없는 full guard 이후에 최종 게시 판정을 따로 내린다.

검토 중 src/tests/SQL/git/branch 수정0, Gradle 실행0, paid/계정/key/OAuth/SSM 접근0이다.

## 정확 SHA256 snapshot

- source `src/main/kotlin/com/meetme/server/coordination/adapter/output/integration/OpenAiLunaNaturalLanguageParserAdapter.kt`: `918fb1341fe07cabfcd907a401a2faa0318b6df37a936439e0eb8404409dd08d`
- source `src/main/kotlin/com/meetme/server/coordination/adapter/output/integration/GeminiNaturalLanguageParserAdapter.kt`: `d0be49b1fe0cefdbb7e97cff984f06e0b338d36bb70a79ab3123c3900880f429`
- source `src/main/kotlin/com/meetme/server/coordination/application/service/GeminiBatchProcessor.kt`: `9753c3b0933d84a14bb52e02e489d6078051f4e93d321422ee7f80c74dc9a800`
- source `src/main/kotlin/com/meetme/server/precheck/application/service/PreSubmissionCheckWorker.kt`: `0ea67fce5580fb7701aad58f8dd29b624f6673da56bfc6344b2226298bb1ca60`
- source `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderConfiguration.kt`: `33aae3dd519d5bfc60a12a794774e97667272fbeb0149352241f712cd9a2f67c`
- source `src/main/kotlin/com/meetme/server/shared/adapter/output/aihttp/AiHttpDispatchConfiguration.kt`: `0942dcb21dd9a957f0ed134dfcad4eae5a1172ce119d137375a3efbfdbfd8cb3`
- support source `src/main/kotlin/com/meetme/server/shared/application/port/output/AiDispatchModePort.kt`: `bc8683b05d1e4d3bd8ae922a030327682fda56f374fba923b4ceca0b2743f18b` (receipt의6개 외 순수 포트; 최종 source manifest에 포함)
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123AiHttpDispatchGateTest.kt`: `490bc5d4e148ec9df850824624eb56142abda3d94881f4179662fd3f562439c0`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchFixtureControlTest.kt`: `6dceaefda47a2b7adc5422ffc38f870bc0120a121020aeecae57e3c16411821a`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchFixtures.kt`: `140268c3b45fa61066fe3e3384e21ead9e8baaff09a5b58feff622c66fdd7e79`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchPublicBoundaryTest.kt`: `160b36f1458983086ecdb1328778ffcc24ab57953f1da130eb6c995c14300c6c`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123ProviderDispatchContractTest.kt`: `6c0e9b33ceb2c196c8343d5340c8821f3a762cdb3e51ff3733ab37a2129faf10`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123TwoStagePhysicalDispatchTest.kt`: `cef44a914e78f50267e67b2cfb3ba5fcaf2f3cf02f8c2e14a4394f1d2ed4c8f5`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchWriteDeadlineTest.kt`: `508d021a5e0759a1cc4988810ed1cdec9675a95340c5d0338bf9ffb34ac60188`
- test `src/test/kotlin/com/meetme/server/coordination/adapter/input/web/Issue123FinalDispatchLifecyclePostgresTest.kt`: `b3d62a22b0dba887d7abe4afd6b517a95f29911fe56c1ca68dab6511d6a1439f`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123FinalLegacyCompatibilityTest.kt`: `81515d2e7e8e739667b4081eff9f172653df814c30847c7ac4dbd05a772010dc`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123ManagedDispatchCompositionTest.kt`: `24fbf7a042c888a75caf68040c2f88e183227239b430c49d689937ee231666e2`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123WorkerDispatchLineageTest.kt`: `df58be0f972ad37cfd2bc3c43202e299ffe9e0bb0cc1566303dcb7e4d245705a`
- evidence `.tdd/history/issue123-dispatch-orchestration-focused-green/receipt.json`: `8e5b7d8764fe9cd91684ee9b29d3d04e6188eb073dcd3d986d6d7d44d4d23f0f`
- evidence `.tdd/red/issue123-dispatch-integration.json`: `f749b684f8ea1940ada2362ab0e23849d4d439255cfd7dd1147777e708c34e1b`
- evidence `.codex/tdd-evidence/issue123-orchestration-focused-green/run.log`: `bbabc0117a1fa1d892b37de8fa616fe5b9e0774592dc63d9f411ae5605f96ad8`
