# 직접 수정 안내 연동

2026-10-28 UTC, 안내 구현의 실제 native OpenAPI export는 `docs/openapi/precheck-edit-guidance.openapi.json`이다. SHA-256은 `3028ed968241315c1d4e1c230491a7eb350ea0b9122cfcddf8aa84fdde075145`이다. 최신 main e799fb6와 동일한 develop0ce4b40을 로컬 통합한 실제 스냅샷이며 내 모임 필터·페이지 계약과 edit_guidance를 함께 확인했다.

본인 `check.status=NEEDS_EDIT` 응답에 새 `check.edit_guidance`를 일반 텍스트로 보여준다. 필드는 required이며 값은 최대160 코드포인트 문자열 또는 null이다. 다른 상태에서는 null이다. 기존 A/B `check.question`은 이 상태에서 null로 유지한다. 안내를 HTML·Markdown·자동 링크로 해석하지 않고, A/B 버튼이나 자동 완료를 만들지 않는다. 기존 직접 수정/취소와 revision/CAS 경로를 사용한다. 구체 안내가 없는 이전 기록도 서버가 일반 안내를 제공하며, 구체 모델 진단처럼 표현하지 않는다.

```json
{
  "status": "NEEDS_EDIT",
  "question": null,
  "edit_guidance": "목요일에 예약이 끝나는 시각을 알려 주세요."
}
```

모델 JSON의 키·타입은 기존 verdict/prompt/sourceExcerpt/choices 네 개를 유지한다. 변경 전 NEEDS_EDIT는 prompt/sourceExcerpt가 null이었다. 이제 새로운 생성 지시는 구체 안내와 정확한 원문 구간을 요구하고 choices는 null로 둔다. 서버는 두 안내 필드가 함께 제공됐는지, 길이·공백·제어/방향 문자·마크업/링크와 원문 일치를 검증한다. 기존 null/null 결과는 호환 읽기와 일반 안내를 유지한다. CLEAR와 A/B 계약은 유지한다.

기존 question JSON 모양을 재사용하므로 새 마이그레이션·release catalog·배포 guard 변경은 없다. 빈 choices 안내는 선택 가능한 질문이 아니며 기존 A/B3개/물리 검사6회·소유권·마감·최종 accepted 정책을 유지한다. 이전 reader도 id/prompt/sourceExcerpt/choices 배열을 읽을 수 있다. 기존 reader의 질문 카운트는 안내까지 보수적으로 포함할 수 있으므로 정상 배포는 새 서버·웹을 함께 확인한다.

실제 합성 TF14는 최소 종료 시각만 질문했고 예약 내용·시간을 추측하지 않았다. 이 한 건을 전반적인 모델 정확성이나 사용자 방/운영 반영 검증으로 확대하지 않는다. 원본 응답 본문·키·사용자 방 식별자는 기록하지 않았다.

최종 로컬 검증: 원본 commit guard ktlintCheck/assemble/test exit0, 2270 tests, 4 SKIP, 실패/오류0. source snapshot 전후 동일. FI4 모두 DETECTED 후 정확 복원. 원격 게시/PR/병합/운영 반영은 신규 이슈 게시 승인 대기로 미완료다.

후속 실제 통합 전체 검증: 2278 tests, 4 SKIP, 실패/오류0. 원본 commit guard exit0와 src snapshot 전후 동일을 확인했다. 원격 작업·운영 반영은 수행하지 않았다.
