# Meet me main 반영 준비 경계

2026-10-17 UTC. 최신 사용자는 웹 기존 main 경로 유지 및 대응 서버 main 병합을 조건부 승인했다. 별도 웹 develop/CI 경로는 만들지 않는다. 검증 없이 즉시 운영에 반영하는 승인으로 해석하지 않는다.

## 현재 판정

**main 병합은 준비 조건 확인 전 보류.** 구현·격리 테스트·서버 develop 통합은 계속한다. main 병합을 통해 아직 승인되지 않은 DB/Secret/권한/유료 모델 작업을 간접 실행하지 않는다.

| 확인 대상 | 저장소 근거 | 남은 조건 |
| --- | --- | --- |
| 서버 main 자동 배포 | `.github/workflows/deploy.yml`의 main push CI 성공 후 build/publish, 이후 기존 승인 release contract·host guard | 새 catalog/guard 지문은 기존 승인을 재사용할 수 없음. 실제 현재 host/history/approval의 읽기 검증과 운영 schema 전환 승인 필요 |
| Flyway | `application.yml`은 enabled, 새 기능은 V11~V16 필요 | 운영 DB migration은 현재 미승인. 최근 문서의 V1~V7 기록은 현재 DB 상태 증명이 아님. 키나 DB 값을 읽거나 바꾸지 않았음 |
| 인증 초기화 | `AccountAuthConfiguration`은 signing 설정 전부 비어 있으면 AUTH_UNAVAILABLE, 일부만 있으면 시작 거부. #109는 신규 HOST 생성/명령에 계정 요구 | 실제 RS256·검증 JWKS·Google/Kakao client/redirect/Origin 준비와 실로그인 미확인. 익명 HOST의 명시 claim도 로그인 준비 필요 |
| 운영 인증 전달 | `deploy/scripts/render-runtime-env.sh`에 AUTH/Google/Kakao 변수 전달이 없고 `deploy/compose.production.yml`은 runtime env만 읽으며 RSA/JWKS mount가 없음 | 비밀값 없는 전달·mount/권한 코드의 검증된 준비안과 사용자 소유 외부 설정이 필요. 이번 조사에서 Secret 읽기/생성/입력0 |
| 웹 main 배포 | `.github/workflows/deploy.yml`은 main push→production Environment→S3/CloudFront | 대응 서버가 새 인증·proof·check protocol을 지원하고 실제 준비됐다는 증거가 먼저 필요. UI를 먼저 배포해 로그인/미지원 API를 강요하지 않음 |
| 새 개인 AI 검사 | 실제 bean의 `PrecheckProviderOptions.totalOutputTokenCapVerified=false`가 호출 전 CONFIGURATION으로 차단한다. 신규 방은 PRE_SUBMIT_V1이 기본이므로 이 상태로 운영에 반영하면 모든 신규 입력의 확인 완료가 불가능하다 | **구체적인 main 사용 차단 조건**. reasoning 포함 과금 토큰 상한·실제 provider 지원 검증 및 검증된 활성화 준비가 필요하다. 실제 유료 호출은 미승인, mock/@Primary 합성 provider 성공이 이 조건을 해소하지 않는다. 고정 입력/호출수/공식 가격 기반 최대 비용/중단 조건을 제안한 뒤 승인 대기하며 우회 활성화0 |
| 복구 | ADR-046/051/052 및 release guard는 구 writer 혼합·잘못된 image rollback을 허용하지 않음 | 신규 schema/protocol 작성 후 구 이미지 rollback 가능하다고 주장하지 않음. 정확 호환 image와 roll-forward·복구 절차를 검증하고 운영 전환 별도 승인 |

## 안전한 확인·실행 순서

1. 통합 코드의 독립 TDD/PG/proof/synthetic provider/browser·결함 주입·전체 gate·독립 최종 리뷰와 exact HEAD CI.
2. 서버 develop의 승인된 단위를 통합하고 exact merge SHA CI 확인. 원격 개발 URL이 없는 현 상태는 별도 알림.
3. 아침에는 새 통합의 정확 SHA/JAR·웹 자산 hash, 격리 PG/Redis, loopback OIDC+합성 RSA/provider, 외부 요청 차단과 합성 계정으로 시나리오 재현. 문서의 운영 URL을 개발 URL로 사용하지 않음.
4. main 전 필요한 외부 조치는 이름·존재/상태·승인만 확인하는 별도 체크로 묶어 부모에게 보고. 비밀값을 채팅/저장소/로그에 요청하거나 출력하지 않음. 미승인 설정을 우회한 임시 로그인·익명 HOST fallback도 추가하지 않음.
5. 위 조건이 충족된 조합만 서버 호환성/데이터 전환을 먼저 확인하고 웹 main을 이어 처리. 실제 배포 완료는 정확 커밋/image·workflow와 실행 증거가 있을 때만 기록.

현재 운영 endpoint·AWS/SSM·DB·Secret은 호출하지 않았다. 별도 staging/develop 서버 주소·현재 실행 SHA는 읽은 저장소 자료만으로 확인되지 않았다. 현재 격리 native API18920/web18921/control18922를 준비했다. 샌드박스 내부 주소이며 공개 staging이 아니다. 상세 `ISSUE_107_LOCAL_SCENARIO.md`.
