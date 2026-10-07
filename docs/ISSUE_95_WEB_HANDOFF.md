# 웹 #19 연결 계약 — 서버 #95

2026-10-07 UTC. 정확한 타입은 실제 `/v3/api-docs`에서 생성한 [native OpenAPI](openapi/issue-95.openapi.json)를 따른다. 제공 시점의 commit SHA는 Git 이력과 인계 메시지를 기준으로 확인한다. D2 명시 선호 집계는 2026-10-07 사용자 승인으로 계약이 확정됐으며 #99 통합에서 구현·검증을 진행한다. 새 입력 UI를 요구하지 않고 이 문서의 공개 옵션 API를 유지한다. 현재 완료 근거는 [#99 통합 검증 기록](ISSUE_99_INTEGRATION.md)을 따른다. 웹 기준은 PR20 HEAD8e56fe0c37cef52e584ff569b97b46b60f28e517로 확인했다.

## 프로토콜과 조회

방 GET 응답의 `recommendation_protocol`은 OpenAPI에서 optional nullable string이다. 이 서버는 null도 필드에 포함해 돌려주며 이전 서버가 필드를 보내지 않는 경우도 legacy 경로로 처리한다. 현재 활성 신규 분석은 정확히 `"diverse-time-v1"`, legacy는 `null`이다. 알려지지 않은 값에서는 신규 확정을 활성화하지 않는다. 기존 `/candidates`를 신규 A/B/C 카드로 해석하지 않는다.

`GET /api/rooms/{inviteCode}/recommendations`는 쿠키로 인증된 방 참여자에게 `{protocol, analysis_id, state_version, quality, total_options, options, has_alternatives, next_cursor}`를 돌려준다. `quality`는 `COMPLETE|PARTIAL`이다. `options`는 최대3개, 부족하면 실제 개수만 있다. primary에서는 `rank`가1~3이며 A/B/C 표시 순서다. mode나 일부 참석 의미는 rank와 관계없다.

각 option: `{option_id, rank, time_range:{start_at,end_at}, variants, summary}`. start는 포함, end는 배타적 ISO8601 절대 시각이다. 임의 한 시간으로 잘라서 확정하지 않고 사용자가 이 창 안의 실제 시작·종료를 선택한다. 자정을 넘는 창은 하나이며 양쪽 날짜를 보여준다.

각 variant: `{variant_id, meeting_mode, attendance_count, total_participants, partial_attendance, place}`. mode는 `IN_PERSON|REMOTE`만 가능하다. place는 온라인에서 null, 대면에서 `{display_name,latitude,longitude}`이고 좌표는 nullable이다. 참가자 ID·원문·개별 선호는 이 응답에 없다.

## 전체 대안과 cursor

`GET /api/rooms/{inviteCode}/recommendations/alternatives?analysis_id=<조회한 UUID>&limit=20`가 첫 페이지다. primary 응답의 `next_cursor=null`로 대안을 없다고 판단하지 않는다. `has_alternatives`는 분석 전체에 primary 외 대안이 있다는 뜻이고 마지막 대안 페이지에서도 true일 수 있다.

대안의 `rank=null`이며 primary를 반복하지 않는다. `limit`은 integer1~100, 기본20이다. 반환된 opaque `next_cursor`를 URL 인코딩해 같은 `analysis_id`에만 전달한다. `next_cursor=null`이면 끝이다. 클라이언트는 cursor를 해석·제작하거나 다른 분석에 재사용하지 않는다. 서버는 저장 개수 제한으로 전체 결과를 자르지 않는다.

잘못된 cursor/limit은400 `VALIDATION_FAILED`, 현재 분석과 다른 `analysis_id`는409 `STALE_ANALYSIS`다. 이때 방과 primary를 다시 조회하고 과거 선택·cursor를 비운다.

## 실제 확정과 결과

HOST 전용 `POST /api/rooms/{inviteCode}/recommendations/{optionId}/confirmation`에 JSON의 네 필드를 모두 보낸다: `analysis_id`, `variant_id`, `start_at`, `end_at`. 기존 쿠키·Origin·요청 제한 정책을 그대로 적용한다. `option.start <= start_at < end_at <= option.end`이며 PostgreSQL이 보존하는 마이크로초 정밀도까지만 허용한다. 더 작은 소수, 누락/null, 외부 variant와 창 밖 시각은400 `VALIDATION_FAILED`다.

200 응답과 기존 `GET /api/rooms/{inviteCode}/result`는 같은 `{candidate, confirmed_at, selection}` 계약이다. 신규 selection은 `{protocol:"diverse-time-v1",analysis_id,option_id,variant_id,start_at,end_at}`. candidate의 `candidate_id=null`, `time_ranges`는 실제 선택한 한 구간이다. `candidate.plan_type`은 과거의 방식/부분참석 의미를 보존하는 호환 필드이므로 신규 카드 A/B/C로 사용하지 않는다. 과거 확정은 기존 candidate_id와 `selection=null`이다.

전체 분석/option/variant/start/end가 같은 요청만200 멱등이다. 다른 선택은409 `CANDIDATE_ALREADY_CONFIRMED`. 신규 분석에 기존 `/candidates/{candidateId}/confirmation`으로 확정하면409 `RECOMMENDATION_SELECTION_REQUIRED`다. 그 URL에 새 body를 보내지 않는다.

오류는 기존 RFC9457 ProblemDetail이며 `status`, `code`, `detail`, `instance`를 기준으로 처리한다. 세 경로 공통401 세션,403 방 참여/주최자 권한,404 방 없음,409 `CANDIDATES_NOT_READY`(처리 중 또는 수정 라운드 OPEN)를 문서화한다. POST는 기존429 요청 제한과503 제한 저장소 장애도 유지한다. 모든 코드와 응답 schema는 native OpenAPI를 확인한다.

신규 확정 후 방 상태는 `CONFIRMED`이며 재수정·재분석을 막는다. 입력 원문·과거 frozen version을 유지한다. 현재 결과의 analysis_id/state_version과 신규 방 상태를 함께 갱신한다.

## 적용 경계

V9는 기존 V1~V8 변경 없이 별도 추천 projection·typed selection을 추가한다. 구 V8 바이너리는 신규 결과를 읽고 표현할 수 없으므로 운영 전환과 rollback을 무조건 호환된다고 선언하지 않는다. 운영은 별도 승인된 절차에서 모든 writer를 중지하고 V9 대응 서버로 전환해야 한다. 기존 배포 guard·권한·보안 정책은 이 작업에서 바꾸지 않았다.
