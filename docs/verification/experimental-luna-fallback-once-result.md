# 합성 Gemini503 → 실제 Luna 단일 fallback 검증 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

## 결과

새 승인 `synthetic-authorization-cfc3122fe73a6ef1`의 실행 `11ef9a92-0dc0-4094-ba1b-cacb5630e565`는 **실제 Luna1회로 HTTP200·strict 구조화3개·후보/추천 각1개·주최자 확정까지 통과**했다. 실제 Gemini/Jev 요청은0, 실제 Luna 재시도0이며 추가 유료 호출 없이 종료했다. Gradle exit0/JUnit1PASS·실패/오류/skip0이다.

| 실제 관측 | 값 |
|---|---|
| 모델·호출 계층 | `gpt-6-luna`, 원본 `OpenAiLunaNaturalLanguageParserAdapter`의 Responses REST API/OkHttp. OpenAI SDK를 쓰는 경로가 아님 |
| endpoint | `https://api.openai.com/v1/responses` |
| 실제 요청 수 | admission1 / CALL_START1 / HEADERS_START1 / HEADERS_END1 / FINAL_FALLBACK ordinal1 |
| 응답 | HTTP200, transportFailure없음, adapter failureKind없음 |
| 시간 | HTTP5.564061744초 / adapter5.734450055초 / 방 생성·입력~확정 검증10.864962180초 |
| 제한 | caller26.995184908초, 실제 CallTimeout26.833614647초. 모두27초 이내 |
| 사용량 | 실제 응답에서 저장한 input2879/output355 tokens. 실제 청구액미확인/null |
| 구조화 | 원본 strict schema 통과3개, 거절0, 모두2026-11-12 KST18:00–20:00 |
| 불변 근거 | 동결3개와 구조화3개가 동일 batch/version으로 조인됨 |
| 서버 후처리 | candidate1/recommendation1,3명 모두 반영, 미반영 입력0, 추천 시간이09–11Z 공통창 안에 있음 |
| 주최자 | confirmation200/result조회200/CONFIRMED. 재조회 selection의analysis/option/variant ID 및 시작/종료가 요청한 값과 일치 |
| 권한 경계 | 원본 Jdbc source 승인3회, source/owner/revision/profile 검사를 생략하지 않음 |

adapter 시간은 원본 Luna parser 진입~반환, HTTP 시간은 실제 OkHttp lifecycle이다. 전체 흐름 시간에는 컨테이너·Spring 기동과 Gradle/정리 시간이 포함되지 않는다. 한 번의 관측을 일반 latency/SLA나27초 timeout 연장 효과로 해석하지 않는다. 토큰 사용량은 응답 usage에서 관측한 값이며 청구 횟수/금액과 같다고 가정하지 않는다.

## 합성과 실제의 구분

- 원본 Gemini parser 대신 포트 test double이503에 해당하는 `SERVER`와 `retryAfterMillis=40000`을 한 번 반환한다. 실제 Gemini HTTP는0이다. HTTP503→SERVER 분류는 기존 별도 mock 회귀로 확인한 계약이며 이번 주입 자체가 Gemini SDK/HTTP를 거친 것은 아니다.
- 40초 Retry-After가 기존 Gemini 단계30초보다 커서 원본 processor가 대기/재시도를 생략하고 Luna로 넘어간다. 생산 재시도 정책을 바꾼 것이 아니다. 일반503 무헤더에서의 모든 재시도 조합 또는 실제 Gemini 장애 직후의 실모델 연속 호출을 이번에 실행한 것은 아니다.
- 개인 사전확인은 명시적 CLEAR test double이다. Jev와 Luna 보완 질문 호출0이다. 입력3개는 동일한 명시 시간의 새 합성 데이터이며 운영/사용자 입력·봉인 holdout을 사용하지 않았다.
- 실제 Luna는 원본 adapter·프롬프트·v3 strict schema·maxOutput32768 및 원본 gate/source 검사를 사용한다. 합성 응답으로 대체하지 않았고 `syntheticLunaResponses=0`이다. 최종 후보/추천은 기존 결정론적 서버가 계산한다.
- 따라서 검증 범위는 **합성 Gemini503·CLEAR 뒤 실제 Luna 서비스 fallback과 저장/매칭/확정 경로1건**이다. 실제 Jev→보완 질문→답변→전체 분석의 블라인드 정확도 검증은 여전히 미완료다. 일반529의 분류 차이와 저신뢰 질문 정책도 이번에 변경하지 않았다.

## 무료 준비와 수정 이력

첫 무료 실행은 managed flag가 불일치한 fixture 설정 때문에 context 기동 중 거절됐다(실제 API0). 둘째는 loopback endpoint의 fingerprint가 원본 fence의 공식 profile과 달라 거절됐다(실제 API0). 권한 검사를 완화하거나 profile을 위장하지 않았다.

최종 무료 fixture는 공식endpoint/profile·원본 source 허가를 유지하고, 그 뒤 application interceptor가 합성 응답을 반환한다. sourceChecks2/syntheticResponse1/실제wire0으로 관측됐다. 무료 사용량100/50은 합성 응답의 값이며 실제 사용량2879/355와 분리했다. 이 무료 성공은 네트워크 전송이나 native27초 timeout 효력 검증을 뜻하지 않는다.

독립 검토가 발견한 잘못된 `meeting_rooms.status` 조회를 실제 `collection_status`로 정정하고 snapshot.available을 통과 조건에 추가했다. 일부 입력이 제외돼도 PASS하지 않도록 거절0·각조건비어있지않음·applied=total=3·unapplied빈배열·참석3·선택ID/시간 일치를 검증한다. 이 변경은 test-only이며 생산SQL/정책을 바꾸지 않는다.

최종 무료 검증69개 중68PASS·유료기본OFF1SKIP·실패/오류0, ktlint PASS다. 원본 source 거절 우선·같은 immutable identity 재검증/다른 attempt 거절·비fallback/미결속 admission/중복header 거절과 기존 gate·27초 예산·fallback 회귀를 포함한다. 첫 실패 XML/로그와 수정 뒤 결과를 각각 보존했다.

## 소스·중복 방지·보존

- 실행 기준 HEAD `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a` + 검증된 로컬 test/통계SQL 변경이다. source/compiled2671개를 실행 전후 재해시해 모두 동일함을 확인했다. 실행 manifest SHA: `656c057c11de51bc5b75a160de13872dd7740ea91e8d2c824735089aee237007`.
- 실제 결과 SHA: `31aaa7eba9a86f7a72ce71fc80a566deb178cebf0b10c476db74d780330c8d2c`.
- 승인 단위 launch intent/approval claim과 실행 plan claim은 모두 소비 상태다. wire ledger는1/maximum1·pending없음이다. 같은 승인을 새 ID로 재사용하거나 결과 확인을 위해 재전송하지 않는다. 기존 cap10/SDK1회 승인은 재사용하지 않았다.
- 사전·사후 독립 검토 P1/P2=0. 생산main289파일·기존 통계SQL patch·기존 실제 평가 원본은 보존했다. main/develop 통합·운영 배포·DB/방 접근·키/권한 확대·UI 변경0이다.
- 새 커밋/게시 및 전체 commit guard 실행은 없다. 이번 검증은 승인된 격리 test-only 범위이며 실제 API 요청은 이1회로 끝났다.

실제 결과·DB snapshot·사용량·정규화 조건·XML·원장: `local-only-evidence/luna-fallback-once-11ef9a92-0dc0-4094-ba1b-cacb5630e565`. 무료 자료·manifest·one-use launcher: `local-only-evidence/luna-fallback-preparation-20261030`.
