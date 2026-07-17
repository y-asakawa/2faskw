#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -n "${GRAPHICALMATRIX_HOME:-}" ]]; then
  IDP_HOME="$GRAPHICALMATRIX_HOME"
elif [[ -n "${IDP_HOME:-}" ]]; then
  IDP_HOME="$IDP_HOME"
elif [[ -d /opt/shibboleth-idp/conf/graphicalmatrix ]]; then
  IDP_HOME="/opt/shibboleth-idp"
else
  IDP_HOME="$SCRIPT_HOME"
fi

DB_PROPERTIES="${DB_PROPERTIES:-$IDP_HOME/conf/graphicalmatrix/db.properties}"
GRAPHICAL_PROPERTIES="${GRAPHICAL_PROPERTIES:-$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties}"
if [[ -n "${H2_JAR:-}" ]]; then
  :
elif compgen -G "$SCRIPT_HOME/lib/h2-*.jar" >/dev/null; then
  H2_JAR="$(ls "$SCRIPT_HOME"/lib/h2-*.jar | head -n 1)"
else
  H2_JAR="$IDP_HOME/edit-webapp/WEB-INF/lib/h2-2.2.224.jar"
fi

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-db.sh COMMAND [OPTIONS]

Read commands:
  list
      Show all GraphicalMatrix enrollment rows.

  show USER
      Show one user's MFA method, sequence status, lock state, TOTP state,
      timestamps, and other public management fields.

User registration / update:
  add USER img03,img07,img11,img14
  add USER A B C D
      Add or update a user's current GraphicalMatrix sequence.
      Alias values such as A/B/C/D are resolved by graphicalmatrix.aliases.

  set-sequence USER img03,img07,img11,img14
  set-sequence USER A B C D
      Update the user's current GraphicalMatrix sequence.

  set-initial-sequence USER img03,img07,img11,img14
  set-initial-sequence USER A B C D
      Update the plaintext initial sequence used by USER RESET.

  set-method USER GraphicalMatrix|TOTP|WebAuthn
      Change the MFA method selected for the user.

  require-change USER on|off
      Enable or disable forced GraphicalMatrix sequence change after login.

  USER RESET
      Copy the plaintext initial sequence into the protected current sequence,
      set status=ACTIVE, clear the lock state, and require sequence change.

Account status / lock:
  enable USER
      Set status=ACTIVE.

  disable USER
      Set status=DISABLED. The DB row is retained.

  unlock USER
      Clear locked_until and failed_count.

  lock USER MINUTES
      Lock the user for the specified number of minutes.

  reset-failures USER
      Clear failed_count without changing the sequence.

  delete USER
      Delete the GraphicalMatrix enrollment row.

CSV import / export:
  csv FILE
  csv -
      Dry-run CSV import. The DB is not updated.

  csv FILE --apply
      Apply CSV import in standard mode.
      WARNING: D deletes the user row in standard mode.

  csv FILE --provisioning
      Dry-run CSV import in provisioning mode.
      In provisioning mode, D is treated as disable, not delete.

  csv FILE --provisioning --apply
  provisioning-csv FILE --apply
      Apply CSV import in provisioning mode.
      A inserts or reactivates/updates, M updates, D disables.

  csv-export FILE
      Export current DB data to CSV. Existing files are not overwritten.

  csv-export FILE --force
      Export current DB data to CSV and overwrite an existing file.

  csv-export -
      Write CSV export to stdout.

TOTP:
  set-totp-seed USER BASE32SEED
      Set a TOTP seed for the user.
      The seed is stored according to graphicalmatrix.totp.seed.storage.

  clear-totp-seed USER
      Remove the TOTP seed.

  reset-totp USER
      Reset TOTP registration state so the user can register again.

WebAuthn:
  webauthn-list [USER]
      List WebAuthn credentials. USER is optional.

  webauthn-reset USER
      Dry-run deletion of all WebAuthn credentials for USER.

  webauthn-reset USER --apply
      Delete all WebAuthn credentials for USER.

  webauthn-reset USER --set-method GraphicalMatrix --apply
      Delete all WebAuthn credentials for USER and set mfa_method=GraphicalMatrix.

  webauthn-delete USER --credential-id CREDENTIAL_ID
      Dry-run deletion of one WebAuthn credential.

  webauthn-delete USER --credential-id CREDENTIAL_ID --apply
      Delete one WebAuthn credential.

Sequence storage:
  sequence-mode
      Show the configured sequence storage mode.

  security-status
      Show machine-readable security migration readiness counters.

  migrate-sequence-storage
      Dry-run sequence storage migration.

  migrate-sequence-storage --apply
      Apply sequence storage migration.

TOTP seed storage:
  totp-seed-mode
      Show the configured TOTP seed storage mode.

  migrate-totp-seed-storage
      Dry-run TOTP seed storage migration.

  migrate-totp-seed-storage --apply
      Apply TOTP seed storage migration.

Configuration help:
  config-help
      Show docs/CONFIG-REFERENCE.md.

  config-help PROPERTY
      Search docs/CONFIG-REFERENCE.md for one property name.

Safety notes:
  - CSV import without --apply is dry-run.
  - WebAuthn reset/delete without --apply is dry-run.
  - Other update commands modify the DB immediately.
  - Standard CSV mode treats D as physical delete.
  - Provisioning CSV mode treats D as disable.
  - Production should not use plaintext sequence storage.
  - Production should not use plaintext TOTP seed storage.

Environment:
  GRAPHICALMATRIX_HOME default: script parent directory for standalone admin installs,
                    otherwise /opt/shibboleth-idp when present
  IDP_HOME         compatibility alias for GRAPHICALMATRIX_HOME
  DB_PROPERTIES   default: $GRAPHICALMATRIX_HOME/conf/graphicalmatrix/db.properties
  GRAPHICAL_PROPERTIES default: $GRAPHICALMATRIX_HOME/conf/graphicalmatrix/graphicalmatrix.properties
  H2_JAR          default: $GRAPHICALMATRIX_HOME/lib/h2-*.jar if present,
                  otherwise $IDP_HOME/edit-webapp/WEB-INF/lib/h2-2.2.224.jar
  SEQUENCE_TOOL_CP optional explicit classpath for Java sequence/export tools

CSV format:
  action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence
  A,user001,GraphicalMatrix,on,"img01,img02,img03,img04","img03,img07,img11,img14"
  M,user001,TOTP,off,"img01,img02,img03,img04","img05,img06,img07,img08"
  D,user001
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
  local key="$1"
  local default_value="${2:-}"
  if [[ -f "$DB_PROPERTIES" ]]; then
    local value
    value="$(grep -E "^[[:space:]]*$key[[:space:]]*=" "$DB_PROPERTIES" | tail -n 1 | sed -E 's/^[^=]*=//' | trim || true)"
    if [[ -n "$value" ]]; then
      printf "%s" "$value"
      return
    fi
  fi
  printf "%s" "$default_value"
}

config_prop() {
  local key="$1"
  local default_value="${2:-}"
  if [[ -f "$GRAPHICAL_PROPERTIES" ]]; then
    local value
    value="$(grep -E "^[[:space:]]*$key[[:space:]]*=" "$GRAPHICAL_PROPERTIES" | tail -n 1 | sed -E 's/^[^=]*=//' | trim || true)"
    if [[ -n "$value" ]]; then
      printf "%s" "$value"
      return
    fi
  fi
  printf "%s" "$default_value"
}

config_reference_path() {
  local candidates=(
    "$IDP_HOME/docs/CONFIG-REFERENCE.md"
    "$SCRIPT_DIR/docs/CONFIG-REFERENCE.md"
    "$SCRIPT_HOME/docs/CONFIG-REFERENCE.md"
    "$SCRIPT_HOME/../docs/CONFIG-REFERENCE.md"
    "$SCRIPT_HOME/../../docs/CONFIG-REFERENCE.md"
  )
  local file
  for file in "${candidates[@]}"; do
    if [[ -r "$file" ]]; then
      printf "%s" "$file"
      return 0
    fi
  done
  return 1
}

config_help() {
  local query="${1:-}"
  local ref
  ref="$(config_reference_path)" || die "CONFIG-REFERENCE.md not found. Install docs or check GRAPHICALMATRIX_HOME."
  if [[ -z "$query" ]]; then
    cat "$ref"
    return
  fi
  if ! grep -F -i -- "$query" "$ref"; then
    die "property not found in CONFIG-REFERENCE.md: $query"
  fi
}

sql_quote() {
  printf "%s" "$1" | sed "s/'/''/g"
}

validate_user() {
  [[ "${1:-}" =~ ^[A-Za-z0-9._@-]+$ ]] || die "invalid user id: ${1:-}"
}

graphical_id_lines() {
  local value="${1:-}"
  [[ -n "$value" ]] || return 0
  local old_ifs="$IFS"
  IFS=','
  local items=($value)
  IFS="$old_ifs"

  local raw item start end prefix start_num end_prefix end_num width start_n end_n i
  for raw in "${items[@]}"; do
    item="$(printf "%s" "$raw" | trim)"
    [[ -n "$item" ]] || continue
    if [[ "$item" == *-* ]]; then
      start="${item%%-*}"
      end="${item#*-}"
      [[ "$start" =~ ^([A-Za-z_-]*)([0-9]+)$ ]] || die "invalid graphicalmatrix graphical range: $item"
      prefix="${BASH_REMATCH[1]}"
      start_num="${BASH_REMATCH[2]}"
      width="${#start_num}"
      if [[ "$end" =~ ^([A-Za-z_-]*)([0-9]+)$ ]]; then
        end_prefix="${BASH_REMATCH[1]}"
        end_num="${BASH_REMATCH[2]}"
        [[ "$end_prefix" == "$prefix" || -z "$end_prefix" ]] || die "invalid graphicalmatrix graphical range: $item"
      elif [[ "$end" =~ ^[0-9]+$ ]]; then
        end_num="$end"
      else
        die "invalid graphicalmatrix graphical range: $item"
      fi
      start_n=$((10#$start_num))
      end_n=$((10#$end_num))
      (( start_n <= end_n )) || die "invalid descending graphicalmatrix graphical range: $item"
      for ((i = start_n; i <= end_n; i++)); do
        printf "%s%0${width}d\n" "$prefix" "$i"
      done
    else
      [[ "$item" =~ ^[A-Za-z_-]*[0-9]+$ ]] || die "invalid graphicalmatrix graphical id: $item"
      printf "%s\n" "$item"
    fi
  done
}

allowed_graphical_ids() {
  local graphicals not_graphicals id not_id skip
  graphicals="$(graphical_id_lines "$(config_prop 'graphicalmatrix.graphicals' 'img01-25')")"
  not_graphicals="$(graphical_id_lines "$(config_prop 'graphicalmatrix.not_graphicals' '')")"
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    skip=0
    while IFS= read -r not_id; do
      [[ -n "$not_id" ]] || continue
      if [[ "$id" == "$not_id" ]]; then
        skip=1
        break
      fi
    done <<< "$not_graphicals"
    (( skip == 0 )) && printf "%s\n" "$id"
  done <<< "$graphicals"
}

line_contains() {
  local lines="$1"
  local needle="$2"
  grep -Fxq "$needle" <<< "$lines"
}

alias_pairs() {
  local value raw pair alias graphical old_ifs
  value="$(config_prop 'graphicalmatrix.aliases' '')"
  [[ -n "$value" ]] || return 0
  old_ifs="$IFS"
  IFS=','
  local items=($value)
  IFS="$old_ifs"
  for raw in "${items[@]}"; do
    pair="$(printf "%s" "$raw" | trim)"
    [[ -n "$pair" ]] || continue
    [[ "$pair" == *:* ]] || die "invalid graphicalmatrix alias pair: $pair"
    alias="$(printf "%s" "${pair%%:*}" | trim)"
    graphical="$(printf "%s" "${pair#*:}" | trim)"
    [[ "$alias" =~ ^[A-Za-z0-9_-]+$ ]] || die "invalid graphicalmatrix alias: $alias"
    [[ "$graphical" =~ ^[A-Za-z_-]*[0-9]+$ ]] || die "invalid graphicalmatrix alias graphical id: $graphical"
    printf "%s=%s\n" "$alias" "$graphical"
  done
}

alias_to_graphical() {
  local token="$1"
  local pair alias graphical
  while IFS= read -r pair; do
    [[ -n "$pair" ]] || continue
    alias="${pair%%=*}"
    graphical="${pair#*=}"
    if [[ "$token" == "$alias" ]]; then
      printf "%s" "$graphical"
      return 0
    fi
  done <<< "$(alias_pairs)"
  return 1
}

graphical_to_alias() {
  local token="$1"
  local pair alias graphical
  while IFS= read -r pair; do
    [[ -n "$pair" ]] || continue
    alias="${pair%%=*}"
    graphical="${pair#*=}"
    if [[ "$token" == "$graphical" ]]; then
      printf "%s" "$alias"
      return 0
    fi
  done <<< "$(alias_pairs)"
  return 1
}

resolve_sequence_to_graphicals() {
  local sequence="${1:-}"
  [[ -n "$sequence" ]] || die "invalid sequence: $sequence"
  local old_ifs="$IFS"
  IFS=','
  local selected=($sequence)
  IFS="$old_ifs"
  local out="" raw token resolved
  for raw in "${selected[@]}"; do
    token="$(printf "%s" "$raw" | trim)"
    [[ -n "$token" ]] || continue
    if resolved="$(alias_to_graphical "$token")"; then
      :
    else
      resolved="$token"
    fi
    if [[ -n "$out" ]]; then
      out="${out},${resolved}"
    else
      out="$resolved"
    fi
  done
  printf "%s" "$out"
}

normalize_initial_sequence() {
  local sequence="${1:-}"
  [[ -n "$sequence" ]] || die "invalid initial_sequence: $sequence"
  local old_ifs="$IFS"
  IFS=','
  local selected=($sequence)
  IFS="$old_ifs"
  local out="" raw token alias graphical
  for raw in "${selected[@]}"; do
    token="$(printf "%s" "$raw" | trim)"
    [[ -n "$token" ]] || continue
    if alias_to_graphical "$token" >/dev/null; then
      alias="$token"
    elif alias="$(graphical_to_alias "$token")"; then
      :
    else
      alias="$token"
    fi
    if [[ -n "$out" ]]; then
      out="${out},${alias}"
    else
      out="$alias"
    fi
  done
  printf "%s" "$out"
}

validate_sequence() {
  local sequence="${1:-}"
  [[ -n "$sequence" ]] || die "invalid sequence: $sequence"
  sequence="$(resolve_sequence_to_graphicals "$sequence")"

  local choice
  choice="$(config_prop 'graphicalmatrix.choice' '4')"
  [[ "$choice" =~ ^[0-9]+$ && "$choice" -gt 0 ]] || die "invalid graphicalmatrix.choice: $choice"

  local old_ifs="$IFS"
  IFS=','
  local selected=($sequence)
  IFS="$old_ifs"
  [[ "${#selected[@]}" -eq "$choice" ]] \
    || die "invalid sequence count: got ${#selected[@]}, expected $choice"

  local allowed seen id seen_id
  local allow_duplicates
  allow_duplicates="$(config_prop 'graphicalmatrix.allow_duplicates' '0')"
  allowed="$(allowed_graphical_ids)"
  [[ -n "$allowed" ]] || die "graphicalmatrix.graphicals has no usable graphical ids"
  seen=""
  for id in "${selected[@]}"; do
    id="$(printf "%s" "$id" | trim)"
    [[ "$id" =~ ^[A-Za-z_-]*[0-9]+$ ]] || die "invalid graphical id in sequence: $id"
    line_contains "$allowed" "$id" || die "graphical id is not allowed by graphicalmatrix.graphicals/not_graphicals: $id"
    if [[ "$allow_duplicates" != "1" && "$allow_duplicates" != "true" && "$allow_duplicates" != "TRUE" ]]; then
      while IFS= read -r seen_id; do
        [[ -n "$seen_id" ]] || continue
        [[ "$seen_id" != "$id" ]] || die "duplicate graphical id in sequence: $id"
      done <<< "$seen"
    fi
    seen="${seen}${id}"$'\n'
  done
}

normalize_force_value() {
  local value="${1:-}"
  case "$value" in
    1|on|ON|true|TRUE|yes|YES) printf "1" ;;
    0|off|OFF|false|FALSE|no|NO) printf "0" ;;
    *) die "force_sequence_change value must be on or off: $value" ;;
  esac
}

sequence_storage_mode() {
  local mode
  mode="$(config_prop 'graphicalmatrix.sequence.storage' 'auto')"
  mode="$(printf "%s" "$mode" | tr '[:upper:]' '[:lower:]')"
  case "$mode" in
    ""|auto) printf "hash" ;;
    plain|plaintext) printf "plaintext" ;;
    keyword|common-keyword|common_keyword) printf "keyword" ;;
    aes|aes-gcm|aes_gcm) printf "aes-gcm" ;;
    hash|hash-salt-pepper|hash_salt_pepper) printf "hash" ;;
    *) die "unsupported graphicalmatrix.sequence.storage: $mode" ;;
  esac
}

sequence_storage_sql_predicate() {
  local column="${1:-sequence}"
  case "$(sequence_storage_mode)" in
    plaintext)
      printf "%s" "$column NOT LIKE 'kw1:%%' AND $column NOT LIKE 'aesgcm1:%%' AND $column NOT LIKE 'hsp1:%%'"
      ;;
    keyword)
      printf "%s" "$column LIKE 'kw1:%%'"
      ;;
    aes-gcm)
      printf "%s" "$column LIKE 'aesgcm1:%%'"
      ;;
    hash)
      printf "%s" "$column LIKE 'hsp1:%%'"
      ;;
  esac
}

security_status() {
  local mode predicate state_column total initial_nonempty initial_incompatible initial_empty
  local incompatible active_empty active_incompatible
  mode="$(sequence_storage_mode)"
  predicate="$(sequence_storage_sql_predicate)"
  state_column="$(run_scalar "
SELECT COUNT(*) AS initial_sequence
FROM INFORMATION_SCHEMA.COLUMNS
WHERE LOWER(TABLE_NAME) = 'graphicalmatrix_enrollment'
  AND LOWER(COLUMN_NAME) = 'state_version';" | tail -n 1 | trim)"
  [[ "$state_column" == "1" ]] \
    || die "state_version column is missing; apply conf/graphicalmatrix/postgresql-schema.sql first"

  total="$(run_scalar "SELECT COUNT(*) AS initial_sequence FROM graphicalmatrix_enrollment;" | tail -n 1 | trim)"
  initial_nonempty="$(run_scalar "
SELECT COUNT(*) AS initial_sequence FROM graphicalmatrix_enrollment
WHERE initial_sequence IS NOT NULL AND initial_sequence <> '';" | tail -n 1 | trim)"
  initial_incompatible="$(run_scalar "
SELECT COUNT(*) AS initial_sequence FROM graphicalmatrix_enrollment
WHERE initial_sequence IS NOT NULL AND initial_sequence <> ''
  AND (initial_sequence LIKE 'kw1:%'
    OR initial_sequence LIKE 'aesgcm1:%'
    OR initial_sequence LIKE 'hsp1:%');" | tail -n 1 | trim)"
  initial_empty="$(run_scalar "
SELECT COUNT(*) AS initial_sequence FROM graphicalmatrix_enrollment
WHERE initial_sequence IS NULL OR initial_sequence = '';" | tail -n 1 | trim)"
  incompatible="$(run_scalar "
SELECT COUNT(*) AS initial_sequence FROM graphicalmatrix_enrollment
WHERE sequence IS NOT NULL AND sequence <> '' AND NOT ($predicate);" | tail -n 1 | trim)"
  active_empty="$(run_scalar "
SELECT COUNT(*) AS initial_sequence FROM graphicalmatrix_enrollment
WHERE status = 'ACTIVE' AND (sequence IS NULL OR sequence = '');" | tail -n 1 | trim)"
  active_incompatible="$(run_scalar "
SELECT COUNT(*) AS initial_sequence FROM graphicalmatrix_enrollment
WHERE status = 'ACTIVE' AND sequence IS NOT NULL AND sequence <> ''
  AND NOT ($predicate);" | tail -n 1 | trim)"

  echo "security.target_storage=$mode"
  echo "security.total_rows=$total"
  echo "security.initial_sequence_nonempty=$initial_nonempty"
  echo "security.initial_sequence_empty_rows=$initial_empty"
  echo "security.initial_sequence_incompatible_rows=$initial_incompatible"
  echo "security.incompatible_sequence_rows=$incompatible"
  echo "security.active_empty_sequence_rows=$active_empty"
  echo "security.active_incompatible_sequence_rows=$active_incompatible"
}

totp_seed_storage_mode() {
  command -v java >/dev/null 2>&1 || die "java is required for TOTP seed storage mode"
  local cp
  cp="$(sequence_tool_classpath)"
  java -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixTotpSeedTool mode "$IDP_HOME"
}

bool_config() {
  local key="$1"
  local default_value="${2:-false}"
  local value
  value="$(config_prop "$key" "$default_value")"
  value="$(printf "%s" "$value" | tr '[:upper:]' '[:lower:]')"
  case "$value" in
    1|true|yes|on) printf "true" ;;
    *) printf "false" ;;
  esac
}

sequence_tool_classpath() {
  if [[ -n "${SEQUENCE_TOOL_CP:-}" ]]; then
    printf "%s" "$SEQUENCE_TOOL_CP"
    return
  fi
  local lib_dir cp plugin_jar
  local candidates=(
    "$SCRIPT_HOME/lib"
    "$IDP_HOME/lib"
    "$IDP_HOME/edit-webapp/WEB-INF/lib"
  )
  for lib_dir in "${candidates[@]}"; do
    [[ -d "$lib_dir" ]] || continue
    plugin_jar="$(ls "$lib_dir"/2faskw-idp-plugin-*.jar 2>/dev/null | head -n 1 || true)"
    if [[ -z "$plugin_jar" ]]; then
      plugin_jar="$(ls "$lib_dir"/graphicalmatrix-idp-plugin-*.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [[ -z "$plugin_jar" ]]; then
      plugin_jar="$(ls "$lib_dir"/graphicalmatrix-*.jar 2>/dev/null | head -n 1 || true)"
    fi
    if [[ -n "$plugin_jar" ]]; then
      cp="$(find "$lib_dir" -maxdepth 1 -type f -name '*.jar' -print 2>/dev/null | sort | paste -sd ':' -)"
      [[ -n "$cp" ]] || die "No jars found in $lib_dir; set SEQUENCE_TOOL_CP for sequence storage tools"
      printf "%s" "$cp"
      return
    fi
  done
  die "2FAS-KW plugin jar not found; install the admin lib directory or set SEQUENCE_TOOL_CP"
}

sequence_storage_encode() {
  local sequence="$1"
  local mode ordered duplicates cp
  mode="$(sequence_storage_mode)"
  if [[ "$mode" == "plaintext" ]]; then
    printf "%s" "$sequence"
    return
  fi
  command -v java >/dev/null 2>&1 || die "java is required for graphicalmatrix.sequence.storage=$mode"
  ordered="false"
  [[ "$(config_prop 'graphicalmatrix.order' '1')" == "1" ]] && ordered="true"
  duplicates="$(bool_config 'graphicalmatrix.allow_duplicates' '0')"
  cp="$(sequence_tool_classpath)"
  java -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixSequenceTool \
    encode "$IDP_HOME" "$sequence" "$ordered" "$duplicates"
}

initial_sequence_plaintext() {
  local sequence="$1"
  normalize_initial_sequence "$sequence"
}

sequence_storage_display() {
  local stored="$1"
  [[ -n "$stored" ]] || return 0
  local cp
  cp="$(sequence_tool_classpath)"
  java -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixSequenceTool \
    display "$IDP_HOME" "$stored"
}

enrollment_list() {
  local user="${1:-}"
  if [[ -n "$user" ]]; then
    local user_q
    user_q="$(sql_quote "$user")"
    run_sql "$select_public_columns
WHERE user_id = '$user_q'
ORDER BY user_id;"
  else
    run_sql "$select_public_columns
ORDER BY user_id;"
  fi
}

totp_seed_storage_encode() {
  local seed="$1"
  command -v java >/dev/null 2>&1 || die "java is required for TOTP seed storage"
  local cp
  cp="$(sequence_tool_classpath)"
  java -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixTotpSeedTool \
    encode "$IDP_HOME" "$seed"
}

totp_seed_storage_migrate() {
  local mode="$1"
  local cp
  command -v java >/dev/null 2>&1 || die "java is required for TOTP seed storage migration"
  cp="$(sequence_tool_classpath)"
  if [[ "$db_type" == "h2" && "$(id -un)" != "jetty" ]]; then
    sudo -u jetty env SEQUENCE_TOOL_CP="$cp" IDP_HOME="$IDP_HOME" \
      DB_PROPERTIES="$DB_PROPERTIES" GRAPHICAL_PROPERTIES="$GRAPHICAL_PROPERTIES" \
      bash -c 'java -cp "$SEQUENCE_TOOL_CP" io.github.yasakawa.faskw.GraphicalMatrixTotpSeedMigrationTool "$1" "$IDP_HOME"' _ "$mode"
  else
    java -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixTotpSeedMigrationTool "$mode" "$IDP_HOME"
  fi
}

sequence_storage_migrate() {
  local mode="$1"
  local cp
  command -v java >/dev/null 2>&1 || die "java is required for sequence storage migration"
  cp="$(sequence_tool_classpath)"
  if [[ "$db_type" == "h2" && "$(id -un)" != "jetty" ]]; then
    sudo -u jetty env SEQUENCE_TOOL_CP="$cp" IDP_HOME="$IDP_HOME" \
      DB_PROPERTIES="$DB_PROPERTIES" GRAPHICAL_PROPERTIES="$GRAPHICAL_PROPERTIES" \
      bash -c 'java -cp "$SEQUENCE_TOOL_CP" io.github.yasakawa.faskw.GraphicalMatrixSequenceMigrationTool "$1" "$IDP_HOME"' _ "$mode"
  else
    java -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixSequenceMigrationTool "$mode" "$IDP_HOME"
  fi
}

csv_export_stream() {
  local cp
  command -v java >/dev/null 2>&1 || die "java is required for CSV export"
  cp="$(sequence_tool_classpath)"
  if [[ "$db_type" == "h2" && "$(id -un)" != "jetty" ]]; then
    sudo -u jetty env SEQUENCE_TOOL_CP="$cp" IDP_HOME="$IDP_HOME" \
      DB_PROPERTIES="$DB_PROPERTIES" GRAPHICAL_PROPERTIES="$GRAPHICAL_PROPERTIES" \
      bash -c 'java -cp "$SEQUENCE_TOOL_CP" io.github.yasakawa.faskw.GraphicalMatrixCsvExportTool "$IDP_HOME"'
  else
    java -cp "$cp" io.github.yasakawa.faskw.GraphicalMatrixCsvExportTool "$IDP_HOME"
  fi
}

csv_export() {
  local output="$1"
  local force="${2:-0}"
  if [[ "$output" == "-" ]]; then
    csv_export_stream
    return
  fi

  local output_dir temp_file
  output_dir="$(dirname "$output")"
  [[ -d "$output_dir" ]] || die "CSV export directory does not exist: $output_dir"
  if [[ -e "$output" && "$force" != "1" ]]; then
    die "CSV export file already exists; use --force to overwrite: $output"
  fi

  umask 077
  temp_file="$(mktemp "$output_dir/.graphicalmatrix-csv-export.XXXXXX")"
  trap 'rm -f "$temp_file"' RETURN
  csv_export_stream > "$temp_file"
  chmod 0600 "$temp_file"
  mv -f "$temp_file" "$output"
  trap - RETURN
  echo "CSV export saved: file=$output mode=portable permissions=0600"
}

sequence_from_args() {
  if [[ "$#" -eq 0 ]]; then
    printf ""
    return
  fi
  if [[ "$#" -eq 1 ]]; then
    printf "%s" "$1"
    return
  fi
  local old_ifs="$IFS"
  IFS=','
  printf "%s" "$*"
  IFS="$old_ifs"
}

normalize_csv_action() {
  local action="${1:-A}"
  case "$action" in
    A|a|ADD|add) printf "A" ;;
    M|m|MODIFY|modify|MOD|mod) printf "M" ;;
    D|d|DELETE|delete|DEL|del) printf "D" ;;
    *) die "CSV action must be A, M, or D: $action" ;;
  esac
}

normalize_method() {
  local method="${1:-}"
  method="${method#MFA:}"
  method="${method#mfa:}"
  case "$method" in
    GraphicalMatrix|graphicalmatrix|GRAPHICALMATRIX) printf "GraphicalMatrix" ;;
    TOTP|totp) printf "TOTP" ;;
    WebAuthn|webauthn|WEBAUTHN) printf "WebAuthn" ;;
    *) die "invalid MFA method: ${1:-}" ;;
  esac
}

validate_totp_seed() {
  [[ "${1:-}" =~ ^[A-Z2-7]+=*$ ]] || die "invalid TOTP seed. Use uppercase Base32."
}

db_driver="$(prop 'graphicalmatrix.db.driver' 'org.h2.Driver')"
db_url="$(prop 'graphicalmatrix.db.url' "jdbc:h2:file:$IDP_HOME/credentials/graphicalmatrix;MODE=PostgreSQL;DATABASE_TO_UPPER=false")"
db_user="$(prop 'graphicalmatrix.db.user' 'sa')"
db_password="$(prop 'graphicalmatrix.db.password' '')"
db_password_file="$(prop 'graphicalmatrix.db.passwordFile' '')"

if [[ -z "$db_password" && -n "$db_password_file" ]]; then
  [[ -r "$db_password_file" ]] || die "DB password file is not readable: $db_password_file"
  db_password="$(tr -d '\r\n' < "$db_password_file")"
fi

if [[ "$db_driver" == *postgresql* || "$db_url" == jdbc:postgresql:* ]]; then
  db_type="postgresql"
else
  db_type="h2"
fi

db_auto_init_default="true"
if [[ "$db_type" == "postgresql" ]]; then
  db_auto_init_default="false"
fi
db_auto_init="$(prop 'graphicalmatrix.db.autoInit' "$db_auto_init_default" | tr '[:upper:]' '[:lower:]')"

jdbc_to_psql_url() {
  local url="$1"
  url="${url#jdbc:}"
  printf "%s" "$url"
}

postgresql_client() {
  local candidate
  if [[ -n "${PSQL_BIN:-}" ]]; then
    if [[ "$PSQL_BIN" == */* ]]; then
      [[ -x "$PSQL_BIN" ]] || die "psql not executable: $PSQL_BIN"
      printf '%s\n' "$PSQL_BIN"
      return
    fi
    candidate="$(command -v "$PSQL_BIN" 2>/dev/null || true)"
    [[ -n "$candidate" ]] || die "psql not found: $PSQL_BIN"
    printf '%s\n' "$candidate"
    return
  fi

  candidate="$(command -v psql 2>/dev/null || true)"
  if [[ -n "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return
  fi

  candidate="$(compgen -G '/usr/pgsql-*/bin/psql' | sort -V | tail -n 1 || true)"
  [[ -n "$candidate" && -x "$candidate" ]] || die "psql not found"
  printf '%s\n' "$candidate"
}

run_sql_h2_as_current_user() {
  java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "$db_url" \
    -user "$db_user" \
    -password "$db_password" \
    -sql "$1"
}

run_sql_h2() {
  [[ -s "$H2_JAR" ]] || die "H2 jar not found: $H2_JAR"
  if [[ "$(id -un)" == "jetty" ]]; then
    run_sql_h2_as_current_user "$1"
  else
    sudo -u jetty env H2_JAR="$H2_JAR" DB_URL="$db_url" DB_USER="$db_user" DB_PASSWORD="$db_password" \
      bash -c 'java -cp "$H2_JAR" org.h2.tools.Shell -url "$DB_URL" -user "$DB_USER" -password "$DB_PASSWORD" -sql "$1"' _ "$1"
  fi
}

run_sql_postgresql() {
  local psql_bin
  psql_bin="$(postgresql_client)"
  PGPASSWORD="$db_password" PGOPTIONS="--client-min-messages=warning" \
    "$psql_bin" -q "$(jdbc_to_psql_url "$db_url")" \
    -U "$db_user" \
    -v ON_ERROR_STOP=1 \
    -P null=null \
    -c "$1"
}

run_sql() {
  if [[ "$db_type" == "postgresql" ]]; then
    run_sql_postgresql "$1"
  else
    run_sql_h2 "$1"
  fi
}

run_sql_file_h2_as_current_user() {
  java -cp "$H2_JAR" org.h2.tools.RunScript \
    -url "$db_url" \
    -user "$db_user" \
    -password "$db_password" \
    -script "$1"
}

run_sql_file_h2() {
  local sql_file="$1"
  [[ -s "$H2_JAR" ]] || die "H2 jar not found: $H2_JAR"
  if [[ "$(id -un)" == "jetty" ]]; then
    run_sql_file_h2_as_current_user "$sql_file"
  else
    sudo -u jetty env H2_JAR="$H2_JAR" DB_URL="$db_url" DB_USER="$db_user" DB_PASSWORD="$db_password" SQL_FILE="$sql_file" \
      bash -c 'java -cp "$H2_JAR" org.h2.tools.RunScript -url "$DB_URL" -user "$DB_USER" -password "$DB_PASSWORD" -script "$SQL_FILE"'
  fi
}

run_sql_file_postgresql() {
  local sql_file="$1"
  local psql_bin
  psql_bin="$(postgresql_client)"
  PGPASSWORD="$db_password" PGOPTIONS="--client-min-messages=warning" \
    "$psql_bin" -q "$(jdbc_to_psql_url "$db_url")" \
    -U "$db_user" \
    -v ON_ERROR_STOP=1 \
    -P null=null \
    -f "$sql_file"
}

run_sql_file() {
  if [[ "$db_type" == "postgresql" ]]; then
    run_sql_file_postgresql "$1"
  else
    run_sql_file_h2 "$1"
  fi
}

run_scalar_postgresql() {
  local psql_bin
  psql_bin="$(postgresql_client)"
  PGPASSWORD="$db_password" PGOPTIONS="--client-min-messages=warning" \
    "$psql_bin" -qAt "$(jdbc_to_psql_url "$db_url")" \
    -U "$db_user" \
    -v ON_ERROR_STOP=1 \
    -c "$1"
}

run_scalar_h2_as_current_user() {
  java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "$db_url" \
    -user "$db_user" \
    -password "$db_password" \
    -sql "$1" \
    | awk 'NF && $0 !~ /^initial_sequence[[:space:]]*$/ && $0 !~ /^\([0-9]+ row/ && $0 !~ /^\(Update count:/ { print; exit }'
}

run_scalar_h2() {
  [[ -s "$H2_JAR" ]] || die "H2 jar not found: $H2_JAR"
  if [[ "$(id -un)" == "jetty" ]]; then
    run_scalar_h2_as_current_user "$1"
  else
    sudo -u jetty env H2_JAR="$H2_JAR" DB_URL="$db_url" DB_USER="$db_user" DB_PASSWORD="$db_password" \
      bash -c 'java -cp "$H2_JAR" org.h2.tools.Shell -url "$DB_URL" -user "$DB_USER" -password "$DB_PASSWORD" -sql "$1" | awk '"'"'NF && $0 !~ /^initial_sequence[[:space:]]*$/ && $0 !~ /^\\([0-9]+ row/ && $0 !~ /^\\(Update count:/ { print; exit }'"'"'' _ "$1"
  fi
}

run_scalar() {
  if [[ "$db_type" == "postgresql" ]]; then
    run_scalar_postgresql "$1"
  else
    run_scalar_h2 "$1"
  fi
}

init_sql="
CREATE TABLE IF NOT EXISTS graphicalmatrix_enrollment (
  user_id VARCHAR(255) PRIMARY KEY,
  sequence VARCHAR(1024) NOT NULL,
  initial_sequence VARCHAR(1024) NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  failed_count INT NOT NULL DEFAULT 0,
  locked_until BIGINT NOT NULL DEFAULT 0,
  mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix',
  totp_seed VARCHAR(255),
  totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED',
  totp_registered_at BIGINT NOT NULL DEFAULT 0,
  last_success_at BIGINT NOT NULL DEFAULT 0,
  force_sequence_change INT NOT NULL DEFAULT 0,
  state_version BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS last_success_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix';
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS totp_seed VARCHAR(255);
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED';
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS totp_registered_at BIGINT NOT NULL DEFAULT 0;"
init_sql="$init_sql
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS force_sequence_change INT NOT NULL DEFAULT 0;"
init_sql="$init_sql
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS initial_sequence VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE graphicalmatrix_enrollment
  ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;"

case "$db_auto_init" in
  1|true|yes|on) ;;
  *) init_sql="" ;;
esac

if [[ "$db_type" == "postgresql" ]]; then
  now_expr="(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint"
  select_public_columns="
SELECT user_id, mfa_method, force_sequence_change, initial_sequence, sequence, status, failed_count,
       CASE WHEN locked_until = 0 THEN NULL
            ELSE to_char(to_timestamp(locked_until / 1000.0) AT TIME ZONE 'Asia/Tokyo',
                         'YYYY/MM/DD HH24:MI:SS') END AS locked_until_jst,
       totp_status,
       CASE WHEN totp_registered_at = 0 THEN NULL
            ELSE to_char(to_timestamp(totp_registered_at / 1000.0) AT TIME ZONE 'Asia/Tokyo',
                         'YYYY/MM/DD HH24:MI:SS') END AS totp_registered_at_jst,
       CASE WHEN last_success_at = 0 THEN NULL
            ELSE to_char(to_timestamp(last_success_at / 1000.0) AT TIME ZONE 'Asia/Tokyo',
                         'YYYY/MM/DD HH24:MI:SS') END AS last_success_at_jst,
       to_char(to_timestamp(created_at / 1000.0) AT TIME ZONE 'Asia/Tokyo',
               'YYYY/MM/DD HH24:MI:SS') AS created_at_jst,
       to_char(to_timestamp(updated_at / 1000.0) AT TIME ZONE 'Asia/Tokyo',
               'YYYY/MM/DD HH24:MI:SS') AS updated_at_jst,
       CASE WHEN totp_seed IS NULL OR totp_seed = '' THEN 'NO' ELSE 'YES' END AS totp_seed_set
FROM graphicalmatrix_enrollment"
  totp_seed_status_expr="CASE WHEN totp_status = 'ACTIVE' THEN 'ACTIVE' ELSE 'PENDING' END"
else
  now_expr="DATEDIFF('MILLISECOND', TIMESTAMP '1970-01-01 00:00:00', CURRENT_TIMESTAMP)"
  select_public_columns="
SELECT user_id, mfa_method, force_sequence_change, initial_sequence, sequence, status, failed_count,
       CASEWHEN(locked_until = 0, NULL,
         FORMATDATETIME(
           DATEADD('HOUR', 9,
             DATEADD('MILLISECOND', locked_until, TIMESTAMP '1970-01-01 00:00:00')),
           'yyyy/MM/dd HH:mm:ss')) AS locked_until_jst,
       totp_status,
       CASEWHEN(totp_registered_at = 0, NULL,
         FORMATDATETIME(
           DATEADD('HOUR', 9,
             DATEADD('MILLISECOND', totp_registered_at, TIMESTAMP '1970-01-01 00:00:00')),
           'yyyy/MM/dd HH:mm:ss')) AS totp_registered_at_jst,
       CASEWHEN(last_success_at = 0, NULL,
         FORMATDATETIME(
           DATEADD('HOUR', 9,
             DATEADD('MILLISECOND', last_success_at, TIMESTAMP '1970-01-01 00:00:00')),
           'yyyy/MM/dd HH:mm:ss')) AS last_success_at_jst,
       FORMATDATETIME(
         DATEADD('HOUR', 9,
           DATEADD('MILLISECOND', created_at, TIMESTAMP '1970-01-01 00:00:00')),
         'yyyy/MM/dd HH:mm:ss') AS created_at_jst,
       FORMATDATETIME(
         DATEADD('HOUR', 9,
           DATEADD('MILLISECOND', updated_at, TIMESTAMP '1970-01-01 00:00:00')),
         'yyyy/MM/dd HH:mm:ss') AS updated_at_jst,
       CASEWHEN(totp_seed IS NULL OR totp_seed = '', 'NO', 'YES') AS totp_seed_set
FROM graphicalmatrix_enrollment"
  totp_seed_status_expr="CASEWHEN(totp_status = 'ACTIVE', 'ACTIVE', 'PENDING')"
fi

upsert_sequence_sql() {
  local user_q="$1"
  local sequence_q="$2"
  local initial_sequence_q="${3:-$sequence_q}"
  if [[ "$db_type" == "postgresql" ]]; then
    cat <<SQL
INSERT INTO graphicalmatrix_enrollment
  (user_id, sequence, initial_sequence, status, failed_count, locked_until, mfa_method, totp_seed,
   totp_status, totp_registered_at, last_success_at, force_sequence_change, state_version,
   created_at, updated_at)
VALUES
  ('$user_q', '$sequence_q', '$initial_sequence_q', 'ACTIVE', 0, 0,
   COALESCE((SELECT mfa_method FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'), 'GraphicalMatrix'),
   (SELECT totp_seed FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'),
   COALESCE((SELECT totp_status FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'), 'UNREGISTERED'),
   COALESCE((SELECT totp_registered_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'), 0),
   COALESCE((SELECT last_success_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'), 0),
   COALESCE((SELECT force_sequence_change FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'), 0),
   COALESCE((SELECT state_version FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'), 0) + 1,
   COALESCE((SELECT created_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'), $now_expr),
   $now_expr)
ON CONFLICT (user_id) DO UPDATE
SET sequence = EXCLUDED.sequence,
    initial_sequence = CASE
      WHEN graphicalmatrix_enrollment.initial_sequence IS NULL
           OR graphicalmatrix_enrollment.initial_sequence = ''
      THEN EXCLUDED.initial_sequence
      ELSE graphicalmatrix_enrollment.initial_sequence
    END,
    status = 'ACTIVE',
    failed_count = 0,
    locked_until = 0,
    state_version = graphicalmatrix_enrollment.state_version + 1,
    updated_at = $now_expr;
SQL
  else
    cat <<SQL
MERGE INTO graphicalmatrix_enrollment
  (user_id, sequence, initial_sequence, status, failed_count, locked_until, mfa_method, totp_seed,
   totp_status, totp_registered_at, last_success_at, force_sequence_change, state_version,
   created_at, updated_at)
KEY(user_id)
VALUES
  ('$user_q', '$sequence_q',
   CASEWHEN((SELECT initial_sequence FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL
      OR (SELECT initial_sequence FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') = '',
     '$initial_sequence_q',
     (SELECT initial_sequence FROM graphicalmatrix_enrollment WHERE user_id = '$user_q')),
   'ACTIVE', 0, 0,
   CASEWHEN((SELECT mfa_method FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL,
     'GraphicalMatrix',
     (SELECT mfa_method FROM graphicalmatrix_enrollment WHERE user_id = '$user_q')),
   (SELECT totp_seed FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'),
   CASEWHEN((SELECT totp_status FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL,
     'UNREGISTERED',
     (SELECT totp_status FROM graphicalmatrix_enrollment WHERE user_id = '$user_q')),
   CASEWHEN((SELECT totp_registered_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL,
     0,
     (SELECT totp_registered_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q')),
   CASEWHEN((SELECT last_success_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL,
     0,
     (SELECT last_success_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q')),
   CASEWHEN((SELECT force_sequence_change FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL,
     0,
     (SELECT force_sequence_change FROM graphicalmatrix_enrollment WHERE user_id = '$user_q')),
   CASEWHEN((SELECT state_version FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL,
     1,
     (SELECT state_version FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') + 1),
   CASEWHEN((SELECT created_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') IS NULL,
     $now_expr,
     (SELECT created_at FROM graphicalmatrix_enrollment WHERE user_id = '$user_q')),
   $now_expr);
SQL
  fi
}

csv_expect_exists_sql() {
  local user_q="$1"
  if [[ "$db_type" == "postgresql" ]]; then
    cat <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') THEN
    RAISE EXCEPTION 'CSV MODIFY/DELETE user does not exist: $user_q';
  END IF;
END
\$\$;
SQL
  else
    cat <<SQL
SELECT CASEWHEN(EXISTS (
  SELECT 1 FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'
), 0, 1/0) AS csv_user_must_exist;
SQL
  fi
}

csv_expect_not_exists_sql() {
  local user_q="$1"
  if [[ "$db_type" == "postgresql" ]]; then
    cat <<SQL
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM graphicalmatrix_enrollment WHERE user_id = '$user_q') THEN
    RAISE EXCEPTION 'CSV ADD user already exists: $user_q';
  END IF;
END
\$\$;
SQL
  else
    cat <<SQL
SELECT CASEWHEN(EXISTS (
  SELECT 1 FROM graphicalmatrix_enrollment WHERE user_id = '$user_q'
), 1/0, 0) AS csv_user_must_not_exist;
SQL
  fi
}

csv_import() {
  local csv_file="$1"
  local apply_mode="${2:-0}"
  local provisioning_mode="${3:-0}"
  local csv_input="$csv_file"
  if [[ "$csv_file" == "-" ]]; then
    csv_input="$(mktemp)"
    cat > "$csv_input"
  else
    [[ -r "$csv_file" ]] || die "CSV file is not readable: $csv_file"
  fi

  command -v python3 >/dev/null 2>&1 || die "python3 is required for CSV import"

  local choice
  choice="$(config_prop 'graphicalmatrix.choice' '4')"
  [[ "$choice" =~ ^[0-9]+$ && "$choice" -gt 0 ]] || die "invalid graphicalmatrix.choice: $choice"

  local sql_file rows_file preview_file
  sql_file="$(mktemp)"
  rows_file="$(mktemp)"
  preview_file="$(mktemp)"
  trap 'rm -f "$sql_file" "$rows_file" "$preview_file"; [[ "$csv_file" == "-" ]] && rm -f "$csv_input"' RETURN

  {
    echo "BEGIN;"
    echo "$init_sql"
  } > "$sql_file"

  python3 - "$csv_input" > "$rows_file" <<'PY'
import csv
import sys

path = sys.argv[1]
sep = "\x1f"
with open(path, newline="") as handle:
    reader = csv.reader(handle)
    for line_no, row in enumerate(reader, start=1):
        if not row or all(not cell.strip() for cell in row):
            continue
        if row[0].lstrip().startswith("#"):
            continue
        if any(sep in cell for cell in row):
            raise SystemExit(f"CSV line {line_no} contains unsupported control character")
        print(str(line_no) + sep + sep.join(cell.strip() for cell in row))
PY

  local row line_no=0 imported=0 skipped=0 add_count=0 modify_count=0 delete_count=0
  local fields action user method force_value initial_sequence sequence
  local first field_count

  append_import_sql() {
    local action="$1"
    local user="$2"
    local method="${3:-}"
    local force_value="${4:-}"
    local initial_sequence="${5:-}"
    local sequence="${6:-}"
    local normalized_initial_sequence resolved_sequence stored_sequence preview_sequence
    local user_q sequence_q initial_sequence_q method_q force_sql

    action="$(normalize_csv_action "$action")"
    validate_user "$user"
    user_q="$(sql_quote "$user")"

    if [[ "$action" == "D" ]]; then
      if [[ "$provisioning_mode" == "1" ]]; then
        printf "line=%s action=D user_id=%s deprovision=disable\n" "$line_no" "$user" >> "$preview_file"
      else
        printf "line=%s action=D user_id=%s\n" "$line_no" "$user" >> "$preview_file"
      fi
      delete_count=$((delete_count + 1))
      {
        csv_expect_exists_sql "$user_q"
        if [[ "$provisioning_mode" == "1" ]]; then
          echo "UPDATE graphicalmatrix_enrollment"
          echo "SET status = 'DISABLED',"
          echo "    failed_count = 0,"
          echo "    locked_until = 0,"
          echo "    state_version = state_version + 1,"
          echo "    updated_at = $now_expr"
          echo "WHERE user_id = '$user_q';"
        else
          echo "DELETE FROM graphicalmatrix_enrollment WHERE user_id = '$user_q';"
        fi
      } >> "$sql_file"
      return
    fi

    [[ -n "$method" ]] || die "CSV line $line_no has empty mfa_method for action $action"
    [[ -n "$force_value" ]] || die "CSV line $line_no has empty force_sequence_change for action $action"
    if [[ -z "$sequence" && -n "$initial_sequence" ]]; then
      sequence="$initial_sequence"
    fi
    [[ -n "$sequence" ]] || die "CSV line $line_no has empty sequence for action $action"
    method="$(normalize_method "$method")"
    force_sql="$(normalize_force_value "$force_value")"
    validate_sequence "$sequence"
    if [[ -z "$initial_sequence" ]]; then
      initial_sequence="$sequence"
    fi
    validate_sequence "$initial_sequence"
    normalized_initial_sequence="$(resolve_sequence_to_graphicals "$initial_sequence")"
    resolved_sequence="$(resolve_sequence_to_graphicals "$sequence")"
    validate_sequence "$resolved_sequence"
    stored_sequence="$(sequence_storage_encode "$resolved_sequence")"
    preview_sequence="$stored_sequence"
    if [[ "$(sequence_storage_mode)" == "plaintext" ]]; then
      preview_sequence="[plaintext-not-shown]"
    fi

    sequence_q="$(sql_quote "$stored_sequence")"
    initial_sequence_q="$(sql_quote "$(initial_sequence_plaintext "$normalized_initial_sequence")")"
    method_q="$(sql_quote "$method")"
    printf "line=%s action=%s user_id=%s mfa_method=%s force_sequence_change=%s initial_sequence=%s sequence=%s\n" \
      "$line_no" "$action" "$user" "$method" "$force_value" "$normalized_initial_sequence" "$preview_sequence" >> "$preview_file"
    if [[ "$action" == "A" ]]; then
      add_count=$((add_count + 1))
    else
      modify_count=$((modify_count + 1))
    fi
    {
      if [[ "$action" == "A" ]]; then
        if [[ "$provisioning_mode" != "1" ]]; then
          csv_expect_not_exists_sql "$user_q"
        fi
        upsert_sequence_sql "$user_q" "$sequence_q" "$initial_sequence_q"
        echo "UPDATE graphicalmatrix_enrollment"
        echo "SET initial_sequence = '$initial_sequence_q',"
        echo "    mfa_method = '$method_q',"
        echo "    force_sequence_change = $force_sql,"
        echo "    status = 'ACTIVE',"
        echo "    failed_count = 0,"
        echo "    locked_until = 0,"
        echo "    state_version = state_version + 1,"
        echo "    updated_at = $now_expr"
        echo "WHERE user_id = '$user_q';"
      else
        csv_expect_exists_sql "$user_q"
        echo "UPDATE graphicalmatrix_enrollment"
        echo "SET sequence = '$sequence_q',"
        echo "    initial_sequence = '$initial_sequence_q',"
        echo "    mfa_method = '$method_q',"
        echo "    force_sequence_change = $force_sql,"
        echo "    state_version = state_version + 1,"
        echo "    updated_at = $now_expr"
        echo "WHERE user_id = '$user_q';"
      fi
    } >> "$sql_file"
  }

  while IFS= read -r row || [[ -n "$row" ]]; do
    IFS=$'\x1f' read -r -a fields <<< "$row"
    line_no="${fields[0]:-0}"
    unset 'fields[0]'
    fields=("${fields[@]}")
    field_count="${#fields[@]}"

    first="${fields[0]:-}"
    if [[ "$(printf "%s" "$first" | tr '[:upper:]' '[:lower:]')" == "action" ]]; then
      skipped=$((skipped + 1))
      continue
    fi

    action="$(normalize_csv_action "${fields[0]:-}")"
    user="${fields[1]:-}"
    method=""
    force_value=""
    initial_sequence=""
    sequence=""

    if [[ "$action" != "D" ]]; then
      [[ "$field_count" -ge 6 ]] \
        || die "CSV line $line_no has too few columns: got $field_count, expected 6"
      method="${fields[2]:-}"
      force_value="${fields[3]:-}"
      initial_sequence="${fields[4]:-}"
      sequence="${fields[5]:-}"
    fi
    append_import_sql "$action" "$user" "$method" "$force_value" "$initial_sequence" "$sequence"

    imported=$((imported + 1))
  done < "$rows_file"

  (( imported > 0 )) || die "CSV file has no import rows: $csv_file"
  echo "COMMIT;" >> "$sql_file"
  chmod 0600 "$sql_file"

  echo "CSV import plan:"
  echo "  file: $csv_file"
  echo "  db: $db_type"
  echo "  mode: $([[ "$provisioning_mode" == "1" ]] && echo provisioning || echo standard)"
  echo "  rows: $imported"
  echo "  skipped: $skipped"
  echo "  add: $add_count"
  echo "  modify: $modify_count"
  if [[ "$provisioning_mode" == "1" ]]; then
    echo "  disable: $delete_count"
  else
    echo "  delete: $delete_count"
  fi
  echo
  cat "$preview_file"
  echo
  echo "DB existence checks are executed when --apply runs:"
  if [[ "$provisioning_mode" == "1" ]]; then
    echo "  A inserts or reactivates/updates an existing user."
    echo "  D disables the user instead of deleting the row."
  else
    echo "  A fails if the user already exists."
    echo "  D deletes the user row."
  fi
  echo "  M/D fail if the user does not exist."
  echo

  if [[ "$apply_mode" != "1" ]]; then
    echo "Dry-run only. Re-run with --apply to update the DB."
    return 0
  fi

  run_sql_file "$sql_file"
  echo "CSV import completed: file=$csv_file rows=$imported skipped=$skipped mode=$([[ "$provisioning_mode" == "1" ]] && echo provisioning || echo standard) db=$db_type"
}

require_postgresql_webauthn() {
  [[ "$db_type" == "postgresql" ]] \
    || die "WebAuthn credential management requires PostgreSQL StorageRecords"
}

validate_credential_id() {
  [[ "${1:-}" =~ ^[A-Za-z0-9._~+/-]+={0,2}$ ]] || die "invalid credential id: ${1:-}"
}

webauthn_user_match_sql() {
  local user_q="$1"
  cat <<SQL
(
     s.id = '$user_q'
  OR elem ->> 'username' = '$user_q'
  OR elem -> 'userIdentity' ->> 'name' = '$user_q'
  OR elem -> 'userIdentity' ->> 'id' = '$user_q'
)
SQL
}

webauthn_credential_array_sql() {
  cat <<'SQL'
CASE
  WHEN jsonb_typeof(s.value::jsonb) = 'array' THEN s.value::jsonb
  ELSE '[]'::jsonb
END
SQL
}

webauthn_list() {
  require_postgresql_webauthn
  local user="${1:-}"
  local where_sql=""
  if [[ -n "$user" ]]; then
    validate_user "$user"
    local user_q
    user_q="$(sql_quote "$user")"
    where_sql="WHERE $(webauthn_user_match_sql "$user_q")"
  fi

  run_sql "
WITH expanded AS (
  SELECT
    s.context,
    s.id,
    s.expires,
    s.version,
    length(s.value) AS value_length,
    elem
  FROM storagerecords s
  CROSS JOIN LATERAL jsonb_array_elements($(webauthn_credential_array_sql)) AS elem
)
SELECT
  context,
  id AS storage_id,
  COALESCE(elem ->> 'username', elem -> 'userIdentity' ->> 'name') AS username,
  elem ->> 'nickname' AS nickname,
  elem -> 'credential' ->> 'credentialId' AS credential_id,
  elem -> 'credential' ->> 'signatureCount' AS signature_count,
  elem ->> 'userVerified' AS user_verified,
  CASE
    WHEN elem ->> 'registrationTime' ~ '^[0-9]+(\\.[0-9]+)?$'
    THEN to_char(to_timestamp((elem ->> 'registrationTime')::double precision) AT TIME ZONE 'Asia/Tokyo',
                 'YYYY/MM/DD HH24:MI:SS')
    ELSE NULL
  END AS registration_time_jst,
  version,
  value_length
FROM expanded s
$where_sql
ORDER BY context, storage_id, username, credential_id;"
}

webauthn_reset() {
  require_postgresql_webauthn
  local user="$1"
  local apply="$2"
  local set_method="${3:-}"
  validate_user "$user"
  [[ "$apply" == "1" || "$apply" == "0" ]] || die "internal error: invalid apply flag"

  local user_q method_sql=""
  user_q="$(sql_quote "$user")"

  if [[ -n "$set_method" ]]; then
    set_method="$(normalize_method "$set_method")"
    [[ "$set_method" == "GraphicalMatrix" ]] || die "webauthn-reset --set-method currently supports GraphicalMatrix only"
    method_sql="
UPDATE graphicalmatrix_enrollment
SET mfa_method = 'GraphicalMatrix', state_version = state_version + 1,
    updated_at = $now_expr
WHERE user_id = '$user_q';"
  fi

  if [[ "$apply" != "1" ]]; then
    echo "Dry-run: WebAuthn credentials that would be reset for user=$user"
    webauthn_list "$user"
    echo
    echo "No changes applied. Re-run with --apply to clear credentials."
    return
  fi

  run_sql "
WITH expanded AS (
  SELECT
    s.context,
    s.id,
    elem
  FROM storagerecords s
  LEFT JOIN LATERAL jsonb_array_elements($(webauthn_credential_array_sql)) AS elem ON true
),
target_rows AS (
  SELECT
    context,
    id,
    count(elem) FILTER (
      WHERE elem IS NOT NULL
        AND $(webauthn_user_match_sql "$user_q")
    ) AS credential_count
  FROM expanded s
  WHERE s.id = '$user_q'
     OR (
       elem IS NOT NULL
       AND $(webauthn_user_match_sql "$user_q")
     )
  GROUP BY context, id
),
updated AS (
  UPDATE storagerecords s
  SET value = '[]',
      version = s.version + 1
  FROM target_rows t
  WHERE s.context = t.context
    AND s.id = t.id
  RETURNING t.credential_count
)
SELECT
  count(*) AS storage_rows_reset,
  COALESCE(sum(credential_count), 0) AS credentials_reset
FROM updated;
$method_sql"
}

webauthn_delete_credential() {
  require_postgresql_webauthn
  local user="$1"
  local credential_id="$2"
  local apply="$3"
  validate_user "$user"
  validate_credential_id "$credential_id"
  [[ "$apply" == "1" || "$apply" == "0" ]] || die "internal error: invalid apply flag"

  local user_q credential_q
  user_q="$(sql_quote "$user")"
  credential_q="$(sql_quote "$credential_id")"

  if [[ "$apply" != "1" ]]; then
    echo "Dry-run: WebAuthn credential that would be deleted for user=$user credential_id=$credential_id"
    run_sql "
WITH expanded AS (
  SELECT s.context, s.id, s.version, length(s.value) AS value_length, elem
  FROM storagerecords s
  CROSS JOIN LATERAL jsonb_array_elements($(webauthn_credential_array_sql)) AS elem
)
SELECT
  context,
  id AS storage_id,
  COALESCE(elem ->> 'username', elem -> 'userIdentity' ->> 'name') AS username,
  elem ->> 'nickname' AS nickname,
  elem -> 'credential' ->> 'credentialId' AS credential_id,
  version,
  value_length
FROM expanded s
WHERE $(webauthn_user_match_sql "$user_q")
  AND elem -> 'credential' ->> 'credentialId' = '$credential_q'
ORDER BY context, storage_id;"
    echo
    echo "No changes applied. Re-run with --apply to delete this credential."
    return
  fi

  run_sql "
WITH expanded AS (
  SELECT
    s.context,
    s.id,
    elem
  FROM storagerecords s
  CROSS JOIN LATERAL jsonb_array_elements($(webauthn_credential_array_sql)) AS elem
),
target AS (
  SELECT
    context,
    id,
    COALESCE(
      jsonb_agg(elem) FILTER (
        WHERE NOT (
          $(webauthn_user_match_sql "$user_q")
          AND elem -> 'credential' ->> 'credentialId' = '$credential_q'
        )
      ),
      '[]'::jsonb
    ) AS new_value,
    count(*) FILTER (
      WHERE $(webauthn_user_match_sql "$user_q")
        AND elem -> 'credential' ->> 'credentialId' = '$credential_q'
    ) AS removed_count
  FROM expanded s
  GROUP BY context, id
),
updated AS (
  UPDATE storagerecords s
  SET value = target.new_value::text,
      version = s.version + 1
  FROM target
  WHERE s.context = target.context
    AND s.id = target.id
    AND target.removed_count > 0
  RETURNING target.removed_count
)
SELECT
  count(*) AS storage_rows_updated,
  COALESCE(sum(removed_count), 0) AS credentials_deleted
FROM updated;"
}

webauthn_delete_user_sql() {
  local user_q="$1"
  cat <<SQL
WITH expanded AS (
  SELECT
    s.context,
    s.id,
    elem
  FROM storagerecords s
  CROSS JOIN LATERAL jsonb_array_elements(
    CASE
      WHEN jsonb_typeof(s.value::jsonb) = 'array' THEN s.value::jsonb
      ELSE '[]'::jsonb
    END
  ) AS elem
),
target AS (
  SELECT
    context,
    id,
    COALESCE(
      jsonb_agg(elem) FILTER (
        WHERE NOT (
             id = '$user_q'
          OR elem ->> 'username' = '$user_q'
          OR elem -> 'userIdentity' ->> 'name' = '$user_q'
          OR elem -> 'userIdentity' ->> 'id' = '$user_q'
        )
      ),
      '[]'::jsonb
    ) AS new_value,
    count(*) FILTER (
      WHERE id = '$user_q'
         OR elem ->> 'username' = '$user_q'
         OR elem -> 'userIdentity' ->> 'name' = '$user_q'
         OR elem -> 'userIdentity' ->> 'id' = '$user_q'
    ) AS removed_count
  FROM expanded
  GROUP BY context, id
),
deleted AS (
  DELETE FROM storagerecords s
  USING target
  WHERE s.context = target.context
    AND s.id = target.id
    AND target.removed_count > 0
    AND (s.id = '$user_q' OR jsonb_array_length(target.new_value) = 0)
  RETURNING target.removed_count
),
updated AS (
  UPDATE storagerecords s
  SET value = target.new_value::text,
      version = s.version + 1
  FROM target
  WHERE s.context = target.context
    AND s.id = target.id
    AND target.removed_count > 0
    AND s.id <> '$user_q'
    AND jsonb_array_length(target.new_value) > 0
  RETURNING target.removed_count
)
SELECT
  (SELECT count(*) FROM deleted) AS storage_rows_deleted,
  (SELECT count(*) FROM updated) AS storage_rows_updated,
  COALESCE((SELECT sum(removed_count) FROM deleted), 0)
    + COALESCE((SELECT sum(removed_count) FROM updated), 0) AS credentials_deleted;
SQL
}

cmd="${1:-}"
if [[ "${2:-}" =~ ^[Rr][Ee][Ss][Ee][Tt]$ ]]; then
  cmd="reset-user"
  set -- "$cmd" "$1"
fi

case "$cmd" in
  config-help)
    config_help "${2:-}"
    ;;

  sequence-mode)
    sequence_storage_mode
    echo
    ;;

  security-status)
    security_status
    ;;

  totp-seed-mode)
    totp_seed_storage_mode
    echo
    ;;

  migrate-sequence-storage)
    case "${2:-}" in
      ""|--dry-run|plan)
        sequence_storage_migrate plan
        ;;
      --apply|apply)
        sequence_storage_migrate apply
        ;;
      *)
        die "unknown migrate-sequence-storage option: ${2:-}"
        ;;
    esac
    ;;

  migrate-totp-seed-storage)
    case "${2:-}" in
      ""|--dry-run|plan)
        totp_seed_storage_migrate plan
        ;;
      --apply|apply)
        totp_seed_storage_migrate apply
        ;;
      *)
        die "unknown migrate-totp-seed-storage option: ${2:-}"
        ;;
    esac
    ;;

  csv-export|export-csv)
    csv_output="${2:-}"
    [[ -n "$csv_output" ]] || die "CSV export file is required"
    csv_force=0
    case "${3:-}" in
      --force|-f) csv_force=1 ;;
      "") ;;
      *) die "unknown csv-export option: ${3:-}" ;;
    esac
    [[ "$csv_output" != "-" || "$csv_force" == "0" ]] || die "--force cannot be used with csv-export -"
    csv_export "$csv_output" "$csv_force"
    ;;

  list)
    [[ -z "$init_sql" ]] || run_sql "$init_sql" >/dev/null
    enrollment_list
    ;;

  show)
    user="${2:-}"
    validate_user "$user"
    [[ -z "$init_sql" ]] || run_sql "$init_sql" >/dev/null
    enrollment_list "$user"
    ;;

  add|set-sequence)
    user="${2:-}"
    sequence="$(sequence_from_args "${@:3}")"
    sequence="$(resolve_sequence_to_graphicals "$sequence")"
    validate_user "$user"
    validate_sequence "$sequence"
    user_q="$(sql_quote "$user")"
    sequence_q="$(sql_quote "$(sequence_storage_encode "$sequence")")"
    initial_sequence_q="$(sql_quote "$(initial_sequence_plaintext "$sequence")")"
    run_sql "$init_sql
$(upsert_sequence_sql "$user_q" "$sequence_q" "$initial_sequence_q")
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  csv|provisioning-csv)
    csv_file="${2:-}"
    [[ -n "$csv_file" ]] || die "CSV file is required"
    csv_apply=0
    csv_provisioning=0
    if [[ "$cmd" == "provisioning-csv" ]]; then
      csv_provisioning=1
    fi
    shift 2
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --apply|--yes|-y|YES)
          csv_apply=1
          shift
          ;;
        --provisioning)
          csv_provisioning=1
          shift
          ;;
        *)
          die "unknown csv option: $1"
          ;;
      esac
    done
    csv_import "$csv_file" "$csv_apply" "$csv_provisioning"
    ;;

  set-initial-sequence)
    user="${2:-}"
    sequence="$(sequence_from_args "${@:3}")"
    sequence="$(resolve_sequence_to_graphicals "$sequence")"
    validate_user "$user"
    validate_sequence "$sequence"
    user_q="$(sql_quote "$user")"
    initial_sequence_q="$(sql_quote "$(initial_sequence_plaintext "$sequence")")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET initial_sequence = '$initial_sequence_q',
    state_version = state_version + 1,
    updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  set-method)
    user="${2:-}"
    method="$(normalize_method "${3:-}")"
    validate_user "$user"
    user_q="$(sql_quote "$user")"
    method_q="$(sql_quote "$method")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET mfa_method = '$method_q', state_version = state_version + 1, updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  set-totp-seed)
    user="${2:-}"
    seed="${3:-}"
    validate_user "$user"
    validate_totp_seed "$seed"
    user_q="$(sql_quote "$user")"
    seed_q="$(sql_quote "$(totp_seed_storage_encode "$seed")")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET totp_seed = '$seed_q',
    totp_status = $totp_seed_status_expr,
    updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  clear-totp-seed|reset-totp)
    user="${2:-}"
    validate_user "$user"
    user_q="$(sql_quote "$user")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET totp_seed = NULL, totp_status = 'UNREGISTERED', totp_registered_at = 0,
    state_version = state_version + 1, updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  webauthn-list)
    user="${2:-}"
    if [[ -n "$user" ]]; then
      validate_user "$user"
    fi
    webauthn_list "$user"
    ;;

  webauthn-reset)
    user="${2:-}"
    [[ -n "$user" ]] || die "webauthn-reset requires USER"
    validate_user "$user"
    webauthn_apply=0
    webauthn_set_method=""
    shift 2
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --apply)
          webauthn_apply=1
          shift
          ;;
        --set-method)
          [[ -n "${2:-}" ]] || die "--set-method requires a method"
          webauthn_set_method="$2"
          shift 2
          ;;
        *)
          die "unknown webauthn-reset option: $1"
          ;;
      esac
    done
    webauthn_reset "$user" "$webauthn_apply" "$webauthn_set_method"
    ;;

  webauthn-delete)
    user="${2:-}"
    [[ -n "$user" ]] || die "webauthn-delete requires USER"
    validate_user "$user"
    webauthn_apply=0
    credential_id=""
    shift 2
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --credential-id)
          [[ -n "${2:-}" ]] || die "--credential-id requires a value"
          credential_id="$2"
          shift 2
          ;;
        --apply)
          webauthn_apply=1
          shift
          ;;
        *)
          die "unknown webauthn-delete option: $1"
          ;;
      esac
    done
    [[ -n "$credential_id" ]] || die "webauthn-delete requires --credential-id"
    webauthn_delete_credential "$user" "$credential_id" "$webauthn_apply"
    ;;

  require-change)
    user="${2:-}"
    value="${3:-}"
    validate_user "$user"
    case "$value" in
      1|on|ON|true|TRUE|yes|YES) force_value=1 ;;
      0|off|OFF|false|FALSE|no|NO) force_value=0 ;;
      *) die "require-change value must be on or off" ;;
    esac
    user_q="$(sql_quote "$user")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET force_sequence_change = $force_value, state_version = state_version + 1, updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  reset-user)
    user="${2:-}"
    validate_user "$user"
    user_q="$(sql_quote "$user")"
    initial_sequence="$(run_scalar "
SELECT initial_sequence AS initial_sequence
FROM graphicalmatrix_enrollment
WHERE user_id = '$user_q';" | tail -n 1 | trim)"
    [[ -n "$initial_sequence" ]] || die "initial_sequence is empty for user: $user"
    validate_sequence "$initial_sequence"
    sequence="$(resolve_sequence_to_graphicals "$initial_sequence")"
    sequence_q="$(sql_quote "$(sequence_storage_encode "$sequence")")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET mfa_method = 'GraphicalMatrix',
    sequence = '$sequence_q',
    status = 'ACTIVE',
    failed_count = 0,
    locked_until = 0,
    force_sequence_change = 1,
    totp_seed = NULL,
    totp_status = 'UNREGISTERED',
    totp_registered_at = 0,
    state_version = state_version + 1,
    updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  enable)
    user="${2:-}"
    validate_user "$user"
    user_q="$(sql_quote "$user")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET status = 'ACTIVE', state_version = state_version + 1, updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  disable)
    user="${2:-}"
    validate_user "$user"
    user_q="$(sql_quote "$user")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET status = 'DISABLED', state_version = state_version + 1, updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  unlock|reset-failures)
    user="${2:-}"
    validate_user "$user"
    user_q="$(sql_quote "$user")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET failed_count = 0, locked_until = 0,
    state_version = state_version + 1, updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  lock)
    user="${2:-}"
    minutes="${3:-}"
    validate_user "$user"
    [[ "$minutes" =~ ^[0-9]+$ ]] || die "minutes must be a non-negative integer"
    user_q="$(sql_quote "$user")"
    run_sql "$init_sql
UPDATE graphicalmatrix_enrollment
SET locked_until = $now_expr + ($minutes * 60 * 1000),
    state_version = state_version + 1,
    updated_at = $now_expr
WHERE user_id = '$user_q';
$select_public_columns
WHERE user_id = '$user_q';"
    ;;

  delete)
    user="${2:-}"
    validate_user "$user"
    user_q="$(sql_quote "$user")"
    with_webauthn=0
    with_webauthn_apply=0
    shift 2 || true
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --with-webauthn)
          with_webauthn=1
          shift
          ;;
        --apply)
          with_webauthn_apply=1
          shift
          ;;
        *)
          die "unknown delete option: $1"
          ;;
      esac
    done
    if [[ "$with_webauthn" == "1" && "$with_webauthn_apply" != "1" ]]; then
      die "delete USER --with-webauthn requires --apply"
    fi
    webauthn_delete_sql=""
    if [[ "$with_webauthn" == "1" ]]; then
      require_postgresql_webauthn
      webauthn_delete_sql="$(webauthn_delete_user_sql "$user_q")"
    fi
    run_sql "$init_sql
DELETE FROM graphicalmatrix_enrollment WHERE user_id = '$user_q';
$webauthn_delete_sql
$select_public_columns
ORDER BY user_id;"
    ;;

  -h|--help|help|"")
    usage
    ;;

  *)
    usage
    die "unknown command: $cmd"
    ;;
esac
