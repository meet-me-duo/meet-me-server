# #108과 확정 #107+#109 조합의 통합 인계

2026-10-17 UTC 부모의 확정 조합을 원격 PR 정보와 읽기 전용 fetch로 확인했다. 현재 #108 검증을 계속하며 다른 원격 브랜치를 변경하거나 이 조합을 #108에 병합하지 않았다. 네 기능의 최종 결합 검증은 각 담당 단위 완료 뒤 부모가 수행한다.

| 조합 | 정확한 HEAD | 확인된 CI |
|---|---|---|
| 서버 [PR117](https://github.com/meet-me-duo/meet-me-server/pull/117) | `c44225d814ce016dfe7d0deb1ec77632cb561448` | [39051106339](https://github.com/meet-me-duo/meet-me-server/actions/runs/39051106339) SUCCESS |
| 웹 [PR28](https://github.com/meet-me-duo/meet-me-web/pull/28) | `fb87358feecf6f979cd55bb10a218a0fe0e1eb3b` | [39050257242](https://github.com/meet-me-duo/meet-me-web/actions/runs/39050257242) SUCCESS |

공통 기준은 서버 #109 `c06b60a9833ae77e5fce9cfef5719ab2f37470ff`, 웹 #109 `95f09c0a98c6538b0f19ed381fdf11a2f5b844d9`다. 위 CI는 그 조합의 증거이며 #108을 포함한 네 기능 전체의 통과 증거가 아니다.

## 공유 파일

서버에서 두 담당 변경이 만나는 파일은 다음과 같다.

- `RoomLifecycleDtos.kt`: #108의 선택 암호·guest participant ID·Unicode 이름 검증과 #107의 `clarification_protocol`을 모두 보존한다. 파일 전체 선택으로 다른 변경을 지우지 않는다.
- `deploy/pipeline/{publish_manifest.py,approved_release.py,host-approved-release.py}`와 `.github/scripts/{approved-release-pipeline.test.cjs,issue109-release-catalog.test.cjs}`: 아래 migration 정책 차이를 함께 해결한다.
- `CorePersistenceIntegrationTest.kt`, `DatabaseMigrationRunnerTest.kt`, `IntegratedLegacyUpgradePostgresTest.kt`: 최종 migration inventory·최신 버전·테이블 수에 맞추며 기존 V1~V11 checksum·데이터 보존·실패 거부 기대를 유지한다.

웹 교집합은 `RoomPage.tsx`, `src/api/{client.ts,types.ts,schema.d.ts}`, `openapi/meet-me.openapi.json`, `styles.css`, `e2e/host-creation.spec.ts`다. #108의 GuestAccessPanel/GuestParticipation과 #107의 ClarificationPanel은 각각 보존한다. `api.joinRoom`은 #108의 객체 body를 유지하고 보완 API 두 함수를 더한다. `Viewer`의 선택적 `context_id`·`participant_id`와 `RecoveryKeys`의 선택적 `clarification_protocol`을 함께 유지한다.

## 실제 소유 증명 연결

#107의 `SharedClarificationOwnerAdapter`는 질문 GET과 답변 POST 모두 공통 `ParticipantOwnershipResolver.READ_OWN`을 사용한다. #108은 같은 port에 실제 cookie session·PostgreSQL grant·암호 검증을 연결한다. 보완 서비스에 별도 guest fallback이나 클라이언트 level 신뢰를 추가하지 않는다.

- 유효한 원래 소유 세션과 정답 암호로 발급한 `VERIFIED_GUEST`는 같은 본인 기록에 대한 기존 READ_OWN 범위를 갖는다. 보완 질문·답변의 방 상태·active round·revision·replay 규칙은 #107 그대로 적용한다.
- `REPLACE_ONLY`는 기존 원문·질문 excerpt 조회와 보완 답변을 모두 거부해야 한다. 일반 submission PUT과 claim도 거부하며 #108 전용 blind 대체 endpoint만 허용한다.
- 계정 인증이 있으면 일반 읽기·쓰기는 계정 기록이 우선이다. 이전 guest 기록의 회원 연결은 유효 VERIFIED 증명+로그인+명시 동의를 갖춘 claim으로만 수행하며 같은 방 기존 회원 기록 충돌은409로 보존한다.
- 무효·만료 Bearer는 유효 guest cookie가 있어도 401이며, 만료·회수 세션과 회원 claim 이후의 옛 guest grant는 보완 API에서도 사용할 수 없다.
- #107 `ClarificationService.answer`는 room 잠금 후 공통 `SaveSubmissionUseCase`를 호출한다. #108의 write guard와 #113 마감 패치를 보존한다. CLOSED 수정 라운드는 지난 초기 마감만으로 거부하지 않고 기존 round/revision 검사를 유지한다.

웹에서는 #107의 `clarifications` private query prefix를 #108의 모든 취소·삭제 경로에 포함한다. 특히 새 `restoreVerifiedGuest`와 기존 방/인증 전환·미참여·인가 실패 정리를 빠뜨리지 않는다. `REPLACE_ONLY`와 미참여 화면에서 ClarificationPanel을 mount하거나 질문 GET을 수행하지 않는다. 보완 답변의 늦은 callback도 방·인증·소유 세대가 바뀌면 cache와 UI를 다시 채우지 않아야 한다. #107의 pending answer 중 재분석 차단과 #108의 stale reaccess 차단을 함께 유지한다.

## Migration catalog 정책 차이

#108 inventory는 V1~V12 연속 12개이며 V12는 guest hash·grant다. #107 확정 조합은 V1~V11+V13의 12개/latest13이며 V13은 보완 질문이다. 두 조합은 개수만 같고 inventory가 다르다.

#108 publisher는 정확한 V1~V12를 요구하고 consumer는 기존 V10/V11과 V12를 허용한다. #107 publisher는 V1~V10 필수 prefix를 보존하며 현재 V11+V13을 포함한다. 어느 쪽 producer/consumer/test도 파일 전체로 덮어쓰면 안 된다. V12와 V13을 모두 보존한 최종 inventory는 #110 이전에 V1~V13의 13개/latest13이며, 예약 V14/V15까지 포함하면 최종 실제 SQL 목록으로 다시 확정해야 한다.

최종 catalog·history·fingerprint·image identity·명시 승인 전환을 하나의 계약으로 정렬한다. V1~V11 원본 바이트와 각 담당 V12/V13 SQL을 보존하고 최종 전체 catalog checksum을 검증한다. #108의 현재 unknown V13 거부와 #107의 현재 V12 부재 oracle은 통합 후 별도 supersession이 필요한 현재 inventory 기대값이다. 이전 frozen 테스트·RED·독립 리뷰는 역사로 보존하며 checksum·prefix·승인·실패 거부 검증을 약화하지 않는다. 운영 schema 전환은 별도 승인 대상이다.

## 부모 최종 결합 검증

- 실제 원래 소유 cookie, 보호 기록의 올바른/틀린 암호, 무암호 REPLACE_ONLY로 보완 질문 GET·답변 POST를 각각 검사한다. 무암호 경로의 원문·excerpt 요청은 0회여야 한다.
- 다른 기록·다른 방·중복 이름·만료/회수·무효 Bearer·claim 전후와 보완 답변/claim/대체 입력 경합을 검사한다. 기존 상태·revision·원문 관계를 보존한다.
- CLOSED correction round와 CONFIRMED 상태, 답변 replay·취소·무응답 및 HOST 명시 재분석의 한 번 실행을 함께 검사한다.
- 합친 native 서버에서 OpenAPI를 다시 생성한다. #109의23경로+#108의3경로+#107의2경로로 최소28경로를 보존하고 #110의 실제 추가 계약도 확인한다. 기존25/26경로 snapshot 중 하나를 그대로 선택하지 않는다.
- 실제 proof 발급 이후 웹 private cache·질문 excerpt·stale callback 격리와 전체 게이트·결함 주입·독립 리뷰·최종 정확 HEAD CI를 새로 검증한다. 각 담당의 기존 통과 수치를 합산해 전체 통과로 표시하지 않는다.

PRD·Architecture·implementation_plan의 공통 통합 편집은 부모 소유이며 #108은 전용 목표·검증·이 문서로 인계한다. SubmissionService 마감 패치와 #110 계측 소유 경계를 유지한다.

#110이 신규 재접근·대체 입력을 계측할 때 암호·credential·hash·원문·동적 participant UUID를 로그나 metric label에 포함하지 않고 고정 route·상태 enum을 사용한다. #108은 해당 계측 파일을 변경하지 않았다.
