# 2FAS-KW Plugin Upgrade Guide

この文書は、2FAS-KW Plugin for Shibboleth IdPを既存環境で更新するための推奨手順をまとめる。

以下では、v1.0.1からv1.1.0への更新を具体例として使用する。
別バージョンへ更新する場合は、JAR名と配布物のバージョンを読み替えること。

## 更新方針

既存設定を維持した上書き更新は可能だが、単純に新しいファイルをコピーするだけでは不十分である。
Plugin JARはバージョンを含むファイル名で配置されるため、旧JARを削除する必要がある。

```text
2faskw-idp-plugin-1.0.1.jar
2faskw-idp-plugin-1.1.0.jar
```

新旧JARが同時に残ると、同じJavaクラスが複数のJARに存在してロード結果が不定になる可能性がある。

v1.1.0ではDB状態とsequence保存方式のセキュリティmigrationが必要です。
通常の更新手順を実行する前に、`docs/SECURITY-UPGRADE-1.1.0.md` を確認してください。

## 事前確認

更新前に以下を確認する。

- 更新対象のIdP、Java、Jettyが新バージョンの動作条件を満たしている
- Plugin配布ZIPのchecksumまたは署名を検証している
- PostgreSQLおよびIdP設定のバックアップを取得できる
- Jettyの停止時間を確保している
- ロールバックに使用する旧Plugin JARと設定ファイルを保管している

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

DBバックアップは、環境のPostgreSQLバックアップ手順に従って取得する。

## 配布物の事前検査

展開した新バージョンの配布物ディレクトリで、package checkを実行する。

```bash
./bin/graphicalmatrix-plugin-check.sh --package-only
```

期待値:

```text
result: OK
```

導入内容をdry-run確認する。

```bash
./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp
```

dry-runで意図しない削除や配置先が表示された場合は、`--apply`を実行しない。

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

新しいPlugin JARだけが残っていることを確認する。

```bash
ls -l /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar
```

v1.1.0への更新では、次の1ファイルだけが表示される状態にする。

```text
2faskw-idp-plugin-1.1.0.jar
```

`core-*.jar`、`HikariCP-*.jar`、`postgresql-*.jar`などの依存JARは、配布物のバージョンとIdP環境の互換性を確認して更新する。
旧依存JARを削除する場合は、他のPluginが同じJARを使用していないことを確認する。

## 既存設定ファイルの更新

導入スクリプトは、既存の `graphicalmatrix.properties`、HTML、CSSを直接上書きしない。
新しいテンプレートは、次のような名前で配置される。

```text
graphicalmatrix.properties.idpnew.TIMESTAMP
db.properties.idpnew.TIMESTAMP
api.properties.idpnew.TIMESTAMP
mfa-policy.properties.idpnew.TIMESTAMP
views/*.html.idpnew.TIMESTAMP
graphicalmatrix.css.idpnew.TIMESTAMP
```

既存ファイルと新しいテンプレートを比較する。

```bash
sudo find /opt/shibboleth-idp/conf/graphicalmatrix \
  -name '*.idpnew*' \
  -type f \
  -print
```

例:

```bash
sudo diff -u \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties.idpnew.TIMESTAMP
```

新バージョンで追加または変更された項目だけを既存設定へ反映する。
DBのユーザーデータは、Pluginの上書き更新だけでは削除または初期化されない。

secret、DBパスワード、API token、秘密鍵を新しいテンプレートで上書きしないこと。

## 設定検査

ユーザーが認証を開始する前に設定検査を実行する。

```bash
./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

本番環境でWARNも失敗として扱う場合:

```bash
./bin/graphicalmatrix-plugin-check.sh \
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

WARを再構築してJettyを起動する。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl start jetty-idp.service
```

旧バージョンで認証試験とログ確認を行う。

## リリースブランチでの推奨試験

正式リリース前は、リリース試験用ブランチで以下を完了してから `main` へマージする。

1. 配布ZIPを作成する
2. 新規インストールを試験する
3. 直前バージョンからの更新を試験する
4. ロールバックを試験する
5. GraphicalMatrix、TOTP、WebAuthnの利用方式を試験する
6. package checkとconfig checkを実行する
7. 問題を修正して再試験する
8. 試験完了後にPull Requestを `main` へマージする
9. リリースタグを作成する
