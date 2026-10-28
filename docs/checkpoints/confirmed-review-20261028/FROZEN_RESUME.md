# Meet me 리뷰 기능 Cloud 재개 체크포인트

상태: 로컬 초안 동결. Cloud 수신·동일성 검증 전이며 완성/배포 상태가 아니다.
서버 기준03bbdc1909d22c0bad4667eedea6e2a0c2ffef87, 웹 기준656f757244104482e00605991967e62a12810b63.
각 저장소의 branch/git status/모든 변경 파일 SHA256/실행한 검증/미완료/정책은 manifest.json에 있다.
리뷰 작업의 신규 커밋·push·PR은 없다. GitHub 이슈145 생성·재조회만 원격 반영됐다.

## 복구와 동일성 확인

1. Cloud에서 이 zip을 내려받고 부모가 전달한 zip SHA256과 비교한다.
2. 새 디렉터리에 두 GitHub 저장소를 복제한다. 각 manifest의 정확한 base_sha를 checkout하고 독립 feature 브랜치를 만든다. 기존 사용자 작업 디렉터리에 덮어쓰지 않는다.
3. `python restore_checkpoint.py server /absolute/new/server`와 `python restore_checkpoint.py web /absolute/new/web`을 실행한다. 스크립트는 정확한 HEAD와 깨끗한 작업 트리를 요구하며, 포함된 파일들을 각각 SHA256 검증 후 복원한다. tracked.patch는 보조 근거이며 파일 복사와 중복 적용하지 않는다.
4. 복원된 파일들의 SHA256 및 git status와 manifest를 대조하고 부모에게 수신 검증 결과를 알린다. 이 단계 전 이관 완료라고 하지 않는다.
5. 새로운 실행 환경의 AGENTS.md/관련 skills를 읽는다. 서버의 기존 project-architecture와 development 지침은 exact base에 있다.

## 이어 할 일

- 서버 ReviewService claim/submit/close는 TODO다. HTTP16 테스트 중15가 이 TODO에서 실패한 유효 RED이므로 기대값을 약화하지 않고 서비스를 구현한다. Domain/DTO/JDBC/SQL/테스트는 초안이며 GREEN/FI/전체게이트 전이다.
- 서버 신규 test26개 중HTTP16만 실행했다. storage7·defense3은 미실행. `.tdd/red` 정식 요약·native OpenAPI export도 아직 없다. 원본 RED 로그는 개인 PC `.codex/tdd-evidence`에만 남겼다.
- 웹 컴포넌트와 ResultPanel 성공 경로2곳을 연결했다. 기능 테스트19개와 synthetic Edge4개는 서로 다른 실행 시점의 증거다. 최종 lifecycle 수정 이후 전체 unit/lint/typecheck/build/E2E 재검증이 필요하다.
- 최신 타입 검사에서 독립 UI 테스트29/62행의 possibly-undefined2건이 남았다. 테스트 작성자 검토 아래 타입만 바로잡고 정책 검증을 약화하지 않는다.
- 최신 원격/동시 AI 작업의 migration과 충돌을 확인하여 docs/drafts/review-schema.sql을 정식 Flyway 번호로 배치한다. 현재는 자동 적용되지 않는다. 기존 AI/AWS/운영/공통 디자인토큰을 수정하지 않는다.
- 실제 로컬 OpenAPI에서 review3경로와 참조DTO만 웹 snapshot에 합치고 타입 생성·API 검사한다.
- ReviewService 트랜잭션/owner lock/terminal CAS와 verified-claim trigger의 rollback·게스트/계정·멱등·다중기기·비공개 경계를 독립검토/FI로 검증한다.
- main 병합·운영배포는 아직 금지. 원격 브랜치/commit/PR도 이번 이관에서 수행하지 않았다.

## 동시 작업 소유권

UI 다른 스레드: feature/ui-brand-refresh, develop656f757 기준이며 common styles/account/landing만 수정. 리뷰·ResultPanel/API는 미변경이라고 부모가 확인했다. 해당 복제본을 덮어쓰지 않는다.
다른 서버 스레드는 AI 운영 활성화. 이 체크포인트는 review 전용 패키지와 draft SQL만 소유하며 AI·운영 설정을 건드리지 않았다.

## 보존·제외

원본 두 저장소와 미커밋 작업을 보존했다. 이 체크포인트는 독립 복제본의 코드·문서·테스트만 포함한다.
.env/자격증명/.git history/node_modules/build/cache/실행 로그/브라우저 스크린샷을 전송하지 않는다.
로컬 합성 스크린샷2개는 파일명·checksum만 manifest excluded_files에 남기고 bytes는 개인PC에 유지한다.
