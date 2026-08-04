#!/usr/bin/env bash
set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "ERROR: graphicalmatrix-sp.sh must be run as root; use sudo." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_HOME="$(cd "$SCRIPT_DIR/.." 2>/dev/null && pwd || true)"

if [[ -n "${GRAPHICALMATRIX_IDP_HOME:-}" ]]; then
  IDP_HOME="$GRAPHICALMATRIX_IDP_HOME"
elif [[ -n "${IDP_HOME:-}" ]]; then
  IDP_HOME="$IDP_HOME"
else
  IDP_HOME="/opt/shibboleth-idp"
fi

if [[ ! -d "$IDP_HOME" ]]; then
  echo "ERROR: Shibboleth IdP home not found: $IDP_HOME" >&2
  exit 1
fi

read_property() {
  local path="$1"
  local key="$2"
  awk -F= -v wanted="$key" '
    {
      name = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", name)
      if (name == wanted) {
        sub(/^[^=]*=/, "")
        gsub(/^[[:space:]]+|[[:space:]]+$/, "")
        print
        exit
      }
    }
  ' "$path"
}

SP_MANAGEMENT_CONFIG="$IDP_HOME/conf/graphicalmatrix/sp-management.properties"
if [[ -z "${IDP_BASE_URL:-}" && -r "$SP_MANAGEMENT_CONFIG" ]]; then
  RELOAD_BASE_URL="$(read_property "$SP_MANAGEMENT_CONFIG" \
    "graphicalmatrix.sp.reload.baseUrl")"
  if [[ -n "$RELOAD_BASE_URL" ]]; then
    case "$RELOAD_BASE_URL" in
      http://*|https://*) export IDP_BASE_URL="$RELOAD_BASE_URL" ;;
      *)
        echo "ERROR: graphicalmatrix.sp.reload.baseUrl must be an HTTP(S) URL." >&2
        exit 1
        ;;
    esac
  fi
fi

JAVA_BIN="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA_BIN="${JAVA_BIN:-java}"
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "ERROR: Java 21 was not found; set JAVA_HOME or JAVA." >&2
  exit 1
fi

find_plugin_jar() {
  local directory="$1"
  [[ -d "$directory" ]] || return 1
  find "$directory" -maxdepth 1 -type f -name '2faskw-idp-plugin-*.jar' -print \
    | sort -V | tail -n 1
}

PLUGIN_JAR=""
for directory in \
  "$PACKAGE_HOME/webapp/WEB-INF/lib" \
  "$IDP_HOME/edit-webapp/WEB-INF/lib" \
  "$IDP_HOME/dist/plugin-webapp/WEB-INF/lib"; do
  candidate="$(find_plugin_jar "$directory" || true)"
  if [[ -n "$candidate" ]]; then
    PLUGIN_JAR="$candidate"
    break
  fi
done

if [[ -z "$PLUGIN_JAR" ]]; then
  echo "ERROR: 2FAS-KW plugin JAR was not found under the package or IdP." >&2
  exit 1
fi

LIB_DIR="$(dirname "$PLUGIN_JAR")"
CLASSPATH="$PLUGIN_JAR"
while IFS= read -r jar; do
  [[ "$jar" == "$PLUGIN_JAR" ]] && continue
  [[ "$(basename "$jar")" == 2faskw-idp-plugin-*.jar ]] && continue
  CLASSPATH="$CLASSPATH:$jar"
done < <(find "$LIB_DIR" -maxdepth 1 -type f -name '*.jar' -print | sort)

exec "$JAVA_BIN" -cp "$CLASSPATH" \
  io.github.yasakawa.faskw.GraphicalMatrixSpManagementTool \
  "$IDP_HOME" "$@"
