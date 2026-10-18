# #130 로컬 평가 client의 환경 CA opt-in

2026-10-18 UTC. 사용자는 기존 환경 CA1개를 이번 로컬 평가 Java client의 메모리에만 추가하고, 정상 인증서·hostname 검증을 유지한 뒤 합산 USD2 안에서 동결16건 평가를 진행하도록 승인했다. 이 기능은 운영 설정·JDK cacerts·OS 신뢰·Secret을 변경하지 않는다.

`transportMode:ENVIRONMENT_HTTPS_PROXY`에서만 선택적 `environmentCa`를 허용한다. 정확한 필드는 `environmentVariable:NODE_EXTRA_CA_CERTS`, `pemFileSha256`, `certificateDerSha256`이다. 실제 환경 파일과 인증서 지문은 로컬 단회 실행 계획에만 보관한다. 부재 시 기존 동작이며 환경 변수 존재만으로 켜지지 않는다.

정규·canonical·무 symlink 파일16KiB 이하, 단일 ASCII PEM, bytes/DER 지문, CA·수명·keyCertSign·자체 서명을 검증한다. 기존 기본 trust anchors를 보존한 PKIX trust manager와 SSLContext를 새 client에만 연결한다. 기본 hostname 검증, 고정 HTTPS 공급자 origin, retry/redirect/fallback0을 유지한다. 주입된 합성 client가 있어도 Java runSuite의 동일 CA 검증을 우회할 수 없다.

Python 실행기는 명시 `JAVA_HOME`의 compiler/JVM을 함께 사용하고, 잘못된 명시 설정에서 fallback하지 않는다. 값이 없을 때만 기존 로컬 JDK 기본값을 사용한다. 기존 PC01 테스트·기대값·30초 제한을 변경하지 않았다. 첫 두 CI 실패는 보존하며 JDK 이식성 결함 확인과 그 실패의 정확 내부 assertion 인과를 구별한다.

독립 CA66·JDK16과 기존169 회귀가 통과했다. 새 CA63의 요구 실패53/초회 통과10, JDK16의 요구 실패14/통제2를 구분한다. 리뷰 뒤 추가한 runSuite3은 INITIAL_PASS→보존된 이전 구현의 RECONSTRUCTED_RED→현재 GREEN으로 기록했다. 새 CA FI6·JDK FI3은 실제 assertion 탐지와 정확 복구를 확인했다. 기존 CA63/원169의 파일과 기대값은 그대로다.

필수 guard는 source574 전후 동일/exit0이며 기존1717 XML(1713PASS/기존4SKIP)을 재사용했다. 새 fresh1717 실행으로 부르지 않는다. 고정 source271/runtime1401의 무인증 HEAD1은 정상 TLS·hostname 검증 후 HTTP421을 받았다. 전송 경로만 확인한 결과로, 모델 가용성·의미 품질·API 청구0 증명이 아니다.

새 단회16건 실행은 별도 계획·무결성·비용·실행 전 독립 검토로 봉인한다. 이전 UNKNOWN 포함 예약1.56254208을 보존하고 시작 전에만 .23068672를 더해 총1.79322880/여유.20677120/환급0으로 관리한다. 이 산술은 보수적인 계획 예약이며 공식 invoice hard cap을 증명하지 않는다. 실행·채점 결과는 상위 평가 보고서에 별도로 기록한다. 운영 임계값·실제 가격 registry·운영 활성화와 실제 생성 질문 품질은 이 검증 범위 밖이다.

근거는 `.tdd/verification/issue130-environment-ca-{red,independent-final,independent-faults,supplement}.json`과 `issue130-jdk-runtime-selection-{red,green,faults}.json`이다. 내부 상세 인증·TLS 진단, 실제 인증서·키·비공개 원문/gold는 게시하지 않는다.
