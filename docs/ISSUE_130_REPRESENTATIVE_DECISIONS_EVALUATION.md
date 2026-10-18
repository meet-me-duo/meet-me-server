# 현재 후속: 공통 정책 V2 무료 정합화 (2026-10-18 UTC)

사용자 승인으로 [공통 정책 V2](ISSUE_130_SHARED_POLICY_V2_EVALUATION.md)의 A CLEAR 설명·A/B 지침·평가 정의 정합화를 별도 수행한다. 아래 실제 A16/A24/B2는 **V2 이전 각 source의 실행·고정 gold/점수 역사**이며 V2 성적으로 재사용하지 않는다. 당시 지침 변경0·CI 대기·예산 문구는 해당 시점을 나타낸다. V2 추가 실모델 호출은0이고 의미 효능은 미검증이다. 최신 무료 검증·게시·정확 CI 상태와 다음 시작점은 [Worklog](ISSUE_107_PRE_SUBMIT_WORKLOG.md)·PR132를 따른다.

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

# 개인 맥락 A24/B2 첫 구성 중단 (역사)

2026-10-18 UTC. 실제 A24 시작은 비용 문자열의 Python/Java 문법 차이로 사례 전 CONFIGURATION 중단됐다. 새 모델 결과0·A24/B2 전원UNRUN·새 raw gate NOT_EVALUATED이며 기존16 FAIL은 별도 보존한다. 현재 장부2.13925888, 이번 .34603008의 proven-unstarted 정산 근거 검토 중·기존 누적 예약1.79322880(UNKNOWN 포함) 불변·refund0·추가 예산 요청0이다. client 객체 생성과 HTTP dispatch를 분리하며 청구액0 직접 계측을 주장하지 않는다. [배치 집계](verification/issue130-context-stop-aggregate.json)와 [실행 계획](../implementation_plan.md)의 최신 구간을 따른다.

# #130 Decisions 평가: 실행 검증과 단계별 품질 결과

2026-10-18 UTC. 이 문서는 실제 평가 대상과 기록 게시 커밋을 구별한다. 최초 실행 대상은 production HEAD `3982535765e878875a901dd9d28352358e594f6f`와 미게시 평가 helper Java `7bf79d8cf266e9af84aa292840228535eb117f841d855e0f2e83fcf7a1a2452f`/Python `5933f3c7bd8d1508dafe26089304a336fb17064328fa996377d5ccda18bdec15`다. 이후 게시 HEAD를 실제 실행 당시 HEAD로 소급하지 않는다.

독립 담당자가 별도 supplemental 8대조쌍 16개 전원을 순서·정답과 함께 사전 봉인했다. 기존 개발60/holdout60은 선택0·불변이다. 균형 대조 진단이며 운영 분포의 무작위 표본이 아니다. root·구현자·리뷰어는 선택 원문·개별 정답·비공개 index를 수동 열람하지 않았다. 입력 준비는 현재 production serializer만 사용했으며 16 wire는 2386–2503 bytes/총39006 bytes·0600이었다.

## 원 실행 전 사전계획: 새24 A와 사전2 B (역사)

사용자가 합산 한도를 USD3으로 늘리고 실제 참가자가 쓸 생활·현장 맥락의 표현을 요청했다. 이 사전계획 당시 hold는USD1.79322880/여유1.20677120/환급0·새 예약/호출0이었다. 현재 STOP과 장부 상태는 문서 맨 위의 최신 구간을 따른다. 기존16의 원점수/FAIL은 보존하고 이후 개선용 DEV로 구분한다. root는 사용자가 명시 요청한 기존 TIME 대조2건만 평가 종료 후 보았으며, 새24 원문·개별gold는 root와 후보 구현자에게 공개하지 않았다.

새12쌍24건은 퇴근/수업/육아/교대/이동, 지시·조건/현장 맥락, 시간대·모임기간으로 해소되는 문맥, 넓은 허용/선호/제외, 유효한 타인 가용성 의존을 포함한다. 두 평가자는 먼저 독립 gold를 봉인했다. A는24/24 일치했고 B는 초기8건 차이가 있었으나, 안전한 두 complete 해석을 만들 수 없으면 NEEDS_EDIT라는 기존 계약을 확인해 실제 응답 보기 전에 조정했다. 원 두 gold와 차이는 보존하고 미해결 정책0으로 봉인했다. 최종 A는CLEAR14/REVIEW10, B는MUST_NOT_ASK14/MUST_ASK_MATERIAL_CHOICE1/SAFE_EDIT_ONLY9다. 작성자·평가자의 과거 별도 합성 자료/프로젝트 노출도 기록하며 완전 무노출 평가자로 부르지 않는다.

**이전의 검토 필요8건과 이번 B2는 같은 후보 집합에서 8→2로 줄인 관계가 아니다.** 이전8은 완료된16 진단의gold(선택필수7/editonly1)이며 여전히B0이다. 이번은 별도 새24의review10건 중 사전 선정2건이다. A24 단회 최대예약.34603008 뒤 B count+generation 한 세트.30412800를2건 실행하면 총2.74751488/여유.25248512다. 세 번째 B까지는 총3.05164288로USD3을 넘으므로2건으로 제한했다. 이는 보수 engineering reservation이며 invoice hard cap이 아니다.

B2 선정은 **유일한 선택형 필요1건 + 고정 입력 순서에서 첫 생활 맥락 직접 수정1건**이다. A 결과를 보거나 잘 맞을 듯한 사례를 선택하지 않았다. 실제 production generator의 count+generation을 독립 실행하는 STANDALONE_B_QUALITY_ONLY이며 새로운A호출/TwoStage/관리형금융/브라우저제출E2E를 검증한 것으로 부르지 않는다. 첫 사례는 질문·서로 다른 A/B 전체제안·다른 조건 보존을, 둘째는 임의 개인 시각 발명 없이 NEEDS_EDIT·질문 없음과 기존 명시 수정 경로를 평가한다. 맞춤 사유 문장은 현재 DTO에 없어 필수 gold로 추가하지 않았다.

B의 나머지 review8건, CLEAR14건의 실제 질문 회피, 최종 제출/UI·전체모임분석·운영 분포의 질문품질은 미검증으로 남는다. A에서 CLEAR군의 불필요B 경로를 측정해도 실제 불필요 질문의 발생률로 바꾸지 않는다. A 기술/API/refusal/schema/usage/timeout/지문/IO오류는STOP·나머지UNRUN으로 B자동진행을 막는다. A 의미FAIL은 그대로 보고하며, 결과 전 정한 독립B2 진단은 의미오답만으로 바꾸거나 재시도하지 않는다. B도 기술실패에서 전체STOP하며 다른 사례로 채우지 않는다.

[사전 집계/노출·라벨조정 기록](verification/issue130-context-holdout-preregistration.json), [이전16 원인 분석](verification/issue130-context-failure-analysis.md), [공식계약 검토](verification/issue130-decisions-contract-review.md)를 따른다. 새 지침1448B/190words는 독립정책리뷰를 통과했고 사례별 예외를 추가하지 않았다. 이 사전계획 당시 source/runtime/실행기 무과금 검증과 실행 전 독립수용은 진행 중이었다. 이후 검증과 실제 구성 중단·수정 경과는 맨 위 최신 구간과 구현 계획에 기록한다.

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

## 도구 검증

독립 RED 뒤 최소 helper 구현을 진행했다. 첫100 실행의 98PASS/author fixture 오류2는 원본과 정확한 보정 차이를 보존했다. 프로세스 시작으로 제한시간을 소진하면 자식 종료가 빠질 수 있는 결함은 별도 유효 assertion RED 뒤 최소 수정했다. 파일 생성 후 chmod 전 노출 창도 별도 RED로 재현하고 생성 시점 POSIX0600으로 수정했다. 기존 기대값을 약화하지 않았다.

최종 합성102/102 PASS, 독립 결함 주입7종/실제 assertion8 탐지·각 정확 복구를 확인했다. 실제 Python CLI→javac→child Java→production classifier/parser/TwoStage→canonical URL의 loopback TLS 합성16개도 통과했다. 동일 계획 재실행은 추가 HTTP0, 무인자 dry run은 network0이다. 합성 실행 시간은 실제 모델 지연이 아니다. 근거는 `.tdd/verification/issue130-harness-{final-green,independent-faults,whole-cli,independent-final}.json`이다.

## 사전 실행 경계

고정 A 최대16, 순차1, 사례15초·wrapper 시작/컴파일을 포함한 전체300초, request/response 각16KiB, 원문500자, count/B/retry/redirect/fallback0이다. 기술 오류·거절·usage 불명·시간/예산/지문 실패는 전체 STOP, 의미 오답·낮은 확신만으로 표본을 바꾸거나 중단하지 않는다. 실제 TwoStage의 B_REQUIRED_NOT_RUN은 생성 질문 성공을 뜻하지 않는다. 0.8은 기존 진단 fixture이며 운영 threshold는 null/OFF다.

실행 계획 SHA `b381bb4cd2b7a043d04a900bdb2b213a53e5b51e8f415229b7a80e5bff9a585b`, 독립 실행전 ACCEPTED SHA `9de93a0af1af9595ba4c4fb29cf32290fde3867934059784c26c0c3281a73dca`를 고정했다. 현재 source235·compiled/runtime/공개 사전규약 등1148파일을 검증했다. 공개 사전규약 manifest `863c039f2a8ee9d7bdf0043de0b4ee4f7ad39e4f5b3c8ec9c61f1cd31a948682`, prepared index SHA `815054af175b5580270c0ae4d7694533b6b25b5673a3b54b7fce4aa60c6410cf`다. 개인정보 원본은 저장소에 게시하지 않는다.

## 실제 단회 결과: 연결 중단, 의미 평가 불가

root가 정확 계획으로 한 번 실행했다. 첫 사례 STARTED/UNKNOWN_HELD 뒤 CONNECTION으로 중단했고, 바깥 wrapper는 CONFIGURATION으로 보고했다. 두 실패 수준을 구별한다. 관측 network interceptor 진입0·HTTP status0·응답0·분류0, 나머지15는 UNRUN이다. interceptor 이전 실패에서 이0은 공급자 도달/청구0 증명이 아니다. 추가 호출·재시도는 하지 않았다.

raw 의미/정규화 routing의 채점 분모는 각각0이다. 계획 분모16을 유지하면서 error1/UNRUN15를 별도 보고한다. 오탐·미탐·정확도·질문 품질을 계산하지 않는다. 기존 실제 단일 사례 raw CLEAR/0.06 불일치와 과거 성공/실패 기록도 그대로 남으며 새 평가 성공으로 대체하지 않는다.

관측 시간은 실패 사례의 A+local route 합산100.397499ms, JVM suite541.536936ms, root process4387.52ms다. 모델 응답 지연이나 stage별 시간으로 해석하지 않으며 p50/p95는 미산출이다. 실행 후 같은 JDK의 기본 ProxySelector가 DIRECT이며 DNS 조회가 UnknownHostException인 것을 비유료로 확인했다. 기존 환경 proxy를 사용한 Python 무인증 HEAD는 HTTP421을 받았으나 이는 gateway일 수 있어 모델 가용성 증명이 아니다. 원래 실패의 정확 exception 단계·공급자 도달 여부를 소급 확정하지 않는다.

## 비용과 남은 범위

기존 UNKNOWN 포함 예약 USD1.33185536을 보존하고 실행 전 16×USD0.01441792=USD0.23068672를 추가 예약했다. **합계 USD1.56254208·계획 여유 USD0.43745792·환급0**을 유지한다. 새 usage는 전부 불명이며 응답 없는 STARTED 사례를 무료로 처리하지 않는다. 65536 input token과 2.2배는 inherited engineering proxy로서 공식 invoice hard cap 증명이 아니다. [비용 사전 독립 검토](verification/issue130-representative-decisions-cost-review.md)를 따른다.

독립 결과 집계 SHA `a1d407aa6de6d9920bba4a87e90e3cb168d97cde6d143138f040634ba8e96e7c`는 분류 분모0과 exposure UNKNOWN을 확인했다. 이 결과 감사는 추가 실제 실행의 승인 근거가 아니다. 대표 의미 평가·운영 임계값/실제 가격·전체 앱에서 실모델과 금융 authority 관통·운영 활성화는 미완료이며 #123을 닫지 않는다. CI에는 키를 비운102와 별도 proxy63 합성 검증을 연결했다. 최초 helper의 필수 guard는 ktlintCheck/assemble/test 및 hook self-tests exit0·새214 suites/1717중1713PASS+기존4SKIP·source235전후동일(454.85초)로 완료했다. 후속 proxy source의 필수 guard도 exit0/source566 전후동일(11.398초)로 통과했다. Gradle은 기존 XML1717(1713PASS/기존4SKIP)을 재사용했으므로 새로운 fresh1717 실행으로 부르지 않는다. 새 proxy 독립63+기존102/FI8은 별도 실제 실행 결과다. 게시 리뷰·commit/push·정확 HEAD CI는 아직 미완료다.

실행 후 독립 결과 감사도 보고 정합 PASS·품질평가 불가로 봉인했다(SHA8396cba8f8d7b3aa72bdb0cd999ee53c56360388ed50146217724ddde173c966). 추가 실행을 허용한 판정이 아니다.


## 환경 proxy 보완과 TLS 확인 대기

환경별 차이를 평가 도구에 한정해 명시적 `transportMode: ENVIRONMENT_HTTPS_PROXY`로 보완했다. 기존 실행은 변경하지 않으며 새 mode만 엄격한 HTTP CONNECT 환경 proxy를 사용한다. malformed/빈 하위 env·userinfo·query/fragment·허용하지 않은 scheme은 연결 전 거부하고 DIRECT fallback·재시도·redirect는 없다. 현재 helper Java SHA c2439ff232fa2eda252c903ebd4cda00f51685c0b1aeca48a11bcce515c5ffdc, Python SHA b2f01a76fc2384f29c8eecf4658ff4f41f9096d844822a005f26213914c3f409다. 원본 helper102 source/evidence는 `.tdd/history/issue130-environment-proxy/original-102/`에 보존했다.

새 독립63은 valid assertion RED42/controls21 뒤63 GREEN, 기존102 unchanged GREEN이며 독립 FI8 모두 assertion 탐지/정확 복구 GREEN이다. 새 mode는 실제 첫 N16 예약 증거의 SHA/상태/정수 refunds0를 요구하고 previous USD1.56254208을 고정한다. 과거1.33185536으로 되돌린 계획을 거부한다. 새16회 최대 예약 제안은0.23068672를 더해1.79322880/여유0.20677120이며 아직 예약하거나 실행하지 않았다. 선택 원문·gold·순서·의미 oracle 변경0이다.

같은 새 factory에서 Authorization/body/key 없이 HEAD 최대1회만 실행한 결과 proxy DNS/connect/TLS start는 관측했으나 `SSLHandshakeException`으로 TLS 미완료·HTTP status 미관측이었다. 상세 nested cause/peer chain은 기록하지 않아 누락 CA가 정확 원인이라고 확정하지 않는다. 공개 CA 메타데이터만 비교한 결과 기존 JDK118·환경 bundle154·공통112/환경만42/JDK만6이며 합집합160이다. 기존 공개 환경 CA를 명시적 지문으로 평가 client 한정 상속하는 방안은 신뢰 anchor 확장이므로 보안 설정 제외 승인 범위에서 자동 진행하지 않는다. 해당 선택에 대한 명시 답변 대기이며 CA 코드·전역 JDK/OS/운영 설정 변경·추가 유료 호출0이다.

승인 시에도 기존 chain/hostname 검증을 유지하고 새 독립 TLS 양성/음성·오염 bundle 거절·기존 default roots 보존 검사와 FI, 무인증 확인, 최신 source/runtime/비용/실행전 독립 리뷰를 거친 새 single-use 계획만 실행할 수 있다. 답변 전에는 현재 hold1.56254208/여유0.43745792/환급0을 그대로 유지한다. 실제 오탐·미탐/정확도/질문 품질/p50/p95는 여전히 미측정이다.


## 첫 게시 CI의 단일 합성 실패와 관측 보완

Draft PR132의 최초 HEAD62f47bb/CI39123972026은 ARM64와 Gradle 빌드 및 기존102 합성을 통과했지만 proxy Java49 중48PASS/assertion1FAIL(Python14PASS)에서 중단했다. 첫 로그에는 개별 실패 ID가 없어 원인을 확정할 수 없다. 동일 테스트의 깨끗한 CI 유사 로컬 실행1회와 관측 보완 뒤 실행은 각각63PASS였으나 첫 실패를 성공으로 소급하지 않는다.

원래49/14 입력·기대값·TLS fixture 및 helper2개는 그대로 두었다. 별도 privacy4 RED→GREEN으로 확인한 고정49 test ID·상태·허용 오류 종류만 출력한다. raw 오류 설명·환경·body·key를 로그로 복사하지 않는다. 테스트 timeout이나 oracle을 바꾸지 않았으며 첫 CI 실패를 해결했다고 주장하지 않는다. 다음 정확 HEAD CI에서 실제 실패 ID를 확인할 수 있게 하는 관측 보완이다.

후속 mandatory guard는 source567 전후동일/exit0·8.718초로 통과했다. 기존1717 XML 재사용이며 새 fresh1717 실행은 아니다. 실제 평가·CA 신뢰·전역/운영 설정·유료 호출에는 변화가 없고 hold1.56254208/여유0.43745792/환급0을 유지한다.
