#!/usr/bin/env bash
set -euo pipefail
set +x

destination="${1:-/opt/meet-me/current/.env.runtime}"
parameter_root="${MEETME_PARAMETER_ROOT:-/meet-me/production}"
region="${AWS_REGION:-ap-northeast-2}"

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
  printf 'APP_ALLOWED_ORIGINS=%s\n' "$allowed_origins"
  printf 'ANONYMOUS_COOKIE_SECURE=true\n'
  printf 'MANAGEMENT_PORT=9090\n'
  printf 'MANAGEMENT_ADDRESS=127.0.0.1\n'
  printf 'REDIS_CONSUMER_NAME=meet-me-production\n'
} >"$temporary"

install -m 0600 "$temporary" "$destination"
