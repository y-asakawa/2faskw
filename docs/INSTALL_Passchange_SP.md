# 2FAS-KW 外部自己管理SP設計（未実装、将来的機能）

> **対象方式**: この文書は、IdPとは別hostnameの自己管理ポータルSPを新設する場合の設計である。
> IdP内でShibboleth認証後に自己管理画面を提供する場合は、
> [INSTALL_Passchange_IdP.md](./INSTALL_Passchange_IdP.md) を参照する。

外部ポータル、独立した画面運用、IdPと自己管理アプリケーションの配置分離が必要な場合に
この方式を選ぶ。SPを追加する必要がない場合は、IdPネイティブ自己管理フロー方式を推奨する。

## 1. 目的

現在の `/idp/graphicalmatrix/change` は、利用者がLDAPのID・パスワードを入力し、
現在のGraphicalMatrixを確認した後に、GraphicalMatrixの画像列またはMFA方式を変更する
IdP内Servletである。本書では、この変更画面をShibboleth SPで保護した自己管理ポータルから
利用できるようにするための設計を定義する。

対象となる変更は以下である。

- GraphicalMatrix sequenceの変更
- 利用するMFA方式の変更
- TOTPおよびWebAuthnの登録開始

ここでいう「パスワード変更」はGraphicalMatrix sequenceの変更を指す。LDAPの
`userPassword` を変更する機能は対象外とする。LDAPパスワード変更を追加する場合は、
LDAP Password Modify Extended Operation、ディレクトリ固有のパスワードポリシー、
失敗時の復旧手順を含めた別設計にする。

## 2. 結論と方針

SP化は可能だが、画面だけを別SPへ移すことはしない。SPにDB接続情報、LDAP bind password、
sequence/TOTP用暗号鍵、WebAuthn StorageServiceへの書込み権限を渡してはならない。

以下の役割分離を採用する。

```text
利用者ブラウザ
  |
  v
自己管理ポータルSP
  |  SAML AuthnRequest (ForceAuthn + 自己管理用MFA要求)
  v
Shibboleth IdP + 2FAS-KW
  |  一回限りの自己管理チケット
  v
自己管理ポータルSP
  |  mTLSで保護された限定Self-Service API
  v
Shibboleth IdP + 2FAS-KW Repository
  |
  +-- DB保存 または LDAP属性保存
  +-- TOTP plugin / WebAuthn plugin
```

SPは利用者画面、SAMLセッション、利用者操作の中継だけを担当する。本人確認、
認可、登録情報の読取り・更新、暗号化、監査ログはIdP内の2FAS-KWで担当する。

## 3. 現行実装と変更が必要な理由

現在の `GraphicalMatrixChangeServlet` には、次の責務が一つに含まれている。

1. `ldap-login` によるLDAP ID・パスワード認証と失敗回数制御。
2. 現在のGraphicalMatrixを使う本人確認challenge。
3. session、CSRF token、challenge有効期限、state versionの管理。
4. GraphicalMatrix sequenceおよびMFA方式の更新。
5. 監査ログの出力と画面描画。

SP化後にLDAPログインをそのまま残すと、ShibbolethでのMFA認証後にも別のLDAP認証を
要求することになる。また、TOTP/WebAuthnを現在のMFA方式にしている利用者に対し、
GraphicalMatrixだけを本人確認手段として要求することは整合しない。

したがって実装時には、次のソースコード変更が必要である。

| 対象 | 変更内容 |
| --- | --- |
| `GraphicalMatrixChangeServlet` | 画面用Servletとして縮小し、LDAPログイン依存の既存経路を互換用に維持する。登録変更の業務処理は共通サービスへ移す。 |
| 新規 `GraphicalMatrixSelfServiceService` | 認証済みSubject、認証強度、state versionを入力として、challenge、sequence更新、MFA方式変更を実行する。HTTP request parameterのuserを信頼しない。 |
| 新規 Self-Service API Servlet | mTLSおよび一回限りチケットを検証する利用者向け限定APIを提供する。既存の管理APIは使用しない。 |
| 新規 Ticket/Transaction store | IdPが発行する短寿命チケットと、自己管理トランザクション状態を保存・一回使用として管理する。 |
| `GraphicalMatrixMfaDecisionStrategy` | 自己管理SPのentityIDを判定できるようにし、自己管理用MFAポリシーの記録と監査を追加する。 |
| `GraphicalMatrixConfig` / 配布設定 | self-service用properties、mTLS信頼設定、対象SP、チケット有効期限を追加する。 |
| `web.xml` と生成スクリプト | 新しいSelf-Service APIのmappingを追加する。 |
| テスト | token再利用、期限切れ、Subject不一致、強制変更、DB/LDAP保存、TOTP/WebAuthn遷移を追加する。 |

既存の `GraphicalMatrixAdminApiServlet` は管理者用Bearer tokenとCIDR制限を前提とする。
利用者が呼び出せるAPIとして公開または流用してはならない。

## 4. 認証・認可モデル

### 4.1 専用SPを作成する

自己管理ポータルは通常の業務SPとは分け、専用entityIDを持たせる。例:

```text
https://mfa-selfservice.example.org/shibboleth
```

SPはShibboleth SPまたはSimpleSAMLphp SPのどちらでもよい。ただし、本番では署名済みの
AuthnRequest、HTTPS、SAML Response署名検証、セッションcookieの`Secure` / `HttpOnly` /
`SameSite`設定を必須とする。

IdP側では、このentityIDだけを自己管理SPとして扱うRelyingParty設定を追加する。
他SPへ自己管理用属性やチケットをreleaseしない。

### 4.2 再認証を必須にする

自己管理ポータルを開く時点で、SPは以下を含むSAML AuthnRequestをIdPへ送る。

- `ForceAuthn=true`
- 自己管理に必要なMFAを表すAuthnContext要求
- 専用SPのentityID

IdPはPassword認証と、利用者に登録されている現在のMFA方式
(GraphicalMatrix、TOTPまたはWebAuthn) を完了させる。単に過去のIdP SSO sessionが
残っているだけでは、変更を許可しない。

GraphicalMatrixを使う場合にForceAuthnを満たせるよう、
`idp.authn.External.forcedAuthenticationSupported=true`を設定する必要がある。
この設定はExternal flowが強制再認証要求へ対応可能であることを宣言するもので、
通常のSP認証を一律にForceAuthnへ変更するものではない。

MFA認証後のSubjectは、IdPがRepositoryで使用する正規化済み利用者IDに対応付ける。
SPから任意の `user` パラメータ、`X-Remote-User`、`X-Forwarded-*` を受け取って
本人として扱うことは禁止する。

### 4.3 属性リリース

自己管理SPには、次のいずれか一つだけを限定的にreleaseする。

1. IdP内の正規利用者IDへ一意に対応付けられる専用属性。
2. 自己管理SP専用のpairwise identifierと、IdP内の対応表。

`uid` やメールアドレスを他SPへ広くreleaseする構成にはしない。pairwise identifierを
選ぶ場合、SPはその値からDB/LDAPを直接検索できないため、後述のIdPチケット方式を必須とする。

属性フィルタは自己管理SPのentityIDに完全一致させる。`*`、同一組織の全SP、
IPアドレス条件だけによるreleaseは使用しない。

## 5. IdP-SP間の自己管理チケット

### 5.1 チケットの目的

SAML assertion中の属性だけを利用してSPから更新APIを呼ぶと、利用者IDの偽装、
古いSSO sessionの再利用、assertion replayの検査漏れが起こり得る。
そのためIdPは、自己管理SPへの認証成功時に短寿命・一回限りの不透明なチケットを発行する。

SPはチケットをmTLSで保護されたIdP内部APIへ渡す。IdPがチケットを消費して初めて、
自己管理トランザクションを開始できる。

### 5.2 チケットの内容

ブラウザ/SPへ渡す値は、推測不能な32 bytes以上のランダム値をbase64url化した不透明値とする。
IdPのticket storeには平文チケットを保持せず、HMACまたはハッシュ化した照合値を保存する。

IdP側の状態には少なくとも次を持たせる。

| 項目 | 内容 |
| --- | --- |
| `ticketId` | ハッシュ化した一回限りチケット識別子。 |
| `subject` | 正規化済み利用者ID。 |
| `relyingPartyId` | 許可した自己管理SP entityID。 |
| `authnInstant` | 直近の強制再認証完了時刻。 |
| `authnContext` | Passwordおよび現在のMFA方式が完了した根拠。 |
| `expiresAt` | 発行から最大5分。 |
| `usedAt` | 消費済み判定。一度消費したら再利用不可。 |
| `requestId` | SAML requestと監査ログを相関するID。 |

チケット値、GraphicalMatrix sequence、TOTP seed、WebAuthn credentialを監査ログへ出力してはならない。

### 5.3 APIチャネル

自己管理APIはインターネットへ公開しない。IdPと自己管理SPの間だけで到達できる内部HTTPS
エンドポイントとし、次をすべて満たす。

- SPのclient certificateをIdPが検証するmTLS。
- client certificate Subject/SANのallowlist。
- チケットの対象SP、期限、一回使用、Subject、認証強度の検証。
- APIごとのCSRF対策ではなく、SP画面でのCSRF対策とIdPトランザクションtokenの二重検証。
- API応答にsequence、TOTP seed、暗号鍵、LDAP bind passwordを含めない。
- reverse proxyを使う場合、IdP APIはプロキシを経由しない専用経路にするか、クライアント証明書情報を
  信頼できるプロキシからのみ受け取る。

## 6. 自己管理APIの論理設計

APIのURL、JSON形式、エラーコードは実装時にOpenAPIとして確定する。ここでは必要な論理操作を定義する。

| 操作 | 前提 | 結果 |
| --- | --- | --- |
| `consume-ticket` | mTLS、未使用ticket、自己管理SP、fresh MFA | サーバ側transactionを作成する。 |
| `get-enrollment-state` | 有効transaction | 現在のMFA方式、変更可否、公開してよい表示情報だけを返す。 |
| `begin-sequence-change` | 有効transaction | 新しい画像表示順とCSRF/tokenを返す。 |
| `save-sequence` | 有効transaction、state version一致 | sequenceを更新し、強制変更フラグを必要に応じて解除する。 |
| `change-mfa-method` | 有効transaction、state version一致 | MFA方式を変更する。TOTP/WebAuthnは登録開始状態にする。 |
| `complete` / `cancel` | 有効transaction | transactionを失効させる。 |

`consume-ticket` 後に生成するtransactionは、Subject、自己管理SP、state version、発行時刻、
CSRF token、利用済み状態に結び付ける。有効期限は既存challengeと同程度の短時間とし、
ブラウザを閉じた場合やエラー時は明示的に失効させる。

更新時は既存の `state_version` による楽観ロックを維持する。別端末または管理者処理で
登録状態が変化した場合は保存を拒否し、再認証からやり直させる。

## 7. 本人確認と強制変更の扱い

自己管理SPでは、ForceAuthnで実施したPassword + 現在のMFA方式を本人確認の根拠とする。
このため、TOTPまたはWebAuthnを現在のMFA方式にしている利用者へGraphicalMatrix入力を
追加要求しない。

ただし、GraphicalMatrixを現在のMFA方式にしている利用者は、再認証時にGraphicalMatrixを
実行するため、現行change画面の `verify-current` と同等の本人確認が得られる。

`force_sequence_change` が有効な利用者は、既存と同じく新しいGraphicalMatrixを保存するまで
MFA方式の変更を許可しない。この制約はSP画面だけでなく、Self-Service APIでも強制する。

現在のMFA方式を利用できない利用者は、自己管理SPから方式変更してはならない。管理者承認、
本人確認、暫定MFA発行などを含む別の復旧フローに分離する。

## 8. TOTPおよびWebAuthnの登録

### TOTP

自己管理SPでTOTPを選択した場合、IdP側で `PENDING` 状態を作成する。TOTP seedの生成、
暗号化、QR code表示、登録完了処理はIdP内で行う。SPのバックエンドまたはブラウザ経由で
TOTP seedを取得・保存しない。

### WebAuthn

WebAuthn credentialはRP IDとブラウザoriginに結び付く。現在のIdPで設定済みのRP ID/originを
維持するため、自己管理SPで方式を選択した後は、短寿命のtransaction handoffを用いて
IdPのWebAuthn registration画面へトップレベル遷移する。

自己管理SPの別hostnameでWebAuthn registration ceremonyを実施してはならない。実施すると
既存credentialとのRP ID不整合、origin不一致、利用者ブラウザでの安全性エラーにつながる。

## 9. DB/LDAP保存との関係

Self-Service APIは `GraphicalMatrixRepository` を通して更新する。そのためDB保存とLDAP保存の
違いをSPへ公開しない。

- DB保存: `graphicalmatrix_enrollment` と既存のTOTP保存処理を利用する。
- LDAP保存: LDAP属性、ACL、service accountはIdP側だけが利用する。
- WebAuthn LDAP StorageService: subtree方式を推奨する既存方針を維持する。

自己管理SPがDBパスワード、LDAP bind password、sequence keyword、AES keyを保持する必要はない。

## 10. 設定案

以下は実装時に `conf/graphicalmatrix/self-service.properties` として追加する設定案であり、
現時点では未実装である。

```properties
# 配布直後は無効。SP/IdP設定とmTLSを完了してから有効化する。
graphicalmatrix.selfservice.enabled = false

# 自己管理を許可するSP entityID。完全一致のみ許可する。
graphicalmatrix.selfservice.allowedRelyingPartyIds = https://mfa-selfservice.example.org/shibboleth

# IdPが発行する一回限りticketとtransactionの有効期限。
graphicalmatrix.selfservice.ticketTtlSeconds = 300
graphicalmatrix.selfservice.transactionTtlSeconds = 600

# fresh Password + MFAを要求する。
graphicalmatrix.selfservice.requireFreshAuthentication = true
graphicalmatrix.selfservice.requiredAuthnContext = urn:example:2faskw:selfservice:mfa

# IdP内部APIのmTLS client certificate allowlist。
graphicalmatrix.selfservice.api.allowedClientSubjects = CN=mfa-selfservice.example.org
```

`allowedRelyingPartyIds`、mTLS Subject、AuthnContextは、SP metadata、RelyingParty設定、
属性フィルタと同じ値になるよう構成検査で検証する。

## 11. 配備構成

自己管理ポータルSPは、IdPと同一ホストに置くことも別ホストに置くこともできる。運用上は
別hostnameを推奨する。

```text
https://mfa-selfservice.example.org/     利用者向けSP画面
https://idp.example.org/idp/             Shibboleth IdP
https://idp-internal.example.org/...     SPからのみ到達できるmTLS API
```

同一ホストに置く場合も、SPの利用者向け経路とIdP内部APIのネットワーク到達性、TLS証明書、
アクセスログ、cookie scopeを分離する。`/idp/graphicalmatrix/change` をリバースプロキシして
公開SP画面のように見せる方法は、Subject連携と再認証保証を満たさないため採用しない。

## 12. 実装フェーズ

### Phase 1: 共通サービス化

- `GraphicalMatrixChangeServlet` から登録状態の検査、sequence更新、MFA方式更新を分離する。
- `GraphicalMatrixSelfServiceService` はSubjectを文字列で受け取るのではなく、検証済みの
  principal contextからのみ構築する。
- 既存の `/idp/graphicalmatrix/change` は互換用として残し、LDAPログイン方式は変更しない。
- DB保存とLDAP保存の単体テストを追加する。

### Phase 2: IdP自己管理チケットとAPI

- 自己管理SPの認証成功を検知し、短寿命ticketを発行する。
- mTLS、ticket消費、transaction、state version、監査ログを実装する。
- ticket replay、期限切れ、SP entityID不一致、client certificate不一致を拒否するテストを追加する。

### Phase 3: SP画面

- Shibboleth SPまたはSimpleSAMLphp SPの参照実装を用意する。
- GraphicalMatrix変更、MFA方式選択、完了/失敗画面を追加する。
- CSRF、Content Security Policy、clickjacking対策、session固定化対策を適用する。

### Phase 4: TOTP/WebAuthn handoff

- TOTPのPENDING/登録完了をIdP側に限定する。
- WebAuthn registrationへの短寿命handoffを実装する。
- 登録後にSPへ戻る経路、取消、期限切れ、複数ブラウザ操作を試験する。

### Phase 5: 導入・移行資料

- SP metadata、IdP metadata provider、RelyingParty設定、属性フィルタ、mTLS証明書の導入手順を追加する。
- `graphicalmatrix-plugin-check.sh` にself-service設定検査を追加する。
- 既存のIdP内change画面からの移行・ロールバック手順を追加する。

## 13. 受入試験

最低限、以下を自動試験と検証IdPで確認する。

| 区分 | 確認内容 |
| --- | --- |
| 認証 | 古いSSO sessionだけでは開始できず、ForceAuthn後のPassword + MFAで開始できる。 |
| Subject | URL、HTML form、HTTP headerの利用者IDを変更しても他利用者を操作できない。 |
| Ticket | ticketの再利用、期限切れ、対象SP不一致、mTLS証明書不一致を拒否する。 |
| 更新 | state version不一致、lock状態、無効登録状態で更新を拒否する。 |
| 強制変更 | `force_sequence_change` 中は新sequence保存前にMFA方式変更できない。 |
| DB/LDAP | DB保存とLDAP保存の双方でsequence/MFA方式更新が正しく保存される。 |
| TOTP | seedがSP/API応答/監査ログに含まれず、IdP画面でのみQR登録できる。 |
| WebAuthn | 登録が既存RP ID/originで成功し、自己管理SPのhostnameをoriginに使わない。 |
| 監査 | subject、SP entityID、requestId、結果、方式変更だけを記録し、秘密値を記録しない。 |
| 互換性 | 既存 `/idp/graphicalmatrix/change` と通常のMFAログインが回帰しない。 |

## 14. 採用しない構成

- SPがDBまたはLDAPへ直接接続して登録情報を書き換える構成。
- 既存管理APIのBearer tokenをブラウザ/SPへ渡す構成。
- SAML属性の `uid`、HTTP header、HTML parameterだけを本人確認根拠にする構成。
- 既存IdP SSO sessionだけでMFA変更を許可する構成。
- WebAuthn登録を自己管理SPの別originで実行する構成。
- 現在のMFAを使えない利用者の復旧を、通常の自己管理変更として扱う構成。

## 15. 実装開始時の決定事項

実装に着手する前に以下を確定する。

1. 自己管理SPの正式FQDN、entityID、運用主体。
2. SP実装方式。Shibboleth SPを推奨し、SimpleSAMLphpは検証用または既存運用互換として扱う。
3. IdP/SP間のmTLS終端位置とclient certificate更新手順。
4. IdP内の正規利用者IDと、自己管理SPへreleaseする識別子の対応方法。
5. 自己管理に必要なAuthnContextと、ForceAuthnを適用するRelyingPartyポリシー。
6. MFAを失った利用者の管理者復旧手順。

これらを確定した後、Phase 1から順に実装する。Phase 1のみではSP経由の利用は開始しない。
