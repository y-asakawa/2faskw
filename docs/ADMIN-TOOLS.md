# 2FAS-KW Admin Tools

## 概要

2FAS-KW Admin Tools は、IdPプラグイン本体をインストールせずに、
DB管理CLIだけをDBサーバまたは管理端末へ導入するための配布物である。

用途:

- GraphicalMatrixユーザー登録/変更/削除
- CSV import/export
- TOTP seed管理
- WebAuthn credential確認/削除
- sequence保存方式の確認/移行

この配布物はJetty、`web.xml`、Shibboleth IdPの認証設定を変更しない。

## 配布物

```text
2faskw-admin-tools-1.0.1/
  bin/
    graphicalmatrix-db.sh
    graphicalmatrix-db-migration.sh
    graphicalmatrix-admin-install.sh

  lib/
    2faskw-idp-plugin-1.0.1.jar
    HikariCP-6.3.0.jar
    postgresql-42.7.11.jar
    core-3.5.3.jar

  conf/graphicalmatrix/
    db.properties.adminnew
    graphicalmatrix.properties.adminnew
    admin.properties.adminnew
    postgresql-schema.sql

  examples/systemd/
    graphicalmatrix-csv-import.path
    graphicalmatrix-csv-import.service

  docs/
    ADMIN-TOOLS.md
    CSV-EXPORT.md
    DB-MIGRATION.md
    SEQUENCE-STORAGE-MIGRATION.md
    SECURITY.md
```

## 必須アプリ

PostgreSQL運用の場合:

```text
Java 21
psql
bash
```

H2はPoC用途のみ。
Admin Tools単体運用では、PostgreSQL接続を標準とする。

## セキュリティ方針

Admin ToolsはDB更新権限を持つため、本番環境では「ZIPを配置しただけで使える」
状態にしない。

公開配布時の標準方針:

```text
1. Admin Toolsはデフォルト無効
2. Admin Tools単体ZIPにはサンプル設定のみ同梱
3. DBパスワード、秘密鍵、client certificateは同梱しない
4. 初回セットアップでPostgreSQL TLS client certificateを必須化できる
5. 本番モードではplaintext sequenceを拒否
6. 書き込み操作はdry-run標準、--apply必須
7. CLI監査ログを必須化
```

本番で必須にする防御:

```text
Admin Tools専用DBロール
接続元IP制限
PostgreSQL TLS client certificate認証
Admin Tools起動時のグループ/ホスト/証明書チェック
更新系操作のdry-run標準化
CLI監査ログ
```

この設計の目的は、Admin Toolsを入手した第三者がIPアドレスやDB接続先を推測しても、
DBを書き換えられないようにすることである。

## インストール

展開:

```bash
cd /tmp
unzip 2faskw-admin-tools-1.0.1.zip
cd 2faskw-admin-tools-1.0.1
```

dry-run:

```bash
sudo ./bin/graphicalmatrix-admin-install.sh \
  --prefix /opt/graphicalmatrix-admin
```

適用:

```bash
sudo ./bin/graphicalmatrix-admin-install.sh \
  --prefix /opt/graphicalmatrix-admin \
  --apply
```

既存の `/opt/graphicalmatrix-admin` がある場合は、以下の形式でバックアップする。

```text
/opt/graphicalmatrix-admin.bak.YYYYMMDDHHMMSS
```

## DB接続設定

`/opt/graphicalmatrix-admin/conf/graphicalmatrix/db.properties` を編集する。

検証用の最小例:

```properties
graphicalmatrix.db.driver=org.postgresql.Driver
graphicalmatrix.db.url=jdbc:postgresql://192.168.0.64:5432/graphicalmatrix
graphicalmatrix.db.user=graphicalmatrix_admin
graphicalmatrix.db.passwordFile=/opt/graphicalmatrix-admin/credentials/graphicalmatrix-db.password
```

本番推奨例:

```properties
graphicalmatrix.db.driver=org.postgresql.Driver
graphicalmatrix.db.url=jdbc:postgresql://192.168.0.64:5432/graphicalmatrix?sslmode=verify-full&sslrootcert=/opt/graphicalmatrix-admin/credentials/db-ssl/db-ca.crt&sslcert=/opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.crt&sslkey=/opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.pk8
graphicalmatrix.db.user=graphicalmatrix_admin
graphicalmatrix.db.passwordFile=/opt/graphicalmatrix-admin/credentials/graphicalmatrix-db.password
```

本番では以下を満たす。

```text
sslmode=verify-full
sslrootcertを指定
Admin Tools専用client certificateを指定
DBユーザーは graphicalmatrix_admin
IdP runtime用 graphicalmatrix_app とは分離
```

パスワードファイル:

```bash
sudo install -d -m 0750 /opt/graphicalmatrix-admin/credentials
sudo install -m 0640 /dev/null /opt/graphicalmatrix-admin/credentials/graphicalmatrix-db.password
sudo vi /opt/graphicalmatrix-admin/credentials/graphicalmatrix-db.password
```

TLS client certificate配置例:

```bash
sudo install -d -m 0750 /opt/graphicalmatrix-admin/credentials/db-ssl
sudo install -m 0640 db-ca.crt /opt/graphicalmatrix-admin/credentials/db-ssl/db-ca.crt
sudo install -m 0640 admin-client.crt /opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.crt
sudo install -m 0600 admin-client.pk8 /opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.pk8
sudo chown -R root:graphicalmatrix-admin /opt/graphicalmatrix-admin/credentials
```

PostgreSQL JDBC driverは秘密鍵にPKCS#8形式を要求する。
PEM秘密鍵をPKCS#8 DERへ変換する例:

```bash
openssl pkcs8 -topk8 -inform PEM -outform DER \
  -in admin-client.key \
  -out admin-client.pk8 \
  -nocrypt
```

秘密鍵にパスフレーズを付ける場合は、JDBC URLまたは接続設定で
`sslpassword` / `sslpasswordcallback` の扱いを別途設計する。
無人運用ではOS権限、ファイル暗号化、secret管理で保護する。

## PostgreSQL側の制限

Admin Tools用DBユーザーをIdP runtime用DBユーザーと分離する。

```sql
CREATE ROLE graphicalmatrix_app LOGIN PASSWORD '<idp runtime password>';
CREATE ROLE graphicalmatrix_admin LOGIN PASSWORD '<admin tools password>';

GRANT CONNECT ON DATABASE graphicalmatrix TO graphicalmatrix_app, graphicalmatrix_admin;
GRANT USAGE ON SCHEMA public TO graphicalmatrix_app, graphicalmatrix_admin;

-- IdP runtimeは認証処理に必要な最小権限
GRANT SELECT, INSERT, UPDATE ON graphicalmatrix_enrollment TO graphicalmatrix_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON storagerecords TO graphicalmatrix_app;

-- Admin Toolsは管理操作用
GRANT SELECT, INSERT, UPDATE, DELETE ON graphicalmatrix_enrollment TO graphicalmatrix_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON storagerecords TO graphicalmatrix_admin;
```

接続元IPとclient certificateをPostgreSQL側で制限する。

例:

```conf
# IdP runtime
hostssl graphicalmatrix graphicalmatrix_app   <idp1-ip>/32         scram-sha-256
hostssl graphicalmatrix graphicalmatrix_app   <idp2-ip>/32         scram-sha-256

# Admin Tools
hostssl graphicalmatrix graphicalmatrix_admin <admin-tools-ip>/32  scram-sha-256 clientcert=verify-full
```

Admin Tools用client certificateのCNをDBユーザー名と一致させる運用にすると、
PostgreSQLの証明書検証とDBユーザーを結び付けやすい。

```text
client certificate CN = graphicalmatrix_admin
DB user               = graphicalmatrix_admin
```

DB側firewalldでも、`5432/tcp` はIdPサーバとAdmin Toolsサーバからのみ許可する。

```bash
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<idp1-ip>/32" port port="5432" protocol="tcp" accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="<admin-tools-ip>/32" port port="5432" protocol="tcp" accept'
sudo firewall-cmd --reload
```

公開配布物にDBパスワード、client certificate、秘密鍵、sequence secretを含めてはならない。

## IdP側設定との同期

Admin ToolsをIdPとは別のDBサーバまたは管理端末で動かす場合、
以下はIdP側と同じ内容にする。

```text
DB接続設定
graphicalmatrix.properties
sequence保存用secret
```

具体的には、以下を揃える。

```text
db.properties:
  graphicalmatrix.db.driver
  graphicalmatrix.db.url
  graphicalmatrix.db.user
  graphicalmatrix.db.passwordFile

graphicalmatrix.properties:
  graphicalmatrix.choice
  graphicalmatrix.graphicals
  graphicalmatrix.not_graphicals
  graphicalmatrix.aliases
  graphicalmatrix.allow_duplicates
  graphicalmatrix.order
  graphicalmatrix.sequence.storage
  graphicalmatrix.productionMode
  graphicalmatrix.totp.seed.storage

sequence保存用secret:
  graphicalmatrix.sequence.keywordFile
  graphicalmatrix.sequence.aesKeyFile
  graphicalmatrix.sequence.pepperFile
  graphicalmatrix.totp.seed.keywordFile
  graphicalmatrix.totp.seed.aesKeyFile
```

これらがIdP側とずれると、以下の問題が起きる。

- alias変換結果がIdP認証時と変わる
- 選択数チェックがIdPと一致しない
- 許可画像/除外画像の判定が一致しない
- 重複選択の許可/禁止が一致しない
- sequence保存形式が一致せず、認証に失敗する
- hash / 暗号化方式でsecretが不足し、登録やCSV importができない

本番では、Admin Tools用の `graphicalmatrix.properties` とsecretファイルを
構成管理または安全な同期手順でIdP側と揃える。
secretファイルを同期する場合は、権限を `0640` 以下にし、配布先OSユーザーを制限する。

## 実行

```bash
export GRAPHICALMATRIX_HOME=/opt/graphicalmatrix-admin
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh list
```

環境変数を使わない場合でも、`graphicalmatrix-db.sh` は自身の親ディレクトリを
`GRAPHICALMATRIX_HOME` 相当として扱う。

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh show test-user001
```

設定項目のHELP:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh config-help
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh config-help graphicalmatrix.admin.csv.autoApply
```

詳細は `docs/CONFIG-REFERENCE.md` を参照する。

## 起動時セキュリティチェック設計

本番モードでは、Admin Tools起動時に以下を検査する。

設定例:

```properties
graphicalmatrix.admin.enabled = false
graphicalmatrix.admin.requiredGroup = graphicalmatrix-admin
graphicalmatrix.admin.allowedHosts = db-admin01,192.168.0.62
graphicalmatrix.admin.requireClientCert = true
graphicalmatrix.admin.clientCertPath = /opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.crt
graphicalmatrix.admin.productionMode = true
graphicalmatrix.admin.rejectPlaintextSequence = true

graphicalmatrix.admin.provisioning.enabled = false
graphicalmatrix.admin.csv.incomingDir = /opt/graphicalmatrix-admin/incoming
graphicalmatrix.admin.csv.processingDir = /opt/graphicalmatrix-admin/processing
graphicalmatrix.admin.csv.processedDir = /opt/graphicalmatrix-admin/processed
graphicalmatrix.admin.csv.failedDir = /opt/graphicalmatrix-admin/failed
graphicalmatrix.admin.csv.logFile = /opt/graphicalmatrix-admin/logs/csv-import.log
graphicalmatrix.admin.csv.lockFile = /opt/graphicalmatrix-admin/logs/csv-import.lock
graphicalmatrix.admin.csv.autoApply = false
graphicalmatrix.admin.csv.autoApplyActions = A,M
graphicalmatrix.admin.csv.autoApplyDeprovision = true
graphicalmatrix.admin.csv.maxRows = 10000
graphicalmatrix.admin.csv.maxDisables = 1000
```

検査内容:

```text
graphicalmatrix.admin.enabled が true であること
実行OSユーザーが graphicalmatrix.admin.requiredGroup に所属していること
実行ホスト名またはIPが graphicalmatrix.admin.allowedHosts に含まれること
graphicalmatrix.admin.requireClientCert=true の場合、clientCertPath が読めること
productionMode=true かつ rejectPlaintextSequence=true の場合、graphicalmatrix.sequence.storage が plaintext ではないこと
CSV provisioning runnerの場合、csv-import.log / lockFile / 処理ディレクトリへ書き込めること
```

本番モードでは `graphicalmatrix.sequence.storage = plaintext` を拒否する。

許可:

```text
hash
keyword
aes-gcm
```

拒否:

```text
plaintext
```

起動時チェックに失敗した場合、DB接続前に終了する。

```text
ERROR: Admin Tools is disabled.
ERROR: current user is not in required group: graphicalmatrix-admin
ERROR: current host is not allowed: <hostname>
ERROR: client certificate is required.
ERROR: plaintext sequence storage is not allowed in production mode.
```

## 管理CLI例

ユーザー登録:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh add test-user001 A B C D --apply
```

MFA方式変更:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh set-method test-user001 TOTP --apply
```

RESET:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh test-user001 RESET --apply
```

CSV import:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh csv /path/to/users.csv --apply
```

CSV export:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh csv-export /secure/backup/graphicalmatrix-users.csv
```

WebAuthn credential確認:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh webauthn-list
```

## dry-run / --apply設計

本番では、読み取り系以外のコマンドはdry-runを標準にする。

読み取り系:

```text
list
show
csv-export
sequence-mode
webauthn-list
```

書き込み系:

```text
add
modify
delete
set-method
set-initial-sequence
reset
unlock
csv
totp-reset
webauthn-reset
webauthn-delete
migrate-sequence
```

書き込み系は `--apply` がない場合、実行予定内容だけを表示する。

```text
DRY-RUN:
  action: MODIFY
  user_id: test-user001
  mfa_method: GraphicalMatrix -> TOTP
  force_sequence_change: 0 -> 1

No changes were applied.
Re-run with --apply to execute.
```

CSV importは件数と操作種別を表示し、`--apply` と確認入力を両方要求する。

```text
CSV plan:
  ADD:    10
  MODIFY: 20
  DELETE:  1

Type YES to continue:
```

危険操作では、対象ユーザー数が閾値を超える場合に拒否または追加確認を行う。

```properties
graphicalmatrix.admin.maxDeleteWithoutForce = 10
graphicalmatrix.admin.requireConfirmText = YES
```

## CLI監査ログ設計

Admin Toolsの操作は、DB更新の成否に関係なく監査ログへ記録する。

ログ先例:

```text
/opt/graphicalmatrix-admin/logs/admin-audit.log
```

権限:

```bash
sudo install -d -m 0750 -o root -g graphicalmatrix-admin /opt/graphicalmatrix-admin/logs
sudo install -m 0640 -o root -g graphicalmatrix-admin /dev/null \
  /opt/graphicalmatrix-admin/logs/admin-audit.log
```

ログ項目:

```text
timestamp
event
result
os_user
effective_user
host
source_ip
db_url_hash
db_user
target_user
action
dry_run
apply
rows_affected
detail
```

例:

```text
ts=<timestamp> event=ADMIN_CLI action=set-method target_user=test-user001 result=OK os_user=admin01 host=db-admin01 dry_run=false rows=1 detail=mfa_method=TOTP
```

ログには以下を出さない。

```text
DBパスワード
sequence secret
TOTP seed
client private key
平文sequence
```

`db_url_hash` は接続先識別用に、JDBC URL全体ではなくハッシュ値を記録する。

## 運用上の注意

- DBサーバへ配置する場合でも、管理CLI実行ユーザーを制限する。
- `db.properties` とDBパスワードファイルの権限を厳格にする。
- 本番では `graphicalmatrix_app` と `graphicalmatrix_admin` のDBユーザーを分ける。
- 本番ではAdmin Tools用のPostgreSQL TLS client certificateを必須にする。
- 本番ではAdmin Tools実行ホストを限定する。
- 本番ではAdmin Tools実行OSグループを限定する。
- 本番では `plaintext` sequence保存方式を拒否する。
- 本番では書き込み系操作に `--apply` を必須にする。
- 本番ではCLI監査ログを必須にする。
- 管理CLIはDB VIPへ接続する。
- DB直接SQL更新ではなく、Admin ToolsまたはAPI経由の管理を標準とする。
- sequence保存方式が `hash` または暗号化の場合、直接SQL登録は避ける。

## 公開配布時の同梱方針

Admin Tools単体ZIPに同梱してよいもの:

```text
JAR
CLIスクリプト
サンプルdb.properties
サンプルgraphicalmatrix.properties
サンプルDDL
README / SECURITY / ADMIN-TOOLS
```

同梱してはいけないもの:

```text
DBパスワード
client certificate
client private key
CA秘密鍵
sequence keyword
AES key
pepper
実環境のdb.properties
実ユーザーCSV
```

初期導入時は、管理者が以下を明示的に配置して初めて利用可能にする。

```text
/opt/graphicalmatrix-admin/conf/graphicalmatrix/db.properties
/opt/graphicalmatrix-admin/conf/graphicalmatrix/graphicalmatrix.properties
/opt/graphicalmatrix-admin/credentials/graphicalmatrix-db.password
/opt/graphicalmatrix-admin/credentials/db-ssl/db-ca.crt
/opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.crt
/opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.pk8
/opt/graphicalmatrix-admin/credentials/<sequence secret>
```

## プロビジョニング連携設計

外部アカウント管理システムからCSVをSCPで転送し、Admin Toolsでユーザー追加/変更を行う運用は可能。
ただし、CSV到着だけで無条件にDB更新すると危険なため、以下の段階的な方式を推奨する。

推奨構成:

```text
Provisioning system
  ↓ scp
Admin Tools server
  /opt/graphicalmatrix-admin/incoming/*.csv
  ↓ systemd path unit
  ↓ import runner
  ↓ dry-run
  ↓ policy check
  ↓ --apply
  ↓ processed/ or failed/
```

ディレクトリ構成:

```text
/opt/graphicalmatrix-admin/incoming
/opt/graphicalmatrix-admin/processing
/opt/graphicalmatrix-admin/processed
/opt/graphicalmatrix-admin/failed
/opt/graphicalmatrix-admin/logs
```

権限設計:

```text
graphicalmatrix-provision:
  SCP専用ユーザー
  incomingへの書き込みのみ許可
  shellはnologinまたは制限shell

graphicalmatrix-admin:
  Admin Tools実行ユーザー/グループ
  incomingを読み取り、processing/processed/failed/logsへ書き込み可能
  db.properties、DB password、client cert、sequence secretを読み取り可能
```

作成例:

```bash
sudo groupadd graphicalmatrix-provision
sudo useradd -r -s /sbin/nologin -g graphicalmatrix-provision graphicalmatrix-provision

sudo install -d -m 0750 -o root -g graphicalmatrix-provision \
  /opt/graphicalmatrix-admin/incoming
sudo install -d -m 0750 -o root -g graphicalmatrix-admin \
  /opt/graphicalmatrix-admin/processing
sudo install -d -m 0750 -o root -g graphicalmatrix-admin \
  /opt/graphicalmatrix-admin/processed
sudo install -d -m 0750 -o root -g graphicalmatrix-admin \
  /opt/graphicalmatrix-admin/failed
sudo install -d -m 0750 -o root -g graphicalmatrix-admin \
  /opt/graphicalmatrix-admin/logs
```

SCP受け口は、可能なら `authorized_keys` の `command=` 制限やSFTP chrootで
`incoming` 以外へ書き込めないようにする。

systemd path unit例:

```ini
# /etc/systemd/system/graphicalmatrix-csv-import.path
[Unit]
Description=Watch GraphicalMatrix provisioning CSV directory

[Path]
PathChanged=/opt/graphicalmatrix-admin/incoming
Unit=graphicalmatrix-csv-import.service

[Install]
WantedBy=multi-user.target
```

systemd service例:

```ini
# /etc/systemd/system/graphicalmatrix-csv-import.service
[Unit]
Description=Import GraphicalMatrix provisioning CSV
After=network-online.target

[Service]
Type=oneshot
User=graphicalmatrix-admin
Group=graphicalmatrix-admin
ExecStart=/opt/graphicalmatrix-admin/bin/graphicalmatrix-csv-import-runner.sh
```

有効化:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now graphicalmatrix-csv-import.path
```

import runner例:

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE=/opt/graphicalmatrix-admin
IN="$BASE/incoming"
WORK="$BASE/processing"
DONE="$BASE/processed"
FAIL="$BASE/failed"
LOG="$BASE/logs/csv-import.log"

shopt -s nullglob

for file in "$IN"/*.csv; do
  name="$(basename "$file")"
  ts="$(date +%Y%m%d%H%M%S)"
  work="$WORK/$ts-$name"

  mv "$file" "$work"

  {
    echo "ts=$(date -Is) event=CSV_IMPORT_START file=$name"

    "$BASE/bin/graphicalmatrix-db.sh" csv "$work"

    "$BASE/bin/graphicalmatrix-db.sh" csv "$work" --apply

    mv "$work" "$DONE/$ts-$name"
    echo "ts=$(date -Is) event=CSV_IMPORT_OK file=$name"
  } >> "$LOG" 2>&1 || {
    rc=$?
    mv "$work" "$FAIL/$ts-$name" 2>/dev/null || true
    echo "ts=$(date -Is) event=CSV_IMPORT_FAIL file=$name rc=$rc" >> "$LOG"
  }
done
```

本番では、最初から完全自動applyにしない。
推奨する段階:

```text
Phase 1:
  CSV受信
  dry-runのみ
  ログと処理計画を確認

Phase 2:
  A/Mのみ自動apply
  Dは物理削除せずユーザー停止として自動apply

Phase 3:
  運用実績が安定した場合も、プロビジョニングDはユーザー停止のままとする
  物理削除は管理者の明示的なpurge操作だけ許可
```

設定案:

```properties
graphicalmatrix.admin.csv.autoApply = false
graphicalmatrix.admin.csv.autoApplyActions = A,M
graphicalmatrix.admin.csv.deprovisionAction = disable
graphicalmatrix.admin.csv.autoApplyDeprovision = true
graphicalmatrix.admin.csv.requireManualApplyActions =
graphicalmatrix.admin.csv.maxRows = 10000
graphicalmatrix.admin.csv.maxDisables = 1000
graphicalmatrix.admin.csv.maxPurges = 0
graphicalmatrix.admin.csv.requireConfirmText = YES
```

プロビジョニング用途では、CSVの `D` は物理削除ではなく停止として扱う。

```text
A: 追加。既存ユーザーがDISABLEDなら再有効化して更新する
M: 変更。既存ユーザーを更新する
D: 停止。status=DISABLED にしてログイン不可にする
```

停止時のDB更新:

```sql
UPDATE graphicalmatrix_enrollment
SET status = 'DISABLED',
    failed_count = 0,
    locked_until = 0,
    updated_at = <now>
WHERE user_id = <user_id>;
```

停止時に残す情報:

```text
user_id
mfa_method
initial_sequence
sequence
totp_seed / totp_status
WebAuthn credential
created_at
last_success_at
```

残す理由:

```text
誤停止から復旧しやすい
監査・問い合わせ対応で過去状態を確認できる
再雇用・再登録時に復旧ポリシーを選べる
プロビジョニング誤配信で即時データ消失しない
```

再有効化ポリシー:

```text
Aで既存DISABLEDユーザーが来た場合:
  status=ACTIVEへ戻す
  CSVのmfa_method, force_sequence_change, initial_sequence, sequenceで更新する

Mで既存DISABLEDユーザーが来た場合:
  標準ではstatusはDISABLEDのまま属性だけ更新する
  設定で再有効化を許可できる
```

設定案:

```properties
graphicalmatrix.admin.csv.addExistingDisabled = reactivate
graphicalmatrix.admin.csv.modifyDisabled = keep-disabled
```

物理削除はプロビジョニングCSVでは行わない。
退職者データの保存期間満了後など、管理者が明示的に実行する。

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh purge test-user001 --apply
```

物理削除時は、関連するTOTP seed、WebAuthn credential、GraphicalMatrix登録情報を削除対象にできる。

```text
purge:
  graphicalmatrix_enrollmentを削除
  WebAuthn credentialを削除
  TOTP seedを削除
```

将来スキーマ拡張する場合は、停止日時と停止理由を追加する。

```sql
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN deprovisioned_at BIGINT DEFAULT 0;
ALTER TABLE graphicalmatrix_enrollment ADD COLUMN deprovision_reason VARCHAR(255);
```

この拡張を入れると、停止済みユーザーの棚卸しや保存期間満了後のpurgeが容易になる。

CSV自動処理時の必須チェック:

```text
ファイル名が許可パターンに一致すること
拡張子が .csv であること
ファイルサイズが上限以下であること
CSVヘッダ/列数/必須値が正しいこと
actionが A/M/D のみであること
Dの件数が停止上限以下であること
プロビジョニングCSVで物理削除を要求していないこと
dry-runでエラーがないこと
Admin Toolsの起動時セキュリティチェックに成功していること
CLI監査ログへ書き込めること
```

処理後の保管:

```text
processed:
  正常処理済みCSVを一定期間保管

failed:
  dry-run失敗、policy違反、apply失敗のCSVを保管

logs:
  dry-run結果、apply結果、件数、処理ユーザー、処理ホストを記録
```

監査ログにはCSVの全内容を出さない。
ユーザーID、action、件数、ファイル名、ハッシュ値、結果を記録する。

```text
ts=<timestamp> event=CSV_IMPORT_PLAN file=users-<date>.csv sha256=<hash> add=100 modify=20 delete=0 result=OK
ts=<timestamp> event=CSV_IMPORT_APPLY file=users-<date>.csv sha256=<hash> rows=120 result=OK
```

セキュリティ上の注意:

```text
SCP専用ユーザーにAdmin Toolsのcredentialを読ませない
incoming配下のCSVを直接実行しない
processingへ移動してから処理する
同じCSVの二重処理を避けるためファイルハッシュを記録する
プロビジョニングDは物理削除ではなく停止として処理する
物理削除はプロビジョニングCSVでは許可しない
処理済みCSVは保存期間を決めて削除する
```

## IdPプラグイン本体との関係

IdPサーバには `2faskw-idp-plugin-1.0.1.zip` を導入する。
DBサーバまたは管理端末には `2faskw-admin-tools-1.0.1.zip` を導入する。

両者は同じJARを利用するが、役割は分離する。

```text
IdP plugin:
  認証処理、Servlet、View、TOTP/WebAuthn連携

Admin Tools:
  管理CLI、CSV、sequence migration、WebAuthn credential管理
```

## 実装済みプロビジョニング機能

実装済みのCLI:

```bash
graphicalmatrix-db.sh csv FILE --provisioning
graphicalmatrix-db.sh csv FILE --provisioning --apply
graphicalmatrix-db.sh provisioning-csv FILE --apply
```

動作:

```text
A: 新規追加。既存ユーザーがある場合は再有効化して更新する
M: 既存ユーザーを更新する
D: 物理削除ではなく status=DISABLED にする
```

実装済みファイル:

```text
bin/graphicalmatrix-csv-import-runner.sh
examples/systemd/graphicalmatrix-csv-import.path
examples/systemd/graphicalmatrix-csv-import.service
conf/graphicalmatrix/admin.properties.adminnew
```

`graphicalmatrix-csv-import-runner.sh` は以下を行う。

```text
admin.propertiesでAdmin Tools/provisioningが有効か確認
実行ユーザーのグループ確認
実行ホスト確認
client certificate必須設定の場合は証明書ファイル確認
productionModeでplaintext sequence拒否を有効化可能
incoming/*.csv を processing へ移動
dry-runを必ず実行
ポリシーを満たす場合のみ --apply
processed/failed/logsへ結果を保存
```

DB1検証環境への配置:

```text
server: 192.168.0.62 / db1.example.com
prefix: /opt/graphicalmatrix-admin
DB接続先: 192.168.0.64:5432 / DB VIP
DBユーザー: graphicalmatrix_admin
systemd path: graphicalmatrix-csv-import.path enabled
```

DB1で実施した主なコマンド:

```bash
sudo dnf -y install java-21-openjdk-headless

sudo groupadd -f graphicalmatrix-admin
sudo groupadd -f graphicalmatrix-provision
sudo useradd -r -s /sbin/nologin -g graphicalmatrix-admin graphicalmatrix-admin
sudo useradd -r -s /sbin/nologin -g graphicalmatrix-provision -G graphicalmatrix-admin graphicalmatrix-provision
sudo usermod -a -G graphicalmatrix-provision graphicalmatrix-admin

sudo bash /tmp/graphicalmatrix-admin-extract/2faskw-admin-tools-1.0.1/bin/graphicalmatrix-admin-install.sh \
  --prefix /opt/graphicalmatrix-admin \
  --apply

sudo install -d -m 0750 -o root -g graphicalmatrix-admin \
  /opt/graphicalmatrix-admin/credentials/db-ssl
sudo install -m 0640 -o root -g graphicalmatrix-admin \
  /tmp/graphicalmatrix-db-ca.crt \
  /opt/graphicalmatrix-admin/credentials/db-ssl/db-ca.crt

sudo install -m 0644 \
  /opt/graphicalmatrix-admin/examples/systemd/graphicalmatrix-csv-import.path \
  /etc/systemd/system/graphicalmatrix-csv-import.path
sudo install -m 0644 \
  /opt/graphicalmatrix-admin/examples/systemd/graphicalmatrix-csv-import.service \
  /etc/systemd/system/graphicalmatrix-csv-import.service
sudo systemctl daemon-reload
sudo systemctl enable --now graphicalmatrix-csv-import.path
```

DB1での確認コマンド:

```bash
sudo -u graphicalmatrix-admin env GRAPHICALMATRIX_HOME=/opt/graphicalmatrix-admin \
  /opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh list

sudo -u graphicalmatrix-admin env GRAPHICALMATRIX_HOME=/opt/graphicalmatrix-admin \
  /opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh csv /tmp/sample.csv --provisioning

sudo systemctl status graphicalmatrix-csv-import.path --no-pager -l
sudo systemctl start graphicalmatrix-csv-import.service
sudo journalctl -u graphicalmatrix-csv-import.service -n 50 --no-pager
sudo tail -n 100 /opt/graphicalmatrix-admin/logs/csv-import.log
```

DB1の現在のPoC設定では、sequence保存方式がまだ `plaintext` のため、
`admin.properties` は検証向けに以下としている。
本番ではsequence保存方式を `hash`、`keyword`、または `aes-gcm` へ移行してから
`productionMode=true` / `rejectPlaintextSequence=true` に戻す。

```properties
graphicalmatrix.admin.productionMode = false
graphicalmatrix.admin.rejectPlaintextSequence = false
```
