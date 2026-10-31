# V2 입력 근거 실험의 로컬 검증

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

기준은 main `40aea5139525b9ed2cba34428f0072cc42e15820`, 작업 브랜치는 `feature/issue-107-evidence-precheck`다. 원문과 한 번의 보완 답변을 별도의 불변 근거로 보존하고, 접수와 해석 확정을 분리하는 실험을 구현했다. 개인 검사와 마감·동결·최종 분석·주최자 진단은 같은 제출 버전의 근거를 소비한다. `meetme.precheck.evidence-enabled`는 기본 OFF이며 기존 V1 기록과 개인정보 계약은 소급 변경하지 않는다.

실험 가정·운영 전 결정 사항은 [V2 계약](../EXPERIMENTAL_INPUT_EVIDENCE.md)을 따른다. 이 기록은 실제 모델의 의미 정확도, 실제 공급자 로그인, 외부 exactly-once, 원격 CI나 운영 배포를 증명하지 않는다. 아래 모든 Gradle 검증은 모델 키 세 개와 live 평가 플래그를 제거한 환경에서 실행했다. 실제 모델 호출은 이 기록 시점0회다.

## 구현 경계와 독립 검증

원문을 생성된 전체 대체 문장으로 바꾸지 않는다. 보완 대상의 코드포인트 위치·질문·선택/직접 답변·각 기준 시각을 보존한다. 질문은 실험상 방·참여자당 한 번이며 수정·취소·재시도로 초기화하지 않는다. 답변 후 유보는 CLEAR로 바꾸지 않고 최종 분석에 전달한다. 낮은 신뢰도·모델 유보·답변 후 검토 필요는 서로 다른 이유를 보존하고, 거부·타임아웃 같은 기술 실패와 구분한다.

최종 모델은 원문과 보완 근거를 받아 기존 조건 schema로 구조화한다. 서버의 결정론적 후보 계산은 유지한다. 최종 모델이 다시 질문하거나 입력 하나의 필수 시간 의미를 해석하지 못하면 그 입력만 안전하게 제외한다. 다른 사람의 가능 시간으로 개인 사실을 확정하지 않는다. 새 V2 주최자 진단은 최종 미반영 원문과 답변만 공개하며 중간 질문과 타인의 개인 화면은 공개하지 않는다.

독립 검토와 결함 주입의 범위·소스 지문은 [최종 감사](experimental-input-evidence-aggregate-audit.md), [기능 FI](../../.tdd/verification/issue107-evidence-contract.json), [정밀도 FI](../../.tdd/verification/issue107-evidence-precision.json)에 기록했다. 기능 결함11개 및 정밀도 결함1개가 실제 assertion에 탐지됐고 각 실행 후 exact restore를 확인했다. 복원 기능53개와 정밀도3개는 별도 실행 결과이며 합산해 새로운 전체 테스트 수로 집계하지 않는다.

V2 영속 기준 시각은 PostgreSQL과 JSON에서 동일한 마이크로초 값으로 보존한다. KST 자정 직전 나노초 값이 DB 반올림 때문에 다음 날로 이동하지 않도록 저장 시 정규화했다. 권한·마감 검사에는 원래 Clock 값을 계속 사용하고 source fingerprint 비교에 허용 오차를 추가하지 않았다. 실제 PostgreSQL dispatch authorize의 긍정 대조군도 통과했다.

## 전체 검증과 역사 fixture 정렬

최초 전체 실행은 284 suites/2311건 중2300 PASS·6 FAIL·5 SKIP·오류0이었다. 실패 원인은 현재 V21 reader를 V17 역사 fixture 준비에 사용한 사례, 추가 native API 필드의 배포 메타데이터 미반영, V20만 검증해야 하는 migration 실행 범위, 새 nullable 필드를 기존 V8 값 비교에 포함한 차이였다. 이를 성공으로 집계하지 않는다. 원본은 `local-only-evidence/analysis-full-gate.log`와 `local-only-evidence/full-gate-first-xml`에 보존했다.

역사 fixture는 V17/V18 단계에서 명시적인 test-only 저장 계약을 사용한다. 같은 두 명/다섯 명 선택이 V17에서는 SQLSTATE23514로 거절되고 V18에서는 성공하는지 rollback-only JDBC로 검증한 뒤, V21을 적용·검증하고 현재 생산 reader로 전환한다. 원래 행 보존·HTTP 결과·선택·멱등·개수 검증은 유지했다. V20 budget 테스트는 V20을 명시 대상으로 고정했고 V8 비교는 새 nullable 키만 분리하면서 모든 과거 evidence가 NULL임을 별도로 확인했다. 생산 코드의 missing-column probe나 fallback은 추가하지 않았다.

native API exact-projection 테스트는 변경하지 않았다. protocol과 모든 operation은 동일하고 승인된 두 evidence schema와 기존 응답 네 종류의 필드 다섯 개만 메타데이터에 추가했다. 관련5suites/9건은 모두 PASS·실패/오류/SKIP0이다. 근거는 `local-only-evidence/evidence-fullgate-fixture-focused-2.log`, `local-only-evidence/full-gate-fixture-focused-xml`이다.

최종 원본 명령 `ktlintCheck assemble test --console=plain`은 **12분34초 SUCCESS**, **284 suites/2311건 중2306 PASS·실패/오류0·의도된 SKIP5**다. SKIP은 native API export/debug3개와 명시 opt-in 실제 모델2개다. 전체 결과를 선별 실행으로 대체하지 않았다. 원본 로그는 `local-only-evidence/analysis-full-gate-final.log`, 요약은 `local-only-evidence/analysis-full-gate-final-summary.json`, XML은 `local-only-evidence/full-gate-final-xml`이다.

추가로 변경된 API 메타데이터 소비를 검증한 오프라인 Node120/120, Python41/41이 통과했다. Python 첫 실행의 Docker 정책 fixture1개는 `--pull=never`에 필요한 `alpine:3.22` 이미지가 없어 exit125였다. 기존 허용 경로에서 해당 고정 이미지를 준비한 뒤 소스·테스트 변경 없이 같은41개를 재실행해 통과했다. 근거는 `local-only-evidence/offline-final-v21-node.log`, `local-only-evidence/offline-final-public-contract-python-green.log`다.

## 소스 고정과 후속 실행

원본 `.codex/hooks/tdd_guard.py`가 HEAD 원문과 바이트 동일함을 확인하고 수정·우회 없이 실행했다. 9.94초 exit0이며 직전 전체 XML 지문 `3e7e6b89d94d4d028db5f8fa3d9d952665bc4fbd040104a893368ee1b14ea834`가 전후 동일하다. 이를 전체 테스트의 새로운 fresh 실행으로 집계하지 않는다. guard 종료 상태와 지문은 `local-only-evidence/analysis-original-guard-summary.json`에 별도로 기록한다. 실제 모델 키 세 개의 값을 출력하지 않고 변경 파일110개와 비교해 일치0, 범위 밖 변경0, 로컬 원본 로그의 gitignore 제외를 확인했다. 근거는 `local-only-evidence/analysis-secret-scan.json`이다. 이 검증 후 로컬 commit만 생성하며 원격 push·PR·main/develop 병합·운영 실행은 하지 않는다. commit hash는 Git 이력에서 확인한다.

실제 모델 평가는 이 로컬 gate와 분리된 후속 단계다. 사전 고정된 개발/봉인 모임과 유한한 물리 호출 상한을 사용하며 성공할 때까지 반복하지 않는다. 코드 수정자는 봉인 개별 정답을 열지 않는다. 실제 평가의 실행 SHA·설정·호출·사용량·실패 및 제한은 별도 결과로 보고해야 한다.
