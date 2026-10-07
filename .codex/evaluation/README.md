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
