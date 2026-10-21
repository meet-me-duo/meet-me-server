# #123 실제 2단계 후속 결과 독립 검토

2026-10-18 UTC. 독립 테스트·oracle 작성자가 저장된 요청·응답·소모 표식·실행 결과를 읽기 전용으로 대조했다. 이 검토자는 실제 API 호출, 소스·테스트·운영 설정 변경을 하지 않았다.

**결론: `FAIL_PROVIDER_OR_STRUCTURAL` / `TASK_INCOMPLETE`.** 새 Decisions의 낮은 확신도 분류와 count까지 실행됐지만 GenerateContent가 HTTP503으로 중단됐다. 성공한 생성 본문·질문·A/B 제안이 없어 **질문의 의미·원문 의도 보존은 평가 불가**다. B의 의미 실패가 확정됐다는 뜻도, 질문 생성이나 실제 전체2단계가 성공했다는 뜻도 아니다. 추가 호출·재시도 없이 사전 중단 규칙을 지켰다.

## 실행 전에 고정한 기준

- 원문: `10월 24일 토요일 8시부터 9시까지 가능해요.`
- 문맥: referenceAt `2026-10-18T04:00:00Z`, `Asia/Seoul`, `ko-KR`, 검색일 `2026-10-24`–`2026-10-25`.
- [사전 oracle](../evaluation/issue123-live-two-stage-oracle.json) SHA `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`와 실행 계획 SHA `368bafb75f2c36f0980501ea5b7b8a9669f5b8eb5e6705c49e4b6659b9aeb746`가 실행 기록과 일치한다. 결과를 본 뒤 원문·정답·질문 기준을 바꾸지 않았다.
- 모드: 새 실제 A1 → 정상 재확인 분기 → runtime count최대1 → generation최대1. 합계최대3 HTTP, 동시성1, retry/redirect/fallback/continuation0, 공유15초.
- threshold0.8은 실행 이전 Git `e83d5280c7c236c99d64165893632920882527fe`의 기존 합성 fixture를 그대로 원용했다. fixture SHA `e8bd75698e37cc4b9cbeb9c625656045ad3a1e6590dcdecf8a5568dc4f9bb197`. 운영 threshold는 여전히 미선택/null·기본OFF다.
- 성공 질문 기준은 동일 날짜의 08–09시 또는20–21시를 구별하는 안전한 A/B 완전 제안이다. CLEAR이면 이 사례의 의미 평가 실패/중단, NEEDS_EDIT이면 안전한 유보지만 질문 목표 미완료로 사전 구분했다. 이번 실행은 두 verdict 모두 얻지 못했다.

## 직접 관측한 실행

| 단계 | 실제 HTTP | 보고된 결과 | HTTP 구간 지연 |
| --- | --- | --- | --- |
| 새 Decisions A | 1회·200 | raw CLEAR, confidence0.06, CLEAR probability0.37. 실제 정규화는 INDETERMINATE/LOW_CONFIDENCE. input512/output0, usageComplete=true | 855.0ms |
| Gemini count | 1회·200 | totalTokens530. 유효한1..4096 범위를 통과 | 225.9ms |
| Gemini GenerateContent | 1회·503 | 성공 생성 본문·질문·usage 없음. STOP_HTTP_FAILURE_UNKNOWN_HELD | 347.8ms |

`live-invocation.json`은 신규 물리 HTTP3회, JVM 실행 exit2, 전체 JVM 구간2177.7ms를 기록한다. HTTP 구간 지연과 JVM 구간은 측정 범위가 다르다. `live-two-stage.json`의 accepted=false와 반환 코드CONFIGURATION도 확인했다. **관측된 직접 원인은 HTTP503**이며, wrapper의 포괄적 CONFIGURATION 분류만으로 잘못된 키·프로필·프롬프트 설정을 원인이라고 확정하지 않는다. 성공 생성 본문은 파일로 존재하지 않고 HTTP오류 본문도 보존되지 않아 공급자 오류 상세는 확인할 수 없다.

이번 추가는 Decisions1 + Gemini count1 + GenerateContent 시도1이다. 이전 실제 Decisions4회와 합쳐 **Decisions5회, Gemini count1회, Gemini generation 시도1회·성공 응답0회**, 물리 HTTP 합계7회다. 공급자 내부에서 실패 요청이 어떻게 처리됐는지는 확인하지 못했으므로 “Gemini 추론이나 청구가0”이라고 단정하지 않는다. 소모 표식은 각 단계 attempts1이며 추가 표식·재시도·후속 호출이 없다.

## 사전 rubric 대조

| 기준 | 판정 | 근거·제한 |
| --- | --- | --- |
| LTS01: 같은 입력·정상 A 재확인 경로 | 관측 범위 통과 | A와 생성 요청의 rawText·다섯 문맥 필드가 일치. 새 A가 LOW_CONFIDENCE로 정규화되어 B 경로에 진입했다. 새 수치가 과거와 같아야 한다고 강요하지 않았다. |
| LTS02: 정상 STOP 후보·단일 text·schema 통과 | 공급자 실패로 미충족 | HTTP503이며 성공 envelope가 없어 payload 구조 검증에 도달하지 못했다. 잘못된 질문 JSON이 관측된 것은 아니다. |
| LTS03: AM/PM 질문 | 평가 불가 | 질문 없음. |
| LTS04: 정확 sourceExcerpt·질문 길이 | 평가 불가 | 반환 payload 없음. |
| LTS05: 유효 A/B·두 완전 제안 | 평가 불가 | 선택지 없음. |
| LTS06–07: 같은 날짜·두 시간 해석·그 밖의 의도 보존 | 평가 불가 | 실제 제안이 없어 날짜·부정·불필요 조건 추가를 판단할 수 없다. |
| LTS08: 명시적 수용 전 자동 치환·완료 금지 | 완료 주장 없음 | accepted=false. 격리 평가이며 UI 직접 수정·답변 저장·accepted revision·운영 소유권 fencing을 실행하지 않았다. |

원래 raw CLEAR와 사전 NEEDS_REVIEW의 불일치는 기존 [실제 Decisions 진단](../ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md)의 FAIL/STOP 그대로다. LOW_CONFIDENCE routing이 적절했다는 별도 관측으로 그 raw 정답을 소급 변경하지 않는다. confidence0.06은 CLEAR 확률6%나 보정된 정확도가 아니다. 별도 CLEAR probability는0.37이며, 공급자의 confidence 수식은 검증하지 않았다.

기존 실제4회의 HTTP 지연1011.1/201.9/196.2/203.6ms와 input usage518/518/513/512·output0 기록은 그대로 유지한다. 이번 새 A의855.0ms와 input512를 별도로 추가하며 과거 값을 대체하지 않는다. 작은 표본에서 정확도·p50/p95·임계값 보정 효과를 주장하지 않는다.

## 요청·상태 무결성과 비용 한계

세 단계 모두 frozen plan의 요청 지문과 소비 표식 지문이 일치했다. A 입력과 B 전체 입력의 원문·문맥이 같고, count의 generateContentRequest는 model 필드를 제외하면 generation 전체 JSON과 구조적으로 같다. profile은 gemini-3.8-flash, candidate1, maxOutputTokens2048, thinkingLevel LOW다. 530은 count 보고값이며 실제 generation 청구 입력 또는 청구 상한의 증거가 아니다.

- A request SHA `fbc526b0d3fe7e014cd3fd379dd8a9d969ec8218c659ff2e60df0ba8e1d81a52`
- A response SHA `7f6d7d7267a016e5f4b08426421936725122f674e6158e030abde4b1bd01609c`
- Count request SHA `1153b46eab50217239a307b73de69b0a5ef494d84fd78ce14ba3b1a7fb90702f`
- Count response SHA `21100ab895229cd890a807d9a7b16876e8466beaf335f7c015684099dcc8b306`
- Generate request SHA `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44`

현재 guard/source manifest의556개 지문은 모두 현재 파일과 일치했다. 격리 runner는 현재 production 계약과 실제 TwoStage 조합을 사용했지만 실제 production SDK transport·영속 금액 권위·운영 room/worker/브라우저 흐름의 실검증을 주장하지 않는다. 운영 설정·Secret·OAuth·배포 변경도 없다.

기존 Gemini+OpenAI 합산USD1 범위에서 계획에는 누적 예약USD0.39063552가 기록돼 있으며 이는 실청구액이 아니다. generation의 성공 응답·usage가 없어 returnedBUsage=null이며 후보/추론/입력 token을0으로 보정하지 않는다. 503을 자동 무료 또는 환불 근거로 삼지 않고 UNKNOWN hold와 계획 여유를 유지한다. invoice·세금·수수료는 확인되지 않았다.

현재 차단은 **성공 생성 응답 부재**다. 실제 질문의 의도 보존을 판단하려면 향후 별도로 허용된 실행에서 유효한 생성 응답이 필요하지만, 이 검토는 재시도나 추가 호출을 수행·제안된 승인으로 간주하지 않는다. 기존 중단 상태를 유지한다.

원본 근거는 로컬 Git 제외 `.codex/tdd-evidence/issue123-live-two-stage/`의 execution-plan.json, live-invocation.json, live-two-stage.json, 세 consumed 표식과 요청/응답·callback metadata다. 민감 정보는 이 문서에 포함하지 않았다.
