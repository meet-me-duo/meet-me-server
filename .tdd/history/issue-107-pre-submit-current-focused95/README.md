# 현재 지문 집중 검증

최종 지문 9개 suite/95개는 실패·오류·skip 0이었다. 구현자 archive의 9개 JUnit XML 합계와 실행 전후 44개 파일 지문 및 당시 실제 disk를 독립 작성자가 다시 대조했다. manifest와 양쪽 source 지문·독립 감사 요약·XML SHA를 보존한다. XML 본문이나 raw log는 이 소형 history에 대량 복사하지 않는다.

현재 지문의 GREEN은 최초 chronological RED나 최종 전체 guard를 대신하지 않는다. FI 결과와 최종 guard는 별도 검증 기록이다.
