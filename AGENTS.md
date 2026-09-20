# meet-me-server Agent Guidance

## 작업 범위

- 이 저장소는 AI 기반 일정 조율 서비스의 백엔드만 다룬다.
- 제품 요구사항은 `docs/PRD.md`, 기술 구조는 `docs/ARCHITECTURE.md`, 장기 영향이 큰 결정은 `docs/ADR.md`를 기준으로 한다.
- 구현 순서와 진행 상황은 `implementation_plan.md`의 체크리스트로 관리하되, 제품·기술 결정의 기준 문서를 대체하지 않는다.
- 사용자와의 대화에서 범위, 우선순위 또는 결정이 바뀌면 `implementation_plan.md`도 현재 합의에 맞게 갱신한다.
- 브랜치, TDD, 코드 리뷰와 개발 흐름은 `.agents/rules/development.md`에서 관리한다.

## 필수 규칙

백엔드 코드, 인프라 또는 프로젝트 문서를 변경하기 전에 작업과 관련된 다음 규칙을 읽는다.

- `.agents/rules/architecture.md`
- `.agents/rules/documentation.md`
- `.agents/rules/adr.md`
- `.agents/rules/development.md`
- `.agents/rules/api-documentation.md`
- `.agents/rules/user-intervention.md`

## 프로젝트 스킬

- 아키텍처, 외부 연동, 데이터 경계 또는 배포 구조를 설계·변경할 때 `.agents/skills/project-architecture/SKILL.md`를 사용한다.

## 작업 원칙

- `develop` 브랜치에서는 코드, 인프라와 프로젝트 문서를 직접 변경하거나 커밋하지 않는다.
- `feature`, `fix`, `docs`, `chore` 작업 브랜치는 깨끗한 작업 트리에서 `develop`으로 이동하고 `git pull --ff-only origin develop`로 최신 상태를 받은 뒤에만 생성한다.
- `develop` 갱신이 충돌하거나 fast-forward 할 수 없으면 임의로 merge/rebase하지 않고 사용자에게 선택지와 트레이드오프를 제시한다.
- 확정된 결정과 TBD를 구분하고, TBD를 구현자의 판단으로 확정하지 않는다.
- 구현에 의미 있는 제약이나 복수의 방안이 있으면 구현 전에 각 선택지의 장점, 단점, 비용과 장기 영향을 트레이드오프로 정리하여 사용자에게 제시하고 선택을 요청한다.
- 쉽게 되돌릴 수 있고 외부 계약이나 아키텍처에 영향을 주지 않는 사소한 내부 구현 세부사항만 합리적인 기본값으로 진행할 수 있다.
- 변경 전 관련 문서를 확인하고, 변경 후 가장 작은 관련 테스트부터 검증한다.
- 사용자가 완료된 작업의 커밋과 푸시를 요청하면, 커밋 전에 `implementation_plan.md`의 체크박스, 현재 진행 요약, 최종 갱신일과 진행 기록을 먼저 실제 상태에 맞게 반영한다.
- 외부 계정, 권한, 결제, 동의 또는 비밀정보가 관련되면 `.agents/rules/user-intervention.md`에 따라 `[AGENT]`, `[USER]`, `[SHARED]` 책임을 분리한다.
- 사용자 개입이 필요하면 종속 작업 전에 이유, 서비스와 메뉴 위치, 사전 조건, 단계별 행동, 입력 형식, 공유 금지 값, 완료 확인, 후속 검증과 차단 범위를 안내한다.
- API Key, Client Secret, Access Token, 비밀번호와 복구 코드를 채팅이나 저장소에 입력하도록 요청하지 않는다.
- 비밀정보, 사용자 개인정보, OAuth 토큰, 외부 API 키를 저장소나 로그에 남기지 않는다.
