# ビルドサーバ アプリケーション導入記録

## 1. 概要

DATE_REDACTEDに、新しいビルドサーバへGraphicalMatrix Pluginのビルド・検証に必要な
アプリケーションを導入した。

対象サーバ:

```text
SSH: user@192.168.81.60
FQDN: idp.example.com
OS: Rocky Linux 10.2
CPU: aarch64
SELinux: Enforcing
```

導入結果:

| アプリケーション | 導入バージョン | 状態 |
|---|---:|---|
| Java | OpenJDK 21.0.11 | 導入済み |
| Maven | 3.9.9 | 導入済み |
| Jetty | 12.0.36 | systemd・HTTPSで起動済み |
| Shibboleth IdP | 5.2.2 | `https://idp.example.com/idp/`で起動済み |
| PostgreSQL | 18.4 | systemdで起動済み |

JettyはShibboleth IdP 5が使用するJakarta EE 10に対応した、Jetty 12.0安定系を使用した。
PostgreSQLはRocky Linux標準リポジトリの18.3ではなく、PGDG公式リポジトリの18.4を使用した。

参考URL:

- Shibboleth IdP: https://shibboleth.net/downloads/identity-provider/latest/
- Jetty 12: https://jetty.org/docs/jetty/12/
- PostgreSQL Red Hat系: https://www.postgresql.org/download/linux/redhat/

## 2. 事前確認

実行コマンド:

```bash
ssh user@192.168.81.60

cat /etc/os-release
uname -m
getenforce
sudo -n true
```

確認結果:

```text
Rocky Linux 10.2 (Red Quartz)
aarch64
Enforcing
passwordless sudo: OK
```

インストール作業中のみ、`user`ユーザーでパスワードレスsudoを使用した。
作業完了後は、不要であれば以下を削除して元に戻す。

```bash
sudo rm -f /etc/sudoers.d/99-user-nopasswd
sudo visudo -c
```

## 3. 配布物の取得

配布物保存先:

```text
/home/user/install-media
/opt/src
```

実行コマンド:

```bash
mkdir -p ~/install-media
cd ~/install-media

curl -fL --retry 3 \
  -o jetty-home-12.0.36.tar.gz \
  https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-home/12.0.36/jetty-home-12.0.36.tar.gz

curl -fL --retry 3 \
  -o shibboleth-identity-provider-5.2.2.tar.gz \
  https://shibboleth.net/downloads/identity-provider/latest/shibboleth-identity-provider-5.2.2.tar.gz

sha256sum jetty-home-12.0.36.tar.gz \
  shibboleth-identity-provider-5.2.2.tar.gz
```

確認結果:

```text
a5273bd642c3134c0220c02c4b228a0569d4e8a7d7db62ba3f5d573a6511e76b  jetty-home-12.0.36.tar.gz
34e9ff69f60debd1a2b689b1992c0da4ac6c5b0e4b21ad662f6aee3f0c227852  shibboleth-identity-provider-5.2.2.tar.gz
```

ShibbolethのSHA-256は公式配布サイトの値と一致した。

## 4. Java 21とビルドツール

実行コマンド:

```bash
sudo dnf install -y \
  java-21-openjdk-devel \
  maven \
  tar \
  unzip \
  wget \
  openssl \
  curl \
  which
```

CLI用の環境変数:

```bash
sudo tee /etc/profile.d/java21.sh >/dev/null <<'EOF'
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
EOF

sudo chmod 0644 /etc/profile.d/java21.sh
```

確認コマンド:

```bash
java -version
javac -version
mvn -version
```

確認結果:

```text
openjdk version "21.0.11" DATE_REDACTED LTS
javac 21.0.11
Apache Maven 3.9.9
Java home: /usr/lib/jvm/java-21-openjdk
Architecture: aarch64
```

## 5. PostgreSQL 18.4

### 5.1 PGDGリポジトリとパッケージ導入

実行コマンド:

```bash
sudo dnf install -y \
  https://download.postgresql.org/pub/repos/yum/reporpms/EL-10-aarch64/pgdg-redhat-repo-latest.noarch.rpm

sudo dnf clean metadata
sudo dnf makecache

sudo dnf install -y \
  postgresql18-server \
  postgresql18-contrib
```

CLI用PATH:

```bash
sudo tee /etc/profile.d/postgresql18.sh >/dev/null <<'EOF'
export PATH="/usr/pgsql-18/bin:$PATH"
EOF

sudo chmod 0644 /etc/profile.d/postgresql18.sh
```

### 5.2 DBクラスタ初期化と起動

実行コマンド:

```bash
sudo /usr/pgsql-18/bin/postgresql-18-setup initdb
sudo systemctl enable --now postgresql-18.service
```

確認コマンド:

```bash
sudo systemctl is-enabled postgresql-18.service
sudo systemctl is-active postgresql-18.service

sudo -u postgres /usr/pgsql-18/bin/psql -Atqc 'SELECT version();'
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc 'SHOW listen_addresses; SHOW port;'
```

確認結果:

```text
postgresql-18.service: enabled / active
PostgreSQL 18.4 on aarch64-unknown-linux-gnu
listen_addresses: localhost
port: 5432
```

待受状態:

```text
127.0.0.1:5432
```

PostgreSQLは外部ネットワークへ公開していない。
GraphicalMatrix用DB、DBユーザー、スキーマは、このアプリケーション導入作業では作成していない。

## 6. Jetty 12.0.36

### 6.1 専用ユーザーと配置

実行コマンド:

```bash
sudo useradd --system \
  --home-dir /opt/jetty-base \
  --shell /sbin/nologin \
  jetty

sudo mkdir -p /opt/src /opt/jetty-base /opt/shibboleth-idp

sudo tar xzf /opt/src/jetty-home-12.0.36.tar.gz -C /opt
sudo ln -sfn /opt/jetty-home-12.0.36 /opt/jetty-home

sudo chown -R jetty:jetty /opt/jetty-base /opt/shibboleth-idp
```

配置結果:

```text
/opt/jetty-home -> /opt/jetty-home-12.0.36
/opt/jetty-base
```

### 6.2 Jetty Base初期化

実行コマンド:

```bash
sudo -u jetty bash -lc '
export JETTY_HOME=/opt/jetty-home
export JETTY_BASE=/opt/jetty-base
cd "$JETTY_BASE"

java -jar "$JETTY_HOME/start.jar" \
  --add-modules=http,ee10-deploy,ee10-annotations,ee10-jsp,ee10-jstl
'
```

有効化した主要モジュール:

```text
http
ee10-deploy
ee10-annotations
ee10-jsp
ee10-jstl
```

確認結果:

```text
jetty.version = 12.0.36
jetty.home = /opt/jetty-home-12.0.36
jetty.base = /opt/jetty-base
java.version = 21.0.11
```

## 7. Shibboleth IdP 5.2.2

### 7.1 配置とインストール

実行コマンド:

```bash
sudo tar xzf /opt/src/shibboleth-identity-provider-5.2.2.tar.gz -C /opt/src

cat > /tmp/idp-install.properties <<'EOF'
idp.scope=example.org
EOF
chmod 0600 /tmp/idp-install.properties

KS="$(openssl rand -base64 36 | tr -d '\n')"
SEALER="$(openssl rand -base64 36 | tr -d '\n')"

cd /opt/src/shibboleth-identity-provider-5.2.2
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

sudo -E ./bin/install.sh \
  --noPrompt \
  --propertyFile /tmp/idp-install.properties \
  --targetDir /opt/shibboleth-idp \
  --hostName 192.168.81.60 \
  --entityID https://192.168.81.60/idp/shibboleth \
  --keystorePassword "$KS" \
  --sealerPassword "$SEALER"

unset KS SEALER
rm -f /tmp/idp-install.properties

sudo chown -R jetty:jetty /opt/shibboleth-idp
```

実際の秘密値はログおよび本書には記録していない。

現在の設定:

```properties
idp.entityID=https://idp.example.com/idp/shibboleth
idp.scope=example.com
idp.cookie.secure = true
```

FQDN、entityID、scopeはHTTPS化時に正式値へ更新した。

### 7.2 MFAモジュール有効化

実行コマンド:

```bash
sudo -u jetty env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/module.sh -e idp.authn.MFA

sudo -u jetty env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh
```

有効モジュール:

```text
idp.Core
idp.CommandLine
idp.EditWebApp
idp.authn.Password
idp.authn.MFA
idp.admin.Hello
```

確認結果:

```bash
sudo -u jetty env JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /opt/shibboleth-idp/bin/version.sh
```

```text
5.2.2
```

## 8. JettyへのIdP配備

作成ファイル:

```text
/opt/jetty-base/webapps/idp.xml
/etc/systemd/system/jetty-idp.service
```

`/opt/jetty-base/webapps/idp.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "http://www.eclipse.org/jetty/configure.dtd">
<Configure class="org.eclipse.jetty.ee10.webapp.WebAppContext">
    <Set name="war">/opt/shibboleth-idp/war/idp.war</Set>
    <Set name="contextPath">/idp</Set>
    <Set name="extractWAR">false</Set>
    <Set name="copyWebDir">false</Set>
    <Set name="copyWebInf">true</Set>
</Configure>
```

`/etc/systemd/system/jetty-idp.service`:

```ini
[Unit]
Description=Jetty 12 for Shibboleth IdP 5
After=network-online.target postgresql-18.service
Wants=network-online.target

[Service]
Type=simple
User=jetty
Group=jetty
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk
Environment=JETTY_HOME=/opt/jetty-home
Environment=JETTY_BASE=/opt/jetty-base
WorkingDirectory=/opt/jetty-base
ExecStart=/usr/bin/java -Didp.home=/opt/shibboleth-idp -jar /opt/jetty-home/start.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

起動コマンド:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now jetty-idp.service
```

確認結果:

```text
jetty-idp.service: enabled / active
Jetty listener: 0.0.0.0:8080
/idp/: HTTP 200
/idp/status: HTTP 200
```

firewalldでは8080番を許可していないため、現在は外部ネットワーク向けの公開設定を行っていない。

## 9. 発生した問題と対応

### 9.1 IdPインストール時のidp.scope不足

最初の無人インストールでは、以下のエラーが発生した。

```text
ERROR - Installation run failed
No value for idp.scope specified
```

対応:

- 未完了の新規インストール先を削除
- property fileへ`idp.scope=example.org`を追加
- IdPインストールを最初から再実行

### 9.2 `/idp/status`がHTTP 500

最初のJetty構成では、以下のクラス不足が発生した。

```text
NoClassDefFoundError: jakarta/servlet/jsp/jstl/core/Config
```

対応:

```bash
sudo -u jetty bash -lc '
export JETTY_HOME=/opt/jetty-home
export JETTY_BASE=/opt/jetty-base
cd "$JETTY_BASE"
java -jar "$JETTY_HOME/start.jar" --add-modules=ee10-jstl
'

sudo systemctl restart jetty-idp.service
```

修正後:

```text
/idp/status: HTTP 200
```

## 10. 最終確認コマンド

```bash
java -version
javac -version
mvn -version

export JETTY_HOME=/opt/jetty-home
export JETTY_BASE=/opt/jetty-base
cd "$JETTY_BASE"
java -jar "$JETTY_HOME/start.jar" --list-config

sudo -u jetty env JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /opt/shibboleth-idp/bin/version.sh

postgres --version
psql --version

sudo systemctl is-enabled postgresql-18.service
sudo systemctl is-active postgresql-18.service
sudo systemctl is-enabled jetty-idp.service
sudo systemctl is-active jetty-idp.service

curl -i http://127.0.0.1:8080/idp/
curl -i http://127.0.0.1:8080/idp/status

sudo ss -ltnp | grep -E ':(5432|8080)[[:space:]]'
```

最終結果:

```text
Java: 21.0.11
Maven: 3.9.9
Jetty: 12.0.36
Shibboleth IdP: 5.2.2
PostgreSQL: 18.4
PostgreSQL: enabled / active / 127.0.0.1:5432
Jetty IdP: enabled / active / 0.0.0.0:443 / 127.0.0.1:8080
HTTPS /idp/: HTTP 200
HTTPS /idp/status from loopback: HTTP 200
external HTTP 8080: connection refused
SELinux: Enforcing
```

PostgreSQLとJetty IdPを明示的に再起動した後も、両サービスの`enabled / active`、
PostgreSQL 18.4、HTTPSの`/idp/`とループバック経由の`/idp/status`のHTTP 200を再確認した。

## 11. サーバ上の作業ログ

実行結果の詳細ログ:

```text
/home/user/install-logs/01-packages.log
/home/user/install-logs/02-postgresql-init.log
/home/user/install-logs/03-prerequisites-layout.log
/home/user/install-logs/04-jetty-idp-install.log
/home/user/install-logs/05-idp-install-retry.log
/home/user/install-logs/06-jetty-service.log
/home/user/install-logs/07-idp-jetty-modules.log
/home/user/install-logs/08-final-validation.log
/home/user/install-logs/09-https-config.log
/home/user/install-logs/09-https-config-continue.log
/home/user/install-logs/10-https-validation.log
/home/user/install-logs/11-ldap-sp-config.log
/home/user/install-logs/12-ldap-idp-validation.log
/home/user/install-logs/13-sp-config-removal.log
/home/user/install-logs/14-ldap-only-validation.log
/home/user/install-logs/15-totp-webauthn-plugin-config.log
/home/user/install-logs/16-totp-webauthn-validation.log
```

本書もサーバ上へ配置した。

```text
/home/user/INSTALL_AP.md
```

## 12. HTTPS / FQDN設定

DATE_REDACTEDに、以下のFQDNと証明書を使用してJettyをHTTPS化した。

```text
FQDN: idp.example.com
IP: 192.168.81.60
HTTPS URL: https://idp.example.com/idp/
```

証明書:

```text
Subject: C=JP, ST=Nagano, L=Nagano, O=Example Organization, OU=IIC, CN=idp.example.com
Issuer: CN=private-ca.example.com
SAN: DNS:idp.example.com, IP:192.168.81.60
notBefore: DATE_REDACTED 03:40:27 UTC
notAfter: DATE_REDACTED 03:40:27 UTC
SHA-256: E8:B2:F7:59:11:41:CA:64:F0:1F:C3:B6:8C:52:E7:F2:10:3C:63:3A:B4:41:38:B1:BE:C9:00:F4:D6:72:9C:37
```

IIC CA証明書の有効期限はDATE_REDACTEDのため、証明書チェーンとしての実質的な
信頼可能期間はCA証明書の有効期限にも制約される。

### 12.1 証明書確認

実行コマンド:

```bash
openssl verify \
  -CAfile iic_ca.ca.cert.pem \
  2faskw.cert.pem

openssl x509 -in 2faskw.cert.pem \
  -noout -subject -issuer -dates -ext subjectAltName -fingerprint -sha256

openssl x509 -in 2faskw.cert.pem -pubkey -noout | openssl sha256
openssl pkey -in 2faskw.key -pubout | openssl sha256
```

確認結果:

```text
2faskw.cert.pem: OK
certificate/private-key match: OK
```

### 12.2 バックアップ

HTTPS設定前のバックアップ:

```text
/opt/backups/https-20260604124505
```

バックアップディレクトリはrootのみ参照できる`0700`へ設定した。

### 12.3 PKCS#12作成と配置

秘密鍵、サーバ証明書、IIC CA証明書からJetty用PKCS#12を作成した。
PKCS#12パスワードはランダム生成し、本書および作業ログには記録していない。

配置先:

```text
/opt/jetty-base/etc/2faskw-keystore.p12
```

権限:

```text
-rw------- jetty:jetty /opt/jetty-base/etc/2faskw-keystore.p12
```

元の秘密鍵を配置した一時ディレクトリ`/home/user/https-stage`は、PKCS#12作成後に削除した。

サーバ自身がIIC CAを信頼できるよう、以下にもCA証明書を配置した。

```text
/etc/pki/ca-trust/source/anchors/private-ca.example.com.pem
```

適用コマンド:

```bash
sudo update-ca-trust
```

### 12.4 Jetty HTTPS設定

Jettyモジュール:

```bash
sudo -u jetty bash -lc '
export JETTY_HOME=/opt/jetty-home
export JETTY_BASE=/opt/jetty-base
cd "$JETTY_BASE"
java -jar "$JETTY_HOME/start.jar" --add-modules=https
'
```

主要設定:

```properties
jetty.ssl.host=0.0.0.0
jetty.ssl.port=443
jetty.ssl.sniRequired=true
jetty.ssl.sniHostCheck=true
jetty.ssl.stsMaxAgeSeconds=31536000
jetty.ssl.stsIncludeSubdomains=false
jetty.sslContext.keyStorePath=/opt/jetty-base/etc/2faskw-keystore.p12
jetty.sslContext.keyStoreType=PKCS12
```

設定ファイル:

```text
/opt/jetty-base/start.d/ssl.ini
/opt/jetty-base/start.d/https.ini
/opt/jetty-base/start.d/http.ini
```

`ssl.ini`にはPKCS#12パスワードが含まれるため、権限を`0600 jetty:jetty`にしている。

HTTP 8080はローカル保守用として、ループバックのみに制限した。

```properties
jetty.http.host=127.0.0.1
```

systemdから443番へbindできるよう、`jetty-idp.service`へ以下を追加した。

```ini
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
AmbientCapabilities=CAP_NET_BIND_SERVICE
```

### 12.5 hostname・IdP設定・metadata

実行コマンド:

```bash
sudo hostnamectl set-hostname idp.example.com
```

IdP設定:

```properties
idp.entityID=https://idp.example.com/idp/shibboleth
idp.scope=example.com
idp.cookie.secure = true
```

`/opt/shibboleth-idp/metadata/idp-metadata.xml`についても、entityID、Scope、
SingleSignOnService、SingleLogoutServiceをFQDNへ更新した。

変更後に以下を実行した。

```bash
sudo -u jetty env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh
```

### 12.6 firewall

実行コマンド:

```bash
sudo firewall-cmd --add-service=https --permanent
sudo firewall-cmd --reload
```

許可サービス:

```text
cockpit dhcpv6-client https ssh
```

8080番はfirewalldで許可せず、Jettyも`127.0.0.1:8080`だけで待ち受ける。

### 12.7 HTTPS検証結果

待受状態:

```text
0.0.0.0:443
127.0.0.1:8080
```

外部FQDN確認:

```text
https://idp.example.com/idp/: HTTP 200
external HTTP 8080: connection refused
```

レスポンス確認:

```text
Strict-Transport-Security: max-age=31536000
Set-Cookie: __Host-JSESSIONID=...; Path=/; Secure; HttpOnly
```

ループバック経由の管理status確認:

```text
https://idp.example.com/idp/status: HTTP 200
```

外部ネットワークからの`/idp/status`はIdPのアクセス制御によりHTTP 403となる。
これは管理エンドポイントを外部公開しない正常な状態である。

証明書チェーン確認:

```text
depth=1 CN=private-ca.example.com
verify return:1
depth=0 CN=idp.example.com
verify return:1
```

PostgreSQLとJetty IdPの再起動後も、以下を再確認した。

```text
postgresql-18.service: enabled / active
jetty-idp.service: enabled / active
HTTPS /idp/: HTTP 200
HTTPS /idp/status from loopback: HTTP 200
```

### 12.8 クライアント側のCA信頼

この証明書は公開認証局ではなく`private-ca.example.com`によって発行されている。
クライアントOSまたはブラウザがIIC CAを信頼していない場合、証明書警告が表示される。

利用端末へ以下のCA証明書を信頼済みルートCAとして配布する必要がある。

```text
iic_ca.ca.cert.pem
```

CAを信頼していない一般クライアントからの確認では、以下のエラーになることを確認した。

```text
SSL certificate problem: self signed certificate in certificate chain
```

## 13. LDAP認証設定

DATE_REDACTEDに、既存PoCサーバー`192.168.81.174`のLDAP Password認証設定を
`2faskw`へ移植した。

### 13.1 バックアップ

`2faskw`:

```text
/opt/backups/ldap-sp-20260604160251
```

### 13.2 LDAP Password認証設定

LDAP接続設定:

```properties
idp.authn.LDAP.authenticator = bindSearchAuthenticator
idp.authn.LDAP.ldapURL = ldap://192.168.245.38:389
idp.authn.LDAP.useStartTLS = false
idp.authn.LDAP.baseDN = ou=People,dc=example-u,dc=ac,dc=jp
idp.authn.LDAP.subtreeSearch = false
idp.authn.LDAP.userFilter = (cn={user})
idp.authn.LDAP.bindDN = cn=Checker,dc=example-u,dc=ac,dc=jp
```

変更ファイル:

```text
/opt/shibboleth-idp/conf/ldap.properties
/opt/shibboleth-idp/credentials/secrets.properties
```

`secrets.properties`はファイル全体を上書きせず、以下のLDAP資格情報2項目だけを
`192.168.81.174`から移植した。値は作業ログおよび本書には記録していない。

```properties
idp.authn.LDAP.bindDNCredential
idp.attribute.resolver.LDAP.bindDNCredential
```

LDAP疎通確認用として以下を追加した。

```bash
sudo dnf -y install openldap-clients
```

確認結果:

```text
LDAP server TCP/389 connection: OK
LDAP anonymous rootDSE query: OK
LDAP Checker bind: FAIL - Invalid credentials (49)
```

`2faskw`へ移植した資格情報は、`192.168.81.174`上の値と文字数・SHA-256が一致している。
そのため転送不備ではなく、保存済みChecker資格情報の失効・変更、またはLDAP側ポリシーを
確認する必要がある。LDAP実ユーザーのログイン試験は、有効なCheckerパスワードへ更新後に行う。

### 13.3 SP設定の取り消し

当初はSimpleSAMLphp SP metadataも移植したが、現段階では不要のため取り消した。

`2faskw`で取り消した内容:

```text
LocalSPTest MetadataProvider: 削除
/opt/shibboleth-idp/metadata/sp-test.xml: 削除
relying-party.xmlのPoC用Assertion暗号化無効化: 追加前へ復元
```

`192.168.81.174`のSimpleSAMLphp設定も、`2faskw`登録前の状態へ復元した。

取り消し前バックアップ:

```text
2faskw: /opt/backups/pre-sp-removal-20260604161643
192.168.81.174: /opt/backups/pre-2faskw-sp-removal-20260604161643
```

### 13.4 再構築・検証結果

実行:

```bash
sudo -u jetty env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh

sudo systemctl restart jetty-idp
```

確認結果:

```text
jetty-idp.service: active
HTTPS /idp/: HTTP 200
HTTPS /idp/status from loopback: HTTP 200
LocalSPTest MetadataProvider count: 0
/opt/shibboleth-idp/metadata/sp-test.xml: removed
LDAP URL: ldap://192.168.245.38:389
LDAP authenticator: bindSearchAuthenticator
LDAP credential properties: 2
LDAP real-user login: not tested because Checker bind is rejected
```

## 14. TOTP・WebAuthn Plugin設定

DATE_REDACTEDに、Shibboleth公式PluginレジストリからTOTPとWebAuthn Pluginを
インストールした。

### 14.1 導入Plugin

```text
net.shibboleth.idp.plugin.authn.totp: 2.3.1
net.shibboleth.idp.plugin.authn.webauthn: 1.4.2
```

インストールコマンド:

```bash
sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/lib/jvm/java-21-openjdk/bin \
  /opt/shibboleth-idp/bin/plugin.sh -I net.shibboleth.idp.plugin.authn.totp

sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/lib/jvm/java-21-openjdk/bin \
  /opt/shibboleth-idp/bin/plugin.sh -I net.shibboleth.idp.plugin.authn.webauthn
```

初回インストール時に表示された署名鍵を確認して受け入れた。

```text
TOTP:
  Fingerprint: DCAA15007BED9DE690CD9523378B845402277962
  User: Scott Cantor <cantor.2@osu.edu>

WebAuthn:
  Fingerprint: B5B5DD332142AD657E8D87AC7D27E610B8A3DC52
  User: Philip David Smart <philip.smart@jisc.ac.uk>
```

導入前バックアップ:

```text
/opt/backups/totp-webauthn-plugin-20260604162315
```

### 14.2 WebAuthn設定

WebAuthnを`2faskw`のHTTPS FQDNで使用できるように設定した。

```properties
idp.authn.webauthn.relyingPartyId = idp.example.com
idp.authn.webauthn.relyingPartyName = Shinshu IdP 2faskw
idp.authn.webauthn.2fa.enabled = true
idp.authn.webauthn.2fa.allowedPreviousFactors = authn/Password
idp.authn.webauthn.admin.registration.accessPolicy = AccessByCurrentUser
```

変更ファイル:

```text
/opt/shibboleth-idp/conf/authn/webauthn.properties
/opt/shibboleth-idp/conf/authn/webauthn-registration.properties
/opt/shibboleth-idp/conf/access-control.xml
```

設定変更前バックアップ:

```text
/opt/backups/webauthn-config-20260604162638
```

WebAuthn credential登録URL:

```text
https://idp.example.com/idp/profile/admin/webauthn-registration
```

### 14.3 WAR再構築

Plugin JARは公式Plugin管理ツールによって`0640 root:root`で配置される。
そのためPlugin導入後のWAR再構築は、`jetty`ユーザーではなくrootで実行した。

```bash
sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh

sudo systemctl restart jetty-idp
```

### 14.4 検証結果

```text
Plugin: net.shibboleth.idp.plugin.authn.totp Current Version: 2.3.1
Plugin: net.shibboleth.idp.plugin.authn.webauthn Current Version: 1.4.2
Module: idp.authn.TOTP [ENABLED]
Module: idp.authn.WebAuthn [ENABLED]
jetty-idp.service: active
HTTPS /idp/: HTTP 200
HTTPS /idp/status from loopback: HTTP 200
WebAuthn registration URL: HTTP 302, authentication flow started
Current startup ERROR: none
```

### 14.5 残作業・注意事項

- LDAP Checker資格情報が拒否されているため、WebAuthn credentialの実登録は未試験。
- TOTP seed供給はGraphicalMatrix DB参照方式へ設定したが、実ユーザーでの登録・認証は未試験。
- DATE_REDACTEDにWebAuthn credential保存先をGraphicalMatrix DB上のJDBC StorageServiceへ変更した。
- 既存のメモリ保存WebAuthn credentialはDBへ移行されないため、WebAuthn利用ユーザーは再登録が必要。
- DATE_REDACTEDにDB永続化後のWebAuthn登録・ログイン成功を確認し、JDBCAcceleratorも有効化した。

### 14.6 WebAuthn credentialをGraphicalMatrix DBへ保存する

目的:

```text
GraphicalMatrix MFA管理DBとWebAuthn credential保存DBを同じPostgreSQL DBに集約する。
DB名は既存の graphicalmatrix を継続利用する。
バックアップ、監視、HA、DB運用を一括化する。
```

参考:

- Shibboleth JDBC StorageService: https://shibboleth.atlassian.net/wiki/spaces/IDPPLUGINS/pages/2989096970/JDBCStorageService
- Shibboleth WebAuthn Credential Repository: https://shibboleth.atlassian.net/wiki/spaces/IDPPLUGINS/pages/3878322213/WebAuthnCredentialRepository

#### DATE_REDACTED 実装結果

導入前バックアップ:

```text
/opt/backups/webauthn-jdbc-storage-20260608093458
```

バックアップ対象:

```text
/opt/shibboleth-idp/conf/global.xml
/opt/shibboleth-idp/conf/authn/webauthn.properties
/opt/shibboleth-idp/conf/idp.properties
/opt/shibboleth-idp/conf/graphicalmatrix/
/opt/shibboleth-idp/edit-webapp/
PostgreSQL dump: graphicalmatrix.sql
```

追加導入Plugin:

```text
net.shibboleth.plugin.storage.jdbc: 2.1.0
```

実行コマンド:

```bash
sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin:/usr/sbin:/sbin \
  /opt/shibboleth-idp/bin/plugin.sh \
    --noPrompt \
    --truststore /opt/shibboleth-idp/credentials/net.shibboleth.idp.plugin.authn.webauthn/truststore.asc \
    -I net.shibboleth.plugin.storage.jdbc
```

署名鍵:

```text
Fingerprint: B5B5DD332142AD657E8D87AC7D27E610B8A3DC52
User: Philip David Smart <philip.smart@jisc.ac.uk>
```

JDBC StorageService Pluginの導入時は非対話環境だったため、既に導入済みの
WebAuthn Plugin truststoreを指定して署名鍵を検証した。

サーバ上の実行ログ:

```text
/home/user/install-logs/23-jdbc-storage-plugin.log
/home/user/install-logs/24-webauthn-storage-schema.log
/home/user/install-logs/25-webauthn-jdbc-storage-config.log
/home/user/install-logs/26-webauthn-jdbc-build-restart.log
```

現在のPlugin状態:

```text
WebAuthn Plugin: installed
JDBC StorageService Plugin: installed
idp.authn.webauthn.StorageService: GraphicalMatrixJDBCStorageService
WebAuthn credential persistence: JDBC-backed
```

#### 実DBスキーマ

PostgreSQLでは引用符なしの`StorageRecords`は小文字の`storagerecords`として作成される。
Shibboleth JDBC StorageServiceの標準SQLも引用符なしで参照するため、この状態で一致する。

実行DDL:

```sql
CREATE TABLE IF NOT EXISTS StorageRecords (
  context varchar(255) NOT NULL,
  id varchar(255) NOT NULL,
  expires bigint DEFAULT NULL,
  value text NOT NULL,
  version bigint NOT NULL,
  PRIMARY KEY (context, id)
);

ALTER TABLE StorageRecords OWNER TO graphicalmatrix_app;

CREATE INDEX IF NOT EXISTS idx_storage_records_expires
  ON StorageRecords (expires);

GRANT SELECT, INSERT, UPDATE, DELETE
  ON StorageRecords TO graphicalmatrix_app;
```

確認結果:

```text
Table: public.storagerecords
Columns: context, id, expires, value, version
Primary key: context, id
Index: idx_storage_records_expires
Current rows: 1 after WebAuthn credential registration test
graphicalmatrix_app: SELECT/INSERT/UPDATE/DELETE可能
```

DBアプリユーザーでの一時レコードINSERT/SELECT/DELETEも成功した。

#### IdP設定

`/opt/shibboleth-idp/conf/global.xml`へ以下を追加した。

```xml
<!-- BEGIN GraphicalMatrix WebAuthn JDBC StorageService -->
<!-- Store WebAuthn credentials in the existing GraphicalMatrix PostgreSQL database. -->
<bean id="GraphicalMatrixStorageDataSource"
      class="org.apache.commons.dbcp2.BasicDataSource"
      destroy-method="close"
      lazy-init="true"
      p:driverClassName="org.postgresql.Driver"
      p:url="jdbc:postgresql://127.0.0.1:5432/graphicalmatrix"
      p:username="graphicalmatrix_app"
      p:password="#{T(java.nio.file.Files).readString(T(java.nio.file.Path).of('/opt/shibboleth-idp/credentials/graphicalmatrix-db.password')).trim()}" />

<bean id="GraphicalMatrixJDBCStorageService"
      parent="shibboleth.JDBCStorageService"
      p:dataSource-ref="GraphicalMatrixStorageDataSource"
      p:cleanupInterval="%{idp.storage.cleanupInterval:PT10M}"
      p:transactionIsolation="4"
      p:retryableErrors="40001" />
<!-- END GraphicalMatrix WebAuthn JDBC StorageService -->
```

`/opt/shibboleth-idp/conf/authn/webauthn.properties`へ以下を追加した。

```properties
idp.authn.webauthn.StorageService = GraphicalMatrixJDBCStorageService
```

WAR再構築とJetty再起動:

```bash
sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh

sudo systemctl restart jetty-idp.service
```

確認結果:

```text
Plugin: net.shibboleth.plugin.storage.jdbc Current Version: 2.1.0
jetty-idp.service: active
HTTPS /idp/: HTTP 200
/idp/graphicalmatrix/change: HTTP 200
WebAuthn registration URL: HTTP 302, authentication flow started
idp.authn.webauthn.StorageService='GraphicalMatrixJDBCStorageService' loaded
GraphicalMatrixJDBCStorageService bean created
GraphicalMatrixStorageDataSource bean created
```

`/idp/status`はサーバ自身の送信元IPが`192.168.81.60`として扱われ、
`AccessByIPAddress`でHTTP 403になった。これは管理エンドポイントのアクセス制御であり、
WebAuthn DB保存設定の起動エラーではない。

注意:

- `storagerecords`はWebAuthn credential登録後に作成される。DATE_REDACTED時点では1件保存済み。
- 以前のデフォルトStorageService上のcredentialはDBへ自動移行されない。
- `mfa_method=WebAuthn`のユーザーでDB credentialが無い場合は、再登録または一時的な`set-method GraphicalMatrix`が必要。
- 起動ログにはWebAuthn Plugin由来の`non-clustered storage service`注意ログが残る。credential保存先はJDBCへ切替済みだが、WebAuthnのロックアウト/補助状態の扱いは追加検証対象とする。

#### WebAuthn DB保存後の実ログイン確認

DATE_REDACTED 10:09頃、`test-user001@example.com`でWebAuthn credential登録と
WebAuthn認証によるSPログイン成功を確認した。

確認ログ:

```text
ValidateAuthenticatorAttestationResponse: Public key registration was valid
StorePublicKeyCredential: Added public key credential registration for user 'test-user001@example.com'
ValidateWebAuthnAssertion: WebAuthn authentication succeeded for 'test-user001@example.com'
Shibboleth-Audit.SSO: ... https://sp.example.com/simplesaml/sp ... Success
Shibboleth-Audit.Logout: ... Success
```

DB確認:

```sql
SELECT context, id, version, length(value) AS value_length
FROM storagerecords
ORDER BY context, id;
```

確認結果:

```text
context: net.shibboleth.idp.plugin.authn.webauthn
id: test-user001@example.com
version: 1
value_length: 699
```

これにより、WebAuthn credentialがGraphicalMatrix DB側へ保存され、
IdP再起動後も参照可能な状態になった。

#### DATE_REDACTED JDBCAccelerator有効化

導入前バックアップ:

```text
/opt/backups/webauthn-jdbc-accelerator-20260608101159
```

サーバ上の実行ログ:

```text
/home/user/install-logs/27-webauthn-jdbc-accelerator.log
```

`/opt/shibboleth-idp/conf/global.xml`へ以下を追加した。

```xml
<!-- BEGIN GraphicalMatrix WebAuthn JDBC Accelerator -->
<!-- Speeds WebAuthn credential lookup while using the same GraphicalMatrix PostgreSQL DataSource. -->
<bean id="WebAuthnJDBCAccelerator"
      parent="shibboleth.authn.WebAuthn.JDBCAccelerator"
      p:dataSource-ref="GraphicalMatrixStorageDataSource"
      p:transactionIsolation="4"
      p:retryableErrors="40001" />
<!-- END GraphicalMatrix WebAuthn JDBC Accelerator -->
```

`/opt/shibboleth-idp/conf/authn/webauthn.properties`で以下を有効化した。

```properties
idp.authn.webauthn.StorageService.cache.enable = true
idp.authn.webauthn.StorageService.cache.expireAfterAccess = PT60M
idp.authn.webauthn.StorageService.jdbcAccelerator = WebAuthnJDBCAccelerator
idp.authn.webauthn.StorageService.jdbcAccelerator.defaultType = QUERY
```

WAR再構築とJetty再起動:

```bash
sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh

sudo systemctl restart jetty-idp.service
```

確認結果:

```text
jetty-idp.service: active
HTTPS /idp/: HTTP 200
WebAuthn registration URL: HTTP 302, authentication flow started
storagerecords: 1 row
Loaded property 'idp.authn.webauthn.StorageService.jdbcAccelerator'='WebAuthnJDBCAccelerator'
Creating shared instance of singleton bean 'WebAuthnJDBCAccelerator'
```

JDBCAccelerator有効化後のWebAuthnログイン再試験:

```text
DATE_REDACTED 10:14:56 MFA method decision: method=WEBAUTHN, flow=authn/WebAuthn
DATE_REDACTED 10:15:03 ValidateWebAuthnAssertion: WebAuthn authentication succeeded for 'test-user001@example.com'
DATE_REDACTED 10:15:03 Shibboleth-Audit.SSO: ... https://sp.example.com/simplesaml/sp ... Success
DATE_REDACTED 10:15:06 Shibboleth-Audit.Logout: ... Success
```

確認結果:

```text
jetty-idp.service: active
storagerecords: 1 row
id: test-user001@example.com
value_length: 699
```

これにより、WebAuthn credentialのDB永続化とJDBCAccelerator有効化後の認証成功を確認済み。

#### 推奨DB構成

同じDBに集約するが、GraphicalMatrix独自テーブルとは用途を分ける。

```text
Database: graphicalmatrix
Role: graphicalmatrix_app

既存:
  public.graphicalmatrix_enrollment

追加:
  public.StorageRecords
```

理由:

- `graphicalmatrix` DBだけをバックアップすれば、GraphicalMatrix設定情報とWebAuthn credentialをまとめて保護できる。
- DB接続先、死活監視、HA設計を一本化できる。
- Shibboleth JDBC StorageServiceの標準SQLが`StorageRecords`を前提にしているため、実装差分を小さくできる。
- `context`列でWebAuthn用レコードと他用途のStorageServiceレコードを分離できる。

参考DDL:

```sql
CREATE TABLE IF NOT EXISTS StorageRecords (
  context varchar(255) NOT NULL,
  id varchar(255) NOT NULL,
  expires bigint DEFAULT NULL,
  value text NOT NULL,
  version bigint NOT NULL,
  PRIMARY KEY (context, id)
);

CREATE INDEX IF NOT EXISTS idx_storage_records_expires
  ON StorageRecords (expires);

GRANT SELECT, INSERT, UPDATE, DELETE
  ON StorageRecords TO graphicalmatrix_app;
```

`context`と`id`は大文字小文字を区別して比較される必要がある。
PostgreSQLの通常の`varchar`比較はこの要件を満たす。

#### 設計時メモ: IdP設定

JDBC StorageService Pluginの標準導入コマンド:

```bash
sudo /opt/shibboleth-idp/bin/plugin.sh -I net.shibboleth.plugin.storage.jdbc
```

非対話環境では署名鍵の確認入力ができないため、実装時は`--noPrompt`と
既存truststoreを指定して導入した。

JDBC driverはGraphicalMatrix用に配置済み。

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/postgresql-42.7.11.jar
```

JDBC StorageService Pluginを追加した場合は、通常どおりWAR再構築が必要。

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

DataSource / StorageService beanの基本設計:

```xml
<!-- conf/global.xml または専用XMLから読み込む -->
<bean id="GraphicalMatrixStorageDataSource"
      class="org.apache.commons.dbcp2.BasicDataSource"
      destroy-method="close"
      lazy-init="true"
      p:driverClassName="org.postgresql.Driver"
      p:url="jdbc:postgresql://127.0.0.1:5432/graphicalmatrix"
      p:username="graphicalmatrix_app"
      p:password="DB_PASSWORD_OR_PROPERTY_REFERENCE" />

<bean id="GraphicalMatrixJDBCStorageService"
      parent="shibboleth.JDBCStorageService"
      p:dataSource-ref="GraphicalMatrixStorageDataSource"
      p:cleanupInterval="%{idp.storage.cleanupInterval:PT10M}"
      p:transactionIsolation="4"
      p:retryableErrors="40001" />
```

パスワードは既存の以下と二重管理しない方針とする。

```text
/opt/shibboleth-idp/credentials/graphicalmatrix-db.password
/opt/shibboleth-idp/conf/graphicalmatrix/db.properties
```

今回の実装では、パスワード値をpropertiesへ複製せず、
`/opt/shibboleth-idp/credentials/graphicalmatrix-db.password`をSpring式で直接読む方式にした。
将来、plugin化時は専用設定ローダーまたは標準property placeholderへ寄せる。

WebAuthn設定:

```properties
# conf/authn/webauthn.properties
idp.authn.webauthn.StorageService = GraphicalMatrixJDBCStorageService
```

#### JDBCAccelerator設計

PostgreSQL 18を使用しているため、WebAuthn PluginのJDBCAccelerator利用条件である
PostgreSQL 17以上を満たす。

追加bean案:

```xml
<bean id="WebAuthnJDBCAccelerator"
      parent="shibboleth.authn.WebAuthn.JDBCAccelerator"
      p:dataSource-ref="GraphicalMatrixStorageDataSource" />
```

WebAuthn設定:

```properties
idp.authn.webauthn.StorageService.jdbcAccelerator = WebAuthnJDBCAccelerator
idp.authn.webauthn.StorageService.jdbcAccelerator.defaultType = QUERY
idp.authn.webauthn.StorageService.cache.enable = true
idp.authn.webauthn.StorageService.cache.expireAfterAccess = PT60M
```

JDBCAcceleratorはcredentialIdやuserHandle検索をDB向けに高速化するための補助機能である。
PoCではStorageServiceのDB永続化を確認後、DATE_REDACTEDにJDBCAcceleratorを有効化した。

#### 実装・検証ステータス

1. 現行DBをバックアップする。実施済み。
2. JDBC StorageService Pluginを導入する。実施済み。
3. `graphicalmatrix` DBへ`StorageRecords`テーブルを追加する。実施済み。
4. `GraphicalMatrixStorageDataSource`と`GraphicalMatrixJDBCStorageService` beanを追加する。実施済み。
5. `idp.authn.webauthn.StorageService = GraphicalMatrixJDBCStorageService`を設定する。実施済み。
6. `build.sh`、Jetty再起動を行う。実施済み。
7. WebAuthn credentialを新規登録する。未実施、ユーザー操作が必要。
8. `StorageRecords`にWebAuthn credentialレコードが作成されることを確認する。未実施。
9. IdP再起動後もWebAuthn認証できることを確認する。未実施。
10. JDBCAcceleratorを有効化する。実施済み。
11. JDBCAccelerator有効化後に、再度WebAuthnログイン成功を確認する。未実施。

確認SQL:

```sql
SELECT context, id, expires, version, length(value) AS value_length
FROM StorageRecords
ORDER BY context, id;
```

credential本体は`value`列にJSONとして保存される想定である。
公開鍵情報やcredentialId等を含むため、DBバックアップとSQL参照権限は機密情報として扱う。

#### 運用上の判断

この構成は「DB管理をGraphicalMatrixと一体化したい」という運用方針に合う。
ただし、同じDB/同じroleに集約すると、GraphicalMatrixアプリケーション権限で
WebAuthn credential保存領域も読み書きできるため、権限分離は弱くなる。

運用簡素化を優先するPoC/小規模構成:

```text
DB: graphicalmatrix
Role: graphicalmatrix_app
Tables: graphicalmatrix_enrollment, StorageRecords
```

権限分離を少し残す本番寄り構成:

```text
DB: graphicalmatrix
Role: graphicalmatrix_app       -> graphicalmatrix_enrollment
Role: idp_storage_app       -> StorageRecords
```

どちらも「DBは1つ」で管理できる。
本番で監査・権限分離を重視する場合は、同一DB内でroleだけ分ける案を推奨する。

#### GraphicalMatrix管理CLIによるWebAuthn credential管理

目的:

```text
graphicalmatrix-db.sh で管理しているユーザーIDを基準に、
不要になったWebAuthn credentialも同じ管理導線で削除できるようにする。
```

実装状態:

```text
DATE_REDACTED実装済み。
/opt/shibboleth-idp/bin/graphicalmatrix-db.sh に webauthn-list / webauthn-reset /
webauthn-delete を追加した。
WebAuthn credential管理コマンドはPostgreSQLのstoragerecordsを対象にする。
```

導入前バックアップ:

```text
/opt/backups/graphicalmatrix-db-webauthn-cli-20260608102342
```

実装コマンド:

```bash
# WebAuthn credentialを全件表示
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-list

# 対象ユーザーのWebAuthn credentialを表示
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-list USER

# 対象ユーザーのWebAuthn credentialを空にするdry-run
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-reset USER

# 確認後にRESETを実行し、再登録待ちに戻す
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-reset USER --apply

# RESET時にMFA方式もGraphicalMatrixへ戻す
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-reset USER --set-method GraphicalMatrix --apply

# 特定credentialだけ削除するdry-run
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-delete USER --credential-id CREDENTIAL_ID

# 特定credentialだけ削除する
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-delete USER --credential-id CREDENTIAL_ID --apply

# GraphicalMatrix管理ユーザー削除時にWebAuthn credentialも削除する場合
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh delete USER --with-webauthn --apply
```

既存の`delete USER`は、初期状態では`graphicalmatrix_enrollment`だけを削除する。
WebAuthn credentialまで消す場合は、事故防止のため`--with-webauthn --apply`を要求する。

RESETとDELETEの意味:

```text
webauthn-reset USER:
  ユーザー管理レコードは残す。
  storagerecords側のWebAuthn credential値を空JSON配列 [] にする。
  ユーザーは再度WebAuthn credentialを登録できる状態になる。

webauthn-delete USER --credential-id ...:
  指定credentialだけを削除する。
  他credentialやGraphicalMatrix管理レコードは残す。

delete USER --with-webauthn:
  GraphicalMatrix管理レコードとWebAuthn credentialを両方削除する。
  退職・アカウント廃止・完全削除向け。
```

削除対象:

```text
Table: StorageRecords
Column: value
Format: WebAuthn credential JSON array
Match:
  value[*].username
  value[*].userIdentity.name
  value[*].userIdentity.id
```

WebAuthn credentialの保存例では、`value`はJSON配列で、各要素に
`username`、`userIdentity`、`credential.credentialId`、`credential.userHandle`等が入る。
ユーザー単位削除では、`username`または`userIdentity.name`が対象ユーザーIDと一致する
credential要素を削除する。

RESET / DELETE ロジック:

```text
1. storagerecordsからWebAuthn credential JSONレコードを抽出する。
2. value::jsonb を配列として展開する。
3. storage id / username / userIdentity.name / userIdentity.id が USER と一致する要素を抽出する。
4. dry-runでは credentialId, nickname, registrationTime, signature_count等を表示する。
5. webauthn-reset --apply時は、該当ユーザーのcredential配列を空にする。
6. webauthn-delete --apply時は、指定credential要素だけを除外する。
7. delete USER --with-webauthn --apply時だけ、空になったStorageRecords行をDELETE対象にする。
8. 配列に他ユーザーのcredentialが残る場合は、valueを残要素だけでUPDATEする。
9. 変更件数と対象credential件数を標準出力へ表示する。
```

実装検証:

```text
webauthn-list:
  既存の test-user001@example.com credentialを表示できることを確認。

webauthn-reset USER:
  dry-runで対象credentialを表示し、DB変更しないことを確認。

webauthn-delete USER --credential-id CREDENTIAL_ID --apply:
  テスト用一時credential 2件のうち1件だけ削除されることを確認。

webauthn-reset USER --apply:
  テスト用一時credentialが空JSON配列 [] になることを確認。

delete USER --with-webauthn:
  --applyなしではエラーになることを確認。

delete USER --with-webauthn --apply:
  テスト用GraphicalMatrix管理ユーザーとテスト用WebAuthn credentialが両方削除されることを確認。
```

確認SQL案:

```sql
SELECT
  context,
  id,
  elem ->> 'username' AS username,
  elem -> 'userIdentity' ->> 'name' AS user_identity_name,
  elem -> 'credential' ->> 'credentialId' AS credential_id,
  elem -> 'credential' ->> 'userHandle' AS user_handle,
  elem ->> 'nickname' AS nickname,
  elem ->> 'registrationTime' AS registration_time
FROM StorageRecords
CROSS JOIN LATERAL jsonb_array_elements(value::jsonb) AS elem
WHERE elem ->> 'username' = :user
   OR elem -> 'userIdentity' ->> 'name' = :user
   OR elem -> 'userIdentity' ->> 'id' = :user
ORDER BY context, id;
```

RESET / DELETE SQLは実装時に検証する。
RESETは再登録を可能にするため、対象ユーザーのStorageRecords行を原則として残し、
`value`を空のJSON配列`[]`へ更新する。
DELETEは完全削除用途のため、対象ユーザーのcredential要素を除外し、空になった行は削除する。

RESET擬似SQL:

```sql
BEGIN;

WITH target_rows AS (
  SELECT DISTINCT
    s.context,
    s.id
  FROM StorageRecords s
  CROSS JOIN LATERAL jsonb_array_elements(s.value::jsonb) AS elem
  WHERE elem ->> 'username' = :user
     OR elem -> 'userIdentity' ->> 'name' = :user
     OR elem -> 'userIdentity' ->> 'id' = :user
)
UPDATE StorageRecords s
SET value = '[]',
    version = s.version + 1
FROM target_rows t
WHERE s.context = t.context
  AND s.id = t.id;

COMMIT;
```

DELETE擬似SQL:

```sql
BEGIN;

WITH expanded AS (
  SELECT
    context,
    id,
    expires,
    version,
    elem
  FROM StorageRecords
  CROSS JOIN LATERAL jsonb_array_elements(value::jsonb) AS elem
),
remaining AS (
  SELECT
    context,
    id,
    jsonb_agg(elem) AS new_value,
    count(*) AS remain_count
  FROM expanded
  WHERE NOT (
       elem ->> 'username' = :user
    OR elem -> 'userIdentity' ->> 'name' = :user
    OR elem -> 'userIdentity' ->> 'id' = :user
  )
  GROUP BY context, id
),
target_rows AS (
  SELECT DISTINCT context, id
  FROM expanded
  WHERE elem ->> 'username' = :user
     OR elem -> 'userIdentity' ->> 'name' = :user
     OR elem -> 'userIdentity' ->> 'id' = :user
)
UPDATE StorageRecords s
SET value = remaining.new_value::text,
    version = s.version + 1
FROM remaining
WHERE s.context = remaining.context
  AND s.id = remaining.id
  AND EXISTS (
    SELECT 1 FROM target_rows t
    WHERE t.context = s.context AND t.id = s.id
  );

DELETE FROM StorageRecords s
WHERE EXISTS (
  SELECT 1 FROM target_rows t
  WHERE t.context = s.context AND t.id = s.id
)
AND NOT EXISTS (
  SELECT 1 FROM remaining r
  WHERE r.context = s.context AND r.id = s.id
);

COMMIT;
```

実装時の注意:

- `StorageRecords.value`が必ずJSON配列である前提を置かず、dry-runで形式チェックする。
- JSON parse不可の行は削除対象にせず、エラーとして表示する。
- `USER`一致は完全一致を基本とする。部分一致やLIKEは使わない。
- `test-user001`と`test-user001@example.com`は別ユーザーとして扱う。
- 必要なら`--alias USER2`を追加し、複数IDを同時に削除できるようにする。
- `webauthn-reset`は値を空にするだけで、GraphicalMatrix管理ユーザーは削除しない。
- `webauthn-reset`後の再登録導線は、WebAuthn登録URLまたはMFA方式変更画面から行う。
- `mfa_method=WebAuthn`のユーザーでcredentialをRESETした場合、通常ログイン不能を避けるため
  `--set-method GraphicalMatrix`を同時指定できるようにする。

例:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-reset USER \
  --set-method GraphicalMatrix \
  --apply
```

RESET後の推奨動作:

```text
1. WebAuthn credential値を空にする
2. mfa_methodをGraphicalMatrixへ戻す
3. totp_statusやtotp_seedは変更しない
4. failed_count/locked_untilは変更しない
5. 変更件数を標準出力へ表示する
```

将来、CLI操作を監査ログへ出す場合のログ例:

```text
ts=... event=WEBAUTHN_CREDENTIAL_RESET user=USER result=OK ip=cli session=- challenge=- detail=count=2,set_method=GraphicalMatrix
```

## 15. GraphicalMatrix Plugin設定

DATE_REDACTEDに、PoC用GraphicalMatrix Plugin配布物を`2faskw`へインストールした。
DATE_REDACTEDにリモートの実ログと現行設定を読み取り専用で再確認し、本節へ反映した。

### 15.1 配布物とバックアップ

配布物:

```text
/home/user/install-media/2faskw-idp-plugin-0.1.0-SNAPSHOT.zip
/opt/src/2faskw-idp-plugin-0.1.0-SNAPSHOT
SHA-256: af5ca0eae490ae89ad04a771013f76e8c6ee058c3b002fb9ad8c514059344715
```

確認コマンド:

```bash
sha256sum /home/user/install-media/2faskw-idp-plugin-0.1.0-SNAPSHOT.zip
sudo find /opt/src/2faskw-idp-plugin-0.1.0-SNAPSHOT -maxdepth 2 \
  \( -name 'PACKAGE-MANIFEST.sha256' -o -name '*check*' -o -name 'README*' -o -name 'INSTALL*' \) \
  -print | sort
```

配布物の`PACKAGE-MANIFEST.sha256`とpackage checkを実行し、以下を確認した。

```text
summary: failures=0 warnings=0
```

適用前の完全バックアップ:

```text
/opt/backups/graphicalmatrix-plugin-20260604163625
```

`/opt/backups/graphicalmatrix-plugin-20260604163601`は、存在しない`modules`ディレクトリを
対象にしたため途中停止した未完了バックアップである。

### 15.2 PluginファイルとIdP設定

`edit-webapp/WEB-INF/web.xml`が未作成だったため、IdP標準の以下を編集用overlayへ複製した。

```bash
sudo install -d -o jetty -g jetty -m 0755 \
  /opt/shibboleth-idp/edit-webapp/WEB-INF

sudo install -o jetty -g jetty -m 0644 \
  /opt/shibboleth-idp/dist/webapp/WEB-INF/web.xml \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml
```

PluginファイルとServlet mappingを適用した。

```bash
cd /opt/src/2faskw-idp-plugin-0.1.0-SNAPSHOT

sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /opt/src/2faskw-idp-plugin-0.1.0-SNAPSHOT \
  --apply

sudo ./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --install \
  --apply
```

適用された主なファイル:

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/core-3.5.3.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-0.1.0-SNAPSHOT.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/postgresql-42.7.11.jar
/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
/opt/shibboleth-idp/conf/graphicalmatrix/db.properties
/opt/shibboleth-idp/conf/graphicalmatrix/api.properties
/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
/opt/shibboleth-idp/conf/graphicalmatrix/postgresql-schema.sql
/opt/shibboleth-idp/conf/graphicalmatrix/views/*.html
/opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css
/opt/shibboleth-idp/edit-webapp/graphicalmatrix/graphicals/img01.svg - img25.svg
/opt/shibboleth-idp/bin/graphicalmatrix-db.sh
/opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh
/opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh
/opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh
```

`web.xml`へ追加されたServlet mapping:

```text
/graphicalmatrix/start
/graphicalmatrix/verify
/graphicalmatrix/change
/graphicalmatrix/graphical
/graphicalmatrix/assets/*
/graphicalmatrix-admin/api/v1/*
```

確認コマンド:

```bash
sudo grep -n -A4 -B2 'GraphicalMatrix' /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml
sudo ls -l /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-0.1.0-SNAPSHOT.jar
sudo find /opt/shibboleth-idp/edit-webapp/graphicalmatrix/graphicals -maxdepth 1 -type f -printf '%f\n' | sort
sudo find /opt/shibboleth-idp/conf/graphicalmatrix/views -maxdepth 1 -type f -printf '%f %s bytes\n' | sort
```

MFAフロー設定:

```properties
idp.authn.flows = MFA
idp.authn.External.externalAuthnPath = contextRelative:/graphicalmatrix/start
```

適用した認証設定:

```text
/opt/shibboleth-idp/conf/authn/mfa-authn-config.xml
  Password成功後にDBのmfa_methodとSP/IPポリシーでMFA方式を判定

/opt/shibboleth-idp/conf/authn/totp-authn-config.xml
  GraphicalMatrix DBからTOTP seedを取得
```

確認コマンド:

```bash
sudo grep -nE 'idp.authn.flows|idp.authn.External.externalAuthnPath' \
  /opt/shibboleth-idp/conf/authn/authn.properties

sudo grep -nE 'GraphicalMatrix|graphicalmatrix|mfa_method|TOTP|WebAuthn|Password' \
  /opt/shibboleth-idp/conf/authn/mfa-authn-config.xml

sudo grep -nE 'graphicalmatrix|TOTP|seed|DataSource|jdbc' \
  /opt/shibboleth-idp/conf/authn/totp-authn-config.xml
```

管理APIは初期状態では無効とした。

```properties
graphicalmatrix.api.enabled = false
```

API無効時でも設定ローダーがtokenファイルを参照するため、空のtokenファイルを
安全な権限で作成した。APIは有効化されない。

```text
0640 root:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

### 15.3 GraphicalMatrix用PostgreSQL

作成したDB:

```text
Database: graphicalmatrix
Role: graphicalmatrix_app
Table: graphicalmatrix_enrollment
```

DBパスワードはランダム生成し、以下へ保存した。値は作業ログと本書には記録していない。

```text
0640 root:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
0640 root:jetty /opt/shibboleth-idp/conf/graphicalmatrix/db.properties
```

スキーマ適用:

```bash
PGPASSWORD="<graphicalmatrix_app password>" \
  /usr/pgsql-18/bin/psql \
  -v ON_ERROR_STOP=1 \
  -h 127.0.0.1 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -f /opt/shibboleth-idp/conf/graphicalmatrix/postgresql-schema.sql
```

管理CLIからPostgreSQLを参照できるよう、以下を配置した。

```text
/usr/local/bin/psql -> /usr/pgsql-18/bin/psql
```

現行DB接続設定:

```text
graphicalmatrix.db.driver=org.postgresql.Driver
graphicalmatrix.db.url=jdbc:postgresql://127.0.0.1:5432/graphicalmatrix
graphicalmatrix.db.user=graphicalmatrix_app
```

確認コマンド:

```bash
sudo grep -nEv '^(#|$)|password|token|secret|pepper|key' \
  /opt/shibboleth-idp/conf/graphicalmatrix/db.properties

sudo -u postgres /usr/pgsql-18/bin/psql -Atqc '\l graphicalmatrix'
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc '\dt graphicalmatrix.*'
```

### 15.4 WAR再構築と検証結果

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

検証結果:

```text
Plugin: io.github.yasakawa.faskw.authn.graphicalmatrix Current Version: 0.1.0
Module: idp.authn.GraphicalMatrix [ENABLED]
Module: idp.authn.MFA [ENABLED]
Module: idp.authn.TOTP [ENABLED]
Module: idp.authn.WebAuthn [ENABLED]

jetty-idp.service: enabled / active
postgresql-18.service: enabled / active
HTTPS /idp/: HTTP 200
HTTPS /idp/status: HTTP 200
HTTPS /idp/graphicalmatrix/change: HTTP 200
HTTPS /idp/graphicalmatrix/assets/graphicalmatrix.css: HTTP 200
HTTPS /idp/graphicalmatrix-admin/api/v1/health: HTTP 404
GraphicalMatrix package check: failures=0 / warnings=0
Current startup ERROR: none
```

現在の2faskwはJettyが`0.0.0.0:443`と`127.0.0.1:8080`を直接待受している。
Apache/nginxは未使用。

確認コマンド:

```bash
sudo ss -lntp
systemctl is-active httpd nginx jetty-idp.service postgresql-18.service

curl -k -o /dev/null -w 'idp=%{http_code}\n' \
  https://idp.example.com/idp/
curl -k -o /dev/null -w 'change=%{http_code}\n' \
  https://idp.example.com/idp/graphicalmatrix/change
curl -k -o /dev/null -w 'css=%{http_code}\n' \
  https://idp.example.com/idp/graphicalmatrix/assets/graphicalmatrix.css
curl -k -o /dev/null -w 'api=%{http_code}\n' \
  https://idp.example.com/idp/graphicalmatrix-admin/api/v1/health
```

管理CLI:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh sequence-mode
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
```

確認結果:

```text
sequence storage: plaintext
graphicalmatrix_enrollment: 0 users
```

上記の`0 users`は、DATE_REDACTED 16:41時点の導入直後ログである。
DATE_REDACTED確認時点では、`graphicalmatrix-db.sh list`で4ユーザーが登録済みである。
方式の内訳は`GraphicalMatrix`、`TOTP`、`WebAuthn`を含む。
sequence値はMFA秘密情報に近いため、本書には現行値を記載しない。

PoC初期値の`plaintext`は検証用である。本番導入前に`hash + salt + pepper`方式へ移行する。

### 15.5 GraphicalMatrix設定値

```text
graphicalmatrix.columns = 5
graphicalmatrix.rows = 5
graphicalmatrix.place = /opt/shibboleth-idp/edit-webapp/graphicalmatrix/graphicals
graphicalmatrix.graphicals = img01-25
graphicalmatrix.not_graphicals =
graphicalmatrix.choice = 4
graphicalmatrix.order = 1
graphicalmatrix.allow_duplicates = 0
graphicalmatrix.force_sequence_change = 1
graphicalmatrix.sequence.storage = plaintext
graphicalmatrix.view.template.enabled = true
graphicalmatrix.view.css.enabled = true
graphicalmatrix.view.css.cacheSeconds = 0
```

alias設定:

```text
A:img01,B:img02,C:img03,D:img04,E:img05,F:img06,G:img07,H:img08,I:img09,J:img10,K:img11,L:img12,M:img13,N:img14,O:img15,P:img16,R:img17,S:img18,T:img19,U:img20,V:img21,W:img22,X:img23,Y:img24,Z:img25
```

`Q`は`O`と紛らわしいため除外している。

MFAポリシー:

```text
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.bypassSPs =
graphicalmatrix.mfa.requiredSPs =
graphicalmatrix.mfa.bypassIPs =
graphicalmatrix.mfa.bypassCIDRs =
graphicalmatrix.mfa.useForwardedFor = false
```

確認コマンド:

```bash
sudo grep -nEv '^(#|$)|password|token|secret|pepper|key' \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties \
  /opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
```

### 15.6 リモート作業ログ

ログ所在:

```text
/home/user/install-logs/17-graphicalmatrix-plugin-config.log
/home/user/install-logs/18-graphicalmatrix-postgresql.log
/home/user/install-logs/19-graphicalmatrix-build-restart.log
/home/user/install-logs/20-graphicalmatrix-validation.log
/home/user/install-logs/21-graphicalmatrix-api-disabled-validation.log
/home/user/install-logs/22-graphicalmatrix-final-validation.log
```

確認コマンド:

```bash
ls -l /home/user/install-logs/*graphicalmatrix* \
  /home/user/install-logs/1[7-9]-* \
  /home/user/install-logs/2[0-2]-*
```

ログ要約:

```text
17-graphicalmatrix-plugin-config.log
  graphicalmatrix-plugin-config.sh --applyを実行。
  JAR、PostgreSQL driver、conf/graphicalmatrix、views、assets、
  img01.svgからimg25.svg、管理CLI/API補助CLIを配置。
  graphicalmatrix-plugin-webxml.shをdry-run後に--applyし、
  web.xmlへServlet mappingとAPI security constraintを追加。

18-graphicalmatrix-postgresql.log
  role=graphicalmatrix_app、database=graphicalmatrixを作成。
  graphicalmatrix_enrollmentテーブルと各種indexを確認。
  graphicalmatrix-db.passwordとdb.propertiesを0640 root:jettyで配置。

19-graphicalmatrix-build-restart.log
  overlay JARの権限を確認。
  /opt/shibboleth-idp/bin/build.shでidp.warを再構築。
  jetty-idp.serviceを再起動しactiveを確認。

20-graphicalmatrix-validation.log
  /idp/、/idp/status、/idp/graphicalmatrix/change、
  /idp/graphicalmatrix/assets/graphicalmatrix.cssを検証。
  PluginとModuleの有効化を確認。
  導入直後のDB CLIでは0行。
  API healthはこの時点でHTTP 500だったが、その後API無効化確認でHTTP 404へ整理。

21-graphicalmatrix-api-disabled-validation.log
  graphicalmatrix-api.tokenが0 byte、0640 root:jettyであることを確認。
  API healthはHTTP 404、レスポンスは{"error":"NOT_FOUND"}。

22-graphicalmatrix-final-validation.log
  jetty-idp.service/postgresql-18.serviceがenabled/active。
  /idp/、/idp/status、GraphicalMatrix変更画面、CSSがHTTP 200。
  API healthがHTTP 404。
  Plugin: io.github.yasakawa.faskw.authn.graphicalmatrix 0.1.0。
  Module: GraphicalMatrix/TOTP/WebAuthn/MFA/Password がENABLED。
  sequence storageはplaintext。
  導入直後DBは0行。
  current startup errorsは空。
  package checkはfailures=0 warnings=0。
```

### 15.7 現行稼働ログ・監査ログ

GraphicalMatrix監査ログ:

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

DATE_REDACTED確認時点で、以下のイベントを確認した。

```text
CHALLENGE_CREATED / VERIFY OK
CHANGE_LDAP_AUTH OK
CHANGE_VERIFY OK
CHANGE_SAVE OK
CHANGE_METHOD_SAVE mfa_method=TOTP
CHANGE_METHOD_SAVE mfa_method=WebAuthn
TOTP_REGISTER_START
TOTP_REGISTER_CANCEL
API_DENIED result=DISABLED
```

確認コマンド:

```bash
sudo tail -80 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

Jettyログでは、終了済みflowを再参照した場合に
`NoSuchFlowExecutionException`が出ている。これはブラウザ戻る操作や期限切れflow参照で起きるもので、
通常のGraphicalMatrix認証成功ログとは別扱いにする。

### 15.8 残作業・注意事項

- 導入直後DBは空だったが、DATE_REDACTED時点では4ユーザー登録済み。
- 監査ログ上、GraphicalMatrix認証、パスワード変更画面、TOTP選択、WebAuthn選択の動作痕跡あり。
- 管理APIを有効化する場合は、空のtokenファイルを強力なBearer tokenへ置き換え、許可IPを再確認する。
- sequence保存方式はPoC用`plaintext`である。本番では`hash + salt + pepper`を使用する。

## 16. 2faskwsp テストSP登録

SimpleSAMLphp テストSP（`sp.example.com`）をIdPに登録し、
SSO/SLO動作を確認した。

### 16-1. /etc/hosts への追加

IdPサーバはDNSで`sp.example.com`を解決できないため、
IdPの`/etc/hosts`に静的エントリを追加した。

```bash
echo "192.168.81.61 sp.example.com" | sudo tee -a /etc/hosts
```

### 16-2. SP metadata ファイルの作成

```bash
sudo vi /opt/shibboleth-idp/metadata/2faskw_sp.xml
```

ファイル内容（抜粋）:

```xml
<?xml version="1.0"?>
<md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata"
    entityID="https://sp.example.com/simplesaml/sp">

  <md:SPSSODescriptor
      AuthnRequestsSigned="true"
      WantAssertionsSigned="true"
      protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">

    <md:KeyDescriptor use="signing">
      <!-- SPの署名証明書 (sp.example.com) -->
      ...
    </md:KeyDescriptor>

    <md:SingleLogoutService
        Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
        Location="https://sp.example.com/simplesaml/module.php/saml/sp/singleLogoutService/2faskwsp"/>

    <md:AssertionConsumerService
        Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
        Location="https://sp.example.com/simplesaml/module.php/saml/sp/assertionConsumerService/2faskwsp"
        index="1"/>
  </md:SPSSODescriptor>
</md:EntityDescriptor>
```

実際のSP metadataは以下のURLから取得した（SPサーバ上で`curl`または
ブラウザで取得してコピー）:

```text
https://sp.example.com/simplesaml/module.php/saml/sp/metadata/2faskwsp
```

### 16-3. metadata-providers.xml への追加

```bash
sudo vi /opt/shibboleth-idp/conf/metadata-providers.xml
```

追加内容:

```xml
<MetadataProvider id="SP2faskwTest"
    xsi:type="FilesystemMetadataProvider"
    metadataFile="%{idp.home}/metadata/2faskw_sp.xml"/>
```

### 16-4. jetty-idp 再起動と確認

```bash
sudo systemctl restart jetty-idp
sudo journalctl -u jetty-idp -n 50 | grep -E "successfully loaded|ERROR"
```

`SP2faskwTest`の`successfully loaded`ログを確認。

### 16-5. SLO トラブルシューティング

初回のSLOで`MessageAuthenticationError`が発生した。

**原因**: SimpleSAMLphpのデフォルトでは`sign.logout = false`（LogoutRequestに署名なし）。
Shibboleth IdP 5.xはSPからの署名なしLogoutRequestを拒否する。

**解決**: SPの`authsources.php`に`'sign.logout' => true`を追加した。
詳細はINSTALL_SP.md セクション8を参照。

**試行して失敗した方法**: `relying-party.xml`に`p:requireSignedRequests="false"`を追加したが、
Shibboleth IdP 5.xでは無効プロパティ（`SAML2SSOConfiguration`が受け付けない）であり、
Spring Beanの初期化エラーでjetty-idpが起動不能になった。
復旧は`relying-party.xml`の`SAML2.SSO`プロファイル定義を
`<ref bean="SAML2.Logout" />`のみの元の状態に戻し、`jetty-idp`を再起動した。

**調査用DEBUGログの追加**: SLO失敗の原因調査中に`logback.xml`へ以下を追加した。
調査完了後は削除すること。

```bash
sudo vi /opt/shibboleth-idp/conf/logback.xml
```

追加したロガー:

```xml
<logger name="org.opensaml.messaging.handler" level="DEBUG"/>
<logger name="org.opensaml.xmlsec.signature" level="DEBUG"/>
```

> **要対応**: 上記DEBUGロガーはログ量が非常に増えるため、調査完了後に削除し
> `jetty-idp`を再起動すること。

### 16-6. 動作確認結果

- **SSO**: `Principal: test-user001`にてLDAP Password → GraphicalMatrix MFA完了。
  `schacHomeOrganization`属性を取得。
- **SLO**: IdP監査ログで`||Success||`を確認。
  Binding: HTTP-Redirect / HTTP-Redirect。

## 17. 未実施事項

未実施:

- PostgreSQLバックアップ
- LDAP Checker資格情報の更新と実LDAPユーザーログイン試験
- 証明書更新・期限監視の自動化
- 利用クライアントへのIIC CA証明書配布
- パスワードレスsudoの無効化

実施済み:

- PostgreSQL監視/HA構成のDB1/DB2構築
  - 詳細: `INSTALL_DB.md`
  - DB1: `192.168.81.62`
  - DB2: `192.168.81.63`
  - DB VIP: `192.168.81.64`
  - 構成: PostgreSQL Streaming Replication + HAProxy + Keepalived
  - 注意: 2台構成のため自動昇格は行わず、DB2昇格は手動運用とする
- IdP `192.168.81.60` の `db.properties` をDB VIPへ切替済み
  - 変更前: `jdbc:postgresql://127.0.0.1:5432/graphicalmatrix`
  - 変更後: `jdbc:postgresql://192.168.81.64:5432/graphicalmatrix`
  - Jetty IdP再起動後、管理CLIでDB VIP経由の参照成功

HA-DB構成：
        +-- IdP-1
LB -----+
        +-- IdP-2
              |
              v
        DB VIP: 例 192.0.2.xxx:5432
              |
              v
        HAProxy active node
              |
              v
        PostgreSQL Primary

DB-1: PostgreSQL + HAProxy + Keepalived
DB-2: PostgreSQL + HAProxy + Keepalived

graphicalmatrix.db.url = jdbc:postgresql://xxx.xx.xx.xx:5432/graphicalmatrix

## 18. firewalld SSH接続元制限

DATE_REDACTEDに、IdP/APサーバ `192.168.81.60` のSSH接続元を
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
HTTPS等の既存公開サービスは変更していない。

## 19. IdP/APパフォーマンスチューニング設定例

### 19.1 前提

この章は、将来の本番投入前に検討する設定例である。
DATE_REDACTED時点では未適用。

想定:

```text
登録ユーザー数: 約50,000
同時接続ユーザー: 約300
IdP台数: 2台想定
DB台数: Primary/Standby 2台
MFA方式: GraphicalMatrix / TOTP / WebAuthn
DB接続先: DB VIP -> HAProxy -> PostgreSQL Primary
```

注意:

- 同時接続300は、IdP 1台あたり300ではなく、LB配下の合計値として考える。
- IdP 2台構成なら、1台あたり150同時接続を目安にする。
- ログイン処理はLDAP、DB、WebAuthn/TOTP、SAML処理が絡むため、DBだけでなくJetty/JVM/LDAPも見る。
- 以下の値は初期案であり、実メモリ、CPU、負荷試験結果に合わせて調整する。

### 19.2 推奨方針

本番では以下を推奨する。

```text
IdPを2台以上にする
LBでHTTPSを受けるか、各IdPのJetty HTTPSを直接受ける
Jetty thread数を同時接続に合わせて調整する
JVM heapを明示する
DB接続数はIdP 1台あたり30-50程度に抑える
DEBUGログを常用しない
GraphicalMatrix画像/CSS/HTMLはキャッシュ可能なものを整理する
監査ログとJettyログのローテーションを必須にする
```

### 19.3 JVM heap設定例

IdP 1台あたりの初期案。

RAM 8GB:

```text
Xms: 2GB
Xmx: 4GB
```

RAM 16GB:

```text
Xms: 4GB
Xmx: 8GB
```

systemd override例:

```bash
sudo systemctl edit jetty-idp.service
```

例:

```ini
[Service]
Environment="JAVA_OPTIONS=-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/urandom"
```

反映:

```bash
sudo systemctl daemon-reload
sudo systemctl restart jetty-idp
sudo systemctl status jetty-idp --no-pager
```

確認:

```bash
sudo systemctl show jetty-idp -p Environment
pgrep -a java
```

注意:

- `Xmx` を物理メモリの大半にしない。
- OS page cache、Jetty native memory、TLS、ログ、監視agent分を残す。
- GCログを有効化する場合はログローテーションを必ず設計する。

GCログ例:

```text
-Xlog:gc*:file=/opt/shibboleth-idp/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=50M
```

### 19.4 Jetty thread設定例

同時300ユーザー、IdP 2台構成なら、1台あたり150同時接続を想定する。
Jetty threadは余裕を見て、1台あたり200-300程度から始める。

確認:

```bash
sudo grep -R --line-number -E 'jetty.threadPool|maxThreads|minThreads|idleTimeout' \
  /opt/jetty-idp-base /etc/systemd/system/jetty-idp.service 2>/dev/null || true
```

設定例:

```properties
# /opt/jetty-idp-base/start.d/threadpool.ini
jetty.threadPool.minThreads=20
jetty.threadPool.maxThreads=300
jetty.threadPool.reservedThreads=20
jetty.threadPool.idleTimeout=60000
```

反映:

```bash
sudo systemctl restart jetty-idp
```

監視:

```bash
sudo journalctl -u jetty-idp -n 100 --no-pager
ss -tan state established '( sport = :443 )' | wc -l
ss -tan state established '( sport = :8080 )' | wc -l
```

注意:

- threadを増やしすぎると、メモリ消費とコンテキストスイッチが増える。
- DB接続数やLDAP接続数が不足している状態でthreadだけ増やしても改善しない。

### 19.5 HTTPS / TLS設定

Jettyが直接HTTPSを受ける場合、TLS処理もIdP CPUを消費する。
LBでTLS終端する場合は、LB-IdP間の暗号化要否を別途決める。

確認:

```bash
sudo ss -ltnp | grep -E ':443|:8080'
openssl s_client -connect idp.example.com:443 -servername idp.example.com </dev/null
```

本番推奨:

```text
TLS 1.2以上
弱いcipher無効化
証明書期限監視
OCSP/CRL方針確認
LB配下ならX-Forwarded-For / Forwardedの扱いを明確化
```

### 19.6 DB接続設定

GraphicalMatrix/TOTPは `db.properties` を参照する。
WebAuthn StorageServiceは `global.xml` のDataSourceを参照する。

DB接続先:

```properties
graphicalmatrix.db.url=jdbc:postgresql://192.168.81.64:5432/graphicalmatrix
```

SSL化後:

```properties
graphicalmatrix.db.url=jdbc:postgresql://db-graphicalmatrix.example.com:5432/graphicalmatrix?sslmode=verify-full&sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

JDBC接続数の目安:

```text
IdP 1台あたり: 30 - 50接続
IdP 2台合計:   60 - 100接続
```

現在の管理CLIや一部実装は操作ごとに接続するため、常時大量接続にはなりにくい。
ただし、APIを高頻度に使う場合やWebAuthn StorageService側でpoolを使う場合は、
DataSource pool上限を明示する。

DB側の推奨は `INSTALL_DB.md` のパフォーマンス章を参照する。

### 19.7 LDAP接続

Password認証はLDAPを参照するため、同時300ユーザーではLDAP側もボトルネックになり得る。

確認対象:

```text
LDAP接続先
LDAP timeout
LDAP connection pool
LDAP bind DN
LDAP server側の接続上限
LDAP検索filter
```

確認コマンド例:

```bash
sudo grep -R --line-number -E 'ldap|LDAP|connectTimeout|responseTimeout|pool' \
  /opt/shibboleth-idp/conf /opt/shibboleth-idp/credentials 2>/dev/null
```

推奨:

```text
LDAP timeoutを無制限にしない
LDAP connection poolを有効化する
LDAP serverを複数指定できるなら冗長化する
検索base/filterを広くしすぎない
LDAP bind DNのパスワード権限を厳格にする
```

### 19.8 GraphicalMatrix静的ファイル

GraphicalMatrixでは画像、CSS、HTML templateを利用する。

現在:

```text
画像: /opt/shibboleth-idp/edit-webapp/graphicalmatrix/graphicals
CSS:  /opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css
HTML: /opt/shibboleth-idp/conf/graphicalmatrix/views/*.html
```

本番推奨:

```text
画像/CSSはHTTP cache headerを設計する
template更新頻度が低ければcacheSecondsを伸ばす
将来のtoken化画像配信では、画像本体の直URL公開を避ける
画像ファイルサイズを小さくする
SVG/PNG/GIFの混在時はContent-Typeを正しく返す
```

設定例:

```properties
graphicalmatrix.view.css.cacheSeconds = 3600
```

注意:

- CSS/templateを頻繁に変更するPoCでは `cacheSeconds=0` が便利。
- 本番ではキャッシュを使う方がJetty負荷を下げられる。

### 19.9 ログ設定

本番ではDEBUGログを常用しない。

削除対象例:

```xml
<logger name="org.opensaml.messaging.handler" level="DEBUG"/>
<logger name="org.opensaml.xmlsec.signature" level="DEBUG"/>
```

監査ログ:

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

推奨:

```text
graphicalmatrix-audit.log のlogrotate
Jettyログのlogrotate
GCログを有効化する場合はGCログのローテーション
ログ転送/保管期間の決定
API有効時は管理操作ログを必ず保管
```

確認:

```bash
sudo du -sh /opt/shibboleth-idp/logs
sudo find /opt/shibboleth-idp/logs -type f -maxdepth 1 -printf '%f %s bytes\n' | sort
```

### 19.10 OS設定例

確認:

```bash
free -h
nproc
ulimit -n
sysctl net.core.somaxconn
sysctl net.ipv4.ip_local_port_range
ss -s
```

設定例:

```conf
# /etc/sysctl.d/90-graphicalmatrix-idp.conf
net.core.somaxconn = 1024
net.ipv4.tcp_keepalive_time = 300
net.ipv4.tcp_keepalive_intvl = 30
net.ipv4.tcp_keepalive_probes = 5
net.ipv4.ip_local_port_range = 10240 60999
```

反映:

```bash
sudo sysctl --system
```

systemd open files:

```bash
systemctl show jetty-idp -p LimitNOFILE
```

必要ならoverride:

```ini
[Service]
LimitNOFILE=65536
```

### 19.11 firewalld / 公開ポート

IdP/APサーバで公開するポートを最小化する。

現在の方針:

```text
443/tcp: IdP HTTPS
8080/tcp: localhostのみ
22/tcp: 192.168.81.0/24のみ
```

確認:

```bash
sudo firewall-cmd --zone=public --list-all
sudo ss -ltnp
```

本番推奨:

```text
SSHは管理CIDRのみに限定
8080は外部公開しない
APIを有効化する場合はHTTPS + API token + allowedCidrs + firewall/LB制限
DB 5432はIdPからDBへ出る通信のみ。IdP側で5432を待ち受けない
```

### 19.12 監視項目

IdP/AP側で監視する。

サービス:

```bash
systemctl is-active jetty-idp
curl -k -s -o /dev/null -w '%{http_code}\n' https://idp.example.com/idp/status
```

プロセス/JVM:

```bash
pgrep -a java
jcmd <PID> VM.flags
jcmd <PID> GC.heap_info
jcmd <PID> Thread.print | grep -E 'java.lang.Thread.State' | sort | uniq -c
```

ポート:

```bash
ss -tan state established '( sport = :443 )' | wc -l
ss -tan state established '( dport = :5432 )' | wc -l
```

ログ:

```bash
sudo tail -n 100 /opt/shibboleth-idp/logs/idp-process.log
sudo tail -n 100 /opt/shibboleth-idp/logs/idp-audit.log
sudo tail -n 100 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
sudo journalctl -u jetty-idp -n 100 --no-pager
```

アプリ確認:

```bash
curl -k -s -o /tmp/idp-status.out -w 'status=%{http_code}\n' \
  https://idp.example.com/idp/status

curl -k -s -o /tmp/im-change.out -w 'change=%{http_code}\n' \
  https://idp.example.com/idp/graphicalmatrix/change
```

### 19.13 負荷試験

本番適用前に以下を試験する。

```text
1. IdP 1台で150同時接続相当
2. IdP 2台で300同時接続相当
3. LDAP Password認証
4. GraphicalMatrix challenge作成
5. GraphicalMatrix verify成功/失敗
6. TOTP認証
7. WebAuthn認証
8. sequence変更画面
9. API無効時の404確認
10. API有効時の管理操作負荷
11. DB VIP切替後のIdP復旧
12. Jetty再起動後の復旧時間
```

見る値:

```text
HTTP平均応答時間
HTTP 95 percentile / 99 percentile
Jetty thread使用率
JVM heap使用率
GC pause
LDAP応答時間
DB接続数
DB query時間
GraphicalMatrix audit件数
エラー率
```

### 19.14 適用順序

推奨順序:

```text
1. DEBUGログを外す
2. ログローテーションを整える
3. JVM heapを明示する
4. Jetty threadを控えめに増やす
5. DB接続数を制御する
6. LDAP timeout/poolを確認する
7. 監視を入れる
8. 負荷試験
9. 実測に合わせてheap/thread/DB接続数を調整する
```

最初からthreadやheapを大きくしすぎない。
DB、LDAP、Jetty thread、JVM heapのどれが詰まっているかを見ながら調整する。

## 20. GraphicalMatrix Plugin 1.0.1 / HikariCP適用

DATE_REDACTEDに、IdP/APサーバ `192.168.81.60`
（`idp.example.com`）へ GraphicalMatrix Plugin `1.0.1` を適用した。

目的:

```text
M-1対応:
GraphicalMatrix runtime servlet、Admin API、TOTP seed参照のDB接続を
HikariCP接続プール経由にする。
```

適用した配布物:

```bash
scp graphicalmatrix-plugin-local-dist/2faskw-idp-plugin-1.0.1.zip \
  user@192.168.81.60:/tmp/2faskw-idp-plugin-1.0.1.zip
```

サーバ上の配置:

```text
/home/user/install-media/2faskw-idp-plugin-1.0.1.zip
/opt/src/2faskw-idp-plugin-1.0.1
```

バックアップ:

```text
/opt/backups/graphicalmatrix-plugin-hikaricp-20260610115951
/opt/backups/graphicalmatrix-plugin-overlay-bakfiles-20260610120138
```

インストールログ:

```text
/home/user/install-logs/23-graphicalmatrix-hikaricp-1.0.1-install-20260610115951.log
/home/user/install-logs/24-graphicalmatrix-hikaricp-webxml-refresh-20260610120004.log
/home/user/install-logs/25-graphicalmatrix-hikaricp-build-restart-20260610120024.log
/home/user/install-logs/26-graphicalmatrix-hikaricp-build-restart-20260610120048.log
/home/user/install-logs/27-graphicalmatrix-hikaricp-build-restart-20260610120115.log
/home/user/install-logs/28-graphicalmatrix-hikaricp-build-restart-20260610120138.log
```

実行した主なコマンド:

```bash
sudo mkdir -p /home/user/install-media /opt/src /opt/backups
sudo install -m 0644 /tmp/2faskw-idp-plugin-1.0.1.zip \
  /home/user/install-media/2faskw-idp-plugin-1.0.1.zip
sudo rm -rf /opt/src/2faskw-idp-plugin-1.0.1
sudo unzip -q /home/user/install-media/2faskw-idp-plugin-1.0.1.zip -d /opt/src

sudo mkdir -p /opt/backups/graphicalmatrix-plugin-hikaricp-20260610115951
sudo cp -a /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  /opt/backups/graphicalmatrix-plugin-hikaricp-20260610115951/lib
sudo cp -a /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml \
  /opt/backups/graphicalmatrix-plugin-hikaricp-20260610115951/web.xml
sudo cp -a /opt/shibboleth-idp/conf/graphicalmatrix \
  /opt/backups/graphicalmatrix-plugin-hikaricp-20260610115951/conf-graphicalmatrix

sudo /opt/src/2faskw-idp-plugin-1.0.1/bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /opt/src/2faskw-idp-plugin-1.0.1 \
  --apply

sudo find /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '2faskw-idp-plugin-*.jar' \
  ! -name '2faskw-idp-plugin-1.0.1.jar' -print -delete

sudo /opt/src/2faskw-idp-plugin-1.0.1/bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp --remove --apply
sudo /opt/src/2faskw-idp-plugin-1.0.1/bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp --install --apply
```

既存 `db.properties` は上書きせず、DB VIP接続先を維持したまま以下を追記した。

```properties
graphicalmatrix.db.pool.enabled=true
graphicalmatrix.db.pool.maximumPoolSize=10
graphicalmatrix.db.pool.minimumIdle=2
graphicalmatrix.db.pool.connectionTimeoutMillis=30000
graphicalmatrix.db.pool.idleTimeoutMillis=600000
graphicalmatrix.db.pool.maxLifetimeMillis=1800000
graphicalmatrix.db.pool.validationTimeoutMillis=5000
```

確認コマンド:

```bash
sudo grep -nE 'graphicalmatrix.db.(url|user|pool)' \
  /opt/shibboleth-idp/conf/graphicalmatrix/db.properties
sudo ls -l /opt/shibboleth-idp/edit-webapp/WEB-INF/lib | \
  grep -E 'graphicalmatrix|HikariCP|postgresql|core-'
sudo grep -nE 'GraphicalMatrixDataSourceListener|GraphicalMatrixStart|GraphicalMatrixAdminApi' \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml
```

WAR再ビルド時に、既存TOTP/WebAuthn/JDBC StorageServiceプラグインのJARが
`root:root 0640` で配置されていたため、`jetty` ユーザーで読めず失敗した。
以下で `root:jetty 0640` に補正した。

```bash
sudo find /opt/shibboleth-idp/dist/plugin-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '*.jar' -exec chgrp jetty {} +
sudo find /opt/shibboleth-idp/dist/plugin-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '*.jar' -exec chmod 0640 {} +
```

また、`edit-webapp` 配下の `.bak.*` ファイルはWAR overlay対象になるため、
以下で `/opt/backups` へ退避した。

```bash
sudo mkdir -p /opt/backups/graphicalmatrix-plugin-overlay-bakfiles-20260610120138
sudo find /opt/shibboleth-idp/edit-webapp -type f -name '*.bak.*' \
  -print -exec mv -t /opt/backups/graphicalmatrix-plugin-overlay-bakfiles-20260610120138 {} +
```

WAR再ビルドと再起動:

```bash
sudo -u jetty /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp
sudo systemctl is-active jetty-idp
```

適用後のWAR確認:

```bash
sudo unzip -l /opt/shibboleth-idp/war/idp.war | \
  grep -E 'WEB-INF/lib/(2faskw-idp-plugin|HikariCP|postgresql|core-)'
sudo unzip -p /opt/shibboleth-idp/war/idp.war WEB-INF/web.xml | \
  grep -nE 'GraphicalMatrixDataSourceListener|GraphicalMatrixStart|SameSite|<secure>'
```

確認結果:

```text
WEB-INF/lib/HikariCP-6.3.0.jar
WEB-INF/lib/core-3.5.3.jar
WEB-INF/lib/2faskw-idp-plugin-1.0.1.jar
WEB-INF/lib/postgresql-42.7.11.jar
WEB-INF/web.xml に GraphicalMatrixDataSourceListener が存在
```

FQDN指定でのHTTP確認:

```bash
curl -k --resolve idp.example.com:443:127.0.0.1 \
  -s -o /tmp/idp.out -w '%{http_code}\n' \
  https://idp.example.com/idp/
curl -k --resolve idp.example.com:443:127.0.0.1 \
  -s -o /tmp/status.out -w '%{http_code}\n' \
  https://idp.example.com/idp/status
curl -k --resolve idp.example.com:443:127.0.0.1 \
  -s -o /tmp/change.out -w '%{http_code}\n' \
  https://idp.example.com/idp/graphicalmatrix/change
curl -k --resolve idp.example.com:443:127.0.0.1 \
  -s -o /tmp/css.out -w '%{http_code}\n' \
  https://idp.example.com/idp/graphicalmatrix/assets/graphicalmatrix.css
```

結果:

```text
/idp/                                200
/idp/status                          200
/idp/graphicalmatrix/change              200
/idp/graphicalmatrix/assets/graphicalmatrix.css 200
```

DB CLI確認:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
```

結果:

```text
DB VIP 192.168.81.64 経由で4ユーザーを参照できた。
```
