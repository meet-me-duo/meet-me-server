# Gemini 관측 실패가 원래 오류 분류를 덮는 경로 보완

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

이 후속 작업은 `c9453a35582ca6503c956ab4e8aff8d73b175ef3`의 실제 개발 평가를 다시 실행하지 않고, 별도 `fix/issue-107-gemini-failure-taxonomy` 브랜치에서 수행한다. 원본 실행 결과·manifest·archive와 원본 feature 브랜치는 보존한다. 추가 유료 호출은0이며 봉인 입력·개별 정답은 열지 않는다. 이 문서는 원본 c945 검증 보고서를 대체하지 않는다.

## 실제 기록으로 확인한 범위

부모가 확인한 c945 개발 평가1회는 Jev HTTP200 6건, Gemini client Call 실패4건, Luna0건이다. 두 모임은 최종 구조화 결과 없이 `ANALYSIS_DELAYED`로 끝났고 holdout은 실행하지 않았다. Gemini의 두 호출에는 HTTP503이 기록됐으며, 나머지 두 호출은 `http_status=null`, 각각 약14.993초 후 실패하고 `CONFIGURATION`으로 저장됐다. 전송 경과 시간은 `elapsed_nanos`이며 고정된 평가 Clock의 DB 시각 차이로 계산하지 않는다.

확인한 최종 parser 관측기는 성공 결과만 저장한다. client Call 관측기는 `callFailed`의 원래 IOException 클래스·내용을 보존하지 않고, coordination attempt는 분류된 kind만 보존한다. 원본 Gradle 로그에서도 알려진 SDK/timeout/dispatch 예외 클래스 및 adapter stack frame을 찾지 못했다. 따라서 `http_status=null`과 저장된 `CONFIGURATION`만으로 실제 설정 오류, timeout 또는 정확한 거절 invariant를 확정할 수 없다. 복원할 원인 자료가 없으므로 그 원인은 미확인으로 유지한다. 503 응답이 공급자와 중간 경로 중 어디서 생성됐는지도 이 제한된 기록으로 단정하지 않는다.

당시 최종 Luna가 호출되지 않은 실행 분기는 명확하다. 첫 모임은 `SERVER → SERVER → CONFIGURATION`, 둘째 모임은 `CONFIGURATION`이었다. `GeminiBatchProcessor`는 비재시도 오류에 즉시 `delay()+return`하므로 마지막 `CONFIGURATION`에서 최종 fallback 분기에 도달하지 않는다. 앞선503으로 fallback 가능 상태가 됐어도 이후 비재시도 오류가 이 경로를 종료한다. 이는 Jev의 개인 보완용 Luna 분기와 별개의 최종 Luna fallback이다.

## 확정한 코드 경로와 최소 수정

설치된 `google-genai:1.72.0`은 IOException을 원래 cause와 함께 SDK 예외로 감싼다. 기존 분류기는 typed timeout cause를 TIMEOUT으로 분류할 수 있다. 그러나 `AiHttpDispatchGate`의 관측 단계에서 deadline이 만료되면 상수 메시지의 `AiHttpDispatchException`이 발생할 수 있고, Gemini adapter의 `finally`가 이를 새 `CONFIGURATION`으로 던져 앞서 분류한 실패를 덮는 코드 경로가 있었다. 이 경로가 재현 가능하다는 사실과 실제 c945의 두 null-status 호출 원인은 구분한다.

수정은 Gemini adapter 하나에서 이미 분류한 원래 실패를 보존하는 것으로 제한한다. 뒤따른 관측 거절은 suppressed 예외로 붙이고 원래 TIMEOUT/SERVER/NETWORK/INVALID_RESPONSE/CONFIGURATION을 전파한다. 원래 실패 없이 정상 SDK 응답의 관측이 거절되면 계속 CONFIGURATION으로 실패한다. 사전 source/config fence 거절 역시 비재시도 CONFIGURATION이다. 새 raw exception 메시지·헤더·body 로그는 추가하지 않는다.

공유 dispatch gate, 정확한 source/context 비교, 권한과 deadline 검사, provider timeout, 최대 재시도·총시간·fallback 정책, prompt와 threshold는 변경하지 않는다. 매번의 재시도·fallback은 기존의 별도 admission과 현재 소유권·source·기한 검사를 거친다. 다른 provider까지 오류 처리를 일괄 변경한 작업이 아니며, 실제 공급자 가용성이나 실제 Luna 성공을 증명하지 않는다.

## 검증 진행 기록

독립 `GeminiObservationFailureContractTest`의 최초7개는 원본 코드에서 네 typed 실패가 CONFIGURATION으로 덮이는 assertion 실패4개와 원래 CONFIGURATION·정상응답 후 관측 거절·사전 source fence 거절 대조군3개 PASS였다. 로그는 `local-only-evidence/followup-gemini-observation-red.log`다. 별도 하네스 안전 계약36개는 TODO skeleton에서 유효 RED를 확인했다(`local-only-evidence/followup-safety-red.log`).

실제 gate의 monotonic deadline 만료를 사용하는 `GeminiExpiredObservationContractTest` 1개는 최초7개 구현 후 별도로 추가됐다. 이를 최초 구현 전 RED8개라고 집계하지 않는다. 후속 정상 회귀와 원본 역전 결함 주입의 근거를 분리해 기록한다. mock SDK factory·in-process interception·합성 DB만 사용하며 추가 공급자 연결은 만들지 않는다.

첫 집중 실행은 신규44개와 기존 bounded fallback·fresh guard·기한·합성 HTTP 계약을 포함한9 suites/123개 PASS였다. 최종 독립 계약7 suites/74개도 실패·오류·skip 없이 PASS했다. 실제 fixture admission과 worker 동시성, 완료 결과 판정, primary-only 모드15개와 blank-key 실제 Spring/HTTP/PG/Redis1개를 포함한다. 후자는 fake CLEAR 사전 검사 뒤 실제 마감·불변 V2 snapshot·원본 Gemini/JDBC fence를 거쳐 GEMINI CONFIGURATION attempt1개·SDK0·Luna0을 확인한 합성 시험이며 공급자 연결 증거가 아니다.

후속 결함 주입12종은 예상 assertion으로 탐지했고 각 파일을 정확 복원했다. 여기에는 Gemini 원본 역전1종, 실제 admission/monitor2종, 최초 cross-split3종, primary-only 경계2종, 실제 앱 모드 연결1종, 완료 경계1종, 최종 CLI outcome2종이 포함된다. Python 최종12methods도 PASS다. 상세 근거는 [독립 감사](experimental-followup-independent-audit.md)와 `.tdd/verification/issue107-followup-*.json`에 분리한다. 최종 전체 gate와 원본 commit guard의 결과는 완료 후 아래에 기록한다. 아직 실모델 재평가 성공으로 보고하지 않는다.

Jev의 raw 의미 판정과 신뢰도 우선순위 및 실험 임계값의 확정 여부는 [별도 정책 감사](experimental-jev-routing-policy-audit.md)를 따른다. 이번 기술 수정으로 그 정책을 확정하거나 변경하지 않는다.

## 전체 검사와 마지막 검증 경계

첫 전체 `ktlintCheck assemble test`는791.665초에 성공했다.292 suites/2386 tests 중2380 PASS·조건부6 SKIP·실패/오류0이다.6 SKIP은 기존 API export/debug3개·기존 live2개·새 primary-only paid smoke1개다. 키3개와 live/primary-smoke/native 실행 플래그를 모두 제거했다. 로그는 `local-only-evidence/followup-fullgate.log`, 당시 XML과 집계는 `local-only-evidence/followup-fullgate-baseline1`에 보존한다.

이 실행 중 별도 리뷰에서 진단 테스트 flow/poll이 예외로 끝날 때 최종 보고서 기록을 건너뛰는 경계를 찾았다. 첫 전체 PASS를 최종 소스 검증으로 전용하지 않고, 승인된 test-only 보고서 보존·poll 중단 경계의 독립 RED/GREEN/FI 이후 원본 guard의 전체 gate를 다시 확인한다. 운영 adapter·retry/fallback 정책의 추가 변경과 유료 실행은 없다. 최종 guard와 로컬 commit은 이 마지막 검증 뒤 기록한다.

마지막 test-only 경계는 독립 신규7개와 기존 실제 앱1개를 강화해 고정했다. flow 실패시에도 최종 보고서 한 번을 남기고 원래 오류를 보존하며, poll 읽기 전후 중단을 확인한다. 진단 계획 출력 경로 불일치와 일회 계획 재사용은 flow 시작 전에 거절한다. 최종8 suites/81개가 실패·오류·skip 없이 PASS했고, 보고 누락·실제 HTTP poll 연결 우회·출력 경로 검사 제거·계획 재사용 허용의 추가FI4종을 모두 assertion으로 탐지한 뒤 정확 복원했다. 앞선12종과 합한 후속FI는16종이며 최종Python14methods도 PASS다. 최초 RED와 후속 역전 RED의 테스트 해시는 독립 근거에서 구분한다. 최종 원본 guard는 이 복구 소스에 대해 전체 gate를 다시 실행한다.

## 최종 가드와 로컬 전달

최종 복구 소스에서 수정하지 않은 `.codex/hooks/tdd_guard.py`를 실행해 exit0을 확인했다(784.334초). 원본 guard SHA-256은 `07c4f974ec7d09b6ff3ca4e3d9e9dfd365bfccb6c10420ff6dbc0b5314473b79`다.293 suites/2393 tests 중2387 PASS·앞서 설명한 조건부6 SKIP·실패/오류0이며, 실행 전81개 집중 XML에서 실행 후 전체2393개 XML로 digest가 바뀌어 전체 테스트의 실제 재실행을 확인했다. 이전 전체2380 PASS를 최종 소스 결과로 재사용한 것이 아니다.

최종 집계·가드 해시·원문 미변경 증거는 `.tdd/verification/issue107-followup-final-quality-gate.json`에 기록했다. 로컬 원본 XML과 요약은 `local-only-evidence/followup-final-guard-results`, 가드 실행 요약은 `local-only-evidence/followup-original-guard-summary.json`에 있다. 모델키·live/primary-smoke/native 플래그는 모두 제거한 검사였다. 추가 유료 호출0·봉인 입력 열람0을 유지한다. 검증된 로컬 commit만 전달하며 원격 push/PR/병합·운영 반영은 하지 않는다. Commit/PR: 동일 커밋 예정 / 원격 없음.
