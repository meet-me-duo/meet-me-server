# #107 실제 평가와 키·권한 준비 경계

> 2026-10-18 최신 승인: Gemini·OpenAI 및 재시도/대체 호출을 합친 이번 실제 검증 총액 USD 1 이하. 예산 승인은 받았지만 입력/부속 호출/세금까지 포함한 상한을 입증하기 전 실행하지 않는다. 최신 소규모안·실행 조건은 [USD1 실행 계획](ISSUE_107_USD1_EVALUATION_EXECUTION_PLAN.md)을 따른다. 아래12/36회는 이전 제안이며 새 실행 확정 수가 아니다. 기존 인증 변수의 존재만 값 비출력으로 확인했고 키 생성·전달·새 접근 설정·운영/배포는 승인되지 않았다.

2026-10-18 UTC. **외부 실행·키 발급/입력·권한/결제 변경을 요청하거나 수행한 문서가 아니다.** mock 구현과 Draft CI는 키 없이 진행한다. 사용자 원문/비밀값을 평가에 쓰지 않는다.

## 호출 계획과 현재 비용 차단

[독립 평가 문서](ISSUE_107_DECISION_EVALUATION.md)의 120개 corpus는 즉시120회 호출 승인이 아니다. 먼저 development의 고정 층화12개(정상2·문맥2·모호4·공격2·불해석2)를 원문 SHA 오름차순으로 선택하는 진단 pilot을 제안한다. 분류 전용이면 Decisions POST≤12, 사례당1·동시1·retry0이다. 2단계 end-to-end까지 별도로 승인하면 전원2차의 최악에 생성≤12, 기존 Gemini count≤12를 더해 HTTP≤36이다. 추가 smoke/canary를 별도 공짜 호출로 만들지 않으며 timeout/거부/유실도 시도1회로 소진한다. 첫 실제 run의 scope·profile·정확 subset은 승인 요청 전에 고정한다.

입력은 원문1..500 codepoints·고정 context·단일 holistic choice 질문, 직렬화 요청≤16KiB다. 이는 코드의 요청 상한이며 **청구 입력 token hard cap 증거가 아니다.** 검토용 confidence 값도 실제 품질 기준/운영 SLO 승인이 아니다.

현재 확인한 Decisions 입력 단가 $0.10/M·출력 무료, 비교 Jev $0.042/M·출력 무료만으로 최대 비용을 계산할 수 없다. 질문별 실제 과금량/배수·동일 request의 billed upper bound와 Gemini count 과금·생성 상한 근거가 아직 부족하다. **지금 검증 가능한 최대 USD/원 비용은 미확정이므로 유료 평가를 실행하지 않는다.** 실제 모델/tier/region·날짜별 가격과 검증된 수량 상한을 확보한 뒤 `Σ(각 물리 호출의 검증된 최악 청구량×가격×배수)`를 보고하고 승인된 USD1 합산 범위 안에서 실행 gate를 확인한다. 전원2차·실패/유실/거부·최종 분석을 평균 절감으로 빼지 않는다.

[OpenAI spend limits](https://developers.openai.com/api/docs/guides/spend-limits)는 alerts와 hard limit을 구분한다. hard limit도 적용 지연으로 소폭 초과할 수 있다고 명시하므로 현재 미확정 평가의 정확 최대 비용 증명으로 전용하지 않는다. 실제 계정의 한도/권한을 조회하거나 수정하지 않았다. 서비스 모임10원·지속 PG 원장·전체 최종 분석 재시도 통제는 [공통 예산 설계](ISSUE_107_SHARED_AI_BUDGET_DESIGN.md)의 별도 조건이다.

중단 조건은 인증/권한·billing/profile/tier 불일치, 429/5xx, timeout/유실, 입력/응답 cap·schema/분포 오류, quota/reservation 실패, unknown usage로 비용 상한을 유지할 수 없음, 추가 호출/정보 노출이다. 중단 뒤 자동/수동 재시도0이다. 의미 오분류는 기록하며 같은 표본을 임의 재호출해 개선된 결과만 집계하지 않는다. 실제 usage·오탐/미탐·stage별/전체 지연·분모와 unknown을 함께 보고하며 현재 p50/p95 실측은 없다.

## 이후 승인을 받은 경우의 최소 사용자 단계 [USER/SHARED]

이유는 사용자 소유 OpenAI 프로젝트에서 Decisions 접근·API 키 권한과 비용을 승인해야 하기 때문이다. 사전 조건은 선택할 조직/프로젝트의 사용 권한, 별도 승인된 평가 scope/검증된 예산 상한·안전한 일회성 비밀 입력 경로다. **현재는 키가 필요하지 않으며 아래를 실행하지 않는다.** 기존 OAuth Google/Kakao 공개 설정을 다시 만들지 않는다.

1. [API Platform](https://platform.openai.com/)에서 사용할 기존 조직/프로젝트를 선택한다. Settings → 해당 Project → Limits의 model/capability 접근과 계정 상태를 사용자가 확인한다. gpt-6-luna의 일반 접근을 Decisions endpoint 권한의 증거로 간주하지 않는다. 새 프로젝트·유료 플랜·한도 상향은 자동으로 요청하지 않는다.
2. Settings → 해당 Project → API Keys에서 기존에 적절한 project-scoped key가 있는지 먼저 확인한다. 새 키가 필요한 경우에만 별도 승인 뒤 **Create new secret key**를 사용한다. 사용자 키는 Restricted에서 필요한 Decisions 요청 권한만 선택하되 실제 UI의 지원 목록을 확인한다. resource별 None/Read/Write/요청별 옵션이 달라질 수 있고 Decisions 항목이 보이지 않으면 소유자에게 확인하며 All 권한으로 확대하지 않는다. 사용자 권한/endpoint 권한이 없는 Read Only key는 생성 요청에 충분하다고 가정하지 않는다.
3. 서비스 계정이 필요한 경우 Project → Members → Service account는 owner 권한이 필요하다. service-account 생성 창의 권한 옵션은 사용자 키와 다르며 생성 후 Project API Keys에서 제한할 수 있다. 기존 프로젝트/키 재사용 여부를 먼저 결정하고 임의 발급·회전·폐기는 하지 않는다.
4. 실제 값은 승인된 로컬 평가 전용 환경변수/비밀 저장 경로에 직접 입력한다. 평가 runner를 연결할 때 사용할 이름은 `OPENAI_API_KEY` 후보이며 실제 운영 SSM/배포 Secret 변경과 분리한다. 값·Authorization 헤더·전체 env·스크린샷을 채팅/이슈/저장소/로그에 보내지 않는다. runner가 아직 제공되지 않았다면 먼저 비밀 입력을 요구하지 않는다.
5. 완료 신호는 프로젝트의 비밀 아닌 식별 정보, key의 configured 여부, 필요한 endpoint/model 권한 확인 상태뿐이다. 이후 에이전트는 값 비출력 검사와 승인된 합성 subset·호출/예산 상한 안의 실행만 확인한다. key 등록 완료·mock 성공을 실제 API 품질·운영 활성화 완료로 표시하지 않는다.

공식 UI 근거: [project 관리](https://help.openai.com/en/articles/9186755-managing-projects-in-the-api-platform), [key 권한](https://help.openai.com/en/articles/8867743-assign-api-key-permissions). 공개 문서와 실제 계정의 이용 가능 여부는 별개이며 현재 계정 로그인/키 확인·API 호출은 수행하지 않았다.

이 때문에 보류되는 것은 실제 품질/청구/계정 검증과 운영 활성화다. 설계·독립 TDD·합성 transport/bridge·코드/문서 게시와 exact HEAD CI는 계속 진행한다. runtime selector는 기본 OFF이고 default budget DENY를 유지한다. main/develop 병합·배포 승인은 이번 범위에 없다.
