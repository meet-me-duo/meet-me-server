# #95 독립 리뷰

리뷰어: `/root/independent_review`. 기준 HEAD: `bf4edff51dea5f1f90a8ac627edf05ec17b6d56d` 위의 구현 중인 작업 트리. 확인일: 2026-10-07 UTC.

AGENTS, 필수 rules, project-architecture skill, PRD/Architecture/ADR, ISSUE_95_CONTRACT 및 관련 프로덕션·독립 테스트를 읽었다. 모델 호출과 Gradle 실행은 하지 않았고 프로덕션/테스트 파일을 수정하지 않았다. 초기 검토와 이후 재리뷰를 시간순으로 보존했다. 마지막 소스 판정을 최종 리뷰 결과로 따른다.

## 수정 요청

1. **P2 — 실제 선택 시각 summary가 초·소수초를 버린다.** `MatchingResultService.selectedResult`는 자유 선택한 실제 창을 `renderWindow`로 전달하고 기존 `CandidateSummaryRenderer.LocalTime.hhmm()`는 분까지 출력한다. 예를 들어 18:00:01~18:00:30 또는 18:00:00.000001~18:00:00.000002는 허용된 양수 길이 선택이지만 summary는 18:00~18:00이다. 새 DTO는 실제 경계를 보존한다고 문서화했고 마이크로초까지 허용하며 최소 소요시간은 없다. 초·소수초가 있는 선택에서는 필요한 정밀도를 유지하고, 독립 HTTP 회귀 사례를 추가해야 한다. 참조: `MatchingResultService.kt:346`, `CandidateSummaryRenderer.kt`의 `hhmm()`.

2. **P2 — 기준 문서에 새 제품·영속성 계약이 반영되지 않았다.** 현재 계약 파일 외 PRD FR-011/012B·Rule 3 및 성공 지표는 종류별 한 장, candidate-only 멱등, 확정 뒤 사용자가 실제 날짜를 공지하는 흐름을 유지한다. Architecture와 ADR-027도 final confirmation을 후보 참조로 설명한다. typed selection과 protocol 구분은 데이터 호환성에 장기 영향을 주므로 기존 ADR-027 등의 대체 범위를 명시한 새 ADR 및 PRD/Architecture 갱신이 필요하다. D2 명시 선호 집계는 승인 전 TBD를 유지해야 한다.

3. **P3 — variant meeting_mode OpenAPI가 방 모드 EITHER까지 허용할 가능성.** `RecommendationVariantResponse.meetingMode`는 공유 enum `MeetingMode`를 사용하지만 실제 variant/DB는 IN_PERSON과 REMOTE만 허용한다. native OpenAPI의 해당 enum을 확인하여 이 두 값으로 좁히고, OpenAPI 테스트에서 허용 값을 검증해야 한다. 참조: `RecommendationDtos.kt:16`.

## 성능 검토 사항

`JdbcRecommendationRepository.find`는 분석의 모든 option/variant/participant 행을 로드한다. primary·대안 limit 1 조회·확정·확정 result에도 이 경로가 사용된다. 승인된 전체 N/N-1/N-2 보존 규칙에서 50명 전원이 같은 창, 공통 대면 지역 하나, EITHER인 경우 창 하나마다 2,552 variants 및 122,600 participant rows가 생성된다. 14개 창이면 약 172만 participant rows다. 저장 누락 없는 계약은 유지하면서 조회 대상 option/variant를 SQL에서 한정하는 방법과 최대 허용 입력의 현실적인 비용을 확인해야 한다. 아직 부하 측정에 근거한 실패라고 단정하지 않는다.

## 확인된 설계 장점과 경계

- 사용자 명시 길이 없이 실제 연속 창을 보존하며 동일 절대 창은 하나의 option으로 묶는다. 전원 후보가 있어도 부분 참석 조합을 평가한다.
- 기존 candidates는 legacy 의미를 유지하고 option UUID를 candidate FK로 위장하지 않는다. 새 protocol의 legacy confirmation은 409다.
- typed confirmation은 room→active run 잠금 및 전체 analysis/option/variant/start/end 튜플 멱등을 적용한다. frozen submission read를 재사용한다.
- 새 조회는 참여자 권한, 새 확정은 HOST 권한을 유지한다. Origin 범위와 host rate limit이 새 URL에도 적용된다. 공개 응답에 정상 원문이나 참가자별 선호를 추가하지 않는다.
- 수정 라운드/worker/공개 상태/권한 capability에서 두 확정 유형을 인식하도록 변경했다.
- DB guard는 당시 작성 예정임을 부모가 명시했으므로 미완성 단계의 누락을 별도 최종 결함으로 기록하지 않았다. 최종 스냅샷에서 old writer, 실제 창 포함, 활성 분석 및 retention을 재확인한다.

## 현재 판정

구현 진행 중이므로 최종 승인 보류. 수정 요청 해결 여부, D2 사용자 결정, 실제 native OpenAPI, 필수 guard/lint/assemble/전체 test 및 독립 결함 주입 결과는 부모의 최종 증거와 함께 확인해야 한다.

## 갱신 스냅샷 재리뷰

V9 DB guard와 precise summary가 추가된 작업 트리를 다시 읽었다. 아래 판정은 정적 검토이며 테스트 실행 성공을 대신하지 않는다.

- 원래 P2 summary: 초 또는 소수초가 있으면 `ISO_OFFSET_DATE_TIME`으로 양쪽 경계를 표시한다. 독립 `RecommendationReviewRegressionPostgresTest`는 29초·1마이크로초 선택 및 저장 후 결과·동일 튜플 replay를 검사한다. 수정 확인.
- 원래 P3 variant enum: 별도 `RecommendationMeetingMode(IN_PERSON, REMOTE)`를 사용하고 native OpenAPI 독립 회귀 검증을 추가했다. 수정 확인.
- 원래 P2 기준 문서: ADR-047이 기존 ADR-027의 신규 protocol 범위 대체를 명시하고 PRD/Architecture가 typed selection·legacy 보존·V9 운영 제한을 기술한다. 핵심 수정 확인. PRD Rule 3 단계 6의 `실제 날짜는 주최자가 정해 공지`는 legacy에 한정하도록 추가 정리를 권고한다.
- Frozen N: MatchingProcessor는 frozen submissions 전체를 `inputs`에 보존하며 미반영 참가자의 availability만 비운다. projector 최소 조합은 `max(2, N-2)`이고 공개 total_participants는 frozen batch 길이, DB 선택 guard의 인원도 동일 run.batch_id의 batch items 기준이다. 미반영 참가자를 제거하여 N을 줄이는 경로를 발견하지 않았다.
- DB 경계: 신규 candidate-only 확정 거부, room→run 잠금, active analysis/CLOSED/OPEN round/구 확정 확인, 단일 창 containment, batch fixed_at 및 같은 frozen batch 참가자 검사를 확인했다. option/variant/run/room은 복합 FK로 연결한다. 확정 뒤 상태/품질/batch/room 및 활성 포인터·수정 round 변경을 거부한다.
- **새 P1 retention 순서 충돌**: typed selection이 있는 방에서 기존 retention의 첫 `active_run_id = NULL`은 새 confirmed room guard에 막힌다. 기존 방 잠금 아래 recommendation_selections를 먼저 제거한 후 기존 pointer 정리·run cascade 순서로 진행해야 한다. 부모가 기존 독립 retention 테스트의 RED 확보 후 이 수정 진행을 확인했다. 수정/최종 GREEN 확인 전 해결로 간주하지 않는다.
- V9 guard는 구 V8 reader/lifecycle까지 호환시켜 주지 않는다. Architecture와 ADR에서 전체 기존 writer/worker/retention 중지 및 V9 호환 digest 운영 검토를 별도 선행조건으로 구분한 것은 적절하다.

### 요청 범위를 유지하는 조회 개선안

현재 전체 hydrate는 검토 사항으로 남아 있다. 저장을 제한하거나 대안·variant를 삭제하지 않고 다음처럼 조회 경계를 나눌 수 있다.

1. `findMetadata(analysisId)`는 protocol·room·totalOptions·hasAlternatives만 반환한다. primary 조회는 SQL `primary_rank IS NOT NULL ORDER BY primary_rank`의 최대 세 option만 조회한다.
2. `findAlternatives(analysisId, afterOrdinal, limit)`는 `primary_rank IS NULL AND ordinal > ? ORDER BY ordinal LIMIT limit+1`로 다음 cursor 존재를 확인한다. 전달된 ordinal이 해당 분석의 실제 대안인지 별도 존재 확인하여 현재 cursor 오류 계약을 유지한다.
3. 확정과 result는 `optionById(analysisId, optionId)` 및 `variantById(analysisId, optionId, variantId)`만 읽는다. authorization·run protocol·tuple containment·멱등 검증은 서비스/DB에 그대로 유지한다.
4. 조회 전용 projection은 `attendanceCount`를 가진다. 공개 응답에 participantIds가 필요 없으므로 수백만 `ParticipantId` 객체 대신 대상 variant의 `COUNT(*)`를 SQL에서 계산한다. 안정 variant 순서를 보존해야 한다면 publication ordinal을 저장하거나 현재 정렬 키를 SQL aggregate로 계산한다.
5. 타깃 variant 조회를 위해 `recommendation_variants(option_id)` 또는 `(coordination_run_id, option_id)` 인덱스를 추가한다. `recommendation_variant_participants(variant_id, participant_id)` PK는 대상 variant count에 이미 유용하다. option의 `(coordination_run_id, ordinal)` unique index는 keyset page를 지원한다.

이 분리는 전체 대안 보존·공개 API 형태·typed selection FK를 바꾸지 않는 내부 구현 변경이다. 최대 50명 합성 입력에서 option 페이지와 선택 result가 대상 option/variant만 조회하는지 독립 검증하고, 부모의 실제 PostgreSQL 검증과 함께 확인한다.

## 계약 커밋 전 native OpenAPI 최종 확인

부모가 46/46 대상 테스트 GREEN과 retention 수정 완료를 전달한 뒤 native JSON 및 `ISSUE_95_WEB_HANDOFF.md`를 직접 대조했다. Gradle은 실행하지 않았다.

- Retention P1: 현재 `deleteRoom`은 이미 잡힌 방 row lock 아래 typed selection 삭제를 먼저 수행한 후 pointer를 비우고 run cascade를 수행한다. 원래 보존 기준일은 유지한다. 정적 수정 확인 및 부모가 전달한 독립 retention GREEN으로 해결 확인.
- 실제 native JSON은 OpenAPI 3.1이며 `limit`은 integer/int32·기본 20·1~100이다. `protocol`·`rank`·`next_cursor`는 nullable scalar, variant place 및 result selection은 `$ref | null` anyOf다. 요청 네 필드는 모두 required, UUID/date-time 형식이 있고 variant mode는 두 값이다. 웹 문서의 cursor 종료·primary 제외·has_alternatives 의미와 코드가 일치한다. 지난 요청 analysis_id는 409 STALE_ANALYSIS, 다른 분석에 속한 cursor를 현재 분석과 섞으면 400 VALIDATION_FAILED다.
- 시각 선택은 start < end와 단일 option containment 및 마이크로초 나머지 검증을 수행한다. seconds/microseconds summary는 실제 양쪽 경계를 유지한다. legacy 후보 ID와 typed option ID를 분리하고 result의 선택 시각을 재조회한다.
- **새 P2 native requiredness 불일치**: 웹 인계는 RoomResponse.recommendation_protocol을 `required nullable string`이라고 한다. 현재 exported JSON의 RoomResponse에는 required 배열이 없어 codegen상 optional이다. 새 필드의 `@Schema(requiredMode=REQUIRED)`와 native 회귀 assertion/재export로 맞추거나 문서가 optional이라고 명시해야 한다. runtime이 항상 null 포함 해당 키를 제공하는 현재 계약에서는 required schema가 일관된다. 부모에게 즉시 전달했다.
- **P3 관련 기존 result 오류 누락**: `/result`의 getConfirmed는 completedRun을 거쳐 처리 중/OPEN revision이면 409를 내지만 native 응답 목록은 200/401/403/404다. 결과 호환 계약을 이번 인계에서 사용하는 만큼 409 ProblemDetail 문서화를 권고한다.
- PRD Rule 3 단계 6의 날짜 공지 문구가 legacy 범위로 좁혀진 것을 확인했다. 원래 문서 잔여 권고는 해결됐다.

현재 신규 제품/API의 추가 런타임 결함은 발견하지 않았다. D2 선호 집계는 승인 대기인 별도 차단 범위이며, 전체 hydrate 성능 사항과 필수 전체 guard/결함 주입 검증은 아직 완료됐다고 주장하지 않는다.

## 최종 소스 및 기존 테스트 적응 리뷰

부모가 조회 refactor 후 대상 46개 GREEN을 확인한 시점의 최종 소스와 4개 기존 테스트 파일 변경을 다시 정적 검토했다. 이 리뷰어는 Gradle을 실행하지 않았다.

- Repository는 metadata의 정확한 전체/대안 count를 별도로 유지하며 primary 최대 3 option, alternatives limit+1 option, 선택/결과의 단일 option으로 SQL 조회를 한정한다. membership·variant도 선택된 option IDs와 analysis ID 양쪽으로 한정한다. ordinal cursor 존재 확인과 keyset 조건은 이전 400/409·다음 페이지·대안 누락 없음 계약을 유지한다. option별 인덱스가 추가됐고 membership 쓰기는 500개씩 분할한다. 전체 분석을 모든 조회마다 hydrate하던 원래 성능 경계는 수정 확인. 한 option의 모든 variants/memberships는 계약대로 유지하므로 최악 입력 비용이 완전히 사라졌다고 주장하지 않는다.
- Room protocol requiredness 차이는 웹 문서를 optional nullable string으로 정정하고 이전 서버의 필드 부재를 legacy로 처리하도록 명시해 해결했다. 실제 native JSON과 일치한다. `/result` native 응답에 409도 추가됐다.
- `CorePersistenceIntegrationTest`와 `DatabaseMigrationRunnerTest`의 기대 Flyway 버전/실행 수는 새 V9에 맞춰 8→9로 변경했으며 assertion을 삭제하거나 범위를 느슨하게 하지 않았다.
- `CorrectionMeetingScenarioPostgresTest`는 신규 분석의 legacy confirmation 기대를 200→409 및 정확한 오류 코드로 바꾸고 실제 typed confirmation 200, 전체 selection tuple, 실제 시간 창, legacy 방식/인원 속성, 참가자 result 재조회 일치 및 CONFIRMED/재수정409를 추가로 검사한다. 기존 보호 검증을 삭제하지 않았다.
- `SubmissionNaturalLanguageOnlyIntegrationTest`도 legacy 우회409/정확한 코드 뒤 typed confirmation·동일 tuple 재확정 완전 일치·worker 재실행 불변·추천 5개 테이블 불변·legacy 참가자의 결과 읽기·전체 N=3/참석2·원문 미노출·frozen batch 및 기존 수동 데이터 보존을 검사한다. candidate ID 기대 변경은 새 selection FK 분리 계약에 따른 것이며 검증 약화가 아니다.

**최종 blocking finding: 없음.** 현재 확정된 비-D2 범위의 소스/계약과 독립 기존 테스트 적응에서 추가 차단 결함이나 기대값 약화를 발견하지 않았다. D2 사용자 결정 대기와 부모가 진행 중인 전체 guard·독립 결함 주입·원격 HEAD CI는 이 정적 리뷰로 완료되지 않는다. 모델 호출 및 프로덕션/테스트 수정은 0회다.

## 추가 binding 오류 계약 검토

독립 `RecommendationBindingPostgresTest`의 필수 analysis_id 누락·잘못된 UUID·숫자가 아닌 limit·잘못된 option 경로 UUID 네 사례와 `MissingServletRequestParameterException` 전용 handler를 읽었다. 누락 쿼리도 기존 공통 `problem(...)`을 통해 400 VALIDATION_FAILED 및 RFC9457 필드를 제공하는 변경이며 auth/Origin/type mismatch 정책을 바꾸지 않는다. 테스트는 정확한 ProblemDetail content type·code·status·필수 속성과 방/작업/두 확정 테이블의 무변경을 검사한다.

다섯 번째 기존 적응 파일 `MatchingResultWebIntegrationTest`는 result 오류 코드 집합에 실제 409를 추가하고 그 응답 schema가 `ApiProblemSchema`인지 추가 검증한다. 기대 집합을 느슨하게 바꾸거나 assertion을 제거하지 않았다. 부모가 별도 baseline 8개 테스트 중 1개 유효 assertion RED를 확보했다고 전달했으며, 이 리뷰어는 테스트를 실행하지 않았다.

추가 변경의 blocking finding 없음. 앞선 최종 정적 판정을 유지한다.
