# 리뷰 지원 힌트와 선행 웹 배포 호환 검증 · 2026-10-28

`RoomResponse.review_prompt_supported`는 리뷰 API 계약 지원을 알리는 optional boolean이다. 새 리뷰 서버는 항상 true를 광고하지만 참여·확정·본인 소유권·사용자 정책과 claim은 기존 서비스가 최종 판정한다. 이 힌트는 권한이나 eligibility가 아니다. 필드가 없거나 false 또는 잘못된 타입이면 웹은 리뷰를 요청하지 않는다.

백엔드 변경은 `RoomLifecycleDtos.kt`의 class body 상수 프로퍼티 7줄 추가뿐이다. 기존 생성자, 기본 인자, copy/from·RoomView·서비스·DB 조회/스키마·환경 토글·관리자 권한은 바꾸지 않았다. 변경 전 DTO 바이트·SHA와 HEAD는 별도 보존했다.

독립 `ReviewSupportHintPostgresTest`의 실제 room HTTP 및 native OpenAPI 두 검사가 필드 누락 때문에 명시적 assertion RED가 됐다. 최소 DTO 추가 뒤 ktlintCheck·assemble과 리뷰 43건이 모두 PASS/skip0이었다. 필드는 OpenAPI에서 생략 가능한 non-null boolean이고 기존 방 필드 25개와 리뷰 POST 세 경로를 보존한다. 기존 7개와 새 지원 힌트 테스트 1개 파일은 각각의 RED 지문과 일치한다.

실제 브라우저 호환 검증은 서로 다른 불변 backend artifact를 순서대로 실행했다.

- 이전 검증 artifact의 실제 legacy/diverse RoomResponse에는 지원 필드가 없다. 새 웹의 desktop/mobile 4건에서 Google HOST·Kakao MEMBER가 본인 확정 결과를 성공적으로 읽었고 리뷰 요청과 PostgreSQL prompt 생성은 0건이었다. 운영 서버 접속이나 운영 이미지 자체가 아니라 구버전 응답 형태를 갖춘 로컬 검증 산출물이다.
- 새 지원 artifact의 RoomResponse는 실제 HTTP에서 literal true다. 새 디자인과 함께 기존 사용자 정책·개인정보 경계를 검증한 desktop/mobile 12건이 모두 PASS했다. 실제 로컬 OAuth/JWKS→JWT/Refresh→Spring HTTP→PostgreSQL/Redis/Flyway V19를 연결했고 API 응답 mock·외부 공급자 요청·token capture는 0이다.
- false/malformed 지원 값은 별도 프런트 계약 검증 범위다. 이를 만들기 위한 백엔드 환경 토글이나 새 권한 판정은 추가하지 않았다. 정확한 7일 경계도 별도 서버 Clock/PostgreSQL 테스트와 구분한다.

과거 12건 proof는 덮어쓰지 않았다. 새 지원 artifact는 독립 클래스/리소스 4개 디렉터리와 의존 JAR 216개를 지문으로 고정했고 브라우저 종료 후에도 일치했다. 구버전 단계는 stop file 정상 종료 exit0을 받았다. 새 지원 단계도 stop file 요청 후 PID 부재, 소유 PostgreSQL·Redis 제거와 API/control 포트 종료를 직접 확인했다. 다만 wait 도구의 transport disconnected 및 뒤이은 unknown-process 응답으로 새 단계의 정확한 exit code는 회수하지 못했다. cleanup 완료와 종료 코드 미수신을 구분한다. 공유 Docker·다른 컨테이너·daemon은 정리하지 않았다.

실제 생성 OpenAPI는 `/workspace/checkpoints/review-brand-refresh-20261028/review-openapi-supported.json`, 실행 근거는 같은 폴더의 `backend-support-validation.json`, `native-old/result.json`, `native-supported/result.json`, 각 서버 `cleanup.json`에 있다. 원본 로그는 로컬 전용이며 저장소·Library에 올리지 않는다.

힌트 변경 후 전체 mandatory suite/guard를 실행한 것으로 주장하지 않는다. 이 단계는 scoped gate이며 별도 clean release worktree의 최종 전체 검증은 릴리스 담당이 수행한다. 운영 DB·원격 권한·AWS·운영 배포 변경은 이 작업에서 실행하지 않았다.
