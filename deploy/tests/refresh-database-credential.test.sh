#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/release/scripts" "$test_root/state"

export FAKE_STATE_DIR="$test_root/state"
export MEETME_CURRENT_LINK="$test_root/release"
export MEETME_DEPLOY_LOCK="$test_root/deploy.lock"
export MEETME_REFRESH_PENDING_FILE="$test_root/pending"
export PATH="$test_root/bin:$PATH"

cat >"$test_root/bin/aws" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == 'ssm' ]]; then
  printf 'arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:test\n'
else
  printf '{"password":"new-password"}\n'
fi
EOF

cat >"$test_root/bin/docker" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  exec) cat "$FAKE_STATE_DIR/app-password" ;;
  inspect) cat "$FAKE_STATE_DIR/health" ;;
  compose)
    printf 'compose\n' >>"$FAKE_STATE_DIR/calls"
    printf 'new-password' >"$FAKE_STATE_DIR/app-password"
    if [[ -f "$FAKE_STATE_DIR/fail-health" ]]; then
      printf 'unhealthy' >"$FAKE_STATE_DIR/health"
    else
      printf 'healthy' >"$FAKE_STATE_DIR/health"
    fi
    ;;
  *) exit 1 ;;
esac
EOF

cat >"$test_root/bin/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

cat >"$test_root/release/scripts/render-runtime-env.sh" <<'EOF'
#!/usr/bin/env bash
printf 'render\n' >>"$FAKE_STATE_DIR/calls"
printf 'DATABASE_PASSWORD=new-password\n' >"$1"
EOF

chmod +x "$test_root/bin/"* "$test_root/release/scripts/render-runtime-env.sh"
printf 'APP_IMAGE=example.invalid/app@sha256:abc\n' >"$test_root/release/.release.env"
touch "$test_root/release/compose.production.yml"

run_refresh() {
  bash "$repository_root/deploy/scripts/refresh-database-credential.sh"
}

printf 'DATABASE_PASSWORD=new-password\n' >"$test_root/release/.env.runtime"
printf 'new-password' >"$test_root/state/app-password"
printf 'healthy' >"$test_root/state/health"
run_refresh >/dev/null
test ! -f "$test_root/state/calls"

printf 'DATABASE_PASSWORD=old-password\n' >"$test_root/release/.env.runtime"
printf 'old-password' >"$test_root/state/app-password"
run_refresh >/dev/null
test "$(cat "$test_root/state/calls")" = $'render\ncompose'
test ! -f "$test_root/pending"

run_refresh >/dev/null
test "$(cat "$test_root/state/calls")" = $'render\ncompose'

printf 'unhealthy' >"$test_root/state/health"
if run_refresh >/dev/null 2>&1; then
  echo 'An unrelated unhealthy app should not be recreated' >&2
  exit 1
fi
test "$(cat "$test_root/state/calls")" = $'render\ncompose'

printf 'DATABASE_PASSWORD=old-password\n' >"$test_root/release/.env.runtime"
printf 'old-password' >"$test_root/state/app-password"
touch "$test_root/state/fail-health"
if run_refresh >/dev/null 2>&1; then
  echo 'A failed credential refresh should report failure' >&2
  exit 1
fi
test -f "$test_root/pending"
rm -f "$test_root/state/fail-health"
run_refresh >/dev/null
test ! -f "$test_root/pending"
test "$(cat "$test_root/state/calls" | wc -l)" -eq 6

echo 'Database credential refresh tests passed'
