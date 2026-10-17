#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runtime_env="$(mktemp)"
auth_directory="$(mktemp -d)"
chmod 0755 "$auth_directory"
printf '%s\n' 'SYNTHETIC_PRIVATE_FILE' >"$auth_directory/signing-private.pem"
printf '%s\n' '{"keys":[],"fixture":true}' >"$auth_directory/verification.jwks.json"
project_name="meet-me-secret-fidelity-${RANDOM}"

cleanup() {
  APP_IMAGE='alpine:3.22' \
    RUNTIME_ENV_FILE="$runtime_env" \
    AUTH_KEYS_DIRECTORY="$auth_directory" \
    docker compose \
      --project-name "$project_name" \
      -f "$repository_root/deploy/compose.production.yml" \
      down --remove-orphans >/dev/null 2>&1 || true
  rm -f "$runtime_env"
  rm -rf "$auth_directory"
}
trap cleanup EXIT

expected='prefix$J4Ze-suffix'
printf 'DATABASE_PASSWORD=%s\n' "$expected" >"$runtime_env"
expected_openai_key='provider$J4Ze-'"'"'"quoted"-\backslash-#suffix'
expected_provider_mode='gemini-luna-required'
printf 'OPENAI_API_KEY=%s\n' "$expected_openai_key" >>"$runtime_env"
printf 'MEETME_RUNTIME_PROVIDER_MODE=%s\n' "$expected_provider_mode" >>"$runtime_env"

actual="$(
  APP_IMAGE='alpine:3.22' \
    RUNTIME_ENV_FILE="$runtime_env" \
    AUTH_KEYS_DIRECTORY="$auth_directory" \
    docker compose \
      --project-name "$project_name" \
      -f "$repository_root/deploy/compose.production.yml" \
      run --rm --no-deps --entrypoint printenv app DATABASE_PASSWORD
)"

if [[ "$actual" != "$expected" ]]; then
  echo "Production Compose did not preserve the runtime secret value" >&2
  exit 1
fi

actual_provider="$(
  APP_IMAGE='alpine:3.22' \
    RUNTIME_ENV_FILE="$runtime_env" \
    AUTH_KEYS_DIRECTORY="$auth_directory" \
    docker compose \
      --project-name "$project_name" \
      -f "$repository_root/deploy/compose.production.yml" \
      run --rm --no-deps --entrypoint printenv app \
      OPENAI_API_KEY MEETME_RUNTIME_PROVIDER_MODE
)"
expected_provider="$(printf '%s\n%s' "$expected_openai_key" "$expected_provider_mode")"
if [[ "$actual_provider" != "$expected_provider" ]]; then
  echo "Production Compose did not preserve the runtime provider configuration" >&2
  exit 1
fi
