# 운영 AI 활성화 Cloud 이관 체크포인트

이 커밋은 미완성 작업을 보존하는 checkpoint다. 완료된 기능·정상 품질 게이트·운영 배포로 해석하면 안 된다.

## 정확한 출발점

- 저장소: meet-me-duo/meet-me-server
- 활성 작업 base/HEAD: 03bbdc1909d22c0bad4667eedea6e2a0c2ffef87 (develop)
- 작업 브랜치: feature/144-production-ai-activation
- 이관 전 활성 작업 로컬 커밋 0, 새 AI PR/main 병합/배포 0.
- 원본 PC 작업과 기존 Library 자료를 삭제·reset하지 않았다.

## 사용자 요구와 현재 코드

실제 운영 환경에서 사용자와 지인들이 AI를 사용하게 한다. 별도 테스트 모드·임의 만료일·총 요청 슬롯·threshold 선택을 필수 조건으로 추가하지 않는다. 정식 금액 정책은 나중에 정한다. 개선 기능과 UI는 별도 작업이며 이 브랜치에서 구현하지 않는다.

renderer 한 파일은 UNCONFIGURED+ON을 provider/routing/classifier 3개 ON, budget OFF, threshold 미출력으로 바꿨다. 기존 CONFIGURED와 Typesafe 조회를 보존했고 관련 Python25개(신규8개 포함)가 통과했다. 새 정책·마이그레이션은 없다.

Kotlin은 아직 skeleton이다. AiRuntimeDispatchGuardPort, gate/factory의 추가 인자, JevClassifierOptions의 추가 인자가 있고 JdbcAiBudgetExecutionFence.authorize에는 TDD TODO가 남아 있다. 실제 운영 제어 분리는 구현되지 않았다. 새 Kotlin15메서드/16실행 테스트는 작성됐지만 Gradle 시작 전 캐시 I/O 오류로 실행되지 않았다. 유효한 새 Kotlin RED도 없다. 먼저 AGENTS.md와 .agents 지침을 읽고 Cloud 환경에서 RED부터 진행해야 한다.

## 다음 구현 범위

### 이관 이후 전달된 금액 기반 분석 차단 제거 승인

이미 승인된 요구는 **정식 비용 정책이 미정이라는 이유로 운영 AI 활성화를 차단하지 않는다**는 것이다. 별도 테스트 모드, 임의의 만료일·요청 슬롯·금액 정책을 활성화의 새 선행 조건으로 만들지 않는다.

이후 사용자는 금액 상한 초과로 분석을 막는 조건 자체를 제거하자고 제안하고, "그래 안전장치를 유지한 채로 우선 정상 운영될 수 있도록 AI 제한을 해제하자고"라고 명시적으로 승인했다. 이 최신 승인은 기존의 정책 미정 상태 허용보다 범위가 넓다. **정식 비용 정책 미설정 및 금액 상한 초과를 이유로 분석을 차단하지 않는다.** 비용 관측·기록은 분석 차단과 분리한다. 아래 번호 목록은 이 추가 승인 이전의 구현 제안이므로 금전 경로의 차단 조건을 그대로 최종 요구로 확정하지 않는다. Cloud에서 최신 승인에 맞게 구현 범위를 재평가한다.

권한 검증, 중복 전송 방지, 재시도 제한, timeout, 상태·정합성 검증, 비밀 보호는 유지한다. 금액 기반 차단 제거 승인을 기술적 안전장치 제거 또는 무제한 유료 검증 호출 승인으로 확대하지 않는다. 이관 freeze를 유지해 이번 갱신은 문서만 추가했으며 해당 승인에 따른 코드·테스트·운영 변경은 실행하지 않았다. Cloud 수신 후 코드·검증을 이어가고 AWS·운영 작업은 사용자가 귀가한 뒤 개인 PC에서 재개한다.

1. provider/routing/classifier 3개 일관성과 금전 selection을 분리한다. budget OFF면 selection false/null, 기존 방의 금전 READY 승격 없음.
2. gate와 AiDispatchModePort에 같은 physicalEnabled를 적용한다. worker/final context와 claim 기준 기한을 유지한다.
3. 기존 JdbcAiBudgetExecutionFence의 room/owner/revision/generation/source/context/상태/deadline 검증을 재사용하되 금전 coverage만 금전 경로에 남긴다. 운영 guard는 짧은 트랜잭션으로 deadline만 반환한다.
4. 금전 OFF gate는 가짜 quote/cap/epoch/permit 없이 현재 guard, 고정5profile, 실제 payload, app/network one-shot 및 non-2xx 보호를 유지한다. 같은 stage를 다시 보내는 복구 경로를 추가하지 않는다.
5. 운영에서 Jev threshold가 없으면 CLEAR도 Luna 검증으로 보낸다. 기존 금전 모드의 threshold 요구와 malformed/refusal 정책은 유지한다.
6. 새16개 및 기존 금전 회귀, 결함 주입, 전체 품질 게이트와 독립 검토를 실제로 완료한다. source-snapshots.zip의 retired-reference-only는 적용하지 않는다.

## 거절된 변경: 그대로 전달

대상: deploy/tests/issue131-runtime-bundle-env.test.cjs, deploy/tests/runtime-auth-env.test.cjs.
미실행이다. 원래 파일은 변경되지 않았으며 blocked-node-oracle.patch는 제안일 뿐이다. 이관/commit/push 승인을 이 검사 정정 승인으로 해석하거나 우회 적용하지 않는다. 새 관련 사용자 승인과 알려진 미실행을 확인한 경우에만 한 번 재검토할 수 있다.

자동 승인 검토 사유 원문:
This retries the previously rejected modification of existing regression oracles, including removing a sentinel case; the transcript provides no new trusted user approval for weakening or reframing those tests.

## 검증 상태를 혼동하지 말 것

checkpoint-state.json에 새 운영 구현과 폐기된 테스트 모드의 결과를 분리했다. 이전 37PASS나 FI4/4를 새 운영 AI 검증으로 주장하면 안 된다. 이전 전체2200은40FAIL/skip4였고 관련44 재검증만 통과했다. 연결 거부의 근본 원인은 미확정이다. 로그 원문은 이관하지 않았다.

## 안전한 복원과 동일성

일반적으로 이 checkpoint commit 자체를 정확한 SHA로 fetch/checkout하면 활성 파일과 저장소 지침이 함께 온다. FILE-MANIFEST.json은 커밋 직전 index의 blob ID와 SHA256으로 파일 동일성을 검증한다. Windows 원본 byte와 LF 정규화 SHA는 source-snapshots.zip 내부 inventory에 있다. Git의 LF 정규화로 raw Windows SHA가 달라질 수 있다. snapshot ZIP의 모든 파일 SHA는 생성 시 원본과 대조했다.

source-snapshots.zip은 active-production과 retired-reference-only의 비밀 없는 코드 원본/patch를 보존한다. 후자는 폐기된 TEST_OPERATION/V19이며 현재 활성 소스에 절대 덮어쓰지 않는다. .env, 키, 인증 캐시, .git, 빌드/의존성 캐시, 원시 .log/.xml은 제외했다. 제외 항목은 inventory에 기록했다.

## 현재 작업 제한

Cloud 수신 확인 전 추가 코드 변경을 멈춘다. 이관 단계에서는 main/develop 변경, PR 병합, 운영 배포, AWS 명령·인증·설정·DB·SSM·유료 검증 호출을 하지 않는다. AWS는 사용자가 귀가한 뒤 개인 PC에서 기존 인증으로 이어간다. 새 자격증명·IAM/인증 변경에는 별도 행동 승인이 필요하다.

## feature push 효과

현재 ci.yml의 push 대상은 main/develop뿐이며 PR과 수동 실행도 지원한다. deploy.yml은 성공한 main push CI 또는 main 수동 실행만 허용한다. infrastructure와 production-preflight도 feature push로 실행되지 않는다. 이 checkpoint feature push는 자동 운영 배포를 유발하지 않으며 workflow 파일을 바꾸지 않았다. PR 생성이나 수동 CI는 이 checkpoint 단계에서 실행하지 않았다.
