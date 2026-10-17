# Runtime activation and input preflight TDD

Baseline: `21a82f6b7a730172e5e88b4f639d82120a16e5a0`. Independent author; tests/evidence only.

Canonical RED: [../../red/issue-107-runtime-input-gate.json](../../red/issue-107-runtime-input-gate.json). 61 executed, 59 assertion failures, 0 errors: 57 reach the agreed runtime TODO; 2 detect the missing legacy external-endpoint guard before routing. Two PASS controls are not claimed as RED. The earlier compile-only run was invalid as RED and is excluded. Local raw log/XML and exact test/skeleton copies are under `.codex/tdd-evidence/issue-107-runtime-input-gate-red*`. SHA details and classification are in `classification.json`. Test before/after fingerprints match.

## Frozen public expectations

- RR01–06: actual production bean and environment/property binding; default OFF even with a key or arbitrary verified flags; fixed runtime profile on deliberate synthetic opt-in; malformed binding fails startup; output2048 and timeout ceiling15s. Production URL is intercepted before any socket.
- RI01–06: one full frozen GenerateContent request counted with system/schema/context; count integer1..4096 gate; invalid/overflow/duplicate/trailing count, HTTP failure or bounded-body failure never generates. No redirect/retry. Raw500 codepoints and each body16KiB remain.
- RI07–11: count is never returned usage; known returned per-kind nonnegative integralLong usage (including0 and over-cap input) survives strict generation rejection; invalid/missing each kind remains unknown; malformed/duplicate whole envelope yields only three unknown observations. Generation HTTP failure also yields three unknown; count-only failure yields none. Metrics use bounded operation/kind tags only.
- RI12–16: local invalid input sends neither request; count and generation share one monotonic min(request.timeout,15s) deadline; trickle/connection failure cannot retry; legacy verification boolean accepts only synthetic literal-loopback HTTP. External legacy test installs a scoped deny ProxySelector before constructing the provider, so the RED cannot open a socket or resolve an external host; finally restores it.

This cycle adds no live opt-in, production access, paid request, secret change, PG admission change or budget increase. Existing frozen legacy synthetic transport tests are unchanged. One admitted application attempt can make at most one count and one generation (six admitted attempts can therefore make at most12 HTTP requests). Count is a preflight input gate, not proof of billed-input equivalence or a billing hard cap. Actual support, actual charge and the10won policy remain unverified/unresolved.
