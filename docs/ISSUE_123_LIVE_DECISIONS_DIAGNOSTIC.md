# #123 최소 실제 Decisions 진단

2026-10-18 UTC. 기존 Gemini+OpenAI 합산 USD1 승인 안에서 격리 HTTP smoke1회와 독립 사전 정답의 한국어 사례를 확인했다. 실제 Decisions 추론은 **4회**, retry/fallback/count·Gemini 추론은0회다. 운영 flags·가격 registry·confidence 정책을 활성화하거나 Decisions를 병합하지 않았다.

| 사례 | 사전 기대 | 실제 raw choice / confidence | HTTP 지연 |
| --- | --- | --- | --- |
| 절대 날짜·시간·서울역 | CLEAR | CLEAR / 0.83 | 1011.1ms |
| 의도된 주말 종일 | CLEAR | CLEAR / 0.88 | 201.9ms |
| 오후3시부터5시의 문맥 | CLEAR | CLEAR / 0.79 | 196.2ms |
| 오전/오후 없는8시부터9시 | NEEDS_REVIEW | CLEAR / 0.06 | 203.6ms |
| 명시적 제외·일요일 불가 | CLEAR | 미호출 | 미측정 |

네 응답 모두 HTTP200이며 현재 production `AmbiguityClassificationContract`의 offline read/usage/parse를 통과했다. 입력 usage 합계2061·출력0, usageComplete=true다. raw 응답 검사의 threshold0은 운영 임계값이 아니다. 추가 평가4case의 원문·사전 정답은 결과를 보기 전에 독립 작성했고 첫 smoke의 CLEAR 기대도 호출 전에 고정했다. 불일치에서 중단하는 계획대로 마지막 사례를 실행하지 않았다. 사후 정답/프롬프트 수정이나 재호출은 없다.

오전/오후 사례의 raw label 불일치를 의미 평가 통과로 바꾸지 않는다. 다만 분포가 CLEAR0.37/NEEDS_REVIEW0.31/INDETERMINATE0.32·confidence0.06이어서 낮은 확신을 생성 모델로 재확인하는 합의 흐름이 필요하다는 근거다. 같은 기록 응답을 threshold0.1이라는 **가정 예시**로 offline 재검사하면 기존 parser가 INDETERMINATE/LOW_CONFIDENCE를 반환한다. 실제 생성 단계나 전체2단계 성공을 검증한 것은 아니며 운영 임계값은 별도 개발/미튜닝 평가가 필요하다.

[공식 응답 계약](https://developers.openai.com/api/reference/resources/decisions/methods/create)은 choice·선택지별 probabilities·confidence를 별도 필드로 제공한다. confidence0.06을 CLEAR 확률6%나 보정된 정답률로 해석하지 않는다(CLEAR probability는0.37). [공식 해석 안내](https://developers.openai.com/api/docs/guides/decisions#interpret-the-answers)는 응용의 labeled examples와 오탐/미탐 비용으로 routing threshold를 정하도록 한다. OpenAI 전용 confidence 수식은 이 확인에서 확보하지 못했으며 다른 공급자의 수식을 전용하지 않는다. 실제 기록 응답의 classifier→TwoStage 회귀2가 INITIAL_PASS했다. 기존 e83 사전 테스트의 threshold0.8을 그대로 사용해 LOW_CONFIDENCE→생성기1회→NEEDS_CHOICE/CLEAR 결과 보존을 확인했다. 생성기는 합성이며 실제 생성 모델 성공이나 운영 임계값 선택을 입증하지 않는다. [회귀 증거](../.tdd/verification/issue123-recorded-low-confidence-pipeline.md)에 사전 threshold·기록 응답 지문을 보존했다.

비용은 전체 고정 JSON 2397~2479B에 대해 일반 BPE byte 상한과 framing 여유65,536token/요율2.2배를 적용했다. 최대5회 token 견적USD0.07208960, 남는USD0.92791040은 세금·결제 등 여유로 유지했다. 실제 보고 usage의 기본 공개 단가 환산은USD0.0002061, 같은2.2배 보수 환산은USD0.00045342다. 최종 invoice/계정별 세금·수수료가 확인됐다는 뜻은 아니며0으로 가정하거나 여유를 반환하지 않았다. [독립 비용 검토](verification/issue123-minimal-smoke-byte-quote-review.md)는 견적의 근거와 서버 강제 cap이 아니라는 한계를 구분한다.

[사전 사례](evaluation/issue123-live-korean-diagnostic-cases.json) SHA는 `47390e74ed16237ce3dd9e9fb0002d304827c22e4592a8965f3151a8fcfd2552`, [구조화 근거](../.tdd/verification/issue123-live-decisions-diagnostic.json)에 request/response/source 지문·호출수·usage·중단 이유를 남겼다. 원시 응답·로컬 원본은 Git 제외 evidence에 보존한다. 기존 개발/holdout corpus는 다시 읽거나 튜닝하지 않았다. 4개 표본으로 정확도나 p50/p95, 실제 방 예산·최종 AREA 분석·운영 준비 완료를 주장하지 않는다.

가격과 byte 기반의 출처는 [공식 Decisions 안내](https://developers.openai.com/api/docs/guides/decisions), [공식 tiktoken BPE 설명](https://github.com/openai/tiktoken), [공식 token counting의 framing 한계](https://developers.openai.com/api/docs/guides/token-counting)다. 모델 전체 context를 이 작은 요청의 필수 과금량으로 잡을 공식 사유는 확인되지 않았다.
