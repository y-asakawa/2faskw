# GraphicalMatrix sequence保存方式 migration

## 概要

`graphicalmatrix.sequence.storage` を変更した後、既存ユーザーの `sequence` を現在の保存方式で再保存するための管理コマンドです。

対象は `graphicalmatrix_enrollment.sequence` です。`initial_sequence` はアカウントマネージャーから渡される初期値の参照用途として平文またはaliasのまま扱う設計のため、このmigrationでは更新しません。

## 対応できる移行

移行元が以下の場合は、復号または読み取りできるため移行できます。

- `plaintext`
- `keyword`
- `aes-gcm`

移行先は現在の `conf/graphicalmatrix/graphicalmatrix.properties` の値で決まります。

```properties
graphicalmatrix.sequence.storage = plaintext
graphicalmatrix.sequence.storage = keyword
graphicalmatrix.sequence.storage = aes-gcm
graphicalmatrix.sequence.storage = hash
```

## 対応できない移行

`hash` 保存済みの `sequence` は復号できません。そのため、`hsp1:` で保存済みの値を別方式へ戻すことはできません。

`hash` に移行する場合は、必ず事前にdry-runで全ユーザーが `PLAN` になることを確認してください。`hash` 化後は管理者が現在値を確認できなくなります。

## 事前確認

現在の設定値を確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh sequence-mode
```

DB内の状態を確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
```

`keyword` または `aes-gcm` から別方式へ移行する場合は、古い値を復号するための secret 設定を残したまま実行してください。

- `graphicalmatrix.sequence.keyword` または `graphicalmatrix.sequence.keywordFile`
- `graphicalmatrix.sequence.aesKey` または `graphicalmatrix.sequence.aesKeyFile`

## dry-run

更新せず、対象ユーザーと移行結果だけを表示します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-sequence-storage
```

明示的にdry-runを指定する場合:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-sequence-storage --dry-run
```

出力例:

```text
PLAN user=test01 from=plaintext to=hash count=4
OK user=test-user001 storage=hash
SKIP user=old01 from=hash to=aes-gcm reason=not_recoverable
summary.mode=dry-run
summary.target_storage=hash
summary.total=3
summary.already=1
summary.planned=1
summary.applied=0
summary.skipped_hash=1
summary.skipped_empty=0
summary.errors=0
```

## 適用

dry-runの内容が正しい場合だけ `--apply` を付けます。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-sequence-storage --apply
```

適用時は、途中にdecode失敗やsequence検証エラーがある場合、DB更新は行わず終了します。

## H2利用時の注意

H2の場合、DBファイルの所有権を崩さないため、管理スクリプトは内部で `jetty` ユーザーとしてmigrationツールを実行します。

## PostgreSQL利用時の注意

PostgreSQLの場合、`conf/graphicalmatrix/db.properties` のJDBC設定を使って接続します。

PostgreSQL JDBCドライバは `/opt/shibboleth-idp/edit-webapp/WEB-INF/lib` 配下にある必要があります。Plugin配布物では `lib/postgresql-*.jar` を同梱するため、インストール時に配置されます。

## 推奨手順

1. DBバックアップを取得する
2. `graphicalmatrix.properties` に移行先の `graphicalmatrix.sequence.storage` と必要なsecretを設定する
3. `sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-sequence-storage` でdry-runする
4. `summary.errors=0` を確認する
5. `hash` へ移行する場合は、`PLAN` 対象と `SKIP` 対象を再確認する
6. `sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-sequence-storage --apply` を実行する
7. `sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list` で保存方式のprefixを確認する
8. IdPログイン試験を行う
