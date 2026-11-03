# 운영자 설정 전달 계약

## 조회·감사 계약

현재 인증된 `CurrentAccountPort`의 accountId와 명시적 허용 목록만 운영자 근거다.
HOST·회원·게스트·이름·이메일·요청의 ID는 권한을 부여하지 않는다. 잘못된 Bearer는 401,
인증 기반 장애는 503이며 guest로 fallback하지 않는다. 인가는 대상 UUID·페이지 조건 검증과 비즈니스 조회보다 먼저 수행한다.

| 읽기 경로 | 범위 |
| --- | --- |
| `GET /api/operations/rooms` | 현재 방 설정·상태 요약 페이지 |
| `GET /api/operations/rooms/{roomId}` | 해당 방과 과거 버전을 포함한 실행·수정 라운드 요약 |
| `GET /api/operations/invocations/{invocationId}` | 해당 실행에 소속된 승인 시도·공급자별 사용량 |

응답 DTO와 오류의 정확한 필드는 실행 중인 OpenAPI를 따른다. 페이지 크기는 기본20·1~100,
timestamp+UUID 내림차순 keyset과 불투명 cursor를 사용한다. cursor는 권한 증명이 아니다.
모든 응답은 오류를 포함해 `Cache-Control: no-store`다. 원문·공급자 응답·owner token·URL/query·헤더·secret은 노출하지 않는다.
인가 거부 시 대상 존재 여부를 조회하지 않으며 잘못된 입력과 DB 예외 원문을 응답·감사에 복사하지 않는다.

보호 데이터 반환 전 별도 트랜잭션으로 최소 접근 감사를 내구 저장한다. 감사 실패는 503이며 데이터를 반환하지 않는다.
감사의 필드는 서버 생성 id·시각, 검증된 actor/target UUID 또는 null, 고정 route·outcome뿐이다.
방·계정 FK로 기존 삭제를 막지 않는다. `audit-retention-days=30`, `audit-cleanup-batch-size=100`,
`cleanup-enabled=true`, 정리 주기 `PT1H`를 기본으로 하며 cutoff보다 엄격히 이전인 행만 제한 삭제한다.
운영 조회에는 방·제출·시도·Outbox 수정·재분석·삭제 명령을 추가하지 않는다.

미확인은 null, 알려진 사용량은 소계로 표시한다. V10 승인 기록이나 V15 parser dispatch·local call 관측은
공급자 수신·청구·실제 HTTP 건수·exactly-once 증명이 아니다. `RECORDED_ADMISSIONS` 분모,
현재 방 설정과 과거 실행 snapshot, 승인·dispatch·게시 성공을 구분한다.
웹은 요청마다 현재 메모리 token·세션 revision을 사용하고 로그아웃·계정 변경 시 보호 cache와 늦은 응답을 폐기한다.

## 설정 전달

이 변경은 기존 읽기 전용 운영 조회의 설정 전달만 보완한다. 인가 서비스, 계정 role, 로그인, 입력·분석·결과 로직은 변경하지 않는다. 실제 계정 UUID는 저장소·PR·일반 로그에 기록하지 않는다. 테스트의 UUID는 모두 합성 값이다.

| Parameter Store 상대 경로 | 타입 | runtime 환경 변수 | 동작 |
| --- | --- | --- | --- |
| `config/operations-enabled` | `String` | `MEETME_OPERATIONS_ENABLED` | 정확한 `true` 또는 `false`; 기본 `false` |
| `secret/operations-operator-user-ids` | `SecureString` | `MEETME_OPERATIONS_OPERATORUSERIDS` | 활성화된 경우에만 읽는 쉼표로 구분된 서로 다른 소문자 정규 UUID 1~2개 |

기본 root는 `/meet-me/production`이다. Spring의 기존 `meetme.operations.enabled`와 `meetme.operations.operator-user-ids`에 바인딩한다. 애플리케이션은 기본 OFF·빈 집합을 유지하며, 실제 인증된 accountId의 명시적 집합 소속만 허용한다. HOST·일반 회원·게스트에게 자동 권한을 주지 않는다. 초기 renderer는 당시 승인된 한 계정에 맞춰 단일 UUID를 허용했다. 후속 두 계정 승인에 따라 동일 SecureString에서 쉼표로 구분된 고유 UUID 1~2개를 허용한다. 빈 항목·중복·세 번째 값·공백·제어문자는 허용하지 않는다. 계정 수를 조회해 자동으로 권한을 부여하지 않는다.

활성화 parameter의 AWS 조회가 실패하면 OFF를 출력한다. 조회에 성공한 응답은 정확한 이름·타입·값, UTF-8 JSON 단일 문서, 중복 key 없음과 64KiB 상한을 검증한다. 잘못된 성공 응답은 기존 env를 교체하지 않고 실패한다. `true`일 때 UUID의 누락·조회 실패·중복·범위 초과·공백·제어문자·잘못된 형식도 같은 방식으로 실패한다. OFF일 때는 UUID parameter를 읽지 않고 UUID 환경 변수도 출력하지 않아 이전 env의 허용 목록을 제거한다.

원자 교체·0600 파일 권한·실패 시 임시 파일 제거와 로그 비노출을 유지한다. OAuth 필수 release에만 새 설정을 적용하며 marker 없는 역사 release의 동작은 보존한다. 비활성화는 `operations-enabled=false`를 정상 전달하는 방식으로 수행한다. 활성화 상태에서 UUID만 제거하면 렌더링이 실패하여 기존 파일과 실행 중 설정이 남을 수 있으므로, 그것을 권한 회수 완료로 간주하지 않는다.

## 적용 전 확인과 공식 배포

- `[SHARED]` PC 담당이 승인된 각 계정의 공급자 identity와 내부 account UUID 대응을 확인한다. renderer는 Google 계정 소유권을 판단하지 않으며 UUID 값은 채팅·공개 증거에 복사하지 않는다.
- `[USER/SHARED]` 기존 인증·승인 범위에서 위 두 parameter를 설정한다. UUID는 기존 SecureString 보호 경로로 입력하며 이 작업은 키 생성·새 IAM 권한 부여를 포함하지 않는다. Cloud는 실제 parameter를 읽거나 변경하지 않는다.
- `[AGENT]` 정확한 diff 확인 후 기존 이슈·PR 템플릿 및 develop/main CI 절차를 따른다. 현재 단계는 코드 준비와 합성 검증이며 원격 게시·실제 parameter 변경·배포를 완료했다고 표시하지 않는다.
- renderer는 기존 guard catalog에 포함되어 있다. 실행 파일 추가 없이 renderer 내용만 바뀌어도 guard 지문이 달라지므로 기존 계약과의 최초 비교는 `TRANSITION_REQUIRED` 대상이다. 기존 `production-transition-approval`에서 해당 실행을 실제 승인한 뒤 pipeline이 규칙 중지·전환·host/전체 DB/API 검증·규칙 복원을 수행한다. 보호 검사를 제거하거나 임의로 AUTO_READY를 만들지 않는다.
- 이 변경은 새 SQL migration이 없고 현재 V1–V20을 보존한다. 새 immutable release의 성공을 확인한 뒤 승인 계정의 운영 API 접근, 비허용 계정 거부, no-store와 감사 기록을 검증한다. 실제 계정·세션 검증은 로컬 합성 검사와 별개다.

## 운영자 메뉴 capability

`GET /api/auth/me`는 `capabilities.canViewOperations`, `POST /api/auth/refresh`는 `account.capabilities.canViewOperations`에 기존 활성화 flag와 현재 인증 계정의 허용 목록 소속 여부를 반환한다. 응답에는 허용 목록·다른 계정 UUID를 추가하지 않고 JWT claim과 DB Role은 변경하지 않는다. 이 boolean은 메뉴 표시용 권한 정보이며 실제 운영 조회의 인가·감사 검사를 대체하지 않는다. 웹은 명시적 true만 메뉴를 표시하고 누락·false·로그아웃·계정 전환 시 이전 권한을 유지하지 않아야 한다.

두 값 설정은 단일 값만 처리하는 구 renderer가 실행 가능한 상태에서 먼저 등록하지 않는다. 기존 한 계정 설정으로 호환 renderer 배포를 검증한 뒤 PC 담당이 승인된 두 UUID를 안전하게 등록하고 공식 설정 반영 절차를 조율한다. 새 릴리스의 원격 CI·배포 완료와 실제 두 번째 계정 등록은 각각 근거를 확인하고 기록한다. 설정 저장만으로 실행 중 애플리케이션에 반영됐다고 간주하지 않는다.
