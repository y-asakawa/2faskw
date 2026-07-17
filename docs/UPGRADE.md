# 2FAS-KW Plugin Upgrade Guide

この文書は、2FAS-KW Plugin for Shibboleth IdPを既存環境で更新するための推奨手順をまとめる。

対象:

- v1.0.0 / v1.0.1 から v1.1.0 への更新
- v1.1.0 から v1.2.0 への更新
- v1.0.x から v1.2.0 へ更新する場合の段階的な確認
- v1.2.3 から v1.2.4 への更新

別バージョンへ更新する場合は、JAR名と配布物のバージョンを読み替えること。

## バージョン別の追加確認

| 更新元 | 更新先 | 必須対応 | 任意対応 |
| --- | --- | --- | --- |
| v1.0.0 / v1.0.1 | v1.1.0 | 旧JAR削除、DB schema確認、sequence保存方式の保護、設定差分反映 | 管理API、CSV運用、logrotate設定 |
| v1.1.0 | v1.2.0 | 旧JAR削除、v1.2.0テンプレート差分反映、config check | LDAP保存、TOTP seed暗号化設定見直し、WebAuthn LDAP StorageService |
| v1.0.x | v1.2.0 | v1.1.0の必須対応を先に完了し、その後v1.2.0差分を反映 | LDAP保存へ切り替える場合は別途移行計画を作成 |
| v1.2.3 | v1.2.4 | 旧JAR削除、WAR再構築、設定検査、既存認証の回帰試験 | IdP自己管理フローの有効化、従来LDAP変更経路の停止 |

v1.1.0ではDB状態とsequence保存方式のセキュリティmigrationが必要です。
v1.0.xから更新する場合は、通常の更新手順を実行する前にv1.1.0のセキュリティ更新項目を確認してください。

v1.2.0ではLDAP保存とWebAuthn LDAP StorageServiceが追加されます。
既定値は引き続きDB保存です。v1.1.0からv1.2.0へ更新するだけなら、既存DB保存データはそのまま利用できます。
LDAP保存へ切り替える場合は、Plugin更新とは別にLDAP schema、ACL、既存データ移行を設計してから実施してください。

v1.2.0の推奨構成:

- GraphicalMatrix / TOTP / MFA方式選択の保存先はDBを推奨する
- `graphicalmatrix.savedata` の既定値は `db`
- LDAP保存は、既存LDAP運用に登録情報を寄せたい場合のオプション
- WebAuthn credential保存はDB/JDBC StorageServiceを推奨する
- WebAuthn credentialをLDAPへ保存する場合は `subtree` 方式を推奨する

v1.2.4では、Password + 現在のMFA方式による強制再認証後に変更画面を開始するIdP自己管理フローを
追加します。既定では自己管理フローは無効、従来LDAP変更経路は有効であるため、JAR更新だけで
従来の変更画面が自動的に無効になることはありません。

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
sudo ls -l /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar
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

v1.2.0でWebAuthn設定を使う場合、またはv1.2.4でIdP自己管理フローを有効にする場合は、
authn設定もバックアップする。

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

***
***

## 以下の追加手順が必要ない場合は、設定検査から始めてください。

## 追加手順
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

## v1.2.3からv1.2.4への追加手順

v1.2.4では、IdP内でPassword + 現在のMFA方式を強制再認証してから、GraphicalMatrix sequenceおよび
MFA方式の変更画面を開始する自己管理フローを追加します。詳細は
[INSTALL_Passchange_IdP.md](./INSTALL_Passchange_IdP.md)を参照してください。

この更新による`graphicalmatrix_enrollment`のスキーマ変更、LDAP schema変更、登録データ移行は
ありません。更新直後は以下が既定値となり、v1.2.3の従来LDAP変更経路を維持します。

```properties
graphicalmatrix.selfservice.enabled = false
graphicalmatrix.selfservice.transactionTtlSeconds = 600
graphicalmatrix.change.legacyLdapLoginEnabled = true
```

### 導入方式を確認する

更新前に、v1.2.3を`plugin.sh`で導入したか、展開ZIPから手動導入したかを確認します。

```bash
sudo /opt/shibboleth-idp/bin/plugin.sh -l

sudo find \
  /opt/shibboleth-idp/dist/plugin-webapp/WEB-INF/lib \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '2faskw-idp-plugin-*.jar' -print
```

`plugin.sh`管理環境では、本書末尾の「v1.2.xからv1.2.xへのplugin.sh更新」を使用します。
手動導入環境では、本書の「配布物の事前検査」から「Pluginファイルの更新」までを実行します。
両方式を混在させて、`dist/plugin-webapp`と`edit-webapp`に2FAS-KW JARを重複配置してはなりません。

手動導入環境では、v1.2.4配置後にv1.2.3のJARだけを削除します。

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.2.3.jar

sudo find \
  /opt/shibboleth-idp/dist/plugin-webapp/WEB-INF/lib \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '2faskw-idp-plugin-*.jar' -print
```

v1.2.4のJARだけが表示されることを確認します。Administrative Flow定義はv1.2.4 JAR内に
含まれるため、flow XMLを`/opt/shibboleth-idp`へ手動コピーする必要はありません。

### 互換設定で更新する

最初に、稼働中の設定ファイル
`/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`へ以下を追加します。

```properties
graphicalmatrix.selfservice.enabled = false
graphicalmatrix.selfservice.transactionTtlSeconds = 600
graphicalmatrix.change.legacyLdapLoginEnabled = true
```

この状態で設定検査、WAR再構築、Jetty起動を行い、v1.2.3で使用していた以下の機能が回帰して
いないことを先に確認します。

- 通常のPassword + MFA認証
- `/idp/graphicalmatrix/change`の従来LDAPログイン
- GraphicalMatrix sequence変更
- 使用中の場合はTOTPおよびWebAuthn認証
- DB保存またはLDAP保存の読み書き

### IdP自己管理フローを有効にする

`/opt/shibboleth-idp/conf/authn/authn.properties`で、`idp.authn.flows`に`MFA`が含まれることを
確認し、GraphicalMatrix External flowのForceAuthn対応を有効にします。他の認証Flowを併用して
いる場合は、既存の`idp.authn.flows`から必要な値を削除しないでください。

```properties
idp.authn.flows = MFA
idp.authn.External.externalAuthnPath = contextRelative:/graphicalmatrix/start
idp.authn.External.nonBrowserSupported = false
idp.authn.External.passiveAuthenticationSupported = false
idp.authn.External.forcedAuthenticationSupported = true
```

次に、`/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`を以下へ変更します。
最初の試験では従来LDAP経路を残します。

```properties
graphicalmatrix.selfservice.enabled = true
graphicalmatrix.selfservice.transactionTtlSeconds = 600
graphicalmatrix.change.legacyLdapLoginEnabled = true
```

設定検査を実行します。

```bash
sudo /path/to/extracted-1.2.4/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

期待値:

```text
OK: [config] self-service valid: enabled=true transaction_seconds=600 legacy_ldap_login=true
OK: [config] self-service authentication flow enabled: idp.authn.flows=MFA
OK: [config] GraphicalMatrix External flow supports forced authentication
result: OK
```

WARを再構築してJettyを起動します。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
sudo systemctl is-active jetty-idp.service
```

新しいブラウザセッションで次へアクセスし、Passwordと現在のMFA方式が毎回要求されることを
確認します。

```text
https://idp.example.org/idp/profile/2faskw/self-service
```

認証成功後は、次のURLへ自動的に遷移して変更メニューが表示されます。handoff URLを直接入力して
試験してはいけません。

```text
https://idp.example.org/idp/graphicalmatrix/change?mode=idp-self-service
```

監査ログで次の順序を確認します。

```bash
sudo tail -n 50 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

```text
event=SELF_SERVICE_AUTH ... result=OK ...
event=SELF_SERVICE_HANDOFF ... result=OK ... detail=one_time_handoff_consumed
```

DB保存またはLDAP保存の利用環境で、sequence変更とMFA方式変更を確認します。TOTPまたはWebAuthnを
現在のMFA方式として使用する場合は、それぞれのFlowがForceAuthn要求に対応し、同じ自己管理URLから
再認証できることも確認します。

全試験が完了した後、従来LDAPログインを停止する場合だけ次へ変更します。

```properties
graphicalmatrix.change.legacyLdapLoginEnabled = false
```

### v1.2.4自己管理フローだけを無効に戻す

自己管理フローに問題があっても、直ちにv1.2.3へJARを戻す必要はありません。まず
`/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`を次へ戻すことで、
v1.2.3と同じ従来LDAP変更経路を使用できます。

```properties
graphicalmatrix.selfservice.enabled = false
graphicalmatrix.change.legacyLdapLoginEnabled = true
```

完全にv1.2.3へ戻す場合は、本書のロールバック手順に従ってv1.2.4 JARを削除し、バックアップした
v1.2.3 JAR、`conf/graphicalmatrix`、`conf/authn`を復元してWARを再構築します。今回の更新では
DB/LDAP schemaを変更しないため、自己管理フローの導入だけを理由とするデータschemaのロールバックは
不要です。

***
***

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
- v1.2.4で自己管理フローを有効にした場合は、Password + 現在のMFA方式による再認証、変更画面への遷移、`SELF_SERVICE_AUTH`と`SELF_SERVICE_HANDOFF`の成功記録

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

v1.2.4からv1.2.3へ戻す例:

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.2.4.jar
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

v1.2.0でWebAuthn設定を変更した場合、またはv1.2.4で自己管理フロー向けのauthn設定を変更した
場合は、それらも復元する。

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

## 公式プラグイン化した場合

既存設定を維持した上書き更新は可能だが、単純に新しいファイルをコピーするだけでは不十分である。
Plugin JARはバージョンを含むファイル名で配置されるため、旧JARを削除する必要がある。

### v1.2.xからv1.2.xへのplugin.sh更新

v1.2.xを `plugin.sh` で導入済みの場合は、公開metadataを使ってv1.2.2以降へ更新できる。
対象IdPの範囲はv5.2.1以上、v5.2.4未満であり、v5.2.3を含む。Shibboleth plugin metadataの
`idpVersionMax` は上限を含まない。

```bash
IDP_HOME=/opt/shibboleth-idp
PLUGIN_ID='io.github.yasakawa.faskw.authn.graphicalmatrix'
METADATA_URL='https://raw.githubusercontent.com/y-asakawa/2faskw/main/plugin-metadata/graphicalmatrix-plugin.properties'

sudo "$IDP_HOME/bin/plugin.sh" -l

sudo "$IDP_HOME/bin/plugin.sh" \
  --updateURL "$METADATA_URL" \
  -u "$PLUGIN_ID"

sudo "$IDP_HOME/bin/plugin.sh" -l
sudo "$IDP_HOME/bin/plugin.sh" -fl
```

更新後の一覧には更新先のバージョンが表示されることを確認する。v1.2.3からv1.2.4への更新では
`Current Version: 1.2.4`が期待値となる。`plugin.sh -u` は、
署名を検証してからWARを再構築する。`--noCheck` は互換性検査を無効化するため、この更新試験では
指定しない。

```text
2faskw-idp-plugin-1.2.x.jar
```

新旧JARが同時に残ると、同じJavaクラスが複数のJARに存在してロード結果が不定になる可能性がある。
