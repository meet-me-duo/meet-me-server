# 입력 수정 라운드의 배포 준비안

이 문서는 ADR-046의 V8 전환과 별도 운영 선행조건을 기록한다. 공통 host guard의 저장소 구현·최종37검사·strict15결함 탐지/원본 복원을 완료했고 최종 독립 리뷰·새 head CI는 확인 대기다. 운영 설치·quiesce·인프라 변경·migration·배포·smoke는 실행하지 않았다. 기능/CI 검증과 실제 호스트 설치·운영 승인을 구분하며 배포 준비 완료로 보고하지 않는다.

## 현재 차단하는 실행

ADR-042에 따라 main 병합 뒤 CI 성공이 자동 CD를 실행한다. 다음 실행은 배포 조건과 별도 승인 전까지 진행하지 않는다.

| 실행 | 차단 이유 |
| --- | --- |
| main 대상 PR 병합 또는 main push | `.github/workflows/deploy.yml`의 자동 운영 CD를 시작한다. |
| `gh workflow run deploy.yml --ref main` | 동일한 기존 migration/교체 경로를 수동 시작한다. |
| 배포/rollback/refresh/restart의 운영 실행 | 설치된 고정 launcher·정확한 승인 digest·운영 선행조건과 별도 승인이 필요하다. source wrapper만 내려받아 설치가 완료되지는 않는다. |
| 이전 release의 미교체 script와 이미 열린 구 Bash FD | 설치 전에는 구 writer/worker를 다시 실행할 수 있다. 교체와 drain을 모두 확인해야 한다. |
| `docker start meet-me-app`, 임의 구 image의 Compose/SSM 직접 실행 | 관리자 직접 실행은 shell guard 밖이므로 운영 권한/절차로 통제해야 한다. |

placeholder는 실행값을 제공하는 명령이 아니다. feature Draft PR·정확한 SHA의 CI는 운영 CD와 구분해 진행할 수 있다. 직접 운영 쓰기·비밀값 조회·실제 모델 호출은 이 준비안에 포함하지 않는다.

## 구현한 관리 경계와 검증 상태

기존 deploy는 구 app이 실행된 채 migration했고 health 실패 뒤 구 image를 되살렸다. 독립 실제 script 실행에서 이 두 경계와 sticky marker 부재의 assertion RED를 확인한 뒤 저장소 구현을 변경했다. installer는 모든 저장 release의 deploy/rollback/refresh를 고정 launcher wrapper로 교체한다. workflow도 설치된 launcher를 직접 호출하며 policy 등록·설치를 자동 수행하지 않는다. ADR-044의 기존 SSM current-script 경로는 설치된 wrapper를 통해 같은 guard를 사용한다.

- `<host-root>/guard/host-release-guard.sh`는 자기 설치 위치에서 host root를 도출한다. 기본 host는 `/opt/meet-me`다. 모든 action은 동일한 실제 exclusive flock을 사용하고 잠금 뒤 current·phase·승인 정책을 다시 읽는다.
- `state/compatible-images.tsv`는 완전한 `registry/repository@sha256:<64 lowercase hex>`·`input_revision_v8`·64hex review evidence의 세 TAB 필드다. 태그·접두어·schema 숫자·label·요청 image 자동 등록은 허용하지 않는다. 파일/디렉터리 root 소유·group/world 비쓰기·regular artifact 여부와 manifest/hash를 검증하며 누락/변조는 AWS secret 조회나 migration/launch 전에 거절한다.
- phase는 `PRE_V8`→`V8_STARTED`→`READY`다. PRE_V8 marker는 없어야 한다. 나머지는 정확한 `input_revision_v8` marker가 필수다. PRE_V8/V8_STARTED는 승인 deploy만 가능하고 rollback/refresh/restart는 READY와 승인 image를 요구한다. 살아 있는 writer를 중지한 뒤 phase·marker를 같은 파일시스템의 atomic rename과 sync로 영속화하고 migrate한다. phase만 기록된 crash도 fail closed하며 marker를 자동 초기화/해제하지 않는다.
- 구 script는 hard-link archive에 원래 inode를 고정하고 manifest에 device/inode 문자열·바이트 hash를 기록한다. 모든 install/launch는 `/proc/<pid>/fd` metadata를 검사하며, 이미 열린 구 script가 flock을 기다려도 거절한다. cmdline·secret을 읽거나 출력하지 않는다. 관측 불가능한 live FD는 실패다. 중단된 pre-marker 설치는 유효한 manifest/pin을 보존한 명시적 installer 재시도로만 마무리한다. manifest 없이 launcher/override가 이미 있는 경우는 wrapper를 변경하거나 artifact를 자동 채택하지 않고 검토된 복구를 요구한다. marker가 있는데 phase가 없는 경우는 자동 복구하지 않는다.
- 앱은 HTTP·worker·relay·retention을 포함한 단일 `meet-me-app`이다. Docker restart를 끄고 stop 후 실제 Running=false 또는 신뢰 가능한 engine/container 부재를 확인한다. migration·health 실패는 점검 상태와 marker를 유지하고 실패 앱을 중지한다. 구 image fallback을 호출하지 않는다. 승인된 새 deploy가 roll-forward한다.
- `guard/compose.guard.yml`의 바이트도 manifest로 검증한다. 모든 Compose 호출의 마지막 override가 승인 APP_IMAGE와 restart `no`를 강제하므로 옛 저장 Compose가 image를 hardcode하거나 unless-stopped여도 이를 따르지 않는다. launch 뒤 실제 `.Config.Image`와 app/proxy health를 다시 검사해야 current/READY를 기록한다. boot unit은 고정 launcher의 restart를 호출하도록 staging만 한다.

첫 독립31검사·설치된 READY guard의 기존 refresh 회귀와 후속 runtime2/static9 검사를 통과한 뒤, FD 관측·설치 artifact 경계의 별도 assertion RED와 최소 수정을 완료했다. 최종 현재 source의37개 독립 검사는381.325초/실패0/오류0으로 통과했다.15개 strict 결함을 모두 탐지했고 각 실행 뒤 선택한210개(99main Kotlin·81test Kotlin·25deploy·5CI) SHA를 정확히 복원했다. 이 선택 집합에 resources는 포함되지 않는다. 별도로 tracked src main/test196개를83a checkpoint와 비교하여 resources를 포함한 바이트 불변을 확인했다. 최종 독립 리뷰·복원 뒤 JVM gate invocation·동일 후속 feature commit/push·정확한 새 head CI는 확인 대기다. JVM의 변경하지 않은 전체438 gate도 통과했으며 이것을 script 검증으로 대신 사용하지 않는다. 테스트는 copied scripts·합성 AWS/Docker·실제 파일/하드링크/flock/queued Bash FD를 사용한다. 실행 환경 uid1000의 owner/proc namespace projection은 명시한 logical 검증이며 실제 root 호스트 권한·전체 `/proc` 가시성·서비스 설치 증거로 보고하지 않는다. 기존 입력 수정 기능의438 GREEN을 새 deployment guard의 GREEN으로 대신하지 않는다.

## 운영자가 별도로 완료할 설치 조건

`[AGENT]`는 source·독립 검증·비밀값 없는 가이드를 준비한다. `[USER]`는 AWS/GitHub/EC2 관리자 권한과 별도 운영 승인을 가지고 다음 조건을 실제 호스트에서 완료한다. 현재 아래 조치는 모두 미실행이다.

1. GitHub main 승격/Production dispatch와 EventBridge의 정기·회전 refresh를 중지하고 이미 대기 중인 SSM 명령·구 Bash 실행을 취소/drain한다. Docker socket·임의 SSM·직접 구 workflow 경로의 관리자 실행을 통제하고 관리되지 않은 DB writer도 확인·중지한다. 운영 single-app topology, 유지보수 시간과 승인된 roll-forward 담당자를 확인한다.
2. `/opt/meet-me/state`를 root 소유 비쓰기 범위로 준비하고 검토한 정확한 digest/evidence의 TSV를 `compatible-images.tsv`에 저장한다. 예시 placeholder나 테스트 digest는 승인 값이 아니다. 미래 image도 별도 검토 후 이 정책을 갱신해야 한다.
3. root 소유·0600 prerequisite JSON을 준비한다. 필수 형식은 `{"version":1,"host_root":"/opt/meet-me","automation_paused":true,"legacy_invocations_drained":true,"restart_policy_reviewed":true,"operator_evidence_sha256":"<64hex>"}`다. boolean은 실제 수행 뒤 운영자가 기록하며 source 테스트가 AWS pause 사실을 보증하지 않는다.
4. 검토한 release를 `/opt/meet-me/releases/<trusted-release>`에 준비하고 root로 `bash <trusted-release>/scripts/install-host-release-guard.sh /opt/meet-me <trusted-release> <root-owned-prerequisites.json>`를 실행한다. installer는 pins·manifest·wrapper 전체를 확인하고 app을 중지하며 phase를 보존한다. cross-filesystem hard-link 실패, 열려 있는 구 FD 또는 unsafe metadata를 강제로 우회하지 않는다. 설치가 완료돼도 migration/웹 배포는 실행하지 않는다.
5. staging된 `/opt/meet-me/guard/meet-me-guarded-restart.service`를 별도 검토 후 `/etc/systemd/system/`에 설치하고 daemon-reload/enable한다. PRE_V8에서 `restart`를 시작하지 않는다. 실제 host 부팅/서비스 설정과 Docker restart 비활성·known process 가시성을 운영자가 확인해야 한다. source unit 복사는 systemd install/enable 완료를 뜻하지 않는다.
6. prerequisite·manifest·wrapper/pin·unit·정확한 승인 digest와 유지보수 계획의 비밀값 없는 완료 신호만 공유한다. credential·AWS secret·runtime env·cookie·사용자 원문은 채팅/저장소/명령 로그에 공유하지 않는다. 모든 gate와 별도 운영 승인 뒤 `[SHARED]` health/schema 읽기 전용 확인으로 이어간다.

관리자 직접 Docker/SSM·별도 unmanaged writer를 shell guard가 완전히 막는다고 주장하지 않는다. 외부 pause/권한 통제를 되돌리거나 운영자가 임의 구 명령을 쓰면 관리 경계의 전제가 깨진다.

## 승인 후 수행할 순서

1. 위 lifecycle guard를 기존 설치 경로까지 적용·검증하고 호환 roll-forward digest와 복구 담당자를 확정한다. 공유 잠금 아래 구 앱 전체를 중지한다. HTTP 차단만으로는 worker·Outbox relay·retention이 멈추지 않는다.
2. 모든 구 writer와 실행 중 작업의 종료를 확인한 후 marker를 기록하고 새 이미지로 V8 migration을 실행한다. 미처리 Redis 참조와 PostgreSQL 작업을 임의 삭제하지 않는다. 이 기간 서비스는 점검 상태다.
3. V8 호환 서버만 시작하여 health·native OpenAPI·additive 조회 계약을 확인한다. 실패 시 구 서버를 재기동하지 않는다. current release 선택과 credential refresh 재개에도 동일한 호환 검사를 적용한다.
4. 검증된 생성 schema를 사용하는 웹을 배포하고 capability가 있을 때만 수정 흐름을 노출한다. 운영 데이터를 사용하지 않는 staging 합성 검증 후 접근과 호환 credential refresh를 재개한다.

V8 backfill 뒤 구 서버가 새 방/분석을 쓰면 active pointer가 누락될 수 있고 구 retention은 새 round FK를 정리하지 못한다. 따라서 migration과 앱 교체 사이의 동시 구 writer 허용은 불가능하다. Flyway downgrade 또는 구 backend rollback은 복구 계획으로 제공하지 않는다.

## 캐시된 구 웹의 잔여 한계

새 웹+구 서버는 capability가 없으면 recovery를 숨길 수 있다. 구 웹+새 서버 OPEN은 COLLECTING으로 표시되지만 round/revision 없는 PUT은409, close는200 no-op이다. 이는 원문/배치 보호이며 수정 UX의 양방향 호환 성공이 아니다. 웹 선배포도 이미 열린 구 탭이나 캐시를 제거하지 못한다.

### 완료한 격리 브라우저 검증

구 웹의 정확한 source `68c41f7619e7f1a28b2b597cb56867e4d3e852bc`를 scratch에 다시 빌드해 기존 baseline bundle과 바이트 지문이 동일함을 확인했다. 첫 새 웹 `059f049a4832effb2e2cc844d33f4cdd50b5dc1e`와 최종 새 웹 `3c403a2667d4a3921ba8a661fb2a4e9758b220ed`의 실제 번들을 각각 격리 브라우저에서 합성 HTTP 응답과 연결한6개 검사가 모두 통과했다. native OpenAPI는 동일한 `22225ba0db80bf9afb31fa641f8edc385a2dda69abf83f0c063fb18741890ce0`이다. 웹 최종 CI #37566261043 성공은 운영 설치·구 탭 강제 갱신의 증거로 사용하지 않는다.

| 검사 | 실제 브라우저에서 확인한 결과 |
| --- | --- |
| 구 웹 + 새 서버 OPEN 합성 계약의 PUT409 | raw_text만 전송하고409를 표시한다. 저장 revision1은 그대로이며 editor draft가 남는다. |
| 같은 구 화면의 close200 | OPEN 라운드와 기존 마감 버튼이 유지되고 재분석 버튼은 없다. 합성 API는 새 작업을 만들지 않는다. |
| hosting을 새 bundle로 바꾼 후 구 탭 유지 | 새로고침 없이 이미 로드한 구 JavaScript가 계속 실행된다. 선배포만으로 교체되지 않는다. |
| 명시적 새로고침 후 신 웹 | 새로고침 자체는 쓰기를 보내지 않는다. 새 UI는 현재 round와 revision을 넣어 명시적으로 저장하며 dirty 입력의 분석을 막는다. |
| 구 웹의 신규 참여 표시 | 구 UI는 Join을 제안하지만 합성 새 계약은 ROOM_CLOSED로 거절한다. 신 웹은 Join을 숨긴다. |
| 신 웹 + 구 서버 합성 계약 | 신규 capability/context/quota/state 필드 없이 기존 raw_text만 보내는 저장과 HOST close를 유지하고 recovery 명령을 숨긴다. |

검증 근거는 로컬 전용 `scratch/meet-me-bootstrap/web17-mixed-version/bundle-manifest.json`과 `probe-result.json`이다. 실제 backend 요청·모델 호출·운영 접근은 모두0이며, 서버의 동작을 실제 HTTP로 재검증한 근거가 아니다. 합성409 detail도 운영 locale 문구 검증이 아니다. 서버의 실제 PostgreSQL/HTTP 검증은 Worklog의 별도 근거를 따른다.

### 남아 있는 운영 안내·지원 조건

새로고침하면 구 웹의 미저장 draft가 사라짐을 검사에서 확인했다. 운영자는 “미저장 입력을 먼저 복사한 뒤 페이지를 새로고침하고 업데이트된 웹에서 본인 입력을 확인해 주세요”라는 안내와 지원 경로를 준비해야 한다. 복사한 미저장 원문은 신 웹에 다시 입력하고 명시적으로 저장한 뒤 저장 결과를 확인하도록 안내한다. 이미 저장된 본인 입력은 신 웹에서 다시 읽되 미저장 draft가 자동 이전된다고 안내하지 않는다. 이 운영 안내·지원 절차와 구 탭 갱신을 보장하는 운영 gate는 아직 구현·승인·실행하지 않았다. 구 화면의 draft를 강제로 지우거나409를 저장 성공으로 표시하지 않는다. 동작 허용 여부는 공개 상태만이 아니라 현재 capability·round·revision으로 판단한다. viewer.context_id는 자기 원문 cache scope hint이며 인증 권한을 대신하지 않는다. 익명/만료/다른 viewer 전환과401/403에서는 자기 원문 cache를 분리·정리한다.

## 읽기 전용 확인과 미실행 목록

로컬/격리 staging에서는 합성 방과 합성 증명으로 GET room/본인 submission/candidates의 metadata·현재 상태·권한 경계를 검사한다. 수정 쓰기·경쟁은 별도의 PostgreSQL/HTTP 합성 검증을, 구 캐시 화면은 위 격리 번들6검사를 완료했다. 실제 cross-version backend와 운영 Origin의 rollout 확인은 아직 수행하지 않았다.

별도 승인된 운영 배포 뒤에는 `/healthz`, 앱 내부 `127.0.0.1:9090/actuator/health`, `/v3/api-docs`를 확인한다. 사용자 방·운영 원문·다른 참여자 입력을 탐색하거나 cookie/credential/원문을 출력하지 않는다. 운영 POST/PUT·reopen·재분석·확정은 smoke 대상이 아니다. 합성 운영 fixture가 따로 승인되지 않으면 health/schema 확인에서 멈춘다.

현재 미실행: 새 guard의 최종 리뷰 확인·후속 commit/push·정확한 새 head CI 완료, 실제 root 호스트 guard/unit 설치, 외부 automation pause·drain, 운영 quiesce, V8 운영 migration, 운영 image/웹 배포, 운영 endpoint smoke, develop/main 병합. source 구현·테스트·native export·CI의 실제 결과는 Worklog와 TDD verification 근거를 따른다. 별도 공급자 평가에서 작은 입력의200/OK와 실제503 응답이 관찰됐지만 원문 의미 정확도와 실제 모델→HTTP/DB 흐름은 아직 검증되지 않았으며 이 운영 준비안의 성공 근거로 사용하지 않는다.
