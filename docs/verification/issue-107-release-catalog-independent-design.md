# #107 release catalog 독립 설계·RED

2026-10-17 UTC. 범위는 원본 `/workspace/meet-me-server`의 CI immutable manifest 실패 후속이다. 사용자 지시와 부모가 확정한 공개 release 계약에 따라 독립 테스트를 작성했다. production·기존 테스트·워크플로는 수정하지 않았고 Gradle·운영·AWS 호출은 실행하지 않았다.

## 확정 계약과 기존 의미 보존

- manifest에는 기존 V1~V10이 정확한 필수 prefix로 있어야 한다. 그 뒤의 정규 양의 정수 version은 엄격한 **숫자** 오름차순이며 V11/V12 없이 V13이 와도 유효하다. 총 catalog는 최대256행이다. 상한은 행 수이고 version 숫자가256 이하여야 한다는 뜻은 아니다.
- 중복 version, leading zero,0/음수/소수/잘못된 case/형태·script-version 불일치·경로 탈출은 거부한다. 빈/빠진 필수 prefix·symlink/디렉터리 SQL 및 symlink migration directory도 거부한다.
- V1~V10 SQL 바이트와 기존 Flyway checksum10개를 보존한다. schema fingerprint는 숫자순 `{version,script,sha256}` canonical JSON의 SHA-256이다. V13 추가·바이트 변경은 새 fingerprint이며 guard fingerprint는 SQL만 바뀌었다고 바뀌지 않는다.
- 기존 manifest V10과 host automatic record V10을 계속 읽는다. 실제 성공 history는 최소 V1~V7 prefix이고 최대256행이며 manifest의 정확한 prefix여야 한다. 건너뛴 V11을 서버가 임의 생성하거나 잘못된 checksum/success를 허용하지 않는다.
- 기존 승인 V10에서 V13로는 자동 승격되지 않는다. automatic/preflight는 TRANSITION_REQUIRED이고 deploy는 명시 승인·실제 두 refresh rule disabled·pending command0을 요구한다. 완료 후 **정확한** schema/guard fingerprint·history·current image가 새 record와 일치할 때만 AUTO_READY다.
- 오래된 record와 새 target의 차이는 기존 계약대로 TRANSITION_REQUIRED일 수 있다. 잘못된 현재 history는 UNSAFE_RELEASE_STATE다. 기록된 이전 source와 현 target의 차이를 무조건 조작 예외로 바꾸어 기존 승인 전환을 막지 않는다.

## 독립 테스트와 RED 근거

`deploy/tests/test_issue107_release_catalog.py`,12개 unittest methods. 고정 SHA-256:
`2b2d467fd5a1be9e7ce1ce1f0032c1b686f847398c8a18ece5f26c2f9f628cc1`.

실행: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s deploy/tests -p 'test_issue107_release_catalog.py' -v`.
원본 로그: `/tmp/issue107-release-catalog-independent-red.log`.

최초 실행은12개 중9개가 V13/generic catalog/host record 요구 기능이 없어 ValueError로 종료했고, 기존 CRC 및 거부 대조군3개는 PASS다. 파일·import·구문·fixture 준비 오류가 아닌 현 production의10개 고정 검증에서 실패했다. 이 RED 이후 기대값 변경은 새 설계·RED 근거를 필요로 한다.

각 method는 source publisher·manifest/facts validator 및 host record metadata reader에 합성 파일/JSON만 제공한다. host record reader 테스트는 `_bytes`를 합성 record 바이트로 대체하여 metadata 계약만 검증하며 root 소유·권한 검사 완료를 주장하지 않는다. 실제 파일시스템 trust는 기존 guard 테스트를 유지한다. catalog256 허용도 실제 archive256 entries/전체 byte 제한을 풀라는 뜻이 아니다.

## 기존 테스트·공통 파일 통합 경계

기존 `.github/scripts/approved-release-pipeline.test.cjs`의 synthetic10 manifest와 기존9행 거부·보안 assertions는 유지해야 한다. source root의 실제 migration catalog만10개라고 가정한 immutable manifest 부분은 V1~V10+V13이 정확히 모두 포함되는 기대값으로 갱신해야 한다. 기존10개 checksum은 같은 숫자를 그대로 검증한다.

현재 CI는 해당 node test 파일을 명시해 실행한다. 새 Python 파일 추가만으로 자동 실행되지 않으므로 기존 node suite에 새 offline unittest wrapper를 추가하거나 명시 CI 연결이 필요하다. 이 연결은 root 담당이며 이 설계에서 workflow를 고치지 않았다.

#109가 `publish_manifest.py`, `approved_release.py`, `host-approved-release.py`의 V11 지원을 소유하고 전달했다. 부모 확인상 해당 변경은10/11 길이·range11의 확장이다. #107 V13의 generic 계약은 V11도 포함하므로 통합 시 그 의미를 보존해 논리적으로 합쳐야 한다. 세 파일을 한쪽 버전으로 통째 덮어쓰지 않는다.

이번 수정은 guard 실행 bundle의 바이트를 바꾸어 guardFingerprint도 바뀐다. 같은 schema여도 기존 자동 승인 계약과 달라 **승인 전환이 필요**하다. CI 통과나 manifest 생성으로 운영 승인·refresh rule 중지·호스트 설치·DB migration·자동 배포가 실행되거나 승인된 것으로 보고하지 않는다.

최종 GREEN·기존 node 회귀·결함 주입·CI exact head는 root 실행 결과로 후속 기록한다. 과거 Kotlin/ARM64 gate 통과는 이 offline release catalog 결함을 검증한 결과가 아니다.

## 구현 후 독립 리뷰·결함 주입과 추가 P2 설계

root의 generic 수정3파일은 publisher에서 숫자 정렬·필수1~10·중복/비정규명/비정규 파일·상한256을 검증하고, manifest/history 및 host record reader가 sparse V13을 받아들이도록 확장한다. fingerprint 계산과 실제 성공 history의 정확 prefix 비교, 명시 승인 전환을 보존한 방향을 확인했다. 기존12개 기대값은 그대로이며 독립 baseline12개 GREEN을 확인했다.

root가 허용한 새 검증 작업으로 원본 파일 바이트를 저장한 뒤3종 fault를 한 번에 하나씩 임시 주입하고 매번 정확히 복원했다. 외부 서비스·Gradle은 호출하지 않았다. 증거는 `docs/verification/issue-107-release-catalog-faults.json`과 다음 원본 로그다.

- duplicate version guard 제거: `/tmp/issue107-release-catalog-fault-duplicate-version-accepted.log`, 행동 실패1개 탐지.
- 실제 history exact-prefix 비교 제거: `/tmp/issue107-release-catalog-fault-history-prefix-not-checked.log`, 행동 실패2개 탐지.
- 정확한 same-contract 대신 READY record만으로 자동 허가: `/tmp/issue107-release-catalog-fault-ready-record-bypasses-transition.log`, 행동 실패2개 탐지.

세 실행은 전부 assertion FAIL로 탐지했고 compile/import/환경 ERROR는0이다. 원본3파일의 before/after SHA가 동일하고 frozen12 테스트 지문도 같음을 확인했다. 이 근거는 추가 P2 수정 **이전** source/test 지문에 묶이며 이후 코드 변경을 검증한 것으로 확대하지 않는다. root가 별도로 기존 node CI entry8개 GREEN을 보고했다. 최신 CI exact head는 후속 원격 receipt가 필요하다.

리뷰 발견 P2: `approved_release.py`의 automaticContract는 최소7개 prefix를 허용하는 history validator에 len>=10만 더하므로 full record에서8/9/10이 빠진10행을 TRANSITION_REQUIRED로 받아들였다. host record reader는 같은 입력을 거부한다. 현재 사실의 exact prefix가 별도로 검증돼 AUTO_READY 우회는 없지만, full record의 필수1~10 계약과 두 reader의 정합을 위해 root가 수정에 동의했다.

기존12개는 그대로 두고13번째 `test_full_automatic_record_requires_all_first_ten_versions_not_only_history_minimum_seven`을 추가했다. missing8/9/10 각각10행 record가 UNSAFE_RELEASE_STATE로 거부돼야 한다. 새 지문은 `3d09a4af0c779c384981758812c73cdcb3a3f830c7c33ce944cf6aa923cdea50`이다. `/tmp/issue107-release-catalog-fullrecord-red.log`에서13methods 중 새1method의3subcases가 ValueError not raised로 실패하고 기존12개는 PASS임을 확인했다. 기대값을 바꿔 GREEN을 만들지 않았다. 새 production 수정·GREEN 및 최종 source 지문 검증은 root 후속 작업이다.

## P2 해소 후 최종 소스 읽기 검토

`approved_release.validate_deploy_facts`가 full automatic record의 첫10개 version을 정확히 문자열1~10과 비교하는 검증을 추가했다. 일반 observed history의 최소7개 prefix와 full 승인 record의 필수10개 prefix를 분리하므로 기존7~10단계에서의 명시 전환은 유지하며 host reader와의 P2 불일치는 해소되었다. account #109의 V11도 추가 버전으로 허용한다.

최종 읽기 검토 시 original과 integration의 approved-release SHA-256은 모두 `dd40a0313418db09d7117c601181dbcd71231fe664dbcce0185d08951ac33481`이었다. publisher는 `d9c3d10b4ccc872860475dfbe452a0833bd7fd830453ec697e2a4121c3183a4f`, host reader는 `6efc9cf7dc34ddd0332e29174a9ba8b46eb2f2d9b100c9d0d9e649815338755e`, frozen13 test는 `3d09a4af0c779c384981758812c73cdcb3a3f830c7c33ce944cf6aa923cdea50`다.

root가 보존한 `/tmp/issue107-release-catalog-final-green.log`의13개 GREEN과 `/tmp/issue107-release-pipeline-final-green.log`의 기존 CI entry8개 GREEN을 확인했다. 기존9행 거부·V10 checksum·승인 전환 기대값을 완화하지 않았다. 이 시점에는 root의 전체 CI 동등 검증이 실행 중이므로 source 임시 FI를 추가하지 않았다. 현재13개를 사용한 기존3종+full-record prefix 제거의4종 FI는 root가 실행 구간을 허용한 뒤 별도로 검증해야 한다. 이전3/3 FI 근거를 최종 변경 후4/4 근거로 표시하지 않는다.

## Commit 전 최종 독립 판정

root의 실행 구간 허용 뒤 **최종 source3개·frozen13개 테스트**로4종 결함 주입을 다시 수행했다. 최종 증거는 별도 `docs/verification/issue-107-release-catalog-final-faults.json`이며 이전12개/3종 근거 파일은 역사로 보존했다.

- baseline13개 PASS.
- duplicate version 허용 mutant: 행동 assertion1개 FAIL.
- 현재 history exact-prefix 검사 제거 mutant: 행동 assertion2개 FAIL.
- READY record만으로 자동 승인 전환 우회 mutant: 행동 assertion2개 FAIL.
- full automatic record의 필수 첫10개 검사 제거 mutant: missing8/9/10의3subcases 행동 FAIL.
- 각 mutant 후 원본 정확 복원, 최종 source3개/test1개 지문 불변 및 복원 후13개 PASS를 확인했다. compile/import/환경 오류를 결함 탐지로 집계하지 않았다.

독립 최종 판정: 이번 release catalog 변경 범위의 미해결 리뷰 지적은 없다. 필수 V1~V10/legacy V10 checksum·history 유지, sparse V13, 명시적 schema/guard 승인 전환 및 fail-closed 경계를 보존한다. P2 reader 불일치도 새13번째 RED→최소 수정→GREEN→해당 guard 제거 mutant 탐지로 해소되었다. 기존 node entry8개 GREEN과 최종 root의 CI 동등·guard/원격 exact head 결과는 별도로 연결한다.

이 판정은 승인된 feature commit·push·Draft PR·CI 게시를 위한 독립 검토이며 운영 배포·승인 record 생성·EventBridge 변경·DB migration·운영 비용 호출 승인을 뜻하지 않는다. guard fingerprint 변경은 실제 운영 전환 승인을 계속 요구한다. 이 FI 작업은 root가 다른 pipeline 소비 검증과 겹치지 않는 실행 구간을 지정한 뒤 수행했으며 Gradle을 실행하지 않았다.
