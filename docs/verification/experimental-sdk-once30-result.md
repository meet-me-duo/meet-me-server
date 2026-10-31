# 합성 SDK 단일 유료 진단 결과 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

## 실제 실행 결과

새 사용자 승인 `synthetic-authorization-ef53e85dfd95c050`에 묶인 실행 `d4e8dcfb-d812-4a73-b231-7a4fe37f5bbd`를 정확히 한 번 실행했다. **2.411399794초에 HTTP503/SERVER로 종료했으며 재호출하지 않았다.** 30초 timeout에 도달한 실행이 아니다. JUnit1건은 정상 모델 응답 미확인으로 FAIL, Gradle exit1이며 이를 모델 또는 전체 서비스 성공으로 표시하지 않는다.

| 항목 | 고정 설정·실제 관측 |
|---|---|
| 소스 | HEAD `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a` + 보존한 로컬 test/SQL 변경. 새 main 통합 없음 |
| 실제 호출 계층 | 별도 test-only GenAI Java SDK1.72.0 → OkHttp4.12.0. 앱 adapter·gate·DB·방 마감 미호출 |
| 모델/endpoint | `gemini-3.8-flash`, `https://generativelanguage.googleapis.com`, generateContent1회 |
| 요청 | 고정 합성 문장97 UTF-8 bytes, 3필드 schema170 bytes, wire body 최대4096 bytes |
| 출력 제한 | maxOutputTokens128, candidateCount1, thinking LOW |
| 타이머 | 단일 SDK 진단 예산30초, SDK 직전 잔여29,950ms, 실제 OkHttp callTimeout29.950초 |
| 응답 | HTTP503, typed SERVER, strict 응답 성공=false, finishReason미관측 |
| 상대 시각 | CallStart→headersEnd0.156976초→responseHeadersEnd2.208505초→CallEnd2.212015초 |
| 호출 수 | durable admission1, headersStart1, headersEnd1, SDK/client lifecycle1 |
| 추가 호출 | SDK/상위 retry0, fallback0, Jev0, Luna0, 자동 재실행0 |
| 사용량/비용 | input/output/thought/total tokens 모두null, 실제 청구액미관측. 0으로 확정하지 않음 |

SDK에는 별도 maxInputTokens 제한을 적용하지 않았다. 입력을 짧은 고정 prompt/schema와 wire bytes로 제한했으며 byte 수를 토큰 수로 표시하지 않는다. 이전 서비스 프롬프트·큰 출력 한도와 다른 최소 요청이므로 timeout만 바꾼 통제 실험이 아니다. HTTP503 응답을 받았다는 사실은 확인했지만 과부하 원인이나 503을 만든 원격/중계 계층은 보존된 메타데이터만으로 확정하지 않는다. 이번에529는 관측되지 않았다. 기존15초 실패의 원인을 소급 확정하지 않는다.

## 한도·소스·무료 검증

- 실제 전송 전 새 manifest SHA `243d5a9e5ed78d3c7687b4bc8e8ce7f36dd8a4bd8b66d146a9e3301f90c3b231`와 source/compiled2656파일을 고정했고, 실행 후 전부 같은 해시임을 확인했다.
- 승인 단위 CREATE_NEW launch intent와 독립 approval claim, 실행 단위 plan claim 및 network interceptor의 단일 durable admission을 모두 소비했다. 같은 승인으로 새 실행 ID를 만들거나 결과 불확실성을 이유로 반복할 수 없다. 기존 중단된 cap10 잔여8회는 사용하지 않았다.
- 작은 SDK probe의 로컬 TLS/ALPN HTTP/2 정상·실제30초 timeout·503 무재시도·redirect 차단·body timeout·두 번째 전송 차단·동시 counter·승인 재사용·strict response를 포함해 집중62PASS/유료기본OFF1SKIP, ktlint PASS를 확인했다. 별도529 추가 후 오류분류12PASS는 기존9+신규3이며 앞선62개와 중복을 더해 전체 수를 부풀리지 않는다.
- 전체 commit guard는 이번 test-only 후속에서 재실행하지 않았다. 새 커밋·원격 게시·main/develop 병합·운영 적용도 없다. 생산 main289파일, 기존 평가 증거6개와 통계 SQL 패치를 보존했다. 사용자/운영 입력·봉인 holdout을 사용하지 않았다.
- 모델 예외는 message/body/header/cause를 JUnit에 전달하지 않는다. Error·cleanup/파일 오류 등으로 안전 결과 파일을 쓰지 못하면 intent/claim/ledger만 남을 수 있으므로 그 경우 UNKNOWN으로 정지해야 한다. 이번 실행은 안전 결과·원장·프로세스 결과·XML이 모두 보존됐다.

## 사용자 설명과 현재 fallback 동작의 차이

고정 fallback 모델은 `gpt-6-luna`이다. 다음은 현재 코드와 모의 테스트 결과이며 운영에서 새 fallback 호출을 관측한 것은 아니다.

| 오류 | 현재 분류 | 기술 재시도·Luna 대상 |
|---|---|---|
| 503/UNAVAILABLE | SERVER | 예 |
| 일반529 또는529/UNAVAILABLE·OVERLOADED | INVALID_REQUEST | 아니요 |
| 529/RATE_LIMIT_EXCEEDED | RATE_LIMIT | 예 |
| 일반429 또는429/RESOURCE_EXHAUSTED | QUOTA | 아니요 |
| 429/RATE_LIMIT_EXCEEDED | RATE_LIMIT | 예 |
| typed timeout/network | TIMEOUT/NETWORK | 예 |

`ProviderFailureClassifier.kt:27–45`는 typed billing/quota/rate 코드를 우선하고500/502/503만 SERVER로 지정하며 나머지5xx는 INVALID_REQUEST로 분류한다. 일반529도 overload fallback을 기대한 사용자 의도와 차이가 있다. 이번에는 세 가지529 CSV 회귀로 **현재 매핑을 확인했으며 생산 분류를 변경하지 않았다.**

ADR-049와 `AnalysisInvocationBudget.kt:5`의60초는 Gemini단계30초 + Luna27초 + 완료3초 예약이다. `GeminiBatchProcessor.kt:117–119,307–325`에서 transient 오류는 남은 Gemini단계 내 최대4회 뒤 Luna1회로 진행한다. 정상적인15초 TIMEOUT 두 번은 Luna27초를 남기는 계약이며 해당 mock 회귀가 통과했다. 긴 Retry-After는 남은 예산에 맞지 않으면 sleep을 생략한다. Luna 진입 시 잔여1ms 미만,57초 경계 초과/DB 잠금 대기, 이전 Luna 사용 또는 run/owner/source 무효면 호출하지 않는다(`GeminiBatchProcessor.kt:434–458`, `JdbcAiBudgetExecutionFence.kt:292`).

f75 수정은 provider TIMEOUT/SERVER를 후속 observation 거부가 CONFIGURATION으로 덮지 않도록 보존한다. 진짜 config/source 거절 및 늦은 정상 응답의 observation 거부는 여전히 CONFIGURATION이다. 기존 c945의 미관측 status-null 원인을 소급 분류할 수 없다. 이번 진단에서 Luna0은 **승인된 fallback0 standalone 실험 설계** 때문이며 fallback 기능 고장의 증거가 아니다.

## 다음 최소 단계 — 아직 실행하지 않음

무료로 확인한 일반529 분류 차이를 먼저 정책 검토 대상으로 삼을 수 있다. 서버 분류나 운영 분기를 임의 변경하지 않았다. 503에 대한 실제 서비스 fallback 성공을 확인하려면 별도 승인이 필요하다.

다음 유료 최소 후보는 **격리 앱에서 합성 입력·Gemini503 test double·실제 Luna fallback 최대1회/27초**를 검증하는 것이다. Gemini/Jev 실제 요청0, Luna 물리요청 최대1, SDK/상위 retry0, 성공·오류·불확실 모두 즉시 종료하도록 별도 manifest/승인을 받아야 한다. 이는 실제 Luna 연결과 서비스 fallback 경로의 시험이며 실제 Gemini가 실패한 뒤 이어지는 완전한 실모델 E2E 또는 블라인드 품질 평가와 구분해야 한다. 현재는 제안만 했으며 준비·실행·추가 유료 요청0이다.

현재 목표인 실제 Jev→Luna 보완 질문→원문/답변 보존→최종 분석의 블라인드 검증은 계속 미완료다. 이번 승인1회는 소진했고 추가 호출 없이 정지한다.

원본 증거: `local-only-evidence/sdk-once30-d4e8dcfb-d812-4a73-b231-7a4fe37f5bbd`; 무료 검증·manifest·launcher: `local-only-evidence/sdk-once30-preparation-20261030`.
