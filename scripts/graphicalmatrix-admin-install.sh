#!/usr/bin/env bash
set -euo pipefail

PREFIX="/opt/graphicalmatrix-admin"
PACKAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPLY=0

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-admin-install.sh [--prefix /opt/graphicalmatrix-admin] [--package-dir DIR] [--apply]

Installs only the GraphicalMatrix management CLI package.
This script does not modify Shibboleth IdP web.xml, Jetty, or IdP plugin files.

Installed layout:
  PREFIX/bin/graphicalmatrix-db.sh
  PREFIX/bin/graphicalmatrix-csv-import-runner.sh
  PREFIX/lib/*.jar
  PREFIX/conf/graphicalmatrix/*.properties
  PREFIX/docs/*
  PREFIX/examples/systemd/*
  PREFIX/incoming, processing, processed, failed, logs

After install:
  export GRAPHICALMATRIX_HOME=PREFIX
  PREFIX/bin/graphicalmatrix-db.sh list
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --prefix)
      [[ -n "${2:-}" ]] || { echo "ERROR: --prefix requires a value" >&2; exit 2; }
      PREFIX="$2"
      shift 2
      ;;
    --package-dir)
      [[ -n "${2:-}" ]] || { echo "ERROR: --package-dir requires a value" >&2; exit 2; }
      PACKAGE_DIR="$2"
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
      echo "ERROR: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -d "$PACKAGE_DIR/bin" ]] || { echo "ERROR: package bin directory not found: $PACKAGE_DIR/bin" >&2; exit 1; }
[[ -d "$PACKAGE_DIR/lib" ]] || { echo "ERROR: package lib directory not found: $PACKAGE_DIR/lib" >&2; exit 1; }
[[ -f "$PACKAGE_DIR/bin/graphicalmatrix-db.sh" ]] || { echo "ERROR: graphicalmatrix-db.sh not found in package" >&2; exit 1; }
if ! ls "$PACKAGE_DIR"/lib/2faskw-idp-plugin-*.jar >/dev/null 2>&1; then
  echo "ERROR: 2FAS-KW admin jar not found: $PACKAGE_DIR/lib/2faskw-idp-plugin-*.jar" >&2
  exit 1
fi

echo "2FAS-KW admin CLI install plan:"
echo "  package_dir: $PACKAGE_DIR"
echo "  prefix:      $PREFIX"
echo "  apply:       $APPLY"
echo
echo "Files/directories:"
echo "  $PREFIX/bin"
echo "  $PREFIX/lib"
echo "  $PREFIX/conf/graphicalmatrix"
echo "  $PREFIX/docs"
echo "  $PREFIX/examples/systemd"
echo "  $PREFIX/incoming"
echo "  $PREFIX/processing"
echo "  $PREFIX/processed"
echo "  $PREFIX/failed"
echo "  $PREFIX/logs"
echo

if [[ "$APPLY" != "1" ]]; then
  echo "Dry-run only. Re-run with --apply to install."
  exit 0
fi

if [[ "$(id -u)" -ne 0 ]]; then
  echo "ERROR: --apply must be run as root or via sudo" >&2
  exit 1
fi

timestamp="$(date +%Y%m%d%H%M%S)"
if [[ -d "$PREFIX" ]]; then
  backup="${PREFIX}.bak.${timestamp}"
  echo "Backup existing prefix: $backup"
  cp -a "$PREFIX" "$backup"
fi

install -d -m 0755 "$PREFIX/bin" "$PREFIX/lib" "$PREFIX/conf/graphicalmatrix" "$PREFIX/docs" "$PREFIX/examples/systemd"
install -d -m 0750 "$PREFIX/incoming" "$PREFIX/processing" "$PREFIX/processed" "$PREFIX/failed" "$PREFIX/logs"

install -m 0755 "$PACKAGE_DIR/bin/graphicalmatrix-db.sh" "$PREFIX/bin/graphicalmatrix-db.sh"
if [[ -f "$PACKAGE_DIR/bin/graphicalmatrix-db-migration.sh" ]]; then
  install -m 0755 "$PACKAGE_DIR/bin/graphicalmatrix-db-migration.sh" "$PREFIX/bin/graphicalmatrix-db-migration.sh"
fi
if [[ -f "$PACKAGE_DIR/bin/graphicalmatrix-csv-import-runner.sh" ]]; then
  install -m 0755 "$PACKAGE_DIR/bin/graphicalmatrix-csv-import-runner.sh" "$PREFIX/bin/graphicalmatrix-csv-import-runner.sh"
fi

cp -a "$PACKAGE_DIR"/lib/*.jar "$PREFIX/lib/"

if [[ -f "$PACKAGE_DIR/conf/graphicalmatrix/db.properties.adminnew" ]]; then
  if [[ -f "$PREFIX/conf/graphicalmatrix/db.properties" ]]; then
    install -m 0640 "$PACKAGE_DIR/conf/graphicalmatrix/db.properties.adminnew" \
      "$PREFIX/conf/graphicalmatrix/db.properties.adminnew.${timestamp}"
  else
    install -m 0640 "$PACKAGE_DIR/conf/graphicalmatrix/db.properties.adminnew" \
      "$PREFIX/conf/graphicalmatrix/db.properties"
  fi
fi

if [[ -f "$PACKAGE_DIR/conf/graphicalmatrix/graphicalmatrix.properties.adminnew" ]]; then
  if [[ -f "$PREFIX/conf/graphicalmatrix/graphicalmatrix.properties" ]]; then
    install -m 0644 "$PACKAGE_DIR/conf/graphicalmatrix/graphicalmatrix.properties.adminnew" \
      "$PREFIX/conf/graphicalmatrix/graphicalmatrix.properties.adminnew.${timestamp}"
  else
    install -m 0644 "$PACKAGE_DIR/conf/graphicalmatrix/graphicalmatrix.properties.adminnew" \
      "$PREFIX/conf/graphicalmatrix/graphicalmatrix.properties"
  fi
fi

if [[ -f "$PACKAGE_DIR/conf/graphicalmatrix/admin.properties.adminnew" ]]; then
  if [[ -f "$PREFIX/conf/graphicalmatrix/admin.properties" ]]; then
    install -m 0640 "$PACKAGE_DIR/conf/graphicalmatrix/admin.properties.adminnew" \
      "$PREFIX/conf/graphicalmatrix/admin.properties.adminnew.${timestamp}"
  else
    install -m 0640 "$PACKAGE_DIR/conf/graphicalmatrix/admin.properties.adminnew" \
      "$PREFIX/conf/graphicalmatrix/admin.properties"
  fi
fi

cp -a "$PACKAGE_DIR"/docs/. "$PREFIX/docs/"
if [[ -d "$PACKAGE_DIR/examples/systemd" ]]; then
  cp -a "$PACKAGE_DIR"/examples/systemd/. "$PREFIX/examples/systemd/"
fi

echo
echo "Installed."
echo "Next:"
echo "  edit $PREFIX/conf/graphicalmatrix/db.properties"
echo "  edit $PREFIX/conf/graphicalmatrix/admin.properties"
echo "  export GRAPHICALMATRIX_HOME=$PREFIX"
echo "  $PREFIX/bin/graphicalmatrix-db.sh list"
