# #123 V17 catalog 독립 검토

2026-10-18 UTC. 독립 reviewer/test author `/root/baseline_merge_review`; 구현자 root. 기준 HEAD `e83d5280c7c236c99d64165893632920882527fe` 이후 동결된 세 Python 최소 패치를 검토했다. 실제 source/test 지문은 `.tdd/history/issue123-v17-catalog-fi/setup.json`과 canonical FI 요약에 고정한다.

판정: **이번 offline catalog 확장 범위에서 확인된 결함 0**. 실제 V17 SQL/PG/최종 전체 게이트는 미검증으로 남긴다. source 편집·운영 접근·workflow/권한 변경·모델 호출은 0이다.

- `publish_manifest.py`: canonical V17 이름 한 항목만 추가한다. current exact inventory, 숫자순 row/checksum/schema fingerprint, symlink/ancestor·auth marker·guard fingerprint 검증은 유지한다. 역사 V16 reader 지원이 current publisher의 V17 생략 허용으로 확대되지 않는다.
- `approved_release.py`: 기존 complete V1..V16을 유지하면서 contiguous V1..V17만 추가하고 새 canonical 이름을 등록한다. 기존 여섯 역사 catalog, 알려지지 않은 sparse/intermediate 거부, 성공 prefix·CRC·최소 history·원본 이미지/guard/schema·pending0·두 disabled rule·명시 승인 조건은 변경하지 않는다.
- `host-approved-release.py`: 별도 record reader에도 정확 V17과 canonical 이름만 추가한다. 역사 V16을 포함한 일곱 기존 catalog의 읽기·엄격한 row 형식/이름/성공/checksum은 유지한다.
- `run-approved-release.py`: 지문/내용 변경 없이 manifest row 기반 V17 package를 검증한다. SQL migration17과 runtime approval17파일을 혼동하지 않는다. auth marker가 있는 current17 및 marker 없는 historical14 지문 선택은 기존 계약 그대로다.

동결된 신규 테스트·helper의 SHA가 chronological RED 요약과 일치한다. 기존 oracle 원본 6개와 정확 diff는 `.tdd/history/issue123-v17-catalog/`에 보존했다. current inventory만 확장하고 기존 역사 matrix·V1..V16 bytes·기존 데이터 보존 assertion을 완화하지 않았다. 실제 소스에 V17이 없는 사실을 RED로 계산하지 않았으며 이번 대상은 temp fixture에만 V17을 둔 VC01..11이다.

격리 FI 상세/원본 로그와 복구 결과는 `.tdd/history/issue123-v17-catalog-fi/manifest.json`, schema 준수 요약은 `.tdd/verification/issue123-v17-catalog-faults-canonical.json`에서 확인한다. 원본과 동일한 35개 파일의 scratch copy로만 변이하며 공유 제품·테스트 파일은 변경하지 않는다.

남은 gate: 실제 V17 SQL의 네 table/constraints·30일 room cascade/late record 정책·V16 upgrade를 실제 PostgreSQL에서 검증, VC12 실제 source publication, 기존 C12+RA14와 새 VC12 전체, 관련 역사 catalog 회귀 및 root 최종 full gate. 이 검토/FI를 그 결과나 운영 migration 허가로 승격하지 않는다. CI 명령은 기존 C파일이 새 VC파일을 require하므로 두 파일을 동시에 별도 실행 목록에 넣지 않아야 한다.
