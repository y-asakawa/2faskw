# 2FAS-KW Log Reference

この文書は、2FAS-KW、Shibboleth IdP、Admin Tools、任意のDashboardで参照するログの
用途、形式、eventの意味、障害時の確認順序をまとめたリファレンスである。

ログにはユーザーID、送信元IPアドレス、SP entityID、監査相関IDなどの個人情報・運用情報が
含まれ得る。閲覧権限、転送先、保存期間を組織の監査・個人情報保護方針に合わせて定める。
パスワード、GraphicalMatrix sequence、TOTP seed、API bearer token、WebAuthnの秘密鍵は
ログに記録してはならない。

## 1. ログ一覧

| ログまたは出力先 | 既定パス・確認方法 | 出力元 | 主な用途 |
| --- | --- | --- | --- |
| GraphicalMatrix監査ログ | `/opt/shibboleth-idp/logs/graphicalmatrix-audit.log` | GraphicalMatrix認証、自己管理、変更画面、管理API | MFA challenge、照合、ロック、自己管理、API操作の追跡。Dashboardの入力正本。 |
| SP管理監査ログ | `/opt/shibboleth-idp/logs/graphicalmatrix-sp-management-audit.log` | `graphicalmatrix-sp.sh` | SP追加、更新、adopt、rollback、削除などの管理操作の追跡。 |
| SP別アクセス制御監査ログ | `/opt/shibboleth-idp/logs/graphicalmatrix-access-audit.log` | IdPのSP別属性アクセス制御 | SP別アクセス許可・拒否の判定確認。 |
| CSVプロビジョニングログ | `/opt/graphicalmatrix-admin/logs/csv-import.log` | Admin Tools CSV runner | CSVの受信、dry-run、反映、失敗、SHA-256の追跡。Admin Tools導入時のみ。 |
| IdP処理ログ | 通常は`/opt/shibboleth-idp/logs/idp-process.log` | Shibboleth IdP | Spring/metadata/Attribute Resolver/認証flowの例外原因。 |
| IdP警告・監査ログ | 通常は`idp-warn.log`、`idp-audit.log` | Shibboleth IdP | 警告、SAML SSO成否など。出力内容はIdPのlogback設定に依存する。 |
| Jetty systemd journal | `journalctl -u jetty-idp.service` | systemd / Jetty | 起動失敗、JVM終了、listener、WARロードの確認。 |
| Dashboard / Agent journal | `journalctl -u 2faskw-dashboard.service`、`journalctl -u 2faskw-dashboard-agent.service` | Dashboard導入時のみ | 監査ログ取込、heartbeat、送信失敗、表示用データの障害確認。 |

`graphicalmatrix-audit.log`は認証監査の主ログである。SP管理・アクセス制御・CSVプロビジョニングは
別ログであり、Dashboardは既定で`graphicalmatrix-audit.log`だけを取り込む。SP管理ログやCSVログを
Dashboardへ自動的に取り込む機能は、現時点ではない。

## 2. GraphicalMatrix監査ログ

### 2.1 形式とフィールド

1行に1 eventを記録するkey=value形式である。時刻はUTCのISO-8601形式である。

```text
ts=2026-08-05T01:23:45.678Z event=VERIFY user=user001 result=OK ip=192.0.2.10 session=node0... challenge=abc123 detail=matched
```

| フィールド | 意味 | 注意 |
| --- | --- | --- |
| `ts` | eventを記録したUTC時刻 | OSのローカル時刻とは異なる場合がある。Dashboardはこの値を集計時刻に使う。 |
| `event` | 操作種別 | 下表を参照。 |
| `user` | 認証・操作対象のユーザーID | 不明な場合は`-`。 |
| `result` | 操作結果または拒否理由の分類 | eventごとに取り得る値が異なる。 |
| `ip` | Servletが受け取った送信元IPアドレス | リバースプロキシ構成では、プロキシのIPになる場合がある。X-Forwarded-Forをこのログが自動採用するわけではない。 |
| `session` | HTTP session単位のランダムな監査相関ID | Servlet containerのsession IDやcookie値ではない。同一session内のevent相関確認用。 |
| `challenge` | Matrix challenge ID | 該当しない操作は`-`。challenge内容や正解sequenceは記録しない。 |
| `detail` | event固有の補足情報 | `key=value`を含む場合がある。値中の空白、`=`、改行などはエスケープされる。 |

`detail`は人による調査向けの補足であり、将来の版で拡張され得る。監視・自動判定は、原則として
`event`と`result`を使い、`detail`だけに依存しない。

### 2.2 認証・ロック関連event

| event | 主なresult | 意味 |
| --- | --- | --- |
| `START` | `ENROLL_REQUIRED`、`LOCKED`、`DB_ERROR`、`FAIL` | 第2要素開始時の登録・ロック・DB状態確認。正常にMatrixを出せた場合は、通常`CHALLENGE_CREATED`が続く。 |
| `CHALLENGE_CREATED` | `OK` | Matrix画面を表示するchallengeを発行した。`detail`には画像数、行・列数、選択数が入る。 |
| `VERIFY` | `OK`、`FAIL`、`LOCKED`、`BAD_REQUEST` | 利用者のMatrix選択を照合した。`OK`は照合成功、`FAIL`は選択不一致。 |
| `FORCE_SEQUENCE_CHANGE_START` | `OK` | 初回設定または管理者要求により、sequence変更を開始した。 |
| `FORCE_SEQUENCE_CHANGE_SAVE` | `OK`、`BAD_REQUEST`、`DB_ERROR` | 強制sequence変更を保存した。正解sequence自体は記録しない。 |
| `TOTP_REGISTER_START` | `OK`、`ENROLL_REQUIRED` | TOTP初回登録画面を開始した。seedは記録しない。 |
| `TOTP_REGISTER_VERIFY` | `OK`、`FAIL`、`BAD_REQUEST` | TOTP初回登録時のコード確認結果。 |
| `TOTP_REGISTER_CANCEL` | `OK` | TOTP登録を利用者が取り消した。 |
| `WEBAUTHN_REGISTER_START` | `OK`、`ENROLL_REQUIRED`、`DB_ERROR` | 現在のMFA方式で本人確認済みの一回限り登録要求を作成し、公式WebAuthn登録flowへ遷移した。 |
| `WEBAUTHN_REGISTER_ACTIVATE` | `OK`、`DENIED`、`ENROLL_REQUIRED`、`DB_ERROR` | 公式Pluginのcredential保存成功hookを受け、2FAS-KWのMFA方式をWebAuthnへ切り替えた結果。credentialや公開鍵は記録しない。 |

`VERIFY result=FAIL`が連続した後に`VERIFY result=LOCKED`または`START result=LOCKED`が出る場合は、
GraphicalMatrixロックアウトが働いている。`locked_until`が`detail`に出る場合はUnix epoch millisecondsであり、
表示時刻への変換はOSまたは監視基盤側で行う。

### 2.3 自己管理・変更画面event

| event | 主なresult | 意味 |
| --- | --- | --- |
| `SELF_SERVICE_AUTH` | `OK`、`DENIED`、`CONFIG_ERROR` | Shibboleth再認証済み自己管理フローの第2要素確認結果。`OK`の`detail`には認証factorと完了時刻が入る。 |
| `SELF_SERVICE_HANDOFF` | `OK`、`DENIED`、`ENROLL_REQUIRED`、`DB_ERROR` | 自己管理フローから変更画面へ渡す一回限りのhandoff結果。`OK`の`detail=one_time_handoff_consumed`が正常例。 |
| `CHANGE_LDAP_AUTH` | `OK`、`FAIL`、`LDAP_ERROR`、`RATE_LIMITED`、`DENIED` | 従来LDAPログイン経路の結果。自己管理へ切り替え後は`DENIED`と`legacy_ldap_login_disabled`が通常。 |
| `CHANGE_START`、`CHANGE_CHALLENGE_CREATED`、`CHANGE_VERIFY` | `OK`、`FAIL`、`LOCKED`、`BAD_REQUEST`、`DB_ERROR` | 現在のMatrixを再確認して変更画面を開始する処理。 |
| `CHANGE_CHOOSE_SEQUENCE`、`CHANGE_SAVE` | `OK`、`BAD_REQUEST`、`ENROLL_REQUIRED`、`DB_ERROR` | 新しいMatrix選択と保存。sequence値は記録しない。 |
| `CHANGE_CHOOSE_METHOD`、`CHANGE_METHOD_SAVE` | `OK`、`BAD_REQUEST`、`ENROLL_REQUIRED`、`DB_ERROR` | MFA方式の選択・保存。`CHANGE_METHOD_SAVE`のdetailには選択方式だけが記録される。 |
| `CHANGE_BACK_MENU` | `OK`、`BAD_REQUEST` | 変更途中でメニューへ戻った操作。 |

`graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs`に送信元IPが一致したLDAP認証では、
`CHANGE_LDAP_AUTH`の`detail`に`ip_limit=bypassed`を付加する。これは独立IP全体制限だけを
除外したことを示し、キー別制限やLDAP認証そのものを省略したことを意味しない。

### 2.4 管理API event

管理APIを有効化している場合のみ記録される。

| event | 主なresult | 意味 |
| --- | --- | --- |
| `API_DENIED` | `DISABLED`、`CONFIG_ERROR`、`FORBIDDEN`、`RATE_LIMITED`、`UNAUTHORIZED` | API無効、token未設定、許可IP外、無効token、rate limitを示す。 |
| `API_USER_UPDATED` | `OK` | APIによる利用者登録・更新。detailにはMFA方式とsequence長だけが入る。 |
| `API_USER_DELETED` | `OK`、`NOT_FOUND` | APIによる利用者削除。 |
| `API_METHOD_CHANGED` | `OK`、`NOT_FOUND` | APIによるMFA方式変更。 |
| `API_ERROR` | `ERROR` | 予期しないAPI処理エラー。詳細な例外原因は`idp-process.log`でも確認する。 |

## 3. SP管理・属性ガバナンス監査ログ

### 3.1 SP管理監査ログ

`graphicalmatrix-sp.sh`の変更操作は、次の形式で
`/opt/shibboleth-idp/logs/graphicalmatrix-sp-management-audit.log`へ記録される。

```text
ts=2026-08-05T01:23:45.678Z event=SP_ADD actor=admin name=research-portal entity_id=https://sp.example.org/shibboleth result=OK detail=-
```

| フィールド | 意味 |
| --- | --- |
| `actor` | `sudo`実行時は`SUDO_USER`、それ以外は実行ユーザー。 |
| `name` | SP管理名。entityIDやmetadata provider IDとは別の値。 |
| `entity_id` | 対象SPのentityID。 |
| `result` | `OK`または`FAILED`。失敗時の詳細な原因はCLI標準エラーと`idp-process.log`も確認する。 |

主なeventは`SP_INIT`、`SP_ADD`、`SP_UPDATE`、`SP_SET_ATTRIBUTES`、`SP_SET_MFA`、
`SP_ENABLE`、`SP_DISABLE`、`SP_REMOVE`、`SP_ADOPT`、`SP_ROLLBACK`、`SP_RESTORE_LEGACY`である。

### 3.2 SP別属性アクセス制御監査ログ

`graphicalmatrix.sp.access.enabled = true`かつ
`graphicalmatrix.sp.access.auditDecisions = true`の場合、SAML Response発行前の判定を
`/opt/shibboleth-idp/logs/graphicalmatrix-access-audit.log`へ記録する。

```text
ts=2026-08-05T01:23:45.678Z event=ACCESS_POLICY_ALLOW user=user001 sp=https://sp.example.org/shibboleth policy_revision=2 reason=ALL_ALLOW_CONDITIONS_MATCHED
```

| event | 意味 |
| --- | --- |
| `ACCESS_POLICY_ALLOW` | 対象SPの属性アクセス制御により許可された。 |
| `ACCESS_POLICY_DENY` | 対象SPの属性アクセス制御により拒否された。`reason`を確認する。 |

代表的な`reason`は`ALL_ALLOW_CONDITIONS_MATCHED`、`EXPLICIT_DENY`、`ATTRIBUTE_MISSING`、
`ATTRIBUTE_VALUE_MISMATCH`、`ATTRIBUTE_CONTEXT_MISSING`である。属性値やallow/deny条件そのものは
監査ログへ記録しない。`ATTRIBUTE_MISSING`ではLDAP値の有無だけでなく、Attribute Resolverの定義と
Resolver bindユーザーの読取り権限を確認する。

CLIの設定・検証操作は同じSP管理監査ログに、`ACCESS_INIT`、`ACCESS_POLICY_SET`、
`ACCESS_POLICY_ENABLE`、`ACCESS_POLICY_DISABLE`、`ACCESS_POLICY_CLEAR`、`ACCESS_POLICY_TEST`、
`ATTRIBUTE_DISCOVER`、`ATTRIBUTE_APPROVE`、`ATTRIBUTE_BLOCK`、`ATTRIBUTE_PROFILE_CREATE`、
`ATTRIBUTE_PROFILE_UPDATE`、`ATTRIBUTE_PROFILE_REMOVE`、`ATTRIBUTE_PROFILE_IMPORT_LEGACY`として記録される。

## 4. Admin Tools CSVプロビジョニングログ

Admin Toolsの`graphicalmatrix-csv-import-runner.sh`を使用する場合、既定で
`/opt/graphicalmatrix-admin/logs/csv-import.log`へ記録される。設定値
`graphicalmatrix.admin.csv.logFile`で変更できる。

| event | 意味 | 運用上の確認 |
| --- | --- | --- |
| `CSV_IMPORT_START` | CSVを安全なprocessing領域へsnapshotし、行数・無効化数・SHA-256を記録した。 | `rows`、`disables`、`sha256`が想定どおりか確認する。 |
| `CSV_IMPORT_DRYRUN_ONLY` | dry-run成功後、auto apply条件を満たさず反映しなかった。 | `autoApply`と操作種別の許可設定を確認する。 |
| `CSV_IMPORT_APPLY_OK` | `--apply`による反映が成功した。 | `processed/`へ移動したCSVとDB結果を確認する。 |
| `CSV_IMPORT_OK` | CSV処理が正常終了し、processed領域へ移動した。 | `APPLY_OK`が無ければdry-runのみである。 |
| `CSV_IMPORT_FAIL` | CSV検証またはDB反映が失敗し、failed領域へ移動した。 | 同じログ内のDB CLI出力とfailed内のCSVを確認する。 |
| `CSV_IMPORT_SKIP` | 不正ファイル名または通常ファイル以外を処理対象外にした。 | SCP/SFTP転送方式、ファイル名、symlink混入を確認する。 |
| `CSV_IMPORT_RUNNER_FAIL` | runnerの事前条件または実行環境で失敗した。 | `admin.properties`、権限、host制限、client certificate、DB接続を確認する。 |

CSVの本文とsequenceは、runnerログに出力しない前提である。処理対象CSV自体には秘密情報が含まれ得るため、
`incoming/`、`processing/`、`processed/`、`failed/`の閲覧権限と保存期間を別途管理する。

## 5. Shibboleth IdP・Jettyログ

`idp-process.log`、`idp-warn.log`、`idp-audit.log`の有無・出力先・出力レベルは、Shibboleth IdPの
logback設定に依存する。通常は`/opt/shibboleth-idp/logs/`配下にあるが、存在しない場合に空ファイルを
作らず、まず実際の配置を確認する。

```bash
# 実在するIdP標準ログのパスを確認する。存在しないログを前提にしない。
sudo find /opt/shibboleth-idp -type f \
  \( -name 'idp-process.log' -o -name 'idp-warn.log' -o -name 'idp-audit.log' \) \
  -print

# metadata provider、Attribute Resolver、Spring初期化、例外の直近記録を確認する。
sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log

# Jettyの起動・停止・JVM終了・systemd restart理由を確認する。
sudo journalctl -u jetty-idp.service -n 200 --no-pager
```

次の症状では、先に`idp-process.log`とJetty journalを確認する。

| 症状 | 主な確認語 | 代表的な原因 |
| --- | --- | --- |
| IdPがHTTP 503を返す | `BeanCreationException`、`Failed to load`、`MetadataResolverService` | metadata provider XML不正、metadataファイルの読取り権限、IdP service初期化失敗。 |
| SPだけがログインできない | `metadata`、`MetadataResolver`、`AttributeFilter` | entityID不一致、metadata未読込、属性filterエラー。 |
| 属性アクセス制御が`ATTRIBUTE_MISSING` | `AttributeResolver`、`LDAP`、`AccessDenied` | Attribute Resolver未定義、LDAP bindユーザーに読取り権限がない。 |
| Matrix開始時に追加認証情報を確認できない | `SQLException`、`ConnectException`、`Timeout` | DB未起動、接続情報・証明書・DB権限・接続プールの問題。 |

## 6. 障害時の確認順序

### 6.1 Matrix照合失敗またはロック

```bash
# challenge発行、照合結果、ロック状態を時系列で確認する。userは実際のユーザーIDへ置き換える。
sudo grep -E 'event=(CHALLENGE_CREATED|VERIFY|START).*user=USER' \
  /opt/shibboleth-idp/logs/graphicalmatrix-audit.log | tail -n 100

# DB保存構成では、登録状態、失敗回数、ロック期限、MFA方式を確認する。値は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh show USER
```

`VERIFY result=FAIL`は画像選択の不一致であり、パスワード失敗を直接示すものではない。
`START result=DB_ERROR`はDB接続・保存情報の確認が必要な状態である。

### 6.2 自己管理への遷移失敗

```bash
# 自己管理の第2要素確認と一回限りhandoffの結果を確認する。
sudo grep -E 'event=(SELF_SERVICE_AUTH|SELF_SERVICE_HANDOFF)' \
  /opt/shibboleth-idp/logs/graphicalmatrix-audit.log | tail -n 100

# AccessDeniedやflow例外の直近原因をIdP処理ログから確認する。
sudo grep -nE 'AccessDenied|FlowExecutionException|AuthenticationException|Caused by:' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 100
```

`SELF_SERVICE_AUTH result=OK`の後に`SELF_SERVICE_HANDOFF result=OK`が続けば、
自己管理の再認証と変更画面への引継ぎは完了している。

### 6.3 SP追加・metadata更新失敗

```bash
# 対象SPのCLI管理状態とmetadata digestを確認する。変更は行わない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify SP_NAME

# SP管理CLIによる反映・rollbackの成功失敗を確認する。
sudo tail -n 100 /opt/shibboleth-idp/logs/graphicalmatrix-sp-management-audit.log

# MetadataResolverServiceの起動・reload失敗原因を確認する。
sudo grep -nE 'MetadataResolverService|metadata-providers\.xml|LocalDynamicMetadataProvider|Failed to load' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 100
```

手作業登録SPの`FilesystemMetadataProvider`が参照するmetadataファイルをJetty実行ユーザーが読めないと、
対象SPだけでなく`MetadataResolverService`全体の起動に失敗することがある。

### 6.4 CSVプロビジョニング失敗

```bash
# CSV runnerの監査ログから、処理段階、対象ファイル名、dry-run・applyの結果を確認する。
sudo tail -n 200 /opt/graphicalmatrix-admin/logs/csv-import.log

# systemd path監視が待機状態か確認する。CSV未到着時のactive (waiting)は正常である。
sudo systemctl status graphicalmatrix-csv-import.path --no-pager -l

# 最後に起動したCSV取り込みserviceの標準出力・標準エラーを確認する。
sudo journalctl -u graphicalmatrix-csv-import.service -n 100 --no-pager
```

`CSV_IMPORT_DRYRUN_ONLY`は失敗ではない。`autoApply = false`、操作種別が未許可、または安全条件により
反映を見送ったことを示す。`CSV_IMPORT_FAIL`では`failed/`の同名CSVと、直前のDB CLI出力を確認する。

## 7. 保持、ローテーション、転送

GraphicalMatrix監査ログのlogrotate例は[LOGROTATE.md](./LOGROTATE.md)と
`examples/logrotate/graphicalmatrix-audit`にある。例では日次rotationと180世代保持を行うが、
実際の保存日数は組織の監査要件、容量、Dashboard再取り込み要件に合わせて決める。

SP管理監査ログ、アクセス制御監査ログ、Admin Tools CSVログ、IdP標準ログ、systemd journalは、
同じrotation設定を自動的に継承しない。それぞれのローテーション・journal保持・集中ログ転送を設定する。
Dashboardのイベント保持期間は、元ログの保存期間とは別設定である。Dashboard障害時に再取り込みできるよう、
元の`graphicalmatrix-audit.log`とrotation済みファイルを必要期間保持する。

ログを集中管理基盤へ転送する場合も、次を守る。

- 監査ログは改行を含まない1行イベントのまま保存し、途中でJSON化・整形して原文を失わない。
- IdP nodeごとにファイル・host・node IDを区別し、複数ノードのeventを混在させない。
- ログ閲覧者にTOTP seed、password、API tokenを出力する運用を作らない。
- 送信元IPとユーザーIDのマスキング・hash化は、インシデント調査とのトレードオフを合意してから行う。
- ログ書込み失敗は認証を停止させない設計である。監査ログの欠落を監視し、書込み権限・容量・rotation後の所有者を定期確認する。

## 8. Dashboardとの関係

Standalone Dashboardは、既定で`graphicalmatrix-audit.log`の構文を入力として解析する。
ログ行の先頭形式`ts=... event=... user=... result=... ip=... session=... challenge=... detail=...`を
独自に変更すると、Dashboard Agentのparser failureやイベント欠落につながる。

Dashboard利用時のAgent設定、収集遅延、オフラインimport、保持期間、複数IdP nodeの集約は
[DASHBOARD.md](./DASHBOARD.md)を参照する。Dashboardは参照専用であり、認証処理、LDAP、MFA保存DB、
IdP管理APIへ接続しない。
