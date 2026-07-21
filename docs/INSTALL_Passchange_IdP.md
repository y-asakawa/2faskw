# 2FAS-KW IdP自己管理フロー導入・運用

> **対象方式**: IdP内でShibbolethの再認証を完了した利用者に対して、
> GraphicalMatrixおよびMFA方式の変更画面を提供する方式である。
> 別hostnameの自己管理ポータルSPを追加する場合は、
> [INSTALL_Passchange_SP.md](./INSTALL_Passchange_SP.md) を参照する。

本機能はv1.2.4で実装した。Password + GraphicalMatrixによる強制再認証、
自己管理画面へのhandoff、変更メニュー表示までの動作をIdP上で確認済みである。
TOTP、WebAuthnおよびLDAP保存を組み合わせた動作は、導入環境ごとに本書の受入試験を実施する。

## 1. 目的

現在の `/idp/graphicalmatrix/change` は、2FAS-KWの通常Servletとして動作する。
利用者はLDAPのID・パスワードを入力し、現在のGraphicalMatrixを確認してから、
GraphicalMatrix sequenceまたはMFA方式を変更する。

本方式では、LDAPログイン画面を自己管理画面の本人確認手段として使わない。Shibboleth IdPの
Password認証と現在のMFA方式による再認証を完了してから、IdP内の自己管理画面を表示する。

対象は以下である。

- GraphicalMatrix sequenceの変更
- MFA方式の変更
- TOTPおよびWebAuthnの登録開始

ここでいう「パスワード変更」はGraphicalMatrix sequenceの変更である。LDAPの`userPassword`
変更は対象外とし、追加する場合はLDAP Password Modify Extended Operationおよびディレクトリの
パスワードポリシーを扱う別機能として設計する。

## 2. 方式の選択

| 項目 | IdP自己管理フロー | 外部自己管理SP |
| --- | --- | --- |
| 認証開始 | IdPの `/idp/profile/2faskw/self-service` | 別hostnameのSP |
| 変更画面 | IdPの `/idp/graphicalmatrix/change` | 別hostnameのSP |
| Shibboleth再認証 | IdP内部で直接実施 | SPからSAML AuthnRequestを送る |
| IdP-SP間チケット / mTLS | 不要 | 必要 |
| WebAuthn registration | 同一IdP originで自然に実施できる | IdP originへhandoffが必要 |
| 運用分離 | IdPと一体 | UI/配備をIdPから分離できる |
| 実装量 | 小さい | 大きい |

自己管理画面をIdPと同じ運用境界で管理できる場合は、IdP自己管理フローを推奨する。
外部ポータルや別組織による画面運用が必要な場合だけ、外部自己管理SP方式を選ぶ。

## 3. 実装構成

```text
利用者ブラウザ
  |
  v
/idp/profile/2faskw/self-service
  |
  +-- IdP Authentication subsystem
  |     Password + 現在のMFA方式を強制再認証
  |
  +-- 2FAS-KW Administrative Flow
  |     認証結果を検査し、同一HttpSessionへ一回限りのhandoffを作成
  |
  +-- /idp/graphicalmatrix/change?mode=idp-self-service
        既存画面、CSRF、期限、state version、Repository更新を再利用
```

自己管理画面、本人確認、保存処理は全てIdP内で完結する。別SP、SAML属性release、
IdP-SP間mTLS、自己管理チケットは使用しない。

## 4. 認証・認可モデル

### 4.1 新しいURL

pluginは以下のIdP profile URLを追加する。

```text
/idp/profile/2faskw/self-service
```

通常のServlet URLである `/idp/graphicalmatrix/change` を、Shibboleth認証を通さず
認証済み画面として扱うことはしない。Servlet単体にはShibbolethの認証コンテキストを
安全に取得し、再認証を要求する機構がないためである。従来LDAP経路を無効にした場合の
Servlet URLは、認証を省略せず、このAdministrative Flowの開始URLへリダイレクトする。

新URLはIdPのAdministrative Profile/WebFlowとして実装し、IdPの`ProfileRequestContext`、
`AuthenticationContext`、認証済みSubjectを使用する。利用者IDをURL、HTML form、
HTTP headerから受け取って本人として扱ってはならない。

### 4.2 fresh authentication

自己管理開始時には、過去のIdP SSO sessionだけを根拠にしない。IdPへ以下を要求する。

- Password認証
- 利用者に現在登録されているMFA方式
- 強制再認証に相当する認証要求

GraphicalMatrix、TOTP、WebAuthnのいずれを利用者が選択していても、現在の方式で
正常にMFAを完了したことを自己管理の本人確認根拠にする。TOTP/WebAuthn利用者へ
GraphicalMatrix入力を追加要求しない。

GraphicalMatrixを使う利用者では、現行のExternal authentication flowがMFAを担当する。
GraphicalMatrix External flowをForceAuthn中に実行できるよう、
`idp.authn.External.forcedAuthenticationSupported=true` を設定する。この設定はExternal flowが
強制再認証要求へ対応可能であることを宣言するもので、通常のSP認証を一律にForceAuthnへ
変更するものではない。

自己管理profileはMFAだけを利用可能な認証flowとして指定し、profile側でForceAuthnを
常に有効にする。Administrative Flowの設定値には`authn/`接頭辞を除いた`MFA`を指定するが、
実行後の認証結果ではFlow IDを`authn/MFA`として検査する。さらに2FAS-KWは、認証結果に今回実行された`authn/Password`と、
`authn/External`、`authn/TOTP`、`authn/WebAuthn`のいずれかが含まれることを検査する。MFA subflow
終了後は、Shibbolethが統合結果のSubjectへ追加する`AuthenticationResultPrincipal`から個別factorを
検査する。`MultiFactorAuthenticationContext`が残る環境では互換用の補助情報として参照する。
過去の認証結果、Passwordだけの完了、通常SP/IP向けMFAバイパスは受け入れない。

### 4.3 認可

認証完了後は`SubjectContext.getPrincipalName()`を取得し、前後の空白を除去して、
`[A-Za-z0-9._@-]+`に一致する利用者IDであることを検査する。
検査済みの値を2FAS-KW Repositoryの利用者IDとして直接使用する。Subjectが未取得、
利用者IDの形式が不正、登録状態が無効、またはロック中の場合は自己管理画面を開始せず、
利用者に一般的なエラーを表示して監査ログへ記録する。

本方式は利用者自身の登録だけを対象にする。別利用者の登録表示・更新、管理者による
強制reset、MFA喪失時の復旧は含めない。

## 5. 画面とトランザクション

### 5.1 画面遷移

```text
開始
  -> Shibboleth再認証
  -> 自己管理メニュー
       -> GraphicalMatrix変更
       -> MFA方式変更
       -> TOTP / WebAuthn登録開始
  -> 完了
```

既存の `change-menu.html`、`change-new.html`、`change-method.html`、
`GraphicalMatrixChangeServlet`の表示・保存処理を再利用する。Administrative Flowは認証のみを担当し、
認証済みSubjectを短寿命かつ一回限りのhandoffとして同一`HttpSession`へ保存する。

Servletは`mode=idp-self-service`でhandoffを消費した場合だけ、既存の変更許可状態を初期化する。
handoffは読み出し前にセッションから削除するため、ブラウザ再読込やURLの直接指定では再利用できない。

### 5.2 サーバ側状態

同一ブラウザのServlet `HttpSession`および既存変更セッションに、次の状態をサーバ側で保持する。

| 項目 | 内容 |
| --- | --- |
| `subject` | IdP認証コンテキストから取得し、空白除去と許可文字検査を行った利用者ID。 |
| 認証済みhandoff | Subject、有効期限、nonce、profile ID。同一セッションで一回だけ消費する。 |
| `expiresAt` | handoffの有効期限。`graphicalmatrix.selfservice.transactionTtlSeconds`で管理する。 |
| `csrfToken` | 画面POSTのCSRF対策。 |
| `stateVersion` | Repositoryの楽観ロック用世代番号。 |
| `forceSequenceRequired` | sequence変更を先に完了すべき状態かどうか。 |
| `sequenceChanged` | 今回のトランザクションでsequenceを更新済みかどうか。 |

Subject、認証時刻、認証強度はブラウザから受け取らない。画面はCSRF tokenと利用者の選択だけを
送信し、Servletはサーバ側セッションのSubjectに対してのみRepositoryを呼び出す。HTML formの
`user`はセッション内Subjectとの一致検査にだけ使用し、本人決定の根拠にはしない。

### 5.3 sequence変更

新しい画像列の表示順はサーバ側で生成する。保存時には以下を検査する。

- 選択数、存在する画像ID、順序、重複許可設定。
- 現在のsequenceと同一でないこと。
- `stateVersion` が現在の登録状態と一致すること。
- 登録が有効で、ロック中ではないこと。
- 自己管理トランザクションとCSRF tokenが有効期限内であること。

保存成功時にはRepository経由でsequenceを更新する。DB保存とLDAP保存の差異は、
WebFlowや画面へ公開しない。

### 5.4 MFA方式変更

`force_sequence_change` が有効な場合、新しいGraphicalMatrixを保存するまでMFA方式変更を
許可しない。この制約は画面表示だけでなく、変更サービスで必ず検査する。

方式変更時にも`stateVersion`を検査する。更新後は、TOTPおよびWebAuthnで登録完了前の状態を
明確に区別し、登録に失敗または取消した場合に利用不能なMFA方式だけが残らないようにする。

## 6. TOTPおよびWebAuthn

### 6.1 TOTP

自己管理画面でTOTPを選択した後の`PENDING`状態、seed生成、暗号化、QR code表示、登録完了は、
2FAS-KWの既存TOTP動作を使用する。今回追加したAdministrative Flowは、TOTP登録処理そのものを
置き換えない。seedをHTML hidden field、URL、監査ログ、通常のAPI応答へ入れてはならない。

### 6.2 WebAuthn

WebAuthn credentialはRP IDとブラウザoriginに結び付く。本方式ではIdP自身のWebAuthn
registration URLへ同一originで遷移できるため、別SP方式より安全かつ単純である。

自己管理画面でWebAuthnを選択した後の登録は、既存WebAuthn pluginの管理用登録flowを使用する。
今回追加したAdministrative Flowはcredential登録処理そのものを置き換えない。既存WebAuthnの
RP ID、origin、管理用登録URLの認証・認可条件を事前に確認する。

## 7. ソースコード変更設計

### 7.1 認証検査とhandoff

実装済みの主なクラスは以下である。

| Class | Role |
| --- | --- |
| `GraphicalMatrixSelfServiceAuthentication` | profile ID、ForceAuthn、Password、第二要素、Subjectを検査する。 |
| `CheckGraphicalMatrixSelfServiceEnabled` | 認証開始前にself-service有効設定を検査する。 |
| `InitializeGraphicalMatrixSelfService` | セッションIDを更新し、一回限りのhandoffを作成する。 |
| `GraphicalMatrixSelfServiceSession` | handoffの作成、期限検査、消費、削除を行う。 |
| `GraphicalMatrixChangeServlet` | handoffから既存変更セッションを初期化し、更新処理を再利用する。 |

### 7.2 IdP profile/WebFlow

新規profile/WebFlowは次を担当する。

- IdP Authentication subsystemへfresh Password + MFA認証を要求する。
- `ProfileRequestContext`からSubjectと認証結果を検証する。
- 短寿命かつ一回限りのhandoffを同一`HttpSession`へ作成する。
- Web Flowの`contextRelative:`指定により、
  `/idp/graphicalmatrix/change?mode=idp-self-service`へリダイレクトする。

`contextRelative:`を付けない相対URLは、実行中のprofile URL配下へ解決され、
`/idp/profile/idp/graphicalmatrix/change`のような存在しないURLになるため使用しない。
利用者がhandoff URLを直接入力する必要はない。

flow定義はplugin JAR内の
`META-INF/net/shibboleth/idp/flows/2faskw/self-service/`、descriptorは
`META-INF/net.shibboleth.idp/postconfig.xml`に配置される。plugin導入後に個別のflow XMLを
`/opt/shibboleth-idp`へコピーする必要はない。

### 7.3 既存Servletとの互換性

既定では`/idp/graphicalmatrix/change`のLDAPログイン経路を残す。
`graphicalmatrix.change.legacyLdapLoginEnabled=false`にすると、GETおよびLDAPログインPOSTは
自己管理profileへリダイレクトされ、ServletからLDAP bindを実行しない。

将来、既存URLを廃止するかどうかは、自己管理profileの導入試験、利用者導線、管理者向け復旧手順を
確認してから別途決定する。

### 7.4 設定

以下は実装済みの設定である。稼働中のIdPで修正するファイルは次である。

```text
/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
```

リポジトリ直下の`graphicalmatrix.properties`と、配布物内の
`conf/graphicalmatrix/graphicalmatrix.properties.idpnew`は導入用テンプレートである。
これらを変更しただけでは、稼働中のIdP設定には反映されない。

```properties
# 配布直後は無効。
graphicalmatrix.selfservice.enabled = false

# 認証profileから変更画面へ渡す一回限りの状態の有効期限。60から900秒。
graphicalmatrix.selfservice.transactionTtlSeconds = 600

# 既存LDAPログイン型change画面を残すかどうか。
graphicalmatrix.change.legacyLdapLoginEnabled = true
```

`graphicalmatrix.selfservice.enabled=true` にする前に、MFA設定、WebAuthn/TOTP認証、監査ログ、
DB/LDAP保存を構成検査で確認する。

## 8. 導入手順

### 8.1 pluginとMFA設定

通常の2FAS-KW plugin導入を完了し、`authn/MFA`がPasswordの後に現在のMFA方式を選択することを
確認する。Administrative Flow定義はplugin JARに含まれるため、flow XMLの手動コピーは不要である。
Administrative Flowから利用するauthentication flowの制限値には、`authn/`接頭辞を除いた`MFA`を
指定する。plugin内の定義はこの形式で同梱されるため、利用者によるXML編集は不要である。

`/opt/shibboleth-idp/conf/authn/authn.properties`でExternal flowのForceAuthn対応を有効にする。

```properties
idp.authn.flows = MFA
idp.authn.External.externalAuthnPath = contextRelative:/graphicalmatrix/start
idp.authn.External.nonBrowserSupported = false
idp.authn.External.passiveAuthenticationSupported = false
idp.authn.External.forcedAuthenticationSupported = true
```

TOTPおよびWebAuthnを選択可能にしている環境では、それぞれのauthentication flowも
forced authenticationをサポートしていることを確認する。

### 8.2 self-serviceを有効化

`/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`をバックアップして編集する。

```bash
sudo cp -a \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties \
  /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties.bak.$(date +%Y%m%d-%H%M%S)

sudo vi /opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties
```

最初は従来のLDAP経路を残して検証するため、同ファイルを以下の値にする。

```properties
graphicalmatrix.selfservice.enabled = true
graphicalmatrix.selfservice.transactionTtlSeconds = 600
graphicalmatrix.change.legacyLdapLoginEnabled = true
```

設定検査を実行する。

```bash
sudo /path/to/extracted-plugin/bin/graphicalmatrix-plugin-check.sh \
  --idp-home /opt/shibboleth-idp \
  --config-only
```

少なくとも次が`OK`になることを確認する。

```text
OK: [config] self-service valid: enabled=true transaction_seconds=600 legacy_ldap_login=true
OK: [config] self-service authentication flow enabled: idp.authn.flows=MFA
OK: [config] GraphicalMatrix External flow supports forced authentication
result: OK
```

pluginを更新した場合はIdP WARを再構築し、Jettyを再起動する。

再構築前に、`dist/plugin-webapp`と`edit-webapp`を合わせて2FAS-KWのJARが1つだけであることを
確認する。旧版と新版が同時に入ると、旧クラスが先にロードされて`NoSuchMethodError`になる。
`plugin.sh`による導入と、展開ZIPから`edit-webapp`へコピーする手動導入を混在させない。

```bash
sudo find \
  /opt/shibboleth-idp/dist/plugin-webapp/WEB-INF/lib \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '2faskw-idp-plugin-*.jar' -print
```

開発中に同じバージョン番号のJARを置き換える場合は、展開した新しい配布物からJARを明示的に
上書きする。`plugin.sh`の更新判定は同じバージョンを更新対象として扱わないため、この試験では
使用しない。

```bash
cd /path/to/extracted-plugin

sudo systemctl stop jetty-idp.service

sudo install -m 0644 \
  webapp/WEB-INF/lib/2faskw-idp-plugin-1.2.4.jar \
  /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-1.2.4.jar
```

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl start jetty-idp.service
sudo systemctl is-active jetty-idp.service
```

次のURLへアクセスし、Passwordと現在のMFA方式が毎回要求されることを確認する。

```text
https://idp.example.org/idp/profile/2faskw/self-service
```

Passwordと現在のMFA方式が成功すると、ブラウザは自動的に次へ遷移し、変更メニューを表示する。
このhandoff URLを利用者が直接入力してはならない。

```text
https://idp.example.org/idp/graphicalmatrix/change?mode=idp-self-service
```

監査ログで次の順序を確認する。

```text
event=SELF_SERVICE_AUTH ... result=OK ...
event=SELF_SERVICE_HANDOFF ... result=OK ... detail=one_time_handoff_consumed
```

### 8.3 従来LDAP経路を停止

DB保存とLDAP保存の両方で一連の変更試験が完了した後、必要に応じて以下へ変更する。

設定先は`/opt/shibboleth-idp/conf/graphicalmatrix/graphicalmatrix.properties`である。

```properties
graphicalmatrix.change.legacyLdapLoginEnabled = false
```

この状態では`/idp/graphicalmatrix/change`を直接開いても自己管理profileへ移動し、
`mode=ldap-login`を直接POSTしてもLDAP bindは実行されない。

## 9. 監査とエラー処理

自己管理開始時の主要な監査イベントは以下である。

| Event | Result | 意味 |
| --- | --- | --- |
| `SELF_SERVICE_AUTH` | `OK` | Password + 現在のMFA方式とSubjectの検査に成功し、handoffを作成した。 |
| `SELF_SERVICE_AUTH` | `DENIED` | profile、Subject、ForceAuthnまたは認証factorの検査に失敗した。 |
| `SELF_SERVICE_AUTH` | `CONFIG_ERROR` | 自己管理設定を読み込めなかった。 |
| `SELF_SERVICE_HANDOFF` | `OK` | 同一セッションのhandoffを一回だけ消費し、変更メニューを開始した。 |
| `SELF_SERVICE_HANDOFF` | `DENIED` | handoffが無い、有効期限切れ、再利用またはself-service無効で拒否した。 |
| `SELF_SERVICE_HANDOFF` | `ENROLL_REQUIRED` | 登録が存在しない、無効またはロック中で開始できなかった。 |
| `SELF_SERVICE_HANDOFF` | `DB_ERROR` | Repositoryから登録状態を取得できなかった。保存先がLDAPの場合もイベント名は現行実装上`DB_ERROR`となる。 |

このほか、既存監査イベントにsequence変更またはMFA方式変更の結果、登録状態、lock、
state version不一致などの拒否理由を記録する。

GraphicalMatrix sequence、選択画像列、TOTP seed、WebAuthn credential、LDAP password、
暗号鍵、CSRF tokenは記録しない。

利用者画面には、登録状態や他利用者の存在を推測できる詳細を出さない。期限切れ、状態変更、
認証不成立は「最初からやり直してください」または管理者連絡の案内に統一する。

## 10. 受入試験

| 区分 | 確認内容 |
| --- | --- |
| 認証 | 既存SSO sessionだけでは開始できず、Password + 現在のMFA方式の再認証後に開始できる。 |
| 遷移 | 認証成功後に`/idp/graphicalmatrix/change?mode=idp-self-service`へ移動し、`/idp/profile/idp/...`へ移動しない。 |
| handoff | `SELF_SERVICE_AUTH result=OK`に続いて`SELF_SERVICE_HANDOFF result=OK`が記録され、変更メニューが表示される。 |
| Subject | URL、form、headerの利用者IDを改変しても他利用者を操作できない。 |
| 状態 | CSRF不一致、期限切れ、WebFlow再実行、ブラウザ戻る操作を安全に拒否または再開できる。 |
| 更新 | state version不一致、ロック中、無効登録状態で保存を拒否する。 |
| 強制変更 | `force_sequence_change` 中は新sequence保存前にMFA方式変更できない。 |
| DB/LDAP | DB保存とLDAP保存でsequence/MFA方式を正しく更新する。 |
| TOTP | seedが監査ログ・URL・SP相当の外部画面へ露出せず、IdPで登録できる。 |
| WebAuthn | 既存RP ID/originで登録でき、登録後に期待するMFA方式で認証できる。 |
| 互換性 | `/idp/graphicalmatrix/change` と通常のMFAログインが回帰しない。 |

## 11. 実装・検証状態

### 実装済み

- `/idp/profile/2faskw/self-service` Administrative Flow。
- fresh Password + 現在のMFA方式の検査。
- SP/IP向けMFAバイパスの自己管理profileへの不適用。
- 一回限りの同一セッションhandoff。
- 既存change画面、CSRF、state version、DB/LDAP Repositoryの再利用。
- 従来LDAPログイン経路の設定による停止。

### 実機確認済み

- Password + GraphicalMatrixによる強制再認証。
- `SELF_SERVICE_AUTH result=OK`の記録。
- Webアプリケーションコンテキスト相対の変更画面リダイレクト。
- 一回限りのhandoff消費と`SELF_SERVICE_HANDOFF result=OK`の記録。
- 既存変更メニューの表示。

### 環境ごとに確認が必要

- TOTPを現在のMFA方式にした利用者の強制再認証。
- WebAuthnを現在のMFA方式にした利用者の強制再認証。
- DB保存とLDAP保存それぞれでのsequenceおよびMFA方式更新。
- TOTP/WebAuthn登録開始後の完了、取消および自己管理画面への復帰。

### 別フェーズ

- TOTP登録を自己管理画面内で完結させる専用遷移と取消処理。
- WebAuthn registration完了後に自己管理メニューへ戻す専用遷移。
- MFA喪失時の管理者復旧機能。

## 12. トラブルシューティング

### `NoSuchMethodError`が発生する

旧版JARと新版JARの同時配置、または再構築前のWARが使われている可能性がある。
8.2の`find`で2FAS-KW JARを確認し、導入方式を一つに統一してからWAR再構築とJetty再起動を行う。

### `SELF_SERVICE_AUTH result=OK`の後に404になる

アクセス先が`/idp/profile/idp/graphicalmatrix/change`になっている場合は、
コンテキスト相対リダイレクト修正前のJARが動作している。同じバージョン番号でも8.2の手順で
JARを上書きし、WARを再構築する。

### `SELF_SERVICE_HANDOFF result=DENIED`になる

handoff URLの直接入力、ブラウザ再読込、有効期限切れ、別セッションへの切替が考えられる。
handoffは一回限りであるため、`/idp/profile/2faskw/self-service`から認証をやり直す。

確認には以下を使用する。

```bash
sudo tail -n 50 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log

sudo grep -nE \
  'SELF_SERVICE_AUTH|SELF_SERVICE_HANDOFF|NoSuchMethodError|No endpoint|AccessDenied' \
  /opt/shibboleth-idp/logs/idp-process.log | tail -n 100
```

## 13. 採用しない構成

- 通常ServletにShibboleth session cookieだけを見せて、Subjectを推測する構成。
- URL、HTML form、HTTP headerの`user`を本人確認の根拠にする構成。
- 過去のSSO sessionだけでMFA方式変更を許可する構成。
- TOTP seed、暗号鍵、LDAP bind passwordをブラウザまたは別アプリケーションへ渡す構成。
- WebAuthn registrationを別hostname/originで実行する構成。
- MFAを利用できない利用者の復旧を通常の自己管理画面で行う構成。

## 14. 運用上の決定事項

1. 利用者へ案内する自己管理profile URL。
2. 従来LDAPログイン経路を停止する時期。
3. TOTP/WebAuthn登録後の戻り先と取消時の運用。
4. MFAを喪失した利用者の管理者復旧手順。
