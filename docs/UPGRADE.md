# 2FAS-KW Plugin Upgrade Guide

この文書は、2FAS-KW Plugin for Shibboleth IdPを既存環境で更新するための推奨手順をまとめる。

対象:

- v1.0.0 / v1.0.1 から v1.1.0 への更新
- v1.1.0 から v1.2.0 への更新
- v1.0.x から v1.2.0 へ更新する場合の段階的な確認

別バージョンへ更新する場合は、JAR名と配布物のバージョンを読み替えること。

## 更新方針

既存設定を維持した上書き更新は可能だが、単純に新しいファイルをコピーするだけでは不十分である。
Plugin JARはバージョンを含むファイル名で配置されるため、旧JARを削除する必要がある。

```text
2faskw-idp-plugin-1.0.1.jar
2faskw-idp-plugin-1.1.0.jar
2faskw-idp-plugin-1.2.0.jar
```

新旧JARが同時に残ると、同じJavaクラスが複数のJARに存在してロード結果が不定になる可能性がある。

v1.1.0ではDB状態とsequence保存方式のセキュリティmigrationが必要です。
v1.0.xから更新する場合は、通常の更新手順を実行する前にv1.1.0のセキュリティ更新項目を確認してください。

v1.2.0ではLDAP保存とWebAuthn LDAP StorageServiceが追加されます。
既定値は引き続きDB保存です。v1.1.0からv1.2.0へ更新するだけなら、既存DB保存データはそのまま利用できます。
LDAP保存へ切り替える場合は、Plugin更新とは別にLDAP schema、ACL、既存データ移行を設計してから実施してください。

## バージョン別の追加確認

| 更新元 | 更新先 | 必須対応 | 任意対応 |
| --- | --- | --- | --- |
| v1.0.0 / v1.0.1 | v1.1.0 | 旧JAR削除、DB schema確認、sequence保存方式の保護、設定差分反映 | 管理API、CSV運用、logrotate設定 |
| v1.1.0 | v1.2.0 | 旧JAR削除、v1.2.0テンプレート差分反映、config check | LDAP保存、TOTP seed暗号化設定見直し、WebAuthn LDAP StorageService |
| v1.0.x | v1.2.0 | v1.1.0の必須対応を先に完了し、その後v1.2.0差分を反映 | LDAP保存へ切り替える場合は別途移行計画を作成 |

v1.2.0の推奨構成:

- GraphicalMatrix / TOTP / MFA方式選択の保存先はDBを推奨する
- `graphicalmatrix.savedata` の既定値は `db`
- LDAP保存は、既存LDAP運用に登録情報を寄せたい場合のオプション
- WebAuthn credential保存はDB/JDBC StorageServiceを推奨する
- WebAuthn credentialをLDAPへ保存する場合は `subtree` 方式を推奨する

## 事前確認

更新前に以下を確認する。

- 更新対象のIdP、Java、Jettyが新バージョンの動作条件を満たしている
- Plugin配布ZIPのchecksumまたは署名を検証している
- PostgreSQLおよびIdP設定のバックアップを取得できる
- Jettyの停止時間を確保している
- ロールバックに使用する旧Plugin JARと設定ファイルを保管している
- LDAP保存へ切り替える場合は、LDAP schema、ACL、service account、LDAPS接続を検証済み
- WebAuthnを使う場合は、FQDN、HTTPS、RP ID、ブラウザの信頼済み証明書を確認済み

現在のPlugin JARを確認する。

```bash
ls -l /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar
```

## バックアップ

JARと設定ファイルをバックアップする。

```bash
TS=$(date +%Y%m%d%H%M%S)

sudo cp -a /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib.bak.$TS

sudo cp -a /opt/shibboleth-idp/conf/graphicalmatrix \
  /opt/shibboleth-idp/conf/graphicalmatrix.bak.$TS
```

必要に応じて、以下もバックアップする。

```bash
sudo cp -a /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml.bak.$TS

sudo cp -a /opt/shibboleth-idp/credentials \
  /opt/shibboleth-idp/credentials.bak.$TS
```

v1.2.0でWebAuthn設定を使う場合は、authn設定もバックアップする。

```bash
sudo cp -a /opt/shibboleth-idp/conf/authn \
  /opt/shibboleth-idp/conf/authn.bak.$TS

sudo cp -a /opt/shibboleth-idp/conf/global.xml \
  /opt/shibboleth-idp/conf/global.xml.bak.$TS
```

DBバックアップは、環境のPostgreSQLバックアップ手順に従って取得する。
LDAP保存へ切り替える場合は、対象ユーザー属性またはWebAuthn StorageService subtreeもLDAP側の手順でバックアップする。

## 配布物の事前検査

展開した新バージョンの配布物ディレクトリで、package checkを実行する。

```bash
sudo ./bin/graphicalmatrix-plugin-check.sh --package-only
```

期待値:

```text
result: OK
```

導入内容をdry-run確認する。

```bash
sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp
```

期待値:

```text
mode=dry-run
package_check=enabled
package_check=running
...
summary: mode=dry-run planned_changes=N applied_changes=0 backups_created=0 templates_deferred=N failures=0 strict=0 package_check=enabled
result: DRY_RUN_OK
next: review planned commands, then re-run with --apply to install.
```

`planned_changes` と `templates_deferred` の件数は、既存ファイルの有無や設定差分によって変わる。
dry-runでは `applied_changes=0`、`backups_created=0`、`failures=0`、`result: DRY_RUN_OK` であることを確認する。

dry-runで意図しない削除や配置先が表示された場合は、`--apply`を実行しない。
`ERROR:`、`result: FAILED`、想定外の `idp_home` / 配置先パスが表示された場合も、原因を修正してから再度dry-runする。

## Pluginファイルの更新

Jettyを停止する。

```bash
sudo systemctl stop jetty-idp.service
```

新バージョンを配置する。

```bash
./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --apply
```

旧Plugin JARだけを削除する。

v1.0.1からv1.1.0へ更新する例:

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.0.1.jar
```

v1.1.0からv1.2.0へ更新する例:

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.1.0.jar
```

新しいPlugin JARだけが残っていることを確認する。

```bash
ls -l /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar
```

v1.1.0への更新では、次の1ファイルだけが表示される状態にする。

```text
2faskw-idp-plugin-1.1.0.jar
```

v1.2.0への更新では、次の1ファイルだけが表示される状態にする。

```text
2faskw-idp-plugin-1.2.0.jar
```

`core-*.jar`、`HikariCP-*.jar`、`postgresql-*.jar`などの依存JARは、配布物のバージョンとIdP環境の互換性を確認して更新する。
旧依存JARを削除する場合は、他のPluginが同じJARを使用していないことを確認する。

## 既存設定ファイルの更新

導入スクリプトは、既存の `graphicalmatrix.properties`、HTML、CSSを直接上書きしない。
新しいテンプレートは、次のような名前で配置される。

```text
graphicalmatrix.properties.idpnew.TIMESTAMP
db.properties.idpnew.TIMESTAMP
ldap.properties.idpnew.TIMESTAMP
webauthn-ldap.properties.idpnew.TIMESTAMP
api.properties.idpnew.TIMESTAMP
mfa-policy.properties.idpnew.TIMESTAMP
authn/webauthn.properties.idpnew.TIMESTAMP
authn/webauthn-registration.properties.idpnew.TIMESTAMP
authn/webauthn-metadata.properties.idpnew.TIMESTAMP
views/*.html.idpnew.TIMESTAMP
graphicalmatrix.css.idpnew.TIMESTAMP
```

既存ファイルと新しいテンプレートを比較する。

```bash
sudo find /opt/shibboleth-idp/conf/graphicalmatrix \
  -name '*.idpnew*' \
  -type f \
  -print

sudo find /opt/shibboleth-idp/conf/authn \
  -name '*.idpnew*' \
  -type f \
  -print
```

例:

```bash
sudo diff -u \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties.idpnew.TIMESTAMP

sudo diff -u \
  /opt/shibboleth-idp/conf/authn/webauthn.properties \
  /opt/shibboleth-idp/conf/authn/webauthn.properties.idpnew.TIMESTAMP
```

新バージョンで追加または変更された項目だけを既存設定へ反映する。
DBのユーザーデータは、Pluginの上書き更新だけでは削除または初期化されない。

secret、DBパスワード、API token、秘密鍵を新しいテンプレートで上書きしないこと。

## v1.0.xからv1.1.0への追加手順

v1.1.0では、DB状態とsequence保存方式の安全性を上げるための移行確認が必要です。

確認する項目:

- `graphicalmatrix.sequence.storage` を本番で `plaintext` のままにしない
- `auto` または `hash` を使う場合は `graphicalmatrix.sequence.pepperFile` を作成する
- 既存の平文sequenceを保護済み形式へ移行する
- `status`、`failed_count`、`locked_until`、`force_sequence_change` などのDB状態列が期待通り存在する
- 管理APIを使う場合はtoken、許可CIDR、sequence露出制御を確認する

v1.0.xから直接v1.2.0へ更新する場合も、このv1.1.0の確認を先に完了してください。

## v1.1.0からv1.2.0への追加手順

v1.2.0の更新では、DB保存の既存環境をそのまま維持するか、LDAP保存を新たに選択するかを分けて判断します。
推奨はDB保存の継続です。

### DB保存を継続する場合

`graphicalmatrix.properties` で保存先を明示する場合は、以下を設定します。
未設定でも既定値は `db` です。

```properties
graphicalmatrix.savedata = db
```

TOTPを使用する場合は、TOTP seedが復号可能な保存方式になっていることを確認します。
sequence保存が `auto` または `hash` の場合、TOTP seedに `auto` を使うと復号できないため、`aes-gcm` または `keyword` を明示します。

```properties
graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

鍵ファイルがない場合は作成します。

```bash
sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
openssl rand -base64 32 | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key >/dev/null
sudo chown root:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
sudo chmod 0640 /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

### LDAP保存へ切り替える場合

LDAP保存はオプションです。Plugin更新と同時に本番データの保存先を切り替える場合は、事前に
`docs/INSTALL_LDAP.md` を読み、LDAP schema、ACL、service account、LDAPS、既存データ移行を完了してください。

`graphicalmatrix.properties`:

```properties
graphicalmatrix.savedata = ldap
```

`ldap.properties`:

```properties
graphicalmatrix.ldap.url = ldaps://ldap.example.jp:636
graphicalmatrix.ldap.baseDN = OU=people,DC=example,DC=jp
graphicalmatrix.ldap.userFilter = (cn={user})
graphicalmatrix.ldap.subtreeSearch = true
graphicalmatrix.ldap.bindDN = CN=graphicalmatrix-writer,OU=system,DC=example,DC=jp
graphicalmatrix.ldap.bindCredentialFile = /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret
```

属性名は、LDAP schema、ACL、`ldap.properties` で必ず一致させます。
DBにある既存ユーザー登録情報は、`graphicalmatrix.savedata=ldap` に変更しただけではLDAPへ自動移行されません。
切り替え前に、GraphicalMatrix sequence、状態、MFA方式、TOTP情報をLDAP属性へ移行してください。

v1.2.0フェーズ1では、Admin users APIはLDAP保存ユーザーの作成・更新に未対応です。
LDAP保存を選択したユーザーの初期投入や一括更新は、LDAP側の運用手順で実施してください。

### WebAuthnを使う場合

WebAuthnはShibboleth WebAuthn pluginが必要です。
2FAS-KW plugin更新だけでは、Shibboleth WebAuthn pluginの導入や署名鍵truststore登録は完了しません。

推奨はDB/JDBC StorageServiceです。
LDAPへ保存する場合は `subtree` 方式を推奨します。

DB/JDBC StorageServiceを使う場合:

```properties
idp.authn.webauthn.StorageService = shibboleth.StorageService
```

LDAP StorageServiceを使う場合:

```properties
idp.authn.webauthn.StorageService = GraphicalMatrixLDAPStorageService
```

LDAP StorageServiceを使う場合は、`examples/webauthn-ldap-storage-config.xml` のbeanをIdPのSpring設定に読み込ませ、
`/opt/shibboleth-idp/conf/graphicalmatrix/webauthn-ldap.properties` を設定します。

```properties
graphicalmatrix.webauthn.ldap.layout = subtree
graphicalmatrix.webauthn.ldap.baseDN = OU=WebAuthnStorage,DC=example,DC=jp
graphicalmatrix.webauthn.ldap.attr.context = gmStorageContext
graphicalmatrix.webauthn.ldap.attr.id = gmStorageId
graphicalmatrix.webauthn.ldap.attr.expires = gmStorageExpires
graphicalmatrix.webauthn.ldap.attr.value = gmStorageValue
graphicalmatrix.webauthn.ldap.attr.version = gmStorageVersion
```

既存WebAuthn credentialを別StorageServiceへ自動移行する手順はありません。
StorageServiceを変更する場合は、既存credentialの移行または利用者の再登録を計画してください。

## 設定検査

ユーザーが認証を開始する前に設定検査を実行する。

```bash
sudo ./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

本番環境でWARNも失敗として扱う場合:

```bash
sudo ./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only \
  --strict
```

設定不正の場合はJettyを起動せず、`CONFIG_CHECK_FAILED`の内容を修正する。

## WAR再構築とJetty起動

WARを再構築する。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
```

Jettyを起動する。

```bash
sudo systemctl start jetty-idp.service
```

起動状態とログを確認する。

```bash
sudo systemctl status jetty-idp.service --no-pager
sudo journalctl -u jetty-idp.service -n 200 --no-pager
sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log
```

## 更新後の動作試験

最低限、以下を確認する。

- LDAPパスワード認証
- GraphicalMatrix認証の成功
- GraphicalMatrix認証の失敗と再試行
- ロックとアンロック
- sequence変更画面
- `graphicalmatrix.choice`と登録sequence数の整合
- TOTPを使用する場合は登録と認証
- WebAuthnを使用する場合は登録済みcredentialによる認証
- LDAP保存を使用する場合はLDAP属性の読み書きとACL
- WebAuthn LDAP保存を使用する場合はStorageService subtreeへの登録、検索、削除
- 管理APIを使用する場合はread-only疎通確認
- `graphicalmatrix-audit.log`への監査記録

設定検査を再実行する。

```bash
./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

## ロールバック

問題が発生した場合はJettyを停止する。

```bash
sudo systemctl stop jetty-idp.service
```

新バージョンのPlugin JARを削除する。

v1.1.0の例:

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.1.0.jar
```

v1.2.0の例:

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.2.0.jar
```

バックアップした旧Plugin JARと設定ファイルを復元する。
復元元のタイムスタンプを確認してから実行すること。

```bash
# 実際のバックアップパスへ置き換える。
sudo cp -a \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib.bak.TIMESTAMP/. \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/

sudo cp -a \
  /opt/shibboleth-idp/conf/graphicalmatrix.bak.TIMESTAMP/. \
  /opt/shibboleth-idp/conf/graphicalmatrix/
```

v1.2.0でauthn設定やglobal.xmlを変更した場合は、それらも復元する。

```bash
sudo cp -a \
  /opt/shibboleth-idp/conf/authn.bak.TIMESTAMP/. \
  /opt/shibboleth-idp/conf/authn/

sudo cp -a \
  /opt/shibboleth-idp/conf/global.xml.bak.TIMESTAMP \
  /opt/shibboleth-idp/conf/global.xml
```

WARを再構築してJettyを起動する。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl start jetty-idp.service
```

旧バージョンで認証試験とログ確認を行う。

LDAP schemaやLDAP上のユーザー属性を追加した場合、PluginロールバックだけではLDAP側の変更は戻りません。
必要であればLDAP側のバックアップから復元してください。
