#!/usr/bin/env bash
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_BIN:-java}"
CONFIG="${DASHBOARD_CONFIG:-/etc/2faskw-dashboard/dashboard.properties}"

exec "$JAVA_BIN" -cp "$APP_HOME/lib/*" \
  io.github.yasakawa.faskw.dashboard.DashboardMain \
  import --config "$CONFIG" "$@"
