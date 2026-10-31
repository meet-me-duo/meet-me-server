# pilot p2 개인 Luna 반사실 진단 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

실제 개인 Luna 요청 **1회, HTTP200, CLEAR**. 질문과 선택지는 없었다. 원본 V2 개인 생성 어댑터와 validator를 사용했다.

모델 응답 본문은 공개하지 않는다. 관측 verdict는 CLEAR였고 질문·선택지는 없었다.

## 범위와 맥락

승인 `synthetic-authorization-bf0363f8c63841a9`, plan `d8724359-961c-4488-9983-0e61333f5da6`.

기존 pilot p2는 Jev semanticAssessment=NEEDS_REVIEW/confidence0.25가 INDETERMINATE로 정규화되어 개인 Luna를 호출하지 않았다. 그 결과와 기존 실패 실행은 바꾸지 않았다. 이번은 그 라우팅을 통과했다고 가정하고 생성기를 직접 호출한 별도 반사실 진단이다.

원문은 기존 `pilot/participant-p2.json`의 rawInput 그대로이며 원문 기준 시각 `2026-10-30T07:15:06.963374Z`, 원본revision1/PENDING V2 evidence를 유지했다. 기간2026-11-12 포함~2026-11-16 미포함, Asia/Seoul, ko-KR, 소요 시간 미지정이다. 생성기 payload에는 Jev의 라벨·신뢰도나 다른 참여자 입력을 추가하지 않았다.

원본 `LunaPreSubmissionCheckProvider.runtime()`의 공식 endpoint와 현행 V2 prompt/schema를 사용했다. 모델gpt-6-luna, Standard(service_tier=default), low reasoning, max_output_tokens2048, store=false. schema verdict는 CLEAR/NEEDS_CHOICE만 허용했다. body SHA256 `ad6e0d73d76e56e29ef03e0e4cda43b68573765abcd3905f7c0369325ee9e28d`.

## 관측

| 항목 | 결과 |
|---|---|
| 실제 유료 물리 요청 | 개인 Luna1, Jev0/Gemini0/최종분석0 |
| 재시도·redirect·인증 재요청 | 0, 단회 승인claim·전송 직전 영속 원장 사용 |
| HTTP | 200, CALL_START/HEADERS_START/HEADERS_END/CALL_END 각1 |
| 어댑터 전체 시간 | 3.178214665초 |
| HTTP 클라이언트 시간 | 3.153394007초 |
| 설정 시간 상한 | 15초 |
| 실제 클라이언트 timeout | 14.999초; 헤더 전송 시작 때 남은 deadline 약14.810초 |
| 토큰 | input971 / output119 / total1090 |
| reasoning tokens | 86, output119에 포함; 추가 합산하지 않음 |
| 실제 청구액 | 미확인 |
| 결과 | CLEAR, prompt=null, sourceExcerpt=null, choices=[] |

network interceptor에서 보존한 `response.json`은 gzip wire 바이트였다. 오프라인에서 제한 길이 내 압축을 해제해 `response-decoded.json`과 `provider-output.txt`를 보존했다. 이는 재요청이 아니며 원본 wire도 유지했다.

## 검증·보존·한계

- 무료 loopback 어댑터/동일payload/단회 원장 검증1PASS, manifest 부작용 없는 검증1PASS, ktlintCheck PASS.
- 실제 진단 JUnit1PASS, Gradle exit0. 실행 전후 source/compiled/입력/runner 등2676파일 해시 일치.
- manifest SHA256 `bcf984f87d4fcfdc2a255768d128616273d195fbbe7f6b1f262c89d1ff898507`.
- 결과 SHA256 `9b199d28315146710475a19b91464c68a859ff38395094669a6e05f75cdd1d6d`.
- HEAD b4b3a8c54938b1f28dd4f1cd782a7333971ce24a. production prompt/threshold/분기/시간 제한 변경, 병합·운영 배포 없음.
- 이전 blind20의2667개 source/compiled 파일은 새 컴파일 전에 별도 archive로 보존했다. 기존 결과·봉인 시나리오 변경/봉인 재열람은 없다.

이것은 직접 어댑터 진단이며 실제 Jev 분기나 PostgreSQL 실행 권한 연결을 검증한 것이 아니다. 실제 개인 경로는 Jev와15초를 공유하지만 이번은 Jev를 호출하지 않아 최대15초 전체를 사용할 수 있었다. 따라서 실제 분기의 남은 시간 안에서 같은 결과가 나오는지, 최종 분석·추천이 성공하는지는 이 진단으로 주장하지 않는다. CLEAR이므로 질문을 만들어 채우지 않았다. 추가 요청 없이 종료했다.

원본 결과 경로: `local-only-evidence/p2-personal-once-d8724359-961c-4488-9983-0e61333f5da6`.
준비와 승인manifest: `local-only-evidence/p2-personal-once-preparation-20261030`.
