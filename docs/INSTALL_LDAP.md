# LDAP New Install Guide

この文書は、2FAS-KW v1.2.0 以降でLDAP保存を新規に導入する場合の手順書です。

通常構成ではDB保存を推奨します。LDAP保存は、既存のLDAP運用にユーザー登録情報を寄せたい場合のオプションです。
WebAuthn credentialもDB/JDBC StorageService保存を推奨します。LDAPへ保存する場合は、StorageServiceの
`context + id` 複合キーと複数recordを扱いやすい `subtree` 方式を推奨します。

対象:

- GraphicalMatrix / MFA方式 / ロック状態 / TOTP seedをLDAPユーザー属性へ保存する
- WebAuthn credentialをLDAPへ保存する場合の設定を行う
- 新規検証環境では389 Directory Serverを例にする

前提:

- Shibboleth IdPと2FAS-KW pluginが導入済み
- 本番ではLDAPS、専用service account、最小権限ACLを使う
- TOTP seedとWebAuthn credentialはLDAP ACLで厳密に保護する

## LDAPの新規インストール

以下はRocky/RHEL系で389 Directory Serverを新規に用意する例です。
既存LDAPを使う場合は、この章のインストール部分ではなく、suffix、ユーザーDN、schema、ACLの確認に読み替えてください。

### パッケージ導入

```bash
sudo dnf install -y 389-ds-base openldap-clients
```

### 389 Directory Server instance作成

例では検証用suffixを `dc=example,dc=test`、Directory Managerを `cn=Directory Manager` とします。

```bash
sudo tee /root/2faskw-389ds.inf >/dev/null <<'EOF'
[general]
full_machine_name = localhost.localdomain
start = True

[slapd]
instance_name = 2faskw
root_dn = cn=Directory Manager
root_password = change_this_directory_manager_password
suffix = dc=example,dc=test
EOF

sudo dscreate from-file /root/2faskw-389ds.inf
sudo systemctl enable --now dirsrv@2faskw.service
```

### 基本ツリーと検証ユーザー作成

```bash
cat >/tmp/2faskw-base.ldif <<'EOF'
dn: ou=People,dc=example,dc=test
objectClass: top
objectClass: organizationalUnit
ou: People

dn: uid=test01,ou=People,dc=example,dc=test
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
uid: test01
cn: Test User
givenName: Test
sn: User
mail: test01@example.test
userPassword: Password123!
EOF

ldapadd -x -H ldap://127.0.0.1:389 \
  -D 'cn=Directory Manager' -W \
  -f /tmp/2faskw-base.ldif
```

この時点のLDAPツリー構造:

```text
dc=example,dc=test
└── ou=People
    └── uid=test01
        ├── objectClass: inetOrgPerson
        ├── uid: test01
        ├── cn: Test User
        ├── mail: test01@example.test
        └── userPassword: <LDAP password>
```

本番では平文LDAPではなくLDAPSまたはStartTLSを使用してください。

### 2FAS-KW用service account作成

GraphicalMatrix/TOTP保存用とWebAuthn LDAP StorageService用は、運用上分けることを推奨します。
小規模検証では同一accountでも動作しますが、本番では属性ごとにACLを絞ってください。

```bash
cat >/tmp/2faskw-service.ldif <<'EOF'
dn: cn=graphicalmatrix-writer,dc=example,dc=test
objectClass: top
objectClass: person
objectClass: organizationalPerson
cn: graphicalmatrix-writer
sn: graphicalmatrix-writer
userPassword: change_this_bind_password
EOF

ldapadd -x -H ldap://127.0.0.1:389 \
  -D 'cn=Directory Manager' -W \
  -f /tmp/2faskw-service.ldif
```

service account追加後のLDAPツリー構造:

```text
dc=example,dc=test
├── cn=graphicalmatrix-writer
│   ├── objectClass: person
│   ├── cn: graphicalmatrix-writer
│   └── userPassword: <bind password>
└── ou=People
    └── uid=test01
        ├── objectClass: inetOrgPerson
        ├── uid: test01
        ├── cn: Test User
        └── mail: test01@example.test
```

## アトリビュートの設定

LDAP保存では、DBの `graphicalmatrix_enrollment` 相当をユーザーエントリの属性に割り当てます。
属性名は環境に合わせて変更できますが、単一値属性として設計してください。

### GraphicalMatrix / TOTP / MFA方式属性

代表的な割り当て:

| 用途 | 設定例 | 内容 |
| --- | --- | --- |
| sequence | `ldap_sequence` | 現在のGraphicalMatrix sequence。`hash` / `aes-gcm` / `keyword` などの保存方式に従う。 |
| initial_sequence | `ldap_initial_sequence` | reset時の初期sequence。 |
| status | `ldap_status` | `ACTIVE` / `DISABLED` など。 |
| failed_count | `ldap_failed_count` | 失敗回数。 |
| locked_until | `ldap_locked_until` | lock解除時刻。epoch milliseconds。 |
| mfa_method | `ldap_mfa_method` | `GraphicalMatrix` / `TOTP` / `WebAuthn`。 |
| totp_seed | `ldap_totp_seed` | TOTP seed。復号が必要なため `aes-gcm` 推奨。 |
| totp_status | `ldap_totp_status` | `UNREGISTERED` / `PENDING` / `ACTIVE`。 |
| totp_registered_at | `ldap_totp_registered_at` | TOTP登録時刻。epoch milliseconds。 |
| last_success_at | `ldap_last_success_at` | 最終成功時刻。 |
| force_sequence_change | `ldap_force_sequence_change` | 初回または強制変更フラグ。 |
| state_version | `ldap_state_version` | 楽観ロック用version。 |
| created_at / updated_at | `ldap_created_at` / `ldap_updated_at` | 作成/更新時刻。 |

この表は配布テンプレートの属性名例です。属性名自体は変更できますが、
LDAP schema、ACL、`ldap.properties` の3箇所で同じ名前を使う必要があります。

389 Directory Serverで検証用schemaを作る場合は、各属性を `Directory String` の単一値として定義し、
補助objectClassにまとめます。OIDは組織で管理する正式OIDに置き換えてください。

```ldif
dn: cn=schema
changetype: modify
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.1 NAME 'ldap_sequence' DESC '2FAS-KW sequence' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.2 NAME 'ldap_initial_sequence' DESC '2FAS-KW initial sequence' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.3 NAME 'ldap_status' DESC '2FAS-KW status' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.4 NAME 'ldap_failed_count' DESC '2FAS-KW failed count' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.5 NAME 'ldap_locked_until' DESC '2FAS-KW locked until' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.6 NAME 'ldap_mfa_method' DESC '2FAS-KW MFA method' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.7 NAME 'ldap_totp_seed' DESC '2FAS-KW TOTP seed' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.8 NAME 'ldap_totp_status' DESC '2FAS-KW TOTP status' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.9 NAME 'ldap_totp_registered_at' DESC '2FAS-KW TOTP registered at' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.10 NAME 'ldap_last_success_at' DESC '2FAS-KW last success at' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.11 NAME 'ldap_force_sequence_change' DESC '2FAS-KW force sequence change' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.12 NAME 'ldap_state_version' DESC '2FAS-KW state version' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.13 NAME 'ldap_created_at' DESC '2FAS-KW created at' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1200.14 NAME 'ldap_updated_at' DESC '2FAS-KW updated at' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: objectClasses
objectClasses: ( 1.3.6.1.4.1.55555.1200.100 NAME 'graphicalMatrixEnrollment' DESC '2FAS-KW enrollment attributes' SUP top AUXILIARY MAY ( ldap_sequence $ ldap_initial_sequence $ ldap_status $ ldap_failed_count $ ldap_locked_until $ ldap_mfa_method $ ldap_totp_seed $ ldap_totp_status $ ldap_totp_registered_at $ ldap_last_success_at $ ldap_force_sequence_change $ ldap_state_version $ ldap_created_at $ ldap_updated_at ) )
```

投入例:

```bash
ldapmodify -x -H ldap://127.0.0.1:389 \
  -D 'cn=Directory Manager' -W \
  -f /tmp/2faskw-graphicalmatrix-schema.ldif
```

ユーザーエントリには補助objectClassと初期値を追加します。

```ldif
dn: uid=test01,ou=People,dc=example,dc=test
changetype: modify
add: objectClass
objectClass: graphicalMatrixEnrollment
-
replace: ldap_sequence
ldap_sequence: img01,img02,img03,img04
-
replace: ldap_initial_sequence
ldap_initial_sequence: img01,img02,img03,img04
-
replace: ldap_status
ldap_status: ACTIVE
-
replace: ldap_failed_count
ldap_failed_count: 0
-
replace: ldap_locked_until
ldap_locked_until: 0
-
replace: ldap_mfa_method
ldap_mfa_method: GraphicalMatrix
-
replace: ldap_totp_status
ldap_totp_status: UNREGISTERED
-
replace: ldap_totp_registered_at
ldap_totp_registered_at: 0
-
replace: ldap_last_success_at
ldap_last_success_at: 0
-
replace: ldap_force_sequence_change
ldap_force_sequence_change: 1
-
replace: ldap_state_version
ldap_state_version: 0
-
replace: ldap_created_at
ldap_created_at: 0
-
replace: ldap_updated_at
ldap_updated_at: 0
```

2FAS-KW属性追加後のユーザーエントリ:

```text
dc=example,dc=test
├── cn=graphicalmatrix-writer
└── ou=People
    └── uid=test01
        ├── objectClass: inetOrgPerson
        ├── objectClass: graphicalMatrixEnrollment
        ├── uid: test01
        ├── cn: Test User
        ├── ldap_sequence: <protected or plaintext sequence>
        ├── ldap_initial_sequence: img01,img02,img03,img04
        ├── ldap_status: ACTIVE
        ├── ldap_mfa_method: GraphicalMatrix
        ├── ldap_totp_status: UNREGISTERED
        ├── ldap_force_sequence_change: 1
        └── ldap_state_version: 0
```

本番では `ldap_sequence` は `graphicalmatrix.sequence.storage` の保存方式に従って保護された値になります。
手作業で平文sequenceを入れるのは検証用途に限定してください。

### LDAPユーザー追加と2FAS-KW初期属性

新規ユーザーをLDAPへ追加し、同時に2FAS-KW属性を持たせる検証例:

```bash
cat >/tmp/2faskw-user-test02.ldif <<'EOF'
dn: uid=test02,ou=People,dc=example,dc=test
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
objectClass: graphicalMatrixEnrollment
uid: test02
cn: Test User 02
givenName: Test
sn: User02
mail: test02@example.test
userPassword: Password123!
ldap_sequence: img01,img02,img03,img04
ldap_initial_sequence: img01,img02,img03,img04
ldap_status: ACTIVE
ldap_failed_count: 0
ldap_locked_until: 0
ldap_mfa_method: GraphicalMatrix
ldap_totp_status: UNREGISTERED
ldap_totp_registered_at: 0
ldap_last_success_at: 0
ldap_force_sequence_change: 1
ldap_state_version: 0
ldap_created_at: 0
ldap_updated_at: 0
EOF

ldapadd -x -H ldap://127.0.0.1:389 \
  -D 'cn=Directory Manager' -W \
  -f /tmp/2faskw-user-test02.ldif
```

本番では、平文の `ldap_sequence` を直接登録しないでください。
`graphicalmatrix.sequence.storage` が `auto` / `hash` / `keyword` / `aes-gcm` の場合は、
IdPに導入済みのJARを使って保存用sequenceを生成してからLDAPへ投入します。

```bash
CP="$(find /opt/shibboleth-idp/edit-webapp/WEB-INF/lib \
  -maxdepth 1 -type f -name '*.jar' -print | sort | paste -sd ':' -)"

STORED_SEQUENCE="$(sudo -u jetty java -cp "$CP" \
  io.github.yasakawa.faskw.GraphicalMatrixSequenceTool \
  encode /opt/shibboleth-idp img01,img02,img03,img04 true false)"

printf '%s\n' "$STORED_SEQUENCE"
```

生成した値を `ldap_sequence` に設定します。
`ldap_initial_sequence` はresetや管理用途で使う初期sequenceです。運用方針に合わせて、平文を置くか、
別の管理システム側で保護して扱うかを決めてください。

```bash
cat >/tmp/2faskw-user-test03.ldif <<EOF
dn: uid=test03,ou=People,dc=example,dc=test
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
objectClass: graphicalMatrixEnrollment
uid: test03
cn: Test User 03
givenName: Test
sn: User03
mail: test03@example.test
userPassword: Password123!
ldap_sequence: $STORED_SEQUENCE
ldap_initial_sequence: img01,img02,img03,img04
ldap_status: ACTIVE
ldap_failed_count: 0
ldap_locked_until: 0
ldap_mfa_method: GraphicalMatrix
ldap_totp_status: UNREGISTERED
ldap_totp_registered_at: 0
ldap_last_success_at: 0
ldap_force_sequence_change: 1
ldap_state_version: 0
ldap_created_at: 0
ldap_updated_at: 0
EOF

ldapadd -x -H ldaps://ldap.example.jp:636 \
  -D 'cn=Directory Manager' -W \
  -f /tmp/2faskw-user-test03.ldif
```

MFA方式をTOTPまたはWebAuthnに変更する例:

```ldif
dn: uid=test03,ou=People,dc=example,dc=test
changetype: modify
replace: ldap_mfa_method
ldap_mfa_method: WebAuthn
```

v1.2.0フェーズ1では、Admin Tools、Admin users API、`graphicalmatrix-db.sh` はDB保存の管理を前提にしています。
LDAP保存ユーザーの追加、更新、削除はLDAP側の `ldapadd` / `ldapmodify`、またはLDAP連携済みの管理システムで実施してください。

### ACL設定

service accountには、ユーザー検索と2FAS-KW属性の読み書きだけを許可します。
389 Directory ServerのACI例:

```ldif
dn: ou=People,dc=example,dc=test
changetype: modify
add: aci
aci: (targetattr="uid || cn || ldap_sequence || ldap_initial_sequence || ldap_status || ldap_failed_count || ldap_locked_until || ldap_mfa_method || ldap_totp_seed || ldap_totp_status || ldap_totp_registered_at || ldap_last_success_at || ldap_force_sequence_change || ldap_state_version || ldap_created_at || ldap_updated_at")(version 3.0; acl "Allow 2FAS-KW LDAP enrollment writer"; allow (read, search, compare, write)(userdn="ldap:///cn=graphicalmatrix-writer,dc=example,dc=test");)
```

`ldap_totp_seed` は認証秘密情報です。一般ユーザー、不要な管理者、汎用LDAP連携アプリから読めないようにしてください。

### WebAuthn LDAP subtree属性

WebAuthn credentialをLDAPへ保存する場合は、ユーザー属性へ直接置く `user-entry` よりも `subtree` 方式を推奨します。
`subtree` 方式では、StorageServiceの1 recordを1 LDAP entryとして保存します。

代表的な属性:

| 用途 | 既定例 | 内容 |
| --- | --- | --- |
| context | `gmStorageContext` | StorageService context。 |
| id | `gmStorageId` | StorageService id。多くの場合ユーザーID。 |
| expires | `gmStorageExpires` | 有効期限。 |
| value | `gmStorageValue` | credential JSON。 |
| version | `gmStorageVersion` | 楽観ロック用version。 |

検証用schema例:

```ldif
dn: cn=schema
changetype: modify
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1300.1 NAME 'gmStorageContext' DESC '2FAS-KW WebAuthn storage context' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1300.2 NAME 'gmStorageId' DESC '2FAS-KW WebAuthn storage id' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1300.3 NAME 'gmStorageExpires' DESC '2FAS-KW WebAuthn storage expiration' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1300.4 NAME 'gmStorageValue' DESC '2FAS-KW WebAuthn storage value' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
-
add: attributeTypes
attributeTypes: ( 1.3.6.1.4.1.55555.1300.5 NAME 'gmStorageVersion' DESC '2FAS-KW WebAuthn storage version' EQUALITY caseExactMatch SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )
```

subtree作成例:

```ldif
dn: ou=WebAuthnStorage,dc=example,dc=test
objectClass: top
objectClass: organizationalUnit
ou: WebAuthnStorage
```

WebAuthn subtreeを使う場合のLDAPツリー構造:

```text
dc=example,dc=test
├── cn=graphicalmatrix-writer
├── ou=People
│   └── uid=test01
│       ├── objectClass: inetOrgPerson
│       ├── objectClass: graphicalMatrixEnrollment
│       ├── ldap_mfa_method: WebAuthn
│       └── ldap_status: ACTIVE
└── ou=WebAuthnStorage
    └── cn=<generated-record-rdn>
        ├── objectClass: extensibleObject
        ├── gmStorageContext: <StorageService context>
        ├── gmStorageId: test01
        ├── gmStorageExpires: <epoch milliseconds or 0>
        ├── gmStorageValue: <credential JSON>
        └── gmStorageVersion: <version>
```

`ou=People` はGraphicalMatrix / TOTP / MFA方式選択の保存先です。
`ou=WebAuthnStorage` はWebAuthn credential recordの保存先です。
`gmStorageId` は、ユーザーエントリ側のログインIDと同じ体系に統一してください。

ACI例:

```ldif
dn: ou=WebAuthnStorage,dc=example,dc=test
changetype: modify
add: aci
aci: (targetattr="cn || gmStorageContext || gmStorageId || gmStorageExpires || gmStorageValue || gmStorageVersion")(target="ldap:///ou=WebAuthnStorage,dc=example,dc=test")(version 3.0; acl "Allow 2FAS-KW WebAuthn LDAP StorageService"; allow (read, search, compare, write, add, delete)(userdn="ldap:///cn=graphicalmatrix-writer,dc=example,dc=test");)
```

`gmStorageValue` にはWebAuthn credential JSONが入ります。現段階ではLDAP StorageService側の保存形式は `plaintext` のため、
LDAP ACLとバックアップ暗号化で保護してください。

### WebAuthn LDAP運用条件

LDAPへWebAuthn credentialを保存する場合は、DB保存よりも運用で担保する整合性が増えます。
特に、ログインID、MFA方式属性、WebAuthn StorageService recordの `id` を同じユーザーID体系に統一してください。

統一するID:

- IdPのログインID
- GraphicalMatrix / TOTP / MFA方式選択で使うユーザーID
- `graphicalmatrix.ldap.userFilter` の `{user}`
- WebAuthn StorageService recordの `id`
- `graphicalmatrix.webauthn.ldap.userFilter` を使う場合の `{id}` / `{user}`

推奨は、LDAPの `uid` を正のユーザーIDとして使う運用です。
ログインは `uid`、MFA保存は `cn`、WebAuthnは `mail` のように分けると、登録情報を検索できない、または別ユーザー扱いになるリスクがあります。

LDAP保存では、2FAS-KWの情報が大きく2系統に分かれます。

| 系統 | 保存先 | 代表キー | 内容 |
| --- | --- | --- | --- |
| GraphicalMatrix / TOTP / MFA方式 | ユーザーエントリ属性 | ユーザーDN / `uid` | sequence、TOTP seed、`ldap_mfa_method` など。 |
| WebAuthn credential | WebAuthn StorageService | `context + id` | credential JSON、version、expiration。 |

そのため、WebAuthnを使うユーザーでは、次の2つが常に整合している必要があります。

- MFA方式属性がWebAuthnを指している
- 同じユーザーIDのWebAuthn credential recordが存在する

例:

```ldif
dn: uid=test01,ou=People,dc=example,dc=test
ldap_mfa_method: WebAuthn
```

```text
gmStorageContext: <WebAuthn pluginが使うStorageService context>
gmStorageId: test01
gmStorageValue: <credential JSON>
```

`ldap_mfa_method` が `WebAuthn` でもcredential recordがない場合、そのユーザーはWebAuthn認証を完了できません。
逆にcredential recordが残っていても、MFA方式属性が `GraphicalMatrix` または `TOTP` の場合はWebAuthnへ進みません。

運用上の条件:

- WebAuthn credential保存は、DB/JDBC StorageServiceを推奨する。
- LDAPへ保存する場合は `subtree` 方式を推奨する。
- `user-entry` 方式は1ユーザー1recordに近い制約があるため、複数credentialや複数contextの運用では避ける。
- WebAuthn subtreeはユーザーLDAPとは別の管理領域にする。
- LDAP schema、ACL、`ldap.properties`、`webauthn-ldap.properties` の属性名を完全に一致させる。
- GraphicalMatrix/TOTP用属性とWebAuthn credential用属性のACLを分けて設計する。
- 本番ではGraphicalMatrix/TOTP保存用service accountとWebAuthn StorageService用service accountを分けることを検討する。
- ユーザー削除、無効化、再登録時は、ユーザー属性側とWebAuthn subtree側の両方を処理する。
- LDAP上のユーザーIDを変更する運用では、WebAuthn StorageService recordの `id` 側も移行する。
- DB/JDBC StorageServiceからLDAP StorageServiceへ切り替えても、既存credentialは自動移行されない。
- StorageServiceを切り替える場合は、credential移行または利用者の再登録を計画する。
- バックアップとリストアは、ユーザーエントリの2FAS-KW属性、WebAuthn subtree、bind secret、sequence/TOTP用secretを同じ時点でそろえる。

削除・無効化時に確認する対象:

- ユーザーエントリの `ldap_mfa_method`
- GraphicalMatrix / TOTP属性
- WebAuthn subtree上のcredential record
- 必要に応じたWebAuthn再登録案内

LDAP StorageServiceは `isClustered=true` のStorageServiceとして扱われます。
複数IdP構成では、すべてのIdPが同じLDAP URL、baseDN、属性名、bind権限を使うようにしてください。
IdPごとに異なるWebAuthn LDAP設定を使うと、登録したcredentialを別IdPから参照できなくなります。

## プラグインの設定（TOTP, WebAuthn）

### 2FAS-KW LDAP保存を有効化する

`graphicalmatrix.properties`:

```properties
graphicalmatrix.savedata = ldap
graphicalmatrix.productionMode = true

graphicalmatrix.sequence.storage = auto
graphicalmatrix.sequence.pepperFile = /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper

graphicalmatrix.totp.seed.storage = aes-gcm
graphicalmatrix.totp.seed.aesKeyFile = /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
```

secret file:

```bash
sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper
openssl rand -hex 32 | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-sequence.pepper >/dev/null

sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key
openssl rand -base64 32 | sudo tee \
  /opt/shibboleth-idp/credentials/graphicalmatrix-totp-aes.key >/dev/null
```

`ldap.properties`:

```properties
graphicalmatrix.ldap.url = ldaps://ldap.example.jp:636
graphicalmatrix.ldap.baseDN = ou=People,dc=example,dc=jp
graphicalmatrix.ldap.userFilter = (uid={user})
graphicalmatrix.ldap.subtreeSearch = true

graphicalmatrix.ldap.bindDN = cn=graphicalmatrix-writer,ou=system,dc=example,dc=jp
graphicalmatrix.ldap.bindCredentialFile = /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret

graphicalmatrix.ldap.attr.sequence = ldap_sequence
graphicalmatrix.ldap.attr.initial_sequence = ldap_initial_sequence
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
graphicalmatrix.ldap.attr.created_at = ldap_created_at
graphicalmatrix.ldap.attr.updated_at = ldap_updated_at
```

bind secret:

```bash
sudo install -o root -g jetty -m 0640 /dev/null \
  /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret
sudo sh -c 'printf "%s\n" "change_this_bind_password" > /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret'
```

### TOTP

TOTPは2FAS-KWの `totp_seed` 属性を使います。
LDAP保存時は、TOTP seedを復号して認証する必要があるため、`aes-gcm` を推奨します。
`hash` はTOTP seedには使えません。

Shibboleth TOTP pluginを導入し、TOTP用authn flowを有効化します。
詳細なプラグイン導入手順はShibboleth IdPのplugin管理手順に従ってください。

2FAS-KW側では、ユーザーの `ldap_mfa_method` を `TOTP` にすると、次回ログイン時にTOTP登録画面へ進みます。

```ldif
dn: uid=test01,ou=People,dc=example,dc=test
changetype: modify
replace: ldap_mfa_method
ldap_mfa_method: TOTP
-
replace: ldap_totp_seed
-
replace: ldap_totp_status
ldap_totp_status: UNREGISTERED
```

### WebAuthn

WebAuthnはShibboleth WebAuthn pluginが必要です。
ブラウザ仕様上、HTTPSとFQDNが前提です。IPアドレスURL、HTTP URL、ブラウザが信頼していない自己署名証明書では登録に失敗します。

推奨:

- WebAuthn credential保存はDB/JDBC StorageService
- LDAP保存を選ぶ場合は `subtree` 方式
- `user-entry` 方式は1ユーザー1recordに近い制約があるため、複数credentialや複数contextの運用では避ける

LDAP subtree保存を使う場合は、`webauthn-ldap-storage-config.xml` をIdPのSpring設定に読み込ませ、
`webauthn.properties` のStorageServiceを切り替えます。

`global.xml` へbeanを追加する例:

```xml
<bean id="GraphicalMatrixLDAPStorageService"
      class="io.github.yasakawa.faskw.GraphicalMatrixLdapStorageService"
      p:id="GraphicalMatrixLDAPStorageService"
      p:idpHome="%{idp.home:/opt/shibboleth-idp}" />
```

`webauthn.properties`:

```properties
idp.authn.webauthn.relyingPartyId = idp.example.jp
idp.authn.webauthn.relyingPartyName = Example IdP
idp.authn.webauthn.allowOriginPort = true
idp.authn.webauthn.origins = https://idp.example.jp
idp.authn.webauthn.StorageService = GraphicalMatrixLDAPStorageService
idp.authn.webauthn.2fa.enabled = true
idp.authn.webauthn.2fa.allowedPreviousFactors = authn/Password
```

`webauthn-ldap.properties`:

```properties
graphicalmatrix.webauthn.ldap.url = ldaps://ldap.example.jp:636
graphicalmatrix.webauthn.ldap.bindDN = cn=graphicalmatrix-writer,ou=system,dc=example,dc=jp
graphicalmatrix.webauthn.ldap.bindCredentialFile = /opt/shibboleth-idp/credentials/graphicalmatrix-ldap-bind.secret

graphicalmatrix.webauthn.ldap.value.storage = plaintext
graphicalmatrix.webauthn.ldap.layout = subtree
graphicalmatrix.webauthn.ldap.baseDN = ou=WebAuthnStorage,dc=example,dc=jp
graphicalmatrix.webauthn.ldap.recordRdnAttr = cn
graphicalmatrix.webauthn.ldap.objectClasses = top,organizationalRole,extensibleObject

graphicalmatrix.webauthn.ldap.attr.context = gmStorageContext
graphicalmatrix.webauthn.ldap.attr.id = gmStorageId
graphicalmatrix.webauthn.ldap.attr.expires = gmStorageExpires
graphicalmatrix.webauthn.ldap.attr.value = gmStorageValue
graphicalmatrix.webauthn.ldap.attr.version = gmStorageVersion
```

WebAuthn登録画面で `The operation is insecure.` が出る場合は、ブラウザで以下を確認してください。

```javascript
window.isSecureContext
location.origin
location.hostname
typeof PublicKeyCredential
```

期待値:

```javascript
true
"https://idp.example.jp"
"idp.example.jp"
"function"
```

自己署名証明書で検証する場合は、サーバ証明書ではなく、署名元のローカルCA証明書をブラウザへ信頼登録してください。

### 反映と確認

設定変更後:

```bash
sudo /opt/shibboleth-idp/bin/build.sh
sudo systemctl restart jetty-idp.service
```

GraphicalMatrix/TOTP属性確認:

```bash
ldapsearch -x -H ldaps://ldap.example.jp:636 \
  -D 'cn=graphicalmatrix-writer,ou=system,dc=example,dc=jp' -W \
  -b 'uid=test01,ou=People,dc=example,dc=jp' \
  objectClass ldap_sequence ldap_status ldap_mfa_method ldap_totp_status
```

WebAuthn subtree確認:

```bash
ldapsearch -x -H ldaps://ldap.example.jp:636 \
  -D 'cn=graphicalmatrix-writer,ou=system,dc=example,dc=jp' -W \
  -b 'ou=WebAuthnStorage,dc=example,dc=jp' \
  '(gmStorageContext=*)' gmStorageContext gmStorageId gmStorageValue gmStorageVersion
```

IdPログ確認:

```bash
sudo tail -n 100 /opt/shibboleth-idp/logs/idp-process.log
sudo tail -n 100 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```
