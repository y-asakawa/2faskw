# 2FAS-KW Command Reference

この文書は、導入後に使用する2FAS-KWおよび関連するShibboleth IdPの代表的なコマンドを
まとめたリファレンスである。設定項目の意味と既定値は
[CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)、作業手順と背景は各導入文書を参照する。

コマンド例はIdPを`/opt/shibboleth-idp`へ導入した構成を前提とする。`USER`、`SP_NAME`、
`ENTITY_ID`、`FILE`は実環境の値に置き換える。

## 1. 共通方針

- 読取りコマンド以外は、対象・出力・バックアップ方針を確認してから実行する。
- `graphicalmatrix-sp.sh`の変更操作とCSV/WebAuthn削除は、既定でdry-runである。内容を確認後、
  `--apply`を付ける。
- `graphicalmatrix-db.sh`の`add`、`set-method`、`unlock`、`delete`などは、指定した時点でDBを
  直接変更する。DB操作には原則として`--apply`を付けない。
- SP管理CLIはroot実行が必要である。DB管理CLIはIdP上では`sudo`で実行する。
- パスワード、sequence、TOTP seed、bearer token、秘密鍵を端末履歴・画面・通常ログへ出力しない。

### 1.1 例中の置換値

| 表記 | 指定する値 | 例・注意 |
| --- | --- | --- |
| `USER` | IdPで認証するユーザーID | `user001`。LDAPのuidなど、GraphicalMatrix enrollmentのユーザーIDを指定する。 |
| `SP_NAME` | 管理CLI内部で使うSP管理名 | `research-portal`。小文字、数字、`-`だけを使い、`[a-z0-9][a-z0-9-]{0,62}`に従う。entityIDや既存provider IDとは別の値である。 |
| `ENTITY_ID` | SP metadataの`entityID`属性の完全な値 | `https://sp.example.org/shibboleth`。URLの見た目ではなくmetadata内の文字列をそのまま指定する。 |
| `ATTRIBUTE` | IdP Attribute Resolverの属性ID | `uid`、`mail`、`businessCategory`など。LDAP属性名と異なる場合がある。 |
| `PROFILE` | 属性release用の管理profile名 | `uid-mail-release`。builtin profileは編集せず、追加・変更には管理profileを作成する。 |
| `FILE` | 読込みまたは出力するファイルパス | CSV、metadata XML、バックアップはroot以外が読めない保護済みディレクトリに置く。 |
| `SHA256_HEX` | CLIのdry-runまたは`check-update`に出たmetadata SHA-256 | 確認済みの値だけを`--approve-sha256`へ渡す。 |

`--confirm`は変更対象を人が確認したことを明示する安全装置である。`adopt`、`rollback`、
`restore-legacy`、`access set`では`ENTITY_ID`を指定し、`remove`では`SP_NAME`を指定する。

## 2. パスと基本確認

| 用途 | IdP同梱CLI | Admin Tools単体導入時 |
| --- | --- | --- |
| DB管理 | `/opt/shibboleth-idp/bin/graphicalmatrix-db.sh` | `/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh` |
| SP管理 | `/opt/shibboleth-idp/bin/graphicalmatrix-sp.sh` | 提供しない |
| 設定検査 | `/opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh` | パッケージ内の同名スクリプト |
| API token管理 | `/opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh` | 提供しない |
| 自動CSVプロビジョニング | 提供しない | `/opt/graphicalmatrix-admin/bin/graphicalmatrix-csv-import-runner.sh` |

### 2.1 Admin Tools専用の自動CSVプロビジョニング

Admin Toolsには、`incoming/`へ転送されたCSVを監視し、検証済みのCSVだけをDBへ反映する
自動プロビジョニング機能が含まれる。IdP同梱の`graphicalmatrix-db.sh`でも
`csv FILE --provisioning`は手動実行できるが、次の機能はAdmin Tools単体配布だけに含まれる。

- `graphicalmatrix-csv-import-runner.sh`: CSVの安全なsnapshot、dry-run、件数・操作種別の検査、必要時の`--apply`、監査ログ、処理済み・失敗ファイルへの振り分けを行う。
- `examples/systemd/graphicalmatrix-csv-import.path`と`graphicalmatrix-csv-import.service`: `incoming/`の変更を検知してrunnerを起動するsystemd unitの雛形。
- `incoming/`、`processing/`、`processed/`、`failed/`、`logs/`: CSV受信から結果保存までを分離するディレクトリ構成。
- `graphicalmatrix-admin-install.sh`: 上記ディレクトリ、DB管理CLI、JAR、設定テンプレート、systemd unit雛形を`/opt/graphicalmatrix-admin`へ配置するインストーラー。

`graphicalmatrix-db.sh`、`graphicalmatrix-db-migration.sh`はAdmin Toolsにも含まれるが、IdP同梱配布物にも
含まれる。SP管理CLIとAPI token管理CLIはAdmin Toolsには含まれない。

初期導入後の確認とsystemd unit有効化の例:

```bash
# Admin Toolsの自動プロビジョニング設定を確認する。enabled、受信先、autoApplyの既定値を読む。
sudo sed -n '1,220p' /opt/graphicalmatrix-admin/conf/graphicalmatrix/admin.properties

# systemd unit雛形をサービス定義へ配置する。既存ファイルがある場合は内容を比較してから上書きする。
sudo install -m 0644 \
  /opt/graphicalmatrix-admin/examples/systemd/graphicalmatrix-csv-import.path \
  /etc/systemd/system/graphicalmatrix-csv-import.path

# CSV取り込みserviceの雛形をサービス定義へ配置する。実行ユーザーとprefixが環境に合うか確認する。
sudo install -m 0644 \
  /opt/graphicalmatrix-admin/examples/systemd/graphicalmatrix-csv-import.service \
  /etc/systemd/system/graphicalmatrix-csv-import.service

# 新しいunitファイルをsystemdへ読み込ませる。
sudo systemctl daemon-reload

# incoming/の変更監視を有効化して直ちに開始する。serviceはCSV到着時にoneshotで起動する。
sudo systemctl enable --now graphicalmatrix-csv-import.path

# 監視unitが有効か確認する。active (waiting)が通常の待機状態である。
sudo systemctl status graphicalmatrix-csv-import.path --no-pager -l

# CSVを待たずに取り込みserviceを1回だけ手動起動する。事前にincoming/へCSVを置く。
sudo systemctl start graphicalmatrix-csv-import.service

# 直近の取り込み結果と失敗理由を確認する。
sudo journalctl -u graphicalmatrix-csv-import.service -n 50 --no-pager

# runner独自の監査ログを確認する。CSVのSHA-256、dry-run、apply、保存先が記録される。
sudo tail -n 100 /opt/graphicalmatrix-admin/logs/csv-import.log
```

自動反映を有効にする前に、`graphicalmatrix.admin.enabled`、
`graphicalmatrix.admin.provisioning.enabled`、`graphicalmatrix.admin.csv.autoApply`、
`graphicalmatrix.admin.csv.autoApplyActions`、`graphicalmatrix.admin.csv.maxRows`、
`graphicalmatrix.admin.csv.maxDisables`を確認する。既定では`autoApply = false`であり、CSVはdry-run後に
反映されない。詳細な受信権限、CSV形式、運用手順は[ADMIN-TOOLS.md](./ADMIN-TOOLS.md)を参照する。

日常確認:

```bash
# Jetty IdPサービスが稼働中かだけを確認する。終了code 0 / active が正常である。
sudo systemctl is-active jetty-idp.service

# GraphicalMatrix設定と参照先ファイルを検査する。DB接続は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only

# 2FAS-KW固有の直近認証イベントを確認する。sequenceやseedは表示されない。
sudo tail -n 50 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log

# IdP処理例外・metadata・Attribute Resolverの診断に使用する。
sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log
```

`graphicalmatrix-plugin-check.sh`の主な指定:

| Command | 用途 |
| --- | --- |
| `--package-only --package-dir DIR` | 展開済みpluginパッケージだけを検査する。 |
| `--idp-only --idp-home DIR` | IdP上の配置状態だけを検査する。 |
| `--config-only --idp-home DIR` | 設定ファイルと参照ファイルを検査する。DB接続は行わない。 |
| `--strict` | WARNも失敗として扱う。リリース前・変更後確認に使う。 |

## 3. DB管理CLI

この節は`graphicalmatrix.savedata = db`の場合だけに適用する。LDAP保存時はLDAPの管理手段を使う。
詳細なCSV仕様とAdmin Tools導入は[ADMIN-TOOLS.md](./ADMIN-TOOLS.md)を参照する。

### 3.1 照会

```bash
# DB上の全enrollment行を一覧表示する。値の変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list

# 1ユーザーのMFA方式、ロック、TOTP、日時などの管理項目を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh show USER

# 現在のsequence保存方式を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh sequence-mode

# 現在のTOTP seed保存方式を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh totp-seed-mode

# 保存方式移行の準備状況と未移行件数を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh security-status

# WebAuthn credentialを全件、または指定ユーザーだけ表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-list [USER]

# 設定リファレンス全体、または指定propertyの説明を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh config-help [PROPERTY]
```

表示時刻は既定でOSのタイムゾーン、取得できない場合はUTCとなる。表示だけ別のIANA timezoneへ
変更する場合は、実行時に`GRAPHICALMATRIX_TIME_ZONE=Asia/Tokyo`を指定する。

### 3.2 利用者・方式・ロック管理

次のコマンドはDBを即時変更する。

```bash
# sequenceを登録または更新する。画像IDまたはaliasを使用でき、DBを直ちに変更する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh add USER A B C D

# 現在のsequenceだけを更新する。初期sequenceは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-sequence USER A B C D

# USER RESET時に戻すplaintextの初期sequenceを設定する。DBを直ちに変更する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-initial-sequence USER A B C D

# 認証時に選択するMFA方式を変更する。GraphicalMatrix、TOTP、WebAuthnから指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method USER GraphicalMatrix

# 次回ログイン後にsequence変更を強制するかを設定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh require-change USER on

# enrollmentを有効化する。登録行は残したまま認証を許可する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh enable USER

# enrollmentを無効化する。登録行は残るがMFA認証を利用させない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh disable USER

# ロック期限と失敗回数を解除する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh unlock USER

# 指定分数だけユーザーをロックする。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh lock USER 15

# 失敗回数だけを0へ戻す。sequenceやロック期限は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh reset-failures USER

# RESETは初期sequenceへ戻し、ロック解除後にsequence変更を要求する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh USER RESET

# enrollment、GraphicalMatrix/TOTP情報、PostgreSQL StorageRecords内の関連WebAuthn credentialを物理削除する。
# 最初にDISABLEDへ変更し、credential削除が失敗した場合は停止状態を保持する。実行前にshowとDBバックアップを行う。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh delete USER
```

### 3.3 CSV、TOTP、WebAuthn、保存方式移行

```bash
# 標準CSVをdry-runする。DBは変更しない。D行は完全削除を保証できないため拒否される。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv

# 上記CSVを反映する。物理削除はCSVではなくdelete USERを使用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv --apply

# provisioning CSVをdry-runする。D行は無効化として扱う予定内容を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv --provisioning

# provisioning CSVを反映する。D行は物理削除ではなくDISABLEDへ変更する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv --provisioning --apply

# 現在のenrollmentをCSVへ出力する。既存FILEは上書きしない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export /secure/backup/users.csv

# 既存FILEを明示的に上書きしてCSVを出力する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export /secure/backup/users.csv --force

# TOTP seedを設定する。BASE32SEEDは端末履歴に残るため、本番では安全な入力方法を検討する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-totp-seed USER BASE32SEED

# 保存済みTOTP seedを削除する。次回利用には再登録が必要になる。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh clear-totp-seed USER

# TOTP登録状態を未登録へ戻す。seedの扱いは実装済み保存方式に従う。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh reset-totp USER

# v1.3.4以前から残ったPENDING TOTPの失効予定を表示する。seedは表示しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh invalidate-pending-totp

# 画像登録を保持し、現在のsequence保存方式と互換な旧PENDING利用者をGraphicalMatrixへ戻す。
# 旧seedと登録Bindingを消去する。MANUAL_RECOVERYは管理者が個別に復旧する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh invalidate-pending-totp --apply

# 指定ユーザーの全WebAuthn credential削除予定を表示する。DBは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-reset USER

# 指定ユーザーの全WebAuthn credentialを削除する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-reset USER --apply

# 1件のWebAuthn credentialを削除する。credential IDはwebAuthn-listで確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-delete USER \
  --credential-id CREDENTIAL_ID --apply

# sequence保存方式の移行予定を確認する。DBは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-sequence-storage

# sequence保存方式を実際に移行する。事前バックアップ後に実行する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-sequence-storage --apply

# TOTP seed保存方式の移行予定を確認する。DBは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-totp-seed-storage

# TOTP seed保存方式を実際に移行する。事前バックアップ後に実行する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-totp-seed-storage --apply

# 移行後の未移行件数と安全性条件を再確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh security-status
```

## 4. SP管理CLI

v1.3.0のSP管理CLIは、metadata、SP向け属性release、SP別MFA方針、属性カタログ、SP別LDAP属性
アクセス制御をIdPローカルで管理し、HTTP APIは提供しない。初回設定、metadata許可ホスト、削除・復元の詳細は
[INSTALL_NEW_SP.md](./INSTALL_NEW_SP.md)を参照する。

### 4.1 状態確認と初期化

```bash
# IdP設定から検出できるSP一覧を表示する。設定・registry・metadataの変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status

# 指定entityIDが手作業登録か管理対象かを確認する。移行前の確認に使用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status --entity-id 'ENTITY_ID'

# 管理対象SPだけを一覧表示する。属性profileとMFA profileも同時に確認できる。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list

# 管理対象に加え、metadata-providers.xmlから検出した手作業登録SPも一覧表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list --all

# 現在の設定状態から、次に実行すべきコマンドを変更なしで表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next

# 初回だけ、managed metadata providerとregistryを作成する予定内容を表示する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init

# 上記の初期化を反映する。metadata-providers.xmlと関連設定を変更する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init --apply
```

`init --apply`後はJettyを再起動し、起動完了後に`status`を確認する。2件目以降のSP追加で
`init`を繰り返さない。

### 4.2 追加・更新・確認

```bash
# metadata URLまたはmetadata fileを使う。まずdry-runでentityID、ACS、証明書、digestを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name SP_NAME \
  --entity-id 'ENTITY_ID' \
  --metadata-url 'https://sp.example.org/metadata' \
  --attribute-profile none \
  --mfa inherit

# 確認済みのSHA-256を指定して反映する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name SP_NAME \
  --entity-id 'ENTITY_ID' \
  --metadata-url 'https://sp.example.org/metadata' \
  --attribute-profile none \
  --mfa inherit \
  --approve-sha256 SHA256_HEX \
  --apply

# registry、metadataファイル、実行中IdPが同じSP情報を参照していることを検証する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify SP_NAME

# metadata URLの現在値を取得し、登録済みdigestとの差異だけを確認する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh check-update SP_NAME \
  --metadata-url 'https://sp.example.org/metadata'

# metadata fileを使う更新予定を表示する。取得したSHA-256とentityIDを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh update SP_NAME \
  --metadata-file /secure/path/sp-metadata.xml

# 上記で確認したSHA-256を承認してmetadata更新を反映する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh update SP_NAME \
  --metadata-file /secure/path/sp-metadata.xml \
  --approve-sha256 SHA256_HEX \
  --apply
```

`add`と`update`は、`--metadata-url`または`--metadata-file`のいずれかを指定する。
`--apply`では承認済みの`--approve-sha256`が必要である。`mfa bypass`などMFAを緩和する指定は、
CLIが追加の確認を要求する。

### 4.3 既存SPの移行、状態変更、履歴

```bash
# 手作業で登録済みのSPを取り込む予定を表示する。registry、metadataは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh adopt SP_NAME \
  --entity-id 'ENTITY_ID'

# 手作業SPを管理対象へ取り込む。confirmにはSP管理名ではなくentityIDを完全一致で指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh adopt SP_NAME \
  --entity-id 'ENTITY_ID' \
  --apply --confirm 'ENTITY_ID'

# 無効化済みSPを再び有効にする。metadataやattribute profileは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh enable SP_NAME --apply

# SPを無効化する。metadataを残したまま、そのSPをIdPで利用させない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh disable SP_NAME --apply

# 無効化済みSPをregistryとmanaged metadataから削除する。confirmにはSP管理名を指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh remove SP_NAME \
  --apply --confirm SP_NAME

# SPの変更履歴と復元可能なrevisionを表示する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh history SP_NAME

# 指定revisionへの復元予定を表示する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh rollback SP_NAME --revision N

# 指定revisionへ実際に復元する。confirmには対象SPのentityIDを完全一致で指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh rollback SP_NAME \
  --revision N --apply --confirm 'ENTITY_ID'

# adopt前のFilesystemMetadataProvider構成へ戻す。confirmには対象SPのentityIDを完全一致で指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh restore-legacy SP_NAME \
  --apply --confirm 'ENTITY_ID'
```

### 4.4 MFA方針

`set-mfa`は1件のSPに対する方針を管理する。`mfa`はIdP全体の既定方針、評価順、全SPに
適用される送信元IP例外を管理する。IdP全体設定の変更時も、CLI管理SPの`set-mfa`設定は保持される。

#### 4.4.1 SP単位の`set-mfa`

`SP_NAME`はentityIDやprovider IDではなく、`graphicalmatrix-sp.sh list`の`NAME`列に表示される
CLI管理名である。1件のSPには1つのMFA profileだけを割り当てる。再度`set-mfa`を実行すると、
以前のprofileは新しいprofileで置き換えられる。

```bash
# 対象SPの現在の管理名とMFA profileを確認する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list

# 対象SPをIdP全体方針に従わせる変更予定を表示する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME inherit

# 対象SPをIdP全体方針に従わせる設定を適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME inherit --apply

# 対象SPでMFAを強制する変更予定を表示する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME force

# 対象SPでMFAを強制する設定を適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME force --apply

# 対象SP全体でMFAを不要にする変更予定を表示する。明示確認が必要である。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME bypass \
  --confirm-bypass

# 対象SP全体でMFAを不要にする設定を適用する。適用時にも明示確認が必要である。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME bypass \
  --confirm-bypass \
  --apply

# 対象SPをrequiredSPsへ登録する変更予定を表示する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME required

# 対象SPをrequiredSPsへ登録する設定を適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME required --apply

# 対象SPかつ指定IPv4 CIDRの場合だけMFAを不要にする変更予定を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME sp-cidr-bypass \
  --cidrs '192.168.10.0/24,10.20.0.0/16'

# 対象SPかつ指定IPv4 CIDRの場合だけMFAを不要にする設定を適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa SP_NAME sp-cidr-bypass \
  --cidrs '192.168.10.0/24,10.20.0.0/16' \
  --apply

# SP単位設定とIdP全体設定を組み合わせた実効判定を確認する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp SP_NAME --ip 192.168.10.20
```

| profile | 動作 |
| --- | --- |
| `inherit` | SPを4つのSP別ルールから外し、IdP全体方針で判定する。 |
| `force` | 対象SPを`forceSPs`へ登録し、該当ルール到達時にMFAを要求する。 |
| `bypass` | 対象SPを`bypassSPs`へ登録し、該当ルール到達時に送信元IPに関係なくMFAを不要にする。 |
| `required` | 対象SPを`requiredSPs`へ登録する。ルール到達時、未登録SPはMFA不要になるため影響範囲に注意する。 |
| `sp-cidr-bypass` | 該当ルール到達時、対象SPと`--cidrs`の両方が一致した場合だけMFAを不要にする。IPv4 CIDRだけ指定できる。 |

`--cidrs`は`sp-cidr-bypass`だけで使用でき、少なくとも1件必要である。`required`を1件でも
設定すると、`requiredSPs`ルール到達時に、列挙されていないSPはMFA不要と判定される。
適用前後に`mfa test`で対象SPと代表的な送信元IPを確認する。

#### 4.4.2 IdP全体の`mfa`

```bash
# IdP全体のMFA設定、SP別設定、手作業による差分の有無を表示する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show

# 指定したSPと送信元IPについて、実効的な判定結果と一致したルールを表示する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp SP_NAME --ip 192.0.2.10

# IdP全体でMFAを免除するCIDRの変更予定とplan_sha256を表示する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --bypass-cidrs '192.168.0.0/16,10.0.0.0/8'

# dry-runで確認した完成後設定を適用する。MFA免除を増やすためconfirm-bypassも必要になる。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --bypass-cidrs '192.168.0.0/16,10.0.0.0/8' \
  --confirm-bypass \
  --approve-sha256 PLAN_SHA256 \
  --apply

# IdP全体のMFA免除CIDRを削除する予定を表示する。オプション省略では既存値は削除されない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --clear-bypass-cidrs

# CLI管理SPの手動差分を、SP管理台帳から復元する予定とplan_sha256を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa reconcile --from-registry

# 確認済みの復元内容を適用する。mfa-policy.propertiesのIdP全体設定は保持される。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa reconcile \
  --from-registry \
  --approve-sha256 PLAN_SHA256 \
  --apply
```

`mfa global set`で指定できる値は`--default`、`--policy-order`、`--bypass-ips`、
`--bypass-cidrs`である。未指定項目は現在値を保持する。IP一覧の削除には
`--clear-bypass-ips`、CIDR一覧の削除には`--clear-bypass-cidrs`を明示する。
`default=bypass`、評価順変更、IP/CIDR例外追加は、適用時に`--confirm-bypass`が必要である。

CLI管理SPに対応する`forceSPs`、`bypassSPs`、`requiredSPs`、`bypassSpCidrs`を手作業で
変更すると、後続のSP管理コマンドは差分を検出して停止する。手作業変更を採用するのではなく、
`set-mfa`で設定し直すか、`mfa reconcile --from-registry`で台帳の状態へ戻す。

## 5. 属性profileとSP別アクセス制御

属性候補の検出、属性release承認、SP別アクセス制御は、SP管理CLIのサブコマンドである。
`access init --apply`は初回だけ実行し、ContextCheckとの競合がないことを確認する。
詳細は[FAQ.md](./FAQ.md#spごとにldap属性で利用可否を制御するにはどうすればよいか)を参照する。

```bash
# Attribute Resolverが検出できる属性候補を一覧表示する。設定変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover

# 指定SPと指定ユーザーの文脈で、実際に解決される属性候補を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover \
  --sp SP_NAME --user USER

# LDAPDirectory DataConnectorの有無を確認する。設定変更は行わない。
# 存在しなければgraphicalmatrixLdapを追加するapplyコマンドを表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver init

# 標準のidp.attribute.resolver.LDAP.*設定を参照するLDAP DataConnectorを追加する。
# 既定ではuidを利用者検索属性として使用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver init \
  --data-connector graphicalmatrixLdap \
  --apply --confirm graphicalmatrixLdap

# 利用者検索属性がuid以外の場合、そのLDAP属性名を指定してDataConnectorを追加する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver init \
  --data-connector graphicalmatrixLdap \
  --search-attribute LOGIN_ATTRIBUTE \
  --apply --confirm graphicalmatrixLdap

# LDAPに存在する属性をAttribute Resolverへ追加する予定内容を表示する。設定は変更しない。
# LDAPDirectory DataConnectorが1つなら自動選択し、複数ある場合は候補を表示して停止する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver add ATTRIBUTE

# 選択したLDAP DataConnectorから属性を解決するAttributeDefinitionを実際に追加する。
# ATTRIBUTEとLDAP側の属性名が同じ場合、--source-attributeは省略できる。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver add ATTRIBUTE \
  --data-connector DATA_CONNECTOR_ID \
  --apply --confirm ATTRIBUTE

# IdP属性IDとLDAP側の属性名が異なる場合、LDAP側の属性名を明示して追加する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver add ATTRIBUTE \
  --source-attribute LDAP_ATTRIBUTE \
  --data-connector DATA_CONNECTOR_ID \
  --apply --confirm ATTRIBUTE

# Resolver変更をIdPへ反映する。実行後にattributes discover --sp ... --user ...で確認する。
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service

# CLIの属性ガバナンス台帳に登録された全属性を一覧表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes list

# 指定属性の用途承認、分類、利用中profileを表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes show ATTRIBUTE

# 属性をIdP内部アクセス制御用として承認する。release用なら--usage releaseを指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes approve ATTRIBUTE \
  --usage access \
  --classification internal \
  --purpose 'IdP-side SP authorization' \
  --apply --confirm ATTRIBUTE

# 指定属性を含むprofileの作成予定を確認する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile create PROFILE \
  --attributes uid,mail \
  --description 'Minimal release profile'

# profileを作成する。各属性は事前にrelease承認とSAML mappingが必要である。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile create PROFILE \
  --attributes uid,mail \
  --description 'Minimal release profile' \
  --apply --confirm PROFILE

# builtinと管理対象を含むattribute profile一覧を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile list

# 1つのprofileに含まれる属性と説明を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile show PROFILE

# 対象SPへ割り当てるprofileの変更予定を表示する。SPへの実際のrelease設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-attributes SP_NAME PROFILE

# 対象SPへprofileを割り当てる。SSO後にSP側で属性受信を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-attributes SP_NAME PROFILE --apply

# SP別アクセス制御のContextCheck導入予定を確認する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init

# SP別アクセス制御を初期化する。既存のContextCheckとの競合を確認してから実行する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init --apply

# businessCategory=AAだけを許可するルールの予定を表示する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set SP_NAME \
  --allow 'businessCategory=AA'

# businessCategory=AAだけを許可するルールを対象SPへ反映する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set SP_NAME \
  --allow 'businessCategory=AA' \
  --apply --confirm 'ENTITY_ID'

# 指定ユーザーがアクセスルールで許可されるかを確認する。属性値は表示しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access test SP_NAME --user USER
```

LDAPに属性があっても、Attribute Resolverが使用するbindユーザーに読取り権限がなければ
`access test`は`ATTRIBUTE_MISSING`として拒否する。`aacli.sh --unfiltered`でIdPが属性を
解決できることを確認する。

## 6. IdP再構築・reload・属性診断

JAR、IdP認証flow、`web.xml`、Attribute Resolver、metadata providerを変更した場合は、設定を検査し、
WARを再構築してJettyを再起動する。

```bash
# plugin配置と設定を厳格に検査する。WARNも失敗として扱い、変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp --config-only --strict

# edit-webappの変更をIdP WARへ反映する。停止時間を確保して実行する。
sudo /opt/shibboleth-idp/bin/build.sh

# 再構築したWARを読み込むためJetty IdPを再起動する。
sudo systemctl restart jetty-idp.service

# Jetty IdPの起動状態を確認する。active以外ならログを確認する。
sudo systemctl is-active jetty-idp.service
```

metadataまたはAttribute Resolverの個別reloadでは、外部公開URLではなくloopback listenerを指定する。

```bash
# managed SP metadata providerだけを再読込する。IDはmetadata-providers.xmlのprovider idである。
sudo env IDP_BASE_URL='http://127.0.0.1:8080/idp' \
  /opt/shibboleth-idp/bin/reload-metadata.sh \
  -id GraphicalMatrixManagedSPMetadata

# 指定principalをAttribute Resolverで解決して表示する。--unfilteredは実際のrelease結果ではない。
sudo env IDP_BASE_URL='http://127.0.0.1:8080/idp' \
  /opt/shibboleth-idp/bin/aacli.sh \
  --principal USER \
  --requester 'ENTITY_ID' \
  --unfiltered
```

`aacli.sh --unfiltered`はResolverが解決した属性の確認用であり、SPへ実際にreleaseされる属性を
確認するものではない。SAML属性releaseは、対象SPでSSOを実行してSP側で確認する。

## 7. API tokenとAPI疎通

管理APIは既定で無効である。有効化、アクセス制御、API endpointの詳細は
[SECURITY.md](./SECURITY.md)と[API-CURL-TESTS.md](./API-CURL-TESTS.md)を参照する。

```bash
# tokenの存在・権限・長さだけを確認する。token値は表示しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh status

# token更新の予定を表示する。tokenファイルは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate

# 新しいtokenを生成して保存する。既存のAPIクライアントは直ちに新tokenへ切り替える。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate --apply
```

token更新後は、APIクライアント側のcredentialを直ちに更新する。通常はJetty再起動は不要である。

## 8. DB移行とsecurity upgrade

H2からPostgreSQLへの移行は、業務停止またはmaintenance windowで実施する。CSVにはsequenceと
TOTP seedが含まれ得るため、秘密情報として扱う。詳細は[DB-MIGRATION.md](./DB-MIGRATION.md)と
[SEQUENCE-STORAGE-MIGRATION.md](./SEQUENCE-STORAGE-MIGRATION.md)を参照する。

```bash
# H2 databaseの行数を確認する。移行前後の件数照合に使い、DBは変更しない。
sudo /path/to/package/bin/graphicalmatrix-db-migration.sh h2-count

# H2 databaseをCSVへ退避する。CSVに秘密情報が含まれ得るため保護されたパスを指定する。
sudo /path/to/package/bin/graphicalmatrix-db-migration.sh h2-export \
  --output /secure/backup/graphicalmatrix-h2.csv

# PostgreSQL schemaを作成・更新する。実DBを変更するため、接続先を確認してから実行する。
sudo /path/to/package/bin/graphicalmatrix-db-migration.sh pg-apply-schema --apply

# 退避CSVをPostgreSQLへ取り込む。実行前に入力CSVと接続先を再確認する。
sudo /path/to/package/bin/graphicalmatrix-db-migration.sh pg-import \
  --input /secure/backup/graphicalmatrix-h2.csv --apply

# H2 export CSVとPostgreSQLの内容を照合する。DBは変更しない。
sudo /path/to/package/bin/graphicalmatrix-db-migration.sh pg-verify \
  --input /secure/backup/graphicalmatrix-h2.csv

# sequence保存方式強化の対象と実行計画を表示する。変更は行わない。
sudo /path/to/package/bin/graphicalmatrix-security-upgrade.sh \
  --package-dir /path/to/package plan

# security upgrade後の配置・設定・移行状態を検査する。変更は行わない。
sudo /path/to/package/bin/graphicalmatrix-security-upgrade.sh \
  --package-dir /path/to/package verify
```

`graphicalmatrix-security-upgrade.sh apply`には、バックアップ、maintenance、schema適用を確認する
明示的なフラグが必要である。省略せず、対象リリースの[UPGRADE.md](./UPGRADE.md)に従う。

## 9. 関連文書

- [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md): 設定項目、既定値、型、再起動要否。
- [FAQ.md](./FAQ.md): 典型的な構成・運用上の質問と切り分け。
- [ADMIN-TOOLS.md](./ADMIN-TOOLS.md): Admin Tools単体導入、CSV、定期処理。
- [INSTALL_NEW_SP.md](./INSTALL_NEW_SP.md): SP管理CLIによる新規SP導入・削除・復元。
- [INSTALL_NEW_Manual_SP.md](./INSTALL_NEW_Manual_SP.md): CLIを使わない手作業のSP導入。
- [UPGRADE.md](./UPGRADE.md): plugin更新、ロールバック、保存方式移行。
