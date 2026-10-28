# Confirmed result review: independent acceptance plan

Prepared from the delegated, confirmed product policy on 2026-10-28. This is a test design, not an execution or deployment result. Only synthetic inputs are used.

| ID | Public behavior and boundary |
| --- | --- |
| RV-01 | A HOST and a MEMBER who can read the confirmed result can each request a review. An unconfirmed result cannot create an eligible prompt. |
| RV-02 | Missing/invalid ownership, another participant, another room, and replacement-only guest proof cannot read or mutate a prompt. A client-supplied identity never grants authority. |
| RV-03 | Choice 1 is exclusive. Choices 2–5 permit all nonempty subsets. OTHER alone with null/empty text is valid. Unknown, duplicate, or empty choices are rejected without consuming the prompt. |
| RV-04 | Other text is optional, treated as plain text, bounded consistently in the contract/UI/database, and never returned by prompt/terminal acknowledgments or error messages. Text without OTHER cannot silently become a saved draft. |
| RV-05 | A room is asked at most once for the proven participant. A retry of the same request can recover its pending claim; another request after reload or on another device cannot issue another prompt. Concurrent claims have a single winner. |
| RV-06 | Satisfied submission stops subsequent automatic prompts across rooms and devices of the same account. It takes precedence over a checked one-week hide box. It does not affect another account or unrelated guest. |
| RV-07 | A non-satisfied submission suppresses another prompt immediately and until seven days after server acceptance. At the exact boundary, the next distinct confirmed room may ask. The submitted room is never asked again. |
| RV-08 | Ordinary close is unsubmitted with or without an in-memory draft. It sends no choices/text. The same room is not asked again; the next eligible confirmed room may ask. |
| RV-09 | Checked close suppresses the proven user across rooms for seven days. Retry does not extend the original server deadline. Before/exactly-at/after deadline are distinguished. |
| RV-10 | Identical submit retry succeeds without duplicate rows or policy extension; changed submission conflicts. Submit/close competition preserves a single terminal outcome. Late close cannot erase a submission; late submit cannot turn a close into a submission. |
| RV-11 | A prompt already pending in another device/room cannot bypass a subsequent satisfied opt-out or otherwise reset the latest suppression. Terminal retries remain recoverable without returning stored text. |
| RV-12 | A verified guest-to-account claim preserves same-room deduplication and proven satisfaction/hide history. Names, invite codes, or unrelated browser sessions cannot merge users. Replacement-only guest recovery does not inherit read/review authority. |
| RV-13 | New persistence constraints reject malformed owner/terminal rows. Transaction failure rolls back both response and policy. Existing room/account cleanup cannot leave a contradictory policy or expose free text. |
| RV-14 | Native OpenAPI documents every endpoint, success/error status, allowed choices, nullable prompt, and text limits. No admin listing, external transfer, or review-content endpoint appears. |
| RV-15 | Logs do not include synthetic sensitive markers, request/response text, credentials, or identity payloads. Error/acknowledgment bodies do not echo them. |

The web acceptance layer separately verifies mobile width and overflow, keyboard focus/escape/tab behavior, accessible grouped choices, exclusive satisfaction, OTHER expand/collapse without persistence, pending controls, recoverable errors, duplicate clicks, unmount/back navigation, and in-memory draft lifetime. Confirmed-result screen mounting and native browser checks remain integration work until the concurrent web owner coordinates the insertion point.

Required evidence distinguishes compiled RED caused by missing behavior, GREEN, independent fault injection, local PostgreSQL/HTTP tests, browser checks, remote CI, and deployment. A cache, Docker, dependency, or compilation failure is an environment/tooling blocker and is not RED evidence.
