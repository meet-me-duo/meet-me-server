# #123 USD2 범위 생성 진단의 독립 의미·호환성 검토

2026-10-18 UTC. 실행 전에 고정한 [oracle](../evaluation/issue123-live-two-stage-oracle.json) `c69edcc525bde189765788edfd96fb05756c0e3a21d23cfcda357a7d52be1d64`와 [B 단독 모드](../evaluation/issue123-standalone-generation-oracle-mode.json) `871c42c29cb73882cb50d17f91433d7d70f293c27c7637231761d201254e5d7d`를 변경하지 않고 실제 응답을 평가했다. 검토자의 추가 paid/API 호출·제품/테스트/운영 설정 변경은0이다.

**고정 사례의 실제 질문 의미 품질은 통과했다. 현재 production parser 호환성은 실패했다.** 따라서 전체 계약·제품 완료로 보고할 수 없다. 이번은 과거 A/count 기록을 재사용한60초 관측의 B 단독 진단이며, fresh2단계15초·runtimeSDK·영속 금융 권위·운영 UI 성공을 검증한 것도 아니다.

## 관측과 그대로 유지한 기준

원문은 `10월 24일 토요일 8시부터 9시까지 가능해요.`다. referenceAt2026-10-18T04:00:00Z, Asia/Seoul, ko-KR, 검색일2026-10-24–25와 정확한 generation payload SHA `3fafbb130ffeedca5e459e9747fd2329d8823f1db217802778ed1fa5dc76cd44`가 기존 두 시도와 같다. 새 A/count 호출은0이며, 이전 rawCLEAR/confidence0.06·합성threshold0.8 정규화와 count530은 과거 근거로만 사용했다.

새 진단의 첫 generation은 HTTP503과 high-demand 오류를 반환했다. 사전 allowlist에 따른 별도 후속1회를10초 간격 뒤 실행했고, 두 번째는 HTTP200·STOP·candidate1·part1을5195.5ms의 HTTP 관측 구간으로 받았다. 이후 추가 paid 호출 없이 중단했다. 원래 raw-label FAIL, 첫503, 두 번째 TIMEOUT은 그대로 보존하며 이 성공으로 지우지 않는다.

실제200 응답 SHA는 `983e23db4fab03c1f5c41e5ca8775e8cf798b122ba5dd9507114c9a7a7cc080a`, text payload SHA는 `bf0f16ba09b40c680f72f0cfe2093318791fd4b744f3b8712394ddbe859e3c7b`다. text와 함께 `thoughtSignature`라는 opaque metadata 필드가 있었다. 원값은 복사·출력·테스트 fixture로 보존하지 않으며 이 검토에는 SHA `3744e543cbf09cf04fbb8b4b7f9e102967205d7ba8c945f4852ccb6e1e88ca89`만 기록한다.

## 실행 전 의미 기준 대조

| 기준 | 판정 | 관측 근거 |
| --- | --- | --- |
| LTS01 freshA→B 전체 경로 | 이번 모드에서 미실행 | B 단독이며 과거 A/count 기록 재사용. 입력 보존만 확인할 수 있다. |
| LTS02 production 구조 parser 수용 | 현재 실패 | STOP/candidate1/part1/text는 있으나 현재 parser가 text 이외 Part 키를 거부해 INVALID_RESPONSE. 공식 metadata 호환 문제를 별도 처리해야 한다. |
| LTS03 AM/PM 한 질문 | 의미 통과 | “8시부터 9시까지가 오전 시간대인지 오후(저녁) 시간대인지 알려주세요.”로 미해결 기간만 묻는다. |
| LTS04 질문·정확 excerpt | 의미·길이 통과 | sourceExcerpt `8시부터 9시까지`는 원문의 정확한 substring이다. 질문160자·excerpt200자 상한 이내다. |
| LTS05 A/B 두 완전 제안 | 의미·구조 통과 | IDs A/B, 두 label과 complete proposedText가 있으며 서로 다르고 원문과도 다르다. label160/proposal500자 이내다. |
| LTS06 같은 날짜의 두 해석 | 의미 통과 | A는 `10월 24일 토요일 오전 8시부터 9시까지 가능해요.`, B는 같은 날짜의 `오후 8시부터 9시까지`다. 각각08–09와20–21을 나타낸다. |
| LTS07 관련 없는 조건 보존 | 의미 통과 | 원래 날짜·요일·1시간 구간·가능 polarity를 유지한다. 새 장소·사람·날짜·참여 조건을 발명하지 않았다. |
| LTS08 제안과 명시적 수용 | 제안 의미 통과, UI 미검증 | 두 대안을 질문으로 제시하며 한쪽을 정답으로 강제하지 않는다. 사용자 선택/직접 수정·accepted revision 저장이나 원문 자동 교체는 이 진단에서 실행하지 않았다. |

오전8시부터9시, 오후8시부터9시라는 같은 문장 내 기간 표현은 시작의 오전/오후가 끝 시각에도 적용되는 두 완전한 구간이다. 날짜나 장소를 불필요하게 다시 묻지 않았다. 이 판정은 기존 기준으로 사람이 평가한 한 사례이며 정규식 의미 판정·답안 기반 oracle 수정·일반 정확도 추정이 아니다. provider JSON에 없는 direct-edit 플래그를 요구하지 않고, 실제 UI에서 직접 수정이 제공됐다고도 주장하지 않는다.

## parser 호환성과 최소 수정 경계

현재 production parser의 실제 결과는 INVALID_RESPONSE였다. 응답 Part의 키는 text와thoughtSignature이며, 현재 exact-key 검사가 text만 허용하는 조건과 충돌한다. 내부 의미 JSON은 기존 verdict/prompt/sourceExcerpt/choices·A/B 계약과 일치한다. 따라서 이 사례는 **raw semantic PASS / current parser compatibility FAIL**로 구분한다. LTS02를 실패 상태로 둔 채 전체 standalone 계약 PASS라고 소급하지 않는다.

공식 [GenerateContent Part](https://ai.google.dev/api/generate-content#Part)는 thoughtSignature를 optional bytes 형식의 공통 metadata로 기술한다. text data와 함께 존재할 수 있다. [GenerateContent thinking 설명](https://ai.google.dev/gemini-api/docs/thinking)은 이 metadata가 최종 part에도 붙을 수 있음을 설명한다. [ProtoJSON bytes/null 계약](https://protobuf.dev/programming-guides/json/)은 표준·URL-safe base64와 padding 유무를 허용하고 null은 unset으로 처리한다. 최소 호환 수정은 이 optional metadata 하나만 좁게 수용하고, 기존 one candidate/STOP/one text part/strict inner JSON/500자·usage·선택지·개인정보 경계를 유지하는 별도 TDD 주기로 진행한다. 실제 signature는 결과·domain·DB·로그에 노출하거나 새 요청으로 이어붙이지 않는다.

## usage·비용·실행 범위

반환 usage는 input530, candidate188, thought366, total1084다. 출력 합계554는 candidate와thought를 합한 값이며2048 이하다. 이는 공급자가 반환한 usage이고 invoice 확정이나 thoughtSignature의 해석·청구 의미가 아니다. 기존 실패/timeout의 UNKNOWN hold를 환급하거나0으로 보정하지 않는다.

누계는 Decisions6·count2·generation 시도4로 HTTP 시도12다. generation의 관측 성공200은1회지만 현재 parser 수용 성공은0이다. 이번 계획의 누적 최대 예약USD1.01330944는 합산USD2 안의 견적 예약이며 실청구액이 아니다. 이전 예약·미확정 청구·세금·수수료를 확인 또는 환급했다고 주장하지 않는다.

원본은 Git 제외 `.codex/tdd-evidence/issue123-usd2-generation-diagnostic/`에 있다. 이 검토 후의 다음 작업은 실제 추가 호출이 아니라 공식 Part 계약에 대한 새 독립 테스트와 parser 호환성 수정·격리 재생이다. 기존15초 제품 정책·운영 thresholdnull/OFF는 유지한다.
