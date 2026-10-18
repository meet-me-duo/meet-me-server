# Issue123 dispatch write deadline 독립 재검토

2026-10-18 UTC. 대상 `/workspace/issue123-durable-budget/server`. **판정: DISPATCH-P2-01 CLOSED — 최소 source 보완과 독립 assertion RED→80 GREEN이 해당 발견을 충족한다.** 이 판정은 기존 P2 한 건에 한정하며 최종 게시/운영 승인이나 다른 pending 영역의 완료 판정이 아니다.

## Source 검토

`AiHttpDispatchGate.kt` SHA256 `25c2dbe5f8c6d1bd6597fc3751da06e864203b1c34984941d08e7fe7a2d000cb`를 직접 확인했다. 기존 검토 snapshot은 `b751f9ce5ba3b5314dc5b9119af07b509c31fccabe09c90097b4044660683063`이다.

- line187에 exchange별 permitCutoff를 보관한다. app claim 이후 line212–216에서 fresh wall remaining과 원래 context monotonic remaining의 최소값으로 cutoff를 한 번만 고정한다. 기존 OkHttp timeout 축소도 유지한다.
- 마지막 network 경계 line238–241은 정확한 claimed permit과 그 cutoff가 없으면 거부하고, immutable body snapshot/hash 검증 뒤 원래 context의 monotonic 잔여시간, `Clock.instant() < permit.deadlineAt`, 저장한 permitCutoff의 monotonic 잔여시간을 모두 검사한다. forward wall 이동은 wall guard로, backward wall 이동은 original/effective monotonic guard로 차단된다.
- guarded 검사 이후 송신은 기존 frozen body copy를 사용한다. 이 수정은 새 reserve/claim/재시도/원장 환급을 추가하지 않는다. 이미 admission된 뒤 TCP 연결 준비가 발생할 수 있다는 기존 구분도 유지한다.

## 독립 RED와 GREEN 근거

신규 테스트 `Issue123DispatchWriteDeadlineTest.kt` SHA256 `508d021a5e0759a1cc4988810ed1cdec9675a95340c5d0338bf9ffb34ac60188`, 공유 fixture SHA256 `140268c3b45fa61066fe3e3384e21ead9e8baaff09a5b58feff622c66fdd7e79`를 확인했다. 테스트는 공개 gate/loopback을 사용하고 gate app admission 뒤 downstream base app interceptor에서 이미 claim1임을 확인한 다음 Clock/nanoTime만 이동시킨다.

1. forward-wall: 실제 monotonic context 잔여시간은 남겨두고 Clock만 permit deadline 이상으로 이동한다.
2. backward-wall-after-tightened-monotonic: 원래 context 잔여시간은 남겨두고 wall은 뒤로, permit에서 축소한 monotonic cutoff는 앞으로 넘긴다.

두 경우 typed CONFIGURATION, reserve1/claim1, API HTTP request0을 검사하며 TCP0을 잘못 주장하지 않는다. freeze된 기존78 oracle은 수정하지 않았다.

`.tdd/red/issue123-dispatch-write-deadline.json`과 raw RED XML을 직접 읽었다. RED XML은 tests2/failures2/errors0/skipped0이며 둘 다 `Expected AiHttpDispatchException ... but was completed successfully`라는 의미 있는 assertion 실패다. compile/setup 오류나 의도하지 않은 예외가 아니다. raw XML SHA256은 `c0f2685fde1982baacd86b48f8f104f0e377d27019d34348cf200ad404b80d0a`다. 별도 이름의 이전 compile-setup-failure 기록을 이 RED로 세지 않았다.

`.tdd/history/issue123-dispatch-write-deadline-green/receipt.json`과 `.codex/tdd-evidence/issue123-dispatch-write-green/receipt.json`의 bytes가 동일하다. receipt SHA256은 `6727e2e44c926f7699388b231c453bf4a29d841786aa033cef225232f2cfa08d`다. 보관 GREEN XML6개를 직접 합산해 기존78+신규2 = tests80/failures0/errors0/skipped0을 확인했다. receipt source10/test7 SHA는 검토 시점 current와 모두 일치했다. run.log SHA256은 `8d4efcd1745bfc861f2490bf23d4581d1589bb3d3f54fc39e893469ee3f0b08a`다.

## 판정의 한계

이번 재검토는 request bytes 직전 permit wall/effective monotonic 검사의 P2 닫힘에 한정한다. shortened permit deadline에 맞춘 response-body watchdog 재예약/강제취소까지 새로 보장한다고 판단하지 않았다. 기존 per-provider/body timeout 및 늦은 product result 차단과 request emission 검사는 구분한다. 이 파일은 새로운 slow-body 테스트/feature 또는 정책 변경을 요구하지 않는다.

후속 managed composition/context/실제 DB fence/orchestration, core의 다른 P2 및 FI/full unchanged mandatory guard는 부모가 별도로 요청할 검토 범위다. 현재 독립 RGR source 판정으로 전체 source freeze/게시 PASS를 대신하지 않는다. 실제 paid/model/SSM/OAuth/운영 호출0, source/tests/git 변경0, Gradle 실행0을 유지했다.

## 정확 source/test snapshot

- source `src/main/kotlin/com/meetme/server/coordination/adapter/output/integration/OpenAiLunaNaturalLanguageParserAdapter.kt`: `7b27926b816e6af9933e6831dcc8be228f643b8722f7329aa841f3c94bd5c03f`
- source `src/main/kotlin/com/meetme/server/coordination/adapter/output/integration/GeminiNaturalLanguageParserAdapter.kt`: `ffe946ea086a832e29ffc35f064b48e8c337122ec03fc3c554564356efeac8b0`
- source `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderConfiguration.kt`: `9ff87920bfc10bb57da82e22fcd8683deb7cd19800fcab03902b5d1060c9d00b`
- source `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt`: `d5d97bd7c6f8100736ace583347c5fe5db386b3a371b59a982f4afb8c2bbeef0`
- source `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/GeminiPreSubmissionCheckProvider.kt`: `16fba187e8f04dbaf4c3f00db0a14d49183743dcf1dc22c7fe2ee78528ce9075`
- source `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/OpenAiAmbiguityClassifier.kt`: `1f33e12211eb9b33b96b70ada710e5a0b3da1b0e0ca4fda7c72c704cc60853fe`
- source `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierConfiguration.kt`: `d9259f6c974b0494907cef262db4c6366e0a93a86293225b767beca8fdd9a265`
- source `src/main/kotlin/com/meetme/server/shared/application/port/output/AiDispatchFingerprints.kt`: `d02a58d5b56ade84c0e4171ba19e0f957a3abfbb5b9ae94e6616e075ae2da1ea`
- source `src/main/kotlin/com/meetme/server/shared/adapter/output/aihttp/AiDispatchBindings.kt`: `52320ad9c8b8a05c4751d533973b1fa321adfcb6ff42ab49971d2779dab74334`
- source `src/main/kotlin/com/meetme/server/shared/adapter/output/aihttp/AiHttpDispatchGate.kt`: `25c2dbe5f8c6d1bd6597fc3751da06e864203b1c34984941d08e7fe7a2d000cb`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123AiHttpDispatchGateTest.kt`: `490bc5d4e148ec9df850824624eb56142abda3d94881f4179662fd3f562439c0`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchFixtureControlTest.kt`: `6dceaefda47a2b7adc5422ffc38f870bc0120a121020aeecae57e3c16411821a`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchFixtures.kt`: `140268c3b45fa61066fe3e3384e21ead9e8baaff09a5b58feff622c66fdd7e79`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchPublicBoundaryTest.kt`: `160b36f1458983086ecdb1328778ffcc24ab57953f1da130eb6c995c14300c6c`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123ProviderDispatchContractTest.kt`: `6c0e9b33ceb2c196c8343d5340c8821f3a762cdb3e51ff3733ab37a2129faf10`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123TwoStagePhysicalDispatchTest.kt`: `cef44a914e78f50267e67b2cfb3ba5fcaf2f3cf02f8c2e14a4394f1d2ed4c8f5`
- test `src/test/kotlin/com/meetme/server/shared/adapter/output/aihttp/Issue123DispatchWriteDeadlineTest.kt`: `508d021a5e0759a1cc4988810ed1cdec9675a95340c5d0338bf9ffb34ac60188`
