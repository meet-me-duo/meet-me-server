# #109 공통 계정·소유권·게스트 연결 계약

상태: 2026-10-17 사용자 결정 기반 구현 계약. #108과 #110에 전달할 원본. 실제 운영 OAuth 활성화/로그인 성공은 미검증이다.

## 계정

- Google과 Kakao 모두 OIDC code flow로 지원. 내부 account UUID와 `(provider, subject)` 유일성으로 식별한다. 이름·이메일이 같아도 서로 다른 provider identity를 자동 병합하지 않는다. Calendar는 범위 밖이다.
- `AccountPrincipal(accountId: UUID, displayName: String)`은 검증된 서비스 Access에서만 만든다. member account context port는 `account.application.port.output.CurrentAccountPort.currentAccount()`다.
- 잘못되거나 만료된 Authorization은 401로 거부하며 guest cookie로 fallback하지 않는다. 유효한 account가 있으면 account 소유권을 우선한다.
- Access는 RS256/kid/15분, response body→메모리→Authorization Bearer. Refresh는 256bit 불투명 토큰의 digest/회전 상태를 Redis에 저장, idle14일·absolute30일. Redis 유실/장애를 로그인 성공으로 대체하지 않는다.
- Refresh는 API host-only `Secure; HttpOnly; SameSite=Lax; Path=/api/auth`, idle와 absolute 잔여 중 짧은 Max-Age. POST refresh/logout/logout-all은 정확한 frontend Origin을 검증한다.
- replay는 해당 family를 폐기. 일반 로그아웃=current family, 전체 로그아웃=검증된 account의 모든 family. 기존 Access 최대15분 잔여 유효, 공급자 연결 해제 없음.

## 참여와 연결

- 주최자 신규 생성은 로그인 필수, 이름은 계정 프로필을 사용한다. 일반 회원 참여도 계정 식별/이름을 사용하여 #108 guest 이름/암호 입력을 생략한다. 게스트 참여는 유지한다.
- `participants`는 `user_id` 또는 `guest_session_id` 정확히 하나를 가지며 같은 방에서 계정/게스트별 최대1개다. 역할 HOST/MEMBER와 participant UUID는 그대로 권한·제출 참조에 사용한다.
- 기존 게스트 기록은 로그인만으로 자동 귀속하지 않는다. `POST /api/rooms/{inviteCode}/claim`에서 authenticated account + 유효한 기존 guest proof를 함께 검증한 명시 요청만 연결한다.
- #108의 새 proof가 필요한 경우 #108 담당은 유효한 기존 기록 소유 증명을 공통 resolver로 전달하는 계약을 연동한다. 이름·이메일·초대 링크만을 claim proof로 인정하지 않는다.
- 같은 방에 해당 account의 참여 기록이 이미 존재하면 409 `PARTICIPANT_CONFLICT`; silent merge/overwrite 없음. 기존 guest proof로 찾은 바로 그 participant의 owner만 바꾸고 ID·역할·제출·인원을 보존한다. 옛 guest 권한은 소멸한다.
- room row lock 아래 owner 검증/충돌 검사/변경을 수행하고 DB XOR+unique 제약으로 동시 claim/join 충돌을 막는다.
- 소유하지 않은 account는 guest proof만으로 account-owned HOST 권한을 얻지 못한다. 링크는 공개 방 조회/초대만 허용한다.

## 아카이브와 HTTP

- `GET /api/account/rooms`: authenticated account의 HOST participant가 있는 방만 최신순으로 반환. 로컬 최근 모임 목록과 분리한다.
- 각 항목 `invite_code`, `purpose`, `created_at`, `expires_at` 포함. `expires_at = (CLOSED면 closed_at, 아니면 created_at) + 30일`로 기존 cleanup 기준을 유지한다. 보존기간 연장/영구 저장 없음.
- 만료 경계는 기존 cleanup의 엄격한 `< cutoff`와 맞춘다. 삭제된 방은 아카이브에 나타나지 않는다.
- OAuth 시작 `/api/auth/{google|kakao}/start`, callback `/api/auth/{google|kakao}/callback`. 서버가 fixed frontend `/auth/callback`으로 redirect하고 Access/Refresh/provider token을 URL에 싣지 않는다. 웹은 refresh로 세션을 초기화한다.
- 로그인 불가503, 인증누락/무효401, Origin/권한403, 계정참여충돌409. OpenAPI는 실제 구현과 동기화한다.

## 변경 파일 경계

| 담당 | 파일/영역 |
| --- | --- |
| #109 | 새 account/**, account auth config, auth 환경변수 placeholder, 필요 auth 의존성 |
| #109 | participant domain/port/persistence, room lifecycle DTO/controller/usecase/service, 새 archive/claim API |
| #109 | coordination MatchingResultService/InputRevisionService/RetryAnalysisService 소유권 resolver 경계 |
| #109 제한 | SubmissionService.kt 생성자 resolver 및 requireParticipant 소유권 경계만; 마감 판단/저장/스냅샷 변경 금지 |
| #109 | 신규 V11 account/participant-owner migration. 기존 공유 V1~V10 수정 금지 |
| #109 V11 전달 | deploy/pipeline/publish_manifest.py, approved_release.py, host-approved-release.py의 V11 catalog 검증 및 독립 offline tests. 운영 workflow/실제 승인·전환은 변경하지 않음 |
| #109 웹 | 로그인/session/API Bearer·refresh, 생성 gate, 회원 참여 이름 생략, 명시적 claim·본인 방 목록, generated OpenAPI/types |
| #108 조율 | guest 이름·선택암호·재접근 proof. participant 공통 파일은 #109 owner 계약을 보존하며 합친다 |
| #110 제외 | 관측·운영자 조회·마스킹·telemetry 파일 및 별도 SubmissionService 마감 수정 |

기록: [WORKLOG](ISSUE_109_WORKLOG.md). 승인 없이 remote write·PR·운영 변경하지 않는다.


## #108 scope 연동 (추가 사용자 결정)

`participant.application.port.output`의 `GuestProofLevel { VERIFIED_GUEST, REPLACE_ONLY }`, `GuestOwnershipProof(roomId: MeetingRoomId, participantId: ParticipantId, level: GuestProofLevel)`, `GuestOwnershipProofPort.resolve(roomId, rawCredential, forUpdate=false): GuestOwnershipProof?`를 공통 계약으로 쓴다. #108이 유효성을 검증한 proof를 공급하며 HTTP JSON의 주장만으로 이 타입을 만들지 않는다. rawCredential이 없어도 검증된 별도 요청 context proof를 port에서 제공할 수 있다.

`participant.application.service.ParticipantOwnershipResolver.resolve(roomId, rawCredential, operation): Participant`의 operation은 READ_OWN/REPLACE_OWN/HOST_COMMAND/CLAIM_ACCOUNT다. 계정은 normal read/replace에서 항상 우선하며 참여가 없으면 guest fallback하지 않는다. VERIFIED_GUEST는 자기 read/replace, REPLACE_ONLY는 기존 입력 대체만 허용한다. REPLACE_ONLY는 원문 GET/claim/HOST에 사용할 수 없으며 GUEST_PROOF_INSUFFICIENT403이다. HOST_COMMAND는 로그인 계정의 HOST만 허용하며 계정누락401 ACCOUNT_REQUIRED, 회원nonHOST403 HOST_PERMISSION_REQUIRED다.

`requireVerifiedGuestForClaim(roomId, rawCredential): Participant` 및 resolve(CLAIM_ACCOUNT)는 로그인 계정+VERIFIED_GUEST를 요구한다. port의 forUpdate=true는 기존 쿠키 adapter의 guest session row lock을 유지한다. room row lock은 claim application service에서 먼저 획득한다. proof room/participant 바인딩과 여전히 guest-owned인지 DB에서 재검사한다. 같은방 계정기록409는 claim service의 충돌검사로 유지한다. 기본 adapter는 기존 guestcookie이고 #108 adapter로 대체/합성할 bean 경계를 둔다.

통합 시 SubmissionService 저장(REPLACE_OWN)/조회(READ_OWN) 소유권 hunk만 합치며 별도 deadline 변경을 보존한다. #109 V11 / #108 V12 / #107 V13 / #110 V14 예약이다. 공통resolver의 단위·HTTP·DB경합 검증과 #108 실제adapter 통합 검증은 구분해 기록한다.

## #110 인증 거부 연결

`account.domain.AuthFailure(val code:String):RuntimeException(code)`의 `AUTH_ACCESS_INVALID`가 invalid Bearer의 계약이다. 중복/형식/길이/JWT실패는401이며 Authorization없는 경우에만 CurrentAccountPort가null이다. `/api/**` interceptor가 선검증하고 guestfallback을 하지 않는다. 인프라불가 AUTH_UNAVAILABLE503, exactOrigin AUTH_ORIGIN_NOT_ALLOWED403이다. #110은401 AUTH_ACCESS_INVALID를DENIED감사로 연결하고 예외를 null로 삼키지 않는다. 실제Bearer/키/쿠키/개인정보를 감사내용에 넣지 않는다.
