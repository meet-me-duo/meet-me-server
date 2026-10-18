독립 custodian 평가에서 실제 Decisions A 16건이 모두 HTTP200·유효 분류·완전 usage로 관측됐다. 기술 오류·refusal·미실행은 0이고 재시도, B 생성, count 호출도 0이다. 선택은 봉인된 보완 8대조쌍 16문장 전원이며 원래 개발60·holdout60에서 선택한 사례는 없다. 이 균형 대조 진단을 일반 정확도나 독립 모집단 표본으로 해석하지 않는다.

원래 A gold CLEAR 8건에서는 raw CLEAR 7·raw NEEDS_REVIEW 1, gold NEEDS_REVIEW 8건에서는 raw CLEAR 6·raw NEEDS_REVIEW 2였다. raw exact-label 일치는 9/16이다. 모호성을 놓친 raw CLEAR 6/8과 불필요한 raw review 1/8을 그대로 보존한다. 사전에 고정한 zero-observed-false-CLEAR raw 기준은 **FAIL**이다. 결과에 맞춘 gold·문장·임계값·프롬프트 변경은 없다.

기존 합성 평가 임계값 .8을 적용한 실제 classifier와 TwoStage 경로는 CLEAR_STOP 3, LOW_CONFIDENCE로 INDETERMINATE가 된 B_REQUIRED_NOT_RUN 13이었다. raw INDETERMINATE·MODEL_UNCERTAIN은 0이다. NEEDS_REVIEW gold 8건은 모두 B 경로로 갔고 unsafe early CLEAR는 0/8이지만, 이 우회는 raw miss 6건을 지우지 않는다. CLEAR gold 8건 중 5건은 불필요한 B 경로로 갔으며, 그중 4건은 raw CLEAR 자체는 맞았으나 낮은 confidence로 유보됐다. 이는 질문을 실제로 잘못 생성한 5건이라는 뜻이 아니다. confidence와 CLEAR 확률도 같은 수치로 해석하지 않는다.

B gold는 질문 금지 CLEAR 8건, 선택 질문이 필요한 7건, 선택 질문 대신 직접 수정이 필요한 SAFE_EDIT_ONLY 1건이다. NEEDS_REVIEW gold 8건을 모두 선택 질문 필수로 바꾸지 않았다. 질문 필수 7건의 A raw miss는 5/7이고 edit-only 1건의 raw miss는 1/1이다. 실제 B 호출이 없으므로 질문 내용·선택지·직접 수정·최종 제출·UI의 품질 및 질문 오탐·미탐률은 **측정하지 못했다**. B가 필요한 13건은 여전히 생성 단계 미완료다.

8쌍 모두 양쪽 응답이 유효했다. raw 대조 구분 성공은 1/8, 정규화 경로 대조 구분 성공은 3/8이며 두 지표를 합치지 않는다. 실행 관측은 완료됐으나 분류 품질 성공이나 운영 활성화 승인으로 기록하지 않는다. 운영 임계값 null/TBD와 기본 OFF도 유지된다.

반환 usage 합계는 input 8395, output 0, total 8395 tokens다. 관측 input에 고정 공식 입력 단가 USD0.10/M을 적용한 입력 전용 단순 추정은 USD0.0008395이며 청구서·완전 청구금액 또는 요금 상한 증명이 아니다. output-free 가격을 output token 0으로 강제하지 않았다. 기존 UNKNOWN 보류와 이번 전체 배치 보류를 합한 USD1.79322880, 남은 승인 여유 USD0.20677120, 환급0을 보존한다. 추가 호출을 승인하지 않는다.

16건의 combined per-case 구간은 실제 A와 로컬 route observer를 함께 포함하며 min 108.483095ms·평균 213.726982ms·max 1092.399537ms다. suite 3827.969504ms와 root guard 6693.675814ms는 별도 관측이다. HTTP·adapter·classifier·probe별 시간은 측정되지 않았고 합계로 추론하지 않는다. 운영 p50/p95를 추정하지 않으며 이전 단일사례 7.029s와 합치지 않는다.

원본 wire/hash·응답 bytes/hash·official choice/confidence/probabilities·usage·정규화 및 monotonic 시간을 독립 대조했다. 원문·개별 ID·gold·응답·gold/output 연결표는 이 공개 집계에 넣지 않았다. 비공개 행 점수만 custodian 경로에 보존했다. 기존 실패 suite와 이전 실제 false CLEAR·503·timeout·old-parser 실패는 변경하거나 성공으로 재분류하지 않았다.
