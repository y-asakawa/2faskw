# Security Guide

この文書は 2FAS-KW Plugin を Shibboleth IdP へ導入する際のセキュリティ運用指針です。

本PluginはPoC段階の実装を含みます。本番環境へ導入する場合は、ここに記載した項目を導入前チェックリストとして確認してください。

運用環境別の確認項目は以下に分けて整理しています。

```text
docs/SECURITY-CHECKLIST.md
```

## 基本方針

- IdP本体、Jetty、OS、DB、LB、Firewallを含めて防御する
- 管理APIは必要な環境だけで有効化する
- API、DB、秘密情報ファイルは管理ネットワークからのみ到達可能にする
- HTTPSを前提にする
- PoC用のplaintext sequence運用は本番で使わない
- TOTP / WebAuthn は導入済みプラグインと設定状態を確認してから有効化する
- 監査ログを保存し、ローテーションと保全をOS側で設計する

## 管理API

管理APIは、アカウントマネージャーやプロビジョニング基盤からDB上のMFA設定を操作するための機能です。

API仕様は以下に定義します。

```text
docs/openapi.yaml
```

配布物では初期状態を無効にします。

```properties
graphicalmatrix.api.enabled = false
```

APIを有効化する場合は、以下をすべて満たしてください。

- HTTPS経由でのみ利用する
- API Bearer tokenを設定する
- `graphicalmatrix.api.allowedCidrs` で接続元IPを制限する
- FirewallまたはLBでも接続元IPを制限する
- tokenファイルをIdP実行ユーザーだけが読める権限にする
- APIの利用ログを監査ログで確認できるようにする

設定例:

```properties
graphicalmatrix.api.enabled = true
graphicalmatrix.api.allowedCidrs = 127.0.0.1/32,192.168.0.0/24
graphicalmatrix.api.bearerTokenFile = /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
graphicalmatrix.api.authFailureLimit = 5
graphicalmatrix.api.authFailureWindowSeconds = 60
graphicalmatrix.api.authFailureLockSeconds = 300
graphicalmatrix.api.response.excludeSequences = true
graphicalmatrix.api.sequence.requireProtectedStorage = true
```

tokenファイル例:

```bash
sudo install -o jetty -g jetty -m 0700 -d /opt/shibboleth-idp/credentials
sudo sh -c 'openssl rand -base64 48 > /opt/shibboleth-idp/credentials/graphicalmatrix-api.token'
sudo chown jetty:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
sudo chmod 0400 /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

注意:

- `api.properties.idpnew` は配布時に `graphicalmatrix.api.enabled = false` へ強制変換されます
- web.xmlにAPI servlet mappingが存在しても、API無効時は404を返します
- APIをInternetへ直接公開しないでください
- Bearer token認証失敗は、`authFailureLimit`、`authFailureWindowSeconds`、`authFailureLockSeconds` により接続元IP単位で短時間ロックされます
- API有効時は `plaintext` sequence保存を拒否します。`hash`、`keyword`、`aes-gcm` のいずれかを利用してください
- APIレスポンスは標準で `initialSequence` / `sequence` を空配列として返します

API token rotation手順:

```text
docs/API-TOKEN-ROTATION.md
```

API curlテスト手順:

```text
docs/API-CURL-TESTS.md
```

補助スクリプト:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh status
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-token.sh rotate --apply --print-token
sudo /opt/shibboleth-idp/bin/graphicalmatrix-api-curl-test.sh \
  --base-url http://127.0.0.1:8080/idp/graphicalmatrix-admin/api/v1 \
  --token-file /opt/shibboleth-idp/credentials/graphicalmatrix-api.token
```

GraphicalMatrix管理APIはtokenファイルを各リクエスト時に読み込むため、
通常はtokenローテーション後のJetty再起動は不要です。

## DB

本番ではPostgreSQLを推奨します。
H2はPoC/検証用途に限定してください。

DB接続設定:

```properties
graphicalmatrix.db.driver=org.postgresql.Driver
graphicalmatrix.db.url=jdbc:postgresql://127.0.0.1:5432/graphicalmatrix
graphicalmatrix.db.user=graphicalmatrix_app
graphicalmatrix.db.passwordFile=/opt/shibboleth-idp/credentials/graphicalmatrix-db.password
graphicalmatrix.db.autoInit=false
graphicalmatrix.db.pool.enabled=true
graphicalmatrix.db.pool.maximumPoolSize=10
```

本番では `postgresql-schema.sql` を事前適用し、IdP実行ユーザーにはDDL権限を付与しないでください。
HikariCP接続プールの `maximumPoolSize` は、IdP台数とPostgreSQL側の `max_connections` を見て決めます。

DBパスワードファイル:

```bash
sudo install -o jetty -g jetty -m 0700 -d /opt/shibboleth-idp/credentials
sudo chown jetty:jetty /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
sudo chmod 0400 /opt/shibboleth-idp/credentials/graphicalmatrix-db.password
```

推奨:

- DBはIdPサーバまたは管理ネットワークからのみ到達可能にする
- DBユーザーは最小権限にする
- DDL適用用ユーザーとアプリ接続用ユーザーを分ける
- PostgreSQLはバックアップ、WAL保全、監視、HA構成を別途設計する
- IdP複数台構成では、DBは共有PostgreSQLまたはHA構成にする

H2からPostgreSQLへ移行する場合:

```text
docs/DB-MIGRATION.md
```

移行CSVには `sequence` と `totp_seed` が含まれます。
CSVは秘密情報として扱い、移行後は安全に削除してください。

## Sequence保存方式

sequenceはDBの `sequence` カラムに保存します。
保存方式は `graphicalmatrix.sequence.storage` で切り替えます。
本番では、導入時に保存方式を決めてください。

推奨順:

1. auto / hash + salt + pepper
2. AES-GCM暗号化
3. 共通キーワード暗号化
4. plaintext

本番標準は `hash + salt + pepper` です。
この方式ではsequenceを復号できませんが、DB漏えい時のリスクを最も抑えられます。

設定例:

```properties
# デフォルト。本番推奨。autoはhash + salt + pepperとして扱う。
graphicalmatrix.sequence.storage = auto
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

# PoC / 後方互換
graphicalmatrix.sequence.storage = plaintext

# 共通キーワード暗号化。復号可能。
graphicalmatrix.sequence.storage = keyword
graphicalmatrix.sequence.keywordFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword

# AES-GCM暗号化。復号可能。
graphicalmatrix.sequence.storage = aes-gcm
graphicalmatrix.sequence.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key

# hash + salt + pepper。復号不可。本番推奨。
graphicalmatrix.sequence.storage = hash
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper
```

注意:

- plaintextはPoC/検証用のみです
- AES-GCMや共通キーワード暗号化は復号可能ですが、鍵管理リスクがあります
- 既存plaintext sequenceは後方互換で認証できます
- 新規登録、sequence変更、RESET、API更新、CSV登録時に現在の保存方式で保存されます
- hash方式ではAPI応答の `sequence` は空配列になり、`sequenceRecoverable` は `false` になります
- 保存方式を本番途中で変更する場合は、`docs/SEQUENCE-STORAGE-MIGRATION.md` の手順でdry-run後に移行します
- 管理CSVエクスポートには復号したsequenceが含まれるため、`docs/CSV-EXPORT.md` に従って秘密情報として扱います
- hash保存済みsequenceは管理CSVへportable exportできません

## GraphicalMatrix画像

画像ファイル名がブラウザから見えると、RPA等で選択対象を推測される可能性があります。

本番で推奨する将来方式:

- 画像をWEB-INF配下または直接公開されない場所へ置く
- 画像Servletで配信する
- 表示ごとに一時tokenを発行する
- ブラウザには実ファイル名を出さない
- tokenと画像IDの対応をサーバ側セッションまたは短時間キャッシュで管理する

PoCで公開画像を使う場合:

- `graphicalmatrix.place` の配置先を限定する
- `graphicalmatrix.graphicals` と `graphicalmatrix.not_graphicals` を設定で管理する
- 不要な画像をWeb公開ディレクトリへ置かない

## GraphicalMatrixシーケンスのエントロピー

標準設定の `graphicalmatrix.graphicals = img01-25`、`graphicalmatrix.choice = 4`、`graphicalmatrix.order = 1` では、
順序付き4画像選択の組み合わせは `25 x 24 x 23 x 22 = 303,600` 通り、約18.2ビットです。

これは第2要素としては、LDAPパスワード認証後に利用し、5回失敗ロックを併用する前提で許容します。
ただし、単独のパスワードや高強度の秘密情報として扱ってはいけません。

運用方針:

- GraphicalMatrixは必ずLDAP等の一次認証後の第2要素として利用する
- 本番では失敗回数ロック、監査ログ、SP/IP単位のMFAポリシーを有効にする
- 高リスクSP、高権限ユーザー、インターネット公開範囲が広い環境ではTOTPまたはWebAuthnを優先する
- より高いエントロピーが必要な場合は、画像数、選択数、重複許可、画像/token化方式の拡張を別途設計する

## LDAP / DB MFA選択

MFA方式はDBの `mfa_method` を参照して選択できます。

想定値:

- `GraphicalMatrix`
- `TOTP`
- `WebAuthn`

運用上の注意:

- TOTPを選択するユーザーにはTOTPプラグインとseed登録機能が必要です
- WebAuthnを選択するユーザーにはWebAuthnプラグイン、HTTPS、FQDN、登録済みcredentialが必要です
- 未導入方式をDBへ設定しないでください
- 管理CLI、CSV、APIで許可方式を統一してください

## TOTP

TOTPを利用する場合は、Shibboleth TOTP Pluginが必要です。

注意:

- QR登録前のユーザーはTOTP認証できません
- seedは機密情報です
- seedはDBまたは安全な属性ストアで保護してください
- TOTP resetは本人確認済みの運用フローで実行してください

### TOTP seed保存方式

TOTP seedは認証時に復号してTOTPコードを計算する必要があります。
そのため、sequence保存方式で推奨している `hash + salt + pepper` はTOTP seedには使いません。

設定:

```properties
graphicalmatrix.totp.seed.storage = auto
```

`auto` の動作:

| `graphicalmatrix.sequence.storage` | TOTP seed保存方式 |
| --- | --- |
| `aes-gcm` | `aes-gcm` |
| `keyword` | `keyword` |
| `hash` | `aes-gcm` または `keyword` の明示選択を要求 |
| `plaintext` | PoCではplaintext。本番モードでは起動拒否または登録拒否 |

本番推奨:

```properties
graphicalmatrix.productionMode = true

graphicalmatrix.sequence.storage = auto
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

`sequence.storage = auto` または `hash` かつ `totp.seed.storage = auto` の組み合わせは設定不備として扱います。
TOTP seedは復号必須のため、この場合は `aes-gcm` または `keyword` を明示してください。

既存の平文TOTP seedを暗号化へ移行する場合:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-totp-seed-storage
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh migrate-totp-seed-storage --apply
```

## WebAuthn

WebAuthnを利用する場合は、Shibboleth WebAuthn Pluginが必要です。

注意:

- HTTPSが必須です
- FQDNでアクセスしてください
- IPアドレスURLやHTTPではパスキー/プラットフォーム認証器が正しく動作しない場合があります
- 登録フローと認証フローのアクセス制御を確認してください
- credentialの削除、再登録、紛失時対応を運用設計に含めてください
- 現在のPoCでは、WebAuthn credentialはShibboleth JDBC StorageService経由でGraphicalMatrix DBの `storagerecords` に保存します
- credential本体はShibboleth WebAuthn Pluginが管理するため、GraphicalMatrix側のsequence保存方式とは別管理です
- `webauthn-reset USER` はDB側credentialを削除し、利用者は再登録が必要になります

## web.xml

`graphicalmatrix-plugin-webxml.sh` は、GraphicalMatrix用Servlet mappingと管理API用security-constraintを追加します。

安全仕様:

- デフォルトはdry-runです
- `--apply` を付けない限り変更しません
- 適用時は `web.xml.bak.TIMESTAMP` を作成します
- marker blockだけを追加・削除します
- 既存PoC設定がある場合は二重追加しません

注意:

- web.xml変更後はIdP WAR再構築とJetty再起動が必要です
- security-constraintはAPIのHTTP methodを許可するためのものです
- API公開可否は `api.properties` の `graphicalmatrix.api.enabled` で制御します

## ファイル権限

以下はIdP実行ユーザーだけが読めるようにしてください。

- `/opt/shibboleth-idp/credentials/graphicalmatrix-db.password`
- `/opt/shibboleth-idp/credentials/graphicalmatrix-api.token`
- sequence保存方式で使うkeyword/key/pepperファイル
- TOTP seedを含むDBまたはファイル

推奨権限:

```bash
sudo chown jetty:jetty /opt/shibboleth-idp/credentials/<secret-file>
sudo chmod 0400 /opt/shibboleth-idp/credentials/<secret-file>
```

## ログ

GraphicalMatrixの監査ログ:

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

記録対象例:

- challenge作成
- verify成功/失敗
- lock
- unlock
- reset
- API操作

注意:

- logrotateはOS側で設定してください
- 設定例は `docs/LOGROTATE.md` と `examples/logrotate/graphicalmatrix-audit` を参照してください
- ログには認証結果やユーザーIDが含まれます
- sequenceやTOTP seedなどの秘密情報をログへ出力しないでください

## ネットワーク制御

本番では以下の多層制御を推奨します。

- IdP公開URLはHTTPSのみ
- 管理APIは管理ネットワークまたはプロビジョニングサーバのみ
- DBはIdPサーバからのみ
- SSHは管理ネットワークからのみ
- Firewall/LB/OS firewallで制限する

PoCでIP制限を行う場合も、IdP、API、DB、SSHを分けて考えてください。

## インストール前チェック

- IdPのバックアップを取得した
- DBバックアップを取得した
- `api.properties.idpnew` がAPI無効である
- DBパスワードファイルの権限を確認した
- API tokenファイルの権限を確認した
- TOTP / WebAuthn の導入有無を確認した
- web.xmlのdry-run結果を確認した
- build.shとJetty restartの手順を確認した
- rollback手順を確認した

## インシデント時の初動

API token漏えい時:

1. `graphicalmatrix.api.enabled = false` に変更する
2. IdPを再起動する
3. tokenを再生成する
4. 監査ログを確認する
5. 必要に応じてDB上のMFA設定を再確認する

DBパスワード漏えい時:

1. DBユーザーのパスワードを変更する
2. `graphicalmatrix-db.password` を更新する
3. IdPを再起動する
4. DB監査ログを確認する

sequence漏えい時:

1. 対象ユーザーをlockまたはdisableする
2. `RESET` で初期化する
3. `force_sequence_change` を有効化する
4. 必要に応じてMFA方式を変更する

## 本番前に追加するもの

- WebAuthn用HTTPS/FQDN設定手順
