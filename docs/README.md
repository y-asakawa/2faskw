# GraphicalMatrix MFA Plugin for Shibboleth IdP

## 概要

GraphicalMatrix MFA Plugin は、Shibboleth IdP 5 に GraphicalMatrix 方式の多要素認証を追加するためのプラグインです。

現在の構成では、Shibboleth IdP の Password 認証後に、DB の登録情報を参照して以下の MFA 方式へ分岐できます。

- GraphicalMatrix
- TOTP
- WebAuthn

GraphicalMatrix では、ユーザーごとに登録された画像 sequence を選択して認証します。
ユーザー本人による GraphicalMatrix sequence 変更画面、TOTP 方式選択、管理CLI、任意の管理APIも含みます。

管理APIはセキュリティ上のリスクが高いため、配布物では初期状態で無効です。

## License

This project is licensed under the Apache License, Version 2.0.

Release packages include third-party runtime libraries. See the repository
root files `LICENSE`, `NOTICE`, and `THIRD-PARTY-NOTICES.md`.

```properties
graphicalmatrix.api.enabled = false
```

APIを利用する場合は、Bearer token、接続元IP制限、HTTPS、Firewall/LB制限を確認した後に有効化してください。

署名済みplugin packageとして配布する将来手順は、以下にまとめています。

```text
docs/SIGNED-PLUGIN-PACKAGE.md
```

## ファイル構成と必須アプリ

### 配布物構成

配布zipを展開すると、以下の構成になります。

```text
graphicalmatrix-idp-plugin-1.0.1/
  LICENSE
  NOTICE
  THIRD-PARTY-NOTICES.md

  webapp/WEB-INF/lib/
    graphicalmatrix-idp-plugin-1.0.1.jar
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
    API-TOKEN-ROTATION.md
    API-CURL-TESTS.md
    ADMIN-TOOLS.md
    CSV-EXPORT.md
    DB-MIGRATION.md
    SEQUENCE-STORAGE-MIGRATION.md
    SIGNED-PLUGIN-PACKAGE.md
    LOGROTATE.md
    INSTALL_LOADTEST.md
    openapi.yaml
```

管理CLIだけをDBサーバまたは管理端末へ導入する場合は、別配布物を利用します。

```text
graphicalmatrix-admin-tools-1.0.1/
  bin/
    graphicalmatrix-db.sh
    graphicalmatrix-db-migration.sh
    graphicalmatrix-admin-install.sh

  lib/
    graphicalmatrix-idp-plugin-1.0.1.jar
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
これらが一致しないと、alias、選択数、画像許可リスト、sequence保存形式が
IdP認証時の判定とずれる可能性があります。
詳細は `docs/ADMIN-TOOLS.md` を参照してください。

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

## インストール方法

### 1. 配布zipを展開

```bash
cd /tmp
unzip graphicalmatrix-idp-plugin-1.0.1.zip
cd graphicalmatrix-idp-plugin-1.0.1
```

### 2. 事前チェック

配布物のファイル一覧とchecksumを確認します。

```bash
sha256sum -c plugin-metadata/PACKAGE-MANIFEST.sha256
```

導入詳細は以下を参照してください。

```text
docs/INSTALL.md
```

```bash
./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/graphicalmatrix-idp-plugin-1.0.1
```

`failures=0` であることを確認します。
TOTP / WebAuthn が未導入の場合は WARN が出ますが、GraphicalMatrixのみ利用する場合は問題ありません。

### 3. 導入dry-run

```bash
./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/graphicalmatrix-idp-plugin-1.0.1
```

出力されるコピー先、バックアップ予定、`.idpnew` 配置予定を確認します。

### 4. Pluginファイルを適用

```bash
sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --package-dir /tmp/graphicalmatrix-idp-plugin-1.0.1 \
  --apply
```

既存設定がある場合は上書きせず、`.idpnew.TIMESTAMP` として配置されます。
必要に応じて既存設定と差分確認してください。

`webapp/WEB-INF/lib/` 配下の配布JARはすべてIdPの `edit-webapp/WEB-INF/lib/` へ配置されます。
PostgreSQL利用時に必要な `postgresql-42.7.11.jar` もここで配置されます。

### 5. web.xml dry-run

```bash
./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --install
```

既存PoCの手動設定がある場合は、二重追加せず `existing_manual_entries_detected` になります。

### 6. web.xml 適用

```bash
sudo ./bin/graphicalmatrix-plugin-webxml.sh \
  --idp-home /opt/shibboleth-idp \
  --install \
  --apply
```

変更前のファイルは以下の形式でバックアップされます。

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml.bak.TIMESTAMP
```

### 7. DBスキーマ適用

PostgreSQLを利用する場合は、事前にDBを作成し、以下を適用します。

```bash
psql -h <DB_HOST> -U <DB_USER> -d <DB_NAME> \
  -f /opt/shibboleth-idp/conf/graphicalmatrix/postgresql-schema.sql
```

DB接続情報は以下に設定します。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/db.properties
```

H2からPostgreSQLへ移行する場合は、以下を参照してください。

```text
docs/DB-MIGRATION.md
```

補助スクリプト:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh h2-count
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh h2-export --output /tmp/graphicalmatrix-migration.csv
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-apply-schema --apply
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-import --input /tmp/graphicalmatrix-migration.csv --apply
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db-migration.sh pg-verify --input /tmp/graphicalmatrix-migration.csv
```

### 8. IdP webapp rebuild

```bash
sudo /opt/shibboleth-idp/bin/build.sh
```

### 9. Jetty restart

環境に合わせてJettyを再起動します。

例:

```bash
sudo systemctl restart jetty
```

PoC環境で `jetty.sh` を使っている場合は、その環境の手順に従ってください。

### 10. 動作確認

```bash
curl -s -o /tmp/im-change.out -w "change_http=%{http_code}\n" \
  http://127.0.0.1:8080/idp/graphicalmatrix/change
```

期待値:

```text
change_http=200
```

管理APIをインストールしていても、初期状態では無効です。

```bash
curl -s -o /tmp/im-api.out -w "api_http=%{http_code}\n" \
  http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1/health
```

期待値:

```text
api_http=404
```

## Admin Tools単体インストール

DBサーバまたは管理端末へ管理CLIだけを入れる場合は、IdPプラグインzipではなく
`graphicalmatrix-admin-tools-1.0.1.zip` を利用します。

```bash
cd /tmp
unzip graphicalmatrix-admin-tools-1.0.1.zip
cd graphicalmatrix-admin-tools-1.0.1
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

重要:

- `db.properties` はIdPが参照するDBと同じDB VIP/Primaryへ接続する
- `graphicalmatrix.properties` はIdP側と同じ内容にする
- `graphicalmatrix.sequence.storage` が `keyword` / `aes-gcm` / `hash` の場合は、
  `keywordFile` / `aesKeyFile` / `pepperFile` もAdmin Tools側へ安全に配置する

実行例:

```bash
/opt/graphicalmatrix-admin/bin/graphicalmatrix-db.sh list
```

詳細:

```text
docs/ADMIN-TOOLS.md
```

## 設定例

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

# Self-service change LDAP authentication rate limit.
graphicalmatrix.change.ldapRateLimit.enabled = true
graphicalmatrix.change.ldapRateLimit.failureLimit = 5
graphicalmatrix.change.ldapRateLimit.windowSeconds = 300
graphicalmatrix.change.ldapRateLimit.lockSeconds = 900
graphicalmatrix.change.ldapRateLimit.key = ip-user

# Sequence storage. Default keeps PoC backward compatibility.
graphicalmatrix.sequence.storage = plaintext
# graphicalmatrix.sequence.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword
# graphicalmatrix.sequence.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key
# graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

# Runtime production guard and TOTP seed storage.
graphicalmatrix.productionMode = false
graphicalmatrix.totp.seed.storage = auto
# graphicalmatrix.totp.seed.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword
# graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key

# View customization.
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

`graphicalmatrix.challenge.seconds` はGraphicalMatrix、TOTP登録、強制sequence変更、ユーザー自身の変更画面で利用するチャレンジ有効期限です。
設定可能範囲は30〜900秒です。

Sequence保存方式:

```properties
# 3. 共通キーワード暗号化。復号可能。
graphicalmatrix.sequence.storage = keyword
graphicalmatrix.sequence.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword

# 2. AES-GCM暗号化。復号可能。
graphicalmatrix.sequence.storage = aes-gcm
graphicalmatrix.sequence.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key

# 1. hash + salt + pepper。復号不可。本番推奨。
graphicalmatrix.sequence.storage = hash
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper
```

TOTP seed保存方式:

```properties
# autoはsequenceのaes-gcm/keyword/plaintextを継承する。
# sequenceがhashの場合は復号不能なため、TOTP seed側は明示設定が必要。
graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

本番推奨は `sequence.storage = hash`、`totp.seed.storage = aes-gcm`、
`graphicalmatrix.productionMode = true` です。

切替時の注意:

- 既存のplaintext sequenceは後方互換で認証できます
- 新規登録、sequence変更、RESET、API更新、CSV登録時に現在の保存方式で保存されます
- `hash` は復号不可のため、API応答の `sequence` は空配列になり、`sequenceRecoverable` は `false` になります
- 管理CLIで確認する場合は `graphicalmatrix-db.sh sequence-mode` で現在の保存方式を確認できます

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
IdP runtime servlet、管理API、TOTP seed参照はHikariCPによるDB接続プールを利用します。
短命の管理CLI処理は直接JDBC接続を利用します。
IdPを複数台構成にする場合は、`maximumPoolSize * IdP台数` がPostgreSQL / HAProxy側の許容接続数を超えないように設定してください。

### api.properties

初期状態:

```properties
graphicalmatrix.api.enabled = false
graphicalmatrix.api.allowedCidrs = 127.0.0.1/32,192.0.2.0/24
graphicalmatrix.api.bearerTokenFile = /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
graphicalmatrix.api.authFailureLimit = 5
graphicalmatrix.api.authFailureWindowSeconds = 60
graphicalmatrix.api.authFailureLockSeconds = 300
graphicalmatrix.api.response.excludeSequences = true
graphicalmatrix.api.sequence.requireProtectedStorage = true
```

APIを有効化する場合:

```properties
graphicalmatrix.api.enabled = true
```

API有効化前に必ず確認すること:

- token file が存在する
- token file が `root:jetty 0640` など安全な権限である
- allowedCidrs が管理ネットワークに限定されている
- Bearer token認証失敗時のレート制限を有効にしている
- `graphicalmatrix.sequence.storage` が `hash`、`keyword`、`aes-gcm` のいずれかである
- APIレスポンスでは標準で `initialSequence` / `sequence` を除外している
- HTTPSでアクセスできる
- Firewall/LBでアクセス元を制限している
- token rotation手順を確認している

API token状態確認:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh status
```

API tokenローテーション:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate --apply --print-token
```

### mfa-policy.properties

SP単位/IP単位でMFA要否を制御する設定です。

```properties
# require: 原則MFAを要求し、bypassルールに一致した場合だけ回避
# bypass : 原則MFAを回避し、requiredSPsに一致した場合だけ要求
graphicalmatrix.mfa.default = require

# 常にMFAを回避するSP entityID
graphicalmatrix.mfa.bypassSPs =

# 空でなければ、このSP entityIDだけMFAを要求する
graphicalmatrix.mfa.requiredSPs =

# 完全一致IPでMFAを回避する
graphicalmatrix.mfa.bypassIPs =

# CIDRでMFAを回避する
graphicalmatrix.mfa.bypassCIDRs =

# 信頼済みReverse Proxy/LB経由のみの接続が保証されている場合だけtrueにする
graphicalmatrix.mfa.useForwardedFor = false
```

`useForwardedFor=true` は、LB/Reverse Proxyが `X-Forwarded-For` または
`X-Real-IP` を正しく上書きし、利用者がIdPへ直接接続できない構成でのみ利用してください。
直接接続が可能な構成では、クライアントが送信元IPヘッダを偽造できるため、
MFAバイパスIP/CIDR判定に悪用される可能性があります。その場合は必ず
`graphicalmatrix.mfa.useForwardedFor = false` のまま運用してください。

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

本番では `enabled=true` にする前に、DB接続設定、TLS、OSグループ、
sequence保存用secret、ファイル権限を確認してください。

### WebAuthn properties

WebAuthnプラグインを使う場合の代表項目です。
FQDN/HTTPSが正しく設定されていることが前提です。

```properties
idp.authn.webauthn.relyingPartyId = idp.example.ac.jp
idp.authn.webauthn.relyingPartyName = Example IdP
idp.authn.webauthn.supportedPrincipals = \
    saml2/http://example.org/ac/classes/mfa, \
    saml1/http://example.org/ac/classes/mfa

# PostgreSQL StorageServiceへcredentialを保存する場合に指定する
idp.authn.webauthn.StorageService = shibboleth.StorageService
idp.authn.webauthn.StorageService.cache.enable = true
idp.authn.webauthn.StorageService.cache.expireAfterAccess = PT60M
idp.authn.webauthn.StorageService.jdbcAccelerator = WebAuthnJDBCAccelerator
idp.authn.webauthn.StorageService.jdbcAccelerator.defaultType = QUERY

idp.authn.webauthn.2fa.enabled = true
idp.authn.webauthn.2fa.allowedPreviousFactors = authn/Password
```

登録/管理画面の詳細設定は `webauthn-registration.properties`、
FIDO Metadata Serviceを使う場合は `webauthn-metadata.properties` を参照してください。

### enrollments.properties

`enrollments.properties` は初期PoCで使っていたファイルベース登録情報です。
現在のDB運用では利用せず、互換確認用のレガシーファイルとして扱います。

```properties
test01.sequence=img03,img07,img11,img14
test01.failedCount=0
test01.lockedUntil=0
```

本番/DB構成では、ユーザー登録、失敗回数、ロック状態は
`graphicalmatrix_enrollment` テーブルで管理します。

API curlテスト:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh \
  --base-url http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1 \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

書き込みテストを行う場合:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh \
  --base-url http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1 \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token \
  --write \
  --user api-test01
```

### 管理CLI

ユーザー追加:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh add test01 A B C D
```

MFA方式変更:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method test01 TOTP
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method test01 GraphicalMatrix
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh set-method test01 WebAuthn
```

RESET:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh test01 RESET
```

CSV一括登録:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv users.csv
```

CSV形式:

```csv
action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence
A,test01,GraphicalMatrix,on,"A,B,C,D","J,Z,W,P"
M,test01,TOTP,off,"A,B,C,D","img05,img06,img07,img08"
D,test01
```

CSVエクスポート:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export /secure/path/graphicalmatrix-users.csv
```

詳細は `docs/CSV-EXPORT.md` を参照してください。

## その他

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
