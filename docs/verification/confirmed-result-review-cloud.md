# 확정 결과 리뷰 Cloud 검증 기록

이 문서는 Cloud의 새 실행을 원본 개인 PC의 동결 결과와 구분한다. 이관 원본은 변경하지 않는다.

## 수신 동일성

- 서버 checkpoint `f196e6b8655fd17c097dc7db0549c7f3e1fcf3d2`, base `03bbdc1909d22c0bad4667eedea6e2a0c2ffef87`.
- 웹 checkpoint `ea67eaca936eb2dad4ac5a735e433da2cbd37376`, base `656f757244104482e00605991967e62a12810b63`.
- 원격 HEAD 및 parent/base 일치. Git manifest는 서버 19개·웹 20개 모두 blob/checkout SHA256 일치하며 manifest 자신의 파일은 commit object로 검증한다.
- 원본 ZIP 59,812 bytes, SHA256 `e12a0a9cce361c792febc5948d4e4bfa21a2f3d01a306a801bd4e3878bcbc13e`. 내부 원본 23개(서버 11·웹 12)의 크기·SHA256 일치, Git 소스는 LF 정규화 후 일치. 원래 untracked 소스도 포함한다. 합성 캡처 2개는 manifest에 명시된 제외 자료다.

## Cloud RED와 핵심 GREEN

- 원본 26개와 독립 권한/전환 테스트 5개: 31개 중 TODO 21 FAIL·10 PASS. 컴파일/환경 실패가 아닌 `NotImplementedError` 도달을 XML로 확인했다.
- 추가한 native 문서·비공개/롤백/정리 검사와 스타일 정리 후 최종 40개: TODO 26개와 명시적 계약 assertion 3개 FAIL·11 PASS. 지문은 `.tdd/red/issue-145-cloud-review.json`에 기록하고 원본 로그는 로컬 `.codex/tdd-evidence/issue-145-cloud-review.log`에만 둔다.
- claim/submit/close, review 전용 no-store 필터, native DTO schema와 정수 enum 교정 후 40개 PASS·실패/오류/skip 0. V19 Flyway를 실제 PostgreSQL에 적용한 결과다.
- AI 활성 checkpoint의 활성 migration 변경 0과 기준 V18을 확인하고 `V19__add_confirmed_result_reviews.sql`을 등록했다. 기존 공유 migration은 변경하지 않았다.

## 후속 검사 상태

- 첫 `ktlintCheck assemble test` 실행은 main ktlint/assemble을 통과했지만 신규 review Aggregate 미등록과 기존 migration 수 18 단정에서 회귀를 발견했다. 해당 리뷰 wrapper client만 중단(exit143)했으며 전체 test 완료로 집계하지 않는다. 목록에 review를 포함하고 19개 migration 및 새 두 테이블을 추가 검증한다. 기존 경계/legacy upgrade 검사를 유지한다.
- 좁은 웹 snapshot 병합의 operationId 충돌은 별도 1개 계약 테스트 RED(함께 실행한 architecture/migration 13개 PASS) 후 `claimReviewPrompt`·`submitReviewPrompt`·`closeReviewPrompt`로 고정했다. 최종 리뷰 41 PASS/skip0 및 실제 native 재export를 확인했다. 웹은 리뷰 3 POST·DTO 4개만 추가하고 기존 모든 path/schema/metadata를 보존했다. operationId 중복은 0개다.
- 최종 `ktlintCheck assemble test` exit0. 256 suites / 2,204 tests 중 2,200 PASS·기존 opt-in 4 SKIP·실패/오류 0, 10분 9초의 실제 새 실행이다. Skip은 기존 live Gemini 1개와 선택적 OpenAPI export/debug 3개이며 소스/조건을 변경하지 않았다.
- 독립 FI 4/4를 실제 JUnit assertion으로 탐지했다. READ_OWN→REPLACE_OWN 권한 우회, stale pending eligibility 제거, 동일 제출 재시도 기한 연장, submit 트랜잭션 제거를 각각 검증하고 매번 원본 bytes와 22개 지문을 복원했다. `.tdd/verification/issue-145-cloud-review-fi.json`은 스키마 검증을 통과했다.
- 실제 loopback OAuth/JWKS/JWT/refresh·Spring HTTP·PostgreSQL/Redis를 연결한 브라우저 12/12 PASS(33.3초), page.route/API 응답 mock 0. `docs/verification/confirmed-review-native-20261028.md`와 웹의 `docs/verification/confirmed-review-cloud-web-20261028.md`를 함께 따른다. 서버는 독립 classpath/artifact로 동결하여 FI와 겹쳐도 변이 코드를 실행하지 않았다. stop-file 정상 종료 exit0, 본인 프로세스·포트·PG/Redis 리소스 정리를 확인했고 공유 자원은 변경하지 않았다.
- 웹 전체 unit 711 PASS, 전체 SPA+대역 E2E 437 PASS/기존 1 SKIP, 합성 브라우저 4 PASS 이후 최종 계약 동기화에 대해 생성/API 정합/typecheck/build/관련 unit 40 PASS를 추가 확인했다. 중복 집계하지 않는다.
- 로컬 JSON/XML/원본 로그는 worktree 상위 checkpoint 및 ignored `.codex/tdd-evidence`에 보존한다. 원본 실행 로그·토큰·비밀값을 Git에 추가하지 않는다.

## 제한과 보존

기타 입력 2,000 Unicode 코드포인트와 버튼 배치/문구는 기존 초안 상태다. 자동 검증이 제품정책 또는 최종 디자인 확정을 뜻하지 않는다. V18 주석이 아직 문서에 없는 ADR-060을 참조하는 기존 불일치를 보존하며, 새 리뷰 결정은 ADR-062로 기록해 번호를 재사용하지 않는다. AI의 별도 ADR-061과 함께 보존한다.

운영 AWS·실제 OAuth/모델·실기기·Safari 검증, main/develop 변경·원격 push·PR·운영 배포는 이 실행에 포함하지 않는다. 원본 개인 PC와 checkpoint 브랜치는 유지한다.
