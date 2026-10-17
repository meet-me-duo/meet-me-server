# Issue #110 내부 분석 계측 — 로컬 검증

기준은 develop `3232128bb1d0886c987e378a787633bd2aee6341`이며, 작업 브랜치는
`feature/110-analysis-observation-summary`다. 기존 작업 저장소의 변경과 별도인 작업 복사본을 사용한다.

## 이번 변경

기존 계측의 마지막 성공 응답 비용을 전체 배치 비용처럼 전달하는 동작을 보완한다.
승인된 시도와 파서 dispatch를 구분하고, provider별 입력·출력 token 및 추정 비용의
KNOWN/UNKNOWN 관찰 수와 확인된 값만 기록한다. Gemini 배치 전체 비용은 dispatch가
하나 이상이고 모든 dispatch의 Gemini 비용이 확인된 경우에만 합산한다.
늦게 도착해 사용하지 못한 응답도 제공된 usage를 보존한다.

파서 dispatch는 실제 HTTP·공급자 수신·청구 건수가 아니다. 내부 지표는 비영속이며
미처리 예외나 프로세스 중단으로 종결 관찰이 누락될 수 있다. 개인정보와 원문을
새 지표의 필드·태그로 전달하지 않는다.

`outcome`은 `GeminiBatchProcessor`의 로컬 구조화 처리 경로의 종결을 뜻한다.
`COMPLETED`는 후보 게시 성공이나 방의 READY/COMPLETE 상태를 보증하지 않는다.
`ANALYSIS_DELAYED`도 현재 방 상태를 재조회한 값이 아니다. 최종 결과는 별도 matching
지표와 영속 상태로 확인하며, `elapsed`에는 동기 후속 matching 처리 시간도 포함된다.

## 검증 경계

- 테스트 설계자와 구현자는 분리했다. 새 테스트는 합의된 공개 관찰 계약과 #110을 기준으로 작성했다.
- RED: 새 15개가 컴파일돼 실행됐고, 관찰 호출 누락 8개와 미구현 어댑터 6개가 예상한 지점에서 실패했다. 필드 제한 1개는 통과했다.
- GREEN: 새 15개와 기존 Gemini 처리 3개, 3 suite/18 test/실패 0/오류 0/스킵 0.
- 독립 정적 리뷰: 승인/dispatch 시점, 늦은 usage, unknown 비용, provider 분리와 제한된 태그를 확인했으며 차단 결함은 발견하지 못했다.
- 의도적 결함 3종을 각각 정상 컴파일 뒤 assertion 실패로 탐지했다: unknown token을 0으로 기록(1개 실패), 승인 수를 dispatch 수로 사용(2개 실패), 마지막 응답 비용을 전체 비용으로 사용(2개 실패). 각 변이 실행의 15개 테스트에서 오류·스킵은 0이었다. 매번 제품 원본 바이트와 SHA-256을 복원했고 테스트 지문도 유지했다. 독립 리뷰에서 원시 XML·변이/복원 지문을 대조했다.
- 첫 전체 품질 실행 `verify`는 2026-10-17 09:13:20 UTC에 exit 1로 끝났다. `assemble` 이후 테스트 실행기의 로컬 연결이 끊겼고(`Connection reset by peer`, executor exit 1073807364), 부분 XML은 18 suite/77 test/실패 0/오류 0/스킵 1이었다. 전체 통과로 집계하지 않는다. 로그·종료 기록과 부분 XML을 별도 보존했다.
- 재개 전 Java·Python 프로세스가 없고 이전 종료 JSON이 있음을 확인했다. 기존 Docker Desktop 27.5.1을 시작하고 자격 증명 없는 실행기로 `verify-resume` 전체 품질 게이트를 실행했다. 공유 엔진을 재시작·종료하거나 전체 컨테이너를 정리하지 않았다. 통합 테스트는 Testcontainers 전용 PostgreSQL 18을 사용했다.
- 최종 `--offline --no-daemon ktlintCheck assemble test`: 2026-10-17 14:05:45–14:14:26 UTC, Gradle exit 0/`BUILD SUCCESSFUL in 8m 39s`. `assemble`과 기존 컴파일 산출물은 up-to-date 재사용이며 `test`는 새로 실행됐다. 최종 XML은 113 suite/678 test/674 PASS/기존 skip 4/실패 0/오류 0이다. 앞선 부분 실행과 대상 GREEN을 이 수에 더하지 않는다.
- Gradle 종료 JSON 저장 후 보조 실행기가 원본 로그를 콘솔에 출력하는 단계에서 CP949 `UnicodeEncodeError`로 끝났다. 이는 Gradle exit 0 이후의 출력 오류다. 원본 로그의 성공 종료, 종료 JSON, 완료 XML 113개를 별도로 수집·해시해 확인했다. 로그에는 테스트 컨테이너 종료 뒤 로컬 연결 거부 경고도 남아 있으며 테스트 assertion 실패·오류는 0이다. 제품·테스트 코드를 수정하거나 전체 검증을 중복 실행하지 않았다.

새 테스트 지문과 RED 명령·실패 사례는
[`../../.tdd/red/issue-110-analysis-observation.json`](../../.tdd/red/issue-110-analysis-observation.json)에 있다.
원본 로그는 Git에서 제외된 `.codex/tdd-evidence/issue-110-analysis-observation.log`에 둔다.
로컬 실행은 Java 17·Gradle 9.7.1 `--offline --no-daemon`을 사용했다.
별도 실행기는 허용된 시스템 경로 환경만 전달하며 AWS·공급자 자격 증명과 실제 AI opt-in을 전달하지 않는다.

기존 스킵 4개는 다음 opt-in 검사다. 조건이나 테스트 파일은 변경하지 않았다.

| 클래스 | 실행 조건 |
| --- | --- |
| `CandidateScenarioLiveProbeTest` | `MEETME_CANDIDATE_LIVE_PROBE=true` 및 실제 키. 이번에는 opt-in false이며 키를 전달하지 않았다. |
| `CorrectionRoundRequestPresencePostgresTest`의 debug capture 1개 | `MEETME_ISSUE92_CAPTURE_OPENAPI=true` |
| `Issue92OpenApiExportIntegrationTest` | `MEETME_ISSUE92_EXPORT_OPENAPI=true` |
| `Issue83OpenApiExportIntegrationTest` | `MEETME_ISSUE83_EXPORT_OPENAPI=true` |

로컬 원본 근거는 작업 복사본 상위 `verification/`에 있다.
`verify.{json,log}`·`verify-results/`는 중단 실행이며,
`verify-resume.{json,log}`·`verify-resume-test-summary.json`·`verify-resume-results/`가 최종 실행이다.
`issue-110-final-evidence.json`에 제품 4파일·테스트 2파일, 전체 XML, 로그 및 요약의 SHA-256,
기존 RED 테스트 지문 유지, 변이 3종 복원, `SubmissionService.kt`의 기준 HEAD 동일성을 기록했다.

## 코드 변경 경로

제품 코드는 다음 4개 파일뿐이며 `SubmissionService.kt`는 변경하지 않았다.

- `src/main/kotlin/com/meetme/server/coordination/application/service/GeminiBatchProcessor.kt`
- `src/main/kotlin/com/meetme/server/shared/adapter/output/observability/MicrometerApplicationMetrics.kt`
- `src/main/kotlin/com/meetme/server/shared/application/port/output/ApplicationMetricsPort.kt`
- `src/main/kotlin/com/meetme/server/shared/application/port/output/AnalysisExecutionObservation.kt` (신규)

신규 테스트 2개는 다음과 같다.

- `src/test/kotlin/com/meetme/server/coordination/application/service/GeminiBatchProcessorObservationTest.kt`
- `src/test/kotlin/com/meetme/server/shared/adapter/output/observability/MicrometerAnalysisExecutionMetricsTest.kt`

## 남은 #110 범위

이 아래 기록은 14:14 UTC 내부 계측 단위 종료 당시의 역사다. 이후 실제 #109
계정 구현 위에서 운영 조회·인가·감사(V14)·transport 관측(V15) 및 웹 화면을
구현했다. 최신 검증과 남은 게시 상태는
[`../ISSUE_110_HANDOFF.md`](../ISSUE_110_HANDOFF.md) 및
`issue-110-integration-final.json`을 기준으로 확인한다. 과거 674 PASS를 후속
전체 게이트나 실제 계정 통합의 통과 수로 재사용하지 않는다.

이번 단위는 #110 전체 완료가 아니다. 영속 실행/시도 이력과 방 설정을 연결하는 조회,
운영자 인가와 접근 감사, 최소 표시 필드와 보관기간, API·웹 화면은 남아 있다.
HOST·회원·게스트의 권한, DB 스키마, 공급자 timeout·재시도·단가·매칭 동작은 변경하지 않았다.
실제 AI·운영 방 생성·운영 데이터 쓰기·외부 telemetry 전송·보안 설정·병합·배포는 실행하지 않았다.
