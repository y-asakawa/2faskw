#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python3}"
VERSION_CONFIG="${VERSION_CONFIG:-$ROOT_DIR/version.ini}"

load_version_config() {
  if [[ ! -f "$VERSION_CONFIG" ]]; then
    echo "ERROR: version config not found: $VERSION_CONFIG" >&2
    exit 1
  fi

  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    if [[ "$line" != *=* ]]; then
      echo "ERROR: invalid version config line: $line" >&2
      exit 1
    fi
    key="${line%%=*}"
    value="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ ! "$key" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
      echo "ERROR: invalid version config key: $key" >&2
      exit 1
    fi
    printf -v "$key" '%s' "$value"
    export "$key"
  done < "$VERSION_CONFIG"
}

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "ERROR: missing required setting in version.ini: $name" >&2
    exit 1
  fi
}

render_template() {
  local source="$1"
  local target="$2"
  "$PYTHON" - "$source" "$target" <<'PY'
import os
import pathlib
import re
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
data = source.read_text(encoding="utf-8")

keys = [
    "VERSION",
    "ARTIFACT_ID",
    "ADMIN_ARTIFACT_ID",
    "PLUGIN_ID",
    "PLUGIN_NAME",
    "SUPPORT_LEVEL",
    "DOWNLOAD_BASE_URL",
    "DOWNLOAD_URL",
    "PLUGIN_METADATA_URL",
    "IDP_VERSION_MIN",
    "IDP_VERSION_MAX",
    "JAVA_VERSION_MIN",
    "JETTY_VERSION_MIN",
    "REQUIRED_MODULES",
    "OPTIONAL_PLUGINS",
    "API_DEFAULT",
    "DATABASE_DEFAULT",
    "BASE_NAME",
    "PLUGIN_ARCHIVE_BASE_NAME",
    "ADMIN_BASE_NAME",
    "RUNTIME_LIBRARIES",
]
for key in keys:
    data = data.replace("@" + key + "@", os.environ.get(key, ""))

version = os.environ["VERSION"]
base_name = os.environ["BASE_NAME"]
admin_base_name = os.environ["ADMIN_BASE_NAME"]
data = data.replace("${project.version}", version)
data = data.replace("${plugin.metadata.url}", os.environ["PLUGIN_METADATA_URL"])
version_pattern = r"[0-9]+(?:\.[0-9]+)*(?:[-+][0-9A-Za-z.-]+)?"

# Keep source docs readable while making packaged docs version-correct.
data = re.sub(r"2faskw-idp-plugin-" + version_pattern, base_name, data)
data = re.sub(r"2faskw-admin-tools-" + version_pattern, admin_base_name, data)
data = re.sub(r"(?m)^(\s*version:\s*)" + version_pattern + r"\s*$", r"\g<1>" + version, data)
data = re.sub(r"(?m)^(plugin\.version\s*=\s*)" + version_pattern + r"\s*$", r"\g<1>" + version, data)
data = re.sub(r"(/shibboleth/plugins/2faskw/)" + version_pattern + r"(/)", r"\g<1>" + version + r"\2", data)
data = re.sub(r"(\.downloadURL\.)" + version_pattern + r"(\s*=)", r"\g<1>" + version + r"\2", data)
data = re.sub(r"(\.baseName\.)" + version_pattern + r"(\s*=)", r"\g<1>" + version + r"\2", data)
data = re.sub(r"(\.adminToolsBaseName\.)" + version_pattern + r"(\s*=)", r"\g<1>" + version + r"\2", data)
data = re.sub(r"(\.idpVersionMin\.)" + version_pattern + r"(\s*=)", r"\g<1>" + version + r"\2", data)
data = re.sub(r"(\.idpVersionMax\.)" + version_pattern + r"(\s*=)", r"\g<1>" + version + r"\2", data)
data = re.sub(r"(\.supportLevel\.)" + version_pattern + r"(\s*=)", r"\g<1>" + version + r"\2", data)

unresolved = sorted(set(re.findall(r"@[A-Z][A-Z0-9_]*@", data)))
if unresolved:
    raise SystemExit("unresolved template tokens in " + str(source) + ": " + ", ".join(unresolved))

target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(data, encoding="utf-8")
PY
}

copy_versioned() {
  render_template "$1" "$2"
}

load_version_config
require_var VERSION
require_var ARTIFACT_ID
require_var ADMIN_ARTIFACT_ID
require_var PLUGIN_ID
require_var DOWNLOAD_BASE_URL
require_var RELEASE_TAG
require_var PLUGIN_METADATA_URL

PUBLIC_SIGNING_KEY_FILE="$ROOT_DIR/bootstrap/keys.txt"
if [[ ! -s "$PUBLIC_SIGNING_KEY_FILE" ]] \
  || ! grep -q -- '-----BEGIN PGP PUBLIC KEY BLOCK-----' "$PUBLIC_SIGNING_KEY_FILE"; then
  echo "ERROR: public signing key not found or invalid: $PUBLIC_SIGNING_KEY_FILE" >&2
  exit 1
fi

"$MVN" -B -ntp -Drevision="$VERSION" -Dplugin.metadata.url="$PLUGIN_METADATA_URL" clean package

MAVEN_VERSION="$("$MVN" -q -DforceStdout -Drevision="$VERSION" -Dplugin.metadata.url="$PLUGIN_METADATA_URL" help:evaluate -Dexpression=project.version)"
MAVEN_ARTIFACT_ID="$("$MVN" -q -DforceStdout -Drevision="$VERSION" -Dplugin.metadata.url="$PLUGIN_METADATA_URL" help:evaluate -Dexpression=project.artifactId)"
if [[ "$MAVEN_VERSION" != "$VERSION" ]]; then
  echo "ERROR: Maven project.version ($MAVEN_VERSION) does not match version.ini VERSION ($VERSION)" >&2
  exit 1
fi
if [[ "$MAVEN_ARTIFACT_ID" != "$ARTIFACT_ID" ]]; then
  echo "ERROR: Maven artifactId ($MAVEN_ARTIFACT_ID) does not match version.ini ARTIFACT_ID ($ARTIFACT_ID)" >&2
  exit 1
fi

BASE_NAME="${ARTIFACT_ID}-${VERSION}"
PLUGIN_ARCHIVE_BASE_NAME="$ARTIFACT_ID"
ADMIN_BASE_NAME="${ADMIN_ARTIFACT_ID}-${VERSION}"
DOWNLOAD_URL="${DOWNLOAD_BASE_URL%/}/${RELEASE_TAG}/"
RUNTIME_LIBRARIES="$(find target/plugin-lib -maxdepth 1 -type f -name '*.jar' -exec basename {} \; | sort | paste -sd, -)"
export VERSION ARTIFACT_ID ADMIN_ARTIFACT_ID BASE_NAME PLUGIN_ARCHIVE_BASE_NAME ADMIN_BASE_NAME DOWNLOAD_URL RUNTIME_LIBRARIES
DIST_ROOT="$ROOT_DIR/target/plugin-dist"
DIST_DIR="$DIST_ROOT/$BASE_NAME"
ZIP_FILE="$DIST_ROOT/$PLUGIN_ARCHIVE_BASE_NAME.zip"
TAR_GZ_FILE="$DIST_ROOT/$PLUGIN_ARCHIVE_BASE_NAME.tar.gz"
VERSIONED_ZIP_FILE="$DIST_ROOT/$BASE_NAME.zip"
VERSIONED_TAR_GZ_FILE="$DIST_ROOT/$BASE_NAME.tar.gz"
CHECKSUM_FILE="$DIST_ROOT/SHA256SUMS"
CHECKSUM_SIGNATURE_FILE="$CHECKSUM_FILE.asc"
ADMIN_DIST_ROOT="$ROOT_DIR/target/admin-dist"
ADMIN_DIST_DIR="$ADMIN_DIST_ROOT/$ADMIN_BASE_NAME"
ADMIN_ZIP_FILE="$ADMIN_DIST_ROOT/$ADMIN_BASE_NAME.zip"

rm -rf \
  "$DIST_DIR" \
  "$ZIP_FILE" \
  "$ZIP_FILE.asc" \
  "$TAR_GZ_FILE" \
  "$TAR_GZ_FILE.asc" \
  "$VERSIONED_ZIP_FILE" \
  "$VERSIONED_ZIP_FILE.asc" \
  "$VERSIONED_TAR_GZ_FILE" \
  "$VERSIONED_TAR_GZ_FILE.asc" \
  "$CHECKSUM_FILE" \
  "$CHECKSUM_SIGNATURE_FILE" \
  "$ADMIN_DIST_DIR" \
  "$ADMIN_ZIP_FILE"
mkdir -p \
  "$DIST_DIR/webapp/WEB-INF/lib" \
  "$DIST_DIR/bootstrap" \
  "$DIST_DIR/conf/authn" \
  "$DIST_DIR/conf/graphicalmatrix/assets" \
  "$DIST_DIR/conf/graphicalmatrix/views" \
  "$DIST_DIR/conf/graphicalmatrix/graphicals" \
  "$DIST_DIR/bin" \
  "$DIST_DIR/examples" \
  "$DIST_DIR/examples/logrotate" \
  "$DIST_DIR/examples/systemd" \
  "$DIST_DIR/plugin-metadata"

cp LICENSE "$DIST_DIR/LICENSE"
cp NOTICE "$DIST_DIR/NOTICE"
cp THIRD-PARTY-NOTICES.md "$DIST_DIR/THIRD-PARTY-NOTICES.md"
render_template plugin-metadata/PACKAGE-README.md.in "$DIST_DIR/README.md"

cp "target/$BASE_NAME.jar" "$DIST_DIR/webapp/WEB-INF/lib/"
cp target/plugin-lib/*.jar "$DIST_DIR/webapp/WEB-INF/lib/"

render_template src/main/resources/io/github/yasakawa/faskw/plugin/plugin.properties \
  "$DIST_DIR/bootstrap/plugin.properties"
cp "$PUBLIC_SIGNING_KEY_FILE" "$DIST_DIR/bootstrap/keys.txt"

cp graphicalmatrix.properties "$DIST_DIR/conf/graphicalmatrix/graphicalmatrix.properties.idpnew"
cp db.properties "$DIST_DIR/conf/graphicalmatrix/db.properties.idpnew"
cp ldap.properties "$DIST_DIR/conf/graphicalmatrix/ldap.properties.idpnew"
cp webauthn-ldap.properties "$DIST_DIR/conf/graphicalmatrix/webauthn-ldap.properties.idpnew"
cp admin.properties "$DIST_DIR/conf/graphicalmatrix/admin.properties.idpnew"
sed \
  -e 's/^[[:space:]]*graphicalmatrix[.]api[.]enabled[[:space:]]*=.*/graphicalmatrix.api.enabled = false/' \
  api.properties > "$DIST_DIR/conf/graphicalmatrix/api.properties.idpnew"
cp mfa-policy.properties "$DIST_DIR/conf/graphicalmatrix/mfa-policy.properties.idpnew"
cp postgresql-schema.sql "$DIST_DIR/conf/graphicalmatrix/postgresql-schema.sql"
cp webauthn.properties "$DIST_DIR/conf/authn/webauthn.properties.idpnew"
cp webauthn-registration.properties "$DIST_DIR/conf/authn/webauthn-registration.properties.idpnew"
cp webauthn-metadata.properties "$DIST_DIR/conf/authn/webauthn-metadata.properties.idpnew"
cp assets/graphicalmatrix.css "$DIST_DIR/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew"
cp views/*.html "$DIST_DIR/conf/graphicalmatrix/views/"
for file in "$DIST_DIR"/conf/graphicalmatrix/views/*.html; do
  mv "$file" "$file.idpnew"
done
cp graphicals/* "$DIST_DIR/conf/graphicalmatrix/graphicals/"

cp graphicalmatrix-db.sh "$DIST_DIR/bin/graphicalmatrix-db.sh"
chmod 0755 "$DIST_DIR/bin/graphicalmatrix-db.sh"
cp scripts/graphicalmatrix-db-migration.sh "$DIST_DIR/bin/graphicalmatrix-db-migration.sh"
cp scripts/graphicalmatrix-api-token.sh "$DIST_DIR/bin/graphicalmatrix-api-token.sh"
cp scripts/graphicalmatrix-security-upgrade.sh "$DIST_DIR/bin/graphicalmatrix-security-upgrade.sh"
cp scripts/graphicalmatrix-api-curl-test.sh "$DIST_DIR/bin/graphicalmatrix-api-curl-test.sh"
cp scripts/graphicalmatrix-plugin-check.sh "$DIST_DIR/bin/graphicalmatrix-plugin-check.sh"
cp scripts/graphicalmatrix-plugin-config.sh "$DIST_DIR/bin/graphicalmatrix-plugin-config.sh"
cp scripts/graphicalmatrix-plugin-uninstall.sh "$DIST_DIR/bin/graphicalmatrix-plugin-uninstall.sh"
cp scripts/graphicalmatrix-plugin-webxml.sh "$DIST_DIR/bin/graphicalmatrix-plugin-webxml.sh"
chmod 0755 \
  "$DIST_DIR/bin/graphicalmatrix-db-migration.sh" \
  "$DIST_DIR/bin/graphicalmatrix-api-token.sh" \
  "$DIST_DIR/bin/graphicalmatrix-security-upgrade.sh" \
  "$DIST_DIR/bin/graphicalmatrix-api-curl-test.sh" \
  "$DIST_DIR/bin/graphicalmatrix-plugin-check.sh" \
  "$DIST_DIR/bin/graphicalmatrix-plugin-config.sh" \
  "$DIST_DIR/bin/graphicalmatrix-plugin-uninstall.sh" \
  "$DIST_DIR/bin/graphicalmatrix-plugin-webxml.sh"

cp web.xml "$DIST_DIR/examples/web.xml.current-poc.xml"
cp mfa-authn-config.xml "$DIST_DIR/examples/mfa-authn-config.xml"
cp totp-authn-config.xml "$DIST_DIR/examples/totp-authn-config.xml"
cp webauthn-management-config.xml "$DIST_DIR/examples/webauthn-management-config.xml"
cp webauthn-registration-config.xml "$DIST_DIR/examples/webauthn-registration-config.xml"
cp webauthn-ldap-storage-config.xml "$DIST_DIR/examples/webauthn-ldap-storage-config.xml"
cp access-control.xml "$DIST_DIR/examples/access-control.xml"
cp attribute-resolver.xml "$DIST_DIR/examples/attribute-resolver.xml"
cp examples/logrotate/graphicalmatrix-audit "$DIST_DIR/examples/logrotate/graphicalmatrix-audit"
cp examples/systemd/graphicalmatrix-csv-import.path "$DIST_DIR/examples/systemd/graphicalmatrix-csv-import.path"
cp examples/systemd/graphicalmatrix-csv-import.service "$DIST_DIR/examples/systemd/graphicalmatrix-csv-import.service"

render_template plugin-metadata/graphicalmatrix-plugin.properties.in "$DIST_DIR/plugin-metadata/graphicalmatrix-plugin.properties"

(
  cd "$DIST_DIR"
  find . -type f | sed 's#^\./##' | sort > plugin-metadata/PACKAGE-CONTENTS.txt
  if command -v sha256sum >/dev/null 2>&1; then
    xargs sha256sum < plugin-metadata/PACKAGE-CONTENTS.txt > plugin-metadata/PACKAGE-MANIFEST.sha256
  else
    while IFS= read -r file; do
      shasum -a 256 "$file"
    done < plugin-metadata/PACKAGE-CONTENTS.txt > plugin-metadata/PACKAGE-MANIFEST.sha256
  fi
)

create_plugin_zip() {
  local zip_file="$1"
  "$PYTHON" - "$DIST_ROOT" "$BASE_NAME" "$zip_file" <<'PY'
import os
import sys
import zipfile

dist_root, base_name, zip_file = sys.argv[1:4]
base_dir = os.path.join(dist_root, base_name)
with zipfile.ZipFile(zip_file, "w", zipfile.ZIP_DEFLATED) as zf:
    for root, _, files in os.walk(base_dir):
        for name in files:
            path = os.path.join(root, name)
            arcname = os.path.relpath(path, dist_root)
            info = zipfile.ZipInfo(arcname)
            info.external_attr = (os.stat(path).st_mode & 0xFFFF) << 16
            with open(path, "rb") as handle:
                zf.writestr(info, handle.read())
PY
}

create_plugin_zip "$ZIP_FILE"
create_plugin_zip "$VERSIONED_ZIP_FILE"

if ! command -v tar >/dev/null 2>&1; then
  echo "ERROR: tar is required to build the Shibboleth plugin archive" >&2
  exit 1
fi
COPYFILE_DISABLE=1 tar -C "$DIST_ROOT" -czf "$TAR_GZ_FILE" "$BASE_NAME"
COPYFILE_DISABLE=1 tar -C "$DIST_ROOT" -czf "$VERSIONED_TAR_GZ_FILE" "$BASE_NAME"

echo "plugin_dist_dir=$DIST_DIR"
echo "plugin_zip=$ZIP_FILE"
echo "plugin_tar_gz=$TAR_GZ_FILE"
echo "plugin_versioned_zip=$VERSIONED_ZIP_FILE"
echo "plugin_versioned_tar_gz=$VERSIONED_TAR_GZ_FILE"

mkdir -p \
  "$ADMIN_DIST_DIR/bin" \
  "$ADMIN_DIST_DIR/lib" \
  "$ADMIN_DIST_DIR/conf/graphicalmatrix" \
  "$ADMIN_DIST_DIR/docs" \
  "$ADMIN_DIST_DIR/examples/systemd" \
  "$ADMIN_DIST_DIR/package-metadata"

cp LICENSE "$ADMIN_DIST_DIR/LICENSE"
cp NOTICE "$ADMIN_DIST_DIR/NOTICE"
cp THIRD-PARTY-NOTICES.md "$ADMIN_DIST_DIR/THIRD-PARTY-NOTICES.md"

cp "target/$BASE_NAME.jar" "$ADMIN_DIST_DIR/lib/"
cp target/plugin-lib/*.jar "$ADMIN_DIST_DIR/lib/"

cp graphicalmatrix-db.sh "$ADMIN_DIST_DIR/bin/graphicalmatrix-db.sh"
cp scripts/graphicalmatrix-db-migration.sh "$ADMIN_DIST_DIR/bin/graphicalmatrix-db-migration.sh"
cp scripts/graphicalmatrix-admin-install.sh "$ADMIN_DIST_DIR/bin/graphicalmatrix-admin-install.sh"
cp scripts/graphicalmatrix-csv-import-runner.sh "$ADMIN_DIST_DIR/bin/graphicalmatrix-csv-import-runner.sh"
chmod 0755 \
  "$ADMIN_DIST_DIR/bin/graphicalmatrix-db.sh" \
  "$ADMIN_DIST_DIR/bin/graphicalmatrix-db-migration.sh" \
  "$ADMIN_DIST_DIR/bin/graphicalmatrix-admin-install.sh" \
  "$ADMIN_DIST_DIR/bin/graphicalmatrix-csv-import-runner.sh"

cp db.properties "$ADMIN_DIST_DIR/conf/graphicalmatrix/db.properties.adminnew"
cp ldap.properties "$ADMIN_DIST_DIR/conf/graphicalmatrix/ldap.properties.adminnew"
cp graphicalmatrix.properties "$ADMIN_DIST_DIR/conf/graphicalmatrix/graphicalmatrix.properties.adminnew"
cp admin.properties "$ADMIN_DIST_DIR/conf/graphicalmatrix/admin.properties.adminnew"
cp postgresql-schema.sql "$ADMIN_DIST_DIR/conf/graphicalmatrix/postgresql-schema.sql"
cp examples/systemd/graphicalmatrix-csv-import.path "$ADMIN_DIST_DIR/examples/systemd/graphicalmatrix-csv-import.path"
cp examples/systemd/graphicalmatrix-csv-import.service "$ADMIN_DIST_DIR/examples/systemd/graphicalmatrix-csv-import.service"

copy_versioned docs/ADMIN-TOOLS.md "$ADMIN_DIST_DIR/docs/ADMIN-TOOLS.md"
copy_versioned docs/CONFIG-REFERENCE.md "$ADMIN_DIST_DIR/docs/CONFIG-REFERENCE.md"
copy_versioned docs/FAQ.md "$ADMIN_DIST_DIR/docs/FAQ.md"
copy_versioned docs/UPGRADE.md "$ADMIN_DIST_DIR/docs/UPGRADE.md"
copy_versioned docs/CSV-EXPORT.md "$ADMIN_DIST_DIR/docs/CSV-EXPORT.md"
copy_versioned docs/DB-MIGRATION.md "$ADMIN_DIST_DIR/docs/DB-MIGRATION.md"
copy_versioned docs/SEQUENCE-STORAGE-MIGRATION.md "$ADMIN_DIST_DIR/docs/SEQUENCE-STORAGE-MIGRATION.md"
copy_versioned docs/SECURITY-UPGRADE-1.1.0.md "$ADMIN_DIST_DIR/docs/SECURITY-UPGRADE-1.1.0.md"
copy_versioned docs/SECURITY.md "$ADMIN_DIST_DIR/docs/SECURITY.md"
copy_versioned docs/SECURITY-CHECKLIST.md "$ADMIN_DIST_DIR/docs/SECURITY-CHECKLIST.md"

cat > "$ADMIN_DIST_DIR/README.md" <<EOF
# 2FAS-KW Admin Tools ${VERSION}

This package installs only the 2FAS-KW management CLI.
It does not modify Shibboleth IdP web.xml, Jetty, or IdP plugin files.

License and third-party notices are included in LICENSE, NOTICE, and
THIRD-PARTY-NOTICES.md.

See docs/ADMIN-TOOLS.md.
EOF

(
  cd "$ADMIN_DIST_DIR"
  find . -type f | sed 's#^\./##' | sort > package-metadata/PACKAGE-CONTENTS.txt
  if command -v sha256sum >/dev/null 2>&1; then
    xargs sha256sum < package-metadata/PACKAGE-CONTENTS.txt > package-metadata/PACKAGE-MANIFEST.sha256
  else
    while IFS= read -r file; do
      shasum -a 256 "$file"
    done < package-metadata/PACKAGE-CONTENTS.txt > package-metadata/PACKAGE-MANIFEST.sha256
  fi
)

"$PYTHON" - "$ADMIN_DIST_ROOT" "$ADMIN_BASE_NAME" "$ADMIN_ZIP_FILE" <<'PY'
import os
import sys
import zipfile

dist_root, base_name, zip_file = sys.argv[1:4]
base_dir = os.path.join(dist_root, base_name)
with zipfile.ZipFile(zip_file, "w", zipfile.ZIP_DEFLATED) as zf:
    for root, _, files in os.walk(base_dir):
        for name in files:
            path = os.path.join(root, name)
            arcname = os.path.relpath(path, dist_root)
            info = zipfile.ZipInfo(arcname)
            info.external_attr = (os.stat(path).st_mode & 0xFFFF) << 16
            with open(path, "rb") as handle:
                zf.writestr(info, handle.read())
PY

echo "admin_dist_dir=$ADMIN_DIST_DIR"
echo "admin_zip=$ADMIN_ZIP_FILE"
