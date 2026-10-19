# history 정리 게시 준비 (2026-10-19 UTC)

- [x] [USER] history639개 삭제와 기존 문서·규칙7개 수정, 총646개만 별도 커밋으로 같은 Draft PR132 브랜치에 정상 게시하고 정확 새 HEAD CI 종료까지 확인하도록 승인했다. 비용/계획/초안12개와 기존 관련 상세 기록은 게시하지 않고 작업 트리에 보존한다.
- [x] [AGENT] HEAD/원격7ab660ad·PR132 Open/Draft·미병합을 재조회하고 총646개 범위를 확인했다. 활성/JSON/pending 보존과 기존 독립 리뷰를 바탕으로 게시할 index/tree를 별도 검증한다.
- [ ] [AGENT] 필수 guard·게시 tree 최종 리뷰 후 정상 commit/push, 원격 diff/tree의 history0·제외자료 부재와 정확 새 HEAD CI를 확인한다. Commit/PR: 동일 커밋 예정 / 기존 Draft132.

유료 호출·비용 재계상·병합·배포는 제외한다. 다음 시작점은 필수 guard와 게시 tree 리뷰이며 아래는 이전 시점 기록이다.

# history 전체 사본 정리 (2026-10-19 UTC, 미게시)

- [USER] 남은553개도 조사 후 함께 정리하도록 재확인했다. 현재 실행 입력이 확인되면 그 파일만 보존하고 과거 JSON/활성 도구/미게시 비용 원본을 유지한다. 추가 commit/push·유료 호출·재계상·병합·배포는 승인 범위가 아니다.
- [AGENT] `7ab660ad7c1ca3553a63013df0d61ef1c07c668b`에서 history639개(4265087 bytes)를 모두 임시 복구하여 Git blob과 byte 대조했고 남은553개도 일치했다. CI/빌드/현재 도구의 실제 입력·glob을 대조한 정적 실행 의존성 확인0이다. 이는 모든 CI를 새 실행한 성적과 구분한다.
- [AGENT] 남은553개는 현재 실행 입력0/동일 소스 여분22/소스 지문 목록5/기록끼리의 참조393/앞 범주 제외 명시적 미참조133으로 한 번씩 분류했다. 참조만으로 보존 필요성을 단정한 이전 설명을 정정한다. 참조 수는 파일·폴더·상대경로 기준 정적 조사다.
- [AGENT] 남은553개를 제거하여 현재 history사본639→0, stub/폴더 이동0이다. [기존 TDD 안내](../.tdd/README.md#과거-fixture실행-사본의-git-복구)에 고정 Git 원본·역사경로 해석·복구법을 최소 보존하고 현행 Markdown 링크10개/4파일을 Git 고정 주소로 전환했다. 과거 JSON의 path/SHA/sourceRevision/판정/실패 이력은 다시 쓰지 않는다.
- [x] [AGENT] history 없는 상태에서 관련 검사135(hook12/runtime pin28/managed Node68/catalog Node26·Python1) PASS, Gradle ktlintCheck/assemble/test exit0·17tasks UP-TO-DATE를 확인했다. 새 전체 테스트 실행으로 표시하지 않는다. 링크310/파일·anchor 오류0·고정Git 링크11·원본639복구·보존 JSON493/runtime654/pending12 byte동일·history path/SHA70쌍·기존문서/기록보존·index clean·diff검사 PASS다.
- [x] [AGENT] 최종 독립 실제diff 리뷰 PASS/열린P1·P2=0이다. 검토 bound는 HEAD7ab660ad / diff SHA256 `9a3ca94475b8faaafee8ad5562517712826f54e072a87befc32b18bb6137decb`이며 이후 변경은 이 결과 상태 기록뿐이다. 리뷰어도 원본639 복구·JSON493 byte동일·서로 다른 직접 path/SHA61쌍·고정Git11·pending/기존기록 보존을 독립 확인했다. 총646변경=history639삭제+기존MD7수정이며 미커밋/미게시로 보고한다. 기존 원격7ab CI SUCCESS는 이번 추가 로컬 정리의 CI로 표시하지 않는다.

추가분은 미커밋/미게시다. 다음 시작점은 부모의 전체646개 작업 사본 결과·게시 범위 검토다. 별도 확인 전 commit/push하지 않으며 아래는 이전 시점 기록이다.

# docs 밖 검증 사본 정리 (2026-10-19 UTC, 미게시)

- [USER] docs 밖 이슈별 script/JSON/폴더도 조사하고 안전한 작업 사본 정리까지 진행하도록 범위를 확대했다. 추가 정리분은 별도 확인 전 commit/push하지 않는다. 기존 미게시 비용 자료12개와 Worklog/계획의 이전 상세 기록을 보존한다.
- [AGENT] 원격 문서 커밋 `7ab660ad7c1ca3553a63013df0d61ef1c07c668b`는 재생성·재게시하지 않았다. 부모 대비 Git `D11/M18`과 GitHub `removed11/modified18`가 일치하고 삭제 경로의 stub/rename은 없다. 정확 [CI39179046945](https://github.com/meet-me-duo/meet-me-server/actions/runs/39179046945)는 Quality Gate·ARM64 모두 최종 SUCCESS, Lifecycle39179046944도 SUCCESS다. Quality literal checkout45f17cd943910994e541df4b2c0e1aa2a7ecd93e의 부모는 develop3982535+HEAD7ab이며 HEAD와 tree577ead506968c774c486da840187a283cf3a14e2가 동일하다. ARM64는 정확7ab 직접 checkout을 확인했다. 이 결과는 원격 문서29개 커밋의 CI이며 추가 로컬90개 CI로 표시하지 않는다.
- [AGENT] 추적1931개 중 `.tdd`1048/history639·RED115·verification202, `evaluation/issue130`70을 분류했다. 중복57그룹126사본/여분69는 삭제 가능 수량이 아니다. 현재 JSON47개가 history145파일을 참조하므로 참조된 자료·유효한 증거와 CI 도구는 보존한다.
- [AGENT] 현재 코드·CI·테스트·남는 증거와 미게시 자료에서 incoming 참조가 없는 과거 fixture/실행 사본14묶음86파일(721170 bytes)을 작업 트리에서 제거했다. 유효 RED/FI 요약·실행기70·제품/테스트/배포/CI·비용 원본은 불변이다. [고정 Git 원본·복구 목록](../.tdd/README.md#과거-fixture실행-사본의-git-복구)에 삭제 이유와 7ab660ad 원본을 연결했으며 과거 JSON의 경로·지문을 다시 쓰지 않는다.
- [AGENT] 전체 사본 대신 원본 커밋/경로/지문을 쓰고 기존 실행기·로컬 산출물 경계를 재사용하도록 개발 규칙을 최소 보완했다. 포괄 ignore 추가0·새 폴더/실행기/보고서0이다. wrapper 통합은 고정 ID·마스킹·실행 상한을 바꿀 수 있어 수행하지 않는다.
- [x] [AGENT] 삭제 원본86개를 Git archive로 임시 디렉터리에 복구하고 HEAD 원본 blob과 byte 대조했다. 남은 현재/미게시 자료의 incoming 참조0, 보호 파일1107 불변·pending12 byte동일·기존11문서/이전기록 보존·index clean·diff검사 PASS다. 로컬 링크318개/파일·anchor 오류0, hook selftest12 및 managed runtime pin28 PASS다. 새 full Gradle/실모델/실제 browser 실행으로 표시하지 않는다.
- [x] [AGENT] 독립 최종 실제 diff 리뷰 PASS/열린P1·P2=0이다. 검토 bound는 HEAD7ab660ad / diff SHA256 `891d6656675b502d6f0680d4781a5003e9b9dc77589f4bbec28b260a88c58864`이며 리뷰어도 원본86 복구·pending12/기존18·21행 보존·활성src/CI/RED/FI 불변을 별도 확인했다. 이 줄은 검토 후 상태 기록이며 새 실행 증거로 표시하지 않는다. 동일 바이트 여분 사본은69→41, 작업 트리에 남은 history파일639→553이다.
- [x] [AGENT] 기존 원격7ab CI 최종상태·literal checkout·원격HEAD일치를 확인했다. 추가90개는 검증/독립 리뷰를 마쳤으며 미커밋/미게시로 결과를 보고한다. 추가 commit/push는 별도 확인 전 수행하지 않는다.

유료 호출·비용 재계상·예약 변경·병합·배포0. 다음 시작점은 부모의 추가90개 작업 사본 결과 검토다. 추가 게시 승인은 아직 없으며 아래는 이전 시점 기록이다.

## 2026-10-18 UTC — 일회성 문서 정리

후속 게시 승인: 사용자는 정리29개만 별도 커밋으로 현재 PR132 branch에 게시하고 정확 HEAD CI까지 확인하도록 승인했다. 승인 대기12개 및 그 상세 작업 기록은 index/게시 tree에서 제외하고 작업 트리에 보존한다. 필수 guard·게시 범위/링크 검증 후 정상 push하며, 이전 거절 뒤 이번 승인으로 한 번만 다시 시도한다. 다시 자동 승인 검토에 거절되면 중단·보고한다. 병합/배포/유료/비용 재계상은 승인하지 않았다. Commit/PR: 동일 후속 커밋 예정 / 기존 Draft132. 아래의 미커밋/미게시·원격 ebcf218 설명은 첫 정리 완료 시점이다. 최종 게시 SHA/정확 CI는 Git 이력과 PR 본문에서 확인한다.

남은 루트46개에도 일회성 성격은 있다. ISSUE_92_SCENARIO_RESULTS, ISSUE_95_REVIEW, ISSUE_99_LIVE_EVALUATION은 이전 작업의 독립 검증 이력이며 이번 승인29개 밖이므로 추가 통합하지 않는다. ISSUE_130_MANAGED_BROWSER_VERIFICATION은 최신 결합 검증 요약이며 승인 대기 README 초안이 참조하므로 보존한다. 다른 설계/계약·운영 안내·혼합 인계 문서는 현재 사용되는 경계와 절차를 포함해 유지하고, 비용 감사/최소 계획2개는 미게시 승인 대기 자료다. 추적되는 게시 tree의 루트는44개이며 로컬 대기2개를 포함한46개와 구분한다.

원인: 저장소 지침은 별도 일회성 Markdown 보고서를 요구하지 않았다. `.agents/rules/development.md`의 진행 기록/PR 검증 설명과 `.tdd/red` 요약·로컬 로그 분리 요구를 단계별 별도 문서로 넓혀 적용한 문서화 선택이었다. `b377043`(실제 진단·단계별 리뷰), `4101038`(생성 진단), `18cdb5d`(단일 사례 검증), `62f47bb`(대표 평가), `ebcf218`(HTTPS 검증)의 생성 이력이 이를 보여 준다. `.agents/rules/documentation.md`에는 기존에 지속 문서와 실행 기록의 위치 구분이 없었다. 지침이 생성을 강제했다는 의미는 아니며, 최근 감사 초안도 담당자가 별도 파일을 선택한 결과다.

정리: 완료된 실행·리뷰 보고11개(716줄)를 이 Worklog의 [원문 통합 구간](#통합한-과거-실행리뷰-원문)에 전체 보존하고 개별 파일을 제거했다. 각 구간의 명시 anchor로 기존 링크를 연결하고 원본 경로·HEAD·`git show` 복구 명령을 남겼다. 현재 설계/API/운영 가이드, 사용자 작성 기준 문서, 기존 검증 JSON/fixture/원시 로그 위치는 보존한다. 기존 검증 자료의 과거 파일 경로·SHA 표는 당시 snapshot이므로 현재 지문으로 덮어쓰지 않는다.

- 통합: #107 LOCAL_SCENARIO/MAIN_READINESS/REAL_MODEL_EVALUATION_PLAN/USD1_EVALUATION_EXECUTION_PLAN, #109 REVIEW, #123 FRESH_HEAD_TWO_STAGE_VALIDATION/LIVE_DECISIONS_DIAGNOSTIC/LIVE_TWO_STAGE_VALIDATION/UI_AND_EVALUATION_VERIFICATION/USD2_GENERATION_DIAGNOSTIC, #130 REPRESENTATIVE_DECISIONS_EVALUATION.
- 보존: 승인 대기 비용 감사/최소 실모델 계획/README 초안 및 관련12개 미커밋 자료의 원본 bytes를 유지한다. 현재 Worklog와 implementation_plan의 기존 내용도 남기고 이번 정리 상태만 추가한다.
- 최소 지침 보완: documentation의 지속 문서/기존 Worklog/기존 검증 경로 구분, development의 기존 Worklog 사용·별도 Markdown 불필요 문구다. AGENTS·보안·CI·숨겨진 시스템 지침·개인 내부 기록은 변경하지 않는다.
- 기존 깨진 inventory 링크1개는 파일/해당 Git 이력에서 자료를 찾지 못한 사실을 명시하고 링크를 제거했다. 대체 증거를 만들거나 당시 검증 성공으로 기록하지 않는다.

현재 docs 루트 Markdown57→46, 전체128→117이다. 문서/링크 정리이며 제품 코드·테스트·실행기·원시 검증 결과 변경0, 유료 호출·비용 재계상·예약 해제0이다. 기존USD3의 완료분 재계상과 새3개 유료 검증은 **승인 대기**다. main·배포·활성화HOLD를 유지한다. 이번 정리는 로컬 미커밋이며 원격 PR132 HEAD ebcf218은 그대로다. 아래와 마지막 통합 구간은 각 당시 기록이다.

최종 검증: 로컬 Markdown 파일/anchor 링크316개·통합 구간 연결28개 모두 유효, 깨진 파일/fragment0·history anchor78개 중복0이다. 원문11개는 Git 원본과 링크 주소를 제외한 전체 내용이 일치하고, 기존 Worklog/구현 계획 본문 및 pending12개 bytes도 보존됐다. 제품/테스트/실행기·배포/CI·원시 검증 자료 불변과 `git diff --check` PASS다. 검증 원본은 로컬 `/tmp/meetme-docs-cleanup/verification.json`이며 새 정리 보고서 파일을 저장소에 추가하지 않았다. 원격 PR132 Open/Draft·미병합/HEAD `ebcf218cd27619e3129e9484c7066899693b378b`를 읽기 전용 재조회했다. commit/push/새PR·새CI는0이다.

실제 변경 분류: 통합 대상11개와 Worklog/implementation_plan, 지침2개 외에 링크만 수정한13개는 ADR, ISSUE_107_DECISION_CLASSIFICATION_DESIGN/DECISION_EVALUATION/DECISION_EXTERNAL_EVALUATION/EXTERNAL_READINESS_RUNBOOK/SHARED_AI_BUDGET_DESIGN, ISSUE_123_DURABLE_AI_BUDGET_CONTRACT/THOUGHT_SIGNATURE_COMPATIBILITY, verification의 issue-107-provider-official-readiness/issue107-decision-precheck-independent-review/issue128-ui-evaluation-publication-final-review/issue123-phase2-publication-review/issue123-live-two-stage-result-review Markdown이다. ISSUE_107_PRE_SUBMIT_DESIGN은 기존 inventory 누락 설명1문장만 고쳤다. 총29개 추적 파일의 문서 정리이며 새 독립 보고서0이다.

## 2026-10-18 UTC — 실제 관리형 HTTPS 3suite 결과

실제 새 managed8 8/8·원HOST8 8/8·원복구6 6/6을 각각 단회 완료했다. retry/flaky/skip0, 각source263/runtime1143/web304/tools26/assets 전후동일·cleanup3종0이다. builder 전체소스494와 실제snapshot263을 구분한다. 새8의 원장22reserve/claim/observe는 A18+count2+generation2이며 TLS 물리22와operation/bodySHA/epoch/owner 결속 불일치0이다. remote0은 설치된 강제 loopback guard+localTLS 구성 근거이며 별도 packet meter가 아니다. 실제 외부모델/OAuth0·TEST_SUBUNIT 합성 정책일 뿐 의미효능/실금액/운영인증 증명이 아니다.

첫 stdout-fd null trim setup오류와 한줄수정/기존68PASS를 보존했다. 과거 rapid-unload401/autoFocus는 삭제하거나 원인 해결로 단정하지 않는다. [검증 요약](ISSUE_130_MANAGED_BROWSER_VERIFICATION.md)과 [canonical](../.tdd/verification/issue130-managed-browser-actual-suites.json)(bfa86a6e…)을 기준으로 실제3scope 독립readback PASS(41febf29…)와 필수guard exit0/9.486초/source563 전후동일을 확인했다. XML1733은1729PASS+기존4SKIP의 재사용 결과다. 최종 독립 게시 리뷰 후 같은 Draft 정상게시/정확HEADCI를 확인한다. Commit/PR: 동일 후속 커밋 예정.

## 2026-10-18 UTC — 실제 HTTPS 첫 시도·최소 재실행

부모는 범용 도구 확장 없이 실제 시나리오의 구체 성공/실패와 재현 원인 확인에 집중하도록 명확히 했다. 실행 전 한정 리뷰8fde52d8…와 동일 Docker 대상 P2 CLOSED 후 new-managed8을 시작했다. 첫 시도는 정상 tsc stdout을 파일 fd로 보낸 spawnSync 결과가 null인데 trim을 호출한 runner TypeError로 서버/브라우저 전 setup 실패했다(RED0). dist 미생성 ENOENT는 2차 정리 검증 오류로 분리했고 소유 containers/listeners/private 잔여는0이다. 원 실패/소스를 보존하고 반환 null 처리 한 줄만 고친 뒤 기존68PASS·새 고유 workdir/session44236 재실행 중이다. 제품 코드/원 oracle 변경0이며 actual 결과를 준비 검증으로 대체하지 않는다. Commit/PR: 동일 후속 커밋 예정.

## 2026-10-18 UTC — runtime 봉인·재현 빌드 점검

검토된 runtime manifest9d3ea4…의 source263/runtime1143/CP219/fixture11을 root가 verify-only로 직접 확인했다. backend 추가 경계4는 원34와 함께38GREEN·독립FI2/재검토로 두 P2가 CLOSED다. 별도 Docker 대상 일치 P2는 새14 독립RED 후 수정 중이며 실제 앱·브라우저는 아직 기동하지 않았다.

후발 재현 builder의 첫 실제 offline build는 기존 한글 테스트 class 경로 MD5 단계의 setup 실패(RED0)다. 자식 JDK 대조에서 기존 whitelist는 file/native/sun.jnu encoding 모두 ANSI_X3.4-1968, 명시 C.UTF-8은 모두 UTF-8임을 확인했다. source/제품을 수정하지 않고 builder 자식 환경만 고정해 새 출력 경로에서 재빌드 중이다. 기존 검토 runtime9d3은 독립 복사라 변경0이며, 이 사전점검을 실제 Spring/PG/browser 성공으로 표시하지 않는다. Commit/PR: 동일 후속 커밋 예정.

## 2026-10-18 UTC — managed attestation 독립 검증

새 orchestrator 계약은 독립 genuine RED28→GREEN28이다. 별도 FI3는 after-source/runtime 검증 누락2assert·Primaryfake 허용1·B generation 원장 필수 누락1을 탐지했고 각 복구28PASS다. [결함 주입 기록](../.tdd/verification/issue130-managed-browser-contract-faults.json)은 순수 attestation 일관성 검증이며 실제 관리형 서버/브라우저 실행 증명이 아니다. 새 browser4×2는 독립 타입 검사·list8까지 완료했고 실제 실행은 대기 중이다. 서버6046+V2 freshcompile exit0/source 전후동일 보고를 받았지만 runtime snapshot은 아직 launcher/최종manifest 없는 NOT_LAUNCHABLE 단계다. 추가 모델/OAuth 호출0. Commit/PR: 동일 후속 커밋 예정.

## 2026-10-18 UTC — managed browser 준비 증거

정확 web33 `3c6fb446e3a6e20aec5fd2f9e1eb78233c6f09e6`의 pnpm10.17.1 frozen install·tsc·Vite 격리 build PASS다. 원 source304/lockfile/HEAD 불변·gitclean을 확인했다. [준비 receipt](verification/issue130-managed-browser-web-preparation.json)는 의존성/빌드만 증명하며 실제 managed backend/browser 연결·실모델·OAuth 증거가 아니다. 기존 host/auth-recovery 기대값과 historical FAIL은 유지하고 새 managed 전용 시나리오는 별도 집계한다. 게시27de 정확 CI39149807904는 Quality/ARM64 최종SUCCESS이며 Lifecycle39149824268도SUCCESS다. 실제 literal checkout과 merge 부모/동일tree를 확인했고 [정확 CI receipt](verification/issue130-27de-exact-ci-success.json)에 분리했다. 새 managed browser 결과로 표시하지 않는다. Commit/PR: 동일 후속 커밋 예정.

# 새 #107 구현·통합 Worklog

## #130 무료 managed HTTPS 브라우저 통합 준비·실행 (2026-10-18 UTC)

- [x] [USER] test 전용 managed launcher·정확 runtime 핀·웹33 의존성 복구·기존 HTTPS 브라우저 연결을 승인했다. 실제 모델/OAuth 호출·운영 설정 변경은 제외하고 합성 결과와 실제 관리형 경로 검증을 구분한다.
- [x] [AGENT] 격리 실행 worktree를 만들었다. 서버 PR133 `6046baf66e49e9382248cd3fb8adb0a8879a7e40`에 최신132 `27de97e137d23a2920d425ea521ee33ec0996a8d`의 V2 A/B 두 파일만 overlay(A a723af8f…/B7e898fcd…)하고, 웹 PR33 `3c6fb446e3a6e20aec5fd2f9e1eb78233c6f09e6`를 별도 detached worktree로 고정했다. 기존 브랜치·웹 oracle 원본은 변경하지 않는다.
- [ ] [AGENT] 독립 공개 계약/RED 후 `evaluation/issue130/managed-browser/`에 별도 시험 launcher·불변 runtime manifest·orchestrator를 구현한다. 실제 managed bean/worker/PG 원장/production TwoStage·클라이언트에 loopback 합성 TLS transport·합성 정책만 연결하고 실 provider/OAuth outbound를 차단한다. 원 OFF/Primary fake 실행기를 새 managed 증거로 재사용하지 않는다.
- [ ] [AGENT] 격리 웹 의존성을 frozen lockfile로 복구하고, 기존 HTTPS 권한·CORS·멱등성·복구·마감 oracle와 고유 실패를 보존해 실제 연결을 검증한다. 새 fake 결과를 모델 의미 정확도나 실제 OAuth/운영 검증으로 표시하지 않는다. 실제 실행 전후 source/runtime/원 oracle 지문·요청 경로·원장/worker 관측을 봉인한다.
- [ ] [AGENT] 최소 무료 회귀·독립 결함 주입/리뷰·필수 guard 이후 검증 가능한 코드·기록만 정상 게시하고 정확 최종 HEAD CI를 확인한다. 현재27de CI39149807904 결과도 계속 확인한다.

제품 API/UI·DB schema·모델/threshold·500자·참여/마감 정책·비밀정보/운영 설정은 바꾸지 않는다. 합성 identity/JWT fixture는 시험에서만 사용한다. 유지 예약USD2.74751488·추가 유료0·기존 UNKNOWN 유지, V2 실제 의미 효능/운영 정책 미검증으로 main·배포·활성화HOLD다. 다음 시작점은 launcher/runtime 공개 명세·독립RED/웹 의존성 복구다. Commit/PR 동일 후속 커밋 예정. 아래는 각 시점의 기록이다.


## #130 A24 Python fixture 후속 (2026-10-18 UTC)

- [x] [AGENT] V2 정합화/무료 검증·최종 독립 리뷰 뒤 정상 HEAD `e5cd063364d90c7b99234c68abfe3d8c5751fbc2`를 동일 Draft132에 게시했다. exact CI39146763104는 Gradle/bounded/PC01+PJ16/CA+JDK/ARM/Lifecycle PASS 뒤 A24 Java26PASS·Python exit1로 실패했다. 원 runner가 Python 실패ID를 출력하지 않아 원격ID/host filesystem 원인은 미확정이다.
- [x] [AGENT] exact e5cd 현 runtime·원Python15만 합성 재현했다. valid parent15PASS, missing/symlink parent에서 각각 PY12/PY14/PY15 assertion3 실패·대조12PASS/오류0다. wrapper의 mocked child가 실제 상속 JDK 파일 조건에 의존하는 fixture 결합을 확인했고 원격 원인 확정과 구분한다. 원 source/15assert·420초/24회/gold·점수는 유지한다.
- [x] [AGENT] A 독립RED11(10assert실패/대조1) 뒤 선택JDK pin·고정15ID/status/type 관측만 추가했다. 별도 privacy1 INITIALPASS를분리하고 최종12PASS/FI3 탐지2·1·4/각복구를확인했다. B는원25의선택경로대조outer2(각25PASS)·FI2 탐지4·1/각복구뒤두fixture표현만canonical화했다. 제품JDK검증·기존assert·420초/24회/예약/kill·V2지침은불변이다.
- [x] [AGENT] 기존A41/ B Java118/비용56/workflow4 PASS다. B 원Python25는완료증거재사용으로118+25=143unique를분리기록하며143fresh단일재실행으로표시하지않는다. source602 before=after 필수가드exit0/9.812초이며기존1733XML(1729PASS+기존4SKIP)재사용이다. 새fresh1733주장0·src/main/src/test는e5cd와동일하다.
- [x] [AGENT] 최종 독립 게시 리뷰 PASS를 봉인했다. 같은 Draft의 정상 후속 commit/push와 정확 새 HEAD CI 확인은 아직 대기 중이다.

추가 유료0·유지 예약USD2.74751488·기존UNKNOWN 유지. V2 의미효능미검증/main·배포·활성화HOLD다. 다음 시작점은 최종 독립 게시 리뷰→정상 후속 게시/정확 HEAD CI이며, 모델/API/UI/DB/threshold/500/참여·마감 정책 변경은 없다. 아래는 각 시점의 기록이다. Commit/PR 동일 후속 커밋 예정.


## #130 공통 정책 V2 정합화·무료 검증 (2026-10-18 UTC)

- [x] [USER] A CLEAR 설명·A/B 지침·평가 정의 정합화와 무료 회귀를 승인했다. API/UI·DB·모델·threshold·유료 호출 변경은 제외한다.
- [x] [AGENT] A 선택지 설명의 광범위한 person-dependent 허용과 A/B 지침·기존 평가 정의 사이의 긴장을 확인했다. 미제공 필수 개인 사실과 명시적 넓은 허용·타인 가용성 의존·날짜/방식 선택권 위임을 구분하는 [V2 정의](ISSUE_130_SHARED_POLICY_V2_EVALUATION.md)를 분리했다. 기존 A16/A24/B2 입력·gold·최초 점수와 V1 resource는 불변이다.
- [x] [AGENT] 독립 공개 DEV12와 wire 기대를 먼저 봉인하고 genuine RED16(11 assertion실패/5 control통과)을 확인했다. A CLEAR 설명과 A/B 지침 두 파일만 최소 적용해 focused16 GREEN이다. 독립 인간 정책 검토 PASS이며 실제 모델 효능 검증은 아니다.
- [x] [AGENT] PC01 fixture는 선택 JVM의 실제 JDK만 자식에 고정하고, 실패 관측은 고정 enum/상한 숫자/boolean만 허용했다. 독립 RED16(9실패/7controls)→GREEN16, FI3 탐지2/1/1·각복구, 기존 proxy49+Python14 PASS를 보존한다. 원43 assertion·30초/16POST/replay0·제품JDK검증은 유지한다. exact2bf CI실패의 원격 원인은 미확정이다.
- [x] [AGENT] V2 관련 회귀245(집중16 포함)·독립 FI2(A회귀3assert/B필수정보문장누락1assert, 매복구16PASS)·6046 관리형 bootstrap8/기존 close3를 완료했다. 새11개 조합 결과를 V1의69+3 역사와 합산하지 않는다.
- [x] [AGENT] 최종 source597 동결 필수 guard PASS(516.447초): ktlintCheck/assemble/test exit0·216 suites/1733 fresh 중1729PASS·기존4SKIP·실패오류0·전후지문동일이다.
- [x] [AGENT] 최종 독립 게시 리뷰 PASS를 봉인했다. 같은 Draft132의 정상 후속 게시·정확 HEAD CI는 다음 단계이며 아직 대기 중이다.
- [ ] [AGENT] 동일 Draft132 정상 후속 commit/push 후 정확 새 HEAD CI를 확인한다. Commit/PR 동일 후속 커밋 예정.

현재 유지 예약 USD2.74751488/기존 UNKNOWN 유지·이번 추가 유료0. 실제 V1 품질 FAIL/효용 미입증과 V2 실모델 미검증으로 main/운영 활성화/배포 HOLD다. 다음 시작점은 최종 독립 게시 리뷰→같은 Draft 정상 후속 게시·정확 HEAD CI다. 아래는 각 시점의 역사다.


## 공통 정책V2 문구 정합화 승인 (2026-10-18 UTC)

부모를 통해사용자가 A설명·A/B지침·평가정의 정합화+무료회귀를승인했다. 필요개인사실/적용대상미확정과명시적유연성·타인가용성·선택권위임/앱표현한계를구분하며온라인날짜위임negative대조를추가한다. 주소등불필요정보요구0·API/UI/DB/model/threshold/500/caps/fences변경0·추가유료0이다. 원A16/A24/B2gold/score/V1wire는역사불변, 새정책V2DEV/wire검증으로분리한다. 현재PC01fixture독립RED후최소핀+관측GREEN/FI중이며그sourcefreeze종료후policy문구만적용한다. 이승인은운영활성화/실모델합격이아니다.


## #130 exact2bf PC01 실패와 공통 정책 검토안 (2026-10-18 UTC)

정상 후속 HEAD2bf62c7/동일Draft132의 exactCI39141877988은 Gradle·bounded102(이전CLI21–24 포함)·ARM/Lifecycle PASS 뒤 proxyPC01 assertion1로실패했다(Java48/49·Pythonexit0). checkout45c669의 부모398+2bf/tree동일·literal로그를확인하고 새실패를보존한다. 로컬원PC01 PASS4.813s/정상stdout211B, parentJAVA_HOMEmissing/symlink일때earlyCONFIGFAIL을재현했으나원격FS/실패branch미관측으로정확원인미확정이다. 독립fixture/privacy RED 이후선택currentJVM JDK명시·bounded stage/exit/크기/boolean 관측을최소검증하며 제품/30초/16POST/replay/assertions는완화하지않는다.

[공통정책검토안](verification/issue130-shared-policy-proposal.md)은독립specPASS의미적용문서다. 명시적유연성/필수개인사실/조건결속/앱표현한계를12행표로분리했고NEEDS_EDIT현재질문reason없음을명시했다. 기존gold/점수/prompt와추가modelcall변경0·현재예약2.74751488/UNKNOWN유지·main/deploy활성화HOLD. 부모에먼저표와코드변경범위제안을보고했으며이문서는모델수정승인이아니다. 다음은무료CI최소수정/검증·최종리뷰/후속게시·정확HEADCI다. Commit/PR 동일 후속 커밋 예정.


## #130 새 A24·B2 단회 실제 완료 (2026-10-18 UTC)

첫 비용 문법 구성 중단의 USD0.34603008만 사용자 승인으로 미실행 예약 정산했고 기존1.79322880/UNKNOWN은 유지했다. 정상 게시 HEAD9d9·source/runtime·원문/gold·새 계획/단계별 독립 수용을 봉인한 뒤 A24 Decisions24, 사전 B2 count2/generation2를 각 한 번 기술 완료했다. 추가 실제 호출/자동 재시도0·현재 예약2.74751488/승인3 안 여유.25248512이며 실제 청구 환급이나 invoice hard cap을 주장하지 않는다.

A 독립 raw miss0/10·불필요 REVIEW7/14·17/24, 정규화 전원 LOW_CONFIDENCE/B_REQUIRED24·CLEAR_STOP0으로 품질/운영 효용 NOT_ESTABLISHED다. B 두 최초 독립 의미 평가는2/2 일치·미해결차이0이며 실제CLEAR2로 필요한 선택형/직접 수정 안내를 각각 놓쳐0/2 PASS·2/2 FAIL이다. goldCLEAR B분모0·standalone2 범위를 유지하고 기존16 FAIL·원 구성STOP을 보존한다. 정답/threshold/prompt튜닝0, 실행·채점 당시 개별fresh원문/gold/output root공개0이다. 평가 뒤 부모의 새 명시 요청으로 합성 B2만 원문/출력/실패 이유를 전달했고 나머지는 비공개다.

정확9d9 CI39138572286은 Gradle/Java77/ARM PASS 뒤 Python CLI21–24 assertion FAIL이다. 최소 JDK fixture 격리는 원25assert불변·조건RED8assert→15GREEN/기존102+JDK16/FI3 13assert탐지·복원으로 완료했다. 새 필수guard source591전후동일/exit0·기존1719XML재사용(1715PASS+4SKIP)이며 fresh재실행주장0이다. 최신PR1336046+짧은prompt9d9의 격리managedgraph 기존69 freshPASS 및 별도마감0/1/2 신규3 PASS·사전oracle/사후독립감사 완료다. 기존398 Primaryfake와구별하며 newtest는현base398 livecompile이아닌재현archive로보존한다. 웹33 schema에는create500 contentless응답1누락이며 최종브라우저·실OAuth/최종모델/운영 검증은미완료다. broad완료승인은 품질/보안 gate 조건이며 현재 main/deploy/활성화는HOLD다. 후속 사후정책감사에서 A의person-dependent축약과B의필수개인사실경계미명시/공개평가정의 간 해석긴장을 확인했다. 고정gold0/2와모델단독원인은구분하고gold/원점수/지침무수정이다. 다음은 정책정합성addendum/최종독립게시검토 및 CI최소수정/필수guard→최종독립리뷰→동일Draft132 정상commit/push·정확HEADCI다. [최신집계](verification/issue130-context-actual-stage-results.json), Commit/PR 동일 후속 커밋 예정.


## #130 단회 실측 STOP·도구 검증 완료 (2026-10-18 UTC)

- [x] 독립102 합성 GREEN·FI7/8assert 및 실제 CLI→canonical TLS 합성16/replay추가0, 소스234+CI/root235 동결. 파일 원자0600·startup cutoff kill 별도 유효 RED와 원본 fixture 오류 보존
- [x] 비용review8fb3·실행전review9de9 ACCEPTED·planb381/compiledruntime 지문 고정 후 root 단회 실행. STARTED1/CONNECTION(outerCONFIGURATION)/관측network0·status0·응답0, UNRUN15. 채점분모0/정확도·FP/FN·p50/p95 미산출, 재시도0
- [x] 기존hold1.33185536+전체추가.23068672=USD1.56254208 유지·여유.43745792·환급0. interceptor0을청구0증명으로쓰지 않으며 unknown1을보존. gold/선택/STOP/.8 불변
- [x] 현재root130 전체필수guard exit0·fresh214suites/1717중1713PASS+기존4SKIP·source235전후동일/454.85s를확인했다.
- [ ] 최종 독립 게시review·기록commit/push/Draft/정확HEADCI. 미완료대표의미평가/#123와운영활성화는그대로 열림. 별도131 runtime C1은43GREEN/C2·배포전달 독립TDD중이다

상세는 [대표 평가 기록](#history-issue-130-representative-decisions-evaluation)이다. 실제 평가대상398+미게시helper해시와미래게시commit을구별한다. 추가 실제 호출은중단했으며 소스변경없는전송환경대조만진행한다. Commit/PR 동일후속커밋예정.

## 이메일 저장·인증 진단 사실 확인 (2026-10-18 UTC)

develop398의 login-only Google scope는 openid profile, Kakao는 openid,profile_nickname이다. account_users에는 표시 이름, account_identities에는 provider+subject가 저장되며 이메일/Gmail 주소·메일 내용 필드/수집 경로는 없다. sub는 plain string이며 필드 암호화가 없다. 저장소의 subject SHA256은 advisory transaction lock ID용이다. 현재 main91eb에는 이 OAuth 계정 구현이 없다. Terraform RDS storage_encrypted=true/EBS encrypted=true는 선언이며 실제 운영 적용 상태는 조회하지 않았다. DB 개인정보·토큰·Secret을 읽거나 변경하지 않았다.

현재 웹 HTTPS 통합에서 정상 refresh200 뒤 이전 쿠키 alias 재전송→401을 실제 재현했다. 프록시 Set-Cookie 출력은 upstream과 메모리 비교 일치, 정확한 브라우저 수신/unload 시점은 미관측이다. strict replay revoke는 유지하고 원8개 실패를 보존한다. 모델/예산 코드 검증과 별개 출시 차단이며 상세 공개 issue 게시가 자동 검토에서 거절되어 로컬 기록으로 보존했다. PC 자체가 필요한 차단은 확인되지 않았고 실제 본인 OAuth/MFA 검증이 준비되면 최소 행동만 안내한다.

## #130 대표 실평가·웹 HTTPS·조건부 main/배포 승인 (2026-10-18 UTC)

서버129 normal merge39825357와 정확 push CI39113901061 Quality/ARM64/literalSHA 및 122/126 간접merged·openPR0/원격branch정리·Lifecycle성공을 완료했다. 새 사용자 지시로 웹25–31의 대체 근거를 재검증하고 PR33 링크/이유와 함께 closed-unmerged 처리했으며24/33은 Draft다. 고유 HTTPS 원7파일은 byte그대로 archive, 현계정/202→CLEAR·TLS/CORS·0/1/2accepted 마감8개를 역할분리해 통합 검증한다. 과거8/native14와 새8을 합산하지 않는다.

사용자는 검증 뒤 main 정상PR·정확CI·실제배포·가입/로그인 확인까지 승인했다. security/Secrets/admin 변경과 미검증모델활성화 정책 결정은 제외한다. 공개 OAuth 설정완료 인계는 유지하나 실제 AUTH/RS256/JWKS 전달·실로그인·DB V1–V17 전환/복구 상태는 미확인이다. 현환경 AWS도구/프로필은 없고 실제운영조회0, 과거 main 배포성공/실패를 새 릴리스 검증으로 대신하지 않는다. OFF상태에서auth검증은 가능하지만 새 precheck accepted완료는 안되며 provider단일flag도 안전한2단계/authority활성화가 아니다. 구체차단을 보고하고 임의토글·가격seed·LEGACY우회하지 않는다.

별도 issue130/clean398 feature에서 봉인 supplemental8쌍16건 A-only평가를 준비한다. 원120불변/선택0/rootblind, manifestd5be33cd/source1d15a4e·정답/순서/STOP사전고정이다. 전체300초는wrapper시작·JVMstartup을 포함하고 A16/Bcount0/retry0/serial1/15초·wholewire16KiB·500자·fixedoutputsingle-use를 독립 RED/mock으로 확인한 뒤 실행한다. rawsemantic오분류는 수정없이16을 계속하고 refusal/HTTP/contract/usage불명/timeout/예산fence는STOP한다. .8은기존진단fixture일뿐 운영threshold는null/OFF이며 B_NOT_RUN은질문성공이아니다.

현재 actual추가0·UNKNOWN환급0·heldUSD1.33185536/총USD2 유지. 추가계획hold.23068672/합1.56254208/여유.43745792는공식invoicehardcap아닌보수proxy이며 실행직전동결·예약한다. 다음시작점은 HTTPS8와평가기계mock/사전리뷰→제한실측/독립성적·지연/비용감사→검증기록/정확CI→출시준비차단판단이다. README127은실증에맞춘Engineering구도만준비하며이미지는부모인계, Linear부모담당이다.

## 서버 develop 병합 승인·열린 PR 정리 (2026-10-18 UTC)

사용자가 Decision Model 포함 검증 통과 뒤 서버 develop 병합을 명시 승인했다. main·운영 활성화·배포·추가 유료 호출과 웹 main 병합은 승인 범위에 포함하지 않는다. 아래 병합 제외 문구는 이전 단계의 이력이다. PR129의67bad4d0가 PR12618cdb5d와 PR122e83d5280를 정확 ancestor로 포함하므로, 독립 준비 리뷰는 최종 필수 CI/충돌/리뷰 확인 뒤 PR129 단일 normal merge를 권고했다. 검증된 source558·workflow·OFF/DENY는 그대로이며, 전체 통합 범위를 취소 수정 한 조건만으로 축소해 설명하지 않는다.

별도 Draft 서버129/웹33을 게시했다. 웹f08aaa98의 exact CI39111519165는 전체 SUCCESS이며 실제 checkout14677b4b의 부모 main9b183978/HEADf08aaa98 및 tree 동일을 확인했다. 서버67bad4d0 CI39111507712는 ARM64 SUCCESS·Quality 진행 중이다. 이 새 승인과 현재 상태만 기록하는 후속 commit 전 unchanged 필수 guard를 확인하고, 후속 HEAD의 CI를 별도로 기다린다. 제품/테스트/fixture/정답 자산과 비용 예약은 변경하지 않는다.

다음 시작점은 최종 HEAD CI/Lifecycle→PR129 normal merge→실제 develop merge SHA·tree·push CI 확인→PR122/126의 간접 merged 여부 재조회와 열린 대체 PR만 근거 댓글 정리다. #123 미완료·모델 품질/운영 가격·threshold/활성화 보류를 유지한다. 실제 merge/정리 결과는 PR 대화·Git 이력·로컬 receipt에 이어 기록한다. 웹은 읽기 전용 포함 관계를 부모에게 보고하며, PR24의 별도 HTTPS 회귀7파일을 보존한다. README#127·Linear 부모 담당·유료 호출0/UNKNOWN 환급0/합산USD2 예약USD1.33185536 유지다.

## #123 UI 계약·분류 평가 준비 재개 (2026-10-18 UTC)

현재 새후보 검증: backend snapshot1조건/web activequestion1조건 최소 수정 후 독립 server7 RED1/controls6→7GREEN/FI2(assert1·5)/매복구7PASS, web7 RED4/controls3→7GREEN/기존67포함74PASS/FIassert4·복구7을 완료했다. 현재웹전체 api/lint/typecheck/build/627unit·425browser+기존skip1/실패·flaky·retry0 PASS다. 원본PR31의집중157/22와중복합산하지않는다. Native14도수정source에서실API154·routeMock0·외부/실AI/OAuth0 PASS, 실제OpenAPI JSON동일·source482/runtime1559/deps216·자산전후동일·정상종료를확인했다. Primary합성provider가TwoStage/classifier/금융graph를우회함을명시한다. 사전정답은기존120불변+새8쌍16/overlay·manifest1d15a4e/독립reviewPASS/structural·PROTOCOL_ONLY·DRAFT/변조거절PASS로봉인했고모델결과는추가하지않았다. 상세는 [UI·평가 준비 검증](#history-issue-123-ui-and-evaluation-verification)이다. 서버 unchanged전체guard도 exit0/source558전후동일·새214suites/1717중1713PASS·기존4SKIP/실패·오류0으로완료했다. 최종독립게시review도LOCAL PASS/미해결P1P2없음으로봉인됐다. 다음시작점은별도server/web Draft·정확HEADCI다. Commit/PR 동일커밋예정으로기록하며원격상태는Git/PR/로컬receipt에서이어확인한다.

독립 계약 감사에서 NEEDS_CHOICE 취소 후 snapshot의 질문과 웹 활성 controls가 남아 새 입력을 막는 P2를 발견했다. 기존67/core+관련90 unit,22 browser는 현재원본PR31에서 재PASS했지만 이 조합은 기존 oracle에 없었다. 서버#128·웹#32와 별도 격리 branch에 independent regression→최소projection/UI guard를 준비하며, 취소한 질문의 DB 이력·3회상한은 삭제/환급하지 않는다. 기존 PR126/31 원본branch는 변경하지 않는다. 새 native는 수정 source를 대상으로 재준비하고 기존 하네스의 Primary synthetic provider 우회 범위를 명시한다.

부모의 추가 지시로 유료 호출 없이 서버18cdb5d/웹PR31의02a68d8을 원격 재확인하고 UI 상태/질문·선택·직접 수정·제출·마감·비공개 계약 및 기존 시나리오를 역할 분리해 점검한다. 기존 unit620/browser425와 native12는 이전 당시 증거로 보존하고, 이번 집중 재실행이나 새 source의 관통 성공으로 합산하지 않는다. 현재18920/18921/18922 fixture는 미기동이며 현 source 재준비 가능성을 먼저 확인한다. 기존 사전 정답 개발60/holdout60의 요청된8차원 coverage와 양성/음성 기준을 독립 점검하고, 원 corpus/정답/기존 실증·threshold를 고치지 않는다. holdout본문은 prompt/구현 담당에게 공개하지 않는다.

다음 시작점은 기존 테스트/계약 대응표→가능한 로컬·격리 집중 검증→필요한 누락의 최소 독립 검증 및 평가 준비 봉인→리뷰/상태 기록이다. 새 유료호출0·UNKNOWN환급0·누적예약USD1.33185536/합산USD2·보수여유USD0.66814464를 유지한다. 작은 호출계획/상한 고정 전 실제 추가평가0, 임계값 결과맞춤0이다. 제품 추가 수정이 필요하면 격리된 승인 범위에서 진행하며 기존 PR31/다른 브랜치 덮어쓰기·병합·운영활성화·배포0이다. README후속#127은 선행검증이후, Linear는 부모 전용 동기화 담당으로 유지한다.

## 최신 수정 HEAD fresh 15초 경로 검증 (2026-10-18 UTC)

부모가 승인한 정확 HEAD `4101038fd421351affa2a1fcdd5741083cb1a6b6`/source557에서 사전 고정 LIVE-KO-03 한 건을 실행했다. 원문·문맥·LOW2048·500자·공통15초·사전 합성threshold0.8을 바꾸지 않았다. 새 helper의 독립 whole-CLI mock25/25 및 실행 전 ACCEPTED 뒤 root가 07:21:29–07:21:37 UTC에 A/count/generation 각1회만 호출했고 모두 HTTP200, retry/fallback/redirect0이다. 만료 직전 pre-open P2는 최소 수정·같은 독립 재현HTTP0으로 닫았고 원실패와 초기fixture오류를 별도 보존했다.

실제 parser/TwoStage는 NEEDS_CHOICE로 오전08–09/오후20–21 질문과 원문 날짜·의도를 보존한 두 제안을 반환했다. 동일 JVM monotonic 경로7029.248374ms <15초, JVM전체7641.481ms를 구분한다. A rawCLEAR/confidence0.06은 사전 의미 기대와 불일치하며 기존 합성threshold0.8에서 LOW_CONFIDENCE로 생성기에 전달됐다. 이 confidence는 정확도나 CLEAR확률이 아니며 운영threshold는 미선택/OFF다. UI 제출완료·SDK transport·PG원장 운영통합·일반정확도/p50/p95 성공으로 해석하지 않는다. 과거503·15초TIMEOUT·원분류FAIL·수정전parserFAIL은 그대로 남는다.

기존 UNKNOWN 포함 예약USD1.01330944 + 새 사전최대USD0.31854592 = 누적USD1.33185536, USD2 한도 내 여유USD0.66814464를 유지한다. 이번 알려진usage는 A512/0, B530입력·127candidate+533thought=660출력이다. invoice/count fee는 확인하지 않았고 예약을 환급하지 않는다. 새3HTTP 뒤 유료STOP, 누계Decisions7/count3/generation5=15다. 제품source/test/운영설정·병합·배포0, Linear는 부모 전용동기화 담당으로 재시도0이다. 독립 [의미 감사](verification/issue123-fresh-head-two-stage-semantic-review.md)와 [관측·비용 감사](verification/issue123-fresh-head-two-stage-observation-cost-review.md)는 모두 범위 내 PASS·미해결P1/P2 없음으로 봉인됐다. 기록commit 전 unchanged필수guard도07:23:05–14 UTC exit0이며 새fresh1710 실행으로 주장하지 않는다. [기록 최종 게시 리뷰](verification/issue123-fresh-head-two-stage-publication-final-review.md)도 PASS·미해결P1/P2 없음이다. 다음은 같은PR126 기록commit/push·정확HEAD CI다. Commit/PR은 동일후속커밋예정이며 평가대상HEAD4101과 이후기록커밋을 구분한다.

## 최신 USD2 진단 후속 (2026-10-18 UTC)

metadata 후속 로컬 최종검증: 독립 RED26(6assertFAIL/20controlPASS)→Contract1file 최소수정→집중328 GREEN→독립FI3 탐지/매복구/최종26PASS→실제기록 재생·독립의미보존감사PASS→새 unchanged전체guard213 suite/1710(1706PASS·기존4SKIP·실패/오류0)·source557전후일치→[최종독립게시리뷰](verification/issue123-thought-signature-publication-final-review.md)PASS/미해결P1,P2없음을완료했다. 기존원본tests·실제503/TIMEOUT/분류FAIL·수정전parserFAIL은변경0이다. 다음은같은PR126 후속commit/push·정확HEADCI다. Commit/PR은동일후속커밋예정이며정확SHA·원격결과는Git이력·PR126/ignoredpublicationreceipt에보존한다. 아래다음시작점중이미지나간단계는각당시진행기록이다.

코드b3770438/별도Draft PR126을 게시했고 정확HEAD CI39102015533 Quality Gate/ARM64 및 Lifecycle39102015519 모두 성공했다([정확 checkout/tree 근거](verification/issue123-b377-exact-ci.json)). 사용자 추가USD1로 기존모든비용/예약을포함한 Gemini+OpenAI 합산USD2 승인. 기존분류FAIL/STOP·생성503/TIMEOUT·예약0.70918144는유지한다. 비유료 원인점검·동일3111B/count530·사전oracle불변·진단runner 격리mock59/59/독립실행리뷰 뒤 단독B 두 요청만 실행했다. 첫503 high demand 뒤 두 번째200/5195.5ms에서 오전/오후 질문을 관측했고 추가유료0으로중단했다. [독립 의미평가](verification/issue123-usd2-generation-semantic-review.md)는 LTS03–07·LTS08 제안의미PASS/UI미검증이나 현parser가 공식 optional thoughtSignature를 거부해 LTS02 FAIL이다. fresh제품15초 전체LTS01은미실행이다. [관측감사](verification/issue123-usd2-generation-observation-audit.md)상 누계Decisions6/count2/generation4=HTTP12, 알려진usage견적USD0.00612370(2.2배, 청구확정아님)·UNKNOWN포함예약USD1.01330944/여유USD0.98669056를유지한다. 다음 시작점은 새 독립 RED→좁은 metadata 호환 수정→격리 재생/FI→새 전체guard/최종리뷰→같은PR126 후속commit/정확HEAD CI다. b377의556소스/전체guard/리뷰는그시점의봉인이며 새cycle성공으로재사용하지않는다. 원signature값추적/로그0·제품15초/운영설정변경0. 아래USD1중단·질문미확보·미게시기록은각당시이력이며 최신상태는이단락과 [USD2 진단](#history-issue-123-usd2-generation-diagnostic)을따른다.

## 현재 #123 영속 공통 예산 (2026-10-18 UTC)

최신 metadata 후속: 독립 새26개 RED6/통제PASS20 봉인→Contract1file 최소수정(SHA7d113125…)→집중328 GREEN/기존testinventory불변→actual raw B parser와기록A/count TwoStage replay accepted/HTTP0을완료했다. root 첫 replay의잘못된harness이름 ClassNotFound는setup오류로분리보존했다. 아래 b377 이전개발기록은그때의이력이다. 현재다음시작점은독립FI3·새전체guard·최종게시리뷰·PR126후속commit/정확HEADCI이며기존b377 CI성공은새cycle증거로합산하지않는다.

1단계 PR122 e83d5280의 코드 CI39093790134 Quality/ARM64 및 최신 Lifecycle39094909440은 성공이다. 완료 범위는 #124, 영속 예산·전체 호출·실평가는 #123으로 분리했다. PR122는 Draft·미병합이며 수정하지 않는다. 새 worktree `/workspace/issue123-durable-budget/server`의 feature/issue-123-durable-ai-budget에서 [구현 계약](ISSUE_123_DURABLE_AI_BUDGET_CONTRACT.md)·독립 명세 freeze를 완료했다. 실제 PG 원장/fence94·managed/문맥 포함 dispatch98·최종 생애/같은 방 관통10·기존 provider387 및 실제 V17 catalog72 PASS, 전송/원장 방어 P2 독립 소스 재검토 CLOSED다. core/fence FI4 각탐지·복구/마지막94 GREEN과 provider/관통 FI5 각탐지·복구/마지막109 GREEN을 완료했다. 기록 실응답을 기존 합성 threshold0.8로 재생한 pipeline2도 INITIAL_PASS다. 변경 없는 새 전체 guard는 212suite/1684개 중1680PASS·기존4SKIP·실패/오류0·source556일치로 통과했다. CODE GATE 독립 리뷰도 PASS다. 후속 실제2단계2시도는 각 새A200 LOW_CONFIDENCE→count200530 뒤 첫generation503·후속공통15초TIMEOUT에서 중단했다. 다음 시작점은 관측·503 차단 독립 감사→최종 게시 리뷰·commit/push/Draft/exactCI다. 아직 2단계 commit/push/Draft/정확 HEAD CI는 완료하지 않았다. 아래 앞선 단계의 게시/CI 대기 문구는 당시 이력이며 최신 상태는 이 단락과 맨 아래 #123 후속 기록을 따른다. 원 진단 Decisions4회와 별도 새A2회로 총Decisions6회, Gemini count2·generation시도2(첫HTTP503·후속TIMEOUT/질문미관측), 시도내 자동재시도0·운영 DB/Secret/접근/Decisions 병합/배포0.

## 저비용 결정 모델 공통 경계 준비 (2026-10-18 UTC)

- [AGENT] 최종 새 필수 guard PASS(03:19:49–03:26:17 UTC): 변경 없는 self-tests/ktlintCheck/assemble/test exit0·fresh189 suite/1479개 중1475PASS·기존4SKIP·실패/오류0. source/test/guard512개 before=after=current이며 첫 중단 시도와 구분했다. 분류142+기존18 집중160·pipeline79, 단계별 독립 FI14종의 탐지/각 복구 근거를 보존했다. 최종 독립 local 게시 리뷰 PASS·미해결 P1/P2 없음이며 commit/push/Draft/exactCI는 바로 다음 단계다. Commit/PR은 동일 커밋 예정이고 이후 정확 원격 결과는 PR/Linear·ignored publish receipt에 보존한다.

- [USER] 최신 후속: 이번 실검증 Gemini·OpenAI·재시도/fallback 합산 USD1 이하 예산 승인. 총액 준수를 입증하지 못하면 실행0이며 키 생성·전달·영구 접근 설정/배포는 제외한다. 기존 환경의 두 key 변수는 존재 여부만 값 비출력으로 확인했고 endpoint/계정/세금은 미확인이다. 별도 최신 [실행 계획](#history-issue-107-usd1-evaluation-execution-plan)을 준비한다.
- [USER] Decisions는 별도 branch/Draft로 유지·develop에 병합0. 기존 네 이슈의 필요한 비-Decisions 변경은 현재 develop과 별도 worktree에서 대조 후 통합/exact merge CI까지 승인됐다. 이미흡수/중복/폐기된 마감 후 흐름은 다시 병합하지 않는다. PR116/117 본문은 부모가 정리했으므로 수정0이다.

- [USER] 설계·mock 구현·독립 TDD/리뷰·commit/push/Draft/exact HEAD CI까지 위임. 유료 평가·키/운영/runtime·병합/배포 미승인.
- [USER] Decisions 1차 충분 CLEAR 통과·의심/정상 낮은 확신은 생성 LLM 재판정(재CLEAR 가능)·보완 선택/직접 수정·마지막 전체 분석 유지의 2단계 개발 방향 승인. 유형 안내 전용 API/SQL/web 확장 없이 분류 port·safe result·standalone OFF adapter·기존 생성 bridge·개발/holdout 평가·누적 예산 설계를 진행한다. 원문/전체마감분석·last CLEAR·기존 caps/fence 유지, 선택 후 재검사 생략0.
- [AGENT] 비-Decisions develop 통합 최종 대조: 실제 미반영 기능0, 추가 merge 불필요. PR115/118/120/121은 조상이며 열린113/116/117/119의 기능은 흡수/새 제출 전 흐름으로 대체됐다. current develop01d00e7f의 실제 merge CI39070926139·두 job 및 checkout 로그 성공을 재확인했다. [독립 대조 보고](verification/issue107-non-decisions-develop-audit.md)에 정확SHA·diff근거·격리를 보존했다. 기존PR 닫기/수정0·Decisions merge0·main/webdevelop변경0이다.

- [AGENT] 원격server develop01d00e7f/main91eb2505·webmain9b183978 대조, clean develop ff-only에서 feature/issue-107-decision-precheck 생성. 기존107 재개방, 다른 PR/branch 직접변경0. 지침/project-architecture·공식 Decisions/Jev 계약·이전인계 확인.
- [AGENT] 고위험 테스트 작성/구현/리뷰 분리. 이전/tmp JDK17/Gradle cache가 없어 환경 준비를 별도 진행하며 환경 오류를 RED로 세지 않는다. 다음은 공통계약 독립 RED·합성검증. 실제모델/OAuth/키/운영호출0.

- [AGENT] classifier 독립 RED: 최초101개 중99 TODO assertion 실패·composition2PASS·오류/skip0, 별도 uncertainty 형식7개 RED7. 원본4test SHA를 바꾸지 않고 보강 파일을 별도 cycle로 고정했다. 구현자는 RED 뒤 새 adapter/contract/port/options만 배치했고 집중 GREEN·pipeline RED는 전역 Gradle lock으로 순차 진행 중이다.
- [AGENT] GitHub #107 맨 위에 채택된2단계 목표·단계 체크리스트·미탐/오탐/오류·refusal/마감/중복·예산 검증/별도 승인 대기를 갱신·재조회했다. 기존96/110과 연결하고 이전body를 보존했으며 중복이슈0이다. 새120개 합성 corpus는 개발60/holdout60으로 분리·구조검증했고 의미oracle 독립검토 진행 중, holdout본문은prompt작성자에게 전달0이다.

- [AGENT] 집중 GREEN: 분류108+기존18=126/126, pipeline73/73 모두 실패/오류/skip0. 원본source/test 지문을 동결했고 각 독립author가 scratch 복사본에서 FI6씩 준비 중이다. 실제 PG budget/全final dispatch·price/threshold binding은 구현하지 않았으며 신규기능 default DENY가 활성화를 차단한다. source의 고정 profile fingerprint를 실제 가격·billed cap 증거로 사용하지 않는다.
- [AGENT] 새120개 사전oracle 독립리뷰 PASS·확정오류0, fixture/정답 수정0·split SHA불변·기존30bytes불변. 일반 분류 경계 보완만 평가문서에 반영했고 holdout본문/개별정답을root·prompt작성자에게 전달0이다. 실제모델정확도·가격/지연 실측 결과가 아니다.

- [AGENT] 독립 교차 리뷰에서 A CLEAR의 Decisions output usage가 기존 생성 결과2048 cap에 걸리는 P2를 발견했다. TS08을 명시하고 기존73 test/RED/FI 이력을 보존한 뒤 수정 Routing+실제 worker6회귀의79개 보완 RED(요구3FAIL/76PASS/error·skip0)를 먼저 확인했다. A terminal output은 N/A/null, 실제 usage는 stage observation에 보존하는 최소 수정만 반영했고 B cap/worker/fencing은 유지했다. 보완79/79 GREEN·실행 전후 전체 main/test5 지문 일치까지 확인했다. A output 복귀 추가 FI1과 필수 전체 guard·최종 리뷰는 다음 확인이다.

- [AGENT] 첫 필수 전체 guard 진행 중 독립 reviewer가 외부 BigDecimal probability의 극단 scale로 합계 연산 자원이 과도해지는 P2를 발견해 해당 실행을 중단했다(exit143·성공 집계0). 원본/log/전 manifest와 interruption receipt를 보존했다. DC06의 내부 decimal precision/scale 상한을 명시하고 기존 테스트를 바꾸지 않은 별도 안전 RED→최소 보완→FI 후 전체 guard를 새로 실행한다. 실제 OOM급 수를 Gradle 주 JVM에서 실행하지 않는다. 새34개 독립 RED는16 genuine assertion FAIL/18 control PASS·오류/skip0이며 기존5개 test/helper는 불변이다. confidence/probability 공통 helper의 precision/scale 검사를 값 비교 전에 추가한 최소 보정만 반영했고 runtime JSON 제약·prompt 변경0이다. 새 집중160/160 GREEN(기존126+숫자34)·source/test 전후 지문 일치까지 확인했다. 독립 cap 제거 FI1에서16 assertion 실패를 탐지했고 정확 복구160 GREEN·원본 source4/test9 지문 일치까지 확인했다. 새 필수 전체 guard를 진행한다.

## 런타임 전달·기본 OFF 제품 코드 준비 재개 (2026-10-17 UTC)

- [USER] 준비 문서 확인 뒤 외부 설정/비용 정책과 독립인 인증 전달 코드·AI 기본 OFF 구성과 입력/출력 제한 준비의 구현·독립 TDD/FI/리뷰·정확 CI·develop까지 명시 승인했다. 실제 SSM/Secret/개인키/호스트 권한·DB·OAuth·유료 호출/main은 제외한다.
- [AGENT] clean develop switch/pull --ff-only 뒤 `fix/issue-107-runtime-readiness`를 생성하고 준비 문서03448bb를21a82f6으로 적용했다. 기존 #107을 후속 코드의 정상 issue lifecycle을 위해 재개방했으며 중복 이슈/기존 PR/다른 브랜치 직접 변경0이다. 아래 문서-only 단계의 '재개방0/제품0'은 당시 이력이다.
- [AGENT] 인증 테스트/구현·catalog 테스트/구현·AI 테스트/구현·독립 최종 리뷰 역할을 분리했다. renderer auth-required/legacy 경계·엄격 JSON/type/CRLF/NUL·원자0600·symlink/실패보존·read-only mount/UID10001·실제 합성 refresh와 승인 catalog17을 먼저 테스트한다. AI는 기본 OFF 실제 바인딩·문서 기반 고정 profile·count4096 gate/사후 known promptUsage 방어·2048 합산 cap/LOW wire·count/gen 공통15초/재시도0을 독립 검증한다.
- [SHARED] 검사 admission6을 변경하지 않는다. 새 count preflight는 최대6 count+6 generation HTTP와 operation별 관측으로 구분하며 기존6을 전체 HTTP6으로 주장하지 않는다. count 값이 실제 billed input과 정확히 같다는 근거·실청구 상한·제품 모임 총10원 충돌·실모델 평가는 미해결이다. 실제 opt-in은 OFF이며 paid 호출0이다.
- [AGENT] 인증54/54(원시NUL4 genuine RED 보완 포함), AI새61+기존18=79/79, catalog26/26, 관련provider/catalog45/45·실제 local Compose fidelity·기존 guard credential refresh GREEN. auth6/provider6/catalog3 FI15종을 assertion으로 탐지·정확 원복했고 원본 제품/테스트 지문이 일치한다. Compose JSONfalse 생략의 독립 oracle 보정은 원본/재구성RED/최종GREEN을 구분했다. 수정 없는 필수 전체 guard exit0: fresh179 suite/1,258개 중1,254PASS·기존4SKIP·실패/오류0, source491 before=after=current. V8전체37/37·Nginx 합성 실행도 exit0이며 최종 독립 게시 리뷰 PASS·미해결 P1/P2 없음(`docs/verification/issue107-runtime-readiness-independent-review.md`). 게시/새CI/develop merge는 다음 단계다.
- [AGENT] 전체 Node140/140 PASS. 최초2건의 standalone javac 경로 누락은 실행 환경 오류로 분리하고 JDK17 경로를 지정한 동일 전체 검사에서 통과했다. 기존 성공으로 실패를 덮거나 유효 RED로 표시하지 않는다. CI는 새 auth54/catalog 계약·artifact marker를 연결했고 직전 실제 Quality Gate17분43초에 신규 검증 시간을 더해 제한을20→30분으로 늘렸으며 기존 검사는 유지했다.
- [AGENT] 전체 guard와 보조 검사 근거: `.tdd/verification/issue107-runtime-readiness-{final-gate,source-manifest,supplemental}.json`. Nginx 첫 로컬 권한 오류와 Node JDK 경로 오류는 성공 실행과 분리했다. FI history의 정확한 `.diff`/`.patch`는 원본 데이터로 보존하며 source diff check에서 해당 archive만 제외한다.
- [SHARED] optional String `/config/precheck-provider-enabled`는 정확 true/false만 환경변수 `MEETME_PRECHECK_PROVIDER_ENABLED`로 전달한다. 미등록/조회 실패는 false, malformed 성공 응답은 전체 교체 거부이며 refresh에도 유지된다. 실제 Parameter 생성/읽기/true 등록은 수행하지 않았다. 기존 OAuth 공개 설정 완료 인계와 별개로 runtime 자격 증명·서명 파일·실로그인·운영 전환이 남았다.

## develop 이후 아침 승인·활성화 준비 (2026-10-17 UTC)

- [AGENT] 서버 develop `bb996482a3090739a8ce02c8b093561e2c5c58a2`·정확 merge CI39064886318 성공, 웹 Draft PR31 HEAD02a68d8·CI39063558547 성공을 유지한다. main·원본 PR/브랜치는 변경하지 않았다.
- [AGENT] 별도 `docs/issue-107-main-activation-readiness`를 clean develop switch/pull --ff-only 뒤 만들었다. 제품/테스트/인프라 코드0, 준비 문서만 변경한다. 기존 #107은 develop 자동 lifecycle로 CLOSED이며 이 문서 준비를 위해 새 중복 이슈나 재개방·이전 PR 변경을 하지 않는다.
- [AGENT] 실제 bean은 default false 생성자 옵션만 사용하며 production opt-in 바인딩이 없음을 확인했다. 동일 GenerateContent 전용 thinking guide에서 합산 cap 근거를 찾아 기존 'Interactions만 근거 있음' 판단을 정정했다. thinkingBudget0 지원/실청구 검증으로 확대하지 않으며 실제 옵션 false·실제 호출0을 유지한다.
- [AGENT] 독립 provider 경계/평가 설계 리뷰에서 지원 profile·production 바인딩·OFF 호출0·LOW wire 변경의 별도 TDD, 정답 누출 금지·고정 분모·물리 호출 상한/timeout 비용 예약을 확인했다. [평가 준비안](#history-issue-107-real-model-evaluation-plan)에 generation30/retry0/count최대30·조건부USD0.32256·현재 최대비용 미입증·중단/측정 명세를 보존했다. p50/p95/정확도 실측값은 없다.
- [SHARED] PRD 모임당 총AI10원과 새 호출 예산의 최악 비용 충돌은 부모 결정 대상으로 보존한다. 한도/500자/마감/참여 정책을 변경하지 않았다. wire·활성화/인증 전달 제품 코드는 다음 독립 TDD 단위이며 문서로 구현 완료를 대체하지 않는다.
- [AGENT] [외부 안내](ISSUE_107_EXTERNAL_READINESS_RUNBOOK.md)에 완료한 Google/Kakao 공개 설정 재요구 없이 변수 이름·안전 입력 위치·RS256/JWKS·UID10001/read-only mount·DB/PITR·복구·최소 사용자 순서를 정리했다. 운영자 요약은 기본 OFF·빈 allowlist 유지이며 core 출시 차단 조건이 아니다. 감사 schema/cleanup은 별도라는 한계도 기록했다.
- [AGENT] 클라우드 연결 종료 알림 뒤 실제 shell/Git 접근 정상·문서 변경 보존·실행 중 테스트/native/browser0·18920/21/22 listen0을 확인했다. 기존 Gradle daemon은 중복 실행/종료하지 않았다. production·OAuth·Secret·DB·paid 호출/변경0.
- [AGENT] 독립 최종 문서 리뷰의 차단 결함0. provider 단독 평가의 queue/save 지연은 N/A로 보완했다. 고정 fixture30/ASK10/NOASK20·산식·7문서 상대 링크·diff check PASS, develop 대비 제품/테스트/인프라 변경0. 수정 없는 canonical commit guard(self-tests/ktlintCheck/assemble/test) exit0. 문서만 바뀌었으므로 기존 up-to-date 테스트 결과를 새 fresh 실행 수로 주장하지 않는다. 원본·receipt는 ignored `.codex/tdd-evidence/issue107-morning-readiness/`에 보존. Commit/PR: 동일 문서 커밋 예정. 기존 CLOSED #107을 재개방하거나 Draft/병합된 PR을 변경하지 않으며 이 문서 브랜치의 별도 PR/CI 완료를 주장하지 않는다.

## 현재 범위와 승인

- 사용자가 D1/D2 승인: CLEAR 후 완료 집계, 시각/수동 마감은 마지막 accepted 동결. pending 수정은 이전 accepted, 최초 미확인/미응답은 이번 결과 미반영. AI/답변 때문에 마감 연장0. 마감 직전 입력 미반영 가능 고지 및 경합 회귀 필수.
- 독립 TDD→구현→격리 provider/PG/proof/browser→FI→전체gate→독립최종리뷰→commit/push/Draft/exactCI→서버develop 병합/exactmergeCI까지 승인. main은 이후 조건부 승인되었으나 운영DB migration·Secret·실OAuth·유료실모델은 미승인이고 readiness 충족 전 main을 보류한다.
- 웹develop 원격 없음. 웹develop 생성·CI변경은 부모가 명시 보류. 기존 main 경로의 조건부 승인과 운영 readiness 보류를 유지한다. 로컬구현/검증·필요최소안 준비는 계속한다.

## develop 실제 반영

- PR115 exact c06b60a9833ae77e5fce9cfef5719ab2f37470ff의 기존CI·독립리뷰와develop비배포·동일예상tree를확인했다. 첫merge자동검토는과거Draft/CI한정승인만인식해거절. 부모의실제최신대화승인·동일HEAD1회재시도지시에따라재조회후정상mergeAPI를한번재시도했다. 우회/admin/강제push0.
- PR115의 일반merge 성공: `59e39bea8db58e17de119be353cb673fb68d9d98`. Git fetch와PR재조회로merged/closed확인, 양parent3232128+c06b60a와tree58cef138c1ea515139edd40237751596c7dfdc68(기존c06 HEAD tree동일)확인. 최신mergeSHA의pushCI는진행관찰중이며성공으로추정하지않는다.
- 다음순서:115 mergeCI성공→118 exactbd8818625aa51480e3a06c1fa17d3e223fa1cfbe→그mergeSHA CI. 113단독선행은익명HOST fixture37실패를유발하므로피한다. PR118이deadlineguard·37개적응회귀·원본증거archive를포함한다. PR113원본da225d8commit은조상이아니며기능흡수/증거보존관계를구분하고PR을임의닫지않는다.

## 로컬 작업트리와 역할

- /workspace/issue107-baseline-integration/server, feature/issue-107-baseline-integration-prep, HEAD bd88186. 부모develop/mainffpull후분리했다. 기존107/110은공통c06이후delta만3way적용해중복109을피함. 원래PRbranch변경0. catalog/역사test9충돌은일단strict110기준으로표시해compile가능하게하고최종V1..16·역사catalog는독립oracle로보정중.
- 웹같은branch명, HEAD e9e81ef. 독립구현자가29/28delta통합, 실제4충돌을hunk단위해결중/새DTO연결대기. 최종OpenAPI는통합서버에서재생성한다.
- server_precheck_tests: 공개계약독립HTTP/PG/경합/보안test·유효RED
- server_precheck_impl: durablecandidate/check/worker·V16·최소기존hunk, RED후구현
- web_precheck_tests / web_precheck_impl: 독립웹oracle와구현분리
- precheck_provider_impl: 개인검사provideradapter, root가독립transporttest작성
- baseline_merge_review: 독립병합순서검토완료, 현재독립catalog/역사fixture테스트설계
- root: 통합·기록·catalogproduction·provider독립tests·최종게이트/독립리뷰·원격단일담당

## 현재 한계와 복구 시작점

- 기존개별CI성공은현재결합/새제품통과근거가아님. 현재새feature GREEN/전체gate/독립최종리뷰/게시미완료.
- Gradle실행환경준비완료:기본home쓰기권한/directDNS/컴파일실패는RED아님. writable GRADLE_USER_HOME=/tmp/meetme-gradle-home·전역flock /tmp/meetme-gradle.lock 사용, 프록시/로컬PG/Redis준비중.
- 기존UXLibrary다운로드403은우회/반복하지않고미검증자료로유지. 설계기록은 /workspace/issue107-pre-submit/server/docs/ 의PRE_SUBMIT문서·독립리뷰를참조한다. 해당준비ZIP libfile_a12f10504fd88191a3cc041f8b4589fb는정책승인전이력이며이파일의최신승인이우선한다.
- 아침원격develop테스트URL·현재실행SHA미확인, 문서상웹staging없음. localhost격리시나리오만예비준비하며실제OAuth/paidprovider사용0. 준비물은독립기반review에기록.

- 최신 사용자 조건부 main 승인으로 앞의 main 미승인/웹develop 대안은 당시 단계 이력이다. 웹develop/CI추가 없이 기존main경로를유지하되 운영DB/Secret/권한/유료호출은별도승인유지. 실제renderer에AUTH전달·RSA/JWKSmount가없고인증blank시AUTH_UNAVAILABLE이므로main을먼저합치지않는다. 상세조건은 ISSUE_107_MAIN_READINESS.md.

## 18:22 UTC 진행

- 로컬 Temurin17.0.20.1+1을 official image digest fe947d993c32dd9cf651a4353a6ed723c2370b3a9d034ea4cab4775404ea531a 에서 준비했다. 프로젝트 JVM 설정 변경0. system CA truststore로 TLS 유지, 전역 Gradle lock 사용. main Kotlin 컴파일 통과; 테스트 setup/compile 실패는 RED0으로 구분한다.
- 승인 정책을 반영한 설계/32 acceptance+6 policy 명세·30개 의미평가 fixture·독립 설계 review를 현재 작업트리에 보존했다. 실제 모델 calls0, accuracy/p50/p95 미측정.
- catalog 독립 12개 GREEN·역사38개/Python1 GREEN·결함3종 탐지. 실제 V16 생성 뒤 실제소스publication/PG 포함 최종게이트가 남는다.
- 웹 신규 독립 테스트 진행 중. UUID fixture 보정은 기존 지문과 구분하고 재구성 skeleton RED를 시간순 최초 RED로 주장하지 않는다. 최종 source/schema/browser/독립 review 전 게시하지 않는다.
- blind 응답 실제 필드는 {replaced:true}; saved:true 간이표현은 계약이 아니다. 신규 protocol에서는 candidate 보존이며 확인완료와 구분한다.

## 18:27 UTC 실제 병합·provider RED

- PR115 merge59e39bea의 exact push CI39057006651 전체 SUCCESS 확인. 다음 PR118 exactbd881862를 정상 merge API로 병합했다: merge `9ec4c6ce7381c9fb2594f98a2112e0cc22b988f9`, 부모59e39bea+bd881862, tree6e598f417cb5e19053160572e4fb2b7bb34e6930은 검증된 bd HEAD tree와 동일. exact push CI39058071664 진행 중. main/deploy0.
- provider transport 독립 15개: 컴파일 성공 후 미구현 TODO에 대한 유효 MISSING_BEHAVIOR RED15/15(오류0) 확인, 최종 test SHA·raw log SHA·XML·예상 실패를 `.tdd/red/issue-107-pre-submit-provider.json`에 고정. 이전 컴파일/setup실패는 유효RED에서 제외했다. 유료/external model 호출0.

- 서버 독립 실제PG/HTTP 48개 실행에서 유효 assertion RED44개를 고정했다. plain TODO 예외2·V16 부재 LEGACY setup오류2는 유효RED에서 제외. 3개 독립 테스트 파일 지문/실패목록/원본 log 지문은 .tdd/red/issue-107-pre-submit-http.json. 본문/V16 구현 착수, 테스트 작성자와 구현자 분리 유지.

## 18:39 UTC 격리 검증 진행

- 최초 실제PG/HTTP48개(19+25+4) GREEN, 실패/오류/skip0. 초기RED44와비assertion/fixture4를구분했다. source변경후이므로최종전체gate와별개이며원본XML/log는precheck-first-green에보존한다.
- provider16 GREEN 및메타데이터provider/model1줄추가후재GREEN. scratch 독립fault5종이7 assertion실패로탐지되고각복구16GREEN. 최종의미정확도/유료실모델검증0.
- provider frozen test P01 methodchain자동format으로722→874 지문변경발견. 한chain줄바꿈/후행comma외변화0을exactdiff로검증했다. 원본chronological RED와722테스트를history보존하고새874의재구성RED15를별도표기(사후supplement이며시간순최초RED주장0). 현재지문으로freshGREEN·독립review를연결한다.
- 웹최종UI독립리뷰snapshot67GREEN·11faulttypes/15assertion탐지/각복구. API最終schema·전체gate·실제backend/browser·최종source결합은후속필수다.
- native test-onlyfreshPG/Redis·합성OIDC/RS256·provider 환경이loopback18920/18921/18922에서기동됐다. 공개staging/운영아님. 실제HTTPbrowser실행은독립작성자수행중이며완료추정0. 상세 ISSUE_107_LOCAL_SCENARIO.md.

## 18:44 UTC develop 병합 검증

- PR118 merge9ec4c6ce의 실제develop pushCI39058071664가전체SUCCESS. Quality Gate117470073617와ARM64BuiltImage117470073856의checkout `git log -1 --format=%H`가9ec4c6ce전체SHA와동일함을원본joblog에서직접확인했다. `docs/verification/issue-118-develop-merge-ci.json`에run/event/branch/actualcheckout근거보존. PRHEAD bd881862의이전CI39054407191와구분한다.
- PR115도merge59e39bea의pushCI39057006651+실제checkout별도근거보존. 정상developpush가자동CI를시작했고보호규칙/권한/워크플로경로변경0. main·운영쓰기0.

- 공개공식문서읽기로providerreadiness를추가확인했다. 3.8모델의thinkingBudget0비활성화와GenerateContent합산과금2048상한은확인되지않았고,Interactions API의다른파라미터보장을전용하지않는다. capfalse/mainhold유지,실호출0. 출처/현재공식가격/추론한계는 docs/verification/issue-107-provider-official-readiness.md. 최대비용·속도·정확도는미측정이며후속비용측정안도총cap확정후구체화한다.

## 18:51 UTC 전체 회귀·오프라인 계약

- 최초원본 full 1168개:288실패/기존skip4/오류0. 독립분류287개는새protocol기본값과기존PUT역사fixture충돌,1개는아키텍처정확모듈목록의precheck누락. 원본기대/소스tar/SHA/XML/log보존후역사방만명시LEGACY로scope화하고승인모듈1개만추가한다. 새default/409/보안assertion을낮추거나전체testprofile을LEGACY로하지않는다.
- offline CI Node117개재실행PASS. 첫115PASS/2FAIL은JAVA_HOME미지정으로javac경로가없던환경오류였고제품/테스트수정0으로JDK17지정후117PASS. 현재SQL16메타데이터추가SHA까지고정한근거는 .tdd/verification/issue107-integrated-offline-node.json. V8persistentguard는별도로실행중이며완료추정0.

## 19:16 UTC 최종 통합 검증

- 서버 최종 집중95개 실제PG/HTTP/proof/retention 회귀가 실패0·오류0·skip0으로 통과했고 독립 작성자가9XML·44 source/test 지문 불변을 직접 감사했다. 직전94PASS/PS06 1FAIL은 새 동일원문제안 거부 계약과 충돌한 2차 합성 fixture였다. expected NEEDS_EDIT/실패코드/미완료/accepted0는 유지하고 두 proposal만 현재raw와 다르게 정합했다. 원본/정확diff/지문supersession은 `.tdd/history/issue-107-pre-submit-index.json`에 이식했다.
- provider18 GREEN, NULguard 제거 독립FI에서2 실제assertion 탐지/복구18 GREEN 추가. 기존5 fault유형과 별도 기록하며 실제모델0.
- 실제backend/browser 독립12개 PASS, API route mock·외부요청·실제모델0. 다만 후속 필수ktlint에서 값이 동일한 문자열2개 길이만 정리하므로 해당 native 증거를 이전snapshot으로 보존하고 최종정확source 재freeze/재실행한다. 실제deadline scheduler tick 누락 하네스는 실제서비스1회control로 보완, CLOSED/frozen기대 유지.
- unchanged TDD guard 초회는 mainktlint 길이2건에서 중단해 전체test에 도달하지 않았다. 보존된9XML95는 직전focused이며 full 통과로 세지 않는다. 문자열 값/분기 동일한 최소분할 뒤 같은필수guard를 fresh 실행한다.
- 로컬 Node117·V8guard37·Compose secret fidelity·DBcredential refresh·원본Nginxruntime exit0. Nginx 초회는materialized0600 파일mode, 다음은DockerCLI의proxy자동주입/BusyBoxhealth로 실패했다. publicconfig0644복원과 이번검사만private빈DOCKER_CONFIG로 원본script/securityflags그대로 성공. 실제운영검증으로 주장하지 않는다.
- 독립core FI6/snapshot검토와웹전체표준620unit·426browser discovery 검사가 진행중. 완료/skip을 최종집계한 뒤에만 게시한다. main readiness 보류 및 actualmodel/운영접근0 유지.

## 19:34 UTC 최종 로컬 게이트

- unchanged mandatory `tdd_guard` PASS: 19:25:32~19:32:06 UTC, 새177XML 1,197개 중1,193PASS·기존4SKIP·실패/오류0. self-tests·ktlintCheck·assemble·전체test를 실제수행했고417source/test/hook이before=after=current이다. `.tdd/verification/issue107-final-server-gate.json` 및 독립 `.tdd/reviews/issue107-independent-final-full-guard-check.json`. 이전3style실패는test미도달로구분했다.
- 웹전체표준 api/lint/typecheck/unit620/build PASS, desktop/mobile 전체426중425PASS·기존skip1·실패/flaky/retry0. 최종serverformat후actualnative12/12 재PASS(19:26:14Z), runtime/sourceManifest03bb749...·source/assets불변, routeMocks/external/model0. 독립웹로컬게시범위승인완료, source127현재일치.
- provider P03 argument형식만정리해최종지문0a31로변경했다. 최초722/중간874와정확diff는history보존. 새지문사후재구성은baseline18GREEN→no-transportCLEARstub에서13assertionRED·2NoSuch(P01/P14의requestprecondition)제외→정확finalsource복구18GREEN. 초기driver의모두assertion가정은거부하고13만유효RED로저장했다. 최초15는TODO도달혼합기록이며canonical은TDD_TODO_REACHED/원본상세보존으로정합했다. 현재13을시간순최초RED로주장하지않는다.
- 새canonicalRED/FI는기존schema7필드·UTCZ로분리하고상세/chronology는history/verification에보존했다. 전체87RED readonlyaudit의기존/구통합기록28개schema drift는현재새증거로승격하지않고기록그대로유지한다. 전면schema/과거기록정리는하지않는다.
- 연결종료알림뒤실제파일읽기·쓰기및기존full/FI/provider프로세스생존확인,중복실행0. 19:27:55 UTC nativeAPI/control/preview3포트모두정상종료확인.
- 잔여:독립coreFI마지막cancel/복구·backendformat재구성3cycle/최종통합승인→commit/push/DraftPR→exactpublishedCI→승인serverdevelop일반merge/exactmergeCI. mainreadiness보류·실모델/OAuth/운영/Secret0.

## 19:38 UTC 게시 전 마감

- 독립coreFI6종각1실제assertion탐지·각정확복구GREEN완료. 초기35source중원래대비형식2파일변경은숨기지않고값동일독립검토+최종guard로연결했다. 별도schema7field canonical과최종FI review를보존했다.
- 독립backend현재지문사후3cycle RED7→정확복구GREEN7(Worker/Privacy2,Defense1,Rate4),0errors/skip. 현재4testSHA및guard417불변을저자가직접감사했다. 정확한과거제품skeleton이아니며이사후근거를최초RED로소급하지않는다.
- 최종91RED readonlyschemaaudit에서새current records전부VALID. 과거28개drift는기존/구이슈이력으로보존한다. `.gitattributes`에이번107history의`-text`만추가해checkout줄바꿈으로원본archiveSHA가바뀌지않게했다. source/test/guard/SQL변경0.
- 로컬필수/독립검증완료뒤최종리뷰마감·게시준비. 새원격branch/PR은아직없고원본PR117/119/28/29브랜치변경/닫기/forcepush0. PR115/118의develop일반merge·정확mergeCI성공은앞단계실적이며새기능CI와별개다.

- 19:40 UTC 독립최종로컬게시승인확정: server-core/최종integration·backend재구성provenance·provider/canonical·catalog·web/실서버최종source를결합감사했고승인범위는기존허용commit/push/Draft이다. 게시후exactHEAD CI를merge전게이트로구분한다. `issue107-independent-server-core-review.json`, `issue107-final-integration-review.md`, `issue107-independent-backend-format-replay-check.json` 참조. main/실모델활성화/운영준비는승인범위에추가되지않는다.


## 2026-10-18 UTC — 1단계 봉인·#123 영속 공통 예산 착수

PR122 HEAD `e83d5280c7c236c99d64165893632920882527fe` 코드 CI39093790134의 Quality Gate/ARM64가 모두 성공했다. Quality checkout synthetic `f2cd9f92fda64f2ea36446cf4dc523d03141c78a` tree는 HEAD와 동일하며 ARM64 직접 HEAD checkout을 로그로 확인했다. 실제 완료한 adapter/bridge/합성 검증 준비를 #124로 분리해 `Closes #124`, #107/#123은 Refs로 유지했다. Refs-only Lifecycle39094466469 실패를 실제 범위 분리로 보정한39094821627은 성공이며 workflow/보호규칙은 변경하지 않았다. PR은 Draft·미병합이다. #107 본문 갱신 API는 전체 기록 교체 위험으로 자동검토 거절되어 재시도하지 않으며 새 이슈/PR에 최신 연결을 보존한다.

다음 작업은 #123 영속 PG 모임 원장·최종 분석/fallback/HOST 재시도 등 전체 물리 dispatch 연결이다. clean develop01d00e7f ff-only 갱신 후 새 `feature/issue-123-durable-ai-budget`를 만들고 검증된 e83만 ff-only 상속했다. 독립 예산 DB01–24 및 전체 dispatch 41묶음 명세/구현 설계를 작성했으며, OFF 중 미계상 이력 UNKNOWN·기존30일 방 삭제 유지/새원장cascade·물리 전송 직전 single-use gate의 계약을 freeze 중이다. 이 기록 시점 새 소스/테스트 구현과 RED는 아직 없다. 금액/가격·청구 증명·활성화/소진 UX는 선택하지 않는다. 실제 모델0회·합산 USD1 비용 증명 차단·키/운영/병합/배포0. 다음 시작점은 공개 계약 freeze와 독립 PG/네트워크 RED다.


## 2026-10-18 UTC — #123 계약 freeze·catalog 독립 RED/GREEN/FI

독립 설계 리뷰 freeze PASS·미해결 P1/P2 없음. 신규 계약은 FINAL generation=실제runVersion(상수0으로 대체하지 않음), CONFIGURATION nonretryable, final SDK32MiB snapshot resource ceiling(청구 cap 아님), 기존 kind별 마감 guard·마지막 lock 뒤 fresh expiry, immutable 실제 송신 bytes·관측 실패의 제품 success 금지를 확정했다. 초안 DB06/20/21/24/10 보정은 독립 작성자가 RED 전에 원본/diff/이유로 보존했다. 실제 금액/운영 quote/활성화 경로는 추가하지 않는다.

V17 catalog는 임시 합성 fixture11개 중 유효 assertion RED7/controlPASS4/준비오류0을 먼저 봉인했다. root의3 Python 최소 패치 후11/11GREEN, 독립 source review PASS·격리 FI3종(publisher 누락17/host 역사16/명시 승인 우회)을 각각 탐지하고 매번11GREEN·원본 지문 복구했다. 신규 test는 기존CI test entry에서 require하며 workflow/보호규칙은 변경하지 않았다. 실제 V17 SQL의 VC12·실제PG와 필수 전체 gate는 아직 미실행이다. Budget/dispatch compile skeleton과 독립 신규 tests를 추가 중이며 동작 구현은 해당 RED 뒤만 진행한다. 실제 모델/운영/병합/배포0.


## 2026-10-18 UTC — #123 집중 GREEN·독립 소스 보완

원장 initial8+money44의 frozen6test 지문으로 실제PG focused52 PASS/실패·오류·skip0을 확인했다. V17 SHA `912b8f227e6ea7c20fc05cf96d705982fb9737c5051c7c07e771494df482834b`는 이 단계에서 불변이며 실제 SQL 포함catalog72 PASS와 별도다. 독립 source review는 P1없음·P2 3건(foreign reservation 잠금/공유profile 대기 뒤 quote expiry/누적합 overflow의 알려진 C 보존)을 발견했고 새 파일 RED 전에는 수정하지 않는다.

게이트/5물리HTTP/TwoStage 집중78 PASS·기존provider387 PASS/실패·오류·skip0, frozen6test 및 10source 지문 일치다. 독립 fixture 검토에서 locale와fake physical ID충돌을 고쳐 원본76/locale76 이력·수정이유를 보존했고 fixturecontrols2 PASS 뒤 현재78의 RED/GREEN을 새로 봉인했다. 독립 리뷰의 전송 직전 permit wall/effective monotonic 재검사 P2도 별도 새RED로 보완한다. 실제PGfence/Worker·Processor 문맥/managedconstructor 보완은 다음 통합 cycle이며 아직 전체 gate/최종 리뷰/게시 완료가 아니다.

Application이 adapter의 gate/property에 의존하지 않도록 pure AiDispatchModePort와 기존 생성자 끝 nullable seam만 skeleton으로 추가했다. OFF는 과거 request context=null 동작을 유지하고 ON의 mode는 금융 허가가 아니며 실제 quote/dispatch 권한이 별도로 필요하다. 새로운41 fence/coverage/회계 방어 및 managed/orchestration/clock tests의 준비·컴파일 실패는 RED0으로 분리한다. 모든 유료모델/운영/Decisions merge/배포0을 유지한다.

### 전송 만료 보완·외부 상태 기록 경계

전송 직전 permit wall/effective monotonic 검사의 새 독립 assertion RED2 뒤 gate만 최소 수정했다. 기존78+신규2=80 GREEN/실패·오류·skip0, frozen7test SHA를 유지했고 독립 재검토에서 DISPATCH-P2-01 CLOSED를 확인했다. `docs/verification/issue123-dispatch-write-review.md`와 `.tdd/history/issue123-dispatch-write-deadline-green/receipt.json`을 따른다. FI·전체 guard·managed/context/fence는 별도 남아 있다.

Linear JIN-72에 이번 집중 검증 진행을 prepend하는 작업은 자동 승인 검토가 해당 목적지의 구현·테스트 상세 게시 명시 승인 부족으로 거절했다. 재시도/우회 없이 기존 Linear 기록을 보존했고 추가 게시 허가 질문을 남겼다. 이 차단은 로컬 구현·검증·Worklog 기록에 적용되지 않는다. 실제 모델0·운영/병합/배포0을 유지한다.

### #123 추가 통합·최소 실제 smoke 준비

원장 실제 PG 기존52+회계 방어3+coverage7+foreign1+기본 authority 구성1=64 GREEN/실패·오류0을 확인했다. 독립 소스 재검토의 기존 CORE-P2 세 건도 source상 CLOSED이며 FI·전체 guard는 남는다. managed 구성·mode·Worker 문맥·기존 OFF trace를 포함한 집중98(기존80+13+3+2)도 실패·오류·skip0이다. 실제 DB fence·최종 생애9·같은 방 개인검사→자동마감→최종 분석 관통1은 별도 검증 중이며 이 수치에 포함하지 않는다. fixture 준비 오류와 유효 assertion RED는 이력에서 분리한다.

부모 최신 지시에 따라 전체 영속 원장 완성을 실제 smoke의 선행 조건으로 삼지 않는다. 기존 키를 비밀값 비출력으로 사용한 OpenAI `GET /v1/models/gpt-6-luna`와 Gemini `GET /v1beta/models/gemini-3.8-flash` 메타데이터 조회는 각각 HTTP200이다. 이는 조회 접근만 확인하며 추론 요청/응답·실제 endpoint 사용 권한·청구 검증은 아니다. 실제 추론은 아직0회, 합산 USD1 승인은 유지한다. 새 credential handoff·키/결제/운영 설정 변경0이다.

짧은 새 한국어 합성 입력·현재 분류 질문1·실제 HTTP1·retry/redirect/fallback/count/tools0의 Decisions smoke payload2431B를 고정했다. 일반 Luna 문맥 한도와 long/region 배수를 적용한 USD0.231은 명시적 추론을 포함한 보수 quote이며 Decisions 전용 billed hard cap으로 표시하지 않는다. 별도 공식 검토는 count 없는 Gemini GenerateContent1의 전체 입력 한도/출력2048·Standard 요율 기반 token quote≤USD0.794112를 제시했다(`docs/verification/issue123-minimal-smoke-gemini-cost.md`). count의 FAQ GetTokens 명칭 대응은 추론으로 구분했다. 계정별 세금/환산/결제 수수료를0으로 가정하지 않으며 USD1 계산 범위(API사용요금 또는 부대비용 포함) 질문은 대기 중이다. 실제 호출은 명확한 실행 근거가 확보될 때만 진행한다.

### 04:52 UTC — 최소 실제 Decisions smoke 성공

부모의 최신 지시에 따라 모델 전체 context 대신 실제 고정2431B의 일반 BPE byte 상한에 프로토콜 여유를 더한65,536token으로 보수 산정했다. 공식 token-counting guide의 local role/boundary 누락 가능성을 고려한 여유이며 서버 hard cap/정확 token count라고 주장하지 않는다. input USD0.10/M·long/region2.2배를 적용한 token quote는USD0.01441792, 기존USD1의 나머지USD0.98558208은 세금/결제 등 추가비용 여유로 남겼다. 계정별 비용을0으로 만들거나 새 금액·키·결제를 승인하지 않았다. tiktoken의 Luna 매핑 부재·선택적 vocabulary 다운로드 proxy403은 우회 없이 N/A로 기록했다. 독립 sanity 검토도 이 단일 소액 견적의 합리성과 한계를 확인했다.

고정 새 한국어 절대 날짜/시간/장소 사례의 실제 `POST /v1/decisions` 1회는 HTTP200·choice CLEAR·confidence0.83·input518/output0/total518·약1011.1ms다. retry/fallback/count/tools0, Gemini 추론0이다. 실제 응답 raw SHA `6ff769bca931fcc9bbb816512daf7f044ec2c146b73668e8b3e1df98d3492798`는 현재 production `AmbiguityClassificationContract`의 read/usage/parse를 추가 API 없이 통과했고 usageComplete=true다. threshold0은 raw 응답 검사용 seam일 뿐 운영 confidence 선택이 아니다. 보고 usage의 기본 단가 환산USD0.0000518(2.2배 보수USD0.00011396)은 invoice/세금 완결 증명이 아니며 여유를 반환하지 않았다.

실제 모델 총1회다. 1표본의 정확도/p50/p95를 만들지 않으며 운영 flags/OFF·authority DENY·Decisions 미병합을 유지한다. 다음 실제 평가는 기존 corpus/holdout을 읽지 않는 독립 신규 한국어4개 사전oracle 동결→동일USD1 누계·최대4개 추가 HTTP·무재시도 계획으로 한정한다. 추가 실행 전 manifest를 고정하고 불일치/오류/불명 usage면 중단한다.

### 04:55 UTC — 한국어 진단 불일치에서 중단

신규 독립4case oracle SHA `47390e74ed16237ce3dd9e9fb0002d304827c22e4592a8965f3151a8fcfd2552`와 최대합산5HTTP manifest를 먼저 고정했다. 추가3회는 주말 종일CLEAR/0.88, 문맥상오후3~5시CLEAR/0.79, 오전오후미지정8~9시rawCLEAR/0.06이다. 마지막은 사전NEEDS_REVIEW와 달라 FAIL/STOP을 유지하고 부정·예외4번째 사례는 호출하지 않았다. 기존smoke포함 총4회/input2061/output0·4응답 production parser offline PASS다. 기본 공개 단가 환산USD0.0002061/2.2배보수USD0.00045342는 최종invoice가 아니며 추가비용 여유는 반환하지 않았다.

같은 모호 사례 기록을 가정threshold0.1로 offline검사하면 기존 parser가 INDETERMINATE/LOW_CONFIDENCE를 반환한다. 운영 임계값을 정하거나 raworacle실패를 성공으로 바꾼 것이 아니고 실제 B생성을 호출하지 않았다. [실제 진단](#history-issue-123-live-decisions-diagnostic)·[독립 결과 감사](verification/issue123-live-korean-review.md)에 지문/latency4표본/미실행과 한계를 남겼다. 기존corpus/holdout재열람·프롬프트튜닝·재시도0이며 Decisions 운영활성화/병합/배포0이다.

### #123 실제 PG 통합·core/fence 독립 FI 완료

최종 min2-cohort를 정합한 real fence30+기존core64=94 GREEN/실패·오류·skip0, scoped 전체475file 전후 지문이 같다. 같은 방 개인3+1 HTTP→자동마감→최종1 HTTP의 관통1은 초회PASS이고 최종 분석/HOST retry/correction/DLQ9와 합쳐 최신10/10 GREEN이다. DLQ fixture의 구정렬컬럼 created_at을 V1정본 occurred_at으로 고친1token은 원본/diff/이유로 보존하고 기존 RED를 소급 수정하지 않는다.

core/fence 격리FI4는 baseline94→cap등호허용/중복dispatch app+SQL CAS 제거/UNKNOWN환급/실제account binding우회를 각각 genuine assertion으로 탐지했다. 각 exactrestore의 선택1/1/1/13 GREEN과 마지막94 GREEN·원본475file불변을 확인했다. FI canonical7field·schema/FormatChecker PASS이며 real fence의 중복 parameterized 표시명만 원래 failure stack의 상위method를 붙여30unique로 정합했다(결과/시각/테스트지문0변경). 다음은 provider/관통 독립FI5·current RED provenance 정합→최종 독립 리뷰/필수 전체guard→승인된게시다.

- [AGENT] 05:17 UTC 새 기록 응답 회귀2 INITIAL_PASS: 실제 classifier와 TwoStage가 raw CLEAR/confidence0.06을 LOW_CONFIDENCE로 처리하고 합성 생성기1회의 NEEDS_CHOICE/CLEAR 결과를 보존한다. threshold0.8은 e83 기존 테스트 값이며 운영 null/OFF 유지·추가 실제 호출0/누계4. 원장94/FI4·dispatch109/FI5 봉인 이후 새 테스트1파일만 추가했고 기존 제품/테스트 변경0이다. active #123 RED10개·FI3개 schema와 현재 testFiles26지문이 일치한다. 원본 hook 전체 guard와 최종 독립 리뷰를 시작했다.

- [AGENT] 부모 후속: 최초 분류 진단 FAIL/STOP을 보존하면서 전체 guard/리뷰 뒤 별도 실제 저확신 2단계 생성 재확인 목표를 준비한다. 사전 oracle·기존 합성 threshold0.8·전체 payload/count·출력2048/LOW·최대 추가3HTTP·동일USD1 누계/중단 조건을 실행 전에 고정하며, 현재 추가 호출0/총4다. 운영 정책/코드·기존 corpus/실패 oracle는 변경하지 않는다. 실제 생성 결과의 의미 평가는 별도 독립 검토한다.

- [AGENT] 첫 #123 전체 guard 05:18:57–05:26:33 UTC exit2: fresh212 suite/1684개 중1679PASS/1assertionFAIL/기존4SKIP/error0, source556 before=after. 유일 실패는 legacy upgrade 테스트의 신규 ai_room_budgets0 기대와 V17의 과거3방 HISTORICAL_UNKNOWN seed 계약 충돌이다. 제품/SQL 결함 또는 유효 RED로 세지 않는다. exact XML·guard/source manifest는 ignored `issue123-durable-budget-first-guard-failed/`, tracked `.tdd/history/issue123-first-full-guard/`에 보존했다. 독립 작성자의 unknown 상태·null 활성화 필드·금액0·예약/관측0 정합 보정과 집중 검사 뒤 전체 guard를 새로 실행한다. 새 실제 AI 호출0/총4 유지.

- [AGENT] legacy oracle 정합2 INITIAL_PASS+ktlint PASS26s, 독립 inverse diff/SHA 검토 PASS·기존 assertion/SQL/제품 변경0. 05:29 UTC 변경 없는 전체 guard를 새로 실행했다. 실제2단계 후속은 독립 oraclec69/plan368b 동결·A2397/count3172/gen3111B·최대추가3HTTP·2048/LOW·공통15초·각single-use, 누적 보수예약USD0.39063552/추가여유USD0.60936448로 준비했고 하네스/비용/독립 실행 전 검토를 연결한다. 새 API호출0/총4, fullguard/review통과뒤만호출한다.

- [AGENT] 새 필수 fullguard 05:29:32–05:36:40 UTC exit0: 수정 없는 hook self-tests/ktlintCheck/assemble/test, fresh212suite/1684개 중1680PASS·기존4SKIP·실패/오류0. source556 before=after/current, manifest3103634acd501621a7133b3168460570d47334672724a2d95cfadc751c2a2072. 첫 실패1684/1FAIL과 합산하지 않는다. 새 실제 모델 검증은 이후 CODE GATE 봉인 뒤 실행하며 현재누계4다.

- [AGENT] CODE GATE 독립 리뷰 d2dd9321 PASS 뒤 고정 plan368b/oraclec69를 05:37:50 UTC 한 번 실행했다. 실제TwoStage→새A200/.06/LOW_CONFIDENCE(in512/out0,855ms)→count200/530(225.9ms)→generation503(347.8ms)에서 중단·retry/fallback0. JVM전체2177.7ms. 총Decisions5/in2573/out0, count1/gen시도1·성공생성응답0. evalwrapper CONFIGURATION은 callback실패 요약이며 실제 공급자 원인은503이다. 성공 생성 응답/usage 증거를 확보하지 못해 질문의 의도 보존 독립평가는 차단됐고 의미PASS/오류로 단정하지 않는다. 원래rawFAIL/STOP보존/threshold정책변경0. 관측Decisions만 baseUSD0.0002573/보수2.2x0.00056606, Gemini청구/생성usageUNKNOWN·총예약USD0.39063552/추가여유USD0.60936448 유지·환급/fees0가정0. 구조화 관측 `.tdd/verification/issue123-live-two-stage-observed.json` 및 실제생성차단 문서·single-usehistory에 보존하고 독립감사를 요청했다.

- [AGENT] 관측 감사 표현 보정: callback은503 오류 본문을 저장하지 않았다. 성공한 생성 질문/usage 증거가 없다는 뜻이며 공급자503 본문이 비었다고 주장하지 않는다.

- [AGENT] 완료된 영속 원장/전체 전송 권한 기술 범위를 실제 [#125](https://github.com/meet-me-duo/meet-me-server/issues/125)에 분리했다. 새 PR은 Closes#125, #107/#123/#124는 Refs로 두고 운영 가격/confidence·실제 생성 의미 검증/활성화가 남은 #123을 닫지 않는다. PR122/e83·server develop01d00e7f/main91eb2505 및 다른 브랜치는 변경0, 신규feature remote ref는 아직없음. 다음은 관측 독립감사/최종 게시 리뷰 뒤 commit/push/Draft/exactCI이며 Commit/PR은 동일커밋예정이다.

- [AGENT] 부모503 bounded retry 명시 후 기존UNKNOWN/hold0.39063552 유지·같은3HTTP 견적0.31854592를 더한0.70918144/추가여유0.29081856 산술을 독립 확인했다. 그다음같은full시도1.02772736은USD1초과로금지. 최초503후7분이상간격의새dir/planb4fe·sameoraclec69/3payloadexact/threshold.8/harness757/내부15s·retry0를준비했고정적실행review후1회만실행한다. 현재attempt2추가calls0/기존누계7HTTP보존. 제품/test556불변·새guard불필요한문서/격리검증후속이다.

- [AGENT] 부모가 요청한 bounded 별도attempt2를 고정planb4fe/독립실행리뷰8c48 뒤05:47 UTC 한 번 실행했다. A200/.06LOW/in512out0/394.6ms→count200530/245.3ms→GEN공통15초TIMEOUT childkill. JVM전체15538.3ms(준비포함), 생성HTTPstatus/개별지연/질문/usage는미관측이며genconsumedSTARTED_UNKNOWN_HELD그대로보존·늦은청구환급0. 누계D6/in3085/out0·count2·gen시도2/성공0=평가HTTP10(모델metadata2별도), 알려진D만baseUSD0.0003085/2.2보수USD0.00067870. 기존hold전부유지+총예약0.70918144/추가여유0.29081856. 다음동일full견적0.31854592를추가하면1.02772736으로USD1보다0.02772736초과해추가dependent실제호출중단. 원503/분류FAIL/STOP·oraclec69/threshold.8·prompt/2048LOW/15초변경0, actualquestion의도보존평가여전히불가. `.tdd/verification/issue123-live-two-stage-attempt2-observed.json`과history에보존했고독립감사후게시최종화한다.

- [AGENT] 2026-10-18 최종 게시 리뷰 PASS `issue123-phase2-publication-final-review.md` d545f2fa, 미해결 P1/P2 없음. 원본 fullguard1684=1680PASS/기존4SKIP·556지문·RGR/FI/수정이력·실진단503/TIMEOUT/UNKNOWN와 Closes#125 범위를 모두 봉인했다. Commit/PR: 동일 커밋 예정. 다음 시작점은 feature/issue-123-durable-ai-budget의 의도된 코드·필수 테스트·상태기록 stage/commit/push→별도 develop 대상 Draft→정확 HEAD CI다. 커밋 이후 정확 SHA·PR URL·각 job checkout/tree와 최종CI는 해당 PR/#125/#123의 상태 및 ignored `.codex/tdd-evidence/issue123-publication/result.json`에 보존한다. 현재 실제 생성 질문 의미/운영threshold·가격·활성화·최종 청구 입증은 미완료이고 추가 실제 호출은 예산상중단·Decisions merge/deploy0이다. Linear JIN-72 추가 갱신은 기존 automatic approval review의 민감 구현정보 공개 권한 부족 거절로 보류하며 새승인없는재시도0이다.


- [AGENT] 2026-10-18 11:10 UTC #130 후속: JDK DIRECT/동일JDK DNS실패·Python환경proxy 무인증HEAD421 대조 후 helper 명시 ENVIRONMENT_HTTPS_PROXY만 최소 보완. 새 독립63 validRED42+control21→63GREEN·unchanged102GREEN·FI8 exactrestore 모두PASS, budget previous1.56254208/원예약증거 필수로 과거hold rollback거부. 새factory 무인증HEAD1은 proxy연결/TLSstart 뒤 SSLHandshakeException/HTTP미관측이었다. 기존 공개CA client한정 추가도 trust anchor확장이므로 명시승인대기·CAcode/운영/전역설정/새유료0. 현재holdUSD1.56254208/여유0.43745792/환급0, retry0.23068672는 제안일뿐 미예약/미실행. 최신 proxy source 전체guard/게시리뷰와 CA선택·실제의미평가는 후속이며 최초235source guard결과를 새source로 소급하지 않는다.


- [AGENT] 2026-10-18 11:22 UTC #130 updated proxy source 필수 hook selftests/ktlintCheck/assemble/test exit0·source566 전후동일(11.398초). 기존XML1717=1713PASS/기존4SKIP 재사용으로 fresh전체실행 주장0, 원래fresh235source guard와별도독립165/FI8을구분한다. `.tdd/verification/issue130-proxy-source-final-quality.json` SHA8d7b53f4. 현재paid0추가·hold1.56254208/여유0.43745792·환급0/CA신뢰추가승인대기 유지. 실제대표의미 미측정·최종게시review는후속이다.


- [AGENT] 2026-10-18 11:34 UTC #130 최종 독립 기술게시review LOCAL PASS(미해결P1/P2없음). current566source·집중13파일·165합성/FI8·필수6절PR본문과 STOP/분모0/hold1.56254208/CA대기/paid추가0 정합을확인했다. Commit/PR 동일커밋예정이며 다음단계는feature/issue-130-representative-decisions의의도된 실행기·필수검증·상태기록 commit/push→별도Draft→정확HEADCI다. 실제품질/가격/threshold/운영활성화완료·추가유료승인으로확대하지않으며이후SHA/PR/CI는Git·PR·로컬publicationreceipt에기록해재귀상태커밋을만들지않는다.


- [AGENT] 2026-10-18 11:58 UTC #130 첫 게시CI39123972026/HEAD62f47bb: ARM64·Gradle1717·기존합성102PASS 뒤 새proxy49Java중assertion1(48PASS)/Python14PASS 실패. 원caseID 미출력으로원인미확정/실패그대로보존. cleanCI-like63PASS·관측보완뒤63PASS이며 original49/14/TLS/helper2bytes불변. safe49ID/status/type whitelist만공개하는새privacy4RED→4GREEN/CI연결, 예상값·timeout·gold변경0. 후속필수guard source567 same/exit0·8.718s/기존XML재사용. 다음은독립리뷰·동일PR132정상후속게시/exactCI이며첫실패해결주장0. actualCA/paid0·hold1.56254208/여유.43745792/환급0 유지, Commit/PR 동일후속커밋예정.


- [AGENT] 2026-10-18 #130 CI 관측 보완 한정 최종독립리뷰 LOCAL PASS·미해결 P1/P2 없음. 고정49 ID와 status/type만 공개하는 projection, 새privacy4 RED→GREEN, 기존49/14/TLS/helper 불변·63GREEN, source567 필수guard 전후동일/기존1717XML재사용을 확인했다. 첫CI assertion1의 case/원인은 여전히 미확정이며 기대값·timeout·factory를 고친 결과가 아니다. 다음은 동일DraftPR132 정상후속commit/push·새정확HEADCI이고 정확SHA/상태는Git·PR·로컬receipt에기록한다. 실제품질분모0·CA미구현/승인대기·추가유료0·hold1.56254208/여유.43745792/환급0 유지. Commit/PR 동일후속커밋예정.


### 2026-10-18 UTC — 승인된 평가 client CA1개와 단회 재평가 준비

[USER] 21:12 KST에 기존 환경 CA1개를 이번 로컬 평가 Java 클라이언트 메모리에만 추가하고 정상 인증서·hostname 검증을 유지하는 범위를 승인했다. 무인증 연결 확인 후 기존 불확실 예약을 유지하며 합산 USD2 내 동결16건 실제 평가를 진행한다. 전역/JDK/OS/운영/Secret 변경·임의 CA 설치·내부 인증 진단 공개 재시도는 범위 밖이다.

[AGENT] 기존 helper·원문/gold·prompt·500자·운영 임계값을 바꾸지 않는 additive opt-in 계약을 작성했다. 구현자와 독립 테스트 작성자를 분리했고 새 RED→GREEN·FI/리뷰를 준비 중이다. 현재 예약1.56254208/여유.43745792/환급0이며 새 실행 시작 전에만 .23068672를 추가 예약한다. 실제 CA 추가·무인증 확인·추가 유료 실행은 아직0이다. Commit/PR: 동일 Draft132 후속 커밋 예정.


### 2026-10-18 UTC — client CA·JDK 독립 검증과 비유료 연결 확인

승인된 helper2개만 최소 수정했다. CA66·JDK16·기존169 회귀 PASS와 새 CA FI6/JDK FI3 탐지·각 정확 복구를 확인했다. 기존 PC01 기대값/30초·원문/prompt/500자·15/300초·source/runtime/예약 fence는 유지했다. runSuite crypto 보완3의 사후 작성과 재구성 RED를 최초63 RED와 구분했다. 필수 guard source574 전후동일/exit0, 기존1717 XML 재사용을 명시한다.

source271/runtime1401 후보를 고정한 뒤 독립 ACCEPTED에 따라 무인증 HEAD1을 실행했다. 정상 TLS·hostname 검증이 완료됐고 HTTP421을 받았다. 전송 경로만 확인했으며 모델 서비스/품질 성공으로 부르지 않는다. 기존 환경 CA1개만 client 메모리에 추가했고 전역/운영/Secret 변경0이다. 예약1.56254208/환급0 유지, 별도 동결16 실행 계획은 최종 독립 검토 중이며 추가 유료0이다. 내부 상세 진단은 로컬만 보존한다. Commit/PR: 동일 Draft132 후속 커밋 예정.


### 2026-10-18 UTC — 승인된 실제 A16 완료·독립 품질 FAIL

별도봉인plan a6a58bb2/독립ACCEPTED90efeb3c/입력무결성742c8761 뒤root가1회만실행했다. HTTP20016/usage16/source-runtime전후동일, technical/refusal/UNRUN/B/count/retry0이다. 이전UNKNOWN포함1.56254208+.23068672 전액을유지해예약1.79322880/여유.20677120/환급0이다. input8395/output0의입력단가전용추정.0008395는invoice가아니다.

독립custodian은사전원문/gold/순서/prompt/threshold/채점규칙을유지해raw9/16·materialmiss6/8·불필요rawreview1/8과zero-false-CLEAR gate FAIL을기록했다. 진단.8 정규화는LOW_CONF13/CLEAR_STOP3/B_REQUIRED13, 검토군unsafeearlyCLEAR0/8·명확군불필요B5/8이며rawmiss를지우지않는다. B gold질문금지8/선택필수7/editonly1을구분하고실제질문품질분모0을유지한다. single-member집계6필드의bool→int형식만정정해원본06029/정정f9fb1와diff를보존했고수치·판정·audit서술은불변이다.

기존169+CA66+JDK16=251합성PASS·새FI9 탐지/복구·mandatorysource574PASS를확인했다. 다음은최종기록/게시독립리뷰→동일Draft132정상후속commit/push→정확HEAD CI다. 실제모델추가호출·운영threshold/가격선택·활성화·merge/deploy0이며품질FAIL/B미검증은잔여로남긴다. Commit/PR: 동일후속커밋예정.


### 2026-10-18 UTC — client CA/JDK·실제 A16 기록 게시 전 독립 리뷰 PASS

독립 읽기 검토로 helper2/CI 신규 합성 단계·7test와 source574 현재 지문, 기존169·CA66·JDK16/FI9 복구 근거 및 공개 집계·비용 산술을 대조했다. 기존 test12개 bytes와 기대/timeout 불변, 실제 CA 지문·체인·원문/gold·응답을 공개 후보에 포함하지 않았으며 새 P1/P2는 없다. 필수 guard의 기존1717 XML 재사용을 명시하고 새 fresh 실행으로 확대하지 않는다. 기술·기록 게시 LOCAL PASS이며 raw품질FAIL/B질문분모0·청구미확정·전액예약1.79322880/환급0을 유지한다. main 병합·배포·운영 활성화는 HOLD, 별도 의미 결과 최종 감사 및 동일 Draft132 후속 commit/push·정확 HEAD CI는 부모가 결합한다. 리뷰어의 실제 CA/키/코퍼스 접근·추가 네트워크·제품/테스트 변경0이다.


### 2026-10-18 UTC — 개인 맥락 후속 승인과 무과금 원인 분석

[USER] 실제 참가자의 퇴근/수업/육아/교대/이동·지시/조건 맥락을 포함한 미사용 평가를 요청하고 Gemini/OpenAI 합산 상한을 USD3으로 늘렸다. 기존 보수 hold1.79322880과 UNKNOWN/환급0을 보존한다. 길고 복잡한 실패별 예외 대신 명확하고 짧은 원칙형 지침을 우선한다.

[AGENT] 원래16의6miss/5과잉B를 독립 분해했다. 원문+5context·instructions/choices/hash가16모두 일치하고 공식choice/confidence해석 오류 근거0, confidence를 CLEAR확률/보정정답률로 읽지 않는다. 현재 purpose/mode는 미전송으로 문맥 설계의 한계와 전송 완전성을 구분한다. 기존 raw9/16·miss6/8·FAIL과B0분모는 불변이다. 완료 후 사용자가 명시 요청한 기존 TIME2건만 root에 공개했으며 새24는 미공개다.

[AGENT] 1448B190word instructions-only 후보의 독립정책 리뷰 PASS, 의미효과 미검증. 선택지·6input·500자·.8·참여/마감·최종분석은 유지한다. 독립author/rater 새24/12쌍 사전gold는 비교/정책조정 중이며 root/후보구현자는 원문·개별gold를 보지 않는다. A24 후 사전B2 standalone quality의 최대 예약 계획은 총2.74751488/여유.25248512이나 아직 새예약·유료실행0이다.

[AGENT] 정확 b677/run39129609869 CI는 Gradle/ARM성공 뒤 기존 bounded BASE_FAILED exit1로 실패했다. 개별원인은 미확정이고 proxy/CA/JDK 이후는 미실행이다. 원12test파일을 바꾸지 않고 새 safe launcher로 고정 단계/ID만 관측하는 독립TDD를 준비한다. 다음은 무과금 RED/최소구현/FI/guard·실행전검토→단회holdout→B진단→최종기록/독립리뷰/동일Draft후속게시·새HEADCI다. main/deploy/활성화HOLD, Commit/PR 동일후속커밋예정.


### 2026-10-18 UTC — 원칙형 지침·A24 검증과 B 범위 동결

원칙형 instructions1448B/267af412만 반영했고 선택지/모델/6input/.8/500자/참여·마감정책은 불변이다. 독립wire2유효RED 뒤 집중45PASS를 확인했다. 새24profile는 독립41 중15유효RED+26controls 뒤 최종41PASS·FI6 모두 실제assert탐지/각복구41PASS다. 첫39/41의CA필수검사2실패와 sibling파일수정에따른inventory불일치는 그대로보존하고 guard를고친뒤새전수41/source동일을따로봉인했다. CI관측22PASS/FI4·원19평가test파일불변도확인했다.

새24 입력/원두gold/실행전B8차조정·사전B2를봉인했다. 최종A14CLEAR/10REVIEW·B14NOTASK/1CHOICE/9EDIT, 미해결정책0이며root/후보구현자미열람이다. 이전16의goldREVIEW8에서같은집합을2로줄인것이아니다. 새B2는유일CHOICE와고정순서첫생활EDIT로선정,3번째B는합산3.05164288로한도를초과한다. 따라서남은review8과CLEAR14의실B질문회피·UI/전체모임품질은미검증이다.

독립B143RED(140요구assert실패+3positivecontrols)를기대변경없이봉인한뒤Bhelper구현중이다. Kotlin-only원REDschema는변경하지않고평가Java/Python7field증거는verification에따로두었다. 격리b677+정확동결runtime의원102단회PASS는원격CI BASE_FAILED원인해결로소급하지않는다. 현재실제hold1.79322880/환급0/새예약·paid0, sourceguard·B검증·실행전freeze수용후A24/B2순서로진행한다. Commit/PR 동일후속커밋예정.


### 2026-10-18 UTC — A24/B2 실행 전 검증 후속

B 전용 실행기 143개 독립 계약 검사가 통과했고, scratch 변이11종을12개 assertion으로 탐지했다. 매회 복원 및 마지막143개 재검증에서 실패/오류/skip0이다. 실제 source·test 변경과 새 유료 호출0이며, 이 검증은 운영 수용이나 의미 품질 증명이 아니다. A24 41검사/FI6, CI 실패 집계22검사/FI4와 함께 최종 필수 guard·source/runtime 봉인 뒤 실행할 예정이다.

전체 회귀 중 JDK fixture2건이 새 A24 profile의 필수 suiteSeconds를 반환하지 않아 tool 선택 전 CONFIGURATION으로 실패했다. 실제 plan 검증을 완화하지 않고 테스트 stub에 기존 profile의 suiteSeconds300·caseCount16만 명시한다. 원19개 test의 byte freeze는 이 fixture1개에만 예외를 기록하고 원본·실패 증거를 보존하며, 나머지18개는 그대로다. 이 로컬 JDK fixture 오류를 기존 원격 BASE_FAILED의 원인으로 단정하지 않는다.

최종 필수 guard는 source590 전후동일·exit0·10.739초다. 앞서 fresh 실행한 Kotlin215suites/1719(1715PASS+기존4SKIP) XML을 재사용했으며 새 fresh 실행이라 부르지 않는다. JDK fixture 보완 뒤16GREEN·selector hardcode 격리변이14assert/2control·정확복원16GREEN을 확인했다. production main249 지문과 실제 클래스877·resource24·jar162 및 실제 helper13개를 별도 snapshot으로 묶어 CP regular1076개를 고정했고, 테스트 클래스·테스트 의존성은 포함하지 않았다. 입력 담당자의 runtime-pinned A24/B2 wire 검증과 root 단회 실행기의 합성·독립 검토가 남았다. 실제 추가 예약/호출0, 기존 hold1.79322880이다.


### 2026-10-18 UTC — A24 원 실행 CONFIGURATION 중단

정확 plan128ef6/독립수용c99283로 A24를 단회 시작하면서 .34603008을 선예약했다. wrapper exit2/CONFIGURATION·stderr0·suite/case/response와 결과 디렉터리0이며 source/runtime전후동일이다. 원 Java cost parser와 진단 copy stack으로 .10/.01441792/.34603008이 Python에서는 통과하나 Java의 선행0 필수 문법에서 거부됨을 확인했다. 제가 만든 계획이 이 형식을 사용했고 독립 수용에서도 숫자만 대조해 놓쳤다. mainClient 객체 생성과 실제 HTTP dispatch를 구분한다.

A24/B2는 전원UNRUN, 새 모델 결과/의미 분모0·raw gate NOT_EVALUATED다. 기존16 raw FAIL은 유지한다. 현재 장부2.13925888/refund0·B예약0이며 재시도0이다. 부모 후속 지시에 따라 기존 누적 예약1.79322880(UNKNOWN 포함)은 그대로 두고, 이번 .34603008은 immutable source/stack/exit/산출물 및 강제 무네트워크 exact 재현으로 proven-unstarted 정산 가능 근거를 검토한다. 확인 가능한 구성 중단을 실제 청구 소진으로 취급하거나 추가 예산을 요청하지 않는다. 원 예약을 덮어쓰지 않으며 별도 정산 근거와 재실행 조건을 먼저 보고한다.


### 2026-10-18 UTC — 비용 문법 보완과 미실행 정산 근거

새 public preflight56은 validRED44+controls12 뒤 최소 Python2hunk로 GREEN이다. guard 두 개를 각각 제거한 FI2를44assert로 탐지하고 각복원56을 확인했다. 기존 A41/B143도 GREEN이며 기존fixture/Java/가격/한도/원문/자연어 의미판정은 바꾸지 않았다. CI에 새56을 명시적으로 연결했고 YAML·기존 배포정책13검사를 통과했다.

원 source13b0/c28 및 계획128 그대로 DUMMY key·강제 Python/JDK network/output deny에서 wrapper→fresh javac→Java.main을 재현해 같은CONFIG/exit2를 확인했다. mainClient 생성 완료 후 validate 진입, index JSON파싱0·DNS/connect/outputwrite시도0·원1684지문 불변이며 deny자체6assert를 확인했다. 과거 실제 packet capture나 invoice조회로 소급하지 않는다. 이번 .34603008은 proven-unstarted 취소정산 후보이며 장부2.13925888/정산적용0·기존누적1.79322880(UNKNOWN포함)·원예약/FAIL 보존이다. 추가 예산 요청0이다.

수정source e79fa4/f9d59로 실제 Python→fresh javac→A.main/mainClient/validate→정상 로컬 합성TLS 24요청의 전체canonicalbyte·파싱/route/usage24·B/count0·replay추가요청0을 확인했다. 외부connect를 startupguard로 막았고 새 helper를cachedclass가shadow하지 않는CP배치를 기록했다. 이 양성합성경로는 실모델 품질이 아니다. post-stop 필수guard source591/manifest2f9be985 전후동일·exit0/11.485초·기존1719XML재사용이다. 새 실제24/B2는전원UNRUN, 본인 원문·개별gold는root미열람으로유지한다. [정산 근거·재실행 조건](verification/issue130-cost-stop-and-reexecution-plan.md)을 기록했으며 실제정산/재시도0·최종게시CI대기다.


## 통합한 과거 실행·리뷰 원문

다음은 각 작성 시점의 기록이며 현재 실행/병합/비용 승인이 아니다. 원문 전체와 당시 제한·실패·승인 경계를 보존하고 링크만 현재 위치로 연결했다. 원본 파일은 Git의 `ebcf218cd27619e3129e9484c7066899693b378b`에서 복구할 수 있다.

<a id="history-issue-107-local-scenario"></a>

<details>
<summary>ISSUE_107_LOCAL_SCENARIO.md</summary>

원본: `docs/ISSUE_107_LOCAL_SCENARIO.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_107_LOCAL_SCENARIO.md`

<a id="history-issue-107-local-scenario--107-로컬-제출-전-확인-재현"></a>

# #107 로컬 제출 전 확인 재현

최종 갱신: 2026-10-17 UTC. 이 문서는 로컬 격리 검증만 설명한다. 실제 AI, 운영 계정, 실제 OAuth 서비스, Secret 변경, 배포를 사용하지 않는다. 게시 커밋과 정확 HEAD CI 연결은 통합 담당자가 추가한다.

<a id="history-issue-107-local-scenario--확인된-범위와-근거"></a>

## 확인된 범위와 근거

실제 browser → Spring → PostgreSQL 18/Redis 8 → 응답 경로를 사용했다. 로컬 OIDC/RSA와 실제 게스트 password/proof 검증을 통과하며 precheck provider만 명시 합성 대역이다. 웹의 `docs/verification/issue-107-native-browser.json`에 12/12 PASS, 실제 API 관측 128회, route mock/외부 요청/실모델 호출 0과 실행 전후 소스·runtime manifest·자산 지문 불변을 기록했다. 이것은 실모델 오탐·미탐, 정확도, p50/p95, 비용의 측정이 아니다.

검증은 pending 저장/완료 구분, 명시 선택과 직접 수정 재검사, 넓은 의도 보존(합성 CLEAR), reload·CAS·멱등 재전송, 수동/기한 마감 중 늦은 결과 차단, 마지막 accepted만 실제 batch에 고정, 실제 본인 proof, REPLACE_ONLY 조회 금지, invalid Bearer 우선, 계정 logout 뒤 개인 결과 격리를 포함한다.

웹 저장소의 근거 파일:

- `.tdd/verification/issue-107-native-runtime-source-manifest.json`: 서버 main Kotlin·migration·하네스 216개 파일. 원본 digest `03bb749dad1461b5096a17c7a6bc0dd76bb4fbd437bfb176ac75d7ce044ce9a0`.
- `.tdd/verification/issue-107-native-frozen-class-resource-manifest.json`: 같은 잠금 구간에서 복제한 runtime 파일 1,339개의 SHA-256.
- `.tdd/verification/issue-107-final-native-contract-gate.json`: 최종 실제 OpenAPI와 웹 snapshot의 JSON 의미 동일성 및 준비 로그 연결.
- `docs/verification/issue-107-native-browser.json`: 웹 행동 소스 7개, native outDir의 모든 자산, 실행 script의 SHA-256. 일회성 `/tmp` 경로 이름이 달라지면 재현 실행의 새 근거로 기록한다.

<a id="history-issue-107-local-scenario--실행-준비"></a>

## 실행 준비

서버와 웹의 통합 검증 소스를 각각 checkout하고 의존성을 준비한다. Docker가 사용 가능해야 한다. 서버 하네스는 `AuthEndToEndPostgresTest`의 격리 컨테이너와 로컬 인증 대역을 재사용하며 별도의 운영 자격 증명을 받지 않는다. 웹 의존성은 lockfile 그대로 설치한다.

이 환경에서 JDK17은 `/tmp/meetme-jdk17`, Gradle cache는 `/tmp/meetme-gradle-home`을 사용했다. 다른 환경에서는 해당 설치 경로만 바꾼다. 공유 작업에서는 서버 build/class 복제 사이에 다른 Gradle이 끼어들지 않도록 같은 `/tmp/meetme-gradle.lock`을 사용한다.

```bash
export JAVA_HOME=/tmp/meetme-jdk17
export GRADLE_USER_HOME=/tmp/meetme-gradle-home
export JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts
```

서버 저장소에서 아래 Python 내용을 `/tmp/issue107-native-prepare.py`로 저장한다. `repo`는 현재 서버 checkout을 가리킨다. 컴파일, class/resource 복제, source manifest 작성을 하나의 flock 구간에서 수행한다. 실행 중인 이전 fixture를 먼저 정상 종료해야 한다.

```python
from pathlib import Path
import hashlib, json, shutil, subprocess

repo = Path.cwd()
subprocess.run([
    './gradlew', '--no-daemon', '--console=plain', '-I',
    'docs/verification/issue-107-native-browser.init.gradle',
    'issue107NativeRuntimeClasspath',
], check=True)
frozen = Path('/tmp/issue107-native-final-frozen-runtime')
frozen.mkdir(exist_ok=True)
runtime = []
parts = Path('/tmp/meetme-issue107-native-runtime.classpath').read_text().split(':')
for index, part in enumerate(parts):
    source = Path(part)
    if source.is_relative_to(repo / 'build') and source.exists():
        target = frozen / f'{index}-{source.name}'
        if source.is_dir():
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(source, target)
        else:
            shutil.copy2(source, target)
        runtime.append(str(target))
    else:
        runtime.append(part)
main = 'com/meetme/server/submission/adapter/input/web/PreSubmissionCheckNativeBrowserServerKt.class'
assert any((Path(part) / main).exists() for part in runtime)
Path('/tmp/issue107-native-final-frozen-runtime.classpath').write_text(':'.join(runtime))
files = sorted(list(repo.glob('src/main/**/*.kt'))
    + list(repo.glob('src/main/resources/db/migration/*.sql'))
    + [repo / 'src/test/kotlin/com/meetme/server/submission/adapter/input/web/PreSubmissionCheckNativeBrowserServer.kt'])
manifest = {str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest()
    for path in files}
Path('/tmp/issue107-native-runtime-manifest.json').write_text(json.dumps(manifest, indent=2))
```

```bash
flock /tmp/meetme-gradle.lock python3 /tmp/issue107-native-prepare.py
rm -f /tmp/issue107-native-stop
"$JAVA_HOME/bin/java" --add-modules=jdk.httpserver \
  -Dmeetme.issue107.native.stop-file=/tmp/issue107-native-stop \
  -cp "$(cat /tmp/issue107-native-final-frozen-runtime.classpath)" \
  com.meetme.server.submission.adapter.input.web.PreSubmissionCheckNativeBrowserServerKt
```

live 서버의 수명 동안 Gradle lock을 잡지 않는다. 준비 후 소스가 바뀌면 기존 근거를 새 소스의 성공으로 표시하지 않고 정상 종료·준비·실행을 다시 수행한다.

<a id="history-issue-107-local-scenario--웹과-독립-시나리오"></a>

## 웹과 독립 시나리오

웹 저장소에서 native 전용 자산을 별도 outDir에 생성한다. `localhost`는 로컬 OIDC redirect/origin 계약의 일부다.

```bash
VITE_API_BASE_URL=http://localhost:18920 pnpm exec vite build --configLoader runner \
  --outDir /tmp/meetme-precheck-native-dist
pnpm exec vite preview --configLoader runner --outDir /tmp/meetme-precheck-native-dist \
  --host localhost --port 18921 --strictPort
```

별도 터미널에서 fixture health의 `fixture=issue107-isolated-native-v1`, `production=false`, API/web origin을 확인한 다음 실행한다. script는 loopback만 허용하며 실제 API 응답을 대체하지 않는다. 실행 중 runtime/source/native 자산을 바꾸지 않는다.

```bash
curl --fail http://127.0.0.1:18922/health
CHROMIUM_PATH=/usr/bin/chromium node scripts/verify-precheck-native.mjs
```

테스트 제어는 127.0.0.1:18922의 별도 HttpServer에만 있다. `/reset`은 격리 방만 초기화하고 실제 로그인 계정/session은 유지한다. `/provider`, `/provider/release`, `/worker/run-one`은 명시 합성 결과와 provider barrier를 제어한다. `/room/deadline`은 격리 방의 시각 경계만 바꾼다. `/room/run-deadline-closure`는 실제 `DeadlineClosureScheduler.closeDueRooms()` transactional bean을 한 번 실행한다. collection_status를 SQL로 CLOSED로 바꾸지 않는다. 이 명시 tick은 기존 60초 주기를 기다리지 않고 실제 deadline/freeze 경로를 검증하기 위한 test-only 제어다.

<a id="history-issue-107-local-scenario--종료와-남은-경계"></a>

## 종료와 남은 경계

`touch /tmp/issue107-native-stop`으로 API/control/context와 컨테이너를 정상 종료한다. 웹 preview도 종료한다. 재시작은 이전 health가 내려간 것을 확인한 뒤 정확한 stop file 하나만 제거하고 위 준비부터 반복한다. 로그에 토큰·password·실제 개인 입력을 출력하지 않는다.

실모델 품질·실제 OAuth 공급자·운영 Secret/DB·배포와 정확 게시 HEAD CI는 이 로컬 결과와 별도다. 실제 provider의 출력 상한 capability가 검증되지 않으면 fail-closed하므로 로컬 합성 성공을 운영 활성화 준비 완료로 간주하지 않는다.

<a id="history-issue-107-local-scenario--필수-전체-게이트와-생산-artifact"></a>

## 필수 전체 게이트와 생산 artifact

최종 unchanged `tdd_guard`가 self-tests·ktlintCheck·assemble·전체 test를 통과했다. 실제 새 XML177개 집계는 1,197개 중1,193 PASS·기존4 SKIP·실패/오류0이고, 검사 시작/종료의417 source/test/hook 지문이 동일하다. 근거는 `.tdd/verification/issue107-final-server-gate.json` 및 `issue107-final-server-source-manifest.json`이다. skip은 기존 OpenAPI debug/export3개와 미승인 live model probe1개다.

생산 boot JAR SHA-256은 `6f95bfa110ce1d26401a0a2906d8ac9852085582dc884a1112b1869641149d21`이고, plain JAR는 `53e6d0f89b354a39238ab8d9fe016f356731af66eeee83e2133ecc4253a27318`이다. 두 JAR에서 V16 포함·test-only native harness 미포함을 확인했다. 이 artifact 검증과 위 native 테스트의 frozen classpath 런타임은 구분한다. 게시 commit/CI는 PR receipt와 통합 Worklog에 별도로 연결한다.

</details>

<a id="history-issue-107-main-readiness"></a>

<details>
<summary>ISSUE_107_MAIN_READINESS.md</summary>

원본: `docs/ISSUE_107_MAIN_READINESS.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_107_MAIN_READINESS.md`

<a id="history-issue-107-main-readiness--meet-me-main-반영-준비-경계"></a>

# Meet me main 반영 준비 경계

2026-10-17 UTC. 최신 사용자는 웹 기존 main 경로 유지 및 대응 서버 main 병합을 조건부 승인했다. 별도 웹 develop/CI 경로는 만들지 않는다. 검증 없이 즉시 운영에 반영하는 승인으로 해석하지 않는다.

<a id="history-issue-107-main-readiness--현재-판정"></a>

## 현재 판정

2026-10-18 후속: 서버 PR121의 develop01d00e7f·merge CI39070926139는 완료됐다. 현재 별도 feature/issue-107-decision-precheck에서 승인된 Decisions→필요시 생성 LLM의 기본 OFF 구현/독립 검증을 준비하며, 이번 승인은 Draft/exact HEAD CI까지다. 실제 금액/계정/청구/품질·durable 모임 예산/최종 분석 전체 연결은 미완료이고 Decisions의 main/develop 병합·배포는 하지 않는다. 최신 비-Decisions 기존 변경의 develop 통합 승인은 별도 격리 대조로 다루며, 합산 USD1 평가 승인도 [실행 조건](#history-issue-107-usd1-evaluation-execution-plan)의 비용 증명 전에는 호출하지 않는다. [2단계 계약](ISSUE_107_TWO_STAGE_CONTRACT.md)·[평가/키 준비](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md)를 최신 후속으로 본다. 아래 단일 Gemini 설명과 이전 문서 링크는 기존 runtime 준비 이력이다.

**main 병합은 준비 조건 확인 전 보류.** 구현·격리 테스트·서버 develop 통합은 계속한다. main 병합을 통해 아직 승인되지 않은 DB/Secret/권한/유료 모델 작업을 간접 실행하지 않는다.

서버 PR120은 develop `bb996482a3090739a8ce02c8b093561e2c5c58a2`로 일반 병합됐고 [정확 merge CI39064886318](https://github.com/meet-me-duo/meet-me-server/actions/runs/39064886318)이 성공했다. 웹 PR31 HEAD `02a68d8e50680e87dd153156e8bb7ea884a794cc`의 [CI39063558547](https://github.com/meet-me-duo/meet-me-web/actions/runs/39063558547)도 성공했으며 Draft·미병합이다. 실제 모델/OAuth/운영 준비 성공과 구분한다.

남은 코드·공식 근거 정정·독립 테스트·평가/비용 명세는 [실제 모델 평가 준비안](#history-issue-107-real-model-evaluation-plan), 완료된 공개 설정을 반복하지 않는 사용자 순서는 [외부 준비 안내](ISSUE_107_EXTERNAL_READINESS_RUNBOOK.md)를 따른다. 운영자 요약은 기본 OFF로 유지하며 core 출시의 차단 조건이 아니다.

| 확인 대상 | 저장소 근거 | 남은 조건 |
| --- | --- | --- |
| 서버 main 자동 배포 | `.github/workflows/deploy.yml`의 main push CI 성공 후 build/publish, 이후 기존 승인 release contract·host guard | 새 catalog/guard 지문은 기존 승인을 재사용할 수 없음. 실제 현재 host/history/approval의 읽기 검증과 운영 schema 전환 승인 필요 |
| Flyway | `application.yml`은 enabled, 새 기능은 V11~V16 필요 | 운영 DB migration은 현재 미승인. 최근 문서의 V1~V7 기록은 현재 DB 상태 증명이 아님. 키나 DB 값을 읽거나 바꾸지 않았음 |
| 인증 초기화 | `AccountAuthConfiguration`은 signing 설정 전부 비어 있으면 AUTH_UNAVAILABLE, 일부만 있으면 시작 거부. #109는 신규 HOST 생성/명령에 계정 요구 | Google/Kakao 공개 앱 설정 완료 인계 유지. 남은 서버 자격 증명 전달·RS256/검증 JWKS·정확 runtime callback/Origin·실로그인 미확인. 익명 HOST의 명시 claim도 로그인 준비 필요 |
| 운영 인증 전달 | 후속 renderer는 typed AUTH/Google/Kakao·optional AI OFF flag를 원자 전달하며 Compose는 UID10001의 read-only RSA/JWKS bind·missing path 자동생성 금지를 적용한다. auth marker/renderer/compose를 current17 승인 지문에 포함 | 코드·합성54/관련 회귀 검증과 실제 외부 준비는 별개. 정확 후속 HEAD CI·운영 파라미터/파일/권한·실로그인 확인 필요. Secret 읽기/생성/입력0 |
| 웹 main 배포 | `.github/workflows/deploy.yml`은 main push→production Environment→S3/CloudFront | 대응 서버가 새 인증·proof·check protocol을 지원하고 실제 준비됐다는 증거가 먼저 필요. UI를 먼저 배포해 로그인/미지원 API를 강요하지 않음 |
| 새 개인 AI 검사 | 후속 production bean은 `MEETME_PRECHECK_PROVIDER_ENABLED=false` 기본 바인딩·고정 GenerateContent3.8/LOW/2048·count4096 gate/사후 usage 방어를 제공한다. key/임의 verified flag만으로 켜지지 않는다. OFF에서는 CONFIGURATION·count/generation0이며 신규 방 확인을 완료할 수 없다 | **main 사용 차단 조건 유지**. activation/input gate 코드 준비와 실제 지원·billed input cap/청구·모임 총10원 정책 결정은 별개. 실제 opt-in/유료 호출 미승인, 합성 성공이 해소하지 않는다. 조건부 비용 산식을 보장으로 사용하지 않으며 실제 OFF 유지 |
| 복구 | ADR-046/051/052 및 release guard는 구 writer 혼합·잘못된 image rollback을 허용하지 않음 | 신규 schema/protocol 작성 후 구 이미지 rollback 가능하다고 주장하지 않음. 정확 호환 image와 roll-forward·복구 절차를 검증하고 운영 전환 별도 승인 |

<a id="history-issue-107-main-readiness--안전한-확인실행-순서"></a>

## 안전한 확인·실행 순서

1. 통합 코드의 독립 TDD/PG/proof/synthetic provider/browser·결함 주입·전체 gate·독립 최종 리뷰와 exact HEAD CI.
2. 서버 develop의 승인된 단위를 통합하고 exact merge SHA CI 확인. 원격 개발 URL이 없는 현 상태는 별도 알림.
3. 아침에는 새 통합의 정확 SHA/JAR·웹 자산 hash, 격리 PG/Redis, loopback OIDC+합성 RSA/provider, 외부 요청 차단과 합성 계정으로 시나리오 재현. 문서의 운영 URL을 개발 URL로 사용하지 않음.
4. main 전 필요한 외부 조치는 이름·존재/상태·승인만 확인하는 별도 체크로 묶어 부모에게 보고. 비밀값을 채팅/저장소/로그에 요청하거나 출력하지 않음. 미승인 설정을 우회한 임시 로그인·익명 HOST fallback도 추가하지 않음.
5. 위 조건이 충족된 조합만 서버 호환성/데이터 전환을 먼저 확인하고 웹 main을 이어 처리. 실제 배포 완료는 정확 커밋/image·workflow와 실행 증거가 있을 때만 기록.

현재 운영 endpoint·AWS/SSM·DB·Secret은 호출하지 않았다. 별도 staging/develop 서버 주소·현재 실행 SHA는 읽은 저장소 자료만으로 확인되지 않았다. 현재 격리 native API18920/web18921/control18922를 준비했다. 샌드박스 내부 주소이며 공개 staging이 아니다. 상세 `ISSUE_107_LOCAL_SCENARIO.md`.

</details>

<a id="history-issue-107-real-model-evaluation-plan"></a>

<details>
<summary>ISSUE_107_REAL_MODEL_EVALUATION_PLAN.md</summary>

원본: `docs/ISSUE_107_REAL_MODEL_EVALUATION_PLAN.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_107_REAL_MODEL_EVALUATION_PLAN.md`

<a id="history-issue-107-real-model-evaluation-plan--107-실제-모델-평가-준비안"></a>

# #107 실제 모델 평가 준비안

> 2026-10-17 단일 Gemini 검사 평가 준비 이력이다. 2026-10-18 채택된 Decisions→필요시 생성 보완의 최신 계획은 [독립 평가 기준](ISSUE_107_DECISION_EVALUATION.md)과 [외부 평가/키 경계](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md)를 따른다. 아래30회와 조건부 산식을 새2단계 평가의 승인/최대 비용으로 사용하지 않는다.

2026-10-17 UTC. **계획이며 실행 승인이 아니다. 실제 호출0, 측정 결과 없음.** 합성 provider 검증은 의미 정확도·실제 API 지원·실청구를 증명하지 않는다.

<a id="history-issue-107-real-model-evaluation-plan--현재-활성화-상태와-공식-근거"></a>

## 현재 활성화 상태와 공식 근거

초기 조사에서는 production opt-in 바인딩이 없었다. 후속 준비 코드의 `PrecheckProviderConfiguration`은 실제 `meetme.precheck.provider.enabled` 바인딩과 `MEETME_PRECHECK_PROVIDER_ENABLED`(기본 false)를 사용한다. runtime factory는 문서 기반 고정 profile만 사용하며 OFF이면 키나 임의 verified 환경변수가 있어도 count/generation0·CONFIGURATION이다. 기존 생성자 options의 verified boolean은 literal loopback HTTP 합성 compatibility 경로에만 남고 외부 호출에는 사용할 수 없다. 실제 운영 opt-in은 수행하지 않았다.

현재 고정 모델은 `gemini-3.8-flash`, API는 GenerateContent다. runtime 요청은 candidate1·`maxOutputTokens:2048`·`thinkingLevel:"LOW"`이며 thinkingBudget은 없다. 초기 합성 compatibility의 thinkingBudget0을 실제 모델의 완전한 thinking-off 근거로 사용하지 않는다. [GenerateContent 전용 thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens)도 thought 포함 infrastructure cutoff와 MAX_TOKENS 종료를 명시한다. 기존 “Interactions만 보장한다” 판단은 정정됐으며 guide/REST 필드 대응과 LOW 지원을 고정 profile의 공식 근거로 사용한다. 실제 요청 지원·청구·의미 정확도 검증과 구분한다.

기존 응답 usage 사후 거부, 15초 HTTP timeout, 로컬 취소는 이미 발생한 원격 계산·과금을 취소하지 않는다. raw500자·body16KiB도 billed input token 상한이 아니다.

<a id="history-issue-107-real-model-evaluation-plan--기본-off-활성화-코드독립-검증-계약-agent"></a>

## 기본 OFF 활성화 코드·독립 검증 계약 [AGENT]

후속 구현은 새61+기존18 focused GREEN으로 확인했다. 전체 gate·FI·최종 리뷰/CI는 Worklog의 실제 완료 기록을 따른다. 실제 모델/OFF 운영 설정을 변경한 결과는 아니다.

1. 기존 GenerateContent/고정 모델/2048 합산 cap/LOW만 허용하는 문서 기반 profile과 기본 OFF opt-in을 구현했다. production endpoint는 설정으로 노출하지 않는다. 합성 loopback options는 테스트 전용이며 실제 OFF·호출0을 유지한다.
2. OFF+key 존재의 outbound0, 잘못된 바인딩의 설정 실패, 고정 profile의 `thinkingLevel:"LOW"`·`maxOutputTokens:2048`·`candidateCount:1`/thinkingBudget 없음 wire를 독립 테스트했다. 이 **외부 요청 파라미터 계약 변경**은 독립61개 RED→기존18개 포함79개 GREEN으로 확인했으며 전역 Gemini 설정이나 다른 분석 요청은 바꾸지 않았다. 생성자 직접 호출뿐 아니라 production bean의 실제 환경 바인딩을 검증했다. ON 경로의 mock 성공을 실제 모델 검증이나 운영 활성화 완료로 표시하지 않는다.
3. thought+candidate cap, MAX_TOKENS/incomplete/empty/malformed 거부, 누락 usage=unknown, 과다 usage, timeout/body trickle/redirect/429/503/인증 오류를 독립 transport로 검증한다. 자동 retry/fallback0을 유지한다. OFF 무시·cap 누락·숨은 retry·truncation 성공·unknown=0의 결함을 주입·탐지하고 복구한다.
4. worker의 revision/generation/deadline/본인 소유권 fencing, 참가자별 admission6회·retry1회·질문3회·queue100/동시2·30초 실행/15초 provider·마감 정책은 바꾸지 않았다. 각 admission은 최대count1+generation1, 총 HTTP는 최대12로 구분하며 count 실패도 admission을 소진한다. `meetme.precheck.provider.http.attempts`의 bounded operation 태그 COUNT_TOKENS/GENERATE_CONTENT를 따로 기록한다. 실제 평가/비용 결정 전 production opt-in은 OFF다.
5. 전체 frozen GenerateContentRequest를 count1회로 계산하여 정수1..4096만 generation을 허용하고, known prompt usage>4096은 응답을 거부한다. 동일15초·자동 retry0·16KiB/500자 제한을 유지한다. known input/candidate/thought usage와 unknown을 성공·거부 응답 모두 별도 관측하며 count값을 실제 사용량으로 대체하지 않는다. [공식 count reference](https://ai.google.dev/api/tokens)의 예시도 count10/generation prompt11로 다르므로 **사전4096 제한+사후 방어는 실제 billed input hard cap 증명이 아니다.** count 비용·동등성은 여전히 미확인이다.

<a id="history-issue-107-real-model-evaluation-plan--30개-실제-평가-실행안-shared-승인-대기"></a>

## 30개 실제 평가 실행안 [SHARED, 승인 대기]

- [고정 fixture](fixtures/issue-107-pre-submit-evaluation.json) Q01~Q10/MUST_ASK10·N01~N20/MUST_NOT_ASK20을 그대로 사용한다. `source_created_at`, Asia/Seoul, ko-KR·검색 기간을 고정한다. **모델에는 Q/N ID·expected/reason/preserve를 보내지 않는다.** opaque UUID·raw/context만 전달하여 정답 누출을 막는다. 실제 개인/방 데이터는 사용하지 않는다.
- Google Gemini Developer API, pinned `gemini-3.8-flash`, GenerateContent Standard, candidate1. profile 준비 후 정확 thinking 파라미터·prompt/schema 지문을 고정한다. tools/search/maps/cache/grounding/fallback은 사용하지 않는다.
- **generation 최대30회, 사례당1회, 자동·수동 retry0, 동시성1.** canary·timeout·응답 유실·invalid 응답도 물리 전송 한 번으로 소진한다. 중단 후 재개/실패 재호출0. 별도 smoke 호출0. count를 쓰는 경우 사례당 최대1회·총30회, 전체 HTTP 최대60회로 별도 집계한다. provider 단독 격리 실행으로 답변 후 재검사·최종 whole-room 분석 등 추가 provider 호출0을 보장한다.
- billed input 목표 hard cap4096, thought 포함 billed output hard cap2048. 코드의 사전 count gate는 준비했지만 **실제 billed input 상한과 count 비용 근거가 미입증이므로 지금 승인 가능한 입증된 최대 USD는 없다.** [공식 Standard 가격](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash)은 조회일 기준 input0.75/output3.75 USD/1M(thought 포함), 2026-12-31까지다. 두 billed hard cap·count 비용0을 입증한 뒤에만 `30 × (4096 × 0.75 + 2048 × 3.75) / 1,000,000 = USD0.32256`가 generation token 요금의 조건부 상한이다. 현재 보장/호출 허가가 아니다. 세금·환율·인프라 제외. 승인 시 날짜·tier·통화 가격을 재확인한다.
- 실행 선행 조건: profile·input count/cap·실패 계약 독립 mock/리뷰/CI → 값 비출력 평가 환경 → 정확한 호출 수·입증한 비용 상한·중단 규칙 보고 → **별도 유료 실행 승인**. 미충족 시 호출0이다.

<a id="history-issue-107-real-model-evaluation-plan--중단-조건"></a>

### 중단 조건

인증/설정/tier/profile 불일치, 429·5xx, timeout·연결/응답 유실, cap/count 실패, 잘못된 스키마·완료 상태, usage 누락으로 잔여 비용 상한을 유지할 수 없는 경우 전체 run을 중단한다. 허용 호출수/예산 초과·추가 provider 호출·타인 정보 혼입 또는 A/B의 무관 조건·극성·날짜 변경도 중단하고 재호출하지 않는다. 단순 ASK/NOASK 오분류는 기록하여 나머지 독립 표본 평가를 계속할 수 있지만 배포 통과로 표시하지 않는다. 매 호출 전 최악 비용을 예약하며 unknown 비용을0원으로 합산하거나 timeout/취소 뒤 예약·호출 한도를 돌려주지 않는다.

<a id="history-issue-107-real-model-evaluation-plan--측정독립-판정"></a>

### 측정·독립 판정

| 구분 | 기록·계산 |
| --- | --- |
| 재현 | run ID·UTC 시각·source/fixture/prompt/schema SHA256·model/API/version/tier/cap·사례 ID/expected는 평가 기록에만 보관 |
| 호출·요금 | count/generation·물리 attempt·HTTP/finish status·timeout·known input/candidate/thought/billed total·unknown 항목·가격일/통화·부분 비용 합계/미확인 수 |
| 의미 | CLEAR/NEEDS_CHOICE/NEEDS_EDIT/technical failure/missing을 분리. ASK의 CLEAR=미탐, NOASK의 CHOICE/EDIT=오탐. ASK의 EDIT는 감지일 뿐 질문 품질 성공은 아님. EDIT 허용 여부는 기존 reason/preserve와 안전한 완전 A/B 가능 여부를 평가 전에 독립 채점자가 고정. 제안 전체 조건·극성·상대 날짜 보존과 명확한 내용 반복 질문을 독립 판정 |
| 분모 | ASK 완료/10·NOASK 완료/20·실패/미실행을 병기. 완료 표본 오탐/미탐률과 전체 계획 정답/30을 함께 보고. 제안 보존의 분모는 제안 생성 표본 수이며 A/B 모두 안전한 질문 단위의 통과 수를 별도로 보고 |
| 지연 | 이번 provider 단독 평가는 monotonic count·provider 전체 HTTP·harness elapsed를 실측. queue wait·저장→verdict는 N/A이며 별도 승인한 전체 경로 평가에서만 측정. 각 성공/실패·timeout과 n을 보존하고 nearest-rank `ceil(p*n)` p50/p95·최댓값 산출. missing을0초로 처리하지 않음 |

임시 보수적 gate 제안은 ASK 감지10/10·NOASK CLEAR20/20·계약/안전 실패0이다. 사용자 승인된 정확도 SLO가 아니며 30개 통과를 일반화 정확도로 주장하지 않는다. prompt 변경 후 재호출은 새 유료 계획/승인 대상이며 같은 표본 최적화를 독립 평가로 표시하지 않는다. p50/p95·정확도는 실행 후에만 기록한다.

<a id="history-issue-107-real-model-evaluation-plan--부모에게-남기는-정책-충돌"></a>

## 부모에게 남기는 정책 충돌

PRD의 **모임당 총 AI10원 미만**은 바꾸지 않았다. 2048 출력 상한만으로도 허용된 최악 출력 요금은 호출당 USD0.00768(기존 기본 환산1500이면11.52원)이며 입력·참가자당 최대6회·최종 whole-room 분석이 더해진다. 이는 실제/최소 비용이 아니라 상한 산식이다. `maximumKrwPerMeeting`은 관측 판정이며 precheck admission의 비용 차단이 아니다. 현재 한도로 총10원을 보장할 수 없으므로 **제품 비용 정책/허용 한도 충돌은 부모 결정 대상**이다. 별도 합성 평가의 소액 예산 승인은 제품10원 정책 변경 승인이 아니다. 제품 활성화 전 이 충돌을 해소해야 하며, 실제 평가 결과가 판단 자료가 될 수 있다. 임의로 PRD·질문/호출 cap·500자 제한을 바꾸지 않는다.

</details>

<a id="history-issue-107-usd1-evaluation-execution-plan"></a>

<details>
<summary>ISSUE_107_USD1_EVALUATION_EXECUTION_PLAN.md</summary>

원본: `docs/ISSUE_107_USD1_EVALUATION_EXECUTION_PLAN.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_107_USD1_EVALUATION_EXECUTION_PLAN.md`

<a id="history-issue-107-usd1-evaluation-execution-plan--107-합산-usd-1-평가-실행-계획-후보"></a>

# #107 합산 USD 1 평가 실행 계획 후보

<a id="history-issue-107-usd1-evaluation-execution-plan--최신-최소-smoke-검토-2026-10-18-utc"></a>

## 최신 최소 smoke 검토 (2026-10-18 UTC)

현재 최종 상태: 부모가 허용한 별도 후속1회를 포함한 실제2단계2시도는 생성에서 첫503·후속공통15초TIMEOUT으로 중단했다. 누계Decisions6/count2/generation시도2(성공0), D보고usage3085/0의 기본환산USD0.0003085/보수2.2배USD0.00067870이며 Gemini청구UNKNOWN이다. 누적예약USD0.70918144/추가여유USD0.29081856를유지하고 다음동일full견적USD0.31854592를더한USD1.02772736이승인USD1을초과하므로 추가dependent실제호출은중단한다. 아래503/5회문단과원래4회문단은각당시이력이고 [최종 실검증 기록](#history-issue-123-live-two-stage-validation)을따른다.

이후 별도 [실제2단계 검증](#history-issue-123-live-two-stage-validation)은 사전 oracle/plan 고정과 전체 guard/CODE GATE 통과 뒤 새A200 LOW_CONFIDENCE→count200/530→generationHTTP503에서 재시도 없이 중단했다. 누계Decisions5·count1·생성시도1이며 성공 생성 질문/usage 증거는 미확보다. 원래4회 FAIL/STOP은 보존한다. 알려진Decisions usage2573/0의 보수 환산USD0.00056606·Gemini청구UNKNOWN, 누적 예약USD0.39063552/추가 비용 여유USD0.60936448를 유지했다. 아래 첫 smoke 문단은 최초4회 평가의 결과이며 전체 현재 단계와 구분한다.

최신 실행 결과는 [#123 실제 Decisions 진단](#history-issue-123-live-decisions-diagnostic)을 따른다. 전체 context 견적 대신 고정2397~2479B/질문1에 BPE byte 상한과65,536token framing 여유·2.2배 단가를 적용했고 독립 sanity 검토 뒤 실제 Decisions4회를 실행했다. HTTP/production parser는4회 모두 통과했으나 오전/오후 사례가 rawCLEAR/confidence0.06으로 사전 기대와 달라 중단했고 마지막1개는 미호출이다. 운영 threshold·실제2단계/생성/최종 분석·invoice 검증 완료는 아니다. 아래0회/미실행/계산범위 대기는 이 실행 전의 이력이다. 기존USD1 합산과 세금/결제 여유는 유지하며 새 금액 승인을 요청하지 않는다.

아래 5개/최대15 HTTP는 이전 후보이며 현재 우선 범위는 짧은 새 합성 입력1·질문1·retry/fallback0의 격리 단일 추론이다. 전체 영속 원장 구현 완료를 이 독립 smoke의 선행 조건으로 삼지 않는다. 기존 합산 USD1 승인은 유지하며 금액 재승인을 요청하지 않는다.

기존 키의 OpenAI Luna/Gemini3.8Flash 모델 메타데이터 GET은 각각200이며 비밀값은 출력·저장하지 않았다. 조회 접근 확인과 실제 추론 endpoint 권한/계약 검증은 다르며 추론은 아직0회다. 새 계정·키·결제·운영 설정 변경은 없다.

Decisions는 공식 입력 전용 요금과 일반 Luna 전체 문맥/long·region 배수를 적용하면 USD0.231의 보수 quote가 가능하지만, 문맥 한도를 Decisions 청구 상한에 적용하는 부분은 추론이다. 전용 hard cap이 확인됐다고 표현하지 않는다. [별도 Gemini 공식 검토](verification/issue123-minimal-smoke-gemini-cost.md)는 count 없는 직접 GenerateContent1에 전체 입력 한도1,048,576·thought 포함 출력2048·Standard 요율을 적용해 token quote≤USD0.794112를 제시한다. 제품 count gate나 production DENY를 우회해 활성화한다는 뜻은 아니다.

count는 불명 과금을0으로 잡지 않고 단일 smoke에서 제외할 수 있다. 계정별 세금·환산/결제 수수료는 미확인이므로 USD1이 공급자 API 사용요금 기준인지 해당 부대비용 포함인지 확인 질문을 남겼다. 금액 증액 질문이 아니다. all-in 기준이면 공급자 세금/결제 조건에서 잔여 범위 내 비용 상한을 확인해야 한다. 기본 응답 shape와 실제 production parser의 offline 검증·usage·단일 지연 표본은 분리해 기록하고 작은 사례를 정확도/p50/p95 성과로 확대하지 않는다.

아래는 최소안으로 좁히기 전의 공식 근거와 이전 계획 이력이다.

2026-10-18 UTC. 기존 코드 기준 HEAD `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`; 병렬 작성 중인 새 구현의 검증 완료를 뜻하지 않는다. **현재 판정: 비용 증명 및 실제 권한 확인 전 BLOCKED, 모델 호출 0.**

최신 사용자 승인은 Gemini와 OpenAI를 합쳐 **평가 전체 USD 1 이하**다. Decisions·생성 보완·최종 분석·count/부속 HTTP·재시도/fallback·실패/응답 유실·세금/불확실성을 모두 포함한다. 공급자별 USD 1이나 run마다 새 USD 1이 아니다. 이 금액 승인을 제품 모임 예산·키/설정 변경·운영 활성화·배포 승인으로 확장하지 않는다. 기존 문서의 “금액 미승인”은 이 최신 평가 금액 승인으로 대체되지만 **옛 12개/최대 36 HTTP 제안의 실행 횟수·scope·비용 증명은 승인된 것이 아니다**.

[외부 평가 준비](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md), [독립 평가](ISSUE_107_DECISION_EVALUATION.md), [공유 예산 설계](ISSUE_107_SHARED_AI_BUDGET_DESIGN.md)를 먼저 대조했다. 이 문서만 작성하며 제품·test·fixture·다른 문서·Secret을 바꾸지 않는다. holdout 본문을 다시 읽거나 프롬프트 개발에 이용하지 않았다. 현재 실제 usage·청구·p50/p95·정확도 실측은 없다.

<a id="history-issue-107-usd1-evaluation-execution-plan--공식-계약-재확인"></a>

## 공식 계약 재확인

아래 공개 1차 출처는 2026-10-18 UTC에 직접 확인했다. 실제 계정의 model/API 접근·가격 계층·통화·납세/결제 상태는 확인하지 않았다. root가 기존 OPENAI_API_KEY/GEMINI_API_KEY의 configured 여부만 비밀값 비출력으로 확인했다고 전달했으며, 이 문서 작성자는 env 값을 읽지 않았다. 키 존재는 endpoint 권한·tier·세금·실행 성공의 증거가 아니다.

| 대상 | 확인한 계약 | 상한으로 전용할 수 없는 부분 |
| --- | --- | --- |
| [OpenAI Decisions guide](https://developers.openai.com/api/docs/guides/decisions) | `gpt-6-luna`, 입력 $0.10/M; 출력/cache read/cache write 요금 0. 지역 처리·long-context 입력 배수 적용. | 무료 출력은 usage 0이 아니다. 복수 질문의 공유 입력이 한 번만 과금된다는 청구식은 확인하지 못했다. 이번 후보는 choice 1개로 제한한다. |
| [Decisions create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create) | input과 questions 1..200, 현재 모델은 gpt-6-luna. request 집계 usage를 반환한다. | Decisions 전용 `max_input_tokens`/`max_output_tokens`는 확인되지 않았다. 문자열 길이·HTTP 16KiB·원문 500 codepoints는 billed input hard cap이 아니다. |
| [GPT-6 Luna model](https://developers.openai.com/api/docs/models/gpt-6-luna) | 일반 모델 context 1,050,000·max output 128,000. 272K 초과 입력 2배, 지역 +10%; 다른 API의 출력 단가는 $0.50/M. | 일반 context 한도를 Decisions 질문/instructions까지 포함한 최대 청구량 보장으로 옮기지 않는다. Decisions 출력 무료와 다른 API 가격도 섞지 않는다. |
| [Gemini 3.8 Flash pricing](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash) | Standard 입력 $0.75/M·출력(생각 포함) $3.75/M, 2026-12-31까지. 2027-01-01부터 각각 $1.50/$7.50. Priority/Batch/Flex는 별도 가격이다. | 할인/무료 quota/캐시 적중을 선차감하지 않는다. 날짜·계층이 바뀌면 quote를 폐기하고 재검토한다. 검색/Maps 등 별도 유료 tool을 사용하지 않는다. |
| [Gemini model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash), [GenerateContent](https://ai.google.dev/api/generate-content), [GenerateContent 전용 thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens) | 모델 입력 1,048,576·출력 65,536. 동일 API의 thinking guide는 max_output_tokens의 thoughts 포함 infrastructure hard cutoff와 MAX_TOKENS 종료를 명시한다. REST maxOutputTokens와 대응하는 공식 합산 출력 cap 근거가 있다. | “Interactions에서만 보장”이라는 최초 조사 판단은 정정됐다. LOW는 thinking OFF가 아니며 thinkingBudget0으로 우회하지 않는다. 공식 cap은 실제 계정의 profile 지원/usage/청구 실측 완료와 구분한다. |
| [Gemini tokens](https://ai.google.dev/gemini-api/docs/tokens), [billing FAQ](https://ai.google.dev/gemini-api/docs/billing#frequently-asked-questions) | countTokens REST 예제가 있으며 billing FAQ는 `GetTokens` API 무료와 400/500 token 무과금(쿼터 소진)을 명시한다. | `GetTokens` 무료 보장이 현재 wire의 `:countTokens`와 같다는 연결은 미확인이다. count를 무료로 가정하지 않는다. 특정 400/500 보장을 timeout/유실/다른 오류로 넓히지 않는다. |

현재 코드의 전체 generateContentRequest 사전 count≤4096·사후 usage 초과 거절과 2048 LOW는 방어 장치다. 동일 endpoint/model/profile의 thought 포함 출력 cap은 위 공식 근거가 있다. **전체 billed input 상한**과 count 요금·실제 계정 profile 지원/청구 확인이 남아 있다. [공식 count reference](https://ai.google.dev/api/tokens)의 예시도 count와 generation prompt usage가 달라 동등한 청구량으로 전용하지 않는다. 사후 결과 거부는 이미 발생한 청구를 취소하지 않는다.

<a id="history-issue-107-usd1-evaluation-execution-plan--ratespend-제한과-세금"></a>

## rate/spend 제한과 세금

- [OpenAI rate limits](https://developers.openai.com/api/docs/guides/rate-limits)는 조직/프로젝트·모델별 RPM/TPM 등을 구분한다. 일반 Luna Build 표의 RPM 5000·TPM 2M을 실제 Decisions 계정 할당량으로 단정하지 않는다. [spend limits](https://developers.openai.com/api/docs/guides/spend-limits)는 alert가 차단이 아니며 hard limit도 전파 지연으로 소폭 초과할 수 있다고 명시한다. USD 1 정확 상한 증명 대신 쓸 수 없다.
- [Gemini rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)는 프로젝트 단위 RPM/입력 TPM/RPD와 계정 조건부 spend rate를 구분한다. Tier 1 spend rate는 USD 10/10분이며 실제 할당량은 AI Studio에서 확인하도록 한다. 이 제한은 합산 평가 USD 1 한도가 아니다.
- [Gemini billing](https://ai.google.dev/gemini-api/docs/billing)의 선불 최소 충전은 USD 5이며 약 10분 billing 지연에 따른 초과 가능성도 있다. 예산 USD 1을 이유로 새 충전·자동 reload·한도/결제 방식 변경을 하지 않는다. 기존 prepaid 잔액이나 hard cap만으로 평가 최대 청구를 증명하지 않는다.
- [OpenAI Services Agreement §6.3](https://openai.com/policies/services-agreement/)의 요금은 세금 별도다. 실제 적용 세금·공급자/결제 수수료와 USD 환산 상한은 미확인이다. [Google billing currency](https://docs.cloud.google.com/billing/docs/resources/currency)는 계정 통화와 월별 환산을 설명한다. 저장소의 KRW/USD 관측 환율이나 임의 VAT 10%를 실제 합산 청구의 증명으로 쓰지 않는다.

<a id="history-issue-107-usd1-evaluation-execution-plan--작은-진단-pilot-제안-5개-물리-요청-최대-15"></a>

## 작은 진단 pilot 제안: 5개, 물리 요청 최대 15

이 횟수는 **검토 가능한 새 제안이며 실행 승인/실행 결과가 아니다**. development에서 NORMAL·CONTEXT_RESOLVED·AMBIGUOUS·ADVERSARIAL·UNINTERPRETABLE 각 1개를 `SHA256(raw_text UTF-8)` 오름차순으로 선택한다. subset/corpus/prompt/schema/profile/threshold와 정확 payload SHA를 실행 전에 고정한다. 여기서는 새 fixture나 subset 파일을 만들지 않는다. holdout은 사용하지 않는다. 5개는 진단이며 전체 차원 gate·운영 정확도 판정이 아니다.

| 호출 phase | 이 pilot의 최대 물리 요청 | 조건 |
| --- | ---: | --- |
| OpenAI Decisions | 5 | 사례당 holistic choice 1개, POST 1회. |
| Gemini countTokens | 5 | 정상 review/low-confidence가 2차 진입할 때 전체 frozen 생성 요청을 count. |
| Gemini generateContent | 5 | count 통과 후 1회; 전원 2차 진입을 최악으로 예약. 충분한 CLEAR/API 오류/refusal 뒤 강제 호출하지 않음. |
| 최종 방 분석·기존 primary retry·fallback·HOST retry·correction·DLQ | 0 | 초기 pilot에는 연결하지 않는다. 후속 평가 시 같은 USD 1 잔액에 별도 quote/호출 manifest를 추가해야 한다. |
| canary·smoke·재실행·SDK hidden retry·redirect·continuation·도구/검색·업로드 | 0 추가 | 첫 사례가 canary를 겸한다. 실패 사례 교체/재호출 없음. |

동시성 1, 사례당 공통 provider deadline 최대 15초, stage마다 새 15초를 지급하지 않는다. 기존 production 4+1 retry 정책을 수정하는 제안이 아니다. production scheduler/worker·전체 room 분석이 함께 도는 앱 대신 해당 고정 subset만 dispatch하는 격리 평가 경로가 필요하며, 그 runner 준비/검증은 아직 완료로 주장하지 않는다. 부속 model 조회/권한 smoke도 외부 요청을 임의 추가하지 않는다.

<a id="history-issue-107-usd1-evaluation-execution-plan--금액-산식과-현재-차단"></a>

## 금액 산식과 현재 차단

승인된 합산 한도는 `B = USD 1`이다. 계획용으로 직접 API 요금 quote 총합을 USD 0.10 이하로 제한하고 나머지 USD 0.90은 세금·수수료·환산·후속 평가를 위한 **미사용 headroom 후보**로 둔다. 이는 세금이 0.90 이하라는 증명이나 새 하위 예산 확정이 아니다. 실제 세금/부대비용의 보수 상한을 확인하지 못하면 headroom이 커 보여도 dispatch하지 않는다. 후속 평가가 0.90을 자동으로 지급받지도 않는다.

```text
전체 평가의 known settled + UNKNOWN worst-case holds
  + 새 물리 요청의 proven quote + 모든 세금/부대비용의 보수 상한 <= USD 1

pilot quote = 5 × Qdecisions + 5 × Qcount + 5 × Qgeneration
```

검토용 조건부 계산 예시는 다음과 같다. **4096 billed input·2048 billed total output·OpenAI 배수≤2.2가 모두 계약상 증명됐다고 가정할 때만**:

```text
Qdecisions = 4096 × $0.10 / 1M × 2.2                  = $0.00090112
Qgeneration = (4096 × $0.75 + 2048 × $3.75) / 1M      = $0.010752
5 × (Qdecisions + Qgeneration)                       = $0.05826560
pilot = $0.05826560 + 5 × Qcount + 세금/부대비용
```

이 예시는 산술이며 현재 최대 비용 견적이 아니다. Decisions의 전체 billed 입력 4096, count 요금, 실제 적용 배수/세금이 아직 미입증이다. GenerateContent thought 포함 출력 2048은 고정 profile에 대응한 공식 cap 근거를 사용하며 실청구 검증 완료로 표시하지 않는다. 실제 예상 usage나 가격만 곱해 최대 USD 1을 보장한다고 보고하지 않는다. 평균 2차 진입률·응답 성공률·캐시 할인은 산식에서 빼지 않는다. 아직 **실행 가능한 검증된 최대 비용 값은 없고, 지금 허용되는 물리 모델 요청은 0**이다.

<a id="history-issue-107-usd1-evaluation-execution-plan--실행-전-통과-조건과-중단"></a>

## 실행 전 통과 조건과 중단

1. 같은 API/model/profile의 전체 청구량 cap·count 요금·적용 가격/배수·세금/부대비용 상한을 근거와 함께 확정한다. 다른 API cap이나 무근거 verified flag를 쓰지 않는다. 부족하면 호출 0을 유지한다.
2. root가 필요 시 비밀값 비출력으로 기존 인증 configured 여부와 실제 endpoint/model 접근 준비 상태만 확인한다. 이 문서 작성자는 키/로그인/권한/계정 조회를 수행하지 않는다. 키 존재는 API 접근/유료 호출 성공의 증거가 아니다. 새 키·결제·한도·운영 Secret 변경은 별도 범위다.
3. root가 새 5개 subset/phase별 상한과 비용 증명을 검토하고 실행 scope를 확정한다. 사용자에게 USD 1 금액을 다시 승인받는 절차를 만들지 않되, 금액 승인만으로 이전 제안 횟수가 자동 확정됐다고 해석하지 않는다.
4. 격리 runner가 모든 provider/phase를 하나의 평가 reservation ledger에 원자 기록하고 재기동해도 누계를 보존하는지 합성 검증한다. 예약→dispatch gap, 중복 전달, timeout/crash·UNKNOWN hold를 검증하고 순차 dispatch한다. 현재 default DENY 제품 budget을 평가를 위해 우회/활성화하지 않는다.
5. 첫 사례 결과가 충분해도 단가·usage·cap·profile 불일치, 429/5xx/401/403, timeout/유실·잘림·malformed, 추가 phase/retry/정보 노출, 예상 외 청구/알 수 없는 비용이면 즉시 중단한다. 부분 지연이나 upstream 진행 가능성이 있는 실패 뒤 새 요청을 보내지 않는다. UNKNOWN은 최악 예약을 유지하고 재호출·hold 환불을 하지 않는다. 알려진 오류 무과금 근거가 있더라도 시도 횟수는 소진한다.
6. case/phase별 계획·전송·유효/실패·미실행, known/unknown usage와 비용·잔여 hold·실측 지연 표본 수를 보고한다. finite pilot의 모델 품질이나 p50/p95를 미리 채우지 않는다. 최종 방 분석/fallback의 미실행을 성공으로 표시하지 않는다.

현재는 1번 비용 증명부터 미통과다. mock/full gate·게시 준비는 이 차단과 독립적으로 계속할 수 있다. USD 1은 이번 합성 평가의 합산 승인으로만 보존하며 제품 모임 10원 요구·durable PG room 원장·전체 최종 분석 retry 제어·운영 활성화와 구분한다.

</details>

<a id="history-issue-109-review"></a>

<details>
<summary>ISSUE_109_REVIEW.md</summary>

원본: `docs/ISSUE_109_REVIEW.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_109_REVIEW.md`

<a id="history-issue-109-review--issue-109-독립-보안아키텍처-리뷰"></a>

# Issue #109 독립 보안·아키텍처 리뷰

상태: **APPROVED_IMPLEMENTATION_SCOPE — 서버·웹·V11 publication 호환성 구현 범위 승인**. 검토자는 `/root/final_review`이며 인증·소유권 런타임 구현에서 분리되어 있다. 서버 테스트 작성은 별도 설계자가 담당한다. 부모의 추가 위임으로 웹 API privacy 테스트 13개와 UI 테스트 3개(닫힌 게스트 방 연결, 기존 익명 HOST 로그인 gate, 이미 참여한 계정의 명시 연결 충돌)는 이 검토자가 독립 작성했으므로 해당 test oracle은 별도 설계자 `identity_db_tests`의 교차 검토를 요청했다. 부모의 별도 지시로 OpenAPI annotation만 수정한 두 Controller는 아래에 명시한다. 실제 운영 OAuth 로그인·배포는 이 리뷰의 검증 대상이 아니다.

<a id="history-issue-109-review--기준과-검토-경계"></a>

## 기준과 검토 경계

- 기준 revision: `3232128bb1d0886c987e378a787633bd2aee6341`.
- `AGENTS.md`, `.agents/rules`의 6개 규칙, `.agents/skills/project-architecture/SKILL.md`, PRD, Architecture, ADR, `ISSUE_109_CONTRACT.md`, `ISSUE_109_WORKLOG.md`를 기준으로 검토한다. 최신 사용자 결정이 과거 제출 MVP 익명 주최자 정책을 대체한다.
- 구현/독립 테스트 설계/RED/GREEN/결함 주입/전체 게이트와 별도로 소스 보안 경계를 확인한다. 아직 골격 상태인 구현을 최종 결함이나 합격으로 판정하지 않는다.
- `SubmissionService.kt`는 소유권 resolver와 참여자 판별의 변경만 허용하며, 마감 처리와 #110 관측 계층은 별도 세션 경계다.

<a id="history-issue-109-review--최종-확인-목록"></a>

## 최종 확인 목록

- [x] Google/Kakao code 교환의 고정 redirect URI·PKCE, 브라우저 결합 one-use state와 nonce, OIDC RS256/kid/서명/issuer/audience/azp/시간 검증.
- [x] 공급자/JWKS HTTP 장애와 거부 응답의 fail closed 및 토큰/코드/개인정보 비노출. 실제 공급자와 격리 대역 검증을 구별.
- [x] 서비스 Access RS256, 명시 kid와 이전 public key 회전, issuer/audience, 최대 900초, 정확한 만료와 미래 iat/nbf 거부. Redis 의존 없음.
- [x] Redis 실제 서버에서 원자 Refresh 회전·replay family 폐기·기기 격리·전체 로그아웃 선형화, idle 14일·absolute 30일과 사용된 digest 보존 TTL.
- [x] Refresh cookie scope·속성·Max-Age 및 정확한 Origin, HTTP 오류 상태와 응답 유출 방지. 무효 Authorization은 게스트 fallback 금지.
- [x] `(provider, subject)` 영구 계정 식별과 동시 가입, 이메일/이름 자동 연결 금지.
- [x] 방 생성과 모든 HOST 명령의 계정 인증, account 우선 소유권, 게스트 참여 유지·회원 이름 생략.
- [x] 명시 claim 이중 증명, 방 잠금과 DB XOR/unique, 동일 방 충돌 409, claim/join 경합, UUID/역할/제출 보존 및 이전 게스트 방 권한 회수.
- [x] 본인 HOST 아카이브의 IDOR 차단·최신순·정확한 30일 경계·closed_at 기준·기존 정리 정책 불변.
- [x] 실제 PostgreSQL migration/경합, 브라우저 통합, 생성 OpenAPI, 전체 서버/웹 게이트와 guard 증거.
- [x] 독립 frozen RED 지문과 최종 테스트 일치, 결함 주입 탐지 및 복구 후 GREEN; 아래 정정된 회귀 검증은 사전 RED로 집계하지 않음.

서버·웹의 실제 전체 게이트와 최종 지문 검사를 완료했다. 최초 typed fixture 오류와 구현 후 회귀 항목을 유효한 사전 RED와 구분했다. 이 구현 범위 승인은 실제 공급자·운영 활성화·실제 #108 adapter 결합 검증의 완료 판정이 아니다.

<a id="history-issue-109-review--초기-전달-사항"></a>

## 초기 전달 사항

- 인증 테스트 설계자에게 Access의 미래 `iat`/`nbf` 및 발급 수명 제한, OIDC 다중 audience의 `azp`, 사용된 Refresh digest의 absolute 만료까지 replay 탐지 검증을 전달했다. 테스트 설계자는 RED freeze 전에 관련 보강 중이다.
- 소유권 테스트 설계자에게 close 이외 HOST 명령의 계정 우선 검증, claim/join 경합 검증을 전달했다.
- guest HOST의 기록상 역할은 보존하되 명령 capability는 로그인된 계정 HOST에서만 활성화하도록 소유권 구현자와 합의했다. 게스트 자신의 제출 권한과 구분한다.
- 웹 리뷰에는 account 변경/로그아웃 뒤 지연 Refresh가 세션을 복원하는 경합, 이전 계정의 늦은 API 응답·query/mutation cache·원문 draft가 다음 계정에 노출되는 경계를 포함한다. 웹은 최신 원격 `main` 기준의 격리 로컬 feature 브랜치에서 진행하며, 이 저장소 서버의 `develop` 기준과 구분해 WORKLOG에 기록한다.
- 동시 신규 로그인과 전체 로그아웃은 선형화 순서에 따라 새 세션이 살아남을 수 있다. 신규 로그인 금지 정책을 추가하지 않으며, 먼저 완료된 family가 전체 로그아웃 뒤 인덱스 누락으로 살아남는 경우를 금지한다.
- 2026-10-17 공식 [Kakao discovery](https://kauth.kakao.com/.well-known/openid-configuration)에서 code flow·S256·RS256·issuer와 [Google discovery](https://accounts.google.com/.well-known/openid-configuration)를 조회했다. 이는 프로토콜 설정의 근거이며 실제 앱 설정이나 운영 로그인 성공의 근거가 아니다. Kakao의 [공식 REST 계약](https://developers.kakao.com/docs/en/kakaologin/rest-api)은 취소 시 `error`/`error_description` callback과 redirect URI 일치, nickname의 동의 조건을 별도로 명시한다.
- nginx access log는 `$uri`를 사용해 query를 제외한다. `error_log warn`의 upstream 장애 원본 request query 노출 가능성은 #110 경계로 부모에게 전달했으며, 운영 활성화 전 별도 검증이 필요하다.

최종 결과와 소스 지문은 `.tdd/reviews/issue-109-final-review.json`에 기록한다.

<a id="history-issue-109-review--중간-증거-확인"></a>

## 중간 증거 확인

- 인증 첫 RED는 컴파일 후 30개 중 29개가 예상한 `TDD TODO` 경계에서 실패했으며 원본 로그가 존재한다. 확인 당시 최초 5개 테스트 지문이 일치했다. 이후 formatter가 Access 테스트의 긴 문자열 표현을 바꾸므로 설계자가 해당 파일을 별도 RED 주기로 분리 중이다. 최종 active RED의 지문을 다시 확인해야 한다.
- Redis 장애의 범용 `assertFails` 테스트는 골격 `TODO`도 실패로 인정한 한계가 있다. 실제 장애 분류와 HTTP 503/no-leak 증거를 추가로 확인해야 한다.
- 독립 계정 영속 테스트는 provider+subject·표시 이름 Unicode 경계·실제 Spring transaction proxy·PostgreSQL insert fault rollback·12개 worker 최초 로그인 경합을 다룬다. 실제 Flyway 호환성은 별도 migration 테스트가 담당한다.
- 웹 API privacy 테스트 `src/api/client.account.test.ts` 12개를 구현 전에 작성하고 실행해 11개가 Bearer 누락·늦은 계정 응답 반환의 요구사항 실패임을 확인했다. AbortSignal 전달 1개는 기존 구현으로 통과했다. 별도 TypeScript 전체 typecheck도 통과했다. frozen RED/지문/설계는 웹 저장소 `.tdd/red/issue-109-web-api.json`, `.tdd/reviews/issue-109-web-api-test-design.json`에 기록했다.
- 웹 API 오류 JSON decode 중 계정 변경 1개를 별도 RED로 추가하고 독립 교차 검토를 받았다. 이후 Bearer 제거·모든 epoch 검사 제거·decode 뒤 epoch 검사 제거 3종에서 각각 7/5/2개 요구사항 실패를 확인하고 원본 바이트를 정확히 복구한 뒤 13/13 GREEN을 확인했다.
- 웹 shared cookie lock의 독립 4개 test oracle과 frozen 지문을 검토하고 `.tdd/reviews/issue-109-web-cookie-lock-cross-review.json`에 승인했다. 실제 여러 탭 브라우저 결과와 Web Locks 미지원 시 same-tab singleflight 한계는 별도로 확인한다.
- #108 공통 증명의 `VERIFIED_GUEST`/`REPLACE_ONLY`와 operation별 권한을 새 resolver에서 재검토 중이다. 계정 우선·proof room/participant binding·guest-owned row 재확인·claim의 강한 proof/잠금은 소스에서 확인했다. public room viewer와 기존 참여 반환 join도 proof 경계를 통과하도록 요청했고 별도 독립 RED를 진행한다. `SubmissionService.kt`는 resolver의 READ/REPLACE 구분 및 소유권 helper 변경만 유지하며 마감 로직은 바뀌지 않았다.
- 공통 proof 기본 40개는 독립 frozen RED→GREEN을 거쳤고, public viewer/join 4개는 구현 이후 추가한 독립 회귀 GREEN으로 정확히 구분한다. 원문 read/replace scope 혼동·약한 proof의 read/claim 승격·이미 계정 소유로 바뀐 row·claim lock 누락의 5개 결함은 실제 assertion failure를 확인하고 원본 SHA를 복구했다. 실제 #108 비밀번호/request-proof adapter는 이 격리 작업 트리에 없으므로, 해당 adapter와 합친 검증을 완료했다고 주장하지 않는다.

<a id="history-issue-109-review--테스트-기록의-정정"></a>

## 테스트 기록의 정정

이 검토자가 작성한 `ClaimConflictView.test.tsx`의 첫 fixture는 생성된 `Viewer.context_id` 대신 존재하지 않는 `participant_id`를 사용했다. Vitest가 TypeScript를 변환해 런타임 실패를 보여주었지만 전체 typecheck에서 fixture 오류가 확인되었으므로 첫 RED를 유효한 TDD로 인정하지 않는다. 원래 기록은 웹 `.tdd/history/issue-109-web-claim-conflict-invalid-typed-fixture/original-red.json`에 INVALIDATED 상태로 보존했다.

수정된 테스트는 공개 opaque `context_id` hint를 인증 증명으로 사용하지 않고 conflict 뒤 동일하게 보존되는지만 확인한다. 실제 접힌 disclosure를 연 뒤 사용자가 claim 버튼을 누르도록 보강했다. 수정 후 전체 TypeScript typecheck와 대상 테스트 GREEN을 확인했고 `.tdd/verification/issue-109-web-claim-conflict.json`에 **구현 이후 수정된 독립 회귀 GREEN**으로 기록했다. 이 정정에 프로덕션 RoomPage를 임시 변경하지 않았으며, 사전 TDD 실행으로 보고하지 않는다.

<a id="history-issue-109-review--openapi-문서-보완-범위"></a>

## OpenAPI 문서 보완 범위

부모의 명시적 지시에 따라 이 검토자가 `RoomLifecycleController.kt`의 GET room/POST participants 403 응답을 `ApiProblemSchema`로 문서화했다. `RoomOwnershipController.kt`의 기존 오류 schema가 이미 명시된 점을 확인·유지하고, claim 403 설명과 claim/archive의 인증 검증 키 사용 불가 503 schema를 보완했다. Controller 동작·인증·DB·보존 정책은 수정하지 않았다. 생성 OpenAPI의 재조회와 웹 schema 동기화는 최종 게이트에서 확인한다.

<a id="history-issue-109-review--구현-중-발견사항"></a>

## 구현 중 발견사항

<a id="history-issue-109-review--r109-01--공급자-http-body-읽기-전체-제한-누락-p1-수정최종-게이트-통과"></a>

### R109-01 — 공급자 HTTP body 읽기 전체 제한 누락 (P1, 수정·최종 게이트 통과)

`OidcOAuthProviderAdapter.read`는 `HttpRequest.timeout`과 `BodyHandlers.ofInputStream()` 뒤 `readNBytes`를 사용했다. 이 timeout은 응답 헤더 이후의 blocking body 소비를 제한하지 못한다. 외부 공급자/JWKS 연결이 헤더를 보내고 멈추면 callback thread가 설정한 5초 이후에도 대기할 수 있다.

독립 격리 JDK probe `/tmp/Issue109HttpBodyTimeoutProbe.java`에서 300ms request timeout, 즉시 헤더, 2200ms 뒤 body의 다음 byte를 보내는 조건을 실행한 결과 body 읽기는 2528ms 후에 완료했다. 실제 공급자/비밀값은 사용하지 않았다. 인증 구현자와 독립 테스트 설계자에게 byte 수 제한과 함께 body 전체 deadline을 보장하고 신규 RED→GREEN으로 검증하도록 전달했다.

수정은 전체 body를 완료하는 64KiB 제한 Flow subscriber와 공유 전체 exchange 예산 안의 비동기 future 대기·timeout 취소를 사용한다. 독립 token/JWKS body-stall RED 2개 뒤 대상 GREEN이 보고되었고, 소스의 헤더 이후 body deadline 및 용량 제한을 확인했다. 전체 게이트의 최종 소스 지문과 다시 대조한다.

<a id="history-issue-109-review--r109-03--닫힌-기존-게스트-방의-명시-연결-ui-없음-p1-수정최종-게이트-통과"></a>

### R109-03 — 닫힌 기존 게스트 방의 명시 연결 UI 없음 (P1, 수정·최종 게이트 통과)

로그인 뒤 계정 우선 원칙에 따라 기존 게스트 HOST 방의 계정 viewer는 아직 참여하지 않은 상태다. 이때 `RoomPage`는 수집 중인 방에만 명시 연결 버튼을 제공했고, 닫힌 방에는 마감 안내만 보여 연결과 아카이브 귀속을 완료할 수 없었다. 서버의 claim은 닫힌 방도 지원하므로 보존 중인 기존 방의 연결 UI가 필요하다.

독립 `src/auth/ClosedGuestClaim.test.tsx`는 full App에서 로그인 계정·닫힌 방·미연결 상태로 시작해 자동 claim과 신규 참여를 금지하고, 사용자 클릭에 의한 claim→기존 HOST 표시→본인 아카이브 링크와 만료 표시를 검증한다. 명시 연결 버튼이 없다는 요구사항 실패를 먼저 확인했다. 웹 `.tdd/red/issue-109-web-closed-claim.json`에 지문과 RED를 기록했다.

수정 후 닫힌 방의 신규 참여 금지를 유지하면서 로그인 계정의 명시 연결 버튼을 제공한다. 동일 frozen 테스트를 이 검토자가 재실행해 1/1 GREEN을 확인했다. 컴포넌트·라우터 통합 결과이며 실제 브라우저·격리 서버 결과와 구분한다. 웹 `.tdd/verification/issue-109-web-closed-claim.json`에 기록했다.

<a id="history-issue-109-review--r109-04--인증-결함-주입의-stale-xml-오판-p1-검증-증거-수정된-재검증-확인-완료"></a>

### R109-04 — 인증 결함 주입의 stale XML 오판 (P1 검증 증거, 수정된 재검증 확인 완료)

첫 인증 fault report는 7개 모두 탐지되었다고 기록했으나, 이 검토자가 원본 로그를 직접 대조해 뒤 4개(replay family·absolute cap·provider nonce·Origin prefix)가 `:compileTestKotlin FAILED`임을 확인했다. 보고서에 적힌 OAuth browser test 실패는 이전 실행의 stale XML이어서 이 4개를 결함 탐지 증거로 인정할 수 없다. 앞 3개(Access signature·Access expiry·OAuth browser)는 실제 target assertion failure를 확인했다.

테스트 설계자가 오류를 인정하고 기존 evidence를 history에 보존·INVALIDATED 처리한 뒤, 성공한 컴파일과 해당 실행에서 새로 생성된 target suite/test 결과를 검사해 7개 모두 재실행하기로 했다. 복구 후 auth 전체 GREEN도 별도로 확인한다. 다른 소유권 7개와 계정 영속 2개 fault 원본 로그는 target test 단계의 실패로 독립 확인했다.

수정된 7개 원본 로그를 이 검토자가 다시 읽어 컴파일 오류 없이 실제 target `:test FAILED`와 명시적인 assertion failure를 확인했다. replay 2개, 나머지 각 1개의 적절한 실패로 총 8개 assertion이 결함을 탐지했다. 복구된 인증 소스 5개의 SHA가 현재 파일과 일치한다. 복구 후 별도 auth 45개가 실패·오류·skip 없이 GREEN이며 `.tdd/verification/issue-109-auth-green.json`의 `finalRestoredVerification`에 기록되었다. 이 auth 한정 실행은 모든 프로덕션을 컴파일하지만 test compilation을 account 디렉터리로 제한한 로컬 init을 사용했으므로, 변경하지 않은 저장소 전체 gate 결과와 구분한다.

<a id="history-issue-109-review--r109-05--kakao-요청-scope-구분자가-공식-계약과-다름-p1-수정최종-게이트-통과"></a>

### R109-05 — Kakao 요청 scope 구분자가 공식 계약과 다름 (P1, 수정·최종 게이트 통과)

production Kakao endpoint의 scope는 `openid profile_nickname`으로 설정되었다. [Kakao 공식 REST 문서](https://developers.kakao.com/docs/en/kakaologin/rest-api)의 authorization 요청은 복수 scope ID를 쉼표로 구분하며, 응답의 scope 문자열만 공백으로 구분한다고 명시한다. Google의 공백 구분과 공급자별 요청 차이를 구분해야 한다.

현재 공통 provider 테스트 대역은 Google/Kakao 모두 임의의 `openid profile` endpoint를 주입하므로 production 기본값의 공급자별 인코딩 차이를 검증하지 못한다. 실제 앱·비밀값 없이 production 기본 authorization URL을 검증하는 독립 테스트를 추가하도록 요청했다. 실제 Kakao에서 거부 응답을 확인한 상태는 아니며, 공식 문서와 불일치하는 요청이 운영 연동을 막을 수 있다는 판단이다.

production 기본 URL의 별도 독립 테스트 2개에서 Google은 통과하고 Kakao만 먼저 실패했다. Kakao 상수만 `openid,profile_nickname`으로 수정한 뒤 production URL·기존 OIDC·body deadline 총 8개가 GREEN이다. 이 검토자가 상수를 원래 값으로 되돌린 바이트의 SHA를 계산하여 이전 7개 fault 검증 소스와 정확히 같음을 확인했다. 이전 fault를 새 소스에서 재실행한 것으로 포장하지 않으며 `.tdd/verification/issue-109-auth-provider-scope.json`에 이 변경 관계를 기록했다.

<a id="history-issue-109-review--r109-06--만료된-웹-계정이-게스트-권한으로-요청할-수-있음-p1-부모-발견수정최종-게이트-통과"></a>

### R109-06 — 만료된 웹 계정이 게스트 권한으로 요청할 수 있음 (P1, 부모 발견·수정·최종 게이트 통과)

부모가 추가로 확인한 경계다. `getSession()`에는 만료된 계정이 남아 있지만 `getAccessToken()`만 null을 반환하면 API는 Bearer 없이 유효한 기존 guest cookie를 보낸다. 계정 UI에서 수행한 요청이 별개의 게스트 참여를 읽거나 교체할 수 있으므로, background 탭에서 자동 refresh timer가 늦어져도 이 fallback을 막아야 한다.

명시적으로 로그아웃한 뒤의 정상 guest 사용과 구분해, 계정 세션이 존재하나 만료되었으면 API가 인증 오류를 반환하고 요청 자체를 보내지 않는 공개 계약 테스트를 별도로 추가한다. 현재 API의 mocked session 테스트만으로는 실제 session module의 이 경계를 검증하지 못하므로 real module과 결합한 독립 RED를 진행한다.

독립 real-module 테스트 3개의 RED 뒤 strict token 검증과 요청 `cache: no-store`를 추가했다. strict 검증 제거는 GET/PUT 2개, cache 제거는 1개의 실제 assertion 실패를 일으켰고 정확한 원본 복구 후 3개 GREEN이다. 이 검토자가 원본 JSON과 현재 source SHA를 대조하고, 새 strict/cache 블록만 제거하면 이전 API Bearer/epoch fault 소스 SHA가 정확히 복원됨도 확인했다. 기본 getter·정상 게스트 계약은 유지하며 background timer 실행을 보안 전제로 삼지 않는다. 증거는 웹 `.tdd/verification/issue-109-web-expired-session.json`이다.

<a id="history-issue-109-review--r109-07--서버가-무시하는-주최자-이름을-보내-정상-unicode-프로필이-거부될-수-있음-p2-수정전체-웹-게이트-및-native-통과"></a>

### R109-07 — 서버가 무시하는 주최자 이름을 보내 정상 Unicode 프로필이 거부될 수 있음 (P2, 수정·전체 웹 게이트 및 native 통과)

계정 이름과 Participant 이름은 1~50 Unicode code point를 허용하고 Zod4도 50개 emoji를 통과시킨다. 그러나 웹은 이를 무시되는 호환 필드 `host_display_name`으로 다시 보내며, 서버의 해당 DTO에는 UTF-16 길이 기준 `@Size(max=50)`가 남아 있다. 따라서 회원이 이름을 다시 입력하지 않아도 되는 계약과 달리 정상 프로필로 방 생성을 완료하지 못할 수 있다.

이 검토자가 설치된 Hibernate Validator 9.1.3의 `SizeValidatorForCharSequence` bytecode에서 `CharSequence.length()` 사용을 확인했고, JavaScript 길이 100인 50개 emoji가 실제 Zod4에서 통과함도 독립 probe로 확인했다. 부모는 서버·보존 정책을 변경하지 않고 웹 요청에서 사용하지 않는 `host_display_name`을 생략하는 좁은 수정을 선택했다. 독립 RED와 수정 후 전체 웹 게이트를 별도로 진행한다.

<a id="history-issue-109-review--서버-최종-판정-2026-10-17-utc"></a>

## 서버 최종 판정 (2026-10-17 UTC)

서버 구현 범위를 승인한다. 변경하지 않은 `sh ./gradlew --no-daemon ktlintCheck assemble test`가 종료 코드 0이며, 보관된 JUnit 130개 suite를 직접 합산해 전체 773개 중 769개 통과, 기존 의도적 skip 4개, 실패·오류 0개를 확인했다. 최종 게이트의 코드·설정·테스트 297개 SHA가 현재 파일과 모두 같고, active 독립 RED의 테스트 지문 16개도 일치한다. 원래 `tdd_guard.py`가 HEAD와 같고 guard 종료 코드 0, 별도 guard self-test 12개 통과를 확인했다. 증거는 `.codex/tdd-evidence/issue-109-final-gate/`에 있다.

R109-01과 R109-05는 수정된 최종 서버 소스에서 전체 게이트를 통과했다. 서버 실제 PostgreSQL/Redis 경합·보안·migration 검증과 생성 OpenAPI schema 일치를 확인했다. 실제 공급자 로그인, 실제 #108 proof adapter와 합친 통합 검증, 배포·운영 DB 변경은 이 승인에 포함하지 않는다. 웹 R109-07의 수정 뒤 전체 웹 게이트·native 브라우저 지문 대조가 남아 있으므로 서버 승인을 전체 목표 완료로 해석하지 않는다.

<a id="history-issue-109-review--r109-08--신규-v11과-release-publication-카탈로그-불일치-p1-부모-발견수정원본-ci-추가-게이트-통과"></a>

### R109-08 — 신규 V11과 release publication 카탈로그 불일치 (P1, 부모 발견·수정·원본 CI 추가 게이트 통과)

부모의 push 전 원본 CI 추가 검사에서 immutable release pipeline이 정확히 V1~V10만 허용하는 경계를 발견했다. V11이 추가된 소스는 publication manifest를 만들 수 없으며 host 승인 record도 V10만 허용한다. 서버 런타임 게이트 승인과 이 새 파이프라인 변경의 publication readiness를 분리한다.

검토한 좁은 수정 경계는 신규 build가 정확 V1~V11을 생성하고, 기존 immutable V10 manifest/record와 신규 V11만 명시적으로 허용하는 것이다. actual Flyway checksum, source/CI identity, guard/source fingerprint, archive 검증과 연속 history 검증은 유지해야 한다. V10 host에서 V11은 자동 승인되지 않으며 기존 명시적 first-transition 승인과 두 refresh rule의 DISABLED 조건을 그대로 요구한다. 실제 배포나 승인 정책의 우회는 허용하지 않는다. 독립 RED·결함 주입·원본 offline CI 추가 게이트가 남아 있다.

<a id="history-issue-109-review--웹-최종-판정-2026-10-17-utc"></a>

## 웹 최종 판정 (2026-10-17 UTC)

R109-07까지 반영한 웹 구현 범위를 승인한다. 원본 로그에서 단위 29개 파일·364개 테스트 통과, 브라우저 384개 예정 중 383개 통과·기존 의도적 desktop mobile-layout 스킵 1개·실패 0개를 확인했다. 전체 lint·typecheck/build·생성 API 동기화 검사도 통과했다. `docs/verification/issue-109-web-final-manifest.json`의 최종 소스·빌드입력·API·브라우저 fixture·실행 번들 53개 SHA를 직접 대조해 모두 일치하며 active RED 테스트 지문 8개도 일치한다. 이전 경계의 이력 SHA는 현 실행 번들의 지문으로 사용하지 않았다.

R109-07 독립 2개 테스트는 정상 이름과 50 Unicode emoji에서 무시되는 host 이름 필드를 완전히 생략하고 나머지 정확한 방 설정을 유지한다. 필드를 복구한 결함은 두 assertion 모두 탐지하고 원본 복구 뒤 GREEN이다. 기존 3개 serialization 기대값 변경은 새 계정 권위 계약의 명시적 supersession으로 남기며 Unicode validation 8개와 일반 게스트 흐름을 유지한다.

최신 native browser는 15:26:14 UTC에 12개 통과했다. 격리 실제 Spring/PostgreSQL/Redis와 loopback HTTP OIDC/JWKS를 거치며 API route mock과 외부 요청은 0이다. provenance의 서버 main 소스 156개·컴파일된 main class 605개·웹 소스 30개 SHA가 현재 파일과 일치한다. production 번들의 전체 브라우저 fixture suite와 실제 프로토콜 native suite는 서로 다른 증거로 구분한다. 실제 공급자 로그인은 여전히 미검증이다.

R109-08 수정은 세 Python 파일의 명시 카탈로그 개수·범위만 바꾸고 기존 identity/checksum/fingerprint/transition 검사를 유지했다. 신규 독립 9개와 기존 7개가 통과했다. publisher가 V11 누락을 허용하는 결함, 자동 승인에서 history/schema/guard 비교를 제거하는 결함, host parser가 V12를 허용하는 결함의 원본 TAP을 직접 읽어 각각 1/2/1개의 실제 assertion 실패를 확인했다. 세 소스가 정확 SHA로 복원되고 신규 9개가 모두 다시 통과했다. 원본 CI 추가 게이트의 최종 결과를 기다린다.

<a id="history-issue-109-review--최종-독립-판정"></a>

## 최종 독립 판정

**APPROVED_IMPLEMENTATION_SCOPE**. 남은 구현 결함이나 로컬 필수 게이트 실패는 없다. 기존 원본 CI 추가 검사 최종 실행은 deployment policy 13, preflight 21, release contract 16, runtime provider 19, built-image workflow 12, issue-links 9개와 Compose·DB refresh·Nginx shell 검사가 모두 통과했다. 변경하지 않은 V8 guard 37개는 동일한 15초 내부 제한을 유지한 단독 실행에서 359.672초에 전부 통과했다. 최초 환경·동시 실행 실패를 지우거나 최초 통과로 보고하지 않았고 `.tdd/reviews/issue-109-ci-extras.json`에 그대로 보존했다.

최종 Kotlin 게이트의 소스와 추가 publication/CI 소스 총 304개 SHA, active #109 RED 테스트 지문 17개를 현재 파일과 직접 다시 대조했다. post-catalog guard도 원래 hook SHA를 유지하여 통과했으며 Python-only 변경 뒤 기존 Kotlin gate의 up-to-date 재사용을 새 실행으로 집계하지 않았다. 원래 V1~V10 migration SQL, 별도 마감 처리, #110 관측 소스는 수정하지 않았다.

이 판정은 별도 사용자 승인된 commit/push/Draft PR 진행을 위한 구현 범위 리뷰다. 부모가 정확한 server PR head의 원격 CI 및 실제 ARM64 image 검증을 이어서 담당한다. 실제 Google/Kakao 로그인·운영 callback/cookie/key 검증, 실제 #108 proof adapter와 합친 통합, #110 callback query error-log 비노출은 남은 외부 검증이다. 실제 운영 로그인 성공·병합·배포·운영 DB 변경을 승인하거나 완료했다고 표시하지 않는다.

</details>

<a id="history-issue-123-fresh-head-two-stage-validation"></a>

<details>
<summary>ISSUE_123_FRESH_HEAD_TWO_STAGE_VALIDATION.md</summary>

원본: `docs/ISSUE_123_FRESH_HEAD_TWO_STAGE_VALIDATION.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_123_FRESH_HEAD_TWO_STAGE_VALIDATION.md`

<a id="history-issue-123-fresh-head-two-stage-validation--123-수정-head-fresh-15초-단일-사례-검증"></a>

# #123 수정 HEAD fresh 15초 단일 사례 검증

2026-10-18 UTC. 평가한 코드는 `4101038fd421351affa2a1fcdd5741083cb1a6b6`, tree `1d8c38eea7c273727834c487dacc214e4c668fbd`다. 이후 기록 전용 커밋과 구분한다. 부모가 기존 Gemini+OpenAI 합산 USD2 안에서 추가 승인한 사전 고정 LIVE-KO-03 한 건만 실행했고 이후 유료 호출을 중단했다.

<a id="history-issue-123-fresh-head-two-stage-validation--실행-조건과-결과"></a>

## 실행 조건과 결과

입력은 `10월 24일 토요일 8시부터 9시까지 가능해요.`다. 기존 oracle·reference/Asia-Seoul 문맥·500자·LOW/2048·15초·fixture threshold0.8은 그대로 유지했다. 새 [독립 실행 mode](evaluation/issue123-fresh-head-two-stage-oracle-mode.json), [비용 사전 리뷰](verification/issue123-fresh-head-two-stage-cost-review.md), [whole-CLI mock25/25](../.tdd/verification/issue123-fresh-head-runner-mock.json), [실행 전 ACCEPTED](verification/issue123-fresh-head-two-stage-preexecution-review.md) 뒤 root가 단1회 실행했다. callback의 만료 후 open 가능성은 사전 독립 P2로 발견해 최소 수정했고 동일 재현에서 HTTP0을 확인했다. 최초 실패와 fixture 준비 오류는 별도로 보존했다.

| 단계 | 관측 | HTTP 지연 | usage |
|---|---|---:|---|
| Decisions | HTTP200, raw CLEAR/confidence0.06 → LOW_CONFIDENCE | 653.398ms | 입력512/출력0 |
| count | HTTP200, 530 | 228.058ms | 청구 usage 미확인 |
| generation | HTTP200, NEEDS_CHOICE | 5934.856ms | 입력530/출력660(127 candidate+533 thought) |

HTTP 지연은 각 callback의 request open부터 body 수집까지이며 Java callback 전체 시간과 구분한다. 실제 production parser와 TwoStage를 거친 단일 JVM monotonic 경로는 **7029.248374ms**로 공통15초 안에 끝났다. JVM 시작·종료를 포함한 전체 실행은7641.481ms다. A/count/generation은 각1회이며 자동retry/fallback/redirect는0이다. 정확 request/response SHA 및 monotonic 시작·cutoff·종료값은 [관측 기록](../.tdd/verification/issue123-fresh-head-two-stage-observed.json)에 있다.

질문은 `'8시부터 9시까지'가 오전인지 오후인지 선택해주세요.`이고 선택지는 다음과 같다.

- 오전: `10월 24일 토요일 오전 8시부터 9시까지 가능해요.`
- 오후: `10월 24일 토요일 오후 8시부터 9시까지 가능해요.`

raw A의 CLEAR는 이 사례의 사전 NEEDS_REVIEW 의미 기대와 계속 불일치한다. confidence0.06을 정답률6%나 CLEAR확률6%로 해석하지 않는다. 정상화는 기존 합성 fixture0.8을 적용한 검증 조건이며 운영 임계값을 선택하거나 활성화한 결과가 아니다. confidence/분류 정책을 이번 응답에 맞춰 조정하지 않았다.

<a id="history-issue-123-fresh-head-two-stage-validation--비용과-해석-범위"></a>

## 비용과 해석 범위

기존 UNKNOWN을 포함한 예약USD1.01330944에 새 최대USD0.31854592를 실행 전에 더해 누적USD1.33185536, USD2 내 잔여여유USD0.66814464를 유지했다. 기존 UNKNOWN·count fee proxy hold를 환급하지 않는다. 이번 알려진 usage의 공개 단가 추정은 USD0.00292370, 기존2.2배 보수계수 적용USD0.00643214다. 과거 알려진 usage와 합친 추정은 각각USD0.00570720/USD0.01255584다. 이는 count fee·실제 invoice·세금·환율을 확인한 청구액이 아니며 예약값에 다시 더하지 않는다. byte 기반 token allowance도 공식 invoice 상한 보장이 아니다.

누계는 Decisions7/count3/generation5시도=물리HTTP15이며, 과거 generation503·15초timeout·원분류FAIL·수정전parserFAIL은 보존한다. 원 응답의 opaque thoughtSignature와 responseId 값은 ignored0600 raw 증거에만 남기고 Git에 복사하지 않는다.

한 건의 전체 경로 관측이며 일반 정확도·오탐/미탐 비율·p50/p95를 추정하지 않는다. HTTP callback harness가 실제 TwoStage/계약을 사용했지만 production SDK transport, 영속 금융 authority의 실제 실행, 브라우저·사용자 제출 완료는 검증하지 않았다. 운영threshold/가격quote/비용소진UX/최종분석 예산배분/과거UNKNOWN해제는 여전히 TBD다. 배포·병합·운영설정·OAuth·Secret 변경0이고 Linear는 부모 전용 동기화가 담당한다.

독립 [질문 의미 감사](verification/issue123-fresh-head-two-stage-semantic-review.md)와 [관측·비용 감사](verification/issue123-fresh-head-two-stage-observation-cost-review.md)는 범위 내 PASS·미해결P1/P2 없음으로 봉인됐다. LTS01–07 및 LTS08 제안의미가 통과했고 UI는 미검증이다. 기록commit 전 unchanged 필수guard도 exit0이며 Gradle UP-TO-DATE 가능성 때문에 새fresh1710 실행으로 집계하지 않는다. 기록 게시의 정확 HEAD CI는 Worklog·Git 이력·PR126에 이어 기록한다.

</details>

<a id="history-issue-123-live-decisions-diagnostic"></a>

<details>
<summary>ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md</summary>

원본: `docs/ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md`

<a id="history-issue-123-live-decisions-diagnostic--123-최소-실제-decisions-진단"></a>

# #123 최소 실제 Decisions 진단

2026-10-18 UTC. 기존 Gemini+OpenAI 합산 USD1 승인 안에서 격리 HTTP smoke1회와 독립 사전 정답의 한국어 사례를 확인했다. 실제 Decisions 추론은 **4회**, retry/fallback/count·Gemini 추론은0회다. 운영 flags·가격 registry·confidence 정책을 활성화하거나 Decisions를 병합하지 않았다.

| 사례 | 사전 기대 | 실제 raw choice / confidence | HTTP 지연 |
| --- | --- | --- | --- |
| 절대 날짜·시간·서울역 | CLEAR | CLEAR / 0.83 | 1011.1ms |
| 의도된 주말 종일 | CLEAR | CLEAR / 0.88 | 201.9ms |
| 오후3시부터5시의 문맥 | CLEAR | CLEAR / 0.79 | 196.2ms |
| 오전/오후 없는8시부터9시 | NEEDS_REVIEW | CLEAR / 0.06 | 203.6ms |
| 명시적 제외·일요일 불가 | CLEAR | 미호출 | 미측정 |

네 응답 모두 HTTP200이며 현재 production `AmbiguityClassificationContract`의 offline read/usage/parse를 통과했다. 입력 usage 합계2061·출력0, usageComplete=true다. raw 응답 검사의 threshold0은 운영 임계값이 아니다. 추가 평가4case의 원문·사전 정답은 결과를 보기 전에 독립 작성했고 첫 smoke의 CLEAR 기대도 호출 전에 고정했다. 불일치에서 중단하는 계획대로 마지막 사례를 실행하지 않았다. 사후 정답/프롬프트 수정이나 재호출은 없다.

오전/오후 사례의 raw label 불일치를 의미 평가 통과로 바꾸지 않는다. 다만 분포가 CLEAR0.37/NEEDS_REVIEW0.31/INDETERMINATE0.32·confidence0.06이어서 낮은 확신을 생성 모델로 재확인하는 합의 흐름이 필요하다는 근거다. 같은 기록 응답을 threshold0.1이라는 **가정 예시**로 offline 재검사하면 기존 parser가 INDETERMINATE/LOW_CONFIDENCE를 반환한다. 실제 생성 단계나 전체2단계 성공을 검증한 것은 아니며 운영 임계값은 별도 개발/미튜닝 평가가 필요하다.

[공식 응답 계약](https://developers.openai.com/api/reference/resources/decisions/methods/create)은 choice·선택지별 probabilities·confidence를 별도 필드로 제공한다. confidence0.06을 CLEAR 확률6%나 보정된 정답률로 해석하지 않는다(CLEAR probability는0.37). [공식 해석 안내](https://developers.openai.com/api/docs/guides/decisions#interpret-the-answers)는 응용의 labeled examples와 오탐/미탐 비용으로 routing threshold를 정하도록 한다. OpenAI 전용 confidence 수식은 이 확인에서 확보하지 못했으며 다른 공급자의 수식을 전용하지 않는다. 실제 기록 응답의 classifier→TwoStage 회귀2가 INITIAL_PASS했다. 기존 e83 사전 테스트의 threshold0.8을 그대로 사용해 LOW_CONFIDENCE→생성기1회→NEEDS_CHOICE/CLEAR 결과 보존을 확인했다. 생성기는 합성이며 실제 생성 모델 성공이나 운영 임계값 선택을 입증하지 않는다. [회귀 증거](../.tdd/verification/issue123-recorded-low-confidence-pipeline.md)에 사전 threshold·기록 응답 지문을 보존했다.

비용은 전체 고정 JSON 2397~2479B에 대해 일반 BPE byte 상한과 framing 여유65,536token/요율2.2배를 적용했다. 최대5회 token 견적USD0.07208960, 남는USD0.92791040은 세금·결제 등 여유로 유지했다. 실제 보고 usage의 기본 공개 단가 환산은USD0.0002061, 같은2.2배 보수 환산은USD0.00045342다. 최종 invoice/계정별 세금·수수료가 확인됐다는 뜻은 아니며0으로 가정하거나 여유를 반환하지 않았다. [독립 비용 검토](verification/issue123-minimal-smoke-byte-quote-review.md)는 견적의 근거와 서버 강제 cap이 아니라는 한계를 구분한다.

[사전 사례](evaluation/issue123-live-korean-diagnostic-cases.json) SHA는 `47390e74ed16237ce3dd9e9fb0002d304827c22e4592a8965f3151a8fcfd2552`, [구조화 근거](../.tdd/verification/issue123-live-decisions-diagnostic.json)에 request/response/source 지문·호출수·usage·중단 이유를 남겼다. 원시 응답·로컬 원본은 Git 제외 evidence에 보존한다. 기존 개발/holdout corpus는 다시 읽거나 튜닝하지 않았다. 4개 표본으로 정확도나 p50/p95, 실제 방 예산·최종 AREA 분석·운영 준비 완료를 주장하지 않는다.

가격과 byte 기반의 출처는 [공식 Decisions 안내](https://developers.openai.com/api/docs/guides/decisions), [공식 tiktoken BPE 설명](https://github.com/openai/tiktoken), [공식 token counting의 framing 한계](https://developers.openai.com/api/docs/guides/token-counting)다. 모델 전체 context를 이 작은 요청의 필수 과금량으로 잡을 공식 사유는 확인되지 않았다.

</details>

<a id="history-issue-123-live-two-stage-validation"></a>

<details>
<summary>ISSUE_123_LIVE_TWO_STAGE_VALIDATION.md</summary>

원본: `docs/ISSUE_123_LIVE_TWO_STAGE_VALIDATION.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_123_LIVE_TWO_STAGE_VALIDATION.md`

<a id="history-issue-123-live-two-stage-validation--123-실제-2단계-재확인--별도-단일-사례-검증"></a>

# #123 실제 2단계 재확인 — 별도 단일 사례 검증

2026-10-18 UTC. 최초 Decisions 진단의 LIVE-KO-03 raw label 불일치 **FAIL/STOP**은 그대로 보존한다. 이 문서는 그 평가를 재개하거나 정답을 바꾸지 않고, 같은 원문에서 정상 재확인 분기에 진입했을 때 실제 생성 응답이 의도를 보존하는지 별도로 검증한다. 실제 실행은 HTTP503에서 중단됐고, 질문 의미 평가 목표는 미완료다.

[독립 사전 oracle](evaluation/issue123-live-two-stage-oracle.json)은 SHA `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`로 동결했다. 기존 e83 테스트의 threshold0.8을 그대로 재사용하며 운영 threshold(null/OFF)를 선택하지 않는다. 원문·prompt·schema·출력 한도를 결과에 맞춰 변경하지 않는다.

- 입력: “10월 24일 토요일 8시부터 9시까지 가능해요.”
- 목표: 날짜와 가능 의도를 보존하면서 같은 날 08–09시/20–21시를 구분하는 NEEDS_CHOICE와 두 완전한 제안. CLEAR는 이 사례의 의미 실패, NEEDS_EDIT는 안전한 유보지만 질문 목표 미완료다.
- 새 물리 HTTP 최대3회: 새 Decisions1 → 정상 review/low-confidence 분기에서만 Gemini count1 → count1..4096이면 generation1. 확신 높은 CLEAR·오류·유실·timeout이면 즉시 중단, 재시도/redirect/fallback/continuation0.
- 원래 production contract의 전체 payload와 candidate1·thinkingLevel LOW·thought 포함 maxOutputTokens2048을 유지한다. 기존 TwoStage가 callback port의 실제 HTTP 호출 순서를 조정하며 공통15초 예산을 사용한다. 생산 SDK transport·실제 영속 예산 authority·웹/제출 저장/방 전체 분석의 실모델 관통 검증으로 주장하지 않는다.

[독립 비용 검토](verification/issue123-live-two-stage-cost-review.md)에 따라 기존 USD0.07208960 예약은 유지한다. Gemini count와 generation 각각81920 입력 allowance·2048 출력의 full-generation 견적에2.2배 여유를 두고, 새 Decisions1을 더한 추가 예약은USD0.31854592다. 같은 승인 USD1에서 누적USD0.39063552, 추가 비용 여유USD0.60936448를 유지한다. Gemini byte/token·count 단가의 공식 상한 증명이 아닌 보수 engineering 견적이고, 세금·최종 청구액을0이나 확인된 값으로 가정하지 않는다.

실행은 수정 없는 전체 guard와 현 소스 독립 리뷰가 통과한 뒤 수행한다. 원시 요청/응답·single-use consumed 기록·개별 HTTP 지연·usage nullable 값과 모델 응답 의미의 독립 판정을 별도 보존한다. 사전 oracle 실패 시 추가 호출·답변 손질·threshold 변경 없이 중단하며 전체 품질·정확도·p50/p95·운영 준비 완료를 주장하지 않는다.

실행 전 wire는 분류2397B·count3172B·generation3111B로 모두16KiB 미만이다. 동결 [실행 계획](https://github.com/meet-me-duo/meet-me-server/blob/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history/issue123-live-two-stage-execution/execution-plan.json)의 SHA는 `368bafb75f2c36f0980501ea5b7b8a9669f5b8eb5e6705c49e4b6659b9aeb746`이며 [wire manifest](https://github.com/meet-me-duo/meet-me-server/blob/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history/issue123-live-two-stage-execution/wire-manifest.json)와 exact harness/callback 원본을 같은 history에 보존한다. callback은 전체 guard exit0을 재검사하고 stage별 O_EXCL consumed를 요청 전에 기록한다. 실행 전 준비 시점의 추가 실제 호출은0이었다. 아래는 이후의 고정 계획 실행 결과다.

<a id="history-issue-123-live-two-stage-validation--실제-관측과-차단"></a>

## 실제 관측과 차단

전체 guard와 CODE GATE 리뷰 통과 뒤 05:37:50–05:37:52 UTC에 고정 계획을 한 번 실행했다.

| 단계 | 관측 | HTTP 응답 측정 |
| --- | --- | --- |
| 새 Decisions | HTTP200, raw CLEAR/confidence0.06, 별도 CLEAR probability0.37, 현재 parser→LOW_CONFIDENCE |855.0ms|
| Gemini count | HTTP200,530토큰, 기존4096 gate통과 |225.9ms|
| Gemini generation | **HTTP503**, 성공 생성 응답·usage 미확보, 즉시 중단 |347.8ms|

실제 TwoStage는 A1→count1→generation1을 조정했다. JVM 전체 실행은2177.7ms이며 SDK transport 관통으로 주장하지 않는다. eval callback의 nonzero exit가 하네스에서 CONFIGURATION으로 요약됐지만 관측된 실제 공급자 실패는 HTTP503이다. 이 실패를 권한 부재나 모델의 의미 오류로 단정하지 않는다. 성공한 생성 질문 응답을 확보하지 못해 사전 LTS02–08과 원문 의도 보존을 독립 평가할 수 없다. 오류 본문은 callback에서 저장하지 않았으므로 503 응답 본문이 비었다고 단정하지 않는다. 결과는 **FAIL_PROVIDER_OR_STRUCTURAL / 질문 검증 미완료**이며 사전 조건대로 재시도·추가 호출0이다.

초기4회와 합한 Decisions는5회·입력2573/출력0이다. 그 보고 usage의 공개 기본 단가 환산은USD0.0002573,2.2배 보수 환산은USD0.00056606이다. Gemini count1과 generation시도1의 청구/생성 usage는 미확인으로 남기고0으로 바꾸지 않았다. 전체 예약USD0.39063552와 추가 비용 여유USD0.60936448를 유지한다. 최초4회 개별 지연1011.1/201.9/196.2/203.6ms와 초기 raw FAIL/STOP은 [원 진단](#history-issue-123-live-decisions-diagnostic)에서 보존한다.

[구조화 관측](../.tdd/verification/issue123-live-two-stage-observed.json)과 [single-use 실행 기록](https://github.com/meet-me-duo/meet-me-server/blob/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history/issue123-live-two-stage-execution/live-invocation.json)에 각 요청/응답·guard·oracle 지문과503 차단을 남겼다. 운영 활성화·임계값 결정·전체 정확도/p50/p95·실제 모임10원 보장을 입증한 결과가 아니다.

<a id="history-issue-123-live-two-stage-validation--별도-후속-시도2--실행-전-고정"></a>

## 별도 후속 시도2 — 실행 전 고정

부모가 최초503의 FAIL/STOP과 미확인 예약을 유지한 채 같은 범위의 한정된 별도 재검증을 요청했다. [독립 예산 검토](verification/issue123-live-two-stage-attempt2-budget-review.md)의 Decimal 검증에 따라 기존USD0.39063552+추가 최대USD0.31854592=누적USD0.70918144, 추가 비용 여유USD0.29081856이다. 그다음 같은 전체 시도는USD1.02772736으로 승인USD1을 넘으므로 실행하지 않는다.

첫 종료 이후7분 이상 지난 시점에 새 single-use 폴더와 [고정 계획](https://github.com/meet-me-duo/meet-me-server/blob/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history/issue123-live-two-stage-attempt2/execution-plan.json)을 준비했다(plan SHA `b4fe8fd30e5b61b87d259cce1ab6a4099220d445910ba5617c2511d9354a0e28`). 기존3개 요청 bytes/SHA·oraclec69·합성threshold0.8·전체15초·2048/LOW·harness757b는 동일하다. 최대새A1/count1/gen1, 이 별도 시도 내부 retry/fallback0이며 첫 실패에서 중단한다. 원래503 계획/결과는 수정하지 않는다. 새attempt2는 실행 전 정적 독립 리뷰 뒤 한 번만 실행하며 현재 준비 실행의 추가 HTTP는0이다.

<a id="history-issue-123-live-two-stage-validation--후속-시도2-실제-관측"></a>

## 후속 시도2 실제 관측

05:47:02–05:47:17 UTC, 첫 종료 이후 약9분 뒤 고정한 후속 시도를 한 번 실행했다. 새A는HTTP200/LOW_CONFIDENCE0.06(입력512/출력0,394.6ms), count는HTTP200/530(245.3ms)이었다. 생성은 **남은 공통15초 budget TIMEOUT**으로 child process가 종료돼 성공 응답/usage를 확보하지 못했다. 두 번째 생성의 HTTP status·개별 HTTP 지연은 관측하지 못했으며503으로 쓰거나0으로 채우지 않는다. JVM 전체15538.3ms는 시작 준비도 포함하므로 pipeline15초 위반이나 생성HTTP지연 실측으로 해석하지 않는다.

누계는 Decisions6회·입력3085/출력0, Gemini count2·생성시도2·성공한 생성응답0, 평가HTTP시도10이다(기존 model metadata조회2는 별도). 알려진Decisions usage만 공개기본단가USD0.0003085/보수2.2배USD0.00067870이며 Gemini의 청구·생성usage는UNKNOWN으로 유지한다. 첫503/원래분류FAIL/STOP·모든 미확인 hold를 보존했고 누적예약USD0.70918144/추가 비용 여유USD0.29081856를 반환하지 않는다.

같은 범위의 추가 검증은 최대USD0.31854592가 더 필요하여 누적USD1.02772736으로 승인USD1보다USD0.02772736 초과한다. **추가 dependent 실제 호출을 중단한다.** 사전oracle/threshold/prompt/2048/LOW/15초를 실패에 맞춰 바꾸지 않았다. 생성 질문이 관측되지 않아 원문 의도 보존의 독립 의미 평가는 여전히 미완료다. [후속 구조화 관측](../.tdd/verification/issue123-live-two-stage-attempt2-observed.json)에 UNKNOWN·timeout·각 계수와 비용을 보존한다.

</details>

<a id="history-issue-123-ui-and-evaluation-verification"></a>

<details>
<summary>ISSUE_123_UI_AND_EVALUATION_VERIFICATION.md</summary>

원본: `docs/ISSUE_123_UI_AND_EVALUATION_VERIFICATION.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_123_UI_AND_EVALUATION_VERIFICATION.md`

<a id="history-issue-123-ui-and-evaluation-verification--123-ui-계약과-분류-평가-준비-검증"></a>

# #123 UI 계약과 분류 평가 준비 검증

2026-10-18 UTC. 추가 유료 호출 없이 현재 서버와 웹 PR31의 계약을 대조하고, 취소 후 질문이 남는 결합 결함을 서버#128·웹#32의 별도 브랜치에서 수정했다. 기준 서버는 `18cdb5ddec4f9e09db50feb54337aae9516a0108`, 웹은 `02a68d8e50680e87dd153156e8bb7ea884a794cc`다. 원본 PR126/PR31은 Draft·미병합으로 보존했고 그 브랜치에 수정하지 않았다. 아래 후보 검증은 이 기준 위의 새 회귀·최소 수정 바이트에 대한 기록이며, 게시 SHA는 Git/PR에서 확인한다.

<a id="history-issue-123-ui-and-evaluation-verification--발견과-최소-수정"></a>

## 발견과 최소 수정

NEEDS_CHOICE 검사를 취소하면 persisted question이 그대로인 상태에서 서버 snapshot이 질문을 반환했고, 웹은 상태와 무관하게 질문을 활성화했다. 이 때문에 새 입력 진입이 막히거나 취소한 검사에 답변을 보내409가 날 수 있었다. 기존 테스트의 취소 fixture는 question:null이어서 이 조합을 놓쳤다.

서버는 열린 방의 유효 상태가 NEEDS_CHOICE일 때만 현재 질문을 노출하도록 projection 조건 한 곳을 보완했다. 웹도 NEEDS_CHOICE에서만 질문·답변 controls를 활성화한다. 저장된 질문을 삭제하지 않으므로 기존 질문3회 상한은 환급되지 않는다. 원문·마지막 accepted·멱등성과 소유권·revision·마감 정책,500자·검사/재시도 상한,default OFF는 바꾸지 않았다. DTO/경로·nullable 공개 계약·DB migration은 그대로다.

- 서버 독립 새7회귀: 질문 노출 assertion1 RED/통제6 PASS→최소 수정→7/7 GREEN. 표시 조건 제거 FI는 assertion1, 질문 이력 삭제 FI는 assertion5로 탐지했고 각 정확 복구 뒤7/7 GREEN이다.
- 웹 독립 새7회귀: 비활성 상태의 질문 controls assertion4 RED/통제3 초회PASS→한 줄 수정→7/7 GREEN. 기존67 포함74/74 PASS, guard 제거 FI에서4개 탐지·복원7/7 PASS다. 초반 fixture/corepack·classpath·사후 지문 검사 준비 오류는 유효 RED나 제품 실패로 계수하지 않고 원본을 별도 보존했다.
- 구체 서버 범위: [계약 감사](verification/issue128-server-contract-scope-audit.md), [독립 FI](../.tdd/verification/issue128-cancel-question-independent-faults.json). 이전 기준의 [교차 저장소 계약 감사](verification/issue123-ui-contract-baseline-audit.md)는 발견 당시 기록으로 구분한다.

<a id="history-issue-123-ui-and-evaluation-verification--ui-시나리오와-실제-검증-범위"></a>

## UI 시나리오와 실제 검증 범위

| 경로 | 검증 근거와 관측 |
| --- | --- |
| 원문 저장→대기 | native N01 실제 POST202·PG 저장·미완료/로딩을 구분 |
| 질문→선택 | N02 원문과 완전한 제안 표시·명시 확인→전체 입력 재검사→CLEAR |
| 직접 수정 | N03 direct_text를 실제 API로 전송·새 입력 전체 재검사→CLEAR |
| 의도된 넓은 범위 | N04는 명시 합성 CLEAR가 원문을 보존함을 검증. 모델이 실제로 CLEAR를 고른 증거는 아님 |
| 취소→새 입력 | 새N13 실제 NEEDS_CHOICE 취소200·question:null·원문 보존·옛 답변 없이 새POST202 |
| 실패→명시 재시도 | 새N14 실제 FAILED/retry_allowed→retry202→대기·미완료→합성 CLEAR/accepted·완료 |
| 새로고침·동시 수정·멱등 재전송 | N11 실제 GET·CAS 단일 승자·동일 request ID 재생 |
| 수동/시간 마감 중 늦은 결과 | N05 두 경로에서 provider를 기다리지 않고 마감·늦은 CLEAR 게시 차단 |
| 마지막 완료본 | N06 실제 frozen batch가 마지막 accepted만 포함·pending 수정 제외 |
| 소유권·게스트 proof·blind 경로 | N07–09 실제 password/HttpOnly proof·타인/HOST 비공개·REPLACE_ONLY 읽기 금지·invalid Bearer 우선 |
| 계정 변경·로그아웃·늦은 개인 응답 | N10 loopback OIDC/RSA를 사용해 계정 우선·logout 뒤 개인 상태 격리 |

원본 웹02에서는 집중unit157·기존browser22가 재통과했으며 그 source268개가 불변이었다. 이 숫자는 새 후보의 전체 실행에 합산하지 않는다. 새 후보의 표준 웹 gate는 API 정합·lint·typecheck·build와 전체unit627/58파일 PASS, 전체browser425 PASS/기존skip1/실패·flaky·retry0이다. 새7·집중74·기준157/22는 전체와 중복된다. 정확한 명령·134파일 지문은 웹 `docs/verification/issue-32-web-final-gate.json`과 `issue-32-web-source-manifest.json`을 따른다.

새 실제 native14는 브라우저→Spring→PostgreSQL18/Redis8, 실제 guest proof와 RS256 검증을 사용했다. 실제 API 관측154회·API route mock0·외부 요청0·실제 모델/OAuth provider 호출0이다. source482·복제 실행 파일1559·dependency216 및 웹 행동7파일/자산6개가 전후 동일했다. 실제 OpenAPI36paths/61schemas는 웹 snapshot과 JSON 의미가 같았다. 종료 뒤 포트18920/21/22와 컨테이너가 모두 내려갔다. [안전한 관측 요약](../.tdd/verification/issue128-native-ui.json)에 지문과 범위를 남겼다.

하네스는 **top-level Primary synthetic PreSubmissionCheckProvider**로 결과를 제공한다. 따라서 native14는 현재 UI/HTTP/PG/worker 상태 계약의 증거이며, 실제 TwoStage/classifier/provider wire·영속 금융 authority의 실행 증거가 아니다. 기존 source의 별도 합성 TwoStage/원장 테스트, 이전 fresh실모델 한 사례와도 섞지 않는다. 실제 OAuth 공급자·운영 DB·Secret·모델 정확도·오탐/미탐률·calibration·p50/p95는 이 실행 범위 밖이다.

<a id="history-issue-123-ui-and-evaluation-verification--사전-정답-평가-준비와-남는-경계"></a>

## 사전 정답 평가 준비와 남는 경계

기존 개발60/holdout60의 원문·A gold·기존 실증과 임계값을 보존하고, 별도8대조쌍/16문장과 단계별 oracle overlay를 독립 작성·리뷰했다. 날짜·시간·장소·지시대상·부정·예외·종속조건·유연한 조건을 점검하며, 넓은 범위·유효한 종속조건 자체를 억지로 질문 대상으로 만들지 않는다. root/구현 담당에게 holdout 원문·개별 정답은 노출하지 않았다. 정확한 최종 파일·집계·채점/노출 이력·사전 봉인은 [평가 자산 manifest](evaluation/issue123-classification-evaluation/manifest.json)와 공개 README를 따른다. manifest SHA는 `1d15a4e9323bbf7f8b3c180a4fb4205c2c116c149ef269dab01a328b9ce2bed7`다. portable 구조 validator·PROTOCOL_ONLY와 DRAFT/변조 거절 검증이 통과했으며 root도 payload를 출력하지 않고 지문/실행을 재확인했다. 초안 P2 4그룹은 실제 호출 전 보정했고 원본/차이를 보존했다. 기존120은 변경하지 않았다.

이것은 정답 세트와 검증 프로토콜 준비다. 합성 PROTOCOL_ONLY 예상값은 모델 결과가 아니며 일반 정확도나 새로운 실제 분류 성적을 만들지 않는다. 정상 CLEAR/질문 금지, 필요한 질문, 안전한 직접 수정·유보, raw 오분류와 생성 단계 복구, 오류·refusal·미실행 분모를 구분한다.

이번 추가 모델 호출과 UNKNOWN 환급은0이다. 기존 누적 예약USD1.33185536/합산USD2·보수여유USD0.66814464는 그대로 유지한다. 추가 실측은 별도 작은 호출계획·사전 상한·정답/중단 조건 고정 없이 수행하지 않는다. 기존 raw CLEAR 불일치·503·15초timeout·수정 전 parser 실패는 역사적 결과로 보존한다. 운영 threshold/가격·활성화·전체 실모델 품질은 미완료이고 README 생성 이미지/편집은 #127 선행 검증 이후다. 병합·배포·운영 설정·Secret 변경은 하지 않았다. Linear는 부모 전용 동기화가 담당한다.

현재 서버 unchanged 필수 전체 guard도 exit0이다. 새 XML214 suites/1717개 중1713PASS·기존4SKIP·실패/오류0이고 source/config558 before=after=current다. 두 모델 key가 빈 값인 subprocess의 실제 호출0이며 [게이트 근거](../.tdd/verification/issue128-final-server-gate.json)에 기록했다. [최종 독립 게시 리뷰](verification/issue128-ui-evaluation-publication-final-review.md)는 LOCAL PUBLICATION PASS·미해결P1/P2 없음으로 봉인됐다. Commit/PR은 동일 후속 커밋 예정이며 원격 정확 HEAD CI는 Git 이력/PR과 로컬 게시 receipt에 남긴다.

</details>

<a id="history-issue-123-usd2-generation-diagnostic"></a>

<details>
<summary>ISSUE_123_USD2_GENERATION_DIAGNOSTIC.md</summary>

원본: `docs/ISSUE_123_USD2_GENERATION_DIAGNOSTIC.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_123_USD2_GENERATION_DIAGNOSTIC.md`

<a id="history-issue-123-usd2-generation-diagnostic--123-usd2-합산-한도의-생성-질문-진단"></a>

# #123 USD2 합산 한도의 생성 질문 진단

2026-10-18 UTC. 사용자가 기존 Gemini+OpenAI 합산USD1에 USD1을 추가해 **모든 이전 호출·예약을 포함한 총USD2**를 승인했다. 최초 분류 FAIL/STOP, 실제 흐름의503/15초TIMEOUT과 미확인 예약USD0.70918144는 유지한다. 코드 PR126/b3770438은 기본 OFF/DENY이며 운영 설정·가격·confidence·product timeout을 바꾸지 않는다.

[비유료 원인 점검](verification/issue123-nonpaid-generation-diagnosis.md) 결과 두 요청의 전체3111B/SHA와 count530은 같다. 첫503의 오류 본문/RetryAfter는 미수집됐고 둘째는 헤더/progress가 없어 연결·대기열·thinking 지연을 구분할 수 없다. 공식문서의 일시적503/생성 지연 가능성이 원인 확정을 뜻하지 않는다. 기존 hold를0이나 무료로 정산하지 않는다.

따라서 [고정 실행 모드](evaluation/issue123-standalone-generation-oracle-mode.json)에서 **동일 생성 요청만 각최대60초 관측**한다. 추가A/count0, B최대2회·동시1이며 첫HTTP200/영구실패/출력오류에서 중단한다. 관측된HTTP200/영구오류가 body timeout으로 바뀌어도 재시도하지 않는다. transient HTTP408/429/500/502/503/504 또는 status미관측 network/timeout에만10초 간격의 후속1회를 허용한다. RetryAfter가10초를 넘거나 유효한ASCII정수/HTTP-date가 아니면 중단한다. 각HTTP 요청 자체 retry/redirect/fallback0이다.

기존 oraclec69의 LTS02–08(같은 날짜08–09/20–21시 선택 제안, 정확excerpt, 나머지의도보존)을 바꾸지 않는다. 새 raw response는 실제 production contract로 파싱하고 독립 의미 평가한다. 기존actualA0.06/합성threshold0.8·count530를 참고해 offline TwoStage replay를 할 수 있으나 **freshA+실B15초 흐름·productionSDK·영속 금융 authority·웹 저장 검증 성공으로 주장하지 않는다**. product15초는 그대로다.

[독립 비용 검토](verification/issue123-usd2-generation-diagnostic-cost-review.md)는 각generation최대견적USD0.152064, 추가2회USD0.304128, 이전예약을 포함한 최대USD1.01330944·추가 비용 여유USD0.98669056를 확인했다. 실제 청구·세금·결제 수수료와 Gemini byte/token 공식 상한을 확인한 값이 아니며, 기존81920 engineering input allowance/2048 thought-inclusive output/2.2배 여유를 유지했다. 미호출 slot과 UNKNOWN 노출은 따로 기록한다.

동결 [계획](https://github.com/meet-me-duo/meet-me-server/blob/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history/issue123-usd2-generation-diagnostic/execution-plan.json) SHA는 `95db90eee4118232952bed08a743956f5d5356145d084df8638355a62a3f90c6`, request SHA는 `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44`다. 실제 실행 전 static review에서 알려진HTTP상태의 재시도 우회와 invalid RetryAfter 허용을 발견해 유료호출0 상태에서 최소 보완했고, 원본과 독립 재현을 history에 보존했다. 최종 격리 mock59/59와 [실행 전 독립 리뷰](verification/issue123-usd2-generation-preexecution-review.md) 후에만 아래 두 요청을 실행했다.

<a id="history-issue-123-usd2-generation-diagnostic--실제-관측과-중단"></a>

## 실제 관측과 중단

2026-10-18 06:09:51 UTC의 첫 생성은 **HTTP503/UNAVAILABLE**, 관측548.4ms였다. 오류 본문은 현재 모델의 high demand를 설명했고 RetryAfter는 없었다. 이 원인은 이번 응답에만 해당하며, 과거 본문 미수집503의 원인으로 소급하지 않는다. 고정 계획대로10.054662초 뒤 실행한 두 번째 생성은 **HTTP200/STOP**, single candidate/part로5195.5ms에 응답했다. 이후 추가 호출0으로 중단했다. 원본 응답 SHA는 `983e23db4fab03c1f5c41e5ca8775e8cf798b122ba5dd9507114c9a7a7cc080a`다. opaque signature 값은 추적 fixture·문서·로그에 복사하지 않는다.

질문은 “8시부터 9시까지가 오전 시간대인지 오후(저녁) 시간대인지 알려주세요.”이며, 같은10월24일의 오전08–09/오후20–21 두 완전한 원문 제안을 반환했다. [독립 의미 평가](verification/issue123-usd2-generation-semantic-review.md)는 사전에 고정한 LTS03–07 및 LTS08의 제안 의미(질문·excerpt·두 대안·날짜/길이/가능 의도 보존)을 통과로 평가했다. **기존 production parser는 공식 optional `thoughtSignature` metadata 때문에 INVALID_RESPONSE**였다. 당시 LTS02 실패와 fresh15초 전체 경로 LTS01 미실행은 그대로 보존한다. 이는 한 사례의 의미 통과이며 일반 정확도나 제품 전체 성공이 아니다. 다음 작업은 별도 독립 RED→최소 metadata 호환 수정→격리 재생이며 추가 유료 호출이 아니다.

반환 usage는 input530/candidate188/thought366/total1084, thought 포함 output554다. 성공 응답의 Gemini 알려진 사용량 견적은 기준USD0.002475·2.2배USD0.005445다. Decisions6회 input3085/output0과 합한 알려진 견적은 기준USD0.0027835·2.2배USD0.00612370이며 **invoice·세금·결제 수수료 확인값이 아니다**. 실패·count·timeout의 청구 UNKNOWN과 모든 예약USD1.01330944를 그대로 유지하고 합산USD2 여유USD0.98669056를 환급하지 않는다. 전체 누계는 Decisions6/count2/generation4 = HTTP시도12, generation HTTP200 응답1/당시 parser 수용0이다. [독립 관측·비용 감사](verification/issue123-usd2-generation-observation-audit.md)가 지문·중단·산술을 확인했다.

기존 코드 b3770438의 [정확 CI](verification/issue123-b377-exact-ci.json)는 Quality Gate/ARM64/Lifecycle 모두 성공했다. metadata 수정은 이 성공과 구별되는 후속 커밋으로 새 필수 guard·독립 리뷰·정확 HEAD CI를 요구한다. 운영 timeout15초·가격/threshold/활성화·참여/마감 정책은 변경하지 않는다.

후속 [metadata 호환 TDD](ISSUE_123_THOUGHT_SIGNATURE_COMPATIBILITY.md)에서는 독립 새26개 genuine RED6/통제20을 확인한 뒤 Contract 한 파일만 수정했다. 집중328개 GREEN·기존 test inventory 불변이며 [exact raw 비네트워크 재생](../.tdd/verification/issue123-thought-signature-offline-replay.json)에서 parser와 기록된 A/count를 거친 TwoStage가 같은 질문/대안/usage를 수용했다. 수정 전 실제 호출의 parser 실패는 역사적으로 유지하고, LTS02의 후속 offline 호환 통과만 별도로 기록한다. 독립FI3 탐지/각복구/최종26PASS 및 새 필수 guard213 suite/1710개(1706PASS·기존4SKIP·0fail/error), source557 before=after=current도 통과했다. [최종 독립 게시 리뷰](verification/issue123-thought-signature-publication-final-review.md) PASS·미해결 P1/P2 없음이며 추가 유료 호출0이다. 후속 commit/push/정확 CI가 다음 단계다. 정확 SHA·원격 결과는 Git 이력·PR126 및 ignored publication receipt에 별도 기록한다.

</details>

<a id="history-issue-130-representative-decisions-evaluation"></a>

<details>
<summary>ISSUE_130_REPRESENTATIVE_DECISIONS_EVALUATION.md</summary>

원본: `docs/ISSUE_130_REPRESENTATIVE_DECISIONS_EVALUATION.md` · 복구: `git show ebcf218cd27619e3129e9484c7066899693b378b:docs/ISSUE_130_REPRESENTATIVE_DECISIONS_EVALUATION.md`

<a id="history-issue-130-representative-decisions-evaluation--현재-후속-공통-정책-v2-무료-정합화-2026-10-18-utc"></a>

# 현재 후속: 공통 정책 V2 무료 정합화 (2026-10-18 UTC)

사용자 승인으로 [공통 정책 V2](ISSUE_130_SHARED_POLICY_V2_EVALUATION.md)의 A CLEAR 설명·A/B 지침·평가 정의 정합화를 별도 수행한다. 아래 실제 A16/A24/B2는 **V2 이전 각 source의 실행·고정 gold/점수 역사**이며 V2 성적으로 재사용하지 않는다. 당시 지침 변경0·CI 대기·예산 문구는 해당 시점을 나타낸다. V2 추가 실모델 호출은0이고 의미 효능은 미검증이다. 최신 무료 검증·게시·정확 CI 상태와 다음 시작점은 [Worklog](ISSUE_107_PRE_SUBMIT_WORKLOG.md)·PR132를 따른다.

<a id="history-issue-130-representative-decisions-evaluation--개인-맥락-a24b2-실제-결과-2026-10-18-utc"></a>

# 개인 맥락 A24/B2 실제 결과 (2026-10-18 UTC)

새 봉인 입력·gold로 정확 게시 HEAD `9d9c934fd8f999f0bd4a1723bd8987b9efd86b93`에서 A24와 사전 지정 standalone B2를 각 한 번 실행했다. Decisions24·count2·generation2 전수 기술 완료/usage 확인, source/runtime 전후동일·자동 retry/redirect/fallback0이다. 원 첫 CONFIGURATION 중단과 원 예약·계획·실패는 보존한다. 이번 실행 뒤 게시할 기록 커밋을 실제 실행 당시 HEAD로 소급하지 않는다.

A의 raw gold CLEAR14는 CLEAR7/REVIEW7, gold REVIEW10은 CLEAR0/REVIEW10이었다. raw 일치는17/24, raw false CLEAR0/10은 **OBSERVED_ZERO_ONLY**다. 고정 진단 .8에서 24건 전부 LOW_CONFIDENCE→B_REQUIRED이고 CLEAR_STOP은0이다. 불필요한 검토 판정7/14와 명확한 입력14/14의 B 경로를 숨기지 않는다. 후자는 실제 불필요 질문 수가 아니다. 품질·운영 효용은 **NOT_ESTABLISHED**, 기존16의 FAIL은 별도이고 개선 인과·모집단 정확도·운영 p50/p95를 주장하지 않는다.

B2는 A 결과 전에 고른 CHOICE1+SAFE_EDIT_ONLY1이며 실제 명확한 gold 입력은0건이다. 두 최초 독립 의미 판정은2/2 일치·미해결차이0, **PASS0/FAIL2**로, 각각 필요한 선택형 보완·직접 수정 안내가 누락되고 실제 CLEAR가 반환됐다. 원 두 판정은 변경하지 않았고 최종 상태는 [단계별 집계](verification/issue130-context-actual-stage-results.json)에 연결한다. 질문·선택지가 없다는 사실을 질문 품질이나 원문 보존의 PASS로 채점하지 않는다. 나머지 review8건과 clear14건의 실제 B 및 UI 최종 제출·운영 분포는 미검증이다. 전체 TwoStage나 영속 금융 E2E로 부르지 않는다.

두 번째 개인 퇴근시각 사례는 후속 정책 감사에서 **지침 명시성 차이**가 확인됐다. A 본문은 필수 personal/local fact 추측을 금지하지만 choice 설명은 person-dependent 조건을 넓게 허용하는 것처럼 읽힐 수 있다. B는 이미 정해진 의미/넓은 범위를 CLEAR로 보존하라는 지침에 필수 개인 시각 누락의 경계가 명시되지 않았다. 따라서0/2는 사전 고정gold 대비 관측 점수로 유지하되, 모델 성능만의 실패 원인으로 단정하지 않는다. prompt/gold/score를 변경하지 않고 정책 정합성 문제를 별도 기록하며 추가 튜닝·유료 호출은 보류한다.

첫 구성 중단의 USD0.34603008만 사용자 승인과 no-dispatch 근거로 별도 미실행 예약 정산했다. 기존 UNKNOWN 포함1.79322880은 유지하고 새 A0.34603008+B0.60825600을 선예약해 현재 **USD2.74751488/engineering 여유0.25248512**다. 실청구 환급·invoice hard cap 증명·UNKNOWN 해제는 없다. 실제 추가 호출은 중단했으며 gold·threshold·지침을 결과에 맞춰 조정하지 않았다. 실행·채점 당시 원문·개별 gold·출력은 root/구현 담당에게 공개하지 않았다. 평가 종료 뒤 부모의 명시 요청으로 개인정보 없는 합성 B2 두 원문·출력·실패 이유만 root에게 전달했다. 나머지 입력/개별gold는 계속 비공개이고 원 평가의 blind 이력은 보존한다.

현재 정확 HEAD9d9의 CI는 Gradle/Java77/ARM 성공 뒤 Python CLI21–24 assertion 실패다. 별도 환경 fixture 최소 보완은 기존25assert를 유지한102/JDK16/조건15 GREEN·FI3 탐지/복원으로 검증했고 이전 실패 이력을 보존한다. 후속 정확 HEAD CI는 별도로 확인한다. 품질·운영 정책/threshold/가격·최신 서버웹 조합·실제 인증/DB 전환 근거가 해결되지 않아 main/배포/운영 활성화는 HOLD다. 넓어진 사용자 완료 승인을 실패 게이트 우회 권한으로 해석하지 않는다.

[A 독립 집계](verification/issue130-context-actual-a24-aggregate.json) · [미실행 정산과 실행 연쇄](verification/issue130-cost-stop-and-reexecution-plan.md)


[실측 시간](verification/issue130-context-actual-latency.json)은 A case110.638249–1066.037736ms/suite4759.835602ms/root9067.388526ms, B case3389.243069–4700.874165ms/suite8422.100454ms/root12466.959510ms다. case는 로컬 parse/usage/관측을, B는 count+generation 둘을 포함하고 root는 compile/guard도 포함한다. standalone B에는 TwoStage routing이 없으므로 공개 집계의 범위 문구만 좁히고 원 집계SHA/수치를 보존했다. 순수 모델 속도·운영 p50/p95는 아니다.

아래는 이전 단계 기록이다. 각 당시 UNRUN·정산 미적용 문구는 현재 상태를 대신하지 않는다.

<a id="history-issue-130-representative-decisions-evaluation--개인-맥락-a24b2-첫-구성-중단-역사"></a>

# 개인 맥락 A24/B2 첫 구성 중단 (역사)

2026-10-18 UTC. 실제 A24 시작은 비용 문자열의 Python/Java 문법 차이로 사례 전 CONFIGURATION 중단됐다. 새 모델 결과0·A24/B2 전원UNRUN·새 raw gate NOT_EVALUATED이며 기존16 FAIL은 별도 보존한다. 현재 장부2.13925888, 이번 .34603008의 proven-unstarted 정산 근거 검토 중·기존 누적 예약1.79322880(UNKNOWN 포함) 불변·refund0·추가 예산 요청0이다. client 객체 생성과 HTTP dispatch를 분리하며 청구액0 직접 계측을 주장하지 않는다. [배치 집계](verification/issue130-context-stop-aggregate.json)와 [실행 계획](../implementation_plan.md)의 최신 구간을 따른다.

<a id="history-issue-130-representative-decisions-evaluation--130-decisions-평가-실행-검증과-단계별-품질-결과"></a>

# #130 Decisions 평가: 실행 검증과 단계별 품질 결과

2026-10-18 UTC. 이 문서는 실제 평가 대상과 기록 게시 커밋을 구별한다. 최초 실행 대상은 production HEAD `3982535765e878875a901dd9d28352358e594f6f`와 미게시 평가 helper Java `7bf79d8cf266e9af84aa292840228535eb117f841d855e0f2e83fcf7a1a2452f`/Python `5933f3c7bd8d1508dafe26089304a336fb17064328fa996377d5ccda18bdec15`다. 이후 게시 HEAD를 실제 실행 당시 HEAD로 소급하지 않는다.

독립 담당자가 별도 supplemental 8대조쌍 16개 전원을 순서·정답과 함께 사전 봉인했다. 기존 개발60/holdout60은 선택0·불변이다. 균형 대조 진단이며 운영 분포의 무작위 표본이 아니다. root·구현자·리뷰어는 선택 원문·개별 정답·비공개 index를 수동 열람하지 않았다. 입력 준비는 현재 production serializer만 사용했으며 16 wire는 2386–2503 bytes/총39006 bytes·0600이었다.

<a id="history-issue-130-representative-decisions-evaluation--원-실행-전-사전계획-새24-a와-사전2-b-역사"></a>

## 원 실행 전 사전계획: 새24 A와 사전2 B (역사)

사용자가 합산 한도를 USD3으로 늘리고 실제 참가자가 쓸 생활·현장 맥락의 표현을 요청했다. 이 사전계획 당시 hold는USD1.79322880/여유1.20677120/환급0·새 예약/호출0이었다. 현재 STOP과 장부 상태는 문서 맨 위의 최신 구간을 따른다. 기존16의 원점수/FAIL은 보존하고 이후 개선용 DEV로 구분한다. root는 사용자가 명시 요청한 기존 TIME 대조2건만 평가 종료 후 보았으며, 새24 원문·개별gold는 root와 후보 구현자에게 공개하지 않았다.

새12쌍24건은 퇴근/수업/육아/교대/이동, 지시·조건/현장 맥락, 시간대·모임기간으로 해소되는 문맥, 넓은 허용/선호/제외, 유효한 타인 가용성 의존을 포함한다. 두 평가자는 먼저 독립 gold를 봉인했다. A는24/24 일치했고 B는 초기8건 차이가 있었으나, 안전한 두 complete 해석을 만들 수 없으면 NEEDS_EDIT라는 기존 계약을 확인해 실제 응답 보기 전에 조정했다. 원 두 gold와 차이는 보존하고 미해결 정책0으로 봉인했다. 최종 A는CLEAR14/REVIEW10, B는MUST_NOT_ASK14/MUST_ASK_MATERIAL_CHOICE1/SAFE_EDIT_ONLY9다. 작성자·평가자의 과거 별도 합성 자료/프로젝트 노출도 기록하며 완전 무노출 평가자로 부르지 않는다.

**이전의 검토 필요8건과 이번 B2는 같은 후보 집합에서 8→2로 줄인 관계가 아니다.** 이전8은 완료된16 진단의gold(선택필수7/editonly1)이며 여전히B0이다. 이번은 별도 새24의review10건 중 사전 선정2건이다. A24 단회 최대예약.34603008 뒤 B count+generation 한 세트.30412800를2건 실행하면 총2.74751488/여유.25248512다. 세 번째 B까지는 총3.05164288로USD3을 넘으므로2건으로 제한했다. 이는 보수 engineering reservation이며 invoice hard cap이 아니다.

B2 선정은 **유일한 선택형 필요1건 + 고정 입력 순서에서 첫 생활 맥락 직접 수정1건**이다. A 결과를 보거나 잘 맞을 듯한 사례를 선택하지 않았다. 실제 production generator의 count+generation을 독립 실행하는 STANDALONE_B_QUALITY_ONLY이며 새로운A호출/TwoStage/관리형금융/브라우저제출E2E를 검증한 것으로 부르지 않는다. 첫 사례는 질문·서로 다른 A/B 전체제안·다른 조건 보존을, 둘째는 임의 개인 시각 발명 없이 NEEDS_EDIT·질문 없음과 기존 명시 수정 경로를 평가한다. 맞춤 사유 문장은 현재 DTO에 없어 필수 gold로 추가하지 않았다.

B의 나머지 review8건, CLEAR14건의 실제 질문 회피, 최종 제출/UI·전체모임분석·운영 분포의 질문품질은 미검증으로 남는다. A에서 CLEAR군의 불필요B 경로를 측정해도 실제 불필요 질문의 발생률로 바꾸지 않는다. A 기술/API/refusal/schema/usage/timeout/지문/IO오류는STOP·나머지UNRUN으로 B자동진행을 막는다. A 의미FAIL은 그대로 보고하며, 결과 전 정한 독립B2 진단은 의미오답만으로 바꾸거나 재시도하지 않는다. B도 기술실패에서 전체STOP하며 다른 사례로 채우지 않는다.

[사전 집계/노출·라벨조정 기록](verification/issue130-context-holdout-preregistration.json), [이전16 원인 분석](verification/issue130-context-failure-analysis.md), [공식계약 검토](verification/issue130-decisions-contract-review.md)를 따른다. 새 지침1448B/190words는 독립정책리뷰를 통과했고 사례별 예외를 추가하지 않았다. 이 사전계획 당시 source/runtime/실행기 무과금 검증과 실행 전 독립수용은 진행 중이었다. 이후 검증과 실제 구성 중단·수정 경과는 맨 위 최신 구간과 구현 계획에 기록한다.

<a id="history-issue-130-representative-decisions-evaluation--이전-완료-실측16-tls-검증-완료-raw-품질-기준-fail"></a>

## 이전 완료 실측16: TLS 검증 완료, raw 품질 기준 FAIL

후속 사용자가 승인한 환경 CA1개를 평가 client 메모리에만 추가하고 정상 TLS·hostname 검증을 확인했다. 전역/JDK/OS/운영/Secret을 변경하지 않았다. 무인증 HEAD1의 HTTP421은 전송 경로만 확인한 증거다. 내부 상세 인증·TLS 진단과 실제 인증서 지문은 게시하지 않는다.

그 뒤 별도로 봉인한 계획으로 root가 실제 A16건을 단회 실행했다. HTTP200·유효 raw 분류·정규화 경로·완전 usage가 각각16, technical/refusal/UNRUN0·B/count/retry0이다. 실행 당시 Git HEAD는28942957이며 미커밋 helper Java c5b07a6f/Python34b14414의 정확 source271/runtime1403/계획 a6a58bb2를 별도 고정했다. 게시 후 HEAD를 실행 당시 코드로 소급하지 않는다.

| 사전 gold | raw CLEAR | raw NEEDS_REVIEW |
| --- | ---: | ---: |
| CLEAR8 | 7 | 1 |
| NEEDS_REVIEW8 | 6 | 2 |

raw 일치는9/16이고 검토 필요8건 중 raw CLEAR 누락6건이다. 사전 zero-observed-false-CLEAR raw 기준은 **FAIL**이다. 고정 진단 임계값.8은13건을 LOW_CONFIDENCE로 유보해 CLEAR_STOP3/B_REQUIRED_NOT_RUN13을 만들었다. 검토 필요군의 unsafe early CLEAR0/8이 raw miss6/8을 지우지 않으며, 명확한 군8건 중5건은 불필요한 B 경로로 갔다. 이5건은 실제 잘못 생성된 질문5건이라는 뜻이 아니다.

B gold는 질문 금지8·선택 질문 필수7·SAFE_EDIT_ONLY1이다. 검토 필요8건을 모두 선택 질문 필수로 바꾸지 않았다. 실제 B를 실행하지 않아 생성 질문·선택지·직접 수정·최종 제출·UI 품질 분모는0이다. raw 대조쌍 구분1/8과 정규화 경로 구분3/8도 별도 기록한다. 입력·gold·threshold·prompt·채점 기준을 결과에 맞춰 변경하지 않았다. 이전 실모델 결과와 source/prompt 노출이 있는 담당자가 작성한 보완 대조집합이며, 원래 개발60/holdout60에서 선택한 사례0·모집단 무작위 표본이 아니다.

반환 usage input8395/output0에 고정 공식 입력단가만 적용한 추정은USD0.0008395다. invoice·완전 청구액이 아니며 기존 UNKNOWN과 두 배치 전액 예약을 합한 **USD1.79322880/여유USD0.20677120/환급0**을 보존한다. controlled A+local route 구간은108.483095–1092.399537ms, suite3827.969504ms/root guard6693.675814ms다. 모델 내부/HTTP/adapter 별 시간과 운영 p50/p95를 추정하지 않는다.

기존169·CA66·JDK16 합성 검증, 새 CA FI6/JDK FI3 탐지·정확 복구가 완료됐다. 필수 guard source574 전후동일/exit0이며 기존1717 XML을 재사용했다. 기존 두 CI 실패와 첫 연결 중단 기록은 그대로 보존한다. 이후 정확 게시 HEAD CI는 별도 확인한다. 분류 품질 FAIL·B 미검증·운영 threshold/정책 미결 때문에 #123 품질·운영 활성화는 미완료다. 다음 유료 호출을 이 결과로 자동 승인하지 않는다.

[실행 증거](verification/issue130-client-ca-execution.json) · [독립 집계](verification/issue130-client-ca-aggregate-result.json) · [독립 감사](verification/issue130-client-ca-result-audit.md) · [client CA/JDK 검증](verification/issue130-client-environment-ca.md)

이하 첫 실행과 proxy/관측 보완은 당시 상태를 보존한 역사 기록이다. 현재 상태를 과거 단계의 CA 미구현·의미 분모0과 혼동하지 않는다.

<a id="history-issue-130-representative-decisions-evaluation--도구-검증"></a>

## 도구 검증

독립 RED 뒤 최소 helper 구현을 진행했다. 첫100 실행의 98PASS/author fixture 오류2는 원본과 정확한 보정 차이를 보존했다. 프로세스 시작으로 제한시간을 소진하면 자식 종료가 빠질 수 있는 결함은 별도 유효 assertion RED 뒤 최소 수정했다. 파일 생성 후 chmod 전 노출 창도 별도 RED로 재현하고 생성 시점 POSIX0600으로 수정했다. 기존 기대값을 약화하지 않았다.

최종 합성102/102 PASS, 독립 결함 주입7종/실제 assertion8 탐지·각 정확 복구를 확인했다. 실제 Python CLI→javac→child Java→production classifier/parser/TwoStage→canonical URL의 loopback TLS 합성16개도 통과했다. 동일 계획 재실행은 추가 HTTP0, 무인자 dry run은 network0이다. 합성 실행 시간은 실제 모델 지연이 아니다. 근거는 `.tdd/verification/issue130-harness-{final-green,independent-faults,whole-cli,independent-final}.json`이다.

<a id="history-issue-130-representative-decisions-evaluation--사전-실행-경계"></a>

## 사전 실행 경계

고정 A 최대16, 순차1, 사례15초·wrapper 시작/컴파일을 포함한 전체300초, request/response 각16KiB, 원문500자, count/B/retry/redirect/fallback0이다. 기술 오류·거절·usage 불명·시간/예산/지문 실패는 전체 STOP, 의미 오답·낮은 확신만으로 표본을 바꾸거나 중단하지 않는다. 실제 TwoStage의 B_REQUIRED_NOT_RUN은 생성 질문 성공을 뜻하지 않는다. 0.8은 기존 진단 fixture이며 운영 threshold는 null/OFF다.

실행 계획 SHA `b381bb4cd2b7a043d04a900bdb2b213a53e5b51e8f415229b7a80e5bff9a585b`, 독립 실행전 ACCEPTED SHA `9de93a0af1af9595ba4c4fb29cf32290fde3867934059784c26c0c3281a73dca`를 고정했다. 현재 source235·compiled/runtime/공개 사전규약 등1148파일을 검증했다. 공개 사전규약 manifest `863c039f2a8ee9d7bdf0043de0b4ee4f7ad39e4f5b3c8ec9c61f1cd31a948682`, prepared index SHA `815054af175b5580270c0ae4d7694533b6b25b5673a3b54b7fce4aa60c6410cf`다. 개인정보 원본은 저장소에 게시하지 않는다.

<a id="history-issue-130-representative-decisions-evaluation--실제-단회-결과-연결-중단-의미-평가-불가"></a>

## 실제 단회 결과: 연결 중단, 의미 평가 불가

root가 정확 계획으로 한 번 실행했다. 첫 사례 STARTED/UNKNOWN_HELD 뒤 CONNECTION으로 중단했고, 바깥 wrapper는 CONFIGURATION으로 보고했다. 두 실패 수준을 구별한다. 관측 network interceptor 진입0·HTTP status0·응답0·분류0, 나머지15는 UNRUN이다. interceptor 이전 실패에서 이0은 공급자 도달/청구0 증명이 아니다. 추가 호출·재시도는 하지 않았다.

raw 의미/정규화 routing의 채점 분모는 각각0이다. 계획 분모16을 유지하면서 error1/UNRUN15를 별도 보고한다. 오탐·미탐·정확도·질문 품질을 계산하지 않는다. 기존 실제 단일 사례 raw CLEAR/0.06 불일치와 과거 성공/실패 기록도 그대로 남으며 새 평가 성공으로 대체하지 않는다.

관측 시간은 실패 사례의 A+local route 합산100.397499ms, JVM suite541.536936ms, root process4387.52ms다. 모델 응답 지연이나 stage별 시간으로 해석하지 않으며 p50/p95는 미산출이다. 실행 후 같은 JDK의 기본 ProxySelector가 DIRECT이며 DNS 조회가 UnknownHostException인 것을 비유료로 확인했다. 기존 환경 proxy를 사용한 Python 무인증 HEAD는 HTTP421을 받았으나 이는 gateway일 수 있어 모델 가용성 증명이 아니다. 원래 실패의 정확 exception 단계·공급자 도달 여부를 소급 확정하지 않는다.

<a id="history-issue-130-representative-decisions-evaluation--비용과-남은-범위"></a>

## 비용과 남은 범위

기존 UNKNOWN 포함 예약 USD1.33185536을 보존하고 실행 전 16×USD0.01441792=USD0.23068672를 추가 예약했다. **합계 USD1.56254208·계획 여유 USD0.43745792·환급0**을 유지한다. 새 usage는 전부 불명이며 응답 없는 STARTED 사례를 무료로 처리하지 않는다. 65536 input token과 2.2배는 inherited engineering proxy로서 공식 invoice hard cap 증명이 아니다. [비용 사전 독립 검토](verification/issue130-representative-decisions-cost-review.md)를 따른다.

독립 결과 집계 SHA `a1d407aa6de6d9920bba4a87e90e3cb168d97cde6d143138f040634ba8e96e7c`는 분류 분모0과 exposure UNKNOWN을 확인했다. 이 결과 감사는 추가 실제 실행의 승인 근거가 아니다. 대표 의미 평가·운영 임계값/실제 가격·전체 앱에서 실모델과 금융 authority 관통·운영 활성화는 미완료이며 #123을 닫지 않는다. CI에는 키를 비운102와 별도 proxy63 합성 검증을 연결했다. 최초 helper의 필수 guard는 ktlintCheck/assemble/test 및 hook self-tests exit0·새214 suites/1717중1713PASS+기존4SKIP·source235전후동일(454.85초)로 완료했다. 후속 proxy source의 필수 guard도 exit0/source566 전후동일(11.398초)로 통과했다. Gradle은 기존 XML1717(1713PASS/기존4SKIP)을 재사용했으므로 새로운 fresh1717 실행으로 부르지 않는다. 새 proxy 독립63+기존102/FI8은 별도 실제 실행 결과다. 게시 리뷰·commit/push·정확 HEAD CI는 아직 미완료다.

실행 후 독립 결과 감사도 보고 정합 PASS·품질평가 불가로 봉인했다(SHA8396cba8f8d7b3aa72bdb0cd999ee53c56360388ed50146217724ddde173c966). 추가 실행을 허용한 판정이 아니다.


<a id="history-issue-130-representative-decisions-evaluation--환경-proxy-보완과-tls-확인-대기"></a>

## 환경 proxy 보완과 TLS 확인 대기

환경별 차이를 평가 도구에 한정해 명시적 `transportMode: ENVIRONMENT_HTTPS_PROXY`로 보완했다. 기존 실행은 변경하지 않으며 새 mode만 엄격한 HTTP CONNECT 환경 proxy를 사용한다. malformed/빈 하위 env·userinfo·query/fragment·허용하지 않은 scheme은 연결 전 거부하고 DIRECT fallback·재시도·redirect는 없다. 현재 helper Java SHA c2439ff232fa2eda252c903ebd4cda00f51685c0b1aeca48a11bcce515c5ffdc, Python SHA b2f01a76fc2384f29c8eecf4658ff4f41f9096d844822a005f26213914c3f409다. 원본 helper102 source/evidence는 `.tdd/history/issue130-environment-proxy/original-102/`에 보존했다.

새 독립63은 valid assertion RED42/controls21 뒤63 GREEN, 기존102 unchanged GREEN이며 독립 FI8 모두 assertion 탐지/정확 복구 GREEN이다. 새 mode는 실제 첫 N16 예약 증거의 SHA/상태/정수 refunds0를 요구하고 previous USD1.56254208을 고정한다. 과거1.33185536으로 되돌린 계획을 거부한다. 새16회 최대 예약 제안은0.23068672를 더해1.79322880/여유0.20677120이며 아직 예약하거나 실행하지 않았다. 선택 원문·gold·순서·의미 oracle 변경0이다.

같은 새 factory에서 Authorization/body/key 없이 HEAD 최대1회만 실행한 결과 proxy DNS/connect/TLS start는 관측했으나 `SSLHandshakeException`으로 TLS 미완료·HTTP status 미관측이었다. 상세 nested cause/peer chain은 기록하지 않아 누락 CA가 정확 원인이라고 확정하지 않는다. 공개 CA 메타데이터만 비교한 결과 기존 JDK118·환경 bundle154·공통112/환경만42/JDK만6이며 합집합160이다. 기존 공개 환경 CA를 명시적 지문으로 평가 client 한정 상속하는 방안은 신뢰 anchor 확장이므로 보안 설정 제외 승인 범위에서 자동 진행하지 않는다. 해당 선택에 대한 명시 답변 대기이며 CA 코드·전역 JDK/OS/운영 설정 변경·추가 유료 호출0이다.

승인 시에도 기존 chain/hostname 검증을 유지하고 새 독립 TLS 양성/음성·오염 bundle 거절·기존 default roots 보존 검사와 FI, 무인증 확인, 최신 source/runtime/비용/실행전 독립 리뷰를 거친 새 single-use 계획만 실행할 수 있다. 답변 전에는 현재 hold1.56254208/여유0.43745792/환급0을 그대로 유지한다. 실제 오탐·미탐/정확도/질문 품질/p50/p95는 여전히 미측정이다.


<a id="history-issue-130-representative-decisions-evaluation--첫-게시-ci의-단일-합성-실패와-관측-보완"></a>

## 첫 게시 CI의 단일 합성 실패와 관측 보완

Draft PR132의 최초 HEAD62f47bb/CI39123972026은 ARM64와 Gradle 빌드 및 기존102 합성을 통과했지만 proxy Java49 중48PASS/assertion1FAIL(Python14PASS)에서 중단했다. 첫 로그에는 개별 실패 ID가 없어 원인을 확정할 수 없다. 동일 테스트의 깨끗한 CI 유사 로컬 실행1회와 관측 보완 뒤 실행은 각각63PASS였으나 첫 실패를 성공으로 소급하지 않는다.

원래49/14 입력·기대값·TLS fixture 및 helper2개는 그대로 두었다. 별도 privacy4 RED→GREEN으로 확인한 고정49 test ID·상태·허용 오류 종류만 출력한다. raw 오류 설명·환경·body·key를 로그로 복사하지 않는다. 테스트 timeout이나 oracle을 바꾸지 않았으며 첫 CI 실패를 해결했다고 주장하지 않는다. 다음 정확 HEAD CI에서 실제 실패 ID를 확인할 수 있게 하는 관측 보완이다.

후속 mandatory guard는 source567 전후동일/exit0·8.718초로 통과했다. 기존1717 XML 재사용이며 새 fresh1717 실행은 아니다. 실제 평가·CA 신뢰·전역/운영 설정·유료 호출에는 변화가 없고 hold1.56254208/여유0.43745792/환급0을 유지한다.

</details>
