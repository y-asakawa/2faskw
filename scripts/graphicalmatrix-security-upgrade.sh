#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
PACKAGE_DIR=""
BACKUP_CONFIRMED=0
MAINTENANCE_CONFIRMED=0
SCHEMA_APPLIED=0

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-security-upgrade.sh --package-dir DIR [--idp-home DIR] plan
  graphicalmatrix-security-upgrade.sh --package-dir DIR [--idp-home DIR] verify
  graphicalmatrix-security-upgrade.sh --package-dir DIR [--idp-home DIR] \
    --backup-confirmed --maintenance-confirmed --schema-applied apply

The apply command performs the sequence data migration only. Apply the packaged
PostgreSQL schema with the deployment DDL account before using apply.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }'
}

run_db() {
  GRAPHICALMATRIX_HOME="$IDP_HOME" \
  IDP_HOME="$IDP_HOME" \
  SEQUENCE_TOOL_CP="$SEQUENCE_TOOL_CP" \
    "$PACKAGE_DIR/bin/graphicalmatrix-db.sh" "$@"
}

verify_status() {
  local status initial_empty initial_incompatible incompatible active_empty active_incompatible
  status="$(run_db security-status)"
  printf '%s\n' "$status"
  initial_empty="$(printf '%s\n' "$status" | value security.initial_sequence_empty_rows)"
  initial_incompatible="$(printf '%s\n' "$status" | value security.initial_sequence_incompatible_rows)"
  incompatible="$(printf '%s\n' "$status" | value security.incompatible_sequence_rows)"
  active_empty="$(printf '%s\n' "$status" | value security.active_empty_sequence_rows)"
  active_incompatible="$(printf '%s\n' "$status" | value security.active_incompatible_sequence_rows)"
  [[ "$initial_empty" == "0" ]] || die "initial_sequence is missing for users: $initial_empty"
  [[ "$initial_incompatible" == "0" ]] || die "initial_sequence migration is incomplete: $initial_incompatible"
  [[ "$incompatible" == "0" ]] || die "sequence migration is incomplete: $incompatible"
  [[ "$active_empty" == "0" ]] || die "ACTIVE users have empty sequences: $active_empty"
  [[ "$active_incompatible" == "0" ]] || die "ACTIVE users have incompatible sequences: $active_incompatible"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --idp-home)
      IDP_HOME="${2:-}"
      shift 2
      ;;
    --package-dir)
      PACKAGE_DIR="${2:-}"
      shift 2
      ;;
    --backup-confirmed)
      BACKUP_CONFIRMED=1
      shift
      ;;
    --maintenance-confirmed)
      MAINTENANCE_CONFIRMED=1
      shift
      ;;
    --schema-applied)
      SCHEMA_APPLIED=1
      shift
      ;;
    plan|apply|verify)
      COMMAND="$1"
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

COMMAND="${COMMAND:-}"
[[ -n "$PACKAGE_DIR" ]] || die "--package-dir is required"
PACKAGE_DIR="$(cd "$PACKAGE_DIR" && pwd)"
[[ -x "$PACKAGE_DIR/bin/graphicalmatrix-db.sh" ]] || die "package DB tool is missing"
[[ -x "$PACKAGE_DIR/bin/graphicalmatrix-plugin-check.sh" ]] || die "package check tool is missing"

if [[ -d "$PACKAGE_DIR/webapp/WEB-INF/lib" ]]; then
  SEQUENCE_TOOL_CP="$(find "$PACKAGE_DIR/webapp/WEB-INF/lib" -type f -name '*.jar' -print | sort | paste -sd ':' -)"
elif [[ -d "$PACKAGE_DIR/lib" ]]; then
  SEQUENCE_TOOL_CP="$(find "$PACKAGE_DIR/lib" -type f -name '*.jar' -print | sort | paste -sd ':' -)"
else
  die "package Java libraries are missing"
fi
[[ -n "$SEQUENCE_TOOL_CP" ]] || die "package Java classpath is empty"
if [[ -n "${H2_JAR:-}" && -f "$H2_JAR" ]]; then
  SEQUENCE_TOOL_CP="$SEQUENCE_TOOL_CP:$H2_JAR"
else
  for h2_jar in "$IDP_HOME"/edit-webapp/WEB-INF/lib/h2-*.jar "$IDP_HOME"/lib/h2-*.jar; do
    [[ -f "$h2_jar" ]] || continue
    SEQUENCE_TOOL_CP="$SEQUENCE_TOOL_CP:$h2_jar"
    break
  done
fi

case "$COMMAND" in
  plan)
    "$PACKAGE_DIR/bin/graphicalmatrix-plugin-check.sh" --package-only --strict \
      --package-dir "$PACKAGE_DIR"
    echo "security.target_storage=$(run_db sequence-mode)"
    echo "next=stop_IdP_apply_schema_run_apply"
    ;;
  apply)
    [[ "$BACKUP_CONFIRMED" == "1" ]] || die "--backup-confirmed is required"
    [[ "$MAINTENANCE_CONFIRMED" == "1" ]] || die "--maintenance-confirmed is required"
    [[ "$SCHEMA_APPLIED" == "1" ]] || die "--schema-applied is required"
    run_db migrate-sequence-storage
    run_db migrate-sequence-storage --apply
    verify_status
    echo "security_upgrade=APPLIED"
    ;;
  verify)
    verify_status
    echo "security_upgrade=READY"
    ;;
  *)
    usage
    exit 2
    ;;
esac
