# 남은 전체 흐름 검증 대조와 최소 실행 계획 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

> 이 문서는 이전 cap20 승인 및 당시 confidence 분기의 계획 이력이다. 해당 실행은 기술 오류로 종료됐다. 이후 승인된 라벨 중심 V2와 새 무료92개 검증·최대10회 제안은 [최신 준비 보고서](experimental-v2-free-flow-preparation.md)를 따른다. 기존 승인/잔여 요청을 재사용하지 않는다.

이번 작업은 기존 증거 정리와 무료 회귀만 수행했다. **추가 유료 요청0**, 새 블라인드 입력 생성/기존 holdout 열람0이다. 기준은 HEAD `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a`와 보존한 로컬 test/SQL 변경이며 운영 최신 main에 통합하거나 배포하지 않았다.

## 실제 관측·무료 계약·남은 영역

| 단계 | 실제 공급자 관측 | 무료 계약·회귀 근거 | 남은 실모델 검증 |
|---|---|---|---|
| Jev 질문 필요 판단 | c945 개발2모임에서 HTTP200 6회. confidence 전 semantic CLEAR4/NEEDS_REVIEW2, 정규화 CLEAR1/INDETERMINATE5. 질문Luna0 | `EvidencePrecheckContractTest`, `EvidenceUnresolvedReasonContractTest`, 기존 Jev transport/라우팅 감사 | raw NEEDS_REVIEW 저신뢰 시 질문 생략의 제품 적합성, 실제 질문 필요/불필요 품질. confidence 전 라벨은 parser 결과이며 raw provider body 보존으로 주장하지 않음 |
| Luna 보완 질문 최대1회 | 실제 질문 API 성공 관측 없음. 최근 실제Luna 성공은 **최종 구조화**였음 | `EvidencePrecheckContractTest`의 질문/직접입력/strict/grounding, `ExperimentalInputEvidencePostgresTest`의 질문 예산·취소·재입력 | 실제 Jev 판정에서 실제 Luna 질문 생성, A/B+직접입력 또는 질문+직접입력, Luna가 질문 불필요라면 통과하는 실제 응답 |
| 답변 결합·재판정 | 실제 공급자 질문→사용자 답변→Jev 재판정 연속 실행 없음 | 같은 원문+대상 구간+보완 정보 보존, CAS/멱등/본인권한/질문1회·답변후추가Luna0, 답변후유보접수·CLEAR위장금지 | 원본 조건을 잃지 않는 실제 재판정·최종모델 소비. UI 답변이 원문 전체 재작성으로 바뀌지 않는 실제 화면 흐름 |
| 마감·불변 동결 | 최근 Luna smoke에서 명확한 합성 원문3개 접수·동결·구조화 version join 일치 | `ExperimentalInputEvidencePostgresTest`의 unresolved 원문/답변/상태 동결·마감 경합 | 실제 생성 질문의 답변·미해결 근거가 포함된 동결본. 최소안은 수동 마감이며 자동 마감 전체 실모델 회귀를 포함하지 않음 |
| 전체 최종 해석 | 최근 실제 Luna1회 HTTP200, 입력2879/output355 tokens, strict 결과3개 모두 동일18–20KST. 실제Gemini 정상 구조화 성공은 아직 없음 | `EvidenceFinalContractTest`, `EvidenceFinalPromptPolicyTest`, 기존 validator·실패 격리 | 미해결 개인 사실을 다른 참여자의 가능시간으로 발명하지 않는 준수, 실제 다중 날짜/선호/장소이동 제약·부분 제외. prompt 문자열 검사는 모델 준수 증거가 아님 |
| 추천·제외 입력 | 실제 Luna smoke는 전원 정상·미반영0·시간안1·HOST 확정 성공 | `RecommendationProjectorContractTest`, `RecommendationProjectorD2ContractTest`, `RecommendationHttpPostgresTest`, `EvidenceHostDiagnosticsPostgresTest`, `EvidenceDiagnosticScopePriorityPostgresTest` | 실제 미해석 입력을 안전 제외하면서 나머지2명 이상 후보와 주최자 사유/원문/최종답변을 함께 표시. 유효하지만 특정 후보에 못 오는 사람과 해석실패 미반영자를 구분 |
| 확정·UI | 실제 smoke에서 confirmation/result200·CONFIRMED·선택ID/시간 재조회 일치. Java HTTP client 기반이며 실제 브라우저 실모델 전체 흐름 아님 | HTTP HOST권한·외부variant/stale analysis·멱등·선택튜플 보호, 기존 별도 웹 fixture/unit 증거 | 실제 질문·제외 사유·추천3안이 포함된 feature 웹 브라우저 경로. 웹/서버 develop 통합 또는 운영 성공으로 해석하지 않음 |

최근 실제 성공의 원본은 `luna-fallback-once-11ef9a92-0dc0-4094-ba1b-cacb5630e565/results.json`이다. Gemini503에 해당하는 SERVER+RetryAfter40초와 CLEAR는 test double이었고 실제 Gemini/Jev는0이다. 실제 Luna5.564초는 작은 명확한3입력1건의 관측이며 일반 성능/정확도 근거가 아니다.

## 후보1개와 A/B/C 정책

신규 정책은 **서로 다른 실제 시간안을 최대3개** 추천하며 복제해서 채우지 않는다. 시간안 내부의 방식/장소/참석 집합은 variant다. legacy candidates의 A=전원대면/B=전원온라인/C=부분참석 분류와 신규 시간안 표시순서 A/B/C는 별개다.

- PRD3.4(:71), FR-011(:218), Rule3(:276–282), ADR-047/048(:492 이후).
- `RecommendationProjector.kt:87–146`: `minOf(3, options.size)`를 선택한다. 참석 인원·명시 선호를 우선하고 같은 품질에서 날짜/시각 다양성을 적용한다.
- 실제 smoke의 `/candidates` 카드1과 `/recommendations` option1은 서로 다른 표현의 수다. 세 입력 모두 같은2026-11-12 18–20KST이므로 하나의 실제 창을 추천·확정한 정상 케이스다. 세 카드/세 시간안 생성이나 다양성은 이 실호출로 검증하지 않았다.
- 이번 무료 회귀에서는 domain의 실제 창0~4개와 복제 금지, HTTP의0/1/2/3/4/5개·3primary+전체대안·typed확정·stale/권한을 검증했다. model이3안 자체를 만들도록 prompt를 변경하거나 같은 창을 복제할 필요가 없다.

## 529: 실제 관측과 코드 판단 분리

실제 관측은 과거 Gemini503 및 status-null 실패, 별도15초 TIMEOUT2회, 작은30초 SDK 진단의2.411초503이다. **529는 실제로 관측하지 않았다.** 503의 과부하 원인/원격 또는 중계 발생층·기존 timeout의 최초 원인은 미확인이다.

`ProviderFailureClassifier.kt:27–45`의 현재 동작은 다음과 같다. 이번12개 오류분류 mock도 모두 PASS했다.

| 합성 HTTP/typed status | 현재 분류 | 재시도·최종 Luna 대상 |
|---|---|---|
| 503/UNAVAILABLE | SERVER | 예 |
| 529/UNAVAILABLE 또는 OVERLOADED | INVALID_REQUEST | 아니요 |
| 529/RATE_LIMIT_EXCEEDED | RATE_LIMIT | 예 |
| 일반429/RESOURCE_EXHAUSTED | QUOTA | 아니요 |
| 429/RATE_LIMIT_EXCEEDED | RATE_LIMIT | 예 |
| typed timeout | TIMEOUT | 예 |

일반529는 overload fallback을 기대한 사용자 의도와 차이가 있다. 이 결과는 **현재 코드 확인**이며 제품적으로 올바른 정책의 승인이나 운영529 수신 증거가 아니다. 이번에는 production classifier·threshold·prompt·fallback 정책을 변경하지 않았다. 다음 분류 변경은 별도 결정/RED→GREEN 대상이다.

## 최소 새 블라인드 계획 — 제안만

가장 작은 유용한 제안은 **개발 pilot1모임 + 별도 봉인 검증1모임, 각3명(총6입력)**이다. 두 사례로 통계적 정확도나 기존40+개 시나리오 전체 회귀를 주장하지 않는다. 기존40+개 전체 원본을 확보한 상태도 아니다. 기존 개발 사례·기존 봉인 gold를 이번 새 blind 자료로 재사용하지 않는다.

1. 먼저 현재 소스와 라우팅 가정을 고정한다. 현재 V2는 confidence 적용 후 NEEDS_REVIEW에만 Luna를 호출하고 저신뢰/INDETERMINATE는 질문을 생략한다. raw NEEDS_REVIEW 저신뢰의 우선순위와0.8 기준의 보정 근거는 TBD다(`EXPERIMENTAL_INPUT_EVIDENCE.md:21`). 승인 없이 threshold/prompt/분기를 바꾸지 않고 **현 코드 준수**와 **사용자 기대 충족**을 별도로 채점한다. 새 정책을 먼저 고르면 그 소스를 고정한 뒤 데이터를 작성한다.
2. 실행 승인이 있으면 fork 없는 새 작성자에게 서비스 사용자 설명만 준다. 코드·모델 prompt·threshold·현재 결과·실패 사례를 공개하지 않는다. 두 모임의 자연어·실제 의도·가능시간/불가/선호/장소 제약과 질문에 답할 사실·기대 교집합/미반영 사유를 실행 전에 고정·해시한다. 개발 세트와 holdout의 입력/답변/정답은 분리 보관한다. 봉인 원문·답변·개별 gold는 별도 실행/채점 담당만 읽으며 구현자는 집계와 익명 경계 실패만 받는다. 지금 작성자를 호출하거나 입력을 생성한 것은 아니다.
3. 사용자 관점의 커버리지 목표는 서로 다른 시간안3개, 보완 후 원조건 보존, A/B 선택 및 직접 답변, 답변 후에도 모르는 사실은 미확정 유지, 최소2명의 유효 입력으로 후보와 해석실패 주최자 표시, 확정 후 ID/시간 보존이다. 작성자가 로직을 역추론해 특정 문장을 맞춰 만들지 않도록 literal 예시를 여기서 정답으로 만들지 않는다. 실제 모델이 해당 질문 형식을 내지 않으면 그 경로는 미실행/실패로 기록하며 강제로 질문을 만들거나 데이터를 교체하지 않는다.
4. 독립 실행·채점 담당자가 고정된 의도에 따라서만 답변한다. 실제 질문이 기존 의도와 맞는 A/B라면 선택하고 그렇지 않으면 사전 고정한 직접 답변을 사용한다. 무관한 질문이나 본인도 모르는 사실에 답을 발명하지 않는다. 답변은 한 번이며 재입력·수정·취소·수동 재분석은 최소 유료 실행에 넣지 않는다(해당 경계는 무료 회귀로 분리).
5. 새 격리 Spring/PG/Redis와 feature 웹을 연결한다. 현재 단일Luna fixture의 CLEAR/Jev차단/FINAL_FALLBACK-only 허가를 그대로 재사용하면 전체 검증이 안 된다. **test-only 하네스**에서 원본 Jev·Luna 질문·원본Jdbc source-first를 연결하고 source/실행/attempt/operation별 전역 원장을 구현·무료 검증한 뒤 source/compiled/config/입력/답변 해시를 고정한다. 원본/답변/미해결정보·CAS·마감 동결·API/화면 선택과 host-only 진단을 확인한다. source안전장치/생산 prompt/정책은 바꾸지 않는다.
6. pilot을 순차1회 실행해 판단·생성·답변·동결·최종해석·정상조건/선호/미반영/기술실패·추천 수·확정을 구분해 보고한다. 모델이 질문을 생략하거나 안전한 선택지를 내지 않은 것을 숨기지 않는다. 기술 오류 또는 wire/claim/보고 coverage 불확실성이 발생하면 신규 실제 전송을 전역 차단한다. **예정된 synthetic Gemini SERVER는 실제 공급자 오류 중단 카운터에 넣지 않는다.** 승인을 재사용해 성공할 때까지 반복하지 않는다.
7. pilot의 source/권한/관측 결함이 없고 필요한 경로가 실행됐을 때만 같은 고정 소스로 봉인1모임을 한 번 실행한다. 질문 미실행·분기 차이 또는 개선이 필요한 결과면 holdout을 열지 않고 보고한다. 코드를 바꿨다면 같은 holdout을 개발 데이터로 삼지 않고 새 승인·새 봉인 자료가 필요하다.

질문은 현재 실험의 방·참여자별 최대1회 가정으로 계산한다. 그 범위와 무응답/검사중/기술실패 마감 정책의 운영 확정은 이번 계획으로 자동 승인되지 않는다. 실제 UI 시각 재설계나 운영 리뷰는 별도 스레드 범위를 유지한다.

## 모델별 물리 요청 상한과 승인 범위

**권장 최소안은 최종만 기존 합성 Gemini503→실제 Luna로 고정**한다. Jev와 질문 Luna는 실제 공급자를 사용하며 기존 source/gate·최종 Luna 계약은 유지한다. 따라서 남은 질문 흐름을 검증하면서 현재 Gemini503 장벽과 비용을 줄일 수 있다. 이 안은 실제 Gemini 정상 경로 또는 실제 Gemini 장애 복구를 검증하지 않는다는 한계를 유지한다.

6명 최초검사, 개인 Luna generation HTTP수G≤6, 게시된 질문수Q≤G, 답변수A≤Q이다. Luna가CLEAR/질문불필요 또는실패를반환해도이미쓴generation1회는G에포함한다. 답변 후 추가 Luna 생성은0이다. 개인 Luna는 현재 **Responses generation1HTTP**이며 countTokens 추가 HTTP는 없다. 옛 count-bundle/2 예약 메타데이터를 실제 호출 수로 사용하지 않는다(`LunaPreSubmissionCheckProvider.kt:113–139`, `TwoStagePreSubmissionCheckProvider.kt:180–219`). 권장안 합계는 `Jev(6+A)+개인Luna(G)+최종Luna(2)=8+A+G≤20`이다.

| 공급자/용도 | G=Q=A=2일 때 계획값 | 절대 상한 |
|---|---:|---:|
| Jev `jev-1.14.0`: 최초+답변 재검사 | 6+2=8 | 6+6=12 |
| Luna `gpt-6-luna`: 개인 질문 | 2 | 6 |
| Luna `gpt-6-luna`: 모임 최종 구조화 | 2 | 2 |
| 실제 Gemini | 0 | 0 |
| 합계 | **12** | **20** |

즉 새 승인 요청은 **Jev최대12 + Luna최대8(개인generation6/최종2), 총20물리 요청**, 모든 재전송 포함이다. 계획값12는개인generation총2·게시질문2·답변2이며그외개인generation이없을때만해당한다. 시나리오가 덜 호출되면 남은 슬롯을 쓰지 않는다. connectivity probe/count API/숨은 retry/추가 모임은 포함하지 않으며 추가하지 않는다. Jev+질문 단계의 기존15초와 최종Luna27초/전체60초, one-use approval/plan claims·물리 직전 durable cap·secret-safe usage/비용 미관측 표시를 유지한다. 질문이 예상보다 많아도 현재 참여자1회 한도 안에서만 진행하며20에 도달하거나 기술 오류가 나면 정지한다.

실제 Gemini까지 포함하는 별도 선택이라면 두 모임 최종에서 Gemini각최대4=8, 최종Luna각1=2로 **Jev12 + 개인generationLuna6 + Gemini8 + 최종Luna2 =28**이 코드상 보수 상한이다. 실제60초/중단 조건으로 더 줄 수 있고4+1을 보장하는 뜻은 아니다. Gemini가 두번모두최초에성공하고G=Q=A=2이면 총12회다. 28안의 하네스·재시도 중단 조건과 실제 공급자 비용은20안과 별도 승인해야 하며 남은 옛 승인 횟수는 재사용하지 않는다.

28은제품의기존retry를허용할때의코드상상한이다. 최소안의“첫실제기술오류전역STOP”를그대로적용하면Gemini실패뒤retry/fallback을끝까지실행할수없다. 실제Gemini장애복구까지시험하려면그중단조건을포함한별도유한계획승인이필요하며, 여기서조용히중단조건을완화하지않는다.

현재 **20안/28안 모두 미승인·미실행**이다. 이번 작업의 유료 호출0이다.

## 이번 무료 검증·보존

11suites/84tests **84PASS·실패/오류/skip0**. 원본 코드 수정 없이 관련 테스트만 실행했다. 전체 commit guard 또는 새로운 실제 모델 평가를 실행한 것이 아니다. SQL/질문/후보·개인정보 각 계약 검증이 연결된 단일 실모델 시나리오 성공과 같다고 합치지 않는다.

원본 실제 결과/manifest/claim/archive와 main289파일·기존SQL patch를 보존한다. 새 산출물은 이 계획, 진행 기록과 `local-only-evidence/{free-regression-verification.json,free-regression.log,xml/}`뿐이다. 병합·게시·운영 변경·새 유료 요청은 없다.
