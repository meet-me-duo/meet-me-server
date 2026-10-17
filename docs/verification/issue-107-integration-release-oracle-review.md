# 통합 release catalog oracle 교정 독립 최종 검토

2026-10-17 UTC. 테스트 설계자와 분리된 root 구현자가 `.github/scripts/issue109-release-catalog.test.cjs`의 전체 diff와 원본 CI 실패5건, 관련17/17 PASS 근거를 검토했다. 미해결 지적 없음.

기존 역사 V11 fixture를 명시 선택으로 보존하고 현재 실제 V1..V11+V13을 별도 고정 목록과 독립 JS CRC32/고정 checksum으로 검증한다. V1..V11 checksum11개는 이전과 같고 V13은 -67069369를 고정 검증한다. 실제 source 전체를 복사하는 current fixture는 예상 밖 migration을 숨기지 않는다.

V11 전용 상한에 의한 V12 무조건 거부는 승인된 generic catalog 계약과 충돌하므로 유효한 완전 catalog의 구조 검증과 다른 계약 전환 검증으로 옮겼다. 동일 계약의 정확 history/fingerprint만 AUTO_READY이며 다른 유효 record는 TRANSITION_REQUIRED다. deploy는 명시 승인과 두 refresh rules DISABLED를 모두 요구하는 assertion을 유지한다. invalid prefix9·checksum·success·pendingCommands·origin/identity·duplicate·symlink·비정규 이름 거부는 보존했다. V10→V11 첫 전환 검증도 그대로다.

제품 Kotlin/SQL/배포 구현3파일/필수 guard/CI workflow와 frozen generic13은 변경하지 않았다. 이번 수정은 기존 oracle의 fixture migration이며 새 제품 RED→GREEN으로 집계하지 않는다. 기존 동일 production source의 generic13 RED/FI4 근거는 별도로 유지한다. 원본 CI39050251162는 품질/ARM64 성공·catalog5FAIL·후속SKIP 기록이며 전체 성공이 아니다.

관련 Node17(내부 frozen13 실행 포함) 전체 PASS와 diff-check를 확인했다. 필수 guard·추가 commit/push 이후 새 exact HEAD CI는 별도 게시 receipt에서 확인한다. 실제 운영/공급자/유료 호출 및 배포0.
