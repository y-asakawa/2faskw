# 新しいSAML SPをShibboleth IdPへ追加する（手作業）

## 1. 対象と方針

本書は、2FAS-KWを導入したShibboleth IdPへ、新しいSAML Service Provider（SP）を
手作業で登録する従来手順である。SP metadata、SP向け属性release、MFAポリシーを
`metadata-providers.xml`、`attribute-filter.xml`、`mfa-policy.properties`で直接管理する。

v1.3.0で`graphicalmatrix-sp.sh`を使える環境では、
[INSTALL_NEW_SP.md](./INSTALL_NEW_SP.md)のCLI管理手順を推奨する。CLI管理するSPに対して、
本書の手順でmetadata、属性release、SP単位MFA方針を直接編集してはならない。

本番SPはShibboleth SPを推奨する。SimpleSAMLphpは既存運用との互換または検証用途として利用できるが、
IdPが扱うSAML metadata、属性release、MFAポリシーの考え方は同じである。

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

SPのTLS証明書とSAML署名用証明書は役割が異なる。TLSだけを信頼してSAML Responseの
署名検証を省略してはならない。

## 4. SP側を設定する

SP側では、製品の公式手順に従ってSAML SPを設定する。少なくとも次を完了する。

1. HTTPSを有効化し、利用者向けURLを正式FQDNへ統一する。
2. SP entityID、ACS URL、SP署名鍵・証明書を設定する。
3. IdP metadataを登録し、IdPのSAML Response署名を検証する。
4. AuthnRequest署名を有効化できるSPでは、有効化する。
5. IdPが取得できるSP metadata endpoint、または検査済みmetadataファイルを用意する。

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

SP側の`entityID`、ACS URL、署名証明書は、以降でIdPへ登録するmetadataと一致させる。

## 5. IdPへSP metadataを登録する

### 5.1 metadataを取得して検査する

IdPサーバでSP metadataを取得する。取得元はSP管理者が提示したHTTPS endpoint、または
署名付きの配布metadataに限定する。利用者入力や未検証URLからmetadataを取り込まない。

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

### 5.2 metadataファイルを配置する

検査済みmetadataをIdPへ配置する。

```bash
sudo install -o root -g jetty -m 0640 /tmp/new-sp.xml \
  /opt/shibboleth-idp/metadata/new-sp.xml
sudo restorecon -v /opt/shibboleth-idp/metadata/new-sp.xml

# Jetty実行ユーザーがmetadataを読めることを確認する。
sudo -u jetty test -r /opt/shibboleth-idp/metadata/new-sp.xml && \
  echo 'OK: Jetty can read SP metadata'
```

`FilesystemMetadataProvider`はmetadataをJetty実行ユーザーが読めない場合、fail-fastで初期化に失敗する。
その場合、他SPを含む`MetadataResolverService`全体が利用不能になる。上の確認に失敗した場合は、
実際のmetadataファイルとJetty実行ユーザーを確認して、所有者・group・mode・SELinux contextを修正する。

```bash
# metadata-providers.xmlに設定したmetadataFileの実パスを確認する。
sudo grep -n 'metadataFile=' /opt/shibboleth-idp/conf/metadata-providers.xml

# この例ではJetty groupへ読み取りを許可する。
sudo chown root:jetty /opt/shibboleth-idp/metadata/new-sp.xml
sudo chmod 0640 /opt/shibboleth-idp/metadata/new-sp.xml
sudo restorecon -v /opt/shibboleth-idp/metadata/new-sp.xml

sudo -u jetty test -r /opt/shibboleth-idp/metadata/new-sp.xml && \
  echo 'OK: Jetty can read SP metadata'
```

### 5.3 metadata providerを追加する

`/opt/shibboleth-idp/conf/metadata-providers.xml`へ、既存の`MetadataProvider`と同じ階層で追加する。
`id`はIdP内で重複しない名前にする。

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

metadata provider変更はWAR再構築を必要としない。起動失敗またはmetadata load errorがないことを
Journalと`/opt/shibboleth-idp/logs/idp-process.log`で確認する。

## 6. SP向けの属性releaseを設定する

SPへ送る属性は最小限にする。`/opt/shibboleth-idp/conf/attribute-filter.xml`へ、SP entityIDに
完全一致するポリシーを追加する。以下は`uid`と`mail`をSPへ送る例である。`attributeID`はIdP内部の
属性IDであり、実際に値を生成するAttribute Resolverと、SAML属性名へ変換するAttribute Registry
（またはlegacy AttributeEncoder）の両方に定義されていなければならない。

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

属性フィルタを変更した後は、Jettyを再起動する。

```bash
sudo systemctl restart jetty-idp.service
```

管理者用属性、LDAP password、GraphicalMatrix sequence、TOTP seed、WebAuthn credentialを
SPへreleaseしてはならない。

SP側では、IdP内部IDの`uid`、`mail`ではなく、Attribute Registryが定義するSAML属性名を受信する。
標準的な例では`uid`は`urn:oid:0.9.2342.19200300.100.1.1`、`mail`は
`urn:oid:0.9.2342.19200300.100.1.3`になる。SPのattribute mapやSimpleSAMLphpの確認画面では、
実際のSAML属性名を確認する。

## 7. 2FAS-KWのMFAポリシーを設定する

編集対象は次のファイルである。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
```

### 7.1 全SPでMFAを必須にする場合

次の既定であれば、新SP向けの追記は不要である。

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.requiredSPs =
```

### 7.2 `requiredSPs`を使う場合

`requiredSPs`が空でない場合、列挙したSPだけがMFA必須になる。新SPもMFA必須にするなら、
既存値を残したまま1行へ追加する。

```properties
graphicalmatrix.mfa.requiredSPs = https://sp1.example.org/shibboleth,https://new-sp.example.org/shibboleth
```

同じプロパティを複数行に書くと後ろの値で上書きされるため、必ず1行にまとめる。

### 7.3 機微なSPとして常にMFAを要求する場合

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

### 7.4 SP単位でMFAを不要にする場合

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
3. `attribute-filter.xml`から新SP向け属性releaseポリシーを削除する。
4. `mfa-policy.properties`から新SP entityIDを削除する。
5. Jettyを再起動する。

SP metadataまたは証明書を更新する場合は、既存のSP登録を削除してから置き換えるのではなく、
SP側とIdP側の証明書切替期間を設けて検証する。鍵ローテーション中に利用者の認証を止めないためである。

## 関連資料

- CLI管理によるSP追加: [INSTALL_NEW_SP.md](./INSTALL_NEW_SP.md)
- 検証用SimpleSAMLphp SPの構築例: [INSTALL_SP.md](./INSTALL_SP.md)
- SP別・IP別のMFAポリシー: [FAQ.md](./FAQ.md)
- 2FAS-KW設定項目: [CONFIG-REFERENCE.md](./CONFIG-REFERENCE.md)
