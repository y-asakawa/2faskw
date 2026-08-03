#!/usr/bin/env bash
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_BIN:-java}"
CONFIG="${DASHBOARD_AGENT_CONFIG:-/etc/2faskw-dashboard-agent/agent.properties}"
COMMAND="${1:-agent}"

case "$COMMAND" in
  agent|start)
    shift || true
    exec "$JAVA_BIN" -cp "$APP_HOME/lib/*" \
      io.github.yasakawa.faskw.dashboard.DashboardMain \
      agent --config "$CONFIG" "$@"
    ;;
  check)
    shift || true
    exec "$JAVA_BIN" -cp "$APP_HOME/lib/*" \
      io.github.yasakawa.faskw.dashboard.DashboardMain \
      check --agent-config "$CONFIG" "$@"
    ;;
  *)
    echo "Usage: $0 {agent|check}" >&2
    exit 2
    ;;
esac
