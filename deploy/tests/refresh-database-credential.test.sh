#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -z "${V8_REFRESH_FIXTURE_ROOT:-}" ]]; then
  exec python3 "$repository_root/deploy/tests/refresh_guard_fixture.py" "${BASH_SOURCE[0]}"
fi
# The Python fixture installs the real copied guard in an isolated logical host.
# AWS/Docker/root ownership and /proc namespace are local stubs, not operational proof.
test_root="$V8_REFRESH_FIXTURE_ROOT"
run_refresh() {
  bash "$V8_REFRESH_FIXTURE_LAUNCHER" refresh
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
# Maintenance recovery requires an explicit approved roll-forward before refresh.
bash "$V8_REFRESH_FIXTURE_LAUNCHER" deploy "$V8_REFRESH_FIXTURE_RELEASE" "$V8_REFRESH_FIXTURE_IMAGE" >/dev/null
run_refresh >/dev/null
test ! -f "$test_root/pending"
test "$(cat "$test_root/state/calls" | wc -l)" -eq 6

echo 'Database credential refresh tests passed'
