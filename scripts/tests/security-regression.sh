#!/usr/bin/env bash
#
# Security regression test suite for 2FAS-KW / GraphicalMatrix.
#
# This script verifies that previously fixed security issues do not regress,
# including token-file symlink replacement, CSV import time-of-check/time-of-use
# handling, rejection of symlinked provisioning CSV files, and management reset
# behavior for protected sequence storage.
#
# Intended use:
#   - Run during development, dependency updates, and release validation.
#   - Safe to keep in Git because it uses temporary directories and dummy data.
#
# Not intended use:
#   - This is not an installer, production operation tool, or monitoring script.
#   - Do not add real DB credentials, tokens, certificates, or server-specific
#     configuration to this file.
#
# Example:
#   bash scripts/tests/security-regression.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

test_token_symlink_replacement() {
  local dir="$TMP/token"
  local sentinel="$TMP/sentinel"
  local token="$dir/api.token"
  mkdir -p "$dir"
  printf '%s\n' "do-not-change" > "$sentinel"
  ln -s "$sentinel" "$token"

  "$ROOT/scripts/graphicalmatrix-api-token.sh" \
    --idp-home "$TMP/idp" \
    --token-file "$token" \
    --no-backup \
    --no-chown \
    rotate --apply >/dev/null

  [[ ! -L "$token" && -f "$token" ]] || fail "token path was not replaced with a regular file"
  [[ "$(cat "$sentinel")" == "do-not-change" ]] || fail "token rotation followed the attacker symlink"
}

test_csv_immutable_snapshot() {
  local base="$TMP/admin"
  local source="$base/incoming/users.csv"
  local state="$TMP/state"
  local test_bin="$TMP/test-bin"
  mkdir -p "$base/bin" "$base/conf/graphicalmatrix" "$base/incoming" "$state" "$test_bin"
  cat > "$test_bin/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod 0755 "$test_bin/flock"

  cat > "$base/conf/graphicalmatrix/admin.properties" <<EOF
graphicalmatrix.admin.enabled=true
graphicalmatrix.admin.provisioning.enabled=true
graphicalmatrix.admin.productionMode=true
graphicalmatrix.admin.rejectPlaintextSequence=true
graphicalmatrix.admin.csv.autoApply=true
graphicalmatrix.admin.csv.autoApplyActions=A
graphicalmatrix.admin.csv.autoApplyDeprovision=false
EOF
  cat > "$base/conf/graphicalmatrix/graphicalmatrix.properties" <<EOF
graphicalmatrix.sequence.storage=hash
EOF
  cat > "$source" <<EOF
action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence
A,alice,GraphicalMatrix,on,,g1
EOF
  cat > "$base/bin/graphicalmatrix-db.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
file="$2"
if command -v sha256sum >/dev/null 2>&1; then
  digest="$(sha256sum "$file" | awk '{print $1}')"
else
  digest="$(shasum -a 256 "$file" | awk '{print $1}')"
fi
if [[ "${4:-}" == "--apply" ]]; then
  [[ "$digest" == "$(cat "$TEST_STATE/dryrun.sha256")" ]]
else
  printf '%s' "$digest" > "$TEST_STATE/dryrun.sha256"
  printf '%s\n' "A,mallory,GraphicalMatrix,on,,evil" > "$ATTACK_SOURCE"
fi
EOF
  chmod 0755 "$base/bin/graphicalmatrix-db.sh"

  GRAPHICALMATRIX_HOME="$base" \
  TEST_STATE="$state" \
  ATTACK_SOURCE="$source" \
  PATH="$test_bin:$PATH" \
    bash "$ROOT/scripts/graphicalmatrix-csv-import-runner.sh"

  local processed
  processed="$(find "$base/processed" -type f -name '*.csv' -print -quit)"
  [[ -n "$processed" ]] || fail "validated CSV snapshot was not processed"
  [[ "$(sha256_file "$processed")" == "$(cat "$state/dryrun.sha256")" ]] \
    || fail "processed CSV differs from validated snapshot"
}

test_csv_rejects_symlink_source() {
  local base="$TMP/admin-symlink"
  local target="$TMP/attacker-controlled.csv"
  local source="$base/incoming/users.csv"
  local test_bin="$TMP/test-bin-symlink"
  mkdir -p "$base/bin" "$base/conf/graphicalmatrix" "$base/incoming" "$test_bin"
  cat > "$test_bin/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod 0755 "$test_bin/flock"

  cat > "$base/conf/graphicalmatrix/admin.properties" <<EOF
graphicalmatrix.admin.enabled=true
graphicalmatrix.admin.provisioning.enabled=true
graphicalmatrix.admin.productionMode=true
graphicalmatrix.admin.rejectPlaintextSequence=true
EOF
  cat > "$base/conf/graphicalmatrix/graphicalmatrix.properties" <<EOF
graphicalmatrix.sequence.storage=hash
EOF
  printf '%s\n' "action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence" \
    "A,mallory,GraphicalMatrix,on,,evil" > "$target"
  ln -s "$target" "$source"
  cat > "$base/bin/graphicalmatrix-db.sh" <<'EOF'
#!/usr/bin/env bash
exit 99
EOF
  chmod 0755 "$base/bin/graphicalmatrix-db.sh"

  GRAPHICALMATRIX_HOME="$base" PATH="$test_bin:$PATH" \
    bash "$ROOT/scripts/graphicalmatrix-csv-import-runner.sh"

  [[ -L "$source" ]] || fail "untrusted CSV symlink was consumed"
  [[ -z "$(find "$base/processing" "$base/processed" -type f -name '*.csv' -print -quit)" ]] \
    || fail "untrusted CSV symlink produced a snapshot"
}

test_csv_preview_displays_initial_and_encoded_sequence() {
  local home="$TMP/csv-preview-idp"
  local csv="$TMP/csv-preview.csv"
  mkdir -p "$home/conf/graphicalmatrix"
  cat > "$home/conf/graphicalmatrix/graphicalmatrix.properties" <<EOF
graphicalmatrix.graphicals=img01-04
graphicalmatrix.choice=4
graphicalmatrix.sequence.storage=keyword
graphicalmatrix.sequence.keyword=test-only-keyword
EOF
  cat > "$home/conf/graphicalmatrix/db.properties" <<EOF
graphicalmatrix.db.driver=org.h2.Driver
graphicalmatrix.db.url=jdbc:h2:mem:csv-preview;MODE=PostgreSQL;DATABASE_TO_UPPER=false
graphicalmatrix.db.user=sa
graphicalmatrix.db.password=
EOF
  cat > "$csv" <<EOF
action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence
A,alice,GraphicalMatrix,on,"img01,img02,img03,img04","img01,img02,img03,img04"
EOF

  local output
  output="$(GRAPHICALMATRIX_HOME="$home" SEQUENCE_TOOL_CP="$ROOT/target/classes" \
    PATH="/opt/homebrew/opt/openjdk/bin:$PATH" \
    bash "$ROOT/graphicalmatrix-db.sh" csv "$csv" --provisioning)"
  grep -q 'initial_sequence=img01,img02,img03,img04' <<< "$output" \
    || fail "CSV preview did not display the initial sequence: $output"
  grep -q 'sequence=kw1:' <<< "$output" \
    || fail "CSV preview did not display the protected stored sequence: $output"
  ! grep -q ' sequence=img01,img02,img03,img04' <<< "$output" \
    || fail "CSV preview displayed the plaintext current sequence: $output"
}

test_management_reset_restores_plaintext_initial_factor() {
  local home="$TMP/idp"
  local test_bin="$TMP/db-test-bin"
  local h2_jar="$HOME/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar"
  local db_url="jdbc:h2:file:$home/credentials/graphicalmatrix;MODE=PostgreSQL;DATABASE_TO_UPPER=false"
  local real_id
  real_id="$(command -v id)"
  [[ -f "$h2_jar" ]] || fail "H2 test jar is missing: $h2_jar"
  mkdir -p "$home/conf/graphicalmatrix" "$home/credentials" "$test_bin"
  cat > "$test_bin/id" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == "-un" ]]; then
  echo jetty
else
  exec "$real_id" "\$@"
fi
EOF
  chmod 0755 "$test_bin/id"
  cat > "$home/conf/graphicalmatrix/db.properties" <<EOF
graphicalmatrix.db.driver=org.h2.Driver
graphicalmatrix.db.url=$db_url
graphicalmatrix.db.user=sa
graphicalmatrix.db.password=
graphicalmatrix.db.autoInit=true
EOF
  cat > "$home/conf/graphicalmatrix/graphicalmatrix.properties" <<EOF
graphicalmatrix.graphicals=img01-25
graphicalmatrix.not_graphicals=img99
graphicalmatrix.aliases=A:img01,B:img02,C:img03,D:img04
graphicalmatrix.choice=4
graphicalmatrix.sequence.storage=keyword
graphicalmatrix.sequence.keyword=test-only-keyword
EOF

  PATH="/opt/homebrew/opt/openjdk/bin:$test_bin:$PATH"
  local cp="$ROOT/target/classes:$h2_jar"
  GRAPHICALMATRIX_HOME="$home" H2_JAR="$h2_jar" SEQUENCE_TOOL_CP="$cp" PATH="$PATH" \
    bash "$ROOT/graphicalmatrix-db.sh" add alice img01,img02,img03,img04 >/dev/null
  GRAPHICALMATRIX_HOME="$home" H2_JAR="$h2_jar" SEQUENCE_TOOL_CP="$cp" PATH="$PATH" \
    bash "$ROOT/graphicalmatrix-db.sh" set-sequence alice img05,img06,img07,img08 >/dev/null
  GRAPHICALMATRIX_HOME="$home" H2_JAR="$h2_jar" SEQUENCE_TOOL_CP="$cp" PATH="$PATH" \
    bash "$ROOT/graphicalmatrix-db.sh" alice RESET >/dev/null

  local security_status
  security_status="$(GRAPHICALMATRIX_HOME="$home" H2_JAR="$h2_jar" SEQUENCE_TOOL_CP="$cp" PATH="$PATH" \
    bash "$ROOT/graphicalmatrix-db.sh" security-status)"
  grep -Fxq "security.initial_sequence_empty_rows=0" <<< "$security_status" \
    || fail "security-status detected missing initial sequence"
  grep -Fxq "security.initial_sequence_incompatible_rows=0" <<< "$security_status" \
    || fail "security-status detected incompatible initial sequence"
  grep -Fxq "security.incompatible_sequence_rows=0" <<< "$security_status" \
    || fail "security-status detected incompatible sequence storage"
  grep -Fxq "security.active_empty_sequence_rows=0" <<< "$security_status" \
    || fail "security-status detected an active empty sequence"

  local result list
  result="$(java -cp "$h2_jar" org.h2.tools.Shell \
    -url "$db_url" -user sa -password "" \
    -sql "SELECT status, sequence LIKE 'kw1:%', initial_sequence, state_version FROM graphicalmatrix_enrollment WHERE user_id='alice'")"
  grep -Eq 'ACTIVE.*TRUE.*A,B,C,D.*[1-9][0-9]*' <<< "$result" \
    || fail "management reset did not restore plaintext initial factor as protected ACTIVE sequence: $result"
  list="$(GRAPHICALMATRIX_HOME="$home" H2_JAR="$h2_jar" SEQUENCE_TOOL_CP="$cp" PATH="$PATH" \
    bash "$ROOT/graphicalmatrix-db.sh" show alice)"
  grep -Eq 'alice[[:space:]]*\| GraphicalMatrix[[:space:]]*\| 1[[:space:]]*\| A,B,C,D[[:space:]]*\| kw1:' <<< "$list" \
    || fail "management output does not display the initial password and raw protected sequence: $list"
}

test_token_symlink_replacement
test_csv_immutable_snapshot
test_csv_rejects_symlink_source
test_csv_preview_displays_initial_and_encoded_sequence
test_management_reset_restores_plaintext_initial_factor
echo "security regression tests: PASS"
