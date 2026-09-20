# meet-me-server

AI 기반 일정 조율 서비스의 Spring Boot/Kotlin 백엔드입니다. 핵심 도메인은
외부 프레임워크와 공급자 SDK에서 분리하는 헥사고날 아키텍처를 사용합니다.

## 요구 환경

- Java 17+
- 저장소에 포함된 Gradle Wrapper
- 로컬 PostgreSQL과 Redis를 위한 Docker

## 로컬 실행

```shell
docker compose up -d postgres redis
./gradlew bootRun --args='--spring.profiles.active=local'
```

애플리케이션은 PostgreSQL Outbox를 Redis Streams로 전달합니다. 로컬 Prometheus
지표는 `/actuator/prometheus`에서 확인할 수 있고 로그는 JSON으로 표준 출력에
기록됩니다. Grafana Alloy와 Grafana Cloud Metrics/Loki 연동은 Post-MVP 범위입니다.

## 검증

```shell
./gradlew ktlintCheck assemble test
```

Windows PowerShell에서는 `./gradlew.bat`을 사용합니다.

## 로컬 비밀정보

`.env.example`을 기준으로 저장소 루트의 `.env.local`에 실제 값을 입력합니다.
`.env.local`은 Git에서 제외되며 실제 API Key, Client Secret과 Token은 채팅,
Issue, PR 또는 커밋에 남기지 않습니다.

```properties
GEMINI_API_KEY=
KAKAO_LOCAL_API_KEY=
GOOGLE_OAUTH_CLIENT_ID=
GOOGLE_OAUTH_CLIENT_SECRET=
KAKAO_OAUTH_CLIENT_ID=
KAKAO_OAUTH_CLIENT_SECRET=
```

Wanted 제출 MVP에서 애플리케이션이 읽을 외부 값은 `GEMINI_API_KEY`와
`KAKAO_LOCAL_API_KEY`입니다. Google·Kakao OAuth 값은 Post-MVP까지 비워 둡니다.
현재 CI에는 외부 Secret이 필요하지 않으며, 발급 위치·최소 권한과 GitHub/AWS 배포 시 저장 위치는
[`docs/USER_INTERVENTION.md`](docs/USER_INTERVENTION.md)를 따릅니다.

Codex가 `git commit`을 실행할 때 같은 검증이 `.codex/hooks/tdd_guard.py`를
통해 자동으로 실행됩니다. 프로덕션 Kotlin 코드가 변경되었다면
`src/test/kotlin` 아래의 테스트도 함께 변경해야 합니다.

## 패키지 경계

```text
com.meetme.server
├── meetingroom/{domain,application,adapter}
├── participant/{domain,application,adapter}
├── submission/{domain,application,adapter}
├── coordination/{domain,application,adapter}
├── shared
└── config
```

각 Aggregate 패키지 안에서 `adapter → application → domain` 의존 방향을
유지합니다. 공통 ID·시간 값과 횡단 기술 계약만 `shared`에 둡니다.

제품 요구사항과 기술 결정은 각각 `docs/PRD.md`, `docs/ARCHITECTURE.md`,
`docs/ADR.md`를 따릅니다.
