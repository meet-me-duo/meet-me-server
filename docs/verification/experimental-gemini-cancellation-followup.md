# Gemini TIMEOUT 무료 원인 범위 점검 (2026-10-31)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**현재 증거는 로컬 호출 제한에 따른 취소 양상과 강하게 일치한다. Gemini/중계 경로가 그 안에 응답하지 않은 이유는 아직 알 수 없다.** 이번 후속에서는 새 공급자·지도·외부 진단 요청0회다. 종료된2회 승인 잔여1회는 사용하지 않았다. 제품 코드·timeout·보안·자격증명·운영 설정 변경과 게시·push·PR·병합·배포·Linear수정도 없다.

[직전 실험](experimental-two-call-smoke-result.md)의 첫 Gemini1회 TIMEOUT/Luna0회는 확정된 종료 결과다. 이번에는 정제된 보존 기록·실행 소스/manifest와 로컬 반례만 조사했다. 정상 모델 결과와 HTTP 오류·timeout을 섞지 않으며, 과거 기록을 새 실행 결과로 바꾸지 않는다.

## 실제 계층과 설정 비교

| 기록 | endpoint·모델·계층 | 실제 시간·결과 | 비교 한계 |
|---|---|---|---|
| 이전15초2회 | `generativelanguage.googleapis.com` generateContent / `gemini-3.8-flash`, 원 서비스 Gemini adapter·gate | 실제callTimeout14.849/14.993초, 각각14.8507/14.9934초에취소·TIMEOUT, INTERRUPTED_IO+STREAM_RESET/CANCEL | 최신과adapter소스SHA가동일하지만 입력·실행시점·실제중계상태를통제한비교는아님 |
| 최신15초1회 | 같은 모델·GenAI Java1.72.0/OkHttp4.12.0, 원adapter·gate, 합성source authority 직접진단 | 실제callTimeout14.635초, HTTP14.636575843초·adapter15.220672858초, HTTP상태없음·TIMEOUT | 앱DB/queue/worker/frontend를 거치지 않았으며 공급자 응답·usage없음 |
| 이전30초 작은SDK1회 | 같은Gemini origin/모델/SDK, 앱adapter/gate없는 별도SDK | **2.411초에HTTP503/SERVER**, 실제callTimeout29.950초 | timeout으로끝난검사가아님. prompt97B/maxOutput128로서비스payload/maxOutput32768와다름 |
| 이전Luna앱성공 | `https://api.openai.com/v1/responses`, `gpt-6-luna`, 원앱adapter/JDBC authority/gate | HTTP200, HTTP5.564초, caller26.995초/실제callTimeout26.834초 | 다른공급자·입력·실행시점. Gemini15초정상경로의증거가아님 |
| 이전Luna60초진단성공 | 같은Luna endpoint/모델, 원앱27초authority밖의 별도standalone | HTTP200, HTTP33.29557초, 설정60초 | 논리입력/출력량·실행계층·예산이다르며 timeout만바꾼통제시험이아님 |

이전15초·SDK30 manifest와 최신 manifest의 Gemini adapter소스SHA는 모두 `b1df029f77a831738dc01c73c69c223d9e23a8237a9d01cd73cbac9564d8de71`다. 최신 wire profile은 정확한 `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent` POST만 허용하며 redirect·추가Call/헤더전송도 차단한다. SDK attempts1, retrydelay0, OkHttp connection replay/redirect false는 그대로다.

Gemini는 `min(caller예산,15초)`와 SDK 호출 직전 남은 millisecond를 사용한다. Luna는같은방식의최대27초이며 직접Responses HTTP다. connect/read/write phase timeout은0이고 전체callTimeout으로제한한다. 따라서 이번14.635초는 임의로새로고정한기준이나숨은10초read timeout이아니라15초예산의잔여값이다. SDK30은그예산이30초였고실제29.950초였지만 그전에503을받았다.

허용된proxy/CA경로를그대로썼으며바꾸지않았다. 위endpoint는 검증한 클라이언트 설정·URL 제한이다. 과거와현재의물리peer·upstream route·proxy상태가동일했다는뜻은아니다. 과거실행의유효proxy/CA전체메타데이터도모두보존된것은아니다. 요청헤더완료는클라이언트가연결에썼다는증거이며Google측수신·내부queue·과금을직접증명하지않는다. 자격증명값을읽거나비교하지않았고현재binding준비상태만확인했다.

## 최신 시간선과 취소 범위

- adapter/request예산15초, 실제HTTP callTimeout14.635초, 실제HTTP종료14.636575843초다. 둘의차이는약1.576ms다.
- 헤더시작0.187947초·완료0.194514초 후, 응답헤더관측없이 CANCELED14.636060초/CALL_FAILED14.636576초였다. 실패시 `canceledAtFailure=true`, cause분류는INTERRUPTED_IO와STREAM_RESET/CANCEL이다.
- adapter전체15.220673초와실제call의차이는0.584097초다. SDK준비·전후처리·정리의합이며세부비중까지분해한측정은아니다. HTTP cap을15.22초로늘린것이아니다.
- Call시작시원래dispatch잔여14.406442825초는relative callTimeout보다0.228557175초짧다. 실제취소는그원래dispatch cutoff보다약0.229617초뒤에관측됐다.

마지막차이는 앞서이미확인한‘Call시작뒤deadline setter만바꾸면 nativewatchdog을재예약하지않음’이라는한계와맞는다. 이번에처음발견한새정책결함이나Gemini가늦은이유로취급하지않는다. 기존gate는늦은성공게시를거부하고원래TIMEOUT을보존한다. 엄밀한socket취소시각과결과수용기한은별개계약이다. 그정밀도를개선하려면후속별도설계·검증이필요하며 이번에는timer/보안gate를변경하지않았다.

## 원인표

| 가설/설명 | 판단 | 근거·남은 한계 |
|---|---|---|
| 응답을기다리던로컬호출예산이끝난양상 | 강하게일치 | actualcallTimeout과실제종료가근접, 로컬취소와INTERRUPTED_IO관측. 경쟁하는모든timer의발화ID는기록하지않아유일주체확정은아님 |
| 최신응답이503/529였음 | 확인불가 | HTTP상태/응답헤더없음. 과거작은SDK503을최신원인으로소급할수없음 |
| 30초Gemini진단도timeout이었음 | 사실과다름 | 해당실행은2.411초HTTP503. 긴대기만으로오류가사라진다는근거도아님 |
| endpoint오타·새모델·새adapter변경 | 검토한설정에서근거없음 | 고정URL/model과동일adapterSHA/manifest. 내부proxy/upstream상태동일까지보장하지않음 |
| SDK숨은재시도때문에반복전송 | 관측과맞지않음 | SDKattempts1·retry/redirectfalse, Call/헤더시작/완료/종료각1. 중계서비스내부의재시도는관측불가 |
| strict schema가이미받은결과를거절 | 최신실행에서는아님 | 응답헤더/본문·파싱결과가없고INVALID_RESPONSE가아닌TIMEOUT. 생성복잡도가대기에영향줬는지는미확인 |
| queue/workerlease/화면polling이최신실패를발생시킴 | 해당실행경로에없음 | 최신첫단계는직접adapter진단이며그컴포넌트들을거치지않음. 실제서비스전체대기시간은별도 |
| 인증·quota·과부하·공급자생성지연 | 배제불가·확정불가 | 최신HTTP상태와원격메타데이터없음. binding ready나과거503은현재인증성공·과부하증명이아님 |
| proxy/egress/원격중계지연 | 배제불가 | 요청헤더완료까지진행했고정책거부는관측되지않음. 실제upstream수신/대기·내부상태는관측불가 |
| canceled=true/CANCEL이면반드시native timeout | 불충분한추론 | 아래명시적caller취소반례에서도두표시가발생. 최신의추가INTERRUPTED_IO/시간일치증거까지함께봐야함 |

현재자료로TLS/DNS/연결이전의즉시실패만으로설명하는것은헤더완료와맞지않지만, 클라이언트이후의중계·원격단계를구분할수는없다. 코드에서Gemini에별도명시Call.cancel을하는하네스경로는없고SDK use/close와각timeout이작동한다. 그래도취소주체의고유기록이없으므로추정을확정사실로표시하지않는다.

## 새 무료 독립 반례와 기존 재현 재사용

새로실행한것은 **loopback TLS/HTTP21요청**뿐이다. 합성NO_RESPONSE서버가요청을받은것을확인한뒤driver가명시적으로Call.cancel을1회호출했다. nativecallTimeout은로컬시험용10초이며SDK/공급자호출은0이다.

- 명시취소시각193.417ms, 전체199.320ms, 서버수신1·protocol h2.
- canceled=true·CANCELED이벤트·STREAM_RESET/CANCEL을관측했다.
- INTERRUPTED_IO는없었고raw실패는StreamResetException/NETWORK였다.

따라서취소flag와reset code만으로native timer를유일원인으로삼을수없다는반례는성립한다. 이번raw반례가실제SDK TIMEOUT의전체cause signature와동일하다는주장은하지않는다. eventcallback순서를타이머주체의충분조건으로삼지도않는다. 소스와정제JSON을독립검토자가재확인했다.

기존6개JUnit메서드/7개로컬기록은직전전체회귀에서PASS였으므로재실행하지않고복사본·해시를보존했다. 빠른SDK응답, 실제15초cap, caller500ms, 원격H2CANCEL→NETWORK, execute전deadline, execute후deadline미재예약, gate늦은성공거부를포함한다. 이번새반례1회를그6개를새로실행한것처럼합산하지않는다. 최초javac1회는진단DTO의없는elapsed getter때문에컴파일실패했고수정후실행1회PASS였다. 이를행동RED나추가네트워크실행으로세지않는다.

서비스의기존batch정책은기술적TIMEOUT/NETWORK/SERVER에대해남은Gemini30초예산내재시도후예약된Luna27초를사용한다. 이번최신직접진단은그processor를실행하지않았으며, Luna0은승인된‘첫기술오류전체중단’때문이다. 따라서현재관측을서비스fallback이TIMEOUT을지원하지않는증거로삼으면안된다.

## 가장 작은 다음 결정

같은15초실호출을그대로반복하거나곧바로운영timeout을늘릴근거는없다. 새제품결함은확인하지못했고이번제품수정도없다. 정밀한취소deadline개선이필요한지와모델응답을얼마나기다릴지는분리해결정해야한다.

서비스흐름확인이우선이라면 **Gemini정상응답을선행조건으로하지않는독립Luna fallback smoke**가가장작다. 별도승인시합성Gemini503/Retry-After40초→실제`gpt-6-luna` Responses 최대1회/27초, 재시도0·실제Gemini/Jev/지도0, 한도·첫기술오류에서종료하고동일저장응답으로만무료후처리·확정재조회를한다. 입력은사전고정한합성3명, 현행schema/maxOutput32768유지, 전역단일claim과wire1회제한으로비용노출을제한한다. 실제청구액은사전확정할수없으며token관측도청구증거와구분한다. 기존잔여1회를쓰는것이아니라새승인이필요하다.

목표가Gemini대기시간가설확인이라면대안은같은서비스payload/prompt/schema/output cap으로격리SDK진단`gemini-3.8-flash` 최대1회/30초·재시도/fallback0이다. 기존작은128token출력probe와달리payload를고정해야한다. 이는앱15초/운영설정을바꾸지않는별도실험이고, 성공해도latency분포나upstream지연원인까지증명하지않는다. 두대안을묶어실행하지않으며 **이번조사에서는어느요청도승인·실행되지않았다.**

## 증거와 보존

디렉터리: `local-only-evidence/cancellation-followup-20261031`.

- `independent-comparison.md/json`: 기존실행별수치·출처·해시·독립인과검토.
- `latest-timeline-arithmetic.json`: 최신타이머/이벤트차이계산.
- `ManualCancelCounterexample.java`, `run-manual.py`, `manual-result.json`, `manual-exit.json`, `manual.audit`: 신규로컬1회증거.
- `previous-local-http2-diagnostic.json`, `previous-local-http2-junit.xml`: 이미통과한기존controls 재사용.
- `source.json`, `protected.json`, `preservation-final.json`: source910개·원본/유료장부/이전archive 등보호23개불변.

기존유료manifest3303개해시도동일하며승인claim은그대로다. 신규원격요청0·지도0, source/운영/보안변경0. 현재차단은권한거부가아니라원격이유를알려줄응답·upstream자료부재다. 무료로가능한비교·구성추적·새독립반례를완료했으며외부검증전에별도결정을기다린다.

최종 독립검토에서 중대한 인과 과장이나 요청 상한 누락은 발견되지 않았다. 정제 증거 묶음은 `local-only-evidence/meet-me-cancellation-followup-20261031.tar.gz`이다.
