# 2FAS-KW v1.1.0 Security Upgrade

この文書は、v1.1.0 で追加された security migration（sequence 保存方式の移行）を
安全に実施するための最小手順をまとめる。

## 目的

v1.1.0 では、既存データの状態確認と migration 実行が必要です。  
通常の更新手順（`docs/UPGRADE.md`）を進める前に、この手順を完了してください。

## 前提

- 配布ZIPを展開済みである
- IdP停止のメンテナンス時間を確保済みである
- DBおよびIdP設定のバックアップを取得済みである
- PostgreSQL schema を適用済みである

## 実行手順

展開した配布物ディレクトリで以下を実行する。

```bash
# 1) 事前確認
./bin/graphicalmatrix-security-upgrade.sh \
  --package-dir . \
  --idp-home /opt/shibboleth-idp \
  plan

# 2) migration 適用
./bin/graphicalmatrix-security-upgrade.sh \
  --package-dir . \
  --idp-home /opt/shibboleth-idp \
  --backup-confirmed \
  --maintenance-confirmed \
  --schema-applied \
  apply

# 3) 検証
./bin/graphicalmatrix-security-upgrade.sh \
  --package-dir . \
  --idp-home /opt/shibboleth-idp \
  verify
```

## 成功判定

`verify` の出力で以下を満たすこと。

- `security_upgrade=READY`
- `security.initial_sequence_empty_rows=0`
- `security.initial_sequence_incompatible_rows=0`
- `security.incompatible_sequence_rows=0`
- `security.active_empty_sequence_rows=0`
- `security.active_incompatible_sequence_rows=0`

失敗した場合は `docs/DB-MIGRATION.md` を確認し、状態修正後に再実行する。
