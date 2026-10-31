# 최종 Luna 단발60초 진단 결과 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

**실제 Luna1회가 HTTP200/completed로 성공했다. Call33.296초·진단 전체33.451초로 기존 앱의27초보다 늦게 응답했다.** 원본 파서는 입력3개를 수용했고 제외0개다. 독립 검토에서 세 사람의 시간 집합은 고정된 의도와3/3 일치했다. 장소 선호 해석 의심1개와 후처리 검증 한계는 남긴다. 앱 제한 변경이나 전체 E2E 성공은 아니다.

## 승인·격리·요청

- 사용자 승인 `synthetic-authorization-ead1921d8663f419`: 기존 pilot10 동일 논리 입력, 실제 최종 Luna 최대1회·60초·retry0. Jev/Gemini0, 앱27초/운영/병합 불변.
- run `e68de3eb-812a-4fc6-8b78-237ec77ea034`. 신규 approval claim·intent·단발 wire ledger를 각각 한 번만 소비했다. 요청은1회이며 재시도·후속 호출0이다.
- 원본 pilot10 DB의 동결 version3개를 골라 원문·원래 기준시각·보완 질문/답변·답변 기준시각·이전 미해결 관측을 그대로 재구성했다. p1 UNRESOLVED 및 실제 보완1개도 보존됐다. 이전 wire 원본은 없어 과거 요청과 바이트 단위 동일하다고 주장하지 않는다.
- 원본 `NaturalLanguageProviderContract.prompt`와 v3 schema로 body를 생성했다. 실제 body **16,125B**, prompt UTF-8 **10,324B**, schema JSON **5,423B**다. 본문 SHA256 `18ae6b7df69cecbac7b99316ba2ea305f323df8933d7bef825f6ed11d7308f93`는 준비·network interceptor·실제 requestBodyEnd의16,125B 관측과 일치한다.
- 요청은 `https://api.openai.com/v1/responses`, `model=gpt-6-luna`, `store=false`, strict `meet_me_conditions_v3`, max_output_tokens32768이다. reasoning/service_tier를 추가 지정하지 않았다. 응답 메타데이터는 **reasoning effort=medium, service_tier=default**를 보고했다. 과거 실패의 적용값까지 소급 확정하는 자료는 아니다.

## 시간과 HTTP 관측

| 항목 | 관측값 |
|---|---:|
| 진단 Call timeout | 60.000초 |
| 실제 Call 경과 | **33.295569652초** |
| client 생성·정리 포함 진단 경과 | **33.450660424초** |
| 요청 body 완료 시점(진단 시작 기준) | 0.331714111초 |
| 응답 header 시작 시점 | 33.437349890초 |
| body 완료→응답 header 대기 | **33.105635779초** |
| HTTP/응답 status | **200 / completed** |
| 응답 body | 55,512B(클라이언트가 읽은 body, TLS 트래픽 크기 아님) |

HEADERS_START/HEADERS_END/REQUEST_BODY_END/CALL_END는 각각1회이고 본문 전송량도 준비 bytes와 같다. 연결과 요청 전송은 초기에 끝났고 대부분은 응답 header 대기 구간이었다. 이를 공급자 내부 queue·추론·프록시 중 특정 요소의 소요 시간으로 분해할 trace는 없다.

이 결과는 같은 논리 입력이 **27초를 넘겨 유효한 응답을 반환할 수 있음**을 확인한다. 앞선 실패가 입력 크기·reasoning·부하·네트워크 중 무엇 때문이었는지 또는 이번과 같은 원인이었는지까지 증명하지 않는다. 별도 진단은 원래 앱 adapter/JDBC authority의27초 제한을 통하지 않으며 새1회/60초 guard를 사용했다. 운영 timeout60초 채택의 적합성이나 앱 E2E 성공으로 확대하지 않는다.

## 구조화 결과와 독립 의미 검토

원본 `parseProviderResponse(strict=true)`의 수용 결과는3개, effective rejection0개, 조건 수11/7/11이다. `strictContractValid=true`는 원본 파서 수용을 뜻하며 전체 의미 정답이나 raw response의 별도 완전한 JSON Schema 검증을 뜻하지 않는다. 파서는 일부 조건을 fail-soft 처리할 수 있어 raw와 effective 결과를 구분했다. 이번 조건 수/유형은 raw와 같았다.

독립 검토(`blind_author_v2`)는 기존 pilot 원문·고정 답변·gold만 사용했다.

- 입력 참조·원문·보완·시간 기준·미해결 메타데이터 연결3/3 일치.
- AVAILABLE 합집합에서 UNAVAILABLE를 뺀 각 개인의 시간 집합이 고정 gold와 분 단위3/3 일치. 특정 요일 선호와 통화 불가 구간도 보존됐다.
- p1은 이미 제공된 보완 답변의 도착 가능 시각으로 해석됐다. 이전 Jev의 미해결 관측만을 이유로 재제외하지 않았고 다른 참여자의 시간으로 개인 사실을 발명한 흔적은 발견하지 못했다.
- 원본/유효 `rejection_code=null`3건은 시간 해석 관점에서 타당하다. 잘못 제외한 입력은 관측되지 않았다.
- **의심1개:** p2의 일반 허용 지역 표현이 추가 `PREFERRED_PLACE`로 해석됐다. 구어상 선호로도 읽힐 수 있어 확정 오류로 단정하지 않지만 고정 의도보다 선호 차원을 추가했을 가능성이 있다. 조건이나 gold를 이에 맞춰 수정하지 않았다.

미검증: 파서 이후 각 조건 필드 값 전체의 동등성, 도보 제한 집행/도착 시각과 이동 시간 중복 차감 여부, 실제 장소·거리 계산, 서버 후보·점수·추천·미반영 화면·권한·주최자 확정 저장. 이 단발 진단은 앱이나 DB를 실행하지 않았다.

## 사용량과 비교

| 관측된 token | 값 |
|---|---:|
| input_tokens | **3,317** |
| output_tokens | **3,203** |
| output 내 reasoning_tokens | **1,946** |
| total_tokens | **6,520** |

추론1,946은 출력3,203에 포함된다. cache_write_tokens3314도 입력 세부 메타데이터이며 별도로 더하지 않는다. 청구액은 확인하지 못했다.

이전 단순3입력 성공은 input2879/output355, 이번은3317/3203이다. 입력 token은438 늘었고 출력(추론 포함)은 크게 늘었다. 과거 성공의 reasoning 세부값은 보존되지 않아 같은 effort였는지 비교할 수 없다. 이 수치만으로 지연의 인과를 확정하지 않는다.

## 검증·보존 증거

- 새 무료 전송4개+payload 준비1개+별도 source/payload preflight1개, 합계 **6PASS**. ktlintCheck/assemble PASS.
- 60초 상한을 제거한 로컬 결함1개를 해당 경계 테스트가1FAIL로 탐지했다. probe를 바이트 단위 정확히 복원하고 최종 검사했다. 모델 호출이나 기존 완료 회귀 전체를 반복하지 않았다. 첫 무료 검사에서는 포맷만 실패했으며 테스트5개는 통과했고 최종 포맷·검사 모두 통과했다.
- 실제 유료 단발 검사 **JUnit1PASS·Gradle exit0**. 독립 guard 검토에서 claim·정확1회/본문/시간/usage·원본 보존을 확인했다. 실제 성공과 무료 모의 성공을 분리한다.
- manifest SHA256 `e5b6487a3d34761fa56f8766c6e7d822b568e7eb956665f0e6fdf8ef93d917dd`, source aggregate `a464cbce99418fbacb5116acd177e93ef57f4db8555ecb9629fc3f8126fa07be`.
- source/compiled/config/data2734개 전후 해시 일치, main source289개·기존 pilot10 원본DB/results/ledger3개 불변. 이전 pilot10의2713개 bound source/compiled도 신규 컴파일 전에 일치 검증 후 별도 archive로 보존했다.
- 준비/manifest/무료 검사/보존 proof: `local-only-evidence/final-luna60-20261030`.
- 실제 ledger/HTTP 결과/원본 response/structured output/파서 수용/독립 의미 검토: `local-only-evidence/final-luna60-e68de3eb-812a-4fc6-8b78-237ec77ea034`.
- 수치·실행/보존 검증: 준비 폴더 `post-run-verification.json`; 원본 모델의 reasoning item 내용은 검토·인용하지 않고 usage 숫자만 사용했다.

승인된1회는 종료됐다. 추가 API0·Jev/Gemini0·봉인 열람0·앱27초 변경0·commit/PR/병합/배포0이다. 앱의 최종 시간 예산과 전체 흐름 재검증은 별도 결정으로 남긴다.
