#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
PACKAGE_DIR=""
APPLY=0
TS="$(date +%Y%m%d%H%M%S)"

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-plugin-config.sh [--idp-home DIR] [--package-dir DIR] [--apply]

Installs GraphicalMatrix plugin package files into a Shibboleth IdP overlay layout.

Default mode is dry-run. Pass --apply to change files.

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

run() {
  printf '+ %s\n' "$*"
  if [[ "$APPLY" -eq 1 ]]; then
    "$@"
  fi
}

run_sudo() {
  printf '+'
  printf ' %q' "${SUDO[@]}" "$@"
  printf '\n'
  if [[ "$APPLY" -eq 1 ]]; then
    "${SUDO[@]}" "$@"
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
  run_sudo mkdir -p "$(dirname "$dest")"
  if [[ "$APPLY" -eq 1 && -f "$dest" ]]; then
    run_sudo cp "$dest" "$dest.bak.$TS"
  fi
  run_sudo install -m 0644 "$src" "$dest"
}

install_executable() {
  local src="$1"
  local dest="$2"
  require_file "$src"
  run_sudo mkdir -p "$(dirname "$dest")"
  if [[ "$APPLY" -eq 1 && -f "$dest" ]]; then
    run_sudo cp "$dest" "$dest.bak.$TS"
  fi
  run_sudo install -m 0755 "$src" "$dest"
}

install_template() {
  local src="$1"
  local dest="$2"
  require_file "$src"
  run_sudo mkdir -p "$(dirname "$dest")"
  if [[ "$APPLY" -eq 1 && -f "$dest" ]]; then
    run_sudo install -m 0644 "$src" "$dest.idpnew.$TS"
  else
    run_sudo install -m 0644 "$src" "$dest"
  fi
}

echo "mode=$([[ "$APPLY" -eq 1 ]] && echo apply || echo dry-run)"
echo "package_dir=$PACKAGE_DIR"
echo "idp_home=$IDP_HOME"

require_dir "$PACKAGE_DIR"
require_dir "$IDP_HOME"
if ! ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/graphicalmatrix-idp-plugin-*.jar >/dev/null 2>&1; then
  echo "ERROR: GraphicalMatrix plugin jar missing under $PACKAGE_DIR/webapp/WEB-INF/lib" >&2
  exit 1
fi
require_file "$PACKAGE_DIR/webapp/WEB-INF/lib/core-3.5.3.jar"
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

echo
echo "Next steps:"
echo "  1. Review any *.idpnew.* files created under $IDP_HOME/conf/graphicalmatrix."
echo "  2. Review docs/INSTALL.md from the plugin package."
echo "  3. Apply web.xml servlet mappings using the web.xml procedure."
echo "  4. Run: $IDP_HOME/bin/build.sh"
echo "  5. Restart Jetty."
echo "  6. Verify /idp/graphicalmatrix/change."
echo
if [[ "$APPLY" -eq 0 ]]; then
  echo "Dry-run only. Re-run with --apply to install."
fi
