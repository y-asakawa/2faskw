#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1"
TOKEN=""
TOKEN_FILE="/opt/shibboleth-idp/credentials/graphicalmatrix-api.token"
TEST_USER="graphicalmatrix-api-test-$(date +%Y%m%d%H%M%S)"
WRITE=0
KEEP_USER=0
EXPECT_DISABLED=0

usage() {
  cat <<'EOF'
Usage:
  graphicalmatrix-api-curl-test.sh [options]

Options:
  --base-url URL       API base URL.
                       Default: http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1
  --token TOKEN        Bearer token value.
  --token-file FILE    Bearer token file. Default: /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
  --user USER          Test user for --write. Default: graphicalmatrix-api-test-TIMESTAMP
  --write              Run write tests: PUT, PATCH, POST actions, DELETE.
  --keep-user          Do not delete the test user after --write tests.
  --expect-disabled    Expect API disabled behavior. Verifies GET /health returns 404.

Read-only mode checks:
  - API disabled returns 404 when --expect-disabled is set
  - missing token returns 401
  - bad token returns 401
  - valid token GET /health returns 200
  - valid token GET /graphicals returns 200

Write mode creates/updates/deletes only the configured test user.
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "$1 not found"
}

read_token() {
  if [[ -n "$TOKEN" ]]; then
    return
  fi
  [[ -r "$TOKEN_FILE" ]] || die "token file is not readable: $TOKEN_FILE"
  TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
  [[ -n "$TOKEN" ]] || die "token file is empty: $TOKEN_FILE"
}

curl_status() {
  local method="$1"
  local path="$2"
  local token_mode="${3:-valid}"
  local body="${4:-}"
  local url="${BASE_URL%/}${path}"
  local tmp code
  tmp="$(mktemp)"
  local args=(-sS -o "$tmp" -w '%{http_code}' -X "$method" "$url")
  case "$token_mode" in
    none)
      ;;
    bad)
      args+=(-H 'Authorization: Bearer invalid-token-for-test')
      ;;
    valid)
      args+=(-H "Authorization: Bearer $TOKEN")
      ;;
    *)
      die "unknown token mode: $token_mode"
      ;;
  esac
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  code="$(curl "${args[@]}" || true)"
  printf "%s" "$code"
  rm -f "$tmp"
}

expect_code() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "OK: $label http=$actual"
  else
    echo "FAIL: $label expected=$expected actual=$actual" >&2
    return 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)
      BASE_URL="${2:-}"
      shift 2
      ;;
    --token)
      TOKEN="${2:-}"
      shift 2
      ;;
    --token-file)
      TOKEN_FILE="${2:-}"
      shift 2
      ;;
    --user)
      TEST_USER="${2:-}"
      shift 2
      ;;
    --write)
      WRITE=1
      shift
      ;;
    --keep-user)
      KEEP_USER=1
      shift
      ;;
    --expect-disabled)
      EXPECT_DISABLED=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

need_cmd curl
[[ -n "$BASE_URL" ]] || die "--base-url must not be empty"
[[ "$TEST_USER" =~ ^[A-Za-z0-9._@-]+$ ]] || die "invalid test user: $TEST_USER"

echo "base_url=$BASE_URL"
if [[ "$EXPECT_DISABLED" == "1" ]]; then
  code="$(curl_status GET /health none)"
  expect_code "disabled GET /health without token" 404 "$code"
  echo "result=OK"
  exit 0
fi

read_token
echo "token_source=$([[ -n "${TOKEN:-}" ]] && echo configured)"
echo "write_tests=$([[ "$WRITE" == "1" ]] && echo yes || echo no)"
echo "test_user=$TEST_USER"

code="$(curl_status GET /health none)"
expect_code "GET /health without token" 401 "$code"

code="$(curl_status GET /health bad)"
expect_code "GET /health with bad token" 401 "$code"

code="$(curl_status GET /health valid)"
expect_code "GET /health with valid token" 200 "$code"

code="$(curl_status GET /graphicals valid)"
expect_code "GET /graphicals with valid token" 200 "$code"

if [[ "$WRITE" != "1" ]]; then
  echo "result=OK"
  exit 0
fi

create_body='{"mfaMethod":"GraphicalMatrix","forceSequenceChange":true,"initialSequence":["A","B","C","D"],"sequence":["img03","img07","img11","img14"],"status":"ACTIVE"}'
code="$(curl_status PUT "/users/$TEST_USER" valid "$create_body")"
expect_code "PUT /users/$TEST_USER" 200 "$code"

code="$(curl_status GET "/users/$TEST_USER" valid)"
expect_code "GET /users/$TEST_USER" 200 "$code"

code="$(curl_status PATCH "/users/$TEST_USER/method" valid '{"mfaMethod":"TOTP"}')"
expect_code "PATCH /users/$TEST_USER/method TOTP" 200 "$code"

for action in totp-reset reset unlock disable enable; do
  code="$(curl_status POST "/users/$TEST_USER/$action" valid)"
  expect_code "POST /users/$TEST_USER/$action" 200 "$code"
done

if [[ "$KEEP_USER" == "1" ]]; then
  echo "kept_user=$TEST_USER"
else
  code="$(curl_status DELETE "/users/$TEST_USER" valid)"
  expect_code "DELETE /users/$TEST_USER" 200 "$code"
fi

echo "result=OK"
