# #123 thoughtSignature 수정 후 비네트워크 재생 독립 검토

2026-10-18 UTC. 원본 실제 응답, 수정된 production parser의 출력, 실제 TwoStage의 비네트워크 재생 결과를 독립 비교했다. **원본 의미·원문·문맥·usage 보존과 수정 후 오프라인 parser/조합 수용은 통과했다.** 검토자의 source/test 변경·Gradle 실행·실제 API·paid 호출은0이다.

## 기준과 지문

- [원래 의미 oracle](../evaluation/issue123-live-two-stage-oracle.json) SHA `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`, [단독 B 모드](../evaluation/issue123-standalone-generation-oracle-mode.json) SHA `871c42c29cb73882cb50d17f91433d7d70f293c27c7637231761d201254e5d7d`를 유지한다.
- 실제 B 원본 bytes SHA `983e23db4fab03c1f5c41e5ca8775e8cf798b122ba5dd9507114c9a7a7cc080a`를 그대로 parser에 재생했다. 실제 opaque signature를 제거한 응답이나 답안을 수동 고친 응답으로 통과시킨 것이 아니다.
- 수정 Contract SHA `7d1131259a0813c6622fe290712a4c2dfa32514c9f2e2adcf266eedfe79e4880`, 독립26-case test SHA `7acddc4e24866fe52d85c2cdbd2c37f11b40aaf4f11dccf6d48af4792ee353e3`가 현재 파일과 일치한다. writer의 보존 receipt는 새26개를 포함한 provider/classification328개 통과·기존 test inventory 불변·Contract만 수정됐음을 기록한다. 이 독립 감사는 새 Gradle 실행을 추가하지 않았다.

## 원문·질문·문맥 보존

재생된 A request SHA는 `fbc526b0d3fe7e014cd3fd379dd8a9d969ec8218c659ff2e60df0ba8e1d81a52`, generation request는 `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44`, count request는 `1153b46eab50217239a307b73de69b0a5ef494d84fd78ce14ba3b1a7fb90702f`로 기존 실제 요청과 같다.

원문 `10월 24일 토요일 8시부터 9시까지 가능해요.`와 referenceAt2026-10-18T04:00:00Z, Asia/Seoul, ko-KR, 검색일2026-10-24–25가 A와 B 입력에서 정확히 일치한다. 과거 A/count 기록과 나중에 확보한 B 응답을 조합한 것이므로 새 실제 fresh chain이라고 표현하지 않는다.

원본 B의 내부 JSON과 parse 결과·TwoStage 최종 결과의 verdict/prompt/sourceExcerpt/각 choice id·label·proposedText를 필드별로 비교해 모두 동일함을 확인했다.

| 항목 | 그대로 보존된 결과 |
| --- | --- |
| verdict | NEEDS_CHOICE |
| 질문 | 8시부터 9시까지가 오전 시간대인지 오후(저녁) 시간대인지 알려주세요. |
| exact excerpt | 8시부터 9시까지 |
| A | 오전 8시부터 9시까지 / 10월 24일 토요일 오전 8시부터 9시까지 가능해요. |
| B | 오후 8시부터 9시까지 / 10월 24일 토요일 오후 8시부터 9시까지 가능해요. |

날짜·요일·한 시간 구간·가능 polarity를 유지하며, 미해결인 오전/오후만 구별한다. 선택을 강제하거나 새로운 장소·사람·다른 날짜를 추가하지 않았다. 원래 [실제 의미 평가](issue123-usd2-generation-semantic-review.md)의 LTS03–07 통과 판단을 수정 없이 유지한다.

## parser 수용과 signature 폐기

수정 전 실제200의 INVALID_RESPONSE는 그대로 역사적 실패로 보존한다. 수정 후에는 동일 실제 bytes가 one candidate/STOP/one text part의 구조와 기존 strict inner JSON·질문·선택지·usage 검사를 통과해 NEEDS_CHOICE로 파싱됐다. **LTS02는 수정된 parser의 오프라인 재생 범위에서 PASS**다. signature를 수용했다고 다른 part field·thought part·extra part·알 수 없는 내부 JSON을 허용한 것은 아니다.

actual signature 원값은 parse 출력, replay 출력, 저장된 offline-two-stage 결과와 최종 result 어디에도 포함되지 않았다. 이 감사 문서에도 원값을 복사하지 않았다. 새 테스트에는 synthetic opaque만 있으며 production 결과 타입에는 signature 필드가 없다. 검토한 수정은 공식 optional transport metadata를 검증 후 폐기하는 범위다.

LTS08의 제안 의미도 유지된다. **harness의 accepted=true는 parser/조합 수용 표식이며 사용자 제출 완료 또는 accepted revision 저장이 아니다.** NEEDS_CHOICE는 여전히 참여자의 선택·직접 수정을 기다리는 결과다. 이 재생에서 UI·답변 API·DB 저장·마감·소유권 게시를 실제 실행하지 않았으므로 해당 경로를 검증했다고 주장하지 않는다.

## usage와 2단계 재생

과거 A는 기존 합성 threshold0.8에서 INDETERMINATE/LOW_CONFIDENCE, confidence0.06, input512/output0으로 재생됐다. threshold0.8은 운영값을 새로 고른 것이 아니며 confidence와 별도 CLEAR probability0.37을 혼동하지 않는다. count는 과거530 보고값을 재생했다.

B 원본·parser returnedUsage·TwoStage returnedBUsage는 input530/candidate188/thought366으로 모두 같다. 최종 result output554는188+366 합계이며 원래 total1084와 일치한다. signature 처리 때문에 usage를 누락·0으로 보정하거나 후보/추론 token을 혼동하지 않았다. 결과의 responseBytes2766도 실제 원본 길이와 같다. 이는 반환 usage 보존이며 실청구·가격·비용 환불 권위가 아니다.

실제 TwoStage의 classifier port1회·generator port1회가 기록됐고, actualTwoStageResult가 parsedB와 동일한 질문·usage를 보존했다. 이 전체 재생의 HTTP·paid 호출은0이다. 기존 실제 호출 누계·503·TIMEOUT·UNKNOWN holds를 변경하거나 환급하지 않았다.

## 준비 오류와 검증 한계

첫 root 재생 명령은 default-package harness에 잘못된 package-qualified 이름을 사용해 ClassNotFoundException으로 종료됐다. 원본 stderr는 Git 제외 `.codex/tdd-evidence/issue123-thought-signature-offline-replay/parse-stderr.txt`에 보존됐다. 제품 parser에 도달하지 못한 준비 오류·HTTP0이며 제품 RED로 집계하지 않는다. 올바른 harness 이름의 후속 실행에서 위 동일 bytes 비교와 수용 결과를 얻었다.

실제 fresh A→count→B가 제품 공유15초 안에 완료됐다는 증거는 아니다. 기존 실제 생성200은 B 단독60초 관측에서 얻었고 재생은 네트워크를 생략했다. runtime SDK transport·영속 금융 authority·운영 worker/room·UI persistence·사용자 수용·일반 정확도·운영 confidence calibration·p50/p95·invoice는 미검증이다. 제품15초·기본OFF·운영 thresholdnull을 변경하지 않는다. 새 전체 guard·FI·최종 게시 검증은 root의 별도 단계다.

[구조화 재생 기록](../../.tdd/verification/issue123-thought-signature-offline-replay.json)과 Git 제외 `.codex/tdd-evidence/issue123-thought-signature-offline-replay-attempt2/`의 parse-stdout.json, replay-stdout.json, two-stage/offline-two-stage.json을 대조했다. 각각의 결과에 actual signature가 없고, replay-stdout과 저장된 offline-two-stage는 JSON 값이 동일함을 확인했다.
