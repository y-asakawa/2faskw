# 2FAS-KW Documentation

このディレクトリには、2FAS-KW Plugin for Shibboleth IdP の導入、設定、
運用、セキュリティに関する文書を配置しています。

## 読む順番

通常の導入では、以下の順に確認してください。

- [INSTALL.md](./INSTALL.md)
- [SECURITY.md](./SECURITY.md)
- [SECURITY-CHECKLIST.md](./SECURITY-CHECKLIST.md)
- [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)

PostgreSQL HA構成、検証用IdP/AP、SimpleSAMLphpテストSPを構築する場合は、
必要に応じて以下の導入記録を参照してください。

- [INSTALL_DB.md](./INSTALL_DB.md)
- [INSTALL_AP.md](./INSTALL_AP.md)
- [INSTALL_SP.md](./INSTALL_SP.md)

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
| [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md) | `*.properties` の設定項目、型、既定値、注意点の一覧。 |
| [build.md](./build.md) | Maven build と release ZIP 作成手順。 |

## 環境構築記録

| File | Description |
| --- | --- |
| [INSTALL_AP.md](./INSTALL_AP.md) | ビルドサーバ / IdP 検証環境のアプリケーション導入記録。 |
| [INSTALL_DB.md](./INSTALL_DB.md) | PostgreSQL HA 構成の導入記録。 |
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
