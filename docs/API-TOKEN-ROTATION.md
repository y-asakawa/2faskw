# API Token Rotation

この文書は GraphicalMatrix 管理APIの Bearer token をローテーションする手順です。

GraphicalMatrix管理APIは、`api.properties` の `graphicalmatrix.api.bearerTokenFile` に指定されたtokenファイルを各リクエスト時に読み込みます。
そのため、tokenファイルを安全に置き換えれば、通常はJetty再起動なしでローテーションできます。

## 前提

- 管理APIを有効化している
- `graphicalmatrix.api.bearerTokenFile` を利用している
- API利用元は `graphicalmatrix.api.allowedCidrs` とFirewall/LBで制限されている
- tokenファイルはIdP実行ユーザーだけが読める
- ローテーション後、API利用クライアント側のtokenも更新できる

配布時のAPI設定は無効です。

```properties
graphicalmatrix.api.enabled = false
```

## 対象ファイル

既定:

```text
/opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

設定:

```properties
graphicalmatrix.api.bearerTokenFile = /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

## 補助スクリプト

配布物には以下を含めます。

```text
bin/graphicalmatrix-api-token.sh
```

このスクリプトはdry-runを既定とし、`--apply` を付けた場合だけtokenを更新します。

## 1. 現在状態の確認

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh status
```

確認する項目:

- `token_file`
- `token_file_exists`
- `token_readable`
- `token_length`
- ファイル所有者
- ファイル権限

## 2. dry-run

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate
```

`dry_run=yes` と表示され、実際にはtokenを変更しません。

確認する項目:

- `would_create_directory`
- `would_backup`
- `would_write_new_token`
- `would_chmod=0400`
- `would_chown=jetty:jetty`

## 3. tokenローテーション

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate --apply --print-token
```

出力される `new_token=` の値を、API利用クライアントへ安全な経路で反映してください。

注意:

- `--print-token` は必要な場合だけ使ってください
- terminal history、作業ログ、チャット、チケットへtokenを残さないでください
- `--print-token` を使わない場合、token値は画面に表示されません

## 4. API利用クライアント更新

API利用クライアント側のBearer tokenを新しい値に更新します。

例:

```bash
curl -H "Authorization: Bearer <NEW_TOKEN>" \
  https://idp.example.ac.jp/idp/graphicalmatrix-admin/api/v1/health
```

期待値:

```json
{"status":"OK"}
```

## 5. 旧tokenが無効であることを確認

旧tokenでAPIを呼び出し、401になることを確認します。

```bash
curl -i -H "Authorization: Bearer <OLD_TOKEN>" \
  https://idp.example.ac.jp/idp/graphicalmatrix-admin/api/v1/health
```

期待値:

```text
HTTP/1.1 401
```

## 6. バックアップ

既存tokenがある場合、既定では以下の形式でバックアップします。

```text
/opt/shibboleth-idp/credentials/graphicalmatrix-api.token.bak.YYYYMMDD-HHMMSS
```

バックアップを作りたくない場合:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate --apply --no-backup
```

## 7. rollback

新tokenでAPI利用クライアントが更新できない場合、バックアップから戻します。

```bash
sudo cp /opt/shibboleth-idp/credentials/graphicalmatrix-api.token.bak.YYYYMMDD-HHMMSS \
  /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
sudo chown jetty:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
sudo chmod 0400 /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

その後、旧tokenで `GET /health` が成功することを確認します。

## 8. 緊急無効化

API token漏えい時は、tokenローテーションだけでなくAPI自体を無効化します。

```properties
graphicalmatrix.api.enabled = false
```

無効化後、APIが404になることを確認します。

```bash
curl -i -H "Authorization: Bearer <TOKEN>" \
  https://idp.example.ac.jp/idp/graphicalmatrix-admin/api/v1/health
```

期待値:

```text
HTTP/1.1 404
```

## 9. 運用周期

推奨:

- 定期ローテーション: 90日から180日ごと
- 緊急ローテーション: 漏えい疑い、担当者変更、クライアント侵害時
- 監査: ローテーション実施日、作業者、対象環境、検証結果を記録する

## 10. 作業記録テンプレート

```text
作業日:
作業者:
対象環境:
token_file:
backup_file:
API利用クライアント:
新token反映時刻:
GET /health 新token結果:
GET /health 旧token結果:
rollback要否:
備考:
```
