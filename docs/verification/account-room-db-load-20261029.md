# 계정 모임 조회 DB 부하 검증 — Issue161

2026-10-29 UTC. [추적 이슈](https://github.com/meet-me-duo/meet-me-server/issues/161). 기준 develop `4bc1170faefc706943f074f9204cc279368109d0`의 tree는 main `c152ba7014e801d47a80f5b8292b182319d2ce86`와 같다. 운영 AWS/RDS 데이터·반복 조회·부하실험·테스트 데이터 삽입은 사용하지 않았다.

## 적용할 최소안

사용자가 계정 선두 인덱스를 기본안으로 확정했다. `participants(user_id, room_id) WHERE user_id IS NOT NULL` B-tree 하나를 추가한다. 기본 전체 목록을 지원하며 guest 행을 저장하지 않는다. 기존 HOST 성능 인덱스와 소유권·방당 주최자·계정별 참여 중복 방지 제약은 유지한다. 새 role 인덱스, 날짜 복제, 조회 테이블, GIN은 추가하지 않는다.

계정 목록의 PreparedStatement에만 `prepareThreshold=0`을 적용해 값에 따라 계획을 다시 선택한다. 커넥션·풀의 기본 설정과 다른 쿼리는 유지한다. 인덱스를 강제로 사용시키지 않으며 다참여 계정에서 순차/해시/정렬 경로를 선택할 수 있다. HOST/MEMBER 필터가 있으면 반환 role은 해당 enum 상수로 투영하되 `p.role = ?` 조건과 바인딩을 그대로 유지한다. 이 조합은 generic 계획의 과도한 개별 방 lookup과 HOST 0건의 잘못 추정된 방 순회 사례를 피하기 위한 것이다.

비용은 매 실행 재계획과 PostgreSQL 드라이버 API 의존이다. 로컬 후속 EXPLAIN의 계획 시간 중앙값은 ALL 약0.33ms, 날짜0.36ms, cursor0.38ms였다. 기존 generic 계획의 해당 후속 측정은 약0.04ms였다. 이는 계측·캐시·로컬 시스템에 의존하는 참고값이고 운영 지연/CPU 또는 처리량 보장은 아니다. 전역 custom plan 설정은 사용하지 않는다.

## 합성 데이터와 관측 방법

실험 DB는 Cloud executor 내부의 `postgres:18-alpine` 컨테이너 `account-room-load-pg`다. Docker 대상은 `unix:///var/run/docker.sock`, 데이터는 로컬 Docker volume의 `/var/lib/postgresql/18/docker`이며 외부 port를 게시하지 않았다. JDBC 실험은 `--network=container:account-room-load-pg`로 같은 network namespace에서 `127.0.0.1:5432/postgres`에 연결했다. psql은 같은 컨테이너 안의 Unix socket을 사용했다. 합성 생성기는 로컬 `benchmark.py`의 `initialize()`, 날짜 구조 snapshot은 `composite-proposal/compare.py`다. 운영 RDS 접속 문자열·자격 증명·데이터는 실험에 사용하지 않았다. 2026-10-29 부모 확인 요청 시 새 실험 쓰기를 멈추고 Docker inspect/DB self-report/Java 연결 설정을 읽어 위치를 다시 확인했다.

격리 Docker PostgreSQL18.6, 실제 V1–V19 스키마에 모임100,000·참여자500,000행을 만들었다. 계정 참여360,100·guest139,900행이다. 계정 분포는5,000×30개,5,000×40개,1×10,100개,1×0개다. 다참여 계정은 MEMBER만 보유한다. 날짜와 참여 여부는 별도 분포이며 제목은 합성 영문·한글·기호 및 희귀 단어다. 결과는 실제 계정/이메일/운영 데이터가 아니다.

초기 SQL EXPLAIN과 실제 JDBC를 구분한다. JDBC는 생산 repository class, Spring JDBC7.0.9·PGJDBC42.7.13·Hikari7.0.2를 호출했다. 각 trial은 풀/물리 연결1개,20회 warmup 후21번째 조회다. local `auto_explain`의 JSON NOTICE를 해당 PreparedStatement에서 수집했다. 실제 호출 뒤의 EXPLAIN EXECUTE가 plan 이력을 바꿀 수 있으므로 실제 query plan과 구분했다. `from_sql=false`와 parameter types로 실제 protocol prepared statement를 확인했다.

아래 buffer 수는 실제 query plan 최상위의 shared hit+read다. 부모·자식 합산이나 고유 페이지/물리 디스크 읽기 수가 아니다. elapsed에는 계측과 NOTICE 처리도 포함되므로 운영 성능 수치로 사용하지 않는다. 행 처리 proxy는 EXPLAIN의 평균 rows×loops로 계산하여 반올림 오차가 있고 join 비교 전체를 의미하지 않는다.

## 계정 인덱스와 준비문 회귀

동일 논리 데이터에서 실제 JDBC를 사용한 관측이다. 단계 사이 heap/visibility와 실행 이력이 달라 아래 단계별 대조를 섞어 인덱스 단독 효과라고 주장하지 않는다.

| 단계·조건 | 일반 ALL | 다참여 ALL | 다참여 HOST 0건 |
| --- | ---: | ---: | ---: |
| 초기 기존 구조/default, 각 계정 반복 | 9,833 | 3,963 | 291,199 |
| 2열 계정 partial/default, 각 계정 반복 | 153 | 41,893 | 1,493 |
| 2열 계정 partial/lookup prepare0, 각 계정 반복 | 153 | 3,963 | 291,199 |
| 최근 쓰기 이후 기존 구조/default | 9,977 | 3,983 | 291,199 |
| 같은 쓰기 이후 최소 복합안 | 124 | 3,983 | 5 |

마지막 대조는 인덱스+조회별 준비문 정책+역할 투영의 복합 변경이다. 일반 HOST는44→45로1 buffer 증가했다. 다참여 희귀 검색3,195→3,195, cursor4,446→4,446이며 모두 개선된다는 주장이 아니다. normal→dense 이력의 기존 ALL9,977→3,983, 날짜38,848→3,274, 빈 계정9,977→3도 관측했다. 가장 큰 정상계정의 개선과 다참여 회귀 회피가 목표다.

추가64회 실제 JDBC probe는 정상·다참여·빈 계정과 HOST/MEMBER·검색값이 같은 풀에 섞이는20회 이력 및 dense→normal 이력을 사용했다. 계정 partial만 있을 때 mixed→dense ALL40,610, 최소 복합안3,983으로 관측했다. 다른 pool 크기·실트래픽 비율·모든 데이터 분포를 대표하지 않는다.

구현 후 실제 변경된 생산 class로32개 혼합 이력을 다시 실행했다. 이 단계의 proxy는 SQL이나 prepare 설정을 바꾸지 않는다. 앞선 prototype과 모든 결과/순서 지문이 같았고, 계정 named statement 부재·connection threshold5·session auto를 확인했다. proxy의 `observedStatementPrepareThreshold=5` 필드는 statement 생성 직후, 생산 Setter 실행 전에 읽은 값이다. 최종 설정값 관측으로 해석하지 않으며 독립 준비문 계약 테스트와 실제 named cache 부재를 동작 근거로 사용한다.

## role·날짜·검색 후보 비교

- **full vs partial 계정 인덱스:** 초기 읽기 효과는 같고22,593,536B→17,932,288B였다. guest를 제외하는 부분 인덱스를 채택한다. 초기 계정 MEMBER1,000 INSERT는root buffers19,281→26,612, WAL records7,068→8,168이었다. 단일 고정 순서 비용 proxy이고 WAL full-page image 차이 때문에 WAL byte/실행 시간 배수를 일반화하지 않는다. DELETE1,000의즉시WAL은 같았지만 이후 VACUUM 비용은 미측정이다.
- **role을 포함하는3열 인덱스:** 명시 쓰기 전 mostly-visible에서 다참여 ALL2,642였으나 참가자10,130행의 nonindexed 컬럼 동일값 갱신 후16,356으로 증가했다. Index Only Scan Heap Fetches7→13,678이며 visibility 의존을 확인했다. 이 갱신은 MVCC/visibility stress이며 실제 인덱스 쓰기 유지비 측정을 대체하지 않는다. 새 role 인덱스를 추가할 이유로 채택하지 않았다.
- **별도 모임 날짜 인덱스:** 실제 JDBC 최소 복합안에 `meeting_rooms(created_at DESC,id ASC)`를 추가한19개 대조에서 다참여 ALL3,983→1,544·날짜3,274→881이었다. 하지만 HOST0건5→316,936·희귀 제목3,195→101,079·cursor4,446→26,333으로 회귀했다. 두 테이블의 인덱스가 JOIN의 정렬을 항상 없애지 않는다. 이번 적용에서 제외한다.
- **trigram:** 초기 `lower(purpose)` GIN과 동일식의 이스케이프된 LIKE를 함께 적용했을 때 희귀 다참여 검색에 이득이 있었지만 일반 계정의 희귀 검색159→342로 증가했다. 추가 인덱스3,932,160B와 쓰기·삭제 유지비가 발생한다. 한글1–2자·흔한 단어·literal %, _, backslash 의미를 고려해 기존 strpos 검색을 유지한다.

## 사용자 제안: 계정·날짜·역할 복합 인덱스

현재 `user_id/role`은 participants, `created_at`은 meeting_rooms에 있다. 하나의 일반 인덱스는 한 relation의 컬럼으로 구성되므로 날짜 복제나 별도 조회 구조가 필요하다. 이 방향은 효과가 있으며, 별도 비민감 snapshot 테이블에서360,100행으로 비교했다. 실제 JDBC 구현·동기화·migration으로 채택한 것은 아니다.

4가지 구조×4계정×11조건×custom/generic=352 SQL PREPARE EXPLAIN과 결과/순서 비교를 수행했다. 새 snapshot heap28,008,448B, 날짜 복합 인덱스약24.4MB다. 기존 participants와 물리 상태가 다르므로 구조 전체의 관측이며 컬럼 순서 하나만의 효과로 해석하지 않는다.

| 후보 | 다참여 ALL custom/generic | 다참여 날짜 custom/generic | 다참여 희귀 검색 custom/generic |
| --- | ---: | ---: | ---: |
| 현 구조 계정2열 인덱스 | 3,983 / 40,616 | 3,274 / 40,616 | 3,195 / 40,616 |
| account,date DESC,role,id ASC | 718 / 718 | 690 / 690 | 40,495 / 40,495 |
| account,date DESC,id ASC INCLUDE(role) | 88 / 88 | 88 / 88 | 40,486 / 40,486 |
| account,role,date DESC,id ASC | 185 / 40,492 | 2,594 / 4,705 | 40,501 / 40,492 |

기본 ALL에서는 date 다음에 role을 키로 끼우면 같은 날짜의 id 정렬과 맞지 않는다. role을 포함 값으로 두고 account,date,id 정렬을 맞춘 구조가 이번 첫 페이지에 더 유리했다. 하지만 역할/제목/보관 조건에서 충분한 결과를 찾지 못하면 여전히 많은 후보를 읽는다. 날짜 범위 뒤 role 조건도 인덱스 내 검사/skip scan에 도움을 줄 수 있으나 항상 스캔 범위를 줄이지 않는다.

날짜 복제는 참여 생성·명시 guest claim·이관/backfill·방 생성일 변경 가능 경계에서 동기화를 요구한다. 별도 조회 테이블은 계정 연결·삭제·30일 보관·권한·중복 제약의 원자적 일치가 추가로 필요하다. collection_status/closed_at은 계속 원본 방으로 확인해야 하며 생성일30일 조건으로 대체할 수 없다. 비동기 refresh는 목록의 즉시성과 권한 일치 문제도 만든다. 현재 정상계정 후보30–40개를 먼저 줄이는 최소안의 이득에 비해 변경/쓰기 부담이 크므로 이번에는 구조를 유지한다.

## 배포 제약과 검증 상태

V20은 일반 transactional CREATE INDEX다. PostgreSQL은 생성 중 writes를 막고 테이블을 읽으므로 운영 부하0을 보장할 수 없다. 기존 승인 전환은 앱 writers를 정지한 maintenance 경계에서 migration을 수행한다. 실제 적용 전 부모가 CI/CD154 복구·전환 완료, 허용 정지 시간과 자원 여유·긴 트랜잭션 및 복구 절차를 조율해야 한다. 이 조사에서는 운영 크기 조회도 하지 않았다.

운영 반영 승인에서 추가된 시간 제한을 미게시 V20에 반영한다. `SET LOCAL lock_timeout='5s'`는 각 잠금 획득 대기, `SET LOCAL statement_timeout='120s'`는 인덱스 생성 statement 실행에 적용된다. 잠금을5초만 보유한다거나 전체 배포가120초 안에 끝난다는 의미는 아니다. 제한 초과 시 migration transaction을 롤백하고 guard의 기존 maintenance/recovery 상태에서 원인을 검토한다. 시간 제한을 자동으로 늘리거나 무제한 재시도하지 않는다. 실제 테스트는 DDL event trigger로 제한값을 관측하고, 별도 blocker에서55P03·history/index/data rollback·blocker 해제후 retry를 확인한다. 같은 물리 pool session에서 설정이0으로 돌아오는 것도 검증했다. PostgreSQL의 [statement/lock timeout](https://www.postgresql.org/docs/18/runtime-config-client.html)과 [SET LOCAL의 transaction 범위](https://www.postgresql.org/docs/18/sql-set.html)를 따른다.

CONCURRENTLY는 쓰기 차단을 줄이지만 추가 scan/대기·I/O가 있으며 transaction block에서 실행할 수 없다. 실패한 invalid 인덱스 처리와 기존 Flyway/배포 계약을 별도로 설계해야 한다. 이번 변경에 무조건 붙이지 않는다. 새SQL/정확 migration catalog가 schema·guard 지문을 바꾸므로 V19에서 곧바로 AUTO_READY라고 가정하지 않는다. 보호 규칙·승인 우회·수동 운영 적용을 수행하지 않았다.

API, KST 생성일 경계, 대소문자 무시 literal substring, created_at DESC/id ASC keyset, 계정 소유권과 CLOSED closed_at/COLLECTING created_at30일 의미는 그대로다. 독립 기능/준비문·migration 테스트, 결함 주입과 최종 품질 검증 결과는 완료 후 아래에 추가한다. 기존 archive의2244 PASS/4SKIP는 초기 인덱스 후보 결과이며 새 JDBC 변경의 검증으로 사용하지 않는다.

## 공식 근거

- [PostgreSQL multicolumn index](https://www.postgresql.org/docs/18/indexes-multicolumn.html): 선두 equality·이후 range·후속조건과 skip scan.
- [Index ordering](https://www.postgresql.org/docs/18/indexes-ordering.html): ORDER BY/LIMIT와 정렬 방향·컬럼 순서.
- [CREATE INDEX](https://www.postgresql.org/docs/18/sql-createindex.html): relation 범위, 잠금, CONCURRENTLY 및 transaction 제약.
- [pgJDBC server preparation](https://jdbc.postgresql.org/documentation/server-prepare/): 기본 threshold5, statement 단위0 설정과 plan tradeoff.
- [PREPARE](https://www.postgresql.org/docs/18/sql-prepare.html), [auto_explain](https://www.postgresql.org/docs/18/auto-explain.html), [pg_trgm](https://www.postgresql.org/docs/18/pgtrgm.html).

## 잠금 예산 보완 전 로컬 검증

2026-10-29 UTC 원본 작업 브랜치에서 `ktlintCheck assemble test`가9분37초에 성공했다. 267 suites/2250건 중2246 PASS·기존4 SKIP·실패/오류0이다. 집중30·Node180·배포 Python311도 PASS다. 독립 새 준비문 테스트의 유효 RED1/control1, 준비 설정 제거·잘못된 role 투영 FI2가 각각 예상 assertion을 탐지했으며 exact restore 후각2 PASS다. 독립 검토 P1/P2는0이다. 이전 동일 V20의 migration FI2는 SQL/테스트 SHA 일치로 범위를 한정해 재사용했다.

최종 검증 기록은 `.tdd/verification/issue161-account-room-db-load.json`, 독립 검토는 `.tdd/reviews/issue161-account-room-db-load.json`이다. 실제 JDBC 전체283개 관측의24 workload/user 키별 rowCount·순서 지문 불일치는0이다. 원격 소스 게시·PR·병합·운영DB 변경은 수행하지 않았다.

## 최신 develop 통합과 게시 준비

부모의 커밋·원격 게시·PR·CI 승인 후 develop `bccf36e405536c689f03b33ff5768030bb058d73`(CI복구 PR162)을 반영했다. 최초4bc1170 기준 패치·전체 검증은 별도로 보존한다. 생산 Kotlin/DB migration/독립 테스트는 같은 SHA이며 보류 PR158은 ancestry에 포함되지 않는다. upstream transport 복구 파일은 최신 develop 그대로다. 원본 commit guard는10.36초에 exit0이며 기존 동일 Kotlin 검증 결과를 재사용할 수 있는 상태다. 추가한 V20 catalog 테스트도 CI의 기존 release catalog step에 연결했고 Node192건이 통과했다. 통합 후 schema 지문은66fbc6bf7bc6fd42e7e720ac18257f8443574fed583c6e7846c6de472f242a45, guard 지문은5aff722d6cd0a9a75bdc9d1ede81650565206aef7ab8482329c704e6081b6a29다. 최신 배포 Python330건이437.184초에 통과했다. 후속 운영 승인에서 요구한 잠금·실행 시간 제한을 보완하고 추가 검증 중이며 실제 운영 적용은 부모 조율 전까지 대기한다.

## 운영 스키마 확인 준비

CI/CD 복구 완료와 부모 조율 후 승인된 runtime DB에서 [metadata-only SQL](sql/issue161-account-history-index-readonly.sql)을 한 번 실행해 신규 계정 인덱스와 기존 HOST/unique 인덱스의 존재·valid/ready/live·정의·predicate 및 Flyway20 script/checksum/success를 대조한다. 이 SQL은 개인 계정·방·참여 행을 읽지 않는다. 아직 운영에서 실행하지 않았으며 스키마·runtime·이미지·자동 계약 검증을 완료했다고 주장하지 않는다.

운영 확인의 기대값은 다음과 같다. 네 인덱스 모두 participants에 존재하고 valid/ready/live가 true여야 한다.

| 인덱스 | 키 | unique | predicate |
| --- | --- | --- | --- |
| ix_account_rooms | user_id, room_id | false | user_id IS NOT NULL |
| ix_account_host_rooms | user_id, room_id | false | role = HOST |
| uq_room_host | room_id | true | role = HOST |
| uq_participant_room_user | room_id, user_id | true | 없음 |

시간 제한을 포함한 최종 미게시 V20의 Flyway checksum은 `-881458142`, SQL SHA256은 `99062f8dfb8bbca178c204be4eb5a297e99868f4193b290dbb3f4261d711cc8a`다. 이 단계의 schema 지문은 `615e197aedf104d52c2ef3ed71e6136b036e7b258e24fb27301f866fe19b0243`, guard는 `5aff722d6cd0a9a75bdc9d1ede81650565206aef7ab8482329c704e6081b6a29`다. 앞선 시간 제한 없는 V20의 checksum/지문은 이 단계에 사용할 수 없다. Flyway20행은 해당 script·checksum·success=true와 일치해야 한다.

새 BudgetPostgresTest는 유효 RED2→GREEN이다. lock/statement budget0, index생략, guest display 변조 FI4를 독립 작성자가 각각 예상 assertion으로 탐지했고 매번 SQL을 exact restore했다. 관련7개·ktlintCheck PASS, Node192 PASS, 변경 영향이 있는 Python publication8개 PASS다. 직전 최신 develop 통합 Python330 PASS는 시간 제한 추가 전 결과로 구분한다. 120초 실행을 강제로 지속하는 부하시험은 하지 않았으며 실제 DDL에서 설정값을 관측하고 누락 결함을 탐지했다. 새 전체 commit guard 결과는 아래 후속 기록에 연결한다.

## 최종 게시 전 검증 (시간 제한 포함 V20)

최신 develop `bccf36e405536c689f03b33ff5768030bb058d73`에서 원본 `.codex/hooks/tdd_guard.py`를 실제 commit 이벤트로 실행했다. guard exit0,638.59초이며 `ktlintCheck assemble test` 결과 XML은268 suites/2252건 중2248 PASS·기존4 SKIP·실패/오류0이다. 최종 V20 SHA99062f·checksum-881458142와 독립 테스트 지문을 다시 확인했다. 새 기록은 `.tdd/verification/issue161-publication-final.json`이며 최초4bc/시간 제한 전2250건 기록은 역사 근거로 보존한다.

원격 게시·PR와 정확 HEAD CI는 이 검증 뒤 진행한다. 운영 반영은 승인되었지만 CI/CD 복구 완료와 부모의 직렬 배포 조율, 기존 승인 전환 및 실제 metadata 검증을 완료하기 전에는 운영 성공으로 표시하지 않는다.

## 첫 원격 CI의 컴파일 메모리 보완

[PR164의 첫 CI40809811773](https://github.com/meet-me-duo/meet-me-server/actions/runs/40809811773)는 ARM64 이미지·이슈 연결을 통과했으나 Quality Gate의 compileTestKotlin에서 GC overhead OutOfMemoryError로 실패했다. 테스트 단언 실패가 아니며 이후 검사는 실행되지 않았다. 로컬 전체 검증에서 이미 사용한 Gradle/Kotlin heap2GB·workers2를 CI 명령에도 명시한다. 필수 ktlintCheck/assemble/test·배포 검사·권한·승인 조건은 유지한다. [Kotlin 공식 문서](https://kotlinlang.org/docs/gradle-compilation-and-caches.html#setting-kotlin-daemon-s-jvm-arguments)의 daemon JVM argument 설정을 따른다. CI/CD 운영 전환 복구 코드와 operating runtime 설정은 변경하지 않는다.

## 운영 완료된 CI/CD main 통합

부모가 main bb07f3b/배포40819827692의 DEPLOYED_VERIFIED를 확인하고 직렬 대기를 해제했다. 해당 main을 작업 브랜치에 정상 merge했으며 진행 문서만 양쪽 기록을 보존해 해결했다. V20/JDBC/독립 테스트는112c4675와 동일하고 upstream 복구 진단·배포 workflow를 그대로 보존했다. 원본 commit guard15.55초 exit0(동일 Kotlin 전체 결과 재사용), Node192 PASS 및 독립 읽기 검토 P1/P2=0이다. 보류158은 어느 부모의 ancestry에도 없다.

이 통합 단계의 schema는 `615e197aedf104d52c2ef3ed71e6136b036e7b258e24fb27301f866fe19b0243`, guard는 `825cf18d7c6b622e08df3aa251d3687f9c7cfd4c625ffbbe513e25307018113d`이며 V20 checksum은 -881458142다. 앞선 guard5aff722d는 최초 PR 검증 단계의 역사값이다. 정확한 새 HEAD CI 후 develop/main 및 기존 승인 전환을 진행하고 runtime·스키마·공개 계약·규칙 복원을 확인한다. 운영 성공은 아직 미확인이다.
