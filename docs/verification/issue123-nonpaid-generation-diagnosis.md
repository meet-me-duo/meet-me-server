# #123 LIVE-KO-03 생성 실패 비유료 진단

작성일 2026-10-18 UTC. 저장된 합성 사례 증거와 공개 공식 문서만 읽었다. 이 진단 작성자의 실 API·모델 호출, 키·개인 계정·운영 접근, 제품 소스·테스트·배포 설정 변경은 모두 0이다. 아래 60초는 독립 평가 제안이며 제품의 공유 15초 제한을 바꾸지 않는다.

## 확인한 증거

증거 루트는 `/workspace/issue123-durable-budget/server/.codex/tdd-evidence/` 아래 `issue123-live-two-stage/`와 `issue123-live-two-stage-attempt2/`이다. 각 `*-consumed.json`, `live-two-stage.json`, `live-invocation.json`, 요청·count 응답을 대조했다.

| 항목 | 첫 시도 | 둘째 시도 |
|---|---|---|
| A HTTP | 200, HTTP 855.0ms | 200, HTTP 394.6ms / callback 전체 452.440299ms |
| A 정규화 | INDETERMINATE / LOW_CONFIDENCE / confidence .06, 평가 fixture threshold .8 | 동일 |
| A 반환 사용량 | input512/output0, usageComplete=true | 동일 |
| Count | 200, totalTokens530, HTTP225.9ms | 200, totalTokens530, HTTP245.3ms / callback 전체293.185067ms |
| B | 503, HTTP347.8ms, STOP_HTTP_FAILURE_UNKNOWN_HELD | UNKNOWN_HELD만 존재; 종료·HTTP status·headers·response 미기록 |
| 전체 JVM | exit2, 2177.7ms | exit2 TIMEOUT, 15538.3ms |
| B 결과·사용량 | 없음/unknown | 없음/unknown |

A 원 요청은 2397B / SHA256 `fbc526b0d3fe7e014cd3fd379dd8a9d969ec8218c659ff2e60df0ba8e1d81a52`로 두 시도 동일하다. 생성 요청은 **3111B** / SHA256 `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44`, count 요청은 **3172B** / SHA256 `1153b46eab50217239a307b73de69b0a5ef494d84fd78ce14ba3b1a7fb90702f`이다. 둘 다 16KiB 이하다. count JSON의 `generateContentRequest`는 실제 생성 JSON에 `model=models/gemini-3.8-flash`만 추가한 구조와 정확히 같다. 생성 설정은 LOW / candidateCount1 / maxOutputTokens2048 / 기존 strict JSON schema이다. 요청 크기·상한·동등성 점검에서는 오구성 증거가 발견되지 않았다. count 성공은 생성 성공·사용 가능한 생성 capacity·응답 지연·실청구액을 보장하지 않는다.

둘째 시도의 A+C callback 소요 합은 약745.625ms이므로, 다른 경계 비용을 빼기 전 B 가용 시간은 약14.254s 이하이다. 실제 callback argv/헤더시점이 기록되지 않아 정확한 서버 생성 예산이나 순수 모델 지연으로 표현할 수 없다. 전체 JVM 15.538s에는 JVM 기동 등의 비용도 있어 B 지연으로 그대로 쓰지 않는다.

## 가능한 원인과 단정 불가 범위

첫 503은 일시적 service/capacity 문제와 양립한다. 그러나 error body/status-name/message·Retry-After·provider request ID를 버려 특정 모델 과부하, 전역 장애, gateway 원인, 계정 제약을 구별할 수 없다. 첫 하네스의 최종 CONFIGURATION은 callback 비정상 종료를 정적으로 감싼 코드다. 제품 설정이 잘못됐다는 증거가 아니며, 제품 adapter의 503→SERVER 계약과도 구분해야 한다.

둘째는 로컬 공유 예산 만료가 관측되었다. TCP/TLS/HTTP headers/첫 body byte/누적 body progress를 기록하지 않았고 부모가 subprocess를 종료하여 metadata도 0B다. 연결 지연, 네트워크 정체, 서비스 대기열, 내부 thinking·decode, 응답 전송 지연을 분리할 수 없다. UNKNOWN_HELD는 미전송·미실행·미청구를 뜻하지 않는다. 클라이언트 종료가 서버의 작업·청구를 즉시 취소했다는 증거도 없다.

이 두 건으로 요청 schema 거부, 한국어 미지원, cap2048 부족, 생성 품질, p50/p95, timeout 비율, 15초 내 안정 완료 가능성을 판단할 수 없다. 2048은 토큰 상한이며 초 단위 완료 보장이 아니다. LOW도 thinking0이나 고정 지연을 뜻하지 않는다. 첫 실패의 body가 없으므로 safety/finish reason을 소급 추정하지 않는다.

## 공식 공개 문서 대조

- 공식 troubleshooting은 429/503의 경우 대기·지수 backoff를 제안하고, SDK 기본 자동 재시도가 존재할 수 있다고 설명한다. 이번 제한된 평가/제품의 hidden retry0 계약은 그대로 유지해야 한다. 동일 문서는 thinking이 응답 지연과 토큰 사용을 늘릴 수 있다고 설명한다. 이는 가능한 원인이며 이번 timeout의 확정 원인이 아니다. [Gemini troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting)
- 공식 오류 표는 503을 일시적 서비스 과부하/중단으로 설명한다. 현재 이 페이지는 Interactions API 범위이므로, 이번 GenerateContent 503의 세부 JSON code가 동일했다고 소급 주장하지 않는다. 당시 수집된 사실은 숫자 HTTP503뿐이다. [API errors](https://ai.google.dev/gemini-api/docs/api-errors)
- 현재 thinking guide는 gemini-3.8-flash에 low/medium/high를 제시하고 동적 reasoning과 출력 cap을 별개로 설명한다. Interactions의 snake_case 예제를 GenerateContent wire로 복사하지 않는다. GenerateContent reference 자체는 thinkingLevel과 prompt/candidate/thought별 usageMetadata를 정의한다. 이번 LOW를 MINIMAL/OFF로 바꾸거나 cap을 늘리는 근거로 사용하지 않는다. [Thinking](https://ai.google.dev/gemini-api/docs/thinking), [GenerateContent reference](https://ai.google.dev/api/generate-content)
- Count는 입력 크기 사전 점검이다. 최종 generation의 반환 사용량·청구서와 구분해야 하며,530으로 알 수 없는 B input/output/thought를 대체하지 않는다. [Count tokens](https://ai.google.dev/gemini-api/docs/tokens)

공식 troubleshooting의 Status 링크는 [Google AI Studio status](https://aistudio.google.com/status)이다. 공개 web 도구는 본문1줄/상태 내용 없음이었고, 인증 없는 직접 GET도 URLError여서 실제 Developer API incident 상태를 확인하지 못했다. 대체 주소 status.ai.google.dev도 접근 불가였다. [Google Cloud Service Health](https://status.cloud.google.com/)에는 조회 시점 “No broad severe incidents”, 마지막 갱신 2026-10-17 22:32 PDT가 보였다. 이 Cloud dashboard는 Developer API 전체의 무장애 보증도, 과거 두 요청 시각의 상태도 아니므로 원인 배제 근거로 삼지 않는다. 계정 로그인·모델 GET은 추가 실행하지 않았다.

## 부모가 실행할 수 있는 최소 분리 평가 제안

1. 기존 UNKNOWN hold 총액 **USD0.70918144는 유지**한다. 부모가 전달한 새 Gemini+OpenAI 합산 승인 한도는 USD2이며, 단순 미예약 잔여는 USD1.29081856이다. 이는 실청구 잔액이 아니며 새 dispatch 전 부모의 검증된 기존 quote/보수적 hold가 추가로 필요하다. 이 문서는 가격이나 새 quote를 만들지 않는다.
2. 새 평가 invocation ID와 새 O_EXCL 소비 기록을 만들고 **B 한 번만**, 기존 3111B payload/SHA를 그대로 보낸다. A와 count 재호출은 0이다. 이전 count530과 동일 payload/model/schema라는 정적 증거를 명시한다. 이는 평가에서 저장된 count를 재사용하는 것이며 제품 count admission 경로를 변경하는 것이 아니다.
3. 제품과 분리한 **전체 본문까지 60초** monotonic 관측 상한을 둔다. transport 자동 retry·redirect·model fallback0, 응답16KiB, LOW/candidate1/output2048 유지. 한 번의 전송이 끝나기 전 추가 호출하지 않는다. 실제 실행 권한은 부모에게만 있으며 본 진단자는 실행하지 않는다.
4. 성공하면 production B contract로 파싱하고 INPUT/CANDIDATE/THOUGHT 원래 값과 unknown을 그대로 보존한다. recorded A .06/.8 + 실 B를 actualTwoStage로 **offline replay**하고, 선택형 질문이 “8시~9시”의 결과 관련 오전/오후 모호성을 보완하는지/원문·날짜·기존 문맥을 왜곡하는지 독립 검토한다. 이는 fresh A→count→B 15초 end-to-end 성공이나 production SDK/인증/예산 원장 실경로 검증으로 표현하지 않는다.
5. 필요하면 transient503/429/408/5xx 뒤 부모가 명시적으로 선택한 **최대1회의 추가 단독 B**만 별도 invocation/hold로 허용하는 안이다. Retry-After가 유효한0..60초라면 대기 기록 후 새 호출,60초초과/해석불가나 예산 부족이면 중단한다. 자동 재시도는 없다. 400/401/403/404, schema/cap 불일치, 두 번째 실패, 전체 합산 한도 불확실성에서는 중단한다. 기존 두 UNKNOWN을 환불하거나 새 요청과 합치지 않는다.

## 새 평가 runner 관측 항목

부모 제안인 allowlisted error code/status/message(실제 key 값 redaction 후), Retry-After<=60초, headers 수신 즉시 상태 기록, last-progress byte count는 현재 원인 분리에 적절하다. message도 길이 제한·control character 처리·query/header credential 비기록이 필요하다. 각 상태는 임시 파일+원자 교체 등으로 즉시 보존하고, 종료 시 UNKNOWN을 가짜 terminal/0usage로 바꾸지 않는다.

추가로 monotonic request-start / headers / first-body-byte / last-progress / completion / local-timeout, HTTP status, responseBytes, exact request SHA, 제한된 provider request ID를 남기면 연결/응답 대기와 body 전송을 일부 구분할 수 있다. DNS/TLS 세부를 계측하지 않으면 headers 전 대기 안에서 네트워크와 서버 think를 완전히 분리할 수 없다는 한계는 유지한다. 503 body는 제한 크기 안에서 정적 allowlist 필드만 정제해 보존하고 전체 원문·credentials는 로그에 남기지 않는다. 제품 provider 로그나 timeout은 이 평가 때문에 수정하지 않는다.

현재 결론: 전송 payload의 크기·count 동등성·고정 설정은 확인됐지만, 아직 생성 질문의 실제 품질이나 15초 경로의 성공은 확인되지 않았다. 단독 B 관측은 이 둘 중 품질과 느린 완료 가능성만 분리해서 점검하는 다음 수단이다.
