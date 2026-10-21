# #107 인증 런타임 전달 — 독립 검증 범위

2026-10-17 UTC. 사용자 승인 범위는 실제 설정 전에 할 제품 코드 준비다. 실제 AWS/SSM·운영 호스트·실제 키·OAuth·모델 호출은 하지 않는다. 테스트 작성자는 제품 renderer/Compose/entrypoint/catalog를 수정하지 않는다. 게시·CI·develop은 부모 담당이다.

## 확정 계약과 독립 oracle

- 새 release는 자체 `runtime-auth-mode=oauth-rs256-required`를 선언한다. 선언 없음은 기존 release로서 추가 auth/toggle 조회·변수 없이 기존 동작을 보존한다. 빈 값·알 수 없는 값·여러 줄·CR/LF/NUL·symlink·nonregular·읽기 권한 없는 선언은 실패한다.
- required는 Google/Kakao ID·secret 4개 SecureString과 redirect 2개·kid/issuer/audience/web callback 4개 String, 기존 allowed origins String을 모두 JSON으로 읽고 검증한다. ASCII graph 한 줄 값을 변환하지 않는다. 하나라도 실패하면 이전 env bytes/0600 유지·임시 파일 정리·값과 원시 AWS 진단 비출력이다.
- 컨테이너 개인키/JWKS 경로는 `/run/meetme-auth/signing-private.pem`, `/run/meetme-auth/verification.jwks.json`이다. required의 기본 env 목적지는 canonical renderer release이고 legacy의 기존 `/opt/meet-me/current/.env.runtime` 기본은 유지한다. 명시 목적지의 parent/ancestor symlink도 거부한다.
- optional `/config/precheck-provider-enabled`는 lookup 실패/미등록이면 `MEETME_PRECHECK_PROVIDER_ENABLED=false`, 존재하면 String exact `true`/`false`만 허용한다. 존재하지만 잘못된 JSON/type/값은 원자적으로 실패한다. legacy는 조회/변수0이다. 실제 설정 활성화 승인은 아니다.
- production Compose는 `${AUTH_KEYS_DIRECTORY:-/opt/meet-me/auth}`를 `/run/meetme-auth`에 long bind로 연결하고 `read_only=true`, `bind.create_host_path=false`를 사용한다. 기존 Dockerfile UID/GID10001을 보존한다. entrypoint/migrate는 변경하지 않으며 기존 Kotlin partial-signing 실패·all-none AUTH_UNAVAILABLE 정책을 보존한다.
- 기존 설치 READY host guard의 실제 refresh 경로에 실제 renderer를 연결해 DB 암호 변경 시 모든 auth·파일 경로·activation 값을 유지하는지, 누락 auth 시 env 보존·app recreate0인지 확인한다. AWS/Docker/root owner는 기존 logical-host fixture의 합성 대역이다. 실제 Bash/flock/file은 사용하지만 실제 host 설치 증거가 아니다.

## 파일과 초기 RED

| 파일 | 검사 수 | 범위 |
| --- | ---: | --- |
| `deploy/tests/runtime-auth-env.test.cjs` | 44 | typed auth·safe raw bytes·legacy/default/ancestor·원자 파일·optional toggle·실제 복사 guard refresh3 |
| `deploy/tests/runtime_auth_refresh_fixture.py` | CJS 하위 fixture | 기존 Sandbox의 실제 guard와 renderer, synthetic outbound만 결합 |
| `deploy/tests/runtime-auth-compose.test.cjs` | 6 | 실제 로컬 Docker/Compose·캐시 Alpine·UID10001·read-only·missing bind·opaque raw env·CI 계약 |
| `deploy/tests/runtime-auth-raw-json.test.cjs` | 4 | AWS stdout의 literal NUL 원본 bytes가 secret/toggle/type로 정상화되는 현상 거부 |

현재 지문의 제품 변경 전 chronological RED는 **50개 중 43 assertion 실패, control 7 PASS**, setup/timeout 오류0이다. renderer44는 실패40/PASS4, Compose6은 실패3/PASS3이다. 전체 원본은 ignored `.codex/tdd-evidence/issue107-runtime-auth{-compose}-red.log`에 있다. 간결한 지문·실패명·명령은 `.tdd/verification/issue107-runtime-auth-{env,compose}-red.json`에 보존한다. Node/Python/Compose 증거이므로 Kotlin 전용 `.tdd/red` JSON Schema에 가장하지 않는다.

부모의 추가 독립 검토에서 raw AWS JSON을 먼저 Bash 변수에 담으면 literal NUL 바이트가 제거될 수 있음을 확인했다. 기존50의 JSON-escaped NUL 값 검사와 구분해 `deploy/tests/runtime-auth-raw-json.test.cjs` 4개를 별도로 추가했다. 첫 auth 구현에서 required secret string·optional `tru[NUL]e`·Parameter Type `Str[NUL]ing`·JSON 뒤 NUL 응답이 모두 성공으로 잘못 변형되는 **4/4 유효 assertion RED**를 확인했다. 이 요구는 기존 malformed/NUL 거부의 구체화이며 기존50의 bytes/기대값을 변경하지 않았다. 별도 chronology는 `.tdd/verification/issue107-runtime-auth-raw-json-red.json`, 원본은 ignored `.codex/tdd-evidence/issue107-runtime-auth-raw-json-red.log`다. 원본 바이트를 Bash 변수에 넣기 전에 검증하도록 제품을 보완한 뒤 동일4개 GREEN을 확인했다.

추가 default/ancestor/toggle 합의와 AC02 host inspection의 synthetic ownership 복원 준비 보정은 최초50의 지문 동결 전에 반영하고 RED를 다시 수집했다. 이전 중간 파일의 RED 지문을 재사용하지 않았다. 이후 첫 GREEN의49/50 중 Compose AC01은 Docker Compose JSON normalization이 explicitfalse 필드를 생략하는 표현을 과하게 제한한 test 결함으로 판정했다. source YAML의 명시false·실제 missing bind 거부/host directory0·실제 read-only/UID 기대값을 모두 유지하고 JSON true만 거부하도록 보정했다. 원본6개/SHA/RED는 `issue107-runtime-auth-compose-original-red.json`에 보존했다. 새 Compose 지문은 baseline21a82f6 Compose를 사용하는 scratch에서 동일 bytes6개로 **3 요구 assertion RED/3 control PASS**를 재구성하고 현재 제품에서 **6/6 GREEN**했다. 새 지문의 원초 chronological RED는 주장하지 않는다. renderer44·refresh helper·rawNUL4 지문은 그대로다. 기본 경로 검사는 역사적 `/opt/meet-me/current/.env.runtime` **문자열만** temporary logical-host 경로로 매핑하며 branch/command/required release 경로를 바꾸지 않는다. 실제 `/opt`에 쓰지 않는다.

기존 `verify-compose-secret-fidelity.sh`는 새로운 필수 bind에 맞춰 synthetic temporary auth directory와 source override만 준비했다. 기존 DB/Luna expected 문자열과 assertions는 그대로다. 이 정렬은 실제 개인키를 생성하거나 검증하지 않는다.

## 완료 조건과 한계

최종54/54 GREEN 이후 고정된 테스트 지문으로 별도 scratch에서 atomic publication·symlink·secret masking·read-only mount·optional activation·refresh 관련 결함을 주입하고 복원한다. 최종 제품 작업트리는 결함 주입에 사용하지 않는다. catalog14→17과 기존 golden 보존은 별도 독립 담당의 검증이다.

최종 **54/54 GREEN**을 원본 로그와 test/source 지문으로 확인했다. scratch의 6 결함은 모두 해당 요구 assertion으로 탐지했고, 매번 정확 bytes 복원 뒤 같은 scoped oracle이 GREEN이었다. 원본 제품/테스트는 바꾸지 않았고 마지막 source7/frozen test4 대조도 일치했다. FI runner는 `issue107-runtime-auth-faults.mjs`, 결과는 `.tdd/verification/issue107-runtime-auth-faults.json`, 종합 근거는 `.tdd/verification/issue107-runtime-auth-independent-final.json`이다. 기존 Compose secret fidelity도 합성 authdir 준비만 추가한 상태에서 독립 PASS했다. 부모 전체 게이트·최종 리뷰·정확 HEAD CI·develop 통합은 별도 완료 항목이다.

이 검사는 실제 운영 Parameter 존재/권한·RSA/JWKS 일치·OAuth 실로그인·운영 UID 권한·guard 승인·배포 준비 완료를 증명하지 않는다. 실제 파일 읽기는 synthetic bytes를 사용하는 로컬 Alpine 검증이다. real key provisioning·실제 설정·운영 접근은 후속 사용자/공동 작업이다.
