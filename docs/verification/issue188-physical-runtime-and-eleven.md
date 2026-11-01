# 운영 factory 경계와 격리 11회 검증

관련 Issue: #188. 운영 장애 복구나 V2 활성화 완료 보고가 아니다. 실제 공급자·지도 호출 없이 develop 대상 Draft PR과 일반 CI까지 검증한다.

이전 조사에서 UNCONFIGURED와 기본 생성자의 DefaultDeny 테스트를 실제 Spring 운영 그래프의 금액 차단으로 해석했다. 전체 wiring을 읽으면 이 진단은 성립하지 않는다. `5e60b4958f9fb8b7d2a487890dd8c6b39cec3d89`와 ADR-061은 개인 입력과 최종 분석 모두의 금전 권한을 기술 실행 권한에서 이미 분리했다. gate의 enabled는 budget flag OR provider flag이며 enforceBudget은 별도다. provider ON의 개인 입력은 checkPhysical로 가므로 기본 금융 거부 분기를 사용하지 않는다. 최종 분석도 같은 dispatch mode를 사용한다. 기본 생성자 단위 테스트는 운영 차단의 증거가 아니다.

이번 최소 보강은 운영 factory에서 쓰이지 않는 AiCallBudgetPort 해석과 DefaultDeny 대체 주입을 제거한다. runtime 생성자는 기술 gate를 요구하며 금융 fallback을 갖지 않는다. gate 누락·비활성화는 공급자 호출 전 CONFIGURATION으로 닫힌다. 직접 금융 생성자의 기본 거부와 명시적 예산·관측·원장 방어는 유지한다. 전역 허용 예산을 주입하지 않는다. 인증·소유권·claim·revision/generation·source/context·CAS·기한·질문 1회·개인15초·최종60초·4+1 admission과 최종 분석은 변경하지 않는다.

운영 graph 회귀는 실제 factory·Gemini SDK·기술 gate·PostgreSQL 권한 검증을 사용한다. UNCONFIGURED와 소진 원장에서도 primary 한 번, fallback 0회, 원장 불변을 단언한다. 외부 공급자 대신 canonical 이름을 검증하는 로컬 TLS 대역만 사용한다. 별도 factory 계약은 gate 누락/비활성화와 지연 생성되는 관련 없는 금융 bean 해석을 구분한다.

ActivationElevenBudget은 test source set 전용이다. 고정 상한은 Jev6·개인Luna3·최종Gemini1·최종Luna1, 합계11이다. 승인·source revision·소스/설정/시나리오 지문을 고정하고 배타적 claim과 새 ledger를 만든다. 실패·불확실 시작도 claim을 소비하며 같은 승인/ledger를 다시 열어 횟수를 초기화하지 않는다.

원래 실행 권한을 전송 등록 전에 확인한다. 한도는 HTTP headers 전 영속화하며 미완료 전송은 하나만 허용한다. 12번째·역할 초과·count·재시도 ordinal·중복·알 수 없는 완료·저장 실패는 전송을 거부한다. 첫 실제 HTTP/transport 오류는 모든 역할의 후속 전송을 중단한다. 실패/timeout을 무료 사용으로 바꾸거나 슬롯을 환급하지 않는다.

HTTP200 이후에도 스키마·의미·개인정보 검증이 ACCEPTED가 될 때까지 다음 전송을 막는다. ActivationElevenHarness는 source 재검증과 active stage를 실제 gate의 source/header 경계에 연결하고 승인된 동작·검증기를 감싼다. 잘못된 역할·중첩 실행·검증 전 재전송·검증기 예외는 닫히며 임의 예외 메시지·요청/응답·키를 증거에 넣지 않는다. 도구 자체에는 공급자 호출이나 유료 실행 entrypoint가 없다. 향후 실행에는 별도 승인된 시나리오·검증기·지문과 실제 도구 연결을 확인해야 한다.

11회는 운영 이용 한도나 원화 비용 보증이 아니다. 청구액 미관측은 null, 금액 상한 검증은 false다. 실제 Gemini 오류 후 Luna 슬롯으로 계속하지 않는다. fallback은 별도 합성 Gemini503으로 검증한다. 실제 모델·의미 품질·청구액·운영 flag 관측은 무료 대역 검증과 구분한다.

현 배포본에도 V2 설정 전달 경로가 있으므로 이 PR 배포를 활성화의 필수 선행으로 만들지 않는다. 실제 SSM과 실행 중 flag를 직접 읽지 않았다면 ON/OFF 관측 완료라 표시하지 않는다. 최종 검사·독립 검토·결함 주입·CI 결과는 완료 후 기록하며 초기 RED와 fixture 오류 원본은 로컬에 보존한다.


독립 검토·결함 주입: 기준 34 PASS, 의미 있는 결함 7/7 탐지, 복원 후 34 PASS와 검토 소스 8개 해시 일치를 확인했다. 기존 factory 복원, 역할 cap 제거, headers 전 저장 생략, validation barrier 제거, headers 경계 지문 검사 생략, 잘못된 역할 허용, 임의 예외 내용 유출을 각각 분리해 검출했다. 역할 cap 합이 11인 상태에서 총합 검사만 제거하는 동등 mutant를 검출 성과로 세지 않았다. 정제된 근거는 .tdd/verification/issue188-independent-review.json에 보존한다.


추가 반례에서 test-only 판정기가 AssertionError를 던질 때 중단 기록 누락과 오류 내용 노출을 발견했다. 독립 3 RED 후 네 callback 경계에서 Throwable을 중단·비공개 처리하도록 보완했다. 최종 포맷 지문으로 이전 helper의 3 RED와 수정 helper의 37 GREEN을 재확인했다. 최종 독립 결함 주입은 8/8 탐지이며 원본·복사본의 최종 검토 지문이 일치한다. 운영 제품 코드는 이 보완으로 추가 변경하지 않았다.

첫 전체 guard는 2,738건 중 3 FAIL·17 기존 SKIP이었다. 실패 3건은 옛 factory fixture의 금융 fallback 기대였다. 기술 gate·현재 실행 문맥을 명시하고 CLEAR·분류/HTTP 횟수·거부 기대값을 보존했다. 폐기된 금전 hold1 대신 ADR-061의 hold0·예약 요청 없음으로 더 엄격하게 검증한다. 직접 금융 테스트 26개는 변경하지 않았다. AssertionError 3개·composition 8개·factory 3개의 집중 검사는 모두 통과했다. 마지막 전체 guard는 보완된 최종 소스로 별도 실행한다.

Snapshot의 actualGemini는 연결된 FINAL_PRIMARY의 관측 headers 수이며 로컬 HTTP 대역에서도 증가한다. 이 값을 단독으로 실제 공급자 호출·청구 횟수로 바꾸어 해석하지 않는다. 이번 실제 외부 공급자·지도 호출은 0회다.


최종 원본 commit guard: ktlintCheck·assemble·test 모두 통과, 2,741건 중 2,724 PASS·기존 17 SKIP·실패/오류 0, 351 suites, 1,025.219초. 검사 동안 Kotlin/build/guard 소스 지문 불변이고 guard 자체 SHA256도 기존과 동일하다. 첫 전체 실패와 최종 성공은 별도 보존했다. 새 SKIP은 없고 이전 17개 이름도 동일하다. CI는 feature push/Draft PR 후 정확한 HEAD를 별도로 확인한다.
