# 계정별 모임 목록 로컬 검증 — 2026-10-28

기준 main: `84324bb11afd7b69316b151c580b90f3c8729b4d`. 작업 브랜치: `feature/account-room-history`. [확정 API 계약](../ACCOUNT_ROOM_HISTORY_API.md)의 로컬 구현·검증 기록이며 원격 게시·PR·병합·배포의 증거가 아니다.

## 변경과 경계

현재 계정의 HOST·MEMBER 모임을 기본 전체로 조회한다. 선택 역할, KST 모임 생성일 양끝 포함 날짜, 이름의 대소문자 무시 문자 그대로 부분 검색을 조합한다. 생성 시각 DESC·방 UUID ASC로 정렬하고 최대 100개의 keyset page와 본인 역할을 반환한다. 전체 개수와 별도 장기 아카이브는 추가하지 않는다.

계정 ID는 기존 CurrentAccountPort에서만 얻고 모든 조회에 소유권 조건을 재적용한다. 게스트 쿠키·같은 이름·공유 링크는 목록의 소유권 근거가 아니다. claim·JWT·Origin/CSRF·보존 스케줄러·DB 스키마·운영 AI 설정은 변경하지 않았다. 보관 기준은 기존대로 COLLECTING 생성 시각, CLOSED 마감 시각의 30일이며 정확한 30일 경계는 포함한다. 기간 필터만 생성 시각에 적용한다.

## 독립 테스트와 검토

- 새 HTTP/PostgreSQL 테스트 8개는 기존 구현에서 모두 요구사항 단언으로 실패했다. 기존 소유권 17개는 통과했고 초기 실행 오류와 유효 RED를 구분했다.
- 구현 후 새 8개·기존 소유권 17개·no-store 1개, 합계 26개가 통과했다. 실패·오류·skip은 0이다. 생성 OpenAPI의 파라미터·역할·페이지 필드·400 응답도 확인했다.
- 독립 검토의 미해결 P1/P2는 0이다. 계정 조건 우회, CLOSED 30일 경계 제외, 같은 생성 시각의 UUID 커서 방향 반전 결함을 각각 테스트가 탐지했다. 매회 정확한 원본 SHA로 복원한 뒤 8개를 다시 통과했다.
- 테스트 SHA는 RED 이후 변경하지 않았고 검토한 생산 코드 SHA도 결함 주입 복원 후 동일하다. RED·결함 주입 JSON은 저장소 증거 스키마 검사를 통과했다.

근거: [RED 요약](../../.tdd/red/account-room-history.json), [독립 검토](../../.tdd/reviews/account-room-history.json), [결함 주입](../../.tdd/verification/account-room-history-faults.json). 원본 로그는 Git 제외 로컬 `.codex/tdd-evidence/account-room-history*.log`와 `/tmp/account-history-*.log`에 보존했다.

## 전체 품질 검사

JDK 17.0.21, Gradle 9.7.1, 실제 PostgreSQL 18 Testcontainers로 `ktlintCheck assemble test`가 exit 0으로 완료됐다. 264 suites / 2,244 tests 중 **2,240 PASS, 기존 4 SKIP, 실패·오류 0**이다. 실행 시간은 11분 53초다. 건너뛴 기존 조건부 검사는 Issue83OpenApiExportIntegrationTest, Issue92OpenApiExportIntegrationTest, CorrectionRoundRequestPresencePostgresTest의 OpenAPI 캡처 1건, CandidateScenarioLiveProbeTest 각 1건이다. 실제 모델 probe를 새로 실행하지 않았다.

실행 명령은 `MEETME_ISSUE95_OPENAPI_EXPORT=/tmp/account-room-history-openapi.json sh /tmp/account-history-gradle.sh ktlintCheck assemble test`다. 임시 wrapper는 표준 Gradle에 로컬 JDK·쓰기 가능한 임시 캐시·이 실행 환경의 기존 프록시와 시스템 truststore를 지정한다. 저장소 빌드 설정·의존성·인증 설정은 변경하지 않았다. 전체 원본 로그는 `.codex/tdd-evidence/account-room-history-full.log`, XML 사본은 `/tmp/account-history-full-results`에 보존했다.

기존 native OpenAPI exporter가 생성한 실제 전체 JSON을 `/workspace/shared/account-room-history-openapi.json`에 전달한다. SHA-256은 `a491cf66707c1009e82ebe521239a0fe2c87a7c872ecdda3f117f76b781df60a`다. 선택 query 6개의 이름·타입·기본값·역할 enum, 날짜 형식, 1~100 limit, nullable next_cursor, 200/400/401/503 응답과 DTO 필드를 추가 확인했다. 전체 검사 후에도 독립 검토 source SHA와 RED test SHA가 동일하다.

최종 `git diff --check`와 기준 main에 대한 전달 패치의 `git apply --check`도 통과했다. 운영 키·실제 OAuth 공급자·실모델·브라우저 성공은 이 검증으로 주장하지 않는다. 코드 커밋·원격 게시·PR·병합·운영 배포는 수행하지 않았으며 구현·로컬 검증에 남은 차단 사항은 없다.

위 문단은 최초 로컬 검증 완료 시점의 기록이다. 이후 2026-10-28 14:11 UTC 사용자가 커밋·원격 게시·PR/CI·develop/main 병합·운영 배포를 승인했다. 추적 이슈는 #151이며 최신 진행은 `implementation_plan.md`와 Git/PR 이력을 따른다. main 승격은 진행 중인 AI 운영전환 완료를 부모에게 확인한 뒤 진행한다. 동일 native JSON을 `docs/openapi/account-room-history.openapi.json`에 보존했으며 새 migration·배포 guard 변경은 없다.

커밋 준비에서 원본 `.codex/hooks/tdd_guard.py`에 실제 PreToolUse/Bash/git commit 이벤트를 입력해 exit0을 확인했다. guard 자체·빌드 파일·보호규칙은 수정하지 않았으며 동일 source/test의 기존 fresh 전체 결과 재사용과 새 fresh 전체 재실행 주장을 구분한다.
