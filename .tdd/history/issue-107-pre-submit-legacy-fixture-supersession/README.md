# 역사 fixture·승인 모듈·proof 기대 정합

최초 변경 없는 전체 실행은 1168개/288 FAIL/오류0/기존 skip4였다. 287개는 구 PUT 기반 역사 fixture가 새 기본 PRE_SUBMIT_V1 방을 만든 충돌이고, 하나는 승인된 precheck 모듈이 정확 아키텍처 허용 set에 빠진 경우였다. 원본 소스 tar에서 추출한 원본 14개와 각각 SHA·정확 diff를 보존한다.

- 역사 방 fixture 12개 파일만 명시 LEGACY로 지정했다. 전역 profile이나 제품 기본값을 바꾸지 않았고 기존 기대도 유지했다.
- 아키텍처 set에 precheck 하나만 추가했다. 기존 domain/application 의존 경계 검사도 새 모듈에 적용한다.
- GuestCookie proof 테스트 한 파일의 두 equality 기대는 실제 fixture session/expiry provenance를 명시하도록 강화했다. room/participant/level 기대는 동일하다. 원본 6개 중 두 실패를 먼저 보존했다.

340개 targeted 재검증에서 남은 private helper 두 곳도 같은 역사 방 fixture 충돌로 분류해 정합했고, 해당 두 class 19개는 PASS했다. 이 제어 성공을 새 기능의 시간순 RED로 집계하지 않는다. 이후 현재 소스 전체 회귀는 최종 guard의 별도 증거다.
