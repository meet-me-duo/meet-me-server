# meet-me-server Implementation Plan

> **상태:** Active  
> **최종 갱신:** 2026-10-09
> **목표:** MVP 백엔드 구현의 의사결정, 작업 순서, 진행 상황과 완료 근거를 한곳에서 추적한다.

이 문서는 실행 체크리스트다. 제품 요구사항은 [`docs/PRD.md`](docs/PRD.md), 기술 구조와 TBD는
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), 확정된 장기 결정은 [`docs/ADR.md`](docs/ADR.md)를
기준으로 한다. 이 문서가 기준 문서와 충돌하면 기준 문서를 우선하고 이 계획을 갱신한다.

## 최신 운영 읽기 점검 (2026-10-09 UTC)

- `[AGENT]` main `35c8faff9c4c112e5ad28ac740cd75d3665105a7`의 [Preflight 37899264333](https://github.com/meet-me-duo/meet-me-server/actions/runs/37899264333), attempt 1을 기존 production OIDC로 실행했다. 인증과 host/Flyway 읽기는 성공했으며 전체 결과는 commandsBefore `AWS_UNAVAILABLE`로 exit 2다. 새 인증·권한·secret 설정 변경은 없다.
- `[USER/AGENT]` 사용자가 재등록한 서울 리전 `/meet-me/production/secret/openai-api-key`는 metadata에서 `SecureString`, Version 1로 확인됐다. 값·키 유효성·runtime 전달은 조회/검증하지 않았다.
- `[AGENT]` 기존 `5e8faab` release는 healthy, 공개 health/OpenAPI는 200이며 probe 전후 PID/restartCount가 같다. 실제 Flyway V1~V7은 모두 성공하고 publisher 37643254403/1 manifest의 prefix와 checksum이 일치한다. V8~V10은 미적용이다. refresh 두 rule은 ENABLED/각 target 1개, guard 8개 파일 없음·guarded-restart not-found/inactive다.
- `[AGENT]` 로컬 `fix/101-preflight-failure-diagnostics`는 최신 develop `492dc13b9f9d12d9550f31ad4c84c1ff5ea51c87` 기준이다. 운영 main과 해당 preflight 코드의 동일성을 확인했다. 전체 SSM 이력의 자동 pagination을 bounded active filter 두 개로 제한하고, timeout/JSON/실행 실패를 고정 코드로 구분하는 수정과 오프라인 회귀를 준비했다. truncation·불명확한 target은 계속 unavailable다. 실제 실패가 timeout인지 JSON 처리인지 기존 artifact로 확정하지 않는다.
- `[AGENT]` 새 unittest 5개 PASS, 기존 preflight/release/deploy 계약 40개 PASS(39개 첫 실행 PASS, Git checkout 소유자 환경 오류 1개 동일 소유자에서 재실행 PASS). python3/Git Bash alias만 local evidence preload로 매핑했고 저장소 assertions는 유지했다. 최종 회귀 5개 재실행 및 고의 결함 5개 모두 assertion 탐지·source 파일 불변을 확인했다. 새 Gradle/전체 앱 실행은 수행하지 않았고 커밋도 없다.
- `[AGENT]` 별도 source 차단: AWS EventBridge Target API의 경로는 `RunCommandParameters.RunCommandTargets`다. 현재 preflight projection과 release `_aws_facts`는 최상위 `RunCommandTargets`를 사용하므로 artifact null을 실제 target 누락으로 판정할 수 없다. release gate의 이 응답 경로 문제는 이번 로컬 수정에 포함하지 않았다. [AWS Target](https://docs.aws.amazon.com/eventbridge/latest/APIReference/API_Target.html) / [RunCommandParameters](https://docs.aws.amazon.com/eventbridge/latest/APIReference/API_RunCommandParameters.html).
- `[SHARED]` 원격 push/PR/병합은 부모의 별도 승인 범위 확인 후 진행한다. reader 변경도 ADR-050의 guard fingerprint를 바꾸므로 기존 게시 tar/digest와 같은 계약으로 취급하지 않는다. exact source/digest·게시 이미지 검증·수정본 읽기 결과와 첫 전환 조건을 보고한 뒤 최종 유지보수 승인이 필요하다. rule 중지·drain·guard 설치·old writer 종료·V8~V10 migration·새 image 시작·서버/웹 배포는 미실행이다. 웹 PR18/20은 Open/Draft임을 확인만 했다. 사전과제 저장소는 접근하지 않았다.

### 같은 날 후속 — EventBridge 경로 수정과 main 자동 실행 분석

- `[AGENT]` 부모의 승인 대기 지시에 따라 원격 변경 없이 같은 로컬 브랜치에서 release gate의 nested `RunCommandParameters.RunCommandTargets` 접근과 preflight의 해당 metadata projection을 수정했다. 정확한 InstanceIds/역할/문서/target 1개 일치·pagination 거부 조건은 그대로다. Input·명령 본문은 metadata에 포함하지 않는다. 앞 항목의 "release gate 수정 미포함"은 이 후속 이전 상태다.
- `[AGENT]` 새 EventBridge 회귀 4개는 기존 schema에서 assertion RED 3개·PASS 1개 확인 후 GREEN 4개다. 정상 nested 응답 허용, 비밀 없는 projection, top-level shadow/다른 인스턴스/없거나 빈 nested 대상 거부를 검증했다. 기존 preflight/release/deploy 41개 PASS·skip0, 기존 command 회귀 5개 PASS다. EventBridge 결함3개를 assertion으로 탐지하고 파일 bytes 불변을 확인했다. 전체 앱 Gradle·원격 CI·독립 리뷰는 이 후속에서 실행하지 않았고 커밋·push·PR도 없다.
- `[AGENT]` 현재 GitHub production environment의 protection_rules는 branch_policy만, 허용 브랜치는 main이다. required reviewer와 wait timer는 없다. main legacy protection은 404(Branch not protected), main 적용 rules API는 []다. 설정은 변경하지 않았다. main push CI 성공 후 `Deploy Production`의 build-publish는 ECR/S3 게시를 자동 실행한다. approved-release는 automatic/approve=false로 실제 facts를 읽고 `TRANSITION_REQUIRED`일 때 deployment SSM 분기를 실행하지 않는다. 유효한 READY/history10/root record·동일 SQL/guard fingerprint·일치하는 current image·active command0·parameterReady이면 AUTO_READY로 사용자 수동 입력 없이 배포할 수 있다.
- `[SHARED]` 따라서 main 반영을 읽기 전용 실행이라고 표시하면 안 된다. 이번 local reader/driver 변경의 guard fingerprint는 기존 게시물과 다르며 현재 V1~V7·guard 미설치 snapshot은 same-contract 자동 배포 조건을 충족하지 않는다. 가상의 pendingCommands=0과 유효한 UNINSTALLED 상태로 수행한 오프라인 policy replay는 automatic/preflight 모두 TRANSITION_REQUIRED였다. 실제 command0·root record·fresh state 확인을 대체하지 않는다. 첫 전환은 action=deploy + approve_first_transition=true 및 두 rule DISABLED·실제 drain0·정확한 history prefix가 필요하다. main 병합 승인에는 자동 build/publish가 포함되는지 부모가 명확히 해야 한다. 운영 전환 승인은 아직 없다.

아래 2026-10-07 진행 요약과 Worklog는 당시 이력이다. 현재 운영 확인 상태는 위 기록을 따른다.

### develop Draft PR 승인 범위 (2026-10-09 UTC)

- `[USER]` 부모가 확인한 08:18 UTC의 “진행해”는 수정 브랜치 push, develop 대상 리뷰 PR, 정확 HEAD CI 실행의 승인이다. main 병합, ECR/S3 운영 release 게시, 운영 배포·migration·refresh rule 변경·guard 설치는 승인하지 않았다.
- `[AGENT]` 이번 수정의 열린 이슈 [#103](https://github.com/meet-me-duo/meet-me-server/issues/103)을 만들었다. 변경은 두 Python 조회 코드, Node 계약 검사 연결, 신규 offline 회귀 테스트 9건 및 이 Worklog로 제한한다. 기존 develop commit `492dc13b9f9d12d9550f31ad4c84c1ff5ea51c87`에서 분기했고 운영 workflow 설정은 바꾸지 않는다.
- `[AGENT]` 최종 Node 계약 검사 41건 PASS/skip 0, command 회귀 5건 및 EventBridge 회귀 4건 PASS, 의도적 결함 8건 모두 assertion으로 탐지했다. 자체 검토에서는 truncation·불명확한 target·nested target 불일치를 계속 unavailable/차단으로 처리하는지 확인했다. 별도의 독립 리뷰 완료를 주장하지 않으며 Draft PR의 리뷰 요청으로 남긴다.
- `[AGENT]` 수정하지 않은 `.codex/hooks/tdd_guard.py`가 exit 0으로 필수 `--no-daemon ktlintCheck assemble test`를 완료했다. JVM 111 suite/654 tests, failures 0/errors 0/skipped 4이며 hook self-test 12건도 PASS이다. 이 결과와 `git diff --check`를 확인한 뒤 승인된 fix 브랜치 commit/push 및 Draft PR을 진행한다. 원격 exact HEAD CI는 게시 후 별도로 확인한다.

## 운영 규칙

### Issue #99 현재 통합 범위 (2026-10-07 UTC)

**추가 승인 — 첫 V8→V9/V10 전환 pipeline:** 기존 GitHub production 역할·보호규칙 안에서 build/publish와 preflight/deploy를 분리한다. 첫 전환은 게시된 정확 run/attempt·tar SHA·ECR digest와 실제 읽기 결과를 검토하고 명시 승인한 뒤에만 실행한다. 성공한 실제 history·guard 계약을 root 소유 record로 저장하며, 같은 SQL/guard 계약의 후속 main CI 배포는 기존 자동 경로를 유지한다. 모든 main 배포의 영구 수동화·IAM/환경 보호 변경은 승인하지 않았다. 두 EventBridge rule의 사용자 중지는 실제 유지보수 시작 시점에 묶고 지금 요청하거나 실행하지 않는다.

- [x] `[AGENT]` 첫 전환 gate와 같은 계약의 후속 자동 배포를 구분하는 독립 설계 검토. immutable run/attempt 게시·실제 history/READY/current digest 재확인·구 V8 승인 제거·기존 lock 중첩 금지 경계를 확정했다.
- [x] `[AGENT]` 독립 assertion RED16→동결39 GREEN/skip0. 분리 pipeline·normal-main 읽기 preflight·기존 host guard 연계와7fault/8assertion 탐지·303파일 정확 복원. 사용자 지시대로 추가 host fixture는 실행 없이 폐기했다.
- [ ] `[AGENT]` 독립 소스 리뷰·변경 없는 mandatory guard·정확 새 HEAD CI와 Draft PR100 기록.
- [ ] `[SHARED]` 정확 배포 대상/run/attempt/digest·실제 preflight 결과·유지보수 시작 조건의 최종 승인. main/develop 병합·원격 변경·migration·rule 중지는 아직 실행하지 않는다.

**추가 승인 — 시간 배분 조정:** PR100 통합 HEAD51a4c0d의 실제 평가에서 총56.975초, Gemini3회+Luna1회 모두 TIMEOUT·ANALYSIS_DELAYED·winner/후보0·late 미게시가 확인됐다. relay의 Luna HTTP200 저장19.795초는 네트워크·프록시·JSON/파일 저장 포함이며 모델 순수 지연이나 새 정책 성공률이 아니다. 사용자가 전체60초를 유지하며 Gemini최대30초/Luna최대27초/완료3초, 최대3재시도·부족시 조기 폴백을 승인했다. 기존 feature 브랜치·Issue99·PR100에서 독립 시간 경계 RED→구현→리뷰→전체guard/새HEADCI를 진행한다. 이 구현 세션의 유료 호출은 계속0이며 운영 설정·권한·DB 적용은 변경하지 않는다.

- [x] `[AGENT]` processor와 Lunaadapter의15초고정캡을 공급자별15/27초로 일치시키고 single monotonic/durable60초 및 Gemini30/provider57/완료60 경계를 검증한다.
- [x] `[AGENT]` 독립 mock·loopback/transport·실제Postgres로20초Luna성공·27/57/60late차단·lockwait·4+1·재전달noreset·원자추천rollback을 검증한다. 유료/운영 쓰기는0.
- [x] `[AGENT]` 시간 배분 후속6f7f54e의 guard654건·CI37599130138 성공 및 전담 평가3시나리오/4분석PASS를 인계했다. 문서/읽기 조회48b8957의CI37610286399도 성공이며 평가 SHA와 제품 바이트 동일성을 구분한다.

**운영키 전달 후속 승인:** 현재 feature/PR100에서 SSM SecureString 참조·runtime env·Compose 전달·entrypoint 기동 조건을 무료 독립 검증한다. 실제 키 값 조회/등록/복사/출력·IAM/환경보호 변경·운영 명령·main merge·서비스 중단은 보류한다. 집 PC 접속은 운영 권한 확대 승인으로 취급하지 않는다.

- [x] `[AGENT]` 새 release의 Luna required mode에서 키 누락/권한/타입/형식 오류가 환경파일 교체와 앱/migration 시작을 막고 기존 Gemini 단독 release는 OpenAI 조회 없이 유지되는 독립 RED/GREEN·결함 검증. 독립18의RED15→GREEN18 뒤 NUL blocker 추가RED1→GREEN19, 7fault/12assertion·9파일 exact 복원, 실제 로컬Compose 합성키/mode byte 보존을 확인했다.
- [x] `[AGENT]` 운영키82b8d2a의 기존 IAM prefix 검토·문서/guard·CI37614973315·Infrastructure37614973327 성공을 확인했다. 앱 시간 배분·SQL·실제 평가 SHA는 별도 유지한다.
- [ ] `[USER]` 기존 AWS 인증의 값 없는 운영 metadata/읽기 접근 확인. 기존 preflight37610286680은 production 환경이 refs/pull/100/merge를 거부하여 runner/step 실행0이다. 반복 재실행과 정책 우회는 하지 않는다.
- [x] `[USER]` AWS 수동 키 등록 완료 보고를 받았다. parameter Name/Type/Version·권한·실제 전달은 검증 전이므로 별도 미완료다.
- [ ] `[SHARED]` 승인된 운영 시점에 실제 parameter→runtime→앱 전달/인증과 정확 digest/guard/drain 시작 조건 확인. 현재 코드/fixture 성공만으로 운영 Luna 준비를 완료 표시하지 않는다.


- [x] `[AGENT]` 지정 PR97 HEAD518c612·PR98 HEAD9a4c4d5·PR94/93 의존, 최신 develop848b968과 규칙·인계를 확인했다. Issue99 생성 후 feature/integrate-recommendations-fallback에서만 조율한다.
- [x] `[AGENT]` PR97 문서 HEAD CI37585734021 성공, 웹 PR20 정확 HEAD8e56fe0c37cef52e584ff569b97b46b60f28e517을 확인했다. 개별 PR CI를 통합 검증으로 재사용하지 않는다.
- [x] `[AGENT]` 추천 V9·invocation V10 SQL의 원본 체크섬을 보존하고 ADR-047/048·Luna ADR-049를 합쳤다. 실제 PostgreSQL 빈 DB10버전 및 기존 V8 데이터 업그레이드2건 GREEN.
- [x] `[AGENT]` 독립 provider/JSON 및 live fixture RED→공통 v3·저장 복원35건 GREEN, 독립 도메인18건 중13 assertion RED, 통합7건 중6 assertion RED를 기록했다. 초기 환경·compile·Mockito fixture 실패는 기능 RED에서 제외했다.
- [x] `[AGENT]` D2·bounded 추천 원자 게시 구현. 첫 집중73건 및 안전성61건은 각각 실패0·skip0이며 중복 합산하지 않는다. 독립 리뷰의 부분 거부 선호 과점수·잘못된 필수 시간 조건 안전성 문제를 추가 assertion RED→GREEN으로 해결했다.
- [x] `[AGENT]` 독립 최종 코드 리뷰 PASS·차단0, 고의 결함11/11 assertion 탐지·247개 정확 복구, native OpenAPI parsed JSON 완전 동일 확인. 직렬화 바이트/SHA는 다르므로 동일 SHA로 표시하지 않는다.
- [x] `[AGENT]` 변경 없는 필수 guard exit0·ktlintCheck/assemble·109suite 전체631건/실패0/오류0/skip4, hook self-tests12 PASS. 첫 전체의 기존 schema 분기수 assertion은 독립 supersession과 focused12 GREEN으로 보완했다.
- [x] `[AGENT]` Worklog 최종 갱신과 commit/push/Draft PR 준비. Commit/PR은 동일 커밋 예정이며 코드·현재 증거 지문과 완료 근거를 동기화했다.
- [x] `[AGENT]` 최초 통합51a4c0d commit/push·develop 대상 Draft PR100 게시·CI37591069034 전 단계 성공을 확인했다. 후속 시간배분의 정확 HEAD CI는 별도로 확인한다.
- [x] `[SHARED]` 전담 실제 평가 서버6f7f54e/웹8e56fe0의3시나리오·4분석PASS를 docs/ISSUE_99_LIVE_EVALUATION.md에 기록했다. 구현/CI 호출0, 평가추가18·누적41/50을 구분하며 새entrypoint/운영키의 실제 전달은 미완료다.
- [ ] `[USER]` 운영 V8guard/digest/드레인 승인. 코드 완료와 배포 준비를 구분하며 main/develop 병합·배포·운영 migration·보안/비밀정보/자동화 변경은 실행하지 않는다.


### Issue #96 독립 구현 기록 (2026-10-07 UTC 인계 시점)

- [x] `[AGENT]` Issue #96·#95, PR #93, 지침 6개, project-architecture skill과 기존 Worklog를 확인했다. 깨끗한 `develop` 848b968에서 ff-only pull 후 독립 `feature/96-bounded-luna-fallback`에 검증된 PR #94 기준 bf4edff를 fast-forward했다. 이 기준은 #93 provider-authority를 이미 포함한다.
- [x] `[AGENT]` 부모가 제공한 저장 Luna 응답 fixture의 canonical SHA-256 `03bb35860232477203710071cb736c185e9c211f418da886dc2bd5d74cc2c6bf`를 확인했다. 원래 sourceHead·모델·입력 참조·referenceDate·검색 배타적 끝·schemaVersion을 보존한다. 직접 모델 호출은 0이며 부모의 평가 횟수·비용을 이 작업의 검증으로 집계하지 않는다.
- [x] `[AGENT]` 독립 계약 테스트 → 구현 → 독립 결함 리뷰 순서로 제한 폴백, 공통 검증, durable 실행·시도 claim·승자 게시·시간 경계를 검증했다. 실제 assertion으로 결함15개를 탐지하고218개 소스·테스트의 바이트 복원을 확인했다.
- [x] `[AGENT]` 실제 PostgreSQL, 무료 저장 응답 재생, 합성 HTTP timeout/취소 및 변경하지 않은 필수 hook의 전체526건/실패0/오류0/skip4를 확인했다.
- [ ] `[AGENT]` push 후 draft PR의 정확 HEAD CI를 확인하고 PR 본문에 실행 링크를 기록한다.
- [ ] `[AGENT]` 검증 후 commit·push·develop 대상 draft PR을 게시한다. 사용자가 이 범위를 명시적으로 위임했으므로 추가 생성 승인 대기는 없다.
- [ ] `[SHARED]` #95와 신규 migration 번호 및 최소 통합 계약을 조율한다. 기존 V1~V8을 수정하지 않으며 #95 추천·확정 모델을 이 브랜치에 구현하지 않는다.

승인 계약은 Gemini 최초 1회 + 최대 3회 지수 백오프 Full Jitter 기술 재시도 뒤 OpenAI `gpt-6-luna` 1회다. 60초 전체 예산에 Luna 호출과 완료 시간을 남기며 실제 남은 시간에 맞춰 Gemini 재시도 수와 호출 timeout을 줄인다. 구현 예산안은 Gemini 최대 42초, Luna 최대 15초, 완료 예약 3초다. 성공한 PARTIAL/NO_MATCH/AMBIGUOUS는 기술 실패가 아니다. NETWORK/TIMEOUT/명시적인 일시 rate-limit/SERVER만 폴백하며 인증·권한·결제·quota·잘못된 요청·설정·응답 검증은 구분한다. 두 공급자는 같은 frozen 입력·스키마·공통 validator와 후처리를 사용하고 결과를 혼합하지 않는다. 두 공급자 기술 실패는 입력·배치 보존과 `ANALYSIS_DELAYED`/후보 0/정상 ACK를 유지한다.

동일 Outbox 재전달·프로세스 재시작은 기존 논리 실행의 deadline·호출 상한을 초기화하지 않는다. 명시적 HOST 재시도는 같은 batch/run의 새 version에 해당하는 별도 논리 실행·비용 단위다. 공급자 exactly-once는 보장하지 않는다. 알 수 없는 usage/비용은 null이며 Luna에 Gemini 단가를 적용하지 않는다. 설정은 코드의 변수 참조만 추가하고 비밀값·운영 설정·권한을 읽거나 변경하지 않는다. 운영 배포·병합·유료 API 호출은 이 작업 범위에 없다.

- `[x]`는 구현, 관련 테스트, 문서 검토와 검증이 모두 끝난 작업만 표시한다.
- `[ ]`는 미착수, 진행 중, 사용자 선택 대기 또는 검증 미완료 상태를 포함한다.
- 작업 시작 전 현재 브랜치, `git status`, 최근 커밋과 관련 GitHub Issue/PR을 확인한다.
- `develop`에서는 직접 작업하지 않는다. `feature`, `fix`, `docs`, `chore` 작업 브랜치는 깨끗한 작업 트리에서 `develop`의 `git pull --ff-only origin develop`이 성공한 뒤 변경 목적에 맞는 접두사로 생성한다.
- `develop`이 분기되거나 pull이 충돌하면 임의 merge/rebase하지 않고 해결 선택지를 사용자에게 제시한다.
- 의미 있는 제약이나 복수의 구현 방안이 있으면 장점, 단점, 비용과 장기 영향을 제시하고 사용자 선택을 받은 뒤 진행한다.
- `TBD`가 걸린 작업은 의사결정 게이트가 완료되기 전에 종속 구현을 시작하지 않는다.
- 하나의 체크 항목 또는 밀접한 하위 항목 묶음을 하나의 명확한 PR 목적으로 유지한다.
- 작업 브랜치를 만들기 전에 GitHub Issue를 생성하고 PR 본문의 독립된 `Closes #<issue-number>` 행으로 연결한다. 프로젝트 GitHub Action이 형식과 열린 Issue 참조를 검증하고 `develop` 병합 시 자동 종료한다.
- 사용자와의 대화에서 범위, 우선순위, 작업 순서 또는 결정이 바뀌면 이 계획도 현재 합의에 맞게 수정한다.
- 빈 원격 저장소의 초기 구성만 `main`에 기준점용 커밋을 만든 뒤 `develop`과 `feature/initial-setup`을 생성하여 PR로 검토한다. 이 초기 절차가 끝난 뒤에는 예외 없이 최신 `develop`에서 목적별 작업 브랜치를 만드는 일반 브랜치 전략을 적용한다.
- 작업 완료 시 체크박스, 최종 갱신일과 하단 진행 기록을 함께 갱신한다.
- 사용자가 완료된 작업의 커밋과 푸시를 요청하면, 커밋 전에 완료 체크박스, 현재 진행 요약, 최종 갱신일과 진행 기록을 먼저 실제 상태에 맞게 반영한다.
- 커밋 전 진행 기록의 Commit/PR 칸은 `동일 커밋 예정`으로 기록할 수 있으며, 실제 커밋 해시는 Git 이력을 기준으로 추적한다.
- 한 단위 작업의 구현·검증이 끝나면 기본적으로 PR 생성 전에 제목과 본문 전체 초안을 사용자에게 보여주고 승인을 받는다. 번호 기반 실행처럼 커밋·push·PR 생성을 명시적으로 위임받은 흐름은 별도 승인 대기 없이 생성하고 즉시 결과를 보고한다.
- 비밀정보, 개인정보, OAuth 토큰과 외부 API 키는 이 문서와 Issue/PR/로그에 기록하지 않는다.

### 책임 표기

- `[AGENT]`: 저장소 코드·문서·테스트와 비밀값 없는 설정을 에이전트가 완료한다.
- `[USER]`: 계정 로그인, 신원·소유권·동의·결제·관리자 권한 또는 실제 비밀값이 필요해 사용자가 직접 완료한다.
- `[SHARED]`: 에이전트가 코드·설정·절차를 준비하고 사용자가 외부 시스템 설정을 완료한다.
- 표기가 없는 기존 구현 항목은 기본적으로 `[AGENT]`다. 외부 작업이 발견되면 하나의 항목을 `[AGENT]`와 `[USER]`로 분리한다.
- 사용자 개입이 필요하면 `.agents/rules/user-intervention.md`의 `[USER ACTION REQUIRED]` 형식으로 서비스 위치와 단계별 행동까지 종속 작업 전에 안내한다.

## 현재 진행 요약

- **현재 작업 — Issue #95 (2026-10-07 UTC):** PR #94 `bf4edff`를 포함한 독립 feature에서 재구성 계약과 실제 native OpenAPI를 준비했다. 독립 도메인18·HTTP/동시성15·DB6·게시3·retention1·리뷰3·binding4 총50개 GREEN을 확인했다. 최신 독립 HTTP 테스트의 PR94 baseline15 assertion RED, DB/정밀도7 assertion RED, 삭제 순서1 assertion RED 및 파일 지문을 기록했다. 신규 temporal option/variant·전체 대안·실제 시각 typed selection·legacy 호환·frozen/revision/confirmed 보호를 구현했다. D2 명시 선호 집계는 사용자 직접 승인으로 확정됐으며 제품 구현·검증은 새 세션에 인계한다. 원격 계약 commitfcde6ca와 Draft PR97을 공개했고 독립 최종 리뷰 및 결함 주입5/5탐지를 완료했다. 원본source/test지문을정확복구했다. 최종필수guard와 HEAD627e0b7의 CI37582706551 전 단계 성공을 확인했다. 이번 문서 후속 HEAD의 CI는 별도로 추적한다. 모델 호출0이며 병합·운영 배포·권한 변경은 수행하지 않는다.

- **클라우드 #91/#92·웹 #17 최신 상태 (2026-10-07):** 서버 Draft PR #94의 `06bc741`와 웹 Draft PR #18의 `3c403a2` 정확 SHA CI는 성공했다. 후속 최소 수정은 SSM 압축 해제 시 CI 실행자의 소유권과 넓은 권한을 복원하지 않도록 `umask 027`·`--no-same-owner`·`--no-same-permissions`를 적용하고 독립 정적 검사를 CI에 연결한다. 가드·Kotlin·SQL·기존 테스트는 변경하지 않는다. 실제 모델 원문 해석 9번째 호출은 HTTP200·10.1초로 기대한 5개 날짜 구간/COMPLETE/Plan B/요약과 일치했으나, 10번째 실제 HTTP·DB 배치는503으로 미완료이고 변형 문장은 미검증이다. 운영 설치·병합·배포는 진행하지 않았다.

- **현재 작업 — Issue #91:** 독립 클라우드 환경의 `/workspace/meet-me-server-91`, `feature/91-language-time-context`에서 자연어 시간 문맥 상속과 상대 날짜 기준일을 개선한다. 기준은 2026-10-07 원격 확인 및 `develop` fast-forward pull을 마친 `848b9688451551781769799ff7d27485ea5a5445`다. 기존 서버·웹의 `work` checkout과 Windows 미커밋 원본은 보존한다. 아래 사용자요청2 기록은 기존 작업의 이력이다.
- **#91 확정 계약:** 상대 날짜는 고정 제출 버전의 `createdAt`을 방 시간대로 변환한 입력별 지역 날짜에 고정한다. 평일 19~21시·이번 주 목요일 20시부터·주말 14~19시 예시는 부모 종료 21시를 상속하고 해당 목요일의 19~20시를 제외한다. 명시적 오전·오후·불가·부정·충돌을 우선하며, 부모 없는 열린 경계는 임의로 완성하지 않는다. 공개 API·DB·matcher 계약을 유지하고 입력별 기준일과 prompt를 전달한다. 원문 전체를 안전하게 해석할 수 있는 제한된 시간 표현만 adapter 내부에서 문맥 처리하고, 공급자 schema/ref/조건 검증 실패를 복구 성공으로 숨기지 않는다.
- **#91 검증 및 승인 범위:** 독립 테스트 설계, RED/GREEN, 원문 문맥 처리·합성 provider fixture와 실제 결정론적 매칭, 주 경계·서울 자정·재시도/재분석 기준일, 의도적 결함 주입, 독립 리뷰와 필수 guard를 실행한다. 합성 fixture는 유료 Gemini·운영 데이터 접근 없이 검증한다. 추가 사용자 요청에 따라 같은 모델·설정의 실제 평가도 필요하며, 기존 테스트 키와 평가 비용 상한 확인 전 호출하지 않는다. fixture 결과를 실제 모델 정확성으로 표시하지 않는다. Draft PR까지 준비하고 병합·운영 배포는 최종 승인 게이트를 유지한다. #92/웹 #17은 별도 텍스트 인계와 미확정 계약 검토 후 분리한다.

- **현재 로컬 조사 (사용자요청2):** 후보 개수뿐 아니라 자연어 의도·Gemini 시간/장소 구조화·하드 배제·선호·모호성·후보 선택·결과 한국어 문구를 종단 평가한다. 시작 시 clean `main c43a30b`, `develop` fast-forward pull 후 `5d8c32e`를 확인했다. `chore/candidate-scenario-investigation`에서 재현을 시작하고 결함 수정 범위가 확정되어 `fix/candidate-semantics`로 rename했다. 별도 worktree는 만들지 않았으며 작업 경로는 `C:/Users/jinhy/Projects/meet-me/meet-me-server` 한 개다. 이번 변경은 main/develop에서 편집하지 않는다.
- **계획/구현 경계:** 기존 A=전원 대면, B=전원 비대면, C=부분 참석 유형·유형별 최대 한 카드·시간 전체 묶음 계약을 유지한다. 합성 provider fixture 23개는 실제 adapter·매칭·result HTTP를 실행하되 repository는 mock이다. 기존 개발 키·고정 모델의 실제 호출은 전후 각 3건, 합계 6건으로 끝냈다. 실제 모델·parser/matcher/result service 검증을 PostgreSQL/Redis/브라우저 전체 E2E로 표시하지 않는다. 시간/장소 다양성 카드 재설계와 시간·장소 결합 지원 스키마는 결정 대기다.

- **현재 단계:** Issue #83은 PR #84·#86으로 main `c43a30b`에 통합됐으며 해당 SHA의 CI run #37133227810·Deploy Production run #37133378148 성공을 2026-10-04 읽기 전용으로 확인했다. 사용자요청2는 2026-10-05 직접 승인 후 Issue #87·검증 커밋 `126cd8f`·develop PR #88 게시를 완료했다. 코드 커밋 CI #37277475001과 기록 커밋 `c4a6b1d`의 CI #37277747962·Issue 연결 검사 모두 성공했다. 서버 운영 승격·배포는 웹 companion 배포 검증 확인 전 보류한다. Issue #74 운영 복구 완료 상태는 유지한다.
- **최종 검증:** 사용자요청2의 합성 matrix 23건 전후 통과, 기존 개발 Gemini 전후 각 3건 성공, 독립 assertion RED·GREEN, 결함 주입 4종 탐지와 바이트 복구를 확인했다. 최종 hook의 `ktlintCheck`·`assemble`·전체 `test`는 exit 0, 329 tests/실패 0/errors 0/skip 2다. 별도 실제 OpenAPI export도 통과했다. 기존 웹 snapshot과 생성 OpenAPI의 parsed JSON이 완전히 같으며 DTO 필드/타입/enum을 바꾸지 않았다. 생성 파일 SHA-256 `697bf2aa065b57d480308dc1dc5f530f180ed817d43693b8092f129f9815daf2`는 기존 snapshot `72ade61680b6f2e245eab9216ec168806d196e6bebb8774fc160a606e7e87c46`와 직렬화 바이트가 달라 동일 지문으로 표시하지 않는다.
- **제품 기능:** 익명 방 생명주기와 조건 제출·고정 배치·Gemini 시간·장소 그룹 구조화, Plan A/B/C·`NO_MATCH`·`PARTIAL`, 후보 조회와 멱등 확정까지 구현했다. 제출 MVP는 지도 API와 좌표 없이 장소 호환 그룹을 사용한다.
- **출시 목표:** Wanted AI Champion 심사·투표를 위해 2026-09-21부터 로그인 없이 핵심 기능을 체험할 수 있는 제출 MVP를 배포한다. Google·Kakao 소셜 로그인과 Google Calendar는 Post-MVP로 미룬다.
- **현재 차단 사항:** 최초 Issue 게시의 자동 검토 거절은 기록으로 보존한다. 2026-10-05 직접 사용자 승인 후 Issue #87 생성은 성공했다. 서버 운영 승격·배포는 부모가 웹 신규 reason의 한국어 안내 companion 배포 검증을 확인할 때까지 보류한다. 추가 자동 검토 거절이 발생하면 우회하지 않는다.
- **현재 사용자 개입:** 이 실행 대화에서 Issue·commit·push·PR·지침상 develop/main 승격·기존 운영 배포의 직접 승인을 받았다. 웹 검증 신호 이후 승인 범위로 진행한다. 새 계정·모델·서비스·권한·비밀값·인프라 추가 없이 기존 개발 설정만 사용한다. 후보 다양성과 진짜 시간·장소 결합 지원은 별도 제품·API 결정 사항이다.
- **프론트엔드 전달:** 프론트엔드는 별도 프로젝트에서 후속 구현한다. 사용자가 prototype HTML을 이미 준비했으며, 백엔드는 검증된 Swagger/OpenAPI와 필요한 화면 흐름·상태·cookie·Origin·Polling·오류 처리만 담은 `docs/FRONTEND_HANDOFF.md`를 제공한다. 별도 API 명세 문서와 prototype HTML은 이 저장소에서 만들지 않는다.
- **개발 흐름:** 선택지 B 위험도 기반 TDD 확정. 일반 변경은 엄격한 Red-Green-Refactor, 고위험 변경은 테스트 설계·구현 역할 분리
- **자율 실행 위임:** 사용자요청2의 검증된 변경을 Issue #87에 연결해 commit·push·PR·원격 CI를 진행한다. 웹 companion 검증 신호 전 서버 운영 승격·배포를 보류하고, 확인 후 직접 승인된 지침상 develop/main 승격과 기존 운영 배포를 진행한다. 추가 거절은 우회하지 않으며 로컬 원시 로그·키·인계 기록은 게시하지 않는다.
- **RED 증거:** 선택지 C 확정. Git에는 `.tdd/red/<work-item>.json`의 최소 메타데이터·테스트 지문만 추적하고 전체 실패 출력은 `.codex/tdd-evidence/<work-item>.log`에 로컬 전용으로 보관한다.
- **고위험 검증:** 트랜잭션·동시성·외부 Adapter의 의도적 결함 주입은 `.tdd/verification/<work-item>.json`에 최종 source·test 지문과 탐지 결과를 추적하고 전체 출력은 로컬 로그로 분리한다.
- **Mutation Testing:** 선택지 B로 조정. PIT는 initial commit과 Phase 1의 선행 조건에서 제외하고 시간 교집합·장소 영역·후보 점수 같은 핵심 결정론적 매칭 로직이 구현된 뒤 효과가 큰 패키지에만 선택 도입한다. 인증·트랜잭션·동시성·멱등성·외부 Adapter는 의도적 결함 주입 검증을 사용한다.
- **아키텍처 자동 검사:** 실제 패키지 경계 문제를 계기로 별도 라이브러리 없이 소스 경로·package 일치, 네 Aggregate별 `domain/application/adapter` 존재, Domain 프레임워크 독립성과 Domain·Application의 Adapter 비의존을 검사하는 회귀 테스트를 도입했다. ArchUnit·Konsist는 현재 검사 범위로 부족해질 때 재비교한다.
- **Issue·PR 작성:** 작업 전에 범위와 완료 조건을 담은 GitHub Issue를 만들고 PR 본문의 독립된 `Closes #<issue-number>` 행으로 연결한다. 프로젝트 GitHub Action은 형식과 열린 Issue 참조를 검사하고 `develop` 병합 시 연결 Issue를 `completed`로 종료한다. PR 제목은 `<type>: <summary>` 형식과 허용 type을 지키며 summary를 명사형 한국어로 끝낸다. 본문은 목적·변경 내용·검증·리뷰 요청·영향 범위를 구체적으로 적는다. 현재 위임 범위에서는 커밋·push·PR 생성 전 승인 대기 없이 진행하고 생성 직후 결과를 보고한다.
- **완료 정책:** 예상 참여 인원·제출 마감은 선택 사항이며, 둘 다 없으면 수동 마감 방식을 명시한다. 자동 조건이 있어도 주최자는 경고 후 조기 마감할 수 있다.
- **최소 인원:** 예상 참여 인원은 2명 이상이며, 모든 종료 방식에서 주최자 포함 고유 제출이 2개 미만이면 `INSUFFICIENT_PARTICIPANTS`로 종료하고 후보를 만들지 않는다.
- **비동기 전달:** PostgreSQL Transactional Outbox + Redis Streams를 구현했다. PostgreSQL 처리 lease와 상태가 기준이며 2분 Pending 회수·재발행, 총 5회 실패 후 참조형 DLQ와 명시적 수동 재처리를 사용한다.
- **장소 입력:** 별도 장소·지도·좌표 필드 없이 자연어만 사용한다. Gemini가 방 전체 명시 장소를 `AREA_n` 근접 호환 그룹과 대표 지역명으로 구조화하고 서버가 그룹 교집합을 계산한다. 이동 제약·미확정 장소는 그룹이나 좌표를 만들지 않는다.
- **Gemini 배치:** 참가자 제출·수정 중에는 호출하지 않는다. 입력 수집 종료 시점의 최신 제출을 방 전체 배치로 고정하고 자연어가 하나 이상일 때만 자연어가 있는 항목으로 논리 작업 하나를 실행한다. 전원이 정형 일정만 제출하면 AI 호출 없이 매칭하며, 제공된 자연어에 참가자별 500자·배치 합계 10,000자 제한을 적용한다. 연동은 공식 Google GenAI SDK Java 클라이언트를 AI Adapter 내부에서만 사용한다.
- **제출 MVP 인증 경계:** 모든 사용자는 로그인 없이 익명 브라우저 세션으로 이용한다. 방을 만든 `Secure HttpOnly` 불투명 세션이 첫 참여자와 `HOST` 역할을 소유하며 같은 세션만 마감·재분석·후보 확정을 수행한다. 공유 링크만으로는 참여자나 주최자 권한을 얻지 못한다.
- **Post-MVP 인증·Calendar:** Google·Kakao 로그인, RS256 Access/회전형 Refresh Token, 공급자 token 암호화와 Google Calendar 연결·방별 ON/OFF·스냅샷은 Post-MVP 백로그로 분리한다.
- **분석 실패:** 최초 1회와 Full Jitter 기반 최대 3회 기술 재시도를 수행한다. 소진 시 입력을 보존하고 `ANALYSIS_DELAYED`로 전이해 후보를 만들지 않으며, 주최자가 같은 고정 배치를 방 단위로 다시 요청할 수 있다. Gemini 응답 성공 후 개별 의미 검증 실패만 `PARTIAL`로 처리한다.
- **상태 모델:** 방 입력 수집, 고정 배치 조율 작업, 후보 품질과 최종 확정을 독립적인 PostgreSQL 상태로 관리하고 API에는 계산된 단일 진행 상태를 제공한다. 재분석은 닫힌 입력 수집을 다시 열지 않는다.
- **데이터 접근:** Komapper JDBC 7.0.0과 KSP 2.3.12를 사용한다. 도메인 Aggregate·Entity·VO, Application Command·Result, Web·외부 공급자 DTO와 영속 `*Record`를 분리하고 Komapper·KSP 타입은 Persistence Adapter 내부에만 둔다. Flyway SQL이 스키마의 기준이다. Komapper 호환을 위해 coroutine 1.11.0을 명시적으로 고정한다.
- **트랜잭션 경계:** 원자적 유스케이스의 Application 서비스 공개 메서드에 Spring `@Transactional`을 적용하고 Komapper JDBC 작업을 같은 트랜잭션에 참여시킨다. Domain은 Spring을 알지 못한다.
- **로컬·테스트 DB:** 로컬 PostgreSQL과 Redis는 Docker Compose로 실행하고 영속성 통합 테스트는 H2 없이 PostgreSQL Testcontainers를 사용한다. Domain·Application 단위 테스트는 가능한 한 DB 없이 실행한다.
- **관측성:** 제출 MVP는 Actuator·Micrometer Prometheus 지표, JSON 표준 출력, 서버 생성 상관관계 ID와 민감정보 제외 테스트까지 구현한다. Alloy·Grafana Cloud Metrics·Loki·대시보드·Alerting과 전송 자격 증명은 Post-MVP로 이관했다.
- **시간대·국제화:** MVP는 `Asia/Seoul`, `ko-KR`로 고정하되 IANA Zone ID·UTC Instant·BCP 47 locale·MessageSource 경계를 선도입한다.
- **DST 경계:** gap의 존재하지 않는 경계는 다음 유효 시각으로 이동하고 overlap은 이른 시작 offset부터 늦은 종료 offset까지 실제 구간을 보존한다.
- **후보 탐색 범위:** 주최자가 지역 날짜 범위를 선택하며, 생략하면 프론트엔드가 안내한 방 생성일 포함 14일을 서버가 적용·저장한다.
- **시간 결과:** 탐색 범위에서 자연어 조건을 실제 날짜별 구간으로 확장하고 후보별 포함 참여자의 Calendar 불가 시간을 차감한다. 별도 소요 시간으로 자르거나 제외하지 않고 남은 모든 연속 가능 구간과 MessageSource 템플릿 자연어 요약을 함께 반환하며 실제 날짜·시간 공지는 주최자가 담당한다.
- **결과 요약:** 반복 조건은 예외 날짜 수가 실제 가능 날짜 수보다 적을 때만 `패턴 + 모든 예외`로 압축한다. 명시 날짜는 날짜별 시간 구간 목록이 같으면 연속 범위를 `매일`, 비연속 날짜를 `·`, 한 날짜 복수 시간을 `또는`으로 손실 없이 묶는다.
- **시간 입력:** 제출 MVP는 ECMAScript trim 후 필수 자연어만 저장한다. 수동 요청은 생략·[]·null shim만 허용하며 nonempty 배열은 400과 새로고침·자연어 재입력을 안내한다. 수동 응답은 deprecated 항상 []다. legacy 슬롯 전용은 빈 가능 구간·미반영이며 원본·배치·인원·저장 결과는 보존한다. 내부 수동 호환 인자는 계산에 사용하지 않는다. Calendar는 Post-MVP다.
- **GitHub:** 초기 구성 PR #1과 국제화 기반 PR #3이 `develop`에 병합되었다. 기본 브랜치가 `main`이므로 PR #3의 `Closes #2`는 GitHub 기본 기능에서 무시되어 Issue #2를 수동 종료했다. 이후 `develop` 병합은 프로젝트 Action이 연결 Issue를 종료한다.
- **현재 작업:** `feature/83-natural-language-only-submission`의 커밋 준비 단계다. origin fetch 후 origin/develop은 기준 `ab7917e048fab75868c2be2417785facc1c29a4a`와 동일하며 origin/main은 병합 이력 16개 앞서지만 파일 차이는 없다. 기존 feature PR은 없다. 다음 동작은 의도한 변경만 stage→commit guard→commit→push→develop 대상 draft PR→Linux CI 확인이다. 검증 증거는 `.tdd/verification/issue-83-natural-language-only.json`을 따른다.

## 자연어 조건 제출 통일 — Issue #83

이 작업에서는 기존 실행 순서의 포괄적인 출판 위임 대신 다음 순서를 적용한다. 기존 인프라·파이프라인만 사용하며 신규 비용·자격 증명·권한·신뢰 설정 및 파괴적 데이터 변경은 수행하지 않는다.

- [x] `[AGENT]` PUT/GET 호환 shim, ECMAScript trim, legacy 안전 미반영과 결과 보존 계약을 조율자·웹과 합의한다.
- [x] `[AGENT]` 공개 Issue #83을 게시·재조회하고 깨끗한 최신 develop에서 작업 브랜치를 생성한다.
- [x] `[AGENT]` Herdr `w1:p6`의 `submission-test-designer` Codex 1개를 구성했다. 설계자는 테스트·RED 증거만 맡고 추가 에이전트를 만들지 않는다. 기존 서버 Codex는 구현, 외부 조율자는 독립 검토를 맡는다.
- [x] `[AGENT]` 합성 회귀 테스트와 유효 RED 확정: 최신 신규 100개 중 assertion 실패 56개, 기존 계약 64개 중 1개. 준비 오류는 증거에서 제외했다.
- [x] `[AGENT]` DTO·use case·검증·자연어 계산·미반영 집계·HOST nullable 원문 구현과 GREEN 확인. 과거 완료 결과를 getter에서 재분류하지 않는다.
- [x] `[AGENT]` PRD·Architecture·ADR-045·handoff 및 실제 로컬 OpenAPI 동기화. HTTP 82개·opt-in export 통과와 실제 schema/service 일치를 검토했다.
- [x] `[AGENT]` 공유 전체 ktlintCheck·assemble·test 288/실패 0/skip 1과 실제 checkout synthetic hook exit 0, 증거 포함 unittest 12개 통과.
- [x] `[SHARED]` 독립 Temp 결함 3종 검출: 수동 배열 허용 25개 중 assertion 실패 10개, FEFF trim 누락 57개 중 2개, legacy 전체 기간 확대 10개 중 3개. 원본/복구 152개 byte SHA 일치와 복구 전체 게이트 288/실패 0/skip 1을 확인했다.
- [x] `[SHARED]` 이번 기능만의 직접 사용자 출판 승인을 확인했다. 기존 게시 금지는 해당 범위에서 해제됐고 merge·deploy는 웹 검증 후 별도 신호 대기다.
- [ ] `[AGENT]` 의도한 변경과 `.tdd` JSON만 stage하고 commit guard→commit→push→develop 대상 draft PR→Linux CI 확인을 진행한다. 실제 진행 상태는 ignored 로컬 인계 기록과 Git/PR 이력을 기준으로 확인한다.
- [x] `[SHARED]` 독립 diff·테스트·실제 OpenAPI·결함 검출/복구·hook 검토 완료. 남은 blocking 구현 지적 없음.
- [ ] `[SHARED]` 실제 원격 CI 통과 후 웹 공개 새 번들 검증과 서버 develop/main 승격 신호를 확인한다. 지금 merge·deploy는 실행하지 않는다.
- [ ] `[SHARED]` 현 서버+신 웹의 raw_text-only·응답 호환과 trim·Unicode 경계 차이를 운영 GET·baseline 로컬 fixture로 확인한다. 호환 검증 후 웹 선배포→실제 확인→서버 develop→main·기존 자동 배포 순서를 따른다. 실제 운영 쓰기 검증은 하지 않는다.
- [x] `[AGENT]` 구 웹 nonempty 요청은 명시 400과 페이지 새로고침 후 자연어 재입력을 안내한다. 캐시·구 번들 잔존을 배포 순서만으로 해결됐다고 표시하지 않는다. 실제 운영 전환은 웹 검증·조율 신호 대기다.

**계약:** raw_text는 ECMAScript String.trim 문자집합으로 앞뒤만 제거한 1~500 Unicode 코드포인트이고 방 합계는 10,000이다. U+FEFF는 제거하고 U+0085는 제거하지 않는다. legacy 슬롯 전용 제출·배치·인원은 보존하되 새 계산의 가능 구간은 빈 목록이며 `LEGACY_MANUAL_ONLY_UNSUPPORTED`로 PARTIAL·미반영 집계에 반영한다. 후보가 없으면 NO_MATCH이고 HOST는 nullable 원문과 사유를 조회할 수 있다. 기존 저장 결과는 자동 재계산하지 않는다. 과거 Phase 5·6의 수동 입력 완료 기록은 당시 계약의 이력이며 신규 실행 계약은 이 작업과 ADR-045로 대체한다.

## 운영 DB 비밀번호 회전 복구 — Issue #74

- [x] `[AGENT]` 운영 재배포 성공과 앱 health 정상화를 확인한다.
- [x] `[AGENT]` Secret `AWSCURRENT` 변경 이벤트, 5분 재확인, SSM 갱신 스크립트와 배포 직렬화를 구현·검증한다.
- [x] `[AGENT]` 변경을 `develop`과 `main`에 병합하고 CI·운영 배포 성공을 확인한다.
- [x] `[AGENT]` 운영 Terraform saved plan을 검토·적용하고 EventBridge/SSM 연결 및 재확인 실행을 검증한다.

## 실행 순서

1. `[USER]` Gemini, AWS, domain처럼 계정·비용·비밀값이 필요한 제출 MVP 선행 작업을 에이전트 안내에 따라 하나씩 완료한다.
2. `[SHARED]` 배포 토폴로지, 지도 공급자와 공개 운영 전 Paid 전환처럼 비용·보안에 영향을 주는 결정을 선행 구간에서 모두 확정한다.
3. `[AGENT]` 이후 `implementation_plan.md` 순서로 Issue 생성, TDD 구현, 검증, 커밋, push와 PR 생성을 승인 대기 없이 반복한다.
4. `[USER]` Context 정리가 필요할 때만 중간 개입하며, 비밀정보 입력·결제·DNS·운영 배포 승인은 해당 시점에 직접 수행한다.
5. `[AGENT]` 제출 MVP가 배포·검증된 뒤 별도 Post-MVP 백로그에서 OAuth, Calendar와 Grafana Cloud 중앙 관측성을 진행한다.

## 구현 브랜치 로드맵

현재 문서 PR #9는 아래 구현 브랜치 수에 포함하지 않는다. 각 브랜치는 직전 PR이 `develop`에 병합된 뒤
깨끗한 작업 트리에서 최신 `develop`을 받아 생성한다.

### 제출 MVP — 6개 브랜치와 긴급 정정 1개

| 번호 | 브랜치 | 포함 단계 | 완료 결과 |
| --- | --- | --- | --- |
| 1 | `feature/core-domain-persistence` | Phase 1~2 | 핵심 도메인 모델, PostgreSQL·Flyway·Komapper 영속 경계 |
| 2 | `feature/anonymous-room-lifecycle` | Phase 3~4 | 익명 브라우저 세션, 주최자 권한, 방 생성·참여·마감 |
| 3 | `feature/submission-gemini-pipeline` | Phase 5 | 조건 제출·수정, 배치 고정, Outbox와 Gemini 구조화 파이프라인 |
| 4 | `feature/deterministic-matching-results` | Phase 6~7 | 당시 Kakao Local 장소 정규화, 결정론적 매칭, Plan A/B/C와 결과 확정. Kakao 경계는 Issue #66에서 대체 |
| 4A | `refactor/aggregate-hexagonal-structure` | 4번 이후·5번 이전 긴급 정정 | 소요 시간 제거, Aggregate 우선 패키지 재편과 아키텍처 회귀 검사 |
| 5 | `feature/reliability-observability` | Phase 8 | Redis Streams, 재시도·DLQ, 보안, 로그·지표와 rate limit |
| 6 | `feature/aws-release` | Phase 9~10 | 컨테이너·Terraform·배포, E2E와 Wanted 제출 MVP 출시 검증 |

### Post-MVP — 3개 브랜치

| 호출 번호 | 브랜치 | 포함 백로그 | 완료 결과 |
| --- | --- | --- | --- |
| PM-1 | `feature/social-auth` | PM-01 | Google·Kakao 로그인과 서비스 Access/Refresh Token |
| PM-2 | `feature/google-calendar` | PM-02 | Google Calendar 연결, 방별 ON/OFF와 불가 시간 스냅샷 |
| PM-3 | `feature/grafana-cloud-observability` | PM-03 | Alloy, Grafana Cloud Metrics·Loki, 대시보드와 Alerting |

### 번호 기반 실행 계약

- 사용자가 `1번 브랜치 작업해줘`처럼 번호로 요청하면 에이전트는 이 표의 브랜치와 범위를 그대로 사용한다.
- 에이전트는 선행 PR의 `develop` 병합과 작업 트리 상태를 확인하고, GitHub Issue 생성 → 최신 `develop` 갱신 →
  브랜치 생성 → 위험도 기반 TDD 구현 → 검증 → `implementation_plan.md` 갱신 → 커밋 → push → PR 생성을
  별도 승인 대기 없이 수행한다.
- 선행 PR이 아직 병합되지 않았거나 `develop`을 fast-forward 할 수 없으면 임의로 merge·rebase하지 않고
  차단 원인과 필요한 사용자 행동만 보고한다.
- 번호 요청은 해당 브랜치의 구현·커밋·push·PR 생성까지 위임한다. PR 병합, 결제, 실제 secret 입력,
  DNS 변경과 운영 배포 승인은 포함하지 않는다.
- 6번 브랜치는 AWS 작업 보류를 해제하는 별도 사용자 지시가 있어야 시작한다. `6번 브랜치 작업해줘`는
  AWS 작업 시작 지시로 간주하지만 실제 비용 발생·DNS 변경·운영 배포는 각 사용자 체크포인트에서 다시 확인한다.
- PM-1~PM-3은 제출 MVP 완료 후에만 시작한다. `PM-1 브랜치 작업해줘`와 같은 형식으로 호출한다.

## Phase 0 — 프로젝트 기반

### 저장소와 개발 환경

- [x] GitHub 원격 저장소를 `origin`으로 연결한다.
- [x] Spring Boot 4.1.1, Kotlin 2.3.21, Java 17 기반 Gradle 프로젝트를 구성한다.
- [x] Gradle 9.7.1 Wrapper와 Kotlin DSL을 추가한다.
- [x] ktlint와 `.editorconfig`를 구성한다.
- [x] Spring MVC, Validation, Actuator와 springdoc-openapi 기본 의존성을 구성한다.
- [x] `ko-KR` 기본 message bundle, `Accept-Language` 해석과 fallback 기반을 구성한다.
- [x] Flyway SQL 마이그레이션 기본 경로 `src/main/resources/db/migration`을 준비한다.
- [x] `[AGENT]` 실제 Secret을 넣는 로컬 `.env.local`을 Git에서 제외하고 키 이름만 있는 `.env.example`을 제공한다.
- [x] 애플리케이션 컨텍스트 기동 테스트를 추가한다.
- [x] `ktlintCheck`, `assemble`, `test`가 통과하는지 검증한다.
- [x] GitHub Issue와 사람이 읽기 쉬운 위험 기반형 Pull Request 템플릿을 구성한다.
- [x] 빈 원격 저장소의 PR 기준점용 커밋 `64e5759`를 `main`에 push한다.
- [x] `develop` 브랜치를 생성하고 원격에 push한다.
- [x] 최신 `develop`에서 `feature/initial-setup`을 생성하고 현재 초기 구성을 동일 커밋 예정 상태로 준비한다.
- [x] `develop` 직접 작업 금지와 최신 `develop`에서만 목적별 작업 브랜치를 생성하는 규칙을 문서화한다.
- [ ] `[SHARED]` GitHub 브랜치 보호 규칙과 필수 상태 검사를 합의하고 설정한다.

### 에이전트 개발 흐름

- [x] 프로젝트 `AGENTS.md`와 개발·아키텍처·문서화 규칙을 구성한다.
- [x] 세션 시작 시 프로젝트 핵심 지침을 다시 주입하는 Codex `SessionStart` 훅을 구성한다.
- [x] `git commit` 전 TDD 조건과 품질 게이트를 검사하는 Codex `PreToolUse` 훅을 구성한다.
- [x] TDD 가드 단위 테스트를 추가하고 통과시킨다.
- [x] 선택지 B 위험도 기반 TDD와 Red-Green-Refactor 절차를 개발 규칙으로 확정한다.
- [x] 고위험 변경의 범위와 테스트 설계자·구현자 책임을 정의한다.
- [x] `[AGENT]` 작업 전 GitHub Issue를 생성하고 PR 템플릿의 필수 `Closes #<issue-number>`로 연결하는 개발 흐름을 규칙과 템플릿에 반영한다.
- [x] RED 증거는 Git 추적 요약 `.tdd/red/<work-item>.json`과 로컬 원본 로그 `.codex/tdd-evidence/<work-item>.log`로 분리하는 선택지 C로 확정한다.
- [x] RED 요약 JSON Schema, 파일 수명주기와 로컬 원본 로그의 Git 제외 규칙을 구현한다.
- [ ] TDD 가드에 RED 근거·테스트 지문 검증과 프로덕션 RED용 `TODO` 차단을 구현한다.
- [x] PIT는 initial commit과 Phase 1의 품질 게이트에서 제외하고 핵심 결정론적 매칭 로직 구현 후 효과가 큰 패키지에만 선택 적용하는 선택지 B로 조정한다.
- [ ] `[DEFERRED]` 시간 교집합·장소 영역·후보 점수 구현 후 PIT 도입 실익, Kotlin 호환성과 실행 비용을 재평가한다.
- [ ] `[DEFERRED]` PIT를 채택하면 Gradle plugin 버전, 대상·제외 패키지, 통과 기준과 CI 실행 주기를 확정한다.
- [ ] 의도적 결함 주입 시나리오와 검토 결과 기록 형식을 확정한다.
- [ ] 고위험 변경의 독립 테스트 검토와 의도적 결함 주입 결과를 품질 게이트에 연결한다.
- [ ] `[USER]` Codex `/hooks`에서 현재 프로젝트 훅 정의를 검토하고 신뢰 등록한다.
- [x] `[AGENT]` CI에서 `ktlintCheck`, `assemble`, `test`를 실행하는 GitHub Actions 워크플로를 추가한다.
- [x] `[AGENT]` PR의 `Closes #<issue-number>` 형식과 열린 Issue 참조를 검사하고 `develop` 병합 시 연결 Issue를 `completed`로 자동 종료하는 GitHub Action을 추가한다.
- [x] 아키텍처 자동 검사는 initial commit과 Phase 1에서 제외하고 코드 리뷰로 의존 방향을 확인하도록 결정한다.
- [ ] `[DEFERRED]` 기능·Adapter 증가로 수동 검토 부담이 커지거나 실제 경계 위반이 발견되면 ArchUnit·Konsist의 Kotlin 호환성, 검사 범위와 유지 비용을 다시 비교한다.
- [ ] `[DEFERRED]` 후속 도구를 채택하면 `domain <- application <- adapter` 의존성 규칙을 자동 검증한다.

## User Intervention Checkpoints

- [x] `[USER]` Gemini Free Tier 개발 키를 `.env.local`과 GitHub `integration` Environment의 `GEMINI_API_KEY`에 직접 등록한다.
- [ ] `[USER]` 공개 심사 사용자의 자연어를 처리하기 전에 Gemini Paid Tier, 예산·사용량 알림과 비용 발생을 승인한다. Tier 1 전환과 합성 E2E 비용 발생 승인은 완료했고 project spend cap·사용량 알림 확인이 남아 있다.
- [x] `[AGENT]` 개발용 외부 연동 검사를 위한 GitHub `integration` Environment를 만들고 현재 PR CI에는 연결하지 않는다.
- [x] `[USER]` AWS 인프라 적용 전 IAM 사용자 MFA, `AdministratorAccess`, `aws login` 임시 CLI 세션, `ap-northeast-2`, 월 USD 80 Budget과 4개 이메일 알림을 확인·승인한다.
- [x] `[SHARED]` 제출 MVP를 EC2 `t4g.small`, ECR, RDS PostgreSQL 18 `db.t4g.micro` Single-AZ, ElastiCache Serverless for Valkey, S3 state와 SSM·Secrets Manager로 배포하도록 확정한다.
- [x] `[USER]` 운영 domain `meet-me.co.kr`을 확보하고 DNS 변경 권한을 준비한다.
- [x] `[SHARED]` 운영 origin을 `https://app.meet-me.co.kr`, `https://api.meet-me.co.kr`로 확정하고 루트 domain은 프론트엔드로 연결한다.
- [x] `[AGENT]` Terraform으로 Route 53 Hosted Zone과 ACM 인증서 검증 레코드를 만들고 가비아에 입력할 네임서버 4개를 출력한다.
- [x] `[USER]` Route 53 생성 후 가비아 기본 네임서버를 AWS 네임서버 4개로 교체한다. 그전까지는 가비아 기본 네임서버를 유지한다.
- [x] `[SHARED]` 제출 MVP에서 Kakao Local과 지도·좌표 공급자 호출을 제외하고 Gemini 장소 근접 그룹을 사용하도록 전환한다.
- [x] `[USER]` 제출 MVP에는 Kakao 앱 활성화·비즈월렛·결제 카드 등록이 필요하지 않음을 확인한다.
- [x] `[USER]` GitHub `production` Environment에 에이전트가 확정한 배포 Variable·Secret을 직접 등록하고 첫 운영 배포를 승인한다.
- [ ] `[POST-MVP][USER]` Alloy·Grafana Cloud 연동 착수 시 계정·stack·요금제와 알림 연락 채널을 선택하고 telemetry 전송 자격 증명을 실행 환경에 직접 등록한다. 제출 MVP에는 필요한 값이 없다.
- [ ] `[POST-MVP][USER]` Google Auth Platform의 운영 앱·Client ID/Secret과 Calendar scope를 준비한다.
- [ ] `[POST-MVP][USER]` Kakao Developers OIDC 앱·REST API key·Client Secret을 준비한다.
- [x] `[AGENT]` 각 사용자 작업 전에 필요한 secret 이름, 최소 권한, 서비스와 메뉴 위치, 단계별 행동, 공유 금지 값과 완료 확인 방법을 `docs/USER_INTERVENTION.md`에 안내한다.
- [x] `[AGENT]` 각 사용자 작업 후 비밀값을 출력하지 않고 설정 존재 여부와 연동 결과만 검증한다.

## Deep Interview 진행 순서

구현 전에 아래 순서로 의존성이 큰 미정 사항부터 한 번에 한 결정을 다룬다. 각 질문에는 권장안과 대안의 트레이드오프를 제시하고, 답변은 관련 Decision Gate와 문서에 즉시 반영한다.

- [x] 1차 — 주최자·참여자 관계, 예상 인원과 입력 완료 의미
- [x] 2차 — 방·참여·제출·마감·확정의 상태 전이와 동시성
- [ ] 3차 — 시간대, 탐색 기간, 슬롯 단위와 충돌 우선순위 (시간대 모델 확정)
- [x] 4차 — 위치 구조화와 장소 후보. 제출 MVP의 지도 검색·허용 영역 결정은 Issue #66에서 Gemini 장소 그룹으로 대체
- [x] 5차 — Plan A/B/C 점수, 동률, 후보 부족과 fallback
- [ ] 6차 — 로그인·게스트 본인 증명·계정 연결·권한·서비스 Refresh Token
- [ ] 7차 — Calendar 동의·조회·동기화·실패 복구
- [ ] 8차 — Gemini 스키마·배치 응답 검증과 비동기 처리 상태 (방 전체 작업·재시도·분석 지연·부분 결과·공개 범위 확정)
- [ ] 9차 — PostgreSQL 모델·트랜잭션·Redis 메시지·멱등성
- [ ] 10차 — 개인정보 보관·삭제, 보안, 관측성, 배포와 복구

## Decision Gates — 종속 구현 전 사용자 선택 필요

각 게이트는 선택지와 트레이드오프를 별도로 제시하고 사용자가 선택한 뒤 Architecture 또는 ADR에 반영한다.

### DG-01 핵심 도메인과 시간 모델

- [x] 제출 MVP 핵심 도메인을 `MeetingRoom`, `Participant`, `Submission`, `CoordinationRun` 네 Aggregate로 분리하고 생명주기와 불변식을 ADR-034로 확정한다. 소셜 `User`는 Post-MVP로 유지한다.
- [x] 방 입력 수집, 고정 배치 조율 작업, 후보 품질과 최종 확정을 독립 상태로 분리하고 API 공개 진행 상태는 이 값들에서 계산하도록 확정한다.
- [x] 예상 참여 인원·제출 마감·수동 마감 중 하나 이상을 선택하고, 자동 조건이 있어도 수동 조기 마감을 허용하는 완료 정책을 확정한다.
- [x] 예상 참여 인원과 제출 마감을 함께 설정하면 먼저 충족된 조건으로 입력 수집을 종료하도록 확정한다.
- [x] 주최자는 항상 참여자이며 예상 참여 인원에 포함하고 다른 참여자와 동일하게 조건을 제출하도록 확정한다.
- [x] 제출 원문이 PostgreSQL에 접수된 고유 참여자를 완료 인원으로 세고, 참가자별 호출 없이 수집 종료 후 방 전체 배치를 구조화하도록 확정한다.
- [x] 예상 참여 인원 `N`은 가입 정원이 아니라 자동 마감할 고유 참여자 제출 목표로 확정한다.
- [x] 입력 수집 종료 전까지 제출 수정을 허용하고, 수정본은 인원을 중복 집계하지 않으며 종료 시점의 최신 버전만 매칭하도록 확정한다.
- [x] 데드라인·참여 인원·수동 마감 또는 이들의 조합에서 가장 먼저 충족된 종료 시점 이후에는 결과 생성 전이라도 수정을 금지하도록 확정한다.
- [x] 예상 참여 인원 최소값과 후보 생성 최소 제출 인원을 주최자 포함 2명으로 확정한다.
- [x] 자동 조건 전 수동 마감은 첫 요청에 `409 EARLY_CLOSE_CONFIRMATION_REQUIRED`를 반환하고 같은 endpoint의 `confirm_early=true` 재요청에서 조건을 다시 검증하도록 확정한다.
- [x] Gemini 응답 성공 후 해석 불가능한 조건은 제외하되 독립적으로 유효한 입력은 유지하고, 의미 검증 미반영 입력이 있으면 `PARTIAL` 결과를 생성하도록 확정한다.
- [x] 마감 전에 참가자별 구조화 작업을 만들지 않고, 마감 트랜잭션이 최신 버전 배치와 논리 작업 하나를 생성하도록 확정한다.
- [x] MVP는 `Asia/Seoul`로 고정하되 방에 IANA Zone ID를 저장하고 UTC Instant·ZoneRules 기반으로 DST까지 계산 가능하게 하도록 확정한다.
- [x] 주최자가 후보 탐색 시작일·종료일을 선택하고, 둘 다 생략하면 방 생성일을 포함한 14일을 서버와 프론트엔드 기본값으로 사용하도록 확정한다.
- [x] 후보 탐색 날짜 범위의 최대 허용 길이를 31일로 확정한다.
- [x] 최종 시간 결과는 탐색 범위의 구조화된 실제 날짜별 가능 구간과 이를 묶어 표현한 자연어 요약을 함께 제공하고, 실제 날짜 공지는 주최자가 담당하도록 확정한다.
- [x] 탐색 날짜 범위를 자연어 시간 조건의 날짜별 확장과 Calendar 불가 시간 검증을 위한 근거 기간으로 확정한다.
- [x] 사용자 가능 시간과 Calendar 절대 불가 시간이 충돌하면 불가 시간을 우선 차감하도록 확정한다.
- [x] 자연어 요약은 서버 MessageSource 템플릿으로 생성하고 구조화 후보와 의미가 일치해야 하며 LLM이 생성하거나 수정하지 않도록 확정한다.
- [x] 예외 날짜 수가 실제 가능 날짜 수보다 적을 때만 `패턴 + 모든 예외`를 사용하고, 동률·예외 우세·불규칙 후보는 실제 날짜와 시간을 나열하는 손실 없는 압축 규칙을 확정한다.
- [x] LLM은 자연어를 압축된 시간 구간으로 구조화하고 Calendar·방 전용 가능·추가 불가 시간 격자 입력을 칸 단위로 생성하거나 재해석하지 않도록 확정한다.
- [x] 주최자 명시 날짜 범위에서는 실제 날짜형, 범위 생략 시에는 7일 주간 반복형 수동 가능·추가 불가 시간을 사용하고 기본 14일 탐색 범위에 확장하도록 확정한다.
- [x] 매칭은 고정 후보 슬롯 없이 연속 시간 구간 연산으로 수행하도록 확정한다.
- [ ] 가능 시간·추가 불가 시간 격자의 5분·10분 간격, 하루 표시 범위와 긴 날짜 범위 이동 방식을 선택한다.
- [x] 게스트와 Calendar 미연동 참여자의 방 전용 격자는 웬투밋식 가능 시간 선택으로, Calendar 연동 참여자의 보완 격자는 추가 불가 시간 선택으로 확정한다.
- [x] 수동 가능 시간은 선택적 부가 입력으로 두고, 빈 배열은 `가능 시간 없음`이 아니라 부가 슬롯 제약 없음으로 확정한다.
- [x] `MANUAL_AVAILABILITY`는 자연어 또는 하나 이상의 수동 가능 시간 중 하나만 있어도 제출 가능하고, 모두 없을 때만 거부하도록 확정한다.
- [x] 공백이 아닌 자연어, 현재 방의 Calendar ON, 하나 이상의 수동 가능 시간이 모두 없으면 미입력으로 보아 `SUBMISSION_INPUT_REQUIRED`로 거부하고 제출 완료 인원에 포함하지 않도록 확정한다.
- [x] Plan A/B/C를 종류별 최대 한 카드로 반환하고, Plan C는 참석 인원·공통 가능 총시간·가장 이른 시작·안정 내부 식별자 순으로 동률을 해소하며 빈 후보는 `NO_MATCH`로 반환하도록 확정한다.
- [x] 핵심 Aggregate 경계와 DST 변환 정책을 Architecture 및 ADR-034·ADR-035에 반영한다.

### DG-02 PostgreSQL 접근과 로컬 데이터베이스

- [x] JPA, Spring Data JDBC, jOOQ와 Komapper의 트레이드오프를 비교하고 Komapper JDBC를 선택한다.
- [x] 도메인 Aggregate·Entity·VO와 영속 `*Record`를 분리하고 Persistence Adapter의 명시적 Mapper로 변환하도록 확정한다.
- [x] 원자적 유스케이스의 Application 서비스 공개 메서드에 Spring `@Transactional`을 적용하도록 확정한다.
- [x] 로컬 PostgreSQL과 Redis를 Docker Compose로 제공하도록 확정한다.
- [x] 영속성 통합 테스트는 H2 없이 PostgreSQL Testcontainers를 사용하도록 확정한다.
- [x] 확정 내용을 Architecture와 ADR-028에 반영한다.

### DG-03 인증·인가와 토큰

- [x] 제출 MVP는 모든 사용자가 로그인 없이 이용하고 방 생성 익명 세션이 `HOST` 역할과 주최자 명령 권한을 소유하도록 ADR-032로 확정한다.
- [x] `[POST-MVP]` 주최자는 Google·Kakao 로그인을 필수로 하고 일반 참여자는 서비스 로그인 없이 게스트 참여를 허용하도록 확정한다.
- [x] 로그인 여부와 관계없이 참여자가 본인 최신 제출을 다시 조회·수정할 수 있게 하고, 공유 초대 링크만으로는 제출 소유권을 인정하지 않도록 확정한다.
- [x] 비로그인 참여자의 본인 증명은 서버 발급 불투명 자격 증명을 `Secure HttpOnly` 쿠키로만 전달하고 PostgreSQL에는 해시만 저장하도록 확정한다.
- [x] 게스트 자격 증명 하나를 브라우저 단위로 여러 방에서 재사용하되 `(guest browser session, room)`당 참여자 하나만 허용하도록 확정한다.
- [x] 게스트 cookie는 API 호스트 전용 `Secure HttpOnly; SameSite=Lax; Path=/api`, 고정 30일 `Max-Age`로 확정한다.
- [x] 게스트 자격 증명은 32바이트 난수와 SHA-256 digest를 사용하고 자동 회전·분실 복구 없이 만료·서버 회수 상태 및 정확한 Origin 검증으로 방어하도록 확정한다.
- [x] 짧은 JWT Access Token과 Redis TTL 기반 회전형 불투명 Refresh Token을 사용하는 방식을 선택한다.
- [x] meet-me Refresh Token은 해시·토큰 패밀리·회전 상태를 Redis에 두고 Google·Kakao 공급자 Refresh Token은 PostgreSQL에 암호화 저장하도록 책임을 분리한다.
- [ ] Google·Kakao 계정 연결 및 중복 계정 정책을 선택한다.
- [x] 제출 MVP의 세션 누락·무효는 `401`, 유효한 비주최자는 `403`, 알 수 없는 초대 코드는 `404`, 상태 충돌은 `409` ProblemDetail로 반환하도록 확정한다.
- [x] Refresh Token 원문을 저장하지 않고 매 갱신 시 회전하며 재사용 감지 시 토큰 패밀리를 폐기하도록 확정한다.
- [x] Access Token 15분, Refresh Token 미사용 14일, 토큰 패밀리 절대 30일로 수명을 확정하고 회전으로 절대 만료가 연장되지 않도록 한다.
- [x] Access Token은 로그인·갱신 response body로 발급하고 이후 `Authorization: Bearer` 헤더로 받으며, Refresh Token은 `Secure HttpOnly` 쿠키의 `Set-Cookie` 헤더로만 전달하도록 확정한다.
- [x] JWT 서명은 RS256을 사용하고 `kid` 기반으로 현재 서명 private key와 교체 중 검증 public key를 관리하도록 확정한다.
- [x] 운영 프론트엔드·API를 같은 상위 사이트의 서브도메인에 배치하고 Refresh cookie를 API 호스트 전용 `Secure HttpOnly; SameSite=Lax; Path=/api/auth`로 제한하며 `Max-Age`를 미사용 TTL과 패밀리 잔여 수명 중 짧은 값으로 설정하도록 확정한다.
- [x] Refresh·로그아웃 요청은 명시적 프론트엔드 Origin과 정확히 일치해야 하고 Origin 누락·불일치와 wildcard credential CORS를 거부하며 별도 CSRF token은 사용하지 않도록 확정한다.
- [x] 일반 로그아웃은 현재 Refresh Token 패밀리만 폐기하고 별도의 모든 기기 로그아웃은 사용자 전체 패밀리를 폐기하도록 선택지 C로 확정한다.
- [ ] 인증·토큰·개인정보 로그 마스킹 상세를 선택한다.
- [x] Redis를 meet-me Refresh Token 상태와 TTL에 사용하도록 확정한다.
- [x] 확정 내용을 Architecture와 ADR-029에 반영한다.

### DG-04 Redis 책임

- [x] 비동기 작업 전달에 PostgreSQL Transactional Outbox와 Redis Streams Consumer Group을 사용한다.
- [x] Redis Pub/Sub은 유실 가능한 실시간 보조 알림 외의 비즈니스 작업 전달에 사용하지 않는다.
- [x] Redis의 추가 책임으로 meet-me Refresh Token 해시·토큰 패밀리·회전 상태와 TTL 관리를 선택한다.
- [x] 제출 MVP의 Redis 추가 책임으로 공개 방 생성·참여·제출·주최자 명령 호출 제한을 선택하고, 별도 중복 키·분산 락·읽기 캐시는 도입하지 않는다.
- [x] Stream 메시지는 이벤트 ID와 방 전체 제출 배치 ID만 담고 원문은 Worker가 PostgreSQL에서 조회하도록 확정한다.
- [x] 작업 Stream `meetme:coordination:work:v1`, Consumer Group `coordination-workers-v1`, DLQ `meetme:coordination:dlq:v1`, 2분 Pending 회수와 PostgreSQL 재발행 경계를 선택한다.
- [x] PostgreSQL을 실패 이력의 기준으로 유지하고 Redis DLQ에는 poison message의 이벤트·배치 참조와 실패 메타데이터만 저장하도록 확정한다.
- [x] Gemini 기술적 재시도 소진은 `ANALYSIS_DELAYED`로 전이해 후보 생성 없이 ACK하고 DLQ에는 넣지 않도록 확정한다.
- [x] 주최자의 방 단위 재분석은 같은 고정 배치를 대상으로 새 Outbox 이벤트를 멱등하게 발행하도록 확정한다.
- [x] 제출 MVP에서는 `ANALYSIS_DELAYED` 자동 재시도를 하지 않고 주최자 방 단위 재분석만 허용한다.
- [x] 동일 메시지가 첫 전달을 포함해 총 5회 처리 실패하면 DLQ로 옮기고, Gemini API 재시도 횟수와 별도로 계산하도록 확정한다.
- [x] Pending 회수는 2분, DLQ는 30일·최근 10,000건 상한, 명시적 이벤트 ID 시작 인자 기반 수동 재처리로 확정한다.
- [x] 호출 제한 key에는 IP·cookie 원문 대신 SHA-256 digest를 쓰고 방 생성·비용 유발 명령은 Redis 장애 시 fail closed, 참여·제출은 fail open으로 확정한다.
- [x] Redis 유실 뒤 PostgreSQL `PUBLISHED` Outbox와 처리 lease를 기준으로 재발행해 영구 비즈니스 데이터와 작업을 복구하도록 설계·검증한다.
- [x] 확정 내용을 Architecture와 ADR-040에 반영한다.

### DG-05 Google Calendar 연동 `[POST-MVP]`

- [x] meet-me 서비스 로그인과 Google Calendar 접근 동의를 분리하되 Calendar OAuth는 로그인한 사용자만 시작할 수 있고 게스트에는 제공하지 않도록 확정한다.
- [x] 로그인한 개인의 Calendar 연결·조회와 현재 참여 중인 방의 적용을 분리하고, Calendar를 연결한 참여자 본인이 현재 방에서 ON/OFF할 수 있도록 선택지 A로 확정한다. ON은 불가 일정 외 탐색 범위를 허용한다는 뜻이며 자연어 없이 Calendar 스냅샷만으로 제출할 수 있다.
- [x] 프론트엔드는 참여자·방별 최초 Calendar ON 때 의미를 재확인하는 팝업을 한 번만 표시하고, 백엔드는 별도 확인 필드 없이 `CALENDAR` 모드를 명시적 사용 의사로 취급하도록 확정한다.
- [x] Calendar ON 제출·수정 시점의 조회 결과와 조회 완료 시각을 불변 제출 버전에 고정하고, 입력 수집 종료 시에는 Google Calendar를 다시 호출하지 않도록 선택지 A로 확정한다.
- [ ] OAuth scope, 일정 조회 기간과 동기화 방식을 선택한다.
- [ ] Google 토큰 저장·암호화·갱신·폐기 정책을 선택한다.
- [ ] 연동 실패, 권한 철회, API 할당량 초과 시 사용자 흐름을 선택한다.
- [ ] 확정 내용을 Architecture와 필요 시 ADR에 반영한다.

### DG-06 Gemini 자연어 파싱

- [ ] 후보 stable Gemini Flash 모델들의 한국어 파싱 품질, 비용과 수명주기를 비교한다.
- [x] 정확한 모델 ID를 stable `gemini-3.8-flash`로 선택한다.
- [x] 공식 SDK와 직접 HTTP Client의 트레이드오프를 비교하고 Google GenAI SDK Java 클라이언트를 선택한다.
- [x] Structured Output을 추가 필드·좌표를 금지한 4종 조건 유니온으로 확정하고 조건 단위 서버 재검증 규칙을 선택한다.
- [x] 네트워크 오류, timeout, HTTP 429와 공급자 5xx에만 자동 재시도하고 의미·도메인 검증 실패에는 재호출하지 않도록 확정한다.
- [x] 방 전체 기술적 재시도 소진 시 입력과 고정 배치를 보존하고 `ANALYSIS_DELAYED`로 전이하여 매칭하지 않도록 확정한다.
- [x] Gemini 응답 성공 후 개별 의미·스키마·도메인 검증 실패만 유효한 일정 입력 모드별 정형 시간 구간과 자연어 조건으로 `PARTIAL` 매칭을 계속하도록 확정한다.
- [x] 실패 원문은 제출 전 고지 후 결과 생성 시 참여자 표시 이름·사유와 함께 주최자에게만 공개하도록 확정한다.
- [x] 미반영 원문은 참고 정보로만 제공하고 주최자에게 다른 참여자의 수정·재처리 권한이나 책임을 부여하지 않도록 확정한다.
- [x] `PARTIAL` 확정에 별도 체크박스·모달·API 확인 필드를 두지 않고 상태가 드러나는 버튼으로 원클릭 확정하도록 확정한다.
- [x] 참가자 제출·수정 중에는 Gemini를 호출하지 않고 마감 시 자연어가 있는 최신 제출만 묶어 방 전체 배치당 논리 작업을 최대 하나 생성하도록 확정한다.
- [x] 자연어가 있는 제출만 Gemini 배치에 포함하고, 전원이 정형 일정만 제출하면 Gemini 작업 없이 결정론적 매칭을 시작하도록 확정한다.
- [x] 참가자별 자연어 최대 500 Unicode 코드 포인트와 방 전체 합계 최대 10,000 코드 포인트를 적용하고 초과 입력을 자르지 않도록 확정한다.
- [x] 최초 1회와 기술적 재시도 최대 3회, Full Jitter `0~1초`·`0~2초`·`0~4초`, 호출별 15초·전체 60초 timeout으로 확정한다.
- [x] 방 최대 참여자 50명, 입력별 최대 32조건과 Gemini UTF-8 응답 최대 256KiB를 선택한다.
- [x] 확정 내용을 Architecture와 ADR-016·ADR-018에 반영한다.

### DG-07 장소 그룹과 Post-MVP 지도·좌표

- [x] 장소 전용 입력란, 지도 선택과 사용자 기준 좌표 수집 없이 자연어만 제출하도록 확정한다.
- [x] `집`, `회사`, `학교 근처`는 장소가 아닌 이동 제약으로, 하나로 특정되지 않는 `중앙역`은 미확정 장소로 보존하고 좌표를 만들지 않도록 확정한다.
- [x] `[SUPERSEDED]` 반경 허용 영역과 Kakao Local 정규화는 ADR-043에 따라 제출 MVP에서 사용하지 않는다.
- [x] Gemini가 방 전체 명시 장소에 `AREA_n` 근접 호환 그룹과 공통 대표 지역명을 부여하도록 확정한다.
- [x] 참여자 내부 그룹은 대안 합집합, 참여자 사이는 문자열 키 교집합으로 서버가 계산하도록 확정한다.
- [x] 후보에는 대표 지역명만 제공하고 제출 MVP 위도·경도는 `null`로 반환하도록 확정한다.
- [x] 실제 좌표·이동시간 지도 공급자와 결제는 Post-MVP 재결정으로 이관한다.
- [x] 확정 내용을 PRD, Architecture와 ADR-043에 반영한다.

### DG-08 실행·배포와 운영

- [x] 참여자 제출은 PostgreSQL에 접수한 뒤 응답하고, LLM 구조화와 일정 매칭은 요청 경로 밖에서 비동기로 처리하도록 경계를 확정한다.
- [x] 비동기 작업 전달에 PostgreSQL Transactional Outbox + Redis Streams를 선택한다.
- [x] 기술적 Gemini 실패 시 프론트엔드가 무기한 로딩하지 않고 `ANALYSIS_DELAYED`와 입력 저장 완료를 표시하도록 확정한다.
- [x] `ANALYSIS_DELAYED`와 일반 처리 상태는 제출 MVP에서 상태 조회 API Polling으로 전달하고 SSE·WebSocket은 도입하지 않는다.
- [x] EC2와 ECS의 비용, 운영 복잡도와 확장성 트레이드오프를 비교하고 제출 MVP에 단일 EC2 `t4g.small`을 선택한다.
- [x] Docker Hub와 ECR을 비교하고 image digest 기반 Amazon ECR을 선택한다.
- [x] Nginx는 EC2, Redis 책임은 ElastiCache Serverless for Valkey에 배치한다.
- [x] 운영 프론트엔드와 API를 같은 상위 사이트의 서브도메인에 배치하도록 확정한다.
- [x] 운영 domain을 `meet-me.co.kr`, 프론트엔드를 `app.meet-me.co.kr`, API를 `api.meet-me.co.kr`로 확정하고 루트 domain은 프론트엔드로 연결하도록 확정한다.
- [x] Terraform state는 versioning·암호화된 S3 backend와 native lock file, 환경별 state key를 사용하고 비밀값은 state에 넣지 않는다.
- [x] GitHub OIDC와 SSM 배포, ECR image digest, Flyway 선실행, 이전 image digest 애플리케이션 롤백 순서를 선택한다.
- [x] 제출 MVP는 Actuator·Micrometer Prometheus endpoint와 JSON 표준 출력까지만 구현하고 Alloy·Grafana Cloud Metrics·Loki·Alerting은 Post-MVP로 이관한다.
- [ ] `[POST-MVP]` Gemini 배치 실패·지연, Outbox·Pending 적체와 DLQ 진입의 Grafana 알림 임계값·연락 채널을 선택한다.
- [x] 신뢰성·관측성 결정을 Architecture와 ADR-040에 반영한다.
- [x] AWS 제출 MVP 배포 결정을 Architecture와 ADR-041에 반영한다.

### DG-09 국제화

- [x] MVP 지원 locale은 `ko-KR`로 고정하되 BCP 47 locale과 Spring MessageSource 기반 i18n을 도입하도록 확정한다.
- [x] 도메인 상태·enum·API 오류 코드는 언어 중립적으로 유지하고 사용자 노출 문자열은 message bundle로 분리하도록 확정한다.
- [x] 시간대와 locale을 독립된 개념으로 다루고 제출 버전에 입력 locale을 기록하도록 확정한다.
- [ ] MVP 이후 추가할 언어의 우선순위와 번역 품질 검증 방식을 선택한다.
- [ ] 사용자 선호 locale 저장 위치와 요청별 locale 결정 우선순위를 선택한다.
- [ ] 확정 내용을 Architecture와 ADR에 반영한다.

## Phase 1 — 핵심 도메인 기반

**선행 조건:** DG-01

- [x] 핵심 용어와 `MeetingRoom`·`Participant`·`Submission`·`CoordinationRun` Aggregate 경계를 정리하고 사용자 검토를 받는다.
- [x] 모임 방식, 방 입력 수집 상태·마감 원인·조율 작업 상태·후보 품질과 참여자 역할 값 객체를 테스트부터 작성한다.
- [x] IANA Zone ID, 지역 날짜·시간, UTC Instant, 실제 날짜형·주간 반복형 시간 구간과 좌표 값 객체를 테스트부터 작성한다. 초기 소요 시간 값 객체는 4A 긴급 정정에서 제거한다.
- [x] DST gap·overlap이 있는 `America/New_York`으로 `ZoneRules` 기반 변환 테스트를 작성한다.
- [x] 모임 생성, 참여, 조건 제출 버전, 조율과 확정 상태 전이 규칙을 테스트부터 작성한다.
- [x] 코드 리뷰와 import 검사로 도메인 코드에 Spring·Komapper·외부 SDK 의존성이 없음을 검증한다.
- [x] Clock·UUID를 경계 값으로 받고 후보를 rank로 정렬하여 동일 입력이 동일한 결과를 만드는 결정론적 테스트 기반을 마련한다.

## Phase 2 — PostgreSQL과 마이그레이션 기반

**선행 조건:** DG-02

- [x] Komapper JDBC 7.0.0·KSP 2.3.12, Spring Boot 관리 PostgreSQL·Flyway·Testcontainers 의존성을 고정하고 coroutine 1.11.0 호환성을 검증한다.
- [x] 로컬·테스트·운영 프로파일의 데이터베이스 설정 경계를 구성한다.
- [x] 익명 브라우저 세션, 방별 참여자·주최자 소유권, 모임, 제출 버전, 분석 배치·시도, 후보, 최종 확정과 Outbox 초기 스키마를 설계한다. 소셜 계정·공급자 grant는 제외한다.
- [x] 최초 Flyway `V1__create_core_domain.sql` 마이그레이션을 추가한다.
- [x] 영속 `*Record`·`@KomapperEntityDef`와 도메인 모델 Mapper를 Persistence Adapter에 분리한다.
- [x] Aggregate별 outbound persistence port와 Komapper PostgreSQL adapter를 구현한다.
- [x] `CoordinationPersistenceService.persist`에 `@Transactional`을 적용하고 CoordinationRun·Outbox 원자성과 rollback을 PostgreSQL에서 통합 테스트한다.
- [x] fresh migrate·validate, 제약과 도메인–Record 왕복 매핑 통합 테스트를 추가한다.
- [x] Flyway SQL을 스키마 기준으로 명시하고 Testcontainers의 fresh migrate·checksum validate로 적용된 파일 변경을 탐지한다.

## Phase 3 — 제출 MVP 익명 세션과 권한

**관련 요구사항:** FR-000A, FR-002C, FR-007A, FR-009, FR-012
**선행 조건:** DG-02, ADR-032

- [x] 익명 브라우저 자격 증명의 발급·검증·만료·회수 흐름을 구현한다.
- [x] 자격 증명을 `Set-Cookie`로만 전달하고 응답 JSON·URL·프론트엔드 저장소에 노출하지 않는 계약 테스트를 작성한다.
- [x] PostgreSQL에 자격 증명 원문이 아닌 단방향 해시와 만료·회수 상태만 저장하는 영속성 테스트를 작성한다.
- [x] 방 생성 세션에 첫 참여자와 `HOST` 역할을 원자적으로 연결한다.
- [ ] 같은 세션만 방 마감·재분석·후보 확정을 수행하는 권한 테스트를 작성한다.
- [x] 하나의 익명 브라우저 세션이 여러 방의 참여자를 소유하고 `(browser session, room)` 중복 참여는 기존 참여자로 귀결되는 영속성·동시성 테스트를 작성한다.
- [ ] 공유 초대 링크, 누락·위조 쿠키와 다른 참여자의 세션으로 본인 입력 또는 주최자 권한을 얻지 못하는 보안 테스트를 작성한다.
- [x] 익명 세션 cookie의 `SameSite`·`Domain`·`Path`·`Max-Age`, CSRF와 허용 Origin 계약을 확정하고 테스트한다.
- [ ] `[DEFERRED]` 공개 방 생성·참여·제출·Gemini 재분석 endpoint의 Redis·Nginx 요청 제한은 5번 브랜치에서 통합 구현한다.
- [x] 인증 실패와 권한 오류의 공통 API 응답, Controller `@ApiResponse`와 DTO `@Schema`를 작성한다.
- [ ] 자격 증명과 개인정보가 로그에 남지 않는지 검증한다.

## Phase 4 — 모임 생성과 참여

**관련 요구사항:** FR-001, FR-001A, FR-001B, FR-002, FR-010A, FR-010B, FR-013
**선행 조건:** Phase 1, Phase 2, Phase 3

- [x] 모임 생성 inbound port와 애플리케이션 서비스를 테스트부터 구현한다.
- [x] 로그인 없이 방 생성을 허용하고 생성 요청의 익명 브라우저 세션에 주최자 참여를 연결하는 테스트를 작성한다.
- [x] 모임 목적과 선호 방식 입력 검증을 구현한다. 초기 소요 시간 입력은 4A 긴급 정정에서 제거한다.
- [x] 방 생성 시 주최자를 첫 번째 참여자로 같은 트랜잭션에서 생성하고 예상 참여 인원에 포함하는 테스트를 작성한다.
- [x] 예상 참여 인원을 설정할 때 2명 미만을 거부하는 검증 테스트를 작성한다.
- [x] MVP 방 생성 시 `Asia/Seoul` Zone ID를 저장하고 다른 Zone 선택을 허용하지 않는 계약 테스트를 작성한다.
- [x] `search_start_date`, `search_end_date`를 함께 선택하거나 함께 생략하도록 검증하고 한쪽만 전달하면 거부하는 테스트를 작성한다.
- [x] 탐색 날짜를 생략하면 방 시간대 생성일 기준 `[today, today + 14 days)`를 저장하고 응답하는 테스트를 작성한다.
- [x] 주최자 명시 범위와 기본 14일 적용이라는 탐색 범위 출처를 저장하고 방 응답에 제공하는 테스트를 작성한다.
- [x] 탐색 범위가 0일 이하이거나 31일을 초과하면 거부하는 테스트를 작성한다.
- [x] OpenAPI `@Schema`에 기본 14일 정책을 명시하여 프론트엔드가 방 생성 화면에 안내할 수 있게 한다.
- [x] 수동 전용 또는 예상 참여 인원·제출 마감 자동 조건을 갖는 완료 정책을 구현한다.
- [x] 자동 조건 전 수동 마감의 경고·재확인 및 주최자 권한을 구현한다.
- [x] 참여 링크는 내부 UUID와 분리한 128비트 base64url 초대 코드로 생성하도록 확정한다.
- [x] 참여 링크 생성과 조회 흐름을 구현한다.
- [x] 방 참여 유스케이스와 중복 참여 정책을 구현한다.
- [x] 일반 참여자가 서비스 로그인 없이 참여하고 자신의 제출 소유권 증명을 발급받는 흐름을 구현한다.
- [x] 모임 생성·참여 Web DTO와 Controller를 구현한다.
- [x] 성공 및 주요 오류 `@ApiResponse`, DTO `@Schema`를 작성한다.
- [x] 도메인·애플리케이션·Web·영속성 테스트를 각각 검증한다.
- [x] 생성된 OpenAPI 계약을 검증한다.

## Phase 5 — 참여자 조건 제출과 자연어 구조화

**관련 요구사항:** FR-004A, FR-005, FR-006, FR-007, FR-007A, FR-008, FR-008A, FR-008B, FR-008C, FR-009, FR-013, FR-014
**선행 조건:** DG-06, Phase 2, Phase 4

- [x] 제출 MVP의 nullable `raw_text`와 `manual_available_times` JSON 요청 계약을 확정한다. 자연어 또는 하나 이상의 가능 시간 중 하나를 요구하고 빈 가능 시간은 부가 제약 없음으로 처리한다.
- [x] 자연어와 수동 가능 시간이 모두 없는 요청을 `SUBMISSION_INPUT_REQUIRED`로 거부하고 제출 버전과 완료 인원을 만들지 않는 API·영속성 테스트를 작성한다.
- [x] 제출 MVP API가 `CALENDAR`, `calendar_blocked_times`와 `additional_blocked_times`를 노출하거나 수락하지 않는 계약 테스트를 작성한다.
- [x] 명시 범위에서는 실제 날짜형만, 기본 범위에서는 주간 반복형만 수동 가능 시간으로 허용하는 검증을 구현한다.
- [x] 수동 격자의 연속·중첩 선택 구간 병합과 범위·요일·시작·종료 검증 계약을 확정한다.
- [x] 구조화된 시간 조건, 특정 가능한 장소, 이동 제약과 미확정 장소의 내부 스키마 및 검증 규칙을 확정한다.
- [x] 방 Zone ID와 제출 BCP 47 locale을 AI Adapter 문맥으로 전달하고 언어 중립 Structured Output으로 변환하는 계약을 테스트한다.
- [x] 자연어 파싱 outbound port와 공급자 독립 실패 타입을 정의한다.
- [x] nullable 자연어와 정형 일정 입력의 원본 제출 버전 및 최신 참조를 저장하되 Gemini를 호출하지 않고 즉시 응답하는 접수 API를 구현한다.
- [x] 본인 증명을 거쳐 자신의 nullable `raw_text`, 수동 가능 시간과 수정 가능 여부를 조회하는 API를 구현한다.
- [x] 익명 참여자는 본인 입력만 조회하고 주최자 역할·공유 링크·타인 자격 증명으로 다른 입력을 조회할 수 없는 계약·권한 테스트를 작성한다.
- [x] 제출 접수 트랜잭션 커밋 시 고유 참여자 완료 인원을 증가시키고 예상 인원 도달 시 입력 수집을 종료하는 테스트를 작성한다.
- [x] `N`번째와 후속 제출이 동시에 도착해도 정확히 `N`개의 고유 참여자 제출만 접수되고 나머지는 종료 오류가 되는 동시성 테스트를 작성한다.
- [x] 입력 수집 종료 트랜잭션이 종료 시점의 최신 버전을 방 전체 제출 배치로 고정하고, 자연어가 있을 때만 논리 작업과 Outbox 이벤트를 하나 만드는 테스트를 작성한다.
- [x] 수집 중 수정본을 불변 버전으로 저장하고 고유 참여자 완료 인원은 증가하지 않는 테스트를 작성한다.
- [x] 입력 수집 종료 시점의 최신 버전만 매칭 입력으로 고정하고 이후 수정은 거부하는 테스트를 작성한다.
- [x] 수정과 종료의 동시 요청에서 커밋 순서에 따라 수정본 포함 또는 종료 오류 중 하나만 성립하는 동시성 테스트를 작성한다.
- [x] 이벤트 ID와 방 전체 제출 배치 ID만 Outbox에 기록하고 처리 서비스가 PostgreSQL에서 원문을 조회하는 비동기 경계를 구현한다.
- [x] Gemini adapter의 Structured Output 계약 테스트를 작성한다.
- [x] 참가자 제출·수정 중에는 호출하지 않고 자연어가 있는 제출만 방 전체 논리 작업 하나에 포함되는지 테스트한다.
- [x] 수동 슬롯만 있는 참여자는 AI 요청에서 제외하되 매칭에는 포함되고, 전원이 수동 슬롯만 제출한 방은 Gemini 작업을 만들지 않는지 테스트한다.
- [x] 참가자별 500자·배치 합계 10,000자 경계와 초과 입력 무절단 거부를 테스트한다.
- [x] 최초 1회와 최대 3회 기술 재시도, Full Jitter 범위, `Retry-After`, 호출별 15초·전체 60초 timeout을 가상 시계와 주입 가능한 난수로 테스트한다.
- [x] 기술적 재시도 소진 시 입력과 고정 배치를 보존하고 `ANALYSIS_DELAYED`로 전이하며 후보와 주최자용 원문 공개를 만들지 않는 테스트를 작성한다.
- [x] 방 생성 익명 세션의 재분석 요청이 같은 고정 배치에 새 Outbox 이벤트를 멱등하게 만들고 참여자 재입력을 요구하지 않는 API를 구현·테스트한다.
- [x] 배치 응답의 불투명 입력 참조값과 개수가 요청과 정확히 일치하는지 검증한다.
- [x] Gemini 결과를 서버 스키마로 재검증하고 잘못된 출력을 거부한다.
- [x] 조건 단위 검증으로 유효한 자연어 조건과 수동 가능 시간은 유지하고 해석 불가능한 조건만 제외하는 테스트를 작성한다.
- [ ] `[BRANCH 4]` 성공한 Gemini 응답의 미반영 조건을 후보 집합 `PARTIAL`과 공개 `READY_WITH_WARNINGS`로 연결한다.
- [x] `집/회사/학교 근처`가 이동 제약으로, 하나로 특정되지 않는 지명이 미확정 장소로 분류되는 계약 테스트를 작성한다.
- [x] LLM이 만든 좌표를 거부하는 테스트를 작성한다. `[BRANCH 4]` Geo Adapter 고유성 검증은 Kakao 정규화와 함께 구현한다.
- [x] 원본 제출 메타데이터와 정형 조건을 PostgreSQL에 저장한다. `[BRANCH 4]` 지도 검색 정규화 스냅샷을 추가한다.
- [x] 다른 참여자에게 개별 조건이 노출되지 않는지 API 테스트한다.
- [x] AI 호출 횟수, 토큰과 추정 비용 메타데이터를 기록한다.
- [x] 민감한 원문·좌표·공급자 응답이 로그와 Outbox에 남지 않는지 테스트한다.
- [x] Controller `@ApiResponse`, DTO `@Schema`와 OpenAPI 계약을 검증한다.

## Phase 6 — 결정론적 일정·장소 매칭

**관련 요구사항:** FR-004A, FR-010, FR-011, FR-011A
**선행 조건:** DG-01, DG-07, Phase 1, Phase 5

- [x] 시간 구간 정규화와 교집합 계산 테스트를 작성한다.
- [x] 수동 가능 시간이 있으면 자연어 또는 중립 기준 구간과 교차하고, 빈 배열이면 자연어 기준 구간을 유지하며, 자연어와 슬롯이 모두 없으면 제출을 거부하는 테스트를 작성한다.
- [x] 자연어 요일·시간 조건을 방 시간대와 탐색 범위의 실제 날짜별 구간으로 확장하는 테스트를 작성한다.
- [x] 후보가 방의 `[searchStartDate, searchEndDate)` 지역 날짜 범위를 벗어나지 않는 테스트를 작성한다.
- [x] 고정 슬롯 양자화나 최소 길이 필터 없이 남은 모든 연속 가능 구간을 보존하는지 테스트한다.
- [x] `[LEGACY][POST-MVP 후보]` 좌표 간 거리와 반경 허용 영역 계산 테스트를 보존한다.
- [x] 한 참여자의 Gemini 장소 그룹 대안 합집합 테스트를 작성한다.
- [x] 여러 참여자의 장소 그룹 교집합과 교집합 부재 테스트를 작성한다.
- [x] `봉천역`과 `서울대입구역`이 같은 호환 그룹이면 공통 대면 후보를 만드는 회귀 테스트를 작성한다.
- [x] 이동 제약 또는 미확정 장소만 있는 조건 분기가 오프라인 후보를 만들지 않는 테스트를 작성한다.
- [x] 전원 참석·오프라인 Plan A 계산을 구현한다.
- [x] 전원 참석·온라인 Plan B 계산을 구현한다.
- [x] 전원 교집합이 없을 때 `N-1`, `N-2` 순의 Plan C 계산을 구현한다.
- [x] 합의한 점수 계산과 동률 정렬 규칙을 구현한다.
- [x] 동일 입력 반복 실행의 결과가 동일한지 속성/회귀 테스트한다.
- [x] 매칭 도메인에서 LLM과 외부 SDK를 호출하지 않는지 검증한다.
- [x] 구조화 후보에서 MessageSource 기반 자연어 요약을 생성하고 제외된 날짜·시간을 가능하다고 표현하지 않는 의미 일치 테스트를 작성한다.
- [x] 반복 조건에서 예외 수가 실제 가능 날짜 수보다 적으면 패턴과 모든 예외를, 동률 또는 예외가 더 많으면 실제 날짜 목록을 선택하는 경계 테스트를 작성한다.
- [x] 일부 시간만 남은 날짜를 예외로 표시하고 특정 날짜·불규칙 후보를 실제 날짜 목록으로 표시하는 테스트를 작성한다.
- [x] 동일 시간 목록의 연속 날짜를 `시작일부터 종료일까지 매일`, 전부 비연속인 날짜를 `·`, 한 날짜의 복수 시간을 `또는`으로 압축하고 월 경계를 손실 없이 표시한다.
- [x] 예상 인원 충족, 제출 마감과 수동 마감이 경합해도 매칭을 한 번만 시작하도록 구현한다.
- [x] 데드라인·수동 마감 시 고유 제출이 2개 미만이면 `INSUFFICIENT_PARTICIPANTS`로 종료하고 후보를 생성하지 않는 테스트를 작성한다.
- [x] 의미 검증에서 미반영 입력이 있으면 후보 집합을 `PARTIAL`로 만들고 반영·전체 제출 수와 미반영 입력 수를 저장하되, `ANALYSIS_DELAYED`에서는 매칭하지 않는 테스트를 작성한다.
- [x] 후보를 PostgreSQL에 저장하고 조회 유스케이스를 구현한다.
- [x] 후보 조회 API가 언어 중립 구조화 결과와 locale별 자연어 요약을 함께 제공하도록 구현하고 OpenAPI 계약을 검증한다.

## Phase 7 — 최종 결과 확정과 공유

**관련 요구사항:** FR-008B, FR-008C, FR-009, FR-012, FR-012A  
**선행 조건:** Phase 6

- [x] 주최자만 후보를 확정할 수 있는 권한 테스트를 작성한다.
- [x] 주최자용 부분 결과에만 참여자 표시 이름, 실패 원문과 미반영 사유를 제공하는 API를 구현한다.
- [x] 미반영 원문을 안전한 일반 텍스트로 반환하고 다른 참여자용 결과에는 포함하지 않는 보안 테스트를 작성한다.
- [x] 제출 전에 실패 원문의 주최자 공개 가능성을 고지할 수 있도록 API 계약에 공개 정책을 명시한다.
- [x] 다른 참여자의 원문 수정·재처리 API와 별도 부분 결과 확인 필드가 존재하지 않는지 계약 테스트로 검증한다.
- [x] 후보가 현재 모임에 속하고 아직 유효한지 검증한다.
- [x] 중복 확정과 동시 요청 처리 정책을 선택하고 구현한다.
- [x] 최종 선택을 PostgreSQL에 원자적으로 저장한다.
- [x] 참여자용 확정 결과 조회 API를 구현한다.
- [x] 주요 충돌·권한 오류 `@ApiResponse`와 DTO `@Schema`를 작성한다.
- [x] 확정 및 결과 조회 OpenAPI 계약과 통합 테스트를 검증한다.

## 긴급 정정 4A — 소요 시간 제거와 Aggregate 우선 패키지 재편

**관련 Issue:** #18
**삽입 위치:** 4번 `feature/deterministic-matching-results` 병합 후, 5번 `feature/reliability-observability` 시작 전

- [x] 방 생성 요청·응답, `MeetingRoom`, 영속 Record·Mapper와 매칭 함수에서 소요 시간 값을 제거한다.
- [x] 교집합·차감 뒤 남은 모든 연속 가능 구간을 길이와 관계없이 후보에 포함하는 회귀 테스트를 통과한다.
- [x] 기존 V1~V4를 수정하지 않고 V5 Flyway 마이그레이션으로 `duration_minutes`를 제거하며 기존 방 데이터 보존을 검증한다.
- [x] `meetingroom`, `participant`, `submission`, `coordination`을 최상위 Aggregate 패키지로 두고 각 패키지 아래에 `domain/application/adapter`를 배치한다.
- [x] Aggregate별 persistence port·Record·Mapper·adapter를 해당 Aggregate로 이동하고 전역 최상위 `domain/application/adapter` 디렉터리를 프로덕션·테스트 모두에서 제거한다.
- [x] 소스 경로·package 일치, Aggregate별 계층 존재, Domain 프레임워크 독립성과 Domain·Application의 Adapter 비의존을 아키텍처 테스트로 고정한다.
- [x] PRD, Architecture, ADR-039와 이 구현 계획을 변경된 제품·기술 결정에 맞게 동기화한다.

## Phase 8 — Redis, 신뢰성, 보안과 관측성

**선행 조건:** DG-04 및 실제 사용 목적이 발생한 기능 단계

- [x] 선택한 책임에 한해 Redis port와 adapter를 구현한다.
- [x] PostgreSQL Outbox 저장·relay와 Redis Streams Consumer Group adapter를 구현한다.
- [x] Outbox 재발행과 Stream 중복 전달에도 LLM 호출·매칭 상태 전이가 중복 실행되지 않도록 멱등성 테스트를 작성한다.
- [x] Stream Pending 작업 회수, 보류·실패 처리와 trimming 정책을 구현하고 장애 복구를 테스트한다.
- [x] 기술적 재시도 소진으로 ACK된 `ANALYSIS_DELAYED` 작업과 역직렬화 실패·불변식 위반·반복 Worker crash 같은 poison message를 구분하는 테스트를 작성한다.
- [x] 동일 메시지 총 5회 실패 시 PostgreSQL 영구 실패 상태를 먼저 기록하고 원문 없는 참조형 Redis DLQ로 옮기는 흐름을 구현·테스트한다.
- [x] Actuator·Micrometer 애플리케이션 지표와 표준 출력 JSON 구조화 로그를 구현한다.
- [ ] `[POST-MVP]` Alloy가 Prometheus 지표와 JSON 로그를 필터링해 Grafana Cloud Metrics·Loki로 보내도록 구성한다. PM-03에서 수행한다.
- [ ] `[POST-MVP]` Grafana-managed Alerting 규칙과 대시보드를 코드 또는 재현 가능한 설정으로 관리한다. PM-03에서 수행한다.
- [x] Redis 장애와 데이터 유실이 영구 데이터 유실로 이어지지 않는지 테스트한다.
- [x] `ANALYSIS_DELAYED` 방의 입력·고정 배치가 Redis 유실과 프로세스 재시작 뒤에도 PostgreSQL에서 복구되는지 테스트한다.
- [x] 중복 제출과 중복 매칭 실행의 멱등성 전략을 구현한다.
- [x] 외부 API별 기존 timeout·제한 재시도를 유지하고 제출 MVP에는 회로 차단기를 추가하지 않기로 선택한다.
- [x] 요청 상관관계 ID와 구조화 로그를 구성한다.
- [x] 로그 마스킹과 민감정보 회귀 테스트를 추가한다.
- [x] 미반영 원문이 로그, 지표와 Redis Stream 메시지 payload에 포함되지 않는지 검증한다.
- [x] 인증 실패, 외부 API 지연·오류, 매칭 시간과 결과 수 지표를 추가한다.
- [x] 논리 배치별 AI 비용의 USD·보수적 KRW 추정과 10원 미만 지표를 구현한다.
- [x] 제출 MVP 일정·좌표·게스트 자격 증명의 30일 보관 및 삭제 정책을 구현한다. Post-MVP 공급자 token은 PM-01·PM-02에서 별도로 확정한다.

## Phase 9 — 로컬 실행, 인프라와 배포

**선행 조건:** DG-08

- [x] 합의한 로컬 PostgreSQL·Redis 실행 구성을 추가한다.
- [x] 애플리케이션 컨테이너 이미지와 비루트 실행 설정을 추가한다.
- [x] Nginx TLS 종료, 라우팅, 요청 제한과 헬스체크 범위를 구현한다.
- [x] Terraform 모듈과 환경 구성을 설계하고 검토받는다.
- [x] Route 53 Hosted Zone, `app`·`api` DNS 레코드와 ACM 인증서 검증을 Terraform으로 구현한다.
- [x] RDS for PostgreSQL과 네트워크 구성을 Terraform으로 구현한다.
- [x] 선택한 EC2/ECS 런타임을 Terraform으로 구현한다.
- [x] 선택한 Redis 운영 배치를 구현한다.
- [x] 이미지 레지스트리 인증과 배포 파이프라인을 구현한다.
- [ ] Flyway 실행 순서, 배포 실패와 롤백 절차를 검증한다.
- [x] 비밀정보가 저장소, 이미지, Terraform state와 CI 로그에 노출되지 않는지 검증한다.
- [x] 운영 헬스체크, 로그, 지표와 알림을 검증한다.

## Phase 10 — Wanted 제출 MVP 통합 검증과 출시 준비

- [x] 로그인 없이 익명 세션 발급 → 방 생성 → 주최자 참여 등록 → 링크 공유 흐름을 E2E 검증한다.
- [x] 익명 참여자의 자연어 전용·수동 슬롯 전용·두 입력 조합 제출과 본인 최신 입력 복원 흐름을 E2E 검증한다.
- [x] 공유 링크·다른 세션·누락 또는 위조 쿠키로 주최자 명령과 타인 입력에 접근하지 못하는지 E2E 검증한다.
- [x] 제출 MVP에 Google·Kakao 로그인과 Calendar OAuth endpoint가 노출되지 않는지 검증한다.
- [x] 마지막 제출 → Plan A/B/C 생성 → 호스트 확정 → 결과 조회를 E2E 검증한다.
- [x] 정상 반영된 조건의 블라인드 입력과 미반영 원문의 주최자 한정 예외가 유지되는지 보안 관점에서 검증한다.
- [x] Gemini 방 전체 배치별 논리 작업, Full Jitter 기술 재시도 범위와 실제 비용 기록을 운영과 유사한 환경에서 검증한다.
- [x] 파싱 실패 → `PARTIAL` 후보 → 주최자 미반영 원문 확인 → 최종 확정 흐름을 E2E 검증한다.
- [x] Gemini 기술적 실패 → `ANALYSIS_DELAYED` → 로딩 종료 → 주최자 재분석 → 결과 생성 흐름과 타인 원문 비공개를 E2E 검증한다.
- [x] 장소 그룹 대안 합집합·참여자 교집합, 그룹 불일치 제외와 이동 제약·미확정 장소의 Plan B/C fallback을 검증한다.
- [x] Swagger/OpenAPI가 구현 응답과 일치하는지 전체 검증한다.
- [x] 실행 환경의 Swagger UI와 `/v3/api-docs`가 공개 API 계약을 완전하게 제공하는지 검증한다.
- [x] 화면 흐름, 공개 상태 전이, 익명 cookie·Origin, Polling 중단 조건과 오류 코드 처리만 담은 `docs/FRONTEND_HANDOFF.md`를 작성한다.
- [x] Swagger/OpenAPI와 `docs/FRONTEND_HANDOFF.md`의 endpoint·상태·오류 코드가 일치하는지 교차 검증한다.
- [x] `[USER]` 프론트엔드 AI에 함께 전달할 prototype HTML을 별도 산출물로 준비한다.
- [ ] `ko-KR` message bundle, 지원하지 않는 locale fallback, 언어 중립 오류 코드와 시간대 직렬화를 통합 검증한다.
- [ ] 주요 개인정보·토큰·좌표가 로그와 오류 응답에 노출되지 않는지 점검한다.
- [ ] 성능 목표와 예상 동시 사용자 부하를 합의하고 부하 테스트한다.
- [ ] 백업, 복구, 마이그레이션과 롤백 리허설을 수행한다.
- [ ] MVP 완료 조건을 PRD 10절과 대조하여 전부 확인한다.
- [ ] 미완료 TBD와 Post-MVP 항목이 아래 별도 백로그에 남아 있는지 확인한다.
- [ ] 출시 승인 체크리스트와 운영 인계 문서를 완료한다.

## Post-MVP — 인증, Calendar와 중앙 관측성

### PM-01 Google·Kakao 로그인과 서비스 토큰

**관련 요구사항:** FR-002A, FR-002B, FR-002D
**선행 조건:** 제출 MVP 출시, DG-03

- [ ] Google·Kakao 개발·운영 OAuth 앱, scope, callback과 계정 연결 정책을 확정한다.
- [ ] Google과 Kakao 로그인 adapter 계약 테스트를 작성하고 구현한다.
- [ ] 공급자 사용자 식별자와 서비스 사용자 연결 유스케이스를 구현한다.
- [ ] RS256 Access Token과 Redis TTL 기반 회전형 Refresh Token의 발급·갱신·폐기를 구현한다.
- [ ] 현재 기기·모든 기기 로그아웃과 공급자 grant 유지 계약을 구현한다.
- [ ] 로그인 주최자만 방 생성·재분석·확정을 수행하도록 전환한다.
- [ ] 익명 방·참여자 소유권을 로그인 계정에 연결하거나 유지하는 마이그레이션 정책을 확정한다.
- [ ] OAuth/JWT/Refresh cookie OpenAPI·보안·로그 마스킹 테스트를 검증한다.

### PM-02 Google Calendar 불가 시간 수집

**관련 요구사항:** FR-003, FR-003A, FR-004, FR-004B
**선행 조건:** PM-01, DG-05

- [ ] Calendar 조회 outbound port의 내부 모델과 실패 타입을 정의하고 adapter 계약 테스트를 작성한다.
- [ ] 최소 scope로 대상 Calendar의 FreeBusy를 조회하고 절대 불가 시간으로 변환한다.
- [ ] Calendar 연결·조회와 방별 ON/OFF를 분리한다.
- [ ] Calendar ON 제출·수정 시점의 정규화 불가 시간과 조회 완료 시각을 불변 제출 버전에 저장한다.
- [ ] 종일·반복·취소 일정, 시간대, 빈 일정과 공급자 실패를 구분한다.
- [ ] 입력 수집 종료에서는 공급자를 다시 호출하지 않고 최신 제출 스냅샷만 사용하는지 검증한다.
- [ ] 권한 철회, 만료 토큰, 할당량·일시 장애와 공급자 token 암호화를 구현한다.
- [ ] 제출·매칭·E2E와 OpenAPI에 Calendar 모드를 추가하고 익명 사용자의 OAuth를 거부한다.

### PM-03 Grafana Cloud 중앙 관측성

**선행 조건:** 제출 MVP 출시, 운영 컴퓨팅·네트워크 토폴로지 확정

- [ ] `[USER]` Grafana Cloud 계정·stack·요금제, 최소 쓰기 권한 Access Policy와 알림 연락 채널을 준비한다.
- [ ] Alloy를 운영 런타임에 배치하고 비밀값 없는 설정 템플릿과 환경별 주입 경계를 구현한다.
- [ ] Prometheus 지표를 Grafana Cloud Metrics로, 필터링한 JSON 로그를 Loki로 전송한다.
- [ ] Gemini 지연·실패, Outbox 적체, Redis Pending 노후화와 DLQ 진입 대시보드·Alerting을 재현 가능한 설정으로 관리한다.
- [ ] 원문·좌표·토큰·쿠키·API key가 원격 telemetry와 label에 포함되지 않는지 검증한다.
- [ ] Metrics·Logs 사용량과 보존 기간, 알림 임계값과 비용 상한을 확정한다.

## PRD 요구사항 추적

| 요구사항 | 구현 단계 | 상태 |
| --- | --- | --- |
| FR-000A 제출 MVP 익명 접근·주최자 권한 | Phase 3, 4, 10 | 핵심 주최자 명령 완료, 공개 배포 검증 남음 |
| FR-001 방 생성 | Phase 4 | 완료 |
| FR-001A 주최자의 참여자 등록·예상 인원 포함 | Phase 1, 4 | 완료 |
| FR-001B 주최자 선택 탐색 범위와 기본 14일 표시 | Phase 4, 6 | 완료 |
| FR-002 참여 링크 | Phase 4 | 완료 |
| FR-002A 주최자 필수 Google·Kakao 로그인 | Post-MVP PM-01 | Post-MVP |
| FR-002B 서비스 계정 연결 | Post-MVP PM-01 | Post-MVP |
| FR-002C 익명 참여자 본인 증명 | Phase 3, 4, 5 | 완료 |
| FR-003 로그인 사용자 Calendar 연동 | Post-MVP PM-02 | Post-MVP |
| FR-003A Calendar 연결과 방별 ON/OFF 적용 분리 | Post-MVP PM-02 | Post-MVP |
| FR-004 Calendar 불가 시간 변환 | Post-MVP PM-02 | Post-MVP |
| FR-004B Calendar ON 제출 시점 스냅샷 고정 | Post-MVP PM-02 | Post-MVP |
| FR-004A 제출 MVP 수동 가능 시간 격자 | Phase 1, 4, 5, 6 | 완료 |
| FR-005 선택적 자연어 조건과 슬롯 전용 제출 | Phase 5 | 완료 |
| FR-006 위치 표현 분류와 검증된 장소 정규화 | Phase 5, 6 | 완료 |
| FR-006A 이동 제약·미확정 장소의 좌표 생성 금지 | Phase 5, 6 | 완료 |
| FR-007 1-Pass Payload | Phase 5 | 완료 |
| FR-007A 본인 최신 제출 조회 | Phase 3, 5 | 완료 |
| FR-008 방 전체 제출 배치별 논리 파싱 작업·입력 제한·기술 오류 재시도 | Phase 5, 8 | 완료 |
| FR-008A 수집 종료 전 수정과 최신 버전 매칭 | Phase 5 | 완료 |
| FR-008B 기술 실패 분석 지연·의미 실패 부분 결과 | Phase 5, 6, 7, 8 | 완료 |
| FR-008C 주최자 방 단위 재분석 | Phase 3, 5, 8 | 완료 |
| FR-009 참여자별 조건 비공개 | Phase 3, 5, 7 | 완료 |
| FR-010 완료 조건 후 매칭 | Phase 4, 5, 6 | 완료 |
| FR-010A 주최자 수동 조기 마감 | Phase 4 | 완료 |
| FR-010B 최소 2명 제출 전 후보 생성 금지 | Phase 4, 6 | 완료 |
| FR-011 Plan A/B/C | Phase 6 | 완료 |
| FR-011A 구조화 후보·자연어 요약 | Phase 6, 4A | 모든 연속 가능 구간 반환으로 정정 완료 |
| FR-012 주최자 최종 확정 | Phase 3, 7 | 완료 |
| FR-012A 부분 결과의 무수정·무추가확인 원클릭 확정 | Phase 7 | 완료 |
| FR-013 IANA Zone ID와 UTC 기반 시간 모델 | Phase 1, 4, 5 | 도메인·DST 기반 완료 |
| FR-014 i18n과 언어 중립 API 코드 | Phase 0, 3, 5, 6 | 완료 |

## 운영 결함 수정

- [x] `[AGENT]` Issue #62: Gemini 종료 `24:00`을 다음 지역 날짜 시작의 배타적 경계로 보존하고, 조건 단위 시간 검증 실패 격리·내부 JSON 왕복·DST 확장을 회귀 테스트한다.
- [ ] `[USER]` 진단 출력에 노출된 운영 Gemini API key와 RDS 관리형 master password를 회전한다.
- [ ] `[SHARED]` Issue #62를 `main`까지 반영해 자동 배포한 뒤 기존 `ANALYSIS_DELAYED` 방을 재분석하고 운영 결과를 검증한다.

## 진행 기록

| 날짜 | 변경 내용 | 검증/근거 | Commit/PR |
| --- | --- | --- | --- |
| 2026-09-04 | 구현 계획 최초 작성. 현재 프로젝트 기반과 Codex 훅 진행 상태 반영 | PRD, Architecture, ADR, 저장소 상태 대조 | 동일 커밋 예정 |
| 2026-09-04 | Flyway SQL 마이그레이션 기본 디렉터리 준비 | `src/main/resources/db/migration/.gitkeep` 확인 | 동일 커밋 예정 |
| 2026-09-05 | 외부 계정·권한·결제·동의·비밀정보의 사용자 개입 규칙과 행동 가이드 체크포인트 추가 | `user-intervention.md`, AGENTS, 프로젝트 스킬과 계획 간 일치 확인 | 동일 커밋 예정 |
| 2026-09-05 | 선택지 B 위험도 기반 TDD 채택. Red-Green-Refactor, 고위험 역할 분리와 TDD 가드 강화 설계 반영 | `development.md`, SessionStart 지침과 실행 계획 대조 | 동일 커밋 예정 |
| 2026-09-05 | 선택적 예상 참여 인원·제출 마감·수동 마감 정책과 비동기 처리 경계 반영. Redis 작업 전달 책임은 선택 대기 | PRD, Architecture, 계획의 요구사항·TBD 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 비동기 작업 전달에 PostgreSQL Transactional Outbox + Redis Streams 선택, Pub/Sub 사용 경계 확정 | Architecture, ADR-011과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 자연어 전용 장소 입력, 특정 가능한 장소의 허용 영역 교집합, 이동 제약·미확정 장소의 좌표 생성 금지 확정 | PRD, Architecture, ADR-012와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 주최자는 항상 첫 번째 참여자이며 예상 참여 인원에 포함하고 조건 제출 대상임을 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | PostgreSQL 접수 완료를 예상 인원 충족 기준으로 삼고, 접수 마감 후 방 전체 구조화와 매칭을 분리 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 예상 참여 인원은 가입 정원이 아닌 고유 참여자 제출 목표이며 N번째 제출까지만 원자적으로 접수하도록 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 입력 수집 종료 전 자유 수정, 종료 시점 최신 버전 고정과 이후 수정 금지 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 일시적 파싱 오류 재시도, 유효 조건 기반 부분 결과와 실패 원문의 주최자 한정 공개 확정 | PRD, Architecture, ADR-013과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 부분 결과의 미반영 원문은 참고용으로만 제공하고 주최자 수정·재처리·추가 확인 없이 원클릭 확정하도록 확정 | PRD, Architecture, ADR-013과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 예상 참여 인원과 후보 생성의 최소 제출 인원을 주최자 포함 2명으로 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | MVP `Asia/Seoul`·`ko-KR` 고정과 IANA Zone ID·UTC·BCP 47·MessageSource 기반 확장 경계 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 주최자 선택 후보 탐색 날짜 범위와 생략 시 오늘부터 14일 기본값·프론트엔드 표시 의무 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 후보 탐색 범위 최대 31일 확정. 최종 결과의 구체 날짜·시간과 반복 요일·시간 패턴 지원 범위는 결정 대기 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 탐색 범위를 날짜별 검증 근거로 사용하고 Calendar 불가 시간을 차감한 구조화 후보와 서버 i18n 자연어 요약을 함께 제공하도록 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 실제 가능 날짜 수와 모든 예외 날짜 수를 비교하는 손실 없는 자연어 압축 규칙 및 동률 시 실제 날짜 나열 우선 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 자연어 중심 입력, LLM을 거치지 않는 선택적 수동 불가 시간 격자, 명시 범위의 날짜형·기본 14일의 주간 반복형과 연속 구간 매칭 확정. 수동 불가 입력 의미는 이후 ADR-023에서 대체 | PRD, Architecture, ADR-015와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 참가자 제출·수정 중 호출을 없애고 마감 시 최신 원문 전체를 방 배치 하나로 Gemini에 전달하도록 정정. 500자·총 10,000자 제한과 Full Jitter 최대 3회·15초/60초 timeout 확정 | PRD, Architecture, ADR-016과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 핵심 Decision Gate를 대부분 확정한 뒤 현재 초기 구성 전체를 하나의 initial commit으로 GitHub에 push하고 이후 브랜치 전략을 시작하도록 운영 순서 확정 | Implementation Plan 운영 규칙과 Phase 0 상태 검토 | 동일 커밋 예정 |
| 2026-09-07 | PostgreSQL 실패 이력, poison message용 참조형 Redis DLQ와 Actuator·Micrometer + Alloy + Grafana Cloud Metrics·Loki·Alerting 관측성 스택 확정 | Architecture, ADR-017, 사용자 개입 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | Redis 메시지는 첫 전달 포함 총 5회 처리 실패 시 DLQ로 이동하고 Gemini API 재시도와 별도로 계산하도록 확정 | PRD, Architecture, ADR-017과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | Gemini 기술적 재시도 소진을 `ANALYSIS_DELAYED`로 분리하고 입력 보존·로딩 종료·주최자 방 단위 재분석과 기술 장애 시 타인 원문 비공개를 확정 | PRD, Architecture, ADR-018과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 주최자 로그인 필수·일반 참여자 게스트 허용과 본인 최신 제출 조회 API의 권한 경계를 확정하고 게스트 본인 증명 방식은 후속 선택으로 유지 | PRD, Architecture, ADR-019와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 게스트 본인 증명에 서버 발급 `Secure HttpOnly` 불투명 자격 증명을 사용하고 PostgreSQL에는 해시만 저장하도록 확정 | PRD, Architecture, ADR-020과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 게스트 자격 증명을 브라우저 단위로 여러 방에서 재사용하고 같은 세션은 방마다 참여자 하나만 소유하도록 확정 | PRD, Architecture, ADR-021과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 주최자 외 참여자는 meet-me 로그인 없이 전체 흐름을 이용하고, 선택적 Google Calendar 인증·동의는 서비스 로그인과 분리하도록 명확화. 이후 ADR-022에서 게스트 Calendar 연동은 제외 | PRD FR-003, Architecture Calendar 경계와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | Google Calendar 연동을 로그인 사용자로 제한하고 비로그인 게스트에게 계정·다른 방에 재사용되지 않는 방 전용 시간 격자를 제공하도록 정정 | PRD, Architecture, ADR-022와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 게스트·Calendar 미연동 참여자는 방 전용 가능 시간을 선택하고 Calendar 연동 참여자는 공급자·추가 불가 시간을 차감하는 이중 일정 입력 모드 확정 | PRD, Architecture, ADR-023과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 시간 격자를 강조하지 않는 선택적 부가 기능으로 두고 자연어·수동 슬롯 중 하나만으로 제출 가능하게 확정. 빈 슬롯은 부가 제약 없음이며 자연어가 전혀 없는 합의된 정형 입력 방은 Gemini를 건너뜀. Calendar 전용 제출은 TBD로 유지 | PRD, Architecture, ADR-024와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: 로그인한 개인만 Calendar를 연결·조회하고, 연결한 참여자 본인이 현재 참여 중인 방에서 ON/OFF한다. ON은 불가 일정 외 탐색 범위를 허용하므로 자연어 없이 제출 가능하며 프론트엔드는 참여자·방별 최초 ON 때 확인 팝업을 한 번만 표시 | PRD, Architecture, ADR-025와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 빈 제출은 `가능 시간 없음`이 아니라 미입력으로 처리하도록 선택지 A 확정. 자연어·Calendar ON·수동 가능 시간이 모두 없으면 `SUBMISSION_INPUT_REQUIRED`로 거부하고 완료 인원에 포함하지 않음 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: Calendar ON 제출·수정 시점의 조회 결과를 불변 제출 버전에 고정하고 방 마감에서는 Google Calendar를 다시 호출하지 않음 | PRD, Architecture, ADR-026과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 B 확정: 방 입력 수집·고정 배치 조율 작업·후보 품질·최종 확정을 분리하고 API 공개 진행 상태를 파생하도록 결정 | Architecture, ADR-027과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: PostgreSQL 접근에 Komapper JDBC를 사용하고 도메인 Aggregate·Entity·VO, 영속 Record, Application·Web·외부 DTO를 경계별로 분리 | Architecture, ADR-028, Komapper 공식 호환성 문서와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: 원자적 유스케이스의 Application 서비스 공개 메서드에 Spring `@Transactional`을 적용하고 Domain은 Spring 독립 유지 | Architecture, ADR-028, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | DB 선택지 A 확정: 로컬은 Docker Compose PostgreSQL·Redis, 영속성 통합 테스트는 H2 없이 PostgreSQL Testcontainers 사용 | PRD, Architecture, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | JWT 권장안 확정: 짧은 Access JWT와 Redis TTL 기반 회전형 불투명 Refresh Token 사용, 공급자 Refresh Token은 PostgreSQL 암호화 저장 | PRD, Architecture, ADR-029와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | Gemini 연동에 공식 Google GenAI SDK Java 클라이언트를 사용하고 SDK 타입을 AI Adapter 내부에 격리하도록 확정 | PRD, Architecture, Google 공식 SDK 문서와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 인증 수명·전달 확정: Access JWT 15분, Refresh 미사용 14일·패밀리 최대 30일, Access는 body 발급 후 Authorization 헤더, Refresh는 Secure HttpOnly Set-Cookie 사용 | PRD, Architecture, ADR-029, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 인증 서명·브라우저 방어 확정: RS256·`kid` 키 교체, same-site 서브도메인 배치, API 호스트 전용 `SameSite=Lax` Refresh 쿠키와 엄격한 Origin 검증 채택 | PRD, Architecture, ADR-030, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | 로그아웃 선택지 C 확정: 일반 로그아웃은 현재 Refresh 패밀리만, 별도의 모든 기기 로그아웃은 사용자 전체 패밀리를 폐기하며 공급자 연결은 유지 | PRD FR-002D, Architecture, ADR-031과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | TDD RED 증거 선택지 C 확정: Git에는 최소 메타데이터·테스트 지문 요약을 추적하고 전체 실패 출력은 로컬 전용으로 분리 | Development Rules와 TDD 가드 강화 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | Mutation Testing 선택지 C 확정: 순수 Domain은 PIT 자동 변이 테스트, 보안·영속성·동시성·외부 Adapter는 의도적 결함 주입 검증 적용 | Development Rules와 품질 게이트 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | PIT 실행 시점 권장안 B 확정: Domain 변경 PR은 관련 범위를 필수 검사하고 로컬은 선택 실행하며 정기 CI에서 전체 Domain 검사 | Development Rules와 CI 품질 게이트 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | PIT 운영 결정을 선택지 B로 조정: initial commit·Phase 1에서는 제외하고 핵심 결정론적 매칭 로직 구현 후 효과가 큰 패키지에만 선택 적용. 이전 즉시 PR·정기 실행 결정은 대체 | Development Rules와 품질 게이트 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | 아키텍처 자동 검사 도구 도입을 후속으로 연기: initial commit·Phase 1에서는 코드 리뷰로 헥사고날 의존 방향을 확인하고 기능·Adapter 증가나 실제 경계 위반 시 ArchUnit·Konsist를 재비교 | Development Rules와 Phase 0 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | PR 템플릿을 위험 기반형으로 단순화하고 사람이 자연스럽게 읽을 수 있는 구체적인 표현을 사용하도록 개발 규칙에 반영 | Pull Request 템플릿, Development Rules와 Phase 0 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-16 | 빈 원격 저장소에 PR 기준점용 `main` 커밋 `64e5759`와 `develop`을 push하고 최신 `develop`에서 `feature/initial-setup` 생성 | `git pull --ff-only origin develop` 성공, 브랜치와 원격 추적 상태 확인 | `64e5759`, 초기 구성은 동일 커밋 예정 |
| 2026-09-16 | 초기 구성 커밋 전 전체 품질 검사 수행 | TDD 가드 자체 테스트 6개 통과, `ktlintCheck`, `assemble`, `test` 성공 | 동일 커밋 예정 |
| 2026-09-16 | Issue #2의 `ko-KR` 기본 locale, `Accept-Language` fallback과 UTF-8 MessageSource 기반 구현 | locale·메시지 focused test 통과, `ktlintCheck`, `assemble`, `test` 성공 | 동일 커밋 예정, #2 |
| 2026-09-16 | 작업 전 Issue 생성, `<type>: <summary>` 명사형 PR 제목과 `Closes #<issue-number>` 자동 종료 흐름을 개발 규칙·PR 템플릿에 명시 | Issue #2와 PR #3 연결 및 템플릿·개발 규칙 일치 검토 | 동일 커밋 예정, #2, PR #3 |
| 2026-09-17 | Issue #4의 기본 CI와 `develop` 병합 Issue 자동 종료 구현. GitHub 기본 closing keyword가 비기본 브랜치에서 무시되는 경계를 프로젝트 Action으로 보완 | 파서·Issue API 처리 테스트 9개, actionlint v1.7.12, `ktlintCheck`, `assemble`, `test`와 PR `Quality Gate`·`Validate Issue Link` 통과 | `0523463`, #4, PR #5 |
| 2026-09-17 | 단위 작업 완료 후 PR 제목·본문 전체 초안을 사용자에게 제시하고 승인 뒤 생성하는 검토 흐름 추가 | Development Rules와 운영 규칙 간 일치 검토 | `0523463`, #4, PR #5 |
| 2026-09-17 | PR #5 병합 후 `Close Linked Issues` 성공과 Issue #4 `completed` 종료 확인 | GitHub PR·Issue 상태와 `develop` 병합 커밋 `b0fed8a` 확인 | `b0fed8a`, #4, PR #5 |
| 2026-09-17 | Issue #6의 RED 증거 Draft 2020-12 JSON Schema, 생성·무효화·보존 수명주기와 로컬 원본 로그 Git 제외 규칙 추가. 2026-09-19 출시 목표와 권장안 우선 결정 원칙 반영 | 계약 테스트 5개, 전체 훅 테스트 11개, `git check-ignore`, `ktlintCheck`, `assemble`, `test` 통과 | 동일 커밋 예정, #6 |
| 2026-09-17 | 실제 Gemini API Key 입력용 로컬 `.env.local`, 키 이름만 있는 `.env.example`과 Secret Git 제외 규칙 추가. 후속 GitHub Actions·배포 Secret은 종속 구현 직전 사용자 행동 가이드를 제공하도록 계획에 명시 | `.env.local` ignore 및 `.env.example` 추적 대상 확인, 실제 값 미포함 확인 | 동일 커밋 예정, #6 |
| 2026-09-18 | Issue #8의 Gemini·Google/Kakao OAuth·GitHub/AWS·Grafana Cloud 사용자 개입 실행 가이드와 로컬 설정 이름 목록 추가. Free Tier 합성 테스트 후 Paid 전환, 개발용 OAuth 앱·localhost callback·GitHub `integration` Environment 선제 준비와 운영 자격 증명 분리 원칙 반영 | 공식 공급자 문서와 Architecture TBD 대조, 실제 비밀값 미포함 여부 검증 예정 | 동일 커밋 예정, #8 |
| 2026-09-18 | Wanted AI Champion 제출 MVP를 로그인 없는 익명 주최자 모드로 재범위화하고 Google·Kakao 로그인과 Google Calendar를 Post-MVP로 분리 | PRD FR-000A, Architecture 제출 MVP 접근 모드, ADR-032와 Phase·요구사항 추적 일치 검토 예정 | 동일 커밋 예정, #8 |
| 2026-09-18 | 사용자가 후속 구현의 커밋·push·PR 생성을 승인 대기 없이 진행하도록 위임하고 사용자 개입 선행 → 자율 구현 → Post-MVP 순서를 명시 | Development Rules와 Implementation Plan 실행 순서 대조 예정 | 동일 커밋 예정, #8 |
| 2026-09-18 | 가비아에서 운영 domain `meet-me.co.kr` 확보. `app`·`api` origin과 Route 53 위임 절차 확정 | Architecture 배포 구조와 사용자 개입 가이드 일치, DNS 변경은 Hosted Zone 생성 뒤 수행 | 동일 커밋 예정, #8 |
| 2026-09-18 | 제출 MVP의 장소 검색·정규화 공급자로 Kakao Local API 확정 | Architecture, ADR-033과 `KAKAO_LOCAL_API_KEY` 설정 계약 반영 | 동일 커밋 예정, #8 |
| 2026-09-18 | Kakao Local REST API Key를 로컬과 GitHub `integration` Environment에 등록 | 실제 값 출력 없이 두 위치의 `KAKAO_LOCAL_API_KEY` 존재 여부 확인 | 동일 커밋 예정, #8 |
| 2026-09-18 | 사용자 개입 선행 준비와 Wanted 제출 MVP 재범위화 문서 검증 | `git diff --check`, hook 단위 테스트, `ktlintCheck`, `assemble`, `test` 통과. `.env.local` Git 제외와 실제 secret 미추적 확인 | 동일 커밋 예정, #8 |
| 2026-09-18 | 제출 MVP 6개와 Post-MVP 2개 구현 브랜치의 이름·Phase 범위·번호 호출 계약 확정 | 각 Phase와 선행 병합 규칙, 자동 실행 위임 및 사용자 전용 승인 경계 대조. `git diff --check`, hook 테스트 11개, `ktlintCheck`, `assemble`, `test` 통과 | PR #9 후속 커밋 예정, #8 |
| 2026-09-19 | Issue #10의 4개 핵심 Aggregate와 상태 전이, IANA 시간·DST gap/overlap, 좌표·기간·실제 날짜형·주간 반복형 값 객체 구현 | Domain RED 15개 중 의도한 11개 실패 확인 후 15개 전체 GREEN, 프레임워크 import 부재 검토 | 동일 커밋 예정, #10 |
| 2026-09-19 | Komapper 7.0.0·KSP 2.3.12, Flyway V1, PostgreSQL 18·Redis 8 Compose, Record·Mapper·adapter와 profile 설정 구현 | PostgreSQL Testcontainers fresh migrate·validate, Aggregate 왕복과 CoordinationRun·Outbox rollback 통합 테스트 통과 | 동일 커밋 예정, #10 |
| 2026-09-19 | 고위험 결함 주입 JSON 계약 추가 및 `@Transactional` 제거 결함이 rollback 테스트에서 탐지되는지 검증 | 결함 주입 시 테스트 실패, 복구 후 source·test SHA-256 기록. 전체 출력은 로컬 전용 로그로 분리 | 동일 커밋 예정, #10 |
| 2026-09-19 | Phase 1~2 구현 전체 품질 게이트와 로컬 Compose 계약 검증 | hook 테스트 12개, Domain 15개·PostgreSQL 통합 4개 포함 전체 24개 테스트, `ktlintCheck`, `assemble`, `test`, `docker compose config`, `git diff --check` 통과 | 동일 커밋 예정, #10 |
| 2026-09-19 | Issue #12의 30일 고정 익명 세션, 128비트 초대 코드, 표시 이름과 방 생성·조회·참여·HOST 수동 마감 API 구현 | Domain·보안 RED 11개, V2 migration RED 4개, 전체 51개·hook 12개 테스트, 결함 주입 4종, `ktlintCheck`, `assemble`, `test`, `git diff --check` 통과 | 동일 커밋 예정, #12 |
| 2026-09-19 | Issue #14의 조건 제출·수정·본인 조회, 50명 상한, 자동·수동·데드라인 마감의 최신 배치 고정, 참조형 Outbox와 `gemini-3.8-flash` Structured Output·분석 지연 재요청 구현 | RED 4개, 전체 76개·hook 12개 테스트, V3 fresh/upgrade, 동시성·멱등성 및 좌표·원문·잠금 결함 주입 3종, `ktlintCheck`, `assemble`, `test`, `git diff --check` 통과 | 동일 커밋 예정, #14 |
| 2026-09-20 | Issue #16의 연속 시간·장소 영역 교집합, Kakao 정확명 고유 정규화, Plan A/B/C·`NO_MATCH`·`PARTIAL`, 후보 조회·주최자 미반영 입력 조회와 멱등 확정 구현 | 역할 분리 RED, 핵심 순위·Kakao 경합·부분 결과·교차 방 FK 결함 주입 탐지, 전체 133개 테스트와 `ktlintCheck`, `assemble`, `test`, `git diff --check` 통과 | 동일 커밋 예정, #16 |
| 2026-09-20 | Issue #18의 소요 시간 전 계층 제거, V5 마이그레이션과 Aggregate 우선 헥사고날 패키지 재편. 프로덕션·테스트의 구형 최상위 `adapter/application/domain` 제거와 ADR-039 추가 | 새 구조 기준 RED·마이그레이션·API 실패 증거, 아키텍처 회귀 검사, hook 12개와 전체 139개 테스트, `ktlintCheck`, `assemble`, `test`, `git diff --check` 통과 | 동일 커밋 예정, #18 |
| 2026-09-20 | Issue #20의 PostgreSQL Outbox relay·Redis Streams consumer, 2분 Pending/유실 복구, 5회 poison DLQ·수동 재처리, 공개 API rate limit, 30일 데이터 정리와 Prometheus·JSON 로그 기반 구현. Alloy·Grafana Cloud Metrics·Loki·Alerting은 PM-03으로 이관 | RED 3종, 중복 실행·DLQ 순서·호출 제한·민감정보·역직렬화·보관 경계 결함 주입 6종 탐지, PostgreSQL·Redis Testcontainers 포함 전체 159개 테스트와 `ktlintCheck`, `assemble`, `test`, `git diff --check` 통과 | 동일 커밋 예정, #20 |
| 2026-09-20 | Issue #22의 비루트 애플리케이션 이미지, Nginx TLS reverse proxy, Flyway 선실행·digest rollback 스크립트, AWS Bootstrap·Production Terraform, GitHub OIDC 배포 workflow와 프론트엔드 전달 지침 구현. 실제 AWS 생성은 Bootstrap apply 승인 게이트에서 대기 | migration·OpenAPI enum RED/GREEN, 전체 `ktlintCheck`·`assemble`·`test`, Docker migration·health·OpenAPI, Nginx config, Compose, Terraform validate·Bootstrap plan, actionlint와 `git diff --check` 통과 | 동일 커밋 예정, #22 |
| 2026-09-20 | 승인된 Bootstrap Terraform plan을 적용해 S3 remote state와 GitHub OIDC 역할을 생성하고 Production backend를 연결. Production plan은 생성 45개·조회 2개·변경 0개·삭제 0개로 확정 | Bootstrap apply·output 검증, Production backend init·validate·saved plan JSON 요약 완료. 비용 발생 Production apply 승인 대기 | 동일 커밋 예정, #22 |
| 2026-09-20 | 승인된 Production plan 적용 중 30개 리소스 생성 후 Free Plan 제한으로 RDS 7일 백업과 ACM exportable certificate 생성 실패. 자동 재시도 없이 state와 남은 plan을 점검 | remote state 36개 항목 중 실제 리소스 30개 확인, recovery plan 생성 15개·변경 0개·삭제 0개. 계정 Free/Active·Credit USD 120, EC2 running·Valkey available·Route 53 zone 생성 확인 | 동일 커밋 예정, #22 |
| 2026-09-20 | Root 계정의 Paid Plan 전환 후 IAM CLI에서 Paid/Active와 Credit USD 120 유지를 확인하고 동일한 나머지 15개 plan 재적용. RDS 대기 중 임시 토큰 만료로 state 업로드·lock 해제 실패 | Terraform 프로세스 0개, 로컬 복구 state serial 6·39개 instance 보존과 `**/*.tfstate` Git 제외 확인. 새 `aws login` 후 원격 state·lock 대조 전 재적용 금지 | 동일 커밋 예정, #22 |
| 2026-09-20 | 갱신한 IAM 세션으로 stale lock 해제·복구 state push, 실제 `available` RDS의 taint 해제와 나머지 12개 리소스 적용 완료 | 최종 Terraform plan 0변경, remote state 52개 주소, EC2 running·RDS/Valkey available·ACM pending validation 확인. 민감 로컬 state와 saved plan 10개 삭제 | 동일 커밋 예정, #22 |
| 2026-09-20 | 사용자가 `meet-me.co.kr`에 운영 중인 기존 웹사이트·메일 레코드가 없음을 확인해 Route 53 전체 위임의 기존 서비스 영향이 없음을 확정 | 현재 가비아 위임의 공개 DNS 오류와 Route 53 Hosted Zone 네임서버 4개 준비 상태 대조 | 동일 커밋 예정, #22 |
| 2026-09-20 | 사용자가 가비아 네임서버를 Route 53의 4개 값으로 변경 | Route 53 권한 서버는 새 zone SOA·NS 정상 응답, DNSSEC DS 없음. `.co.kr` 상위 등록부는 아직 기존 가비아 NS 3개를 반환해 공개 resolver SERVFAIL·ACM pending validation 상태로 전파 대기 | 동일 커밋 예정, #22 |
| 2026-09-20 | 가비아 변경 후 `.co.kr` 상위 등록부와 공개 resolver에 Route 53 네임서버 4개 전파 완료 | 상위 위임 4개 정확 일치, Cloudflare NS 조회와 `api.meet-me.co.kr` A 레코드 해석 성공. ACM은 후속 DNS 검증 처리 중 | 동일 커밋 예정, #22 |
| 2026-09-20 | 사용자가 Gemini·Kakao API key와 ACM export passphrase를 SSM Parameter Store에 직접 등록 | 실제 값 조회 없이 지정된 이름 3개 존재와 `SecureString` 타입 확인. ACM 검증 CNAME 공개 해석·기대값 일치와 도메인 검증 `SUCCESS` 확인, 인증서 전체 발급 처리 대기 | 동일 커밋 예정, #22 |
| 2026-09-20 | 공개 DNS 전파 후 `api.meet-me.co.kr` exportable ACM 인증서 발급 완료 | ACM 상태 `ISSUED` 확인, 수동 export 없이 SSM passphrase를 사용하는 배포 스크립트로 후속 설치 예정 | 동일 커밋 예정, #22 |
| 2026-09-20 | 사용자가 GitHub `production` Environment와 배포 Variable 7개를 직접 등록 | GitHub API에서 값 출력 없이 Environment 이름, 변수 이름 7개 정확 일치와 `main` custom deployment branch policy 확인 | 동일 커밋 예정, #22 |
| 2026-09-20 | PR #25의 `main` 병합 후 첫 Deploy Production run #35501179625 실행. OIDC·ECR 인증 성공 뒤 Temurin Alpine JRE의 ARM64 manifest 부재로 image build 중단 | Flyway·SSM·EC2 배포 미실행 확인. Temurin Jammy JDK/JRE가 amd64·arm64/v8 manifest를 제공하고 Alpine JDK/JRE는 amd64만 제공함을 `docker buildx imagetools inspect`로 확인 | 동일 커밋 예정, #26 |
| 2026-09-20 | Issue #26에서 Temurin build/runtime를 Jammy multi-arch로 전환하고 builder를 native `$BUILDPLATFORM`에 고정 | `docker buildx build --platform linux/arm64 --load` 성공, image architecture `arm64`, runtime UID/GID `10001:10001`, curl·Java 실행과 기존 healthcheck metadata 확인 | 동일 커밋 예정, #26 |
| 2026-09-20 | ARM64 수정 반영 후 Deploy Production run #35502317970 재실행. image build·ECR push·패키징과 RDS Flyway V1~V6 적용 성공 후 앱 health 실패 | SSM invocation과 앱 로그에서 Compose의 `$...` 변수 보간 경고와 `meetme_admin` DB 인증 실패 확인. `env_file.format: raw`와 `$` 포함 가짜 비밀번호 원문 보존 CI 회귀 검사로 수정 | 동일 커밋 예정, #30 |
| 2026-09-20 | Production Compose runtime env file을 raw 형식으로 전달하고 공통 회귀 스크립트를 CI에 연결 | `$` 포함 가짜 비밀번호를 임시 env file에 넣고 Alpine 임시 컨테이너의 `printenv` 결과가 원문과 정확히 일치함을 확인. 실제 secret은 테스트·출력에 사용하지 않음 | 동일 커밋 예정, #30 |
| 2026-09-20 | raw secret 수정 반영 후 Deploy Production run #35503275542 재실행. image·ECR·패키징과 Flyway v6 멱등 검증 성공 후 Compose container name 충돌 | 이전·현재 release 디렉터리명이 서로 다른 Compose project/network가 되고 고정 `meet-me-app` 이름을 공유함을 SSM stderr로 확인. 배포·rollback project를 `meet-me-production`으로 고정하고 다른 project label의 legacy 컨테이너만 제거하도록 수정, Docker label 조회와 고정 project 렌더링 검증 통과 | 동일 커밋 예정, #34 |
| 2026-09-20 | 고정 Compose project 수정 반영 후 Deploy Production run #35503788927 재실행. SSM 배포와 앱 health 성공 후 공개 HTTPS 연결 거부 | Nginx 로그에서 `chown("/var/cache/nginx/client_temp", 101) failed (Operation not permitted)` 확인. 내부 high port를 유지하며 공식 image의 worker 권한 하향에 필요한 `CHOWN`·`SETGID`·`SETUID`만 복원하고 app·Nginx health를 배포·rollback 성공 조건으로 강화 | 동일 커밋 예정, #38 |
| 2026-09-20 | Production 동일 조건의 Nginx 실제 기동 회귀 검사를 CI에 추가 | 임시 encrypted self-signed key·dummy upstream, read-only rootfs, tmpfs와 `cap_drop: ALL` + 최소 3개 capability로 Nginx를 실행하고 내부 `/healthz` 성공 확인. 임시 key·container·network는 종료 시 삭제 | 동일 커밋 예정, #38 |
| 2026-09-20 | PR #41의 `main` 병합으로 Deploy Production run #35504731515 성공. ARM64 image build·ECR push, Flyway V6 멱등 선실행, SSM 배포, 앱·Nginx health와 공개 HTTPS·OpenAPI 검사를 모두 완료 | GitHub Actions `Build and Deploy` 전체 성공, EC2 `running`, RDS `available`·7일 백업·Single-AZ, Valkey `available`, 앱·Nginx 컨테이너 `healthy` 확인 | 동일 커밋 예정, #42 |
| 2026-09-20 | 운영 공개 경계와 관측성 검증 | `/healthz`·Swagger UI·`/v3/api-docs` 200, HSTS·nosniff·frame·referrer 보호 헤더, 누락·잘못된 Origin 403, Actuator health·Prometheus 200과 관련 metric 103줄, 최근 앱 로그 민감 필드명 일치 0건 확인 | 동일 커밋 예정, #42 |
| 2026-09-20 | 합성 데이터로 익명 호스트·멤버 수동 슬롯 제출, 최신 입력 복원, 예상 인원 자동 마감, 결정론적 후보 생성·호스트 확정·참여자 결과 조회 운영 E2E 완료 | 두 제출 revision 1, 무쿠키·위조 cookie 401, 멤버 호스트 명령 403, `EXPECTED_PARTICIPANTS` 마감, `READY` → Plan B `COMPLETE` → `CONFIRMED` 확인. 임시 cookie 파일은 값이 남지 않도록 비움 | 동일 커밋 예정, #42 |
| 2026-09-20 | 실행 OpenAPI와 프론트엔드 전달 계약 교차 검증 | 운영 11개 method·path가 `docs/FRONTEND_HANDOFF.md`의 11개와 정확히 일치하고 공개 상태 8개도 일치. Google·Kakao 로그인·Calendar 후보 endpoint는 모두 404 확인 | 동일 커밋 예정, #42 |
| 2026-09-20 | 사용자가 Google AI Studio 결제 설정 후 프로젝트 Tier 1 전환을 확인하고 합성 자연어 운영 E2E를 승인 | 등록 key로 models 목록·`gemini-3.8-flash` metadata와 최소 JSON 생성 200 확인. 실제 key 값은 조회 결과와 로그에 출력하지 않음 | 동일 커밋 예정, #42 |
| 2026-09-20 | 자연어 전용·자연어와 수동 슬롯 조합 제출 후 운영 분석이 `ANALYSIS_DELAYED`로 종결된 원인 분리 | 제출·마감 정상, DB attempt 1회 `INVALID_RESPONSE`·token 사용량 없음. key·model·일반 생성은 200이고 현행 중첩 schema는 400 `INVALID_ARGUMENT`, 두 `maxItems` 제거 schema는 200 확인 | 동일 커밋 예정, #42 |
| 2026-09-20 | Gemini 중첩 배열 schema complexity 호환 수정 | 공급자 schema의 50×32 `maxItems`만 제거하고 `parseProviderResponse`의 입력 50개·조건 32개 검증 유지. RED에서 기존 상한 노출 실패, 수정 후 Adapter focused test GREEN | 동일 커밋 예정, #42 |
| 2026-09-20 | PR #45의 `main` 병합 뒤 Deploy Production run #35506995695로 Gemini schema 호환 수정 재배포 | ARM64 image·ECR·Flyway V6 멱등·SSM 교체·앱과 Nginx health·공개 health와 OpenAPI 전체 성공 | 동일 커밋 예정, #46 |
| 2026-09-20 | 기존 합성 자연어 방을 `ANALYSIS_DELAYED`에서 호스트 재분석해 `READY_WITH_WARNINGS`로 전이하고 PARTIAL 보안·확정 흐름 검증 | attempt 2 성공, input/output token 233/403·추정 USD 0.001686·10원 미만 지표 확인. 미반영 원문은 호스트 200, 멤버 403, 무쿠키 401이며 멤버 결과에 미반영 정보 없음, Plan B 확정 후 `CONFIRMED` | 동일 커밋 예정, #46 |
| 2026-09-20 | 실제 Gemini 응답 모양을 값 없이 조사하고 의미 조건 정규화 선택지 A 확정 | TIME_WINDOW마다 관련 없는 `query`·`radius_meters`·`expression`이 null이고 date·day_of_week가 함께 존재함을 확인. 관련 없는 null은 무시하고 date와 day가 일치할 때 date 우선, 불일치·관련 없는 non-null은 거부하도록 결정 | 동일 커밋 예정, #46 |
| 2026-09-20 | Issue #46의 Gemini 의미 조건 출력 정규화 구현 | 실제 응답 모양 RED 후 Adapter 전체 GREEN. 날짜·요일 불일치 허용과 TIME_WINDOW의 non-null `query` 허용 결함을 각각 주입해 같은 계약 테스트가 모두 탐지하고 정상 구현 복구 | 동일 커밋 예정, #46 |
| 2026-09-20 | PR #49의 `main` 병합 뒤 Deploy Production run #35508363012로 Gemini 의미 조건 정규화 재배포 | ARM64 image·ECR·Flyway V6 멱등·SSM 교체·앱과 Nginx health·공개 health와 OpenAPI 전체 성공 | 동일 커밋 예정, #50 |
| 2026-09-20 | 새 합성 방의 자연어 전용·자연어와 수동 슬롯 조합 제출이 다시 `ANALYSIS_DELAYED`로 종결된 원인 분리 | 최신 attempt 1회 `INVALID_RESPONSE`. 실제 structured response에서 공통 optional schema가 `polarity`·`end_time`을 생략하고 `start_time`에 `+09:00` offset을 포함함을 합성 응답으로 확인 | 동일 커밋 예정, #50 |
| 2026-09-20 | 조건 타입별 `anyOf`와 offset 없는 방 지역 시각 계약 선택지 A 확정·구현 | 5개 타입 변형과 필수 필드를 가진 schema probe 200, `format: time` 제거와 `HH:mm` description·prompt 적용 probe 200 및 완전한 시간 구간 확인. RED/GREEN과 `end_time` 필수 해제·`format: time` 복원 결함 주입 탐지 | 동일 커밋 예정, #50 |
| 2026-09-20 | PR #53의 `main` 병합 뒤 Deploy Production run #35509635216으로 Gemini 타입별 출력 계약 재배포 | ARM64 image·ECR·Flyway V6 멱등·SSM 교체·앱과 Nginx health·공개 health와 OpenAPI 전체 성공 | 동일 커밋 예정, #54 |
| 2026-09-20 | 기존 지연 합성 방 재분석으로 자연어 전용·자연어와 수동 슬롯 조합의 운영 E2E 완료 | `READY`, `COMPLETE`, Plan B와 실제 시간 구간, 호스트 확정 200·멤버 확정 403·멤버 결과 200·`CONFIRMED` 확인. Gemini input/output 252/311 token, 응답 738 bytes, 추정 USD 0.00135525와 배치 10원 미만 지표 확인 | 동일 커밋 예정, #54 |
| 2026-09-20 | Issue #54의 main CI 성공 후 운영 CD 자동 실행 정책 구현 | `push`·`main`·`success` 삼중 조건, 선행 `head_sha` 고정, 수동 main 복구 경로, Production Environment·OIDC 유지와 직렬·진행 중 비취소 정책 회귀 검사 | 동일 커밋 예정, #54 |
| 2026-09-20 | PR #57의 `main` 병합으로 자동 CI/CD 최초 운영 검증 | main CI run #35511844367 성공 뒤 수동 실행 없이 `workflow_run` CD run #35511947428 성공. 두 실행의 SHA `80169fc0` 일치와 SSM 배포·공개 health·OpenAPI·Swagger UI 200 확인 | 동일 커밋 예정, #58 |
| 2026-09-20 | Issue #58의 제품 소개·백엔드 포트폴리오 README 재구성과 작업 브랜치 분류 확장 | 기준 문서·코드 대조, markdownlint 오류 0건, 저장소 링크 존재, CI badge·운영 Swagger·OpenAPI HTTP 200, `ktlintCheck`·`assemble`·`test`와 commit guard 통과 | 동일 커밋 예정, #58 |
| 2026-09-20 | Issue #62의 Gemini `24:00` 종료를 다음 지역 날짜 자정의 배타적 경계로 보존하고 잘못된 날짜·시간을 조건 단위로 격리, 구조화 조건 JSON 왕복과 Architecture 계약 갱신 | Adapter·Matcher·영속 Mapper RED/GREEN, `24:00` 인식·다음 날짜 이동·JSON 보존·조건 격리 결함 주입 4종 탐지, `ktlintCheck`, `assemble`, 전체 `test`, `git diff --check` 통과 | 동일 커밋 예정, #62 |
| 2026-09-21 | Issue #66의 제출 MVP Kakao Local 호출 제거와 Gemini 장소 근접 호환 그룹 전환 | `AREA_n` Structured Output v2, 서버 그룹 교집합·먼 참여자 Plan C 제외, 좌표 없는 대표 지역명, 기존 MATCHING 지연 실행의 STRUCTURING 재시도 migration 구현. 결함 주입 2종 탐지, `ktlintCheck`, `assemble`, 전체 176개 `test`, `git diff --check` 통과 | 동일 커밋 예정, #66 |
| 2026-09-21 | Issue #70의 명시 후보 날짜·시간 요약 압축 | 날짜별 전체 시간 구간 목록 기준 그룹화, 연속 `매일`·비연속 `·`·복수 시간 `또는`·월 경계 표현 구현. RED 5개와 일정 동일성 결함 주입 2종 탐지, `ktlintCheck`, `assemble`, 전체 183개 `test`, `git diff --check` 통과 | 동일 커밋 예정, #70 |
| 2026-09-29 | Issue #74의 RDS 비밀번호 회전 후 앱 자격 증명 자동 갱신 구현 | 운영 재배포 run #36440308054 성공·앱 `healthy`, 스크립트 회전·멱등·재시도 테스트, Terraform 형식·구성 및 계획 신규 6개만 확인, EventBridge 패턴 정·역 검사, `ktlintCheck`·`assemble`·`test` 통과 | 동일 커밋 예정, #74 |
| 2026-09-29 | Issue #74 운영 적용 뒤 EventBridge Run Command 대상 ARN 보정 | 최초 5분 재확인에서 `FailedInvocations`가 발생해 SSM 명령이 생성되지 않은 것을 확인. AWS 계정 ID가 포함된 대상 ARN과 명시적 문서 이름으로 수정하고 Terraform 계획이 대상 2개·IAM 정책 1개 갱신만 포함함을 확인 | 동일 커밋 예정, #74 |
| 2026-09-29 | Issue #74 EventBridge 대상 입력 진단과 실제 SSM 전달 확인 | 임시 진단 대기열에서 중첩 `Parameters` 입력은 `INVALID_JSON`, 계정 범위 문서 ARN은 `InvalidDocument`로 확인. AWS 소유 문서 ARN과 직접 `commands` 입력으로 주기 명령 #3025f842 성공·앱 healthy를 확인하고 Terraform에 검증된 조합을 반영 | 동일 커밋 예정, #74 |
| 2026-09-29 | Issue #74 검증된 EventBridge 설정 운영 반영 | Terraform 4개 갱신·생성/삭제 0개, 5분 재확인 SSM 명령 #6a6a27e·#6d42e5c 모두 성공·앱 healthy, 임시 진단 대기열 제거, 후속 Terraform plan 0변경 확인 | 동일 커밋 예정, #74 |
| 2026-09-29 | Issue #74의 최종 `main` 승격과 자동 운영 배포 완료 | PR #75·#77·#79를 `develop`, PR #76·#78을 `main`에 병합. 최종 main CI run #36450090557과 Deploy Production run #36450384064 성공, 공개 OpenAPI 200, SSM 5분 명령 성공, Terraform 0변경·작업 트리 깨끗함을 확인 | 동일 커밋 예정, #80 |
| 2026-10-03 | Issue #83 자연어 필수·수동 입력 종료, legacy 안전 미반영·기존 결과 보존과 ADR-045·실제 OpenAPI 동기화 완료 | 신규 100/56·기존 64/1 assertion RED, HTTP 82·export GREEN, 공유·독립·복구 전체 288/실패 0/skip 1, 결함 3종 assertion 실패 10·2·3 검출·152 byte SHA 복구, synthetic hook exit 0·unittest 12개 통과, 독립 blocking 지적 없음. 운영 쓰기·새 인프라·보안 변경 없음 | #83, commit/push/PR/merge/deploy 보류 |
| 2026-10-03 | Issue #83 직접 사용자 승인 확인 후 출판 준비 재개 | source/resources/tests 152개·RED/결함 증거 지문 일치, origin/develop 기준 SHA 동일·origin/main과 파일 차이 없음, 검증된 변경만 stage·guard·commit·push·draft PR·Linux CI 순서 진행. merge·deploy는 웹 공개 새 번들 확인 후 별도 신호 대기 | 동일 커밋 예정, #83 |
| 2026-10-04 | 사용자요청2 자연어 의도·후보 감소 경로 재현 후 시간 요약·Gemini 조건 해석의 안전 개선 완료 | 합성23 전후, 실제Gemini3+3 성공, 독립17 사례·RED3종·결함4종 탐지와 SHA복구, hook 전체329/실패0/errors0/skip2, 별도OpenAPI export 및 웹snapshot parsed JSON 동일, 독립blocking없음. 안전제외·후보다양성·C정책충돌 한계 기록 | 로컬 `fix/candidate-semantics`; 자동 승인 검토의 Issue 게시 거절로 commit/push/PR/merge/배포 미실행 |
| 2026-10-05 | 이 실행 대화의 직접 사용자 승인 후 Issue #87 생성·출판 준비 재개 | 변경 source3/test4 지문 일치, 원격 develop5d8c32e/mainc43a30b 기준 불변·중복Issue/PR없음 확인. 필수 commit gate·commit·push·develop PR·Linux CI 순서 진행. 웹 신규reason 한국어 안내 companion 배포 검증 확인 전 서버 운영 승격·배포 보류 | 동일 커밋 예정, #87 |
| 2026-10-05 | Issue #87 검증 변경 commit·push·develop PR #88 게시 | 정확한 Bash/command hook 이벤트로 실제 전체 gate exit0·329/실패0/errors0/skip2 확인, stage18파일 독립리뷰 blocking없음, commit126cd8f·push성공. 초기 다른형식 hook 이벤트는 no-op여서 검증근거에서 제외. Linux CI는 PR head 기준 확인하고 웹 companion 배포 검증 전 병합·운영배포 보류 | 126cd8f, #87·PR #88 |
| 2026-10-05 | PR #88 Linux CI·Issue 연결 검사 성공과 서버 배포 대기 인계 | 코드head126cd8f CI #37277475001, 기록headc4a6b1d CI #37277747962 성공. Ubuntu 품질·배포정책·Compose secret fidelity·DB credential refresh·Nginx 검사 통과. 이후 기록 변경의 최종head검사도 게시PR에서 확인하며 서버 develop/main병합·운영배포는 웹companion검증신호 전 보류 | c4a6b1d, #87·PR #88 |

## 사용자요청2 Worklog — 추천 로직·결과 설명·Gemini 프롬프트 (2026-10-04 UTC)

### 계획 → 재현 → 구현 인계

- [x] `[AGENT]` 필수 rules 6개, project-architecture skill, PRD/Architecture/ADR 및 기존 implementation_plan 진행 기록·Issue83 handoff를 먼저 확인했다.
- [x] `[AGENT]` clean main c43a30b에서 develop ff-only pull 성공(5d8c32e) 후 로컬 조사 브랜치를 만들었다. main과 develop 기준점은 merge topology가 다르지만 이번 관련 source 내용은 같다.
- [x] `[AGENT]` 후보 개수뿐 아니라 조건 해석·시간/공간 의도·결과 문구까지 범위 정정을 계획에 반영했다.
- [x] `[AGENT]` 합성 matrix 23건과 실제 모델 대표 전후 3건을 구분해 실행하고 초기 발견·안전 수정 범위를 먼저 공유했다.
- [x] `[AGENT]` 독립 테스트 설계자와 구현자를 분리하고 assertion RED를 확인한 후 로컬 수정했다. 초기 15개/12실패, 요약 테스트의 문구 과제약 정합 수정 후 5개/5실패 재RED, 독립 리뷰 추가 발견의 mixed pattern 2개/2실패 RED를 별도로 보존했다.
- [x] `[AGENT]` 최종 복구 전체 게이트 exit 0(329/실패0/errors0/skip2), 실제 OpenAPI export와 웹 snapshot 구조 동일, 독립 결함 검토의 blocking 없음 및 source/test 지문 일치를 확인했다.
- [x] `[SHARED]` GitHub Issue: 최초 자동 승인 검토 거절 이후 중단했고, 2026-10-05 이 실행 대화의 직접 사용자 승인 후 Issue #87 생성 성공을 확인했다.
- [x] `[AGENT]` 실제 필수 commit gate exit0·전체329/실패0/errors0/skip2 확인 후 검증 변경을 commit126cd8f·push하고 develop PR #88을 게시했다.
- [x] `[AGENT]` PR #88의 코드head126cd8f 및 기록headc4a6b1d 원격 Linux CI·Issue 연결 검사 성공을 확인했다. 후속 문서기록 head도 PR의 검사 성공 후에만 병합한다.
- [ ] `[SHARED]` 부모의 웹 companion 배포 검증 확인 후 develop/main 승격·기존 운영 배포 결과를 확인한다.

브랜치-before-Issue 경위: 개발 규칙은 Issue 생성→clean develop pull→작업 브랜치 생성 순서를 요구한다. 처음에는 사용자가 원격 게시 없이 로컬 개선·테스트만 요청하여 Issue를 만들지 않고 조사 브랜치를 먼저 생성했다. 결함 수정으로 범위를 확정해 fix/candidate-semantics로 rename했다. 이후 부모가 직접 사용자 승인 원문을 전달했고 공개 이슈 중복 확인 후 생성 1회를 시도했으나 자동 검토가 현재 실행 스레드의 직접 승인 부재·최초 게시 금지를 이유로 거절했다. 같은 범위로 재시도하거나 다른 게시 도구로 우회하지 않았다. 정책 파일은 수정하지 않았다.

### 후보가 줄어드는 단계와 기존 정책

- Gemini candidateCount(1)은 JSON 응답 후보 1개를 받는 공급자 설정이며 Plan 카드 수 제한이 아니다. 조건 최대 32개·응답256KiB·output32768 토큰은 검증 상한이고 이번 사례의 시간 대안을 잘라내지 않았다.
- 입력 가능한 시간 대안은 합집합, UNAVAILABLE는 차감, 참여자 사이는 반개구간 교집합이다. 여러 구간은 하나의 카드 time_ranges에 전부 남는다.
- 전원 대면 A, 전원 비대면 B, 실패 시 부분 참석 C 유형으로 분기한다. 전원 후보가 있으면 바로 반환하므로 현재 한 결과에 최대 A+B 두 유형이고 C는 함께 반환하지 않는다. 유효한 시간 하나만 있다고 3개로 복제하지 않는다.
- 장소 대안은 참여자 안에서 합집합, 참여자 사이에 AREA_n 교집합을 계산하고 문자열 정렬의 첫 공통 키만 대표 지역으로 선택한다. 강남/홍대가 모두 유효해도 두 지역의 별도 A 카드를 만들지 않는다. 실제 거리·교통시간·선호 점수는 계산하지 않는다.
- 부분 참석 C의 순서는 참석 인원→공통 총시간→이른 시작→안정 ID다. 시간·장소 다양성 topK나 선호 가중치는 없다. result service/DTO는 생성 후보를 그대로 노출하며 별도 후보 필터가 없다.
- 웹 정적 읽기 확인: RoomPage는 candidates.map으로 전 항목을 표시하며 slice/filter로 후보를 줄이지 않는다. 이번 작업에서 웹 파일은 수정하지 않았고 브라우저 UI E2E는 실행하지 않았다.

### 합성 검증 matrix (provider 응답은 fixture, 실제 parser/processor/result HTTP, repository는 mock)

각 행의 전체 참여자 원문·provider 조건·파싱 조건 수·개별 availability·전원 시간/장소 교집합·최종 JSON은 `.codex/tdd-evidence/candidate-scenario-before.jsonl` 및 `candidate-scenario-after.jsonl`, 재현 fixture는 `CandidateScenarioMatrixTest.kt`에 있다. 첫 before의 학교 근처/UNRESOLVED 사례 표현은 독립 리뷰에서 부정확함을 확인해 최종 fixture/after를 중앙역으로 정합 수정했으며 학교 근처는 별도 travel 사례로 구분한다.

| ID | 합성 원문 핵심 / 인원 | 결과 전→후 | 후보 감소 근거 |
| --- | --- | --- | --- |
| two-many-times-remote | 2명, 9/21 9~11·14~16·19~21시 강남역 | B 1장/구간3 유지 | 비대면 유형별 집계 |
| three-many-times-either | 3명, 같은 3시간·강남역 | A+B/각구간3 유지 | 전원 A/B 반환 후 종료 |
| two-one-time-offline | 2명, 9/21 19~21시 강남만 | A1/구간1 유지 | 유효한 한 시간 |
| two-no-time | 2명, 오전9~11 vs 오후14~16 | 빈 목록 유지 | 시간 교집합 없음 |
| three-partial-time | 3명, 두명19~21·한명9~11 | C1/참석2 유지 | 부분 참석 fallback |
| three-no-pair | 3명, 아침·오후·저녁 분리 | 빈 목록 유지 | 최소2명 시간 없음 |
| two-different-preferences | 2명, 9~11 또는19~21·선호는 서로 반대 | B1/구간2 유지 | 선호 점수 없음, 대안 보존 |
| two-hard-exclusion | 2명, 9~16 가능·11~14 절대불가 | B1/9~11·14~16 유지 | 하드 제외 차감 |
| two-wide-time | 2명, 9~18 vs10~13 | B1/10~13 유지 | 시간 교집합 축소 |
| two-ambiguous-partial | 2명, generic 모호 미반영 vs19~21 | PARTIAL B1 유지 | 기존 generic neutral 정책; 신규 unsafe reason 테스트와 구분 |
| two-midnight-boundary | 2명, 23시~자정 | B1/23~00 문구→23~24 | 실제 UTC 구간 동일, 종료 경계 표현 수정 |
| two-cross-date | 2명, 21일23~22일02 vs22일00~02 | B1/22일00~02 유지 | 날짜 경계·서울 UTC 변환 |
| two-nearby-stations | 2명, 봉천역 vs서울대입구역 | A1/관악구 북부 유지 | 같은 fixture 장소 그룹 |
| two-separated-stations | 2명, 강남 vs홍대·EITHER | B1 유지 | 대면 장소 교집합 없음 |
| two-place-unspecified | 2명, 한명 장소 없음·대면 | 빈 목록 유지 | 장소를 임의 생성하지 않음 |
| two-multiple-common-stations | 2명, 강남 또는홍대·대면 | A1/강남 유지 | 첫 공통 그룹만 선택 |
| two-same-time-different-places-either | 2명, 같은 시간·강남/홍대·온라인 | A+B 유지 | 장소 대안은 첫 그룹, 비대면 별도 유형 |
| two-duplicate-conditions | 2명, 같은 시간/장소 반복 | A1/구간1 유지 | 시간 normalize·장소 distinct |
| three-place-only-fallback | 3명, 같은 시간·강남2/홍대1·대면 | C1/참석2 유지 | 기존 Issue66 spatial fallback 테스트에 고정된 정책 |
| two-unresolved-place | 2명, 중앙역 미확정 vs강남·EITHER | PARTIAL B1 유지 | 미확정 장소는 대면 제외 |
| two-travel-constraint | 2명, 강남에서30분이내 vs강남 | B1 유지 | 이동 제약을 실제 장소로 가정하지 않음 |
| two-only-exclusions | 2명, 0~9 불가 vs10~13 가능 | B1/10~13 유지 | AVAILABLE 없음 중립 기준·제외 차감 |
| two-touching-boundaries | 2명, 9~11 vs11~14 | 빈 목록 유지 | 반개구간 경계는 겹치지 않음 |

추가 독립 계약 테스트는 자정/익일/다일/월 경계, 반복+다른요일 및 반복 overnight, 프롬프트 의미 경계, unsafe reason 두명 NO_MATCH·세명 유효2 C·generic 기존 정책 유지까지 포함한다. 기존 전체 테스트의 IANA/DST 경계·인증·영속/비동기 회귀도 최종 게이트에서 확인한다.

### 실제 Gemini 전후 근거 (동일 합성 원문, 기존 gemini-3.8-flash, 각 단계3회/재시도0)

| 사례와 원문 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 3명 모두 `2026년 9월 21일 오전 9~11시, 오후 2~4시, 저녁 7~9시 강남역 또는 홍대입구역에서 가능해요.` | COMPLETE A1, 시간3개 모두, 강남역 | COMPLETE A1, 동일 UTC3구간, 대표 지역 강남. 그룹명은 모델이 달리 표현할 수 있음 |
| 2명 `9월21일 강남역 19시~자정 가능, 20~21시 절대불가` / `같은날19시~자정 가능` | UNAVAILABLE 차감은 정확, `19:00~20:00 또는 21:00~00:00` | 같은 UTC2구간, `19:00~20:00 또는 21:00~24:00` |
| 2명 `9/21 9~11시는 강남역에서만, 19~21시는 홍대입구역에서만` / 같은 시간에 장소는 반대 | 평평한 시간2·장소2 목록으로 연결 소실, 참석 불가능한 강남A를 COMPLETE로 생성 | 양쪽 conditions=[]·UNSUPPORTED_CONDITIONAL_CONSTRAINT, PARTIAL·후보0. 진짜 결합 조건 지원 완료로 표시하지 않음 |

실제 성공 trace는 before3/after3, failure행0을 XML·trace에서 직접 확인했다. input/output token 합계는 전1307/2446, 후2093/1453이다. 프롬프트가 길어 input token은 늘었으며 가격/청구 금액은 조회하지 않았고 무료 또는 비용0으로 주장하지 않는다. 실제 모델 결과 뒤에는 production parser/matcher/result service를 사용했지만 repository는 mock이며 운영 방·데이터는 만들지 않았다. 제출 MVP에는 지도 검색 API 호출이 없고 좌표/실매장/이동시간 검증을 완료한 것으로 표시하지 않는다.

### 수정 내용과 설계 결정 경계

- Renderer는 UTC 구간을 실제 지역 날짜 경계에서 나누고 24:00을 보존한다. dominant 반복 패턴에 속하지 않는 날짜가 있으면 실제 날짜별 표현으로 fallback해 유효 시간을 숨기지 않는다. 저장된 시간·후보·확정은 변경하지 않는다.
- Gemini prompt는 하드 제외·명시 가능 대안·soft 선호·모호 시간·자정 넘김·장소 금지·입력의 data 경계와 final Plan/score 생성 금지를 구분한다. 현재 단일 prompt의 지시문을 보강하며 모델·SDK·schema·timeout·retry 설정은 유지한다.
- 현재 스키마로 안전히 보존할 수 없는 시간/장소 결합은 UNSUPPORTED_CONDITIONAL_CONSTRAINT, 임의 경계가 필요한 모호 시간은 AMBIGUOUS_TIME_CONSTRAINT로 분류한다. MatchingProcessor는 이 신규 사유 두 개에만 empty availability를 적용한다. 미반영/전체 인원은 보존하고 유효한 최소2명만 C에 포함한다. 다른 검증 실패의 기존 중립 정책은 유지한다.
- API DTO 필드/enum 변경은 없다. HOST 미반영 `reason`은 범용string이다. 기존 웹은 unknownreason를 원문코드로 노출하므로 타입 오류는 없으나 한국어 사유표시 추가는 웹 담당 조율 대상이다. PARTIAL·NO_MATCH 및 HOST만 미반영 원문 공개 계약은 유지한다.
- 후보 다양성 선택지: (A) 현 정책 유지+유효 구간을 한 카드에 전부 표현하면 API 변경과 추가 모델비용이 없지만 지역 대안은 하나만 보인다. (B) 시간/장소별 실제 대안을 별도 카드로 만들면 비교는 쉬우나 유형별1장·확정 단위·rank/Plan 의미와 웹을 함께 재설계해야 한다. (C) 유형은 유지하고 카드 안 place_options/시간장소 연결 대안을 추가하면 표시 정보는 보존되나 DTO/저장/확정 계약이 늘어난다. 참석인원/하드조건을 우선하고 선호·다양성 점수의 가중치와 동률은 별도 합의해야 한다. 점수를 계산하지 않는 현 시스템에서 '선호 최적/가장 가까운 장소'라는 이유를 만들지 않는다.
- 진짜 시간·장소 결합 지원은 participant별 허용 (시간, 장소그룹, 모임방식) 분기 OR를 보존하고, 같은 장소/방식에서만 참여자 사이 시간 교집합을 구한 뒤 후보·확정·UI가 그 연결을 유지해야 한다. Flat union을 카르테시안 곱으로 만드는 현 구조에서 프롬프트만으로 이를 지원했다고 볼 수 없다.
- 남은 정책 충돌: PRD/Architecture의 C는 '전원 시간 교집합 없음'이라고 쓰지만 기존 Issue66 test/code는 전원시간이 있어도 장소만 불일치하면 subset C를 허용한다. 기존 동작을 임의 변경하지 않았으며 공간 불일치 fallback을 유지할지 문구/정책을 함께 결정해야 한다.
- 모델은 새 지시를 항상 지킨다는 보장이 없고 이유코드 없이 반환한 평탄화 오류를 서버가 원문에서 독립 검출하지 못한다. 6회 실제 대표 검증은 모든 표현의 정확성을 보장하지 않는다. 미지원 입력을 안전 제외하는 대신 후보가 줄거나 NO_MATCH가 될 수 있으며 이 경우 COMPLETE로 과장하지 않는다.

### 최종 로컬 검증·출판 인계

- `.codex/hooks/tdd_guard.py`에 synthetic PreToolUse `git commit --dry-run` 이벤트를 입력해 실제 commit 없이 전체 게이트를 실행했다. hook exit 0, `ktlintCheck`·`assemble`·`test` 성공, 총329/실패0/errors0/skip2를 실제 XML에서 집계하고 `.codex/tdd-evidence/candidate-final-verification.json`과 보존 XML에 기록했다. skip은 기존 opt-in OpenAPI export와 새 opt-in 실제 Gemini probe이며, 각각 별도 실행에서 성공했다. 원격 Linux CI와 브라우저 E2E는 실행하지 않았다.
- 최종 결함 주입은 summary baseline 복원5, unsafe-time guard 제거3, prompt baseline 복원4, mixed-pattern guard 제거2개의 assertion 실패를 탐지했다. 소스는 각 실행의 finally에서 원래 바이트로 복구하고 `.tdd/verification/candidate-semantics.json`의 source3/test4 지문을 모두 대조했다. 독립 리뷰의 blocking 지적은 모두 해소됐다.
- 실제 OpenAPI 생성 검증은 `UnappliedInputResponse.reason`의 type=string·enum없음을 확인했다. 기존 웹 `openapi/meet-me.openapi.json`과 생성 JSON 전체를 파싱해 비교한 결과 동일, 차이 경로0이었다. 생성 바이트 SHA는 `697bf2aa065b57d480308dc1dc5f530f180ed817d43693b8092f129f9815daf2`이며 비교한 snapshot과 직렬화 바이트만 달랐다.
- 로컬 전용 PR 제목/본문은 `.codex/tdd-evidence/candidate-pr-draft.md`, 게시·CI·배포 선행조건/차단범위/신규reason 한국어 안내와 합성 응답은 `.codex/tdd-evidence/candidate-deployment-handoff.md`에 남겼다. 실제 열린 Issue가 없으므로 가짜 `Closes` 번호를 만들거나 PR을 게시하지 않았다.
- 자동 승인 검토는 GitHub Issue 생성을 현재 실행 스레드의 신뢰 가능한 직접 승인 부재·최초 원격 게시 금지를 이유로 거절했다. 부모의 이후 지시대로 원격 승인 재시도와 다른 게시 경로 우회는 하지 않는다. 로컬 검증 완료와 원격 게시/배포 완료를 구분한다.

### 2026-10-05 직접 승인 후 출판 재개

이 실행 대화에서 사용자가 직접 `승인`했다. 범위는 meet-me-duo/meet-me-server의 이번 추천 로직·결과 설명·Gemini 프롬프트 수정에 대한 Issue 생성·commit·push·PR 생성·지침상 develop/main 승격·기존 운영 배포다. 승인 전에는 읽기 전용으로 변경 보존과 최신 기준을 확인했고, 승인 후 중복 없는 실제 Issue #87을 생성했다. 이는 이전 거절의 우회가 아니라 새 직접 승인에 따른 출판 재개다. 추가 검토 거절은 우회하지 않는다.

서버 운영 승격·배포의 선행조건은 부모가 웹 신규 reason 두 개의 한국어 안내 companion 배포 검증을 확인하는 것이다. 이 신호 전에는 Issue·commit·push·develop PR·CI까지만 진행한다. 검증 source3/test4의 최종 지문7개와 원격 develop/main 기준이 기존 근거와 동일함을 재확인했다. 원시 로그와 로컬 인계 자료는 commit하지 않는다.

출판 전 실제 commit 명령을 대상으로 필수 `.codex/hooks/tdd_guard.py`를 재실행해 exit 0을 확인했다. stage 범위는 이번 source·test·문서·최소 TDD metadata 18개 파일이며 정책/인프라/웹/로컬 원시 로그는 포함하지 않는다. 전체329/실패0/errors0/skip2의 검증 근거를 보존했고 commit·push·PR·CI 결과는 후속 단계에서 갱신한다.

실행 근거는 `.codex/tdd-evidence/candidate-publication-verification.json`이다. hook이 실제로 받는 `tool_name=Bash`, `tool_input.command` 형식과 전체 XML329개를 확인했다. 앞선 exec_command/cmd 형식의 이벤트는 hook이 무시했으므로 그 exit0을 실제 게이트 성공으로 사용하지 않았다. 실제 전체 gate 이후 commit126cd8f·push·PR #88 생성은 성공했고 작업 브랜치의 검증 코드 지문은 유지한다. 웹 companion 검증 전 서버 병합·운영 배포는 아직 없다.

2026-10-05 PR #88의 codehead126cd8f Linux CI #37277475001와 기록headc4a6b1d CI #37277747962가 모두 성공했고 Issue 연결 검사도 성공했다. 원격 CI의 Ubuntu 품질 게이트와 배포 정책·Compose secret fidelity·DB credential refresh·Nginx 검증 단계가 전부 성공임을 확인했다. CI 기록만 추가한 최종 head도 GitHub 검사를 확인한 뒤에만 병합한다. 원격 develop5d8c32e/mainc43a30b는 유지하며 웹 companion 확인 전 서버 병합·배포하지 않는다.

## 2026-10-07 — Issue #91 독립 클라우드 구현 시작

- [x] `[AGENT]` 공식 환경 도구 준비와 baseline 검증: JDK 17·체크섬 검증 Gradle 9.7.1, 서버 gate 329건/실패0/skip2, 웹 unit94/E2E257+skip1. 유료 모델·운영 데이터 접근 없음.
- [x] `[AGENT]` 열린 Issue #91과 최신 develop `848b968` 확인, 깨끗한 별도 develop worktree에서 fast-forward pull 후 지정 feature 브랜치 생성. 원본 서버·웹 work checkout 보존.
- [x] `[AGENT]` 독립 계약 테스트와 유효한 assertion/TODO RED 근거 수집. 7개 RED 기록의 테스트 지문을 고정했다.
- [x] `[AGENT]` 입력별 immutable referenceDate·문맥 상속 구현 및 원문 전체 의미/유일 부모/부정·명시 시각·32조건 한도 검증. 독립 설계한 37개 대상 테스트 GREEN과 지원 범위 내 독립 코드 리뷰 PASS를 확인했다.
- [x] `[AGENT]` 실제 processor/result·합성 adapter/matcher 회귀, 독립 리뷰·의도적 결함 주입 6/6 탐지와 필수 guard 통과. 최종 전체 테스트 366건/실패0/오류0/기존 skip2.
- [x] `[AGENT]` 검증·한계·정확한 SHA를 기록하고 #91 Draft PR 및 후속 인계 준비.
- [x] `[AGENT]` 사용자 후속 요구에 따라 제한적인 정규식 의미 게이트를 LLM 중심 해석으로 교체하고 새 독립 RED·결함 주입·리뷰를 완료한다.

확정 범위는 #91이며 #92/웹 #17의 미확정 수정 라운드·비용·멱등 계약은 별도 결정 대상으로 유지한다. 서버 production 코드는 로컬에서 아직 변경되지 않았음을 텍스트 인계로 확인했다. Library ZIP 전송은 공식 경로 두 번 실패 후 중단했고 원본 파일 덮어쓰기는 하지 않았다. 실제 Gemini 성공이나 운영 복구 완료를 합성 fixture 결과로 단정하지 않는다.

### 후속 설계 전환 및 평가 실행기 인계

- 기존 제한 문맥 구현 `f70536e`는 Draft PR #93으로 보존했다. 해당 SHA의 CI #37556137445와 Issue Lifecycle #37556137512는 성공했다. 병합·배포는 실행하지 않았다.
- 독립 아키텍처 검토에서 유효한 공급자 SUCCESS도 원문 정규식으로 거부하거나 덮어쓰는 문제가 확인됐다. 사용자 후속 요구에 따라 LLM 중심 prompt·immutable referenceDate·구조 검증을 유지하는 설계로 전환한다. 기존 제한 문법의 GREEN을 일반 자연어 정확성 근거로 사용하지 않는다. 기존 테스트·근거의 대체 이유와 새 독립 검증을 기록한 뒤 PR 내용을 갱신한다.
- 별도 평가 작업이 저장소에서 가져올 수 있도록 `.codex/evaluation/issue-91.gradle`과 JUnit 자동 실행이 없는 실행기를 추가했다. 고정 23사례와 원문 반복 2회, 이전 예상 결과 지문을 유지했다. 비용 없는 저장소 dry run은 호출0·모든 사례 UNRUN·NOT_EXPLICITLY_ARMED를 확인했다.
- 실제 실행은 별도 평가 작업의 단일 소유다. 이 구현 작업의 공급자 호출은 countTokens 포함0이다. 합산 output 상한 검증 보고와 달리 input100k 예약의 하드 상한 근거는 아직 없으므로 비용 확인 플래그 기본false를 유지한다. 평가 결과·키·원시 로그는 커밋하지 않는다.
- #92의 별도 수정 라운드와 웹 #17 연결은 후속 승인 범위이며 독립 테스트 작성 중이다. #91 설계 전환과 실제 모델 평가를 우선하고, 기존 미확정 결과 입력 보존 계약을 별도 검증한다.

### 2026-10-07 #91 독립 테스트 및 웹 인계 상태

- 독립 문맥·기준일 테스트 18개: 컴파일 후 예상 RED 11개를 고정하고 첫 GREEN을 확인했다. 독립 검토에서 공급자 AVAILABLE 합집합 보정과 명시 오전 종료 상속 누락을 발견했고 별도 회귀 4개 중 3개 assertion RED를 고정했다. 사용자 정확한 filler 원문 회귀를 포함한 총 22개 대상 테스트가 수정 후 모두 통과했다.
- 제출 버전 생성 시각의 방 지역 날짜를 parser 입력에 전달하고, prompt 및 adapter 내부 전체 입력 제한 문법으로 부모 종료와 날짜별 배제를 처리한다. 공급자 schema/ref/condition 검증 실패, 다른 rejection과 장소 조건은 복구로 덮지 않는다. 최종 전체 guard·결함 주입·Draft PR은 아직 미완료다.
- 웹 #17 인계 ZIP은 6개 청크와 전체 SHA-256, 7파일 SHA-256을 모두 검증해 저장소 밖의 안전한 인계 폴더에 보존했다. 기존 웹 checkout 덮어쓰기와 legacy 코드 배포는 하지 않았다.
- 실제 평가 계획은 합성 23사례 + 원문 반복 2회, 최대 25호출이다. 고정 gemini-3.8-flash/LOW/15초/32768출력/1attempt를 유지한다. 현재 GEMINI_API_KEY 없음, 실제 호출 0회, 사용자가 최대 25회·총 USD0.30 비용 상한을 승인했으며 secure 키 설정은 별도 대기다. 기존 방당 10원 설정을 평가 승인으로 사용하지 않는다. 승인된 USD0.30 상한은 매 호출 최대 출력 비용 예약 시 일부 사례를 미실행으로 남길 수 있다.

- 2026-10-07 추가 진행: provider 성공 응답이 원문 충돌·부정·조건부 장소를 숨기는 회귀 10개를 독립 설계해 7개 assertion RED를 고정했다. 제한 문맥을 NotApplicable/Resolved/Rejected로 구분하고 성공 조건에도 전체 원문 안전 검사를 적용 중이다. 일반 시간·장소와 안양역 보존을 검사한다. 추가 경계 검토는 정오 상속과 변환 전 공급자 area-name 전체 배치 검증을 확인한다.
- 웹 별도 `feature/17-analysis-recovery-cloud` worktree에서 인계 코드 API/lint/typecheck/build, 단위121개, E2E305개/skip1/실패0 검증을 완료했다. RecoveryPanel의 #92 API 연결은 아직 미구현이다. #92의 no-op 재사용·라운드 generation/request ID·구 클라이언트/rollback 계약은 독립 설계 인계에서 결정 항목으로 유지한다.
- 최종 독립 회귀는 총 37개이며 모두 GREEN이다. 정오·전체 배치 장소 이름 검증·종료가 명시된 시간과 장소 보존 3개 중 2개 assertion RED, 야간 날짜 모호성과 밤 12시 자정 2개 assertion RED를 추가로 고정했다. `밤 1~6시`는 모호하여 미반영하고 `밤 12시`는 자정으로 처리한다. 6개 RED 기록의 테스트 파일 해시는 변경되지 않았다. 결함 주입과 전체 guard는 별도 실행 중이며 실제 모델 정확성은 아직 미검증이다.
- 현재 작업의 키 boolean 확인은 `exists=false/nonempty=false`다. 다른 새 환경의 키 존재 확인을 현재 작업의 인증 성공으로 사용하지 않으며 키를 환경 간 복사하지 않는다. 실제 평가 하네스는 별도 scratch에 준비했고 공급자 비용 경계 검증 전 실제 호출을 차단한다. 고정 23사례+원문 반복 2회 계획에 새 야간 fixture 2개를 자동 대체하지 않는다.
- 첫 전체 guard는 366건 중 기존 prompt 원문 행 보존 계약 1건이 실패했다. 기존 테스트를 변경하지 않고 assertion RED를 별도 고정한 뒤 `reference_date → ref → locale → rawText` 한 행으로 수정했다. 기존 `ref → locale → rawText` 연결과 새 기준일 연결을 함께 보존하며, 기존 6개와 새 37개 총 43개 대상 GREEN·ktlintCheck 통과 및 독립 format 리뷰 PASS를 확인했다. 최종 source 지문으로 결함 주입과 전체 guard를 다시 검증한다.
- 최종 지문의 결함 주입 6개를 다시 실행해 모두 assertion 실패로 탐지했고 원본 바이트를 복구했다. 2026-10-07 01:12 UTC에 변경하지 않은 `.codex/hooks/tdd_guard.py`가 종료 코드0으로 통과했다. `ktlintCheck assemble test`는 61개 suite/366건/실패0/오류0/skip2이며 기존 유료 live probe와 OpenAPI export opt-in만 skip했다. 독립 리뷰 역할은 `/root/issue91_test_design`, 판정은 지원 범위 내 PASS다. 고정 테스트 7개 기록과 최종 소스·결함 검증 지문은 `.tdd/red/issue-91-*.json` 및 `.tdd/verification/issue-91-time-context.json`을 따른다. 원시 로그는 ignored `.codex/tdd-evidence/`에만 보존한다. 새 작업 인계를 위해 검증한 소스·테스트·문서·요약을 별도 feature 커밋으로 보존하며 SHA는 Git 이력으로 추적한다. 실제 Gemini 평가·#92 연결·Draft PR·병합·운영 배포 완료를 의미하지 않는다.


### #91 LLM 중심 계약 전환 (2026-10-07)

- 사용자 후속 요구와 독립 아키텍처 검토에 따라 `KoreanTimeContextResolver`와 adapter 원문 semantic gate를 제거했다. 유효한 provider SUCCESS의 시간·장소를 보존하고 AMBIGUOUS를 합성 성공으로 복구하지 않는다. Prompt는 일반 부모/자식 시각과 AVAILABLE union minus UNAVAILABLE, immutable reference_date를 안내한다. API·모델·SDK·재시도 설정은 변경하지 않는다.
- 독립 신규 authority15개는 기존 `20bd3b4` 코드에서 컴파일 후 예상 assertion7개 RED를 확인했다. 기존37개를 행별 검토해 raw lexical 기대18개를 대체했고 구조/provider14개와 기준일5개를 보존했다. 기준일의 성공 pipeline은 명시적 provider SUCCESS fixture로 바꾸고 정확한 five-window 기대값은 유지했다. 변경된 테스트의 34개 migration 묶음도 기존 코드를 복원해 assertion7개 RED를 재확인한 뒤 지문을 고정했다. 원문 로그는 ignored `.codex/tdd-evidence/`에만 있다.
- 이전7RED·6결함 근거를 `.tdd/history/issue-91-lexical-f70536e/`로 바이트 그대로 옮기고 `.tdd/supersessions/issue-91-llm-boundary.json`에 대체 사유와 보존 본문 지문을 기록했다. 이전 커밋과 성공366건은 당시 제한 문법 계약의 이력이며 새 설계의 검증 근거로 재사용하지 않는다. 원문 행 보존의 기존 prompt 계약은 테스트 바이트가 같아 해당 RED 근거를 활성 트리에도 그대로 유지한다.
- 추가 실제 평가5사례는 실제 응답 관측 전에 독립 작성자가 고정했다. 평일6–9/7–10·주말1–6·명시AM부모·독립안양역의 정확한 구간·참가자·COMPLETE·요약을 유지하며 모델 결과에 맞춰 변경하지 않는다. 공급자 호출은 별도 부모 지정 평가 작업만 수행한다. 구현 작업의 실제 호출은0이다.
- #92 서버 production과 웹 #17 연결은 별도 worktree와 담당자로 병렬 진행한다. 실제 OpenAPI 생성과 서버 Gradle은 공유 캐시 충돌을 피하도록 root가 순차 실행한다. 새로운 분석 라운드와 비용 상한은 별도 계약이며 모델 의미 미리보기 API를 추정하여 구현하지 않는다.

- 새 경계 최종 검증: authority15+보존provider14+reference5가 GREEN이며 새 결함7개(lexical복귀,상수덮어쓰기,모호성성공변환,worker기준일,ref검증누락,날짜/요일검증누락,area명검증누락)를 모두 assertion으로 탐지했다. 실제 소스를 복구하고 독립 reviewer가 source3/test6/삭제resolver/archive8의 지문을 다시 확인했다. 리뷰 기록은 `.tdd/reviews/issue-91-provider-authority.json`, 새 결함 기록은 `.tdd/verification/issue-91-provider-authority.json`이다.
- 2026-10-07 01:57 UTC 변경하지 않은 mandatory tdd_guard는 exit0, ktlintCheck/assemble/test 모두 성공했다. XML62suite/369건/실패0/오류0/기존skip2다. 새6개 offline 공유원장 테스트가 잠금·누적25회/USD0.30·미정산예약·중복·정책변경을 검사한다. 기본25/확장30 dryrun은 모두 UNRUN/호출0, synthetic replay는 예상PASS1/foreignrefFAIL1/UNRUN28이며 실제 공급자 응답이나 새prompt 모델 정확성 근거가 아니다.
- 최종 feature commit/push 및 Draft PR #93의 설명을 현재 LLM 중심 경계로 갱신한다. 커밋 해시는 Git 이력으로 추적한다. 실제 모델 평가의 input100k 예약 하드 상한은 아직 별도 확인이 필요하고 live 기본flagfalse다. 새 outputdir이 승인을 초기화하지 않도록 approvalID+sharedledgerdir를 필수로 하며 미정산 예약은 전체 비용을 유지한 채 중단한다. 실제 모델·운영 데이터·병합·배포 호출0. #92 생산 컴파일 성공과 웹 mock검증은 별도이며 실제 PostgreSQL 확장 경계/OpenAPI/native연결 최종gate는 미완료다.

## 2026-10-07 — Issue #92 입력 보존 수정 라운드

- [x] 별도 develop 기반 feature worktree와 #91 의존 commit 보존, 독립 설계 검토 및 보수적 계약 승인.
- [x] ADR-046으로 CLOSED 불변·수정 cohort·명시적 분석·영속 멱등·활성 pointer·3회 논리 분석 한도 확정.
- [x] 독립 PostgreSQL/HTTP/실제 잠금 테스트 17개 컴파일 후 missing reopen endpoint 404 assertion RED 확인·지문 고정 (`.tdd/red/issue-92-correction-round-compatibility.json`). 앞선 compile 오류는 RED에서 제외했다.
- [x] immutable version 복원·migration·라운드 API·owner 수정·worker 활성 상태·확정 경쟁 기능 구현 및 실제 경계 검증.
- [x] 최종 독립 결함 주입9개 전체 탐지와 동결195개 파일 원본 복원.
- [x] 결함 완료 후 최종 독립 리뷰 PASS 및 source/test 동결 지문 일치.
- [x] 변경하지 않은 전체 필수 guard: 78suite·438test·실패0·오류0·opt-in skip4.
- [x] 실제 native OpenAPI 생성과 웹 #17 최초 RecoveryPanel 연결, draft/auth/cache/늦은 응답 검증.
- [x] 웹 후속 unit176·browser329 PASS/기존 skip1·flaky0와 최종 head `3c403a2667d4a3921ba8a661fb2a4e9758b220ed` 확인.
- [x] root의 서버 로컬 feature commit·push `bf367bee46bbb2cc78b15f1f418c8e09efc29d0e` 및 Draft PR #94 게시.
- [x] 서버 이식성 checkpoint `83a29f60537b5df883cc608d0e8e920747e447a2`의 정확한 head CI #37567631982 전체8단계 성공. 후속 guard commit의 CI는 별도 확인하며 main/develop 병합과 운영 배포는 보류.
- [x] 별도 승인된 V8 lifecycle guard: 독립 계약·유효 assertion RED → 생산 script 구현 → 최종37검사 PASS·strict15결함 전체 탐지·selected210 정확 복원 및 기존196src 바이트 불변 확인.
- [ ] guard 최종 독립 리뷰·복원 뒤 JVM gate invocation·동일 feature commit/push·정확한 새 head CI. 실제 호스트 설치는 별도 운영 조건이며 미실행이다.

#91의 최신 LLM 중심 구현 `1ddbd26747936a56a0f6f2f380d1ef066fb41ed7`을 #92 feature에 통합했다. #92 생산 기능과 native API 계약은 아래 실제 검증을 통과했다. 구현·테스트 담당자의 실제 공급자 호출과 운영 데이터 접근은 0이며 별도 평가 담당자의 공급자 관측은 아래에 분리한다. 논리 분석 한도를 물리 API 호출 또는 USD 상한으로 보고하지 않는다.

### 현재 #92 검증·출판 상태

아래 상태가 뒤의 초기 진행 기록과 미완료 표현을 대체한다. 과거 RED·수정 과정은 이력으로 보존한다.

- 통합 전후 최초 경계 44개 GREEN 이후, 최종 기능·migration·Redis·권한·6개 모임·native schema selector는 총66개/실행65개 PASS/diagnostic capture1skip/실패0/오류0이다. root 실행 요약은 저장소 밖 `scratch/meet-me-bootstrap/issue-92-final66-native.json`이다. skip은 진단용 schema capture이며 검증 assertion을 생략한 성공으로 계산하지 않는다.
- 실제 OpenAPI SHA-256은 `22225ba0db80bf9afb31fa641f8edc385a2dda69abf83f0c063fb18741890ce0`이다. 필수 request5/round3, 기본 false, revision_round anyOf(object ref,null)를 native JSON에서 확인했다. 독립 JSON Schema와 실제 TypeScript 생성 결과도 `RevisionRoundResponse | null`을 보존한다. generated JSON/타입을 손으로 고치지 않았다.
- root의 신규 worker fixture에서 정확한 stub6개를 보강한 detector baseline35개 GREEN 뒤 최종 결함9개를 모두 DETECTED로 확인했다. 각 실행 당시 동결195개 파일 원본 바이트를 복원했으며, 앞선 fault3 SURVIVED는 탐지 성공으로 계산하지 않는다. `.tdd/verification/issue-92-recovery-boundaries.json`은 현재 실제 detector/helper5개와 source112개 지문을 추적한다. 이후 추가 테스트를 포함한 새196개 파일 전체를 다시 결함 주입·복원했다는 주장은 하지 않는다. 독립 리뷰는 production39개·source112개 불변과 실제 detector5개·결함9개 이력을 확인했고 source blocker0이다.
- 첫 전체 guard의435개 중55개 fixture 실패 뒤 독립 저자가 활성 포트/정확한 버전 조회 등 fixture 정합을 보완하여 legacy79개 GREEN을 확인했다. RED 근거의 비실패 sidecar 지문만 좁힌 이유·원본 이력은 [.tdd/supersessions/issue-92-legacy-fixture-compatibility.json](.tdd/supersessions/issue-92-legacy-fixture-compatibility.json) 및 `.tdd/history/issue-92-legacy-fixture-compatibility/`에 보존한다. 원래 실제 실패 테스트의 기대·실패·sourceRevision·실행 명령·로그는 바꾸지 않았으며, 이 과거 근거를 현재 의존 전체의 신규 RED로 과장하지 않는다.
- root는2026-10-07 03:09:19 UTC에 변경하지 않은 `.codex/hooks/tdd_guard.py`의 최종 exit0을 확인했다. `ktlintCheck assemble test`는78suite/438test/실패0/오류0/opt-in skip4이고 guard 단위 검증12개도 통과했다. legacy HOST/auth/idempotency/worker 기존 테스트와 신규503 회귀3개가 이 전체 GREEN에 포함된다. source112개와 adapter `d166e57eb625883243e055e261dd8c32e070d3fcb37953be92b4704c56b9fc17`은 불변이다. 실행 요약은 저장소 밖 `scratch/meet-me-bootstrap/issue-92-full-guard.json`이며 문서 owner의 source/test/harness 변경·Gradle·모델 호출은0이다.
- 웹 #17의 unit174·결함7과 push head `059f049a4832effb2e2cc844d33f4cdd50b5dc1e`는 앞 단계의 이력이다. 최종 head는 `3c403a2667d4a3921ba8a661fb2a4e9758b220ed`이며 unit176 PASS·browser329 PASS/기존 skip1/flaky0·오류0, 결함8 탐지/정확한 원본 복원과 구/신 번들 합성6검사를 완료했다. native22225 지문은 동일하다. 이 웹 결과를 서버 CI·운영 배포 완료로 사용하지 않는다.
- 구 웹 정확한 source `68c41f7619e7f1a28b2b597cb56867e4d3e852bc` 재빌드 bundle과 최초 baseline의 바이트 동일성을 확인한 뒤 구/신 실제 bundle 격리 브라우저6검사가 모두 PASS했다. 구 PUT409는 draft·revision1을 유지하고 close200은 OPEN/no analysis이며 hosting 교체만으로 구 탭은 바뀌지 않았다. 명시적 reload 후 신 UI는 현재 round/revision으로 저장하고 구 Join은 ROOM_CLOSED·신 Join은 숨김이다. 신 웹+신규 필드 없는 구 계약은 raw-only 저장/HOST close를 유지한다. 실제 backend/model/운영 호출0인 합성 HTTP 검증이며 real cross-version backend/운영 Origin 검증과 구분한다. 새로고침은 미저장 구 draft를 잃게 하므로 먼저 복사하고 신 웹에서 다시 입력·명시적 저장·결과 확인하는 안내·지원 및 운영 갱신 gate는 아직 미구현/미실행이다. 상세6검사와 잔여 조건은 [INPUT_REVISION_ROLLOUT.md](docs/INPUT_REVISION_ROLLOUT.md)에 기록했다.
- 합성 모임6개의 기대 시간·참석자·실제 결과·검증 범위는 [ISSUE_92_SCENARIO_RESULTS.md](docs/ISSUE_92_SCENARIO_RESULTS.md)에 추적한다. 실제 PostgreSQL/HTTP/application processor/matcher이며 자연어 port는 mock, Redis consumer end-to-end와 실제 모델 정확도 증거는 아니다.
- 앞선 PR 생성 거절은 최초 게시 금지의 명확한 철회가 없었던 시점의 이력이다. 이후 사용자가 직접 게시를 명확히 승인하여 root가 서버 `bf367bee46bbb2cc78b15f1f418c8e09efc29d0e`를 push하고 [서버 Draft PR #94](https://github.com/meet-me-duo/meet-me-server/pull/94)(base develop)와 [웹 Draft PR #18](https://github.com/meet-me-duo/meet-me-web/pull/18)(base main)을 게시했다. 이는 새 직접 승인에 따른 재개이며 이전 거절을 우회하지 않았다. 웹의 정확한 head `3c403a2667d4a3921ba8a661fb2a4e9758b220ed`의 CI #37566261043은 unit176·browser329/기존 skip1 검사를 포함해 성공했다. 서버 최초 head bf367의 CI #37566280630은 합성 모임6개 결과 기록의 `/workspace` 경로 권한 때문에 실패했다. 독립 저자의 이식성 수정 뒤 해당6개 검사는 통과했고 root의 변경하지 않은 전체438 gate는 2026-10-07 03:34:18 UTC에78suite/438test/실패0/오류0/opt-in skip4로 다시 통과했다. 독립 리뷰 PASS 뒤 이식성 수정·검증·기록3파일을 checkpoint `83a29f60537b5df883cc608d0e8e920747e447a2`로 commit/push했고 정확한 새 head의 CI #37567631982는 전체8단계가 성공했다. 별도 guard 생산 파일은 이 이식성 checkpoint에 포함하지 않는다. 기능 기대·운영 정책·평가 도구를 완화하지 않았으며, 이 CI 실패를 기능 GREEN 또는 전체 CI 성공으로 기록하지 않는다. 병합·운영 배포는 실행하지 않았다.
- 사용자가 별도로 승인한 V8 lifecycle guard는 독립 실제 script의 세 assertion RED(writer가 실행 중인 migration·구 image fallback·sticky marker 부재)를 확인한 뒤 생산 source를 구현했다. 첫 독립31검사와 설치된 READY guard를 사용하는 기존 credential-refresh 검사를 통과했다. 후속 리뷰 경계의 실제 TSV extra empty TAB·미승인 current에서 승인 target rollback·신규 static CI 연결 assertion RED를 확인하고 최소 수정 뒤 해당 runtime2개와 static9개 GREEN을 확인했다. FD 관측 실패·manifest 없는 기존 설치 artifact의 조기 거절도 별도 assertion RED 뒤 최소 수정했다. 최종 현재 source의37개 검사는381.325초/실패0/오류0으로 통과했다. source hash는 launcher `57117c373ea4a73ea5a6babb2da0784d19f801986301c7da8c35319904696e2f`, installer `3ee4718626ef2725503486e5ca635eff7a5d95b173d6b3aba64f34cdfab5b1d1`다.15개 strict 결함은 모두 DETECTED이며 각 실행 뒤 선택한210개(99main Kotlin·81test Kotlin·25deploy·5CI) SHA를 정확히 복원했다. 이 선택 집합은 resources를 제외하므로 기존 source/test 전부 동결했다고 확대하지 않는다. 별도로 tracked src main/test196개를 이식성83a checkpoint와 비교하여 resources를 포함한 바이트 불변도 확인했다. 최종 독립 리뷰·복원 뒤 JVM gate invocation·새 head CI는 확인 대기다. source·독립 테스트·문서·근거는 같은 후속 feature commit으로 보존할 예정이며 아직 새 commit/push/CI 성공을 기록하지 않는다. 새 guard 전체 완료로 표시하지 않으며 이식성 checkpoint에는 guard 생산 코드와 테스트가 포함되지 않는다. [INPUT_REVISION_ROLLOUT.md](docs/INPUT_REVISION_ROLLOUT.md)는 root 호스트 선행조건·operator 설치·직접 관리자 실행의 잔여 경계·캐시 구 웹 안내·읽기 전용 smoke를 기록한다. 실제 host guard/unit 설치·automation pause/drain·migration·서버/웹 배포·운영 smoke·develop/main 병합은 미실행이다. main CI 이후 자동 CD 경계를 유지하며 배포 준비 완료로 보고하지 않는다.

- 부모 단독 실제 Gemini 평가는 7/50회에서 작은 연결 확인 요청(6번째)200·OK, 제보 원문 요청(7번째)503·UNAVAILABLE/출력 없음으로 보고됐다. 실제 원문 정확도와 실제 모델 HTTP·DB 검증은 미확인이며 합성 모임6개 결과와 구분한다. 이 root의 공급자 호출·평가 도구 변경은0이다.
- root의 변경하지 않은 JVM 필수 guard invocation은2026-10-07 03:48:09 UTC에 exit0(약9초)으로 통과했다. Gradle은 up-to-date/incremental이며 이 시각438개를 새로 실행한 결과가 아니다. 유지한 실제 XML은03:32–03:34의 fresh 이식성 검증78suite/438test/실패0/오류0/skip4이고 Kotlin source는 동일하다. 새 배포 guard의 최종37검사 PASS와15결함 전체 탐지는 위 별도 근거를 따른다. Compose secret fidelity는 통과했다. 로컬 Nginx 검사는 최초600 config의 EACCES, 바이트 동일644 fixture의 health timeout을 성공으로 계산하지 않는다. 같은 fixture에서 테스트 프로세스의 proxy 변수만 비운 후 통과했으며 원본 script/config 바이트·권한·운영 설정은 변경하지 않았다. 상세 이력은 저장소 밖 `scratch/meet-me-bootstrap/issue-92-v8-nginx-check/manifest.json`이고 정확한83a CI의 원본 Nginx 단계도 성공했다. 이 결과가 실제 호스트 설치·운영 smoke를 의미하지 않는다.

### 초기 구현·검증 진행 이력


- #92 구현 역할은 `/root/server92_implementation`, 테스트 설계는 `/root/issue91_test_design`로 분리한다. 기존 17개 frozen test는 변경하지 않았다. Gradle은 shared cache 충돌을 막기 위해 root만 순차 실행한다. 첫 compile/targeted GREEN, 추가 worker/confirmation/retention/rollback RED·fault injection·OpenAPI export·독립 review는 아직 확인하지 않았다. 외부 모델/운영 접근 0.
- 알려진 승인 계약의 제약: quota0에서도 OPEN/저장은 허용하지만 새 version 저장 후 REUSED가 불가능하여 새 분석409 뒤 OPEN이 유지될 수 있다. 별도 cancel API를 임의 추가하지 않았고 사용자 UI와 최종 보고에서 이 제한을 명시한다.
- 첫 production compile 성공. 첫 phase1 17개 중 16개 통과, analyze-first의 늦은 PUT는 올바른409 후 테스트 외부 TransactionTemplate의 UnexpectedRollbackException을 확인하여 독립 저자가 rollback-aware wrapper를 보완한다. 해당 변경 파일의 RED 지문은 다시 기록한다. production lock/state를 완화하지 않았다.
- 독립 review에서 V8 CONSUMED outcome NULL을 PostgreSQL CHECK UNKNOWN으로 허용하는 결함을 발견했다. 실제 PostgreSQL에서 UPDATE 성공1/expected exception 없음의 assertion RED를 확인 (`.tdd/red/issue-92-consumed-null-outcome.json`), 최소 IS NOT NULL 제약을 추가했다. 새 테스트의 frozen 지문은 유지되며 GREEN 재검증 대기다.
- 추가 승인한 viewer.context_id는 같은 이름·역할의 세션 교체에서 자기 원문 cache를 분리하는 본인 participant opaque UUID hint다. credential/session ID와 다른 참여자 ID를 노출하거나 권한 증명으로 사용하지 않는다. 독립 실제 HTTP 회귀 3개 RED를 먼저 확인한 뒤 additive DTO mapping과 native OpenAPI에 반영한다.
- 후속 실제 PostgreSQL 검증: 수정된 rollback-aware fixture를 포함한 phase1 17개와 NULL outcome 1개가 모두 GREEN. viewer.context_id 독립 HTTP 3개는 컴파일·setup 성공 후 예상 assertion RED (`.tdd/red/issue-92-viewer-context.json`)를 고정하고 ViewerParticipation→RoomLifecycle→Web DTO에 본인 opaque UUID 최소 mapping을 추가했다. 21개 재GREEN 및 worker/역사 버전/확정 경쟁/rollback/retention·OpenAPI·최종 guard는 대기다.


### #92 기존 클라이언트·배포·읽기 전용 확인 인계

- 현재 deploy script는 V8 적용 중 구 writer/worker를 유지하고 health 실패 시 previous image로 돌아간다. explicit rollback과 정기/이벤트 DB credential refresh도 구 앱을 실행할 수 있다. 실제 운영에 접근하지 않고 저장소 script만 읽어 확인했으며 ADR-046에 경로·위험·최소 변경 설계를 기록했다. 현재 배포 준비 완료가 아니다.
- 후속 배포 gate: 공통 잠금과 지속적인 최소 호환 release guard를 모든 재시작 경로/기존 설치 script에 먼저 적용 → 앱 전체(writer/worker/relay/retention) quiesce → breaking marker → V8 migration → 호환 새 서버 health/조회 계약 → native schema 웹/capability → 합성 확인 → 접근/credential refresh 재개. marker 이후 이전 image 자동 복귀 금지와 호환 roll-forward/점검 유지가 필요하다. 스크립트 구현에는 독립 high-risk 테스트 RED·fault/review가 선행하며 현재 설계만 준비했다.
- 구 웹+새 서버 OPEN의 COLLECTING·round 없는 PUT409·close200 no-op은 데이터 보호이며 수정 UX 호환 완료가 아니다. 웹 선배포도 기존 탭/캐시를 없애지 못한다. 잔여 구 화면 한계를 operator gate에 남기고 새로고침/업데이트 웹에서 본인 입력 확인 안내를 준비한다. 원문 draft를 강제로 지우거나 저장 성공으로 오인하지 않는다.
- 배포 후 읽기 전용 확인은 승인 이후 healthz/내부 actuator health/native OpenAPI, 격리 합성 fixture의 GET room/own submission/candidates metadata·권한만 대상으로 한다. 운영 원문/증명 출력·다른 참여자 접근·POST/PUT/재분석/확정은 포함하지 않는다. 배포/인프라 변경/실제 운영 smoke는 실행하지 않았다.
- source format은 root가 완료했으며 format으로 변경된 독립 테스트의 정확한 지문 RED 재확인은 root/독립 저자가 담당한다. 생산 구현 owner는 테스트·평가 도구를 변경하지 않으며 현재 source 쓰기를 멈추고 target21·boundary23·native OpenAPI 검증 결과를 기다린다.

- #91 최신 feature fast-forward와 #92 변경 재적용 뒤 PRD FR-008D는 LLM 중심 해석 계약을 유지하고 FR-008E 수정 라운드를 함께 보존했다. Worklog의 최신 #91 기록과 #92 기록을 모두 유지하여 충돌을 해결했다. ARCHITECTURE 자동 병합은 최신 provider SUCCESS/AMBIGUOUS 경계와 #92 round/viewer context 추가를 함께 확인했다. 평가 도구·source·test는 문서 소유자가 수정하지 않았다.
- 잠금 해석 보완: coordination/run 상태 writer는 room→정확한 run을 잠근다. correction 제출 head-only 저장은 room lock 아래 immutable source를 조회하여 같은 coordination writer와 직렬화되며 run 상태를 변경하지 않는다. 같은 cohort의 원문 수정은 공개 room 상태를 바꾸지 않아 room state_version을 증가시키지 않으며 자기 입력의 revision과 viewer.context_id로 private cache 순서를 구분한다.

- #91 통합 전후 실제 targeted boundary 집계는 각각 44개 GREEN이다(phase1 17 + NULL1 + viewer3 + additional9 + confirm2 + frozen5 + worker7). 추가 boundary는 23개이며 초기 대화의 24/45 집계를 정정한다. 실제 OpenAPI exporter 1개는 필수 요청 property required 목록 assertion에서 실패했으므로 native schema export 완료가 아니다. 독립 runtime 누락/null 거절 회귀를 먼저 고정한 뒤 field-local 검증과 required schema를 보완한다. 근거 요약은 root가 작성한 저장소 밖 `/workspace/scratch/meet-me-bootstrap/issue-92-all-boundaries-green.json`을 따른다.

- 독립 runtime 요청 검증은 omitted/null expected_generation 모두400이며 DB 변화가 없음을 확인했다. 런타임 타입 변경은 불필요하여 nonnull Long/UUID와 force_reparse=false를 유지하고 request 필수5·round response 필수3의 OpenAPI requiredMode만 명시했다. 독립 presence8/schema1 RED 이후 native export 재검증을 기다린다.
- PR에서 검토 가능한 배포 준비안은 [docs/INPUT_REVISION_ROLLOUT.md](docs/INPUT_REVISION_ROLLOUT.md)에 추적했다. 실제 차단 명령과 quiesce/compatible roll-forward/기존 refresh 경로/구 캐시 안내/읽기 전용 smoke를 분리해 기록했으며 승인·구현·운영 실행 완료로 표시하지 않는다.

- 후속 요청·migration·Redis·권한·legacy 검증과 native export의 root 집계는 총65개(64실행 성공, debug capture1skip)였다. 웹 실제 생성기 검증에서 RoomResponse.revision_round가 OpenAPI3.1의 object ref와 type:null을 같은 schema에 둔 모순을 발견했다. 별도 독립 실제HTTP/null-schema test1의 assertion RED (`.tdd/red/issue-92-nullable-round-schema.json`) 후 해당 신규 property만 anyOf(object ref, null)로 교체하는 RoomOpenApiConfiguration을 추가했다. 다른 nullable enum·DTO·실제 응답·generated JSON은 수정하지 않는다. root format·66개 재검증 및 native 생성기의 object|null 확인은 대기다.


### 공급자 503 High Demand의 기존 보존 경로 읽기 검토

- 공개 parser 경로의 신규 독립 HighDemand 회귀3개가 기존 production에서 모두 GREEN이다. 숫자503은 SERVER/retryable이고 Retry-After초→2000ms·retryDelay소수초→1500ms를 확인했다. SDK factory에서 합성 예외를 던져 공급자 네트워크 호출은0이다. renderer/JSON/repr의 중첩 escaping을 실제 source와 혼동한 과도한 escaping 의심은 문자 개수(각regex token앞sourcebackslash2개,5xx행총8개)와 실제 테스트로 반박되어 정정한다. adapter 패턴·prompt·SDK·timeout은 수정하지 않았으며 이 실행은 RED가 아니다. 기존 `AMBIGUOUS_TIME_CONSTRAINT`는 공급자 성공 JSON의 rejection_code에서만 저장되어503과 구분된다.
- processor는 올바르게 분류된 retryable SERVER 등 기술 실패에 최대4회, Retry-After 우선 또는 Full Jitter 1/2/4초 범위로 재시도한다. 소진 시 기존 run·고정 batch를 ANALYSIS_DELAYED로 보존하고 새 structured result·candidate·head를 쓰지 않는다. worker는 정상 종결로 Outbox PROCESSED/ACK하며 DLQ 또는 자동 무한 재시도를 만들지 않는다. 최대4회 기술 시도는 수동 HOST retry마다 다시 적용되고3회 correction 논리 한도와 별개다.
- authenticated HOST의 명시적 retry는 room→active pointer의 정확한 run 잠금 뒤 delayed-only로 수행한다. 동일 batch/run을 유지하고 attempt count 다음 번호부터 기록한다. QUEUED+PENDING 반복은 no-op이며 pending 이후 진행 상태의 재요청은 새 분석을 만들지 않고 거절한다. 새 수정 라운드가 열려 있으면 retry도 거절한다. worker start/isCurrent/complete/delay의 활성 pointer·round·exact run version 검사가 오래된 분석을 막는다.
- 신규 `GeminiHighDemandBoundaryTest`3개는 public SDK factory의 합성503→classifier와 실제 processor/영속화 서비스의 fixture 경로를 통과했다. 최대4회 실패에서 SERVER ledger1..4·같은 frozen input/batch·ANALYSIS_DELAYED·구조화/후보/AMBIGUOUS 생성0, 명시적 domain retry에서 ledger5..8을 확인했다. 이 신규 회귀는 실제 PostgreSQL/HTTP HOST retry를 연결한 검사가 아니다. 기존 `GeminiBatchProcessorTest`는 기술 실패 후 최대4회·jitter·delay, `SubmissionWebIntegrationTest`는 HOST retry2회/samebatch1/PENDING1, `CoordinationWorkerTest`는 중복 이벤트 한 번 실행·지연 ACK/DLQ0, `WorkerPublicationBoundaryTest`는 stale publication 차단을 별도로 검증한다. 기존 HTTP HOST/auth/idempotency와 worker baseline은 위 최종 전체 guard438개 GREEN에 포함되어 통과했다. 신규5033개의 domain/fixture 검증 범위를 실제503 HTTP 통합으로 확대해 해석하지 않는다.
- 60초는 현재 call 시작·backoff 전에 검사하는 monotonic deadline이다. 개별15초 호출 timeout을 남은 budget으로 줄이지 않으므로 마지막 in-flight 호출까지 포함한 엄격한 벽시계60초 상한은 코드상 보장하지 않는다. 이 읽기 검토에서는 timeout·새 자동 retry 정책·공급자 API·평가 하네스를 변경하지 않았다. 검토 작업의 실제 모델 호출과 운영 접근은0이며 별도 평가 작업의 HTTP503 결과를 이 구현의 모델 성공/AMBIGUOUS 근거로 사용하지 않는다.

V8 guard 최종 커밋 전 확인: 독립 리뷰 PASS(guard57117c…/installer3ee471…), 결함15개의 실제 selected test 본체 assertion·선택210개 정확 복원·기존196개83a 동일을 확인했다. 2026-10-07 03:59:01 UTC의 변경하지 않은 mandatory hook exit0는 동일 Kotlin/SQL의 up-to-date 출력(앞선 새 실행438/실패0/오류0/skip4)을 재사용했다. guard37개는 별도로 실제 새 실행했으며 실패0이다. 구현·독립 테스트·검증 기록·문서는 동일 커밋 예정, push 후 새 정확 head CI는 대기다. main/develop 병합과 실제 운영 설치·설정·migration·배포는 미실행이다.

### 클라우드 배포 압축 해제 후속 검증 (2026-10-07 UTC)

- `06bc741` CI #37569594968는 JVM gate·정적9·Compose fidelity·credential refresh·가드37·Nginx를 모두 통과했다. 위 기록의 CI 대기는 이 완료 상태로 갱신한다.
- 별도 독립 저자가 추가한 압축 해제 계약4개는 기존 `06bc741` 설정에서 모두 실제 본체 assertion RED였다. 구현자는 workflow2개만 변경했고 정적13개 GREEN, 옵션/umask/CI 연결 누락 결함4개 탐지와 workflow 정확 복원을 확인했다. 실제 GNU tar의 일반 사용자 fixture는 외부 UID/GID·0777 archive에서 실행 사용자 소유권·0750 추출을 확인했으며 root/SSM 운영 실행 증명이 아니다.
- 기존 가드·deploy·기존 정적 검사 및 기능196개는 `06bc741`과 바이트 동일하다. 과거 선택210개 복원 증거는 `06bc741`의 당시 범위로 보존하고, workflow2개가 달라진 현재 전체210개와 동일하다고 표시하지 않는다. 별도 후속 근거는 `.tdd/deployment/issue-92-release-extraction.json`으로 추적한다.
- 실제 모델 평가는 부모가 별도 환경에서 총10/50회 진행했다. 9번째 원문 해석200 성공과 10번째 실제 HTTP·DB 배치503 미완료를 합성 검사와 구분한다. 이 구현·검증 작업의 공급자 호출 및 평가 도구 변경은0이다.
- Commit/PR: 동일 커밋 예정, 기존 Draft PR #94 업데이트. 정확 새 head CI는 push 이후 확인하며 운영 host 설치·설정·migration·배포·main/develop 병합은 미실행이다.


## Issue #95 Worklog — 다양한 날짜·시간 추천 (2026-10-07 UTC)

- [x] `[AGENT]` 이슈95, AGENTS/rules/project-architecture와 PRD/Architecture/ADR·기존 Worklog를 먼저 읽었다. 최신 develop848b968 clean pull 후 독립 feature에 PR94bf4edff를 fast-forward하고 PR93 포함을 확인했다.
- [x] `[AGENT]` [재구성 계약](docs/ISSUE_95_CONTRACT.md), [웹 인계](docs/ISSUE_95_WEB_HANDOFF.md)와 실제 OpenAPI를 작성했다. option/variant는 candidate와 별도이며 protocol은 diverse-time-v1이다. 확정의 실제 구간을 기존 result 표현과 typed selection에 함께 담는다.
- [x] `[AGENT]` 독립 테스트 저자의 총50개 대상 테스트 GREEN을 확인했다. 수정 라운드의 원frozen version id/raw_text/created_at을 보존하고 최신 방 state_version만 증가하는 계약도 검증했다. V1~V8·기존 배포 guard·권한 정책은 변경하지 않았다.
- [x] `[AGENT]` 독립 리뷰의 초/마이크로초 요약, 새 nullable schema와 variant2값enum, typedconfirmed 방 retention 순서 문제를 수정했다. V9는 구candidate writer와 확정 뒤 run/room/revision 변경을 DB에서도 거부한다. 구V8 reader 호환/운영rollback은 별도 대응이 필요하다.
- [x] `[AGENT]` 원격 계약 인계 첫 commit 전 필수 변경 없는 tdd_guard를 통과했다. ktlintCheck/assemble/전체 test488개 중484PASS·기존 옵션형 live probe·디버그/스키마 export4SKIP·실패0, hook self-testsPASS다. Commit/PR: 동일 커밋 예정.
- [x] `[AGENT]` 독립 결함 주입5/5 DETECTED와 원본bytes/SHA복구, 최종독립리뷰 blocking없음, [Draft PR97](https://github.com/meet-me-duo/meet-me-server/pull/97) 공개. [결함주입증거](.tdd/verification/issue-95-boundaries.json).
- [x] `[AGENT]` mutation정확복구후 변경없는최종필수guard 재통과: lint/assemble/test488개·실패0/오류0·기존live probe/디버그/export4SKIP, hook self-testsPASS.
- [x] `[AGENT]` 구현 HEAD627e0b717a7f4f72dfc27b2ff580b2ed9f46c24a의 CI37582706551 전 단계 성공·Issue Lifecycle 성공을 확인했다. Draft PR97 본문에도 실제 결과를 기록했다.
- [x] `[USER]` D2 제안에 사용자 직접 승인 “Meet me는 네 제안대로 진행해”를 확인했다. 동일 차원 OR·여러 차원 AND·참석자별 최대1점·창 전체 충족·선호 하위 창 보존·필수 제약 우선·자연어 입력 유지가 확정됐다.
- [ ] `[AGENT]` D2 제품 구현·독립 RED/GREEN·저장 복원·공통 provider validator/schema·#96 통합 검증. 현재 코드의 preferenceCount=0은 미구현 상태이며 전체95완료로 보고하지 않는다.
- `[AGENT]` 전체 대안을 임의 저장 상한으로 자르지 않는다. N=50·많은 창에서 조합/membership 규모가 커지므로 primary최대3·대안limit+1·선택option만 DB에서 조회하고 membership을500행 batch로 게시하도록 개선했다. 전체 조합 저장 규모와 대량 variant 응답의 실측/참석집합 정규화는 후속 평가 범위다.

- `[AGENT]` 필수 쿼리 누락400을 기존 RFC9457 VALIDATION_FAILED로 통일했다. 독립 binding4사례에서 누락만유효RED(다른UUID/정수형식3사례는기존전역handlerGREEN), 수정후4GREEN. 기존5파일은독립저자가V9와새프로토콜의우회409/typed성공계약에맞췄고PR94baseline69개중5assertRED 및결과문서8개중1assertRED를별도로기록했다. 기대값약화없는독립재리뷰를완료했다.
- `[AGENT]` 기존 CI 보조검사: 배포정책Node13PASS, Compose비밀값보존PASS, 자격증명갱신fixturePASS, V8releaseguard37PASS. Nginx는선택환경checkout0600/700과Dockerclientproxy로기존runtime검사실패를확인한뒤 원본과byte-identical한로컬읽기fixture와빈Dockerclientconfig에서변경없는runtime스크립트PASS를확인했다. 저장소파일권한·운영정책은변경하지않았고정확원격HEAD CI를별도로확인한다.

- `[AGENT]` 2026-10-07 UTC 첫 API/구현commit `fcde6ca716357cfc923786669c944ca3fc48d734`를원격feature에push하고구현전체완료전부모/웹에게nativeOpenAPI·DTO·protocol/confirm/errors/cursorURL을즉시인계했다. DraftPR97 base는develop848b968이며PR94/93의존을본문에명시했다. D2대기는동일하다.
- `[AGENT]` 고의결함5종(N하한·장소union·낮은인원다양성·DBlegacywriter우회·마이크로초체크우회)은정확한namedassertion실패로모두탐지했다. mutation마다finally로원본복구후source/testSHA검증을통과했다. sourceRevision은첫원격구현commitfcde6ca다. rawlog는Git제외로컬codex evidence에보존한다.

- `[AGENT]` 사용자추가인계에따라 PR98 HEAD9a4c4d5의V9 invocation schema를read-only확인했다. #95의추천V9와같은version충돌이며단독GREEN/CI로배포준비완료를선언하지않는다. [통합인계](docs/ISSUE_95_96_INTEGRATION_HANDOFF.md)에별도브랜치·#96V9/#95V10제안·bounded게시와projection원자성·빈DB/기존V8데이터및전체통합HEAD검증순서를기록했다. feature번호변경/운영적용/병합은수행하지않았다.


### D2 승인과 새 구현 세션 인계 (2026-10-07 UTC)

- 사용자 직접 승인으로 계약·PRD·Architecture·ADR-048·웹/통합 인계를 갱신했다. 승인 전 기록과 독립 리뷰의 당시 판단은 이력으로 보존한다.
- #96 HEAD9a4c4d5463e578829baed12cfebad16fb0bf33a4를 read-only 확인했다. 두 live adapter가 공통 NaturalLanguageProviderContract의 strict validator/schema/prompt를 사용하며 기존 fixture helper의 비strict v1/v2 경로와 영속 JSON mapper도 함께 검토해야 한다.
- [D2 구현 인계](docs/ISSUE_95_D2_IMPLEMENTATION_HANDOFF.md)에 승인 예시·정확한 baseline·공유 파일·필수 회귀/결함 주입·통합 deadline/rollback·공개 API 유지와 0유료호출 범위를 기록했다.
- 이 후속은 문서만 변경한다. src/main·src/test·native OpenAPI·migration·guard·권한/배포 정책은 구현 baseline627e0b7과 바이트 동일하게 유지한다. Commit/PR: 동일 커밋 예정 / 기존 Draft PR97. 새 문서 HEAD CI는 push 이후 확인하며 baseline CI 성공을 재사용해 새 HEAD 성공으로 표시하지 않는다.

- 문서 후속 검증(2026-10-07 UTC): 공통 #96 validator/schema와 영속 mapper를 고정 SHA에서 독립 읽기 전용 대조한 리뷰 PASS·blocking 없음. 계약 키워드/로컬 링크 확인과 git diff --check PASS, source/test/native API/guard 바이트 불변 확인. 변경 없는 필수 tdd_guard exit0는 docs-only source의 기존 Gradle up-to-date 결과를 재사용했으며 새488건 실행으로 주장하지 않는다. 새 문서 HEAD CI는 원격 push 이후 별도 확인해야 한다.
### Issue #96 제한된 Luna 폴백 구현·검증 (2026-10-07 UTC)

- Gemini 최초 1회와 Full Jitter 최대 3회 기술 재시도 뒤 Luna 1회를 구현했다. 전체 60초의 초기 배분은 Gemini 42초, Luna 15초, 완료 3초이며 DB admission 대기 뒤 실제 남은 시간으로 timeout을 다시 계산한다. 저장 중 deadline 초과는 typed 예외로 롤백하고 별도 트랜잭션에서 ANALYSIS_DELAYED로 종결한다. 호출하지 못한 admission도 보수적으로 슬롯을 소비하며 physical-call metrics와 구분한다.
- 두 어댑터는 공통 prompt/schema/validator를 사용한다. OpenAI strict nullable rejection_code는 required+null을 허용한다. 두 HTTP transport의 retry와 redirect를 명시적으로 끄고 본문 읽기 전체 timeout·취소를 검증한다. typed 인증/권한/결제/quota/잘못된 요청과 일시 NETWORK/TIMEOUT/RATE_LIMIT/SERVER를 구분하며 성공 PARTIAL/AMBIGUOUS/NO_MATCH는 폴백하지 않는다.
- V9는 run ID+STRUCTURING version별 원래 deadline·owner·4+1 admission·승자를 영속화한다. 기존 V1~V8과 #95의 추천·확정 모델을 바꾸지 않는다. 재전달·재시작은 예산을 초기화하거나 자동 유료 재호출하지 않고 원래 deadline 만료 복구로 지연 처리한다. HOST 명시적 retry는 같은 frozen batch/run의 새 version을 사용한다. 공급자 exactly-once와 운영 배포 완료를 주장하지 않는다.
- 독립 테스트 설계는 contract_tests와 durable_tests, 공급자 구현은 provider_adapter, 독립 리뷰·결함 설계는 fault_review가 담당했다. 최초 RED, 보조 코드 수리 후 재확인, 기존 검색 범위 계약의 #96 supersession을 각 .tdd/red·history·supersessions에 보존한다. 일부 lifecycle 원본 XML은 덮어써졌으므로 원본 실패 로그를 증거로 사용하며 XML을 재작성하지 않는다.
- 저장 Luna fixture는 부모 평가 세션의 실제 응답이며 canonical SHA-256 03bb35860232477203710071cb736c185e9c211f418da886dc2bd5d74cc2c6bf를 보존했다. 10/15의 유효한 UNAVAILABLE 조건은 validator에서 유지하고 matcher가 검색 기간에 적용한다. 기대 10/7·8·9 19–21 COMPLETE와 범위 밖 AVAILABLE-only의 빈 가능 구간을 무료 재생으로 검증한다. 공급자 원문 lexical 해석·SUCCESS 덮어쓰기·AMBIGUOUS 복구는 재도입하지 않는다.
- 첫 대상 98건은 모두 GREEN이다. 첫 전체 524건은 오류0/skip4이며 실패3건은 범위 밖 날짜를 잘못된 조건으로 취급하던 기존 계약이다. 사용자 #96 요구에 따라 독립 저자가 해당 조건 검증과 검색 적용을 분리하고 기존 잘못된 enum·요일·좌표·참조 거절을 유지하여 새 RED를 확인한다. 최종 GREEN·결함 주입·mandatory hook·정확 HEAD CI의 최종 집계는 아래 완료 근거를 따른다.
- 배포 정적13개·Compose fidelity·합성 credential refresh·V8 release guard37개는 통과했다. Nginx 원본 로컬 실행은 workspace의0600 bind file 및 주입 proxy403 환경 문제로 실패했고, 동일 공개 config의 읽기 가능한 임시 fixture와 컨테이너 로컬 HTTP proxy 제거로 health를 확인했다. 저장소 파일 권한·운영 권한·deployment script는 변경하지 않았으며 정확 HEAD CI에서 원본 script를 확인한다.
- 이 구현 세션의 유료 모델 호출·운영 접근·비밀값 읽기/출력/저장·운영 등록·권한 변경은0이다. 부모의 Gemini10+Luna9 실제 품질 평가를 이 세션의 호출 또는 새 transport 실검증으로 집계하지 않는다. 개인 OPENAI 연결은 운영 설정·배포 승인과 별개이며 코드에는 변수 참조만 추가했다.
- Commit/PR: 동일 커밋 예정, develop 대상 독립 draft PR. #94 bf4edff 기준과 #93 포함, #95 migration 번호 예약은 통합 담당 조율 항목이다. 운영 배포·병합은 미실행이다.

**#96 최종 로컬 완료 근거**: 변경하지 않은 .codex/hooks/tdd_guard.py가 exit0(130.077초)로 ktlintCheck·assemble·test 및 guard self-tests를 통과했다. 실제90suite/526건/실패0/오류0/skip4이며 기존 유료 live probe·export/debug opt-in만 skip했다. 독립 source/test SHA 검토 PASS와 결함15개 assertion 탐지·전체218개 바이트 복원을 .tdd/reviews/issue-96-independent-review.json 및 .tdd/verification/issue-96-bounded-fallback.json으로 추적한다. 모든 #96 RED8개 요약의 JSON Schema와 최종 테스트 지문이 일치한다. 원시 로그·XML은 ignored .codex/tdd-evidence에만 보존한다. 커밋·draft PR 생성과 정확 HEAD CI는 이 검증 지문을 사용하며 결과 URL·head SHA는 PR 본문과 Git 이력에서 확인한다. 운영 배포·main/develop 병합 및 유료 호출은0이다.


## Issue #99 Worklog — 추천·Luna 폴백 통합과 D2 (2026-10-07 UTC)

- Issue99 생성 뒤 clean develop848b968 pull 기준 feature/integrate-recommendations-fallback에서 지정 PR97 HEAD518c612와 PR98 HEAD9a4c4d5를 통합했다. 두 merge parent와 PR94bf4edff·PR93 의존을 보존하며 main/develop 직접 수정·병합은 실행하지 않았다. PR97 문서 HEAD CI37585734021·PR98 CI37581751054의 성공을 원격 확인했으며 통합 CI로 재사용하지 않는다.
- 추천 V9와 invocation V10의 SQL 내용 체크섬, 기존 V1~V8 및 unchanged tdd_guard를 보존했다. ADR-047 추천·ADR-048 D2·ADR-049 Luna로 충돌을 조율했다. 실제 PostgreSQL18의 빈 DB10migration/validate와 기존 V8 CONFIRMED·OPEN·CONSUMED/원문·createdAt·frozen cohort·과거 후보/확정/attempt 전체 행 보존을 검증했다. 운영 Flyway 적용 여부는 조회하지 않았으며 미적용을 확정하지 않는다.
- 참석 인원→명시 선호 충족 사람수→동률 다양성, 참석자별 최대1점, 같은 차원 OR·다른 차원 AND, 시간 선호 합집합의 전체 창 포함을 구현했다. 원래 하드 가능 창·선호 교차 하위 창·다자 공통 선호 창을 보존하며 불가·예외·기존 공통 장소조건을 먼저 적용한다. 명시 차원의 존재는 검색 확장 결과가 비어도 유지한다.
- 선호 전용 도메인 타입과 공통 live v3 Gemini/Luna prompt/schema/validator·JSON 저장/복원을 일치시켰다. 구 v1/v2 비strict 결과는 선호 없음으로 유지한다. raw 키 exact validation/nullable required/정확 refs/32조건/256KiB/AREA 이름 경계를 유지하며 잘못된 하드 시간은 기존 unsafe reason으로 안전 제외한다. 부분 거부 참가자의 유효 가능 조건과 선호 하위 창은 보존하되 선호 득점은 제외한다.
- bounded matching은 계산 중 취소를 확인하고 room→run 잠금/version/durable deadline 아래 legacy 후보·추천 projection·room 완료를 한 transaction으로 게시한다. 게시 뒤 deadline 초과는 전체 rollback 뒤 fresh delay/ANALYSIS_DELAYED·ACK로 처리한다. 8명/5개 창의 controlled deadline 사례와 duplicate-worker·재전달·이미 확정 결과 보존을 실제 PostgreSQL로 검증했다. 이를 운영 성능 벤치마크나 최대50명 부하 보장으로 표시하지 않는다.
- 독립 도메인·provider/mapper·통합 테스트와 별도 구현·리뷰 역할을 분리했다. 유효 assertion RED→첫73 focused GREEN→안전성61 GREEN을 보존하되 overlap 합산하지 않는다. 초기 compile/환경/Mockito fixture 실패는 행동 RED에서 제외했다. 일부 도메인 RED XML은 다음 실행에 덮어써졌으므로 원시 실패 로그만 보존하고 XML을 재작성하지 않았다. 부분 거부 과점수·하드 시간 안전성 지적은 추가 RED로 해결했다.
- 첫 전체631건의 실패1은 기존 schema5 분기 검사가 승인된 hard5+preferred3 확장을 반영하지 못한 assertion이었다. 독립 저자가 기존 하드 검사와 다른 테스트 본문을 유지하고 exact 타입/required/null/날짜·반복/HH:mm 검사를 강화했다. before 테스트·관련 과거 RED5개를 byte 보존하고 CONTRACT_SUPERSEDED 관측을 history에 기록했으며 focused12 GREEN·후속 독립 리뷰 PASS다. 범위밖 과거 증거를 소급 수정하지 않는다.
- 의도적 결함11/11은 assertion 실패41개로 탐지했고 당시 동결247개 source/test의 정확 복구를 독립 확인했다. 후속 schema 검사 강화는 non-injected 테스트1개만 바꾸고 production 모두는 그대로이며 final247 지문은 verification/review에서 갱신해 검증했다. .tdd/red Issue99 10개와 fault verification1개 schema/currenthash PASS, supersession before/afterhash PASS, 독립 최종 코드/테스트/문서 리뷰 PASS·차단0이다.
- 변경하지 않은 필수 guard exit0, ktlintCheck·assemble·109suite 전체631tests/실패0/오류0/skip4, hook self-tests12 PASS다. 배포 정적13·Compose fidelity·합성 credential refresh·V8guard37·동일 공개 config Nginx 임시 fixture PASS다. Nginx 원본 script는 정확 HEAD CI에서 확인하며 저장소 권한·운영 설정은 변경하지 않았다. 원시 로그/XML은 ignored .codex/tdd-evidence에만 보관한다.
- 실제 /v3/api-docs native export의 전체 parsed JSON은 PR97 OpenAPI와 동일하다. 직렬화 byte/SHA는 다르며 .tdd/reviews/issue-99-openapi-contract.json에 두 지문을 기록했다. 공개 diverse-time-v1 옵션/확정/legacy API·입력 UI는 유지했다. 웹 PR20 정확 HEAD8e56fe0c37cef52e584ff569b97b46b60f28e517을 원격 확인했지만 backend/browser 공동 실행은 주장하지 않는다.
- 이 구현 세션의 유료 호출0이다. 실제 통합 품질은 전담01a114c3-02d3-7011-ab8f-19706bdda089만 검증한다. 기존 유료19/50 및 Luna7/8은 새 통합 품질 PASS로 재사용하지 않는다. 전담자는 기존 평가 하네스의 v2 체크·legacy matcher 연결을 v3·추천 repository 경로로 조율해야 한다. 무료 integration은 typed fake parser, provider 검증은 loopback/shared validator이며 실제 모델 lexical 정확성은 별도다. 웹 담당01a114d2-c535-7787-adca-69742f5cd08d와 부모를 통해 연계한다.
- Commit/PR: 동일 커밋 예정, develop 대상 Draft PR. 정확 HEAD CI는 게시 후 PR 본문에 확인 결과를 기록한다. 코드 검증 완료와 운영 준비를 구분하며 V8guard/digest/드레인 승인은 미해결이다. main/develop 병합·배포·운영 DB 적용·운영 보안/비밀정보 변경·자동화 중지는 실행하지 않았다.

## Issue #99 Worklog — 승인된 공급자 시간 배분 후속 (2026-10-07 UTC)

- 전담 평가가 보고한 최초 통합 invocation56.975초/TIMEOUT·Luna relay HTTP200 저장19.795초를 시간 배분 문제로 분류했다. relay 측정은 네트워크·프록시·읽기·저장을 포함하며 취소 전파 한계가 있어 모델 순수 지연/새27초 성공률로 확대하지 않는다. 이 구현의 유료 호출0이다.
- 사용자 승인에 따라 동일 feature/Issue99/PR100에서 단일60초, Gemini 호출15·단계30(백오프/claim 포함), Luna 최대27, 완료3을 공통 Application port로 맞췄다. 조기 fallback/claim 후 남은 cap과30/57/60 exclusive 경계를 검증했으며 기존 최대4+1·owner/noreset·frozen·ACK·원자 추천 rollback은 유지한다. 직접 request 기본15는 유지하므로 직접 Luna 평가 caller는27을 명시해야 한다.
- 독립 테스트 저자2명과 read-only reviewer를 분리했다. 최종 RED48건/20 AssertionFailedError/오류0→집중 GREEN72건/실패0이다. 실제 PG에서20초Luna가51초에 winner/추천/room 완료,57/60 물리호출0,60초 게시 rollback 및 재전달noreset을 검증했다. 실제 OkHttp timeout 설정 검사는 무네트워크 interceptor로 시행했으며 실제27초 모델 성공을 주장하지 않는다. 기존 기대 변경은 before/RED byte archive와 승인 supersession으로 추적했다.
- 고의 결함5/5 assertion 탐지·실패35·오류0, 전체250개 source/test 정확 복원을 독립 확인했다. 복원 후 변경 없는 필수 guard exit0·hook self-tests12·ktlintCheck/assemble·111suite 전체654tests/실패0/오류0/skip4, 배포 정적13 PASS다. 새 native OpenAPI 전체 parsed JSON은 기존 계약과 완전 동일이며 byte/SHA 차이를 별도 budget 증거로 기록했다. 기존 통합 evidence는 소급 덮어쓰지 않았다.
- V1~V10 migration과 bounded-luna-v1 바이트/정책, 공개 API/UI는 유지한다. CI Quality 실행 한도15→20분만 조정해 기존 원격 CI의 약15분 검사와 추가PG회귀를 수용하며 product60초·hook/검사·권한·운영 deployment/concurrency는 유지한다.
- Commit/PR: 동일 커밋 예정, 기존 Draft PR100 갱신. 새 정확 SHA와 로컬 검증은 push 즉시 부모에게 인계하고 해당 HEAD CI는 PR100 본문에 확정한다. 유료 통합 재평가는 전담 세션, 웹 공동 실행·운영 Flyway/V8guard/digest/드레인 승인은 별도이며 병합·배포·운영DB/보안/비밀/자동화 변경은 수행하지 않는다.

## Issue #99 Worklog — 실제 평가 인계와 운영 읽기 preflight (2026-10-07 UTC)

- 사용자 승인으로 기존 GHA production 역할/SSM의 읽기 preflight를 준비한다. 새 IAM/토큰/OS 설정은 하지 않는다. 서비스 중단은 실제 대상·시작 조건을 부모에게 먼저 보고한 뒤 별도로 진행한다. main merge 자동CD는 preflight·운영키·digest 순서 확정 전 실행하지 않는다.
- 기존 gh 인증이 있으나 실행환경 프록시가 직접 GitHub REST를403으로 차단하고 연결 도구에는 workflow dispatch가 없다. 기존 bootstrap에는 production OIDC 역할의 ReadOnlyAccess와 특정instance AWS-RunShellScript 권한이 선언돼 있으나 실제 적용은 조회 전 미확정이다. 새 공개Issue는 내부운영metadata 공개 위험으로 자동승인검토가거절해생성하지않았다. 기존Issue99/PR100 feature 안에서 비밀값 없는 조회코드와독립검증을준비한다.
- [실제 평가 기록](docs/ISSUE_99_LIVE_EVALUATION.md)은 전담이 보고한 서버6f7f54e/웹8e56fe0의3시나리오·4분석PASS, 추가18생성·누적41/50 및추정/미확정비용을구분한다. 기록/운영조회작업의 유료호출0이며 이후문서/조회코드 HEAD를 새로운 실제평가대상으로 혼동하지않는다.
- 현재 조회 구현/독립 RED·리뷰·원격 실행은 진행 중이다. 운영OpenAI 값은 사용자 보안입력이며 시험환경에서 복사/출력하지않는다. 조회거부·예상밖migration·새비용/권한은 확대하지않고보고한다.
- 읽기 전용 조회의 독립20검사는 RED6 assertion→GREEN20/skip0이며 결함3개를 assertion7개로 탐지하고 관련6파일 exact 복원을 확인했다. 독립 최종 리뷰 PASS·차단0, 기존 배포정책13 PASS다. 실제 격리PG18.6/current bootJar PropertiesLauncher reader smoke는 운영 조회와 구분한다. 변경 없는 mandatory guard exit0(8.725초)는 기존654건의 Gradle up-to-date 결과를 재사용하며 새654 실행으로 주장하지 않는다. 제품 src/main·src/test/V1~V10 및 guard는6f7f54e와 동일하다.
- [실행 인계](docs/ISSUE_99_PRODUCTION_PREFLIGHT_HANDOFF.md)에 승인 경계·조회 방법·키 위치·digest/CD 순서·남은 시작 조건을 정리했다. 기존Draft PR100 feature에서 조회workflow와 평가기록을 commit/push하며 새 정확HEAD CI와 실제 AWS 접근은 원격 실행 결과로 별도 보고한다. 이 기록 시점의 실제 운영조회 성공0·서비스중단0·유료호출0이다.

## Issue #99 Worklog — Luna 운영키 전달 후속 (2026-10-07 UTC)

- `[AGENT]` 사용자 승인으로 기존feature/PR100에서 공개release mode gemini-luna-required, SSM SecureString→OPENAI_API_KEY/MEETME_RUNTIME_PROVIDER_MODE→raw Compose→entrypoint pre-server/migrate/customcommand 검사 경로를 추가했다. ownrelease marker exactenum±LF 검증, SecureString 타입/ASCII33~126 rawJSON 검증, OpenAI 값/AWS진단 비출력, 같은dir0600 임시file→atomic rename과 unsafe destination 거부를 구현했다. mode없는/명시gemini-only는 OpenAI조회0이며 기존release의mode로DBrefresh에도요구를 유지한다.
- `[AGENT]` 독립testauthor는18 frozen검사를작성했고 최초RED15 assertion/pass3→GREEN18을확인했다. reviewer가 발견한 BashNUL marker 우회는원본18/RED byte archive를보존한뒤별도추가test1의19RED18pass/1assertion→rawjq수정→GREEN19/skip0로해결했다. 새19의원본18prefix는byte-identical이다.
- `[AGENT]` 7fault(type/rawASCII/startupguard/atomicrename/legacyOpenAIquery/rawmode/packagemode누락)를 assertion12개로탐지했고9source/testfileexact복원후19GREEN을재확인했다. 별도독립reviewJSON으로추적하며배포13+preflight20 combined33GREEN/skip0, 실제localAlpine rawCompose의합성DB/OpenAI키와mode byte보존을확인했다. 실제secret/운영AWS/모델 호출은0이다.
- `[AGENT]` 변경없는mandatoryguardexit0(9.556초)는기존654건의Gradleup-to-date결과를재사용한다. 새654실행으로주장하지않는다. src/main·src/test·V1~V10·시간배분·nativeAPI·hostguard·runtimeIAM은실제평가6f7f54e와동일하다. image에포함되는entrypoint는변경되므로이전image digest를새배포에재사용하지않는다.
- `[AGENT]` IAM선언의SSM /meet-me/production/*는새path를포함하며Terraform에는수동등록name출력만추가했다. 새secret값/resource/IAM/KMS정책/권한은만들지않는다. TerraformCLI가선택환경에없어fmt/validate를실행했다고주장하지않으며값없는output/path를정적검토했다. 실제적용권한은미확인이다.
- `[USER]` 수동키생성/SSM SecureString등록과실제전달확인은미완료다. docs/ISSUE_99_LUNA_RUNTIME_SETUP.md에책임/정확경로/완료신호/비밀공유금지를기록했다. 집PC의첫AWS시도는명령전transport연결실패로조회0이라는부모보고를기록하며재접속을권한확대승인으로취급하지않는다. 기존production환경의PRmerge-ref거부를우회하거나수동rerun하지않았다. main/develop병합·운영중단·배포·자동화/보안정책변경은보류한다.
- Commit/PR: 동일커밋예정, 기존DraftPR100갱신. 새정확HEADCI는push후별도로확인하며기존48b8957 CI37610286399의성공을재사용하지않는다. 새코드는앱/시간배분을바꾸지않지만배포entrypoint/env계약은새source로구분한다.

## Issue #99 Worklog — 격리 ARM64 이미지와 운영 대상 대조 (2026-10-07 UTC)

- `[AGENT]` 운영키82b8d2a 정확 CI37614973315와 Infrastructure37614973327은 성공했다. Terraform fmt/init backend=false/validate 성공이며 production plan은 skip이다. 읽기 preflight37614973475는 기존 production 환경의 PR merge ref 거부로 runner/step0·AWS조회0이다.
- `[USER]` 본인 PowerShell의 기존 AWS 프로필 로그인과 수동 키 등록을 보고했다. 별도 집PC 실행계정은 프로필 접근이 없어 자동 조회가 불가능하다. 마지막 성공 운영 배포37279832865의 SSM 대상과 사용자 전달 대상이 일치함을 로그로 확인했다. 현재 환경 변수 직접 읽기는403으로 차단됐으며 현재 설정·host·parameter 타입/버전·실제 권한/전달은 미검증이다. 비밀값/실제 대상ID는 공개 기록에 넣지 않는다.
- `[AGENT]` 새 CI built-image job은 정확 PR HEAD checkout/persist-credentials=false·contents read만 사용한다. 기존 GHA QEMU/buildx로 linux/arm64 Dockerfile image를 build/load하되 registry push/login·AWS·production environment는 없다. 실제 UID/JAR/entrypoint SHA와 JRE17, required mode의24개 거부를 포함한28개 격리 probe는 network none·read-only·capdrop/no-new-privileges이며 Java sentinel로 guard 결함에도 Spring/migration이 시작되지 않는다. 합성 키만 사용하고 sanitized proof JSON만3일 보존한다. config image ID를 ECR 승인 digest로 취급하지 않는다.
- `[AGENT]` 독립 source 계약10개의 최초RED5 assertion/pass5→GREEN10/skip0, 6fault를6assertion으로탐지하고3파일exact복원→GREEN10을 확인했다. 원본10/최초RED를byte보존한뒤implicitDockerartifact/summary차단test1을추가해11RED10pass/1assertion→두jobenvflags추가→GREEN11/skip0을확인했다. 최종11에서8fault/8assertion과3파일exact복원을재확인했다. 독립 reviewer/helper/test author 역할을 분리했다. 이것은 source 계약 근거이며 실제 ARM64 build/28probe 실행은 push 후 새 exactHEAD CI에서 확인한다.
- `[AGENT]` 제품 Kotlin/SQL·runtime renderer/entrypoint·Compose·Dockerfile·IAM·deployment workflow·mandatoryguard는82b8d2a와 동일하다. 앱의 실제 paid/web 평가SHA는6f7f54e/8e56fe0 그대로이며 새 유료 호출0이다. 원격명령·main/develop병합·운영배포·DB적용·자동화/보안/인증설정변경0. 수동 metadata 준비 명령은 docs/ISSUE_99_PRODUCTION_PREFLIGHT_HANDOFF.md에만 마련했다.
- 변경없는최종mandatoryguardexit0(9.07초), 기존배포13/preflight20/runtime19/최종image11의combined63GREEN/skip0. 앱코드는동일하므로새654실행으로주장하지않는다.
- Commit/PR: 기존DraftPR100의동일커밋예정. 새ARM64image/전체CI는push후별도로확인하며운영키82CI성공을새image성공으로재사용하지않는다.

### ARM64 실제 실행 후속 (2026-10-07 UTC)

- 새정확HEAD04c6b16363984488b60cee52faf21b8303161a55를commit/push하고CI37619050758을실행했다. ARM64build/load는성공했으나첫격리helper검사가약380ms뒤실패해28probe/proof성공을선언하지않는다. amd64runner에서platform미명시Docker경고가첫fileprobe의무출력검사와충돌했을가능성을공식Docker/Moby동작과source로검토했다. 실제stderr는출력/보존하지않아원인확정으로기록하지않는다.
- 독립testauthor가원본11/RED/history를보존하고12번째sharedrunner의explicitplatform계약을추가했다. 새12RED11pass/1assertion을확인했다. helper의platform선택과고정phase/숫자probe진단만보완하며오류본문/키/argv출력이나무출력조건완화는하지않는다. 앱/runtime/IAM/Dockerfile/SQL/deployworkflow는그대로다.
- Infrastructure37619050888은fmt/bootstrap·productionvalidate성공,productionplan skip이다. Preflight37619050603은동일production환경보호로runner0/steps0이다. 기존정책을바꾸거나manualrerun하지않았다.
- 사용자정정에따라실제배포는기존GitHubActions로진행하는기준을유지한다. 현재main5e8faab의workflow는CI/DeployProduction/Infrastructure/IssueLifecycle이며read-onlypreflight는없고manualdeploy는실제ECRpush/SSM/migration을수행한다. main의V1~7source는운영history증거가아니다. 기존migration/health/OpenAPI검증과새V8+의실제guard/digest/drain확인을구분한다. 사용자로컬CLI는필수경로로단정하지않으며새권한/보호규칙/인증은추가하지않는다. 기존CD의build→deploy사이독립digest승인단계가없는시작순서미해결도구분한다.
- platform보완후12GREEN/skip0, 최종9fault/9assertion·3파일exact복원, combined64/0/skip0을확인했다. 변경없는필수guardexit0(9.302초)는기존654품질결과를재사용하며새654실행으로주장하지않는다. 독립최종리뷰와후속새HEADCI는구분한다.
- 후속commit/새HEADCI는helper/계약/evidence수정후진행한다. 유료호출·원격SSM명령·운영변경은계속0이다.


### 2026-10-07 Issue99 첫 전환 gate와 동일 계약 main 자동 배포 후속

- 사용자 명시 승인대로 기존 GitHub production 역할·보호규칙 안에서 build-publish와 approved-release를 분리했다. 첫 V8→V9/V10 또는 SQL/guard 계약 변경은 immutable run/attempt 게시물의 source/CI/publisher·archiveSHA·ECR digest·실제 history와 명시 승인을 요구한다. 같은 계약의 후속 main CI는 실제 READY·history/current image/root record를 확인하고 자동 배포한다. 영구 수동화·IAM/protection 변경은 없다.
- source SQL10과14파일 실행 계약을 묶고, 실제 rule 상태/target·Online·지원되는 active command 조회·SecureString Name/Type/Version을 확인한다. 두파일 readstate 전송/60KiB command 상한, host1440/SSM1500/poll1560/job30분, rootsticky defaultlock 및 중첩 lifecycle lock 회피를 독립 리뷰했다. 실제 rule 중지는 유지보수 시작 승인 시에만 안내하며 지금 요청/실행하지 않았다.
- 독립 동결6 신규+기존33의 유효 RED16/23PASS 뒤 최종39 PASS/실패0/skip0, 실제 Flyway12.4.0 checksum10 일치와7fault/8assertion 탐지·303 source/test 파일 exact복원을 확인했다. 테스트 기대를 구현자가 바꾸지 않았고 과거 workflow/RED를 history에 보존했다. 추가 host orchestration fixture는 최신 사용자 범위 지시로 실행 없이 폐기했으므로 독립 host runtime proof를 주장하지 않는다.
- 변경 없는 필수 tdd_guard SHA07c4f974…exit0/8.639초로 hook self-tests·ktlintCheck·assemble·test를 확인했다. Gradle cached654 결과 재사용이며 새 로컬654 실행으로 집계하지 않는다. 제품 Kotlin/API/V1~V10 SQL/runtime renderer/entrypoint·기존guard/installer는369c5ff와 동일하다. 새 정확HEAD CI는 게시 후 PR100에서 별도로 확인한다.
- 현재 실제AWS조회/운영명령/서비스중단/DB쓰기/main·develop병합/유료모델호출0. 운영 source/digest와 실제preflight를 보고한 뒤 최종배포승인을 받아야 한다. 구현/문서/검증 후속 Commit/PR: 동일 커밋 예정, 기존Draft PR100 유지.
