# Issue 192 검증 기록

## 복구 계약

실패 작업의 원 journal과 command identity는 변경하지 않는다. 별도의 immutable intent와 `FAILED_CLOSED` 종결 증거를 남기고, owner를 조건부 쓰기로 fence한 뒤 검증된 동일 snapshot에서만 해제한다. 배포 성공이나 `NOT_ATTEMPTED`로 바꾸지 않는다. 원래 실패한 명령과 workflow의 종료, 활성 명령 부재, 전체 DB history와 guard, 동일한 정상 runtime 및 규칙을 모두 증명하지 못하면 중단한다.

기존 release 경로는 계속 거절한다. 새 배포에는 새 immutable build가 필요하며, 안전한 wrapper 진단 marker는 제한된 stage/reason만 기록한다. 당시 실패에는 직접 분기 로그가 없으므로 기존 경로 거절은 강한 정황이며 확정 원인으로 취급하지 않는다.

## 독립 검사

- 독립 핵심 계약 26개: 변경 전 정상 수집 후 assertion RED, 변경 후 GREEN.
- 실제 adapter writer 5개, 전체 history proof 1개, FI gate 2개, 통합·CLI·wrapper 9개를 합친 43개 PASS. FI는 16개 mutant를 검출했다.
- 전체 history의 boolean/integer 및 integer/float 동등성 혼동을 독립 반례로 검출하고 closure 경로에서 canonical JSON 비교로 수정했다.
- 상세 범위와 LF 해시는 [독립 검토 기록](issue192-independent-closure-review.md)에 보존한다. 원본 운영 로그와 로컬 테스트 로그는 게시하지 않는다.

## 전체 검사에서 발견한 테스트 환경·관측 문제

첫 Windows 전체 실행은 2,706개 중 104개 실패, 17개 skip이었다. POSIX filesystem와 TLS 차이 외의 실패까지 환경 문제로 일괄 처리하지 않았다. 정상 Linux filesystem의 별도 WSL 작업 공간에서 원본 guard를 실행한 결과 2,702개 중 17개 실패, 17개 skip이었다. 실패는 PostgreSQL 연결 거절 16개와 HTTP2 진단 단언 1개였다.

실패한 네 클래스는 원래 단언 그대로 집중 재실행해 통과했다. 해당 실행에서 세 새 PostgreSQL fixture의 mapped port를 관찰했으며 localhost, IPv4, IPv6에 각각 21개 성공 표본이 있었고 실패 표본은 없었다. 이전 실패 컨테이너도 시작 후 살아 있었고 실패 뒤 정리 호출로 종료됐다. 이는 과거 포트가 준비됐다는 직접 증거가 아니다.

고정 Testcontainers 2.0.5의 PostgreSQLContainer는 내부 준비 로그 두 번으로 시작을 판정하며 호스트의 mapped port를 검사하지 않는다. 영향을 받은 세 fixture는 원래 두 로그 조건과 전체 60초 상한을 유지하고 mapped port 준비 조건을 추가했다. 테스트 기대값·DB migration·운영 코드·Docker 권한은 변경하지 않았다. 원래 연결 거절의 정확한 당시 원인은 미확정이며 이 수정은 확인된 준비 판정 공백을 닫는다.

고정 OkHttp 4.12.0의 `canceled` callback은 다른 이벤트와 동시에 또는 종료 뒤에 전달될 수 있다. observer는 `finish()` 뒤의 이벤트를 버리므로 최종 snapshot에 CANCELED event가 반드시 있다는 단언은 외부 계약에 맞지 않는다. 실제 call의 `canceledAtFailure == true`를 검증하도록 바꾸고, 이른 callback과 finish 뒤 늦은 callback을 각각 결정적으로 검사한다. TIMEOUT, HTTP/2, 물리 요청 1회, 요청 timeout, 실제 timeout과 경과 시간, CALL_FAILED 및 응답 부재 단언은 유지한다. 원래 실패한 실행의 진단 snapshot은 보존되지 않아 당시 경합을 직접 확인했다고 주장하지 않는다.

## 추가 독립 반례와 결함 주입

HTTP2 observer는 네트워크 없이 분리한 다섯 대조군을 통과했다. 실제 취소 상태가 true인 이른/늦은 callback, false 상태, callback 표지만 있고 물리 상태는 false인 적대적 합성 입력, getter가 불능인 null을 구분한다. scratch bytecode에서 정확한 isCanceled getter 한 곳을 always-false·always-true·callback-only 추론으로 바꾼 세 결함은 의도한 AssertionError 일곱 개로 검출했다. 클래스 로딩·검증기 오류를 검출로 세지 않았고 원본 source/test/class 해시는 유지했다. 모순된 callback-only 합성 입력이 RealCall의 실제 동작이라는 뜻은 아니다.

DB 준비 조건은 고정 WaitAllStrategy에 독립 대조군을 넣어 로그/포트 네 조합 중 둘 다 true일 때만 완료됨을 확인했다. 실제 compiled helper의 원 image·로그 regex/횟수2·outer/child60초·포트 AND를 확인했고 로그 횟수1·timeout120초·포트 조건 제거 구조 변이 세 개를 검출했다. 이는 구성 계약의 검사이며 DB 접속이나 과거 거절의 재현은 아니다. 이 추가 검증은 Docker/DB/Gradle/provider 호출 없이 별도 scratch에서 수행했다.

두 번째 원본 native 전체 guard는 22분43초 후 실패했다. 실제 XML은 2,693개 중 2,675 PASS/실패1/기존17 SKIP이었다. ReviewedAiBillSettlementPostgresTest의 컨테이너 시작이 outer60초 복합 준비 조건에서 timeout돼 12개 메서드 대신 initializationError 하나로 집계됐고, 새 테스트2개를 포함한 기대2,704개보다11개 줄었다. 해당12개를 통과로 계산하지 않는다.

고정 구현상 WaitAll은 로그→HostPort 순차이며 HostPort는 내부 Docker exec 검사와 외부 TCP 검사 둘을 병렬로 기다린다. 사후 PostgreSQL ready로그 두 번만으로 당시 WaitingConsumer가 소비했는지 또는 어느 하위 검사가 막혔는지 구분할 수 없다. 따라서 이번 실패를 외부 TCP 실패로 확정하지 않는다. 독립12 fixture의 cached/fresh 포트와 TCP가 모두 일치·성공했고, 별도3 fixture에서 localhost/Windows NAT 게이트웨이는 연결됐으나 host.docker.internal은 연결되지 않았다. 이는 새 관측이며 과거 실패 원인의 직접 증거가 아니다.

## 최종 로컬 검증 상태

원본 `.codex/hooks/tdd_guard.py`의 ktlintCheck·assemble·test는 native Linux에서 **EXIT0,2,704개 중2,687 PASS/기존17 SKIP/실패·오류0**,1,448.457초로 완료됐다. 이전 실행과 skip 이름 집합이 같고 원본 hook 해시도 유지됐다. 로그·시간·요청 수·guard 기대값과 테스트 범위를 완화하지 않았다. 합성 provider/local fixture만 사용하며 실제 유료 호출은0이다.

실제 WSL 모드가 NAT임을 확인하고 [Microsoft의 Windows 호스트 접근 지침](https://learn.microsoft.com/en-us/windows/wsl/networking) 및 [Testcontainers HOST_OVERRIDE 설정](https://java.testcontainers.org/features/configuration/)에 따라 테스트 프로세스에만 현재 Windows NAT 호스트 주소를 명시했다. 초기 Logback과 Spring 재초기화에는 별도 scratch의 관측 설정을 적용해 로그 소비·내부 exec·외부 TCP 단계를 수집했다. OS·Docker·방화벽·TLS·credential 설정은 바꾸지 않았다. 이전 실패의 정확한 하위 정지 단계나 근본 원인이 이 구성으로 인과적으로 확정됐다고 주장하지 않는다.

native HTTP2/observer22·DB16 집중 검사와 배포 Python424, Compose 합성 비밀값 전달·credential refresh stub·격리 Nginx 검사도 PASS다. closure43 및16개 FI mutant 검출과 HTTP2/DB 추가 독립 반례·변이 검사는 앞 절의 범위를 따른다.

로컬 native Node409개는315 PASS/94 FAIL이었다. jq 경로 누락은 기존 호스트 권한으로 설치할 수 없었다. 검증한 jq1.8.1을 정상 경로에 둔 격리 Linux 이미지에서는 변경 없는409개 중402 PASS/7 FAIL/skip0이다. 남은7개는 해당 이미지에 제공하지 않은 Compose·host Docker API 관련 DX01, AC01–05, EV06이다. native Docker의 --mount 단독 검사도 없는 자체 fixture 경로를 자동 생성하고 합성 명령을 실행해 Desktop bind 동작을 별도 재현했다. 이를 통과 처리하지 않고 **정확한 원격 HEAD의 정상 Ubuntu 필수 CI**를 확인한다.

전체 guard 실행 중 검증한 변경 runtime/test 파일의 canonical aggregate SHA-256은 `ddc9a7b941161cd5ff8d76cfd79b9153f2b82895e10e64ce6698788a5694b226`이며 기준 HEAD는 `7b0aed8ec9e6f51bc52726dd6b749dce1563382b`다. 게시 전 source/test bytes 및 staged Git blob과 대조하고 문서의 실제 검사 결과만 후속 갱신한다. 최종 commit/tree와 원격 CI 증거는 PR에 기록한다. 모든 필수 CI와 독립 검토 조건이 충족되기 전에는 병합·운영 종결·배포를 실행하지 않는다.

## 운영 중단 조건

정확한 계정·대상·identity·원 명령 terminal failure·활성 명령 부재·owner/journal snapshot·전체 DB history·guard·규칙·runtime proof 중 하나라도 다르거나 읽기가 불확실하면 쓰지 않는다. 조건부 쓰기 응답이 불확실하면 같은 계획으로 재조회하며, 다른 plan이나 강제 잠금 해제·원 journal 수정·기존 release 삭제로 우회하지 않는다. 기존 보호 배포 gate는 그대로 따른다.

유료 provider 검증은 별도 승인된 총액 상한을 실제로 강제할 수 있을 때만 가능하다. 무료 검증 통과를 유료 실제 흐름 확인으로 표시하지 않는다.
