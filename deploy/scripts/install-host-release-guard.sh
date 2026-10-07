#!/usr/bin/env bash
set -euo pipefail
set +x

[[ "$#" == 3 ]] || { echo 'Usage: install-host-release-guard.sh <host-root> <trusted-release> <prerequisites.json>' >&2; exit 1; }
[[ "$(id -u)" == 0 ]] || { echo 'Guard installation requires root' >&2; exit 1; }
host_root="$(realpath -e "$1")"
trusted_release="$(realpath -e "$2")"
prerequisite_record="$3"
# Validate the trusted code path before sourcing any shell code from it.
[[ ! -L "$2" && "$trusted_release" == "$host_root"/releases/* && "${trusted_release#"$host_root"/releases/}" != */* ]] || {
  echo 'Trusted release escapes managed inventory' >&2; exit 1;
}
for bootstrap_path in "$host_root" "$host_root/releases" "$trusted_release" "$trusted_release/scripts" "$trusted_release/scripts/host-release-guard.sh"; do
  [[ -e "$bootstrap_path" && ! -L "$bootstrap_path" && -r "$bootstrap_path" && "$(stat -c '%u' "$bootstrap_path")" == 0 ]] || {
    echo 'Unsafe installer code or host path' >&2; exit 1;
  }
  bootstrap_mode="$(stat -c '%a' "$bootstrap_path")"
  (( (8#$bootstrap_mode & 8#022) == 0 )) || { echo 'Installer path is writable by group or world' >&2; exit 1; }
done
[[ -f "$trusted_release/scripts/host-release-guard.sh" ]] || { echo 'Guard source must be a regular file' >&2; exit 1; }
source "$trusted_release/scripts/host-release-guard.sh"
guard_root
umask 077
guard_directory "$host_root"
guard_directory "$host_root/releases"
trusted_release="$(guard_release "$host_root" "$trusted_release")"
guard_file "$trusted_release/scripts/host-release-guard.sh"
guard_file "$trusted_release/compose.guard.yml"
guard_prerequisites "$prerequisite_record" "$host_root"
guard_directory "$host_root/state"
guard_policy "$host_root/state/compatible-images.tsv"
guard_lock
guard_prerequisites "$prerequisite_record" "$host_root"
guard_policy "$host_root/state/compatible-images.tsv"

for directory in "$host_root/guard" "$host_root/guard/legacy-archive"; do
  if [[ ! -e "$directory" ]]; then mkdir -m 0700 "$directory"; fi
  guard_directory "$directory"
done
manifest="$host_root/state/installation-manifest.json"
retired='[]'
previous_phase=''
if [[ -e "$manifest" || -L "$manifest" ]]; then
  guard_file "$manifest"
  jq -e --arg root "$host_root" '
    .version == 1 and .host_root == $root and
    (.launcher_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.override_sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
    (.wrappers | type == "object" and keys == ["deploy-release.sh","refresh-database-credential.sh","rollback-release.sh"]) and
    (.operator_evidence_sha256 | type == "string" and test("^[0-9a-fA-F]{64}$")) and
    (.retired_scripts | type == "array") and all(.retired_scripts[];
      (.path | type == "string") and (.archive | type == "string") and
      (.device | type == "string" and test("^[0-9]+$")) and
      (.inode | type == "string" and test("^[0-9]+$")) and
      (.sha256 | type == "string" and test("^[0-9a-f]{64}$")))
  ' "$manifest" >/dev/null || guard_fail 'interrupted installation manifest is corrupt'
  guard_prerequisites "$host_root/state/prerequisites.json" "$host_root"
  [[ "$(jq -r '.operator_evidence_sha256' "$host_root/state/prerequisites.json")" == "$(jq -r '.operator_evidence_sha256' "$manifest")" ]] || guard_fail 'existing prerequisite attestation mismatch'
  while IFS=$'\t' read -r pinned device inode sha; do
    [[ "$pinned" == "$host_root"/guard/legacy-archive/* ]] || guard_fail 'existing pin escapes archive'
    guard_file "$pinned"
    [[ "$(stat -c '%d:%i' "$pinned")" == "$device:$inode" && "$(guard_sha "$pinned")" == "$sha" ]] || guard_fail 'existing retired inode proof is corrupt'
  done < <(jq -r '.retired_scripts[] | [.archive,.device,.inode,.sha256] | @tsv' "$manifest")
  retired="$(jq -c '.retired_scripts' "$manifest")"
  # Recovery may complete an interrupted pre-marker installation, never reset V8.
  if [[ -e "$host_root/state/phase" ]]; then
    previous_phase="$(guard_phase "$host_root")"
  elif [[ -e "$host_root/state/minimum-contract" || -L "$host_root/state/minimum-contract" ]]; then
    guard_fail 'marker without phase requires reviewed recovery'
  fi
  [[ "$(jq -r '.launcher_sha256' "$manifest")" == "$(guard_sha "$trusted_release/scripts/host-release-guard.sh")" ]] || guard_fail 'guard upgrade needs separate reviewed installation'
  guard_file "$host_root/guard/host-release-guard.sh"
  [[ "$(guard_sha "$host_root/guard/host-release-guard.sh")" == "$(jq -r '.launcher_sha256' "$manifest")" ]] || guard_fail 'do not replace inconsistent installed guard bytes'
  guard_file "$host_root/guard/compose.guard.yml"
  [[ "$(guard_sha "$host_root/guard/compose.guard.yml")" == "$(jq -r '.override_sha256' "$manifest")" && "$(guard_sha "$trusted_release/compose.guard.yml")" == "$(jq -r '.override_sha256' "$manifest")" ]] || guard_fail 'do not replace inconsistent installed override bytes'
elif [[ -e "$host_root/state/phase" || -e "$host_root/state/minimum-contract" || -L "$host_root/state/phase" || -L "$host_root/state/minimum-contract" ]]; then
  guard_fail 'state without installation manifest requires reviewed recovery'
elif [[ -e "$host_root/guard/host-release-guard.sh" || -L "$host_root/guard/host-release-guard.sh" || -e "$host_root/guard/compose.guard.yml" || -L "$host_root/guard/compose.guard.yml" ]]; then
  guard_fail 'unattested installed launcher or override requires reviewed recovery before handoff'
fi

wrappers='{}'
for name in deploy-release.sh rollback-release.sh refresh-database-credential.sh; do
  guard_file "$trusted_release/scripts/$name"
  wrappers="$(jq -c --arg name "$name" --arg sha "$(guard_sha "$trusted_release/scripts/$name")" '. + {($name):$sha}' <<<"$wrappers")"
done
shopt -s nullglob
for release in "$host_root"/releases/*; do
  guard_release "$host_root" "$release" >/dev/null
  for name in deploy-release.sh rollback-release.sh refresh-database-credential.sh; do
    original="$release/scripts/$name"
    guard_file "$original"
    if [[ "$(guard_sha "$original")" == "$(jq -r --arg name "$name" '.[$name]' <<<"$wrappers")" ]]; then continue; fi
    device="$(stat -c '%d' "$original")"
    inode="$(stat -c '%i' "$original")"
    sha="$(guard_sha "$original")"
    archive="$host_root/guard/legacy-archive/$device-$inode-$sha"
    if [[ ! -e "$archive" ]]; then ln "$original" "$archive" || guard_fail 'legacy inode hard-link pin failed'; fi
    guard_file "$archive"
    [[ "$(stat -c '%d:%i' "$archive")" == "$device:$inode" && "$(guard_sha "$archive")" == "$sha" ]] || guard_fail 'legacy pin identity mismatch'
    retired="$(jq -c --arg path "$original" --arg archive "$archive" --arg device "$device" --arg inode "$inode" --arg sha "$sha" '. + [{path:$path,archive:$archive,device:$device,inode:$inode,sha256:$sha}] | unique_by([.device,.inode])' <<<"$retired")"
  done
done

evidence="$(jq -r '.operator_evidence_sha256' "$prerequisite_record")"
candidate="$(jq -nc --arg root "$host_root" --arg sha "$(guard_sha "$trusted_release/scripts/host-release-guard.sh")" --arg override "$(guard_sha "$trusted_release/compose.guard.yml")" --argjson wrappers "$wrappers" --argjson retired "$retired" --arg evidence "$evidence" '{version:1,host_root:$root,launcher_sha256:$sha,override_sha256:$override,wrappers:$wrappers,retired_scripts:$retired,operator_evidence_sha256:$evidence}')"
temporary_inventory="$(mktemp "$host_root/state/.installation-inventory.XXXXXX")"
printf '%s\n' "$candidate" >"$temporary_inventory"
trap 'rm -f "$temporary_inventory"' EXIT
guard_retired_processes "$temporary_inventory"

# A manifest before wrapper replacement preserves pins if installation stops
# midway. Missing phase prevents all lifecycle actions until explicit retry.
if [[ ! -e "$host_root/guard/host-release-guard.sh" ]]; then
  guard_atomic "$host_root/guard/host-release-guard.sh" 0750 <"$trusted_release/scripts/host-release-guard.sh"
fi
guard_atomic "$host_root/guard/compose.guard.yml" <"$trusted_release/compose.guard.yml"
guard_atomic "$host_root/state/prerequisites.json" <"$prerequisite_record"
printf '%s\n' "$candidate" | guard_atomic "$manifest"
for release in "$host_root"/releases/*; do
  for name in deploy-release.sh rollback-release.sh refresh-database-credential.sh; do
    guard_atomic "$release/scripts/$name" 0750 <"$trusted_release/scripts/$name"
  done
done
guard_file "$trusted_release/meet-me-guarded-restart.service"
guard_atomic "$host_root/guard/meet-me-guarded-restart.service" 0644 <"$trusted_release/meet-me-guarded-restart.service"
guard_manifest "$host_root"
guard_retired_processes "$manifest"
guard_quiesce
if [[ -z "$previous_phase" ]]; then printf 'PRE_V8\n' | guard_atomic "$host_root/state/phase"; fi
echo 'Guard staged and managed app stopped; operating setup and first approved deploy remain separate'
