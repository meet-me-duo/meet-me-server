# Issue 236 current migration catalog and historical fixture review

독립 테스트 설계자가 V32 추가로 발생한 기존 통합 테스트 실패를 검토했다. 운영 코드와 V1~V31 SQL은 변경하지 않았다.

최초 전체 검사에는 4,887건 중 실패 9건과 건너뜀 41건이 있었다. 여섯 파일의 여덟 실패는 현재 카탈로그를 V31로 고정하거나, 역사적 V31 단계에 상한 없이 최신 마이그레이션을 적용한 기대값 문제였다. PC06 한 건은 선택 분석 엔진을 의도적으로 제외한 역사적 테스트에서 새 운영자 명령 빈의 의존성이 충족되지 않은 초기화 문제였다. 그 최초 검사는 수정 전 파일 지문에 대한 결과이며, 수정 후 테스트의 RED로 재표기하지 않았다.

- `DatabaseMigrationRunnerTest`, `CorePersistenceIntegrationTest`, `ScheduleIrRepositoryPostgresTest`, `ApprovedTermsFinalAnalysisRoutePostgresTest`, `ScheduleIrIntegrationPostgresTest`: 상한 없는 현재 카탈로그 기대값만 32로 변경했다. 기존 데이터·제약·분석 의미 검증은 유지했다.
- `IntegratedLegacyUpgradePostgresTest`: V8/V30/V31 수치와 V31 SQL SHA-256, 기존 데이터 검증을 유지했다. V31 적용과 검증에 명시적으로 target 31을 지정했다. 그 단계 다음에 V32 하나를 적용하고 카탈로그 32, 기존 데이터 보존, 빈 운영자 명령 테이블을 확인한다.
- `RecommendationPartialConfirmationMigrationPostgresTest`: 현재 단계만 32로 변경했다. V17/V18의 두 명/다섯 명 선택 제약과 기존 결과 검증은 유지했다. 선택 엔진을 제외한 이 클래스에만 `@MockitoBean(enforceOverride = true)`로 새 `OperatorReanalysisCommandPort`를 교체하고, 끝에서 `verifyNoInteractions`를 확인한다. 기존 호스트 명령과 저장소는 실제 구현을 사용한다. 정상 전체 애플리케이션의 새 명령 빈은 실제 구현을 사용하는 별도 PostgreSQL 40건에서 검증한다. 이 변경은 역사적 통합 fixture 호환성 수정이며 새 제품 동작을 추가한 RED가 아니다.

수정된 일곱 파일은 포맷과 Kotlin 테스트 컴파일을 통과한 뒤 지문을 동결했다. [RED 기록](../red/issue236-current-catalog-v32.json)은 이 최종 파일 그대로 아홉 메서드를 실행한 **구현 후 반사실 검증**이다. 실제 구현 후보의 격리 복사본에서 `V32__add_operator_room_reanalysis.sql` 하나만 제외했고, 그 밖의 운영 코드는 그대로 두었다. 원본 작업 트리에는 결함을 주입하지 않았다.

이 실행에서 아홉 건 모두 의도한 assertion으로 실패했고 오류와 건너뜀은 없었다. 일곱 건은 현재 32 대신 31을 관측했다. 두 역사적 통합 사례는 V30/V31 단계를 통과한 뒤 V32 추가 적용 건수 1 대신 0을 관측했다. V32를 원래 바이트로 복원한 동일 명령은 아홉 건 모두 통과했다. 원문 명령·로그·JUnit XML·복사본 구성과 최초 실패 자료는 Git에서 제외한 작업 artifacts에 보존했다.

동결된 전체 소스 지문:

- 수정된 테스트와 V32 포함: `1e2d847ce3f3b72e5c2c86c794d35b191260bf20fb90fcf77093c2c7641fbed8`
- 위 격리 RED에서 V32만 제외: `95c19a352b4bcb573e68d82fe8fedbfcde9121c6d7accff3f9f4868a42318d03`

이 지문은 `build.gradle.kts`와 `src`의 파일 경로를 정렬한 뒤 각 상대 경로, NUL, 파일 SHA-256 바이트를 연결하여 계산한 SHA-256이다. RED JSON의 `sourceRevision`은 해당 격리 실행의 실제 소스 지문이다. 테스트 파일별 지문과 실행한 아홉 메서드의 정확한 명령은 RED JSON에 있다.
