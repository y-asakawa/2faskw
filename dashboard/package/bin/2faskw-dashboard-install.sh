#!/usr/bin/env bash
set -euo pipefail

PACKAGE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MODE=""
PREFIX=""
APPLY=0

usage() {
  cat <<'EOF'
Usage:
  sudo ./bin/2faskw-dashboard-install.sh --mode server|agent \
    [--prefix /opt/2faskw-dashboard] [--apply]

Without --apply, the installer only prints the planned paths.
It does not enable or start a systemd unit.
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --mode)
      MODE="${2:-}"
      shift 2
      ;;
    --prefix)
      PREFIX="${2:-}"
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

[[ "$MODE" == "server" || "$MODE" == "agent" ]] || {
  echo "ERROR: --mode must be server or agent" >&2
  exit 2
}

if [[ -z "$PREFIX" ]]; then
  if [[ "$MODE" == "server" ]]; then
    PREFIX="/opt/2faskw-dashboard"
  else
    PREFIX="/opt/2faskw-dashboard-agent"
  fi
fi

if [[ "$MODE" == "server" ]]; then
  SERVICE_USER="2faskw-dashboard"
  CONFIG_DIR="/etc/2faskw-dashboard"
  STATE_DIR="/var/lib/2faskw-dashboard"
  CONFIG_SOURCE="dashboard.properties.example"
  CONFIG_TARGET="dashboard.properties"
else
  SERVICE_USER="2faskw-dashboard-agent"
  CONFIG_DIR="/etc/2faskw-dashboard-agent"
  STATE_DIR="/var/lib/2faskw-dashboard-agent"
  CONFIG_SOURCE="agent.properties.example"
  CONFIG_TARGET="agent.properties"
fi

printf 'mode=%s\nprefix=%s\nconfig=%s/%s\nstate=%s\n' \
  "$MODE" "$PREFIX" "$CONFIG_DIR" "$CONFIG_TARGET" "$STATE_DIR"

if [[ "$APPLY" != "1" ]]; then
  echo "dry-run only; add --apply to install files"
  exit 0
fi

[[ "$(id -u)" == "0" ]] || {
  echo "ERROR: --apply must be run as root" >&2
  exit 1
}

if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  command -v useradd >/dev/null 2>&1 || {
    echo "ERROR: useradd is required to create $SERVICE_USER" >&2
    exit 1
  }
  useradd --system --home-dir "$STATE_DIR" --shell /sbin/nologin "$SERVICE_USER"
fi

install -d -m 0755 "$PREFIX/bin" "$PREFIX/lib"
install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 \
  "$CONFIG_DIR" "$CONFIG_DIR/credentials" "$STATE_DIR"
install -m 0755 "$PACKAGE_DIR"/bin/*.sh "$PREFIX/bin/"
install -m 0644 "$PACKAGE_DIR"/lib/*.jar "$PREFIX/lib/"

if [[ ! -e "$CONFIG_DIR/$CONFIG_TARGET" ]]; then
  install -m 0640 "$PACKAGE_DIR/conf/$CONFIG_SOURCE" "$CONFIG_DIR/$CONFIG_TARGET"
fi
if [[ "$MODE" == "server" && ! -e "$CONFIG_DIR/roles.properties" ]]; then
  install -m 0640 "$PACKAGE_DIR/conf/roles.properties.example" \
    "$CONFIG_DIR/roles.properties"
fi
if [[ ! -e "$CONFIG_DIR/reason-mapping.properties" ]]; then
  install -m 0644 "$PACKAGE_DIR/conf/reason-mapping.properties" \
    "$CONFIG_DIR/reason-mapping.properties"
fi
chown "$SERVICE_USER:$SERVICE_USER" "$CONFIG_DIR"/*.properties

echo "installed; review configuration, run check, then install the appropriate systemd example"
