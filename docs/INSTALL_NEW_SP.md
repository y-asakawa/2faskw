# 新しいSAML SPをShibboleth IdPへ追加する

## 1. 目的

本書は、2FAS-KWを導入したShibboleth IdPへ、新しいSAML Service Provider（SP）を
追加するための運用手順である。通常のSP追加では2FAS-KWのソースコード変更は不要であり、
SP metadata、IdPの属性リリース、MFAポリシーを設定する。

本番SPはShibboleth SPを推奨する。SimpleSAMLphpは既存運用との互換または検証用途として
利用できるが、SP追加時にIdPが扱うSAML metadataとMFAポリシーの考え方は同じである。

## v1.2.7以降の推奨CLI手順

v1.2.7以降は、IdPサーバ上の`graphicalmatrix-sp.sh`でmetadata、属性リリース、MFA方針を
一括管理できる。HTTP APIや管理Web UIは提供せず、許可された管理者が`sudo`で実行する。
更新commandは既定でdry-runとなり、`--apply`を付けるまでIdP設定を変更しない。

最初に`/opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties`を編集する。

```properties
graphicalmatrix.sp.management.enabled = true
graphicalmatrix.sp.metadata.allowedHosts = new-sp.example.org
graphicalmatrix.sp.metadata.allowedAcsHosts = new-sp.example.org
```

metadata URLを使う場合は`allowedHosts`、metadataファイルを使う場合もACSのFQDNを
`allowedAcsHosts`へ登録する。IP literal、HTTP、redirect、URL credential、query、fragmentは
受け入れない。

Shibbolethの管理コマンドが使用する`http://localhost/idp`でIdPへ接続できない構成だけ、IdPの
loopback listenerを指定する。管理flowを外部へ公開するURLは指定しない。

```properties
graphicalmatrix.sp.reload.baseUrl = http://127.0.0.1:8080/idp
```

初回だけ管理用metadata providerと属性filter blockを初期化する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh init --apply
sudo systemctl restart jetty-idp.service
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status
```

`init --apply`はファイルを更新するだけであり、`status`はファイル上の設定を読む。このため初期化後は
Jettyを再起動し、起動完了を確認する。Jettyが8080で待ち受ける構成の例は次のとおりである。

```bash
until curl --noproxy '*' -fsSI --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/idp/status >/dev/null 2>&1; do
  echo 'Waiting for Jetty...'
  sleep 2
done
```

通常はここまでで`add`または`adopt`へ進める。`add --apply`と`adopt --apply`は、成功時にCLI自身が
metadata reloadを実行する。手動の`reload-metadata.sh`は、CLIがreloadエラーを表示した場合、または
admin reload endpointと`graphicalmatrix.sp.reload.baseUrl`の接続先を事前確認する場合だけ使用する。
エラー時の対処は[FAQ](./FAQ.md#手作業で登録済みのspをsp管理cliの対象へ移行するにはどうすればよいか)を参照する。

新しいSPは、まずdry-runで検証する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp \
  --entity-id https://new-sp.example.org/shibboleth \
  --metadata-url https://new-sp.example.org/Shibboleth.sso/Metadata \
  --attribute-profile uid \
  --mfa force
```

表示された`metadata_sha256`、ACS、証明書fingerprintをSP管理者から独立した経路で取得した
値と照合する。一致した場合だけ、同じ入力へdigestと`--apply`を追加する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp \
  --entity-id https://new-sp.example.org/shibboleth \
  --metadata-url https://new-sp.example.org/Shibboleth.sso/Metadata \
  --attribute-profile uid \
  --mfa force \
  --approve-sha256 METADATA_SHA256 \
  --apply

sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh verify new-sp
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next new-sp
```

### 2つ目以降のSPを追加する

2つ目のSPを追加する場合、`init`は再実行しない。最初に
`/opt/shibboleth-idp/conf/graphicalmatrix/sp-management.properties`の許可リストへ、既存値を
残したまま新しいFQDNをカンマ区切りで追加する。`graphicalmatrix-sp.sh add`は、このpropertiesを
自動更新しない。

```properties
graphicalmatrix.sp.management.enabled = true
graphicalmatrix.sp.metadata.allowedHosts = new-sp.example.org,new-sp2.example.org
graphicalmatrix.sp.metadata.allowedAcsHosts = new-sp.example.org,new-sp2.example.org
```

`allowedHosts`は`--metadata-url`の取得元、`allowedAcsHosts`はmetadataに含まれるACSのFQDNを
許可する。metadata配布元とACSが異なる場合は、それぞれ対応するFQDNへ分けて追加する。

設定保存後、2つ目のSPをdry-runする。propertiesはCLI起動時に読み込まれるため、この変更だけを
反映する目的でJettyを再起動する必要はない。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp2 \
  --entity-id https://new-sp2.example.org/shibboleth \
  --metadata-url https://new-sp2.example.org/Shibboleth.sso/Metadata \
  --attribute-profile uid \
  --mfa force
```

表示された`metadata_sha256`、entityID、ACS、証明書fingerprintを独立した経路で確認し、一致した
場合だけ適用する。

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
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh next new-sp2
```

既存のFQDNを許可リストから削除しても登録済みSPは自動削除されないが、そのFQDNからmetadataを
再取得する将来の`update`は拒否される。通常は、運用中のSPに必要なFQDNをすべて残す。

最後はIdP URLを直接開かず、SPの保護ページからSSOを開始して確認する。既存の手作業登録SPは
`status`で検出でき、対応している`FilesystemMetadataProvider`は`adopt`でCLI管理へ移行できる。
更新履歴は`history`、CLI変更の復元は`rollback`、手作業登録状態への復元は
`restore-legacy`を使用する。詳細は
[v1.2.7 SP管理CLI設計](./release-notes/v1.2.7-SP-MANAGEMENT-CLI-DESIGN.md)を参照する。

> **＜手動手順: CLIを使わない場合だけ＞**
>
> 以下の手順は、v1.2.6以前の環境、またはCLIが安全に取り込めない独自metadata構成のための手動手順である。
> v1.2.7以降に`graphicalmatrix-sp.sh`で管理するSPでは、metadata、属性release、SP単位MFA方針を
> この手順で直接編集しない。CLIの`add`、`set-attributes`、`set-mfa`を使用する。

## 2. 認証フロー

```text
利用者
  -> SPの保護ページ
  -> SPがSAML AuthnRequestを作成
  -> IdP SSO endpoint
  -> IdPがSP metadataでentityID、ACS、証明書を検証
  -> 2FAS-KWがSP entityIDと送信元IPからMFA要否を判定
  -> Password認証（LDAP）
  -> 必要なMFA（GraphicalMatrix / TOTP / WebAuthn）
  -> IdPが属性を取得し、SP向け属性フィルタを適用
  -> IdPが署名済みSAML ResponseをSPのACSへ返却
  -> SPがIdP署名を検証し、アプリケーションセッションを作成
```

MFAポリシーで使うSP識別子は、利用者向けURLやACS URLではなく、SP metadataの
`EntityDescriptor`にある`entityID`である。`https`/`http`、ポート番号、末尾スラッシュ、
パスを含めて完全一致する。

```xml
<md:EntityDescriptor entityID="https://new-sp.example.org/shibboleth">
```

## 3. 事前に決める項目

| 項目 | 内容 |
| --- | --- |
| SP FQDN | HTTPSで公開する正式なFQDN。IdPから名前解決できること。 |
| SP entityID | 長期的に変更しない識別子。metadata、属性フィルタ、MFAポリシーで同じ値を使う。 |
| ACS URL | IdPがSAML Responseを返すSP側endpoint。metadataに記載される。 |
| SP署名鍵・証明書 | AuthnRequest署名、metadata署名、または将来の鍵更新に使う。秘密鍵はSP外へ出さない。 |
| IdP metadata | IdP entityID、SSO/SLO endpoint、IdP署名証明書。SPが信頼する。 |
| 属性 | SPが本当に必要とする属性だけを決める。 |
| MFA要否 | 全利用者で必要か、SP単位または送信元IP条件で例外を設けるか。 |

SPのTLS証明書と、SAML署名用証明書は役割が異なる。TLSだけを信頼してSAML Responseの
署名検証を省略してはならない。

### 3.1 CLI入力値の制約

`graphicalmatrix-sp.sh`は、IdP設定を変更する前に入力値とmetadataを検証する。SP管理名は
SAMLのentityIDとは別のCLI用識別子であり、英小文字、数字、ハイフンだけを使う。

| 入力 | 制約 |
| --- | --- |
| `--name` | 1〜63文字。`[a-z0-9][a-z0-9-]{0,62}`。先頭を数字にできる。大文字、空白、`_`、`.`は不可。 |
| `--entity-id` | metadata内の`EntityDescriptor/@entityID`と完全一致する絶対URI。空白と制御文字は不可。 |
| `--metadata-url` | HTTPSだけ。`sp-management.properties`の`allowedHosts`へ登録済みのFQDNだけを指定できる。 |
| `--metadata-file` | 通常ファイルだけ。シンボリックリンクは不可。最大1 MiB。 |
| metadata内のACS | HTTPSだけ。`allowedAcsHosts`で許可したFQDNだけを使用できる。 |
| `--attribute-profile`、`--mfa` | CLIが定義する許可済みの値だけを指定できる。CIDRは`sp-cidr-bypass`の場合だけ指定する。 |

初回の`add`はdry-runとして実行し、表示されたentityID、ACS、証明書fingerprint、
`metadata_sha256`を確認する。`--apply`で反映するには、その`metadata_sha256`を
`--approve-sha256`へ完全一致で指定しなければならない。

## 4. ＜手動＞SP側の設定

SP側では、製品の公式手順に従ってSAML SPを設定する。少なくとも以下を完了する。

1. HTTPSを有効化し、利用者向けURLを正式FQDNへ統一する。
2. SP entityID、ACS URL、SP署名鍵・証明書を設定する。
3. IdP metadataを登録し、IdPのSAML Response署名を検証する。
4. AuthnRequest署名を有効化できるSPでは、有効化する。
5. SP metadataを取得できるendpointを用意する。

SimpleSAMLphpの例では、`authsources.php`にSP entityIDとIdP entityIDを設定し、
`saml20-idp-remote.php`にIdPのSSO/SLO endpointおよび署名証明書を登録する。

```php
'new-sp' => [
    'saml:SP',
    'entityID' => 'https://new-sp.example.org/shibboleth',
    'idp' => 'https://idp.example.org/idp/shibboleth',
    'privatekey' => 'new-sp.key',
    'certificate' => 'new-sp.crt',
    'sign.logout' => true,
],
```

SP側の`entityID`、ACS URL、署名証明書は、次節でIdPへ登録するmetadataと一致させる。

## 5. ＜手動＞IdPへSP metadataを登録する

### 5.1 metadataの取得と検査

IdPサーバでSP metadataを取得する。取得元はSP管理者が提示したHTTPS endpoint、または
署名付きの配布metadataに限定する。利用者入力や未検証のURLからmetadataを取り込まない。

```bash
curl -fsSLo /tmp/new-sp.xml \
  https://new-sp.example.org/Shibboleth.sso/Metadata

xmllint --noout /tmp/new-sp.xml

grep -E 'entityID|AssertionConsumerService|SingleLogoutService|X509Certificate' \
  /tmp/new-sp.xml
```

次を確認する。

- `entityID`が事前に決めた値と一致する。
- ACSがSPの正式なHTTPS FQDNだけを指す。
- metadata内の署名証明書がSP管理者から提示されたものと一致する。
- 不要なACS、テスト用FQDN、失効済み証明書が残っていない。

### 5.2 metadataファイルの配置

検査済みmetadataをIdPへ配置する。

```bash
sudo install -o root -g jetty -m 0640 /tmp/new-sp.xml \
  /opt/shibboleth-idp/metadata/new-sp.xml
```

SELinuxを使用する環境では、ファイルをコピーした後にsecurity contextも復元する。

```bash
sudo restorecon -v /opt/shibboleth-idp/metadata/new-sp.xml
```

`FilesystemMetadataProvider`は、登録されたmetadataファイルをJetty実行ユーザーが読めない場合、
fail-fastで初期化に失敗する。その場合、他のSPを含む`MetadataResolverService`全体が利用不能になる。
配置後は、実際の実行ユーザーで読み取りを確認する。

```bash
sudo -u jetty test -r /opt/shibboleth-idp/metadata/new-sp.xml && \
  echo 'OK: Jetty can read SP metadata'
```

この確認が失敗した場合は、次の順で復旧する。`jetty`はこの文書の標準的なJetty実行ユーザーであり、
実環境で異なる場合は実際のサービス実行ユーザーへ置き換える。

```bash
# 1. metadata-providers.xmlのmetadataFile属性から実ファイルを特定する。
sudo grep -n 'metadataFile=' /opt/shibboleth-idp/conf/metadata-providers.xml

# 2. IdPには読み取りだけを許可する。
sudo chown root:jetty /opt/shibboleth-idp/metadata/new-sp.xml
sudo chmod 0640 /opt/shibboleth-idp/metadata/new-sp.xml
sudo restorecon -v /opt/shibboleth-idp/metadata/new-sp.xml

# 3. Jetty実行ユーザーとして読み取れることを確認する。
sudo -u jetty test -r /opt/shibboleth-idp/metadata/new-sp.xml && \
  echo 'OK: Jetty can read SP metadata'

# 4. fail-fastで停止したresolverを復旧する。
sudo systemctl restart jetty-idp.service
curl --noproxy '*' -fsSI http://127.0.0.1:8080/idp/status
```

### 5.3 metadata providerの追加

`/opt/shibboleth-idp/conf/metadata-providers.xml`に、既存の`MetadataProvider`と同じ階層で
追加する。`id`はIdP内で重複しない名前にする。

```xml
<MetadataProvider id="NewSpMetadata"
                  xsi:type="FilesystemMetadataProvider"
                  metadataFile="%{idp.home}/metadata/new-sp.xml"/>
```

metadata provider設定を反映する。

```bash
sudo systemctl restart jetty-idp.service
sudo systemctl is-active jetty-idp.service
sudo journalctl -u jetty-idp.service -n 100 --no-pager
```

metadata providerの変更はWAR再構築を必要としない。起動失敗またはmetadata load errorがないことを
Journalと`/opt/shibboleth-idp/logs/idp-process.log`で確認する。

## 6. SP向けの属性リリースを設定する

v1.2.7以降、`graphicalmatrix-sp.sh add`で`--attribute-profile`を指定して登録する場合は、
属性リリースもCLIが自動管理する。通常のCLI登録では`attribute-filter.xml`を手編集しない。
CLIは2FAS-KW管理block内に、SP entityIDへ完全一致する最小権限のポリシーを生成する。

```bash
# 新規登録時にuidとmailだけをreleaseする予定を確認する（dry-run）。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp \
  --entity-id https://new-sp.example.org/shibboleth \
  --metadata-file /secure/incoming/new-sp-metadata.xml \
  --attribute-profile uid-mail \
  --mfa force

# 既存のCLI管理SPの属性profile変更を確認してから反映する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-attributes new-sp uid-mail
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-attributes new-sp uid-mail --apply
```

既定profileは`none`、`uid`、`uid-mail`である。許可済みcustom profileは
`sp-management.properties`へ定義できる。任意のXML断片、条件分岐、値変換など、profileで表せない
複雑な属性releaseだけは手作業で設定する。その場合は対象SPをCLI管理対象に含めないか、設定変更時に
管理blockとの重複がないことを確認する。

### ＜手動＞個別の属性releaseを直接設定する場合

CLIを使わない従来手順、または上記の複雑な個別ポリシーでは、次のように手作業で設定する。
SPへ送る属性は最小限にする。`/opt/shibboleth-idp/conf/attribute-filter.xml`へ、SP entityIDに
完全一致するポリシーを追加する。以下は`uid`と`mail`をSPへ送る例であり、属性IDは実際の
attribute-resolver設定に合わせて変更する。

```xml
<AttributeFilterPolicy id="releaseToNewSp">
    <PolicyRequirementRule xsi:type="Requester"
        value="https://new-sp.example.org/shibboleth"/>

    <AttributeRule attributeID="uid">
        <PermitValueRule xsi:type="ANY"/>
    </AttributeRule>

    <AttributeRule attributeID="mail">
        <PermitValueRule xsi:type="ANY"/>
    </AttributeRule>
</AttributeFilterPolicy>
```

この断片は既存の`AttributeFilterPolicyGroup`内へ追加する。既存ファイルが名前空間prefixを
使っている場合は、そのファイルの表記に合わせる。断片だけを別ファイルとして配置してはならない。

属性フィルタを変更した後は、構文確認とJetty再起動を行う。

```bash
sudo systemctl restart jetty-idp.service
```

管理者用属性、LDAP password、GraphicalMatrix sequence、TOTP seed、WebAuthn credentialを
SPへreleaseしてはならない。

## 7. 2FAS-KWのMFAポリシーを設定する

v1.2.7以降、CLI管理SPのSP単位MFA方針は、`add --mfa`または`set-mfa`で自動管理する。
CLIは対象SPのentityIDを`mfa-policy.properties`の管理対象propertyへ追加・更新・削除するため、
通常のCLI登録では`forceSPs`、`bypassSPs`、`requiredSPs`、`bypassSpCidrs`を手編集しない。

```bash
# SP追加時に送信元IPの例外より優先してMFAを要求する予定を確認する（dry-run）。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh add \
  --name new-sp \
  --entity-id https://new-sp.example.org/shibboleth \
  --metadata-file /secure/incoming/new-sp-metadata.xml \
  --attribute-profile uid \
  --mfa force

# 既存のCLI管理SPをrequiredSPs方式へ変更する予定を確認してから反映する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa new-sp required
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh \
  set-mfa new-sp required --apply
```

CLIで選べるSP単位MFA profileは`inherit`、`force`、`bypass`、`required`、
`sp-cidr-bypass`である。`disable`または`remove`では、対象SPのCLI管理MFA設定も自動で除去する。

### ＜手動＞全体MFAポリシーを設定する場合

全SPへ影響する既定方針、評価順、全体送信元IP例外はCLIの管理対象外であり、管理者が
次のファイルを手作業で設定する。

編集対象は次のファイルである。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
```

#### ＜手動＞7.1 全SPでMFAを必須にする場合

次の既定であれば、新SP向けの追記は不要である。

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.requiredSPs =
```

#### ＜手動＞7.2 `requiredSPs`を使う場合

`requiredSPs`が空でない場合、列挙したSPだけがMFA必須になる。新SPもMFA必須にするなら、
既存値を残したまま1行へ追加する。

```properties
graphicalmatrix.mfa.requiredSPs = https://sp1.example.org/shibboleth,https://new-sp.example.org/shibboleth
```

同じプロパティを複数行に書くと後ろの値で上書きされるため、必ず1行にまとめる。

#### ＜手動＞7.3 機微なSPとして常にMFAを要求する場合

送信元CIDRでMFAを回避する構成でも、新SPだけは常にMFAを要求する場合は、`forceSPs`を
`bypassNetwork`より前に評価する。

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.forceSPs = https://new-sp.example.org/shibboleth
graphicalmatrix.mfa.bypassCIDRs = 192.168.10.0/24
graphicalmatrix.mfa.policyOrder = forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default
```

この例では、社内ネットワークから新SPへアクセスしてもMFAを要求する。通常SPは
`bypassNetwork`によりMFA不要となる。

#### ＜手動＞7.4 SP単位でMFAを不要にする場合

公開情報だけを扱うSPなど、明示的にMFAを不要とする場合だけ設定する。

```properties
graphicalmatrix.mfa.bypassSPs = https://new-sp.example.org/shibboleth
```

`policyOrder`はプロパティファイルの行順ではなく、値に列挙した左から順に評価される。
設定後は次を実行して検査する。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

MFAポリシーだけを変更した場合は、通常は次回認証から反映される。進行中のセッションを避け、
新しいブラウザセッションで確認する。

## 8. 動作確認

1. IdPとSPのサービス状態を確認する。

```bash
sudo systemctl is-active jetty-idp.service
```

2. SPの保護ページから認証を開始する。IdPのSSO URLを直接利用して確認しない。
3. Password認証と、設定されたMFA方式が実行されることを確認する。
4. SP側で認証済みセッションが作成され、必要な属性だけが受信されることを確認する。
5. IdPログで実際に判定されたSP entityIDとMFAポリシーを確認する。

```bash
sudo grep -E 'MFA (policy decision|method decision)' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 30

sudo tail -n 50 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

6. 次の異常系を確認する。

- metadataにないACS URLを使うAuthnRequestが拒否される。
- MFA必須SPでMFAを完了しなければSPへ戻れない。
- MFA不要SPで、意図したMFAポリシーが適用される。
- SPがIdP署名検証に失敗した場合、SAML Responseを受け入れない。

## 9. ロールバック

問題が発生した場合は、次の順でロールバックする。

1. `metadata-providers.xml`から新SPの`MetadataProvider`を削除する。
2. `/opt/shibboleth-idp/metadata/new-sp.xml`を退避または削除する。
3. `attribute-filter.xml`から新SP向け属性リリースポリシーを削除する。
4. `mfa-policy.properties`から新SP entityIDを削除する。
5. Jettyを再起動する。

SP metadataまたは証明書を更新する場合は、既存のSP登録を削除してから置き換えるのではなく、
SP側とIdP側の証明書切替期間を設けて検証する。鍵ローテーション中に利用者の認証を止めないためである。

## 10. 関連資料

- 検証用SimpleSAMLphp SPの構築例: [INSTALL_SP.md](./INSTALL_SP.md)
- SP別・IP別のMFAポリシー: [FAQ.md](./FAQ.md)
- 2FAS-KW設定項目: [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)
