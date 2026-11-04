# 공개 검증 기록과 역사 근거

RED·독립 검토·결함 주입·검증 기록은 각 recordedAt/source revision 당시의 근거다.
과거 실행 결과와 source/test SHA-256은 최신 source의 값으로 바꿔 쓰지 않는다.

완료 기록의 원래 바이트를 보존하는 위치는 정확한 Git revision이다. 2026-11-04 정리 전
기준은 `780b144d4355b41d2b21eeb109252a8fc84abc33`이며 당시 main
`d3391fbf7eb2de7f9c617c0dcd90cd685b6cc86f`과 파일 내용이 같다.
이 revision에서 `.tdd/history/`, 완료된 요약, `_handoff/ai-activation-20261028/`,
과거 issue 접두 OpenAPI·검증 JSON 및 일회성 FI 실행기를 확인할 수 있다.
현재 트리에 같은 before-image·ZIP·manifest를 복제해 보존하지 않는다.

남겨 둔 봉인 JSON·manifest의 역사적 경로나 지문은 현재 경로에 맞추려고 재작성하지 않는다.
삭제된 경로는 기록된 revision의 출처이며 현재 작업 트리의 필수 입력이 아니다.
필요한 원문은 기록된 revision 또는 위 정리 전 기준의 Git 이력에서 확인한다.
현재 source의 검사 결과는 [구현 계획](../implementation_plan.md)과 정확한 Git revision·CI run을
따르며 과거 기록의 PASS를 새로운 검증 결과로 표시하지 않는다.

내부 승인 식별자와 과거 호출 계획은 새로운 유료 실행 권한이 아니다. 원본 모델 응답·로그·
transport·키/토큰/헤더·개인정보·봉인 fixture/gold·로컬 DB/백업은 게시하지 않는다.
`local-only-evidence/`는 원본 로컬 보존 자료의 이름이며 공개 다운로드 링크가 아니다.

새 임시 파일은 [수명주기](README.md)와 `.agents/rules/documentation.md`에 따라 필요할 때만
ignored 경로에서 생성·소비·정리한다. 진행 중 후속 검증 증거는 완료 확인 전 삭제하지 않는다.
현재 요구사항·설계·운영 계약은 PRD·Architecture·ADR와 상시 운영 문서에 직접 반영한다.
