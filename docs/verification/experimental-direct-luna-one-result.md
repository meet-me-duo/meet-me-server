# 새 명시 승인 Luna 직접1회 진단 결과 (2026-10-31)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**Luna 직접 요청1회가 HTTP200/completed로 성공했다. 원래 어댑터 경과9.319초·HTTP경과9.198초이며, 세 입력의 시간과 이동 표현이 사전 의도대로 보존됐다.** 같은 저장 응답의 무료 도메인 재생에서 공통19~20시 시간 후보를 유지하면서 두 이동 조건은 미검증으로 남겼다. 실제 이동 가능, 전체 앱 fallback·Jev/보완 질문·DB 확정·브라우저·일반 지연·운영 성공은 검증하지 않았다.

## 승인·범위·실행 고정

부모가 전달한 이전 유료 지시 전달은 자동 승인 검토에서 거절돼 실행0이었다. 이후 사용자의 새 명시 승인 `synthetic-authorization-d0ace57578a87106` 범위는 **Luna만 최대1회/27초, SDK/app재시도0, Gemini/지도/추가fallback0, 기술오류 즉시종료**다. 새승인ID의기존실행/claim부재를확인하고독립사전검토후새전역CREATE_NEW claim을소비·fsync했다. 이범위를다시확대하거나이전2회승인의폐기잔여1회를사용하지않았다. 이번실행에는추가권한거부가없었다.

서버기준은 `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a` + 기존격리V2/조건부장소/오류보존변경이다. 최신compiled main/test/resources와dependency를보존해썼고3,307개실행파일해시를호출전후검증했다. 제품/운영timeout·schema·prompt·모델·보안·자격증명설정은바꾸지않았다. 실행child에는기존지원OPENAI binding만정상SDK/HTTP인증에사용했고값을저장하거나출력하지않았다. Gemini/TYPESAFE키와다른live플래그는제거했다.

실제계층은 `OpenAiLunaNaturalLanguageParserAdapter` → 직접Responses HTTP/OkHttp → 원전송gate다. 별도OpenAI SDK나상위재시도loop는없다. gate는합성source authority로이진단의입력/실행만제한하며앱JDBC권한·collection·worker를대신검증한것은아니다. Ledger는LUNA단계만허용하고Call·ordinal·헤더시작/완료·종료각1, matching완료·2xx/noFailure·원파서/입력ref검증후에만DONE이된다. 실패/불확실시STOP이며claim은반환하지않는다.

## 새 합성 모임과 실제 해석

모임은대면, Asia/Seoul,2026-11-24부터25일배타적종료,소요시간미지정이다. 새원문3개·사전의도·요청ref를 `scenario.json`에호출전고정했다. `PRE_SUBMIT_V2` PENDING evidence와원문을직접전달했으며Jev가CLEAR로확인했다고표시하지않았다. 봉인holdout/실사용자/운영입력은쓰지않았고블라인드평가가아니다.

| 참여자 | 사전등록 시간 | 사전등록 장소·이동 의도 | 모델 결과 |
|---|---|---|---|
|1|11/24 18~21시|사당역에서 도보15분 이내 필수|TIME_WINDOW와 해당 장소 기준의 TRAVEL_CONSTRAINT 보존 |
|2|11/24 19~22시|대면, 장소/이동 제한 없음|정확한TIME_WINDOW만, 임의 장소조건 추가없음 |
|3|11/24 18:30~20시|개인 출발지 미정, 대중교통40분 이내 필수|TIME_WINDOW와 기준점 미상의 TRAVEL_CONSTRAINT 보존 |

세 ref는모두일치하고rejection_code는모두null, clarification은모두null이다. 시간 조건3개·이동 표현2개를수용했다. 특정역이라는위치기준과개인출발지미상이라는차이가표현에보존됐고출발지·거리·실제이동가능여부를발명하지않았다. 장소/이동의실충족은확인되지않았다.

## 실제 요청·시간·사용량

| 항목 | 관측 |
|---|---|
| 요청 모델/endpoint | `gpt-6-luna`, `https://api.openai.com/v1/responses` |
| 모델snapshot ID | 별도로기록하지않음. 요청모델명과resolvedsnapshot을혼동하지않음 |
| 실제 client Call / 헤더시작 / 헤더완료 / 종료 |각1, 동일Call ID |
| HTTP·응답 상태 |200 / completed |
| 요청/기존adapter cap |27초 |
| 실제HTTP callTimeout |잔여26.909802540초 |
| HTTP 경과 |9.197778227초 |
| 어댑터 경과 |9.319314090초 |
| 요청 출력 상한 |기존32768 유지 |
| 정제 구조화text 크기 |974 UTF-8 bytes |
| input_tokens |2885 |
| output_tokens |787, reasoning_tokens476포함 |
| total_tokens |3672 (=2885+787) |
| 기타usage메타데이터 |cache_write_tokens2882/cached_tokens0. 전체token에다시더하지않음 |
| 실제 청구액 |미관측,0으로확정하지않음 |
| Gemini/Jev/지도/추가fallback/재시도 |각0 |

이한건은27초안에응답했다는증거이며성공률·p95·항상27초면충분하다는증거는아니다. 과거33.296초Luna단독진단이나Gemini TIMEOUT원인을해결했다고말하지않는다. 헤더전송/Call기록은클라이언트증거이며vendor청구exactly-once를증명하지않는다.

응답tee는원래읽기흐름을따르며최대262144B만메모리에복사한다. EOF완료후status/usage/output_text만정제저장하고headers·error message·reasoning items는저장하지않는다. gzip/oversize새fixture는이번에추가실행하지않았고코드검토범위다. 원파서계약통과를별도JSONSchema엔진의완전검증이나의미정확성일반화로주장하지않는다.

## 무료 사전·저장 응답 검증

호출전실제Luna어댑터와gate를loopback 정상/503/잘못된응답/250ms timeout4사례로실행했다. 각각서버수신1회, 정상DONE·오류STOP이며원래27초cap/잔여축소·bodytee출력기록을확인했다. 기존Ledger의중복Call/헤더·종료누락/실패/중복거부계약은동일해시의기존증거를재사용했고새검사로중복집계하지않는다. 최초javac의okio.Path import충돌은수정한컴파일오류이며행동RED나유료요청이아니다.

응답수신후에는provider키/live플래그제거·비루프백TCP차단상태에서**저장된동일output_text만**사용했다.

- 원래strict parseProviderResponse로다시읽은결과가liveparsedResults와동일했다. 저장형식mapper의JSON직렬화/역직렬화왕복도동일했다. 실제DB쓰기는아니다.
- 사전등록시간의모든경계가일치했다. 원 `TimeRangeMatcher`로각시간을확장하고원 `DeterministicCandidateMatcher`/`RecommendationProjector`를실행했다.
- `MatchingProcessor`의TRAVEL_CONSTRAINT→UNVERIFIED기존분기에맞춰매칭입력을조립했다. 세사람공통후보는11/24 19~20시, 대면, 장소·이동미확인참여자2명이며확정참석으로표시하지않는다. 실제장소·좌표는만들지않았다.
- projector는최대3개옵션을산출했고전체3인variant의19~20시와미확인2명표시가보존됐다. 모든3개옵션이전원참석이라는뜻은아니다.
- 별도합성매칭반례에서한사람의실제시간을옮기면전체3인후보가복원되지않았다(가능한부분참여는허용). 서로다른알려진하드장소AREA1/2/3은미확인이있어도일치후보를만들지않았다. 저장모델응답이나gold를수정한것이아니다.
- 원CandidateSummaryRenderer와message resource로아래문장을렌더했다. 실제API·UI실행이나DB확정/재조회검증은아니다.

> 11월 24일 19:00~20:00. 3명의 시간 조건이 맞지만, 일부 장소·이동 조건은 확인하지 못해 실제 대면 참석 여부는 미확정입니다.

새posthelper는입력인자조립을위한Kotlin value-class constructor접근에서javac오류1회가있었고reflection으로해결했다. 그뒤무료post실행1회exit0/PASS였다. 이컴파일오류는제품코드결함·행동RED가아니다. 유료요청은재실행하지않았다.

## 독립 검토·보존·한계

독립작업자가사전manifest3307파일·원소스910·보호22자료·새claim/SHA와단일전송guard를재검증했다. 실제응답과사전시나리오의의미도대조했고post결과·소스를읽어검토했다. 블라인드채점은아니며검토범위의P1/P2문제는발견하지못했다. 이전무료후속archive도별도해시불변이다.

증거 디렉터리: `local-only-evidence/luna-direct-one-20261031`.

- `manifest.json`, `scenario.json`, `independent-preflight.md`: 새승인·실행고정·사전검토.
- `paid/result.json`, `paid/response.json`, `paid/ledger.json`, `paid-exit.json`: 실제단일요청과정제출력/usage.
- `post-result.json`, `LunaStoredResponsePostcheck.java`, `post-exit.json`, `post.audit`: 무료저장응답재생·시간/장소반례.
- `result-summary.json`, `preservation-final.json`, `independent-result.md`: 요약·불변·독립검토.
- 새단일claim: `local-only-evidence/synthetic-authorization-d0ace57578a87106.claim`.

제품/웹소스·운영timeout·설정·보안·자격증명·DB마이그레이션변경0, 지도/추가모델호출0, 게시/push/PR/merge/deploy/Linear수정0이다. 이전2619PASS/17SKIP·웹807/브라우저결과는보존하고관련없는전체검사를반복하지않았다. 새직접진단·무료도메인재생완료범위를실제service/JDBC/normalization/주최자확정/브라우저fallback·성능·운영검증으로확대하지않는다. 현재차단은없고승인된1회는소비완료돼추가요청은허용되지않는다.

정제 실행 소스·응답·무료 검증 묶음: `local-only-evidence/meet-me-direct-luna-one-20261031.tar.gz`.
