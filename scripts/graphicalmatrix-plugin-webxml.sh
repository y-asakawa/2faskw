#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
ACTION="install"
APPLY=0
TS="$(date +%Y%m%d%H%M%S)"

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-plugin-webxml.sh [--idp-home DIR] [--install|--remove] [--apply]

Adds or removes GraphicalMatrix servlet mappings from edit-webapp/WEB-INF/web.xml.

Default mode is dry-run. Pass --apply to change files.

This script:
  - creates a backup before changing web.xml
  - adds a marked GraphicalMatrix servlet block before <session-config>
  - adds a marked GraphicalMatrix API security block after <session-config>
  - removes only the marked GraphicalMatrix blocks
  - does not rebuild the IdP war
  - does not restart Jetty
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --idp-home)
      IDP_HOME="${2:-}"
      shift 2
      ;;
    --install)
      ACTION="install"
      shift
      ;;
    --remove)
      ACTION="remove"
      shift
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
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

WEB_XML="$IDP_HOME/edit-webapp/WEB-INF/web.xml"
BEGIN_MARK="<!-- BEGIN GraphicalMatrix MFA Plugin servlet mappings -->"
END_MARK="<!-- END GraphicalMatrix MFA Plugin servlet mappings -->"
BEGIN_SECURITY_MARK="<!-- BEGIN GraphicalMatrix MFA Plugin security constraints -->"
END_SECURITY_MARK="<!-- END GraphicalMatrix MFA Plugin security constraints -->"

require_file() {
  local file="$1"
  [[ -f "$file" ]] || { echo "ERROR: missing file: $file" >&2; exit 1; }
}

run_sudo() {
  printf '+'
  if [[ "${USE_SUDO:-0}" -eq 1 ]]; then
    printf ' sudo'
  fi
  printf ' %q' "$@"
  printf '\n'
  if [[ "$APPLY" -eq 1 ]]; then
    if [[ "${USE_SUDO:-0}" -eq 1 ]]; then
      sudo "$@"
    else
      "$@"
    fi
  fi
}

make_servlet_block() {
  cat <<'EOF'
    <!-- BEGIN GraphicalMatrix MFA Plugin servlet mappings -->
    <!-- GraphicalMatrix External authentication and management API endpoints. -->
    <listener>
        <listener-class>io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixDataSourceListener</listener-class>
    </listener>

    <servlet>
        <servlet-name>GraphicalMatrixStart</servlet-name>
        <servlet-class>io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixStartServlet</servlet-class>
    </servlet>
    <servlet-mapping>
        <servlet-name>GraphicalMatrixStart</servlet-name>
        <url-pattern>/graphicalmatrix/start</url-pattern>
    </servlet-mapping>

    <servlet>
        <servlet-name>GraphicalMatrixVerify</servlet-name>
        <servlet-class>io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixVerifyServlet</servlet-class>
    </servlet>
    <servlet-mapping>
        <servlet-name>GraphicalMatrixVerify</servlet-name>
        <url-pattern>/graphicalmatrix/verify</url-pattern>
    </servlet-mapping>

    <servlet>
        <servlet-name>GraphicalMatrixChange</servlet-name>
        <servlet-class>io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixChangeServlet</servlet-class>
    </servlet>
    <servlet-mapping>
        <servlet-name>GraphicalMatrixChange</servlet-name>
        <url-pattern>/graphicalmatrix/change</url-pattern>
    </servlet-mapping>

    <servlet>
        <servlet-name>GraphicalMatrixGraphical</servlet-name>
        <servlet-class>io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixGraphicalServlet</servlet-class>
    </servlet>
    <servlet-mapping>
        <servlet-name>GraphicalMatrixGraphical</servlet-name>
        <url-pattern>/graphicalmatrix/graphical</url-pattern>
    </servlet-mapping>

    <servlet>
        <servlet-name>GraphicalMatrixAsset</servlet-name>
        <servlet-class>io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixAssetServlet</servlet-class>
    </servlet>
    <servlet-mapping>
        <servlet-name>GraphicalMatrixAsset</servlet-name>
        <url-pattern>/graphicalmatrix/assets/*</url-pattern>
    </servlet-mapping>

    <servlet>
        <servlet-name>GraphicalMatrixAdminApi</servlet-name>
        <servlet-class>io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixAdminApiServlet</servlet-class>
    </servlet>
    <servlet-mapping>
        <servlet-name>GraphicalMatrixAdminApi</servlet-name>
        <url-pattern>/graphicalmatrix-admin/api/v1/*</url-pattern>
    </servlet-mapping>
    <!-- END GraphicalMatrix MFA Plugin servlet mappings -->

EOF
}

make_security_block() {
  cat <<'EOF'
    <!-- BEGIN GraphicalMatrix MFA Plugin security constraints -->
    <!-- Allow any HTTP methods to the GraphicalMatrix management API. -->
    <security-constraint>
        <web-resource-collection>
            <web-resource-name>GraphicalMatrix Administrative API</web-resource-name>
            <url-pattern>/graphicalmatrix-admin/api/*</url-pattern>
        </web-resource-collection>
        <!-- no auth-constraint tag here -->
    </security-constraint>
    <!-- END GraphicalMatrix MFA Plugin security constraints -->

EOF
}

python_install() {
  local source="$1"
  local output="$2"
  local servlet_block_file="$3"
  local security_block_file="$4"
  python3 - "$source" "$output" "$servlet_block_file" "$security_block_file" \
    "$BEGIN_MARK" "$END_MARK" "$BEGIN_SECURITY_MARK" "$END_SECURITY_MARK" <<'PY'
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

source, output, servlet_block_file, security_block_file, begin, end, sec_begin, sec_end = sys.argv[1:9]
text = pathlib.Path(source).read_text(encoding="utf-8")
changed = False

def harden_session_cookies(value):
    session_pattern = re.compile(r"<session-config\b[^>]*>.*?</session-config>", re.DOTALL)
    match = session_pattern.search(value)
    if not match:
        block = """    <session-config>
        <cookie-config>
            <http-only>true</http-only>
            <secure>true</secure>
            <attribute>
                <attribute-name>SameSite</attribute-name>
                <attribute-value>Lax</attribute-value>
            </attribute>
        </cookie-config>
        <tracking-mode>COOKIE</tracking-mode>
    </session-config>

"""
        anchor = value.find(sec_begin)
        if anchor >= 0:
            return value[:anchor] + block + value[anchor:], True
        closing = re.search(r"(?m)^([ \t]*)</web-app>\s*$", value)
        if not closing:
            raise SystemExit("ERROR: </web-app> not found")
        return value[:closing.start()] + block + value[closing.start():], True

    session = match.group(0)
    original_session = session
    cookie_pattern = re.compile(r"<cookie-config\b[^>]*>.*?</cookie-config>", re.DOTALL)
    cookie_match = cookie_pattern.search(session)
    if not cookie_match:
        cookie = """        <cookie-config>
            <http-only>true</http-only>
            <secure>true</secure>
            <attribute>
                <attribute-name>SameSite</attribute-name>
                <attribute-value>Lax</attribute-value>
            </attribute>
        </cookie-config>
"""
        insert = cookie
        if "<tracking-mode>" not in session:
            insert += "        <tracking-mode>COOKIE</tracking-mode>\n"
        session = session.replace("</session-config>", insert + "    </session-config>", 1)
    else:
        cookie = cookie_match.group(0)
        active_cookie = re.sub(r"<!--.*?-->", "", cookie, flags=re.DOTALL)
        if re.search(r"<http-only>\s*(true|false)\s*</http-only>", active_cookie, flags=re.IGNORECASE):
            cookie = re.sub(r"<http-only>\s*(true|false)\s*</http-only>",
                "<http-only>true</http-only>", cookie, count=1, flags=re.IGNORECASE)
        else:
            cookie = cookie.replace("<cookie-config>",
                "<cookie-config>\n            <http-only>true</http-only>", 1)
        active_cookie = re.sub(r"<!--.*?-->", "", cookie, flags=re.DOTALL)
        if re.search(r"<secure>\s*(true|false)\s*</secure>", active_cookie, flags=re.IGNORECASE):
            cookie = re.sub(r"<secure>\s*(true|false)\s*</secure>",
                "<secure>true</secure>", cookie, count=1, flags=re.IGNORECASE)
        else:
            cookie = cookie.replace("</cookie-config>",
                "            <secure>true</secure>\n        </cookie-config>", 1)
        active_cookie = re.sub(r"<!--.*?-->", "", cookie, flags=re.DOTALL)
        if re.search(r"<attribute-name>\s*SameSite\s*</attribute-name>", active_cookie, flags=re.IGNORECASE):
            cookie = re.sub(
                r"(<attribute>\s*<attribute-name>\s*SameSite\s*</attribute-name>\s*<attribute-value>).*?(</attribute-value>\s*</attribute>)",
                r"\1Lax\2", cookie, count=1, flags=re.DOTALL | re.IGNORECASE)
        else:
            cookie = cookie.replace("</cookie-config>", """            <attribute>
                <attribute-name>SameSite</attribute-name>
                <attribute-value>Lax</attribute-value>
            </attribute>
        </cookie-config>""", 1)
        session = session[:cookie_match.start()] + cookie + session[cookie_match.end():]

    if "<tracking-mode>" not in session:
        session = session.replace("</session-config>",
            "        <tracking-mode>COOKIE</tracking-mode>\n    </session-config>", 1)
    return value[:match.start()] + session + value[match.end():], session != original_session

if begin not in text and end not in text and (
    "io.github.yasakawa.faskw.graphicalmatrix.GraphicalMatrixStartServlet" in text
    or "/graphicalmatrix/start" in text
    or "/graphicalmatrix-admin/api/v1/*" in text
):
    text, hardened = harden_session_cookies(text)
    pathlib.Path(output).write_text(text, encoding="utf-8")
    print("status=existing_manual_entries_detected_cookie_hardened" if hardened
        else "status=existing_manual_entries_detected")
    raise SystemExit(0)

if begin not in text and end not in text:
    block = pathlib.Path(servlet_block_file).read_text(encoding="utf-8")
    anchor = re.search(r"(?m)^([ \t]*)<session-config>", text)
    if anchor:
        pos = anchor.start()
    else:
        closing = re.search(r"(?m)^([ \t]*)</web-app>\s*$", text)
        if not closing:
            raise SystemExit("ERROR: </web-app> not found")
        pos = closing.start()
    text = text[:pos] + block + text[pos:]
    changed = True

if sec_begin not in text and sec_end not in text:
    block = pathlib.Path(security_block_file).read_text(encoding="utf-8")
    anchor = re.search(r"(?m)^([ \t]*)<!--\s*Uncomment to use container managed authentication", text)
    if anchor:
        pos = anchor.start()
    else:
        closing = re.search(r"(?m)^([ \t]*)</web-app>\s*$", text)
        if not closing:
            raise SystemExit("ERROR: </web-app> not found")
        pos = closing.start()
    text = text[:pos] + block + text[pos:]
    changed = True

text, hardened = harden_session_cookies(text)
changed = changed or hardened

try:
    ET.fromstring(text)
except ET.ParseError as ex:
    raise SystemExit(f"ERROR: resulting web.xml is not well-formed: {ex}")

pathlib.Path(output).write_text(text, encoding="utf-8")
print("status=installed" if changed else "status=already_installed")
PY
}

python_remove() {
  local source="$1"
  local output="$2"
  python3 - "$source" "$output" "$BEGIN_MARK" "$END_MARK" \
    "$BEGIN_SECURITY_MARK" "$END_SECURITY_MARK" <<'PY'
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

source, output, begin, end, sec_begin, sec_end = sys.argv[1:7]
text = pathlib.Path(source).read_text(encoding="utf-8")
pattern = re.compile(r"[ \t]*" + re.escape(begin) + r".*?" + re.escape(end) + r"[ \t]*\n?", re.DOTALL)
new_text, count = pattern.subn("", text, count=1)
sec_pattern = re.compile(r"[ \t]*" + re.escape(sec_begin) + r".*?" + re.escape(sec_end) + r"[ \t]*\n?", re.DOTALL)
new_text, sec_count = sec_pattern.subn("", new_text, count=1)
if count == 0 and sec_count == 0:
    pathlib.Path(output).write_text(text, encoding="utf-8")
    print("status=not_installed")
    raise SystemExit(0)

try:
    ET.fromstring(new_text)
except ET.ParseError as ex:
    raise SystemExit(f"ERROR: resulting web.xml is not well-formed: {ex}")

pathlib.Path(output).write_text(new_text, encoding="utf-8")
print("status=removed")
PY
}

echo "mode=$([[ "$APPLY" -eq 1 ]] && echo apply || echo dry-run)"
echo "action=$ACTION"
echo "web_xml=$WEB_XML"
require_file "$WEB_XML"

if [[ "$APPLY" -eq 1 ]]; then
  if [[ "$(id -u)" -eq 0 || ( -w "$WEB_XML" && -w "$(dirname "$WEB_XML")" ) ]]; then
    USE_SUDO=0
  else
    USE_SUDO=1
  fi
else
  USE_SUDO=0
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
CURRENT="$TMP_DIR/web.xml.current"
NEW="$TMP_DIR/web.xml.new"
SERVLET_BLOCK="$TMP_DIR/graphicalmatrix-webxml-servlet-block.xml"
SECURITY_BLOCK="$TMP_DIR/graphicalmatrix-webxml-security-block.xml"
cp "$WEB_XML" "$CURRENT"
make_servlet_block > "$SERVLET_BLOCK"
make_security_block > "$SECURITY_BLOCK"

if [[ "$ACTION" == "install" ]]; then
  python_install "$CURRENT" "$NEW" "$SERVLET_BLOCK" "$SECURITY_BLOCK"
elif [[ "$ACTION" == "remove" ]]; then
  python_remove "$CURRENT" "$NEW"
else
  echo "ERROR: unsupported action: $ACTION" >&2
  exit 2
fi

if cmp -s "$CURRENT" "$NEW"; then
  echo "web.xml unchanged"
else
  echo "web.xml would change"
  if command -v diff >/dev/null 2>&1; then
    diff -u "$CURRENT" "$NEW" || true
  fi
fi

if [[ "$APPLY" -eq 1 && ! -f "$WEB_XML.bak.$TS" ]]; then
  run_sudo cp "$WEB_XML" "$WEB_XML.bak.$TS"
fi
if [[ "$APPLY" -eq 1 ]]; then
  run_sudo install -m 0644 "$NEW" "$WEB_XML"
fi

echo
echo "Next steps:"
echo "  1. Run: $IDP_HOME/bin/build.sh"
echo "  2. Restart Jetty."
echo "  3. Verify /idp/graphicalmatrix/change."
echo
if [[ "$APPLY" -eq 0 ]]; then
  echo "Dry-run only. Re-run with --apply to change web.xml."
fi
