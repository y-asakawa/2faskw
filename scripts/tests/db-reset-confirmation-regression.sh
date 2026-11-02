#!/usr/bin/env bash
# Test RESET confirmation and argument handling without connecting to a real DB.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

mkdir -p "$TMP/home/conf/graphicalmatrix" "$TMP/bin"
cat > "$TMP/home/conf/graphicalmatrix/db.properties" <<'EOF'
graphicalmatrix.db.driver=org.postgresql.Driver
graphicalmatrix.db.url=jdbc:postgresql://127.0.0.1/not-a-real-database
graphicalmatrix.db.user=test-only
graphicalmatrix.db.autoInit=true
EOF

cat > "$TMP/bin/java" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$3" == io.github.yasakawa.faskw.GraphicalMatrixInitialSequenceTool ]]
[[ "$4" == reset && "$6" == alice ]]
printf 'RESET\n' >> "$GM_RESET_CALLS"
exit "${GM_RESET_FAIL:-0}"
EOF

cat > "$TMP/bin/psql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'SELECT\n' >> "$GM_RESET_CALLS"
printf 'reset-result\n'
EOF
chmod 0755 "$TMP/bin/java" "$TMP/bin/psql"

reset_cli() {
  GRAPHICALMATRIX_HOME="$TMP/home" \
    DB_PROPERTIES="$TMP/home/conf/graphicalmatrix/db.properties" \
    GRAPHICAL_PROPERTIES="$TMP/home/conf/graphicalmatrix/graphicalmatrix.properties" \
    GRAPHICALMATRIX_TIME_ZONE=UTC SEQUENCE_TOOL_CP=test-only-classpath \
    PSQL_BIN="$TMP/bin/psql" GM_RESET_CALLS="$TMP/calls" \
    PATH="$TMP/bin:$PATH" bash "$ROOT/graphicalmatrix-db.sh" "$@"
}

assert_no_calls() {
  [[ ! -s "$TMP/calls" ]] || fail "cancelled/rejected reset invoked a DB tool"
}

assert_reset_calls() {
  [[ "$(cat "$TMP/calls")" == $'RESET\nSELECT' ]] \
    || fail "reset did not invoke exactly one reset and one result query"
  : > "$TMP/calls"
}

for answer in NO no ''; do
  printf '%s\n' "$answer" | reset_cli alice RESET > "$TMP/out" 2> "$TMP/err"
  grep -q 'Reset cancelled: user=alice' "$TMP/err" || fail "NO/empty input did not cancel"
  assert_no_calls
done

if reset_cli alice RESET < /dev/null > "$TMP/out" 2> "$TMP/err"; then
  fail "EOF was accepted as reset confirmation"
fi
grep -q 'Use -y for unattended execution' "$TMP/err" || fail "missing EOF diagnostic"
assert_no_calls

printf 'Y\nNO\n' | reset_cli alice RESET > "$TMP/out" 2> "$TMP/err"
grep -q 'Please enter YES or NO' "$TMP/err" || fail "invalid response was not rejected"
assert_no_calls

printf 'YES\n' | reset_cli alice RESET > "$TMP/out" 2> "$TMP/err"
grep -Fq '[YES/NO]' "$TMP/err" || fail "reset confirmation prompt was not displayed"
assert_reset_calls

printf 'invalid\nyEs\n' | reset_cli alice reset > "$TMP/out" 2> "$TMP/err"
assert_reset_calls

printf 'NO\n' | reset_cli reset-user alice > "$TMP/out" 2> "$TMP/err"
assert_no_calls
printf 'yes\n' | reset_cli reset-user alice > "$TMP/out" 2> "$TMP/err"
assert_reset_calls

for option in -y --yes; do
  reset_cli alice RESET "$option" < /dev/null > "$TMP/out" 2> "$TMP/err"
  [[ ! -s "$TMP/err" ]] || fail "$option unexpectedly asked for confirmation"
  assert_reset_calls
  reset_cli reset-user alice "$option" < /dev/null > "$TMP/out" 2> "$TMP/err"
  [[ ! -s "$TMP/err" ]] || fail "reset-user $option unexpectedly asked for confirmation"
  assert_reset_calls
done

for option in --apply --typo -Y; do
  if reset_cli alice RESET -y "$option" < /dev/null > "$TMP/out" 2> "$TMP/err"; then
    fail "unknown option $option was ignored"
  fi
  grep -q 'unknown reset option' "$TMP/err" || fail "missing unknown-option diagnostic"
  assert_no_calls
done

if reset_cli reset-user < /dev/null > "$TMP/out" 2> "$TMP/err"; then
  fail "missing reset user was accepted"
fi
assert_no_calls

if GM_RESET_FAIL=7 reset_cli alice RESET -y < /dev/null > "$TMP/out" 2> "$TMP/err"; then
  fail "backend reset failure was ignored"
fi
[[ "$(cat "$TMP/calls")" == RESET ]] || fail "failed reset still queried success output"

echo 'DB RESET confirmation regression: OK'
