# #107 실제 모델 평가 준비안

> 2026-10-17 단일 Gemini 검사 평가 준비 이력이다. 2026-10-18 채택된 Decisions→필요시 생성 보완의 최신 계획은 [독립 평가 기준](ISSUE_107_DECISION_EVALUATION.md)과 [외부 평가/키 경계](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md)를 따른다. 아래30회와 조건부 산식을 새2단계 평가의 승인/최대 비용으로 사용하지 않는다.

2026-10-17 UTC. **계획이며 실행 승인이 아니다. 실제 호출0, 측정 결과 없음.** 합성 provider 검증은 의미 정확도·실제 API 지원·실청구를 증명하지 않는다.

## 현재 활성화 상태와 공식 근거

초기 조사에서는 production opt-in 바인딩이 없었다. 후속 준비 코드의 `PrecheckProviderConfiguration`은 실제 `meetme.precheck.provider.enabled` 바인딩과 `MEETME_PRECHECK_PROVIDER_ENABLED`(기본 false)를 사용한다. runtime factory는 문서 기반 고정 profile만 사용하며 OFF이면 키나 임의 verified 환경변수가 있어도 count/generation0·CONFIGURATION이다. 기존 생성자 options의 verified boolean은 literal loopback HTTP 합성 compatibility 경로에만 남고 외부 호출에는 사용할 수 없다. 실제 운영 opt-in은 수행하지 않았다.

현재 고정 모델은 `gemini-3.8-flash`, API는 GenerateContent다. runtime 요청은 candidate1·`maxOutputTokens:2048`·`thinkingLevel:"LOW"`이며 thinkingBudget은 없다. 초기 합성 compatibility의 thinkingBudget0을 실제 모델의 완전한 thinking-off 근거로 사용하지 않는다. [GenerateContent 전용 thinking guide](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens)도 thought 포함 infrastructure cutoff와 MAX_TOKENS 종료를 명시한다. 기존 “Interactions만 보장한다” 판단은 정정됐으며 guide/REST 필드 대응과 LOW 지원을 고정 profile의 공식 근거로 사용한다. 실제 요청 지원·청구·의미 정확도 검증과 구분한다.

기존 응답 usage 사후 거부, 15초 HTTP timeout, 로컬 취소는 이미 발생한 원격 계산·과금을 취소하지 않는다. raw500자·body16KiB도 billed input token 상한이 아니다.

## 기본 OFF 활성화 코드·독립 검증 계약 [AGENT]

후속 구현은 새61+기존18 focused GREEN으로 확인했다. 전체 gate·FI·최종 리뷰/CI는 Worklog의 실제 완료 기록을 따른다. 실제 모델/OFF 운영 설정을 변경한 결과는 아니다.

1. 기존 GenerateContent/고정 모델/2048 합산 cap/LOW만 허용하는 문서 기반 profile과 기본 OFF opt-in을 구현했다. production endpoint는 설정으로 노출하지 않는다. 합성 loopback options는 테스트 전용이며 실제 OFF·호출0을 유지한다.
2. OFF+key 존재의 outbound0, 잘못된 바인딩의 설정 실패, 고정 profile의 `thinkingLevel:"LOW"`·`maxOutputTokens:2048`·`candidateCount:1`/thinkingBudget 없음 wire를 독립 테스트했다. 이 **외부 요청 파라미터 계약 변경**은 독립61개 RED→기존18개 포함79개 GREEN으로 확인했으며 전역 Gemini 설정이나 다른 분석 요청은 바꾸지 않았다. 생성자 직접 호출뿐 아니라 production bean의 실제 환경 바인딩을 검증했다. ON 경로의 mock 성공을 실제 모델 검증이나 운영 활성화 완료로 표시하지 않는다.
3. thought+candidate cap, MAX_TOKENS/incomplete/empty/malformed 거부, 누락 usage=unknown, 과다 usage, timeout/body trickle/redirect/429/503/인증 오류를 독립 transport로 검증한다. 자동 retry/fallback0을 유지한다. OFF 무시·cap 누락·숨은 retry·truncation 성공·unknown=0의 결함을 주입·탐지하고 복구한다.
4. worker의 revision/generation/deadline/본인 소유권 fencing, 참가자별 admission6회·retry1회·질문3회·queue100/동시2·30초 실행/15초 provider·마감 정책은 바꾸지 않았다. 각 admission은 최대count1+generation1, 총 HTTP는 최대12로 구분하며 count 실패도 admission을 소진한다. `meetme.precheck.provider.http.attempts`의 bounded operation 태그 COUNT_TOKENS/GENERATE_CONTENT를 따로 기록한다. 실제 평가/비용 결정 전 production opt-in은 OFF다.
5. 전체 frozen GenerateContentRequest를 count1회로 계산하여 정수1..4096만 generation을 허용하고, known prompt usage>4096은 응답을 거부한다. 동일15초·자동 retry0·16KiB/500자 제한을 유지한다. known input/candidate/thought usage와 unknown을 성공·거부 응답 모두 별도 관측하며 count값을 실제 사용량으로 대체하지 않는다. [공식 count reference](https://ai.google.dev/api/tokens)의 예시도 count10/generation prompt11로 다르므로 **사전4096 제한+사후 방어는 실제 billed input hard cap 증명이 아니다.** count 비용·동등성은 여전히 미확인이다.

## 30개 실제 평가 실행안 [SHARED, 승인 대기]

- [고정 fixture](fixtures/issue-107-pre-submit-evaluation.json) Q01~Q10/MUST_ASK10·N01~N20/MUST_NOT_ASK20을 그대로 사용한다. `source_created_at`, Asia/Seoul, ko-KR·검색 기간을 고정한다. **모델에는 Q/N ID·expected/reason/preserve를 보내지 않는다.** opaque UUID·raw/context만 전달하여 정답 누출을 막는다. 실제 개인/방 데이터는 사용하지 않는다.
- Google Gemini Developer API, pinned `gemini-3.8-flash`, GenerateContent Standard, candidate1. profile 준비 후 정확 thinking 파라미터·prompt/schema 지문을 고정한다. tools/search/maps/cache/grounding/fallback은 사용하지 않는다.
- **generation 최대30회, 사례당1회, 자동·수동 retry0, 동시성1.** canary·timeout·응답 유실·invalid 응답도 물리 전송 한 번으로 소진한다. 중단 후 재개/실패 재호출0. 별도 smoke 호출0. count를 쓰는 경우 사례당 최대1회·총30회, 전체 HTTP 최대60회로 별도 집계한다. provider 단독 격리 실행으로 답변 후 재검사·최종 whole-room 분석 등 추가 provider 호출0을 보장한다.
- billed input 목표 hard cap4096, thought 포함 billed output hard cap2048. 코드의 사전 count gate는 준비했지만 **실제 billed input 상한과 count 비용 근거가 미입증이므로 지금 승인 가능한 입증된 최대 USD는 없다.** [공식 Standard 가격](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.8-flash)은 조회일 기준 input0.75/output3.75 USD/1M(thought 포함), 2026-12-31까지다. 두 billed hard cap·count 비용0을 입증한 뒤에만 `30 × (4096 × 0.75 + 2048 × 3.75) / 1,000,000 = USD0.32256`가 generation token 요금의 조건부 상한이다. 현재 보장/호출 허가가 아니다. 세금·환율·인프라 제외. 승인 시 날짜·tier·통화 가격을 재확인한다.
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
