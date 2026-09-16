# 2FAS-KW Operations Runbook

この文書は、2FAS-KWの通常運用、日常点検、変更前後の確認、障害切り分け、緊急時の初動および
復旧完了判定をまとめたrunbookです。

標準例は次を前提とします。

```text
IdP home:        /opt/shibboleth-idp
IdP service:     jetty-idp.service
IdP loopback:    http://127.0.0.1:8080/idp
Admin Tools:     /opt/graphicalmatrix-admin
Dashboard:       /opt/2faskw-dashboard
```

実環境のservice名、context path、DB/LDAP endpoint、runtime userおよび配置先が異なる場合は
置き換えてください。

## 1. 運用原則

1. まず時刻、対象user、SP entityID、IdP node、送信元IP、発生操作を確定する。
2. systemd、IdP process log、2FAS-KW audit log、DB/LDAP、MFA方式、SPの順に確認する。
3. 調査中にcredential、sequence、TOTP seed、API token、DB passwordを画面やticketへ記録しない。
4. read-only確認を先に行い、restart、unlock、reset、policy変更は影響を確認してから実行する。
5. MFA障害の回避目的で、全体方針や対象SPを安易に`bypass`へ変更しない。
6. pepper/key fileが見つからなくても、新しい鍵を同名で生成しない。
7. 原因不明のまま再起動を繰り返さず、最初の失敗logと設定差分を保存する。
8. DB、LDAP、IdP signing keyまたはstorage-format secretの変更前にbackupを取得する。

backupと復旧は[BACKUP-RESTORE.md](./BACKUP-RESTORE.md)、logの詳細は
[LOG-REFERENCE.md](./LOG-REFERENCE.md)を参照してください。

## 2. 正常状態

通常時は少なくとも次を満たします。

- `jetty-idp.service`が`active`である。
- loopbackの`/idp/status`が成功する。
- `graphicalmatrix-plugin-check.sh --config-only`が`result: OK`で終了する。
- DB保存では`security-status`のincompatible/empty error件数が0である。
- 利用中のstorage-format secretをIdP runtime userが読み取れる。
- SP管理を使う場合、managed metadata directoryとregistryが整合している。
- 認証audit logへ新しいeventが出力される。
- CSV runner、Dashboard、Agentなど任意componentは、使用しているものだけが正常である。
- filesystem、DB、LDAP、Dashboard spoolおよびlog領域に十分な空きがある。
- IdP/SP/API/DashboardのTLS certificateが運用基準内の残存期間を持つ。

## 3. 点検頻度

| 頻度 | 確認項目 |
| --- | --- |
| 常時監視 | IdP status、認証失敗率、DB/LDAP到達性、disk、certificate期限、audit log停止。 |
| 日次 | systemd失敗、config check、security-status、直近ERROR、CSV/Dashboard任意component。 |
| 週次 | lockout傾向、SP metadata/policy drift、logrotate、backup完了、Agent spool。 |
| 月次 | restore test結果、容量推移、依存component更新、管理権限、不要token/certificate。 |
| release前後 | package署名、compatibility、設定差分、全MFA方式、代表SP、rollback。 |

頻度は利用者数、認証重要度、監査要件、障害許容時間に合わせて短縮してください。

## 4. 日常点検

### 4.1 IdPと2FAS-KW

```bash
sudo systemctl is-active jetty-idp.service

curl --noproxy '*' -fsSI --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/idp/status

sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

`active`、HTTP成功、`result: OK`を確認します。外部公開interfaceの`/status`が403でも、loopback
statusが成功し、公開制限が意図した設定なら異常とは限りません。

### 4.2 DB保存

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh sequence-mode

sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh security-status
```

`security-status`では少なくとも次を確認します。

- `initial_sequence_incompatible_rows=0`
- `incompatible_sequence_rows=0`
- `active_empty_sequence_rows=0`

TOTPを使用する場合は、TOTP seedが`hash`ではなく、設定した`aes-gcm`または`keyword`で復号可能な
ことをtest userの登録・認証で確認します。

LDAP enrollment構成ではDB件数を正常性判断に使わず、LDAP service、bind、対象attribute、ACLと
test userの認証を確認してください。

### 4.3 SP管理とMFA方針

SP管理CLIを有効にしている場合だけ実行します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list --all

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show
```

代表SPは個別に検証します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify SP_NAME

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp SP_NAME --ip CLIENT_IP
```

`SP_NAME`はentityIDではなく、`graphicalmatrix-sp.sh list`の`NAME`列を使用します。MFA testへ
実在userの秘密情報は不要です。

SP別LDAP属性access policyを使用する場合は、test userで実効判定を確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access show SP_NAME

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access test \
  SP_NAME --user TEST_USER
```

### 4.4 Log

```bash
sudo journalctl -u jetty-idp.service -n 100 --no-pager

sudo tail -n 100 /opt/shibboleth-idp/logs/idp-process.log

sudo tail -n 100 /opt/shibboleth-idp/logs/idp-warn.log

sudo tail -n 100 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

直近の異常を絞る場合は、調査開始時刻を明示します。

```bash
sudo journalctl -u jetty-idp.service \
  --since 'YYYY-MM-DD HH:MM:SS' --no-pager
```

`idp-process.log`には通常の起動情報も含まれます。文字列`ERROR`だけで障害と断定せず、exceptionの
先頭、`Caused by`、対象request、直後のservice状態を一続きで確認します。

### 4.5 容量とrotation

```bash
df -h /opt/shibboleth-idp /var/log

sudo du -sh /opt/shibboleth-idp/logs

sudo systemctl is-enabled logrotate.timer

sudo systemctl status logrotate.service --no-pager
```

`logrotate.service`はoneshotのため、成功後の`inactive (dead)`は正常です。直近実行の
`status=0/SUCCESS`を確認します。失敗時はSELinux AVC、log directory context、owner/group、
各`/etc/logrotate.d/graphicalmatrix-*`の`create`/`su`設定を確認します。

## 5. 任意Component

### 5.1 Admin Tools CSV provisioning

```bash
sudo systemctl status graphicalmatrix-csv-import.path --no-pager -l

sudo journalctl -u graphicalmatrix-csv-import.service -n 100 --no-pager

sudo tail -n 200 /opt/graphicalmatrix-admin/logs/csv-import.log

sudo find /opt/graphicalmatrix-admin/incoming \
  /opt/graphicalmatrix-admin/processing \
  /opt/graphicalmatrix-admin/failed \
  -maxdepth 1 -type f -ls
```

path unitの`active (waiting)`は正常です。`.upload`は転送途中fileであり、完成後に`.csv`へatomic
renameして初めて処理対象にします。`failed/`へ移動したfileを、そのまま`incoming/`へ戻して
再試行せず、失敗理由と重複適用の有無を確認してください。

### 5.2 DashboardとAgent

```bash
sudo systemctl is-active 2faskw-dashboard.service

sudo systemctl is-active 2faskw-dashboard-agent.service

sudo journalctl -u 2faskw-dashboard.service -n 100 --no-pager

sudo journalctl -u 2faskw-dashboard-agent.service -n 100 --no-pager
```

複数instanceのAgentでは、`2faskw-dashboard-agent@NODE.service`を確認します。Dashboard障害は
IdP認証を停止させない構成ですが、監査表示の欠落、Agent spool増加、mTLS期限切れを監視します。

## 6. 安全なJetty再起動

設定変更やPlugin更新で再起動が必要な場合は、事前にbackup、config check、保守時間帯、LB drainを
確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only

sudo /opt/shibboleth-idp/bin/build.sh

sudo systemctl restart jetty-idp.service

until curl --noproxy '*' -fsSI --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/idp/status >/dev/null 2>&1; do
  echo 'Waiting for Jetty...'
  sleep 2
done
```

最初の`curl: (7) Failed to connect`はlistener準備前なら想定内です。待機が継続する場合はloopを
中断し、次を確認します。

```bash
sudo systemctl status jetty-idp.service --no-pager -l

sudo journalctl -u jetty-idp.service -n 200 --no-pager

sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log
```

起動失敗時に`build.sh`とrestartを繰り返すと最初の原因を失いやすいため、設定差分と最初のstack
traceを保存します。

## 7. 障害切り分け

| 症状 | 最初の確認 | 代表的な原因 |
| --- | --- | --- |
| IdPへ接続できない | systemd、listener、Jetty journal | service停止、起動中、bind失敗、Firewall、誤URL。 |
| `/idp/status`が500/503 | `idp-process.log`の最初のexception | Spring bean、metadata、DB、LDAP、WebFlow初期化失敗。 |
| Plugin config check失敗 | FAIL行と参照path | secret欠落、owner/group、保存方式不整合、設定上書き。 |
| Password後に追加認証へ進めない | MFA decision log、DB/LDAP user状態 | user未登録、MFA方式不整合、DB/LDAP接続失敗。 |
| Matrix画像を押せない | deployed JavaScript、CSP、browser console | asset未統合、旧WAR/cache、CSP、templateとscriptのversion不一致。 |
| Matrixで常に失敗 | audit event、user state、storage mode | sequence違い、pepper/key違い、選択数/order設定変更。 |
| TOTPが失敗 | seed status、時刻同期、TOTP Plugin log | seed未登録、復号鍵違い、clock skew、Plugin設定。 |
| WebAuthnが失敗 | RP ID/origin、credential store、Plugin log | FQDN/HTTPS変更、StorageService不整合、credential欠落。 |
| SPだけ失敗 | SP verify、metadata、IdP署名証明書 | entityID/ACS不一致、metadata未読込、certificate trust、attribute filter。 |
| `ATTRIBUTE_MISSING` | AACLI、Attribute Resolver、LDAP ACL | Resolver未定義、DataConnector未読込、LDAP bind userが読めない。 |
| CSVが処理されない | path unit、incoming名、runner log | `.csv`でない、unit停止、権限、autoApply条件、DB接続。 |
| Dashboardが更新されない | Agent journal、spool、mTLS、source log | Agent停止、certificate、network、parser failure、rotation。 |
| logrotate失敗 | service journal、AVC、file context | `/opt` logが`usr_t`、owner/mode不一致、設定syntax。 |

### 7.1 IdP起動失敗

```bash
sudo systemctl status jetty-idp.service --no-pager -l

sudo journalctl -u jetty-idp.service -n 250 --no-pager

sudo grep -nE \
  'BeanCreationException|Caused by:|MetadataResolverService|SQLException|LDAP|Failed to load' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 150
```

`MetadataResolverService is unavailable`では、個別provider名だけでなく、
`metadata-providers.xml`全体、参照XMLの存在、Jetty userからの読取り、XML syntaxを確認します。

### 7.2 DBまたはstorage-format error

```bash
sudo grep -nE \
  '^graphicalmatrix\.(savedata|sequence\.(storage|pepperFile|keywordFile|aesKeyFile)|totp\.seed\.(storage|keywordFile|aesKeyFile))[[:space:]]*=' \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties

sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh security-status
```

`Missing pepper or pepperFile`、`NoSuchFileException`、`incompatible sequence`では、DBが見えることと
credentialを検証できることは別問題です。同じ世代のsecret file、設定path、owner/group/modeを
確認します。新しいsecretで置換しません。

### 7.3 User単位の認証失敗

次の`show`はDB enrollment構成の確認です。LDAP enrollment構成では、対応するLDAP entry、ACL、
保存attributeおよびIdP logを確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh show USER

sudo grep -E 'user=USER([[:space:]]|$)' \
  /opt/shibboleth-idp/logs/graphicalmatrix-audit.log | tail -n 100
```

確認する項目は`status`、`mfa_method`、`failed_count`、`locked_until`、TOTP登録状態、最終成功時刻です。
sequence、TOTP seed、WebAuthn private keyを表示する必要はありません。

unlock、reset、方式変更は状態変更操作です。本人確認、申請、影響、監査要件を確認し、
[COMMAND-REFERENCE.md](./COMMAND-REFERENCE.md)の現行versionの手順に従ってください。

### 7.4 SPとmetadata

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list --all

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify SP_NAME

sudo tail -n 100 \
  /opt/shibboleth-idp/logs/graphicalmatrix-sp-management-audit.log

sudo grep -nE \
  'MetadataResolverService|metadata-providers\.xml|LocalDynamicMetadataProvider|Failed to load' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 100
```

`ACS host is not approved`はIdPがSPへ通常login時に問い合わせるerrorではありません。SP metadataの
取込・検証時に、ACS hostが`allowedAcsHosts`へ許可されていないことを示します。

`Invalid certificate signature`がSP側に出た場合は、SPが保持するIdP metadataの署名certificateと、
IdPが実際に使用するsigning certificateのfingerprintを比較します。SP登録用metadataとIdP公開
metadataを混同しないでください。

### 7.5 LDAP属性access

まずLDAPに値があること、次にIdP Attribute Resolverが値を解決すること、最後に2FAS-KW access
policyが値を評価することを分けて確認します。

```bash
sudo env IDP_BASE_URL='http://127.0.0.1:8080/idp' \
  /opt/shibboleth-idp/bin/aacli.sh \
  --principal TEST_USER \
  --requester 'SP_ENTITY_ID' \
  --unfiltered

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access test \
  SP_NAME --user TEST_USER
```

LDAPにattributeが存在してもAACLIに出なければaccess判定には使用できません。LDAP検索だけでなく、
Resolver definition、DataConnector、search filter、return attribute、bind ACL、IdP再読込を確認します。

## 8. Security incident初動

credential漏えい、管理API不正利用、DB/LDAP改ざん、signing key漏えいまたは不正releaseの疑いがある
場合は、通常障害と分けて扱います。

1. 発生時刻、node、source、対象user/SP、観測logを保全する。
2. IdP、DB、LDAP、proxy、Admin Tools、Dashboardの時刻を記録する。
3. 影響する管理surfaceをnetworkまたはservice単位で隔離する。
4. 調査前にlog、DB、設定、credential fileを削除・上書きしない。
5. API tokenやDB passwordをrotateする場合は、先に証拠と依存clientを確認する。
6. signing key漏えいでは、単なるfile差替えではなくIdP metadata rolloverと全SP trust更新を行う。
7. 公開Issueへ秘密情報や未修正の詳細を投稿せず、[Security Policy](../SECURITY.md)に従う。

管理APIを使用していない環境では、`graphicalmatrix.api.enabled=false`を維持します。CSV provisioningを
一時停止する場合は、処理中fileとDB反映状況を確認してからpath unitを停止します。

```bash
sudo systemctl stop graphicalmatrix-csv-import.path

sudo find /opt/graphicalmatrix-admin/incoming \
  /opt/graphicalmatrix-admin/processing \
  -maxdepth 1 -type f -ls
```

IdP全体を停止するか、対象管理経路だけを隔離するかは、侵害範囲と認証継続のriskをincident責任者が
判断します。

## 9. 変更作業

### 9.1 作業前

- [COMPATIBILITY.md](./COMPATIBILITY.md)でversion範囲を確認する。
- [BACKUP-RESTORE.md](./BACKUP-RESTORE.md)に従いbackupとrollbackを用意する。
- 現在のJAR、config、DB/LDAP件数、MFA policy、SP一覧を記録する。
- `.idpnew`と現行fileの差分を確認する。
- clusterではnodeごとの作業順、LB drain、共有DB/StorageServiceへの影響を決める。

### 9.2 作業後

- config check、WAR build、IdP statusを確認する。
- Password + GraphicalMatrixを必ず試験する。
- 使用中の場合はTOTP、WebAuthn、自己管理、SP access、Admin Tools、Dashboardを試験する。
- 代表SPでSAML response、属性、MFA方針、IdP署名検証を確認する。
- 新しいaudit eventとerror logを確認する。
- rollback可能時間が終了するまで旧artifactとbackupを保護する。

詳細なupdate/rollback順序は[UPGRADE.md](./UPGRADE.md)を使用します。

## 10. Cluster・容量運用

- 全IdP nodeで同じ2FAS-KW version、設定、storage-format secretを使用する。
- enrollment DB/LDAPとWebAuthn StorageServiceを全nodeから同じ論理dataとして参照する。
- session、lockout、WebAuthn補助状態がnode localかsharedかを確認する。
- HikariCPの`maximumPoolSize * IdP node数 + Admin Tools接続`をDB上限内にする。
- 大規模NATではIP単位rate limitだけで正規userを巻き込まないよう設計する。
- 4月などの一斉登録・変更前に、IdP、DB、LDAP、proxy、audit diskのload testを行う。
- NTP/chronyを全node、DB、LDAP、SP、Dashboardで同期する。TOTPとlog相関に影響する。

Dashboard障害は認証停止条件ではありませんが、DB/LDAP、storage-format secret、IdP signing keyの
障害は認証継続へ直接影響します。componentごとに監視重要度を分けてください。

## 11. Escalation情報

保守担当へ渡す情報は、秘密を含めず次を揃えます。

- 2FAS-KW、IdP、Java、Jetty、TOTP/WebAuthn Pluginのversion。
- 発生時刻とtimezone、IdP node、SP entityID、MFA方式。
- 再現手順、期待結果、実際の結果。
- `systemctl status`、該当時間のjournal、IdP process/audit log。
- config checkのsummary。properties全体やsecret値は添付しない。
- 直前の変更、rollback実施有無、影響user数。
- 単一user、単一SP、単一node、全体のどこまで影響するか。

サポート条件は[SUPPORT-POLICY.md](./SUPPORT-POLICY.md)を参照してください。脆弱性の疑いは公開Issueで
扱いません。

## 12. 復旧完了条件

障害対応はserviceが起動しただけでは完了しません。次を確認します。

1. 原因と影響範囲が記録されている。
2. IdP statusとconfig checkが正常である。
3. DB/LDAP、secret、MFA user状態、SP metadata/policyが整合している。
4. 使用中の全MFA方式と代表SPで認証が成功する。
5. 拒否すべきuser/policyが正しく拒否される。
6. audit log、rotation、Dashboard/Agent、CSV runnerが必要範囲で復旧している。
7. 一時的なbypass、Firewall例外、debug log、test credentialを残していない。
8. backup、監視、runbookへ再発防止を反映している。
