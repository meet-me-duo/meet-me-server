# #108 격리 native 재현 구성

JDK17·Node24·pnpm·Docker와 로컬 Chromium을 사용했다. 서버는 `Issue108NativeApplication` test entrypoint이며 실제 Spring·PostgreSQL18·Redis8·JWT·OIDC adapter를 사용한다. OIDC endpoint만 test configuration에서 loopback으로 지정한다. 실제 공급자 계정이나 credential은 필요하지 않다.

서버 저장소에서 별도 터미널로 다음 fixture를 실행한다. 아래 DB 값은 fixture 전용 공개 합성 값이다.

```bash
umask 077
mkdir -p /tmp/issue108-native
docker run --rm --name issue108-native-pg -e POSTGRES_DB=meetme108 -e POSTGRES_USER=synthetic108 -e POSTGRES_PASSWORD=local-fixture-only -p 127.0.0.1:55432:5432 postgres:18-alpine
docker run --rm --name issue108-native-redis -p 127.0.0.1:56379:6379 redis:8-alpine
node src/test/fixtures/issue108-native/oidc.mjs
```

각 장기 프로세스는 별도 터미널을 사용한다. OIDC fixture가 임시 RSA key/JWKS를 `/tmp/issue108-native`에0600으로 생성하므로 OIDC를 먼저 한 번 시작하고 같은 검증 중 재시작하지 않는다. 로컬 포트와 container 이름은 이 fixture 전용으로 비워 둔다.

서버 저장소에서 test classpath를 생성한다.

```bash
./gradlew --no-daemon -I src/test/fixtures/issue108-native/classpath.gradle issue108NativeClasspath
```

JDK17의 `java`가 PATH에 있는 환경에서 다음과 같이 시작한다. test resources의 Flyway 비활성 기본값을 명시 CLI로 덮어쓰는 것이 필요하다.

```python
from pathlib import Path
import os

config = Path("src/test/fixtures/issue108-native/application-native.yml").resolve()
args = [
    "java", "-cp", Path("/tmp/issue108-native/classpath.txt").read_text().strip(),
    "com.meetme.server.participant.adapter.input.web.Issue108NativeApplication",
    f"--spring.config.additional-location=file:{config}",
    "--server.address=127.0.0.1", "--server.port=8080",
    "--spring.flyway.enabled=true",
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/meetme108",
    "--spring.datasource.username=synthetic108",
    "--spring.datasource.password=local-fixture-only",
    "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=56379",
    "--meetme.anonymous.allowed-origins=http://localhost:5173",
    "--meetme.anonymous.cookie-secure=false", "--meetme.rate-limit.enabled=true",
    "--meetme.reliability.enabled=false", "--meetme.data-retention.enabled=false",
]
os.execvp(args[0], args)
```

Flyway V1~V12 성공과8080 기동을 확인한다. 위 classpath를 직접 사용하는 재현에서는 실행 중 source compile·변이를 수행하지 않는다. 이번 최종 증거는 이 classpath의 프로젝트 디렉터리를 별도 immutable runtime으로 복사하고1,051개 파일 지문을 고정한 실행이다.

웹 저장소에서는 `VITE_API_BASE_URL`을 비워 상대 `/api`를 사용해 빌드한다. Vite preview의 기존 proxy가 로컬8080을 사용한다.

```bash
VITE_API_BASE_URL= corepack pnpm build
corepack pnpm exec vite preview --configLoader runner --host 127.0.0.1 --port 5173 --strictPort
node scripts/verify-guest-reaccess-native.mjs
node scripts/verify-account-native.mjs
```

실행 결과는 웹의 `docs/verification/issue-108-native.json`, `issue-109-after-108-native.json`, `issue-108-final-runtime-recheck.json`으로 구분한다. native script는 API를 fulfill하지 않고 loopback 이외의 브라우저 요청을 차단한다. 암호·토큰·쿠키·원문·전체 Playwright 오류를 결과에 기록하지 않는다. 한 번의16+12검증은 방4개를 만들며 시간당 생성 한도를 그대로 적용한다. 이번 최종 실행 후에는10/10이었고 reset·우회하지 않았다.

최종 native 이후에는 같은 Schema annotation 값을 유지한 Kotlin 인자 줄서식만 변경했다. manifest와 native 결과를 이 후속 서식 변경의 재실행으로 표시하지 않았으며, 최종 소스는 별도 전체 게이트와 정확 HEAD CI로 확인한다.
