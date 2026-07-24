# Install Guide

この文書は、2FAS-KW Plugin配布物を既存のShibboleth IdP 5へ導入する手順です。
公式Shibboleth plugin packageとしては未完成です。

## この文書の使い方

OS、Jetty、Shibboleth IdP、PostgreSQL HA、SimpleSAMLphpテストSPの構築は、
必要に応じて別文書を参照してください。

| 目的 | 参照先 |
| --- | --- |
| IdP/APサーバ構築 | `INSTALL_AP.md` |
| PostgreSQL HA構築 | `INSTALL_DB.md` |
| SimpleSAMLphpテストSP構築 | `INSTALL_SP.md` |
| 設定項目の意味 | `CONFIG-REFERENCE.md` |
| セキュリティ確認 | `SECURITY.md`, `SECURITY-CHECKLIST.md` |

作業は以下の流れで進めます。

| Phase | Section | 内容 |
| --- | --- | --- |
| 事前確認 | 0-3 | 配布物、前提、確認項目 |
| 配置 | 4-6 | package check、dry-run、apply |
| 設定 | 7-11 | 保存方式、DB、web.xml、MFA/External flow |
| 反映 | 12-15 | WAR再構築、Jetty再起動、動作確認、ログ確認 |
| 補助 | 16-20 | Admin Tools、主要設定例、運用、rollback、記録テンプレート |

## 対応Linuxディストリビューション

2FAS-KWのJavaプラグイン本体はRocky Linux専用ではありません。
ただし、この導入手順はRocky Linux 10.xで検証しており、以下を前提にしています。

- systemd
- firewalld
- dnf
- PGDG PostgreSQL RPM構成
- `/opt/shibboleth-idp` をIdP homeとする構成
- `jetty` ユーザーでJetty/IdPを実行する構成

AlmaLinux、RHEL、CentOS StreamなどのRHEL互換環境では、同じ方針で動作する可能性が高いです。
Debian、Ubuntu、その他のLinuxでは、パッケージ名、PostgreSQLのパス、サービス定義、
Firewall設定を環境に合わせて読み替えてください。

## 0. 配布物

### IdP Plugin 配布物

配布 ZIP を展開すると、以下の構成になります。

```text
2faskw-idp-plugin-1.0.1/
  LICENSE
  NOTICE
  THIRD-PARTY-NOTICES.md

  webapp/WEB-INF/lib/
    2faskw-idp-plugin-1.0.1.jar
    core-*.jar
    HikariCP-*.jar
    postgresql-*.jar

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
    webauthn-ldap-storage-config.xml
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
    core-*.jar
    HikariCP-*.jar
    postgresql-*.jar

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

対象ソフトウェアは先に導入しておく。
IdP/APサーバの導入は [INSTALL_AP.md](./INSTALL_AP.md)、
DBサーバの導入は [INSTALL_DB.md](./INSTALL_DB.md) を参照する。

- Shibboleth IdP 5系
- Java 21
- Jetty 12
- PostgreSQL

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

`edit-webapp/WEB-INF/web.xml` が未作成の場合は、IdP標準の `web.xml` を
overlay用にコピーしてから続行する。

```bash
sudo mkdir -p /opt/shibboleth-idp/edit-webapp/WEB-INF

sudo cp -a \
  /opt/shibboleth-idp/dist/webapp/WEB-INF/web.xml \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml

sudo chown root:root /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml
sudo chmod 0644 /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml

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
  --idp-home /opt/shibboleth-idp
```

このコマンドは、展開済み配布物ディレクトリ
`2faskw-idp-plugin-1.0.1` の中で実行してください。
別ディレクトリから実行する場合だけ、`--package-dir /path/to/2faskw-idp-plugin-1.0.1`
を指定します。

ビルドサーバや手元の端末で配布物だけ確認する場合:

```bash
./bin/graphicalmatrix-plugin-check.sh --package-only
```

IdPサーバ上でIdP側の状態だけ確認する場合:

```bash
./bin/graphicalmatrix-plugin-check.sh --idp-only --idp-home /opt/shibboleth-idp
```

`graphicalmatrix.properties` と参照先ファイルだけを確認する場合:

```bash
./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

このモードはDBへ接続せず、以下を検査します。

- Runtimeと同じJavaローダーによる数値、画像数、選択数、エイリアスの整合性
- 有効画像ファイル、CSS、HTMLテンプレートの存在と読取可否
- sequence保存方式とpepper/keyword/AES key
- TOTP seed保存方式

設定変更後は、ユーザーが認証画面へアクセスする前に実行してください。
正常時は終了コード `0`、設定不正時は終了コード `1` を返します。
WARNも失敗として扱う場合は `--strict` を追加します。

WARNも失敗扱いにしたい本番導入前確認では `--strict` を付けます。

```bash
./bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --strict
```

期待値:

```text
summary: package_failures=0 package_warnings=0 idp_failures=0 idp_warnings=0 config_failures=0 config_warnings=0 strict=0
result: OK
```

TOTP / WebAuthn を使わない場合、それらのoptional plugin未検出WARNは許容できます。
[プラグインのインストールはINSTALL_AP.mdを参照してください。](./INSTALL_AP.md)

`--package-only` ではIdP環境を確認しないため、手元の端末やCIでも `result: OK` になります。
`--strict` ではoptional plugin未検出などのWARNも失敗扱いになります。


## 5. plugin files dry-run

```bash
./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp
```

dry-runの前に、configスクリプトは配布物の `--package-only` checkを自動実行します。
本番導入前にWARNも失敗扱いにしたい場合は `--strict` を付けます。
すでに別手順で配布物確認済みの場合だけ、`--skip-package-check` で省略できます。

```bash
./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --strict
```

確認すること:

- `edit-webapp/WEB-INF/lib/` へ配置されるJAR
- `conf/graphicalmatrix/*.idpnew` の配置
- `bin/` へ配置される管理スクリプト
- 既存ファイルがある場合のbackup / `.idpnew.TIMESTAMP`
- 最後の `summary` と `result`

期待値:

```text
summary: mode=dry-run planned_changes=N applied_changes=0 backups_created=0 templates_deferred=0 failures=0 strict=0 package_check=enabled
result: DRY_RUN_OK
```

dry-run後に行うこと:

1. dry-runの出力を確認する

   コピー予定のJAR、設定テンプレート、管理スクリプト、backup予定の既存ファイルを確認します。
   dry-runでは実ファイルは変更されません。

2. 配布物内の `docs/INSTALL.md` を確認する

   作業中の配布物に同梱された手順を確認します。GitHub上の最新版ではなく、
   実際に導入しているZIP内の手順を優先してください。

   ```bash
   less docs/INSTALL.md
   ```

3. 問題がなければ、次の `plugin files apply` へ進む

   `/idp/graphicalmatrix/change` の確認は、apply、設定確認、`web.xml` 適用、
   WAR再構築、Jetty再起動が完了してから行います。dry-run直後のHTTP 404/503は
   最終確認結果として扱いません。

## 6. plugin files apply

```bash
sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --apply
```

`--apply` はrootで実行するか、`sudo -n true` が成功するパスワードレスsudo環境で実行してください。
sudoが非対話で利用できない場合、スクリプトはファイル変更前に停止します。

本番導入前の厳格確認を同時に行う場合:

```bash
sudo ./bin/graphicalmatrix-plugin-config.sh \
  --idp-home /opt/shibboleth-idp \
  --apply \
  --strict
```

配置される主なJAR:

```text
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.0.1.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/core-*.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/HikariCP-*.jar
/opt/shibboleth-idp/edit-webapp/WEB-INF/lib/postgresql-*.jar
```

applyが成功すると、導入内容は以下にTSV形式で記録されます。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/install-manifest-YYYYMMDDHHMMSS.tsv
```

期待値:

```text
summary: mode=apply planned_changes=N applied_changes=N backups_created=N templates_deferred=N failures=0 strict=0 package_check=enabled
result: APPLY_OK
manifest: /opt/shibboleth-idp/conf/graphicalmatrix/install-manifest-YYYYMMDDHHMMSS.tsv
```

apply後に行うこと:

1. `/opt/shibboleth-idp/conf/graphicalmatrix` に作成される `*.idpnew.*` を確認する

   既存設定がある場合、導入スクリプトは上書きしません。
   新しいテンプレートは `.idpnew` または `.idpnew.TIMESTAMP` として配置されます。

   ```bash
   sudo find /opt/shibboleth-idp/conf/graphicalmatrix \
     -maxdepth 1 \
     -type f \
     -name '*.idpnew*' \
     -ls
   ```

   必要に応じて、既存ファイルと差分を確認してから手動で反映します。

   ```bash
   sudo diff -u \
     /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties \
     /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties.idpnew
   ```

2. 後述の `web.xml` 手順で servlet mapping を適用する

   2FAS-KW の servlet endpoint を IdP webapp に追加します。
   まず dry-run で変更予定を確認し、問題なければ `--apply` を付けて適用します。

   ```bash
   ./bin/graphicalmatrix-plugin-webxml.sh \
     --idp-home /opt/shibboleth-idp

   sudo ./bin/graphicalmatrix-plugin-webxml.sh \
     --idp-home /opt/shibboleth-idp \
     --apply
   ```

3. `/opt/shibboleth-idp/bin/build.sh` を実行する

   `edit-webapp` に配置したJAR、設定、`web.xml` の変更を IdP WAR に反映します。

   ```bash
   sudo env \
     JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
     PATH=/usr/lib/jvm/java-21-openjdk/bin:/usr/bin:/bin \
     /opt/shibboleth-idp/bin/build.sh
   ```

4. Jettyを再起動する

   再構築した WAR を Jetty に読み込ませます。

   ```bash
   sudo systemctl restart jetty-idp.service
   sleep 5
   sudo systemctl status jetty-idp.service --no-pager
   ```

5. `/idp/graphicalmatrix/change` を確認する

   HTTPS FQDNで変更画面に到達できることを確認します。
   未認証状態では、認証フロー開始を示すHTTP 302またはログイン画面のHTTP 200を期待します。

   ```bash
   curl -k -s -o /tmp/graphicalmatrix-change.out -w 'change=%{http_code}\n' \
     https://idp.example.com/idp/graphicalmatrix/change
   ```

## 7. 設定ファイル確認

この章では、導入時に決める保存方式と、初期状態で確認すべき主要設定を扱います。
本番導入では、ここで選んだ保存方式を後から頻繁に変えない前提で設計してください。

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

LDAP保存オプションを使う場合のみ、以下も確認します。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/ldap.properties
```

WebAuthn credentialをLDAP StorageServiceへ保存する場合のみ、以下も確認します。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/webauthn-ldap.properties
```

### 導入時に決める保存先と保存方式

以下は初回ユーザー登録前に決めてください。
運用開始後に変更する場合は、既存データの移行、復号可否、再登録要否を確認する必要があります。
特に `sequence.storage = hash` は復号できないため、別方式へ戻せません。

2FAS-KWは、ユーザー登録情報、失敗回数、ロック状態、MFA方式、TOTP seedを
デフォルトでは `graphicalmatrix_enrollment` テーブルに保存します。
推奨構成はDB保存です。v1.2.0以降は、既存LDAP運用に合わせるためのオプションとしてLDAPユーザー属性保存も選択できます。

保存先の選択肢:

| 保存先 | 推奨/用途 | 変更時の注意 |
| --- | --- | --- |
| `db` | 推奨。PostgreSQLを使う。 | H2からPostgreSQLへ移行する場合は `docs/DB-MIGRATION.md` を使う。 |
| `ldap` | LDAP属性運用に寄せたい場合の選択肢。LDAPS + 専用service accountを使う。 | LDAP属性スキーマ、ACL、既存データ移行を先に設計する。 |

秘密情報の保護方式の選択肢:

| 対象 | 推奨 | 変更時の注意 |
| --- | --- | --- |
| GraphicalMatrix sequence | `hash` | `auto` は `hash` として扱う。`hash` は復号不可。後戻りには再登録が必要。 |
| TOTP seed | `aes-gcm` | TOTP seedは認証時に復号が必要なため `hash` は使えない。 |
| WebAuthn credential | JDBC StorageService | DB/JDBC保存を推奨。GraphicalMatrix/TOTPの保存先とは別に、WebAuthn / StorageService設定で制御する。LDAP StorageServiceは選択時オプション。 |

編集対象ファイル:

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/db.properties
```

`graphicalmatrix.properties` では、GraphicalMatrix sequence と TOTP seed の保存方式を選択します。
以下のどれを採用するかを導入時に決めてください。

### 推奨: DB保存を使う

既定値は `db` です。通常構成ではDB保存を使います。
DB保存では、GraphicalMatrix / TOTP / MFA方式選択を `graphicalmatrix_enrollment` に保存します。
保存先は導入時に選択でき、DB保存を基本としつつ、LDAPユーザー属性への保存も利用できます。

```properties
graphicalmatrix.savedata = db
```

### 本番推奨: sequenceはhash、TOTP seedはAES-GCM

GraphicalMatrix sequenceは復号不要なため `hash` を推奨します。
TOTP seedは認証時に復号が必要なため `aes-gcm` を使います。

編集ファイル:

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
```

```properties
graphicalmatrix.productionMode = true

graphicalmatrix.sequence.storage = auto
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

secret fileを作成します。pepperはHMAC用のsecret、AES keyは32 bytesのbase64値です。

```bash
sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

openssl rand -hex 32 | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper >/dev/null

sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key

openssl rand -base64 32 | tr -d '\n' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key >/dev/null

sudo chown root:jetty \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key

sudo chmod 0640 \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

### 復号可能にしたい場合: sequenceとTOTP seedをkeywordで保存

管理者が既存sequenceを復号できる運用にしたい場合は `keyword` を選択します。
この例では、GraphicalMatrix sequenceとTOTP seedの両方をkeyword方式で保存します。
本番では `hash` より弱くなるため、secret fileの権限管理を厳格にしてください。

編集ファイル:

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
```

```properties
graphicalmatrix.productionMode = true

graphicalmatrix.sequence.storage = keyword
graphicalmatrix.sequence.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword

graphicalmatrix.totp.seed.storage = keyword
graphicalmatrix.totp.seed.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword
```

secret fileを作成します。

```bash
# ここではランダムにパスワードを作成しています。

sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword

openssl rand -base64 32 | tr -d '\n' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword >/dev/null

sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword

openssl rand -base64 32 | tr -d '\n' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword >/dev/null

sudo chown root:jetty \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword

sudo chmod 0640 \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword
```

`graphicalmatrix.totp.seed.keywordFile` を省略した場合は、
`graphicalmatrix.sequence.keywordFile` がフォールバック利用されます。
ただし、sequence用とTOTP seed用のsecretを分けた方が、漏えい時の影響範囲を分離できます。

### 復号可能にしたい場合: sequenceをAES-GCM鍵で保存

`keyword` より明示的にAES-GCM鍵を使いたい場合は `aes-gcm` を選択します。
sequenceも復号可能になるため、鍵漏えい時の影響を考慮してください。

編集ファイル:

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
```

```properties
graphicalmatrix.productionMode = true

graphicalmatrix.sequence.storage = aes-gcm
graphicalmatrix.sequence.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key

graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

secret fileを作成します。

```bash
# ここではランダムにパスワードを作成しています。

sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key

openssl rand -base64 32 | tr -d '\n' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key >/dev/null

sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key

openssl rand -base64 32 | tr -d '\n' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key >/dev/null

sudo chown root:jetty \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key

sudo chmod 0640 \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

### secret fileの中身と復号方式

`keywordFile` には、共通キーワード文字列を1行で保存します。
例えば中身が `pass` の場合、以下の状態でも動作はします。

```text
sudo vi /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword
```

```text
pass
```

ただし `pass` のような短い文字列は弱いため、本番では使わないでください。
作成する場合はランダム値を使います。

```bash
sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword

openssl rand -base64 32 | tr -d '\n' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword >/dev/null
```

固定文字列を入れる場合は、以下のように書けます。
コマンド履歴や画面ログに残るため、本番では推奨しません。

```bash
printf '%s\n' 'pass' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword >/dev/null
```

`cat` で見ると中身は表示されますが、secretを画面やログへ出すことになるため、
運用時は避けてください。

```bash
sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword
```

`aesKeyFile` には、AES-GCM用の鍵を保存します。
これは人間が覚えるパスワードではなく、16 / 24 / 32 bytesの鍵です。
base64エンコードした32 bytes鍵を推奨します。

```bash
openssl rand -base64 32 | tr -d '\n' | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key >/dev/null
```

保存方式ごとの復号可否:

| 保存方式 | DB上のprefix | 復号可否 | 内部方式 |
| --- | --- | --- | --- |
| `keyword` | `kw1:` / `totpkw1:` | 可 | keywordからPBKDF2-HMAC-SHA256でAES鍵を導出し、AES-GCMで復号 |
| `aes-gcm` | `aesgcm1:` / `totpaesgcm1:` | 可 | `aesKeyFile` のAES鍵でAES-GCM復号 |
| `hash` | `hsp1:` | 不可 | pepper付きHMAC-SHA256で照合のみ実施 |
| `plaintext` | prefixなし | 可 | 暗号化なし |

復号は、IdP runtimeや管理CLIが `graphicalmatrix.properties` とsecret fileを読んで自動的に行います。
DB上の値を手作業で復号する運用は想定していません。
既存データを別方式へ変換する場合は `docs/SEQUENCE-STORAGE-MIGRATION.md` を使います。

---
---
### DB上のsequenceを確認・表示する方法


障害調査や移行確認で、DBに保存されている特定ユーザーのGraphicalMatrix sequenceを
表示したい場合は、管理CLIでDB上の値を確認してから `GraphicalMatrixSequenceTool` を使います。
これは確認用の手順です。通常運用でユーザーのsequenceを表示する必要はありません。

まず現在の保存方式を確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh sequence-mode
```

ユーザー `test01` の登録情報を確認します。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh show test01
```

出力内の `sequence` 値を控えます。
`keyword` 方式では `kw1:`、`aes-gcm` 方式では `aesgcm1:`、`hash` 方式では `hsp1:`
で始まります。

`kw1:` または `aesgcm1:` の場合は、以下のように表示できます。
`STORED` には `show test01` で確認した `sequence` 値をそのまま入れます。

```bash
STORED='kw1:...'

CP="$(find /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 \
  -type f \
  -name '*.jar' \
  -print | sort | paste -sd ':' -)"

sudo java -cp "$CP" \
  io.github.yasakawa.faskw.GraphicalMatrixSequenceTool \
  display \
  /opt/shibboleth-idp \
  "$STORED"
```

表示例:

```text
img03,img07,img11,img14
```

このコマンドは `/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties` と
secret fileを参照します。`keyword` 方式では `graphicalmatrix.sequence.keywordFile`、
`aes-gcm` 方式では `graphicalmatrix.sequence.aesKeyFile` が正しく設定されている必要があります。

`hsp1:` で始まる `hash` 方式のsequenceは復号できません。
この場合は保存値から元の画像列を表示できず、入力された画像列との照合のみ可能です。

---
---

### PostgreSQLの場合、`db.properties` で接続先を指定します。

編集ファイル:

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/db.properties
```

```properties
graphicalmatrix.db.driver = org.postgresql.Driver
graphicalmatrix.db.url = jdbc:postgresql://db-graphicalmatrix.example.com:5432/graphicalmatrix
graphicalmatrix.db.user = graphicalmatrix_app
graphicalmatrix.db.passwordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
graphicalmatrix.db.autoInit = false
graphicalmatrix.db.pool.enabled = true
```

PoCでのみ平文sequenceを使う場合:

```properties
graphicalmatrix.productionMode = false
graphicalmatrix.sequence.storage = plaintext
graphicalmatrix.totp.seed.storage = auto
```

既存ユーザー登録後に `graphicalmatrix.sequence.storage` を変更する場合は、
`docs/SEQUENCE-STORAGE-MIGRATION.md` を確認してください。

### LDAPを選択した場合: LDAPユーザー属性へ保存する

推奨はDB保存です。
LDAP保存は、既存LDAPの属性運用にGraphicalMatrix / TOTP / MFA方式選択を寄せたい場合に選択できます。
DB保存より先に選ぶ前提ではありません。

LDAP保存に切り替える場合は、LDAP属性スキーマ、ACL、既存データ移行を事前に設計してください。

```properties
graphicalmatrix.savedata = ldap
```

LDAP保存を使う場合のみ、`ldap.properties` も設定します。

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/ldap.properties
```

最小例:

```properties
graphicalmatrix.ldap.url = ldaps://ldap.example.jp:636
graphicalmatrix.ldap.baseDN = OU=people,DC=example,DC=jp
graphicalmatrix.ldap.userFilter = (cn={user})
graphicalmatrix.ldap.subtreeSearch = true

graphicalmatrix.ldap.bindDN = CN=graphicalmatrix-writer,OU=system,DC=example,DC=jp
graphicalmatrix.ldap.bindCredentialFile = /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret

graphicalmatrix.ldap.attr.sequence = ldap_sequence
graphicalmatrix.ldap.attr.status = ldap_status
graphicalmatrix.ldap.attr.failed_count = ldap_failed_count
graphicalmatrix.ldap.attr.locked_until = ldap_locked_until
graphicalmatrix.ldap.attr.mfa_method = ldap_mfa_method
graphicalmatrix.ldap.attr.totp_seed = ldap_totp_seed
graphicalmatrix.ldap.attr.totp_status = ldap_totp_status
graphicalmatrix.ldap.attr.totp_registered_at = ldap_totp_registered_at
graphicalmatrix.ldap.attr.last_success_at = ldap_last_success_at
graphicalmatrix.ldap.attr.force_sequence_change = ldap_force_sequence_change
graphicalmatrix.ldap.attr.state_version = ldap_state_version
graphicalmatrix.ldap.attr.updated_at = ldap_updated_at
```

bind credentialを作成します。

```bash
sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret

sudo vi /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret
sudo chown root:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret
sudo chmod 0640 /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret
```

LDAP保存でも、sequenceとTOTP seedの暗号化/ハッシュ化は `graphicalmatrix.properties` の設定を使います。
本番では、sequenceは `hash`、TOTP seedは `aes-gcm` または `keyword` を使ってください。
`graphicalmatrix.totp.seed.storage=auto` が `hash` を継承する状態では、TOTP seedを復号できないためConfig Checkで失敗します。

注意:

- LDAP保存はGraphicalMatrix / TOTP / MFA方式選択の保存先を切り替える機能です。
- WebAuthn credentialの保存先は `idp.authn.webauthn.StorageService` で別に制御します。
- v1.2.0フェーズ1では、Admin users APIはLDAP保存ユーザーの作成・更新に未対応です。

### 管理APIは初期状態で無効にしてください。

編集ファイル:

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/api.properties
```

```properties
graphicalmatrix.api.enabled = false
```


### MFAポリシーは `mfa-policy.properties` で設定します。

編集ファイル:

```text
sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/mfa-policy.properties
```

```properties
graphicalmatrix.mfa.default = require
graphicalmatrix.mfa.forceSPs =
graphicalmatrix.mfa.policyOrder = forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default
graphicalmatrix.mfa.bypassSPs =
graphicalmatrix.mfa.bypassSpCidrs =
graphicalmatrix.mfa.requiredSPs =
graphicalmatrix.mfa.bypassIPs =
graphicalmatrix.mfa.bypassCIDRs =
graphicalmatrix.mfa.useForwardedFor = false
```

`policyOrder`は左から評価し、最初にMFA必須またはMFA不要を決定したルールを採用する。
学内・社内CIDRでは通常SPのMFAを省略し、機微なSPだけはMFAを強制する例:

```properties
graphicalmatrix.mfa.forceSPs = https://sp-sensitive.example.org/shibboleth
graphicalmatrix.mfa.bypassCIDRs = 192.168.0.0/24
graphicalmatrix.mfa.policyOrder = forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default
graphicalmatrix.mfa.default = require
```

自己管理フローは`policyOrder`の対象外で、常にMFAを要求する。無効な順序やCIDRは
`graphicalmatrix-plugin-check.sh --config-only`で検出する。

特定のSPだけで送信元IPv4 CIDRによるMFA例外を設定する場合は、
`graphicalmatrix.mfa.bypassSpCidrs` に `<SP entityID>|<CIDR>[,<CIDR>]` をセミコロン区切りで指定する。
単一IPv4アドレスは`/32`で指定する。

```properties
graphicalmatrix.mfa.bypassSpCidrs = https://sp1.example.org/shibboleth|192.168.10.0/24;https://sp2.example.org/shibboleth|10.20.0.0/16
```

`useForwardedFor=true` は、信頼済みReverse Proxy/LBが送信元IPヘッダを
安全に上書きし、IdPへの直接接続をFirewall/LBで遮断している構成でのみ
利用してください。直接接続が残っている場合、クライアントが
`X-Forwarded-For` / `X-Real-IP` を偽造してMFAバイパスIP/CIDR判定を
すり抜ける可能性があるため、`false` のままにしてください。

## 8. DB設定
[DBのインストールはINSTALL_DB.mdを参照してください。](./INSTALL_DB.md)

PostgreSQLを利用する場合は、先に `docs/INSTALL_DB.md` のDB構築、VIP、
HAProxy、Keepalived、IdP側DB接続設定を完了してください。
この章では、2FAS-KW用スキーマだけを適用します。

```bash
PGPASSWORD='実際のGraphicalMatrix DBパスワード' \
  /usr/pgsql-18/bin/psql \
  -h 192.0.2.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -f /opt/shibboleth-idp/conf/graphicalmatrix/postgresql-schema.sql
```

適用確認:

```bash
PGPASSWORD='実際のGraphicalMatrix DBパスワード' \
  /usr/pgsql-18/bin/psql \
  -h 192.0.2.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -Atqc 'SELECT count(*) FROM graphicalmatrix_enrollment;'
```

新規DBなら `0` が返ります。

DB接続パスワードファイルを作成します。
ここには、DBロール作成時に `graphicalmatrix_app` へ設定したパスワードを入れます。

```bash
GRAPHICALMATRIX_DB_PASSWORD='実際のGraphicalMatrix DBパスワード'
printf 'length=%s\n' "${#GRAPHICALMATRIX_DB_PASSWORD}"

sudo install -d -m 0750 -o root -g jetty \
  /opt/shibboleth-idp/credentials

sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-db.password

printf '%s\n' "$GRAPHICALMATRIX_DB_PASSWORD" | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-db.password >/dev/null

unset GRAPHICALMATRIX_DB_PASSWORD

sudo chown root:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
sudo chmod 0640 /opt/shibboleth-idp/credentials/graphicalmatrix-db.password

sudo -u jetty test -r /opt/shibboleth-idp/credentials/graphicalmatrix-db.password \
  && echo OK
```

`/opt/shibboleth-idp/conf/graphicalmatrix/db.properties` は、以下の状態にしておきます。
詳細なDB構築手順と接続先切替は `docs/INSTALL_DB.md` に従ってください。

```properties
graphicalmatrix.db.url = jdbc:postgresql://192.0.2.64:5432/graphicalmatrix
graphicalmatrix.db.user = graphicalmatrix_app
graphicalmatrix.db.passwordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
graphicalmatrix.db.autoInit = false
graphicalmatrix.db.pool.enabled = true
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

## 11. MFA / External flow設定

2FAS-KWはShibboleth IdPの `authn/MFA` flow内で動作する。
Password認証だけでSPへ戻る場合は、`authn/MFA` が選択されていない。

### mfa-authn-config.xml

配布物のMFA設定例をIdPへ配置する。

```bash
sudo cp -a /opt/shibboleth-idp/conf/authn/mfa-authn-config.xml \
  /opt/shibboleth-idp/conf/authn/mfa-authn-config.xml.bak.$(date +%Y%m%d-%H%M%S)

sudo install -o root -g jetty -m 0640 \
  ./examples/mfa-authn-config.xml \
  /opt/shibboleth-idp/conf/authn/mfa-authn-config.xml
```

確認:

```bash
sudo grep -nE 'TransitionMap|authn/Password|GraphicalMatrixMfaDecisionStrategy|authn/External|authn/TOTP|authn/WebAuthn' \
  /opt/shibboleth-idp/conf/authn/mfa-authn-config.xml
```

`GraphicalMatrixMfaDecisionStrategy` が表示されることを確認する。

### authn.properties

IdPが `authn/MFA` を選択するように設定する。

```bash
sudo cp -a /opt/shibboleth-idp/conf/authn/authn.properties \
  /opt/shibboleth-idp/conf/authn/authn.properties.bak.$(date +%Y%m%d-%H%M%S)

sudo vi /opt/shibboleth-idp/conf/authn/authn.properties
```

設定:

```properties
idp.authn.flows = MFA
```

External認証の遷移先を、Shibbolethのデフォルト `/external.jsp` ではなく
2FAS-KWのServletへ変更する。

```properties
idp.authn.External.externalAuthnPath = contextRelative:/graphicalmatrix/start
idp.authn.External.nonBrowserSupported = false
idp.authn.External.passiveAuthenticationSupported = false
idp.authn.External.forcedAuthenticationSupported = true
```

`forcedAuthenticationSupported=true` は、自己管理profileが要求するForceAuthn時にも
GraphicalMatrix External flowを実行可能にする設定である。この設定だけで通常のSP認証を
常に再認証へ変更するものではない。自己管理profileではPasswordと現在のMFA方式を毎回実行する。

確認:

```bash
sudo grep -nE '^idp.authn.flows|idp.authn.External.externalAuthnPath|idp.authn.External.forcedAuthenticationSupported|external.jsp|/graphicalmatrix/start' \
  /opt/shibboleth-idp/conf/authn/authn.properties \
  /opt/shibboleth-idp/conf/idp.properties
```

`idp.authn.External.externalAuthnPath` が `/external.jsp` のままだと、
Password成功後に以下の404になる。

```text
HTTP ERROR 404 JSP file [/external.jsp] not found
```

2FAS-KWのServlet mappingも確認する。

```bash
sudo grep -nE 'GraphicalMatrixStart|/graphicalmatrix/start|GraphicalMatrixVerify|/graphicalmatrix/verify' \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/web.xml
```

`/idp/graphicalmatrix/start` への直接アクセスは、External認証のconversation keyが無いため
HTTP 500になる場合がある。直接確認では404でないことを確認する。

## 12. パスワード変更の認証方式

GraphicalMatrix sequenceおよびMFA方式の変更画面には、次の二つの認証経路がある。

| 経路 | URL | 本人確認 |
| --- | --- | --- |
| 従来経路 | `/idp/graphicalmatrix/change` | LDAP ID・パスワード、現在のGraphicalMatrix |
| IdP自己管理経路 | `/idp/profile/2faskw/self-service` | Shibboleth Password認証、現在のMFA方式 |

配布時の既定値は、前提設定が未完了の環境でパスワード変更を利用不能にしないため、
従来経路を維持する。

```properties
# /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
graphicalmatrix.selfservice.enabled = false
graphicalmatrix.change.legacyLdapLoginEnabled = true
```

新規本番導入、または自己管理フローの試験が完了した既存環境では、Shibboleth再認証を必須にする
次の設定を推奨する。

```properties
# /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
graphicalmatrix.selfservice.enabled = true
graphicalmatrix.selfservice.transactionTtlSeconds = 600
graphicalmatrix.change.legacyLdapLoginEnabled = false
```

この状態では`/idp/graphicalmatrix/change`へ直接アクセスしても、
`/idp/profile/2faskw/self-service`へ移動する。利用者はShibbolethのPassword認証と現在のMFA方式を
毎回完了してから変更メニューへ進む。

段階導入では、まず`graphicalmatrix.selfservice.enabled = true`と
`graphicalmatrix.change.legacyLdapLoginEnabled = true`を設定し、自己管理URLの動作を確認する。
試験完了後に`legacyLdapLoginEnabled`だけを`false`へ変更する。両方を`false`には設定できない。

自己管理フローを有効にする場合は、11章の
`idp.authn.External.forcedAuthenticationSupported = true`も必要である。設定後は
`graphicalmatrix-plugin-check.sh --config-only`、WAR再構築、Jetty再起動を実行する。
詳細な導入・受入試験・トラブルシューティングは
[INSTALL_Passchange_IdP.md](./INSTALL_Passchange_IdP.md)を参照する。

## 13. IdP WAR再構築

```bash
sudo /opt/shibboleth-idp/bin/build.sh
```

## 14. Jetty再起動

systemd例:

```bash
sudo systemctl daemon-reload
sudo systemctl restart jetty-idp.service
```

環境固有の起動方式がある場合は、その手順に従ってください。

## 15. 動作確認

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

MFA flow確認:

```bash
sudo tail -n 300 /opt/shibboleth-idp/logs/idp-process.log | grep -iE \
  'MFA policy decision|MFA method decision|GraphicalMatrixMfaDecisionStrategy|authn/MFA|authn/External|authn/TOTP|authn/WebAuthn'
```

`MFA policy decision` または `MFA method decision` が出れば、
2FAS-KWのMFA分岐に入っている。

## 16. ログ確認

```bash
sudo tail /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

logrotate設定例:

```text
docs/LOGROTATE.md
examples/logrotate/graphicalmatrix-audit
```

## 17. Admin Tools単体インストール

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

## 18. 主要設定例

この章は設定ファイルの最終確認用です。
導入作業中に作成したsecret fileやDB接続設定と矛盾がないことを確認してください。

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

# 5回目から通常ロック
graphicalmatrix.lockout.failureLimit = 5
graphicalmatrix.lockout.lockSeconds = 900
# 10回目から最大ロック
graphicalmatrix.lockout.maxLockFailureCount = 10
graphicalmatrix.lockout.maxLockSeconds = 2592000

graphicalmatrix.change.ldapRateLimit.enabled = true
graphicalmatrix.change.ldapRateLimit.failureLimit = 5
graphicalmatrix.change.ldapRateLimit.windowSeconds = 300
graphicalmatrix.change.ldapRateLimit.lockSeconds = 900
graphicalmatrix.change.ldapRateLimit.key = ip-user

# IdP内のShibboleth再認証済み自己管理flow。導入確認後にtrueへ変更する。
graphicalmatrix.selfservice.enabled = false
graphicalmatrix.selfservice.transactionTtlSeconds = 600
# 自己管理flowへ移行後、従来のLDAPログイン画面を停止する場合はfalseにする。
graphicalmatrix.change.legacyLdapLoginEnabled = true

graphicalmatrix.sequence.storage = auto
# graphicalmatrix.sequence.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword
# graphicalmatrix.sequence.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

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

GraphicalMatrix画像列照合のロック期限が経過しても、`failed_count` は自動的に0へ戻りません。
既定では5回目から9回目は15分ロック、10回目以降は30日ロックです。認証成功または
管理者のunlock・RESET等で失敗回数をリセットします。

Sequence保存方式:

```properties
graphicalmatrix.sequence.storage = auto
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

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

本番推奨は `sequence.storage = auto` または `hash`、`totp.seed.storage = aes-gcm`、
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
graphicalmatrix.api.allowedCidrs = 127.0.0.1/32,192.0.2.0/24
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
graphicalmatrix.mfa.forceSPs =
graphicalmatrix.mfa.policyOrder = forceSPs,bypassSPs,bypassSpCidrs,bypassNetwork,requiredSPs,default
graphicalmatrix.mfa.bypassSPs =
graphicalmatrix.mfa.bypassSpCidrs =
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

WebAuthn credentialはDB/JDBC StorageService保存を推奨します。
LDAPへ保存する場合は `examples/webauthn-ldap-storage-config.xml` をIdP側の読み込み対象に追加し、
`/opt/shibboleth-idp/conf/graphicalmatrix/webauthn-ldap.properties` で保存レイアウトを設定したうえで、
`webauthn.properties` のStorageServiceを切り替えます。

```properties
idp.authn.webauthn.StorageService = GraphicalMatrixLDAPStorageService
```

LDAP保存レイアウトは、専用subtreeへ `context + id` の1レコードを1エントリとして保存する `subtree` を推奨します。
ユーザーエントリ属性へ保存する `user-entry` も選択できますが、1ユーザー1レコード前提となるため、
複数context/複数recordが必要な運用では `subtree` を使います。

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

## 19. 運用補足

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

## 20. rollback

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

## 21. install記録テンプレート

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
