# Issue #107·#109 실제 소유권 통합 독립 검토

- 검토일: 2026-10-17 UTC.
- 작업 위치: `/workspace/meet-me-server-107-integration`, `feature/issue-107-account-integration`.
- 기준: #109 `c06b60a9833ae77e5fce9cfef5719ab2f37470ff`와 #107 `d459` 통합. 정확한 최종 source/commit 및 원격 CI는 부모의 통합 receipt에 연결한다.
- 역할: 공개 계약으로 새 HTTP 테스트 7개를 먼저 작성한 뒤, 요청받은 최종 검토에서 실제 binder와 공통 인증·소유권 소스를 읽었다. 검토자는 production 코드·기존 테스트의 기대값을 변경하지 않았으며 Gradle·FI·앱 기동을 실행하지 않았다.
- 근거: 사용자 #107/#109 통합 지시, `docs/ISSUE_107_109_INTEGRATION.md`, PRD FR-007A/008E/008F/009, ADR-046/049, 공통 READ_OWN·REPLACE_ONLY·계정 우선·invalid Bearer 거부 계약.

## 소스 검토 판정

현재 검토 범위에서 새로 확정한 결함은 없다. `SharedClarificationOwnerAdapter`는 `ClarificationOwnerPort`를 구현하고, `ParticipantOwnershipResolver.resolve(roomId, rawGuestCredential, OwnershipOperation.READ_OWN)`으로만 위임한다. adapter가 cookie를 필수로 요구하거나 account/guest 선택·JWT 처리·fallback을 복제하지 않는다. GET과 POST 모두 같은 본인 원문 열람 수준의 증명을 요구한다.

`ClarificationService`는 GET에서 private 제출·구조화 결과·질문을 읽기 전에, POST에서 question 소유자·replay·source/round/CAS 검증 전에 이 port를 호출한다. 답변 저장의 기존 `SaveSubmissionUseCase`는 REPLACE_OWN을 사용하지만 그것이 보완 답변의 선행 READ_OWN 검사를 대신하지 않는다. 특히 CANCEL은 후속 제출 저장이나 원문 GET이 없으므로 선행 검사 자체가 강한 본인 증명을 요구해야 한다. 현재 binder가 이를 충족한다.

실제 `RequestCurrentAccountAdapter`는 Authorization가 없을 때만 null을 반환한다. malformed/중복 header, 너무 긴 token 및 JWT 검증 실패를 인증 오류로 전파한다. 실제 RS256 codec은 서명·kid·issuer·audience·발급/만료·최대15분 수명을 검사한다. 공통 resolver는 유효 계정이 있으면 room+user participant만 사용하고 그 조회 실패를 guest cookie로 보완하지 않는다. 계정이 없을 때만 guest proof를 확인하며 REPLACE_ONLY는 READ_OWN을 얻을 수 없다. 이 변경은 해당 공통 구현을 수정하지 않는다.

실제 guest cookie adapter는 digest·유효기간·회수 여부와 room participant를 확인해 VERIFIED_GUEST를 반환한다. 명시적 claim은 공통 서비스의 room 잠금·이중 증명·계정 충돌 검사를 거쳐 기존 participant owner만 바꾼다. #107의 source version과 question provenance는 participant ID를 유지하므로 claim으로 새 원문이나 별도 질문을 만들 필요가 없다.

## 독립 테스트와 기대값 보존

파일은 `ClarificationAccountOwnershipPostgresTest.kt`다. formatter 뒤 고정 SHA-256은 `9419a6607938502f76e17ef6b76fb2cd737957e8b8a192ae60a1d47564b7f762`다. 원문·권한·HTTP status·revision·provenance·work 개수의 기대값을 구현에 맞춰 낮추지 않았다.

이 suite는 `@MockitoBean CurrentAccountPort`를 가진 기존 `CorrectionRoundPostgresFixture`를 상속하지 않는다. 실제 `AccountAuthConfiguration`에 일회성 RSA2048 private key·공개 JWKS를 임시 디렉터리로 제공하고 실제 AccessTokenPort 발급·JWT 검증·request account adapter·공통 resolver·ClarificationService·PostgreSQL을 거친다. key는 합성 테스트에서 생성하고 종료 시 임시 파일을 삭제한다. OAuth/Google·운영 token·실제 secret은 사용하지 않는다.

| 검증 | 고정 기대와 불변식 |
| --- | --- |
| cookie 없는 실제 계정 | 질문/본인 제출 GET, A 답변 200·새 revision2·old source 보존. 답변만으로 work0. HOST 명시 analysis와 재전송은 batch/run/Outbox 각각1개, attempt/invocation0이며 정확한 frozen version을 고정 |
| outsider 계정+유효 guest cookie | GET/POST403 PARTICIPANT_REQUIRED. guest fallback·개인 원문/질문/선택지/사유 노출·head/version/work 변경 없음 |
| HOST 계정+다른 guest cookie | 본인 질문은 빈 배열. 타인 question POST409 REVISION_CONFLICT. HOST 권한으로 타인 답변 불가 |
| invalid Bearer+유효 guest cookie | 질문/본인 제출 GET 및 POST401 AUTH_ACCESS_INVALID. fallback·개인정보 노출·version/work 변경 없음 |
| VERIFIED_GUEST와 REPLACE_ONLY | 실제 verified guest의 질문/원문 GET 허용. 약한 proof는 양 GET 및 A/CANCEL/DIRECT POST403 GUEST_PROOF_INSUFFICIENT, version/work 불변 |
| VERIFIED_GUEST 답변 성공 | A와 DIRECT 각각200·revision2·source/saved-version/choice provenance, source 원문 불변, work0·미응답 질문 재노출 없음 |
| 명시적 claim 전후 | participant UUID·source version·revision1·question UUID 보존. 이후 계정 cookie 없는 조회/답변 허용, 옛 guest cookie 거부. 새 revision2 및 동일 participant 보존 |

두 AI provider port만 비용 차단용 mock으로 대체하고 모든 사례에서 `verifyNoInteractions`로0호출을 확인한다. 질문은 구조화 저장소 port에 합성 결과를 넣어 준비한다. 따라서 자연어를 실제 모델이 잘 해석했는지를 평가하지 않는다.

REPLACE_ONLY는 이 작업에 실제 약한 proof 발급 경로가 없으므로 GuestOwnershipProofPort spy에서 해당 scope만 주입한다. 이때도 실제 resolver가 거부하며 CurrentAccountPort/ClarificationOwnerPort는 mock하지 않는다. 이는 #108 실제 proof adapter·만료·서명 검증 성공의 근거가 아니다.

초안 6개에서 독립 설계 검토를 받아 CANCEL 거부와 VERIFIED_GUEST 쓰기 성공을 보강했다. 최종 7개를 고정하기 전에 발생한 nullable List 컴파일 오류는 `.map { requireNotNull(it) }`로 준비만 수정했으며 유효 RED로 취급하지 않았다. 이후 formatter 외 기대값 변경은 없다. 현재 약한 proof 사례에는 A뿐 아니라 CANCEL과 DIRECT도 포함된다.

## RED와 후속 실행 상태

`.tdd/red/issue-107-account-integration.json`과 `.codex/tdd-evidence/issue-107-account-integration.log`를 읽어, 실제 binder 추가 전 7개 중5개가 assertion으로 실패하고 오류는0인 유효 RED를 확인했다. cookie 없는 계정의401, 유효 계정과 guest cookie를 잘못 혼합한200, HOST에게 타인 질문 노출, REPLACE_ONLY의200 및 claim 뒤 계정 접근 실패를 검출했다. 이 상태에서 production binder를 위의 한 줄 위임으로 추가하고 test 전역 `SyntheticClarificationOwnershipAdapter`를 삭제했다.

최종 통합 서버 gate는 `/workspace/shared/issue107/integration-server-final-gate.json`에서 exit0·666.094초다. 명령은 `/tmp/meetme-run-gradle ktlintCheck assemble test --no-daemon --max-workers=2`다. 실제 `build/test-results/test/TEST-*.xml`을 직접 재집계해844건 중840 PASS·기존4 SKIP·실패/오류0을 확인했다. 새 account7개도7/7 PASS이고 formatter 후 고정 지문은 그대로다. `/tmp/issue107-integration-native-green.log`의 복원 후 account/native 실행 성공과 `/tmp/issue107-integration-final-gate.log`의 전체 실행 성공을 확인했다.

`/workspace/shared/issue107/integration-account-fault.json`과 원본 XML을 읽어 POST만 REPLACE_OWN으로 낮추고 GET은 READ_OWN 대조군으로 유지한 FI가 **CANCEL200(기대403)** assertion으로 실패1/오류0을 탐지한 것을 확인했다. 전역 GET 실패를 CANCEL 탐지로 오표시하지 않았다. source exact restoration 및 테스트 불변을 기록하며 현재 adapter SHA-256 `efe458893c9c9f27c94adcdee28e64ad645f263a8e1e2bf475756562960f0b5e`와 테스트 SHA가 기록과 일치한다.

## 이전 차단의 해소 범위와 남은 증거

과거 #107 독립 브랜치의 R2였던 실제 adapter 부재·격리 production-classpath 앱 기동 차단은 해소되었다. `SharedClarificationOwnerAdapter`를 제공하고 전역 synthetic adapter를 제거했으며, `/workspace/shared/issue107/integration-runtime.json`의 별도 패키지 앱 smoke가 PASS다. 현재 boot JAR SHA-256을 직접 계산해 `605f7a003b31a9deba8553e73d483914df9a7459535703efcafce37ab5867a6e`와 동일함을 확인하고 ZIP에서 실제 binder 포함·synthetic owner/test class 부재를 확인했다.

실앱 smoke는 test classpath 없이 실제 HTTP health200, JWT 본인200/타인403/invalidBearer401, 답변 새 revision·provenance·source보존/replay, 저장만으로 work0, HOST 명시 분석의 batch/run/Outbox 각각1회 증가를 확인한다. invocation/attempt는 전후0이며 실제 OAuth/AI 호출은 없다. 이는 @SpringBootTest만으로 기동을 추정한 결과가 아니다.

`docs/verification/issue-107-integration-fixture-migration.json`과 published #107 source `d459968b0e89e25b48eb1987acafa62e19d7e4b5` 대비 실제 diff를 읽었다. `ClarificationLifecyclePostgresTest.largeRoom` 변경은 account HOST 준비·생성 요청 cookie·준비한 HOST credential 보존뿐이다. 21명/10000·10001 Unicode 경계, reject/status·원문/revision/provenance/work 기대값은 변경하지 않았다. integration fixture SHA `7e0e7431c12ff002add892ebc7f00eafbfa2fc1f67bb2868989b540391a15788`가 기록과 일치한다. 기존 standalone RED는 역사로 보존하고 현재 지문에 새 TDD 근거를 연결한다.

현재 source migration은 V1~V11 및 V13의12개이며 latest13이다. `/tmp/issue107-integration-release-pipeline-green.log`에서 V11+V13 실제 catalog를 사용하는 기존 CI entry8개 PASS를 확인했다. 이미 공유한 V1~V10 checksum을 바꾸거나 migration gap을 임의 채우지 않는다.

독립 최종 판정: 통합 binder·공통 소유권의 검토 범위에서 미해결 코드/테스트 기대값 지적은 없다. 실제 source/test 지문·844개 전체 gate·POST-only FI·현재 JAR smoke가 같은 통합 결과를 뒷받침한다. mandatory guard의 최종 receipt·원격 exact head CI·웹 browser 최종 통합 결과는 부모의 최종 인계 자료에 별도로 연결한다. 서버 통과를 웹 전체 완료나 운영 준비 완료로 확대하지 않는다.

운영 OAuth 공급자 연결·실서비스 로그인, 운영 key/Redis·DB·migration·배포, #108 실제 proof adapter와 모델 의미 정확성은 이 격리 통합 검증으로 입증하지 않는다. 사용자에게 승인된 commit·push·Draft PR·정확 SHA CI와 운영/병합 승인은 구분한다. 검토 중에는 읽기와 이 문서 작성만 수행했다.
