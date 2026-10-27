# #107 합산 USD 1 평가 실행 계획 후보

## 최신 최소 smoke 검토 (2026-10-18 UTC)

현재 최종 상태: 부모가 허용한 별도 후속1회를 포함한 실제2단계2시도는 생성에서 첫503·후속공통15초TIMEOUT으로 중단했다. 누계Decisions6/count2/generation시도2(성공0), D보고usage3085/0의 기본환산USD0.0003085/보수2.2배USD0.00067870이며 Gemini청구UNKNOWN이다. 누적예약USD0.70918144/추가여유USD0.29081856를유지하고 다음동일full견적USD0.31854592를더한USD1.02772736이승인USD1을초과하므로 추가dependent실제호출은중단한다. 아래503/5회문단과원래4회문단은각당시이력이고 [최종 실검증 기록](ISSUE_123_LIVE_TWO_STAGE_VALIDATION.md)을따른다.

이후 별도 [실제2단계 검증](ISSUE_123_LIVE_TWO_STAGE_VALIDATION.md)은 사전 oracle/plan 고정과 전체 guard/CODE GATE 통과 뒤 새A200 LOW_CONFIDENCE→count200/530→generationHTTP503에서 재시도 없이 중단했다. 누계Decisions5·count1·생성시도1이며 성공 생성 질문/usage 증거는 미확보다. 원래4회 FAIL/STOP은 보존한다. 알려진Decisions usage2573/0의 보수 환산USD0.00056606·Gemini청구UNKNOWN, 누적 예약USD0.39063552/추가 비용 여유USD0.60936448를 유지했다. 아래 첫 smoke 문단은 최초4회 평가의 결과이며 전체 현재 단계와 구분한다.

최신 실행 결과는 [#123 실제 Decisions 진단](ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md)을 따른다. 전체 context 견적 대신 고정2397~2479B/질문1에 BPE byte 상한과65,536token framing 여유·2.2배 단가를 적용했고 독립 sanity 검토 뒤 실제 Decisions4회를 실행했다. HTTP/production parser는4회 모두 통과했으나 오전/오후 사례가 rawCLEAR/confidence0.06으로 사전 기대와 달라 중단했고 마지막1개는 미호출이다. 운영 threshold·실제2단계/생성/최종 분석·invoice 검증 완료는 아니다. 아래0회/미실행/계산범위 대기는 이 실행 전의 이력이다. 기존USD1 합산과 세금/결제 여유는 유지하며 새 금액 승인을 요청하지 않는다.

아래 5개/최대15 HTTP는 이전 후보이며 현재 우선 범위는 짧은 새 합성 입력1·질문1·retry/fallback0의 격리 단일 추론이다. 전체 영속 원장 구현 완료를 이 독립 smoke의 선행 조건으로 삼지 않는다. 기존 합산 USD1 승인은 유지하며 금액 재승인을 요청하지 않는다.

기존 키의 OpenAI Luna/Gemini3.8Flash 모델 메타데이터 GET은 각각200이며 비밀값은 출력·저장하지 않았다. 조회 접근 확인과 실제 추론 endpoint 권한/계약 검증은 다르며 추론은 아직0회다. 새 계정·키·결제·운영 설정 변경은 없다.

Decisions는 공식 입력 전용 요금과 일반 Luna 전체 문맥/long·region 배수를 적용하면 USD0.231의 보수 quote가 가능하지만, 문맥 한도를 Decisions 청구 상한에 적용하는 부분은 추론이다. 전용 hard cap이 확인됐다고 표현하지 않는다. [별도 Gemini 공식 검토](verification/issue123-minimal-smoke-gemini-cost.md)는 count 없는 직접 GenerateContent1에 전체 입력 한도1,048,576·thought 포함 출력2048·Standard 요율을 적용해 token quote≤USD0.794112를 제시한다. 제품 count gate나 production DENY를 우회해 활성화한다는 뜻은 아니다.

count는 불명 과금을0으로 잡지 않고 단일 smoke에서 제외할 수 있다. 계정별 세금·환산/결제 수수료는 미확인이므로 USD1이 공급자 API 사용요금 기준인지 해당 부대비용 포함인지 확인 질문을 남겼다. 금액 증액 질문이 아니다. all-in 기준이면 공급자 세금/결제 조건에서 잔여 범위 내 비용 상한을 확인해야 한다. 기본 응답 shape와 실제 production parser의 offline 검증·usage·단일 지연 표본은 분리해 기록하고 작은 사례를 정확도/p50/p95 성과로 확대하지 않는다.

아래는 최소안으로 좁히기 전의 공식 근거와 이전 계획 이력이다.

2026-10-18 UTC. 기존 코드 기준 HEAD `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`; 병렬 작성 중인 새 구현의 검증 완료를 뜻하지 않는다. **현재 판정: 비용 증명 및 실제 권한 확인 전 BLOCKED, 모델 호출 0.**

최신 사용자 승인은 Gemini와 OpenAI를 합쳐 **평가 전체 USD 1 이하**다. Decisions·생성 보완·최종 분석·count/부속 HTTP·재시도/fallback·실패/응답 유실·세금/불확실성을 모두 포함한다. 공급자별 USD 1이나 run마다 새 USD 1이 아니다. 이 금액 승인을 제품 모임 예산·키/설정 변경·운영 활성화·배포 승인으로 확장하지 않는다. 기존 문서의 “금액 미승인”은 이 최신 평가 금액 승인으로 대체되지만 **옛 12개/최대 36 HTTP 제안의 실행 횟수·scope·비용 증명은 승인된 것이 아니다**.

[외부 평가 준비](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md), [독립 평가](ISSUE_107_DECISION_EVALUATION.md), [공유 예산 설계](ISSUE_107_SHARED_AI_BUDGET_DESIGN.md)를 먼저 대조했다. 이 문서만 작성하며 제품·test·fixture·다른 문서·Secret을 바꾸지 않는다. holdout 본문을 다시 읽거나 프롬프트 개발에 이용하지 않았다. 현재 실제 usage·청구·p50/p95·정확도 실측은 없다.

## 공식 계약 재확인

아래 공개 1차 출처는 2026-10-18 UTC에 직접 확인했다. 실제 계정의 model/API 접근·가격 계층·통화·납세/결제 상태는 확인하지 않았다. root가 기존 OPENAI_API_KEY/GEMINI_API_KEY의 configured 여부만 비밀값 비출력으로 확인했다고 전달했으며, 이 문서 작성자는 env 값을 읽지 않았다. 키 존재는 endpoint 권한·tier·세금·실행 성공의 증거가 아니다.

| 대상 | 확인한 계약 | 상한으로 전용할 수 없는 부분 |
| --- | --- | --- |
| [OpenAI Decisions guide](https://developers.openai.com/api/docs/guides/decisions) | `gpt-6-luna`, 입력 $0.10/M; 출력/cache read/cache write 요금 0. 지역 처리·long-context 입력 배수 적용. | 무료 출력은 usage 0이 아니다. 복수 질문의 공유 입력이 한 번만 과금된다는 청구식은 확인하지 못했다. 이번 후보는 choice 1개로 제한한다. |
| [Decisions create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create) | input과 questions 1..200, 현재 모델은 gpt-6-luna. request 집계 usage를 반환한다. | Decisions 전용 `max_input_tokens`/`max_output_tokens`는 확인되지 않았다. 문자열 길이·HTTP 16KiB·원문 500 codepoints는 billed input hard cap이 아니다. |
| [GPT-6 Luna model](https://developers.openai.com/api/docs/models/gpt-6-luna) | 일반 모델 context 1,050,000·max output 128,000. 272K 초과 입력 2배, 지역 +10%; 다른 API의 출력 단가는 $0.50/M. | 일반 context 한도를 Decisions 질문/instructions까지 포함한 최대 청구량 보장으로 옮기지 않는다. Decisions 출력 무료와 다른 API 가격도 섞지 않는다. |
| [Gemini 3.8 Flash pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash) | Standard 입력 $0.75/M·출력(생각 포함) $3.75/M, 2026-12-31까지. 2027-01-01부터 각각 $1.50/$7.50. Priority/Batch/Flex는 별도 가격이다. | 할인/무료 quota/캐시 적중을 선차감하지 않는다. 날짜·계층이 바뀌면 quote를 폐기하고 재검토한다. 검색/Maps 등 별도 유료 tool을 사용하지 않는다. |
| [Gemini model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash), [GenerateContent](https://ai.google.dev/api/generate-content), [GenerateContent 전용 thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens) | 모델 입력 1,048,576·출력 65,536. 동일 API의 thinking guide는 max_output_tokens의 thoughts 포함 infrastructure hard cutoff와 MAX_TOKENS 종료를 명시한다. REST maxOutputTokens와 대응하는 공식 합산 출력 cap 근거가 있다. | “Interactions에서만 보장”이라는 최초 조사 판단은 정정됐다. LOW는 thinking OFF가 아니며 thinkingBudget0으로 우회하지 않는다. 공식 cap은 실제 계정의 profile 지원/usage/청구 실측 완료와 구분한다. |
| [Gemini tokens](https://ai.google.dev/gemini-api/docs/tokens), [billing FAQ](https://ai.google.dev/gemini-api/docs/billing#frequently-asked-questions) | countTokens REST 예제가 있으며 billing FAQ는 `GetTokens` API 무료와 400/500 token 무과금(쿼터 소진)을 명시한다. | `GetTokens` 무료 보장이 현재 wire의 `:countTokens`와 같다는 연결은 미확인이다. count를 무료로 가정하지 않는다. 특정 400/500 보장을 timeout/유실/다른 오류로 넓히지 않는다. |

현재 코드의 전체 generateContentRequest 사전 count≤4096·사후 usage 초과 거절과 2048 LOW는 방어 장치다. 동일 endpoint/model/profile의 thought 포함 출력 cap은 위 공식 근거가 있다. **전체 billed input 상한**과 count 요금·실제 계정 profile 지원/청구 확인이 남아 있다. [공식 count reference](https://ai.google.dev/api/tokens)의 예시도 count와 generation prompt usage가 달라 동등한 청구량으로 전용하지 않는다. 사후 결과 거부는 이미 발생한 청구를 취소하지 않는다.

## rate/spend 제한과 세금

- [OpenAI rate limits](https://developers.openai.com/api/docs/guides/rate-limits)는 조직/프로젝트·모델별 RPM/TPM 등을 구분한다. 일반 Luna Build 표의 RPM 5000·TPM 2M을 실제 Decisions 계정 할당량으로 단정하지 않는다. [spend limits](https://developers.openai.com/api/docs/guides/spend-limits)는 alert가 차단이 아니며 hard limit도 전파 지연으로 소폭 초과할 수 있다고 명시한다. USD 1 정확 상한 증명 대신 쓸 수 없다.
- [Gemini rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)는 프로젝트 단위 RPM/입력 TPM/RPD와 계정 조건부 spend rate를 구분한다. Tier 1 spend rate는 USD 10/10분이며 실제 할당량은 AI Studio에서 확인하도록 한다. 이 제한은 합산 평가 USD 1 한도가 아니다.
- [Gemini billing](https://ai.google.dev/gemini-api/docs/billing)의 선불 최소 충전은 USD 5이며 약 10분 billing 지연에 따른 초과 가능성도 있다. 예산 USD 1을 이유로 새 충전·자동 reload·한도/결제 방식 변경을 하지 않는다. 기존 prepaid 잔액이나 hard cap만으로 평가 최대 청구를 증명하지 않는다.
- [OpenAI Services Agreement §6.3](https://openai.com/policies/services-agreement/)의 요금은 세금 별도다. 실제 적용 세금·공급자/결제 수수료와 USD 환산 상한은 미확인이다. [Google billing currency](https://docs.cloud.google.com/billing/docs/resources/currency)는 계정 통화와 월별 환산을 설명한다. 저장소의 KRW/USD 관측 환율이나 임의 VAT 10%를 실제 합산 청구의 증명으로 쓰지 않는다.

## 작은 진단 pilot 제안: 5개, 물리 요청 최대 15

이 횟수는 **검토 가능한 새 제안이며 실행 승인/실행 결과가 아니다**. development에서 NORMAL·CONTEXT_RESOLVED·AMBIGUOUS·ADVERSARIAL·UNINTERPRETABLE 각 1개를 `SHA256(raw_text UTF-8)` 오름차순으로 선택한다. subset/corpus/prompt/schema/profile/threshold와 정확 payload SHA를 실행 전에 고정한다. 여기서는 새 fixture나 subset 파일을 만들지 않는다. holdout은 사용하지 않는다. 5개는 진단이며 전체 차원 gate·운영 정확도 판정이 아니다.

| 호출 phase | 이 pilot의 최대 물리 요청 | 조건 |
| --- | ---: | --- |
| OpenAI Decisions | 5 | 사례당 holistic choice 1개, POST 1회. |
| Gemini countTokens | 5 | 정상 review/low-confidence가 2차 진입할 때 전체 frozen 생성 요청을 count. |
| Gemini generateContent | 5 | count 통과 후 1회; 전원 2차 진입을 최악으로 예약. 충분한 CLEAR/API 오류/refusal 뒤 강제 호출하지 않음. |
| 최종 방 분석·기존 primary retry·fallback·HOST retry·correction·DLQ | 0 | 초기 pilot에는 연결하지 않는다. 후속 평가 시 같은 USD 1 잔액에 별도 quote/호출 manifest를 추가해야 한다. |
| canary·smoke·재실행·SDK hidden retry·redirect·continuation·도구/검색·업로드 | 0 추가 | 첫 사례가 canary를 겸한다. 실패 사례 교체/재호출 없음. |

동시성 1, 사례당 공통 provider deadline 최대 15초, stage마다 새 15초를 지급하지 않는다. 기존 production 4+1 retry 정책을 수정하는 제안이 아니다. production scheduler/worker·전체 room 분석이 함께 도는 앱 대신 해당 고정 subset만 dispatch하는 격리 평가 경로가 필요하며, 그 runner 준비/검증은 아직 완료로 주장하지 않는다. 부속 model 조회/권한 smoke도 외부 요청을 임의 추가하지 않는다.

## 금액 산식과 현재 차단

승인된 합산 한도는 `B = USD 1`이다. 계획용으로 직접 API 요금 quote 총합을 USD 0.10 이하로 제한하고 나머지 USD 0.90은 세금·수수료·환산·후속 평가를 위한 **미사용 headroom 후보**로 둔다. 이는 세금이 0.90 이하라는 증명이나 새 하위 예산 확정이 아니다. 실제 세금/부대비용의 보수 상한을 확인하지 못하면 headroom이 커 보여도 dispatch하지 않는다. 후속 평가가 0.90을 자동으로 지급받지도 않는다.

```text
전체 평가의 known settled + UNKNOWN worst-case holds
  + 새 물리 요청의 proven quote + 모든 세금/부대비용의 보수 상한 <= USD 1

pilot quote = 5 × Qdecisions + 5 × Qcount + 5 × Qgeneration
```

검토용 조건부 계산 예시는 다음과 같다. **4096 billed input·2048 billed total output·OpenAI 배수≤2.2가 모두 계약상 증명됐다고 가정할 때만**:

```text
Qdecisions = 4096 × $0.10 / 1M × 2.2                  = $0.00090112
Qgeneration = (4096 × $0.75 + 2048 × $3.75) / 1M      = $0.010752
5 × (Qdecisions + Qgeneration)                       = $0.05826560
pilot = $0.05826560 + 5 × Qcount + 세금/부대비용
```

이 예시는 산술이며 현재 최대 비용 견적이 아니다. Decisions의 전체 billed 입력 4096, count 요금, 실제 적용 배수/세금이 아직 미입증이다. GenerateContent thought 포함 출력 2048은 고정 profile에 대응한 공식 cap 근거를 사용하며 실청구 검증 완료로 표시하지 않는다. 실제 예상 usage나 가격만 곱해 최대 USD 1을 보장한다고 보고하지 않는다. 평균 2차 진입률·응답 성공률·캐시 할인은 산식에서 빼지 않는다. 아직 **실행 가능한 검증된 최대 비용 값은 없고, 지금 허용되는 물리 모델 요청은 0**이다.

## 실행 전 통과 조건과 중단

1. 같은 API/model/profile의 전체 청구량 cap·count 요금·적용 가격/배수·세금/부대비용 상한을 근거와 함께 확정한다. 다른 API cap이나 무근거 verified flag를 쓰지 않는다. 부족하면 호출 0을 유지한다.
2. root가 필요 시 비밀값 비출력으로 기존 인증 configured 여부와 실제 endpoint/model 접근 준비 상태만 확인한다. 이 문서 작성자는 키/로그인/권한/계정 조회를 수행하지 않는다. 키 존재는 API 접근/유료 호출 성공의 증거가 아니다. 새 키·결제·한도·운영 Secret 변경은 별도 범위다.
3. root가 새 5개 subset/phase별 상한과 비용 증명을 검토하고 실행 scope를 확정한다. 사용자에게 USD 1 금액을 다시 승인받는 절차를 만들지 않되, 금액 승인만으로 이전 제안 횟수가 자동 확정됐다고 해석하지 않는다.
4. 격리 runner가 모든 provider/phase를 하나의 평가 reservation ledger에 원자 기록하고 재기동해도 누계를 보존하는지 합성 검증한다. 예약→dispatch gap, 중복 전달, timeout/crash·UNKNOWN hold를 검증하고 순차 dispatch한다. 현재 default DENY 제품 budget을 평가를 위해 우회/활성화하지 않는다.
5. 첫 사례 결과가 충분해도 단가·usage·cap·profile 불일치, 429/5xx/401/403, timeout/유실·잘림·malformed, 추가 phase/retry/정보 노출, 예상 외 청구/알 수 없는 비용이면 즉시 중단한다. 부분 지연이나 upstream 진행 가능성이 있는 실패 뒤 새 요청을 보내지 않는다. UNKNOWN은 최악 예약을 유지하고 재호출·hold 환불을 하지 않는다. 알려진 오류 무과금 근거가 있더라도 시도 횟수는 소진한다.
6. case/phase별 계획·전송·유효/실패·미실행, known/unknown usage와 비용·잔여 hold·실측 지연 표본 수를 보고한다. finite pilot의 모델 품질이나 p50/p95를 미리 채우지 않는다. 최종 방 분석/fallback의 미실행을 성공으로 표시하지 않는다.

현재는 1번 비용 증명부터 미통과다. mock/full gate·게시 준비는 이 차단과 독립적으로 계속할 수 있다. USD 1은 이번 합성 평가의 합산 승인으로만 보존하며 제품 모임 10원 요구·durable PG room 원장·전체 최종 분석 retry 제어·운영 활성화와 구분한다.
