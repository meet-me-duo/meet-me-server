# #95·#96 병렬 V9 충돌과 통합 인계

> 아래 본문은 PR97/98의 시작 인계 기록이다. #99 통합의 최신 구현·검증 상태는 [통합 기록](ISSUE_99_INTEGRATION.md)을 따른다. 추천 V9·invocation V10, ADR-047/048·Luna ADR-049로 조정하며 원본 SQL 바이트를 보존했다.

확인: 2026-10-07 UTC. #95 [Draft PR97](https://github.com/meet-me-duo/meet-me-server/pull/97)의 구현 snapshot은 `fcde6ca716357cfc923786669c944ca3fc48d734`, #96 [Draft PR98](https://github.com/meet-me-duo/meet-me-server/pull/98)의 확인 snapshot은 `9a4c4d5463e578829baed12cfebad16fb0bf33a4`다. 양쪽 기준 PR94 HEAD `bf4edff51dea5f1f90a8ac627edf05ec17b6d56d`는 PR93/V8을 포함한다.

**각 PR의 독립 GREEN/CI는 통합·배포 준비 완료를 뜻하지 않는다.** 현재 두 migration은 같은 version9여서 그대로 한 classpath에 넣으면 Flyway duplicate version 오류다. 이 세션은 #96 source를 읽어 비교했으며 운영 적용·번호 변경·통합 merge를 실행하지 않았다.

## 두 V9의 목적과 내용

| PR | 파일 | 목적과 데이터 영향 |
| --- | --- | --- |
| #95 / PR97 | [V9__add_time_recommendations.sql](https://github.com/meet-me-duo/meet-me-server/blob/fcde6ca716357cfc923786669c944ca3fc48d734/src/main/resources/db/migration/V9__add_time_recommendations.sql) | recommendation_analyses/options/variants/variant_participants/selections 5개 테이블. 분석·방·option·variant의 복합 FK와 run cascade, 시간창 dedup·primary1~3·양수 실제 선택 창, 최소 frozen N/N-1/N-2 참가자 확인. 기존 final_confirmations/coordination_runs/meeting_rooms/input_revision_rounds에 새 protocol 우회·확정 뒤 변경을 막는 trigger와 projection UPDATE 불가 trigger, option 조회 인덱스를 추가한다. 과거 후보/확정에 추천 데이터를 임의 backfill하지 않으며 legacy protocol/selection은 null이다. |
| #96 / PR98 | [V9__add_bounded_analysis_invocations.sql](https://github.com/meet-me-duo/meet-me-server/blob/9a4c4d5463e578829baed12cfebad16fb0bf33a4/src/main/resources/db/migration/V9__add_bounded_analysis_invocations.sql) | analysis_invocations에 run/version별 unique 실행·owner_token·원래 started/deadline(최대60초)·Gemini0~4/Luna0~1 admission·finished/winner·bounded-luna-v1 정책과 미완료 deadline 인덱스를 추가한다. coordination_attempts에 provider/model/policy_version/invocation_id를 추가하고 기존 행에는 GEMINI/gemini-3.8-flash/gemini-v1/null 기본값을 적용한다. invocation/run FK, 공급자/모델/정책 CHECK, invocation→winner의 deferred FK를 추가해 재전달이 호출 예산을 초기화하지 않게 한다. |

테이블·함수 이름의 직접 중복은 없다. **두 파일의 Flyway version 충돌과 공유 application 로직 병합**이 별도 문제다. 기존 V1~V8은 양쪽에서 보존한다.

## 안전한 통합 순서 제안

1. 독립 통합 브랜치를 승인된 PR94/PR93 기준에서 만든다. 두 PR의 정확한 HEAD를 고정하고, 운영이나 보존해야 할 DB에 어느 V9도 적용되지 않았는지 기존 Flyway 이력으로 확인한다. 이 세션은 운영 이력을 조회하지 않았다.
2. 부모의 최신 통합 검토 방향은 미적용 조건에서 #95의 추천 schema를 V9로 유지하고 #96 파일을 `V10__add_bounded_analysis_invocations.sql`로 조정하는 것이다. 이전의 반대 번호 배분 제안은 이 방향으로 갱신한다. 두 schema 사이에 직접 FK 의존은 없으며 최종 번호 배분은 별도 통합 브랜치에서 고정한다. 이미 적용된 migration의 이름·checksum을 바꾸거나 repair로 덮지 않는다. 독립 feature95의 번호는 이 보고에서 변경하지 않는다.
3. Git 충돌을 수동으로 조정한다. 특히 MatchingProcessor/MatchingProcessingPersistenceService의 #96 `completeBounded`와 #95 `completeRecommendations`를 함께 유지해야 한다. room→active run 잠금, version/confirmed guard, invocation owner/deadline/canPublish 확인 아래 legacy 후보·추천 projection·방 transition을 **한 트랜잭션**에 게시하고, 게시 뒤 deadline 재검증 실패 시 전부 롤백해야 한다. deadline 후 fresh delay 처리도 원frozen 입력과 확정 결과를 보존해야 한다. 한쪽 함수만 선택하면 다른 계약을 잃는다.
4. ActiveRunLock·CoordinationPersistenceAdapters·MatchingResultService·InputRevisionService·RoomLifecycleService·RoomDataRetentionRepository와 테스트/문서의 양쪽 변경을 함께 검토한다. 문서 충돌도 합친다. 두 PR이 ADR-047을 서로 다른 결정에 사용하며 #95의 후속 D2는 ADR-048이다. 통합 브랜치에서 두 결정을 모두 보존하고 #96 Luna 기록을 다음 미사용 번호로 배정한 뒤 문서 참조를 갱신한다. typed selection 삭제→room pointer 정리 순서를 유지하고 invocation/attempt 및 recommendation의 FK/cascade 정리를 모두 보존한다. 새 분석의 실제 선택 API와 과거 candidate 확정을 구분하며 #96의 실행 예산 fencing도 유지한다.
5. 임시 빈 PostgreSQL에서 V1~V10 전체 migrate/validate와 중복 version0을 검증한다. 별도의 **기존 V8 데이터 fixture**에서 upgrade를 실행하고 원문·created_at·frozen version/cohort·OPEN/CONSUMED 수정 라운드·과거 후보/확정·과거 attempt 행을 그대로 비교한다. 과거 attempt 기본값과 legacy protocol/selection=null, 새 invocation FK와 recommendation FK를 검사한다. 새 버전 기대9인 두 migration 테스트는 통합 schema의10으로 맞추되 검증을 약화하지 않는다.
6. 유료 호출 없는 Fake/저장 응답으로 두 기능의 전체·동시성·롤백 테스트와 unchanged 필수 guard/lint/assemble/test를 실행한다. Luna fallback→정확한 날짜별 추천→실제 시각 확정→worker 재전달·재분석 거부까지 통합 사례를 추가한다. 게시 도중 deadline 초과는 후보/projection 모두0, 입력/frozen version 보존, ANALYSIS_DELAYED/ACK를 검증한다. 큰 N/여러 창의 projection·DB 쓰기가 #96 완료 예산에 들어가는지도 측정한다.
7. 통합 HEAD의 실제 native OpenAPI를 재생성하고 웹 #19의 계약/401~503/legacy 경로를 검증한다. 통합 Draft PR과 그 정확한 HEAD CI가 통과해야 통합 검증 완료다. 현재 각 feature의 CI를 통합 HEAD의 결과로 재사용하지 않는다.

D2 명시 선호 집계는 사용자 직접 승인으로 확정됐으며 [새 구현 인계](ISSUE_95_D2_IMPLEMENTATION_HANDOFF.md)를 따른다. 두 공급자의 공통 validator/schema와 게시 deadline 경계를 함께 유지해야 한다. D2 제품 구현은 아직 미완료다. 운영 전환과 구 V8 reader/rollback 호환, 기존 설치 guard·writer/worker/retention 중지 및 V9/V10 대응 digest 검토는 통합 테스트와 구분한다. 운영 배포·권한/보안 변경·main/develop merge·유료 평가 호출을 이 작업에서 실행하지 않는다.
