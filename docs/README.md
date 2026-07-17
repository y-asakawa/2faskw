# 2FAS-KW Documentation

このディレクトリには、2FAS-KW Plugin for Shibboleth IdP の導入、設定、
運用、セキュリティに関する文書を配置しています。

## Releaseファイル構成
`2faskw-idp-plugin.tar.gz` と `2faskw-idp-plugin.zip` は、
同じ配布内容を異なる圧縮形式で提供するファイルです。

- `2faskw-idp-plugin.tar.gz`:
  Shibboleth IdPのplugin installerによる自動導入・更新用です。
- `2faskw-idp-plugin.zip`:
  展開して手動導入する場合、および配布内容を確認する場合に使用します。
Shibboleth plugin形式ではtar.gzとZIPの両方を配布物として扱えますが、現在の公開metadata経由の更新ではtar.gzが使われます。

## 読む順番
通常の導入では、以下の順に確認してください。

- [INSTALL.md](./INSTALL.md)
- [SECURITY.md](./SECURITY.md)
- [SECURITY-CHECKLIST.md](./SECURITY-CHECKLIST.md)
- [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)
- [FAQ.md](./FAQ.md)
- [UPGRADE.md](./UPGRADE.md)

PostgreSQL HA構成、検証用IdP/AP、SimpleSAMLphpテストSPを構築する場合は、
必要に応じて以下の導入記録を参照してください。

- [INSTALL_DB.md](./INSTALL_DB.md)
- [INSTALL_AP.md](./INSTALL_AP.md)
- [INSTALL_SP.md](./INSTALL_SP.md)
- [INSTALL_Passchange_IdP.md](./INSTALL_Passchange_IdP.md)
- [INSTALL_Passchange_SP.md](./INSTALL_Passchange_SP.md)

管理CLIだけを導入する場合は、[ADMIN-TOOLS.md](./ADMIN-TOOLS.md) を参照してください。
API連携を行う場合は、[openapi.yaml](./openapi.yaml)、
[API-TOKEN-ROTATION.md](./API-TOKEN-ROTATION.md)、
[API-CURL-TESTS.md](./API-CURL-TESTS.md) を参照してください。

## バージョン管理

配布物のバージョンはソース直下の `version.ini` を正とします。
`scripts/build-plugin-package.sh` は `version.ini` を読み込み、Mavenの `revision`、
plugin metadata、OpenAPI、配布物内ドキュメントへ同じバージョンを反映します。
詳細は [build.md](./build.md) を参照してください。

## 導入・設定

| File | Description |
| --- | --- |
| [README.md](./README.md) | このファイル。docs ディレクトリ内の文書一覧。 |
| [INSTALL.md](./INSTALL.md) | IdP への導入手順、配布物構成、必須アプリ、主要設定例、rollback 手順。 |
| [INSTALL_Manual_Installation.md](./INSTALL_Manual_Installation.md) | `graphicalmatrix-plugin-check.sh` / `graphicalmatrix-plugin-config.sh` を使わずに手動で確認・配置する手順。 |
| [INSTALL_LDAP.md](./INSTALL_LDAP.md) | LDAP新規導入、属性設計、TOTP/WebAuthn LDAP保存設定の手順。 |
| [INSTALL_Passchange_IdP.md](./INSTALL_Passchange_IdP.md) | GraphicalMatrix変更およびMFA方式変更を、IdP内のShibboleth再認証済み自己管理フローで提供するための設計。推奨方式。 |
| [INSTALL_Passchange_SP.md](./INSTALL_Passchange_SP.md) | 外部自己管理SPを追加し、GraphicalMatrix変更およびMFA方式変更を提供するための設計。 |
| [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md) | `*.properties` の設定項目、型、既定値、注意点の一覧。 |
| [FAQ.md](./FAQ.md) | 設定読込エラー、反映タイミング、ログ確認などのFAQ。 |
| [UPGRADE.md](./UPGRADE.md) | Plugin更新、旧JAR整理、設定差分反映、動作試験、ロールバック手順。 |
| [DB-SCHEMA.md](./DB-SCHEMA.md) | GraphicalMatrix DB スキーマ設計の素案。 |
| [build.md](./build.md) | Maven build と release ZIP 作成手順。 |

## 環境構築記録

| File | Description |
| --- | --- |
| [INSTALL_AP.md](./INSTALL_AP.md) | ビルドサーバ / IdP 検証環境のアプリケーション導入記録。 |
| [INSTALL_DB.md](./INSTALL_DB.md) | PostgreSQL HA 構成の導入記録。 |
| [INSTALL_LDAP.md](./INSTALL_LDAP.md) | LDAP新規導入と2FAS-KW LDAP保存設定の手順。 |
| [INSTALL_SP.md](./INSTALL_SP.md) | SimpleSAMLphp テストSPの導入手順・確認記録。 |

## 運用・管理

| File | Description |
| --- | --- |
| [ADMIN-TOOLS.md](./ADMIN-TOOLS.md) | 管理CLI、Admin Tools 単体導入、CSV import/export、監査ログの運用説明。 |
| [CSV-EXPORT.md](./CSV-EXPORT.md) | `graphicalmatrix_enrollment` から管理CSVを出力する手順。 |
| [LOGROTATE.md](./LOGROTATE.md) | GraphicalMatrix audit log の logrotate 設定例。 |
| [INSTALL_LOADTEST.md](./INSTALL_LOADTEST.md) | 負荷試験環境と load test に関する補助メモ。 |

## API

| File | Description |
| --- | --- |
| [openapi.yaml](./openapi.yaml) | 管理APIの OpenAPI 定義。 |
| [API-TOKEN-ROTATION.md](./API-TOKEN-ROTATION.md) | 管理API bearer token の発行、確認、ローテーション手順。 |
| [API-CURL-TESTS.md](./API-CURL-TESTS.md) | 管理API の curl による疎通・読み書きテスト手順。 |

## セキュリティ

| File | Description |
| --- | --- |
| [SECURITY.md](./SECURITY.md) | 管理API、DB、sequence/TOTP seed、WebAuthn、監査ログなどのセキュリティ運用ガイド。 |
| [SECURITY-CHECKLIST.md](./SECURITY-CHECKLIST.md) | 本番導入前に確認するセキュリティチェックリスト。 |

## 移行

| File | Description |
| --- | --- |
| [DB-MIGRATION.md](./DB-MIGRATION.md) | H2 から PostgreSQL へ移行する手順。 |
| [SEQUENCE-STORAGE-MIGRATION.md](./SEQUENCE-STORAGE-MIGRATION.md) | GraphicalMatrix sequence / TOTP seed 保存方式を移行する手順。 |

## 簡易運用マニュアル

ここでは、導入後の日常確認と基本的な管理操作をまとめる。コマンド例はIdPを
`/opt/shibboleth-idp`へ導入した構成を前提とする。

### 日常点検

まずIdPサービス、2FAS-KW設定、直近の監査ログを確認する。

```bash
sudo systemctl is-active jetty-idp.service

sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only

sudo tail -n 50 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

`result: OK`と`active`を確認する。設定検査で`FAIL`が出た場合は、利用者認証を開始する前に
`/opt/shibboleth-idp/conf/graphicalmatrix/`または`/opt/shibboleth-idp/conf/authn/`の設定を修正する。

### 利用者向けURL

`idp.example.org`は実際のIdP FQDNへ置き換える。

| 用途 | URL | 利用条件 |
| --- | --- | --- |
| IdP状態確認 | `https://idp.example.org/idp/status` | IdPのstatus endpointを公開している場合。 |
| 通常ログイン | SPが開始するSAML認証URL | 利用者へIdPログインURLを直接案内しない。 |
| 従来の変更画面 | `https://idp.example.org/idp/graphicalmatrix/change` | `graphicalmatrix.change.legacyLdapLoginEnabled=true`の場合。LDAP ID・パスワードと現在のGraphicalMatrixを使用する。 |
| 推奨の自己管理画面 | `https://idp.example.org/idp/profile/2faskw/self-service` | `graphicalmatrix.selfservice.enabled=true`の場合。Shibboleth Password認証と現在のMFA方式で再認証する。 |
| 管理API | `https://idp.example.org/idp/graphicalmatrix-admin/api/v1/` | APIを明示的に有効化した管理クライアントだけが使用する。 |

TOTP登録とWebAuthn登録は、自己管理画面または変更メニューでMFA方式を選択して開始する。
登録用URLを利用者へ直接案内せず、既存の認証・認可条件を通して開始する。

### DB保存時の管理CLI

`graphicalmatrix.savedata = db`の場合、IdPサーバ上では次のCLIを使用できる。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh show user001
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh sequence-mode
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh totp-seed-mode
```

代表的な管理操作は以下のとおりである。`add`、`set-method`、`unlock`、`RESET`などはDBを直ちに
変更するため、対象ユーザーを確認して実行する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh add user001 A B C D
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method user001 GraphicalMatrix
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method user001 TOTP
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method user001 WebAuthn
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh unlock user001
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh user001 RESET
```

CSV投入は、最初にdry-runを実行する。`--apply`を付けたときだけDBへ反映する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv --apply
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export /secure/path/graphicalmatrix-users.csv
```

スタンドアロンのAdmin Toolsを導入している場合は、同じCLIを
`/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh`から実行する。管理CLI、CSV形式、
WebAuthn credentialの管理、保存方式移行の詳細は[ADMIN-TOOLS.md](./ADMIN-TOOLS.md)を参照する。

### LDAP保存時の管理

`graphicalmatrix.savedata = ldap`の場合、上記の`graphicalmatrix-db.sh`はユーザー属性を管理しない。
ユーザー追加、sequence、MFA方式、TOTP状態の管理はLDAPの管理手段で行う。
LDAP属性、ACL、ユーザー追加、WebAuthn LDAP保存の運用は[INSTALL_LDAP.md](./INSTALL_LDAP.md)を参照する。

### ログ確認

2FAS-KW固有の監査ログと、IdPの処理ログを確認する。

```bash
sudo tail -f /opt/shibboleth-idp/logs/graphicalmatrix-audit.log

sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log
```

自己管理フローを有効にしている場合、正常な開始では次の監査イベントが順に記録される。

```text
event=SELF_SERVICE_AUTH ... result=OK ...
event=SELF_SERVICE_HANDOFF ... result=OK ...
```

監査ログにはsequence、TOTP seed、WebAuthn credential、LDAP password、暗号鍵を記録してはならない。
logrotate設定は[LOGROTATE.md](./LOGROTATE.md)を参照する。

### 設定変更と再起動

主な設定ファイルは以下である。

| 対象 | ファイル |
| --- | --- |
| GraphicalMatrix、保存先、自己管理 | `/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties` |
| DB接続 | `/opt/shibboleth-idp/conf/graphicalmatrix/db.properties` |
| LDAP接続 | `/opt/shibboleth-idp/conf/graphicalmatrix/ldap.properties` |
| MFAポリシー | `/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties` |
| IdP認証Flow | `/opt/shibboleth-idp/conf/authn/authn.properties` |

Plugin JAR、`web.xml`、IdP認証Flowを変更した場合は、設定検査後にWARを再構築してJettyを再起動する。
`graphicalmatrix.properties`、外部HTML、CSSだけを変更した場合は、通常は次回アクセスから反映される。
ただし、進行中の認証・変更セッションには開始時の設定が残る場合があるため、新しいブラウザセッションで
確認する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only

sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
sudo systemctl is-active jetty-idp.service
```

Plugin更新とロールバックは[UPGRADE.md](./UPGRADE.md)、画面HTML/CSSの編集は[FAQ.md](./FAQ.md)、
管理APIは[API-CURL-TESTS.md](./API-CURL-TESTS.md)を参照する。
