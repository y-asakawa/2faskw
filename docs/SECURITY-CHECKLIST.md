# Security Checklist

このチェックリストは、GraphicalMatrix MFA Plugin の導入前・導入後に確認する項目です。

環境ごとに必要な項目が異なるため、以下の区分で確認してください。

- PoC / 検証環境
- 本番単体構成
- 本番冗長構成
- 管理API有効環境
- TOTP有効環境
- WebAuthn有効環境

## 1. PoC / 検証環境

PoCでは動作確認を優先できますが、外部公開する場合は本番に近い制御を行ってください。

### 必須

- [ ] IdP設定のバックアップを取得した
- [ ] DBバックアップを取得した
- [ ] `graphicalmatrix.api.enabled = false` である
- [ ] APIを有効化していない
- [ ] DB接続先を把握している
- [ ] `graphicalmatrix-db.sh list` / `show USER` で登録情報を確認できる
- [ ] `graphicalmatrix-db.sh sequence-mode` で現在のsequence保存方式を確認した
- [ ] Jetty再起動手順を確認した
- [ ] rollback手順を確認した

### 推奨

- [ ] SSH接続元を管理IPに限定した
- [ ] IdPアクセス元を検証元IPに限定した
- [ ] `/opt/shibboleth-idp/credentials` 配下の権限を確認した
- [ ] `graphicalmatrix-audit.log` を確認できる
- [ ] `plaintext` sequenceがPoC用であることを関係者で共有した

## 2. 本番単体構成

IdPとDBを単体構成で運用する場合の最低限の確認項目です。

### OS / ネットワーク

- [ ] IdP公開URLはHTTPSのみである
- [ ] HTTPからHTTPSへリダイレクトする
- [ ] SSH接続元を管理ネットワークに限定した
- [ ] OS firewallを有効化した
- [ ] 不要なポートを閉じた
- [ ] NTPまたはchronyで時刻同期している
- [ ] OSログとIdPログの保存期間を決めた

### IdP / Jetty

- [ ] Shibboleth IdPのバージョンを記録した
- [ ] Jettyのバージョンを記録した
- [ ] Javaのバージョンを記録した
- [ ] `build.sh` 実行手順を確認した
- [ ] Jetty restart手順を確認した
- [ ] web.xml変更前バックアップを取得した
- [ ] `docs/INSTALL.md` を確認した
- [ ] `plugin-metadata/PACKAGE-MANIFEST.sha256` を確認した
- [ ] `graphicalmatrix-plugin-webxml.sh --install` のdry-run結果を確認した
- [ ] `graphicalmatrix-plugin-check.sh` の結果を確認した

### DB

- [ ] PostgreSQLを利用している
- [ ] H2を本番利用していない
- [ ] H2からPostgreSQLへ移行する場合は `docs/DB-MIGRATION.md` を確認した
- [ ] `graphicalmatrix.db.autoInit = false` であり、DDLを事前適用している
- [ ] HikariCPの `maximumPoolSize * IdP台数` がDB許容接続数内である
- [ ] `graphicalmatrix-db-migration.sh h2-count` で移行元件数を確認した
- [ ] 最終export前にJetty停止またはメンテナンス状態にした
- [ ] H2 export CSVを秘密情報として扱っている
- [ ] `graphicalmatrix-db-migration.sh pg-verify` が `verify=OK` になった
- [ ] 移行後、H2用 `db.properties` のrollback先を保持している
- [ ] 移行後、CSVを安全に削除した
- [ ] DBユーザーはアプリ用の最小権限である
- [ ] DDL適用用ユーザーとアプリ接続用ユーザーを分けている
- [ ] DBパスワードをファイルで管理している
- [ ] DBパスワードファイルはIdP実行ユーザーだけが読める
- [ ] DBバックアップ手順を確認した
- [ ] DB restore手順を確認した

### sequence保存方式

- [ ] `plaintext` を本番で使っていない
- [ ] 本番標準として `hash` を選択した、または復号要件により `aes-gcm` / `keyword` を明示的に選択した
- [ ] `hash` の場合、pepperファイルの権限を確認した
- [ ] `aes-gcm` の場合、AES keyファイルの権限と保管方法を確認した
- [ ] `keyword` の場合、keywordファイルの権限と保管方法を確認した
- [ ] 既存plaintext行の移行方針を決めた
- [ ] 保存方式を変更する場合は `docs/SEQUENCE-STORAGE-MIGRATION.md` を確認した
- [ ] `graphicalmatrix-db.sh migrate-sequence-storage` のdry-runで `summary.errors=0` を確認した
- [ ] hash方式では管理者がsequenceを復号確認できないことを運用側が理解している
- [ ] CSVエクスポートを使う場合は `docs/CSV-EXPORT.md` を確認した
- [ ] CSVエクスポートファイルを秘密情報として扱い、権限`0600`を確認した

### ログ / 監査

- [ ] `/opt/shibboleth-idp/logs/graphicalmatrix-audit.log` を監視対象にした
- [ ] `docs/LOGROTATE.md` を確認した
- [ ] `examples/logrotate/graphicalmatrix-audit` を環境に合わせて確認した
- [ ] logrotate設定を用意した
- [ ] `logrotate -d /etc/logrotate.d/graphicalmatrix-audit` がエラーなしで完了した
- [ ] `logrotate -f /etc/logrotate.d/graphicalmatrix-audit` 後に新ログへ追記されることを確認した
- [ ] 認証失敗、LOCKED、RESET、API操作を監査できる
- [ ] ログにsequence、TOTP seed、API tokenを出力しないことを確認した

## 3. 本番冗長構成

LB配下に複数IdPを置く場合の確認項目です。

### LB / IdP

- [ ] LBのヘルスチェックURLを決めた
- [ ] LBからIdPへの通信方式を決めた
- [ ] X-Forwarded-For等のクライアントIP取得方式を確認した
- [ ] `graphicalmatrix.mfa.useForwardedFor=true` を使う場合、IdPへの直接接続をFirewall/LBで遮断した
- [ ] `graphicalmatrix.mfa.useForwardedFor=true` を使う場合、Reverse Proxy/LBが `X-Forwarded-For` / `X-Real-IP` を上書きすることを確認した
- [ ] IdP各ノードの設定ファイルを同期する方式を決めた
- [ ] 各IdPノードのPlugin JARバージョンが一致している
- [ ] 各IdPノードの `graphicalmatrix.properties` が一致している
- [ ] 各IdPノードのsecretファイルが一致している
- [ ] secretファイル同期の権限と手順を確認した

### DB HA

- [ ] 共有PostgreSQLまたはPostgreSQL HA構成を利用している
- [ ] DB primary / standby構成を決めた
- [ ] failover方式を決めた
- [ ] IdPから接続するDB VIPまたは接続先を決めた
- [ ] DB failover時のIdP動作を確認した
- [ ] WAL保全とバックアップを設計した
- [ ] DB監視を設定した

### セッション

- [ ] LBのセッション維持要否を確認した
- [ ] GraphicalMatrix challengeは同一IdPセッションで完結することを確認した
- [ ] セッション維持しない場合のIdPクラスタ設定を確認した

## 4. 管理API有効環境

管理APIは高リスク機能です。必要な環境だけで有効化してください。

### 有効化前

- [ ] APIを利用する業務要件がある
- [ ] API利用元サーバを特定した
- [ ] API利用元IPを `graphicalmatrix.api.allowedCidrs` に限定した
- [ ] FirewallまたはLBでもAPI利用元IPに限定した
- [ ] HTTPS経由でのみ利用する
- [ ] API tokenを生成した
- [ ] API tokenファイルはIdP実行ユーザーだけが読める
- [ ] API token認証失敗時のレート制限を設定した
- [ ] API有効時のsequence保存方式が `hash`、`keyword`、`aes-gcm` のいずれかである
- [ ] APIレスポンスのsequence除外設定を有効にしている
- [ ] API token rotation手順を決めた
- [ ] `docs/API-TOKEN-ROTATION.md` を確認した
- [ ] `graphicalmatrix-api-token.sh status` でtoken状態を確認した
- [ ] `docs/openapi.yaml` をAPI利用者へ共有した
- [ ] `docs/API-CURL-TESTS.md` を確認した
- [ ] `graphicalmatrix-api-curl-test.sh` の読み取りテストが成功した
- [ ] API操作ログを監査対象にした

### 有効化後

- [ ] `GET /health` が許可IPからのみ成功する
- [ ] 許可外IPから403になる
- [ ] tokenなしで401になる
- [ ] token誤りで401になる
- [ ] token誤りを連続実行すると429になる
- [ ] `graphicalmatrix.api.enabled = false` に戻すと404になる
- [ ] PUT / PATCH / DELETE / POST actionの操作ログが残る
- [ ] token rotation後、新tokenでGET /healthが成功する
- [ ] token rotation後、旧tokenで401になる
- [ ] テスト専用ユーザーで `graphicalmatrix-api-curl-test.sh --write` が成功した
- [ ] 書き込みテスト後にテストユーザーが削除された、または意図して保持された

## 5. TOTP有効環境

TOTPを利用する場合は、Shibboleth TOTP Pluginとの連携確認が必要です。

- [ ] Shibboleth TOTP Pluginが導入済みである
- [ ] TOTP authn flowが有効である
- [ ] `mfa_method = TOTP` のユーザーだけTOTPへ分岐する
- [ ] 初回ログインでQR登録画面が表示される
- [ ] QR登録後にTOTP認証が成功する
- [ ] `reset-totp USER` またはAPI `totp-reset` で再登録状態に戻せる
- [ ] TOTP seedをログに出していない
- [ ] TOTP seedの保存先と保護方式を確認した
- [ ] `sequence.storage = hash` の場合、TOTP seed保存方式に `aes-gcm` または `keyword` を明示選択した
- [ ] 本番モードではTOTP seedのplaintext保存を拒否する方針を確認した

## 6. WebAuthn有効環境

WebAuthnを利用する場合は、HTTPSとFQDNが必須です。

- [ ] Shibboleth WebAuthn Pluginが導入済みである
- [ ] HTTPSでアクセスしている
- [ ] FQDNでアクセスしている
- [ ] IPアドレスURLで運用していない
- [ ] 登録フローのアクセス制御を確認した
- [ ] credential登録が成功する
- [ ] macOS Passkey / Windows Hello等の対象認証器でテストした
- [ ] credential紛失時の再登録手順を決めた
- [ ] WebAuthn credential保存先が `idp.authn.webauthn.StorageService` でDB永続化されている
- [ ] DB側credential削除後はデバイス側にcredentialが残っていても再登録が必要になることを運用側が理解している
- [ ] `mfa_method = WebAuthn` のユーザーだけWebAuthnへ分岐する

## 7. 変更作業前チェック

Plugin更新、設定変更、DB変更を行う前に確認してください。

- [ ] 作業日時と作業者を記録した
- [ ] 作業対象ノードを記録した
- [ ] IdP設定バックアップを取得した
- [ ] DBバックアップを取得した
- [ ] 現在のPlugin ZIPを退避した
- [ ] 新しいPlugin ZIPの `PACKAGE-MANIFEST.sha256` を確認した
- [ ] 現在の `graphicalmatrix.properties` を退避した
- [ ] 現在の `db.properties` を退避した
- [ ] 現在の `api.properties` を退避した
- [ ] 現在の `web.xml` を退避した
- [ ] DB移行を伴う場合は `DB-MIGRATION.md` の作業記録テンプレートを準備した
- [ ] rollback手順を確認した
- [ ] メンテナンス通知の要否を確認した

## 8. 変更作業後チェック

- [ ] `build.sh` が成功した
- [ ] Jetty restartが成功した
- [ ] IdPログに起動エラーがない
- [ ] `/idp/graphicalmatrix/change` が表示できる
- [ ] GraphicalMatrix認証が成功する
- [ ] 誤ったGraphicalMatrix選択でリトライできる
- [ ] 5回失敗時にロック画面が出る
- [ ] `graphicalmatrix-db.sh unlock USER` で解除できる
- [ ] sequence変更画面が利用できる
- [ ] `graphicalmatrix-audit.log` に操作ログが出る
- [ ] API無効環境ではAPIが404になる
- [ ] API有効環境では許可IP/tokenのみ成功する
- [ ] DB移行後は `graphicalmatrix-db.sh list` / `show USER` がPostgreSQLで成功する

## 9. インシデント対応チェック

### API token漏えい

- [ ] `graphicalmatrix.api.enabled = false` に変更した
- [ ] Jettyを再起動した
- [ ] tokenを再生成した
- [ ] API利用ログを確認した
- [ ] 不審なDB変更がないか確認した

### DBパスワード漏えい

- [ ] DBユーザーパスワードを変更した
- [ ] `graphicalmatrix-db.password` を更新した
- [ ] Jettyを再起動した
- [ ] DB接続ログを確認した

### sequence漏えい

- [ ] 対象ユーザーをdisableまたはlockした
- [ ] `RESET` を実行した
- [ ] `force_sequence_change` を有効化した
- [ ] 必要に応じてMFA方式をTOTP/WebAuthnへ変更した
- [ ] 漏えい経路を確認した

## 10. 記録テンプレート

```text
作業日:
作業者:
対象環境:
対象IdPノード:
Plugin ZIP:
DB:
sequence保存方式:
API有効/無効:
TOTP有効/無効:
WebAuthn有効/無効:
変更内容:
検証結果:
rollback要否:
備考:
```
