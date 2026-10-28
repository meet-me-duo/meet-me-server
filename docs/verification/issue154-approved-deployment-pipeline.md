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
