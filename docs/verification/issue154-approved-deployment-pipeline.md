# #154 승인 전환과 실제 배포 완료 검증

일반 배포는 main CI에서 실제 운영 확인까지 자동으로 연결하고, 특별 전환은 같은 pipeline의 실제 사용자 승인 뒤 규칙 중지·drain·배포·검증·안전한 복원으로 계속한다. source/run/attempt·manifest·실제 환경 보호를 검증하며 미구성 환경은 참조하지 않는다. 원본 SQL·host lock/fence·명시 전환 조건은 유지한다.

## 검증 결과

- 원본 commit guard: ktlintCheck·assemble·test exit0. 265 suites / 2,245건 중 **2,241 PASS·기존 4 SKIP**, 실패·오류0. 관련 입력571개 전후 지문 동일.
- 배포 Python **304/304 PASS**, 전체 Node **172/172 PASS**. 기존 Java probe 검증도 제공된 JDK17로 실행했다.
- 독립 신규 경계 **200/200 PASS**, 최종 감사 미해결 P1/P2 없음. 승인/완료·adapter·공개 계약·controller/준비·최종 CLI의 격리 논리 결함 **55/55 탐지**, 원본 복원 후 재통과.
- 실제 Spring·PostgreSQL·Redis native 명세를 생성·대조했다. operation 수가 같아도 내 모임 API의 role·has_more·next_cursor 속성 변경을 RED로 탐지하고 실제 export를 반영해 재통과했다. 이 데이터는 operation/property 이름의 형태 증명이며 모든 타입·보안 의미의 동등성을 주장하지 않는다.
- 실제 source archive·publisher·읽기 JVM probe의 로컬 smoke와 기존 SSM 명령 크기 제한도 통과했다. AWS 전송은 mock이며 실제 운영 실행으로 확대하지 않는다.

## 보존 및 실패 의미

원래 current18 기대값은 새 실행 의존성23에 맞춰 독립적으로 확장했다. 원본 bytes/SHA와 정확한 변경 이유는 `.tdd/history` 및 아래 supersession에 보존했다. 과거14/17/18 archive와 V1–V19 SQL·승인·규칙 중지·pending0·비밀 보호·파일 권한·UID10001 부정 검사는 유지했다. 초기 오래된 current fixture·필수 readonly DATA 누락·JDK PATH 실패는 최종 결과에서 숨기지 않고 교정 근거와 분리했다.

명령 전송 응답 유실은 UNKNOWN이며 재제출·무조건 enable·구 writer rollback을 하지 않는다. 실행하지 않은 job의 APPLIED 증명이나 mode/action 출처 모순은 거부한다. 실제 APPLIED 후 executor가 실패·취소된 경우에는 관측된 효과와 증명 값을 보존하면서 전체 완료를 실패로 표시한다. 읽기 전용 reconcile은 원래 journal과 알려진 명령을 조회할 뿐 소유권 이전·규칙 복원·재배포를 수행하지 않는다.

## 운영 적용과 한계

구체 승인된 두 규칙 제어 권한·새 보호 환경은 별도 PC 담당이 적용·재조회했다. Cloud의 AWS·IAM·환경 설정 중복 변경은 없다. 기존 production/OIDC는 유지하며 gate source에는 확인된 비밀 아닌 ID만 반영했다.

이 문서의 결과는 로컬 코드·합성 경계 검증이다. 새 PR/main CI, 실제 runner의 live gate 접근·승인 기록과 첫 전환, 실제 runtime/DB/API·규칙 복원은 해당 실행 증거로 별도 확인해야 한다. 기존 일반 자동 배포 run40718457774의 성공은 별도 운영 회귀 기준이며 #154 코드 rollout 성공으로 표시하지 않는다. 사용자 로그인·실제 리뷰 제출·실제 provider 호출은 이 작업에서 수행하지 않았다.

## 근거

- [최종 검사·소스 SHA](../../.tdd/verification/issue154-final.json)
- [독립 RED](../../.tdd/deployment/issue154-independent-red.json), [core·catalog FI](../../.tdd/verification/issue154-independent-fi.json)
- [current catalog 변경](../../.tdd/supersessions/issue154-catalog.json), [Python fixture 보완](../../.tdd/supersessions/issue154-current-python-fixtures.json), [Node fixture 보완](../../.tdd/supersessions/issue154-current-node-fixtures.json)
- [실제 native 계약](../../.tdd/verification/issue154-native-public-api-myrooms.json), [운영 pipeline](../../deploy/APPROVED_RELEASE_PIPELINE.md)

## 운영 전 공개 경로·앱 건강 보완

운영 읽기 대조에서 공개 Actuator 경로가404인 반면 선언된 Nginx healthz가 정확한200·ok개행이라는 차이를 발견했다. 이전 합성 fixture가 잘못된 공개 경로를 가정했다. 독립 테스트를 실제 공개 계약으로 고정하고 원본·SHA·supersession을 보존했으며 유효 RED28개에서 실패 assertion38개·설정 오류0을 확인했다. 또한 최종 host 증명과 규칙 복원이 unhealthy/starting/미확인 앱을 허용하는 결함을 재현하고, 해당 최종 경로에만 fresh healthy 조건을 추가했다. 앱이 중지된 maintenance-resume 사전점검은 유지한다.

보완 후 전체 배포 Python311/311·Node172/172·원본 commit guard를 통과했다. 독립94개 GREEN·격리 결함 주입15/15탐지이며 미해결 P1/P2는 없다. 수정한 실제 공개 검증 함수도 기존 서비스에 읽기 전용으로 실행해 통과했다. 이는 새 릴리스의 runtime·DB·운영 전환 증명이 아니다. [보완 최종 근거](../../.tdd/verification/issue154-public-health-final.json)와 [독립 검토](../../.tdd/reviews/issue154-public-health-independent-review.json)를 따른다.

## 실제 승인 전환 후 규칙 제어 응답 보완

배포40795593214는 실제 보호 환경 승인 후 전환 전제조건에서 실패했다. 전환·최종 로그는 runtime NOT_ATTEMPTED, rulesRestored true, pendingCommands0이며 최종 결과는 RECOVERY_REQUIRED다. 같은 작업을 재실행하지 않았다. AWS 공식 EnableRule/DisableRule의 정상 출력 없음 계약을 JSON 오류로 거부하는 코드 결함을 재현했다. 실제 호출·journal·lock 대조는 인증된 읽기 확인 전까지 원인 상관관계의 미확인 범위로 남긴다.

기존 경계 검사가 첫 수정의 위치 오류를 탐지하여 읽기 전용 preflight를 원본으로 복구하고 승인된 전환 모듈에만 응답 처리·안전 진단을 배치했다. 기존 검사는 수정하지 않았다. 새 독립 RED19개를 고정한 뒤 전체 Python330·Node172·원본 commit guard, 독립 원본 Python50·Node29 및 격리 결함 주입21/21을 통과했다. 빈 읽기 응답·오류 종료·타임아웃·예상 밖 출력은 계속 실패하며 성공 응답도 정확한 규칙 재조회를 대신하지 않는다. 최초 수정의 이력과 판정 철회는 보존했다.

[최종 검증](../../.tdd/verification/issue154-aws-control-final.json)과 [독립 검토](../../.tdd/reviews/issue154-aws-control-transport-independent-review.json)를 따른다. 보완의 원격 CI·운영 반영은 별도이며, 이전 작업 종료와 현재 규칙·lock을 확인하기 전 새 runtime 전환을 시작하지 않는다. 입력→분석→결과 로직은 포함하지 않는다.

## OIDC 제한 복구 진단과 이전 작업 안전 검사

기존 reconcile v1 판정은 그대로 두고 journal·owner의 검증된 제한 필드를 별도 출력한다. 분류의 gate 출력 전과 실행의 claim 전 모두 미확정 이전 작업을 차단한다. namespace 건수 모순과 미조회 journal을 실제 부재로 잘못 표시한 경우도 독립 RED 후 수정했다. 실제 비밀값·holder·ETag·commandId·원본 JSON은 출력하지 않는다.

최종 관련 Python267/267, 전체 Node288/288, 원본 commit guard가 통과했다. 전체 Python369/369는 마지막 namespace 표시 사례2개 추가 전 실행이며, 이후 변경 모듈과 새 사례를 포함한267개를 다시 통과했다. 기존65개 테스트 지문은 동일하다. Node 첫 실행의 JDK PATH 누락은 환경을 바로잡아 통과했으며 기대값을 수정하지 않았다. 독립41개와 결함 주입 결과는 [최종 검증 기록](../../.tdd/verification/issue154-recovery-inspection-final.json), [독립 검토](../../.tdd/reviews/issue154-recovery-inspection-independent-review.json), [결함 주입](../../.tdd/verification/issue154-recovery-inspection-independent-fi.json)을 따른다.

이 결과는 로컬 코드 검증이다. 현재 운영 host/DB의 이전 읽기 증거와 신규 제한 journal/owner 관측을 혼동하지 않는다. main 반영 후 기존 action=reconcile을 정확한 원래 build/run/operation으로 실행해야 하며, UNKNOWN·미해제 owner·조회 실패는 운영 재실행 허가가 아니다. 추가 권한·잠금 변경 없이 기존 OIDC를 사용한다. 분석 PR158과 DB 최적화 PR164는 포함하지 않는다.
