# V2 라벨 중심 분기와 Jev 출력 형식 이력 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

사용자 승인 `synthetic-authorization-cffef69a5db86b9e` 범위에서 격리 V2만 수정하고 무료 검증했다. Choice를 유지했으며 유료 모델 호출·운영 적용·병합은 없다. [ADR-065](../ADR.md)와 [V2 계약](../EXPERIMENTAL_INPUT_EVIDENCE.md)이 현재 정책을 기록한다.

## 구현 결과

| V2 단계 | 유효 의미 라벨 | 동작 |
|---|---|---|
| 최초 입력 | CLEAR | 개인 생성 없이 확정 접수 |
| 최초 입력 | NEEDS_REVIEW 또는 INDETERMINATE | Luna가 재확인. CLEAR면 접수하고 필요하면 질문1회 |
| 보완 답변 후 | CLEAR | 확정 접수, 추가 Luna0 |
| 보완 답변 후 | NEEDS_REVIEW 또는 INDETERMINATE | 추가 Luna0, 미해결 정보를 접수·동결해 최종 분석에 포함 |
| 모든 단계 | 잘못된 응답·기술 실패 | 새 정상 접수로 위장하지 않음 |

`JevAmbiguityClassifier`는 V2에서 confidence가 낮거나 최대 확률이 동점이어도 검증된 Choice 의미 라벨을 보존한다. confidence/확률/usage 결과 필드는 그대로다. V2에서 사용하지 않는 동작용 minimumConfidence의 누락은 허용하지만, 명시된 잘못된 구성값·응답 confidence 필수성/수치 범위·확률 합·최대 라벨·파생 confidence 일관성은 기존 검증을 유지한다. V1의 신뢰도 정규화·구성 검사는 유지했다.

`TwoStagePreSubmissionCheckProvider`는 V2에서 semanticAssessment가 있으면 우선하고 공통 port가 제공하지 않으면 assessment를 읽는다. 검증은 라벨 분기보다 먼저 실행한다. 최초 유보를 더 이상 생성 없이 접수하지 않으며, supplement가 있을 때만 추가 생성 없이 미해결로 반환한다. 같은15초 잔여를 공유하고 생성 prompt/schema·공개 API·DB schema·원문/답변·source/CAS·최종 후보 계산은 바꾸지 않았다.

confidence/확률 보존은 기존 결과 객체와 실험 관측에 해당한다. 운영 DB에 새 영구 열을 추가한 것이 아니다. 질문1회는 기존 방·참여자 게시 한도다. 새 원문 재입력에서는 생성 호출 뒤 게시를 거부할 수 있는 기존 제약과 미응답·실패 마감의 가정은 이번에 바꾸지 않았다.

## 무료 검증

- 독립 작성한 일반 합성 불변 테스트를 사용했다. pilot 문구·정답·봉인 자료로 테스트를 만들거나 조정하지 않았다.
- 최초62개 실행의19실패를 보존한 뒤 최소 구현으로62PASS. 이후 managed physical 정상 분기10개를 추가했다.
- 추가 테스트의 RED 지문을 새로 확정하려고 변경 전 production 두 파일을 보존 archive에서 임시 복원했다. 새72개 중24실패/오류0을 확인한 뒤 수정본 두 파일을 finally에서 정확 복원했다. 첫 RED도 별도 보존했다.
- 최종 회귀 **41suites/507tests 전부PASS, 실패·오류·skip0**, ktlintCheck/assemble PASS. 신규 V2 class63개와 Evidence 계약9개가 이507개에 포함된다. 프로젝트 전체 commit guard를 실행했다고 주장하지 않는다.
- 원문·답변 CAS/멱등·질문1회·새revision·미해결 접수·마감 동결·주최자 진단·final 계약·source fencing·V1·Jev/개인 생성·공유deadline 관련 회귀를 실행했다.
- 의도적 결함3개를 각53개 버전에서 검증해 전부 탐지했다: V2 confidence 덮어쓰기 복원8실패, 최초 INDETERMINATE 생성 생략5실패, 답변 후 추가 생성 허용3실패. 컴파일 오류·skip은 없었고 각 단계 원본 SHA를 복원했다. 이후 추가한 physical10개와 FI의 과거53개 지문을 혼동하지 않는다.
- 모델 키와 paid mode를 회귀 child 환경에서 제거했다. 실제 공급자 모델 호출0. 독립 최종 source/문서/physical 테스트 검토에서 P1/P2가 없었다.

증거는 `.tdd/red/issue107-v2-label-routing.json`, `.tdd/verification/issue107-v2-label-routing.json`, `local-only-evidence/label-routing-preparation-20261030`이다. 제품 코드 변경은 src/main 아래 Kotlin 두 파일의 로컬 작업 트리에 있으며 HEAD는 기존 b4b3a8c54938b1f28dd4f1cd782a7333971ce24a다. 새 commit/push/PR/merge/deploy는 없다.

기존 p2 실제 진단의 source/compiled2676파일은 변경 전에 별도 archive로 보존했다. 기존 결과·manifest·모델 응답·봉인 자료는 수정하지 않았다. 과거 유료 결과를 새 라우팅이 실모델로 통과한 증거로 재사용하지 않는다. 최종 Luna timeout 등 이전 기술 실패의 해결이나 전체 흐름 실모델 성공을 이번 무료 변경으로 주장하지 않는다.

## Noul과 Choice의 확인된 이력

| 시점 | 실제 근거 |
|---|---|
| 2026-10-18 [e83d5280c7c236c99d64165893632920882527fe](https://github.com/meet-me-duo/meet-me-server/commit/e83d5280c7c236c99d64165893632920882527fe) | Jev가 아닌 OpenAI Decisions/gpt-6-luna `/v1/decisions`의 최초 분류 구현부터 Choice3라벨 |
| 2026-10-20 [289edadc4534faa4d5a323f988c4117578934369](https://github.com/meet-me-duo/meet-me-server/commit/289edadc4534faa4d5a323f988c4117578934369) | Jev1.14.0 `/v1/systemone`을 추가할 때도 Choice3라벨. [당시 요청 코드](https://github.com/meet-me-duo/meet-me-server/blob/289edadc4534faa4d5a323f988c4117578934369/src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/JevAmbiguityClassificationContract.kt#L55) |
| 2026-10-20 [6be4c45193133aed2b372cf8987b2d8a6f058588](https://github.com/meet-me-duo/meet-me-server/commit/6be4c45193133aed2b372cf8987b2d8a6f058588) | 개인 생성기를 Luna로 연결. [PR135](https://github.com/meet-me-duo/meet-me-server/pull/135), 통합f2fb0dcddc57ce4ede38f99d8916803509c51828. 분류 출력 형식 전환이 아님 |
| 2026-10-29 [c9453a35582ca6503c956ab4e8aff8d73b175ef3](https://github.com/meet-me-duo/meet-me-server/commit/c9453a35582ca6503c956ab4e8aff8d73b175ef3) | V2 Luna의 일반 NEEDS_EDIT 금지. 동시에 도입된 V2 유보 접수/생성 생략 매핑이 이번 수정 대상. Jev의 Choice 도입보다 뒤이며 다른 계층 |

접근 가능한 Git 전체 이력에서 Jev를 Noul로 실제 호출했다가 Choice로 전환한 증거는 찾지 못했다. Noul 소스 이력은 [잘못된 응답 type 거부용 negative fixture](https://github.com/meet-me-duo/meet-me-server/blob/289edadc4534faa4d5a323f988c4117578934369/src/test/kotlin/com/meetme/server/precheck/adapter/output/classification/JevAmbiguityClassificationTransportTest.kt#L550)였고, 설계 문서는 공급자 기능 목록에서만 Noul을 소개했다. Git에 없는 초기 실험·외부 Codex 대화까지 없었다고 단정하지 않는다.

보존된 오전/오후 없는8~9시 CLEAR/confidence0.06 오판 기록은 Jev Noul이 아니라 OpenAI Decisions Choice다([실제 진단 기록](../ISSUE_123_LIVE_DECISIONS_DIAGNOSTIC.md)). Y/N이라는 질문의 개념과 Noul이라는 API primitive는 같은 말이 아니다.

ADR-056과 Architecture는 원래48개를 이미 본 개발 세트로 구분하고 관련 시각 선별·시간대 판단을 분리한 별도 Choice 후보를 기록한다. relevance3라벨/period4라벨도 Noul은 아니었다. 이는 rubric·질문·평가 자료 가공이며 우리 자료로 Jev 가중치를 학습시켰다는 증거는 확인하지 못했다. 과거40+개 전체 원본을 확보·재검증한 것도 아니다.

## 지금 정책에서 Noul을 어떻게 볼지

현재 실제 요청은 CLEAR/NEEDS_REVIEW/INDETERMINATE의3지 Choice다. 개념적 판단과 후속 동작은 통과 또는 Luna 재확인의 두 그룹이다. 원래 INDETERMINATE는 보완 필요와 판단 유보를 구분하기 위해 도입됐고, 수정 후에는 독립적인 후속 행동 차이는 줄고 판단 이유·평가 구분으로 남는다.

따라서 ‘추가 확인이 필요한가?’를 Noul로 표현하는 것은 개념적으로 검토할 만하다. 그러나 Noul은 예의 확률 하나를 반환하므로 예의 정의·확률 임계값·중간 유보 처리와 평가를 새로 정해야 한다. 세 분류의 최대 라벨을 선택하는 것과 이진 확률을 판단하는 것은 같지 않으며 Choice confidence 기준을 그대로 옮길 수 없다. Noul이 자동으로 더 정확하다는 근거도 현재 자료에는 없다. 이번에는 Choice를 유지했고 Noul 변경은 구현하지 않았다.

## 향후 설계·검증 인계 원칙 (2026-10-30)

사용자 원칙: Jev는 완벽한 모호성 검출이나 모든 불확실성의 사전 차단을 위한 관문이 아니라 보완 질문의 필요 여부를 돕는 보조 장치다. 서비스의 핵심은 전체 참여자의 자연어·보완 정보를 모아 수행하는 최종 분석이다. Jev의 완벽성을 추구하면서 분류·상태·임계값·반복 검사를 늘려 전체 흐름을 과도하게 복잡하게 만들거나 실행을 막지 않는다.

향후 변경은 Jev 단독 정답률보다 최종 분석까지의 진행, 필요한 질문의 유용성, 원문·답변 보존과 최종 미반영 처리로 판단한다. 미해결 의미는 보존해 전체 해석의 기회를 주되, 본인만 아는 사실을 발명하거나 기술 실패를 정상 CLEAR로 바꾸지 않는다. 이 원칙은 현재 승인된 Choice 라벨 분기·질문1회·기술 보호 정책을 추가로 변경하는 승인이 아니다. 새로운 차단 조건이나 복잡한 사전 검사를 제안할 때는 최종 분석에 꼭 필요한 근거부터 확인한다.

이번 인계 반영은 문서만 변경했다. 코드·운영 설정·기존 평가 결과·봉인 자료·검증 archive는 보존하고 추가 모델 호출·외부 앱 갱신·병합·배포는 수행하지 않았다.

## 역할과 사전 확인의 목적 보완 인계

사용자 원문(2026-10-30, synthetic-authorization-51f2c3bbac4abb8f):

Jev는 보완 질문의 필요 여부를 돕고, 개인 Luna는 재확인 후 통과 또는 구조화된 질문을 제공한다. 이 사전 단계는 최종 분석 불가 위험을 줄이는 보조 장치이며 전체 맥락 해석을 대체하지 않는다.

## Jev·Luna·최종 분석의 역할

- Jev는 개인 입력에 보완 질문이 필요한지 판단을 돕는 단순 분류기다. 완전한 시간 구조화·교집합 계산·최종 분석 성공을 확정하지 않는다.
- 개인 Luna는 재확인 결과 보완이 불필요하면 CLEAR로 넘기고, 필요하면 Structured Output으로 구체적인 질문과 안전한 선택지/직접 답변 경로를 제공한다. Jev가 재확인 대상으로 보냈다고 질문을 반드시 만들지 않는다.
- 사전 확인의 목적은 최종 분석 불가로 끝날 위험을 줄이는 것이다. 각 참여자의 입력이 모인 뒤 해석이 더 쉬워질 수 있으므로, 앞단에서 모든 사실을 확정하려고 전체 맥락 해석의 기회를 없애지 않는다. 원문과 보완 근거는 그대로 최종 분석에 제공한다.
- 최종 모델은 전체 입력의 맥락을 해석하되 다른 사람의 가능 시간으로 개인만 아는 사실을 발명하지 않는다. 끝내 해석하지 못한 입력은 기존대로 안전하게 제외하고, 주최자에게 후보와 함께 제외 입력·사유를 보여준다. 시간 후보 계산과 선택 저장은 기존 서버 계약을 따른다.

현재 승인된 라벨 중심 분기는 이 목적과 맞는다. 첫 CLEAR는 개인 보완 없이 접수하고 NEEDS_REVIEW/INDETERMINATE는 Luna가 재확인한다. 답변 후에는 추가 질문 없이 미해결 근거까지 최종 분석으로 보낸다. 내부 CLEAR/CONFIRMED는 개인 보완 단계의 상태이며 완전한 시간 해석·최종 구조화 성공을 보장하는 관문으로 해석하지 않는다. 이 일치 확인은 구현 목적의 대조이며 실모델 전체 흐름 성공을 새로 검증한 것은 아니다.

이번 보완은 목적·역할 인계만 추가했다. 기존 제외 입력/사유의 주최자 표시와 개인 사실 비발명 정책을 유지한다. 새 유료 호출·Noul 전환·추가 기능·코드 변경·병합·배포는 수행하지 않았고, 기존 실제 실패와 미검증 영역도 그대로다.
