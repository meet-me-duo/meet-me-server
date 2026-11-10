# 분석 정상화: 반복 가능한 실제 HTTP 검증

사용자가 지정한 검증 사례로 **현재 실행 → 기대와 비교 → 원인 분석/가설 → 한 가지 수정 → 빌드/Run → 같은 입력 재검증**을 반복한다. 이 절차는 아래 과거 단발 모델 평가와 별개이며, 당시 예산·실행 승인을 재사용하지 않는다. 현재 구현과 [V5 인계 문서](../../V5_HANDOFF.md)를 함께 확인한다.

## 실행 경로와 기준 고정

- 기존 `ExperimentalLiveHttpEvaluationTest`를 `c5HttpCandidateTest`로 빌드·실행한다. 실제 HTTP·공급자·PostgreSQL/Redis를 쓰며 test-only V5 bean과 명시 계약 선택을 사용한다. 일반 `java -jar`·브라우저 로그인·운영 배포와 구분한다. prod의 신규 기본 선택은 별도 공통 마감 HTTP 회귀에서 검증하며 이 하네스의 명시 선택 결과를 기본 선택 검증으로 바꾸어 보고하지 않는다.
- 각 사례는 별도 JVM/DB/출력 디렉터리로 한 번 실행한다. 한 방의 실패 때문에 나머지 인원을 생략한 결과를 전체 성공으로 표시하지 않는다. 공통 인증·결제·통신 오류는 원인을 확인한 뒤 후속 송신을 결정한다.
- 브랜치·분석 계약·접수 프로토콜·기준시각·검색기간·모임 방식·결과 선택 길이를 비공개 실행 명세에 고정한다. payload의 검색 종료일은 포함하며 API 종료일은 다음 날로 변환한다. 결과 선택 길이는 시험 설정이며 모델이 정할 값이 아니다.
- 최신 인계 생성 후보를 시험하려면 `schema-coherence`, `provider-parity`, `common-finite-schema`를 true, `selected-choice-context`를 false로 고정한다. known-date/선택지 후보를 함께 켜지 않는다. 이 설정은 실모델 성공이 확인된 설정이라는 뜻이 아니다. 다른 설정은 별도 manifest와 비교 이유를 남긴다.
- 반복 승인 시간어의 조건 문맥 식별 후보는 `scoped-term-evidence=true`로 별도 선택한다. 생략/false는 기존 전역 유일 인용 검증을 유지한다. 이 옵션은 같은 입력/생성 schema를 유지하고 근거 지침·검증 경계만 비교하며, 새 저장 근거는 format9로 구별한다.
- 실제 사용자 원문은 비교 기준으로 고정한다. 사용자가 원문 편집을 명시적으로 요청하지 않으면 줄임말 교정·문장 재작성·기존 맥락 삭제나 보완답의 원문 합치기를 하지 않는다. 보완답은 실제 질문에 연결된 별도 API 응답으로 보존한다. 답안 파일을 준비했어도 실제 질문·전송이 없으면 최종 모델에 반영됐다고 주장하지 않는다. 이 경우 원문을 바꿔 우회하지 않고 반영되지 않은 범위를 기록한다.
- 원문·보완답안·질문/답·요청/응답·DB 증거·로그·실행별 manifest와 보고서는 저장소 밖에 둔다. 개별 사례의 이력·작업 메모는 Git에서 제외된 `local/`에 보관한다. 공개 저장소에는 재사용 절차·도구·시간 용어 기준표와 비개인 합성 테스트만 둔다.

## 정답지: 승인된 한국어 시간 용어 기준표

사용자가 말하는 **정답지**는 보완 질문의 답안이 아니라 `ko-time-terms-1` 기준표다. 현재 서버 계산 기준은 `src/main/kotlin/com/meetme/server/submission/domain/time/ApprovedKoreanTimeTermPolicy.kt`, 프롬프트 표는 `ScheduleDslApprovedTermsProviderContract.kt`에서 V4/V5 및 C5 후보로 전달된다. 현재 AM 00–12, PM 12–24, DAWN 00–06, MORNING 06–11, LUNCH/MIDDAY 11–15, DAYTIME 09–18, EVENING 17–22, NIGHT 20–익일06, LATE_NIGHT 22–익일04, ALL_DAY 00–24다.

단순 용어는 기준표로 계산하고, 늦은 점심 등 문맥은 같은 요청에 포함된 원문과 함께 해석한다. 문맥 추정은 INFERRED/NON_PRECISE, 명시 시각은 원문 근거를 유지하며 기준표로 덮어쓰지 않는다. 보완 질문의 실제 답은 이 시간표와 별개다.

관리 개선 제안은 버전이 있는 단일 JSON에서 서버 구간과 프롬프트 표를 함께 생성하는 것이다. 아직 구현/정책 변경이 확정된 것은 아니며 첫 baseline에는 현재 코드/표를 유지한다. LLM은 로컬 파일 경로를 읽지 않으므로 JSON을 분리하더라도 실제 요청에 표 내용을 직렬화해야 한다.

## 사례별 검증 명세와 누락 자료

시간 용어 정답지와 **사례별 검증 명세**를 구분한다. 비공개 사례 원문·완성 기대 결과·실제 보완답이 인계 자료에 없으면 제공받은 자료의 범위까지만 사용한다. `src/test/resources/schedule-ir-integration/sources-and-oracles.json` 같은 별도 합성 사례를 다른 입력의 정답으로 대입하거나 누락 자료를 임의 복원하지 않는다.

새로 선택한 시험 설정과 확인된 과거 설정을 구분한다. **명시 가정에 따른 초기 관측**을 기존 실행 재현이나 완성 검증 명세에 대한 전체 통과로 분류하지 않는다. 보완 질문·답변 분기의 실제 실행 여부를 기록하고 시험용 보완답을 참여자가 확인한 사실로 간주하지 않는다.

후속 실모델 실행 전에 원문·검색 날짜 범위·모임 길이/선택 길이·모임 방식·필요한 보완답을 확인하고, 참가자별 기대와 최종 기대를 비공개 명세에 먼저 고정한다. 모르는 항목은 미확인으로 남기며 부분 기대를 전체 의미 통과로 확대하지 않는다. 실행 기록은 입력 → 실제 처리 → 사전 기대 → 실제 결과/차이 → 확인된 원인/가설을 따른다. 원본 응답과 과거 manifest는 유지하고 후속 정정은 별도 qualification에 기록한다.

## API 순서

1. 합성 로컬 HOST 계정으로 `POST /api/rooms`. HOST는 추가 가용시간 응답자가 아니다.
2. 각 참여자 `POST /api/rooms/{code}/participants` 후 해당 게스트 cookie를 유지한다.
3. `POST .../submission/checks`에 새 request ID·현재 revision·원문을 보낸다. `GET .../checks/current`를 polling하여 QUEUED/CHECKING이 끝날 때까지 기다린다. 최초 Jev 결과와 실제 질문 생성 여부를 따로 기록한다.
4. NEEDS_CHOICE이면 **실제로 반환된 질문**을 읽고 고정 보완답안을 대조한다. 실행 도구는 `question-routing/{questionId}.pending.json`을 만든다. 보완답안의 `directAnswer` 또는 `offTopicResponse`를 골라 같은 위치의 `{questionId}.decision.json`에 `{"questionId":"실제 ID","field":"directAnswer"}`처럼 기록한다. 관련 없는 질문이나 개인 사실이 없는 질문에 새 사실을 발명하지 않는다. 보완답안이 없으면 질문을 사용자에게 전달하거나 미응답으로 기록한다.
5. 도구가 `POST .../checks/{checkId}/answer`에 request ID·candidate revision·check version·question ID와 답을 제출하고 재판정을 polling한다. 직접 답은 `direct_text`, 실제 선택 클릭은 `choice_id`로 구분한다. label과 proposedText를 혼동하지 않는다. 원문을 답으로 덮어쓰거나 선택지 의미를 사후 추가하지 않는다.
6. 모든 참여자의 실제 접수 상태가 CLEAR/RECEIVED인지 확인한 뒤 `POST .../close`에 `confirm_early=true`, `analysis_contract=DSL_STRUCTURAL_V5`를 명시한다. 미접수자를 빼고 전체 성공으로 진행하지 않는다.
7. 방 상태를 polling하고 candidates·unapplied-inputs·recommendations를 조회한다. 조건부 참석을 해석하는 시험 클라이언트는 실제 웹과 같이 candidates·recommendations/alternatives·confirmation·result의 해당 요청에 `attendance_support=conditional-v1`을 명시한다. 이를 누락한409를 분석 실패나 추천 없음으로 해석하지 않는다. 최종 provider 원장, 실제 응답, strict reader 거부 사유, IR 저장·새 JDBC 복원/재계산을 대조한다. 추천이 있을 때만 기존 API로 길이에 맞는 결과를 선택한다.

실제 질문과 질문/원문 해시·revision이 결속된 `TEST_SYNTHETIC` 경로도 기존 도구가 제공한다. 사용한 경우 답변은 시험 가정이며 실제 참여자의 사실로 보고하지 않는다. 보완답안은 최종 분석 모델에 넣지 않고, 보완 답변 선택과 사후 평가에만 쓴다.

## 실행 명령과 파일 결속

현재 사용자 요청의 수신자·목적·호출 상한을 private manifest에 기록한다. manifest에는 단일 방 payload/보완답안/config의 절대 경로와 SHA-256, 현재 소스 SHA-256, 기대 결과, 새 outputDirectory가 필요하다. 같은 출력 디렉터리를 재사용하거나 실패한 요청을 근거 없이 재송신하지 않는다.

새 시험의 비공개 `run-spec.json`에는 `caseId`, `payloadPath`, `answersPath`, `configPath`, `outputDirectory`, `maximumPhysicalCalls`, `dataClassification`, `expected`, `settingsSource`, `authorizationReference`를 명시한다. `expected`는 부분 기대/미확인 사항을 구분하고 `settingsSource`는 사용자가 확인한 설정인지 새 시험 가정인지 적는다. `--prepare`는 이 자료의 현재 해시를 결속해 같은 디렉터리에 **새** `manifest.json`을 만들며 모델을 호출하거나 누락된 답을 생성하지 않는다.

```bash
# supplied run-spec을 준비한 후 현재 코드/설정/입력과 기대값을 동결한다. 모델 호출 없음.
python .codex/evaluation/run-analysis-flow.py /absolute/private/new-case/run-spec.json --prepare

# 기본 동작은 파일·설정·소스 결속만 확인한다. 모델 호출 없음.
python .codex/evaluation/run-analysis-flow.py /absolute/private/case/manifest.json

# Cloud의 Gradle cache/toolchain/proxy 설정을 유지한다. 키는 환경 설정에서만 주입한다.
GRADLE_USER_HOME=/workspace/.gradle python .codex/evaluation/run-analysis-flow.py \
  /absolute/private/case/manifest.json --run

# 실행 중 pending 질문을 읽고 실제 근거가 있는 보완답을 선택했을 때만 사용한다.
python .codex/evaluation/run-analysis-flow.py /absolute/private/case/manifest.json \
  --answer ACTUAL_QUESTION_UUID --field directAnswer

# 현재 소스가 변경됐어도 과거 실행의 원본 manifest/응답을 유지한 채 보고서를 만든다.
python .codex/evaluation/run-analysis-flow.py /absolute/private/case/manifest.json --report
```

runner는 기존 test task에 명시 설정과 상속 proxy/trust만 전달한다. 기본 runtime/reader·재시도·시간 제한을 바꾸지 않는다. 소스 변경 뒤에는 관련 최소 회귀와 `ktlintCheck assemble testClasses`를 실행하고 새 manifest를 만든다. 실제 모델 호출 전 명세의 누락 사실과 설정을 확인하고 manifest를 검토한다. 이미 확인/승인한 동일 범위를 매번 재승인받지 않는다.

선택 config의 `place-constraints=true`는 공통 V5의 source-bound 장소/이동 선언 후보이며 `provider-parity=true`와 함께 사용한다. 기본은 false다. 이 후보는 원문·시간 조건·모임 길이·시간어 표를 유지하며 기준 장소/분 단위 상한/미검증 상태의 추출·format10 복원·선택된 참석 집합의 공개 설명을 각각 대조한다. 이동 기준점을 실제 모임 장소로 저장하거나 원응답의 누락을 사후 문구로 채우지 않는다. 기존 설정은 선택 키 없이도 유효하다.

선택 config의 `union-preferred-availability=true`는 새 V5 run에 schedule-structural-union-1을 동결한다. 기본은 false이며 기존 V5/V4·저장 결과의 정책은 바꾸지 않는다. 평가된 명시 가능과 독립 선호를 합친 뒤 전역 불가를 차감하고, 제한된 PREFER(base, preference)·ALL_OF·EXCEPT·CHOICE는 평탄화하지 않는다. 선호-only/시간미상 가용 근거에는 추가 가점이 없고, 계산 가능한 가용 근거가 있는 혼합 입력만 선호 가점을 준다. 새 일반 지침과 계산의 버전 결속·과거 복원을 검증한 후 실제 원문을 재시험한다. 실제 사례로 지침을 맞춘 뒤 같은 자료를 독립 평가라고 부르지 않으며, 미평가 합성 자료의 원문·기대값은 실행 전에 고정한다.

선택 config의 `server-evidence-references=true`는 새 V5 run의 생성 계약을 `schedule-dsl-evidence-response-1`로 동결한다. 기본 false이며 이전 run과 후속 재계산은 당시 계약을 유지한다. 서버가 실제 원문·보완답 구간의 ID를 발급하고 모델은 모든 근거 필드에서 ID를 선택한다. 새 native/runtime·평가 전송은 공통 wire로 같은 데이터·지침·유한 schema를 사용한다. 새 profile은 장소 선언과 입력 기준 날짜/검색 맥락을 포함하며 평가에서는 `provider-parity`와 `place-constraints`도 true여야 한다. 이전 생성 계약의 quote/offset 검증은 유지하고 새 원응답에 quote·offset·모델 작성 proof bank가 섞이면 거부한다.

새 근거는 `server-evidence-catalog-1`과 format11을 사용한다. 같은 문구라도 위치별 ID를 구분하고 출처·버전·코드포인트 위치·해시·조건/분기 결속을 검증한다. 원 모델 JSON의256KiB 제한은 그대로이며, 새 profile의 HTTP envelope는1MiB, 서버 자체의 원문 연결은2MiB, 신뢰된 후속 변환은4MiB로 각각 제한한다. 초과를 추가 모델 호출이나 조용한 목록 절단으로 보정하지 않는다. 고정된 안전 오류 코드와 원본 증거를 구분해 기록한다. 구 binary는 format11을 읽지 못하므로 writer 옵션 OFF를 구 binary rollback의 안전성으로 간주하지 않는다.

근거 ID는 의미의 정답을 보장하지 않는다. 문맥 구간과 서버가 식별한 정확 시간어·시각·공개 장소 하위 구간을 선택할 수 있지만 미등록 장소의 정확 명칭 구간은 목록에 없을 수 있다. 없는 구간을 발명하거나 문장 전체를 정확 장소명으로 표시하지 않으며 미해결·미검증 상태와 유효한 시간 후보의 처리까지 확인한다. 실제 원문·보완답 전송 여부, 모든 참조 ID와 실제 구간, 모델 선언·검증·DB 복원·최종 후보를 별도로 대조한다.

선택 config의 `relative-date-context=true`는 공통 V5 시험 요청에만 적용하는 명시 날짜 맥락 후보다. 기본값은 false이며 `provider-parity=true`를 요구한다. 기존 기준시각·검색기간·달력 정보에 더해 frozen source별 `input_ref/source_id/revision/reference_at/input_local_date`와 시작일 포함·종료일 미포함·마지막 포함일을 짧은 `relative_date_context`로 제공한다. 재분석 당일 시각을 기준으로 삼거나 목표 월·참가자 가용성을 대신 결정하지 않는다. 원문·기존 의미 규칙·응답 schema·reader는 유지하고 맥락 사용 지침만 덧붙인다. 실제 새 보완답이 추가되면 맥락만 바꾼 단일 변수 비교와 구별한다.

C5 응답 수신은 HTTP 포장·모델 JSON·원문 관측 상한을 구별한다. 명시 place 후보의 API 응답 포장은 지침·스키마 echo를 포함하므로1MiB, 실제 모델 JSON은 기존256KiB UTF-8 한도를 유지한다. 기본 prototype의 OpenAI 포장256KiB 및 Gemini 포장1MiB는 그대로다. 관측이 켜져 있으면 최대2MiB까지 완전한 응답을 비공개로 보존한 뒤 해당 허용 크기를 검사한다. 허용 상한을 넘으면 `LOCAL_RESPONSE_BYTE_LIMIT`와 선언 길이·실제 관측 길이를 남기고 계속 거부한다. 관측 상한도 넘으면 `captureComplete=false`이며 완전한 원문을 보존했다고 주장하지 않는다. 관측 callback은 원문 복사본만 받으며 관측 실패로 모델 바이트나 허용 판정을 바꾸지 않는다. 시간 초과 등 원문이 없는 과거 실행을 임의로 복원하지 않는다.

기존 evaluator는 실제 질문마다120초 동안 decision을 기다린다. 이 시간 안에 답을 확인할 수 없으면 미응답으로 남기고 임의 답/부분 접수로 마감하지 않는다. 중단·실패 뒤 기존 결과 파일을 지우거나 동일 request를 자동 재실행하지 않는다. 다음 비교는 변경 가설과 새 실행 디렉터리를 먼저 기록한다. `execution-report.json`의 의미 판정은 자동 PASS가 아닌 검토 대기 상태다.

## 결과와 다음 가설

각 실행은 코드·실행 설정·입력·보완답안 해시, 최초/재판정 Jev, 질문과 **실제 전송한 답**, 접수된 version, 최종 모델/시도/HTTP/토큰/시간, strict reader 사유, 저장/복원, 추천·누락·조건부 상태를 남긴다. HTTP 200·CLEAR·테스트 종료 코드와 의미 정확성을 별도로 판정한다. 원장 안의 과거 tranche/비용 참고 필드를 현재 누적 예산이나 청구 증거로 쓰지 않는다.

참가자별 가용·불가·선호와 최종 교집합·참석 인원을 고정 명세에 대조한다. 이동시간 조건은 실제 경로 검증과 장소 힌트를 구분하며, 장소 미확정을 시간 교집합 없음으로 오인하지 않는다. 날짜 전일, 근사 시각, 부정, 선호-only/선호 점수와 역할/root 연결도 별도로 확인한다.

실제 흐름 실행은 선택 POST와 최종 결과 GET이 모두 HTTP200이어야 완료로 기록한다. 각 응답과 상태를 먼저 보존하고 실패하면 `FINAL_SELECTION/INCOMPLETE_SELECTION`으로 중단해 후속 방 호출을 막는다. 이 검사는 기술적 흐름 완료를 확인하며 의미 정답 대조를 대신하지 않는다.

관측 사실 → 기대와 차이 → 확인된 직접 원인 → 미확인 가설 → 다음에 바꿀 한 가지 순서로 기록한다. 기술 실패는 의미 실패와 구분하며, 응답을 고치거나 validator를 약화해 통과시키지 않는다. 정상화 작업은 각 실험의 근거를 유지하며 진행한다.

---

# Issue #91 실제 Gemini 평가

`src/test/resources/evaluation/issue-91/cases.json`의 합성 23사례와 사용자 제공 원문 반복 2회를 비교하는 선택 실행 하네스다. `BoundedGeminiEvaluation`에는 JUnit 테스트 애너테이션이 없으며, 기본 `test`와 CI는 유료 평가를 실행하지 않는다. 모델 성공은 실제 호출 결과로만 보고한다.

사례 파일 SHA-256은 `44a02c180a852bdd5cb0757c9e13cc1a8d64c5d32642a6dd256de068cbad9fe1`이다. 기존 scratch 계획에서 세션 승인·자격증명 상태와 관련 없는 작업 메타데이터를 제거했으며 23사례·고정 예상 결과는 그대로다. 예상 결과 지문은 `7ba87b15b853a2bbc04651badc6d3c36da40f8817cfee7fe7ed17674fa2e5eb3`이다. 기대값을 관측 결과에 맞춰 변경하지 않는다.

SDK 1.72.0, `gemini-3.8-flash`, LOW, 실제 adapter prompt/schema, candidateCount 1, maxOutputTokens 32768, timeout 15초와 attempts 1을 사용한다. 기존 `HTTPS_PROXY`를 사용하며 전송 재시도·redirect는 끈다. 공급자 JSON, 정규화 조건, 실제 TimeRangeMatcher·MatchingProcessor·MatchingResultService의 구간·참가자·품질·한국어 설명을 별도로 기록한다. 합성 저장소만 사용하며 DB·운영 API에 접근하지 않는다.

날짜와 요일을 동시에 반환해도 서로 일치하면 adapter처럼 허용한다. 둘 다 없거나 불일치하면 실패다. `getCandidates` 인증은 합성 host의 `findByRoomAndGuestSession`으로 검증하며 이 경로에서 `findById`는 호출되지 않는다.

## 기본 실행: 비용 없는 dry run

저장소 루트에서 다른 Gradle 작업이 끝난 뒤 실행한다. 출력 디렉터리는 저장소 밖의 **아직 존재하지 않는 경로**로 지정한다. 키를 명령 인자나 소스에 넣지 않는다.

```bash
TASK_EVAL_SHA=$(git rev-parse HEAD)
./gradlew --no-daemon -I .codex/evaluation/issue-91.gradle meetmeGeminiEvaluation \
  -PmeetmeLiveEval=false -PmeetmeCostBoundVerified=false \
  -PmeetmeEvalSha="$TASK_EVAL_SHA" \
  -PmeetmeEvalOutput=/tmp/meetme-issue-91-dry-run
```

모든 25사례가 `UNRUN`, 호출 0회, `NOT_EXPLICITLY_ARMED`로 기록된다. 필요하면 기존 신뢰 저장소 경로만 `meetmeEvalTrustStore`로 지정한다. 인증서 검증이나 네트워크 정책을 완화하지 않는다.

`-PmeetmeEvalExtension=true`를 추가하면 `extension.json`의 5개 고정 사례를 포함한다. 이 파일은 실제 응답을 보기 전에 작성한 평일 6–9·7–10, 주말 1–6, 명시적 오전 부모 기간, 독립 장소 사례이며 SHA-256은 `8b1133de496a8489b8fe5bd68b522ba8523dd54cdca57e385a77feeb57206a6d`이다. 순서는 사용자 원문, 원문 반복 2회, 확장 5사례, 나머지 기존 22사례다. 확장 dry run은 30사례가 모두 `UNRUN`이다. 실제 호출 한도는 여전히 25회이므로 전체 30사례를 실행했다고 보고할 수 없다. 확장 기본값은 false이며 기존 계획은 변하지 않는다.

## 저장 응답 재처리: 비용 없는 replay

기존 평가 출력의 `<case-id>.wire.json`만 읽어 현재 adapter와 실제 matcher·summary를 고정 기대값에 비교한다. 원문 JSON 구조·input_ref 검증을 생략하거나 저장된 응답을 수정하지 않는다. 사례별 입력 참조가 일치하는 기존 하네스 응답을 사용한다. 없는 파일은 `UNRUN`, 구조 또는 결과가 맞지 않으면 `FAIL`이다.

```bash
TASK_EVAL_SHA=$(git rev-parse HEAD)
./gradlew --no-daemon -I .codex/evaluation/issue-91.gradle meetmeGeminiEvaluation \
  -PmeetmeEvalExtension=true -PmeetmeReplayDirectory=/tmp/meetme-existing-wires \
  -PmeetmeEvalSha="$TASK_EVAL_SHA" \
  -PmeetmeEvalOutput=/tmp/meetme-issue-91-replay
```

출력은 새 디렉터리여야 하며 기존 wire 디렉터리 안에 만들지 않는다. `mode=REPLAY_POSTPROCESSING`, `provider_calls=0`, 현재 저장소 SHA, 기존 wire SHA, 고정 기대값을 기록한다. 키를 읽거나 SDK를 초기화하기 전에 반환하며 공유 유료 원장도 소비하지 않는다. 이 결과는 저장된 모델 출력에 대한 현재 후처리 증거이고, 새 prompt에 대한 모델 정확성 또는 새로운 A/B 모델 호출 증거를 제공하지 않는다.

## 실제 호출 차단과 비용 경계

실제 호출은 별도의 평가 승인, 사용자가 보안 설정에 직접 등록한 `GEMINI_API_KEY`, `meetmeLiveEval=true`, 검증된 비용 경계에 대한 `meetmeCostBoundVerified=true`가 모두 있어야 한다. 추가로 부모가 지정한 동일 `meetmeEvalApprovalId`와 저장소 밖의 공유 `meetmeEvalLedgerDirectory`를 모든 실행에 전달한다. 승인 ID를 자동 생성하지 않는다. 저장소 자료는 계정·결제·키 설정이나 새로운 실행의 승인을 대신하지 않는다. 두 실행 플래그의 기본값은 false다.

최대 25호출/USD0.30, aggregate input 100k와 thinking 포함 output 40k를 제한한다. 매 호출 전에 남은 input 목표 전체와 configured output 32768을 예약한다. 최초 예약은 명시된 USD0.75/M input·USD3.75/M output 기준 USD0.19788이다. 사용량을 확인한 뒤만 정산하며 실패·누락 사용량은 예약을 유지한 채 재시도 없이 중단한다. 예산 때문에 일부 사례가 미실행일 수 있다.

별도 평가 작업의 인계 보고는 공식 문서로 max_output_tokens가 thinking과 최종 출력의 합산 하드 상한임을 확인했고, 명시된 모델 가격도 재확인했다고 기록한다. 해당 검증의 정확한 출처 URL은 이 인계에 포함되지 않아 추가 링크를 추정하지 않는다. [공식 API 문서](https://ai.google.dev/api/generate-content)의 totalTokenCount에는 prompt·thoughts·candidates가 포함된다.

남은 불확실성은 input 100k 예약이다. 사례 원문의 최대 길이는 237 UTF-8 bytes이며 코드에서 prompt와 schema를 각각 16KiB 이하로 제한하지만, 이 크기 제한이 공급자의 input-token 수를 100k 이하로 보장한다는 tokenizer 또는 vendor countTokens 근거는 확보하지 못했다. 추정치 `4*(UTF8 prompt+schema bytes)+16384`도 입증된 하드 상한이 아니다. 입력 경계 또는 승인된 엄격한 결제 제한을 확보하기 전에는 비용 확인 플래그를 켜지 않는다. 호출 후 사용량 검사는 이미 발생한 요금을 되돌리지 못한다. 이 작업에서는 countTokens를 포함한 공급자 호출을 실행하지 않는다.

공유 디렉터리의 `<approval-id>.lock`을 실제 평가 전체 실행 동안 잠근다. `<approval-id>.json`의 누적 호출·예약 비용·확인된 비용·input/output 사용량을 재사용하고, 호출 전 atomic 파일 교체로 예약을 저장한다. 사용량 검증 후에만 정산한다. 같은 사례 ID·prompt SHA·모델 설정 정책의 기존 호출을 다시 실행하지 않으며 해당 사례는 `UNRUN`으로 남긴다. 중단·실패·미확인 사용량의 `RESERVED_PENDING`은 전체 예약을 유지하고 후속 실제 실행을 차단한다. 잠금 충돌 또는 정책 불일치도 SDK 호출 전에 중단한다.

공유 원장을 삭제하거나 다른 승인 ID·디렉터리로 기존 승인의 예산을 초기화하지 않는다. 도입 전에 발생한 호출은 부모의 단일 평가 담당자가 기존 보고서의 예약 비용까지 포함해 먼저 인계해야 하며, 이 하네스가 과거 외부 호출을 자동 발견하지는 않는다. Dry run과 replay는 공유 유료 원장을 만들거나 소비하지 않는다. 실제 생성은 부모가 지정한 단일 담당자만 수행한다. 보고서·공급자 원문·원장·로그·키는 커밋하지 않는다.

## 모임 탐색 기준 상대 날짜 후보

`search-relative-dates=true`는 `server-evidence-references=true`와 함께 신규 V5 실행에서만 request2/response2를 선택한다. 원문과 실제 보완답은 바꾸지 않고 상대 날짜의 우선 맥락을 모임 탐색 달력으로 바꾼다. 입력별 `allowed_dates`를 전달하고 공유 schema의 날짜 enum과 서버의 입력별 검사를 함께 적용한다. 같은 입력에 명시된 숫자 절대 날짜는 범위 밖이어도 보존하며 다른 참가자의 날짜를 빌리지 않는다. ISO 연월일·한국어 연월일/월일·월/일 literal을 지원하고 연도 생략 시 해당 출처의 기준 연도와 탐색 기간 연도를 후보로 유지한다. 탐색 366일·전체 후보 400개를 넘으면 명시 실패한다. RAW_SCOPE와 저장 조회에도 동결된 새 계약의 가드를 적용하며 기존 response1에는 소급 적용하지 않는다.

이 옵션은 Jev CLEAR의 접수·보완질문 분기를 바꾸지 않는다. 사전 질문 유무·실제 답변 전송·날짜 의미·원문 참조 무결성·최종 추천을 각각 대조하며, 한 날짜 오류를 차단한 사실만으로 전체 분석 통과를 주장하지 않는다. wrapper의 사전 검사는 PRECOMMITTED_ORACLE의 방/참가자 연결과 로컬 합성 host 길이를 검증하며, underlying harness의 별도 TEST_SYNTHETIC 모드를 대신하지 않는다.

한 번 모델 출력을 확인한 독립 평가는 이후 회귀 자료로 취급한다. 그 결과를 보고 프롬프트나 코드를 조정한 뒤 같은 사례의 성공을 미사용 평가 통과로 보고하지 않는다. 후속 일반화 검증에는 보지 않은 독립 사례와 기대를 다음 출력 전에 고정하고, 달력의 주차와 같은 요일의 월 내 순번처럼 다른 의미를 조용히 동일시하지 않는다.

## 월 전체 기준 달력 사실 후보

`calendar-month-facts=true`는 `server-evidence-references=true`, `search-relative-dates=true`와 함께 신규 V5의 request3/response3를 선택한다. 기본 OFF이며 기존 실행의 계약은 바뀌지 않는다. `calendar_facts_version=server-calendar-facts-2`를 요청/응답에 명시하고, 탐색 날짜마다 전체 월 기준 같은 요일의 순번·마지막 출현·월 첫날 요일·월 길이를 제공한다. 주말도 요일에 포함하며 마지막 영업일이라는 의미가 아니다. 달력 주차와 같은 요일의 월 내 순번은 구별하고 새 주차 규약을 임의로 확정하지 않는다.

원문·실제 보완답·근거 catalog·입력별 날짜 권한과 기존 UNKNOWN 처리는 유지한다. 달력 사실은 native 근거나 가용 선언이 아니며, 잘못된 모델 날짜를 서버가 바꾸지 않는다. 같은 최종 요청을 보강할 뿐 Jev CLEAR 뒤 질문이나 모델 호출을 추가하지 않는다. 이전 generation2 요청·읽기와 format11은 그대로 유지한다. 실제 입력과 합성 입력, 이미 관측한 회귀 자료와 새로운 미사용 평가를 구분해 같은 코드로 입력·출력·미반영·확정 결과를 비교한다.

## 근거 선택 번호 생성 계약

`indexed-evidence-selections=true`는 기존 server-evidence-references/search-relative-dates/calendar-month-facts 세 옵션과 함께 신규 V5 request4/response4를 선택한다. 기본 OFF이며 기존 동결 실행을 승격하지 않는다. `evidence_selection_version=server-evidence-selection-1`을 요청/응답에 결속한다. 모델은 요청의 단일 발급 목록 `entries[].selection`에서 0부터 N-1까지의 정수를 선택한다. 모든 조건·날짜·시각·시간어·관계식·장소 근거에 적용하며 긴 native ID는 모델에 제공하지 않는다. 생성 스키마의 integer/minimum/maximum과 서버의 타입·범위·참가자 소유권 검사를 함께 유지한다.

선택 번호는 해당 실행의 동결 snapshot과 요청 전송 결속 아래에서만 해석한다. 번호 자체는 실행 간 재생 방지 토큰이나 의미 정확성의 증거가 아니다. 서버가 기존 native ID·불변 출처/revision/span/hash로 연결하며 format11을 유지한다. 잘못된 번호를 유사 근거로 치환하지 않는다. 원 모델 응답 256KiB, 내부 ID 변환 문서 256KiB, native 근거 확장 2MiB의 기존 경계를 각각 유지하며 작은 번호로 raw 응답이 줄어도 내부 한도를 자동 확대하지 않는다.

## 주간 반복 원조건 생성 계약

`weekly-schedule-rules=true`는 server-evidence-references/search-relative-dates/calendar-month-facts/indexed-evidence-selections와 함께 신규 V5 request5/response5 및 `weekly_rules_version=weekly-schedule-rule-1`을 선택한다. 기본 OFF, 과거 실행의 계약·정책은 유지한다. 반복 요일은 원조건 하나의 WEEKLY atom과 native 근거·본인 anchor 필터·시간 선언으로 전달하고 서버가 모든 해당 날짜를 전개한다. 단일 날짜·월의 마지막/순번 요일을 일반 반복으로 바꾸지 않는다. 다른 참가자 날짜에 맞춘 개별 가능 시간 가지치기와 조건 복제를 하지 않는다.

새 canonical format12의 weeklyApplications에는 원선언·anchor별 원 지역 구간·검색 교집합·resolved/version이 있으며, 기존 base의 UNKNOWN/NON_PRECISE 원조건은 보존한다. 모델이 선택한 atom 근거를 같은 조건의 native bundle에 통합하는 구문 컴파일과 임의 근거 추가를 구분한다. 조회 시 파생 결과를 다시 계산해 대조한다. 실제 평가에서는 참가자별 전체 가용성뿐 아니라 날짜별 전원/부분 인원 후보, 불가·예외·선호 점수·장소 표시·선택/최종 조회를 대조한다. 단일 재시험 통과를 보편 정확성으로 표시하지 않으며 실제 질문·보완답 분기가 달라진 실행의 인과 비교도 제한한다.
