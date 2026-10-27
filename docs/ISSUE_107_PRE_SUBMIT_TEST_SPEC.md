# #107 제출 전 확인 테스트 명세

2026-10-17 UTC. 공개 요구사항에서 도출한 준비 명세. 아직 테스트 코드/RED/GREEN/실모델 평가가 아니다. 설계 근거는 최신 사용자 합의, PRD Rule1/4, ADR-045/046/049/051/052, #108 공개 proof 계약이다. 기존 사후질문 fixture44개는 역사 oracle로 보존한다. 이 명세는 구현 테스트의 독립 작성·검토 입력이며 구현자의 자기 검증으로 독립 테스트 작성이 끝났다고 표시하지 않는다.

## 수용·경계·실패 목록

| ID | 설정/행동 | 공개 관찰 결과 |
| --- | --- | --- |
| PS01 | 제출 후 provider barrier 대기 | 원문 PG 보존, 카드 QUEUED/CHECKING. 제출 완료·다른 참여자 대기 오표시0. 저장+job/outbox 하나 |
| PS02 | 공급자 CLEAR + 완료 transaction | 정확 source만 accepted. 질문0. 카드 완료/실제 현재 방 안내. 최종 매칭 성공 보장 없음 |
| PS03 | 영향 있는 ambiguity + 안전 A/B | 본인 질문1개·직접 수정·취소. 원문 불변. 질문 제안 전용 추가 호출0 |
| PS04 | 문맥 명확·넓은 의도·상대 날짜·불가/선호 | MUST_NOT_ASK fixture에 불필요 질문 없음. regex 분류/임의 범위 축소/자동 원문변환 없음 |
| PS05 | A/B 선택·다음날 지연 답변 | 명시한 전체 해석으로 새 version1개, source→question→choice→new version. source 상대 날짜 불변. NEEDS_CHOICE 사람 대기는 실행30초와 별개이며 현재 방의 쓰기/마감 조건으로 답변 허용. 재검사 중 미완료 |
| PS06 | DIRECT·여전히 모호한 답변·복합 모호성 | 전체 수정본 보존, 새 상대 날짜는 새 created_at. 재검사 NEEDS_EDIT를 CLEAR로 올리지 않음. 안전 A/B 없을 때 선택지 발명0 |
| PS07 | 취소·미응답·브라우저 이탈 | 원문 남음, 새 완료/최종 분석 job0. reload는 실제 status 복원. 브라우저 abort와 서버 CANCEL 구분 |
| PS08 | timeout/연결실패/invalid JSON/잘못된 enum·missing ref | 실패 분류·원문·usage 불명null 보존. COMPLETE 질문없음으로 삼키지 않음. 기술실패에 의미 실패 결론 금지 |
| PS09 | 중복 클릭·같은 key/body 재전송·응답 유실 | version/check/outbox·provider admission 중복0, 최초 outcome+현재 snapshot. GET 복구 후 같은 key. GET/reload는 쓰기/호출0 |
| PS10 | 동일 key 다른 body·동일 revision 다른 탭 쓰기 |409, 최대 한 새 head. 패자 draft 보존. 성공/답변/호출 budget 변화 추가0 |
| PS11 | 옛 provider 응답이 새 수정 뒤 도착 | 원래 check만 superseded, 최신 raw/accepted/질문/방 상태 덮어쓰기0 |
| PS12 | 취소 vs clear, close vs clear/answer/retry | room lock으로 한 선형화만. deadline 정확 같음도 완료 게시 거부. 이미 완료된 작업 취소는409/current |
| PS13 | deadline 지난 직후 scheduler 전 같은 원문 재제출 | #113처럼 early same-text return 전 차단. 수정 라운드의 오래된 최초 deadline은 기존 허용 계약 유지 |
| PS14 | reload·첫 GET 지연·다른 탭 새 revision·decode 지연 | room/check version 각각 단조. 최신 서버 값과 로컬 미저장 타이핑을 구별. 오래된 성공 toast/완료 cache 복구0 |
| PS15 | 계정 전환·동일 이름 타인·로그아웃·claim·proof 복구 | query/timer/메모리 제거. 늦은 GET/POST와 JSON decode까지 이전 epoch 무시. 새 계정에 원문·질문·답변 노출0 |
| PS16 | HOST/다른 참여자/다른 방/UUID·링크만 접근 | 모든 GET/명령/replay·room·unapplied·운영 요약·오류·로그에서 타인 원문/질문/답변 비노출 |
| PS17 | 실제 #108 원래 세션·정답/오답 암호·만료/회수 | VERIFIED 본인만 허용. actual proof issuing → common resolver → guard까지 검사. mock level로 대체 완료 금지 |
| PS18 | REPLACE_ONLY 및 로그인+invalid Bearer+valid guest | 신규 검사 GET/POST/답변/취소/retry 모두 거부, invalid Bearer401. 기존 blind replace만 원계약 유지·비교 oracle0 |
| PS19 | claim vs 답변/완료/replay·owner binding 변경 | claim 우선이면 옛 grant 쓰기/결과 반영 차단. 완료 먼저면 provenance 보존·명시 연결. 타인 outcome 노출0 |
| PS20 | 500/501 emoji·trim·내부 공백·배치10000/10001 | 기존 Unicode 기준/오류. 원문 자르기0. 실패 head/집계/작업/비용카운터 불변. A/B도 같은 경계 |
| PS21 | 질문3회·검사 admission6회·retry1회·중복/취소/revision 초기화 공격 | 영속 상한 우회0. 상한시 원문 보존·불가 사유, 거짓 완료0. V13 질문 이력 포함. count preflight 경로는 admission당 count1+generation1 이하이며 전체HTTP6이라는 주장은 하지 않음 |
| PS22 | 큐100 포화·동시 worker·Redis 유실/재시작 | 저장 raw 유실0, FAILED_QUEUE 또는 내구 job 복구. 전역 in-flight≤2. 최종분석 풀 고갈0 |
| PS23 | 중복 전달·claim 후 crash·lease 만료·늦은 결과 | 동일 execution attempts/deadline 불변, 무허가 재호출0. 명시 retry는 새 execution/generation 한 번만 허용하고 옛 응답 폐기. 외부 불확실 호출도 비용상한 차감 유지. expired 결과 accepted0 |
| PS24 | queue wait 포함30초·실제 HTTP 느린 본문 | 남은시간-게시3초, call≤15초, 전체deadline 후 게시0. 가짜 clock/backoff와 transport cancel로 검증 |
| PS25 | check에서 장소 AREA1이 같은 두 개인 결과 | 두 AREA1 단순 합집합/교집합/캐시 재사용0. 마감 후 전체 frozen batch를 다시 그룹화·결정론 매칭 |
| PS26 | 새 check 후 최종분석·수정분석 | check가 correction3회/최종60초·4+1을 차감·초기화하지 않음. 총 비용은 purpose별 합계, usage 없으면null |
| PS27 | 구 V13 기록·구 OPEN round·확정·새 protocol 방 | 구 데이터/결과 불변, 사후질문 중복 생성0, 새 기본흐름 HOST 연락/열기/재분석 의존0 |
| PS28 | capability 불일치·404/500 질문조회·NO_MATCH 갱신 | 구 미지원과 실제 오류 구분, 오류가 질문없음/완료로 숨지 않음. NO_MATCH state 변경 반영·문구 일관 |
| PS29 | PG schema upgrade·트랜잭션 중간 실패·30일 정리 | V11~15 bytes 보존, V16+ inventory/checksum 일치. raw/job/answer/accepted atomicity, dangling FK0·retention 연장0 |
| PS30 | native OpenAPI·모바일/desktop·실제 local app | 실제 status/error/schema와 생성타입 일치. 동일 카드 loading/질문/실패/완료·접근 경계·키보드 흐름 |
| PS31 | A/B를 KST 다음날 저장한 후 마감 최종 batch/provider까지 실행 | source 원문/기준일 불변·new version created_at은 실제 새 시각·interpretation_reference_at은 옛 anchor. 최종 요청/시간 결과까지 원래 내일을 유지. DIRECT 대조군은 새 날짜 기준 |
| PS32 | 입력16KiB/output2048 tokens/body16KiB 경계·잘림 | 초과/미지원 설정은 성공/무한retry가 아닌 typed failure. reasoning 포함 상한 계약 확인·usage미확인null. room 총비용 보장으로 오해하지 않음 |

## 승인된 D1/D2 정책 oracle

| ID | 경계 | 승인된 기대 |
| --- | --- | --- |
| PD01 | N번째 raw 저장·검사대기 | collecting, 완료 인원N-1. 저장과 완료를 구분 |
| PD02 | N번째 CLEAR와 다른 참여자 쓰기 경합 | 원자 완료 인원N·마감·정확 accepted version batch, 이후 쓰기거부 |
| PD03 | 첫 원문이 NEEDS_CHOICE/실패/취소/한도인 채 시각·수동 마감 | accepted 없음→cohort 제외, 이번 결과 미반영 표시 |
| PD04 | 이전 accepted 존재·새 수정 검사 중 마감 | 직전 accepted 사용, pending 원문 보존·적용본 구분 |
| PD05 | 두 명 저장, 한 명만 CLEAR로 마감 | INSUFFICIENT_PARTICIPANTS, 가능 시간 추정0 |
| PD06 | 기존 REPLACE_ONLY blind 대체 | 실제 wire {replaced:true}·제한 권한 보존. 신규 방은 candidate 저장만 먼저 하고 CLEAR 뒤 반영. 읽기/질문 권한 승격0. grant candidate revision+generation CAS로 중복/동시 대체 fence |

D2는 실패/무응답/취소/상한 모두와 이전 accepted 존재 여부의 직교 표로 확장한다. CLOSED에서 명시적으로 열린 기존 correction round는 기존 편집 계약을 유지하며 초기 수집 완료 집계를 새로 만들지 않는다.

## 의미 평가와 구현 검증 분리

신규 [합성 평가 목록](fixtures/issue-107-pre-submit-evaluation.json)은 MUST_ASK와 MUST_NOT_ASK를 분리한다. 기대값은 최신 사용자 합의와 공개 의미로 도출하며 응답을 보고 수정하지 않는다. broad/명확 대조군의 불필요 질문율과 모호 대조군의 누락률·선택지의 다른 조건/날짜/극성 보존을 독립 평가한다. 빈 결과/실패/NEEDS_EDIT를 CLEAR로 집계하지 않는다. 기대 수치·정확도 목표를 관측 전에 통과 실적으로 쓰지 않는다.

기존44개 fixture의 `additional_ai_calls=0`는 사후배치 기준이다. 새 검사 호출수 oracle에 복사하지 않는다. synthetic provider가 정답을 반환하는 테스트는 **그 응답을 안전히 처리하는지**만 증명한다. 실제 모델의 잘못된 CLEAR/불필요 질문을 server validator가 의미상 완전히 검출한다고 주장하지 않는다.

현 단계 actual calls0. 유료 평가 계획이 필요하면 이 고정 입력 목록과 반복 횟수·총 호출수, 입출력 token 상한, 당시 공식 가격/환산 기준, 최대 비용, 실패/비용/시간 중단 조건을 별도 보고한 뒤 승인 대기한다. 가격·p50/p95·정확도는 현재 미측정이다.

## 실행·결함 주입·독립 리뷰

테스트 작성자는 구현과 분리한다. 순수 상태/port → 실제 PG(Testcontainers, 두 connection+barrier) → provider transport synthetic → 실제 RS256/proof HTTP → mock browser → 격리 실제 backend/browser 순서. timeout/DB/컴파일 오류는 유효한 요구사항 RED가 아니다. 테스트 fingerprint가 바뀌면 RED 재수집. 원본 로그는 `.codex/tdd-evidence`, 추적 요약은 `.tdd/red/issue-107-pre-submit-*.json`.

필수 주입: source CAS 제거(PS10/11), deadline 검사 제거(PS12/13), owner/replay 인증 제거(PS16~19), UUID 무시(PS09), cancel/실패 CLEAR 승격(PS07/08), attempt 재설정(PS21/23), 최종 batch에서 source 날짜 anchor 누락(PS31), body/token 상한 누락(PS32), UI epoch 제거(PS14/15), AREA 재사용(PS25), 오류 숨김(PS28). 매 건 유효 assertion 실패·새 실행 로그·원본 exact 복원·최종 GREEN 확인. compile 오류/오래된 XML은 탐지 근거에서 제외한다.

최종 검토는 구현과 분리하고 정책 oracle 누락·privacy·날짜·목록의 양성/음성 대조군을 확인한다. 서버 기존 guard와 전체 gate, 웹 필수 gate·고정 build browser, 정확 HEAD CI까지 끝나야 게시 범위를 완료로 기록한다. 현재 명세 검토는 이 최종 구현 검증을 대체하지 않는다.
