# API Curl Tests

この文書は GraphicalMatrix 管理APIの実動作確認用curl例です。

管理APIは配布時は無効です。
有効化する場合は、HTTPS、Bearer token、接続元IP制限、Firewall/LB制限を確認してください。

## 前提

API設定:

```properties
graphicalmatrix.api.enabled = true
graphicalmatrix.api.allowedCidrs = 127.0.0.1/32,192.0.2.0/24
graphicalmatrix.api.bearerTokenFile = /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

API base URL例:

```bash
BASE_URL="http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1"
```

token読み込み:

```bash
TOKEN="$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-api.token)"
```

## 1. API無効確認

APIが無効な状態では、token有無に関係なく404になります。

```bash
curl -i "$BASE_URL/health"
```

期待値:

```text
HTTP/1.1 404
```

補助スクリプト:

```bash
./bin/graphicalmatrix-api-curl-test.sh \
  --base-url "$BASE_URL" \
  --expect-disabled
```

## 2. tokenなし

```bash
curl -i "$BASE_URL/health"
```

期待値:

```text
HTTP/1.1 401
```

レスポンス例:

```json
{"error":"UNAUTHORIZED"}
```

## 3. token誤り

```bash
curl -i \
  -H "Authorization: Bearer invalid-token" \
  "$BASE_URL/health"
```

期待値:

```text
HTTP/1.1 401
```

## 4. health

```bash
curl -sS \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/health"
```

期待値:

```json
{"status":"OK"}
```

## 5. graphicals

```bash
curl -sS \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/graphicals"
```

期待値:

```json
{
  "columns": 5,
  "rows": 5,
  "choice": 4,
  "order": 1,
  "allowDuplicates": false,
  "graphicals": ["img01", "img02"],
  "aliases": {
    "A": "img01"
  }
}
```

## 6. user作成 / 更新

```bash
curl -sS -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{
    "mfaMethod": "GraphicalMatrix",
    "forceSequenceChange": true,
    "initialSequence": ["A", "B", "C", "D"],
    "sequence": ["img03", "img07", "img11", "img14"],
    "status": "ACTIVE"
  }' \
  "$BASE_URL/users/api-user001"
```

期待値:

- HTTP 200
- `userId` が `api-user001`
- `mfaMethod` が `GraphicalMatrix`
- `forceSequenceChange` が `true`

## 7. user取得

```bash
curl -sS \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/users/api-user001"
```

期待値:

- HTTP 200
- `sequenceStorage`
- `sequenceRecoverable`
- `totpSeedSet`

## 8. MFA方式変更

TOTP:

```bash
curl -sS -X PATCH \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"mfaMethod":"TOTP"}' \
  "$BASE_URL/users/api-user001/method"
```

GraphicalMatrix:

```bash
curl -sS -X PATCH \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"mfaMethod":"GraphicalMatrix"}' \
  "$BASE_URL/users/api-user001/method"
```

WebAuthn:

```bash
curl -sS -X PATCH \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"mfaMethod":"WebAuthn"}' \
  "$BASE_URL/users/api-user001/method"
```

## 9. action API

RESET:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/users/api-user001/reset"
```

RESET後は `status=ACTIVE` のまま、`sequence` に `initialSequence` の内容が
現在の保存方式で再設定され、次回ログイン時にパスワード変更が強制されます。

unlock:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/users/api-user001/unlock"
```

disable:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/users/api-user001/disable"
```

enable:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/users/api-user001/enable"
```

TOTP reset:

```bash
curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/users/api-user001/totp-reset"
```

## 10. user削除

```bash
curl -sS -X DELETE \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/users/api-user001"
```

期待値:

```json
{"userId":"api-user001","deleted":true}
```

## 11. 補助スクリプト

読み取りテスト:

```bash
./bin/graphicalmatrix-api-curl-test.sh \
  --base-url "$BASE_URL" \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

書き込みテスト:

```bash
./bin/graphicalmatrix-api-curl-test.sh \
  --base-url "$BASE_URL" \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token \
  --write \
  --user api-user001
```

書き込みテストは以下を実行します。

- PUT user
- GET user
- PATCH method
- POST totp-reset
- POST reset
- POST unlock
- POST disable
- POST enable
- DELETE user

テストユーザーを残す場合:

```bash
./bin/graphicalmatrix-api-curl-test.sh \
  --base-url "$BASE_URL" \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token \
  --write \
  --keep-user \
  --user api-user001
```

## 12. 監査ログ確認

```bash
sudo tail /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

確認するイベント例:

- `API_DENIED`
- `API_USER_UPDATED`
- `API_METHOD_CHANGED`
- `API_USER_RESET`
- `API_UNLOCKED`
- `API_DISABLED`
- `API_ENABLED`
- `API_TOTP_RESET`
- `API_USER_DELETED`

## 13. 異常系期待値

| 条件 | 期待HTTP |
| --- | --- |
| API無効 | 404 |
| token未設定 | 503 |
| tokenなし | 401 |
| token誤り | 401 |
| 許可外IP | 403 |
| user未登録GET | 404 |
| 不正userId | 400 |
| 不正mfaMethod | 400 |
| 不正status | 400 |

## 14. 注意事項

- 書き込みテストはDBを変更します
- 本番では必ずテスト専用ユーザーを使ってください
- token値をログ、チケット、チャットへ残さないでください
- APIはインターネットへ直接公開しないでください
- allowedCidrsとFirewall/LBの両方で接続元を制限してください
