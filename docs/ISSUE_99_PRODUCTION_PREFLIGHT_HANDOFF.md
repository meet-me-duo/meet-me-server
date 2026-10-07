# Issue #99 운영 읽기 preflight 실행 인계

## 고정 기준과 승인 경계

실제 공급자·웹 공동 평가는 서버 `6f7f54e46846c690a8374aa7dc1898bee21d873f`, 웹 `8e56fe0c37cef52e584ff569b97b46b60f28e517`에서 3시나리오·4분석을 통과했다. 자세한 측정과 비용의 한계는 [실제 평가 기록](ISSUE_99_LIVE_EVALUATION.md)을 따른다. 후속 문서/조회 HEAD를 새로운 유료 평가 SHA로 표시하지 않는다.

사용자는 기존 GHA 역할/SSM을 통한 운영 읽기와 이후 보호장치·드레인·서버→웹 작업을 승인했다. 실제 중단 전에는 정확한 운영 대상과 시작 조건을 부모 세션에 보고한다. 현재는 읽기 준비 단계이며 main/develop 병합, 배포, 운영 migration, 서비스 중단, 자동화 중지, 비밀값 조회/등록은 실행하지 않았다. 새 IAM·토큰·OS 설정·비용 또는 예상 밖 migration이 필요하면 확대하지 않고 보고한다.

## 접근과 실행 방법

선택 실행환경의 직접 GitHub REST는 프록시403으로 차단됐고 연결 도구에는 workflow dispatch가 없다. AWS CLI/직접 AWS 자격증명도 없다. 저장소에는 기존 production OIDC 역할의 ReadOnlyAccess와 특정 runtime instance의 AWS-RunShellScript 권한이 선언되어 있지만 실제 적용은 아직 확인하지 않았다.

기존 Issue99/Draft PR100의 `feature/integrate-recommendations-fallback`에서 `.github/workflows/production-preflight.yml`을 준비했다. 동일 저장소 PR100·develop base·고정 feature·기존 작성자/실행자/재실행자만 허용하고 현재 PR SHA를 AWS 권한 취득 전에 검증한다. production 환경과 기존 역할/instance 변수를 사용한다. 브랜치 push의 PR 이벤트로 실행하며 권한 거부나 환경 승인은 우회하지 않는다.

조회는 EC2/RDS metadata, 두 credential refresh EventBridge rule/target, 진행 중 SSM command, 현재 image digest/guard 정책·파일 신뢰/phase/unit, Flyway history 및 공개 health/OpenAPI HTTP 상태로 제한한다. 운영 OpenAI 위치는 기존 prefix의 `/meet-me/production/secret/openai-api-key`를 제안하며 ParameterStore Name/Type/Version만 확인한다. 존재 여부는 아직 미확정이다. 값은 사용자 보안 입력이며 시험환경에서 복사하지 않는다. 현재 runtime env renderer에는 OpenAI 전달이 구현되지 않았으므로 parameter 존재만으로 Luna 준비 완료를 선언하지 않는다.

Flyway는 현재 컨테이너의 기존 PG driver와 독립 Java reader로 읽는다. child JVM은 bounded timeout/heap 및 임시 디렉터리 정리 아래 실행하며 Spring main을 시작하지 않는다. DB 서버의 READ ONLY transaction을 확인하고 history의 version/script/checksum/success만 조회한 뒤 rollback한다. 비밀값·환경 전체·예외 본문·원시 SSM 출력은 로그/artifact에 남기지 않는다. container PID/restart 상태의 전후 metadata도 수집한다.

## 로컬 근거와 미완료 항목

- 독립 20개 검사: RED6 assertion → GREEN20, skip0. 기존 배포 정책13 통과.
- 결함3개(rollback/출력 schema/재실행자 누락)를 assertion7개로 탐지하고 관련6파일을 정확 복원했다. `.tdd/verification/issue-99-production-preflight.json`과 독립 리뷰를 따른다.
- 실제 로컬 격리 PostgreSQL18.6 및 기존 bootJar PropertiesLauncher에서 reader의 성공/정확한 fixture history를 확인했다. 이는 운영 DB 조회 증거가 아니다.
- 제품 src/main·src/test 및 V1~V10 migration은 실제 평가 SHA와 동일하다. 새 HEAD의 전체 guard/CI 및 실제 AWS 조회는 별도로 확인한다.
- 이 문서를 작성한 시점의 실제 운영 조회 성공은0이다. Workflow 실행 뒤 exact SHA/run URL과 결과를 부모에게 먼저 인계한다.

조회가 성공해도 배포 준비 완료를 자동 판정하지 않는다. 실제 Flyway가 예상 V8 이하인지, 충돌 V9 invocation/예상 밖 migration이 없는지, 현재 digest와 guard/단위 상태, 두 rule의 실제 target/state, 진행 중 command와 key 위치를 확인한다. 예상 밖 migration은 즉시 중단·보고하며 repair/번호 변경하지 않는다.

새 image digest는 build 후에만 확정된다. 현 CD는 build와 deploy 사이 독립 승인 단계가 없으므로 preflight·키 전달·정확 digest 승인 순서를 확정하기 전에 main을 merge하지 않는다. V9/V10 적용 후 오래된 V8 image 복구를 허용하지 않으며 policy의 호환 label만으로 새 migration 호환성을 보장하지 않는다. 서비스 중단/guard 설치/automation pause·drain/DB 적용은 부모의 실제 시작 보고 이후 단계다.

새 공개 Issue 생성은 내부 운영 접근 metadata 공개를 사용자가 승인하지 않았다는 자동 승인 검토로 거절됐다. Issue는 생성되지 않았고 재시도하지 않았다. 이 인계는 이미 승인된 기존 Issue99/PR100의 코드·문서 범위에서 비밀값 없이 유지한다.
