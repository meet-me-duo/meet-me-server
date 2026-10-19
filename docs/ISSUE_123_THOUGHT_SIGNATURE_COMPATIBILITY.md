# #123 실제 응답 metadata 호환 후속 계약

2026-10-18 UTC. [실제 B 진단](ISSUE_107_PRE_SUBMIT_WORKLOG.md#history-issue-123-usd2-generation-diagnostic)에서 질문 제안의 의미는 고정 기준에 부합했지만, 기존 parser가 text와 함께 온 공식 optional thoughtSignature를 거절했다. 이 후속은 모델 prompt·semantic oracle·confidence·가격·제품15초 deadline을 바꾸지 않는 별도 TDD 수정이다. b377의 전체 guard/CI 성공과 수정 전 INVALID_RESPONSE는 역사적 증거로 보존한다.

[공식 규격 검토](verification/issue123-thought-signature-compatibility-proposal.md)를 채택한다. Part는 정확히 하나의 object이며 required string text와 optional thoughtSignature만 허용한다. signature의 absent/null은 unset, empty string은0바이트다. nonnull은 표준 또는 URL-safe base64의 padded/unpadded 형식으로 검증하며 공백·혼합 alphabet·invalid character/길이/padding·잘못된 JSON type은 typed INVALID_RESPONSE로 거절한다. trim·자동수정·원문 의미 판정은 하지 않는다. 기존16KiB 응답 한도 안에서 검증 후 opaque bytes를 폐기한다.

단일 candidate/STOP/part, 중복 JSON key 거절, strict inner JSON, 정확 excerpt, A/B 두 대안, proposal500자와 질문·label 상한, input4096/output2048 thought-inclusive 검증은 유지한다. 형식 실패 때도 이미 반환된 usage 관측을 보존하며 비용 UNKNOWN을0이나 환급으로 바꾸지 않는다. signature는 result/domain/DB/exception/log/metric/request에 전달하지 않는다. 현재 독립 one-shot에는 conversation continuation이 없으며, 향후 multi-turn 재사용은 별도 설계 범위다.

독립 test author가 기존 test bytes를 유지하고 새 `PrecheckThoughtSignatureTransportTest`의 SIG01–04를 고정한다. SIG01은7개 공식 optional 표현과 실제 text payload/usage를 합성 signature로 검증한다. SIG02는9개 잘못된 type/encoding, SIG03은5개 unknown/thought/multipart/candidate/duplicate 경계, SIG04는5개 inner JSON/excerpt/choice/proposal/usage 경계를 loopback runtime provider에서 검증한다. 실제 opaque signature 원값은 fixture에 복사하지 않는다.

순서는 genuine assertion RED→별도 구현자 최소 수정→집중 GREEN→독립 FI/정확 원복→실제 기록의 비네트워크 parser/TwoStage 재생→새 필수 전체 guard→독립 최종 리뷰→같은 Draft PR126 후속 commit/push/정확 HEAD CI다. replay는 과거 A/count와 이번 B 기록을 사용하며 fresh 실제15초 전체 흐름·SDK·영속 금융 authority·UI accepted 저장·일반 정확도를 입증하지 않는다. 추가 유료 호출은0이다.

## 검증 진행 기록

새26개는 compile 성공 후6개의 실제 assertion 실패/20개 통제 통과로 [RED](../.tdd/red/issue123-thought-signature.json)를 봉인했다. 기존 테스트 bytes를 유지하고 별도 구현자가 Contract 한 파일만14줄 추가/2줄 교체했다. source SHA는 `7d1131259a0813c6622fe290712a4c2dfa32514c9f2e2adcf266eedfe79e4880`이다. 새26개 포함 provider/classification328개가 전부 GREEN이고 단일 파일 ktlint도 통과했다. [구현 집중 증거](https://github.com/meet-me-duo/meet-me-server/blob/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history/issue123-thought-signature/implementation-green/receipt.json)는 기존 테스트 inventory 불변을 확인한다.

[실제 기록 재생](../.tdd/verification/issue123-thought-signature-offline-replay.json)은 raw B SHA983e23…의 수정 parser 수용 및 과거 A/count를 통과한 실제 TwoStage의 같은 NEEDS_CHOICE/정확 excerpt/A-B proposedText/530 input·554 thought-inclusive output 보존을 확인했다. HTTP/유료 호출은0이다. 첫 재생은 root가 잘못된 package-qualified harness 이름을 사용한 ClassNotFound 실행 오류였으며 원본을 보존하고 제품 실패/RED로 세지 않았다. 올바른 이름을 사용한 별도 실행만 성공 근거다. 당시 실제 호출에서의 parser 실패를 소급 변경하지 않는다.

독립 [FI3](../.tdd/verification/issue123-thought-signature-faults.json)는 signature 허용 삭제6개, base64 검증 생략5개, unknown Part key 제한 우회2개의 실제 assertion 실패를 탐지했다. 매 변이의 exact restore 뒤 해당 묶음7/9/5개가 통과했고 최종26/26 GREEN, 원본 전체 source/test inventory 및 scratch 복구 지문이 일치했다. [정확 변이·복구 기록](https://github.com/meet-me-duo/meet-me-server/blob/7ab660ad7c1ca3553a63013df0d61ef1c07c668b/.tdd/history/issue123-thought-signature-fi/detailed-receipt.json)을 보존한다.

새 [필수 전체 guard](../.tdd/verification/issue123-thought-signature-final-gate.json)는 06:28:54–06:37:01 UTC에 변경 없는 hook self-tests/ktlintCheck/assemble/test exit0으로 통과했다. fresh213 suite/1710개 중1706PASS·기존4SKIP·실패/오류0, source/config557개 before=after=current를 확인했다. 이 subprocess의 두 API key는 빈 값이며 실제 모델 호출0이다. 이전 b377의1684개 guard를 대체 성공으로 덮지 않고 새 증거를 분리했다. [최종 독립 게시 리뷰](verification/issue123-thought-signature-publication-final-review.md)도 PASS·미해결 P1/P2 없음이다. 후속 commit/push/정확 HEAD CI가 다음 단계다. Commit/PR은 동일 후속 커밋 예정이며, 정확 SHA와 원격 결과는 Git 이력·PR126 및 ignored publication receipt에 기록한다.
