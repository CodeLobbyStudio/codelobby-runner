#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
config=${JUDGE0_CONFIG_FILE:-$PWD/judge0.conf}
[[ "$config" == /* ]] || config=$PWD/$config
export JUDGE0_CONFIG_FILE=$config
docker compose --env-file "$config" stop
