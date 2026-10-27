# 현재 네 지문의 사후 재구성 RED 보충

정확 과거 제품 skeleton 바이트가 보존되어 있지 않아, 최종 source 복사본에서 기능 부재를 재구성했다. **원래 시간순 RED나 정확 과거 skeleton 재실행이 아니다.** 현재 테스트 네 파일은 byte-exact이며 기대값을 수정하지 않았다. 실행은 global Gradle lock, 실제 격리 PostgreSQL, 합성 provider/RSA만 사용했다. 실제 모델·OAuth·운영 호출은 0이다.

- Worker dispatch no-op skeleton: Worker PS03 + Privacy PS22에서 2 assertion RED → source byte 복구 → 같은 2 GREEN.
- 원문 동일 제안 guard 부재: Defense PS08에서 1 assertion RED → source byte 복구 → 같은 1 GREEN.
- checks POST quota route 등록 부재: Rate 4개 parameter case에서 4 assertion RED → source byte 복구 → 같은 4 GREEN.

합계 7 assertion RED와 7 복구 GREEN, 오류·skip 0이다. 컴파일/환경/fixture 오류로 집계한 RED는 없다. 각 scratch 제품 변형의 정확 diff, 테스트 지문, 실제 명령·local raw log SHA·실패 요약·복구 결과는 manifest에 있다. 실행 script도 보존한다. script의 ROOT/JDK/cache 경로는 당시 workspace 경로이며 재실행 환경에 맞는 경로 준비가 필요하다. Raw logs와 XML 본문은 복사하지 않는다.

새 strict canonical workItem 세 개는 `issue-107-pre-submit-worker-format-replay`, `issue-107-pre-submit-defense-format-replay`, `issue-107-pre-submit-rate-format-replay`다. 최초 original RED의 과거 SHA를 현재 SHA로 소급 변경하지 않았다. 상세 원본과 스키마 projection은 `../issue-107-pre-submit-evidence-schema/`를 참조한다. 현재 원본 source와 테스트는 실행 전후 동일하다.
