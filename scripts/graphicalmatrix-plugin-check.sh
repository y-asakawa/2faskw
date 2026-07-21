#!/usr/bin/env bash
set -euo pipefail

IDP_HOME="/opt/shibboleth-idp"
PACKAGE_DIR=""
CHECK_PACKAGE=1
CHECK_IDP=1
CHECK_CONFIG=0
STRICT=0

package_failures=0
package_warnings=0
idp_failures=0
idp_warnings=0
config_failures=0
config_warnings=0
current_scope="package"

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-plugin-check.sh [--idp-home DIR] [--package-dir DIR] [--package-only|--idp-only|--config-only] [--strict]

Checks the 2FAS-KW plugin package and/or the target Shibboleth IdP layout.

Options:
  --package-only   Check only the extracted plugin package. Useful on build hosts or CI.
  --idp-only       Check only the target IdP layout. Useful after package validation.
  --config-only    Validate graphicalmatrix.properties and referenced files without DB access.
  --strict         Treat warnings as a failed check.

Default mode checks both the package and the IdP layout.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --idp-home)
      IDP_HOME="${2:-}"
      shift 2
      ;;
    --package-dir)
      PACKAGE_DIR="${2:-}"
      shift 2
      ;;
    --package-only)
      CHECK_PACKAGE=1
      CHECK_IDP=0
      CHECK_CONFIG=0
      shift
      ;;
    --idp-only)
      CHECK_PACKAGE=0
      CHECK_IDP=1
      CHECK_CONFIG=0
      shift
      ;;
    --config-only)
      CHECK_PACKAGE=0
      CHECK_IDP=0
      CHECK_CONFIG=1
      shift
      ;;
    --strict)
      STRICT=1
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

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "$PACKAGE_DIR" ]]; then
  PACKAGE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  PACKAGE_DIR="$(cd "$PACKAGE_DIR" && pwd)"
fi

ok() {
  printf 'OK: [%s] %s\n' "$current_scope" "$1"
}

warn() {
  case "$current_scope" in
    package) package_warnings=$((package_warnings + 1)) ;;
    idp) idp_warnings=$((idp_warnings + 1)) ;;
    config) config_warnings=$((config_warnings + 1)) ;;
  esac
  printf 'WARN: [%s] %s\n' "$current_scope" "$1"
}

fail() {
  case "$current_scope" in
    package) package_failures=$((package_failures + 1)) ;;
    idp) idp_failures=$((idp_failures + 1)) ;;
    config) config_failures=$((config_failures + 1)) ;;
  esac
  printf 'FAIL: [%s] %s\n' "$current_scope" "$1"
}

need_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    ok "file exists: $file"
  else
    fail "file missing: $file"
  fi
}

need_dir() {
  local dir="$1"
  if [[ -d "$dir" ]]; then
    ok "directory exists: $dir"
  else
    fail "directory missing: $dir"
  fi
}

check_idp_plugin_jar_duplicates() {
  local jars=()
  local directory jar_file
  for directory in \
    "$IDP_HOME/dist/plugin-webapp/WEB-INF/lib" \
    "$IDP_HOME/edit-webapp/WEB-INF/lib"; do
    [[ -d "$directory" ]] || continue
    shopt -s nullglob
    for jar_file in "$directory"/2faskw-idp-plugin-*.jar; do
      jars+=("$jar_file")
    done
    shopt -u nullglob
  done

  if [[ "${#jars[@]}" -gt 1 ]]; then
    fail "multiple 2FAS-KW plugin jars would be merged into the IdP WAR: ${jars[*]}"
  elif [[ "${#jars[@]}" -eq 1 ]]; then
    ok "single 2FAS-KW plugin jar in IdP overlays: ${jars[0]}"
  else
    warn "2FAS-KW plugin jar not found in IdP overlays"
  fi
}

first_match() {
  local pattern="$1"
  local matches=()
  shopt -s nullglob
  matches=($pattern)
  shopt -u nullglob
  if [[ "${#matches[@]}" -gt 0 ]]; then
    printf '%s\n' "${matches[0]}"
  fi
}

properties_value() {
  local file="$1"
  local key="$2"
  awk -F= -v key="$key" '
    $0 !~ /^[[:space:]]*#/ {
      left=$1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", left)
      if (left == key) {
        value=substr($0, index($0, "=") + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        print value
        exit
      }
    }
  ' "$file"
}

openapi_version() {
  local file="$1"
  awk '
    /^[[:space:]]*version:[[:space:]]*/ {
      value=$0
      sub(/^[[:space:]]*version:[[:space:]]*/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$file"
}

manifest_value() {
  local jar="$1"
  local key="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | awk -F': ' -v key="$key" '$1 == key { gsub(/\r$/, "", $2); print $2; exit }'
  elif command -v jar >/dev/null 2>&1; then
    local tmpdir
    tmpdir="$(mktemp -d)"
    (cd "$tmpdir" && jar xf "$jar" META-INF/MANIFEST.MF) >/dev/null 2>&1 || true
    if [[ -f "$tmpdir/META-INF/MANIFEST.MF" ]]; then
      awk -F': ' -v key="$key" '$1 == key { gsub(/\r$/, "", $2); print $2; exit }' "$tmpdir/META-INF/MANIFEST.MF"
    fi
    rm -rf "$tmpdir"
  fi
}

jar_has_entry() {
  local jar_file="$1"
  local entry="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$jar_file" 2>/dev/null | grep -Fx "$entry" >/dev/null
  elif command -v jar >/dev/null 2>&1; then
    jar tf "$jar_file" 2>/dev/null | grep -Fx "$entry" >/dev/null
  else
    return 127
  fi
}

verify_sha256_manifest() {
  local manifest="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$PACKAGE_DIR" && sha256sum -c "$manifest" >/dev/null)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$PACKAGE_DIR" && shasum -a 256 -c "$manifest" >/dev/null)
  else
    return 127
  fi
}

check_equals() {
  local label="$1"
  local actual="$2"
  local expected="$3"
  if [[ -z "$actual" ]]; then
    fail "$label missing; expected $expected"
  elif [[ "$actual" == "$expected" ]]; then
    ok "$label matches package version: $actual"
  else
    fail "$label mismatch: actual=$actual expected=$expected"
  fi
}

print_summary_and_exit() {
  local failures=$((package_failures + idp_failures + config_failures))
  local warnings=$((package_warnings + idp_warnings + config_warnings))
  local result="OK"

  if [[ "$failures" -gt 0 ]]; then
    if [[ "$config_failures" -gt 0 && "$package_failures" -eq 0 && "$idp_failures" -eq 0 ]]; then
      result="CONFIG_CHECK_FAILED"
    elif [[ "$package_failures" -gt 0 && "$idp_failures" -gt 0 ]]; then
      result="CHECK_FAILED"
    elif [[ "$package_failures" -gt 0 ]]; then
      result="PACKAGE_CHECK_FAILED"
    else
      result="IDP_CHECK_FAILED"
    fi
  elif [[ "$STRICT" -eq 1 && "$warnings" -gt 0 ]]; then
    result="STRICT_WARNING_FAILED"
  elif [[ "$warnings" -gt 0 ]]; then
    result="OK_WITH_WARNINGS"
  fi

  echo
  echo "summary: package_failures=$package_failures package_warnings=$package_warnings idp_failures=$idp_failures idp_warnings=$idp_warnings config_failures=$config_failures config_warnings=$config_warnings strict=$STRICT"
  echo "result: $result"

  case "$result" in
    OK)
      if [[ "$CHECK_CONFIG" -eq 1 ]]; then
        echo "next: configuration is valid; begin a new authentication session to verify runtime behavior."
      elif [[ "$CHECK_IDP" -eq 1 ]]; then
        echo "next: run graphicalmatrix-plugin-config.sh --idp-home $IDP_HOME for dry-run."
      else
        echo "next: run this check on the IdP server, or re-run with --idp-home DIR."
      fi
      ;;
    OK_WITH_WARNINGS)
      if [[ "$CHECK_CONFIG" -eq 1 ]]; then
        echo "note: configuration is usable, but review storage and optional-feature warnings before production use."
        echo "next: resolve relevant warnings, or re-run with --strict to enforce a warning-free result."
      else
        echo "note: optional TOTP/WebAuthn warnings are acceptable if those MFA methods are not used."
        echo "next: review warnings, then continue with graphicalmatrix-plugin-config.sh dry-run."
      fi
      ;;
    STRICT_WARNING_FAILED)
      echo "action: strict mode treats warnings as failures; resolve warnings or re-run without --strict."
      ;;
    PACKAGE_CHECK_FAILED)
      echo "action: rebuild the package with ./scripts/build-plugin-package.sh and verify the ZIP was fully extracted."
      ;;
    IDP_CHECK_FAILED)
      echo "action: run this command on the target IdP server, or specify the correct --idp-home DIR."
      ;;
    CONFIG_CHECK_FAILED)
      echo "action: fix graphicalmatrix.properties, conf/authn/authn.properties, or referenced files before allowing user authentication."
      ;;
    CHECK_FAILED)
      echo "action: fix package failures first, then re-run against the target IdP."
      ;;
  esac

  if [[ "$failures" -gt 0 || ( "$STRICT" -eq 1 && "$warnings" -gt 0 ) ]]; then
    exit 1
  fi
}

check_package() {
  current_scope="package"
  echo
  echo "== Package checks =="

  need_dir "$PACKAGE_DIR"

  local package_base package_version package_archive_base plugin_jar zxing_core_jar hikari_jar bootstrap_props metadata_props metadata_base_name openapi_yaml manifest_version bootstrap_version openapi_ver
  package_base="$(basename "$PACKAGE_DIR")"
  if [[ "$package_base" == 2faskw-idp-plugin-* && "$package_base" != "2faskw-idp-plugin-" ]]; then
    package_version="${package_base#2faskw-idp-plugin-}"
    package_archive_base="${package_base%-$package_version}"
    ok "package version derived from directory: $package_version"
  else
    package_version=""
    package_archive_base=""
    fail "package directory name must be 2faskw-idp-plugin-VERSION: $package_base"
  fi

  plugin_jar="$(first_match "$PACKAGE_DIR/webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar")"
  if [[ -n "$plugin_jar" ]]; then
    ok "2FAS-KW plugin jar exists: $plugin_jar"
  else
    fail "2FAS-KW plugin jar missing: $PACKAGE_DIR/webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar"
  fi

  zxing_core_jar="$(first_match "$PACKAGE_DIR/webapp/WEB-INF/lib/core-*.jar")"
  if [[ -n "$zxing_core_jar" ]]; then
    ok "ZXing core jar exists: $zxing_core_jar"
  else
    fail "ZXing core jar missing: $PACKAGE_DIR/webapp/WEB-INF/lib/core-*.jar"
  fi
  hikari_jar="$(first_match "$PACKAGE_DIR/webapp/WEB-INF/lib/HikariCP-*.jar")"
  if [[ -n "$hikari_jar" ]]; then
    ok "HikariCP jar exists: $hikari_jar"
  else
    fail "HikariCP jar missing: $PACKAGE_DIR/webapp/WEB-INF/lib/HikariCP-*.jar"
  fi
  if [[ -n "$(first_match "$PACKAGE_DIR/webapp/WEB-INF/lib/postgresql-*.jar")" ]]; then
    ok "PostgreSQL JDBC driver exists"
  else
    fail "PostgreSQL JDBC driver missing: $PACKAGE_DIR/webapp/WEB-INF/lib/postgresql-*.jar"
  fi

  bootstrap_props="$PACKAGE_DIR/bootstrap/plugin.properties"
  metadata_props="$PACKAGE_DIR/plugin-metadata/graphicalmatrix-plugin.properties"
  openapi_yaml="$PACKAGE_DIR/docs/openapi.yaml"

  need_file "$bootstrap_props"
  need_file "$metadata_props"
  need_file "$PACKAGE_DIR/plugin-metadata/README.md"
  need_file "$PACKAGE_DIR/plugin-metadata/PACKAGE-CONTENTS.txt"
  need_file "$PACKAGE_DIR/plugin-metadata/PACKAGE-MANIFEST.sha256"
  need_file "$PACKAGE_DIR/conf/graphicalmatrix/graphicalmatrix.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/graphicalmatrix/db.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/graphicalmatrix/ldap.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/graphicalmatrix/webauthn-ldap.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/graphicalmatrix/api.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/graphicalmatrix/mfa-policy.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/graphicalmatrix/postgresql-schema.sql"
  need_file "$PACKAGE_DIR/conf/authn/webauthn.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/authn/webauthn-registration.properties.idpnew"
  need_file "$PACKAGE_DIR/conf/authn/webauthn-metadata.properties.idpnew"
  need_file "$PACKAGE_DIR/bin/graphicalmatrix-db.sh"
  need_file "$PACKAGE_DIR/bin/graphicalmatrix-db-migration.sh"
  need_file "$PACKAGE_DIR/bin/graphicalmatrix-api-token.sh"
  need_file "$PACKAGE_DIR/bin/graphicalmatrix-security-upgrade.sh"
  need_file "$PACKAGE_DIR/bin/graphicalmatrix-api-curl-test.sh"
  need_file "$PACKAGE_DIR/examples/webauthn-ldap-storage-config.xml"
  need_file "$PACKAGE_DIR/examples/logrotate/graphicalmatrix-audit"
  need_file "$PACKAGE_DIR/docs/README.md"
  need_file "$PACKAGE_DIR/docs/INSTALL.md"
  need_file "$PACKAGE_DIR/docs/INSTALL_Manual_Installation.md"
  need_file "$PACKAGE_DIR/docs/INSTALL_LDAP.md"
  need_file "$PACKAGE_DIR/docs/INSTALL_Passchange_IdP.md"
  need_file "$PACKAGE_DIR/docs/INSTALL_Passchange_SP.md"
  need_file "$PACKAGE_DIR/docs/SECURITY.md"
  need_file "$PACKAGE_DIR/docs/SECURITY-CHECKLIST.md"
  need_file "$PACKAGE_DIR/docs/API-TOKEN-ROTATION.md"
  need_file "$PACKAGE_DIR/docs/API-CURL-TESTS.md"
  need_file "$PACKAGE_DIR/docs/MFA-POLICY-ORDER-DESIGN.md"
  need_file "$PACKAGE_DIR/docs/FAQ.md"
  need_file "$PACKAGE_DIR/docs/UPGRADE.md"
  need_file "$PACKAGE_DIR/docs/CSV-EXPORT.md"
  need_file "$PACKAGE_DIR/docs/DB-MIGRATION.md"
  need_file "$PACKAGE_DIR/docs/SEQUENCE-STORAGE-MIGRATION.md"
  need_file "$PACKAGE_DIR/docs/LOGROTATE.md"
  need_file "$openapi_yaml"

  if [[ -n "$plugin_jar" ]]; then
    local flow_entry
    for flow_entry in \
      "META-INF/net.shibboleth.idp/postconfig.xml" \
      "META-INF/net/shibboleth/idp/flows/2faskw/self-service/self-service-flow.xml" \
      "META-INF/net/shibboleth/idp/flows/2faskw/self-service/self-service-beans.xml"; do
      if jar_has_entry "$plugin_jar" "$flow_entry"; then
        ok "self-service JAR resource exists: $flow_entry"
      else
        case "$?" in
          127) warn "unzip/jar not found; skipped self-service JAR resource checks"; break ;;
          *) fail "self-service JAR resource missing: $flow_entry" ;;
        esac
      fi
    done
  fi

  if [[ -f "$PACKAGE_DIR/conf/graphicalmatrix/api.properties.idpnew" ]]; then
    if grep -Eq '^[[:space:]]*graphicalmatrix[.]api[.]enabled[[:space:]]*=[[:space:]]*false[[:space:]]*$' \
        "$PACKAGE_DIR/conf/graphicalmatrix/api.properties.idpnew"; then
      ok "API template is disabled by default"
    else
      fail "API template must have graphicalmatrix.api.enabled = false"
    fi
  fi

  if [[ -f "$PACKAGE_DIR/conf/graphicalmatrix/mfa-policy.properties.idpnew" ]]; then
    local policy_order
    policy_order="$(properties_value \
      "$PACKAGE_DIR/conf/graphicalmatrix/mfa-policy.properties.idpnew" \
      "graphicalmatrix.mfa.policyOrder")"
    if [[ "$policy_order" == "forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default" ]]; then
      ok "MFA policy template has the safe default rule order"
    else
      fail "MFA policy template has an unexpected rule order: $policy_order"
    fi
    if grep -Eq \
        '^[[:space:]]*graphicalmatrix[.]mfa[.]forceSPs[[:space:]]*=' \
        "$PACKAGE_DIR/conf/graphicalmatrix/mfa-policy.properties.idpnew"; then
      ok "MFA policy template contains forceSPs"
    else
      fail "MFA policy template is missing graphicalmatrix.mfa.forceSPs"
    fi
  fi

  if [[ -n "$package_version" && -f "$metadata_props" ]]; then
    if grep -q "io.github.yasakawa.faskw.authn.graphicalmatrix.versions[[:space:]]*=[[:space:]]*$package_version" "$metadata_props"; then
      ok "plugin metadata version matches package version"
    else
      fail "plugin metadata version must include package version: $package_version"
    fi
    metadata_base_name="$(properties_value "$metadata_props" "io.github.yasakawa.faskw.authn.graphicalmatrix.baseName.$package_version")"
    if [[ "$metadata_base_name" == "$package_archive_base" ]]; then
      ok "plugin metadata baseName matches archive base name: $package_archive_base"
    else
      fail "plugin metadata baseName mismatch: expected $package_archive_base, got ${metadata_base_name:-<missing>}"
    fi
  fi

  if [[ -n "$package_version" && -f "$bootstrap_props" ]]; then
    bootstrap_version="$(properties_value "$bootstrap_props" "plugin.version")"
    check_equals "bootstrap plugin.version" "$bootstrap_version" "$package_version"
  fi

  if [[ -n "$package_version" && -f "$openapi_yaml" ]]; then
    openapi_ver="$(openapi_version "$openapi_yaml")"
    check_equals "OpenAPI info.version" "$openapi_ver" "$package_version"
  fi

  if [[ -n "$package_version" && -n "$plugin_jar" ]]; then
    manifest_version="$(manifest_value "$plugin_jar" "Implementation-Version")"
    if [[ -z "$manifest_version" ]]; then
      if command -v unzip >/dev/null 2>&1 || command -v jar >/dev/null 2>&1; then
        fail "JAR manifest Implementation-Version missing; expected $package_version"
      else
        warn "unzip/jar not found; skipped JAR manifest version check"
      fi
    else
      check_equals "JAR Implementation-Version" "$manifest_version" "$package_version"
    fi
  fi

  if [[ -f "$PACKAGE_DIR/plugin-metadata/PACKAGE-MANIFEST.sha256" ]]; then
    if verify_sha256_manifest "plugin-metadata/PACKAGE-MANIFEST.sha256"; then
      ok "package sha256 manifest verifies"
    else
      case "$?" in
        127) warn "sha256sum/shasum not found; skipped package manifest verification" ;;
        *) fail "package sha256 manifest verification failed" ;;
      esac
    fi
  fi
}

check_idp() {
  current_scope="idp"
  echo
  echo "== IdP checks =="

  need_dir "$IDP_HOME"
  if [[ ! -d "$IDP_HOME" ]]; then
    warn "skipping deeper IdP checks because idp_home is not a directory"
    return
  fi

  need_file "$IDP_HOME/bin/build.sh"
  need_file "$IDP_HOME/edit-webapp/WEB-INF/web.xml"
  check_idp_plugin_jar_duplicates

  if [[ -x "$IDP_HOME/bin/module.sh" ]]; then
    local module_output
    module_output="$("$IDP_HOME/bin/module.sh" -l 2>/dev/null || true)"
    if grep -q 'idp.authn.Password' <<<"$module_output"; then
      ok "module listed: idp.authn.Password"
    else
      warn "module not listed or module.sh output unavailable: idp.authn.Password"
    fi
    if grep -q 'idp.authn.MFA' <<<"$module_output"; then
      ok "module listed: idp.authn.MFA"
    else
      warn "module not listed or module.sh output unavailable: idp.authn.MFA"
    fi
  else
    warn "module.sh not executable: $IDP_HOME/bin/module.sh"
  fi

  if [[ -d "$IDP_HOME/credentials/net.shibboleth.idp.plugin.authn.totp" ]]; then
    ok "optional TOTP plugin truststore directory exists"
  else
    warn "optional TOTP plugin not detected; acceptable if TOTP MFA is not used"
  fi

  if [[ -d "$IDP_HOME/credentials/net.shibboleth.idp.plugin.authn.webauthn" ]]; then
    ok "optional WebAuthn plugin truststore directory exists"
  else
    warn "optional WebAuthn plugin not detected; acceptable if WebAuthn MFA is not used"
  fi
}

check_config() {
  current_scope="config"
  echo
  echo "== Configuration checks =="

  local plugin_jar java_bin output line tool_status failures_before
  local graphical_properties self_service authn_properties authn_flows external_forced
  check_idp_plugin_jar_duplicates
  plugin_jar="$(first_match "$PACKAGE_DIR/webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar")"
  if [[ -z "$plugin_jar" ]]; then
    plugin_jar="$(first_match "$IDP_HOME/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar")"
  fi
  if [[ -z "$plugin_jar" ]]; then
    fail "2FAS-KW plugin jar not found in IdP or package"
    return
  fi
  ok "configuration checker jar found: $plugin_jar"

  java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  if ! command -v "$java_bin" >/dev/null 2>&1; then
    fail "java command not found: $java_bin"
    return
  fi
  if ! "$java_bin" -version >/dev/null 2>&1; then
    fail "java command is not usable: $java_bin"
    return
  fi

  failures_before="$config_failures"
  set +e
  output="$("$java_bin" -cp "$plugin_jar" \
    io.github.yasakawa.faskw.GraphicalMatrixConfigCheckTool \
    --idp-home "$IDP_HOME" 2>&1)"
  tool_status=$?
  set -e

  while IFS= read -r line; do
    case "$line" in
      "OK: "*) ok "${line#OK: }" ;;
      "WARN: "*) warn "${line#WARN: }" ;;
      "FAIL: "*) fail "${line#FAIL: }" ;;
      CONFIG_SUMMARY*) ;;
      "") ;;
      *) fail "configuration checker output: $line" ;;
    esac
  done <<< "$output"

  if [[ "$tool_status" -ne 0 && "$config_failures" -eq "$failures_before" ]]; then
    fail "configuration checker exited with status $tool_status"
  fi

  graphical_properties="$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties"
  if [[ -f "$graphical_properties" ]]; then
    self_service="$(properties_value "$graphical_properties" "graphicalmatrix.selfservice.enabled")"
  else
    self_service=""
  fi
  self_service="$(printf '%s' "$self_service" | tr '[:upper:]' '[:lower:]')"
  if [[ "$self_service" == "true" || "$self_service" == "1" \
      || "$self_service" == "yes" || "$self_service" == "on" ]]; then
    authn_properties="$IDP_HOME/conf/authn/authn.properties"
    if [[ ! -r "$authn_properties" ]]; then
      fail "self-service requires readable authn.properties: $authn_properties"
      return
    fi
    authn_flows="$(properties_value "$authn_properties" "idp.authn.flows")"
    external_forced="$(properties_value "$authn_properties" \
      "idp.authn.External.forcedAuthenticationSupported")"
    external_forced="$(printf '%s' "$external_forced" | tr '[:upper:]' '[:lower:]')"
    if tr ',' ' ' <<<"$authn_flows" | grep -Eq '(^|[[:space:]])MFA($|[[:space:]])'; then
      ok "self-service authentication flow enabled: idp.authn.flows=$authn_flows"
    else
      fail "self-service requires MFA in idp.authn.flows: actual=$authn_flows"
    fi
    if [[ "$external_forced" == "true" ]]; then
      ok "GraphicalMatrix External flow supports forced authentication"
    else
      fail "self-service requires idp.authn.External.forcedAuthenticationSupported=true"
    fi
  fi
}

echo "package_dir=$PACKAGE_DIR"
echo "idp_home=$IDP_HOME"
echo "mode=$([[ "$CHECK_CONFIG" -eq 1 ]] && echo config-only || { [[ "$CHECK_PACKAGE" -eq 1 && "$CHECK_IDP" -eq 1 ]] && echo all || { [[ "$CHECK_PACKAGE" -eq 1 ]] && echo package-only || echo idp-only; }; })"
echo "strict=$STRICT"

if [[ "$CHECK_PACKAGE" -eq 1 ]]; then
  check_package
fi
if [[ "$CHECK_IDP" -eq 1 ]]; then
  check_idp
fi
if [[ "$CHECK_CONFIG" -eq 1 ]]; then
  check_config
fi

print_summary_and_exit
