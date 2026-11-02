# 실패 작업 종결의 독립 계약 검증

## 범위와 역할

Issue #192의 실패 작업 종결 엔진을 구현 담당과 분리해 검증했다. 테스트 작성자는 승인된 요구사항, ADR-063의 실패 보존·소유권·완료 경계와 기존 운영 계약으로 기대값을 먼저 고정했다. 새 구현의 반환값으로 기대 레코드를 만들지 않았다.

이 기록은 무료 합성 계약 검증이다. AWS·SSM·운영 DB·GitHub 쓰기나 모델·지도 호출은 하지 않았다. 실제 운영 종결·배포 완료를 뜻하지 않는다. 어댑터·CLI·기존 safety checker 연결의 최종 검토와 전체 gate는 별도 검증 기록 및 PR의 정확 HEAD 결과를 따른다.

## 고정한 불변식

- 원래 automatic-operation journal, immutable identity와 실패 commandId를 그대로 보존한다. `UNKNOWN`을 `NOT_ATTEMPTED`로 바꾸거나 실패를 `APPLIED`로 표시하지 않는다.
- 계획은 읽기 전용이다. 정확한 target·manifest·원 journal/owner 내용과 ETag·전체 baseline 증명을 digest로 묶고, 적용에는 그 계획의 명시적 approval digest를 요구한다.
- 원 명령은 `Failed`, 원 workflow의 정확 run/attempt는 `completed`, 활성 명령은 정수 `0`이어야 한다. healthy 상태 하나만으로 통과하지 않는다.
- 현재 source/release/build/image와 trusted guard·전체 history·schema fingerprint 및 두 규칙이 승인한 증명과 정확히 같아야 한다.
- immutable intent → 조건부 recovery owner fence → fresh 재검증 → immutable `FAILED_CLOSED` sidecar → fresh 재검증 → 조건부 owner 해제를 순서대로 진행한다. 새 recovery holder를 최종 owner에도 보존해 구 runner의 소유권이 돌아오지 않게 한다.
- 응답 유실은 쓰기 실패의 증거가 아니다. exact readback으로 확인하지 못하면 추가 쓰기 없이 `INCOMPLETE`를 반환한다. 같은 계획의 재시도는 이미 보존된 객체를 덮어쓰거나 배포를 다시 제출하지 않는다.
- closure가 존재해도 owner 미해제·원 journal 변경·증명 변경이면 종결로 인정하지 않는다. 모든 결과의 `deploymentComplete`는 literal `false`다.

## RED와 GREEN 근거

독립 테스트는 `deploy/tests/test_issue154_failed_operation_closure.py`에 있다. 기존 `test_issue154*.py` CI discovery에 포함된다.

| 단계 | 대상과 결과 |
| --- | --- |
| 최초 요구사항 RED | untouched develop 기준의 엔진에 동일한 독립 테스트를 임시 복사해 실행: 24 tests, 89 assertion failures, import/문법 오류 없음. 미지원 public closure API가 실패 이유다. |
| 최초 GREEN | 구현 엔진에서 같은 24 tests 통과. 최초 실제 실행은 이미 구현이 반영된 시점이어서 이를 RED로 표시하지 않았다. |
| 추가 엄격 타입 RED | 기존 24개 기대값은 유지하고 2개 테스트 추가: 26 tests, persisted numeric alias 5개가 잘못 `CLOSED`가 되어 실패. |
| 엄격 타입 수정 GREEN | canonical JSON 비교로 변경한 엔진에서 확장한 26 tests 통과. 테스트 기대값을 약화하지 않았다. |

명령은 `python -X utf8 -m unittest discover -s deploy/tests -p test_issue154_failed_operation_closure.py -v`다. 최초 RED는 동일 경로 구조의 임시 디렉터리에 untouched 기준 파일과 동결한 테스트를 복사해 실행했다. 작업 중인 구현 파일은 되돌리거나 수정하지 않았다. 첫 임시 디렉터리 설정 실패는 테스트 RED 근거에서 제외했다.

- 최초 테스트 SHA-256: `736cf8d93d24eda4b2c6c1d37a0d9fa98e169049584035490a6da8b512682e11`
- 확장 동결 테스트 SHA-256(LF 정규화): `fd9545c6f1cb87e4c3132cc14e922af29ef40fe2600213247f5ef01daba30120`
- untouched RED source SHA-256: `eb66d2a96e330f1e864fdd4d1f68565b846584e2b40dcead064c73b0b1778a10`
- alias RED source SHA-256: `4888ef335957a99dc85828c71ad64ea9e0f320482df708afd159a0700602ece4`

전체 raw 테스트 출력은 Git에서 제외한 `.codex/tdd-evidence/failed-operation-closure-*.log`에 로컬로만 보존한다. 공개 기록에는 합성 입력·실패 분류·지문·집계만 남긴다.

별도 branch 준비의 Git line-ending 처리로 파일이 CRLF가 된 시점의 raw 지문은 `9e2aca2b11713cd46aa2cd8b0cb42b6531d7086bddda9b9ae0f42b7a4d5a4ff1`이었다. LF 정규화 지문은 위 값과 같아 테스트 내용·기대값 변경과 구분했다.

## 독립 검토에서 발견한 결함

Python 객체 비교는 `False == 0`, `True == 1`, `4 == 4.0`을 참으로 취급한다. 최초 구현의 구조 비교 때문에 다음 다섯 persisted 레코드가 승인된 레코드와 같은 것으로 처리됐다.

1. closure의 `deploymentComplete=0`
2. 현재 owner의 `released=1`
3. 현재 journal의 `sequence=4.0`
4. intent에 보존된 original owner의 `released=0`
5. intent에 보존된 original journal의 `sequence=4.0`

독립 반례를 먼저 RED로 기록한 뒤 구현자가 canonical JSON 바이트 비교로 수정했다. 원 계획을 reseal해도 부적절한 원 타입은 거절한다. 레코드의 JSON 타입까지 같은지 검사하며, 숫자 alias를 정상 terminal 상태로 승격하지 않는다.

## 의도적 결함 주입

동결한 테스트와 정상 구현을 허용된 scratch 안의 임시 복사본에 두고 한 번에 결함 하나씩 주입했다. live 작업 파일은 변경하지 않았다. 정상 control은 26 tests 통과했고 아래 12개 결함은 모두 assertion failure로 탐지됐다. 원본 테스트 지문은 전후 동일했다.

| 결함 | 탐지된 assertion failures |
| --- | ---: |
| exact approval 확인 제거 | 1 |
| 원 SSM Failed 요구 제거 | 9 |
| 원 workflow terminal 요구 제거 | 1 |
| 활성 명령 0 검사 제거 | 5 |
| trusted guard 요구 제거 | 2 |
| 전체 history 일치 요구 제거 | 2 |
| canonical JSON 비교를 Python 객체 비교로 회귀 | 5 |
| journal 내용·ETag snapshot 검증 제거 | 3 |
| recovery fence에서 기존 holder 재사용 | 16 |
| closure commit 뒤 fresh proof 재검증 제거 | 1 |
| 승인 계획 digest 무결성 검사 제거 | 2 |
| 승인 당시와 다른 유효 baseline proof 수용 | 1 |

FI control에 사용한 UTF-8/LF source 지문은 `125fdcddedd3b5471d1471d93585557eacc29606e7af2749c4189ddb4e50c692`다. control 및 각 mutant의 지문·종료 코드·집계는 로컬 `failed-operation-closure-fi-summary.json`에 남겼다. 이는 이 엔진 계약의 결함 탐지 근거이며 시스템 전체의 결함 부재나 실제 분산 장애 시험을 뜻하지 않는다.

동일한 12개 엔진 mutant를 실행하는 재현 가능한 gate를 `deploy/tests/test_issue154_failed_closure_fi.py`에 추가했다. 이 gate는 control이 정상 통과한 뒤 각 결함이 지정된 독립 계약의 assertion으로 실패하는지 확인한다. 문법·import 오류를 결함 탐지 성공으로 계산하지 않는다.

## 어댑터 경계의 추가 독립 검증

엔진의 동결 26개와 별도로 실제 `AwsReleaseAdapter`의 writer 메서드를 실행하는 독립 테스트 5개를 추가했다. 저장소 IO만 합성 객체 저장소로 바꾸고 실제 메서드의 승인된 레코드·현재 snapshot·대상 검사와 조건부 쓰기를 검증했다.

- 지원되는 정상 prefix에서 sidecar는 If-None-Match, owner는 각 관측 ETag의 If-Match로 기록한다.
- 직접 writer 재진입에서도 숫자/boolean alias를 저장하지 않는다.
- owner fence 없이 closure를 commit할 수 없다.
- fence 직전 original journal 내용·journal ETag·owner ETag 변경을 거절한다.
- 다른 계정 context로 intent/fence/commit/release를 쓸 수 없다.

이 5개는 이미 보완된 최신 어댑터에서 처음 실행해 GREEN이었다. 이를 과거 구현 RED로 표현하지 않는다. 추가로 임시 복사본에서 canonical 비교 회귀·owner fence 생략·journal snapshot 검사 생략·대상 context 검사 생략의 4개 mutant를 모두 지정 assertion으로 탐지했다.

1차 실행 명령 `python -X utf8 -m unittest discover -s deploy/tests -p test_issue154_failed_closure_fi.py -v`는 **7 tests GREEN**이었다. 독립 어댑터 계약 5개와 엔진/어댑터 FI gate 2개이며, 안쪽 control은 각각 동결 계약 26개와 어댑터 계약 5개를 실행한다. 총 16개 mutant를 모두 탐지하고 live source·테스트는 수정하지 않는다. 아래 최종 proof 경계의 추가 테스트로 현재 이 파일은 8개 테스트를 포함한다.

## 연결 코드의 독립 검토

- operator CLI는 plan/apply 두 모드만 별도로 처리하며, 일반 배포 실행 분기로 들어가지 않는다. plan은 새 파일을 독점 생성하고 apply는 저장된 계획의 exact digest를 요구한다. 준비 단계는 기존 계정·리전·bucket 명명과 원 build/attempt manifest·archive·CI origin을 검증한다.
- 실제 증명 adapter는 원 SSM commandId/instance/Failed/nonzero code와 정확 GitHub run/attempt의 completed 상태·저장소·main·배포 workflow를 확인한다. 실제 전체 Flyway history와 trusted source/image/build/guard/schema를 manifest와 대조한 뒤에만 축약된 proof를 만든다. 건강 상태 하나만으로 `historyMatches=true`를 만들지 않는다.
- 다음 candidate의 manifest가 달라도 inspector는 원 closure에 묶인 manifest SHA와 build/attempt archive를 다시 열어 원 baseline을 검증한다. 새 candidate의 증명을 과거 실패 종결의 증명으로 대체하지 않는다. namespace/account/region/bucket과 snapshot도 다시 대조한다.
- wrapper는 기존 release를 삭제하거나 재사용하지 않으며 기존 경로·parent 권한·download/integrity·추출·policy·하위 배포·timeout의 실패를 제한된 stage/reason으로 표시한다. subprocess의 원 stderr·예외·경로·입력값을 진단 marker에 포함하지 않는다. 기존 폴더 거절이 이번 사건의 직접 로그로 확인됐다는 결론은 만들지 않는다.

## 남은 경계

검토한 엔진·adapter writer·연결 코드에서 이 시점의 미해결 P1/P2 결함은 발견하지 못했다. 이 역할은 실제 AWS나 운영 DB로 분산 장애를 실행하지 않았다. 전체 gate·CI와 build/probe prerequisite·실행 절차의 검증은 구현 담당의 정확 최종 HEAD 기록을 함께 확인해야 한다. 새 closure inspection은 원 baseline을 재검증하는 읽기 전용 probe가 필요하므로 과거 진단의 무조건적인 no-new-probe 설명과 구분해야 한다. 실제 운영에서는 검증된 PR의 병합/배포와 실패 종결 메타데이터 적용을 별도로 승인받아야 한다.

## 게시 전 문서·diff 재검토

2026-11-02의 현재 내용 diff와 새 [실패 종결 절차](../../deploy/FAILED_OPERATION_CLOSURE.md), ADR-069, Architecture, Worklog, implementation plan을 다시 읽었다. 지원 범위는 terminal `Failed`인 원 automatic operation과 정확한 원 baseline에 한정되고, 원 UNKNOWN journal 보존·완료 false·조건부 fence/해제·불완전 결과의 읽기 재조회·구체 승인 경계가 일치한다. 원 workflow·manifest와 새 candidate를 혼동하거나 owner만 풀어 재배포하라는 안내, 자동 rollback·기존 release 삭제·원 holder 복구 지침은 없다.

운영 읽기 plan도 별도 승인 대상임을 명시하고, JDK17의 기존 read-only Flyway probe 컴파일과 새 검사기의 control-plane 호환 확인을 선행 조건으로 적었다. 새 closure inspection의 실제 읽기 probe와 기존 journal-only 경로를 구분했다. 실패 경로 marker가 과거 사건의 정확한 분기를 소급 증명하지 않는다는 한계도 유지한다.

공개할 변경 내용·새 문서·합성 테스트·RED 요약에서 실제 운영 계정/인스턴스/실패 operation/SSM command 식별자와 원본 plan·로그를 포함하지 않았음을 확인했다. 로컬 compiled probe, 생성된 inspection 결과와 `.codex/tdd-evidence` 원본은 공개 파일 묶음에 넣지 않는다. 이 재검토에서 추가 P1/P2는 발견하지 못했다. 무료 전체 gate와 exact HEAD CI는 당시 진행 중이므로 이 문서의 독립 계약/FI 통과를 그 완료로 확대하지 않는다.

## 최종 전체 history proof 경계의 추가 반례

게시 직전 별도 반례로 실제 Flyway history는 정상 정수 checksum·literal boolean을 유지하고, `automaticContract.history`만 deep copy한 뒤 checksum을 같은 값의 실수로, `success`를 정수 `1`로 바꿨다. 실제 `AwsReleaseAdapter.closure_evidence`가 두 잘못된 계약을 `historyMatches=true`로 투영하는 것을 확인했다. 기존 helper가 실제 history 행의 타입은 검사했지만 contract history와의 비교에는 Python 객체 동등성을 썼기 때문이다.

동결 26개는 바꾸지 않고 `FailedClosureProofBoundaryContracts` 1개 테스트에 실제/contract × checksum/success의 네 subcase를 추가했다. 정상 control과 실제 history의 두 잘못된 타입은 기존대로 처리됐고, contract의 두 numeric alias가 **2 assertion failures / 오류 0**으로 RED가 됐다. 최초 클래스 배치 설정 오류는 정정하고 RED 근거에서 제외했다. RED source의 LF SHA-256은 `0fa4e7f6b1a191046d835b3558958e0ba617d4685bb0ed037b42b98b1b852404`다.

구현자는 새 closure 경로에서만 실제 전체 history와 contract 전체 history를 각각 manifest의 expected 전체 history와 canonical JSON으로 대조하도록 최소 수정했다. 역사 helper나 기존 배포의 수용 범위는 넓히지 않았다. 독립 담당이 같은 proof 테스트를 다시 실행해 **1 test GREEN / 네 subcase 통과**를 확인했다. 해당 source LF SHA-256은 `bc8dfdcb313a77b6397b36ee0e8a8cce7e93a11b6a16f0965b3b3d74911dd593`이며 동결 26개의 LF 지문은 계속 `fd9545c6f1cb87e4c3132cc14e922af29ef40fe2600213247f5ef01daba30120`이다.

구현 담당의 로컬 `issue192-closure-final-green.log`도 직접 열어 **43 tests / OK**를 확인했다. 독립 핵심 26개, writer 5개, proof 1개, FI gate 2개와 integration/CLI/wrapper 9개다. 기존 16개 mutant gate도 이 실행에 포함됐다. raw RED/GREEN은 로컬 전용으로 보존한다. 이 추가 검토의 미해결 P1/P2는 없으며, 전체 native guard·최종 HEAD 원격 CI 및 실제 운영의 검증·승인은 여전히 별도다.

## 전체 검사 후속의 테스트 계약·준비 경계 검토

원본 전체 검사에서 별도로 드러난 HTTP2 callback oracle와 PostgreSQL fixture 준비 조건을 [후속 독립 검토](issue192-http2-oracle-review.md)에 기록했다. 고정된 OkHttp 계약에 따라 물리적인 `canceledAtFailure=true`를 반드시 확인하고 기존 timeout/HTTP2/요청 수/시간 범위 단언을 유지했다. early/late callback의 두 결정적 observer 계약을 추가했으며 production Kotlin은 바꾸지 않았다. 기존 native Ubuntu/JDK17 환경의 ktlint와 두 집중 suite는 실제 XML 합계 **22 PASS / 실패·오류·skip 0**이다.

DB helper는 기존 두 준비 로그와 전체 60초 제한·동일 image·JDBC/SQL 기대값을 유지하고 실제 mapped port 대기를 더한 것으로 읽기 검토했다. 이 역할은 DB 검사를 중복 실행하지 않았다. 과거 callback 누락과 connection-refused의 정확 원인은 아직 추정이며, 이 집중 검증을 전체 guard 통과로 표시하지 않는다. 최종 전체 검사·CI는 이 테스트 변경까지 반영해야 한다.

## 게시 전 최종 검증 인계

독립 closure43개와16개 결함 검출 뒤, HTTP2의 실제취소false/unknown을 포함한5개 대조군과3개변이를7개 의도한단언으로 확인했고 DB준비조건4조합과3개 구조변이를 추가검토했다. 원본·테스트·compiledclass는변경하지않았다. [추가관측계약](issue192-http2-oracle-review.md)의 범위를넘어 과거원인을확정하지않는다. 최종원본native전체guard EXIT0(2,687PASS/기존17SKIP/실패·오류0), code/test지문유지와로컬Node환경한계를 [검증기록](issue192-validation.md)에 구분했다. 검토범위에미해결P1/P2는없으며 정확한원격HEAD필수CI와운영freshproof는별도전제다.
