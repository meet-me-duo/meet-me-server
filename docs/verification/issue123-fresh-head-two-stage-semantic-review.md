# #123 새 HEAD 실제 단일 사례15초 흐름 독립 검토

2026-10-18 UTC. **새 실제 A→조건부 count→생성→수정 parser→실제 TwoStage의 선언된 callback 경로가 한 사례에서 공유15초 안에 완료됐고, 최종 질문은 사전 의미 기준을 통과했다.** 다만 A의 raw CLEAR 정답 불일치는 그대로이며, 정상 LOW_CONFIDENCE 처리 뒤 생성 단계가 안전한 질문을 제시한 결과로 구분한다.

검토자는 원본·소모 표식·실행 계획·harness timing을 읽고 비교했으며 source/test/helper 변경, Gradle 실행, 추가 API/paid 호출을 하지 않았다. [구조화 감사](issue123-fresh-head-two-stage-semantic-review.json)에 안전한 지문·계산·rubric을 보존한다. 실제 signature와 키 값은 출력하거나 복사하지 않았다.

## 실행 전 고정과 무결성

실행 HEAD는 `4101038fd421351affa2a1fcdd5741083cb1a6b6`다. 실행 계획 SHA `3a9fd47f2e5da20ed69f895abcd0c045aceeaefc1e12d98d24121fd6e8028330`, 원래 oracle `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`, fresh mode `b40a1d20eec4464ca41cbaefb91341aadf0e01e68f1f6ed1b06f4cf08f79adb2`가 실제 기록과 일치한다. 최종 계획은 dispatch 전에 동결됐다. 현재 HEAD·세 관련 production source·harness·callback·557개 source manifest 지문도 일치한다.

원문은 `10월 24일 토요일 8시부터 9시까지 가능해요.`이며 referenceAt2026-10-18T04:00:00Z, Asia/Seoul, ko-KR, 검색일2026-10-24–25가 A/B 요청에 그대로 전달됐다. fresh count의 전체 generation JSON은 model 필드를 제외하면 실제 생성 요청과 같다. 기존 과거 A/count 응답을 재생해 이번 실제 stage를 대체하지 않았다.

A/count/generation은 각각 attempts1·HTTP200·automaticRetries0다. 저장 본문의 raw SHA·길이는 각 관측 표식과 일치하고 원본 편집/교체 없이 파싱했다. 관측 Content-Type은 A의 application/json, Gemini 두 응답의 application/json; charset=UTF-8이며, Gemini는 modelVersion gemini-3.8-flash·serviceTier standard를 보고했다. 이 헤더와 usage는 공급자 관측값이며 invoice 확정이나 운영 권한 증거는 아니다.

## 지연의 독립 계산

| 구간 | 관측값 |
| --- | --- |
| 실제 TwoStage 전체 monotonic 구간 | 7029.248374ms |
| 새 Decisions HTTP | 653.398ms |
| 새 count HTTP | 228.058ms |
| 새 generation HTTP | 5934.856ms |
| JVM 전체 구간 | 7641.481ms |

pipelineStartedNanos33656725469324, cutoff33671725456084, finished33663754717698을 직접 빼서 elapsed7029248374ns를 확인했다. 사용 가능한 budget은14999986760ns이고 최종 반환 시7970738386ns가 남았다. 즉 final return은 실제 공유 cutoff 이전이다. cutoff가 check 진입 직전에 만들어져 약13.24µs 먼저 시작한 점까지 포함한 값이며 임의로15초를 덧붙이지 않았다.

callback에 전달된 남은 시간도 A14998ms→count14274ms→generation13991ms로 줄어든다. harness는 실제 TwoStage.check 전후를 같은 JVM System.nanoTime으로 측정하고 final return이 cutoff 이후면 TIMEOUT으로 처리한다. 생성 body 수신과 parseB는 check의 반환 이전에 실행된다. JVM 시작·종료를 포함한7641.481ms를 pipeline 지연과 혼동하지 않는다. 이 한 관측에서 p50/p95를 계산하거나 일반 지연 보장을 주장하지 않는다.

## A raw 판정과 정상 재확인 분기

새 A는 raw CLEAR, confidence0.06, CLEAR probability0.37을 반환했다. 고정 사전 기대 NEEDS_REVIEW와 raw label의 불일치는 여전히 기록한다. confidence0.06을 CLEAR 확률6%나 보정된 정확도로 해석하지 않는다.

기존 Git e83의 합성 threshold0.8을 그대로 사용한 현재 분류기는 이를 INDETERMINATE/LOW_CONFIDENCE로 정규화했다. 이 정상 유보는 사전 허용된 생성 분기이며 fresh count530을 통과한 뒤 생성이1회 실행됐다. 이번 새 결과가 과거 수치와 같다는 사실을 성공 조건으로 강요하지 않았고 운영 임계값도 선택하지 않았다.

## LTS01–08 고정 oracle 대조

| 기준 | 판정 | 근거 |
| --- | --- | --- |
| LTS01 fresh 전체·문맥·공유 deadline | 선언된 callback 경로 통과 | 새 A/count/B가 각각1회 실제 호출됐고 정확 문맥과 하나의15초 cutoff를 지켰다. raw A 정답 불일치는 별도로 유지한다. |
| LTS02 현재 parser 수용 | 통과 | 새 raw B를 수정 없이 파싱했다. STOP/candidate1/part1/text와 공식 optional signature를 처리했고 내부 JSON·usage를 수용했다. |
| LTS03 한 AM/PM 질문 | 통과 | “‘8시부터 9시까지’가 오전인지 오후인지 선택해주세요.”로 필요한 구별만 묻는다. |
| LTS04 정확 excerpt·길이 | 통과 | `8시부터 9시까지`는 원문의 정확 substring이며 질문·excerpt 상한 이내다. |
| LTS05 A/B 두 완전 제안 | 통과 | A/B ID와 서로 다른 label·complete proposedText를 가지며 원문과 다른 두 제안이다. 크기 제한 이내다. |
| LTS06 같은 날짜의 두 해석 | 통과 | A는10월24일 토요일 오전8–9시, B는 같은 날짜 오후8–9시로08–09/20–21을 구별한다. |
| LTS07 다른 의도 보존 | 통과 | 날짜·요일·한 시간·가능 polarity가 같고 새 장소·사람·조건·다른 날짜가 없다. |
| LTS08 제안 의미 | 통과, UI 미검증 | 한쪽을 자동 확정하지 않고 선택을 요청한다. 참여자 수용·직접 수정·DB accepted revision은 실행하지 않았다. |

정확 제안은 A `10월 24일 토요일 오전 8시부터 9시까지 가능해요.`, B `10월 24일 토요일 오후 8시부터 9시까지 가능해요.`다. 현재 actualTwoStageResult의 verdict/prompt/sourceExcerpt/두 choice의 모든 필드는 raw 내부 payload와 동일하다. 원문을 고친 후 파싱하거나 후속 제안을 손으로 수정한 것이 아니다.

harness accepted=true는 parser·composition 결과 수용이며 **사용자 제출 완료 또는 accepted revision 저장이 아니다**. 결과는 NEEDS_CHOICE이므로 원래 제품 흐름에서는 참여자의 명시적 선택/직접 수정을 기다린다. provider에 없는 direct-edit JSON flag를 새로 요구하지 않고 UI 제공 여부를 실검증했다고도 하지 않는다.

## usage·signature·비용

새 A의 반환 usage는 input512/output0이다. fresh count는530을 보고했다. B는 input530/candidate127/thought533/total1190을 반환했고, parser·최종 결과는 thought-inclusive output660을 보존했다. 이는2048 이하다. count530을 청구 입력의 정확한 증명으로 삼지 않으며 invoice·세금·수수료는 미확인이다.

B raw 응답 SHA는 `1cf9d06f2bd21dc9f3c553f5491af65870a6c1928bd498d1d25be3bbf0b25417`, bytes3527이다. 실제 optional signature는 최종 TwoStage 결과와 구조화 감사에 포함되지 않는다. 원값을 새로운 fixture에 넣지 않았고 자동 재요청·후속 conversation에 재사용하지 않았다.

새 물리 시도는3회, 이전12회를 포함한 누계15회다(Decisions7/count3/generation 시도5). generation의 관측 HTTP200은 이번을 포함2회이며 이전503/TIMEOUT을 지우지 않는다. 계획상 누적 최대 예약USD1.33185536·여유USD0.66814464는 같은 합산USD2 안의 보수 예약 값이다. 실제 invoice 금액이나 실패 hold 환불이라고 해석하지 않는다.

## 남는 검증 범위

이번에는 실제 fresh 단일 사례의 callback/현재 production 계약/실제 TwoStage 조합과 최종 질문을15초 내 관측했다. 실제 production SDK HTTP transport, 영속 PostgreSQL 금융 authority, 운영 room/worker/브라우저, 직접 수정·accepted revision·소유권 게시, 최종 whole-room matching은 이 실행의 대상이 아니다. 운영 flagsOFF·thresholdnull과 제품15초 정책도 바꾸지 않았다.

원래 raw label FAIL, 과거503·TIMEOUT·수정 전 parser 실패,60초 B 단독 진단 및 비네트워크 재생은 각각의 역사적 결과로 보존한다. 한 합성 일정의 성공으로 일반 정확도·운영 confidence calibration·모델 신뢰성·p50/p95·서비스 전체 준비 완료를 주장하지 않는다. 추가 paid 호출 없이 이 단일 실행을 평가했다.

원본은 Git 제외 `.codex/tdd-evidence/issue123-fresh-head-two-stage/`의 최종 execution-plan.json, live-invocation.json, live-two-stage.json, 각 consumed·callback metadata와 요청/응답이다. signature·키 원값은 이 감사 문서에 복사하지 않았다.
