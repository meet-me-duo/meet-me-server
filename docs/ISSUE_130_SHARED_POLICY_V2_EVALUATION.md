# A/B 공통 모호성 정책 V2: 공개 개발 명세와 무료 회귀

2026-10-18 UTC. 사용자 승인 공통 정책표를 A 설명·A/B 실제 전송 지침·새 평가 정의에 일치시키는 명세다. 운영 활성화·실모델 효능 검증이 아니다. API/UI/DB, 원문500 Unicode 제한, 모델, confidence 라우팅, 비용/질문/검사 상한, 인증·마감·revision fence, 최종 전체 분석/AREA 계약은 바꾸지 않는다.

작성자는 기존 합성 corpus 작성·custody·A24 집계·B2 응답 평가에 노출됐다. 이번 자료는 **공개 DEV**이며 비노출 holdout 또는 새 정확도 실적으로 표시하지 않는다. 기존 A16/A24/B2 입력·gold·최초 두 판정·점수는 불변이다. 사후 정책 정합성 긴장이 발견됐다는 사실과 기존 관측 평가를 구별한다. 기존 B2 개인 경계 실패를 모델만의 원인으로 확정하지 않는다. 남은 비공개22 입력은 이번 작성에서 읽거나 재사용하지 않는다.

## 새 평가 정의

허용 조건에는 날짜·시간·장소·방식·부정·예외·적용 범위와 명시적 조건 관계가 포함된다. 단일 날짜/시각으로 줄일 수 있는지를 CLEAR 기준으로 삼지 않는다.

| 규칙 | A 의미 기대 | B 최소 출력 계약 |
| --- | --- | --- |
| 명시적 넓은 허용·대안·선호·제외, 다른 참여자 가용성 의존, 주최자의 날짜/방식 선택권 | 해당 관계를 보존할 수 있고 필수 숨은 사실이 없으면 CLEAR | CLEAR, 질문/선택 강요 없음 |
| 본인 참여의 필수 개인/현장 사실이 원문·제공 문맥에 없고 값에 따라 허용 조건이 달라짐 | NEEDS_REVIEW. 일상 경계를 문구로 복사하는 것만으로 해소했다고 하지 않음 | 원문/context가 제공한 두 안전한 완전 해석이 없으면 NEEDS_EDIT. 개인 시각을 발명하지 않음 |
| 날짜·시간·장소·방식·부정·예외·범위 결속이 미정이며 결속별 허용 조건이 달라짐 | NEEDS_REVIEW | 두 text/context-grounded 완전 제안이면 NEEDS_CHOICE, 아니면 NEEDS_EDIT |
| 위 조건 중 필요한 사실/결속이 원문·문맥으로 이미 해소됨 | CLEAR. 특정 낱말로 판정하지 않음 | 재질문 없음 |
| 의미가 명확하나 현재 최종 스키마 미지원·후보0·검색기간 밖·숫자 부재만 존재 | 그것만으로 모호성을 만들지 않음 | 그것만을 이유로 질문/수정 강요 없음. 최종 분석의 미반영은 별도 |
| 장소 조건의 출발 기준점만 빠짐 | 실제 허용 장소를 바꾸면 NEEDS_REVIEW | 최소한의 사용 가능한 장소 기준만 필요. 정확한 집 주소를 요구하거나 사적 위치를 추측하지 않음 |

안전한 두 제안은 원문의 다른 조건·부정·예외·선호를 모두 보존해야 한다. NEEDS_EDIT는 현재 질문/reason 필드를 갖지 않으므로 사유별 새 UI/API를 요구하지 않는다. 기술 오류·refusal·판단 유보와 의미 모호성은 별개다. A의 실제 반환 choice와 낮은 confidence에 따른 라우팅도 분리한다. 현재 `.8`은 비활성 진단 프로필 기준이며 이 문서가 운영 기준을 새로 정하거나 낮추지 않는다.

## 공개 wire 계약

신규 [A V2 질문 resource](../src/test/resources/precheck/classification/decision-principles-v2-question.json)는 제품 생성 뒤 복사한 snapshot이 아니다. 테스트 작성자가 승인 정책표에서 먼저 정의하고 구현자와 공개 문구만 합의한 기대다. type/name/3choice·기존 NEEDS_REVIEW/INDETERMINATE 설명을 보존하고 CLEAR 설명과 지침을 정합화한다. V1 resource와 원 DPW 테스트 bytes는 history에 보존한다. active DPW는 resource pointer와 두 SHA만 명시적으로 V2로 전환하고 기존 두 검증의 강도를 유지한다.

[공개 DEV resource](../src/test/resources/precheck/shared-policy-v2-development.json)의 `common_normative_clauses` 네 문장이 A와 B 실제 outbound body에 동일하게 들어가야 한다. 이 clause 검증은 wire 전달 요구이며 자연어 문장을 모델이 올바르게 적용한다는 증명이 아니다. 하나의 큰 snapshot만 비교하지 않고 실제 loopback HTTP, 6개 입력 필드, 원문, count 전체 본문, schema/caps, 반환 제안과 직접 수정 파싱 불변을 함께 검사한다.

## DEV12와 대조쌍

자료의 `case_id/policy_rule/expected_a/expected_b/independent_rationale/contrast_pair/safe_complete_proposals`는 공개 테스트 metadata다. 모델 입력은 `input`의 정확6필드만 사용하며 B의 기존 `input_ref`만 추가한다. 개인정보·실제 사용자 원문·비밀은 없다. 새 날짜 기준과 합성 문장으로 만들었으며 기존 private22는 읽지 않았다.

- 개인 경계 미제공과 같은 원문 내 명시 시각 제공 대조.
- 온라인 전용 날짜 결속 미정과 **주최자에게 어느 날짜를 고를지 명시 위임한 negative** 대조. 후자는 누락 개인 사실이 아니라 허용한 선택권이다.
- 사적 출발 기준점 미제공과 출발 장소/이동 방식/범위가 명시된 unsupported-only 대조. 정확한 주소를 받아야만 명확하다고 하지 않는다.
- 정의된 넓은 허용·부드러운 선호·제외, 유효한 타인 가용성 의존, 명시적 시간별 장소 결속, 빈 허용 집합, 기간 밖 조건, 문맥으로 유일하게 정해지는 상대 날짜.

이 자료는 CLEAR9/NEEDS_REVIEW3이며 B CLEAR9/NEEDS_CHOICE1/NEEDS_EDIT2다. 추론 모델을 실행하지 않으므로 이 숫자는 사전 의미 명세 집계일 뿐 성적이 아니다. 이동 수단이나 기준점이 실제로 또 미정인 문장을 schema-only CLEAR로 억지 확정하지 않는다.

## 검증 및 증거 범위

`SharedPrecheckPolicyV2WireTest`는 실제 classifier HTTP 지침·CLEAR 설명, 실제 generator count/generation의 공통 지침, 원문/6context 불변, model/choice/schema/16KiB/500경계, complete proposals 전달과 questionless NEEDS_EDIT 파싱을 검증한다. fixture HTTP는 DEV gold와 무관하게 고정 응답을 돌려준다. REVIEW gold에도 CLEAR를 돌려주는 wire 통제가 있으므로 mock 성공을 의미 정답률로 오해할 수 없다. 별도의 실제 모델 호출·paid·운영 접근은0이다.

예상 RED는 현재 V1 HTTP 지침/설명과 사전 정의 V2 기대의 불일치다. 컴파일/환경/fixture 준비 오류는 유효 RED가 아니다. 지문·실행 명령·실패 assertion을 `.tdd/red/issue130-shared-policy-v2-wire.json`에, 원본 로그를 `.codex/tdd-evidence/`에 남긴다. 후속 구현/리뷰/FI 후에도 실제 모델의 오탐·미탐·질문 품질은 미검증으로 표시한다. 최신 진행 기록과 CI/게시 관리는 부모가 소유한다.
