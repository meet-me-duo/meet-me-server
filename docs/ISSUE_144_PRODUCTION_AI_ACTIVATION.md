# #144 실제 운영 AI 활성화 계약

사용자와 지인들이 배포된 운영 서비스의 AI를 사용하는 것이 목표다. 최신 사용자 승인은 비용 정책 미설정과 금액 상한 초과를 이유로 방 생성·개인 검사·최종 분석을 차단하지 않는 것이다. 정식 금액 정책은 추후 결정한다. 별도 테스트 모드, 임의 만료일·전역 요청 슬롯·threshold 선택을 활성화 조건으로 추가하지 않는다. 미배포 TEST_OPERATION/V19 작업은 다른 작업 공간에 보존하며 이번 릴리스에는 포함하지 않는다. AI는 V1–V18 SQL과 공개 API를 변경하지 않는다. 개선 기능·UI·리뷰 작업은 별도다.

## OA01 설정과 실행 경계

provider/routing/classifier 3개는 모두 ON 또는 모두 OFF여야 한다. 예산 flag만으로 불완전한 공급자 구성을 활성화하지 않는다. 세 기능이 ON이면 budget flag가 OFF 또는 ON이어도 금액 정책 미설정과 소진된 cap은 분석 차단 조건이 아니다. 운영 selection은 금전 권한을 발급하지 않으며 명시 제공된 유효 정책은 metadata로만 유지한다. 기존 방의 원장을 초기화하거나 신규 방을 금전 READY로 승격하지 않는다. 가짜 quote, cap, bill, epoch, permit을 만들지 않는다.

명시 제공 artifact의 구조·해시·시작 시 유효시각과 제공된 threshold의 충돌 검사는 유지한다. 순수 금액 artifact의 시작 시 만료 검사를 비차단으로 재정의하는 범위는 현재 보류다. 실제 실행의 기한·방 생성은 시작 뒤 금액 정책 만료와 분리한다.

실제 전송 보호는 `physicalEnabled = budget.enabled || provider.enabled`로 gate와 mode port 양쪽에 동일하게 적용한다. 기존 worker/final은 execution context와 claim 기준 deadline을 계속 생성한다. TwoStage의 기존 physical 경로와 고정 모델·profile·실제 payload를 재사용한다. 기존 실행/소유권·revision·generation·방 상태·마감·허용 attempt ordinal은 전송 직전에 다시 검증한다. shared fence의 금전 coverage 검증만 금전 경로에 남기며 운영 권한 검증을 생략하지 않는다.

## OA02 금액 없는 전송 권한

`AiRuntimeDispatchGuardPort.authorize(request, at)`는 기존 PostgreSQL room/execution claim을 짧은 트랜잭션에서 잠그고 현재 유효한 실행의 deadline만 반환한다. 동일한 owner/source/context/상태 검증과 고정 5개 profile·payload hash 형식을 요구한다. 새 스키마나 전역 counter를 만들지 않는다. 실제 HTTP 동안 DB lock을 유지하지 않는다.

운영 gate는 reserve/claim/observe 금전 port를 호출하지 않는다. 대신 app·network 경계에서 현재 guard를 재확인하고 반환 기한과 기존 monotonic/wall-clock cutoff를 적용한다. 그룹의 operation 선택, 한 app Call·한 network exchange, 요청 endpoint/method/bytes, 자동 retry/redirect/auth follow-up 및 non-2xx 차단은 유지한다. 기존 precheck 작업 claim·최대 실행6/동시 실행2와 final invocation 4+1 admission은 유지한다. 실행6은 HTTP6이 아니며 2단계면 최대12 HTTP다. 같은 stage를 다시 보내는 새로운 복구 경로는 추가하지 않는다. 기존 provider·실행의 usage 관측은 유지하지만 이를 실제 청구 증명이나 원장 전체 coverage로 표시하지 않는다.

## OA03 임계값 미선정과 기능

Jev→Luna 개인 검사와 최종 Gemini→Luna 경로를 유지한다. 운영의 Jev threshold가 없으면 유효한 Jev CLEAR도 바로 확정하지 않고 INDETERMINATE로 보내 Luna 결과로 검증한다. 가짜 threshold를 선택하지 않는다. malformed/refusal·실패 정책, 공유15초 및 기존 입력·출력 한도를 유지한다. 독립된 기존 재무 컴포넌트 계약은 명시 threshold와 quote 검증을 유지한다.

## OA04 기존 재무 컴포넌트의 보안 회귀

운영 wiring은 금전 초기화 권한을 부여하지 않지만 기존 직접 컴포넌트의 INSERT receipt·savepoint·rollback·quote·bill·cap·정산 방어를 삭제하지 않는다. 테스트 전용 legacy fixture가 이 계약의 원래 기대값을 유지하고, 현재 실제 운영 graph는 별도 native PostgreSQL/TLS 검사에서 budget OFF/ON·UNCONFIGURED·READY 소진·시작 뒤 금액 만료를 검증한다. 이 fixture는 운영에 배포할 새 모드가 아니다.

## 검증·배포

독립 단위/실제 PostgreSQL/로컬 TLS mock으로 신규·기존 방, 금액 상태, 철회·stale·마감·경합·재전송·503 follow-up·다른 profile/payload·시간 경계를 검증한다. 원본16 실행과 최신 정책 승계 이후의 RED/GREEN을 구분한다. 금전 컴포넌트 회귀·결함 주입·독립 리뷰·전체 품질 게이트를 확인하고, 미완료·차단은 검증 기록에 남긴다. Cloud에서는 commit·push·PR·main/develop 변경·AWS·운영 배포·유료 검증 호출을 실행하지 않는다. 실제 활성화는 사용자가 귀가 후 개인 PC에서 이어갈 후속이다.

## 기존 Node 검사 승인과 보존

`deploy/tests/issue131-runtime-bundle-env.test.cjs`와 `deploy/tests/runtime-auth-env.test.cjs`의 기존 기대값 수정은 자동 승인 검토에 거절됐고 그 시점에는 미실행이었다. 거절 사유는 기존 회귀 oracle·sentinel 제거를 재시도하면서 새로운 관련 사용자 승인이 없다는 것이었다. 이 원본 지문·거절 사유·제안 patch는 보존한다.

2026-10-28 사용자가 정확한 두 검사 수정과 보호 검사 유지·독립 검토를 명시 승인했다. 부모 작업자가 기존 원본·미실행을 확인하고 proposal을 적용하지 않은 단 한 번의 수정으로 성공했다. 합법 UNCONFIGURED의 활성화를 검증하고 sentinel을 삭제하지 않은 채 잘못된 UNCONFIGURED 구조는 계속 거부한다. secret·기존 환경·CONFIGURED/OFF·file mode·사전 credential 검사의 원래 body를 보존한 독립 audit와 75/75 PASS/skip0을 확인했다. 이 결과는 운영 배포 승인이 아니다.

새 Python 운영 계약8개는 기존 quality CI step의 명시 unittest 명령으로 연결했다. trigger·job 권한·운영 배포 경로와 기존 검사 명령은 변경하지 않았으며 실제 push/Actions 실행은 하지 않았다.
