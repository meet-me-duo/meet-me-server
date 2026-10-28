# NEEDS_EDIT 보완 안내 독립 검토

검토일: 2026-10-28 UTC. 구현과 분리된 테스트 설계·검토 역할이 작성했다. 프로덕션 코드·테스트를 검토 과정에서 변경하지 않았으며 유료 모델을 추가 호출하지 않았다.

## 결론

검토한 다섯 프로덕션 파일에서 미해결 P1/P2 결함은 발견하지 않았다. 사용자가 무엇을 수정할지 알 수 있도록 NEEDS_EDIT의 구체적인 안내를 보존하고 본인에게 반환한다. 안내가 없는 기존 기록에는 원인을 발명하지 않는 서버 기본 안내를 사용한다. 입력·완료·마감·본인 소유권 경계는 유지한다.

초기 검토에서는 방향 제어문자 차단에서 U+061C, U+200E, U+200F 누락을 P2로 지적했다. 별도 독립 테스트 6개에서 유효한 assertion RED(errors 0)를 확인한 뒤 세 코드포인트가 추가 거부됐다. 최종 집중 XML은 아래 34개 모두 PASS·실패/오류/skip 0이다. 전체 최종 guard와 FI는 이 문서 작성 시점에 별도 진행 중이다.

| 검사 | 실행 수 | 확인한 경계 |
| --- | ---: | --- |
| PrecheckEditGuidanceContractTest | 16 | Luna/Gemini 파싱, 안내·정확 구간, atomic pair, 코드포인트 길이, 제어문자·마크업·링크 거부, legacy/CLEAR 호환 |
| PrecheckEditGuidanceBidiContractTest | 6 | Luna/Gemini 각각 U+061C/U+200E/U+200F 거부 |
| PrecheckEditGuidancePostgresTest | 6 | 실제 PostgreSQL 저장·재조회, private HTTP, A/B 한도, 직접 수정 재검사, 낡은 결과 폐기, 생성 OpenAPI |
| PrecheckTimeFlexibilityPolicyContractTest | 6 | 기존 넓은 시간·문맥·요일 보존 정책 전송과 원문 보존 |

## 변경 경계 검토

- 모델의 기존 네 필드와 schema 타입은 유지한다. NEEDS_EDIT에만 prompt/sourceExcerpt를 추가로 허용하고 둘 중 하나만 있으면 INVALID_RESPONSE다. 원문 그대로의 1~200 코드포인트 구간을 요구하며 choices는 null/빈 배열만 허용한다. 기존 안내 없는 NEEDS_EDIT도 읽을 수 있고 CLEAR에 안내를 실을 수 없다.
- 안내는 1~160 코드포인트의 비어 있지 않은 단일행 일반 텍스트다. C0/C1, 줄 구분, surrogate와 방향 제어문자, HTML 기호·마크다운 링크·주요 navigation scheme을 거부한다. 원문 구간은 참가자가 쓴 비신뢰 데이터로 유지하므로 `<끝난 뒤>` 같은 원문을 별도 의미 규칙으로 재작성하지 않는다. 공개 edit_guidance는 안내만 반환한다.
- 기존 question JSON의 id/prompt/sourceExcerpt/choices 구조에서 choices=[]로 저장하므로 새 SQL migration은 없다. 재조회 후 안내가 보존되며 공개 NEEDS_EDIT.question은 계속 null이다. 안내의 내부 UUID나 원문 구간을 선택형 질문으로 노출하지 않는다.
- questionCount는 precheck의 choices 크기가 2인 실제 A/B만 집계한다. 이전 submission_clarifications의 집계와 방·본인 조건은 유지한다. 안내 두 개 뒤 실제 A/B 세 개까지 허용하고 네 번째를 기존 QUESTION_LIMIT_REACHED로 막는 PostgreSQL 검증이 있다.
- snapshot은 열린 방에서 NEEDS_EDIT의 내부 안내를 전달하지만 wire는 본인의 edit_guidance로만 투영한다. 기존 ownership resolver·revision/generation·현재 candidate·상태·마감 검증을 우회하지 않는다. 안내를 저장해도 accepted나 완료 인원은 바뀌지 않는다. 빈 choices의 숨겨진 ID로 A를 선택할 수 없으며 직접 수정도 새 검사를 거친다. HOST·공개 방·무인증 응답에 본인 안내가 나오지 않고 private 응답의 no-store를 유지한다.
- 기존 서버 fallback은 총 입력 길이·질문 한도·재검사 코드에 맞는 일반 안내다. 모델의 구체적 사유를 받은 것처럼 꾸미거나 개인 수업·퇴근 시각을 지어내지 않는다. 다른 상태의 edit_guidance는 null이다.

## 실제 합성 TF14 한 번의 평가

별도 사전 oracle의 원문은 `목요일에는 제 개인 예약이 끝난 다음에만 만날 수 있어요.`다. 기존 합성 TF11의 수업 문장과 다르며, 빠진 최소 정보인 예약 종료 시각만 묻고 예약 내용이나 임의 시각을 요구하지 않는 결과를 기대했다.

관측 결과는 NEEDS_EDIT이며 안내는 `검색 기간에 해당하는 목요일마다 개인 예약이 끝나는 시각을 알려 주세요.`다. 원문 구간은 정확한 원문 전체이고 choices는 빈 배열이다. 5,183ms, 입력 966 / 출력 212 tokens가 기록됐다. 필요한 종료 시각만 질문하며 숫자·장소·예약 내용을 발명하지 않았다. 길이·원문 포함·단일행·plain text 조건도 만족한다.

- oracle: `docs/evaluation/precheck-edit-guidance-oracle.json`, SHA-256 `4b36477bedcdcdca87430897f61b1ca45a555be5d044467fe2e6f7ff311a0afe`
- 실제 결과: `docs/evaluation/precheck-edit-guidance-observed.json`
- 실제 평가 manifest: `docs/evaluation/precheck-edit-guidance-manifest.json`
- 실제 평가 source fingerprint: `466a9c8b9a5916d73640c2926aac2053f2ea7a602e40c91baeb8c12ebd4140e0`
- 실제 평가 당시 provider contract SHA-256: `e6ecaf11b1fc4918079f430d841b54e9a91687d0f930e4eec9e13f14f89e1416`

원본 `/tmp/precheck-edit-guidance-live-final`과 저장소 결과·manifest 사본은 바이트가 일치한다. oracle·source fingerprint 계산·payload 지문·고정 model/store/service tier/reasoning/output cap·ID TF14·최대1/실행1/retry0을 독립 확인했다.

이 실제 평가 뒤 Bidi 검증을 더 엄격히 했으므로 최종 provider contract 파일 지문은 달라진다. 최종 INSTRUCTIONS를 소스에서 독립 추출해 실제 TF14 요청의 instructions와 정확히 일치함을 확인했다. 나머지 adapter/runtime properties/harness 소스 지문도 실제 manifest와 일치한다. 강화된 validator로 TF14를 다시 호출하지 않았다.

최초 시간 조건 합성6개, 반복 범위 후속1개, 이번 안내1개는 서로 다른 단계의 소스에서 실행한 결과다. 이전 7개는 안내 구현 후 다시 호출하지 않았다. 이전 범위 보존 검토와 TF08의 최종 재검증 부재는 `precheck-time-flexibility-independent-review.md`에 보존했다.

## 최종 검토 소스와 공개 계약

| 파일 | SHA-256 |
| --- | --- |
| src/main/kotlin/com/meetme/server/precheck/adapter/input/web/PrecheckController.kt | 8c0734bdd02c754aa3271a464a66d5d243265bd3777370650c6671208e06f1ab |
| src/main/kotlin/com/meetme/server/precheck/adapter/output/persistence/JdbcPrecheckRepository.kt | 47fe55e3954241ce2c44659a6a293e169a95c796270c75074717e58acd1b4a16 |
| src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt | e66265242f169230270a2b167cfe91dc78a5f9c8a8c8c3b6715a1245b8149e99 |
| src/main/kotlin/com/meetme/server/precheck/application/service/PreSubmissionCheckService.kt | b46581c0e91cec83318f0b3890506cc4b1c4fc2b5a76ccd0f6226d78305a4b26 |
| src/main/kotlin/com/meetme/server/precheck/application/service/PrecheckProcessingService.kt | 729836188c69d2f2bf14b3c7020962815c9de0769e8d61f791616d4256b8f1b9 |

생성 OpenAPI 파일 `docs/openapi/precheck-edit-guidance.openapi.json`의 실제 SHA-256은 `eb8623729afdfa94632701397c5188c2aeb683fc02a09dd18e368948f8f5e5d1`이다. `PrecheckStateResponse.properties.edit_guidance`가 nullable이며 maxLength=160임을 파일과 실제 HTTP 생성 테스트 양쪽에서 확인했다.

## 검증 한계와 남은 작업

전송 assertion·FI는 모델의 일반 의미 정확성 증거가 아니다. TF14 한 번은 단독 Luna 생성의 노출된 합성 사례이며 Jev 실제 분류, 두 단계 지연, 사용자 방과 운영 서버 반영, 개인정보 전체 위협 모델을 검증하지 않는다. 토큰 관측은 실청구 증명이 아니다.

FI는 안내 파싱 거부, 제어문자 검증 제거, SQL A/B 필터 제거, 공개 question 숨김 제거를 대상으로 제안했다. 기존 ownership 알고리즘은 바꾸지 않았으므로 해당 기존 회귀와 새 필드의 본인 한정 검증을 사용하고, 변경된 projection 자체의 결함 주입을 별도로 검증하는 것이 적절하다. 최종 FI와 필수 전체 guard 결과는 담당자가 확정해 연결해야 한다. 원격 이슈 게시 자동심사 거절로 원격 게시·PR·병합·운영 반영은 보류 중이며 이 검토는 그 완료를 의미하지 않는다.
