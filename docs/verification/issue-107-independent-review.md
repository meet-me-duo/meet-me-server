# Issue #107 독립 서버 검토

- 검토일: 2026-10-17 UTC. 최종 source 재검토는 공통 소유권 port 분리 및 owner delegation 3개 GREEN 뒤에 수행했다.
- 역할: 초기 HTTP 테스트 설계자와 독립 최종 검토자. 초기 테스트는 공개 제품·HTTP 계약 및 기존 합성 fixture만 읽고 작성했다. 최종 검토에서만 프로덕션 구현을 읽었다. 기대값의 근거는 `docs/ISSUE_107_CONTRACT.md`, `docs/ISSUE_107_TEST_DESIGN.md`, PRD FR-007A/008D/008E/009 및 ADR-046/049다.
- 범위: clarification application/port/controller/JDBC, `V13__add_submission_clarifications.sql`, 구조화 저장 연계, 공통 provider 계약, 안전 제외 matcher와 HOST 미반영 조회 경계. `GeminiBatchProcessor.kt`, 관측 계층, `SubmissionService.kt`는 수정하지 않았다. 유료 공급자·운영 호출, push·PR·병합·배포는 수행하지 않았다.

## 검토 결과

### I107-R1: 현재 배치에서 해소된 질문의 저장 권한 재검증 누락 — CLOSED

`ClarificationService.get`은 활성 배치의 구조화 결과가 `AMBIGUOUS_TIME_CONSTRAINT` 또는 `AMBIGUOUS_INPUT`인 경우에만 질문을 공개한다. 최초 검토 시 `answer`는 같은 활성 run/source/revision/OPEN round를 검증하지만 해당 구조화 결과의 모호성 사유를 다시 확인하지 않았다.

같은 immutable source의 새 분석이 SUCCESS로 끝나 기존 질문이 더 이상 공개되지 않아도, 이전 화면에서 보관한 question ID로 A/B 또는 CANCEL을 POST하면 저장될 수 있다. 현재 활성 결과에서 유효하지 않은 질문이 원문을 바꾸는 권한이 되면 안 된다.

독립 검증: `ClarificationLifecyclePostgresTest.active completed source no longer ambiguous fences an old question id`. 기존 source의 구조화 결과를 모호성 없음으로 바꾸고 GET 질문 0 및 POST 409 `REVISION_CONFLICT`, version/head/work 불변을 검증한다. 후속 source 검토에서 `answer`가 현재 run의 구조화 결과 및 모호성 사유를 다시 검사하도록 수정된 것을 확인했다. 이 검사는 저장·CANCEL 이전에 수행되고, 이미 처리한 동일 요청의 영속 replay는 기존 응답을 그대로 반환한다. 수정 후 lifecycle 및 전체 gate 통과를 확인해 R1을 종결한다. 결함 주입·최종 gate 근거는 아래에 기록했다.

### I107-R2: 공통 소유권 adapter 미전달 — 제품·프로덕션 기동 완료 차단

최초 guest-only `ClarificationService.owner` 복제 구현은 제거됐다. 현재 feature는 `ClarificationOwnerPort.requireOwner(roomId: MeetingRoomId, rawGuestCredential: String?): Participant`에 GET과 POST 모두 위임한다. 서비스에는 guest/session/account 선택, cookie 필수 판정 또는 guest fallback이 없다. 공통 port가 허용한 참여자를 사용하고 거절 예외를 그대로 전달한다. 소스 검토에서 guest repository/credential 의존이 제거된 것을 확인했다.

#109 세션이 실제 shared ownership resolver adapter를 소유한다. 계정/게스트 주체 선택, invalid Bearer 401, 거절 뒤 guest fallback 금지 및 guest/account exactly-one participant 계약은 그 adapter의 책임이다. 현재 `src/main`에는 이 port의 실제 adapter를 추가하지 않았으므로 production Spring 구성은 의도적으로 미완성이며 기동을 완료할 수 없다. 이 상태를 제품 인증 완료 또는 배포 준비 완료로 보고하면 안 된다.

`SyntheticClarificationOwnershipAdapter`는 `src/test` 아래의 합성 guest 증명 전용 `@Component`다. 이중 구현을 제품에 남기지 않고 기존 guest HTTP fixture를 계속 검증하기 위한 test double이다. Gradle의 test dependency/source 구분과 현재 plain jar ZIP 항목을 읽기 전용으로 확인했다. `ClarificationOwnerPort.class`는 포함되고 합성 adapter 및 HTTP/owner delegation 테스트 class는 포함되지 않는다.

최종 assemble 뒤 plain/boot jar 둘 다 ZIP 항목과 SHA-256을 다시 확인했다. `.codex/tdd-evidence/issue-107-final-gate.json`의 기록과 일치하고 합성 adapter·HTTP/owner delegation 테스트 class가 없다.

| 로컬 artifact | SHA-256 |
| --- | --- |
| `build/libs/meet-me-server-0.0.1-SNAPSHOT-plain.jar` | `669530428df2c0465096195e5284253321d0a2873ae5417dfd0c3ca7fb31d156` |
| `build/libs/meet-me-server-0.0.1-SNAPSHOT.jar` | `006c92c9dbfaf6a4b7cedbab26731e3b730a2228b22b8f73a53959a8ecc02f2a` |

이는 최종 로컬 artifact의 test code 제외 근거다. 실제 소유권 adapter가 없는 production 기동 성공 또는 배포 승인 근거가 아니다.

독립 `ClarificationOwnerDelegationPostgresTest`는 공통 port를 `@MockitoBean`으로 대체한다. port가 참여자를 반환하면 legacy guest cookie 없이 질문 GET 200을 허용하고, 유효한 합성 guest cookie가 있어도 port가 401 또는 403을 반환하면 fallback 없이 거절하며 본문에 원문/질문/선택지/모호성 사유가 없는지 검증한다. 정확한 room/전달 credential로 port를 호출하는지도 검사한다. 이 테스트가 반환하는 participant 자체는 기존 합성 guest 데이터이며 계정 token 또는 Bearer parser 검증이 아니다.

실제 adapter 전달 뒤에는 account owner 허용·타인 거부·guest/account 전환·invalid Bearer와 유효 guest cookie 동시 제공 시 401·동일 방 계정 participant 복원·답변 저장 및 owner GET까지 통합 검증해야 한다. `SaveSubmissionUseCase`와 `GetOwnSubmissionUseCase`의 실제 계정 통합도 이 포트 단독 mock으로 입증되지 않는다. owner delegation 3개 GREEN은 부모 세션 전달 및 `/tmp/issue107-owner-delegation-green.log`의 성공 종료로 확인했다. Gradle 실행과 최종 증거 수집은 부모 세션이 담당한다.

## 보강한 독립 검증

`ClarificationLifecyclePostgresTest.kt`의 고정 기대값은 다음과 같다.

1. 같은 source의 최초 proposal 본문과 question ID를 보존하고, CANCEL 후 같은 source 재전달로 질문을 되살리지 않는다.
2. 답변으로 생성한 version이 다시 모호해도 질문을 반복하지 않고 모호성 안전 제외 상태를 유지한다.
3. 답변한 원문을 다른 revision으로 다시 저장해도 반복 질문을 만들지 않는다.
4. 한 방의 동일 참여자에게 생성한 source 질문은 최대 3개이며 기존 수정 분석 3회 한도는 그대로다.
5. 활성 결과에서 모호성이 사라진 이전 question ID는 저장·취소 권한이 없다.
6. unknown 필드, null, 잘못된 scalar/array/object, 숫자 enum, 정수 필드의 문자열/실수/boolean, 필수 필드 누락은 400이고 쓰기가 없다.
7. DIRECT는 ECMAScript 경계만 trim하고 U+0085 및 내부 공백·줄바꿈을 보존한다.
8. 실제 HTTP로 만든 21명 합성 방에서 A와 DIRECT 모두 10,001 코드포인트를 거부하며, emoji를 포함한 정확한 10,000은 허용한다. 거부 시 질문 답변·head/version·추가 work가 없다.
9. 보존기간 정리는 답변 provenance/FK를 포함해 만료 방을 제거하고 새 방의 pending 질문을 보존한다.
10. native `/v3/api-docs`가 두 clarification 경로·요청 필수 필드/UUID/정수/enum/문자열·응답 필드·주요 문제 응답과 room support marker를 문서화한다. `MEETME_ISSUE107_OPENAPI_EXPORT`가 있으면 생성 응답 바이트를 그대로 내보낸다.

초기 `ClarificationHttpPostgresTest`의 16개는 소유권/Origin, 저장/취소, Unicode 500 경계, source CAS, 멱등 replay, 실제 PostgreSQL lock 경합, 실패 주입 롤백, HOST 원문·사유 비공개를 담당한다. 추가 lifecycle의 유효 RED는 `.tdd/red/issue-107-lifecycle.json`에 native marker/정수 타입/stale question 검증의 실패로 기록됐다. source 재검토에서 세 경계의 수정을 확인했고, 최종 전체 gate에서 provider 15개·HTTP 16개·lifecycle 10개 모두 통과했다. 결함 주입·전체 게이트 원본 확인 결과는 아래에 기록했다.

공통 소유권 위임 테스트 3개는 포트 결정의 우선권과 401/403 비공개 회귀를 별도로 담당한다. 실제 #109 adapter가 없는 상태의 성공은 격리 검증에 한정한다.

## 오라클 정정 이력

- 질문/답변은 room `state_version`을 증가시킨다. 타인 또는 CANCEL 전후 제출 내용 불변 비교에서 `state_version`만 제외했다. raw_text/revision/created_at 등 제출 필드는 그대로 검증한다.
- 기존 ADR-046에서 OPEN 라운드의 candidates/unapplied 조회는 409 `CANDIDATES_NOT_READY`다. 타인 모호성 비공개는 OPEN 이전 200에서 검사하고 OPEN에서는 기존 409를 유지한다.
- OpenAPI nullable 문자열은 string 또는 string/null type union을 모두 허용한다. 생성기 표현 방식 차이를 제품 결함으로 판정하지 않는다.
- 최종 formatter 이후 테스트 지문이 바뀌면 부모 세션이 RED를 재수집한다. 구현자가 기대값을 약화해서 GREEN을 만들지 않는다.

## 최종 독립 검토 판정

검토 범위에서 R1 이후 새로 확정한 구현 결함은 없다. R2는 그대로 제품 완료와 production 기동을 차단한다. 공통 resolver로의 위임과 synthetic test adapter의 artifact 분리는 의도한 책임 경계에 맞으며, 포트의 mock 성공을 실제 계정 인증 성공으로 확대하지 않는다.

서버 FI 6종은 모두 탐지 완료했다. 이 검토자는 `.tdd/verification/issue-107.json`, 여섯 원본 로그와 `/tmp/issue107-server-fault-status.log`를 읽어 각 실패가 컴파일·fixture·환경 오류가 아닌 행동 assertion 실패임을 확인했다. 기록의 source 5개·test 4개 SHA-256을 현재 파일과 다시 비교해 모두 일치하므로 원본 복원도 확인했다. 추가 Gradle 실행이나 production 수정은 하지 않았다.

| 주입한 결함 | 독립 테스트의 실제 탐지 |
| --- | --- |
| HOST에 타인 모호성 원문·사유 공개 | 비공개 HTTP 사례 1개 실패 |
| 답변 저장의 트랜잭션 제거 | provenance 실패 시 head/version 롤백 사례 1개 실패 |
| replay의 payload 비교 제거 | 동일 UUID·다른 body 충돌 사례 1개 실패 |
| 답변으로 생성한 version의 반복 질문 억제 제거 | 답변 lineage 사례 1개 실패 |
| AMBIGUOUS_INPUT 안전 제외 제거 | matching의 해당 사유 6개 실패, 기존 사유 대조군 유지 |
| 공급자 선택지 중복 거부 제거 | 서로 다른 전체 선택지 계약 사례 1개 실패 |

위 검토는 합의한 여섯 결함의 탐지력 근거다. 모든 가능한 결함을 검출한다는 뜻은 아니다. 피드백 보강 전 원본 복원 후 전체 실행은 719개 중 715 PASS·기존 SKIP 4·FAIL 0·ERROR 0, wrapper 500.747초·exit 0으로 완료됐다. 이 수치는 이전 검증 이력이다.

사용자의 `7-8시쯤` 피드백을 반영해 합성 fixture는 44개, 답변 시나리오는 7개로 보강됐다. 추가 `ClarificationReportedTimeRegressionTest` 8개는 양 공급자의 합성 응답에 대해 정확한 입력 구절, 근사 표현, 오전/오후·24시간·문맥, 상대 날짜, 자정, 불가 극성, 역전·동일·불가능한 시각의 안전 제외를 검증한다. 이 테스트의 소스와 JUnit XML을 직접 확인했다. 서버 main 코드를 바꾸거나 실제 모델을 호출한 검증은 아니며, 실모델이 `7-8시쯤`을 정확히 해석한다는 의미로 확대하지 않는다.

피드백 보강 뒤 최종 실제 wrapper `sh gradlew --no-daemon ktlintCheck assemble test`는 exit 0, 513.22초다. 현재 JUnit XML을 직접 합산한 결과는 **727개 중 723 PASS·기존 SKIP 4·FAIL 0·ERROR 0**으로 최종 JSON과 일치한다. clarification HTTP 16개·lifecycle 10개·ownership delegation 3개·provider 15개·matching safety 12개 및 새 피드백 회귀 8개가 모두 통과했다. plain/boot jar SHA-256은 위 표의 이전 검증값과 같고 test 대역 제외도 다시 확인했다.

필수 `python .codex/hooks/tdd_guard.py`는 최종 exit 0, 10.443초다. 보강 전 guard 10.906초는 이전 이력이며, guard의 UP-TO-DATE 결과를 별도 전체 테스트 실행으로 중복 집계하지 않는다. 실행 명령·시간·suite별 결과·artifact 지문은 `.codex/tdd-evidence/issue-107-final-gate.json`, wrapper 원본은 `/tmp/issue107-server-final-gate.log`, guard 원본은 `/tmp/issue107-server-final-guard.log`에 있다. 이 검토자는 새 Gradle 실행 없이 원본과 XML을 읽었다.

첫 전체 실행의 실패 원본은 `/workspace/shared/issue107/server-first-full-gate.tar.gz`에 보존돼 있다. archive XML에서 기존 migration 개수·최신 버전 기대 4개와 모호성 후보 oracle 1개, 총 5개 실패를 확인했다. migration 기대는 실제 catalog `V1~V10 + V13`의 11개·최신 13·V8 이후 3개에 맞춰 수정했고, 기존 `flyway.validate()` 및 원문·시각·동결 배치·attempt·확정·OPEN/CONSUMED 보존 assertion은 유지했다. V1~V10 파일은 HEAD와 byte 지문이 동일함을 별도로 확인했다. 최종 전체 실행에서 해당 기존 회귀도 모두 통과했다.

재개 지점은 #109 실제 adapter 전달 → 계정·Bearer·guest 동시 자격 증명 우선순위 및 저장/조회 통합 → 통합 source 기준 전체 gate 재실행 → 최종 artifact 경계 확인이다. 후속 commit·push·Draft PR·정확 SHA CI는 새 사용자 승인 범위이며 독립 검토자는 수행하지 않았다. 병합·배포·운영 DB·secret 변경·유료 모델 호출은 허용 범위에 포함하지 않는다.

## 확인한 설계 경계와 남은 근거

room 잠금 아래 소유자 확인 후 replay하고, source/current revision/OPEN round를 CAS 검증하며, 기존 저장 유스케이스와 provenance update를 같은 트랜잭션으로 묶는 방향은 계약과 일치한다. 원문과 수정본은 별도 immutable version이며 최초 source proposal은 보존한다. 실패 주입으로 이 트랜잭션 경계가 실제로 지켜지는지 확인한다.

provider는 기존 배치 응답에 nullable clarification만 추가하고 보완 생성용 호출을 만들지 않는다. proposal은 빈 conditions와 모호성 사유에만 허용하며 선택지 크기·substring·중복을 검증한다. 명시된 source reference_date/절대 날짜 안내, 불가·선호 보존 prompt와 fixture는 구조화 계약의 근거다. 실모델이 이 의미를 항상 지킨다는 증거는 아니다.

실제 모델의 자연어 정확성·운영 credential·공급자 비용/지연·운영 migration 적용은 미검증이다. 서버의 격리 HTTP/PostgreSQL/provider stub, native OpenAPI 및 전체 gate·합의한 결함 주입은 완료했다. 웹 타입·브라우저 검증은 별도 웹 검토 근거로 판단하며, 이 서버 검토만으로 대신하지 않는다. 제품 완료와 production 기동의 차단은 #109 인증 계약 실제 통합이 남아 있으므로 해제하지 않는다.

### 현재 해결 확인 범위

R1은 `answer`가 현재 활성 batch의 같은 source version을 여전히 모호한 rejection으로 가지고 있는지 확인하도록 수정했다. 독립 lifecycle의 stale 질문409 기대는 수정 후 통과했다. native schema의 strict JSON 정수와 room 지원 표지도 별도 실제 OpenAPI test를 통과했다. R2는 main의 guest-only resolver를 제거하고 공통 소유권 port와 src/test 전용 합성 대역으로 분리했다. 실제 #109 adapter가 없으므로 R2의 계정 통합/프로덕션 기동 차단은 해제하지 않는다.
