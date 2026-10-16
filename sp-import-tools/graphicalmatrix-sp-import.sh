#!/usr/bin/env bash
# Standalone SP migration assistant. Source IdP files are read-only.
set -euo pipefail

SOURCE="${BASH_SOURCE[0]}"
LINKS=0
while [[ -L "$SOURCE" ]]; do
  ((LINKS+=1))
  [[ "$LINKS" -le 32 ]] || { echo 'ERROR: launcher symlink depth exceeds 32.' >&2; exit 2; }
  DIRECTORY="$(cd -P "$(dirname "$SOURCE")" && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ "$SOURCE" == /* ]] || SOURCE="$DIRECTORY/$SOURCE"
done
TOOL_HOME="$(cd -P "$(dirname "$SOURCE")" && pwd)"
JAVA_BIN="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA_BIN="${JAVA_BIN:-java}"
command -v "$JAVA_BIN" >/dev/null 2>&1 || {
  echo 'ERROR: Java 21 or newer is required; set JAVA_HOME or JAVA.' >&2
  exit 2
}
if ! VERSION_OUTPUT="$("$JAVA_BIN" -version 2>&1)"; then
  printf 'ERROR: unable to run Java: %s; set JAVA_HOME or JAVA to a working Java 21+ runtime.\n' "$JAVA_BIN" >&2
  printf '%s\n' "$VERSION_OUTPUT" >&2
  exit 2
fi
JAVA_MAJOR="$(printf '%s\n' "$VERSION_OUTPUT" | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ && "$JAVA_MAJOR" -ge 21 ]] || {
  echo 'ERROR: Java 21 or newer is required.' >&2
  exit 2
}
shopt -s nullglob
JARS=("$TOOL_HOME"/lib/2faskw-sp-import-tools-*.jar)
[[ "${#JARS[@]}" == 1 ]] || {
  echo "ERROR: exactly one SP Import Tools JAR must exist in $TOOL_HOME/lib" >&2
  exit 2
}
exec "$JAVA_BIN" -jar "${JARS[0]}" --tool-home "$TOOL_HOME" "$@"
