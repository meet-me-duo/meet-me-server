# Issue #107·#109 소유권 통합 수용 기준과 독립 검증 설계

작성일: 2026-10-17 UTC. 작업 위치: `/workspace/meet-me-server-107-integration`. 부모가 전달한 통합 기준은 #109 `c06` 위에 #107 `d459`를 no-commit 적용한 상태다. 정확한 최종 commit SHA는 통합 receipt에서 고정한다. 이 문서는 공개 계약과 기존 fixture를 읽은 독립 테스트 설계이며 구현 완료·실행 결과가 아니다.

## 계약과 통합 경계

근거는 사용자 #107 지시, `ISSUE_107_CONTRACT.md`, `ISSUE_109_CONTRACT.md`, `ISSUE_109_HANDOFF.md`, PRD FR-007A/008D/008E/009, ADR-046/049와 `.agents/rules/development.md`의 독립 테스트·리뷰 원칙이다. 이번 단계는 이미 확정된 공통 계정/게스트 소유권 경계를 #107에 연결한다. 인증·토큰·guest proof를 새로 구현하지 않는다.

실제 production binder는 `ClarificationOwnerPort.requireOwner(roomId, rawGuestCredential)`를 `ParticipantOwnershipResolver.resolve(roomId, rawGuestCredential, OwnershipOperation.READ_OWN)`에 위임해야 한다. 질문 GET과 답변 POST 모두 동일 READ_OWN 계약이다. 답변은 선택지·현재 source revision의 본인 소유 증명을 요구하므로 기존 제출 PUT의 REPLACE_OWN으로 낮추지 않는다. REPLACE_ONLY는 원문/질문·선택지를 읽는 권한으로 승격할 수 없다.

초안 설계 시점에는 통합 main adapter가 없었다. 최신 구현·실앱·전체 gate 결과는 이 문서 마지막 최종 검토 절을 따른다. #107 독립 기능 작업의 과거 BLOCKED는 실제 binder/wiring·기동·HTTP 검증이 완료될 때만 해소할 수 있다. interface와 합성 대역·mock delegate3개의 이전 GREEN만으로 해소하지 않는다.

## 공개 수용 기준

| ID | 입력·행동 | 필요한 공개 결과와 불변식 |
| --- | --- | --- |
| INT-01 | production Spring context 및 패키징 | test 전용 adapter·mock CurrentAccountPort 없이 실제 ClarificationOwnerPort bean이 정확히1개이고 실제 공통 resolver를 사용한다. plain/boot JAR에 합성 adapter가 없으며 앱이 실제 HTTP health와 본인 질문 API를 처리한다. |
| INT-02 | 검증된 Bearer 계정이 현재 방 participant를 소유 | guest cookie 없이 본인 질문 GET·답변 POST 가능. account user_id로 동일 participant ID를 찾으며 방/role/제출/기존 provenance를 보존한다. |
| INT-03 | 유효한 계정과 다른 사람의 유효 guest cookie가 함께 있음 | 계정 우선. 계정이 방에 참여하지 않았으면403 PARTICIPANT_REQUIRED이며 guest로 fallback하지 않는다. 계정이 소유한 다른 participant가 있으면 그 본인 데이터만 조회한다. |
| INT-04 | malformed/invalid/expired/중복 Authorization와 유효 guest cookie | HTTP401 AUTH_ACCESS_INVALID. guest fallback·질문 노출·새 revision·provenance·work 변화가 없다. 실제 RequestCurrentAccountAdapter/interceptor를 거친다. |
| INT-05 | CurrentAccountPort가 AuthFailure/불가 예외를 던짐 | 예외를 null로 삼키지 않고 기존401/503 계약을 전파한다. binder에 임의 guest fallback이나 새 인증 실패 변환이 없다. |
| INT-06 | 계정없음·VERIFIED_GUEST proof | 같은 방의 여전히 guest-owned participant 본인만 질문 GET·답변 POST 가능. rawCredential 없는 검증된 request context proof가 제공돼도 공통 port 계약을 그대로 소비한다. |
| INT-07 | REPLACE_ONLY proof | GET 및 POST 모두403 GUEST_PROOF_INSUFFICIENT. A/B/DIRECT/CANCEL 모두 동일하고 기존 제출 PUT 교체 권한이 보완 질문 읽기·답변 권한을 만들지 않는다. |
| INT-08 | proof room/participant 불일치·DB상 account-owned로 변경 | 공통 resolver 거부. 이름·초대링크·이전 guest credential로 소유권 복구/추측 없음. 질문/원문/선택지/사유가 오류 body에도 없다. |
| INT-09 | guest 기록의 명시적 account claim 성공 전후 | 같은 participant UUID·제출·source question을 유지한다. 이후 새 account가 본인 질문을 읽고 답할 수 있으며 옛 guest proof는 거부한다. 로그인만으로 자동 claim하지 않는다. |
| INT-10 | 같은 방의 다른 계정/다른 참가자/HOST가 타인 question ID 사용 | 타인 raw text·question/options·ambiguity reason·answer가 비공개. HOST 권한은 타인 답변 권한이 아니다. 오류·room·미반영 응답도 함께 검사한다. |
| INT-11 | 계정 HOST가 기존 수정 라운드를 열고 account 또는 VERIFIED_GUEST 본인이 답변 | 기존 OPEN·source/current revision·round CAS·정확 Origin을 지켜 새 immutable revision과 provenance를 원자 저장한다. 저장으로 분석/Outbox/provider 호출은0이다. |
| INT-12 | 비로그인 guest가 HOST 명령·회원 nonHOST가 HOST 명령 | 각각 기존401 ACCOUNT_REQUIRED/403 HOST_PERMISSION_REQUIRED. 본인 보완 권한을 방 재분석 권한으로 올리지 않는다. |
| INT-13 | 계정 HOST의 명시 analysis | 답변 version+나머지 frozen cohort의 정확 version으로 새 batch/run/Outbox를1회 생성한다. 60초/최대4+1·수정분석3회·재사용·멱등성 규칙 유지. |
| INT-14 | stale source/round/revision, 같은 request 재시도·다른 payload·동시답변 | 기존409/CAS·replay·원자 rollback·새로고침 복원 기대값을 유지한다. 권한 전환 후 replay에도 현재 본인 증명을 먼저 검증한다. |
| INT-15 | A/B/DIRECT/CANCEL, 무응답·여전히 모호한 답변 | 원문·답변 lineage 보존. 취소/무응답/여전히 모호함을 임의 성공으로 승격하거나 반복 질문을 재생하지 않는다. REPLACE_ONLY 거부는 선택별로 우회할 수 없다. |
| INT-16 | native schema와 웹 연결 | 최종 생성 OpenAPI에 기존 clarification·account/guest 주요 오류가 일치한다. 계정 세션/guest proof 전환 시 본인 질문 cache와 늦은 응답 차단을 실제 HTTP 연계로 확인한다. |

구체적 denial status는 기존 공통 계약을 따른다. 질문 ID 유출을 막기 위한 generic conflict와 account 참여권한 거부를 혼동해 기대값을 완화하지 않는다. 기존 guest-only feature 오류와 공통 resolver의 코드가 다르면 #109 확정 계약을 기준으로 어떤 요청 준비가 바뀌었는지 기록한다.

## 기존 합성 fixture와 바꿔야 할 준비 범위

읽은 경계는 `CorrectionRoundPostgresFixture`, `AccountPrincipalTestFixture`, `SyntheticClarificationOwnershipAdapter`, #107 HTTP/lifecycle/owner-delegation tests 및 공통 resolver/proof adapter다. 구현자에게 다음 준비 변경만 전달했고 이 설계자가 main/test 파일을 수정하지 않았다.

| 파일·준비 | 현재 차이 | 기대값을 유지하는 변경 범위 |
| --- | --- | --- |
| `CorrectionRoundPostgresFixture.completedRoom()` | #109 base가 기본 HOST/MEMBER를 account-owned로 만들며 AccountPrincipalTestFixture의 cookie marker로 mock CurrentAccountPort를 제공한다. #107의 guest-only synthetic owner는 이 marker를 실제 guest session으로 찾지 못한다. | production binder를 공통 resolver에 연결한다. 기존 account test helper를 쓰는 사업 흐름 검증은 유지하되 실제 Bearer HTTP 검증과 명확히 구분한다. |
| `SyntheticClarificationOwnershipAdapter` | test source의 전역 @Component guest-only 대역이다. 실 binder 추가 뒤 bean 경쟁 또는 실제 binder 우회가 발생할 수 있다. | 통합 suite에서 이 대역을 제거하거나 명시적으로 격리한다. test 대역을 @Primary로 만들어 실 binder를 숨기지 않는다. production JAR에 복사하지 않는다. |
| `ClarificationLifecyclePostgresTest.largeRoom()` | 무인증 POST /rooms와 응답 guest Set-Cookie를 HOST로 취급하는 옛 준비가 남아 있다. #109 신규 생성은 로그인 필수다. | `accounts.newAccount("host")` 등 확정된 account fixture로 HOST 생성·세션을 준비한다. 21명·Unicode 합계10000/10001·답변 A/DIRECT·writes0·rollback·provenance 기대값은 유지한다. |
| 기존 completedRoom 호출 | 기본 memberAccount=true라 guest proof가 실제로 검증되는 표본이 사라질 수 있다. | VERIFIED_GUEST coverage는 `completedRoom(memberAccount=false)` 등 명시 guest member 준비로 별도 유지한다. 모든 case를 account mock으로만 바꿔 통과시키지 않는다. |
| `ClarificationOwnerDelegationPostgresTest` | mock feature port로 위임 이후 business behavior만 검증한다. | 기존3개는 단위 경계 회귀로 유지 가능하나 actual resolver/binder·Bearer·REPLACE_ONLY 검증의 대체로 표시하지 않는다. |
| HOST 명령/기존 직접 service 호출 | #109는 HOST_COMMAND에 계정 HOST를 요구한다. guest HOST로 준비했던 옛 테스트가 setup에서 실패할 수 있다. | HOST 생성/CurrentAccount context만 정확히 준비한다. HOST 전용·Origin·멱등/CAS·원문비공개 assertions는 완화하지 않는다. |

AccountPrincipalTestFixture의 `test-account-<UUID>` cookie marker는 mock CurrentAccountPort를 위한 합성 장치다. 실제 production cookie가 account 인증을 대체한다는 계약이 아니며 이 fixture를 쓰는 MockMvc 통과를 real Bearer 인증 성공으로 보고하면 안 된다. #108 실제 request proof adapter는 이번 범위에 없다. 공통 scoped proof fake와 #108 실제 adapter 결합 성공도 구분한다.

## 실행 설계와 증거

1. **작은 binder 계약 RED/GREEN:** 실제 ParticipantOwnershipResolver+repository/proof/current account test doubles로 account 우선, 예외 전파, VERIFIED_GUEST, REPLACE_ONLY 거부를 검증한다. feature adapter가 READ_OWN을 사용하지 않으면 실패하는 사례를 고정한다. production binder 구현은 root가 담당한다.
2. **실제 PostgreSQL HTTP 통합:** 실제 binder/resolver와 DB를 사용한다. business fixture의 CurrentAccount mock으로 account cohort와 guest cohort를 각각 준비하고 기존16+10+3 회귀를 수행한다. REPLACE_ONLY/request-context proof는 공통 port fake만 교체하되 binder를 대체하지 않는다. snapshot·batch·version·question provenance·work counts로 거부와 성공을 검증한다.
3. **실앱 기동·Bearer HTTP:** test-only ownership adapter 및 mock CurrentAccountPort 없이 production Spring wiring을 띄운다. 일회성 PostgreSQL/Redis, 격리 RS256 키와 AccessTokenPort를 사용하고 real RequestCurrentAccountAdapter/interceptor를 통과한다. 기존 #109 loopback OIDC/JWKS harness를 재사용할 수 있으며 실제 공급자 login/운영 자격 증명은 사용하지 않는다.
4. **완결 흐름:** account HOST 생성→account/guest MEMBER 참여·저장→명시 close→합성 구조화 결과로 본인 질문 저장→HOST reopen→본인 GET/A 또는 DIRECT POST→새로고침 GET→저장 후 work0→HOST 명시 analysis→정확 frozen version/Outbox1 확인. 실제 모델 없이 구조화 결과를 seed한 부분을 기록하며 모델 의미 정확성의 증거로 삼지 않는다.
5. **경합·권한 전환:** 기존 답변/분석/일반 PUT 경합, rollback·replay·claim 후 old guest 거부를 실제 DB 경계에서 확인한다. provider/60초 예산 회귀와 최근 `7-8시쯤`8개도 유지한다.
6. **의도적 결함 주입:** READ_OWN을 REPLACE_OWN으로 낮춘 mutant, CurrentAccount 예외를 null로 삼킨 mutant, account 미참여 때 guest fallback한 mutant, 실 binder 대신 synthetic bean을 사용하는 mutant를 각각 탐지하는 검증을 준비한다. 원본 정확 복원과 source/test 지문을 남긴다.
7. **최종 gate·독립 리뷰:** 최신 통합 source fingerprint로 `ktlintCheck`, `assemble`, `test`와 mandatory guard, schema export·JAR test대역 부재를 확인한다. 기존 #107 standalone727개나 #109 standalone773개를 통합 전체 GREEN으로 재사용하지 않는다. binder 작성 뒤 독립 최종 리뷰로 필요한 동작과 실제 bean 선택을 확인한다.

RED는 컴파일·bean 충돌·데이터 준비 오류가 아닌 새 binder 요구 동작 부재로 실패해야 한다. 초기 startup bean 오류나 로그인 필수 setup 오류는 별도 환경/fixture 실패로 기록한다. 기대값 정정은 설계 역할이 기준 계약과 정확한 diff를 설명한 뒤 수행하며 이전 FAIL을 보존한다.

## 완료 판정과 미검증 경계

통합 완료에는 실제 production binder·단일 bean 선택·test대역 미포함 패키징, 계정/VERIFIED_GUEST/REPLACE_ONLY/invalidBearer의 실제 검증, 기존 보완 기능·공통 소유권 회귀 및 최종 통합 gate 근거가 필요하다. 실제 앱 기동과 native HTTP를 확인하기 전에는 과거 production-startup BLOCKED를 해소했다고 표시하지 않는다.

이번 설계 작성은 읽기와 문서만 수행했다. Gradle·앱·실모델·운영·비용 발생 호출을 실행하지 않았다. 운영 OAuth·운영 image·migration·배포, #108 실제 proof adapter, 모델의 시간 의미 정확성은 격리 통합 검증과 별개다. 실제 API token·cookie·원문은 로그·문서·receipt에 기록하지 않는다. 후속 승인된 게시도 제품·운영 완료를 뜻하지 않는다.

## 새 실제 JWT HTTP suite의 독립 사전 검토

대상은 `ClarificationAccountOwnershipPostgresTest.kt` 초안6개다. 이 검토는 읽기만 수행했으며 root가 formatter/RED를 진행 중인 시점이다. 새로운 기대값은 테스트 작성자에게 요청했으며 검토자가 테스트를 고치지 않았다.

확인한 적합한 경계:

- 기존 `CorrectionRoundPostgresFixture`와 합성 account cookie를 상속하지 않고, 일회성 RSA2048 키/JWKS와 실제 AccessTokenPort 발급·RequestCurrentAccountAdapter·공통 resolver를 사용한다. CurrentAccountPort/ClarificationOwnerPort를 mock하지 않는다.
- 계정 cookie 없는 조회·새 revision 답변, 기존 source 보존, 저장만으로 work0, HOST 명시 분석의 batch/run/Outbox1 및 frozen version 보존을 검증한다.
- outsider 계정+유효 guest cookie는 account 우선으로403이며 HOST도 다른 guest 질문을 빌릴 수 없다. 잘못된 Bearer+유효 guest cookie는401 AUTH_ACCESS_INVALID로 실패하고 개인정보·writes/work가 없다.
- REPLACE_ONLY만 GuestOwnershipProofPort spy로 주입하고 나머지 guest credential/claim/JWT 경로는 실제 구현을 사용한다. 이는 #108 실제 약한 proof 발급 경로를 검증하는 근거가 아니다.
- explicit claim은 기존 participant UUID·source version·question ID를 보존하고 새 account에게 본인 권한을 주며 옛 guest 권한을 거부한다.
- provider port는 비용 차단 목적으로 mock하고 `verifyNoInteractions`로0호출을 검증한다. source 구조화 결과를 seed하는 부분은 공급자 의미 정확성 검증이 아니다.

보강 요청 INT-R1: REPLACE_ONLY 답변 거부에 **CANCEL을 포함**해야 한다. 초안은 POST A만 보낸다. 잘못된 binder가 REPLACE_OWN을 쓰면 A 저장 이후 응답 복원 `GetOwnSubmissionUseCase`의 READ_OWN에서403이 발생해 전체 rollback될 수 있어, 겉의403·쓰기0만으로 최초 binder 검사를 증명하지 못한다. CANCEL은 후속 제출 저장/원문 GET 없이 처리되므로 최초 보완 권한 자체가 READ_OWN인지 검사한다. 가능하면 DIRECT도 같은 거부 표에 포함하되 기존 원문/version/work 불변 assertion은 유지한다. 이 요청은 테스트 오라클 약화가 아닌 공개 권한 계약의 탐지력 보강이다.

보강 요청 INT-R2: VERIFIED_GUEST 자체의 답변 성공 표본이 필요하다. 초안의 guest 성공은 조회 후 claim을 거쳐 account가 답하는 흐름이다. 별도 guest A 또는 DIRECT 성공으로 새 immutable revision·source/provenance·work0을 확인해야 약한 proof를 막으면서 정상 강한 guest의 쓰기까지 막는 회귀를 탐지할 수 있다. 기존 독립107 guest-only test 대역 통과로 실제 공통 resolver의 guest 답변 성공을 대신하지 않는다.

위 두 요청은 테스트 작성자와 root에게 전달했고, 반영·RED/GREEN의 최신 상태는 아래 재검토 및 최종 검토 절에 기록했다. 초안에는 계정 토큰/게스트 cookie를 assertion failure·로그에 출력하는 검증 코드를 추가하지 않는다. 실제 패키지 앱 기동은 root의 별도 smoke 근거가 필요하며 @SpringBootTest 성공만으로 production JAR 단일 bean wiring 완료를 선언하지 않는다.

## INT-R1·R2 보강 반영 재검토

테스트 작성자의 후속7개 suite를 읽어 두 보강이 반영됨을 확인했다. REPLACE_ONLY 거부 요청 표에는 기존 A뿐 아니라 CANCEL·DIRECT가 포함되고 모두403 GUEST_PROOF_INSUFFICIENT 및 versions/work 불변·비공개를 요구한다. 추가된 VERIFIED_GUEST 성공 method는 A/DIRECT를 각각200·revision2·source/saved version provenance·원본불변·질문종료·work0/provider0으로 검증한다. INT-R1/INT-R2의 **설계 누락은 해소**되었다.

검토 시 formatter 후 테스트 SHA-256은 `9419a6607938502f76e17ef6b76fb2cd737957e8b8a192ae60a1d47564b7f762`였다. 작성자가 전달한 formatter 전 지문과 다르므로 최종 RED/GREEN은 root가 실제 고정 지문으로 기록해야 한다. 이 재검토에서는 JDK/Gradle 또는 테스트·production 변경을 실행하지 않았다. 당시에는 binder·패키지 앱 smoke가 필요했으며, 이후 확인한 실제 근거와 차단 해소는 아래 최종 검토 절에 기록한다.

## 실제 binder·패키지 앱 독립 최종 읽기 검토

최신 `SharedClarificationOwnerAdapter`는 `@Component` 하나로 `ParticipantOwnershipResolver.resolve(..., READ_OWN)`에 그대로 위임한다. 별도 guest 인증·account parsing·예외 삼킴·fallback이 없다. 조회와 답변이 동일 feature port를 소비하므로 보완 답변에도 READ_OWN을 유지한다. 검토한 source SHA-256은 `efe458893c9c9f27c94adcdee28e64ad645f263a8e1e2bf475756562960f0b5e`다.

`/workspace/shared/issue107/integration-runtime.json`의 격리 실앱 PASS 근거를 읽었다. 현재 boot JAR의 SHA-256을 직접 계산해 `605f7a003b31a9deba8553e73d483914df9a7459535703efcafce37ab5867a6e`와 일치함을 확인했고 ZIP을 열어 실제 adapter class 존재·합성 adapter/테스트 class 부재를 재확인했다. root smoke는 test classpath 없이 일회성 PostgreSQL·RSA로 실제 HTTP health200, 계정 JWT 본인200/타인403/invalidBearer401, 답변 revision2/provenance/source보존/replay·저장 work0, HOST 명시 analysis의 정확1개 batch/run/Outbox 증가를 기록한다. AI invocation/attempt는 전후0이다. 이 검토자는 앱을 다시 실행하지 않았다.

따라서 초기에 적었던 **실제 main binder 부재·격리 production-classpath 앱 기동 차단은 해소되었다.** 이는 운영 배포·실제 OAuth 공급자 활성화·#108 실제 weak-proof 발급 통합을 완료했다는 의미가 아니다. standalone 과거 문서의 BLOCKED는 당시 이력이고 현재 통합 상태는 이 근거를 기준으로 판단한다.

`/workspace/shared/issue107/integration-account-fault.json`은 GET을 READ_OWN으로 유지한 채 POST만 REPLACE_OWN으로 낮춘 mutant를 검증한다. 의도대로 GET 성공 단계에서 먼저 실패하지 않고 **CANCEL 응답200(기대403)** 행동 실패1건으로 탐지했다. XML은1test/1failure/0error이며 원본 정확 복원·테스트 지문 불변을 기록한다. 현재 binder SHA도 해당 복원 기록과 일치한다. INT-R1의 보강이 실제 POST 권한 하향을 검출한 증거다.

root의 focused71개 및 mutant 복원 후 account7개+native1개 재실행 성공 로그를 확인했다. 이후 최종 전체 통합 서버 gate도 완료되었다. `/workspace/shared/issue107/integration-server-final-gate.json`은 ktlintCheck·assemble·test exit0/666.094초를 기록하며, 실제 XML을 직접 재집계해844건 중840 PASS·기존4 SKIP·실패/오류0과 account7/7 PASS를 확인했다. 이는 이전 standalone727개 또는 #109773개와 합산한 추정이 아닌 별도 통합 전체 실행 결과다.

마이그레이션12개(V1~V11,V13)/latest13, V11+V13 catalog CI entry8개 PASS를 확인했다. `issue-107-integration-fixture-migration.json`과 published feature 대비 diff에서 largeRoom의 account HOST 준비만 바뀌고 기존21명·10000/10001 Unicode·원문/revision/provenance/work assertions가 유지됨을 확인했다. 새 binder/source/test/현재bootJAR 지문도 위 최종 근거와 일치한다. 자세한 독립 판정은 `docs/verification/issue-107-account-integration-review.md`를 따른다.

서버 통합 검토 범위의 미해결 지적은 없다. mandatory guard·원격 exact head CI·웹 browser 최종 통합 receipt는 부모 인계 자료에서 별도로 확인하며 운영 OAuth·#108 실제 proof adapter·운영 배포·실모델 의미 정확성의 한계는 유지한다. 이 재검토에서는 docs만 수정했고 소스·테스트·기대값·Gradle·FI를 변경하거나 실행하지 않았다.

## 후속 통합 게시 범위

로컬 검증 완료 후 사용자가 별도 Draft 게시와 정확 HEAD CI 확인을 승인했다. 실제 코드의 분기 기준은 #109 서버 c06b60a, 웹95f09c0다. 서버 CI는 develop/main 대상 PR에서만 실행하므로 통합 Draft의 PR base는 develop3232128, 웹main9b18397로 두고 #109 포함 이력과 그 이후의 #107 delta를 명시한다. 기존 독립 PR116/27은 유지하며 강제 갱신/병합하지 않는다. 이 문서의 이전 '로컬 미게시' 표시는 당시 단계의 기록이다. Commit/PR 결과는 게시 후 별도 receipt를 따른다.
