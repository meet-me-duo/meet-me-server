# 제출 전 시간 조건·직접 수정 안내 검증

## 완료한 로컬 변경

넓은 시간 가능 범위와 같은 연속 구간의 오전·오후 문맥을 강제로 좁히지 않도록 generation prompt를 보강했다. 보완문은 요일·반복 범위도 보존한다. 필요한 개인 사실을 추측해 A/B를 만드는 대신 NEEDS_EDIT에 구체적인 보완 질문을 담아 본인에게만 표시한다. 세 verdict와 strict model JSON의 네 필드 타입은 유지한다.

새 공개 필드 `check.edit_guidance`는 최대160 코드포인트 일반 텍스트 또는 null이다. NEEDS_EDIT에서 기존 A/B question은 null이며 안내만으로 완료·선택 권한을 만들지 않는다. 모델 안내의 원문 구간·길이·단일행·표시 안전성을 검증하고 기존 JSON으로 보존한다. null 안내의 기존 기록은 모델 사유를 지어내지 않는 서버 일반 안내를 제공한다. 새 SQL migration·배포 guard·키·권한 변경은 없다.

## 기존 사용자 입력 진단

부모가 전달한 운영 PC 진단상 동일 원문의 revision1~3은 NEEDS_EDIT, failureCode 없음, 질문0, 재검사 아님이었다. Luna는 정상 응답했고 약2.931초였다. timeout·인증·파싱·질문 한도·배치 길이 원인은 제외됐다. 실제 Jev 원판정·확률과 Luna의 구체 사유는 기록되지 않아 정확한 의미 오판 이유는 확인되지 않았다.

이 작업에서 사용자 원문을 실제 모델에 다시 보내거나 사용자 방을 마감·수정·삭제·재입력하지 않았다. 기존 원문의 새 운영 결과는 미확인이다.

## 실제 합성 호출 8회

모두 사전 고정한 개인정보 없는 개발 사례이며 서로 다른 세 후보에서 실행했다. 같은 입력의 중복 유료 호출은 없고 최종 후보에서 8개를 모두 검증했다고 주장하지 않는다.

| 단계 | ID/유형 | 실제 결과 | 확인 범위 |
| --- | --- | --- | --- |
| 최초 prompt 후보 | TF02 문맥상 명확한 오후 연속 범위 | CLEAR, 2749ms | 끝점 오전·오후 재질문 없음 |
| 최초 prompt 후보 | TF05 구체 요일 범위와 넓은 오후 조건의 결합 | CLEAR, 1606ms | 넓은 조건 강제 축소·직접 수정 없음 |
| 최초 prompt 후보 | TF06 오후 전체와 명시적 제외 | CLEAR, 1620ms | 넓은 가능 범위·불가 조건 보존 의도 통과 |
| 최초 prompt 후보 | TF08 실제 오전·오후 모호성 | NEEDS_CHOICE, 3375ms | 구체 A/B 질문 확인. 단일 날짜로 구체화되어 날짜 범위 보존은 미확인으로 남김 |
| 최초 prompt 후보 | TF11 필수 개인 종료 시각 부족 | NEEDS_EDIT, 2848ms | A/B 시간을 지어내지 않음. 안내 추가 전 후보라 질문 없음 |
| 최초 prompt 후보 | TF12 필수 가능/불가 조건의 충돌 | NEEDS_EDIT, 3126ms | 충돌 조건을 임의로 지우지 않음. 안내 추가 전 후보 |
| 요일 보존 후보 | TF13 명시적 매주 조건의 오전·오후 모호성 | NEEDS_CHOICE, 2454ms | 두 보완문 모두 매주 금요일 범위를 보존 |
| 구체 안내 후보 | TF14 필요한 개인 예약 종료 시각 부족 | NEEDS_EDIT, 5183ms | 필요한 종료 시각만 질문, 정확한 원문 구간·빈 choices. 예약 내용·시간 추측 없음 |

TF08의 정확한 입력을 최종 소스에서 다시 평가하지 않았다. TF14 이후의 방향 제어문자 차단 보강은 검증을 강화했으며 generation instructions와 실제 TF14 request의 instructions가 같음을 독립 검토했다. 최종 모델의 일반 정확성·운영 Jev 판정·2단계 전체 지연·사용자 방 전체 흐름·통계적 오탐률은 이 소수 평가로 입증되지 않는다. 토큰 usage는 청구 증명이 아니다.

## 자동 검증 근거

- `.tdd/red/precheck-time-flexibility.json`: 최종 정책 테스트6개 중 기존 소스의 정책 누락4개 RED.
- `.tdd/red/precheck-edit-guidance.json`: 안내 계약·PostgreSQL22개 중 신규 동작 누락12개 RED.
- `.tdd/red/precheck-edit-guidance-bidi.json`: 방향 제어문자6개 RED.
- 집중 최종 정책·안내·방향 제어문자34개 PASS. 안내 추가 전 후보의 전체 gate는2242개 중2238 PASS·기존4 SKIP였다.
- `.tdd/verification/precheck-edit-guidance.json`: 안내 파싱·표시 검증·A/B 카운트·공개 projection FI 결과.
- `.tdd/verification/precheck-time-flexibility-final-gate.json`: 최종 전체 gate의 실제 완료 결과. 진행 중에는 이 파일의 성공을 가정하지 않는다.
- `docs/verification/precheck-edit-guidance-independent-review.md`: 독립 검토와 실제 TF14·OpenAPI 근거.

## 원격·통합 범위

신규 이슈 생성은 자동 승인 심사에서 두 번 거절돼 새 이슈는 없다. 추가 재시도·다른 경로 우회·원격 게시·PR·develop/main 원격 병합·운영 배포는 하지 않았다. 정확한 승인 대상과 본문은 `precheck-issue-draft.md`에 보존했다.

최신 main e799fb6/develop0ce4b40은 내 모임 API를 포함한다. 이 작업과 production source 경계는 겹치지 않으며 진행 문서 상단의 두 기록을 보존해 통합한다. 최신 통합 native OpenAPI는 병합 후 다시 생성해 내 모임 필터·페이지 계약과 edit_guidance가 함께 있는지 확인해야 한다.
