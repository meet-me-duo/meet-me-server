#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_env="$(mktemp)"
project_name="meet-me-secret-fidelity-${RANDOM}"

cleanup() {
  APP_IMAGE='alpine:3.22' \
    RUNTIME_ENV_FILE="$runtime_env" \
    docker compose \
      --project-name "$project_name" \
      -f "$repository_root/deploy/compose.production.yml" \
      down --remove-orphans >/dev/null 2>&1 || true
  rm -f "$runtime_env"
}
trap cleanup EXIT

expected='prefix$J4Ze-suffix'
printf 'DATABASE_PASSWORD=%s\n' "$expected" >"$runtime_env"

actual="$(
  APP_IMAGE='alpine:3.22' \
    RUNTIME_ENV_FILE="$runtime_env" \
    docker compose \
      --project-name "$project_name" \
      -f "$repository_root/deploy/compose.production.yml" \
      run --rm --no-deps --entrypoint printenv app DATABASE_PASSWORD
)"

if [[ "$actual" != "$expected" ]]; then
  echo "Production Compose did not preserve the runtime secret value" >&2
  exit 1
fi
