# 2FAS-KW Architecture

この文書は、2FAS-KWの主要コンポーネント、責務境界、実行時の依存関係を
保守担当者向けに説明します。導入・運用手順は
[INSTALL.md](../INSTALL.md)および[COMMAND-REFERENCE.md](../COMMAND-REFERENCE.md)を
参照してください。

## 文書の基準と更新ルール

この文書は特定のリリース番号へ固定しません。初回作成時に参照したソースは
コミット`1fc8fc8`です。

次の変更を行う場合は、同じpull requestで該当する開発文書を更新してください。

- 認証フロー、Servlet URL、WebFlow定義を変更する。
- enrollmentの列、状態、保存形式を変更する。
- SP管理registry、metadata、属性profile、MFA方針を変更する。
- 設定キー、既定値、設定ファイルの配置を変更する。
- 主要クラスの責務またはテストの対応関係を変更する。

文書と実装が一致するかは、`version.ini`、Javaソース、配布テンプレート、テストを
正とし、文書だけを根拠に判断しないでください。

## システム構成

```text
Browser
  |
  v
Shibboleth IdP WebFlow
  |-- authn/Password
  |-- MFA decision strategy
  |     |-- authn/External -> 2FAS-KW GraphicalMatrix/TOTP registration
  |     |-- authn/TOTP     -> Shibboleth TOTP Plugin
  |     `-- authn/WebAuthn -> Shibboleth WebAuthn Plugin
  |
  |-- 2FAS-KW self-service profile
  `-- SAML response to SP

2FAS-KW runtime
  |-- enrollment repository -> PostgreSQL or LDAP
  |-- sequence/TOTP storage protection
  |-- audit log
  `-- optional Admin API

Administrative components
  |-- SP management CLI -> registry, metadata, attribute filter, MFA policy
  |-- Admin Tools       -> enrollment CLI and CSV provisioning
  `-- Dashboard         -> normalized audit event store and UI
```

## コンポーネント境界

| Component | Primary responsibility | Main location |
| --- | --- | --- |
| IdP plugin runtime | 認証、自己管理、設定読込、enrollment更新 | `src/main/java/io/github/yasakawa/faskw` |
| Plugin integration | Shibboleth plugin/module登録 | `src/main/java/io/github/yasakawa/faskw/plugin` |
| IdP resources | WebFlow、Spring bean、Servlet、view、CSS | `src/main/resources`, root templates |
| SP management | SP metadata、registry、属性release、SP別MFA・アクセス制御 | `GraphicalMatrixSpManagementTool`, `GraphicalMatrixSpGovernanceTool` |
| Admin Tools | DB管理CLI、CSV import/export、systemd連携 | `scripts`, distribution build logic |
| Dashboard | 監査イベント取込、正規化、保存、表示 | `dashboard` |
| Packaging | plugin/Admin Tools/Dashboard配布物の生成・署名 | `scripts/build-*-package.sh` |

## IdP plugin runtime

`GraphicalMatrixRuntime`はIdP home、repository、監査loggerへの共通入口です。
認証Servletはこの入口を通じて保存先を取得し、DBかLDAPかを直接判定しません。

`GraphicalMatrixRepository`は保存先のfacadeです。`graphicalmatrix.savedata`に応じて、
JDBC処理または`GraphicalMatrixLdapEnrollmentStore`へ委譲します。この境界により、
Servlet、MFA判定、自己管理は同じAPIを使用できます。

`GraphicalMatrixConfig`は画像行列、challenge、lockout、自己管理、表示templateなどの
runtime設定を検証済みの値へ変換します。DB接続は`GraphicalMatrixDbConfig`、LDAP接続は
`GraphicalMatrixLdapConfig`、保存先選択は`GraphicalMatrixSaveDataConfig`が担当します。

## Web endpoints

`web.xml`がplugin固有のServletを次のURLへ割り当てます。

| URL | Servlet | Role |
| --- | --- | --- |
| `/graphicalmatrix/start` | `GraphicalMatrixStartServlet` | challenge開始、登録状態の判定 |
| `/graphicalmatrix/verify` | `GraphicalMatrixVerifyServlet` | 選択値、TOTP登録確認、強制変更の処理 |
| `/graphicalmatrix/change` | `GraphicalMatrixChangeServlet` | 自己管理操作 |
| `/graphicalmatrix/graphical` | `GraphicalMatrixGraphicalServlet` | 許可済み画像の配信 |
| `/graphicalmatrix/assets/*` | `GraphicalMatrixAssetServlet` | CSSなどの配信 |
| `/graphicalmatrix-admin/api/v1/*` | `GraphicalMatrixAdminApiServlet` | 任意の管理API |

URLの追加・変更時は`web.xml`、Servlet、security constraint、package regressionを
同時に確認します。

## Shibboleth integration

`GraphicalMatrixMfaDecisionStrategy`はShibboleth MFA flowから呼び出され、最初にSP・
送信元IPに対するMFA要否を評価し、次に利用者の`mfa_method`から実行flowを返します。
GraphicalMatrixは`authn/External`を使用します。TOTPとWebAuthnの認証器本体は、対応する
Shibboleth公式pluginのflowを使用します。

自己管理は通常のSAML SPではなく、IdP内の専用profileとして実行されます。Passwordと
現在の第二要素を再確認し、短時間の一回限りhandoffを経て変更画面へ移ります。

詳細は[AUTHENTICATION-FLOW.md](./AUTHENTICATION-FLOW.md)を参照してください。

## Administrative paths

SP管理CLIは実行中IdPの内部状態を直接編集しません。管理対象ファイルを検証・更新し、
必要なreload endpointを呼び出します。managed metadata、registry、revision snapshotを
一体として扱います。詳細は
[SP-MANAGEMENT-INTERNALS.md](./SP-MANAGEMENT-INTERNALS.md)を参照してください。

Admin ToolsはIdP web processから分離した管理CLIです。CSV provisioningではincoming
directoryへのrenameを完了通知として使い、runnerがprocessingへ移動してからdry-runと
適用を行います。

Dashboardは認証経路の必須要素ではありません。監査イベントを取り込み、秘匿化・
正規化した情報を表示する独立コンポーネントです。Dashboard停止時もIdP認証を継続できる
境界を維持します。

## Configuration ownership

| File | Owner in code | Purpose |
| --- | --- | --- |
| `conf/graphicalmatrix/graphicalmatrix.properties` | `GraphicalMatrixConfig`, storage classes | runtime、保存形式、自己管理 |
| `conf/graphicalmatrix/db.properties` | `GraphicalMatrixDbConfig` | JDBC接続、pool |
| `conf/graphicalmatrix/ldap.properties` | `GraphicalMatrixLdapConfig` | LDAP enrollment保存 |
| `conf/graphicalmatrix/mfa-policy.properties` | `GraphicalMatrixMfaPolicy` | MFA要否と評価順 |
| `conf/graphicalmatrix/sp-management.properties` | `GraphicalMatrixSpManagementConfig` | SP管理、metadata制約、属性・access機能 |
| `conf/graphicalmatrix/api.properties` | `GraphicalMatrixApiConfig` | 管理APIの有効化・認可 |
| `conf/metadata-providers.xml` | Shibboleth + `GraphicalMatrixSpXmlConfig` | metadata provider |
| `conf/attribute-filter.xml` | Shibboleth + SP management | SP向け属性release |

完全な設定項目は[CONFIG-REFERENCE.md](../CONFIG-REFERENCE.md)を参照してください。

## Security boundaries

- Browser入力はServlet境界で検証し、sessionに保存したflow識別子と照合します。
- 認証失敗回数とlockout更新は保存先のatomic operationとして扱います。
- sequenceとTOTP seedは用途の異なる保存形式・鍵管理を使用します。
- SP metadataはサイズ、XML parser、entityID、ACS host、取得元hostを検証します。
- 管理操作はdry-runを既定とし、破壊的操作では明示的な確認値を要求します。
- registry、metadata、秘密情報、backupは実行主体ごとに権限を分離します。
- API、Admin Tools、Dashboardは個別に有効化し、認証経路の既定依存にしません。

脆弱性情報や実環境固有の防御設定は公開設計書へ記録せず、
[SECURITY.md](../SECURITY.md)と組織の非公開運用資料で管理します。

## Related documents

- [CODE-REFERENCE.md](./CODE-REFERENCE.md): 主要クラスと変更影響の索引
- [AUTHENTICATION-FLOW.md](./AUTHENTICATION-FLOW.md): 認証・自己管理の呼出関係
- [SP-MANAGEMENT-INTERNALS.md](./SP-MANAGEMENT-INTERNALS.md): SP管理CLI内部設計
- [DATA-STORAGE.md](./DATA-STORAGE.md): DB、LDAP、秘密情報、状態遷移

