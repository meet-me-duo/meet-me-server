# 제출 전 시간 조건 보존 독립 검토

검토일: 2026-10-28 UTC. 구현과 분리된 테스트 설계·검토 역할이 작성했다.

## 확인한 결과

- 개인정보 없는 최초 사전 oracle 12개는 CLEAR 7 / NEEDS_CHOICE 3 / NEEDS_EDIT 2다. 후속 범위 보존 검증용 TF13 oracle은 별도 파일에 먼저 고정했다. 사용자 원문·방 식별자는 포함하지 않았다.
- 프로덕션 변경은 `PrecheckProviderContract.INSTRUCTIONS`의 두 문단과 보완문 범위 보존 지시 한 문장이다. 넓은 허용 범위를 보존하고 같은 연속 구간의 문맥상 명시된 오전·오후를 끝점에 적용한다. 별도 대안이나 절로의 적용에는 전체 문맥의 근거를 요구한다. 보완문은 원문이 한 번으로 제한하지 않은 요일·반복 조건을 단일 날짜로 좁히지 않도록 한다.
- 초기 검토에서 별도 대안으로의 시간대 상속을 무조건 금지한 문구를 지적했다. `without support from the complete text`로 제한한 최종 수정에서 해당 지적은 해결됐다.
- structured schema/parser, deadline, dispatch, 소유권·revision, 비용·참여·마감 경계는 변경하지 않았다. 정규식 의미 판정·특정 입력 하드코딩·무조건 CLEAR 처리는 없다.
- 비용0 전송 테스트와 결함 주입은 정책의 전달·원문 보존을 검증한다. 모델의 의미 정확성을 입증하지 않는다.

## 실제 소수 합성 평가

부모가 기존 승인 범위에서 최초 고정 6개를 production Luna adapter로 한 번씩 실행한 결과를 독립적으로 읽었다. 이 최초 평가는 아래의 범위 보존 지시 한 문장을 추가하기 전 소스에서 수행했다. 이 검토자는 유료 호출을 하지 않았다.

| ID | 기대 verdict | 실제 verdict | 지연 ms | 입력/출력 tokens |
| --- | --- | --- | ---: | ---: |
| TF02 | CLEAR | CLEAR | 2749 | 799/32 |
| TF05 | CLEAR | CLEAR | 1606 | 812/32 |
| TF06 | CLEAR | CLEAR | 1620 | 808/101 |
| TF08 | NEEDS_CHOICE | NEEDS_CHOICE | 3375 | 800/274 |
| TF11 | NEEDS_EDIT | NEEDS_EDIT | 2848 | 798/165 |
| TF12 | NEEDS_EDIT | NEEDS_EDIT | 3126 | 823/188 |

**verdict는 6/6 일치한다. 전체 보완문 의미 보존은 별도다.** TF08의 질문은 오전·오후를 직접 구별하고 A/B가 서로 다르며 정확한 원문 구간·길이·필드 계약을 충족한다. 그러나 실제 두 proposal은 원문의 `금요일`을 `2026년 10월 30일 금요일` 하나로 구체화했다. oracle의 안전한 예시는 요일 범위를 그대로 보존한다. 검색 기간에 금요일이 여러 번 있으므로 이 구체화가 원래 날짜 적용 범위를 보존하는지는 확인되지 않았다. 따라서 TF08의 구조·오전/오후 질문은 확인됐지만 **전체 proposal의 날짜 범위 보존은 미확인**이다. 이를 안전한 보완문 6/6 성공으로 보고하지 않는다.

원문의 반복/단일 요일 의미를 추가로 확정하거나 TF08을 재호출하지 않았다. 기존 프롬프트의 상대 날짜 절대화 지시가 이 결과에 영향을 주었을 가능성은 추정이며, 이번 두 문단으로 새로 생긴 회귀인지는 비교 검증하지 않았다.

### 범위 보존 후속 검증

위 발견에 따라 `Preserve weekday and recurrence scope in both complete proposals; do not replace a weekday pattern with one calendar date unless the original text explicitly limits it to that occurrence.`라는 일반 지시를 추가했다. 원래 12개 oracle이나 최초 6개 관측값을 수정하지 않았다. 새 TFP03 전송 테스트와 별도 TF13의 기대 결과를 구현 전에 고정했다.

부모는 최종 소스에서 명시적으로 `매주 금요일`이라고 쓴 TF13만 추가 1회 실행했다. 원래 6개는 재호출하지 않았다.

| ID | 기대 verdict | 실제 verdict | 지연 ms | 입력/출력 tokens | 보완문 범위 |
| --- | --- | --- | ---: | ---: | --- |
| TF13 | NEEDS_CHOICE | NEEDS_CHOICE | 2454 | 832/125 | A/B 모두 매주 금요일 유지 |

질문은 오전·오후를 구별하며 A는 `매주 금요일 오전 6시부터 오전 8시까지 가능해요.`, B는 `매주 금요일 오후 6시부터 오후 8시까지 가능해요.`다. 반복 요일과 시간 범위를 보존했고 단일 날짜를 추가하지 않았다. 정확한 원문 구간·필드·길이·A/B 차이도 기존 계약을 만족한다. **최종 소스의 TF13 1개는 verdict와 보완문 범위 보존을 확인했다. 최초 TF08의 정확한 입력을 최종 소스로 다시 검증한 결과는 없다.**

## 근거와 일치 검사

- oracle: `docs/evaluation/precheck-time-flexibility-oracle.json`, SHA-256 `ce025b63f71196dd2032dbb898143b8110188bfe9ff2a24e923e37ca5319d91f`
- 최초 6개 평가 prompt 소스 SHA-256: `0f19b4bc58dfabcbda2a969dc668412294831db931fae27c038fa9e2b62fdcb5`
- 최초 6개 평가 source fingerprint: `b037c1a2783a28a17899e2d051fd9c8756e6579944dbfe3ba1bbc96c026832e5`
- 실제 결과: `docs/evaluation/precheck-time-flexibility-observed.json`
- 평가 소스·compiled class·payload 지문: `docs/evaluation/precheck-time-flexibility-manifest.json`
- 후속 TF13 oracle: `docs/evaluation/precheck-weekday-scope-oracle.json`, SHA-256 `44ec67e00ba56fbb83c6faf1626d43d13fd4dd1f9a9976c824659ba48bcb3474`
- 최종 prompt 소스: `src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt`, SHA-256 `0c900513faa5406832d8ef34800d4abaa726ce9834ee20c2c342ef93abd6b4d4`
- 후속 TF13 평가 source fingerprint: `ecf8318d497eb441e3802b8efe560e41dc5d300a640967e43f855ca5054d70c9`
- 후속 TF13 결과·지문: `docs/evaluation/precheck-weekday-scope-observed.json`, `docs/evaluation/precheck-weekday-scope-manifest.json`
- FI 기록: `.tdd/verification/precheck-time-flexibility.json`

원본 실행 디렉터리 `/tmp/precheck-time-flexibility-live-final`과 저장소 결과·manifest 사본의 바이트 일치를 확인했다. oracle, 4개 source, 2개 compiled class, 6개 payload와 metadata 지문, 고정 model/store/service tier/reasoning/output cap, claim 파일 존재, 예상 ID/최대6/실행6/retry0 및 결과 필드 구조도 일치했다.

후속 원본 디렉터리 `/tmp/precheck-weekday-scope-live-final`도 저장소 결과·manifest 사본과 바이트가 일치했다. 별도 oracle, source fingerprint 계산, 요청 payload·고정 profile·claim·최대1/실행1/retry0 및 반복 범위 보존을 확인했다. FI 후 복원된 4개 현재 source 지문은 TF13 manifest와 일치한다. 검토 시점의 compiled contract class는 FI 실행 산출물이라 최종 전체 guard가 복원 소스를 다시 컴파일한 결과를 확인해야 한다.

FI 기록의 세 정책 제거 결과는 모두 DETECTED이며 broad/meridiem은 TFP01, weekday scope는 TFP03의 Luna/Gemini assertion이 탐지했다(errors 0). 기록한 최종 source·test 지문은 실제 복원된 파일과 일치한다. 이 결과는 정책 전달 검증이며 실제 모델의 false CLEAR/false NEEDS_EDIT 탐지력으로 해석하지 않는다.

## 남은 범위

이 결과는 서로 다른 두 소스의 노출된 개발용 합성6개와 추가1개에 대한 단독 생성 단계 평가다. 최종 소스에서 7개를 모두 검증한 결과가 아니다. Jev 실제 판정·2단계 전체 지연·사용자 방·실제 운영 반영·모집단 오탐률 검증도 아니다. 토큰 관측은 청구 증명이 아니다. 최초 TF08의 최종 소스 적용 결과는 미확인이다. 문서 작성 시점의 최종 전체 guard 결과는 아직 확인 전이며 신규 이슈 게시 자동심사 거절로 원격 게시·PR·병합·운영 반영도 보류 상태다.
