# Install Guide

この文書は 2FAS-KW Plugin 配布物を Shibboleth IdP 5 へ導入する手順です。

この配布物は内部配布用です。
署名済み公式Shibboleth plugin packageとしての外部配布は未完成です。

## 0. 配布物構成と必須アプリ

### IdP Plugin 配布物

配布 ZIP を展開すると、以下の構成になります。

```text
2faskw-idp-plugin-1.0.1/
  LICENSE
  NOTICE
  THIRD-PARTY-NOTICES.md

  webapp/WEB-INF/lib/
    2faskw-idp-plugin-1.0.1.jar
    core-3.5.3.jar
    HikariCP-6.3.0.jar
    postgresql-42.7.11.jar

  bootstrap/
    plugin.properties
    keys.txt

  conf/graphicalmatrix/
    graphicalmatrix.properties.idpnew
    db.properties.idpnew
    api.properties.idpnew
    mfa-policy.properties.idpnew
    postgresql-schema.sql

  conf/graphicalmatrix/assets/
    graphicalmatrix.css.idpnew

  conf/graphicalmatrix/views/
    *.html.idpnew

  conf/graphicalmatrix/graphicals/
    img01.svg - img25.svg

  bin/
    graphicalmatrix-db.sh
    graphicalmatrix-db-migration.sh
    graphicalmatrix-api-token.sh
    graphicalmatrix-api-curl-test.sh
    graphicalmatrix-plugin-check.sh
    graphicalmatrix-plugin-config.sh
    graphicalmatrix-plugin-uninstall.sh
    graphicalmatrix-plugin-webxml.sh

  examples/
    web.xml.current-poc.xml
    mfa-authn-config.xml
    totp-authn-config.xml
    webauthn-management-config.xml
    webauthn-registration-config.xml
    access-control.xml
    attribute-resolver.xml
    logrotate/
      graphicalmatrix-audit

  plugin-metadata/
    README.md
    graphicalmatrix-plugin.properties
    PACKAGE-CONTENTS.txt
    PACKAGE-MANIFEST.sha256

  docs/
    README.md
    INSTALL.md
    SECURITY.md
    SECURITY-CHECKLIST.md
    build.md
    CONFIG-REFERENCE.md
    API-TOKEN-ROTATION.md
    API-CURL-TESTS.md
    ADMIN-TOOLS.md
    CSV-EXPORT.md
    DB-MIGRATION.md
    SEQUENCE-STORAGE-MIGRATION.md
    LOGROTATE.md
    INSTALL_LOADTEST.md
    openapi.yaml
```

### Admin Tools 配布物

管理CLIだけをDBサーバまたは管理端末へ導入する場合は、
`2faskw-admin-tools-1.0.1.zip` を利用します。

```text
2faskw-admin-tools-1.0.1/
  bin/
    graphicalmatrix-db.sh
    graphicalmatrix-db-migration.sh
    graphicalmatrix-admin-install.sh

  lib/
    2faskw-idp-plugin-1.0.1.jar
    core-3.5.3.jar
    HikariCP-6.3.0.jar
    postgresql-42.7.11.jar

  conf/graphicalmatrix/
    db.properties.adminnew
    graphicalmatrix.properties.adminnew
    postgresql-schema.sql

  docs/
    ADMIN-TOOLS.md
    CSV-EXPORT.md
    DB-MIGRATION.md
    SEQUENCE-STORAGE-MIGRATION.md
    SECURITY.md
    SECURITY-CHECKLIST.md
```

Admin ToolsはJetty、`web.xml`、Shibboleth IdP設定を変更しません。
別サーバで動かす場合は、IdP側と同じDB接続設定、`graphicalmatrix.properties`、
sequence保存用secretを配置してください。

### 必須アプリ

IdP 実行環境:

- Shibboleth IdP 5.2 系
- Java 21 実行環境
- Jetty 12
- PostgreSQL 推奨
- H2 はPoC/検証用途のみ
- PostgreSQL JDBC driverは配布物の `webapp/WEB-INF/lib/` に同梱

ビルド環境:

- Java 21
- Maven 3.9 系
- Python 3
- curl

MFA連携:

- GraphicalMatrixのみの場合、追加のShibboleth MFAプラグインは不要
- TOTPを利用する場合、Shibboleth TOTP Plugin が必要
- WebAuthnを利用する場合、Shibboleth WebAuthn Plugin が必要

管理APIを使う場合:

- HTTPS
- API用Bearer token
- 管理ネットワークまたはIP制限
- FirewallまたはLBによるアクセス制限
- `docs/openapi.yaml` に定義されたAPI仕様の確認
- `docs/API-TOKEN-ROTATION.md` に定義されたtoken rotation手順の確認
- `docs/API-CURL-TESTS.md` に定義されたcurlテスト手順の確認

管理CLIだけを使う場合:

- Java 21
- psql
- bash
- DB接続先へのfirewalld許可

## 1. 事前確認

対象:

- Shibboleth IdP 5.2系
- Java 21
- Jetty 12
- PostgreSQL推奨
- H2はPoC / 検証用途のみ

作業前に取得するもの:

- IdP設定バックアップ
- `edit-webapp` バックアップ
- `web.xml` バックアップ
- DBバックアップ
- 現在のPlugin ZIP

確認:

```bash
/opt/shibboleth-idp/bin/build.sh --help >/dev/null
test -f /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml
```

## 2. 配布物展開

```bash
cd /tmp
unzip 2faskw-idp-plugin-1.0.1.zip
cd 2faskw-idp-plugin-1.0.1
```

## 3. 配布物の完全性確認

配布物には以下が含まれます。

```text
plugin-metadata/PACKAGE-CONTENTS.txt
plugin-metadata/PACKAGE-MANIFEST.sha256
plugin-metadata/graphicalmatrix-plugin.properties
```

checksum確認:

```bash
sha256sum -c plugin-metadata/PACKAGE-MANIFEST.sha256
```

macOSなど `sha256sum` がない環境では `shasum -a 256` を利用してください。

注意:

- `PACKAGE-MANIFEST.sha256` は展開後のファイル確認用です
- 外部配布時は別途、配布ZIP自体の署名と公開鍵検証を用意してください
- `bootstrap/keys.txt` はPoC placeholderです

## 4. package check

```bash
./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/2faskw-idp-plugin-1.0.1
```

期待値:

```text
summary: failures=0
```

TOTP / WebAuthn を使わない場合、それらのoptional plugin未検出WARNは許容できます。

## 5. plugin files dry-run

```bash
./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/2faskw-idp-plugin-1.0.1
```

確認すること:

- `edit-webapp/WEB-INF/lib/` へ配置されるJAR
- `conf/graphicalmatrix/*.idpnew` の配置
- `bin/` へ配置される管理スクリプト
- 既存ファイルがある場合のbackup / `.idpnew.TIMESTAMP`

## 6. plugin files apply

```bash
sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/2faskw-idp-plugin-1.0.1 \
  --apply
```

配置される主なJAR:

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.0.1.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/core-3.5.3.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/HikariCP-6.3.0.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/postgresql-42.7.11.jar
```

## 7. 設定ファイル確認

既存設定がある場合、導入スクリプトは上書きせず `.idpnew.TIMESTAMP` を配置します。
差分確認して必要な設定だけ反映してください。

全設定項目の詳細は以下を参照してください。

```text
docs/CONFIG-REFERENCE.md
```

確認対象:

```text
/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
/opt/shibboleth-idp/conf/graphicalmatrix/db.properties
/opt/shibboleth-idp/conf/graphicalmatrix/api.properties
/opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
```

管理APIは初期状態で無効にしてください。

```properties
graphicalmatrix.api.enabled = false
graphicalmatrix.api.allowedCidrs = 127.0.0.1/32,192.168.0.0/24
graphicalmatrix.api.bearerTokenFile = /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
graphicalmatrix.api.authFailureLimit = 5
graphicalmatrix.api.authFailureWindowSeconds = 60
graphicalmatrix.api.authFailureLockSeconds = 300
graphicalmatrix.api.response.excludeSequences = true
graphicalmatrix.api.sequence.requireProtectedStorage = true
```

GraphicalMatrixチャレンジ有効期限は `graphicalmatrix.properties` で設定できます。

```properties
graphicalmatrix.challenge.seconds = 180
```

設定可能範囲は30〜900秒です。

MFAポリシーは `mfa-policy.properties` で設定します。

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.bypassSPs =
graphicalmatrix.mfa.requiredSPs =
graphicalmatrix.mfa.bypassIPs =
graphicalmatrix.mfa.bypassCIDRs =
graphicalmatrix.mfa.useForwardedFor = false
```

`useForwardedFor=true` は、信頼済みReverse Proxy/LBが送信元IPヘッダを
安全に上書きし、IdPへの直接接続をFirewall/LBで遮断している構成でのみ
利用してください。直接接続が残っている場合、クライアントが
`X-Forwarded-For` / `X-Real-IP` を偽造してMFAバイパスIP/CIDR判定を
すり抜ける可能性があるため、`false` のままにしてください。

## 8. DB設定

PostgreSQLを利用する場合:

```bash
psql -h <DB_HOST> -U <DDL_USER> -d graphicalmatrix \
  -f /opt/shibboleth-idp/conf/graphicalmatrix/postgresql-schema.sql
```

DBスキーマは事前適用し、IdP実行時のDDL自動実行は無効にする。

```properties
graphicalmatrix.db.autoInit = false
```

IdP runtime servlet、管理API、TOTP seed参照はHikariCP接続プールを利用する。

```properties
graphicalmatrix.db.pool.enabled = true
graphicalmatrix.db.pool.maximumPoolSize = 10
graphicalmatrix.db.pool.minimumIdle = 2
graphicalmatrix.db.pool.connectionTimeoutMillis = 30000
graphicalmatrix.db.pool.idleTimeoutMillis = 600000
graphicalmatrix.db.pool.maxLifetimeMillis = 1800000
graphicalmatrix.db.pool.validationTimeoutMillis = 5000
```

H2からPostgreSQLへ移行する場合:

```text
docs/DB-MIGRATION.md
```

sequence保存方式を変更する場合:

```text
docs/SEQUENCE-STORAGE-MIGRATION.md
```

PoCでH2を利用する場合は、`db.properties` をH2向けにしてください。

## 9. web.xml dry-run

```bash
./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --install
```

既存PoC設定がある場合は、二重追加せず `existing_manual_entries_detected` になります。

## 10. web.xml apply

```bash
sudo ./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --install \
  --apply
```

バックアップ:

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml.bak.TIMESTAMP
```

## 11. IdP rebuild

```bash
sudo /opt/shibboleth-idp/bin/build.sh
```

## 12. Jetty restart

systemd例:

```bash
sudo systemctl restart jetty
```

環境固有の起動方式がある場合は、その手順に従ってください。

## 13. 動作確認

変更画面:

```bash
curl -s -o /tmp/im-change.out -w "change_http=%{http_code}\n" \
  http://127.0.0.1:8080/idp/graphicalmatrix/change
```

管理CLI:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
```

API無効確認:

```bash
curl -s -o /tmp/im-api.out -w "api_http=%{http_code}\n" \
  http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1/health
```

期待値:

```text
api_http=404
```

APIを有効化する環境では、以下も確認します。

```text
docs/API-CURL-TESTS.md
```

読み取りテスト:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh \
  --base-url http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1 \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

書き込みテスト:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh \
  --base-url http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1 \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token \
  --write \
  --user api-user001
```

## 14. ログ確認

```bash
sudo tail /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

logrotate設定例:

```text
docs/LOGROTATE.md
examples/logrotate/graphicalmatrix-audit
```

## 15. Admin Tools単体インストール

管理CLIだけをDBサーバまたは管理端末へ導入する場合は、
`2faskw-admin-tools-1.0.1.zip` を利用する。

この導入では、Shibboleth IdP、Jetty、`web.xml` は変更しない。

```bash
cd /tmp
unzip 2faskw-admin-tools-1.0.1.zip
cd 2faskw-admin-tools-1.0.1
```

dry-run:

```bash
sudo ./bin/graphicalmatrix-admin-install.sh \
  --prefix /opt/graphicalmatrix-admin
```

適用:

```bash
sudo ./bin/graphicalmatrix-admin-install.sh \
  --prefix /opt/graphicalmatrix-admin \
  --apply
```

DB接続設定:

```text
/opt/graphicalmatrix-admin/conf/graphicalmatrix/db.properties
```

確認:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh list
```

詳細:

```text
docs/ADMIN-TOOLS.md
```

## 16. 主要設定例

全設定項目の意味、型、既定値、注意点は `docs/CONFIG-REFERENCE.md` を参照してください。
Admin Toolsでは以下でも確認できます。

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh config-help
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh config-help graphicalmatrix.challenge.seconds
```

### graphicalmatrix.properties

```properties
graphicalmatrix.columns = 5
graphicalmatrix.rows = 5
graphicalmatrix.place = /opt/shibboleth-idp/edit-webapp/graphicalmatrix/graphicals
graphicalmatrix.graphicals = img01-25
graphicalmatrix.not_graphicals =
graphicalmatrix.aliases = A:img01,B:img02,C:img03,D:img04,E:img05,F:img06,G:img07,H:img08,I:img09,J:img10,K:img11,L:img12,M:img13,N:img14,O:img15,P:img16,R:img17,S:img18,T:img19,U:img20,V:img21,W:img22,X:img23,Y:img24,Z:img25
graphicalmatrix.choice = 4
graphicalmatrix.order = 1
graphicalmatrix.challenge.seconds = 180
graphicalmatrix.allow_duplicates = 0
graphicalmatrix.force_sequence_change = 1

graphicalmatrix.change.ldapRateLimit.enabled = true
graphicalmatrix.change.ldapRateLimit.failureLimit = 5
graphicalmatrix.change.ldapRateLimit.windowSeconds = 300
graphicalmatrix.change.ldapRateLimit.lockSeconds = 900
graphicalmatrix.change.ldapRateLimit.key = ip-user

graphicalmatrix.sequence.storage = plaintext
# graphicalmatrix.sequence.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword
# graphicalmatrix.sequence.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key
# graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

graphicalmatrix.productionMode = false
graphicalmatrix.totp.seed.storage = auto
# graphicalmatrix.totp.seed.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword
# graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key

graphicalmatrix.view.template.enabled = true
graphicalmatrix.view.template = /opt/shibboleth-idp/conf/graphicalmatrix/views/graphicalmatrix.html
graphicalmatrix.view.lockedTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/locked.html
graphicalmatrix.view.unavailableTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/unavailable.html
graphicalmatrix.view.totpRegisterTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/totp-register.html
graphicalmatrix.view.changeStartTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/change-start.html
graphicalmatrix.view.changeCurrentTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/change-current.html
graphicalmatrix.view.changeMenuTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/change-menu.html
graphicalmatrix.view.changeNewTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/change-new.html
graphicalmatrix.view.changeMethodTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/change-method.html
graphicalmatrix.view.changeCompleteTemplate = /opt/shibboleth-idp/conf/graphicalmatrix/views/change-complete.html
graphicalmatrix.view.css.enabled = true
graphicalmatrix.view.css = /opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css
graphicalmatrix.view.css.cacheSeconds = 0
```

`graphicalmatrix.challenge.seconds` はGraphicalMatrix、TOTP登録、強制sequence変更、
ユーザー自身の変更画面で利用するチャレンジ有効期限です。設定可能範囲は30〜900秒です。

Sequence保存方式:

```properties
graphicalmatrix.sequence.storage = keyword
graphicalmatrix.sequence.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword

graphicalmatrix.sequence.storage = aes-gcm
graphicalmatrix.sequence.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key

graphicalmatrix.sequence.storage = hash
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper
```

TOTP seed保存方式:

```properties
graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

本番推奨は `sequence.storage = hash`、`totp.seed.storage = aes-gcm`、
`graphicalmatrix.productionMode = true` です。

### db.properties

PostgreSQL例:

```properties
graphicalmatrix.db.driver = org.postgresql.Driver
graphicalmatrix.db.url = jdbc:postgresql://127.0.0.1:5432/graphicalmatrix
graphicalmatrix.db.user = graphicalmatrix_app
graphicalmatrix.db.passwordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
graphicalmatrix.db.autoInit = false
graphicalmatrix.db.pool.enabled = true
graphicalmatrix.db.pool.maximumPoolSize = 10
graphicalmatrix.db.pool.minimumIdle = 2
graphicalmatrix.db.pool.connectionTimeoutMillis = 30000
graphicalmatrix.db.pool.idleTimeoutMillis = 600000
graphicalmatrix.db.pool.maxLifetimeMillis = 1800000
graphicalmatrix.db.pool.validationTimeoutMillis = 5000
```

`graphicalmatrix.db.autoInit` はPoC/bootstrap用のDDL自動実行設定です。
本番では `postgresql-schema.sql` を事前適用し、`false` のまま運用してください。
IdPを複数台構成にする場合は、`maximumPoolSize * IdP台数` がPostgreSQL / HAProxy側の許容接続数を超えないように設定してください。

### api.properties

初期状態:

```properties
graphicalmatrix.api.enabled = false
graphicalmatrix.api.allowedCidrs = 127.0.0.1/32,192.168.0.0/24
graphicalmatrix.api.bearerTokenFile = /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
graphicalmatrix.api.authFailureLimit = 5
graphicalmatrix.api.authFailureWindowSeconds = 60
graphicalmatrix.api.authFailureLockSeconds = 300
graphicalmatrix.api.response.excludeSequences = true
graphicalmatrix.api.sequence.requireProtectedStorage = true
```

APIを有効化する場合は、token file、CIDR制限、HTTPS、Firewall/LB制限、
token rotation手順を確認してから `graphicalmatrix.api.enabled = true` にしてください。

API token状態確認:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh status
```

API tokenローテーション:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate --apply --print-token
```

### mfa-policy.properties

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.bypassSPs =
graphicalmatrix.mfa.requiredSPs =
graphicalmatrix.mfa.bypassIPs =
graphicalmatrix.mfa.bypassCIDRs =
graphicalmatrix.mfa.useForwardedFor = false
```

`useForwardedFor=true` は、LB/Reverse Proxyが `X-Forwarded-For` または
`X-Real-IP` を正しく上書きし、利用者がIdPへ直接接続できない構成でのみ利用してください。

### admin.properties

Admin Tools単体配布物で使う設定です。

```properties
graphicalmatrix.admin.enabled = false
graphicalmatrix.admin.requiredGroup = graphicalmatrix-admin
graphicalmatrix.admin.allowedHosts =
graphicalmatrix.admin.requireClientCert = false
graphicalmatrix.admin.clientCertPath = /opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.crt
graphicalmatrix.admin.productionMode = true
graphicalmatrix.admin.rejectPlaintextSequence = true

graphicalmatrix.admin.provisioning.enabled = false
graphicalmatrix.admin.csv.incomingDir = /opt/graphicalmatrix-admin/incoming
graphicalmatrix.admin.csv.processingDir = /opt/graphicalmatrix-admin/processing
graphicalmatrix.admin.csv.processedDir = /opt/graphicalmatrix-admin/processed
graphicalmatrix.admin.csv.failedDir = /opt/graphicalmatrix-admin/failed
graphicalmatrix.admin.csv.logFile = /opt/graphicalmatrix-admin/logs/csv-import.log
graphicalmatrix.admin.csv.lockFile = /opt/graphicalmatrix-admin/logs/csv-import.lock
graphicalmatrix.admin.csv.autoApply = false
graphicalmatrix.admin.csv.autoApplyActions = A,M
graphicalmatrix.admin.csv.autoApplyDeprovision = true
graphicalmatrix.admin.csv.maxRows = 10000
graphicalmatrix.admin.csv.maxDisables = 1000
```

### WebAuthn properties

WebAuthnプラグインを使う場合の代表項目です。FQDN/HTTPSが正しく設定されていることが前提です。

```properties
idp.authn.webauthn.relyingPartyId = idp.example.com
idp.authn.webauthn.relyingPartyName = Example IdP
idp.authn.webauthn.supportedPrincipals = \
    saml2/http://example.org/ac/classes/mfa, \
    saml1/http://example.org/ac/classes/mfa

idp.authn.webauthn.StorageService = shibboleth.StorageService
idp.authn.webauthn.StorageService.cache.enable = true
idp.authn.webauthn.StorageService.cache.expireAfterAccess = PT60M
idp.authn.webauthn.StorageService.jdbcAccelerator = WebAuthnJDBCAccelerator
idp.authn.webauthn.StorageService.jdbcAccelerator.defaultType = QUERY

idp.authn.webauthn.2fa.enabled = true
idp.authn.webauthn.2fa.allowedPreviousFactors = authn/Password
```

### enrollments.properties

`enrollments.properties` は初期PoCで使っていたファイルベース登録情報です。
現在のDB運用では利用せず、互換確認用のレガシーファイルとして扱います。

```properties
user001.sequence=img03,img07,img11,img14
user001.failedCount=0
user001.lockedUntil=0
```

本番/DB構成では、ユーザー登録、失敗回数、ロック状態は
`graphicalmatrix_enrollment` テーブルで管理します。

### 管理CLI例

ユーザー追加:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh add user001 A B C D
```

MFA方式変更:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method user001 TOTP
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method user001 GraphicalMatrix
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method user001 WebAuthn
```

RESET:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh user001 RESET
```

CSV一括登録:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv
```

CSV形式:

```csv
action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence
A,user001,GraphicalMatrix,on,"A,B,C,D","J,Z,W,P"
M,user001,TOTP,off,"A,B,C,D","img05,img06,img07,img08"
D,user001
```

CSVエクスポート:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export /secure/path/graphicalmatrix-users.csv
```

詳細は `docs/ADMIN-TOOLS.md` と `docs/CSV-EXPORT.md` を参照してください。

## 17. 運用補足

### アンインストール

dry-run:

```bash
./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp
```

適用:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply
```

設定も退避する場合:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply \
  --remove-config
```

注意:

- DBデータは削除しません
- audit logは削除しません
- web.xmlのmarker block削除は `graphicalmatrix-plugin-webxml.sh --remove --apply` で行います

### web.xmlからの削除

```bash
sudo ./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --remove \
  --apply
```

### 本番導入時の注意

- H2ではなくPostgreSQLを利用する
- APIは必要な場合だけ有効化する
- APIはインターネット公開しない
- TOTP / WebAuthn を使う場合は、対応するShibboleth pluginを事前に導入する
- WebAuthnはHTTPS + FQDNが必須
- 複数IdP構成では共通PostgreSQLと設定同期を行う
- Plugin更新前にDBとIdP設定をバックアップする

### 監査ログのlogrotate

GraphicalMatrix監査ログ:

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

設定例:

```text
docs/LOGROTATE.md
examples/logrotate/graphicalmatrix-audit
```

適用例:

```bash
sudo install -m 0644 examples/logrotate/graphicalmatrix-audit \
  /etc/logrotate.d/graphicalmatrix-audit
sudo logrotate -d /etc/logrotate.d/graphicalmatrix-audit
```

logrotateはOS側の設定です。
プラグイン導入スクリプトでは `/etc/logrotate.d/` へ自動配置しません。

### 現時点の制限

- 内部配布用plugin packageであり、署名済み公式plugin packageではありません
- `bootstrap/keys.txt` はplaceholderです
- web.xml適用は専用スクリプトで行います
- DB migration自動適用は行いません
- Jetty restartは自動実行しません
- sequence保存方式は設定で切替できますが、既存データの一括再保存は `docs/SEQUENCE-STORAGE-MIGRATION.md` の手順で行います

## 18. rollback

1. Jettyを停止する
2. `graphicalmatrix-plugin-webxml.sh --remove --apply` を実行する
3. `graphicalmatrix-plugin-uninstall.sh --apply` を実行する
4. 必要に応じて `web.xml.bak.TIMESTAMP` を戻す
5. `build.sh` を実行する
6. Jettyを起動する

web.xml marker削除:

```bash
sudo ./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --remove \
  --apply
```

plugin overlay削除:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply
```

設定も退避する場合:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply \
  --remove-config
```

runtime依存JARも削除する場合:

```bash
sudo ./bin/graphicalmatrix-plugin-uninstall.sh \
  --idp-home /opt/shibboleth-idp \
  --apply \
  --remove-runtime-deps
```

## 19. install記録テンプレート

```text
作業日:
作業者:
対象IdP:
Plugin ZIP:
PACKAGE-MANIFEST確認:
事前check結果:
config dry-run結果:
web.xml dry-run結果:
build.sh結果:
Jetty restart結果:
/idp/graphicalmatrix/change確認:
管理CLI確認:
API無効確認:
rollback要否:
備考:
```
