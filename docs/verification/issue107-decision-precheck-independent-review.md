# #107 Decisions 2단계 독립 최종 검토

2026-10-18 UTC. reviewer는 classifier/pipeline 제품·테스트 및 평가 corpus 작성자와 분리됐다. 기준 HEAD는 `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`다. 이 파일 외 source/tests/문서/branch/stage/commit/push를 변경하지 않았다. 실제 모델·키/Secret·OAuth·운영/SSM·유료 호출은 하지 않았다.

## 현재 판정

**최종 독립 로컬 게시 판정: PASS.** source·공개 계약·독립 RED/GREEN/FI와 새 필수 전체 guard를 통과했고 미해결 P1/P2는 없다. 사용자가 승인한 commit/push·표준 템플릿의 Draft PR·정확 HEAD CI 확인 단계로 진행할 수 있다. 아직 원격 게시/CI 성공을 주장하지 않으며 운영 활성화·정확도/청구 상한·main/develop 병합·배포 승인이 아니다. Decisions는 develop/main에 병합하지 않는다.

## 교차 계약 발견 사항

- **P2 / 해결:** 외부 probability를 `USE_BIG_DECIMAL_FOR_FLOATS`로 읽고 precision/scale의 자원 상한 없이 분포 합계·차이를 계산해, 작은 JSON의 `1e-500000000,1,0`도 긴 계산/OOME를 유발할 수 있었다. body16KiB·HTTP deadline은 계산 크기를 제한하지 않고 OOME는 `catch (Exception)` 밖이다. reviewer는 실제 Jackson3.1.5 reader가 scale999999999를 허용하고 별도 `jshell -R-Xmx64m`·외부 timeout15초의 BigDecimal add가 OOME를 냄을 확인했다(Gradle/source 변경0, TDD RED나 모델 실측 아님). DC06을 내부 표현 precision≤1000·scale -1000..1000으로 보완했고, 실제 helper는 decimalValue 획득 직후 직접 범위 검사 후 finite/값 비교/합계/argmax로 진행한다. `abs` overflow·runtime mapper 제약 변경0이며 정확 tolerance를 유지한다. reviewer가 보관 before/current diff로 helper 한 곳만 바뀜을 확인했다(현재 SHA `c45fa35d…`). 독립 저비용 RED34/실패16→GREEN160 및 cap 제거 FI16 assertion 탐지→정확 원복160과 최종 전체 guard를 확인했다. OOM급 수는 Gradle에서 실행하지 않았다.
- **P2 / 해결:** A 단독 CLEAR가 Decisions의 `outputTokens`를 기존 `PrecheckProviderResult`로 그대로 반환해 기존 worker의 Gemini 생성 profile `outputTokens <= 2048`과 충돌했다. Decisions는 이 출력 상한을 요청하지 않아 합법적인 known output2049인 충분 CLEAR가 FAILED, missing usage는 CLEAR가 되는 비대칭이었다. 실제 대량 usage 측정 주장은 아니다. TS08을 보완하고 A의 terminal output만 null/N/A로 반환하며 actual stage usage는 budget/metrics에 보존하도록 최소 수정했다. reviewer는 수정 전 scratch와 직접 비교해 주석+한 값 변경만 확인했다(bridge SHA `d276541d…`). worker/B의 2048·음수 검증과 게시 fencing은 변경0이다. 보완 RED79/실패3→GREEN79 및 FI7의 assertion2 탐지·정확 원복6 GREEN과 최종 전체 guard를 XML로 직접 확인했다.
- **비차단 한계:** malformed response에서 이미 읽은 known usage는 classifier metrics에 보존되지만 failureCode 전용 exception을 거치면 bridge budget observation에는 unknown으로 남는다. UNKNOWN hold를 풀지 않아 현재 default DENY prototype의 비용 안전성을 낮추지는 않는다. 모든 실패의 known usage가 stage ledger로 전달된다는 주장은 하지 않으며 durable ledger 구현 시 safe typed failure usage 전파를 검토해야 한다.
- **비차단 내부 경계:** bridge는 generation0을 허용하지만 실제 worker/DB generation은 양수다. 외부 입력 경로나 실제 worker 우회는 없고 제품 정책 변경은 아니다. 향후 prototype request 경계 정리 시 별도 oracle로 0의 의미를 고정할 수 있다.

## 계약과 문서 검토

- AGENTS와 관련 `.agents` 규칙·project-architecture 스킬, PRD·Architecture·ADR-053/054, DC01–12·TS01–12, 누적 예산 설계 및 평가/외부 준비 문서를 대조했다. 최신 승인은 충분한 Decisions CLEAR를 통과시키고 검토 필요/정상 유보를 생성 단계에서 재판정하는 개발 방향이다. API 오류/refusal과 정상 유보를 합치지 않는다.
- 원문/context/anchor를 두 단계에 그대로 전달하며 불용어 제거·정규식 의미 판정·자동 원문 변환·타인 원문 제공·개인 구조화/AREA 병합을 도입하지 않는다. 마감 후 전체 해석·기존 last CLEAR/ownership/revision/generation/deadline 게시 fencing과 선택/직접수정 재검사는 유지한다.
- standalone classifier 자체의 연결 제한과 승인된 bridge/selector의 명시 조합을 구분했다. 충분 CLEAR·NEEDS_REVIEW·정상 MODEL_UNCERTAIN/LOW_CONFIDENCE·REFUSED·invalid의 분기 및 모순 result shape 거부를 명시했다. INDETERMINATE에도 confidence/distribution 형식 검증을 생략하지 않는다.
- 한 요청의 reserve/호출/기록 모두 공통15초를 사용하며 B에는 남은 시간만 전달한다. stage별 provider/model usage를 기록하고 기존 단일 provider 결과에는 terminal stage metadata만 둔다. A+B token을 B모델 요금이나 단일 output2048 한도로 잘못 합치지 않는다.
- 현재 budget 범위는 port/default DENY·reservation 계약과 fake 검증이다. durable PG 원장·모든 final/fallback/HOST retry/correction/DLQ dispatch 연결·역사 unknown 이관·실제 금액/quote 입증·예산 소진 시 참여 행동은 미완료/미확정이다. 이 미완료를 이유로 이번 단계에 V17/전체 최종 파이프 리팩터링을 추가하지 않는다. 실제 활성화 전에는 반드시 별도 완료해야 한다.
- 기본 selector OFF와 기존 provider OFF, selector만 ON이어도 default DENY/outbound0인 경계가 명시됐다. fake quote/`verified` 값은 합성 dispatch 계약이며 실제 청구 근거가 아니다. timeout/refusal/unknown/stale 비용을0/무료 환급으로 만들지 않는다. 금액·운영 임계값·모델 품질·최대 USD를 구현자가 새로 확정하지 않는다.

## 고정 테스트·RED 확인

| 대상 | 직접 대조한 근거 | 결과 |
| --- | --- | --- |
| Classifier 최초 RED | `.tdd/red/issue107-decision-classifier.json`, `.tdd/history/issue107-decision-classifier-red/manifest.json`, 보관 XML3·원본 log SHA | 101실행/요구 assertion 실패99/control2, error/skip0. test/fixture4 현재 SHA 일치 |
| 유보 형식 보완 RED | `.tdd/red/issue107-decision-uncertain-validation.json`, history `uncertain-supplement.json` | 별도 genuine RED7, error0. 최초101 지문을 바꾸지 않고 누락 confidence/distribution의 early return 결함을 검증. test/helper2 SHA 일치 |
| Classifier 집중 GREEN | `.codex/tdd-evidence/issue107-decision-green/TEST-*.xml`7개 직접 재집계, history `first-green.json` | 신규108+legacy18=126, failure/error/skip0. source4/test5 및 원본 log/XML SHA 일치 |
| Pipeline RED | `.tdd/red/issue-107-two-stage-pipeline.json`, 보관 XML4 직접 재집계 | 73실행/요구 assertion 실패71/OFF control2, error/skip0. test4 현재 SHA 일치 |
| Pipeline 집중 GREEN | `.codex/tdd-evidence/issue-107-two-stage-pipeline-green-receipt.json`, XML4 직접 재집계 | 73실행/failure/error/skip0. source 동결7·전체 source231·test4 SHA 일치. 교차 P2 수정 시 새 보완 근거 필요 |
| Classifier 독립 FI | `.tdd/verification/issue107-decision-classifier-fi.json`, history `manifest.json`, raw run.log/XML SHA 직접 대조 | 격리 copy baseline/restored 각126 GREEN. OFF 우회·낮은 confidence 승격·분포합 검증 생략·trickle deadline 연장·503 숨은 추가호출·usage unknown 누락의6변이가 각각 assertion으로 탐지됨(실패1/2/2/1/1/6·error/skip0). source4/test8와 복사 main inventory 현재 SHA 일치, 원본 제품 변이0 |
| Pipeline 최초 독립 FI | `.tdd/verification/issue-107-two-stage-pipeline-faults.json`, scoped raw XML 직접 재집계 및 실행 script의6개 변경식 검토 | quote 검증 우회·단계마다 deadline 재지급·refusal 생성 전환·단계 usage 합산·전체 OFF 우회·worker generation 누락이 assertion15개로 탐지됨(1/9/1/1/1/2, error/skip0). 각 scoped 원복과 final73 GREEN. classifier 형식-only 변경을 감지한 마지막 전체 지문 assertion 중단은 FI 탐지로 세지 않음. 형식 동기화 후 fresh73 GREEN/XML4 및 source231 SHA 일치 확인 |
| TS08 보완 RED | 현재 `.tdd/red/issue-107-two-stage-pipeline.json`, terminal-usage RED XML5 직접 재집계·test5 SHA·보관 old73 test4 SHA 대조 | 79실행/요구 assertion 실패3/나머지76 통과, error/skip0. Routing 단 한 기대값을 supersede하고 실제 worker로 A4/2049·A음수·B-1/2048/2049의6개 새 사례 추가. A actual usage 보존과 B 기존 cap 유지도 확인 |
| TS08 보완 GREEN/FI7 | terminal-usage GREEN receipt/XML5 및 `.tdd/verification/issue-107-two-stage-terminal-usage-fault.json`의 baseline/mutant/restored XML 직접 대조 | GREEN79, failure/error/skip0·source231/test5 SHA 일치. A output을 다시 legacy field로 넣은 변이는6개 중 assertion2 실패/error0이며 baseline6/정확 원복6 GREEN. canonical pipeline FI7field에 최초6과 현재7의 이력을 구분 |
| DC06 수치 자원 RED | `.tdd/red/issue107-decision-numeric-resource.json`, history `issue107-decision-numeric-resource/red.json`, 보관 XML 직접 재집계 | 신규34/정상적인 요구 assertion 실패16/control18, error/skip0·test/helper SHA 일치. CLEAR와 명시 유보의 confidence/probability ±scale1001·precision1001 거부, inclusive1000·정확 tolerance1e-6 허용/초과 거부를 검사. precision 사례의 mapper4096 주입은 새 합성 테스트에만 한정되고 기존5 classifier 파일은 불변 |
| DC06 수치 자원 GREEN/FI | history `issue107-decision-numeric-resource/green.json`, canonical `issue107-decision-numeric-resource-fi.json`, history FI `manifest.json` 및 raw log/XML SHA 직접 대조 | 신규142+legacy18=160 GREEN/XML8. cap 한 줄 제거 변이에서34개 중16 assertion FAIL/18 control PASS/error0, baseline160/정확 원복160 GREEN. canonical source4/test9와 GREEN source/test10 현재 SHA 일치·원본 제품 변이0 |

첫 dependency/JVM proxy 환경 준비 오류는 유효 RED로 집계하지 않는다. 테스트는 schema/wire·원문/context·분포/confidence/유보·오류/usage·한 번 호출/deadline·privacy·실제 composition을 검사한다. pipeline oracle은 reserve 전 outbound0·1+2 보수 bundle·중복/동시 quota·unknown hold·정산 실패·공통 deadline·default OFF/DENY·worker scope를 포함한다. fake 원장은 실제 PG 원자성·실청구 상한 증명이 아니다.

각 행의 지문 일치는 해당 주기의 검토 당시 값이다. 이후 승인된 format-only·TS08·DC06 주기의 변경을 역사적 지문에 소급 반영하지 않는다. 최종 현재 source는 새 전체 guard manifest로 대조해야 한다.

classifier FI 뒤 `AmbiguityClassificationContract.kt` 한 줄의 초과 들여쓰기만 보정했다. reviewer는 FI의 원본 보관 파일과 format-only 파일을 직접 비교해 비공백 bytes 동일을 확인했다(`c77a911e…` → `02423846…`). `.tdd/history/issue107-decision-classifier-red/format-only-contract.json`에 추적하며 원래 RED/GREEN/FI 지문을 소급 수정하지 않았다. 그 뒤 별도 DC06 주기에서 helper만 보완해 현재 `c45fa35d…`가 됐다.

TS08 보완은 기존 Routing oracle의 output4 기대를 null/N/A로 바꾸므로 `.tdd/history/issue-107-two-stage-original73/`의 최초 RED·변경 근거와 로컬 원본 테스트를 보존한다. 기대값 변경 뒤의 보완 RED/GREEN 및 FI는 별도 주기이며 최초73의 기록을 현재 테스트에 소급 적용하지 않는다. pipeline FI의 별도 잘못된 cwd/UP-TO-DATE control은 현재 형식 검증 근거에서 제외됐다.

## 평가·외부 준비의 독립성

- `docs/verification/issue107-decision-evaluation-review.md`의 별도 oracle reviewer는 작성자/프롬프트 구현자와 분리돼120개를 사전 검토했고 확인된 oracle 오류0을 기록했다. 이는 모델 실측 결과가 아니다.
- 이 최종 reviewer는 holdout 개별 본문/정답을 읽지 않았다. development/holdout/기존30의 **파일 SHA만** 평가 리뷰의 값과 대조해 일치함을 확인했다. holdout 예제/개별 정답을 root·프롬프트 구현자에게 전달하지 않았다.
- split별60/전체120 corpus는 호출 승인이 아니다. 별도 고정12개 pilot, 분류≤12/전원B일 때 count+generation을 포함한 전체≤36, retry0/추가 smoke0은 미승인 실행 계획이다. 실제 금액 상한 미입증 상태에서는 실행하지 않는다.
- 첫 단계 false CLEAR가 B의 보정 기회를 없애는 위험, 전원 B 진입의 최악 비용, 정상 넓은 범위·타인 의존·후보0·문맥 해소 대조, 정상 유보와 API 오류 분리, 독립 holdout의 소진·분모/INCOMPLETE/N/A를 명시했다. 모두 abstain해 false CLEAR0만 만든 결과도 품질 통과가 아니다. 실제 confidence 보정·가격/청구·p50/p95/일반화 정확도는 측정하지 않았다.

## 최종 전체 gate와 게시 준비

[최종 전체 gate](../../.tdd/verification/issue107-decision-final-gate.json)는 2026-10-18 03:19:49.809212–03:26:17 UTC의 변경 없는 `python3 .codex/hooks/tdd_guard.py < event.json` 실행 exit0을 기록한다. reviewer는 guard bytes가 기준 HEAD와 동일함을 직접 확인했다. self-tests·ktlintCheck·assemble·test 통과이며 보관 manifest의189개 XML을 직접 다시 집계해1479개 중1475 PASS·기존4 SKIP·failure/error0을 확인했다. 모든 XML의 SHA와 mtime을 대조해 실행 시작 뒤 새 결과임을 확인했다. SKIP4는 기존 진단/OpenAPI export3·live model probe1이며 변경하지 않았다.

[현재 source manifest](../../.tdd/verification/issue107-decision-source-manifest.json)의 SHA는 `d517ec8778310600487a311b0bbd8d622663114e33c5ed3bce766e55c7df766e`다. reviewer는 보관 before/after와 현재512개 behavior 파일을 직접 해시해 모두 같음을 확인했다. 빈 quiet log SHA도 receipt와 일치하며, 빈 로그 자체를 성공 근거로 전용하지 않고 exit/신규 XML/불변 guard와 함께 확인했다. 독립 FI는 최초 classifier6·pipeline6 및 두 보완1개씩의 역사적14종 탐지/원복을 구분하며 모두 최종 source의 동일 시점 변이라고 주장하지 않는다. 최종 전체 검사는 모든 보완을 포함한다.

최신 사용자는 공급자 합산 평가 총액 USD1을 조건부 승인했다. [USD1 실행 계획](../ISSUE_107_USD1_EVALUATION_EXECUTION_PLAN.md)은 이를 반영하며 새5개/최대15HTTP를 제안으로 구분한다. 조건부 `$0.05826560 + count + 세금/부대비용` 산술을 대조했고 최대 청구 상한으로 주장하지 않음을 확인했다. 전체 billed input·count 요금·계정/세금 상한이 미입증이고 runner도 미완료여서 현재 dispatch0/BLOCKED인 결론은 적절하다. 옛12/36회는 역사 제안이며 금액 승인이 그 횟수를 자동 확정하지 않는다. 키·계정·결제 설정을 이 reviewer가 읽거나 바꾸지 않았다.

[공식 재대조 기록](issue107-decisions-official-contract-recheck.md)을 현재 source와 대조하고 1차 문서를 직접 다시 확인했다. 문자열 input과 choice는 [create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)의 지원 형식이며, 별도 confidence와 named refusal은 [response reference](https://developers.openai.com/api/reference/resources/decisions)의 구조와 일치한다. predicate 예제를 이유로 SDK/wire를 바꿀 필요는 없다. [동일 GenerateContent thinking 문서](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens)는 thought 포함 출력 cutoff를 뒷받침하지만 입력 청구 상한/실제 계정 지원을 증명하지 않는다. [Gemini billing](https://ai.google.dev/gemini-api/docs/billing)의 GetTokens 무료 문구와 현재 countTokens wire의 동등성은 별도 미확인으로 유지한다. 최신 금액 승인도 제품 방 전체 비용 상한·활성화 완료는 아니다.

현재 source 변경은 application outbound port·외부 adapter 및 shared budget 경계에 한정되며 domain 의존 방향을 유지한다. 실제 worker 변경은 이미 획득한 room/generation을 내부 request에 넣는 두 줄이며 기존 게시 검증/소유권/마감/마지막 CLEAR 구현은 불변이다. 공개 API·SQL·웹·배포 구성과 기존 Gemini adapter 변경0을 확인했다.

`/tmp/issue107-decision-pr-body.md`는 `.github/pull_request_template.md`의 정확한 여섯 제목과 순서(변경 목적/변경 내용/검증/리뷰 요청/영향 범위와 주의사항/관련 이슈)를 지키며 `Closes #107` 독립 행과 계약·예산·평가·리뷰 링크를 담는다. 최종1479개/512개 지문·역사적 FI14종과 실제 모델0·USD1 실행 차단을 적은 현재 본문도 재확인했다. exact HEAD CI는 게시 후 확인 대상으로 명시했다. 이 reviewer는 기존 PR116/117을 수정하거나 닫지 않았다.

첫 전체 guard는 숫자 P2 발견 후 중단됐으며 `.tdd/verification/issue107-decision-guard-interruption.json`의 `INTERRUPTED_NOT_A_PASS`로 분리됐다. 성공으로 집계하지 않는다. 새 전체 guard와 독립 리뷰가 완료됐으므로 승인된 로컬 commit/push/Draft 게시를 진행할 수 있다. 원격 URL·정확 HEAD SHA와 CI 성공 확인은 부모의 다음 단계이며 이 시점에 완료로 주장하지 않는다. 실제 모델 품질·가격/청구 cap·durable 예산 원장·활성화/운영/병합은 별도 미완료 상태다.
