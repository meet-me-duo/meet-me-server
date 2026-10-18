# #130 무료 관리형 HTTPS 브라우저 검증

현재 상태: **새 managed8 8/8·기존 HOST8 8/8·복구6 6/6 실제 PASS. 최종 독립 결과 검토/필수 guard/게시가 남았다**. 사용자가 승인한 test 전용 launcher/runtime pin/웹 의존성 복구/기존 HTTPS 연결 범위다. 제품 코드·API·UI·DB schema·500자·참여/마감 정책은 변경하지 않는다.

| 실행 재료 | 고정 출처 |
|---|---|
| 서버 본체 | PR133 `6046baf66e49e9382248cd3fb8adb0a8879a7e40` |
| V2 문구 출처 | PR132 `27de97e137d23a2920d425ea521ee33ec0996a8d` |
| A overlay SHA256 | `a723af8f453ad56770d0d141e6f222f21bae3729b245f735bb67e68fe794ece5` |
| B overlay SHA256 | `7e898fcdc0476739ff855f8ae2f62f0d32d5b4660ac58527efe55312cf7af53f` |
| 웹 | PR33 `3c6fb446e3a6e20aec5fd2f9e1eb78233c6f09e6` |

실행 서버는 detached PR133 위에 위 두 V2 파일만 덮은 조합이며, PR132 전체를 merge한 결과로 표시하지 않는다. 신규 시험 도구는 PR132의 `evaluation/issue130/managed-browser/`에서 추적한다. 기존 launcher와 웹 원본은 수정하지 않는다.

| 증거 | 구분/판정 |
|---|---|
| 정확 웹 의존성·타입 검사·격리 빌드 | PASS. 원 source304/lockfile/HEAD 불변·gitclean. [준비 receipt](verification/issue130-managed-browser-web-preparation.json). |
| 새로운 validator/protocol 테스트 | Node68(기존54+daemon binding14)·backend28+guard6+추가4 PASS/독립RED·FI·복구 완료. 세 P2는 독립 재검토로 CLOSED다. 실제 browser 결과와 분리한다. |
| 새 managed M01–M04 × desktop/mobile | 실제8/8PASS. 원장22/claim22/observation22와 물리 합성22 일치·mismatch0/remote0·전체 수명 지문동일·cleanup0. [결과](verification/issue130-managed-browser-actual-new8.json). 모델 의미 정확도 증명이 아니다. |
| 기존 host HTTPS 8 / auth recovery 6 | 원 bytes·기대값 그대로 각각8/8·6/6PASS/재시도·스킵0·cleanup0. [HOST](verification/issue130-managed-browser-actual-original8.json) / [복구](verification/issue130-managed-browser-actual-recovery6.json). 이번 단회 결과이며 과거 rapid unload401/automatic focus 원인 해결을 주장하지 않는다. |
| 실제 OAuth / 실제 모델 / 운영 DB·배포 | 이번 범위에서 미실행. synthetic identity/session은 실제 OAuth 인증 성공 증명이 아니다. |

`/health`의 과거 fixture marker는 기존 spec 호환 alias만 허용한다. 실제 관리형 경로 확인은 별도 `/managed/readiness`의 실제 target bean·4 flags·source/runtime/classpath 지문·startup 전 hard physical loopback gate와 직접 SQL/HTTP 관측을 함께 사용한다. 서비스 readiness와 public CREATE 이후 DB의 room budget READY는 서로 다른 증거다.

검증은 loopback 합성 TLS에만 응답을 준비하고 실제 provider/classifier/parser/worker/fence/PG 원장을 유지한다. count/generation과 선택 응답·명시 재검사, 멱등성/본인 소유권/revision, HOST 미제출 accepted0/1/2 마감 및 late response 차단을 기존 정책으로 확인한다. 지문 불일치·fake provider·OFF flag·외부 연결·누락 원장·불완전 cleanup은 실패 처리한다. 스킵·재시도로 실패를 숨기지 않는다.

실행 후 정확 명령·결과·source/runtime 전후 지문·합성 물리 요청과 실제 모델 요청0을 분리한 원장·정리 상태를 봉인한다. 독립 결함 주입/리뷰와 필수 guard 뒤 동일 Draft132에 정상 후속 게시하고 정확 HEAD CI를 확인한다. 새 완료 증거 전에는 이 문서의 예정 항목을 PASS로 읽지 않는다. 추가 유료 호출0·유지 예약USD2.74751488·기존 UNKNOWN 유지, main/배포/활성화 HOLD다.
