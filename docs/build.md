# ソースから2FAS-KWをビルドする

## ビルド

リリースおよびパッケージのビルドでは、`version.ini` をバージョン情報の正とする。

```ini
VERSION=1.2.2
ARTIFACT_ID=2faskw-idp-plugin
ADMIN_ARTIFACT_ID=2faskw-admin-tools
```

`scripts/build-plugin-package.sh` は `version.ini` を読み込み、Mavenへ
`-Drevision` としてバージョンを渡す。plugin metadata、OpenAPI、パッケージに
同梱する文書も同じバージョンで生成する。

Mavenを直接実行する場合、`pom.xml` には `revision` の既定値がある。ただし、
リリース成果物は `scripts/build-plugin-package.sh` で生成する。

```bash
mvn -B -ntp clean package
```

このコマンドはplugin JARをビルドし、実行時依存ライブラリを `target/` 配下へコピーする。

## リリースパッケージ

pluginおよび管理ツールのリリースパッケージを生成する。

```bash
./scripts/build-plugin-package.sh
```

リリースビルドには、ASCII armored形式の公開リリース鍵
`bootstrap/keys.txt` が必要である。この公開鍵はpluginアーカイブ内の
`bootstrap/keys.txt` として格納される。秘密鍵やpassphraseをリポジトリに置いてはいけない。

スクリプトは最初に `mvn -B -ntp clean package` を実行し、リリース用ディレクトリ、
固定名およびバージョン付きのplugin ZIP/tar.gz、固定名およびバージョン付きの
Admin Tools ZIP、固定名アーカイブ3件の`SHA256SUMS`を生成する。

ローカルでソースから作成した成果物は、リリース担当者の署名手順で署名されるまでは、
公式のリリースパッケージではない。

想定する出力ファイル名:

```text
target/plugin-dist/2faskw-idp-plugin-<VERSION>.zip
target/plugin-dist/2faskw-idp-plugin-<VERSION>.tar.gz
target/plugin-dist/2faskw-idp-plugin.zip
target/plugin-dist/2faskw-idp-plugin.tar.gz
target/admin-dist/2faskw-admin-tools-<VERSION>.zip
target/admin-dist/2faskw-admin-tools.zip
target/plugin-dist/SHA256SUMS
```

固定名のZIPと `tar.gz` は、Shibboleth plugin installer向けの公開アーカイブである。
バージョン付きのZIPと `tar.gz` は同一のバージョン付きトップレベルディレクトリを含み、
直接配布および手動導入用として保持する。Admin Tools ZIPはShibboleth pluginではなく、
DB管理CLIを別の管理端末へ手動導入するための独立した配布物である。
IdPプラグイン本体と同様、Admin Tools ZIPにも詳細な`docs/`は含めない。配布専用の
`README.md`だけを含め、詳細はGitHub上の公開文書URLで案内する。

通常ビルドではGPG署名を生成しない。秘密鍵を保管するリリース環境で、追跡対象の
Git差分がないことを確認してから、リリース担当者だけが次を実行する。

```bash
./scripts/build-plugin-package.sh --sign
```

`--sign`は、固定名とバージョン付きのpluginアーカイブ、Admin Tools ZIP、
`SHA256SUMS`へASCII armored detached signatureを生成する。その後、
`bootstrap/keys.txt`だけを読み込んだ一時鍵リングで、署名とfingerprintを検証する。
秘密鍵、passphrase、秘密鍵バックアップ、失効証明書をGit、CI、配布物へ置いてはいけない。

リリースZIPには少なくとも次を含める。

```text
LICENSE
NOTICE
THIRD-PARTY-NOTICES.md
```
