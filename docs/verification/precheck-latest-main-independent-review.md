# 최신 main·develop 통합 독립 검토

검토일: 2026-10-28 UTC. 저장소 읽기와 이 문서 작성만 수행했다. 프로덕션·테스트·handoff 파일을 변경하지 않았으며 원격 변경이나 추가 모델 호출을 하지 않았다.

## 기준과 결론

- 검증한 안내 구현 로컬 커밋: `15ebedab590748ab1fcbec1003368d3874029b14`.
- 통합 대상 develop: `0ce4b4026865ccdcfa5f012a35be3bb0101df2ee` — 내 모임 API PR #152 통합.
- 조회한 최신 main: `e799fb60b5029d42c601e4a33c51e0ba1beacaff` — PR #155 승격. main과 위 develop의 tracked tree는 동일했다.
- 원래 작업 기준: `974b3800e5098b400ef22a42f626b4cfb4f21782`.

검토 시점의 정상 merge --no-commit 통합 결과에서 미해결 P1/P2 결함이나 기능 누락은 발견하지 않았다. 기존 내 모임 API와 본인 수정 안내가 함께 존재한다. 이 결론은 원격 게시·배포 완료를 뜻하지 않는다.

## 양쪽 변경 보존

원래 기준에서 안내 커밋이 바꾼 `src` 파일 10개는 통합 작업 트리와 커밋의 바이트가 모두 일치한다. precheck 프로덕션 5개 및 관련 테스트·하네스가 원격 통합 때문에 바뀌지 않았다. 원격 develop이 바꾼 `src` 파일 8개도 작업 트리와 원격 커밋의 바이트가 모두 일치한다. 내 모임의 파라미터·controller·port·service·JDBC와 관련 테스트가 보존됐다.

공통 변경 문서는 PRD·Architecture·implementation_plan 세 개뿐이다. PRD와 Architecture에서는 양쪽의 추가 계약 문장이 모두 남아 있음을 기준별 diff와 대조했다. implementation_plan은 같은 위치에 추가한 상단 한 블록이 충돌했으며, 오탐·수정 안내와 #151 내 모임 섹션을 모두 보존한 결과를 확인했다. 충돌 표시는 남아 있지 않다. #151 섹션의 이전 게시 대기 체크박스는 당시 커밋에서 가져온 기록이며 현재 배포 증명으로 사용하지 않는다.

`.github`, deploy, infra, SQL migration, Gradle build/settings/wrapper는 원래 기준과 통합 작업 트리 사이에 변경이 없다. 운영 전환·스키마·배포 guard를 새로 바꾸는 병합이 아니다.

## 통합 OpenAPI

실제 재생성된 `docs/openapi/precheck-edit-guidance.openapi.json`의 전체 SHA-256을 독립 계산했다.

`3028ed968241315c1d4e1c230491a7eb350ea0b9122cfcddf8aa84fdde075145`

같은 파일에서 다음 계약을 함께 확인했다.

- `PrecheckStateResponse.edit_guidance`: 응답의 required 필드이며 nullable, maxLength=160. 기존 question과 상태 필드가 함께 존재한다.
- `GET /api/account/rooms`: role, created_from, created_to, query, limit, cursor 여섯 조회 파라미터.
- `AccountRoomsResponse`: 기존 rooms와 has_more·next_cursor. 각 `AccountRoomResponse`에 본인의 role.

기존 `docs/openapi/account-room-history.openapi.json`은 원격 develop의 파일과 바이트가 같다. 기존 고정 SHA-256 `a491cf66707c1009e82ebe521239a0fe2c87a7c872ecdda3f117f76b781df60a`를 유지한다. 이전 안내 단독 스냅샷의 `eb862372...`는 과거 구현 검토 근거이며 통합 소스의 현재 전체 API 지문으로 사용하면 안 된다. 웹 인계는 위 `3028ed96...` 통합 파일을 기준으로 해야 한다.

## 검증 증거와 남은 범위

통합 전 안내 소스의 필수 guard는 `.tdd/verification/precheck-time-flexibility-final-gate.json`에서 exit0, 267 suites / 2270 tests, 2266 PASS·기존4 SKIP·실패/오류0으로 기록돼 있다. sourceBefore/sourceAfter도 일치한다. 이 결과는 통합 후 전체 검증을 대신하지 않는다.

통합 작업 트리의 집중 XML에서는 안내 계약16 + Bidi6 + PostgreSQL6 + 기존 정책6 + 내 모임8 + 기존 소유권17 = **59개 모두 PASS, 실패/오류/skip 0**을 직접 확인했다. staged 및 unstaged diff의 whitespace 검사도 통과했다.

안내 FI 4건은 `.tdd/verification/precheck-edit-guidance.json`에서 모두 DETECTED로 기록됐다. 안내 거부6·제어문자 검증 제거8·안내의 A/B 한도 소모2·내부 질문 공개1의 assertion 실패와 errors0을 기록하며, 모델의 일반 의미 정확성 증거로 해석하지 않는다. 이전 실제 합성 평가와 지문·한계는 별도 독립 검토 문서를 따른다. 추가 유료 호출 없이 기존 결과를 유지했다.

작성 시점에는 통합 후 전체 필수 guard와 로컬 merge commit이 아직 완료 전이다. 원격 이슈 게시 자동심사 거절에 따른 원격 게시·PR/CI·develop/main·운영 배포 대기도 유지한다. 원격 또는 운영 결과를 이 로컬 통합 검토로 대체하지 않는다.

후속 실제 통합 전체 검증: 2278 tests, 4 SKIP, 실패/오류0. 원본 commit guard exit0와 src snapshot 전후 동일을 확인했다. 원격 작업·운영 반영은 수행하지 않았다.
