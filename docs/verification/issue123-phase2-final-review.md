# #123 Phase2 최종 독립 리뷰

2026-10-18 UTC. reviewer `/root/review_pre_submit_design`; 구현·독립 oracle 작성과 분리된 read-only source/test/evidence 검토. 이 리뷰 파일만 작성한다. **최종 CODE GATE PASS: 현재 source/test·독립 RGR/FI·변경 없는 전체 guard 검토를 통과했고 미해결 P1/P2는 없다.** 봉인 시점 실제 Decisions 총4회이며 추가 실제2단계 진단은 아직 미실행이다. 그 별도 실행 준비는 /tmp harness와 문서에 한정해 제품·테스트 변경0으로 구분한다. 실제 결과의 별도 의미/문서 리뷰와 최종 PR 본문/완료 기술 scope issue 정렬 뒤 게시 판정을 연결한다. 그 다음 단계의 준비 상태를 현재 source review 실패로 처리하거나 새 사용자 승인 체계로 만들지 않는다.

## Source와 닫힌 발견

[Core 최종 로컬 리뷰](issue123-core-final-local-review.md), [실제 DB fence](issue123-execution-fence-source-review.md), [orchestration](issue123-orchestration-source-review.md), [전송 시각 보완](issue123-dispatch-write-review.md)을 합쳐 확인했다. 이전 source 발견은 모두 최소 수정과 독립 supplemental RED/GREEN으로 닫혔다.

- core의 foreign reservation 선잠금, profile 대기 뒤 새 quote expiry, aggregate overflow의 known bill C 보존: 현재 service `9c2fb6df…`, JDBC `2584914d…`에서 해결됐다. strict settled+held+Q<cap·immutable replay·UNKNOWN fullhold·완전 bill만 정산·NUMERIC38의 정직한 freeze를 유지한다.
- gate의 전송 직전 fresh wall 및 permit-tightened monotonic 누락: 현재 gate `25c2dbe5…`에서 해결됐다. 연결 전 reserve/claim과 마지막 request bytes one-shot/hash/endpoint 검사, 같은 Call follow-up/두 번째 SDK Call 차단은 별도 경계다. frozen captured bytes를 실제로 보내며 mutable RequestBody를 다시 평가하지 않는다.
- 실제 fence `9a816338…`는 room→현재 check/execution 또는 run/invocation/attempt→core budget 잠금 순서를 지킨다. 기존 ownership/binding/current revision·kind별 closure·runVersion0·invocation provider ordinal·15/27/전체60 및 coverage 시작을 검증한다. 같은 방을 revision/HOST retry/DLQ별 새 account로 만들지 않는다.
- mandatory managed gate, 순수 application mode port, ON bounded composition 조기 CONFIGURATION, OFF nullable legacy 경로, 미사용 fallback key로 정상 primary를 막지 않는 조건과 Worker/Processor의 정확 context/anchor를 확인했다. V17은 과거 HISTORICAL_UNKNOWN/신규 UNCONFIGURED, structural-only global profile 및 방 소유 요청/관측 cascade로 기존30일 삭제를 유지한다.

현재 실제 가격·cap·금융 authority/coverage·운영 confidence 활성화 경로는 제공하지 않는다. 생산 registry는 pure offline default DENY이고 flags는 기본 OFF다. 원문500/기존 실행·질문 한도/last CLEAR/참여·마감·최종 방 전체 AREA 해석과 matching 정책을 바꾸지 않는다. 기술 원장을 실제 비용10원 충족·운영 준비 완료로 확대하지 않는다.

## 독립 검증과 FI

core94/FI4 및 실제 PG9+whole1은 위 core 최종 리뷰에서 raw XML/log·exact patch·복구 SHA까지 확인했다. 이번에는 provider 최종 canonical과 raw evidence를 직접 확인했다.

| 경계 | 실제 결함 탐지 | 복구 |
| --- | --- | --- |
| ON admission/claim 우회 | unchanged denial parameter5개 assertion FAIL | 동일5 PASS |
| last-write wall/effective monotonic 삭제 |2 assertion FAIL |2 PASS |
| whole-body hash 삭제 | same UTF8 length 신규 oracle1 assertion FAIL |1 PASS |
| Worker context 누락 | ON case1 assertion FAIL, OFF/ABSENT controls 유지 |3 PASS |
| final persisted context 누락 | 실제 PG lifecycle/whole10 중6 assertion FAIL |10 PASS |

각 변이는 errors0/skip0이며 source 변이를 current source 문자열에 메모리로 재구성해 exact mutantSHA5와 모두 일치함을 확인했다. tracked canonical source/test SHA도 current와 일치한다. baseline109 및 final109-restored는 보관 XML12개를 직접 합산해 각각 **109 PASS/실패·오류·skip0**이다. 과거78/80/98/108 실행을109에 더하지 않는다. original canonical476 inventory는109 baseline→final 및 당시 source 검토 시점에 같았다. 이후 아래 legacy upgrade oracle1파일이 별도 정합 보정되었으므로 이 역사 inventory를 최신 전체 tree와 동일하다고 주장하지 않는다. 선택 source/new test 지문과 FI 결과는 그대로다.

FI의 실제 oracle gap을 숨기지 않았다. 기존 다른 길이 body mutation은 OkHttp Content-Length 검사로 거부되어 hash 삭제 mutant에서 원래 Gate27이 모두 PASS(**SURVIVED**)했다. 원래 product/frozen27을 바꾸지 않고 같은 UTF8 길이 A→B의 독립 테스트1개를 추가했다. 이 테스트는 최초1 PASS→같은 hash 삭제1 assertion FAIL→exact restore1 PASS다. [gap supplement](../../.tdd/history/issue123-dispatch-body-oracle-gap/supplement.json)는 원 생존을 보존하고 신규 검증을 별도 연결한다. 이를 새 제품 수정이나 chronological RED로 만들지 않는다.

첫 broad admission mutant의 따라오는 missing-reservation trace/setup성 오류도 원본에 보존하고 canonical 탐지는 unchanged public denial5에 한정했다. DLQ의 outbox created_at→정본 occurred_at fixture1token은 기대값/제품 변경 없이 보존됐고 corrected TM06의 context 누락 탐지는 **post-implementation RECONSTRUCTED supplement/FI**다. [supersession](../../.tdd/history/issue123-dispatch-integration-outbox-fixture/supersession.json)은 원 chronological27과 unchanged managed/Worker/legacy subset, corrected testSHA와 재구성 근거를 분리한다. whole-room1은 최초 PASS이며 사후 FI에서만 의도적 실패가 있었다. 이력의 setup 실패를 요구 RED로 세지 않았다.

같은 실제 PG 방의 개인 A/count/B3 + 둘째 A1 → expected2 자동마감 → 전체 final SDK1이 같은 epoch/원장1개/execution3개/물리예약5개/held50 TEST_SUBUNIT/settled0으로 이어진다. loopback·synthetic quote/coverage로 검증했으며 실제 청구 금액/운영 권위 증거는 아니다.

## 실제 기록 응답 회귀2

[기록 응답 회귀](../../.tdd/verification/issue123-recorded-low-confidence-pipeline.md)와 receipt/raw XML·source inventory를 직접 확인했다. 새 test SHA `487ed1ecf4950cf4da8c18169ba734846ae5239e27e4a015cd5b9c705d4dfb6c`, recorded response SHA `7f6d7d7267a016e5f4b08426421936725122f674e6158e030abde4b1bd01609c`다. actual classifier loopback→actual TwoStage가 raw CLEAR/confidence0.06을 INDETERMINATE/LOW_CONFIDENCE로 해석하고 recording fake generator를 각1회만 호출해 NEEDS_CHOICE/재CLEAR의 결과·metadata를 보존한다. CLEAR probability0.37과 confidence0.06을 동일시하지 않는다.

threshold0.8은 Git e83d5280의 기존 `DecisionClassificationFixtures.kt:118`이고 SHA `e8bd75698e37cc4b9cbeb9c625656045ad3a1e6590dcdecf8a5568dc4f9bb197`가 current와 같음을 git show/hash로 직접 확인했다. 실제 진단 결과를 본 뒤 고른 운영 임계값이 아니다. 기본 runtime confidence null/OFF는 유지된다.

XML은2 PASS/실패·오류·skip0이고 그 회귀 배치의 source477 before=after 및 당시 current가 같았다. 이후 legacy upgrade oracle1파일 정합은 별도 이력으로 분리하며 새 full guard manifest에서 최신 전체 tree를 확인한다. 이 새 파일은109/FI5 봉인 후 추가되어 별도 **REGRESSION_INITIAL_PASS**로 관리한다. 실제 API 추가0, generator/budget synthetic이며 real two-model 성공·확률 calibration·새 운영 정책/정확도 증거가 아니다. 기존 실모델 diagnostic raw label 불일치를 재분류 성공으로 덮어쓰지 않는다.

## 실제 모델·문서·게시 범위

실제 Decisions 진단은 총4회(추가0), retry/count/Gemini/fallback0이며 raw CLEAR/confidence0.06이 사전 NEEDS_REVIEW와 달라 **FAIL/STOP**, 마지막 부정·예외 사례 미호출을 유지한다. HTTP200/production parser offline 통과와 의미 정답 통과를 구분한다. threshold0/가정0.1 offline 예시는 운영 threshold가 아니며 confidence의 타 공급자 수식·1-p를 가져오지 않는다. 단일 사례 지연/known usage는 관측값이고 p50/p95·정확도·invoice/세금/최대비용 보장은 미검증이다.

현재 PRD/Architecture/ADR055/계약/plan/Worklog의 frozen 범위·실제 모델 진단 한계는 일치한다. 이전 source/기록 리뷰의 당시 pending/0call 수치는 시간순 이력으로 보존하고 현재 상태는 최신 기록이 우선한다. [catalog 독립 리뷰](../../.tdd/verification/issue123-v17-catalog-review.md)와 actual-source catalog72/current 지문도 참고했다. migration17과 runtime approval17 파일을 혼동하지 않으며 과거V16 reader/기존 release 승인 경계를 보존한다. CI workflow나 보호 규칙을 완화하지 않았다.

게시 예정 PR은 `.github/pull_request_template.md`의 정확6절(변경 목적/변경 내용/검증/리뷰 요청/영향 범위와 주의사항/관련 이슈)을 사용해야 한다. 현재 준비 본문은6절이나 검증/issue가 준비 placeholder라 이 상태로 게시하지 않는다. root가 full guard 실제 결과 및 실제 완료 기술 범위의 열린 issue를 연결한 뒤 재검토한다. broad #123의 실제 비용·운영 평가 미완료를 Closes로 지우거나 phase1 #124를 phase2로 다시 닫지 않는다. #107/#123과 PR122의 상속·미병합 경계를 명시한다.

사용자 기존 승인 범위는 최종 검증 후 commit/push/Draft/정확 HEAD CI까지이며 Decisions develop/main 병합·배포/운영 DB/OAuth/Secret/IAM 변경은 포함하지 않는다. 원격 CI는 게시 후 exact HEAD 결과를 확인할 별도 상태이며 로컬 테스트를 CI 성공으로 표시하지 않는다.

## 전체 guard 및 최종 코드 판정

최초 root 원본 `.codex/hooks/tdd_guard.py` 실행은 exit2, fresh212 suites/1684 tests 중1679 PASS·1 FAIL·기존4 SKIP·errors0이었다. 556개 검증 대상 파일은 before=after였다. 유일 실패는 `IntegratedLegacyUpgradePostgresTest`의 ai_room_budgets0 기대와 V17의 확정된 기존3room HISTORICAL_UNKNOWN seed 사이 oracle 계약 충돌이다. 최초 실패/지문/XML을 `.tdd/history/issue123-first-full-guard`와 로컬 first-guard-failed에 보존했고 통과/제품 missing-behavior RED로 세지 않았다.

독립 oracle 정합(current SHA `7f9b7d37dbd8cd88f6ac59139eeb760427c87c0a09c82bd738e595ab58c5d505`)은 제품/SQL 변경0이다. exact3 budget rows 모두 HISTORICAL_UNKNOWN·epoch/cap/unit code·scale/policy/coverage NULL·held/settled0·예약/관측/profile0을 요구한다. exact diff를 메모리 역적용한 SHA가 원본 `66848faaa278229a8e1addfa2533029beaf22fe7a1e9b460ffc5a856bbe87b6c`와 같아 snapshot/auth/attempt/confirmation/round 검증이 byte 보존됨을 확인했다. 독립 focused2 및 ktlintCheck가26초에 첫 PASS했으며 이력은 REGRESSION_INITIAL_PASS다. 이전94/109/recorded2의 전체 inventory는 당시 봉인이며 이1파일 정합 이후 latest current 전체 지문은 아래 새 guard만으로 확인한다. 새 독립 테스트26개/제품 source는 바뀌지 않았다.

새 전체 guard는 **2026-10-18T05:29:32.329045Z–05:36:40.346667Z, exit0**이다. `.tdd/verification/issue123-durable-budget-final-gate.json`과 manifest/raw XML 지문을 직접 확인했다.

- 원본 hook은 Git HEAD와 동일하고 self-tests·ktlintCheck·assemble·test가 통과했다. guard log는 정상 quiet empty SHA `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`다.
- fresh XML212개를 직접 합산해 **1684 tests =1680 PASS +기존4 SKIP, 실패0/오류0**을 확인했다. 모든 XML이 guard 시작 이후 생성됐고 xml-manifest SHA와 current bytes가 같다.
- 수정된 legacy oracle을 포함한 source/build/deploy/CI/hook 등556개는 before=after=manifest=current로 전부 일치한다. source manifest SHA는 `3103634acd501621a7133b3168460570d47334672724a2d95cfadc751c2a2072`다.
- guard subprocess는 API key를 비운 synthetic/local 검증이며 실모델 추가0이다. 별도 기존 진단 총4회와 혼동하지 않는다. 가격/운영 authority·운영 threshold·활성화 미완료 경계는 유지한다.

따라서 현재 코드·테스트·독립 FI·전체 guard의 **최종 로컬 CODE GATE PASS**를 확정한다. 이 reviewer는 guard 재실행/source/test/branch/git 편집·실제 API/계정·운영 접근을 하지 않았다. 현 review 파일만 작성했다. 부모가 guard+리뷰 후 별도 승인 범위의 실제 소액2단계 진단을 진행하더라도 제품/테스트를 변경하지 않으면 이 source 봉인은 유지된다. 그 실행 결과는 별도 의미/문서 검토로 연결하고 PR6절/실제 완료 scope issue·commit/push/Draft/exact HEAD CI와 Decisions 미병합/미배포는 다음 게시 단계의 확인 대상이다. 현재 아직 실행하지 않은 실제2단계 성공이나 원격 게시/CI 성공을 미리 선언하지 않는다.

## 증거 SHA256

- `.tdd/verification/issue123-all-ai-dispatch-faults.json`: `8defe83bf9bdfc1b26d58488d45d0ad1dc7cc6d43d57fb127cb9031187ac70ff`
- `.tdd/verification/issue123-all-ai-dispatch-final.json`: `021b4548779384e54a9b206757f27f857ae2ade509e6ff65375f580d9bf07300`
- `.tdd/history/issue123-all-dispatch-fi/detailed-receipt.json`: `4d6b5576b3a34aacf9745e9d65af946e42b44874d10ae82cbbe5d10a93cf0d7d`
- `.tdd/history/issue123-dispatch-body-oracle-gap/supplement.json`: `49ab06b14e1f2dbdfeeb041eca303e2ca7dd06a11d0cffad0afd827bc57ea213`
- `.tdd/history/issue123-dispatch-integration-outbox-fixture/supersession.json`: `6698d4cedbc358cae9cb9ae1caafe75658c7cd9b1b6bddccd2db8a7f4745bec9`
- `.tdd/history/issue123-recorded-low-confidence-pipeline/receipt.json`: `4054441f0e92ce2a1f1535ea4d1156d127964e55c14927b009c9e68a13d54ada`
- `.tdd/verification/issue123-budget-core-fence-fi.json`: `1b14aab07d4e7e63910918caae9037aa7668d0308c6425fb2e8a0fd8a03c2a53`

Source 핵심 exact SHA는 각 canonical의 sourceFiles와 위 source 리뷰에 고정했고 current와 직접 대조했다. 이 리뷰의 source/test/git/Gradle/외부 모델/계정·운영 접근은 모두0이다.

- final gate receipt `.tdd/verification/issue123-durable-budget-final-gate.json`: `ccf4d3cc36d58bb7d656e308f93738fa5dcb79f977de7e3c745c78380224f792`
- final source manifest `.tdd/verification/issue123-durable-budget-source-manifest.json`: `3103634acd501621a7133b3168460570d47334672724a2d95cfadc751c2a2072`
