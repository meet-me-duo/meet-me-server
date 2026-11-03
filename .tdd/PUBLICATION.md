# 공개 검증 기록의 범위

이 디렉터리의 RED·독립 검토·결함 주입·검증 기록은 각 `recordedAt`/source revision 당시의 역사 근거다. test/source SHA-256은 과거 실행 당시 값을 보존하며 공개 정제 후 파일이나 최신 통합 소스의 값으로 바꿔 쓰지 않는다.

내부 승인 식별자는 합성 표기로 정제했다. 이 표기와 과거 호출 계획은 새로운 유료 실행 권한이 아니며 남은 횟수를 재사용할 수 없다. 원본 모델 응답·원본 로그·transport·키/토큰/헤더·개인정보·봉인 fixture/gold·로컬 DB/백업은 게시하지 않는다. `local-only-evidence/`는 로컬 원본 보존 자료의 이름이고 공개 다운로드 링크가 아니다.

최신 소스의 검사와 게시 결과는 [구현 계획](../implementation_plan.md)의 해당 작업 및 정확한 Git revision·CI run으로 확인한다. `.tdd/history`의 과거 코드 사본은 제품 실행 코드가 아니며 최신 소스의 성공으로 간주하지 않는다.

이슈별 Markdown 정리 후에도 이 디렉터리와 `_handoff/`, `docs/evaluation/`·`docs/verification/`의 봉인된 manifest·지문·검토 JSON은 원래 바이트를 보존한다. 그 안의 삭제된 문서 경로는 당시 revision의 역사적 출처이며 현재 작업 트리의 필수 입력이 아니다. 필요한 원문은 기록된 source revision의 Git 이력에서 확인한다. 현재 요구사항·설계·운영 계약은 PRD·Architecture·ADR 및 상시 운영 문서에 직접 반영하며 과거 증거를 현재 계약으로 전용하지 않는다.
