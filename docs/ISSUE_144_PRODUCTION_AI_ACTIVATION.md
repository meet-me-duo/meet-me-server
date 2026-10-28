# #144 실제 운영 AI 활성화 계약

사용자와 지인들이 배포된 운영 서비스의 AI를 사용하는 것이 목표다. 정식 금액 정책은 추후 결정한다. 별도 테스트 모드, 임의 만료일·전역 요청 슬롯·threshold 선택을 활성화 조건으로 추가하지 않는다. 미배포 TEST_OPERATION/V19 작업은 다른 작업 공간에 보존하며 이번 릴리스에는 포함하지 않는다. V1–V18 SQL과 공개 API는 유지한다. 개선 기능과 UI 작업은 별도다.

## OA01 설정과 실행 경계

provider/routing/classifier 3개는 모두 ON 또는 모두 OFF여야 한다. 기존 budget flag는 금액 enforcement만 나타낸다. 3개 ON + budget OFF + UNCONFIGURED는 정상 운영 구성이다. budget ON은 기존 CONFIGURED 정책·quote·coverage·정산 조건을 그대로 요구한다. 금액 OFF일 때 금전 selection은 false/null로 남고 방을 금전 READY로 승격하지 않는다. 가짜 quote, cap, bill, epoch, permit을 만들지 않는다.

실제 전송 보호는 `physicalEnabled = budget.enabled || provider.enabled`로 gate와 mode port 양쪽에 동일하게 적용한다. 기존 worker/final은 execution context와 claim 기준 deadline을 계속 생성한다. TwoStage의 기존 physical 경로와 고정 모델·profile·실제 payload를 재사용한다. 기존 실행/소유권·revision·generation·방 상태·마감·허용 attempt ordinal은 전송 직전에 다시 검증한다. shared fence의 금전 coverage 검증만 금전 경로에 남기며 운영 권한 검증을 생략하지 않는다.

## OA02 금액 없는 전송 권한

`AiRuntimeDispatchGuardPort.authorize(request, at)`는 기존 PostgreSQL room/execution claim을 짧은 트랜잭션에서 잠그고 현재 유효한 실행의 deadline만 반환한다. 동일한 owner/source/context/상태 검증과 고정 5개 profile·payload hash 형식을 요구한다. 새 스키마나 전역 counter를 만들지 않는다. 실제 HTTP 동안 DB lock을 유지하지 않는다.

금액 OFF gate는 reserve/claim/observe 금전 port를 호출하지 않는다. 대신 app·network 경계에서 현재 guard를 재확인하고 반환 기한과 기존 monotonic/wall-clock cutoff를 적용한다. 그룹의 operation 선택, 한 app Call·한 network exchange, 요청 endpoint/method/bytes, 자동 retry/redirect/auth follow-up 및 non-2xx 차단은 유지한다. 기존 precheck 작업 claim·최대 실행6/동시 실행2와 final invocation 4+1 admission은 유지한다. 실행6은 HTTP6이 아니며 2단계면 최대12 HTTP다. 같은 stage를 다시 보내는 새로운 복구 경로는 추가하지 않는다.

## OA03 임계값 미선정과 기능

Jev→Luna 개인 검사와 최종 Gemini→Luna 경로를 유지한다. 운영의 Jev threshold가 없으면 유효한 Jev CLEAR도 바로 확정하지 않고 INDETERMINATE로 보내 Luna 결과로 검증한다. 가짜 threshold를 선택하지 않는다. malformed/refusal·실패 정책, 공유15초 및 기존 입력·출력 한도를 유지한다. 기존 금전 모드의 명시 threshold 요구는 그대로다.

## 검증·배포

독립 단위/실제 PostgreSQL/로컬 TLS mock으로 3ON+budgetOFF, 기존 방과 신규 방, 철회·stale·마감·경합·재전송·503 follow-up·다른 profile/payload·시간 경계를 검증한다. 금전 모드 회귀·결함 주입·독립 리뷰·필수 CI를 통과한 뒤 PR/main/배포를 진행한다. 운영 AI 활성화와 별도 유료 검증 호출을 구분한다. 새 키 등록·IAM/인증 변경은 대상·영향을 따로 보고하고 승인 전 실행하지 않는다.
