#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
TOKEN_FILE=""
OWNER="jetty:jetty"
TOKEN_BYTES=48
APPLY=0
PRINT_TOKEN=0
NO_BACKUP=0
NO_CHOWN=0

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-api-token.sh [--idp-home DIR] [--token-file FILE] rotate [--apply] [--print-token]
  graphicalmatrix-api-token.sh [--idp-home DIR] [--token-file FILE] status

Options:
  --idp-home DIR      Shibboleth IdP home. Default: /opt/shibboleth-idp
  --token-file FILE   Override token file path.
  --owner USER:GROUP  Owner used on --apply. Default: jetty:jetty
  --bytes N           Random bytes before base64 encoding. Default: 48
  --no-backup         Do not keep a timestamped backup of the previous token.
  --no-chown          Do not change owner on generated files.
  --apply             Actually rotate the token. Without this, dry-run only.
  --print-token       Print the new token after rotation. Use carefully.

Notes:
  - The GraphicalMatrix API reads the token file on each request.
  - Jetty restart is normally not required after token rotation.
  - Update provisioning clients immediately after rotation.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

trim() {
  sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

prop() {
  local file="$1"
  local key="$2"
  local default_value="${3:-}"
  if [[ -f "$file" ]]; then
    local value
    value="$(grep -E "^[[:space:]]*$key[[:space:]]*=" "$file" | tail -n 1 | sed -E 's/^[^=]*=//' | trim || true)"
    if [[ -n "$value" ]]; then
      printf "%s" "$value"
      return
    fi
  fi
  printf "%s" "$default_value"
}

generate_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 "$TOKEN_BYTES" | tr -d '\n'
    return
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$TOKEN_BYTES" <<'PY'
import base64
import os
import sys
size = int(sys.argv[1])
print(base64.b64encode(os.urandom(size)).decode("ascii"), end="")
PY
    return
  fi
  die "openssl or python3 is required to generate an API token"
}

atomic_replace() {
  local source="$1"
  local destination="$2"
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$source" "$destination" <<'PY'
import os
import sys
os.replace(sys.argv[1], sys.argv[2])
PY
    return
  fi
  mv -fT -- "$source" "$destination"
}

api_properties() {
  printf "%s/conf/graphicalmatrix/api.properties" "$IDP_HOME"
}

resolve_token_file() {
  if [[ -n "$TOKEN_FILE" ]]; then
    printf "%s" "$TOKEN_FILE"
    return
  fi
  prop "$(api_properties)" "graphicalmatrix.api.bearerTokenFile" \
    "$IDP_HOME/credentials/graphicalmatrix-api.token"
}

status() {
  local file="$1"
  echo "idp_home=$IDP_HOME"
  echo "api_properties=$(api_properties)"
  echo "token_file=$file"
  if [[ -f "$file" ]]; then
    echo "token_file_exists=yes"
    if [[ -r "$file" ]]; then
      echo "token_readable=yes"
      echo "token_length=$(wc -c < "$file" | tr -d ' ')"
    else
      echo "token_readable=no"
    fi
    ls -l "$file"
  else
    echo "token_file_exists=no"
  fi
}

rotate() {
  local file="$1"
  local dir backup backup_base staging tmp token timestamp owner_group
  dir="$(dirname "$file")"
  timestamp="$(date +%Y%m%d-%H%M%S)"
  backup_base="$file.bak.$timestamp"
  backup="$backup_base"

  echo "idp_home=$IDP_HOME"
  echo "api_properties=$(api_properties)"
  echo "token_file=$file"
  echo "owner=$OWNER"
  echo "token_bytes=$TOKEN_BYTES"

  if [[ "$APPLY" != "1" ]]; then
    echo "dry_run=yes"
    echo "would_create_directory=$dir"
    if [[ -f "$file" && "$NO_BACKUP" != "1" ]]; then
      echo "would_backup=$backup_base"
    fi
    echo "would_write_new_token=$file"
    echo "would_chmod=0400"
    [[ "$NO_CHOWN" != "1" ]] && echo "would_chown=$OWNER"
    echo "Add --apply to rotate the token."
    return
  fi

  install -d -m 0750 "$dir"
  chmod go-w "$dir"
  if [[ "$NO_CHOWN" != "1" && "$EUID" -eq 0 ]]; then
    owner_group="${OWNER#*:}"
    [[ "$owner_group" != "$OWNER" && -n "$owner_group" ]] || owner_group="root"
    chown "root:$owner_group" "$dir"
  fi

  staging="$(mktemp -d "$dir/.graphicalmatrix-token.XXXXXX")"
  chmod 0700 "$staging"
  tmp="$staging/token"
  trap 'rm -rf -- "${staging:-}"' EXIT HUP INT TERM

  if [[ -f "$file" && "$NO_BACKUP" != "1" ]]; then
    [[ ! -L "$file" ]] || die "token file must not be a symbolic link: $file"
    local backup_index=1
    while [[ -e "$backup" ]]; do
      backup="$backup_base.$backup_index"
      backup_index=$((backup_index + 1))
    done
    cp -- "$file" "$staging/backup"
    chmod 0400 "$staging/backup"
    if [[ "$NO_CHOWN" != "1" ]]; then
      chown "$OWNER" "$staging/backup"
    fi
    atomic_replace "$staging/backup" "$backup"
    echo "backup=$backup"
  fi

  token="$(generate_token)"
  umask 077
  printf "%s\n" "$token" > "$tmp"
  chmod 0400 "$tmp"
  if [[ "$NO_CHOWN" != "1" ]]; then
    chown "$OWNER" "$tmp" 2>/dev/null || true
  fi
  atomic_replace "$tmp" "$file"
  rm -rf -- "$staging"
  staging=""
  trap - EXIT HUP INT TERM
  echo "rotated=yes"
  echo "token_file=$file"
  echo "restart_required=no"
  if [[ "$PRINT_TOKEN" == "1" ]]; then
    echo "new_token=$token"
  else
    echo "new_token=(hidden; use --print-token only when needed)"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --idp-home)
      IDP_HOME="${2:-}"
      shift 2
      ;;
    --token-file)
      TOKEN_FILE="${2:-}"
      shift 2
      ;;
    --owner)
      OWNER="${2:-}"
      shift 2
      ;;
    --bytes)
      TOKEN_BYTES="${2:-}"
      shift 2
      ;;
    --no-backup)
      NO_BACKUP=1
      shift
      ;;
    --no-chown)
      NO_CHOWN=1
      shift
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    --print-token)
      PRINT_TOKEN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    rotate|status)
      CMD="$1"
      shift
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

CMD="${CMD:-}"
[[ -n "$IDP_HOME" ]] || die "--idp-home must not be empty"
[[ "$TOKEN_BYTES" =~ ^[0-9]+$ && "$TOKEN_BYTES" -ge 32 ]] || die "--bytes must be an integer >= 32"

TOKEN_FILE="$(resolve_token_file)"
[[ -n "$TOKEN_FILE" ]] || die "token file path is empty"

case "$CMD" in
  status)
    status "$TOKEN_FILE"
    ;;
  rotate)
    rotate "$TOKEN_FILE"
    ;;
  "")
    usage
    exit 2
    ;;
  *)
    die "unknown command: $CMD"
    ;;
esac
