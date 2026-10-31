# 최종 Luna 성공·시간 초과 비교 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

> 이 문서는 후속 실호출 전 비교 기록이다. 이후 새 승인으로 시행한 최종 Luna1회/60초 진단은 HTTP200·33.296초에 완료됐다. [후속 결과](experimental-final-luna-sixty-result.md)를 참조한다. 앱27초와 기존 실행 결과는 변경하지 않았다.

**fallback 호출은 두 실행 모두 실제로 이뤄졌다. 이번 실패는 Luna 미호출이 아니라, 거의 같은27초 caller 예산 안에 응답을 받지 못한 실패다.** 입력이 커지고 복잡해진 차이는 확인되지만 그 차이가 시간 초과를 일으켰다는 인과는 확인하지 못했다. 이번 작업은 보존 증거/코드 읽기와 크기 계산만 수행했으며 API 호출0·테스트 재실행0·생산 코드/설정 변경0이다.

비교 대상은 성공 `11ef9a92-0dc0-4094-ba1b-cacb5630e565`와 실패 `d2119585-667e-47c4-8877-5d4152fd47e2`다. 둘 다 합성 Gemini SERVER/503+RetryAfter40초 → 실제 최종 Luna1회이며 실제 Gemini0·자동재시도0이었다. 성공의 개인 precheck는 CLEAR test double, 실패는 실제 Jev/개인 Luna 및 답변을 거쳤다는 차이가 있다.

## 입력·스키마·설정 대조

| 항목 | 성공 | 이번 시간 초과 |
|---|---|---|
| 참여 입력 | 같은 짧은 명확한 시간 문장3개 | 서로 다른 자연어3개, 날짜/예외시간/선호/장소, 보완 답변1 |
| 원문 합계 | 114 code points / UTF-8 **234B** | 435 code points / **995B** |
| 별도 답변 | 없음 | 134 code points / **310B** |
| 검색 기간 | 11/12 하루(end11/13 exclusive) | 11/12~15(end11/16 exclusive) |
| 모델·API | gpt-6-luna / Responses | 동일 |
| 출력 계약 | meet_me_conditions_v3 / strict json_schema | 동일 |
| max_output_tokens / store | 32768 / false | 동일 |
| reasoning / service_tier / temperature / stream | 요청에 명시하지 않음 | 동일 |
| 최종 모델 input/output tokens | 2879 /355 관측 | 미관측 |

크기 계산에서 성공 원문은 manifest에 고정된 `PrimaryGeminiTechnicalSmokeTest.kt`의 문자열이다. 실패 원문/보완은 실제 동결 version3개를 `database.json`에 조인해 계산했다. 성공 원문은 같은 문장이 반복된 것이고 실패는 p1 UNRESOLVED+보완1/p2·p3 CONFIRMED다. 원문+답변 텍스트만 비교하면234B→1305B(약5.6배)지만 **공통 prompt·schema·JSON/evidence를 포함한 전체 API 요청이5.6배라는 뜻은 아니다.** 실제 최종 요청 바이트 원본과 실패 input token 수가 없어 그 비율은 미확인이다. DB evidence compact JSON1215B도 provider wire 크기와 구분해 계산 파일에만 남겼다.

최종 adapter·조건 contract·모델 configuration·예산·JDBC fence·관측 등 관련12개 source SHA는 두 manifest와 현재 파일에서 모두 동일하다. 전체 실험 소스가 동일한 것은 아니며, 그 사이 승인된 Jev V2 라벨 분기/하네스는 변경됐다. 두 최종 요청 모두 같은 v3 조건 추출 schema를 사용하며, 추천 A/B/C는 서버 후처리다. 개인 Luna의 low/2048 설정과 최종 요청의 설정은 별개다. **최종 요청에는 reasoning effort와 service tier가 명시되지 않았고 서버가 실제 적용한 값·모델 backend revision은 관측되지 않았다.** 스키마나 출력 상한이 이번에 바뀌었다는 근거는 없다.

방 방식은 성공 REMOTE/실패 IN_PERSON이지만 `NaturalLanguageBatchRequest`에는 meetingMode 필드가 없다. 이 방 설정 변화 자체가 최종 Luna 요청 옵션을 바꾼 것으로 해석하지 않는다. 입력 문장에 장소 조건이 추가된 차이는 실제로 있다.

## 시간 예산과 실제 경과

| 항목 | 성공 | 이번 시간 초과 |
|---|---:|---:|
| 설정 상한 | 27초 | 27초 |
| caller 잔여 | 26.995184908초 | 26.995410560초 |
| 상대 transport timeout getter | 26.833614647초 | 미계측 |
| transport DB Call 경과 | 5.564061744초 | 26.878637167초 |
| wire hook Call 경과 | 별도 수치 없음 | 26.879518916초 |
| adapter 경과 | 5.734450055초 | 27.004642555초 |
| 응답/오류 | HTTP200 / 성공 | HTTP null / wire TIMEOUT |

전체 최종60초는 `GeminiBatchProcessor.process` 진입에서 새로 시작한다. 앞선 Jev/개인 질문 시간이 그대로 최종 예산을 소진한 구조가 아니며 실제 caller 잔여도 거의 같다. 두 번 모두 합성 RetryAfter40초가 Gemini 단계30초보다 길어 즉시 Luna로 넘어갔다. 15초 잘림이나 Gemini30초 대기 때문에 실패했다는 설명은 이번 관측과 맞지 않는다. 완료예약3초·Luna attempt27초 및 gate의 authority deadline은 계속 적용된다.

성공 fixture는 같은 adapter를 내부 생성자로 재생성하고 call timeout 기록용 application interceptor를 추가했다. 실패는 원래 Spring `lunaParser`를 사용했다. adapter가 양쪽 모두 retry/redirect/phase timeout/call budget을 재설정하지만 이 client 조립/계측 차이는 남아 있다. 두 실행의 시간·입력·외부 서비스 상태도 다르므로 엄밀한 입력 크기 A/B 실험으로 볼 수 없다. 성공과 실패 모두 async transport observer를 사용하므로 동기/비동기 차이라고 설명할 근거도 없다.

## DB CONFIGURATION과 wire TIMEOUT

실패 wire는 timeout 계열 IOException cause를 보고 TIMEOUT으로 분류했고 `responseHeadersEnd`의 HTTP 상태는 관측되지 않았다. `parser_transport_client_calls`에는 FAILED/HTTP null1건이 저장됐으며, `coordination_attempts`에는 CONFIGURATION/finished=true가 저장됐다. **CONFIGURATION은 DB에 기록된 실패 분류이며 DB 쓰기 오류를 뜻하지 않는다.** 키 누락이나 HTTP 인증 거절·schema 오류가 실제 관측됐다는 뜻도 아니다.

`OpenAiLunaNaturalLanguageParserAdapter.kt:216–232`는 IOException을 분류한 뒤에도 finally의 `physical.observe()`가 `AiHttpDispatchException`을 던지면 CONFIGURATION을 새로 던진다. `AiHttpDispatchGate.kt:317–321`의 비금전예산 경로도 잔여 시간/authority deadline을 검사한다. 이 사후 오류 덮어쓰기 경로는 두 관측이 달라질 수 있음을 코드로 설명한다. 다만 이번 어느 deadline 검사에서 거부됐는지는 저장 trace로 확정하지 못했다. 운영 오류를 원래 TIMEOUT으로 보존하도록 수정한 것은 아니다.

## 확인과 미확인

확인: 실제 Luna Call1회/헤더 전송, 같은 모델·schema·최대27초, 더 크고 복잡한 입력, timeout 계열 실패, 응답 HTTP 상태 미관측, 최종 구조화0. 즉 최종 결과의 schema 검증 실패로 끝났다는 관측은 없다.

미확인: 입력 복잡성 때문에27초를 넘겼는지, 공급자 대기/부하/실제 reasoning effort/네트워크·프록시 지연인지, 정확한 최초 취소 또는 원격 reset 원인, 실패 요청의 전체 input/output tokens·청구액. 1회 성공/1회 실패만으로 어느 원인도 확정하지 않는다. 로컬 제한과 일치하는 실패라는 점과 원격 지연 이유는 별개다.

## 다음 최소 검증안 — 제안만, 미승인·미실행

추가 판단이 필요하다면 **실패 때 동결한 합성3입력+보완을 동일 contract로 재구성하여, 최종 Luna만 최대1회·60초의 격리 진단**을 제안한다. Jev/개인 Luna/Gemini0, 재시도·fallback0, model/schema/prompt 생성 규칙·32768 출력 상한은 유지하고 응답/오류 모두 종료한다. 새 승인·payload/source hash·한 번만 소비되는 원장1회·첫 header/종료/취소 순서의 비밀 없는 계측이 필요하다. 실제 wire 원본이 없으므로 동일 논리 입력의 재구성이며, 과거 요청과 바이트 단위 동일성을 주장하지 않는다.

기존 adapter와 JDBC authority가27초를 강제하므로 caller/client timeout만60초로 올려도 이 진단이 되지 않는다. 제안은 **기존 앱 예산/fence를 통하지 않는 별도의 단발 Responses 진단**이며, 그 대신 endpoint·합성 payload·물리1회·60초 watchdog을 별도 고정해야 한다. 기존 앱/운영의27초·전체60초·권한 정책을 변경하거나 몰래 우회해 실행하는 계획이 아니다. 이 별도 경계와 추가 사용료를 포함한 명시 승인이 있어야 준비·실행한다. 이번에는 만들거나 호출하지 않았다.

27~60초 사이 성공하면 그 시점의 같은 논리 입력이 기존 예산보다 오래 걸려 응답할 수 있다는 증거가 된다. 27초 안 성공하면 간헐성/실행 경로 차이를 포함한 다른 요인이 남고,60초에도 실패하면 원인 미확인을 유지한다. 어느 결과도 과거 실패의 입력 크기 인과나 운영 timeout60초의 적합성, 전체 앱 E2E 성공을 증명하지 않는다.

비용 없는 별도 후속은 TIMEOUT+사후 observe 거절의 원래 오류 보존 계약을 추가 검증하는 것이다. 이미 끝난 테스트를 반복하는 대신 새 조합을 검증할 수 있으나, 이는 관측 정확도 개선이며 공급자 응답 지연을 해결하지는 않는다. 이번 읽기 전용 범위에서는 구현·테스트하지 않았다.

## 근거

- 기존 성공: `local-only-evidence/{results,post-run-verification}.json`, 성공 승인 manifest `luna-fallback-preparation-20261030/11ef9a92-0dc0-4094-ba1b-cacb5630e565.approved.json`.
- 이번 실패: `local-only-evidence/{results,database,wire-ledger}.json`, `local-only-evidence/approved-manifest.json`.
- 계산·source12개/원본 artifact SHA: `local-only-evidence/{input-metrics,timing-and-settings,comparison-evidence}.json`.
- 함수 근거: `OpenAiLunaNaturalLanguageParserAdapter.parseObserved`(:95,216), `NaturalLanguageProviderContract.prompt`(:306), `GeminiBatchProcessor.process`(:76), `AiHttpDispatchGate.forPreparedCall`(:317), 성공 client 조립 `LunaFallbackOnceFixture.kt:288`, 실패 원본 bean 감싸기 `BlindFlowApplication.kt:93`.

추가 API0·재실행0·봉인 접근0·생산 변경0·병합/배포0이다. 기존 결과/원장은 수정하지 않고 새 비교 자료만 남겼다.
