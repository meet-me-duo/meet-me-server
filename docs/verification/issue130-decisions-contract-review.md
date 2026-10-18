# Decisions 공식 계약과 현재 해석 독립 검토

2026-10-18 UTC. 결론: 현재 자료에서는 confidence/CLEAR 확률 혼동, 라벨 반전, 잘못된 응답 경로 또는 low-confidence 분기 구현 오류를 확인하지 못했다. 이는 모델 품질 PASS가 아니다. 원래 raw gate FAIL과 6/8 miss는 그대로 유효하며, 개별 gold·응답을 읽지 않은 이번 분석은 원인을 모델·프롬프트·gold 중 하나로 확정하지 않는다.

[공식 가이드](https://developers.openai.com/api/docs/guides/decisions#interpret-the-answers)는 choice/score에 “a probability distribution and a separate `confidence` field”가 있다고 설명하고 앱의 labeled examples로 threshold를 정하도록 한다. 현재 두 공식 페이지에는 confidence가 **CLEAR 확률** 또는 **보정된 정답 확률**이라는 수학적 정의가 없다. 이름이 confidence라는 이유만으로 `.8`을 정답률80%로 해석할 수 없다. 공식 예제도 선택 확률과 confidence가 서로 다르다.

[Create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)의 choice 옵션·질문별 instructions·문자열 input과 응답 answers/choice/confidence/probabilities/refusal 구조는 현재 사용하는 wire 모양과 일치한다. predicate의 probability와 choice의 confidence는 다른 필드다.

## 소스 대조

- `AmbiguityClassificationContract.kt:27–67`: 원문과 reference_at/time_zone/locale/search 날짜를 JSON 문자열 input으로 직렬화한다. 하나의 `choice` 질문 `material_ambiguity`, CLEAR/NEEDS_REVIEW/INDETERMINATE의 서로 다른 세 값·설명과 질문 내부 instructions를 보낸다. 실제16개 본문은 열지 않았다. 이 직렬화는 JSON 문자열을 담는 정상 wire이며, 잘못된 타입이나 user/developer 메시지 위치에 prompt를 넣었다는 근거는 없다.
- 같은 파일 `:116–158`: `answers[0].choice`를 enum으로 읽고 각 `probabilities[].value/probability`와 **별도** confidence를 검증한다. confidence를 P(CLEAR)로 바꾸거나 `1-p`로 역산하지 않는다. explicit INDETERMINATE는 MODEL_UNCERTAIN, 그 밖의 값은 confidence<minimumConfidence일 때 LOW_CONFIDENCE가 된다. 정확히 `.8`은 통과하는 `<` 경계다.
- `:222–228`: 날짜·시간·장소·지시대상·부정·예외·범위·자기모순을 함께 보고, 명확한 문맥·의도된 넓은 범위·유효한 타인 의존 조건은 보호한다. 원문 재작성·regex 의미 판정·불용어 제거를 지시하지 않는다. 이 prompt가 모델에 적합한지는 별도 실증 문제다.
- `OpenAiAmbiguityClassifier.kt:142–151,184–212`: wire parser와 명시 설정 threshold를 사용하며, 오류에서 CLEAR를 만들지 않는다. 옵션 기본값은 enabled=false/threshold=null이다.
- `TwoStagePreSubmissionCheckProvider.kt:163–205,234–248`: 정상 CLEAR만 종료하고 나머지 유효 평가를 B에 전달한다. REFUSED·잘못된 응답은 오류 경로다. 같은15초 잔여 시간을 넘긴 결과를 성공으로 바꾸지 않는다.

## 집계와의 관계

공개 집계 f9fb1b3c는 raw CLEAR13/REVIEW3, LOW_CONFIDENCE13/B13, CLEAR_STOP3과 일치한다. 명확군 raw정답7 중4가 낮은 confidence로 유보됐고 원래 raw과잉검토1을 더하면 불필요B경로5/8이다. 모호군 raw miss6은 B로 완화됐지만 원분류 miss로 남는다. 이는 **기존 `.8` 정책의 경로 효과**로 설명할 수 있다. 최선의 threshold나 confidence calibration의 증거가 아니며 threshold를 낮춰 결과를 개선했다고 재채점할 수 없다.

raw9/16·miss6/8은 현재 gold에 대한 출력 불일치다. 개인별 의미 판정과 gold 적합성은 custodian만 검토한다. 복합 기준을 단일 저비용 choice에 묻는 task/prompt 적합성, 한국어·문맥 처리, 선택지 설명의 추상성이 실패에 기여했을 가능성은 **추론**이며 집계만으로 인과 입증되지 않는다. B0/질문 분모0이므로 최종 질문 품질 원인도 분석할 수 없다.

## 별도 주의와 최소 다음 단계

parser의 합계 tolerance1e-6·선택값=argmax·정확한 키 집합은 앱의 엄격한 안전 정책이다. 현재 공식 문서에서 그 세부 조건의 보장까지 확인하지 못했다. 향후 공식 호환성이 거부되는 합성 사례가 나오면 별도 검토해야 하지만, 이번16건은 전부 유효 parse였으므로 이를 현재 품질 실패 원인으로 삼을 수 없다.

기존 DCW03/DCW04/DCW09, DCU01, TwoStage routing 검증을 먼저 재사용한다. 필요할 때만 공개 합성 응답으로 (a) 같은 선택/분포·다른 confidence, (b) 같은 선택/confidence·다른 유효 분포, (c) `.8` 바로 아래/동일 경계, (d) high-confidence INDETERMINATE/refusal/error의 분리 등을 추가 TDD 명세로 삼는다. 이는 새 실모델 호출이나 정책 변경 없이 해석 경계를 확인하는 제안이다. 현재 source·gold·prompt·threshold·점수는 변경하지 않는다.

읽은 것은 공식 primary 두 페이지, 지정 소스와 공개 집계뿐이다. 개별 입력·정답·wire·prepared index·raw response·실제 CA/env/key를 읽거나 모델을 호출하지 않았다. 예약1.79322880/여유.20677120/환급0과 추가 paid STOP을 유지한다. 이 로컬 분석은 공개 게시·운영 활성화 승인도 아니다.
