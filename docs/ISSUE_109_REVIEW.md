# Issue #109 독립 보안·아키텍처 리뷰

상태: **APPROVED_IMPLEMENTATION_SCOPE — 서버·웹·V11 publication 호환성 구현 범위 승인**. 검토자는 `/root/final_review`이며 인증·소유권 런타임 구현에서 분리되어 있다. 서버 테스트 작성은 별도 설계자가 담당한다. 부모의 추가 위임으로 웹 API privacy 테스트 13개와 UI 테스트 3개(닫힌 게스트 방 연결, 기존 익명 HOST 로그인 gate, 이미 참여한 계정의 명시 연결 충돌)는 이 검토자가 독립 작성했으므로 해당 test oracle은 별도 설계자 `identity_db_tests`의 교차 검토를 요청했다. 부모의 별도 지시로 OpenAPI annotation만 수정한 두 Controller는 아래에 명시한다. 실제 운영 OAuth 로그인·배포는 이 리뷰의 검증 대상이 아니다.

## 기준과 검토 경계

- 기준 revision: `3232128bb1d0886c987e378a787633bd2aee6341`.
- `AGENTS.md`, `.agents/rules`의 6개 규칙, `.agents/skills/project-architecture/SKILL.md`, PRD, Architecture, ADR, `ISSUE_109_CONTRACT.md`, `ISSUE_109_WORKLOG.md`를 기준으로 검토한다. 최신 사용자 결정이 과거 제출 MVP 익명 주최자 정책을 대체한다.
- 구현/독립 테스트 설계/RED/GREEN/결함 주입/전체 게이트와 별도로 소스 보안 경계를 확인한다. 아직 골격 상태인 구현을 최종 결함이나 합격으로 판정하지 않는다.
- `SubmissionService.kt`는 소유권 resolver와 참여자 판별의 변경만 허용하며, 마감 처리와 #110 관측 계층은 별도 세션 경계다.

## 최종 확인 목록

- [x] Google/Kakao code 교환의 고정 redirect URI·PKCE, 브라우저 결합 one-use state와 nonce, OIDC RS256/kid/서명/issuer/audience/azp/시간 검증.
- [x] 공급자/JWKS HTTP 장애와 거부 응답의 fail closed 및 토큰/코드/개인정보 비노출. 실제 공급자와 격리 대역 검증을 구별.
- [x] 서비스 Access RS256, 명시 kid와 이전 public key 회전, issuer/audience, 최대 900초, 정확한 만료와 미래 iat/nbf 거부. Redis 의존 없음.
- [x] Redis 실제 서버에서 원자 Refresh 회전·replay family 폐기·기기 격리·전체 로그아웃 선형화, idle 14일·absolute 30일과 사용된 digest 보존 TTL.
- [x] Refresh cookie scope·속성·Max-Age 및 정확한 Origin, HTTP 오류 상태와 응답 유출 방지. 무효 Authorization은 게스트 fallback 금지.
- [x] `(provider, subject)` 영구 계정 식별과 동시 가입, 이메일/이름 자동 연결 금지.
- [x] 방 생성과 모든 HOST 명령의 계정 인증, account 우선 소유권, 게스트 참여 유지·회원 이름 생략.
- [x] 명시 claim 이중 증명, 방 잠금과 DB XOR/unique, 동일 방 충돌 409, claim/join 경합, UUID/역할/제출 보존 및 이전 게스트 방 권한 회수.
- [x] 본인 HOST 아카이브의 IDOR 차단·최신순·정확한 30일 경계·closed_at 기준·기존 정리 정책 불변.
- [x] 실제 PostgreSQL migration/경합, 브라우저 통합, 생성 OpenAPI, 전체 서버/웹 게이트와 guard 증거.
- [x] 독립 frozen RED 지문과 최종 테스트 일치, 결함 주입 탐지 및 복구 후 GREEN; 아래 정정된 회귀 검증은 사전 RED로 집계하지 않음.

서버·웹의 실제 전체 게이트와 최종 지문 검사를 완료했다. 최초 typed fixture 오류와 구현 후 회귀 항목을 유효한 사전 RED와 구분했다. 이 구현 범위 승인은 실제 공급자·운영 활성화·실제 #108 adapter 결합 검증의 완료 판정이 아니다.

## 초기 전달 사항

- 인증 테스트 설계자에게 Access의 미래 `iat`/`nbf` 및 발급 수명 제한, OIDC 다중 audience의 `azp`, 사용된 Refresh digest의 absolute 만료까지 replay 탐지 검증을 전달했다. 테스트 설계자는 RED freeze 전에 관련 보강 중이다.
- 소유권 테스트 설계자에게 close 이외 HOST 명령의 계정 우선 검증, claim/join 경합 검증을 전달했다.
- guest HOST의 기록상 역할은 보존하되 명령 capability는 로그인된 계정 HOST에서만 활성화하도록 소유권 구현자와 합의했다. 게스트 자신의 제출 권한과 구분한다.
- 웹 리뷰에는 account 변경/로그아웃 뒤 지연 Refresh가 세션을 복원하는 경합, 이전 계정의 늦은 API 응답·query/mutation cache·원문 draft가 다음 계정에 노출되는 경계를 포함한다. 웹은 최신 원격 `main` 기준의 격리 로컬 feature 브랜치에서 진행하며, 이 저장소 서버의 `develop` 기준과 구분해 WORKLOG에 기록한다.
- 동시 신규 로그인과 전체 로그아웃은 선형화 순서에 따라 새 세션이 살아남을 수 있다. 신규 로그인 금지 정책을 추가하지 않으며, 먼저 완료된 family가 전체 로그아웃 뒤 인덱스 누락으로 살아남는 경우를 금지한다.
- 2026-10-17 공식 [Kakao discovery](https://kauth.kakao.com/.well-known/openid-configuration)에서 code flow·S256·RS256·issuer와 [Google discovery](https://accounts.google.com/.well-known/openid-configuration)를 조회했다. 이는 프로토콜 설정의 근거이며 실제 앱 설정이나 운영 로그인 성공의 근거가 아니다. Kakao의 [공식 REST 계약](https://developers.kakao.com/docs/en/kakaologin/rest-api)은 취소 시 `error`/`error_description` callback과 redirect URI 일치, nickname의 동의 조건을 별도로 명시한다.
- nginx access log는 `$uri`를 사용해 query를 제외한다. `error_log warn`의 upstream 장애 원본 request query 노출 가능성은 #110 경계로 부모에게 전달했으며, 운영 활성화 전 별도 검증이 필요하다.

최종 결과와 소스 지문은 `.tdd/reviews/issue-109-final-review.json`에 기록한다.

## 중간 증거 확인

- 인증 첫 RED는 컴파일 후 30개 중 29개가 예상한 `TDD TODO` 경계에서 실패했으며 원본 로그가 존재한다. 확인 당시 최초 5개 테스트 지문이 일치했다. 이후 formatter가 Access 테스트의 긴 문자열 표현을 바꾸므로 설계자가 해당 파일을 별도 RED 주기로 분리 중이다. 최종 active RED의 지문을 다시 확인해야 한다.
- Redis 장애의 범용 `assertFails` 테스트는 골격 `TODO`도 실패로 인정한 한계가 있다. 실제 장애 분류와 HTTP 503/no-leak 증거를 추가로 확인해야 한다.
- 독립 계정 영속 테스트는 provider+subject·표시 이름 Unicode 경계·실제 Spring transaction proxy·PostgreSQL insert fault rollback·12개 worker 최초 로그인 경합을 다룬다. 실제 Flyway 호환성은 별도 migration 테스트가 담당한다.
- 웹 API privacy 테스트 `src/api/client.account.test.ts` 12개를 구현 전에 작성하고 실행해 11개가 Bearer 누락·늦은 계정 응답 반환의 요구사항 실패임을 확인했다. AbortSignal 전달 1개는 기존 구현으로 통과했다. 별도 TypeScript 전체 typecheck도 통과했다. frozen RED/지문/설계는 웹 저장소 `.tdd/red/issue-109-web-api.json`, `.tdd/reviews/issue-109-web-api-test-design.json`에 기록했다.
- 웹 API 오류 JSON decode 중 계정 변경 1개를 별도 RED로 추가하고 독립 교차 검토를 받았다. 이후 Bearer 제거·모든 epoch 검사 제거·decode 뒤 epoch 검사 제거 3종에서 각각 7/5/2개 요구사항 실패를 확인하고 원본 바이트를 정확히 복구한 뒤 13/13 GREEN을 확인했다.
- 웹 shared cookie lock의 독립 4개 test oracle과 frozen 지문을 검토하고 `.tdd/reviews/issue-109-web-cookie-lock-cross-review.json`에 승인했다. 실제 여러 탭 브라우저 결과와 Web Locks 미지원 시 same-tab singleflight 한계는 별도로 확인한다.
- #108 공통 증명의 `VERIFIED_GUEST`/`REPLACE_ONLY`와 operation별 권한을 새 resolver에서 재검토 중이다. 계정 우선·proof room/participant binding·guest-owned row 재확인·claim의 강한 proof/잠금은 소스에서 확인했다. public room viewer와 기존 참여 반환 join도 proof 경계를 통과하도록 요청했고 별도 독립 RED를 진행한다. `SubmissionService.kt`는 resolver의 READ/REPLACE 구분 및 소유권 helper 변경만 유지하며 마감 로직은 바뀌지 않았다.
- 공통 proof 기본 40개는 독립 frozen RED→GREEN을 거쳤고, public viewer/join 4개는 구현 이후 추가한 독립 회귀 GREEN으로 정확히 구분한다. 원문 read/replace scope 혼동·약한 proof의 read/claim 승격·이미 계정 소유로 바뀐 row·claim lock 누락의 5개 결함은 실제 assertion failure를 확인하고 원본 SHA를 복구했다. 실제 #108 비밀번호/request-proof adapter는 이 격리 작업 트리에 없으므로, 해당 adapter와 합친 검증을 완료했다고 주장하지 않는다.

## 테스트 기록의 정정

이 검토자가 작성한 `ClaimConflictView.test.tsx`의 첫 fixture는 생성된 `Viewer.context_id` 대신 존재하지 않는 `participant_id`를 사용했다. Vitest가 TypeScript를 변환해 런타임 실패를 보여주었지만 전체 typecheck에서 fixture 오류가 확인되었으므로 첫 RED를 유효한 TDD로 인정하지 않는다. 원래 기록은 웹 `.tdd/history/issue-109-web-claim-conflict-invalid-typed-fixture/original-red.json`에 INVALIDATED 상태로 보존했다.

수정된 테스트는 공개 opaque `context_id` hint를 인증 증명으로 사용하지 않고 conflict 뒤 동일하게 보존되는지만 확인한다. 실제 접힌 disclosure를 연 뒤 사용자가 claim 버튼을 누르도록 보강했다. 수정 후 전체 TypeScript typecheck와 대상 테스트 GREEN을 확인했고 `.tdd/verification/issue-109-web-claim-conflict.json`에 **구현 이후 수정된 독립 회귀 GREEN**으로 기록했다. 이 정정에 프로덕션 RoomPage를 임시 변경하지 않았으며, 사전 TDD 실행으로 보고하지 않는다.

## OpenAPI 문서 보완 범위

부모의 명시적 지시에 따라 이 검토자가 `RoomLifecycleController.kt`의 GET room/POST participants 403 응답을 `ApiProblemSchema`로 문서화했다. `RoomOwnershipController.kt`의 기존 오류 schema가 이미 명시된 점을 확인·유지하고, claim 403 설명과 claim/archive의 인증 검증 키 사용 불가 503 schema를 보완했다. Controller 동작·인증·DB·보존 정책은 수정하지 않았다. 생성 OpenAPI의 재조회와 웹 schema 동기화는 최종 게이트에서 확인한다.

## 구현 중 발견사항

### R109-01 — 공급자 HTTP body 읽기 전체 제한 누락 (P1, 수정·최종 게이트 통과)

`OidcOAuthProviderAdapter.read`는 `HttpRequest.timeout`과 `BodyHandlers.ofInputStream()` 뒤 `readNBytes`를 사용했다. 이 timeout은 응답 헤더 이후의 blocking body 소비를 제한하지 못한다. 외부 공급자/JWKS 연결이 헤더를 보내고 멈추면 callback thread가 설정한 5초 이후에도 대기할 수 있다.

독립 격리 JDK probe `/tmp/Issue109HttpBodyTimeoutProbe.java`에서 300ms request timeout, 즉시 헤더, 2200ms 뒤 body의 다음 byte를 보내는 조건을 실행한 결과 body 읽기는 2528ms 후에 완료했다. 실제 공급자/비밀값은 사용하지 않았다. 인증 구현자와 독립 테스트 설계자에게 byte 수 제한과 함께 body 전체 deadline을 보장하고 신규 RED→GREEN으로 검증하도록 전달했다.

수정은 전체 body를 완료하는 64KiB 제한 Flow subscriber와 공유 전체 exchange 예산 안의 비동기 future 대기·timeout 취소를 사용한다. 독립 token/JWKS body-stall RED 2개 뒤 대상 GREEN이 보고되었고, 소스의 헤더 이후 body deadline 및 용량 제한을 확인했다. 전체 게이트의 최종 소스 지문과 다시 대조한다.

### R109-03 — 닫힌 기존 게스트 방의 명시 연결 UI 없음 (P1, 수정·최종 게이트 통과)

로그인 뒤 계정 우선 원칙에 따라 기존 게스트 HOST 방의 계정 viewer는 아직 참여하지 않은 상태다. 이때 `RoomPage`는 수집 중인 방에만 명시 연결 버튼을 제공했고, 닫힌 방에는 마감 안내만 보여 연결과 아카이브 귀속을 완료할 수 없었다. 서버의 claim은 닫힌 방도 지원하므로 보존 중인 기존 방의 연결 UI가 필요하다.

독립 `src/auth/ClosedGuestClaim.test.tsx`는 full App에서 로그인 계정·닫힌 방·미연결 상태로 시작해 자동 claim과 신규 참여를 금지하고, 사용자 클릭에 의한 claim→기존 HOST 표시→본인 아카이브 링크와 만료 표시를 검증한다. 명시 연결 버튼이 없다는 요구사항 실패를 먼저 확인했다. 웹 `.tdd/red/issue-109-web-closed-claim.json`에 지문과 RED를 기록했다.

수정 후 닫힌 방의 신규 참여 금지를 유지하면서 로그인 계정의 명시 연결 버튼을 제공한다. 동일 frozen 테스트를 이 검토자가 재실행해 1/1 GREEN을 확인했다. 컴포넌트·라우터 통합 결과이며 실제 브라우저·격리 서버 결과와 구분한다. 웹 `.tdd/verification/issue-109-web-closed-claim.json`에 기록했다.

### R109-04 — 인증 결함 주입의 stale XML 오판 (P1 검증 증거, 수정된 재검증 확인 완료)

첫 인증 fault report는 7개 모두 탐지되었다고 기록했으나, 이 검토자가 원본 로그를 직접 대조해 뒤 4개(replay family·absolute cap·provider nonce·Origin prefix)가 `:compileTestKotlin FAILED`임을 확인했다. 보고서에 적힌 OAuth browser test 실패는 이전 실행의 stale XML이어서 이 4개를 결함 탐지 증거로 인정할 수 없다. 앞 3개(Access signature·Access expiry·OAuth browser)는 실제 target assertion failure를 확인했다.

테스트 설계자가 오류를 인정하고 기존 evidence를 history에 보존·INVALIDATED 처리한 뒤, 성공한 컴파일과 해당 실행에서 새로 생성된 target suite/test 결과를 검사해 7개 모두 재실행하기로 했다. 복구 후 auth 전체 GREEN도 별도로 확인한다. 다른 소유권 7개와 계정 영속 2개 fault 원본 로그는 target test 단계의 실패로 독립 확인했다.

수정된 7개 원본 로그를 이 검토자가 다시 읽어 컴파일 오류 없이 실제 target `:test FAILED`와 명시적인 assertion failure를 확인했다. replay 2개, 나머지 각 1개의 적절한 실패로 총 8개 assertion이 결함을 탐지했다. 복구된 인증 소스 5개의 SHA가 현재 파일과 일치한다. 복구 후 별도 auth 45개가 실패·오류·skip 없이 GREEN이며 `.tdd/verification/issue-109-auth-green.json`의 `finalRestoredVerification`에 기록되었다. 이 auth 한정 실행은 모든 프로덕션을 컴파일하지만 test compilation을 account 디렉터리로 제한한 로컬 init을 사용했으므로, 변경하지 않은 저장소 전체 gate 결과와 구분한다.

### R109-05 — Kakao 요청 scope 구분자가 공식 계약과 다름 (P1, 수정·최종 게이트 통과)

production Kakao endpoint의 scope는 `openid profile_nickname`으로 설정되었다. [Kakao 공식 REST 문서](https://developers.kakao.com/docs/en/kakaologin/rest-api)의 authorization 요청은 복수 scope ID를 쉼표로 구분하며, 응답의 scope 문자열만 공백으로 구분한다고 명시한다. Google의 공백 구분과 공급자별 요청 차이를 구분해야 한다.

현재 공통 provider 테스트 대역은 Google/Kakao 모두 임의의 `openid profile` endpoint를 주입하므로 production 기본값의 공급자별 인코딩 차이를 검증하지 못한다. 실제 앱·비밀값 없이 production 기본 authorization URL을 검증하는 독립 테스트를 추가하도록 요청했다. 실제 Kakao에서 거부 응답을 확인한 상태는 아니며, 공식 문서와 불일치하는 요청이 운영 연동을 막을 수 있다는 판단이다.

production 기본 URL의 별도 독립 테스트 2개에서 Google은 통과하고 Kakao만 먼저 실패했다. Kakao 상수만 `openid,profile_nickname`으로 수정한 뒤 production URL·기존 OIDC·body deadline 총 8개가 GREEN이다. 이 검토자가 상수를 원래 값으로 되돌린 바이트의 SHA를 계산하여 이전 7개 fault 검증 소스와 정확히 같음을 확인했다. 이전 fault를 새 소스에서 재실행한 것으로 포장하지 않으며 `.tdd/verification/issue-109-auth-provider-scope.json`에 이 변경 관계를 기록했다.

### R109-06 — 만료된 웹 계정이 게스트 권한으로 요청할 수 있음 (P1, 부모 발견·수정·최종 게이트 통과)

부모가 추가로 확인한 경계다. `getSession()`에는 만료된 계정이 남아 있지만 `getAccessToken()`만 null을 반환하면 API는 Bearer 없이 유효한 기존 guest cookie를 보낸다. 계정 UI에서 수행한 요청이 별개의 게스트 참여를 읽거나 교체할 수 있으므로, background 탭에서 자동 refresh timer가 늦어져도 이 fallback을 막아야 한다.

명시적으로 로그아웃한 뒤의 정상 guest 사용과 구분해, 계정 세션이 존재하나 만료되었으면 API가 인증 오류를 반환하고 요청 자체를 보내지 않는 공개 계약 테스트를 별도로 추가한다. 현재 API의 mocked session 테스트만으로는 실제 session module의 이 경계를 검증하지 못하므로 real module과 결합한 독립 RED를 진행한다.

독립 real-module 테스트 3개의 RED 뒤 strict token 검증과 요청 `cache: no-store`를 추가했다. strict 검증 제거는 GET/PUT 2개, cache 제거는 1개의 실제 assertion 실패를 일으켰고 정확한 원본 복구 후 3개 GREEN이다. 이 검토자가 원본 JSON과 현재 source SHA를 대조하고, 새 strict/cache 블록만 제거하면 이전 API Bearer/epoch fault 소스 SHA가 정확히 복원됨도 확인했다. 기본 getter·정상 게스트 계약은 유지하며 background timer 실행을 보안 전제로 삼지 않는다. 증거는 웹 `.tdd/verification/issue-109-web-expired-session.json`이다.

### R109-07 — 서버가 무시하는 주최자 이름을 보내 정상 Unicode 프로필이 거부될 수 있음 (P2, 수정·전체 웹 게이트 및 native 통과)

계정 이름과 Participant 이름은 1~50 Unicode code point를 허용하고 Zod4도 50개 emoji를 통과시킨다. 그러나 웹은 이를 무시되는 호환 필드 `host_display_name`으로 다시 보내며, 서버의 해당 DTO에는 UTF-16 길이 기준 `@Size(max=50)`가 남아 있다. 따라서 회원이 이름을 다시 입력하지 않아도 되는 계약과 달리 정상 프로필로 방 생성을 완료하지 못할 수 있다.

이 검토자가 설치된 Hibernate Validator 9.1.3의 `SizeValidatorForCharSequence` bytecode에서 `CharSequence.length()` 사용을 확인했고, JavaScript 길이 100인 50개 emoji가 실제 Zod4에서 통과함도 독립 probe로 확인했다. 부모는 서버·보존 정책을 변경하지 않고 웹 요청에서 사용하지 않는 `host_display_name`을 생략하는 좁은 수정을 선택했다. 독립 RED와 수정 후 전체 웹 게이트를 별도로 진행한다.

## 서버 최종 판정 (2026-10-17 UTC)

서버 구현 범위를 승인한다. 변경하지 않은 `sh ./gradlew --no-daemon ktlintCheck assemble test`가 종료 코드 0이며, 보관된 JUnit 130개 suite를 직접 합산해 전체 773개 중 769개 통과, 기존 의도적 skip 4개, 실패·오류 0개를 확인했다. 최종 게이트의 코드·설정·테스트 297개 SHA가 현재 파일과 모두 같고, active 독립 RED의 테스트 지문 16개도 일치한다. 원래 `tdd_guard.py`가 HEAD와 같고 guard 종료 코드 0, 별도 guard self-test 12개 통과를 확인했다. 증거는 `.codex/tdd-evidence/issue-109-final-gate/`에 있다.

R109-01과 R109-05는 수정된 최종 서버 소스에서 전체 게이트를 통과했다. 서버 실제 PostgreSQL/Redis 경합·보안·migration 검증과 생성 OpenAPI schema 일치를 확인했다. 실제 공급자 로그인, 실제 #108 proof adapter와 합친 통합 검증, 배포·운영 DB 변경은 이 승인에 포함하지 않는다. 웹 R109-07의 수정 뒤 전체 웹 게이트·native 브라우저 지문 대조가 남아 있으므로 서버 승인을 전체 목표 완료로 해석하지 않는다.

### R109-08 — 신규 V11과 release publication 카탈로그 불일치 (P1, 부모 발견·수정·원본 CI 추가 게이트 통과)

부모의 push 전 원본 CI 추가 검사에서 immutable release pipeline이 정확히 V1~V10만 허용하는 경계를 발견했다. V11이 추가된 소스는 publication manifest를 만들 수 없으며 host 승인 record도 V10만 허용한다. 서버 런타임 게이트 승인과 이 새 파이프라인 변경의 publication readiness를 분리한다.

검토한 좁은 수정 경계는 신규 build가 정확 V1~V11을 생성하고, 기존 immutable V10 manifest/record와 신규 V11만 명시적으로 허용하는 것이다. actual Flyway checksum, source/CI identity, guard/source fingerprint, archive 검증과 연속 history 검증은 유지해야 한다. V10 host에서 V11은 자동 승인되지 않으며 기존 명시적 first-transition 승인과 두 refresh rule의 DISABLED 조건을 그대로 요구한다. 실제 배포나 승인 정책의 우회는 허용하지 않는다. 독립 RED·결함 주입·원본 offline CI 추가 게이트가 남아 있다.

## 웹 최종 판정 (2026-10-17 UTC)

R109-07까지 반영한 웹 구현 범위를 승인한다. 원본 로그에서 단위 29개 파일·364개 테스트 통과, 브라우저 384개 예정 중 383개 통과·기존 의도적 desktop mobile-layout 스킵 1개·실패 0개를 확인했다. 전체 lint·typecheck/build·생성 API 동기화 검사도 통과했다. `docs/verification/issue-109-web-final-manifest.json`의 최종 소스·빌드입력·API·브라우저 fixture·실행 번들 53개 SHA를 직접 대조해 모두 일치하며 active RED 테스트 지문 8개도 일치한다. 이전 경계의 이력 SHA는 현 실행 번들의 지문으로 사용하지 않았다.

R109-07 독립 2개 테스트는 정상 이름과 50 Unicode emoji에서 무시되는 host 이름 필드를 완전히 생략하고 나머지 정확한 방 설정을 유지한다. 필드를 복구한 결함은 두 assertion 모두 탐지하고 원본 복구 뒤 GREEN이다. 기존 3개 serialization 기대값 변경은 새 계정 권위 계약의 명시적 supersession으로 남기며 Unicode validation 8개와 일반 게스트 흐름을 유지한다.

최신 native browser는 15:26:14 UTC에 12개 통과했다. 격리 실제 Spring/PostgreSQL/Redis와 loopback HTTP OIDC/JWKS를 거치며 API route mock과 외부 요청은 0이다. provenance의 서버 main 소스 156개·컴파일된 main class 605개·웹 소스 30개 SHA가 현재 파일과 일치한다. production 번들의 전체 브라우저 fixture suite와 실제 프로토콜 native suite는 서로 다른 증거로 구분한다. 실제 공급자 로그인은 여전히 미검증이다.

R109-08 수정은 세 Python 파일의 명시 카탈로그 개수·범위만 바꾸고 기존 identity/checksum/fingerprint/transition 검사를 유지했다. 신규 독립 9개와 기존 7개가 통과했다. publisher가 V11 누락을 허용하는 결함, 자동 승인에서 history/schema/guard 비교를 제거하는 결함, host parser가 V12를 허용하는 결함의 원본 TAP을 직접 읽어 각각 1/2/1개의 실제 assertion 실패를 확인했다. 세 소스가 정확 SHA로 복원되고 신규 9개가 모두 다시 통과했다. 원본 CI 추가 게이트의 최종 결과를 기다린다.

## 최종 독립 판정

**APPROVED_IMPLEMENTATION_SCOPE**. 남은 구현 결함이나 로컬 필수 게이트 실패는 없다. 기존 원본 CI 추가 검사 최종 실행은 deployment policy 13, preflight 21, release contract 16, runtime provider 19, built-image workflow 12, issue-links 9개와 Compose·DB refresh·Nginx shell 검사가 모두 통과했다. 변경하지 않은 V8 guard 37개는 동일한 15초 내부 제한을 유지한 단독 실행에서 359.672초에 전부 통과했다. 최초 환경·동시 실행 실패를 지우거나 최초 통과로 보고하지 않았고 `.tdd/reviews/issue-109-ci-extras.json`에 그대로 보존했다.

최종 Kotlin 게이트의 소스와 추가 publication/CI 소스 총 304개 SHA, active #109 RED 테스트 지문 17개를 현재 파일과 직접 다시 대조했다. post-catalog guard도 원래 hook SHA를 유지하여 통과했으며 Python-only 변경 뒤 기존 Kotlin gate의 up-to-date 재사용을 새 실행으로 집계하지 않았다. 원래 V1~V10 migration SQL, 별도 마감 처리, #110 관측 소스는 수정하지 않았다.

이 판정은 별도 사용자 승인된 commit/push/Draft PR 진행을 위한 구현 범위 리뷰다. 부모가 정확한 server PR head의 원격 CI 및 실제 ARM64 image 검증을 이어서 담당한다. 실제 Google/Kakao 로그인·운영 callback/cookie/key 검증, 실제 #108 proof adapter와 합친 통합, #110 callback query error-log 비노출은 남은 외부 검증이다. 실제 운영 로그인 성공·병합·배포·운영 DB 변경을 승인하거나 완료했다고 표시하지 않는다.
