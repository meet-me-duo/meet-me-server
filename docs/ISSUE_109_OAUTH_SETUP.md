# #109 OAuth 활성화 준비

> **최신 상태:** 부모의 후속 인계에서 Google·Kakao 공개 앱 설정 완료를 확인했다. 아래 초기 미설정 절차는 과거 이력이며 재실행 요구가 아니다. 기존 Google Secret 보관/이전 값 폐기·Kakao callback 저장 완료도 유지한다. 남은 런타임 전달·RS256/JWKS·실로그인·운영 전환은 [현재 안내](ISSUE_107_EXTERNAL_READINESS_RUNBOOK.md)를 따른다. 실제 secret 값이나 운영 로그인 성공을 확인한 의미는 아니다.

2026-10-17 확인. 이 문서는 설정 준비이며 실제 앱·키·운영 로그인 성공 근거가 아니다. 지금은 생성·입력·콘솔 변경을 실행하지 않는다. 사용자 승인 후 사용자 신원·관리자 권한으로 수행할 절차다.

## [USER ACTION REQUIRED]

**이유:** 공급자 앱 소유권·동의 화면·자격 증명·운영 redirect 승인은 사용자 책임이다. 사용자가 기존 Kakao meet-me 앱과 Google meet-me 프로젝트 존재를 확인했다. 신규 앱/프로젝트는 생성하지 않는다. 에이전트는 실제 secret을 읽거나 출력하지 않는다.

**사전 조건:** Meet me용 Google Cloud 프로젝트와 Kakao Developers 앱에 대한 관리자 권한, 서비스 홈페이지·개인정보처리방침·연락처 준비. 로그인만 다루며 Calendar grant는 요청하지 않는다. 로컬·운영 앱을 분리하면 운영에 테스트 URI/사용자가 섞이지 않지만 앱·키 관리 비용이 늘어난다.

### Google

1. [Google Cloud Console](https://console.cloud.google.com/)에서 기존 `meet-me` 프로젝트를 선택한다.
2. Google Auth Platform의 Branding/Audience/Data Access에서 앱 표시 정보·대상 사용자·기본 OpenID 프로필 동의를 검토한다. 요청 scope는 `openid profile`이며 이메일·Calendar 권한은 요청하지 않는다. 테스트 모드라면 허용된 테스트 사용자만 실제 검증에 사용한다.
3. Google Auth Platform → Clients → 기존 Web application client를 선택한다. 기존 client가 없다면 생성하지 말고 그 상태를 알린 뒤 별도 승인을 받는다.
4. 아래 Google redirect URI를 Authorized redirect URIs에 정확히 등록한다. 이번 구현은 서버 code flow이며 JavaScript origin은 redirect URI와 다르다.
5. Client ID와 Client Secret을 아래 이름으로 안전한 런타임 환경에 직접 입력한다.

Google은 등록 redirect와 요청 redirect의 정확한 일치를 요구한다. [공식 OIDC·Clients·redirect 설정](https://developers.google.com/identity/openid-connect/openid-connect).

### Kakao

1. [Kakao Developers](https://developers.kakao.com/)에서 기존 `meet-me` 앱을 선택한다.
2. Kakao Login을 활성화하고 OpenID Connect를 활성화한다. 동의 항목은 닉네임을 포함한 로그인 최소 프로필로 제한한다. 요청 scope는 `openid,profile_nickname`이며 이메일·Calendar 권한은 요청하지 않는다.
3. 현행 App → Platform Key → REST API key에서 Web/Redirect URI와 Client secret 설정을 검토한다. 계정별 UI가 다르면 실제 메뉴를 확인한 뒤 변경한다.
4. 아래 Kakao redirect URI를 정확히 등록한다. REST API key를 `KAKAO_CLIENT_ID`로, 활성화한 client secret을 `KAKAO_CLIENT_SECRET`으로 사용한다. JavaScript key/Admin key를 대신 넣지 않는다.

Kakao OIDC는 `openid`와 nonce를 사용하며 현행 discovery는 S256 PKCE를 지원한다. [공식 사전 설정](https://developers.kakao.com/docs/ko/kakaologin/prerequisite), [공식 REST/OIDC·PKCE 계약](https://developers.kakao.com/docs/en/kakaologin/rest-api).

## 정확한 URI

| 용도 | 로컬 | 운영 |
| --- | --- | --- |
| Google redirect | `http://localhost:8080/api/auth/google/callback` | `https://api.meet-me.co.kr/api/auth/google/callback` |
| Kakao redirect | `http://localhost:8080/api/auth/kakao/callback` | `https://api.meet-me.co.kr/api/auth/kakao/callback` |
| 서버→웹 완료 redirect | `http://localhost:5173/auth/callback` | `https://app.meet-me.co.kr/auth/callback` |
| 정확한 frontend Origin | `http://localhost:5173` | `https://app.meet-me.co.kr` |

웹 callback은 공급자 콘솔 redirect가 아니다. URI 끝 slash를 추가하지 않는다. 개발을 localhost로 고정하고 127.0.0.1과 혼용하지 않는다. cookie는 로컬도 Secure이며 브라우저 localhost 동작은 실제 통합 검증 결과로 별도 기록한다. 원격 개발 호스트라면 HTTPS와 정확한 callback/origin 승인 후 새 URI를 사용한다.

## 변수와 안전한 입력 위치

| 변수 | 의미/형식 | 안전한 위치 |
| --- | --- | --- |
| `GOOGLE_CLIENT_ID` | Google Web OAuth client ID | 서버 런타임 환경 |
| `GOOGLE_CLIENT_SECRET` | Google client secret | gitignored 로컬 `.env` 또는 승인된 운영 secret store |
| `GOOGLE_REDIRECT_URI` | 위 환경별 Google URI | 서버 런타임 환경 |
| `KAKAO_CLIENT_ID` | Kakao REST API key | 서버 런타임 환경/secret store |
| `KAKAO_CLIENT_SECRET` | 활성화한 Kakao client secret | gitignored 로컬 `.env` 또는 승인된 운영 secret store |
| `KAKAO_REDIRECT_URI` | 위 환경별 Kakao URI | 서버 런타임 환경 |
| `AUTH_SIGNING_KEY_ID` | 현재 서명키의 고유 kid | 서버 런타임 환경 |
| `AUTH_SIGNING_PRIVATE_KEY_PATH` | RSA PKCS#8 PEM 파일의 절대 경로 | 저장소 밖 제한권한 secret file, 컨테이너 read-only mount |
| `AUTH_VERIFICATION_JWKS_PATH` | 현재·교체 중 이전 public RSA key의 JWKS 파일 경로 | read-only mount. private key 포함 금지 |
| `AUTH_ISSUER` | 운영 기본 `https://api.meet-me.co.kr`; 로컬 격리 issuer로 지정 | 서버 런타임 환경 |
| `AUTH_AUDIENCE` | 기본 `meetme-web` | 서버 런타임 환경 |
| `AUTH_WEB_CALLBACK_URI` | 위 환경별 웹 완료 URI | 서버 런타임 환경 |
| `APP_ALLOWED_ORIGINS` | 정확한 frontend Origin 목록, wildcard 금지 | 기존 서버 허용 Origin 설정 |
| `VITE_API_BASE_URL` | 운영 `https://api.meet-me.co.kr`; 로컬 빈 값은 Vite proxy | 웹 공개 빌드 환경, secret 금지 |

Redis는 기존 서버 연결 설정을 재사용한다. Auth 전용 key namespace를 사용하고 Refresh 원문은 저장하지 않는다. 현재 배포 파이프라인에는 새 auth secret 전달을 임의 추가하지 않는다. 승인된 운영 secret 저장·마운트 경로 및 기존 CD 연결은 별도 적용 단계에서 확인한다. 운영 signing key 생성/회전도 사용자 작업이며 자동 임시 키로 대체하지 않는다.

**공유 금지:** Client Secret, REST API key 원문, private key, Access/Refresh/provider token, 쿠키, OAuth code, 비밀번호·복구 코드. 채팅·저장소·빌드 로그·스크린샷에 넣지 않는다. `VITE_` 변수는 브라우저 공개 자산에 들어가므로 secret을 넣지 않는다.

**완료 신호:** `Google 설정 완료`, `Kakao 설정 완료`, `변수 이름 configured`, 환경명과 등록 URI만 전달한다. 비밀값은 보내지 않는다.

**다음 검증 [AGENT/SHARED]:** 값 비출력 상태 검사 → 사용자 승인 계정으로 실제 두 공급자 로그인·취소·재로그인 → callback/Origin/cookie/kid·rotation → 다른 기기 archive/권한 격리를 확인한다. 이 검증 전에는 운영 로그인 성공으로 표시하지 않는다.

**차단 범위:** 실제 공급자 연동·운영 활성화만 외부 설정에 종속한다. 격리 provider 대역, 실제 PostgreSQL/Redis, 보안·경합·웹 브라우저 통합과 문서 작업은 계속 진행한다.


## 사용자 콘솔 확인 반영 (2026-10-17)

- 기존 Google meet-me 프로젝트는 외부·테스트 상태, 테스트 사용자1명, OAuth client/승인도메인/scope목록 없음으로 사용자 확인됐다. 프로젝트를 새로 만들지 않는다. Web OAuth client 생성 및 Branding/Audience/DataAccess/승인도메인/정확redirect 설정이 별도 승인 대상이다. 실제 ClientSecret 생성·입력은 아직 승인 전이다.
- 기존 Kakao meet-me 앱은 사용자Owner·일반앱, 대표도메인 https://meet-me.co.kr, KakaoLoginOFF로 확인됐다. 새앱/비즈전환을 전제하지 않는다. KakaoLogin/OpenIDConnect 활성화, 닉네임동의, redirect 및 clientsecret 설정 검토가 승인 대상이다. 이메일동의 권한은 필요없다. profile_image는 요청하지 않는다.
- Google openid profile은 이름을 받기 위한 묶음 scope다. email/Calendar는 요청하지 않으며 응답에 불필요한 사진필드가 와도 저장·사용하지 않는다. Kakao openid,profile_nickname은 이름만 요청한다. 둘 다 공급자subject+내부UUID가 식별자다.
- 실제 운영설정·로그인 성공은0. 위 사용자확인 상태를 에이전트의 secret조회나 콘솔변경 결과로 표시하지 않는다.

- 후속 사용자 확인: Google 새Secret발급/보관·이전값폐기 및 Kakao callback저장 완료. 에이전트는 값조회/출력을 하지 않았고 서버에는 아직입력되지않음. 이 확인을 실제공급자로그인/운영활성화완료로 확대하지 않는다. 공급자대역검증유지.
