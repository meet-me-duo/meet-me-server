# Free managed HTTPS browser contract v1

This is a new proof, separate from the historical OFF/Primary fake runner. No product API, UI, policy, limits, model, prompt, operating settings or private evaluation input changes are authorized. Local synthetic OIDC is permitted; real OAuth and model calls are not.

The independent author previously wrote synthetic fixtures and evaluated the sealed A24/B2. Those historical bytes and failures remain unchanged. This suite uses new public synthetic inputs and checks deterministic transport, ownership and lifecycle behavior, not natural-language model quality.

## Browser minimum

Four unique scenarios run once in desktop and mobile viewport (eight executions, retries/skips zero):

- M01: synthetic test identity/session through the real application auth/session path creates a HOST room with no input/accepted version; shares/defers input. A clear route and a review -> count -> generation -> explicit choice -> recheck route use the actual managed beans and durable ledger. POST202 is saved/pending, never completed. Replayed UUID/body adds no candidate/execution/accepted version; stale revision409 changes nothing. Stable reload restores the same account and own accepted input. A separate guest fixture cannot read HOST private input or close the room. Actual unapproved-origin OPTIONS403 makes no DB change. A fresh untrusted-CA profile rejects TLS.
- M02/M03/M04: HOST remains unsubmitted and 0/1/2 MEMBER inputs are accepted CLEAR. Early close cancel preserves state, confirm closes with the existing insufficient/queued policy. Only the latest accepted versions freeze; a newer pending revision and its late worker response never replace the frozen versions. Repeated close and writes after closure retain existing contracts.

The historical original eight rapid-full-navigation refresh failures and the automatic-focus browser residual remain historical failures/residuals; these new eight are not their successful replacement. Stable navigation is a deliberately named control.

## Unchanged historical suites

The original host8 and account-recovery6 specs must also be attempted byte-for-byte with the new launcher/config, recorded as separate denominators. No skip, retry or assertion relaxation is permitted. Incompatibility is a setup gap; existing rapid-unload failures are reported as failures, not relabelled as passes of the new managed8. The completion validator below verifies only the additional managed8 scope, never the original8/recovery6 or an overall success claim.

## Orchestrator public seam

`runner-contract.mjs` exports `validateManagedReadiness(pins, observed)` returning `{ready:true}` and `validateManagedCompletion(pins, observed)` returning `{verified:true}` only after all requirements below. Invalid evidence throws an Error whose `code` is the exact public code below. This is a deterministic attestation consistency check, not proof that self-reported flags are true. Independent backend tests, source/runtime hash readback, real SQL and HTTP observations supply those facts.

Pins: schemaVersion=1; web/server `{head,files}`; runtime `{files}`; graph `{provider,classifier,budget,repository,gate}`; scenarios exact `M01,M02,M03,M04`; projects exact `desktop,mobile`. File maps are nonempty path -> lowercase SHA256. Heads are lowercase SHA1. The actual pin manifest contains the exact isolated web HEAD3c6fb446 and server6046baf with only approved V2 A/B source overlays; placeholders in unit tests are synthetic.

Observed `provenanceBefore` and completion `provenanceAfter`: `{webHead,serverHead,webFiles,serverFiles,runtimeFiles}` must exactly match pins (no missing, extra or altered file). Required health: fixture=`issue130-managed-browser-v1`, production=false, primaryProviderFake=false, all four managedEnabled/providerEnabled/routingEnabled/classifierEnabled=true, actual graph exactly pinned. Outbound: mode=`LOOPBACK_DENY_ALL_OTHER`, installedBeforeApplication=true, negativeProbeDenied=true, nonLoopbackConnections=0. Credentials: syntheticOnly=true, realEnvironmentKeysInherited=false. Named errors: INVALID_PINS, SOURCE_PIN_MISMATCH, RUNTIME_PIN_MISMATCH, MANAGED_GRAPH_REQUIRED, OUTBOUND_DENY_REQUIRED, SYNTHETIC_CREDENTIALS_REQUIRED.

Completion also requires nonempty equal `assetsBefore/assetsAfter`; browser `{cases:[{scenario,project,status,attempts}],routesIntercepted,unexpectedOrigins}` has the exact 4x2 Cartesian set, passed once each, no route interception/unexpected origin. Required ledger: reservations/dispatchClaims/observations are positive integers; operationCounts.PRECHECK_CLASSIFICATION/PRECHECK_COUNT/PRECHECK_GENERATION each positive; missingOrMismatchedDispatches=0. Network remoteCalls=0, localSyntheticCalls positive. Cleanup ownedContainersRemaining/ownedListenersRemaining/privateTempMaterialRemaining all zero. Named errors: ASSET_PIN_MISMATCH, EXACT_BROWSER_MATRIX_REQUIRED, REAL_MANAGED_LEDGER_REQUIRED, EXTERNAL_PROVIDER_FORBIDDEN, CLEANUP_INCOMPLETE. Missing, malformed, boolean-as-number or negative values must not be accepted.

The runner gathers raw backend readiness and normalizes it to this schema, preserving the original digest-bound attestation and its independent backend validation receipt. The actual graph reports ultimate target classes; no Primary provider/classifier/budget override is permitted. Only the physical HTTP transport is directed to a deny-all-other loopback synthetic server. A reservation or physical local request is not a billable model call. Tests must observe both A skip and B count/generation paths; real provider calls remain zero.

All new files live in the server evaluation directory. The runner stages the new spec/config and a byte-exact copy of the isolated web fixture under an owned /tmp directory, resolving only the fixed web node_modules; original web tracked files stay clean. Source/runtime/assets are verified before and after. Raw request bodies, cookies, auth headers, credentials, user data and private corpus never enter public receipts. Positive and negative controls below validate orchestration; actual browser success must be recorded separately and never inferred from mock tests.
