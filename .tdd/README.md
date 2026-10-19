# TDD RED evidence

프로덕션 Kotlin 변경의 RED 근거는 Git에 추적하는 요약과 로컬에만 보관하는 원본 실행
로그로 나눈다. 요약 계약은 [`schema/red-evidence.schema.json`](schema/red-evidence.schema.json)을
따른다.

## 생성

1. 작업 Issue와 TDD 주기 하나를 나타내는 소문자 `workItem`을 정한다. 한 Issue에서 테스트
   묶음이 여러 번 독립적으로 RED가 되면 `issue-6-first-cycle`처럼 서로 다른 식별자를 쓴다.
2. 테스트나 Skeleton을 추가하기 전 기준 커밋의 전체 object ID를 `sourceRevision`에 기록한다.
3. 가장 작은 관련 테스트를 실행하고, 요구한 동작이 아직 없어서 예상한 테스트가 실패했는지
   확인한다. 컴파일, 설정, 의존성이나 테스트 데이터 오류는 RED 근거가 아니다.
4. 전체 출력은 `.codex/tdd-evidence/<workItem>.log`에 저장한다. 비밀정보나 개인정보가 출력에
   포함됐다면 로그를 보존하지 말고 노출 원인을 먼저 제거한 뒤 다시 실행한다.
5. 실제로 실행한 Kotlin 테스트 파일마다 저장소 기준 상대 경로와 소문자 SHA-256을 기록하고,
   실행 명령은 셸 문자열이 아닌 인자 배열로 기록한다.
6. 요약을 `.tdd/red/<workItem>.json`에 저장한다. 원본 출력이나 사용자·비밀정보는 요약에
   넣지 않는다.

## 무효화와 재확인

- RED 이후 테스트 파일의 내용이 바뀌면 기존 요약은 무효다. 변경된 테스트로 RED를 다시
  확인하고 요약의 SHA-256, 실패 테스트, 명령과 기록 시각을 새 결과로 교체한다.
- 테스트의 입력·기대값·검증 논리가 같더라도 파일 지문이 달라졌다면 예외 없이 재확인한다.
- RED가 컴파일·설정·의존성·테스트 데이터 준비 오류에서 발생했다면 해당 요약과 원본 로그를
  폐기하고 올바른 실패 지점으로 다시 실행한다.
- `sourceRevision`은 RED를 시작한 기준점을 유지한다. GREEN 구현 커밋이 추가됐다는 이유로
  기준점을 갱신하지 않는다.

## 보존

- 유효한 요약 JSON은 구현과 함께 커밋하고 병합 후에도 저장소에 유지하여 코드 리뷰와 변경
  이력에서 확인할 수 있게 한다.
- 원본 로그는 로컬 commit guard 검증에만 사용하고 Git에 추가하지 않는다. 작업 브랜치가
  병합되고 후속 검증이 끝나면 로컬 로그는 삭제할 수 있다.
- 구현이 되돌려져 요약이 더 이상 현재 코드의 근거가 아니어도 과거 Git 이력은 보존한다.
  현재 트리에서 제거할 필요가 있으면 별도 정리 변경으로 삭제 이유를 남긴다.

### 과거 fixture·실행 사본의 Git 복구

과거 `.tdd/history/` 사본 639개는 현재 코드·CI·테스트의 실행 입력이 아니므로 현재 트리에서
제거했다. 전체 원본은 [고정 Git 이력](https://github.com/meet-me-duo/meet-me-server/tree/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history)에 보존한다.
유효한 `red/`, `verification/` 요약과 현재 `evaluation/` 실행기·필수 fixture는 유지한다.
이 정리는 과거 실패, fixture 교정, 원래 기대값과 판정을 현재 결과로 대체하지 않는다.

남아 있는 증거 JSON·과거 기록·주석의 `.tdd/history/...` 경로는 위 커밋의 Git object 경로로
해석한다. 원래 JSON의 경로·SHA·`sourceRevision`·판정은 그대로이며 현재 파일의 존재나 현재
소스와의 일치를 뜻하지 않는다. `7ab660ad7c1ca3553a63013df0d61ef1c07c668b`는 보관본의 커밋이지
각 실행의 `sourceRevision`을 바꾸는 값이 아니다. 미게시 승인 대기 자료에도 같은 보관 위치를
적용하며 그 원문은 수정하지 않는다.

개별 원본 열람: `git show 7ab660ad7c1ca3553a63013df0d61ef1c07c668b:.tdd/history/<경로>`.
전체 복구: `git archive 7ab660ad7c1ca3553a63013df0d61ef1c07c668b -- .tdd/history`를 빈 임시
디렉터리에 추출한다. 복구한 파일의 SHA를 원래 요약과 대조할 수 있다. 실행을 재현할 때는
기록에 지정된 source revision·도구·runtime을 별도 작업 사본에서 함께 복구한다. 현재 구현과
과거 사본을 섞은 새 실행을 과거 실행 증거로 표시하지 않는다. 새 임시 산출물은 기존 로컬
전용 경로에 두며 history 사본을 현재 트리에 다시 복제하지 않는다.

## 고위험 결함 주입 검증

트랜잭션·동시성·보안·외부 Adapter처럼 PIT로 충분히 검증할 수 없는 경계는 구현을 복구한
최종 source 지문과 의도적으로 주입한 결함, 이를 탐지한 테스트를
`verification/<workItem>.json`에 기록한다. 계약은
[`schema/fault-injection-evidence.schema.json`](schema/fault-injection-evidence.schema.json)을 따른다.
전체 실행 출력은 RED 로그와 동일하게 `.codex/tdd-evidence/`에만 보관하고 Git에는 넣지 않는다.
