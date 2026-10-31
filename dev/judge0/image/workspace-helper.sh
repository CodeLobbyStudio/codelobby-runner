#!/bin/bash
# Root-only, fixed-path workspace mounts for the pinned experimental worker.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
fail() { echo "Bounded workspace operation failed: $1" >&2; exit 1; }
[[ $# -ge 2 ]] || fail arguments
operation=$1
id=$2
[[ "$id" =~ ^(0|[1-9][0-9]{0,2})$ ]] || fail box-id
case "$operation" in
  prepare)
    [[ $# -eq 4 ]] || fail arguments
    size=$3
    inodes=$4
    [[ "$size" =~ ^[1-9][0-9]{0,2}$ ]] && ((size >= 4 && size <= 256)) || fail size
    [[ "$inodes" =~ ^[1-9][0-9]{0,4}$ ]] && ((inodes >= 128 && inodes <= 16384)) || fail inodes
    ;;
  cleanup) [[ $# -eq 2 ]] || fail arguments ;;
  *) fail operation ;;
esac
[[ $EUID -eq 0 ]] || fail privileges
base=/var/local/lib/codelobby-workspaces
boxbase=/var/local/lib/isolate
workspace=$base/$id
target=$boxbase/$id
for path in /var /var/local /var/local/lib "$boxbase"; do
  [[ -d "$path" && ! -L "$path" && $(stat -c %u "$path") == 0 ]] || fail parent
  mode=$(stat -c %a "$path")
  (( (8#$mode & 022) == 0 )) || fail parent-permissions
done
if [[ ! -e "$base" ]]; then mkdir -m 0700 "$base"; fi
[[ -d "$base" && ! -L "$base" && $(stat -c %u "$base") == 0 ]] || fail workspace-parent
mode=$(stat -c %a "$base")
(( (8#$mode & 066) == 0 )) || fail workspace-parent-permissions
# Isolate checks source traversal as the invoking worker UID. No listing/write.
chmod 0711 "$base"
mkdir -p -m 0700 /run/codelobby-workspace-locks
exec 9>"/run/codelobby-workspace-locks/$id"
flock -n 9 || fail busy
[[ ! -L "$workspace" && ! -L "$target" && ! -L "$target/box" && ! -L "$target/tmp" ]] || fail symlink

release_workspace() {
  if ! mountpoint -q "$workspace"; then
    # Never touch unknown mounts or recursively remove ordinary directories.
    mountpoint -q "$target/box" && return 1
    mountpoint -q "$target/tmp" && return 1
    if [[ -d "$workspace" ]]; then rmdir "$workspace"; fi
    return 0
  fi
  [[ $(findmnt -n -M "$workspace" -o FSTYPE) == tmpfs ]] || return 1
  [[ $(findmnt -n -M "$workspace" -o SOURCE) == "codelobby-workspace-$id" ]] || return 1
  for path in "$target/box" "$target/tmp"; do
    if mountpoint -q "$path"; then
      [[ $(stat -c %d "$path") == $(stat -c %d "$workspace") ]] || return 1
      timeout 10 umount "$path" || return 1
    fi
  done
  timeout 10 umount "$workspace" || return 1
  rmdir "$workspace"
}
if [[ "$operation" == cleanup ]]; then
  release_workspace || fail cleanup
  exit 0
fi
[[ -d "$target" && $(stat -c %u "$target") == 0 && -d "$target/box" ]] || fail uninitialized-box
mountpoint -q "$target/box" && fail existing-mount
mountpoint -q "$target/tmp" && fail existing-mount
mountpoint -q "$workspace" && fail existing-workspace
# An empty mountpoint can remain after container restart. Never delete its contents.
if [[ -e "$workspace" ]]; then rmdir "$workspace" || fail nonempty-workspace; fi
mkdir -m 0700 "$workspace"
owner=$(stat -c '%u:%g' "$target/box")
trap 'release_workspace || echo "Bounded workspace rollback failed" >&2' ERR
mount -t tmpfs -o "size=${size}m,nr_inodes=$inodes,mode=0711,nosuid,nodev" "codelobby-workspace-$id" "$workspace"
mount --make-private "$workspace"
mkdir "$workspace/box" "$workspace/tmp" "$workspace/shm"
chown "$owner" "$workspace/box"
chmod 0700 "$workspace/box"
chmod 1777 "$workspace/tmp" "$workspace/shm"
if [[ ! -d "$target/tmp" ]]; then mkdir -m 1777 "$target/tmp"; fi
mount --bind "$workspace/box" "$target/box"
mount --bind "$workspace/tmp" "$target/tmp"
trap - ERR
