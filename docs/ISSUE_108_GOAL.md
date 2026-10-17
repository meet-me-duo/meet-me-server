# Issue #108 — 이름·선택 암호와 재접근

원본: https://github.com/meet-me-duo/meet-me-server/issues/108

사용자 목표일: 2026-10-18 KST. 2026-10-17 명시 재개와 제품 결정이 이슈 본문의 과거 Human Blocker를 대체한다. 내장 goal 도구가 없어 이 문서를 목표 기준으로 사용한다. 초기 독립 단계에서는 원격 작업을 수행하지 않았다. 이후 사용자가 이 이슈의 commit·feature push·Draft PR·정확 HEAD CI 확인을 명시 위임했다. 병합·배포·운영 호출·실제 credential 변경은 여전히 별도 승인 전 수행하지 않는다.

## 완료 조건

- [x] 비회원 이름만/이름+선택 암호 참여, 중복 이름을 서로 다른 participant ID로 구분
- [x] 암호 없는 참여의 수정 미보호를 선택 전에 안내
- [x] 암호가 있는 기록은 다른 기기에서 암호 검증 후 재접근, 암호 초기화·HOST 우회 복구 없음
- [x] 암호 없는 기록은 다른 기기에서 대체 입력만 허용하고 기존 원문 조회·노출 금지
- [x] 같은 기기의 유효 소유 세션 접근 범위 유지
- [x] 회원은 이름·암호 입력 면제, 로그인+유효 소유 증명+명시 동의만 guest→member 연결
- [x] 동일 방 기존 회원 기록 충돌은 덮어쓰기 없이 명시 충돌
- [x] 안전한 암호 해시, 분산 시도 제한, 일반화된 실패, 경합·탈취 방어 검증
- [x] 마감·확정·HOST 전용 명령·원문 비공개 회귀 검증
- [x] 독립 테스트 설계 → 유효 RED → GREEN → 의도 결함 주입 → 전체 게이트 → 독립 최종 리뷰
- [x] 서버/웹 격리 통합과 OpenAPI 생성 계약 일치

## 파일 소유 경계

이 세션은 새 GuestPassword* 및 GuestReaccess* 파일, 전용 테스트·증거, 웹의 독립 GuestParticipation* 컴포넌트/API 모듈을 우선 소유한다. #109가 공통 계정·Participant·세션 모델, 인증 해석, 회원 연결과 인증 웹 진입점을 소유한다. 공통 계약 전달 전 공통 파일을 수정하지 않는다. SubmissionService.kt의 마감 수정은 #113 원격 정확 SHA에서 보존적으로 가져오며 검사 순서를 유지한다. #108은 소유권 가드와 blind 동일 원문 처리에 필요한 작은 hunk만 추가한다. #110 계측 파일은 수정하지 않는다. Flyway 번호는 #109와 합의 후 사용한다.

## 실제 통합 재개 (2026-10-17)

#109 서버 `c06b60a9833ae77e5fce9cfef5719ab2f37470ff`와 웹 `95f09c0a98c6538b0f19ed381fdf11a2f5b844d9`, #113 마감 수정 `da225d8aaa9097db2360a6bf0579322de16b5968`을 원격에서 직접 확인했다. 원본 독립 작업 트리는 그대로 보존하고 각 별도 `feature/issue-108-integration` 작업 트리에서 실제 공통 port·인증·claim과 연결한다. 현재 상태와 실행 증거는 [실제 통합 기록](ISSUE_108_INTEGRATION.md)에 둔다. 아래 수치는 독립 단계의 이력이며 통합 결과로 재사용하지 않는다.

## 독립 단계의 기준 revision과 당시 상태

- 서버: 최신 develop `3232128bb1d0886c987e378a787633bd2aee6341`, `feature/issue-108-guest-reaccess`
- 웹: 원격 develop 없음, 저장소 docs/BRANCHING.md에 따라 최신 main `9b183978d5987f8fa8a5f02d542b17181e4929b4`, 같은 이름의 별도 feature 브랜치
- #109 공통 계정/claim/proof/operation/파일 소유 계약 수신. V11은 #109, V12는 #108 예약. GuestOwnershipProofPort와 ParticipantOwnershipResolver의 실제 skeleton 공유 소스 및 런타임 통합은 대기다. [연결 계약](ISSUE_108_INTEGRATION_CONTRACT.md)을 따른다.
- 기존 계정·운영 설정 변경 없음. 전체 기능 완료나 게이트 통과를 아직 주장하지 않는다.

## 암호 구현 선택

JDK 표준 PBKDF2-HMAC-SHA256, 600,000회, 암호별 난수 salt 16바이트와 파생 키 32바이트를 versioned hash로 보관한다. 추가 외부 라이브러리 없이 현재 JDK에서 구현·검증할 수 있고, CPU 비용은 시도 제한으로 방어한다. 메모리 경화 Argon2id보다 오프라인 공격 저항성이 낮다는 비용이 있다. 선택 암호 null과 빈 암호는 구분하며 비어 있지 않은 1~128 Unicode 코드포인트, 앞뒤 공백을 보존한다. 비교는 constant-time digest comparison, malformed/과도 cost encoded hash는 fail closed한다.

근거: [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) (2026-10-17 확인). 이 선택은 회원 로그인 암호를 도입하지 않는다.

## 독립 구현 결과와 통합 잔여

- [x] 독립 테스트 저자가 작성한 서버 47개와 웹 20개 계약 테스트의 RED/GREEN 확인. 기존 frozen 테스트 기대값 변경 없음.
- [x] 원래 소유 세션/다른 방/다른 participant/선택 암호/무암호 대체 전용/회원 연결/nullable 계정 소유 snapshot 판정 구현
- [x] PBKDF2 독립 fixture·Unicode malformed·정규 Base64url·시도 제한·저장소 오류 비노출 검증
- [x] 웹 신규 참여·선택 암호·회원 입력 면제·중복 이름 기록 선택·대체 입력·stale 응답 격리 컴포넌트 구현
- [x] 웹 결함 6종/12 assertions 탐지 및 exact 복원. 서버는 독립 3개 경계를 한 grouped run으로 주입해 12 assertions 탐지 및 exact 복원.
- [x] 변경 없는 원본 tdd_guard에서 ktlintCheck·assemble·test PASS. 전체116 suite/710 tests, 실패0/오류0/기존skip4. 웹 전체단위328 PASS, 기존mock browser383 PASS/기존skip1/실패0/flaky0/retries0, api:check/lint/typecheck/build PASS.
- [ ] #109 resolver와 실제 소유 증명 scope 연결, V11+V12 통합, 공개 HTTP/기존 화면·회원 claim 연결
- [ ] 실제 PostgreSQL/Redis·두 브라우저·마감/확정·회원 충돌·탈취·경합 통합, 통합 native OpenAPI export

위 독립 구현과 기존 회귀 검증은 #108 전체 완료나 운영 반영을 뜻하지 않는다. 공통 파일을 여러 세션에서 변경하지 않기 위해 PRD·Architecture·implementation_plan의 통합 갱신은 부모/#109가 단일 편집자로 수행하며, 이 세션의 원본 목표·결정·검증·인계는 #108 전용 문서에 남긴다.
