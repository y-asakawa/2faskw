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
固定名およびバージョン付きのplugin ZIP/tar.gz、管理ツールZIPを生成する。

ローカルでソースから作成した成果物は、リリース担当者の署名手順で署名されるまでは、
公式のリリースパッケージではない。

想定する出力ファイル名:

```text
target/plugin-dist/2faskw-idp-plugin-<VERSION>.zip
target/plugin-dist/2faskw-idp-plugin-<VERSION>.tar.gz
target/plugin-dist/2faskw-idp-plugin.zip
target/plugin-dist/2faskw-idp-plugin.tar.gz
target/admin-dist/2faskw-admin-tools-<VERSION>.zip
```

固定名のZIPと `tar.gz` は、Shibboleth plugin installer向けの公開アーカイブである。
バージョン付きのZIPと `tar.gz` は同一のバージョン付きトップレベルディレクトリを含み、
直接配布および手動導入用として保持する。

リリースZIPには少なくとも次を含める。

```text
LICENSE
NOTICE
THIRD-PARTY-NOTICES.md
```
