# 장소 진단 후속: 전체 회귀 실패 원인과 무료 검증 마무리 (2026-10-30)

> 공개용 역사 기록: 아래 수치와 실행 제한은 해당 실험 당시의 결과다. 원본 응답·로그·개인정보·내부 대화 식별자는 게시하지 않는다. 현재 통합 소스의 검사 상태는 [게시 준비 요약](issue-176-publication-readiness.md)을 따른다.

이 문서는 앞선 `experimental-place-diagnostics-result.md`의 마지막 전체 회귀 실패1건을 후속 조사한 기록이다. 생산 코드의 정책·시간 제한·모델·프롬프트·판정 기준은 추가로 변경하지 않았다. 수정은 무료 통합 테스트의 상태 소유 범위에 한정한다.

## 확인된 원인과 이전 해석의 정정

`BlindFlowHttpContractTest`와 `LunaFallbackOnceApplicationContractTest`는 서로 다른 앱 컨텍스트와 DB를 사용하면서도 `LunaFallbackOnceSettings.current`라는 하나의 lazy 객체를 공유했다. 그 객체에는 primary/Luna 호출 수, 최초 승인한 source 요청, mock 응답, wire ledger와 파싱 기록이 모두 들어 있었다.

앞선 전체 실패 자료(`local-only-evidence/results.json`)의 핵심은 **합성 primary 호출 수2, Luna 호출 수1**이다. 먼저 실행한 BlindFlow HTTP 검사는 성공했고, 후행 fallback 검사의 primary 대역은 `incrementAndGet()==1`에서 실패했다. 이 `IllegalStateException`은 의도된 합성503 파싱 예외가 아니므로 후행 검사는 Luna 단계에 진입하지 못하고 Gemini attempt도 미완료로 남았다.

따라서 당시 전역 기록의 “Luna 파싱3건 성공”은 **이전 테스트의 기록**이다. 이를 현재 호출의 성공 응답이 DB에 반영되지 않은 증거로 해석한 것은 잘못이었다. 현재 DB의 structured0과 전역 파싱 기록의 불일치는 서로 다른 실행의 관측을 섞은 것으로 설명된다. invocation이 나중에 종료된 정확한 recovery 경로는 당시 상관 ID 부족으로 별도 미확인이다.

컨테이너 연결 속성을 불변 값으로 캡처한 앞선 변경은 실제로 확인된 별도 설정 격리 결함을 고쳤다. 하지만 호출 카운터·source·mock 응답의 전역 공유는 해결하지 않았으며, 이번 실패의 원인으로 그 캡처 변경을 지목하지 않는다.

## 직접 재현과 최소 수정

값 캡처가 적용된 소스에서 두 기존 앱 검사를 같은 JVM으로 실행해 **앞 검사PASS/후행 검사FAIL·primary2**를 재현했다. 원문·응답·시간 제한·기존 assertion을 바꾸지 않았다. 증거는 `place-regression-closure-20261030/pair-before-evidence.json`이다.

무료 settings 전체를 Spring 컨텍스트별 singleton Bean으로 생성하고 parser·source guard·wire hook·검사 결과 수집에 그 같은 객체를 주입했다. BeanPostProcessor는 필요한 분기에서만 ObjectProvider를 평가하고 상태를 closure로 고정한다. 카운터만 초기화하거나 `==1`을 완화하지 않았다.

유료 모드는 원래 `current` 객체를 그대로 반환한다. manifest·claim·source 승인·1회 제한·27초 제한은 유지하며, 잘못된 mode도 기존 로더가 거부한다. properties는 유료 검증과 claim을 컨테이너 시작보다 먼저 수행한다. 기존 직접 호출 guard 테스트는 명시적인 무료 settings 인자만 추가했고 delegate-first 검증을 유지했다. 제거한 settings.stop 호출은 원래 구현이 비어 있는 함수였다.

수정 후 같은 두 앱과 source/한도 계약을 합친 **5개 검사 모두 PASS, ktlint PASS**다. 수정 전과 반대 순서로 실행된 두 번째 앱도 정상 종료했다. 현재 fallback의 자체 기록은 primary1/Luna1/파싱3/DB structured3/후속검증PASS/실제 wire0으로 일치한다. 이는 전역 상태 공유를 제거한 효과의 직접 증거이며 연결 설정 캡처와 구분된다.

## 외부 API 차단과 실행 보존

검사 자식 환경에서 provider key와 모든 실호출 mode를 제거하고 Gradle을 offline으로 실행한다. 추가로 LD_PRELOAD guard가 비루프백 INET `connect`를 EACCES로 거부하고 루프백·Unix socket만 허용한다. JDK Socket/HttpClient의 외부 연결 거부와 루프백 성공을 자체 검사했다. managed proxy가 비루프백 주소임도 값 출력 없이 확인했다. 감사 로그에는 종류·PID·address family만 남기며 원문·헤더·키·주소 값은 남기지 않는다.

이 차단은 preload가 적용된 libc connect 경로의 증거다. DNS·직접 syscall·외부 daemon까지 모두 차단했다고 확대하지 않는다. 이번 회귀의 모델 SDK는 통제된 JDK 프로세스와 mock 응답을 사용하며 실호출 mode는 비활성이다. 집중 재현의 외부 connect 시도는0이었다.

연결 끊김 뒤 14:13:33 UTC에 기존 결과를 먼저 회수했다. 수정 후 집중 검사는 14:11:29 UTC exit0으로 종료돼 있었고, 최종 전체 시작 기록은 없었다. 결과를 성공으로 추정하거나 중복 실행하지 않았다.

## 최종 전체 회귀

최종 전체는 `run.py final-full ktlintCheck assemble test` 한 번이며 `final-full-started.json`의 독점 시작 기록으로 중복 실행을 막았다. 원문·기대값 완화, 시간 제한 확대, 신규 테스트 제외는 없다. 기존 검사의 비활성 조건은 유지했다.

기준 HEAD는 `b4b3a8c54938b1f28dd4f1cd782a7333971ce24a`와 보존된 V2 작업 트리다. src/main·test·build 설정660파일의 지문은 실행 전후 모두 `28e36183bb01d77adaa1a3fb0aa60f141aa3f2978ad43bbb8e1d2d82dc7907f0`이며, 생산 소스는 앞선 전체 검사 이후 불변이다.

| 최종 확인 | 결과 |
|---|---|
| 전체 실행 | 2026-10-30 14:15:28–14:33:09 UTC, Gradle exit0 |
| 전체2610건 | **2593PASS /0FAIL /0ERROR /17SKIP** |
| ktlintCheck /assemble | PASS /PASS |
| 기존 실패의 선행 BlindFlow HTTP | PASS, 14:21:20 시작 |
| 후행 Luna fallback | PASS, 14:22:33 시작, 컨텍스트 자체 primary1/Luna1/DB structured3/확정 검증PASS |
| 건너뜀 집합 | 앞선 전체 검사와 동일17건, 새 제외 없음 |
| 실행 소스660파일 /원본 공급자 자료4개 /기존 소비claim | 모두 불변 |
| 연결 차단 | 실제 test worker PID120806의 guard 활성 확인, 전체 외부 connect 시도0 |

단일 전체 실행에서 두 앱이 선행·후행으로 모두 통과해 원래 실패 순서까지 검증했다. 이전 단계의 집중 검사들을 합산해 전체 성공으로 대신한 결과가 아니다. 연결 guard 자체 검사에서 TEST-NET 외부 Socket/HTTP 연결은 권한 거부됐으며, 실제 전체 회귀에는 외부 연결 시도조차 없었다. 실제 SDK 요청은 mock 응답/루프백을 사용했고 신규 외부 모델 요청0이다.

남은 미검증은 실제 모델의 의미·지리 정확성, 실제 도보 제한 충족과 봉인 평가다. 이것들은 이번 무료 회귀의 차단 사유가 아니며 지원 정책도 바꾸지 않았다. 당시 invocation 종료의 세부 recovery 경로는 확정하지 않았지만, 후행 primary의 strict once 실패와 전역 기록 혼합이라는 원인은 기존 코드·직접 재현·수정 후 같은 순서의 전체 실행으로 확인했다.

증거 폴더: `local-only-evidence/place-regression-closure-20261030`. source diff, `causal-proof.json`, 실행별 로그/XML·guard 증거를 보존한다. 게시·병합·배포·운영 접근 및 추가 유료 API 요청은 없다. 실제 모델 정확성·도보 충족·봉인 사례 평가는 이번 검증에 포함하지 않는다.
