# meet-me-server

AI 기반 일정 조율 서비스의 Spring Boot/Kotlin 백엔드입니다. 핵심 도메인은
외부 프레임워크와 공급자 SDK에서 분리하는 헥사고날 아키텍처를 사용합니다.

## 요구 환경

- Java 17+
- 저장소에 포함된 Gradle Wrapper

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
```

현재 파일은 안전한 입력 위치만 준비한 상태입니다. Gemini 어댑터를 구현할 때
`GEMINI_API_KEY` 환경 변수 주입과 GitHub Actions Secret 참조를 연결합니다.

Codex가 `git commit`을 실행할 때 같은 검증이 `.codex/hooks/tdd_guard.py`를
통해 자동으로 실행됩니다. 프로덕션 Kotlin 코드가 변경되었다면
`src/test/kotlin` 아래의 테스트도 함께 변경해야 합니다.

## 패키지 경계

```text
com.meetme.server
├── domain
├── application
│   ├── port
│   │   ├── input
│   │   └── output
│   └── service
├── adapter
│   ├── input.web
│   └── output
│       ├── persistence
│       └── integration
└── config
```

- `domain`은 Spring, 데이터베이스, 캐시와 외부 SDK에 의존하지 않습니다.
- `application`은 유스케이스와 포트를 정의하고 도메인 흐름을 조정합니다.
- `adapter`는 HTTP, 영속성, AI/OAuth/Calendar/지도 연동을 구현합니다.
- `config`는 어댑터와 애플리케이션을 조립합니다.

제품 요구사항과 기술 결정은 각각 `docs/PRD.md`, `docs/ARCHITECTURE.md`,
`docs/ADR.md`를 따릅니다.
