# #123 수정 HEAD fresh 15초 단일 사례 검증

2026-10-18 UTC. 평가한 코드는 `4101038fd421351affa2a1fcdd5741083cb1a6b6`, tree `1d8c38eea7c273727834c487dacc214e4c668fbd`다. 이후 기록 전용 커밋과 구분한다. 부모가 기존 Gemini+OpenAI 합산 USD2 안에서 추가 승인한 사전 고정 LIVE-KO-03 한 건만 실행했고 이후 유료 호출을 중단했다.

## 실행 조건과 결과

입력은 `10월 24일 토요일 8시부터 9시까지 가능해요.`다. 기존 oracle·reference/Asia-Seoul 문맥·500자·LOW/2048·15초·fixture threshold0.8은 그대로 유지했다. 새 [독립 실행 mode](evaluation/issue123-fresh-head-two-stage-oracle-mode.json), [비용 사전 리뷰](verification/issue123-fresh-head-two-stage-cost-review.md), [whole-CLI mock25/25](../.tdd/verification/issue123-fresh-head-runner-mock.json), [실행 전 ACCEPTED](verification/issue123-fresh-head-two-stage-preexecution-review.md) 뒤 root가 단1회 실행했다. callback의 만료 후 open 가능성은 사전 독립 P2로 발견해 최소 수정했고 동일 재현에서 HTTP0을 확인했다. 최초 실패와 fixture 준비 오류는 별도로 보존했다.

| 단계 | 관측 | HTTP 지연 | usage |
|---|---|---:|---|
| Decisions | HTTP200, raw CLEAR/confidence0.06 → LOW_CONFIDENCE | 653.398ms | 입력512/출력0 |
| count | HTTP200, 530 | 228.058ms | 청구 usage 미확인 |
| generation | HTTP200, NEEDS_CHOICE | 5934.856ms | 입력530/출력660(127 candidate+533 thought) |

HTTP 지연은 각 callback의 request open부터 body 수집까지이며 Java callback 전체 시간과 구분한다. 실제 production parser와 TwoStage를 거친 단일 JVM monotonic 경로는 **7029.248374ms**로 공통15초 안에 끝났다. JVM 시작·종료를 포함한 전체 실행은7641.481ms다. A/count/generation은 각1회이며 자동retry/fallback/redirect는0이다. 정확 request/response SHA 및 monotonic 시작·cutoff·종료값은 [관측 기록](../.tdd/verification/issue123-fresh-head-two-stage-observed.json)에 있다.

질문은 `'8시부터 9시까지'가 오전인지 오후인지 선택해주세요.`이고 선택지는 다음과 같다.

- 오전: `10월 24일 토요일 오전 8시부터 9시까지 가능해요.`
- 오후: `10월 24일 토요일 오후 8시부터 9시까지 가능해요.`

raw A의 CLEAR는 이 사례의 사전 NEEDS_REVIEW 의미 기대와 계속 불일치한다. confidence0.06을 정답률6%나 CLEAR확률6%로 해석하지 않는다. 정상화는 기존 합성 fixture0.8을 적용한 검증 조건이며 운영 임계값을 선택하거나 활성화한 결과가 아니다. confidence/분류 정책을 이번 응답에 맞춰 조정하지 않았다.

## 비용과 해석 범위

기존 UNKNOWN을 포함한 예약USD1.01330944에 새 최대USD0.31854592를 실행 전에 더해 누적USD1.33185536, USD2 내 잔여여유USD0.66814464를 유지했다. 기존 UNKNOWN·count fee proxy hold를 환급하지 않는다. 이번 알려진 usage의 공개 단가 추정은 USD0.00292370, 기존2.2배 보수계수 적용USD0.00643214다. 과거 알려진 usage와 합친 추정은 각각USD0.00570720/USD0.01255584다. 이는 count fee·실제 invoice·세금·환율을 확인한 청구액이 아니며 예약값에 다시 더하지 않는다. byte 기반 token allowance도 공식 invoice 상한 보장이 아니다.

누계는 Decisions7/count3/generation5시도=물리HTTP15이며, 과거 generation503·15초timeout·원분류FAIL·수정전parserFAIL은 보존한다. 원 응답의 opaque thoughtSignature와 responseId 값은 ignored0600 raw 증거에만 남기고 Git에 복사하지 않는다.

한 건의 전체 경로 관측이며 일반 정확도·오탐/미탐 비율·p50/p95를 추정하지 않는다. HTTP callback harness가 실제 TwoStage/계약을 사용했지만 production SDK transport, 영속 금융 authority의 실제 실행, 브라우저·사용자 제출 완료는 검증하지 않았다. 운영threshold/가격quote/비용소진UX/최종분석 예산배분/과거UNKNOWN해제는 여전히 TBD다. 배포·병합·운영설정·OAuth·Secret 변경0이고 Linear는 부모 전용 동기화가 담당한다.

독립 [질문 의미 감사](verification/issue123-fresh-head-two-stage-semantic-review.md)와 [관측·비용 감사](verification/issue123-fresh-head-two-stage-observation-cost-review.md)는 범위 내 PASS·미해결P1/P2 없음으로 봉인됐다. LTS01–07 및 LTS08 제안의미가 통과했고 UI는 미검증이다. 기록commit 전 unchanged 필수guard도 exit0이며 Gradle UP-TO-DATE 가능성 때문에 새fresh1710 실행으로 집계하지 않는다. 기록 게시의 정확 HEAD CI는 Worklog·Git 이력·PR126에 이어 기록한다.
