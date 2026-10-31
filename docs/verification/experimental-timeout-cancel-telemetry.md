# Timeout/cancel 선택 진단 계약

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

이 후속은 `f75fa60087ac7d74619585cd41e6ba1e0d1c5e0e`의 원본 실행·보고서·manifest·패키지·가드 XML을 변경하지 않는다. 기존 판정·prompt·threshold·15초 Gemini 호출 예산·60초 분석 예산·source fencing·재시도/fallback 정책은 유지한다. 구현 담당자의 실제 모델 호출은0이다. 새 실제 실행은 별도 승인과 누적 physical API 최대10·같은 기술 오류2회 중단, 소스 고정 후 부모 GO를 따르며 이 구현 검증과 구분한다.

## 관측과 보존 경계

기존 `ParserTransportObservationPort`의4필드 client-call DTO, PostgreSQL 관측 테이블과 비동기 영속 경로는 변경하지 않는다. 새 optional `ParserTransportDiagnosticPort`는 로컬 invocation/attempt 식별자, provider, 제한된 상대 기간·이벤트·원인 enum만 전달한다. 기본 bean은 없고 실험 하네스가 메모리에 수집해 새 sidecar로 export한다. 진단 recorder 미설정·기록 실패는 기존 결과를 바꾸지 않는다. DB migration과 새로운 운영 로그는 없다.

`actualCallTimeoutNanos`는 `callStart`에서 읽은 `Call.timeout().timeoutNanos()` 설정값이다.0은 설정상 무제한이고 null은 미관측이다. 요청/adapter 예산은 별도 필드이며, dispatch와 call deadline의 남은 기간은 관측 시각에 차감해 음수를0으로 제한한다. `requestHeadersDeadlineRemainingNanos`는 첫 `requestHeadersStart`에서 읽은 필드다. OkHttp가 이미 타이머를 예약한 뒤 deadline 필드를 변경했을 수도 있으므로 이 값 자체를 실제 재예약된 watchdog 시각으로 해석하지 않는다.

클라이언트 호출은 최대256개, 이벤트는 호출당16개, 원인 chain은8개로 제한하고 잘림·미완료·메타데이터 읽기 실패를 명시한다. 원인 class는 정확한 allowlist를 enum으로 바꾸고, 미허용 하위 class는 OTHER다. 메시지 문자열을 파싱하지 않으며, reset code는 typed HTTP/2 reset 예외의 enum만 관측한다. URL·header·body·원문·키·예외 message와 미허용 class명은 전달하지 않는다.

callStart, requestHeadersStart/End, responseHeadersStart/End, canceled, callFailed/callEnd는 발생 순서와 상대 기간으로 기록한다. canceled/isCanceled와 reset code는 로컬 관측 사실이며 원격 reset의 최초 원인이나 방향을 확정하지 않는다. 기존 진단 listener의 requestHeadersStart는 실험 counter의 거절보다 먼저 관측될 수 있으므로 헤더 송신 성공으로 보지 않는다. headersEnd에서만 로컬 헤더 쓰기 완료가 관측됐다고 표시하며, 이것도 공급자 수신·청구 증거가 아니다. 불완전하거나 없는 관측은 UNKNOWN으로 유지한다.

## 검증 상태

독립14개에서 opt-in TODO/미연결 실패13개와 DTO shape 대조군1개를 먼저 확인했다(`local-only-evidence/telemetry-session-red.log`). 구현 후 신규14개·기존 Session9개·실패 보존7개·실제 gate 만료1개를 포함한4 suites/31개가 실패·오류·skip 없이 PASS했다(`local-only-evidence/telemetry-session-green-2.log`). 첫 GREEN 시도는 formatter의 긴 줄 검사로 테스트 전에 종료됐으며 현재 코드 형식 교정 뒤 재실행했다.

읽기 검토에서 큰 요청 Duration의 진단용 toNanos 변환이 기존 min15초 동작에 영향을 줄 수 있는 경계를 추가로 발견했다. 별도 독립2개에서 opt-in 유무 모두 ArithmeticException으로 실패하는 RED를 확인했다(`local-only-evidence/telemetry-overflow-red.log`). 최소 수정 후 default OFF는 진단 변환을 생략하고, opt-in에서 표현할 수 없는 요청 기간은 포화값으로 위장하지 않고 null/metadataUnavailable로 보존한다. 실제 adapter 예산은 그대로15초다. 신규2개와 앞선31개를 합한5 suites/33개가 실패·오류·skip 없이 PASS했다(`local-only-evidence/telemetry-overflow-green.log`). 공유 Gate/호출 상한 하네스·결함 주입/정확 복원·최종 원본 guard는 후속이다. 이전 f75의 검사 성공을 새 계측 검증으로 전용하지 않는다.

독립 실행 담당자가 취소 이벤트 누락·실제 timeout 값 제거·typed reset 원인 제거·진단 recorder 예외 유출의4종 결함을 주입했다. 각14개 테스트가 실행되어 예상 assertion으로 모두 탐지했고 매번 Session 원본 바이트로 정확 복원했다. 최종33개도 실패·오류·skip 없이 PASS했다. 원본은 `local-only-evidence/evidence.json`, 추적 요약은 `.tdd/verification/issue107-timeout-diagnostic-fi.json`이다. 추가 공급자 호출은0이다. shared Gate/상한 하네스와 최종 전체 가드는 아직 후속이며 이4종 FI를 그 범위 검증으로 전용하지 않는다.

공통 wire hook/counter는 독립23개·동시성1개·실제 gate 조합9개를 TODO/행동 RED로 고정했다. 첫 counter 시도는 stub 서명 불일치, 첫 gate 시도는 fixture의 deadline 필드명 오기로 컴파일 전에 끝났으며 RED에 집계하지 않았다. 현재 방의 여러 execution 통과1개는 구현 후 회귀로 구분한다. 최종 분류 미완료 상태의 단계 진입1개와 정확한 execution/operation/attempt/ordinal 상관관계5개도 별도 RED 뒤 최소 수정했다. 이들과 기존 권한·기한 회귀를 포함한14 suites/121개가 실패·오류·skip 없이 PASS했다(`local-only-evidence/telemetry-finalization-green.log`).

독립5종 추가 결함 주입은 상한 확대·같은 오류 중단 지연·source binding 무시·미지 오류의 NETWORK 위장·헤더 전 거절 제거를 모두 assertion으로 탐지했다. 매회 정확 복원 뒤11 suites/73개가 PASS했다(그대로인 기존 권한 회귀48개는 이 복구 실행에서 반복하지 않았으며 앞선121개와 구분한다). 원본은 `local-only-evidence/evidence.json`, 추적 요약은 `.tdd/verification/issue107-cumulative-counter-wire-fi.json`이다. 앞선 계측4종과 합한 현재 FI는9종이다. 실제 실행용 fixture/driver의 계획된 mock과 전체 원본 guard는 후속이며 추가 유료 호출은0이다.

실제 실행 경로는 기본 OFF인 독립 cumulative entry로 분리했다. source/config/fixture 해시·clean HEAD·키3개 준비를 확인한 뒤 한 번 쓰는 승인 UUID를 먼저 소진하고 새 output/ledger를 만든다. 새 fixture는 원래 Jdbc source authority를 먼저 호출하며, connectivity만 CLEAR 사전 검사 test double을 명시한다. 각 연결 진단 방은 FINAL_PRIMARY 실제1회만 허용하고 실패 후 재시도/fallback을 막으며, 최대2번째 새 방도 같은 ledger를 사용한다. 실제 Gemini 구조화 성공만 E2E 진입을 허용한다. E2E는 원래 provider/router/retry/fallback을 그대로 사용하고 전체10/같은 정규화 오류2회 상한을 공유한다.

계획된 마지막 mock8개는 driver5개 TODO와 반환형 REFUSED 관측 누락1개의 유효 RED6개, LOW_CONFIDENCE/MODEL_UNCERTAIN 정상 유보 대조군2개 PASS를 확인했다(`local-only-evidence/telemetry-runtime-contracts-red-2.log`). 최초 시도는 format 긴 줄로 테스트 전에 끝났으며 RED에 집계하지 않았다. REFUSED 관측은 결과를 바꾸지 않고 알려진 CLASSIFICATION 물리 호출에 한 번만 연결한다. 정상 유보는 기술 오류로 계수하지 않는다. 최소 수정 뒤 기존121개와 합한16 suites/129개가 실패·오류·skip 없이 PASS했다(`local-only-evidence/telemetry-runtime-contracts-green.log`). 그 뒤 source/test/runtime를 고정해 수정하지 않은 원본 guard로 전체 검사를 실행한다. 추가 유료 호출은 계속0이다.

## 최종 전체 가드와 전달

수정하지 않은 `.codex/hooks/tdd_guard.py`가814.966초에 exit0으로 종료됐다. 원본 SHA-256은 `07c4f974ec7d09b6ff3ca4e3d9e9dfd365bfccb6c10420ff6dbc0b5314473b79`다.304 suites/2458 tests 중2451 PASS·조건부7 SKIP·실패/오류0이며,129개 집중 XML에서 전체2458개 XML로 digest가 바뀌어 전체의 실제 재실행을 확인했다.7 SKIP은 API export/debug3개와 기본 OFF live/진단 entry4개다. 키3개와 모든 MEETME 실험 플래그를 제거했다. 새 실제 모델 호출·holdout 열람은0이다.

최종 XML/집계는 `local-only-evidence/telemetry-final-guard-results`, 가드 요약은 `local-only-evidence/telemetry-original-guard-summary.json`에 보존했다. 추적 요약은 `.tdd/verification/issue107-timeout-cap10-final-quality-gate.json`, 독립 감사는 [별도 문서](experimental-timeout-cancel-independent-audit.md)다. 원본 c945/f75 결과·manifest·패키지·가드 XML은 그대로 유지한다. 커밋 전 실제 키3개를 메모리에서 대조하고 raw 로그가 ignore되는지 확인하며, 검증된 로컬 commit만 전달한다. 실제10상한 실행은 새 manifest/source 고정과 부모 GO 이후 별도 담당자가 진행한다. Commit/PR: 동일 커밋 예정 / 원격 게시 없음.
