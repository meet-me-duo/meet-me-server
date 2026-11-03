# 계정별 내 모임 목록 API

2026-11-03 사용자 결정: 주최·참여 모임을 기본 전체 목록으로 조회하고, 기간은 **모임 생성일**, 히스토리는 **모임 생성 시각부터 30일 정리 정책**을 적용한다. 이 문서는 구현 계약이며 운영 배포 완료를 의미하지 않는다.

프론트 생성 입력은 native exporter가 생성한 [OpenAPI JSON](openapi/account-room-history.openapi.json)이다. 생성 파일은 수동으로 수정하지 않으며 현재 커밋의 생성 결과를 사용한다. 게시된 commit의 동일 경로를 고정해 사용한다.

`GET /api/account/rooms`는 기존 Access Token 인증을 요구한다. 계정 ID는 인증 context에서만 얻고 `participants.user_id`가 같고 `account_archive_hidden_at IS NULL`인 방만 반환한다. 게스트 쿠키, 이름 일치, 초대 링크로 익명 기록이나 다른 계정 기록을 자동 귀속하지 않는다. 명시적 claim·HOST 권한·Origin/CSRF 계약은 그대로다. 응답은 `Cache-Control: no-store`다.

| 파라미터 | 계약 |
| --- | --- |
| `role` | 생략 시 HOST와 MEMBER 전체. 지정 시 대문자 `HOST` 또는 `MEMBER` |
| `created_from` | 선택, `YYYY-MM-DD`. KST(`Asia/Seoul`) 해당 생성일 00:00 이상 |
| `created_to` | 선택, `YYYY-MM-DD`. KST 해당 생성일 다음날 00:00 미만. 양쪽을 지정하면 시작일 ≤ 종료일 |
| `query` | 선택, 앞뒤 공백 제거 후 모임 이름(`purpose`)의 대소문자 무시 부분 문자열. 최대 100 Unicode code point. 빈 검색어는 전체. `%`, `_`, `\`는 문자 그대로 검색 |
| `limit` | 기본 20, 1~100 정수 |
| `cursor` | 이전 응답의 `next_cursor`를 그대로 전달. 첫 페이지는 생략. 역할·기간·검색 변경 시 커서 초기화 |

선택값을 지정하지 않을 때는 해당 파라미터 자체를 생략한다. 전체 역할에 `role=ALL`을 보내거나 날짜·커서에 빈 문자열을 보내면 400이다. `query`만 빈 문자열을 전체 검색으로 허용한다.

날짜는 0001~9998년의 유효한 ISO 날짜만 허용한다. 예를 들어 `created_from=2026-10-15&created_to=2026-10-15`는 UTC `2026-10-14T15:00:00Z` 이상, `2026-10-15T15:00:00Z` 미만을 조회한다. 닫힌 방도 기간 필터는 `closed_at`이 아닌 `created_at`에 적용한다. 어느 한쪽만 지정할 수도 있다.

```json
{
  "rooms": [
    {
      "invite_code": "AAAAAAAAAAAAAAAAAAAAAQ",
      "purpose": "스터디 모임",
      "created_at": "2026-10-15T03:00:00Z",
      "expires_at": "2026-11-14T03:00:00Z",
      "role": "MEMBER"
    }
  ],
  "has_more": false,
  "next_cursor": null
}
```

기존 `rooms`와 항목 필드는 유지하고 본인의 `role`, 최상위 `has_more`와 nullable `next_cursor`를 추가한다. 생성 시각 내림차순, 같은 시각이면 방 UUID 오름차순으로 고정하며 마지막 반환 항목 뒤를 읽는 keyset pagination을 사용한다. `has_more=true`일 때만 `next_cursor`를 제공한다. 새 모임이 앞에 추가되어도 이미 본 항목이 다음 페이지에 밀려 중복되지 않는다. 목록은 실시간 조회이므로 페이지 사이 삭제·만료된 방의 잔존을 보장하지 않으며 스냅샷이나 전체 개수는 제공하지 않는다. 커서는 인증 자격 증명이 아니며 어떤 커서를 전달해도 현재 계정 조건을 다시 적용한다.

잘못된 역할·날짜·역전 기간·검색 길이·페이지 크기·커서는 `400`/`VALIDATION_FAILED`다. 로그인 누락은 `401`, 인증 검증 키 장애는 기존 `503` 계약이다. 실제 생성 OpenAPI의 파라미터·응답 스키마도 같은 계약으로 검증한다.

## 30일 보관과 삭제 경계

- `COLLECTING`과 `CLOSED` 모두 `created_at`에서 30×24시간 후가 `expires_at`이다. 개인 숨김·최근 마감·수정 라운드는 이를 연장하지 않는다.
- 기존 정리 로직은 기준 시각이 `now - 30일`보다 **작은** 방을 삭제한다. 목록도 같은 비교를 적용해 정확히 30일 경계인 방은 포함하고 경계를 지난 방은 정리 배치 실행 전이라도 숨긴다. 삭제 실행 시각은 기존 스케줄러를 따른다.
- 생성일 필터는 보관 기준을 변경하거나 보관 기간을 연장하지 않는다. 가입·재로그인·명시적 연결 역시 기간을 연장하지 않는다. 이미 삭제된 기록은 복구하지 않으며 별도 장기 아카이브 테이블을 만들지 않는다.

## 내 목록에서 숨김

`POST /api/account/rooms/{inviteCode}/hide`는 body 없이 Access Token과 기존 정확한 프론트엔드 Origin을 보낸다. 계정 ID는 인증 context에서만 얻으며 guest cookie로 계정 권한을 보충하지 않는다. 본인의 HOST·MEMBER 관계만 숨긴다. 최초 성공과 이미 숨긴 본인 모임은 모두 `204 No Content`, `Cache-Control: no-store`이며 응답 body와 Content-Type은 없다. Web은 성공 시 JSON 파서를 호출하지 않는다.

| 응답 | 계약 |
| --- | --- |
| 204 | 본인 관계 숨김 완료 또는 이미 숨김 |
| 401 / ACCOUNT_REQUIRED | 로그인 계정 없음, guest 단독 요청 |
| 401 / 기존 인증 오류 | 무효·만료 Access Token |
| 403 / ORIGIN_NOT_ALLOWED | Origin 누락. 불허 Origin은 기존 CORS 403 응답이며 Problem JSON을 보장하지 않음 |
| 404 / ROOM_NOT_FOUND | 타인 모임·없는/잘못된 코드·생성일부터 30일 초과 모임을 동일하게 처리 |
| 503 / 기존 인증 오류 | 인증 검증 키 사용 불가 |

동일 관계의 반복·동시 요청은 같은 결과이며 최초 숨김 시각을 유지한다. 숨김은 서버에 저장되어 다른 기기·검색·역할·생성일 필터·커서와 재참여에서도 유지된다. 숨긴 항목은 페이지 크기 계산 전에 제외된다. 복원 API는 제공하지 않는다.

모임 전역 상태와 다른 사람의 목록·직접 초대 링크·참여·역할·제출과 결과 접근은 바뀌지 않는다. 모임/참여자를 삭제하거나 분석을 취소하지 않는다. 모든 데이터의 물리 정리는 위 생성일 30일 정책을 따른다.

서버 배포와 V23 운영 migration 적용 승인은 별도다. 이 문서나 develop 병합만으로 Web에서 새 API를 사용할 수 있다고 간주하지 않는다.
