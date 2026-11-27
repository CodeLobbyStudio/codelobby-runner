#!/bin/bash
# Load or atomically create a private local key, without printing its contents.
codelobby_load_private_key() {
  local key_file=$1 key_temp key_value
  if [[ ! -e "$key_file" && ! -L "$key_file" ]]; then
    command -v openssl >/dev/null || { echo 'OpenSSL is required to generate a private key.' >&2; return 1; }
    umask 077
    key_temp=$(mktemp "${key_file}.XXXXXX") || return 1
    if ! openssl rand -hex 32 > "$key_temp"; then
      rm -f "$key_temp"
      return 1
    fi
    # A concurrent starter may already have created the key. Preserve that key.
    ln "$key_temp" "$key_file" 2>/dev/null || true
    rm -f "$key_temp"
  fi
  [[ -f "$key_file" && ! -L "$key_file" ]] || { echo 'Private key must be a regular file, not a symlink.' >&2; return 1; }
  chmod 0600 "$key_file" || return 1
  key_value=$(cat "$key_file") || return 1
  [[ "$key_value" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid private key file. Restore the original key.' >&2; return 1; }
  CODELOBBY_LOADED_KEY=$key_value
}
