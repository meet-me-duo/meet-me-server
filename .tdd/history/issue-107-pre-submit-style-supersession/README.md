# 서식·주석 보정

Privacy의 FQN method chain 두 곳, Defense의 map literal 한 곳, Rate의 줄바꿈과 ADR-039→ADR-040 주석만 변경했다. 정확 diff와 원본·후속 SHA를 보존한다. 세 파일의 문자열 literal 순서가 같음을 독립 대조했고 기대값·요청 경로·test count는 바꾸지 않았다.

Privacy 원본은 최초48 history의 동일 파일을 참조해 중복 보관하지 않는다. 과거 canonical RED의 지문을 새 지문으로 덮어쓰지 않는다. 현재 지문의 GREEN과 후속 FI는 별도 증거다.
