# Frontend implementation handoff

> **대상:** Wanted 제출 MVP 프론트엔드 프로젝트의 구현 AI
>
> **계약 우선순위:** 실행 중인 `/v3/api-docs` > 이 문서 > `docs/PRD.md` > prototype HTML

이 문서는 API 필드와 응답 스키마를 복제하지 않는다. endpoint, DTO, nullable 여부, enum과 HTTP 응답은 반드시
Swagger/OpenAPI를 기준으로 생성하거나 확인한다. 이 문서는 화면 흐름과 브라우저 보안 경계처럼 스키마만으로
알기 어려운 구현 규칙만 설명한다.

## 1. API 문서 위치

- 로컬 Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- 로컬 OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- 운영 Swagger UI: `https://api.meet-me.co.kr/swagger-ui/index.html`
- 운영 OpenAPI JSON: `https://api.meet-me.co.kr/v3/api-docs`

Swagger가 이 문서나 prototype과 다르면 임의로 맞추지 말고 백엔드 계약 변경 여부를 확인한다.

## 2. 브라우저 세션과 요청 공통 규칙

- 제출 MVP에는 로그인 화면과 Google·Kakao OAuth endpoint가 없다.
- 서버는 방 생성 또는 최초 참여 시 `meet_me_guest`를 `Secure HttpOnly; SameSite=Lax; Path=/api` cookie로 발급한다.
- 프론트엔드는 cookie 값을 읽거나 body, URL, `localStorage`, `sessionStorage`에 복사하지 않는다.
- 모든 API 요청은 cookie가 자동 전송되도록 `fetch`의 `credentials: "include"` 또는 동등한 설정을 사용한다.
- 운영 Origin은 정확히 `https://app.meet-me.co.kr`이다. wildcard CORS나 임의 Origin fallback을 가정하지 않는다.
- cookie를 잃거나 다른 브라우저·기기로 이동하면 기존 참여·주최자 권한을 자동 복구하지 않는다.
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
3. 자연어와 수동 가능 시간은 각각 선택 입력이지만 둘 다 비어 있으면 안 된다.
4. 수동 시간은 가능한 시간을 의미한다. 별도 소요 시간 입력과 장소 전용 입력란은 만들지 않는다.
5. 다른 참여자의 입력은 조회하지 않는다.

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

- `READY` 또는 `READY_WITH_WARNINGS`에서 `GET /api/rooms/{inviteCode}/candidates`를 호출한다.
- 후보는 Plan A/B/C 중 서버가 만든 항목만 표시하며 프론트엔드에서 다시 점수화하거나 정렬하지 않는다.
- 대면 후보의 `place.display_name`은 Gemini가 구조화한 공통 근방명이다. 제출 MVP는 지도를 사용하지 않으므로 `latitude`와 `longitude`는 `null`이며 프론트엔드는 좌표나 핀을 요구하지 않는다.
- `READY_WITH_WARNINGS`의 HOST만 `GET /api/rooms/{inviteCode}/candidates/unapplied-inputs`를 사용할 수 있다.
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

## 7. 제출 MVP에서 만들지 않을 기능

- Google·Kakao 로그인
- Google Calendar 연결
- 이메일·비밀번호 계정
- 장소 전용 검색 입력 또는 지도 좌표 선택
- 예상 소요 시간 입력
- SSE·WebSocket 연결
- cookie 원문을 보여주는 디버그 UI

## 8. 완료 검증

- Swagger의 모든 공개 endpoint와 주요 오류 응답이 클라이언트 타입에 반영됐는지 확인한다.
- 동일 브라우저, 다른 브라우저, cookie 삭제·위조, 공유 링크만 가진 사용자의 권한 차이를 E2E로 검증한다.
- `ANALYZING` Polling부터 `READY`, `READY_WITH_WARNINGS`, `NO_MATCH`, `ANALYSIS_DELAYED`, `CONFIRMED` 화면을 각각 검증한다.
- 실제 배포 Origin에서 credential CORS와 `Secure HttpOnly` cookie가 동작하는지 확인한다.
- prototype HTML은 시각·상호작용 참고 자료이며, 데이터 계약은 Swagger와 이 문서를 따른다.

