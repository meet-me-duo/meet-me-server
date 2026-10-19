# #107 제출 전 확인 설계안

2026-10-17 UTC. 상태: 설계 검토용, 제품 구현·migration·실모델 검증 전. 최신 사용자 합의가 기존 [사후 질문 계약](ISSUE_107_CONTRACT.md)·ADR-052의 질문 시점을 대체한다. 기존 기록과 V13 데이터는 삭제하지 않는다. 아래 API/테이블 이름은 제안이며 아직 구현된 계약이 아니다.

## 기준과 현재 범위

| 대상 | 확인한 HEAD | 역할 / 원격 상태 |
| --- | --- | --- |
| 서버 PR117 | `c44225d814ce016dfe7d0deb1ec77632cb561448` | #109 + 기존 #107, Draft/Open·미병합, CI39051106339 success |
| 웹 PR28 | `fb87358feecf6f979cd55bb10a218a0fe0e1eb3b` | #109 + 기존 #107, Draft/Open·미병합, CI39050257242 success |
| 서버 PR118 | `bd8818625aa51480e3a06c1fa17d3e223fa1cfbe` | #109 + #108 + #113 deadline, CI39054407191 최종 재조회 success |
| 웹 PR30 | `e9e81efe2c75813623729e4a9b8214bd8430c8b9` | #109 + #108, CI39054384504 success |
| 서버 PR119 | `5a360d47722618efc67f72e6bf2fbc4c97f45e23` | #109 + #110, CI39055254815 조회 당시 진행 중 |
| 웹 PR29 | `ab25c6f8008cba191f2cf96dfb171b189f32a66f` | #109 + #110, CI39054110586 success |

처음 조회 때 없던 서버 #110 branch는 이후 PR119 게시로 확인했다. V14 `add_operator_access_audits`, V15 `add_parser_transport_observations`의 실제 원격 파일과 #110 인계를 읽었다. Lifecycle39055254784 실패 뒤 본문 수정에 따른39055365843 success를 구분하며 제품CI39055254815는 아직 진행 중이다. 각 CI는 해당 SHA의 개별 검증이며 네 기능 결합 검증이 아니다. 서버 develop3232128 / 웹 main9b18397를 ff-only 갱신한 새 작업 브랜치에서 위 #107 기준까지 fast-forward했다. 원격 브랜치 변경 없음.

기존 #107 미커밋 UX 패치 11파일은 부모 전달로 중단 상태 확인: 집중67/69, timer2 미해결, 새 FI/전체/browser/CI 미실행. 필수 기반이나 검증 완료 패치로 취급하지 않는다. 선별 재사용 시 새 명세로 검증한다.

## 합의된 행동과 변경 이유

- 제출 클릭 → 서버에 원문 보존 → 같은 입력 카드에서 비동기 확인 → 명확하면 제출 완료·다른 참여자 대기. 결과에 영향을 주는 모호성에만 본인 A/B/직접 수정. HOST 연락·수정 열기·재분석을 기본 경로에서 제거한다.
- 문맥이 유일한 표현과 의도된 넓은 범위는 되묻지 않는다. 검사 CLEAR는 매칭 성공·가능 후보 존재를 뜻하지 않는다. 검사 AI도 오탐·미탐할 수 있다.
- 정규식 의미 분류·자동 원문 변환·모델에 최종 매칭 위임 없음. 원문은 기존 ECMAScript trim, 1~500 Unicode 코드포인트, 방 최신 입력 합계10,000을 유지한다. 자르기·새 Unicode 정규화 없음.
- 신규 제출은 명확한 입력도 모호성 검사를 위한 **최초 호출은 필요**하다. 기존 이슈의 '명확한 입력 추가 호출0 / 매 제출 호출 안 함'과 달라지는 지점이다. 최초 검사 뒤 질문 생성 전용 호출은 추가하지 않는다. regex로 명확성을 선판정해 호출을 회피하지 않는다.

## 승인된 참여·마감 정책

사용자가 D1/D2 권고안을 승인했다. 신규 PRE_SUBMIT_V1 방은 CLEAR를 현재 원문 revision에 원자적으로 반영한 뒤에만 완료 인원으로 집계한다. 시각/수동 마감은 즉시 마지막 accepted를 동결하며 유예를 만들지 않는다. 수정 검사 중에는 이전 accepted를 사용하고 최초 미확인·미응답·실패 입력은 결과에 반영하지 않는다. 마감 직전 입력에는 미반영 가능성을 알린다. 마감과 결과 도착은 같은 방 잠금으로 선형화한다.

기존 LEGACY 방과 이미 열린 correction round의 복구 계약은 보존한다. 기존 500 코드포인트·방 합계10,000·최소 참여 인원·deadline 자체를 바꾸지 않는다. 새로운 승인 체계는 추가하지 않는다.

## 상태와 데이터 경계

PostgreSQL을 원문·검사·멱등 결과의 기준으로 사용한다. 별도 candidate 원문·검사 이력을 두고 기존 submission version은 accepted에만 추가한다. candidate 저장이 기존 `SubmissionService.save`의 자동 마감을 호출해서는 안 된다. 승인된 정책에 따라 최소 hunk로 저장과 완료 처리를 분리하고 #108 write guard·#113 deadline 검사를 보존한다.

| 서버 상태 | 입력 카드 안내 | 완료 여부 |
| --- | --- | --- |
| 저장 요청 결과 미확인 | 저장 여부 확인 중 / 원문 메모리 유지 | 미확정 |
| QUEUED / CHECKING | 원문 저장됨 · 모호한 부분 확인 중 | 미완료 |
| NEEDS_CHOICE | 저장됨 · 이 부분만 확인해 주세요, A/B/직접 수정·취소 | 미완료 |
| NEEDS_EDIT | 안전한 A/B를 만들 수 없어 직접 수정 필요 / 재질문 한도 안내 | 미완료 |
| FAILED / TIMED_OUT / LIMIT_REACHED | 원문 저장됨 · 확인 실패/시간 초과/검사 한도, 허용된 명시 재시도 | 미완료 |
| CANCELLED / SUPERSEDED | 원문 보존·현재 작업 취소/새 수정으로 대체됨 | 이 작업은 미완료 |
| CLEAR + 완료 트랜잭션 성공 | 제출 완료 · 다른 참여자를 기다려 주세요 | 완료 |
| CLOSED_BEFORE_COMPLETION | 모임 마감 · 이 입력 확인은 반영되지 않음 | 완료로 승격하지 않음 |

검사 결과 CLEAR만으로 완료를 표시하지 않는다. 같은 트랜잭션에서 head/CAS·현재 수집/마감·소유 binding을 확인하고 완료 pointer 및 D1 집계를 적용한 뒤에만 완료다. 현재 방이 마감/분석 중이면 실제 방 상태 안내를 우선한다. 이전 accepted가 있는 수정은 별도의 기존 완료본을 보여주고 새 수정이 반영됐다고 표시하지 않는다.

검사 record는 room/participant/source version·revision, 원문 기준일/zone/locale/검색 범위, protocol/prompt/schema version, 확인한 답변 계보, 상태·check_version, 영속 deadline·attempt claim·provider usage/모델, 소유 binding을 갖는다. 요청 idempotency ledger와 question/answer provenance를 원자 기록한다. A/B 파생 revision의 실제 created_at은 저장시각으로 유지하고 별도 `interpretation_reference_at`을 source의 의미 기준시각으로 상속한다. DIRECT/일반 새 입력은 새 created_at을 기준으로 한다. 신규 protocol A/B만 PRD FR-008D의 일반 created_at 원칙에 이 source anchor 예외를 적용하며, 기존 version의 anchor가 없으면 created_at으로 fallback한다. 최종 frozen batch/공급자 request도 이 anchor를 소비해야 하며 검사 단계만 옛 날짜를 쓰고 최종 분석에서 다시 오늘/내일을 이동시키면 안 된다. 새 데이터는 방 삭제/기존30일 정리에서 FK와 pointer 순서까지 검증한다. 원문·답변·원문 digest를 로그/metric label에 넣지 않는다.

제안 migration은 **V16 이후**다. 재확인 결과 PR117에는 V1~V11+V13, PR118에는 V1~V12가 있다. 이후 PR119에서 V1~V11+V14/V15의 실제 원격 파일을 확인했다. 구현 직전 부모 #110 exact SHA와 원격 전체 inventory를 다시 확인해 충돌 없는 번호를 보고하고 추가한다. 이번 작업에서는 SQL을 만들거나 예약 번호를 재정의하지 않는다.

## 명령·동시성·복원 제안

제안 API는 별도 `POST /submission/checks`(원문 저장+enqueue), `GET /submission/checks/current`, `POST /submission/checks/{id}/answer`, `/retry`, `/cancel`이다. 기존 사후질문 경로의 의미를 바꾸지 않는다. 새 capability `pre-submit-check-v1`은 배포 지원 표지이며 인증을 대신하지 않는다. 최종 native OpenAPI에서 response/오류/필드 이름을 확정하고 웹 타입을 생성한다.

- 저장/답변/취소/재시도 모두 UUID request_id, expected_revision(최초0), expected_check_version, 해당 source/check/question ID를 받는다. 초기 요청에는 check가 없으므로 expected_check_version은 생략한다. idempotency scope는 room+participant+명령+request_id. 같은 key+정규 payload는 기록한 outcome을 재생하고 다른 body는409. 인증·현 소유권 검사가 replay보다 먼저다. 과거 outcome과 현재 head/check/room snapshot을 별도로 반환해 replay가 최신 UI를 완료로 되돌리지 않게 한다.
- room → participant/proof(#108) → exact check/head 순서로 잠그고 CAS한다. 모든 저장·답변·재시도는 실제 현재 권한·#108 write guard·deadline을 transaction 안에서 재확인한다. 동일 expected revision의 두 탭은 최대 한 쓰기만 성공한다. 응답 유실은 private GET으로 복구 후 같은 key 재시도; reload에서 자동 POST하지 않는다.
- 원문 저장+check/outbox 생성은 한 transaction. 외부 AI 호출은 DB 잠금 밖. Worker는 DB에 attempt를 선점·차감한 다음 호출하며 lease/owner/source/check version/deadline을 모두 확인한 단일 승자만 결과를 게시한다. 프로세스 중단·중복 Stream 전달로 deadline·시도 수를 초기화하지 않는다. 외부 공급자 exactly-once를 주장하지 않는다.
- worker가 최신 원문을 수정하지 않는다. 오래된 revision·명령 취소·새 수정·마감 뒤 결과는 SUPERSEDED/CLOSED 또는 폐기하고 accepted head·batch·공개 상태를 바꾸지 않는다. clear와 cancel 경합은 room lock 순서로 하나만 성립한다. clear가 먼저 완료됐다면 cancel은409/current를 반환하며 완료를 취소했다고 표시하지 않는다.
- A/B는 source 전체 문맥·극성·다른 조건과 source 상대 날짜를 보존한 전체 수정 제안이다. 사용자가 선택한 전체 내용을 보여주고 명시 확정했을 때만 새 immutable revision으로 저장한다. 원문과 source 기준일은 불변이며 choice·question·new version을 연결한다. DIRECT는 전체 수정본을 사용자가 편집하며 새 revision 날짜를 기준으로 새 상대 표현을 해석한다. 자동 치환은 없다.
- A/B/DIRECT로 바뀐 입력은 한 번 재검사한다. 다시 같은 모호성이거나 한도 도달이면 NEEDS_EDIT/LIMIT_REACHED로 종결하고 선택을 성공 해석으로 간주하지 않는다. 질문을 억지로 만들 수 없는 복합 모호성도 NEEDS_EDIT이며 CLEAR가 아니다. 답하지 않거나 취소해도 원문을 보존하고 새 완료로 집계하지 않는다.
- 새로고침은 현재 본인 원문·check·질문·완료본을 private GET으로 복원한다. 메모리에만 있던 미저장 타이핑은 복원 보장하지 않는다. 다른 탭의 최신 head와 로컬 미저장 내용을 구분해 보존하고 충돌을 안내한다. `room state_version`과 `check_version`은 별도로 비교한다.
- 웹 key는 방+viewer.context_id+account epoch+check/source ID. 방 이동·계정 전환·로그아웃·proof 재접근/claim·401/403/404 시 관련 private cache와 타이머를 제거한다. 지연 response JSON decode·callback에도 시작 epoch를 재확인한다. HTTP abort는 UI 취소이며 서버 CANCEL 성공과 구분한다. localStorage/URL에 원문·답변·키·credential을 넣지 않는다.

## 실제 #108/#109 소유권 소비

근거: PR118의 `ISSUE_108_INTEGRATION_CONTRACT.md`, `ISSUE_108_107_INTEGRATION_HANDOFF.md`와 실제 resolver/write guard. 계정이 있으면 계정 기록 우선, 무효/만료 Bearer는 유효 guest cookie가 있어도401. 읽기와 새 검사/답변/취소/재시도 모두 공통 READ_OWN으로 검증하고 쓰기는 #108 guard를 추가 통과한다. VERIFIED_GUEST만 본인 기록에 접근하며 rawCredential null의 검증 context도 지원한다. HOST·이름·participant UUID·초대 링크는 타인 권한이 아니다.

REPLACE_ONLY는 원문·질문·답변 조회/새 본인 검사 경로를 모두 거부한다. #108 전용 blind 대체 명령은 그대로 보존한다. **blind 대체를 일반 검사 흐름에 자동 편입하지 않는다.** 편입 시 원문 일치/질문/accepted 여부를 추론하는 oracle과 기존 proof one-shot 계약 문제가 있으므로 별도 부모 통합 검토가 필요하다. 기존 참여 경로를 막거나 권한을 승격하지 않는다.

지속 worker는 enqueue 때 검증한 participant/source 소유 binding을 사용하며 Bearer/쿠키/암호를 저장·전달하지 않는다. claim·회수 등 binding 변경은 publish를 막는다. 브라우저 계정 전환은 기존 계정 소유 작업의 취소 권한을 새 계정에 주지 않는다. 조회/명령은 매번 현재 인증을 검증한다. HOST·일반 room·미반영 endpoint·오류·#110 운영 요약에도 이 신규 원문/질문/답변을 노출하지 않는다.

## 호출 상한·큐·관측: 가역적인 초기값

아래는 합성 검증용 초기 설정안이며 성능 SLO나 실측값이 아니다. 코드·운영 활성화는 아직 없다.

- version당 동시 check1개, 최초 provider call1회. SDK 자동 retry/redirect·precheck fallback 없음. 명시 재시도 최대1회/check, 같은 request replay는 추가0. 명시 retry는 새 execution_id/generation·새 절대deadline을 영속 생성하며 기존 execution의 deadline/카운터는 변경하지 않는다. 재전달/재시작은 새 execution을 만들 수 없다. 구 execution의 늦은 응답은 generation 불일치로 폐기한다. A/B/DIRECT 후 재검사도 총예산에 포함한다.
- 기존 방·참여자 질문 상한3을 보존한다(구 V13 발생분 포함). 신규 검사 admission은 방·참여자당 최대6회로 영속 제한하며 새 revision·reload·취소로 초기화하지 않는다. runtime input preflight를 추가한 경로는 admission당 count최대1+generation최대1, 총 HTTP최대12(count6+generation6)로 구분한다. count 실패도 admission을 소진하고 자동으로 복구하지 않는다. 이전의 단일 generation 경로에서 쓴 '물리 호출6'을 전체 HTTP6으로 주장하지 않는다. 추가 count의 실제 비용/지연·모임 총10원 충돌은 실제 활성화 전 별도 결정 대상이다. 상한 소진에도 원문 보존·직접 수정 저장은 가능하나 완료로 승격하지 않는다. 소진 입력의 마감 참여는 D2를 따른다.
- DB outbox+기존 전달 인프라를 재사용하되 별도 check job type·worker permit을 둔다. 단일 현재 배포의 전역 동시성2, 대기100개 초기값. 다중 worker도 DB claim/permit으로 합계2를 강제한다. 완료 분석의 worker/60초 예산을 잠식하지 않는 별도 실행 풀을 둔다.
- DB 저장 성공 뒤 큐 포화/전달 실패는 저장됨+FAILED_QUEUE로 보존하고 허용된 명시 재시도를 제공한다. 접수 원문이 사라지거나 완료로 표시되면 안 된다. application 재시작은 PG의 미발행 outbox/expired claim을 복구하며 원문을 Redis에 의존시키지 않는다.
- 각 execution의 절대30초는 해당 최초 enqueue 또는 허용된 명시 retry 접수 시 시작, queue wait 포함. provider 최대15초이며 남은 시간-게시 예약3초로 축소한다. admission 예산 부족 시 호출0으로 timeout. 실제 HTTP 본문 수신까지 deadline을 적용한다. 동일 execution의 중단된 claim은 남은 예산을 새로 주지 않고 실패 종결한다. 브라우저 polling은2초부터 최대5초, terminal/인증변경에서 중단한다. NEEDS_CHOICE는 provider execution의 정상 종결이며 사람의 답변 대기에는30초를 적용하지 않는다. 사람 답변은 현재 room의 쓰기 가능/마감/소유권 조건만 적용하고, 허용된 답변 뒤 재검사는 새 source version의 새 execution으로30초를 시작한다.
- 요청의 고정 prompt/schema·현재 원문/context 전체를 UTF-8 최대16KiB로 제한한다(원문500·전체10000은 별도 유지). 응답은 최대2048 output tokens·본문16KiB, 질문160/구절200/전체 수정본각500 코드포인트를 구조 검증한다. 토큰 상한은 공급자가 과금하는 reasoning/thinking 범위도 포함하는지 adapter 계약에서 검증하고, 지원할 수 없는 설정은 호출 전에 실패시킨다. 초과/잘림은 INVALID_RESPONSE이며 성공/기술 자동재시도 사유가 아니다. 이 상한을 실측 비용이나 모임10원 보장의 근거로 쓰지 않는다.
- `check`와 `final_analysis` 지표를 purpose로 구분: queue depth/oldest wait, in_flight, end-to-end/provider latency histogram, result/failure, stale/cancel drop, retry/call/question cap, provider/model usage·estimated cost. 비용 합계는 두 purpose를 모두 포함한다. 미제공 usage/미확인 가격은null, 0으로 대체하지 않는다. #110 전용 파일을 덮어쓰지 않고 최소 adapter 연동 hunk를 인계한다.
- 모임10원 목표는 기존 제품 목표이며 이 설계의 호출수 상한만으로 충족한다고 주장하지 않는다. p50/p95·정확도·원 단위 비용은 미측정이다. 실측이 필요하면 별도 합성 입력·호출수·당시 확인 가격 기반 최대 비용·중단 조건을 먼저 제시하고 승인 대기한다.

## 최종 분석·기존 데이터 이행

개인 check는 ambiguity verdict와 본인 수정 제안만 산출한다. 다른 참여자의 원문·이름은 provider 입력에 넣지 않는다. 개인별 `AREA_1`끼리 같은 장소라는 뜻이 아니므로 **시간 구조화/AREA/후보를 최종 결과로 캐시 병합하지 않는다.** 마감 후 전체 frozen batch의 최종 구조화·장소 근접 그룹·결정론적 매칭을 유지한다. 기존60초·Gemini최대4+Luna최대1·수정분석3회 예산도 그대로다. 재사용은 같은 source/version/context의 check 표시·질문/답변 provenance 및 정확한 기존 batch 결과 재사용 계약으로 한정한다.

V13 질문/답변·구 batch·확정·기존 OPEN correction round는 변경/자동 이관하지 않는다. 신규 흐름은 방 생성 시 protocol을 고정해 새 방부터 적용하는 전환안이다. 기존 방은 구 계약으로 완료하고 새 방에서 구 사후질문 생성/보여주기를 동시에 켜지 않는다. V13 계보와 상한은 보존한다. capability 없는 웹/서버 조합에서 저장만으로 완료를 만들어서는 안 되며 지원 불일치를 명시한다. 구 writer가 새 accepted pointer를 무시할 수 있으므로 새 데이터 작성 뒤 구 writer rollback 가능성을 주장하지 않는다. 배포·운영 전환은 별도 범위다.

후속 UX 자료의 조회 오류 숨김·NO_MATCH stale 갱신·모순 문구는 회귀 테스트 항목으로 유지한다. 미완료 패치를 그대로 가져와 해결됐다고 기록하지 않는다. 관련 PR을 닫거나 강제 push하지 않는다.

## 부모가 조율할 결합 기반과 최소 영향

필요한 조합은 서버 PR117의 #107+#109 **위에** PR118의 #108 실제 proof/write guard와 #113 deadline hunk, PR119의 #110 관측/감사·V14/V15를 선별 통합한 기준이다. 웹은 PR28에 PR30의 #108 참여/재접근과 PR29의 #110 경로를 보존한다. 이 작업에서는 세 PR을 merge하지 않았다. 부모가 단일 편집자를 정해 공통 #109 이력 중복을 피하고 hunk를 대조한 뒤 실제 결합 SHA를 전달해야 한다.

초기 설계는 Git diff 목록과 SQL SHA-256을 `verification/issue-107-pre-submit-base-inventory.json`에 저장했다고 기록했으나, 현재 작업 트리와 이 경로의 Git 이력에서 해당 파일을 확인하지 못했다. 이 누락 자료를 결합 검증 근거로 사용하지 않는다. 위 결합 범위 설명은 당시 텍스트 대조이며 실제 merge conflict 여부를 실행해 확인한 결과가 아니다.

| 영역 | 최소 변경/결합 영향 |
| --- | --- |
| 저장·완료 | SubmissionService의 저장/마감 분리, CollectionClosureService와 count/cohort reader, 새 check service/ports·worker. #108 guard와 #113 동일원문 반환 전 deadline 검사 보존 |
| 영속성 | V16+ check/ledger/head·FK/retention, 기존 V11~15 원본. 세 PR별 sparse catalog를 최종 실제 V1~15(+신규)로 정렬하되 exact allowlist·checksum·history·approval fingerprint 보존 |
| 공급자 | 최종 batch port와 구 v3/clarification reader 보존, 별도 개인 ambiguity request/schema. SDK 자동재시도 없음, 최종 AREA 파이프라인 변경 최소화 |
| 보안 | 공통 CurrentAccountPort/ParticipantOwnershipResolver·#108 actual proof 소비. 신 credential/암호/claim 구현 없음 |
| 공개 계약 | RoomLifecycleDtos의 #108 필드·#107 capability, 새 check endpoint, error DTO/i18n. 통합 native OpenAPI 한 번 생성해 웹 snapshot 갱신 |
| 관측 | #110 transport record는 analysis invocation/attempt FK에 결합돼 있으므로 check를 가짜 analysis로 넣지 않음. 별도 check attempt ledger의 안전한 usage/latency를 같은 고정 purpose dimension으로 보고 |
| 웹 | RoomPage 저장/완료·private cache, 구 ClarificationPanel은 구 방 경계 유지, #108 GuestAccessPanel, client/types/schema/styles. #110 App 경로 보존 |
| 검증/기록 | migration runner/catalog frozen-history 구분, native/browser fixtures, PRD/Architecture/후속 ADR·plan/Worklog. 기존 guard·보호 조건·실측 한계 유지 |

서버 #107/#108 delta의 공유 파일9개, #107/#110 공유11개, #108/#110 공유9개다. 웹은 각각7/2/2개이며 API snapshot·schema를 여러 버전 중 하나로 선택하는 방식으로 해결하지 않는다. #110 catalog는 V12/V13을 현재 의도적으로 거부하므로 단순 파일 추가만으로 통합 완료가 아니다.

## 구현 순서와 완료 증거

1. 승인된 D1/D2를 고정하고 네 기능 결합 기반을 조율한다. 확인한 #110 SHA 이후 원격 inventory 변화 재확인. #108 blind 경로는 현 계약을 보존해 분리.
2. 독립 테스트 설계자가 [테스트 명세](ISSUE_107_PRE_SUBMIT_TEST_SPEC.md)의 공개 행동 테스트·오탐/미탐 평가 oracle을 고정. 컴파일 가능한 skeleton → 유효 assertion RED·fingerprint 보존.
3. 최소 domain/application → PG migration/원자 저장·멱등성 → 합성 provider/queue → native HTTP/OpenAPI → 웹 입력 카드 순서 TDD. 고위험 테스트 설계/구현·최종 검토 역할 분리.
4. 격리 실제 PostgreSQL/Redis·실제 #108 proof 발급+RS256/OIDC 대역·browser flow. mock/합성 결과와 실제 모델 의미 평가는 구분.
5. 필요한 결함 주입 및 정확 복원, 서버 guard/ktlintCheck/assemble/test, 웹 api:check/lint/typecheck/test/build/e2e, 독립 최종 리뷰.
6. 기록 갱신 뒤 승인된 commit·push·별도 Draft PR·정확 HEAD CI. 기존 PR 보존. 유료/운영 호출·실OAuth·Secret 변경은 별도 승인 전까지 하지 않는다. 서버 develop 병합과 정확 merge SHA CI까지 승인되었다. main은 조건부 승인이나 운영 readiness 충족 전에는 보류한다.

이 문서는 승인된 설계이며 구현·검증 진행 상태와 정확 근거는 Worklog 및 .tdd 기록을 따른다. 합성 테스트를 실제 모델 정확도·속도 검증으로 해석하지 않는다. 다음 시작점은 [WORKLOG](ISSUE_107_PRE_SUBMIT_WORKLOG.md)에 둔다.
