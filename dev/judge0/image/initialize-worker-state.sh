#!/bin/bash
# Initialize only fixed worker-state directories, without changing existing journal ownership.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[[ $# -eq 0 && $EUID -eq 0 ]] || exit 1
root=/var/lib/codelobby-worker-state
uid=$(id -u judge0)
gid=$(id -g judge0)
for parent in /var /var/lib; do
  [[ -d "$parent" && ! -L "$parent" && $(stat -c %u "$parent") == 0 ]] || exit 1
  mode=$(stat -c %a "$parent")
  (( (8#$mode & 022) == 0 )) || exit 1
done
if [[ ! -e "$root" && ! -L "$root" ]]; then install -d -m 0711 "$root"; fi
[[ -d "$root" && ! -L "$root" && $(stat -c %u "$root") == 0 ]] || exit 1
mode=$(stat -c %a "$root")
(( (8#$mode & 022) == 0 )) || exit 1
chmod 0711 "$root"
# The parent is root-owned and not writable by the worker. Initialization is serialized.
[[ ! -L "$root/.initialize.lock" ]] || exit 1
exec 9>"$root/.initialize.lock"
flock -x 9
for name in slots jobs; do
  directory=$root/$name
  if [[ -e "$directory" || -L "$directory" ]]; then
    [[ -d "$directory" && ! -L "$directory" && $(stat -c %u "$directory") == "$uid" && $(stat -c %g "$directory") == "$gid" && $(stat -c %a "$directory") == 700 ]] || exit 1
  else
    install -d -m 0700 -o "$uid" -g "$gid" "$directory"
  fi
done
