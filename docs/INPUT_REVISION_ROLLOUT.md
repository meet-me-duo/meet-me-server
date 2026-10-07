# 입력 수정 라운드의 배포 준비안

이 문서는 ADR-046의 V8 전환을 검토할 준비안이다. 기능 구현·로컬/CI 검증과 운영 배포 승인을 구분한다. 현재 운영 배포·quiesce·인프라 변경·운영 smoke는 실행하지 않았다. 기존 lifecycle script는 아래 guard를 구현하지 않았으므로 배포 준비 완료가 아니다.

## 현재 차단하는 실행

ADR-042에 따라 main 병합 뒤 CI 성공이 자동 CD를 실행한다. 다음 실행은 배포 조건과 별도 승인 전까지 진행하지 않는다.

| 실행 | 차단 이유 |
| --- | --- |
| main 대상 PR 병합 또는 main push | `.github/workflows/deploy.yml`의 자동 운영 CD를 시작한다. |
| `gh workflow run deploy.yml --ref main` | 동일한 기존 migration/교체 경로를 수동 시작한다. |
| `bash deploy/scripts/deploy-release.sh <release-directory> <image-digest>`의 운영 실행 | 현재 구 앱이 실행된 상태로 migrate하고 health 실패 시 이전 image를 재기동한다. |
| `bash deploy/scripts/rollback-release.sh <previous-release-directory>`의 V8 이후 운영 실행 | previous image의 active pointer·round·retention 계약이 검증되지 않았다. |
| 이전 release의 credential-refresh script, `docker start meet-me-app`, 구 image의 `docker compose up` | 중지한 구 writer/worker를 다시 실행할 수 있다. |

placeholder는 실행값을 제공하는 명령이 아니다. feature Draft PR·정확한 SHA의 CI는 운영 CD와 구분해 진행할 수 있다. 직접 운영 쓰기·비밀값 조회·실제 모델 호출은 이 준비안에 포함하지 않는다.

## 검토해야 할 최소 lifecycle 변경

`deploy-release.sh`, `rollback-release.sh`, `refresh-database-credential.sh`는 동일한 release 잠금을 쓰지만 잠금은 실행이 끝난 후 구 image 재기동을 막지 않는다. 특히 health 실패 후 `/opt/meet-me/current`가 이전 release를 가리키면 ADR-044의 5분/EventBridge refresh가 구 앱을 force-recreate할 수 있다. 새 release 폴더의 guard만 추가하면 이미 설치된 이전 script는 이를 모른다.

- 모든 재시작 경로가 사용하는 host entrypoint 또는 사전 갱신된 guard에서 지속적인 breaking-release marker와 검증된 compatible image digest를 검사한다. schema 숫자나 image label만으로 실행을 허가하지 않는다.
- marker는 migration 직전에 원자적으로 기록한다. migration 실패 또는 완료 여부 불명도 marker를 유지하고 이전 image 실행을 거절한다. V8 미적용을 별도로 증명하기 전 자동 해제하지 않는다.
- 기존 설치 script·대기 중 refresh·배포 dispatch·수동 rollback·host/Compose 재시작·운영자의 구 image 직접 실행을 통제한다. 기존 스크립트가 우회 가능한 상태이면 gate는 실패다.
- marker 이후 health 실패는 호환 digest로 roll-forward하거나 점검 상태를 유지한다. automatic previous-image fallback을 사용하지 않는다. credential refresh도 호환 current release만 실행해야 한다.

이 변경은 아직 구현하지 않았다. 독립 테스트의 assertion RED, 실패 경로·guard 우회 결함 주입, 독립 리뷰와 전체 gate를 통과한 구체적인 script diff가 운영 승인 전에 필요하다.

## 승인 후 수행할 순서

1. 위 lifecycle guard를 기존 설치 경로까지 적용·검증하고 호환 roll-forward digest와 복구 담당자를 확정한다. 공유 잠금 아래 구 앱 전체를 중지한다. HTTP 차단만으로는 worker·Outbox relay·retention이 멈추지 않는다.
2. 모든 구 writer와 실행 중 작업의 종료를 확인한 후 marker를 기록하고 새 이미지로 V8 migration을 실행한다. 미처리 Redis 참조와 PostgreSQL 작업을 임의 삭제하지 않는다. 이 기간 서비스는 점검 상태다.
3. V8 호환 서버만 시작하여 health·native OpenAPI·additive 조회 계약을 확인한다. 실패 시 구 서버를 재기동하지 않는다. current release 선택과 credential refresh 재개에도 동일한 호환 검사를 적용한다.
4. 검증된 생성 schema를 사용하는 웹을 배포하고 capability가 있을 때만 수정 흐름을 노출한다. 운영 데이터를 사용하지 않는 staging 합성 검증 후 접근과 호환 credential refresh를 재개한다.

V8 backfill 뒤 구 서버가 새 방/분석을 쓰면 active pointer가 누락될 수 있고 구 retention은 새 round FK를 정리하지 못한다. 따라서 migration과 앱 교체 사이의 동시 구 writer 허용은 불가능하다. Flyway downgrade 또는 구 backend rollback은 복구 계획으로 제공하지 않는다.

## 캐시된 구 웹의 잔여 한계

새 웹+구 서버는 capability가 없으면 recovery를 숨길 수 있다. 구 웹+새 서버 OPEN은 COLLECTING으로 표시되지만 round/revision 없는 PUT은409, close는200 no-op이다. 이는 원문/배치 보호이며 수정 UX의 양방향 호환 성공이 아니다. 웹 선배포도 이미 열린 구 탭이나 캐시를 제거하지 못한다.

### 완료한 격리 브라우저 검증

구 웹의 정확한 source `68c41f7619e7f1a28b2b597cb56867e4d3e852bc`를 scratch에 다시 빌드해 기존 baseline bundle과 바이트 지문이 동일함을 확인했다. 새 웹은 `059f049a4832effb2e2cc844d33f4cdd50b5dc1e`, native OpenAPI는 `22225ba0db80bf9afb31fa641f8edc385a2dda69abf83f0c063fb18741890ce0`이다. 이 두 실제 번들을 격리 브라우저에서 합성 HTTP 응답과 연결한6개 검사가 모두 통과했다.

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

현재 미실행: lifecycle script 변경·guard 설치, 운영 quiesce, V8 운영 migration, 운영 image/웹 배포, 운영 endpoint smoke, develop/main 병합. 소스 테스트·native export·CI의 실제 결과는 Worklog와 TDD verification 근거를 따른다.
