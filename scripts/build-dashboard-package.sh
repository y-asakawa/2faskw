#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python3}"
VERSION_CONFIG="${VERSION_CONFIG:-$ROOT_DIR/version.ini}"

load_version_config() {
  [[ -f "$VERSION_CONFIG" ]] || {
    echo "ERROR: version config not found: $VERSION_CONFIG" >&2
    exit 1
  }
  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    [[ "$line" == *=* ]] || {
      echo "ERROR: invalid version config line: $line" >&2
      exit 1
    }
    key="${line%%=*}"
    value="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || {
      echo "ERROR: invalid version config key: $key" >&2
      exit 1
    }
    printf -v "$key" '%s' "$value"
  done < "$VERSION_CONFIG"
}

load_version_config
: "${VERSION:?ERROR: VERSION is missing from version.ini}"
: "${DASHBOARD_ARTIFACT_ID:?ERROR: DASHBOARD_ARTIFACT_ID is missing from version.ini}"

DASHBOARD_BASE_NAME="${DASHBOARD_ARTIFACT_ID}-${VERSION}"
DASHBOARD_BUILD_ROOT="$ROOT_DIR/dashboard/target"
DASHBOARD_DIST_ROOT="$ROOT_DIR/target/dashboard-dist"
DASHBOARD_DIST_DIR="$DASHBOARD_DIST_ROOT/$DASHBOARD_BASE_NAME"
DASHBOARD_VERSIONED_ZIP="$DASHBOARD_DIST_ROOT/$DASHBOARD_BASE_NAME.zip"
DASHBOARD_FIXED_ZIP="$DASHBOARD_DIST_ROOT/$DASHBOARD_ARTIFACT_ID.zip"

"$MVN" -B -ntp -f dashboard/pom.xml -Drevision="$VERSION" clean package

DASHBOARD_MAVEN_VERSION="$("$MVN" -q -DforceStdout -f dashboard/pom.xml \
  -Drevision="$VERSION" help:evaluate -Dexpression=project.version)"
if [[ "$DASHBOARD_MAVEN_VERSION" != "$VERSION" ]]; then
  echo "ERROR: Dashboard Maven project.version ($DASHBOARD_MAVEN_VERSION) does not match version.ini VERSION ($VERSION)" >&2
  exit 1
fi

"$PYTHON" "$ROOT_DIR/scripts/check-jar-manifest-version.py" \
  "$DASHBOARD_BUILD_ROOT/$DASHBOARD_BASE_NAME.jar" \
  "$VERSION"

rm -rf \
  "$DASHBOARD_DIST_DIR" \
  "$DASHBOARD_VERSIONED_ZIP" \
  "$DASHBOARD_VERSIONED_ZIP.asc" \
  "$DASHBOARD_FIXED_ZIP" \
  "$DASHBOARD_FIXED_ZIP.asc"

mkdir -p \
  "$DASHBOARD_DIST_DIR/bin" \
  "$DASHBOARD_DIST_DIR/lib" \
  "$DASHBOARD_DIST_DIR/conf" \
  "$DASHBOARD_DIST_DIR/examples/systemd" \
  "$DASHBOARD_DIST_DIR/examples/httpd" \
  "$DASHBOARD_DIST_DIR/examples/logrotate" \
  "$DASHBOARD_DIST_DIR/package-metadata"

cp LICENSE "$DASHBOARD_DIST_DIR/LICENSE"
cp NOTICE "$DASHBOARD_DIST_DIR/NOTICE"
cp THIRD-PARTY-NOTICES.md "$DASHBOARD_DIST_DIR/THIRD-PARTY-NOTICES.md"
cp dashboard/package/bin/*.sh "$DASHBOARD_DIST_DIR/bin/"
chmod 0755 "$DASHBOARD_DIST_DIR"/bin/*.sh
cp "dashboard/target/$DASHBOARD_BASE_NAME.jar" "$DASHBOARD_DIST_DIR/lib/"
cp dashboard/target/dashboard-lib/*.jar "$DASHBOARD_DIST_DIR/lib/"
cp dashboard/package/conf/* "$DASHBOARD_DIST_DIR/conf/"
cp dashboard/package/examples/systemd/* "$DASHBOARD_DIST_DIR/examples/systemd/"
cp dashboard/package/examples/httpd/* "$DASHBOARD_DIST_DIR/examples/httpd/"
cp dashboard/package/examples/logrotate/* "$DASHBOARD_DIST_DIR/examples/logrotate/"

"$PYTHON" - "$VERSION" \
  plugin-metadata/DASHBOARD-PACKAGE-README.md.in \
  "$DASHBOARD_DIST_DIR/README.md" <<'PY'
import pathlib
import re
import sys

version, source_name, target_name = sys.argv[1:4]
source = pathlib.Path(source_name)
target = pathlib.Path(target_name)
data = source.read_text(encoding="utf-8").replace("@VERSION@", version)
unresolved = sorted(set(re.findall(r"@[A-Z][A-Z0-9_]*@", data)))
if unresolved:
    raise SystemExit("unresolved Dashboard README tokens: " + ", ".join(unresolved))
target.write_text(data, encoding="utf-8")
PY

(
  cd "$DASHBOARD_DIST_DIR"
  find . -type f | sed 's#^\./##' | sort > package-metadata/PACKAGE-CONTENTS.txt
  if command -v sha256sum >/dev/null 2>&1; then
    xargs sha256sum < package-metadata/PACKAGE-CONTENTS.txt \
      > package-metadata/PACKAGE-MANIFEST.sha256
  else
    while IFS= read -r file; do
      shasum -a 256 "$file"
    done < package-metadata/PACKAGE-CONTENTS.txt \
      > package-metadata/PACKAGE-MANIFEST.sha256
  fi
)

"$PYTHON" - "$DASHBOARD_DIST_ROOT" "$DASHBOARD_BASE_NAME" "$DASHBOARD_VERSIONED_ZIP" <<'PY'
import os
import pathlib
import sys
import zipfile

dist_root, base_name, zip_name = sys.argv[1:4]
base_dir = pathlib.Path(dist_root, base_name)
with zipfile.ZipFile(zip_name, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(base_dir.rglob("*")):
        if not path.is_file():
            continue
        name = path.relative_to(pathlib.Path(dist_root)).as_posix()
        info = zipfile.ZipInfo(name)
        info.external_attr = (path.stat().st_mode & 0xFFFF) << 16
        with path.open("rb") as handle:
            archive.writestr(info, handle.read())
PY

cp "$DASHBOARD_VERSIONED_ZIP" "$DASHBOARD_FIXED_ZIP"
cmp -s "$DASHBOARD_VERSIONED_ZIP" "$DASHBOARD_FIXED_ZIP" || {
  echo "ERROR: fixed-name and versioned Dashboard ZIP files differ" >&2
  exit 1
}

echo "dashboard_dist_dir=$DASHBOARD_DIST_DIR"
echo "dashboard_zip=$DASHBOARD_VERSIONED_ZIP"
echo "dashboard_public_zip=$DASHBOARD_FIXED_ZIP"
