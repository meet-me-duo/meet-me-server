# 제출 전 verdict 도입 근거 재확인

2026-10-28 UTC, 운영 source `84324bb11afd7b69316b151c580b90f3c8729b4d`의 원격 ADR/PRD와 도입 PR을 읽었다. 이 문서는 조사 결과이며 새 상태 설계의 승인이 아니다.

- [ADR-053](https://github.com/meet-me-duo/meet-me-server/blob/84324bb11afd7b69316b151c580b90f3c8729b4d/docs/ADR.md#L568)은 사용자 합의에 따라 명확하거나 의도적으로 넓은 조건을 좁히지 않고, 실제 모호성만 본인 A/B·직접 수정으로 확인하며, 현재 revision의 CLEAR가 반영된 뒤 완료로 집계하는 제품 행동을 기록한다. D1/D2 승인은 완료·마감 정책이다.
- [ADR-054](https://github.com/meet-me-duo/meet-me-server/blob/84324bb11afd7b69316b151c580b90f3c8729b4d/docs/ADR.md#L585)는 분류 유보를 생성 단계에서 다시 확인하고 기존 질문·직접 수정으로 처리하며, API 오류·refusal과 정상 유보를 구분한다.
- [ADR-059](https://github.com/meet-me-duo/meet-me-server/blob/84324bb11afd7b69316b151c580b90f3c8729b4d/docs/ADR.md#L643)는 개인 생성 공급자를 Luna로 변경한 결정이다. 세 verdict를 새로 도입한 결정은 아니다.
- main 계보에서 `PrecheckProviderContract`의 세 verdict는 [bb62a95](https://github.com/meet-me-duo/meet-me-server/blob/bb62a95ed1a9b00dc3c190721b67040c3de857ab/src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt#L136-L156), [PR120](https://github.com/meet-me-duo/meet-me-server/pull/120)에서 확인된다. 2026-10-17 develop 병합 commit은 `bb996482a3090739a8ce02c8b093561e2c5c58a2`다.
- [당시 설계 표](https://github.com/meet-me-duo/meet-me-server/blob/bb62a95ed1a9b00dc3c190721b67040c3de857ab/docs/ISSUE_107_PRE_SUBMIT_DESIGN.md#L39-L45)는 안전한 A/B를 만들 수 없을 때 직접 수정을 요구하는 NEEDS_EDIT와 FAILED/TIMED_OUT을 구분한다. 확인한 자료만으로 특정 enum 세 개를 사용자가 직접 지정했다고 단정할 수 없다. 사용자 원대화 검색은 이용 불가여서 별도 직접 승인 근거를 확인하지 못했다.

NEEDS_EDIT라는 이름이나 세 분류 자체가 필수는 아니다. 필요한 개인 사실을 모르는 입력을 CLEAR로 통과시키거나, 사실을 발명해 A/B를 만들거나, 입력 보완을 시스템 실패로 표시하는 대신 자유응답 등의 미해결 질문 경로를 둘 수 있다. 이는 현재 A/B 전용 schema·검증·API/웹 흐름의 변경이며 이 작업에서는 선택하거나 적용하지 않았다.

“오후 전체 가능”은 이미 넓은 의도 보존 요구에 해당한다. 정확한 숫자 끝점이 없다는 이유로 NEEDS_EDIT를 정당화할 수 없다. 현재 수정은 이 기존 요구와 문맥상 시간대·요일 범위를 명확히 하는 prompt 변경이며 verdict/schema 삭제는 하지 않는다.

Luna의 실제 outgoing strict schema는 `docs/evaluation/precheck-luna-output-schema.json`에 저장했다. Responses `text.format.type=json_schema`, `strict=true`, 네 필드 모두 required이며 UI 상태 DTO와 별개다. 모델 payload에는 NEEDS_EDIT의 세부 이유 필드가 없다.
