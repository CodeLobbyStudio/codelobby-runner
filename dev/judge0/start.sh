#!/bin/bash
# Start the prebuilt local environment. Secrets are generated once and never printed.
set -euo pipefail
cd "$(dirname "$0")"
image=ghcr.io/ptrthdr/codelobby-judge0@sha256:df4b929480326646edc449c3df7fa52562c0a55ce773529084efc3b819ed8970
command -v docker >/dev/null || { echo 'Install and start Docker Desktop first.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Start Docker Desktop, then retry.' >&2; exit 1; }
docker compose version >/dev/null
# The registry manifest digest is verified by Docker, independently of local image-ID format.
if ! docker image inspect "$image" >/dev/null 2>&1; then
  docker image pull --platform linux/amd64 "$image"
fi

config=${JUDGE0_CONFIG_FILE:-$PWD/judge0.conf}
[[ "$config" == /* ]] || config=$PWD/$config
export JUDGE0_CONFIG_FILE=$config
if [[ ! -f "$config" ]]; then
  command -v openssl >/dev/null || { echo 'OpenSSL is required to generate local passwords.' >&2; exit 1; }
  umask 077
  temp=$(mktemp "${config}.XXXXXX")
  trap 'rm -f "$temp"' EXIT
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      POSTGRES_PASSWORD=|REDIS_PASSWORD=|SECRET_KEY_BASE=)
        printf '%s=%s\n' "${line%%=*}" "$(openssl rand -hex 32)" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done < judge0.conf.example > "$temp"
  mv "$temp" "$config"
  trap - EXIT
  echo 'Created private judge0.conf with independent random secrets.'
fi
chmod 0600 "$config"
# Validate key presence without printing any values.
awk -F= '
  /^(POSTGRES_PASSWORD|REDIS_PASSWORD|SECRET_KEY_BASE)=/ { if (length($2) < 32) exit 1; seen[$1]++ }
  END { if (length(seen) != 3) exit 1 }
' "$config" || { echo 'Private judge0.conf is incomplete. Restore its original secrets.' >&2; exit 1; }
docker compose --env-file "$config" up -d --wait --wait-timeout 180
echo "Judge0 is ready: http://127.0.0.1:${JUDGE0_PORT:-2359}"
echo 'Check installed language profiles at /languages. Run ./smoke-test.py for an execution check.'
