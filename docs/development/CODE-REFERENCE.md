# 2FAS-KW Code Reference

この文書は、保守時に最初に確認するクラス、主要メソッド、対応テストをまとめた索引です。
全private helperの一覧ではありません。クラスの完全なAPIはソースを参照してください。

## Maintenance rule

初回作成時の参照基準はコミット`1fc8fc8`です。クラスの追加、責務移動、主要入口の改名、
対応テストの変更を行う場合は、この文書を同じpull requestで更新します。

## Runtime and configuration

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixRuntime` | `idpHome()`, `repository()`, `auditLogger()` | IdP runtimeの共有入口 |
| `GraphicalMatrixConfig` | `load()`, `resolveGraphicalToken()`, `validateSequence()` | 画像行列・challenge・表示・lockout設定の読込と検証 |
| `GraphicalMatrixSaveDataConfig` | `load()`, `isLdap()` | DB/LDAP保存先の選択 |
| `GraphicalMatrixDbConfig` | `load()`, pool getters | JDBC設定の検証 |
| `GraphicalMatrixDataSource` | `getConnection()`, `close()` | HikariCPまたは直接JDBC接続の管理 |
| `GraphicalMatrixDataSourceListener` | Servlet context lifecycle | webapp停止時のpool解放 |
| `GraphicalMatrixConfigCheckTool` | CLI entry point | package/IdP/config整合性検査 |

設定値を増やす場合は、templateへの追加だけでは不十分です。loaderの既定値・範囲検証、
getter、config check、`CONFIG-REFERENCE.md`、unit testを更新します。

## Authentication web layer

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixStartServlet` | `doGet()` | userとflowを解決し、状態に応じた開始画面を表示 |
| `GraphicalMatrixVerifyServlet` | `doPost()` | challenge回答、TOTP登録確認、強制sequence変更を処理 |
| `GraphicalMatrixChangeServlet` | `doGet()`, `doPost()` | 自己管理の状態machineを駆動 |
| `GraphicalMatrixGraphicalServlet` | `doGet()`, `doHead()` | 設定で許可された画像だけを配信 |
| `GraphicalMatrixAssetServlet` | `doGet()`, `doHead()`, `stylesheet()` | CSS assetとresponsive変数を配信 |
| `GraphicalMatrixViewRenderer` | `renderChallenge()`ほか | HTML templateへescape済み値を埋め込む |
| `GraphicalMatrixPasswordUserResolver` | principal resolution | Password flowのuserをExternal flowへ引き継ぐ |
| `GraphicalMatrixSupport` | flow/session helpers | WebFlowとの成功・失敗handoffを共通化 |

`GraphicalMatrixStartServlet`と`GraphicalMatrixVerifyServlet`はsessionに保存したflow key、
期限、userを信頼境界として扱います。認証画面やPOST parameterを変更する場合は、両Servletと
`GraphicalMatrixViewRenderer`を一緒に確認します。

## Enrollment repository

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixRepository` | `findEnrollment()`, `verify()`, `updateSequence()`, `updateMfaMethod()` | DB/LDAP共通のenrollment API |
| `GraphicalMatrixEnrollment` | accessors | challenge判定に必要なsnapshot |
| `GraphicalMatrixMfaSettings` | accessors | MFA方式とTOTP登録状態のsnapshot |
| `GraphicalMatrixVerifyResult` | result factories | success、failure、lock、enroll required、storage errorの表現 |
| `GraphicalMatrixLockoutPolicy` | policy calculation | 失敗回数からlock期限を計算 |
| `GraphicalMatrixLdapEnrollmentStore` | repository-compatible methods | LDAP保存時の読込・atomic更新 |

主要な`GraphicalMatrixRepository`操作は次のとおりです。

| Method | State effect |
| --- | --- |
| `findEnrollment()` | 読取のみ |
| `verify()` | 成功時に失敗状態をclear、失敗時にcounter/lockを更新 |
| `verifyForSequenceChange()` | 自己管理前の現在sequence確認 |
| `updateSequence()` | sequence、初期sequence、強制変更状態を更新 |
| `findMfaSettings()` | MFA方式とTOTP状態を読取 |
| `prepareTotpRegistration()` | seedを生成しTOTPを`PENDING`へ遷移 |
| `verifyAndActivateTotp()` | 登録codeを検証しTOTPを`ACTIVE`へ遷移 |
| `updateMfaMethod()` | MFA方式を変更し、必要な関連状態を整合化 |
| `activateWebAuthnIfMethodCurrent()` | 期待する旧方式のときだけWebAuthnへ切替 |

DB/LDAPの状態契約は[DATA-STORAGE.md](./DATA-STORAGE.md)を参照してください。

## Protected storage

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixSequenceStorage` | `load()`, `encode()`, `matches()`, `storedMode()`, `usable()` | sequence保存形式と照合 |
| `GraphicalMatrixTotpSeedStorage` | `load()`, `encode()`, `decode()`, `storedMode()` | TOTP seedの可逆保護 |
| `GraphicalMatrixTotpSupport` | `newBase32Seed()`, `otpauthUrl()`, `qrSvg()`, `verify()` | TOTP登録用の生成と確認 |
| `GraphicalMatrixSequenceMigrationTool` | CLI entry point | sequence保存形式の計画・移行 |
| `GraphicalMatrixTotpSeedMigrationTool` | CLI entry point | TOTP seed保存形式の計画・移行 |

保存prefixやcanonicalizationを変更すると既存レコードとの互換性へ影響します。変更時は
runtime読込、管理CLI、CSV import、migration、config checkを横断して確認します。

## MFA policy and flow selection

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixMfaDecisionStrategy` | `apply()` | MFA要否と利用者別flowを決定 |
| `GraphicalMatrixMfaPolicy` | `parse()`, `evaluate()` | SP/IP ruleを指定順で評価 |
| `GraphicalMatrixMfaPolicyConfig` | policy file helpers | 全体MFA設定の構造化 |
| `GraphicalMatrixSpMfaConfig` | `render()`, `managedDrift()` | CLI管理SPのMFA設定を生成・検査 |
| `GraphicalMatrixTotpSeedSource` | seed lookup | Shibboleth TOTP Pluginへactive seedを供給 |

新しいMFA方式を追加するときは、DB値だけを追加しないでください。方式の正規化、flow選択、
登録・解除、自己管理、管理CLI、監査、未登録時fallbackを一体として設計します。

## Self-service and WebAuthn

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixSelfServiceAuthentication` | `validate()` | Shibboleth再認証結果を検証 |
| `GraphicalMatrixSelfServiceSession` | `initialize()`, `consume()`, `clear()` | 一回限りself-service handoff |
| `InitializeGraphicalMatrixSelfService` | WebFlow action | 認証済みprincipalからsessionを開始 |
| `CheckGraphicalMatrixSelfServiceEnabled` | WebFlow action | profile有効化をfail closedで判定 |
| `GraphicalMatrixWebAuthnRegistrationSession` | `initialize()`, `consume()`, `clear()` | WebAuthn登録要求を一回限りで保持 |
| `GraphicalMatrixWebAuthnRegistrationHook` | `accept()` | 公式pluginの登録成功後にMFA方式を切替 |
| `GraphicalMatrixLdapAuthenticator` | `authenticate()` | legacy自己管理LDAP login |
| `GraphicalMatrixLdapLoginRateLimiter` | `isLimited()`, `recordFailure()`, `clear()` | legacy loginのuser/IP rate limit |

自己管理は認証方式の変更を行うため、通常の設定画面より高い再認証保証を必要とします。
session TTL、consume semantics、同時変更防止を弱める変更は避けます。

## Admin API

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixApiConfig` | `load()`, `allowedIp()`, `validBearer()` | APIの有効化、network/token認可、rate limit |
| `GraphicalMatrixAdminApiServlet` | `service()`, HTTP handlers | health、graphical、user管理API |
| `GraphicalMatrixCidrSet` | parse/match | CIDR allowlist判定 |
| `GraphicalMatrixJson` | read/write helpers | size制限付きJSON処理 |

APIは任意機能で、既定では無効です。route追加時はOpenAPI、token/CIDR認可、responseの
sequence除外、JSON body上限、監査、curl testを更新します。

## SP management

| Class | Main methods | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixSpManagementTool` | `execute()`とcommand handlers | SP lifecycle、revision、MFA全体設定 |
| `GraphicalMatrixSpGovernanceTool` | nested command handlers | 属性catalog/profile、resolver、SP access policy |
| `GraphicalMatrixSpManagementConfig` | `load()`とpath/policy getters | SP管理設定と管理対象path |
| `GraphicalMatrixSpMetadata` | `fromFile()`, `fromUrl()`, `parseExisting()` | metadata取得・安全なparse・検証 |
| `GraphicalMatrixSpRegistry` | `load()`, `put()`, `remove()`, `save()` | managed SP registry |
| `GraphicalMatrixSpXmlConfig` | provider/filter operations | Shibboleth XMLの管理block更新 |
| `GraphicalMatrixSpFiles` | `atomicWrite()`, `appendAudit()` | atomic file updateと監査追記 |
| `GraphicalMatrixAttributeCatalog` | `load()`, policy/profile operations | 属性governance catalog |
| `GraphicalMatrixAttributeDiscovery` | discovery operations | resolver、registry、runtime観測の統合 |
| `GraphicalMatrixSpAccessPolicyStore` | `load()`, `save()`, `put()`, `remove()` | SP別access policy保存 |
| `GraphicalMatrixSpAccessEvaluator` | `evaluate()` | 属性値に対するallow/deny判定 |
| `GraphicalMatrixSpAccessFunction` | `apply()` | Shibboleth ContextCheckとのadapter |

詳細は[SP-MANAGEMENT-INTERNALS.md](./SP-MANAGEMENT-INTERNALS.md)を参照してください。

## Administrative CLI and provisioning

| Class or script | Main entry | Responsibility |
| --- | --- | --- |
| `GraphicalMatrixEnrollmentAdminTool` | Java `main()` | enrollment追加・変更・無効化・状態表示 |
| `GraphicalMatrixSequenceTool` | Java `main()` | sequenceのencode・診断 |
| `GraphicalMatrixCsvExportTool` | Java `main()` | 管理用CSV export |
| `graphicalmatrix-db.sh` | shell command dispatch | classpath、設定、psql/Java CLIの統合 |
| `graphicalmatrix-sp.sh` | shell command dispatch | SP管理Java CLIの起動 |
| `graphicalmatrix-csv-import-runner.sh` | systemd service entry | CSV snapshot、dry-run、apply、archive |

CSV形式変更時はshell parser、Java admin tool、README/command reference、systemd path unit、
package regressionを一緒に確認します。

## Dashboard

| Class | Responsibility |
| --- | --- |
| `DashboardMain` | process entry point |
| `DashboardConfig` | UI、ingest、TLS、retention設定 |
| `DashboardServer` | HTTP UI/APIとingest endpoint |
| `DashboardStore` | normalized eventの永続化・検索・retention |
| `DashboardAgent` | 監査logのtailとbatch送信 |
| `AuditLogParser`, `EventNormalizer` | raw lineから共通eventへ変換 |
| `PrivacyFilter` | 表示・保存前の秘匿化 |
| `DashboardAuthorizer` | dashboard access認可 |

Dashboardは別Maven moduleです。plugin本体のunit testだけでは検証されないため、
`dashboard` moduleのtestとpackage buildも実行します。

## Change impact map

| Change | Start with | Also inspect |
| --- | --- | --- |
| 認証画面・POST項目 | Start/Verify Servlet | ViewRenderer、session、CSS、security regression |
| lockout | Repository、LockoutPolicy | LDAP store、self-service、audit、state tests |
| sequence保存形式 | SequenceStorage | migration、Admin Tools、CSV、config check |
| TOTP登録 | TotpSupport、Repository | TotpSeedStorage、SeedSource、self-service |
| WebAuthn登録 | registration session/hook | official plugin config、method transition |
| MFA評価順 | MfaPolicy | DecisionStrategy、SP MFA CLI、FAQ |
| SP metadata制約 | SpMetadata | config、CLI output、registry、tests |
| 属性release | GovernanceTool | catalog、attribute-filter、resolver discovery |
| SP access | AccessPolicy/Evaluator | ContextCheck XML、audit、runtime reload |
| Admin API | ApiServlet | ApiConfig、OpenAPI、curl/security tests |
| responsive UI | AssetServlet、CSS | Config、template、desktop/mobile tests |

## Test mapping

| Area | Primary tests |
| --- | --- |
| Config and runtime security | `GraphicalMatrixSecurityRegressionTest`, `GraphicalMatrixLockoutConfigTest`, `GraphicalMatrixSaveDataConfigTest` |
| GraphicalMatrix verification | `GraphicalMatrixVerifyServletTest`, `GraphicalMatrixRepositoryStateTest`, `GraphicalMatrixSequenceStorageTest` |
| Responsive assets | `GraphicalMatrixAssetServletTest`, `GraphicalMatrixResponsiveConfigTest` |
| MFA policy | `GraphicalMatrixMfaDecisionStrategyTest`, `GraphicalMatrixMfaPolicyTest` |
| Self-service | `GraphicalMatrixSelfServiceAuthenticationTest`, `GraphicalMatrixSelfServiceSessionTest`, `InitializeGraphicalMatrixSelfServiceTest` |
| WebAuthn handoff | `GraphicalMatrixWebAuthnRegistrationSessionTest` |
| LDAP | `GraphicalMatrixLdapConfigTest`, `GraphicalMatrixLdapEnrollmentStoreTest`, `GraphicalMatrixLdapLoginRateLimiterTest` |
| Admin API | `GraphicalMatrixAdminApiServletJsonBodyTest` |
| SP management | `GraphicalMatrixSpManagementToolTest`, `GraphicalMatrixSpMetadataTest`, `GraphicalMatrixSpRegistryTest`, `GraphicalMatrixSpFilesTest` |
| Attribute/access | `GraphicalMatrixAttributeCatalogTest`, `GraphicalMatrixAttributeDiscoveryTest`, `GraphicalMatrixSpAccessPolicyTest`, `GraphicalMatrixSpAccessXmlConfigTest` |
| Package integration | `scripts/tests/package-regression.sh`, `scripts/tests/security-regression.sh`, `scripts/tests/plugin-config-permission-regression.sh` |

最低確認は`mvn test`です。配布物、権限、shell、別moduleへ触れた変更では、該当するpackage
buildとshell regressionも実行してください。

