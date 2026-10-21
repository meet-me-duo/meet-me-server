# Issue #108 독립 최종 리뷰

- 검토일: 2026-10-17 UTC
- 검토자: `/root/independent_review` (구현·테스트 설계와 분리)
- 기준 revision: `3232128bb1d0886c987e378a787633bd2aee6341`
- 판정: 현재 독립 암호·재접근 모듈과 웹 props 컴포넌트 범위에서 P1/P2 결함을 발견하지 못했다. 공개 HTTP·DB·기존 화면 통합 완료 판정은 보류한다.

## 범위와 확인 결과

`GuestPasswordHasher`, `GuestReaccessAttemptGuard`, 두 output port, `GuestReaccessPolicy`, `GuestReaccess` 모델 및 관련 독립 테스트 5개를 읽었다. 서버의 domain ← application ← adapter 의존 방향과 Domain의 프레임워크 독립성이 유지된다.

암호는 PBKDF2-HMAC-SHA256 600,000회, 난수 salt 16바이트, 파생 키 32바이트이며 고정 cost·길이·정규 Base64url 무패딩 encoding만 수용한다. malformed UTF-16, 빈 암호, 과도한 길이, 변조·비정규 해시를 거부하고 앞뒤 공백 및 Unicode 정규화 형태를 보존한다. `MessageDigest.isEqual` 비교와 일시 문자·파생 키 배열 초기화를 확인했다. 두 독립 fixture를 Python `hashlib.pbkdf2_hmac`로 다시 계산하여 테스트 기대값과의 일치를 확인했다. 정책 입력의 `toString`은 암호·해시·세션을 노출하지 않는다.

정책은 방·참여자 일치를 먼저 검사하고 nullable `ownerSessionId`를 세션 비교 전에 거부한다. null == null에 의한 OWNER 부여가 없다. 기존 유효 소유 세션의 권한은 유지하고, 다른 기기에는 암호 증명 시 VERIFIED_GUEST, 무암호 기록에는 REPLACE_ONLY만 반환한다. 회원 연결 기록을 과거 게스트 암호나 무암호 대체 경로로 탈취할 수 없으며, 소유 세션이 누락된 snapshot은 32개 입력 조합 모두 거부한다.

시도 제한은 IP별 30회와 room/participant별 5회를 각각 15분 창으로 검사한다. IP 교체로 대상 제한 키가 바뀌지 않으며 SHA-256 키에 원 식별자를 포함하지 않는다. 한도 초과 및 limiter RuntimeException은 거부하고 내부 예외 원인이나 식별자를 외부 예외에 옮기지 않는다.

웹 `GuestParticipation`과 고정 테스트 20개도 독립 검토했다. 중복 이름을 participant ID로 구분하고, 암호 선택 안내·비밀 입력 초기화·일반 오류·대체 입력 분리·마감 수정 차단·방/인증 identity 전환 시 지연 응답 무효화를 확인했다. 직접 재실행한 컴포넌트 테스트 20개가 통과했다.

## 증거 대조

- `.tdd/red/issue-108-core.json`의 35개, encoding의 7개 RED XML은 모두 해당 TODO 본문에 도달했다. 계정 경계 추가 RED XML은 5개 중 기대 행동 미구현에 의한 4개 실패·1개 통과였다.
- 기존 frozen 테스트 지문과 새 account boundary 지문을 확인했다. 현재 source 6개와 test 5개는 `.tdd/verification/issue-108-core.json`의 SHA-256에 모두 일치한다.
- `.codex/tdd-evidence/issue-108-final-focused.log`는 BUILD SUCCESSFUL이며, 보관된 `issue-108-final-focused-xml/` 합계는 **47개 통과, 실패 0, 오류 0, skip 0**이다.
- 서버 grouped fault XML에서 평문 저장 결함은 hasher 테스트 8개, REPLACE_ONLY의 읽기 권한 승격은 policy 테스트 2개, 제한 결과 무시는 guard 테스트 2개의 실제 assertion 실패로 탐지됐다. 서로 다른 테스트 class로 경계를 구분하며 HTTP·DB·분산 경합 검증으로 해석하지 않는다. 복원된 source/test 지문도 verification JSON과 일치한다.
- 웹 fault 로그 6개에서 암호 누락·암호 잔존·오류 상세 노출·대체 권한 승격·마감 우회·stale 재접근 수용을 각각 실제 assertion 실패로 탐지했음을 확인했다.
- 전체 `ktlintCheck`, `assemble`, 전체 `test`를 실행하는 변경 없는 원본 guard의 exit code **0**을 `.codex/tdd-evidence/issue-108-quality-summary.json`에서 확인했다(502.888초). guard SHA-256은 `07c4f974ec7d09b6ff3ca4e3d9e9dfd365bfccb6c10420ff6dbc0b5314473b79`로 요약 및 현재 원본과 일치하고 해당 파일의 Git diff도 없다. 실제 `build/test-results/test/` XML을 별도로 합산한 결과도 **116 suites, 710 tests, 실패 0, 오류 0, skip 4**로 요약과 일치한다. source 6개·test 5개 지문도 다시 대조하여 모두 일치했다. 현재 독립 변경 범위의 서버 품질 게이트는 PASS다.

## 통합 한계와 남은 검증

공통 `GuestOwnershipProofPort`, `GuestProofLevel`, `OwnershipOperation`, `ParticipantOwnershipResolver`의 정확한 계약은 수신하여 `docs/ISSUE_108_INTEGRATION_CONTRACT.md`에서 확인했다. #109 실제 공유 소스와 wiring은 아직 통합되지 않았다. 이 모듈은 HTTP·DB·계정 연결·실제 화면에 연결되지 않았고 V12는 예약만 된 상태다. 따라서 #108 전체 완료 또는 출시 가능 판정이 아니다.

`activeSessionId`에는 서버가 만료·회수를 검증한 세션만 전달해야 한다. `roomKey`에는 인증 viewer epoch가 포함되어야 하며 같은 표시 이름의 계정 전환에서도 달라져야 한다. clientAddress는 신뢰할 수 있는 서버 경계에서 정규화하여 전달해야 한다. 실제 credential·쿠키·캐시 전환, Redis 경합과 장애, 원문 비노출 HTTP 응답, 원자적 revision precondition·회원 충돌·연결 동의, HOST 권한 비승계, deadline·확정 상태의 쓰기 재검사는 통합 후 검증해야 한다.

검토자는 프로덕션 코드·테스트·기존 증거를 수정하거나 결함을 주입하지 않았다. 이번 검토에서 작성한 파일은 이 리뷰 기록뿐이다.
