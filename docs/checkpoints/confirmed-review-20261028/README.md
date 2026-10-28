# 확정 결과 리뷰 Cloud 이관 체크포인트

이 브랜치는 **미완성 코드의 보존·이관용**이다. 구현 완료나 릴리스 승인으로 사용하지 않는다.
관련 이슈: https://github.com/meet-me-duo/meet-me-server/issues/145

- 저장소: https://github.com/meet-me-duo/meet-me-server.git
- 정확한 기준 SHA: `03bbdc1909d22c0bad4667eedea6e2a0c2ffef87`
- 이관 브랜치: `feature/review-cloud-checkpoint-20261028`
- 작업 시작 브랜치: `feature/confirmed-result-review`
- 보존 대상: frozen-manifest.json의 `server` 변경 파일 전부. 이 문서와 원본 zip·재개 절차·복원 도구·비밀 검사 결과·Git blob checksum을 추가한다.
- 제외: .env/자격증명/개인정보·raw 실행 로그/node_modules/build/cache/브라우저 스크린샷. 원본 저장소와 기존 Library 체크포인트는 보존한다.

## 승인과 배포 경계

2026-10-28 사용자가 공식 Library 전송 오류의 대안으로 별도 GitHub 작업 브랜치의 commit·push와 Cloud의 exact-commit 대조를 명시 승인했다. 미완성/TODO/미실행 검증을 숨기지 않는 checkpoint 전송이며, 이 목적에 필요한 commit을 승인한 것이다. main/develop 변경·PR 병합·운영 배포·AWS 실행은 승인 범위 밖이다.

두 저장소 workflow를 읽어 feature push가 운영배포를 실행하지 않는 것을 확인했다. 서버 CI의 push는 main/develop이고 운영배포는 성공한 main CI의 workflow_run에서만 시작된다. 웹 운영배포 push 대상은 main이며 수동 workflow는 실행하지 않는다. workflow·hook·보호규칙은 변경하지 않는다.

## 현재 검증과 미완료

- 서버 HTTP/PostgreSQL16: 15 FAIL(ReviewService TODO), OpenAPI 경로1 PASS. storage7·persistence3은 작성만 했으며 미실행이다.
- 서버 claim/submit/close는 TODO이며 GREEN·FI·전체 품질 게이트·정식 RED 요약·OpenAPI export가 미완료다. SQL은 docs/drafts/review-schema.sql이고 정식 Flyway 번호·자동적용은 아직 없다.
- 웹 작성자7·독립8·결과연결4 테스트가 통과했다. 앞의15개는 최종 pageshow lifecycle 추가 이전 결과이므로 최종 전체 rerun이 필요하다. 합성 Edge 데스크톱/모바일4개도 통과했지만 실제 backend 종단 검증은 아니다.
- 최신 웹 타입 검사에는 ReviewPrompt.contract.test.tsx29/62행의 possibly-undefined2건이 남아 있다. 기능을 수정하지 않고 현 상태를 보존한다.
- commit 전 서버 기존 guard를 변경 없이 실행해 결과를 별도 기록한다. 이 체크포인트를 품질 게이트 통과나 배포 가능으로 표시하지 않는다.

## Cloud 수신 검증

1. 부모가 전달한 원격 최종 commit SHA를 새 디렉터리에 checkout하고 branch/HEAD를 재조회한다.
2. git-blob-checksums.json의 각 경로에 대해 `git show HEAD:<path>`의 bytes SHA256을 대조한다. Windows↔Linux checkout 줄바꿈 차이와 Git object 동일성을 구분한다.
3. 원본 작업 바이트가 필요하면 포함된 `meet-me-review-cloud-checkpoint-20261028.zip`를 풀고 frozen-manifest.json의 파일별 SHA256과 비교한다. zip SHA256은 `e12a0a9cce361c792febc5948d4e4bfa21a2f3d01a306a801bd4e3878bcbc13e`다. 원본 zip은 서버11·웹12 코드/문서/테스트와 정확한 복원 절차를 담는다.
4. GitHub 소스와 zip 내부 소스의 Git LF 정규화 차이는 정상일 수 있다. original working-copy SHA256과 normalized Git blob SHA256은 별도 필드이며 어느 쪽도 다른 것으로 대체하지 않는다.
5. Cloud 수신·대조 성공을 알리기 전 이관 완료라고 하지 않는다. 승인된 구현은 Cloud에서 계속하고 AWS 인증·운영 적용은 개인 PC 후속으로 남긴다.

합의된 정책·API 초안·파일 소유권·상세 재개 순서는 FROZEN_RESUME.md와 frozen-manifest.json을 따른다. 해당 원본 문서의 ‘commit/push 없음’은 원본 zip 동결 시점의 기록이며 현재 브랜치의 신규 checkpoint commit과 구분한다.
