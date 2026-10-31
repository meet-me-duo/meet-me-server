# 최신 저장 Luna 응답의 로컬 fallback·DB·브라우저 연결 (2026-10-31)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**합성 Gemini 기술 오류 + 최신 저장 Luna 응답으로 실제 로컬 worker→PG/Redis→후보→주최자 확정→Chromium 새로고침을 연결했다. 성공·기술실패2방/브라우저3검증묶음이 통과했다. 신규 실제 공급자·지도 요청은0회다.** 실제 공급자 fallback 성공이나 운영·일반 성능을 증명한 결과는 아니다.

## 확인한 공백과 범위

이전 [브라우저 검증](experimental-conditional-place-candidates-result.md)은 다른 저장응답을 사용했고, [최신 Luna직접1회](experimental-direct-luna-one-result.md)는 실제9.319초성공·의미대조와무료도메인재생까지였다. 최신 저장응답 자체가 실제앱의마감/worker/fallback·DB·확정·화면을통과하는공백만이번에확인했다. 이미통과한mobile/시간·장소충돌·중복클릭·대규모회귀는다시실행하지않았다.

서버 `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a` + 기존격리변경, 웹 `6e406ce07f5045790120bce6f34407ca0d65e108` + 기존조건부설명변경을보존했다. 제품/테스트소스변경0이고새scratch Java·Node실행기와보고서/진행계획만추가했다. 현재웹소스가동일하므로직전검증빌드자산11개의해시를검증해재사용했다. 원래full2619PASS/17동일SKIP·웹807을새로실행했다고주장하거나이번숫자와합산하지않는다.

`verification` 스킬의browser→API→data→UI추적을사용했다. 기존프로젝트지침·격리harness를유지했고운영방/DB/키/지도/외부서비스를사용하지않았다. 최신직접호출승인과이전폐기잔여는모두그대로보존했다.

## 실제 경로·합성 경계

1. fresh PG18/Redis8과원Spring앱을loopback에서기동했다. synthetic호스트/참여자3명으로실제HTTP방생성·개인입력접수·수동마감을수행했다. 사전확인은CLEAR test double이며실제Jev·개인질문모델을쓰지않았다.
2. 원 `CollectionClosureService`·불변batch·Outbox relay·Redis Stream·worker·durable invocation/attempt·`GeminiBatchProcessor`·원JDBC sourcefence를사용했다. sourceauthorize4회를관측했다.
3. Gemini parser port에SERVER/Retry-After40000ms를합성주입했다. **현재최초+최대3재시도정책은그대로**다.40초가Gemini단계예약30초보다길어기다리거나재시도하지않고fallback으로넘어가는특정정책경로다.‘원래재시도없음’이나‘4회재시도전체검증’이아니다.
4. 원Luna adapter에현재frozen입력을전달했다. 성공방에서는[직접진단](experimental-direct-luna-one-result.md)의저장output_text를재생했다. 고유exact raw로대응을검증하고 `input_ref`만실제현재versionUUID로재매핑했다. 조건·거절/clarification을바꾸지않았으며실DB정규화조건3건도원저장결과와비교해동일했다.
5. 실패방에서는같은원Luna adapter의application interceptor에서합성SocketTimeoutException을주입했다. 실제27초를기다린remote timeout이아니라기술실패전파검증이다.
6. 두인터셉터는외부네트워크전에반환/실패한다. 공급자헤더·DNS·network와지도는각각별도fail-closed guard,전체Java/Node/Chromium은비루프백TCP차단으로보호했다.

원직접진단은V2 PENDING이었고이번앱접수는mockCLEAR로생성된evidence다. 원문은같지만동일provider wire/prompt를재생성해모델응답을얻은시험은아니다. 저장출력재생으로앱소비계약을확인한것이다.

## 예산·횟수의 구분

| 항목 | 성공방 | 실패방 |
|---|---:|---:|
| Gemini 논리적시도(DB admission) |1 SERVER|1 SERVER|
| 실제Gemini SDK/공급자 요청 |0|0|
| Luna 논리적fallback invocation |1|1|
| 원Luna adapter의로컬OkHttp Call lifecycle |1|1|
| 신규실제Luna/지도 요청 |0|0|
| 저장응답재생 |1|0|
| 합성fallback기술실패 |0|1 TIMEOUT|
| 호출진입의실제caller 잔여예산 |26.991182651초|26.993755687초|

현행60초/primary30초·호출15초/fallback27초/완료3초의제품상수·SQL제약·sourcefence는바꾸지않았다. 두invocation모두gemini_attempts1/luna_attempts1이며추가fallback이없었다. 이번에는짧은재생/합성오류경로를검증했으며deadline경계·재시도전체·실제네트워크latency를다시검증한것은아니다.

성공방DB에input2885/output787이보이는것은저장응답의usage를그대로재생한**로컬테스트메타데이터**다. 새모델token소비나청구로집계하면안된다. 실제유료호출수는0이며직접진단의기존1회를추가사용한것도아니다.

## 성공방: worker·저장·선택·확정·재조회

- 원문frozen3, structured3, legacycandidate1, 신규recommendation option3, winner1을저장했다. 시간제약3개와TRAVEL_CONSTRAINT2개가실DB에서도보존됐다.
- 세사람전체variant는11월24일19~20시(UTC10~11시), IN_PERSON이다. 두사람장소·이동조건은미확인이며 `attendance_confirmed=false`, 가짜장소/좌표나출발지는추가하지않았다. 나머지두옵션은부분참여이며3옵션모두전원참석이라는뜻은아니다.
- Chromium151.0.7922.173/1365×900에서세후보를실제API로읽었다. 각기존설명문안에미확정설명이1회포함됐고‘명참석가능’이라는단정은없었다. 후보공개설명에개인사당역/출발지/40분표현은없었다. 새장소경고영역은없으며기존공통PARTIAL안내1개·입력하지않은host본인안내는유지됐다.
- 전체3인19~20시옵션을동적으로찾아선택하고가능범위전체를확정했다. 실제confirmation POST1회/200, 선택DB1행이었다.
- 새로고침후실제GET `/result`와확정응답이동일했다. 선택analysis/option/variant/time이DB와일치했고선택variant의3참여자중place_unverified2개, 설명·false참석확정값도유지됐다. frozen/structured해시는확정전후동일했다.

실제확인한후보/확정설명:

> 11월 24일 19:00~20:00. 3명의 시간 조건이 맞지만, 일부 장소·이동 조건은 확인하지 못해 실제 대면 참석 여부는 미확정입니다.

## 실패방: 입력 보존·지연 표시·ACK

실제worker가SERVER→TIMEOUT을DB에기록하고ANALYSIS_DELAYED로종결했다. 원문frozen3은보존됐고structured0/candidate0/option0/selection0/winner없음을확인했다. Outbox는PROCESSED, delivery_count1, Redispending0/streamlength0이었다. 실패를새추천이나정상승인으로감추지않았다.

Chromium에서도‘분석이잠시지연되고있어요’와입력보존안내가표시됐고후보/확정은없었다. 새로고침후에도동일했다. ‘분석다시요청’버튼은존재하지만누르지않아새분석을만들지않았다. 두방모두ACK까지끝난뒤최종DB스냅샷을보존했다.

## 브라우저·네트워크·보존 근거

브라우저는합성host의auth refresh bootstrap만대체했다. 이후방/추천/미반영/확정/결과API는실제Spring/RS256검증/PG/Redis다. 실제OAuth로그인이나상용인증플로우를검증한것은아니다. desktop만실행했으며이번mobile/실물기기추가검사는없다. 세스크린샷(후보/확정재조회/실패)은직접시각확인했다.

실provider헤더/DNS/network/map카운터모두0, externalPageRequests0이다. 다만Chromium백그라운드접속시도는denyproxy6회·libc비루프백connect3회차단됐다. 이는서로다른레이어라합산한unique요청수로표시하지않으며‘모든외부시도0’이라고말하지않는다. 서버차단시도는0이었다. 원키/live플래그는제거했고token/header/HAR/trace는내보내지않았다.

독립검토자는실DB와저장응답의3ref조건/거절값동일성,선택시간·미확인2명,실패보존/ACK,브라우저결과와source910/protected28/web11개해시를재검증했다. 검토범위P1/P2문제0이다. 이전유료manifest3307파일도전부동일하다. 새기능결함은발견되지않았고제품/테스트소스수정없이완료했다. 최초scratch컴파일의Java17 Duration.isPositive미지원1건만compareTo로정정했으며행동RED나제품결함으로세지않는다.

증거 디렉터리: `local-only-evidence/luna-replay-app-20261031`.

- `LunaReplayBrowserServer.java`, `start-server.py`, `browser.mjs`, `run-browser.py`: 제한된재현실행기.
- `positive-initial-state.json`, `failure-initial-state.json`, `final-db-state.json`, `stored-response-db-equality.json`: 실제PG/Redis/불변데이터증거.
- `browser-result.json`, `browser.log`, `browser-exit.json`, `server.log`, `server-exit.json`, `.audit`: 실제실행·종료·차단근거.
- `positive-candidates.png`, `positive-selection.png`, `positive-confirmed.png`, `positive-reloaded.png`, `failure-delayed.png`: 화면증거.
- `result-summary.json`, `independent-review.md`, `baseline.json`, `web-assets-reused.json`: 요약·독립검토·보존.

브라우저·서버프로세스는각exit0으로종료했고소유한테스트PG/Redis도정리했다. 현재차단없음. 신규공급자/지도/유료요청0, 제품/운영timeout·보안·자격증명변경0, 외부게시/push/PR/merge/deploy/Linear수정0이다. 이번결과는합성Gemini오류+저장Luna응답의로컬통합검증이며, 실제공급자fallback·일반지연/운영/질문모델전과정의실시간검증은아니다.

정제 실행기·로그·DB·스크린샷 묶음: `local-only-evidence/meet-me-latest-luna-local-fallback-browser-20261031.tar.gz`.
