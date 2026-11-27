#!/bin/bash
# Start the local engine and runner with separate private transport keys.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
config=${JUDGE0_CONFIG_FILE:-$root/dev/judge0/judge0.conf}
[[ "$config" == /* ]] || config=$root/dev/judge0/$config
export JUDGE0_CONFIG_FILE=$config
artifact_key_file=${JUDGE0_ARTIFACT_KEY_FILE:-${config}.artifact-key}
[[ "$artifact_key_file" == /* ]] || artifact_key_file=$root/dev/judge0/$artifact_key_file
export JUDGE0_ARTIFACT_KEY_FILE=$artifact_key_file
./dev/judge0/start.sh
. ./dev/judge0/private-key.sh
codelobby_load_private_key "$artifact_key_file"
export RUNNER_JUDGE0_ARTIFACT_KEY=$CODELOBBY_LOADED_KEY
if [[ -z "${CODELOBBY_INTERNAL_KEY:-}" ]]; then
  internal_key_file=${RUNNER_INTERNAL_KEY_FILE:-$root/dev/runner-internal.key}
  [[ "$internal_key_file" == /* ]] || internal_key_file=$root/$internal_key_file
  codelobby_load_private_key "$internal_key_file"
  export CODELOBBY_INTERNAL_KEY=$CODELOBBY_LOADED_KEY
  printf 'Backend-to-runner key file: %s\n' "$internal_key_file"
else
  [[ ${#CODELOBBY_INTERNAL_KEY} -ge 32 ]] || { echo 'The internal key must contain at least 32 characters.' >&2; exit 1; }
  echo 'Using the provided backend-to-runner internal key.'
fi
unset CODELOBBY_LOADED_KEY
export RUNNER_SUBMIT_ENABLED=${RUNNER_SUBMIT_ENABLED:-true}
export JUDGE0_URL=${JUDGE0_URL:-http://127.0.0.1:${JUDGE0_PORT:-2359}}
echo 'Starting the runner with Run and Java Submit. Keep all key files private.'
exec ./mvnw -B -ntp spring-boot:run
