# V5 개발 인계 — WIP

이 브랜치는 작업을 이어가기 위한 소스 체크포인트다. 정상화 완료, 전체 품질 게이트 통과, 운영 반영 또는 배포 승인을 의미하지 않는다.

## 기준과 범위

- 기준 커밋: `074f6d239ff92eb4917a46b30d0cba6ccf96ae5f`. 기존 원격 `feature/issue-107-schedule-dsl-ast`에 있던 커밋이다.
- 원래 dirty worktree와 별도의 실험 사본에서 V5에 필요한 코드·테스트만 골라 가져왔다. 기존 작업 트리나 main을 변경하지 않았다.
- V2/V3/V4/V5는 출시 버전이 아니라 선택된 분석 계약이다. V5는 `schedule-dsl-response-5` / `schedule-ir-product-6` / `schedule-ast-1` / `ko-time-terms-1`을 사용한다. V5는 이전 DSL·단어장·문맥/모드 타입과 저장 코드에 의존하므로 관련 선행 코드와 V26–V31 마이그레이션도 포함한다.
- 일반 신규 마감의 기본 선택은 LEGACY다. 격리 시험에서는 `analysis_contract: DSL_STRUCTURAL_V5`를 명시한다. 과거 실행 계약을 재작성하거나 운영 기본값을 변경하지 않는다.
- 이 브랜치 push는 기존 CI의 main/develop push 조건에 해당하지 않는다. 배포 workflow는 main의 CI 완료 또는 수동 요청에 한정된다. 이 인계에서는 PR 생성·workflow dispatch·merge·배포를 하지 않는다.

로컬 인계 체크아웃에서 compile·assemble·정식 `ktlintCheck`·공통12/12·합성JDBC6/6을 통과했다. 이 문서와 검증 JSON은 게시 전 검사 시점의 기록이다. 실제 게시 SHA는 이 브랜치의 원격 ref에서 확인한다. 실모델 전송·운영 반영은 여전히 미실행이다.

## 주요 코드

- `src/main/.../coordination/application/service/SelectedScheduleDslBatchProcessor.kt`: 실제 제품의 최종 분석 조율. Gemini 우선 및 기존 조건부 Luna 정책을 보존한다.
- `src/main/.../coordination/adapter/output/integration/ScheduleDsl*`, `StrictScheduleDsl*`: 프로토콜·시간 표현·원문 근거·역할·참조 검증.
- `src/main/.../submission/adapter/output/persistence/JdbcScheduleDsl*`: 기존 IR 저장·복원.
- `src/main/.../coordination/domain/dsl/ScheduleDslInputPolicy.kt`: 선호-only는 가능 범위로 사용하고 추가 점수는 0. 명시 가능과 선호가 함께 있을 때만 가능 범위 안에서 선호 점수를 준다. 불가가 우선한다.
- `src/test/.../experiment/ExperimentalLiveHttpEvaluationTest.kt`: 기존 RANDOM_PORT HTTP/application-service 및 PostgreSQL/Redis 시험 도구. 기본적으로 실제 provider 실행은 환경 guard로 닫혀 있다. 새 운영 API가 아니다.
- `src/test/.../coordination/adapter/output/integration/C5Evaluation*`, `C5DirectLocatorProducer.java`: opt-in direct-locator parser, 중복 입력 schema 제거, 생성 계약 후보.
- `src/test/.../experiment/C5CollectedResponseReplay.kt`: 이미 있는 응답 바이트를 받아 hash/body 결속 → 기존 decoder → strict parser → 실제 JDBC 저장/새 연결 복원 → 계산을 수행하는 **무전송** 연결. send/admission/자격 증명 API가 없다.

## 확인된 결과와 미검증

- 공통 계약 무료 검사 12/12: 두 API 봉투 안의 공통 입력·instructions·native schema 일치, 비순환 생성 schema, 기존 reader 깊이 8/방문 128 제한 보존 등을 확인했다. 방문 수 제한은 여전히 reader가 집행한다.
- 로컬 처리 무료 검사 6/6: 비개인 합성 자료로 양 provider 봉투가 같은 typed IR이 되는지, 실제 PostgreSQL 저장·새 JDBC 연결 복원·계산이 일치하는지, hash/설정/JSON/stale revision 거부 및 전송 미호출을 확인했다.
- 위 무료 결과는 API 수용, 실제 모델 의미 정확성, 실전 지연 또는 전체 E2E 통과를 증명하지 않는다.
- 마지막 실제 최종 Luna 단발은 HTTP 200 이후 한쪽 `BOUNDS`와 `PRECISE_INTERVAL` 조합 때문에 기존 strict parser에서 `INVALID_RESPONSE`가 됐다. IR/추천 생성은 완료되지 않았다. 원응답을 수정하거나 reader를 완화하지 않았다.
- 이후 생성 schema와 reader의 항상 금지되는 형식 조합을 맞춘 후보를 무료 검증했다. 최신 공통 schema는 실제 provider에 보내지 않았으므로 공급자 수용·모델 정확성·지연은 미검증이다.
- 남은 의미 문제: 알려진 날짜/미상 경계의 보존, 근사 시각의 추정 표시, 선언한 조건의 올바른 root 연결, 실제 가용/선호의 구분. 형식 제약만으로 해결됐다고 간주하지 않는다.
- known-date instructions와 selected-choice context는 별도 opt-in 후보다. 공통 모델 비교에서는 함께 켜지 않는다. 자유 입력에 선택지 의미를 사후 추가하지 않는다.
- 과거 전체 gate의 한 스냅샷은 3,950건 중 3,917 pass / 16 fail / 17 skip, 약 63분이었다. 이것은 이 인계 커밋의 전체 gate 결과가 아니다. CI 시간 조건·정확 commit CI·ARM64·운영 검증은 미완료다.

## 무전송 로컬 검증

JDK 17과 Docker가 필요하다. PostgreSQL 이미지는 기존 표준 Testcontainers fixture를 사용하며 실제 운영 DB에는 쓰지 않는다. 새 collected-response 검사는 image pull을 금지하므로 `postgres:18-alpine`과 `testcontainers/ryuk:0.14.0`이 로컬 캐시에 있어야 한다. 새 환경에서 이미지가 없다면 먼저 해당 환경의 허용된 패키지 준비 절차로 확보한다. 아래 명령은 저장소 루트에서 실행한다. 실제 API 키와 LIVE opt-in은 제공하지 않는다.

```bash
env -u MEETME_LIVE_EVAL -u MEETME_NATIVE_SCHEMA_COMPARE \
  -u OPENAI_API_KEY -u GEMINI_API_KEY -u TYPESAFE_API_KEY \
  ./gradlew --no-daemon c5HttpCandidateTest \
  --tests '*C5CandidateRuntimeBindingTest.HR07*' \
  --tests '*C5CandidateRuntimeBindingTest.HR11*' \
  --tests '*C5CandidateRuntimeBindingTest.HR12*' \
  --tests '*C5CandidateRuntimeBindingTest.HR14*' \
  --tests '*C5CandidateRuntimeBindingTest.HR19*' \
  --tests '*C5CandidateRuntimeBindingTest.HR20*' \
  --tests '*C5CandidateRuntimeBindingTest.HR21*' \
  --tests '*C5CandidateRuntimeBindingTest.HR22*' \
  --tests '*C5CandidateRuntimeBindingTest.HR23*'

env -u MEETME_LIVE_EVAL -u MEETME_NATIVE_SCHEMA_COMPARE \
  -u OPENAI_API_KEY -u GEMINI_API_KEY -u TYPESAFE_API_KEY \
  ./gradlew --no-daemon test --tests '*C5CollectedResponseReplayPostgresTest'
```

공유하는 `frozen-public-producer-baseline.json`에는 공통 schema와 prompt만 있다. 참여자 입력·sources·보완답·모델 응답은 없다. 절대 경로를 읽던 검사는 이 공개 fixture를 사용한다. build origin 검증은 현재 프로젝트의 실제 build/classes 아래만 허용한다. 기존 후보와의 동작 차이는 이 두 이식성 변경뿐이다. 새 체크아웃의 정식 lint에서 발견한 줄 길이·들여쓰기 각 1건도 의미 변경 없이 정리했다.

## 다음 최소 작업

1. `AGENTS.md`와 관련 규칙을 읽고 무전송 검증을 재현한다. guard·reader·시간 제한·원문을 약화하지 않는다.
2. 새 실제 모델 비교 전 사용자의 현재 데이터·수신자·호출 상한과 전송 검토를 확인한다. 저장소나 과거 예산 잔액을 실행 허가로 삼지 않는다.
3. 기존 `FinalLunaSixtyProbe`는 OpenAI용이며 Gemini 제품 경로는 15초다. 격리된 양 모델 40초/모델별 전체 60초·retry0/fallback0 비교의 실제 송신과 `C5CollectedResponseReplay` 사이 caller·원장·trace 결속은 미구현이다. 새 프레임워크를 만들지 않고 이 연결만 완성한다. 제품 15/40/60초와 기존 4+1 체인은 바꾸지 않는다.
4. 동일 accepted 입력·instructions·native schema·출력 상한을 고정하고 모델별 1회씩 API 수용·strict parse·저장/계산·의미·시간을 별도 판정한다. LOW/low, temperature 생략 시 기본값, tokenizer가 동등하다고 단정하지 않는다.
5. 입력→실행/미실행 단계→사전 기대→실제/차이→확정 원인/가설→다음 계획을 기록한다. 실패 후 같은 요청을 근거 없이 반복하지 않는다.

## 제외 자료와 재현 한계

- 실제 가족·타인의 원문/위치·보완답·provider request/response·암호화 reasoning·원장·개인 자료가 있는 로그/DB dump는 포함하지 않는다. 로컬 원본은 이동·변경·삭제하지 않았다.
- API 키·토큰·환경 파일·비밀키·build/cache/JAR·원 작업 일지 전문·무관한 미커밋 CI/배포 변경은 포함하지 않는다.
- 비공개 replay 파일을 읽는 조건부 테스트를 공개 합성 fixture로 바꿔 실모델 성공처럼 처리하지 않는다. 비공개 자료가 필요한 replay는 이 브랜치만으로 재현할 수 없다.
- 과거 실제 전체 흐름을 재연하려면 사용자에게 로컬 입력과 해당 전송 동의를 받아야 한다. 새 대화나 Cloud 환경에 기존 비밀/개인 자료가 자동으로 따라온다고 가정하지 않는다.
- 공개 검사 요약은 `docs/verification/v5-handoff-free.json`에 기록한다. 전체 원 로그와 사적 증거는 Git 밖에 보관한다.
