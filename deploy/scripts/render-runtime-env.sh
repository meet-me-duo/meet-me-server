#!/usr/bin/env bash
set -euo pipefail
set +x

destination="${1:-/opt/meet-me/current/.env.runtime}"
parameter_root="${MEETME_PARAMETER_ROOT:-/meet-me/production}"
region="${AWS_REGION:-ap-northeast-2}"

runtime_fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

if [[ -L "$destination" || ( -e "$destination" && ! -f "$destination" ) ]]; then
  runtime_fail 'Runtime environment destination is unsafe'
fi

# A stored legacy release without this declaration remains Gemini-only.
# Resolve the declaration from this renderer's release, including on DB refresh.
mode_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/runtime-provider-mode"
runtime_mode=gemini-only
if [[ -L "$mode_file" ]]; then
  runtime_fail 'Runtime provider mode is invalid'
elif [[ -e "$mode_file" ]]; then
  [[ -f "$mode_file" && -r "$mode_file" ]] || runtime_fail 'Runtime provider mode is invalid'
  mode_permissions="$(stat -c '%a' "$mode_file" 2>/dev/null)" ||
    runtime_fail 'Runtime provider mode is invalid'
  [[ "$mode_permissions" =~ ^[0-7]{3,4}$ ]] ||
    runtime_fail 'Runtime provider mode is invalid'
  (( (8#$mode_permissions & 0444) != 0 )) ||
    runtime_fail 'Runtime provider mode is invalid'
  # Validate raw bytes before Bash could trim newlines or discard NUL.
  if ! runtime_mode="$(jq -Rser '
    select(. == "gemini-only" or . == "gemini-only\n" or
      . == "gemini-luna-required" or . == "gemini-luna-required\n") |
    rtrimstr("\n")
  ' "$mode_file" 2>/dev/null)"; then
    runtime_fail 'Runtime provider mode is invalid'
  fi
fi

openai_api_key=
if [[ "$runtime_mode" == gemini-luna-required ]]; then
  if ! openai_parameter="$(aws ssm get-parameter \
    --region "$region" \
    --name "$parameter_root/secret/openai-api-key" \
    --with-decryption \
    --output json 2>/dev/null)"; then
    runtime_fail 'Required Luna runtime parameter is unavailable'
  fi
  # Validate JSON before command substitution can trim newlines or discard NUL.
  if ! openai_api_key="$(jq -ser '
    select(length == 1) | .[0].Parameter |
    select(.Type == "SecureString") | .Value |
    select(type == "string" and length > 0) |
    select(explode | all(. >= 33 and . <= 126))
  ' <<< "$openai_parameter" 2>/dev/null)"; then
    runtime_fail 'Required Luna runtime parameter is invalid'
  fi
  unset openai_parameter
fi

get_parameter() {
  aws ssm get-parameter \
    --region "$region" \
    --name "$1" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
}

database_secret_arn="$(get_parameter "$parameter_root/config/database-secret-arn")"
database_secret="$(aws secretsmanager get-secret-value \
  --region "$region" \
  --secret-id "$database_secret_arn" \
  --query 'SecretString' \
  --output text)"
database_password="$(printf '%s' "$database_secret" | jq -er '.password')"

database_url="$(get_parameter "$parameter_root/config/database-url")"
database_username="$(get_parameter "$parameter_root/config/database-username")"
redis_host="$(get_parameter "$parameter_root/config/redis-host")"
redis_port="$(get_parameter "$parameter_root/config/redis-port")"
redis_ssl_enabled="$(get_parameter "$parameter_root/config/redis-ssl-enabled")"
allowed_origins="$(get_parameter "$parameter_root/config/allowed-origins")"
gemini_api_key="$(get_parameter "$parameter_root/secret/gemini-api-key")"

umask 077
temporary="$(mktemp "${destination}.XXXXXX")"
trap 'rm -f "$temporary"' EXIT

{
  printf 'DATABASE_URL=%s\n' "$database_url"
  printf 'DATABASE_USERNAME=%s\n' "$database_username"
  printf 'DATABASE_PASSWORD=%s\n' "$database_password"
  printf 'DATABASE_MAX_POOL_SIZE=10\n'
  printf 'REDIS_HOST=%s\n' "$redis_host"
  printf 'REDIS_PORT=%s\n' "$redis_port"
  printf 'REDIS_SSL_ENABLED=%s\n' "$redis_ssl_enabled"
  printf 'GEMINI_API_KEY=%s\n' "$gemini_api_key"
  printf 'MEETME_RUNTIME_PROVIDER_MODE=%s\n' "$runtime_mode"
  if [[ "$runtime_mode" == gemini-luna-required ]]; then
    printf 'OPENAI_API_KEY=%s\n' "$openai_api_key"
  fi
  printf 'APP_ALLOWED_ORIGINS=%s\n' "$allowed_origins"
  printf 'ANONYMOUS_COOKIE_SECURE=true\n'
  printf 'MANAGEMENT_PORT=9090\n'
  printf 'MANAGEMENT_ADDRESS=127.0.0.1\n'
  printf 'REDIS_CONSUMER_NAME=meet-me-production\n'
} >"$temporary"

chmod 0600 "$temporary"
mv -T "$temporary" "$destination"
