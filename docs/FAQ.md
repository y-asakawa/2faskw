# 2FAS-KW FAQ

2FAS-KW Plugin for Shibboleth IdP の設定、認証、運用で発生しやすい事象をまとめる。

## `graphicalmatrix.properties` の読込エラーはどのような場合に発生するか

現在の実装では、主に以下の場合に `graphicalmatrix.properties` の読込エラーが発生する。

### 数値設定が不正

以下の設定はエラーになる。

- `graphicalmatrix.columns` または `graphicalmatrix.rows` が `0` 以下
- `graphicalmatrix.choice` が `0` 以下
- `graphicalmatrix.order` が `1` または `2` 以外
- `graphicalmatrix.challenge.seconds` が `30` から `900` の範囲外
- `graphicalmatrix.view.css.cacheSeconds` が負数
- 数値項目に数値以外の文字列を指定

エラーになる設定例:

```properties
graphicalmatrix.columns = five
graphicalmatrix.order = 3
graphicalmatrix.challenge.seconds = 10
```

### 画像数とマトリクスのセル数が一致しない

有効画像数は、必ず以下と一致させる。

```text
有効画像数 = graphicalmatrix.columns * graphicalmatrix.rows
```

次の設定では、画面のセル数が `1 * 5 = 5` であるのに対して、有効画像数が25個あるためエラーになる。

```properties
graphicalmatrix.columns = 1
graphicalmatrix.rows = 5
graphicalmatrix.graphicals = img01-25
```

想定されるエラー:

```text
GraphicalMatrix graphical count must match columns * rows.
graphicals=25, cells=5
```

`graphicalmatrix.not_graphicals` で除外した後の画像数も、有効画像数として判定される。

次の設定では、25個から `img25` が除外されて24個になるため、`5 * 5 = 25` と一致せずエラーになる。

```properties
graphicalmatrix.columns = 5
graphicalmatrix.rows = 5
graphicalmatrix.graphicals = img01-25
graphicalmatrix.not_graphicals = img25
```

### 選択数が有効画像数を超えている

重複選択を禁止している場合、`graphicalmatrix.choice` は有効画像数以下にする。

次の設定では、5個の画像に対して6個の選択を要求するためエラーになる。

```properties
graphicalmatrix.columns = 1
graphicalmatrix.rows = 5
graphicalmatrix.graphicals = img01-05
graphicalmatrix.choice = 6
graphicalmatrix.allow_duplicates = 0
```

### 画像範囲の書式が不正

以下のような範囲指定はエラーになる。

```properties
# 開始番号が終了番号より大きい
graphicalmatrix.graphicals = img25-01

# 開始と終了の接頭辞が一致しない
graphicalmatrix.graphicals = img01-photo25

# 数字を含まない範囲
graphicalmatrix.graphicals = imgA-imgZ
```

範囲指定には、次のように同じ接頭辞と数値を使用する。

```properties
graphicalmatrix.graphicals = img01-25
```

### エイリアス設定が不正

以下の設定はエラーになる。

```properties
# 区切りの「:」がない
graphicalmatrix.aliases = A-img01

# 対応する画像IDがない
graphicalmatrix.aliases = A:

# 有効画像に存在しない画像IDを参照している
graphicalmatrix.aliases = A:img99
```

エイリアスは、次の形式で有効画像に対応付ける。

```properties
graphicalmatrix.aliases = A:img01,B:img02,C:img03
```

### LDAP変更画面のレート制限設定が不正

`graphicalmatrix.change.ldapRateLimit.enabled = true` の場合、以下の条件を満たす必要がある。

- `graphicalmatrix.change.ldapRateLimit.failureLimit` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.windowSeconds` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.lockSeconds` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.key` は `ip`、`user`、`ip-user` のいずれか

設定例:

```properties
graphicalmatrix.change.ldapRateLimit.enabled = true
graphicalmatrix.change.ldapRateLimit.failureLimit = 5
graphicalmatrix.change.ldapRateLimit.windowSeconds = 300
graphicalmatrix.change.ldapRateLimit.lockSeconds = 900
graphicalmatrix.change.ldapRateLimit.key = ip-user
```

### プロパティファイル自体を読み込めない

以下の場合も設定読込に失敗する可能性がある。

- IdP実行ユーザーにプロパティファイルの読取権限がない
- Java Propertiesとして不正なエスケープがある
- 設定したパス文字列がOS上で不正
- ファイルの読込中にI/Oエラーが発生した

設定ファイルが存在しない場合は、現在の実装ではエラーではなく既定値が使用される。

## 設定変更はいつ反映されるか

`graphicalmatrix.properties` は認証画面や変更画面などへのリクエスト時に読み込まれる。
設定を保存した瞬間に進行中の処理が切り替わるのではなく、保存後の次回アクセスから新しい設定が使用される。

- IdPの再起動は通常不要
- 新しく開始した認証や変更画面では新しい設定を使用
- 進行中の認証セッションには、開始時の設定が残る場合がある
- `graphicalmatrix-db.sh` は次回実行時に新しい設定を使用
- DB内の既存 `sequence` と `initial_sequence` は自動変換されない

`graphicalmatrix.choice` を変更した場合は、既存ユーザーのsequence数も新しい設定に合わせる必要がある。

## 画面のHTMLやCSSを編集するにはどうすればよいか

GraphicalMatrixプラグインの画面テンプレートとCSSは、通常、IdP本体の `views` ではなく次の場所に配置される。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/views
/opt/shibboleth-idp/conf/graphicalmatrix/assets
```

IdP本体の `/opt/shibboleth-idp/views` には `login.vm` や `totp.vm` などのShibboleth IdP本体またはIdPプラグイン用テンプレートが配置される。GraphicalMatrixの認証画面や変更画面を編集する場合は、原則として `/opt/shibboleth-idp/conf/graphicalmatrix/views` を編集する。

主な編集対象は次のとおり。

| 目的 | ファイル |
|---|---|
| GraphicalMatrix認証画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/graphicalmatrix.html` |
| 変更開始画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-start.html` |
| 現在のGraphicalMatrix確認画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-current.html` |
| MFA方式変更メニュー | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-menu.html` |
| 新しいGraphicalMatrix選択画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-new.html` |
| MFA方式選択画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-method.html` |
| 変更完了画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-complete.html` |
| 2FAS-KW側のTOTP QR登録画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/totp-register.html` |
| GraphicalMatrix画面CSS | `/opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css` |

設定ファイルでは、次の項目で参照先を確認できる。

```bash
sudo grep -E 'graphicalmatrix.view.template|graphicalmatrix.view.change|graphicalmatrix.view.css' \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
```

HTMLテンプレートとCSSだけを編集する場合は、通常、WAR再構築とJetty再起動は不要である。保存後、ブラウザを再読み込みして確認する。CSSのキャッシュを避けたい検証環境では、`graphicalmatrix.view.css.cacheSeconds = 0` にしておくと確認しやすい。

設定検査を行う場合は、次のコマンドを実行する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

一方で、選択済み画像リストの動的表示、ボタン押下時のJavaScript生成、Servlet処理など、Javaクラス側で生成している画面要素を変更した場合は、Plugin JARの更新、WAR再構築、Jetty再起動が必要になる。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

### TOTPとWebAuthnの画面を編集する場合

TOTPとWebAuthnは、どの画面を編集したいかで編集場所が異なる。

2FAS-KWが表示するTOTP QR登録画面は、GraphicalMatrix側の外部HTMLテンプレートである。MFA方式をTOTPへ変更した後、初回登録時に表示されるQRコード登録画面を変更する場合は、次のファイルを編集する。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/views/totp-register.html
```

このテンプレートは `/opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css` を使う。通常、WAR再構築とJetty再起動は不要で、保存後の次回表示から反映される。

一方、Shibboleth TOTP Plugin自体が表示するTOTP認証画面は、IdP本体側のVelocityテンプレートである。通常は次のファイルを編集する。

```text
/opt/shibboleth-idp/views/totp.vm
/opt/shibboleth-idp/views/totp-error.vm
```

WebAuthn Pluginの登録画面や認証画面は、Shibboleth WebAuthn Plugin側のテンプレートを編集する。配置は導入済みプラグインのバージョンやインストール方法で変わる可能性があるため、まず実ファイルを確認する。

```bash
sudo find /opt/shibboleth-idp/views -iname '*webauthn*' -print
```

例:

```text
/opt/shibboleth-idp/views/webauthn
```

`/opt/shibboleth-idp/views/*.vm` や `/opt/shibboleth-idp/views/webauthn/*` を編集した場合は、GraphicalMatrixの外部HTMLとは異なり、Jetty再起動を推奨する。

```bash
sudo systemctl restart jetty-idp.service
```

通常、Velocityテンプレートだけを編集した場合はWAR再構築までは不要である。ただし、Plugin JAR、Servlet、IdP認証フロー、`web.xml`、依存ライブラリを変更した場合は、WAR再構築とJetty再起動を行う。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

## Jettyの再起動が必要になるのはどのような場合か

変更対象がJavaクラス、Servlet登録、IdP認証フロー、JVM設定に関係する場合は、原則としてJettyを再起動する。

### Jetty再起動が必要な変更

- Plugin JARの追加、更新、削除
- `edit-webapp/WEB-INF/lib` 配下の依存JAR変更
- `web.xml` のServlet、Filter、URLマッピング変更
- TOTP、WebAuthnなどIdPプラグインの追加または更新
- Java、Jetty、Shibboleth IdPの更新
- JVMオプション、環境変数、systemd unitの変更
- JVMが読み込むtruststoreまたはkeystoreの変更

Plugin JARや `web.xml` を変更した場合は、通常、先にIdPのWARを再構築してからJettyを再起動する。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

### Jetty再起動を推奨する変更

- `mfa-authn-config.xml` などSpringまたはIdP認証フロー設定の変更
- MFA方式のBeanや認証フロー定義の変更
- IdPモジュールの有効化または無効化
- JettyまたはIdPで使用するTLS証明書や秘密鍵の変更
- 設定キャッシュやDB接続プールを確実に初期化したい場合

### 通常はJetty再起動が不要な変更

- `graphicalmatrix.properties`
- `db.properties`
- `api.properties`
- `mfa-policy.properties`
- `views/*.html`
- `assets/graphicalmatrix.css`
- GraphicalMatrix画像ファイル
- DB内のユーザー情報

これらは基本的に保存後の次回リクエストまたは次回コマンド実行時に再読込される。
`db.properties` の接続先や接続プール設定を変更した場合は、設定変更を検出した時点で既存プールを閉じ、新しい設定で接続プールを作成する。

設定ファイルを変更した後は、ユーザーが認証を開始する前に設定検査を実行する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

判断基準は次のとおり。

```text
Javaクラス、Servlet登録、IdP認証フロー、JVM設定を変更:
  build.shおよびJetty再起動を検討する

外部HTML、CSS、画像、GraphicalMatrixプロパティ、DBデータを変更:
  通常はJetty再起動不要
```

## v1.0.1からv1.1.0へは上書きインストールできるか

既存設定を維持した上書き更新は可能だが、旧バージョンJARの削除、設定差分の確認、
WAR再構築、Jetty再起動が必要である。

詳細な更新、動作試験、ロールバック手順は
[UPGRADE.md](./UPGRADE.md) を参照する。

## 設定読込エラーはどこに記録されるか

認証画面で設定読込エラーが発生した場合、ユーザーにはIdPの一般エラー画面が表示される可能性がある。
例外の詳細は、主にJettyのjournalまたはIdPプロセスログで確認する。

```bash
sudo journalctl -u jetty-idp.service -n 200 --no-pager
```

```bash
sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log
```

直近10分の設定関連エラーを検索する例:

```bash
sudo journalctl -u jetty-idp.service --since "10 minutes ago" \
  | grep -iE 'GraphicalMatrix|IllegalArgumentException|ServletException'
```

設定読込はGraphicalMatrix監査ログの記録処理より前に失敗する場合があるため、次の監査ログには記録されない可能性がある。

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

## ユーザーアクセス前に設定エラーを確認する方法

次のコマンドで、DBへ接続せずに `graphicalmatrix.properties` と参照先ファイルを検査できる。

```bash
/opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

主な検査対象:

- Runtimeと同じJavaローダーによる数値、画像数、選択数、エイリアス
- `graphicalmatrix.place` 配下の有効画像ファイル
- CSSとHTMLテンプレート
- sequence保存方式と必要なpepper、keyword、AES key
- TOTP seed保存方式

正常例:

```text
summary: package_failures=0 package_warnings=0 idp_failures=0 idp_warnings=0 config_failures=0 config_warnings=0 strict=0
result: OK
```

設定不正例:

```text
FAIL: [config] runtime configuration invalid: IllegalArgumentException: GraphicalMatrix graphical count must match columns * rows. graphicals=25, cells=5

summary: package_failures=0 package_warnings=0 idp_failures=0 idp_warnings=0 config_failures=1 config_warnings=0 strict=0
result: CONFIG_CHECK_FAILED
```

TOTPを使用しない環境では、TOTP保存方式に関するWARNを許容できる場合がある。
本番設定を警告なしで確認する場合は `--strict` を付ける。

```bash
/opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only \
  --strict
```

この検査はDB内の既存 `sequence` と `graphicalmatrix.choice` の整合性までは確認しない。

## 関連文書

- [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)
- [INSTALL.md](./INSTALL.md)
- [SECURITY.md](./SECURITY.md)
