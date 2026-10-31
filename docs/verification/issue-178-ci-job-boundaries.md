# CI 품질·배포 계약 작업 분리

2026-10-31. [Issue178](https://github.com/meet-me-duo/meet-me-server/issues/178)은 기존 검사를 유지하면서 전체 job 시간 초과를 해결한다. 기준은 develop `8b36fd5415bc4773e91f29fedc5ddf257ef6b12d`다. 애플리케이션·SQL·provider timeout/retry·API·권한·운영 설정은 변경하지 않는다.

## 관측한 문제

[기존 CI41188585976](https://github.com/meet-me-duo/meet-me-server/actions/runs/41188585976)은 두 시도 모두 GitHub의 최대 job 실행시간30분 초과로 취소됐다. 두 번째는 개별 실행 단계가 모두 성공했어도 job/workflow 결론은 CANCELLED다. 이를 CI 성공으로 바꾸거나 배포 gate를 우회하지 않는다. 동일 job의 추가 반복 대신 작업을 나눈다.

## 변경 계약

| Job | 검사 | 유지하는 경계 |
| --- | --- | --- |
| Quality Gate | 기존 checkout·Java17·Gradle ktlintCheck/assemble/test | 원문 명령·ubuntu-latest·30분·readonly |
| Deployment Contracts | 독립 checkout·Java17, 새 구조 검사1개, 기존 배포 계약16단계 | 원문 명령·각 단계 순서·30분·생산 권한 없음 |
| ARM64 Built Image | 기존 빌드·격리 실행·정제 artifact | job 전체 원문, push:false·readonly |

기존18개 run 명령은 정확히 한 번 실행하고, 구조 검사 명령1개만 추가한다. 세 job은 needs/조건부 skip 없이 독립 실행한다. CI 이름·trigger·concurrency·권한을 유지하고 모든 필수 job이 성공해야 전체 CI가 성공한다. 기존 main push CI 전체 성공을 요구하는 게시 workflow gate도 바꾸지 않는다.

새 계약 검사는 Node builtin만 사용한다. 공개 baseline에서 고정한 root·setup·18명령·ARM64·게시 gate와 실제 CI 원문을 비교해 검사 누락/복제·순서·실패 무시·권한 확대를 거절한다. 의도적으로 엄격한 비교이므로 향후 승인된 formatting/구조 변경도 frozen 계약을 함께 검토해야 한다.

## 완료한 로컬 검증

- 독립 설계한6개 계약 검사는 기존 구조에서2 PASS/4 FAIL로 RED를 확인했다. 새 job 부재와 quality에 남은 배포 단계 때문에 실패했으며 컴파일/fixture 오류가 아니다.
- 같은 테스트를 변경하지 않고 workflow를 나눈 뒤6 PASS, 기존 관련 배포·이미지 source 계약25개와 함께31 PASS/0 FAIL/0 SKIP를 확인했다.
- 독립 검토에서 baseline fixture와 공개 source가 같고 승인된 변환만 적용됐음을 확인했다. moved script에서 Gradle build output 의존은 발견하지 못했다.
- scratch-only 결함주입27개를 모두 탐지했다. Nginx/V22 누락·복제·명령 변경·실패 무시·job/step skip·직렬화·timeout 증가·권한/production 환경 추가·ARM64 push·main CI 게시 gate 약화를 포함한다. 원본 CI/deploy/test/fixture 해시는 전후 동일하다.

RED 요약은 [.tdd 기록](../../.tdd/red/issue178-ci-job-boundaries.json), 독립 검토·mutant 요약은 [검토 기록](../../.tdd/reviews/issue178-ci-job-boundaries.json)에 있다. 원본 로그는 로컬에만 보존한다. 테스트 SHA는 `cc0f968a32d03cde3789e694ce04418fbcab3fa5188c165df9965b55f7d81232`, fixture SHA는 `8129fbf885d29863a33b27e746ae37482ae1f8d8448275e3853ceec1b6126eff`다.

## 원본 전체 가드와 추가 감시 결과

수정하지 않은 `.codex/hooks/tdd_guard.py`로 ktlintCheck·assemble·test를 실행해 EXIT0을 확인했다. 2026-10-31 10:56:09~11:14:25 UTC, 1,095.756초이며 2,675개 중 2,658 PASS/기존 SKIP17/FAIL0/ERROR0이다. 이전 검토 소스의 skip17개 이름도 같고, 검사 대상 source/test/build/CI679개 해시는 실행 전후 동일하다.

별도 로컬 네트워크 감시 wrapper는 Gradle daemon의 비loopback IPv6 연결 시도12건을 차단해 EXIT1을 반환했다. 원본 가드 EXIT0과 별개의 결과이며 숨기거나 assertion을 완화하지 않았다. 차단은 실제 connect 전에 이뤄졌고, provider 키·live flag는 미설정이었다. 감시에는 목적지/호출 stack이 없어 해당 시도의 원인은 미확인이다. 실제 모델·지도 요청은 수행하지 않았다.

## 남은 검증

정확 feature/merge SHA의 GitHub CI 종료는 아직 확인 전이다. 로컬 source 계약 검사는 실제 runner 분리·성능·전체 CI 성공을 대신하지 않는다. 실제 완료 뒤 PR과 별도 결과 기록에 정확 SHA/CI URL을 남긴다.

이번 CI 수정은 main 승격·운영 배포·DB 적용·V2 활성화·구 웹 혼합 버전 호환의 해결을 뜻하지 않는다. 유료 모델·지도 요청과 봉인 자료 열람은 하지 않는다. 기존 사업 데이터와 모델 응답·운영 원문 로그는 게시하지 않는다.
