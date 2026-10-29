#!/bin/bash
# Adapt only the pinned experimental image's startup logging.
set -euo pipefail

case "${1:-}" in
  server)
    expected=536580aed18ba2e6702232666142dacd590419ef41fad3cc5a23d1226fa88b5c
    original=/api/scripts/server
    ;;
  workers)
    expected=3b72247c3dcc052340d374cee8247b8d4db9f896cdb29f381ddb0603af1a9003
    original=/api/scripts/workers
    ;;
  *)
    echo "Expected server or workers startup mode" >&2
    exit 1
    ;;
esac

# Fail closed if the original script changes. No secrets are read by this wrapper.
printf '%s  %s\n' "$expected" "$original" | sha256sum -c - >/dev/null
if [[ "$1" == workers ]]; then
  # The pinned image has a group-writable /var/local ancestor.
  # Isolate/workspace paths must not have writable ancestors.
  sudo chmod go-w /var/local /var/local/lib
  export PATH="/opt/codelobby-bin:$PATH"
fi
patched=$(mktemp /tmp/judge0-start.XXXXXX)
sed 's@^export | sudo tee /api/environment$@export | sudo tee /api/environment > /dev/null@' "$original" > "$patched"
# Keep the upstream startup and signal handling in the foreground process.
exec /bin/bash "$patched"
