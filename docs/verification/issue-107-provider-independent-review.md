# Issue #107 공급자·매칭 독립 최종 검토

- 검토일: 2026-10-17 UTC.
- 역할 분리: 최초 `ClarificationProviderContractTest`·테스트 명세·fixture 작성 시 구현은 읽지 않았다. 부모가 최종 독립 검토를 요청한 뒤 공통 provider contract, clarification domain, 구조화 저장, MatchingProcessor/projector, HOST 미반영 조회, clarification service를 읽었다. 프로덕션 코드를 수정하지 않았다.
- 근거: 사용자 #107 위임, `docs/ISSUE_107_CONTRACT.md`, `docs/ISSUE_107_TEST_DESIGN.md`, PRD FR-007A/008B/008D/008E/009 및 ADR-045/046/048/049. 이 검토는 공급자/도메인 경계이며 HTTP·웹·마이그레이션 전체 독립 검토를 대체하지 않는다.

## 결과

공통 provider 응답에 nullable clarification을 추가하는 방향과 기존 배치 구조화 호출을 재사용하는 정보 흐름을 확인했다. 잘못된 proposal을 `INVALID_RESPONSE`로 거부하고, 모호한 참여자에 대해 가용 시간을 빈 목록으로 만드는 구현은 계약과 일치한다. 새 `AMBIGUOUS_INPUT`을 안전 제외 대상에서 빼도 테스트가 잡는지 확인할 회귀 테스트 12개를 추가했다. 해당 mutant의 6개 행동 실패와 provider 중복 선택지 mutant의 1개 행동 실패를 원본 로그에서 확인했다. 서버 결함 주입은 6/6 탐지되었고 기록된 source/test 지문 9개가 현재 복원된 파일과 일치한다. 실제 보고 표현용8개를 포함한 최종 서버 wrapper gate와 mandatory TDD guard는 모두 exit0이다. XML을 직접 재집계해727건 중723 PASS·기존4 SKIP·실패/오류0을 확인했으며 Clarification 관련64개도 모두 PASS다. 추가 사례 이전719건/715 PASS는 이전 성공 기록으로 구분한다. 어느 검증 성공도 #109 production 통합 차단을 해소하지 않는다.

독립 검토 범위에서 확정된 신규 구현 결함은 발견하지 않았다. **#109 공통 소유권 resolver의 production adapter 통합과 실제 애플리케이션 기동은 명시적 BLOCKED다.** 최신 `ClarificationService`는 자체 guest resolver를 제거하고 조회·답변 모두 `ClarificationOwnerPort.requireOwner`에 위임한다. `src/main`에는 이 port의 실제 구현이 없으며, `SyntheticClarificationOwnershipAdapter`는 `src/test`에만 있다. 따라서 테스트용 adapter나 mock으로 성공한 격리 검증을 production 기동·계정 인증 통합 완료로 보고할 수 없다. #109의 공통 account/guest resolver를 소비하는 실제 adapter와 wiring이 들어와야 차단을 해소할 수 있다. 이 검토에서는 인증 구현을 복제하지 않았다.

## 확인한 공급자 정보 흐름

1. Gemini/Luna가 같은 공통 prompt/schema/response validator를 사용한다. live v3 schema는 clarification required+nullable이고 기존 v2/v3 reader의 absent는 additive 호환으로 null이다. OpenAI strict의 기존 rejection_code 필수 검증은 유지한다.
2. proposal은 `AMBIGUOUS_TIME_CONSTRAINT`/`AMBIGUOUS_INPUT`에만 허용하고 raw conditions가 하나라도 있으면 fail closed한다. 원문 substring excerpt, 질문·두 전체 대체 원문의 Unicode 길이, trim, 중복·원문동일, 필드와 타입을 검증한다.
3. prompt는 명확한 입력에 null, 후보에 영향을 주는 모호성에만 질문 하나, 다른 조건·불가·선호 보존, immutable reference_date로 상대 날짜를 절대 날짜화하도록 요구한다. 두 안전한 전체 해석을 만들 수 없으면 null을 요구한다.
4. `JdbcStructuredSubmissionRepository.replaceForBatch`는 구조화 결과 저장과 함께 별도 clarification 저장소에 source version별 proposal을 보존한다. 일반 구조화 결과 읽기는 기존 조건·사유를 복원하며 질문의 영속 기준은 전용 clarification 저장소다. 질문 생성만을 위한 새로운 공급자 호출은 이 흐름에 없다.
5. `MatchingProcessor`는 `AMBIGUOUS_TIME_CONSTRAINT`, `AMBIGUOUS_INPUT` 및 기존 미지원 결합 사유에 대해 빈 가용 시간을 생성한다. 원래 참여자는 전체 인원에 남고, 다른 유효한 참여자 2명 이상만 부분 후보를 만들 수 있다. 모호한 선호가 득점하거나 참석자를 추가하지 않는다.
6. `MatchingResultService.getUnappliedInputs`는 타인의 두 모호성 사유를 HOST에게도 원문과 함께 반환하지 않는다. aggregate 부분 결과 인원과 개인정보는 구분한다. HTTP 계층의 실제 권한 회귀는 별도 테스트 담당이다.
7. 본인 소유권은 `ClarificationOwnerPort.requireOwner(room.id, rawCredential)`를 통해 공통 resolver에 위임한다. resolver의 401/403 거부 후 feature 내부 guest fallback을 수행하지 않는다. 이 위임 경계의 테스트는 실제 Bearer/account parsing 또는 production wiring을 검증하는 테스트가 아니다.
8. 기존 worker·60초/최대4+1 및 수정 분석 3회 정책을 이 변경에서 새로 구현하지 않는다. 해당 파일은 다른 작업의 통합 경계다. 기존 비용·기한 회귀 gate를 반드시 함께 실행해야 한다.

## 추가한 독립 안전 제외 테스트

파일: `src/test/kotlin/com/meetme/server/coordination/application/service/ClarificationMatchingSafetyTest.kt`.

각 항목을 `AMBIGUOUS_TIME_CONSTRAINT`와 `AMBIGUOUS_INPUT` 두 사유로 실행한다(6개 parameterized methods, 총 12 cases).

- 유효 1명과 모호 1명은 모임을 만들 수 없다. COMPLETED/PARTIAL이며 전체2·반영1·미반영1을 보존한다.
- 유효 2명과 모호 1명은 유효 두 사람의 정확한 10~13시 교집합만 생성하고 totalParticipants=3을 보존한다.
- 전원 모호하면 전체 날짜 가능으로 확대하지 않고 후보0/PARTIAL이다.
- 저장된 가용·불가·선호 조각이 남아 있어도 authoritative 모호 사유가 있으면 그 사람은 참석할 수 없다. 저장 증거 자체는 지우지 않는다.
- 선호만 남은 모호 참여자는 neutral all-day base를 얻을 수 없다.
- 실제 recommendation projector를 거친 공개 결과에도 모호 참여자가 없고 해당 선호의 preferenceCount는0이다. 다른 두 사람의 넓은 유효 대안은 남는다.

테스트는 기존 합성 저장소 fixture를 재사용하지만 실제 MatchingProcessor와 deterministic matcher/projector를 실행한다. repository fake/mock 통과는 PostgreSQL 경합 검증을 대신하지 않는다. 기존 HTTP/PostgreSQL 별도 gate를 유지한다.

고정 시 최초 SHA-256: `e95dda1be0a60d3e84efebecf3816d2d5c79470b1caa71dee135a2f1651f716f`. formatter 후 현재 지문은 `1919f26059656da31845b1452500b1a7a261ae9fce7d8f47052da23d9e008cfe`이며 `.tdd/verification/issue-107.json`의 기록과 일치한다. 기대값 변경으로 GREEN을 만들지 않는다.

## 검증 상태와 결함 탐지 기준

- `git diff --check`: 신규 파일 작성 시 통과.
- provider 기존 15개: 부모 세션이 초기 GREEN을 전달했다. 최초 RED는 `.tdd/red/issue-107-provider.json`과 로컬 원본 로그에 기록되어 있다.
- 소유권 위임 3개: 부모 세션이 `ClarificationOwnerDelegationPostgresTest` GREEN을 전달했다. cookie 없는 본인 허용, 공통 port 401/403 거부 시 fallback 금지·비공개·쓰기0을 검증한다. mock port를 사용하므로 실제 계정 resolver와 production adapter의 통합 완료 증거는 아니다.
- 신규 안전 제외 12개: 부모의 targeted baseline 로그 `/tmp/issue107-matching-green.log`에서 BUILD SUCCESSFUL을 확인했다. 최초 단독 Gradle 시도는 daemon 준비 단계에서 중단(exit130)했으므로 그 중단 기록은 성공/RED 근거가 아니다.
- 결함 주입: `.tdd/verification/issue-107.json`에 6개 fault의 정확한 실행 명령·탐지 테스트·source/test 지문이 있다. `/tmp/issue107-server-fault-status.log`는 각 fault 탐지 및 모든 원본 바이트 복원, COMPLETE6을 기록한다. 이 검토에서 기록된 source5/test4의 SHA-256을 직접 다시 계산해 9/9 일치를 확인했다. JSON schema 검증 PASS는 부모가 별도로 보고했다.
- `generic-ambiguity-safe-exclusion`: `.codex/tdd-evidence/issue-107-fault-generic-ambiguity-safe-exclusion.log`에서 총12개 중 `AMBIGUOUS_INPUT`의 정확히6개가 AssertionFailedError로 실패한 것을 확인했다. 컴파일·환경 오류가 아닌 잘못된 참석/후보/선호 행동을 탐지했다.
- `distinct-provider-options`: `.codex/tdd-evidence/issue-107-fault-distinct-provider-options.log`에서 중복 전체 대체 원문을 거부해야 하는 테스트1개가 AssertionFailedError로 실패한 것을 확인했다.
- 나머지 HOST 비공개·답변 트랜잭션 롤백·replay payload fence·답변 lineage 반복 억제도 같은 추적 JSON과 상태 로그에 각각 행동 실패1개로 DETECTED가 기록되어 있다. 해당 HTTP 경계의 상세 독립 판정은 서버 HTTP reviewer 문서와 연결한다.
- 최종 전체 wrapper gate: `.codex/tdd-evidence/issue-107-final-gate.json`의 `sh gradlew --no-daemon ktlintCheck assemble test` exit0·513.22초와 `/tmp/issue107-server-final-gate.log`의 BUILD SUCCESSFUL을 확인했다. `python .codex/hooks/tdd_guard.py`도 exit0·10.443초다. guard는 up-to-date 작업을 재사용하므로 테스트727건을 새로 실행한 두 번째 표본으로 중복 집계하지 않는다.
- 최종 `build/test-results/test/TEST-*.xml`을 직접 재집계해 총727·실패0·오류0·기존 skip4·PASS723을 확인했다. Clarification 관련 합계64개(HTTP16, lifecycle10, 소유권위임3, provider15, matching12, reported-time8)가 모두 PASS다. `CandidateScenarioMatrixTest`23개도 모두 PASS다. 실제 사례 보강 이전719건/715 PASS·500.747초 wrapper·10.906초 guard는 피드백 전 검증 이력이며 최신 집계와 합산하지 않는다.
- 기존 `two-ambiguous-partial`은 모호1명+명확1명에 전원후보를 기대하던 이전 oracle을 최신 안전 제외·최소참석2 계약에 따라 후보0/PARTIAL로 독립 정정했다. 모호성 trace도 빈 가용 시간으로 표시한다. 부모 세션은 이 정정과 별도 migration 기대값3개 정정 후 전체 gate를 다시 실행했으며 최초 gate 실패는 최종 성공으로 덮어쓰지 않고 별도 근거로 보존한다.
- 최종 서버 검증 JSON은 plain/boot JAR 두 개 모두 test-only ownership adapter 미포함을 기록한다. 두 JAR의 SHA-256도 직접 계산해 기록과 일치하며 실제 사례 보강 이전 빌드와 동일함을 확인했다. main 실제 `ClarificationOwnerPort` adapter가 없어 production startup/account 통합 BLOCKED는 유지된다. 이 재검토에서는 Gradle이나 production·테스트 변경을 실행하지 않았다. 검증 성공을 제품 완료로 선언하지 않는다.

## 최신 실제 표현의 독립 대조 검토

사용자의 정확한 제보는 `7-8시쯤`이며 기존 oracle에 없던 하이픈·축약·근사 표현 조합이다. `docs/ISSUE_107_TEST_DESIGN.md` 대조표와 `docs/verification/issue-107-reported-time-comparison.md`를 현재44개 fixture 및 새8개 회귀와 대조했다. 기존25개 기대값과 기존15개 provider/12개 matching 테스트는 변경하지 않았다.

원격 main `91eb2505ddf6b4db549a5bc2b8b07f11e514d0b0`의 provider 계약 SHA-256 `24c165bfbf25057c29c89be29fad6a90df504024fe13255360efc32d0a46ebf8` 및 모호 경계 발명 금지·명시 AM/PM 우선·미해소 거부 지시를 바이트와 행 번호로 재확인했다. 기존 소스에도 이 지시가 있었다는 사실을 사용자 오류 원인이 밝혀졌다는 의미로 확장하지 않는다. 실제 운영 image SHA와 당시 공급자 응답·로그는 미확인이고 과거 오류 원인은 미확정이다.

새 `ClarificationReportedTimeRegressionTest`는 원문 `7-8시쯤`에 합성 모호성 응답과 proposal을 공급하고, 명확 오전/저녁/24시간제·source 기준 오늘/내일/명시 월요일·자정·불가·잘못된 hard 시각 순서의 응답 계약을 검사한다. `쯤`의 수치 허용오차나 역전 시각의 자동 swap/밤샘을 발명하지 않는다. 안전한 A/B가 없으면 null 질문으로 안전 제외할 수 있다.

`/tmp/issue107-reported-time-green.log`에서 BUILD SUCCESSFUL22초를, 해당 JUnit XML에서8건/실패0/오류0/skip0을 직접 확인했다. formatter 후 SHA-256 `cab9f8d38d627418eb32aa6d4c2d0ba7192ce9cdc737c6ea8cc6a81651806399`가 최초 고정 지문과 같다. 기존 서버 FI6개의 source/test hash는 이 보강으로 바뀌지 않았다.

이8개를 포함한 전체727개 gate는 최종 XML727/실패0/오류0/기존skip4로 완료되었다. 기존719개 성공과 targeted8개 성공을 합쳐 추정한 결과가 아니라 별도 전체 실행 결과를 확인한 것이다. 기존 FI6개의 source/test9개 지문도 다시 계산해 유지됨을 확인했다. 이 재검토는 docs만 수정하며 production·테스트·Gradle을 실행하지 않았다.

## 실제 의미 정확성과 검증 한계

`docs/fixtures/issue-107-ambiguities.json`은 기존25개에 실제 보고 표현 관련 T01~T19를 추가한44개 분류 fixture와 기존7개 답변 시나리오를 담은 독립 합성 oracle이다. 오전/오후·명확 문맥·source 기준 상대 날짜·자정·불가/선호 보존의 기대 의미를 명시한다. parser 테스트는 synthetic provider response를 읽는 코드와 prompt에 필요한 지시가 존재함을 검증한다. fixture의 입력을 실제 모델에 보내 분류 정확성을 평가한 결과가 아니다.

따라서 **명확한 입력에서 실제 모델이 항상 질문을 만들지 않는지, 질문 A/B가 원문의 모든 의미를 보존하는지, source 기준 상대 날짜를 매번 정확히 절대 날짜화하는지는 실모델 검증되지 않았다.** 서버의 구조 검증은 길이·형태·substring·극단적인 계약 위반을 막지만, 두 한국어 문장의 의미 동등성이나 모델이 다른 참여자의 문맥을 잘못 섞는 일을 증명하지 못한다. 기존 prompt/schema 단위 테스트를 그 의미 정확성의 증거로 과장하지 않는다.

검토와 테스트 작성 중 실모델·운영·비용 발생 호출은 하지 않았다. 유료 평가, 운영 자격 증명, 공급자 실지연/비용, 운영 migration·배포·병합은 이 작업에서 수행하지 않았다. 후속 사용자 승인은 독립 범위의 commit·push·Draft PR·CI 게시를 허용하지만 해당 게시를 #109 통합 또는 제품 완료로 보고할 근거가 되지 않는다. 이 검토자는 문서 동기화만 수행했다. 격리 서버·브라우저 검증의 완료와 이 한계 및 #109 통합 차단을 최종 보고에서 분리해야 한다.
