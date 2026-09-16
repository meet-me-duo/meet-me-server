# Architecture Rules

## 기준 문서

- 제품 요구사항: `docs/PRD.md`
- 시스템 구조와 확정·미정 기술 항목: `docs/ARCHITECTURE.md`
- 장기 영향이 큰 결정과 트레이드오프: `docs/ADR.md`

문서가 충돌하면 임의로 해석하지 말고 최신 사용자 결정 또는 관련 ADR을 확인한다.

## 의존성 경계

- 헥사고날 아키텍처의 의존성은 외부 어댑터에서 애플리케이션과 도메인 방향으로 향해야 한다.
- 도메인 로직은 Spring, Komapper, Redis, HTTP Client와 외부 SDK 타입에 직접 의존하지 않는다.
- 도메인 Aggregate·Entity·Value Object, Web DTO, 외부 API DTO와 Komapper 영속 `Record`를 분리한다.
- Komapper 애너테이션, KSP 매핑 정의와 생성 메타모델은 Persistence Adapter 내부에만 둔다.
- 원자적 유스케이스의 트랜잭션 경계는 Application 서비스 메서드의 Spring `@Transactional`로 선언하되 Domain에는 Spring 트랜잭션 타입을 노출하지 않는다.
- 데이터베이스 의존 통합 테스트는 H2 대체 구현이 아니라 Testcontainers의 실제 PostgreSQL로 검증한다.
- 외부 시스템 접근은 outbound port 뒤에 둔다. 시스템 진입점은 inbound port 또는 use case로 표현한다.
- 실제 교체 가능성이나 독립 테스트 가치가 없는 경계에 형식적인 포트를 추가하지 않는다.

## 핵심 불변 조건

- PostgreSQL은 영구 비즈니스 데이터의 최종 기준이다.
- PostgreSQL 스키마 변경은 Flyway의 버전 관리 마이그레이션으로 수행한다.
- 이미 공유되거나 적용된 Flyway 마이그레이션 파일은 수정하지 않고 새 마이그레이션으로 변경한다.
- Redis 장애나 데이터 유실이 영구 비즈니스 데이터 유실로 이어져서는 안 된다.
- meet-me Access Token은 15분 수명의 RS256 JWT로 검증하고 `kid` 기반 검증 키 교체를 지원한다. 불투명 Refresh Token은 미사용 14일, 토큰 패밀리는 최초 로그인부터 최대 30일이며 해시·회전 상태와 TTL을 Redis에 둔다. Access Token은 응답 body로 발급한 뒤 `Authorization: Bearer` 헤더로 받고, Refresh Token은 API 호스트 전용 `Secure HttpOnly; SameSite=Lax; Path=/api/auth` 쿠키의 `Set-Cookie` 헤더로만 전달한다. Refresh·로그아웃은 정확한 프론트엔드 Origin을 검증한다. 일반 로그아웃은 현재 Refresh 패밀리만, 별도 모든 기기 로그아웃은 사용자에게 속한 전체 Refresh 패밀리를 폐기하며 기존 Access Token은 최대 15분간 유효할 수 있다. 서비스 로그아웃은 Google·Kakao 공급자 연결을 해제하지 않는다. 공급자 Refresh Token은 서비스 토큰과 분리하여 암호화된 영구 저장소에 둔다.
- AI는 OCR과 비정형 입력 구조화만 담당한다.
- 시간 교집합, 거리 판정과 Plan A/B/C 계산은 결정론적 도메인 로직으로 구현한다.
- 동일한 매칭 입력은 동일한 결과를 생성해야 한다.

## 미정 사항

`docs/ARCHITECTURE.md`에서 TBD로 표시된 항목은 사용자와 합의하기 전 확정하거나 종속 구현을 시작하지 않는다.
