# Issue123 provider / physical HTTP gate 독립 1차 source 리뷰

2026-10-18 UTC. `/workspace/issue123-durable-budget/server`, phase2 #123. **현재 판정: 신규 P1 없음, DISPATCH-P2-01 한 건 OPEN으로 최종 source/게시 승인 보류.** 이미 알려진 real DB fence TODO 및 worker/GeminiBatch/context/managed constructor 결합 미완료는 이번 신규 발견으로 중복 집계하지 않는다. core의 별도 P2는 `/tmp/issue123-core-source-review.md`에서 관리한다.

## 검토 범위와 증거

`AiHttpDispatchGate`, prepared/group/exchange/helper/fingerprint, Decisions classifier, 개인 Gemini count/generation, final Gemini SDK, final Luna Responses, TwoStage와 해당 factory의 receipt source10개를 frozen dispatch/public/core contract 및 AD/BD/ID/US/TM/PV에 대조했다. source/tests/branch/git/Gradle은 변경·실행하지 않았다.

`.codex/tdd-evidence/issue123-dispatch-green/receipt.json`과 보관 XML 5개를 직접 집계: gate27 + fixture controls2 + public boundary4 + actual adapter40 + TwoStage5 = **78 tests, failures0/errors0/skipped0**. receipt source10/test6 SHA는 검토 시점 current 파일 모두 일치했다. 이는 frozen baseline-corrected RED의 후속 집중 GREEN이다. actor/fake ledger와 loopback 실제 HTTP 증거이며 real PG CAS/실제 ownership 검증이나 실제 가격/청구 상한 증거가 아니다. 실제 paid provider call0이다.

## DISPATCH-P2-01 — 최종 전송 경계에서 permit wall/effective monotonic deadline을 재검사하지 않음

위치: `shared/adapter/output/aihttp/AiHttpDispatchGate.kt:211–217,230–235`.

app interceptor는 durable claim 뒤 `claimed.deadlineAt`과 Clock을 비교하고, 원래 context cutoff와 permit 잔여시간 중 짧은 값으로 OkHttp Call timeout을 축소한다. 이는 이미 구현되어 있다. 그러나 마지막 network interceptor에서는 `remaining(context)`만 검사한다. claim 당시 계산한 permit-derived monotonic cutoff는 전송 guard용으로 보관하지 않으며 `Clock.instant() < claimed.deadlineAt`도 전송 직전에 다시 확인하지 않는다.

따라서 gate app claim 뒤 downstream app interceptor/connection 과정에서 Clock이 앞으로 이동하여 가격/auth의 permit wall window가 종료되어도 원래 context와 monotonic elapsed timeout이 남아 있으면 요청 bytes가 전송될 수 있다. 예산 hold/원문 유실 문제는 아니지만 만료된 권한으로 물리 요청을 보낼 수 있는 경계 누락이다. 부모는 기존 deadline 계약의 의미를 “emission 직전에 original+permit-tightened monotonic cutoff와 fresh wall deadline을 모두 검사”로 명확히 확인했다. 새 참여/마감/가격 정책 선택은 아니다.

최소 개선: claim에서 계산한 effective monotonic cutoff를 exchange에 보존하고 마지막 network guard에서 original/effective monotonic 및 current Clock<claimed.deadlineAt을 확인한 뒤만 request bytes를 보낸다. backward wall jump가 monotonic deadline을 늘려서는 안 된다. 새 DB transaction/재claim/새 permit/자동 retry는 추가하지 않는다.

독립 supplemental RED: frozen 78 테스트를 수정하지 않고 새 파일에서 base app interceptor를 통해 gate app claim 직후 Clock만 permit expiry 이상으로 이동시킨다. claim1, 실제 HTTP request0, full hold/추가 claim0을 확인한다. 이미 유효 claim이 승인된 뒤이므로 connection setup0을 약속하지 않는다. backward wall clock과 effective monotonic expiry도 허용량 연장이 없음을 검증한다. 수정 후 이 신규 oracle GREEN/FI와 기존78/legacy regression 근거를 연결한다.

## 확인한 구조와 동작

- `PreparedAiHttpRequest.freeze`는 defensive body copy와 SHA256 whole-body hash를 만든다. `copyBody`도 복사다. lazy SDK는 최초 app interception에서 bounded body를 캡처해 quote/reserve/claim하고, 실제 outgoing body를 그 immutable bytes로 교체한다. stateful RequestBody를 quote A 후 다시 평가하여 B를 보내지 않는다.
- prepared와 lazy 모두 app interceptor가 index0에 삽입되어 reservation/claim 거절은 connection 전 발생한다. missing/malformed reservation, wrong execution/child/profile/payload는 허가로 취급되지 않는다. prepare의 context/bundle/ordinal/attempt identity를 검사하고 같은 group child 선택을 한 번만 허용한다. actual claim CAS의 영속성은 core 별도 검증이다.
- exchange의 appUsed는 같은 SDK/exchange의 두 번째 Call을 연결 전 차단한다. 마지막 networkUsed는 같은 Call의 두 번째 network exchange를 차단한다. endpoint/method/body를 마지막 network 경계에서 다시 검사하고 immutable body로 송신하여 downstream body 변조가 quote와 달라지지 않는다.
- retryOnConnectionFailure/redirect/authenticator를 비활성화하고 terminal non2xx를 network interceptor 안에서 분류한다. 따라서 첫503 Retry-After0을 숨은 follow-up으로 소모하지 않고 원래 SERVER로 유지한다. 실제429도 기존 rate-limit kind로 유지한다. 관측/예산/context 거절은 static CONFIGURATION이며 provider transient와 구분된다. final SDK는 cause chain 안의 typed gate/status를 bounded depth로 식별한다.
- Decisions는 고정 `gpt-6-luna`/Decisions API/전체 JSON body, 개인 Gemini는 고정 runtime full count/gen request·개별2048, final Gemini/Luna는 기존32768 wire 설정을 유지한다. body에는 실제 prompt/schema/instructions/model/settings가 포함되어 whole hash로 결속된다. profile은 operation/API endpoint/model/method/structural cap/v1을 포함하는 구조 지문이고 verified 가격 권위가 아니다. parser confidence는 explicit nullable 기존 설정이며 synthetic threshold를 production 기본값으로 만들지 않았다.
- gate에 새 room/check/owner/token/source digest를 provider header/query/body로 주입하는 코드가 없다. 기존 final opaque input_ref는 유지된다. error body는 최대16KiB의 로컬 상태 분류에만 쓰고 신규 raw 응답/secret/원문 로깅을 만들지 않는다. gate 예외는 bounded CONFIGURATION이며 입력/cause를 담지 않는다.
- A/count/gen/final 각 물리 leg가 자신의 reserve/claim/observe를 갖는다. TwoStage physical branch는 기존 stage facade reserve/record를 건너뛰므로 부모+child 이중 hold가 없다. CLEAR는 B0, 정상 review/uncertainty만 B로 진행하고 shared15초 남은 timeout/context를 전달한다. A terminal outputTokens는 N/A/null이며 B2048 검증을 늘리지 않는다.
- adapter는 정상 JSON의 known usage를 product schema/finish/refusal validation 전에 확보한다. count totalTokens를 generation usage나 청구 금액으로 바꾸지 않으며 count usage는 불명으로 둔다. malformed/non200/무usage는 null로 남는다. 어떠한 token/usageComplete도 settle 호출이나 hold 환급으로 연결되지 않는다.
- finally의 observe false/exception은 CONFIGURATION으로 표면화되어 성공 parse/CLEAR를 반환하지 않는다. count 관측 실패는 generation 진행을 막는다. 늦은 관측은 먼저 원래 permit으로 기록한 뒤 기한을 검사하므로 비용 증거는 product timeout보다 독립적으로 남고 늦은 성공은 반환하지 않는다. 알려진 usage는 adapter 메모리 snapshot/원래 reservation 관측에만 붙고 새 product publication을 수행하지 않는다.
- flag OFF는 base client와 old TwoStage stage-budget 경로를 보존한다. 새 classifier bean은 새 budget ON 조건에서만 생성하고 기존 provider/routing/classifier flags를 함께 적용한다. confidence 기본null·defaultDeny authority이므로 실제 운영 quote/threshold/금액이 자동 구성되지 않는다. 전체 legacy 회귀는 부모가 별도 실행 준비 중이므로 이 source 검토로 완료했다고 주장하지 않는다.

## 범위 제한과 다음 시작점

실제 DB fence는 아직 TODO이며 worker와 GeminiBatchProcessor의 persisted context/immutable source snapshot 전달 및 managed ON constructor hardening은 별도 독립 RED 대기다. 현재 fake-ledger 양성은 실제 auth/binding/revision/run/epoch를 통과했다는 증거가 아니다. managed 모든 5종에 같은 gate가 주입되는 최종 구성, ON missing fallback/repository 거절, 정상 CLOSED final/OPEN precheck kind별 guard, 실제 HTTP 동안 PG lock 해제와 callback 경쟁은 후속 실제 결합에서 검토한다.

현재 runtime authority가 DENY이고 minConfidence=null이므로 실제 가격/세금/count fee/billed upper bound/평가 품질·지연/10원 또는 USD1 cap 달성을 입증하지 않는다. actual provider API/model/key/운영 설정 변경·실모델 호출0을 유지한다. 구조32MiB SDK capture는 기존 입력 최대를 위한 내부 자원 상한이며 billed cap이 아니고 원문500/개별10000/응답256KiB 등 기존 제한을 바꾸지 않는다.

다음 시작점은 DISPATCH-P2-01의 독립 supplemental RED→최소 수정→GREEN/FI, 기존78와 provider legacy regression 확인이다. real fence/worker/orchestration 후속 리뷰와 변경 없는 full guard 뒤 최종 source/게시 가능 판정을 별도로 내린다. 현재 commit/push/Draft 또는 정확 HEAD CI 완료를 주장하지 않는다.

## 검토 snapshot SHA256

- `src/main/kotlin/com/meetme/server/coordination/adapter/output/integration/OpenAiLunaNaturalLanguageParserAdapter.kt`: `7b27926b816e6af9933e6831dcc8be228f643b8722f7329aa841f3c94bd5c03f`
- `src/main/kotlin/com/meetme/server/coordination/adapter/output/integration/GeminiNaturalLanguageParserAdapter.kt`: `ffe946ea086a832e29ffc35f064b48e8c337122ec03fc3c554564356efeac8b0`
- `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderConfiguration.kt`: `9ff87920bfc10bb57da82e22fcd8683deb7cd19800fcab03902b5d1060c9d00b`
- `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt`: `d5d97bd7c6f8100736ace583347c5fe5db386b3a371b59a982f4afb8c2bbeef0`
- `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/GeminiPreSubmissionCheckProvider.kt`: `16fba187e8f04dbaf4c3f00db0a14d49183743dcf1dc22c7fe2ee78528ce9075`
- `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/OpenAiAmbiguityClassifier.kt`: `1f33e12211eb9b33b96b70ada710e5a0b3da1b0e0ca4fda7c72c704cc60853fe`
- `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierConfiguration.kt`: `d9259f6c974b0494907cef262db4c6366e0a93a86293225b767beca8fdd9a265`
- `src/main/kotlin/com/meetme/server/shared/application/port/output/AiDispatchFingerprints.kt`: `d02a58d5b56ade84c0e4171ba19e0f957a3abfbb5b9ae94e6616e075ae2da1ea`
- `src/main/kotlin/com/meetme/server/shared/adapter/output/aihttp/AiDispatchBindings.kt`: `52320ad9c8b8a05c4751d533973b1fa321adfcb6ff42ab49971d2779dab74334`
- `src/main/kotlin/com/meetme/server/shared/adapter/output/aihttp/AiHttpDispatchGate.kt`: `b751f9ce5ba3b5314dc5b9119af07b509c31fccabe09c90097b4044660683063`
