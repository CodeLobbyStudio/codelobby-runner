#!/bin/bash
# Start the prebuilt local environment. Secrets are generated once and never printed.
set -euo pipefail
cd "$(dirname "$0")"
image=ghcr.io/ptrthdr/codelobby-judge0@sha256:df4b929480326646edc449c3df7fa52562c0a55ce773529084efc3b819ed8970
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    echo 'Run this script inside a WSL2 Linux distribution, not Git Bash or PowerShell.' >&2
    exit 1
    ;;
esac
command -v docker >/dev/null || { echo 'Install Docker Engine with Compose v2, or Docker Desktop with WSL2 integration on Windows.' >&2; exit 1; }
engine=$(docker info --format '{{.OSType}} {{.CgroupVersion}}' 2>/dev/null) || {
  echo 'Docker is unavailable. Start Docker and check access from this terminal.' >&2
  exit 1
}
[[ "$engine" == 'linux 2' ]] || { echo 'Judge0 requires Linux containers with cgroup v2.' >&2; exit 1; }
security=$(docker info --format '{{range .SecurityOptions}}{{println .}}{{end}}')
[[ "$security" != *rootless* ]] || { echo 'This Judge0 configuration requires a rootful Docker engine. Rootless mode is unsupported.' >&2; exit 1; }
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
docker compose --env-file "$config" -f compose.yaml -f compose.reports.yaml up -d --wait --wait-timeout 180
echo "Judge0 HTTP API is ready: http://127.0.0.1:${JUDGE0_PORT:-2359}"
echo 'Java Run adapters are included. HTTP readiness alone does not verify execution.'
echo 'Check /languages and run python3 smoke-test.py for an engine execution check.'
