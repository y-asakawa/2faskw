#!/usr/bin/env bash
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_BIN:-java}"
CONFIG="${DASHBOARD_CONFIG:-/etc/2faskw-dashboard/dashboard.properties}"
COMMAND="${1:-server}"

case "$COMMAND" in
  server)
    shift || true
    exec "$JAVA_BIN" -cp "$APP_HOME/lib/*" \
      io.github.yasakawa.faskw.dashboard.DashboardMain \
      server --config "$CONFIG" "$@"
    ;;
  check)
    shift || true
    exec "$JAVA_BIN" -cp "$APP_HOME/lib/*" \
      io.github.yasakawa.faskw.dashboard.DashboardMain \
      check --config "$CONFIG" "$@"
    ;;
  *)
    echo "Usage: $0 {server|check}" >&2
    exit 2
    ;;
esac
