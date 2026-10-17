# Issue #108 독립 보안 테스트 설계

- 역할: 구현과 분리된 테스트 설계자. 신규 프로덕션 구현의 동작을 기대값 근거로 사용하지 않는다.
- 기준일: 2026-10-17. 완료 목표: 2026-10-18 KST까지 구현·독립 검증 완료.
- 기준: 사용자 확정 제품 결정, 기존 PRD FR-002C/FR-007A/FR-008A/FR-009, Architecture의 게스트 쿠키·Origin·비공개·트랜잭션 경계, development.md의 고위험 역할 분리/TDD 규칙.
- 현재 PRD의 다른 기기 자동 복구 금지는 아래 명시적 재접근 결정으로 확장된다. 구현자는 PRD/Architecture/ADR을 함께 정합화한다.
- 이슈 본문 직접 조회: `gh issue view 108 --json title,body,url`은 GraphQL Forbidden. 부모 세션이 connector로 본문을 확인해 전달된 제품 결정과 같음을 확인했다. 오래된 HumanBlocker는 사용자의 이번 명시 재개로 대체되었다.
- 이 문서는 검증 계획이며 별도 프론트엔드 API 명세를 대체하지 않는다. 최종 HTTP 계약은 생성 OpenAPI와 실행 검증이 기준이다.

## 1. 확정 동작과 검증 불변식

1. 이름은 중복될 수 있다. 같은 방의 동명 참여자는 각각 다른 participant 식별자를 가지며 이름으로 기존 기록을 선택하거나 합치지 않는다.
2. 암호는 선택이다. 미설정 시 다른 기기에서 대체 입력으로 수정할 수 있다는 점을 제출 전에 명확히 알린다. 대체 입력을 하기 위해 기존 원문을 조회·자동 채움하지 않는다.
3. 암호가 설정된 기록의 다른 기기 재접근은 해당 participant의 암호 검증이 필요하다. 암호를 초기 재설정하거나 HOST가 우회 복구하는 흐름은 없다.
4. 유효한 기존 소유 세션은 기존 접근 범위를 유지한다. 이름·암호 입력을 다시 요구하거나 소유권을 다른 동명 참여자로 바꾸지 않는다.
5. 검증으로 얻는 제출 접근과 HOST 명령 권한을 구분한다. 재접근 때문에 HOST 마감·재분석·확정 권한이 생겨서는 안 된다.
6. 암호 없는 대체 입력 권한은 기존 원문을 볼 수 있는 소유 증명이 아니다. 공유 링크·참여자 식별자만으로 기존 원문을 반환하지 않는다. 제출 이후 어떤 원문을 읽을 수 있는지는 최종 공개 권한 계약으로 확인하며, 원문 반환에 관한 권한 확대는 자동 추론하지 않는다.
7. 회원은 이름·암호를 입력하지 않는다. 게스트 연결에는 유효한 소유 증명 + 로그인 + 명시적 동의가 모두 필요하다. 같은 방 회원 참여 기록과 충돌하면 기존 원문·소유권을 덮어쓰지 않고 명시적 충돌을 반환한다. #109 공통 소유권 계약은 수신했으며 실제 공유 패치와 통합 검증은 대기 중이다.
8. 마감·확정·잠금 상태는 재접근으로 되돌아가지 않는다. 읽기 권한과 현재 수정 가능 여부를 따로 검증한다.
9. 암호 원문·해시·세션 자격 증명은 HTTP JSON·URL·오류·로그·metric label에 노출하지 않는다. 암호는 무작위 salt가 있는 비용 기반 단방향 해시로 저장한다.
10. 오입력·대상 부재 등 실패 응답은 비밀 정보나 암호 존재 여부를 구분해 알려주지 않는다. 한도 초과는 일반적인 429로 제한할 수 있으나 내부 카운터·식별 정보는 노출하지 않는다.

## 2. 공개 계약 제안 및 역할 경계

테스트 구현 전에 구현자와 포트·성공/거부 결과만 합의하며 내부 알고리즘·저장 방법은 테스트 기대값으로 사용하지 않는다. 암호 포트와 아래 순수 재접근 정책은 합의 완료이며 HTTP와 use case 경계는 후속 합의한다.

```kotlin
interface GuestPasswordPort {
    fun hash(rawPassword: String): String
    fun verify(rawPassword: String, passwordHash: String): Boolean
}
```

- 포트의 입력은 1~128 Unicode 코드 포인트이며 원문을 자동 trim/소문자화하지 않는다. 공백도 코드 포인트로 보존한다. 선택 암호 부재는 null로 표현한다. hash의 빈 값/초과 입력은 IllegalArgumentException, verify의 빈 값/초과 입력은 false다.
- 확정 해시 공개 형식은 `pbkdf2-sha256$600000$base64url-no-padding(salt16)$base64url-no-padding(key32)`이다. PBKDF2WithHmacSHA256 600000회, 무작위 salt 16바이트, key 32바이트를 사용한다. 임의 cost 변경은 거부하며 알고리즘 검증에는 Python hashlib로 별도 생성한 고정 fixture를 사용한다. 새로운 의존성은 추가하지 않는다.
- 해시 손상·알 수 없는 형식은 인증 성공이나 원문 비교로 fallback하지 않는다. 상세 실패 노출 없이 거부한다.
- 재접근은 방 + participant 불투명 식별자 + 선택적 암호를 입력으로 받는 독립 use case를 제안한다. 이름은 재접근 키가 아니다.
- 성공 결과는 기존 소유 세션 유지 / 암호 증명으로 제출 읽기·수정 / 무암호 대체 입력 전용을 구분할 수 있어야 한다. 이 결과가 영속 세션·쿠키·응답에 어떻게 매핑되는지는 공개 계약 수신 후 고정한다.
- 확정 순수 정책: `participant.application.service.GuestReaccessPolicy(passwords: GuestPasswordPort).evaluate(record: GuestReaccessRecord?, proof: GuestReaccessProof): GuestReaccessDecision`. 모델은 `participant.domain`에 두며 record는 `roomId: UUID, participantId: UUID, ownerSessionId: UUID?, passwordHash: String?, memberLinked: Boolean = false`, proof는 `roomId: UUID, participantId: UUID, activeSessionId: UUID?, rawPassword: String?`를 갖는다. 마감/확정/레이트리밋은 이 순수 정책이 아닌 유스케이스 경계에서 검증한다.
- 정책 결과: room/id 불일치·record 부재·ownerSessionId=null은 DENIED; 일치하는 유효 기존 non-null owner는 memberLinked/암호 입력과 관계없이 OWNER; 그 외 memberLinked는 DENIED; 올바른 암호 증명만 VERIFIED_GUEST(제출 읽기·허용 상태 수정, HOST 권한 없음); 암호 없는 비소유 기기는 REPLACE_ONLY(기존 원문 읽기 없음); 나머지는 DENIED다. 회원 소유 snapshot은 ownerSessionId=null, memberLinked=true다. null owner와 null 요청 session이 같다고 OWNER를 부여하지 않으며, invalid guest snapshot의 null owner도 모든 조합에서 fail closed한다.
- HTTP 제안: 상태 변경 POST + 정확한 Origin 허용 목록, 자격 증명은 기존과 동일한 보안 HttpOnly 쿠키, 일반 검증 실패는 공통 401/코드, 시도 제한은 공통 429, 마감·확정 수정은 409. 세부 경로/코드는 합의 후 OpenAPI 테스트로 고정한다.
- 암호 유무와 관계없는 오류 응답 비교는 HTTP 상태·고정 오류 코드·필드 구성을 검증한다. elapsed time을 동일하게 assert하는 불안정한 타이밍 테스트는 사용하지 않는다. 대상 부재에도 해시 비용 처리가 있는지는 독립 리뷰한다.
- 확정 시도 제한 포트: `participant.application.port.output.GuestReaccessAttemptPort.check(roomId: UUID, participantId: UUID, clientAddress: String): Unit`. adapter는 `GuestReaccessAttemptGuard(limiter: RateLimitPort)`다. IP별 15분 30회와 방·participant별 15분 5회의 두 분산 키를 검사하고 쿠키에 의존하지 않는다. 키는 `meetme:guest-reaccess:ip:` 또는 `meetme:guest-reaccess:record:` 뒤 SHA-256 lowercase hex 64자로 구성하며 digest preimage는 공개 계약으로 고정하지 않는다.
- 제한 거부는 같은 패키지의 `GuestReaccessLimitException(code = TOO_MANY_ATTEMPTS, retryAfter: Duration)`이고 Redis/limiter RuntimeException은 `LIMIT_UNAVAILABLE`과 null retryAfter로 fail closed한다. 내부 예외 cause와 IP/방/참여자 식별자를 예외에 싣지 않는다. 허용 요청은 두 한도를 모두 검사하며 테스트는 호출 순서를 검증하지 않는다.

작업 파일 소유:

| 역할 | 소유 범위 |
| --- | --- |
| 독립 테스트 설계자 | 이 문서, 합의 후 신규 암호·재접근 전용 테스트 파일 |
| #108 구현자 | 독립 암호 포트/어댑터, 재접근 use case/어댑터·영속 grant, 관련 문서 |
| #109 세션 | 공통 회원 인증·소유권 연결 계약·구현. 수신 전 기대값 고정 금지 |
| 타 세션 | SubmissionService.kt 마감 패치, #110 계측 파일. 이 역할에서 수정 금지 |

### #109 실제 공통 소유권 계약 매핑

2026-10-17 부모 세션이 전달한 실제 계약이다. 아직 이 작업공간에 공유 skeleton/구현 패치를 수신하지 않았으므로 아래 타입을 별도로 복제하거나 같은 이름의 테스트용 프로덕션 타입을 만들지 않는다.

- `participant.application.port.output.GuestProofLevel`: `VERIFIED_GUEST`, `REPLACE_ONLY`.
- 같은 패키지의 `GuestOwnershipProof(roomId: MeetingRoomId, participantId: ParticipantId, level: GuestProofLevel)`.
- `GuestOwnershipProofPort.resolve(roomId: MeetingRoomId, rawCredential: String?, forUpdate: Boolean = false): GuestOwnershipProof?`.
- `participant.application.service.OwnershipOperation`: `READ_OWN`, `REPLACE_OWN`, `HOST_COMMAND`, `CLAIM_ACCOUNT`.
- `ParticipantOwnershipResolver(accounts: CurrentAccountPort, proofs: GuestOwnershipProofPort, participants: ParticipantRepository).resolve(roomId, rawCredential, operation): Participant` 및 `requireVerifiedGuestForClaim(roomId, rawCredential)`.
- `Participant`는 `guestSessionId`와 `userId` 중 정확히 하나만 소유자로 갖는다. 공통 ID에는 기존 `MeetingRoomId`/`ParticipantId`를 사용하며 별도 계정·소유권 타입을 만들지 않는다.

| #108 독립 판정 | #109 공통 증명 | 허용 범위 |
| --- | --- | --- |
| OWNER | VERIFIED_GUEST | 기존 본인 제출 읽기·허용 상태 대체 입력, 로그인·명시 동의가 있는 연결의 증명으로 사용 |
| VERIFIED_GUEST | VERIFIED_GUEST | 본인 제출 읽기·허용 상태 대체 입력, 로그인·명시 동의가 있는 연결의 증명으로 사용 |
| REPLACE_ONLY | REPLACE_ONLY | 본인 기록 대체 입력만; 기존 원문 읽기·HOST·계정 연결 거부 |
| DENIED | null | 어떤 guest 소유권도 부여하지 않음 |

OWNER를 VERIFIED_GUEST에 매핑하는 것은 제출 소유 증명의 매핑이다. 공통 resolver에서 HOST 명령은 account가 소유한 HOST participant만 허용한다. 게스트 record에 남은 HOST role이나 기존 session role을 공통 proof의 권한으로 복제하지 않는다. 기존 익명 HOST 흐름의 #109 전환은 부모 공통 패치의 명시적 통합 범위로 관리하며 #108에서 병렬 수정하지 않는다.

| 주체/증명 | READ_OWN | REPLACE_OWN | HOST_COMMAND | CLAIM_ACCOUNT |
| --- | --- | --- | --- | --- |
| VERIFIED_GUEST | 허용 | 방 상태 허용 시 허용 | 거부 | 로그인·명시적 동의·충돌 없음이 모두 필요 |
| REPLACE_ONLY | 403 | 방 상태 허용 시 허용 | 403 | 403 `GUEST_PROOF_INSUFFICIENT` |
| 계정 소유 MEMBER | 본인만 허용 | 본인·방 상태 허용 시 허용 | 거부 | 연결 대상의 별도 VERIFIED_GUEST 증명이 필요 |
| 계정 소유 HOST | 본인만 허용 | 본인·방 상태 허용 시 허용 | 허용 | 연결 대상의 별도 VERIFIED_GUEST 증명이 필요 |

계정 연결의 guest 증명은 `forUpdate=true`로 해석·조회해야 한다. 증명이 없거나 부족하면 `GUEST_PROOF_INSUFFICIENT` 403이며, 같은 방의 기존 회원 participant와 충돌하면 `PARTICIPANT_CONFLICT` 409다. 로그인 자체가 게스트 증명을 대신하지 않으며, 증명만으로 로그인·동의를 대신하지 않는다.

### 공유 패치 후 독립 통합 수용 테스트

아래 목록은 구현 완료 주장이 아니다. 실제 #109 패치를 수신한 뒤 공개 타입으로 테스트를 먼저 작성하고 필요한 skeleton RED를 확인한다. 기존 47개 독립 테스트는 frozen 상태를 보존한다.

| ID | 입력/상황 | 수용 기준 |
| --- | --- | --- |
| X01 | 게스트 VERIFIED_GUEST, READ_OWN/REPLACE_OWN | 증명의 room/participant에 해당하는 기존 participant 반환; 새 participant나 계정 소유권 미생성 |
| X02 | 게스트 REPLACE_ONLY, REPLACE_OWN | 동일 participant의 대체 입력 허용; 결과에 기존 원문 조회 권한을 추가하지 않음 |
| X03 | REPLACE_ONLY, READ_OWN/HOST_COMMAND/CLAIM_ACCOUNT | 모두 403; 원문 미조회·불변 저장·연결 없음; claim은 GUEST_PROOF_INSUFFICIENT |
| X04 | VERIFIED_GUEST + guest HOST role, HOST_COMMAND | 거부; guest role이 account HOST 권한으로 승격되지 않음 |
| X05 | 유효한 계정 인증 + 계정 MEMBER/HOST | 실제 계정 소유 participant 기준 본인 작업 허용; HOST_COMMAND는 account HOST만 허용; 동시 guest proof와 role 혼합 금지 |
| X06 | 무효/만료/위조 Bearer + 유효 guest 쿠키 또는 context proof | 계정 인증 오류를 유지; guest fallback으로 읽기·수정·HOST·claim 성공 금지 |
| X07 | Bearer 없이 account 부재 + 유효 guest proof | 정상 guest 경로 사용; account 부재와 잘못된 Bearer를 같은 상태로 취급하지 않음 |
| X08 | rawCredential=null + 요청 context의 VERIFIED_GUEST proof | proof port 호출을 생략하거나 조기 거부하지 않음; 증명 범위 내 READ_OWN/REPLACE_OWN 허용 |
| X09 | rawCredential=null + REPLACE_ONLY 또는 null context proof | 각각 대체 입력만 허용 또는 거부; null credential만으로 소유권 추정 금지 |
| X10 | requireVerifiedGuestForClaim에 null/non-null rawCredential | 모두 proof port에 그대로 전달하며 forUpdate=true; VERIFIED_GUEST만 반환/허용 |
| X11 | claim의 proof 부재·REPLACE_ONLY·로그인 부재·동의 부재 | 각 누락을 독립 거부; guest/member 기록과 원문·소유권·완료 인원 불변 |
| X12 | 계정 소유 participant가 같은 방에 이미 존재 | PARTICIPANT_CONFLICT 409; guest/member 자동 병합·원문 덮어쓰기·다른 기록 삭제 없음 |
| X13 | proof의 room/participant와 저장 participant의 room 불일치/부재 | 본인 권한 미부여; 타 방 원문·참여자 존재 여부를 권한 성공 응답으로 노출하지 않음 |
| X14 | 성공/실패 claim과 재접근이 동시에 실행 | forUpdate 경계가 실제 트랜잭션 내에서 작동; 정확히 한 소유자, 충돌 명시, 기존 원문 유실 없음 |
| X15 | READ_OWN과 REPLACE_OWN, requireVerifiedGuestForClaim 각각 호출 | proof port에 실제 room/credential이 보존되고, claim의 forUpdate=true가 누락되지 않음; 내부 호출 순서를 기대값으로 고정하지 않음 |
| X16 | 회원 소유 snapshot 또는 불완전 guest snapshot에서 ownerSessionId=null | null active session·옛 암호·임의 session 모두 DENIED→null; null==null OWNER 또는 REPLACE_ONLY 우회 없음 |

HTTP 연결 후에는 X01~X16을 실제 쿠키·Bearer·Origin과 PostgreSQL로 재검증한다. 상태별 수정 거부(L01~L04)와 기존 원문 노출 금지(W01~W05)는 resolver 단위 성공만으로 대체하지 않는다.

## 3. 테스트 행렬

각 보안 테스트는 A(기존 소유 브라우저), B(새 브라우저), C(공격자)를 분리한다. 원문에는 구분 가능한 비밀 문자열을 사용하고 응답뿐 아니라 저장된 head/version·참여자 수·소유권 불변식도 검증한다.

| ID | 경계·시나리오 | 기대 결과 | 층 |
| --- | --- | --- | --- |
| P01 | 같은 암호를 두 번 hash | 원문과 다른 해시, 서로 다른 salt 결과, 둘 다 원문 verify 성공 | 암호 계약 |
| P02 | 원문/한 글자 다른 암호/빈 암호 | 원문만 성공, 오입력 거부, 검증이 해시를 변경하지 않음 | 암호 계약 |
| P03 | 암호 앞뒤 공백·대소문자·Unicode | 입력 그대로의 암호만 성공. trim, case fold, Unicode 축약 없음 | 암호 계약 |
| P04 | 손상·절단·알 수 없는 hash | 성공하지 않으며 원문·해시를 오류에 노출하지 않음 | 암호 계약 |
| P05 | 서로 다른 participant가 같은 암호 | 각자 salt, 타 participant 접근 권한과 무관 | 저장/통합 |
| P06 | 선택 암호 미설정 | 빈 해시나 원문 대신 암호 부재로 저장; 기존 보안 세션만 읽기 유지 | 통합 |
| P07 | 허용 최대 길이/초과 | 공시된 입력 제약대로 수락/거부, 긴 입력 조용한 절단으로 다른 암호가 같아지지 않음 | 계약/통합 |
| I01 | 같은 방, 같은 이름, 다른 브라우저 | 다른 participant 식별자로 독립 등록, 원문·완료 인원 혼합 없음 | PostgreSQL/HTTP |
| I02 | A의 유효 소유 세션 재진입 | 기존 participant·이름·역할·원문 접근 범위 유지, 새 participant 미생성 | use case/HTTP |
| I03 | B가 이름 또는 초대 링크만 제시 | 동명 기록 소유권·기존 원문 읽기 미부여 | HTTP |
| I04 | 다른 방의 participant 식별자 사용 | 실패 응답, 다른 방 원문·권한 미노출 | use case/HTTP |
| I05 | 새 브라우저의 암호 있는 기록 재접근 | 정확한 participant + 정답 암호에서만 제출 읽기/현재 허용 범위 수정 | use case/HTTP |
| I06 | 암호 누락/오입력/임의 식별자 | 동일한 공개 실패 계약, 쿠키/소유권·원문/수정 상태 불변 | use case/HTTP |
| I07 | B의 만료·위조·회수 쿠키 | 기존 소유 세션으로 취급하지 않음, 유효한 재접근 증명 없이 읽기 불가 | HTTP |
| I08 | A의 유효 소유 세션 + 틀린/미입력 암호 | 재증명 요구 없이 기존 접근 유지; 새로운 입력으로 소유권 교체 금지 | use case/HTTP |
| W01 | B의 무암호 기록 재접근 | 대체 입력 권한만 부여, 기존 raw_text·버전 본문·Calendar snapshot 미포함 | use case/HTTP |
| W02 | 대체 입력 전용 B에서 모든 읽기 경로 호출 | GET submission, room viewer, 결과 부가정보 등으로 기존 원문 우회 노출 금지 | HTTP |
| W03 | B의 무암호 대체 입력 제출 | 같은 participant의 새 불변 버전, 완료 인원 증가 없음, A의 원문은 이전 버전으로 보존 | PostgreSQL/HTTP |
| W04 | C가 raw_text null/빈/부분 payload 전송 | 공시된 제출 검증에 따라 거부; 기존 원문 반환·자동 보완·부분 덮어쓰기 없음 | HTTP/PostgreSQL |
| W05 | 암호 있는 기록에 무암호 대체 경로 요청 | write-only 권한도 부여하지 않음 | use case/HTTP |
| A01 | HOST 기록의 암호로 B 재접근 | 제출 접근만 획득; close/retry/confirm/revision-open 등 HOST 명령 거부 | HTTP |
| A02 | HOST가 타인의 암호 재설정/기록 복구 시도 | 공개 기능 없음, 기존 원문·해시·소유권 불변 | HTTP/검토 |
| A03 | 재접근 세션으로 다른 participant 식별자 요청 | 읽기·수정 거부, name 같아도 불변 | HTTP |
| A04 | Origin 누락·다른 origin·유사 prefix·null | 상태 변경 모두 거부, 쿠키/권한·DB 변경 없음 | HTTP |
| A05 | 재접근 성공 쿠키 | Secure/HttpOnly/SameSite=Lax/host-only/Path=/api, 원문·해시 body/URL 미포함 | HTTP |
| R01 | 잘못된 암호 N회 후 N+1회 | 설정 경계부터 일반 429, 정답 입력도 제한 계약을 우회하지 않음 | limiter/HTTP |
| R02 | 새 쿠키·세션 교체·식별자 표현 변경·동시 시도 | 동일 대상의 시도 제한 우회 불가, 허용 횟수 초과의 성공 없음 | 통합/경합 |
| R03 | 다른 대상·다른 방 시도 및 구간 종료 | 계약에 따라 독립 제한/회복, 무관 사용자의 전역 차단 없음 | limiter/통합 |
| R04 | limiter 장애 | 암호 검증·권한 발급이 무제한 허용되지 않음; 원문·권한 불변 | 장애/통합 |
| R05 | 오입력/대상 부재/대상 다른 방/암호 없음 | 고정 오류 코드·필드에 비밀 상태/해시/원문 미포함. 암호 부재의 성공 응답은 대체 입력 범위만 표시 | HTTP |
| L01 | deadline - epsilon / deadline / + epsilon | deadline부터 모든 대체 입력·정답 암호 기반 수정 거부, 원문·head·완료 인원 불변 | Clock/통합 |
| L02 | 수동 마감·N명 자동 마감 | 재접근은 종료 상태를 재개하지 않음; 읽기 허용 여부는 보유 grant 유지 | 통합 |
| L03 | 확정·분석중·분석지연·닫힌 수정 라운드 | 현재 상태 수정 금지, 재접근이 수정 라운드/분석 실행/확정을 만들지 않음 | 통합 |
| L04 | 열린 수정 라운드 | 유효한 허용 범위만 수정; 원래 마감·확정 규칙 우회 없음 | 통합 |
| C01 | 같은 새 브라우저의 재접근 두 요청 경합 | 중복 participant/서로 다른 잘못된 소유권 생성 없음, 실제 증명 범위 유지 | PostgreSQL 경합 |
| C02 | 기존 소유자와 대체 입력 요청 동시 수정 | DB 직렬화 계약에 따라 불변 버전·단일 head, 참여 인원 미증가·원문 유실 없음 | PostgreSQL 경합 |
| C03 | 마지막 허용 제출과 close/confirm 경합 | 선형화 순서와 일치. 종료 후 저장/새 batch 변경 없음 | PostgreSQL 경합 |
| C04 | 검증/발급 도중 저장 실패 | 발급 쿠키와 DB 권한의 불일치·반쪽 소유권 없음, rollback | 장애/통합 |
| C05 | 여러 기기 재접근과 기존 A 세션 | A의 유효한 기존 권한 미회수, 새 grant가 A/HOST 권한을 복제하지 않음 | PostgreSQL/HTTP |
| M01 | 회원 참여 정상 | 이름·암호 필드 없이 기존 회원 기록 식별; 게스트 이름과 자동 매칭 금지 | #109 패치 대기 |
| M02 | 증명/로그인/동의 각각 하나 누락 | 연결 거부, 기존 guest/member 소유권·원문 불변 | #109 패치 대기 |
| M03 | 무암호 대체 입력 권한으로 회원 연결 | 기존 원문 소유 증명으로 인정하지 않음 | #109 패치 대기 |
| M04 | 같은 방 기존 회원 기록 충돌 | 명시적 409 충돌, guest/member 기록·원문 덮어쓰기 없음 | #109 패치 대기 |
| M05 | 동시 연결/재접근 | 단일 귀속·명시적 충돌, 타 계정 탈취·기존 유효 소유자 원문 노출 없음 | #109 패치 대기 |

## 4. 독립 검증 순서와 RED 증거

1. 공개 계약 합의 후 담당 신규 테스트 파일명을 구현자에게 알린다. 구현자는 테스트 컴파일에 필요한 port·값·메서드 skeleton만 제공하고 동작은 `TODO("TDD RED: <spec-id>")`로 둔다.
2. 가장 작은 암호 계약 테스트 → 재접근 use case → 실제 PostgreSQL/HTTP 순서로 RED를 확인한다. 컴파일 실패·환경 실패·컨테이너 기동 실패는 유효한 RED가 아니다.
3. RED 원본은 `.codex/tdd-evidence/issue-108-*.log`, 추적 요약은 `.tdd/red/issue-108-*.json`으로 보관한다. 요약은 기준 revision·파일 SHA-256·명령·실패명·예상 실패 분류·시각을 포함한다. 비밀값/원문 payload는 기록하지 않는다.
4. 확정 테스트의 기대값을 구현에 맞춰 낮추지 않는다. 공개 계약 변경이나 테스트 결함이 확인되면 설계로 돌아가 테스트 수정·새 RED를 기록한다.
5. 구현자가 GREEN을 확인한 후 독립 설계자가 상태/권한/오류·DB 불변식 누락을 검토한다. fault injection은 최종 코드와 분리한 임시 작업본에서 한 결함씩 적용하고 각 결함이 해당 테스트를 실패시키는지 확인한 뒤 원복한다.
6. 전체 게이트: `./gradlew ktlintCheck assemble test`. Docker/로컬 DB와 필요한 도구가 준비되지 않은 경우 실행 성공으로 표시하지 않고 차단된 실제 테스트를 보고한다. 실 공급자·운영 credential 호출은 사용하지 않는다.

## 5. 결함 주입 계획

최종 결과는 ID·주입 지점의 책임·탐지 테스트·예상 거부/실제 결과·원복 확인·실행 명령을 표로 기록한다. 특정 내부 함수 이름은 구현 완료 전 고정하지 않는다.

| 결함 ID | 의도적 결함 | 반드시 탐지할 ID | 통과 기준 |
| --- | --- | --- | --- |
| F01 | verify를 항상 true로 변경 | P02, I06, W05 | 정답 외 입력이 성공하여 테스트 실패 |
| F02 | hash에 고정 salt/평문/SHA digest 사용 | P01, P05 + 독립 알고리즘 리뷰 | 서로 다른 hash 불변식 또는 비용 기반 해시 리뷰 실패 |
| F03 | 암호 앞뒤 공백 trim/암묵적 절단 | P03, P07 | 구분되는 암호가 같은 것으로 수락되어 테스트 실패 |
| F04 | 무암호 재접근에 READ_WRITE 부여 | W01, W02, M03 | 과거 원문 노출 또는 잘못된 소유 증명 수락 탐지 |
| F05 | displayName으로 기존 participant 선택 | I01, I03, A03 | 동명 참여자의 ID/원문 독립성 테스트 실패 |
| F06 | 재접근 grant에 HOST 원래 role 복제 | A01 | 실제 HOST 명령이 성공하여 테스트 실패 |
| F07 | 입력 participant의 room scope 검증 제거 | I04, A03 | 다른 방/타 participant 읽기·수정으로 테스트 실패 |
| F08 | 재접근이 기존 owner session을 교체/회수 | I02, C05 | A의 기존 접근 회귀 탐지 |
| F09 | limiter bypass 또는 증가를 비원자 처리 | R01, R02, R04 | 한도 후 검증/발급 성공 또는 장애시 허용 탐지 |
| F10 | 대상 부재/오입력에 다른 공개 상세 code 추가 | I06, R05 | 공개 실패 응답 비교 실패 |
| F11 | write-only 제출 경로의 마감/확정 검증 제거 | L01, L03, C03 | 종료 후 새 버전·head 변경 탐지 |
| F12 | 재접근·제출 저장의 lock/원자성 제거 | C01, C02, C04 | latch로 만든 경합에서 중복/부분 저장/유실 탐지 |
| F13 | Origin 검증 제거 | A04 | 허용되지 않은 출처의 권한 발급 성공 탐지 |
| F14 | password/hash/rawText를 응답·구조 로그에 추가 | W01, A05, 로그 검사 | 민감 문자열 포함 검증 실패 |
| F15 | 연결의 proof/login/consent 또는 충돌 검사 생략 | M02, M03, M04, M05 | 권한 탈취/덮어쓰기 탐지 (#109 계약 후) |

성공률 숫자나 라인 커버리지로 보안 경계 통과를 대체하지 않는다. 경합은 barrier/latch로 같은 선형화 지점을 경쟁시키며 `Thread.sleep`에 의존하는 우연한 순서 검증을 피한다.

## 6. 현재 상태와 남은 계약

- 설계 완료, 구현 파일 변경 없음. 문서 작성 자체는 RED/GREEN 증거가 아니다.
- 독립 계약 테스트 작성 완료: 암호 `participant/adapter/output/security/GuestPasswordHasherTest.kt` 12개, 순수 정책 `participant/application/service/GuestReaccessPolicyTest.kt` 15개, 시도 제한 `participant/adapter/output/security/GuestReaccessAttemptGuardTest.kt` 8개, 인코딩 경계 `participant/adapter/output/security/GuestPasswordEncodingBoundaryTest.kt` 7개, 계정 경계 `participant/application/service/GuestReaccessAccountBoundaryTest.kt` 5개. 경로의 기준은 `src/test/kotlin/com/meetme/server/`다. 47개 테스트를 frozen 상태로 보존한다.
- 부모 세션에서 focused 47개·결함 주입·전체 guard를 실행 중이다. 실행 결과는 부모의 실제 로그/증거를 기준으로 기록하며 이 문서 갱신만으로 통과 처리하지 않는다.
- #109 실제 소유권 계약 수신 완료, 공유 skeleton/구현 패치 수신 대기. X01~X16 독립 통합 수용 목록을 추가했으며 새 공통 타입·인증 모델을 복제하지 않는다.
- 현재 47개 계약 테스트는 PostgreSQL 경합·HTTP 전체 흐름 검증을 대체하지 않는다. 공유 패치 후 HTTP/재접근 유스케이스와 위 공통 계약으로 통합 테스트를 작성한다.
- 기존 원문 노출 없는 무암호 대체 입력 이후에도 REPLACE_ONLY는 READ_OWN을 허용하지 않는다. 기존 원문은 어떤 경우에도 자동 반환하지 않는다.
- 기존 제출 마감 패치와 계측 파일은 각 소유 세션에서 검증·통합한다.
