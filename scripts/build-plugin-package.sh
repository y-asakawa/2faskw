#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python3}"

"$MVN" -B -ntp clean package

VERSION="$("$MVN" -q -DforceStdout help:evaluate -Dexpression=project.version)"
ARTIFACT_ID="$("$MVN" -q -DforceStdout help:evaluate -Dexpression=project.artifactId)"
BASE_NAME="${ARTIFACT_ID}-${VERSION}"
ADMIN_BASE_NAME="2faskw-admin-tools-${VERSION}"
DIST_ROOT="$ROOT_DIR/target/plugin-dist"
DIST_DIR="$DIST_ROOT/$BASE_NAME"
ZIP_FILE="$DIST_ROOT/$BASE_NAME.zip"
ADMIN_DIST_ROOT="$ROOT_DIR/target/admin-dist"
ADMIN_DIST_DIR="$ADMIN_DIST_ROOT/$ADMIN_BASE_NAME"
ADMIN_ZIP_FILE="$ADMIN_DIST_ROOT/$ADMIN_BASE_NAME.zip"

rm -rf "$DIST_DIR" "$ZIP_FILE" "$ADMIN_DIST_DIR" "$ADMIN_ZIP_FILE"
mkdir -p \
  "$DIST_DIR/webapp/WEB-INF/lib" \
  "$DIST_DIR/bootstrap" \
  "$DIST_DIR/conf/graphicalmatrix/assets" \
  "$DIST_DIR/conf/graphicalmatrix/views" \
  "$DIST_DIR/conf/graphicalmatrix/graphicals" \
  "$DIST_DIR/bin" \
  "$DIST_DIR/examples" \
  "$DIST_DIR/examples/logrotate" \
  "$DIST_DIR/examples/systemd" \
  "$DIST_DIR/plugin-metadata" \
  "$DIST_DIR/docs"

cp LICENSE "$DIST_DIR/LICENSE"
cp NOTICE "$DIST_DIR/NOTICE"
cp THIRD-PARTY-NOTICES.md "$DIST_DIR/THIRD-PARTY-NOTICES.md"

cp "target/$BASE_NAME.jar" "$DIST_DIR/webapp/WEB-INF/lib/"
cp target/plugin-lib/*.jar "$DIST_DIR/webapp/WEB-INF/lib/"

cp src/main/resources/jp/ac/example/graphicalmatrix/plugin/plugin.properties \
  "$DIST_DIR/bootstrap/plugin.properties"
cat > "$DIST_DIR/bootstrap/keys.txt" <<'EOF'
# PoC placeholder.
# Add the plugin signing public key material before external distribution.
EOF

cp graphicalmatrix.properties "$DIST_DIR/conf/graphicalmatrix/graphicalmatrix.properties.idpnew"
cp db.properties "$DIST_DIR/conf/graphicalmatrix/db.properties.idpnew"
cp admin.properties "$DIST_DIR/conf/graphicalmatrix/admin.properties.idpnew"
sed \
  -e 's/^[[:space:]]*graphicalmatrix[.]api[.]enabled[[:space:]]*=.*/graphicalmatrix.api.enabled = false/' \
  api.properties > "$DIST_DIR/conf/graphicalmatrix/api.properties.idpnew"
cp mfa-policy.properties "$DIST_DIR/conf/graphicalmatrix/mfa-policy.properties.idpnew"
cp postgresql-schema.sql "$DIST_DIR/conf/graphicalmatrix/postgresql-schema.sql"
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
cp scripts/graphicalmatrix-api-curl-test.sh "$DIST_DIR/bin/graphicalmatrix-api-curl-test.sh"
cp scripts/graphicalmatrix-plugin-check.sh "$DIST_DIR/bin/graphicalmatrix-plugin-check.sh"
cp scripts/graphicalmatrix-plugin-config.sh "$DIST_DIR/bin/graphicalmatrix-plugin-config.sh"
cp scripts/graphicalmatrix-plugin-uninstall.sh "$DIST_DIR/bin/graphicalmatrix-plugin-uninstall.sh"
cp scripts/graphicalmatrix-plugin-webxml.sh "$DIST_DIR/bin/graphicalmatrix-plugin-webxml.sh"
chmod 0755 \
  "$DIST_DIR/bin/graphicalmatrix-db-migration.sh" \
  "$DIST_DIR/bin/graphicalmatrix-api-token.sh" \
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
cp access-control.xml "$DIST_DIR/examples/access-control.xml"
cp attribute-resolver.xml "$DIST_DIR/examples/attribute-resolver.xml"
cp examples/logrotate/graphicalmatrix-audit "$DIST_DIR/examples/logrotate/graphicalmatrix-audit"
cp examples/systemd/graphicalmatrix-csv-import.path "$DIST_DIR/examples/systemd/graphicalmatrix-csv-import.path"
cp examples/systemd/graphicalmatrix-csv-import.service "$DIST_DIR/examples/systemd/graphicalmatrix-csv-import.service"

cp plugin-metadata/graphicalmatrix-plugin.properties "$DIST_DIR/plugin-metadata/graphicalmatrix-plugin.properties"
cp plugin-metadata/README.md "$DIST_DIR/plugin-metadata/README.md"
cp docs/README.md "$DIST_DIR/docs/README.md"
cp docs/INSTALL.md "$DIST_DIR/docs/INSTALL.md"
cp docs/SECURITY.md "$DIST_DIR/docs/SECURITY.md"
cp docs/SECURITY-CHECKLIST.md "$DIST_DIR/docs/SECURITY-CHECKLIST.md"
cp docs/API-TOKEN-ROTATION.md "$DIST_DIR/docs/API-TOKEN-ROTATION.md"
cp docs/API-CURL-TESTS.md "$DIST_DIR/docs/API-CURL-TESTS.md"
cp docs/ADMIN-TOOLS.md "$DIST_DIR/docs/ADMIN-TOOLS.md"
cp docs/CONFIG-REFERENCE.md "$DIST_DIR/docs/CONFIG-REFERENCE.md"
cp docs/CSV-EXPORT.md "$DIST_DIR/docs/CSV-EXPORT.md"
cp docs/DB-MIGRATION.md "$DIST_DIR/docs/DB-MIGRATION.md"
cp docs/SEQUENCE-STORAGE-MIGRATION.md "$DIST_DIR/docs/SEQUENCE-STORAGE-MIGRATION.md"
cp docs/SIGNED-PLUGIN-PACKAGE.md "$DIST_DIR/docs/SIGNED-PLUGIN-PACKAGE.md"
cp docs/LOGROTATE.md "$DIST_DIR/docs/LOGROTATE.md"
cp docs/INSTALL_LOADTEST.md "$DIST_DIR/docs/INSTALL_LOADTEST.md"
cp docs/openapi.yaml "$DIST_DIR/docs/openapi.yaml"

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

"$PYTHON" - "$DIST_ROOT" "$BASE_NAME" "$ZIP_FILE" <<'PY'
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

echo "plugin_dist_dir=$DIST_DIR"
echo "plugin_zip=$ZIP_FILE"

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
cp graphicalmatrix.properties "$ADMIN_DIST_DIR/conf/graphicalmatrix/graphicalmatrix.properties.adminnew"
cp admin.properties "$ADMIN_DIST_DIR/conf/graphicalmatrix/admin.properties.adminnew"
cp postgresql-schema.sql "$ADMIN_DIST_DIR/conf/graphicalmatrix/postgresql-schema.sql"
cp examples/systemd/graphicalmatrix-csv-import.path "$ADMIN_DIST_DIR/examples/systemd/graphicalmatrix-csv-import.path"
cp examples/systemd/graphicalmatrix-csv-import.service "$ADMIN_DIST_DIR/examples/systemd/graphicalmatrix-csv-import.service"

cp docs/ADMIN-TOOLS.md "$ADMIN_DIST_DIR/docs/ADMIN-TOOLS.md"
cp docs/CONFIG-REFERENCE.md "$ADMIN_DIST_DIR/docs/CONFIG-REFERENCE.md"
cp docs/CSV-EXPORT.md "$ADMIN_DIST_DIR/docs/CSV-EXPORT.md"
cp docs/DB-MIGRATION.md "$ADMIN_DIST_DIR/docs/DB-MIGRATION.md"
cp docs/SEQUENCE-STORAGE-MIGRATION.md "$ADMIN_DIST_DIR/docs/SEQUENCE-STORAGE-MIGRATION.md"
cp docs/SECURITY.md "$ADMIN_DIST_DIR/docs/SECURITY.md"
cp docs/SECURITY-CHECKLIST.md "$ADMIN_DIST_DIR/docs/SECURITY-CHECKLIST.md"

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
