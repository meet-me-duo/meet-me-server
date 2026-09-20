# User Intervention Runbook

> **상태:** Active
>
> **최종 확인:** 2026-09-20
> **목적:** 외부 계정, 권한, 결제와 비밀정보가 필요한 작업의 입력 위치와 완료 기준을 비밀값 노출 없이 관리한다.

이 문서는 `.agents/rules/user-intervention.md`를 이 프로젝트의 실제 외부 서비스에 적용한 실행 가이드다.
실제 API Key, Client Secret, Access Token, 비밀번호, 복구 코드와 private key는 채팅, Git, Issue,
PR, 로그에 남기지 않는다.

## 1. 설정 저장 위치

| 환경 | 저장 위치 | 원칙 |
| --- | --- | --- |
| 로컬 개발 | Git에서 제외된 저장소 루트 `.env.local` | `.env.example`의 이름만 복사하고 실제 값은 사용자가 직접 입력한다. |
| 현재 GitHub CI | 없음 | 현재 `ci.yml`은 외부 API나 배포 자격 증명을 사용하지 않는다. |
| 개발용 외부 연동 검사 | GitHub `integration` Environment | 2026-09-18 생성 완료. 수동 `workflow_dispatch` 통합 검사만 이 Environment를 사용하고 PR CI에는 노출하지 않는다. |
| 향후 배포 workflow | GitHub `production` Environment의 Variable | 장기 Access Key 없이 Bootstrap이 생성한 GitHub OIDC 역할과 비밀이 아닌 배포 식별자만 등록한다. |
| 운영 애플리케이션 | AWS Systems Manager Parameter Store·RDS 관리형 Secrets Manager secret | 애플리케이션 비밀을 GitHub에서 컨테이너 이미지나 Terraform state로 복사하지 않는다. 실제 API key와 인증서 내보내기 passphrase는 사용자가 SSM `SecureString`에 직접 입력한다. |

GitHub의 비밀값과 비밀이 아닌 설정을 구분한다.

- **Secret:** API Key, OAuth Client Secret, Grafana Access Policy Token, JWT private key, 공급자 토큰 암호화 키, 데이터베이스 비밀번호
- **Variable:** AWS Region, 배포 IAM Role ARN, 이미지 저장소 이름, 공개 endpoint처럼 노출되어도 인증 권한이 생기지 않는 값
- AWS 배포 인증에 GitHub OIDC를 채택하면 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`는 GitHub Secret으로 만들지 않는다.

## 2. 설정 이름 목록

### 현재 로컬 입력 대상

| 이름 | 분류 | 발급 위치 | 현재 상태 |
| --- | --- | --- | --- |
| `GEMINI_API_KEY` | Secret | Google AI Studio API Keys | `.env.local`과 GitHub `integration` 등록 확인 완료. 키 값은 출력하지 않음 |

### Post-MVP에 준비할 OAuth 값

다음 이름은 `.env.example`에 자리만 예약되어 있고 제출 MVP에서는 애플리케이션이 읽지 않는다. Wanted
제출 MVP가 완료될 때까지 새 Client를 만들거나 `.env.local`과 GitHub에 입력하지 않는다.

| 이름 | 분류 | 용도 | 선행 결정 |
| --- | --- | --- | --- |
| `GOOGLE_OAUTH_CLIENT_ID` | 식별 설정 | Google 로그인과 별도 Calendar 동의에 사용할 개발용 Web OAuth client | Google Auth Platform Testing 앱 |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Secret | 서버의 Google authorization code 교환 | Google Auth Platform Testing 앱 |
| `KAKAO_OAUTH_CLIENT_ID` | 식별 설정 | 개발용 Kakao REST API key | Kakao 개발자 앱 |
| `KAKAO_OAUTH_CLIENT_SECRET` | Secret | Kakao token 발급 요청의 Client secret | Kakao 개발자 앱 |

JWT private key, 공급자 token 암호화 키, PostgreSQL·Redis 자격 증명과 Grafana Cloud token의 정확한
환경 변수 이름은 해당 구현·배포 설계에서 확정한다. 이름이 정해지기 전에 임시 secret을 만들지 않는다.

## 3. Gemini

### [USER ACTION REQUIRED]

**이유:** Gemini API Key 생성, 이용약관 동의, 결제와 실제 비밀값 입력은 Google 계정 소유자가 해야 한다.
실제 사용자 자연어는 개인·민감정보를 포함할 수 있다. Google은 무료 서비스 입력을 제품 개선에 사용할 수
있고 개인·민감정보를 제출하지 말라고 안내하므로, 운영 사용자 데이터를 처리할 프로젝트는 Paid Tier를
권장한다.

**위치:** [Google AI Studio API Keys](https://aistudio.google.com/api-keys) → `Dashboard` →
`Projects`, `API Keys`, `Usage` 또는 `Rate limits`

**사전 조건:** Google 계정, meet-me가 소유할 Google Cloud 프로젝트, 운영 Paid Tier를 선택한다면
Cloud Billing 계정·결제 수단·최소 선불금 또는 후불 결제 승인

**할 일:**

1. `Dashboard > Projects`에서 meet-me 전용 프로젝트를 선택하거나 가져온다.
2. `API Keys`에서 현재 키가 **authorization key**인지 확인한다. Google은 2026년 9월부터 standard
   API key를 거부한다고 안내하므로 standard key라면 새 authorization key로 교체한다.
3. 운영 사용자 데이터를 처리할 프로젝트는 `Set up billing`으로 Paid Tier를 활성화한다. 결제 승인 전에는
   합성 테스트 데이터만 사용한다.
4. `Dashboard > Rate limits`에서 사용할 모델의 RPM·TPM·RPD를 확인하고, `Usage`에서 사용량을 확인한다.
5. 키를 저장소 루트 `.env.local`의 `GEMINI_API_KEY=` 뒤에 직접 입력한다.

**입력값:** `GEMINI_API_KEY=<Google AI Studio에서 한 번 복사한 실제 키>`

**확정된 단계:**

- 개발·계약 테스트는 **Free Tier + 합성 데이터**로 진행한다.
- 실제 사용자 자연어를 전송하기 전에 사용자가 비용을 검토하고 **Paid Tier** 전환을 승인한다. Paid Tier
  전환 전에는 실제 사용자·개인·민감 데이터를 보내지 않는다.

**공유 금지:** API Key 원문, 결제 정보, 복구 코드

**완료 확인:** 에이전트에는 `Gemini: auth key / Free 또는 Paid / 로컬 등록 완료`처럼 값 없는 상태만 알린다.

**다음 검증:** 에이전트가 `.env.local`의 `GEMINI_API_KEY` 존재 여부만 확인하고, Gemini Adapter가 구현된 뒤
합성 입력으로 모델 목록·Structured Output 호출을 검증한다.

**영향 범위:** 키가 없어도 Domain·Application과 Fake Adapter 개발은 가능하다. 실제 Gemini 계약 검증과
운영 호출만 차단된다.

공식 근거: [API Key 관리](https://ai.google.dev/gemini-api/docs/api-key),
[Billing](https://ai.google.dev/gemini-api/docs/billing),
[Gemini API 추가 약관](https://ai.google.dev/gemini-api/terms),
[Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)

## 4. 지도 API `[POST-MVP]`

제출 MVP는 Kakao Local을 포함한 지도 API를 호출하지 않으므로 앱 활성화, 비즈월렛, 결제 카드 또는
`KAKAO_LOCAL_API_KEY` 등록이 필요하지 않다. 기존에 등록한 key를 저장소나 채팅에 옮기지 않는다.
Post-MVP에서 실제 좌표·이동시간 검증이 필요해지면 공급자, 무료 제공량, 결제 수단과 운영 비용을 다시
비교하고 사용자가 결제를 승인한 뒤 별도 `[USER ACTION REQUIRED]` 절차를 작성한다.

## 5. Google 로그인과 Calendar `[POST-MVP]`

### [AGENT] 개발 callback 계약

Post-MVP 작업을 재개할 때 Spring Security의 기본 callback 형식으로 개발용 Web OAuth client를 준비한다.

- Google 로그인 registration ID: `google-login`
- Google Calendar 증분 동의 registration ID: `google-calendar`
- 개발 Redirect URI:
  - `http://localhost:8080/login/oauth2/code/google-login`
  - `http://localhost:8080/login/oauth2/code/google-calendar`
- 로그인 scope: `openid email profile`
- Calendar는 로그인과 별도의 증분 동의 및 `access_type=offline`을 사용한다.
- Calendar 범위는 primary calendar만 반영할지, 구독한 여러 calendar를 반영할지 사용자 선택 뒤 확정한다.
- 같은 개발용 Google Web OAuth client에 두 Redirect URI를 모두 등록한다.

### [USER ACTION REQUIRED]

**이유:** OAuth consent screen, 앱 공개 대상, 민감 scope 승인과 Client Secret 발급은 Google 프로젝트
소유자가 해야 한다.

**위치:** [Google Auth Platform](https://console.cloud.google.com/auth) → `Branding`, `Audience`,
`Data Access`, `Clients`; [Google Calendar API](https://console.cloud.google.com/apis/library/calendar-json.googleapis.com)

**사전 조건:** Google 계정, 앱 이름·지원 이메일, 개발 테스트에 사용할 Google 계정. 운영 도메인과
개인정보처리방침 URL은 운영 client와 공개 검증 전에 추가한다.

**할 일:**

1. meet-me Google Cloud 프로젝트에서 Google Calendar API를 활성화한다.
2. `Google Auth Platform > Branding`에 앱 이름과 지원 이메일을 입력한다.
3. `Audience`에서 `External / Testing`과 테스트 사용자를 설정한다. Calendar scope를 요청한
   Testing authorization과 refresh token은 7일 후 만료될 수 있음을 고려한다.
4. `Data Access`에 `openid`, `userinfo.email`, `userinfo.profile`을 추가한다. Calendar scope는 아래 사용자
   선택이 끝난 뒤 최소 범위로 추가한다.
5. `Clients > Create client > Web application`에서 위의 localhost Redirect URI 두 개를 정확히 등록한다.
   scheme, host, path와 trailing slash까지 실제 요청과 일치해야 한다.
6. 생성된 Client ID와 Client Secret을 `.env.local`의 확정된 이름에 직접 입력한다.
7. GitHub `integration` Environment에도 Client ID는 Variable, Client Secret은 Secret으로 등록한다.
8. 공개 운영 전 별도 운영 프로젝트/client와 HTTPS Redirect URI를 준비하고 Branding·Data Access 검증
   상태와 `Audience > In production` 전환을 확인한다.

**입력값:**

```properties
GOOGLE_OAUTH_CLIENT_ID=
GOOGLE_OAUTH_CLIENT_SECRET=
```

**공유 금지:** Client Secret, authorization code, access token, refresh token, 사용자 Calendar 데이터

**완료 확인:** `Google OAuth client 생성 완료`, 등록한 Redirect URI 목록, 승인된 scope 이름과
Testing/In production 상태만 알린다. Client Secret 값은 알리지 않는다.

**다음 검증:** 에이전트가 설정 존재 여부, Redirect URI 일치, 로그인 OIDC와 별도 Calendar 증분 동의,
refresh token 갱신·철회를 값 출력 없이 검증한다.

**영향 범위:** 제출 MVP에는 영향이 없다. Post-MVP Google 로그인 착수 전에 client를 준비한다. Calendar scope
선택, token 암호화 정책과 공급자 장애 UX는 DG-05에서 계속 확정한다. 운영 공개는 별도 운영 client,
HTTPS domain, 개인정보처리방침과 Google 검증 전까지 차단된다.

공식 근거: [OAuth client 관리](https://support.google.com/cloud/answer/15549257),
[Audience와 Testing](https://support.google.com/cloud/answer/15549945),
[Calendar scope](https://developers.google.com/workspace/calendar/api/auth),
[Web server OAuth](https://developers.google.com/identity/protocols/oauth2/web-server)

## 6. Kakao 로그인 `[POST-MVP]`

### [AGENT] 개발 callback·최소 정보 계약

개발 앱은 OIDC를 켜고 안정적인 `sub`를 계정 식별자로 사용한다. `openid`와 nickname만 요청하며 email은
수집하지도 계정 식별자로 사용하지도 않는다. Spring Security registration ID는 `kakao`, 개발 Redirect URI는
`http://localhost:8080/login/oauth2/code/kakao`로 고정한다.

### [USER ACTION REQUIRED]

**이유:** Kakao Developers 앱 생성, 서비스 정보·동의 항목 승인과 Client Secret 발급은 앱 소유자가 해야 한다.

**위치:** [Kakao Developers](https://developers.kakao.com/) → `내 애플리케이션` → 대상 앱 →
`앱 > 일반`, `앱 > 플랫폼 키 > REST API 키`, `카카오 로그인 > 사용 설정`, `동의항목`, `OpenID Connect`

**사전 조건:** Kakao 계정, 앱 이름·회사 또는 개발자 표시명·아이콘. email을 필수 동의로 쓰지 않으므로
개발 단계에서 이를 위한 Biz App 전환은 요구하지 않는다.

**할 일:**

1. meet-me 전용 앱을 만들고 앱 기본 정보를 입력한다.
2. `카카오 로그인 > 사용 설정`을 `ON`으로 바꾼다.
3. `카카오 로그인 > OpenID Connect`를 `ON`으로 바꾼다.
4. `카카오 로그인 > 동의항목`에서 nickname만 사용하도록 설정하고 email은 요청하지 않는다.
5. `앱 > 플랫폼 키 > REST API 키`에서
   `http://localhost:8080/login/oauth2/code/kakao`를 Redirect URI로 등록한다.
6. 같은 화면의 `Client secret`이 활성화된 상태인지 확인하고 발급된 값을 안전하게 보관한다.
7. REST API key와 Client secret을 `.env.local`에 직접 입력한다.
8. GitHub `integration` Environment에도 REST API key는 Variable, Client secret은 Secret으로 등록한다.

**입력값:**

```properties
KAKAO_OAUTH_CLIENT_ID=
KAKAO_OAUTH_CLIENT_SECRET=
```

`KAKAO_OAUTH_CLIENT_ID`에는 JavaScript key나 Admin key가 아니라 **REST API key**를 입력한다.

**공유 금지:** Client Secret, Admin key, access token, refresh token, authorization code

**완료 확인:** `Kakao 앱 생성 완료`, Redirect URI, OIDC ON/OFF와 동의 항목 ID만 알린다. 실제 key와
secret은 알리지 않는다.

**다음 검증:** 에이전트가 설정 존재 여부, `state`·`nonce`, ID token issuer·audience·signature와 callback
일치 여부를 값 출력 없이 검증한다.

**영향 범위:** 제출 MVP에는 영향이 없다. Post-MVP Kakao 로그인 착수 전에 개발 앱을 준비한다. 운영 domain과
callback은 배포 domain 확정 뒤 같은 앱에 추가하거나 운영 앱을 분리한다.

공식 근거: [Kakao Login 사전 설정](https://developers.kakao.com/docs/en/kakaologin/prerequisite),
[REST API](https://developers.kakao.com/docs/en/kakaologin/rest-api),
[앱 설정](https://developers.kakao.com/docs/en/app-setting/app)

## 7. 운영 도메인과 DNS

### 현재 상태

- `[USER]` 가비아에서 `meet-me.co.kr` 도메인을 확보했다.
- 운영 프론트엔드는 `https://app.meet-me.co.kr`, API는 `https://api.meet-me.co.kr`를 사용한다.
- Route 53 Hosted Zone이 아직 생성되지 않았으므로 현재는 가비아 기본 네임서버를 유지한다.

### [SHARED] Route 53 전환

**이유:** Route 53 Hosted Zone은 Terraform이 생성할 수 있지만 등록기관의 네임서버 변경은 도메인 소유자가
가비아에서 직접 수행해야 한다.

**위치:** 가비아 로그인 → `My가비아` → `서비스 관리` → `도메인 통합 관리툴` → `도메인 정보 변경` →
`네임서버`

**사전 조건:** 에이전트가 Terraform으로 `meet-me.co.kr` Route 53 Hosted Zone을 생성하고 AWS가 할당한
네임서버 4개를 전달한 상태

**할 일:**

1. 에이전트가 Hosted Zone, ACM DNS 검증과 `app`·`api` 레코드를 Terraform으로 준비한다.
2. 사용자는 위 가비아 메뉴에서 `meet-me.co.kr`을 선택하고 네임서버 설정을 연다.
3. `타사 네임서버`를 선택한다.
4. 에이전트가 전달한 Route 53 네임서버 호스트명 4개를 입력한다. IP 주소나 임의의 DNS 레코드는 입력하지 않는다.
5. 변경을 저장하고 완료 상태만 알린다.

**입력값:** Route 53 Hosted Zone이 할당한 `ns-...awsdns-...` 형식의 네임서버 호스트명 4개

**공유 금지:** 가비아 비밀번호, MFA·본인인증 값, 결제 정보. 네임서버 호스트명과 domain은 공개 설정이므로
공유할 수 있다.

**완료 확인:** `meet-me.co.kr 네임서버 변경 완료`라고 알린다.

**다음 검증:** 에이전트가 공개 DNS 위임, `app`·`api` 레코드, ACM 발급 상태와 HTTPS 연결을 검증한다.

**영향 범위:** 변경 전까지 Terraform과 애플리케이션 구현은 계속할 수 있지만 공개 HTTPS 배포 검증은
완료할 수 없다. 네임서버 변경은 전파에 최대 48시간이 걸릴 수 있다.

공식 근거: [가비아 네임서버 변경 안내](https://customer.gabia.com/faq/detail/286/991)

## 8. GitHub Actions와 AWS

### 현재 상태

- 현재 CI는 GitHub 기본 `GITHUB_TOKEN`만 사용하고 외부 Secret이 필요하지 않다.
- GitHub `integration` Environment와 `GEMINI_API_KEY` Secret 등록은 완료됐다. 현재 workflow는 이
  Environment를 참조하지 않는다.
- 2026-09-20 기준 사용자는 `AdministratorAccess`가 있는 IAM 사용자에 MFA를 등록했고 AWS CLI 2.36.49의
  `aws login` 임시 자격 증명으로 `ap-northeast-2` STS 호출을 검증했다. 장기 Access Key는 만들지 않았다.
- 월 USD 80 일반 요금 Budget과 실제 비용 50%·75%·100%, 예상 비용 100% 이메일 알림을 생성했다.
- AWS Free Plan 잔여 기간은 182일, Credit 잔액은 USD 120이다. 잔여 기간 만료와 Credit 소진 중 먼저
  도달하면 Free Plan이 종료되므로 공개 운영을 지속하기 전에 Paid Plan 전환 승인이 필요하다.
- `meet-me.co.kr`의 상위 `co.kr` 위임은 가비아 네임서버 3개를 가리키지만 2026-09-20 공개 resolver와
  가비아 권한 서버 조회는 `SERVFAIL`·`REFUSED`를 반환했다. 현재 공개 DNS 서비스는 정상 해석되지 않으므로
  Route 53 전환 전에 운영 중인 웹·메일 레코드가 없음을 사용자에게 확인하고, Hosted Zone 생성 후 AWS
  네임서버 4개로 위임한다.
- 제출 MVP는 EC2 `t4g.small`, ECR, RDS PostgreSQL 18 `db.t4g.micro` Single-AZ, ElastiCache Serverless for
  Valkey, S3 Terraform state, SSM·Secrets Manager와 GitHub OIDC를 사용한다.
- 2026-09-20 승인된 Bootstrap plan을 적용해 암호화·버전 관리 S3 state bucket과
  `meet-me-github-production` OIDC 역할을 생성했다. 이어서 Production plan은 생성 45개, 조회 2개,
  변경·삭제 0개로 계산했다.
- 승인된 Production apply는 30개 리소스를 생성한 뒤 Free Plan의 RDS 백업 보존 기간 제한과 ACM exportable
  certificate 제한에서 중단됐다. 현재 EC2는 `running`, ElastiCache Serverless for Valkey는 `available`,
  Route 53 Hosted Zone은 생성된 상태이며 RDS·ACM과 종속 리소스 15개가 남아 있다. 계정은 Free Plan
  `ACTIVE`, Credit USD 120이며 Paid Plan 전환 또는 Free Plan용 인증서·백업 설계 변경을 결정하기 전에는
  재적용하지 않는다.
- 사용자가 Root 계정으로 Paid Plan 전환을 완료했고 IAM CLI에서 `PAID`·`ACTIVE`, Credit USD 120 유지를
  확인했다. 동일한 나머지 15개 plan을 적용하던 중 `aws login` 단기 토큰이 만료되어 AWS 작업 자체와 별개로
  원격 state 업로드·lock 해제가 실패했다. Terraform 프로세스가 없는 것과 Git에서 제외된 로컬
  `errored.tfstate` 복구본을 확인했으며, IAM 사용자가 `aws login`을 갱신한 뒤 원격 state와 대조·복구하기
  전에는 apply를 재실행하지 않는다.
- 갱신한 IAM 세션으로 stale lock 해제, 최신 state push와 정상 `available` RDS의 taint 해제를 완료했다.
  나머지 12개 리소스를 적용한 뒤 Terraform plan 0변경, EC2 `running`, RDS·Valkey `available`, ACM
  `PENDING_VALIDATION`을 확인하고 민감한 로컬 복구 state와 saved plan을 삭제했다. Route 53 네임서버 4개가
  준비됐으며 기존 웹·메일 사용 여부를 확인한 뒤 가비아 위임을 변경한다.
- 사용자가 `meet-me.co.kr`에 운영 중인 기존 웹사이트와 메일이 없음을 확인했다. 기존 MX·TXT·웹 레코드
  이관 없이 Route 53 네임서버 4개로 전체 위임을 변경할 수 있다.
- 사용자가 가비아에서 Route 53 네임서버 4개로 변경을 완료했다. Route 53 권한 서버의 zone은 정상이지만
  `.co.kr` 상위 등록부는 아직 기존 가비아 네임서버 3개를 반환하고 공개 resolver는 `SERVFAIL`이므로 전파를
  기다린다. DNSSEC DS는 없어 서명 불일치 문제는 아니다.
- 후속 확인에서 `.co.kr` 상위 등록부와 Cloudflare 공개 resolver가 Route 53 네임서버 4개를 정확히 반환하고
  `api.meet-me.co.kr` A 레코드도 해석됐다. ACM은 `PENDING_VALIDATION`으로 AWS의 후속 검증 처리를 기다린다.
- 사용자가 `/meet-me/production/secret/` 아래 Gemini·Kakao key와 ACM export passphrase 3개를 직접
  등록했다. 값 조회 없이 세 Parameter의 존재와 `SecureString` 타입을 확인했다. ACM 검증 CNAME은 공개
  DNS에서 AWS 기대값과 일치하고 도메인 검증은 `SUCCESS`이며 인증서 전체 발급 처리를 기다린다.
- 후속 확인에서 `api.meet-me.co.kr` exportable ACM 인증서가 `ISSUED`로 전환됐다. 사용자가 ACM에서
  수동으로 private key를 export하지 않고 배포 스크립트가 SSM passphrase로 export·설치한다.
- 사용자가 GitHub `production` Environment에 배포 Variable 7개를 직접 등록했다. GitHub API로 값은
  출력하지 않고 정확한 변수 이름 7개와 `main` custom deployment branch policy를 확인했다.

### [SHARED] 선행 결정

다음 항목으로 CD workflow와 권한을 구현한다.

1. 단일 EC2 `t4g.small`의 Nginx·Spring Boot 컨테이너
2. ECR image digest
3. ElastiCache Serverless for Valkey
4. versioning·암호화와 native lock file을 사용하는 S3 remote state
5. RDS managed Secrets Manager secret과 사용자가 직접 등록하는 SSM `SecureString`
6. 동일 이미지 Flyway 선실행, 애플리케이션 교체, 이전 image digest rollback

AWS 인증은 장기 Access Key를 GitHub Secret에 저장하는 방식보다 GitHub Actions OIDC와 환경별 최소 권한
IAM role을 권장한다. 이 방식을 채택하면 GitHub에는 AWS secret이 없고 다음 비밀 아닌 Variable만 필요하다.

```text
AWS_REGION
AWS_DEPLOY_ROLE_ARN
```

필요하면 이미지 repository 이름과 운영 URL도 Environment Variable로 추가한다. 실제 이름은 workflow와
Terraform이 이를 참조할 때 확정한다.

### [USER ACTION REQUIRED]

**이유:** AWS 계정·결제·예산·관리자 권한과 운영 배포 승인은 계정 소유자가 해야 한다. GitHub Environment의
보호 승인과 secret 최종 입력도 저장소 관리자 작업이다.

**위치:** [AWS Management Console](https://console.aws.amazon.com/)의 `Billing and Cost Management > Budgets`,
`IAM Identity Center` 또는 `IAM`; GitHub 저장소 `Settings > Environments`

**사전 조건:** 월 예산, 알림 수신 주소, AWS Region과 DG-08 결정 완료. Terraform plan 검토 전에는 실제
인프라 apply와 가비아 네임서버 변경을 수행하지 않는다.

**할 일:**

1. `[완료]` 관리자 IAM 사용자에 MFA를 등록하고 `aws login` 임시 자격 증명을 사용한다.
2. `[완료]` `Billing and Cost Management > Budgets`에 월 USD 80과 4개 이메일 알림을 설정한다.
3. `[완료]` Bootstrap Terraform plan과 OIDC trust policy를 검토·적용하고 Production plan을 생성한다.
4. `[완료]` GitHub `Settings > Environments > New environment`에서 `production`을 만들고 `main`만 배포하도록 제한한다.
5. 가능한 플랜이면 Required reviewer와 self-review 방지를 설정한다.
6. `[완료]` 승인된 OIDC IAM role ARN과 Region을 Environment **Variable**로 입력한다. 장기 AWS Access Key는 입력하지 않는다.
7. `[완료]` Route 53 Hosted Zone과 기존 DNS 레코드를 확인한 뒤 가비아 네임서버를 AWS가 할당한 4개 값으로 교체한다.
8. 첫 운영 배포는 Terraform plan, DB migration 순서와 rollback 절차를 확인한 뒤 직접 승인한다.
9. `[완료]` Free Plan 종료 전에 공개 서비스를 계속 운영하려면 `Upgrade plan`을 직접 승인한다. AWS Organizations 또는
   Control Tower 생성은 Credit을 즉시 만료시킬 수 있으므로 이 작업에 사용하지 않는다.

**입력값:** workflow 구현 뒤 `AWS_REGION=ap-northeast-2`, `AWS_DEPLOY_ROLE_ARN`과 ECR repository 이름을
GitHub `production` Environment Variable로 등록한다. Gemini·Kakao key와 RDS password 원문은 GitHub에
복사하지 않고 AWS 런타임 비밀 저장소에 직접 입력한다.

**공유 금지:** AWS root 비밀번호, MFA seed·복구 코드, Access Key, Secret Access Key, session token,
Terraform state 원문

**완료 확인:** AWS 계정 별칭·ID의 전체 값 대신 마지막 4자리, Region, Budget 설정 여부,
`production` Environment 생성 여부와 OIDC role 이름만 알린다.

**다음 검증:** 에이전트가 secret 이름과 workflow 참조, OIDC `sub`·`aud` 제한, Terraform plan과 dry-run을
값 출력 없이 검증한다.

**영향 범위:** DG-08 전에는 운영 인프라·CD만 차단된다. 로컬 개발, CI와 애플리케이션 구현은 계속 가능하다.

공식 근거: [GitHub Actions의 AWS OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws),
[GitHub Environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments),
[AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/create-cost-budget.html)

## 9. Grafana Cloud `[POST-MVP]`

제출 MVP는 Actuator·Micrometer Prometheus endpoint와 JSON 표준 출력까지만 사용한다. Alloy 설치,
Grafana Cloud Metrics·Loki 전송, 대시보드·Alerting과 실제 자격 증명 등록은 Post-MVP 작업이므로 현재
사용자가 설정할 값은 없다. 아래 절차는 해당 작업을 다시 시작할 때만 수행한다.

### [USER ACTION REQUIRED]

**이유:** Grafana Cloud 계정·stack 지역·요금제, telemetry 전송 token과 실제 알림 수신 채널은 사용자가
소유하고 승인해야 한다.

**위치:** [Grafana Cloud Portal](https://grafana.com/auth/sign-in/) → `Add Stack`; stack → `Connections`;
`Security > Access Policies`; Grafana → `Alerts & IRM > Contact points`

**사전 조건:** 운영 AWS Region, telemetry 전송량·보존 요구, 알림 수신 이메일 또는 Slack/Webhook 소유권

**할 일:**

1. MVP는 Free plan의 단일 production stack으로 시작하고, 운영 AWS Region과 가장 가까운 사용 가능 region을 선택한다.
2. `Connections`에서 Hosted Prometheus와 Loki의 endpoint·username을 확인한다. 이는 secret은 아니지만
   stack별 설정으로 관리한다.
3. `Security > Access Policies`에서 production Alloy 전용 policy를 만든다.
4. 직접 구성할 때는 `metrics:write`, `logs:write`만 부여한다. Fleet Management를 채택하면 공식 설정이
   요구하는 `set:alloy-data-write`를 사용한다. read·admin 권한은 telemetry 전송 token에 넣지 않는다.
5. 만료일이 있는 token을 발급하고 운영 비밀 저장소에 직접 입력한다. token은 발급 직후 한 번만 복사할 수 있다.
6. `Alerts & IRM > Contact points`에서 소유한 이메일 또는 승인된 Slack/Webhook 연락처를 등록하고 테스트 알림을 보낸다.
7. `Cost Management and Billing > Usage`에서 Metrics·Logs 사용량과 plan 한도를 확인한다.

**입력값:** 정확한 환경 변수 이름은 Alloy 설정과 AWS 비밀 저장소가 구현될 때 확정한다. GitHub Secret이나
`.env.local`에 미리 입력하지 않는다.

**공유 금지:** Access Policy Token, Slack/Webhook URL, contact point 인증 값

**완료 확인:** stack slug, region, Free/유료 plan, policy 이름·scope, 연락 채널 종류와 테스트 알림 성공 여부만
알린다. token과 실제 연락처 주소는 알리지 않는다.

**다음 검증:** 에이전트가 token 존재 여부, 최소 scope, Alloy의 Metrics·Loki 수집 상태와 테스트 알림 결과를
값 출력 없이 검증한다.

**영향 범위:** 제출 MVP에는 영향이 없다. Post-MVP의 실제 원격 수집·대시보드·알림 검증만 차단된다.

공식 근거: [Stack 생성](https://grafana.com/docs/grafana-cloud/platform/security-and-account-management/account-management/cloud-stacks/create-update-stacks/),
[Cloud Access Policy 용도](https://grafana.com/docs/grafana-cloud/platform/security-and-account-management/security-and-access/authentication-and-permissions/),
[Alloy Metrics 전송](https://grafana.com/docs/grafana-cloud/observe-and-act/send-data/alloy/collect/prometheus-metrics/),
[로그 전송](https://grafana.com/docs/grafana-cloud/observe-and-act/send-data/logs/collect-logs-with-alloy/),
[알림 연락처](https://grafana.com/docs/grafana-cloud/alerting-and-irm/alerting/alerting-rules/create-notification-policy/)

## 10. 비밀값 없는 완료 신호

사용자는 다음 형식으로 상태만 전달한다.

```text
Gemini: auth key / Paid / 로컬 등록 완료
Google OAuth: Testing / client 생성 완료 / scope 이름 / Redirect URI 목록
Kakao OAuth: OIDC ON / 앱 생성 완료 / 동의 항목 ID / Redirect URI
GitHub/AWS: production Environment 생성 / OIDC role 이름 / Region / Budget 설정 완료
Grafana `[POST-MVP]`: stack slug / region / plan / policy scope / 테스트 알림 성공
```

값이 등록됐다는 문장만으로는 외부 연동을 완료 처리하지 않는다. 에이전트가 해당 코드·workflow를 준비한 뒤
존재 여부와 합성 요청 성공을 값 출력 없이 검증해야 각 `[USER]` 체크포인트를 완료로 바꾼다.
