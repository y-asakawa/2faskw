# SimpleSAMLphp テストSP導入手順・確認記録

## 1. 概要

GraphicalMatrix MFAのエンドツーエンド試験用に、SimpleSAMLphp SPを
`192.168.81.61`へ構築し、IdP `idp.example.com`へ登録した。

DATE_REDACTED時点の現行設定は以下。

```text
IdP:
  Host: idp.example.com
  IP: 192.168.81.60
  EntityID: https://idp.example.com/idp/shibboleth

SP:
  Host: sp.example.com
  IP: 192.168.81.61
  SimpleSAMLphp authsource: 2faskwsp
  EntityID: https://sp.example.com/simplesaml/sp
  Test app: https://sp.example.com/
  Metadata: https://sp.example.com/simplesaml/module.php/saml/sp/metadata/2faskwsp
```

当初は`2faskw_sp`をホスト名として扱ったが、アンダースコアを含むFQDNは
SimpleSAMLphpのURL検証で拒否された。そのため、ホスト名とauthsourceを
`2faskwsp`へ統一した。

注意点:

- SP側の現行HTTPS設定は`sp.example.com`で動作している。
- SP側ApacheのHTTP/80リダイレクトは、確認時点では
  `https://192.168.81.61/`へ向いている。HTTPSのSP動作には影響しないが、
  再構築時は`https://sp.example.com/`へ統一することを推奨する。
- IdP側のmetadataファイル名は旧名の
  `/opt/shibboleth-idp/metadata/2faskw_sp.xml`のまま。
  ただしXML内容は`2faskwsp`のEntityID/ACSへ更新済み。

## 2. 確認済み現行状態

### SPサーバ

読み取り専用で確認した結果。

```bash
ssh operator@192.168.81.61 'hostnamectl; hostname -f'
ssh operator@192.168.81.61 'sudo httpd -t'
ssh operator@192.168.81.61 'systemctl is-active httpd php-fpm'
ssh operator@192.168.81.61 'systemctl is-enabled httpd php-fpm'
ssh operator@192.168.81.61 'sudo firewall-cmd --list-services'
```

結果。

```text
Hostname: sp.example.com
OS: Rocky Linux 10.2 aarch64
Apache config: Syntax OK
httpd: active / enabled
php-fpm: active / enabled
firewalld services: cockpit dhcpv6-client http https ssh
```

HTTPSエンドポイント。

```bash
curl --cacert SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem \
  -o /dev/null -w 'root=%{http_code}\n' \
  https://sp.example.com/

curl --cacert SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem \
  -o /dev/null -w 'metadata=%{http_code}\n' \
  https://sp.example.com/simplesaml/module.php/saml/sp/metadata/2faskwsp

curl --cacert SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem \
  -D - -o /dev/null \
  'https://sp.example.com/?login=1'
```

結果。

```text
root=200
metadata=200
login start=303
Location: https://idp.example.com/idp/profile/SAML2/Redirect/SSO?...SigAlg=...rsa-sha256...
```

### IdPサーバ

読み取り専用で確認した結果。

```bash
ssh operator@192.168.81.60 'hostnamectl; hostname -f'
ssh operator@192.168.81.60 'systemctl is-active jetty-idp.service'
ssh operator@192.168.81.60 \
  'sudo grep -n -A5 -B3 SP2faskwTest /opt/shibboleth-idp/conf/metadata-providers.xml'
ssh operator@192.168.81.60 \
  'sudo grep -nE "EntityDescriptor|AssertionConsumerService|2faskwsp" /opt/shibboleth-idp/metadata/2faskw_sp.xml'
```

結果。

```text
Hostname: idp.example.com
jetty-idp.service: active
MetadataProvider id: SP2faskwTest
metadataFile: %{idp.home}/metadata/2faskw_sp.xml
SP EntityID in XML: https://sp.example.com/simplesaml/sp
SP ACS in XML: https://sp.example.com/simplesaml/module.php/saml/sp/saml2-acs.php/2faskwsp
```

IdP側`/etc/hosts`には以下が入っている。

```text
192.168.81.61 sp.example.com
```

DNSが正式に安定して解決できるなら、このhosts固定は不要になる。

## 3. リモートインストールログの要約

SPサーバのログは`/home/operator/install-sp-01-packages.log`から
`/home/operator/install-sp-09-final-validation.log`まで存在する。
これらは主に初回構築時の`2faskw_sp`およびIP正規URL暫定設定のログであり、
最終的な`2faskwsp`変更後の現行状態とは一部異なる。

```text
/home/operator/install-sp-01-packages.log
  httpd, mod_ssl, PHP 8.3.29, php-fpm, php-ldap, php-xml等を導入。
  httpd/php-fpmをenable。

/home/operator/install-sp-02-simplesamlphp.log
  SimpleSAMLphp 2.5.2 full packageを取得。
  SHA-256検証OK。
  /opt/simplesamlphp-2.5.2 を展開。
  /opt/simplesamlphp/bin/sanitycheck.php は存在せず実行不可。

/home/operator/install-sp-03-configure.log
  TLSファイル、SimpleSAMLphp SP鍵、config.php、authsources.php、
  saml20-idp-remote.php、Apache設定、テストアプリを配置。
  PHP構文チェックOK。
  ただし標準ssl.confがlocalhost.crtを参照してApache構文エラー。

/home/operator/install-sp-04-apache-start.log
  標準ssl.conf退避後、Apache Syntax OK。
  firewalldでhttp/httpsを許可。
  httpd/php-fpm active。
  TCP/80, TCP/443待受を確認。

/home/operator/install-sp-05-local-validation.log
  初回証明書はCN=2faskw_sp.example.com。
  / はHTTP 200。
  /simplesaml/ と /simplesaml/admin/ はHTTP 500。
  原因はアンダースコア付きFQDNをSimpleSAMLphpが拒否したため。

/home/operator/install-sp-06-canonical-ip.log
  baseurlpath/entityIDを一時的に https://192.168.81.61/ 系へ変更。
  SimpleSAMLphp cache clear実行。
  httpd/php-fpm active。

/home/operator/install-sp-07-ip-validation.log
  IP正規URLで / はHTTP 200、/simplesaml/ はHTTP 303、
  SP metadataはHTTP 200。
  古い2faskw_sp URLに対するInvalid destination URLの履歴ログあり。

/home/operator/install-sp-08-fix-idp-metadata.log
  saml20-idp-remote.phpのEndpoint形式をSimpleSAMLphp 2.5.2向けに修正。
  login startがHTTP 303になり、IdP SSOへリダイレクトすることを確認。
  修正前のEndpoint形式エラー履歴あり。

/home/operator/install-sp-09-final-validation.log
  IP正規URL時点でApache Syntax OK、サービスactive、SP metadata HTTP 200、
  署名付きAuthnRequest生成、firewalld許可サービスを確認。
```

IdPサーバのログ。

```text
/home/operator/install-sp-idp-01-metadata.log
  SP metadataを /opt/shibboleth-idp/metadata/2faskw_sp.xml に配置。
  metadata-providers.xmlへSP2faskwTestを追加。
  XML検証OK。

/home/operator/install-sp-idp-02-restart-validation.log
  jetty-idp.service active。
  /idp/status HTTP 200。
  FilesystemMetadataResolver SP2faskwTest が metadataFile を正常ロード。
```

## 4. 新規インストール時の作業順序

この章は、同じ構成をもう一度作る場合の指針。
現行サーバ確認時には、以下の変更系コマンドは実行していない。

### 4.1 前提

```text
SP FQDN: sp.example.com
SP IP: 192.168.81.61
IdP FQDN: idp.example.com
IdP IP: 192.168.81.60
SP authsource: 2faskwsp
SP EntityID: https://sp.example.com/simplesaml/sp
```

ローカル証明書ファイル。

```text
SP_test/202606041921132faskwsp/2faskwsp.cert.pem
SP_test/202606041921132faskwsp/2faskwsp.key
SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem
```

### 4.2 SPサーバへ証明書を転送

ローカルで実行。

```bash
scp -i ~/.ssh/id_ed25519 \
  SP_test/202606041921132faskwsp/2faskwsp.cert.pem \
  SP_test/202606041921132faskwsp/2faskwsp.key \
  SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem \
  operator@192.168.81.61:/home/operator/
```

SPサーバで実行。

```bash
sudo install -o root -g root -m 0644 /home/operator/2faskwsp.cert.pem \
  /etc/pki/tls/certs/2faskwsp.cert.pem
sudo install -o root -g root -m 0644 /home/operator/iic_ca.ca.cert.pem \
  /etc/pki/tls/certs/iic_ca.ca.cert.pem
sudo install -o root -g root -m 0600 /home/operator/2faskwsp.key \
  /etc/pki/tls/private/2faskwsp.key
sudo sh -c 'cat /etc/pki/tls/certs/2faskwsp.cert.pem /etc/pki/tls/certs/iic_ca.ca.cert.pem > /etc/pki/tls/certs/2faskwsp.fullchain.pem'

sudo mkdir -p /opt/simplesamlphp/cert
sudo install -o root -g apache -m 0640 /home/operator/2faskwsp.key \
  /opt/simplesamlphp/cert/2faskwsp.key
sudo install -o root -g apache -m 0644 /home/operator/2faskwsp.cert.pem \
  /opt/simplesamlphp/cert/2faskwsp.cert.pem
```

確認。

```bash
openssl verify -CAfile /etc/pki/tls/certs/iic_ca.ca.cert.pem \
  /etc/pki/tls/certs/2faskwsp.cert.pem
openssl x509 -in /etc/pki/tls/certs/2faskwsp.cert.pem \
  -noout -subject -issuer -dates -ext subjectAltName
```

### 4.3 SPサーバへApache/PHPを導入

SPサーバで実行。

```bash
sudo dnf install -y \
  httpd \
  mod_ssl \
  php \
  php-cli \
  php-fpm \
  php-mbstring \
  php-xml \
  php-intl \
  php-ldap \
  php-opcache \
  php-process \
  curl \
  tar \
  openssl \
  unzip

sudo systemctl enable --now php-fpm.service httpd.service
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload
```

標準`ssl.conf`が存在しないlocalhost証明書を参照して起動できない場合のみ退避。

```bash
sudo mv /etc/httpd/conf.d/ssl.conf /etc/httpd/conf.d/ssl.conf.disabled
```

### 4.4 SimpleSAMLphpを導入

SPサーバで実行。

```bash
sudo mkdir -p /opt/src
cd /opt/src

sudo curl -fL --retry 3 \
  -o simplesamlphp-2.5.2-full.tar.gz \
  https://github.com/simplesamlphp/simplesamlphp/releases/download/v2.5.2/simplesamlphp-2.5.2-full.tar.gz

echo '1394883cc15fb532b9cbac899377caac72163aaab964c0c67a793a69142a8902  simplesamlphp-2.5.2-full.tar.gz' |
  sudo sha256sum -c -

sudo tar xzf simplesamlphp-2.5.2-full.tar.gz -C /opt
sudo ln -sfn /opt/simplesamlphp-2.5.2 /opt/simplesamlphp
sudo chown -R root:root /opt/simplesamlphp-2.5.2
```

### 4.5 `/opt/simplesamlphp/config/config.php`を編集

編集ファイル。

```text
/opt/simplesamlphp/config/config.php
```

編集方針。

```php
'baseurlpath' => 'https://sp.example.com/simplesaml/',
'technicalcontact_name' => '2faskw SP Administrator',
'technicalcontact_email' => 'root@sp.example.com',
'secretsalt' => '<random string>',
'trusted.url.regex' => true,
'trusted.url.domains' => ['2faskwsp\.example-u\.ac\.jp'],
'session.cookie.secure' => true,
'session.phpsession.cookiename' => 'SimpleSAML_2faskwsp',
'store.type' => 'phpsession',
```

実際の編集は`vi`等で行う。

```bash
sudo vi /opt/simplesamlphp/config/config.php
```

確認。

```bash
php -l /opt/simplesamlphp/config/config.php
sudo grep -nE 'baseurlpath|trusted.url.domains|trusted.url.regex|session.cookie.secure|session.phpsession.cookiename|store.type' \
  /opt/simplesamlphp/config/config.php
```

### 4.6 `/opt/simplesamlphp/config/authsources.php`を編集

編集ファイル。

```text
/opt/simplesamlphp/config/authsources.php
```

設定例。

```php
<?php

$config = [
    'admin' => [
        'core:AdminPassword',
    ],

    '2faskwsp' => [
        'saml:SP',
        'entityID' => 'https://sp.example.com/simplesaml/sp',
        'idp' => 'https://idp.example.com/idp/shibboleth',
        'discoURL' => null,
        'privatekey' => '2faskwsp.key',
        'certificate' => '2faskwsp.cert.pem',
        'sign.logout' => true,
    ],
];
```

編集。

```bash
sudo vi /opt/simplesamlphp/config/authsources.php
php -l /opt/simplesamlphp/config/authsources.php
```

### 4.7 `/opt/simplesamlphp/metadata/saml20-idp-remote.php`を編集

編集ファイル。

```text
/opt/simplesamlphp/metadata/saml20-idp-remote.php
```

IdPメタデータを取得し、SimpleSAMLphp形式へ変換して記載する。

```bash
curl -o /tmp/idp-metadata.xml https://idp.example.com/idp/shibboleth
```

SimpleSAMLphp 2.5.2では、`SingleSignOnService`と`SingleLogoutService`を
配列の配列として記述する。

```php
<?php

$metadata['https://idp.example.com/idp/shibboleth'] = [
    'SingleSignOnService' => [
        [
            'Binding' => 'urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect',
            'Location' => 'https://idp.example.com/idp/profile/SAML2/Redirect/SSO',
        ],
    ],
    'SingleLogoutService' => [
        [
            'Binding' => 'urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect',
            'Location' => 'https://idp.example.com/idp/profile/SAML2/Redirect/SLO',
        ],
    ],
    'certData' => '<IdP signing certificate body>',
    'sign.authnrequest' => true,
];
```

編集。

```bash
sudo vi /opt/simplesamlphp/metadata/saml20-idp-remote.php
php -l /opt/simplesamlphp/metadata/saml20-idp-remote.php
```

### 4.8 Apache HTTPS設定を編集

編集ファイル。

```text
/etc/httpd/conf.d/2faskwsp.conf
```

推奨設定例。

```apache
ServerName sp.example.com
Listen 443 https
SSLSessionCache shmcb:/run/httpd/sslcache(512000)
SSLSessionCacheTimeout 300

<VirtualHost *:80>
    ServerName sp.example.com
    ServerAlias sp.example.com
    Redirect permanent / https://sp.example.com/
</VirtualHost>

<VirtualHost *:443>
    ServerName sp.example.com
    ServerAlias sp.example.com
    DocumentRoot /var/www/2faskwsp

    SSLEngine on
    SSLCertificateFile /etc/pki/tls/certs/2faskwsp.fullchain.pem
    SSLCertificateKeyFile /etc/pki/tls/private/2faskwsp.key

    Header always set Strict-Transport-Security "max-age=31536000"
    SetEnv SIMPLESAMLPHP_CONFIG_DIR /opt/simplesamlphp/config

    Alias /simplesaml /opt/simplesamlphp/public

    <Directory /opt/simplesamlphp/public>
        Require all granted
        AllowOverride None
        Options FollowSymLinks
        DirectoryIndex index.php
    </Directory>

    <Directory /var/www/2faskwsp>
        Require all granted
        AllowOverride None
        Options -Indexes
        DirectoryIndex index.php
    </Directory>

    ErrorLog logs/2faskwsp_error.log
    CustomLog logs/2faskwsp_access.log combined
</VirtualHost>
```

編集・確認・再起動。

```bash
sudo vi /etc/httpd/conf.d/2faskwsp.conf
sudo apachectl configtest
sudo systemctl restart httpd php-fpm
```

### 4.9 テストSPアプリを配置

編集ファイル。

```text
/var/www/2faskwsp/index.php
```

最小構成。

```php
<?php

declare(strict_types=1);

require_once '/opt/simplesamlphp/src/_autoload.php';

$auth = new SimpleSAML\Auth\Simple('2faskwsp');

if (isset($_GET['login'])) {
    $auth->requireAuth(['ReturnTo' => 'https://sp.example.com/']);
}
if (isset($_GET['logout'])) {
    $auth->logout('https://sp.example.com/');
}

$authenticated = $auth->isAuthenticated();
$attributes = $authenticated ? $auth->getAttributes() : [];
```

配置・確認。

```bash
sudo mkdir -p /var/www/2faskwsp
sudo vi /var/www/2faskwsp/index.php
php -l /var/www/2faskwsp/index.php
```

### 4.10 SimpleSAMLphp cacheをクリア

```bash
sudo -u apache /opt/simplesamlphp/bin/console cache:clear
```

環境によってapacheユーザーで実行できない場合はrootで実行し、動作確認後に
cache/logディレクトリの権限を確認する。

## 5. IdP側のSP登録手順

### 5.1 SP metadataを取得

IdPサーバで実行。

```bash
curl -o /tmp/2faskwsp.xml \
  https://sp.example.com/simplesaml/module.php/saml/sp/metadata/2faskwsp

grep -E 'entityID|AssertionConsumerService|SingleLogoutService|X509Certificate' \
  /tmp/2faskwsp.xml
```

現行環境ではファイル名を旧名で維持している。
新規構築では`2faskwsp.xml`へ変更してもよいが、
`metadata-providers.xml`側と必ず一致させる。

```bash
sudo install -o jetty -g jetty -m 0644 /tmp/2faskwsp.xml \
  /opt/shibboleth-idp/metadata/2faskw_sp.xml
```

### 5.2 `metadata-providers.xml`を編集

編集ファイル。

```text
/opt/shibboleth-idp/conf/metadata-providers.xml
```

追加する設定。

```xml
<!-- BEGIN 2faskwsp test SP metadata -->
<MetadataProvider id="SP2faskwTest"
                  xsi:type="FilesystemMetadataProvider"
                  metadataFile="%{idp.home}/metadata/2faskw_sp.xml"/>
<!-- END 2faskwsp test SP metadata -->
```

編集。

```bash
sudo vi /opt/shibboleth-idp/conf/metadata-providers.xml
```

### 5.3 IdPからSP FQDNが解決できない場合

DNSで解決できない場合のみ、IdPサーバで実行。

```bash
echo '192.168.81.61 sp.example.com' | sudo tee -a /etc/hosts
```

現行環境ではこのhostsエントリが入っている。

### 5.4 IdPを再起動して確認

```bash
sudo systemctl restart jetty-idp.service
systemctl is-active jetty-idp.service
sudo journalctl -u jetty-idp.service -n 50 --no-pager | grep SP2faskwTest
```

期待結果。

```text
FilesystemMetadataResolver SP2faskwTest:
New metadata successfully loaded for '/opt/shibboleth-idp/metadata/2faskw_sp.xml'
```

## 6. 動作確認コマンド

SPサーバまたは作業端末から確認。

```bash
curl --cacert SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem \
  -o /dev/null -w '%{http_code}\n' \
  https://sp.example.com/

curl --cacert SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem \
  -o /dev/null -w '%{http_code}\n' \
  https://sp.example.com/simplesaml/module.php/saml/sp/metadata/2faskwsp

curl --cacert SP_test/202606041921132faskwsp/iic_ca.ca.cert.pem \
  -D - -o /dev/null \
  'https://sp.example.com/?login=1'
```

期待結果。

```text
/ : 200
/simplesaml/module.php/saml/sp/metadata/2faskwsp : 200
/?login=1 : 303
Location: https://idp.example.com/idp/profile/SAML2/Redirect/SSO
SigAlg: rsa-sha256
```

ブラウザ試験。

1. `https://sp.example.com/`を開く。
2. `Login with 2faskw IdP`を押す。
3. IdPでLDAP Password + GraphicalMatrix MFAを完了する。
4. SPへ戻り、SAML attributesが表示されることを確認する。
5. Logoutを押し、SLOが完了することを確認する。

## 7. 現行ファイル構成

SPサーバ。

```text
/etc/httpd/conf.d/2faskwsp.conf
/etc/pki/tls/certs/2faskwsp.cert.pem
/etc/pki/tls/certs/2faskwsp.fullchain.pem
/etc/pki/tls/certs/iic_ca.ca.cert.pem
/etc/pki/tls/private/2faskwsp.key
/opt/simplesamlphp -> /opt/simplesamlphp-2.5.2
/opt/simplesamlphp/config/config.php
/opt/simplesamlphp/config/authsources.php
/opt/simplesamlphp/metadata/saml20-idp-remote.php
/opt/simplesamlphp/cert/2faskwsp.cert.pem
/opt/simplesamlphp/cert/2faskwsp.key
/var/www/2faskwsp/index.php
```

IdPサーバ。

```text
/opt/shibboleth-idp/metadata/2faskw_sp.xml
/opt/shibboleth-idp/conf/metadata-providers.xml
/etc/hosts
```

ローカル証明書・作業ファイル。

```text
SP_test/202606041921132faskwsp/
SP_test/202606041713482faskw_sp/
```

`202606041713482faskw_sp`は旧名作業時の証明書・deployファイル。
現行FQDNでは`202606041921132faskwsp`を使用する。

## 8. SP metadata更新が必要なケース

以下の場合はIdP側のSP metadataを更新する。

```text
SP証明書・鍵を更新した
SP EntityIDを変更した
SP ACS URLを変更した
SP authsource名を変更した
SP FQDNを変更した
```

更新手順。

```bash
curl -o /tmp/2faskwsp.xml \
  https://sp.example.com/simplesaml/module.php/saml/sp/metadata/2faskwsp

sudo install -o jetty -g jetty -m 0644 /tmp/2faskwsp.xml \
  /opt/shibboleth-idp/metadata/2faskw_sp.xml

sudo systemctl restart jetty-idp.service
sudo journalctl -u jetty-idp.service -n 50 --no-pager | grep SP2faskwTest
```

## 9. IdP metadata更新が必要なケース

以下の場合はSP側のIdP remote metadataを更新する。

```text
IdP署名証明書を更新した
IdP EntityIDを変更した
IdP SSO/SLO URLを変更した
```

更新対象。

```text
/opt/simplesamlphp/metadata/saml20-idp-remote.php
```

更新後。

```bash
php -l /opt/simplesamlphp/metadata/saml20-idp-remote.php
sudo -u apache /opt/simplesamlphp/bin/console cache:clear
sudo systemctl restart httpd php-fpm
```

## 10. ロールバック

SPサーバの初回導入前バックアップ。

```text
/opt/backups/simplesamlphp-sp-20260604172109
```

IdPサーバのSP metadata登録前バックアップ。

```text
/opt/backups/2faskw-sp-metadata-20260604172937
```

IdPからSP metadataを除去する場合。

```bash
sudo vi /opt/shibboleth-idp/conf/metadata-providers.xml
sudo rm -f /opt/shibboleth-idp/metadata/2faskw_sp.xml
sudo systemctl restart jetty-idp.service
```

`metadata-providers.xml`からは`SP2faskwTest`ブロックを削除する。

## 11. 注意事項

- `install-sp-01`から`install-sp-09`は旧名/暫定IP設定の履歴を含む。
  現行設定確認には、必ず実ファイルを確認する。
- 新規構築時は`2faskwsp`へ統一し、`2faskw_sp`をホスト名やauthsourceに使わない。
- IdP側metadataファイル名は旧名のままでも動作するが、運用上は
  `2faskwsp.xml`へ統一した方が分かりやすい。
- SimpleSAMLphp管理画面を利用する場合、管理者パスワードを別途設定する。
- 作業後はパスワードレスsudoを無効化する。
- 本番相当の試験では、HTTP/80のリダイレクト先もFQDNへ統一する。

## 12. firewalld SSH接続元制限

DATE_REDACTEDに、SPサーバ `192.168.81.61` のSSH接続元を
`192.168.81.0/24` のみに制限した。

作業前の確認:

```bash
echo "$SSH_CONNECTION"
sudo firewall-cmd --state
sudo firewall-cmd --get-active-zones
sudo firewall-cmd --list-all
```

作業前のバックアップ:

```bash
ts=$(date +%Y%m%d%H%M%S)
sudo sh -c "firewall-cmd --permanent --list-all > /root/firewalld-before-ssh-restrict-$ts.txt"
```

設定コマンド:

```bash
sudo firewall-cmd --permanent --zone=public \
  --add-rich-rule='rule family="ipv4" source address="192.168.81.0/24" service name="ssh" accept'

sudo firewall-cmd --permanent --zone=public --remove-service=ssh
sudo firewall-cmd --reload
```

確認コマンド:

```bash
sudo firewall-cmd --permanent --zone=public --query-service=ssh
sudo firewall-cmd --permanent --zone=public \
  --query-rich-rule='rule family="ipv4" source address="192.168.81.0/24" service name="ssh" accept'
sudo firewall-cmd --zone=public --list-all
```

確認結果:

```text
query-service=ssh: no
query-rich-rule: yes
```

この設定により、SSHは `192.168.81.0/24` からのみ許可される。
HTTP/HTTPS等の既存公開サービスは変更していない。
