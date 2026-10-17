# Issue #109 실행 기록

## 복구 시작점

- 범위: 서버 #109 + 연결 웹 로그인/회원 참여/본인 HOST 방 아카이브. 다른 이슈 구현은 포함하지 않는다.
- 사용자 목표: 2026-10-18 KST까지 #107~110 마무리 중 #109 담당. 공식 외부 마감이나 완료 보장은 아니다.
- 서버 기준: 최신 원격 develop `3232128bb1d0886c987e378a787633bd2aee6341`, 깨끗한 트리에서 `feature/issue-109-account-archive` 생성. 원격 추적 ref가 없는 환경이어서 명시 fetch 후 develop에서 ff-only pull을 확인했다.
- 웹: 원격 branch 목록은 main `9b183978d5987f8fa8a5f02d542b17181e4929b4`만 존재하고 docs/BRANCHING.md는 main 기반. 기준 차이를 알리고 확인 질문 후, 최신 원격 기준으로 격리한다는 취지 및 웹 고유 지침에 맞춰 최신 origin/main pull 후 같은 이름의 local feature를 생성했다. 서버 develop/웹 main 차이를 인계에 명시한다. 원격 branch 생성은 없음.
- 내장 goal 도구: 제공된 tool metadata에서 전용 goal API 없음. 설치/숨은 설정 변경 없음. 이 파일과 체크리스트를 목표 기록으로 사용한다.
- 기존 미커밋 변경: 양 저장소 착수 시 clean. work 브랜치의 미커밋 작업을 승계하지 않음.

## 공통 계약과 변경 경계

원본 계약: [ISSUE_109_CONTRACT.md](ISSUE_109_CONTRACT.md). 부모가 #108/#110에 전달할 계정·소유권·게스트 연결 계약과 파일 경계를 담는다.

## 완료 기준

- [x] Google/Kakao 서버 OAuth code+PKCE+browser-bound single-use state+OIDC nonce/issuer/audience/signature 검증
- [x] RS256 kid Access 15분, Access 메모리·Authorization 헤더, Refresh cookie-only
- [x] Redis atomic Refresh 회전: idle14일/absolute30일, replay family 폐기, 현재/전체기기 로그아웃, exact Origin
- [x] 공급자 subject 계정 식별·영구 PostgreSQL 저장·이메일 자동병합 없음
- [x] 로그인 필수 생성·회원 이름/암호 재입력 면제·게스트 참여 유지
- [x] 명시적 게스트 연결: 이중 증명·동일방 충돌409·경합·ID/제출 보존·이전 게스트 권한 회수
- [x] HOST 본인 방 아카이브, 타계정/링크 권한 격리, 기존30일 정리·만료표시
- [x] 실제 PostgreSQL 마이그레이션·제약·트랜잭션·경합 검증
- [x] 웹 UI와 실제 격리 서버/공급자 대역 브라우저 통합
- [x] 독립 frozen RED·GREEN·결함 주입·별도 최종 리뷰
- [x] 전체 서버 ktlintCheck/assemble/test + guard, 웹 api:check/lint/typecheck/test/build/e2e
- [x] 생성 OpenAPI 동기화·제품/아키텍처/ADR/구현 계획·OAuth 행동 가이드·인계 자료

## 역할과 실행 규칙

- root: 계약/기록/사용자 가이드/DB migration, 웹(기준 확인 후), 통합·게이트·인계
- auth_tests: 공개 계약 기반 독립 인증 테스트·RED freeze·보안 결함 주입
- auth_impl: account/auth 서버 구현; frozen 테스트 기대값 수정 금지
- ownership_tests: 독립 소유권/아카이브/DB migration 테스트·RED freeze·경합·결함 주입
- room_impl: participant/room/기존 권한 서비스의 계정 소유권 통합
- 최종 reviewer는 구현에서 분리하여 배정한다. Gradle은 `flock /tmp/meetme-109-gradle.lock`으로 직렬화한다.

## 승인/외부 경계

- push/PR/병합/배포/운영 DB/실제 credential 생성·입력은 수행하지 않는다.
- 실제 OAuth 앱·key·운영 RS256 key가 준비되기 전 isolated provider double만 사용. 성공을 운영 로그인으로 보고하지 않는다.
- #110 관측 계층은 제외. SubmissionService.kt는 소유권 resolver 주입/requireParticipant 경계만 변경 가능하며 별도 마감 수정은 제외한다.
- Linear JIN-74 기존 카드 재조회 성공. 상세 갱신은 자동 승인 검토가 외부 보안·구현정보 전달 위험으로 거절해 미적용. 이후 부모가 Linear 전담/재시도·질문 금지를 명시했다. 기존 질문은 폐기하며 이 세션에서는 외부 갱신·추가 질문 없이 로컬 기록만 유지한다.

## 실행 기록

- 2026-10-17 UTC: 저장소 AGENTS·6개 규칙·project-architecture skill·#109 원문·인증/보존 PRD/Architecture/ADR 확인. 사용자 최신 제품 결정이 오래된 Human Blocker/미정 상태보다 우선한다.
- 독립 고위험 TDD 역할 분리 착수. 실제 Docker 사용 가능, PostgreSQL/Redis Testcontainers 검증 예정. 아직 구현/검증 완료 항목 없음.

- 2026-10-17 UTC: 독립 migration RED1 실패(계정테이블 누락 assertion)→V11→GREEN1. 인증 독립30 테스트 중29 TODO assertion 실패·1 장애assertFails 성공, identity 독립6 TODO 실패. 환경/설정 실패는 RED로 집계하지 않음.
- 부모에게 principal/owner/API/V11 예약/파일경계를 대화로 전달. #110에는 Nginx callback query error log 비노출 검증 필요를 전달했으며 관측파일 직접 변경 없음.

- 부모 통합 예약 확정: #109 V11 / #108 V12 / #107 V13 / #110 V14. V11 기존 원격V1~10와 충돌없음. 다른 번호 사용/기존 migration 수정하지 않음. SubmissionService 소유권 hunk만 인계하여 별도 마감 검사를 보존한다. 부모 Linear 전담 유지.
- 사용자가 기존 Google meet-me 프로젝트/Kakao meet-me 앱 존재를 확인. 신규 생성하지 않고 기존 client/redirect/동의/최소scope/테스트계정/변수 입력을 안내. 실제 secret 조회/출력0.

- 독립 리뷰 R109-04: 인증 결함주입 최초7/7 집계 중 일부가 신규 scopedproof 테스트 compile실패 뒤 이전XML을 읽은 오집계. 해당최초결과는 INVALIDATED로 철회/이력보존하며 compilegate+새JUnit 파일 확인 후7개 전부 재실행한다. 원본 소스 exact복원은 확인됐지만 이를 결함탐지 성공으로 혼동하지 않는다. 아직 인증fault 최종통과 미확정.

- 첫 공통계약/서버source인계 Library libfile_596ef93921888191827fd7521ebf14a3 v0, archiveSHA22bb9fd642eb344450235e18e6a3508818c42349ae8b36724535d224a3936e5d,384598bytes. base3232128,73sourcefiles+manifest+patch3개. exactbasegitapplycheckPASS. Targeted verified/fullgatespending/actual#108adaptercombinedpending로표시. 부모에게ID/hash/한계보고완료. 실제비밀/키/쿠키/rawlogs제외.
- 인증최초fault오집계는철회이력유지. correctedfreshJUnit compilegate7/7및auth45postrestoreGREEN을독립reviewer가확인. 소유권기존7fault,추가scope5fault원본SHA복원확인.

- #108 참조자료 Library materialize: 첫 전송과 부모가 허용한 새 전송 참조 1회 모두 정확한 도구 제공 URL에서 HTTP403. 파일 미생성으로 내용/해시 미검증. 임의 URL·다른 환경 경로·우회 없이 중단. #108 실제 세션의 병합 책임은 유지한다.
- 첫 전체 서버 gate:771개 중6실패/4skip. account architecture whitelist, account우선 matching fixture 2개와 V11 migration 총수 기대값4개 확인 중. 보안 실패를 임의 면제하지 않으며 수정 후 같은 전체gate 재실행한다. 최신 웹362단위GREEN 및 expiry/cachefault2종 탐지/정확복구 완료.

- 최종 서버 원래전체gate(수정후):773total/769실행PASS/기존disabled4/fail0/error0, source불변. 동일hookguard exit0, 별도guardselftest12PASS. 실제commit아직미실행. focused28PASS·native최종main12PASS·실제OpenAPI23paths SHA924b0683 확인.
- 추가 사용자승인: 최종검증/독립리뷰가통과한범위에서commit→featurepush→DraftPR→정확headCI까지허용. merge/auto-merge/main배포/운영DB/Secret입력은제외. feature워크플로가배포를유발하면중단한다. 서버develop/웹main원격base SHA불변과featurebranch부재확인; CLI GitHubREST403은공식연결GitHub도구로대체했고본인열린PR0확인.
- 최종리뷰P2 Unicode회원이름: 서버가무시하는legacyhost_display_name의UTF16@Size에긴정상프로필이막힐수있어웹에서해당field를생략. 독립RED2→GREEN2, re-addfault1종2assertions탐지/정확복구. 기존Unicode검증은유지하고같은payload의나머지계약은불변. 서버소스변경없음, 최종웹전체게이트/native재확인진행.

- 웹최종localgate:364unit/29files, api/lint/typecheck/buildPASS, finalbrowser383PASS+기존skip1/FAIL0/retry0(최종index-By9lmPRX.js), source/config/schema/fixture/dist불변. 최종native12PASS15:26:14. 독립웹리뷰APPROVED. 승인범위내웹commit95f09c0a98c6538b0f19ed381fdf11a2f5b844d9/featurepush/DraftPR25 생성완료; 실제운영설정미검증을본문명시. CI39045603250 진행중. PRmergeab0a9d00b50ecd83a2863f55c68f126984a71a94 tree가head와동일.
- R10908 V11전달catalog: 신규독립RED9중7assertfail→exactV11publisher/immutableV10V11reader조건5곳 수정→새9+기존7=16GREEN. 3fault/4assertions탐지·exactrestore/new9GREEN. 실제Flyway12.4 V11checksum-1822689491. CI기존Node명령에새9파일만추가; 운영workflow조건불변. postcatalog원래guardexit0, 기존773Kotlin gate의업투데이트재사용을새실행으로집계하지않음.
- 게시전changedfile검사에서privatekeyblock/GoogleSecretprefix/AWS/GitHub/Slacktoken패턴없음. 실제provider/runtime비밀파일/환경조회없음. 합성provider/RS256/refreshfixture만사용하며원본로그·환경파일·키는추적제외. 웹raw TDD evidence디렉터리는gitignore추가.

- 웹 exacthead CI39045603250 success: unit364 및 E2E383통과/기존skip1을 job117433583969 원본로그에서 확인했다. checkout mergeSHA ab0a9d00...과head95f09c0...가모두로그에있고 두tree동일을Git으로확인했다.
- 기존V8보호테스트 최초자원경합실행의11개15초timeout은그대로보존했다. 테스트/스크립트/15초제한을바꾸지않은단독전체37개가359.672초에모두통과했다. JDK17누락과Nginxcheckoutmode/daemon설정의환경실패도기록후정확동일source의정상환경에서재검증했다. 실제운영정책실행이나완화는없다.

- 최종 CI 부가 검사 모두 통과: deployment13/preflight21/catalog16/runtime provider19/built-image workflow12/issue links9, compose·DB refresh·Nginx shell, 원래 V8 guard37. 최초 환경 실패와 무효 증거는 삭제하지 않고 재실행 provenance를 보존했다. 실제 ARM64 이미지 검증은 feature PR의 별도 CI에서 확인하며 로컬 이미지 실행 완료로 표시하지 않는다.

- 독립 최종 리뷰 APPROVED_IMPLEMENTATION_SCOPE: 열린 구현 결함·미완료 구현 검사가 없다. 실제 OAuth/#108 adapter 결합/#110 운영 로그/원격 ARM64 CI는 별도 외부 검증으로 유지한다. 모든 local gate·안전한 게시 검사를 마쳐 승인된 feature commit·push·Draft PR·정확 head CI 단계로 이동한다. Commit/PR: 동일 커밋 예정, 정확 hash는 Git 이력과 원격 receipt 참조.
