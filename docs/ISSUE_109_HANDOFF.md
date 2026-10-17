# Issue #109 로컬 구현 인계

상태: 서버·웹 구현과 로컬 전체 게이트 통과. 최종 독립 리뷰와 원격 CI 기록은 인계 receipt 및 PR을 기준으로 한다. 실제 공급자 로그인과 운영 활성화는 미검증이다.

## 범위와 적용 기준

- 서버: `develop` `3232128bb1d0886c987e378a787633bd2aee6341`에서 분리한 local `feature/issue-109-account-archive`.
- 웹: 원격 develop이 없고 저장소 지침이 main 기준이므로 `main` `9b183978d5987f8fa8a5f02d542b17181e4929b4`에서 같은 local feature 이름으로 분리했다. 양쪽 모두 착수 시 clean 상태였으며 기존 미커밋 작업을 가져오지 않았다.
- Google/Kakao code+PKCE/OIDC, RS256 Access15분, Redis 회전 Refresh(idle14일/absolute30일), 기기별·전체 로그아웃, 영구 계정 식별, 계정 HOST 생성, 회원·게스트 참여, 명시적 게스트 연결, 본인 HOST 아카이브와30일 정리 예정 시각을 구현했다.
- 이메일·이름·초대 링크로 자동 연결하지 않는다. 같은 방에 기존 계정 참여가 있으면 `409 PARTICIPANT_CONFLICT`이며 기존 ID·제출·역할을 병합하거나 덮어쓰지 않는다.
- #110 관측 파일과 별도 마감 수정은 제외했다. `SubmissionService.kt`는 소유권 import/생성자/save의 REPLACE_OWN/get의 READ_OWN/소유권 helper만 변경했다. 인계의 별도 patch로 적용하고 수신 측 마감 검사를 보존한다.
- V11 전달에 필요한 release catalog 검증 파일3개를 추가로 수정했다. publisher는 V1~V11 정확 목록만 만들며 과거 V10 immutable manifest/record 검증을 보존한다. V10→V11은 fingerprint·전체 history가 달라 자동 승격되지 않으며 기존 명시 승인/규칙중지/명령drain 조건을 유지한다. 실제 게시·운영 전환은 실행하지 않는다.
- migration 예약: #109 V11, #108 V12, #107 V13, #110 V14. V1~V10 SQL은 변경하지 않았다. V11은 기존 게스트 기록을 자동 연결하지 않는다.

## 공통 계정·게스트 증명 계약

정확한 패키지·API·오류·파일 경계는 [CONTRACT](ISSUE_109_CONTRACT.md)를 따른다. `AccountPrincipal(accountId, displayName)`과 `CurrentAccountPort`가 계정 경계다. `GuestOwnershipProofPort`는 방·참여자에 결합된 `VERIFIED_GUEST` 또는 `REPLACE_ONLY`를 반환한다. 후자는 본인 제출 교체만 허용하며 조회·HOST 명령·계정 연결로 승격하지 않는다. 정상 작업은 계정 우선이고 잘못된 Bearer는 게스트로 fallback하지 않는다. 계정 연결만 검증된 계정과 강한 게스트 증명을 함께 검사한다.

기본 cookie proof adapter와 대체 가능한 bean을 제공한다. #108의 실제 비밀번호/request-proof adapter는 원래 #108 세션에서 통합한다. 수신 자료 Library의 최초 및 허용된 새 전송 참조 다운로드가 모두HTTP403으로 실패하여 #108 archive 내용·해시는 이 환경에서 검증하지 못했다. 임의 경로나 URL로 우회하지 않았으며 #108 구현 전체를 가져오지 않았다. 따라서 공통 port·대역 계약 검증을 실제 #108 adapter 결합 검증으로 표시하지 않는다.

## 검증과 증거

- 독립 구현자·테스트 설계자·최종 검토자를 분리했다. frozen RED와 GREEN, 보안·경합·실제 DB, 결함 주입 및 정확 복구는 `.tdd/red`, `.tdd/verification`, `.tdd/reviews`에 기록한다.
- 인증 결함 주입 최초 집계의 stale XML 오류와 웹 ClaimConflict 최초 typed fixture 오류는 INVALIDATED 이력으로 보존했다. 수정된 새 실행과 구현 후 회귀 검증을 사전 TDD로 표시하지 않는다.
- 서버 전체 첫 실행의6개 실패는 새 계정 package·권한 fixture·V11 개수 기대값이었다. 기존 경계·데이터 보존 assertions를 유지하여 수정했고 집중28개/6classes 통과했다.
- 최신 웹 단위364개 통과. 서버 전체 gate773개(실행769통과/기존disabled4), failure/error0와 변경 없는 guard가 통과했다. guard self-test12개도 통과했다. 최종 웹384 scheduled browser는383통과/기존skip1/실패·재시도0이다. V11 전달 catalog 새9+기존7=16통과, 독립fault3종/4assertions 탐지와정확복구도통과했다. 기존 CI 부가 검사도 모두 통과했다. 세부 명령·환경 재실행·소스 지문은 `.tdd/reviews/issue-109-ci-extras.json`을 따른다. 원래 V8 guard37개는 수정 없는 단독 실행에서 통과했으며 초기 동시 실행의15초 timeout은 이력으로 유지했다.
- 실제 Spring 서버+PostgreSQL18+Redis8+loopback HTTP OIDC/JWKS와 최신 웹의 native browser12개 통과. API route mock0, 외부 요청0, 실제 공급자 credential0이다. 두 공급자·생성·아카이브/만료·기기 간 동일 계정·타 계정 격리·게스트·명시 연결/충돌·회원 이름 생략·기기별/전체 로그아웃·다중 탭 갱신을 확인했다.
- 최신 서버 생성 OpenAPI23paths: 서버 `docs/openapi/issue-109.openapi.json`과 웹 snapshot 동일SHA256 `924b0683f5f48de3fe3fd3d1d130c6212a511912db31b284f9e697ba840fd19e`. 웹 타입은 실제 문서에서 재생성했다. `403` scoped proof 및 계정503 schema를 포함한다.
- 원본 로그/XML은 로컬 `.codex/tdd-evidence`와 `/tmp`에 보존하고, 인계 archive에는 비밀값·키·쿠키·토큰·원문 로그를 포함하지 않는다.

## 재현

서버는 Docker(PostgreSQL18/Redis8 Testcontainers), Java17과 Gradle wrapper를 사용한다. `sh ./gradlew --no-daemon ktlintCheck assemble test`가 원래 전체 gate다. 변경하지 않은 `.codex/hooks/tdd_guard.py`의 self-test와 gate도 검증한다. guard에 검증용 event를 입력하는 것은 실제 git commit을 실행하지 않는다.

웹은 lockfile 기준 `corepack pnpm install --frozen-lockfile` 후 `api:check`, `lint`, `typecheck`, `test`, `build`, `test:e2e`를 사용한다. 이 환경은 Playwright의 system Chromium을 명시했으며 retries0으로 검증한다. 실제 운영 API에 snapshot을 요청하지 않고 `OPENAPI_URL=http://localhost:8080/v3/api-docs corepack pnpm api:snapshot`으로 격리 서버 명세를 받는다.

Native 확인은 서버 테스트 runtime classpath에 있는 `com.meetme.server.account.adapter.input.web.AuthEndToEndPostgresTestKt` main을 실행하고 웹을 localhost5173에서 띄운 뒤 웹 `node scripts/verify-account-native.mjs`를 실행한다. harness는 독립 테스트용 일회성 PostgreSQL/Redis/공급자/서명키를 사용한다. 스크립트는 loopback 공급자 endpoint만 허용하며 실제 공급자나 운영 API를 대상으로 실행하지 않는다. 로컬 게스트 cookie 설정은 테스트 harness 전용이며 실제 Auth cookie Secure 속성을 축소하지 않는다.

## 남은 외부 단계와 승인 경계

[OAuth 설정 가이드](ISSUE_109_OAUTH_SETUP.md)에 정확한 local/production redirect URI, 최소 scope, 변수 이름과 안전한 입력 위치를 준비했다. 기존 Google meet-me 프로젝트/Kakao meet-me 앱을 사용한다. 부모를 통해 Google Secret 교체·보관/이전값폐기 및 Kakao callback 저장을 확인했지만 에이전트는 실제 값을 읽거나 출력하지 않았고 서버 입력은 아직이다.

실제 두 공급자 로그인·취소·재로그인, 운영 callback/Origin/Secure cookie/키 회전과 기기 검증은 사용자 관리 설정·안전한 런타임 secret 입력과 별도 승인 후 수행한다. #110의 callback query 로그 비노출 확인도 운영 활성화 전 수신 세션에서 담당한다. 사용자의 후속 명시 승인으로 최종 검증·독립 리뷰 통과 후 commit·feature push·Draft PR·정확 head CI 확인을 진행한다. merge/auto-merge/main배포/운영 DB/Secret 변경은 승인 범위 밖이며 수행하지 않는다. feature push/PR는 기존 workflow상 운영 배포를 유발하지 않음을 독립 확인했다. 원격 결과는 인계 receipt와 PR 기록을 기준으로 한다. 내장 goal API가 제공되지 않아 설치·숨은 설정 변경 없이 WORKLOG와 구현 계획으로 목표를 추적했다.

최종 gate 요약·독립 리뷰와 전달 archive receipt는 이 문서와 함께 제공한다.

## 승인된 웹 원격 인계

웹 commit `95f09c0a98c6538b0f19ed381fdf11a2f5b844d9`, [Draft PR25](https://github.com/meet-me-duo/meet-me-web/pull/25), [CI39045603250](https://github.com/meet-me-duo/meet-me-web/actions/runs/39045603250)이 success이며 CI unit364/E2E383통과·기존skip1을 확인했다. PR merge tree와 exact head tree가동일함을확인했다. 이후 서버 exacthead와 CI최종결과는 별도원격receipt/PR기록을기준으로한다.
