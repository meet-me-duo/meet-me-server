# 최초 48개 독립 서버 테스트

실제 PostgreSQL/HTTP와 격리 합성 provider로 실행했다. 최초 실행 48개 중 assertion RED 44개를 고정했고, plain TODO 2개와 V16 부재 fixture 오류 2개는 유효 RED에서 제외했다. 이후 동일 지문으로 48개 GREEN을 확인했다. 원본 소스와 canonical RED의 당시 사본을 보존한다.

초기 RED XML은 다음 직렬 Gradle 실행으로 대체되어 남아 있지 않다. 원본 raw log의 SHA/크기는 manifest에 남겼으며 로그 전체와 XML 대량 복사는 하지 않는다. paid/production 모델 호출은 0이다.

Privacy 서식 변경은 `../issue-107-pre-submit-style-supersession/`, Worker PS06 fixture 정합은 `../issue-107-pre-submit-ps06-fixture-supersession/`에서 분리한다. 새 지문의 사후 FI/재구성 RED를 최초 시간순 RED로 주장하지 않는다.
