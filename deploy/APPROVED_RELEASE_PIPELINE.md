# 승인 전환과 실제 배포 완료

Issue #154 / ADR-063. 일반 배포는 main의 정확한 CI 이후 실제 운영 검증까지 자동으로 진행한다. SQL/guard 계약이 다른 특별 전환은 pipeline의 실제 승인 후 계속하며, 개인 PC의 AWS CLI를 정상 배포의 마지막 단계로 요구하지 않는다.

## 정상 경로

1. **Build and Publish**가 성공한 main CI의 정확한 SHA로 ARM64 image와 불변 run/attempt manifest·archive를 게시한다.
2. **Classify Published Release**가 현재 host·전체 DB 이력·source/image/guard·필수 parameter metadata를 읽어 분류한다.
3. AUTO_READY이면 **Automatic Runtime Deployment**가 명령을 한 번 제출하고 실제 적용을 검증한다. 이 경로는 두 갱신 규칙을 중지하지 않는다.
4. TRANSITION_REQUIRED이면 기존 보호를 실제로 확인한 **Approve production transition** job이 승인을 기다린다. 아직 규칙을 중지하지 않는다. 승인 전용 job은 AWS 권한이 없다.
5. 같은 source/manifest/run/attempt의 실제 승인 기록을 검증한 **Approved Runtime Transition**이 규칙 원래 상태 기록·중지·drain·fresh 사실 검증·deploy/resume·검증·안전한 원상 복원을 진행한다.
6. **Verify Actual Deployment Completion**은 원래 규칙 상태·활성 명령 종료와 runtime·전체 DB·공개 API 증명을 모두 확인한 결과만 성공으로 인정한다. artifact 외에도 안전한 로그 marker와 Step Summary에 결과를 남긴다.

게시 성공, CI 성공, 코드 이슈 종료와 실제 운영 완료는 서로 다른 상태다. 의도한 읽기 전용 preflight/진단은 운영 완료로 표시하지 않는다.

## 실제 승인 경계

`deploy/transition-approval.json`은 비밀 없는 확인된 environment/reviewer ID와 gate job 이름이다. 실제 환경의 required reviewer·main-only branch policy와 동일 run의 승인/게시 job·승인 이력을 다시 확인한다. 선언·입력 boolean·성공한 job 하나만으로 승인했다고 판단하지 않는다.

초기 승인 계약은 attempt1만 지원한다. GitHub 승인 이력이 attempt별 정보를 주지 않으므로 재실행 attempt에서 과거 승인을 재사용하지 않는다. gate가 UNCONFIGURED이거나 live 보호가 누락되면 전환 전에 중단하고 새 무보호 environment를 참조하여 자동 생성하지 않는다.

기존 production/OIDC 신뢰는 유지한다. 새 승인 환경은 main-only, 확인된 기존 사용자 reviewer, 관리자 우회 금지, 단독 운영을 위한 self-review 허용이다. PC 담당이 별도 구체 승인으로 적용·재조회했으며 저장소 코드 작업이 그 실제 적용을 대신하지 않는다.

IAM의 `ManageMeetMeCredentialRefreshRules`는 기존 `meet-me-production-db-credential-reconcile`, `meet-me-production-db-credential-rotated`의 EnableRule/DisableRule만 허용한다. 다른 rule·target·IAM·키·운영 AI flag 변경은 이 기능의 권한이 아니다.

## 완료 증명과 공개 API 계약

완료 marker는 엄격한 identity·타입·허용 필드만 포함하며 sourceSha, build run/attempt, 실행 workflow run/attempt, releaseId, image digest를 묶는다. 기존 deployed=true 의미와 별도로 deploymentComplete를 판정한다.

- APPLIED: 검증된 명령/host 응답과 fresh runtime·전체 이력 증명이 필요하다.
- UNKNOWN: 명령 전송 응답 유실·timeout·취소·불명확 상태이다. false 또는 미실행으로 바꾸지 않는다.
- 원래 규칙 상태를 확인하지 못했거나 active-command 수를 읽지 못하면 완료가 아니다.
- 공개 health200만으로 완료하지 않는다. candidate archive에 묶인 `public-api-contract.json`의 operation/path/operationId와 schema 자체 property 이름을 실제 native OpenAPI와 비교한다.

공개 계약 데이터는 실제 Spring native 명세를 독립 PostgreSQL/Redis 테스트에서 생성·비교한다. 예제·사용자 데이터·server URL·키를 포함하지 않는다. 이는 operation/property 이름의 형태 증명이며 모든 응답 타입·보안 의미·실제 로그인/리뷰 제출의 검증을 뜻하지 않는다. runtime의 정확한 image/source와 전체 DB 이력 증명도 별도로 필요하다.

공개 계약은 실행 코드나 배포 권한 정책이 아닌 읽기 전용 기대 데이터다. archiveSha로 불변 릴리스에 묶되 compatibility guard 지문을 이 데이터만으로 변경하지 않는다. API만 바뀐 일반 배포를 불필요한 DB maintenance로 바꾸지 않기 위해서다. 반면 실행 의존 모듈과 승인 정책은 완전한 guard catalog에 포함하며 과거18/17/14 catalog의 명시적 읽기 지원은 유지한다.

## 중단과 복구

명령 전송 전에 durable intent를 기록하고 받은 commandId를 보존한다. 동일 operation의 새 실행이 원래 owner를 임의로 탈취하거나 lease 시간만으로 재제출하지 않는다. 원래 DISABLED였던 규칙을 ENABLED로 바꾸지 않는다.

실패 후 `finally: enable both`, 구 image 자동 재기동·rollback, Flyway history 삭제/수정은 하지 않는다. 안전한 READY와 실제 명령 종료를 확인한 경우에만 원래 규칙 상태로 복원할 수 있다. 공개 API 확인만 실패했다면 host가 안전한 경우 규칙은 복원할 수 있지만 결과는 DEPLOYED_UNVERIFIED이며 완료 false다. 복원 실패도 RESTORATION_REQUIRED로 남긴다.

읽기 전용 operation 진단은 원래 journal/identity·known command를 조회하고 fresh host proof를 확인하는 경로다. 명령 재제출·취소·owner takeover·규칙 변경 권한을 주지 않는다. command ID가 없거나 결과가 불명확하면 RECOVERY_REQUIRED를 유지하고, 구 writer를 시작하거나 임의 수리를 실행하지 않는다. 기존 archive에 공개 계약 데이터가 없으면 읽기/사전점검과 full-completion 지원을 구분하며 증명을 만들어 성공시키지 않는다.

## 검증과 rollout

원본 테스트·SQL·host lock/fence를 보존하고 독립 RED/GREEN·소유권/UNKNOWN/복원/승인/metadata/공개 계약 결함 주입을 수행한다. 필수 Gradle gate, Python·Node·workflow 검사, 정확한 PR/main CI를 통과해야 한다.

현재 구현의 실제 검증·게시·배포 상태는 implementation_plan.md와 해당 PR의 정확한 SHA/run 근거를 따른다. 보안 설정 적용만으로 새 코드의 운영 배포를 완료 표시하지 않는다. 다른 승인된 서버 작업과 main 운영 배포를 직렬화한다.

