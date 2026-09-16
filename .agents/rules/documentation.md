# Documentation Rules

## 문서별 책임

- `docs/PRD.md`: 사용자가 원하는 제품 행동, MVP 범위, 비즈니스 규칙과 성공 지표
- `docs/ARCHITECTURE.md`: 시스템 구조, 기술 스택, 컴포넌트 책임, 데이터 흐름과 기술적 미정 사항
- `docs/ADR.md`: 차후 구현에 큰 영향을 주는 확정된 아키텍처 결정
- `.agents/rules/development.md`: 브랜치 전략, TDD, 코드 리뷰, 테스트·검증 및 개발 흐름
- `.agents/rules/api-documentation.md`: 프론트엔드가 사용하는 Swagger/OpenAPI 작성 규칙
- `.agents/rules/user-intervention.md`: 외부 계정·권한·결제·동의·비밀정보 작업의 사용자/에이전트 책임과 행동 가이드 규칙

## 변경 규칙

- 요구사항 변경은 PRD에, 기술 구조 변경은 Architecture에 반영한다.
- 한 결정이 여러 문서에 영향을 줄 때 각 문서의 책임에 해당하는 내용만 갱신하고 문장을 복제하지 않는다.
- 아직 합의하지 않은 선택은 `TBD`로 표시한다.
- 링크, 경로, 모델명과 임계값은 관련 기준 문서 사이에서 일치시킨다.
- 문서 날짜나 현재 지원 상태처럼 변할 수 있는 사실은 확인한 근거와 확인 시점을 함께 남긴다.
