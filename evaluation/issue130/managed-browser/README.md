# Managed browser fixture

This is a local synthetic integration harness. It uses the production managed precheck graph and a disposable PostgreSQL/Redis/local OIDC fixture. Provider HTTPS terminates only at a synthetic loopback TLS server. It proves neither real model meaning nor operational OAuth, billing or activation. All operating settings remain untouched. The historical OFF/Primary provider launcher is preserved separately.

`BACKEND_CONTRACT.md` and `PUBLIC_CONTRACT.md` define the independent backend and browser oracles. Existing original eight, account recovery six and new managed eight have separate results; setup errors are not product RED or successful browser runs.

Rebuild from a detached exact server6046 checkout with only the two approved V2 overlays already applied. Use a local JDK17 and an already populated Gradle cache. The builder refuses other tracked diffs, compiles offline under `/tmp/meetme-gradle.lock`, copies complete production classes/resources and explicit dependency jars, copies only allowlisted fixture classes, freshly compiles the launcher, then fingerprints and validates every snapshot file. It never starts the app, pulls container images or calls providers.

```sh
python3 evaluation/issue130/managed-browser/prepare_runtime.py \
  --server-source /workspace/issue130-managed-browser-integration/server \
  --java-home /tmp/meetme-jdk17 \
  --gradle-user-home /tmp/meetme-gradle-home \
  --output /tmp/meetme-managed-browser-rebuild
python3 evaluation/issue130/managed-browser/runtime_pin.py \
  --manifest /tmp/meetme-managed-browser-rebuild/snapshot/manifest.json --verify-only
```

The output path must not exist. Each rebuild produces its own manifest and compile receipt; local paths/toolchain metadata can change digests and must be reviewed rather than reusing an old approval. Generated classes, jars, logs and ephemeral key material stay local. The selected java/javac executable digests and source-to-output lineage are in `build-metadata/receipt.json`. Container images postgres:18-alpine and redis:8-alpine must already be local before a separate actual launch. The client guard permits only loopback and only the constructor-pinned JDK keytool subprocess. Docker pull policy is explicitly never-pull.

After reviewing the exact runtime, launch with a nonexistent work directory:

```sh
python3 evaluation/issue130/managed-browser/runtime_pin.py \
  --manifest /tmp/meetme-managed-browser-rebuild/snapshot/manifest.json \
  --work-dir /tmp/meetme-managed-browser-run/backend
```

Create only its parent first. The launcher stays running until its `stop` file or a shutdown signal. Readiness is verified separately with `--readiness <backend/managed-readiness.json> --verify-only`. `owned-resources.json` is local metadata for independent cleanup verification. Browser orchestration additionally requires the isolated exact web33 checkout, its restored dependencies and local Chromium. It validates actual source/runtime readback and managed readiness; a compatibility health label alone is insufficient.

The initial sealed snapshot was built through the same explicit compile/copy/manifest steps recorded in local receipts. The consolidated builder was also executed in a fresh output directory: its full offline production/test-class compile and fresh launcher compile passed, followed by full snapshot validation. The first reproduction attempt failed during compileTestKotlin output hashing because the sanitized environment selected ASCII filename encoding; its original output is preserved as a setup failure, not product RED. The builder now fixes LANG and LC_ALL to C.UTF-8. The selected JDK was independently checked: file.encoding, native.encoding and sun.jnu.encoding changed from ASCII to UTF-8 under that two-variable correction. No original source/test expectation was changed. This build proof does not start Spring/PG, execute tests, or imply an actual browser pass. Actual execution and cleanup receipts must be recorded separately.
