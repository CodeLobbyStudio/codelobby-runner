#!/bin/bash
# Preserve execution limits. Give only compiler invocations their own budget.
set -euo pipefail
real=/usr/local/bin/isolate
id=0
run=0
cg=0
previous=
for arg in "$@"; do
  if [[ "$previous" == -b || "$previous" == --box-id ]]; then id=$arg; fi
  case "$arg" in
    --box-id=*) id=${arg#*=} ;;
    --run) run=1 ;;
    --cg) cg=1 ;;
    --) break ;;
  esac
  previous=$arg
done
if ((run == 0)); then exec "$real" "$@"; fi
[[ "$id" =~ ^(0|[1-9][0-9]{0,2})$ ]] || { echo "Unsupported bounded workspace ID" >&2; exit 2; }
phase=${CODELOBBY_ISOLATE_PHASE:-execution}
[[ "$phase" == execution || "$phase" == compile ]] || { echo "Unknown Isolate phase" >&2; exit 2; }
if [[ "$phase" == compile && "$cg" != 1 ]]; then
  echo "Compilation requires aggregate cgroup accounting" >&2
  exit 2
fi
# Capture the same memory value enforced by Isolate. It is not read from submission environment.
memory_kib=
previous=
for arg in "$@"; do
  if [[ "$previous" == --cg-mem || "$previous" == -m || "$previous" == --mem ]]; then memory_kib=$arg; fi
  case "$arg" in
    --cg-mem=*|--mem=*) memory_kib=${arg#*=} ;;
    --) break ;;
  esac
  previous=$arg
done
if [[ "$phase" == compile ]]; then memory_kib=500000; fi
[[ "$memory_kib" =~ ^[1-9][0-9]{0,6}$ ]] || { echo "Missing aggregate memory limit" >&2; exit 2; }
options=()
while (($#)); do
  if [[ "$1" == -- ]]; then
    shift
    if [[ "$phase" == compile ]]; then
      # Isolate uses KiB: 500000 * 1024 = 512000000 bytes.
      options+=(--time=10 --wall-time=20 --extra-time=0 --cg-mem=500000)
    fi
    exec "$real" "${options[@]}" -E "CODELOBBY_MEMORY_LIMIT_KIB=$memory_kib" --dir="/dev/shm=/var/local/lib/codelobby-workspaces/$id/shm:rw" -- "$@"
  fi
  if [[ "$phase" == compile ]]; then
    case "$1" in
      -t|--time|-w|--wall-time|-x|--extra-time|-m|--mem|--cg-mem)
        (($# >= 2)) || exit 2
        shift 2
        continue
        ;;
      --time=*|--wall-time=*|--extra-time=*|--mem=*|--cg-mem=*)
        shift
        continue
        ;;
    esac
  fi
  options+=("$1")
  shift
done
echo "Expected Isolate command separator" >&2
exit 2
