# 읽기 전용 reconcile의 실패 단계 보존

관련 Issue: #190. 기존 배포의 UNKNOWN 원인 확인·복구나 V2 활성화 완료를 뜻하지 않는다. PR #189의 factory/격리 전송 도구와 독립된 진단 변경이다.

## 문제와 경계

기존 reconcile은 준비 메타데이터·origin·archive/package·journal 관측 중 예외가 나면 일반 exit 2만 남긴다. plan/report가 없는 상태에서 최종 job이 artifact를 내려받으려 해 원래 관측 실패와 artifact 누락이 섞인다. 기존 명령의 Failed를 정상 조회한 보고서와 진단 절차 자체의 실패를 구별해야 한다.

진단은 기존 event/context·publication·origin·archive/package·identity 검증을 유지한다. 이미 존재하는 journal·owner·명령 상태와 규칙을 읽는 범위만 사용하며 새 배포, command 재전송, owner claim/release, journal 저장, 규칙 변경이나 설정 변경 권한을 추가하지 않는다. 원 운영 실패가 불확실하면 계속 불확실로 남기고 실제 flag·DB·runtime을 관측하지 않았는데 확인 완료라고 표시하지 않는다.

## 진단 실패 계약

별도 `reconcile-diagnostic.json`과 `MEETME_RECONCILE_DIAGNOSTIC_V1` 표지는 고정 protocol, 형식 검증된 operationId 또는 잘못된 입력의 null, `diagnosticComplete=false`, 허용된 stage와 reason만 포함한다. 예외 원문·임의 클래스·응답·키·환경변수·traceback은 포함하지 않는다. 실제 외부 권한 거절을 확인하지 못했다면 오류 문구만으로 ACCESS_DENIED라고 추정하지 않는다.

최종 검사는 검증된 진단 실패를 plan 조회보다 먼저 읽어 동일한 정제 실패를 표시하고 실패 상태를 유지한다. 알려지지 않은 필드·단계·원인, operation 불일치, 진단 완료 위조는 거부한다. 기존 정상 reconciliation-v1의 필드와 성공 배포 completion 계약은 바꾸지 않는다. 진단 artifact는 배포 완료 증명이 아니며 owner나 작업을 해제하지 않는다.

## 검증 기록

독립 테스트 설계, 실제 준비 경로의 RED→GREEN, 개인정보·무변경 반례, 의도적 결함 주입과 원본 전체 guard 결과는 최종 소스로 확인한 뒤 기록한다. 외부 AWS/GitHub 서비스 응답의 합성 대역 검증은 실제 운영 journal 접근·모델 품질·운영 활성화 성공과 구별한다.

이번 승인 범위는 로컬 구현·무료 검증·feature push·develop Draft PR와 정확 HEAD 일반 CI까지다. main/develop 병합, workflow dispatch, 운영 배포·복구, 유료 모델/지도 호출과 권한 변경은 수행하지 않는다. 운영에서 새 진단 코드를 실행하려면 실제 승격 및 workflow 자동 부작용을 확인한 별도 승인이 필요하다.

독립 검증은 실제 driver 진입점·동적 controller·prepare_release·reconcile·completion을 연결하고 publication/origin/archive/package/public-contract/identity/journal 검증을 실행했다. 외부 서비스 응답만 합성 대역이다. 최초 테스트 10개에서 예상 assertion/subtest 실패 23건·설정 오류 0을 확인했고, 원자 파일 발행 경계를 실제로 거부하도록 fixture를 정정한 뒤 동일 기대값과 최종 지문으로 baseline RED를 다시 확인했다. 제품의 원자 발행을 없애거나 기대값을 완화하지 않았다. 최종 집중10 PASS, 기존 issue154 전체277 PASS이며 독립 검토는 baseline10 PASS·의미 있는 결함8/8 탐지·복원10 PASS·소스/테스트/workflow4개 지문 일치다. 상세 정제 근거는 `.tdd/red/issue190-readonly-reconcile-diagnostics.json`과 `.tdd/verification/issue190-reconcile-diagnostics.json`을 따른다.

진단 artifact 저장 자체가 불가능한 경우에는 `REPORT_PUBLICATION` 정제 marker와 실패 상태만 남을 수 있다. 이 물리적 한계를 artifact 생성 성공이나 정상 관측으로 표시하지 않는다. 실제 운영 관측·복구는 이번 무료 합성 검증과 별개다.

## 확대 검사 중 확인한 기존 검사와 환경 한계

첫 원본 전체 guard는 2,702건 중 2,684 PASS·기존17 SKIP·1 FAIL로 종료했다. 실패는 변경하지 않은 `PrecheckRuntimeInputGateTransportTest`의 RI16 한 매개변수에서 CONFIGURATION 거절 후 JVM 전역 ProxySelector 기록이 비어 있지 않았다는 단언이다. 원 URI/thread가 보존되지 않아 제품 결함이나 백그라운드 간섭을 확정하지 못했다. 관련 Kotlin 제품·테스트는 기준 커밋과 바이트 동일하다. 기존 클래스만 소스 변경 없이 집중 실행한 결과는 통과했다. 독립 검토 후 다른 자체 검사와 병행하지 않고 원본 전체 guard를 딱 한 번 더 확인하며, 재실패 시 반복하지 않는다. 최초 실패·집중 통과·최종 결과를 별도 보존하고 원인 규명·제품 수리로 확대하지 않는다. 단언·필터·timeout·제외는 변경하지 않았다.

기존 배포 계약의 현재 workflow 하위 명령20개 중 1–19는 로컬 통과했다. 마지막 Nginx 검사는 관리 환경의 비공개 체크아웃 파일 모드와 컨테이너 기본 프록시 때문에 로컬에서 통과하지 못했다. 공개 설정·기존 검사 스크립트의 동일 바이트만 정상 읽기 모드의 임시 검증 공간에 복사하면 Nginx는 기동·listen했지만 기존 localhost wget 검사에는 프록시403이 발생했다. 운영 설정·비밀·보안/네트워크 설정이나 검사 코드를 바꾸지 않았다. 이 로컬 제한을 통과로 기록하지 않고, 정확한 feature SHA의 일반 CI에서 기존 Nginx 검사를 그대로 확인한다.

무변경 검증의 0회 단언은 이번 준비 오류와 기존 SSM Failed 관측 사례에 적용한다. 기존 SSM Success를 대조하는 경로는 원래의 읽기 전용 host probe를 사용할 수 있으며, 이를 원래 배포 명령 재전송이나 운영 복구로 해석하지 않는다. 성공 관측의 기존 권한·host/image/history 검사도 이번 변경으로 늘리거나 생략하지 않았다.

## 최종 로컬 결과와 게시 시점

원본 guard의 두 번째이자 마지막 전체 확인은 ktlintCheck·assemble·test EXIT0, 346 suites·2,702건 중 **2,685 PASS·기존17 SKIP·실패/오류0**, 1,017.304초였다. 실행 중 Kotlin/Python/workflow/shell·관련 테스트와 원본 guard 지문은 불변이었다. 최초1FAIL과 최종0FAIL의 전체 XML·로그는 별도 로컬 보존했고 skip17개 이름은 동일했다. 기존 RI16 실패 원인을 해결했다고 주장하지 않는다.

로컬 배포 계약은19명령 통과·Nginx1명령 환경 제한으로 구분한다. 정확 HEAD 일반 CI의 같은 Nginx 검사·나머지 필수 결과는 feature 게시 후 별도로 확인한다. CI를 통과시키려고 검사·권한·trigger·단언·timeout·재시도를 바꾸지 않았다. Commit/PR은 동일 커밋 예정이며 실제 커밋과 CI의 기준은 Git/PR 이력이다.
