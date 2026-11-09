# V5 WIP 인계 진행 상태

기준: `074f6d239ff92eb4917a46b30d0cba6ccf96ae5f` (기존 원격 DSL/AST 작업 커밋).
이 문서는 공개 인계용 요약이다. 실제 개인 입력/요청응답이 섞인 원 작업 일지는 로컬 원본에 보존하며 이 브랜치에 복사하지 않는다.

- [x] V5 코드와 선행 V2/V3/V4·단어장·저장·마이그레이션 의존성 분리.
- [x] direct locator, 공통 입력/schema, 선호-only 점수 정책과 기존 reader/IR/JDBC/계산 경로의 격리 후보 보존.
- [x] 과거 무료 공통 계약 12/12 및 무전송 합성 JDBC 처리 6/6 결과를 공개 요약으로 기록.
- [x] 개인 자료 없는 공통 schema/prompt fixture와 프로젝트 상대 build origin 경계로 이식성 수정.
- [x] 새 인계 체크아웃의 compile·assemble·정식 ktlintCheck·공통12/12·합성JDBC6/6·개인자료/비밀 재검사·독립 검토.
- [x] 공개용 WIP 인계 snapshot 준비. 실제 게시 결과는 원격 ref/commit SHA 및 별도 게시 receipt로 확인한다.
- [ ] 실제 API 수용·최신 모델 의미·동일40/60초 실행·전체 E2E·전체 gate·정확 commit CI·ARM64·운영 검증.

실제 모델 요청·전송 admission·예약·배포는 이번 인계에서 실행하지 않는다. 기본 LEGACY/운영 정책을 바꾸지 않는다. 기존 모델 실패/UNKNOWN을 성공으로 바꾸지 않는다.

범위·실행 방법·확인된 실패·다음 최소 작업은 [V5_HANDOFF.md](V5_HANDOFF.md)를 참고한다. 공개 검증 요약은 `docs/verification/v5-handoff-free.json`을 참고한다.

사용자 지시에 따라 이 검증 snapshot을 별도 WIP 브랜치에 게시한다. 게시 완료는 원격 ref와 commit SHA 조회 결과로 판정한다. 원 실행물·원본·비공개 증거는 보존했다.
