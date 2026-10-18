# 비-Decisions 서버 develop 통합 대조

2026-10-18 UTC. 별도 clean detached worktree `/workspace/issue107-non-decisions-develop-audit`에서 실제 원격/PR/diff를 읽기 전용으로 대조했다. Decisions 작업 트리·index·source 및 기존 PR을 변경하지 않았다.

**필요한 비-Decisions 기능의 미반영 delta는 0이다. 추가 병합이 필요하지 않다.** 원격 develop은 `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`, tree는 `7d08d9658cf3f63d8f99785e11785028c44173c1`이다. Decisions 신규 classifier/bridge/budget 제품 코드는 이 develop에 없다.

| 대상 | 실제 HEAD | 판정 |
| --- | --- | --- |
| PR115 계정109 | c06b60a9833ae77e5fce9cfef5719ab2f37470ff | MERGED, HEAD가 develop 조상 |
| PR118 게스트108 | bd8818625aa51480e3a06c1fa17d3e223fa1cfbe | MERGED, HEAD가 develop 조상 |
| PR120 제출 전107·계정·게스트·운영 통합 | bb62a95ed1a9b00dc3c190721b67040c3de857ab | MERGED, HEAD가 develop 조상 |
| PR121 runtime 준비 | 20fb099c89dc51be01678c8e014cd8a68db76a76 | MERGED, HEAD가 develop 조상 |
| PR113 마감 | da225d8aaa9097db2360a6bf0579322de16b5968 | OPEN/DRAFT, 필요한 마감 guard·회귀는 통합됨 |
| PR116 이전 마감 후107 | dc145c3e0d9f4a12fe8cc367cf467377376bcc36 | OPEN/DRAFT, 호환 기능 흡수·새 흐름으로 대체 |
| PR117 이전 계정/107 | c44225d814ce016dfe7d0deb1ec77632cb561448 | OPEN/DRAFT, 계정/호환 흡수·제출 전 비공개/현재 catalog로 대체 |
| PR119 운영110 | 5a360d47722618efc67f72e6bf2fbc4c97f45e23 | OPEN/DRAFT, 운영 기능 흡수 |

실제 흡수 경로는 **113→118, 116·117·119→120**이다. 계정 기반은115, 그 뒤 runtime/auth/catalog 보강은121이다. 117은 계정 통합을 검토한 미병합 중간 원본이며 실제 반영 PR로 세지 않는다. Git first-intro로 deadline guard가 bd881862, ClarificationService·운영 관측 핵심 파일과 privacy/quota/LEGACY 제한이 bb62a95에서 develop 이력에 처음 들어옴을 확인했다.

## 실제 차이의 의미

- PR113의 마감 시각 이상 차단·비교정 입력 거부는 현재 SubmissionService에 보존됐다. 테스트 차이는 실제 계정 HOST·proof 만료/철회와 역사 LEGACY fixture 통합이며 예전 assertion을 약화한 누락이 아니다.
- PR116 변경52경로 중27개가 byte 동일하다. 없는6개는 역사 catalog 증거5개와 실제 계정/proof 소유권으로 교체된 test-only 합성 adapter다. 원래 제품 파일의 누락은 없다.
- PR117 변경56경로 중34개 동일·없는 경로0이다. 다른 제품4개는 PRE_SUBMIT_V1 타인 원문 비공개, 게스트/password/participant/protocol DTO, precheck quota와 LEGACY 한정 사후 질문 생성이다. 사용자가 채택한 새 흐름·강화 경계이며 예전 흐름을 다시 병합하면 안 된다.
- PR119 변경108경로 중93개 동일·없는 경로0이다. 유일한 제품 Kotlin 차이는 최종 전체 분석에서 accepted version의 원래 anchor를 사용하는 통합 보완이다. 운영자 summary/audit/transport 관측 제품 파일은 모두 byte 동일하다.

옛 PR 전체 commit의 patch identity/조상 관계와 필요한 기능 hunk 흡수를 구분했다. `git cherry`의 +만으로 누락이라고 판정하지 않았다. 현재 sparse V1..V16과17-file auth runtime catalog를 과거 V13 정책으로 되돌리지 않는다. 기존 PR113/116/117/119는 닫거나 수정하지 않았고 PR116/117 본문 형식도 재수정하지 않았다.

## 현재 develop 정확 CI

[CI39070926139](https://github.com/meet-me-duo/meet-me-server/actions/runs/39070926139)는 develop push·정확 `01d00e7fcbaa48dd91a9b3308c0e6d320cdc01df`·completed/success다. Quality Gate117508190564와 ARM64 Built Image117508190765의 같은 HEAD·모든 단계 success를 직접 재조회했다. Quality 원본 로그의 실제 checkout 전체 SHA도 일치하며 BUILD SUCCESSFUL을 확인했다. PR121 HEAD의 CI39069432900과 별개인 실제 develop merge SHA 검증이다.

commit별 workflow 목록의 빈 결과와 CLI403은 성공으로 덮지 않았다. 연결된 read connector의 직접 run/job/log 조회로 정확한 기존 CI를 확인했으며 자격 증명/정책 변경은 없었다. 상세 path map과 원본 독립 보고서는 ignored `.codex/tdd-evidence/issue107-non-decisions-develop-audit/`에 보존했다.

추가 source 변경·새 test·새 merge 없이 요청한 비-Decisions develop 통합 완료를 보고한다. Decisions는 별도 feature/Draft에 두며 develop/main 병합·배포·새 web develop 생성은 하지 않는다.
