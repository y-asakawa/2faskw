#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
H2_JAR=""
H2_PROPERTIES=""
H2_URL=""
H2_USER=""
H2_PASSWORD=""
H2_PASSWORD_FILE=""
PG_PROPERTIES=""
PG_SCHEMA=""
INPUT_FILE=""
OUTPUT_FILE=""
APPLY=0
TRUNCATE=0

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-db-migration.sh h2-count [options]
  graphicalmatrix-db-migration.sh h2-export --output FILE [options]
  graphicalmatrix-db-migration.sh pg-count [options]
  graphicalmatrix-db-migration.sh pg-apply-schema [--apply] [options]
  graphicalmatrix-db-migration.sh pg-import --input FILE [--apply] [--truncate] [options]
  graphicalmatrix-db-migration.sh pg-verify --input FILE [options]

Commands:
  h2-count         Count source H2 rows.
  h2-export        Export source H2 enrollment rows to CSV.
  pg-count         Count target PostgreSQL rows.
  pg-apply-schema  Apply PostgreSQL schema. Dry-run unless --apply is set.
  pg-import        Import CSV into PostgreSQL by upsert. Dry-run unless --apply is set.
  pg-verify        Compare CSV row count and user_id checksum with PostgreSQL.

Options:
  --idp-home DIR          Shibboleth IdP home. Default: /opt/shibboleth-idp
  --h2-jar FILE           H2 jar. Default: $IDP_HOME/edit-webapp/WEB-INF/lib/h2-2.2.224.jar
  --h2-properties FILE    Source H2 db.properties. Optional.
  --h2-url URL            Source H2 JDBC URL.
  --h2-user USER          Source H2 user. Default: sa
  --h2-password VALUE     Source H2 password.
  --h2-password-file FILE Source H2 password file.
  --pg-properties FILE    Target PostgreSQL db.properties.
                           Default: $IDP_HOME/conf/graphicalmatrix/db.properties
  --pg-schema FILE        PostgreSQL schema SQL.
                           Default: $IDP_HOME/conf/graphicalmatrix/postgresql-schema.sql
  --input FILE            Migration CSV input.
  --output FILE           Migration CSV output.
  --apply                 Actually apply PostgreSQL schema/import.
  --truncate              Delete target rows before pg-import. Use carefully.

Notes:
  - h2-export CSV contains sequence and TOTP seed data. Store it as a secret.
  - pg-import uses PostgreSQL COPY through psql and upserts by user_id.
  - Stop Jetty or put the IdP in maintenance before the final export/import.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

trim() {
  sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

prop_file() {
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

jdbc_to_psql_url() {
  local url="$1"
  url="${url#jdbc:}"
  printf "%s" "$url"
}

resolve_defaults() {
  H2_JAR="${H2_JAR:-$IDP_HOME/edit-webapp/WEB-INF/lib/h2-2.2.224.jar}"
  PG_PROPERTIES="${PG_PROPERTIES:-$IDP_HOME/conf/graphicalmatrix/db.properties}"
  PG_SCHEMA="${PG_SCHEMA:-$IDP_HOME/conf/graphicalmatrix/postgresql-schema.sql}"

  local default_h2_url="jdbc:h2:file:$IDP_HOME/credentials/graphicalmatrix;MODE=PostgreSQL;DATABASE_TO_UPPER=false"
  H2_URL="${H2_URL:-$(prop_file "$H2_PROPERTIES" 'graphicalmatrix.db.url' "$default_h2_url")}"
  H2_USER="${H2_USER:-$(prop_file "$H2_PROPERTIES" 'graphicalmatrix.db.user' 'sa')}"
  H2_PASSWORD="${H2_PASSWORD:-$(prop_file "$H2_PROPERTIES" 'graphicalmatrix.db.password' '')}"
  H2_PASSWORD_FILE="${H2_PASSWORD_FILE:-$(prop_file "$H2_PROPERTIES" 'graphicalmatrix.db.passwordFile' '')}"
  if [[ -z "$H2_PASSWORD" && -n "$H2_PASSWORD_FILE" ]]; then
    [[ -r "$H2_PASSWORD_FILE" ]] || die "H2 password file is not readable: $H2_PASSWORD_FILE"
    H2_PASSWORD="$(tr -d '\r\n' < "$H2_PASSWORD_FILE")"
  fi

  PG_URL="$(prop_file "$PG_PROPERTIES" 'graphicalmatrix.db.url' '')"
  PG_USER="$(prop_file "$PG_PROPERTIES" 'graphicalmatrix.db.user' '')"
  PG_PASSWORD="$(prop_file "$PG_PROPERTIES" 'graphicalmatrix.db.password' '')"
  PG_PASSWORD_FILE="$(prop_file "$PG_PROPERTIES" 'graphicalmatrix.db.passwordFile' '')"
  if [[ -z "$PG_PASSWORD" && -n "$PG_PASSWORD_FILE" ]]; then
    [[ -r "$PG_PASSWORD_FILE" ]] || die "PostgreSQL password file is not readable: $PG_PASSWORD_FILE"
    PG_PASSWORD="$(tr -d '\r\n' < "$PG_PASSWORD_FILE")"
  fi
}

require_h2() {
  [[ -s "$H2_JAR" ]] || die "H2 jar not found: $H2_JAR"
  [[ -n "$H2_URL" ]] || die "H2 URL is empty"
}

require_postgresql() {
  command -v psql >/dev/null 2>&1 || die "psql not found"
  [[ "$PG_URL" == jdbc:postgresql:* ]] || die "PostgreSQL JDBC URL is invalid or missing in $PG_PROPERTIES"
  [[ -n "$PG_USER" ]] || die "PostgreSQL user is missing in $PG_PROPERTIES"
}

run_h2_sql_current_user() {
  java -cp "$H2_JAR" org.h2.tools.Shell \
    -url "$H2_URL" \
    -user "$H2_USER" \
    -password "$H2_PASSWORD" \
    -sql "$1"
}

run_h2_sql() {
  require_h2
  if [[ "$(id -un)" == "jetty" ]]; then
    run_h2_sql_current_user "$1"
  else
    sudo -u jetty env H2_JAR="$H2_JAR" H2_URL="$H2_URL" H2_USER="$H2_USER" H2_PASSWORD="$H2_PASSWORD" \
      bash -c 'java -cp "$H2_JAR" org.h2.tools.Shell -url "$H2_URL" -user "$H2_USER" -password "$H2_PASSWORD" -sql "$1"' _ "$1"
  fi
}

run_pg_sql() {
  require_postgresql
  PGPASSWORD="$PG_PASSWORD" PGOPTIONS="--client-min-messages=warning" \
    psql -q "$(jdbc_to_psql_url "$PG_URL")" \
      -U "$PG_USER" \
      -v ON_ERROR_STOP=1 \
      -P null=null \
      -c "$1"
}

run_pg_file() {
  local sql_file="$1"
  require_postgresql
  PGPASSWORD="$PG_PASSWORD" PGOPTIONS="--client-min-messages=warning" \
    psql -q "$(jdbc_to_psql_url "$PG_URL")" \
      -U "$PG_USER" \
      -v ON_ERROR_STOP=1 \
      -P null=null \
      -f "$sql_file"
}

h2_init_sql() {
  cat <<'SQL'
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
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS initial_sequence VARCHAR(1024) NOT NULL DEFAULT '';
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix';
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS force_sequence_change INT NOT NULL DEFAULT 0;
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS totp_seed VARCHAR(255);
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED';
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS totp_registered_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN IF NOT EXISTS last_success_at BIGINT NOT NULL DEFAULT 0;
UPDATE graphicalmatrix_enrollment SET initial_sequence = sequence WHERE initial_sequence IS NULL OR initial_sequence = '';
SQL
}

migration_select() {
  cat <<'SQL'
SELECT user_id, sequence, initial_sequence, status, failed_count, locked_until,
       mfa_method, totp_seed, totp_status, totp_registered_at, last_success_at,
       force_sequence_change, created_at, updated_at
FROM graphicalmatrix_enrollment
ORDER BY user_id
SQL
}

h2_count() {
  run_h2_sql "$(h2_init_sql)
SELECT COUNT(*) AS enrollment_count FROM graphicalmatrix_enrollment;"
}

h2_export() {
  [[ -n "$OUTPUT_FILE" ]] || die "--output is required"
  local output_dir
  output_dir="$(dirname "$OUTPUT_FILE")"
  [[ -d "$output_dir" ]] || die "output directory does not exist: $output_dir"
  [[ -w "$output_dir" ]] || die "output directory is not writable by current user: $output_dir"

  rm -f "$OUTPUT_FILE"
  local sql
  sql="$(h2_init_sql)
CALL CSVWRITE('$(printf "%s" "$OUTPUT_FILE" | sed "s/'/''/g")',
'$(migration_select | tr '\n' ' ' | sed "s/'/''/g")',
'charset=UTF-8 fieldSeparator=, fieldDelimiter=\"');"
  run_h2_sql "$sql"
  [[ -f "$OUTPUT_FILE" ]] || die "CSV export failed: $OUTPUT_FILE"
  chmod 0600 "$OUTPUT_FILE"
  echo "exported_csv=$OUTPUT_FILE"
  echo "csv_mode=0600"
  echo "csv_contains_secret_data=yes"
}

pg_count() {
  run_pg_sql "SELECT COUNT(*) AS enrollment_count FROM graphicalmatrix_enrollment;"
}

pg_apply_schema() {
  [[ -r "$PG_SCHEMA" ]] || die "PostgreSQL schema file is not readable: $PG_SCHEMA"
  echo "postgresql_properties=$PG_PROPERTIES"
  echo "postgresql_schema=$PG_SCHEMA"
  if [[ "$APPLY" != "1" ]]; then
    echo "dry_run=yes"
    echo "would_apply_schema=$PG_SCHEMA"
    echo "Add --apply to apply the schema."
    return
  fi
  run_pg_file "$PG_SCHEMA"
  echo "schema_applied=yes"
}

csv_count() {
  python3 - "$1" <<'PY'
import csv
import sys
with open(sys.argv[1], newline="") as handle:
    reader = csv.DictReader(handle)
    rows = list(reader)
print(len(rows))
PY
}

csv_user_checksum() {
  python3 - "$1" <<'PY'
import csv
import hashlib
import sys
with open(sys.argv[1], newline="") as handle:
    reader = csv.DictReader(handle)
    users = sorted(row.get("USER_ID") or row.get("user_id") or "" for row in reader)
print(hashlib.sha256("\n".join(users).encode("utf-8")).hexdigest())
PY
}

pg_import_sql_file() {
  local input="$1"
  local sql_file="$2"
  local input_q
  input_q="$(printf "%s" "$input" | sed "s/'/''/g")"
  cat > "$sql_file" <<SQL
BEGIN;
CREATE TEMP TABLE graphicalmatrix_migration_import (
  user_id VARCHAR(255) PRIMARY KEY,
  sequence VARCHAR(1024) NOT NULL,
  initial_sequence VARCHAR(1024) NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  failed_count INTEGER NOT NULL DEFAULT 0,
  locked_until BIGINT NOT NULL DEFAULT 0,
  mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix',
  totp_seed VARCHAR(255),
  totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED',
  totp_registered_at BIGINT NOT NULL DEFAULT 0,
  last_success_at BIGINT NOT NULL DEFAULT 0,
  force_sequence_change INTEGER NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
\\copy graphicalmatrix_migration_import (user_id, sequence, initial_sequence, status, failed_count, locked_until, mfa_method, totp_seed, totp_status, totp_registered_at, last_success_at, force_sequence_change, created_at, updated_at) FROM '$input_q' WITH (FORMAT csv, HEADER true)
SQL
  if [[ "$TRUNCATE" == "1" ]]; then
    echo "DELETE FROM graphicalmatrix_enrollment;" >> "$sql_file"
  fi
  cat >> "$sql_file" <<'SQL'
INSERT INTO graphicalmatrix_enrollment
  (user_id, sequence, initial_sequence, status, failed_count, locked_until, mfa_method,
   totp_seed, totp_status, totp_registered_at, last_success_at, force_sequence_change,
   created_at, updated_at)
SELECT user_id, sequence, initial_sequence, status, failed_count, locked_until, mfa_method,
       totp_seed, totp_status, totp_registered_at, last_success_at, force_sequence_change,
       created_at, updated_at
FROM graphicalmatrix_migration_import
ON CONFLICT (user_id) DO UPDATE
SET sequence = EXCLUDED.sequence,
    initial_sequence = EXCLUDED.initial_sequence,
    status = EXCLUDED.status,
    failed_count = EXCLUDED.failed_count,
    locked_until = EXCLUDED.locked_until,
    mfa_method = EXCLUDED.mfa_method,
    totp_seed = EXCLUDED.totp_seed,
    totp_status = EXCLUDED.totp_status,
    totp_registered_at = EXCLUDED.totp_registered_at,
    last_success_at = EXCLUDED.last_success_at,
    force_sequence_change = EXCLUDED.force_sequence_change,
    created_at = EXCLUDED.created_at,
    updated_at = EXCLUDED.updated_at;
COMMIT;
SQL
}

pg_import() {
  [[ -n "$INPUT_FILE" ]] || die "--input is required"
  [[ -r "$INPUT_FILE" ]] || die "input CSV is not readable: $INPUT_FILE"
  command -v python3 >/dev/null 2>&1 || die "python3 is required"
  local rows sql_file
  rows="$(csv_count "$INPUT_FILE")"
  sql_file="$(mktemp)"
  trap 'rm -f "$sql_file"' RETURN
  pg_import_sql_file "$INPUT_FILE" "$sql_file"
  echo "postgresql_properties=$PG_PROPERTIES"
  echo "input_csv=$INPUT_FILE"
  echo "input_rows=$rows"
  echo "truncate=$([[ "$TRUNCATE" == "1" ]] && echo yes || echo no)"
  if [[ "$APPLY" != "1" ]]; then
    echo "dry_run=yes"
    echo "would_import_rows=$rows"
    echo "Add --apply to import."
    return
  fi
  pg_apply_schema >/dev/null
  run_pg_file "$sql_file"
  echo "imported_rows=$rows"
}

pg_verify() {
  [[ -n "$INPUT_FILE" ]] || die "--input is required"
  [[ -r "$INPUT_FILE" ]] || die "input CSV is not readable: $INPUT_FILE"
  command -v python3 >/dev/null 2>&1 || die "python3 is required"
  require_postgresql
  local csv_rows csv_checksum pg_rows pg_checksum pg_users_file
  csv_rows="$(csv_count "$INPUT_FILE")"
  csv_checksum="$(csv_user_checksum "$INPUT_FILE")"
  pg_rows="$(PGPASSWORD="$PG_PASSWORD" PGOPTIONS="--client-min-messages=warning" \
    psql -qAt "$(jdbc_to_psql_url "$PG_URL")" -U "$PG_USER" -v ON_ERROR_STOP=1 \
      -c "SELECT COUNT(*) FROM graphicalmatrix_enrollment;")"
  pg_users_file="$(mktemp)"
  trap 'rm -f "$pg_users_file"' RETURN
  PGPASSWORD="$PG_PASSWORD" PGOPTIONS="--client-min-messages=warning" \
    psql -qAt "$(jdbc_to_psql_url "$PG_URL")" -U "$PG_USER" -v ON_ERROR_STOP=1 \
      -c "SELECT user_id FROM graphicalmatrix_enrollment ORDER BY user_id;" > "$pg_users_file"
  pg_checksum="$(python3 - "$pg_users_file" <<'PY'
import hashlib
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    users = [line.rstrip("\n") for line in handle]
print(hashlib.sha256("\n".join(users).encode("utf-8")).hexdigest())
PY
)"
  echo "csv_rows=$csv_rows"
  echo "postgresql_rows=$pg_rows"
  echo "csv_user_id_checksum=$csv_checksum"
  echo "postgresql_user_id_checksum=$pg_checksum"
  if [[ "$csv_rows" == "$pg_rows" && "$csv_checksum" == "$pg_checksum" ]]; then
    echo "verify=OK"
  else
    echo "verify=NG"
    return 1
  fi
}

CMD=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    h2-count|h2-export|pg-count|pg-apply-schema|pg-import|pg-verify)
      CMD="$1"
      shift
      ;;
    --idp-home)
      IDP_HOME="${2:-}"
      shift 2
      ;;
    --h2-jar)
      H2_JAR="${2:-}"
      shift 2
      ;;
    --h2-properties)
      H2_PROPERTIES="${2:-}"
      shift 2
      ;;
    --h2-url)
      H2_URL="${2:-}"
      shift 2
      ;;
    --h2-user)
      H2_USER="${2:-}"
      shift 2
      ;;
    --h2-password)
      H2_PASSWORD="${2:-}"
      shift 2
      ;;
    --h2-password-file)
      H2_PASSWORD_FILE="${2:-}"
      shift 2
      ;;
    --pg-properties)
      PG_PROPERTIES="${2:-}"
      shift 2
      ;;
    --pg-schema)
      PG_SCHEMA="${2:-}"
      shift 2
      ;;
    --input)
      INPUT_FILE="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_FILE="${2:-}"
      shift 2
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    --truncate)
      TRUNCATE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$IDP_HOME" ]] || die "--idp-home must not be empty"
resolve_defaults

case "$CMD" in
  h2-count)
    h2_count
    ;;
  h2-export)
    h2_export
    ;;
  pg-count)
    pg_count
    ;;
  pg-apply-schema)
    pg_apply_schema
    ;;
  pg-import)
    pg_import
    ;;
  pg-verify)
    pg_verify
    ;;
  "")
    usage
    exit 2
    ;;
  *)
    die "unknown command: $CMD"
    ;;
esac
