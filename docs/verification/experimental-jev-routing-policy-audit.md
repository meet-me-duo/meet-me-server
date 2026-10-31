# Jev 응답·신뢰도·Luna 분기 정책 대조

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

확인일: 2026-10-29 UTC. 운영 기준 소스 `40aea5139525b9ed2cba34428f0072cc42e15820`, 격리 V2 실제 평가 소스 `c9453a35582ca6503c956ab4e8aff8d73b175ef3`를 대조했다. 이 문서는 정책을 새로 확정하거나 라우팅·prompt·threshold를 변경하지 않는다. 후속 기술 실패 수정에서도 Jev 분기는 그대로 유지한다.

> 이 문서의 아래 대조는2026-10-29 당시 실행을 보존한다. 2026-10-30 사용자 승인 ADR-065는 V2에 한해 저신뢰 라벨 덮어쓰기와 최초 유보의 Luna 생략을 대체했다. 현재 정책은 [V2 실험 계약](../EXPERIMENTAL_INPUT_EVIDENCE.md)을 따른다. 과거 관측과 정답은 수정하지 않는다.

## 핵심 확인

Jev의 HTTP 성공, 정규화 전 의미 라벨, 신뢰도 적용 후 라벨은 서로 다른 관측이다. `semanticAssessment`는 응답 parser가 보존한 정규화 전 라벨이고, `assessment`는 adapter가 신뢰도 정책까지 적용한 결과다. Luna 라우터는 `semanticAssessment`가 아니라 `assessment`를 읽는다. 실제 평가에는 파싱된 라벨·확률·신뢰도가 있으며, 원시 provider 응답 바이트는 없다.

`NEEDS_REVIEW` 저신뢰를 `INDETERMINATE/LOW_CONFIDENCE`로 바꾸는 처리는 기준 main부터 있었다. 이번 V2 변경은 그 정규화된 보류를 Luna에 보내는 대신 미확정으로 접수하는 것이다. 따라서 “Jev 응답 성공이면 Luna”는 일반적인 분기 설명으로 부정확하다. 기존 V1도 충분한 CLEAR는 생성 없이 끝나며, 기술 실패는 정상 보류와 다른 예외 경로다.

## 계약과 설정

- `JevAmbiguityClassificationContract.parse`: 선택 라벨이 확률 최댓값과 일치하고 확률 합계·범위·usage 구조가 유효한지 검증한다. 정규화 전 라벨과 확률은 `semanticAssessment`/`probabilities`로 남긴다.
- confidence는 선택 라벨 확률 그 자체가 아니다. wire 일관성 검증식은 `(3 × max_probability - 1) / 2`, 반올림 허용 오차는 0.02다. 이는 의미 정확도 보정이나 사용자 질문 정책의 검증이 아니다. 실제 `max_probability=0.81`, `confidence=0.72`인 응답은 유효하지만 confidence 기준 0.8에는 미달한다.
- `meetme.precheck.classifier.minimum-confidence` 기본값은 null이다. managed 경로에서 누락·저신뢰·최대 확률 동점은 LOW_CONFIDENCE로 정규화된다.
- `requireMinimumConfidence`는 별도 공개 설정이 아니라 `dispatchGate.enforceBudget`로 결속된다. true인데 기준값이 없으면 HTTP 전 CONFIGURATION으로 실패한다. false인 누락 설정은 실제 분류 후 보류될 수 있다.
- classifier/provider/decision-routing opt-in은 별개이며 활성화만으로 신뢰도·질문 정책이 검증되지는 않는다. 이 조사에서 운영 설정을 읽거나 변경하지 않았다. 실제 평가의 0.8은 사전 고정한 실험값이다.

근거: `precheck/adapter/output/classification/{JevAmbiguityClassificationContract,JevAmbiguityClassifier,AmbiguityClassifierConfiguration,AmbiguityClassifierRuntimeProperties}.kt`, `precheck/application/port/output/AmbiguityClassificationPort.kt`.

코드 위치: [wire 확률·confidence 검증](../../src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/JevAmbiguityClassificationContract.kt#L125), [기존 신뢰도 정규화](../../src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/JevAmbiguityClassifier.kt#L147), [runtime 설정 결속](../../src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierConfiguration.kt#L38), [V2 보류 분기](../../src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt#L214). 이 Jev 코드들은 c945 후속 기술 실패 수정에서 바꾸지 않는다.

## V1과 V2의 실제 분기

다음 표는 첫 원문 검사이며, V2는 `InputEvidence`가 있는 요청이다. 질문 한도를 이미 쓴 새 원문의 접수 여부는 별도 상태 정책이다.

| 정규화 전 응답/상황 | adapter 결과 | V1 Luna | V2 Luna | V2 접수 |
|---|---|---|---|---|
| CLEAR, 충분한 신뢰도 | CLEAR | 생략 | 생략 | CONFIRMED |
| NEEDS_REVIEW, 충분한 신뢰도 | NEEDS_REVIEW | 호출 | 호출 가능 | Luna 결과에 따름 |
| CLEAR, 저신뢰 | INDETERMINATE/LOW_CONFIDENCE | 호출 | 생략 | UNRESOLVED |
| NEEDS_REVIEW, 저신뢰 | INDETERMINATE/LOW_CONFIDENCE | 호출 | 생략 | UNRESOLVED |
| 기준값 누락, require=false | INDETERMINATE/LOW_CONFIDENCE | 호출 | 생략 | UNRESOLVED |
| 모델 보류, 정책 검증 통과 | INDETERMINATE/MODEL_UNCERTAIN | 호출 | 생략 | UNRESOLVED |
| 기준값 누락, require=true | CONFIGURATION 예외 | 생략 | 생략 | 새 접수 없음 |
| HTTP 실패·timeout·refusal·잘못된 응답 | 기술 실패 예외 | 생략 | 생략 | 새 접수 없음 |
| V2의 답변 후 NEEDS_REVIEW | NEEDS_REVIEW | 해당 없음 | 추가 생성 생략 | UNRESOLVED/AFTER_ANSWER_REVIEW |

`TwoStagePreSubmissionCheckProvider`는 CLEAR를 먼저 종료한 뒤, V2에서 “정규화된 NEEDS_REVIEW이고 supplement 없음”일 때만 생성 경로로 진행한다. refusal은 validation에서 실패하므로 정상 INDETERMINATE 접수로 처리하지 않는다. 기술 실패 시 이전 접수본을 마감에 쓰는 가정과 새 접수 성공은 구분한다.

## 실제 개발 일회 평가의 관측

아래 순번은 export 순서이며 input token 수는 해당 실행에서 모두 고유했다. 참가자 직접 ID가 classifier export에 없어 독립 평가자가 check/실행의 token 수와 순차 실행을 교차 확인했다. 이 상관관계의 한계를 숨기지 않는다.

| 순번 / input token | 정규화 전 라벨 | 선택 확률 | confidence | 정규화 후 | Luna |
|---|---|---|---|---|---|
| 1 / 995 | NEEDS_REVIEW | 0.64 | 0.46 | INDETERMINATE | 생략 |
| 2 / 998 | CLEAR | 0.95 | 0.92 | CLEAR | 생략 |
| 3 / 1004 | CLEAR | 0.73 | 0.59 | INDETERMINATE | 생략 |
| 4 / 1013 | NEEDS_REVIEW | 0.53 | 0.30 | INDETERMINATE | 생략 |
| 5 / 1006 | CLEAR | 0.78 | 0.67 | INDETERMINATE | 생략 |
| 6 / 1030 | CLEAR | 0.81 | 0.72 | INDETERMINATE | 생략 |

6건은 실제 HTTP200과 유효 응답이었다. 1건 CONFIRMED, 5건 UNRESOLVED로 원문을 보존해 모두 접수·동결했다. Luna 질문/답변은 0건이다. 독립 정답과 정규화 전 의미 분류는 5/6 일치했고, 필요한 질문 1건도 저신뢰 보류로 생략됐다. 최종 Gemini 기술 실패로 구조화·후보가 없어 이 입력이 전체 문맥에서 정당하게 해석되거나 안전 제외되는지는 관측하지 못했다. 이 결과는 Luna의 의미 실패 또는 전체 흐름의 안전 성공을 증명하지 않는다. 0.8을 이 사례에 맞춰 바꾸지 않았다.

원본: `local-only-evidence/{results,summary}.json`. 독립 평가: `local-only-evidence/development.evaluation.c9453a3.json`. 원본 파일과 실행 manifest는 변경하지 않았다. 봉인 원문·답변·정답은 열지 않았다.

## 합의의 범위와 구현 가정

| 항목 | 확인된 근거 | 이번 보고에서의 지위 |
|---|---|---|
| Jev는 최종 실패를 예방하는 보조장치 | 사용자: “Jev에서 모든걸 거를 필요는 없고 보조장치 개념…” | 직접 설명한 제품 의도 |
| 신뢰도 부족·기준값 누락 때 개인 생성을 생략하는 방향 | 사용자: “NEEDS_REVIEW가 아니면 … 기준값이 없거나 신뢰도가 부족하면 생략을 하는건 어떻게 생각해?” | 사용자가 제안했고 이후 계획의 실험 구현을 포괄 승인한 방향 |
| 보완이 필요한 경우 최대 1회, 답변 후 추가 질문 없음 | 사용자: “보완 질문 1회!”와 해당 후속 범위 | 직접 확정 |
| 답변 후에도 미확정이면 접수와 해석을 분리해 최종 전달 | 사용자 “그러자”는 해당 명시 제안에 대한 승인 | 직접 확정 |
| raw NEEDS_REVIEW와 저신뢰가 동시에 있을 때 신뢰도 보류를 먼저 적용 | 위 사용자 문장은 정규화 전/후 필드나 충돌 우선순위를 지정하지 않음 | 이번 실험 구현의 해석. 세부 우선순위 직접 확정 근거는 없음 |
| threshold=0.8 | 사전등록 실험 설정. 운영 보정 평가·사용자 값 선택 근거 없음 | 검증되지 않은 실험값, 운영 결정 TBD |
| 기술 실패·질문 미응답·검사 중 마감의 상세 처리 | 이전 계약을 보존하는 보수적 실험 가정 | 확정 승인과 분리할 TBD |

사용자 근거는 부모가 전달한 신뢰된 대화 이력이다. `semanticAssessment`라는 구현 필드 기준을 명시한 부모의 읽기 조사 지시를 사용자 자신의 구체적 우선순위 확정 발언으로 바꾸어 인용하지 않는다. 이후 “바꾼 후 테스트하고 보고” 승인은 실험 구현 권한이며, 이 작은 결과로 운영 threshold의 보정 완료까지 뜻하지 않는다.

기존 ADR-054는 정상 낮은 확신을 생성 LLM이 재확인하도록 했고 ADR-061은 confidence가 없으면 정상 CLEAR도 Luna 검사한다고 명시한다. ADR-059는 개인 생성 공급자만 Luna로 바꾸며 이 정책을 유지한다. V2/ADR-064는 미확정 접수와 한 번의 질문을 도입했으나 구체 threshold나 raw/정규화 우선순위를 직접 결정한 문장은 없다. 그러므로 V2의 정상 보류 생략은 **기존 정책과 다른 실험 매핑**이며, 기존 정책을 그대로 유지했다고 설명하면 안 된다.

## 이번 단계에서 바꾸지 않는 것

필요 질문 한 건이 생략된 결과를 보고 prompt·threshold·gold 또는 raw 라벨 우선순위를 바꾸지 않는다. 후속 기술 실패 수정은 하네스 중단과 예외 보존에 한정한다. 운영 반영 전에는 “명시 NEEDS_REVIEW를 우선할지, 저신뢰 보류를 우선할지”와 운영 기준값을 구체적으로 정해야 한다. 원래 40개 이상 블라인드 전체를 확보·통과한 정책이라고 주장하지 않는다.
