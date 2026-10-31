# 새 승인 pilot10 실모델 검증 결과 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**부분 검증 후 최종 Luna TIMEOUT으로 중단했다. 전체 흐름 통과가 아니다.** 실제 질문1·고정 직접답변1·미해결 접수 및 원문/답변 동결3건은 확인했지만 최종 구조화·추천·미반영 사유·확정 저장은 미검증이다. 추가 API 호출은 하지 않았다.

## 실행 및 승인

- 사용자 승인 `synthetic-authorization-300cb71b79ff69b2`: 개발 pilot1모임/3명, 실제 Jev≤6·개인 Luna≤3·최종 Luna≤1, 총≤10, 실제 Gemini0. 재시도0·첫 기술 오류 전역 중단.
- run `d2119585-667e-47c4-8877-5d4152fd47e2`, 실행은1회다. 신규 claim/intent/ledger를 사용했고 예전 cap20 잔여분은 사용하지 않았다. 이번에 남은3회도 재사용하지 않고 종료한다.
- HEAD `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a` + 승인된 격리 V2 로컬 변경. Choice 라벨 우선, IN_PERSON, 개인 공유15초·최종 Luna 최대27초·전체60초/완료예약3초를 유지했다. prompt/threshold/모델/운영 제한을 변경하지 않았다.
- manifest SHA256 `de7f0acf8b4ae554d83de4a2ad9d149a4a3feb87b46f6b6756ae092f158e7e2d`, source aggregate SHA256 `b30c76cda4d67d11d04b36518c8e788255c49378ac38e9a1e0fc33e6abe145d5`.
- 고정한 source/compiled/config 파일2713개와 pilot3개 파일의 전후 해시는 모두 같다. 봉인 시나리오 읽기/변경0, 웹 변경0, commit/PR/병합/운영 배포0이다.

## 실제 요청과 라벨 분기

| 실제 공급자 | 물리 요청 | 관측 결과 |
|---|---:|---|
| TypeSafe `jev-1.14.0`, System One Choice | 4 | HTTP200 4개 |
| OpenAI `gpt-6-luna`, 개인 Responses | 2 | HTTP200·NEEDS_CHOICE1/CLEAR1 |
| OpenAI `gpt-6-luna`, 최종 Responses | 1 | TIMEOUT, HTTP상태 미관측 |
| 실제 Gemini | 0 | SERVER/503+RetryAfter40초는 port test double |
| 합계 | **7/10** | 첫 기술 오류 후 전역 중단 |

7개 모두 송신 전 영속 예약·header emitted·call finished가 확인된다. 원본 JDBC source authorization21회이며 외부모델 역할별6/3/1 제한을 넘지 않았다. `coordination_attempts`의 Gemini1행은 합성 primary 단계이며 실제 Gemini 요청으로 세지 않는다.

| 참여자/단계 | 파싱된 Jev semantic label / confidence | 실제 후속 동작 |
|---|---|---|
| p1 최초 | NEEDS_REVIEW /0.35 | 개인 Luna NEEDS_CHOICE, TEXT_ONLY 질문1 |
| p1 답변 후 | NEEDS_REVIEW /0.64 | 추가 Luna/질문0, RECEIVED·UNRESOLVED 접수 |
| p2 최초 | NEEDS_REVIEW /0.31 | 개인 Luna CLEAR, 질문0, CONFIRMED |
| p3 최초 | CLEAR /0.58 | 개인 Luna0, CONFIRMED |

기존0.8 설정보다 낮아도 V2의 유효 라벨을 덮어쓰지 않았고 p1/p2가 실제 개인 Luna에 도달했다. 위 라벨/확률은 parser 결과와 실험 관측이며 공급자 raw 응답 바이트를 보존했다고 주장하지 않는다. 답변 후 NEEDS_REVIEW가 남더라도 질문을 반복하지 않고 미해결 정보까지 접수하는 합의가 실행됐다. 이 한 사례에 맞춰 classifier prompt나 고정 답변을 바꾸지 않았다.

개인 Luna는 도착 가능 시각을 묻는 질문 한 번을 생성했다. 질문 응답 본문은 공개하지 않는다.

대상은 도착 가능 시각에 관한 원문 구간이며 선택지0개의 TEXT_ONLY였다. 독립 oracle은 사전 고정된 참여자의 도착 가능 시각/요일·불가시간·선호를 그대로 직접 답변했다. 새로운 사실을 만들거나 원문·질문·gold를 수정하지 않았다. 정확한 답변은 실행 결과의 `pilot/answer-decisions/9eb7fbe1-3898-41d6-9ad9-052a89f55ceb.json`에 보존돼 있다.

## 시간 초과: wire·caller·저장 분리

| 관측 층 | 실제 값 |
|---|---|
| 최종 caller 잔여 예산 | 26.995410560초 |
| wire hook Call 경과 | 26.879518916초 |
| adapter wrapper 경과 | 27.004642555초, FAILED |
| transport DB 경과 | 26.878637167초, FAILED/HTTP null |
| wire 오류 | TIMEOUT/HTTP null |
| DB attempt 오류 | CONFIGURATION |

이번에는15초에 잘린 것이 아니라 약27초 예산에서 실패한 Call을 관측했다. Call 경과는 네트워크 응답 이후의 의미 해석 성공 시간이 아니다. 원격이 왜 그 안에 응답하지 못했는지, 원격 처리·청구가 어디까지 진행됐는지, SDK 취소와 원격 reset 중 최초 원인은 미확인이다.

원래 최종 adapter는 IOException 분류 후 `finally physical.observe()`의 dispatch 예외가 원래 실패를 CONFIGURATION으로 덮을 수 있다. 이번 결과도 wire TIMEOUT과 DB CONFIGURATION을 그대로 나눠 보존한다. 어느 deadline 검사에서 관측 거절됐는지까지 확정하는 trace는 없으며 저장 오류를 실제 HTTP 오류라고 바꾸어 보고하지 않는다. 예전 실패와 달리 이번 transport DB에는 FAILED1건이 남았다. source/timeout/실패분류 코드를 실행 후 수정하지 않았다.

## 확인된 연결과 미검증

독립 채점은 `pilotPass=false`, 부분 통과다.

- 실제 질문1/사전 고정 답변1, 원문 보존과 보완 답변 DB 저장, 답변 후 추가 질문0을 확인했다.
- p1의 미해결 상태를 CLEAR로 위장하지 않았다. 원문·보완·미해결 정보가 포함된 수용 version을 포함해3건을 동결했고 source/version 연결3/3을 확인했다.
- 최종 adapter/원본 source fence를 통과한 실제 Luna Call까지 도달했지만 결과가 없었다. 공급자가 원문을 성공적으로 해석/반영했다고 주장하지 않는다.
- structured0, 추천·HOST 미반영 응답·주최자 확정 저장·브라우저 전체 흐름은 이번 실제 실행에서 미검증이다. NO_MATCH 판정이나 정상 통과로 해석하지 않는다.
- 실패 시에도 `coverage.questionObserved=true`, `answerSent=true`, 미도달 `unappliedObserved=null`, `selectionPersisted=null`로 실제 진행을 보존했다.
- 실제 JUnit1FAIL/Gradle exit1이며 실패를 PASS로 바꾸지 않았다. launcher outcomeUncertain=false는 프로세스 종료를 확인했다는 뜻이며 원격 요청의 처리·청구까지 확정됐다는 뜻이 아니다.

## 관측된 사용량

| 공급자 단계 | 입력 token | 출력 token | 제한 |
|---|---:|---:|---|
| Jev 성공4회 | 4,724 | 205 | classifier 단계별 관측 합계 |
| 개인 Luna 성공2회 | 1,927 | 365 | OPENAI precheck DB2개만 합산 |
| 최종 Luna1회 | 미관측 | 미관측 | timeout 사용량을0으로 두지 않음 |

성공6회에서 관측한 부분합만 입력6,651/출력570이다. `PRECHECK`의 분류 usage가 다시 나타나는 경우를 중복 합산하지 않았다. 최종 실패의 token/청구액은 미확인이고 전체 실제 청구액은 확인하지 못했다. 출력 내 reasoning token을 별도 가산하지 않는다.

## 준비 검증과 증거

새한도 테스트11개는 기존20회 하드코딩 skeleton에서6FAIL을 탐지한 후 새6/3/1/10·immutable caps로 통과했다. 새 preflight2개·기존budget13·timing2·라벨63을 포함해91PASS, 보고 상태 보존2개를 더한 최종93PASS/skip0·ktlintCheck/assemble PASS였다. 독립 읽기 검토의 P2(후속실패 시 앞선 질문/답변을false로 오보고)는 실행 전 수정·재검증했고 남은 P1/P2 없이 실행했다. 이는 무료 사전 검증이며 실제 실패 실행의 성공 근거로 대체하지 않는다. 이전 [무료92개 연결 결과](experimental-v2-free-flow-preparation.md)와 구분한다.

- 준비/manifest/source proof/무료93개/XML/프로세스 결과: `local-only-evidence/pilot10-20261030`
- 실제 원장/DB/최종 결과: `local-only-evidence/pilot10-d2119585-667e-47c4-8877-5d4152fd47e2`
- 독립 채점: 같은 경로의 `pilot/independent-grade.json` (판단 세부는 private 파일에 보존)
- 사용량·보존 검증: 준비 폴더의 `observed-usage.json`, `post-run-verification.json`.

승인된 단일 실행은 종료됐다. 잔여3회 또는 예전 잔여16회로 자동 재시도하지 않는다. 추가 유료 호출 없이 기존 코드와 결과를 보존한다.
