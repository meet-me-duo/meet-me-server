# Issue #107 독립 테스트 설계

> 이 문서는 기존 사후 배치/수정 라운드 구현과 정확 PR 이력의 계약이다. 신규 PRE_SUBMIT_V1 기본 흐름은 ADR-053 및 ISSUE_107_PRE_SUBMIT_DESIGN/TEST_SPEC을 따른다. 기존 LEGACY 회귀·증거를 새 제출 전 기능의 완료 근거로 사용하지 않는다.

작성일: 2026-10-17 UTC. 역할: 테스트 설계자. 구현 코드를 읽거나 기대값의 근거로 삼지 않았다.

## 근거와 범위

- 사용자 위임: #107 및 관련 웹. 명확한 입력은 추가 질문·추가 AI 호출 0. 마감 후 기존 배치에서 결과에 영향을 주는 모호성만 감지하고 본인에게만 선택형 질문을 제공한다. 답변은 새 revision, HOST가 기존 수정 라운드에서 명시적으로 재분석한다. 60초·수정 분석 3회 한도는 유지한다. 운영·실모델 호출, push·PR·병합·배포는 범위 밖이다.
- [Issue #107](https://github.com/meet-me-duo/meet-me-server/issues/107)의 부모 세션 전달 공개 계약: A/B/직접 입력의 세 선택지, 원문과 답변 관계 보존, 미응답·취소·여전히 모호한 답변의 안전 처리, 반복 피로 최소화.
- `PRD.md`: FR-002C, FR-005, FR-007A, FR-008~008E, FR-009, FR-013 및 Rule 1.
- `ADR.md`: ADR-035 시간대, ADR-045 안전 제외, ADR-046 수정 라운드·CAS·멱등성, ADR-048 불가와 선호 분리, ADR-049 60초/공급자 상한.
- `.agents/rules/development.md`: 위험도 기반 역할 분리, 공개 계약 테스트, 독립 리뷰·의도적 결함 주입. `.agents/rules/architecture.md`: 실제 PostgreSQL 통합 검증.

기존 부분 결과 HOST 원문 공개 규정과 충돌하는 #107 질문·답변·모호성 사유에는 최신 사용자 지시인 **본인만 접근·타인 원문과 사유 비공개**를 우선한다. 인증 구현은 #109 계약을 소비하며 새 자격 증명 체계를 만들지 않는다. 자연어 의미 fixture는 공급자 평가 oracle이다. 서버가 원문 정규식으로 검증된 공급자 결과를 덮어쓰는 구현 근거가 아니다.

## 고정 공개 계약

부모 세션이 제안한 다음 계약을 테스트 대상으로 고정한다. 최종 필드명·HTTP 오류 코드는 native OpenAPI와 동기화해야 한다.

- `GET /api/rooms/{inviteCode}/submission/clarifications`: 인증된 본인의 질문·답변 상태만 반환한다. 참가자 ID를 바꾸어 타인을 조회하는 통로를 만들지 않는다.
- `POST /api/rooms/{inviteCode}/submission/clarifications/{questionId}/answer`: `request_id`, `revision_round_id`, `expected_revision`, `choice: A | B | DIRECT | CANCEL`, 선택 시에만 `direct_text`를 받는다.
- A/B는 원문의 다른 명확한 조건을 모두 보존한 전체 수정본이다. DIRECT는 전체 원문 대체이며 화면에 이 의미를 표시한다. CANCEL은 세 해석 선택지와 별개의 취소 행동이다.
- 관계는 `source_version -> question -> answer -> new_version`으로 보존한다. 질문은 immutable source version·source analysis 및 소유자에 묶인다. 답변 저장은 현재 OPEN 수정 라운드에서만 가능하다.
- 한 revision당 질문 최대 1개. 동일 원문과 동일 확인 이력에 대한 반복 질문은 억제한다. 새로고침·동일 배치 재전달·기술 재시도·HOST force reparse가 반복 질문을 우회하지 못한다.
- A/B 전체 수정본의 상대 날짜는 원본 버전 생성일을 기준으로 절대 날짜화한다. DIRECT의 새 상대 날짜는 새 revision 생성일 기준이다. 답변 지연으로 기존 '내일'의 의미가 바뀌지 않아야 한다.

## 테스트 명세와 oracle

| ID | 설정·행동 | 공개 결과와 불변식 |
| --- | --- | --- |
| I107-01 | 오전/오후가 명확한 입력, 명확한 자정·부정·선호·문맥 fixture를 최초 저장하고 마감한다 | 저장 시 공급자 호출 0. 기존 배치 호출만 사용. 질문 0, 보완 생성용 추가 호출 0. 명확한 입력을 재질문하지 않는다. |
| I107-02 | '7~9시'처럼 서로 다른 두 해석이 후보를 바꾸는 입력을 마감한다 | 기존 배치 결과로 본인 질문 하나. A/B 각각 구체적이고 다른 해석이며 직접 입력이 있다. 질문 생성이 별도 provider 호출·worker 재시도·분석 작업을 만들지 않는다. 안전 미반영 상태를 임의 성공으로 올리지 않는다. |
| I107-03 | 같은 숫자라도 명시 오전/오후 또는 유일하고 호환되는 부모 종료시각이 있는 문맥 | 문장 조각만 보고 모호로 바꾸지 않는다. '매일 18~22시, 내일은 19시부터'는 내일 19~22시. 상충·복수 부모 또는 부모 없는 열린 끝은 임의 완성하지 않는다. |
| I107-04 | 본인이 OPEN 수정 라운드에서 A/B를 선택한다 | 원본과 질문은 불변. 새 전체 원문·answer provenance·새 version/head가 원자 저장된다. 참가자 수·closed_at·기존 batch는 유지. 분석/Outbox/provider 호출 0. |
| I107-05 | 다음 날/다다음 날 답변하거나 같은 source를 재시도한다 | source '내일'은 source created_at의 KST 날짜 기준 유지. A/B는 절대 날짜로 보존. DIRECT '내일'은 새 created_at 기준. UTC 날짜로 판정하지 않는다. |
| I107-06 | 무응답, 취소, 여전히 모호한 DIRECT, 확인 완료 후 동일 모호 원문 재분석 | 무응답·취소는 원본 유지, 새 해석·revision·분석 없음. 모호 DIRECT는 새 원문을 보존할 수 있으나 임의 확정 금지. 동일 원문+확인 이력 질문 반복 금지, 자동 재분석 금지. 새 의미가 있는 수정은 새 원문 평가 가능. |
| I107-07 | DIRECT 입력 및 조작된 A/B/enum 요청 | ECMAScript trim 후 1~500 Unicode 코드포인트, 내부 공백 보존, 초과 자르기 금지, 방 합계 10,000 적용. 빈 값·잘못된 choice·필요 direct_text 누락·알 수 없는 선택 ID·다른 question/room의 option·A/B에 임의 replacement 주입 거부. 실패 시 어떤 head·revision·응답 기록·quota도 변경하지 않는다. |
| I107-08 | 같은 request_id/payload 재전송, 같은 ID 다른 payload, 두 탭 동시 답변 | 동일 요청 재사용 시 최초 operation outcome 보존 및 version 1개. 다른 payload는 conflict. 같은 expected_revision에서 다른 두 답변 중 최대 하나 성공. CAS 실패 시 유실 저장·중복 답변·질문 완료 오표시 없음. |
| I107-09 | 오래된 source question 응답과 일반 본인 PUT, HOST 분석·확정과 답변이 경합한다 | 질문 source와 현재 head·round를 다시 확인. 오래된 응답은 최신 원문을 덮어쓰지 않는다. 분석과 답변 중 선형화된 한 순서만 성립하고 frozen batch는 정확한 immutable version을 가리킨다. CONSUMED/다른 round/확정 후 답변 거부. |
| I107-10 | HOST·다른 참가자·초대 링크만 가진 익명·다른 방 question ID로 GET/POST | 소유권 없는 질문·원문·선택지·답변·모호 사유가 body, error, room, 미반영 조회, 로그에 노출되지 않는다. HOST 권한도 타인 질문 접근을 허용하지 않는다. 인증 구현은 기존/#109 경계를 사용한다. |
| I107-11 | 질문 표시 후 reload, 저장 성공 응답 유실 후 reload, 늦은 GET, viewer 전환/401/403 | 서버 기준 질문/답변/현재 revision으로 복원하고 읽기만으로 쓰지 않는다. 낮은 state_version/generation 응답이 새 화면을 덮어쓰지 않는다. 저장 전 draft와 저장 성공을 구분한다. 본인 원문 캐시는 viewer/room별 분리하고 소유권 변경 시 제거한다. |
| I107-12 | HOST reopen → 답변 저장 → HOST 명시 analysis, 새 수정 분석 3회 후 재시도 | 참가자 답변만으로 분석 안 함. HOST 명시 분석만 새 batch/run/Outbox 생성. 기존 correction count/재사용/멱등성 우선순위를 유지. 3회 소진 뒤 저장은 가능하나 새 분석은 기존 409. 실패한 분석 count 환불이나 질문 전용 quota 초기화 없음. |
| I107-13 | 기술 재시도·Luna fallback·Outbox 재전달·프로세스 재시작·60초 경계 | 기존 단일60초, Gemini최대4/Luna최대1 및 30/27/3 배분 유지. 모호성은 기술 실패가 아니므로 fallback/추가AI 호출 사유가 아니다. 질문·답변 조회/저장이 deadline·attempt count를 리셋하지 않는다. deadline 후 질문/후보의 늦은 게시 차단. |
| I107-14 | 계약 생성·브라우저 전체 흐름 | native OpenAPI에 성공·주요 거부 응답과 모든 DTO 제약. 지원 capability 없으면 새 UI 숨김. 본인만 질문, 명시 저장, HOST 명시 재분석, 새로고침, 취소, conflict·권한 오류의 draft 보존·한국어 안내를 브라우저에서 검증. |

### 입력 경계

- 500/501은 UTF-16 길이가 아닌 Unicode 코드포인트로 검증한다. emoji 500개 허용/501개 거부, 조합 문자 코드포인트를 임의 축약하지 않는다.
- `U+FEFF`, `U+3000`, NBSP 경계 trim을 검증한다. `U+0085`는 ECMAScript trim 대상이 아니며 내부 줄바꿈과 공백은 보존한다.
- 질문 A/B도 DIRECT와 같은 전체 원문·배치 길이 검증을 거친다. 서버가 발행한 option이라는 이유로 우회하지 않는다.
- 날짜별 '24:00' 종료와 다음날 '00:00'을 보존하며 같은 날의 0분 창으로 바꾸지 않는다. 불가를 가능으로 뒤집거나 선호를 필수로 바꾸는 선택지는 거부한다.
- 상충 문장이나 서로 다른 조건을 임의 삭제하지 않는다. A/B가 두 안전한 전체 해석을 만들 수 없으면 질문을 만들기 위해 사실을 꾸며내지 않는다. DIRECT와 안전 미반영을 안내한다.

## Provider 계약의 독립 테스트 고정

`ClarificationProviderContractTest`는 위 의미 oracle과 별도로 부모 세션에서 고정한 공개 공급자 계약을 검증한다. `StructuredSubmissionResult.clarification`은 optional `ClarificationProposal(excerpt, question, optionA, optionB)`다. v3 JSON의 `clarification`은 live schema에서 required+nullable이지만, 기존 v2/v3 저장·mock 응답의 absent는 additive reader 호환으로 null 처리한다. 기존 rejection_code strict 검증은 완화하지 않는다.

명시 proposal은 `AMBIGUOUS_TIME_CONSTRAINT` 또는 `AMBIGUOUS_INPUT`에만 허용하며 conditions는 비어야 한다. excerpt는 원문의 정확한 substring 1~200 코드포인트, 질문은 trim 후 1~160, 두 전체 원문 대체 option은 trim 후 각각 1~500이며 서로 같거나 원문과 같을 수 없다. unknown/missing/type 오류는 전체 INVALID_RESPONSE, 추가 기술 재시도 금지다. Gemini/Luna의 공통 결과·schema·prompt 의미, Unicode 경계·null/absent 호환을 검사한다. 테스트는 실행 전에 고정하며 변경 시 RED를 다시 수집한다.

## Fixture 사용법

`fixtures/issue-107-ambiguities.json`은 합성 한국어 입력, 불변 source created_at, KST 기준일, 기대 분류(`CLEAR`/`AMBIGUOUS`), 보존할 의미를 제공한다. `CLEAR`는 지원 가능한 의미가 유일하다는 뜻이며 후보가 반드시 존재한다는 뜻이 아니다. 모호한 장소·미지원 조건도 원문과 사유를 타인에게 공개하지 않는다.

분류 oracle은 실제 모델 평가를 위한 기대값이다. 격리 unit/contract 테스트에서는 provider stub이 이 분류와 구조화 결과를 반환하도록 하여 질문의 보존·인가·흐름을 독립 검증한다. stub 통과로 실제 자연어 의미 정확성을 주장하지 않는다. 모델 평가는 이번 작업 범위에 없고 비용 승인 없이 호출하지 않는다.

## 실제 보고 표현과 시간 명세 대조표 (2026-10-17 추가)

사용자가 보고한 실제 표현은 **`7-8시쯤`**이다. 기존 25개 fixture에는 이 하이픈·축약·근사 표현의 조합이 없었으므로 T01~T19를 추가해 총44개 분류 oracle과 기존7개 답변 시나리오를 보존한다. 기존25개의 기대값은 변경하지 않았다. 새 기대값은 사용자 지시와 PRD FR-008D의 명시 시각·문맥·불가 우선·임의 경계 생성 금지에서 도출했다. parser 구현을 보고 분류 oracle을 만들지 않았다.

모든 행의 `CLEAR`는 질문0·추가 AI0이다. `AMBIGUOUS`는 기술 파싱 실패와 구분되는 `AMBIGUOUS_TIME_CONSTRAINT` 또는 `AMBIGUOUS_INPUT`이며 빈 가용 시간으로 안전 제외한다. 본인에게만 질문을 제시할 수 있고, 안전한 전체 A/B를 만들 수 없으면 질문을 꾸며내지 않는다. `쯤`을 ±15분·±30분 또는 새로운 숫자 경계로 바꾸지 않는다. A/B에 `쯤`을 보존했다고 경계의 모호성까지 해소된 것으로 판정하지 않으며, 답변은 새 revision으로 저장한 뒤 HOST의 명시적 재분석에서 다시 평가한다.

| 조건 | 기존 oracle | 추가 oracle·정확 표현 | 기대와 보존할 경계 |
| --- | --- | --- | --- |
| 실제 보고·하이픈·근사 | A01·A05·A06 | T01 `7-8시쯤`, T18 `오전 7-8시쯤 가능` | T01 AM/PM+근사 미확정. T18 오전 재질문 금지, 근사 경계만 미확정. 수치 허용오차 발명 금지. |
| AM/PM 누락·물결표 | A01·A11 | T02 `7~8시` | AMBIGUOUS. 맥락 없는 7을 저녁으로 고정하지 않는다. |
| 명시 오전·저녁·24시간제 | C01·C02·C09 | T03 `오전 7~8시`, T04 `저녁 7~8시`, T05 `19~20시` | CLEAR. 07~08 또는19~20을 그대로 보존한다. |
| 오늘·내일 | C03·C13 | T06 `오늘 19~20시 가능`, T07 `내일 오전 7~8시 가능` | source KST 기준일2026-10-17에서 각각17일·18일. 같은 version의 retry로 이동 금지. |
| 다음주·명시 월요일 | 상대 날짜 계약 | T08 `다음주 월요일 저녁 7~8시 가능` | CLEAR. 월요일 시작 주와 명시 요일에 따라2026-10-19. 쓰지 않은 다른 날짜 생성 금지. |
| 다음주·기간 미확정 | A02·A11 | T09 `다음주 7~8시 가능` | AMBIGUOUS. AM/PM 불명확 상태에서 임의 월요일·단일 날짜를 만들지 않는다. |
| 자정 전후·배타 종료 | C04·C05 | T10 `오늘 23시부터 내일 0시까지 가능`, T11 `내일 0~1시 가능` | CLEAR. 첫날24:00 종료와 다음날00:00 시작, 날짜 경계를 보존한다. |
| 역전 범위 | A03 열린 문맥 | T12 `오전 8~7시 가능`, T13 `20~19시 불가` | AMBIGUOUS. 자동 swap·밤샘·다음날 종료 추정 금지. 불가 극성 보존. |
| 가능·불가 | C06·C12·A04 | T14 `7~8시 불가`, T15 `저녁 7~8시 불가` | T14 미확정, T15 CLEAR UNAVAILABLE19~20. 부정 때문에 명시 문맥을 잃지 않는다. |
| 문맥 확정·미확정 | C08·C09·C14·A07 | T16 `저녁에만 가능해요. 7~8시요`, T17 `오전이나 저녁 중 아직 정하지 못했어요. 7~8시요` | T16 CLEAR. T17 AMBIGUOUS이며 미결정을 임의 OR 가능 대안으로 확정하지 않는다. |
| 선호 분리 | C07·A10 | T19 `19~20시 가능하고 19시30분~20시를 선호해요` | CLEAR. 하드19~20·선호19:30~20을 분리하고 나머지 가능한 대안 보존. |

새 `ClarificationReportedTimeRegressionTest.kt`는 필요하다. 기존15개 provider 계약과12개 매칭 안전 제외는 보고된 원문의 정확한 표현을 포함하지 않았기 때문이다. 별도8개 테스트가 synthetic provider 응답의 다음 공개 동작을 고정한다: 원문 `7-8시쯤`의 모호성 사유·proposal 보존, 짧은 AM/PM 미확정과 부정 보존, 명확 시각·본인 문맥의 null 질문, source 날짜·명시 다음주 월요일 보존, 자정, 명시 불가, 역전·동일·불가능한 hard 시각의 안전 거부, 안전한 A/B가 없는 미해소 결과의 null 질문.

테스트가 파서에 공급하는 날짜·분류·시각은 **가정한 모델 응답**이다. 예를 들어 `오전 7~8시`에 한 날짜의 TIME_WINDOW를 공급하는 테스트는 해당 날짜가 자연어 원문의 전체 해석이라는 주장이 아니라, 공급된 유효 시각과 null 질문을 서버가 보존한다는 계약 검증이다. 테스트는 모델이 원문을 제대로 분류했는지, `쯤`을 항상 안전하게 다루는지, source 날짜나 명시 요일을 스스로 정확히 계산하는지 검증하지 않는다. CLEAR의 질문0·추가AI0은 제품 기대이며 synthetic parser 테스트만으로 production 공급자 호출 수까지 증명하지 않는다. 실제 모델·운영·비용 발생 호출 없이 의미 정확성 한계는 그대로 남는다.

기존15/12 테스트와 main 코드는 이 보강에서 변경하지 않는다. 새 테스트 실행은 부모 세션이 전담한다. 기존 최종719건 gate는 새8개 테스트를 포함하지 않으므로 이 보강의 컴파일·GREEN·새 전체 gate 여부를 별도 명령/결과로 기록해야 한다.

## 실행 계층과 RED 근거

1. 순수 계약/도메인: fixture 보존, 선택 검증, 반복 억제, source 날짜와 새 날짜, CANCEL 불변식.
2. Application/adapter: provider spy·고정 clock·fake outbound를 사용하여 저장/질문 추가AI0, 명시 분석, 권한과 오류를 검증한다. private 메서드 호출 순서를 oracle로 삼지 않는다.
3. 실제 PostgreSQL Testcontainers: 동시 두 connection과 barrier로 CAS, 저장/분석 경합, FK·중복 유일성, 트랜잭션 중단·restart 후 멱등 replay와 복원 검증. H2나 메모리 fake로 대체 완료하지 않는다.
4. Native OpenAPI 계약: 생성 schema와 웹 타입 정합, 잘못된 enum/unknown/type/missing field, 실제 response status 비교.
5. 브라우저: API mock 상태 전이와 격리 로컬 서버 연결을 구분해 기록한다. 데스크톱/모바일에서 본인 질문만 표시, 저장·reload·두 탭 conflict·취소·지연·한도·권한 확인.
6. 관련 회귀 후 `ktlintCheck`, `assemble`, `test`, 웹의 저장소 지정 lint/typecheck/test/build 및 API generated diff gate. 환경 미지원은 실패/미실행으로 기록하고 완료 처리하지 않는다.

구현 전 컴파일 가능한 skeleton에서 요구사항 부재로 실패한 RED를 수집한다. `.tdd/red/issue-107*.json`에 source revision·테스트 fingerprint·실행명령·실패 테스트·예상 원인·시각만 추적하고 원본은 `.codex/tdd-evidence/` 로컬 로그에 보존한다. 기대값/테스트 변경 시 RED를 다시 확인한다.

## 의도적 결함 주입과 독립 최종 리뷰

실제 배포·외부 공급자를 쓰지 않고 임시 작업 복제본 또는 테스트 전용 fault seam으로 한 번에 한 결함을 주입한다. 원본 구현에 결함을 남기지 않는다.

| 결함 | 반드시 실패할 명세 |
| --- | --- |
| 소유자 검사를 우회하거나 HOST에게 타인 질문/사유를 노출 | I107-10, I107-14 |
| expected_revision 또는 source/round 비교를 무시 | I107-08, I107-09 |
| idempotency key를 무시하고 매번 새 version 생성 | I107-04, I107-08 |
| 답변 저장 시 자동 분석/추가 provider 호출 | I107-01, I107-04, I107-12, I107-13 |
| A/B의 '내일'을 답변일 기준으로 이동 | I107-05 |
| CANCEL/모호 답변을 성공 해석으로 승격 | I107-06 |
| 동일 모호 원문마다 질문/분석 count 초기화 | I107-06, I107-12, I107-13 |
| 불가 극성 반전·선호 하드화·자정 종료 소실 | I107-01, I107-03, fixture 보존 검증 |
| 선택 검증을 우회하거나 초과 직접 입력을 자름 | I107-07 |
| stale GET이 최신 답변 화면을 덮어씀 | I107-11 |

결과는 mutation ID, 주입 위치, 실행 테스트, expected detection, 실제 실패/실행 결과, 원복 확인, source/test fingerprint로 기록한다. 최종 검토자는 구현자와 분리하고 이 명세의 누락·오라클 약화·양성/음성 대조군을 검토한다. mutation이 검출되지 않으면 원인을 밝히고 테스트 설계로 되돌아간다.

## 완료와 잔여 검증의 구분

제품 완료는 서버·웹 구현, 공개 계약, 실제 PostgreSQL 경합, 전체 관련 gate, 독립 리뷰와 결함 탐지 증거가 모두 있을 때만 보고한다. mock 브라우저와 stub 공급자 결과는 격리 흐름 검증이며 실모델 의미 정확성·운영 소유권/배포·실사용 비용을 입증하지 않는다. 운영 호출·실모델 평가·push·PR·병합·배포는 수행하지 않는다. 미실행 gate와 충돌 파일 통합 대기는 blocker로 즉시 보고하며 resume용 branch/source SHA·명령·로그 경로를 남긴다.
