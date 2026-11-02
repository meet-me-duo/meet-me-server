# HTTP2 취소 관측과 테스트 준비 경계 검토

## 정확한 실패 분류와 계약

기존 전체 검사에서 `GeminiLocalHttp2DiagnosticTest`의 15초 adapter cap 사례는 `CANCELED` callback 이벤트의 존재 단언에서 실패했다. 앞선 TIMEOUT 분류, HTTP2와 요청 1회, 실제 14–15초 timeout 및 14–18초 경과 단언은 유지한다. 이 실패를 기간 상한 실패로 표현하지 않는다. 정확한 당시 진단 snapshot이 확보되지 않아 실제 callback 경합이 그 실행의 원인이었다고 확정하지 않는다.

고정된 OkHttp 4.12.0의 [EventListener 계약](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/EventListener.kt#L379)은 cancellation callback의 동시 실행과 terminal 이벤트 뒤 도착을 허용한다. [RealCall 구현](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealCall.kt#L126)은 취소 flag와 exchange 취소를 먼저 처리한 뒤 callback을 호출한다. 로컬 캐시의 정확 JAR bytecode도 같은 순서였다. Google SDK 1.72.0의 [동기 요청 경로](https://github.com/googleapis/java-genai/blob/v1.72.0/src/main/java/com/google/genai/HttpApiClient.java#L74)는 execute의 IO 실패를 감싸며 취소 callback 완료를 join하지 않는다.

observer는 실패 시점의 실제 `Call.isCanceled()`를 `canceledAtFailure`에 보존하고, `finish()` 이후 callback은 확정된 snapshot에 추가하지 않는다. 따라서 snapshot에 CANCELED callback이 없다는 사실만으로 물리적 취소가 없었다고 판정할 수 없다.

## 최소 테스트 수정

production Kotlin은 변경하지 않았다. `GeminiLocalHttp2DiagnosticTest`의 취소 검사를 `canceledAtFailure == true`로 교정했다. TIMEOUT·HTTP2·정확 요청 1회·요청 30초/adapter 15초·실제 14–15초/경과 14–18초·CALL_FAILED 존재·응답 header 완료 없음 단언을 그대로 유지한다. sleep이나 timeout 증가, 가짜 CANCELED 이벤트를 넣지 않았다.

`ParserTransportDiagnosticContractTest`에 두 결정적 계약을 추가했다.

- early callback: 물리 취소 flag를 true로 바꾼 뒤 canceled와 callFailed를 순서대로 전달한다. 실제 취소 상태, 정확한 이벤트 순서·시각과 정제된 실패 분류가 함께 보존된다.
- late callback: 물리 취소 flag를 true로 바꾼 뒤 callFailed·finish·canceled를 순서대로 전달한다. 실제 취소 상태가 true로 남고, 확정 snapshot과 export 횟수는 바뀌지 않으며 관측하지 않은 callback 이벤트를 만들지 않는다.

이는 pinned callback 계약에 맞춘 테스트 oracle 교정이다. 당시 원인 확정, 생산 timeout 정책 변경, provider 성공 또는 실제 운영 검증으로 확대하지 않는다.

## PostgreSQL 준비 helper의 독립 검토

주 작업자가 추가한 test-only `mappedPortReadyPostgres()`와 세 fixture 변경을 읽기 검토했다. Testcontainers 2.0.5의 [PostgreSQLContainer](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/modules/postgresql/src/main/java/org/testcontainers/postgresql/PostgreSQLContainer.java#L48)와 같은 준비 로그 정규식·2회 조건 및 `postgres:18-alpine`을 유지하고 mapped listening port 대기를 추가한다. [WaitAllStrategy](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/core/src/main/java/org/testcontainers/containers/wait/strategy/WaitAllStrategy.java)는 기본 outer timeout으로 전체 60초를 적용하므로 제한을 두 배로 늘리지 않는다.

기존 JDBC 연결·fixture 시작/종료·SQL·기대값은 유지된다. 이 준비 조건 보강은 검사 완화가 아니다. 과거 connection-refused의 정확한 원인은 아직 추정이며, 이 역할은 DB fixture나 전체 검사를 중복 실행하지 않았다.

## 검증 기록

최초 Windows 집중 시도는 Gradle 캐시 접근에서 종료됐고 테스트가 실행되지 않았다. 다음 Windows 실행은 test compilation을 마쳤지만 추가 테스트의 chain 서식과 fixture import 정렬에서 ktlint가 실패해 assertion 결과로 사용하지 않았다. 서식을 정정한 뒤 기존 native Ubuntu/JDK17/Gradle 캐시에서 두 HTTP2 관련 suite와 ktlint를 집중 실행했다.

`ktlintCheck test --tests '*ParserTransportDiagnosticContractTest' --tests '*GeminiLocalHttp2DiagnosticTest'`가 EXIT0, BUILD SUCCESSFUL 2m46s로 종료됐다. 실제 XML은 HTTP2 **6 PASS**, DiagnosticContract **16 PASS**, 합계 **22 PASS / 실패·오류·skip 0**이다. DB suite나 전체 guard는 이 역할에서 실행하지 않았다.

새 집중 실행의 15초 사례는 TIMEOUT, 실제 요청 1회/h2, elapsed 15.027251640초, call timeout 14.997초, `canceledAtFailure=true`였다. CALL_FAILED 뒤 CANCELED callback이 관측된 순서도 확인했다. 이 자료는 새 합성 loopback 실행의 결과이며 원래 실패 snapshot의 인과를 소급 확정하지 않는다.

검사한 native 테스트 source의 LF SHA-256:

- `GeminiLocalHttp2DiagnosticTest.kt`: `296815f91c74be003bfc33617199f6b742e734ecfa99fa2ca158709d4ce800ca`
- `ParserTransportDiagnosticContractTest.kt`: `cf2fd28503c53bb9904d7b3aae6e7a1ab9bef43ada411c1cfd1cab2d35226d1e`

정제된 XML 집계·지문·합성 사례는 로컬 전용 `issue192-http2-native-summary.json`에 보존한다. 전체 원본 guard와 정확 최종 HEAD CI는 주 작업자가 이 테스트 변경을 포함한 최종 source로 별도 확인한다.

## 후속 독립 결함 주입

별도 scratch의 compiled observer 대조군5개는 early/late 실제취소true, 실제false, callback표지만true인적대적합성입력, getter불능null을 구분해 통과했다. 정확 getter한곳의 always-false/always-true/callback-only추론3변이는 의도한AssertionError7개로 검출했고 원본source/test/class해시를 유지했다. 네트워크/Gradle0이며 모순된합성입력을 실제RealCall동작으로 주장하지 않는다. 기존단언의시간·요청·TIMEOUT보호와 실제취소증명을 유지하는 검토에서 열린P1/P2는 없다.

DBhelper는 pinnedWaitAll의 로그/포트4조합 중둘다true일때만완료됨과 outer/child60초를 확인했다. compiledhelper의 원image/log2/regex/60초+portAND를검사하고 log1/timeout120/port제거 구조변이3개를검출했다. 실제DB접속·과거실패재현을 대신하는 증거가아니다. 최종원본nativeguard는2,704개 중2,687PASS/기존17SKIP/실패·오류0으로종료했고, 원인불확실성은 [전체검증기록](issue192-validation.md)에 보존한다.
