#!/usr/bin/env bash
set -euo pipefail

BASE="${GRAPHICALMATRIX_HOME:-/opt/graphicalmatrix-admin}"
ADMIN_PROPERTIES="${ADMIN_PROPERTIES:-$BASE/conf/graphicalmatrix/admin.properties}"
GRAPHICAL_PROPERTIES="${GRAPHICAL_PROPERTIES:-$BASE/conf/graphicalmatrix/graphicalmatrix.properties}"
DB_TOOL="$BASE/bin/graphicalmatrix-db.sh"

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

bool_true() {
  case "$(printf "%s" "${1:-}" | tr '[:upper:]' '[:lower:]' | trim)" in
    true|yes|on|1) return 0 ;;
    *) return 1 ;;
  esac
}

timestamp() {
  date -Is
}

LOG_FILE="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.logFile' "$BASE/logs/csv-import.log")"
LOCK_FILE="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.lockFile' "$BASE/logs/csv-import.lock")"
mkdir -p "$(dirname "$LOG_FILE")" "$(dirname "$LOCK_FILE")"

log() {
  printf 'ts=%s %s\n' "$(timestamp)" "$*" >> "$LOG_FILE"
}

fail() {
  log "event=CSV_IMPORT_RUNNER_FAIL detail=$(printf "%q" "$*")"
  echo "ERROR: $*" >&2
  exit 1
}

check_admin_enabled() {
  bool_true "$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.enabled' 'false')" \
    || fail "Admin Tools are disabled: graphicalmatrix.admin.enabled is not true"
  bool_true "$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.provisioning.enabled' 'false')" \
    || fail "CSV provisioning is disabled: graphicalmatrix.admin.provisioning.enabled is not true"
}

check_group() {
  local required_group
  required_group="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.requiredGroup' '')"
  [[ -z "$required_group" ]] && return 0
  id -nG | tr ' ' '\n' | grep -Fxq "$required_group" \
    || fail "current user is not in required group: $required_group"
}

check_host() {
  local allowed hosts item current
  allowed="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.allowedHosts' '')"
  [[ -z "$allowed" ]] && return 0
  hosts="$(hostname -f 2>/dev/null || hostname)"
  hosts="$hosts $(hostname -s 2>/dev/null || true) $(hostname -I 2>/dev/null || true)"
  IFS=',' read -r -a items <<< "$allowed"
  for item in "${items[@]}"; do
    item="$(printf "%s" "$item" | trim)"
    [[ -n "$item" ]] || continue
    for current in $hosts; do
      if [[ "$item" == "$current" ]]; then
        return 0
      fi
    done
  done
  fail "host is not allowed for Admin Tools: allowedHosts=$allowed"
}

check_client_cert() {
  bool_true "$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.requireClientCert' 'false')" || return 0
  local cert_path
  cert_path="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.clientCertPath' '')"
  [[ -n "$cert_path" && -r "$cert_path" ]] \
    || fail "client certificate is required but not readable: $cert_path"
}

check_sequence_storage() {
  bool_true "$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.productionMode' 'true')" || return 0
  bool_true "$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.rejectPlaintextSequence' 'true')" || return 0
  local mode
  mode="$(prop "$GRAPHICAL_PROPERTIES" 'graphicalmatrix.sequence.storage' 'plaintext')"
  case "$(printf "%s" "$mode" | tr '[:upper:]' '[:lower:]' | trim)" in
    plaintext|plain|'')
      fail "plaintext sequence storage is rejected in Admin Tools production mode"
      ;;
  esac
}

csv_action_list() {
  awk -F, '
    /^[[:space:]]*($|#)/ { next }
    NR == 1 && tolower($1) == "action" { next }
    {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", $1)
      print toupper($1)
    }
  ' "$1"
}

csv_row_count() {
  awk -F, '
    /^[[:space:]]*($|#)/ { next }
    NR == 1 && tolower($1) == "action" { next }
    { count++ }
    END { print count + 0 }
  ' "$1"
}

csv_disable_count() {
  csv_action_list "$1" | awk '$1 == "D" || $1 == "DELETE" || $1 == "DEL" { count++ } END { print count + 0 }'
}

should_apply_csv() {
  bool_true "$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.autoApply' 'false')" || return 1

  local actions allowed auto_d action allowed_match
  actions="$(csv_action_list "$1")"
  allowed="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.autoApplyActions' 'A,M')"
  auto_d="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.autoApplyDeprovision' 'true')"

  while IFS= read -r action; do
    [[ -n "$action" ]] || continue
    case "$action" in
      D|DELETE|DEL)
        bool_true "$auto_d" || return 1
        ;;
      A|ADD)
        [[ ",$allowed," == *",A,"* ]] || return 1
        ;;
      M|MODIFY|MOD)
        allowed_match=0
        [[ ",$allowed," == *",M,"* ]] && allowed_match=1
        (( allowed_match == 1 )) || return 1
        ;;
      *)
        return 1
        ;;
    esac
  done <<< "$actions"

  return 0
}

process_file() {
  local src="$1"
  local name ts work rows disables max_rows max_disables apply_args

  name="$(basename "$src")"
  [[ "$name" =~ ^[A-Za-z0-9._-]+[.]csv$ ]] || {
    log "event=CSV_IMPORT_SKIP file=$(printf "%q" "$name") detail=invalid_filename"
    return 0
  }

  rows="$(csv_row_count "$src")"
  max_rows="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.maxRows' '10000')"
  [[ "$rows" =~ ^[0-9]+$ && "$rows" -le "$max_rows" ]] \
    || fail "CSV row count exceeds limit: file=$name rows=$rows maxRows=$max_rows"

  disables="$(csv_disable_count "$src")"
  max_disables="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.maxDisables' '1000')"
  [[ "$disables" =~ ^[0-9]+$ && "$disables" -le "$max_disables" ]] \
    || fail "CSV disable count exceeds limit: file=$name disables=$disables maxDisables=$max_disables"

  ts="$(date +%Y%m%d%H%M%S)"
  work="$PROCESSING_DIR/$ts-$name"
  mv "$src" "$work"

  log "event=CSV_IMPORT_START file=$(printf "%q" "$name") rows=$rows disables=$disables"
  if "$DB_TOOL" csv "$work" --provisioning >> "$LOG_FILE" 2>&1; then
    if should_apply_csv "$work"; then
      "$DB_TOOL" csv "$work" --provisioning --apply >> "$LOG_FILE" 2>&1
      log "event=CSV_IMPORT_APPLY_OK file=$(printf "%q" "$name")"
    else
      log "event=CSV_IMPORT_DRYRUN_ONLY file=$(printf "%q" "$name")"
    fi
    mv "$work" "$PROCESSED_DIR/$ts-$name"
    log "event=CSV_IMPORT_OK file=$(printf "%q" "$name")"
  else
    local rc=$?
    mv "$work" "$FAILED_DIR/$ts-$name" 2>/dev/null || true
    log "event=CSV_IMPORT_FAIL file=$(printf "%q" "$name") rc=$rc"
    return "$rc"
  fi
}

[[ -x "$DB_TOOL" ]] || fail "graphicalmatrix-db.sh is not executable: $DB_TOOL"
[[ -r "$ADMIN_PROPERTIES" ]] || fail "admin.properties is not readable: $ADMIN_PROPERTIES"

check_admin_enabled
check_group
check_host
check_client_cert
check_sequence_storage

INCOMING_DIR="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.incomingDir' "$BASE/incoming")"
PROCESSING_DIR="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.processingDir' "$BASE/processing")"
PROCESSED_DIR="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.processedDir' "$BASE/processed")"
FAILED_DIR="$(prop "$ADMIN_PROPERTIES" 'graphicalmatrix.admin.csv.failedDir' "$BASE/failed")"
mkdir -p "$INCOMING_DIR" "$PROCESSING_DIR" "$PROCESSED_DIR" "$FAILED_DIR" "$(dirname "$LOG_FILE")"

exec 9>"$LOCK_FILE"
flock -n 9 || fail "another CSV import runner is already running"

shopt -s nullglob
for file in "$INCOMING_DIR"/*.csv; do
  process_file "$file"
done
