# Stopped maintenance resume

Use the explicit `resume` action only for a reviewed interrupted transition. It is
never selected by automatic publication or by the ordinary `deploy` action.
`approve_first_transition=true` is required in addition to `action=resume`.

## Preconditions

- Publish a corrected **new** release through successful main CI. Pin its source,
  build run/attempt, immutable manifest, archive SHA, schema/guard fingerprints and
  complete ECR digest. Existing release directories are still rejected; no archive
  or failed release is overwritten.
- Both runtime helpers must be regular, owner-executable files in the archive.
  Root extraction keeps `umask 077` and `--no-same-owner --no-same-permissions`.
  Host validation checks the extracted helpers before any installer, app stop or
  marker change. No recursive chmod or fixed launcher upgrade is performed.
- The installed guard must be trusted and retain the exact `V8_STARTED` phase and
  `input_revision_v8` sticky marker. Ordinary running-app preflight remains unchanged.
- Both credential refresh rules must be DISABLED. Global Pending/Executing SSM
  command metadata must prove no active command targets the instance; unknown or
  paginated target metadata fails closed.
- The app must remain present and stopped, with unchanged PID, restart count and
  image during the reader. Auth key/mount readiness and parameter metadata must
  still pass their existing checks.
- The reviewed image digest must already be cached on the host. Acquiring that
  exact image is an explicitly authorized deployment preparation step using the
  existing ECR path, not an implicit action of this read-only DB reader.

## DB reader and authority

The maintenance reader never starts or execs the existing application. It runs
only `ReadOnlyFlywayProbe` in a separate bounded container from the exact reviewed
image (`--pull=never`, overridden entrypoint, read-only root filesystem, no Linux
capabilities, no new privileges). Its database-only environment comes from the
verified root-owned 0600 environment of the current release and is copied into a
private temporary file. Values and subprocess diagnostics are never emitted.
Stale or missing credentials fail closed; this path does not rotate them.

The JDBC reader does not start Spring or run Flyway. It requires transaction read
only, short connection/query/lock timeouts and rollback. Only database identity,
server version and `flyway_schema_history` version/script/checksum/success leave
the reader. The database must be `meetme`; its history must exactly match a
successful prefix of the reviewed manifest. Unknown, changed or failed rows stop
the operation. The selected probe container and private temporary files are
cleaned up; no application container is removed by that cleanup.

After fresh driver checks, the host helper repeats archive, executable, guard,
stopped-app and history checks under its existing serialization. The existing
fixed launcher remains the sole lifecycle authority. It preserves the sticky
marker, performs migrations using the approved digest, starts that release and
selects current/READY only after health succeeds. Exact post-migration history,
digest, current link and trusted installed catalog are required before the
automatic-release contract is recorded.

## Stop and completion criteria

Artifact, guard, image, auth, command-drain or history mismatch, reader failure,
migration failure, health failure or final contract failure stops the release.
No old image fallback, marker reset or database downgrade is permitted. A failed
transition remains in maintenance for review. Do not retry an uncertain remote
operation before reading its actual SSM and host state.

Verify runtime digest, current link, READY, complete Flyway history, application
and Nginx health, OpenAPI, auth mounts and the final automatic-release contract.
Restoring the two credential refresh rules is a separate explicit operating
action after those checks, and is not performed by this workflow.
