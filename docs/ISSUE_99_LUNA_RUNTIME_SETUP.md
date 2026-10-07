# Issue #99 Luna 운영키 전달 계약

## 코드 경로

서버 저장소는 `meet-me-duo/meet-me-server`, 운영 환경은 `production`, 기본 AWS 리전은 `ap-northeast-2`다. 키는 기존 Gemini 패턴을 따라 SSM Parameter Store의 `/meet-me/production/secret/openai-api-key` SecureString에서 읽어 `OPENAI_API_KEY`로 전달한다. GitHub Secrets에 같은 이름을 추가해도 이 경로에서는 사용하지 않는다. DB 자격증명은 기존 RDS 관리 Secrets Manager에 그대로 둔다.

새 배포 tar는 `deploy/runtime-provider-mode`의 `gemini-luna-required`를 포함한다. `render-runtime-env.sh`는 자신의 release 파일을 읽으므로 현재 작업 디렉터리나 목적지의 다른 mode 파일로 설정이 바뀌지 않는다. 파일은 정확한 enum 또는 끝의 LF 하나만 허용하고 잘못된 파일·symlink·읽기 불가를 거부한다. 기존 mode 파일이 없는 release/명시 `gemini-only`는 OpenAI를 조회하지 않는다.

required mode는 SSM JSON의 Type이 SecureString이고 Value가 비어 있지 않은 ASCII33~126 문자열인지 확인한 뒤 값을 추출한다. 공백·줄바꿈·NUL·제어문자를 거부하며 `$`, quote, backslash, `#` 등은 데이터로 보존한다. 실패할 때 OpenAI 값·AWS 오류 본문을 출력하지 않으며 기존 `.env.runtime`을 유지한다. 모든 조회·검증이 끝난 후 같은 디렉터리의0600 임시 파일을 rename하여 교체한다. 새 환경에는 `MEETME_RUNTIME_PROVIDER_MODE`와 required mode의 `OPENAI_API_KEY`가 들어간다.

Compose의 기존 raw env_file 계약으로 변수 두 개를 전달한다. container entrypoint는 required mode에서 server/migrate 및 다른 command를 실행하기 전에 키를 같은 문자 계약으로 확인한다. 키가 없는 상태를 운영 fallback 준비로 취급하지 않는다. credential refresh는 현재 release의 같은 renderer/mode를 재사용하므로 새 코드 배포 이후 DB 비밀번호 갱신 때도 Luna 요구가 유지된다. 기존 release의 Gemini 단독 동작은 유지한다.

## 사용자 등록 보고와 확인 — 운영 검증 미완료

사용자는 AWS에 키를 직접 등록했고 본인 PowerShell의 기존 배포 프로필 로그인도 완료했다고 보고했다. 선택 실행환경에서는 그 프로필에 접근할 수 없으며 parameter Name/Type/Version·실제 권한·전달/인증은 아직 확인하지 않았다. 등록 보고와 검증 완료를 구분한다.

- `[USER]` 기존 운영 AWS 계정과 서울리전에서 위 정확한 이름의 SecureString을 직접 등록한다. 기존 Gemini와 같은 AWS 관리 SSM KMS 키를 사용한다. 다른 customer managed key/권한/유료 tier가 필요한 경우 자동 확대하지 않고 별도 보고한다. 실제 OpenAI 키는 사용자 본인이 생성·입력하며 시험환경에서 복사하지 않는다.
- `[USER]` 공유할 완료 신호는 `등록 완료`, parameter 이름·Type·Version 같은 값 없는 metadata다. 실제 키·환경 파일·전체 AWS 출력은 채팅/Git/Issue/PR/로그에 넣지 않는다.
- `[AGENT]` 코드상 runtime IAM의 `ssm:GetParameter` 등 리소스 `/meet-me/production/*`는 새 이름을 포함한다. 이번 변경은 출력 이름만 추가하며 새 IAM/KMS policy·secret resource·값을 만들지 않는다. 실제 적용 권한과 정책 deny는 미확정이다.
- `[SHARED]` 실제 운영 시점에 parameter 타입/권한, 값 없는 앱 전달 신호, 공급자 인증 및 동일 image digest/guard/drain 조건을 별도로 확인한다. 모의 검증은 실제 키의 유효성이나 운영 준비 완료를 증명하지 않는다.

AWS 기준은 [Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)와 [SecureString KMS](https://docs.aws.amazon.com/systems-manager/latest/userguide/secure-string-parameter-kms-encryption.html)를 따른다. 현재 구현은 기존 AWS 관리 키 패턴을 확장하며 계정의 실제 정책을 변경하지 않는다.

## 평가와 운영 경계

실제 모델/웹 공동 평가의 고정 SHA는 서버6f7f54e·웹8e56fe0이며 [평가 기록](ISSUE_99_LIVE_EVALUATION.md)을 따른다. 이 후속은 배포 renderer/entrypoint·공개 mode·참조·검증만 변경하고 앱 Kotlin·시간 배분·SQL·공개 API는 바꾸지 않는다. 새 배포 이미지의 entrypoint 바이트는 달라지므로 제품 앱 소스가 같다는 이유로 이전 image digest를 재사용하지 않는다.

운영키 코드82b8d2a의 CI37614973315와 Infrastructure37614973327은 성공했다. 이후 ARM64 이미지 검증은 AWS 권한 없는 CI에서 새 Dockerfile build/load와 격리된 entrypoint/JRE 검사로 진행한다. 합성 키만 쓰고 앱·migration·공급자 요청은 시작하지 않는다. 로컬 config image ID는 ECR manifest digest 승인이나 운영 전달 증거가 아니다.

읽기 preflight37610286680·37614973475는 GitHub production 환경이 PR merge ref를 거절하여 runner/step 실행0이었다. 기존 성공 배포 로그의 대상은 사용자가 조회한 대상과 일치하지만 현재 환경 변수 직접 읽기는 차단됐고 실제 host/Flyway/key metadata 조회는 미완료다. 이 코드 작업은 실제 AWS 조회·키 값 작업·운영 중단·자동화 중지·main/develop 병합을 수행하지 않는다. 시작 조건과 실행 인계는 [운영 preflight 인계](ISSUE_99_PRODUCTION_PREFLIGHT_HANDOFF.md)를 따른다.
