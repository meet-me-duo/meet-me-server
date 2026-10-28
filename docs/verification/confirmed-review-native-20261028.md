# 확정 결과 리뷰 native 브라우저 검증 · 2026-10-28

검증 대상은 Cloud의 리뷰 worktree다. `ReviewNativeBrowserServer.kt`는 test classpath에서만 실행하며 production route나 실제 외부 공급자·AWS 설정을 추가하지 않는다.

실제 Spring HTTP와 Testcontainers PostgreSQL 18/Redis 8, Flyway V1~V19를 사용했다. 로그인은 기존 `AuthEndToEndPostgresTest.IsolatedAuthProviders`의 loopback OAuth/JWKS 대역과 실제 callback·RS256 Access·Refresh 처리를 연결했다. Google 대역 계정은 HOST, Kakao 대역 계정은 MEMBER다. 운영 Google/Kakao 또는 AI 성공 근거로 확대하지 않는다.

`native-runtime/manifest.json`에 동결한 프로젝트 클래스·리소스 4개 디렉터리와 의존 JAR 216개의 SHA256, 리뷰 소스·V19·harness 지문을 기록했다. 독립 복사본으로 직접 Java 프로세스를 실행해 다른 worktree 빌드나 의도적 결함 주입이 이 프로세스의 classpath를 바꾸지 않게 했다. 종료 직전에도 산출물과 JAR 지문이 모두 일치했다. 기존 동결 리뷰 테스트 6개 및 operation ID 테스트의 소스 SHA256은 formatter·harness 컴파일 전후에 일치했다.

- 실제 legacy 확정 결과와 diverse-time 확정 결과 seed가 모두 PostgreSQL 제약을 통과했다. 인증 없는 review claim은 실제 HTTP 401과 `Cache-Control: no-store`를 반환했다.
- Chromium desktop/mobile에서 6개 흐름씩 총 12개가 통과했다. HOST/MEMBER 각자 요청, 만족 후 다른 방 억제, 불만족 제출의 7일 숨김, 미제출 원문 없는 체크 닫기, 일반 닫기·새로고침·다음 방, 동일 계정 다중 기기 억제를 검증했다.
- 브라우저 API 응답 대역과 `page.route`는 0개다. 실제 room/result/review HTTP 및 PostgreSQL 상태·개수/정책 boolean을 검증했다. 외부 origin·page error·읽기 실패 응답은 0개다. screenshot·trace·video와 토큰/Refresh 원문 캡처는 없다.
- 7일의 정확한 경계는 별도의 서버 Clock/PostgreSQL 테스트 근거와 구분한다. native 제어 서버는 OTHER 원문 대신 저장 행 수만 반환한다. 기타 입력 2,000 코드포인트와 버튼 배치가 원본 초안이라는 상태도 바꾸지 않는다.

API는 localhost:18930, 웹은 localhost:18931, 제어 서버는 127.0.0.1:18932에 한정했다. 제어 서버는 loopback·Origin 없는 1 KiB 이하 JSON 요청만 받는다. AI 호출 flag와 background worker는 비활성화했고, 테스트 전용 BeanDefinitionRegistryPostProcessor가 무조건 실행되던 일정 스캔까지 제거했음을 startup에서 확인했다.

브라우저 종료 후 이 프로세스의 stop file을 생성해 정상 종료(exit 0)했다. 소유 PostgreSQL·Redis 컨테이너가 제거됐고 Java PID와 API·control 포트가 닫혔음을 확인했다. 공유 Docker, 다른 컨테이너와 daemon은 정리하지 않았다.

실행 근거는 이 Cloud 환경의 `/workspace/checkpoints/review-20261028/native-validation.json`, `native-runtime/{manifest.json,smoke.json,cleanup.json}`, `native-browser/result.json`에 보존한다. 원본 실행 로그는 로컬에만 두며 저장소·Library에 전송하지 않는다.

- Artifact manifest SHA256: `d0992fa4e1e1e6867e5a3014f5bc10ab2dd828bf8805e65fa72a8274fe7876c7`
- Browser result SHA256: `2e97abfd573b108bc68f7f0e0b5c068dd030a9d0241967f759f04a492d289c2d`
- Harness source SHA256: `d8fcaf432ab193a544a7d9905b1049736f215c9db28e0acf0a404044b0110836`
