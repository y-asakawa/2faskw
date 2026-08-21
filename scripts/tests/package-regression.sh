#!/usr/bin/env bash
#
# Validate release archives created by scripts/build-plugin-package.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PYTHON="${PYTHON:-python3}"
VERSION="$(awk -F= '$1 == "VERSION" { print $2; exit }' "$ROOT/version.ini")"
PLUGIN_DIST="$ROOT/target/plugin-dist"
ADMIN_DIST="$ROOT/target/admin-dist"
ADMIN_ROOT="2faskw-admin-tools-$VERSION"
ADMIN_VERSIONED="$ADMIN_DIST/$ADMIN_ROOT.zip"
ADMIN_FIXED="$ADMIN_DIST/2faskw-admin-tools.zip"
DASHBOARD_DIST="$ROOT/target/dashboard-dist"
DASHBOARD_ROOT="2faskw-dashboard-$VERSION"
DASHBOARD_VERSIONED="$DASHBOARD_DIST/$DASHBOARD_ROOT.zip"
DASHBOARD_FIXED="$DASHBOARD_DIST/2faskw-dashboard.zip"
CHECKSUMS="$PLUGIN_DIST/SHA256SUMS"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -n "$VERSION" ]] || fail "VERSION is missing from version.ini"

for file in \
  "$PLUGIN_DIST/2faskw-idp-plugin.tar.gz" \
  "$PLUGIN_DIST/2faskw-idp-plugin.zip" \
  "$ADMIN_VERSIONED" \
  "$ADMIN_FIXED" \
  "$DASHBOARD_VERSIONED" \
  "$DASHBOARD_FIXED" \
  "$CHECKSUMS"; do
  [[ -s "$file" ]] || fail "required artifact is missing or empty: $file"
done

cmp -s "$ADMIN_VERSIONED" "$ADMIN_FIXED" \
  || fail "fixed-name and versioned Admin Tools ZIP files differ"
cmp -s "$DASHBOARD_VERSIONED" "$DASHBOARD_FIXED" \
  || fail "fixed-name and versioned Dashboard ZIP files differ"

cp \
  "$PLUGIN_DIST/2faskw-idp-plugin.tar.gz" \
  "$PLUGIN_DIST/2faskw-idp-plugin.zip" \
  "$ADMIN_FIXED" \
  "$DASHBOARD_FIXED" \
  "$CHECKSUMS" \
  "$TMP/"
(
  cd "$TMP"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c SHA256SUMS
  else
    shasum -a 256 -c SHA256SUMS
  fi
)

"$PYTHON" - "$PLUGIN_DIST/2faskw-idp-plugin.zip" "2faskw-idp-plugin-$VERSION" <<'PY'
import pathlib
import stat
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
expected_root = sys.argv[2]
required = {
    f"{expected_root}/bin/graphicalmatrix-sp.sh",
    f"{expected_root}/conf/graphicalmatrix/sp-management.properties.idpnew",
    f"{expected_root}/examples/logrotate/README.md",
    f"{expected_root}/examples/logrotate/graphicalmatrix-audit",
    f"{expected_root}/examples/logrotate/graphicalmatrix-sp-management-audit",
    f"{expected_root}/examples/logrotate/graphicalmatrix-access-audit",
    f"{expected_root}/examples/logrotate/graphicalmatrix-csv-import",
}
with zipfile.ZipFile(archive) as zf:
    names = set(zf.namelist())
    missing = sorted(required - names)
    if missing:
        raise SystemExit("SP management package entries are missing: " + ", ".join(missing))
    mode = (zf.getinfo(f"{expected_root}/bin/graphicalmatrix-sp.sh").external_attr >> 16) & 0xFFFF
    if not mode & stat.S_IXUSR:
        raise SystemExit("SP management CLI is not executable")
    config = zf.read(
        f"{expected_root}/conf/graphicalmatrix/sp-management.properties.idpnew"
    ).decode("utf-8")
    if "graphicalmatrix.sp.management.enabled = false" not in config:
        raise SystemExit("SP management CLI must be disabled by default")
    if "graphicalmatrix.sp.reload.baseUrl =" not in config:
        raise SystemExit("SP management reload base URL setting is missing")
    cli = zf.read(f"{expected_root}/bin/graphicalmatrix-sp.sh").decode("utf-8")
    if "IDP_BASE_URL" not in cli or "graphicalmatrix.sp.reload.baseUrl" not in cli:
        raise SystemExit("SP management CLI does not configure the reload base URL")
    required_runtime_jars = ("httpclient-", "httpcore-", "commons-logging-")
    bundled_jars = {
        pathlib.PurePosixPath(name).name
        for name in names
        if name.startswith(f"{expected_root}/webapp/WEB-INF/lib/") and name.endswith(".jar")
    }
    missing_jars = [
        prefix for prefix in required_runtime_jars
        if not any(name.startswith(prefix) for name in bundled_jars)
    ]
    if missing_jars:
        raise SystemExit(
            "SP management CLI runtime JARs are missing: " + ", ".join(missing_jars)
        )
    readme = zf.read(f"{expected_root}/README.md").decode("utf-8")
    if "https://github.com/y-asakawa/2faskw/blob/main/docs/INSTALL_NEW_SP.md" not in readme:
        raise SystemExit("SP management documentation URL is missing from package README")
PY

"$PYTHON" - "$ADMIN_FIXED" "$ADMIN_ROOT" <<'PY'
import hashlib
import pathlib
import stat
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
expected_root = sys.argv[2]
required = {
    f"{expected_root}/README.md",
    f"{expected_root}/LICENSE",
    f"{expected_root}/NOTICE",
    f"{expected_root}/THIRD-PARTY-NOTICES.md",
    f"{expected_root}/bin/graphicalmatrix-db.sh",
    f"{expected_root}/bin/graphicalmatrix-admin-install.sh",
    f"{expected_root}/bin/graphicalmatrix-csv-import-runner.sh",
    f"{expected_root}/examples/logrotate/README.md",
    f"{expected_root}/examples/logrotate/graphicalmatrix-csv-import",
    f"{expected_root}/package-metadata/PACKAGE-CONTENTS.txt",
    f"{expected_root}/package-metadata/PACKAGE-MANIFEST.sha256",
}
forbidden_parts = {".git", ".github", "credentials", "target", "__MACOSX"}
forbidden_suffixes = (".csv", ".log", ".swp", ".tmp", ".bak")

with zipfile.ZipFile(archive) as zf:
    infos = zf.infolist()
    names = [info.filename for info in infos]
    if len(names) != len(set(names)):
        raise SystemExit("duplicate ZIP entry")
    if len(names) > 512:
        raise SystemExit("too many ZIP entries")
    if sum(info.file_size for info in infos) > 256 * 1024 * 1024:
        raise SystemExit("uncompressed ZIP size exceeds 256 MiB")

    for info in infos:
        path = pathlib.PurePosixPath(info.filename)
        if path.is_absolute() or ".." in path.parts:
            raise SystemExit(f"unsafe ZIP entry: {info.filename}")
        if not path.parts or path.parts[0] != expected_root:
            raise SystemExit(f"unexpected ZIP root: {info.filename}")
        if len(path.parts) > 1 and path.parts[1] == "docs":
            raise SystemExit(f"detailed documentation is bundled: {info.filename}")
        if any(part in forbidden_parts for part in path.parts):
            raise SystemExit(f"forbidden ZIP path: {info.filename}")
        if path.name in {".DS_Store", "Thumbs.db"} or path.name.startswith("._"):
            raise SystemExit(f"local filesystem artifact: {info.filename}")
        if path.name.lower().endswith(forbidden_suffixes):
            raise SystemExit(f"forbidden file type: {info.filename}")
        mode = (info.external_attr >> 16) & 0xFFFF
        if stat.S_ISLNK(mode):
            raise SystemExit(f"symlink is not allowed: {info.filename}")

    missing = sorted(required - set(names))
    if missing:
        raise SystemExit("required entries are missing: " + ", ".join(missing))
    if any(pathlib.PurePosixPath(name).name == "graphicalmatrix-sp.sh" for name in names):
        raise SystemExit("SP management CLI must not be bundled in Admin Tools")

    installer = zf.read(
        f"{expected_root}/bin/graphicalmatrix-admin-install.sh"
    ).decode("utf-8")
    if '"$PACKAGE_DIR"/docs/' in installer or '"$PREFIX/docs"' in installer:
        raise SystemExit("Admin Tools installer must not require detailed documentation in the ZIP")

    readme = zf.read(f"{expected_root}/README.md").decode("utf-8")
    required_document_urls = {
        "https://github.com/y-asakawa/2faskw/blob/main/docs/ADMIN-TOOLS.md",
        "https://github.com/y-asakawa/2faskw/blob/main/docs/CONFIG-REFERENCE.md",
        "https://github.com/y-asakawa/2faskw/blob/main/docs/UPGRADE.md",
        "https://github.com/y-asakawa/2faskw/blob/main/docs/SECURITY.md",
    }
    missing_urls = sorted(url for url in required_document_urls if url not in readme)
    if missing_urls:
        raise SystemExit("README document URLs are missing: " + ", ".join(missing_urls))

    manifest_name = f"{expected_root}/package-metadata/PACKAGE-MANIFEST.sha256"
    manifest = zf.read(manifest_name).decode("utf-8")
    for line in manifest.splitlines():
        digest, relative = line.split(maxsplit=1)
        relative = relative.lstrip("*")
        name = f"{expected_root}/{relative}"
        if name not in names:
            raise SystemExit(f"manifest entry is missing: {name}")
        actual = hashlib.sha256(zf.read(name)).hexdigest()
        if actual != digest.lower():
            raise SystemExit(f"manifest digest mismatch: {name}")
PY

"$PYTHON" - "$DASHBOARD_FIXED" "$DASHBOARD_ROOT" <<'PY'
import hashlib
import pathlib
import stat
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
expected_root = sys.argv[2]
required = {
    f"{expected_root}/README.md",
    f"{expected_root}/LICENSE",
    f"{expected_root}/NOTICE",
    f"{expected_root}/THIRD-PARTY-NOTICES.md",
    f"{expected_root}/bin/2faskw-dashboard.sh",
    f"{expected_root}/bin/2faskw-dashboard-agent.sh",
    f"{expected_root}/bin/2faskw-dashboard-import.sh",
    f"{expected_root}/bin/2faskw-dashboard-install.sh",
    f"{expected_root}/conf/dashboard.properties.example",
    f"{expected_root}/conf/agent.properties.example",
    f"{expected_root}/conf/roles.properties.example",
    f"{expected_root}/conf/reason-mapping.properties",
    f"{expected_root}/examples/systemd/2faskw-dashboard.service",
    f"{expected_root}/examples/systemd/2faskw-dashboard-agent.service",
    f"{expected_root}/package-metadata/PACKAGE-CONTENTS.txt",
    f"{expected_root}/package-metadata/PACKAGE-MANIFEST.sha256",
}
forbidden_parts = {
    ".git", ".github", "credentials", "target", "docs", "__MACOSX",
}
forbidden_suffixes = (
    ".csv", ".log", ".swp", ".tmp", ".bak", ".mv.db", ".trace.db",
    ".p12", ".pfx", ".jks", ".key", ".crt",
)
private_markers = (
    b"-----BEGIN PRIVATE KEY-----",
    b"-----BEGIN RSA PRIVATE KEY-----",
    b"-----BEGIN EC PRIVATE KEY-----",
    b"-----BEGIN OPENSSH PRIVATE KEY-----",
    b"-----BEGIN PGP PRIVATE KEY BLOCK-----",
)

with zipfile.ZipFile(archive) as zf:
    infos = zf.infolist()
    names = [info.filename for info in infos]
    if len(names) != len(set(names)):
        raise SystemExit("duplicate Dashboard ZIP entry")
    if len(names) > 512:
        raise SystemExit("too many Dashboard ZIP entries")
    if sum(info.file_size for info in infos) > 256 * 1024 * 1024:
        raise SystemExit("Dashboard ZIP uncompressed size exceeds 256 MiB")

    for info in infos:
        path = pathlib.PurePosixPath(info.filename)
        if path.is_absolute() or ".." in path.parts:
            raise SystemExit(f"unsafe Dashboard ZIP entry: {info.filename}")
        if not path.parts or path.parts[0] != expected_root:
            raise SystemExit(f"unexpected Dashboard ZIP root: {info.filename}")
        if any(part in forbidden_parts for part in path.parts):
            raise SystemExit(f"forbidden Dashboard ZIP path: {info.filename}")
        if path.name in {".DS_Store", "Thumbs.db"} or path.name.startswith("._"):
            raise SystemExit(f"local filesystem artifact: {info.filename}")
        if path.name.lower().endswith(forbidden_suffixes):
            raise SystemExit(f"forbidden Dashboard file type: {info.filename}")
        mode = (info.external_attr >> 16) & 0xFFFF
        if stat.S_ISLNK(mode):
            raise SystemExit(f"symlink is not allowed: {info.filename}")
        data = zf.read(info)
        if any(marker in data for marker in private_markers):
            raise SystemExit(f"private key material found: {info.filename}")

    missing = sorted(required - set(names))
    if missing:
        raise SystemExit("required Dashboard entries are missing: " + ", ".join(missing))

    jars = [
        name for name in names
        if name.startswith(f"{expected_root}/lib/2faskw-dashboard-") and name.endswith(".jar")
    ]
    if len(jars) != 1:
        raise SystemExit("Dashboard application JAR is missing or duplicated")
    regex_jars = [
        name for name in names
        if name.startswith(f"{expected_root}/lib/re2j-") and name.endswith(".jar")
    ]
    if len(regex_jars) != 1:
        raise SystemExit("Dashboard RE2/J runtime JAR is missing or duplicated")

    notices = zf.read(f"{expected_root}/THIRD-PARTY-NOTICES.md").decode("utf-8")
    if "RE2/J | 1.8 | BSD-3-Clause" not in notices:
        raise SystemExit("Dashboard RE2/J notice is missing")

    for script in [
        f"{expected_root}/bin/2faskw-dashboard.sh",
        f"{expected_root}/bin/2faskw-dashboard-agent.sh",
        f"{expected_root}/bin/2faskw-dashboard-import.sh",
        f"{expected_root}/bin/2faskw-dashboard-install.sh",
    ]:
        mode = (zf.getinfo(script).external_attr >> 16) & 0xFFFF
        if not mode & stat.S_IXUSR:
            raise SystemExit(f"Dashboard script is not executable: {script}")

    readme = zf.read(f"{expected_root}/README.md").decode("utf-8")
    required_urls = {
        "https://github.com/y-asakawa/2faskw/blob/main/docs/DASHBOARD.md",
        "https://github.com/y-asakawa/2faskw/blob/main/docs/INSTALL.md",
        "https://github.com/y-asakawa/2faskw/blob/main/docs/build.md",
        "https://github.com/y-asakawa/2faskw/blob/main/docs/SECURITY.md",
    }
    missing_urls = sorted(url for url in required_urls if url not in readme)
    if missing_urls:
        raise SystemExit("Dashboard README document URLs are missing: " + ", ".join(missing_urls))

    agent_config = zf.read(
        f"{expected_root}/conf/agent.properties.example"
    ).decode("utf-8")
    if "agent.privacy.userMode = plain" not in agent_config:
        raise SystemExit("Dashboard Agent must default to plain user IDs")

    manifest_name = f"{expected_root}/package-metadata/PACKAGE-MANIFEST.sha256"
    manifest = zf.read(manifest_name).decode("utf-8")
    for line in manifest.splitlines():
        digest, relative = line.split(maxsplit=1)
        relative = relative.lstrip("*")
        name = f"{expected_root}/{relative}"
        if name not in names:
            raise SystemExit(f"Dashboard manifest entry is missing: {name}")
        actual = hashlib.sha256(zf.read(name)).hexdigest()
        if actual != digest.lower():
            raise SystemExit(f"Dashboard manifest digest mismatch: {name}")
PY

echo "package regression tests passed"
