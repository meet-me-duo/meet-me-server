# 저장된 실제 Luna 응답의 무료 후처리 재생 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**원래 대면 모임은 추천·확정까지 이어지지 않았다.** 실제로 저장한 Luna 응답을 재생하자 시간3명은 반영됐지만 대면 장소 영역이 없어 `NO_MATCH`/추천0으로 끝났다. 모임 방식만 `EITHER`로 바꾼 별도 진단 대조군에서는 비대면 추천3개·주최자 확정·DB 저장까지 연결됐다. 후자는 원래 카페 모임 해결이나 개인의 비대면 동의를 뜻하지 않는다.

외부 공급자 API0, 새 무료 검사3PASS·ktlintCheck/assemble PASS다. 이 PASS는 현재 동작의 특성화와 재생/후처리 연결 검증이며 **원래 대면 사례 또는 실시간 모델 전체 E2E의 성공 판정이 아니다.**

## 재생 경계와 보존

원본은 `final-luna60-e68de3eb-812a-4fc6-8b78-237ec77ea034/response.json`의 실제 HTTP200/completed 응답이다. 각 새 모임의 동결 version을 가리키도록 **output_text.results[].input_ref만** 치환했다. 원래 conditions·preferences·rejection·나머지 response 메타데이터는 유지한다. 단위 계약 검사는 세 결과의 input_ref 이외 모든 필드의 JSON 동등성과 누락/중복 ref 거부를 확인했다. reasoning item 내용은 읽거나 인용하지 않았다.

실제 Spring 앱·공개 HTTP·격리 PostgreSQL18/Redis8·원본 최종 Luna adapter·원본 JDBC source fence·원본 matching/projector/persistence를 사용했다. 최종 HTTP 응답만 application interceptor로 반환한다. DNS/연결/header 전송과 장소 API는 차단했고 provider key/유료 MODE도 자식 실행 환경에서 제거했다. source 검사4회·저장 응답 반환2회, 외부 header/DNS/connect/place 시도0이다. 응답의 과거 usage는 그대로 남아도 이번 유료 사용량이 아니다.

개인 단계는 과거 관측을 재현하는 test double이다. p1 질문1/실제 저장 답변/답변후 UNRESOLVED, p2/p3 CLEAR를 각 새 모임에 접수했다. Jev나 개인 Luna를 실시간 재검증한 것이 아니다. 새 질문 ID·접수 timestamp·동결 ID는 새 테스트 실행의 값이며 원문과 보완의 논리 내용은 유지했다. 두 모임 모두 동결3개와 파서 정규화 DTO→DB 재조회 결과의 전필드 동등성3/3을 확인했다. 동일 파서로 expected를 만들었으므로 raw 모델 field→파서 변환의 의미 정확성을 새로 독립 증명한 것으로 확대하지 않는다.

## 관측 결과

| 항목 | 원래 IN_PERSON | 별도 EITHER 대조군 |
|---|---|---|
| 최종 모델 응답 의미 | 동일 저장 조건·거절·선호 | 동일, 모임 방식만 변경 |
| 공개 종결 | **NO_MATCH** | READY → CONFIRMED |
| quality / applied / total | COMPLETE /3/3 | COMPLETE /3/3 |
| 원본·유효 모델 rejection | 0개 | 0개 |
| 주최자 미반영 목록 | `[]` | `[]` |
| 후보 / 신규 시간 추천 | 0 /0 | 1 /3 |
| 추천 방식 | 없음 | 모두 REMOTE |
| 주최자 확정 저장 | 0개, 수행할 추천 없음 | 1개, 반환 ID·시각과 DB 일치 |

EITHER 대조군의 시간 추천은 KST 기준 다음과 같다.

- 11월12일 목요일19:30–21:00,3명.
- 11월13일 금요일20:00–21:30,3명.
- 11월13일 금요일19:00–19:30,2명 부분 참석.

주최자가 실제 반환 option/variant/analysis ID와 시간으로 confirmation을 호출했다. confirmation/result200, CONFIRMED, DB의 coordination_run/option/variant/start/end가 요청·재조회와 정확히 일치했다. 이는 비대면 허용으로 바꾼 **후처리 진단 대조군**의 결과다. 실제 Luna가 그 새 모임에서 같은 판단을 냈다는 증거나 원문 작성자가 비대면 참석에 동의했다는 증거가 아니다.

## 원래 대면 모임이 막힌 이유와 미반영 안내의 빈틈

- p1/p3 출력은 `SPECIFIC_PLACE`와 역 기준 도보 `TRAVEL_CONSTRAINT`가 함께 있다. `MatchingProcessor.kt:134–156`은 TravelConstraint 하나라도 있으면 offlineArea를 null로 만든다.
- p2 출력은 `PREFERRED_PLACE`만 있고 `SPECIFIC_PLACE`가 없다. 선호를 하드 허용 영역으로 승격하지 않으므로 역시 offlineArea가 없다.
- `DeterministicCandidateMatcher.kt:134`는 대면 영역 또는 기하 영역이 있어야 대면 후보를 만든다. 세 명 모두 그 영역이 없어 원래 IN_PERSON은 부분 참석 후보도 만들지 못했다. 시간 교집합이 없어서 생긴 NO_MATCH가 아니다.
- TravelConstraint는 `UnresolvedPlace`와 달리 이 경로에서 normalized-place 실패 snapshot을 남기지 않고 모델 rejection도0이다. `MatchingResultService.getUnappliedInputs`는 rejection/장소 실패 snapshot/누락 구조화만 읽으므로 **주최자 미반영 목록은 비었다.** 이를 임의로 AMBIGUOUS_TIME로 바꾸거나 PARTIAL 결과로 꾸미지 않았다.

NO_MATCH 자체는 정상 종결이다. 다만 “시간3건을 해석했지만 장소 계산을 못 해 대면 후보가 없다”는 이유를 사용자가 이 목록으로는 알 수 없는 설명 공백이 확인됐다. `quality=COMPLETE`는 여기서 모든 도보 조건까지 계산 완료했다는 보장이 아니다.

PRD Rule2(:261)는 불명확한 개인 기준점을 추측하지 않는 보수 정책이고 ADR-012(:107)/Architecture(:242)는 이동제약·미확정 표현만 있는 경우를 다룬다. **기준역이 명시된 도보5/10분 조건이 SpecificPlace와 함께 있을 때까지 장소 전체를 무조건 제거하는 범위가 문서로 명시 확정됐다고 단정하지 않는다.** 현재 코드는 더 넓게 차단한다. 이 지점의 지원 범위와 주최자 안내는 후속 제품/설계 판단 대상이며 이번 검증에서 정책을 바꾸지 않았다.

저장 응답에는 해석 실패 rejection이0개이므로, 이번 재생은 미반영 목록이 비어 있는 처리를 확인했다. nonempty 해석 실패 사유 표시를 이 응답으로 실검증했다고 주장하지 않는다. 이전 별도 무료 제외1건 테스트와 구분한다.

## 장소 선호 의심: 원문과 실제 결과

| 원문 | 사전 고정 의도 | 실제 Luna 출력 |
|---|---|---|
| “홍대입구역 주변 좋아요.” | 홍대입구역 주변 **가능/허용** | `PREFERRED_PLACE`, query=홍대입구역 주변, AREA_1 |
| “사진 보려면 조용한 카페였으면 좋겠어요.” | **조용한 카페 선호** | `PREFERRED_PLACE`, query=조용한 카페, AREA_1 |

첫 문장은 허용/수락과 선호 양쪽으로 읽히며, 두 번째는 원하는 속성을 더 명시적으로 말한다. 현재 prompt는 보통 허용에서 선호를 발명하지 말라고 하지만 PRD는 모든 “좋아요”의 문맥별 분류를 확정하지 않는다. 따라서 고정 의도보다 장소 선호 조건이 하나 추가된 차이는 확인되나 확정 모델 오류로 단정하지 않는다.

**이전 보고의 “새 선호 차원”이라는 표현은 정정한다. 같은 장소 차원 안의 추가 조건이다.** 같은 차원은 OR, 참가자당 최대1점이며 자동2점이 아니다. 이번 두 항목은 같은 AREA_1이고 `MatchingProcessor`는 areaKeys를 set으로 모은다. 이 사례에서 추가 항목 때문에 지역 키나 점수가2배가 됐다는 근거는 없다. 비대면 대조군은 장소 선호 충족으로 계산되지 않는다. 다른 지역/문맥에서 허용 범위나 순위가 바뀔 가능성과 일반 언어 정책은 별도다.

현재 무료 검증 완료를 위해 사용자의 추가 판단은 필요 없다. 서비스의 일반 분류 정책을 바꾸려면 허용/명시적 선호의 경계를 여러 독립 사례로 결정해야 하며, 이 pilot gold 하나에 맞춰 모든 “좋아요”를 허용 또는 선호로 고정하지 않는다. p2 해석을 바꾸기만 해도 p1/p3 도보 제약 차단은 남으므로 원래 NO_MATCH 전체를 해결하는 단일 수정으로 제시하지 않는다.

## 검증과 산출물

- XML: `SavedLunaReplayContractTest`2개 + `SavedLunaReplayTest`1개, **3PASS/실패0/오류0/skip0**. 최종 Gradle exit0·ktlintCheck/assemble PASS.
- 초기 formatter의 긴 줄 오류1회는 별도 로그로 보존하고 최종 검사는 통과했다. 기대값을 성공에 맞춰 완화하지 않았다. 사전 독립 설계 검토와 최종 독립 읽기 검토가 위 범위/수치를 확인했다.
- main source289개·원본 replay artifact4개 해시 불변. 이전60초 진단의2734개 bound source/compiled도 신규 컴파일 전 전부 일치 확인·별도 backup했다.
- 결과: `local-only-evidence/replay-results.json`와 각 case의 host 응답/flow JSON.
- 준비·로그/XML·원본해시·검증: `local-only-evidence/saved-luna-replay-20261030`의 `post-run-verification.json`, `place-preference-review.json`.

외부 모델/장소 API0·봉인 접근0·생산 코드/시간제한/모델정책 변경0·commit/PR/병합/배포0이다. 원래 대면 모임의 추천과 확정은 여전히 해결되지 않았고, 실시간 모델 전체 흐름과도 구분한다.
