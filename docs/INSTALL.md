# Install Guide

この文書は 2FAS-KW Plugin 配布物を Shibboleth IdP 5 へ導入する手順です。

この配布物は内部配布用です。
署名済み公式Shibboleth plugin packageとしての外部配布は未完成です。

## 1. 事前確認

対象:

- Shibboleth IdP 5.2系
- Java 21
- Jetty 12
- PostgreSQL推奨
- H2はPoC / 検証用途のみ

作業前に取得するもの:

- IdP設定バックアップ
- `edit-webapp` バックアップ
- `web.xml` バックアップ
- DBバックアップ
- 現在のPlugin ZIP

確認:

```bash
/opt/shibboleth-idp/bin/build.sh --help >/dev/null
test -f /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml
```

## 2. 配布物展開

```bash
cd /tmp
unzip 2faskw-idp-plugin-1.0.1.zip
cd 2faskw-idp-plugin-1.0.1
```

## 3. 配布物の完全性確認

配布物には以下が含まれます。

```text
plugin-metadata/PACKAGE-CONTENTS.txt
plugin-metadata/PACKAGE-MANIFEST.sha256
plugin-metadata/graphicalmatrix-plugin.properties
```

checksum確認:

```bash
sha256sum -c plugin-metadata/PACKAGE-MANIFEST.sha256
```

macOSなど `sha256sum` がない環境では `shasum -a 256` を利用してください。

注意:

- `PACKAGE-MANIFEST.sha256` は展開後のファイル確認用です
- 外部配布時は別途、配布ZIP自体の署名と公開鍵検証を用意してください
- `bootstrap/keys.txt` はPoC placeholderです

## 4. package check

```bash
./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/2faskw-idp-plugin-1.0.1
```

期待値:

```text
summary: failures=0
```

TOTP / WebAuthn を使わない場合、それらのoptional plugin未検出WARNは許容できます。

## 5. plugin files dry-run

```bash
./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/2faskw-idp-plugin-1.0.1
```

確認すること:

- `edit-webapp/WEB-INF/lib/` へ配置されるJAR
- `conf/graphicalmatrix/*.idpnew` の配置
- `bin/` へ配置される管理スクリプト
- 既存ファイルがある場合のbackup / `.idpnew.TIMESTAMP`

## 6. plugin files apply

```bash
sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/2faskw-idp-plugin-1.0.1 \
  --apply
```

配置される主なJAR:

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.0.1.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/core-3.5.3.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/HikariCP-6.3.0.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/postgresql-42.7.11.jar
```

## 7. 設定ファイル確認

既存設定がある場合、導入スクリプトは上書きせず `.idpnew.TIMESTAMP` を配置します。
差分確認して必要な設定だけ反映してください。

全設定項目の詳細は以下を参照してください。

```text
docs/CONFIG-REFERENCE.md
```

確認対象:

```text
/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
/opt/shibboleth-idp/conf/graphicalmatrix/db.properties
/opt/shibboleth-idp/conf/graphicalmatrix/api.properties
/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
```

管理APIは初期状態で無効にしてください。

```properties
graphicalmatrix.api.enabled = false
graphicalmatrix.api.allowedCidrs = 127.0.0.1/32,192.168.0.0/24
graphicalmatrix.api.bearerTokenFile = /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
graphicalmatrix.api.authFailureLimit = 5
graphicalmatrix.api.authFailureWindowSeconds = 60
graphicalmatrix.api.authFailureLockSeconds = 300
graphicalmatrix.api.response.excludeSequences = true
graphicalmatrix.api.sequence.requireProtectedStorage = true
```

GraphicalMatrixチャレンジ有効期限は `graphicalmatrix.properties` で設定できます。

```properties
graphicalmatrix.challenge.seconds = 180
```

設定可能範囲は30〜900秒です。

MFAポリシーは `mfa-policy.properties` で設定します。

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.bypassSPs =
graphicalmatrix.mfa.requiredSPs =
graphicalmatrix.mfa.bypassIPs =
graphicalmatrix.mfa.bypassCIDRs =
graphicalmatrix.mfa.useForwardedFor = false
```

`useForwardedFor=true` は、信頼済みReverse Proxy/LBが送信元IPヘッダを
安全に上書きし、IdPへの直接接続をFirewall/LBで遮断している構成でのみ
利用してください。直接接続が残っている場合、クライアントが
`X-Forwarded-For` / `X-Real-IP` を偽造してMFAバイパスIP/CIDR判定を
すり抜ける可能性があるため、`false` のままにしてください。

## 8. DB設定

PostgreSQLを利用する場合:

```bash
psql -h <DB_HOST> -U <DDL_USER> -d graphicalmatrix \
  -f /opt/shibboleth-idp/conf/graphicalmatrix/postgresql-schema.sql
```

DBスキーマは事前適用し、IdP実行時のDDL自動実行は無効にする。

```properties
graphicalmatrix.db.autoInit = false
```

IdP runtime servlet、管理API、TOTP seed参照はHikariCP接続プールを利用する。

```properties
graphicalmatrix.db.pool.enabled = true
graphicalmatrix.db.pool.maximumPoolSize = 10
graphicalmatrix.db.pool.minimumIdle = 2
graphicalmatrix.db.pool.connectionTimeoutMillis = 30000
graphicalmatrix.db.pool.idleTimeoutMillis = 600000
graphicalmatrix.db.pool.maxLifetimeMillis = 1800000
graphicalmatrix.db.pool.validationTimeoutMillis = 5000
```

H2からPostgreSQLへ移行する場合:

```text
docs/DB-MIGRATION.md
```

sequence保存方式を変更する場合:

```text
docs/SEQUENCE-STORAGE-MIGRATION.md
```

PoCでH2を利用する場合は、`db.properties` をH2向けにしてください。

## 9. web.xml dry-run

```bash
./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --install
```

既存PoC設定がある場合は、二重追加せず `existing_manual_entries_detected` になります。

## 10. web.xml apply

```bash
sudo ./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --install \
  --apply
```

バックアップ:

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml.bak.TIMESTAMP
```

## 11. IdP rebuild

```bash
sudo /opt/shibboleth-idp/bin/build.sh
```

## 12. Jetty restart

systemd例:

```bash
sudo systemctl restart jetty
```

環境固有の起動方式がある場合は、その手順に従ってください。

## 13. 動作確認

変更画面:

```bash
curl -s -o /tmp/im-change.out -w "change_http=%{http_code}\n" \
  http://127.0.0.1:8080/idp/graphicalmatrix/change
```

管理CLI:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
```

API無効確認:

```bash
curl -s -o /tmp/im-api.out -w "api_http=%{http_code}\n" \
  http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1/health
```

期待値:

```text
api_http=404
```

APIを有効化する環境では、以下も確認します。

```text
docs/API-CURL-TESTS.md
```

読み取りテスト:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh \
  --base-url http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1 \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

書き込みテスト:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh \
  --base-url http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1 \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token \
  --write \
  --user api-test01
```

## 14. ログ確認

```bash
sudo tail /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

logrotate設定例:

```text
docs/LOGROTATE.md
examples/logrotate/graphicalmatrix-audit
```

## 15. Admin Tools単体インストール

管理CLIだけをDBサーバまたは管理端末へ導入する場合は、
`2faskw-admin-tools-1.0.1.zip` を利用する。

この導入では、Shibboleth IdP、Jetty、`web.xml` は変更しない。

```bash
cd /tmp
unzip 2faskw-admin-tools-1.0.1.zip
cd 2faskw-admin-tools-1.0.1
```

dry-run:

```bash
sudo ./bin/graphicalmatrix-admin-install.sh \
  --prefix /opt/graphicalmatrix-admin
```

適用:

```bash
sudo ./bin/graphicalmatrix-admin-install.sh \
  --prefix /opt/graphicalmatrix-admin \
  --apply
```

DB接続設定:

```text
/opt/graphicalmatrix-admin/conf/graphicalmatrix/db.properties
```

確認:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh list
```

詳細:

```text
docs/ADMIN-TOOLS.md
```

## 16. rollback

1. Jettyを停止する
2. `graphicalmatrix-plugin-webxml.sh --remove --apply` を実行する
3. `graphicalmatrix-plugin-uninstall.sh --apply` を実行する
4. 必要に応じて `web.xml.bak.TIMESTAMP` を戻す
5. `build.sh` を実行する
6. Jettyを起動する

web.xml marker削除:

```bash
sudo ./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --remove \
  --apply
```

plugin overlay削除:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply
```

設定も退避する場合:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply \
  --remove-config
```

runtime依存JARも削除する場合:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply \
  --remove-runtime-deps
```

## 16. install記録テンプレート

```text
作業日:
作業者:
対象IdP:
Plugin ZIP:
PACKAGE-MANIFEST確認:
事前check結果:
config dry-run結果:
web.xml dry-run結果:
build.sh結果:
Jetty restart結果:
/idp/graphicalmatrix/change確認:
管理CLI確認:
API無効確認:
rollback要否:
備考:
```
