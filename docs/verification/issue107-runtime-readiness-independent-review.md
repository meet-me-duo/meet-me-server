# #107 runtime readiness 독립 최종 검토

2026-10-17 UTC. 검토자는 제품·테스트 작성과 분리됐다. 대상은 `fix/issue-107-runtime-readiness`, 기준 HEAD `21a82f6b7a730172e5e88b4f639d82120a16e5a0` 이후의 인증 전달·기본 OFF 개인 검사 provider 준비다. 이 파일 외 소스/테스트 변경, branch/stage/commit/push, 실제 AWS/Secret/OAuth/모델/운영 접근은 하지 않았다.

## 현재 판정

**로컬 게시 검토 PASS. 미해결 P1/P2 없음.** 인증·catalog·AI provider의 고정 소스·독립 테스트·RED/FI·필수 전체 게이트와 최종 문서를 확인했다. 기존 사용자 승인 범위의 commit·push·Draft PR·정확 HEAD CI 단계로 진행할 수 있다. 원격 정확 HEAD CI와 develop merge CI는 아직 이후 단계의 완료 근거이며 이 로컬 판정으로 대체하지 않는다. 운영 활성화·유료 평가·main·배포 승인이 아니다.

## 인증·catalog 확인

- AGENTS, architecture/documentation/ADR/development/API/user-intervention 규칙과 project-architecture 스킬, 현재 runbook/evaluation plan을 대조했다.
- 새 release-local `runtime-auth-mode`는 정확한 `oauth-rs256-required`와 선택적 LF만 허용한다. legacy missing marker는 auth/toggle 추가 조회·변수 없이 기존 경로를 유지한다. 새 required 기본 목적지는 renderer의 canonical release이며 명시 목적지/조상 symlink를 거부한다.
- 신규 AUTH는 AWS 원시 stdout을 Bash에 담기 전에 JSON/Type/ASCII graph·CR/LF/NUL을 검증한다. optional toggle은 exact String true/false, lookup 실패 OFF, malformed successful response는 env 교체 실패다. 비밀값/원시 AWS 진단을 출력하지 않고 모든 수집 뒤 같은 디렉터리0600 임시 파일을 원자 rename한다. 실패 시 기존 env를 유지한다. 기존 DB/provider text helper의 전면 개선은 이번 변경 범위가 아니다.
- Compose bind는 read-only와 `create_host_path:false`, 고정 컨테이너 경로를 사용한다. 실제 UID10001/소유권·쓰기 거부/없는 host path는 로컬 cached Alpine의 합성 파일로 검증했다. 실제 RSA/JWKS 일치·운영 파일 권한 검증으로 확대하지 않는다. 기존 host guard refresh가 같은 release renderer를 사용하며 auth 누락 시 app recreate0인 논리 host 검증을 확인했다.
- publisher의 신규17 파일과 runner/host의 markerless 역사14·신규17 계약이 분리됐다. marker 존재 시14 downgrade/unknown marker/임의 subset·superset을 허용하지 않는다. marker·renderer·production Compose가 같은 guardFingerprint에 결속돼 기존 승인을 재사용하지 못한다. 기존 migration16개/checksum/history·명시 승인·두 refresh rules/pending guard는 유지한다.
- CI가 신규 auth 세 파일·catalog 계약을 실행하고 배포 archive에 auth marker를 포함한다. quality timeout30분은 유한하며 전체 정확 HEAD CI 성공은 아직 별도 확인 대상이다.

## 확인한 근거

| 범위 | 근거 | 판정/한계 |
| --- | --- | --- |
| Auth 계약 | `.tdd/verification/issue107-runtime-auth-independent-final.json` | 54/54, source7+test4 현재 SHA 일치 직접 확인 |
| Auth TDD | `issue107-runtime-auth-{env,raw-json,compose}-red.json` | env44의 chronological RED40/control4, rawJSON4의 별도 genuine RED4. Compose 원본RED와 current bytes 재구성RED를 구분 |
| Auth FI | `.tdd/verification/issue107-runtime-auth-faults.json` 및 `issue107-runtime-auth-faults-canonical.json` | scratch6/6 유효 assertion 탐지·정확 복원·scoped GREEN. canonical7필드/current source7·test4 SHA 직접 확인. 제품 트리 주입0 |
| Catalog | `.tdd/verification/issue107-runtime-auth-catalog-green.json` | 고정26/26, test/helper3+source3 SHA 현재 일치 직접 확인 |
| Catalog FI | `issue107-runtime-auth-catalog-faults-canonical.json` 및 상세 faults JSON | scratch3/3·각 복원26 GREEN. 특권 host filesystem/unit transport fixture 대역은 한계로 기록 |

## AI provider 확인

- 실제 `PrecheckProviderConfiguration` bean이 별도 기본 false 속성으로 runtime factory를 생성한다. arbitrary verified/profile/endpoint 환경값은 이를 활성화하지 못한다. `GeminiProperties`의 기존 init 불변식이 `gemini-3.8-flash`를 고정한다. synthetic legacy verified 옵션은 literal loopback HTTP만 허용하며 외부 endpoint 우회는 차단된다.
- runtime 요청은 candidate1·총출력2048·LOW, thinkingBudget 없음이다. 생성 입력을 한 번 직렬화하고 count wrapper에 동일 tree+model을 넣는다. 사전 count1..4096을 통과할 때만 generation 최대1회다. count 누락/비정수/중복 JSON/초과/실패는 generation을 허용하지 않는다. 입력/본문16KiB와 request cap·500 codepoint도 보존한다.
- count/generation은 하나의 monotonic min(request.timeout,15초) deadline을 사용한다. redirect/connection retry를 끄고 HTTP non200은 OkHttp follow-up 전에 차단한다. HTTP/읽기/파싱 뒤 남은 예산을 확인한다. 기존 durable application admission6·질문3·명시retry1·최종 whole-room 분석과 경합 fencing은 바꾸지 않았다. admission6은 count6+generation6까지 가능하며 총HTTP6으로 표현하지 않는다.
- generation 응답의 반환 usage를 구조 검증과 분리해 기록하므로 cap/finish/verdict 거부 시 알려진 항목을 잃지 않는다. malformed/누락/transport 실패는 항목별 unknown이며 count 값으로 보충하지 않는다. 고정 operation/kind만 meter tag로 사용하고 원문·사용자 ID·질문·credential은 넣지 않는다. generation prompt usage>4096은 사후 거부이므로 이미 발생한 원격 계산/청구를 차단한 증거가 아니다.
- `.tdd/red/issue-107-runtime-input-gate.json`과 독립 history에서 유효 실행61/요구 assertion59실패/control2, earlier compile-only failure 제외를 확인했다. `.tdd/verification/issue-107-runtime-input-gate-green.json`의 집중79(신규61+legacy18)/실패0·skip0, source5/test5 및 보관 XML5 SHA를 현재 bytes와 직접 대조했다.
- `.tdd/verification/issue-107-runtime-input-gate-fi.json`·history manifest에서 FI6개/요구 assertion 실패7/errors0, baseline79·정확 복원79 GREEN을 확인했다. current source6/test5 SHA와 mutation별 restored SHA도 직접 일치 확인했다. FI는 테스트 작성자가 수행했고 이 검토가 별도로 최종 제품 소스를 대조했다.

## 전체 게이트와 최종 기록

- `.codex/tdd-evidence/issue107-runtime-readiness-node-full-jdk-green.log`: Node140/140·실패0·skip0를 직접 확인했다. 앞선 JDK 경로 준비 실패를 이 실행의 제품 RED나 성공으로 합산하지 않는다.
- `.codex/tdd-evidence/issue107-runtime-readiness-v8-full.log`: V8 guard37/37·453.852초·OK를 직접 확인했다. 새 합성 auth mount를 준비한 기존 Compose secret fidelity와 legacy refresh의 성공은 auth 근거와 별도다.
- 계정·precheck application·SQL·Dockerfile·entrypoint·`.codex/hooks/tdd_guard.py`·`.agents`는 기준 HEAD 대비 변경이 없음을 확인했다. migration 전용 main이 Spring auth key bean을 기동하지 않는 기존 경계도 유지한다.
- `.tdd/verification/issue107-runtime-readiness-final-gate.json`: 변경 없는 필수 guard exit0(hook self-tests·ktlintCheck·assemble·test). 실제 `build/test-results/test/TEST-*.xml`179개를 직접 재집계해 tests1258/failure0/error0/기존skip4, PASS1254를 확인했다. XML mtime은 모두 해당 guard 시작 이후다. cached 과거 실행을 새 전체 실행으로 집계하지 않았다.
- tracked `issue107-runtime-readiness-source-manifest.json`을 실제 시작 전 manifest 및 현재491개 bytes와 직접 대조해 모두 일치함을 확인했다. source-manifest SHA와 final-gate 요약도 일치한다. 최종 review는 소스/테스트 지문을 변경하지 않았다.
- `.tdd/verification/issue107-runtime-readiness-supplemental.json`의 모든 초기 실패/성공 원본 로그 SHA를 직접 대조했다. Nginx 재실행은 public config workspace 권한만0644·빈 private Docker config를 사용한 동일 스크립트 exit0이며 quiet log는0bytes다. 초기 permission 오류 로그와 이를 분리했고 실제 운영 TLS/권한 완료로 주장하지 않는다.
- MAIN_READINESS·EVALUATION_PLAN·EXTERNAL_READINESS_RUNBOOK·ARCHITECTURE·ADR·DESIGN/TEST_SPEC·Worklog·implementation plan이 구현된 기본 OFF/profile/count·최대HTTP12 관측과 역사/현재 검증을 구분함을 확인했다. 이전 UI/계정/마감·500자 정책을 새 기능으로 재정의하지 않았다.
- history의 정확 `.patch`/`.diff`는 결함 주입 원본 자료로 보존하며 그 안의 의도된 whitespace를 제품 코드 lint 결함으로 취급하지 않는다. 공개 API/SQL·기존 guard를 변경해 검증을 우회한 흔적은 없다.

## 남은 범위

합성 검증은 실제 운영 설정·공급자 지원·실청구·billed input 동등성·모임 총10원 보장을 증명하지 않는다. 실제 opt-in·유료30개 평가·실제 SSM/Secret/RSA/OAuth/host/DB·main 및 배포는 보류다. 기본 OFF인 새 방은 확인을 완료할 수 있으리라 주장하지 않으며 MAIN_READINESS의 main 차단 조건을 유지한다. 정확 게시/merge SHA CI 확인은 부모가 후속 기록한다.
