# Data Storage and State Model

この文書は、enrollmentの論理schema、DB/LDAP保存、sequence/TOTP seedの保護形式、状態遷移、
管理系データのownershipを説明します。

## Storage abstraction

`GraphicalMatrixRepository`は認証・自己管理に対する保存facadeです。

```text
GraphicalMatrixRepository
  |-- graphicalmatrix.savedata=db
  |     -> GraphicalMatrixDbConfig
  |     -> GraphicalMatrixDataSource
  |     -> graphicalmatrix_enrollment
  `-- graphicalmatrix.savedata=ldap
        -> GraphicalMatrixLdapConfig
        -> GraphicalMatrixLdapEnrollmentStore
        -> configured LDAP attributes
```

上位層は`findEnrollment()`、`verify()`、`updateSequence()`、`findMfaSettings()`などの同じAPIを
使います。新しい保存backendを追加する場合も、この状態契約を維持します。

## Enrollment schema

PostgreSQLの正規schemaは`postgresql-schema.sql`です。

| Column | Purpose |
| --- | --- |
| `user_id` | primary key、第一認証principalと対応 |
| `sequence` | 現在のGraphicalMatrix sequence、保護形式を含む |
| `initial_sequence` | 強制変更・管理表示で使う初期sequence情報 |
| `status` | enrollmentの利用可否 |
| `failed_count` | GraphicalMatrix照合の連続失敗回数 |
| `locked_until` | lock解除時刻、epoch milliseconds |
| `mfa_method` | `GraphicalMatrix`、`TOTP`、`WebAuthn` |
| `totp_seed` | 保護されたTOTP seed、未設定時null |
| `totp_status` | `UNREGISTERED`、`PENDING`、`ACTIVE` |
| `totp_registered_at` | TOTP登録確定時刻 |
| `last_success_at` | 最終成功時刻 |
| `force_sequence_change` | 次回にsequence変更を要求するflag |
| `state_version` | atomic更新・競合検出用version |
| `created_at`, `updated_at` | record lifecycle時刻 |

時刻はDB型のtimestampではなくepoch millisecondsとして扱います。表示時にCLIが読みやすい
形式へ変換します。

## Core state transitions

### Enrollment status

```text
missing
  -> add/provision -> ACTIVE

ACTIVE
  -> disable/deprovision -> DISABLED

DISABLED
  -> enable or add/reactivate -> ACTIVE
```

deprovisionの既定動作は物理削除ではなくdisableです。認証履歴との整合、誤操作からの復旧、
監査を保つためです。

### GraphicalMatrix verification

```text
ACTIVE, unlocked
  |-- success -> failed_count=0, locked_until=0, last_success_at=now
  `-- failure -> failed_count++
                  `-- threshold reached -> locked_until=policy deadline

locked_until <= now
  -> next verification may proceed

admin unlock
  -> failed_count=0, locked_until=0
```

各mutationで`state_version`と`updated_at`を進めます。DBではrow lockとtransactionを使い、
同時requestによる失敗counterのlost updateを防ぎます。

### TOTP registration

```text
UNREGISTERED
  -> prepare -> PENDING + protected seed
  -> valid confirmation code -> ACTIVE + registered_at

PENDING
  -> retry registration -> same pending seed
  -> reset/change method -> registration state cleared as defined by operation

ACTIVE
  -> normal auth uses Shibboleth TOTP Plugin
```

TOTPへ方式を設定しただけでは通常TOTP認証を開始しません。seedと`ACTIVE`状態が揃うまで、
2FAS-KW登録flowへ戻します。

### WebAuthn method activation

```text
current method
  -> create one-time registration request
  -> official WebAuthn Plugin stores credential
  -> success hook consumes matching request
  -> compare-and-set method to WebAuthn
```

WebAuthn credentialそのものは2FAS-KW enrollment tableへ保存しません。Shibboleth WebAuthn
Pluginが設定されたStorageServiceへ保存します。

## Sequence storage formats

`GraphicalMatrixSequenceStorage`が形式を選択し、prefixで既存recordを識別します。

| Logical mode | Stored prefix | Property |
| --- | --- | --- |
| plaintext compatibility | no protected prefix | `plaintext` |
| keyword-based encryption | `kw1:` | `keyword` |
| AES-GCM encryption | `aesgcm1:` | `aes-gcm` |
| salted HMAC comparison | `hsp1:` | `hash` |

`hash`は照合専用で元sequenceを復元しません。`keyword`と`aes-gcm`は移行・表示要件に応じて
復号可能です。production modeでは保護されていない保存形式を受け入れない構成にします。

sequence比較では、`graphicalmatrix.order`と`graphicalmatrix.allow_duplicates`を含む
canonicalization contractが重要です。これを変えると、同じ画像選択でも既存hashと一致しなく
なる可能性があります。

秘密値はpropertiesへ直接記載せず、root管理のcredential fileから読みます。秘密fileの実値、
backup場所、配布方法は公開文書へ記録しません。

## TOTP seed storage

TOTP seedは通常認証ごとに取得するため、hashだけでは保存できません。
`GraphicalMatrixTotpSeedStorage`は設定とsequence storage modeから有効な可逆形式を決めます。

sequence用pepperとTOTP用暗号鍵は目的が異なります。同じfileや同じ鍵materialを共有しません。
鍵を失うと既存seedを復号できず、利用者のTOTP再登録が必要になります。鍵rotationは
`SEQUENCE-STORAGE-MIGRATION.md`の計画・検証・切替手順に従います。

## LDAP mapping

LDAP保存では、`ldap.properties`の`graphicalmatrix.ldap.attr.*`が論理fieldをLDAP attributeへ
対応付けます。例えばsequence、status、failure count、MFA方式、TOTP状態、state versionを
個別属性へ保存します。

LDAP schema、ACL、atomic modify/compareの能力がrepository契約を満たす必要があります。
第一認証用LDAP属性と2FAS-KW enrollment属性は同じentryへ置けますが、bind accountには必要な
属性だけのread/write権限を与えます。

IdP Attribute Resolverが読むLDAP属性と、2FAS-KWがenrollmentとして保存するLDAP属性は別の
設定です。`ldapsearch`で値が見えても、`attribute-resolver.xml`に接続・AttributeDefinitionが
なければAACLIやSP access判定には現れません。

## Connection and pooling

`GraphicalMatrixDbConfig`はJDBC URL、user、password file、pool設定を読みます。
`GraphicalMatrixDataSource`はpool有効時にHikariCPを共有し、Servlet context終了時に
`GraphicalMatrixDataSourceListener`がcloseします。

pool sizeはIdP node単位です。複数node構成では、全node、Admin Tools、migration、監視の接続数を
合算してPostgreSQL上限を設計します。

`graphicalmatrix.db.autoInit`は検証用途を除き無効を基本とし、schema変更は管理されたmigration
として適用します。

## Administrative data

### SP management

SP registry、managed metadata、attribute catalog、access policy、revisionはfilesystemに保存され、
`GraphicalMatrixSpFiles.atomicWrite()`で置き換えます。これらはenrollment DBとは別backup単位です。

### Admin Tools and CSV provisioning

CSV runnerは次のdirectory stateを使います。

```text
incoming/*.csv
  -> atomic rename detected by systemd path unit
  -> processing/<timestamp>-*.csv
       |-- success -> processed/
       `-- failure -> failed/
```

upload途中のfileをrunnerが読まないよう、送信側は一時拡張子で完成させてから`.csv`へrenameします。
runnerはimmutable snapshotに対してdry-runを行い、設定で許可されたactionだけを自動適用します。

CSVの`D` actionは既定でdisableです。大量disableには別上限を設け、通常のadd/modifyと区別します。
import logはIdP runtime logではなくAdmin Tools hostに保存します。

### Dashboard

Dashboardはraw audit eventをparseし、`PrivacyFilter`を通したnormalized eventを独自storeへ保存します。
認証判定の正規状態には使いません。Dashboard storeを失っても認証状態が変化しない設計です。

## Backup and restore boundaries

復旧単位は少なくとも次に分けます。

- enrollment DBまたはLDAP attributes。
- sequence/TOTPのstorage-format secrets。
- IdP設定とWebFlow resources。
- SP registry、managed metadata、revision、legacy snapshot。
- WebAuthn Pluginのcredential StorageService。
- Admin Toolsのprocessed/failed CSVと監査log。

DBだけをrestoreしても、対応するstorage-format secretがなければ保護済みsequence/seedを利用
できません。逆にsecret fileだけではenrollmentを復元できません。backup世代と復旧手順では
この組を同じ整合点として扱います。

## Tests

| Concern | Tests |
| --- | --- |
| DB state transition | `GraphicalMatrixRepositoryStateTest` |
| sequence formats | `GraphicalMatrixSequenceStorageTest` |
| storage configuration | `GraphicalMatrixSaveDataConfigTest`, `GraphicalMatrixSecurityRegressionTest` |
| LDAP state parity | `GraphicalMatrixLdapEnrollmentStoreTest`, `GraphicalMatrixLdapConfigTest` |
| LDAP storage mapping | `GraphicalMatrixLdapStorageConfigTest` |
| lockout/rate limit | `GraphicalMatrixLockoutConfigTest`, `GraphicalMatrixLdapLoginRateLimiterTest` |
| SP registry/files | `GraphicalMatrixSpRegistryTest`, `GraphicalMatrixSpFilesTest` |

schemaまたは保存形式を変更するpull requestでは、旧record読込、移行dry-run、rollback、同時更新、
秘密file欠落時のfail-closed動作をtest対象に含めます。

