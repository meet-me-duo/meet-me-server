# #123 실제 2단계 별도 두 번째 시도 독립 검토

2026-10-18 UTC. 저장된 실행 계획·소모 표식·요청·A/count 응답·최종 결과를 사전 oracle에 대조했다. 검토자의 추가 실제 호출, 소스·테스트·운영 설정 변경은0이다.

**판정: `FAIL_PROVIDER_OR_STRUCTURAL` / `TASK_INCOMPLETE` — 구체적 원인은 공유 deadline의 `TIMEOUT`.** 실제 생성 질문·제안·usage가 관측되지 않아 **질문의 원문 의도 보존은 평가 불가**다. 이번 생성 단계의 HTTP 상태는 기록되지 않았다. 첫 시도의503을 두 번째 시도에도 관측된 것처럼 쓰지 않으며, 의미 실패가 확정되거나 질문 생성에 성공했다고도 판단하지 않는다.

## 고정 기준과 이전 시도 보존

- 원문은 `10월 24일 토요일 8시부터 9시까지 가능해요.` 그대로다. referenceAt `2026-10-18T04:00:00Z`, Asia/Seoul, ko-KR, 검색일2026-10-24–25도 유지했다.
- [원래 oracle](../evaluation/issue123-live-two-stage-oracle.json) SHA `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`가 그대로다. 두 번째 계획 SHA는 `b4fe8fd30e5b61b87d259cce1ab6a4099220d445910ba5617c2511d9354a0e28`이며 실행 이전에 동결됐다.
- 실제 TwoStage를 조합하는 harness SHA `757bc8a0accdc2bfa47ff831244fbf5aac88349cc2d98daea80c68eb872716c9`, 세 payload의 정확 bytes·SHA, 질문 prompt/schema와 profile이 첫 시도와 동일하다. 기존 합성 threshold0.8도 재사용했다. 운영 임계값 선정·프롬프트 튜닝은 없다.
- 이번 실행은 부모가 명시 허용한 **별도 bounded 시도1회**다. 각 계획 안에서 SDK/HTTP 자동 retry·redirect·fallback·continuation은0이며, 첫 시도 실패를 숨기거나 같은 예약을 환급해서 재사용하지 않았다. [첫 시도의503 감사](issue123-live-two-stage-result-review.md)와 원본 기록은 그대로 보존한다.

## 두 번째 시도에서 관측한 값

| 단계 | 관측 | HTTP 구간 지연 |
| --- | --- | --- |
| 새 Decisions A | 200·1회. raw CLEAR/confidence0.06, 별도 CLEAR probability0.37. 정규화 INDETERMINATE/LOW_CONFIDENCE. input512/output0, usageComplete=true | 394.6ms |
| Gemini count | 200·1회. totalTokens530,1..4096 preflight 통과 | 245.3ms |
| Gemini generation | callback1회가 시작돼 UNKNOWN_HELD 표식 작성. 공유 deadline에서 자식 process 종료. 성공 본문·질문·usage·HTTP 상태 없음 | 미관측 |

최종 `live-two-stage.json`은 accepted=false, failureCode=TIMEOUT, classifier/count/generationCallbacks 각각1, returnedBUsage=null이다. `GENERATE-consumed.json`에는 attempts1·UNKNOWN_HELD·startedAt·requestSha256만 있고 httpStatus·finishedAt·elapsedHttpMs가 없다. successful generation response 파일도 없다. callback metadata 파일은 완성된 응답 근거가 아니다. 공급자가 요청을 수신·처리·청구했는지 확정할 수 없으며 “미전송”, “무과금”, “다시503”으로 보정하지 않는다.

`live-invocation.json`의 전체 JVM 구간은15538.3ms, exit2다. 이는 JVM 시작·callback/process 정리까지 포함한 값이며 성공한 generation HTTP 지연이 아니다. harness는 `remaining(deadline)`으로 남은 시간을 전달하고 `Process.waitFor`가 만료되면 자식과 descendants를 강제 종료하는 기존 코드를 사용한다. 이 기록으로 stage마다 새15초를 부여한 성공을 주장하지 않는다.

## 사전 의미 oracle 판정

- **LTS01 정상 재확인 경로·입력 보존:** 관측 범위에서 통과. A와 B 요청의 원문·문맥 및 count/generation 전체 payload 동일성을 확인했다. 새 A는 현재 분류기의 LOW_CONFIDENCE로 생성 경로에 진입했다. confidence는 기존처럼 CLEAR probability와 별도이며 운영 정확도로 해석하지 않는다.
- **LTS02 유효한 생성 envelope·구조:** timeout으로 성공 envelope를 얻지 못해 미충족. malformed 질문이 실제 관측됐다는 뜻은 아니다.
- **LTS03–07 질문·sourceExcerpt·A/B 완전 제안·날짜/시간/나머지 의도 보존:** 모두 평가 불가. 같은 날짜08–09시와20–21시를 안전하게 제시했는지 판단할 실제 질문이 없다.
- **LTS08 자동 원문 변경·제출 완료 금지:** accepted=false와 중단 결과를 확인했다. 모델이 안전한 NEEDS_EDIT를 반환했다는 의미가 아니며 UI 직접 수정·accepted revision·운영 소유권 경로는 이 격리 실행에서 검증하지 않았다.

실제 질문을 얻지 못한 구체적 차단 때문에 전체 목표는 미완료다. 첫 raw CLEAR와 사전 NEEDS_REVIEW의 불일치 FAIL/STOP 및 이전4개 진단의 지연·usage 기록은 수정하지 않는다. 정상 LOW_CONFIDENCE 분기를 관측한 것으로 원래 raw label의 의미 평가를 통과로 소급하지 않는다. 실제2단계 질문 성공·모델 정확도·운영 임계값·p50/p95를 주장할 근거도 없다.

## 누적 시도와 비용 중단

최초 Decisions4회 + 첫 별도 시도3개 + 이번3개를 합쳐 **HTTP 시도 누계10: Decisions6, Gemini count2, generation 시도2·성공 응답0**이다. 이번 generation은 시작 소모 표식으로 집계한 시도이며 실제 서버 수신/내부 추론은 미확정이다. 이전 시도는 generation503, 이번 시도는 HTTP 상태 미관측 TIMEOUT으로 구분한다. 이후 추가 호출은 없다.

동일 Gemini+OpenAI 합산USD1 승인 아래 계획상 누적 예약은USD0.70918144이고, 여유USD0.29081856이다. 이는 invoice로 확인한 지출·환불·잔고가 아니다. 두 generation의 UNKNOWN hold를 그대로 보존한다. 같은 범위 한 번의 추가 견적USD0.31854592를 더하면 **USD1.02772736 > USD1**이므로 현재 계획에서 추가 전체 시도는 허용 범위를 넘는다. 이번 감사는 그 여유를 근거로 쪼갠 재호출이나 새로운 외부 계약을 선택하지 않는다.

count530을 실제 generation 청구 입력으로 대체하지 않고 반환되지 않은 input/candidate/thought/output usage는 null/미상으로 유지한다. timeout으로 자식을 종료했다고 공급자 작업이나 요금이 취소됐다는 보장은 없다. invoice·세금·수수료는 미확인이다.

## 무결성과 검증 범위

기존 guard/source manifest556개 지문은 현재 파일과 모두 일치한다. oracle·harness·세 payload는 사전 지문 그대로이며 기존 정책·코드·설정 변경이 없다. 실제 production SDK transport·영속 금액 권위·운영 worker/room/UI 전체 성공을 이 callback 평가로 주장하지 않는다.

원본은 로컬 Git 제외 `.codex/tdd-evidence/issue123-live-two-stage-attempt2/`의 execution-plan.json, live-invocation.json, live-two-stage.json, 세 consumed 표식, 요청·A/count 응답과 callback metadata다. 이 문서는 기록 감사이며 추가 실제 호출을 수행하지 않았다. 다음 상태는 **추가 호출 중단, 실제 질문 의미 평가 미완료, 미확정 비용 보존**이다.
