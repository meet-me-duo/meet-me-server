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

release_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
auth_mode_file="$release_directory/runtime-auth-mode"
auth_required=false
if [[ -L "$auth_mode_file" ]]; then
  runtime_fail 'Runtime authentication mode is invalid'
elif [[ -e "$auth_mode_file" ]]; then
  [[ -f "$auth_mode_file" && -r "$auth_mode_file" ]] || runtime_fail 'Runtime authentication mode is invalid'
  auth_mode_permissions="$(stat -c '%a' "$auth_mode_file" 2>/dev/null)" ||
    runtime_fail 'Runtime authentication mode is invalid'
  [[ "$auth_mode_permissions" =~ ^[0-7]{3,4}$ ]] || runtime_fail 'Runtime authentication mode is invalid'
  (( (8#$auth_mode_permissions & 0444) != 0 )) || runtime_fail 'Runtime authentication mode is invalid'
  if ! jq -Rse 'select(. == "oauth-rs256-required" or . == "oauth-rs256-required\n")' \
    "$auth_mode_file" >/dev/null 2>&1; then
    runtime_fail 'Runtime authentication mode is invalid'
  fi
  auth_required=true
  # New releases default to their own canonical directory; legacy defaults stay unchanged.
  if [[ "$#" == 0 ]]; then destination="$release_directory/.env.runtime"; fi
fi

validate_auth_destination() {
  local entry="$destination"
  [[ ! -L "$entry" && ( ! -e "$entry" || -f "$entry" ) ]] ||
    runtime_fail 'Runtime environment destination is unsafe'
  while [[ "$entry" != / && "$entry" != . ]]; do
    [[ ! -L "$entry" ]] || runtime_fail 'Runtime environment destination is unsafe'
    entry="$(dirname -- "$entry")"
  done
}
if [[ "$auth_required" == true ]]; then validate_auth_destination; fi

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

get_auth_parameter() {
  # Never capture unvalidated JSON in Bash: command substitution discards raw NUL.
  if ! aws ssm get-parameter \
    --region "$region" --name "$parameter_root/$1" \
    --with-decryption --output json 2>/dev/null |
    jq -Rser --arg expected_type "$2" '
    select(explode | all(. != 0)) | fromjson | .Parameter |
    select(.Type == $expected_type) | .Value |
    select(type == "string" and length > 0) |
    select(explode | all(. >= 33 and . <= 126))
  ' 2>/dev/null; then
    runtime_fail 'Required authentication runtime parameter is invalid'
  fi
}

if [[ "$auth_required" == true ]]; then
  google_client_id="$(get_auth_parameter secret/google-client-id SecureString)"
  google_client_secret="$(get_auth_parameter secret/google-client-secret SecureString)"
  kakao_client_id="$(get_auth_parameter secret/kakao-client-id SecureString)"
  kakao_client_secret="$(get_auth_parameter secret/kakao-client-secret SecureString)"
  google_redirect_uri="$(get_auth_parameter config/google-redirect-uri String)"
  kakao_redirect_uri="$(get_auth_parameter config/kakao-redirect-uri String)"
  auth_signing_key_id="$(get_auth_parameter config/auth-signing-key-id String)"
  auth_issuer="$(get_auth_parameter config/auth-issuer String)"
  auth_audience="$(get_auth_parameter config/auth-audience String)"
  auth_web_callback_uri="$(get_auth_parameter config/auth-web-callback-uri String)"
  allowed_origins="$(get_auth_parameter config/allowed-origins String)"
  precheck_provider_enabled=false
  if precheck_parameter="$(
    set +e
    aws ssm get-parameter \
      --region "$region" --name "$parameter_root/config/precheck-provider-enabled" \
      --with-decryption --output json 2>/dev/null |
      jq -Rser '
      select(explode | all(. != 0)) | fromjson | .Parameter |
      select(.Type == "String") | .Value |
      select(type == "string" and (. == "true" or . == "false"))
    ' 2>/dev/null
    pipeline_status=("${PIPESTATUS[@]}")
    if [[ "${pipeline_status[0]}" != 0 ]]; then exit 71; fi
    if [[ "${pipeline_status[1]}" != 0 ]]; then exit 72; fi
  )"; then
    precheck_provider_enabled="$precheck_parameter"
  else
    lookup_status="$?"
    if [[ "$lookup_status" != 71 ]]; then
      runtime_fail 'Pre-submit provider runtime configuration is invalid'
    fi
  fi
  unset precheck_parameter
fi

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
if [[ "$auth_required" != true ]]; then
  allowed_origins="$(get_parameter "$parameter_root/config/allowed-origins")"
fi
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
  if [[ "$auth_required" == true ]]; then
    printf 'GOOGLE_CLIENT_ID=%s\n' "$google_client_id"
    printf 'GOOGLE_CLIENT_SECRET=%s\n' "$google_client_secret"
    printf 'GOOGLE_REDIRECT_URI=%s\n' "$google_redirect_uri"
    printf 'KAKAO_CLIENT_ID=%s\n' "$kakao_client_id"
    printf 'KAKAO_CLIENT_SECRET=%s\n' "$kakao_client_secret"
    printf 'KAKAO_REDIRECT_URI=%s\n' "$kakao_redirect_uri"
    printf 'AUTH_SIGNING_KEY_ID=%s\n' "$auth_signing_key_id"
    printf 'AUTH_ISSUER=%s\n' "$auth_issuer"
    printf 'AUTH_AUDIENCE=%s\n' "$auth_audience"
    printf 'AUTH_WEB_CALLBACK_URI=%s\n' "$auth_web_callback_uri"
    printf 'AUTH_SIGNING_PRIVATE_KEY_PATH=/run/meetme-auth/signing-private.pem\n'
    printf 'AUTH_VERIFICATION_JWKS_PATH=/run/meetme-auth/verification.jwks.json\n'
    printf 'MEETME_PRECHECK_PROVIDER_ENABLED=%s\n' "$precheck_provider_enabled"
  fi
  printf 'ANONYMOUS_COOKIE_SECURE=true\n'
  printf 'MANAGEMENT_PORT=9090\n'
  printf 'MANAGEMENT_ADDRESS=127.0.0.1\n'
  printf 'REDIS_CONSUMER_NAME=meet-me-production\n'
} >"$temporary"

chmod 0600 "$temporary"
if [[ "$auth_required" == true ]]; then validate_auth_destination; fi
mv -T "$temporary" "$destination"
