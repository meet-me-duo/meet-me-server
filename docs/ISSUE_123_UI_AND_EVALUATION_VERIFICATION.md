# #123 UI 계약과 분류 평가 준비 검증

2026-10-18 UTC. 추가 유료 호출 없이 현재 서버와 웹 PR31의 계약을 대조하고, 취소 후 질문이 남는 결합 결함을 서버#128·웹#32의 별도 브랜치에서 수정했다. 기준 서버는 `18cdb5ddec4f9e09db50feb54337aae9516a0108`, 웹은 `02a68d8e50680e87dd153156e8bb7ea884a794cc`다. 원본 PR126/PR31은 Draft·미병합으로 보존했고 그 브랜치에 수정하지 않았다. 아래 후보 검증은 이 기준 위의 새 회귀·최소 수정 바이트에 대한 기록이며, 게시 SHA는 Git/PR에서 확인한다.

## 발견과 최소 수정

NEEDS_CHOICE 검사를 취소하면 persisted question이 그대로인 상태에서 서버 snapshot이 질문을 반환했고, 웹은 상태와 무관하게 질문을 활성화했다. 이 때문에 새 입력 진입이 막히거나 취소한 검사에 답변을 보내409가 날 수 있었다. 기존 테스트의 취소 fixture는 question:null이어서 이 조합을 놓쳤다.

서버는 열린 방의 유효 상태가 NEEDS_CHOICE일 때만 현재 질문을 노출하도록 projection 조건 한 곳을 보완했다. 웹도 NEEDS_CHOICE에서만 질문·답변 controls를 활성화한다. 저장된 질문을 삭제하지 않으므로 기존 질문3회 상한은 환급되지 않는다. 원문·마지막 accepted·멱등성과 소유권·revision·마감 정책,500자·검사/재시도 상한,default OFF는 바꾸지 않았다. DTO/경로·nullable 공개 계약·DB migration은 그대로다.

- 서버 독립 새7회귀: 질문 노출 assertion1 RED/통제6 PASS→최소 수정→7/7 GREEN. 표시 조건 제거 FI는 assertion1, 질문 이력 삭제 FI는 assertion5로 탐지했고 각 정확 복구 뒤7/7 GREEN이다.
- 웹 독립 새7회귀: 비활성 상태의 질문 controls assertion4 RED/통제3 초회PASS→한 줄 수정→7/7 GREEN. 기존67 포함74/74 PASS, guard 제거 FI에서4개 탐지·복원7/7 PASS다. 초반 fixture/corepack·classpath·사후 지문 검사 준비 오류는 유효 RED나 제품 실패로 계수하지 않고 원본을 별도 보존했다.
- 구체 서버 범위: [계약 감사](verification/issue128-server-contract-scope-audit.md), [독립 FI](../.tdd/verification/issue128-cancel-question-independent-faults.json). 이전 기준의 [교차 저장소 계약 감사](verification/issue123-ui-contract-baseline-audit.md)는 발견 당시 기록으로 구분한다.

## UI 시나리오와 실제 검증 범위

| 경로 | 검증 근거와 관측 |
| --- | --- |
| 원문 저장→대기 | native N01 실제 POST202·PG 저장·미완료/로딩을 구분 |
| 질문→선택 | N02 원문과 완전한 제안 표시·명시 확인→전체 입력 재검사→CLEAR |
| 직접 수정 | N03 direct_text를 실제 API로 전송·새 입력 전체 재검사→CLEAR |
| 의도된 넓은 범위 | N04는 명시 합성 CLEAR가 원문을 보존함을 검증. 모델이 실제로 CLEAR를 고른 증거는 아님 |
| 취소→새 입력 | 새N13 실제 NEEDS_CHOICE 취소200·question:null·원문 보존·옛 답변 없이 새POST202 |
| 실패→명시 재시도 | 새N14 실제 FAILED/retry_allowed→retry202→대기·미완료→합성 CLEAR/accepted·완료 |
| 새로고침·동시 수정·멱등 재전송 | N11 실제 GET·CAS 단일 승자·동일 request ID 재생 |
| 수동/시간 마감 중 늦은 결과 | N05 두 경로에서 provider를 기다리지 않고 마감·늦은 CLEAR 게시 차단 |
| 마지막 완료본 | N06 실제 frozen batch가 마지막 accepted만 포함·pending 수정 제외 |
| 소유권·게스트 proof·blind 경로 | N07–09 실제 password/HttpOnly proof·타인/HOST 비공개·REPLACE_ONLY 읽기 금지·invalid Bearer 우선 |
| 계정 변경·로그아웃·늦은 개인 응답 | N10 loopback OIDC/RSA를 사용해 계정 우선·logout 뒤 개인 상태 격리 |

원본 웹02에서는 집중unit157·기존browser22가 재통과했으며 그 source268개가 불변이었다. 이 숫자는 새 후보의 전체 실행에 합산하지 않는다. 새 후보의 표준 웹 gate는 API 정합·lint·typecheck·build와 전체unit627/58파일 PASS, 전체browser425 PASS/기존skip1/실패·flaky·retry0이다. 새7·집중74·기준157/22는 전체와 중복된다. 정확한 명령·134파일 지문은 웹 `docs/verification/issue-32-web-final-gate.json`과 `issue-32-web-source-manifest.json`을 따른다.

새 실제 native14는 브라우저→Spring→PostgreSQL18/Redis8, 실제 guest proof와 RS256 검증을 사용했다. 실제 API 관측154회·API route mock0·외부 요청0·실제 모델/OAuth provider 호출0이다. source482·복제 실행 파일1559·dependency216 및 웹 행동7파일/자산6개가 전후 동일했다. 실제 OpenAPI36paths/61schemas는 웹 snapshot과 JSON 의미가 같았다. 종료 뒤 포트18920/21/22와 컨테이너가 모두 내려갔다. [안전한 관측 요약](../.tdd/verification/issue128-native-ui.json)에 지문과 범위를 남겼다.

하네스는 **top-level Primary synthetic PreSubmissionCheckProvider**로 결과를 제공한다. 따라서 native14는 현재 UI/HTTP/PG/worker 상태 계약의 증거이며, 실제 TwoStage/classifier/provider wire·영속 금융 authority의 실행 증거가 아니다. 기존 source의 별도 합성 TwoStage/원장 테스트, 이전 fresh실모델 한 사례와도 섞지 않는다. 실제 OAuth 공급자·운영 DB·Secret·모델 정확도·오탐/미탐률·calibration·p50/p95는 이 실행 범위 밖이다.

## 사전 정답 평가 준비와 남는 경계

기존 개발60/holdout60의 원문·A gold·기존 실증과 임계값을 보존하고, 별도8대조쌍/16문장과 단계별 oracle overlay를 독립 작성·리뷰했다. 날짜·시간·장소·지시대상·부정·예외·종속조건·유연한 조건을 점검하며, 넓은 범위·유효한 종속조건 자체를 억지로 질문 대상으로 만들지 않는다. root/구현 담당에게 holdout 원문·개별 정답은 노출하지 않았다. 정확한 최종 파일·집계·채점/노출 이력·사전 봉인은 [평가 자산 manifest](evaluation/issue123-classification-evaluation/manifest.json)와 공개 README를 따른다. manifest SHA는 `1d15a4e9323bbf7f8b3c180a4fb4205c2c116c149ef269dab01a328b9ce2bed7`다. portable 구조 validator·PROTOCOL_ONLY와 DRAFT/변조 거절 검증이 통과했으며 root도 payload를 출력하지 않고 지문/실행을 재확인했다. 초안 P2 4그룹은 실제 호출 전 보정했고 원본/차이를 보존했다. 기존120은 변경하지 않았다.

이것은 정답 세트와 검증 프로토콜 준비다. 합성 PROTOCOL_ONLY 예상값은 모델 결과가 아니며 일반 정확도나 새로운 실제 분류 성적을 만들지 않는다. 정상 CLEAR/질문 금지, 필요한 질문, 안전한 직접 수정·유보, raw 오분류와 생성 단계 복구, 오류·refusal·미실행 분모를 구분한다.

이번 추가 모델 호출과 UNKNOWN 환급은0이다. 기존 누적 예약USD1.33185536/합산USD2·보수여유USD0.66814464는 그대로 유지한다. 추가 실측은 별도 작은 호출계획·사전 상한·정답/중단 조건 고정 없이 수행하지 않는다. 기존 raw CLEAR 불일치·503·15초timeout·수정 전 parser 실패는 역사적 결과로 보존한다. 운영 threshold/가격·활성화·전체 실모델 품질은 미완료이고 README 생성 이미지/편집은 #127 선행 검증 이후다. 병합·배포·운영 설정·Secret 변경은 하지 않았다. Linear는 부모 전용 동기화가 담당한다.

현재 서버 unchanged 필수 전체 guard도 exit0이다. 새 XML214 suites/1717개 중1713PASS·기존4SKIP·실패/오류0이고 source/config558 before=after=current다. 두 모델 key가 빈 값인 subprocess의 실제 호출0이며 [게이트 근거](../.tdd/verification/issue128-final-server-gate.json)에 기록했다. [최종 독립 게시 리뷰](verification/issue128-ui-evaluation-publication-final-review.md)는 LOCAL PUBLICATION PASS·미해결P1/P2 없음으로 봉인됐다. Commit/PR은 동일 후속 커밋 예정이며 원격 정확 HEAD CI는 Git 이력/PR과 로컬 게시 receipt에 남긴다.
