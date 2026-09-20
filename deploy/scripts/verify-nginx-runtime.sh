#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_directory="$(mktemp -d)"
suffix="${RANDOM}"
network_name="meet-me-nginx-test-${suffix}"
app_container="meet-me-nginx-test-app-${suffix}"
nginx_container="meet-me-nginx-test-proxy-${suffix}"
passphrase='meet-me-nginx-test-passphrase'
certificate_subject='/CN=api.meet-me.co.kr'
nginx_configuration="$repository_root/deploy/nginx/nginx.conf"
tls_directory="$temporary_directory"
disable_docker_path_conversion=false
if [[ "${OSTYPE:-}" == msys* ]]; then
  certificate_subject='//CN=api.meet-me.co.kr'
  nginx_configuration="$(cygpath -w "$nginx_configuration")"
  tls_directory="$(cygpath -w "$tls_directory")"
  disable_docker_path_conversion=true
fi

cleanup() {
  docker container rm --force "$nginx_container" "$app_container" >/dev/null 2>&1 || true
  docker network rm "$network_name" >/dev/null 2>&1 || true
  rm -rf "$temporary_directory"
}
trap cleanup EXIT

openssl req \
  -x509 \
  -newkey rsa:2048 \
  -keyout "$temporary_directory/private-key.pem" \
  -out "$temporary_directory/fullchain.pem" \
  -days 1 \
  -passout "pass:${passphrase}" \
  -subj "$certificate_subject" >/dev/null 2>&1
printf '%s' "$passphrase" >"$temporary_directory/passphrase"
chmod 0755 "$temporary_directory"
chmod 0644 \
  "$temporary_directory/fullchain.pem" \
  "$temporary_directory/private-key.pem" \
  "$temporary_directory/passphrase"

docker network create "$network_name" >/dev/null
docker run --detach \
  --name "$app_container" \
  --network "$network_name" \
  --network-alias app \
  nginx:1.29-alpine >/dev/null

if [[ "$disable_docker_path_conversion" == true ]]; then
  export MSYS_NO_PATHCONV=1
fi
docker run --detach \
  --name "$nginx_container" \
  --network "$network_name" \
  --read-only \
  --security-opt no-new-privileges:true \
  --cap-drop ALL \
  --cap-add CHOWN \
  --cap-add SETGID \
  --cap-add SETUID \
  --tmpfs /var/cache/nginx:size=64m \
  --tmpfs /var/run:size=8m \
  --tmpfs /tmp:size=16m \
  --volume "$nginx_configuration:/etc/nginx/nginx.conf:ro" \
  --volume "$tls_directory:/etc/nginx/tls:ro" \
  nginx:1.29-alpine >/dev/null

for _ in $(seq 1 5); do
  if docker exec "$nginx_container" wget --quiet --spider http://127.0.0.1:8080/healthz 2>/dev/null; then
    exit 0
  fi
  if [[ "$(docker inspect --format '{{.State.Status}}' "$nginx_container")" != 'running' ]]; then
    docker logs "$nginx_container" >&2 || true
    exit 1
  fi
  sleep 1
done

docker exec "$nginx_container" netstat -lntp >&2 || true
docker logs "$nginx_container" >&2 || true
echo 'Production Nginx runtime health check timed out' >&2
exit 1
