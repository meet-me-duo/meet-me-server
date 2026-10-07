#!/usr/bin/env bash
set -euo pipefail
set +x

# Local, isolated image verification only. Never pull/push or start the application.
phase=inspect
probe=0
fail() { printf 'Built image verification failed phase=%s probe=%d\n' "$phase" "$probe" >&2; exit 1; }
[[ $# == 1 && "$1" =~ ^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$ ]] || fail
image="$1"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
scratch="$(mktemp -d)"
containers=()
cleanup() {
  for name in "${containers[@]}"; do docker rm -f "$name" >/dev/null 2>&1 || true; done
  rm -rf "$scratch"
}
trap cleanup EXIT
trap fail HUP INT TERM

metadata="$(docker image inspect --format '{{.Id}} {{.Os}} {{.Architecture}} {{.Config.User}} {{json .Config.Entrypoint}}' "$image" 2>"$scratch/error")" || fail
phase=metadata
[[ "$metadata" =~ ^(sha256:[0-9a-f]{64})\ linux\ arm64\ 10001:10001\ \[\"/app/container-entrypoint.sh\"\]$ ]] || fail
config_id="${BASH_REMATCH[1]}"
image="$config_id"
source_sha="$(sha256sum "$repository_root/deploy/container-entrypoint.sh")"
source_sha="${source_sha%% *}"
[[ "$source_sha" =~ ^[0-9a-f]{64}$ ]] || fail

# Keep Docker option arguments before the image and command arguments after it.
run_command() {
  local name="meetme-built-image-$(basename "$scratch")-${#containers[@]}"
  containers+=("$name")
  local -a options=()
  while [[ $# -gt 0 && "$1" != -- ]]; do options+=("$1"); shift; done
  [[ $# -gt 0 ]] || fail
  shift
  probe=$((probe + 1))
  docker run --platform linux/arm64 --name "$name" --rm --pull never --network none --read-only \
    --security-opt no-new-privileges --cap-drop ALL \
    --tmpfs /tmp:rw,nosuid,size=128m "${options[@]}" "$image" "$@" \
    >"$scratch/stdout" 2>"$scratch/stderr"
}

phase=file
run_command --entrypoint /bin/sh -- -eu -c '
  test "$(id -u):$(id -g)" = 10001:10001
  test -f /app/app.jar && test -s /app/app.jar
  test "$(stat -c %u:%g /app/app.jar)" = 10001:10001
  test -f /app/container-entrypoint.sh && test ! -L /app/container-entrypoint.sh
  test "$(stat -c %u:%g:%a /app/container-entrypoint.sh)" = 10001:10001:755
  actual=$(sha256sum /app/container-entrypoint.sh); test "${actual%% *}" = "$1"
' image-file-check "$source_sha" || fail
[[ ! -s "$scratch/stdout" && ! -s "$scratch/stderr" ]] || fail

check_jre() {
  [[ ! -s "$scratch/stdout" ]] || fail
  grep -Eq '^openjdk version "17\.' "$scratch/stderr" || fail
  grep -Eq 'OpenJDK (64-Bit )?Server VM' "$scratch/stderr" || fail
  ! grep -Eq 'Spring|Started .*Application' "$scratch/stderr" || fail
}

# Legacy mode is absent, and the custom command is the real packaged JRE.
phase=jre-legacy-absent
run_command -- java -version || fail
check_jre
phase=jre-legacy-explicit
run_command --env MEETME_RUNTIME_PROVIDER_MODE=gemini-only -- java -version || fail
check_jre
synthetic_key='image$fixture-'"'"'"quoted"-\backslash-#opaque'
phase=jre-required
run_command --env MEETME_RUNTIME_PROVIDER_MODE=gemini-luna-required \
  --env "OPENAI_API_KEY=$synthetic_key" -- /bin/sh -eu -c \
  'test "$OPENAI_API_KEY" = "$1"; exec java -version' image-key-check "$synthetic_key" || fail
check_jre

# A temporary Java sentinel makes an accidental acceptance safe even for server/migrate.
# It runs as UID10001 in private /tmp; the real application and migration never start.
guard_probe='mkdir /tmp/probe; printf "#!/bin/sh\necho JAVA_STARTED\nexit 97\n" > /tmp/probe/java; chmod 755 /tmp/probe/java; export PATH=/tmp/probe:$PATH; exec /app/container-entrypoint.sh "$@"'
check_rejected() {
  local reason="$1"; shift
  if run_command --entrypoint /bin/sh "$@"; then fail; fi
  [[ ! -s "$scratch/stdout" ]] || fail
  printf '%s\n' "$reason" >"$scratch/expected-error"
  cmp -s "$scratch/expected-error" "$scratch/stderr" || fail
}
for command in server migrate custom; do
  arguments=("$command")
  [[ "$command" != custom ]] || arguments=(java -version)
  phase=key-guard
  check_rejected 'Required Luna runtime key is missing or invalid' \
    --env MEETME_RUNTIME_PROVIDER_MODE=gemini-luna-required -- \
    -eu -c "$guard_probe" image-key-guard "${arguments[@]}"
  for unsafe in '' 'contains space' $'contains\tTAB' $'contains\nLF' 'unicode-키'; do
    check_rejected 'Required Luna runtime key is missing or invalid' \
      --env MEETME_RUNTIME_PROVIDER_MODE=gemini-luna-required --env "OPENAI_API_KEY=$unsafe" -- \
      -eu -c "$guard_probe" image-key-guard "${arguments[@]}"
  done
  phase=mode-guard
  for invalid_mode in unsupported $'gemini-only\n'; do
    check_rejected 'Runtime provider mode is invalid' \
      --env "MEETME_RUNTIME_PROVIDER_MODE=$invalid_mode" --env "OPENAI_API_KEY=$synthetic_key" -- \
      -eu -c "$guard_probe" image-mode-guard "${arguments[@]}"
  done
done

# Config ID identifies this local build only; it is not an ECR release manifest digest.
phase=provenance
source_revision="$(git -C "$repository_root" rev-parse HEAD 2>"$scratch/error")" || fail
[[ "$source_revision" =~ ^[0-9a-f]{40}$ ]] || fail
printf '{"protocol":"meetme-built-image-v1","sourceSha":"%s","configImageId":"%s","architecture":"arm64","entrypointSha256":"%s","runtimeJava":17,"cases":28,"network":"none","rootFilesystem":"read-only","noEcrDigestApproval":true,"checks":"passed"}\n' "$source_revision" "$config_id" "$source_sha"
