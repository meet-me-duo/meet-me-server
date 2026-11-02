# 실패한 automatic operation의 명시적 종결

Issue #192 / ADR-069. 이 절차는 실패 배포의 기록을 보존하고 다음 배포의 사전 안전 검사를 지원한다. 배포 성공이나 V2 활성화 절차가 아니다. 구현·무료 검증·Draft PR 승인과 실제 운영 실행 승인은 구분한다.

## 지원 범위와 보존

원 journal이 `meet-me-automatic-operation-v1`, `UNKNOWN / RECOVERY_REQUIRED`, `rulesState=UNTOUCHED`이고 원 commandId가 있는 경우만 지원한다. 원 SSM 명령은 정확한 인스턴스에서 terminal `Failed`, 원 GitHub Deploy workflow의 정확한 run/attempt는 `completed`여야 한다. 취소·timeout·전송 여부 불명, transition operation, 다른 image가 실행 중인 실패는 이 최소 절차의 지원 범위가 아니다.

원 journal·sequence·commandId·immutable identity·send claim은 변경하거나 삭제하지 않는다. `APPLIED`로 바꾸거나 `NOT_ATTEMPTED`로 되돌리지 않는다. 같은 namespace에 불변 `.closure-intent.json`과 `.closure.json`을 추가하고, 검토한 원 owner만 조건부 fence·해제한다. 최종 sidecar의 `FAILED_CLOSED`는 실패 종결이며 `deploymentComplete=false`다.

## 승인 전 준비 — 실행하지 않는 검토 단계

1. 검토할 코드의 정확 HEAD와 테스트/CI를 확인한다. 새 검사기를 실제 사용하는 control plane이 이 sidecar를 이해해야 다음 배포를 허용할 수 있다. 이전 main 검사기는 owner를 해제해도 UNKNOWN을 계속 차단한다.
2. 기존 AWS profile만 선택하고 계정·서울 리전·인스턴스·bucket·원 operation·build run/attempt를 명시한다. 새 IAM/credential·보안 설정은 만들지 않는다.
3. 무료 로컬 준비로 JDK17에서 `javac --release 17 -d deploy/preflight/compiled deploy/preflight/ReadOnlyFlywayProbe.java`를 실행한다. 기존 읽기 전용 전체 Flyway probe를 컴파일하는 단계이며 운영에 연결하지 않는다. plan을 저장할 `.codex/tdd-evidence` 디렉터리도 미리 생성한다.
4. 아래 plan 실행도 실제 S3/SSM/GitHub 읽기와 새 읽기 전용 host probe를 수행하므로 별도 운영 읽기 승인 후에만 실행한다. 현재 PR 준비에서는 실행하지 않는다.

```text
python deploy/pipeline/run-approved-release.py --failed-closure-plan --profile <existing-profile> --account-id <12-digit-account> --region ap-northeast-2 --instance-id <exact-instance> --bucket <exact-meet-me-artifact-bucket> --build-run-id <original-build-run> --build-run-attempt <original-build-attempt> --operation-id <original-64-hex-operation> --plan-file .codex/tdd-evidence/closure-plan.json
```

plan은 S3 원본 snapshot과 ETag, 원 identity, 계정/namespace, 원 manifest 지문 및 검증된 baseline 증명을 묶는다. 전체 DB history의 version/script/checksum/success, source/build/image·schema/guard 지문, trusted READY와 앱 healthy, 정확한 두 규칙의 원래 상태·target binding, 활성 명령 0을 확인한다. 원 package의 출처·archive SHA·정책/guard catalog도 검증한다. 단순 healthy 또는 현재 디렉터리 시각만으로 원 실패 분기를 증명하지 않는다.

plan 생성은 운영 메타데이터를 쓰지 않는다. stdout에는 operationId·approvalDigest와 완료 false만 표시한다. 로컬 plan 파일에는 owner의 opaque holder와 ETag 등 내부 복구 메타데이터가 있으므로 채팅·Git·PR artifact에 올리지 않는다. 자격증명·환경 전체·사용자 DB 내용은 plan에 포함하지 않는다.

## 별도 명시 승인 후 apply

승인 대상은 검토한 HEAD, 정확한 target/operation과 **plan의 approvalDigest**, 원 journal 보존, sidecar 두 개의 생성과 해당 owner의 fence·해제다. 재배포·restart·migrate·규칙 변경·flag 변경은 포함하지 않는다.

```text
python deploy/pipeline/run-approved-release.py --failed-closure-apply --profile <existing-profile> --plan-file .codex/tdd-evidence/closure-plan.json --approve-digest <reviewed-exact-64-hex-digest>
```

apply는 승인 snapshot을 새로운 관측으로 조용히 바꾸지 않는다. 원 workflow 종료와 Failed·활성 명령 0·전체 baseline을 재검증하고, 동일한 intent를 `If-None-Match:*`로 기록한다. 원 owner ETag의 `If-Match`로 plan에 묶인 recovery holder를 설정해 구 writer의 소유권을 fence한다. journal/ETag와 전체 증명을 다시 검증한 뒤 불변 closure를 기록하고, 다시 확인한 owner ETag로 해제한다. 최종 owner는 recovery holder를 유지한다.

각 쓰기의 응답 유실은 실제 미실행으로 취급하지 않고 정확한 저장 결과를 재조회한다. 저장된 값·snapshot이 정확히 일치할 때만 다음 단계로 진행한다. 부분 상태에서는 다음 배포가 계속 차단되며 같은 승인 plan만 명시 재시도할 수 있다. 같은 값을 재기록해도 ETag가 바뀌면 동시 변경으로 거부한다. 객체의 JSON `false/0`, `true/1`, 정수/실수도 구별한다.

`outcome=CLOSED`, `ownerReleased=true`는 fresh 증명과 안정된 최종 snapshot까지 확인한 경우다. exit2 또는 `INCOMPLETE / ownerReleased=false`는 검증된 종결이 없다는 뜻이며 실제 쓰기 미실행을 보장하지 않는다. 즉시 읽기 재조회로 상태를 확인하고, 다른 plan 생성·owner 삭제·무조건 재쓰기·새 배포를 하지 않는다.

## 중단과 되돌림 범위

identity·manifest·command/workflow·history/guard/image·규칙 불일치, 활성 명령, owner/journal/ETag 변경, 읽기 실패 또는 조건부 충돌이면 중단한다. 원 실패 기록과 이미 기록한 intent/fence/closure를 남긴다. 자동 rollback·이전 holder 복원·closure 삭제는 하지 않는다. 부분 상태를 직접 지우는 대신 같은 plan의 검증된 재시도 또는 별도 검토를 요청한다. baseline을 증명하지 못하면 잠금이 남는 것이 의도한 안전 동작이다.

이후 prior-operation 검사는 원 immutable manifest/package를 다시 열고 전체 현재 baseline과 sidecar를 검증한다. 다음 후보 manifest의 지문을 원 실패 manifest와 혼동하지 않는다. 이 새 closure 경로는 읽기 전용 전체 host probe와 원 Failed 명령 조회를 할 수 있다. 기존 journal-only inspection의 새 SSM 없음 계약은 기존 경로에 유지한다. `safeToProceed`는 다음 작업의 사전 안전 판정이며 실패한 배포의 성공 판정이 아니다.

## 기존 release 재적용 안내 정정

이미 존재하거나 symlink인 release 경로는 wrapper가 계속 거절하고 기존 파일을 보존한다. 같은 immutable build를 다시 선택하면 같은 releaseId이므로 재시도 해결책이 아니다. wrapper는 `MEETME_DEPLOY_WRAPPER_FAILURE_V1`의 허용된 stage/reason만 남기고 subprocess stdout/stderr·예외 원문·환경 값은 내보내지 않는다. 과거 무출력 exit2의 원인을 새 marker가 소급 증명하지 않는다.

정식 실패 종결과 검사기 호환 확인 후, 별도 승인된 새 immutable publication/build의 새 releaseId로 정상 배포한다. 활성 release 디렉터리를 삭제해 거절을 우회하지 않는다. 일반 배포의 원래 보호 승인·schema/guard 비교·전체 완료 검증을 유지한다. V2 설정·실제 서비스 흐름·유료 모델 검증은 각각 후속 승인과 증거가 필요하다.
