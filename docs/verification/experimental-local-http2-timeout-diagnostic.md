# 무료 로컬 HTTP/2 타이머 진단 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

기준은 `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a`와 보존한 통계 SQL 로컬 수정이다. 부모가 전달한 main `c2211f8`은 이 작업 트리에 통합하지 않았다. 이번 추가 공급자 요청은 **0회**이며 종료된 cap10 승인/원장을 재사용하지 않았다. 생산 코드·15초 제한·프롬프트·모델·판정 정책·운영 환경·holdout은 변경하지 않았다.

## 실제 실행

`GeminiLocalHttp2DiagnosticTest`는 pinned GenAI Java SDK1.72.0/OkHttp4.12.0/Okio3.6.0과 테스트 전용 MockWebServer4.12.0을 사용한다. 임시 인증서만 신뢰하는 client를 SDK builder에 넣고 loopback TLS/ALPN HTTP/2를 실제로 통신한다. 합성 원문/응답·placeholder 키만 사용한다. 모델 응답을 외부에서 생성한 시험은 아니다.

최종 `ktlintCheck test` 집중 실행은 6suites/37tests **37PASS·실패/오류/skip0**, Gradle exit0이다. 신규 진단은 6methods/7local requests다. 각 사례에서 network protocol `h2`와 서버 수신 stream/request1을 확인했다. 단순 JSON 존재가 아니라 보존한 JUnit XML과 결합해 판정했다. 전체 commit guard는 이번에 재실행하지 않았고 커밋·게시·병합·배포는 하지 않았다.

| 사례 | 실제 종결/결과 | 의미 |
|---|---|---|
| 빠른 SDK 정상 응답 | parse2.034s, 정상 구조화1조건 | fixture·TLS/H2·SDK envelope 유효 통제. 모델 품질 증거 아님 |
| caller30초/adapter15초/헤더18초 지연 | parse15.019s, 실제 callTimeout14.998s, TIMEOUT | adapter cap이 실제 HTTP/2 대기를 제한함 |
| caller500ms/헤더5초 지연 | parse0.508s, 실제 callTimeout497ms, TIMEOUT | 더 짧은 caller 예산이 적용됨 |
| 서버가 HTTP/2 CANCEL 주입 | parse0.067s, NETWORK, reset CANCEL, canceledAtFailure=false | CANCEL 코드만으로 로컬 timeout을 단정할 수 없음 |
| raw OkHttp 시작 전500ms deadline | 0.501s, timeout/canceled | watchdog이 진입 시 deadline을 예약함 |
| raw OkHttp 시작 뒤500ms deadline | 1.296s, 응답 성공, canceled=false | deadline getter 변경만으로 기존 watchdog이 다시 예약되지 않음 |
| 실제 gate, dispatch700ms/SDK2초/헤더1.2초 지연 | parse1.331s, 응답 CALL_END 후 CONFIGURATION, 취소 이벤트 없음 | 늦은 정상 응답은 결과로 반환되지 않지만 전송이 dispatch deadline에서 즉시 취소되는 것은 아님 |

마지막 gate 사례는 `AiHttpDispatchGate` 실제 클래스를 쓰되 `enforceBudget=false`와 합성 runtime guard를 쓴다. 실제 PostgreSQL reservation/source authority 경합, 전체 앱 마감·Jev/Luna·후보·주최자 선택을 검증했다는 뜻은 아니다. 기존 successful-reply observation 거부가 CONFIGURATION이 되는 경로를 관측했으며 SDK 전 설정 거절 또는 원격 CONFIG 오류와 구분한다.

첫 실행은 5tests/4PASS·1FAIL이었다. 15초 native TIMEOUT 뒤 MockWebServer20초 지연 task의 shutdown 대기가 실패한 fixture 정리 오류다. 원래 결과를 보존하고 헤더 지연을18초로 줄여 동일15초 cap 오라클로 재검증했다. cleanup 결과로 native 성공을 꾸미지 않았다. 최종 추가 gate 사례와 raw 이벤트 관측도 함께 보존했다.

## 취소 순서와 기존 실호출 해석

최종 로컬15초 사례의 callStart 기준 CALL_FAILED는14.998913초, CANCELED callback은15.000858초이며 canceledAtFailure=true였다. 첫 로컬 실행에서는 CANCELED가 먼저였다. 두 이벤트의 순서를 보장된 불변식으로 사용하면 안 된다.

설치된 OkHttp bytecode에서 `RealCall.execute()`는 interceptor 실행 전에 timeout.enter()를 호출한다. `RealCall.timeout.timedOut()`는 cancel()을 호출하고, cancel()은 canceled flag 설정과 exchange 취소 뒤에 listener.canceled()를 호출한다. 따라서 reader의 failure callback과 canceled callback 관측 순서가 경합할 수 있다. Okio의 deadline setter는 필드만 변경하고 기존 AsyncTimeout queue를 다시 예약하지 않는다. 로컬 paired control은 이 차이를 직접 관측했다. 이 bytecode와 javap 원본은 별도 증거 폴더에 있다.

기존 실제 Gemini2회의 실제 callTimeout14.849/14.993초와 CANCELED14.850/14.993초, INTERRUPTED_IO→STREAM_RESET/CANCEL은 이번 로컬 watchdog timeout 양상과 일치한다. **로컬 client timeout이 대기를 종결했다는 설명이 강하게 뒷받침되지만, 왜 원격 응답이 그 전에 없었는지와 remote reset 최초 원인은 여전히 미확인이다.** 기존 null-status 오류를 소급 재분류하지 않는다. 원격 응답/헤더/usage가 없는 것을 비용0 또는 공급자 미수신으로 표시하지 않는다.

## 코드 근거와 범위

- `GeminiNaturalLanguageParserAdapter.kt:96,139,172–180`: caller와15초의 min, OkHttp timeout 및 SDK 직전 잔여값 적용.
- `AiHttpDispatchGate.kt:249–251`: application interceptor에서 Call의 deadline을 늦게 설정한다.
- `AiHttpDispatchGate.kt:314–331`: 결과 observation 시 남은 권한/deadline을 확인한다. 이번 진단은 이 정책을 바꾸지 않았다.
- `GeminiLocalHttp2DiagnosticTest.kt`: 정상/실제 cap/짧은 caller/원격 reset/raw paired deadline/실제 gate 비교. 테스트 인증서 신뢰 외 SDK 설정은 원본을 사용한다.
- 변경 파일은 위 신규 테스트, 테스트 전용 의존성2개, 진행 계획과 이 기록이다. 기존 SQL 수정은 바이트 그대로 보존했다. main289개 및 기존 실제 실행 증거6개의 해시가 동일하다.

## 다음 선택지 — 제안만, 추가 실행 없음

1. 무료 범위에서는 설치 SDK/transport와 기존 trace의 한계를 여기까지 확인했다. 별도 타이머 정책 설계가 필요하면 caller/adapter/gate의 deadline 정렬과 취소 보장·늦은 응답 거절·재시도 시간 예약을 함께 검토해야 한다. 근거 없이 공통15초를 늘리는 수정은 하지 않는다.
2. 공급자가15초 뒤 응답 가능한지를 직접 확인할 필요가 있다면 **새 합성 standalone SDK 진단1회**, call/request timeout30초, 실제 요청 최대1·attempts1·자동 retry/redirect/connection retry/fallback/Jev/Luna0을 제안한다. 새 manifest/source/config/input hash·one-use 실행 ID·독립 원장을 고정하고 성공/오류 모두1회로 종료한다. 모델·prompt/schema·출력 한도는 유지한다. 이전 cap10 잔여8회를 쓰지 않는다.

옵션2는 부모 확인 전 구현/실행하지 않는다. 추가 유료 요청이며30초 안에도 실패할 수 있고 usage 미관측 시 과금 여부를0으로 확정할 수 없다. 성공해도 운영15초 SLA, 전체 앱 분석, 실제 Jev→Luna→최종분석 블라인드 평가 성공을 뜻하지 않는다. 전체 흐름의 실제 모델 검증은 계속 미완료다.

원본/최종 XML, 안전한 이벤트 JSON, 소스 고정, 첫 실패 및 bytecode: `local-only-evidence/local-http2-diagnostic-20261030`. 기존 통계 DB는 앞선 조사에서 이미 삭제된 것으로 확인되어 이번에도 복구 통계로 바꾸지 않는다.
