# 최종 분석 예산·기한 오류 보존 무료 검증 (2026-10-31)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

현재 격리 소스는 서버 `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a` + 기존 V2/조건부 장소 미커밋 변경, 웹 `6e406ce07f5045790120bce6f34407ca0d65e108` + 기존 변경이다. 원격 최신/운영 설정은 조회하지 않았다. 사용자 승인에 따라 무료 분석·필요한 로컬 수정·검증만 수행한다. 유료 호출·지도·holdout 개봉·게시·병합·배포·운영 timeout 변경은 없다.

## 확인된 예산

아래 값은 Luna fallback이 연결된 현재 경로의 코드·기본 설정이며 실제 운영 환경변수의 적용값을 뜻하지 않는다. fallback이 없는 경로는 별도의 예약 계산을 쓴다.

| 경계 | 제한·기준 | 실제 의미와 근거 |
|---|---|---|
| 전체 분석 | processor 진입부터 monotonic 60초 | `GeminiBatchProcessor.process`에서 시작. 초기 큐 대기는 포함되지 않는다. DB 시작·claim 대기는 포함되며 durable deadline도 이전 실행에서 연장되지 않는다. |
| Gemini 단계 | 최대30초, HTTP별 최대15초, 총4시도 | Luna27+완료3초를 먼저 예약한다. backoff·admission 시간도30초에서 차감한다. |
| Gemini 재시도 | 최초+최대3, Full Jitter 0~1/2/4초 | NETWORK/TIMEOUT/RATE_LIMIT/SERVER만 재시도·fallback 대상. Retry-After가 남은 Gemini 예산보다 길면 기다리지 않고 fallback. 의미 거절·인증·설정 실패는 같은 경로가 아니다. |
| 최종 Luna | 최대1회, 최대27초 | `min(27초, caller 잔여시간)`으로 admission 뒤 축소한다. Gemini가 빨리 실패해도 남은 primary 예약 시간을 빌려33초를 허용하지 않는다. |
| 결과 게시·matching | provider 결과 도착·게시 진입은57초 이전, DB게시·matching 완료는60초 이전 | provider 호출 deadline 또는 호출별 상한에 정확히 도달한 성공도 거부한다. 저장 전후 wall/monotonic 재검사와 트랜잭션 rollback으로 늦은 winner·추천 게시를 막는다. 3초는 성공 보장이 아닌 예약이다. fallback도 남은 시간·현재 소유권이 있어야 시작하며, 늦게 돌아온 primary 호출 때문에 예약 시간까지 소진되면 새 호출을 허용하지 않는다. |
| Gemini SDK·HTTP | SDK attempts1/delay0/jitter0, OkHttp 연결재시도·redirect 끔 | caller와15초 중 작은 cap. SDK 호출 직전 잔여시간으로 다시 축소. 독립 connect/read/write phase timer는0이며 전체 call timer 사용. |
| Luna HTTP | Responses 직접 HTTP, 재시도·redirect0 | caller와27초 중 작은 전체 call timer; dispatch 직전 잔여시간 적용. 모델/strict schema/출력 상한은 변경하지 않았다. |
| 실제 dispatch authority | invocation deadline−30초(primary)/−3초(fallback), attempt시작+15/27초 중 작은 값 | `JdbcAiBudgetExecutionFence.finalAnalysis`; current run/version/owner/attempt/source도 재검사한다. 단일 SDK timeout 변경만으로 예산을 늘릴 수 없다. |
| Worker lease | `pendingMinIdle` 기본120초 | scheduler가 같은 설정을 worker lease에 전달한다. claim 뒤 heartbeat는 없으며 lease가60초 분석 예산을 늘리지 않는다. owner fencing은 durable invocation에서 별도로 수행한다. |
| 큐·복구 | relay 기본1초, worker fixed-delay500ms, read block100ms, batch10 순차 처리 | 초기 큐 대기·앞 메시지 처리가 추가된다. stale claim/republication 기본120초; recovery scan 기본30초. 이 값들로 전체 사용자 대기60초를 보장할 수 없다. |
| abandoned invocation | 원래 deadline 이후 기본30초 주기 복구 | 새 유료 시도를 허용하지 않고 지연 상태로 종결한다. 중복 전달은 기존 invocation·owner·시도 상한을 초기화하지 않는다. |
| 프론트엔드 | ANALYZING2초, 수집/참여중 READY5초 polling | `RoomPage.tsx`와 `api/client.ts`: 조회 AbortSignal만 전달하며60초/27초 전체 UI timer는 없다. 조회 취소가 worker/provider 작업 취소를 의미하지 않는다. |
| transport 기록 | 비동기 queue128, DB tx2초/statement1초/lock0.5초 | 진단 기록 누락은 미관측이며 provider 재호출의 근거가 아니다. 이번 수정 대상인 gate observe의 권한 검사와 별개다. |

설계 근거는 [ADR-049](../ADR.md#adr-049-제한된-luna-구조화-폴백과-영속-실행-예산), [PRD](../PRD.md) 249행 시간 배분이다. 구현은 `AnalysisInvocationBudget`, `GeminiBatchProcessor`·동일 파일 persistence, `MatchingProcessor` persistence, `JdbcAiBudgetExecutionFence`, `CoordinationWorker`/`CoordinationWorkServices`, `OutboxRelay`, `AnalysisInvocationRecovery`, `ReliabilityProperties` 및 웹 polling이다. 새 예산 정책/ADR/DB/API 변경은 도입하지 않았다.

## 일반 안전 조건

- backoff·잠금 대기·재시도를 포함해 원래 caller deadline과 durable deadline 중 더 이른 경계를 지킨다. 프로세스 재시작·재전달·늦은 callback으로 시계나 시도 상한을 초기화하지 않는다.
- 실제 transport cap은 provider phase/attempt/authority의 남은 시간 이하여야 한다. SDK 내부 retry·redirect를 추가해 물리 요청 수가 늘어나지 않아야 한다.
- 시간 여유가 있어도 현재 run/version/source/owner가 아니면 새 호출·winner·추천을 게시하지 않는다. 성공 응답에도 같은 권한 검사를 적용한다.
- fallback은 유효한 기술적 transient와 남은 시간·원래 호출 상한이 모두 충족될 때만 실행한다. 의미 해석 실패를 기술적 timeout으로 바꾸거나 반대로 바꾸지 않는다.
- 실패 뒤 진단·권한 기록도 실패할 수 있다. 원래 실패 분류/원인/Retry-After를 보존하면서, 후속 기록 실패를 성공 허가로 해석하지 않는다.
- 큐 체류·복구 지연·프론트 조회 주기는 모델 처리 예산과 분리한다. 60초 결과 게시 제한을 사용자 전체 대기60초 보장으로 안내하지 않는다.

## 27초와 과거33.296초의 차이

[이전60초 진단](experimental-final-luna-sixty-result.md)은 실제 Luna1회 HTTP200/completed, call33.295569652초, input3317/output3203(추론1946포함)이었다. 앱의27초 및 영속 실행 fence 바깥에서 명시 승인된 단일 진단이므로 실제 앱 전체 성공과 다르다. [이전 비교](experimental-final-luna-success-timeout-comparison.md)의 짧은 입력은5.564초 성공, pilot10은 wire26.879초/adapter27.005초 종료였다. 모델·응답 형식·27초 cap이 같아도 입력/출력량은 달랐다. 한 사례는 응답 가능성을 보일 뿐, latency 분포나 지연 원인을 증명하지 않는다.

60초 전체 시간이 남았다는 이유만으로 Luna가27초를 넘겨도 된다는 계약은 없다. 현행 정확한27초 경계 거부를 테스트 실패로 취급하지 않는다. 향후 Luna 상한을 바꾸려면 primary 예약·caller/admission·durable/dispatch deadline·완료 시간·worker lease 및 사용자 대기 목표를 함께 결정해야 한다.

## 증거로 확인된 결함과 최소 수정

Luna adapter는 원래 TIMEOUT/NETWORK/503을 throw한 뒤 `finally`의 `physical.observe`가 기한 만료로 거절되면 CONFIGURATION을 새로 throw했다. 실제 gate를 쓴 독립 테스트에서 TIMEOUT/cause, NETWORK/cause, SERVER/Retry-After 세 계약이 실패했다. 성공 만료 거부와 정상 성공 두 대조군은 통과했다. 기존 Gemini adapter에는 이미 원래 오류 보존 로직이 있었다.

Luna에만 같은 원래 오류 보존을 적용했다. 원래 parser 예외가 있으면 그대로 반환하고 후속 권한 거부를 suppressed로 보존한다. 원래 오류가 없는 성공 응답의 observe 거부는 계속 CONFIGURATION으로 실패한다. 취소·재시도·프로바이더 cap·gate 권한 검사·모델·prompt·schema·출력 상한·DB/API는 바꾸지 않았다. 이는 진단 분류 결함을 고치는 수정이며 지연 자체를 해결하거나 새 호출을 허용하지 않는다.

이 결함은 과거 wire TIMEOUT / DB CONFIGURATION 불일치가 생길 수 있는 구체 경로를 증명한다. 과거 실행의 당시 primary/secondary 전체 trace가 없으므로 그 실행의 단일 원인으로 소급 확정하지 않으며 원래 기록을 수정하지 않는다.

## 독립 검증

별도 검토자가 구현 변경 전에 `LunaExpiredObservationContractTest` 5개를 작성했다. 실제 dispatch gate와 고정 wall권한·수동 monotonic clock, HTTP application interceptor를 쓰며 `chain.proceed`·DNS·provider 연결은 하지 않는다. 실제 gate deadline과 정확히 같을 때 TIMEOUT/NETWORK/SERVER 및 원인·Retry-After 보존, 만료 성공 거부, 만료전 성공을 검증한다. 테스트 SHA와 RED 원본을 보존한다.

기존의 독립 합성 테스트도 재실행한다: Luna26.999초 수용/27.000초 거부,57·60초 늦은 게시 거부, 초반 transient·Retry-After fallback, admission으로 축소된 timeout, crash 재전달·외부 owner/old run 거부·단일 winner·동시 중복fallback. 저장 응답 회귀와 조건부 장소 후보·확정·재조회는 전체 회귀에서 다시 검증한다. 별도 실시간 브라우저를 재실행했다고 주장하지 않는다.

기존 로컬 HTTP/2 실험에서 SDK 시작 뒤 deadline setter만으로 native watchdog이 재장전되지 않는 반례가 있었다. 이번 인터셉터 테스트는 만료 결과 거부·분류 보존을 확인할 뿐, 원격 socket의 즉시 취소나 billed work 중단을 입증하지 않는다. 현재 adapter의 사전 whole-call timer와 후속 fence는 유지한다. 60초는 새 호출 허용과 결과 게시의 예산이며 협조하지 않는 I/O·DB·스케줄링까지 정확히60초에 종료한다는 보장이 아니다. 원격 reset과 로컬 cancel의 모든 과거 인과는 미확인이다.

최종 `ktlintCheck assemble test`는2026-10-31 04:45:59 UTC exit0으로 완료했다. **336suites/2636tests = 2619PASS·17SKIP·실패/오류0**이다. 기존17개 건너뜀의 suite/test 식별자는 이전 전체 회귀와 동일하다. 집중54개는 전체 결과에 포함되며 합산하지 않는다.

- 새 독립 테스트: RED5중3실패/정상제어2통과 → 최종5/5PASS.
- 집중7suites/54PASS: 시간경계·fallback/admission·Gemini 기존 관측 계약·worker중복 포함.
- 의도적 결함 주입: 성공의관측권한 거부를 무시하는 compile-valid 변경에서5중 정확히 만료성공 control1개FAIL. 원복 소스 byte동일·동결 테스트SHA동일을 확인한 후 전체 회귀를 실행했다.
- 실제PG 통합: durable owner12, integratedfallback9, lifecycle6, publicationrollback3, admissiondeadline2 전부PASS. 과거 version/다른owner/중복/원래deadline/게시중취소와rollback을 검증했다.
- 저장된 Luna 응답호환1PASS, 조건부장소확정/재조회5PASS. 기존 loopback 실제TLS/HTTP2진단6PASS도 전체 회귀에 포함됐다. 새 원격 모델 생성 품질이나 실시간 브라우저 E2E 성공으로 확대 해석하지 않는다.
- 실행 시 provider키/live flag 제거, Gradle offline 및 JVM 비루프백libc connect 차단을 적용했다. RED/GREEN/결함주입/전체모두 비루프백차단시도0. 실제Jev/Gemini/Luna/지도 요청0. 이 방어가 DNS/directsyscall/외부daemon 전체를 통제한다는 주장은 하지 않는다.
- 전체시작 이후 source910개 지문이 동일하며 baseline기존1975파일중 변경은 Luna어댑터와진행계획뿐이다. 새테스트·RED요약·이보고서는별도추가했다. 기존웹소스·장소구현·archive2/소비claim/원본응답 등보호18개는동일하다. 새테스트SHA `d5e9a941b131ca1a65b99879783b4f4814586e87e8481d46829d2d4935a3f175`.

실행 중 권한거부·불확실한쓰기는 없었으며 현재 미해결 환경 차단도 없다. 실제 모델 latency·청구액·운영적용값은미확인이며 이번범위에서추가유료검증이없다는제한과구분한다.

## 다음 독립 실모델 검증 계획 (미승인·미실행)

현재 무료 계약 증명만으로 새로운 유료 호출이 필수는 아니다. 실제 latency/성공 경로 확인을 원할 때의 최소 smoke는 코드/프롬프트/현재 결과를 보지 않은 작성자가 새 합성 모임3명과 의도를 사전 고정하고, 실`gemini-3.8-flash` generateContent최대1회 + 합성503→실`gpt-6-luna` Responses최대1회, 총 물리 요청2회다. 동일 모델·현행 strict schema·출력 상한 및15/27초를 유지하고 재시도0, 첫 기술 오류·분류 불일치·권한 손실·한도 초과 시 전체 중단한다. Jev/개인 Luna/지도0이며 봉인 사례·운영 데이터는 쓰지 않는다. 각 경로가 성공해도 의미 일반화·latency p95·Jev 전체 흐름 성공은 별도다.

첫 단계는 Gemini adapter 단일 진단으로 앱 재시도/fallback을 실행하지 않는다. 통과한 경우에만 둘째 단계에서 합성 Gemini503/Retry-After40초를 현재 앱에 주입해 실제 Luna1회와 후처리·확정 저장을 확인한다. 이 구분으로 앱의 최대4회 정책을 임의 변경하지 않고 이번 실험의 실제 요청을2회로 제한한다.

요청수를 사전 durable 장부/중복실행 claim으로 제한하고 원래 source/fixture/model 설정을 고정한다. 관측 token을 기록하되 청구액으로 간주하지 않는다. 정확 금액 상한이 필요하면 기존 허용 가격·출력 cap 기준의 사전 추정과 사용자 승인이 선행돼야 하며 현재 가격·청구액은 미확인이다. 상한/시간 확대 실험은 이2회 안에 섞지 않고 별도 정책·승인을 받는다. 이번 작업은 어느 외부 요청도 실행하지 않았다.

## 보존 자료

새 증거는 `local-only-evidence/final-budget-audit-20261031`에 별도 저장한다. 기존 조건부 장소/브라우저 archive2개·과거 소비claim·원본 provider artifact4개를 hash로 보호한다. baseline1975파일을 보존했고 새 테스트는 독립 작성으로 baseline에서 제외했다. 원본 실패 기록/holdout/이전 archive는 덮어쓰지 않는다. 변경은 기존 격리 트리에만 남기며 게시·merge·운영 반영은 수행하지 않는다.

최종 독립 재검토도 전체 XML·동일17SKIP·source910/보호18 해시와 원복을 직접 확인했고, 검토한 수정 경계의 P1/P2 발견0이다. 증거·현재5파일·증분패치는 `local-only-evidence/meet-me-final-budget-audit-b4b3a8c-20261031.tar.gz`로 보존한다.
