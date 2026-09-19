# meet-me-server Implementation Plan

> **상태:** Active  
> **최종 갱신:** 2026-09-19
> **목표:** MVP 백엔드 구현의 의사결정, 작업 순서, 진행 상황과 완료 근거를 한곳에서 추적한다.

이 문서는 실행 체크리스트다. 제품 요구사항은 [`docs/PRD.md`](docs/PRD.md), 기술 구조와 TBD는
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), 확정된 장기 결정은 [`docs/ADR.md`](docs/ADR.md)를
기준으로 한다. 이 문서가 기준 문서와 충돌하면 기준 문서를 우선하고 이 계획을 갱신한다.

## 운영 규칙

- `[x]`는 구현, 관련 테스트, 문서 검토와 검증이 모두 끝난 작업만 표시한다.
- `[ ]`는 미착수, 진행 중, 사용자 선택 대기 또는 검증 미완료 상태를 포함한다.
- 작업 시작 전 현재 브랜치, `git status`, 최근 커밋과 관련 GitHub Issue/PR을 확인한다.
- `develop`에서는 직접 작업하지 않는다. 피처 브랜치는 깨끗한 작업 트리에서 `develop`의 `git pull --ff-only origin develop`이 성공한 뒤 생성한다.
- `develop`이 분기되거나 pull이 충돌하면 임의 merge/rebase하지 않고 해결 선택지를 사용자에게 제시한다.
- 의미 있는 제약이나 복수의 구현 방안이 있으면 장점, 단점, 비용과 장기 영향을 제시하고 사용자 선택을 받은 뒤 진행한다.
- `TBD`가 걸린 작업은 의사결정 게이트가 완료되기 전에 종속 구현을 시작하지 않는다.
- 하나의 체크 항목 또는 밀접한 하위 항목 묶음을 하나의 명확한 PR 목적으로 유지한다.
- 작업 브랜치를 만들기 전에 GitHub Issue를 생성하고 PR 본문의 독립된 `Closes #<issue-number>` 행으로 연결한다. 프로젝트 GitHub Action이 형식과 열린 Issue 참조를 검증하고 `develop` 병합 시 자동 종료한다.
- 사용자와의 대화에서 범위, 우선순위, 작업 순서 또는 결정이 바뀌면 이 계획도 현재 합의에 맞게 수정한다.
- 빈 원격 저장소의 초기 구성만 `main`에 기준점용 커밋을 만든 뒤 `develop`과 `feature/initial-setup`을 생성하여 PR로 검토한다. 이 초기 절차가 끝난 뒤에는 예외 없이 최신 `develop`에서 feature 브랜치를 만드는 일반 브랜치 전략을 적용한다.
- 작업 완료 시 체크박스, 최종 갱신일과 하단 진행 기록을 함께 갱신한다.
- 사용자가 완료된 작업의 커밋과 푸시를 요청하면, 커밋 전에 완료 체크박스, 현재 진행 요약, 최종 갱신일과 진행 기록을 먼저 실제 상태에 맞게 반영한다.
- 커밋 전 진행 기록의 Commit/PR 칸은 `동일 커밋 예정`으로 기록할 수 있으며, 실제 커밋 해시는 Git 이력을 기준으로 추적한다.
- 한 단위 작업의 구현·검증이 끝나면 기본적으로 PR 생성 전에 제목과 본문 전체 초안을 사용자에게 보여주고 승인을 받는다. 번호 기반 실행처럼 커밋·push·PR 생성을 명시적으로 위임받은 흐름은 별도 승인 대기 없이 생성하고 즉시 결과를 보고한다.
- 비밀정보, 개인정보, OAuth 토큰과 외부 API 키는 이 문서와 Issue/PR/로그에 기록하지 않는다.

### 책임 표기

- `[AGENT]`: 저장소 코드·문서·테스트와 비밀값 없는 설정을 에이전트가 완료한다.
- `[USER]`: 계정 로그인, 신원·소유권·동의·결제·관리자 권한 또는 실제 비밀값이 필요해 사용자가 직접 완료한다.
- `[SHARED]`: 에이전트가 코드·설정·절차를 준비하고 사용자가 외부 시스템 설정을 완료한다.
- 표기가 없는 기존 구현 항목은 기본적으로 `[AGENT]`다. 외부 작업이 발견되면 하나의 항목을 `[AGENT]`와 `[USER]`로 분리한다.
- 사용자 개입이 필요하면 `.agents/rules/user-intervention.md`의 `[USER ACTION REQUIRED]` 형식으로 서비스 위치와 단계별 행동까지 종속 작업 전에 안내한다.

## 현재 진행 요약

- **현재 단계:** Phase 5 — Issue #14 구현·검증 완료, PR 생성 준비
- **제품 기능:** 익명 방 생명주기에 본인 조건 제출·수정·조회, 50명 상한, 자동·수동·데드라인 마감과 PostgreSQL 고정 배치·Outbox를 연결했다.
- **출시 목표:** Wanted AI Champion 심사·투표를 위해 2026-09-21부터 로그인 없이 핵심 기능을 체험할 수 있는 제출 MVP를 배포한다. Google·Kakao 소셜 로그인과 Google Calendar는 Post-MVP로 미룬다.
- **현재 차단 사항:** 기능 구현을 막는 외부 자격 증명은 없다. 운영 domain은 `meet-me.co.kr`, 장소 공급자는 Kakao Local API로 확정했고 Gemini·Kakao Local 개발 키를 로컬과 GitHub `integration` Environment에 등록했다. AWS 관련 설정은 사용자 지시 전까지 보류하며, 공개 배포 전 AWS 계정·예산·Region·배포 방식, Route 53 생성 후 가비아 네임서버 변경과 Gemini Paid 전환 승인이 필요하다.
- **현재 사용자 개입:** 제출 MVP 기능 구현 전 필요한 Gemini·Kakao Local 개발 키와 domain 준비를 완료했다. 지금은 가비아 기본 네임서버를 유지하고 AWS 작업을 시작하지 않는다. 이후 사용자가 AWS 진행을 지시하면 계정 보안·예산을 확인하고, Terraform이 Route 53 Hosted Zone을 만든 뒤 가비아 네임서버를 교체한다. 실제 심사 사용자 자연어를 Gemini에 보내기 전 Paid Tier 전환을 승인한다. OAuth·Calendar 사용자 작업은 Post-MVP까지 중단한다.
- **개발 흐름:** 선택지 B 위험도 기반 TDD 확정. 일반 변경은 엄격한 Red-Green-Refactor, 고위험 변경은 테스트 설계·구현 역할 분리
- **자율 실행 위임:** 사용자가 후속 구현의 커밋·push·PR 생성까지 별도 승인 대기 없이 진행하도록 명시적으로 위임했다. 각 PR의 범위·검증·URL은 생성 직후 보고하며, 결제·비밀정보 입력·운영 배포와 파괴적 작업은 이 위임에 포함하지 않는다.
- **RED 증거:** 선택지 C 확정. Git에는 `.tdd/red/<work-item>.json`의 최소 메타데이터·테스트 지문만 추적하고 전체 실패 출력은 `.codex/tdd-evidence/<work-item>.log`에 로컬 전용으로 보관한다.
- **고위험 검증:** 트랜잭션·동시성·외부 Adapter의 의도적 결함 주입은 `.tdd/verification/<work-item>.json`에 최종 source·test 지문과 탐지 결과를 추적하고 전체 출력은 로컬 로그로 분리한다.
- **Mutation Testing:** 선택지 B로 조정. PIT는 initial commit과 Phase 1의 선행 조건에서 제외하고 시간 교집합·장소 영역·후보 점수 같은 핵심 결정론적 매칭 로직이 구현된 뒤 효과가 큰 패키지에만 선택 도입한다. 인증·트랜잭션·동시성·멱등성·외부 Adapter는 의도적 결함 주입 검증을 사용한다.
- **아키텍처 자동 검사:** initial commit과 Phase 1에서는 ArchUnit·Konsist를 도입하지 않고 코드 리뷰로 헥사고날 의존 방향을 확인한다. 기능·Adapter 증가로 수동 검토 부담이 커지거나 실제 경계 위반이 발견되면 도구를 다시 비교한다.
- **Issue·PR 작성:** 작업 전에 범위와 완료 조건을 담은 GitHub Issue를 만들고 PR 본문의 독립된 `Closes #<issue-number>` 행으로 연결한다. 프로젝트 GitHub Action은 형식과 열린 Issue 참조를 검사하고 `develop` 병합 시 연결 Issue를 `completed`로 종료한다. PR 제목은 `<type>: <summary>` 형식과 허용 type을 지키며 summary를 명사형 한국어로 끝낸다. 본문은 목적·변경 내용·검증·리뷰 요청·영향 범위를 구체적으로 적는다. 현재 위임 범위에서는 커밋·push·PR 생성 전 승인 대기 없이 진행하고 생성 직후 결과를 보고한다.
- **완료 정책:** 예상 참여 인원·제출 마감은 선택 사항이며, 둘 다 없으면 수동 마감 방식을 명시한다. 자동 조건이 있어도 주최자는 경고 후 조기 마감할 수 있다.
- **최소 인원:** 예상 참여 인원은 2명 이상이며, 모든 종료 방식에서 주최자 포함 고유 제출이 2개 미만이면 `INSUFFICIENT_PARTICIPANTS`로 종료하고 후보를 만들지 않는다.
- **비동기 전달:** PostgreSQL Transactional Outbox + Redis Streams 확정. PostgreSQL이 작업 상태의 기준이며 Pub/Sub은 비즈니스 작업에 사용하지 않는다.
- **장소 입력:** 장소를 입력할 때는 별도 장소·지도·좌표 필드 없이 자연어만 사용한다. 특정 가능한 장소만 기본 1km 또는 명시 반경의 허용 영역을 만들고, 이동 제약·미확정 장소는 좌표를 만들지 않는다.
- **Gemini 배치:** 참가자 제출·수정 중에는 호출하지 않는다. 입력 수집 종료 시점의 최신 제출을 방 전체 배치로 고정하고 자연어가 하나 이상일 때만 자연어가 있는 항목으로 논리 작업 하나를 실행한다. 전원이 정형 일정만 제출하면 AI 호출 없이 매칭하며, 제공된 자연어에 참가자별 500자·배치 합계 10,000자 제한을 적용한다. 연동은 공식 Google GenAI SDK Java 클라이언트를 AI Adapter 내부에서만 사용한다.
- **제출 MVP 인증 경계:** 모든 사용자는 로그인 없이 익명 브라우저 세션으로 이용한다. 방을 만든 `Secure HttpOnly` 불투명 세션이 첫 참여자와 `HOST` 역할을 소유하며 같은 세션만 마감·재분석·후보 확정을 수행한다. 공유 링크만으로는 참여자나 주최자 권한을 얻지 못한다.
- **Post-MVP 인증·Calendar:** Google·Kakao 로그인, RS256 Access/회전형 Refresh Token, 공급자 token 암호화와 Google Calendar 연결·방별 ON/OFF·스냅샷은 Post-MVP 백로그로 분리한다.
- **분석 실패:** 최초 1회와 Full Jitter 기반 최대 3회 기술 재시도를 수행한다. 소진 시 입력을 보존하고 `ANALYSIS_DELAYED`로 전이해 후보를 만들지 않으며, 주최자가 같은 고정 배치를 방 단위로 다시 요청할 수 있다. Gemini 응답 성공 후 개별 의미 검증 실패만 `PARTIAL`로 처리한다.
- **상태 모델:** 방 입력 수집, 고정 배치 조율 작업, 후보 품질과 최종 확정을 독립적인 PostgreSQL 상태로 관리하고 API에는 계산된 단일 진행 상태를 제공한다. 재분석은 닫힌 입력 수집을 다시 열지 않는다.
- **데이터 접근:** Komapper JDBC 7.0.0과 KSP 2.3.12를 사용한다. 도메인 Aggregate·Entity·VO, Application Command·Result, Web·외부 공급자 DTO와 영속 `*Record`를 분리하고 Komapper·KSP 타입은 Persistence Adapter 내부에만 둔다. Flyway SQL이 스키마의 기준이다. Komapper 호환을 위해 coroutine 1.11.0을 명시적으로 고정한다.
- **트랜잭션 경계:** 원자적 유스케이스의 Application 서비스 공개 메서드에 Spring `@Transactional`을 적용하고 Komapper JDBC 작업을 같은 트랜잭션에 참여시킨다. Domain은 Spring을 알지 못한다.
- **로컬·테스트 DB:** 로컬 PostgreSQL과 Redis는 Docker Compose로 실행하고 영속성 통합 테스트는 H2 없이 PostgreSQL Testcontainers를 사용한다. Domain·Application 단위 테스트는 가능한 한 DB 없이 실행한다.
- **관측성:** Actuator·Micrometer 지표와 JSON 로그를 Alloy로 수집해 Grafana Cloud Metrics·Loki·Grafana-managed Alerting에 전송한다. PostgreSQL은 실패 이력의 기준이며 동일 Redis 메시지가 첫 전달 포함 총 5회 실패하면 원문 없는 poison message 참조만 DLQ에 보관한다.
- **시간대·국제화:** MVP는 `Asia/Seoul`, `ko-KR`로 고정하되 IANA Zone ID·UTC Instant·BCP 47 locale·MessageSource 경계를 선도입한다.
- **DST 경계:** gap의 존재하지 않는 경계는 다음 유효 시각으로 이동하고 overlap은 이른 시작 offset부터 늦은 종료 offset까지 실제 구간을 보존한다.
- **후보 탐색 범위:** 주최자가 지역 날짜 범위를 선택하며, 생략하면 프론트엔드가 안내한 방 생성일 포함 14일을 서버가 적용·저장한다.
- **시간 결과:** 탐색 범위에서 자연어 조건을 실제 날짜별 구간으로 확장하고 후보별 포함 참여자의 Calendar 불가 시간을 차감한다. 구조화 후보와 MessageSource 템플릿 자연어 요약을 함께 반환하며 실제 날짜 공지는 주최자가 담당한다.
- **결과 요약:** 반복 조건은 예외 날짜 수가 실제 가능 날짜 수보다 적을 때만 `패턴 + 모든 예외`로 압축하고, 동률·예외 우세·불규칙 조건은 실제 날짜와 시간을 나열한다.
- **시간 입력:** 자연어가 주 입력이고 격자는 강조하지 않는 선택적 부가 기능이다. `MANUAL_AVAILABILITY`는 자연어 또는 하나 이상의 수동 가능 시간 중 하나만 있어도 제출할 수 있으며, 빈 슬롯은 `가능 시간 없음`이 아니라 부가 제약 없음이다. Calendar 연동 사용자는 방에서 ON했을 때 공급자 불가 시간과 선택적 추가 불가 시간을 사용하며 자연어는 선택 사항이다. 정형 시간은 LLM을 거치지 않으며 명시 날짜 범위에서는 실제 날짜형, 기본 14일 방에서는 7일 주간 반복형 구간으로 계산한다.
- **GitHub:** 초기 구성 PR #1과 국제화 기반 PR #3이 `develop`에 병합되었다. 기본 브랜치가 `main`이므로 PR #3의 `Closes #2`는 GitHub 기본 기능에서 무시되어 Issue #2를 수동 종료했다. 이후 `develop` 병합은 프로젝트 Action이 연결 Issue를 종료한다.
- **현재 작업:** Issue #14의 `feature/submission-gemini-pipeline`에서 제출 불변 버전, 동시 마감·배치 고정, 참조형 Outbox, `gemini-3.8-flash` 조건 유니온 처리 서비스와 분석 지연 재요청의 구현·검증을 완료했다. Kakao 실제 정규화는 4번, Redis relay·consumer는 5번 브랜치로 유지한다. AWS 설정은 사용자 지시 전까지 보류한다.

## 실행 순서

1. `[USER]` Gemini, AWS, domain처럼 계정·비용·비밀값이 필요한 제출 MVP 선행 작업을 에이전트 안내에 따라 하나씩 완료한다.
2. `[SHARED]` 배포 토폴로지, 지도 공급자와 공개 운영 전 Paid 전환처럼 비용·보안에 영향을 주는 결정을 선행 구간에서 모두 확정한다.
3. `[AGENT]` 이후 `implementation_plan.md` 순서로 Issue 생성, TDD 구현, 검증, 커밋, push와 PR 생성을 승인 대기 없이 반복한다.
4. `[USER]` Context 정리가 필요할 때만 중간 개입하며, 비밀정보 입력·결제·DNS·운영 배포 승인은 해당 시점에 직접 수행한다.
5. `[AGENT]` 제출 MVP가 배포·검증된 뒤 별도 Post-MVP 백로그에서 OAuth와 Calendar를 진행한다.

## 구현 브랜치 로드맵

현재 문서 PR #9는 아래 구현 브랜치 수에 포함하지 않는다. 각 브랜치는 직전 PR이 `develop`에 병합된 뒤
깨끗한 작업 트리에서 최신 `develop`을 받아 생성한다.

### 제출 MVP — 6개 브랜치

| 번호 | 브랜치 | 포함 단계 | 완료 결과 |
| --- | --- | --- | --- |
| 1 | `feature/core-domain-persistence` | Phase 1~2 | 핵심 도메인 모델, PostgreSQL·Flyway·Komapper 영속 경계 |
| 2 | `feature/anonymous-room-lifecycle` | Phase 3~4 | 익명 브라우저 세션, 주최자 권한, 방 생성·참여·마감 |
| 3 | `feature/submission-gemini-pipeline` | Phase 5 | 조건 제출·수정, 배치 고정, Outbox와 Gemini 구조화 파이프라인 |
| 4 | `feature/deterministic-matching-results` | Phase 6~7 | Kakao Local 장소 정규화, 결정론적 매칭, Plan A/B/C와 결과 확정 |
| 5 | `feature/reliability-observability` | Phase 8 | Redis Streams, 재시도·DLQ, 보안, 로그·지표와 rate limit |
| 6 | `feature/aws-release` | Phase 9~10 | 컨테이너·Terraform·배포, E2E와 Wanted 제출 MVP 출시 검증 |

### Post-MVP — 2개 브랜치

| 호출 번호 | 브랜치 | 포함 백로그 | 완료 결과 |
| --- | --- | --- | --- |
| PM-1 | `feature/social-auth` | PM-01 | Google·Kakao 로그인과 서비스 Access/Refresh Token |
| PM-2 | `feature/google-calendar` | PM-02 | Google Calendar 연결, 방별 ON/OFF와 불가 시간 스냅샷 |

### 번호 기반 실행 계약

- 사용자가 `1번 브랜치 작업해줘`처럼 번호로 요청하면 에이전트는 이 표의 브랜치와 범위를 그대로 사용한다.
- 에이전트는 선행 PR의 `develop` 병합과 작업 트리 상태를 확인하고, GitHub Issue 생성 → 최신 `develop` 갱신 →
  브랜치 생성 → 위험도 기반 TDD 구현 → 검증 → `implementation_plan.md` 갱신 → 커밋 → push → PR 생성을
  별도 승인 대기 없이 수행한다.
- 선행 PR이 아직 병합되지 않았거나 `develop`을 fast-forward 할 수 없으면 임의로 merge·rebase하지 않고
  차단 원인과 필요한 사용자 행동만 보고한다.
- 번호 요청은 해당 브랜치의 구현·커밋·push·PR 생성까지 위임한다. PR 병합, 결제, 실제 secret 입력,
  DNS 변경과 운영 배포 승인은 포함하지 않는다.
- 6번 브랜치는 AWS 작업 보류를 해제하는 별도 사용자 지시가 있어야 시작한다. `6번 브랜치 작업해줘`는
  AWS 작업 시작 지시로 간주하지만 실제 비용 발생·DNS 변경·운영 배포는 각 사용자 체크포인트에서 다시 확인한다.
- PM-1과 PM-2는 제출 MVP 완료 후에만 시작한다. `PM-1 브랜치 작업해줘`, `PM-2 브랜치 작업해줘` 형식으로 호출한다.

## Phase 0 — 프로젝트 기반

### 저장소와 개발 환경

- [x] GitHub 원격 저장소를 `origin`으로 연결한다.
- [x] Spring Boot 4.1.1, Kotlin 2.3.21, Java 17 기반 Gradle 프로젝트를 구성한다.
- [x] Gradle 9.7.1 Wrapper와 Kotlin DSL을 추가한다.
- [x] ktlint와 `.editorconfig`를 구성한다.
- [x] Spring MVC, Validation, Actuator와 springdoc-openapi 기본 의존성을 구성한다.
- [x] `ko-KR` 기본 message bundle, `Accept-Language` 해석과 fallback 기반을 구성한다.
- [x] Flyway SQL 마이그레이션 기본 경로 `src/main/resources/db/migration`을 준비한다.
- [x] `[AGENT]` 실제 Secret을 넣는 로컬 `.env.local`을 Git에서 제외하고 키 이름만 있는 `.env.example`을 제공한다.
- [x] 애플리케이션 컨텍스트 기동 테스트를 추가한다.
- [x] `ktlintCheck`, `assemble`, `test`가 통과하는지 검증한다.
- [x] GitHub Issue와 사람이 읽기 쉬운 위험 기반형 Pull Request 템플릿을 구성한다.
- [x] 빈 원격 저장소의 PR 기준점용 커밋 `64e5759`를 `main`에 push한다.
- [x] `develop` 브랜치를 생성하고 원격에 push한다.
- [x] 최신 `develop`에서 `feature/initial-setup`을 생성하고 현재 초기 구성을 동일 커밋 예정 상태로 준비한다.
- [x] `develop` 직접 작업 금지와 최신 `develop`에서만 피처 브랜치를 생성하는 규칙을 문서화한다.
- [ ] `[SHARED]` GitHub 브랜치 보호 규칙과 필수 상태 검사를 합의하고 설정한다.

### 에이전트 개발 흐름

- [x] 프로젝트 `AGENTS.md`와 개발·아키텍처·문서화 규칙을 구성한다.
- [x] 세션 시작 시 프로젝트 핵심 지침을 다시 주입하는 Codex `SessionStart` 훅을 구성한다.
- [x] `git commit` 전 TDD 조건과 품질 게이트를 검사하는 Codex `PreToolUse` 훅을 구성한다.
- [x] TDD 가드 단위 테스트를 추가하고 통과시킨다.
- [x] 선택지 B 위험도 기반 TDD와 Red-Green-Refactor 절차를 개발 규칙으로 확정한다.
- [x] 고위험 변경의 범위와 테스트 설계자·구현자 책임을 정의한다.
- [x] `[AGENT]` 작업 전 GitHub Issue를 생성하고 PR 템플릿의 필수 `Closes #<issue-number>`로 연결하는 개발 흐름을 규칙과 템플릿에 반영한다.
- [x] RED 증거는 Git 추적 요약 `.tdd/red/<work-item>.json`과 로컬 원본 로그 `.codex/tdd-evidence/<work-item>.log`로 분리하는 선택지 C로 확정한다.
- [x] RED 요약 JSON Schema, 파일 수명주기와 로컬 원본 로그의 Git 제외 규칙을 구현한다.
- [ ] TDD 가드에 RED 근거·테스트 지문 검증과 프로덕션 RED용 `TODO` 차단을 구현한다.
- [x] PIT는 initial commit과 Phase 1의 품질 게이트에서 제외하고 핵심 결정론적 매칭 로직 구현 후 효과가 큰 패키지에만 선택 적용하는 선택지 B로 조정한다.
- [ ] `[DEFERRED]` 시간 교집합·장소 영역·후보 점수 구현 후 PIT 도입 실익, Kotlin 호환성과 실행 비용을 재평가한다.
- [ ] `[DEFERRED]` PIT를 채택하면 Gradle plugin 버전, 대상·제외 패키지, 통과 기준과 CI 실행 주기를 확정한다.
- [ ] 의도적 결함 주입 시나리오와 검토 결과 기록 형식을 확정한다.
- [ ] 고위험 변경의 독립 테스트 검토와 의도적 결함 주입 결과를 품질 게이트에 연결한다.
- [ ] `[USER]` Codex `/hooks`에서 현재 프로젝트 훅 정의를 검토하고 신뢰 등록한다.
- [x] `[AGENT]` CI에서 `ktlintCheck`, `assemble`, `test`를 실행하는 GitHub Actions 워크플로를 추가한다.
- [x] `[AGENT]` PR의 `Closes #<issue-number>` 형식과 열린 Issue 참조를 검사하고 `develop` 병합 시 연결 Issue를 `completed`로 자동 종료하는 GitHub Action을 추가한다.
- [x] 아키텍처 자동 검사는 initial commit과 Phase 1에서 제외하고 코드 리뷰로 의존 방향을 확인하도록 결정한다.
- [ ] `[DEFERRED]` 기능·Adapter 증가로 수동 검토 부담이 커지거나 실제 경계 위반이 발견되면 ArchUnit·Konsist의 Kotlin 호환성, 검사 범위와 유지 비용을 다시 비교한다.
- [ ] `[DEFERRED]` 후속 도구를 채택하면 `domain <- application <- adapter` 의존성 규칙을 자동 검증한다.

## User Intervention Checkpoints

- [x] `[USER]` Gemini Free Tier 개발 키를 `.env.local`과 GitHub `integration` Environment의 `GEMINI_API_KEY`에 직접 등록한다.
- [ ] `[USER]` 공개 심사 사용자의 자연어를 처리하기 전에 Gemini Paid Tier, 예산·사용량 알림과 비용 발생을 승인한다.
- [x] `[AGENT]` 개발용 외부 연동 검사를 위한 GitHub `integration` Environment를 만들고 현재 PR CI에는 연결하지 않는다.
- [ ] `[USER]` AWS 인프라 적용 전 계정 MFA, 관리자 접근, `ap-northeast-2` 사용, 월 예산과 알림 수신 주소를 확인·승인한다.
- [ ] `[SHARED]` 제출 MVP 배포 토폴로지, 이미지 레지스트리, Redis 배치, Terraform state와 런타임 비밀 저장소를 확정한다.
- [x] `[USER]` 운영 domain `meet-me.co.kr`을 확보하고 DNS 변경 권한을 준비한다.
- [x] `[SHARED]` 운영 origin을 `https://app.meet-me.co.kr`, `https://api.meet-me.co.kr`로 확정하고 루트 domain은 프론트엔드로 연결한다.
- [ ] `[AGENT]` Terraform으로 Route 53 Hosted Zone과 ACM 인증서 검증 레코드를 만들고 가비아에 입력할 네임서버 4개를 출력한다.
- [ ] `[USER]` Route 53 생성 후 가비아 기본 네임서버를 AWS 네임서버 4개로 교체한다. 그전까지는 가비아 기본 네임서버를 유지한다.
- [x] `[SHARED]` 제출 MVP 지도·좌표 공급자로 Kakao Local API를 선택하고 검색·정규화만 공급자에 위임하도록 확정한다.
- [x] `[USER]` Kakao Developers 앱의 REST API Key를 `.env.local`과 GitHub `integration` Environment의 `KAKAO_LOCAL_API_KEY`에 직접 등록한다.
- [ ] `[USER]` GitHub `production` Environment에 에이전트가 확정한 배포 Variable·Secret을 직접 등록하고 첫 운영 배포를 승인한다.
- [ ] `[USER]` 관측성 연동 직전 Grafana Cloud 계정·stack·요금제와 알림 연락 채널을 선택하고 telemetry 전송 자격 증명을 합의된 실행 환경에 직접 등록한다.
- [ ] `[POST-MVP][USER]` Google Auth Platform의 운영 앱·Client ID/Secret과 Calendar scope를 준비한다.
- [ ] `[POST-MVP][USER]` Kakao Developers OIDC 앱·REST API key·Client Secret을 준비한다.
- [x] `[AGENT]` 각 사용자 작업 전에 필요한 secret 이름, 최소 권한, 서비스와 메뉴 위치, 단계별 행동, 공유 금지 값과 완료 확인 방법을 `docs/USER_INTERVENTION.md`에 안내한다.
- [ ] `[AGENT]` 각 사용자 작업 후 비밀값을 출력하지 않고 설정 존재 여부와 연동 결과만 검증한다.

## Deep Interview 진행 순서

구현 전에 아래 순서로 의존성이 큰 미정 사항부터 한 번에 한 결정을 다룬다. 각 질문에는 권장안과 대안의 트레이드오프를 제시하고, 답변은 관련 Decision Gate와 문서에 즉시 반영한다.

- [x] 1차 — 주최자·참여자 관계, 예상 인원과 입력 완료 의미
- [ ] 2차 — 방·참여·제출·마감·확정의 상태 전이와 동시성 (상태 책임 분리는 확정, 중복·경합 확정 정책은 남음)
- [ ] 3차 — 시간대, 탐색 기간, 슬롯 단위와 충돌 우선순위 (시간대 모델 확정)
- [ ] 4차 — 위치 구조화, 지도 검색 고유성, 허용 영역과 실제 장소 후보
- [ ] 5차 — Plan A/B/C 점수, 동률, 후보 부족과 fallback
- [ ] 6차 — 로그인·게스트 본인 증명·계정 연결·권한·서비스 Refresh Token
- [ ] 7차 — Calendar 동의·조회·동기화·실패 복구
- [ ] 8차 — Gemini 스키마·배치 응답 검증과 비동기 처리 상태 (방 전체 작업·재시도·분석 지연·부분 결과·공개 범위 확정)
- [ ] 9차 — PostgreSQL 모델·트랜잭션·Redis 메시지·멱등성
- [ ] 10차 — 개인정보 보관·삭제, 보안, 관측성, 배포와 복구

## Decision Gates — 종속 구현 전 사용자 선택 필요

각 게이트는 선택지와 트레이드오프를 별도로 제시하고 사용자가 선택한 뒤 Architecture 또는 ADR에 반영한다.

### DG-01 핵심 도메인과 시간 모델

- [x] 제출 MVP 핵심 도메인을 `MeetingRoom`, `Participant`, `Submission`, `CoordinationRun` 네 Aggregate로 분리하고 생명주기와 불변식을 ADR-034로 확정한다. 소셜 `User`는 Post-MVP로 유지한다.
- [x] 방 입력 수집, 고정 배치 조율 작업, 후보 품질과 최종 확정을 독립 상태로 분리하고 API 공개 진행 상태는 이 값들에서 계산하도록 확정한다.
- [x] 예상 참여 인원·제출 마감·수동 마감 중 하나 이상을 선택하고, 자동 조건이 있어도 수동 조기 마감을 허용하는 완료 정책을 확정한다.
- [x] 예상 참여 인원과 제출 마감을 함께 설정하면 먼저 충족된 조건으로 입력 수집을 종료하도록 확정한다.
- [x] 주최자는 항상 참여자이며 예상 참여 인원에 포함하고 다른 참여자와 동일하게 조건을 제출하도록 확정한다.
- [x] 제출 원문이 PostgreSQL에 접수된 고유 참여자를 완료 인원으로 세고, 참가자별 호출 없이 수집 종료 후 방 전체 배치를 구조화하도록 확정한다.
- [x] 예상 참여 인원 `N`은 가입 정원이 아니라 자동 마감할 고유 참여자 제출 목표로 확정한다.
- [x] 입력 수집 종료 전까지 제출 수정을 허용하고, 수정본은 인원을 중복 집계하지 않으며 종료 시점의 최신 버전만 매칭하도록 확정한다.
- [x] 데드라인·참여 인원·수동 마감 또는 이들의 조합에서 가장 먼저 충족된 종료 시점 이후에는 결과 생성 전이라도 수정을 금지하도록 확정한다.
- [x] 예상 참여 인원 최소값과 후보 생성 최소 제출 인원을 주최자 포함 2명으로 확정한다.
- [x] 자동 조건 전 수동 마감은 첫 요청에 `409 EARLY_CLOSE_CONFIRMATION_REQUIRED`를 반환하고 같은 endpoint의 `confirm_early=true` 재요청에서 조건을 다시 검증하도록 확정한다.
- [x] Gemini 응답 성공 후 해석 불가능한 조건은 제외하되 독립적으로 유효한 입력은 유지하고, 의미 검증 미반영 입력이 있으면 `PARTIAL` 결과를 생성하도록 확정한다.
- [x] 마감 전에 참가자별 구조화 작업을 만들지 않고, 마감 트랜잭션이 최신 버전 배치와 논리 작업 하나를 생성하도록 확정한다.
- [x] MVP는 `Asia/Seoul`로 고정하되 방에 IANA Zone ID를 저장하고 UTC Instant·ZoneRules 기반으로 DST까지 계산 가능하게 하도록 확정한다.
- [x] 주최자가 후보 탐색 시작일·종료일을 선택하고, 둘 다 생략하면 방 생성일을 포함한 14일을 서버와 프론트엔드 기본값으로 사용하도록 확정한다.
- [x] 후보 탐색 날짜 범위의 최대 허용 길이를 31일로 확정한다.
- [x] 최종 시간 결과는 탐색 범위의 구조화된 실제 날짜별 가능 구간과 이를 묶어 표현한 자연어 요약을 함께 제공하고, 실제 날짜 공지는 주최자가 담당하도록 확정한다.
- [x] 탐색 날짜 범위를 자연어 시간 조건의 날짜별 확장과 Calendar 불가 시간 검증을 위한 근거 기간으로 확정한다.
- [x] 사용자 가능 시간과 Calendar 절대 불가 시간이 충돌하면 불가 시간을 우선 차감하도록 확정한다.
- [x] 자연어 요약은 서버 MessageSource 템플릿으로 생성하고 구조화 후보와 의미가 일치해야 하며 LLM이 생성하거나 수정하지 않도록 확정한다.
- [x] 예외 날짜 수가 실제 가능 날짜 수보다 적을 때만 `패턴 + 모든 예외`를 사용하고, 동률·예외 우세·불규칙 후보는 실제 날짜와 시간을 나열하는 손실 없는 압축 규칙을 확정한다.
- [x] LLM은 자연어를 압축된 시간 구간으로 구조화하고 Calendar·방 전용 가능·추가 불가 시간 격자 입력을 칸 단위로 생성하거나 재해석하지 않도록 확정한다.
- [x] 주최자 명시 날짜 범위에서는 실제 날짜형, 범위 생략 시에는 7일 주간 반복형 수동 가능·추가 불가 시간을 사용하고 기본 14일 탐색 범위에 확장하도록 확정한다.
- [x] 매칭은 고정 후보 슬롯 없이 연속 시간 구간 연산으로 수행하도록 확정한다.
- [ ] 가능 시간·추가 불가 시간 격자의 5분·10분 간격, 하루 표시 범위와 긴 날짜 범위 이동 방식을 선택한다.
- [x] 게스트와 Calendar 미연동 참여자의 방 전용 격자는 웬투밋식 가능 시간 선택으로, Calendar 연동 참여자의 보완 격자는 추가 불가 시간 선택으로 확정한다.
- [x] 수동 가능 시간은 선택적 부가 입력으로 두고, 빈 배열은 `가능 시간 없음`이 아니라 부가 슬롯 제약 없음으로 확정한다.
- [x] `MANUAL_AVAILABILITY`는 자연어 또는 하나 이상의 수동 가능 시간 중 하나만 있어도 제출 가능하고, 모두 없을 때만 거부하도록 확정한다.
- [x] 공백이 아닌 자연어, 현재 방의 Calendar ON, 하나 이상의 수동 가능 시간이 모두 없으면 미입력으로 보아 `SUBMISSION_INPUT_REQUIRED`로 거부하고 제출 완료 인원에 포함하지 않도록 확정한다.
- [ ] 후보가 세 개 미만일 때의 서버 응답과 동률 후보 정렬 규칙을 선택한다.
- [x] 핵심 Aggregate 경계와 DST 변환 정책을 Architecture 및 ADR-034·ADR-035에 반영한다.

### DG-02 PostgreSQL 접근과 로컬 데이터베이스

- [x] JPA, Spring Data JDBC, jOOQ와 Komapper의 트레이드오프를 비교하고 Komapper JDBC를 선택한다.
- [x] 도메인 Aggregate·Entity·VO와 영속 `*Record`를 분리하고 Persistence Adapter의 명시적 Mapper로 변환하도록 확정한다.
- [x] 원자적 유스케이스의 Application 서비스 공개 메서드에 Spring `@Transactional`을 적용하도록 확정한다.
- [x] 로컬 PostgreSQL과 Redis를 Docker Compose로 제공하도록 확정한다.
- [x] 영속성 통합 테스트는 H2 없이 PostgreSQL Testcontainers를 사용하도록 확정한다.
- [x] 확정 내용을 Architecture와 ADR-028에 반영한다.

### DG-03 인증·인가와 토큰

- [x] 제출 MVP는 모든 사용자가 로그인 없이 이용하고 방 생성 익명 세션이 `HOST` 역할과 주최자 명령 권한을 소유하도록 ADR-032로 확정한다.
- [x] `[POST-MVP]` 주최자는 Google·Kakao 로그인을 필수로 하고 일반 참여자는 서비스 로그인 없이 게스트 참여를 허용하도록 확정한다.
- [x] 로그인 여부와 관계없이 참여자가 본인 최신 제출을 다시 조회·수정할 수 있게 하고, 공유 초대 링크만으로는 제출 소유권을 인정하지 않도록 확정한다.
- [x] 비로그인 참여자의 본인 증명은 서버 발급 불투명 자격 증명을 `Secure HttpOnly` 쿠키로만 전달하고 PostgreSQL에는 해시만 저장하도록 확정한다.
- [x] 게스트 자격 증명 하나를 브라우저 단위로 여러 방에서 재사용하되 `(guest browser session, room)`당 참여자 하나만 허용하도록 확정한다.
- [x] 게스트 cookie는 API 호스트 전용 `Secure HttpOnly; SameSite=Lax; Path=/api`, 고정 30일 `Max-Age`로 확정한다.
- [x] 게스트 자격 증명은 32바이트 난수와 SHA-256 digest를 사용하고 자동 회전·분실 복구 없이 만료·서버 회수 상태 및 정확한 Origin 검증으로 방어하도록 확정한다.
- [x] 짧은 JWT Access Token과 Redis TTL 기반 회전형 불투명 Refresh Token을 사용하는 방식을 선택한다.
- [x] meet-me Refresh Token은 해시·토큰 패밀리·회전 상태를 Redis에 두고 Google·Kakao 공급자 Refresh Token은 PostgreSQL에 암호화 저장하도록 책임을 분리한다.
- [ ] Google·Kakao 계정 연결 및 중복 계정 정책을 선택한다.
- [x] 제출 MVP의 세션 누락·무효는 `401`, 유효한 비주최자는 `403`, 알 수 없는 초대 코드는 `404`, 상태 충돌은 `409` ProblemDetail로 반환하도록 확정한다.
- [x] Refresh Token 원문을 저장하지 않고 매 갱신 시 회전하며 재사용 감지 시 토큰 패밀리를 폐기하도록 확정한다.
- [x] Access Token 15분, Refresh Token 미사용 14일, 토큰 패밀리 절대 30일로 수명을 확정하고 회전으로 절대 만료가 연장되지 않도록 한다.
- [x] Access Token은 로그인·갱신 response body로 발급하고 이후 `Authorization: Bearer` 헤더로 받으며, Refresh Token은 `Secure HttpOnly` 쿠키의 `Set-Cookie` 헤더로만 전달하도록 확정한다.
- [x] JWT 서명은 RS256을 사용하고 `kid` 기반으로 현재 서명 private key와 교체 중 검증 public key를 관리하도록 확정한다.
- [x] 운영 프론트엔드·API를 같은 상위 사이트의 서브도메인에 배치하고 Refresh cookie를 API 호스트 전용 `Secure HttpOnly; SameSite=Lax; Path=/api/auth`로 제한하며 `Max-Age`를 미사용 TTL과 패밀리 잔여 수명 중 짧은 값으로 설정하도록 확정한다.
- [x] Refresh·로그아웃 요청은 명시적 프론트엔드 Origin과 정확히 일치해야 하고 Origin 누락·불일치와 wildcard credential CORS를 거부하며 별도 CSRF token은 사용하지 않도록 확정한다.
- [x] 일반 로그아웃은 현재 Refresh Token 패밀리만 폐기하고 별도의 모든 기기 로그아웃은 사용자 전체 패밀리를 폐기하도록 선택지 C로 확정한다.
- [ ] 인증·토큰·개인정보 로그 마스킹 상세를 선택한다.
- [x] Redis를 meet-me Refresh Token 상태와 TTL에 사용하도록 확정한다.
- [x] 확정 내용을 Architecture와 ADR-029에 반영한다.

### DG-04 Redis 책임

- [x] 비동기 작업 전달에 PostgreSQL Transactional Outbox와 Redis Streams Consumer Group을 사용한다.
- [x] Redis Pub/Sub은 유실 가능한 실시간 보조 알림 외의 비즈니스 작업 전달에 사용하지 않는다.
- [x] Redis의 추가 책임으로 meet-me Refresh Token 해시·토큰 패밀리·회전 상태와 TTL 관리를 선택한다.
- [ ] 중복 요청 방지, 분산 락, 캐시와 호출 제한 중 추가 사용 목적은 필요 기능 착수 전에 선택한다.
- [x] Stream 메시지는 이벤트 ID와 방 전체 제출 배치 ID만 담고 원문은 Worker가 PostgreSQL에서 조회하도록 확정한다.
- [ ] Stream key, Consumer Group, trimming, Pending 복구, retry·보류·실패 정책을 선택한다.
- [x] PostgreSQL을 실패 이력의 기준으로 유지하고 Redis DLQ에는 poison message의 이벤트·배치 참조와 실패 메타데이터만 저장하도록 확정한다.
- [x] Gemini 기술적 재시도 소진은 `ANALYSIS_DELAYED`로 전이해 후보 생성 없이 ACK하고 DLQ에는 넣지 않도록 확정한다.
- [x] 주최자의 방 단위 재분석은 같은 고정 배치를 대상으로 새 Outbox 이벤트를 멱등하게 발행하도록 확정한다.
- [ ] `ANALYSIS_DELAYED`의 자동 지연 재시도 여부와 횟수 상한을 선택한다.
- [x] 동일 메시지가 첫 전달을 포함해 총 5회 처리 실패하면 DLQ로 옮기고, Gemini API 재시도 횟수와 별도로 계산하도록 확정한다.
- [ ] Pending 회수 대기시간, DLQ 보존 기간, trimming과 수동 재처리 절차를 선택한다.
- [ ] 키 형식, TTL, 장애 시 동작과 PostgreSQL 복구 경계를 선택한다.
- [ ] Redis가 없어도 영구 비즈니스 데이터가 유실되지 않는지 설계 검토한다.
- [ ] 확정 내용을 Architecture와 ADR에 반영한다.

### DG-05 Google Calendar 연동 `[POST-MVP]`

- [x] meet-me 서비스 로그인과 Google Calendar 접근 동의를 분리하되 Calendar OAuth는 로그인한 사용자만 시작할 수 있고 게스트에는 제공하지 않도록 확정한다.
- [x] 로그인한 개인의 Calendar 연결·조회와 현재 참여 중인 방의 적용을 분리하고, Calendar를 연결한 참여자 본인이 현재 방에서 ON/OFF할 수 있도록 선택지 A로 확정한다. ON은 불가 일정 외 탐색 범위를 허용한다는 뜻이며 자연어 없이 Calendar 스냅샷만으로 제출할 수 있다.
- [x] 프론트엔드는 참여자·방별 최초 Calendar ON 때 의미를 재확인하는 팝업을 한 번만 표시하고, 백엔드는 별도 확인 필드 없이 `CALENDAR` 모드를 명시적 사용 의사로 취급하도록 확정한다.
- [x] Calendar ON 제출·수정 시점의 조회 결과와 조회 완료 시각을 불변 제출 버전에 고정하고, 입력 수집 종료 시에는 Google Calendar를 다시 호출하지 않도록 선택지 A로 확정한다.
- [ ] OAuth scope, 일정 조회 기간과 동기화 방식을 선택한다.
- [ ] Google 토큰 저장·암호화·갱신·폐기 정책을 선택한다.
- [ ] 연동 실패, 권한 철회, API 할당량 초과 시 사용자 흐름을 선택한다.
- [ ] 확정 내용을 Architecture와 필요 시 ADR에 반영한다.

### DG-06 Gemini 자연어 파싱

- [ ] 후보 stable Gemini Flash 모델들의 한국어 파싱 품질, 비용과 수명주기를 비교한다.
- [x] 정확한 모델 ID를 stable `gemini-3.8-flash`로 선택한다.
- [x] 공식 SDK와 직접 HTTP Client의 트레이드오프를 비교하고 Google GenAI SDK Java 클라이언트를 선택한다.
- [x] Structured Output을 추가 필드·좌표를 금지한 4종 조건 유니온으로 확정하고 조건 단위 서버 재검증 규칙을 선택한다.
- [x] 네트워크 오류, timeout, HTTP 429와 공급자 5xx에만 자동 재시도하고 의미·도메인 검증 실패에는 재호출하지 않도록 확정한다.
- [x] 방 전체 기술적 재시도 소진 시 입력과 고정 배치를 보존하고 `ANALYSIS_DELAYED`로 전이하여 매칭하지 않도록 확정한다.
- [x] Gemini 응답 성공 후 개별 의미·스키마·도메인 검증 실패만 유효한 일정 입력 모드별 정형 시간 구간과 자연어 조건으로 `PARTIAL` 매칭을 계속하도록 확정한다.
- [x] 실패 원문은 제출 전 고지 후 결과 생성 시 참여자 표시 이름·사유와 함께 주최자에게만 공개하도록 확정한다.
- [x] 미반영 원문은 참고 정보로만 제공하고 주최자에게 다른 참여자의 수정·재처리 권한이나 책임을 부여하지 않도록 확정한다.
- [x] `PARTIAL` 확정에 별도 체크박스·모달·API 확인 필드를 두지 않고 상태가 드러나는 버튼으로 원클릭 확정하도록 확정한다.
- [x] 참가자 제출·수정 중에는 Gemini를 호출하지 않고 마감 시 자연어가 있는 최신 제출만 묶어 방 전체 배치당 논리 작업을 최대 하나 생성하도록 확정한다.
- [x] 자연어가 있는 제출만 Gemini 배치에 포함하고, 전원이 정형 일정만 제출하면 Gemini 작업 없이 결정론적 매칭을 시작하도록 확정한다.
- [x] 참가자별 자연어 최대 500 Unicode 코드 포인트와 방 전체 합계 최대 10,000 코드 포인트를 적용하고 초과 입력을 자르지 않도록 확정한다.
- [x] 최초 1회와 기술적 재시도 최대 3회, Full Jitter `0~1초`·`0~2초`·`0~4초`, 호출별 15초·전체 60초 timeout으로 확정한다.
- [x] 방 최대 참여자 50명, 입력별 최대 32조건과 Gemini UTF-8 응답 최대 256KiB를 선택한다.
- [x] 확정 내용을 Architecture와 ADR-016·ADR-018에 반영한다.

### DG-07 지도·좌표

- [x] 장소 전용 입력란, 지도 선택과 사용자 기준 좌표 수집 없이 자연어만 제출하도록 확정한다.
- [x] `집`, `회사`, `학교 근처`는 장소가 아닌 이동 제약으로, 하나로 특정되지 않는 `중앙역`은 미확정 장소로 보존하고 좌표를 만들지 않도록 확정한다.
- [x] 특정 가능한 장소에 명시 반경 또는 기본 1km 반경을 적용하고, 참여자 내부 대안은 합집합, 참여자 사이는 교집합으로 계산하도록 확정한다.
- [x] 국내 장소 검색 적합성, 구현 비용과 공급자 종속성을 비교해 제출 MVP 공급자로 Kakao Local API를 선택한다.
- [x] 지도 공급자는 장소 검색·정규화와 표시용 이름을 제공하고, 거리·영역 계산은 서버가 담당하도록 확정한다.
- [ ] 검색 결과를 하나의 장소로 인정할 고유성·신뢰도 기준을 선택한다.
- [ ] 허용 영역 교집합에서 대표 지점과 실제 모임 장소 후보를 만드는 방식을 선택한다.
- [ ] 좌표 정밀도, 보관 기간과 삭제 정책을 선택한다.
- [ ] 확정 내용을 Architecture와 필요 시 ADR에 반영한다.

### DG-08 실행·배포와 운영

- [x] 참여자 제출은 PostgreSQL에 접수한 뒤 응답하고, LLM 구조화와 일정 매칭은 요청 경로 밖에서 비동기로 처리하도록 경계를 확정한다.
- [x] 비동기 작업 전달에 PostgreSQL Transactional Outbox + Redis Streams를 선택한다.
- [x] 기술적 Gemini 실패 시 프론트엔드가 무기한 로딩하지 않고 `ANALYSIS_DELAYED`와 입력 저장 완료를 표시하도록 확정한다.
- [ ] `ANALYSIS_DELAYED` 상태 갱신과 일반 처리 상태 갱신을 Polling, SSE 또는 WebSocket 중 어떤 방식으로 전달할지 선택한다.
- [ ] EC2와 ECS의 비용, 운영 복잡도와 확장성 트레이드오프를 비교하고 선택한다.
- [ ] Docker Hub와 ECR을 비교하고 이미지 레지스트리를 선택한다.
- [ ] Nginx와 Redis의 운영 배치를 선택한다.
- [x] 운영 프론트엔드와 API를 같은 상위 사이트의 서브도메인에 배치하도록 확정한다.
- [x] 운영 domain을 `meet-me.co.kr`, 프론트엔드를 `app.meet-me.co.kr`, API를 `api.meet-me.co.kr`로 확정하고 루트 domain은 프론트엔드로 연결하도록 확정한다.
- [ ] Terraform 상태 저장소, 잠금, 환경 분리와 비밀정보 주입 방식을 선택한다.
- [ ] CI/CD, 롤백과 데이터베이스 마이그레이션 실행 순서를 선택한다.
- [x] Actuator·Micrometer + Alloy + Grafana Cloud Metrics·Loki·Grafana-managed Alerting을 채택하고 자체 Grafana·Loki·Prometheus와 MVP tracing은 제외하도록 확정한다.
- [ ] Gemini 배치 실패·지연, Outbox·Pending 적체와 DLQ 진입의 알림 임계값·연락 채널을 선택한다.
- [x] 확정 내용을 Architecture와 ADR-017에 반영한다.

### DG-09 국제화

- [x] MVP 지원 locale은 `ko-KR`로 고정하되 BCP 47 locale과 Spring MessageSource 기반 i18n을 도입하도록 확정한다.
- [x] 도메인 상태·enum·API 오류 코드는 언어 중립적으로 유지하고 사용자 노출 문자열은 message bundle로 분리하도록 확정한다.
- [x] 시간대와 locale을 독립된 개념으로 다루고 제출 버전에 입력 locale을 기록하도록 확정한다.
- [ ] MVP 이후 추가할 언어의 우선순위와 번역 품질 검증 방식을 선택한다.
- [ ] 사용자 선호 locale 저장 위치와 요청별 locale 결정 우선순위를 선택한다.
- [ ] 확정 내용을 Architecture와 ADR에 반영한다.

## Phase 1 — 핵심 도메인 기반

**선행 조건:** DG-01

- [x] 핵심 용어와 `MeetingRoom`·`Participant`·`Submission`·`CoordinationRun` Aggregate 경계를 정리하고 사용자 검토를 받는다.
- [x] 모임 방식, 방 입력 수집 상태·마감 원인·조율 작업 상태·후보 품질과 참여자 역할 값 객체를 테스트부터 작성한다.
- [x] IANA Zone ID, 지역 날짜·시간, UTC Instant, 기간, 실제 날짜형·주간 반복형 시간 구간, 좌표와 소요 시간 값 객체를 테스트부터 작성한다.
- [x] DST gap·overlap이 있는 `America/New_York`으로 `ZoneRules` 기반 변환 테스트를 작성한다.
- [x] 모임 생성, 참여, 조건 제출 버전, 조율과 확정 상태 전이 규칙을 테스트부터 작성한다.
- [x] 코드 리뷰와 import 검사로 도메인 코드에 Spring·Komapper·외부 SDK 의존성이 없음을 검증한다.
- [x] Clock·UUID를 경계 값으로 받고 후보를 rank로 정렬하여 동일 입력이 동일한 결과를 만드는 결정론적 테스트 기반을 마련한다.

## Phase 2 — PostgreSQL과 마이그레이션 기반

**선행 조건:** DG-02

- [x] Komapper JDBC 7.0.0·KSP 2.3.12, Spring Boot 관리 PostgreSQL·Flyway·Testcontainers 의존성을 고정하고 coroutine 1.11.0 호환성을 검증한다.
- [x] 로컬·테스트·운영 프로파일의 데이터베이스 설정 경계를 구성한다.
- [x] 익명 브라우저 세션, 방별 참여자·주최자 소유권, 모임, 제출 버전, 분석 배치·시도, 후보, 최종 확정과 Outbox 초기 스키마를 설계한다. 소셜 계정·공급자 grant는 제외한다.
- [x] 최초 Flyway `V1__create_core_domain.sql` 마이그레이션을 추가한다.
- [x] 영속 `*Record`·`@KomapperEntityDef`와 도메인 모델 Mapper를 Persistence Adapter에 분리한다.
- [x] Aggregate별 outbound persistence port와 Komapper PostgreSQL adapter를 구현한다.
- [x] `CoordinationPersistenceService.persist`에 `@Transactional`을 적용하고 CoordinationRun·Outbox 원자성과 rollback을 PostgreSQL에서 통합 테스트한다.
- [x] fresh migrate·validate, 제약과 도메인–Record 왕복 매핑 통합 테스트를 추가한다.
- [x] Flyway SQL을 스키마 기준으로 명시하고 Testcontainers의 fresh migrate·checksum validate로 적용된 파일 변경을 탐지한다.

## Phase 3 — 제출 MVP 익명 세션과 권한

**관련 요구사항:** FR-000A, FR-002C, FR-007A, FR-009, FR-012
**선행 조건:** DG-02, ADR-032

- [x] 익명 브라우저 자격 증명의 발급·검증·만료·회수 흐름을 구현한다.
- [x] 자격 증명을 `Set-Cookie`로만 전달하고 응답 JSON·URL·프론트엔드 저장소에 노출하지 않는 계약 테스트를 작성한다.
- [x] PostgreSQL에 자격 증명 원문이 아닌 단방향 해시와 만료·회수 상태만 저장하는 영속성 테스트를 작성한다.
- [x] 방 생성 세션에 첫 참여자와 `HOST` 역할을 원자적으로 연결한다.
- [ ] 같은 세션만 방 마감·재분석·후보 확정을 수행하는 권한 테스트를 작성한다.
- [x] 하나의 익명 브라우저 세션이 여러 방의 참여자를 소유하고 `(browser session, room)` 중복 참여는 기존 참여자로 귀결되는 영속성·동시성 테스트를 작성한다.
- [ ] 공유 초대 링크, 누락·위조 쿠키와 다른 참여자의 세션으로 본인 입력 또는 주최자 권한을 얻지 못하는 보안 테스트를 작성한다.
- [x] 익명 세션 cookie의 `SameSite`·`Domain`·`Path`·`Max-Age`, CSRF와 허용 Origin 계약을 확정하고 테스트한다.
- [ ] `[DEFERRED]` 공개 방 생성·참여·제출·Gemini 재분석 endpoint의 Redis·Nginx 요청 제한은 5번 브랜치에서 통합 구현한다.
- [x] 인증 실패와 권한 오류의 공통 API 응답, Controller `@ApiResponse`와 DTO `@Schema`를 작성한다.
- [ ] 자격 증명과 개인정보가 로그에 남지 않는지 검증한다.

## Phase 4 — 모임 생성과 참여

**관련 요구사항:** FR-001, FR-001A, FR-001B, FR-002, FR-010A, FR-010B, FR-013
**선행 조건:** Phase 1, Phase 2, Phase 3

- [x] 모임 생성 inbound port와 애플리케이션 서비스를 테스트부터 구현한다.
- [x] 로그인 없이 방 생성을 허용하고 생성 요청의 익명 브라우저 세션에 주최자 참여를 연결하는 테스트를 작성한다.
- [x] 모임 목적, 소요 시간과 선호 방식 입력 검증을 구현한다.
- [x] 방 생성 시 주최자를 첫 번째 참여자로 같은 트랜잭션에서 생성하고 예상 참여 인원에 포함하는 테스트를 작성한다.
- [x] 예상 참여 인원을 설정할 때 2명 미만을 거부하는 검증 테스트를 작성한다.
- [x] MVP 방 생성 시 `Asia/Seoul` Zone ID를 저장하고 다른 Zone 선택을 허용하지 않는 계약 테스트를 작성한다.
- [x] `search_start_date`, `search_end_date`를 함께 선택하거나 함께 생략하도록 검증하고 한쪽만 전달하면 거부하는 테스트를 작성한다.
- [x] 탐색 날짜를 생략하면 방 시간대 생성일 기준 `[today, today + 14 days)`를 저장하고 응답하는 테스트를 작성한다.
- [x] 주최자 명시 범위와 기본 14일 적용이라는 탐색 범위 출처를 저장하고 방 응답에 제공하는 테스트를 작성한다.
- [x] 탐색 범위가 0일 이하이거나 31일을 초과하면 거부하는 테스트를 작성한다.
- [x] OpenAPI `@Schema`에 기본 14일 정책을 명시하여 프론트엔드가 방 생성 화면에 안내할 수 있게 한다.
- [x] 수동 전용 또는 예상 참여 인원·제출 마감 자동 조건을 갖는 완료 정책을 구현한다.
- [x] 자동 조건 전 수동 마감의 경고·재확인 및 주최자 권한을 구현한다.
- [x] 참여 링크는 내부 UUID와 분리한 128비트 base64url 초대 코드로 생성하도록 확정한다.
- [x] 참여 링크 생성과 조회 흐름을 구현한다.
- [x] 방 참여 유스케이스와 중복 참여 정책을 구현한다.
- [x] 일반 참여자가 서비스 로그인 없이 참여하고 자신의 제출 소유권 증명을 발급받는 흐름을 구현한다.
- [x] 모임 생성·참여 Web DTO와 Controller를 구현한다.
- [x] 성공 및 주요 오류 `@ApiResponse`, DTO `@Schema`를 작성한다.
- [x] 도메인·애플리케이션·Web·영속성 테스트를 각각 검증한다.
- [x] 생성된 OpenAPI 계약을 검증한다.

## Phase 5 — 참여자 조건 제출과 자연어 구조화

**관련 요구사항:** FR-004A, FR-005, FR-006, FR-007, FR-007A, FR-008, FR-008A, FR-008B, FR-008C, FR-009, FR-013, FR-014
**선행 조건:** DG-06, Phase 2, Phase 4

- [x] 제출 MVP의 nullable `raw_text`와 `manual_available_times` JSON 요청 계약을 확정한다. 자연어 또는 하나 이상의 가능 시간 중 하나를 요구하고 빈 가능 시간은 부가 제약 없음으로 처리한다.
- [x] 자연어와 수동 가능 시간이 모두 없는 요청을 `SUBMISSION_INPUT_REQUIRED`로 거부하고 제출 버전과 완료 인원을 만들지 않는 API·영속성 테스트를 작성한다.
- [x] 제출 MVP API가 `CALENDAR`, `calendar_blocked_times`와 `additional_blocked_times`를 노출하거나 수락하지 않는 계약 테스트를 작성한다.
- [x] 명시 범위에서는 실제 날짜형만, 기본 범위에서는 주간 반복형만 수동 가능 시간으로 허용하는 검증을 구현한다.
- [x] 수동 격자의 연속·중첩 선택 구간 병합과 범위·요일·시작·종료 검증 계약을 확정한다.
- [x] 구조화된 시간 조건, 특정 가능한 장소, 이동 제약과 미확정 장소의 내부 스키마 및 검증 규칙을 확정한다.
- [x] 방 Zone ID와 제출 BCP 47 locale을 AI Adapter 문맥으로 전달하고 언어 중립 Structured Output으로 변환하는 계약을 테스트한다.
- [x] 자연어 파싱 outbound port와 공급자 독립 실패 타입을 정의한다.
- [x] nullable 자연어와 정형 일정 입력의 원본 제출 버전 및 최신 참조를 저장하되 Gemini를 호출하지 않고 즉시 응답하는 접수 API를 구현한다.
- [x] 본인 증명을 거쳐 자신의 nullable `raw_text`, 수동 가능 시간과 수정 가능 여부를 조회하는 API를 구현한다.
- [x] 익명 참여자는 본인 입력만 조회하고 주최자 역할·공유 링크·타인 자격 증명으로 다른 입력을 조회할 수 없는 계약·권한 테스트를 작성한다.
- [x] 제출 접수 트랜잭션 커밋 시 고유 참여자 완료 인원을 증가시키고 예상 인원 도달 시 입력 수집을 종료하는 테스트를 작성한다.
- [x] `N`번째와 후속 제출이 동시에 도착해도 정확히 `N`개의 고유 참여자 제출만 접수되고 나머지는 종료 오류가 되는 동시성 테스트를 작성한다.
- [x] 입력 수집 종료 트랜잭션이 종료 시점의 최신 버전을 방 전체 제출 배치로 고정하고, 자연어가 있을 때만 논리 작업과 Outbox 이벤트를 하나 만드는 테스트를 작성한다.
- [x] 수집 중 수정본을 불변 버전으로 저장하고 고유 참여자 완료 인원은 증가하지 않는 테스트를 작성한다.
- [x] 입력 수집 종료 시점의 최신 버전만 매칭 입력으로 고정하고 이후 수정은 거부하는 테스트를 작성한다.
- [x] 수정과 종료의 동시 요청에서 커밋 순서에 따라 수정본 포함 또는 종료 오류 중 하나만 성립하는 동시성 테스트를 작성한다.
- [x] 이벤트 ID와 방 전체 제출 배치 ID만 Outbox에 기록하고 처리 서비스가 PostgreSQL에서 원문을 조회하는 비동기 경계를 구현한다.
- [x] Gemini adapter의 Structured Output 계약 테스트를 작성한다.
- [x] 참가자 제출·수정 중에는 호출하지 않고 자연어가 있는 제출만 방 전체 논리 작업 하나에 포함되는지 테스트한다.
- [x] 수동 슬롯만 있는 참여자는 AI 요청에서 제외하되 매칭에는 포함되고, 전원이 수동 슬롯만 제출한 방은 Gemini 작업을 만들지 않는지 테스트한다.
- [x] 참가자별 500자·배치 합계 10,000자 경계와 초과 입력 무절단 거부를 테스트한다.
- [x] 최초 1회와 최대 3회 기술 재시도, Full Jitter 범위, `Retry-After`, 호출별 15초·전체 60초 timeout을 가상 시계와 주입 가능한 난수로 테스트한다.
- [x] 기술적 재시도 소진 시 입력과 고정 배치를 보존하고 `ANALYSIS_DELAYED`로 전이하며 후보와 주최자용 원문 공개를 만들지 않는 테스트를 작성한다.
- [x] 방 생성 익명 세션의 재분석 요청이 같은 고정 배치에 새 Outbox 이벤트를 멱등하게 만들고 참여자 재입력을 요구하지 않는 API를 구현·테스트한다.
- [x] 배치 응답의 불투명 입력 참조값과 개수가 요청과 정확히 일치하는지 검증한다.
- [x] Gemini 결과를 서버 스키마로 재검증하고 잘못된 출력을 거부한다.
- [x] 조건 단위 검증으로 유효한 자연어 조건과 수동 가능 시간은 유지하고 해석 불가능한 조건만 제외하는 테스트를 작성한다.
- [ ] `[BRANCH 4]` 성공한 Gemini 응답의 미반영 조건을 후보 집합 `PARTIAL`과 공개 `READY_WITH_WARNINGS`로 연결한다.
- [x] `집/회사/학교 근처`가 이동 제약으로, 하나로 특정되지 않는 지명이 미확정 장소로 분류되는 계약 테스트를 작성한다.
- [x] LLM이 만든 좌표를 거부하는 테스트를 작성한다. `[BRANCH 4]` Geo Adapter 고유성 검증은 Kakao 정규화와 함께 구현한다.
- [x] 원본 제출 메타데이터와 정형 조건을 PostgreSQL에 저장한다. `[BRANCH 4]` 지도 검색 정규화 스냅샷을 추가한다.
- [x] 다른 참여자에게 개별 조건이 노출되지 않는지 API 테스트한다.
- [x] AI 호출 횟수, 토큰과 추정 비용 메타데이터를 기록한다.
- [x] 민감한 원문·좌표·공급자 응답이 로그와 Outbox에 남지 않는지 테스트한다.
- [x] Controller `@ApiResponse`, DTO `@Schema`와 OpenAPI 계약을 검증한다.

## Phase 6 — 결정론적 일정·장소 매칭

**관련 요구사항:** FR-004A, FR-010, FR-011, FR-011A
**선행 조건:** DG-01, DG-07, Phase 1, Phase 5

- [ ] 시간 구간 정규화와 교집합 계산 테스트를 작성한다.
- [ ] 수동 가능 시간이 있으면 자연어 또는 중립 기준 구간과 교차하고, 빈 배열이면 자연어 기준 구간을 유지하며, 자연어와 슬롯이 모두 없으면 제출을 거부하는 테스트를 작성한다.
- [ ] 자연어 요일·시간 조건을 방 시간대와 탐색 범위의 실제 날짜별 구간으로 확장하는 테스트를 작성한다.
- [ ] 후보가 방의 `[searchStartDate, searchEndDate)` 지역 날짜 범위를 벗어나지 않는 테스트를 작성한다.
- [ ] 고정 슬롯 양자화 없이 남은 연속 구간이 모임 소요 시간을 만족하는지 테스트한다.
- [ ] 좌표 간 거리 계산 테스트를 작성한다.
- [ ] 사용자 명시 반경과 기본 1km 허용 반경 적용 테스트를 작성한다.
- [ ] 한 참여자의 대안 장소 허용 영역 합집합 테스트를 작성한다.
- [ ] 여러 참여자의 장소 허용 영역 교집합과 교집합 부재 테스트를 작성한다.
- [ ] `봉천역`과 `서울대입구역` 사이 공통 허용 영역에서 후보를 만드는 회귀 테스트를 작성한다.
- [ ] 이동 제약 또는 미확정 장소만 있는 조건 분기가 오프라인 후보를 만들지 않는 테스트를 작성한다.
- [ ] 전원 참석·오프라인 Plan A 계산을 구현한다.
- [ ] 전원 참석·온라인 Plan B 계산을 구현한다.
- [ ] 전원 교집합이 없을 때 `N-1`, `N-2` 순의 Plan C 계산을 구현한다.
- [ ] 합의한 점수 계산과 동률 정렬 규칙을 구현한다.
- [ ] 동일 입력 반복 실행의 결과가 동일한지 속성/회귀 테스트한다.
- [ ] 매칭 도메인에서 LLM과 외부 SDK를 호출하지 않는지 검증한다.
- [ ] 구조화 후보에서 MessageSource 기반 자연어 요약을 생성하고 제외된 날짜·시간을 가능하다고 표현하지 않는 의미 일치 테스트를 작성한다.
- [ ] 반복 조건에서 예외 수가 실제 가능 날짜 수보다 적으면 패턴과 모든 예외를, 동률 또는 예외가 더 많으면 실제 날짜 목록을 선택하는 경계 테스트를 작성한다.
- [ ] 일부 시간만 남은 날짜를 예외로 표시하고 특정 날짜·불규칙 후보를 실제 날짜 목록으로 표시하는 테스트를 작성한다.
- [ ] 예상 인원 충족, 제출 마감과 수동 마감이 경합해도 매칭을 한 번만 시작하도록 구현한다.
- [ ] 데드라인·수동 마감 시 고유 제출이 2개 미만이면 `INSUFFICIENT_PARTICIPANTS`로 종료하고 후보를 생성하지 않는 테스트를 작성한다.
- [ ] 의미 검증에서 미반영 입력이 있으면 후보 집합을 `PARTIAL`로 만들고 반영·전체 제출 수와 미반영 입력 수를 저장하되, `ANALYSIS_DELAYED`에서는 매칭하지 않는 테스트를 작성한다.
- [ ] 후보를 PostgreSQL에 저장하고 조회 유스케이스를 구현한다.
- [ ] 후보 조회 API가 언어 중립 구조화 결과와 locale별 자연어 요약을 함께 제공하도록 구현하고 OpenAPI 계약을 검증한다.

## Phase 7 — 최종 결과 확정과 공유

**관련 요구사항:** FR-008B, FR-008C, FR-009, FR-012, FR-012A  
**선행 조건:** Phase 6

- [ ] 주최자만 후보를 확정할 수 있는 권한 테스트를 작성한다.
- [ ] 주최자용 부분 결과에만 참여자 표시 이름, 실패 원문과 미반영 사유를 제공하는 API를 구현한다.
- [ ] 미반영 원문을 안전한 일반 텍스트로 반환하고 다른 참여자용 결과에는 포함하지 않는 보안 테스트를 작성한다.
- [ ] 제출 전에 실패 원문의 주최자 공개 가능성을 고지할 수 있도록 API 계약에 공개 정책을 명시한다.
- [ ] 다른 참여자의 원문 수정·재처리 API와 별도 부분 결과 확인 필드가 존재하지 않는지 계약 테스트로 검증한다.
- [ ] 후보가 현재 모임에 속하고 아직 유효한지 검증한다.
- [ ] 중복 확정과 동시 요청 처리 정책을 선택하고 구현한다.
- [ ] 최종 선택을 PostgreSQL에 원자적으로 저장한다.
- [ ] 참여자용 확정 결과 조회 API를 구현한다.
- [ ] 주요 충돌·권한 오류 `@ApiResponse`와 DTO `@Schema`를 작성한다.
- [ ] 확정 및 결과 조회 OpenAPI 계약과 통합 테스트를 검증한다.

## Phase 8 — Redis, 신뢰성, 보안과 관측성

**선행 조건:** DG-04 및 실제 사용 목적이 발생한 기능 단계

- [ ] 선택한 책임에 한해 Redis port와 adapter를 구현한다.
- [ ] PostgreSQL Outbox 저장·relay와 Redis Streams Consumer Group adapter를 구현한다.
- [ ] Outbox 재발행과 Stream 중복 전달에도 LLM 호출·매칭 상태 전이가 중복 실행되지 않도록 멱등성 테스트를 작성한다.
- [ ] Stream Pending 작업 회수, 보류·실패 처리와 trimming 정책을 구현하고 장애 복구를 테스트한다.
- [ ] 기술적 재시도 소진으로 ACK된 `ANALYSIS_DELAYED` 작업과 역직렬화·불변식 위반·반복 Worker crash 같은 poison message를 구분하는 테스트를 작성한다.
- [ ] 동일 메시지 총 5회 실패 시 PostgreSQL 영구 실패 상태를 먼저 기록하고 원문 없는 참조형 Redis DLQ로 옮기는 흐름을 구현·테스트한다.
- [ ] Actuator·Micrometer 애플리케이션 지표와 표준 출력 JSON 구조화 로그를 구현한다.
- [ ] Alloy가 Prometheus 지표와 JSON 로그를 필터링해 Grafana Cloud Metrics·Loki로 보내도록 구성한다.
- [ ] Grafana-managed Alerting 규칙과 대시보드를 코드 또는 재현 가능한 설정으로 관리하고 Gemini 배치 실패를 운영자가 인지하는지 검증한다.
- [ ] Redis 장애와 데이터 유실이 영구 데이터 유실로 이어지지 않는지 테스트한다.
- [ ] `ANALYSIS_DELAYED` 방의 입력·고정 배치가 Redis 유실과 프로세스 재시작 뒤에도 PostgreSQL에서 복구되는지 테스트한다.
- [ ] 중복 제출과 중복 매칭 실행의 멱등성 전략을 구현한다.
- [ ] 외부 API별 타임아웃, 재시도와 회로 차단 필요성을 비교하고 선택한다.
- [ ] 요청 상관관계 ID와 구조화 로그를 구성한다.
- [ ] 로그 마스킹과 민감정보 회귀 테스트를 추가한다.
- [ ] 미반영 원문이 로그, 지표와 Redis Stream 메시지 payload에 포함되지 않는지 검증한다.
- [ ] 인증 실패, 외부 API 지연·오류, 매칭 시간과 결과 수 지표를 추가한다.
- [ ] 모임별 AI 비용 집계를 구현하고 10원 미만 여부를 측정한다.
- [ ] 일정·좌표·토큰의 보관 및 삭제 정책을 구현한다.

## Phase 9 — 로컬 실행, 인프라와 배포

**선행 조건:** DG-08

- [ ] 합의한 로컬 PostgreSQL·Redis 실행 구성을 추가한다.
- [ ] 애플리케이션 컨테이너 이미지와 비루트 실행 설정을 추가한다.
- [ ] Nginx TLS 종료, 라우팅, 요청 제한과 헬스체크 범위를 구현한다.
- [ ] Terraform 모듈과 환경 구성을 설계하고 검토받는다.
- [ ] Route 53 Hosted Zone, `app`·`api` DNS 레코드와 ACM 인증서 검증을 Terraform으로 구현한다.
- [ ] RDS for PostgreSQL과 네트워크 구성을 Terraform으로 구현한다.
- [ ] 선택한 EC2/ECS 런타임을 Terraform으로 구현한다.
- [ ] 선택한 Redis 운영 배치를 구현한다.
- [ ] 이미지 레지스트리 인증과 배포 파이프라인을 구현한다.
- [ ] Flyway 실행 순서, 배포 실패와 롤백 절차를 검증한다.
- [ ] 비밀정보가 저장소, 이미지, Terraform state와 CI 로그에 노출되지 않는지 검증한다.
- [ ] 운영 헬스체크, 로그, 지표와 알림을 검증한다.

## Phase 10 — Wanted 제출 MVP 통합 검증과 출시 준비

- [ ] 로그인 없이 익명 세션 발급 → 방 생성 → 주최자 참여 등록 → 링크 공유 흐름을 E2E 검증한다.
- [ ] 익명 참여자의 자연어 전용·수동 슬롯 전용·두 입력 조합 제출과 본인 최신 입력 복원 흐름을 E2E 검증한다.
- [ ] 공유 링크·다른 세션·누락 또는 위조 쿠키로 주최자 명령과 타인 입력에 접근하지 못하는지 E2E 검증한다.
- [ ] 제출 MVP에 Google·Kakao 로그인과 Calendar OAuth endpoint가 노출되지 않는지 검증한다.
- [ ] 마지막 제출 → Plan A/B/C 생성 → 호스트 확정 → 결과 조회를 E2E 검증한다.
- [ ] 정상 반영된 조건의 블라인드 입력과 미반영 원문의 주최자 한정 예외가 유지되는지 보안 관점에서 검증한다.
- [ ] Gemini 방 전체 배치별 논리 작업, Full Jitter 기술 재시도 범위와 실제 비용 기록을 운영과 유사한 환경에서 검증한다.
- [ ] 파싱 실패 → `PARTIAL` 후보 → 주최자 미반영 원문 확인 → 최종 확정 흐름을 E2E 검증한다.
- [ ] Gemini 기술적 실패 → `ANALYSIS_DELAYED` → 로딩 종료 → 주최자 재분석 → 결과 생성 흐름과 타인 원문 비공개를 E2E 검증한다.
- [ ] 장소 허용 영역 합집합·교집합, 이동 제약·미확정 장소와 Plan B/C fallback 회귀 시나리오를 검증한다.
- [ ] Swagger/OpenAPI가 구현 응답과 일치하는지 전체 검증한다.
- [ ] `ko-KR` message bundle, 지원하지 않는 locale fallback, 언어 중립 오류 코드와 시간대 직렬화를 통합 검증한다.
- [ ] 주요 개인정보·토큰·좌표가 로그와 오류 응답에 노출되지 않는지 점검한다.
- [ ] 성능 목표와 예상 동시 사용자 부하를 합의하고 부하 테스트한다.
- [ ] 백업, 복구, 마이그레이션과 롤백 리허설을 수행한다.
- [ ] MVP 완료 조건을 PRD 10절과 대조하여 전부 확인한다.
- [ ] 미완료 TBD와 Post-MVP 항목이 아래 별도 백로그에 남아 있는지 확인한다.
- [ ] 출시 승인 체크리스트와 운영 인계 문서를 완료한다.

## Post-MVP — 소셜 로그인과 Google Calendar

### PM-01 Google·Kakao 로그인과 서비스 토큰

**관련 요구사항:** FR-002A, FR-002B, FR-002D
**선행 조건:** 제출 MVP 출시, DG-03

- [ ] Google·Kakao 개발·운영 OAuth 앱, scope, callback과 계정 연결 정책을 확정한다.
- [ ] Google과 Kakao 로그인 adapter 계약 테스트를 작성하고 구현한다.
- [ ] 공급자 사용자 식별자와 서비스 사용자 연결 유스케이스를 구현한다.
- [ ] RS256 Access Token과 Redis TTL 기반 회전형 Refresh Token의 발급·갱신·폐기를 구현한다.
- [ ] 현재 기기·모든 기기 로그아웃과 공급자 grant 유지 계약을 구현한다.
- [ ] 로그인 주최자만 방 생성·재분석·확정을 수행하도록 전환한다.
- [ ] 익명 방·참여자 소유권을 로그인 계정에 연결하거나 유지하는 마이그레이션 정책을 확정한다.
- [ ] OAuth/JWT/Refresh cookie OpenAPI·보안·로그 마스킹 테스트를 검증한다.

### PM-02 Google Calendar 불가 시간 수집

**관련 요구사항:** FR-003, FR-003A, FR-004, FR-004B
**선행 조건:** PM-01, DG-05

- [ ] Calendar 조회 outbound port의 내부 모델과 실패 타입을 정의하고 adapter 계약 테스트를 작성한다.
- [ ] 최소 scope로 대상 Calendar의 FreeBusy를 조회하고 절대 불가 시간으로 변환한다.
- [ ] Calendar 연결·조회와 방별 ON/OFF를 분리한다.
- [ ] Calendar ON 제출·수정 시점의 정규화 불가 시간과 조회 완료 시각을 불변 제출 버전에 저장한다.
- [ ] 종일·반복·취소 일정, 시간대, 빈 일정과 공급자 실패를 구분한다.
- [ ] 입력 수집 종료에서는 공급자를 다시 호출하지 않고 최신 제출 스냅샷만 사용하는지 검증한다.
- [ ] 권한 철회, 만료 토큰, 할당량·일시 장애와 공급자 token 암호화를 구현한다.
- [ ] 제출·매칭·E2E와 OpenAPI에 Calendar 모드를 추가하고 익명 사용자의 OAuth를 거부한다.

## PRD 요구사항 추적

| 요구사항 | 구현 단계 | 상태 |
| --- | --- | --- |
| FR-000A 제출 MVP 익명 접근·주최자 권한 | Phase 3, 4, 10 | 방 생성·참여·마감 권한 완료, 후속 주최자 명령 남음 |
| FR-001 방 생성 | Phase 4 | 완료 |
| FR-001A 주최자의 참여자 등록·예상 인원 포함 | Phase 1, 4 | 완료 |
| FR-001B 주최자 선택 탐색 범위와 기본 14일 표시 | Phase 4, 6 | 방 계약 완료, 후보 경계 남음 |
| FR-002 참여 링크 | Phase 4 | 완료 |
| FR-002A 주최자 필수 Google·Kakao 로그인 | Post-MVP PM-01 | Post-MVP |
| FR-002B 서비스 계정 연결 | Post-MVP PM-01 | Post-MVP |
| FR-002C 익명 참여자 본인 증명 | Phase 3, 4, 5 | 세션·참여 소유권 완료, 제출 소유권 남음 |
| FR-003 로그인 사용자 Calendar 연동 | Post-MVP PM-02 | Post-MVP |
| FR-003A Calendar 연결과 방별 ON/OFF 적용 분리 | Post-MVP PM-02 | Post-MVP |
| FR-004 Calendar 불가 시간 변환 | Post-MVP PM-02 | Post-MVP |
| FR-004B Calendar ON 제출 시점 스냅샷 고정 | Post-MVP PM-02 | Post-MVP |
| FR-004A 제출 MVP 수동 가능 시간 격자 | Phase 1, 4, 5, 6 | 시간 모델·영속 기반 완료 |
| FR-005 선택적 자연어 조건과 슬롯 전용 제출 | Phase 5 | 미착수 |
| FR-006 위치 표현 분류와 검증된 장소 정규화 | Phase 5 | 미착수 |
| FR-006A 이동 제약·미확정 장소의 좌표 생성 금지 | Phase 5 | 미착수 |
| FR-007 1-Pass Payload | Phase 5 | 미착수 |
| FR-007A 본인 최신 제출 조회 | Phase 3, 5 | 미착수 |
| FR-008 방 전체 제출 배치별 논리 파싱 작업·입력 제한·기술 오류 재시도 | Phase 5, 8 | 미착수 |
| FR-008A 수집 종료 전 수정과 최신 버전 매칭 | Phase 5 | 미착수 |
| FR-008B 기술 실패 분석 지연·의미 실패 부분 결과 | Phase 5, 6, 7, 8 | 미착수 |
| FR-008C 주최자 방 단위 재분석 | Phase 3, 5, 8 | 미착수 |
| FR-009 참여자별 조건 비공개 | Phase 3, 5 | 미착수 |
| FR-010 완료 조건 후 매칭 | Phase 4, 5, 6 | 종료 정책·수동 마감 완료, 자동 종료·매칭 남음 |
| FR-010A 주최자 수동 조기 마감 | Phase 4 | 완료 |
| FR-010B 최소 2명 제출 전 후보 생성 금지 | Phase 4, 6 | 공개 상태 완료, 후보 생성 차단 남음 |
| FR-011 Plan A/B/C | Phase 6 | 미착수 |
| FR-011A 구조화 후보·자연어 요약 | Phase 6 | 미착수 |
| FR-012 주최자 최종 확정 | Phase 3, 7 | 미착수 |
| FR-012A 부분 결과의 무수정·무추가확인 원클릭 확정 | Phase 7 | 미착수 |
| FR-013 IANA Zone ID와 UTC 기반 시간 모델 | Phase 1, 4, 5 | 도메인·DST 기반 완료 |
| FR-014 i18n과 언어 중립 API 코드 | Phase 0, 3, 5 | 공통 ProblemDetail·오류 코드 완료, 제출 API 남음 |

## 진행 기록

| 날짜 | 변경 내용 | 검증/근거 | Commit/PR |
| --- | --- | --- | --- |
| 2026-09-04 | 구현 계획 최초 작성. 현재 프로젝트 기반과 Codex 훅 진행 상태 반영 | PRD, Architecture, ADR, 저장소 상태 대조 | 동일 커밋 예정 |
| 2026-09-04 | Flyway SQL 마이그레이션 기본 디렉터리 준비 | `src/main/resources/db/migration/.gitkeep` 확인 | 동일 커밋 예정 |
| 2026-09-05 | 외부 계정·권한·결제·동의·비밀정보의 사용자 개입 규칙과 행동 가이드 체크포인트 추가 | `user-intervention.md`, AGENTS, 프로젝트 스킬과 계획 간 일치 확인 | 동일 커밋 예정 |
| 2026-09-05 | 선택지 B 위험도 기반 TDD 채택. Red-Green-Refactor, 고위험 역할 분리와 TDD 가드 강화 설계 반영 | `development.md`, SessionStart 지침과 실행 계획 대조 | 동일 커밋 예정 |
| 2026-09-05 | 선택적 예상 참여 인원·제출 마감·수동 마감 정책과 비동기 처리 경계 반영. Redis 작업 전달 책임은 선택 대기 | PRD, Architecture, 계획의 요구사항·TBD 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 비동기 작업 전달에 PostgreSQL Transactional Outbox + Redis Streams 선택, Pub/Sub 사용 경계 확정 | Architecture, ADR-011과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 자연어 전용 장소 입력, 특정 가능한 장소의 허용 영역 교집합, 이동 제약·미확정 장소의 좌표 생성 금지 확정 | PRD, Architecture, ADR-012와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 주최자는 항상 첫 번째 참여자이며 예상 참여 인원에 포함하고 조건 제출 대상임을 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | PostgreSQL 접수 완료를 예상 인원 충족 기준으로 삼고, 접수 마감 후 방 전체 구조화와 매칭을 분리 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 예상 참여 인원은 가입 정원이 아닌 고유 참여자 제출 목표이며 N번째 제출까지만 원자적으로 접수하도록 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 입력 수집 종료 전 자유 수정, 종료 시점 최신 버전 고정과 이후 수정 금지 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 일시적 파싱 오류 재시도, 유효 조건 기반 부분 결과와 실패 원문의 주최자 한정 공개 확정 | PRD, Architecture, ADR-013과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 부분 결과의 미반영 원문은 참고용으로만 제공하고 주최자 수정·재처리·추가 확인 없이 원클릭 확정하도록 확정 | PRD, Architecture, ADR-013과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 예상 참여 인원과 후보 생성의 최소 제출 인원을 주최자 포함 2명으로 확정 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | MVP `Asia/Seoul`·`ko-KR` 고정과 IANA Zone ID·UTC·BCP 47·MessageSource 기반 확장 경계 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 주최자 선택 후보 탐색 날짜 범위와 생략 시 오늘부터 14일 기본값·프론트엔드 표시 의무 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 후보 탐색 범위 최대 31일 확정. 최종 결과의 구체 날짜·시간과 반복 요일·시간 패턴 지원 범위는 결정 대기 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 탐색 범위를 날짜별 검증 근거로 사용하고 Calendar 불가 시간을 차감한 구조화 후보와 서버 i18n 자연어 요약을 함께 제공하도록 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 실제 가능 날짜 수와 모든 예외 날짜 수를 비교하는 손실 없는 자연어 압축 규칙 및 동률 시 실제 날짜 나열 우선 확정 | PRD, Architecture, ADR-014와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-05 | 자연어 중심 입력, LLM을 거치지 않는 선택적 수동 불가 시간 격자, 명시 범위의 날짜형·기본 14일의 주간 반복형과 연속 구간 매칭 확정. 수동 불가 입력 의미는 이후 ADR-023에서 대체 | PRD, Architecture, ADR-015와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 참가자 제출·수정 중 호출을 없애고 마감 시 최신 원문 전체를 방 배치 하나로 Gemini에 전달하도록 정정. 500자·총 10,000자 제한과 Full Jitter 최대 3회·15초/60초 timeout 확정 | PRD, Architecture, ADR-016과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 핵심 Decision Gate를 대부분 확정한 뒤 현재 초기 구성 전체를 하나의 initial commit으로 GitHub에 push하고 이후 브랜치 전략을 시작하도록 운영 순서 확정 | Implementation Plan 운영 규칙과 Phase 0 상태 검토 | 동일 커밋 예정 |
| 2026-09-07 | PostgreSQL 실패 이력, poison message용 참조형 Redis DLQ와 Actuator·Micrometer + Alloy + Grafana Cloud Metrics·Loki·Alerting 관측성 스택 확정 | Architecture, ADR-017, 사용자 개입 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | Redis 메시지는 첫 전달 포함 총 5회 처리 실패 시 DLQ로 이동하고 Gemini API 재시도와 별도로 계산하도록 확정 | PRD, Architecture, ADR-017과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | Gemini 기술적 재시도 소진을 `ANALYSIS_DELAYED`로 분리하고 입력 보존·로딩 종료·주최자 방 단위 재분석과 기술 장애 시 타인 원문 비공개를 확정 | PRD, Architecture, ADR-018과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 주최자 로그인 필수·일반 참여자 게스트 허용과 본인 최신 제출 조회 API의 권한 경계를 확정하고 게스트 본인 증명 방식은 후속 선택으로 유지 | PRD, Architecture, ADR-019와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 게스트 본인 증명에 서버 발급 `Secure HttpOnly` 불투명 자격 증명을 사용하고 PostgreSQL에는 해시만 저장하도록 확정 | PRD, Architecture, ADR-020과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 게스트 자격 증명을 브라우저 단위로 여러 방에서 재사용하고 같은 세션은 방마다 참여자 하나만 소유하도록 확정 | PRD, Architecture, ADR-021과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 주최자 외 참여자는 meet-me 로그인 없이 전체 흐름을 이용하고, 선택적 Google Calendar 인증·동의는 서비스 로그인과 분리하도록 명확화. 이후 ADR-022에서 게스트 Calendar 연동은 제외 | PRD FR-003, Architecture Calendar 경계와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | Google Calendar 연동을 로그인 사용자로 제한하고 비로그인 게스트에게 계정·다른 방에 재사용되지 않는 방 전용 시간 격자를 제공하도록 정정 | PRD, Architecture, ADR-022와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 게스트·Calendar 미연동 참여자는 방 전용 가능 시간을 선택하고 Calendar 연동 참여자는 공급자·추가 불가 시간을 차감하는 이중 일정 입력 모드 확정 | PRD, Architecture, ADR-023과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-07 | 시간 격자를 강조하지 않는 선택적 부가 기능으로 두고 자연어·수동 슬롯 중 하나만으로 제출 가능하게 확정. 빈 슬롯은 부가 제약 없음이며 자연어가 전혀 없는 합의된 정형 입력 방은 Gemini를 건너뜀. Calendar 전용 제출은 TBD로 유지 | PRD, Architecture, ADR-024와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: 로그인한 개인만 Calendar를 연결·조회하고, 연결한 참여자 본인이 현재 참여 중인 방에서 ON/OFF한다. ON은 불가 일정 외 탐색 범위를 허용하므로 자연어 없이 제출 가능하며 프론트엔드는 참여자·방별 최초 ON 때 확인 팝업을 한 번만 표시 | PRD, Architecture, ADR-025와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 빈 제출은 `가능 시간 없음`이 아니라 미입력으로 처리하도록 선택지 A 확정. 자연어·Calendar ON·수동 가능 시간이 모두 없으면 `SUBMISSION_INPUT_REQUIRED`로 거부하고 완료 인원에 포함하지 않음 | PRD, Architecture와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: Calendar ON 제출·수정 시점의 조회 결과를 불변 제출 버전에 고정하고 방 마감에서는 Google Calendar를 다시 호출하지 않음 | PRD, Architecture, ADR-026과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 B 확정: 방 입력 수집·고정 배치 조율 작업·후보 품질·최종 확정을 분리하고 API 공개 진행 상태를 파생하도록 결정 | Architecture, ADR-027과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: PostgreSQL 접근에 Komapper JDBC를 사용하고 도메인 Aggregate·Entity·VO, 영속 Record, Application·Web·외부 DTO를 경계별로 분리 | Architecture, ADR-028, Komapper 공식 호환성 문서와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 선택지 A 확정: 원자적 유스케이스의 Application 서비스 공개 메서드에 Spring `@Transactional`을 적용하고 Domain은 Spring 독립 유지 | Architecture, ADR-028, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | DB 선택지 A 확정: 로컬은 Docker Compose PostgreSQL·Redis, 영속성 통합 테스트는 H2 없이 PostgreSQL Testcontainers 사용 | PRD, Architecture, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | JWT 권장안 확정: 짧은 Access JWT와 Redis TTL 기반 회전형 불투명 Refresh Token 사용, 공급자 Refresh Token은 PostgreSQL 암호화 저장 | PRD, Architecture, ADR-029와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | Gemini 연동에 공식 Google GenAI SDK Java 클라이언트를 사용하고 SDK 타입을 AI Adapter 내부에 격리하도록 확정 | PRD, Architecture, Google 공식 SDK 문서와 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 인증 수명·전달 확정: Access JWT 15분, Refresh 미사용 14일·패밀리 최대 30일, Access는 body 발급 후 Authorization 헤더, Refresh는 Secure HttpOnly Set-Cookie 사용 | PRD, Architecture, ADR-029, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-08 | 인증 서명·브라우저 방어 확정: RS256·`kid` 키 교체, same-site 서브도메인 배치, API 호스트 전용 `SameSite=Lax` Refresh 쿠키와 엄격한 Origin 검증 채택 | PRD, Architecture, ADR-030, Architecture 규칙과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | 로그아웃 선택지 C 확정: 일반 로그아웃은 현재 Refresh 패밀리만, 별도의 모든 기기 로그아웃은 사용자 전체 패밀리를 폐기하며 공급자 연결은 유지 | PRD FR-002D, Architecture, ADR-031과 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | TDD RED 증거 선택지 C 확정: Git에는 최소 메타데이터·테스트 지문 요약을 추적하고 전체 실패 출력은 로컬 전용으로 분리 | Development Rules와 TDD 가드 강화 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | Mutation Testing 선택지 C 확정: 순수 Domain은 PIT 자동 변이 테스트, 보안·영속성·동시성·외부 Adapter는 의도적 결함 주입 검증 적용 | Development Rules와 품질 게이트 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | PIT 실행 시점 권장안 B 확정: Domain 변경 PR은 관련 범위를 필수 검사하고 로컬은 선택 실행하며 정기 CI에서 전체 Domain 검사 | Development Rules와 CI 품질 게이트 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | PIT 운영 결정을 선택지 B로 조정: initial commit·Phase 1에서는 제외하고 핵심 결정론적 매칭 로직 구현 후 효과가 큰 패키지에만 선택 적용. 이전 즉시 PR·정기 실행 결정은 대체 | Development Rules와 품질 게이트 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | 아키텍처 자동 검사 도구 도입을 후속으로 연기: initial commit·Phase 1에서는 코드 리뷰로 헥사고날 의존 방향을 확인하고 기능·Adapter 증가나 실제 경계 위반 시 ArchUnit·Konsist를 재비교 | Development Rules와 Phase 0 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-15 | PR 템플릿을 위험 기반형으로 단순화하고 사람이 자연스럽게 읽을 수 있는 구체적인 표현을 사용하도록 개발 규칙에 반영 | Pull Request 템플릿, Development Rules와 Phase 0 계획 간 일치 검토 | 동일 커밋 예정 |
| 2026-09-16 | 빈 원격 저장소에 PR 기준점용 `main` 커밋 `64e5759`와 `develop`을 push하고 최신 `develop`에서 `feature/initial-setup` 생성 | `git pull --ff-only origin develop` 성공, 브랜치와 원격 추적 상태 확인 | `64e5759`, 초기 구성은 동일 커밋 예정 |
| 2026-09-16 | 초기 구성 커밋 전 전체 품질 검사 수행 | TDD 가드 자체 테스트 6개 통과, `ktlintCheck`, `assemble`, `test` 성공 | 동일 커밋 예정 |
| 2026-09-16 | Issue #2의 `ko-KR` 기본 locale, `Accept-Language` fallback과 UTF-8 MessageSource 기반 구현 | locale·메시지 focused test 통과, `ktlintCheck`, `assemble`, `test` 성공 | 동일 커밋 예정, #2 |
| 2026-09-16 | 작업 전 Issue 생성, `<type>: <summary>` 명사형 PR 제목과 `Closes #<issue-number>` 자동 종료 흐름을 개발 규칙·PR 템플릿에 명시 | Issue #2와 PR #3 연결 및 템플릿·개발 규칙 일치 검토 | 동일 커밋 예정, #2, PR #3 |
| 2026-09-17 | Issue #4의 기본 CI와 `develop` 병합 Issue 자동 종료 구현. GitHub 기본 closing keyword가 비기본 브랜치에서 무시되는 경계를 프로젝트 Action으로 보완 | 파서·Issue API 처리 테스트 9개, actionlint v1.7.12, `ktlintCheck`, `assemble`, `test`와 PR `Quality Gate`·`Validate Issue Link` 통과 | `0523463`, #4, PR #5 |
| 2026-09-17 | 단위 작업 완료 후 PR 제목·본문 전체 초안을 사용자에게 제시하고 승인 뒤 생성하는 검토 흐름 추가 | Development Rules와 운영 규칙 간 일치 검토 | `0523463`, #4, PR #5 |
| 2026-09-17 | PR #5 병합 후 `Close Linked Issues` 성공과 Issue #4 `completed` 종료 확인 | GitHub PR·Issue 상태와 `develop` 병합 커밋 `b0fed8a` 확인 | `b0fed8a`, #4, PR #5 |
| 2026-09-17 | Issue #6의 RED 증거 Draft 2020-12 JSON Schema, 생성·무효화·보존 수명주기와 로컬 원본 로그 Git 제외 규칙 추가. 2026-09-19 출시 목표와 권장안 우선 결정 원칙 반영 | 계약 테스트 5개, 전체 훅 테스트 11개, `git check-ignore`, `ktlintCheck`, `assemble`, `test` 통과 | 동일 커밋 예정, #6 |
| 2026-09-17 | 실제 Gemini API Key 입력용 로컬 `.env.local`, 키 이름만 있는 `.env.example`과 Secret Git 제외 규칙 추가. 후속 GitHub Actions·배포 Secret은 종속 구현 직전 사용자 행동 가이드를 제공하도록 계획에 명시 | `.env.local` ignore 및 `.env.example` 추적 대상 확인, 실제 값 미포함 확인 | 동일 커밋 예정, #6 |
| 2026-09-18 | Issue #8의 Gemini·Google/Kakao OAuth·GitHub/AWS·Grafana Cloud 사용자 개입 실행 가이드와 로컬 설정 이름 목록 추가. Free Tier 합성 테스트 후 Paid 전환, 개발용 OAuth 앱·localhost callback·GitHub `integration` Environment 선제 준비와 운영 자격 증명 분리 원칙 반영 | 공식 공급자 문서와 Architecture TBD 대조, 실제 비밀값 미포함 여부 검증 예정 | 동일 커밋 예정, #8 |
| 2026-09-18 | Wanted AI Champion 제출 MVP를 로그인 없는 익명 주최자 모드로 재범위화하고 Google·Kakao 로그인과 Google Calendar를 Post-MVP로 분리 | PRD FR-000A, Architecture 제출 MVP 접근 모드, ADR-032와 Phase·요구사항 추적 일치 검토 예정 | 동일 커밋 예정, #8 |
| 2026-09-18 | 사용자가 후속 구현의 커밋·push·PR 생성을 승인 대기 없이 진행하도록 위임하고 사용자 개입 선행 → 자율 구현 → Post-MVP 순서를 명시 | Development Rules와 Implementation Plan 실행 순서 대조 예정 | 동일 커밋 예정, #8 |
| 2026-09-18 | 가비아에서 운영 domain `meet-me.co.kr` 확보. `app`·`api` origin과 Route 53 위임 절차 확정 | Architecture 배포 구조와 사용자 개입 가이드 일치, DNS 변경은 Hosted Zone 생성 뒤 수행 | 동일 커밋 예정, #8 |
| 2026-09-18 | 제출 MVP의 장소 검색·정규화 공급자로 Kakao Local API 확정 | Architecture, ADR-033과 `KAKAO_LOCAL_API_KEY` 설정 계약 반영 | 동일 커밋 예정, #8 |
| 2026-09-18 | Kakao Local REST API Key를 로컬과 GitHub `integration` Environment에 등록 | 실제 값 출력 없이 두 위치의 `KAKAO_LOCAL_API_KEY` 존재 여부 확인 | 동일 커밋 예정, #8 |
| 2026-09-18 | 사용자 개입 선행 준비와 Wanted 제출 MVP 재범위화 문서 검증 | `git diff --check`, hook 단위 테스트, `ktlintCheck`, `assemble`, `test` 통과. `.env.local` Git 제외와 실제 secret 미추적 확인 | 동일 커밋 예정, #8 |
| 2026-09-18 | 제출 MVP 6개와 Post-MVP 2개 구현 브랜치의 이름·Phase 범위·번호 호출 계약 확정 | 각 Phase와 선행 병합 규칙, 자동 실행 위임 및 사용자 전용 승인 경계 대조. `git diff --check`, hook 테스트 11개, `ktlintCheck`, `assemble`, `test` 통과 | PR #9 후속 커밋 예정, #8 |
| 2026-09-19 | Issue #10의 4개 핵심 Aggregate와 상태 전이, IANA 시간·DST gap/overlap, 좌표·기간·실제 날짜형·주간 반복형 값 객체 구현 | Domain RED 15개 중 의도한 11개 실패 확인 후 15개 전체 GREEN, 프레임워크 import 부재 검토 | 동일 커밋 예정, #10 |
| 2026-09-19 | Komapper 7.0.0·KSP 2.3.12, Flyway V1, PostgreSQL 18·Redis 8 Compose, Record·Mapper·adapter와 profile 설정 구현 | PostgreSQL Testcontainers fresh migrate·validate, Aggregate 왕복과 CoordinationRun·Outbox rollback 통합 테스트 통과 | 동일 커밋 예정, #10 |
| 2026-09-19 | 고위험 결함 주입 JSON 계약 추가 및 `@Transactional` 제거 결함이 rollback 테스트에서 탐지되는지 검증 | 결함 주입 시 테스트 실패, 복구 후 source·test SHA-256 기록. 전체 출력은 로컬 전용 로그로 분리 | 동일 커밋 예정, #10 |
| 2026-09-19 | Phase 1~2 구현 전체 품질 게이트와 로컬 Compose 계약 검증 | hook 테스트 12개, Domain 15개·PostgreSQL 통합 4개 포함 전체 24개 테스트, `ktlintCheck`, `assemble`, `test`, `docker compose config`, `git diff --check` 통과 | 동일 커밋 예정, #10 |
| 2026-09-19 | Issue #12의 30일 고정 익명 세션, 128비트 초대 코드, 표시 이름과 방 생성·조회·참여·HOST 수동 마감 API 구현 | Domain·보안 RED 11개, V2 migration RED 4개, 전체 51개·hook 12개 테스트, 결함 주입 4종, `ktlintCheck`, `assemble`, `test`, `git diff --check` 통과 | 동일 커밋 예정, #12 |
| 2026-09-19 | Issue #14의 조건 제출·수정·본인 조회, 50명 상한, 자동·수동·데드라인 마감의 최신 배치 고정, 참조형 Outbox와 `gemini-3.8-flash` Structured Output·분석 지연 재요청 구현 | RED 4개, 전체 76개·hook 12개 테스트, V3 fresh/upgrade, 동시성·멱등성 및 좌표·원문·잠금 결함 주입 3종, `ktlintCheck`, `assemble`, `test`, `git diff --check` 통과 | 동일 커밋 예정, #14 |
