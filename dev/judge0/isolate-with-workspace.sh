#!/bin/bash
# Preserve Isolate options and force /dev/shm into the same per-box quota.
set -euo pipefail
real=/usr/local/bin/isolate
id=0
run=0
previous=
for arg in "$@"; do
  if [[ "$previous" == -b || "$previous" == --box-id ]]; then id=$arg; fi
  case "$arg" in
    --box-id=*) id=${arg#*=} ;;
    --run) run=1 ;;
    --) break ;;
  esac
  previous=$arg
done
if ((run == 0)); then exec "$real" "$@"; fi
[[ "$id" =~ ^(0|[1-9][0-9]{0,2})$ ]] || { echo "Unsupported bounded workspace ID" >&2; exit 2; }
options=()
while (($#)); do
  if [[ "$1" == -- ]]; then
    shift
    exec "$real" "${options[@]}" --dir="/dev/shm=/var/local/lib/codelobby-workspaces/$id/shm:rw" -- "$@"
  fi
  options+=("$1")
  shift
done
echo "Expected Isolate command separator" >&2
exit 2
