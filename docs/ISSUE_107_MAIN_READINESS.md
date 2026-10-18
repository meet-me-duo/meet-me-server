# Meet me main 반영 준비 경계

2026-10-17 UTC. 최신 사용자는 웹 기존 main 경로 유지 및 대응 서버 main 병합을 조건부 승인했다. 별도 웹 develop/CI 경로는 만들지 않는다. 검증 없이 즉시 운영에 반영하는 승인으로 해석하지 않는다.

## 현재 판정

2026-10-18 후속: 서버 PR121의 develop01d00e7f·merge CI39070926139는 완료됐다. 현재 별도 feature/issue-107-decision-precheck에서 승인된 Decisions→필요시 생성 LLM의 기본 OFF 구현/독립 검증을 준비하며, 이번 승인은 Draft/exact HEAD CI까지다. 실제 금액/계정/청구/품질·durable 모임 예산/최종 분석 전체 연결은 미완료이고 Decisions의 main/develop 병합·배포는 하지 않는다. 최신 비-Decisions 기존 변경의 develop 통합 승인은 별도 격리 대조로 다루며, 합산 USD1 평가 승인도 [실행 조건](ISSUE_107_USD1_EVALUATION_EXECUTION_PLAN.md)의 비용 증명 전에는 호출하지 않는다. [2단계 계약](ISSUE_107_TWO_STAGE_CONTRACT.md)·[평가/키 준비](ISSUE_107_DECISION_EXTERNAL_EVALUATION.md)를 최신 후속으로 본다. 아래 단일 Gemini 설명과 이전 문서 링크는 기존 runtime 준비 이력이다.

**main 병합은 준비 조건 확인 전 보류.** 구현·격리 테스트·서버 develop 통합은 계속한다. main 병합을 통해 아직 승인되지 않은 DB/Secret/권한/유료 모델 작업을 간접 실행하지 않는다.

서버 PR120은 develop `bb996482a3090739a8ce02c8b093561e2c5c58a2`로 일반 병합됐고 [정확 merge CI39064886318](https://github.com/meet-me-duo/meet-me-server/actions/runs/39064886318)이 성공했다. 웹 PR31 HEAD `02a68d8e50680e87dd153156e8bb7ea884a794cc`의 [CI39063558547](https://github.com/meet-me-duo/meet-me-web/actions/runs/39063558547)도 성공했으며 Draft·미병합이다. 실제 모델/OAuth/운영 준비 성공과 구분한다.

남은 코드·공식 근거 정정·독립 테스트·평가/비용 명세는 [실제 모델 평가 준비안](ISSUE_107_REAL_MODEL_EVALUATION_PLAN.md), 완료된 공개 설정을 반복하지 않는 사용자 순서는 [외부 준비 안내](ISSUE_107_EXTERNAL_READINESS_RUNBOOK.md)를 따른다. 운영자 요약은 기본 OFF로 유지하며 core 출시의 차단 조건이 아니다.

| 확인 대상 | 저장소 근거 | 남은 조건 |
| --- | --- | --- |
| 서버 main 자동 배포 | `.github/workflows/deploy.yml`의 main push CI 성공 후 build/publish, 이후 기존 승인 release contract·host guard | 새 catalog/guard 지문은 기존 승인을 재사용할 수 없음. 실제 현재 host/history/approval의 읽기 검증과 운영 schema 전환 승인 필요 |
| Flyway | `application.yml`은 enabled, 새 기능은 V11~V16 필요 | 운영 DB migration은 현재 미승인. 최근 문서의 V1~V7 기록은 현재 DB 상태 증명이 아님. 키나 DB 값을 읽거나 바꾸지 않았음 |
| 인증 초기화 | `AccountAuthConfiguration`은 signing 설정 전부 비어 있으면 AUTH_UNAVAILABLE, 일부만 있으면 시작 거부. #109는 신규 HOST 생성/명령에 계정 요구 | Google/Kakao 공개 앱 설정 완료 인계 유지. 남은 서버 자격 증명 전달·RS256/검증 JWKS·정확 runtime callback/Origin·실로그인 미확인. 익명 HOST의 명시 claim도 로그인 준비 필요 |
| 운영 인증 전달 | 후속 renderer는 typed AUTH/Google/Kakao·optional AI OFF flag를 원자 전달하며 Compose는 UID10001의 read-only RSA/JWKS bind·missing path 자동생성 금지를 적용한다. auth marker/renderer/compose를 current17 승인 지문에 포함 | 코드·합성54/관련 회귀 검증과 실제 외부 준비는 별개. 정확 후속 HEAD CI·운영 파라미터/파일/권한·실로그인 확인 필요. Secret 읽기/생성/입력0 |
| 웹 main 배포 | `.github/workflows/deploy.yml`은 main push→production Environment→S3/CloudFront | 대응 서버가 새 인증·proof·check protocol을 지원하고 실제 준비됐다는 증거가 먼저 필요. UI를 먼저 배포해 로그인/미지원 API를 강요하지 않음 |
| 새 개인 AI 검사 | 후속 production bean은 `MEETME_PRECHECK_PROVIDER_ENABLED=false` 기본 바인딩·고정 GenerateContent3.8/LOW/2048·count4096 gate/사후 usage 방어를 제공한다. key/임의 verified flag만으로 켜지지 않는다. OFF에서는 CONFIGURATION·count/generation0이며 신규 방 확인을 완료할 수 없다 | **main 사용 차단 조건 유지**. activation/input gate 코드 준비와 실제 지원·billed input cap/청구·모임 총10원 정책 결정은 별개. 실제 opt-in/유료 호출 미승인, 합성 성공이 해소하지 않는다. 조건부 비용 산식을 보장으로 사용하지 않으며 실제 OFF 유지 |
| 복구 | ADR-046/051/052 및 release guard는 구 writer 혼합·잘못된 image rollback을 허용하지 않음 | 신규 schema/protocol 작성 후 구 이미지 rollback 가능하다고 주장하지 않음. 정확 호환 image와 roll-forward·복구 절차를 검증하고 운영 전환 별도 승인 |

## 안전한 확인·실행 순서

1. 통합 코드의 독립 TDD/PG/proof/synthetic provider/browser·결함 주입·전체 gate·독립 최종 리뷰와 exact HEAD CI.
2. 서버 develop의 승인된 단위를 통합하고 exact merge SHA CI 확인. 원격 개발 URL이 없는 현 상태는 별도 알림.
3. 아침에는 새 통합의 정확 SHA/JAR·웹 자산 hash, 격리 PG/Redis, loopback OIDC+합성 RSA/provider, 외부 요청 차단과 합성 계정으로 시나리오 재현. 문서의 운영 URL을 개발 URL로 사용하지 않음.
4. main 전 필요한 외부 조치는 이름·존재/상태·승인만 확인하는 별도 체크로 묶어 부모에게 보고. 비밀값을 채팅/저장소/로그에 요청하거나 출력하지 않음. 미승인 설정을 우회한 임시 로그인·익명 HOST fallback도 추가하지 않음.
5. 위 조건이 충족된 조합만 서버 호환성/데이터 전환을 먼저 확인하고 웹 main을 이어 처리. 실제 배포 완료는 정확 커밋/image·workflow와 실행 증거가 있을 때만 기록.

현재 운영 endpoint·AWS/SSM·DB·Secret은 호출하지 않았다. 별도 staging/develop 서버 주소·현재 실행 SHA는 읽은 저장소 자료만으로 확인되지 않았다. 현재 격리 native API18920/web18921/control18922를 준비했다. 샌드박스 내부 주소이며 공개 staging이 아니다. 상세 `ISSUE_107_LOCAL_SCENARIO.md`.
