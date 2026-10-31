# 최소 블라인드 통합 검증 결과 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**pilot은 실제 요청4회 뒤 최종 Luna TIMEOUT으로 중단됐다. 봉인 모임은 열지 않았고 추가 요청도 하지 않았다. 전체 흐름 성공은 미검증이다.**

사용자 흐름에서는 세 입력 모두 질문 없이 최종 분석으로 진행했으므로 통과했다. 내부 상태는 CLEAR가 아니라 `RECEIVED / interpretation=UNRESOLVED`였다. 개인 Luna 호출0이므로 이번에 `NEEDS_EDIT`나 `NEEDS_CHOICE`가 반환된 적은 없다. 이미 만들어진 질문을 버린 것이 아니라 질문 생성 단계에 진입하지 않았다.

## 실제 Jev 분기와 NEEDS_EDIT 제거의 관계

| 참가자 | Jev HTTP | 정규화 전 의미 라벨 | confidence / 기준 | 정규화 후 | 개인 Luna | 개인 검사 상태 |
|---|---:|---|---|---|---:|---|
| p1 | 200 | NEEDS_REVIEW | 0.30 / 0.80 | INDETERMINATE / LOW_CONFIDENCE | 0 | RECEIVED / UNRESOLVED |
| p2 | 200 | NEEDS_REVIEW | 0.25 / 0.80 | INDETERMINATE / LOW_CONFIDENCE | 0 | RECEIVED / UNRESOLVED |
| p3 | 200 | CLEAR | 0.58 / 0.80 | INDETERMINATE / LOW_CONFIDENCE | 0 | RECEIVED / UNRESOLVED |

정규화 전 라벨은 strict parser가 보존한 `semanticAssessment`다. provider 응답 원문 바이트는 저장하지 않았다. 이 라벨을 실제 질문 본문이나 `NEEDS_CHOICE`와 동일하게 취급하지 않는다.

- `JevAmbiguityClassifier.kt:147–156`: 유효한 응답도 confidence가 minimumConfidence보다 낮으면 assessment를 INDETERMINATE/LOW_CONFIDENCE로 바꾼다. semanticAssessment는 보존한다.
- `TwoStagePreSubmissionCheckProvider.kt:197–208,214–235`: V2에서 정규화된 CLEAR는 확정 종료하고, 정규화된 NEEDS_REVIEW이며 답변 전인 경우에만 개인 Luna로 보낸다. 이번 INDETERMINATE는 UNRESOLVED/CLASSIFIER_LOW_CONFIDENCE로 반환해 생성하지 않는다.
- `PrecheckProcessingService.kt:170–232`: 원문을 바꾸지 않고 accepted version으로 저장한다. 해당 V2 보류 입력은 RECEIVED, evidence.interpretation은 UNRESOLVED다. 세 입력 모두 submission_complete=true, failure_code=null이었다.
- `PrecheckProviderContract.kt:313–324,357–361`: V2 개인 생성 strict schema는 CLEAR/NEEDS_CHOICE만 허용하고 일반 NEEDS_EDIT을 금지한다. 안전한 A/B를 만들 수 없어도 구체적 질문과 직접 답변을 허용한다.

NEEDS_EDIT enum·구 V1 schema·공용 UI의 옛 문구는 호환을 위해 코드에 남아 있다. 따라서 모든 코드에서 그 이름을 삭제한 것은 아니다. 정상 V2 의미 판정에서 직접 전체 재작성을 강요하는 생성 결과는 제거했다. 이번 세 입력에는 직접 수정 요구도 질문도 없었고, 미확인 상태로 접수해 최종 분석을 시도했다.

현재 구현에는 ‘명확하다고 판정해 통과’와 ‘보완 질문’ 외에 **신뢰도 부족을 미확정으로 보존하고 질문 없이 접수하는 세 번째 경로**가 있다. 사용자 경험상으로는 통과지만, 내부 의미 판정은 CLEAR가 아니다. HTTP 장애를 성공 처리한 것도 아니다. Jev 세 요청은 모두 HTTP200과 유효 응답이었고, 뒤의 최종 Luna 기술 실패와 구분된다.

질문 예산을 이미 사용한 후 새 원문을 다시 제출하는 경우에는 다른 UNRESOLVED 상태로 새 버전 접수를 보류하고 이전 접수본을 유지하는 가정도 남아 있다. 이는 이번 RECEIVED와 다르며 운영 확정 정책으로 확대하지 않는다.

## 기준값 0.8과 합의의 범위

- 설정명은 `meetme.precheck.classifier.minimum-confidence`, 본 코드 기본값은 null이다 (`AmbiguityClassifierRuntimeProperties.kt:9`).
- 이번에는 기존 실험값0.8을 `BlindFlowSettings.kt:156`과 실행manifest로 고정했다. `AmbiguityClassifierConfiguration.kt:38–41`이 Jev options로 전달한다. confidence는 선택 라벨 확률 자체가 아니며 wire 일관성 검증식은 `(3*max_probability-1)/2`다.
- ADR-054 (`docs/ADR.md:590`)의 옛 정책은 정상 저신뢰도 생성 LLM이 재확인하는 것이다. ADR-061 (:659)은 confidence 설정이 없으면 정상 CLEAR도 Luna 검사로 보낸다.
- ADR-064 (:694)은 별도 V2의 질문1회·원문 보존·미확정 접수·최종 해석을 승인하지만 **0.8이라는 값이나 raw NEEDS_REVIEW와 저신뢰가 동시에 있을 때의 우선순위를 직접 확정하지 않는다.**
- `docs/EXPERIMENTAL_INPUT_EVIDENCE.md:21,41`도0.8은 검증된 운영값이 아닌 실험값이며 구체적 우선순위와 운영값은 TBD라고 명시한다. 이번 실행 승인을 운영 정책 보정 완료로 해석하지 않는다.

기존 상세 대조는 `docs/verification/experimental-jev-routing-policy-audit.md`에 있다. 이번 한 사례에 맞춰 prompt·threshold·분기·정답을 바꾸지 않았다. ‘NEEDS_REVIEW면 질문’이라는 사용자 설명과 현재 ‘confidence 정규화 후 NEEDS_REVIEW만 질문’은 같은 조건이 아니다.

## 실제 실행과 호출 예산

- 승인: `synthetic-authorization-f19d2f59a82e7fd6`. pilot1+sealed1, 각3명. 물리 상한 Jev12/개인Luna6/최종Luna2/합계20, 실제Gemini0.
- plan: `c87e564a-1753-4d81-8650-8638d84b1435`.
- 실행HEAD: `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a`, 기존 격리 실험 branch. main 소스289개 불변, source/compiled 등2667파일은 실행 전후 해시 일치.
- 실행manifest SHA256: `0f64f152bcb6b5638f3e6b4aab09a6ea1793a317a4b02d30a2e0b73c36588522`.
- source SHA256: `d175052b91218c86b1569c0537d9bc29479342c7f8e05635c0a36a4617539fd5`.
- 기존 Jev/개인Luna 공유15초, 최종Luna 최대27초/전체60초, threshold0.8, 생산 prompt/schema를 유지했다.

| 실제 물리 API | 회수 | 결과 | 관측 토큰 |
|---|---:|---|---|
| Jev jev-1.14.0 | 3 | HTTP200×3 | input3313 / output153 |
| Luna 개인 질문 gpt-6-luna | 0 | 미실행 | 없음 |
| Luna 최종 gpt-6-luna | 1 | TIMEOUT, HTTP 상태 미관측 | 미관측 |
| Gemini | 0 | 503 상당 SERVER는 test double | 없음 |
| 합계 | **4** | header 전송4, 종료 관측4 | 실제 청구액 미확인 |

Jev 사용량은 CLASSIFICATION 개별3건을 한 번만 합산했다. PRECHECK wrapper의 중복 이벤트와 DB null을 합산하지 않았다. Luna 토큰·비용을 추정하지 않는다. 개별 Luna 실측 elapsed는 이번에 보존되지 않았고27초는 설정 상한이다. JUnit method 전체33.241초를 API 응답 시간으로 대체하지 않는다.

첫 기동은 모델 생성 전 사전 검사에서 물리0으로 멈췄다. Kotlin `Set<Path> + Path`가 파일 대신 경로 구성요소를 더하는 하네스 오류를 당시 class bytecode와 무료 회귀로 확인했다. `+ setOf(path)`로 수정하고 원본manifest/log/intent/JUnit을 보존했다. claim/output 부재·exit1·물리0을 확인한 뒤 한 번만 사전 검사 복구를 실행했다. 실제 모델 실행은 복구 후 한 번이다.

첫 실제 기술 오류로 원장은 PROVIDER_FAILURE가 되어 모든 역할의 새 전송을 차단했다. 남은16칸은 사용하지 않는다. JUnit1실패·Gradle exit1을 성공으로 바꾸지 않는다.

## 확인된 범위와 미검증

독립 작성자는 코드·prompt·이전 결과 없이 새2모임을 사전 고정했다. 구현자는 sealed 원문·답변·gold를 읽지 않았고 기존holdout도 열지 않았다.

- pilot: 접수·원문 불변·미해결 사유·동결version 대응을 각각 **3/3** 독립 확인했다. 질문0/답변0. 최종 요청의 원본이 되는 동결 자료에3건이 들어갔다. 원격 모델의 수신·의미 소비·실제 반영 완료는 증명하지 못했다.
- 최종 Luna: 구조화 결과0. 추천·미해석 입력의 주최자 표시·선택 확정 저장은 이번에 미검증이다. 후보 없는 정상 NO_MATCH로 판정한 것은 아니다.
- sealed: **HOLD, 하네스 읽기0·모델0**. 기술 중단과 질문/답변 필수 경로 미실행으로 개방 조건을 충족하지 않았다.
- Java HTTP 하네스이며 실제 브라우저 전체 흐름 검증은 아니다. 이전 mock/API 성공을 합쳐 이번 실모델 전체 성공이라고 주장하지 않는다.

신규 무료 회귀16PASS/실패·오류·skip0, ktlintCheck PASS. 전체 commit guard 실행은 아니다. production코드·운영설정·web branch 변경, commit/push/merge/deploy는 없었다.

## TIMEOUT과 DB CONFIGURATION의 차이

wire 원장은 마지막 Luna를 TIMEOUT으로 저장했지만 DB coordination_attempts는 CONFIGURATION, transport snapshot은 빈 배열이었다.

`OpenAiLunaNaturalLanguageParserAdapter.kt:216–232`에는 IOException 분류 뒤 finally의 physical.observe가 dispatch 예외를 던지면 기존 오류를 CONFIGURATION으로 덮는 경로가 있다. `AiHttpDispatchGate.kt:314–333`의 이번 !enforceBudget 경로는 runtimeGuard를 다시 호출하지 않고 남은 시간/deadline을 검사한다. 따라서 ‘하네스 stop이 source 등록을 거부해서 생겼다’고 단정하지 않는다. 실제 어느 deadline 검사에 걸렸는지는 미확인이다. Gemini 쪽은 기존 분류를 보존하는 처리가 있다 (:283).

transport=[]에는 비동기 기록 큐, 중단 직후 한 번의 DBsnapshot, teardown 사이 관측 한계가 있다. 기록 지연·쓰기 실패·종료 경합을 구별할 증거가 없어 원인을 확정하지 않는다. 빈 배열을 모델 미호출 근거로 쓰지 않는다. wire 원장으로 실제 전송1/종료TIMEOUT은 확인됐다.

다음 무료 최소 검증은 Luna의 deadline 종료 관측에 의한 예외 덮어쓰기를 mock으로 재현하고 비동기 기록과 snapshot의 순서를 latch/PG로 비교하는 것이다. 이번에 이 production 수정이나 유료 재실행은 하지 않았다. 정책적으로는 raw NEEDS_REVIEW와 저신뢰도의 우선순위를 정하는 문제를 한 pilot에 맞춰 기준값을 낮추는 것과 구분해야 한다.

## 증거

- 준비·manifest·무료XML: `local-only-evidence/blind20-20261030`
- 원본 결과: `local-only-evidence/{results,wire-ledger,database}.json`
- 개인별 분기: 같은 경로 `routing-evidence.json`. raw/gold는 포함하지 않는다.
- 독립 채점: 같은 경로 `pilot/independent-grade.json`. pilotPass=false/sealedHOLD.
- 결과 SHA256: `c9154d07e9d89ba3c6d3556faffab3f7fdc3107e87c8bbdb4d0788e7963a06e0`.
