# 현재 네 테스트 지문의 사후 재구성 RED 실행안

기록 성격: **실행 전 계획 사본**. 이후 완료한 실제 7 RED/7 복구 GREEN 근거는 `../issue-107-pre-submit-backend-format-replay/`에 별도로 보존한다. 과거 제품 skeleton의 정확 바이트 snapshot은 현재 workspace/history/tmp 검색에서 발견되지 않았다. 최초 테스트 원본과 raw log는 보존되어 있다. 이 계획을 정확 과거 skeleton의 재실행이나 시간순 최초 RED로 표현하지 않는다.

원본 근거:

- 최초48 원본: `../issue-107-pre-submit-initial48/original/` 및 chronological-red.json
- 최초 local raw log: `.codex/tdd-evidence/issue-107-pre-submit-http.log` (초기 XML 대체됨)
- Defense/Rate 원본: `../issue-107-pre-submit-style-supersession/original/`
- 현재 지문까지의 정합: style/PS06 history의 exact diff와 SHA

실행 경계:

1. 최종 src/main, build/Gradle 설정과 아래 현재 네 test 파일을 별도 `/tmp` scratch에 byte-exact 복사한다. main/test의 before SHA를 기록한다. 실제 공유 저장소 제품/테스트는 수정하지 않는다.
2. 현재 final 파일 SHA는 Worker `366dd031be2f2717234025717975451aa7ab9b6715b64a85b4443cf53a271aeb`, Privacy `0ded3b08ddfc9cab3835e5780a23f40a206614f68354a3a02cda36ca438be548`, Defense `89275de222afa014b08bb7a5ce6c89ef677fa91ed506637ab177d239c9e674cd`, Rate `b056297553ad9677baf3380c6ad64d6dabc728ed10c3b7cb163cd726a30d07bc`다. 실행 직전에 재확인한다.
3. 모든 실행은 `/tmp/meetme-gradle.lock`, JDK17/system CA, 격리 PostgreSQL/합성 provider/RSA를 사용한다. 실모델·실제 OAuth·운영 호출은 0. test source는 네 파일만, Gradle method selector는 아래 최소 case만 선택한다.
4. cycle A: worker dispatch를 미구현 no-op(false) skeleton으로 재구성한다. Worker PS03 질문 state(NEEDS_CHOICE 기대/QUEUED 실제)와 Privacy PS22 queue 자리 회복의 provider 1회 기대/0회 실제를 확인한다. 이 skeleton은 원래 제품 바이트가 아니라 기능 부재의 사후 재구성이다.
5. cycle B: same-source proposal 검증만 과거 부재 상태로 되돌린다. Defense PS08 원문 동일 제안의 FAILED/INVALID_RESPONSE 기대가 NEEDS_CHOICE로 실패하는 원래 관측 동작을 확인한다.
6. cycle C: checks POST의 submission quota route 등록만 과거 부재 상태로 되돌린다. Rate 네 POST 경로의 429/기존 quota 기대를 네 parameter case로 확인한다.
7. 각 cycle은 원본 scratch source에서 별도 시작하며 mutation 정확 diff·source SHA·test SHA·원본 log/XML·assertion 실패를 기록한다. 컴파일/환경/fixture 문제는 RED0으로 분류한다. 복구 source byte 일치와 해당 method GREEN도 별도로 확인한다.
8. 새 workItem `issue-107-pre-submit-*-format-replay`의 strict schema 7필드 RED를 기록한다. `recordedAt`은 Z 형식, failedTests는 string 배열로 작성한다. 사후 보충/이전 source의 정확 복제 불가라는 설명은 history에 기록한다.

이 계획은 현재 지문 재확인의 실행 방안이며 아직 실행 결과나 유효 RED가 아니다. Independent FI와 최종 guard full은 별도 증거다.
