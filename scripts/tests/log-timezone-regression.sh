#!/usr/bin/env bash
# Check real CSV runner output under OS timezone settings without touching a database.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/home/bin" "$TMP/home/conf/graphicalmatrix" "$TMP/bin"
cat > "$TMP/home/bin/graphicalmatrix-db.sh" <<'EOF'
#!/usr/bin/env bash
printf 'unexpected DB call\n' >> "$DB_CALLS"
exit 99
EOF
chmod 0755 "$TMP/home/bin/graphicalmatrix-db.sh"
printf '%s\n' 'graphicalmatrix.admin.enabled=false' > "$TMP/home/conf/graphicalmatrix/admin.properties"

check_zone() {
  local zone="$1" suffix="$2" line timestamp rc=0
  rm -f "$TMP/home/logs/csv-import.log"
  TZ="$zone" DB_CALLS="$TMP/db-calls" GRAPHICALMATRIX_HOME="$TMP/home" \
    bash "$ROOT/scripts/graphicalmatrix-csv-import-runner.sh" > "$TMP/out" 2> "$TMP/err" || rc=$?
  [[ "$rc" == 1 ]] || { echo 'FAIL: disabled runner did not stop before database access' >&2; exit 1; }
  line="$(head -n 1 "$TMP/home/logs/csv-import.log")"
  timestamp="${line%% *}"
  [[ "$timestamp" =~ ^ts=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})$ ]] \
    || { echo 'FAIL: invalid ISO-8601 timestamp' >&2; exit 1; }
  [[ "$timestamp" == *"$suffix" && "$line" == *' event=CSV_IMPORT_RUNNER_FAIL '* ]] \
    || { echo "FAIL: CSV log does not follow timezone $zone" >&2; exit 1; }
}

check_zone UTC Z
check_zone Asia/Tokyo +09:00
check_zone Asia/Kathmandu +05:45
check_zone Etc/GMT+4 -04:00
[[ ! -e "$TMP/db-calls" ]] || { echo 'FAIL: timezone test invoked the DB tool' >&2; exit 1; }

cat > "$TMP/bin/date" <<'EOF'
#!/usr/bin/env bash
echo not-a-timestamp
EOF
chmod 0755 "$TMP/bin/date"
rm -f "$TMP/home/logs/csv-import.log"
rc=0
PATH="$TMP/bin:$PATH" DB_CALLS="$TMP/db-calls" GRAPHICALMATRIX_HOME="$TMP/home" \
  bash "$ROOT/scripts/graphicalmatrix-csv-import-runner.sh" > "$TMP/out" 2> "$TMP/err" || rc=$?
[[ "$rc" != 0 && ! -s "$TMP/home/logs/csv-import.log" ]] \
  || { echo 'FAIL: invalid date output became an empty or misleading log timestamp' >&2; exit 1; }
grep -q 'cannot format CSV log timestamp' "$TMP/err"
echo 'Log timezone regression: PASS'
