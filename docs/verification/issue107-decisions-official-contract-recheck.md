# #107 Decisions 공식 계약 재대조

2026-10-18 UTC. 사용자가 공유한 Java/curl predicate 예제와 현재 HTTP adapter·독립 mock fixture를 다시 비교했다. 이 확인으로 SDK/제품/테스트를 변경하지 않았다. 실모델 호출 결과가 아니다.

- [요청 레퍼런스](https://developers.openai.com/api/reference/resources/decisions/methods/create)는 텍스트 문자열과 메시지 배열을 모두 지원한다. 현재 JSON 문자열 input은 원문·기준 시각·시간대·locale·검색 범위 여섯 필드만 담는다. 이미지·다른 참여자 원문·식별자·평가 정답을 보내지 않는다. 메시지의 input_text 형식으로 바꾸어야 하는 계약 위반은 없다.
- POST /v1/decisions와 gpt-6-luna는 공식 endpoint/model이다. [공식 가이드](https://developers.openai.com/api/docs/guides/decisions)는 predicate뿐 아니라 choice도 설명한다. 현재 질문 하나에 명확/검토/판단불가의 세 문자열 선택지를 고정하는 방식은 지원 범위다. predicate의 참 확률을 읽거나 1-p로 뒤집는 구현은 없다.
- [응답 레퍼런스](https://developers.openai.com/api/reference/resources/decisions)는 질문 순서의 answers와 choice별 확률, 별도 confidence를 정의한다. 현재 parser는 한 개 답·요청한 이름/type/model·선택지 전부의 분포와 합계·최대 선택 일치를 검증한다. confidence를 선택지 확률과 동일하다고 가정하지 않는다. 합성 임계값0.8에서0.79 유보/0.8 통과를 검증했으며 운영 임계값은 미설정이다.
- 이름이 일치하는 refusal은 별도 거부 사유로, 정상 낮은 확신/모델 유보와 구분한다. API 오류·malformed·거부는 bridge에서 CLEAR나 자동 생성 fallback이 되지 않는다. 문맥을 포함한 실제 정확도와 confidence 보정은 별도 실평가 대상이다.

대조 근거: `AmbiguityClassificationContract.kt`, `OpenAiAmbiguityClassifier.kt`, `DecisionClassificationFixtures.kt`, transport의 DCW01–06/DCW09, safety·uncertainty 테스트 및 `TwoStagePrecheckRoutingTest.kt`. 기존 집중160+pipeline79 결과와 새 전체 guard를 연결하며 같은 검사를 새로 반복했다고 주장하지 않는다.
