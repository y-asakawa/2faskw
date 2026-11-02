#!/usr/bin/env bash
# Check display-only WebAuthn columns and safe fallback without accessing a deployed DB.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
mkdir -p "$TMP/home/conf/graphicalmatrix" "$TMP/bin"
cat > "$TMP/home/conf/graphicalmatrix/db.properties" <<'EOF'
graphicalmatrix.db.driver=org.postgresql.Driver
graphicalmatrix.db.url=jdbc:postgresql://127.0.0.1/not-a-real-database
graphicalmatrix.db.user=test-only
graphicalmatrix.db.autoInit=false
EOF
cat > "$TMP/bin/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
sql="${!#}"
printf '%s\n' "$sql" >> "$GM_DISPLAY_QUERIES"
if [[ "$sql" == *'LEFT JOIN LATERAL'* ]]; then
  if [[ "${GM_DISPLAY_FAIL:-0}" == 1 ]]; then
    echo 'ERROR: test-only-sensitive-json-value' >&2
    exit 1
  fi
  printf '%s\n' 'totp_seed_set | webauthn_status | webauthn_credential_count' 'YES | REGISTERED | 3'
else
  [[ "$sql" == *"'UNKNOWN' AS webauthn_status"* ]]
  [[ "$sql" == *"'UNKNOWN' AS webauthn_credential_count"* ]]
  printf '%s\n' 'totp_seed_set | webauthn_status | webauthn_credential_count' 'YES | UNKNOWN | UNKNOWN'
fi
EOF
cat > "$TMP/bin/id" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' jetty
EOF
cat > "$TMP/bin/java" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$3" == io.github.yasakawa.faskw.GraphicalMatrixH2Command && "$4" == sql ]]
sql="${!#}"
[[ "$sql" == *"'UNKNOWN' AS webauthn_status"* ]]
[[ "$sql" != *storagerecords* ]]
printf '%s\n' "$sql" >> "$GM_DISPLAY_QUERIES"
printf '%s\n' 'webauthn_status | webauthn_credential_count' 'UNKNOWN | UNKNOWN'
EOF
chmod 0755 "$TMP/bin/psql" "$TMP/bin/id" "$TMP/bin/java"
printf '%s\n' test-only > "$TMP/h2.jar"

display_cli() {
  GRAPHICALMATRIX_HOME="$TMP/home" GRAPHICALMATRIX_TIME_ZONE=UTC \
    PSQL_BIN="$TMP/bin/psql" SEQUENCE_TOOL_CP=test-only H2_JAR="$TMP/h2.jar" \
    GM_DISPLAY_QUERIES="$TMP/queries" PATH="$TMP/bin:$PATH" \
    bash "$ROOT/graphicalmatrix-db.sh" "$@"
}

assert_column_order() {
  local actual expected
  actual="$(awk '
    /AS locked_until,/ { print "locked_until" }
    /AS last_success,/ { print "last_success" }
    /AS created,/ { print "created" }
    /AS updated,/ { print "updated" }
    /^[[:space:]]*totp_status,/ { print "totp_status" }
    /AS totp_registered,/ { print "totp_registered" }
    /AS totp_seed_set/ { print "totp_seed_set" }
    /AS webauthn_status/ { print "webauthn_status" }
    /AS webauthn_credential_count/ { print "webauthn_credential_count" }
  ' "$TMP/queries")"
  expected=$'locked_until\nlast_success\ncreated\nupdated\ntotp_status\ntotp_registered\ntotp_seed_set\nwebauthn_status\nwebauthn_credential_count'
  [[ "$actual" == "$expected" || "$actual" == "$expected"$'\n'"$expected" ]] \
    || fail "display column order is incorrect"
}

display_cli list > "$TMP/out" 2> "$TMP/err"
assert_column_order
grep -q 'REGISTERED | 3' "$TMP/out" || fail "list has no registration summary"
[[ ! -s "$TMP/err" ]] || fail "successful summary produced a warning"
grep -q '^BEGIN READ ONLY;' "$TMP/queries" || fail "summary query is not read-only"
grep -q "s.context = 'net.shibboleth.idp.plugin.authn.webauthn'" "$TMP/queries" || fail "storage context is not restricted"
grep -q 's.id = e.user_id' "$TMP/queries" || fail "summary is not keyed by canonical username"
! grep -q 'userIdentity.*id' "$TMP/queries" || fail "summary treats a user handle as a username"
! grep -q 'SET mfa_method\|INSERT INTO\|UPDATE storagerecords\|DELETE FROM' "$TMP/queries" || fail "display mutates data"

: > "$TMP/queries"
display_cli show alice > "$TMP/out" 2> "$TMP/err"
assert_column_order
grep -q "WHERE user_id = 'alice'" "$TMP/queries" || fail "show lost its user filter"

: > "$TMP/queries"
GM_DISPLAY_FAIL=1 display_cli list > "$TMP/out" 2> "$TMP/err"
assert_column_order
grep -q 'UNKNOWN | UNKNOWN' "$TMP/out" || fail "unavailable storage was not UNKNOWN"
grep -q 'summary unavailable' "$TMP/err" || fail "fallback has no diagnostic"
! grep -q 'sensitive-json-value' "$TMP/err" || fail "DB diagnostics leaked credential data"
[[ "$(grep -c '^ORDER BY user_id;' "$TMP/queries")" == 2 ]] || fail "fallback did not run exactly one enrollment query"

: > "$TMP/queries"
GM_DISPLAY_FAIL=1 display_cli show alice > "$TMP/out" 2> "$TMP/err"
assert_column_order
[[ "$(grep -c "WHERE user_id = 'alice'" "$TMP/queries")" == 2 ]] || fail "fallback lost the show filter"

cat > "$TMP/home/conf/graphicalmatrix/db.properties" <<'EOF'
graphicalmatrix.db.driver=org.h2.Driver
graphicalmatrix.db.url=jdbc:h2:mem:display-test
graphicalmatrix.db.user=sa
graphicalmatrix.db.autoInit=false
EOF
: > "$TMP/queries"
display_cli show alice > "$TMP/out" 2> "$TMP/err"
assert_column_order
grep -q 'UNKNOWN | UNKNOWN' "$TMP/out" || fail "H2 did not display UNKNOWN"
[[ ! -s "$TMP/err" ]] || fail "H2 produced an unexpected warning"
! grep -q storagerecords "$TMP/queries" || fail "H2 tried the PostgreSQL lookup"
echo "WebAuthn list/show display regression: OK"
