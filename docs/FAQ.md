# 2FAS-KW FAQ

2FAS-KW Plugin for Shibboleth IdP の設定、認証、運用で発生しやすい事象をまとめる。

## `graphicalmatrix.properties` の読込エラーはどのような場合に発生するか

現在の実装では、主に以下の場合に `graphicalmatrix.properties` の読込エラーが発生する。

### 数値設定が不正

以下の設定はエラーになる。

- `graphicalmatrix.columns` または `graphicalmatrix.rows` が `0` 以下
- `graphicalmatrix.choice` が `0` 以下
- `graphicalmatrix.order` が `1` または `2` 以外
- `graphicalmatrix.challenge.seconds` が `30` から `900` の範囲外
- `graphicalmatrix.view.css.cacheSeconds` が負数
- 数値項目に数値以外の文字列を指定

エラーになる設定例:

```properties
graphicalmatrix.columns = five
graphicalmatrix.order = 3
graphicalmatrix.challenge.seconds = 10
```

### 画像数とマトリクスのセル数が一致しない

有効画像数は、必ず以下と一致させる。

```text
有効画像数 = graphicalmatrix.columns * graphicalmatrix.rows
```

次の設定では、画面のセル数が `1 * 5 = 5` であるのに対して、有効画像数が25個あるためエラーになる。

```properties
graphicalmatrix.columns = 1
graphicalmatrix.rows = 5
graphicalmatrix.graphicals = img01-25
```

想定されるエラー:

```text
GraphicalMatrix graphical count must match columns * rows.
graphicals=25, cells=5
```

`graphicalmatrix.not_graphicals` で除外した後の画像数も、有効画像数として判定される。

次の設定では、25個から `img25` が除外されて24個になるため、`5 * 5 = 25` と一致せずエラーになる。

```properties
graphicalmatrix.columns = 5
graphicalmatrix.rows = 5
graphicalmatrix.graphicals = img01-25
graphicalmatrix.not_graphicals = img25
```

### 選択数が有効画像数を超えている

重複選択を禁止している場合、`graphicalmatrix.choice` は有効画像数以下にする。

次の設定では、5個の画像に対して6個の選択を要求するためエラーになる。

```properties
graphicalmatrix.columns = 1
graphicalmatrix.rows = 5
graphicalmatrix.graphicals = img01-05
graphicalmatrix.choice = 6
graphicalmatrix.allow_duplicates = 0
```

### 画像範囲の書式が不正

以下のような範囲指定はエラーになる。

```properties
# 開始番号が終了番号より大きい
graphicalmatrix.graphicals = img25-01

# 開始と終了の接頭辞が一致しない
graphicalmatrix.graphicals = img01-photo25

# 数字を含まない範囲
graphicalmatrix.graphicals = imgA-imgZ
```

範囲指定には、次のように同じ接頭辞と数値を使用する。

```properties
graphicalmatrix.graphicals = img01-25
```

### エイリアス設定が不正

以下の設定はエラーになる。

```properties
# 区切りの「:」がない
graphicalmatrix.aliases = A-img01

# 対応する画像IDがない
graphicalmatrix.aliases = A:

# 有効画像に存在しない画像IDを参照している
graphicalmatrix.aliases = A:img99
```

エイリアスは、次の形式で有効画像に対応付ける。

```properties
graphicalmatrix.aliases = A:img01,B:img02,C:img03
```

### LDAP変更画面のレート制限設定が不正

`graphicalmatrix.change.ldapRateLimit.enabled = true` の場合、以下の条件を満たす必要がある。

- `graphicalmatrix.change.ldapRateLimit.failureLimit` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.windowSeconds` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.lockSeconds` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.key` は `ip`、`user`、`ip-user` のいずれか
- `graphicalmatrix.change.ldapRateLimit.ipFailureLimit` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.ipWindowSeconds` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.ipLockSeconds` は `1` 以上
- `graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs` は空、またはIPv4/IPv6 CIDRを
  カンマ区切りで最大256件。ホスト名、prefixなしの単一IP、空要素は使用不可

設定例:

```properties
graphicalmatrix.change.ldapRateLimit.enabled = true
graphicalmatrix.change.ldapRateLimit.failureLimit = 5
graphicalmatrix.change.ldapRateLimit.windowSeconds = 300
graphicalmatrix.change.ldapRateLimit.lockSeconds = 900
graphicalmatrix.change.ldapRateLimit.key = ip-user
graphicalmatrix.change.ldapRateLimit.ipFailureLimit = 100
graphicalmatrix.change.ldapRateLimit.ipWindowSeconds = 60
graphicalmatrix.change.ldapRateLimit.ipLockSeconds = 300
graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs =
```

### 大規模な共有NATからLDAP変更画面を利用するにはどうすればよいか

同じNATアドレスから多数の利用者が一斉に操作する環境では、利用者ごとの入力誤りが
`ipFailureLimit`へ合算され、正常な利用者までIP全体制限へ巻き込む可能性がある。
信頼済みの学内・社内ネットワークに限り、独立したIP全体制限だけを除外できる。

```properties
# 利用者ごとの制限は維持する。
graphicalmatrix.change.ldapRateLimit.enabled = true
graphicalmatrix.change.ldapRateLimit.failureLimit = 5
graphicalmatrix.change.ldapRateLimit.windowSeconds = 300
graphicalmatrix.change.ldapRateLimit.lockSeconds = 900
graphicalmatrix.change.ldapRateLimit.key = ip-user

# このCIDRでは独立IP全体制限だけを除外する。
graphicalmatrix.change.ldapRateLimit.ipLimitBypassCIDRs = 192.168.0.0/16,10.0.0.0/8
```

CIDRに一致しても、`failureLimit`によるキー別制限は継続する。既定の`key=ip-user`なら、
同じNAT配下でもユーザーIDごとに別の失敗カウンターとなる。`key=ip`ではキー別制限も
共有IP単位になるため、この用途では`user`または`ip-user`を使用する。

この設定はLDAP保護全体のホワイトリストではなく、`ipFailureLimit`、`ipWindowSeconds`、
`ipLockSeconds`で構成する独立IP全体制限だけの除外である。通常のShibboleth Password認証、
GraphicalMatrix画像照合のロック、LDAPサーバー自身のロック方針には影響しない。
接続元はServletが認識したIPを使用する。リバースプロキシ環境ではプロキシIPになる場合があるため、
信頼できるプロキシ構成と実際の`graphicalmatrix-audit.log`の`ip`を確認してCIDRを決定する。

設定保存後は次のリクエストから反映され、通常はJetty再起動を必要としない。適用前に設定検査を行う。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

### GraphicalMatrixロックアウト設定が不正

GraphicalMatrix画像列照合のロックアウト設定は、以下の条件をすべて満たす必要がある。

- `graphicalmatrix.lockout.failureLimit` は `1`から`100`
- `graphicalmatrix.lockout.lockSeconds` は `1`から`2592000`
- `graphicalmatrix.lockout.maxLockFailureCount` は `failureLimit`より大きく`1000`以下
- `graphicalmatrix.lockout.maxLockSeconds` は `lockSeconds`以上、最大`2592000`

既定設定:

```properties
graphicalmatrix.lockout.failureLimit = 5
graphicalmatrix.lockout.lockSeconds = 900
graphicalmatrix.lockout.maxLockFailureCount = 10
graphicalmatrix.lockout.maxLockSeconds = 2592000
```

既定では1回目から4回目はロックせず、5回目から9回目は15分ロック、10回目以降は
30日ロックする。ロック期限の経過だけでは `failed_count` は0へ戻らない。
認証成功または管理者のunlock・RESET等でリセットされる。

`maxLockSeconds=0` を永久ロックとして指定することはできない。設定検査で
`CONFIG_CHECK_FAILED` になった場合は、4設定の大小関係をまとめて確認する。

### プロパティファイル自体を読み込めない

以下の場合も設定読込に失敗する可能性がある。

- IdP実行ユーザーにプロパティファイルの読取権限がない
- Java Propertiesとして不正なエスケープがある
- 設定したパス文字列がOS上で不正
- ファイルの読込中にI/Oエラーが発生した

設定ファイルが存在しない場合は、現在の実装ではエラーではなく既定値が使用される。

## 設定変更はいつ反映されるか

`graphicalmatrix.properties` は認証画面や変更画面などへのリクエスト時に読み込まれる。
設定を保存した瞬間に進行中の処理が切り替わるのではなく、保存後の次回アクセスから新しい設定が使用される。

- IdPの再起動は通常不要
- 新しく開始した認証や変更画面では新しい設定を使用
- 進行中の認証セッションには、開始時の設定が残る場合がある
- `graphicalmatrix-db.sh` は次回実行時に新しい設定を使用
- DB内の既存 `sequence` と `initial_sequence` は自動変換されない

`graphicalmatrix.choice` を変更した場合は、既存ユーザーのsequence数も新しい設定に合わせる必要がある。

## 手作業で登録済みのSPをSP管理CLIの対象へ移行するにはどうすればよいか

既存SPは、v1.3.0へ更新しただけでは変更されない。CLI管理を使わないSPは、従来の
`FilesystemMetadataProvider`設定のまま運用できる。`init --apply`は管理CLIの共通基盤を作るだけで、
既存SPを自動的に移行しない。

```
# 現状のステータスを調査する
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status

`EXISTING_LOCAL`　と表示されているSPがあれば手動登録
```

nextで次になにを行うべきかを調査する
```
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next
```

移行前に、`/opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties`を編集する。
既存値を消さず、metadata内のACSホスト名を`allowedAcsHosts`へカンマ区切りで追加する。
`adopt`では外部metadataを取得しないため、`allowedHosts`への追加は不要である。

```properties
graphicalmatrix.sp.management.enabled = true
graphicalmatrix.sp.metadata.allowedAcsHosts = existing-sp.example.org

# IdPがlocalhost:80で待ち受けていない場合だけ、実際のloopback listenerを指定する。
graphicalmatrix.sp.reload.baseUrl = http://127.0.0.1:8080/idp
```

`existing-sp.example.org`はmetadataに記載されたACSのFQDNへ置き換える。複数SPがある場合は、既存値を
残してカンマ区切りで追記する。`reload.baseUrl`には`/status`、`/profile`、外部公開URLを付けず、
IdP context pathまでを指定する。IdPが`http://localhost:80/idp`で待ち受ける場合は、この行を空のままにする。

ステータスを確認、HTTP/1.1 200 OKなど。間違っていたら、graphicalmatrix.sp.reload.baseUrlを修正すること。
```
curl --noproxy '*' -fsSI http://127.0.0.1:8080/idp/status
```
nextで次になにを行うべきかを調査する
```
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next
```

`adopt`の前に、管理基盤を一度だけ初期化する必要がある。`init --apply`の後は、metadata providerを
実行中のIdPへ読み込ませるため、必ずJettyを再起動する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init --apply
sudo systemctl restart jetty-idp.service
sleep 5
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
```

### 初期化後のJetty再起動とmetadata reloadエラーを解消する

`init --apply`は`metadata-providers.xml`を更新するが、実行中IdPには反映しない。このため、初期化後の
Jetty再起動は必須である。次の待機ループでlistenerの起動完了を確認する。

```bash
sudo systemctl restart jetty-idp.service

until curl --noproxy '*' -fsSI --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/idp/status >/dev/null 2>&1; do
  echo 'Waiting for Jetty...'
  sleep 2
done
```

通常はここまででよい。`adopt --apply`、新規SPの`add --apply`、metadata更新の`update --apply`は、
成功時にCLI自身が`reload-metadata.sh`を実行するため、初期化直後に手動でreloadする必要はない。
`graphicalmatrix.sp.reload.baseUrl`は、このCLI内の自動reloadの接続先として事前に設定する。

次の手動reloadは、IdPのadmin reload endpoint、managed provider、および`reload.baseUrl`の接続先を
事前確認したい場合、またはCLIがmetadata reloadエラーを出した場合だけ実行する。

```bash
sudo env IDP_BASE_URL='http://127.0.0.1:8080/idp' \
  /opt/shibboleth-idp/bin/reload-metadata.sh \
  -id GraphicalMatrixManagedSPMetadata
```

成功時は`Metadata reloaded for 'GraphicalMatrixManagedSPMetadata'`と表示される。手動reloadが失敗しても、
まずJettyの起動完了と`metadata-providers.xml`の設定を確認する。

エラー別の意味と対処は以下のとおりである。

| エラー | 原因 | 対処 |
| --- | --- | --- |
| `http://localhost/idp ... 404 Not Found` | IdPがlocalhost:80で待ち受けていない。 | `graphicalmatrix.sp.reload.baseUrl`を`http://127.0.0.1:8080/idp`のような実際のloopback listenerへ設定する。 |
| `Metadata source not found` | `init --apply`後のJetty再起動が未実施で、実行中IdPにmanaged providerがない。 | `metadata-providers.xml`に`GraphicalMatrixManagedSPMetadata`があることを確認し、Jettyを再起動してからreloadする。 |
| `MetadataResolverService is unavailable` | いずれかのmetadata providerが起動時にfail-fastで失敗した。 | 次節の既存metadataファイルの読み取り検査を行い、修正後にJettyを再起動する。 |
| `接続を拒否されました` | Jetty再起動直後で8080 listenerがまだ準備できていない、または起動失敗。 | 上記の待機ループで準備完了を確認する。待機が続く場合は`journalctl -u jetty-idp.service -n 160 --no-pager`を確認する。 |

nextで次になにを行うべきかを調査する
```
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next
```
移行する場合は、対象SPを1件ずつ確認して`adopt`する。最初に既存SPとして一意に検出されることを
確認する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status \
  --entity-id 'https://existing-sp.example.org/shibboleth'
```

`STATUS`が`EXISTING_LOCAL`、`TYPE`が`FilesystemMetadataProvider`なら移行候補である。
`DUPLICATE`、`INVALID_METADATA`、複数行、または`MANAGED`の場合は`adopt`せず、既存のmetadata設定を
確認する。

`FilesystemMetadataProvider`のmetadataファイルはJetty実行ユーザーが読み取れなければならない。
読めない場合、対象SPだけでなく`MetadataResolverService`全体がfail-fastで停止する。次の手順は
`status`出力の`metadata_file=`から実ファイルの絶対パスを自動取得する。

```bash
# 対象SPのentityIDを直接指定する。
ENTITY_ID='https://existing-sp.example.org/shibboleth'

#　このまま実行
METADATA_FILE="$(sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status \
  --entity-id "$ENTITY_ID" | awk -F= \
  '/^[[:space:]]*metadata_file=/{print $2; exit}')"

sudo test -f "$METADATA_FILE" || {
  echo "ERROR: status did not return an existing metadata_file for: $ENTITY_ID" >&2
  exit 1
}
printf 'metadata_file=%s\n' "$METADATA_FILE"

# 2. IdPには読み取りだけを許可し、SELinux contextを復元する。
sudo chown root:jetty "$METADATA_FILE"
sudo chmod 0640 "$METADATA_FILE"
sudo restorecon -v "$METADATA_FILE"

# 3. Jettyとして読み取れることを確認する。
sudo -u jetty test -r "$METADATA_FILE" && \
  echo 'OK: Jetty can read existing SP metadata'
```

ここで失敗した場合は`adopt`を実行しない。すでにIdPが503やmetadata reloadエラーになっている場合は、
上記を修正してからJettyを再起動する。

```bash
sudo systemctl restart jetty-idp.service
curl --noproxy '*' -fsSI http://127.0.0.1:8080/idp/status
```

dry-runで移行内容を確認する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh adopt existing-sp \
  --entity-id 'https://existing-sp.example.org/shibboleth'
```

`source_provider`、`metadata_sha256`、`attribute_profile`、`mfa_profile`を確認後、適用する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh adopt existing-sp \
  --entity-id 'https://existing-sp.example.org/shibboleth' \
  --apply \
  --confirm 'https://existing-sp.example.org/shibboleth'

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify existing-sp
```

nextで次になにを行うべきかを調査する
```
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next
```

list を表示して登録されていることを確認する。
```
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list

MANAGEDとなっていれば、登録済み。
```

`小文字と大文字に注意：existing-sp`は管理用の名前であり、実在するSP名に置き換える。`status`に表示された`SOURCE`は既存の
metadata provider IDであり、管理名ではないため、そのまま指定しない。使用可能な形式は
`[a-z0-9][a-z0-9-]{0,62}`である。移行後はSPからログインし、MFAと属性releaseを確認する。
移行前の手作業状態へ戻す必要がある場合は、`restore-legacy`を使用する。

## 2つ目以降のSPを追加するにはどうすればよいか

`/opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties`の許可リストへ、既存のFQDNを
残したまま新しいSPのFQDNをカンマ区切りで追加する。

```properties
graphicalmatrix.sp.management.enabled = true
graphicalmatrix.sp.metadata.allowedHosts = new-sp.example.org,new-sp2.example.org
graphicalmatrix.sp.metadata.allowedAcsHosts = new-sp.example.org,new-sp2.example.org
```

`graphicalmatrix-sp.sh add`はこのpropertiesを自動更新しない。許可リストは管理者が事前に定める
信頼境界であり、CLI入力だけで外部接続先やACSの許可範囲を広げないためである。2つ目以降のSPでは
`init --apply`を再実行する必要はない。

最初にdry-runする。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp2 \
  --entity-id https://new-sp2.example.org/shibboleth \
  --metadata-url https://new-sp2.example.org/Shibboleth.sso/Metadata \
  --attribute-profile uid \
  --mfa force
```

表示された`metadata_sha256`、entityID、ACS、証明書fingerprintを確認し、一致したdigestを指定して
適用する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp2 \
  --entity-id https://new-sp2.example.org/shibboleth \
  --metadata-url https://new-sp2.example.org/Shibboleth.sso/Metadata \
  --attribute-profile uid \
  --mfa force \
  --approve-sha256 METADATA_SHA256 \
  --apply

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify new-sp2
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list
```

propertiesの変更は次回のCLI起動から読み込まれるため、その変更だけを反映する目的でJettyを再起動
する必要はない。詳しい確認項目は[INSTALL_NEW_SP.md](./INSTALL_NEW_SP.md)を参照する。

## SPごと、送信元IPごとにMFAの要否を変更するにはどうすればよいか

SP単位の設定は`set-mfa`、IdP全体の設定は`mfa`を使用する。まず現在の実効設定と
CLI管理SPに手作業差分がないことを確認する。

```bash
# IdP全体設定、SP別設定、手作業差分の有無を表示する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show

# 指定したSPと送信元IPに対する実効判定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp SP_NAME --ip 192.0.2.10
```

`managed_policy_drift`が`OK`でない場合は、CLI管理SPに対応する設定へ手作業差分があるため、
以後の変更を行う前に台帳との差分を確認してCLIで修復する。

```bash
# 台帳から復元される内容とplan_sha256を確認する。まだファイルは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  mfa reconcile --from-registry

# 内容を確認後、表示されたplan_sha256を指定して台帳の状態へ戻す。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  mfa reconcile --from-registry \
  --approve-sha256 PLAN_SHA256 \
  --apply
```

CLIは次のファイルを安全に更新する。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
```

全SPに適用する送信元CIDR例外を変更する例を示す。

```bash
# 変更後の全設定とplan_sha256を確認する。まだファイルは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --bypass-cidrs '192.168.10.0/24,10.20.0.0/16'

# 同じ値と確認済みplan_sha256を指定して適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --bypass-cidrs '192.168.10.0/24,10.20.0.0/16' \
  --confirm-bypass \
  --approve-sha256 PLAN_SHA256 \
  --apply
```

MFA方針の運用変更はCLIで行う。CLI管理SPに対応する`forceSPs`、`bypassSPs`、`requiredSPs`、
`bypassSpCidrs`をpropertiesファイルで直接変更してはならない。最初に`list`で対象SPのCLI管理名を確認する。

```bash
# NAME列に表示されるCLI管理名を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list
```

機微なSPで常にMFAを要求する場合は`force`を使用する。

```bash
# 変更予定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa sensitive-sp force

# 確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa sensitive-sp force --apply
```

公開SP全体でMFAを不要にする場合は`bypass`を使用する。MFAを弱める変更であるため、適用時に
`--confirm-bypass`が必要である。

```bash
# 変更予定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa public-sp bypass --confirm-bypass

# 確認後に明示承認して適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa public-sp bypass --confirm-bypass --apply
```

指定したSP群だけをMFA必須にする選択型運用では、対象SPごとに`required`を設定する。

```bash
# 1件目の変更予定を確認し、確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa sp1 required
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa sp1 required --apply

# 2件目の変更予定を確認し、確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa sp2 required
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa sp2 required --apply
```

`required`を1件でも設定すると、`requiredSPs`ルールを評価した時点で、設定したSPだけがMFA必須となり、
それ以外のSPではMFAを要求しない。既定順序では先行するbypassルールが先に評価される。
全SPをMFA必須にしたい環境で、単に新規SPのMFAを有効にする目的では`required`を使用しない。

`--policy-order`はpropertiesファイルの行順ではなく、カンマ区切りで指定した左から順に評価する。
最初にMFA必須またはMFA不要を決定したルールで評価を終了する。指定できるルールは次の6つである。

| ルール | 意味 |
| --- | --- |
| `forceSPs` | 指定SPでMFAを強制する。 |
| `bypassSPs` | 指定SPでMFAを不要にする。 |
| `bypassSpCidrs` | 指定SPとCIDRの両方が一致した場合にMFAを不要にする。 |
| `bypassNetwork` | `bypassIPs`または`bypassCIDRs`が一致した場合にMFAを不要にする。 |
| `requiredSPs` | リストが空でなければ、対象SPだけをMFA必須にする。 |
| `default` | 最終的な既定値を適用する。 |

6ルールを各1回含め、`default`を最後にする。未知の名前、重複、欠落、末尾以外の`default`は
設定エラーとなる。`requiredSPs`が空でない場合は必ずMFA必須またはMFA不要を決定するため、
その後ろのルールには到達しない。自己管理フローはこの順序の対象外で、常にMFAを要求する。

`bypass`はSP全体、`mfa global set`の`--bypass-ips`と`--bypass-cidrs`は全SPを判定対象とする。
特定のSPで、かつ指定CIDRの場合だけMFAを不要にする場合は`sp-cidr-bypass`を使用する。
単一のIPv4アドレスは`/32`で指定する。

```bash
# sp1で指定CIDRの場合だけMFAを不要にする変更予定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa sp1 sp-cidr-bypass --cidrs '192.168.10.0/24'

# 確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa sp1 sp-cidr-bypass --cidrs '192.168.10.0/24' --apply

# sp2に複数CIDRを指定する場合も、同じ方法で設定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa sp2 sp-cidr-bypass --cidrs '10.20.0.0/16,10.21.0.0/16'
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa sp2 sp-cidr-bypass --cidrs '10.20.0.0/16,10.21.0.0/16' --apply
```

CLIは管理名からSP entityIDを解決するため、利用者向けURL、ACS URL、IdPのSSOエンドポイントを
コマンドへ指定しない。適用後は`mfa show`と`mfa test`で設定と実効判定を確認する。
通常のSPログインを実行した後は、IdPログの`sp=`からも実際に判定されたentityIDを確認できる。

```bash
sudo grep -E 'MFA (policy decision|method decision)' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 30
```

次のログの場合、MFA判定で使用されたSP entityIDは`https://sp1.example.org/shibboleth`である。

```text
MFA policy decision: rule=default, result=require, sp=https://sp1.example.org/shibboleth, ip=192.168.10.20
```

学内・社内CIDRでは通常SPのMFAを省略し、機微なSPだけは同じCIDRからでもMFAを強制する場合は、
機微なSPを`force`にし、IdP全体の既定値、評価順、CIDR例外をCLIで設定する。

```bash
# 機微なSPをforceへ変更する予定を確認し、確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa sensitive-sp force
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-mfa sensitive-sp force --apply

# IdP全体設定の変更予定とplan_sha256を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --default require \
  --policy-order 'forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default' \
  --bypass-cidrs '192.168.0.0/24'

# 同じ値と確認済みplan_sha256を指定して適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --default require \
  --policy-order 'forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default' \
  --bypass-cidrs '192.168.0.0/24' \
  --confirm-bypass \
  --approve-sha256 PLAN_SHA256 \
  --apply
```

この例では、`192.168.0.1`から機微なSPへアクセスすると`forceSPs`でMFA必須となり、通常SPへ
アクセスすると`bypassNetwork`でMFA不要となる。学外IPから通常SPへアクセスした場合は
`default=require`が適用される。

### `mfa show`でSPが`force`の場合、全体CIDR除外は適用されるか

次のように、対象SPが`mfa=force`で、既定の評価順では`forceSPs`が`bypassNetwork`より前にある場合、
送信元が`bypass_cidrs`に含まれていても、そのSPではMFAが強制される。

```text
default=require
policy_order=forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default
bypass_ips=
bypass_cidrs=192.0.2.0/24
use_forwarded_for=false
managed_policy_drift=OK
sp=local-test-sp status=ACTIVE mfa=force cidrs=
```

この例では、`local-test-sp`へのアクセスは最初の`forceSPs`でMFA必須と確定するため、後続の
`bypassNetwork`は評価されない。

このSPにも全体CIDR除外を適用する場合は、SP単位方針を`inherit`へ変更する。

```bash
# 変更予定を確認する。まだ設定ファイルは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa local-test-sp inherit

# 確認後、SP単位方針をinheritへ変更する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa local-test-sp inherit --apply

# 指定した送信元IPで実際にどのルールが適用されるか確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp local-test-sp --ip 192.0.2.10
```

`inherit`へ変更した後、この例の`192.0.2.0/24`からのアクセスは`bypassNetwork`でMFA不要となる。
CIDR外からのアクセスは、最後の`default=require`によってMFA必須となる。

### `force`、`required`、`inherit`はどう使い分けるか

`set-mfa`で指定する3つの代表的なprofileには、次の違いがある。

| profile | 対象SPの動作 | 全体CIDR除外 | 他のSPへの影響 |
| --- | --- | --- | --- |
| `force` | 対象SPでMFAを強制する。 | 既定順序では適用されない。 | なし。 |
| `required` | 対象SPをMFA必須リストへ追加する。 | 既定順序では先に適用される。 | リスト外のSPがMFA不要になる可能性がある。 |
| `inherit` | IdP全体のMFA方針に従う。 | 適用される。 | なし。 |

`force`は最初の`forceSPs`でMFA必須と確定する。全体CIDR除外の`bypassNetwork`より前に評価されるため、
機微なSPを送信元ネットワークにかかわらずMFA必須にする場合に使用する。

```bash
# 対象SPで常にMFAを要求する設定の変更予定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa local-test-sp force

# 確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa local-test-sp force --apply
```

`required`は`requiredSPs`へ対象SPを追加する。既定順序では`bypassNetwork`より後に評価されるため、
送信元が全体CIDR除外に一致した場合は対象SPでもMFA不要となる。

```bash
# 対象SPをMFA必須リストへ追加する設定の変更予定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa local-test-sp required

# 確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa local-test-sp required --apply
```

`required`を1件でも設定すると、`requiredSPs`に含まれないSPは、そのルールを評価した時点でMFA不要と判定される。
全SPをMFA必須にしたい環境で、単に対象SPのMFAを有効にする目的では使用しない。

通常の「全SPでMFA必須、ただし全体CIDR除外を適用する」という方針では、各SPを`inherit`、
IdP全体を`default=require`とする。特定のSPだけCIDR除外の対象外にする場合は、そのSPを`force`とする。

設定後は、対象SPとCIDR内外のIPを指定して実効判定を確認する。

```bash
# CIDR内の送信元IPに対する実効判定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp local-test-sp --ip 192.0.2.10

# CIDR外の送信元IPに対する実効判定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp local-test-sp --ip 203.0.113.10
```

優先順位を変更する場合は、`mfa global set --policy-order`で6ルールを残したまま並び替える。
例えば`bypassNetwork`を`forceSPs`より前に置くと、学内・社内CIDRから機微なSPへアクセスした場合も
MFA不要になるため、影響を理解した場合だけ適用する。

```bash
# 評価順の変更予定とplan_sha256を確認する。まだファイルは変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --policy-order 'bypassNetwork,forceSPs,bypassSPs,bypassSpCidrs,requiredSPs,default'

# 同じ評価順、確認済みplan_sha256、bypassの明示確認を指定して適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa global set \
  --policy-order 'bypassNetwork,forceSPs,bypassSPs,bypassSpCidrs,requiredSPs,default' \
  --confirm-bypass \
  --approve-sha256 PLAN_SHA256 \
  --apply

# 適用後の設定と、代表的なSP・送信元IPの実効判定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa test \
  --sp sensitive-sp --ip 192.168.0.10
```

## 画面のHTMLやCSSを編集するにはどうすればよいか

GraphicalMatrixプラグインの画面テンプレートとCSSは、通常、IdP本体の `views` ではなく次の場所に配置される。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/views
/opt/shibboleth-idp/conf/graphicalmatrix/assets
```

IdP本体の `/opt/shibboleth-idp/views` には `login.vm` や `totp.vm` などのShibboleth IdP本体またはIdPプラグイン用テンプレートが配置される。GraphicalMatrixの認証画面や変更画面を編集する場合は、原則として `/opt/shibboleth-idp/conf/graphicalmatrix/views` を編集する。

### IdP本体のログイン画面を編集する場合

ユーザーIDとパスワードを入力する最初のIdPログイン画面は、通常、次のVelocityテンプレートを編集する。

```text
/opt/shibboleth-idp/views/login.vm
```

IdP本体の画面全体に適用するCSSやロゴなどの画像は、通常、次の場所を編集または追加する。

```text
/opt/shibboleth-idp/edit-webapp/css/main.css
/opt/shibboleth-idp/edit-webapp/images/
```

`login.vm` では、`$actionUrl`、CSRF関連のhidden input、ユーザーID・パスワードのinput名を削除または変更してはならない。認証要求の送信やCSRF検証に必要である。

IdP本体の`views`、`edit-webapp/css`、`edit-webapp/images`を変更した後は、WARへ反映するため次を実行する。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

`/opt/shibboleth-idp/webapp/` 配下は`build.sh`で上書きされるため、直接編集しない。

主な編集対象は次のとおり。

| 目的 | ファイル |
|---|---|
| GraphicalMatrix認証画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/graphicalmatrix.html` |
| 変更開始画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-start.html` |
| 現在のGraphicalMatrix確認画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-current.html` |
| MFA方式変更メニュー | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-menu.html` |
| 新しいGraphicalMatrix選択画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-new.html` |
| MFA方式選択画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-method.html` |
| 変更完了画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/change-complete.html` |
| 2FAS-KW側のTOTP QR登録画面 | `/opt/shibboleth-idp/conf/graphicalmatrix/views/totp-register.html` |
| GraphicalMatrix画面CSS | `/opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css` |

設定ファイルでは、次の項目で参照先を確認できる。

```bash
sudo grep -E 'graphicalmatrix.view.template|graphicalmatrix.view.change|graphicalmatrix.view.css' \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
```

HTMLテンプレートとCSSだけを編集する場合は、通常、WAR再構築とJetty再起動は不要である。保存後、ブラウザを再読み込みして確認する。CSSのキャッシュを避けたい検証環境では、`graphicalmatrix.view.css.cacheSeconds = 0` にしておくと確認しやすい。

設定検査を行う場合は、次のコマンドを実行する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

一方で、選択済み画像リストの動的表示、ボタン押下時のJavaScript生成、Servlet処理など、Javaクラス側で生成している画面要素を変更した場合は、Plugin JARの更新、WAR再構築、Jetty再起動が必要になる。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

### TOTPとWebAuthnの画面を編集する場合

TOTPとWebAuthnは、どの画面を編集したいかで編集場所が異なる。

2FAS-KWが表示するTOTP QR登録画面は、GraphicalMatrix側の外部HTMLテンプレートである。MFA方式をTOTPへ変更した後、初回登録時に表示されるQRコード登録画面を変更する場合は、次のファイルを編集する。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/views/totp-register.html
```

このテンプレートは `/opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css` を使う。通常、WAR再構築とJetty再起動は不要で、保存後の次回表示から反映される。

一方、Shibboleth TOTP Plugin自体が表示するTOTP認証画面は、IdP本体側のVelocityテンプレートである。通常は次のファイルを編集する。

```text
/opt/shibboleth-idp/views/totp.vm
/opt/shibboleth-idp/views/totp-error.vm
```

WebAuthn Pluginの登録画面や認証画面は、Shibboleth WebAuthn Plugin側のテンプレートを編集する。配置は導入済みプラグインのバージョンやインストール方法で変わる可能性があるため、まず実ファイルを確認する。

```bash
sudo find /opt/shibboleth-idp/views -iname '*webauthn*' -print
```

例:

```text
/opt/shibboleth-idp/views/webauthn
```

`/opt/shibboleth-idp/views/*.vm` や `/opt/shibboleth-idp/views/webauthn/*` を編集した場合は、GraphicalMatrixの外部HTMLとは異なり、Jetty再起動を推奨する。

```bash
sudo systemctl restart jetty-idp.service
```

通常、Velocityテンプレートだけを編集した場合はWAR再構築までは不要である。ただし、Plugin JAR、Servlet、IdP認証フロー、`web.xml`、依存ライブラリを変更した場合は、WAR再構築とJetty再起動を行う。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

## WebAuthnへMFA方式を変更するとき、登録の途中で失敗したらどうなるか

自己管理画面でWebAuthnを選択しても、2FAS-KWはその時点でMFA方式を変更しない。
現在のMFA方式を保持したまま、Shibboleth WebAuthn Pluginの公式登録flowを開始する。

公式Pluginがcredentialの保存に成功し、登録成功hookで同一利用者の一回限り要求を
確認できた場合だけ、2FAS-KWのMFA方式を`WebAuthn`へ切り替える。次の場合は元の
MFA方式が維持される。

- 利用者が登録画面を閉じた。
- authenticator登録が失敗した。
- 一回限りの登録要求が期限切れになった。
- 登録中に管理者または別の操作がMFA方式を変更した。

この連携にはShibboleth WebAuthn Plugin 1.3.0以上が必要である。管理CLIの
`set-method USER WebAuthn`はcredentialの存在を検査しない強制操作のため、通常の利用者登録には
使用しない。動作確認では、監査ログの`WEBAUTHN_REGISTER_START result=OK`と
`WEBAUTHN_REGISTER_ACTIVATE result=OK`を確認する。

## Jettyの再起動が必要になるのはどのような場合か

変更対象がJavaクラス、Servlet登録、IdP認証フロー、JVM設定に関係する場合は、原則としてJettyを再起動する。

### Jetty再起動が必要な変更

- Plugin JARの追加、更新、削除
- `edit-webapp/WEB-INF/lib` 配下の依存JAR変更
- `web.xml` のServlet、Filter、URLマッピング変更
- TOTP、WebAuthnなどIdPプラグインの追加または更新
- Java、Jetty、Shibboleth IdPの更新
- JVMオプション、環境変数、systemd unitの変更
- JVMが読み込むtruststoreまたはkeystoreの変更

Plugin JARや `web.xml` を変更した場合は、通常、先にIdPのWARを再構築してからJettyを再起動する。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

### Jetty再起動を推奨する変更

- `mfa-authn-config.xml` などSpringまたはIdP認証フロー設定の変更
- MFA方式のBeanや認証フロー定義の変更
- IdPモジュールの有効化または無効化
- JettyまたはIdPで使用するTLS証明書や秘密鍵の変更
- 設定キャッシュやDB接続プールを確実に初期化したい場合

### 通常はJetty再起動が不要な変更

- `graphicalmatrix.properties`
- `db.properties`
- `api.properties`
- `mfa-policy.properties`
- `views/*.html`
- `assets/graphicalmatrix.css`
- GraphicalMatrix画像ファイル
- DB内のユーザー情報

これらは基本的に保存後の次回リクエストまたは次回コマンド実行時に再読込される。
`db.properties` の接続先や接続プール設定を変更した場合は、設定変更を検出した時点で既存プールを閉じ、新しい設定で接続プールを作成する。

設定ファイルを変更した後は、ユーザーが認証を開始する前に設定検査を実行する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

判断基準は次のとおり。

```text
Javaクラス、Servlet登録、IdP認証フロー、JVM設定を変更:
  build.shおよびJetty再起動を検討する

外部HTML、CSS、画像、GraphicalMatrixプロパティ、DBデータを変更:
  通常はJetty再起動不要
```

## バージョンアップで上書きインストールできるか

既存設定を維持した上書き更新は可能。
旧バージョンJARの削除、設定差分の確認、WAR再構築、Jetty再起動が必要。

詳細な更新、動作試験、ロールバック手順は
[UPGRADE.md](./UPGRADE.md) を参照する。

## 設定読込エラーはどこに記録されるか

認証画面で設定読込エラーが発生した場合、ユーザーにはIdPの一般エラー画面が表示される可能性がある。
例外の詳細は、主にJettyのjournalまたはIdPプロセスログで確認する。

```bash
sudo journalctl -u jetty-idp.service -n 200 --no-pager
```

```bash
sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log
```

直近10分の設定関連エラーを検索する例:

```bash
sudo journalctl -u jetty-idp.service --since "10 minutes ago" \
  | grep -iE 'GraphicalMatrix|IllegalArgumentException|ServletException'
```

設定読込はGraphicalMatrix監査ログの記録処理より前に失敗する場合があるため、次の監査ログには記録されない可能性がある。

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

## ユーザーアクセス前に設定エラーを確認する方法

次のコマンドで、DBへ接続せずに `graphicalmatrix.properties` と参照先ファイルを検査できる。

```bash
/opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

主な検査対象:

- Runtimeと同じJavaローダーによる数値、画像数、選択数、エイリアス
- `graphicalmatrix.place` 配下の有効画像ファイル
- CSSとHTMLテンプレート
- sequence保存方式と必要なpepper、keyword、AES key
- TOTP seed保存方式

正常例:

```text
summary: package_failures=0 package_warnings=0 idp_failures=0 idp_warnings=0 config_failures=0 config_warnings=0 strict=0
result: OK
```

設定不正例:

```text
FAIL: [config] runtime configuration invalid: IllegalArgumentException: GraphicalMatrix graphical count must match columns * rows. graphicals=25, cells=5

summary: package_failures=0 package_warnings=0 idp_failures=0 idp_warnings=0 config_failures=1 config_warnings=0 strict=0
result: CONFIG_CHECK_FAILED
```

TOTPを使用しない環境では、TOTP保存方式に関するWARNを許容できる場合がある。
本番設定を警告なしで確認する場合は `--strict` を付ける。

```bash
/opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only \
  --strict
```

この検査はDB内の既存 `sequence` と `graphicalmatrix.choice` の整合性までは確認しない。

## 他システムからTOTPまたはWebAuthnを移行できるか

TOTPは、移行元から利用者ごとの**元のTOTP seed**を安全に取得でき、2FAS-KW側のTOTP認証設定と
互換性がある場合に限り、個別移行できる。単に利用者が現在表示できるワンタイムコードやQR画像だけでは、
安全かつ確実な一括移行の入力にはならない。

現在の`graphicalmatrix-db.sh`には、1ユーザーの大文字Base32 seedを登録する機能がある。一方、
他システムのTOTP seedを含むCSVを一括インポートする機能は提供していない。seedは認証秘密情報であるため、
端末履歴、通常のCSV、通常ログに残さない専用の移行手順を設計する。

seedを登録しただけで直ちに本番移行完了とはしない。利用者の既存Authenticatorで生成されたコードを使い、
2FAS-KWのTOTP認証・有効化フローを1ユーザーずつ検証してから展開する。移行元とTOTPの方式
（seed、ハッシュ方式、桁数、時間刻み）が互換でなければ、利用者に2FAS-KW側で再登録してもらう。

WebAuthn credentialの他システムからの移行は、原則としてできない。credentialは認証先の
**RP ID**に暗号学的に結び付くため、別のドメインまたは別のRelying Partyへコピーしても利用できない。
2FAS-KWの管理CLIもWebAuthnについては一覧・削除だけを提供し、import機能は提供していない。

同一RP ID、同一のShibboleth WebAuthn Plugin、同一のStorageService保存形式を維持したまま保存先だけを
移す場合は、別途の保存先移行として検討できる。しかし、credential ID、公開鍵、署名カウンタ、
StorageServiceのcontextを壊さず扱う必要があり、直接DBへ書き込む運用はサポートしない。
通常は、新しい2FAS-KW環境で利用者にWebAuthn credentialを再登録してもらう。

## SPごとにLDAP属性で利用可否を制御するにはどうすればよいか

v1.3.0以降の`graphicalmatrix-sp.sh access`を使用する。SP metadata管理、属性release、MFA要否とは
別の機能であり、IdPが解決した未フィルタ属性をSAML Response発行前に評価する。認可判定のたびに
LDAPを追加検索する処理ではない。

> [!IMPORTANT]
> LDAPに対象属性が存在するだけでは判定できない。Attribute Resolverが使用するLDAP bindユーザーにも、
> 対象属性の`read`権限が必要である。権限不足やResolver未設定の場合、`access test`は
> `reason=ATTRIBUTE_MISSING`として対象SPをfail closedで拒否する。属性値を設定した後は、
> 検証用利用者と対象SPのentityIDを指定して、IdPが実際に属性を解決できることを確認する。

```bash
sudo env IDP_BASE_URL='http://127.0.0.1:8080/idp' \
  /opt/shibboleth-idp/bin/aacli.sh \
  --principal USER_ID \
  --requester 'https://sp.example.org/shibboleth' \
  --unfiltered
```

出力に対象属性がない場合は、Attribute Resolverの定義、LDAP bindユーザーの権限、LDAP側の
アクセス制御を確認する。389 Directory ServerではACI、OpenLDAPではACLなど、LDAP製品に応じた
読取り権限の設定が必要である。`--unfiltered`はResolverの確認用であり、通常のSAML属性releaseを
確認する操作ではない。

LDAPには存在するがAttribute Resolverに未登録の属性は、v1.3.1以降の
`attributes resolver add`で追加できる。次は`businessCategory`を追加する例である。

```bash
# 1. LDAPに対象ユーザーの属性が存在することを確認する。
# 接続先、bind DN、base DN、検索filterは実環境へ置き換える。
sudo ldapsearch -LLL -x \
  -H ldap://127.0.0.1:389 \
  -D 'cn=Directory Manager' -W \
  -b 'ou=People,dc=example,dc=test' \
  '(uid=test01)' businessCategory

# 2. SP管理CLIによる設定変更を有効にする。
# /opt/shibboleth-idp/conf/graphicalmatrix/sp-management.propertiesで次を設定する。
# graphicalmatrix.sp.management.enabled = true

# 3. LDAP DataConnectorを確認し、存在しなければ追加する予定内容を表示する。
# 既存のLDAPDirectory DataConnectorが1つあればNO_CHANGEになり、そのIDが表示される。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver init

# 4. DataConnectorが存在しない場合だけ、dry-runに表示されたコマンドで追加する。
# 既定の検索属性はuid。利用者検索に別のLDAP属性を使う場合は--search-attributeを指定する。
# data_connectorがgraphicalmatrixLdapの場合
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver init \
  --data-connector graphicalmatrixLdap \
  --apply --confirm graphicalmatrixLdap

# 5. Attribute Resolverへ属性を追加する予定内容を確認する（dry-run）。
# LDAPDirectory DataConnectorが1つだけなら自動選択される。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver add businessCategory

# 6. dry-runで表示されたDataConnectorを指定して実際に追加する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes resolver add businessCategory \
  --data-connector graphicalmatrixLdap \
  --apply --confirm businessCategory

# 7. Attribute Resolver変更をIdPへ反映する。
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service

# 8. 対象SPと利用者で属性が実際に解決されることを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes list
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes show \
  businessCategory

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover \
  --sp 2faskwlocaltest \
  --user test01
```

`resolver init`が新設する`graphicalmatrixLdap`は、`conf/ldap.properties`と
`credentials/secrets.properties`の標準的な`idp.attribute.resolver.LDAP.*`設定を参照する。
既定の利用者検索filterは`(uid=$resolutionContext.principal)`である。ログインIDに対応するLDAP属性が
`uid`以外なら、init時に`--search-attribute LDAP_ATTRIBUTE`を指定する。

LDAP DataConnectorが複数ある場合、CLIは自動選択せず停止する。エラーに表示された候補から、対象LDAPの
DataConnector IDを`--data-connector ID`で指定する。IdP属性IDとLDAP属性名が異なる場合は、
`--source-attribute LDAP_ATTRIBUTE`も指定する。CLIは既存の同名`AttributeDefinition`を
上書きしない。既存定義がある場合は、その定義を管理者が確認して修正する。

`resolver add`は属性をLDAPから解決可能にするだけで、SPへの送信やSP別アクセス許可を自動承認しない。
上のruntime確認で対象属性が`runtime-observed`になった後、用途に応じて`attributes approve`と
`access set`を実行する。

初回はContextCheck連携を明示的に初期化する。既存ContextCheckがある場合は自動上書きされず、
`CONTEXT_CHECK_CONFLICT`で停止する。

初期化前に`sp-management.properties`の`graphicalmatrix.sp.runtimeGroup`をIdP実行アカウントの
primary groupへ設定する。標準構成は`jetty`である。`access init --apply`は、runtime groupへ
access policy、attribute catalog、SP管理台帳の読み取りだけを許可する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init --apply
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

`businessCategory=AA`の利用者だけを`2faskwlocaltest`へ許可する例:

```bash
# 1. IdPで利用可能な属性IDの候補を検出する。
# 属性値は表示・保存しない。businessCategoryが候補にあることを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover

# 2. businessCategoryをIdP内部のSPアクセス制御に使うことを実際に承認する。
# --usage access はSPへの属性送信ではなく、IdP内の認可判定にだけ利用する指定である。
# --classification internal は内部利用の属性として分類する。
# --confirm には承認する属性IDを完全一致で指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes approve \
  businessCategory --usage access --classification internal \
  --purpose 'IdP-side SP authorization' \
  --apply --confirm businessCategory

# 3. 対象SPのaccess policyを設定する事前確認（dry-run）。
# 2faskwlocaltest はSPのentityIDではなく、SP管理CLIに登録した管理名である。
# businessCategory=AA を満たす利用者だけを許可する予定内容を表示し、設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set \
  2faskwlocaltest --allow 'businessCategory=AA'

# 4. access policyを対象SPへ実際に適用する。
# --confirm には対象SPの実際のentityIDを完全一致で指定する。
# https://sp.example.org/shibboleth は例のため、対象SPのentityIDへ置き換える。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set \
  2faskwlocaltest --allow 'businessCategory=AA' \
  --apply --confirm 'https://sp.example.org/shibboleth'
```

`businessCategory=AA` **または** `businessCategory=BB`の利用者だけを
`2faskwlocaltest`へ許可する場合は、同じ属性IDに`--allow`を繰り返して指定する。

```bash
# 5. AAまたはBBを持つ利用者を許可するaccess policyの事前確認（dry-run）。
# 同じ属性IDに指定した複数の値はOR条件として評価される。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set \
  2faskwlocaltest \
  --allow 'businessCategory=AA' \
  --allow 'businessCategory=BB'

# 6. 確認したaccess policyを対象SPへ実際に適用する。
# --confirmには対象SPの実際のentityIDを完全一致で指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access set \
  2faskwlocaltest \
  --allow 'businessCategory=AA' \
  --allow 'businessCategory=BB' \
  --apply --confirm 'https://sp.example.org/shibboleth'
```

異なる属性はAND、同一属性へ繰り返した`--allow`値はORである。denyはallowより先に評価する。
期待属性がない場合や値が一致しない場合、policy設定済みSPだけをfail closedで拒否する。
policyを設定していないSPには影響しない。

適用後は、許可される利用者と拒否される利用者をそれぞれ指定して判定を確認する。

```bash
# 5. businessCategory=AAを持つ利用者で、許可（decision=ALLOW）を確認する。
# 属性値そのものは表示せず、判定結果と確認した属性IDだけを出力する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access test \
  2faskwlocaltest --user user-with-aa

# 6. businessCategory=BBを持つ利用者で、許可（decision=ALLOW）を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access test \
  2faskwlocaltest --user user-with-bb

# 7. businessCategory=AAまたはBBを持たない利用者で、拒否（decision=DENY）を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access test \
  2faskwlocaltest --user user-without-aa
```

最後に対象SPの保護URLから実際にSSOを行い、許可利用者はSPへ到達し、非許可利用者は
IdPで拒否されることを確認する。`access set --apply`後の判定は設定ファイルを定期reloadするため、
この操作だけを理由に`build.sh`やJetty再起動を行う必要はない。

設定したSPアクセス制御を一時的に無効化する場合は、最初にdry-runで変更内容を確認してから
適用する。`2faskwlocaltest`はentityIDではなく、`graphicalmatrix-sp.sh list`の`NAME`列に表示される
SP管理名へ置き換える。

```bash
# 対象SPの管理名とentityIDを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list

# access policyを無効化する予定内容を確認する。設定は変更しない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access disable \
  2faskwlocaltest

# 確認したaccess policyの無効化を実際に適用する。
# --confirmには対象SPの実際のentityIDを完全一致で指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access disable \
  2faskwlocaltest \
  --apply --confirm 'https://sp.example.org/shibboleth'

# enabled=falseとrevisionの更新を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access show \
  2faskwlocaltest
```

`access disable`はSP登録、MFAポリシー、access policyの条件自体を削除しない。対象SPのLDAP属性による
アクセス制限だけを無効にし、属性値に関係なく認証を継続させる。設定を再度有効にする場合は、同じ
SP管理名とentityIDを指定して次を実行する。

```bash
# 無効化したaccess policyを再度有効にする予定内容を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access enable \
  2faskwlocaltest

# 確認した再有効化を実際に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access enable \
  2faskwlocaltest \
  --apply --confirm 'https://sp.example.org/shibboleth'
```

無効化・再有効化ではrevisionが更新される。これらの変更も設定ファイルの定期reloadで反映されるため、
通常は`build.sh`やJetty再起動を必要としない。

## LDAP属性の候補とSPへ送信できる属性を確認するにはどうすればよいか

`attributes discover`はresolver、Attribute Registry、既存filter、profile、access policyから
属性IDだけを検出する。実属性値は表示・保存しない。

属性profileの作成・更新時と`set-attributes`時には、全属性が現在`release-approved`かつ
`mapped`であることをCLIが再検証する。`attributes discover --sp SP_NAME --user USER`を使い、
検証用利用者で`runtime-observed`かつ`mapped`であることを確認してからprofileを作成する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover \
  --sp 2faskwlocaltest --user test01
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes list
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes show uid
```

SPへ送るprofileには`release-approved`かつSAML mapping済みの属性だけを登録できる。
`internal-only`はIdP内部のaccess policyには使えるが、SPへreleaseできない。

```bash
# 1. attribute profile作成の事前確認（dry-run）。
# uid-mail-release は任意のprofile管理名であり、まだ設定は変更しない。
# --attributes は、このprofileでSPへ送信を許可する属性IDを指定する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile create \
  uid-mail-release --attributes uid,mail \
  --description 'Account correlation and mail notification'

# 2. profileを実際に作成する。
# --confirm には、作成するprofile管理名を完全一致で指定する。
# この時点では、どのSPにも属性は送信されない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile create \
  uid-mail-release --attributes uid,mail \
  --description 'Account correlation and mail notification' \
  --apply --confirm uid-mail-release

# 3. 対象SPへprofileを割り当てる事前確認（dry-run）。
# 2faskwlocaltest はSPのentityIDではなく、SP管理CLIに登録した管理名である。
# uid-mail-release は手順1・2で作成したprofile管理名である。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-attributes 2faskwlocaltest uid-mail-release

# 4. 対象SPへprofileを実際に適用する。
# この操作で、対象SP向けmanaged attribute filterにuidとmailの送信設定が反映される。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-attributes 2faskwlocaltest uid-mail-release --apply

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile show uid-mail-release
```

`discover`やprofile作成のdry-runだけではattribute filterを変更しない。最後の`set-attributes --apply`
を対象SPへ実行した時点で、そのSP向けmanaged filterへ反映される。

### 既存profileへeduPersonPrincipalNameを追加する場合

既に`uid-mail-release`を`2faskwlocaltest`へ割り当てており、そこへ
`eduPersonPrincipalName`を追加する例である。最初に`attributes discover`の
`GOVERNANCE`が`release-approved`、`SAML MAPPING`が`mapped`であることを確認する。
すでに`release-approved`なら承認操作は不要である。`candidate`の場合だけ、明示承認してから
profileを更新する。

```bash
# 1. 対象利用者で、属性が解決され、SAML mapping済みであることを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover \
  --sp 2faskwlocaltest --user test01

# 2. 表示がcandidateの場合だけ実行する。release-approvedの場合は不要。
# --usage release は、この属性をSPへ送信可能な属性として承認する指定である。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes approve \
  eduPersonPrincipalName \
  --usage release \
  --classification personal \
  --purpose 'Federated account identifier' \
  --apply \
  --confirm eduPersonPrincipalName

# 3. 既存の管理対象profileを更新する事前確認（dry-run）。
# --attributes は置換指定のため、残したいuidとmailも含めて全属性を列挙する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile update \
  uid-mail-release \
  --attributes uid,mail,eduPersonPrincipalName \
  --description 'Account identifiers and federated principal name'

# 4. profileを実際に更新する。
# uid-mail-releaseを割り当て済みのSPのmanaged AttributeFilterPolicyも自動更新・reloadされる。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile update \
  uid-mail-release \
  --attributes uid,mail,eduPersonPrincipalName \
  --description 'Account identifiers and federated principal name' \
  --apply \
  --confirm uid-mail-release

# 5. profile内容と対象SPへの割当を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes profile show uid-mail-release
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify 2faskwlocaltest
```

`attributes profile update`は**管理対象profile**だけを更新できる。組み込みの`uid`、`uid-mail`、
`none`は更新できないため、それらを利用中の場合は`uid-mail-eppn-release`などの新しい管理対象profileを
作成し、`set-attributes SP_NAME uid-mail-eppn-release --apply`で対象SPへ割り当てる。

適用後は、次の順で確認する。

```bash
# 1. profileに登録した属性IDを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes profile show uid-mail-release

# 2. SP管理台帳の割当状態とmetadata整合性を確認する。
# attribute_profile=uid-mail-release、status=ACTIVE、result=OK を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  verify 2faskwlocaltest
```

`set-attributes --apply`が`result=APPLY_OK`で完了した場合、CLIは対象SPのmanaged
AttributeFilterPolicyを書き換え、`shibboleth.AttributeFilterService`を自動reloadするため、
この操作だけを理由に`build.sh`やJetty再起動を行う必要はない。

最後に、対象SPの保護URLから実際にSSOを実施し、SP側のセッション情報、アプリケーションの属性表示、
またはSPのSAMLデバッグログで`uid`と`mail`が受信されていることを確認する。CLIの`verify`は
管理台帳とmetadataの整合性を確認するものであり、実際のSAML Responseに含まれる属性値までは確認しない。

実際の属性送信は、検証用利用者でSPの保護URLからSSOを開始した後、**SP側**で確認する。IdPの
`attributes discover`や`attributes profile show`は、IdPが解決可能な属性や送信設定を確認するための
commandであり、送信済みSAML Responseを表示するものではない。

IdP内部の属性IDとSPが受信するSAML属性名は別である。例えば標準的なAttribute Registryでは、
`uid`は`urn:oid:0.9.2342.19200300.100.1.1`、`mail`は
`urn:oid:0.9.2342.19200300.100.1.3`、`eduPersonPrincipalName`は
`urn:oid:1.3.6.1.4.1.5923.1.1.1.6`として送られる。SP側の期待値は、IdP内部IDではなく
実際のSAML属性名に合わせる。

SP側の確認方法は製品に依存するが、次の方針とする。

- Shibboleth SP: アプリケーションが参照する`attribute-map.xml`の属性名を、保護された検証用endpointで
  確認する。`REMOTE_USER`や属性ヘッダの有無だけを表示し、値を画面や通常ログへ出力しない。
- SimpleSAMLphp: SSO後に`$auth->getAttributes()`で取得した配列から、実際のSAML属性名（OIDまたは
  SP側で設定した別名）が存在することだけを検証用画面へ表示する。IdP内部IDの`uid`だけを配列keyとして
  期待すると、正常に送信されていても`missing`と誤判定する。
- SPのアプリケーション: `uid`と`mail`を使う機能を検証し、属性がない場合に意図どおり拒否またはエラーに
  なることも確認する。

SAMLトレーサーやSPのデバッグログでSAML Responseを直接確認する方法もあるが、属性値やセッション情報を
露出しやすいため、本番環境の通常運用には使用しない。障害調査で一時的に有効化する場合も、対象利用者を
限定し、出力を速やかに削除する。

SimpleSAMLphpで存在確認だけを行う検証用ページは、次のようにIdP内部IDとSAML属性名の候補を対応付ける。
通常運用では属性値を表示しない。

```php
$expected = [
    'uid' => ['uid', 'urn:oid:0.9.2342.19200300.100.1.1'],
    'mail' => ['mail', 'urn:oid:0.9.2342.19200300.100.1.3'],
    'eduPersonPrincipalName' => [
        'eduPersonPrincipalName', 'urn:oid:1.3.6.1.4.1.5923.1.1.1.6',
    ],
];

foreach ($expected as $label => $aliases) {
    $present = array_filter($aliases, static fn ($key) => array_key_exists($key, $attributes));
    printf("%s: %s\n", $label, $present ? 'present' : 'missing');
}
```

属性値そのものを表示する試験は、テスト利用者とアクセス制限された一時endpointだけで実施する。試験後は
endpointを削除し、Webサーバーのaccess log・error logへ値が残っていないことを確認する。

## 関連文書

- [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)
- [INSTALL.md](./INSTALL.md)
- [SECURITY.md](./SECURITY.md)
