# ビルドサーバ アプリケーション導入記録

## 1. 概要

新しいビルドサーバへGraphicalMatrix Pluginのビルド・検証に必要な
アプリケーションを導入した。

この文書はRocky Linux 10.2での実作業記録である。
手順中の `dnf`、`firewall-cmd`、`systemctl`、PGDG RPM、`/usr/pgsql-18/bin` は
Rocky/RHEL互換環境を前提にしている。
Debian、Ubuntu、その他のLinuxでは、パッケージ名、Firewall、systemd unit、
PostgreSQLの配置パスを環境に合わせて読み替える必要がある。

対象サーバ:

```text
SSH: user@192.0.2.60
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
ssh user@192.0.2.60

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
openjdk version "21.0.11" LTS
javac 21.0.11
Apache Maven 3.9.9
Java home: /usr/lib/jvm/java-21-openjdk
Architecture: aarch64
```

## 5. PostgreSQL 18.4

別サーバ、HA構成は [<mark>DBを構築する：INSTALL_DB.md</mark>](./INSTALL_DB.md)　を参照

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

cd ./tmp
sudo tar xzf jetty-home-12.0.36.tar.gz -C /opt
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

実行コマンド:ホスト名を決める
```bash
sudo hostnamectl set-hostname idp.example.com
```

設定ファイル：idp-install.properties
```
idp.entityID=https://idp.example.com/idp/shibboleth
idp.scope=example.com
idp.cookie.secure = true
```

実行コマンド：propertiesの設置
```bash
cd .tmp/
sudo tar xzf shibboleth-identity-provider-5.2.2.tar.gz -C /opt/src

cat > /tmp/idp-install.properties <<'EOF'
idp.entityID=https://idp.example.com/idp/shibboleth
idp.scope=example.com
idp.cookie.secure = true
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
  --hostName 192.0.2.60 \
  --entityID https://192.0.2.60/idp/shibboleth \
  --keystorePassword "$KS" \
  --sealerPassword "$SEALER"

unset KS SEALER
rm -f /tmp/idp-install.properties

sudo chown -R jetty:jetty /opt/shibboleth-idp
```

※秘密値は本書には記録していない。
※FQDN、entityID、scopeは、HTTPS化時に正式値へ更新してもよい。


変更する場合
/opt/shibboleth-idp/conf/idp.properties をバックアップして編集します。
```bash
sudo cp -a /opt/shibboleth-idp/conf/idp.properties \
  /opt/shibboleth-idp/conf/idp.properties.bak.$(date +%Y%m%d%H%M%S)

sudo vi /opt/shibboleth-idp/conf/idp.properties
```

次に、静的 metadata も IP から FQDN に直します。
```bash
sudo cp -a /opt/shibboleth-idp/metadata/idp-metadata.xml \
  /opt/shibboleth-idp/metadata/idp-metadata.xml.bak.$(date +%Y%m%d%H%M%S)

sudo vi /opt/shibboleth-idp/metadata/idp-metadata.xml
```

主に直す範囲
entityID
Scope
SingleSignOnService Location
SingleLogoutService Location
ArtifactResolutionService Location があればそれも


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

確認：
```bash
sudo systemctl is-enabled jetty-idp.service
sudo systemctl is-active jetty-idp.service
sudo systemctl status jetty-idp.service --no-pager

sudo ss -ltnp | grep -E ':8080|:443'

sudo ss -ltnp 'sport = :8080'

curl -i http://127.0.0.1:8080/idp/
curl -i http://127.0.0.1:8080/idp/status
```

確認結果:
```text
jetty-idp.service: enabled / active
Jetty listener: 0.0.0.0:8080
/idp/: HTTP 200
/idp/status: HTTP 200
```

※firewalldに注意


## 9. 最終確認コマンド

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

## 10. サーバ上の作業ログ

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

## 11. 追加設定フェーズ

ここまでで、OS、Java、Jetty、Shibboleth IdP、基本的なJetty配備までの
初期導入は完了している。

以降の章では、IdP/APサーバを実際のMFA検証環境として使うための追加設定を扱う。

- HTTPS / FQDN設定
- LDAP Password認証設定
- TOTP / WebAuthn Plugin設定
- 2FAS-KW Plugin導入
- テスト用SP metadata登録
- 運用・性能・監視に関する補足設定

## 12. HTTPS / FQDN設定

以下のFQDNと証明書を使用してJettyをHTTPS化した。

```text
FQDN: idp.example.com
IP: 192.0.2.60
HTTPS URL: https://idp.example.com/idp/
```

証明書:

```text
Subject: C=JP, ST=JAPAN, L=TOKYO, O=Example Organization, OU=, CN=idp.example.com
Issuer: CN=private-ca.example.com
SAN: DNS:idp.example.com, IP:192.0.2.60
notBefore: <timestamp>
notAfter: <timestamp>
SHA-256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

証明書チェーンの信頼可能期間はCA証明書の有効期限にも制約される。

### 12.1 証明書確認

実行コマンド:

```bash
openssl verify \
  -CAfile private-ca.ca.cert.pem \
  idp.cert.pem

openssl x509 -in idp.cert.pem \
  -noout -subject -issuer -dates -ext subjectAltName -fingerprint -sha256

openssl x509 -in idp.cert.pem -pubkey -noout | openssl sha256
openssl pkey -in idp.key -pubout | openssl sha256
```

確認結果:

```text
2faskw.cert.pem: OK
certificate/private-key match: OK
```
※SHA2-256(stdin)の出力はここには記載しない。

### 12.2 バックアップ

HTTPS設定前のバックアップ:

```text
TS="$(date +%Y%m%d%H%M%S)"
BACKUP_DIR="/opt/backups/https-$TS"

sudo mkdir -p "$BACKUP_DIR"
sudo chmod 0700 "$BACKUP_DIR"
sudo chown root:root "$BACKUP_DIR"

sudo cp -a /opt/jetty-base/start.d "$BACKUP_DIR/jetty-start.d"
sudo cp -a /opt/jetty-base/etc "$BACKUP_DIR/jetty-etc" 2>/dev/null || true
sudo cp -a /etc/systemd/system/jetty-idp.service "$BACKUP_DIR/jetty-idp.service"
sudo cp -a /opt/shibboleth-idp/conf/idp.properties "$BACKUP_DIR/idp.properties"
sudo cp -a /opt/shibboleth-idp/metadata/idp-metadata.xml "$BACKUP_DIR/idp-metadata.xml"

sudo stat -c '%a %U:%G %n' "$BACKUP_DIR"
sudo ls -la "$BACKUP_DIR"
```

### 12.3 PKCS#12作成と配置

秘密鍵、サーバ証明書、CA証明書からJetty用PKCS#12を作成した。
PKCS#12パスワードはランダム生成し、本書および作業ログには記録していない。

適応コマンド：
```bash
# 1. Jetty用ディレクトリを用意

sudo mkdir -p /opt/jetty-base/etc
sudo chown jetty:jetty /opt/jetty-base/etc
sudo chmod 0750 /opt/jetty-base/etc

# 2. 証明書があるディレクトリでPKCS#12を作成（あくまで例）
パスワードをランダムに生成

P12_PASS="$(openssl rand -base64 36 | tr -d '\n')"

＃ 確認方法
printf '%s\n' "$P12_PASS"
※後に利用するので、コンソールは落とさない。

openssl pkcs12 -export \
  -inkey idp.example.com.key \
  -in idp.example.com.crt \
  -certfile private-ca.example.com.pem \
  -name idp.example.com \
  -out /tmp/idp-keystore.p12 \
  -passout "pass:$P12_PASS"

# 3. 配置
sudo mkdir -p /opt/jetty-base/etc
sudo chown jetty:jetty /opt/jetty-base/etc
sudo chmod 0750 /opt/jetty-base/etc

sudo install -o root -g root -m 0644 \
  private-ca.example.com.pem \
  /etc/pki/ca-trust/source/anchors/private-ca.example.com.pem

# 4. 権限
sudo install -o jetty -g jetty -m 0600 \
  /tmp/idp-keystore.p12 \
  /opt/jetty-base/etc/idp-keystore.p12

# 5. 適応
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
# 1. SSL設定
sudo vi /opt/jetty-base/start.d/ssl.ini

#ssl.ini
jetty.ssl.host=0.0.0.0
jetty.ssl.port=443
jetty.ssl.sniRequired=true
jetty.ssl.sniHostCheck=true
jetty.ssl.stsMaxAgeSeconds=31536000
jetty.ssl.stsIncludeSubdomains=false
jetty.sslContext.keyStorePath=/opt/jetty-base/etc/idp-keystore.p12
jetty.sslContext.keyStoreType=PKCS12
jetty.sslContext.keyStorePassword=[パスワード]
jetty.sslContext.keyManagerPassword=[パスワード]

※注意
[パスワード] printf '%s\n' "$P12_PASS"

# 2. HTTPS設定
sudo vi /opt/jetty-base/start.d/https.ini

#https.ini
--modules=https
jetty.ssl.host=0.0.0.0
jetty.ssl.port=443
jetty.ssl.sniRequired=true
jetty.ssl.sniHostCheck=true
jetty.ssl.stsMaxAgeSeconds=31536000
jetty.ssl.stsIncludeSubdomains=false

# 3. HTTP設定
sudo vi /opt/jetty-base/start.d/http.ini

#http.ini
--modules=http
jetty.http.host=127.0.0.1
jetty.http.port=8080

# 4. 権限
sudo chown jetty:jetty /opt/jetty-base/start.d/ssl.ini
sudo chmod 0600 /opt/jetty-base/start.d/ssl.ini

# 5. systemdから443番へbindできるよう、`jetty-idp.service`へ以下を追加
sudo vi /etc/systemd/system/jetty-idp.service

#jetty-idp.service
[CapabilityBoundingSet=CAP_NET_BIND_SERVICE
AmbientCapabilities=CAP_NET_BIND_SERVICEService]

# 6. restart
sudo systemctl daemon-reload
sudo systemctl restart jetty-idp.service
```

### 12.5 IdP metadata確認


HTTPS化後、`/opt/shibboleth-idp/metadata/idp-metadata.xml` の以下が
FQDNになっていることを確認する。

- entityID
- Scope
- SingleSignOnService
- SingleLogoutService

確認コマンド:

```bash
sudo grep -nE 'entityID|Scope|SingleSignOnService|SingleLogoutService' \
  /opt/shibboleth-idp/metadata/idp-metadata.xml
```

変更後に実行

```bash
sudo -u jetty env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh
```

### 12.6 firewall

実行コマンド:

```bash
# firewalld が起動していることを確認
sudo systemctl is-enabled firewalld
sudo systemctl is-active firewalld

# 現在の zone を確認
sudo firewall-cmd --get-default-zone
sudo firewall-cmd --get-active-zones

# 現在の許可サービスを確認
sudo firewall-cmd --list-services
sudo firewall-cmd --list-ports

sudo firewall-cmd --add-service=https --permanent
sudo firewall-cmd --reload

sudo firewall-cmd --query-service=https
sudo firewall-cmd --list-services
sudo firewall-cmd --list-ports
```

8080番はfirewalldで許可せず、Jettyも`127.0.0.1:8080`だけで待ち受ける。

```
sudo firewall-cmd --query-port=8080/tcp
sudo firewall-cmd --query-service=http
```

### 12.7 HTTPS検証結果

Jetty起動・停止コマンド:

```bash
# 自動起動を有効化して起動
sudo systemctl enable --now jetty-idp.service

# 手動起動
sudo systemctl start jetty-idp.service

# 手動停止
sudo systemctl stop jetty-idp.service

# 再起動
sudo systemctl restart jetty-idp.service

# 自動起動の有効/無効確認
sudo systemctl is-enabled jetty-idp.service

# 起動状態確認
sudo systemctl is-active jetty-idp.service
sudo systemctl status jetty-idp.service --no-pager

# 自動起動を無効化する場合
sudo systemctl disable jetty-idp.service
```

Jetty確認コマンド:

```bash
sudo systemctl daemon-reload
sudo systemctl restart jetty-idp.service
sleep 5

sudo systemctl is-enabled jetty-idp.service
sudo systemctl is-active jetty-idp.service
sudo systemctl status jetty-idp.service --no-pager

sudo journalctl -u jetty-idp.service -n 150 --no-pager

sudo ss -ltnp | grep -E ':(443|8080)[[:space:]]'
sudo ss -ltnp 'sport = :443'
sudo ss -ltnp 'sport = :8080'

curl -s -o /tmp/idp-root.out -w 'idp=%{http_code}\n' \
  https://idp.example.com/idp/

curl -s -o /tmp/idp-status.out -w 'status=%{http_code}\n' \
  --resolve idp.example.com:443:127.0.0.1 \
  https://idp.example.com/idp/status
```

Jettyが起動しない場合は、以下で直接原因を確認する。

```bash
sudo journalctl -u jetty-idp.service -n 200 --no-pager \
  | grep -E 'Caused by|Exception|ERROR|WARN|KeyStore|password|Bind|Permission|No such|accessible'
```

待受確認:

```bash
sudo ss -ltnp | grep -E ':(443|8080)[[:space:]]'
sudo ss -ltnp 'sport = :443'
sudo ss -ltnp 'sport = :8080'
```

待受状態:

```text
0.0.0.0:443
127.0.0.1:8080
```

外部FQDN確認:

```bash
curl -s -o /tmp/idp-root.out -w 'idp=%{http_code}\n' \
  https://idp.example.com/idp/

curl -s -o /tmp/idp-8080.out -w 'external_8080=%{http_code}\n' \
  --connect-timeout 5 \
  http://idp.example.com:8080/idp/ || echo 'external HTTP 8080: connection refused'
```

外部FQDN確認:

```text
https://idp.example.com/idp/: HTTP 200
external HTTP 8080: connection refused
```

レスポンスヘッダー確認:

```bash
curl -s -D - -o /dev/null https://idp.example.com/idp/ \
  | grep -iE 'strict-transport-security|set-cookie'
```

レスポンス確認:

```text
Strict-Transport-Security: max-age=31536000
Set-Cookie: __Host-JSESSIONID=...; Path=/; Secure; HttpOnly
```

ループバック経由の管理status確認:

```bash
curl -s -o /tmp/idp-status.out -w 'status=%{http_code}\n' \
  --resolve idp.example.com:443:127.0.0.1 \
  https://idp.example.com/idp/status
```

ループバック経由の管理status確認:

```text
https://idp.example.com/idp/status: HTTP 200
```

外部ネットワークからの`/idp/status`はIdPのアクセス制御によりHTTP 403となる。
これは管理エンドポイントを外部公開しない正常な状態である。

外部ネットワークからの管理status確認:

```bash
curl -s -o /tmp/idp-status-external.out -w 'status_external=%{http_code}\n' \
  https://idp.example.com/idp/status
```

期待結果:

```text
status_external=403
```

証明書チェーン確認:

```bash
openssl s_client \
  -connect idp.example.com:443 \
  -servername idp.example.com \
  -verify_return_error </dev/null
```

証明書チェーン確認:

```text
depth=1 CN=private-ca.example.com
verify return:1
depth=0 CN=idp.example.com
verify return:1
```

PostgreSQLとJetty IdPの再起動後も、以下を再確認した。

```bash
sudo systemctl restart postgresql-18.service
sudo systemctl restart jetty-idp.service

sudo systemctl is-enabled postgresql-18.service
sudo systemctl is-active postgresql-18.service
sudo systemctl is-enabled jetty-idp.service
sudo systemctl is-active jetty-idp.service

curl -s -o /tmp/idp-root-after-restart.out -w 'idp=%{http_code}\n' \
  https://idp.example.com/idp/

curl -s -o /tmp/idp-status-after-restart.out -w 'status=%{http_code}\n' \
  --resolve idp.example.com:443:127.0.0.1 \
  https://idp.example.com/idp/status
```

```text
postgresql-18.service: enabled / active
jetty-idp.service: enabled / active
HTTPS /idp/: HTTP 200
HTTPS /idp/status from loopback: HTTP 200
```

### 12.8 利用者端末側のCA信頼

この証明書は公開認証局ではなく `private-ca.example.com` によって発行されている。
そのため、利用者端末、検証端末、ブラウザ、または連携SPが
このprivate CAを信頼していない場合、証明書警告またはTLS検証エラーになる。

利用者端末または検証端末には、以下のCA証明書を信頼済みルートCAとして配布する。

```text
private-ca.example.com.pem
```

## 13. LDAP認証設定

LDAP Password認証を有効にするため、Shibboleth IdPのLDAP接続設定と
bind DN資格情報を設定した。ここではサンプルLDAPとして `192.0.2.38` を使う。

変更ファイル:

```text
/opt/shibboleth-idp/conf/ldap.properties
/opt/shibboleth-idp/credentials/secrets.properties
```

### 13.1 バックアップ

```bash
TS="$(date +%Y%m%d%H%M%S)"
BACKUP_DIR="/opt/backups/ldap-$TS"

sudo mkdir -p "$BACKUP_DIR"
sudo chmod 0700 "$BACKUP_DIR"
sudo chown root:root "$BACKUP_DIR"

sudo cp -a /opt/shibboleth-idp/conf/ldap.properties \
  "$BACKUP_DIR/ldap.properties"

sudo cp -a /opt/shibboleth-idp/credentials/secrets.properties \
  "$BACKUP_DIR/secrets.properties"

sudo ls -la "$BACKUP_DIR"
```

### 13.2 LDAP接続設定

`ldap.properties`を編集する。

```bash
sudo vi /opt/shibboleth-idp/conf/ldap.properties
```

設定例:

```properties
idp.authn.LDAP.authenticator = bindSearchAuthenticator
idp.authn.LDAP.ldapURL = ldap://192.0.2.38:389
idp.authn.LDAP.useStartTLS = false
idp.authn.LDAP.baseDN = ou=People,dc=example,dc=com
idp.authn.LDAP.subtreeSearch = false
idp.authn.LDAP.userFilter = (uid={user})
idp.authn.LDAP.bindDN = uid=user,dc=example,dc=com
```

確認:

```bash
sudo grep -nE \
  'idp.authn.LDAP.(authenticator|ldapURL|useStartTLS|baseDN|subtreeSearch|userFilter|bindDN)' \
  /opt/shibboleth-idp/conf/ldap.properties
```

### 13.3 LDAP bind DN資格情報

`secrets.properties`はファイル全体を上書きせず、LDAP資格情報の項目だけを編集する。
実際の値は作業ログおよび本書には記録しない。

```properties
idp.authn.LDAP.bindDNCredential
idp.attribute.resolver.LDAP.bindDNCredential
```

編集:

```bash
sudo vi /opt/shibboleth-idp/credentials/secrets.properties
```

設定例:

```properties
idp.authn.LDAP.bindDNCredential = <LDAP bind password>
idp.attribute.resolver.LDAP.bindDNCredential = <LDAP bind password>
```

権限確認:

```bash
sudo chown root:jetty /opt/shibboleth-idp/credentials/secrets.properties
sudo chmod 0640 /opt/shibboleth-idp/credentials/secrets.properties
sudo ls -l /opt/shibboleth-idp/credentials/secrets.properties
```

値を表示せずに項目の存在だけ確認する。

```bash
sudo grep -nE \
  '^(idp.authn.LDAP.bindDNCredential|idp.attribute.resolver.LDAP.bindDNCredential)[[:space:]]*=' \
  /opt/shibboleth-idp/credentials/secrets.properties
```

### 13.4 LDAP疎通確認

LDAP疎通確認用として以下を追加した。

```bash
sudo dnf -y install openldap-clients
```

TCP接続確認:

```bash
nc -vz 192.0.2.38 389
```

`nc`が無い場合:

```bash
timeout 5 bash -c '</dev/tcp/192.0.2.38/389' \
  && echo 'LDAP server TCP/389 connection: OK' \
  || echo 'LDAP server TCP/389 connection: FAIL'
```

anonymous rootDSE確認:

```bash
ldapsearch -x \
  -H ldap://192.0.2.38:389 \
  -s base \
  -b '' \
  namingContexts
```

Checker bind確認:

```bash
LDAP_BIND_PASSWORD='<LDAP bind password>'

ldapsearch -x \
  -H ldap://192.0.2.38:389 \
  -D 'cn=Checker,dc=example,dc=com' \
  -w "$LDAP_BIND_PASSWORD" \
  -b 'ou=People,dc=example,dc=com' \
  -s one \
  '(cn=user001)' \
  dn

unset LDAP_BIND_PASSWORD
```

確認結果:

```text
LDAP server TCP/389 connection: OK
LDAP anonymous rootDSE query: OK
LDAP Checker bind: FAIL - Invalid credentials (49)
```

### 13.5 IdP反映

設定変更後、IdPを再起動してログを確認する。

```bash
sudo systemctl restart jetty-idp.service
sleep 5
sudo systemctl is-active jetty-idp.service
sudo systemctl status jetty-idp.service --no-pager

sudo journalctl -u jetty-idp.service -n 200 --no-pager \
  | grep -iE 'error|exception|failed|caused by'

sudo journalctl -u jetty-idp.service -n 300 --no-pager \
  | grep -i ldap
```

## 14. TOTP・WebAuthn Plugin設定

Shibboleth公式PluginレジストリからTOTPとWebAuthn Pluginを
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
/opt/backups/totp-webauthn-plugin-<timestamp>
```

バックアップ（必要なら）:

```bash
TS="$(date +%Y%m%d%H%M%S)"
BACKUP_DIR="/opt/backups/totp-webauthn-plugin-$TS"

sudo mkdir -p "$BACKUP_DIR"
sudo chmod 0700 "$BACKUP_DIR"
sudo chown root:root "$BACKUP_DIR"

sudo cp -a /opt/shibboleth-idp/conf "$BACKUP_DIR/conf"
sudo cp -a /opt/shibboleth-idp/credentials "$BACKUP_DIR/credentials"
sudo cp -a /opt/shibboleth-idp/edit-webapp "$BACKUP_DIR/edit-webapp"
sudo cp -a /opt/shibboleth-idp/metadata "$BACKUP_DIR/metadata"

sudo cp -a /opt/shibboleth-idp/system "$BACKUP_DIR/system" 2>/dev/null || true
sudo cp -a /opt/shibboleth-idp/dist "$BACKUP_DIR/dist" 2>/dev/null || true

sudo /opt/shibboleth-idp/bin/plugin.sh -l \
  > /tmp/shibboleth-plugins-before.txt
sudo install -o root -g root -m 0600 \
  /tmp/shibboleth-plugins-before.txt \
  "$BACKUP_DIR/shibboleth-plugins-before.txt"
rm -f /tmp/shibboleth-plugins-before.txt

sudo find "$BACKUP_DIR" -maxdepth 2 -type f | sort \
  > /tmp/totp-webauthn-backup-files.txt
sudo install -o root -g root -m 0600 \
  /tmp/totp-webauthn-backup-files.txt \
  "$BACKUP_DIR/backup-files.txt"
rm -f /tmp/totp-webauthn-backup-files.txt

sudo stat -c '%a %U:%G %n' "$BACKUP_DIR"
sudo ls -la "$BACKUP_DIR"
```

### 14.2 TOTP SeedSource設定

TOTP seedはShibboleth TOTP Plugin標準のStorageServiceではなく、
2FAS-KWの `graphicalmatrix_enrollment.totp_seed` から参照する。
そのため、TOTP PluginのSeedSourceを `GraphicalMatrixTotpSeedSource` に差し替える。

注意:

- `GraphicalMatrixTotpSeedSource` は2FAS-KW plugin JARに含まれる。
- この設定を反映するのは、2FAS-KWインストールとWAR再構築が完了した後にする。
- 設定だけ先に作成してもよいが、2FAS-KW JARがWARに入る前にJettyを再起動すると、class not foundで起動失敗する可能性がある。

TOTP seed保存方式は `/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`
で設定する。詳細は `docs/INSTALL.md` のDB保存方式を参照する。

例:

```properties
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties

graphicalmatrix.totp.seed.storage = keyword
graphicalmatrix.totp.seed.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword
```

または本番推奨:

```properties
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties

graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

設定ファイル:

2FAS-KW配布物には設定例が含まれる。

```text
examples/totp-authn-config.xml
```

既存ファイルをバックアップしてから、設定例を配置する。

```bash
TS="$(date +%Y%m%d%H%M%S)"

sudo cp -a /opt/shibboleth-idp/conf/authn/totp-authn-config.xml \
  /opt/shibboleth-idp/conf/authn/totp-authn-config.xml.bak.$TS \
  2>/dev/null || true

sudo install -o root -g jetty -m 0644 \
  examples/totp-authn-config.xml \
  /opt/shibboleth-idp/conf/authn/totp-authn-config.xml
```

設定内容は以下を含む。

```text
sudo vi /opt/shibboleth-idp/conf/authn/totp-authn-config.xml
```

```xml
<bean id="shibboleth.authn.TOTP.SeedSource"
      class="io.github.yasakawa.faskw.GraphicalMatrixTotpSeedSource" />
```

確認:

```bash
sudo grep -n 'GraphicalMatrixTotpSeedSource' \
  /opt/shibboleth-idp/conf/authn/totp-authn-config.xml

sudo xmllint --noout /opt/shibboleth-idp/conf/authn/totp-authn-config.xml
```

`GraphicalMatrixTotpSeedSource` は `/opt/shibboleth-idp/conf/graphicalmatrix/db.properties`
を読み、`graphicalmatrix_enrollment` から以下の条件を満たすseedを取得する。

```text
mfa_method = TOTP
totp_status = ACTIVE
totp_seed is not empty
```

TOTP seedは、ユーザーがMFA方式をTOTPへ変更した後、次回ログイン時のQR登録で生成される。
登録が完了すると `totp_status = ACTIVE` になり、以後のTOTP認証で参照される。

### 14.3 WebAuthn設定

WebAuthnを`2faskw`のHTTPS FQDNで使用できるように設定した。

設定ファイルを編集する。

```bash
sudo vi /opt/shibboleth-idp/conf/authn/webauthn.properties
sudo vi /opt/shibboleth-idp/conf/authn/webauthn-registration.properties
sudo vi /opt/shibboleth-idp/conf/access-control.xml
```

```properties
idp.authn.webauthn.relyingPartyId = idp.example.com
idp.authn.webauthn.relyingPartyName = Example IdP 2faskw
idp.authn.webauthn.2fa.enabled = true
idp.authn.webauthn.2fa.allowedPreviousFactors = authn/Password
idp.authn.webauthn.admin.registration.accessPolicy = AccessByCurrentUser
```

`access-control.xml` では、上記 `accessPolicy` から参照する
`AccessByCurrentUser` policyを定義する。これはWebAuthn credential登録画面で、
認証済みユーザー本人による登録だけを許可するための設定である。

`shibboleth.AccessControlPolicies` の `util:map` 内に以下を追加または確認する。

```xml
<entry key="AccessByCurrentUser">
    <bean parent="shibboleth.PredicateAccessControl">
        <constructor-arg>
            <bean id="AccessByCurrentUserPredicate"
                  class="net.shibboleth.idp.plugin.authn.webauthn.admin.impl.AllowCurrentUserAccessPredicate" />
        </constructor-arg>
    </bean>
</entry>
```

設定確認:

```bash
sudo grep -nE \
  'idp.authn.webauthn.(relyingPartyId|relyingPartyName|2fa.enabled|2fa.allowedPreviousFactors|admin.registration.accessPolicy)' \
  /opt/shibboleth-idp/conf/authn/webauthn.properties \
  /opt/shibboleth-idp/conf/authn/webauthn-registration.properties

sudo grep -n 'AccessByCurrentUser' \
  /opt/shibboleth-idp/conf/access-control.xml
```

XML構文確認:

```bash
sudo xmllint --noout /opt/shibboleth-idp/conf/access-control.xml
```

再起動
```bash
sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh

sudo systemctl restart jetty-idp.service
```

WebAuthn credential登録URL:

```text
https://idp.example.com/idp/profile/admin/webauthn-registration
```

登録URL確認:

```bash
curl -k -s -o /tmp/webauthn-registration.out -w 'webauthn_registration=%{http_code}\n' \
  https://idp.example.com/idp/profile/admin/webauthn-registration
```

未認証状態では、認証フロー開始を示すHTTP 302またはログイン画面のHTTP 200を期待する。

### 14.4 WAR再構築

Plugin JARは公式Plugin管理ツールによって`0640 root:root`で配置される。
そのためPlugin導入後のWAR再構築は、`jetty`ユーザーではなくrootで実行した。

```bash
sudo env \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
  /opt/shibboleth-idp/bin/build.sh

sudo systemctl restart jetty-idp
```

### 14.5 2FAS-KWをインストール
[<mark>2FAS-KWを構築する：INSTALL.md</mark>](./INSTALL.md)　を参照

インストールが終わったら次へ進む

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

#### 実装結果

導入前バックアップ:

```text
/opt/backups/webauthn-jdbc-storage-<timestamp>
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

```
# 確認
sudo file /opt/shibboleth-idp/credentials/net.shibboleth.idp.plugin.authn.webauthn/truststore.asc
sudo head -n 20 /opt/shibboleth-idp/credentials/net.shibboleth.idp.plugin.authn.webauthn/truststore.asc
```
JDBC StorageService Pluginの導入時は非対話環境だったため、既に導入済みの
WebAuthn Plugin truststoreを指定して署名鍵を検証した。

```
#　再起動
sudo systemctl restart jetty-idp.service
```

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

#### StorageRecordsテーブル作成

PostgreSQLでは引用符なしの`StorageRecords`は小文字の`storagerecords`として作成される。
Shibboleth JDBC StorageServiceの標準SQLも引用符なしで参照するため、この状態で一致する。

実行DDL:
```
PGPASSWORD='GraphicalMatrix DB password' \
  psql \
  -h 192.0.2.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -v ON_ERROR_STOP=1 <<'SQL'
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
SQL
```

確認結果:

```text
 psql \
  -h 192.0.2.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -c '\d storagerecords'

                     テーブル"public.storagerecords"
   列    |         タイプ         | 照合順序 | Null 値を許容 | デフォルト
---------+------------------------+----------+---------------+------------
 context | character varying(255) |          | not null      |
 id      | character varying(255) |          | not null      |
 expires | bigint                 |          |               |
 value   | text                   |          | not null      |
 version | bigint                 |          | not null      |

インデックス:
    "storagerecords_pkey" PRIMARY KEY, btree (context, id)
    "idx_storage_records_expires" btree (expires)


Table: public.storagerecords
Columns: context, id, expires, value, version
Primary key: context, id
Index: idx_storage_records_expires
Current rows: 1 after WebAuthn credential registration test
graphicalmatrix_app: SELECT/INSERT/UPDATE/DELETE可能
```

DBアプリユーザーでの一時レコードINSERT/SELECT/DELETEも成功した。

#### IdP設定

```
sudo vi /opt/shibboleth-idp/conf/global.xml
```
`</beans> の直前に以下を追加します。`

```xml
<!-- BEGIN GraphicalMatrix WebAuthn JDBC StorageService -->
<!-- Store WebAuthn credentials in the existing GraphicalMatrix PostgreSQL database. -->
<bean id="GraphicalMatrixStorageDataSource"
      class="org.apache.commons.dbcp2.BasicDataSource"
      destroy-method="close"
      lazy-init="true"
      p:driverClassName="org.postgresql.Driver"
      p:url="jdbc:postgresql://192.0.2.64:5432/graphicalmatrix"
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
`p:url`はIdPサーバ自身ではなく、DB VIPまたはPostgreSQL接続先を指定する。
IdPサーバ上でPostgreSQLを動かしていない構成では、`127.0.0.1:5432`を指定すると
JDBC StorageServiceの初期化に失敗し、IdPがHTTP 503を返す。

IdPサーバからDBへ接続できることを確認する。

```bash
PGPASSWORD="$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password)" \
  /usr/pgsql-18/bin/psql \
  -h 192.0.2.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -Atqc 'SELECT 1;'
```
以下、webauthnの保存先を設定ファイルに追加した。
（デフォルトだとメモリに保存）
```
sudo vi /opt/shibboleth-idp/conf/authn/webauthn.properties
```
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

注意:

- `storagerecords`はWebAuthn credential登録後に作成される。
- 以前のデフォルトStorageService上のcredentialはDBへ自動移行されない。
- `mfa_method=WebAuthn`のユーザーでDB credentialが無い場合は、再登録または一時的な`set-method GraphicalMatrix`が必要。
- 起動ログにはWebAuthn Plugin由来の`non-clustered storage service`注意ログが残る。credential保存先はJDBCへ切替済みだが、WebAuthnのロックアウト/補助状態の扱いは追加検証対象とする。

#### WebAuthn DB保存後の実ログイン確認
`user001@example.com`でWebAuthn credential登録と
WebAuthn認証によるSPログイン成功を確認した。

確認ログ:

```text
ValidateAuthenticatorAttestationResponse: Public key registration was valid
StorePublicKeyCredential: Added public key credential registration for user 'user001@example.com'
ValidateWebAuthnAssertion: WebAuthn authentication succeeded for 'user001@example.com'
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
id: user001@example.com
version: 1
value_length: 699
```

これにより、WebAuthn credentialがGraphicalMatrix DB側へ保存され、
IdP再起動後も参照可能な状態になった。

#### JDBCAccelerator有効化

DBの件数が多い場合は、JDBCAccelerator有効化して検索速度を上げる。

導入前バックアップ:

```text
/opt/backups/webauthn-jdbc-accelerator-<timestamp>
```

サーバ上の実行ログ:

```text
/home/user/install-logs/27-webauthn-jdbc-accelerator.log
```

global.xmlに設定を追加。

```
sudo vi /opt/shibboleth-idp/conf/global.xml
```
以下を追加した。
`</beans> の直前 に入れる。`
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

webauthn.propertiesにも設定を追加する。

```
sudo vi /opt/shibboleth-idp/conf/authn/webauthn.properties
```
以下を有効化した。

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
<timestamp> method decision: method=WEBAUTHN, flow=authn/WebAuthn
<timestamp> ValidateWebAuthnAssertion: WebAuthn authentication succeeded for 'user001@example.com'
<timestamp> Shibboleth-Audit.SSO: ... https://sp.example.com/simplesaml/sp ... Success
<timestamp> Shibboleth-Audit.Logout: ... Success
```

確認結果:

```text
jetty-idp.service: active
storagerecords: 1 row
id: user001@example.com
value_length: 699
```

これにより、WebAuthn credentialのDB永続化とJDBCAccelerator有効化後の認証成功を確認済み。



#### GraphicalMatrix管理CLIによるWebAuthn credential管理

目的:

```text
graphicalmatrix-db.sh で管理しているユーザーIDを基準に、
不要になったWebAuthn credentialも同じ管理導線で削除できるようにする。
```

実装状態:

```text
実装済み。
/opt/shibboleth-idp/bin/graphicalmatrix-db.sh に webauthn-list / webauthn-reset /
webauthn-delete を追加した。
WebAuthn credential管理コマンドはPostgreSQLのstoragerecordsを対象にする。
```

導入前バックアップ:

```text
/opt/backups/graphicalmatrix-db-webauthn-cli-<timestamp>
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


## 16. テスト用SP連携確認

この章では、テスト用SimpleSAMLphp SPをIdPへ登録し、SSO/SLOを確認する。
作業はSP側とIdP側に分かれるため、各項目の冒頭に作業場所を明記する。

### 16.1 テスト用SP metadata ファイルの作成

この項目はIdP側で作業する。
テスト用SPを追加する場合は、SP側で公開されるmetadataをIdPへ保存する。
以下はSimpleSAMLphp SPの例。

```bash
sudo curl -fsSL \
  https://sp.example.com/simplesaml/module.php/saml/sp/metadata/2faskwsp \
  -o /opt/shibboleth-idp/metadata/2faskw_sp.xml

sudo chown root:jetty /opt/shibboleth-idp/metadata/2faskw_sp.xml
sudo chmod 0644 /opt/shibboleth-idp/metadata/2faskw_sp.xml
```

XMLとして読めることを確認する。

```bash
sudo xmllint --noout /opt/shibboleth-idp/metadata/2faskw_sp.xml
```

metadata内の `entityID`、`AssertionConsumerService`、`SingleLogoutService` が
SP側設定と一致していることを確認する。
抜粋に `...` を含めたXMLは保存せず、SPから取得した完全なmetadataを使う。

### 16.2 IdP側 metadata-providers.xml への追加

この項目はIdP側で作業する。

```bash
sudo vi /opt/shibboleth-idp/conf/metadata-providers.xml
```

既存の `ChainingMetadataProvider` の内側へ以下を追加する。
ファイル末尾へ単独で置くとXML構造が壊れるため、追加位置に注意する。

```xml
<MetadataProvider id="SP2faskwTest"
    xsi:type="FilesystemMetadataProvider"
    metadataFile="%{idp.home}/metadata/2faskw_sp.xml"/>
```

SP側では、IdP metadataを登録し、SPの `entityID` とIdPへ渡す
metadata内の `entityID` を一致させる。

SP側の代表的な確認対象:

```text
metadata/saml20-idp-remote.php
config/authsources.php
cert/
```

SP側 `authsources.php` 例:

```php
'2faskwsp' => [
    'saml:SP',
    'entityID' => 'https://sp.example.com/simplesaml/sp',
    'idp' => 'https://idp.example.com/idp/shibboleth',
    'privatekey' => 'sp.example.com.key',
    'certificate' => 'sp.example.com.crt',
],
```

SP側の `entityID`、証明書、ACS URL、SLO URLがIdPへ登録したmetadataと
一致していない場合、ログイン後の応答処理やSLOでエラーになる。

### 16.3 IdP側 metadata反映と確認

この項目はIdP側で作業する。

```bash
sudo systemctl restart jetty-idp.service

sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log | grep -iE \
  'SP2faskwTest|2faskw_sp|metadata|error|exception'
```

metadata関連のERRORが出ていないことを確認する。
SPのログインURLからIdPへ遷移し、Password認証後に2FAS-KW MFAが要求されることを確認する。

### 16.4 SLO トラブルシューティング

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

### 16.5 動作確認結果

- **SSO**: `Principal: user001`にてLDAP Password → GraphicalMatrix MFA完了。
  `schacHomeOrganization`属性を取得。
- **SLO**: IdP監査ログで`||Success||`を確認。
  Binding: HTTP-Redirect / HTTP-Redirect。

## 17. DB/HA反映状況と残作業

### 17.1 実施済み

- PostgreSQL監視/HA構成のDB1/DB2構築
  - 詳細: `INSTALL_DB.md`
  - DB1: `192.0.2.62`
  - DB2: `192.0.2.63`
  - DB VIP: `192.0.2.64`
  - 構成: PostgreSQL Streaming Replication + HAProxy + Keepalived
  - 注意: 2台構成のため自動昇格は行わず、DB2昇格は手動運用とする
- IdP `192.0.2.60` の `db.properties` をDB VIPへ切替済み
  - 変更前: `jdbc:postgresql://127.0.0.1:5432/graphicalmatrix`
  - 変更後: `jdbc:postgresql://192.0.2.64:5432/graphicalmatrix`
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

### 17.2 未実施事項

- PostgreSQLバックアップ
- LDAP Checker資格情報の更新と実LDAPユーザーログイン試験
- 証明書更新・期限監視の自動化
- 利用クライアントへのIIC CA証明書配布
- パスワードレスsudoの無効化

## 18. firewalld SSH接続元制限

IdP/APサーバ `192.0.2.60` のSSH接続元を
`192.0.2.0/24` のみに制限した。

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
  --add-rich-rule='rule family="ipv4" source address="192.0.2.0/24" service name="ssh" accept'

sudo firewall-cmd --permanent --zone=public --remove-service=ssh
sudo firewall-cmd --reload
```

確認コマンド:

```bash
sudo firewall-cmd --permanent --zone=public --query-service=ssh
sudo firewall-cmd --permanent --zone=public \
  --query-rich-rule='rule family="ipv4" source address="192.0.2.0/24" service name="ssh" accept'
sudo firewall-cmd --zone=public --list-all
```

確認結果:

```text
query-service=ssh: no
query-rich-rule: yes
```

この設定により、SSHは `192.0.2.0/24` からのみ許可される。
HTTPS等の既存公開サービスは変更していない。

## 19. IdP/APパフォーマンスチューニング設定例

### 19.1 前提

この章は、将来の本番投入前に検討する設定例である。
未適用。

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
graphicalmatrix.db.url=jdbc:postgresql://192.0.2.64:5432/graphicalmatrix
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
22/tcp: 192.0.2.0/24のみ
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
