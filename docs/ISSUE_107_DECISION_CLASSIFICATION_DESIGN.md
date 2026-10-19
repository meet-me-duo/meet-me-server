# #107 저비용 모호성 분류 공통 경계

현재 A/B 모호성 의미의 세부 경계는 사용자 승인 후 분리한 [공통 정책 V2](ISSUE_130_SHARED_POLICY_V2_EVALUATION.md)를 따른다. 필요한 개인 사실의 미제공과 명시적 넓은 허용·타인 가용성 의존·날짜/방식 선택권 위임을 구분한다. 아래는 해당 작성 시점의 설계·평가 이력이며 기존 corpus/gold/성적은 소급 수정하지 않는다. 현재 실행·예산·게시 상태는 [Worklog](ISSUE_107_PRE_SUBMIT_WORKLOG.md)를 따른다.

2026-10-18 UTC. 기준 develop `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`. 이번 단계는 외부 호출 없이 검토할 수 있는 공통 경계·목업 검증과 Draft PR/exact HEAD CI까지다. Decisions의 main/develop 병합·배포·키 발급/설정·운영 변경은 하지 않는다. 최신 실제 검증 합산 USD1 예산 승인은 [실행 계획](ISSUE_107_PRE_SUBMIT_WORKLOG.md#history-issue-107-usd1-evaluation-execution-plan)의 비용·인증 gate를 충족해야 하며 현재 실제 호출0이다.

## 확정 범위와 결정 대기

원문 전체, 기준 시각/시간대/locale/검색 범위, 기존 마지막 CLEAR 마감 정책을 보존한다. 마감 후 전체 참여자 조건의 구조화·장소 그룹과 결정론적 매칭을 유지하며 개인별 구조화/AREA를 합치지 않는다. 불용어 제거·정규식 의미 판정·자동 원문 변환은 하지 않는다. 다른 참여자의 이름·원문은 분류 입력에 넣지 않는다. 유효한 타인 의존 조건, 넓은 가능 범위, 문맥으로 명확한 표현은 모호성 그 자체가 아니다.

Decisions 분류 뒤 유형별 안내만 보여주는 안과 의심 사례만 생성 LLM으로 다시 확인하는 안을 비교한 뒤, **2단계 개발 방향은 사용자 승인됐고 생성 모델·운영 confidence 임계값·비용 금액·소진 시 제품 행동은 미확정**이다. 유형별 안내 전용 API/DB/web 확장은 도입하지 않는다. 기본 OFF standalone adapter와 기존 생성 검사 재사용은 [2단계 계약](ISSUE_107_TWO_STAGE_CONTRACT.md)에 따라 준비한다.

## 공개 내부 계약 DC01–DC12

- DC01: `AmbiguityClassificationPort`는 application outbound port다. request는 rawText 전체(기존 1..500 codepoints), referenceAt/timeZone/locale/searchStartDate/searchEndDate/timeout이다. 빈/초과/NUL·유효하지 않은 context/비양수 timeout은 전송 전에 거부한다. raw의 앞뒤 공백·부정/예외/접속 표현을 제거하거나 재작성하지 않는다.
- DC02: 결과 assessment는 `CLEAR`, `NEEDS_REVIEW`, `INDETERMINATE`만 허용한다. 원문 구간·질문·수정 후보·시간 구조/추천을 생성하거나 담지 않는다. 이 결과 자체는 accepted 제출이나 사용자 완료 상태가 아니다. 분류 결과를 단독 accepted 집계에 연결하지 않는다. 승인된 2단계 bridge만 TS 계약에 따라 기존 provider/worker의 안전 경계를 사용하며 공개 API·영속성은 그대로다.
- DC03: OpenAI candidate adapter만 구현한다. canonical endpoint는 `https://api.openai.com/v1/decisions`, 모델은 `gpt-6-luna`. `{model,input,questions}`에 하나의 holistic `choice` 질문 `material_ambiguity`를 넣는다. choices는 위 3개 문자열이며 descriptions/instructions로 날짜·시간·장소·지시 대상·부정·예외·적용 범위·자기모순의 결과 영향을 설명한다. 공유 input은 JSON 직렬화한 원문/context이며 사용자 문자열을 instructions로 보간하지 않는다. 식별자·expected·rubric 정답을 보내지 않는다.
- DC04: Decisions는 predicate/choice/score만 지원한다. 이번 wire는 choice1개로 고정하며 category/구간을 추론해 덧붙이지 않는다. 명확한 정상/유효한 넓은 범위·타인 의존 조건은 CLEAR, 결과를 바꾸는 미해소 해석은 NEEDS_REVIEW, 근거가 부족하면 INDETERMINATE라는 관찰 가능한 의미를 instructions에 둔다. 사용자 문자열의 명령·정답 주입은 데이터로 취급한다.
- DC05: 기본 OFF는 키 유무와 무관하게 outbound0이다. standalone adapter 자체는 환경값이나 Spring worker를 읽거나 자동 연결하지 않는다. 2단계 selector와 default DENY 조합은 TS11을 따른다. 합성 테스트의 명시 옵션만 enabled 및 검토용 최소 confidence를 받으며, 임계값 부재/비유한/범위 밖은 전송 전 CONFIGURATION이다. 임계값은 운영 품질 승인이 아니다.
- DC06: exact named answer1개·지원 type/value·완전한 유한 probability 분포(각 0..1, 중복/누락0, 합계1 허용오차1e-6)·confidence0..1을 검증한다. 외부 confidence/probability의 십진수 표현은 precision≤1000·scale -1000..1000의 내부 자원 상한을 값 비교·분포 합계 연산 전에 검증한다. 이를 넘는 극단 지수/정밀도는 INVALID_RESPONSE이며, 16KiB 본문 상한만으로 연산 비용이 제한된다고 보지 않는다. 0/1·통상 소수·허용오차 경계의 유효성은 유지한다. choice와 최대 확률의 불일치도 거부한다. 충분한 confidence의 CLEAR만 분류 CLEAR 후보다. 낮은 confidence는 INDETERMINATE이며, 임계값 이상이라는 사실은 정확도 증명이 아니다.
- DC07: exact named refusal은 INDETERMINATE/REFUSED, 모델 INDETERMINATE는 MODEL_UNCERTAIN으로 구분한다. malformed/missing/duplicate/trailing JSON·알 수 없는 필드/값·model/name 불일치·비정상 분포는 INVALID_RESPONSE다. unknown/거부/오류를 CLEAR로 바꾸지 않는다. 원시 refusal/error body는 결과·예외·로그에 담지 않는다.
- DC08: 한 classification에 물리 POST 최대1, redirect/connection retry/429/503 follow-up0이다. 전체 HTTP/body 읽기에 min(request timeout,15초)의 하나의 monotonic deadline, request/response UTF-8 16KiB 상한을 둔다. 잘림/빈 본문은 실패이며 자동 fallback/생성 호출은 없다. body cap은 billed input cap이 아니다.
- DC09: nullable nonnegative Long input/output usage를 별도로 보존하며 output 무료를 output_tokens0으로 바꾸지 않는다. missing/invalid usage는 unknown(0 아님)으로 구분하고 결과/실패를 관측한다. 유효한 top input/output은 독립 보존하고 usageComplete=false 및 incomplete 지표를 기록한다. details/total까지 완전한 비음수 Long·overflow-safe 합계/cache·reasoning 범위를 검증했을 때만 usageComplete=true다. usage 오류·missing인 분류 성공도 비용은 unknown이며 운영 활성화나 예산 정산 허가가 아니다. 분류 assessment와 실제 청구 증거를 혼동하지 않는다.
- DC10: metrics는 고정 provider/model/operation/outcome/failure 태그만 사용하고 원문·질문·응답·키·소유자 식별자를 넣지 않는다. exception 메시지도 고정 코드만 사용한다. 키/헤더/query/context가 출력되지 않아야 한다.
- DC11: 실제 Spring composition에서 기본 selector OFF이면 기존 Gemini `PreSubmissionCheckProvider` 기본 OFF가 그대로임을 검증한다. 새 분류 adapter는 standalone이며 앱에 자동 등록/활성화되지 않는다. 2단계 opt-in selector의 default DENY와 worker의 room/generation 전달은 TS 계약으로 별도 검증한다. 기존 500/방10000·개인6회·retry1·질문3·마감/본인 ownership·revision fencing·SQL은 변경하지 않는다.
- DC12: 기존 A/B 후보는 전체 후보의 모든 지원 모호성이 해소됐다는 선행 증거가 없어 선택 후 재검사를 유지한다. 향후 생략은 exact 후보·source/check/generation·anchor·prompt/schema에 결속한 선행 CLEAR 증거와 안전한 게시 fencing을 검증할 때만 별도 도입한다. 사용자 선택만으로 CLEAR 처리하지 않는다. 선택 뒤 새 원문도 같은 저가1차 검사를 거치므로 충분 CLEAR이면 추가 생성 호출 없이 끝낼 수 있으며, 재검사 자체를 생략하는 계약은 추가하지 않는다.

## 2단계 후보의 영향과 재사용

새 분류 port는 두 안이 공유한다. 채택된 2단계는 NEEDS_REVIEW 뒤 기존 `GeminiPreSubmissionCheckProvider`의 CLEAR 재판정·NEEDS_CHOICE/NEEDS_EDIT·bounded proposal 검증과 worker의 마감/소유권/revision 게시 경계를 재사용할 수 있다. 외부 호출 없는 검증은 기존 Gemini 생성 adapter를 재사용하며 실제 모델/profile의 비용·품질 검증과 활성화는 별도다. 낮은 confidence/거부/실패는 CLEAR로 우회하지 않으며 정상 유보와 API 오류·거부의 분기는 TS02를 따른다. 복수 모호성은 기존 재검사·직접 수정으로 보존한다.

1차 false CLEAR는 2차가 보정할 기회를 없애므로 핵심 회귀의 잘못된 CLEAR0을 검토 gate로 둔다. 2차는 실제 모호성 없음으로 CLEAR를 반환할 수 있어야 한다. stage들이 기존 worker/provider의 남은 시간·모임 누적 예산을 공유해야 하며 stage마다 새 15초를 지급하지 않는다. 최악에는 전원이 2차로 넘어가므로 평균 절감으로 최대 비용을 주장하지 않는다.

## 공급자 근거와 미확인

- [OpenAI guide](https://developers.openai.com/api/docs/guides/decisions), [create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create), [response reference](https://developers.openai.com/api/reference/resources/decisions): public beta, choice/predicate/score·복수 독립 질문 지원, 현재 gpt-6-luna, 입력 $0.10/M·출력/cache 요금0. 지역/long-context 배수·계정 접근·질문별 실제 청구량·Decisions 전용 사전 입력 cap은 미검증이다. 일반 model context를 청구 상한으로 전용하지 않는다.
- [Jev API](https://docs.typesafe.ai/api), [models](https://docs.typesafe.ai/models): state/questions/instructions/criteria, keyed answers·noul/choice/score, 입력 $0.042/M·출력 무료. 구조와 가격 비교만 보존하며 두 공급자 운영 연결을 만들지 않는다.
- 실제 키 발급/권한 단계·평가 호출 수/입력 cap/입증 가능한 최대 비용·중단 조건은 평가 문서에 분리한다. 사용자 입력/비밀값을 외부 평가에 사용하지 않는다. 현재 paid calls0·정확도/지연/청구 실측0이다.

공통 평가: [독립 평가 기준](ISSUE_107_DECISION_EVALUATION.md). 예산: [모임 누적 예산 설계](ISSUE_107_SHARED_AI_BUDGET_DESIGN.md). 이 문서는 candidate 설계이며 새 ADR로 운영 정책을 확정하지 않는다.
