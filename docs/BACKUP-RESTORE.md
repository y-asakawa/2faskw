# 2FAS-KW Backup and Restore Guide

この文書は、2FAS-KWの認証状態、設定、秘密情報、SP管理情報および監査情報を
バックアップし、障害時に整合性を保って復元するための基本方針を示します。

本書のコマンドは標準配置例です。実環境のservice名、DB名、保存先、実行user、
HA構成および組織のbackup製品に合わせて読み替えてください。

## 1. 最重要原則

2FAS-KWの復旧では、**DBだけを保存しても不十分**です。保護済みcredentialを利用するには、
そのDB世代と一致するstorage-format secretが必要です。

```text
enrollment DB / LDAP data
        +
sequence pepper / encryption key
        +
TOTP seed encryption key
        +
IdP・2FAS-KW configuration
        =
認証状態を復元できる一つの復旧世代
```

- `hash` sequenceはpepperを失うと既存sequenceを検証できません。
- `aes-gcm`または`keyword`で保存したsequence/TOTP seedは、対応する鍵を失うと復号できません。
- 新しい鍵を生成しても、古い鍵で保護した既存dataは復元されません。
- IdP署名・暗号化鍵を失うと、SP側のmetadata trustやSAML復号へ影響します。
- WebAuthn credentialの秘密鍵はauthenticator側にありますが、IdP側の公開credential record、
  user binding、RP IDおよびStorageServiceを失うと既存登録を使用できません。

backupは暗号化し、認証runtime、通常管理者およびbackup管理者の権限を必要に応じて分離して
ください。秘密情報を通常のIssue、chat、作業logまたは公開repositoryへ保存してはなりません。

## 2. 復旧目標

導入組織は、2FAS-KWとは別に次を決定します。

| 項目 | 決定内容 |
| --- | --- |
| RPO | 許容できるenrollment・credential・policy変更の消失期間。 |
| RTO | IdP認証を復旧するまでの目標時間。 |
| 世代数 | 日次、週次、月次などの保持世代。 |
| 保管先 | IdP/DB host障害の影響を受けない暗号化済みoff-host領域。 |
| 復旧責任者 | DB、IdP、LDAP、証明書、SP、security incidentの担当者。 |
| 復旧試験 | 隔離環境でrestoreと実認証を確認する頻度。 |

2040年までのプロジェクト維持目標や最新リリースのサポート対象は、利用組織のRPO/RTOを
保証しません。詳細は[SUPPORT-POLICY.md](./SUPPORT-POLICY.md)を参照してください。

## 3. Backup対象

### 3.1 ReleaseとIdP Plugin

- 使用中の2FAS-KW release archive、署名、`SHA256SUMS`および公開鍵。
- 使用中のShibboleth IdP、TOTP Plugin、WebAuthn Pluginのversion情報。
- `/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/`の2FAS-KW JARと同梱runtime JAR。
- `/opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml`。
- `/opt/shibboleth-idp/edit-webapp/graphicalmatrix/`など、運用中のassetと独自template。

release artifactは再取得だけに依存せず、署名検証済みの同一物を復旧用に保管してください。

### 3.2 IdP・2FAS-KW設定

少なくとも次を保存します。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/
/opt/shibboleth-idp/conf/authn/
/opt/shibboleth-idp/conf/attribute-resolver.xml
/opt/shibboleth-idp/conf/attribute-filter.xml
/opt/shibboleth-idp/conf/metadata-providers.xml
/opt/shibboleth-idp/conf/relying-party.xml
/opt/shibboleth-idp/conf/global.xml
/opt/shibboleth-idp/conf/services.properties
/opt/shibboleth-idp/conf/ldap.properties
```

存在しない任意fileは除外できます。IdP全体のbackupでは、2FAS-KWが直接変更しないIdP設定、
views、messages、credentials、metadataおよびWAR overlayも含めてください。

### 3.3 Credentialとstorage-format secret

`/opt/shibboleth-idp/credentials/`には、次のような重要情報が含まれます。

- IdP signing/encryption private keyとcertificate。
- PostgreSQL password file。
- GraphicalMatrix sequence pepper、keywordまたはAES key。
- TOTP seed keywordまたはAES key。
- 管理API bearer token。
- SP管理revision/transaction backupなど、運用で同directory配下へ配置した管理情報。

実際に使用するfileは、設定値から確認します。

```bash
sudo grep -R -nE \
  '(^|\.)(passwordFile|pepperFile|keywordFile|aesKeyFile|bearerTokenFile)[[:space:]]*=' \
  /opt/shibboleth-idp/conf/graphicalmatrix
```

秘密fileの内容を画面へ表示せず、path、所有者、mode、backup収録有無だけを確認してください。

### 3.4 Enrollment DBとWebAuthn StorageService

PostgreSQLでは少なくとも次を同じDB backupに含めます。

- `graphicalmatrix_enrollment`
- schema migrationで追加された2FAS-KW関連object
- WebAuthn JDBC StorageServiceを同じDBで使う場合の`storagerecords`

DB role、owner、grant、TLS certificateおよび接続設定は、data dumpとは別に復元可能な状態へ
記録してください。application passwordの平文を手順書へ記載してはなりません。

### 3.5 LDAP保存

`graphicalmatrix.savedata=ldap`を使用する場合は、対象user entryの2FAS-KW属性をLDAP側の
backupへ含めます。WebAuthn LDAP StorageServiceを使う場合は、専用subtreeも同じ整合点で
保存します。

LDAP server固有のonline backup、replication、exportおよびrestore手順を使用してください。
単独の`ldapsearch`出力は、binary/operational attribute、ACL、schema、entry UUID、password
policyなどを完全には復元できないため、正規backupの代替にしません。

### 3.6 SP管理情報

次を一組として保存します。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/sp-management-registry.json
/opt/shibboleth-idp/conf/graphicalmatrix/access-policy.json
/opt/shibboleth-idp/conf/graphicalmatrix/attribute-catalog.json
/opt/shibboleth-idp/metadata/2faskw-managed-sp/
/opt/shibboleth-idp/credentials/graphicalmatrix/
```

さらに、`metadata-providers.xml`、`attribute-filter.xml`、`attribute-resolver.xml`、
`relying-party.xml`および`mfa-policy.properties`を同じ世代で保存します。registryだけを戻すと、
managed metadata、属性release、access policyまたはMFA policyとrevisionが一致しない場合があります。

### 3.7 Admin Tools

Admin Toolsを使用する場合は次を保存します。

- `/opt/graphicalmatrix-admin/conf/graphicalmatrix/`
- IdPと同期したstorage-format secret
- `processed/`と`failed/`のCSVおよび`csv-import.log`
- systemd path/service、logrotateおよび専用upload user設定

`incoming/`または`processing/`に作業中fileがある状態で復旧世代を確定しないでください。
処理完了または明示的な隔離後にbackupします。

### 3.8 Dashboardとaudit log

認証監査の正本は`graphicalmatrix-audit.log`とrotation済みlogです。DashboardのH2 DBは
再構築可能なindexですが、保存する場合はDashboardを停止してcopyします。

Dashboard Agentを使う場合は、未送信spool、agent state、mTLS certificate、truststoreおよび
Dashboard設定も対象です。H2 indexだけを保存して監査logを失う運用にはしないでください。

### 3.9 OS・network設定

- Jetty、Dashboard、Agent、CSV runnerのsystemd unit/drop-in。
- reverse proxy、TLS certificate、Firewall、SELinux fcontext、logrotate。
- DNS、VIP、DB/LDAP endpoint、NTPおよび監視設定。
- file owner/group/modeとruntime user/group。

これらはapplication backupと別の構成管理systemで管理しても構いませんが、復旧時に同じversionを
再現できる必要があります。

## 4. 整合したBackupの取得

### 4.1 事前確認

1. backup世代IDを決める。
2. 現行version、保存方式、DB/LDAP選択、optional Plugin、SP件数を記録する。
3. IdP、Admin Tools、管理API、CSV runnerからの書込みを短時間停止または保守状態にする。
4. DB/LDAP snapshotとfile backupを同じ作業windowで取得する。
5. checksum、暗号化、off-host転送およびrestore testを行う。

参照専用確認例です。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh sequence-mode

sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh security-status

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list --all

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
```

LDAP enrollment構成では、DB CLIの結果だけをbackup判定に使用しないでください。

### 4.2 File backup例

次は同一host内の一時退避例です。`/secure/backup`は、実際の暗号化済みbackup mountへ
置き換えてください。

```bash
TS=$(date +%Y%m%d%H%M%S)
BACKUP="/secure/backup/2faskw-$TS"

sudo install -d -o root -g root -m 0700 "$BACKUP"

sudo tar -C /opt -czf "$BACKUP/shibboleth-idp-files.tar.gz" \
  shibboleth-idp/conf \
  shibboleth-idp/credentials \
  shibboleth-idp/metadata \
  shibboleth-idp/edit-webapp

sudo sha256sum "$BACKUP/shibboleth-idp-files.tar.gz" |
  sudo tee "$BACKUP/SHA256SUMS" >/dev/null
```

このarchiveにはprivate key、DB password、TOTP keyなどが含まれます。通常のfilesystem backupより
強いaccess control、暗号化、監査および持出し制限を適用してください。

### 4.3 PostgreSQL dump例

PostgreSQLの整合dumpを同じ世代へ保存します。`postgres` userはmode `0700`のroot専用backup
directoryを通過できないため、DB data directory配下でdumpを作成し、完了後にroot権限でbackup
領域へ収録します。

```bash
DUMP_TMP="/var/lib/pgsql/graphicalmatrix-$TS.dump"

sudo -u postgres pg_dump \
  --dbname=graphicalmatrix \
  --format=custom \
  --no-owner \
  --no-privileges \
  --file="$DUMP_TMP"

sudo install -o root -g root -m 0600 \
  "$DUMP_TMP" "$BACKUP/graphicalmatrix.dump"

sudo rm -f "$DUMP_TMP"

sudo sha256sum "$BACKUP/graphicalmatrix.dump" |
  sudo tee -a "$BACKUP/SHA256SUMS" >/dev/null
```

HA/managed PostgreSQLでは、組織のsnapshot、WAL archive、backup agentおよびpoint-in-time recoveryを
優先してください。`pg_dump`だけではDB cluster、role、tablespace、replication設定を復元しません。

### 4.4 完了条件

- archiveとDB/LDAP backupのchecksumが記録されている。
- backup世代に2FAS-KW/IdP/Plugin versionが記録されている。
- storage-format secretがDB/LDAPと同じ世代に含まれる。
- backupがIdP/DB host外へ暗号化して転送されている。
- backupを復号できる担当者と手順が確認されている。
- 隔離環境へのrestore testが成功している。

## 5. Restore手順

### 5.1 復旧前に確認すること

1. 障害原因と侵害有無を確認し、証拠保全が必要なら先にsnapshotを取得する。
2. 復旧する2FAS-KW、IdP、Java、Jetty、DB、TOTP/WebAuthn Pluginのversionを確定する。
3. 対象世代のchecksumと署名を検証する。
4. DB/LDAP dataとstorage-format secretが同じ復旧世代であることを確認する。
5. 既存環境へ直接上書きせず、可能な限り隔離hostまたは別DBへ復元する。

互換範囲は[COMPATIBILITY.md](./COMPATIBILITY.md)を確認してください。

### 5.2 PostgreSQLを別DBへ検証復元する

破壊的なin-place restoreを最初の手順にしません。例では空の検証DBへ戻します。

```bash
RESTORE_DB=graphicalmatrix_restore_test
RESTORE_DUMP=/var/lib/pgsql/graphicalmatrix-restore.dump

sudo install -o postgres -g postgres -m 0400 \
  /secure/backup/2faskw-BACKUP_ID/graphicalmatrix.dump \
  "$RESTORE_DUMP"

sudo -u postgres createdb --owner=graphicalmatrix_app "$RESTORE_DB"

sudo -u postgres pg_restore \
  --dbname="$RESTORE_DB" \
  --exit-on-error \
  --no-owner \
  --no-privileges \
  "$RESTORE_DUMP"

sudo -u postgres psql -d "$RESTORE_DB" -c '\dt'

sudo -u postgres psql -d "$RESTORE_DB" -c \
  'select count(*) from graphicalmatrix_enrollment;'

sudo rm -f "$RESTORE_DUMP"
```

WebAuthn JDBC StorageServiceを使用する場合は、`storagerecords`の件数とPluginからの読込みも確認
します。本番DBへの切替方法はHA/VIP/DNS/connection stringの構成に依存するため、組織のDB復旧
runbookに従ってください。

### 5.3 IdP fileを検証用directoryへ展開する

まずrootだけが読める検証用directoryへ展開し、稼働中のIdPとの差分を確認します。この比較段階では
Jettyを停止する必要はありません。

```bash
RESTORE_STAGE=/secure/restore-staging/2faskw-BACKUP_ID

sudo install -d -o root -g root -m 0700 "$RESTORE_STAGE"

sudo tar -C "$RESTORE_STAGE" -xzf \
  /secure/backup/2faskw-BACKUP_ID/shibboleth-idp-files.tar.gz

sudo diff -ruN \
  /opt/shibboleth-idp/conf \
  "$RESTORE_STAGE/shibboleth-idp/conf" || true
```

IdP全体のversion、他Plugin、certificate、`web.xml`および独自設定との整合を比較します。確認後、
組織の構成管理または承認済み復旧手順で必要なfileだけを配置します。全fileを戻す場合も、Jetty、
Admin Tools CSV pathなどの書込みcomponentを停止し、現行fileを別世代へ退避してから実施してください。
この文書では、稼働環境を無条件に上書きするcopy commandは提示しません。

復元配置後に所有者、mode、runtime group、SELinux contextを確認します。

```bash
sudo systemctl show jetty-idp.service -p User -p Group

sudo restorecon -RFv \
  /opt/shibboleth-idp/conf \
  /opt/shibboleth-idp/credentials \
  /opt/shibboleth-idp/metadata \
  /opt/shibboleth-idp/edit-webapp
```

custom SELinux fcontextを構成している場合は、policyを復元してから`restorecon`を実行します。

### 5.4 LDAPを復元する

LDAP保存では、LDAP serverの正規restore手順を使用し、次を確認します。

- 2FAS-KW schemaとattribute syntax。
- user entryの2FAS-KW attribute。
- WebAuthn StorageService subtree。
- service account、ACL、password policy、TLS certificate。
- IdPのcanonical user IDとLDAP/WebAuthn record IDの一致。

replication構成では、古いnodeをそのまま参加させて新しいdataを上書きしないよう、replica generationと
復旧方向をLDAP管理者が決定してください。

### 5.5 Buildと起動

設定を検査してからWARを再構築します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only

sudo /opt/shibboleth-idp/bin/build.sh

sudo systemctl start jetty-idp.service

until curl --noproxy '*' -fsSI --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/idp/status >/dev/null 2>&1; do
  echo 'Waiting for Jetty...'
  sleep 2
done
```

loopback listener、context path、service名が異なる環境では置き換えてください。待機が続く場合は
自動で再起動を繰り返さず、`journalctl`と`idp-process.log`を確認します。

### 5.6 復旧後の確認

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh security-status

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show

sudo tail -n 100 /opt/shibboleth-idp/logs/idp-process.log

sudo tail -n 100 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

次を実際に試験します。

- Password + GraphicalMatrix認証。
- 有効なuserがいる場合のTOTP認証。
- 有効なcredentialがある場合のWebAuthn認証。
- 自己管理の再認証と、変更を適用しない画面遷移確認。
- 代表SPごとのSAML response、IdP署名検証、属性release、MFA policy。
- SP access policyを使う場合の許可userと拒否user。
- Admin Tools、CSV runner、Dashboardを使う場合の各read-only check。

復旧確認のために本番userのcredentialを不用意にresetしないでください。専用test userを使用します。

## 6. Secretを失った場合

| 失ったもの | 影響 | 基本対応 |
| --- | --- | --- |
| Hash sequence pepper | 既存hash sequenceを検証できない | backupから同じpepperを復元。なければ対象userを再登録する。 |
| Sequence AES/keyword key | 既存の復号可能sequenceを読めない | 同じ鍵を復元。なければ再登録する。 |
| TOTP AES/keyword key | 既存TOTP seedを復号できない | 同じ鍵を復元。なければTOTPをresetして再登録する。 |
| API bearer token | 管理API clientが認証できない | APIを隔離し、新tokenを生成してclientと同期する。 |
| DB password | IdP/Admin ToolsがDB接続できない | DB側で安全にrotateし、password fileを同期する。 |
| IdP signing key | SPがSAML署名を検証できない | IdP certificate rollover手順を使用し、SP metadata trustを更新する。 |
| IdP encryption key | IdP宛暗号化dataを復号できない | backup復元またはIdP certificate rolloverとSP設定更新を行う。 |
| WebAuthn StorageService | 登録credentialを検索できない | 同じrecordを復元。なければuserごとに再登録する。 |

鍵を失った状態で同名fileへ新しい乱数を生成すると、file存在checkだけは通っても既存dataとの互換性は
回復しません。復旧不能dataと新規dataを混在させる前に、影響user、保存形式prefix、再登録計画を
確定してください。

## 7. 定期Restore試験

少なくともrelease更新、storage方式変更、DB/LDAP移行、IdP certificate更新およびbackup製品変更時に
restore試験を行います。

試験記録には次を残します。

- backup世代IDと対象version。
- checksum/署名検証結果。
- 復元したDB/LDAP件数。
- secret fileの存在、owner、mode確認。内容は記録しない。
- config check、IdP status、代表MFA/SP認証の結果。
- RPO/RTO実測値と未解決事項。
- 試験環境の安全な破棄確認。

日常点検と障害時の確認順序は[OPERATIONS-RUNBOOK.md](./OPERATIONS-RUNBOOK.md)、更新時の個別backupと
rollbackは[UPGRADE.md](./UPGRADE.md)を参照してください。
