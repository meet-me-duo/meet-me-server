# #107 main 전 남은 외부 작업 안내

> 2026-10-18 모델 후속은 [Decisions 2단계 계약](ISSUE_107_TWO_STAGE_CONTRACT.md)·[평가와 키 준비](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md)를 따른다. 아래 단일 Gemini30개 안내는 이전 이력이며 새 평가 호출 수/비용 승인이 아니다. 공개 OAuth 설정 완료와 기존 인증/DB 운영 경계는 계속 유지한다.

2026-10-17 UTC. 실행하지 않은 준비안이다. production 접근·OAuth 실로그인·Secret 생성/조회/입력·DB 변경·main 병합·배포 승인이 아니다. 로컬 합성 RSA/OIDC/provider·PG 검증은 실제 운영 완료 증거가 아니다.

## 완료 이력

최신 부모 인계에 따라 **Google·Kakao 공개 앱 설정은 완료로 유지**한다. 기존 기록의 Google 새 Secret 발급·안전 보관/이전 값 폐기와 Kakao callback 저장 완료도 유지한다. 공개 설정을 다시 만들거나 토글을 반복하도록 요구하지 않는다. 서버 자격 증명 전달·운영 RS256/JWKS 파일·DB/복구 상태·실로그인은 별개로 미확인이다. 이전 [OAuth 문서](ISSUE_109_OAUTH_SETUP.md)의 초기 미설정 목록은 과거 이력이다.

## 인증 런타임 [AGENT → USER → SHARED]

**저장소 준비:** 새 `runtime-auth-mode=oauth-rs256-required` 릴리스의 renderer가 아래 typed Parameter를 소비하고 Compose가 RSA/JWKS를 read-only bind한다. marker 없는 과거 릴리스는 auth 조회0으로 보존한다. 새 marker·renderer·Compose는 승인 guard catalog17에 묶이며 기존14 승인을 재사용하지 않는다. 합성 focused 검증과 실제 운영 준비는 별개이고 최종 전체 gate/리뷰/게시 근거는 Worklog에서 확인한다.

| 런타임 변수 | Parameter 경로(`/meet-me/production` 아래) | 형식 |
| --- | --- | --- |
| GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET | `/secret/google-client-id`, `/secret/google-client-secret` | SecureString, 기존 Web OAuth client |
| KAKAO_CLIENT_ID / KAKAO_CLIENT_SECRET | `/secret/kakao-client-id`, `/secret/kakao-client-secret` | SecureString, 기존 REST API key/활성 secret. Admin/JS key 금지 |
| GOOGLE_REDIRECT_URI | `/config/google-redirect-uri` | String, `https://api.meet-me.co.kr/api/auth/google/callback` |
| KAKAO_REDIRECT_URI | `/config/kakao-redirect-uri` | String, `https://api.meet-me.co.kr/api/auth/kakao/callback` |
| AUTH_SIGNING_KEY_ID | `/config/auth-signing-key-id` | String, 고유 kid |
| AUTH_ISSUER / AUTH_AUDIENCE | `/config/auth-issuer`, `/config/auth-audience` | String, `https://api.meet-me.co.kr` / `meetme-web` |
| AUTH_WEB_CALLBACK_URI | `/config/auth-web-callback-uri` | String, `https://app.meet-me.co.kr/auth/callback` |
| APP_ALLOWED_ORIGINS | 기존 `/config/allowed-origins` | String, 정확한 `https://app.meet-me.co.kr`; wildcard 금지 |
| AUTH_SIGNING_PRIVATE_KEY_PATH / AUTH_VERIFICATION_JWKS_PATH | 파일 본문을 env 단일행 값으로 전달하지 않음 | 아래 read-only 컨테이너 파일 경로 |
| MEETME_PRECHECK_PROVIDER_ENABLED | `/config/precheck-provider-enabled` | optional String, 정확한 `true`/`false`만 허용. 미등록/조회 실패는 false, 성공 응답이 malformed이면 전체 env 교체 거부. 현재 기본 OFF·실제 등록/활성화 미승인 |

**[AGENT] 준비한 계약:** AWS 원본 JSON을 Bash에 담기 전에 raw/escaped NUL·개행·Type·값을 검증하고 전부 성공한 뒤 0600 env를 원자 교체한다. 값은 출력하지 않으며 목적지/조상 symlink·실패 시 이전 env 보존·기존 credential refresh·read-only mount/UID10001을 합성 값으로 검증한다. required의 기본 destination은 renderer의 canonical release 디렉터리다. 기존 Kotlin의 부분 signing 시작 거부/전부 없음 AUTH_UNAVAILABLE 정책은 유지하고 임시 키·익명 HOST fallback은 없다. entrypoint·migration·Dockerfile·IAM 정책은 변경하지 않았다. `infra/production/runtime.tf`에는 기존 parameter prefix 읽기 정책이 있으나 실제 적용/권한 성공은 미확인이다.

**[USER ACTION REQUIRED — 검증된 코드의 운영 적용을 별도 승인한 뒤]** 이유는 사용자 소유 자격 증명을 안전하게 실행 환경에 넣기 위해서다. 기존 앱 공개 설정을 반복하는 작업이 아니다. 사전 조건은 기존 AWS 계정·서울 리전 및 합의된 비밀관리 경로 접근 권한이다. 지금 생성/입력하거나 실제 SSM을 조회하지 않는다.

1. [Systems Manager](https://console.aws.amazon.com/systems-manager/) → Parameter Store, `ap-northeast-2`에서 위 이름에 기존 자격 증명을 직접 등록한다. UI에서 Parameter Store가 별도 서비스로 보이면 해당 메뉴를 사용한다. 새 IAM/KMS 권한이 필요하면 자동 확대하지 않는다. AI optional flag는 미등록/OFF로 유지하며 비용 정책·실제 평가 승인을 받기 전 true로 등록하지 않는다.
2. 서비스 JWT용 RSA PKCS#8 개인키와 **public RSA JWK Set**을 승인된 보안 경로에 준비한다. 기존 안전한 키가 있으면 재발급하지 않는다. JWKS의 `keys`에는 공개 RSA `kty,n,e,kid`가 있어야 하고 private `d,p,q,dp,dq,qi`는 없어야 한다. 현재 kid와 서명 개인키의 공개 부분이 일치해야 한다. 실제 회전/폐기는 이번 준비에서 실행하지 않는다.
3. mount는 호스트 `${AUTH_KEYS_DIRECTORY:-/opt/meet-me/auth}` → 컨테이너 `/run/meetme-auth/:ro`다. `signing-private.pem`≤16KiB, `verification.jwks.json`≤64KiB의 컨테이너 경로를 renderer가 고정 출력한다. 없는 호스트 디렉터리는 자동 생성하지 않는다. 실제 UID/GID10001에 필요한 읽기·탐색 권한만 부여한다. 실제 파일·소유권은 운영 승인 뒤 준비하며 지금 실행하지 않는다.
4. 완료 신호는 환경명, Parameter/변수 **이름과 configured 여부**, 파일 존재·read-only·권한 검사 통과 여부다. Client Secret/REST API key/개인키·토큰·쿠키·code/state·DB 암호를 채팅·저장소·로그·스크린샷에 보내지 않는다.

**[SHARED] 후속:** 값 비출력 설정 검사 → 격리 회귀 → 별도 승인한 사용자 계정으로 Google/Kakao 실제 로그인·취소·재로그인 → callback/Origin·Secure HttpOnly cookie·RS256 kid/issuer/audience·다른 계정/기기 소유권·archive를 확인한다. 등록 완료를 실로그인 성공으로 표시하지 않는다. 이 작업은 실제 인증 활성화만 막으며 합성 검증·문서는 계속 가능하다.

## 운영 DB·전환·복구 [USER/SHARED]

**지금 할 외부 작업은 없다.** 정확 대상·중단 창·복구 비용을 보고하고 별도 승인을 받기 전 운영 조회·snapshot/PITR·SSM dispatch를 실행하지 않는다. #99의 과거 운영 승인을 이번 실행에 전용하지 않는다.

1. 승인 후 기존 검토된 read-only preflight/host reader로 instance/image digest·guard/phase·Flyway version/script/checksum/success·credential refresh rule/대기 명령 **메타데이터만** 확인한다. DB 암호·전체 env·Secret 값·원시 SSM 출력은 수집하지 않는다. 저장소 V1~V7 기록을 현재 운영 history로 간주하지 않는다. 현재 main의 Deploy workflow를 조회용으로 실행하지 않는다.
2. [RDS Console](https://console.aws.amazon.com/rds/) 서울 → Automated backups에서 대상·보관기간·earliest/latest restorable time과 UTC를 확인한다. 복구 검증은 별도 비용 승인 뒤 **새 private DB instance**로 복원한다. 기존 DB를 덮어쓰거나 즉시 endpoint를 바꾸지 않는다. VPC/subnet/security group/parameter group·암호화 접근을 확인한다. [공식 PITR 절차](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIT.html).
3. 유지보수 직전 snapshot을 선택한 경우 RDS → Snapshots → Take snapshot → 확인한 instance·고유 snapshot 이름 → **Available/생성 시각**을 확인한다. Single-AZ snapshot은 짧은 I/O 정지를 유발할 수 있고 수동 snapshot은 자동 만료되지 않으므로 중단·보관 비용을 승인에 포함한다. [공식 snapshot 절차](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreateSnapshot.html). 생성 성공만으로 복구 rehearsal 성공을 선언하지 않는다.
4. 정확 publisher run/attempt·source SHA·ECR digest·tar/guard fingerprint·V1~V16 원본 catalog와 실제 history의 허용 prefix를 맞춘다. 체크섬 불일치·알 수 없는/실패 migration·충돌 V9·불명확한 phase이면 중단한다. repair/SQL 수정/phase 초기화0, V1~V15 변경0이다.
5. 실제 유지보수 시작 승인 후 EventBridge → Rules의 기존 두 credential refresh rule을 검토된 절차로 중지·drain하고 SSM Run Command 활성 작업0·구 writer/worker/relay/retention 정지를 확인한다. rule 이름/instance ID는 승인된 읽기 결과에서 얻으며 추측해 생성·중지하지 않는다. 기존 host launcher/guard 경로만 사용한다.
6. 승인된 이미지 migration → 앱 기동 → health/OpenAPI·실제 V16 history·READY·동일 digest·새 인증/proof/check를 확인한다. breaking marker 뒤 구 이미지 자동/수동 rollback은 하지 않는다. 실패 시 쓰기 중지 상태를 보존하고 호환 digest의 roll-forward를 우선 검토한다. PITR endpoint 전환은 데이터 유실 시점·비용을 보고한 뒤 별도 승인으로만 수행한다. Flyway 자동 downgrade는 없다.

완료 신호는 대상 식별 메타데이터·backup/PITR 가능 시각/복구 rehearsal 결과·history/catalog 지문·정확 digest/workflow/READY/health다. 운영 데이터·DB URL 자격 증명·backup 본문은 공유하지 않는다.

## 선택 기능과 최소 사용자 순서

운영자 요약 `OperatorQueryProperties.enabled=false`와 빈 allowlist를 유지한다. 운영자 UUID 등록·권한 변경·활성화는 core login/room/precheck의 선행 조건이 아니다. `/api/operations` 거부는 정상이다. 기존 실제 CurrentAccountPort·명시 allowlist·조회 전 인가·감사 실패 시 보호 정보 미반환 정책을 유지한다. OFF는 감사 schema·Flyway를 제거하는 설정이 아니며 `cleanupEnabled=true`의 감사 정리는 별도다.

1. 에이전트의 코드·독립 검증 결과를 확인하고 **모임 총 AI10원 정책과 새 호출 예산의 충돌**을 부모가 결정한다. 기본 OFF 활성화·인증 전달 경로의 코드 준비를 사용자 키 입력 탓으로 남기지 않는다. count 사전 제한은 실제 billed input hard cap 증명이 아니므로 [평가 준비안](ISSUE_107_REAL_MODEL_EVALUATION_PLAN.md)의 남은 과금 근거도 확인한다.
2. 입증된 최대 비용을 보고받은 뒤 **30개 실제 평가 승인**. 준비된 경로로 기존 OAuth 자격 증명·RS256/JWKS를 안전하게 전달하고 별도 실로그인 검증을 승인한다. 공개 앱 설정은 반복하지 않는다.
3. 실제 준비 결과 뒤 **운영 읽기·복구/유지보수·정확 전환 승인**. main 자동 배포 때문에 준비 전 main을 먼저 병합하지 않는다. 서버 호환성을 확인한 뒤 웹 main을 진행한다.

실제 모델·런타임 인증·DB/host 전환 중 미완료가 있으면 main 보류다. 운영자 요약 활성화는 이 목록에 포함하지 않는다.
