# Signed Plugin Package Plan

## 目的

2FAS-KW Pluginを、Shibboleth IdPのplugin installerで扱いやすい
署名済みplugin packageとして配布できるようにする。

ここでいう「署名済み公式plugin package」には2段階ある。

```text
1. 署名済みShibboleth IdP plugin互換package
   自組織で署名鍵、metadata、配布URLを管理する。

2. Shibboleth Project公式配布
   Shibboleth Project側の標準plugin一覧や配布基盤に載せる。
   これは自組織だけでは完結せず、Project側の受入、審査、公開運用が必要。
```

当面の目標は 1 とする。
2 を目指す場合も、まず 1 の品質まで整える。

## 公式仕様上の重要点

Shibboleth IdP plugin packageは、少なくとも以下を満たす必要がある。

- `webapp/WEB-INF/lib` に、`net.shibboleth.idp.plugin.IdPPlugin` serviceを実装するJARを含める
- `bootstrap/plugin.properties` を含める
- `bootstrap/plugin.properties` の `plugin.id` はJAR内のplugin IDと一致させる
- `bootstrap/keys.txt` には署名検証用の公開鍵を入れる
- plugin本体とは別に、互換性metadataのpropertiesをHTTPSで公開する
- 配布packageにdetached signatureを付ける
- plugin installerで署名鍵のfingerprintを検証できるようにする

現在の2FAS-KW pluginは、以下までは実装済み。

```text
webapp/WEB-INF/lib/2faskw-idp-plugin-1.0.1.jar
META-INF/services/net.shibboleth.idp.plugin.IdPPlugin
PropertyDrivenIdPPlugin実装
bootstrap/plugin.properties
plugin-metadata/graphicalmatrix-plugin.properties
```

不足しているもの:

```text
実署名鍵
bootstrap/keys.txt の本物の公開鍵
detached signature
HTTPS配布URL
正式なcompatibility metadata公開URL
tar.gz形式の公式配布物
署名検証を含むplugin.shインストール試験
リリース鍵の保管/失効/ローテーション手順
```

## 推奨ディレクトリ

リリース用に以下を追加する。

```text
remote_graphicalmatrix_src/release/
  README.md
  keys/
    RELEASE-KEY-FINGERPRINT.txt
  metadata/
    graphicalmatrix-plugin.properties
  scripts/
    build-signed-release.sh
    verify-signed-release.sh
```

秘密鍵はリポジトリに置かない。
公開鍵、fingerprint、検証手順だけを管理対象にする。

## 署名鍵

リリース用GPG鍵を作る。
個人の日常鍵ではなく、2FAS-KW plugin release専用鍵にする。

例:

```bash
gpg --quick-generate-key \
  "2FAS-KW Plugin Release <2faskw-release@example.ac.jp>" \
  rsa4096 sign 3y
```

fingerprint確認:

```bash
gpg --list-keys --fingerprint "2FAS-KW Plugin Release"
```

公開鍵export:

```bash
gpg --armor --export "2FAS-KW Plugin Release" \
  > bootstrap/keys.txt
```

リポジトリに入れてよいもの:

```text
bootstrap/keys.txt
release/keys/RELEASE-KEY-FINGERPRINT.txt
```

リポジトリに入れてはいけないもの:

```text
秘密鍵
秘密鍵バックアップ
失効証明書の未管理コピー
署名用passphrase
```

## package形式

現在の内部配布物はzip中心。
公式互換配布では、以下を生成する方針にする。

```text
2faskw-idp-plugin-1.0.1.tar.gz
2faskw-idp-plugin-1.0.1.tar.gz.asc
2faskw-idp-plugin-1.0.1.zip
2faskw-idp-plugin-1.0.1.zip.asc
SHA256SUMS
SHA256SUMS.asc
```

`plugin.sh` の自動取得で利用する標準は `tar.gz + tar.gz.asc` を優先する。
zipは手動検証/内部配布用として残す。

packageの中身は現在の `2faskw-idp-plugin-1.0.1/` と同じ構造にする。

## detached signature

tar.gz署名:

```bash
gpg --armor --detach-sign \
  target/plugin-dist/2faskw-idp-plugin-1.0.1.tar.gz
```

zip署名:

```bash
gpg --armor --detach-sign \
  target/plugin-dist/2faskw-idp-plugin-1.0.1.zip
```

checksum:

```bash
sha256sum \
  target/plugin-dist/2faskw-idp-plugin-1.0.1.tar.gz \
  target/plugin-dist/2faskw-idp-plugin-1.0.1.zip \
  > target/plugin-dist/SHA256SUMS

gpg --armor --detach-sign target/plugin-dist/SHA256SUMS
```

検証:

```bash
gpg --verify 2faskw-idp-plugin-1.0.1.tar.gz.asc \
  2faskw-idp-plugin-1.0.1.tar.gz

sha256sum -c SHA256SUMS
```

## compatibility metadata

公開するmetadata例:

```properties
io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix.versions = 1.0.1

io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix.downloadURL.1.0.1 = https://example.jp/shibboleth/plugins/2faskw/1.0.1/
io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix.baseName.1.0.1 = 2faskw-idp-plugin-1.0.1
io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix.idpVersionMin.1.0.1 = 5.2.0
io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix.idpVersionMax.1.0.1 = 6.0.0
io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix.supportLevel.1.0.1 = Current
```

注意:

- `downloadURL` はpackage本体のあるディレクトリURL
- `baseName` は拡張子なしのpackage名
- `supportLevel=Current` のものだけを通常更新対象にする
- 古い版は `OutOfDate` や `Withdrawn` へ変更する
- metadataはHTTPSで公開する
- `bootstrap/plugin.properties` の `plugin.url.0` はこのmetadata URLへ向ける

## bootstrap/plugin.properties

例:

```properties
plugin.id = io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix
plugin.version = 1.0.1
plugin.license = /jp/ac/example/graphicalmatrix/plugin/LICENSE.txt
plugin.modules.required = idp.authn.Password,idp.authn.MFA
plugin.url.0 = https://example.jp/shibboleth/plugins/2faskw/plugin.properties
```

確認すること:

- `plugin.id` がJava実装のplugin IDと一致する
- `plugin.version` がMaven versionと一致する
- `plugin.url.0` がHTTPSで取得できる
- `bootstrap/keys.txt` が本物の公開鍵になっている

## 配布URL構成

例:

```text
https://example.jp/shibboleth/plugins/2faskw/plugin.properties
https://example.jp/shibboleth/plugins/2faskw/1.0.1/2faskw-idp-plugin-1.0.1.tar.gz
https://example.jp/shibboleth/plugins/2faskw/1.0.1/2faskw-idp-plugin-1.0.1.tar.gz.asc
https://example.jp/shibboleth/plugins/2faskw/1.0.1/SHA256SUMS
https://example.jp/shibboleth/plugins/2faskw/1.0.1/SHA256SUMS.asc
```

公開サーバ要件:

- HTTPS必須
- TLS証明書が正しい
- directory listingは任意だが、ファイルURLは固定する
- MIME typeは標準的なものにする
- 過去versionのファイルを消さない
- metadata更新はatomicに行う

## build-signed-release.shで行うこと

将来実装するスクリプトの流れ:

```bash
mvn -B -ntp clean package

# plugin dist作成
scripts/build-plugin-package.sh

# tar.gz生成
tar -C target/plugin-dist \
  -czf target/plugin-dist/2faskw-idp-plugin-1.0.1.tar.gz \
  2faskw-idp-plugin-1.0.1

# signature生成
gpg --armor --detach-sign target/plugin-dist/2faskw-idp-plugin-1.0.1.tar.gz
gpg --armor --detach-sign target/plugin-dist/2faskw-idp-plugin-1.0.1.zip

# checksum生成
sha256sum target/plugin-dist/2faskw-idp-plugin-1.0.1.tar.gz \
          target/plugin-dist/2faskw-idp-plugin-1.0.1.zip \
  > target/plugin-dist/SHA256SUMS
gpg --armor --detach-sign target/plugin-dist/SHA256SUMS
```

## ローカルインストール試験

IdP検証環境で行う。

```bash
sudo /opt/shibboleth-idp/bin/plugin.sh \
  -i /tmp/2faskw-idp-plugin-1.0.1.tar.gz
```

ローカルファイルで試験する場合も、同じディレクトリに `.asc` を置く。

```text
/tmp/2faskw-idp-plugin-1.0.1.tar.gz
/tmp/2faskw-idp-plugin-1.0.1.tar.gz.asc
```

初回は署名鍵のaccept確認が出る。
表示されたfingerprintが `RELEASE-KEY-FINGERPRINT.txt` と一致することを
別経路で確認してからacceptする。

確認:

```bash
sudo /opt/shibboleth-idp/bin/plugin.sh -l
sudo /opt/shibboleth-idp/bin/plugin.sh -cl io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix
```

## 更新試験

更新metadataを公開した状態で確認する。

```bash
sudo /opt/shibboleth-idp/bin/plugin.sh -fl io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix
sudo /opt/shibboleth-idp/bin/plugin.sh -u io.github.yasakawa.faskw.idp.plugin.authn.graphicalmatrix
```

確認項目:

- IdP versionに合う最新Current版が選ばれる
- signature検証が通る
- 既にtruststoreに入った鍵以外で署名した場合は警告になる
- インストール後にIdP rebuildが成功する
- rollback手順が成立する

## リリース前チェックリスト

- [ ] Maven versionが非SNAPSHOT
- [ ] 依存関係にSNAPSHOTがない
- [ ] Java releaseが21
- [ ] `plugin.version` がMaven versionと一致
- [ ] JAR manifestのImplementation-VersionがMaven versionと一致
- [ ] `META-INF/services/net.shibboleth.idp.plugin.IdPPlugin` が1つだけ
- [ ] `bootstrap/plugin.properties` が存在
- [ ] `bootstrap/keys.txt` が本物の公開鍵
- [ ] `plugin.url.0` がHTTPS metadata URL
- [ ] compatibility metadataに新versionを追加
- [ ] 旧versionのsupportLevelを整理
- [ ] `tar.gz` と `tar.gz.asc` を生成
- [ ] `SHA256SUMS` と `SHA256SUMS.asc` を生成
- [ ] clean環境で `plugin.sh -i` 成功
- [ ] clean環境で `plugin.sh -l` / `plugin.sh -cl` 成功
- [ ] update metadata経由の `plugin.sh -u` 成功
- [ ] 署名鍵fingerprintを別経路で確認可能
- [ ] SECURITY.md / LICENSE / NOTICEを更新
- [ ] 配布URLを長期保持する

## 現状との差分

現在の `1.0.1` は内部配布としては利用可能だが、署名済みplugin packageとしては未完成。

不足:

```text
bootstrap/keys.txt がplaceholder
detached signature未生成
tar.gz未生成
plugin.url.0 がexample URL
downloadURLがexample URL
署名鍵fingerprint管理なし
plugin.shによる署名検証インストール試験未実施
```

次に実装するなら、優先順は以下。

```text
1. release用GPG鍵を作成し、公開鍵とfingerprintをローカル管理
2. build-plugin-package.shにtar.gz生成を追加
3. build-signed-release.shを追加
4. plugin.properties / metadata URLを実URLへ変更
5. 検証IdPでplugin.sh -i試験
6. update metadata経由のplugin.sh -u試験
```

## 参考

- Shibboleth IdP 5 PluginDevelopment
  - https://shibboleth.atlassian.net/wiki/spaces/IDP5/pages/3199512741/PluginDevelopment
- Shibboleth PluginBuildSteps
  - https://shibboleth.atlassian.net/wiki/spaces/DEV/pages/1196393741/PluginBuildSteps
- Shibboleth PluginTrust
  - https://shibboleth.atlassian.net/wiki/spaces/DEV/pages/1196393148/PluginTrust
- Shibboleth PluginInstallation
  - https://shibboleth.atlassian.net/wiki/spaces/IDPPLUGINS/pages/1376878882/PluginInstallation
