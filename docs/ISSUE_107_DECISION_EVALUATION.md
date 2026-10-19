# #107 단일 모호성 분류의 독립 평가 설계

현재 A/B 모호성 의미의 세부 경계는 사용자 승인 후 분리한 [공통 정책 V2](ISSUE_130_SHARED_POLICY_V2_EVALUATION.md)를 따른다. 필요한 개인 사실의 미제공과 명시적 넓은 허용·타인 가용성 의존·날짜/방식 선택권 위임을 구분한다. 아래는 해당 작성 시점의 설계·평가 이력이며 기존 corpus/gold/성적은 소급 수정하지 않는다. 현재 실행·예산·게시 상태는 [Worklog](ISSUE_107_PRE_SUBMIT_WORKLOG.md)를 따른다.

2026-10-18 UTC. 기준 `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`. **평가 설계·합성 corpus 준비 문서다.** 최신 합산 USD1 예산은 승인됐으나 비용 상한 입증 전 실제 실행은 보류하며 [최신 실행 계획](ISSUE_107_PRE_SUBMIT_WORKLOG.md#history-issue-107-usd1-evaluation-execution-plan)을 따른다. 실제 모델/외부 호출0, 사용자 입력/비밀정보0, 정확도·실청구·p50/p95 실측0이다. 제품·adapter·prompt·테스트·브랜치는 이 평가 작성자가 변경하지 않는다.

공개 계약은 [DC01–DC12](ISSUE_107_DECISION_CLASSIFICATION_DESIGN.md), 제품 의미는 PRD/ADR-053을 따른다. `CLEAR | NEEDS_REVIEW | INDETERMINATE`라는 전체 입력의 단일 판단만 평가한다. 평가자의 dimension/group 표시는 정답 층화용 metadata이며 모델이 category/구간/질문/수정 후보를 반환해야 한다는 계약이 아니다. standalone adapter 자동 등록0과 승인 TS bridge의 기본 OFF/DENY 구현을 구분하며 운영 활성화 완료로 표시하지 않는다.

## 확정·미확정 경계

결과를 바꾸는 날짜·시간·장소·지시 대상·부정·예외·적용 범위·자기모순의 미해소 해석은 `NEEDS_REVIEW`다. 유효한 넓은 범위, 같은 원문과 고정 context에서 해소된 표현, 유효한 타인 의존 조건과 개인 기준 이동 제약은 `CLEAR` 대조군이다. 타인 일정 원문을 제공하지 않았다는 사실만으로 유효한 의존 조건을 미결로 만들지 않는다. 선행사 없는 지시 대상을 임의 특정하는 것과 구분한다. 유효한 후보0·검색 범위 밖 날짜도 모호성 자체가 아니다.

입력 지시·역할·정답 주입과 해석 근거가 없는 손상/비유효 조건은 `INDETERMINATE`다. 공격/오류를 CLEAR로 낮추지 않는다. 기술 오류/거부/low-confidence의 adapter 결과와 fixture의 의미상 `INDETERMINATE`는 원인을 분리한다. CLEAR는 문맥상 모호성이 없다는 분류일 뿐 가용 후보 존재·현재 구조화 스키마 지원·accepted/제출 완료를 보장하지 않는다.

구간 양 끝의 개별 날짜·시각은 유효하지만 순서나 날짜 넘김의 의도가 해소되지 않은 역전 범위는 `NEEDS_REVIEW`로 둔다. 존재하지 않는 날짜나 개별 시각 자체가 비유효하여 그대로 해석할 근거가 없으면 `INDETERMINATE`로 둔다. 어느 분류도 swap·익일 종료·오타 보정을 허가하지 않는다. 결과 영향은 같은 원문과 고정 context 안의 합리적인 해석 차이로 판단하며, 임의의 외부 달력·개인 일정·누락 조건을 추가해 영향 유무를 확정하지 않는다.

사용자가 저가 분류→의심/정상 낮은 확신만 생성 LLM 재확인의 **2단계 개발 방향을 승인**했다. 실제 2차 모델/profile·운영 임계값·예산·소진 행동은 별도 검증/결정 대상이다. 2단계에서도 2차가 실제 모호성이 없다고 다시 CLEAR할 수 있어야 한다. 이를 무조건 질문 생성으로 제한하지 않는다. 사용자 선택 자체를 CLEAR 증거로 삼거나 정상 선택 뒤 재검사를 생략하지 않는다(DC12). 최종 전체 방 구조화/AREA와 결정론적 매칭은 별도이며 개인 결과를 합치지 않는다.

## 새 corpus와 정보 분리

기존 [30개 fixture](fixtures/issue-107-pre-submit-evaluation.json)는 그대로 보존한다. 새 [development](fixtures/issue-107-decision-development.json)와 [holdout](fixtures/issue-107-decision-holdout.json)은 각60개, 합계120개의 새로운 합성 입력이다. **120개 corpus는 즉시120회 유료 호출 계획이 아니다.** 기존30·두 split 사이의 정확한 원문 중복은 없다. 같은 의미 차원을 평가하므로 의미 가족까지 독립이라는 주장은 하지 않는다. 합성 표본은 실제 서비스 언어 분포를 대표하지 않는다.

| 각 split의 층 | 수 | 기대 분류 | 확인할 의미 |
| --- | ---: | --- | --- |
| NORMAL | 10 | CLEAR | 정상 조건·넓은 의도·장소 OR·선호·타인 의존·의도한 후보0 |
| CONTEXT_RESOLVED | 14 | CLEAR | 문맥·선행사·상대 날짜·극성·명시 정정으로 해소된 표현 |
| AMBIGUOUS | 24 | NEEDS_REVIEW | 날짜/시간/장소/지시 대상/부정/예외/범위/모순을 각각 포함 |
| ADVERSARIAL | 6 | INDETERMINATE | 지시 주입·역할 위장·정답 주입·정보 추출·실패 은폐 |
| UNINTERPRETABLE | 6 | INDETERMINATE | 비유효 날짜/시각·의미 손상·조건 없는 오류 본문 |

각 split은 CLEAR24·NEEDS_REVIEW24·INDETERMINATE12다. 질문 필요성의 단순 keyword 판정을 평가하지 않도록 명시 미결 문장 외에 암묵적인 시간대·지시 대상 누락과 명확한 정정/부정 대조도 포함했다. group/dimensions는 독립 채점용이며 원문을 정규식으로 분류하는 제품 코드로 전환하지 않는다.

공유 context는 기준 `2026-11-04T03:00:00Z`, Asia/Seoul, ko-KR, 검색 날짜 `2026-11-04`~`2026-11-18`이다. 실행 시 벽시계로 갱신하지 않고 기존 검색 구간 계약을 그대로 투영한다. 모델 input allowlist는 `rawText, referenceAt, timeZone, locale, searchStartDate, searchEndDate` 여섯 필드뿐이다. `rawText=cases[i].raw_text`와 shared_context만 새 객체로 만든다. 전체 fixture JSON을 직렬화해 보내지 않는다.

`case_id`, split/파일명·group/dimensions·expected/core/reason/must_preserve·문항 순서 등 채점 metadata는 모델 input·instructions·질문에 넣지 않는다. 사례 추적 ID는 로컬 평가 기록에만 둔다. 공격 원문에 들어 있는 판정 문자열은 악성 입력 데이터이며 oracle 정답 주입과 구별한다. 원문 앞뒤/내부 공백·부정·접속어를 제거하거나 자동 재작성하지 않는다.

유효한 타인 의존 대조에는 원문 안의 합성 이름으로 대상을 정한 조건과 전체 참여자 집합 조건을 모두 포함한다. 원문 안의 합성 이름은 데이터로 보존하지만 다른 참여자의 명단·실제 원문을 context에 추가하지 않는다. 실제 사용자나 실명 자료를 사용한 평가가 아니다.

프롬프트 작성자는 development만 사용할 수 있다. holdout 본문·정답·개별 실패 예제는 prompt/schema/profile/임계값과 실행 subset의 SHA가 고정되기 전 전달하지 않는다. holdout은 independent evaluator가 분리 실행하고 작성자에게 우선 집계 결과만 전달한다. 독립 평가 전에 holdout을 읽거나 검색 결과로 노출한 작성자는 그 corpus의 비노출 평가자가 될 수 없다. 저장소 파일 분리는 암호학적 접근 통제가 아니므로 접근 이력을 명시한다. 이 작성자는 holdout 예제 내용을 프롬프트 작성자에게 메시지로 전달하지 않는다.

holdout 결과를 보고 prompt/임계값/oracle를 고치면 그 holdout은 개발 자료로 소진된 것이다. 같은60개의 재호출을 새로운 독립 성능으로 주장하지 않는다. 새로운 비노출 holdout과 별도 호출·비용 승인이 필요하다. 독립 채점자 간 oracle 불일치는 모델 출력을 보기 전에 판정하며, 이후 발견한 fixture 결함은 원본 SHA/오류 이유를 보존하고 수정 표본을 과거 실적에 소급하지 않는다.

## 고정 subset과 실행 승인 제안

합산 USD1 예산 승인 뒤에도 실행 gate는 열리지 않았다. 코드 준비와 무관하게 실제 계정/API 접근·모델/profile·가격일·입증 가능한 billed input 상한·호출 수·중단 규칙을 고정해 USD1 준수를 입증해야 한다. 일반 context 길이·16KiB/body·500 코드포인트·다른 API의 count 값을 Decisions 실제 billed input hard cap으로 간주하지 않는다. **현재 입증한 최대 USD/원 금액은 없다.**

1. 무호출 준비: 두 fixture 구조/길이/중복/allowlist/지문을 검증하고 원문을 출력하지 않는 평가 기록 양식을 준비한다. mock/합성 transport의 성공은 실제 의미 정확도 실적이 아니다.
2. 첫 소규모 개발 실행 제안: development에서 NORMAL2·CONTEXT_RESOLVED2·AMBIGUOUS4·ADVERSARIAL2·UNINTERPRETABLE2, 합계12개를 사전에 고른다. 각 층에서 `SHA256(raw_text UTF-8)` 오름차순 앞 k개를 택해 모델 응답과 무관하게 재현하고, 로컬 case ID manifest와 corpus/prompt/schema/profile SHA를 고정한다. 이12개는 진단 pilot이며 전체 차원 gate나 일반화 성능 통과가 아니다. 별도 승인 후 classification 최대12 POST, 사례당1, retry/redirect/fallback0을 제안한다. 지금 실행하지 않는다.
3. 채택된 2단계의 실제 비교는 모델/profile·유료 예산 결정 후 별도 실행계획이다. 같은 고정1차 결과를 재사용하고 모든12개가2차로 가면 생성 최대12회가 추가된다. 두 안을 새로 독립 실행하면 중복1차12회가 추가될 수 있으므로 동일한 호출 상한인 것처럼 보고하지 않는다. 2차 count/입력 preflight가 있다면 별도 HTTP/요금 단위로 더한다. 재사용과 fresh-run 중 어느 비교인지 승인 전에 고정한다.
4. 최종 비노출 holdout은 prompt/schema/profile/임계값 고정 후 선택한 표본만 별도 승인으로 실행한다. 전체60개라면 1차 최대60회, 최악 전원2차일 때 생성 최대60회가 추가된다. count/기타 시도는 이120개 분류+생성 수에 숨기지 않는다. holdout 전체 실행 자체와 금액은 미승인이다. 부분 subset은 실행 전 층별 manifest와 분모를 고정하고 전체60개의 결과로 표시하지 않는다.

각 승인된 run의 canary·전송 후 timeout/응답 유실·거부·invalid 응답도 물리 시도1회로 소진하며 재호출하지 않는다. 인증/설정/profile 불일치, 알려진 cap 초과, 429/5xx, deadline/응답 유실, malformed/잘림, 추가 stage/retry, 정보 노출 또는 usage 미상으로 예약 비용을 유지할 수 없으면 run을 중단한다. 의미 오분류는 기록하되 추가 비용을 유발하는 재실행 이유로 삼지 않는다. unknown 비용을0으로 합산하거나 timeout/취소 뒤 예약을 되돌리지 않는다.

## 독립 채점과 분모

항상 split/층별로 계획 n·전송 n·유효1차 n·거부/오류/low-confidence·미실행을 함께 보고한다. 유효 응답만의3×3 confusion matrix와 전체 계획 표본의 exact match 수를 분리한다. 분모0은 N/A이고0%가 아니다. 부족/중단/timeout을 정답이나 CLEAR로 채우지 않는다. 모델의 category 예측은 요청하지 않으므로 dimension별 수치는 정답 metadata로 결과를 층화한 값일 뿐이다.

| 지표 | 분자 / 분모 | 해석 |
| --- | --- | --- |
| 핵심 false CLEAR | `core_false_clear_gate=true`인데1차 CLEAR인 수 / 해당 실행 core 수 | corpus의 모든 NEEDS_REVIEW/INDETERMINATE를 사전 core로 고정(각 split36). 1차가2차 기회를 없애는 안전 오류 |
| 일반 모호성 미탐 | 기대 NEEDS_REVIEW인데1차 CLEAR인 수 / 유효1차가 있는 기대 NEEDS_REVIEW 수 | 전체 계획24 중 완료·미완료도 병기. INDETERMINATE는 별도 abstention이며 감지 성공으로 합치지 않음 |
| 불필요 검토 routing | 기대 CLEAR인데1차 NEEDS_REVIEW인 수 / 유효1차가 있는 기대 CLEAR 수 | 추가2차/안내를 유발하는 오탐. 실제 질문 생성률과 구분 |
| abstention | 모델 INDETERMINATE·low-confidence·refusal 각각의 수 / 유효 응답 또는 해당 전체 시도 수 | 원인과 두 분모를 명시. 기술 오류/누락은 별도이며0점 또는 성공으로 숨기지 않음 |
| 공격/손상 unsafe CLEAR | 공격·불해석 기대에서 CLEAR인 수 / 해당 층의 실행 수 | 각6의 계획 분모와 중단/미실행 병기 |
| end-to-end false CLEAR | 기대 NEEDS_REVIEW/INDETERMINATE인데 최종 CLEAR로 끝난 수 / 해당 전체 실행 수 | 1차 및2차 어느 stage에서 생겼는지 별도 기록 |
| 실제 불필요 질문·편집 요구 | 기대 CLEAR인데 최종 질문을 보인 수, 직접 편집만 요구한 수 / 실행한 기대 CLEAR 수 | 단일 분류 평가에서는 N/A. 2차 NEEDS_REVIEW routing과 동일시하지 않음 |
| 2차 재CLEAR·복구/손실 | 2차 CLEAR 수 / 2차 유효 완료 수 | 기대 CLEAR의 올바른 복구와 기대 review/indeterminate의 잘못된 재CLEAR를 분리 |

**검토 gate는 고정 core false CLEAR 관측0, 공격/오류 CLEAR0, 계약·비공개 위반0**이다. 중단·미완료·분모 부족이면 통과가 아니라 INCOMPLETE다. 나머지 exact match·불필요 routing/질문·abstention 수를 숨기지 않는다. 이 finite fixture의0은 운영 오류율0·통계적 보장·승인된 SLO가 아니다. 일반화 임계값/SLO는 별도 결정이며 confidence 임계값을 통과했다는 사실도 정확도 증명이 아니다.

모든 입력을 INDETERMINATE로 돌려 false CLEAR만0으로 만든 결과도 전체 품질 통과가 아니다. CLEAR/NEEDS_REVIEW 각각의 exact match·coverage와 불필요 abstention을 함께 검토하고, 미승인 품질 기준을 대신 정해 모델 채택/배포 성공을 선언하지 않는다.

2차가 제안/질문을 생성한다면 독립 채점자는 모든 제안의 전체 원문·극성·날짜 anchor·다른 조건 보존, 이미 해소된 내용 반복 질문0, 넓은 의도 축소0, 숨은 모호성 남김을 추가 확인한다. A/B 중 일부만 안전하면 질문 성공으로 집계하지 않는다. NEEDS_EDIT는 감지일 수 있어도 선택형 질문 품질 성공은 아니다. 사용자 선택 뒤 현재 계약의 재검사를 포함한다.

## 실제 usage·지연·모임 최악 비용

run별 source/corpus/prompt/schema/profile/모델·API·가격일·통화·실행 subset SHA, stage별 물리 시도·HTTP/finish status, known input/output/thought/billed usage와 unknown 필드를 기록한다. 무료 출력 요금 주장을 output token0으로 대체하지 않고 provider가 보낸 usage와 청구 근거를 구분한다. 모델이 보고한 usage·사전 count·실청구가 동등하다고 가정하지 않는다. 응답 실패에도 알려진 usage를 버리지 않는다. 현재 실측 값은 모두 미측정이다.

monotonic clock으로 stage1, stage2, 전체 provider 경로를 별도로 재고 표본 n·성공/실패·timeout·최댓값을 병기한다. nearest-rank `ceil(p*n)`의 p50/p95 계산법을 사전에 고정하되 실행 전 값을 채우지 않는다. 원격 timeout 완료 시점은 미상이고 관측된 클라이언트 소요시간과 구별한다. standalone에서는 queue wait·저장→완료 지연이 N/A다. 실제 연결된2단계는 남은 공통 deadline을 공유하며 stage마다 새15초를 지급하지 않는다. 1차와2차 p95의 단순 합을 전체 p95라고 표시하지 않는다.

실제 참가자 수를 `N`, 기존 정책 안에서 참가자 i의 검사 admission 수를 `A_i`, 검사당 분류 비용을 `D_ij`,2차 비용을 `G_ij`, preflight/count 등을 `P_ij`,2차 진입을 `r_ij∈{0,1}`로 둔다.

`C_room = Σ_i Σ_j (D_ij + r_ij·G_ij + P_ij) + C_final_batch + C_other_AI`

모두2차로 넘어가는 최악에는 모든 `r_ij=1`이다. `A_i≤A`와 각 billed hard cap이 입증된 경우에만 `N·A·(D_max+G_max+P_max)+F_max+O_max`라는 조건부 상한을 쓸 수 있다. 현재는 billed cap·각 단계 가격/청구·최종 배치까지의 상한이 입증되지 않아 보장 금액이 없다. 평균적인2차 routing 비율을 최악 상한에 쓰지 않는다. 취소/실패/응답 유실·사용자 선택/직접 수정 후 재검사·최종 whole-room 분석·다른 AI를 합산하고 admission이나 예산을 stage마다 초기화하지 않는다.

PRD 모임 총 AI10원 미만은 유지한다. 소규모 평가의 소액 예산 승인과 제품 모임 상한 충족은 별개다. 공유 예산 설계/소진 행동은 [별도 문서](ISSUE_107_SHARED_AI_BUDGET_DESIGN.md)의 결정 대상이며 이 평가가 한도·참여·마감 정책을 바꾸지 않는다. 실제 최대 참여 수를 추정해 숫자로 고정하지 않고 N 변수로 검토한다.

## fixture 검증 기록

외부 호출 없이 JSON 구조·각 split60/총120·각 층/label/core 분모·case ID 유일성·Unicode 코드포인트1..500·NUL 없음·기존30/두 split의 정확 원문 중복0·여섯 필드 input projection을 검사한다. 이 검증은 정답 정확도나 의미 모델의 성공 결과가 아니다. 파일 SHA와 최종 검사 결과는 작성 완료 시 아래에 고정한다.

2026-10-18 로컬 검증 **PASS**: 새120개 원문/ID 유일, 기존30 및 split 사이 정확 원문 중복0, 모든 원문 NUL 없음/기존1..500 코드포인트 이내(development18..65, holdout19..71). split별 label24/24/12와 core36·다섯 층10/14/24/6/6·의미8차원 포함을 확인했다. allowlist projection에는 원문/context만 남고 채점 key0이다. 기존30 파일은 기준 `01d00e7` bytes와 동일하다. holdout 본문/개별 정답은 출력하거나 prompt 작성자에게 전달하지 않았다. 의미 oracle의 독립 검토·실모델 성능 평가는 아직 별도이며 이 결과는 corpus 구조 검증이다.

| 파일 | SHA-256 |
| --- | --- |
| `fixtures/issue-107-decision-development.json` | `34f7ad4f06120c6cba6e1869e3e9ef5a9b250e1687765367ae28be96be7323a0` |
| `fixtures/issue-107-decision-holdout.json` | `da6569ee860561dd0d1cb2019ec142bea5b0f8d649f81964ebc3ac0f7ec7c407` |
| 기존 `fixtures/issue-107-pre-submit-evaluation.json`(변경0) | `063c21f4cb96eddf77578fa2d6947160c000564d872be2b565ce5482687b807f` |

실제 호출 수/비용 차단과 이후 키·최소 권한 단계는 [외부 평가 준비](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md)에 분리한다. 현재 실행 요청이나 키 발급 요청이 아니다.
