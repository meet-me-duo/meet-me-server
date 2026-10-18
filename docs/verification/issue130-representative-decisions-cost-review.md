# #130 대표 Decisions 평가 비용 사전 독립 검토

2026-10-18 UTC. 리뷰어 `/root/review_pre_submit_design`. **ACCEPTED_PLANNING_RESERVATION_BASIS / EXECUTION_HOLD**. 기존 승인된 합산 USD2 안에서 아래 N16 진단의 계획용 보수 예약 기준을 수용한다. 공급자가 강제하는 청구 hard cap이나 실제 invoice 상한을 입증한 판정은 아니다. 최종 helper/mock/FI·실행 바이트/plan/runtime 봉인과 별도 실행 전 리뷰·root의 명시 실행 단계는 아직 필요하다. 이 문서 자체로 호출하지 않는다.

## 근거와 범위

금일 실제 모델/키/계정 조회 없이 공식 primary 문서를 다시 읽었다. [Decisions 가격 안내](https://developers.openai.com/api/docs/guides/decisions#pricing-and-availability)는 `gpt-6-luna`의 입력 USD0.10/M, 출력·cache-read·cache-write 요금 없음, 지역 처리와 long-context 배수 적용을 명시한다. 다른 Luna API의 출력 가격을 이 진단에 섞지 않는다. [create reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)에서 Decisions 전용 `max_input_tokens`/`max_output_tokens`에 의한 청구 상한은 확인하지 못했다.

공개 사전등록 기준은 `/tmp/issue123-representative-evaluation/public/cost-proposal.json` SHA `d67124833c700fecc912c05bfde342e42a28421672471f3ce0f8662180c369de`, 공개 selection SHA `8571fdccaa9785cacf40c6fbc2aea62e78d8a7acdd8b0cd1333de4c600982626`다. 원문·개별 ID/gold·private index는 이 비용 검토에서 읽지 않았다. 선택 범위는 봉인된 별도 8대조쌍/16개 전원, 원120 선택0이다. semantic 결과에 따른 교체/증원은 허용하지 않는다.

이 범위는 canonical `POST /v1/decisions` A 최대16회, 고정 질문1개·모델/현재 prompt·기존 평가 fixture threshold0.8이다. serial1, 자동 retry/redirect/fallback/continuation0, Gemini count0·generation0·다른 모델0이다. B_REQUIRED_NOT_RUN은 실제 TwoStage의 로컬 분기 관측이며 유료 B나 생성 질문을 의미하지 않는다. 운영 threshold null/OFF·실행 권한·가격 registry·배포는 바꾸지 않는다.

## 정확한 Decimal 예약 계산

| 항목 | USD |
| --- | ---: |
| 기존 known/UNKNOWN 합산 노출 예약 유지 | 1.33185536 |
| 사례당 계획 예약: 65536 × 0.10 / 1000000 × 2.2 | 0.01441792 |
| N16 최대 추가 계획 예약 | 0.23068672 |
| 합산 최대 계획 예약 | 1.56254208 |
| 승인 USD2와의 계획상 여유 | 0.43745792 |

위 산술은 정확하다. 기존 독립 비용 검토 `docs/verification/issue123-fresh-head-two-stage-cost-review.md`(SHA `51b1179fb74b6e995ad683941498cca8064e95440fc1bb5b7b835cb1171350a1`)의 동일 65536/2.2 기준을 다시 쓰며 실제 모델이 저렴하게 응답할 것이라는 예측으로 예약을 낮추지 않는다. N16 전체 추가 예약은 시작 전에 묶고 이번 평가에서 이전 timeout/UNKNOWN 또는 새 STARTED/UNKNOWN을 환급하지 않는다. 시작하지 않은 사례는 UNRUN으로 별도 기록하고 성공/실제 청구0을 만들어내지 않는다.

## 강제되는 경계와 추정의 한계

전체 canonical request와 response 각각16KiB·원문500 codepoints·사례당15초·startup을 포함한 suite300초·단회/순차 실행은 실행 전 별도 mock과 고정 helper에서 검증할 기술 경계다. 이 바이트·시간·횟수 제한은 **공급자의 billed input 65536 상한을 증명하지 않는다**. 65536은 inherited engineering proxy이고 실제 tokenizer 계산이나 공식 pre-billing cap이 아니다. 2.2도 실제 계정의 세금·수수료·환산액을 증명하지 않는다. 여유 USD0.43745792가 모든 부대비용을 보장한다고 주장하지 않는다.

따라서 이 판정은 기존 proxy와 불확실성을 보존하는 소액 진단의 계획용 예약 수용에 한정된다. 공식 invoice hard cap을 필수로 요구하는 실행에는 이 근거가 충분하지 않으며 fail closed해야 한다. 출력 요금이 없다는 사실은 output token count가0이거나 실패 요청 usage가0이라는 뜻이 아니다. Returned usage는 관측값이며 invoice가 아니다.

실제 HTTP non200/timeout/connection·거절·parser/schema/usage 오류 또는 unknown/incomplete usage, 관측 input>65536, source/profile/예약/fence/개인정보 경계 오류가 나면 **다음 사례 전에 전체 STOP**하고 노출을 UNKNOWN 포함 그대로 유지해야 한다. 응답 후 STOP은 이미 나간 호출의 청구를 소급 제한하지 못한다. 정상 semantic 오답·낮은 confidence·MODEL_UNCERTAIN만으로 선택된 균형 집합을 중도 교체/적응 중단하지 않는다. 추가 호출로 실패를 복구하거나 N16을 채우지 않는다.

count와 B의 추가 요금 노출을0으로 두는 이유는 해당 물리 호출 자체가0이기 때문이다. count가 무료라고 추정하거나 미관측 B usage를0으로 대체하지 않는다. 실제 기록에는 알려진 input/output counts와 unknown·refusal·error·UNRUN, raw 분류와 정규화 라우팅을 분리한다. 최종 질문/수정 품질, 제출 완료, 운영 정확도·calibration·p50/p95는 이 A-only 비용 검토의 결과가 아니다.

## 최종 실행 전 조건

이 파일의 SHA만 `costProfile.review`에 고정할 수 있다. 최종 plan에는 정확 HEAD·prod serializer/classifier/TwoStage와 helper·실제 compiled runtime/classpath·공개 oracle/STOP 기준·16개 prepared wire의 개수/지문·고정 output/단회 소비 marker를 별도로 고정해야 한다. 이 문서/최종 리뷰의 자기 참조 hash는 source manifest에 넣지 않는다.

가격/모델/질문 수·proxy/배수·기존 hold·N16·호출 종류/횟수가 달라지거나 이전 노출을 전액 보존하지 못하면 이 예약 수용은 적용되지 않는다. 본 리뷰 이후에도 **유료 실행은 HOLD**이며, 최종 독립 실행 전 ACCEPTED와 root 단독 실행 봉인 전 호출0을 유지한다. 리뷰어는 모델/API/auth/Secret 조회·변경, helper 실행, Gradle, 코드/테스트/Git 변경을 하지 않았다.
