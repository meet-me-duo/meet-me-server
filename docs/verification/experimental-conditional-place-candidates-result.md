# 장소 미검증 시간 후보 — 격리 구현·무료 검증

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

2026-10-31. 서버 `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a`의 기존 V2 미커밋 작업, 웹 `6e406ce07f5045790120bce6f34407ca0d65e108`의 피처 작업에서 이어서 구현했다. main/develop 병합·게시·배포·운영 변경은 없다. 이전 전체 회귀 2593PASS/17SKIP와 원본 응답·패치는 별도로 보존했다.

## 바뀐 동작

검증하지 못한 장소·이동 조건만으로 시간 후보 전체를 제거하지 않는다. 명시된 강한 장소 그룹의 교집합과 실제 시간 충돌은 계속 검사한다. 장소 제한 없음, 미검증, 과거 누락 데이터를 구분하며 특정 지역·문장·테스트 ID 예외는 없다. IN_PERSON 모임을 자동으로 REMOTE로 바꾸지 않는다. EITHER 재생은 별도로 설정을 바꾼 통제 사례다.

후보의 내부 참여자 ID 목록에 장소 미확인 상태를 함께 보존한다. 새 V22는 candidate_participants / recommendation_variant_participants에 place_unverified를 추가하고 기존 행은 false로 유지한다. 불변 추천 선택은 원래 variant를 참조하며 미확인 상태를 변경하지 않는다. DB는 flag 변경과 REMOTE 참여자의 미확인 flag 삽입을 거부한다. 추천의 기존 IN_PERSON place_name 필수 CHECK만 제거해 장소를 발명하지 않은 시간 후보도 저장한다. 나머지 FK·시간·선택·권한·업데이트 guard는 유지한다.

### V22가 필요한 이유와 과거 데이터

기존 참여자 관계에는 시간 후보에 포함된 사람만 저장되어 있어, 메모리에서만 붙인 미확인 상태는 재조회·서버 재시작·확정 저장 뒤 사라질 수 있다. 전체 quality=PARTIAL만으로는 어느 조합의 어느 참여자가 장소 미확인인지 보존할 수 없다. 선택된 variant의 참여자별 place_unverified를 DB에 함께 저장하도록 V22를 추가했다. 장소 없는 대면 시간안을 저장하려면 기존 대표 장소명 필수 CHECK도 조정해야 한다. 원문·구조화 조건은 그대로 두며 새 장소를 생성하지 않는다.

과거 행의 place_unverified 기본값은 false이며, 빈 미확인 목록에서 유도되는 새 API attendance_confirmed 기본값은 true다. 이는 **과거 후보의 기존 표시를 유지하는 호환 기본값**이다. 과거 장소·이동을 새로 검증했다는 뜻이나 실제 참석 사실의 증명이 아니다. 과거 후보·순위·선택·원문은 재계산하지 않는다. PC06은 실제 V17→V18 거부/허용과 동일 스냅샷을 검증한 뒤 V22까지 업그레이드하고, 과거 두 관계 테이블의 미확인 행 수0 및 이전 확정 HTTP 결과 동일성을 확인한다. 이전 서버의 필드가 없는 응답은 웹의 기존 읽기 방식으로 유지한다. 조건부 후보의 새 설명을 표현하려면 신규 서버와 웹을 함께 검토해야 한다.

### 설명과 확정 저장의 검증

설명 문자열 자체를 별도 DB 컬럼에 저장하지는 않는다. 고정된 시간창과 조합·참여자별 미확인 사실로 설명을 재생성한다. 주최자 확정은 그 불변 option/variant와 선택 시각만 참조하며 장소 상태를 충족으로 변경하지 않는다.

CPP01은 두 저장소를 통한 미확인 ID 왕복과 원래 이동 조건 보존을 검증한다. CPP02는 확정 전후 candidate_participants / recommendation_variant_participants의 participant_id·place_unverified 행 전체가 동일한지 비교하고, 확정 응답·재조회·동일 확정 재시도에서 false와 미확인 설명이 남는지 확인한다. CPP04는 선택 후 flag를 false로 바꾸는 SQL을 거부한다. CPP05는 비대면 참여자를 미확인 대면 상태로 새로 삽입하는 SQL을 거부한다. 저장 응답 재생에서는 추천 설명과 선택 후 설명 모두 미확정 문구를 포함하며 실제 선택 variant의 미확인 참여자2명이 DB에 남는다. 선택 시각이 더 좁아지면 시간 부분은 선택한 범위로 생성하고 장소 미확인 의미는 유지한다.

공개 API에는 CandidateResponse.attendance_confirmed, RecommendationVariantResponse.attendance_confirmed / summary 세 필드만 추가한다. false일 때 인원은 시간 조건 일치 인원이며 실제 대면 참석 가능으로 해석하지 않는다. 카드·시간 선택·확정·legacy 카드의 기존 문장과 인원 표시를 사용한다. 새 경고 영역이나 배지는 추가하지 않았다. 다른 사람의 원문·이동 제한·개별 선호를 설명에 새로 공개하지 않는다. 기존 HOST 미반영 진단과 원문·보완 답변·PLACE 범위는 유지한다.

실제 재생에서 나온 설명:

> 11월 12일 19:30~21:00. 3명의 시간 조건이 맞지만, 일부 장소·이동 조건은 확인하지 못해 실제 대면 참석 여부는 미확정입니다.

최종 화면에서도 `3/3명 시간 일치`와 같은 설명을 유지한다. 주최자 시간 확정이 장소 검증 완료라는 뜻은 아니다.

## 일반 의미 규칙과 비파괴 변경 확인

후보 수는 특정 지명·문장이 아니라 방의 IN_PERSON/REMOTE/EITHER 설정과 구조화된 강한 제약으로 결정한다. 유효한 시간 조건만 있고 장소 제한이 명시되지 않았으면 장소 제한 없음이며, EITHER에서는 대면 A와 비대면 B 모두 허용된다. 이전 `.single()` 전제는 장소 제한 없음과 해석하지 못한 장소를 같은 null로 취급한 과거 동작에 의존했다. 수정된 테스트는 후보 수 검사를 삭제하지 않고 정확한 A/B 두 방식과 두 후보 모두의 기존 시간 구간·실제 고정 참여자·장소 없음·미확인 없음·선호 점수를 검증한다. 명시된 장소가 충돌하면 해당 대면 조합은 계속 거부하며, 검증 불가 이동만 있으면 시간 후보를 미확인으로 유지한다. production 코드에는 테스트 ID·특정 지명·문구·5분/10분 예외가 없다.

V22에는 기존 행의 DELETE/UPDATE나 원문·후보 재계산이 없다. 두 boolean 컬럼 추가, 기존 대표 장소명 필수 CHECK만 완화, 미확인 상태 변경 및 비대면 flag 삽입 방지 trigger 추가다. 기존 키·외래키·좌표/비대면 제약과 선택 guard는 유지했다. 실제 빈 DB 전체 migrate/validate, V8→V22 이전 비즈니스 컬럼 스냅샷 동일성, V17→V18의 기존 거부/허용, V22 이후 이전 공개 결과 동일성과 false 기본값을 별도로 확인했다. 운영 migration과 rollback은 실행하지 않았다.

기존 실제 응답을 재사용한 원래 IN_PERSON 방은 후보 생성→추천3개→주최자 확정→재조회까지 통과했다. 선택 전후 설명 문자열과 false 상태가 동일한 것은 기존 HTTP 산출물을 읽어 다시 대조했으며 새 실행·API 요청은 없었다. 증거는 `replay-confirmation-proof.json`이다. 알려진 시간 충돌·장소 충돌 거부는 독립 CP03/CP04/CP11/CP12와 기존 회귀에서 확인했다. UI는 기존 설명 span/문장과 인원 표시를 사용하며 새 장소 전용 경고 영역·배지는 추가하지 않았다. 기존 공통 PARTIAL 안내와 HOST 진단은 유지한다. 웹 mock 흐름은 설명→선택→확정 API→저장 결과까지 같은 미확인 의미를 표시하며, 실제 브라우저와 서버를 연결한 시험으로 혼동하지 않는다.

## 검증 근거

- 독립 설계 도메인16개 + PostgreSQL/HTTP5개: 실제 시간·장소 충돌, 미상 기준점, 무제약/legacy 누락 구분, 같은 기준점의 다른 이동 제한, 복수 장소 대안, 부분 참석, 순서 독립, 50명, 개인정보, nullable 장소, 확정·재조회·멱등성·영속 상태를 검증했다.
- 새 정책이 대체한 기존13개 중4개 기대값과 matrix23개 중3개 기대값만 독립 검토 후 수정했다. 시간 교집합·원문·진단·권한·과거 확정 검증은 유지했다. 최종 집중57개 PASS.
- 초기 RED27개 중13개가 기존 정책 때문에 실패했다. 후속 복수 AREA 소실/단일 좌표 영역 누락은 별도 반례로 고정해 수정했다. 컴파일 fixture 타입 오류, rank99 CHECK 오류, StaticMessageSource 누락은 유효한 정책 RED와 구분해 원본 로그를 보존했다.
- 의도적 결함5개를 모두 탐지했다: 알려진 장소 충돌 무시2실패, 미확인 ID 삭제9실패, JDBC 조회 누락4실패, flag 변경 허용1실패, REMOTE 삽입 허용1실패. 마지막은 CPP05 첫 추천 INSERT 경로에서 탐지되며, 정상 테스트가 legacy INSERT 거부도 별도로 확인한다. 원본 코드는 매번 정확히 복구했다.
- 저장된 실제 Luna 응답은 input_ref UUID만 새 격리 방에 연결했다. 원문/보완/조건의 파서 결과와 DB가 동일하다. 원래 IN_PERSON은 READY_WITH_WARNINGS, legacy 후보1/추천3, 주최자 CONFIRMED/선택1행이며 선택한 variant의 미확인 참여자2명이 그대로 저장됐다. EITHER 통제 사례는 legacy2/추천3/선택1행이다. 실시간 모델 통합 성공으로 표시하지 않는다.
- 웹 전체805PASS + 추가 legacy 카드1개 + mock 선택→확정 API→저장 결과1개, 고유807개 테스트를 실행했다. 최종 관련36PASS, 타입·린트·native API 생성/정합성·빌드 PASS. 이 시점의 mock 검증은 실제 UI와 서버를 연결한 아래 후속 검증과 구분한다.
- 모든 서버 실행은 키/live mode 제거, Gradle --offline, libc connect의 비loopback TCP 차단 아래 수행했다. 로컬 PostgreSQL/Redis/HTTP 및 Docker Unix socket만 사용했다. DNS/direct syscall/별도 외부 daemon 전체를 차단한 OS 격리라고 주장하지 않는다. 재생은 provider headers/DNS/connect/지도 요청 모두0을 별도로 기록한다.

첫 전체 회귀는 2631개 중 **2607PASS/7FAIL/0ERROR/17SKIP**였다. 실패는 EITHER 무제약 후보 개수 가정2건, V17에서 V22 컬럼을 읽던 역사 fixture1건, 최신 버전/실행 수 기대값4건이다. 상세 원인과 실패 원본은 first-full-result.json/XML에 보존했다. 서비스 판정은 바꾸지 않고 두 방식 모두에 원래 시간 oracle을 적용했으며, PC06 테스트 전용 역사 저장소 두 개로 실제 V17/V18 스키마와 기존 23514→허용·롤백·스냅샷·HTTP·멱등성 검증을 유지했다. 최신 버전 기대값22/실행 수14를 명시했으며 이 보완 집중14개가 통과했다.

최종 전체 ktlintCheck / assemble / test: **PASS** (2026-10-31 03:08:36 UTC, exit0). 전체2631개 = **2614PASS / 0FAIL / 0ERROR / 기존17SKIP**이며 건너뜀 목록은 이전 전체와 동일하다. 실패했던 7건도 최종 실행에서 모두 통과했다. 실행 소스666파일 SHA-256 `f7599a1ac881c2bc8a0b43e86a456324c38913381ad5fd5074203e6d78db57a6`를 고정했다. 프로덕션 소스는 첫 전체 실행 뒤 바꾸지 않았다. `local-only-evidence/final-full2-started.json`은 실패 원인을 보완한 이 실행의 중복 방지 표식이다.

## 범위와 보존

새 지도·좌표 추정·이동시간 환산·지명 예외·모델 prompt/threshold/timeout 변경은 없다. 실제 이동 가능성, 불투명 자연어의 모델 해석 정확성, 미개봉 holdout, 실모델 재검증은 미검증이다. 새 마이그레이션은 로컬 테스트 DB에서만 적용했으며 운영 적용은 별도다. 이전 결과는 재판정하지 않는다. 이 조건부 후보를 읽는 API와 UI는 함께 검토해야 하며 오래된 UI는 새 flag의 의미를 표현하지 못한다.

증거 디렉터리: `local-only-evidence/conditional-place-20261031`. baseline.tar.gz와 baseline.json은 시작 시1961파일을 보존한다. 초기 실패·수정·재생·FI·최종 실행은 각기 다른 로그/XML/JSON이다. 기존 원본 공급자 response.json / structured.json / pilot database.json / final60 payload.json 4개 및 소비된 승인 claim은 수정하거나 재사용하지 않았다. 봉인 시나리오는 열지 않았다. 추가 외부 API·유료 요청·게시·푸시·PR·병합·배포0.


최종 실행 전후666개 코드/리소스/빌드·API 계약 지문, 보호 대상19개, 원본 자료4개의 해시를 확인했다. 실제 테스트 worker PID42424의 차단 모듈 활성화와 비loopback 연결 시도0을 확인했다. 이 시점의 서버 회귀 검사는 완료됐으며 이후 추가 유료 모델 호출은 없다. 승인된 브라우저 후속 검증은 다음 절에 별도로 기록한다. V22는 로컬 테스트 DB만 적용했다. 이 기존 검증 종료 시점에서 실모델·실제 이동/장소 충족·미개봉 holdout·브라우저와 서버 연결·운영 적용은 미실행이었다. 브라우저와 로컬 서버 연결은 아래 후속 검증으로 완료했고 나머지 범위는 미실행을 유지한다. 차단된 작업은 없고 이 미실행 항목은 승인 범위 밖이다.


## 후속: 실제 Chromium ↔ 격리 로컬 서버 검증 (2026-10-31)

**완료:** 실제 Chromium 151.0.7922.173로 4개 시나리오·6개 검증 묶음이 통과했다. 브라우저와 서버 프로세스 모두 exit0으로 종료했고 새 격리 PostgreSQL/Redis도 정상 정리했다. 서버/웹 제품 코드와 테스트 코드는 변경하지 않았다. 기존 서버2614PASS/17SKIP·웹 고유807개 결과를 보존하고 무관한 전체 회귀를 다시 실행하지 않았다. 문서·검증 증거만 추가한다. Linear는 수정하지 않았다.

### 실행 구성과 차단

현재 HEAD는 서버 b4b3a8c의 기존 실험 미커밋 작업, 웹6e406ce의 기존 피처 작업 그대로다. 서버는 직전 통과 빌드의 main1002개/test1116개 클래스 및 main28개/test6개 리소스를 동결하고 의존 jar까지2368개 파일 지문을 확인했다. 새 Java launcher는 저장소 밖에서 이 런타임에 연결했다. 웹은 현재 소스로 scratch/web-dist를 새로 빌드하고 실제 정적 파일을 서빙했다. 방·추천·확정·결과 API는 같은 origin 프록시를 통해 실제 Spring 서버·PostgreSQL·Redis를 사용하며 이 API 응답을 Playwright로 대체하지 않았다.

브라우저 로그인 시작의 `/api/auth/refresh`만 합성 세션으로 bootstrap한다. 실제 서버는 해당 Bearer를 RS256으로 검증한다. 따라서 이 실행은 실제 OAuth/refresh 서비스 자체의 통합 검증이 아니다. Playwright HAR/trace나 헤더·토큰 덤프는 만들지 않았고 결과 JSON에도 토큰을 넣지 않았다.

실제 모델 키/live mode를 제거하고 원래 대면 Luna 응답만2회 재생했다. 기존 replay cap2는 변경하지 않았다. 서로 다른 실제 충돌을 확인하는2개 반례는 별도의 명시적 parser double이며 원래 응답을 고쳐서 만든 결과가 아니다. 서버 provider headers/DNS/connect/지도 호출은 각각0, 브라우저 페이지 외부 요청도0이다. Chromium 배경 연결은 로컬 deny proxy9건 및 libc connect6건이 차단했다. 서로 다른 계층의 숫자이며 합산한 API 호출 수나 전체 시도0으로 표시하지 않는다. 서버와 웹 build의 비loopback connect 차단 건수는0이다. 실제 모델·유료·지도 요청0이며 운영/timeout 설정은 변경하지 않았다. 기존 libc guard 범위 밖의 DNS/direct syscall/외부 daemon까지 OS 전체 격리했다고 주장하지 않는다.

### 실제 확인 결과

| 사례 | 실제 브라우저·서버 결과 |
| --- | --- |
| 데스크톱1365×900 | 원래 대면 재생으로 추천3개. 각각 정확한 시간·인원(3/3,3/3,2/3), IN_PERSON, 시간 일치와 설명 안의 미확정 문구를 확인했다. 추천2에서20:05~21:05 KST를 직접 골라 확정 후 실제 GET/DB 선택 튜플 및 새로고침 결과가 일치했다. |
| 모바일390×844 에뮬레이션 | 같은 원래 대면 재생으로 추천3개. 추천1의19:30~21:00 전체 구간을 선택했고 확정·새로고침 후 미확인 상태와 설명을 유지했다. 가로 넘침이 없고 설명을 잘리지 않게 렌더링했다. 실물 휴대폰 시험은 아니다. |
| 알려진 시간 충돌 | IN_PERSON의 합성3명 구간이 쌍별로 겹치지 않는 parser double을 실제 matching에 전달했다. 서버 NO_MATCH/추천0, 브라우저 후보·선택 버튼0, 새로고침 후 선택 저장0이다. |
| 알려진 장소 충돌 | 같은 가능한 시간과 서로 다른 강한 AREA1/2/3을 가진 parser double을 실제 matching에 전달했다. 서버 NO_MATCH/추천0, 브라우저 후보·선택 버튼0, 새로고침 후 선택 저장0이다. 실제 모델/지도 판정 검증은 아니다. |

양쪽 양성 방은 준비 시 selection0/READY_WITH_WARNINGS를 확인해 이미 확정된 방을 브라우저 성공 증거로 사용하지 않았다. 선택→추천으로 돌아가기와 홈 이동→브라우저 뒤로가기까지 확정 요청0·DB 선택0이었다. 확정 버튼의 두 DOM click() 활성화는 각 방에서 실제 confirmation POST1회와 선택 저장1행으로 끝났다. 물리 포인터 double-click 시험으로 표현하지 않는다. 확정 후 새로고침·실제 native GET 결과는 확정 응답과 동일했다. 기존 리뷰 prompt의 별도 로컬 POST는 유지되므로 모든 POST가 한 번뿐이었다고 주장하지 않는다.

확정 전후 두 참여자 관계의 미확인 행 전체와 frozen 원문/보완·structured 조건 SHA-256이 동일했다. 선택된 대면 variant의 미확인 참여자2명과 attendance_confirmed=false가 그대로 남았다. 데스크톱은 선택한 좁은 시간으로 설명의 시간 부분이 바뀌고 미확인 의미는 유지됐으며, 모바일 전체 범위는 기존 설명도 그대로 유지됐다. 각 카드·시간 선택·확정·재조회 화면은 `참석 가능` 대신 `시간 일치`를 표시한다.

### 실제 화면과 중복 안내

새 장소 전용 경고·배지는 추가하지 않았다. 카드마다 기존 설명에 미확정 문구가1회 들어가고, 시간 선택에서는 같은 설명을 보여준다. 기존 공통 PARTIAL 안내1개와 HOST 미반영 상세는 유지한다. 이 fixture는 기존 replay와 동일하게 별도 HOST와 제출한 guest3명 구성이므로 HOST 자신의 미제출 안내1개도 기존대로 보인다. 이 안내까지 없었다고 표현하지 않는다. 상세 원문은 후보 문장에 삽입하지 않는다. 독립 검토에서도 기능 범위의 추가 P1/P2는 없었다.

브라우저 console page error0이며, 테스트 종료 전 기록한 DB 선택 수는 desktop1/mobile1/time-conflict0/place-conflict0이다. source와 이전 문서1973개 파일은 보고서 갱신 전까지 모두 동일했고 서버 소스666개와 동결 런타임2368개의 지문도 유지했다. 보고서·계획·검증 기록 이외의 제품/테스트 소스 수정은 없다. 브라우저 기반 검증을 막는 남은 차단은 없다.

### 명령·증거 경로

실행 디렉터리: `local-only-evidence/conditional-place-browser-20261031`.

- 서버: `python .../start-server.py` → 현재 동결 classpath로 `java --add-modules jdk.httpserver ... ConditionalPlaceBrowserServer` 실행. `ConditionalPlaceBrowserServer.java`, `compile-final.log`, `server-started.json`, `server.log`, `ready.json`, `server-exit.json`, `server.audit` 보존.
- 웹: 설치된 `node_modules/.bin/vite build --configLoader runner --outDir .../web-dist`, VITE_API_BASE_URL은 same-origin 빈 값. `web-build.log/json`, `web-assets-manifest.json` 보존.
- 브라우저: `node .../browser.mjs` (서버·웹·브라우저 모두 loopback guard 적용). `browser.log`, `browser-result.json`, `browser-exit.json`, `browser.audit`, `final-db-state.json`, `preservation-before-report.json`, `final-result.json` 보존. 재시도 없이 한 번 실행했다.
- 실제 화면: `desktop-candidates.png`, `desktop-time-selection.png`, `desktop-confirmed.png`, `desktop-reloaded.png`, 대응 `mobile-*.png`, `time-conflict-no-match.png`, `place-conflict-no-match.png`.

비공개 화면 증거: 모바일 후보 화면 (mobile-candidates.png, 미게시) · 비공개 화면 증거: 데스크톱 재조회 화면 (desktop-reloaded.png, 미게시)
