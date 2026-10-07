#!/usr/bin/env bash
set -euo pipefail
set +x

launcher=/opt/meet-me/guard/host-release-guard.sh
if [[ ! -x "$launcher" || -L "$launcher" ]]; then
  echo 'Installed release guard is required; operating setup has not been completed' >&2
  exit 1
fi
exec "$launcher" rollback "$@"
