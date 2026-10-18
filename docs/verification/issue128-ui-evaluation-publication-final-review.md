# #128 / web#32 UI·평가 준비 최종 독립 게시 리뷰

2026-10-18 UTC. 판정: **LOCAL PUBLICATION REVIEW PASS — 미해결 P1 0 / P2 0**. 검토한 현재 바이트와 아래 검증 범위에서 서버#128·웹#32의 별도 Draft PR 게시를 진행할 수 있다. Commit/push·새 PR 번호·그 게시 HEAD의 원격 CI는 아직 후속 단계다. 이 판정은 병합·배포·운영 설정·추가 유료 호출 승인이 아니다.

리뷰어 `/root/review_pre_submit_design`는 제품·테스트·실행 스크립트·Git을 수정하지 않았고 Gradle, API, 모델을 실행하지 않았다. 독립 명세/소스/실행 원본/지문을 읽어 대조했다. 서버 AGENTS와 관련 규칙, 기존 PRD·제출 전 계약 및 앞선 독립 설계 검토를 기준으로 판단했다.

## 검토한 변경과 불변식

- 서버 기준 `18cdb5ddec4f9e09db50feb54337aae9516a0108`, 웹 기준 `02a68d8e50680e87dd153156e8bb7ea884a794cc`. 각 기준의 미병합 PR126/PR31을 상속한 격리 작업 브랜치다. 기존 PR/브랜치 변경이나 병합을 전제하지 않는다.
- 서버 제품 delta는 snapshot의 질문 노출 조건 한 곳이다. 열린 방이고 유효 상태가 `NEEDS_CHOICE`일 때만 현재 질문을 반환한다. 웹 제품 delta도 `NEEDS_CHOICE`에서만 질문과 답변 controls를 활성화하는 한 조건이다. CANCELLED의 저장된 질문은 유지한다.
- 취소 후 과거 질문이 새 입력을 막던 P2는 닫혔다. mutation/reload 모두 질문이 숨겨지고 원문을 보존한 editor와 새 CREATE가 가능하다. 과거 답변·stale revision/check-version·타인 명령·마감 후 명령은 여전히 거부된다.
- question publication 이력과 3회 상한을 보존한다. 취소 시 질문을 지워 상한을 환급하는 변경은 없다. 원문/accepted/last CLEAR·멱등 replay·소유권/인증·마감·500자·검사/재시도 상한·전체 마감 분석/AREA·default OFF·공개 DTO/경로/DB schema는 변경하지 않았다. 추가 사용자 정책 결정은 필요하지 않다.

| 현재 파일 | SHA-256 |
| --- | --- |
| 서버 `PreSubmissionCheckService.kt` | `fa872f579a51742553935eec9e4e321fac42710178e4c772ccbb0ef152e45f5c` |
| 서버 `Issue128CancelledQuestionHttpPostgresTest.kt` | `8c22e9fda348eeae8483f8e2465789d9636a6f06af295b2184d3b041df9ffbe3` |
| 웹 `SubmissionCheckPanel.tsx` | `2bcf5ffa421d6f3ee30ad6c06224fa10da253fcafde8723ee4ea6c1c9942d8a2` |
| 웹 `SubmissionCheckPanel.status-lifecycle.test.tsx` | `ba446a4f16f54901f6cc23b976befea50c20457f920d265e82793eb80d678cd4` |
| 웹 `scripts/verify-precheck-native.mjs` | `f0743d90d7447e82edcaeb63de09bb50ed6d9981e7c03279e4481356a8d40322` |

## 독립 TDD·결함 주입과 전체 gate

서버 새 7개는 실제 PostgreSQL과 MockMvc 공개 HTTP 계약을 검증한다. 최초 assertion RED 1/통제 6 PASS 뒤 동일 테스트로 7 GREEN이다. 취소/reload·새 CREATE/CLEAR·멱등 replay·질문 이력/3회 상한·타인 비공개·stale CAS·마감 fence를 포함한다. 표시 guard 제거는 assertion 1개, 저장된 질문 삭제는 assertion 5개가 탐지했다. 각 정확 복원 뒤 7 PASS이며 원본 479 src 지문과 고정 테스트가 유지됐다. 해당 XML과 exact patch/복원 지문을 대조했다. MockMvc 결과 자체를 실제 네트워크 관측으로 계수하지 않았다.

웹 새 7개는 최초 유효 assertion RED 4/통제 3 PASS, 최소 수정 후 7 GREEN, 기존 67개 포함 독립 74 GREEN이다. guard 제거 FI는 assertion 4개 탐지 후 정확 복원 7 PASS다. 비활성 retained question, 정상 질문의 전체 제안 명시 확인, 직접 수정 전체 재검사, 실패→명시 retry→미완료 대기→accepted CLEAR를 검증한다. 독립 원본 RED/FI와 저장소에 복사한 요약 SHA가 각각 `953b8d8fcde287ddae9ca25e51ac0f29fbe8f5441afa1cf2f00a1652a3698da2` / `21f9b0265ae75ae4270ff613303b6cd974981bc47ded4a3634aaaff717ea367b`로 같다. 초기 fixture/typecheck 준비 오류는 RED가 아닌 별도 이력으로 보존됐다.

서버 최종 unchanged 필수 guard는 08:12:44.779376–08:20:55.261164 UTC에 hook self-tests/ktlintCheck/assemble/test **exit 0**이다. 실제 XML 214 suites/1717 tests 중 **1713 PASS·기존 4 SKIP·failure/error 0**을 직접 집계했고 모든 XML mtime이 실행 시작 뒤다. source/config 558개의 before=after=current를 전부 재계산해 불일치 0을 확인했다. 모델 키는 빈 값이며 실제 모델/OAuth 호출 0이다. 과거 557개/1710개 gate를 이번 fresh 결과로 재사용하지 않았다.

- [서버 최종 gate](../../.tdd/verification/issue128-final-server-gate.json), SHA `167776a1c0516bcb087811f86f8bd426e37d4f6b07ed50ff086b79faab20ff0c`
- [서버 558파일 manifest](../../.tdd/verification/issue128-final-server-source-manifest.json), SHA `36d77ad91e1526c314f6c86fc1122dcceaf53e607b031e8be0b49ae1d72cfc24`
- [서버 독립 FI](../../.tdd/verification/issue128-cancel-question-independent-faults.json), 원본 `.codex/tdd-evidence/issue128-independent-fi/`, exact history `.tdd/history/issue128-independent-fi/`

웹 전체 gate는 API 정합/lint/typecheck/build PASS, unit 58파일 **627/627 PASS**, browser **425 PASS·기존 1 SKIP·failure/flaky/error/retry 0**이다. browser 원본 stats와 unit 원본을 대조했고 source/test/e2e/script/schema/lock/config 134개를 현재 지문과 전부 비교해 불일치 0을 확인했다. 전체 browser는 synthetic same-origin route fixtures이고 실제 backend gate는 아니다. 기존 집중 157/22, 새 7/74와 전체 숫자를 합산하지 않는다. 웹 근거는 `docs/verification/issue-32-web-final-gate.json`, `issue-32-web-source-manifest.json`(SHA `67aad468a66395bfb41887c282fec35da45ac715f9dd04c865da4b6193c018da`), `issue-32-independent-red-manifest.json`, `issue-32-independent-author-fi.json`이다.

## 현재 후보의 실제 native와 한계

현재 수정 바이트의 native **14 PASS**, 실제 API 관측 **154회**, API route mock 0/외부 요청 0/실제 모델·OAuth 공급자 호출 0이다. 기존 12개 oracle를 보존하고 취소→question:null/원문 보존→새 POST202, 실패→명시 retry202→pending→accepted CLEAR 두 경로를 추가했다. 저장/대기/실패를 제출 완료로 표시하지 않는 assertion을 포함한다. 기존 proof/소유권·invalid Bearer 우선·계정 변경/늦은 응답·CAS/replay·수동/시간 마감·마지막 accepted freeze도 검증한다.

source 482·frozen runtime 1559·dependency 216개를 올바른 runtime-final 기준 경로와 현재 파일로 직접 대조해 불일치 0을 확인했다. 웹 native 자산 지문과 실제 OpenAPI 36 paths/61 schemas의 웹 snapshot JSON 의미 동일성도 확인했다. fixture 포트 18920/21/22와 컨테이너 종료는 cleanup 근거에 기록됐다. 첫 postrun verifier의 잘못된 상대 경로 해석과 초기 선택 output 디렉터리 준비 오류는 원본 보존 후 교정됐으며 제품 실패나 추가 RED로 취급하지 않았다.

이 native는 **top-level Primary synthetic PreSubmissionCheckProvider가 실제 TwoStage/classifier/provider transport와 영속 금융 authority를 우회**한다. 실제 browser/HTTP/PG/Redis/local RSA·loopback OIDC/guest-proof 상태 계약의 증거다. 실모델의 질문 판정·품질/정확도·오탐/미탐·calibration·p50/p95·운영 전 경로 성공을 입증하지 않는다.

- [안전 native 요약](../../.tdd/verification/issue128-native-ui.json), SHA `27d2ef11ec61ee7c0c4091cbcdfed4734dc594a7ceae6dde666030769e5d495d`
- 웹 `docs/verification/issue-32-native-browser.json`, SHA `75eb68b34dd39b9401df7486c94724ef75ff783681f42fc93f972f3af6afc9d7`
- runtime manifest SHA `0c242dece87d8693f7ab47ba12d183fd763ba6fee979fb6563f02683895f4901`; 원본 준비/실행·cleanup은 해당 native 요약의 지정 경로에 보존한다.

## 비공개 사전 정답과 범위

기존 개발 60/holdout 60의 원문·A gold·core gate는 그대로다. 별도 8쌍/16문장과 단계별 overlay를 사전 독립 검토했고 초기 DRAFT P2 4그룹은 보정/재리뷰로 닫혔다. 최종 3개 payload는 독립 검토한 바이트에서 status 필드만 봉인 변경했음을 재확인했다. manifest의 18개 연결 파일 지문도 모두 현재와 같다. 개별 원문/ID/gold/수정 세부사항은 root·구현 담당에게 전달하지 않았다.

명확한 문맥·의도된 넓은 범위/유효한 종속조건 보호, 실제 최소 대조, branch에 따른 의미 보존, 근거 없는 사실 생성 금지, 안전한 NEEDS_EDIT와 질문 성공/제출 완료의 구분을 검토했다. raw A 오분류와 정규화 라우팅·B 복구, 불필요 질문/직접 수정, 오류/refusal/미실행 및 분모를 분리한다. author/reviewer 노출 이력은 공개되며 모두에게 미노출인 split이라는 주장은 없다. PROTOCOL_ONLY는 채점 규약 sanity이며 모델 품질 결과가 아니다. 구조/DRAFT·변조 거절 PASS는 평가 준비의 증거이고 실모델 평가 0이다.

공개 [manifest](../evaluation/issue123-classification-evaluation/manifest.json) SHA `1d15a4e9323bbf7f8b3c180a4fb4205c2c116c149ef269dab01a328b9ce2bed7`, [독립 요약](../evaluation/issue123-classification-evaluation/review-summary.json)에 counts/hash/노출/봉인 경계를 연결했다. 개별 비공개 판단은 이 보고서에도 복제하지 않는다.

## 게시 범위와 다음 단계

검토한 서버/웹 PR 초안은 저장소의 6개 절(변경 목적/변경 내용/검증/리뷰 요청/영향 범위와 주의사항/관련 이슈)을 충족한다. 서버 `Closes #128`·Refs #123/#107, 웹 `Closes #32`·서버 #128/#123 참조가 좁은 수정 범위와 맞는다. 기존 미병합 PR126/PR31을 상속해 develop/main 대비 전체 diff가 더 크다는 설명, 기준 HEAD와 이후 게시 HEAD 구분, native 우회/사전 평가 자산 한계가 명시됐다.

검토한 PR body SHA는 서버 `c7536e19dd9ed40fea2baa9e2c46e9af189fd6f7f2e03cbd8a19bd6698ace09a`, 웹 `c21f905841eab8a05a21b21e43a59c06d6fdf78076253aac1c0338157af63bca`다. 본 리뷰 링크와 실제 게시 URL/SHA/CI 결과를 추가하는 후속 상태 기록은 가능하다. 제품/테스트/하네스/평가 payload의 의미를 바꾸면 해당 범위는 다시 검증해야 한다.

[현재 검증 보고](../ISSUE_123_UI_AND_EVALUATION_VERIFICATION.md), implementation_plan과 Worklog는 코드 완료/검증 완료와 commit·새 Draft·정확 HEAD CI 대기를 구분한다. 과거 raw CLEAR 불일치·503·timeout·parser 실패·유료 기록은 재라벨하지 않았다. 추가 유료 호출/UNKNOWN 환급 0, 기존 합산 USD2의 예약 USD1.33185536/여유 USD0.66814464 유지이며 운영 threshold·가격/활성화·전체 모델 품질은 여전히 미완료다. README/이미지 #127, 병합·배포·운영/Secret 변경·Linear 재시도는 이번 범위에 없다.

남은 단계는 부모가 이 바이트를 commit/push하고 **기존 PR을 보존한 새 Draft 두 개**를 게시한 뒤 각 정확 HEAD의 CI를 확인·기록하는 것이다. 현재 로컬 PASS를 아직 없는 원격 CI 성공으로 표현하지 않는다.
