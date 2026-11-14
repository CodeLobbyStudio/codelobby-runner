#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
config=${JUDGE0_CONFIG_FILE:-$PWD/judge0.conf}
[[ "$config" == /* ]] || config=$PWD/$config
export JUDGE0_CONFIG_FILE=$config
[[ -f "$config" ]] || { echo 'Private Judge0 configuration is missing. Use the same JUDGE0_CONFIG_FILE as start.sh.' >&2; exit 1; }
docker compose --env-file "$config" -f compose.yaml -f compose.reports.yaml stop
