# 라벨 중심 V2의 무료 전체 연결 검증 준비 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

사용자 `synthetic-authorization-890fa4fd8b5e0955`의 이어서 진행 요청에 따른 격리 후속이다. 기준은 실험 브랜치 `fix/issue-107-gemini-failure-taxonomy`, HEAD `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a`와 승인된 로컬 V2 변경이다. main/develop 병합·운영 변경·추가 유료 요청은 하지 않는다. 과거 cap20은 실제4회 후 오류 중단으로 끝났으며 잔여16회는 새 승인으로 간주하지 않는다.

## 확인 범위

새 통제 합성3명 모임에서 공개 HTTP → 실제 Spring/격리 PostgreSQL·Redis → 원본 Jev/personal Luna adapter 및 라벨 중심 TwoStage → 답변 후 재검사·미해결 접수 → 동결 → 최종 Luna adapter → 서버 계산 → HOST 미반영 사유·추천·확정 저장을 연결한다. 공급자 응답은 테스트 application interceptor에서 합성 JSON으로 반환한다. 실제 모델의 판단 품질·속도·원격 전송·브라우저/웹 UI 검증과 구분한다. 미공개 봉인 시나리오의 원문/답변/gold는 열지 않는다.

`BlindFlowHttpFlow`의 하드코딩 REMOTE를 없애고 `MeetingMode`를 명시 인자로 받는다. 실제 생성 방의 DB `meeting_mode`도 일치해야 한다. 기존 카페 pilot의 다음 실행 모드는 `IN_PERSON`으로 지정하되 원래 input.json을 수정하지 않는다. 실행 manifest의 `meetingModes`를 별도 메타데이터로 쓰며 누락 시 REMOTE로 대체하지 않는다. 봉인 모드는 독립 실행 담당자가 의도에 맞게 정해야 한다. 기존 소비된 승인·manifest·source archive는 그대로 보존한다. 이 변경이 과거 Jev 라벨의 원인을 증명하는 것은 아니다. 개인 분류 요청은 meetingMode를 전달하지 않는다.

테스트용 최종 Luna 관측은 성공 시에만 남던 caller/elapsed 기록을 `finally`로 옮겨 실패에도 보존한다. 원래 예외·결과는 유지하며 body/header/원문/예외 message를 기록하지 않는다. 실제 transport의 절대 deadline을 새로 계측한 것으로 해석하지 않는다.

## 최종 Luna 시간 예산 대조

| 항목 | 성공 `11ef9a92…` | 실패 `c87e564a…` |
|---|---|---|
| 최종 Luna 설정 상한 | 27초 | 27초 |
| caller 잔여 시간 | 26.995184908초 | 정확값 미관측; 0초 초과·27초 이하 assertion 통과 |
| 상대 transport call timeout | 26.833614647초 | 미관측 |
| 실제 HTTP 경과 | 5.564061744초 | 미관측 |
| 결과 | HTTP200/구조화·추천·확정 성공 | wire TIMEOUT/HTTP상태 미관측, DB CONFIGURATION |

성공은 27초, 실패는 개인 질문용15초가 적용됐다는 근거는 없다. 전체 최종 분석60초는 `GeminiBatchProcessor.process`에서 시작하며 앞선 개별 Jev 검사시간을 공유하지 않는다. 완료 예약3초를 제외한 deadline과 Luna attempt 시작+27초 중 짧은 시각까지 사용할 수 있다. DB claim 대기도 그 시간에 포함된다. gate는 caller의 monotonic 잔여와 DB authority의 wall-clock deadline을 다시 제한한다. 두 실행 모두 합성 Gemini SERVER+RetryAfter40초라서 Gemini 단계30초를 기다리지 않고 Luna로 전환했다.

관련 코드는 `AnalysisInvocationBudget.kt`, `GeminiBatchProcessor.kt`, `JdbcAiBudgetExecutionFence.kt`, `AiHttpDispatchGate.kt`다. 두 실행 manifest의 이 파일들과 최종 Luna adapter·async observer SHA는 같으며 현재 소스와도 같다. 성공 fixture는 내부 생성자로 같은 adapter를 재생성하여 client timeout 기록 interceptor를 추가했고, 실패 fixture는 원래 Spring `lunaParser`를 감쌌다. 성공도 async observer port를 주입하므로 동기/비동기 차이로 설명할 수 없다.

`OpenAiLunaNaturalLanguageParserAdapter`의 IOException 분류 후 `finally physical.observe()`가 dispatch 예외를 던지면 원래 TIMEOUT을 CONFIGURATION으로 덮을 수 있는 코드 경로가 있다. 실제 실패에서 어느 deadline 검사에 걸렸는지, 원격 지연의 최초 원인, 빈 비동기 transport DB의 이유는 미확인이다. 실패 JUnit 전체33.241초를 API 소요시간으로 쓰지 않는다. 이번에는 생산 timeout·분류·retry/fallback 정책을 바꾸지 않는다.

## 다음 최소 실모델 재검증 — 제안, 미실행

먼저 **공개된 개발 pilot1모임·3명만** 새 승인으로 실행하는 안을 권장한다. 원문/질문 응답의 사전 고정된 의도를 유지하고 모임 방식만 올바른 IN_PERSON 실행 메타데이터로 지정한다. 과거 실패 입력에 맞춰 prompt·threshold·라벨을 강제하지 않는다.

| 실제 공급자 요청 | 최대 물리 요청 |
|---|---:|
| Jev 최초3명 + 답변한 사람만 재검사 | 6 |
| 개인 Luna 최초 확인, 각 최대1회 | 3 |
| 최종 Luna | 1 |
| 실제 Gemini | 0 |
| 합계 | 10 |

최종 분석은 합성 Gemini503+RetryAfter40초 → 실제 Luna fallback이다. SDK/앱 자동재시도0, 추가 생성0, precheck 공유15초·최종 Luna 최대27초·전체60초/완료예약3초를 유지한다. 물리 요청별 source/immutable profile·사전예약·누적 ledger와 한 번만 소비되는 새 승인 식별자/manifest/source hash가 필요하다. 기존 cap20의 승인·잔여 요청은 재사용하지 않는다. 기존 `BlindFlowSettings`/budget은 예전 승인 ID와20회 한도를 강제하므로 새10회 승인에 맞춘 별도 runner/preflight를 먼저 준비·무료 검증해야 한다. 기존 runner를 그대로 실행하는 계획이 아니다.

첫 실제 기술 오류·유효하지 않은 응답·권한/계측/횟수 불확실이면 전체 중단하고 재호출 없이 보존한다. 예측 가능한 합성 Gemini503만 이 조건에서 제외한다. 유효한 개인 Luna CLEAR는 정상 결과이며 질문을 꾸며 만들지 않는다. 질문 경로가 나오지 않으면 그 경로는 실검증 미완료로 보고하고 같은 입력을 반복하거나 원문을 바꿔 성공을 유도하지 않는다. 질문이 나오면 사전 고정된 사실에 맞춰 답변하고 최종 추천/미반영 사유/확정 저장까지 관측한다. 끝나면 더 호출하지 않는다.

이 최소안에 봉인 실행·새 시나리오 생성·실제 Gemini 정상 경로·브라우저 검증은 포함하지 않는다. pilot 통과 후 봉인은 별도 범위로 판단하며 구현자가 개별 gold를 열어 수정하지 않는다. 사용량은 관측된 token만, 청구액은 확인될 때만 보고한다. 이번 문서의 요청 수는 제안이며 실행 승인이 아니다.

## 검증 결과

새 통제 모임 무료 연결1개와 timing2개, 관련 회귀79개, V2 evidence PostgreSQL10개가 모두 PASS/Gradle0다. **합계9개 suite/92 PASS·실패0·오류0·skip0**다. V2 PG는 A/B 선택 후 원문/대상 보완 보존, 직접답변, 멱등/CAS 경쟁, 질문1회 예산·취소, 미해결 접수와 마감 동결을 확인한다. `ktlintCheck`와 `assemble`도 PASS다. 전체 CI/commit guard 재실행이나 실제 모델 검증으로 확대하지 않는다.

| 새 합성 참여자 | Jev 유효 라벨·confidence | 개인 Luna·답변 처리 | 최종 모의 해석 |
|---|---|---|---|
| question | 최초 NEEDS_REVIEW/0.4 → 답변 후 INDETERMINATE/0.4 | TEXT_ONLY 질문1·직접답변1·추가생성0, RECEIVED/UNRESOLVED | AMBIGUOUS_TIME_CONSTRAINT/TIME 미반영 |
| luna-clear | INDETERMINATE/0.4 | Luna CLEAR, 질문0 | 반영 |
| direct-clear | CLEAR/0.4 | Luna 호출0 | 반영 |

설정된 기존 confidence0.8이 위 유효 라벨을 덮지 않음을 실제 원본 classifier/router에 합성 응답을 연결하여 확인했다. 합성 Jev4/개인Luna2/최종Luna1, 합성 Gemini5031이다. **실제 공급자 요청은0회**이며 이 수는 API 과금 호출이 아니다. source authorize14회/서로 다른 요청7개·거절0, 외부 provider header/DNS/connect 시도0, 장소 검색0이다. 금전 예산 enforcement는 OFF인 격리 계약 검사이며 실제 유료 admission/HTTP wire 관측과 구분한다.

원문·답변·미해결 이유·원래 및 답변 기준시각·동결 version3개가 실제 최종 adapter JSON에 전달됨을 비교했다. structured3/반영2/미반영1, PARTIAL·READY_WITH_WARNINGS, 추천1개(동일한 실제 시간창 18–20 KST, 대면 참석2명), HOST 확정200·result200·CONFIRMED·선택 run/option/variant/시각의 DB 일치를 확인했다. 최대3개 정책에서 가능한 시간창1개를 확인한 것이며 A/B/C3개 생성이나 모델 의미 품질을 증명하지 않는다.

HOST에게 미반영 원문·최종답변·TIME 사유만 표시하고 개인 질문은 숨긴다. 비로그인/guest는 정확히401/ACCOUNT_REQUIRED이며 합성 원문·답변·질문이 응답에 없다. 인증된 MEMBER403·민감정보 비공개는 별도 기존 `EvidenceHostDiagnosticsPostgresTest`3개 PASS 근거다. 그 fixture는 계정 context를 모의하므로 실제 JWT의 다른회원 권한 검사까지 주장하지 않는다.

첫 실행은 새 fixture의 Kotlin 혼합 map 타입 추론/format 오류로 테스트 전에 실패했다. 두 번째는 driver에 추가한 guest403 기대가 계정 필수 HOST_COMMAND 계약과 달라 연결 테스트1FAIL(timing2PASS)이었다. 원본 resolver와 독립 검토에 따라401+ACCOUNT_REQUIRED 및 민감정보 부재로 정확히 교정했으며 제품 권한 코드는 바꾸지 않았다. 첫 compile 실패 때 이전 XML이 수집된 것도 `connected-stale-xml-not-from-this-run`으로 분리·정정하고 이후 runner는 실행 전 이전 XML을 별도 보존한다. 실패·설정 오류를 기능 RED/최종 PASS로 바꾸어 세지 않는다.

통과 연결 원본: `local-only-evidence/free-contract-results.json`. 실행 로그/XML/수집 정정: `local-only-evidence/free-label-flow-preparation-20261030`의 connected-3/regression/evidence 기록과 `verification.json`, `preservation.json`. 첫 connected compile 오류와 connected-2 기대값 오류도 별도 보존했다. 다음 유료10회안은 제안뿐이며 실행하지 않았다. 생산 src/main289개와 기존 시나리오7개는 독립 해시 비교로 그대로임을 확인했고 봉인 JSON을 파싱하지 않았다. 웹 변경0·병합/배포0이다.


최종 독립 읽기 검토에서 새 연결의 source/합성 HTTP 경계·동결 근거·HOST 공개/guest 거부·확정 DB 검증·다음 요청 한도의 수정 필요 결함을 발견하지 못했다. 생산 소스289개와 기존 시나리오7개 보존을 별도 재확인했다. 프로덕션 Kotlin/SQL/프롬프트/threshold/제한 변경0, 새 commit/PR/병합/배포0이다. 남은 것은 새 실모델 재검증 승인과 그 승인에 맞는 실행기 무료 사전 검증이며, 실제 모델의 의미 품질·지연 및 웹 브라우저 전체 흐름은 이번 결과로 검증되지 않았다.
