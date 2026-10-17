# #107 통합 catalog 독립 최종 소스 리뷰

배포 코드 구현자·고정 catalog 테스트 작성자와 분리해 `publish_manifest.py`, `approved_release.py`, `host-approved-release.py`를 검토했다. **미해결 P1/P2 없음, 이 세 파일의 검토 통과.** 정확 소스 지문과 명령 결과는 같은 이름의 JSON에 기록했다. 제품 전체 완료나 운영 배포 승인 판정은 아니다.

새 publisher는 실제 SQL의 정확한 V1~V16 파일명 집합과 숫자 순서를 요구한다. 누락·추가·별칭·디렉터리·원본 링크를 거부하며, V1~V15는 기존 승인 PR의 원본 바이트 지문과 일치한다. 스키마 지문은 순서 있는 version/script/raw SHA-256 목록에, guard·archive 지문은 각각 실제 바이트에 결합한다. 현재 V16은 합성 RED 대체물이 아닌 실제 파일이다.

reader 두 곳은 승인된 여섯 역사 전체 목록과 새 연속 16개 목록만 받는다. 관찰 history는 대상 manifest의 성공 행·버전·이름·checksum·순서와 정확히 같은 prefix여야 한다. 따라서 옛 #107/#110의 sparse history를 읽을 수 있어도, 그 이력을 V1~V16의 prefix로 간주하지 않는다. `approve_first_transition=True`도 이 경계를 우회하지 못한다. 이런 DB의 실제 통합 이행 방안은 별도 운영 검토가 필요하며 이번 검토에서 승인하지 않았다.

자동 진행은 완전한 history, 신뢰된 READY, 같은 schema/guard 지문을 계속 요구한다. 수동 전환은 명시 승인·refresh rule 두 개 DISABLED·pending command 0을 요구한다. 실패 행, checksum·순서 변경, CI/source 불일치도 거부한다. 역사 reader의 이전 파일명 호환은 보존하며 새 16개 목록에는 canonical 이름을 강제한다.

CI와 같은 catalog Node 46개를 재실행해 전부 통과했다. 별도의 읽기 전용 probe 52개로 실제 소스 publication, 여섯 역사 목록·host record, 허용 prefix, sparse history의 승인 우회 거부, 인가 전제 보존을 확인했다. 기존 독립 결함 주입 3건과 테스트 이관 기록도 대조했다. 이 리뷰로 production·고정 테스트를 수정하지 않았고 외부·운영·유료 호출은 없다. V16 업무 스키마의 PG 검증과 전체 제품 gate는 별도 담당 범위다.
