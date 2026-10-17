# ‘7-8시쯤’ 제보와 비교 범위

2026-10-17 사용자 제보의 원문 표현은 **‘7-8시쯤’**이다. 오전·오후가 없는 이 표현을 근거 없이 한쪽 시각으로 확정하지 않는다. ‘쯤’의 허용 오차나 역전된 범위의 밤샘·자동 교환도 새로 발명하지 않는다. 의미 oracle과 전체 대조표는 `docs/fixtures/issue-107-ambiguities.json`, `docs/ISSUE_107_TEST_DESIGN.md`를 따른다.

## 확인한 기존 소스

- 조회 시 원격 서버 main은 `91eb2505ddf6b4db549a5bc2b8b07f11e514d0b0`, 작업 기준 develop은 `3232128bb1d0886c987e378a787633bd2aee6341`이다. 원격 main이 실제 실행 중인 운영 image의 SHA라는 확인은 하지 않았다.
- main의 `NaturalLanguageProviderContract.kt` SHA-256은 `24c165bfbf25057c29c89be29fad6a90df504024fe13255360efc32d0a46ebf8`이다. 256행은 모호한 시간 경계를 발명하지 말라고 하고, 265행은 명시 AM/PM·24시간제 우선, 280행은 충돌 문맥의 모호성 유지, 283행은 시간 경계를 정할 수 없을 때 빈 conditions와 `AMBIGUOUS_TIME_CONSTRAINT`를 요구한다.
- 기존 reader도 유효한 모호성 rejection 자체를 `INVALID_RESPONSE` 예외로 바꾸지는 않는다. 반면 잘못된 JSON·input_ref·허용되지 않은 필드 등 wire 계약 위반은 `INVALID_RESPONSE`다. ‘7-8시쯤’의 당시 실제 공급자 응답과 로그는 없으므로 사용자가 본 오류의 구체 원인은 확정할 수 없다.
- 기존 응답에는 `clarification`이 없고 본인 A/B/직접 입력 질문·답변 revision API도 없다. 기존 웹 main `9b183978d5987f8fa8a5f02d542b17181e4929b4`의 조건 placeholder는 `예: 화요일과 목요일 저녁, 봉천역 근처면 좋아요. 비대면도 가능해요.`이고 명시적인 오전·오후 상시 안내는 없다.

## 이번 변경으로 확인하는 차이

기존 배치 안에서 모호성 rejection과 안전한 본인 질문을 함께 보존하고, 모호함을 공급자 기술 오류·재시도·추가 호출 사유로 만들지 않는다. 명확한 오전/저녁·24시간제와 유일한 문맥은 추가 질문 없이 기존 구조화 결과를 사용한다. 오전·오후가 없는 원문은 안전한 A/B 또는 본인 직접 입력으로 확인하며, 원문과 답변·새 revision 관계를 보존한다. 답변도 HOST의 명시 재분석 전에는 분석하지 않는다.

입력창에는 오전·오후와 24시간제의 짧은 상시 안내, `내일 오후 7~8시` 예시를 제공한다. 비대면 예시는 장소 선택을 요구하지 않고, 대면/병행 예시는 장소 선호를 함께 표현한다. 같은 안내를 본인 보완 답변의 전체 직접 입력에도 제공한다.

fixture와 synthetic provider 회귀는 전달된 모호성 사유·질문·명확 조건·날짜와 안전 제외를 검증한다. 실제 모델이 이 표현을 항상 정확히 해석한다는 증거나 과거 운영 오류의 재현은 아니다. 실제 운영 AI·비용 발생 모델 호출·운영 로그/DB 조회는 수행하지 않았다. #109 실제 소유권 adapter와 프로덕션 기동 차단도 별개로 남아 있다.


## 독립 대조 검토와 추가 회귀

독립 검토자가 원격 main `91eb2505ddf6b4db549a5bc2b8b07f11e514d0b0`의 provider 계약 바이트를 `git show`로 읽어 위 SHA-256과256/265/280/283행 지시를 재확인했다. 이 근거는 **기존 소스에도 모호성 안전 처리 지시가 있었다**는 비교다. 당시 운영 image·설정·공급자 원응답·오류 로그를 확인한 근거가 아니며, 제보된 오류를 기존 parser의 특정 결함으로 단정하지 않는다.

- 기존25개 fixture에 T01~T19를 추가해 총44개 분류 oracle과 기존7개 답변 시나리오를 유지한다. 실제 문자열 `7-8시쯤`을 그대로 포함하며 `7~8시`, 명시 오전/저녁/24시간제, 오늘/내일/명시 다음주 월요일, 자정, 역전 범위, 불가, 문맥·선호 차이를 대조한다.
- 모든 CLEAR oracle은 질문0·추가 AI0이다. 모호성은 기술 파싱 실패를 뜻하지 않으며 원문을 보존한 채 안전 제외한다. 본인 질문의 안전한 전체 A/B가 없으면 대안을 발명하지 않는다. `쯤`에는 새로운 분 단위 허용오차를 만들지 않는다.
- `ClarificationReportedTimeRegressionTest`의8개는 양 공급자에 **합성 응답**을 공급하여 모호성 사유·proposal, 명확 조건의 null 질문·시각, 불가 극성, source 날짜와 자정, 역전·동일·불가능 시각의 안전 거부를 검사한다. `쯤`이 들어간 A/B 보존은 해당 근사 경계까지 해결되었다는 주장이 아니다.
- `/tmp/issue107-reported-time-green.log`의 BUILD SUCCESSFUL22초와 해당 JUnit XML의8tests/0failures/0errors/0skips를 직접 확인했다. formatter 후 테스트 SHA-256은 `cab9f8d38d627418eb32aa6d4c2d0ba7192ce9cdc737c6ea8cc6a81651806399`로 고정 지문과 같다.
- 추가8개를 포함한 최종 전체 서버 gate 완료: `.codex/tdd-evidence/issue-107-final-gate.json`의 wrapper513.22초/guard10.443초 모두 exit0이며 원본 로그의 BUILD SUCCESSFUL을 확인했다. 최종 JUnit XML을 직접 재집계해727건 중723 PASS·기존4 SKIP·실패/오류0, Clarification 관련64 PASS를 확인했다. 기존719개 gate는 보강 전 성공 이력으로 유지하고 합산하지 않는다. 두 JAR SHA와 기존 FI source/test9개 지문도 일치한다. #109 실제 ownership adapter·production 기동은 여전히 BLOCKED다.

이 회귀는 실제 모델이 `7-8시쯤`을 언제나 같은 의미로 분류하거나 명확한 입력에서 질문을 만들지 않는다는 semantic proof가 아니다. 이번 보강에서 main parser/matcher와 기존15/12 테스트를 변경하지 않았고, 기존 서버 FI6개의 source/test 지문도 유지한다. 실제 운영 오류 재현·근본 원인 확정·production 계정 통합은 남아 있다.
