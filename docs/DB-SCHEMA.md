# GraphicalMatrix DB Schema Design

## 1. 概要

この文書は、GraphicalMatrix MFAで利用する現在のDBスキーマ設計をまとめる素案である。

対象は以下の2種類のデータである。

- GraphicalMatrix / TOTP / MFA方式選択を管理する `graphicalmatrix_enrollment`
- Shibboleth WebAuthn PluginのJDBC StorageServiceが利用する `storagerecords`

`graphicalmatrix_enrollment` はGraphicalMatrixが直接管理するテーブルである。
`storagerecords` はShibboleth JDBC StorageServiceの標準テーブルであり、
GraphicalMatrixの管理CLIはWebAuthn credential管理のために参照・更新する。

## 2. 設計方針

- ユーザー単位のMFA状態を `user_id` で一元管理する。
- MFA方式は `mfa_method` で切り替える。
- GraphicalMatrix sequenceとTOTP seedは同じユーザーレコードに保持する。
- WebAuthn credentialはShibboleth WebAuthn Pluginの形式を尊重し、`storagerecords` に保存する。
- IdP runtimeでは削除操作を最小限にし、管理操作はCLIまたはAdmin Toolsで実施する。
- PostgreSQL本番運用では事前にDDLを適用し、IdP runtimeユーザーにDDL権限を与えない。

## 3. 論理モデル

```text
graphicalmatrix_enrollment
  1 row = 1 user MFA state

storagerecords
  1 row = Shibboleth StorageService record
  value = WebAuthn credential JSON array
```

関連はDB制約では定義していない。
運用上は `graphicalmatrix_enrollment.user_id` と `storagerecords.id`、
または `storagerecords.value` 内の `username` / `userIdentity.name` / `userIdentity.id`
を対応付ける。

## 4. 物理スキーマ

### 4.1 graphicalmatrix_enrollment

GraphicalMatrix本体、TOTP seed、MFA方式選択、ロック状態を管理する主テーブル。

```sql
CREATE TABLE IF NOT EXISTS graphicalmatrix_enrollment (
  user_id VARCHAR(255) PRIMARY KEY,
  sequence VARCHAR(1024) NOT NULL,
  initial_sequence VARCHAR(1024) NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  failed_count INTEGER NOT NULL DEFAULT 0,
  locked_until BIGINT NOT NULL DEFAULT 0,
  mfa_method VARCHAR(32) NOT NULL DEFAULT 'GraphicalMatrix',
  totp_seed VARCHAR(255),
  totp_status VARCHAR(32) NOT NULL DEFAULT 'UNREGISTERED',
  totp_registered_at BIGINT NOT NULL DEFAULT 0,
  last_success_at BIGINT NOT NULL DEFAULT 0,
  force_sequence_change INTEGER NOT NULL DEFAULT 0,
  state_version BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
```

### 4.2 graphicalmatrix_enrollment columns

| Column | Type | Null | Default | Description |
| --- | --- | --- | --- | --- |
| `user_id` | `VARCHAR(255)` | no | none | ユーザーID。主キー。IdPの認証済みPrincipalと対応する。 |
| `sequence` | `VARCHAR(1024)` | no | none | 現在のGraphicalMatrix sequence。保存方式により平文、暗号化文字列、hash文字列になる。 |
| `initial_sequence` | `VARCHAR(1024)` | no | `''` | `USER RESET`用の初期sequence。初期パスワードとして管理者が確認できるよう平文で保持する。 |
| `status` | `VARCHAR(32)` | no | `ACTIVE` | ユーザー状態。通常は `ACTIVE`、停止時は `DISABLED`。 |
| `failed_count` | `INTEGER` | no | `0` | GraphicalMatrix認証失敗回数。成功時や方式変更時に0へ戻す。 |
| `locked_until` | `BIGINT` | no | `0` | ロック解除時刻。Unix epoch milliseconds。`0` は未ロック。 |
| `mfa_method` | `VARCHAR(32)` | no | `GraphicalMatrix` | 利用するMFA方式。`GraphicalMatrix`、`TOTP`、`WebAuthn` を想定する。 |
| `totp_seed` | `VARCHAR(255)` | yes | `NULL` | TOTP seed。保存方式により平文または暗号化文字列になる。 |
| `totp_status` | `VARCHAR(32)` | no | `UNREGISTERED` | TOTP登録状態。`UNREGISTERED`、`PENDING`、`ACTIVE` を想定する。 |
| `totp_registered_at` | `BIGINT` | no | `0` | TOTP登録完了時刻。Unix epoch milliseconds。 |
| `last_success_at` | `BIGINT` | no | `0` | 最終認証成功時刻。Unix epoch milliseconds。 |
| `force_sequence_change` | `INTEGER` | no | `0` | 次回ログイン時のGraphicalMatrix変更強制フラグ。`0` は無効、非0は有効。 |
| `state_version` | `BIGINT` | no | `0` | 管理状態の世代番号。本人確認後の古い画面から無効化・ロック状態を上書きしないために使用する。 |
| `created_at` | `BIGINT` | no | none | レコード作成時刻。Unix epoch milliseconds。 |
| `updated_at` | `BIGINT` | no | none | レコード更新時刻。Unix epoch milliseconds。 |

### 4.3 graphicalmatrix_enrollment indexes

```sql
CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_status
  ON graphicalmatrix_enrollment (status);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_mfa_method
  ON graphicalmatrix_enrollment (mfa_method);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_totp_status
  ON graphicalmatrix_enrollment (totp_status);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_force_sequence_change
  ON graphicalmatrix_enrollment (force_sequence_change);
```

主な検索は `user_id` 主キー参照である。
上記インデックスは管理画面、CLI、集計、運用確認での絞り込みを想定する。

### 4.4 storagerecords

`storagerecords` はShibboleth JDBC StorageServiceが利用するテーブルである。
WebAuthn credentialをDB永続化する場合に必要になる。

```sql
CREATE TABLE IF NOT EXISTS StorageRecords (
  context varchar(255) NOT NULL,
  id varchar(255) NOT NULL,
  expires bigint DEFAULT NULL,
  value text NOT NULL,
  version bigint NOT NULL,
  PRIMARY KEY (context, id)
);

CREATE INDEX IF NOT EXISTS idx_storage_records_expires
  ON StorageRecords (expires);
```

PostgreSQLでは引用符なしの `StorageRecords` は小文字の `storagerecords` として作成される。
Shibboleth JDBC StorageServiceも引用符なしで参照するため、この状態で一致する。

### 4.5 storagerecords columns

| Column | Type | Null | Default | Description |
| --- | --- | --- | --- | --- |
| `context` | `varchar(255)` | no | none | Shibboleth StorageServiceのcontext。WebAuthnではWebAuthn Pluginのcontextが入る。 |
| `id` | `varchar(255)` | no | none | StorageService上のID。多くの場合ユーザーIDと対応する。 |
| `expires` | `bigint` | yes | `NULL` | 期限。StorageService仕様に従う。 |
| `value` | `text` | no | none | WebAuthn credential情報。GraphicalMatrix管理CLIではJSON配列として扱う。 |
| `version` | `bigint` | no | none | StorageServiceの楽観ロック用バージョン。更新時に増加する。 |

## 5. 状態値

### 5.1 mfa_method

| Value | Meaning |
| --- | --- |
| `GraphicalMatrix` | GraphicalMatrix画像選択を第2要素として利用する。 |
| `TOTP` | Shibboleth TOTP Pluginを利用する。TOTP seedは `totp_seed` に保存する。 |
| `WebAuthn` | Shibboleth WebAuthn Pluginを利用する。credentialは `storagerecords` に保存する。 |

### 5.2 status

| Value | Meaning |
| --- | --- |
| `ACTIVE` | 認証可能。 |
| `DISABLED` | 管理操作により停止中。認証対象として扱わない。 |

### 5.3 totp_status

| Value | Meaning |
| --- | --- |
| `UNREGISTERED` | TOTP seed未登録。 |
| `PENDING` | TOTP seed発行済み、確認コード検証前。 |
| `ACTIVE` | TOTP登録完了。 |

## 6. 更新タイミング

| Operation | Main Updates |
| --- | --- |
| ユーザー作成 | `graphicalmatrix_enrollment` に1行追加する。 |
| GraphicalMatrix認証成功 | `failed_count=0`、`locked_until=0`、`last_success_at`、`updated_at` を更新する。 |
| GraphicalMatrix認証失敗 | `failed_count` を加算し、閾値到達時に `locked_until` を設定する。 |
| GraphicalMatrix変更完了 | `sequence` を更新し、`force_sequence_change=0` に戻す。 |
| MFA方式変更 | `mfa_method` を更新し、必要に応じてTOTP状態や失敗回数を初期化する。 |
| TOTP登録開始 | `totp_seed` を発行し、`totp_status=PENDING` にする。 |
| TOTP登録完了 | `totp_status=ACTIVE`、`totp_registered_at`、`last_success_at` を更新する。 |
| WebAuthn登録 | Shibboleth WebAuthn Pluginが `storagerecords` を更新する。 |
| WebAuthn reset/delete | 管理CLIが `storagerecords.value` を更新または行削除する。 |

認証時のGraphicalMatrix/TOTP関連更新では、ユーザー行を `FOR UPDATE` で取得して同時更新を抑制する。

## 7. 保存データの保護

`sequence` と `totp_seed` は秘密情報として扱う。

`sequence` の保存方式は `graphicalmatrix.sequence.storage` で制御する。
主な方式は以下である。

- `plaintext`
- `keyword`
- `aes-gcm`
- `hash`

`totp_seed` の保存方式は `graphicalmatrix.totp.seed.storage` で制御する。
TOTP seedは認証時に復元が必要なため、`hash` は利用しない。

WebAuthn credentialはShibboleth WebAuthn Pluginの形式で `storagerecords.value` に保存される。
credentialの構造はGraphicalMatrix側で独自定義しない。

## 8. 権限設計

本番運用ではIdP runtime用DBユーザーと管理用DBユーザーを分離する。

例:

```sql
GRANT CONNECT ON DATABASE graphicalmatrix TO graphicalmatrix_app, graphicalmatrix_admin;
GRANT USAGE ON SCHEMA public TO graphicalmatrix_app, graphicalmatrix_admin;

GRANT SELECT, INSERT, UPDATE ON graphicalmatrix_enrollment TO graphicalmatrix_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON storagerecords TO graphicalmatrix_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON graphicalmatrix_enrollment TO graphicalmatrix_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON storagerecords TO graphicalmatrix_admin;
```

IdP runtimeに `graphicalmatrix_enrollment` の `DELETE` を付与しないことで、
通常認証処理からのユーザー削除を避ける。
WebAuthnはShibboleth Pluginの登録・削除動作により `storagerecords` の削除が必要になる場合がある。

## 9. DDL適用方針

本番では `graphicalmatrix.db.autoInit=false` とし、
`postgresql-schema.sql` を事前にDB管理者またはDDL権限を持つユーザーで適用する。

PoCや検証環境では `graphicalmatrix.db.autoInit=true` により、
アプリケーション起動時に不足カラムを追加できる。
ただし、本番では起動時DDLを避ける。

`storagerecords` はWebAuthn DB永続化を利用する場合に別途作成する。
このテーブルはGraphicalMatrix本体の `postgresql-schema.sql` には含めず、
Shibboleth JDBC StorageService構成の一部として管理する。

## 10. 運用確認SQL

ユーザー件数:

```sql
SELECT count(*) FROM graphicalmatrix_enrollment;
```

MFA方式別件数:

```sql
SELECT mfa_method, count(*)
FROM graphicalmatrix_enrollment
GROUP BY mfa_method
ORDER BY mfa_method;
```

ロック中ユーザー:

```sql
SELECT user_id, failed_count, locked_until
FROM graphicalmatrix_enrollment
WHERE locked_until > (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
ORDER BY locked_until;
```

TOTP登録状態:

```sql
SELECT totp_status, count(*)
FROM graphicalmatrix_enrollment
GROUP BY totp_status
ORDER BY totp_status;
```

WebAuthn StorageService件数:

```sql
SELECT context, count(*)
FROM storagerecords
GROUP BY context
ORDER BY context;
```

DB上のGraphicalMatrix sequence確認:

```sql
SELECT user_id, sequence
FROM graphicalmatrix_enrollment
WHERE user_id = 'test01';
```

`sequence` が `kw1:` または `aesgcm1:` で始まる場合は、
IdPサーバ上で `GraphicalMatrixSequenceTool` を使って表示できる。
`keyword` 方式では `graphicalmatrix.sequence.keywordFile`、
`aes-gcm` 方式では `graphicalmatrix.sequence.aesKeyFile` が必要である。

```bash
STORED="$(PGPASSWORD="$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password)" \
  psql "host=db-graphicalmatrix.example.com port=5432 dbname=graphicalmatrix user=graphicalmatrix_app sslmode=verify-full sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt" \
  -Atqc "SELECT sequence FROM graphicalmatrix_enrollment WHERE user_id = 'test01';")"

CP="$(find /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 \
  -type f \
  -name '*.jar' \
  -print | sort | paste -sd ':' -)"

sudo java -cp "$CP" \
  io.github.yasakawa.faskw.GraphicalMatrixSequenceTool \
  display \
  /opt/shibboleth-idp \
  "$STORED"
```

表示例:

```text
img03,img07,img11,img14
```

`hsp1:` で始まる `hash` 方式のsequenceは復号できない。
この場合は保存値から元の画像列を表示できず、認証時の照合のみ可能である。

secret fileの内容を `cat` 等で画面に表示するとログや端末履歴に残る可能性があるため、
通常は `GraphicalMatrixSequenceTool` 経由で確認する。

## 11. 今後の検討

- `status`、`mfa_method`、`totp_status` のCHECK制約を追加するか。
- `created_at`、`updated_at` を `BIGINT` からDB timestamp型へ変更するか。
- `storagerecords` の管理責任範囲をShibboleth文書側に分離するか。
- 大規模運用時の追加インデックス、パーティショニング、監査テーブル要否。
- `DELETE` ではなく論理削除を採用するか。
