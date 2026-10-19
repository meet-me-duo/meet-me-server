# #107 모임 전체 AI 예산 설계 후보

2026-10-18 UTC. 기존 동작의 코드 확인 기준 `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`(기존 runtime 동작 기준; 새 2단계 bridge·공통 port/default DENY는 별도 TS 구현과 합성 검증을 완료). 이 문서는 **설계 제안**이다. 유료 예산 금액·환율 정책·소진 시 제품 행동을 확정하지 않으며, durable PG migration·최종 분석을 포함한 모든 dispatch의 제품 연결·feature flag 활성화는 수행하지 않는다. 실제 모델·유료 API·운영·OAuth·SSM·Secret 호출은 0이다. 최신 사용자 승인 방향은 충분한 confidence의 CLEAR를 통과시키고 NEEDS_REVIEW/정상 low-confidence는 생성 모델로 재확인하는 2단계다. API 오류/refusal은 별도 실패이며 자동 생성 fallback으로 바꾸지 않는다. 이 방향 승인이 유료 예산·운영 활성화·최대 비용 보장 승인은 아니다. 새 분류 공통 경계는 [분류 설계](ISSUE_107_DECISION_CLASSIFICATION_DESIGN.md)에 별도로 둔다.

최신 실제 검증의 Gemini·OpenAI 합산 USD1 예산 승인은 [별도 평가 실행 계획](ISSUE_107_PRE_SUBMIT_WORKLOG.md#history-issue-107-usd1-evaluation-execution-plan)을 따른다. 이는 제품 room 예산 금액·소진 행동이나 이 영속 원장 구현 완료를 승인한 것이 아니다. 평가도 총액 상한 증명 전 모델 호출0이다.

## 현재 요구사항과 구현의 차이

[PRD](PRD.md)의 414–417행은 모임 1건의 **모든 AI API 호출을 합산하여 10원 미만**이라는 MVP 요구사항을 둔다. 이 요구사항을 변경하지 않는다. 현재 구현의 10원 비교는 알려진 개별 비용의 **사후 관측 지표**이며, 방 단위 누적 지출을 차단하는 예산 원장은 없다.

| 확인한 코드 | 현재 보장과 한계 |
| --- | --- |
| `src/main/kotlin/com/meetme/server/config/ReliabilityConfiguration.kt:34–41`, `shared/adapter/output/observability/MicrometerApplicationMetrics.kt:89–100` | 환율과 `maximumKrwPerMeeting` 설정으로 알려진 `estimatedCostUsd`를 비교한다. 호출 전 원자 예약·방 누적 잔액 검사는 없다. unknown 비용을 0으로 증명하지 않는다. 이하 표의 짧은 경로는 `src/main/kotlin/com/meetme/server/` 기준이다. |
| `precheck/application/service/PreSubmissionCheckService.kt:128–141,471`, `PrecheckProcessingService.kt:84–123` | 개인 검사 실행은 6회 상한이며 room lock 뒤 global admission lock·fresh clock 재검사를 사용한다. 이는 방 예산이나 전체 물리 HTTP 6회 상한이 아니다. |
| `precheck/adapter/output/provider/GeminiPreSubmissionCheckProvider.kt:62–101` | 기본 OFF runtime profile은 전체 generation 요청을 `countTokens`로 확인한 뒤 최대 한 번 생성한다. count 1회 + generation 1회, 공통 최대 15초, 입력 count 4096·출력 profile 2048·LOW, hidden retry 0이다. 개인 실행 6회는 이 경로에서 count 최대 6 + generation 최대 6 HTTP가 될 수 있다. |
| `coordination/application/service/GeminiBatchProcessor.kt:306–320,338–401` | 비용 추정은 알려진 Gemini input/output에 한하며 다른 provider는 null이다. invocation별 Gemini 최대 4회·fallback 최대 1회는 있지만 모임 생애 누적 비용 상한은 아니다. |
| `coordination/application/service/RetryAnalysisService.kt:36–66`, `coordination/domain/CoordinationRun.kt:174–208`, `coordination/application/service/DeadLetterReplayService.kt:26–36` | 실패 HOST 재시도·DLQ replay가 새 run version/invocation으로 진행될 수 있다. 모임 생애에 걸친 재시도 비용 총량을 제한하는 공통 원장은 없다. |
| `precheck/application/service/PrecheckProcessingService.kt:137,237` | attempt/usage 기록과 결과 게시 fencing을 구분한다. 새 예산 정산도 오래된 결과를 accepted로 게시하는 근거가 되어서는 안 된다. |

기존 개인 6회·질문 3회·retry 1회·원문 500 codepoints·방 합계 10000·교정 round 제한·시간/마감/인증/본인 소유권/revision fencing은 그대로다. 최종 분석의 기존 bounded retry 정책도 그대로다. 새 분류의 HTTP retry 0과 기존 count/generation의 hidden retry 0을 늘리지 않는다. 이 문서가 개인 검사와 마감 후 전체 분석/AREA를 대체하거나 합치는 근거는 아니다.

## 공급자 계약에서 확인한 것과 미확인

공식 공개 문서 확인일은 2026-10-18 UTC이며 실제 계정 접근·청구·정확도·지연은 검증하지 않았다.

- [OpenAI Decisions guide](https://developers.openai.com/api/docs/guides/decisions), [create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create), [response reference](https://developers.openai.com/api/reference/resources/decisions): `POST /v1/decisions`, `gpt-6-luna`, 공유 `input`과 `questions`; predicate/choice/score를 지원한다. 이번 공통 분류 후보는 `material_ambiguity`라는 holistic choice 1개이며 8질문 고정안이 아니다. 입력 $0.10/M, 출력·cache read/write 요금 0이며 지역/long-context 배수를 고려해야 한다. 출력 무료는 실제 `output_tokens=0`이라는 뜻이 아니다.
- Decisions의 response는 `answers`, `model`, `usage`이며 usage는 요청 집계 `input_tokens`, `input_tokens_details`, `output_tokens`, `output_tokens_details`, `total_tokens`다. 질문별 사용량 배열이 아니다. 복수 질문을 한 요청에 넣을 수 있다는 사실만으로 공유 입력이 한 번만 과금된다고 단정하지 않는다. 질문 수별 청구 확대 방식·Decisions 전용 사전 billed token cap·계정 eligibility는 미확인이다. 일반 [모델 context 한도](https://developers.openai.com/api/docs/models/gpt-6-luna)나 요청 문자열/HTTP byte 한도를 청구 입력 상한으로 전용하지 않는다.
- [TypeSafe API](https://docs.typesafe.ai/api), [models](https://docs.typesafe.ai/models): `POST /v1/systemone`, `state`와 keyed `questions`, 입력 $0.042/M·출력 무료다. 모델 문서의 context 한도와 state 재사용 설명만으로 질문별 청구 배수나 최대 billed input을 증명하지 못한다. 비교 자료이며 TypeSafe 연결을 선택하지 않는다.
- 현재 Gemini profile의 count 결과·사후 usage 거부·출력 설정은 방어 장치다. `countTokens`가 오류·과금 불명확 요청까지 포함한 전체 청구량의 계약상 상한인지, count 자체의 요금이 무엇인지 별도로 증명해야 한다. [Gemini token counting](https://ai.google.dev/gemini-api/docs/tokens), [GenerateContent reference](https://ai.google.dev/api/generate-content), [pricing](https://ai.google.dev/gemini-api/docs/pricing)을 같은 API/model/profile에 대조해야 한다. [동일 GenerateContent thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens)에는 thought 포함 출력 hard cutoff 근거가 있다. 이를 보존하되 실제 profile 지원·billed input 및 count 요금의 증명과 구분한다. 다른 API의 보장을 전용하거나 출력 제한만으로 전체 청구 상한을 주장하지 않는다.

따라서 현재 자료만으로 새 분류·전체 2단계 평가의 **검증 가능한 최대 USD 비용을 확정할 수 없다**. 단가를 아는 것과 청구 가능한 수량의 최대치를 아는 것은 별개다. 오류 응답·timeout·refusal·invalid JSON도 무과금으로 추정하지 않는다. 공개 문서의 접근 조건은 실제 계정의 사용 허가·무료 quota·신용 한도를 증명하지 않는다.

## 제안하는 공통 경계

PostgreSQL을 유일한 권위로 하여 모임당 하나의 budget aggregate와 물리 외부 요청별 reservation/ledger를 둔다. Redis·프로세스 메모리·Micrometer는 잔액 판단의 권위가 아니다. 실제 테이블명·migration·기존 worker 연결은 후속 구현 설계 대상이다. 병렬로 준비하는 공통 reserve port/default-deny 경계는 유효한 승인 profile이 없으면 신규 pipeline outbound를 0으로 유지해야 한다. port/합성 테스트만 준비하고 durable PG ledger·최종 분석을 포함한 모든 경로 연결·검증된 quote가 없는 단계는 **방 전체 최대 비용 보장 미완료이며 유료 활성화 차단 상태**다.

같은 `roomId`의 precheck Decisions, token count, generation, 마감 후 전체 구조화, fallback, 실패 HOST retry, correction 재분석, DLQ replay를 모두 합산한다. 새로운 participant/account binding/check revision/run version/invocation/교정 round가 새 예산을 지급하지 않는다. 요청 수 집계와 비용 집계는 별도이며, count를 generation 안에 숨기거나 fallback을 별도 무제한 예산으로 두지 않는다. 모임 밖 평가 호출도 별도 평가 scope 원장에 합산하되 운영 room 예산으로 위장하지 않는다.

기존 도메인 outbound port/worker와 외부 HTTP 사이의 공통 예산 경계가 모든 유료 경로를 통과해야 한다. 호출하지 않는 결정론적 매칭에는 가상 요금을 붙이지 않는다. 제품 기본 OFF는 유지하고, 향후 활성화도 유효한 quote와 원자 예약을 우회할 수 없어야 한다.

### 호출 전 quote

물리 요청의 immutable quote에는 다음 근거가 필요하다.

1. provider·API endpoint·고정 model/version·profile·가격 기준일/통화와 적용 가격 계층.
2. instructions/schema/choices/context 등 **전체** 입력을 포함한 최대 billed input 수량, output/reasoning의 최대 청구 수량 또는 무료 계약, 질문별 청구 단위/배수.
3. 지역·long-context 등 최대 적용 배수, count/기타 부속 요청의 요금, 정확한 물리 요청 수와 hidden retry 0.
4. 근거 URL/버전과 요청 payload fingerprint. 개인정보 원문 대신 내부 검증용 해시를 쓰며 해시를 공개 metric label로 내보내지 않는다.

**proven billed cap·적용 가격·필수 배수 중 하나라도 없으면 quote를 만들지 않고 해당 요청의 outbound는 0**이다. count가 필요하다면 count 요청도 별도 quote가 있어야 한다. count 후 generation의 크기를 알게 되는 구조는 무상·무제한 탐색 허가가 아니다. HTTP byte cap·모델 context 크기·평균 token/문자 비율·사후 usage 제한을 증명 대신 쓰지 않는다. 캐시 적중·무료 quota·할인·평균 2차 진입률을 최악 비용에서 선차감하지 않는다.

금액은 고정 소수/정수 최소 단위로 상향 보수 계산한다. quote의 가격/환율 버전은 예약 후 config 변경으로 낮추거나 바꾸지 않는다. 통화와 환율 위험·세금/수수료의 포함 범위 및 예산 금액 `B`는 결정 대기다. 기존 PRD의 엄격한 “미만”을 실제 admission으로 구현한다면 다음 관계를 원자적으로 만족해야 한다.

```text
known settled cost + outstanding worst-case holds + new quote < B
```

unknown 비용은 outstanding hold에 남으며 0으로 합산하지 않는다. 이 산식은 각 quote가 실제 상한이라는 전제에서만 유효하다. 공급자 과청구/계약 변화로 실제 비용이 quote를 초과하는 위험을 DB가 없애지는 못한다.

### 잠금·멱등성·dispatch 간격

모든 경로는 기존 room lock을 먼저 획득한다. 그 뒤 필요한 기존 run/check/global admission lock 순서를 보존하고 budget row를 마지막에 잠그는 공통 순서를 제안한다. 예: precheck는 room → 기존 check/admission → budget, 최종 분석은 room → run/invocation → budget이다. settlement/reconciliation도 room → budget이며 ledger를 먼저 잠근 뒤 room/run으로 역진입하지 않는다. 여러 room에 걸친 transaction을 만들지 않는다. 실제 구현 전 기존 repository SQL 잠금까지 독립 검토한다.

현재 claim/owner/revision/binding/마감/남은 시간 검사가 성공한 동일 transaction에서 실행 claim과 reservation을 기록한다. 최종 lock 대기 후 fresh clock 재검사를 생략하지 않는다. HTTP 동안 DB lock을 보유하지 않는다. `roomId + logical operation id + phase + physical attempt ordinal`로 고정한 unique request identity를 사용해 중복 클릭·outbox 중복 전달·작업 재기동이 같은 예약을 두 번 차감하지 못하게 한다. idempotency key가 같아도 payload/profile이 다르면 새 요청으로 몰래 실행하지 않고 충돌을 거부한다.

예약 commit과 외부 HTTP 사이에는 원자성이 없다. 내부 상태 후보는 `RESERVED → DISPATCH_INTENT → SETTLED/UNKNOWN`이며 예약 취소는 전송하지 않았음을 입증한 경우에 한정한다. dispatch 권한은 CAS로 한 worker만 획득한다. provider idempotency 보장이 없는 상태에서 DB lease만으로 외부 exactly-once를 주장하지 않는다.

| 중단 지점 | 비용 처리 제안 |
| --- | --- |
| 예약 transaction rollback, 전송 권한 획득 전 명백한 취소 | 외부 I/O 0임을 입증한 경우에만 hold 해제. |
| 예약 commit 후 dispatch intent 전 crash | 복구자는 기존 예약을 조회한다. 아직 어떤 worker도 전송 권한을 얻지 않았음을 증명할 때만 취소/기존 작업 계속 중 하나를 선택한다. 새 예약으로 재전송하지 않는다. |
| dispatch intent 후 crash, 연결/응답 유실, timeout/cancel | 전송됐을 수 있으므로 UNKNOWN과 hold 유지. lease 만료·방 마감·사용자 취소만으로 환불하거나 자동 재전송하지 않는다. |
| count 완료 후 generation 미시작 | count 정산과 generation hold를 구분한다. generation 미전송이 입증된 부분만 해제한다. |
| usage가 있는 오류/refusal/invalid product response | 제품 실패와 무관하게 알려진 실제 비용을 정산한다. 비용 근거가 불완전하면 UNKNOWN hold를 보존한다. |

기존 허용된 retry/fallback은 각각 새 physical identity로 남은 **같은** room 예산을 예약한다. 기존 retry 횟수·분기 조건은 유지하며 비용 때문에 자동 재시도를 추가하지 않는다. UNKNOWN hold 때문에 재시도가 거부될 수 있고, 이를 해제할 운영 조정 권한/증거·감사 절차는 미정이다.

### usage와 제품 게시의 분리

비용 정산은 실행 식별자/물리 요청 식별자에 결속하며 제품 결과가 stale인지와 독립적으로 한 번 수행한다. 새 revision·다른 탭 수정·계정 변경·마감으로 결과를 폐기해도 이미 발생한 비용을 지우지 않는다. 늦게 도착한 usage는 해당 ledger만 정산하며 accepted submission·마감 snapshot·새 check 결과를 갱신하지 않는다. 기존 publish CAS·owner/binding·deadline fencing은 그대로 유지한다.

중복 usage는 멱등 처리하고, 불완전/상충 usage는 원래 근거와 함께 reconciliation 대상으로 보존한다. 더 큰 실제 비용이 확인되면 상한에 맞게 잘라 기록하지 않는다. 초과를 기록하고 추가 admission을 차단/관측하는 내부 안전 경계가 필요하며, 사용자에게 보일 행동은 별도 결정이다. 완료된 청구 근거로 실제 비용이 확정된 경우에만 남은 hold를 해제한다. usage 누락을 0으로, 출력 무료를 output usage 0으로, 실패 HTTP를 무과금으로 치환하지 않는다.

기존 room의 과거 호출 비용이 불완전하면 새 budget 생성 시 spent=0으로 소급 초기화하지 않는다. 역사 usage backfill 가능성·unknown 이관·적용 시작 시점은 후속 migration 설계의 필수 결정이며, 이번 문서에서 데이터를 만들거나 정책을 선택하지 않는다.

## 최악 비용과 승인된 2단계 방향

유효한 quote의 upper bound를 `Qdecision`, `Qcount`, `Qgeneration`, `Qfinal`, `Qfallback`으로 표시한다. count가 없는 provider/profile은 해당 phase를 생략하며, 과금 여부가 미확인이면 0으로 두지 않는다. 2단계 개발 방향은 승인됐지만 생성 모델/profile·유료 금액·운영 활성화는 별도 결정이다.

```text
precheck worst case = sum over all admitted checks
                     (Qdecision + Qcount + Qgeneration)
room worst case     = precheck worst case
                    + sum over every final-analysis invocation/retry/correction/DLQ
                      (all primary request quotes + all allowed fallback quotes)
```

승인된 2단계에서도 **모든 참여자의 모든 허용 검사에서 2차가 필요할 수 있다**. 조건부로 기존 개인 6회에 두 단계를 함께 결속하는 설계를 택할 때만 `6 × 참여자 수 × (Qdecision + Qcount + Qgeneration)`을 precheck 부분의 보수 상한 후보로 쓸 수 있다. 현재 standalone classifier가 이미 이 admission에 연결됐다는 뜻은 아니다. stage마다 개인 횟수/15초/예산을 새로 주지 않으며 남은 execution/provider deadline을 공유해야 한다.

최종 invocation당 4+1 제한만으로 실패 HOST 재시도 등 전체 invocation 수의 유한 상한을 증명할 수 없다. 따라서 현재 코드의 횟수만으로 방 전체 최대 비용을 계산할 수 없다. 공통 `B`와 모든 phase의 유효한 quote가 필요한 이유다. 평가도 입력 목록·N회·각 quote·적용 배수·전체 상한·중단 조건을 사전에 제시해야 한다. 현재는 billed quantity의 상한이 미입증이므로 단가에 임의 token 수를 곱한 금액을 “최대 평가 비용”으로 게시하지 않는다. 평균 절감·p50/p95·정확도·실제 청구 실측은 없다.

## 제품 결정 대기와 검증 명세

예산을 precheck와 최종 분석에 어떻게 배분할지, 최종 분석 몫을 미리 보호할지, 소진/UNKNOWN 때문에 참여를 막을지·나중에 재시도하게 할지·다른 제출 경로를 둘지는 **TBD**다. 이는 참여 가능 여부·마감 정책에 영향을 주므로 구현자가 정하지 않는다. 예산 거절을 CLEAR/제출 완료로 바꾸거나 방 마감을 연장하지 않는다. 원문 안전 보존·실패와 완료 구분·마지막 CLEAR 마감 원칙은 유지하되 새 공개 상태/문구/API 계약은 별도 합의가 필요하다.

durable PG 및 모든 final dispatch 구현 착수 전 필요한 검증은 다음과 같다. 이 문서는 해당 SQL/제품 wiring의 구현 완료가 아니다. 병행하는 TS 공통 port/default DENY·fake 검증과 구분한다.

- 실제 PostgreSQL에서 precheck/final이 마지막 잔액을 동시에 예약할 때 한쪽만 성공, transaction rollback과 중복 identity가 이중 차감/이중 전송을 만들지 않는지 확인한다.
- 모든 count/generation/final/fallback/HOST retry/correction/DLQ 경로가 같은 room budget을 통과하며 새 revision/run/계정 변경이 잔액을 재설정하지 않는지 확인한다. 다른 room 간 비용 혼합도 거부한다.
- dispatch 전후 crash·응답 유실·lease 만료·cancel·방 마감·늦은 usage에서 UNKNOWN hold가 보존되고 stale 결과 게시가 0인지 확인한다. 중복/상충 usage와 실제 비용 초과를 별도로 검증한다.
- cap/가격/배수가 빠진 quote는 HTTP 0, count 실패 뒤 generation 0, 명시 OFF는 키 존재와 무관하게 HTTP 0인지 검증한다. mock 비용/usage는 실청구 증거로 승격하지 않는다.
- 전원이 2차에 진입하는 입력·최대 기존 retry/fallback·반복 HOST/DLQ·historical unknown 이관을 합성 검증한다. 평균 통과율을 상한 증명에 쓰지 않는다.
- 독립 fault injection은 reservation 우회·unknown 환불·중복 차감·잘못된 room scope·새 invocation 예산 reset·고정 quote의 가격 변경을 각각 탐지해야 한다. 구현자와 테스트 작성자/최종 리뷰어 역할을 분리한다.

관측에는 bounded provider/model/operation/outcome과 known settled/held unknown/거절 수를 분리한다. 기존 physical admission과 count/generation HTTP 수를 같은 지표로 합치지 않는다. 원문·질문·답변·raw provider body·secret·사용자/room ID를 metric label이나 일반 로그에 넣지 않는다. ledger의 민감한 내부 참조는 접근 통제된 PG에 한정한다. 정산/환율/청구 reconciliation의 실행 주기·보존 기간·권한 역시 미정이다.

이 설계의 완료는 예산 구현·과금 상한 입증·운영 활성화 승인이 아니다. 공통 port/default DENY와 2단계 bridge는 TS에서 구현·합성 검증했지만 후속 결정과 독립 검토 뒤에만 durable migration/전체 wiring·PG 계약 테스트를 진행하며, 현재 기본 OFF·유료 호출 0을 유지한다.
