#!/usr/bin/env bash
# Offline launcher checks; all Java invocations use a controlled fake runtime.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
TMP="$(cd "$TMP" && pwd -P)"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

mkdir -p "$TMP/tools with spaces/lib" "$TMP/java home/bin"
install -m 0755 "$ROOT/sp-import-tools/graphicalmatrix-sp-import.sh" "$TMP/tools with spaces/launch.sh"
LAUNCHER="$TMP/tools with spaces/launch.sh"
touch "$TMP/tools with spaces/lib/2faskw-sp-import-tools-test.jar"
cat > "$TMP/java home/bin/java" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -version ]]; then
  if [[ "${MOCK_JAVA_FAIL:-0}" == 1 ]]; then
    echo 'mock Java runtime cannot start' >&2
    exit 1
  fi
  printf 'openjdk version "%s.0.1"\n' "${MOCK_JAVA_MAJOR:-21}" >&2
  exit 0
fi
printf '%s\n' "$@" > "$JAVA_ARGS"
exit "${MOCK_APP_RC:-0}"
SH
chmod 0755 "$TMP/java home/bin/java"
FAKE_JAVA="$TMP/java home/bin/java"

expect_rc() {
  local expected="$1" rc=0
  shift
  "$@" > "$TMP/output.txt" 2>&1 || rc=$?
  if [[ "$rc" != "$expected" ]]; then
    cat "$TMP/output.txt" >&2
    fail "expected rc=$expected, got rc=$rc"
  fi
}

expect_rc 2 env JAVA="$FAKE_JAVA" MOCK_JAVA_FAIL=1 "$LAUNCHER" --version
grep -Fq 'ERROR: unable to run Java:' "$TMP/output.txt" || fail 'missing Java startup diagnosis'
grep -Fq 'mock Java runtime cannot start' "$TMP/output.txt" || fail 'Java stderr was lost'
grep -Fq 'JAVA_HOME or JAVA' "$TMP/output.txt" || fail 'missing runtime configuration hint'

expect_rc 2 env JAVA="$FAKE_JAVA" MOCK_JAVA_MAJOR=17 "$LAUNCHER" --version
grep -Fq 'Java 21 or newer is required' "$TMP/output.txt" || fail 'old Java was accepted'

expect_rc 0 env -u JAVA JAVA_HOME="$TMP/java home" JAVA_ARGS="$TMP/args.txt" "$LAUNCHER" --version
grep -Fxq -- '-jar' "$TMP/args.txt" || fail 'launcher did not use jar entry point'
grep -Fxq -- '--tool-home' "$TMP/args.txt" || fail 'missing tool home'
grep -Fxq -- "$TMP/tools with spaces" "$TMP/args.txt" || fail 'path with spaces was split'
grep -Fxq -- '--version' "$TMP/args.txt" || fail 'application arguments were lost'

expect_rc 0 env JAVA="$FAKE_JAVA" JAVA_HOME="$TMP/missing" JAVA_ARGS="$TMP/args.txt" "$LAUNCHER" --version
expect_rc 2 env JAVA="$TMP/missing-java" "$LAUNCHER" --version
expect_rc 3 env JAVA="$FAKE_JAVA" JAVA_ARGS="$TMP/args.txt" MOCK_APP_RC=3 "$LAUNCHER" --analysis-only

touch "$TMP/tools with spaces/lib/2faskw-sp-import-tools-extra.jar"
expect_rc 2 env JAVA="$FAKE_JAVA" "$LAUNCHER" --version
grep -Fq 'exactly one SP Import Tools JAR' "$TMP/output.txt" || fail 'multiple jars were accepted'

echo 'SP Import launcher regression tests passed (7 cases)'
