#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
APPLY=0
REMOVE_CONFIG=0
REMOVE_RUNTIME_DEPS=0
TS="$(date +%Y%m%d%H%M%S)"

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-plugin-uninstall.sh [--idp-home DIR] [--apply] [--remove-config] [--remove-runtime-deps]

Removes 2FAS-KW plugin overlay files from a Shibboleth IdP layout.

Default mode is dry-run. Pass --apply to change files.

Safety defaults:
  - does not remove DB data
  - does not remove conf/graphicalmatrix unless --remove-config is specified
  - does not remove runtime dependency jars unless --remove-runtime-deps is specified
  - does not rebuild the IdP war or restart Jetty
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --idp-home)
      IDP_HOME="${2:-}"
      shift 2
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    --remove-config)
      REMOVE_CONFIG=1
      shift
      ;;
    --remove-runtime-deps)
      REMOVE_RUNTIME_DEPS=1
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

if [[ "$APPLY" -eq 1 ]]; then
  if [[ "$(id -u)" -eq 0 ]]; then
    SUDO=()
  else
    SUDO=(sudo)
  fi
else
  SUDO=()
fi

run_sudo() {
  printf '+'
  printf ' %q' "${SUDO[@]}" "$@"
  printf '\n'
  if [[ "$APPLY" -eq 1 ]]; then
    "${SUDO[@]}" "$@"
  fi
}

remove_file() {
  local file="$1"
  if [[ -e "$file" || "$APPLY" -eq 0 ]]; then
    run_sudo rm -f "$file"
  fi
}

backup_and_remove_dir() {
  local dir="$1"
  local backup="$2"
  if [[ "$APPLY" -eq 1 && -d "$dir" ]]; then
    run_sudo mkdir -p "$(dirname "$backup")"
    run_sudo mv "$dir" "$backup"
  else
    run_sudo mv "$dir" "$backup"
  fi
}

echo "mode=$([[ "$APPLY" -eq 1 ]] && echo apply || echo dry-run)"
echo "idp_home=$IDP_HOME"

if [[ "$APPLY" -eq 1 ]]; then
  for jar in "$IDP_HOME"/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar; do
    [[ -e "$jar" ]] && remove_file "$jar"
  done
  for jar in "$IDP_HOME"/edit-webapp/WEB-INF/lib/graphicalmatrix-idp-plugin-*.jar; do
    [[ -e "$jar" ]] && remove_file "$jar"
  done
else
  run_sudo rm -f "$IDP_HOME/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar"
  run_sudo rm -f "$IDP_HOME/edit-webapp/WEB-INF/lib/graphicalmatrix-idp-plugin-*.jar"
fi

if [[ "$REMOVE_RUNTIME_DEPS" -eq 1 ]]; then
  remove_file "$IDP_HOME/edit-webapp/WEB-INF/lib/core-3.5.3.jar"
  if [[ "$APPLY" -eq 1 ]]; then
    for jar in "$IDP_HOME"/edit-webapp/WEB-INF/lib/postgresql-*.jar; do
      [[ -e "$jar" ]] && remove_file "$jar"
    done
  else
    run_sudo rm -f "$IDP_HOME/edit-webapp/WEB-INF/lib/postgresql-*.jar"
  fi
else
  echo "Keeping runtime dependencies by default:"
  echo "  $IDP_HOME/edit-webapp/WEB-INF/lib/core-3.5.3.jar"
  echo "  $IDP_HOME/edit-webapp/WEB-INF/lib/postgresql-*.jar"
fi

remove_file "$IDP_HOME/bin/graphicalmatrix-db.sh"
remove_file "$IDP_HOME/bin/graphicalmatrix-db-migration.sh"
remove_file "$IDP_HOME/bin/graphicalmatrix-api-token.sh"
remove_file "$IDP_HOME/bin/graphicalmatrix-api-curl-test.sh"

if [[ "$REMOVE_CONFIG" -eq 1 ]]; then
  backup_and_remove_dir "$IDP_HOME/conf/graphicalmatrix" "$IDP_HOME/conf/graphicalmatrix.removed.$TS"
  backup_and_remove_dir "$IDP_HOME/edit-webapp/graphicalmatrix" "$IDP_HOME/edit-webapp/graphicalmatrix.removed.$TS"
else
  echo "Keeping configuration and graphicals by default:"
  echo "  $IDP_HOME/conf/graphicalmatrix"
  echo "  $IDP_HOME/edit-webapp/graphicalmatrix"
fi

echo
echo "Not removed:"
echo "  - DB data"
echo "  - audit logs"
echo "  - web.xml mappings unless handled by the web.xml procedure"
echo
echo "Next steps:"
echo "  1. Remove web.xml servlet mappings using the web.xml procedure."
echo "  2. Run: $IDP_HOME/bin/build.sh"
echo "  3. Restart Jetty."
echo
if [[ "$APPLY" -eq 0 ]]; then
  echo "Dry-run only. Re-run with --apply to uninstall."
fi
