# Issue #92 모임별 격리 API 검증 결과

2026-10-07 UTC 검증. 방의 시간대는 Asia/Seoul이며 검색기간은 `[2026-10-07, 2026-10-12)`이다. 모든 입력은 합성 데이터다. 실제 PostgreSQL 18, HTTP API, Application processor와 결정론적 matcher를 실행했다. 자연어 구조화는 명시적 mock port를 사용했고 외부 Gemini 호출은 0회다. Redis consumer를 경유한 실행이나 실제 모델의 자연어 정확도를 증명하지 않는다.

| 모임 | 참가자 원문 | 변경 | 기대 결과: 10월 8일 한국시간 | 실제 결과 | 실패 |
| --- | --- | --- | --- | --- | --- |
| 2명 인원 마감 | host: 2026년10월8일 오후7시부터9시까지 가능해요<br>member1: 2026년10월8일 오후7시부터9시까지 가능해요 | 없음 | 19:00–21:00 / host, member1 | 19:00–21:00 / host, member1 · COMPLETE | 0 |
| 3명 인원 마감 | host: 2026년10월8일 오후7시부터9시까지 가능해요<br>member1: 2026년10월8일 오후7시부터9시까지 가능해요<br>member2: 2026년10월8일 오후8시부터10시까지 가능해요 | 없음 | 20:00–21:00 / host, member1, member2 | 20:00–21:00 / host, member1, member2 · COMPLETE | 0 |
| 2명 시간 마감 | host: 2026년10월8일 오후7시부터9시까지 가능해요<br>member1: 2026년10월8일 오후8시부터10시까지 가능해요 | 없음 | 20:00–21:00 / host, member1 | 20:00–21:00 / host, member1 · COMPLETE | 0 |
| 2명 수동 마감 | host: 2026년10월8일 오후7시부터9시까지 가능해요<br>member1: 2026년10월8일 오후7시부터9시까지 가능해요 | 없음 | 19:00–21:00 / host, member1 | 19:00–21:00 / host, member1 · COMPLETE | 0 |
| NO_MATCH 뒤 수정 | host: 2026년10월8일 오후7시부터8시까지 가능해요<br>member1: 2026년10월8일 오후9시부터10시까지 가능해요 | member1: 2026년10월8일 오후7시부터8시까지 가능해요 | 19:00–20:00 / host, member1 | 19:00–20:00 / host, member1 · COMPLETE | 0 |
| PARTIAL 뒤 수정 | host: 2026년10월8일 오후7시부터9시까지 가능해요<br>member1: 2026년10월8일 오후7시부터9시까지 가능해요<br>member2: 미확정 시간 입력 | member2: 2026년10월8일 오후7시부터9시까지 가능해요 | 19:00–21:00 / host, member1, member2 | 19:00–21:00 / host, member1, member2 · COMPLETE | 0 |

NO_MATCH 사례는 최초 공개 상태 NO_MATCH와 빈 후보를 HTTP assertion으로 확인했다. PARTIAL 사례는 최초 2/3 참석과 원문 보존을 확인한 뒤 세 번째 참여자만 수정했다. 두 수정 사례 모두 새 분석으로 COMPLETE가 됐으며, 확정 후 수정 라운드를 다시 열려는 요청은 409로 거절됐다. 원문 저장은 분석을 자동 실행하지 않았다.

최초 NO_MATCH의 room snapshot은 원본 JSON에서 마지막 snapshot으로 대체된다. 최초 NO_MATCH 공개 상태의 근거는 통과한 테스트 assertion이며 최초 candidates 응답은 원본 보고서에 별도 보존됐다.

재현: `CorrectionMeetingScenarioPostgresTest` 6개. 원본 JSON은 로컬 전용 `scratch/meet-me-bootstrap/issue-92-meeting-scenarios`에 저장하고, 간결한 결과는 `.tdd/scenarios/issue-92-meeting-scenarios.json`에 추적한다. 운영 방/사용자 데이터는 조회하거나 변경하지 않았다.
