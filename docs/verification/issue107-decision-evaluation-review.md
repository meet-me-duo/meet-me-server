# #107 분류 평가 corpus 독립 oracle 검토

2026-10-18 UTC. reviewer: `/root/baseline_merge_review`. 기준 HEAD `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df` 위의 작성 중 평가 문서/corpus를 읽었다. 이 리뷰어는 fixture 작성자·프롬프트 구현자가 아니다. 아래 결과는 **평가 준비와 의미 oracle의 사전 검토 PASS**이며 모델 정확도·청구 상한·운영/유료 실행 승인이나 제품 구현 완료 판정이 아니다.

## 범위와 지문

| 파일 | 검토 SHA-256 |
| --- | --- |
| `docs/ISSUE_107_DECISION_EVALUATION.md` | `ca987e5339fc73dbae3dcbc749d824e78fa312a56421cf85342b9d965444af14` |
| `docs/fixtures/issue-107-decision-development.json` | `34f7ad4f06120c6cba6e1869e3e9ef5a9b250e1687765367ae28be96be7323a0` |
| `docs/fixtures/issue-107-decision-holdout.json` | `da6569ee860561dd0d1cb2019ec142bea5b0f8d649f81964ebc3ac0f7ec7c407` |
| 기존 `docs/fixtures/issue-107-pre-submit-evaluation.json` | `063c21f4cb96eddf77578fa2d6947160c000564d872be2b565ce5482687b807f` |

두 split의 120개 원문과 oracle를 전수 읽고 고정 context와 함께 의미를 검토했다. 기존 30개는 원문 중복 및 기준 HEAD의 bytes 보존만 검사했으며 새 모델 실적으로 재평가하지 않았다. fixture·제품·프롬프트·테스트를 편집하지 않았다. 이 리뷰어의 변경 범위는 이 리뷰 파일 하나다.

## 집계와 구조 검사

별도 로컬 Python assertion으로 다음을 재집계했다. 처음 보조 검사에서 기존 fixture의 collection key를 잘못 가정한 오류를 수정한 뒤 아래 검사를 새로 완료했다. 이는 제품/fixture 결함이나 모델 결과가 아니다.

| 항목 | development | holdout |
| --- | ---: | ---: |
| 전체 | 60 | 60 |
| NORMAL / CONTEXT_RESOLVED | 10 / 14 | 10 / 14 |
| AMBIGUOUS | 24 | 24 |
| ADVERSARIAL / UNINTERPRETABLE | 6 / 6 | 6 / 6 |
| CLEAR / NEEDS_REVIEW / INDETERMINATE | 24 / 24 / 12 | 24 / 24 / 12 |
| core false CLEAR 계획 분모 | 36 | 36 |
| 원문 코드포인트 최소..최대 | 18..65 | 19..71 |

- case ID·원문은 새 120개 전체에서 각각 유일하고 기존 30개와 정확 원문 중복은 0이다. 기존 30개의 bytes는 기준 HEAD와 동일하다. 의미 가족까지 독립이라는 판정은 아니다.
- 모든 원문은 1..500 코드포인트이며 NUL이 없다. 두 split의 context가 동일하고 모델 input projection은 원문과 고정 context의 여섯 필드뿐이다. 채점 metadata는 projection에 들어가지 않는다. 실제 전송 경로의 metadata 비노출은 후속 구현 검증 대상이다.
- core flag는 모든 NEEDS_REVIEW/INDETERMINATE에 true, CLEAR에 false이며 label과 일치한다. dimension은 중첩 metadata이므로 dimension 분모를 합쳐 전체 표본 수로 쓰면 안 된다.
- 기준 시각은 `2026-11-04T03:00:00Z` = Asia/Seoul의 11월 4일 12시다. Gregorian 계산으로 기준일 수요일·다음 날 목요일·11월 6일 금요일·7일 토요일·8일 일요일·검색 종료 18일 수요일을 대조했다. 상대 날짜·명시 일자·요일을 연결한 oracle의 확인된 불일치는 0이다. 외부 휴일/개인 달력을 모델 context에 추가하지 않았다.

## 의미 oracle 판정

확정 oracle 오류는 0건이다. 고정 context를 놓고 날짜·시간·장소·지시 대상·부정·예외·적용 범위·자기모순의 결과 영향과, 같은 입력에서 이미 해결된 대조를 확인했다. CLEAR 대조는 단순 후보 존재 여부나 구조화 스키마 지원 여부와 구분돼 있다. 의도한 넓은 범위·OR·개인 기준 이동 제약·타인 의존 조건·의도한 후보 0을 무조건 질문 대상으로 삼지 않는다. 타인 의존에 상대의 실제 일정이 없다는 사실과 지시 대상 자체가 빠진 경우를 구분한다.

공격/손상은 의미 oracle의 INDETERMINATE이며 API refusal·low-confidence·기술 오류와 원인을 합치지 않는다. 이번 공격층은 명시적인 주입·권한 위장·정보 추출·실패 은폐를 다루는 사전 회귀 corpus로 적절하다. 모델이 이 분포에서 성공한다고 실제 공격 방어나 자연어 전체 분포의 정확성이 증명되는 것은 아니다.

의미 경계 한 건은 평가 작성자에게만 case ID로 확인했고, 고정 context 안에서 결과에 영향을 주는 해석을 배제할 수 없다는 근거로 정답 유지에 동의했다. 외부 달력을 발명하거나 특정 달력의 빈 집합을 단정하지 않았다. 경계값 자체가 유효한 역전 범위의 의도 미결과, 실제 존재하지 않는 날짜/비유효 개별 시각의 복원 근거 부족을 구분하는 일반 rubric도 작성자에게 권고했고 최종 평가 문서의 일반 설명 반영을 확인했다. 모델 결과를 보고 정답을 바꾸지 않았고 corpus SHA는 유지했다. 개별 holdout 예제·정답·실패 사례를 이 문서에 인용하지 않는다.

## 분모와 실행 경계

평가 문서는 계획/전송/유효 응답/미실행·오류를 분리하고, 계획 core 36과 실제 실행 core 분모를 함께 공개하도록 한다. 유효 응답 confusion matrix만으로 전체 실행 성공률을 대신하지 않는다. 분모 0은 N/A이며 중단·미완료는 INCOMPLETE다. 모든 응답을 abstain해 false CLEAR 0만 얻은 결과도 전체 품질 통과가 아니라는 경계가 적절하다.

충분한 CLEAR 통과·NEEDS_REVIEW/정상 low-confidence의 생성 재확인이라는 승인된 개발 방향과, API 오류/refusal의 별도 실패를 구분한다. 2차 재CLEAR의 복구와 잘못된 CLEAR를 나누고 실제 불필요 질문/직접 편집 요구는 1차 routing과 별도로 채점해야 한다. 단일 분류만 평가할 때 2차 품질은 N/A다. 전원 2차 진입·별도 count·실패/응답 유실·최종 전체 분석까지 비용 합산하는 식은 평균 절감으로 최대 금액을 주장하지 않는다.

실행 승인 전에 subset·prompt/schema/profile/임계값 SHA와 물리 호출 상한·청구 수량 상한·가격/배수·중단 조건을 고정해야 한다. 12개 진단 pilot은 전체 차원 gate가 아니며, 전체 120개 corpus도 120회 실행 승인이나 예산 승인이 아니다. 검증 가능한 최대 비용은 아직 미입증이다. 이번 리뷰의 외부 API/모델/유료 호출은 0이며 정확도·실청구·p50/p95 실측도 0이다.

## 비노출과 제한

이 리뷰어는 holdout 본문/oracle를 읽었으므로 이후 해당 corpus의 프롬프트 작성자나 비노출 모델 튜닝 담당자가 될 수 없다. root·프롬프트 작성자에게는 집계 결론만 전달하며 개별 case 내용/정답을 전달하지 않았다. 세부 의미 확인은 fixture 작성자에게만 전달했다. 저장소에 파일이 있다는 사실은 접근 통제가 아니므로 향후 prompt/schema/profile/subset 동결과 접근 이력을 별도 보존해야 한다.

두 split은 동일한 의미 가족을 공유하고 명시적인 미결·정정 표현이 많다. 합성 문장의 성공률을 자연 발생 입력 분포, 짧은 생략·구어체, 혼합 공격 입력, 실제 참가자의 의도 분포에 일반화할 수 없다. finite core에서 false CLEAR 0 관측은 운영 오류율 0이나 통계적 상한/SLO를 증명하지 않는다. 실제 모델 평가·품질 채택·예산 원장 구현·전체 AI 경로 연결·운영 활성화는 이 리뷰 범위 밖이며 미완료로 유지한다.
