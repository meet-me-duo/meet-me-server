# 개인 맥락 평가의 사전 실행 경계

2026-10-18 UTC. 현재 준비 중이며 이 문서는 실제 결과나 운영 수용을 뜻하지 않는다. 기존16 raw gate FAIL과 원래 gold/응답을 보존한다. 새24의 사전 입력·두 원 gold·실행 전 조정·B2 선정은 [사전 집계](issue130-context-holdout-preregistration.json)에 봉인했다. 원문·개별 gold·식별자 연결은 로컬0700/0600 custody만 보유한다.

## 순서와 비용

| 단계 | 고정 범위 | 추가 최대 보수 예약USD | 누적USD |
| --- | --- | ---: | ---: |
| 기존 유지 | UNKNOWN/이전 실패 포함·환급0 | 0 | 1.79322880 |
| A | 새24에 후보 지침 각1회·serial1 | .34603008 | 2.13925888 |
| B | 사전2건 독립 generator·count2+generation2 이하·새A0 | .60825600 | 2.74751488 |

합산 승인USD3의 계획 여유는.25248512다. 각 단계는 시작 직전에 전체 상한을 CREATE_NEW로 예약하고, 실패/유실/미실행/싼 usage에서 차액을 환급하거나 다른 호출에 재사용하지 않는다. 65536/81920 input proxy와2.2 margin·count를full-generation으로 잡은 단가는 engineering reservation이며 invoice hard cap 증명이 아니다. 현행 공식 입력/출력 단가와 thought-inclusive2048 cap은 별도 독립 검토했다. 실제 wire≤16KiB/text-only와 extras없음·source/runtime manifest를 실행 직전 확인해야 한다.

A는 고정 CANDIDATE_A24_USD3_V1 profile,15초/case·wrapper시작/compile포함420초다. 과거16/300초 profile을 변경하지 않는다. B는 STANDALONE_B_QUALITY_ONLY, count+generation 공유15초/case·wrapper시작/compile포함120초다. 모델/6input/500Unicode/B candidate1·LOW·2048 output·count4096 gate는 그대로다. 새 arbitrary endpoint/model/profile/cap 옵션은 두지 않는다. helper는 기본 dry run이며 실제 실행에는 root가 봉인한 정확 plan hash와 명시 실행 seal이 모두 필요하다.

B2는 유일 MATERIAL_CHOICE와 고정 순서 첫 생활 맥락 SAFE_EDIT_ONLY다. 이전16의review8에서같은pool을축소한것이아니다. B3까지는총3.05164288로한도를초과한다. 나머지review8·CLEAR14의실B질문회피와UI/최종분석은미검증분모로남는다.

## 중단과 판정

- root만 각 단계 단회 실행한다. 입력/gold/순서/선정·후보·.8·채점 규칙은 결과를 보고 바꾸지 않는다. count/retry/redirect/fallback은 A0, B count≤2·generation≤2이며 retry/redirect/fallback0이다.
- A 기술/API/refusal/schema/usage불명·timeout·지문·예산·IO오류는 전체STOP하고 나머지UNRUN을 기록하며 B자동진행을 막는다. 의미 오답만으로 사례를 교체하거나 조기 중단하지 않는다.
- A의 의미FAIL은 그대로 보고한다. 사전 정한 독립B2 품질 진단은 그 의미 오답을 고치기 위한 재시도나 전체TwoStage 성공으로 부르지 않고 별도 시행할 수 있다. B의 기술/API/계약/generation의필요usage/timeout/IO오류는 B 전체STOP·나머지UNRUN이며 다른 사례로 채우지 않는다.
- raw gold×choice 혼동, explicit INDETERMINATE/refusal/error/UNRUN, normalization/LOW_CONFIDENCE, unsafe early CLEAR와 unnecessaryB를 분리한다. NEEDS_REVIEW10의 raw falseCLEAR가1개라도 있으면 사전 raw safety gate는FAIL이다. zero observed falseCLEAR가 나와도 전부abstain/과도한B경로를 품질PASS나 운영준비로 바꾸지 않는다. 운영threshold/utility수용값은 선택하지 않는다.
- A gold14/10·12pairs와 B gold14NOTASK/1CHOICE/9EDIT 분모를 유지한다. B실평가2는질문/선택지보존1·editonly1이며실제질문금지CLEAR분모0이다. B가NEEDS_EDIT로유보하면선택형필요gold의질문성공으로세지않는다. 기술파싱PASS와독립의미품질을분리한다.
- 기존16과새24를합산해정확도/개선인과/운영p50·p95를주장하지않는다. 시간은실제로관측한구간과표본만기록한다. 새holdout을열어다음튜닝에쓰면DEV/reused로바꾸고새미사용평가가필요하다.

count 응답의 totalTokens는 기존4096 admission gate의 관측값이며 실제 청구 input과 동일하다는 증명이 아니다. count의 최종 청구·invoice 미확정은 full-operation 예약으로 유지한다. 정상 count를 청구서 부재만으로 실패라 부르거나 무료로 처리하지 않는다. A 분류/generation의 필요한 usage 누락·잘못된 count schema와 이 회계상의 미확정은 구분한다.

## 전송과 제품 경계

A는 source/runtime와 전체 canonical request를 봉인한다. 기존 승인환경CA1개만 평가 client 메모리에추가하며 기본JDKtrust·인증서·hostname검증을 유지한다. A의api.openai.comorigin guard는그대로이고 B는별도고정GoogleHTTPS443/countTokens·generateContent두POSTpath만허용한다. 전역/JDK/OS/운영trust·Secret/OAuth·배포설정변경은없다.

B는실제 production Gemini runtime provider의count gate와parser를사용하되독립port진단이다. A판단을강제로바꾼productionroute/TwoStage/관리형금융/웹저장/auth/최종분석E2E로부르지않는다. 질문제안은사용자수락전원문에반영하지않고 NEEDS_EDIT는제출완료가아니다. 운영기본OFF/UNCONFIGURED와main/deploy/활성화HOLD는그대로다.
