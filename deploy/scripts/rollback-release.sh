#!/usr/bin/env bash
set -euo pipefail

release_directory="${1:?previous release directory is required}"
compose_project="${MEETME_COMPOSE_PROJECT_NAME:-meet-me-production}"
export COMPOSE_PROJECT_NAME="$compose_project"

if [[ ! -f "$release_directory/.release.env" ]]; then
  echo "Release metadata not found: $release_directory/.release.env" >&2
  exit 1
fi

app_image="$(sed -n 's/^APP_IMAGE=//p' "$release_directory/.release.env")"
if [[ "$app_image" != *@sha256:* ]]; then
  echo "Stored APP_IMAGE is not an immutable digest" >&2
  exit 1
fi

cd "$release_directory"
APP_IMAGE="$app_image" docker compose -f compose.production.yml up -d --remove-orphans

wait_for_healthy_container() {
  local container_name="$1"
  local status

  for _ in $(seq 1 24); do
    status="$(docker inspect --format '{{.State.Health.Status}}' "$container_name" 2>/dev/null || true)"
    if [[ "$status" == "healthy" ]]; then
      return 0
    fi
    if [[ "$status" == "unhealthy" ]]; then
      return 1
    fi
    sleep 5
  done
  return 1
}

if wait_for_healthy_container meet-me-app && wait_for_healthy_container meet-me-nginx; then
  ln -sfn "$release_directory" /opt/meet-me/current
  exit 0
fi

docker logs --tail 100 meet-me-app >&2 || true
docker logs --tail 100 meet-me-nginx >&2 || true
echo "Rollback health check failed" >&2
exit 1
