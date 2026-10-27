# Issue123 actual execution fence 독립 source 리뷰

2026-10-18 UTC. `/workspace/issue123-durable-budget/server`. **현 source 범위에서 신규 P1/P2 없음. 실제 DB fence source 및 확보된30 RED→94 집중 GREEN 검토 PASS.** 최종 PG lifecycle9/전체 관통1·독립 FI·변경 없는 전체 mandatory guard·게시/exact CI까지 완료했다는 판정은 아니다.

## 범위와 exact snapshot

`JdbcAiBudgetExecutionFence.kt` SHA256 `9a816338c3187805fe5bab6aefa053a0af5add45a1a04954e9e01e8538eeadd8`를 frozen #123 core/dispatch 계약, `PrecheckProcessingService`/`PrecheckOwnershipBindingAdapter`, `ActiveRunLock`/bounded invocation, `FrozenSubmissionReader`, 실제 ownership/reaccess 변경 경로와 대조했다. source/tests/SQL/git/Gradle은 변경·실행하지 않았다.

canonical `.tdd/red/issue123-real-execution-fence.json` SHA256 `7423349b6366a720b94e0c7edbab203f7ca24622423c9a1b484822fc901a1d7e`, 독립 `Issue123ExecutionFencePostgresTest.kt` SHA256 `c67870981321407aec23f3606bf59af17ba8330df92e9d5b37d5f9dacd921600`를 current 및 corrected-min2 canonical RED archive와 대조했다. 원래1인 final fixture를 현재2인 cohort oracle과 혼합하지 않는다.

## ownership·개인 검사

- 최초 room FOR UPDATE 뒤 현재 room으로 제한한 check/candidate/execution join에서 check와 해당 generation execution을 잠근다. foreign check ID로 다른 방 check row를 잠그지 않는다. budget은 core가 마지막에 잠근다.
- PRE_SUBMIT_V1/COLLECTING, 개인 operation/ordinal1/attempt null과 count+generation bundle 형태를 검사한다. CHECKING/current version/generation 및 check·execution 두 ownerToken, 미완료 상태, claimed/started 존재를 확인한다.
- participant별 최신 candidate revision을 재조회하고 실제 participant.room과 현재 `bindings.isActive`를 확인한다. 새 candidate·guest→account claim·다른 user binding은 원래 check 권한이 아니다. 기존 계정 binding에는 새 JWT/게스트 만료 정책을 만들지 않는다.
- guest는 캡처된 provenance expiry와 현재 session expiry/revoked 상태, 사용한 재접근 grant의 room/session/participant/generation/level 및 expiry를 재확인한다. 유효 기한을 check/execution deadline−completion3초, claimed/start+15초, room submission deadline, guest session/grant/captured expiry의 최소값으로 전달한다. 따라서 core의 마지막 budget lock 후 fresh check에서 인증/binding window도 다시 적용된다. bindings 이외에 새 proof나 Secret을 발급하지 않는다.
- raw와 해석 referenceAt/zone/locale/search range를 DB의 immutable candidate에서 다시 hash한다. caller context만으로 허가하지 않고 trim·불용어 제거·원문 변환을 도입하지 않는다.

## 최종 분석·ordinal·시간

- 정상 CLOSED 방을 일괄 거부하지 않는다. 요청 room의 active run, STRUCTURING/current version, 실제 invocation run_version/ownerToken, unfinished/no-winner/bounded policy를 검사한다. 기존 현재 run/상태 fencing을 사용하며 hardcoded generation0이나0불가 조건이 없다. generation=version=실제runVersion이어서 실제0 fixture와 후속version 양성을 모두 표현한다.
- attempt를 exact invocation/run/ID로 제한해 잠그고 provider/model/policy/unfinished를 확인한다. primary1..4/fallback1만 허용한다. provider ordinal은 전역 attempt.number가 아니라 해당 invocation/provider 안에서 현재 attempt까지의 개수를 계산해 admitted provider count와 비교한다. 후속 invocation의 global attempt7도 primary ordinal1이다. 새 attempt를 만드는 fence가 아니다.
- primary는 invocation deadline−30초(fallback27+completion3), attempt.startedAt+15초의 최소값이다. fallback은 invocation deadline−3초, attempt.startedAt+27초의 최소값이다. fresh max(at,Clock)와 비교하고 core에 이 wall deadline을 전달한다. caller가 새 duration/context cutoff를 주어 persisted window를 늘리지 못한다.

## frozen cohort와 coverage

- final은 현재 run의 batch items에서 exact historical version/head를 읽는다. 최소2개 frozen items, distinct participant/version, 같은 room을 먼저 검사한다. accepted-version anchor는 V16 unique 제약을 사용하고 anchor의 room/participant도 일치해야 한다.
- `FrozenSubmissionReader`와 같은 Kotlin UUID 정렬을 적용한 뒤 raw null인 수동 입력만 natural request에서 제외한다. 따라서 수동+자연어2명 cohort를 자연어1개라는 이유로 거부하지 않으며 최종 전체 해석을 개인 결과 합치기로 대체하지 않는다.
- natural input_ref는 exact frozen version UUID, 원문·locale·해석 anchor(accepted precheck anchor, 없으면 해당 version.createdAt)의 같은 local date를 사용한다. 실제 worker/Processor와 shared length-prefixed fingerprint를 사용해 source/context를 대조한다. 최신 head로 historical batch를 바꾸지 않는다.
- personal coverage는 DB execution queued_at, final은 실제 invocation.started_at을 query에 전달한다. registry의 caller epoch/시작시각을 믿거나 V17 출생을 proof로 삼지 않는다. `JdbcDurableAiBudgetRepository.trackedEpoch`는 해당 room READY 및 coverage_started_at<=실제 시작을 확인하고, core가 budget lock 뒤 READY/sameepoch를 다시 검사한다. READY/금액/가격/과거 UNKNOWN 해제 경로를 만들지 않는다.

## RED/GREEN 직접 확인

검토 도중 부모가 전달한 “94 실행 중” 상태에서 실제 완료 아티팩트가 생성되어 직접 검증했다.

- corrected min2 `.codex/tdd-evidence/issue123-real-execution-fence-cohort-red-retry/`의 frozen test SHA가 canonical c678…와 일치하고 source-before/source-after가 동일하다. XML은30 assertion FAIL/errors0/skips0이며 public fence의 TDD TODO 도달을 assertDoesNotThrow가 탐지한 요구 RED다. 앞선 DI setup failure/1인 cohort 기록을 이 RED로 세지 않는다.
- `.codex/tdd-evidence/issue123-budget-fence-first-green/exit-status`는0이다. XML10개를 직접 합산해 **94 tests / failures0 / errors0 / skipped0**을 확인했다. 이는 실제 fence30+기존 core/보완64이며 source authority/coverage 일부는 명시적 synthetic seam이다. live billed authority 증거가 아니다.
- first-green source inventory475개가 before=after=current이며 manifest의 test11개 SHA도 current와 모두 일치했다. 기존 accounting/foreign/default-authority 보완 GREEN도 이94 안에 포함되어 이전 source-only 보완 리뷰의 실행 확인 근거가 된다. FI/전체 guard는 여전히 별도다.

Evidence SHA256:

- first-green manifest `d3e55bd4d35e1265e5c76d9029ff6576a567004f174df79ff696227abb80de09`
- first-green source-before/source-after(동일) `fe519fa80f959f11bd650f7d7d083c0e6fb88368da033b04e46c0579f158675b`
- first-green run.log `c50fd0a6cb81d0e3c766dfde589ca26d4001f32089166efae4236f26229bf8f8`

## 한계와 다음 단계

소유권·수정·마감이 HTTP와 분산 원자적이라는 주장은 하지 않는다. 이 fence는 기존 room/claim locks 안의 현재 권한을 검사하고 core의 마지막 budget lock 및 gate의 전송 직전 deadline/immutable one-shot 검증과 함께 작동한다. after-intent UNKNOWN/fullhold, 늦은 usage와 product publication의 분리는 기존 별도 검토 계약을 유지한다.

최종 실제 PG9 및 개인 A/count/B→자동마감→전체 final의 같은 epoch 관통1, 독립 FI와 full unchanged guard가 남아 있다. 이번 review가 그 경로를 실행하거나 승인했다고 표현하지 않는다. 새로운 참여/마감·30일 보존·실제 통화/가격/금액·운영 활성화 정책을 고르지 않았다.

외부 모델 호출 수는 부모가 별도로 실행·보고한 **격리 Decisions smoke1회**로 갱신된 현재 프로젝트 사실과 구분한다. 이 PG 집중 cycle과 본 read-only 리뷰의 actual external model calls는0이다. smoke는 영속원장의 실제금액/quality/latency 통계 proof가 아니고 운영 기본 OFF·DENY는 유지된다. key/계정/SSM/OAuth 조회0, source/test/git 변경0, Gradle 실행0이다.
