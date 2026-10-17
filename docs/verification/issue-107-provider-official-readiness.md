# 개인 provider의 공식 문서 확인 (2026-10-17 UTC)

공개 공식 문서만 읽었다. 인증·유료 모델·운영 endpoint 호출0, 설정 변경0. 아래 확인은 합성 transport 테스트를 실제 API 검증으로 바꾸지 않는다.

- [Gemini 3.8 Flash 모델 문서](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)는 모델 코드와 Structured Outputs 지원, low/medium/high thinking 수준을 명시한다. 현재 요청의 `thinkingBudget:0`이 해당 모델에서 thinking을 비활성화한다는 근거는 이 문서에서 확인하지 못했다. minimal 수준은 지원하지 않는다고 명시돼 있다.
- [GenerateContent reference](https://ai.google.dev/api/generate-content#v1beta.GenerationConfig)는 `maxOutputTokens`를 response candidate 상한으로 설명하고 `thoughtsTokenCount`를 별도 usage로 정의한다. **후속 정정:** [GenerateContent 전용 thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens)에도 thought 포함 infrastructure cutoff와 MAX_TOKENS 종료가 명시돼 있다. guide의 `max_output_tokens`와 REST `maxOutputTokens` 필드 대응을 함께 확인하는 근거다. 최초 조사에서 이 별도 guide를 누락했으며 “현재 API의 공식 합산 cap 근거가 없다”는 판단은 대체한다. 응답 사후검증이 이미 발생한 비용을 취소하지 않는다는 경계는 유지한다.
- 일반 [Thinking guide](https://ai.google.dev/gemini-api/docs/thinking#token-limits-and-max_output_tokens)의 Interactions 설명을 GenerateContent에 전용하지 않는다. 위 동일 API 근거가 있으므로 새 API 전환이 필수라고 판단하지 않는다. 실제 요청 지원·청구·thinkingBudget0 비활성화·input cap을 검증한 것은 아니다.
- [공식 Standard 가격](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash)은 조회 시 2026-12-31까지 USD/1M tokens 기준 input0.75/output3.75(thinking 포함)이다. 2027 가격 인상도 공지돼 있어 실제 평가 승인 시 다시 확인한다. 가격은 API 지원/전체토큰 상한·실청구·환율·세금을 검증한 값이 아니다.

현재 `totalOutputTokenCapVerified=false` 유지가 필요하다. 실제 bean에는 runtime opt-in 바인딩도 없어 단순 키 등록으로 활성화되지 않는다. 신규 PRE_SUBMIT_V1을 이 상태로 main에 배포하면 확인을 완료할 수 없으므로 main을 보류한다. 입력30개(질문필요10/질문금지20)는 고정돼 있지만 input cap/실제 계약·청구·모임 총10원 정책과 호출 예산 충돌은 미해결이다. 정확 최대 비용·p50/p95·정확도를 만들어내지 않는다.

후속 실제 평가가 필요하면 먼저 지원 가능한 API/모델 파라미터와 합산 출력 cap을 확정하고, 입력 목록·반복수·총물리호출수·공식가격·예상최대비용·중단조건을 구체화해 사용자 승인을 받는다. 현재 승인 없는 paid probe나 cap 우회 활성화는 하지 않는다.

재조사·독립 리뷰 후 [실제 평가 준비안](../ISSUE_107_REAL_MODEL_EVALUATION_PLAN.md)에 generation30/retry0/count최대30, 조건부 USD0.32256 산식과 현재 최대 USD 미입증, 정답 누출 방지·고정 분모·독립 판정/지연 측정·중단 규칙을 기록했다. 이 산식을 실제 실행 허가나 보장으로 사용하지 않는다.
