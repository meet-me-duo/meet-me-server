# #107 제출 전 확인 독립 설계 검토

2026-10-17 UTC. 구현과 분리한 요구사항 검토이며 제품 코드·테스트·정책을 변경하지 않았다. 검토 대상은 `ISSUE_107_PRE_SUBMIT_DESIGN.md`, `ISSUE_107_PRE_SUBMIT_TEST_SPEC.md`, `docs/fixtures/issue-107-pre-submit-evaluation.json`, 전용 WORKLOG 및 웹 `ISSUE_107_PRE_SUBMIT_HANDOFF.md`다. AGENTS·관련 규칙·project-architecture 스킬, PRD Rule4/FR-008D, ADR-045/046/049/051/052 및 지정된 원격 커밋의 #108/#110 인계를 대조했다.

## 판정

**보완 후 설계 준비 단계 통과. 미해결 P1/P2 없음. 구현 승인·배포 승인·실모델 품질 검증 판정이 아니다.** 초기 P2 세 항목과 재검토 결과를 아래에 보존한다. D1/D2는 사용자 참여·마감·cohort를 바꾸는 미결 정책으로 유지한다. accepted pointer·집계·동결 batch를 선택안대로 구현하는 것은 이번 리뷰의 허용 범위가 아니다.

## 초기 발견 사항 (아래 재검토에서 모두 해소)

### P2 R1 — 검사 실행 deadline과 사람의 답변/명시 재시도 수명을 구분해야 함

- 근거: 설계 50·86·90행은 TIMED_OUT의 허용된 명시 재시도, check당 retry1회, enqueue 시 시작하는 check 절대30초/예산 초기화 금지를 함께 제안한다. PS05는 다음날 A/B 답변도 요구한다.
- 영향: 동일 check deadline을 모든 후속 명령에 그대로 적용하면 timeout 뒤 retry는 호출 가능 시간이 없고, 30초 뒤 질문 답변도 닫힐 수 있다. 반대로 임의로 deadline을 초기화하면 중복 전달/재시작에서 예산을 복구하는 결함과 구별되지 않는다.
- 개선: provider 실행 단위·question 수명·명시 retry의 새 실행 단위를 요구사항으로 분리한다. 명시 retry가 새 deadline을 얻는지, 얻는 경우 영속 retry/call 상한과 원래 실행 fencing을 어떻게 보존하는지 명시한다. 사람의 질문 대기에는 실행30초를 적용하지 않고 현재 방/수정 가능 계약을 적용한다는 기대도 고정한다. `timeout→명시 retry`, `같은 retry key replay`, `옛 실행 지연 결과`, `질문 생성30초 후 답변`을 독립 oracle로 추가한다. 구체 값은 안전한 내부 설계로 정할 수 있으며 방 마감 연장으로 해소하지 않는다.

### P2 R2 — A/B의 고정 날짜를 최종 배치 파싱까지 검증하는 oracle 누락

- 근거: 설계69행/PS05는 source 상대 날짜 불변을 요구하나 새 immutable revision을 만든다. 설계95행 이후는 최종 frozen batch 전체를 다시 구조화한다. 기존 FR-008D는 새 수정 version의 생성일을 상대 날짜 기준으로 사용하며, 기존 #107 계약15행은 A/B에 source 기준으로 해소한 **절대 날짜**를 사용한다.
- 영향: source가 10월17일의 `내일 7~8시 가능`이고 다음날 답해 `내일 오전7~8시 가능`이 새 원문으로 저장되면, 질문/재검사 단계에서 source 날짜를 보존하더라도 최종 분석이 10월19일로 이동시킬 수 있다. 현재 요구의 의도는 보존이지만 단계 간 증거가 부족하다.
- 개선: 기존 계약처럼 A/B의 명시 표시·확정 대상 전체 제안에 source 기준 절대 날짜를 담는 요구를 유지하거나, FR-008D와 일치하는 다른 명시 계약을 기록한다. 원문을 서버가 자동 변환하는 방식으로 해결하지 않는다. `다음날 선택→새 version→CLEAR→마감→최종 provider 입력/매칭`까지 10월18일이 유지됨을 PS05에 연결하고, DIRECT의 새 기준일 대조도 둔다.

### P2 R3 — 물리 호출 상한 외 호출당 출력/응답 크기 경계 필요

- 근거: 설계86~92행은 호출6회·retry·동시성·시간/큐 상한을 두지만 provider 최대 출력 token, 실제 HTTP body 크기, proposal/질문 크기 및 초과 응답의 처리 요구가 없다. 테스트 명세59행의 입출력 token 상한은 향후 유료 평가 계획에만 등장한다.
- 영향: 호출 횟수만으로 호출당 소비량/메모리 사용량이 제한되지는 않는다. 지나치게 긴 질문/선택지가 500자 입력 한도에 걸리는 경우에도 기술/스키마 실패와 실제 모호성을 구분해야 한다.
- 개선: 실제 호출 전에 provider request의 출력 한도와 응답 body/필드 경계를 명세하고, 초과 응답은 저장된 원문을 보존하는 분류된 실패로 처리한다. 질문/선택지를 자르거나 완료로 승격하지 않는다. 합성 transport의 큰 body/긴 proposal·usage 미상 사례를 PS08/20에 연결한다. 정확한 가격·실측 비용은 계속 미확인으로 두며 새 유료 호출은 필요 없다.

## 보완 후 재검토

- **R1 해소:** 명시 retry만 새 execution_id/generation·새 절대deadline을 원자 생성하고 이전 execution/카운터를 보존한다. replay/재전달/crash가 새 실행을 만들 수 없고 이전 generation 응답은 폐기한다. NEEDS_CHOICE의 사람 대기는 provider 실행30초와 분리돼 현재 방의 쓰기/마감/소유권 조건을 따른다. PS05/23/24에 이 구분이 반영됐다.
- **R2 해소:** 실제 새 created_at과 source에서 상속하는 interpretation_reference_at을 분리하고, final frozen batch/provider까지 anchor를 전달하도록 명시했다. 신규 protocol A/B만의 FR-008D 예외, 기존 version의 null anchor→created_at fallback, DIRECT/일반 새 입력의 새 기준일을 설계·PRD 전환절에 구분했다. PS31과 최종 batch anchor 누락 결함 주입이 추가됐다. 원문 자동 변환 요구는 없다.
- **R3 해소:** 전체 provider 입력16KiB, 출력2048 token/본문16KiB, 질문160/구절200/전체 수정본각500 코드포인트를 제안 경계로 명시했다. reasoning/thinking 포함 과금 상한을 보장할 수 없는 설정은 사전 차단한다. 초과/잘림은 INVALID_RESPONSE이며 성공이나 기술 자동retry가 아니다. PS32 및 상한 누락 결함 주입이 추가됐다. 이 숫자는 합성 검증용이며 비용/성능 실측이나 참여 정책 승인이 아니다.
- 추가된 기준 inventory는 지정 Git SHA에서 `git diff --name-only`로 서버/웹 delta 수와 모든 공유 경로 교집합을 독립 재산출해 일치함을 확인했다. merge 실행·결합 빌드·결합 CI 증거로 해석하지 않는다.

## 확인한 적절한 경계와 잔여 조건

- 저장/완료 분리, source/check CAS, 인증 후 replay, 현재 snapshot과 과거 outcome 분리, room 잠금·late result fencing, epoch/늦은 JSON 정리, 30일 삭제·V11~15 보존은 요구사항에 포함돼 있다. 기존 500/10,000 제한을 바꾸지 않았다.
- #108 PR118 `bd8818625aa51480e3a06c1fa17d3e223fa1cfbe`의 READ_OWN, invalid Bearer 우선401, 실제 proof 발급, REPLACE_ONLY blind one-shot을 재정의하지 않았다. 새 protocol 방의 blind 대체 결과를 accepted/check에 연결할지는 부모 통합 검토가 남아 있으며 자동 편입·경로 차단 어느 쪽도 이 리뷰에서 정하지 않는다.
- #110 PR119 `5a360d47722618efc67f72e6bf2fbc4c97f45e23`의 안전 요약/최소 감사·unknown=null·purpose별 관측·V14/V15 소유 경계를 존중한다. 물리 호출 시작/완료는 공급자 수신/과금 증명이 아니다.
- 호출6회와 전역동시성2/큐100/실행30초는 **합성 검증용 제안**이다. 상한 소진과 큐 지연이 실제 확인 완료를 막으므로 운영·제품의 중립적 초기값으로 승인됐다고 해석하면 안 된다. D2 직교표에 실패/무응답/취소/상한·이전 accepted 유무를 포함한 현 요구를 유지하고, 다수 동시 제출 합성 검증에서 해당 상태 분포도 보고해야 한다. 참여 의미를 바꾸는 결과는 D1/D2 선택에 따라 처리한다.
- 의미 fixture JSON은 읽기 검증 결과 30개/고유 ID30개, MUST_ASK10·MUST_NOT_ASK20이다. 넓은 주말/기간·장소 OR·부드러운 선호·범위 밖 유효 입력을 MUST_NOT_ASK로 둬 사용자 의도에 맞는다. 원본 fixture를 구현 classifier로 쓰지 않으며 합성 provider 정답은 실제 모델 오탐/미탐률 증거가 아니다. 아직 측정된 정확도/p50/p95/원 단위 비용은 없다.
- 기존 V13·구 방·OPEN correction round 보존, 새 방 protocol 고정, 최종 전체 AREA 재분류, 기존 후속 UX 패치 선별 검증과 지원 불일치 오류가 명시돼 있다. 구 writer로의 안전 rollback을 주장하지 않는다.

검증은 문서/공개 계약 대조, JSON 구조·분류 수, 지정 Git delta/공유 경로 inventory 일치 및 `git diff --check`뿐이다. 제품 테스트·RED/GREEN·새 CI·유료/운영 호출을 수행하지 않았다. 이 리뷰 파일 외 편집 없음.
