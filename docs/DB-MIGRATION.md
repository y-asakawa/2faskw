# DB Migration

この文書は GraphicalMatrix MFA のDBを H2 から PostgreSQL へ移行する手順です。

本番では PostgreSQL を推奨します。
H2 は PoC / 検証用途に限定してください。

## 前提

- IdPの設定、webapp、DBをバックアップ済みである
- PostgreSQLサーバ、DB、ユーザーを作成済みである
- PostgreSQLへIdPサーバから接続できる
- `psql` が作業サーバに入っている
- H2 export時はH2 DBへ書き込みが発生しないよう、Jetty停止またはメンテナンス状態にする
- 移行CSVは `sequence` と `totp_seed` を含むため秘密情報として扱う

## 関連ファイル

```text
/opt/shibboleth-idp/conf/graphicalmatrix/db.properties
/opt/shibboleth-idp/conf/graphicalmatrix/postgresql-schema.sql
/opt/shibboleth-idp/bin/graphicalmatrix-db.sh
/opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh
```

移行補助スクリプト:

```text
bin/graphicalmatrix-db-migration.sh
```

## 1. PostgreSQL DBとユーザー作成

例:

```bash
sudo -u postgres createuser graphicalmatrix_app
sudo -u postgres createdb -O graphicalmatrix_app graphicalmatrix
```

パスワード設定例:

```bash
sudo -u postgres psql -c "ALTER USER graphicalmatrix_app WITH PASSWORD '<DB_PASSWORD>';"
```

## 2. DBパスワードファイル作成

```bash
sudo install -d -m 0750 -o jetty -g jetty /opt/shibboleth-idp/credentials
sudo sh -c 'printf "%s\n" "<DB_PASSWORD>" > /opt/shibboleth-idp/credentials/graphicalmatrix-db.password'
sudo chown jetty:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
sudo chmod 0400 /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
```

## 3. PostgreSQL用 db.properties

```properties
graphicalmatrix.db.driver=org.postgresql.Driver
graphicalmatrix.db.url=jdbc:postgresql://<DB_HOST>:5432/graphicalmatrix
graphicalmatrix.db.user=graphicalmatrix_app
graphicalmatrix.db.passwordFile=/opt/shibboleth-idp/credentials/graphicalmatrix-db.password
```

切替前に、現在のH2用 `db.properties` は退避してください。

```bash
sudo cp /opt/shibboleth-idp/conf/graphicalmatrix/db.properties \
  /opt/shibboleth-idp/conf/graphicalmatrix/db.properties.h2.bak.$(date +%Y%m%d-%H%M%S)
```

## 4. 移行前カウント確認

H2の件数を確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh h2-count
```

既にH2用 `db.properties` を退避済みの場合は、H2接続情報を明示します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh h2-count \
  --h2-url 'jdbc:h2:file:/opt/shibboleth-idp/credentials/graphicalmatrix;MODE=PostgreSQL;DATABASE_TO_UPPER=false' \
  --h2-user sa
```

## 5. Jetty停止

最終export前に、H2への更新を止めます。

例:

```bash
sudo systemctl stop jetty
```

PoCで別の起動方式を使っている場合は、その環境の手順に従ってください。

## 6. H2からCSV export

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh h2-export \
  --output /tmp/graphicalmatrix-migration.csv
```

出力CSVは秘密情報です。

```bash
sudo chmod 0600 /tmp/graphicalmatrix-migration.csv
```

## 7. PostgreSQLスキーマ適用

dry-run:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-apply-schema
```

適用:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-apply-schema --apply
```

## 8. PostgreSQLへimport

dry-run:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-import \
  --input /tmp/graphicalmatrix-migration.csv
```

適用:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-import \
  --input /tmp/graphicalmatrix-migration.csv \
  --apply
```

既存行をすべて削除してから取り込みたい場合:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-import \
  --input /tmp/graphicalmatrix-migration.csv \
  --truncate \
  --apply
```

通常は `--truncate` なしを推奨します。
`user_id` が一致する行は upsert で更新されます。

## 9. 移行結果確認

件数確認:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-count
```

CSVとPostgreSQLの `user_id` 件数・checksum確認:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-verify \
  --input /tmp/graphicalmatrix-migration.csv
```

期待値:

```text
verify=OK
```

管理CLIで確認:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh show <USER_ID>
```

## 10. PostgreSQLへ切替

`/opt/shibboleth-idp/conf/graphicalmatrix/db.properties` をPostgreSQL設定にします。

その後、IdP webappを再構築し、Jettyを起動します。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl start jetty
```

## 11. 動作確認

- GraphicalMatrixログイン
- TOTP選択済みユーザーのログイン
- WebAuthn選択済みユーザーの遷移
- `graphicalmatrix-db.sh list`
- 管理APIを有効化している場合は `GET /health`
- `/opt/shibboleth-idp/logs/graphicalmatrix-audit.log`

## 12. rollback

PostgreSQL切替後に問題がある場合:

1. Jettyを停止する
2. `db.properties` をH2版へ戻す
3. IdP webappを再構築する
4. Jettyを起動する
5. H2側でログイン確認する

例:

```bash
sudo systemctl stop jetty
sudo cp /opt/shibboleth-idp/conf/graphicalmatrix/db.properties.h2.bak.YYYYMMDD-HHMMSS \
  /opt/shibboleth-idp/conf/graphicalmatrix/db.properties
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl start jetty
```

## 13. 移行後の後始末

移行CSVは秘密情報です。
確認完了後、安全に削除してください。

```bash
sudo shred -u /tmp/graphicalmatrix-migration.csv
```

`shred` が使えないファイルシステムでは、運用ルールに従って削除してください。

## 14. 注意事項

- H2とPostgreSQLを同時に更新し続ける構成にはしない
- IdP複数台構成では全IdPが同じPostgreSQLを参照する
- `sequence` 保存方式を変更する場合は、DB移行とは別作業として扱う
- hash方式のsequenceは復号できないため、移行CSVでも現在値確認はできない
- TOTP seedは秘密情報のため、CSV、バックアップ、ログへの露出を避ける
- PostgreSQL JDBC driverがIdP webappへ配置されていることを確認する

## 作業記録テンプレート

```text
作業日:
作業者:
対象IdP:
H2件数:
CSVファイル:
PostgreSQL DB:
PostgreSQL件数:
pg-verify結果:
Jetty停止時刻:
Jetty起動時刻:
ログイン確認ユーザー:
rollback要否:
備考:
```
