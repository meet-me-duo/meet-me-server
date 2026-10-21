# #108 공통 소유권 연결 준비

이 문서는 독립 단계의 #109 연결 계약과 당시 준비 상태를 보존한다. 실제 원격 #109 서버/웹 소스를 받은 뒤 별도 통합 브랜치에서 HTTP·V12·기존 방 화면을 연결하는 후속 단계로 전환했다. 최신 구현·진행·원격 SHA와 승인 범위는 [실제 통합 기록](ISSUE_108_INTEGRATION.md)을 따른다. 아래의 소스 대기·미연결 설명은 독립 단계 당시 상태다.

## 2026-10-17 부모가 전달한 #109 확정 계약

- `account.domain.AccountPrincipal(accountId: UUID, displayName: String)`
- `account.application.port.output.CurrentAccountPort.currentAccount(): AccountPrincipal?`
- 검증된 Access 계정만 반환한다. Authorization 누락은 null, 잘못된 Bearer는 401이고 게스트 fallback은 없다.
- Participant는 guestSessionId 또는 userId 중 정확히 하나를 갖는다. `findByRoomAndUser`와 원자적 claim, 기존 Participant 모델·ports·persistence·RoomLifecycle·인증 CORS·소유 resolver는 #109 소유다.
- `POST /api/rooms/{inviteCode}/claim`은 로그인+유효 게스트 증명+명시 요청을 요구하고 room lock 아래 실행한다. 동일 방 기존 회원 기록 충돌은 409 `PARTICIPANT_CONFLICT`. ID·역할·제출을 보존한다.
- #109의 `V11__add_accounts_and_participant_ownership.sql`과 충돌하지 않도록 #108은 V12를 예약한다. 아직 활성 Flyway 파일은 추가하지 않았다.
- 인증 응답은 `accessToken`, `expiresAt`, `account{id,displayName}`이며 Refresh는 cookie-only다.
- 현재 선택 환경에는 #109의 새 소스가 없다. 공통 런타임 타입을 복제하지 않는다. 계약 테스트 대역으로 준비한 뒤 부모 통합 단계에서 실제 구현과 연결한다.
- 추가 공통 포트·operation 계약도 수신했다(아래). 현재는 #109 skeleton 공유 패치와 실제 타입·resolver 소스를 기다린다. 런타임 타입을 복제하거나 REPLACE_ONLY를 기존 owner로 가장하는 bridge를 만들지 않는다.

## 확정된 공통 증명과 operation 계약

```kotlin
// participant.application.port.output — #109 소유
enum class GuestProofLevel { VERIFIED_GUEST, REPLACE_ONLY }
data class GuestOwnershipProof(
    val roomId: MeetingRoomId,
    val participantId: ParticipantId,
    val level: GuestProofLevel,
)
interface GuestOwnershipProofPort {
    fun resolve(roomId: MeetingRoomId, rawCredential: String?, forUpdate: Boolean = false): GuestOwnershipProof?
}

// participant.application.service — #109 소유
enum class OwnershipOperation { READ_OWN, REPLACE_OWN, HOST_COMMAND, CLAIM_ACCOUNT }
// ParticipantOwnershipResolver(accounts: CurrentAccountPort, proofs: GuestOwnershipProofPort, participants: ParticipantRepository)
// resolve(roomId, rawCredential, operation): Participant
// requireVerifiedGuestForClaim(roomId, rawCredential): Participant
```

| #108 독립 정책 결과 | 공통 증명 | 허용 범위 |
| --- | --- | --- |
| OWNER | VERIFIED_GUEST | 기존 검증 세션의 본인 읽기·수정; HOST는 로그인 후 명시 연결 |
| VERIFIED_GUEST | VERIFIED_GUEST | 읽기·수정, 로그인+명시 동의 시 claim |
| REPLACE_ONLY | REPLACE_ONLY | 대체 입력만; 읽기·HOST·claim은 403 |
| DENIED | null | 권한 없음, 공통 일반 오류 |

로그인 계정은 자신의 기록을 읽고 수정한다. HOST 명령은 계정 HOST만 가능하다. claim은 로그인과 `forUpdate=true`로 얻은 VERIFIED_GUEST 증명을 요구하며 불충분한 증명은 403 `GUEST_PROOF_INSUFFICIENT`, 동일 방 회원 기록 충돌은 409 `PARTICIPANT_CONFLICT`다. ID·역할·제출은 기존 계약대로 보존한다.

rawCredential이 null이어도 검증된 요청 context 증명이 있을 수 있으므로 port 호출 전 거부하지 않는다. #108 adapter는 검증이 완료된 서버 측 증명만 제공하며 HTTP body의 level을 신뢰하지 않는다. 같은 방 범위·유효 세션·저장된 grant·읽기/수정 범위·claim 경합을 실제 adapter/resolver 통합 테스트에서 확인한다. 현재 독립 정책의 activeSessionId 입력은 이 trust boundary의 구현을 대신하지 않는다.

## 준비한 독립 경계

| 파일/계약 | 담당과 의미 |
| --- | --- |
| `GuestPasswordPort` / `GuestPasswordHasher` | #108. null이 아닌 선택 암호의 hash/verify, 회원 비밀번호와 별개 |
| `GuestReaccessRecord` / `GuestReaccessProof` / `GuestReaccessDecision` | #108. 서버가 검증한 activeSessionId와 room/participant ID만 판정 입력으로 사용 |
| `GuestReaccessPolicy.evaluate` | #108. OWNER / VERIFIED_GUEST / REPLACE_ONLY / DENIED |
| `GuestReaccessAttemptPort` / `GuestReaccessAttemptGuard` | #108. 모든 성공·실패 시도 전에 IP+대상별 Redis 제한; Redis 장애 시 fail closed |
| 웹 `GuestParticipation` | #108. props callback으로 회원/게스트·재접근·대체 입력을 표현. HTTP URL을 가정하지 않음 |
| 기존 `Participant`, `ParticipantPorts`, 영속 Records/Mappers, 공통 계정·인증 resolver, guest→member 연결 | #109 소유. 실제 resolver의 세부 진입점 수신 대기 |
| 기존 `RoomLifecycleService` / `RoomLifecycleController` / DTO, 웹 `RoomPage`·`api/client`·인증 상태 | #109 소유. 별도 patch/단일 편집자로 통합 |
| `SubmissionService.kt` 마감 패치 / #110 계측 파일 | 이 세션 변경 없음 |

## 소유권 계약에 필요한 불변식

1. 이름은 label이며 소유 증명이 아니다. 중복 이름은 participant UUID로 선택한다. 초대 코드·UUID만으로 원문 읽기 권한은 생기지 않는다.
2. 원래 유효 소유 세션의 범위를 유지한다. `activeSessionId`는 raw body가 아니라 서버 인증 결과로만 만든다. 만료/회수된 세션은 null로 해석한다.
3. VERIFIED_GUEST는 제출 읽기·수정만 허용한다. HOST 역할·계정 아카이브·마감·분석·확정 권한은 절대로 승계하지 않는다. 기존 참가자의 guestSessionId를 재접근 세션으로 옮기지 않는다.
4. REPLACE_ONLY는 기존 원문 GET과 정상 본인 복원 cache를 사용하지 않는다. 별도 대체 명령으로만 입력한다. 응답도 기존 payload를 포함하지 않는다. 대체 후에도 읽기 권한으로 승격하지 않는다.
5. 회원 연결은 로그인+유효 소유 증명+명시 동의가 모두 필요하다. REPLACE_ONLY는 회원 연결 증명으로 사용할 수 없다. 다른 기기에서 이미 회원 연결된 기록은 이전 guest 암호·무암호 정책으로 되찾지 않는다.
6. 같은 방의 다른 기존 참여자/회원 기록과 충돌하면 자동 전환·병합·덮어쓰기 없이 명시 409다. 기존 소유권, HOST, 원문·제출 version은 보존한다.
7. 암호는 최초 참여 transaction 안에서만 설정한다. 같은 세션의 반복 join이 기존 암호를 바꾸거나 제거하지 않는다. 초기 재설정·HOST 우회 복구 API를 만들지 않는다.
8. 재접근 응답의 grant는 현재 서버 세션에 묶고 만료/회수·방 보존 정책을 따른다. 원래 세션 cookie를 응답 JSON·URL·Web Storage에 넣지 않는다.
9. write 시점에 room → session/participant 순으로 기존 잠금 순서를 지켜 권한·암호/회원 연결 상태·deadline·collection/revision round·confirmation을 다시 확인한다. 조회 시 판정만으로 후속 쓰기를 허용하지 않는다.
10. 무암호 대체 시도는 선택 당시의 서버 측 revision precondition에 묶는다. 같은 revision에서 시작한 두 대체 입력은 원자적으로 하나만 수용하고 나머지는 명시 충돌/재선택으로 안내한다. 원문과 같은 입력도 새 버전을 만들어 증명을 한 번 소비하며 원문 일치 여부를 추론할 수 없게 한다. 서로 다른 계정의 연결은 한 계정만 수용한다. 대체 입력이 먼저 저장된 뒤 유효 소유자가 로그인·동의로 연결하는 순서는 최신 원문을 보존하며 두 요청 모두 성공할 수 있다. 연결이 먼저 끝나면 이전 guest 대체는 거부한다. 원문을 GET하여 precondition을 구하지 않는다. 이 범위는 실제 통합 사전 리뷰에서 원래의 포괄적 경합 문구를 선형화 규칙으로 구체화한 것이다.

## 웹 연결 조건

- `roomKey`에는 초대 코드뿐 아니라 인증 주체/guest session 전환의 opaque epoch를 포함한다. 같은 표시 이름의 다른 계정도 epoch가 달라야 한다. 실제 cookie credential·Access Token을 key로 사용하지 않는다.
- `records`는 공개 선택용 participant ID·표시 이름만 포함한다. 원문·암호·해시·원 guest session ID·타 계정 ID·HOST 권한을 전달하지 않는다.
- 최초 참여 성공 화면에서도 본인의 기록 번호를 확인할 수 있어야 한다. 다른 기기에서 중복 이름 중 자신의 기록을 명시 선택하는 근거로 사용하며, 기록 번호 자체를 권한 증명으로 취급하지 않는다.
- `editable`은 서버의 현재 명령 capability를 사용한다. 기존 수집 마감/확정 상태에서 신규 참여를 열지 않는다. 재접근 읽기는 이후 상태에서도 서버 권한에 따라 가능하다.
- `onJoin`은 회원이면 `{}`, 게스트면 trimmed 이름과 선택 암호를 전달한다. 컴포넌트는 이 callback에 URL/credential 저장 계약을 숨긴다.
- `onReaccess`는 participant ID와 선택 암호를 전달하고 VERIFIED_GUEST 또는 REPLACE_ONLY 결과만 받는다. 오류 본문은 UI에 그대로 표시하지 않는다.
- `onVerified`는 이전 개인 cache를 취소/제거한 후 서버의 room/viewer를 재조회하도록 연결한다. 비밀번호 검증 성공만으로 클라이언트가 역할을 만들지 않는다.
- `onReplace`는 participant ID와 새 rawText만 전달한다. server-side grant의 precondition을 사용하며 응답으로 기존 원문을 복원하지 않는다.
- callback 안에서 API 응답을 cache/room 상태에 적용할 때도 시작한 방·인증 epoch를 대조해야 한다. `onJoin`/`onReplace`는 `Promise<void>`이므로 컴포넌트가 보호하는 것은 자신의 UI 반영이며 callback 내부의 외부 cache 변경은 부모가 격리한다.
- 브라우저 메모리 외 원문·암호 저장은 없다. 비동기 완료가 다른 방·인증 epoch에 도달하면 UI 반영하지 않는다.

## 통합 수신 후 필요한 검증

공개 HTTP DTO/오류·Origin/CORS·Secure HttpOnly 쿠키, native OpenAPI export, 실제 PostgreSQL transaction·회원 충돌·동시 join/reaccess/replace/link, deadline/확정·타방·HOST 명령 탈취·기존 게스트 호환, 두 브라우저의 서버 연결 E2E를 추가한다. 현재 독립 unit/component PASS로 이 항목들을 대신하지 않는다. V12 예약과 공통 인증·proof·operation 인터페이스는 확인됐으나 #109 실제 공유 소스와 통합 순서가 준비되기 전 기존 migration/공통 파일은 바꾸지 않는다.
