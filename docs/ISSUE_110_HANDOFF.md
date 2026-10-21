# Issue 110: operator summaries and local transport observations

This unit joins the actual account implementation from server PR115 at
`c06b60a9833ae77e5fce9cfef5719ab2f37470ff` and web PR25 at
`95f09c0a98c6538b0f19ed381fdf11a2f5b844d9`. The separate integration branch is
`feature/110-account-109-integration` in each repository. The original uncommitted
server checkout and standalone web PR26 are preserved.

## Policy and implementation

Operations remain disabled with an empty account UUID allowlist by default.
Only `CurrentAccountPort.currentAccount()?.accountId` supplies operator identity.
Normal members, guest cookies, HOST ownership, names and caller identity hints do
not grant access. Invalid Bearer authentication returns401; unavailable identity
infrastructure returns503. Neither becomes a guest session. No real account was
registered and no operational enablement, AWS/SSM access or deployment occurred.

GET summaries use named safe projections, historical execution versions and
invocation-scoped attempts. Durable minimal auditing gates protected output;
failed audit storage returns503. Audit retention defaults to30days and is
configurable. Raw submissions, provider responses, owner tokens, request URLs,
headers and secrets are absent from these summaries and audits.

V14 adds the minimal audit store. V15 links bounded local client lifecycle facts
to the exact admitted invocation/attempt/provider. Parser dispatch, local call
start/completion, wall-clock execution time, outcomes and known usage have
separate meanings. Unknown remains null; known usage is a subtotal. Neither
provider receipt, provider billing nor exactly-once delivery is claimed.

The web uses the real memory token supplier on every request and the actual
session revision. Expired known sessions fail before fetch; account changes and
logout discard protected state and late responses. Tokens are not persisted.

## Verified boundaries

- The unchanged repository commit guard succeeds on the final combined source:
  hook self-tests, ktlintCheck, assemble and test. Fresh JUnit has150 suites,
  888 cases:884 pass, four existing opt-in skips, failures/errors zero. It ran
  17:36:05–17:43:54 UTC after the corrected focused20 passed. The earlier full
  888-case run with four assertion failures remains archived separately.

- Actual socket HTTP:14 authorization/authentication/audit cases pass, plus one
  PostgreSQL migration runner test. The first valid RED had12 assertion failures
  in15 cases; the uncached dependency setup failure is excluded.
- Web:415 unit tests pass; production browser395 pass and one existing skip,
  no failures/flaky/retries. Source and built-output fingerprints are unchanged.
- Native browser:8 checks pass with actual isolated Spring, PostgreSQL18,
  Redis8, loopback OIDC/JWKS, RS256 and refresh rotation. API route mocks0,
  external requests0. This is synthetic local authentication, not production
  Google/Kakao login. Fixed test ports18910/18911 preserve an existing8080 owner.
- The actual integrated OpenAPI has three operations paths and ten referenced
  components identical to the generated web snapshot. The integrated export is
  `verification/issue-110-account-integrated.openapi.json` in this docs directory.
- Release policy:Linux32 tests pass, including real symlink rejection; the
  existing publication/approval suite separately passes7 on Windows with actual
  Git metadata. A container Git-path setup failure and Windows symlink privilege
  failures are preserved as setup failures, not product RED or success.
- Nginx static privacy contracts6 pass. Actual unprivileged network-none Nginx
  rejects two callback upstream requests with502 and a malformed request with400
  without logging synthetic code/state. This projects the repository HTTP policy
  without TLS; it is not a production configuration or TLS verification claim.
- New memory-binding faults2/2 and guard/log faults3/3 are detected by assertions
  with exact source restoration and unchanged tests. The original standalone
  transport/operator mutation results remain separate historical evidence.
- One additional server authentication-status fault is detected by seven of
  twelve actual HTTP assertions (five pass, errors/skip zero). Invalid and expired
  Bearer, malformed headers and duplicate authorization remain401 after restoration.

Initial native fixture failures (occupied8080, invalid synthetic UUIDs and an
unhandled wait promise) are excluded. Their corrections affect test utilities,
not validation policy. Existing109 test methods retain their behavior; original
fixture bytes and fingerprint changes are in `.tdd/history/issue-110-native-*`.

The old standalone evidence and unapplied-account patch are historical artifacts.
Actual integration uses the repository source and the evidence above. Local raw
source hashes may include Windows line endings; source pins normalize LF, while
release schema/guard fingerprints always bind the actual publication bytes.

## Nginx and release approval

Nginx error diagnostics include unredacted request lines. Request-context error
logging is therefore suppressed throughout `http`, including callbacks and
pre-location parsing errors. Safe access summaries retain request ID, method,
URI path, status and timing; startup/configuration diagnostics remain stderr.
This trades detailed proxy request-error messages for callback confidentiality.
No running Nginx configuration was changed.

The publisher accepts only exact named source catalogs V1..V10+V14/V15 or
V1..V11+V14/V15. It rejects unknown files, reserved placeholders, missing or
duplicate versions and symlinked files, directories, ancestors or repository
roots. Readers retain immutable V10/V11 contracts. Exact successful history,
source checksums, guard fingerprints, no pending commands and an explicit first
transition with both rules paused remain required. Changed schema or guard
fingerprints cannot silently reuse approval. No approval record was changed.

## Central integration handoff

The separately verified107+109 combination is server PR117
`c44225d814ce016dfe7d0deb1ec77632cb561448` (CI39051106339) and web PR28
`fb87358feecf6f979cd55bb10a218a0fe0e1eb3b` (CI39050257242). It is not included in
this110+109 verification. No other issue branch was overwritten.
The parent subsequently reports that107 is being revised in another context for
AI confirmation before submission. PR117/28 identify the older verified source;
they must not be treated as the final107 product contract or completion evidence.

Shared merge points include server release validators, catalog tests, CI,
architecture/PRD/worklog, migration-runner expectations and the native109 fixture;
web App routing, OpenAPI/generated types, architecture and implementation log.
Keep107 clarification routes and ownership checks alongside the operations route.
This110 delta does not modify `SubmissionService`, participant ownership/proof
ports or account production code. Real108 proof issuance is still107's separate
integration dependency; HOST/proof possession must never imply operator access.

V11=109, V12=108, V13=107, V14/V15=110. The current sparse catalog intentionally
does not accept V12/V13. Central integration must add their actual approved files
and checksums, preserve historical complete catalogs and exact prefix ordering,
and rerun fingerprint/approval/security and full integration checks. Do not add
empty placeholders or replace exact allowlists with arbitrary file discovery.

## Publication status

The successful local server gate is recorded in
`verification/issue-110-integration-final.json`; independent evidence review is
alongside it. Exact published HEAD CI is recorded in the Draft PR after publication.
Commit/feature push/Draft PR are authorized; merge, deployment, operational DB,
secrets, real keys and real operator assignment remain outside this task.
