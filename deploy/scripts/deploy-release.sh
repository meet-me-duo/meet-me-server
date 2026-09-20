#!/usr/bin/env bash
set -euo pipefail

release_directory="${1:?release directory is required}"
app_image="${2:?application image digest is required}"
region="${AWS_REGION:-ap-northeast-2}"
compose_project="${MEETME_COMPOSE_PROJECT_NAME:-meet-me-production}"
export COMPOSE_PROJECT_NAME="$compose_project"

if [[ "$app_image" != *@sha256:* ]]; then
  echo "APP_IMAGE must use an immutable sha256 digest" >&2
  exit 1
fi

cd "$release_directory"
chmod 0755 scripts/*.sh container-entrypoint.sh

scripts/render-runtime-env.sh "$release_directory/.env.runtime"
scripts/export-certificate.sh /opt/meet-me/tls

registry="${app_image%%/*}"
aws ecr get-login-password --region "$region" |
  docker login --username AWS --password-stdin "$registry" >/dev/null

docker pull "$app_image"
docker run --rm --network host --env-file "$release_directory/.env.runtime" "$app_image" migrate

printf 'APP_IMAGE=%s\n' "$app_image" >"$release_directory/.release.env"
chmod 0600 "$release_directory/.release.env" "$release_directory/.env.runtime"

previous=""
if [[ -L /opt/meet-me/current ]]; then
  previous="$(readlink -f /opt/meet-me/current || true)"
fi

remove_legacy_container() {
  local container_name="$1"
  local current_project

  if ! docker container inspect "$container_name" >/dev/null 2>&1; then
    return
  fi

  current_project="$(
    docker container inspect \
      --format '{{ index .Config.Labels "com.docker.compose.project" }}' \
      "$container_name" 2>/dev/null || true
  )"
  if [[ "$current_project" != "$compose_project" ]]; then
    docker container rm --force "$container_name" >/dev/null
  fi
}

remove_legacy_container meet-me-app
remove_legacy_container meet-me-nginx

APP_IMAGE="$app_image" docker compose -f compose.production.yml up -d --remove-orphans

healthy=false
for _ in $(seq 1 24); do
  status="$(docker inspect --format '{{.State.Health.Status}}' meet-me-app 2>/dev/null || true)"
  if [[ "$status" == "healthy" ]]; then
    healthy=true
    break
  fi
  if [[ "$status" == "unhealthy" ]]; then
    break
  fi
  sleep 5
done

if [[ "$healthy" != "true" ]]; then
  docker logs --tail 100 meet-me-app >&2 || true
  if [[ -n "$previous" && -f "$previous/.release.env" ]]; then
    previous_image="$(sed -n 's/^APP_IMAGE=//p' "$previous/.release.env")"
    cd "$previous"
    APP_IMAGE="$previous_image" docker compose -f compose.production.yml up -d --remove-orphans
  fi
  echo "Deployment health check failed; application rollback attempted" >&2
  exit 1
fi

ln -sfn "$release_directory" /opt/meet-me/current
docker image prune --force --filter 'until=168h' >/dev/null
