# #123 USD2 합산 한도의 생성 질문 진단

2026-10-18 UTC. 사용자가 기존 Gemini+OpenAI 합산USD1에 USD1을 추가해 **모든 이전 호출·예약을 포함한 총USD2**를 승인했다. 최초 분류 FAIL/STOP, 실제 흐름의503/15초TIMEOUT과 미확인 예약USD0.70918144는 유지한다. 코드 PR126/b3770438은 기본 OFF/DENY이며 운영 설정·가격·confidence·product timeout을 바꾸지 않는다.

[비유료 원인 점검](verification/issue123-nonpaid-generation-diagnosis.md) 결과 두 요청의 전체3111B/SHA와 count530은 같다. 첫503의 오류 본문/RetryAfter는 미수집됐고 둘째는 헤더/progress가 없어 연결·대기열·thinking 지연을 구분할 수 없다. 공식문서의 일시적503/생성 지연 가능성이 원인 확정을 뜻하지 않는다. 기존 hold를0이나 무료로 정산하지 않는다.

따라서 [고정 실행 모드](evaluation/issue123-standalone-generation-oracle-mode.json)에서 **동일 생성 요청만 각최대60초 관측**한다. 추가A/count0, B최대2회·동시1이며 첫HTTP200/영구실패/출력오류에서 중단한다. 관측된HTTP200/영구오류가 body timeout으로 바뀌어도 재시도하지 않는다. transient HTTP408/429/500/502/503/504 또는 status미관측 network/timeout에만10초 간격의 후속1회를 허용한다. RetryAfter가10초를 넘거나 유효한ASCII정수/HTTP-date가 아니면 중단한다. 각HTTP 요청 자체 retry/redirect/fallback0이다.

기존 oraclec69의 LTS02–08(같은 날짜08–09/20–21시 선택 제안, 정확excerpt, 나머지의도보존)을 바꾸지 않는다. 새 raw response는 실제 production contract로 파싱하고 독립 의미 평가한다. 기존actualA0.06/합성threshold0.8·count530를 참고해 offline TwoStage replay를 할 수 있으나 **freshA+실B15초 흐름·productionSDK·영속 금융 authority·웹 저장 검증 성공으로 주장하지 않는다**. product15초는 그대로다.

[독립 비용 검토](verification/issue123-usd2-generation-diagnostic-cost-review.md)는 각generation최대견적USD0.152064, 추가2회USD0.304128, 이전예약을 포함한 최대USD1.01330944·추가 비용 여유USD0.98669056를 확인했다. 실제 청구·세금·결제 수수료와 Gemini byte/token 공식 상한을 확인한 값이 아니며, 기존81920 engineering input allowance/2048 thought-inclusive output/2.2배 여유를 유지했다. 미호출 slot과 UNKNOWN 노출은 따로 기록한다.

동결 [계획](../.tdd/history/issue123-usd2-generation-diagnostic/execution-plan.json) SHA는 `95db90eee4118232952bed08a743956f5d5356145d084df8638355a62a3f90c6`, request SHA는 `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44`다. 실제 실행 전 static review에서 알려진HTTP상태의 재시도 우회와 invalid RetryAfter 허용을 발견해 유료호출0 상태에서 최소 보완했고, 원본과 독립 재현을 history에 보존했다. 최종 격리 mock59/59와 [실행 전 독립 리뷰](verification/issue123-usd2-generation-preexecution-review.md) 후에만 아래 두 요청을 실행했다.

## 실제 관측과 중단

2026-10-18 06:09:51 UTC의 첫 생성은 **HTTP503/UNAVAILABLE**, 관측548.4ms였다. 오류 본문은 현재 모델의 high demand를 설명했고 RetryAfter는 없었다. 이 원인은 이번 응답에만 해당하며, 과거 본문 미수집503의 원인으로 소급하지 않는다. 고정 계획대로10.054662초 뒤 실행한 두 번째 생성은 **HTTP200/STOP**, single candidate/part로5195.5ms에 응답했다. 이후 추가 호출0으로 중단했다. 원본 응답 SHA는 `983e23db4fab03c1f5c41e5ca8775e8cf798b122ba5dd9507114c9a7a7cc080a`다. opaque signature 값은 추적 fixture·문서·로그에 복사하지 않는다.

질문은 “8시부터 9시까지가 오전 시간대인지 오후(저녁) 시간대인지 알려주세요.”이며, 같은10월24일의 오전08–09/오후20–21 두 완전한 원문 제안을 반환했다. [독립 의미 평가](verification/issue123-usd2-generation-semantic-review.md)는 사전에 고정한 LTS03–07 및 LTS08의 제안 의미(질문·excerpt·두 대안·날짜/길이/가능 의도 보존)을 통과로 평가했다. **기존 production parser는 공식 optional `thoughtSignature` metadata 때문에 INVALID_RESPONSE**였다. 당시 LTS02 실패와 fresh15초 전체 경로 LTS01 미실행은 그대로 보존한다. 이는 한 사례의 의미 통과이며 일반 정확도나 제품 전체 성공이 아니다. 다음 작업은 별도 독립 RED→최소 metadata 호환 수정→격리 재생이며 추가 유료 호출이 아니다.

반환 usage는 input530/candidate188/thought366/total1084, thought 포함 output554다. 성공 응답의 Gemini 알려진 사용량 견적은 기준USD0.002475·2.2배USD0.005445다. Decisions6회 input3085/output0과 합한 알려진 견적은 기준USD0.0027835·2.2배USD0.00612370이며 **invoice·세금·결제 수수료 확인값이 아니다**. 실패·count·timeout의 청구 UNKNOWN과 모든 예약USD1.01330944를 그대로 유지하고 합산USD2 여유USD0.98669056를 환급하지 않는다. 전체 누계는 Decisions6/count2/generation4 = HTTP시도12, generation HTTP200 응답1/당시 parser 수용0이다. [독립 관측·비용 감사](verification/issue123-usd2-generation-observation-audit.md)가 지문·중단·산술을 확인했다.

기존 코드 b3770438의 [정확 CI](verification/issue123-b377-exact-ci.json)는 Quality Gate/ARM64/Lifecycle 모두 성공했다. metadata 수정은 이 성공과 구별되는 후속 커밋으로 새 필수 guard·독립 리뷰·정확 HEAD CI를 요구한다. 운영 timeout15초·가격/threshold/활성화·참여/마감 정책은 변경하지 않는다.

후속 [metadata 호환 TDD](ISSUE_123_THOUGHT_SIGNATURE_COMPATIBILITY.md)에서는 독립 새26개 genuine RED6/통제20을 확인한 뒤 Contract 한 파일만 수정했다. 집중328개 GREEN·기존 test inventory 불변이며 [exact raw 비네트워크 재생](../.tdd/verification/issue123-thought-signature-offline-replay.json)에서 parser와 기록된 A/count를 거친 TwoStage가 같은 질문/대안/usage를 수용했다. 수정 전 실제 호출의 parser 실패는 역사적으로 유지하고, LTS02의 후속 offline 호환 통과만 별도로 기록한다. 독립FI3 탐지/각복구/최종26PASS 및 새 필수 guard213 suite/1710개(1706PASS·기존4SKIP·0fail/error), source557 before=after=current도 통과했다. [최종 독립 게시 리뷰](verification/issue123-thought-signature-publication-final-review.md) PASS·미해결 P1/P2 없음이며 추가 유료 호출0이다. 후속 commit/push/정확 CI가 다음 단계다. 정확 SHA·원격 결과는 Git 이력·PR126 및 ignored publication receipt에 별도 기록한다.
