# Backend canonical RED schema projection

http/boundary/defense/admission 네 기록의 상세 원본 바이트를 각각 `.json.original`에 보존했다. active canonical은 스키마의 7필드만 남기고 동일 UTC 시각의 표기를 Z로 정리했다. Admission failedTests 객체는 원본 XML stack의 실제 method와 parameter 표시를 결합한 고유 문자열로 투영했다.

과거 testFiles SHA·원래 실행 결과·기준 sourceRevision은 현재 테스트 지문으로 소급 변경하지 않았다. 수정된 테스트 지문 재확인은 별도 `*-format-replay` workItem으로 기록한다. schema 추가 필드가 허용되지 않으므로 supersession 연결은 별도 history/index에만 둔다. Draft202012 jsonschema로 네 projection을 검증했다.
