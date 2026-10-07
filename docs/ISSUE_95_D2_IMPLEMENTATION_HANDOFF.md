# #95 D2 확정 계약과 새 구현 세션 인계

확인일: 2026-10-07 UTC. **D2의 사용자 결정은 완료됐고 제품 구현은 아직 미완료다.** 이번 세션은 문서만 갱신하며 제품 코드·유료 모델 호출을 시작하지 않는다. 후속 문서 커밋 SHA는 원격 Git/PR 이력과 인계 메시지를 기준으로 확인한다.

## 시작 기준과 승인 근거

- 서버 #95: `feature/95-diverse-time-recommendations`, [Draft PR97](https://github.com/meet-me-duo/meet-me-server/pull/97). 제품 구현 기준 SHA는 `627e0b717a7f4f72dfc27b2ff580b2ed9f46c24a`다. 첫 실제 API 인계 commit은 `fcde6ca716357cfc923786669c944ca3fc48d734`이며 제품 source/test/native OpenAPI가 두 commit 사이 동일하다.
- 의존 PR94 HEAD `bf4edff51dea5f1f90a8ac627edf05ec17b6d56d`는 PR93 `1ddbd267`을 포함한다. 기준 develop은 `848b9688451551781769799ff7d27485ea5a5445`였으며 후속 세션은 최신 원격을 확인한다. main/develop에 직접 수정하거나 병합하지 않는다.
- #96: [Draft PR98](https://github.com/meet-me-duo/meet-me-server/pull/98) HEAD `9a4c4d5463e578829baed12cfebad16fb0bf33a4`. 두 공급자 공통 validator/schema 연계를 이 SHA에서 read-only 확인했다. #96 자체 CI37581751054 성공·526건/실패0/skip4는 부모가 인계한 결과이며 #95와 통합된 검증이 아니다.
- 사용자는 참석 인원 → 선호가 맞는 사람 수 → 날짜·시간 다양성, 시간·장소를 모두 만족할 때 참가자별1건, 문장 반복으로 점수 증가 없음, 가능한 대안 유지, 새 UI 없이 자연어 입력이라는 설명에 **“Meet me는 네 제안대로 진행해”**라고 직접 승인했다. 이전 문서·정적 리뷰의 D2 대기 기록은 당시 이력이며 새 의사결정 대기가 아니다.

## 확정된 제품 의미

[계약](ISSUE_95_CONTRACT.md)의 D2 규칙과 PRD §3.4/FR-011/Rule3, ADR-048을 따른다.

1. 필수 가능·불가·예외·기존 장소조건·최소2명 조건을 먼저 만족해야 한다. 선호는 가능한 대안을 지우거나 참석 가능 범위를 늘리지 않는다.
2. 참석 인원 우선, 다음은 해당 variant에서 선호를 충족한 참석자 수다. 참석자별 최대1점이며 선호 없음은0점이고 비참석자는 집계하지 않는다. 문장 수·선호 차원 수로 가중하지 않는다.
3. 같은 차원의 명시 대안은 OR, 명시된 여러 차원은 AND다. 날짜와 시간은 하나의 시간 predicate로 묶는다. “목요일20시이후”를 목요일 OR20시이후로 분리하지 않는다.
4. 시간 창 전체가 그 사람의 선호 시간 합집합에 포함돼야 시간 차원을 충족한다. 장소 차원은 variant의 기존 유효한 공통 장소 그룹으로 판정한다. 시간만 맞거나 장소만 맞으면 둘 다 적은 사람의1점은 얻지 못한다.
5. 실제 가능 창과 선호 시간의 교차 및 여러 참석자의 선호 경계가 만드는 의미 있는 하위 창을 별도 option으로 보존한다. 원래 전체 가능 창도 보존한다. 최대3 primary와 전체 대안, 같은 창 dedup/variants, 실제 선택 구간 확정은 그대로다. 임의 길이·무한한 임의 부분 창은 만들지 않는다.
6. 경계 분할/교집합 계산, 내부 타입과 schema version 전략은 후속 구현자가 설계한다. 같은 인원·같은 선호 충족 수이면 기존 날짜/시간 다양성·안정 동률 규칙을 사용한다.

| 입력·전제 | 기대 의미 |
| --- | --- |
| “평일19–21가능, 목요일20시이후선호” | 평일19–21을 유지. 목요일20–21은 선호 하위 창으로 보존. 목요일19–21전체는 해당 사람의 시간 선호0점. 20–21길이는 명시된 가능 종료와 선호 시작의 교차이며 고정1시간 설정이 아니다. |
| “강남 또는 홍대 선호” | 유효한 공통 장소가 강남 또는 홍대면 장소 차원 충족. 두 장소를 함께 충족하지 않으며 두 표도 아니다. 다른 장소가 기존 조건으로 유효하면 선호로 제거하지 않는다. 선호만으로 공통 장소를 발명하지 않는다. |
| 위 두 차원을 함께 입력 | 목요일20–21이면서 강남 또는 홍대인 variant에 해당 참석자1점. 시간만/장소만 맞는 안은0점. 다른 참석 인원과 필수 조건이 같다는 전제다. |
| 모두18–22가능, A20–21선호/B21–22선호 | 전체18–22는 두 선호를 충족하지 않는다. 두 하위 창을 보존하고 각각 실제 충족한 참석자만 집계한다. |
| 모두18–22가능, A19–21선호/B20–22선호 | 각 선호 창뿐 아니라 공통 선호20–21에서 두 사람이 충족하는 하위 창도 놓치지 않는다. |
| “목요일20시이후선호, 목요일20–21불가” | 불가 우선. 불가 시간은 선호 하위 창으로도 생성/확정하지 않는다. |

새 입력 UI는 없다. 기존 자연어 제출 PUT과 frozen submission version을 그대로 사용한다. 기존에 지원하지 않는 필수 시간·장소 조건부 결합을 선호 지원을 이유로 임의 허용하거나 Cartesian product로 늘리지 않는다. 현재 코드의 `preferenceCount=0`을 실제 선호 충족 결과로 설명해서는 안 된다.

## #96 공통 schema/validator와 영속 경계

#96 고정 SHA의 관련 파일은 아래다. #95에 아직 이 공통 파일이 없으므로 양쪽 소스를 읽고 통합 범위를 조율한다. 한 공급자 adapter에만 D2를 구현하지 않는다.

| 파일·현재 관찰 | 후속 요구 |
| --- | --- |
| `coordination/adapter/output/integration/NaturalLanguageProviderContract.kt` | 공통 prompt, RESPONSE_SCHEMA, parseProviderResponse. 현재 live schema_version2, root/result 키·input_ref 정확 일치·조건32개·응답262144byte·AREA명 일관성을 검증한다. TIME_WINDOW(AVAILABLE/UNAVAILABLE), SPECIFIC_PLACE, TRAVEL_CONSTRAINT, UNRESOLVED_PLACE만 있다. 선호를 별도 구조로 표현하고 동일 차원 대안/여러 차원 관계와 시간·장소 역할을 보존해야 한다. schema/version 및 legacy 복원 전략을 명시하되 기존 safety/byte/condition 한도와 무결성 검증을 조용히 제거하지 않는다. |
| `GeminiNaturalLanguageParserAdapter.kt` | 실제 parse는 공통 validator를 strict=true로 호출하고 공통 schema/prompt를 사용한다. 기존 테스트 helper는 비strict v1/v2 호환 경로다. 새로운 live 계약과 기존 fixture 호환을 구분한다. |
| `OpenAiLunaNaturalLanguageParserAdapter.kt` | 같은 공통 계약과 strict=true, OpenAI response format strict=true. format name은 현재 meet_me_conditions_v2다. 공통 schema 확장 시 두 요청 payload와 format/version 명칭을 일관되게 맞춘다. SDK/모델 점수나 후보 생성은 허용하지 않는다. |
| `submission/domain/StructuredCondition.kt` | 현재 선호 타입/필드 없음. 공급자 중립 내부 모델로 하드 제약·선호를 구분하고 도메인→adapter 의존을 만들지 않는다. AVAILABLE을 선호로 재해석하지 않는다. |
| `submission/adapter/output/persistence/SubmissionPersistenceAdapters.kt`의 StructuredConditionJsonMapper | 조건 JSON의 toMap/fromMap을 함께 갱신하고 실제 DB round-trip을 검증한다. 기존 저장 조건은 선호 없음으로 복원한다. 과거 후보·확정은 자동 재분석/backfill하지 않는다. raw_text/created_at/submissionVersionId와 cohort를 바꾸지 않는다. |
| `coordination/application/service/MatchingProcessor.kt`, `domain/matching/RecommendationProjector.kt` | frozen 구조화 결과에서 필수 조건을 먼저 계산하고 각 참석 집합/장소 variant별 선호1점과 하위 창을 계산한다. 기존 matcher의 legacy Plan 의미와 unsafe time rejection을 유지한다. 개인별 선호/정상 원문은 공개하지 않는다. |
| `GeminiBatchProcessor.kt`와 MatchingProcessingPersistenceService | #96의 invocation ownership·durable/monotonic deadline·bounded completion 안에서 #95 legacy 결과와 projection을 한 transaction으로 게시한다. 게시 뒤 deadline 실패는 전부 rollback하고 원frozen 입력/확정 보호를 유지한다. |

경로의 공통 prefix는 `src/main/kotlin/com/meetme/server/`다. 새 내부 representation/schema version은 일반 기술 선택으로 진행할 수 있으며 같은 제품 의미에 대해 다시 사용자 승인을 요청할 필요가 없다. 잘못된 선호나 표현 불가능한 조건을 조용히 선호 없음으로 버리지 않고 기존 검증·미반영/부분 결과 경계를 독립 테스트로 확인한다.

## 공개 API와 통합 조건

- `diverse-time-v1`, `/recommendations`, `/recommendations/alternatives`, `/recommendations/{optionId}/confirmation` 및 option/variant DTO를 유지한다. schema_version은 내부 공급자 wire 계약이며 공개 recommendation protocol과 구분한다.
- 선택의 analysis/option/variant/start/end 전체 튜플, 단일 창 포함·마이크로초·멱등·stale/OPEN/confirmed 보호와 legacy candidate/result 호환을 유지한다. 소요시간·새 선호 입력 필드·개인별 점수를 공개 API에 추가하지 않는다.
- 기존 [native OpenAPI](openapi/issue-95.openapi.json)와 [웹 인계](ISSUE_95_WEB_HANDOFF.md)가 웹 작업 기준이다. 후속 실제 서버 OpenAPI를 다시 생성해 parsed 계약 차이를 검사하고 byte 차이가 있으면 원인을 설명한다.
- 현재 두 PR 모두 V9다. 최신 부모 검토 방향은 #95 V9 유지·#96 invocations V10이며 별도 통합 브랜치에서 최종 번호 배분, 빈 DB와 기존 V8 fixture migrate/validate, 공유 코드 병합을 검증한다. 상세는 [통합 인계](ISSUE_95_96_INTEGRATION_HANDOFF.md)를 따른다. 적용된 migration checksum을 수정/repair하지 않는다. ADR도 양쪽 ADR-047 충돌이 있으므로 #95의 시간 추천047/D2 048과 #96 Luna 결정을 모두 보존하며 통합 브랜치에서 번호·참조를 정리한다.

## 새 구현 세션의 검증 요구

- 독립 테스트 저자는 확정 계약으로 assertion RED를 먼저 작성한다. 도메인: 위 사례, 무선호/중복문장/비참석자/같은차원 OR/여러차원 AND/시간 전체 포함·부분 겹침/선호 합집합·서로 겹치는 선호 하위 창/불가·예외·장소조건/인원 우선·동률 다양성·대안 누락0·no padding·DST/자정을 포함한다.
- provider mock fixture: 동일한 선호 응답을 Gemini/Luna가 동일 도메인으로 복원, strict schema/version/unknown fields·모든 input_ref 정확 일치/AREA명 검증·유효조건 한도·불가를 선호로 변환 금지·조건부 필수 제약 안전 제외를 확인한다. 프롬프트 문자열 존재만 확인하는 테스트로 대체하지 않는다.
- 실제 PostgreSQL/HTTP: JSON 저장·복원, legacy 선호 없음, frozen 재분석·새 버전과 old batch 분리, CONFIRMED 보호, primary/전체 alternatives/선택 창·variant·권한·멱등·race와 개인정보를 검증한다.
- #96 결합: fake/저장 응답으로 Gemini→Luna fallback→선호 하위 창 추천→실제 확정, 중복 worker·invocation budgets·deadline 후 후보/projection rollback·ANALYSIS_DELAYED/ACK·큰N/다수창 완료 예산을 검증한다. 개별 PR의 CI를 통합 검증으로 재사용하지 않는다.
- 독립 리뷰와 고의 결함 주입: AND→OR, 인당1점→조건개수, 전체 포함→부분겹침, 하위창 유실, 하드불가 우회, provider별 schema 분리, projection 게시 뒤 deadline guard 제거 등을 실제 assertion으로 탐지하고 원본 SHA를 정확 복구한다.
- 변경 없는 mandatory tdd_guard·lint·assemble·전체 테스트·새 정확 HEAD CI를 완료하고 Worklog/RED·GREEN/리뷰/결함 증거를 갱신한다. 유료 호출은0이며 라이브 의미 평가는 별도 승인된 전담 평가 세션으로 분리한다.

## 완료된 근거와 아직 없는 근거

#95 제품 baseline627e0b7은 [CI37582706551](https://github.com/meet-me-duo/meet-me-server/actions/runs/37582706551) 전 단계 성공, 로컬 필수 guard488건 중484PASS/4기존선택skip/실패0, 독립 리뷰 비-D2 차단없음, 결함5/5탐지를 완료했다. 이 결과는 **D2 구현·새 provider 계약·#95/#96 통합 검증이 아니다**. 승인 후 문서 변경과 제품 검증을 구분해 보고한다.

main/develop 병합·운영 migration/배포·권한/보안 변경·유료 모델 호출은 이 인계의 실행 범위에 포함되지 않는다.
