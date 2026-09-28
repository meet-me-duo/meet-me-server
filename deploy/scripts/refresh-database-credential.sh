#!/usr/bin/env bash
set -euo pipefail
set +x

current_link="${MEETME_CURRENT_LINK:-/opt/meet-me/current}"
lock_file="${MEETME_DEPLOY_LOCK:-/var/lock/meet-me-release.lock}"
pending_file="${MEETME_REFRESH_PENDING_FILE:-/run/meet-me-credential-refresh.pending}"
region="${AWS_REGION:-ap-northeast-2}"
parameter_root="${MEETME_PARAMETER_ROOT:-/meet-me/production}"
compose_project="${MEETME_COMPOSE_PROJECT_NAME:-meet-me-production}"
export COMPOSE_PROJECT_NAME="$compose_project"

exec 9>"$lock_file"
flock -w 300 9

release_directory="$(readlink -f "$current_link")"
if [[ ! -f "$release_directory/.env.runtime" || ! -f "$release_directory/.release.env" ]]; then
  echo 'Current release metadata is missing' >&2
  exit 1
fi

secret_arn="$(aws ssm get-parameter \
  --region "$region" \
  --name "$parameter_root/config/database-secret-arn" \
  --query 'Parameter.Value' \
  --output text)"
current_password="$(aws secretsmanager get-secret-value \
  --region "$region" \
  --secret-id "$secret_arn" \
  --version-stage AWSCURRENT \
  --query 'SecretString' \
  --output text | jq -er '.password')"
runtime_password="$(sed -n 's/^DATABASE_PASSWORD=//p' "$release_directory/.env.runtime")"
app_password="$(docker exec meet-me-app sh -c 'printf %s "$DATABASE_PASSWORD"' 2>/dev/null || true)"
app_health="$(docker inspect --format '{{.State.Health.Status}}' meet-me-app 2>/dev/null || true)"

if [[ "$current_password" == "$runtime_password" && "$current_password" == "$app_password" && "$app_health" == 'healthy' ]]; then
  rm -f "$pending_file"
  echo 'Database credential is current and the app is healthy'
  exit 0
fi

if [[ "$current_password" == "$runtime_password" && "$current_password" == "$app_password" && ! -f "$pending_file" ]]; then
  echo 'Database credential is current but the app is unhealthy' >&2
  exit 1
fi

umask 077
: >"$pending_file"
"$release_directory/scripts/render-runtime-env.sh" "$release_directory/.env.runtime"
app_image="$(sed -n 's/^APP_IMAGE=//p' "$release_directory/.release.env")"
if [[ "$app_image" != *@sha256:* ]]; then
  echo 'Stored APP_IMAGE is not an immutable digest' >&2
  exit 1
fi

cd "$release_directory"
APP_IMAGE="$app_image" docker compose -f compose.production.yml up -d --no-deps --force-recreate app

for _ in $(seq 1 24); do
  app_health="$(docker inspect --format '{{.State.Health.Status}}' meet-me-app 2>/dev/null || true)"
  if [[ "$app_health" == 'healthy' ]]; then
    rm -f "$pending_file"
    echo 'Database credential refreshed and the app is healthy'
    exit 0
  fi
  if [[ "$app_health" == 'unhealthy' ]]; then
    break
  fi
  sleep 5
done

echo 'Database credential refreshed but the app did not become healthy' >&2
exit 1
