# Frontend implementation handoff

> **대상:** 현재 백엔드와 연결하는 프론트엔드 구현
>
> **계약 우선순위:** 실행 중인 `/v3/api-docs` > 이 문서 > `docs/PRD.md` > prototype HTML

이 문서는 API 필드와 응답 스키마를 복제하지 않는다. endpoint, DTO, nullable 여부, enum과 HTTP 응답은 반드시
Swagger/OpenAPI를 기준으로 생성하거나 확인한다. 이 문서는 화면 흐름과 브라우저 보안 경계처럼 스키마만으로
알기 어려운 구현 규칙만 설명한다.

계정·게스트 소유권은 [Architecture](ARCHITECTURE.md)의 현재 계정 계약을 따른다.
아래 기존 제출·polling 흐름은 LEGACY 방의 호환 계약이며 개인 검사 방에 그대로 적용하지 않는다.
PRE_SUBMIT_V1은 CLEAR accepted만 완료로 집계하고, PRE_SUBMIT_V2의 RECEIVED·보완·마감/공개 정책은
[V2 계약](EXPERIMENTAL_INPUT_EVIDENCE.md)을 따른다. 신규 방 protocol과 실제 OpenAPI를 먼저 확인한다.

## 공개 방 제출 진행 집계

공통 `RoomResponse`의 `submitted_participant_count`는 required/non-null integer(minimum 0)이다. 해당 room의 최신 제출 버전이 존재하는 고유 participant head 수이며 제출한 주최자·회원·게스트를 포함한다. 수정·재참여·claim·개인 목록 숨김으로 인원은 변하지 않고, 초안·precheck만 있는 참여자는 제외한다. 현재 수정 라운드의 재제출 수나 후보의 고정 분석 배치 수와 다를 수 있다. 공개 invite-code API는 집계 숫자만 추가하며 이름·개인 식별자·명단·조건 원문을 추가하지 않는다.

`expected_participants`는 nullable 자동 마감 목표(2~50명)다. 수동 마감·시간-only 자동 마감에서는 null이며 표시용 가입자/접속자 수나 정원이 아니다. 목표 없는 경우 임의 분모를 만들지 않고 목표를 초과한 집계도 자르지 않는다. 마감·보존·권한 정책은 그대로다. 실제 생성 계약은 `docs/openapi/account-room-history.openapi.json`, 배포 검증 projection은 `deploy/public-api-contract.json`에서 확인하며 원격 source SHA는 검증 소스 게시 후 Git/PR에서 확인한다. Web UI 구현과 배포는 별도 담당이며 이 서버 작업의 병합·운영 배포는 별도 승인 범위다.

## 1. API 문서 위치

- 로컬 Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- 로컬 OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- 운영 Swagger UI: `https://api.meet-me.co.kr/swagger-ui/index.html`
- 운영 OpenAPI JSON: `https://api.meet-me.co.kr/v3/api-docs`

Swagger가 이 문서나 prototype과 다르면 임의로 맞추지 말고 백엔드 계약 변경 여부를 확인한다.

## 2. 브라우저 세션과 요청 공통 규칙

- Google·Kakao 로그인 후 Access Token은 메모리에 두고 `Authorization: Bearer`로 보낸다. Refresh Token은 API 호스트 전용 `Secure HttpOnly; SameSite=Lax; Path=/api/auth` cookie로만 전달한다. token을 URL·브라우저 영구 저장소에 복사하지 않는다.
- 주최자는 계정으로 인증한다. 게스트 참여에는 `meet_me_guest`의 `Secure HttpOnly; SameSite=Lax; Path=/api` cookie와 서버가 검증한 소유권을 사용한다. 잘못된 Bearer·계정 권한 부족을 guest fallback으로 우회하지 않는다.
- 프론트엔드는 cookie 값을 읽거나 body, URL, `localStorage`, `sessionStorage`에 복사하지 않는다.
- 모든 API 요청은 cookie가 자동 전송되도록 `fetch`의 `credentials: "include"` 또는 동등한 설정을 사용한다.
- 운영 Origin은 정확히 `https://app.meet-me.co.kr`이다. wildcard CORS나 임의 Origin fallback을 가정하지 않는다.
- cookie를 잃거나 다른 브라우저·기기로 이동하면 기존 참여·주최자 권한을 자동 복구하지 않는다.
- 이름·초대 링크·participant UUID는 소유 증명이 아니다. 서버가 검증한 VERIFIED_GUEST만 본인 조회를 허용하고 REPLACE_ONLY는 읽기·HOST·claim 권한을 주지 않는다. 계정 연결은 로그인과 유효 소유 증명을 모두 갖춘 명시적 claim으로만 수행한다.
- refresh·로그아웃은 정확한 Origin 검사를 유지한다. 로그아웃·계정 변경은 보호 cache와 진행 중 form을 비우며 이전 세션의 늦은 응답을 폐기한다. 내 모임 목록은 [현재 목록 계약](ACCOUNT_ROOM_HISTORY_API.md)을 따른다.
- 표시 문구는 `detail`을 사용할 수 있지만 분기 로직은 locale과 무관한 RFC 9457 `code`를 사용한다.
- 요청에는 `Accept-Language: ko-KR`을 보내고 모든 절대 시각은 ISO 8601 offset 형식으로 처리한다.

## 3. 핵심 화면 흐름

### 방 생성

1. `POST /api/rooms`
2. 성공한 응답의 `invite_code`를 기준으로 방 URL을 만든다.
3. 응답 body나 JavaScript에서 guest credential을 찾지 않는다. 브라우저가 `Set-Cookie`를 처리한다.
4. `viewer.role == HOST`일 때만 마감·재분석·후보 확정 UI를 노출한다.

### 공유 링크 참여

1. `GET /api/rooms/{inviteCode}`로 공개 방 정보를 조회한다.
2. `viewer.joined == false`이면 표시 이름을 받아 `POST /api/rooms/{inviteCode}/participants`를 호출한다.
3. 이미 참여한 브라우저면 같은 참여자를 반환하므로 중복 참여 화면을 만들지 않는다.

### 조건 제출과 복원

1. 현재 참여자의 기존 입력 복원은 `GET /api/rooms/{inviteCode}/submission`을 사용한다.
2. 제출과 수정은 모두 `PUT /api/rooms/{inviteCode}/submission`을 사용한다.
3. 자연어는 필수다. ECMAScript String.trim 후 Unicode 코드포인트로 길이를 검증한다. UTF-16 textarea maxLength를 500자로 간주하지 않는다. 정확한 집합은 PRD Rule 1을 따르며 U+FEFF는 제거하고 U+0085는 보존한다.
4. 입력용 타임테이블·수동 상태·복원·전송과 deprecated 응답 배열 사용을 제거한다. 날짜 범위 입력과 읽기 전용 후보·확정 time_ranges는 유지한다.
5. GET legacy null은 빈 입력으로 안전하게 복원하고 자연어 입력 필요를 안내한다. 로드만으로 PUT하지 않으며 collecting에서 명시적으로 저장할 때 새 revision을 만든다. nonempty 수동 요청의 지원 종료 오류는 자연어 재입력으로 안내한다. 타인 입력은 조회하지 않는다.

### 입력 마감

- 자동 마감은 expected participants 또는 deadline 조건으로 서버가 수행한다.
- 수동 마감은 HOST만 `POST /api/rooms/{inviteCode}/close`를 호출한다.
- `EARLY_CLOSE_CONFIRMATION_REQUIRED`이면 서버가 반환한 현재·목표 인원과 deadline을 보여주고 사용자 재확인 뒤
  `confirm_early=true`로 한 번 더 호출한다.

## 4. Polling 상태 전이

후보 endpoint를 Polling하지 않는다. 입력이 마감된 뒤 `GET /api/rooms/{inviteCode}`의 `public_status`를
2초 간격으로 조회한다. 네트워크 오류가 연속되면 최대 5초까지 backoff하고 화면 이탈 시 요청을 취소한다.

| `public_status` | 프론트엔드 동작 |
| --- | --- |
| `COLLECTING` | 제출 화면 유지. 입력 마감 전 상태다. |
| `ANALYZING` | 분석 중 화면을 표시하고 Polling을 계속한다. |
| `INSUFFICIENT_PARTICIPANTS` | Polling 중단. 후보를 요청하지 않고 최소 인원 부족을 표시한다. |
| `ANALYSIS_DELAYED` | Polling 중단. 입력 저장 완료와 분석 지연을 표시한다. HOST에게만 재분석 버튼을 제공한다. |
| `NO_MATCH` | Polling 중단. 정상적인 빈 결과로 표시한다. |
| `READY` | Polling 중단 후 후보를 조회한다. |
| `READY_WITH_WARNINGS` | Polling 중단 후 후보를 조회하고 부분 결과임을 표시한다. HOST에게만 미반영 입력 조회 UI를 제공한다. |
| `CONFIRMED` | Polling 중단 후 확정 결과를 조회한다. |

HOST의 재분석은 `POST /api/rooms/{inviteCode}/analysis/retry`이며 `202` 이후 다시 Polling을 시작한다. 참가자에게
재입력을 요구하지 않는다.

## 5. 후보와 확정

### 현재 다양한 시간 추천

- 방의 `recommendation_protocol`이 정확히 `diverse-time-v1`이면 `/recommendations`의 최대3개 시간안을 A/B/C 순서로 표시한다. 표시 순서는 대면·온라인·부분 참석의 종류를 뜻하지 않는다. 같은 창의 방식·장소·참석 조합은 variant다. 알 수 없는 protocol에서 새 확정을 활성화하지 않는다.
- 전체 대안은 같은 `analysis_id`의 `/recommendations/alternatives`로 조회한다. `has_alternatives`와 불투명 `next_cursor`를 구분하고 다른 분석의 cursor를 재사용하지 않는다. `STALE_ANALYSIS`이면 방·primary를 다시 읽고 이전 선택을 비운다.
- HOST는 `/recommendations/{optionId}/confirmation`에 조회한 `analysis_id`, `variant_id`와 창 안에서 고른 실제 `start_at`, `end_at`을 보낸다. 시작은 포함·끝은 배타적이며 PostgreSQL 마이크로초 정밀도까지만 허용한다. 같은 전체 선택만 멱등이고 다른 확정은 충돌이다. 신규 분석을 legacy candidate 확정 URL로 보내지 않는다.
- result의 typed `selection`과 실제 선택 구간을 표시한다. `candidate.plan_type`은 legacy 호환 의미이며 새 A/B/C 라벨로 사용하지 않는다. 자정 넘김은 양쪽 날짜를 보존한다.
- 조건부 참석 의미를 표현하는 클라이언트는 candidates·추천 primary/alternatives·result GET과 두 confirmation POST에 `attendance_support=conditional-v1`을 보낸다. `attendance_confirmed=false`를 확정 참석으로 바꾸거나 숨기지 않는다. 미지원 요청의 `409 CLIENT_UPDATE_REQUIRED`는 새로고침/업데이트 안내로 처리한다.

### LEGACY 후보 호환

- `READY` 또는 `READY_WITH_WARNINGS`에서 `GET /api/rooms/{inviteCode}/candidates`를 호출한다.
- 후보는 Plan A/B/C 중 서버가 만든 항목만 표시하며 프론트엔드에서 다시 점수화하거나 정렬하지 않는다.
- A는 전원 대면, B는 전원 비대면, C는 부분 참석 후보 유형이다. 각 유형은 최대 한 카드이며 가능한 시간이 여러 개여도 `time_ranges`와 `summary`에 모두 묶인다. 실제 후보가 한 개이면 한 카드만 표시한다. 요약의 `24:00`은 표시 날짜 다음날 자정의 배타적 끝점이다.
- 대면 후보의 `place.display_name`은 Gemini가 구조화한 공통 근방명이다. 제출 MVP는 지도를 사용하지 않으므로 `latitude`와 `longitude`는 `null`이며 프론트엔드는 좌표나 핀을 요구하지 않는다.
- READY_WITH_WARNINGS 또는 NO_MATCH의 HOST는 미반영 endpoint를 사용할 수 있다. legacy 슬롯 전용은 nullable raw_text와 LEGACY_MANUAL_ONLY_UNSUPPORTED로 표시하며 타인 정상 입력·수동 구간은 공개하지 않는다.
- `UNSUPPORTED_CONDITIONAL_CONSTRAINT`는 시간별 장소처럼 현재 스키마로 반영할 수 없는 결합 조건, `AMBIGUOUS_TIME_CONSTRAINT`는 임의 시간 경계를 만들 수 없어 반영하지 않은 시간 조건이다. 기존 string `reason` 계약을 사용하며 새 필드는 없다. 서버는 해당 참여자를 전체 인원에 유지하고 가능 시간을 가정하지 않는다. 이 사유는 정상 반영이나 `COMPLETE`로 설명하지 않으며 기존 `PARTIAL`·HOST 미반영 조회 흐름을 사용한다. 웹에서 사유별 한국어 안내를 추가하는 작업은 별도 저장소 담당 범위다.
- 후보 확정은 HOST만 `POST /api/rooms/{inviteCode}/candidates/{candidateId}/confirmation`을 호출한다.
- 같은 후보 재확정은 멱등하지만 다른 후보가 이미 확정된 `409`에서는 현재 확정 결과를 다시 조회한다.
- 확정 결과는 참여자 모두 `GET /api/rooms/{inviteCode}/result`로 조회한다.

## 6. 오류 처리

- `400`: 필드 오류 또는 입력 계약 위반. `field_errors`, `actual`, `maximum`, `reason`이 있으면 입력 UI에 반영한다.
- `401`: guest cookie 누락·만료·위조. 기존 권한을 복구됐다고 표시하지 않는다.
- `403`: 현재 참여자 또는 HOST 권한 부족. 공유 링크만으로 권한을 얻었다고 가정하지 않는다.
- `404`: 방·제출·후보·확정 결과 없음. 리소스 종류는 `code`로 구분한다.
- `409`: 현재 상태와 명령의 충돌. 조기 마감 확인, 후보 준비 전, 이미 다른 후보 확정 등을 `code`로 분기한다.
- `429`: `retry_after_seconds` 또는 `Retry-After` 뒤에만 재시도한다. 자동 무한 재시도하지 않는다.
- `503`: 비용 유발 명령의 제한 저장소 장애. 사용자에게 잠시 후 재시도를 안내한다.

## 7. 현재 범위에 포함하지 않는 기능

- Google Calendar 연결
- 이메일·비밀번호 계정
- 장소 전용 검색 입력 또는 지도 좌표 선택
- 예상 소요 시간 입력
- SSE·WebSocket 연결
- cookie 원문을 보여주는 디버그 UI

### 추천 variant의 공개 이름

추천 variant의 `available_participant_names`는 고정 분석 배치 중 해당 조합에 포함된 참여자의 공개 표시 이름 목록이다. 전원 참석과 대안 페이지에도 제공하며 동명이인은 별도 항목으로 유지한다. `attendance_confirmed=false`이면 시간 조건 일치 명단으로 표시하고 장소·이동 및 실제 참석 미확정 설명을 유지한다. `excluded_participant_names`는 해당 조합에서 제외된 명단이며 불참 확정이나 해석 실패 사유를 뜻하지 않는다. 구 서버에서 새 이름 필드가 없으면 명단을 추정하지 않는다.

## 8. 완료 검증

- Swagger의 모든 공개 endpoint와 주요 오류 응답이 클라이언트 타입에 반영됐는지 확인한다.
- 동일 브라우저, 다른 브라우저, cookie 삭제·위조, 공유 링크만 가진 사용자의 권한 차이를 E2E로 검증한다.
- `ANALYZING` Polling부터 `READY`, `READY_WITH_WARNINGS`, `NO_MATCH`, `ANALYSIS_DELAYED`, `CONFIRMED` 화면을 각각 검증한다.
- 실제 배포 Origin에서 credential CORS와 `Secure HttpOnly` cookie가 동작하는지 확인한다.
- prototype HTML은 시각·상호작용 참고 자료이며, 데이터 계약은 Swagger와 이 문서를 따른다.

### 자연어 전환의 배포 호환

- 현 서버와 신 웹의 자연어 전용 요청·응답은 운영 GET과 baseline 로컬 fixture로 확인하고, 가능하면 웹 선배포·실제 확인 후 서버를 배포한다. 운영 쓰기 테스트는 하지 않는다.
- 신 서버는 구 웹의 nonempty 수동 요청을 400으로 거부하며 detail에 페이지 새로고침 후 자연어 재입력을 명시한다. 조용히 무시하지 않는다.
- 캐시와 오래 열린 구 번들은 순서만으로 해결되지 않는다. 구 화면 잔존과 오류 문구를 별도로 확인한다.
- 신 웹은 전송 전에 ECMAScript trim을 적용한다. 현 서버의 Kotlin trim과 다른 U+001C~001F 처리 등은 전환 중 호환 차이로 분리 보고하며 계약을 임의로 바꾸지 않는다.
