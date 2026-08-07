# 新しいSAML SPをShibboleth IdPへ追加する（CLI管理）

## 1. 対象と方針

本書は、v1.3.0の2FAS-KWに同梱される
`/opt/shibboleth-idp/bin/graphicalmatrix-sp.sh`を使い、新しいSAML Service Provider（SP）を
IdPへ追加する手順である。metadata、SP向け属性release、SP単位MFA方針をCLIで一貫して管理する。

更新操作は既定でdry-runである。表示されたmetadata、entityID、ACS、証明書fingerprintを確認し、
`--apply`を付けた操作だけがIdP設定を変更する。HTTP APIや管理Web UIは提供しない。

CLIを使わずに`metadata-providers.xml`、`attribute-filter.xml`、`mfa-policy.properties`を直接編集する
従来手順は、[INSTALL_NEW_Manual_SP.md](./INSTALL_NEW_Manual_SP.md)を参照する。CLI管理するSPに対して、
それらのファイルを直接編集してはならない。

## 2. 事前に決める項目

| 項目 | 内容 |
| --- | --- |
| SP FQDN | HTTPSで公開する正式なFQDN。IdPから名前解決できること。 |
| SP entityID | metadataの`EntityDescriptor/@entityID`と完全一致する長期識別子。 |
| metadata URLまたはmetadataファイル | SP管理者から独立した経路で内容を照合できるもの。 |
| ACS URL | metadataに記載される、SAML Responseを返すSP側endpoint。 |
| 属性profile | SPが本当に必要とする属性だけを選ぶ。既定は`none`、`uid`、`uid-mail`。 |
| MFA profile | `inherit`、`force`、`bypass`、`required`、`sp-cidr-bypass`から選ぶ。 |

SPのTLS証明書とSAML署名用証明書は別物である。TLSだけを信頼してSAML Responseの署名検証を
省略してはならない。

### 2.1 CLI入力値の制約

| 入力 | 制約 |
| --- | --- |
| `--name` | 1〜63文字。`[a-z0-9][a-z0-9-]{0,62}`。英小文字、数字、ハイフンだけを使用する。 |
| `--entity-id` | metadata内の`EntityDescriptor/@entityID`と完全一致する絶対URI。 |
| `--metadata-url` | HTTPSだけ。`allowedHosts`へ登録済みのFQDNだけを使用できる。 |
| `--metadata-file` | 通常ファイルだけ。シンボリックリンクは不可。最大1 MiB。 |
| metadata内のACS | HTTPSだけ。`allowedAcsHosts`で許可したFQDNだけを使用できる。 |
| `--attribute-profile`、`--mfa` | CLIが定義する許可済みの値だけを指定できる。 |

## 3. 初回だけ行うCLI管理の初期化

初回だけ、`/opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties`を編集する。
metadata URLを利用する場合はmetadata配布元を`allowedHosts`へ、metadataに記載されたACSのFQDNを
`allowedAcsHosts`へ登録する。

```properties
graphicalmatrix.sp.management.enabled = true
graphicalmatrix.sp.metadata.allowedHosts = new-sp.example.org
graphicalmatrix.sp.metadata.allowedAcsHosts = new-sp.example.org
# Jetty実行アカウントのprimary group。標準構成はjetty。
graphicalmatrix.sp.runtimeGroup = jetty
```

`graphicalmatrix.sp.runtimeGroup`には、IdPを実行するサービスアカウントのprimary groupを指定する。
標準的な`jetty-idp.service`では`jetty`である。異なる場合は、次で`User`と`Group`を確認して
実際のgroup名へ置き換える。

```bash
sudo systemctl show jetty-idp.service -p User -p Group
```

`access init --apply`は、`conf/graphicalmatrix`を`0750`、access policy・attribute catalog・
SP管理台帳を`root:<runtimeGroup>`かつ`0640`へ設定する。Jettyには読み取りだけを許可し、
管理CLIを実行する管理者だけが書き込む。手動でJSONを作成・復元した場合も、同じ所有者とmodeへ戻す。

IP literal、HTTP、redirect、URL credential、query、fragmentは受け入れない。
IdPの管理コマンドが使用する`http://localhost/idp`で接続できない構成だけ、外部公開URLではなく
loopback listenerを指定する。

```properties
graphicalmatrix.sp.reload.baseUrl = http://127.0.0.1:8080/idp
```

次に、管理用metadata providerとattribute filter blockを初期化する。

```bash
# 現在の状態と、次に必要な操作を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next

# 初期化内容を確認してから反映する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init --apply

# init --apply後は、Jettyへ新しいmetadata providerを読み込ませる。
sudo systemctl restart jetty-idp.service

# Jettyが起動完了するまで待機する。ポートは環境に合わせて変更する。
until curl --noproxy '*' -fsSI --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/idp/status >/dev/null 2>&1; do
  echo 'Waiting for Jetty...'
  sleep 2
done

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
```

`init`は一度だけ実行する。2件目以降のSP追加で再実行しない。`add --apply`が成功した場合、CLIが
metadataとattribute filterをreloadするため、通常は再起動不要である。

## 4. 新しいSPを追加する

### 4.1 dry-runでmetadataを検査する

まず、SP metadataを取得して検査する予定内容を表示する。次の例の`new-sp`はCLI管理名であり、
entityIDとは別の識別子である。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp \
  --entity-id https://new-sp.example.org/shibboleth \
  --metadata-url https://new-sp.example.org/Shibboleth.sso/Metadata \
  --attribute-profile none \
  --mfa force
```

出力された`metadata_sha256`、entityID、ACS、証明書fingerprintを、SP管理者が提供した情報など
独立した経路で取得した値と照合する。SHA-256を確認できない場合は適用しない。

### 4.2 検査済みmetadataを適用する

dry-runで確認したSHA-256値を`--approve-sha256`へ指定して適用する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp \
  --entity-id https://new-sp.example.org/shibboleth \
  --metadata-url https://new-sp.example.org/Shibboleth.sso/Metadata \
  --attribute-profile none \
  --mfa force \
  --approve-sha256 METADATA_SHA256 \
  --apply
```

`result=APPLY_OK`を確認する。CLIはmanaged metadata、SP向けattribute filter、SP単位MFA方針を
更新する。metadataの配置先を手作業で編集する必要はない。

### 4.3 追加結果を確認する

```bash
# metadataのentityID・digest、属性profile、MFA profile、状態を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify new-sp

# 管理対象SP一覧と次の候補操作を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh list
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next new-sp
```

最後にIdP URLを直接開かず、対象SPの保護ページからSSOを開始する。Password認証、必要なMFA、
SP側のセッション作成、および必要最小限の属性だけがSPへ届くことを確認する。

## 5. 2つ目以降のSPを追加する

2つ目以降のSPを追加する場合、`init`は再実行しない。最初に許可リストへ新しいFQDNを追記する。
既存値を消さず、カンマ区切りで追加する。`graphicalmatrix-sp.sh add`はこのpropertiesを自動更新しない。

```properties
graphicalmatrix.sp.management.enabled = true
graphicalmatrix.sp.metadata.allowedHosts = new-sp.example.org,new-sp2.example.org
graphicalmatrix.sp.metadata.allowedAcsHosts = new-sp.example.org,new-sp2.example.org
```

metadata配布元とACSのFQDNが異なる場合、前者を`allowedHosts`、後者を`allowedAcsHosts`へ分けて
登録する。このproperties変更だけを反映する目的でJettyを再起動する必要はない。

```bash
# dry-runで2つ目のSP metadataを検査する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp2 \
  --entity-id https://new-sp2.example.org/shibboleth \
  --metadata-url https://new-sp2.example.org/Shibboleth.sso/Metadata \
  --attribute-profile uid \
  --mfa force

# 表示されたMETADATA_SHA256を確認してから適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp2 \
  --entity-id https://new-sp2.example.org/shibboleth \
  --metadata-url https://new-sp2.example.org/Shibboleth.sso/Metadata \
  --attribute-profile uid \
  --mfa force \
  --approve-sha256 METADATA_SHA256 \
  --apply

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify new-sp2
```

許可リストからFQDNを削除しても登録済みSPは自動削除されないが、そのFQDNからmetadataを再取得する
将来の`update`は拒否される。運用中SPに必要なFQDNは残す。

## 6. 属性releaseを変更する

既存のCLI管理SPへ属性profileを適用する場合は、まず対象利用者で属性が解決され、SAML mapping済みで
あることを確認する。IdP内部の属性IDと、SPが受け取るSAML属性名は同じとは限らない。

```bash
# 対象SPと検証用利用者で、uidとmailがruntime-observedかつmappedであることを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes discover --sp new-sp --user TEST_USER

# SPへ送る属性profileの内容を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  attributes profile show uid-mail

# profileを対象SPへ割り当てる予定内容を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-attributes new-sp uid-mail

# profileを実際に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-attributes new-sp uid-mail --apply

# 割当状態を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify new-sp
```

custom profileの作成、属性候補の検出、属性の承認は[FAQ](./FAQ.md#ldap属性の候補とspへ送信できる属性を確認するにはどうすればよいか)を参照する。
`set-attributes`は、profile内の全属性が現在`release-approved`かつ`mapped`でなければ拒否する。

## 7. SP単位MFA方針を変更する

SP追加時の`--mfa`または既存SPへの`set-mfa`で設定する。CLI管理SPについて
`forceSPs`、`bypassSPs`、`requiredSPs`、`bypassSpCidrs`を直接編集してはならない。

```bash
# 変更予定を確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa new-sp required

# 確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa new-sp required --apply
```

`inherit`、`force`、`bypass`、`required`、`sp-cidr-bypass`が選択できる。全SPに影響する既定方針、
評価順、全体送信元IP例外はCLI管理外であるため、[FAQ](./FAQ.md#spごと送信元ipごとにmfaの要否を変更するにはどうすればよいか)を参照する。

## 8. 任意: LDAP属性でSP利用可否を制御する

v1.3.0以降は、CLI管理SPに対し、LDAP由来の未フィルタ属性を使ってIdP内部で利用可否を判定できる。
属性をSPへ送るための設定とは別機能である。初回だけContextCheck連携を初期化する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh access init --apply
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

詳細な属性承認、policy設定、許可・拒否の試験手順は、
[FAQ](./FAQ.md#spごとにldap属性で利用可否を制御するにはどうすればよいか)を参照する。

## 9. 変更の取消しと既存SPの移行

CLIで作成したSPは、`history`で変更履歴を確認し、`rollback`でCLI管理下の過去revisionへ戻せる。
既存の手作業登録SPは`status`で検出し、対応する`FilesystemMetadataProvider`なら`adopt`で
CLI管理へ移行できる。手作業登録状態へ戻す場合は`restore-legacy`を使用する。

具体的な移行、取消し、metadata reloadエラーの対処は、
[UPGRADE.md](./UPGRADE.md#既存の手作業登録spをcli管理へ移行する場合)および
[v1.3.0統合リリースノート](./release-notes/v1.3.0-RELEASE-NOTES.md)および
[v1.3.0 SP別LDAP属性アクセス制御・属性カタログCLI設計](./release-notes/v1.3.0-SP-ACCESS-ATTRIBUTE-CATALOG-DESIGN.md)を参照する。

## 関連資料

- CLIを使わない手作業登録: [INSTALL_NEW_Manual_SP.md](./INSTALL_NEW_Manual_SP.md)
- 検証用SimpleSAMLphp SPの構築例: [INSTALL_SP.md](./INSTALL_SP.md)
- SP別・IP別のMFAポリシー: [FAQ.md](./FAQ.md)
- 2FAS-KW設定項目: [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)
