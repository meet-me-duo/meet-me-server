#!/usr/bin/env bash
set -euo pipefail

release_directory="${1:?previous release directory is required}"

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

for _ in $(seq 1 24); do
  status="$(docker inspect --format '{{.State.Health.Status}}' meet-me-app 2>/dev/null || true)"
  if [[ "$status" == "healthy" ]]; then
    ln -sfn "$release_directory" /opt/meet-me/current
    exit 0
  fi
  if [[ "$status" == "unhealthy" ]]; then
    break
  fi
  sleep 5
done

docker logs --tail 100 meet-me-app >&2 || true
echo "Rollback health check failed" >&2
exit 1

