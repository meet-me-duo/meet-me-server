# 현재 Decisions 코드 계약 인계 (2026-10-18 UTC)

이 문서는 코드 관측이다. 부모의 최신 공식 문서 조사와 대조하기 전 결론을 확정하지 않는다. 유료 호출·프롬프트/threshold 튜닝은0이다. 기준은 실제 실행 HEAD `9d9c934fd8f999f0bd4a1723bd8987b9efd86b93`이며 이후 CI fixture 변경은 production contract를 바꾸지 않았다.

| 확인 항목 | 정확 코드 위치 | 실제 동작 |
| --- | --- | --- |
| 요청 모델·input | `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt:27` | `gpt-6-luna`, input은 6필드 JSON을 다시 직렬화한 **문자열**. raw_text/reference_at/time_zone/locale/search_start_date/search_end_date만 있고 purpose/mode는 없다. |
| 질문 type | 같은 파일 `:41` | 질문1개, type=`choice`, name=`material_ambiguity`. **3분류** CLEAR/NEEDS_REVIEW/INDETERMINATE. predicate·score·사용자에게 보일 질문 생성이 아니다. |
| 현재 지침 | 같은 파일 `:222` | 1448 UTF-8 bytes/190 words, SHA `267af412fe53b9ac088475d76c1cbe5a90e6f34714efb25cb47ba39c900dd8ec`. 결과 후 변경0. |
| 선택값·confidence | 같은 파일 `:131` | `answers[0].choice`를 enum으로 읽고 **별도** `confidence`를 읽는다. confidence를 P(CLEAR)로 변환하거나 1-p로 뒤집지 않는다. |
| 선택지 probability | 같은 파일 `:135` | `probabilities` 3개의 value/probability를 검증한다. 각0..1/누락·중복0/합1(오차1e-6)/선택값이 argmax와 일치해야 한다. 분포를 직접 routing threshold에 쓰지 않는다. |
| 공통 수치 helper | 같은 파일 `:189` | helper명이 probability지만 confidence와 선택지 probability 둘 모두의 숫자형·유한·0..1·자원상한만 검증한다. 두 필드가 같은 의미라는 변환은 없다. |
| 정규화 | 같은 파일 `:152` | raw INDETERMINATE→MODEL_UNCERTAIN. 나머지는 confidence<minimumConfidence→INDETERMINATE/LOW_CONFIDENCE, 그 외 raw assessment 유지. 정확히 .8은 `<` 비교의 통과 경계. |
| B 생략 | `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt:132` | 정규화된 assessment가 CLEAR인 경우만 CLEAR 반환. 나머지 정상 REVIEW/유보는 `:142`부터 생성기로 보낸다. 오류/refusal은 별도 실패 처리. |
| .8의 실제 출처 | `evaluation/issue130/Issue130RepresentativeDecisionsCli.java:182`, `:318`; `evaluation/issue130/run_representative_decisions.py:155` | 고정 진단 fixture에 .8 검증/옵션 주입을 hardcode했다. labeled-data calibration이나 공식 추천값으로 도출한 근거는 없다. 결과에 맞춰 낮추지 않았다. |
| 운영 기본값 | `src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierOptions.kt:5`, `AmbiguityClassifierRuntimeProperties.kt:5` | enabled=false/minimumConfidence=null. 실제 운영 임계값 미선택. |

[비밀값 없는 요청 예시](issue130-decisions-synthetic-request-example.json)는 새 공개 합성 입력이며 private corpus/실제 응답이 아니다. 인증 헤더·키·참여자 식별자는 없다. 요청 모양을 확인하는 예시이지 실제 무료/유료 호출 결과가 아니다.

## 공식 의미와 코드 판단의 구별

기존 [공식 계약 검토](issue130-decisions-contract-review.md)는 가이드가 선택지 분포와 별도 confidence를 구별하며, reference에 confidence의 정답률 보정 공식이 없다는 한계를 이미 기록했다. 부모의 최신 조사도 같은 구별을 확인 중이다. 현재 코드는 probability를 정답일 확률로 바꿔 쓰지는 않지만, **미보정 confidence .8을 B 생략의 휴리스틱으로 사용하는 효과와 경제성은 입증되지 않았다**. .8이 정답률80% 또는 안전성 보장이라는 근거는 없다. choice 대신 predicate가 낫다는 성능 결론도 내리지 않는다.

A24 raw CLEAR7/REVIEW17과 실제 정규화 B_REQUIRED24/CLEAR_STOP0을 분리한다. 관측 B 생략은0/24로 현재 routing의 비용 절감 근거는0이다. raw CLEAR7/24를 실제 절감량으로 세지 않는다. B2는 사전 gold CHOICE1/SAFE_EDIT1에 모두 CLEAR를 반환해 의미 correct0/2로 독립 평가됐고 clear gold분모0이다. 이 진단으로 일반 정확도·인과 개선·운영 p50/p95를 추정하지 않는다.

권고는 **제출 전 저장/확인 분리·개인 비공개·revision/ownership/close fencing 같은 안전 구조는 유지하되, 현재 AI 후보의 main 병합·운영 활성화와 추가 수정 검증을 보류**하는 것이다. 먼저 공식 조사로 type/choice/confidence 계약을 대조하고 다음 검증의 목적·독립 신규 gold·false-CLEAR/과잉질문/abstention·비용 기준을 사전 확정한다. 같은 표본에 맞춘 threshold 하향이나 추가 유료 호출은 진행하지 않는다. B의 필요한 보완 누락도 A와 별도 과제로 남는다.

## 실제 직렬화와 오류 경계 추가 확인

`OpenAiAmbiguityClassifier.kt:20–24,81,104`는 OpenAI SDK 대신 OkHttp와 Jackson을 직접 사용한다. 실제 실행 동결 runtime의 HTTP/JSON 라이브러리는 OkHttp4.12.0/Jackson3.1.5이며 OpenAI SDK jar는 없다. 따라서 SDK 버전의 input 타입과 현재 본문을 동일하게 취급하지 않는다. 실제 HTTP는 production serializer bytes를 사용하고 평가 CLI `:312–316`의 네트워크 interceptor가 frozen wire와 바이트 동일성을 확인했다. `input`은 6필드 JSON 문자열이다.

confidence는 contract `:134`에서 Decimal→Double로 변환하므로 IEEE754 표현 한계는 있으나 별도 소수점 반올림·기본값 대입은 없다. 원 HTTP 응답 바이트는 별도 저장해 재감사 가능하다. missing/잘못된 필드는 `INVALID_RESPONSE`, refusal은 `REFUSED`, 정상 explicit INDETERMINATE는 `MODEL_UNCERTAIN`, threshold 유보는 `LOW_CONFIDENCE`다. `TwoStagePreSubmissionCheckProvider.kt:234–248`은 refusal을 오류로 처리하고 나머지 두 정상 유보 이유만 허용한다. `OpenAiAmbiguityClassifier.kt:152–165`는 전송/상태/파싱 오류를 별도 예외로 보존하므로 의미상 모호성으로 기본 대체하지 않는다. A24 실제 응답과 raw probabilities의 값별 매칭·분포/coverage는 custodian의 무과금 원응답 감사로 확인한다.

B2는 부모가 평가 후 원문·출력·독립 실패 근거를 명시 요청해 해당 두 합성 사례만 root에게 공개했다. A24 나머지 원문/개별gold는 비공개이고 평가 전 blind 이력은 소급 변경하지 않는다.

## 공식 조사와의 현재 대조

부모가 최신 공식 [가이드](https://developers.openai.com/api/docs/guides/decisions), [Create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create), 공식 openai-node `src/resources/decisions.ts`와 `src/helpers/decisions-tool-runner.ts`를 확인했다. 전달된 결론은 선택지 probability와 confidence의 구분, confidence 계산/보정 공식 및 .8=80% 정답률 보장의 부재, predicate의 우월한 성능 근거 부재다. 이번 보고는 해당 공식 조사의 전달 내용을 코드와 대조한 것이며 별도 공식 성능 실험이 아니다.

**choice=argmax는 확인된 API 필수 보장이 아니라 현재 애플리케이션의 보수적 일관성 정책**이다. 현재 코드는 `compareTo(max)==0`이라 선택값이 최대값과 동률이어도 허용한다. 저장 응답의 실제 불일치·동률 여부를 먼저 감사하고 근거 없이 검증을 제거하지 않는다. INDETERMINATE는 판단 근거 부족의 선택지이고 LOW_CONFIDENCE는 별도 confidence 기반 유보라 경계가 겹칠 수 있지만 이것을 이번 품질 실패의 확정 원인으로 단정하지 않는다. 모든 .8 routing 수치는 **실제 운영이 비활성인 고정 진단 profile의 결과**다.

## 저장 응답만 사용한 확률 감사 결과

원 응답 confidence와 저장 snapshot은24/24 수치 일치했고 probabilities도24/24 일치했다. 이 표본의 관측 일치이지 Java Double의 일반 무손실 보장은 아니다. 원 JSON 숫자 lexeme은 별도 비공개 보존하고 정밀 Decimal로 비교했다. 누락·무효·refusal·기술 오류를0 또는 의미상 모호성으로 대체하지 않았다. 이 실제24건에는 해당 오류가 없었으며 합성10개에서 배열순서 변경/정밀 경계/오류 분리를 검증했다.

confidence 전체0.11–0.66, raw CLEAR7건은0.18–0.35다. value로 매칭한 p(CLEAR)는 전체0.09–0.57/raw CLEAR7건0.45–0.57이다. 반환 choice는 argmax집합과24/24 일치·불일치0·동률0이어서 실제 동률 수용 분모는0이다. p(INDETERMINATE)는24건0.09–0.22이고 INDETERMINATE 선택0과 구분한다. 확률 질량만으로 실패 원인을 확정하지 않는다.

| 사전 고정 보고용 기준 | 0 | .25 | .5 | .75 | .8 | .9 | 1 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| raw CLEAR7 중 confidence 기준 유지 | 7 | 5 | 0 | 0 | 0 | 0 | 0 |
| raw CLEAR7 중 p(CLEAR) 기준 유지 | 7 | 7 | 5 | 0 | 0 | 0 | 0 |

각 행은 전체24/명확gold14/검토gold10 분모와 원 선택을 유지한 **보고용 반사실 계산**이다. p(CLEAR)로 raw NEEDS_REVIEW를 CLEAR로 뒤집지 않았다. 관측 false pass는0이지만 raw CLEAR 오답 자체가0이라 운영 안전성/최적threshold 근거가 아니다. 통과 후보0인 열의 조건부 오류율은 분모0/N/A이며0%로 부르지 않는다. 실제 비활성 진단profile .8은 B생략0/24 그대로이고 원점수/threshold/운영정책은 불변이다.

[confidence 감사](issue130-a24-confidence-audit.json), [argmax/유보 보충](issue130-a24-argmax-uncertainty.json)를 따르며, 추가 API/network/key 호출0이다.

## 후속 정책 정합성 감사

[사후 감사](issue130-b2-policy-audit/policy-conflict.md)는 A 전체 지침의 필수 개인 사실 추측 금지와 짧은 person-dependent 설명, 공개 평가정의·B 지침의 관계 의미 보존을 대조해 실제 해석상 긴장을 확인했다. B2 개인 퇴근시각 사례는 사전gold와의 불일치로 남지만 모델 단독 실패 원인으로 확정하지 않는다. 다른 날짜-방식 결속 누락은 이 문구 충돌로 설명되지 않는다. 기존gold/원점수/두 최초 판정은 무수정이며 이 감사자는 기존rater의 사후source노출자다.

다음 검증 전에는 정의된 넓은 허용·명시적 타인 가용성 의존과 필수 개인 종료시각 누락의 경계를 A instructions/choice 설명/B instructions/gold 정의에 일관되게 명시해야 한다. 현재 스키마가 숫자로 구조화하지 못한다는 이유만으로 모호하다고 처리해서는 안 되며, 문자 predicate를 보존할 수 있다는 이유만으로 필요한 개인 사실 누락을 무조건 통과시켜서도 안 된다. 이번 감사는 명확화를 위한 기록이며 새prompt/정책수정·재채점·유료실험은0이다.
