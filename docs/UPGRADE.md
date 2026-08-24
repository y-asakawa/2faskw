# 2FAS-KW Plugin Upgrade Guide

この文書は、2FAS-KW Plugin for Shibboleth IdPを既存環境で更新するための推奨手順をまとめる。

対象:

- v1.0.0 / v1.0.1 から v1.1.0 への更新
- v1.1.0 から v1.2.0 への更新
- v1.0.x から v1.2.0 へ更新する場合の段階的な確認
- v1.2.3 から v1.2.4 への更新
- v1.2.4 から v1.2.5 への更新
- v1.2.6 から v1.2.7 への更新
- v1.2.7 から v1.3.0 への更新
- v1.3.0 から v1.3.1 への更新
- v1.3.1 から v1.3.2 への更新

別バージョンへ更新する場合は、JAR名と配布物のバージョンを読み替えること。

## バージョン別の追加確認

| 更新元 | 更新先 | 必須対応 | 任意対応 |
| --- | --- | --- | --- |
| v1.0.0 / v1.0.1 | v1.1.0 | 旧JAR削除、DB schema確認、sequence保存方式の保護、設定差分反映 | 管理API、CSV運用、logrotate設定 |
| v1.1.0 | v1.2.0 | 旧JAR削除、v1.2.0テンプレート差分反映、config check | LDAP保存、TOTP seed暗号化設定見直し、WebAuthn LDAP StorageService |
| v1.0.x | v1.2.0 | v1.1.0の必須対応を先に完了し、その後v1.2.0差分を反映 | LDAP保存へ切り替える場合は別途移行計画を作成 |
| v1.2.3 | v1.2.4 | 旧JAR削除、WAR再構築、設定検査、既存認証の回帰試験 | IdP自己管理フローの有効化、従来LDAP変更経路の停止 |
| v1.2.4 | v1.2.5 | 旧JAR削除、ロックアウト4設定の追加、WAR再構築、設定検査 | 通常・最大ロック時間の調整 |
| v1.2.6 | v1.2.7 | 旧JAR削除、WAR再構築、設定検査、既存認証の回帰試験 | Dashboard導入、SP追加管理CLIの有効化 |
| v1.2.7 | v1.3.0 | 旧JAR削除、WAR再構築、設定検査、既存認証の回帰試験 | Dashboardのv1.3.0配布物への更新、SP管理CLIの継続利用、SP別LDAP属性アクセス制御・属性カタログCLIの初期化 |
| v1.3.0 | v1.3.1 | 旧JAR削除、WAR再構築、設定検査、既存認証の回帰試験 | SP・IdP全体MFA方針CLI、LDAP Resolver属性追加CLI、大規模共有NAT向けLDAP変更画面設定 |
| v1.3.1 | v1.3.2 | 旧JAR削除、WAR再構築、設定検査、既存認証の回帰試験 | 2FAS-KW固有ログのlogrotate設定、Admin Toolsの更新 |

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

v1.2.5では、GraphicalMatrix画像列照合のロック条件を設定化し、通常ロックと最大ロックの
二段階制御を追加します。既定では5回目から15分、10回目以降は30日ロックされます。

v1.2.7では、Dashboardの別配布に加えて、IdPローカルでSP metadata、属性リリース、SP別MFA
方針を管理するCLIを本体パッケージへ追加します。SP管理CLIは既定で無効であり、更新だけで
既存のmetadata設定やSP別MFA方針を変更しません。SP管理用HTTP APIは追加されません。

v1.3.0では、SP別のIdP属性アクセス制御と属性カタログを`graphicalmatrix-sp.sh`へ追加します。
更新直後はアクセス制御が無効であり、既存SPの認証・属性releaseは変更しません。利用する環境だけ
`access init`を明示的に実行し、ContextCheck module、IdP build、Jetty再起動を行います。
v1.2.7で導入したDashboardとSP管理CLIもv1.3.0の配布物へ含まれます。既にSP管理CLIを初期化済みの
環境では`init`を再実行せず、必要な場合だけ`access init`を実行します。

v1.3.1では、SP単位の`set-mfa`に加えてIdP全体のMFA方針を管理・検証する`mfa`、LDAP属性を
Attribute Resolverへ安全に追加する`attributes resolver init/add`、LDAP変更画面の共有NAT向け
保護設定を追加します。更新だけでは既存MFA方針、Attribute Resolver、rate limit設定を変更しません。
必要な機能だけdry-runで内容を確認してから明示的にapplyします。

v1.3.2では、配布物から詳細文書を除外してトップレベル`README.md`から正本へ案内する構成へ変更します。
2FAS-KW固有ログ用のlogrotateサンプルを追加し、Admin Tools配布物にはCSVプロビジョニングログ用の
サンプルを同梱します。更新だけで既存の認証設定、DB schema、SP管理台帳、MFA方針、LDAP Resolver、
OSのlogrotate設定は変更しません。

## 事前確認

更新前に以下を確認する。

- 更新対象のIdP、Java、Jettyが新バージョンの動作条件を満たしている
- Plugin配布ZIPのchecksumまたは署名を検証している
- PostgreSQLおよびIdP設定のバックアップを取得できる
- Jettyの停止時間を確保している
- ロールバックに使用する旧Plugin JARと設定ファイルを保管している
- LDAP保存へ切り替える場合は、LDAP schema、ACL、service account、LDAPS接続を検証済み
- WebAuthnを使う場合は、FQDN、HTTPS、RP ID、ブラウザの信頼済み証明書を確認済み

現在のバージョン（Plugin JAR）を確認する。

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

# 稼働中の画像IDと画像ファイルをロールバックできるように保管する。
if sudo test -d /opt/shibboleth-idp/edit-webapp/graphicalmatrix; then
  sudo cp -a /opt/shibboleth-idp/edit-webapp/graphicalmatrix \
    /opt/shibboleth-idp/edit-webapp/graphicalmatrix.bak.$TS
fi
```

必要に応じて、以下もバックアップする。

```bash
sudo cp -a /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml.bak.$TS

sudo cp -a /opt/shibboleth-idp/credentials \
  /opt/shibboleth-idp/credentials.bak.$TS

# Shibboleth IdP本体のLDAP認証・属性Resolver接続設定。
if sudo test -f /opt/shibboleth-idp/conf/ldap.properties; then
  sudo cp -a /opt/shibboleth-idp/conf/ldap.properties \
    /opt/shibboleth-idp/conf/ldap.properties.bak.$TS
fi
```

`/opt/shibboleth-idp/conf/ldap.properties`はShibboleth IdP本体のLDAP設定、
`/opt/shibboleth-idp/conf/graphicalmatrix/ldap.properties`は2FAS-KW enrollmentをLDAPへ
保存する場合の設定であり、用途が異なる。前者は2FAS-KW Pluginの導入スクリプトでは更新しない。

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
sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --apply
```

設定ディレクトリは`root:jetty 0750`などで保護されているため、dry-runとapplyの両方で
configスクリプト自体を`sudo`から起動する。一般ユーザーでスクリプトを起動し、内部sudoだけに
依存する実行方法は使用しない。

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
sp-management.properties.idpnew.TIMESTAMP
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
`*.idpnew.TIMESTAMP`を既存ファイルへそのままコピーせず、必ず差分を確認して必要なキーだけを
反映する。

導入スクリプトが既存設定を保持したかは、最新のinstall manifestで確認できる。

```bash
MANIFEST="$(sudo find /opt/shibboleth-idp/conf/graphicalmatrix \
  -maxdepth 1 -type f -name 'install-manifest-*.tsv' \
  -printf '%T@ %p\n' | sort -nr | awk 'NR == 1 { print $2 }')"

if [[ -n "$MANIFEST" ]]; then
  echo "manifest=$MANIFEST"
  sudo grep -E \
    '(graphicalmatrix|db|ldap|sp-management|mfa-policy)\.properties' \
    "$MANIFEST"
else
  echo 'ERROR: install manifest was not found' >&2
fi
```

`install_template_deferred`は既存ファイルを保持して`*.idpnew.TIMESTAMP`を作成したことを示す。
`install_template`は、導入時に既存ファイルが存在せずテンプレートを新規作成したことを示す。

既存環境のアップグレードで、運用済みの設定ファイルに`install_template`が表示された場合は、
設定が保持された状態ではない。IdPを再起動する前に作業を止め、アップグレード前のバックアップを
確認する。複数の設定ファイルが同時に`install_template`になっている場合は、
`conf/graphicalmatrix`ディレクトリ全体がインストール時に存在しなかった可能性が高い。

```bash
# UPGRADE手順で作成した設定ディレクトリのバックアップを新しい順に表示する。
sudo find /opt/shibboleth-idp/conf -maxdepth 1 -type d \
  -name 'graphicalmatrix.bak.*' -printf '%T@ %p\n' | sort -nr

# 個別に作成された設定ファイルのバックアップも確認する。
sudo find /opt/shibboleth-idp/conf/graphicalmatrix -maxdepth 1 -type f \
  \( -name '*.bak.*' -o -name '*.rpmsave' -o -name '*.rpmnew' \) \
  -printf '%T@ %p\n' | sort -nr
```

バックアップが見つかった場合も、ディレクトリ全体を無条件に上書きして戻さない。旧設定と
新しい配布テンプレートを比較し、DB/LDAP接続先、保存方式、secretファイルのパス、MFA policy、
SP管理設定などの運用値を復元した上で、新バージョンで追加されたキーを反映する。diffには
credentialや内部ホスト名が含まれる可能性があるため、出力を外部へ貼り付けない。

```bash
# BACKUP_DIRとPACKAGE_DIRは実在するパスへ置き換える。
BACKUP_DIR='/opt/shibboleth-idp/conf/graphicalmatrix.bak.TIMESTAMP'
PACKAGE_DIR='/path/to/2faskw-idp-plugin-VERSION'

# 内容を表示せず、変更の有無だけを確認する例。
for NAME in \
  graphicalmatrix.properties \
  db.properties \
  ldap.properties \
  webauthn-ldap.properties \
  mfa-policy.properties \
  sp-management.properties; do
  if sudo test -f "$BACKUP_DIR/$NAME"; then
    sudo diff --brief \
      "$BACKUP_DIR/$NAME" \
      "/opt/shibboleth-idp/conf/graphicalmatrix/$NAME" || true
  fi
done

# 新バージョンで追加されたキーは、復元した旧設定と配布テンプレートを比較して取り込む。
sudo diff -u \
  "$BACKUP_DIR/graphicalmatrix.properties" \
  "$PACKAGE_DIR/conf/graphicalmatrix/graphicalmatrix.properties.idpnew"
```
***
***

## 以下の追加手順が必要ない場合は、設定検査から始めてください。


## v1.0.xからv1.1.0への追加手順

v1.1.0では、DB状態とsequence保存方式の安全性を上げるための移行確認が必要です。

確認する項目:

- `graphicalmatrix.sequence.storage` を本番で `plaintext` のままにしない
- `auto` または `hash` を使う場合は `graphicalmatrix.sequence.pepperFile` を作成する
- 既存の平文sequenceを保護済み形式へ移行する
- `status`、`failed_count`、`locked_until`、`force_sequence_change` などのDB状態列が期待通り存在する
- 管理APIを使う場合はtoken、許可CIDR、sequence露出制御を確認する

v1.0.xから直接v1.2.0へ更新する場合も、このv1.1.0の確認を先に完了してください。

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
自己管理画面からWebAuthnへ安全に方式変更する機能は、登録成功hookを備えた
Shibboleth WebAuthn Plugin 1.3.0以上が必要です。2FAS-KW更新後はWARを再構築し、
`postconfig.xml`が提供する`shibboleth.authn.WebAuthn.audit.AddKeyAuditSuccessHook`を読み込ませます。
独自hookで同じbean IDを上書きすると、credential登録後に2FAS-KWのMFA方式が切り替わらないため、
複合hookとして2FAS-KW hookも呼び出してください。

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

v1.2.4のMFAポリシー優先順位を利用する場合は、既存の
`/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties`へ次を追加する。
未追加でも同じ既定順序で動作するが、運用上の優先順位を明示するため追加を推奨する。

```properties
graphicalmatrix.mfa.forceSPs =
graphicalmatrix.mfa.policyOrder = forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default
```

学内・社内CIDRでは通常SPのMFAを省略し、機微なSPだけMFAを強制する例:

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.forceSPs = https://sp-sensitive.example.org/shibboleth
graphicalmatrix.mfa.bypassCIDRs = 192.168.0.0/24
graphicalmatrix.mfa.policyOrder = forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default
```

`policyOrder`は6ルールを各1回含め、`default`を最後にする。設定反映前に
`graphicalmatrix-plugin-check.sh --config-only`を実行し、`MFA policy valid`が表示されることを確認する。

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

## v1.2.4からv1.2.5への追加手順

v1.2.5では、通常ログインと変更画面のGraphicalMatrix画像列照合に、共通の二段階
ロックアウト設定を適用します。DB schemaおよびLDAP schemaの変更はありません。

`/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`へ以下を追加します。

```properties
# 5回目から通常ロック
graphicalmatrix.lockout.failureLimit = 5
graphicalmatrix.lockout.lockSeconds = 900

# 10回目から最大ロック
graphicalmatrix.lockout.maxLockFailureCount = 10
graphicalmatrix.lockout.maxLockSeconds = 2592000
```

設定を省略した場合も同じ既定値が使われますが、運用値を明確にするため既存設定ファイルへの
追記を推奨します。許容条件は以下です。

- `failureLimit`: `1`から`100`
- `lockSeconds`: `1`から`2592000`
- `maxLockFailureCount`: `failureLimit`より大きく`1000`以下
- `maxLockSeconds`: `lockSeconds`以上、`2592000`以下
- `0`による永久ロックは使用できない

`failed_count`はロック期限の経過だけでは0へ戻りません。既定値では5回目から9回目の
失敗は15分ロック、10回目以降は30日ロックです。30日経過後の最初の認証にも失敗すると、
その時点から再び30日ロックされます。正しい画像列での認証成功、または管理者による
unlock・RESET等で0へ戻ります。

設定検査を実行します。

```bash
sudo ./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

期待値:

```text
OK: [config] GraphicalMatrix lockout valid: failure_limit=5 lock_seconds=900 max_lock_failure_count=10 max_lock_seconds=2592000
result: OK
```

更新後は専用のテストユーザーで、通常ログインと変更画面の両方が同じ
`failed_count`を使用することを確認します。本番ユーザーを故意にロックしてはなりません。

```bash
sudo tail -f /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

監査detailで以下を確認します。

```text
failed_count=4,...,lock_level=none,lock_seconds=0,locked_until=0
failed_count=5,...,lock_level=normal,lock_seconds=900,locked_until=<epoch_millis>
failed_count=10,...,lock_level=maximum,lock_seconds=2592000,locked_until=<epoch_millis>
```

DB保存の場合は管理CLIでも状態を確認できます。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh show test-user
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh unlock test-user
```

LDAP保存の場合は、設定した失敗回数属性とロック期限属性をLDAP管理手順で確認・解除します。

v1.2.4へロールバックしても、DBまたはLDAPに保存済みの `failed_count` と
`locked_until` は自動的に短縮されません。v1.2.5で30日ロックされたテストユーザーは、
必要に応じて管理者がunlockしてからロールバックします。v1.2.4は新しい4設定を無視します。

## v1.2.6からv1.2.7への追加手順

v1.2.7では、本体パッケージにSP追加管理CLIを追加します。CLIはIdPサーバ上でroot権限により
実行し、SP管理用のHTTP API、管理Web UI、listener port、API tokenは追加しません。
Dashboardは従来どおり別パッケージであり、本体更新だけでは導入されません。

更新スクリプトが配置した次のテンプレートを確認します。

```bash
sudo find /opt/shibboleth-idp/conf/graphicalmatrix \
  -name 'sp-management.properties.idpnew*' \
  -type f \
  -print

sudo ls -l /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh
```

新規ファイルとして配置された場合も、既定値は次のとおりです。この状態では`status`、`list`、
`next`などの読み取りは利用できますが、`--apply`を伴う更新は拒否され、既存IdP設定は変わりません。

```properties
graphicalmatrix.sp.management.enabled = false
```

SP管理CLIを利用しない環境は、この既定値のまま通常の設定検査と認証回帰試験へ進みます。
利用する環境だけ、[INSTALL_NEW_SP.md](./INSTALL_NEW_SP.md)に従ってmetadata取得元とACSの
FQDNを許可し、管理機能を有効化します。

```properties
graphicalmatrix.sp.management.enabled = true
graphicalmatrix.sp.metadata.allowedHosts = new-sp.example.org
graphicalmatrix.sp.metadata.allowedAcsHosts = new-sp.example.org
```

初回は既存SPを変更しない`status`と`next`を確認し、管理用metadata providerと属性filter blockを
dry-runしてから適用します。

IdPが`http://localhost:80/idp`で待ち受けていない場合は、`init --apply`より前に
`/opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties`へ、実際のloopback listenerを
指定します。`/status`や`/profile`を末尾に付けず、IdP context pathまでを指定します。

```properties
graphicalmatrix.sp.reload.baseUrl = http://127.0.0.1:8080/idp
```

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init --apply
```

`init --apply`は`metadata-providers.xml`と`attribute-filter.xml`を原子的に更新し、変更前の
バックアップを`credentials/graphicalmatrix/sp-management-backups/`へ保存します。初期化後だけ
Jettyを1回再起動します。初期版v1.2.7が作成した`conf/graphicalmatrix/sp-management-revisions/`
または`sp-management-backups/`が存在する場合、更新後のCLIは`credentials/graphicalmatrix/`配下へ
自動移動します。旧配置と新配置の両方が存在する場合は、内容を確認して手動で統合してください。

初期化直後に手動で`reload-metadata.sh`を実行する必要はありません。`add --apply`、`adopt --apply`、
`update --apply`が成功時にmanaged metadata providerを自動reloadします。

初期版v1.2.7が`metadata-providers.xml`へ作成した
`id="2FAS-KWManagedSPMetadata"`は、数字で始まるためXMLの`NCName`として無効です。更新後の
`init --apply`は、この旧IDを`id="GraphicalMatrixManagedSPMetadata"`へ自動移行します。旧IDにより
`shibboleth.MetadataResolverService`の初期化がすでに失敗している場合も、移行後にJettyを再起動
してください。`reload-metadata.sh`だけでは、初期化に失敗したresolverを復旧できません。

```bash
sudo systemctl restart jetty-idp.service
sudo systemctl is-active jetty-idp.service
curl --noproxy '*' -fsSI http://127.0.0.1:8080/idp/status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next
```

### 既存の手作業登録SPをCLI管理へ移行する場合

既存SPは更新しただけでは変更されない。CLI管理へ移行しないSPは、そのまま既存の
`FilesystemMetadataProvider`設定で運用できる。`init --apply`は管理用metadata provider、
attribute filter block、管理台帳を初期化するだけであり、既存SPを自動的に移行しない。

移行するSPだけ、1件ずつ`adopt`を実行する。最初に対象SPが一意な既存ローカルSPとして検出される
ことを確認する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status \
  --entity-id 'https://existing-sp.example.org/shibboleth'
```

出力が`EXISTING_LOCAL`かつ`FilesystemMetadataProvider`であることを確認する。`MANAGED`、
`DUPLICATE`、`INVALID_METADATA`、または複数行が表示された場合は移行せず、metadata sourceの
重複やXMLエラーを先に解消する。

`FilesystemMetadataProvider`はmetadataファイルをJetty実行ユーザーが読めない場合にfail-fastで
初期化を停止し、`MetadataResolverService`全体を利用不能にする。移行前に、次の手順で
`status`出力の`metadata_file=`から実パスを自動取得する。

```bash
# 対象SPのentityIDを指定し、statusが表示するmetadata_fileを自動取得する。
ENTITY_ID='https://existing-sp.example.org/shibboleth'
METADATA_FILE="$(sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status \
  --entity-id "$ENTITY_ID" | awk -F= \
  '/^[[:space:]]*metadata_file=/{print $2; exit}')"

sudo test -f "$METADATA_FILE" || {
  echo "ERROR: status did not return an existing metadata_file for: $ENTITY_ID" >&2
  exit 1
}
printf 'metadata_file=%s\n' "$METADATA_FILE"

# jettyは標準的なJetty実行ユーザーであり、実環境で異なる場合は置き換える。
sudo chown root:jetty "$METADATA_FILE"
sudo chmod 0640 "$METADATA_FILE"
sudo restorecon -v "$METADATA_FILE"
sudo -u jetty test -r "$METADATA_FILE" && \
  echo 'OK: Jetty can read existing SP metadata'
```

読み取り検査に失敗した場合は`adopt`を実行しない。すでに`MetadataResolverService`が停止した場合は、
権限を直した後にJettyを再起動してから`status`を再確認する。

```bash
sudo systemctl restart jetty-idp.service
curl --noproxy '*' -fsSI http://127.0.0.1:8080/idp/status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
```

既存metadataをファイルから読み取るため、metadata内のACS FQDNを
`graphicalmatrix.sp.metadata.allowedAcsHosts`に追加する。`adopt`ではmetadata URLを取得しないため、
`allowedHosts`への追加は不要である。既存値は残してカンマ区切りで追記する。

```properties
graphicalmatrix.sp.metadata.allowedAcsHosts = existing-sp.example.org
```

次に、変更しないdry-runを実行する。`NAME`には管理用の一意な名前を指定する。`status`に表示された
`SOURCE`（既存のmetadata provider ID）は`NAME`ではないため、その値をそのまま指定してはいけない。
使用可能な文字は`[a-z0-9][a-z0-9-]{0,62}`であり、先頭を数字にできる。例えば、ローカル検証SPなら
`local-test-sp`、業務SPなら`library-portal`のような小文字の管理名を付ける。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh adopt existing-sp \
  --entity-id 'https://existing-sp.example.org/shibboleth'
```

表示された`source_provider`、`metadata_sha256`、`attribute_profile`、`mfa_profile`を確認する。
`attribute_profile=none`は、このSPに対してCLIが追加で属性をreleaseしないことを表す。必要な属性が
ある場合は、移行前に属性release方針を確認する。

内容が正しい場合だけ、entityIDを完全一致で指定して適用する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh adopt existing-sp \
  --entity-id 'https://existing-sp.example.org/shibboleth' \
  --apply \
  --confirm 'https://existing-sp.example.org/shibboleth'

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify existing-sp
```

`adopt`は旧providerと旧metadataファイルを管理用metadata providerと管理台帳へ移行し、移行前の
状態をrevisionとして保存する。SP自体のentityID、証明書、ACSを変更する操作ではない。移行後は
対象SPから実際にログインし、属性releaseとMFA方針が従来どおりであることを確認する。必要なら
`restore-legacy`で手作業登録状態へ戻せる。

新規SPの追加は必ずdry-runを先に行い、表示されたentityID、ACS、証明書fingerprint、metadataの
SHA-256をSP管理者から得た値と照合します。一致したdigestを`--approve-sha256`へ指定した場合だけ
`--apply`します。詳しい追加、更新、無効化、adopt、rollback、restore-legacy手順は
[v1.3.0統合リリースノート](./release-notes/v1.3.0-RELEASE-NOTES.md)および
[v1.3.0 SP別LDAP属性アクセス制御・属性カタログCLI設計](./release-notes/v1.3.0-SP-ACCESS-ATTRIBUTE-CATALOG-DESIGN.md)を参照してください。

SP管理CLIを無効へ戻す場合は、まずCLI管理SPの状態と復元要否を確認してから、設定を
`false`へ戻します。`false`への変更だけでは、既に配置したmetadataや属性・MFA方針は削除されません。

## v1.2.7からv1.3.0への統合更新手順

v1.3.0はDashboard、SP管理CLI、SP別LDAP属性アクセス制御、属性カタログCLIを含む統合リリースである。
v1.2.7でDashboardを導入している場合は、`2faskw-dashboard-1.3.0.zip`へ更新する。SP管理CLIを
初期化済みの場合は、既存のmanaged registry、metadata、属性release、MFA方針を維持するため、
`init`を再実行しない。

v1.3.0のインストールだけでは、SP別属性アクセス制御は有効にならない。既存の
`sp-management.properties`へ次の新規設定を反映する。既定値のままなら既存認証への影響はない。

```properties
graphicalmatrix.sp.access.enabled = false
graphicalmatrix.sp.access.reloadIntervalSeconds = 5
graphicalmatrix.sp.access.auditDecisions = true
# Jetty実行アカウントのprimary group。標準構成はjetty。
graphicalmatrix.sp.runtimeGroup = jetty
graphicalmatrix.sp.attributes.blocked =
```

`graphicalmatrix.sp.runtimeGroup`は、IdP実行アカウントのprimary groupへ設定する。標準構成は
`jetty`だが、異なる環境では次で確認して置き換える。

```bash
#コマンドで確認
sudo systemctl show jetty-idp.service -p User -p Group

User=jetty
Group=jetty

#設定ファイルを確認
sudo grep -n '^graphicalmatrix\.sp\.runtimeGroup' \
  /opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties

#空であればsp-management.propertiesに以下を追加
graphicalmatrix.sp.runtimeGroup = jetty
```

機能を利用する場合は、まずSP管理CLIが初期化済みで、対象SPが`MANAGED`であることを確認する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init
```

dry-runに`CONTEXT_CHECK_CONFLICT`が出た場合は適用しない。既存の
`shibboleth.context-check.Function`または`Condition`と2FAS-KW判定を手作業で統合する必要がある。
競合がなければ適用し、表示された順にbuildと再起動を行う。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init --apply
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service

until curl --noproxy '*' -fsSI --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/idp/status >/dev/null 2>&1; do
  echo 'Waiting for Jetty...'
  sleep 2
done

#展開したv1.3配布物のディレクトリから実行してください。
sudo ./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only

#
#エラーが出た場合
#
#よくあるエラーの修正
#

# ERROR: graphicalmatrix.sp.management.enabled must be true before applying SP governance changes

# graphicalmatrix.sp.management.enabled = falseになっているので、trueに変更。
# managementの設定を行っていない場合は、v1.2.6からv1.2.7への追加手順を行いmanagementnの設定を有効にする。


# FAIL: [config] SP managed metadata directory runtime access invalid: IllegalStateException: SP managed metadata directory is not readable by configured runtime group jetty: /opt/shibboleth-idp/metadata/2faskwsp

DIR='/opt/shibboleth-idp/metadata/2faskwsp'

sudo install -d -m 0750 -o root -g jetty "$DIR"

# 配下のmetadata XMLはJettyが読むだけで、管理CLIだけが更新する。
sudo find "$DIR" -type d -exec chown root:jetty {} \; -exec chmod 0750 {} \;
sudo find "$DIR" -type f -exec chown root:jetty {} \; -exec chmod 0640 {} \;

sudo restorecon -RFv "$DIR"

# Jetty実行ユーザーから読取り・通過できることを確認する。
sudo -u jetty test -r "$DIR" && \
sudo -u jetty test -x "$DIR" && \
echo 'OK: Jetty can access the managed SP metadata directory'
```

`access init --apply`は、`/opt/shibboleth-idp/conf/graphicalmatrix`を`0750`、
`access-policy.json`、`attribute-catalog.json`、`sp-management-registry.json`を
`root:<runtimeGroup>`かつ`0640`へ設定する。旧版のJSONを手動で復元した場合は、次で同じ状態へ戻す。

```bash
sudo chown root:jetty /opt/shibboleth-idp/conf/graphicalmatrix
sudo chmod 0750 /opt/shibboleth-idp/conf/graphicalmatrix
sudo chown root:jetty \
  /opt/shibboleth-idp/conf/graphicalmatrix/access-policy.json \
  /opt/shibboleth-idp/conf/graphicalmatrix/attribute-catalog.json \
  /opt/shibboleth-idp/conf/graphicalmatrix/sp-management-registry.json
sudo chmod 0640 \
  /opt/shibboleth-idp/conf/graphicalmatrix/access-policy.json \
  /opt/shibboleth-idp/conf/graphicalmatrix/attribute-catalog.json \
  /opt/shibboleth-idp/conf/graphicalmatrix/sp-management-registry.json
sudo restorecon -Rv /opt/shibboleth-idp/conf/graphicalmatrix
```

上の`jetty`は`graphicalmatrix.sp.runtimeGroup`と同じ値へ読み替える。設定検査でruntime groupの
読み取りエラーが出た場合は、この所有者・mode・SELinux contextを確認する。

属性候補を確認し、SPへ送信しないIdP内部判定用属性としてbusinessCategoryを承認する例を示す。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes approve \
  businessCategory \
  --usage access \
  --classification internal \
  --purpose 'IdP-side SP authorization'

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes approve \
  businessCategory \
  --usage access \
  --classification internal \
  --purpose 'IdP-side SP authorization' \
  --apply --confirm businessCategory

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes show businessCategory
```

対象SPを調べておく
```
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list
```
対象SPに`businessCategory=AA`を要求する場合は、dry-run後にentityIDを確認値として適用する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set \
  2faskwlocaltest --allow 'businessCategory=AA'

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set \
  2faskwlocaltest --allow 'businessCategory=AA' \
  --apply --confirm 'https://sp.example.org/shibboleth'

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access show 2faskwlocaltest
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next 2faskwlocaltest
```

policy変更はJSONの再読込で反映され、通常はJetty再起動を必要としない。初回の`access init`、
ContextCheck設定変更、Plugin JAR更新時だけbuildと再起動を行う。詳細は
[v1.3.0 SPアクセス制御・属性カタログ設計](./release-notes/v1.3.0-SP-ACCESS-ATTRIBUTE-CATALOG-DESIGN.md)
を参照する。

## v1.3.0からv1.3.1への更新手順

v1.3.1は、v1.3.0の認証、SP管理台帳、SP metadata、属性release、SP別アクセス制御、MFA方針を
引き継ぐ。更新だけでは、既存SPのMFA profile、IdP全体のMFA方針、Attribute Resolver、
LDAP変更画面のrate limit設定を変更しない。DB schemaの追加migrationも不要である。

v1.3.1で追加または拡張する主な管理機能は次のとおりである。

| 機能 | コマンド | 更新直後の動作 |
| --- | --- | --- |
| SP単位MFA方針 | `set-mfa` | 既存profileを維持する。明示的にapplyしたSPだけ変更する。 |
| IdP全体MFA方針 | `mfa show/test/global set/reconcile` | 既存の全体設定を維持する。参照コマンドだけでは変更しない。 |
| LDAP Resolver属性追加 | `attributes resolver init/add` | `attribute-resolver.xml`を変更しない。明示的にapplyした場合だけ変更する。 |
| LDAP変更画面のNAT除外 | properties設定 | 既定では無効であり、既存rate limitを維持する。 |

### 事前バックアップ

この文書の共通バックアップに加え、v1.3.1の任意機能を使用する場合はMFA方針、SP管理台帳、
Attribute Resolverを個別に復元できるよう保管する。

```bash
TS=$(date +%Y%m%d%H%M%S)

sudo cp -a \
  /opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties \
  /opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties.bak.$TS

if sudo test -f \
  /opt/shibboleth-idp/conf/graphicalmatrix/sp-management-registry.json; then
  sudo cp -a \
    /opt/shibboleth-idp/conf/graphicalmatrix/sp-management-registry.json \
    /opt/shibboleth-idp/conf/graphicalmatrix/sp-management-registry.json.bak.$TS
fi

sudo cp -a \
  /opt/shibboleth-idp/conf/attribute-resolver.xml \
  /opt/shibboleth-idp/conf/attribute-resolver.xml.bak.$TS
```

backupにはMFA方針や内部ホスト情報が含まれるため、root以外へ不要な読取り権限を与えない。

### Plugin JARをv1.3.1へ更新する

先に本書の「配布物の事前検査」「Pluginファイルの更新」「既存設定ファイルの更新」を実施する。
`graphicalmatrix-plugin-config.sh --apply`は既存設定を直接上書きせず、新テンプレートを
`*.idpnew.TIMESTAMP`として配置する。既存のDB、LDAP、MFA、SP管理設定へ新テンプレートを
そのまま上書きしない。

v1.3.1 JARを配置した後、旧v1.3.0 JARだけを削除する。

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.3.0.jar

sudo find /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '2faskw-idp-plugin-*.jar' -print
```

期待値は次の1ファイルだけである。

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.3.1.jar
```

新旧JARが同時に残っている状態でWARを再構築しない。`*.idpnew.TIMESTAMP`とinstall manifestを
確認し、既存設定が`install_template_deferred`として保持されたことを確認する。

### SP管理CLIを使用している環境の確認

SP管理CLIを初期化していない環境は、v1.3.1への更新だけを理由に`init --apply`を実行しない。
既にSP管理CLIを使用している環境だけ、管理台帳とMFA方針の整合性を確認する。

```bash
# SP管理CLIの有効設定を確認する。
sudo grep -n '^graphicalmatrix\.sp\.management\.enabled' \
  /opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties

# CLI管理SPと現在のMFA profileを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list

# IdP全体設定、SP別設定、手作業差分の有無を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show
```

`managed_policy_drift=OK`であれば、移行操作は不要である。`MISMATCH`の場合はapplyを続行せず、
更新前backup、SP管理台帳、`mfa-policy.properties`の差分を確認する。台帳を正として戻す場合だけ、
次のdry-runとplan SHA-256承認を使用する。

```bash
# 台帳から復元する内容とplan_sha256を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  mfa reconcile --from-registry

# 内容確認後、表示されたplan_sha256を指定して適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  mfa reconcile --from-registry \
  --approve-sha256 PLAN_SHA256 \
  --apply
```

`reconcile`は手作業値を台帳へ取り込むコマンドではない。CLI管理SPに対応するMFA方針を台帳から
再生成する。手作業差分を維持したい場合は適用せず、正しいprofileを確認してから`set-mfa`で
SPごとに設定し直す。

代表的なCLI管理SPについて、CIDR内外の送信元IPで実効判定を確認する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp SP_NAME --ip 192.0.2.10

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp SP_NAME --ip 203.0.113.10
```

更新確認だけで`set-mfa`または`mfa global set`を実行する必要はない。MFA方針を実際に変更する
場合は、先にdry-runを実行し、[v1.3.1 SP・IdP全体MFA方針管理CLI](./release-notes/v1.3.1-SP-MFA-POLICY-CLI.md)
の確認・承認手順に従う。

### 任意: LDAP Resolver属性追加CLIを使用する

LDAP上に存在するが`attributes discover --sp SP_NAME --user USER`へ表示されない属性を、
SP別アクセス制御または属性releaseに利用する場合だけ実施する。更新確認だけなら、この項目はスキップする。
applyにはSP管理CLIが初期化済みで、`graphicalmatrix.sp.management.enabled = true`であることが必要である。
未初期化の環境では、この機能だけを使う前にSP管理CLIの導入・権限設定を完了する。

```bash
# 対象ユーザーについて、現在IdPが解決できる属性を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover \
  --sp SP_NAME --user USER

# LDAP DataConnectorの追加要否をdry-runで確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver init
```

既存のLDAP DataConnectorが1件ある場合は`NO_CHANGE`となり、そのDataConnectorを利用できる。
LDAP DataConnectorがない場合だけ、表示内容を確認して初期化する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver init \
  --data-connector graphicalmatrixLdap \
  --apply --confirm graphicalmatrixLdap
```

対象属性を追加する場合も、先にdry-runを行う。

```bash
# 追加予定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver add businessCategory \
  --data-connector graphicalmatrixLdap

# 内容を確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver add businessCategory \
  --data-connector graphicalmatrixLdap \
  --apply --confirm businessCategory
```

Resolver変更は実行中IdPへ即時反映しない。この後の共通手順でWARを再構築してJettyを再起動し、
同じ`attributes discover`で対象属性が`runtime-observed`になることを確認する。LDAP ACL/ACI、
base DN、検索filter、service accountのread権限はこのCLIでは変更しない。詳細は
[v1.3.1 LDAP Resolver属性追加CLI](./release-notes/v1.3.1-LDAP-RESOLVER-ATTRIBUTE-CLI.md)を参照する。

### 任意: LDAP変更画面の共有NAT設定

既定動作を維持する場合、設定追加は不要である。大規模な共有NATから従来LDAP変更画面を
一斉利用し、IP全体の失敗上限による巻き添えを避ける場合だけ、稼働中の
`/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`へ信頼済みCIDRを追加する。

```properties
graphicalmatrix.change.ldapRateLimit.key = ip-user
graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs = 192.168.0.0/16,10.0.0.0/8
```

この設定は独立IP全体制限だけを除外する。利用者ごとのキー別制限は残る。`key=ip`のままでは
キー別制限も共有IP単位になるため、共有NAT用途では`user`または`ip-user`を使用する。
CIDRは実際の監査ログの`ip`とネットワーク管理情報を照合し、必要最小限にする。

設定値を検査する。

```bash
# 展開したv1.3.1配布物のディレクトリから実行する。
sudo ./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

設定ファイルはLDAP変更画面の各リクエストで再読込されるため、CIDR変更だけなら通常は
Jetty再起動を必要としない。Plugin JARをv1.3.1へ更新した場合は、通常の更新手順どおり
WAR再構築とJetty再起動を行う。

### v1.3.1更新後の確認

この後の共通手順に従い、設定検査、WAR再構築、Jetty起動、既存認証の回帰試験を行う。
SP管理CLIを使用している場合は、起動後に次も確認する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list

sudo grep -E 'MFA (policy decision|method decision)' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 30
```

最低限、次を確認する。

- `managed_policy_drift=OK`である。
- 更新前と同じSPが同じMFA profileで表示される。
- `force`のSPは、既定順序では全体CIDR例外にかかわらずMFA必須となる。
- `inherit`のSPは、IdP全体設定と送信元IPに従って判定される。
- LDAP Resolverを変更した場合は、対象属性が`runtime-observed`になる。
- LDAP変更画面のCIDR除外を設定した場合も、利用者単位のrate limitが維持される。

### v1.3.1固有変更のロールバック

JARをv1.3.0へ戻す場合は、後述の共通ロールバック手順に従う。さらに、v1.3.1導入後に
MFA方針またはAttribute Resolverをapplyしていた場合だけ、事前backupから対応ファイルを復元する。

```bash
# TIMESTAMPは事前バックアップで作成した値へ置き換える。
sudo cp -a \
  /opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties.bak.TIMESTAMP \
  /opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties

sudo cp -a \
  /opt/shibboleth-idp/conf/attribute-resolver.xml.bak.TIMESTAMP \
  /opt/shibboleth-idp/conf/attribute-resolver.xml
```

SP管理台帳は、v1.3.1で`set-mfa`をapplyして台帳自体を変更した場合だけ、対応するbackupを
復元する。MFA方針だけを旧状態へ戻して台帳を新状態のまま残すとdriftになるため、両者のrevisionを
合わせる。復元後は所有者、group、mode、SELinux contextを確認し、WARを再構築してJettyを起動する。

## v1.3.1からv1.3.2への更新手順

v1.3.2はv1.3.1の認証、DB、SP管理、属性カタログ、アクセス制御、MFA方針をそのまま引き継ぐ。
DB schema migrationは不要であり、更新だけで既存の`mfa-policy.properties`、
`attribute-resolver.xml`、`sp-management-registry.json`、OSのlogrotate設定を変更しない。

### Plugin JARをv1.3.2へ更新する

本書の共通手順「配布物の事前検査」「Pluginファイルの更新」「既存設定ファイルの更新」を実施する。
`graphicalmatrix-plugin-config.sh --apply`は既存設定を直接上書きせず、新テンプレートを
`*.idpnew.TIMESTAMP`として配置する。v1.3.1の設定ファイルを新テンプレートで置換してはならない。

v1.3.2 JARを配置した後、旧v1.3.1 JARだけを削除する。

```bash
sudo rm -f \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.3.1.jar

sudo find /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '2faskw-idp-plugin-*.jar' -print
```

期待値:

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.3.2.jar
```

新旧Plugin JARが同時に残っている状態でWARを再構築しない。

### 任意: 2FAS-KW固有ログのlogrotate設定

v1.3.2配布物の`examples/logrotate/`には、以下の2FAS-KW固有ログ用サンプルが含まれる。
使用している機能に対応するファイルだけをOSの`/etc/logrotate.d/`へ配置する。

```text
graphicalmatrix-audit
graphicalmatrix-sp-management-audit
graphicalmatrix-access-audit
graphicalmatrix-csv-import
```

```bash
# GraphicalMatrix認証監査ログ。
sudo install -m 0644 examples/logrotate/graphicalmatrix-audit \
  /etc/logrotate.d/graphicalmatrix-audit

# SP管理CLIを使用する場合。
sudo install -m 0644 examples/logrotate/graphicalmatrix-sp-management-audit \
  /etc/logrotate.d/graphicalmatrix-sp-management-audit

# SP別アクセス制御のdecision監査を使用する場合。
sudo install -m 0644 examples/logrotate/graphicalmatrix-access-audit \
  /etc/logrotate.d/graphicalmatrix-access-audit

# Admin ToolsのCSVプロビジョニングを使用する場合。
sudo install -m 0644 examples/logrotate/graphicalmatrix-csv-import \
  /etc/logrotate.d/graphicalmatrix-csv-import

sudo logrotate -d /etc/logrotate.d/graphicalmatrix-*
```

`idp-process.log`、`idp-warn.log`、`idp-audit.log`などのShibboleth IdP標準ログは、
`/opt/shibboleth-idp/conf/logback.xml`のLogback設定が日次ローテーションと既定180世代の保持を
管理する。これらへ上記のlogrotateサンプルを追加適用してはならない。詳細は
[LOGROTATE.md](./LOGROTATE.md)を参照する。

### 任意: Admin Toolsをv1.3.2へ更新する

Admin Toolsを導入済みでCSVプロビジョニングを使用する場合だけ、v1.3.2の
`2faskw-admin-tools-1.3.2.zip`を展開し、配布物の`README.md`に記載された手順で更新する。
Admin Tools更新はIdP Plugin JAR、Jetty、IdP設定を変更しない。

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
