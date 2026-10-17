# PS06 두 번째 provider fixture 정합

A 선택 후 같은 `question()`을 재사용하면 두 번째 AM 제안이 현재 raw와 같아진다. 승인된 구조 검증은 이를 INVALID_RESPONSE로 처리하므로, 반복 질문 금지 정책을 검증하던 합성 fixture가 충돌했다. 최초 95개 실행은 94 PASS/이 한 건 FAIL이었다.

두 번째 합성 provider 응답만 현재 raw와 서로 다른 두 전체 제안으로 분리했다. AM/PM opaque ID, source excerpt, NEEDS_EDIT·RECHECK_REQUIRES_EDIT·미완료·accepted 0 기대는 모두 유지했다. 제품 코드를 이 fixture 때문에 완화하지 않았다. 원본은 최초48 history의 Worker를 참조한다. 후속 재구성 RED/FI는 사후 보충이며 시간순 최초 RED가 아니다.
