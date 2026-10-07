#!/usr/bin/env bash
set -euo pipefail
set +x

# Sourced by the explicit installer; lifecycle dispatch only runs when executed.
guard_fail() { echo "Release guard: $*" >&2; exit 1; }

guard_root() { [[ "$(id -u)" == 0 ]] || guard_fail 'root execution is required'; }

guard_safe() {
  local path="$1" mode
  [[ -e "$path" && ! -L "$path" && -r "$path" ]] || guard_fail 'missing or unsafe artifact'
  [[ "$(stat -c '%u' "$path")" == 0 ]] || guard_fail 'artifact must be root owned'
  mode="$(stat -c '%a' "$path")"
  (( (8#$mode & 8#022) == 0 )) || guard_fail 'artifact is writable by group or world'
}

guard_file() { guard_safe "$1"; [[ -f "$1" ]] || guard_fail 'regular artifact required'; }
guard_directory() { guard_safe "$1"; [[ -d "$1" && -x "$1" ]] || guard_fail 'directory required'; }
guard_sha() { sha256sum "$1" | cut -d ' ' -f 1; }

guard_atomic() {
  local target="$1" mode="${2:-0600}" temporary
  temporary="$(mktemp "$(dirname "$target")/.guard-write.XXXXXX")" || guard_fail 'state temporary file failed'
  if ! cat >"$temporary" || ! chmod "$mode" "$temporary" || ! sync -f "$temporary"; then
    rm -f "$temporary"
    guard_fail 'state persistence failed'
  fi
  if ! mv -T "$temporary" "$target" || ! sync -f "$(dirname "$target")"; then
    guard_fail 'state replacement failed; reviewed recovery is required'
  fi
}

guard_lock() {
  local lock_file="${MEETME_DEPLOY_LOCK:-/var/lock/meet-me-release.lock}"
  if [[ -e "$lock_file" || -L "$lock_file" ]]; then guard_file "$lock_file"; fi
  exec 9>"$lock_file"
  flock -w 300 9 || guard_fail 'exclusive lifecycle lock unavailable'
}

guard_prerequisites() {
  guard_file "$1"
  jq -e --arg root "$2" '
    .version == 1 and .host_root == $root and
    .automation_paused == true and .legacy_invocations_drained == true and
    .restart_policy_reviewed == true and
    (.operator_evidence_sha256 | type == "string" and test("^[0-9a-fA-F]{64}$"))
  ' "$1" >/dev/null || guard_fail 'operator prerequisite record is invalid'
}

guard_image_format() {
  [[ "$1" =~ ^[a-zA-Z0-9][a-zA-Z0-9._:/-]*@sha256:[0-9a-f]{64}$ && "${1%@*}" == */* ]]
}

guard_policy() {
  local policy="$1" requested="${2:-}" image contract evidence extra line without_tabs count=0 found=0
  local -A seen=()
  guard_file "$policy"
  while IFS= read -r line || [[ -n "$line" ]]; do
    without_tabs="${line//$'\t'/}"
    (( ${#line} - ${#without_tabs} == 2 )) || guard_fail 'policy requires exactly three TAB-separated fields'
    IFS=$'\t' read -r image contract evidence extra <<<"$line"
    guard_image_format "$image" || guard_fail 'policy contains an invalid image digest'
    [[ "$contract" == input_revision_v8 && "$evidence" =~ ^[0-9a-fA-F]{64}$ && -z "$extra" ]] ||
      guard_fail 'policy contract or review provenance is invalid'
    [[ -z "${seen[$image]+present}" ]] || guard_fail 'duplicate image approval'
    seen["$image"]=1
    count=$((count + 1))
    if [[ "$image" == "$requested" ]]; then found=1; fi
  done <"$policy"
  (( count > 0 )) || guard_fail 'empty image policy'
  if [[ -n "$requested" ]]; then (( found == 1 )) || guard_fail 'image digest is not approved'; fi
}

guard_release() {
  local root="$1" requested="$2" resolved
  [[ ! -L "$requested" && -d "$requested" ]] || guard_fail 'release directory is invalid'
  resolved="$(realpath -e "$requested")" || guard_fail 'release resolution failed'
  [[ "$resolved" == "$root"/releases/* && "${resolved#"$root"/releases/}" != */* ]] ||
    guard_fail 'release escapes managed inventory'
  guard_directory "$resolved"
  guard_directory "$resolved/scripts"
  printf '%s\n' "$resolved"
}

guard_manifest() {
  local root="$1" manifest evidence name expected release archive device inode sha
  manifest="$root/state/installation-manifest.json"
  guard_file "$manifest"
  jq -e --arg root "$root" '
    .version == 1 and .host_root == $root and
    (.launcher_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.override_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.wrappers | type == "object" and keys == ["deploy-release.sh","refresh-database-credential.sh","rollback-release.sh"]) and
    (.retired_scripts | type == "array") and
    all(.retired_scripts[];
      (.path | type == "string") and (.archive | type == "string") and
      (.device | type == "string" and test("^[0-9]+$")) and
      (.inode | type == "string" and test("^[0-9]+$")) and
      (.sha256 | type == "string" and test("^[0-9a-f]{64}$")))
  ' "$manifest" >/dev/null || guard_fail 'installation manifest is corrupt'
  guard_file "$root/guard/host-release-guard.sh"
  [[ "$(guard_sha "$root/guard/host-release-guard.sh")" == "$(jq -r '.launcher_sha256' "$manifest")" ]] ||
    guard_fail 'installed launcher bytes do not match attestation'
  guard_file "$root/guard/compose.guard.yml"
  [[ "$(guard_sha "$root/guard/compose.guard.yml")" == "$(jq -r '.override_sha256' "$manifest")" ]] ||
    guard_fail 'approved image and restart override bytes do not match attestation'
  guard_prerequisites "$root/state/prerequisites.json" "$root"
  evidence="$(jq -r '.operator_evidence_sha256' "$root/state/prerequisites.json")"
  [[ "$evidence" == "$(jq -r '.operator_evidence_sha256' "$manifest")" ]] || guard_fail 'prerequisite provenance mismatch'
  shopt -s nullglob
  for release in "$root"/releases/*; do
    guard_release "$root" "$release" >/dev/null
    for name in deploy-release.sh rollback-release.sh refresh-database-credential.sh; do
      guard_file "$release/scripts/$name"
      expected="$(jq -er --arg name "$name" '.wrappers[$name] | select(test("^[0-9a-f]{64}$"))' "$manifest")" ||
        guard_fail 'wrapper attestation missing'
      [[ "$(guard_sha "$release/scripts/$name")" == "$expected" ]] || guard_fail 'unguarded stored release entrypoint'
    done
  done
  while IFS=$'\t' read -r archive device inode sha; do
    [[ "$archive" == "$root"/guard/legacy-archive/* ]] || guard_fail 'archive escapes installed guard'
    guard_file "$archive"
    [[ "$(stat -c '%d' "$archive")" == "$device" && "$(stat -c '%i' "$archive")" == "$inode" && "$(guard_sha "$archive")" == "$sha" ]] ||
      guard_fail 'retired inode pin is invalid'
  done < <(jq -r '.retired_scripts[] | [.archive,.device,.inode,.sha256] | @tsv' "$manifest")
}

guard_retired_processes() {
  local inventory="$1" proc_directory fd identity pid device inode
  local -A retired=()
  while IFS=$'\t' read -r device inode; do retired["$device:$inode"]=1; done < <(
    jq -r '.retired_scripts[] | [.device,.inode] | @tsv' "$inventory")
  shopt -s nullglob
  for proc_directory in /proc/[0-9]*; do
    pid="${proc_directory##*/}"
    [[ "$pid" != "$$" ]] || continue
    [[ -d "$proc_directory" ]] || continue
    if [[ ! -r "$proc_directory/fd" || ! -x "$proc_directory/fd" ]]; then
      [[ ! -d "$proc_directory" ]] && continue
      guard_fail 'live process FD metadata is unobservable; drain and review'
    fi
    for fd in "$proc_directory"/fd/*; do
      if ! identity="$(stat -Lc '%d:%i' "$fd" 2>/dev/null)"; then
        [[ ! -d "$proc_directory" ]] && continue
        # A live FD is a symlink. -e follows its target and can report false
        # when access is denied, so it cannot establish descriptor absence.
        if [[ ! -L "$fd" && ! -e "$fd" ]]; then
          [[ -r "$proc_directory/fd" && -x "$proc_directory/fd" ]] ||
            guard_fail 'live FD directory became unobservable'
          continue
        fi
        guard_fail 'live FD metadata is unobservable'
      fi
      [[ -z "${retired[$identity]+present}" ]] || guard_fail 'opened legacy entrypoint detected; cancel and drain it'
    done
  done
}

guard_phase() {
  local root="$1" phase
  guard_file "$root/state/phase"
  phase="$(cat "$root/state/phase")"
  case "$phase" in
    PRE_V8)
      cmp -s "$root/state/phase" <(printf 'PRE_V8\n') || guard_fail 'phase bytes are invalid'
      [[ ! -e "$root/state/minimum-contract" && ! -L "$root/state/minimum-contract" ]] || guard_fail 'PRE_V8 contradicts sticky marker'
      ;;
    V8_STARTED|READY)
      cmp -s "$root/state/phase" <(printf '%s\n' "$phase") || guard_fail 'phase bytes are invalid'
      guard_file "$root/state/minimum-contract"
      cmp -s "$root/state/minimum-contract" <(printf 'input_revision_v8\n') || guard_fail 'sticky minimum contract is invalid'
      ;;
    *) guard_fail 'unknown deployment phase' ;;
  esac
  printf '%s\n' "$phase"
}

guard_container_present() {
  if docker container inspect meet-me-app >/dev/null 2>&1; then return 0; fi
  docker info >/dev/null 2>&1 || guard_fail 'Docker engine cannot establish writer absence'
  local names
  names="$(docker ps -a --format '{{.Names}}')" || guard_fail 'container inventory unavailable'
  [[ "$names" != *meet-me-app* ]] || guard_fail 'app inspection failed despite inventory presence'
  return 1
}

guard_quiesce() {
  if guard_container_present; then
    docker update --restart=no meet-me-app >/dev/null || guard_fail 'app restart disabling failed'
    docker stop --time 30 meet-me-app >/dev/null || guard_fail 'writer stop failed'
    [[ "$(docker inspect --format '{{.State.Running}}' meet-me-app)" == false ]] || guard_fail 'app writers are still running'
  fi
}

guard_running_image() {
  if guard_container_present; then
    [[ "$(docker inspect --format '{{.Config.Image}}' meet-me-app)" == "$1" ]] ||
      guard_fail 'running image does not match approved current metadata'
  fi
}

guard_health() {
  local container="$1" status
  for _ in $(seq 1 24); do
    status="$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    [[ "$status" != healthy ]] || return 0
    [[ "$status" != unhealthy ]] || return 1
    sleep 5
  done
  return 1
}

guard_current() {
  local root="$1" current
  [[ -L "$root/current" ]] || guard_fail 'current release link missing'
  current="$(readlink -f "$root/current")" || guard_fail 'current release cannot resolve'
  guard_release "$root" "$current"
}

guard_stored_image() {
  guard_file "$1/.release.env"
  local image
  image="$(sed -n 's/^APP_IMAGE=//p' "$1/.release.env")"
  guard_image_format "$image" || guard_fail 'release metadata digest is invalid'
  printf '%s\n' "$image"
}

guard_compose() {
  local release="$1" image="$2"
  shift 2
  # Last override forces the reviewed digest and disables Docker auto-restart
  # even when a stored old Compose file hardcodes an image/restart policy.
  local override="$guard_host_root/guard/compose.guard.yml"
  guard_file "$override"
  [[ "$(guard_sha "$override")" == "$(jq -r '.override_sha256' "$guard_host_root/state/installation-manifest.json")" ]] ||
    guard_fail 'Compose override attestation mismatch'
  if ! (cd "$release" && APP_IMAGE="$image" docker compose -f compose.production.yml -f "$override" "$@"); then
    guard_fail 'approved Compose transition failed'
  fi
  docker update --restart=no meet-me-app >/dev/null || guard_fail 'app restart policy verification failed'
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' meet-me-app)" == no ]] || guard_fail 'app restart remains unguarded'
}

guard_begin_transition() {
  printf 'V8_STARTED\n' | guard_atomic "$guard_host_root/state/phase"
  if [[ ! -e "$guard_host_root/state/minimum-contract" ]]; then
    printf 'input_revision_v8\n' | guard_atomic "$guard_host_root/state/minimum-contract"
  fi
  guard_transition_started=1
}

guard_finish() {
  local release="$1" image="$2" temporary
  guard_running_image "$image"
  guard_health meet-me-app && guard_health meet-me-nginx || guard_fail 'approved release health failed; maintenance retained'
  temporary="$(mktemp -d "$guard_host_root/.current.XXXXXX")"
  ln -s "$release" "$temporary/current" || guard_fail 'current link preparation failed'
  mv -T "$temporary/current" "$guard_host_root/current" || guard_fail 'current release replacement failed'
  rmdir "$temporary"
  sync -f "$guard_host_root" || guard_fail 'current release persistence failed'
  printf 'READY\n' | guard_atomic "$guard_host_root/state/phase"
  guard_transition_started=0
}

guard_exit() {
  local result="$?"
  trap - EXIT
  if (( result != 0 && guard_transition_started == 1 )); then
    # Never execute an earlier image or log user/provider payloads on failure.
    guard_quiesce || true
  fi
  exit "$result"
}

guard_refresh() {
  local release="$1" image="$2" secret_arn current_password runtime_password app_password app_health
  local pending_file="${MEETME_REFRESH_PENDING_FILE:-/run/meet-me-credential-refresh.pending}"
  guard_file "$release/.env.runtime"
  guard_running_image "$image"
  secret_arn="$(aws ssm get-parameter --region "$guard_region" --name "${MEETME_PARAMETER_ROOT:-/meet-me/production}/config/database-secret-arn" --query 'Parameter.Value' --output text)"
  current_password="$(aws secretsmanager get-secret-value --region "$guard_region" --secret-id "$secret_arn" --version-stage AWSCURRENT --query 'SecretString' --output text | jq -er '.password')"
  runtime_password="$(sed -n 's/^DATABASE_PASSWORD=//p' "$release/.env.runtime")"
  app_password="$(docker exec meet-me-app sh -c 'printf %s "$DATABASE_PASSWORD"' 2>/dev/null || true)"
  app_health="$(docker inspect --format '{{.State.Health.Status}}' meet-me-app 2>/dev/null || true)"
  if [[ "$current_password" == "$runtime_password" && "$current_password" == "$app_password" && "$app_health" == healthy ]]; then
    rm -f "$pending_file"
    echo 'Database credential is current and the approved app is healthy'
    return
  fi
  if [[ "$current_password" == "$runtime_password" && "$current_password" == "$app_password" && ! -f "$pending_file" ]]; then
    guard_fail 'credential is current but app is unhealthy'
  fi
  [[ ! -L "$pending_file" ]] || guard_fail 'pending credential artifact is unsafe'
  : >"$pending_file"
  chmod 0600 "$pending_file"
  guard_begin_transition
  guard_quiesce
  guard_file "$release/scripts/render-runtime-env.sh"
  "$release/scripts/render-runtime-env.sh" "$release/.env.runtime"
  chmod 0600 "$release/.env.runtime"
  guard_compose "$release" "$image" up -d --no-deps --force-recreate app
  guard_finish "$release" "$image"
  rm -f "$pending_file"
  echo 'Database credential refreshed and the approved app is healthy'
}

guard_main() {
  guard_root
  umask 077
  local action="${1:-}" release image phase current_image
  case "$action:$#" in deploy:3|rollback:2|refresh:1|restart:1) ;; *) guard_fail 'invalid lifecycle arguments' ;; esac
  guard_host_root="$(cd "$(dirname "$(realpath -e "${BASH_SOURCE[0]}")")/.." && pwd -P)"
  [[ "$(realpath -e "${BASH_SOURCE[0]}")" == "$guard_host_root/guard/host-release-guard.sh" ]] || guard_fail 'use the fixed installed launcher'
  guard_directory "$guard_host_root"
  guard_directory "$guard_host_root/guard"
  guard_directory "$guard_host_root/guard/legacy-archive"
  guard_directory "$guard_host_root/state"
  guard_directory "$guard_host_root/releases"
  guard_lock
  guard_manifest "$guard_host_root"
  guard_policy "$guard_host_root/state/compatible-images.tsv"
  phase="$(guard_phase "$guard_host_root")"
  guard_retired_processes "$guard_host_root/state/installation-manifest.json"
  if [[ "$action" != deploy && "$phase" != READY ]]; then guard_fail 'maintenance phase permits approved deploy only'; fi
  case "$action" in
    deploy) release="$(guard_release "$guard_host_root" "$2")"; image="$3" ;;
    rollback) release="$(guard_release "$guard_host_root" "$2")"; image="$(guard_stored_image "$release")" ;;
    refresh|restart) release="$(guard_current "$guard_host_root")"; image="$(guard_stored_image "$release")" ;;
  esac
  guard_image_format "$image" || guard_fail 'immutable full image digest required'
  guard_policy "$guard_host_root/state/compatible-images.tsv" "$image"
  if [[ "$action" != deploy ]]; then
    current_image="$(guard_stored_image "$(guard_current "$guard_host_root")")"
    guard_policy "$guard_host_root/state/compatible-images.tsv" "$current_image"
  fi
  guard_region="${AWS_REGION:-ap-northeast-2}"
  export COMPOSE_PROJECT_NAME="${MEETME_COMPOSE_PROJECT_NAME:-meet-me-production}"
  guard_transition_started=0
  trap guard_exit EXIT
  if [[ "$action" == refresh ]]; then guard_refresh "$release" "$image"; return; fi
  if [[ "$action" != deploy ]]; then guard_running_image "$current_image"; fi
  guard_quiesce
  guard_begin_transition
  if [[ "$action" == deploy ]]; then
    guard_file "$release/scripts/render-runtime-env.sh"
    guard_file "$release/scripts/export-certificate.sh"
    "$release/scripts/render-runtime-env.sh" "$release/.env.runtime"
    chmod 0600 "$release/.env.runtime"
    "$release/scripts/export-certificate.sh" "$guard_host_root/tls"
    aws ecr get-login-password --region "$guard_region" | docker login --username AWS --password-stdin "${image%%/*}" >/dev/null
    docker pull "$image"
    docker run --rm --network host --env-file "$release/.env.runtime" "$image" migrate
    printf 'APP_IMAGE=%s\n' "$image" | guard_atomic "$release/.release.env"
  else
    guard_file "$release/.env.runtime"
  fi
  guard_file "$release/compose.production.yml"
  guard_compose "$release" "$image" up -d --remove-orphans
  guard_finish "$release" "$image"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then guard_main "$@"; fi
