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
  "$CHECKSUMS"; do
  [[ -s "$file" ]] || fail "required artifact is missing or empty: $file"
done

cmp -s "$ADMIN_VERSIONED" "$ADMIN_FIXED" \
  || fail "fixed-name and versioned Admin Tools ZIP files differ"

cp \
  "$PLUGIN_DIST/2faskw-idp-plugin.tar.gz" \
  "$PLUGIN_DIST/2faskw-idp-plugin.zip" \
  "$ADMIN_FIXED" \
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

echo "package regression tests passed"
