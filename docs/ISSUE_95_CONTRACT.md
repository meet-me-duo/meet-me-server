# 다양한 날짜·시간 추천과 실제 시각 확정 — #95

확인일: 2026-10-07 UTC. 구현 기준은 PR #94 HEAD `bf4edff51dea5f1f90a8ac627edf05ec17b6d56d`이며 PR #93 `1ddbd267`을 포함한다. 최신 `develop`은 `848b9688451551781769799ff7d27485ea5a5445`다. 다른 환경의 미커밋 계획을 가져오지 않고 [이슈 #95](https://github.com/meet-me-duo/meet-me-server/issues/95)와 이 실행의 사용자 승인으로 재구성한다.

## 확정 범위

- 참석 인원 → 명시 선호 충족 → 같은 품질 내 날짜·시간 다양성 순서로 최대 세 시간안을 A/B/C로 표시한다. A/B/C는 새 추천의 표시 순서이며 대면·온라인·부분 참석은 variant 속성이다.
- 전체 N/N-1/N-2 조합을 최소 두 명 조건으로 평가한다. 전원 후보가 있어도 다른 조합을 평가하고 전체 유효 시간안을 보존한다. 같은 절대 시작·종료 창은 하나의 option이며 참석 집합·방식·공통 장소의 대안은 별도 variant다.
- 최소 두 명, 명시 불가·예외 우선 차감, 기존 장소 호환·미해석 제약 안전 제외와 frozen submission version 계약을 유지한다.
- 실제 가능한 연속 구간을 제시한다. 새 고정 길이·임의 한 시간·같은 안 복제는 만들지 않는다. 자정의 접한 구간은 가능한 연속 창으로 유지하며 summary에 양쪽 날짜를 표시한다.
- primary 최대 세 개 외 전체 대안은 분석에 고정된 cursor로 끝까지 조회한다. 저장 상한으로 잘라낸 결과를 전체라고 표시하지 않는다.
- 실제 확정은 option의 단일 창 안에서 `start <= selected.start < selected.end <= end`를 만족해야 한다. 분석·option·variant·시작·종료 전체 튜플만 멱등이며 다른 선택은 충돌한다.
- 공개 응답에는 정상 입력 원문과 참가자별 선호를 포함하지 않는다. 미반영 원문 공개는 기존 HOST 전용 경계를 유지한다.

## D2 의사결정 대기

이슈에는 명시 선호 집계가 TBD다. 참가자별 동일 1표, 동일 차원 대안 OR·복수 차원 AND, 시간 선호의 전체 창 충족과 선호 하위 창 별도 보존안을 사용자에게 제시했다. 결정 전에는 D2 종속 구현·기대값을 확정하지 않는다. 현재 구조화 schema에는 선호 전용 필드가 없으므로 기존 AVAILABLE을 선호로 추정하지 않는다. 예를 들어 모두18~22가능, A20~21선호/B21~22선호에서 전체18~22창이 두 사람 선호를 만족한다고 표시하면 안 된다.

## 신규 HTTP 계약

native OpenAPI를 최종 field/type 기준으로 사용한다. prefix는 `/api/rooms/{inviteCode}`다.

| 요청 | 계약 |
| --- | --- |
| `GET /recommendations` | 인증된 방 참여자. protocol, analysis_id, state_version, quality, total_options, primary options(최대3), has_alternatives. |
| `GET /recommendations/alternatives?analysis_id=UUID&cursor=...&limit=20` | 같은 분석의 primary 제외 대안. limit 1~100, next_cursor nullable. 잘못된 cursor400·지난 분석409. |
| `POST /recommendations/{optionId}/confirmation` | 기존 HOST·Origin·rate limit 정책을 적용한다. JSON body의 analysis_id, variant_id, start_at, end_at 네 필드는 필수다. |

option은 `option_id`, nullable `rank`(primary1~3), 단일 `time_range {start_at,end_at}`, `variants`, `summary`다. variant는 `variant_id`, `meeting_mode`, `attendance_count`, `total_participants`, `partial_attendance`, nullable `place`다. 분석 protocol은 정확히 `diverse-time-v1`이다. legacy 분석은 protocol=null로 구분한다. 웹은 알려진 protocol에서만 실제 시각 확정을 활성화하고 unknown에서는 차단·새로고침을 안내하며 absent에서는 기존 경로를 유지한다.

## 기존 API·영속성 호환

기존 candidates의 rank1~3, plan_type 의미, 다중 time_ranges와 과거 final_confirmations를 보존한다. 기존 confirmation은 body 없는 API이며 새 JSON을 그 URL에 보내지 않는다. 신규 protocol 분석의 candidate-only confirmation은409 `RECOMMENDATION_SELECTION_REQUIRED`로 거부한다.

typed selection은 candidate FK와 분리한다. 신규 `/result`와 confirmation 응답은 기존 candidate 표현과 별도 selection을 제공하며 candidate.time_ranges는 실제 선택 창이다. 새 selection의 candidate_id는 null이며 option UUID를 candidate ID로 위장하지 않는다. legacy result는 기존 candidate_id와 selection=null을 유지한다. CONFIRMED 상태와 수정 라운드·재분석·worker 보호는 두 확정 타입을 모두 인식한다.

V1~V8은 수정하지 않는다. 신규 projection/option/variant/selection은 한 분석의 legacy 결과 완료와 같은 트랜잭션으로 게시한다. 방→정확 run 잠금, room/run/batch·참석자 동일 방 FK와 cascade retention을 유지한다. 새 스키마에서 구 V8 writer가 실행되는 mixed-version 경계는 독립 PostgreSQL 테스트와 DB 제약으로 검토하며 기존 운영 설치·배포 승인을 자동 확장하지 않는다.

## 검증·권한 범위

독립 테스트 저자·구현자·리뷰를 분리한다. 0~4+창·중복과 variants·인원과 불가·대안 페이지 누락0·stale/범위/권한/멱등/경합·legacy/mixed-version·재분석/frozen version·자정/DST·개인정보를 실제 PostgreSQL 및 HTTP로 검증한다. 유효 assertion RED→GREEN, 의도적 결함 주입과 정확 복구, 필수 변경 없는 commit guard, lint/assemble/전체 test, Draft PR과 해당 HEAD CI를 확인한다.

구현·commit·push·Draft PR은 사용자 위임 범위다. main/develop 병합, 운영 배포, 권한·보안 정책 변경은 범위 밖이다. 유료 모델 호출은0이며 별도 전담 평가 세션의 결과를 새 기능 검증으로 재사용하지 않는다.
