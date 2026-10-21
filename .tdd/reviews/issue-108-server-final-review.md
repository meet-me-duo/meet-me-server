# #108 서버 독립 최종 리뷰

- 검토일: 2026-10-17 UTC
- 검토자: `/root/independent_review` (구현·독립 테스트 설계와 분리)
- 기준: #109 서버 `c06b60a9833ae77e5fce9cfef5719ab2f37470ff` 및 인계된 #113 마감 패치에 대한 현재 #108 통합 변경
- 판정: **현재 #108/#109 및 인계된 #113 변경 범위 승인. 미해결 P1/P2 없음.** #107/#110 최종 결합, 정확한 원격 HEAD의 CI, 병합·배포·운영 승인으로 확장하지 않는다.

## 코드와 경계

실제 HTTP controller·DTO, application service와 port, ownership proof adapter, JDBC 저장소, V12, 기존 RoomLifecycle/Submission 연결 및 native fixture를 검토했다. `domain ← application ← adapter` 의존 방향과 Domain의 프레임워크 독립성이 유지된다. 공개 JSON DTO에는 내부 `blindReplacement` 권한 플래그가 없고 전용 서비스만 해당 값을 구성한다. 필수 `GuestSubmissionWriteGuard`가 실제 저장 경로에 연결되어 있다.

암호는 최초 참여와 같은 transaction에서 PBKDF2-SHA256 600,000회·난수 salt 16바이트·파생 키 32바이트로 저장한다. 기존 재참여가 이름·암호를 바꾸지 않는다. 독립 Python fixture, 정규 무패딩 Base64url, 잘못된 UTF-16·길이·변조 hash 거부, constant-time digest 비교, 비밀 DTO의 redacted 문자열을 확인했다. 시도 제한은 IP와 room/participant의 분산 키를 사용하며 Redis 장애에서 거부한다. 오류 응답에 암호·credential·hash·내부 원인을 옮기지 않는다.

원래 소유 세션은 유지되고 재접근은 별도 유효 세션에 결합한 PostgreSQL grant를 사용한다. grant는 고정 세션 만료와 회수를 검사하며, nullable owner를 null 세션과 같다고 인증하지 않는다. 회원 claim 이후 target의 guest 소유 조건이 사라지면 이전 grant도 거부한다. 올바른 암호는 기존 제출 읽기·수정 증명만 제공하며 HOST 명령을 승계하지 않는다. claim은 로그인·VERIFIED 증명·명시 요청을 요구하고 기존 회원 기록 충돌을 덮어쓰지 않는다.

REPLACE_ONLY는 공개 방 조회에서 미참여 상태를 유지하고 원문 GET·일반 PUT·claim을 허용하지 않는다. 별도 blind PUT은 빈 입력에서 시작하여 성공 여부만 반환한다. room 잠금 안에서 증명·수집 상태·deadline·저장 revision을 검사하고 같은 snapshot의 두 대체 요청 중 하나만 저장한다. 원문과 같은 blind 입력도 새 버전을 만들어 snapshot을 소비하므로 원문 일치 여부를 반복 요청으로 추론할 수 없다. claim과 대체의 경합은 인계 문서에 명시된 선형화 규칙을 따른다.

V12의 room/participant 복합 FK, session FK, grant level·revision·expiry 제약을 확인했다. V1~V11 SQL은 HEAD와 직접 비교하여 바이트 변경이 없다. legacy upgrade는 기존 데이터와 권한을 보존하며 hash/grant를 임의로 backfill하지 않는다. 새 참여도 room 잠금 이후 deadline을 검사한다. 닫힌 방의 암호 재접근은 기존 읽기만 복구하고, 보호된 기존 수정 라운드의 round/revision 검사는 유지된다.

앞선 리뷰에서 찾은 deadline 신규 참여, blind 동일 원문 no-op, DTO UTF-16 이름 제한 문제는 수정되었으며 독립 HTTP 테스트로 검증됐다. 이름은 DTO `@Size`의 UTF-16 제한을 제거하고 기존 도메인의 trim 후 1~50 코드포인트 규칙에 위임한다.

## 최종 증거 대조

- `/workspace/shared/issue-108/final-source-manifest.json`의 서버 383개 파일을 현재 바이트와 대조해 모두 일치했다. 각 정렬된 `path + NUL + sha256 + LF`를 연결한 SHA-256도 `50fa9515b3bb064392b10ef03e9d667886904cb989e3ebf36c619d89cc08c6cb`로 일치했다.
- 원본 `.codex/hooks/tdd_guard.py` SHA-256은 `07c4f974ec7d09b6ff3ca4e3d9e9dfd365bfccb6c10420ff6dbc0b5314473b79`이며 HEAD 대비 변경이 없다. `ktlintCheck`, `assemble`, 전체 `test`를 수행하는 guard의 최종 결과는 **exit 0, 975.703초**다.
- 최종 guard 요약과 실제 새 `build/test-results/test/` XML을 각각 대조했다. **138 suites, 898 tests, 실패 0, 오류 0, 기존 skip 4**이며 [.tdd/reviews/issue-108-final-quality-gate.json](issue-108-final-quality-gate.json)의 XML 138개 SHA-256도 모두 일치했다. skip은 기존 schema export/debug 및 live provider probe 항목이다.
- 원본 guard는 성공한 child 출력을 의도적으로 출력하지 않는다. 최종 `issue-108-final-quality-gate.log`는 **0바이트**이며 raw Gradle 성공 출력으로 주장하지 않는다. 앞선 포맷 실패는 [별도 기록](issue-108-final-gate-format-failure.json)의 실패이고 그때 남아 있던 이전 74개 XML을 최종 실행 결과로 합산하지 않았다.
- 기존 독립 47개, 실제 HTTP 37개, Unicode 이름 3개, deadline 37개와 scoped/migration supersession의 모든 활성 RED 테스트 지문이 현재 파일과 일치한다. 최종 전체 실행에서 해당 suite들도 전부 GREEN이다. Unicode RED는 3개 중 U01/U03의 기대 201·실제 400 실패 2개와 통과 1개였으며 문법·환경 오류가 아니다.
- scoped·V12·릴리스 catalog supersession의 보관 원본 및 이전 증거 지문을 확인했다. #113 fixture 변경은 account HOST cookie와 EXPIRED/REVOKED의 별도 guest actor 설정에 한정되며 기존 업무 assertion은 유지된다.
- [HTTP 결함 주입](../verification/issue-108-http-integration-faults.json)은 **동시 변이 5종의 단일 74개 실행**이다. 지정 H10/H25/H37/H36/정확한 deadline-FIRST assertion이 모두 실제 실패했고 전체 25실패·오류 0이었다. 복원 후 74개 GREEN이며 현재 source 4개·test 2개 지문은 복원 증거와 일치한다. 이를 독립 실행 5회로 세지 않는다.
- [릴리스 catalog 결함 주입](../verification/issue-108-release-catalog-faults.json)은 별도 1회로 V12 누락을 탐지하고 복원 후 14개 GREEN이다. 현재 publisher·고정 테스트 지문도 일치한다. 오프라인 pipeline 전체 로그는 **64개 통과, 실패·skip 0**이며 관련 30개는 리뷰자가 직접 재실행해 통과했다. 기존 V10/V11 소비, V1~V12 신규 발행, checksum·fingerprint·명시 승인 전환·실패 거부 조건이 유지된다.
- native fixture는 test source·`@TestConfiguration`과 고정 loopback 설정에 한정된다. 키는 저장소 밖 임시 경로에서 생성되며 API mock 없이 실제 쿠키·RS256·PostgreSQL·Redis를 사용한다. 최종 immutable runtime manifest `b72e40fada1b6906e2a9dd39f07e2c99031c23119dedf2baea0d021c31f38b64`와 1,051개 파일 지문을 직접 대조했다. 최종 native #108 16개·#109 회귀 12개 PASS, 브라우저 외부 요청 0 증거를 확인했다. 이후 DTO Schema 인자 줄바꿈은 annotation 값 불변이며 현재·native `JoinRoomRequest`의 `javap -c -p` 출력도 동일하다. native 결과를 최종 guard 실행 결과와 혼합하지 않는다.

## 통합 인계와 승인 범위

현재 #108 기능의 서버·웹 독립 리뷰는 각각 완료했다. 웹 승인은 별도 `docs/verification/issue-108-web-final-review.md`에 기록되어 있다. #107+#109 확정 조합과 #110을 합친 결과는 아직 검증하지 않았다. 공유 DTO·proof·private cache prefix·V12/V13 이후 catalog의 구체 인계는 [통합 인계 문서](../../docs/ISSUE_108_107_INTEGRATION_HANDOFF.md)에 남아 있다. 각 담당 테스트 수를 더하여 최종 결합 통과로 표시하면 안 되며 합친 정확한 HEAD에서 새 계약·경합·전체 게이트와 CI를 확인해야 한다.

검토자는 프로덕션 코드·테스트·기존 증거를 수정하거나 결함을 주입하지 않았다. 이번 최종 단계에서 작성한 파일은 이 서버 독립 리뷰 기록뿐이다. 원격 push·PR·병합·배포·운영 호출을 수행하지 않았다.
