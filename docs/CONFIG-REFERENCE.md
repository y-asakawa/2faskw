# GraphicalMatrix Configuration Reference

この文書は、2FAS-KW Plugin / Admin Tools で利用する
`*.properties` の設定項目リファレンスである。

設定例だけでなく、各項目の意味、型、既定値または例、運用上の注意をまとめる。

## 共通方針

- 本番では `plaintext` sequence storage を使わない。
- secret/token/password/certificate/private key は配布ZIPへ同梱しない。
- `true` / `false` は小文字で記載する。
- パスは、IdP側では `/opt/shibboleth-idp`、Admin Tools側では `/opt/graphicalmatrix-admin` を基準にする。
- LDAPやIdP attribute resolverで取得できる氏名などの表示属性は、MFA DBへ重複保存しない設計を優先する。

## graphicalmatrix.properties

GraphicalMatrixの画面、画像、sequence保存方式、View外部化を制御する。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `graphicalmatrix.columns` | integer | `5` | 画像マトリクスの列数。 | `rows * columns` と利用画像数の整合を取る。 |
| `graphicalmatrix.rows` | integer | `5` | 画像マトリクスの行数。 | スマホ表示ではCSS側のレスポンシブ対応も確認する。 |
| `graphicalmatrix.place` | path | `/opt/shibboleth-idp/edit-webapp/graphicalmatrix/graphicals` | 画像ファイルの配置場所。 | 将来のtoken配信/WEB-INF配下移動時は見直す。 |
| `graphicalmatrix.graphicals` | list/range | `img01-25` | 利用対象画像ID。範囲指定可。 | `img01-25` / `img01,img02` の形式。 |
| `graphicalmatrix.not_graphicals` | list/range | empty | 利用対象から除外する画像ID。 | 紛らわしい画像を除外する用途。 |
| `graphicalmatrix.aliases` | map | `A:img01,...` | エイリアスから画像IDへの対応。 | CSV/CLIではエイリアス指定を優先できる。 |
| `graphicalmatrix.choice` | integer | `4` | ユーザーが選択する画像数。 | DBのsequence数、CSV列、画面表示と整合させる。 |
| `graphicalmatrix.order` | integer | `1` | sequence照合方式。 | `1`: 順番通り、`2`: 順不同で一致可。 |
| `graphicalmatrix.challenge.seconds` | integer seconds | `180` | challenge有効期限。 | 30-900秒を想定。長すぎる値は再利用リスクが上がる。 |
| `graphicalmatrix.allow_duplicates` | integer/bool | `0` | 同じ画像の複数選択を許可するか。 | `1` の場合 `img01,img01,img01,img01` のようなsequenceを許可。 |
| `graphicalmatrix.force_sequence_change` | integer/bool | `1` | 初回/RESET後の強制sequence変更。 | DB利用時に有効。変更完了後はユーザー行のforce値を解除する。 |
| `graphicalmatrix.change.ldapRateLimit.enabled` | boolean | `true` | セルフサービス変更画面のLDAP認証レート制限。 | `/graphicalmatrix/change` のLDAP bind前に判定する。 |
| `graphicalmatrix.change.ldapRateLimit.failureLimit` | integer | `5` | ロックまでのLDAP認証失敗回数。 | 1以上。 |
| `graphicalmatrix.change.ldapRateLimit.windowSeconds` | integer seconds | `300` | 失敗回数を数える時間窓。 | 1以上。 |
| `graphicalmatrix.change.ldapRateLimit.lockSeconds` | integer seconds | `900` | 閾値超過後の制限時間。 | 制限中はLDAP bindを実行しない。 |
| `graphicalmatrix.change.ldapRateLimit.key` | enum | `ip-user` | レート制限キー。 | `ip`, `user`, `ip-user`。NAT配下の巻き添えを抑えるため`ip-user`推奨。 |
| `graphicalmatrix.productionMode` | boolean | `false` | Runtime側の本番保護。 | true時はplaintext TOTP seed保存を拒否する。 |
| `graphicalmatrix.sequence.storage` | enum | `auto` | sequence保存方式。 | `auto`, `plaintext`, `keyword`, `aes-gcm`, `hash`。`auto`は`hash`として扱う。本番は`hash`推奨。 |
| `graphicalmatrix.sequence.keywordFile` | path | `/opt/shibboleth-idp/credentials/graphicalmatrix-sequence.keyword` | keyword暗号化用secret。 | 復号可能。権限は`0640`以下。 |
| `graphicalmatrix.sequence.aesKeyFile` | path | `/opt/shibboleth-idp/credentials/graphicalmatrix-sequence-aes.key` | AES-GCM用鍵。 | 復号可能。鍵管理リスクがある。 |
| `graphicalmatrix.sequence.pepperFile` | path | `/opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper` | hash方式のpepper。 | 復号不可。漏えい時はローテーションと再登録を検討。 |
| `graphicalmatrix.totp.seed.storage` | enum | `auto` | TOTP seed保存方式。 | `auto`, `plaintext`, `keyword`, `aes-gcm`。`hash`は不可。sequenceが`auto`/`hash`の場合は`aes-gcm`または`keyword`を明示する。 |
| `graphicalmatrix.totp.seed.keywordFile` | path | `/opt/shibboleth-idp/credentials/graphicalmatrix-totp.keyword` | TOTP seed keyword暗号化用secret。 | 未設定時はsequence keyword設定をフォールバック利用する。 |
| `graphicalmatrix.totp.seed.aesKeyFile` | path | `/opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key` | TOTP seed AES-GCM用鍵。 | 未設定時はsequence AES key設定をフォールバック利用する。本番推奨。 |
| `graphicalmatrix.view.template.enabled` | boolean | `true` | HTMLテンプレート外部化の有効/無効。 | false時は組み込みHTMLを使う。 |
| `graphicalmatrix.view.template` | path | `/opt/shibboleth-idp/conf/graphicalmatrix/views/graphicalmatrix.html` | GraphicalMatrix認証画面テンプレート。 | View変更時はJetty restartまたはcache設定に注意。 |
| `graphicalmatrix.view.lockedTemplate` | path | `.../locked.html` | ロック画面テンプレート。 | ロック時の案内を変更できる。 |
| `graphicalmatrix.view.unavailableTemplate` | path | `.../unavailable.html` | 利用不可/未登録画面テンプレート。 | DB未登録やMFA不可時の画面。 |
| `graphicalmatrix.view.totpRegisterTemplate` | path | `.../totp-register.html` | TOTP登録画面テンプレート。 | QRコード表示画面。 |
| `graphicalmatrix.view.changeStartTemplate` | path | `.../change-start.html` | sequence変更開始画面テンプレート。 | ユーザーID入力/開始導線。 |
| `graphicalmatrix.view.changeCurrentTemplate` | path | `.../change-current.html` | 現在sequence確認画面テンプレート。 | 変更前認証用。 |
| `graphicalmatrix.view.changeMenuTemplate` | path | `.../change-menu.html` | MFA変更メニュー画面テンプレート。 | GraphicalMatrix/TOTP/WebAuthn選択。 |
| `graphicalmatrix.view.changeNewTemplate` | path | `.../change-new.html` | 新sequence選択画面テンプレート。 | `graphicalmatrix.choice` と一致させる。 |
| `graphicalmatrix.view.changeMethodTemplate` | path | `.../change-method.html` | MFA方式選択画面テンプレート。 | 利用可能方式と整合させる。 |
| `graphicalmatrix.view.changeCompleteTemplate` | path | `.../change-complete.html` | 変更完了画面テンプレート。 | 完了メッセージ用。 |
| `graphicalmatrix.view.css.enabled` | boolean | `true` | 外部CSSの有効/無効。 | false時は組み込みCSSを使う。 |
| `graphicalmatrix.view.css` | path | `/opt/shibboleth-idp/conf/graphicalmatrix/assets/graphicalmatrix.css` | 外部CSSファイル。 | レスポンシブ対応はここで調整する。 |
| `graphicalmatrix.view.css.cacheSeconds` | integer seconds | `0` | CSSキャッシュ秒数。 | 本番は`3600`など、検証中は`0`が扱いやすい。 |

## db.properties

IdP runtime / Admin Tools がMFA DBへ接続するための設定。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `graphicalmatrix.db.driver` | class name | `org.postgresql.Driver` | JDBC driver class。 | PostgreSQL推奨。H2はPoC用途。 |
| `graphicalmatrix.db.url` | JDBC URL | `jdbc:postgresql://127.0.0.1:5432/graphicalmatrix` | DB接続URL。 | 本番はDB VIP/FQDNとTLS設定を入れる。 |
| `graphicalmatrix.db.user` | string | `graphicalmatrix_app` | DB接続ユーザー。 | IdP runtimeとAdmin Toolsはロール分離推奨。 |
| `graphicalmatrix.db.passwordFile` | path | `/opt/shibboleth-idp/credentials/graphicalmatrix-db.password` | DBパスワードファイル。 | 配布ZIPへ同梱しない。権限は`0640`以下。 |
| `graphicalmatrix.db.autoInit` | boolean | `false` | 起動時DDL自動実行。 | 本番はfalse。DDLは事前適用する。 |
| `graphicalmatrix.db.pool.enabled` | boolean | `true` | HikariCP接続プール利用。 | IdP runtime/API/TOTP参照で利用。CLIは短命接続。 |
| `graphicalmatrix.db.pool.maximumPoolSize` | integer | `10` | 最大プールサイズ。 | `IdP台数 * maximumPoolSize` がDB上限を超えないようにする。 |
| `graphicalmatrix.db.pool.minimumIdle` | integer | `2` | 最小idle接続数。 | 常時接続数に影響する。 |
| `graphicalmatrix.db.pool.connectionTimeoutMillis` | milliseconds | `30000` | 接続取得タイムアウト。 | DB障害時の待ち時間に影響。 |
| `graphicalmatrix.db.pool.idleTimeoutMillis` | milliseconds | `600000` | idle接続の破棄時間。 | `minimumIdle`との関係に注意。 |
| `graphicalmatrix.db.pool.maxLifetimeMillis` | milliseconds | `1800000` | 接続最大寿命。 | DB/LB側の接続寿命より短くする。 |
| `graphicalmatrix.db.pool.validationTimeoutMillis` | milliseconds | `5000` | 接続検証タイムアウト。 | 短すぎると一時遅延で失敗しやすい。 |

TLS verify-full例:

```properties
graphicalmatrix.db.url=jdbc:postgresql://db-graphicalmatrix.example.com:5432/graphicalmatrix?sslmode=verify-full&sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

## api.properties

管理APIの有効化、IP制限、認証失敗制御、sequence露出制御を行う。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `graphicalmatrix.api.enabled` | boolean | `false` recommended | 管理APIの有効/無効。 | 配布物では無効を推奨。 |
| `graphicalmatrix.api.allowedCidrs` | CIDR list | `127.0.0.1/32,192.0.2.0/24` | API接続許可CIDR。 | Firewall/LB側でも制限する。 |
| `graphicalmatrix.api.bearerTokenFile` | path | `/opt/shibboleth-idp/credentials/graphicalmatrix-api.token` | Bearer tokenファイル。 | ローテーション手順を用意する。 |
| `graphicalmatrix.api.authFailureLimit` | integer | `5` | 認証失敗ロックまでの回数。 | token総当たり対策。 |
| `graphicalmatrix.api.authFailureWindowSeconds` | seconds | `60` | 認証失敗カウント窓。 | 短すぎると攻撃検知が弱くなる。 |
| `graphicalmatrix.api.authFailureLockSeconds` | seconds | `300` | 認証失敗時のロック秒数。 | 運用監視と合わせて調整。 |
| `graphicalmatrix.api.response.excludeSequences` | boolean | `true` | API応答からsequenceを除外する。 | 本番はtrue推奨。 |
| `graphicalmatrix.api.sequence.requireProtectedStorage` | boolean | `true` | API利用時に保護済みsequence保存方式を要求。 | `hash`, `keyword`, `aes-gcm` を想定。 |

## mfa-policy.properties

SP単位/IP単位でMFA要否を制御する。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `graphicalmatrix.mfa.default` | enum | `require` | 既定のMFA方針。 | `require` または `bypass`。 |
| `graphicalmatrix.mfa.bypassSPs` | list | empty | MFAを回避するSP entityID。 | カンマ区切り。 |
| `graphicalmatrix.mfa.requiredSPs` | list | empty | MFAを要求するSP entityID。 | 空でなければ対象SPを限定する。 |
| `graphicalmatrix.mfa.bypassIPs` | IP list | empty | MFAを回避する完全一致IP。 | 管理端末や学内IPなど。 |
| `graphicalmatrix.mfa.bypassCIDRs` | CIDR list | empty | MFAを回避するCIDR。 | 広すぎる範囲にしない。 |
| `graphicalmatrix.mfa.useForwardedFor` | boolean | `false` | `X-Forwarded-For` / `X-Real-IP` を参照。 | リバースプロキシ/LB経由のみの接続が保証され、プロキシが送信元IPヘッダを上書きする場合のみtrue。直接接続が可能ならfalse固定。 |

## admin.properties

Admin Tools単体配布物とCSV provisioning runnerの安全制御。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `graphicalmatrix.admin.enabled` | boolean | `false` | Admin Tools有効/無効。 | 配布直後はfalse。 |
| `graphicalmatrix.admin.requiredGroup` | group name | `graphicalmatrix-admin` | 実行ユーザーが所属すべきOSグループ。 | root実行より専用ユーザー運用を推奨。 |
| `graphicalmatrix.admin.allowedHosts` | list | empty | 実行許可ホスト名/IP。 | DB管理サーバに限定する。 |
| `graphicalmatrix.admin.requireClientCert` | boolean | `false` | client certificate必須化。 | 本番ではtrueを検討。 |
| `graphicalmatrix.admin.clientCertPath` | path | `/opt/graphicalmatrix-admin/credentials/db-ssl/admin-client.crt` | client cert確認パス。 | DB接続URLのcert設定とも揃える。 |
| `graphicalmatrix.admin.productionMode` | boolean | `true` | 本番向けチェックを有効にする。 | PoCでplaintextを使う場合のみfalse。 |
| `graphicalmatrix.admin.rejectPlaintextSequence` | boolean | `true` | plaintext sequence storageを拒否する。 | 本番はtrue。 |
| `graphicalmatrix.admin.provisioning.enabled` | boolean | `false` | CSV provisioning runnerを有効化。 | SCP連携を使う場合のみtrue。 |
| `graphicalmatrix.admin.csv.incomingDir` | path | `/opt/graphicalmatrix-admin/incoming` | CSV受信ディレクトリ。 | SCP専用ユーザーはここだけ書き込み可。 |
| `graphicalmatrix.admin.csv.processingDir` | path | `/opt/graphicalmatrix-admin/processing` | 処理中CSV置き場。 | runnerが移動してから処理する。 |
| `graphicalmatrix.admin.csv.processedDir` | path | `/opt/graphicalmatrix-admin/processed` | 正常処理後CSV置き場。 | 保存期間を決める。 |
| `graphicalmatrix.admin.csv.failedDir` | path | `/opt/graphicalmatrix-admin/failed` | 失敗CSV置き場。 | エラー調査用。 |
| `graphicalmatrix.admin.csv.logFile` | path | `/opt/graphicalmatrix-admin/logs/csv-import.log` | CSV処理ログ。 | logrotate対象。 |
| `graphicalmatrix.admin.csv.lockFile` | path | `/opt/graphicalmatrix-admin/logs/csv-import.lock` | 二重起動防止lock。 | runnerがflockで利用。 |
| `graphicalmatrix.admin.csv.autoApply` | boolean | `false` | CSV自動適用。 | true時もdry-run後にpolicy確認する。 |
| `graphicalmatrix.admin.csv.autoApplyActions` | list | `A,M` | 自動適用を許可する操作。 | `D` は別設定で制御。 |
| `graphicalmatrix.admin.csv.autoApplyDeprovision` | boolean | `true` | `D` をdisableとして自動適用する。 | 物理削除ではない。 |
| `graphicalmatrix.admin.csv.maxRows` | integer | `10000` | 1 CSVあたり最大行数。 | 誤投入対策。 |
| `graphicalmatrix.admin.csv.maxDisables` | integer | `1000` | 1 CSVあたり最大disable件数。 | 大量停止事故対策。 |

## webauthn.properties

Shibboleth WebAuthn authentication pluginの代表設定。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `idp.authn.webauthn.relyingPartyId` | FQDN | `idp.example.com` | WebAuthn RP ID。 | パスキーはRP IDに紐づく。IP運用ではなくFQDN/HTTPSが前提。 |
| `idp.authn.webauthn.relyingPartyName` | string | `Example IdP` | 利用者に見えるRP名。 | 登録画面に表示される。 |
| `idp.authn.webauthn.supportedPrincipals` | list | MFA principal list | WebAuthnが満たす認証Principal。 | SP側要求と整合させる。 |
| `idp.authn.webauthn.StorageService` | bean id | `shibboleth.StorageService` | credential保存先StorageService。 | DB永続化する場合に指定。 |
| `idp.authn.webauthn.StorageService.cache.enable` | boolean | `true` | credential repository cache。 | 多台構成ではTTLに注意。 |
| `idp.authn.webauthn.StorageService.cache.expireAfterAccess` | duration | `PT60M` | cache有効期間。 | 即時削除反映が必要なら短くする。 |
| `idp.authn.webauthn.StorageService.jdbcAccelerator` | bean id | `WebAuthnJDBCAccelerator` | JDBC高速化設定。 | PostgreSQL StorageService利用時。 |
| `idp.authn.webauthn.StorageService.jdbcAccelerator.defaultType` | enum | `QUERY` | JDBC accelerator方式。 | 利用DBとプラグイン仕様に合わせる。 |
| `idp.authn.webauthn.2fa.enabled` | boolean | `true` | 2FAとしてWebAuthnを使う。 | パスワード後の追加認証。 |
| `idp.authn.webauthn.2fa.allowedPreviousFactors` | list | `authn/Password` | 2FA前段として許可する認証Flow。 | MFA authn-configと合わせる。 |

## webauthn-registration.properties

WebAuthn credential登録/管理画面の代表設定。
配布ファイルにはコメントアウトされた設定候補を含む。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `idp.authn.webauthn.admin.registration.forceAuthn` | boolean | `false` | 登録FlowでSSO済みでも再認証するか。 | セキュリティ重視ならtrueを検討。 |
| `idp.authn.webauthn.allowUntrustedAttestation` | boolean | `true` | 未信頼attestationを許可するか。 | FIDO Metadata運用時はfalseも検討。 |
| `idp.authn.webauthn.registration.collectUsername` | boolean | `true` | 登録前にユーザー名を入力させるか。 | IdP認証済み属性利用時は設計次第。 |
| `idp.authn.webauthn.admin.registration.authenticate` | boolean | `true` | 登録画面で認証を要求する。 | 基本true。 |
| `idp.authn.webauthn.admin.registration.accessPolicy` | bean/policy | `AccessByCurrentUser` | 登録Flowアクセス制御。 | 利用者本人登録ならCurrentUser。 |
| `idp.authn.webauthn.registration.residentKey` | enum | `preferred` | discoverable credential/passkey要求。 | `discouraged`, `preferred`, `required`。 |
| `idp.authn.webauthn.registration.userVerification` | enum | `discouraged` | 登録時User Verification要求。 | パスキー/生体認証運用では`preferred`以上を検討。 |
| `idp.authn.webauthn.registration.attestationConveyancePreference` | enum | `none` | attestation要求。 | `none`, `indirect`, `direct`, `enterprise`。 |
| `idp.authn.webauthn.registration.nicknameRequired` | boolean | `true` | credential nickname必須。 | 複数デバイス管理に有用。 |
| `idp.authn.webauthn.admin.management.accessPolicy` | bean/policy | `AccessByAdmin` | 管理画面アクセス制御。 | 管理者用Flow。 |

詳細項目は配布ファイル `webauthn-registration.properties` のコメントを参照する。

## webauthn-metadata.properties

FIDO Metadata Service / AAGUID補足メタデータの設定。

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `idp.authn.webauthn.metadata.enabled` | boolean | `false` | FIDO Metadata Service利用。 | attestation検証を強める場合に検討。 |
| `idp.authn.webauthn.metadata.trustRootFile` | path | `%{idp.home}/credentials/root-r3.crt` | FIDO MDS trust root。 | 公式手順で取得する。 |
| `idp.authn.webauthn.metadata.expectedLegalHeaders` | string | FIDO legal header | Metadata blob利用条件確認。 | FIDOの法的文言に合わせる。 |
| `idp.authn.webauthn.metadata.crls` | path list | `%{idp.home}/credentials/root-r3.crl` | CRLファイル。 | metadata署名失効確認。 |
| `idp.authn.webauthn.metadata.cacheFile` | path | `%{idp.home}/metadata/fido-metadata.bin` | URL取得metadataのcache。 | 起動時ダウンロード運用で利用。 |
| `idp.authn.webauthn.metadata.metadataBlobUrl` | URL | `https://mds3.fidoalliance.org` | Metadata blob URL。 | 外部通信可否を確認。 |
| `idp.authn.webauthn.metadata.metadataBlobFile` | path | `%{idp.home}/metadata/blob-mds3.jwt` | ローカルmetadata blob。 | URL設定を上書きする。 |
| `idp.authn.webauthn.metadata.aaguid.enabled` | boolean | `false` | 補足AAGUID metadata利用。 | 独自表示名/アイコン用。 |
| `idp.authn.webauthn.metadata.aaguid.passkeyAaguidFile` | path | `aaguid.json` | 補足AAGUID JSON。 | 未掲載authenticator表示補完。 |

## enrollments.properties

初期PoCで使っていたファイルベース登録情報。
現在のDB運用では利用しない。

レガシー例:

```properties
user001.sequence=img03,img07,img11,img14
user001.failedCount=0
user001.lockedUntil=0
```

| Property | Type | Default / Example | Description | Notes |
| --- | --- | --- | --- | --- |
| `<user>.sequence` | sequence | `img03,img07,img11,img14` | ファイルベースsequence。 | DB運用では `graphicalmatrix_enrollment.sequence` を使う。 |
| `<user>.failedCount` | integer | `0` | 失敗回数。 | DB運用では `failed_count`。 |
| `<user>.lockedUntil` | epoch millis | `0` | ロック解除時刻。 | DB運用では `locked_until`。 |

## Bundled / Related Software Reference

この章は、GraphicalMatrix配布物に同梱されるソフトウェアと、
運用上関連する外部ソフトウェアの一覧である。

`Included` の意味:

```text
yes:
  2FAS-KW Plugin/Admin Tools配布ZIPに含まれる

no:
  OS、Shibboleth IdP、DB、運用環境側で別途用意する
```

### Bundled Java Libraries

| Component | Version | Included | Required | Purpose | Notes |
| --- | --- | --- | --- | --- | --- |
| `2faskw-idp-plugin` | `@VERSION@` | yes | yes | 2FAS-KW本体、Servlet、DB処理、Admin Tools用Java処理。 | このプロジェクトの成果物。 |
| HikariCP | `pom.xml` 参照 | yes | DB pool利用時 | JDBC connection pool。 | `db.properties` の `graphicalmatrix.db.pool.*` で制御する。 |
| PostgreSQL JDBC Driver | `pom.xml` 参照 | yes | PostgreSQL利用時 | PostgreSQLへJDBC接続する。 | TLS接続、client certificate接続にも利用する。 |
| ZXing core | `pom.xml` 参照 | yes | TOTP QR生成時 | TOTP登録画面のQRコード生成。 | TOTPを使わない場合も配布物には含まれる。 |

### Provided Runtime APIs

以下はビルド時に参照するが、通常はShibboleth IdP / Jetty runtime側が提供する。
GraphicalMatrix配布ZIPへは同梱しない。

| Component | Version | Included | Required | Purpose | Notes |
| --- | --- | --- | --- | --- | --- |
| Shibboleth IdP API | `5.2.2` | no | yes | IdP認証Flow、Profile、Admin API連携。 | IdP本体のバージョンと合わせる。 |
| OpenSAML API | `5.2.2` | no | yes | Shibboleth IdPが利用するSAML/Profile API。 | IdP runtime側が提供する。 |
| Shibboleth shared support | `9.2.2` | no | yes | Shibboleth共通サポートAPI。 | IdP runtime側が提供する。 |
| Jakarta Servlet API | `6.0.0` | no | yes | Servlet実装用API。 | Jetty 12 / IdP runtime側が提供する。 |
| SLF4J API | `2.0.13` | no | yes | ログAPI。 | runtime側のロギング構成と合わせる。 |
| Shibboleth TOTP Plugin API/Impl | `2.3.1` | no | TOTP利用時 | TOTP認証方式との連携。 | TOTPを選択肢に入れる場合は別途導入する。 |

### Bundled CLI / Shell Tools

| Tool | Included | Required | Purpose | Notes |
| --- | --- | --- | --- | --- |
| `graphicalmatrix-db.sh` | yes | 管理運用時 | ユーザー登録、CSV import/export、TOTP/WebAuthn管理、sequence migration。 | `help` / `config-help` を提供する。 |
| `graphicalmatrix-db-migration.sh` | yes | DB移行時 | H2/PostgreSQL移行補助。 | 詳細は `DB-MIGRATION.md`。 |
| `graphicalmatrix-admin-install.sh` | Admin Tools ZIPのみ | Admin Tools導入時 | Admin Tools単体インストール。 | IdP本体やweb.xmlは変更しない。 |
| `graphicalmatrix-csv-import-runner.sh` | Admin Tools ZIPのみ | provisioning連携時 | SCP投入されたCSVをsystemd経由で取り込む。 | `admin.properties` で明示的に有効化する。 |
| `graphicalmatrix-api-token.sh` | Plugin ZIP | API利用時 | Bearer token生成/確認/ローテーション。 | tokenは配布ZIPへ同梱しない。 |
| `graphicalmatrix-api-curl-test.sh` | Plugin ZIP | API疎通確認時 | 管理APIのcurlテスト。 | 書き込みテストは検証環境で行う。 |
| `graphicalmatrix-plugin-check.sh` | Plugin ZIP | 導入前確認時 | IdP環境/配布物の事前チェック。 | optional plugin未導入はWARN扱いの場合がある。 |
| `graphicalmatrix-plugin-config.sh` | Plugin ZIP | Plugin導入時 | JAR、設定ファイル、管理スクリプト配置。 | 既存設定は上書きせず `.idpnew` を配置する。 |
| `graphicalmatrix-plugin-uninstall.sh` | Plugin ZIP | Plugin削除時 | Pluginファイル削除補助。 | DBデータ削除は別操作。 |
| `graphicalmatrix-plugin-webxml.sh` | Plugin ZIP | Servlet設定時 | `web.xml` へのServlet/filter設定適用。 | 適用前にdry-runする。 |

### Required Runtime Software

| Component | Included | Required | Purpose | Notes |
| --- | --- | --- | --- | --- |
| Shibboleth IdP | no | yes | 認証基盤本体。 | IdP 5.2系を前提に設計。 |
| Java | no | yes | IdP/Jetty/GraphicalMatrix実行環境。 | Java 21を前提にビルド。 |
| Jetty | no | yes | IdPを動かすServlet container。 | Jetty 12を前提。 |
| PostgreSQL | no | production recommended | MFA DB、TOTP seed、WebAuthn credential保存先。 | H2はPoC/検証用途のみ。 |
| bash | no | yes | 管理スクリプト実行。 | Admin Tools/Plugin scriptsで利用。 |
| `psql` | no | PostgreSQL運用時 | SQL確認、DDL適用、障害調査。 | Admin Toolsサーバにも配置すると運用しやすい。 |
| systemd | no | Linux運用時 | Jetty/PostgreSQL/HAProxy/Keepalived/CSV runner管理。 | CSV provisioning runnerはsystemd path/service例を同梱。 |
| firewalld | no | Rocky/RHEL系運用時 | SSH/HTTPS/PostgreSQL/VRRPのアクセス制限。 | 本番ではSSH/DB接続元を必ず制限する。 |
| OpenSSL | no | TLS/secret運用時 | 証明書確認、秘密鍵変換、token/secret生成補助。 | PostgreSQL JDBC client keyはPKCS#8形式が必要。 |

### Optional Integration Software

| Component | Included | Required | Purpose | Notes |
| --- | --- | --- | --- | --- |
| Shibboleth TOTP Plugin | no | TOTP利用時 | TOTP認証方式。 | `mfa_method=TOTP` を使う場合に導入する。 |
| Shibboleth WebAuthn Plugin | no | WebAuthn利用時 | WebAuthn/Passkey/Windows Hello/macOS passkey。 | HTTPS/FQDNが必須。 |
| JDBC StorageService | no | WebAuthn DB保存時 | WebAuthn credentialをPostgreSQLへ永続化。 | GraphicalMatrix DBと同じPostgreSQLで管理可能。 |
| HAProxy | no | DB HA構成時 | DB VIPからPostgreSQL PrimaryへTCP転送。 | 現構成ではDBノード上でTCP pass-through。 |
| Keepalived | no | DB VIP構成時 | DB VIPのフェイルオーバー。 | 2台構成ではsplit-brain対策に注意。 |
| PgBouncer | no | 高負荷/接続数対策時 | PostgreSQL connection pooler。 | IdP台数/接続数が増えた場合の選択肢。 |
| SimpleSAMLphp | no | SP検証時 | テスト用SAML SP。 | 本番SP追加はShibboleth IdP metadata設定で行う。 |
| k6 | no | 負荷試験時 | API/IdP/SP連携の負荷試験。 | 別サーバから実行する設計を推奨。 |

### HA / Operations Components

| Component | Included | Required | Purpose | Notes |
| --- | --- | --- | --- | --- |
| DB VIP | no | DB HA構成時 | IdPから見た単一DB接続先。 | DNS/FQDNとTLS証明書SANの整合が重要。 |
| PostgreSQL Streaming Replication | no | DB HA構成時 | Primary/Standby同期。 | 同期/非同期の選択はRPO要件で決める。 |
| PostgreSQL TLS server certificate | no | DB通信暗号化時 | DB接続の暗号化とサーバ認証。 | `sslmode=verify-full` では接続先名と証明書名を一致させる。 |
| PostgreSQL TLS client certificate | no | Admin Tools強化時 | Admin Tools接続元の証明書認証。 | DBパスワードに加えて相互認証を行う。 |
| logrotate | no | 運用時 | 監査ログ/CSVログ/DBログのローテーション。 | 設定例は `LOGROTATE.md`。 |

### Compatibility Notes

- Plugin JARはJava 21でビルドする。
- IdP plugin配布物にはruntime依存JARとしてHikariCP、PostgreSQL JDBC Driver、ZXing coreを含める。
- Shibboleth IdP、Jetty、Servlet API、OpenSAML、SLF4J、TOTP plugin APIはruntime側提供として扱う。
- Admin Tools単体ZIPには、DB操作に必要なJARとCLIを含めるが、DB password、TLS private key、client certificate、sequence secretは含めない。
- WebAuthn/PasskeyはFQDNとHTTPSを前提とし、IPアドレスURLでの本番利用は避ける。
