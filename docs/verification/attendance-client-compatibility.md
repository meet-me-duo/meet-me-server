# 구 웹의 조건부 참석 의미 호환 검증

2026-10-31, 운영 반영 전 로컬 1단계. 서버 기준develop6d67c810, 웹2fa24d3a이며 CI179의 기존 성공과 원본 분석 작업은 보존했다. 새 공개 Issue/PR/commit/push·병합·운영·V2 활성화·유료모델/지도는 수행하지 않는다.

## 계약과 변경 경계

ADR-067에 따라 candidates·recommendations primary/alternatives·result GET과 candidate/typed confirmation POST에 optional query attendance_support=conditional-v1을 추가한다. 미지원·누락·기타 값과 실제false payload 조합은409 CLIENT_UPDATE_REQUIRED, true/과거 결과는 기존 호환이다. 후보 일부를 삭제하거나 false를true로 바꾸지 않는다. 기존 인증·HOST·Origin·active run·OPEN round·선택 범위·멱등/충돌 순서를 유지하고 DB write 전에 거부한다. 동일 선택 재요청과 저장 결과도 같은 의미 경계로 보호한다.

영속diverse-time-v1/V9 CHECK·기존V21/V22·참여자별 미확인 상태/설명·시간 선택·matcher·precheck/provider timeout/retry·권한/CORS 설정은 바꾸지 않는다. 새 schema migration은 없다. 여섯 경로의 성공/오류에 no-store를 적용한다. Spring RequestPath/PathPattern으로 MVC와 같은 matrix parameter·encoded literal 해석을 사용한다.

## 독립 RED·GREEN·결함 주입

- 독립 HTTP+실제PostgreSQL ACC17: baseline production 수정 전7PASS/10예상FAIL/오류·skip0, 구현 후17PASS. 테스트해시9d34ce605c3ac6037284b6448d37838303781cd7a76bb1c2bfa7bc181e7e8ab6을 동결했다.
- 검토 중 raw URI regex가 MVC와 동등한 URL을 놓쳐 no-store가 누락되는 P2를 발견했다. 별도CACHE3에서 기존 handler status/code는 유지하면서 cache header만 누락됨을 재현했다. 일반 framework path 해석으로 수정 후17+3=20PASS를 확인했다. CACHE해시9cb3e5866b129a454a280523ed17ee197a0f7c6335379632399e4281ccab1c52를 동결했다.
- 독립scratch control20PASS, 유한6mutants 모두 실제 동작 assertion8개로 검출했다. 누락cap 지원오인·typed/legacy write 및 replay·result read·no-store를 다뤘다. 매회 복원, 원본307파일과 frozen test 해시 불변, 열린P1/P2 없음. 공개 요약은 .tdd/red 및 .tdd/reviews에 두고 원본 로그는 로컬에 보존한다.
- 이전CPP의 조건부 정상 경로는 지원 query를 명시하되 공유 fixture default는 null로 유지했다. 실제 사용되는 합성 재생 HTTP client4URL만 새 지원을 선언했다. 초기회귀14PASS/1재생client누락FAIL은 보존했고 해당client 정정 후1PASS로 확인했다. 실제모델 응답/gold/물리 호출 예산은 변경하지 않았다.

## 전체 회귀의 테스트 계약 정정

첫 ktlintCheck·assemble·test는2695건 중12FAIL/17SKIP로 실패했으며 원본 결과를 보존했다. 10건은 기존 조건부 결과 검사의 지원 선언 누락이었다. 해당 HTTP·matrix·unit 호출에만 지원을 명시하고 공유 fixture의 기본값은 null로 유지했다. 기존 상태 코드·실제 시각·불변 tuple·room lock·원문·DB 건수·권한 단언은 바꾸지 않았다.

나머지2건은 이전 MockMvc 요청의 thread-local host 쿠키가 새 unit fixture의 viewAsMember를 덮어쓰던 테스트 격리 누락이었다. 독립 NFI2는 기존 fixture에서1PASS/1예상FAIL을 재현했다. 새 fixture 시작 시 이전 request context만 비우는2줄 수정 후2개 모두 통과했고, 별도 HTTP control은 host200/member403을 그대로 요구한다. 실제 인증·인가 코드는 변경하지 않았다. 수정 후 관련67건/ktlintCheck 통과, 최종 동일 명령 재실행은2697건 중2680PASS/17기존SKIP/실패·오류0으로 성공했다. 앞선17SKIP와 최종 집합이 완전히 같고 신규SKIP는 없다. 실행1002.547초 동안681개 소스 지문이 불변이었다.

## 웹·실제 Chromium 경계

웹 unit825PASS, API 계약15PASS, 기본 browser discovery518개를 서로 겹치지 않는98+420배치로 모두 검증해517PASS/기존1SKIP/retry0를 확인했다. 기존SKIP는 변경 없는 landing desktop의 mobile 전용 검사다. 추가 evidence browser6PASS와 로컬 native 하네스 사전검사 단위3PASS도 확인했다. 실제 생성 OpenAPI JSON은 여섯 operation의 optional query·409description만 달라졌으며 types를 자동 생성했다. UI TSX/CSS와 새 표시 영역은 바꾸지 않았다. 웹 build·lint·typecheck·api:check와 최종 서버 ktlintCheck·assemble·test가 모두 통과했다.

운영 웹 source5555ccbe를 별도 detached 작업 공간에 고정하고 동일 lock의 bundle을 build해 실제 Chromium에서 비교했다. 신 웹은 false를 시간 일치로 표시하고 한 번 선택·확정·새로고침해도 false/설명/실제 시각을 유지했다. 과거 필드 누락 verified 응답도 유지했다. 구 탭은 합성으로 채운 cached false의 잘못된 표시를 재현한 뒤 다음POST409/GET409에서 쓰기0·오표시숨김을 확인했다. 저장 결과 cache 재조회도409 뒤 숨겼으며 실제 document 요청은 각1번으로 구bundle이 교체되지 않은 상태다.

이 브라우저 검증은 API contract 대역이고, 실제 HTTP+PG·worker 재생은 별도 테스트다. 하나의 연속 브라우저→DB 또는 실provider 전체 성공으로 확대하지 않는다. 초기 하네스의 없는 radio 가정, SPA frame event를 문서 reload로 오산한 계측, 비버블 visibility event는 원본 실패를 보존하고 해당 case를 정정했다. product assertion을 낮추거나 성공한 case를 반복하지 않았다.

## rollout과 남은 단계

이미 연결이 끊긴 DOM은 서버가 소급 수정할 수 없다. 기존 코드·schema·데이터의 의미와 첫 조건부 producer 전환 조건을 사전점검한 뒤, 새 웹을 먼저 확인하고 guard 포함 신 서버를 처음부터 적용해야 한다. 구 탭은 다음 요청/확정에서 새로고침 안내를 받는다. 같은DB의 false 데이터가 있는 동안 guard 없는 binary나 구 웹으로 무조건 복귀하지 않는다. 기존V21/V22 schema-before-code·guard/복구 검토는 그대로 필요하다.

운영 사전점검의 정제 자료는 코디네이터가 별도 확인하고 원본 artifact/운영 메타데이터는 로컬 기록에 보존한다. 이 검증은 추가 dispatch·운영 조회·migration·배포·복원 시험을 수행하지 않는다. 사전점검 성공을 복원 시험이나 자동 dispatch 가능으로 확대하지 않으며, 실제 적용 전에 현재 상태와 신선도를 별도 확인한다.

다음은 새 게시/develop CI 승인, 운영순서·복구 검토, main/운영 적용·actualSHA/DB/UI 확인, V2별도비용승인, 전체 모델 흐름/실패/구클라이언트 검증·기록 단계다. 지금의 로컬 1단계를 전체 완료로 표시하지 않는다.

1단계 로컬 호환 보강·무료 검증은 완료다. 2단계 Issue/feature 게시·develop PR/병합·정확 SHA CI는 새 구체 승인 대기이며 미실행이다. 전체 서비스 운영 반영 완료가 아니다.
