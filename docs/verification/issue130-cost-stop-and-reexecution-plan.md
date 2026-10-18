# 비용 표기 구성 중단과 재실행 조건

2026-10-18 UTC. 현재 실제 재시도·B2·예약 정산 적용은 모두0이다.

A24의 원 계획 `128ef6c3…`는 `.10`·`.01441792`·`.34603008`을 사용했다. Python은 이를 허용했지만 원 Java는 선행0이 있는 금액 문자열만 허용해 `validate → cost → decimal`에서 CONFIGURATION으로 종료했다. 클라이언트 객체는 먼저 생성되므로 클라이언트 생성 전 실패라고 말하지 않는다. 실제 요청은 suite/case 표식 이후 `runCase → classify`에서만 시작한다.

원 실행 exit2·CONFIGURATION·stderr0·결과/모든 시작 표식0·source/runtime 전후동일, 원 parser의 세 값 거부·세 canonical 대조군 수용, 진단 copy stack을 독립 감사했다. 추가로 원 Python→fresh javac→원 Java.main을 DUMMY 키와 강제 Python/JDK network/output deny로 재현했다. 두 JVM의 guard 설치와 mainClient 생성 완료 후 validate 진입, DNS/connect/output 쓰기 시도0, index JSON 파싱0, 원1684개 지문 불변을 확인했다. 차단장치 자체는 별도6개 assertion을 통과했다. 이 재현을 과거 실행의 packet capture나 invoice 조회로 부르지 않는다.

따라서 이번 .34603008은 실제 과금 소진이나 기존 UNKNOWN에 합칠 금액이 아니라 **proven-unstarted 취소 정산 후보**다. 원 예약/실패 기록은 보존하고 별도 append-only 정산 근거로 연결할 수 있다. 현재 적용0이므로 장부는 아직2.13925888이며 기존 누적1.79322880(UNKNOWN 포함)은 그대로다. 이 사실로 invoice 전체 청구액0을 주장하지 않는다.

| 단계 | 예약USD | 정산이 확정될 경우 누적USD |
| --- | ---: | ---: |
| 기존 유지(UNKNOWN 포함) | 1.79322880 | 1.79322880 |
| 새 A24 단회 | .34603008 | 2.13925888 |
| A 기술 완료 뒤 사전 고정 standalone B2 | .60825600 | 2.74751488 |

기존 승인USD3 안의 여유는.25248512이며 추가 예산 요청은 없다. 재실행 전에는 이번 예약의 미실행 정산 근거를 확정하고, 기존 실패 디렉터리와 분리한 새 단회 실행/예약 경로를 사용해야 한다. 금액은 `0.10`처럼 Java와 일치하는 문자열을 그대로 작성하며 원 계획은 수정하지 않는다. 수정된 source/필수 guard/runtime/기존24·B2 wire/gold/정산 연쇄/새 plan을 다시 봉인하고 독립 실행 수용을 받는다. 이번 보고는 실제 재시도나 정산 적용 자체가 아니다.

수정 범위는 A/B Python의 금액 문자열 형식 검사 두 곳이다. 자연어 의미 판정·원문 변환·Java 검증 완화·모델·6개 문맥·500자·.8·마감/참여 정책은 바꾸지 않는다. 새 public preflight56 유효 RED44+정상군12 뒤 GREEN, 기존 A41/B143 GREEN과 실제 wrapper→fresh javac→로컬 합성 TLS의 A24 완료 및 재실행 추가0을 확인한다. 합성 응답은 실제 모델 정확도 증명이 아니다.

재실행 시에도 A는24회/serial1·per15초/whole420초, B는 사전2건/count2+generation2 이하·공유per15초/whole120초, retry/redirect/fallback0이다. 기술/API/refusal/필요usage/지문/IO/timeout 오류는 STOP하고 남은 건UNRUN·B 자동진행0이다. 의미 오답은 원 gold로 보존하며 A 의미 FAIL과 별개로 사전 독립B2만 진단할 수 있다. 현재 새24/B2는 전원UNRUN·의미 분모0/NOT_EVALUATED이고 기존16 FAIL은 유지한다. 운영 기준·실제 질문금지군B·UI/최종분석·운영 latency는 미검증이며 main/deploy/활성화 HOLD다.
