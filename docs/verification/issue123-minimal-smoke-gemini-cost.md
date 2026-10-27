# #123 최소 Gemini smoke 비용 검토 — 공식 계약 기반 보수 상한

2026-10-18 UTC. 읽기 전용 공개 공식 문서 연구와 현재 source의 모델/profile 확인만 수행했다. 실제 API·model metadata HTTP·계정·키·결제 접근0, 코드/테스트/설정 변경0. 결과물은 이 /tmp 문서뿐이다. 기존 Gemini+OpenAI **평가 전체 합산 USD1 이하 승인**을 유지하며 재승인을 요구하거나 공급자별/시도별로 예산을 초기화하지 않는다.

## 결론

**count 없는 단일 GenerateContent smoke는 token 요금에 관해 USD0.794112라는 공식 계약 기반 보수 quote를 만들 수 있다.** 정상적인 공급자 계약 이행을 전제로 문서상 전체 입력 한도1,048,576을 전부 과금하고 thoughts+candidate 합산 출력2048을 예약한다. 짧은 원문/500자/사전 count4096를 billed input cap으로 바꾸지 않는다. 일반적인 계약 기반 보수 견적에 공급자 위약·무한 hidden 요금까지 절대적으로 배제하는 추가 증명을 요구할 필요는 없다.

다만 이 값은 **Standard 요청의 token 요금**이다. 실제 합산 평가 잔여액·계정 적용 세금/통화 환산/수수료를 아직 확인하지 않았으므로 현재 all-in USD1 실행 통과는 아니다. 비용 증명 부족을 “작은 1회 평가도 원천적으로 불가능”이라고 일반화하지 말고 아래 남은 USD0.205888의 구체적 조건으로 좁혀 확인하면 된다. 이번 연구자는 실행하지 않는다.

count1+generation1안은 FAQ 무료 설명이 REST countTokens를 가리킨다는 해석을 채택할 경우 같은 token quote가 가능하다. 다만 명시 alias가 없어 그 부분은 확인 사실과 추론을 구분한다. 불확실성을 없애는 평가 대안은 count0/direct generation1이다. 이는 제품의 count gate 제거 제안이 아니다.

## 1. 현재 code에서 확인한 사실

worktree `/workspace/issue123-durable-budget/server`의 `src/main/resources/application.yml:40`은 `gemini-3.8-flash`다. `precheck/adapter/output/provider/PrecheckProviderContract.kt:40–44`는 candidateCount1·maxOutputTokens2048·runtime thinkingLevel LOW, `:52–60`은 전체 generation JSON을 generateContentRequest로 싸 count한다. `GeminiPreSubmissionCheckProvider.kt:76,107–117,295`는 해당 출력 cap과 count→generateContent 경로를 유지한다. 짧은 사용자 원문 외 system instruction·고정 context·JSON schema도 실제 전체 입력이다.

이 검토는 기존 제품 provider 기본 OFF나 영속 authority의 DENY를 바꾸지 않는다. 새 운영 가격/profile을 seed하지 않으며 standalone 평가 옵션이 제품 활성화를 의미하지 않는다.

## 2. 직접 확인한 공식 사실

| 항목 | 공식 사실 및 출처 |
| --- | --- |
| 모델의 전체 한도 | [Gemini3.8Flash model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash): input token limit1,048,576·output65,536. [GenerateContent token guide의 context windows](https://ai.google.dev/gemini-api/docs/generate-content/tokens#context-windows)는 Gemini3의 입력/출력 budget이 별개임을 설명한다. 입력은 원문만의 한도가 아니다. |
| 낮춘 출력 cap | [GenerateContent 전용 thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens): max_output_tokens는 thoughts를 포함한 생성 token 합산의 infrastructure hard cutoff, reasoning 중 cap에 도달하면 MAX_TOKENS/잘린 응답이어도 발생한 생각 token을 청구한다. [REST GenerationConfig](https://ai.google.dev/api/generate-content#v1beta.GenerationConfig)의 maxOutputTokens와 대응한다. Interactions 근거를 빌릴 필요가 없다. LOW는 생각 OFF가 아니다. |
| Standard 단가 | [Gemini Developer API pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash): 2026-12-31까지 input USD0.75/M, output(thinking포함) USD3.75/M. 2027-01-01부터 각각1.50/7.50이므로 날짜가 넘어가면 재견적한다. Priority는1.35/6.75이며 다른 가격이다. 무료 quota/캐시 할인/Batch·Flex 할인을 상한에서 선차감하지 않는다. |
| request 가격 계층 | [GenerateContent ServiceTier](https://ai.google.dev/api/generate-content#ServiceTier): unspecified의 기본이 standard다. 계정의 rate-limit/billing usage tier와 request의 Standard/Priority 가격 계층은 다르다. 별도 priority 요청/header·다른 backend를 보내지 않는 고정 REST wire이면 Standard quote를 적용할 근거가 있다. 실제 평가 runner의 wire 동결/검토는 실행 전에 필요하다. |
| 실제 count REST | [CountTokens reference](https://ai.google.dev/api/tokens): models.countTokens는 tokenizer를 실행하고 count를 반환하며 `POST /v1beta/{model=models/*}:countTokens`다. generateContentRequest는 system instructions/function declarations 등 전체 입력을 받는다. [전체 method index](https://ai.google.dev/api/all-methods)의 models에는 countTokens가 있으며 별도 GetTokens method는 확인되지 않았다. |
| 무료 FAQ | [Gemini billing FAQ](https://ai.google.dev/gemini-api/docs/billing#frequently-asked-questions)는 GetTokens API가 billed되지 않고 inference quota에도 잡히지 않는다고 설명한다. 같은 FAQ가 token 계산 안내에서 GenerativeModel.count_tokens를 참조한다. 다만 GetTokens=countTokens라고 직접 등호/redirect를 명시한 문장은 확인하지 못했다. |
| 실패/결제 | 같은 [billing FAQ](https://ai.google.dev/gemini-api/docs/billing#frequently-asked-questions)는 400/500 token 무과금·쿼터 소진을 명시한다. 이는 timeout/유실/모든 오류 무과금 보장은 아니다. [billing guide](https://ai.google.dev/gemini-api/docs/billing)는 선불 최소 충전USD5·약10분 계상 지연 초과 가능성을 설명한다. 새 충전/자동reload로 USD1을 보장하려 하지 않는다. |

## 3. 추론: 전체 모델 한도를 이용한 quote

범위는 **한 합성 입력·질문1·candidate1·일반 text-only·동기 generateContent POST1**이다. tools/search/maps/files/cache 생성/grounding/자동 continuation/재시도/fallback/OpenAI/최종 배치/인프라 생성은0이다. 고정 REST 요청을 동결하고 byte cap·schema를 로컬 검증하며 다른 유료 기능이 없음을 확인한다.

정상적으로 처리 가능한 전체 입력의 모델 한도가1,048,576이므로 전부 정상 Standard 입력 요율로 과금된다고 잡는 것은 보수적 계약 해석이다. system/schema/컨텍스트 overhead를 별도로 누락시키는 500자×평균token 계산을 쓰지 않는다. 실제 입력이 짧거나 count와 generation usage가 조금 다른 문제를 이 큰 모델 상한이 흡수한다. 이것은 특정 payload의 정확 tokenizer 결과나 실제 usage 실측이 아니다.

```text
입력 상한 요금 = 1,048,576 × USD0.75 / 1,000,000 = USD0.786432
출력 상한 요금 =     2,048 × USD3.75 / 1,000,000 = USD0.007680
직접 generation 1회 token 요금 quote               = USD0.794112
합산 USD1 대비 잔여(기존 평가 지출0일 때)            = USD0.205888
```

이는 API token 요금의 계약 기반 상한이며 공급자 계약 위반까지 보장하는 보험이나 실청구 영수증은 아니다. 그 수준의 절대 증명은 일반적인 소액 평가 사전 견적의 필요조건으로 삼지 않는다. 반대로 실제 잘린 결과를 성공/정확도 통과로 집계하지 않는다. 실패/timeout이어도 worst-case hold는 유지하고 자동 재시도하지 않는다.

## 4. count1 + generation1 선택지

**확인 사실:** 실제 REST 명칭은 countTokens이고 FAQ 무료 명칭은 GetTokens다. **합리적 추론:** 같은 billing FAQ가 count_tokens로 토큰 계산을 안내하고 method index에 별도 GetTokens가 없는 점에서, 무료 FAQ가 이 token counting 기능을 관용적으로 가리킬 가능성이 높다. 두 이름이 다르다는 이유만으로 별개 유료 서비스가 있다고 단정하지 않는다.

그러나 직접 alias/정확 countTokens 요율 문장이 없어 “공식 REST 요금0을 직접 확인했다”는 표현은 과장이다. root가 이 동일 기능 해석을 승인된 quote 근거로 수용하면 count token요금0 + generation0.794112로 계산할 수 있고 물리 HTTP는2회다. 더 엄격한 근거가 필요하면 공개 공식 FAQ/지원 설명에서 연결을 확인하거나 count 없는 direct1회 옵션을 택한다. 이를 알아내기 위한 무승인 paid probe나 실제 계정/API 조회는 하지 않는다.

count 비용을 모른 채 임의로 generation input 요율과 같다고 가정하는 것도 계약 근거가 아니다. 그런 요금을 적용하는 단순 민감도 예시는 두 번의 전체 입력을 계산하면 USD1.580544로 이미1을 넘으므로 count 요금 불명확성을 잔여에 숨길 수 없음을 보여줄 뿐, 실제 count 가격 주장이나 quote는 아니다.

## 5. 실제 all-in gate: 무엇이 남고 왜 필요한가

```text
C_prior(기존 Gemini+OpenAI 평가 known spend + UNKNOWN holds)
  + USD0.794112 + Q_count(없으면0)
  + 상한이 확인된 세금/환산/결제 부대비용 <= USD1
```

- direct generation1·C_prior0이면 추가 금액 상한은 USD0.205888이다. 별도 고정 수수료가 없고 비례 부대비용만 있다면 최대 약25.9268%까지 수용 가능하다. 가령 **단순 산술 예시**로10%만 추가되면 USD0.87352320,20%만 추가되면 USD0.95293440이다. 실제 사용자의 VAT/카드 요율을10%나20%라고 추정한 것이 아니다.
- [Gemini Additional Terms의 Payment Terms](https://ai.google.dev/gemini-api/terms#payment-terms)는 Paid Services 결제에 Google Cloud Payment/Tax/지역·통화 조항을 적용한다. [GCP Terms §2.2](https://cloud.google.com/terms/)는 적용 세금을 고객이 부담한다고 한다. [Cloud billing currency](https://docs.cloud.google.com/billing/docs/resources/currency)는 계정 통화·월별 환산을 설명한다. 따라서 public USD token 가격표만으로 실제 납세지·면세 여부·통화/결제 수수료를 특정할 수 없다.
- 이것이 구체적 blocker인 이유는 잔여 .205888을 넘는 surcharge 또는 C_prior가 있으면 USD1 합산 승인을 위반하기 때문이다. 막연한 “세금은 언제나 알 수 없다”가 아니라, 계정의 비민감 요율/통화/해당 결제 조건과 기존 평가 누계를 확인해 위 부등식을 만족하면 해소할 수 있다. 새 금액 승인/설정 변경을 요구하지 않는다. 이 연구자는 그 계정 접근 권한이 없다.
- Standard REST 기본값을 고정하면 단순히 rate-limit tier 미조회만으로 가격이 무한하다고 보지 않는다. 다만 실제 runner가 Priority를 선택하면 동일 token cap의 요금도 USD1.4294016이므로 해당 wire를 쓰면 안 된다. 2027 Standard 가격도 두 배여서 이 quote를 재사용할 수 없다.
- 무료 quota/크레딧 존재·신규 충전·prepay 잔액/중단 지연은 token 가격 상한과 별개다. 평가를 위해 새 최소USD5 구매가 필요하면 현재USD1 승인에서 자동 실행하지 않는다. 기존 configured 키만으로 접근·청구계층을 확인했다고 하지 않는다.

## 6. 검토 가능한 실행안과 경계

우선 후보는 **count0 + direct GenerateContent1**의 격리 smoke다. 기존 고정 모델·전체 prompt/context/schema와 candidate1/2048/LOW를 사용하되 사용자 데이터·holdout0, 유효한 합성 조건1개만 쓴다. 정확 endpoint/payload SHA·Standard wire·실제 전체 HTTP 상한1·retry/redirect/fallback/continuation0과 기존 평가 합산 잔액을 root가 확인하고 위 all-in gate를 만족하면 기존USD1 승인 안에서 검토할 수 있다. 이번 연구가 그 실행을 수행하거나 승인을 새로 부여하지 않는다.

count 생략은 제품의 count gate나 managed budget DENY 우회가 아니다. 기존 production provider를 그대로 호출하면 count가 먼저 나가므로 HTTP1 옵션은 격리 평가 runner의 별도 정확 실행 scope로 준비해야 한다. 실제 runner가 없거나 위 조건이 입증되지 않으면 호출0이다. 후속 Decisions/최종 분석도 같은USD1 잔액을 공유하며 이 smoke 성공이 추가 호출·정확도·방10원·운영 활성화를 승인하지 않는다.
