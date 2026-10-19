# #123 영속 공통 AI 예산 구현 계약

2026-10-18 UTC. 1단계 PR122/e83d5280은 #124의 기본 OFF adapter·bridge 준비로 고정한다. 이 문서는 #123 후속의 구현·독립 TDD 기준이며 운영 활성화나 실제 청구 상한 증명이 아니다.

## 확정 범위

- PostgreSQL이 같은 방의 누적 예약·정산·UNKNOWN 권위다. 개인 Decisions/count/generation, 최종 Gemini/Luna, 기존 HOST retry/correction/DLQ가 같은 방을 쓴다. 새 계정·revision·generation·run/version/invocation은 새 예산이 아니다.
- 새 enforcement flag 기본 OFF는 기존 final 경로의 동작을 보존한다. ON은 기존 영속 invocation/4+1 production composition을 지원하며 fallback bean 또는 bounded repository가 없는 과거 생성자 seam은 CONFIGURATION으로 거부한다. 이는 OpenAI key가 비어 있다는 이유로 정상 primary를 막는다는 뜻이 아니다. ON에서는 실제 전송 5종 모두 유효한 영속 권한과 신뢰 가능한 immutable quote가 필요하다. 실제 가격·cap·운영 profile을 새로 승인하거나 seed하지 않는다. 미구성·불명·과거 미계상 비용은 DENY다.
- 기존 개인15초/실행30초, final 전체60초/Gemini15초/Luna27초/완료3초와 기존 호출·질문 상한, 원문·500자·last CLEAR·소유권/게시 fencing을 유지한다. admission 대기는 원래 deadline을 소비한다. fence의 effective expiry는 기존 인증/binding/claim 유효기간을 포함하고 마지막 budget 잠금 대기 뒤 fresh clock으로 재검사한다. 개인 검사는 CLOSED에서 차단하지만 최종 분석은 정상 CLOSED 방의 현재 STRUCTURING run을 허용하는 기존 kind별 guard를 재사용한다.
- 방→기존 정확 check/run/claim 검증→budget 순서로 잠근다. 이미 commit된 논리 admission을 예산 거절로 환급하지 않는다. 새 물리 예약/hold/dispatch intent의 쓰기는 각 트랜잭션에서 원자적이며 HTTP 동안 DB lock을 잡지 않는다.
- 요청별 stable identity와 실제 전송 payload/API/model/profile을 결속한다. 예약 조회·replay는 전송 권한이 아니다. durable single-use dispatch CAS와 실제 전송 직전 one-shot 검증으로 같은 Call의 follow-up 및 SDK 두 번째 Call을 막는다. provider exactly-once를 주장하지 않는다. SDK lazy 경로도 캡처한 immutable bytes를 실제 송신하고 body 재평가로 바뀐 내용을 보내지 않는다. 관측 기록 false/예외는 hold를 유지하며 현재 제품 success/CLEAR로 숨기지 않는다.
- count와 generation은 각각 물리 요청·quote·usage를 갖는다. stage bundle/child의 중복 과금은 없다. token usage·완전한 usage·입증된 청구액을 구별하고 usage만으로 hold를 환급하지 않는다.
- 불확실한 전송·timeout·취소·crash·관측 실패는 full hold를 유지한다. late usage는 원래 요청에만 붙고 새 accepted/frozen/winner를 바꾸지 않는다. 검증된 완전 청구 근거만 정산하며 충돌·quote 초과는 정직하게 기록하고 추가 admission을 막는다. NUMERIC38의 기술 저장 범위를 넘는 bill 또는 누적합은 SQL 쓰기 전에 탐지해 FROZEN·UNKNOWN hold·불투명 ACCOUNTING 근거로 남기며, 잘라 저장하거나 SQL overflow rollback 뒤 READY로 남기지 않는다.

## 과거 이력과 삭제

V17 이전 방 또는 enforcement OFF 동안 미계상 호출이 가능한 방을 spent0으로 활성화하지 않는다. V17 이후 생성됐다는 사실만으로 fresh 증거가 되지 않는다. 명시적 lineage/coverage 근거 없는 초기화·설정 ON/OFF 전환·재시작은 UNKNOWN을 해제하지 않는다. 새 방은 생성 트랜잭션에서 미구성 상태를 남기고 독립 synthetic fixture만 검증된 coverage를 제공한다. 운영 활성화·과거 UNKNOWN 해제 경로는 제공하지 않는다.

기존 생성/종료 기준30일 방 삭제 정책은 유지한다. 살아 있는 방의 마감·실패·timeout은 UNKNOWN을 제거하지 않지만, 실제 보존기간 삭제는 새 방 소유 원장도 cascade 삭제한다. 방 삭제 뒤 late 관측은 NOT_FOUND/no-op이며 방·예산·전송 권한을 재생성하지 않는다. 공유 profile은 입력/방/epoch를 포함하지 않는 구조 profile/policy/unit 정보만 보관하고, payload별 quote/evidence/금액 지문은 전부 방 소유 예약 행에서 함께 삭제한다. 이 원장은 영구 회계 보관소가 아니다. 원문·질문·답변·raw 응답/secret은 원장과 일반 로그에 저장하지 않는다.

## 독립 명세 정합성 보정

초안 DB20의 lazy bootstrap은 신규 방 생성 트랜잭션/이미 검증된 동일 lineage의 중복 초기화 멱등성으로 한정한다. 초안 DB21의 UNKNOWN 유지와 실제30일 삭제는 위처럼 구분한다. DB06의 rollback은 새 물리 예약 트랜잭션의 부분 쓰기에 적용하며 이미 commit된 기존 논리 claim/횟수를 되돌린다는 뜻이 아니다. 보정 전 초안과 독립 검토 근거를 별도 이력으로 보존한다.

## 미확정·차단 범위

실제 B/통화·FX/세금·단가/청구 cap 근거·운영 confidence·예산 소진/UNKNOWN의 참여 UX·최종 분석 몫 보호·과거 비용 reconciliation은 TBD다. 현재 공개 API/마감/참여 정책을 새로 선택하지 않는다. 내부 거절은 기존 안전한 실패/지연으로 남고 CLEAR/제출 완료·추가 retry/fallback으로 바뀌지 않는다. 합산 USD1 안의 별도 격리 [Decisions 진단4회](ISSUE_107_PRE_SUBMIT_WORKLOG.md#history-issue-123-live-decisions-diagnostic)는 byte/framing 보수 견적·독립 사전oracle로 실행했으며 의미 불일치에서 중단했다. 이 관측을 생산 quote/청구 cap 증명·운영 threshold·실제 전체2단계 검증으로 전용하지 않는다.

## 검증과 다음 단계

독립 작성자의 DB01–24 및 AD/BD/ID/US/TM/PV 명세를 공개 contract에 연결한다. real PostgreSQL의 strict `spent + hold + quote < B`, 경쟁 예약/dispatch, rollback/restart/UNKNOWN/보존 삭제와 loopback 실제 wire의 5종 coverage/중복 방지·OFF/ON 양성/음성을 검증한다. 합성 금액은 무차원 테스트 단위이고 실청구/정확도/p50/p95 증거가 아니다. RED→GREEN·FI·독립 리뷰·변경 없는 필수 guard 뒤 commit/push/Draft·정확 HEAD CI까지 진행하며 Decisions develop/main 병합·배포는 하지 않는다.
