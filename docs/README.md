# 2FAS-KW Documentation

このディレクトリには、2FAS-KW Plugin for Shibboleth IdP の導入、設定、
運用、セキュリティに関する文書を配置しています。

## ファイル一覧

| File | Description |
| --- | --- |
| `README.md` | このファイル。docs ディレクトリ内の文書一覧。 |
| `INSTALL.md` | IdP への導入手順、配布物構成、必須アプリ、主要設定例、rollback 手順。 |
| `INSTALL_AP.md` | ビルドサーバ / IdP 検証環境のアプリケーション導入記録。 |
| `INSTALL_DB.md` | PostgreSQL HA 構成の導入記録。 |
| `INSTALL_SP.md` | SimpleSAMLphp テストSPの導入手順・確認記録。 |
| `SECURITY.md` | 管理API、DB、sequence/TOTP seed、WebAuthn、監査ログなどのセキュリティ運用ガイド。 |
| `SECURITY-CHECKLIST.md` | 本番導入前に確認するセキュリティチェックリスト。 |
| `build.md` | Maven build と release ZIP 作成手順。 |
| `CONFIG-REFERENCE.md` | `*.properties` の設定項目、型、既定値、注意点の一覧。 |
| `ADMIN-TOOLS.md` | 管理CLI、Admin Tools 単体導入、CSV import/export、監査ログの運用説明。 |
| `API-TOKEN-ROTATION.md` | 管理API bearer token の発行、確認、ローテーション手順。 |
| `API-CURL-TESTS.md` | 管理API の curl による疎通・読み書きテスト手順。 |
| `CSV-EXPORT.md` | `graphicalmatrix_enrollment` から管理CSVを出力する手順。 |
| `DB-MIGRATION.md` | H2 から PostgreSQL へ移行する手順。 |
| `SEQUENCE-STORAGE-MIGRATION.md` | GraphicalMatrix sequence / TOTP seed 保存方式を移行する手順。 |
| `LOGROTATE.md` | GraphicalMatrix audit log の logrotate 設定例。 |
| `INSTALL_LOADTEST.md` | 負荷試験環境と load test に関する補助メモ。 |
| `openapi.yaml` | 管理APIの OpenAPI 定義。 |

## 最初に読む文書

通常の導入では、以下の順に確認してください。

```text
INSTALL.md
SECURITY.md
SECURITY-CHECKLIST.md
CONFIG-REFERENCE.md
```

管理CLIだけを導入する場合は、`ADMIN-TOOLS.md` を参照してください。

API連携を行う場合は、`openapi.yaml`、`API-TOKEN-ROTATION.md`、
`API-CURL-TESTS.md` を参照してください。
