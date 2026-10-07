# 추천·Luna 폴백 통합과 D2 검증 — #99

2026-10-07 UTC. 작업 브랜치는 `feature/integrate-recommendations-fallback`, 원격 기준은 PR97 `518c612f4be9627427f61a56c982b496342f0a28`·PR98 `9a4c4d5463e578829baed12cfebad16fb0bf33a4`다. PR94 `bf4edff51dea5f1f90a8ac627edf05ec17b6d56d`와 PR93 의존을 포함한다. 최신 develop848b968 clean pull 뒤 브랜치를 만들었다.

## 구현 경계

추천 V9 SQL SHA-256 `a6fc046611fb3cafa96d51f87de09687b3fdecc403988d211b7d86c4fad0585d`와 invocation SQL SHA-256 `694940de9100b58bc4a1ca2889d5d44fbd86221dead6805082b2c7999ee002ab`를 보존하고 invocation 이름만 V10으로 배분했다. V1~V8은 변경하지 않았다. 원격 PR/branch 상태는 확인했지만 운영 Flyway 이력의 적용 여부를 조회하거나 repair하지 않았다. 운영 적용 증거가 없다는 사실을 운영 미적용 확인으로 확대하지 않는다.

ADR-047 시간 추천·ADR-048 D2를 유지하고 Luna 결정을 ADR-049로 배분했다. MatchingProcessor는 frozen 구조화 결과의 하드 가능/불가·장소조건을 먼저 적용하고 명시 선호 차원 존재, 시간 합집합의 전체 창 포함, 유효 공통 장소를 계산한다. 참석 인원→선호 충족 참석자수→동률 다양성 순이며 참가자별 최대1점이다. 원래 가능 창, 개인 선호와의 교차 창, 여러 선호가 겹친 창을 보존한다.

live schema_version3의 PREFERRED_TIME_WINDOW/PREFERRED_PLACE는 하드 타입과 분리한다. Gemini/Luna가 동일 prompt/schema/validator를 사용하고 v1/v2 비strict fixture와 기존 JSON은 선호 없음으로 복원한다. strict raw 키/nullable required/32조건/256KiB/정확 input_ref/AREA 이름·unsafe conditional 경계를 유지한다. 부분 거부 조건은 유효한 하드 가능 조건과 선호 하위 창을 보존하되 해당 참가자의 선호 점수를 0으로 제한한다. 잘못된 하드 시간 조건은 AMBIGUOUS_TIME_CONSTRAINT로 안전 제외해 누락된 불가 조건으로 참석 가능성이 확대되지 않게 한다. 공개 protocol은 diverse-time-v1이며 새 입력 UI·개인 점수 필드는 없다.

bounded 경로의 legacy 후보·projection·room transition은 room→run 잠금 아래 한 transaction으로 게시한다. 계산 중 취소와 게시 전/후 monotonic·durable deadline 검사를 유지하며 초과 시 모든 결과를 rollback하고 새 transaction에서 ANALYSIS_DELAYED를 기록한다.

## 현재 검증

독립 테스트 작성자·구현자·리뷰 역할을 분리했다. 첫 provider/JSON 및 live fixture50건 중23예상 실패, 수정한 도메인·명시차원18건 중13 assertion RED, PostgreSQL 통합7건 중6 assertion RED를 기록했다. 실제 빈 DB/V8 업그레이드2건 GREEN. 첫 집중 GREEN73건과 최종 안전성 GREEN61건은 모두 실패0·오류0·skip0이며 중복 테스트가 있으므로 합산하지 않는다. 부분 거부 선호 득점과 하드 시간 안전성은 추가 assertion RED로 보완했다. 일부 최초 도메인 XML은 다음 실행에 덮어써졌으므로 원시 실패 로그를 근거로 보존하고 재작성하지 않았다.

독립 최종 리뷰 PASS·차단0, 의도적 결함11/11 assertion 탐지·실패41개·247개 소스/테스트 정확 복구를 확인했다. 최종 지문과 탐지 테스트는 `.tdd/verification/issue-99-integration.json`, 독립 검토는 `.tdd/reviews/issue-99-independent-source-review.json`에 추적한다. 원시 로그·XML은 ignored `.codex/tdd-evidence`에만 보존한다. 첫 전체 guard631건 중1건은 기존 schema 분기수5 검사가 승인된 v3의 hard5+preferred3을 반영하지 못한 assertion이었다. 독립 테스트 담당자가 기존 하드 검사를 유지하고 선호3종·정확 required/null/scope 검사를 추가했으며 focused12건 GREEN이다. 원본과 과거 증거는 `.tdd/supersessions/issue-99-schema-preference-expansion.json`으로 보존한다. 최종 변경 없는 guard exit0·ktlintCheck/assemble·109suite 전체631건/실패0/오류0/skip4와 hook self-tests12 PASS를 확인했다. 배포 정적13·Compose/credential fixture·V8guard37 및 동일 공개 config의 Nginx 임시 fixture도 PASS다. 정확 HEAD CI 결과는 게시 후 Draft PR 본문을 기준으로 확인하며 원본 Nginx script 역시 해당 CI에서 확인한다.

## 웹·실평가·운영 인계

웹 PR20 HEAD `8e56fe0c37cef52e584ff569b97b46b60f28e517`를 원격 확인했다. 새 native OpenAPI를 실제 /v3/api-docs에서 생성해 웹이 사용하는 PR97 계약과 parsed JSON 완전 일치를 확인했다. 직렬화 바이트는 다르며 두 SHA는 `.tdd/reviews/issue-99-openapi-contract.json`에 기록했다. 실제 backend/browser 공동 검증은 웹 담당01a114d2-c535-7787-adca-69742f5cd08d와 부모를 통해 조율한다.

이 구현의 유료 호출은0. 통합 준비 후 전담 평가 세션01a114c3-02d3-7011-ab8f-19706bdda089에 정확 HEAD·live v3·무료 fixture 근거를 인계한다. 기존 유료19/50·Luna 품질7/8은 새 통합 품질 PASS로 재사용하지 않는다. 기존 평가 하네스의 v2 응답 체크와 추천 repository를 연결하지 않은 legacy matching 경로는 전담자가 v3·통합 projection 경로에 맞춰 검토해야 한다. exhaustive preference 출력 branches는 컴파일 호환만 추가했으며 하네스를 실행하지 않았다. IntegratedRecommendationFallbackPostgresTest는 typed fake parser로 저장→MatchingProcessor→추천 조회/확정 경로를 검증한다. 전담 실제 D2 종단 평가는 실제 공통 parser를 연결하고 그 이후 저장·매칭·추천·확정은 이 통합 테스트와 동일한 경로를 사용해야 한다.

main/develop 병합·배포·운영 migration·보안 설정·비밀정보 취급·자동화 중지를 실행하지 않는다. 운영 V8guard/digest/드레인 승인과 새 공급자 고지/설정은 배포 준비의 별도 미해결 항목이다.
