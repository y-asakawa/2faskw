#!/usr/bin/env bash
#
# Verify that plugin upgrades preserve configuration hidden by directory permissions.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION="$(awk -F= '$1 == "VERSION" { print $2; exit }' "$ROOT/version.ini")"
ARCHIVE="$ROOT/target/plugin-dist/2faskw-idp-plugin.zip"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

if [[ "$(id -u)" -eq 0 ]]; then
  echo "SKIP: this regression test must run as a non-root user"
  exit 0
fi
if ! command -v sudo >/dev/null 2>&1 || ! sudo -n true 2>/dev/null; then
  if [[ "${CI:-}" == "true" ]]; then
    fail "passwordless sudo is required in CI"
  fi
  echo "SKIP: passwordless sudo is unavailable"
  exit 0
fi
trap 'sudo -n rm -rf "$TMP"' EXIT HUP INT TERM
[[ -s "$ARCHIVE" ]] || fail "release archive is missing: $ARCHIVE"

mkdir -p "$TMP/package" "$TMP/idp"
unzip -q "$ARCHIVE" -d "$TMP/package"
PACKAGE_DIR="$TMP/package/2faskw-idp-plugin-$VERSION"
INSTALLER="$PACKAGE_DIR/bin/graphicalmatrix-plugin-config.sh"
CONFIG_DIR="$TMP/idp/conf/graphicalmatrix"
SENTINEL="site-specific-setting-must-survive"
CONFIGS=(
  graphicalmatrix.properties
  db.properties
  ldap.properties
  webauthn-ldap.properties
  api.properties
  mfa-policy.properties
  sp-management.properties
)

[[ -x "$INSTALLER" ]] || fail "packaged installer is missing or not executable"

sudo install -d -m 0750 -o root -g root "$CONFIG_DIR"
for name in "${CONFIGS[@]}"; do
  printf '%s\n' "$SENTINEL:$name" | sudo tee "$CONFIG_DIR/$name" >/dev/null
  sudo chown root:root "$CONFIG_DIR/$name"
  sudo chmod 0640 "$CONFIG_DIR/$name"
done

"$INSTALLER" \
  --idp-home "$TMP/idp" \
  --skip-package-check \
  --apply >/dev/null

for name in "${CONFIGS[@]}"; do
  actual="$(sudo cat "$CONFIG_DIR/$name")"
  [[ "$actual" == "$SENTINEL:$name" ]] \
    || fail "protected configuration was overwritten: $name"
  sudo find "$CONFIG_DIR" -maxdepth 1 -type f \
    -name "$name.idpnew.*" -print -quit | grep -q . \
    || fail "deferred template was not created: $name"
done

MANIFEST="$(sudo find "$CONFIG_DIR" -maxdepth 1 -type f \
  -name 'install-manifest-*.tsv' -print -quit)"
[[ -n "$MANIFEST" ]] || fail "install manifest was not created"

for name in "${CONFIGS[@]}"; do
  sudo awk -F '\t' -v destination="$CONFIG_DIR/$name" '
    $1 == "install_template_deferred" && index($3, destination ".idpnew.") == 1 { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "$MANIFEST" || fail "manifest did not record a deferred template: $name"
done

echo "PASS: protected plugin configurations were preserved"
