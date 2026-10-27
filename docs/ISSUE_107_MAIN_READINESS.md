# Meet me main 반영 준비 경계

## 2026-10-20 통합 후보와 운영 준비

현재 범위는 server/web develop 통합과 main행 검토 준비다. main 병합·운영 배포·키/권한 변경·추가 유료 호출은 하지 않는다. OAuth 콘솔 설정은 사용자가 완료했다. 아래 서버 설정 미확인은 콘솔 재설정 요청이 아니다.

[PR138](https://github.com/meet-me-duo/meet-me-server/pull/138)의 코드 기준은 `bf6f4e00a06e682ae45b4317033808a7da8912e5`이며 승인 정책의 최종4+1 보호분을 신규 개인 예약에서 보전한다. 로컬 전체2054 PASS·기존4 SKIP, 독립29 GREEN/FI3/정확 복구와 원본 guard exit0을 확인했다. README 정리 후 정확 source/최종 CI와 develop 반영 여부는 해당 PR과 [main행 PR136](https://github.com/meet-me-duo/meet-me-server/pull/136)을 따른다. 웹 main행은 [PR34](https://github.com/meet-me-duo/meet-me-web/pull/34)다.

다음 상세는 README에서 옮긴 운영·평가 인계다. 구현 통과와 운영 활성화를 구분하며, 과거 승인·실험 예산을 새 운영 cap으로 사용하지 않는다.

### 개인 확인과 전체 분석의 재시도

개인 확인의 Jev 분류와 Luna 질문 생성은 기존 15초를 공유합니다. 자동 재시도 대신 허용된 실패에 한해 사용자가
명시적으로 재시도하며, 답변·직접 수정은 새 원문 버전의 재검사입니다. 최종 전체 분석은 재시도 가능한
Gemini 장애에 한해 최대 4회 시도하고 `Retry-After`가 없으면 지수 backoff와 full jitter를 적용합니다.
남은 실행 시간과 예약이 허용할 때 Luna fallback을 최대 1회 사용합니다. 동일 작업의 무제한 재전송은
허용하지 않으며 지속 장애는 원문을 보존한 `ANALYSIS_DELAYED`로 구분합니다.

### 통합 검증 상태와 지원 경계

- 개인 보완은 GPT-6 Luna Responses 단일 요청이며 Standard(`service_tier=default`)·low reasoning·출력 최대2,048토큰을 명시합니다. count·자동 재시도·개인 단계 fallback은 없습니다. 실제 Standard 처리·15초 내 응답률·질문 의미 품질은 무료 회귀와 구분해 미검증으로 남깁니다.

- Google/Kakao·소유권, 질문 API·답변·재검사, 웹 화면과 영속 예산 구현을 server/web develop에 통합했습니다.
  무료 회귀와 CI는 실제 운영 OAuth 로그인·callback·서명키 설정 및 새 모델의 의미 품질·지연 검증과 구분합니다.
- Jev 전체 입력 분류의 운영 연결을 준비합니다. 별도 원본48 실험에서 검증한 시각 관련성 및
  `AM/PM/NOT_STATED/UNRESOLVED` 판단은 시간대 보완 실험이며, `NO_PERIOD_QUESTION`만으로
  입력 전체를 `CLEAR`로 승인하지 않습니다. 모임 길이·날짜 경계·최종 일정 해석은 전체 분석에 남깁니다.
- 검토된 운영 가격 근거·방 예산·단위·confidence 임계값이 없으면 개인 확인과 관리형 AI 예산은 기본
  OFF·`UNCONFIGURED`·전송 거부입니다. 개발 평가 한도를 운영 비용 승인으로 사용하지 않습니다.
- 최종 조건 추출의 실제 Luna 4/4 검증과 이전 무료 회귀는 개인 Jev 판단·질문 생성·답변·로그인을
  포함한 운영 전체 흐름의 실검증을 대신하지 않습니다. 추가 실모델 호출은 이번 통합 준비에 포함하지 않습니다.
- main행 검토 PR은 서버 [#136](https://github.com/meet-me-duo/meet-me-server/pull/136)과 웹 [#34](https://github.com/meet-me-duo/meet-me-web/pull/34)입니다. main 병합은 기존 자동 운영 게시·배포를 촉발하므로 서버의 새 OAuth/RS256·typed secret·V11–V17 대상 준비와 웹의 호환 배포 순서를 먼저 확인해야 합니다. 기존 main의 읽기 전용 preflight 성공을 새 대상 준비 완료로 사용하지 않습니다. 검증된 소스 통합만으로 운영 AI 기능을 활성화하지 않습니다.
- 단일 참여자의 명시적인 기본 시간과 한 단계 대체 시간을 지원합니다. 여러 조건부 참여자, 연쇄·순환
  대체는 안전 제외와 `PARTIAL` 사유로 남깁니다. 해석 실패·구조화 결과 누락을 참석 가능으로 추정하지 않습니다.

### 병합 전 비용과 운영 준비

10원은 달성된 고정 상한이나 새 결제 승인이 아닙니다. 2026-10-20 공식 Standard 가격의
Gemini 출력 $3.75/백만 토큰과 Luna 출력 $0.50/백만 토큰을 **조건부 계산**에 사용하면 다음과 같습니다.
Gemini 가격은 2026-12-31까지이며 이후 재검토가 필요합니다.
([Gemini 가격](https://ai.google.dev/gemini-api/docs/pricing),
[Luna 모델 가격](https://developers.openai.com/api/docs/models/gpt-6-luna))

| 요청 범위 | 출력 요청 한도 | 출력만의 조건부 최대 비용(USD) |
| --- | --- | --- |
| 개인 Luna 질문 1회 | 2,048 | 0.001024 |
| 최종 Gemini 1회 | 32,768 | 0.122880 |
| 최종 Luna fallback 1회 | 32,768 | 0.016384 |
| 최종 분석 한 invocation의 최대 4+1회 | 각 32,768 | 0.507904 |

이는 실제 사용량 예측·실청구·모임 전체 상한이 아닙니다. 전체 입력·schema·reasoning/캐시·서비스 tier·
세금/환전·개인 Jev/Luna 검사와 새 correction/HOST invocation을 별도로 계산해야 합니다. 최종 Luna는
검증된 요청의 `service_tier`를 생략한 상태이며 개인 Luna의 명시적 `default`와 다릅니다. 응답의 실제 tier는
확인되지 않았습니다. 검증한 최종 prompt/schema와 두 최종 출력 한도는 그대로 보존했습니다.

현재 입력 제한은 개인500/방10,000 Unicode code point·최대50명입니다. 개인 Luna의 전송 전 검사는
전체 요청16,384바이트이고, 입력4,096토큰 검사는 **응답 usage를 받은 뒤** 수행합니다. 최종 요청은 실제
전송 바이트를 고정·해시 검증하지만 32MiB capture 제한은 청구 토큰 상한이 아닙니다. 대표 입력의
26–77KB 오프라인 측정이나 최대 모델 context 전량의 $6.52 계산으로 실용적 방 cap을 확정하지 않습니다.
정확 모델의 전체 요청 토큰과 유효 가격/만료를 결속한 operation 최대액 근거가 필요합니다.
입력을 포함한 한 invocation의 조건부 산식은 `(0.75×Gemini 입력 합계 + 3.75×Gemini 출력 합계 +
0.10×Luna 입력 + 0.50×Luna 출력)/1,000,000`입니다. 이때 출력은 과금되는 reasoning을 포함하며
캐시·tier 등 계약 차이는 별도 반영해야 합니다. 기존 usage 기반 추정 지표나 고정 환산율은 이 근거를 대신하지 않습니다.

관리형 예산은 승인된 최종 `PRIMARY` 최대액의4배와 `FALLBACK` 최대액의 합을 신규 개인 예약에서 보호합니다.
가상 보호분을 실제 hold로 중복 계상하지 않으며 같은 예약의 replay에도 다시 청구하지 않습니다.
이는 미래 최종 invocation 한 번의 여유이고 반복 실행 전체의 비용 보장이 아닙니다. cap 부족·만료·근거
누락은 전송 전에 거부합니다. `usageComplete=true`도 완전 청구 증거가 아니므로 production의
`completeBill()`은 현재 `null`을 유지합니다. 낮은 usage로 hold가 풀리는 운영 정산은 아직 지원하지 않아
누적 예약 때문에 정상 요청도 중단될 수 있습니다. 임의 가격으로 환급하지 않습니다.
HTTP gate는 usage 관측만 전달합니다. 실제 완전 청구 증거를 공급하고 해당 예약의 `settle()`을 호출하는
운영 소비자도 아직 연결되지 않았으므로 resolver 하나를 추가하는 것만으로 정산이 완성되지 않습니다.

Jev 기본 confidence는 `null`입니다. 검토된 development60/holdout60/보강16의 gold는 실측 예측이 아니며
과거 다른 모델의0.8/0.1을 운영 임계값으로 재사용하지 않습니다.
([평가 범위](evaluation/issue123-classification-evaluation/README.md),
[공급자 confidence 설명](https://docs.typesafe.ai/confidence))

OAuth 콘솔 설정은 사용자 완료 상태입니다. 남은 확인은 기존 runtime typed 설정 전달·RS256 파일의
UID10001 가독성/읽기 전용 mount·V11–V17 적용 및 복구 준비입니다. 기존 main preflight는 V1–V10만
확인했습니다. 이 실행기의 GitHub 연결은 secret 메타데이터 endpoint 조회를 허용하지 않으며 새 대상의
host 증거도 아직 없습니다. 운영 AI 정책은 `UNCONFIGURED`·OFF를 유지합니다. 코드/CI 완료를
운영 준비 완료로 표시하지 않으며 main 병합이 자동 게시·배포를 촉발하기 전에 이 차이를 해소해야 합니다.
OFF는 기존 legacy 최종 분석 동작을 보존하므로 전체 AI의 전역 중지 스위치가 아닙니다. 관리형 보호가
적용되지 않은 기존 호출을 이 예산으로 계상했다고 주장하지 않습니다.


## 이전 기록 — 2026-10-17/18

다음은 당시 상태와 승인 범위의 이력이다. 위 최신 범위와 혼용하지 않는다.

2026-10-17 UTC. 최신 사용자는 웹 기존 main 경로 유지 및 대응 서버 main 병합을 조건부 승인했다. 별도 웹 develop/CI 경로는 만들지 않는다. 검증 없이 즉시 운영에 반영하는 승인으로 해석하지 않는다.

### 당시 판정

2026-10-18 후속: 서버 PR121의 develop01d00e7f·merge CI39070926139는 완료됐다. 현재 별도 feature/issue-107-decision-precheck에서 승인된 Decisions→필요시 생성 LLM의 기본 OFF 구현/독립 검증을 준비하며, 이번 승인은 Draft/exact HEAD CI까지다. 실제 금액/계정/청구/품질·durable 모임 예산/최종 분석 전체 연결은 미완료이고 Decisions의 main/develop 병합·배포는 하지 않는다. 최신 비-Decisions 기존 변경의 develop 통합 승인은 별도 격리 대조로 다루며, 합산 USD1 평가 승인도 [실행 조건](ISSUE_107_USD1_EVALUATION_EXECUTION_PLAN.md)의 비용 증명 전에는 호출하지 않는다. [2단계 계약](ISSUE_107_TWO_STAGE_CONTRACT.md)·[평가/키 준비](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md)를 최신 후속으로 본다. 아래 단일 Gemini 설명과 이전 문서 링크는 기존 runtime 준비 이력이다.

**main 병합은 준비 조건 확인 전 보류.** 구현·격리 테스트·서버 develop 통합은 계속한다. main 병합을 통해 아직 승인되지 않은 DB/Secret/권한/유료 모델 작업을 간접 실행하지 않는다.

서버 PR120은 develop `bb996482a3090739a8ce02c8b093561e2c5c58a2`로 일반 병합됐고 [정확 merge CI39064886318](https://github.com/meet-me-duo/meet-me-server/actions/runs/39064886318)이 성공했다. 웹 PR31 HEAD `02a68d8e50680e87dd153156e8bb7ea884a794cc`의 [CI39063558547](https://github.com/meet-me-duo/meet-me-web/actions/runs/39063558547)도 성공했으며 Draft·미병합이다. 실제 모델/OAuth/운영 준비 성공과 구분한다.

남은 코드·공식 근거 정정·독립 테스트·평가/비용 명세는 [실제 모델 평가 준비안](ISSUE_107_REAL_MODEL_EVALUATION_PLAN.md), 완료된 공개 설정을 반복하지 않는 사용자 순서는 [외부 준비 안내](ISSUE_107_EXTERNAL_READINESS_RUNBOOK.md)를 따른다. 운영자 요약은 기본 OFF로 유지하며 core 출시의 차단 조건이 아니다.

| 확인 대상 | 저장소 근거 | 남은 조건 |
| --- | --- | --- |
| 서버 main 자동 배포 | `.github/workflows/deploy.yml`의 main push CI 성공 후 build/publish, 이후 기존 승인 release contract·host guard | 새 catalog/guard 지문은 기존 승인을 재사용할 수 없음. 실제 현재 host/history/approval의 읽기 검증과 운영 schema 전환 승인 필요 |
| Flyway | `application.yml`은 enabled, 새 기능은 V11~V16 필요 | 운영 DB migration은 현재 미승인. 최근 문서의 V1~V7 기록은 현재 DB 상태 증명이 아님. 키나 DB 값을 읽거나 바꾸지 않았음 |
| 인증 초기화 | `AccountAuthConfiguration`은 signing 설정 전부 비어 있으면 AUTH_UNAVAILABLE, 일부만 있으면 시작 거부. #109는 신규 HOST 생성/명령에 계정 요구 | Google/Kakao 공개 앱 설정 완료 인계 유지. 남은 서버 자격 증명 전달·RS256/검증 JWKS·정확 runtime callback/Origin·실로그인 미확인. 익명 HOST의 명시 claim도 로그인 준비 필요 |
| 운영 인증 전달 | 후속 renderer는 typed AUTH/Google/Kakao·optional AI OFF flag를 원자 전달하며 Compose는 UID10001의 read-only RSA/JWKS bind·missing path 자동생성 금지를 적용한다. auth marker/renderer/compose를 current17 승인 지문에 포함 | 코드·합성54/관련 회귀 검증과 실제 외부 준비는 별개. 정확 후속 HEAD CI·운영 파라미터/파일/권한·실로그인 확인 필요. Secret 읽기/생성/입력0 |
| 웹 main 배포 | `.github/workflows/deploy.yml`은 main push→production Environment→S3/CloudFront | 대응 서버가 새 인증·proof·check protocol을 지원하고 실제 준비됐다는 증거가 먼저 필요. UI를 먼저 배포해 로그인/미지원 API를 강요하지 않음 |
| 새 개인 AI 검사 | 후속 production bean은 `MEETME_PRECHECK_PROVIDER_ENABLED=false` 기본 바인딩·고정 GenerateContent3.8/LOW/2048·count4096 gate/사후 usage 방어를 제공한다. key/임의 verified flag만으로 켜지지 않는다. OFF에서는 CONFIGURATION·count/generation0이며 신규 방 확인을 완료할 수 없다 | **main 사용 차단 조건 유지**. activation/input gate 코드 준비와 실제 지원·billed input cap/청구·모임 총10원 정책 결정은 별개. 실제 opt-in/유료 호출 미승인, 합성 성공이 해소하지 않는다. 조건부 비용 산식을 보장으로 사용하지 않으며 실제 OFF 유지 |
| 복구 | ADR-046/051/052 및 release guard는 구 writer 혼합·잘못된 image rollback을 허용하지 않음 | 신규 schema/protocol 작성 후 구 이미지 rollback 가능하다고 주장하지 않음. 정확 호환 image와 roll-forward·복구 절차를 검증하고 운영 전환 별도 승인 |

### 당시 안전한 확인·실행 순서

1. 통합 코드의 독립 TDD/PG/proof/synthetic provider/browser·결함 주입·전체 gate·독립 최종 리뷰와 exact HEAD CI.
2. 서버 develop의 승인된 단위를 통합하고 exact merge SHA CI 확인. 원격 개발 URL이 없는 현 상태는 별도 알림.
3. 아침에는 새 통합의 정확 SHA/JAR·웹 자산 hash, 격리 PG/Redis, loopback OIDC+합성 RSA/provider, 외부 요청 차단과 합성 계정으로 시나리오 재현. 문서의 운영 URL을 개발 URL로 사용하지 않음.
4. main 전 필요한 외부 조치는 이름·존재/상태·승인만 확인하는 별도 체크로 묶어 부모에게 보고. 비밀값을 채팅/저장소/로그에 요청하거나 출력하지 않음. 미승인 설정을 우회한 임시 로그인·익명 HOST fallback도 추가하지 않음.
5. 위 조건이 충족된 조합만 서버 호환성/데이터 전환을 먼저 확인하고 웹 main을 이어 처리. 실제 배포 완료는 정확 커밋/image·workflow와 실행 증거가 있을 때만 기록.

현재 운영 endpoint·AWS/SSM·DB·Secret은 호출하지 않았다. 별도 staging/develop 서버 주소·현재 실행 SHA는 읽은 저장소 자료만으로 확인되지 않았다. 현재 격리 native API18920/web18921/control18922를 준비했다. 샌드박스 내부 주소이며 공개 staging이 아니다. 상세 `ISSUE_107_LOCAL_SCENARIO.md`.
