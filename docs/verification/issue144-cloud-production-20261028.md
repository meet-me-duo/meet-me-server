# #144 Cloud 운영 AI 검증

2026-10-28 UTC. 실제 운영 적용과 별도 유료 모델 호출은 실행하지 않았다. 이 기록은 수신된 미완성 작업을 최신 승인에 맞춰 이어간 로컬 코드·대역 검증이다.

## 수신과 원본 보존

- 원격 branch `feature/144-production-ai-activation`, 정확한 HEAD `c3c1de12a7faffb4fc6623db49f20288000a503f`, base `03bbdc1909d22c0bad4667eedea6e2a0c2ffef87`를 직접 대조했다.
- Git blob26의 SHA256·크기·object ID·mode·checkout bytes가 일치했다. manifest 자신을 포함한 변경은27개다. 직전 `f3f350e9dd9affd3790b06ad70dbb85ec2585ff7` 뒤에는 README·manifest만 바뀌었다.
- 원본 ZIP651,089 bytes의 SHA256은 `2d56a03d2502ba77afbaf2fa112cdca70f9841dc4f32f7f6e8e5d0750fb80c8f`다. 활성 원본22의 raw/LF 지문과 patch가 일치했고12개는 bytes동일·10개는 CRLF차이뿐이다. 원래 untracked15개를 포함한다.
- 폐기 참고73개도 검증했지만 활성 소스에 적용하지 않았다. AI의 신규 migration은 없고 V1–V18 SQL은 그대로다. 리뷰의 별도 V19와 worktree를 구분한다.
- 원본 PC 작업, 수신 commit·ZIP·handoff 문서와 기존 테스트 원본은 보존한다. `.env`·실제 키·인증 cache·개인 원문·원시 로그는 이관하거나 Git에 넣지 않았다.

## 최신 요구와 구현 경계

비용 정책 미설정과 소진된 금액 상한은 방 생성·개인 검사·최종 분석을 차단하지 않는다. provider 세 flag의 일관성, 현재 실행·owner/revision/generation·source/context·상태·마감, 고정 profile과 실제 payload, app/network 한 번의 전송·자동 retry/redirect/follow-up 차단은 유지한다. 운영 금전 권한은 발급하지 않고 기존 원장을 초기화하거나 새 quote/cap/epoch/permit을 만들지 않는다.

운영 threshold가 없으면 유효한 Jev CLEAR도 Luna를 거치며 가짜 숫자를 선택하지 않는다. 명시 제공 artifact의 구조·해시·시작 시 유효시각 검증과 명시 threshold 충돌 거부는 유지한다. 시작 뒤 금액 정책 만료는 CREATE/분석과 분리한다. 직접 재무 컴포넌트의 receipt·savepoint·quote·정산 계약은 명시적인 테스트 전용 fixture에서 원래 assertion으로 보존했다.

## 실행 결과

| 구분 | 실제 결과와 범위 |
| --- | --- |
| 이관 원본 Kotlin16 |14 FAIL/2 PASS. 미구현 runtime guard·gate·운영 구성 경계의 RED이며 원본4개 테스트와 XML·로그를 별도 보존했다. |
| 최신 정책 RED |77개 중20 FAIL/57 PASS. 독립 설계자가 정책을 승계하고 기존 원본7개와 사유를 보존했다. |
| 테스트 설계 교정 |첫 GREEN의76 PASS/1 FAIL은 raw budget property와 실제 physical gate를 혼동한 새 assertion이었다. 입력 binding을 독립 교정하고 원본 금액 coupling으로 RED20을 다시 확인한 뒤 candidate bytes를 정확히 복원했다. |
| 최종 집중 Kotlin |77 PASS/실패0/오류0/skip0. budget OFF/ON·UNCONFIGURED·READY 소진·CONFIGURED·시작 뒤 금액 만료의 실제 PG/TLS 운영 graph16과 guard/gate11을 포함한다. |
| Python |신규 운영 renderer·publication8과 기존 관련19, 합계27 PASS. 앞서 실행한9개 subset은 중복 집계하지 않는다. |
| 승인된 Node2 |원본73은71 PASS/정책 충돌2 FAIL. 새 사용자 승인 뒤 부모의 단1회 수정과 독립 audit를 거쳐75 PASS/실패0/skip0. |
| 나머지 관련 Node |policy catalog·provider 환경·auth Compose·raw JSON38 PASS/실패0/skip0. |
| CI 연결 보호 |기존 배포 workflow/guard/extraction13 PASS. 새 Python8을 quality의 기존 step에 정확히 명령1줄 추가했고 YAML parse·나머지 구조 동일성 검사를 통과했다. |
| 독립 결함 주입 |5/5 실제 JUnit assertion 탐지. owner·network 재인증·one-shot·payload digest·threshold 부재의 Luna 생략을 검증하고 매번 제품272·테스트276개 지문을 정확 복원했다. 마지막77 PASS도 재확인했다. |
| 기존 재무 컴포넌트 회귀 |INSERT receipt6·Room8·savepoint2, 합계16 PASS/실패0/skip0. 원래 보안·정산 assertion을 유지한 명시적 legacy component fixture다. |
| 기존 Spring 구성 회귀 |첫 전체 실행의 bean 등록2건·optional provider 주입2건을 원래31개 검사로 재현했다. 테스트 변경 없이 등록 조건과 null 주입 처리를 보완해31 PASS를 확인했다. |
| 전체 Gradle |ktlintCheck·assemble·test, exit0·9분13초.255 suites/2193건 중2189 PASS·기존4 SKIP·실패0·오류0. 소스·리소스·fixture548개가 전후 동일하다. 첫 실행의 공유 deadline 실패1은 원문 그대로 두 번과 최종 전체에서 PASS했으며 최초 실패를 보존했다. |
| 원본 커밋 가드 |수정 없는 require_test_change·run_quality_gate 함수가 PASS했다. hook 자체 검사12와 동일한 필수 Gradle tasks를 검증했고 새2193건 실행으로 중복 집계하지 않는다. git commit은 호출하지 않았다. |

최초 Kotlin compile 메모리 부족과 자원 조율을 위한 취소는 유효 RED가 아니다. Java17·Gradle9.7.1의 작업 cache, 기존 환경 프록시·OS truststore와 in-process/worker1 설정으로 실제 실행을 확인했다. Python DX04의 최초 Docker125는 필요한 Alpine3.22 cache 부재였고 해당 이미지 준비 뒤 원래 검사를 변경 없이 통과했다. CI는 앞선 합성 Compose 검사 성공 시 동일 이미지를 cache에 유지하므로 별도 pull 단계를 추가하지 않았다.

## Node 승인 경계

두 대상은 `deploy/tests/issue131-runtime-bundle-env.test.cjs`, `deploy/tests/runtime-auth-env.test.cjs`다. 기존 회귀 oracle·sentinel 변경에 새 관련 승인이 없다는 자동 승인 검토 거절과 그때의 미실행 상태를 보존했다. 정확한 두 파일에 대한 새 사용자 승인을 받은 부모가 제안 patch를 적용하지 않고 독립 작성한 한 번의 변경으로 성공했으며 재거절은 없었다.

합법 UNCONFIGURED+ON은3기능 ON·budget OFF·threshold없음·opaque Typesafe 전달·600 mode로 검증했다. 기존 sentinel을 포함한 negative7종은 삭제하지 않았고, 잘못된 UNCONFIGURED 구조의 pre-credential 거부도 유지했다. CONFIGURED/OFF/Compose·fixture·helper·기존 auth/secret/환경 검사의 원래 body는 독립 read-only audit에서 동일성을 확인했다. 이 승인·수정은 운영 권한이나 Actions 실행으로 확대하지 않았다.

## 근거와 남은 범위

- 정식 RED: `.tdd/red/issue144-production-cost-removal.json`과 로컬 전용 `.codex/tdd-evidence/issue144-production-cost-removal.log`. 기존 Spring 구성 호환성은 `.tdd/red/issue144-spring-composition-compatibility.json`과 대응 로컬 로그에 별도 기록했다.
- 독립 FI: `.tdd/verification/issue144-production-cost-removal-fi.json`. RED/FI JSON Schema 검증도 통과했다. parameter display name 집계 누락은 보존한 원시 XML의 정확한 method/line과 실제 assertion4개를 대조해 바로잡았고 변이를 재실행하거나 테스트를 변경하지 않았다.
- 독립 기대값 승계와 원본: `.tdd/supersessions/issue144-production-cost-removal.json`, `.tdd/history/issue144-production-cost-removal/originals/`.
- Cloud 수신·원본16·교정 RED·집중 GREEN·전체 source/test 지문은 작업 공간 `/workspace/checkpoints/ai-20261028/`에 보존한다. 집중 GREEN은 제품272·테스트276개 파일 지문을 고정했다. 후속 Spring 구성 호환성 수정은 구성2개 파일뿐이며 FI 대상 실행 본문3개·테스트 전체는 동일하다. 최종 전체 결과와 원본 hook 확인은 `final-full-gate/result.json`, `original-hook-result.json`에 보존했다.548개는 Kotlin514개와 SQL·설정·fixture34개를 합친 수다.
- 기존 TEST_OPERATION의37 PASS·FI4/4 및2200개 과거 실행은 이 운영 구현의 검증 결과로 사용하지 않는다.
- 명시 제공 금액 artifact의 시작 시 만료 검사를 비차단으로 재정의하는 범위는 현재 보류다. 현재 승인된 미설정·상한 소진 분리와 구분한다.
- 실제 모델 의미·지연·청구, target OAuth/secret readiness, 원격 CI·main 병합·운영 활성화는 미검증이다. AWS 인증·배포는 사용자가 귀가 후 개인 PC에서 이어간다. 최신 사용자 위임으로 feature 게시·develop PR/CI·안전한 develop 통합을 진행한다. main의 CI 성공은 production OIDC·ECR/S3 게시·release로 연결되므로 main 병합과 모든 AWS workflow dispatch는 보류한다. AWS·운영 배포·실제 모델 호출은0이며 원격 결과는 별도 게시 receipt와 PR에 기록한다.
