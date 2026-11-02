# Authentication and Self-Service Flow

この文書は、Shibboleth IdPから2FAS-KWの認証、TOTP、WebAuthn、自己管理へ至る呼出関係と
状態遷移を説明します。

## Entry flow

```text
SAML AuthnRequest
  -> Shibboleth relying-party/profile processing
  -> authn/MFA
       -> authn/Password
       -> GraphicalMatrixMfaDecisionStrategy.apply()
            -> GraphicalMatrixMfaPolicy.evaluate(SP, client IP)
            -> BYPASS: MFA flow complete
            -> REQUIRE: GraphicalMatrixRepository.findMfaSettings(user)
                 -> GraphicalMatrix: authn/External
                 -> TOTP active:     authn/TOTP
                 -> WebAuthn:        authn/WebAuthn
                 -> unknown/error:   authn/External
```

第一認証はShibbolethの`authn/Password`です。2FAS-KWはその結果に含まれる
`UsernamePrincipal`を利用するため、browserから送られたuser IDを第二要素の主体として
単独では信用しません。

完了時は選択flowの結果自身から利用者を取得します。`authn/TOTP`では公式の`TOTPPrincipal`に
対応し、カスタムvalidatorが返す一意の`UsernamePrincipal`も許可します。両方の型がある場合は
一致が必要です。一次認証との一致、結果flow、登録世代の検査を省略せず、可変のSubjectContextから
第二要素の利用者を補完しません。

`authn/WebAuthn`の公式1.4.2第二要素モードでは、結果に`UsernamePrincipal`が含まれない場合が
あります。この場合に限り、公式の成功結果生成処理が設定した`SubjectCanonicalizationContext`を
参照し、その`Subject`が選択結果の`Subject`と同一オブジェクトであること、過去結果ではないこと、
正規化エラーがなく利用者名が空でないことを必須にします。username principalが存在する場合は
名前の一致も必要です。第一認証の名前だけによる代用やWebAuthn user handleの名前扱いは行いません。
公式WebAuthnプラグイン・登録キー・追加設定は変更不要です。

## MFA policy evaluation

`GraphicalMatrixMfaDecisionStrategy.apply()`は次の順で処理します。

1. `mfa-policy.properties`を読み込む。
2. `RelyingPartyContext`からSP entityIDを取得する。
3. requestから送信元IPを取得する。
4. `GraphicalMatrixMfaPolicy.parse()`で設定を検証する。
5. `policyOrder`の先頭からruleを評価する。
6. 結果が`bypass`なら追加flowを返さない。
7. 結果が`require`ならenrollmentのMFA方式を読み、対応flowを返す。

policyが不正または読めない場合は、MFAを省略する方向へ倒さず、追加認証を要求します。
proxy headerを使用する設定では、IdPの手前でheaderを上書きするtrusted proxy構成が必要です。

SP別設定と全体設定の管理方法は
[INSTALL_NEW_SP.md](../INSTALL_NEW_SP.md)と
[COMMAND-REFERENCE.md](../COMMAND-REFERENCE.md)を参照してください。

## GraphicalMatrix challenge

### Start

`GraphicalMatrixStartServlet.doGet()`は、External flowから渡されたuserとflow keyを確認し、
repositoryからenrollmentを読みます。

```text
missing/inactive enrollment -> unavailable or enrollment-required result
locked enrollment           -> locked view
TOTP selected but pending    -> TOTP registration view
force sequence change       -> sequence change view
normal GraphicalMatrix       -> randomized challenge view
```

challengeでは、許可された画像IDから表示順を生成し、user、flow key、期限、表示順をsessionへ
保存します。browserへ保存値そのものを渡さず、画像配信も設定上のallowlistを通します。

### Verify

`GraphicalMatrixVerifyServlet.doPost()`は次を検証します。

1. request methodと必須parameter。
2. sessionにchallenge stateが存在すること。
3. sessionのflow keyとPOST値が一致すること。
4. challenge期限内であること。
5. 選択数、画像ID、重複、順序の制約。
6. repositoryの保存値との照合。

`GraphicalMatrixRepository.verify()`はDBでは対象行を`FOR UPDATE`でlockし、照合と失敗状態の
更新を同じtransactionで行います。LDAP保存も同じ外部契約を満たすatomic更新を行います。

```text
correct
  -> failed_count = 0
  -> locked_until = 0
  -> last_success_at = now
  -> state_version++
  -> External flow success

incorrect below threshold
  -> failed_count++
  -> state_version++
  -> retry

incorrect at threshold
  -> failed_count++
  -> locked_until = calculated deadline
  -> state_version++
  -> locked view
```

保存エラーや不整合を認証成功として扱いません。利用者向けには必要最小限の結果を表示し、
詳細は監査・process logへ記録します。

## TOTP

利用者の`mfa_method`がTOTPでも、seedがactiveでない場合は`authn/External`へ入り、
2FAS-KWの登録画面を表示します。

```text
select TOTP in self-service
  -> Repository.beginTotpRegistration(user, verifiedStateVersion, now, fixedTtl)
  -> atomically store protected seed + registration ID + fixed expiry + PENDING state
  -> bind user, registration ID, post-begin state version and expiry to HTTP session
  -> render QR/otpauth information
  -> user enters current code
  -> Repository.verifyTotpRegistration(binding, code, now)
  -> activate only when the complete binding still matches
  -> ACTIVE state and clear registration ID/expiry
```

誤code時は同じBindingで確認したseedだけを再表示し、登録期限は延長しません。登録中に管理者が
方式変更、reset、disable等を行った場合は、管理操作が登録IDを消去して`state_version`を増加させるため、
古い登録画面からの確認・取消はSTALEとして拒否されます。取消も
`cancelTotpRegistration(binding, config, now)`による条件付き更新です。

登録確認が成功した後の通常loginは`authn/TOTP`へ進みます。通常認証のcode検証、window、
authentication result lifetimeなどはShibboleth TOTP Pluginの責務です。2FAS-KWは
`GraphicalMatrixTotpSeedSource`を通じてactive seedを提供します。

TOTP seedは通常認証で必要なため、sequence hashとは異なり復号可能な保護形式を使います。

## WebAuthn

WebAuthn credentialの生成・秘密鍵保持・assertion検証はShibboleth WebAuthn Pluginの責務です。
2FAS-KWは登録開始とMFA方式の確定を調停します。

```text
self-service requests WebAuthn
  -> GraphicalMatrixWebAuthnRegistrationSession.initialize()
  -> redirect to official registration profile
  -> official plugin stores credential
  -> GraphicalMatrixWebAuthnRegistrationHook.accept()
  -> consume matching one-time registration request
  -> Repository.activateWebAuthnIfMethodCurrent()
```

登録画面を閉じた場合、登録が失敗した場合、sessionが期限切れの場合、登録中に管理者が方式を
変更した場合は、WebAuthnへの方式切替を確定しません。

通常loginで`mfa_method=WebAuthn`なら`authn/WebAuthn`へ進みます。credential数や保存先は
WebAuthn Pluginおよび選択したStorageServiceの設定です。

## Self-service

推奨自己管理入口はIdP内の`/profile/2faskw/self-service`です。

```text
browser
  -> self-service profile
  -> authn/Password
  -> current second factor selected by MFA decision strategy
  -> GraphicalMatrixSelfServiceAuthentication.validate()
  -> InitializeGraphicalMatrixSelfService
  -> GraphicalMatrixSelfServiceSession.initialize()
  -> redirect to /graphicalmatrix/change
  -> GraphicalMatrixChangeServlet consumes handoff
```

`GraphicalMatrixSelfServiceSession`は短時間、一回限り、認証済みuserへ結び付いたhandoffです。
直接`/graphicalmatrix/change`へアクセスしても、このhandoffがなければ変更を開始できません。

`GraphicalMatrixChangeServlet`は現在方式の確認、変更menu、新sequence登録、TOTP登録、WebAuthn
登録を状態ごとに処理します。新しいsequenceは確認入力と設定制約を満たした後に保存します。

legacy LDAP login入口を有効にした構成では`GraphicalMatrixLdapAuthenticator`を使います。
この入口にはuser単位とIP単位のrate limitがあり、trusted networkのbypass設定はIP全体制限に
だけ適用されます。新規構成ではShibboleth再認証済みself-service profileを優先します。

## SP access control in the SAML flow

SP別属性access機能を有効にすると、Shibboleth ContextCheckから
`GraphicalMatrixSpAccessFunction.apply()`が呼ばれます。

```text
resolved IdP attributes + relying party
  -> load access policy snapshot
  -> GraphicalMatrixSpAccessEvaluator.evaluate()
       -> disabled/no policy: no restriction
       -> matching deny rule: deny
       -> all allow attributes satisfied: allow
       -> otherwise: deny
```

同じ属性に複数の許可値を指定した場合はOR、異なる属性のallow条件はANDとして評価します。
access判定用に承認した属性は、属性release profileへ追加しない限りSPへ送信されません。

## Audit points

Common completion recording is described in [MFA-SUCCESS-RECORD.md](./MFA-SUCCESS-RECORD.md).
`GraphicalMatrixMfaCompletionStrategy` uses the mandatory enrollment lookup for validation and
best-effort timestamp storage. It creates the existing self-service authorization receipt only
after a valid state verdict; metadata flags/results never authorize a user. Re-evaluation performs
a fresh mandatory lookup without replaying the write or `MFA_COMPLETE` audit.

次の境界では成功・失敗・状態変更を監査対象にします。

- challenge開始、照合成功、照合失敗、lockout。
- sequence変更、MFA方式変更、TOTP登録、WebAuthn登録確定。
- self-service再認証とhandoff不整合。
- SP access allow/deny。
- 管理APIによるenrollment変更。

監査logのeventと保持は[LOG-REFERENCE.md](../LOG-REFERENCE.md)を参照してください。

## Extension checklist

認証方式またはflowを追加するときは、少なくとも次を確認します。

- `GraphicalMatrixMfaDecisionStrategy`の方式正規化とflow選択。
- enrollmentの方式値、未登録・active状態。
- self-serviceの登録開始、cancel、成功確定。
- 管理CLI/API/CSVの入力検証。
- Shibboleth flow、bean、plugin dependency。
- audit eventと利用者向けerror。
- 通常login、強制再認証、同時変更、期限切れのtest。

## Primary tests

- `GraphicalMatrixMfaDecisionStrategyTest`
- `GraphicalMatrixMfaPolicyTest`
- `GraphicalMatrixVerifyServletTest`
- `GraphicalMatrixRepositoryStateTest`
- `GraphicalMatrixSelfServiceAuthenticationTest`
- `GraphicalMatrixSelfServiceSessionTest`
- `GraphicalMatrixWebAuthnRegistrationSessionTest`
- `GraphicalMatrixLdapLoginRateLimiterTest`
- `GraphicalMatrixSpAccessPolicyTest`


## v1.4.1の更新

v1.4.1ではMFA完了で検証済みの世代をProfileRequestContextのGraphicalMatrixSelfServiceAuthorizationContextに保持する。最終MFA構成結果と同一のPassword／第二要素結果に束縛し、一回だけHandoffへ渡す。TOTP／WebAuthnの検証アルゴリズムは変更しない。GraphicalMatrixの検証結果は、その更新自身のbefore／after versionを返す。
