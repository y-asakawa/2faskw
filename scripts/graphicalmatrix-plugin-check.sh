#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
PACKAGE_DIR=""

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-plugin-check.sh [--idp-home DIR] [--package-dir DIR]

Checks the GraphicalMatrix plugin package and the target Shibboleth IdP layout.
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

failures=0
warnings=0

ok() {
  printf 'OK: %s\n' "$1"
}

warn() {
  warnings=$((warnings + 1))
  printf 'WARN: %s\n' "$1"
}

fail() {
  failures=$((failures + 1))
  printf 'FAIL: %s\n' "$1"
}

need_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    ok "file exists: $file"
  else
    fail "file missing: $file"
  fi
}

need_dir() {
  local dir="$1"
  if [[ -d "$dir" ]]; then
    ok "directory exists: $dir"
  else
    fail "directory missing: $dir"
  fi
}

echo "package_dir=$PACKAGE_DIR"
echo "idp_home=$IDP_HOME"

need_dir "$PACKAGE_DIR"
if ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/graphicalmatrix-idp-plugin-*.jar >/dev/null 2>&1; then
  ok "GraphicalMatrix plugin jar exists"
else
  fail "GraphicalMatrix plugin jar missing: $PACKAGE_DIR/webapp/WEB-INF/lib/graphicalmatrix-idp-plugin-*.jar"
fi
need_file "$PACKAGE_DIR/webapp/WEB-INF/lib/core-3.5.3.jar"
if ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/postgresql-*.jar >/dev/null 2>&1; then
  ok "PostgreSQL JDBC driver exists"
else
  fail "PostgreSQL JDBC driver missing: $PACKAGE_DIR/webapp/WEB-INF/lib/postgresql-*.jar"
fi
need_file "$PACKAGE_DIR/bootstrap/plugin.properties"
need_file "$PACKAGE_DIR/plugin-metadata/graphicalmatrix-plugin.properties"
need_file "$PACKAGE_DIR/plugin-metadata/README.md"
need_file "$PACKAGE_DIR/plugin-metadata/PACKAGE-CONTENTS.txt"
need_file "$PACKAGE_DIR/plugin-metadata/PACKAGE-MANIFEST.sha256"
need_file "$PACKAGE_DIR/conf/graphicalmatrix/graphicalmatrix.properties.idpnew"
need_file "$PACKAGE_DIR/conf/graphicalmatrix/db.properties.idpnew"
need_file "$PACKAGE_DIR/conf/graphicalmatrix/api.properties.idpnew"
need_file "$PACKAGE_DIR/conf/graphicalmatrix/postgresql-schema.sql"
need_file "$PACKAGE_DIR/bin/graphicalmatrix-db.sh"
need_file "$PACKAGE_DIR/bin/graphicalmatrix-db-migration.sh"
need_file "$PACKAGE_DIR/bin/graphicalmatrix-api-token.sh"
need_file "$PACKAGE_DIR/bin/graphicalmatrix-api-curl-test.sh"
need_file "$PACKAGE_DIR/examples/logrotate/graphicalmatrix-audit"
need_file "$PACKAGE_DIR/docs/README.md"
need_file "$PACKAGE_DIR/docs/INSTALL.md"
need_file "$PACKAGE_DIR/docs/SECURITY.md"
need_file "$PACKAGE_DIR/docs/SECURITY-CHECKLIST.md"
need_file "$PACKAGE_DIR/docs/API-TOKEN-ROTATION.md"
need_file "$PACKAGE_DIR/docs/API-CURL-TESTS.md"
need_file "$PACKAGE_DIR/docs/CSV-EXPORT.md"
need_file "$PACKAGE_DIR/docs/DB-MIGRATION.md"
need_file "$PACKAGE_DIR/docs/SEQUENCE-STORAGE-MIGRATION.md"
need_file "$PACKAGE_DIR/docs/LOGROTATE.md"
need_file "$PACKAGE_DIR/docs/openapi.yaml"

if [[ -f "$PACKAGE_DIR/conf/graphicalmatrix/api.properties.idpnew" ]]; then
  if grep -Eq '^[[:space:]]*graphicalmatrix[.]api[.]enabled[[:space:]]*=[[:space:]]*false[[:space:]]*$' \
      "$PACKAGE_DIR/conf/graphicalmatrix/api.properties.idpnew"; then
    ok "API template is disabled by default"
  else
    fail "API template must have graphicalmatrix.api.enabled = false"
  fi
fi

if [[ -f "$PACKAGE_DIR/plugin-metadata/graphicalmatrix-plugin.properties" ]]; then
  package_base="$(basename "$PACKAGE_DIR")"
  package_version="${package_base#graphicalmatrix-idp-plugin-}"
  if [[ -n "$package_version" && "$package_version" != "$package_base" ]] \
      && grep -q "io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix.versions[[:space:]]*=[[:space:]]*$package_version" \
      "$PACKAGE_DIR/plugin-metadata/graphicalmatrix-plugin.properties"; then
    ok "plugin metadata version matches package version"
  else
    fail "plugin metadata version must include package version: ${package_version:-unknown}"
  fi
  if [[ -n "$package_version" && "$package_version" != "$package_base" ]] \
      && grep -q "baseName.$package_version[[:space:]]*=[[:space:]]*$package_base" \
      "$PACKAGE_DIR/plugin-metadata/graphicalmatrix-plugin.properties"; then
    ok "plugin metadata baseName matches package directory"
  else
    fail "plugin metadata baseName mismatch"
  fi
fi

if [[ -f "$PACKAGE_DIR/plugin-metadata/PACKAGE-MANIFEST.sha256" ]]; then
  if command -v sha256sum >/dev/null 2>&1; then
    if (cd "$PACKAGE_DIR" && sha256sum -c plugin-metadata/PACKAGE-MANIFEST.sha256 >/dev/null); then
      ok "package sha256 manifest verifies"
    else
      fail "package sha256 manifest verification failed"
    fi
  else
    warn "sha256sum not found; skipped package manifest verification"
  fi
fi

need_dir "$IDP_HOME"
need_file "$IDP_HOME/bin/build.sh"
need_file "$IDP_HOME/edit-webapp/WEB-INF/web.xml"

if [[ -x "$IDP_HOME/bin/module.sh" ]]; then
  module_output="$("$IDP_HOME/bin/module.sh" -l 2>/dev/null || true)"
  if grep -q 'idp.authn.Password' <<<"$module_output"; then
    ok "module listed: idp.authn.Password"
  else
    warn "module not listed or module.sh output unavailable: idp.authn.Password"
  fi
  if grep -q 'idp.authn.MFA' <<<"$module_output"; then
    ok "module listed: idp.authn.MFA"
  else
    warn "module not listed or module.sh output unavailable: idp.authn.MFA"
  fi
else
  warn "module.sh not executable: $IDP_HOME/bin/module.sh"
fi

if [[ -d "$IDP_HOME/credentials/net.shibboleth.idp.plugin.authn.totp" ]]; then
  ok "optional TOTP plugin truststore directory exists"
else
  warn "optional TOTP plugin not detected"
fi

if [[ -d "$IDP_HOME/credentials/net.shibboleth.idp.plugin.authn.webauthn" ]]; then
  ok "optional WebAuthn plugin truststore directory exists"
else
  warn "optional WebAuthn plugin not detected"
fi

echo "summary: failures=$failures warnings=$warnings"
if [[ "$failures" -gt 0 ]]; then
  exit 1
fi
