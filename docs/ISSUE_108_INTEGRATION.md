# #108 실제 계정·게스트 통합

## 기준과 승인 범위

2026-10-17 원격에서 직접 확인한 #109 서버 `c06b60a9833ae77e5fce9cfef5719ab2f37470ff`와 웹 `95f09c0a98c6538b0f19ed381fdf11a2f5b844d9`를 별도 `feature/issue-108-integration` 작업 트리에 보존한다. 기존 독립 단계의 패치·RED·리뷰·전체 검증은 그대로 남기며 실제 통합 완료 증거와 구분한다. 마감 수정은 #113의 원격 HEAD `da225d8aaa9097db2360a6bf0579322de16b5968`에서 서비스·독립 PostgreSQL 테스트·증거를 가져왔다. 기존 원격 브랜치를 수정하거나 강제 push하지 않는다.

사용자는 이 작업의 완료된 변경에 대해 commit·feature push·Draft PR·정확한 HEAD의 CI 확인을 위임했다. 병합·배포·운영 DB·실제 공급자 로그인과 credential/Secret 변경은 포함하지 않는다. 내장 goal 도구는 없어 [문서 목표](ISSUE_108_GOAL.md)로 추적한다.

## 공개 동작

- 게스트의 이름은 중복 가능하며 각 기록은 별도 participant UUID다. 최초 응답의 자기 `viewer.participant_id`와 공개 선택 목록의 ID는 권한 증명이 아니다. 회원은 계정 이름을 사용하며 이름·암호 입력을 생략한다.
- 선택 암호는 최초 참여의 같은 transaction에서 PBKDF2-SHA256 600,000회·무작위 salt16바이트·파생키32바이트로 저장한다. 재참여는 기존 이름/암호를 덮어쓰지 않는다. 암호를 비워두면 수정이 보호되지 않는다는 안내를 먼저 제공한다.
- `GET /api/rooms/{inviteCode}/guest-records`는 게스트 소유 기록의 이름·번호만 반환한다. 회원 기록, 원문·해시·세션·credential을 포함하지 않는다.
- `POST .../guest-records/reaccess`는 선택 번호와 선택 암호를 검증한다. 원래 유효한 소유 세션은 범위를 유지한다. 다른 기기의 보호 기록은 정답 암호를 요구하며 HOST 우회나 암호 초기화는 없다. 무암호 기록은 `REPLACE_ONLY`, 보호 기록은 `VERIFIED_GUEST`다. 신규 credential은 HttpOnly cookie로만 전달한다.
- `REPLACE_ONLY`는 기존 원문 조회·HOST·회원 연결을 허용하지 않는다. 전용 `PUT .../guest-records/{participantId}/submission`은 새 전체 원문만 받아 `{replaced:true}`만 반환한다. 일반 `PUT /submission`은 이 증명을 거부한다. 공개 방 polling은 `joined:false`를 유지한다.
- 다른 기록의 기존 소유권/접근 범위가 있는 동일 기기에서 기록을 자동 전환하지 않는다. 계정 인증이 있으면 일반 읽기/쓰기 권한은 계정 우선이며 무효·만료 Bearer는 guest로 fallback하지 않는다.
- 회원 연결은 #109의 명시적 claim과 로그인·유효한 VERIFIED 증명을 사용한다. 기존 회원 기록 충돌은409이며 두 기록과 원문을 보존한다. 연결 후 과거 guest 접근은 무효다.

## 동시성·실패 경계

V12는 보호 해시와 room/session에 결합한 재접근 grant를 PostgreSQL에 둔다. grant는 서버가 검증한 유효 세션에만 결합하고 세션의 고정 만료를 넘기지 않는다. 원래 participant owner는 변경하지 않는다. 공개 level·UUID만 제출해 권한을 주장할 수 없다. 명시 연결 시 guest owner가 해제되므로 오래된 grant도 사용할 수 없다.

재접근과 쓰기는 room 잠금 아래 소유권·상태를 확인한다. 같은 revision에서 시작한 두 blind 대체는 하나만 저장되고 다른 요청은409다. 서로 다른 계정의 claim도 한 계정만 소유권을 얻는다. blind 대체가 먼저 저장된 후 유효 소유자가 명시 연결하는 순서는 두 요청 모두 성공할 수 있으며 최신 원문을 보존한다. claim이 먼저 완료되면 오래된 guest 대체는 거부된다. 이 선형화 규칙이 독립 모델 문서의 포괄적 “경합 시 하나만 성공” 문구를 구체화한다.

마감 검사는 room 잠금·인증 이후 동일 원문 조기 반환 이전에 수행한다. blind 대체는 수집중이며 deadline 미경과인 방에서만 허용한다. 닫힌/확정 방의 암호 재접근은 읽기만 복구한다. 기존 수정 라운드 권한은 보호된 일반 소유권 경로로 검증한다.

암호 검증 이전 IP30회/기록5회·15분 제한을 적용한다. IP는 servlet remote address로 읽고 임의 forwarded header를 신뢰하지 않는다. Redis 실패는503 fail closed, 한도 초과는429+Retry-After다. 실패에 암호 존재 여부·해시·credential·내부 예외를 포함하지 않으며 secret DTO의 toString도 가린다.

## 진행 상태

- [x] 정확한 #109/#113 원격 HEAD와 별도 작업 트리
- [x] 기존 독립47 테스트·웹20 테스트·Library 인계 보존
- [x] 실제 RoomPage 독립16 테스트 최초 RED
- [x] 실제 HTTP/PostgreSQL 독립 37개와 기존 계약 21개 RED/GREEN, V12 경계
- [x] 실제 브라우저→격리 서버 흐름·오류와 별도 PostgreSQL HTTP 경합 검증
- [x] 결함 주입·전체 gate·독립 최종 리뷰
- [ ] commit·push·Draft PR·정확 HEAD CI

이 체크리스트의 미완료 항목은 이전 단계의 통과 결과로 대신 완료 처리하지 않는다.

## 실제 통합 검증 중간 기록

2026-10-17 UTC 기준 독립 HTTP 37개를 포함한 관련 58개가 실패·오류 없이 통과했다. 원본 독립 47개와 HTTP 37개 테스트 지문은 보존했다. V12 추가로 변경이 필요한 기존 scoped proof·migration 계약은 이전 테스트와 RED를 보관하고 별도 supersession 근거로 추적한다.

웹은 실제 RoomPage 독립 16개를 포함한 전체 단위 400개가 통과했다. 실제 브라우저에서 격리 Spring/PostgreSQL/Redis와 loopback OIDC를 사용한 #108 16개 및 계정 회귀 12개도 통과했다. API 모킹과 외부 브라우저 요청은 0개다. 마감 409 뒤 명시적 확인과 재요청 200을 실제 관측했다. 확정 상태·트랜잭션 경합은 별도 HTTP 테스트로 검증한다. 증거는 웹 `docs/verification/issue-108-native.json`, `issue-109-after-108-native.json`에 있다.

V12 릴리스 catalog는 새 발행에 정확히 V1~V12를 요구하고 기존 V10·V11 소비를 유지하도록 최소 확장했다. 독립 신규 14개를 포함한 관련 30개와 추가 오프라인 배포 계약 전체 64개가 통과했다. 독립 리뷰에서 P1/P2는 없었고 실제 운영 호출은 없었다. 기존 V1~V11 바이트와 승인·지문·체크섬·실패 거부 조건을 유지한다.

전체 브라우저 첫 실행은 378 통과·기존 skip 1·실패 5였다. 독립 저자가 검토한 자정 경계 시계 고정과 전수 layout 조회/스크린샷 출력 최적화를 적용하며 기존 기대값·범위·시간 제한을 유지한다. 이 실행을 최종 통과로 계산하지 않는다. 독립 리뷰에서 발견한 HTTP 이름 길이의 UTF-16/Unicode 코드포인트 불일치는 별도 테스트로 재현 후 수정한다. 최종 전체 게이트·결함 주입·리뷰·원격 CI는 아직 진행 중이다.

## Unicode·결함 주입 최종 소스 검증

이름 DTO의 UTF-16 `@Size`와 도메인의 Unicode 코드포인트 규칙 불일치를 독립 HTTP 3개로 재현했다. U01/U03은 201 예상·400 실제의 RED였고 U02의 51개 거부와 상태 보존은 통과했다. DTO의 중복 제약 한 개만 제거하고 trim 후 1~50 코드포인트를 문서화한 뒤 Unicode3+HTTP37+마감37 총77개가 모두 통과했다. 생성 OpenAPI 26경로 및 웹 타입을 이 최종 서버에서 다시 동기화했다.

#113 마감 테스트는 회원 HOST 생성 fixture와 EXPIRED/REVOKED의 게스트 actor만 공통 인증 계약에 맞췄다. 원래 마감·인증 우선순위·동시성 기대값은 유지하고 37개가 모두 통과했다. 이전 테스트·RED·7개 결함 결과는 역사 보관본으로 분리했다.

실제 통합 HTTP 검증은 암호 검증 우회, revision 경합 검사 누락, blind 동일 원문 조기 반환, 가입 transaction 누락, 마감 검사 누락의 5개 변이를 4개 소스에 함께 주입한 단일74테스트 실행에서 지정 assertion으로 모두 탐지했다(25실패·오류0). 원본 바이트·권한 복원 뒤74개가 모두 통과했다. V12 발행 누락 변이도 독립 catalog 테스트가 탐지했고 복원 뒤14개가 모두 통과했다. 이는 서로 독립된 HTTP 5회 실행을 뜻하지 않는다.

웹 실제 RoomPage에서는 개인 cache 삭제 누락, 무암호 대체의 원문 GET, 오래된 방·인증 응답 뒤 추가 조회의 3개 변이를 각각 탐지했다. 바이트·권한을 복원한 뒤 고정16개가 모두 통과했다. 최종 Unicode 서버의 고정 산출물 1,051개 파일 manifest를 사용한 native #108 16개와 계정 회귀12개도 다시 모두 통과했다. 실제 credential 변경·limit reset·운영 호출은 없었다.

2026-10-17 17:30 UTC 최종 원본 TDD guard 전체 게이트가 종료0으로 통과했다. self-test와 ktlintCheck·assemble·test를 실행했고 fresh JUnit 138 suite/898 tests, 실패0·오류0·기존skip4를 확인했다. guard SHA256은 `07c4f974ec7d09b6ff3ca4e3d9e9dfd365bfccb6c10420ff6dbc0b5314473b79`로 원본과 같다. 성공 시 자식 로그를 출력하지 않는 원본 동작을 유지하며 `.tdd/reviews/issue-108-final-quality-gate.json`에 각 XML 지문을 보존한다. 웹 최종 전체400단위·383브라우저/기존skip1 및 API·lint·typecheck·build도 통과했고 독립 웹 리뷰가 승인했다. 서버 최종 독립 리뷰와 원격 정확 HEAD CI는 이어서 확인한다. #107/#110을 합친 전체 기능 검증은 부모의 후속 범위다.

서버 독립 최종 리뷰도 `.tdd/reviews/issue-108-server-final-review.md`에서 승인됐고 담당 범위의 미해결 P1/P2는 없다. 웹 feature 브랜치는 `e9e81efe2c75813623729e4a9b8214bd8430c8b9`로 게시됐다. 이후 웹 Draft PR 생성은 자동 승인 검토에서 별도 승인이 현재 검토 문맥에 없다는 이유로 차단됐다. 추가 push·PR 생성은 명시 승인 응답을 기다리며 로컬 커밋과 안전한 인계를 계속한다. 정확 HEAD 원격 CI는 아직 실행/확인하지 않았다.
