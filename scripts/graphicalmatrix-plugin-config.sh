#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
PACKAGE_DIR=""
APPLY=0
STRICT=0
PACKAGE_CHECK=1
TS="$(date +%Y%m%d%H%M%S)"
planned_changes=0
applied_changes=0
backups_created=0
templates_deferred=0
failures=0
MANIFEST_FILE=""

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-plugin-config.sh [--idp-home DIR] [--package-dir DIR] [--apply] [--strict] [--skip-package-check]

Installs 2FAS-KW plugin package files into a Shibboleth IdP overlay layout.

Default mode is dry-run. Pass --apply to change files.

Options:
  --apply               Actually install files. Without this option, only planned commands are printed.
  --strict              Pass --strict to package pre-checks before installing.
  --skip-package-check  Skip the integrated package-only check.

This installer:
  - copies all packaged jars into edit-webapp/WEB-INF/lib
  - copies configuration templates without overwriting existing configs
  - copies views, CSS, graphical assets, graphicalmatrix-db.sh, graphicalmatrix-db-migration.sh, graphicalmatrix-api-token.sh, and graphicalmatrix-api-curl-test.sh
  - keeps the management API disabled by default
  - does not edit web.xml yet
  - does not rebuild the IdP war or restart Jetty
EOF
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
    --apply)
      APPLY=1
      shift
      ;;
    --strict)
      STRICT=1
      shift
      ;;
    --skip-package-check)
      PACKAGE_CHECK=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "$PACKAGE_DIR" ]]; then
  PACKAGE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  PACKAGE_DIR="$(cd "$PACKAGE_DIR" && pwd)"
fi

if [[ "$APPLY" -eq 1 ]]; then
  if [[ "$(id -u)" -eq 0 ]]; then
    SUDO=()
  else
    SUDO=(sudo)
  fi
else
  SUDO=()
fi

record_manifest() {
  local action="$1"
  local src="${2:-}"
  local dest="${3:-}"
  if [[ "$APPLY" -eq 1 && -n "$MANIFEST_FILE" ]]; then
    if [[ "${#SUDO[@]}" -gt 0 ]]; then
      printf '%s\t%s\t%s\n' "$action" "$src" "$dest" | "${SUDO[@]}" tee -a "$MANIFEST_FILE" >/dev/null
    else
      printf '%s\t%s\t%s\n' "$action" "$src" "$dest" | tee -a "$MANIFEST_FILE" >/dev/null
    fi
  fi
}

run() {
  printf '+ %s\n' "$*"
  if [[ "$APPLY" -eq 1 ]]; then
    "$@"
  fi
}

run_sudo() {
  printf '+'
  if [[ "${#SUDO[@]}" -gt 0 ]]; then
    printf ' %q' "${SUDO[@]}"
  fi
  printf ' %q' "$@"
  printf '\n'
  if [[ "$APPLY" -eq 1 ]]; then
    if [[ "${#SUDO[@]}" -gt 0 ]]; then
      "${SUDO[@]}" "$@"
    else
      "$@"
    fi
  fi
}

require_file() {
  local file="$1"
  [[ -f "$file" ]] || { echo "ERROR: missing file: $file" >&2; exit 1; }
}

require_dir() {
  local dir="$1"
  [[ -d "$dir" ]] || { echo "ERROR: missing directory: $dir" >&2; exit 1; }
}

install_copy() {
  local src="$1"
  local dest="$2"
  require_file "$src"
  planned_changes=$((planned_changes + 1))
  run_sudo mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" ]]; then
    run_sudo cp "$dest" "$dest.bak.$TS"
    if [[ "$APPLY" -eq 1 ]]; then
      backups_created=$((backups_created + 1))
      record_manifest "backup" "$dest" "$dest.bak.$TS"
    fi
  fi
  run_sudo install -m 0644 "$src" "$dest"
  if [[ "$APPLY" -eq 1 ]]; then
    applied_changes=$((applied_changes + 1))
    record_manifest "install" "$src" "$dest"
  fi
}

install_executable() {
  local src="$1"
  local dest="$2"
  require_file "$src"
  planned_changes=$((planned_changes + 1))
  run_sudo mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" ]]; then
    run_sudo cp "$dest" "$dest.bak.$TS"
    if [[ "$APPLY" -eq 1 ]]; then
      backups_created=$((backups_created + 1))
      record_manifest "backup" "$dest" "$dest.bak.$TS"
    fi
  fi
  run_sudo install -m 0755 "$src" "$dest"
  if [[ "$APPLY" -eq 1 ]]; then
    applied_changes=$((applied_changes + 1))
    record_manifest "install_executable" "$src" "$dest"
  fi
}

install_template() {
  local src="$1"
  local dest="$2"
  require_file "$src"
  planned_changes=$((planned_changes + 1))
  run_sudo mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" ]]; then
    run_sudo install -m 0644 "$src" "$dest.idpnew.$TS"
    if [[ "$APPLY" -eq 1 ]]; then
      templates_deferred=$((templates_deferred + 1))
      applied_changes=$((applied_changes + 1))
      record_manifest "install_template_deferred" "$src" "$dest.idpnew.$TS"
    fi
  else
    run_sudo install -m 0644 "$src" "$dest"
    if [[ "$APPLY" -eq 1 ]]; then
      applied_changes=$((applied_changes + 1))
      record_manifest "install_template" "$src" "$dest"
    fi
  fi
}

preflight_sudo() {
  if [[ "$APPLY" -ne 1 || "$(id -u)" -eq 0 ]]; then
    return
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    echo "ERROR: sudo not found; run as root or install sudo." >&2
    exit 1
  fi
  if ! sudo -n true 2>/dev/null; then
    echo "ERROR: sudo is required for --apply and is not available without a password prompt." >&2
    echo "action: run with passwordless sudo, run as root, or execute dry-run without --apply first." >&2
    exit 1
  fi
}

run_package_check() {
  if [[ "$PACKAGE_CHECK" -ne 1 ]]; then
    echo "package_check=skipped"
    return
  fi
  local check_script="$PACKAGE_DIR/bin/graphicalmatrix-plugin-check.sh"
  require_file "$check_script"
  local args=(--package-only --package-dir "$PACKAGE_DIR")
  if [[ "$STRICT" -eq 1 ]]; then
    args+=(--strict)
  fi
  echo "package_check=running"
  bash "$check_script" "${args[@]}"
}

create_manifest() {
  if [[ "$APPLY" -ne 1 ]]; then
    return
  fi
  MANIFEST_FILE="$IDP_HOME/conf/graphicalmatrix/install-manifest-$TS.tsv"
  run_sudo mkdir -p "$(dirname "$MANIFEST_FILE")"
  if [[ "${#SUDO[@]}" -gt 0 ]]; then
    printf 'action\tsource\tdestination\n' | "${SUDO[@]}" tee "$MANIFEST_FILE" >/dev/null
  else
    printf 'action\tsource\tdestination\n' | tee "$MANIFEST_FILE" >/dev/null
  fi
  echo "install_manifest=$MANIFEST_FILE"
}

print_summary_and_exit() {
  local result
  if [[ "$failures" -gt 0 ]]; then
    result="FAILED"
  elif [[ "$APPLY" -eq 1 ]]; then
    result="APPLY_OK"
  else
    result="DRY_RUN_OK"
  fi

  echo
  echo "summary: mode=$([[ "$APPLY" -eq 1 ]] && echo apply || echo dry-run) planned_changes=$planned_changes applied_changes=$applied_changes backups_created=$backups_created templates_deferred=$templates_deferred failures=$failures strict=$STRICT package_check=$([[ "$PACKAGE_CHECK" -eq 1 ]] && echo enabled || echo skipped)"
  echo "result: $result"
  if [[ -n "$MANIFEST_FILE" ]]; then
    echo "manifest: $MANIFEST_FILE"
  fi
  if [[ "$APPLY" -eq 1 ]]; then
    echo "next: review deferred *.idpnew.$TS files, apply web.xml mappings, run $IDP_HOME/bin/build.sh, then restart Jetty."
  else
    echo "next: review planned commands, then re-run with --apply to install."
  fi
  echo "note: this script does not edit web.xml, rebuild the IdP war, restart Jetty, initialize DB data, or enable the management API."

  if [[ "$failures" -gt 0 ]]; then
    exit 1
  fi
}

echo "mode=$([[ "$APPLY" -eq 1 ]] && echo apply || echo dry-run)"
echo "package_dir=$PACKAGE_DIR"
echo "idp_home=$IDP_HOME"
echo "strict=$STRICT"
echo "package_check=$([[ "$PACKAGE_CHECK" -eq 1 ]] && echo enabled || echo skipped)"

require_dir "$PACKAGE_DIR"
require_dir "$IDP_HOME"
preflight_sudo
run_package_check
create_manifest

if ! ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar >/dev/null 2>&1; then
  echo "ERROR: 2FAS-KW plugin jar missing under $PACKAGE_DIR/webapp/WEB-INF/lib" >&2
  exit 1
fi
require_file "$PACKAGE_DIR/webapp/WEB-INF/lib/core-3.5.3.jar"
if ! ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/HikariCP-*.jar >/dev/null 2>&1; then
  echo "ERROR: HikariCP jar missing under $PACKAGE_DIR/webapp/WEB-INF/lib" >&2
  exit 1
fi
if ! ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/postgresql-*.jar >/dev/null 2>&1; then
  echo "ERROR: PostgreSQL JDBC driver missing under $PACKAGE_DIR/webapp/WEB-INF/lib" >&2
  exit 1
fi

for src in "$PACKAGE_DIR"/webapp/WEB-INF/lib/*.jar; do
  install_copy "$src" "$IDP_HOME/edit-webapp/WEB-INF/lib/$(basename "$src")"
done

install_template "$PACKAGE_DIR/conf/graphicalmatrix/graphicalmatrix.properties.idpnew" \
  "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties"
install_template "$PACKAGE_DIR/conf/graphicalmatrix/db.properties.idpnew" \
  "$IDP_HOME/conf/graphicalmatrix/db.properties"
install_template "$PACKAGE_DIR/conf/graphicalmatrix/api.properties.idpnew" \
  "$IDP_HOME/conf/graphicalmatrix/api.properties"
install_template "$PACKAGE_DIR/conf/graphicalmatrix/mfa-policy.properties.idpnew" \
  "$IDP_HOME/conf/graphicalmatrix/mfa-policy.properties"
install_copy "$PACKAGE_DIR/conf/graphicalmatrix/postgresql-schema.sql" \
  "$IDP_HOME/conf/graphicalmatrix/postgresql-schema.sql"

for src in "$PACKAGE_DIR"/conf/graphicalmatrix/views/*.idpnew; do
  base="$(basename "$src" .idpnew)"
  install_template "$src" "$IDP_HOME/conf/graphicalmatrix/views/$base"
done

install_template "$PACKAGE_DIR/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew" \
  "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css"

run_sudo mkdir -p "$IDP_HOME/edit-webapp/graphicalmatrix/graphicals"
for src in "$PACKAGE_DIR"/conf/graphicalmatrix/graphicals/*; do
  install_copy "$src" "$IDP_HOME/edit-webapp/graphicalmatrix/graphicals/$(basename "$src")"
done

install_executable "$PACKAGE_DIR/bin/graphicalmatrix-db.sh" "$IDP_HOME/bin/graphicalmatrix-db.sh"
install_executable "$PACKAGE_DIR/bin/graphicalmatrix-db-migration.sh" "$IDP_HOME/bin/graphicalmatrix-db-migration.sh"
install_executable "$PACKAGE_DIR/bin/graphicalmatrix-api-token.sh" "$IDP_HOME/bin/graphicalmatrix-api-token.sh"
install_executable "$PACKAGE_DIR/bin/graphicalmatrix-api-curl-test.sh" "$IDP_HOME/bin/graphicalmatrix-api-curl-test.sh"

print_summary_and_exit
