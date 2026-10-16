#!/usr/bin/env bash
# Standalone package builder. Release signing is owned by build-plugin-package.sh.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
MVN="${MVN:-mvn}"
PYTHON="${PYTHON:-python3}"
VERSION_CONFIG="${VERSION_CONFIG:-$ROOT_DIR/version.ini}"
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%%#*}"
  [[ "$line" == *=* ]] || continue
  key="${line%%=*}"
  value="${line#*=}"
  case "$key" in VERSION|SP_IMPORT_ARTIFACT_ID) printf -v "$key" '%s' "$value" ;; esac
done < "$VERSION_CONFIG"
: "${VERSION:?VERSION is required}"
: "${SP_IMPORT_ARTIFACT_ID:?SP_IMPORT_ARTIFACT_ID is required}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][a-zA-Z0-9.-]+)?$ && "$SP_IMPORT_ARTIFACT_ID" =~ ^[a-z0-9][a-z0-9-]+$ ]] || {
  echo 'ERROR: unsafe package version/artifact ID' >&2
  exit 2
}
BASE="$SP_IMPORT_ARTIFACT_ID-$VERSION"
DIST="$ROOT_DIR/target/sp-import-dist"
PACKAGE="$DIST/$BASE"
mkdir -p "$DIST"
rm -f "$DIST/$BASE.zip.asc" "$DIST/$SP_IMPORT_ARTIFACT_ID.zip.asc"
"$MVN" -B -ntp -f sp-import-tools/pom.xml -Drevision="$VERSION" clean package
for property in version artifactId; do
  actual="$("$MVN" -q -DforceStdout -f sp-import-tools/pom.xml -Drevision="$VERSION" help:evaluate -Dexpression="project.$property")"
  expected="$VERSION"
  [[ "$property" == version ]] || expected="$SP_IMPORT_ARTIFACT_ID"
  [[ "$actual" == "$expected" ]] || { echo "ERROR: Maven $property differs from version.ini" >&2; exit 1; }
done
"$PYTHON" scripts/check-jar-manifest-version.py "sp-import-tools/target/$BASE.jar" "$VERSION"
rm -rf "$PACKAGE"
mkdir -p "$PACKAGE/lib" "$PACKAGE/package-metadata"
install -m 0755 sp-import-tools/graphicalmatrix-sp-import.sh "$PACKAGE/graphicalmatrix-sp-import.sh"
cp "sp-import-tools/target/$BASE.jar" "$PACKAGE/lib/"
cp sp-import-tools/target/runtime-libraries/jackson-core-2.21.5.jar "$PACKAGE/lib/"
cp LICENSE NOTICE THIRD-PARTY-NOTICES.md "$PACKAGE/"
"$PYTHON" - "$PACKAGE" "$VERSION" <<'PY'
import hashlib
import json
import pathlib
import sys

package = pathlib.Path(sys.argv[1])
version = sys.argv[2]
template = pathlib.Path('plugin-metadata/SP-IMPORT-PACKAGE-README.md.in').read_text(encoding='utf-8')
(package / 'README.md').write_text(template.replace('@VERSION@', version), encoding='utf-8')
(package / 'package-metadata/VERSION').write_text(version + '\n', encoding='utf-8')
(package / 'package-metadata/COMPATIBILITY.txt').write_text(
    'Runtime: Bash, Java 21 or newer; no Python runtime or Maven required.\n'
    'Target CLI output contract: 2FAS-KW 1.3.5 and 1.4.0.\n'
    'Source: static Shibboleth IdP configurations and 2FAS-KW management schemas 1/2 (1.3.5/1.4.0 contract).\n'
    'Managed bundle: v2; generic: v4 with original snapshot evidence (v3 readable; legacy v1 authorization unassessed).\n'
    'Aggregate extraction is explicit and generic-only; no automatic signature verification or filter engine.\n'
    'Extracted snapshots/known validation filters require independent metadata review and typed apply confirmation.\n'
    'Isolation is mandatory for managed apply and generic registration-only with pending authorization.\n'
    'Generic authorization is reported separately, never applied; exit 10 means authorization pending.\n'
    'Explicit generic --defer-attributes fixes builtin none with no approval/profile creation; exit 11 means attributes pending.\n'
    'Deferred apply requires registration-only/isolation flags and typed acknowledgement; none does not suppress shared release policies.\n'
    'Snapshot validity is rechecked at target preparation and before writes/add; source HTTP refresh is not inherited.\n'
    'Unknown conditions are held by default; explicit deferred mode retains attribute holds as phase-two work, never resolves metadata/global holds.\n'
    'Historical IdP runtime behavior is not certified.\n', encoding='utf-8')
(package / 'package-metadata/RUNTIME-DEPENDENCIES.txt').write_text(
    'com.fasterxml.jackson.core:jackson-core:2.21.5 | Apache-2.0 | lib/jackson-core-2.21.5.jar\n', encoding='utf-8')
(package / 'package-metadata/SBOM.cdx.json').write_text(json.dumps({
    'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
    'metadata': {'component': {'type': 'application', 'name': '2faskw-sp-import-tools', 'version': version,
        'purl': f'pkg:maven/io.github.yasakawa/2faskw-sp-import-tools@{version}'}},
    'components': [{'type': 'library', 'group': 'com.fasterxml.jackson.core', 'name': 'jackson-core',
        'version': '2.21.5', 'purl': 'pkg:maven/com.fasterxml.jackson.core/jackson-core@2.21.5',
        'licenses': [{'license': {'id': 'Apache-2.0'}}],
        'hashes': [{'alg': 'SHA-256', 'content': hashlib.sha256((package / 'lib/jackson-core-2.21.5.jar').read_bytes()).hexdigest()}]}]
}, indent=2) + '\n', encoding='utf-8')
content = package / 'package-metadata/PACKAGE-CONTENTS.txt'
content.touch()
files = sorted(p for p in package.rglob('*') if p.is_file())
content.write_text(''.join(str(p.relative_to(package)) + '\n' for p in files), encoding='utf-8')
(package / 'package-metadata/PACKAGE-MANIFEST.sha256').write_text(''.join(
    hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + str(p.relative_to(package)) + '\n' for p in files), encoding='utf-8')
PY
"$PYTHON" - "$DIST" "$BASE" <<'PY'
import pathlib
import sys
import zipfile

dist = pathlib.Path(sys.argv[1])
base = sys.argv[2]
with zipfile.ZipFile(dist / (base + '.zip'), 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((dist / base).rglob('*')):
        if path.is_file():
            info = zipfile.ZipInfo(str(path.relative_to(dist)))
            info.external_attr = (path.stat().st_mode & 0xFFFF) << 16
            archive.writestr(info, path.read_bytes())
PY
cp "$DIST/$BASE.zip" "$DIST/$SP_IMPORT_ARTIFACT_ID.zip"
cmp -s "$DIST/$BASE.zip" "$DIST/$SP_IMPORT_ARTIFACT_ID.zip"
"$PYTHON" scripts/check-sp-import-package.py "$DIST/$SP_IMPORT_ARTIFACT_ID.zip" "$BASE" "$VERSION"
echo "sp_import_zip=$DIST/$SP_IMPORT_ARTIFACT_ID.zip"
