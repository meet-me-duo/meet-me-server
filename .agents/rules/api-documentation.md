# API Documentation Rules

프론트엔드는 백엔드가 생성하는 Swagger/OpenAPI 문서를 API 계약으로 사용한다.

## Controller

- 모든 공개 Controller 엔드포인트에 `@ApiResponse`를 추가한다.
- 성공 응답뿐 아니라 실제로 반환할 수 있는 주요 오류 상태와 응답 스키마를 문서화한다.
- 구현의 HTTP 상태 코드와 문서의 응답 코드가 일치해야 한다.

## DTO

- 요청 및 응답 DTO와 공개 필드에 `@Schema`를 추가한다.
- `@Schema`에는 프론트엔드가 의미와 제약을 이해할 수 있는 설명을 포함한다.
- nullable 여부, 허용 값, 형식과 예시가 계약 이해에 필요하면 명시한다.
- 내부 도메인 모델과 영속성 Entity를 API 스키마로 직접 노출하지 않는다.

## 검증

- API 변경 시 생성된 OpenAPI 문서에 엔드포인트, 응답과 DTO 스키마가 반영됐는지 확인한다.
- 코드 동작과 Swagger 문서가 다르면 코드 변경을 완료한 것으로 간주하지 않는다.
