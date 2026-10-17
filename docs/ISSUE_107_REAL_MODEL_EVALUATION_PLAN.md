# #107 실제 모델 평가 준비안

2026-10-17 UTC. **계획이며 실행 승인이 아니다. 실제 호출0, 측정 결과 없음.** 합성 provider 검증은 의미 정확도·실제 API 지원·실청구를 증명하지 않는다.

## 현재 활성화 상태와 공식 근거

현재 `PrecheckProviderConfiguration`은 options 없이 provider를 생성한다. `PrecheckProviderOptions.totalOutputTokenCapVerified`는 환경 설정이 아닌 생성자 옵션(default false)이며 production opt-in 바인딩이 없다. 키나 존재하지 않는 verified 환경변수만 추가해도 HTTP 전 CONFIGURATION 실패다. **운영 활성화 코드도 남아 있다.**

현재 고정 모델은 `gemini-3.8-flash`, API는 GenerateContent, 요청은 candidate1·`maxOutputTokens:2048`·`thinkingBudget:0`이다. 재조사에서 [GenerateContent 전용 thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens)도 thought 포함 infrastructure cutoff와 MAX_TOKENS 종료를 명시함을 확인했다. 기존의 “Interactions만 보장한다”는 판단을 정정한다. guide의 `max_output_tokens`와 [REST reference](https://ai.google.dev/api/generate-content#v1beta.GenerationConfig)의 `maxOutputTokens` 대응을 근거로 묶되 실제 요청 지원/청구 검증으로 확대하지 않는다. 현재 모델에서 `thinkingBudget:0`이 완전한 thinking-off라는 근거는 없다. 공식 권장 thinkingLevel 및 전체 cap에 맞춘 요청 준비가 필요하다. 새 API·다른 모델로 전환할 필요가 확정된 것은 아니다.

기존 응답 usage 사후 거부, 15초 HTTP timeout, 로컬 취소는 이미 발생한 원격 계산·과금을 취소하지 않는다. raw500자·body16KiB도 billed input token 상한이 아니다.

## 안전한 활성화 구현·독립 TDD 명세 [AGENT]

아래는 준비 명세이며 현재 제공되는 설정 이름이나 완료된 코드가 아니다.

1. 기존 GenerateContent/고정 모델/2048 합산 cap/공식 지원 thinking 파라미터만 허용하는 capability profile과 별도 기본 OFF opt-in을 준비한다. 임의 모델·endpoint·환경 boolean 하나로 검증을 주장하지 않는다. 합성 loopback options는 테스트 전용이다. OFF 상태와 실제 호출0을 유지한다.
2. OFF+key 존재의 outbound0, 불완전/미지원 profile의 안전한 설정 오류, 지원 profile의 정확 wire를 테스트한다. 후속 최소 wire 후보는 `thinkingLevel:"LOW"`·`maxOutputTokens:2048`·`candidateCount:1`, thinkingBudget 없음이다. 이는 **외부 요청 파라미터 계약 변경**이므로 별도 독립 TDD/리뷰가 필요하다. 전역 Gemini 설정이나 다른 분석 요청은 바꾸지 않는다. 생성자 직접 호출뿐 아니라 production bean의 실제 바인딩을 검증한다. ON 경로의 mock 성공을 실제 모델 검증이나 운영 활성화 완료로 표시하지 않는다.
3. thought+candidate cap, MAX_TOKENS/incomplete/empty/malformed 거부, 누락 usage=unknown, 과다 usage, timeout/body trickle/redirect/429/503/인증 오류를 독립 transport로 검증한다. 자동 retry/fallback0을 유지한다. OFF 무시·cap 누락·숨은 retry·truncation 성공·unknown=0의 결함을 주입·탐지하고 복구한다.
4. worker의 revision/generation/deadline/본인 소유권 fencing, 참가자별 admission6회·retry1회·질문3회·queue100/동시2·30초 실행/15초 provider·마감 정책은 바꾸지 않는다. 실제 평가 전 production opt-in은 OFF다.
5. 평가용 billed input hard cap4096을 위해 정확 전송 prompt+schema+metadata의 token 산정 계약을 별도로 확인한다. count가 필요하면 동등성·실패/초과 시 generation0·count 비용·전체 시간 예산을 검증한다. count 호출을 기존 generation admission과 혼동하지 않으며 제품 경로에 몰래 추가하지 않는다.

## 30개 실제 평가 실행안 [SHARED, 승인 대기]

- [고정 fixture](fixtures/issue-107-pre-submit-evaluation.json) Q01~Q10/MUST_ASK10·N01~N20/MUST_NOT_ASK20을 그대로 사용한다. `source_created_at`, Asia/Seoul, ko-KR·검색 기간을 고정한다. **모델에는 Q/N ID·expected/reason/preserve를 보내지 않는다.** opaque UUID·raw/context만 전달하여 정답 누출을 막는다. 실제 개인/방 데이터는 사용하지 않는다.
- Google Gemini Developer API, pinned `gemini-3.8-flash`, GenerateContent Standard, candidate1. profile 준비 후 정확 thinking 파라미터·prompt/schema 지문을 고정한다. tools/search/maps/cache/grounding/fallback은 사용하지 않는다.
- **generation 최대30회, 사례당1회, 자동·수동 retry0, 동시성1.** canary·timeout·응답 유실·invalid 응답도 물리 전송 한 번으로 소진한다. 중단 후 재개/실패 재호출0. 별도 smoke 호출0. count를 쓰는 경우 사례당 최대1회·총30회, 전체 HTTP 최대60회로 별도 집계한다. provider 단독 격리 실행으로 답변 후 재검사·최종 whole-room 분석 등 추가 provider 호출0을 보장한다.
- billed input 목표 hard cap4096, thought 포함 billed output hard cap2048. **현재 input token enforcement와 count 비용 근거는 미완료이므로, 지금 승인 가능한 입증된 최대 USD는 없다.** [공식 Standard 가격](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash)은 조회일 기준 input0.75/output3.75 USD/1M(thought 포함), 2026-12-31까지다. 두 hard cap·count 비용0을 입증한 뒤에만 `30 × (4096 × 0.75 + 2048 × 3.75) / 1,000,000 = USD0.32256`가 generation token 요금의 조건부 상한이다. 현재 보장/호출 허가가 아니다. 세금·환율·인프라 제외. 승인 시 날짜·tier·통화 가격을 재확인한다.
- 실행 선행 조건: profile·input count/cap·실패 계약 독립 mock/리뷰/CI → 값 비출력 평가 환경 → 정확한 호출 수·입증한 비용 상한·중단 규칙 보고 → **별도 유료 실행 승인**. 미충족 시 호출0이다.

### 중단 조건

인증/설정/tier/profile 불일치, 429·5xx, timeout·연결/응답 유실, cap/count 실패, 잘못된 스키마·완료 상태, usage 누락으로 잔여 비용 상한을 유지할 수 없는 경우 전체 run을 중단한다. 허용 호출수/예산 초과·추가 provider 호출·타인 정보 혼입 또는 A/B의 무관 조건·극성·날짜 변경도 중단하고 재호출하지 않는다. 단순 ASK/NOASK 오분류는 기록하여 나머지 독립 표본 평가를 계속할 수 있지만 배포 통과로 표시하지 않는다. 매 호출 전 최악 비용을 예약하며 unknown 비용을0원으로 합산하거나 timeout/취소 뒤 예약·호출 한도를 돌려주지 않는다.

### 측정·독립 판정

| 구분 | 기록·계산 |
| --- | --- |
| 재현 | run ID·UTC 시각·source/fixture/prompt/schema SHA256·model/API/version/tier/cap·사례 ID/expected는 평가 기록에만 보관 |
| 호출·요금 | count/generation·물리 attempt·HTTP/finish status·timeout·known input/candidate/thought/billed total·unknown 항목·가격일/통화·부분 비용 합계/미확인 수 |
| 의미 | CLEAR/NEEDS_CHOICE/NEEDS_EDIT/technical failure/missing을 분리. ASK의 CLEAR=미탐, NOASK의 CHOICE/EDIT=오탐. ASK의 EDIT는 감지일 뿐 질문 품질 성공은 아님. EDIT 허용 여부는 기존 reason/preserve와 안전한 완전 A/B 가능 여부를 평가 전에 독립 채점자가 고정. 제안 전체 조건·극성·상대 날짜 보존과 명확한 내용 반복 질문을 독립 판정 |
| 분모 | ASK 완료/10·NOASK 완료/20·실패/미실행을 병기. 완료 표본 오탐/미탐률과 전체 계획 정답/30을 함께 보고. 제안 보존의 분모는 제안 생성 표본 수이며 A/B 모두 안전한 질문 단위의 통과 수를 별도로 보고 |
| 지연 | 이번 provider 단독 평가는 monotonic count·provider 전체 HTTP·harness elapsed를 실측. queue wait·저장→verdict는 N/A이며 별도 승인한 전체 경로 평가에서만 측정. 각 성공/실패·timeout과 n을 보존하고 nearest-rank `ceil(p*n)` p50/p95·최댓값 산출. missing을0초로 처리하지 않음 |

임시 보수적 gate 제안은 ASK 감지10/10·NOASK CLEAR20/20·계약/안전 실패0이다. 사용자 승인된 정확도 SLO가 아니며 30개 통과를 일반화 정확도로 주장하지 않는다. prompt 변경 후 재호출은 새 유료 계획/승인 대상이며 같은 표본 최적화를 독립 평가로 표시하지 않는다. p50/p95·정확도는 실행 후에만 기록한다.

## 부모에게 남기는 정책 충돌

PRD의 **모임당 총 AI10원 미만**은 바꾸지 않았다. 2048 출력 상한만으로도 허용된 최악 출력 요금은 호출당 USD0.00768(기존 기본 환산1500이면11.52원)이며 입력·참가자당 최대6회·최종 whole-room 분석이 더해진다. 이는 실제/최소 비용이 아니라 상한 산식이다. `maximumKrwPerMeeting`은 관측 판정이며 precheck admission의 비용 차단이 아니다. 현재 한도로 총10원을 보장할 수 없으므로 **제품 비용 정책/허용 한도 충돌은 부모 결정 대상**이다. 별도 합성 평가의 소액 예산 승인은 제품10원 정책 변경 승인이 아니다. 제품 활성화 전 이 충돌을 해소해야 하며, 실제 평가 결과가 판단 자료가 될 수 있다. 임의로 PRD·질문/호출 cap·500자 제한을 바꾸지 않는다.
