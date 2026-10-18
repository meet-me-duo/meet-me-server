# #123 실제 2단계 재확인 — 별도 단일 사례 검증

2026-10-18 UTC. 최초 Decisions 진단의 LIVE-KO-03 raw label 불일치 **FAIL/STOP**은 그대로 보존한다. 이 문서는 그 평가를 재개하거나 정답을 바꾸지 않고, 같은 원문에서 정상 재확인 분기에 진입했을 때 실제 생성 응답이 의도를 보존하는지 별도로 검증한다. 실제 실행은 HTTP503에서 중단됐고, 질문 의미 평가 목표는 미완료다.

[독립 사전 oracle](evaluation/issue123-live-two-stage-oracle.json)은 SHA `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`로 동결했다. 기존 e83 테스트의 threshold0.8을 그대로 재사용하며 운영 threshold(null/OFF)를 선택하지 않는다. 원문·prompt·schema·출력 한도를 결과에 맞춰 변경하지 않는다.

- 입력: “10월 24일 토요일 8시부터 9시까지 가능해요.”
- 목표: 날짜와 가능 의도를 보존하면서 같은 날 08–09시/20–21시를 구분하는 NEEDS_CHOICE와 두 완전한 제안. CLEAR는 이 사례의 의미 실패, NEEDS_EDIT는 안전한 유보지만 질문 목표 미완료다.
- 새 물리 HTTP 최대3회: 새 Decisions1 → 정상 review/low-confidence 분기에서만 Gemini count1 → count1..4096이면 generation1. 확신 높은 CLEAR·오류·유실·timeout이면 즉시 중단, 재시도/redirect/fallback/continuation0.
- 원래 production contract의 전체 payload와 candidate1·thinkingLevel LOW·thought 포함 maxOutputTokens2048을 유지한다. 기존 TwoStage가 callback port의 실제 HTTP 호출 순서를 조정하며 공통15초 예산을 사용한다. 생산 SDK transport·실제 영속 예산 authority·웹/제출 저장/방 전체 분석의 실모델 관통 검증으로 주장하지 않는다.

[독립 비용 검토](verification/issue123-live-two-stage-cost-review.md)에 따라 기존 USD0.07208960 예약은 유지한다. Gemini count와 generation 각각81920 입력 allowance·2048 출력의 full-generation 견적에2.2배 여유를 두고, 새 Decisions1을 더한 추가 예약은USD0.31854592다. 같은 승인 USD1에서 누적USD0.39063552, 추가 비용 여유USD0.60936448를 유지한다. Gemini byte/token·count 단가의 공식 상한 증명이 아닌 보수 engineering 견적이고, 세금·최종 청구액을0이나 확인된 값으로 가정하지 않는다.

실행은 수정 없는 전체 guard와 현 소스 독립 리뷰가 통과한 뒤 수행한다. 원시 요청/응답·single-use consumed 기록·개별 HTTP 지연·usage nullable 값과 모델 응답 의미의 독립 판정을 별도 보존한다. 사전 oracle 실패 시 추가 호출·답변 손질·threshold 변경 없이 중단하며 전체 품질·정확도·p50/p95·운영 준비 완료를 주장하지 않는다.

실행 전 wire는 분류2397B·count3172B·generation3111B로 모두16KiB 미만이다. 동결 [실행 계획](../.tdd/history/issue123-live-two-stage-execution/execution-plan.json)의 SHA는 `368bafb75f2c36f0980501ea5b7b8a9669f5b8eb5e6705c49e4b6659b9aeb746`이며 [wire manifest](../.tdd/history/issue123-live-two-stage-execution/wire-manifest.json)와 exact harness/callback 원본을 같은 history에 보존한다. callback은 전체 guard exit0을 재검사하고 stage별 O_EXCL consumed를 요청 전에 기록한다. 실행 전 준비 시점의 추가 실제 호출은0이었다. 아래는 이후의 고정 계획 실행 결과다.

## 실제 관측과 차단

전체 guard와 CODE GATE 리뷰 통과 뒤 05:37:50–05:37:52 UTC에 고정 계획을 한 번 실행했다.

| 단계 | 관측 | HTTP 응답 측정 |
| --- | --- | --- |
| 새 Decisions | HTTP200, raw CLEAR/confidence0.06, 별도 CLEAR probability0.37, 현재 parser→LOW_CONFIDENCE |855.0ms|
| Gemini count | HTTP200,530토큰, 기존4096 gate통과 |225.9ms|
| Gemini generation | **HTTP503**, 성공 생성 응답·usage 미확보, 즉시 중단 |347.8ms|

실제 TwoStage는 A1→count1→generation1을 조정했다. JVM 전체 실행은2177.7ms이며 SDK transport 관통으로 주장하지 않는다. eval callback의 nonzero exit가 하네스에서 CONFIGURATION으로 요약됐지만 관측된 실제 공급자 실패는 HTTP503이다. 이 실패를 권한 부재나 모델의 의미 오류로 단정하지 않는다. 성공한 생성 질문 응답을 확보하지 못해 사전 LTS02–08과 원문 의도 보존을 독립 평가할 수 없다. 오류 본문은 callback에서 저장하지 않았으므로 503 응답 본문이 비었다고 단정하지 않는다. 결과는 **FAIL_PROVIDER_OR_STRUCTURAL / 질문 검증 미완료**이며 사전 조건대로 재시도·추가 호출0이다.

초기4회와 합한 Decisions는5회·입력2573/출력0이다. 그 보고 usage의 공개 기본 단가 환산은USD0.0002573,2.2배 보수 환산은USD0.00056606이다. Gemini count1과 generation시도1의 청구/생성 usage는 미확인으로 남기고0으로 바꾸지 않았다. 전체 예약USD0.39063552와 추가 비용 여유USD0.60936448를 유지한다. 최초4회 개별 지연1011.1/201.9/196.2/203.6ms와 초기 raw FAIL/STOP은 [원 진단](ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md)에서 보존한다.

[구조화 관측](../.tdd/verification/issue123-live-two-stage-observed.json)과 [single-use 실행 기록](../.tdd/history/issue123-live-two-stage-execution/live-invocation.json)에 각 요청/응답·guard·oracle 지문과503 차단을 남겼다. 운영 활성화·임계값 결정·전체 정확도/p50/p95·실제 모임10원 보장을 입증한 결과가 아니다.

## 별도 후속 시도2 — 실행 전 고정

부모가 최초503의 FAIL/STOP과 미확인 예약을 유지한 채 같은 범위의 한정된 별도 재검증을 요청했다. [독립 예산 검토](verification/issue123-live-two-stage-attempt2-budget-review.md)의 Decimal 검증에 따라 기존USD0.39063552+추가 최대USD0.31854592=누적USD0.70918144, 추가 비용 여유USD0.29081856이다. 그다음 같은 전체 시도는USD1.02772736으로 승인USD1을 넘으므로 실행하지 않는다.

첫 종료 이후7분 이상 지난 시점에 새 single-use 폴더와 [고정 계획](../.tdd/history/issue123-live-two-stage-attempt2/execution-plan.json)을 준비했다(plan SHA `b4fe8fd30e5b61b87d259cce1ab6a4099220d445910ba5617c2511d9354a0e28`). 기존3개 요청 bytes/SHA·oraclec69·합성threshold0.8·전체15초·2048/LOW·harness757b는 동일하다. 최대새A1/count1/gen1, 이 별도 시도 내부 retry/fallback0이며 첫 실패에서 중단한다. 원래503 계획/결과는 수정하지 않는다. 새attempt2는 실행 전 정적 독립 리뷰 뒤 한 번만 실행하며 현재 준비 실행의 추가 HTTP는0이다.

## 후속 시도2 실제 관측

05:47:02–05:47:17 UTC, 첫 종료 이후 약9분 뒤 고정한 후속 시도를 한 번 실행했다. 새A는HTTP200/LOW_CONFIDENCE0.06(입력512/출력0,394.6ms), count는HTTP200/530(245.3ms)이었다. 생성은 **남은 공통15초 budget TIMEOUT**으로 child process가 종료돼 성공 응답/usage를 확보하지 못했다. 두 번째 생성의 HTTP status·개별 HTTP 지연은 관측하지 못했으며503으로 쓰거나0으로 채우지 않는다. JVM 전체15538.3ms는 시작 준비도 포함하므로 pipeline15초 위반이나 생성HTTP지연 실측으로 해석하지 않는다.

누계는 Decisions6회·입력3085/출력0, Gemini count2·생성시도2·성공한 생성응답0, 평가HTTP시도10이다(기존 model metadata조회2는 별도). 알려진Decisions usage만 공개기본단가USD0.0003085/보수2.2배USD0.00067870이며 Gemini의 청구·생성usage는UNKNOWN으로 유지한다. 첫503/원래분류FAIL/STOP·모든 미확인 hold를 보존했고 누적예약USD0.70918144/추가 비용 여유USD0.29081856를 반환하지 않는다.

같은 범위의 추가 검증은 최대USD0.31854592가 더 필요하여 누적USD1.02772736으로 승인USD1보다USD0.02772736 초과한다. **추가 dependent 실제 호출을 중단한다.** 사전oracle/threshold/prompt/2048/LOW/15초를 실패에 맞춰 바꾸지 않았다. 생성 질문이 관측되지 않아 원문 의도 보존의 독립 의미 평가는 여전히 미완료다. [후속 구조화 관측](../.tdd/verification/issue123-live-two-stage-attempt2-observed.json)에 UNKNOWN·timeout·각 계수와 비용을 보존한다.
