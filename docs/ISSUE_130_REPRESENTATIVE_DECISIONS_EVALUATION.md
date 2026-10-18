# #130 대표 Decisions 평가: 도구 검증과 단회 중단 기록

2026-10-18 UTC. 이 문서는 실제 평가 대상과 기록 게시 커밋을 구별한다. 대상은 production HEAD `3982535765e878875a901dd9d28352358e594f6f`와 미게시 평가 helper Java `7bf79d8cf266e9af84aa292840228535eb117f841d855e0f2e83fcf7a1a2452f`/Python `5933f3c7bd8d1508dafe26089304a336fb17064328fa996377d5ccda18bdec15`다. 이후 게시 HEAD를 실제 실행 당시 HEAD로 소급하지 않는다.

독립 담당자가 별도 supplemental 8대조쌍 16개 전원을 순서·정답과 함께 사전 봉인했다. 기존 개발60/holdout60은 선택0·불변이다. 균형 대조 진단이며 운영 분포의 무작위 표본이 아니다. root·구현자·리뷰어는 선택 원문·개별 정답·비공개 index를 수동 열람하지 않았다. 입력 준비는 현재 production serializer만 사용했으며 16 wire는 2386–2503 bytes/총39006 bytes·0600이었다.

## 도구 검증

독립 RED 뒤 최소 helper 구현을 진행했다. 첫100 실행의 98PASS/author fixture 오류2는 원본과 정확한 보정 차이를 보존했다. 프로세스 시작으로 제한시간을 소진하면 자식 종료가 빠질 수 있는 결함은 별도 유효 assertion RED 뒤 최소 수정했다. 파일 생성 후 chmod 전 노출 창도 별도 RED로 재현하고 생성 시점 POSIX0600으로 수정했다. 기존 기대값을 약화하지 않았다.

최종 합성102/102 PASS, 독립 결함 주입7종/실제 assertion8 탐지·각 정확 복구를 확인했다. 실제 Python CLI→javac→child Java→production classifier/parser/TwoStage→canonical URL의 loopback TLS 합성16개도 통과했다. 동일 계획 재실행은 추가 HTTP0, 무인자 dry run은 network0이다. 합성 실행 시간은 실제 모델 지연이 아니다. 근거는 `.tdd/verification/issue130-harness-{final-green,independent-faults,whole-cli,independent-final}.json`이다.

## 사전 실행 경계

고정 A 최대16, 순차1, 사례15초·wrapper 시작/컴파일을 포함한 전체300초, request/response 각16KiB, 원문500자, count/B/retry/redirect/fallback0이다. 기술 오류·거절·usage 불명·시간/예산/지문 실패는 전체 STOP, 의미 오답·낮은 확신만으로 표본을 바꾸거나 중단하지 않는다. 실제 TwoStage의 B_REQUIRED_NOT_RUN은 생성 질문 성공을 뜻하지 않는다. 0.8은 기존 진단 fixture이며 운영 threshold는 null/OFF다.

실행 계획 SHA `b381bb4cd2b7a043d04a900bdb2b213a53e5b51e8f415229b7a80e5bff9a585b`, 독립 실행전 ACCEPTED SHA `9de93a0af1af9595ba4c4fb29cf32290fde3867934059784c26c0c3281a73dca`를 고정했다. 현재 source235·compiled/runtime/공개 사전규약 등1148파일을 검증했다. 공개 사전규약 manifest `863c039f2a8ee9d7bdf0043de0b4ee4f7ad39e4f5b3c8ec9c61f1cd31a948682`, prepared index SHA `815054af175b5580270c0ae4d7694533b6b25b5673a3b54b7fce4aa60c6410cf`다. 개인정보 원본은 저장소에 게시하지 않는다.

## 실제 단회 결과: 연결 중단, 의미 평가 불가

root가 정확 계획으로 한 번 실행했다. 첫 사례 STARTED/UNKNOWN_HELD 뒤 CONNECTION으로 중단했고, 바깥 wrapper는 CONFIGURATION으로 보고했다. 두 실패 수준을 구별한다. 관측 network interceptor 진입0·HTTP status0·응답0·분류0, 나머지15는 UNRUN이다. interceptor 이전 실패에서 이0은 공급자 도달/청구0 증명이 아니다. 추가 호출·재시도는 하지 않았다.

raw 의미/정규화 routing의 채점 분모는 각각0이다. 계획 분모16을 유지하면서 error1/UNRUN15를 별도 보고한다. 오탐·미탐·정확도·질문 품질을 계산하지 않는다. 기존 실제 단일 사례 raw CLEAR/0.06 불일치와 과거 성공/실패 기록도 그대로 남으며 새 평가 성공으로 대체하지 않는다.

관측 시간은 실패 사례의 A+local route 합산100.397499ms, JVM suite541.536936ms, root process4387.52ms다. 모델 응답 지연이나 stage별 시간으로 해석하지 않으며 p50/p95는 미산출이다. 실행 후 같은 JDK의 기본 ProxySelector가 DIRECT이며 DNS 조회가 UnknownHostException인 것을 비유료로 확인했다. 기존 환경 proxy를 사용한 Python 무인증 HEAD는 HTTP421을 받았으나 이는 gateway일 수 있어 모델 가용성 증명이 아니다. 원래 실패의 정확 exception 단계·공급자 도달 여부를 소급 확정하지 않는다.

## 비용과 남은 범위

기존 UNKNOWN 포함 예약 USD1.33185536을 보존하고 실행 전 16×USD0.01441792=USD0.23068672를 추가 예약했다. **합계 USD1.56254208·계획 여유 USD0.43745792·환급0**을 유지한다. 새 usage는 전부 불명이며 응답 없는 STARTED 사례를 무료로 처리하지 않는다. 65536 input token과 2.2배는 inherited engineering proxy로서 공식 invoice hard cap 증명이 아니다. [비용 사전 독립 검토](verification/issue130-representative-decisions-cost-review.md)를 따른다.

독립 결과 집계 SHA `a1d407aa6de6d9920bba4a87e90e3cb168d97cde6d143138f040634ba8e96e7c`는 분류 분모0과 exposure UNKNOWN을 확인했다. 이 결과 감사는 추가 실제 실행의 승인 근거가 아니다. 대표 의미 평가·운영 임계값/실제 가격·전체 앱에서 실모델과 금융 authority 관통·운영 활성화는 미완료이며 #123을 닫지 않는다. CI에는 키를 비운102와 별도 proxy63 합성 검증을 연결했다. 최초 helper의 필수 guard는 ktlintCheck/assemble/test 및 hook self-tests exit0·새214 suites/1717중1713PASS+기존4SKIP·source235전후동일(454.85초)로 완료했다. 후속 proxy source의 필수 guard도 exit0/source566 전후동일(11.398초)로 통과했다. Gradle은 기존 XML1717(1713PASS/기존4SKIP)을 재사용했으므로 새로운 fresh1717 실행으로 부르지 않는다. 새 proxy 독립63+기존102/FI8은 별도 실제 실행 결과다. 게시 리뷰·commit/push·정확 HEAD CI는 아직 미완료다.

실행 후 독립 결과 감사도 보고 정합 PASS·품질평가 불가로 봉인했다(SHA8396cba8f8d7b3aa72bdb0cd999ee53c56360388ed50146217724ddde173c966). 추가 실행을 허용한 판정이 아니다.


## 환경 proxy 보완과 TLS 확인 대기

환경별 차이를 평가 도구에 한정해 명시적 `transportMode: ENVIRONMENT_HTTPS_PROXY`로 보완했다. 기존 실행은 변경하지 않으며 새 mode만 엄격한 HTTP CONNECT 환경 proxy를 사용한다. malformed/빈 하위 env·userinfo·query/fragment·허용하지 않은 scheme은 연결 전 거부하고 DIRECT fallback·재시도·redirect는 없다. 현재 helper Java SHA c2439ff232fa2eda252c903ebd4cda00f51685c0b1aeca48a11bcce515c5ffdc, Python SHA b2f01a76fc2384f29c8eecf4658ff4f41f9096d844822a005f26213914c3f409다. 원본 helper102 source/evidence는 `.tdd/history/issue130-environment-proxy/original-102/`에 보존했다.

새 독립63은 valid assertion RED42/controls21 뒤63 GREEN, 기존102 unchanged GREEN이며 독립 FI8 모두 assertion 탐지/정확 복구 GREEN이다. 새 mode는 실제 첫 N16 예약 증거의 SHA/상태/정수 refunds0를 요구하고 previous USD1.56254208을 고정한다. 과거1.33185536으로 되돌린 계획을 거부한다. 새16회 최대 예약 제안은0.23068672를 더해1.79322880/여유0.20677120이며 아직 예약하거나 실행하지 않았다. 선택 원문·gold·순서·의미 oracle 변경0이다.

같은 새 factory에서 Authorization/body/key 없이 HEAD 최대1회만 실행한 결과 proxy DNS/connect/TLS start는 관측했으나 `SSLHandshakeException`으로 TLS 미완료·HTTP status 미관측이었다. 상세 nested cause/peer chain은 기록하지 않아 누락 CA가 정확 원인이라고 확정하지 않는다. 공개 CA 메타데이터만 비교한 결과 기존 JDK118·환경 bundle154·공통112/환경만42/JDK만6이며 합집합160이다. 기존 공개 환경 CA를 명시적 지문으로 평가 client 한정 상속하는 방안은 신뢰 anchor 확장이므로 보안 설정 제외 승인 범위에서 자동 진행하지 않는다. 해당 선택에 대한 명시 답변 대기이며 CA 코드·전역 JDK/OS/운영 설정 변경·추가 유료 호출0이다.

승인 시에도 기존 chain/hostname 검증을 유지하고 새 독립 TLS 양성/음성·오염 bundle 거절·기존 default roots 보존 검사와 FI, 무인증 확인, 최신 source/runtime/비용/실행전 독립 리뷰를 거친 새 single-use 계획만 실행할 수 있다. 답변 전에는 현재 hold1.56254208/여유0.43745792/환급0을 그대로 유지한다. 실제 오탐·미탐/정확도/질문 품질/p50/p95는 여전히 미측정이다.
