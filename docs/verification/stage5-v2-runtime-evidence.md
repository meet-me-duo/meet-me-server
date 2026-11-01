# 신규 V2 모임 설정 전달 검증 — 2026-11-01

기준 develop은 `422d8c26b24f7bce6a105f49b5f2efeafc57e5b3`이다. 이번 로컬 변경의 병합·배포·활성화·유료 검증은 수행하지 않았다. 기존 Stage4 배포 완료 인계와 이번 준비를 구분한다. 제품 정책은 [ADR-068](../ADR.md)과 [V2 계약](../EXPERIMENTAL_INPUT_EVIDENCE.md)을 따른다.

## 변경과 운영 경계

renderer14줄 추가로 기존 optional nonsecret SSM String `config/precheck-evidence-enabled`를 `MEETME_PRECHECK_EVIDENCE_ENABLED`에 전달한다. 이 이름은 기존 `@Value("${meetme.precheck.evidence-enabled:false}")`에 대응한다. 정확한 Name·Type·boolean을 확인하며 미등록/조회 실패는 OFF, malformed 성공 응답은 이전 env를 보존하며 거부한다. 기본 OFF·false 재전달은 이전 true 행을 제거한다. Compose raw env_file이 실제 app 환경에 값을 보존하므로 shell 기본값 override를 추가하지 않았다.

provider/routing/classifier 활성화·재정 권한·IAM·비밀·DB migration·Kotlin 제품 동작·최종 matcher·UI를 바꾸지 않았다. app 재시작 후 성공한 신규 INSERT만 protocol을 고정한다. OFF 복원 이후에도 기존 V2 reader·원문/명시 답변·해석 미확정 정보·frozen accepted version과 조건부 참석 guard를 유지한다. 새 값을 운영에서 읽거나 등록/활성화하지 않았다.

## 최종 실행 결과

| 검사 | 결과 |
| --- | --- |
| 원본 `.codex/hooks/tdd_guard.py`의 ktlintCheck·assemble·test | exit0, 978.996초 |
| 전체 JUnit XML346개 suite | 2,702건 중 **2,685 PASS / 17 SKIP / 실패·오류0** |
| 기존17 SKIP 이름/클래스 | 이전 최종 XML과 동일 |
| 원본 guard / 검사 중 source | 바이트 지문 불변 |
| 실제 CI auth 명령의 runtime·Compose·raw JSON 검사 | **82 PASS** |
| 운영자 runtime 회귀 | **90 PASS** |
| 기존 production AI 준비 Python 검사 | **8 PASS** |
| 신규 runtime 집중 계약 | RED7 FAIL → GREEN7 PASS, 역방향 provider 독립1 PASS |
| 독립 환경 이름·실제 PostgreSQL 경계 | 5개, 최종 전체 회귀에 포함 |
| 독립 runtime 결함 주입 | 5/5 탐지, 원본/기대값 불변 |

위 수치는 서로 포함되는 실행이 있으므로 합산한 총 테스트 수로 해석하지 않는다. 실제 외부 LLM/지도 호출은0이다. 프로세스에서 공급자 키를 제거하고 전체 guard의 비loopback TCP 연결을 차단했으며 해당 audit에 차단 시도는 없었다. 모의 응답·실제 PostgreSQL은 실모델 의미 품질이나 운영 전체 흐름 성공의 증거가 아니다.

## 독립 반례와 보존

- 정확한 환경 이름의 Spring 해석과 provider만 켰을 때 evidence 기본 OFF
- provider OFF/evidence ON 및 provider ON/evidence OFF의 양방향 독립
- 신규 모임 OFF→ON→OFF, 기존 V1/V2 update 불변, invite 충돌 시 기존 모임 불변
- OFF로 시작한 app context에서 기존 V2 원문/명시 답변/미해결 근거와 frozen accepted version 읽기
- 빈 값·대소문자·숫자·boolean 타입·공백/개행/NUL·잘못된 Name/Type·malformed JSON 거부와 기존 환경 원자 보존
- 실제 Compose raw env_file에서 반대되는 ambient shell 값이 flag를 덮어쓰지 않음
- 기존 질문/취소/수정 이력 SQL, 실패 전 accept 분기, room lock·CAS·마감 version 동결, HOST 전용 최종 미반영 공개 및 웹의 제출 전 고지는 기존 코드/회귀로 유지

신규 테스트 설계와 검토를 구현자와 분리했다. 기본 ON·환경 변수명 오류·양방향 provider 결합·잘못된 boolean 정규화의 유한 mutant5개를 별도 복사본에만 주입해 모두 탐지했다. 봉인 사례·실제 사용자 입력·운영 데이터는 사용하지 않았다.

## 실패 이력과 로컬 환경 한계

첫 runtime6개 suite 동시 실행201건은152 PASS/49 FAIL이었다. 이 중48건은 새 optional 설정에 대한 운영자 AWS 대역의 generic 성공 응답을 실제 미등록 오류로 바로잡았고 기존 단언은 유지해90 PASS를 확인했다. 나머지 기존20초 refresh 검사는 같은 제한의 단독 실행에서12.7초, 이후 실제 CI auth 명령에서도 통과했다. 기존 Python8건의 초기5실패도 같은 optional 미등록 대역만 보완해8 PASS가 됐다. timeout·단언을 완화하지 않았다.

최초 전체 guard는 새 테스트의 서식 검사에서 멈췄다. 공백·줄바꿈·후행 쉼표만 수정하고 실제 ktlintCheck와 최종 전체 guard를 통과했다. 초기 집중 테스트의 raw ktlint 실행 성공을 정식 lint 성공으로 보고했던 설명은 정정했으며 원본 실패를 보존했다.

Deployment Contracts의17개 명령 경계도 검사했다. 16개는 원래 명령/동일 source의 기존 증거로 성공했고, 마지막 실제 Nginx 검사는 관리형 로컬 환경 보정을 별도로 명시한다. 새 worktree의 공개 nginx.conf0600을 일반 checkout과 같은0644로 맞췄으며 HEAD 원본 바이트는 동일하다. 이후 컨테이너는8080/8443에 정상 listen했지만 주입된 프록시를 쓰는 자체 health가 실패했다. 동일 컨테이너의 고정 HTTP127.0.0.1 health에만 `wget -Y off`를 적용한 로컬 Docker 테스트 대역에서 원래 검증 스크립트가 성공했다. 외부/registry 프록시·TLS·컨테이너 보안 옵션·제품 스크립트는 변경하지 않았다. 이 결과를 네이티브 원격 CI 성공으로 확대하지 않는다.

## 게시와 다음 단계

커밋 직전 [Issue #184](https://github.com/meet-me-duo/meet-me-server/issues/184)와 최신 develop 기반 `fix/issue-184-v2-runtime-evidence`를 확인했다. feature commit/push·develop Draft PR·정확 HEAD 원격 CI는 다음 단계이며 로컬 검증을 원격 CI 완료로 표시하지 않는다. 실제 게시 후 해당 SHA의 일반 CI를 끝까지 확인해야 한다. 이후 병합·운영 배포/설정·신규 모임 활성화·추가 유료 검증은 실제 대상과 요청/비용 상한을 갖춰 별도 승인받는다. OFF 복귀는 새 writer만 중단하며 기존 V2 reader/guard/schema를 보존한다.

원본 로그·전체 모델 응답·봉인 fixture·비밀·로컬 DB/백업은 게시하지 않는다. 원본 분석 작업 공간과 이전 검증/배포 이력은 별도 보존한다.
