# 확정 결과 리뷰 전용 운영 반영 준비 · 2026-10-28

사용자는 리뷰 디자인, 리뷰 서버와 필요한 DB 스키마의 PR·CI·병합·운영 반영을 승인했다. AI 운영 활성화와 직접 AWS 인증은 보류한다. 이 문서는 기존 이관·로컬 검증 기록을 덮어쓰지 않고 최신 릴리스 단계만 기록한다.

## 변경과 데이터 영향

기준은 `main` bbf3a8364f1c971a292034ebf4038b1dbe2b3b80이며 `develop` 03bbdc1909d22c0bad4667eedea6e2a0c2ffef87과 같은 파일 트리다. 원본 checkpoint f196e6b8655fd17c097dc7db0549c7f3e1fcf3d2와 개인 PC 자료를 보존했다. 체크포인트 ZIP·복구 도구·개발 서버 helper·원본 로그는 이 릴리스 변경에서 제외한다.

V19 SQL의 SHA-256은 `b5e55df55f97f754239653252862e6631c570f1a724b05b063d8320826d5109f`다. `review_user_policies`와 `review_prompts`, choices 검증 함수와 검증된 소유권 claim 연계 함수·trigger를 추가한다. 기존 사용자 데이터의 삭제·backfill은 없다. V1–V18 SQL은 기준 커밋 바이트와 일치한다. 공개 조회 API로 선택·자유 입력을 노출하지 않는다.

기존 AI runtime 정책·provider/auth mode·production compose 설정은 바꾸지 않는다. `review_prompt_supported`는 optional true 힌트이며 권한이나 eligibility를 부여하지 않는다. 웹은 구 서버에서 필드가 없으면 리뷰 컴포넌트를 마운트하지 않는다.

## 배포와 복구 경계

정확한 V19 카탈로그를 기존 폐쇄 목록에 추가하고 이전 카탈로그의 읽기, 이름·체크섬·지문·성공 이력·승인 조건을 유지한다. 새 스키마와 guard 지문은 기존 자동 배포 계약과 달라 정상적으로 `TRANSITION_REQUIRED`를 요구한다. 이를 `AUTO_READY`로 꾸미지 않는다.

최종 main push CI가 성공하면 기존 Deploy Production이 OIDC로 immutable image·archive·manifest를 생성한다. 정확한 build run/attempt와 SQL·guard 지문을 확인한 후 기존 승인 경로의 preflight를 먼저 읽는다. 명시적 전환은 두 refresh rule 모두 DISABLED, pending command 0, 신뢰된 성공 migration 이력과 runtime 상태가 맞아야 한다. 이 조건이 맞지 않거나 지원되는 dispatch 인증이 없으면 운영 실행을 중단하고 정확한 차단만 보고한다. 신규 권한·비밀 설정이나 직접 AWS 인증, 새 배포 트리거로 우회하지 않는다.

실패 시 Flyway downgrade나 사용자 리뷰 데이터 삭제를 하지 않는다. 적용 후 V18까지만 아는 과거 artifact를 다시 배포하는 것은 현재 migration 이력과 지문 검증을 통과하지 못한다. 이전 기능으로 복귀해야 하면 V19와 현재 guard를 보존한 호환 빌드를 새 릴리스로 준비해야 한다. 공식 guard는 전환 중 실패 상태를 유지하며 검증되지 않은 이전 writer를 자동 기동하지 않는다. 복구는 관측된 phase와 immutable artifact를 기준으로 승인된 resume/전진 배포 경로만 사용한다.

## 최신 검증 상태

별도 clean worktree의 변경하지 않은 mandatory guard는 2026-10-28 05:15 UTC에 exit0으로 통과했다. ktlintCheck·assemble·test의 fresh XML은 257 suites/2,206 tests 중 2,202 PASS·기존 opt-in 4 SKIP·실패/오류0이다. 최신 힌트 이후 결과로, 앞선 전체 2,200 PASS와 구분한다. 리뷰 대상 43 PASS·native 구 응답 4 PASS/새 지원 12 PASS도 별도 근거로 보존한다. V19 카탈로그는 독립 유효 RED 6 FAIL/2 PASS 후 신규 8 PASS, 기존 CI catalog·정책 합본 97 PASS를 확인했다. 독립 FI 4개 모두 실제 명시적 assertion으로 탐지했고 생산 3개·독립 test/CI 6개·SQL 19개·runtime policy 1개의 29개 지문을 정확히 복원했다. 복원 후 신규 8개도 다시 PASS했다. 부모의 생산 diff 독립 읽기에서도 기존 catalog·검사 로직·권한 변경 없이 정확한 V19 한 개만 확장했음을 확인했다. 원격 CI·운영 반영은 아직 완료로 표시하지 않는다.

배포 전 2026-10-28 05:05 UTC 운영 읽기에서는 healthz와 native OpenAPI가 200이고 리뷰 경로·지원 힌트는 없었다. 이 결과는 기존 서비스 상태이며 새 기능 배포 증거가 아니다. 실제 계정 로그인·리뷰 제출·실모델 호출은 수행하지 않았다.
