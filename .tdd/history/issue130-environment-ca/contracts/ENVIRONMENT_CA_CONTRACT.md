# Issue130 evaluation-only existing environment CA contract (pre-RED)

Base published revision:289429579a961f29e271e8155463676edde934f0. The user explicitly authorized at 2026-10-18 21:12 KST one existing environment CA in the local evaluation Java client's memory, normal certificate/hostname validation, unauthenticated connection verification first, then the frozen real evaluation within the retained aggregate USD2 budget. No global/JDK/OS/production/Secret changes, arbitrary CA installation, public internal diagnostic publication or new budget authority.

## Additive plan and unchanged behavior

Optional top-level `environmentCa` is supported only with exact `transportMode:ENVIRONMENT_HTTPS_PROXY`. Absence preserves all previously frozen behavior. Presence (including null) is an explicit opt-in and must be an object with exactly three string fields:

```
{"environmentVariable":"NODE_EXTRA_CA_CERTS","pemFileSha256":"64 lowercase hexadecimal","certificateDerSha256":"64 lowercase hexadecimal"}
```

No configurable environment-variable name, arbitrary certificate path, broad platform bundle, default inference, CA download, fallback or skip-verification option. Root freezes the actual approved file and certificate hashes in its single-use execution plan. Synthetic tests freeze only their own synthetic CA hashes.

Both Python validate_plan and Java runSuite validate the structure and existing environment-file identity/pin before any actual HTTP; Java mainClient performs the same validation without DNS, keys or network. The path comes solely from the explicitly supplied environment's NODE_EXTRA_CA_CERTS (Python optional `validate_plan(plan,index,output,environment=None)` parameter, defaulting to current process env only when opt-in is present), must be a nonblank absolute canonical nonsymlink regular file, with no symlink parent alias; 1..16384 bytes and exact PEM byte hash. No input/key/token/path/certificate subject or raw exception logging. Failure is CONFIGURATION before HTTP, with no alternate CA/source/mode.

Certificate input must be one ASCII PEM CERTIFICATE block with only whitespace outside that block; concatenated certificates, binary/PKCS7 or trailing nonwhitespace content are rejected. Java additionally parses exactly one X.509 certificate, verifies the certificate DER SHA, valid current lifetime, CA basic constraints, keyCertSign when keyUsage is present, and self-signed CA signature. Reject extra certificates, leaf certificates, expired/not-yet-valid, wrong pins and malformed data. Python verifies the file metadata and byte hash; cryptographic certificate validation belongs to Java, not duplicated with external subprocesses. Java runSuite must apply the opt-in validation even when a synthetic Dependencies.client is provided; it does not replace/reconfigure that injected client.

## Client-only trust composition

Use the default JDK X509 accepted issuer certificates, retaining all of them, plus that one already-provided environment CA to construct an in-memory KeyStore/default PKIX TrustManagerFactory/SSLContext bound only to this new evaluation OkHttpClient. No trust-all/delegate-OR wrapper, private key read, custom permissive HostnameVerifier, System.setProperty, SSLContext.setDefault, JDK cacerts or platform mutation. The normal default hostname verifier remains. Existing retryOnConnectionFailure=false, redirects=false and SSL redirects=false remain; no endpoint fallback. The additional trust is usable only for the fixed HTTPS provider origin api.openai.com:443 (an application interceptor may reject any other origin before network); production canonical request endpoint and bytes are unchanged. The separately approved no-auth check uses HEAD https://api.openai.com/ only.

Factory construction is pure (no key, DNS or network). The plan defaults stay opt-out. No price/threshold/prompt/input/operating flag changes. No raw response or private corpus access by implementation/review agents.

## Verification and execution order

Independent test author freezes new test sources/expectations and proves genuine RED or distinguishes already-passing controls before implementation. Existing 102+63+privacy4 original tests/oracles remain byte-identical. New tests use synthetic CA/proxy/TLS endpoints only; validate positive client-scoped trust, retained JDK trust, absent opt-in rejection, mismatched CA/pins, invalid metadata/certificate, wrong hostname, fixed-origin confinement, retries/redirects0, normal validation, both plan entry points, and global security state unchanged. Separate author/reviewer performs intentional boundary fault injection and exact restoration. No paid calls by any subagent.

After GREEN/FI/independent review, root freezes source/runtime/plan hashes, performs one logical unauthenticated bodyless HEAD with no provider API key or Authorization and normal trust/hostname validation. Failure STOP: no second CA, arbitrary repair or extra diagnostic retries. Success establishes transport only, not model service/quality. Only then root may execute one separately sealed original16 A-only batch; full16 reservation .23068672 is made before execution, retaining current UNKNOWN/fullhold1.56254208/refunds0 => total1.79322880/headroom.20677120 under approvedUSD2. No invoice hardcap claim or refund based on no-response observations. Existing serial1/15s per case/300s whole/one-use/technical error wholeSTOP/no retry/redirect/fallback/B/count remain. Reusing the old failed suite output is prohibited.

Private TLS cause evidence remains local only. Public documentation may state the approved narrow client CA facility and sanitized execution/quality limitations, without publishing internal diagnostic chain/operator/cert details. Root/custodian separately score completed semantics against the frozen gold, preserving question-forbidden cases, uncertainty and all unrun denominators. No invented accuracy or p50/p95.
