# 승인된 새 합성 입력 최대2회 실모델 smoke 결과 (2026-10-31)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**Gemini 실제1회가 TIMEOUT으로 끝나 첫 기술 오류에서 전체 실행을 중단했다. 실제 Luna0회이며 남은1회는 폐기했다.** 재시도·추가fallback·지도·Jev호출0이다. 실행 종료 코드는0이지만 모델 성공이 아니라 증거 정리 완료를 뜻한다. 해석 결과·usage·청구액은 미관측이며 전체 흐름 성공을 주장하지 않는다.

## 범위와 실행 고정

사용자 승인 `synthetic-authorization-22a0a84b3c783600`은 Gemini최대1회/15초 다음 Luna최대1회/27초, 총 물리 요청2회·재시도0·첫기술오류전체중단이다. 기존 승인 잔여분을 재사용하지 않았다. 전역 승인별 `CREATE_NEW` claim과 launch marker를 만들었고 삭제·환불하지 않았다. 실행 전 manifest3303개의 소스·실제 compiled class·runtime dependency·설정/무료 증거 해시를 검증했다. 실행 후에도 전부 동일했다.

서버는 `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a` + 기존 격리 V2/조건부 장소/오류 보존 변경, 웹은 기존 `6e406ce07f5045790120bce6f34407ca0d65e108` + 보존 변경이다. 이번 제품 소스 변경0이며 현재 runtime을 새 scratch에 동결해 사용했다. 기존 지원되는 환경의 key binding과 proxy/CA를 사용했고 값은 기록하지 않았다. 원격 최신·운영 설정은 조회하지 않았다.

계획된 첫 경로는 실제 Gemini 서비스 어댑터→GenAI Java SDK1.72.0→OkHttp→실제 전송gate이다. 진단 실행의 source authority는 합성 guard이며 운영 DB를 쓰지 않는다. 첫 경로가 정상 HTTP·종료 기록·strict parsing·입력 참조 집합 검증까지 성공할 때만 별도 격리 앱의 합성 Gemini503/Retry-After40초→실제 Luna1회/27초→후처리·확정 저장을 실행하도록 했다. 두 번째 경로는 첫 오류 때문에 시작하지 않았다. 실제 Gemini 실패 뒤 자동 Luna fallback을 시험한 결과가 아니다.

새 개발 합성 모임은2026-11-20부터21일 배타적 종료, Asia/Seoul, 대면, 소요시간미지정/수동마감이다. 세 원문과 사전 고정한 의도는 `scenario.json`에 보존했다. 공통 시간 의도는19~20시다. 첫 참여자는 특정 역 기준 도보10분 필수, 두 번째는 장소·이동 제한 없음, 세 번째는 개인 출발지 미상·대중교통30분 필수다. 확인하지 않은 장소·이동 조건은 충족됐다고 주장할 수 없으며 실제 시간 충돌을 없애지 않는다. 봉인 입력·운영/사용자 입력은 사용하지 않았다. 블라인드 평가가 아니다.

## 실제 결과

| 항목 | 관측 |
|---|---|
| 실제 모델 | `gemini-3.8-flash` |
| SDK Call / 헤더 시작 / 헤더 완료 / Call종료 | 각각1, 같은 Call ID |
| 반환 | `NaturalLanguageParserException`, `TIMEOUT` |
| HTTP 상태 | 미관측(null), 503/529로 확인된 것이 아님 |
| 어댑터 전체 경과 |15.220672858초 |
| 실제 HTTP 경과 |14.636575843초 |
| 요청 예산 / 어댑터 예산 |각15초 |
| 실제 HTTP callTimeout |남은 시간으로 축소된14.635초 |
| 실패 시 취소 여부 |true |
| 진단 분류 |INTERRUPTED_IO, STREAM_RESET/CANCEL |
| 이벤트 순서 |CALL_START→REQUEST_HEADERS_START→REQUEST_HEADERS_END→CANCELED→CALL_FAILED |
| 실제 Luna/Jev/지도 |각0회 |
| 최종 상태 |STOP, stopped=true |
| 파싱된 조건·usage·청구액 |모두 미관측. 비용0으로 확정하지 않음 |

실제 요청 헤더가 전송됐다는 클라이언트 증거1개를 보존했다. 공급자 수신·청구 exactly-once까지 증명하는 것은 아니다. 로컬 종료 기록의 `TERMINAL_STOP`2개는 두 catch가 동일 TIMEOUT을 기록한 것이며 요청2회가 아니다. 추가 Call/헤더/재시도는 없다.

HTTP 경과와 실제callTimeout이 근접하고 로컬 취소가 관측돼 기존 제한/취소 양상과 일치한다. 그러나 정확한 타이머 소유자나 공급자가 그 시간 안에 응답하지 않은 이유, 원격reset 인과까지 확정하지 않는다. 어댑터15.22초에는 SDK 초기화·처리·정리 시간이 포함되므로 HTTP cap을15.22초로 늘린 것이 아니다. 이번 호출의 모델 결과/본문은 도착하지 않아 의미 추출 품질을 채점할 수 없다. 과거33.3초 Luna 진단이나 과부하 추정과 이번 TIMEOUT을 혼동하지 않는다.

Luna 실호출, 실제 Luna 응답의 장소·시간 해석, 새 응답의 추천/제외 사유·확정 저장은 미실행이다. 이번 승인에 남은1회를 재시도나 다른진단에 사용할 수 없다.

## 무료 사전 검증과 하네스 복구

유료 실행 전에 provider키/live flags를 제거하고 비루프백 libc TCP접속을 차단한 별도 프로세스에서 검증했다. 외부 접속 차단 시도0, 외부 모델/지도 요청0이었다.

- 실제 Gemini 어댑터·SDK·gate와 실제 Luna 어댑터·gate를 각각 loopback 정상/503/잘못된응답/250ms timeout으로 실행했다. 8사례 모두 서버 수신1요청·재시도0. Luna 실제callTimeout이 요청 cap 이하임도 network interceptor에서 확인했다.
- 다른 SDK Call/같은 Call의 두 번째 헤더 시작을 차단했다. 종료 기록 누락·TIMEOUT종료·503종료·중복종료도 다음단계 허용을 거부했다. HTTP200이나 헤더 전송만으로 Luna를 허용하지 않는다.
- 기존 독립 장소/시간 반례16개와 저장된 모델 응답 호환1개를 실행해 통과했다. 장소 미검증+시간 후보 유지, 실제 시간충돌·장소충돌 거절, 같은 기준점의 다른 제한, 비대면/대면 분리를 포함한다.
- 실제 localhost HTTP/Spring/PG18/Redis8 앱에서 합성 precheck·합성Gemini503·합성Luna body로 추천3개·주최자확정·결과재조회 동등성을 확인했다. 지도 호출0. 이 mock body는 시간 조건만 포함하므로 새 원문의 실제 장소 추출 정확성을 검증한 것은 아니다. 해당 의미 규칙은 독립 domain 반례와 이전 저장 응답 검증 범위로 구분한다.

첫 앱 준비는 잘못된 배타적 종료일(시작=종료) 때문에 실패했다. 독립 검토에서는 Luna bean 이름, 조회 SQL의 존재하지 않는status컬럼, 확정 endpoint/field, 다음단계 허용 전 종료기록 검증 문제도 발견했다. 모두 **scratch 하네스에서만** 정정한 뒤 무료 검증을 다시 통과했다. 이전 실패/초기 컴파일·무료 시도는 삭제하지 않고 `preflight-attempt1/2`와 compile 로그로 보존했다. 제품 assertion·prompt·threshold·timeout을 바꿔 통과시킨 것이 아니다.

기존 제품 소스가 그대로이므로 앞선 [최종 예산 검증](experimental-final-analysis-budget-audit.md)의2619PASS/17동일SKIP와 이전 웹807/브라우저 증거는 보존하고 불필요하게 전체 재실행하지 않았다. 이번 scratch 검사 숫자를 그 전체 검사에 더하지 않는다.

## 보존과 미완료 범위

증거 디렉터리: `local-only-evidence/paid-two-20261031`.

- `result-summary.json`, `paid-result.json`, `paid/ledger.json`, `paid/gemini-result.json`: 실제 결과와 시간/종료 증거.
- `scenario.json`, `manifest.json`, `manifest-sha256.txt`: 새 합성 원문·사전 의도·3303파일 실행 고정.
- `free-result.json`, `app-free/app-result.json`, `independent-preflight.md`, `independent-result.md`: 무료 검증과 독립 검토.
- `TwoCallHarness.java`, `TwoCallApp.java`, `launch.py`, `classes/`, `runtime/`: scratch 실행 소스와 고정한 현재 compiled runtime. `runtime.classpath`는 기존 캐시 의존성을 가리키며 manifest는 그 해시까지 보존한다.
- 전역 단일 승인 claim: `local-only-evidence/synthetic-authorization-22a0a84b3c783600.claim`.

기존 소스910개·보호 자료19개(앞선 archive 포함)의 해시가 모두 동일하다. 제품/웹/DB migration/timeout/모델·schema/자격증명·보안 설정 변경, Linear수정, 외부 게시·push·PR·merge·deploy는 없다. 이번 원격 오류 결과는 확정됐으며 불확실한 실행을 재시도하지 않았다. 작업을 막은 것은 권한거부가 아니라 첫 공급자 요청의TIMEOUT과 합의한 중단 조건이다.

남은 것은 실제 공급자 응답 지연 원인과 Gemini 정상해석·Luna 실호출/후처리의 검증이다. 추가 비용 없는 코드/증거 조사는 가능하지만, 새 원격 요청이나 timeout 정책 확대가 필요하면 별도 구체 승인 전에 실행하지 않는다. 이번 실험은 종료됐다.

최종 정제 증거 묶음: `local-only-evidence/meet-me-two-call-smoke-20261031.tar.gz`. 독립 결과 검토도 실제Gemini1/Luna0·STOP·manifest불변·잔여폐기를 확인했으며, 검토한 실행guard의P1/P2 결함은 발견하지 못했다.
