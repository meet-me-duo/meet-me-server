# Issue #107 모호성 보완 계약

- 목표: 2026-10-18 KST까지 서버·웹 구현과 격리 검증. 원격 develop `3232128bb1d0886c987e378a787633bd2aee6341`, 웹 main `9b183978d5987f8fa8a5f02d542b17181e4929b4` 기준. push/PR/병합/배포/유료·운영 호출 제외.
- 결정 출처: 2026-10-17 사용자 위임. 과거 이슈의 Human Blocker와 질문 시점 TBD는 이번 지시로 해제. 내장 goal 도구 없음; 설치/설정 변경 없이 본 문서와 실행 근거로 추적.
- 독립 oracle: [테스트 설계](ISSUE_107_TEST_DESIGN.md), [fixtures](fixtures/issue-107-ambiguities.json). 자연어 fixture는 모델 평가 oracle이며 원문 정규식으로 모델의 의미 판정을 덮어쓰지 않는다.

## 제품 동작

마감 뒤 기존 방 전체 배치 응답에서만 질문을 제안한다. 완전한 문맥으로 확정되는 입력에는 질문·추가 AI 호출이 없다. 기존 결과에 영향이 있는 미해소 모호성에만 A/B/직접 입력을 제공한다. 한 버전당 질문은 최대 1개이고 질문 제안을 위한 별도 호출은 0이다. 기술 재시도/폴백은 기존 60초·최대4+1, 수정 분석은 기존 방 수명 3회다.

모호한 입력은 빈 조건과 `AMBIGUOUS_TIME_CONSTRAINT` 또는 `AMBIGUOUS_INPUT`으로 안전 제외한다. 질문을 만들 수 없는 복합 모호성은 임의의 두 선택지를 만들지 않고 미반영한다. 응답하지 않음·취소·답변 후에도 모호함은 가능 시간을 확대하거나 의미를 확정하지 않는다.

질문은 본인만 조회한다. HOST라도 타인의 모호한 원문·질문·사유·답변은 읽을 수 없다. 기존 비모호성 미반영 고지 계약은 유지한다. 서버의 본인 제출 증명과 정확 Origin 방어를 그대로 사용하며 #109 인증 구현을 중복하지 않는다.

답변은 HOST가 명시적으로 연 기존 OPEN 수정 라운드에서만 새 revision으로 저장한다. 저장·조회·재시도는 분석하지 않으며 HOST가 기존 `/analysis`로 명시적으로 분석한다. A/B는 다른 조건과 극성을 보존한 전체 수정본이다. source 버전의 기준일에 해소한 절대 날짜를 사용한다. DIRECT는 전체 대체 원문이며 새 버전 생성일이 상대 날짜의 기준이라는 설명을 제공한다.

## 서버·웹 API

`GET /api/rooms/{inviteCode}/submission/clarifications`:

```json
{"questions":[{"question_id":"uuid","source_revision":1,"excerpt":"7~9시","question":"어느 시간인가요?","option_a":"오전 7~9시 가능","option_b":"오후 7~9시 가능","editable":false}],"state_version":2}
```

현재 활성 완료 배치에 속하고 본인 최신 버전과 일치하는 미응답 질문만 반환한다. collecting 최초/분석중/지연/확정 결과에서는 질문을 새로 공개하지 않는다. OPEN 라운드가 없으면 읽기만 가능하다. 질문 id는 source version에서 안정적으로 정하고 최초 질문 본문을 고정해 강제 재분석·재전달로 바뀌지 않는다.

`POST /api/rooms/{inviteCode}/submission/clarifications/{questionId}/answer`:

```json
{"request_id":"uuid","revision_round_id":"uuid","expected_revision":1,"choice":"DIRECT","direct_text":"2026-10-18 오전 7~9시 가능"}
```

- A/B는 서버에 고정된 전체 수정본을 선택하고 direct_text를 허용하지 않는다. DIRECT만 전체 대체 원문을 받는다. CANCEL은 새 제출을 만들지 않고 질문을 닫는다.
- DIRECT는 ECMAScript trim 뒤 1~500 Unicode 코드포인트. 선택과 직접 입력의 혼합, null/빈/초과/unknown 선택은 400. 새 원문과 방 합계 10,000 검증은 기존 저장 유스케이스가 담당한다.
- 성공은 `{status: ANSWERED|CANCELLED, submission: SubmissionResponse|null}`. 동일 요청 UUID와 같은 원문 body는 라운드 종료 뒤에도 저장한 결과를 재생한다. 다른 body의 UUID 재사용, stale revision·round·질문, 타인의 question은 409 REVISION_CONFLICT. 인증401/참여권한403/방404/Origin403은 기존 계약.
- room 잠금→소유 확인→기존 재생 확인→active source/run/OPEN round/CAS→기존 SaveSubmissionUseCase→답변 출처 기록을 하나의 트랜잭션으로 묶는다. HOST 분석과 일반 수정도 같은 room 잠금을 사용한다.
- source version·최초 proposal·request/body·새 version·답변 시각을 FK로 보존한다. 같은 원문에 이미 답한 이력과 답변으로 생성된 version은 새 질문에서 제외한다. 방당 본인 질문 생성은 최대3개로 제한한다.

웹은 방+서버 viewer context별 private cache를 사용한다. 답변 입력은 메모리에만 두고 새로고침은 서버에 저장한 질문/제출로 복원한다. 실패 시 원문과 작업 UUID를 유지하고 중복 클릭을 막는다. 409는 상태 재조회 후 stale 답변을 다시 전송하지 않는다. 권한 상실·viewer 변경·방 이동 뒤 늦은 GET/POST 응답은 private UI/cache를 되살리지 않는다. 구 서버404는 미지원으로 숨긴다.

본인 context가 같아도 질문 ID·source revision·수정 라운드가 바뀌면 선택과 직접 입력·작업 UUID·오류·늦은 저장 응답의 수명을 종료한다. 새 질문/라운드는 미선택으로 시작하고 이전 질문의 답변 의도를 전용하지 않는다. 같은 질문·같은 라운드 재조회는 진행 중인 입력을 유지한다.

## 변경 경계와 검증

`GeminiBatchProcessor.kt`, 관측 계층, `SubmissionService.kt`에는 통합 변경을 하지 않는다. provider 공통 계약·구조화 저장 어댑터가 질문을 보존하고 별도 clarification application/ports/web/persistence가 기존 저장 유스케이스를 조합한다. 새 Flyway FK는 보존기간 정리와 함께 검증한다.

독립 테스트 설계/테스트 작성→유효 RED→최소 구현→관련 GREEN→의도적 결함 주입→전체 관련 게이트→독립 리뷰 순서로 진행한다. 실제 모델의 의미 정확성·비용·지연은 무호출 mock/격리 검증으로 증명하지 않는다. 전체 완료는 구현/검증/한계를 구분하여 최종 근거에 기록한다.

## #109 통합 차단과 소유권 포트 (최종 로컬 경계)

독립 게시 #107 d459968의 `ClarificationOwnerPort`에는 main binding이 없어 단독 기동할 수 없다. 이 별도 #109 c06b60a 기반 통합 브랜치는 `SharedClarificationOwnerAdapter`가 공통 `ParticipantOwnershipResolver.resolve(..., OwnershipOperation.READ_OWN)`으로 GET과 답변을 위임한다. JWT/Bearer·게스트/계정 선택·fallback을 복제하지 않으며 예외를 그대로 전파한다. 테스트 전용 `SyntheticClarificationOwnershipAdapter`는 통합 브랜치에서 제거했다. 실제 JWT·PostgreSQL HTTP, test classpath 없는 production JAR 기동 및 답변→HOST 명시 분석은 로컬 격리 환경에서 통과했으며 전체 통합 게이트 결과는 별도 검증 기록을 따른다. 운영 OAuth 로그인·배포·실제 모델 의미 검증은 이 결과에 포함하지 않는다.

연결할 공통 계약은 `account.domain.AccountPrincipal(accountId: UUID, displayName: String)`와 `account.application.port.output.CurrentAccountPort.currentAccount(): AccountPrincipal?`다. Authorization 없는 null과 유효 계정을 구분하고 잘못된 Bearer는401, 유효 guest cookie가 있어도 guest로 fallback하지 않아야 한다. Participant의 guest/user 배타 소유, room+user lookup·claim은 #109만 수정한다. 기존 SaveSubmissionUseCase/GetOwnSubmissionUseCase의 계정 지원도 #109 통합 후 함께 확인해야 한다.

V11은 #109, V12는 다른 세션, #107은 V13으로 예약했다. `RoomLifecycleDtos.kt`에는 지원 표지3줄만 추가한 로컬 통합 패치가 있다. #109의 최신 파일 전체를 이 기준 파일로 덮어쓰지 말고 그 작은 hunk만 합쳐야 한다. GeminiBatchProcessor.kt·SubmissionService.kt·관측 계층은 기준 commit과 동일하다. 웹 OpenAPI/schema도 #107 격리 서버의 native 스냅샷이므로 다른 기능을 합친 서버에서 다시 생성한다.

## 후속 게시 승인 (2026-10-17 UTC)

사용자가 최종 테스트와 독립 리뷰를 통과한 범위의 commit·push→Draft PR→정확한 HEAD CI 확인을 추가 승인했다. 초기 원격 쓰기 제외는 이 범위에서 해제한다. #107 독립 구현의 격리 검증과 #109 실제 통합 대기를 PR에 구분하고, 제품 완료나 프로덕션 기동 완료로 표현하지 않는다. merge·auto-merge·main 배포·운영 DB·Secret 변경·운영/유료 모델 호출은 계속 제외한다. feature push/PR의 배포 비발생, 원격 최신 상태·중복 PR과 비밀값 미포함을 확인한 뒤 게시한다.
