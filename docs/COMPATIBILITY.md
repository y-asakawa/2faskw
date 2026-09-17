# 2FAS-KW Compatibility Matrix

この文書は、2FAS-KWの最新安定リリースについて、対応範囲、build時の基準、
参照環境での検証実績および未検証範囲を整理します。

- **対象リリース:** 2FAS-KW v1.3.5
- **サポート状態:** Current
- **サポート方針:** [SUPPORT-POLICY.md](./SUPPORT-POLICY.md)

新しい2FAS-KWリリースが公開された場合、この文書もそのリリースに合わせて更新します。
旧リリースの互換性は各リリースノートと、その時点のGit履歴を参照してください。

## 1. 表記

| 表記 | 意味 |
| --- | --- |
| 対応 | `version.ini`、plugin metadataまたは公開設定で、現行リリースの対象として宣言している範囲。 |
| Build基準 | Maven buildでコンパイルに使用しているAPIまたはlibraryのバージョン。 |
| 参照検証 | プロジェクトの構築記録、負荷試験または導入手順で実際に使用した構成。全組合せの保証ではない。 |
| 条件付き | 対象機能を有効にする場合だけ必要となる外部componentまたは設定。 |
| 未検証 | 動作する可能性はあるが、プロジェクトとして互換性を確認していない構成。 |

「Build基準」は、そのバージョンだけを実行可能とする意味ではありません。一方、buildに
成功することだけでは、実際のIdP、DB、LDAPまたはブラウザでの動作確認にはなりません。

## 2. 中核実行環境

| Component | 現行v1.3.5の範囲 | 検証・根拠 | 判定 |
| --- | --- | --- | --- |
| 2FAS-KW | v1.3.5 | `version.ini`とplugin metadataの`Current` release | 対応 |
| Shibboleth IdP | 5.2.1以上、5.2.4未満 | Build基準5.2.3。構築記録5.2.2、負荷試験5.2.3 | 対応 |
| Java | 21以上を最低条件として宣言 | Java 21 release target、CIはTemurin 21 | Java 21を参照検証。22以降は未検証 |
| Jetty | 12以上を最低条件として宣言 | Jetty 12.0.36の構築記録 | Jetty 12を参照検証。将来のmajor versionは未検証 |
| Jakarta Servlet API | 6.1.0 | Mavenのprovided API。実体はIdP/Jetty runtimeが提供 | Build基準 |
| Linux | Java plugin自体は特定distribution専用ではない | Rocky Linux 10.x、参照記録はRocky Linux 10.2 | Rocky Linux 10.xを参照検証 |
| CPU architecture | Java部分はarchitecture非依存 | Rocky Linux 10.2 aarch64の構築記録 | aarch64を参照検証。他architectureは個別確認 |

Shibboleth plugin metadataの`idpVersionMax`は上限を含みません。したがって、
`idpVersionMax=5.2.4`は「5.2.4まで」ではなく、**5.2.4未満**を意味します。
Shibboleth IdP 5.2.4以降は、現行v1.3.5の宣言範囲に含まれません。

JavaとJettyには上限値を宣言していませんが、未検証の将来バージョンまで互換性を保証する
意味ではありません。Java 21およびJetty 12を基本構成としてください。

## 3. OSと運用基盤

| 環境 | 状態 | 注意事項 |
| --- | --- | --- |
| Rocky Linux 10.x | 参照検証 | 導入手順、systemd、firewalld、dnf、PGDG RPMの基準環境。 |
| AlmaLinux / RHEL / CentOS Stream | 未検証 | RHEL互換として近い構成だが、package名、SELinux、service定義を確認する。 |
| Debian / Ubuntu | Build CIのみ、配備は未検証 | `ubuntu-latest`でMaven buildを行うが、IdP配備手順は検証していない。 |
| Windows / macOS | サーバ配備は未対応 | 開発用buildを除き、systemd前提の配布scriptは使用できない。 |
| SELinux enforcing | 参照検証 | `/opt`配下のIdP、ログ、SimpleSAMLphpなどは適切なfile contextが必要。 |
| systemd | Linux運用での基準 | Jetty、Dashboard、Admin Tools CSV runnerなどのservice/path管理に使用。 |

Admin ToolsとDashboardのJava本体はRocky Linux固有ではありません。ただし、同梱の導入例、
service unit、Firewall設定およびPostgreSQL clientの配置はRHEL互換環境を前提とします。

## 4. DatabaseとLDAP

| Component | 現行の扱い | 参照構成 | 注意事項 |
| --- | --- | --- | --- |
| PostgreSQL | 本番推奨、既定DB | PostgreSQL 18/18.4 | Serverの正式な最小・最大minor rangeは宣言していない。導入環境でschema、TLS、backupを試験する。 |
| PostgreSQL JDBC Driver | 配布物へ同梱 | 42.7.13 | `pom.xml`の現行runtime dependency。 |
| HikariCP | DB pool利用時に同梱 | 7.1.0 | `graphicalmatrix.db.pool.*`で設定する。 |
| H2 | PoC、移行および自動test用途 | 2.4.240 | 本番のenrollment DBとしてはサポートしない。 |
| 389 Directory Server | LDAP保存とLDAP属性連携の参照環境 | Rocky Linux上の389 Directory Server | 専用schema、ACL、LDAPSまたは検証済みTLSが必要。 |
| その他のLDAPv3 server | 未検証 | なし | schema、atomic update、ACL、timestamp、bind/TLS動作を個別に確認する。 |

PostgreSQL 18以外がJDBC Driver上で接続可能であっても、それだけで2FAS-KWの検証済み構成には
なりません。本番採用前にschema適用、登録、認証、lockout、同時更新、backup/restoreを確認して
ください。

LDAPには次の異なる用途があります。どちらを使用するかを区別してください。

- Shibboleth IdPのPassword認証およびAttribute Resolver
- 2FAS-KW enrollment、TOTP seed、MFA方式またはWebAuthn StorageServiceの保存

通常の構成では、2FAS-KW enrollmentはPostgreSQL、Password認証と属性取得はLDAPを使用します。

## 5. MFA Plugin互換性

| MFA方式 | 外部Plugin | 現行v1.3.5の基準 | 状態 |
| --- | --- | --- | --- |
| GraphicalMatrix | 不要 | 2FAS-KW本体の`authn/External` flow | 標準機能 |
| TOTP | Shibboleth TOTP Plugin | Build基準2.3.2 | 条件付き対応 |
| WebAuthn | Shibboleth WebAuthn Plugin | 自己管理画面からの方式変更は1.3.0以上 | 条件付き対応 |

TOTP PluginとWebAuthn Pluginは2FAS-KW配布物に含まれません。別途導入し、Shibboleth IdP側で
対応するauthentication flowを有効にする必要があります。

WebAuthnの通常認証だけでなく、2FAS-KW自己管理画面から安全に方式変更する場合は、credential
保存成功後の`AddKeyAuditSuccessHook`を備えたShibboleth WebAuthn Plugin 1.3.0以上が必要です。
WebAuthnはさらに、HTTPS、正しいFQDN、RP ID、origin、信頼済み証明書および適切な
StorageServiceを必要とします。

TOTP/WebAuthn Pluginの将来バージョンは、公開API、bean名、flow IDまたは設定形式が変わる可能性が
あるため、2FAS-KW更新とは別に登録と通常認証の回帰試験を行ってください。

## 6. SPとブラウザ

| 対象 | 状態 | 現行の参照範囲 |
| --- | --- | --- |
| SAML 2.0 SP | 対応 | 正しいentityID、ACS、署名・暗号化設定およびIdP metadata trustを持つSP。 |
| Shibboleth SP | 本番推奨 | 個別version rangeは未宣言。利用中versionでSSO、属性、SLOを試験する。 |
| SimpleSAMLphp | 検証用 | SimpleSAMLphp 2.5.2、Apache HTTP Server、PHP 8.3の参照構成。 |
| Desktop browser | 条件付き | JavaScript、cookie、HTTPSおよびIdPが必要とするbrowser機能が有効であること。 |
| Mobile browser | 条件付き | responsive gridを提供するが、browser/version別の正式matrixは未整備。 |
| WebAuthn authenticator | 条件付き | browser、OS、RP ID、credential policyおよびWebAuthn Pluginの対応範囲に依存。 |

2FAS-KWは特定のSP製品だけに限定されません。ただし、SPごとのmetadata、IdP署名証明書、属性名、
NameID、MFA要求およびclock skewを導入前に確認してください。

ブラウザについては、特定versionごとの互換性を宣言していません。少なくとも、組織でサポートする
desktop/mobile browserごとにPassword認証、GraphicalMatrix選択、再試行、自己管理、TOTP登録、
WebAuthn登録およびSP復帰を受入試験してください。

## 7. Admin ToolsとDashboard

| 成果物 | 必須runtime | 保存先・接続先 | 状態 |
| --- | --- | --- | --- |
| `2faskw-admin-tools` | Java 21、bash、利用機能に応じて`psql` | 2FAS-KWと同じPostgreSQLおよび同じstorage-format secret | v1.3.5と同一版を使用 |
| `2faskw-dashboard` | Java 21、bash | Dashboard専用H2 index、監査logまたはAgent入力 | v1.3.5と同一版を使用 |
| Dashboard Agent | Java 21、bash、audit log read権限 | HTTPS+mTLSでDashboardへ送信 | Dashboardと同一版を使用 |

IdP Plugin、Admin Tools、Dashboardは同じリリースから取得し、異なる2FAS-KWバージョンを混在
させないでください。Admin Toolsで保護済みsequenceやTOTP seedを扱う場合は、IdPと同じ保存方式、
pepperまたは暗号鍵が必要です。

## 8. Build・CI基準

| 項目 | 現行値 | 用途 |
| --- | --- | --- |
| Java release target | 21 | Plugin、Admin Tools、Dashboardのコンパイル |
| Shibboleth IdP API | 5.2.3 | Plugin build時のprovided API |
| OpenSAML API | 5.2.3 | Plugin build時のprovided API |
| Shibboleth shared support | 9.2.3 | Plugin build時のprovided API |
| Shibboleth TOTP Plugin | 2.3.2 | TOTP連携のprovided build dependency |
| Jakarta Servlet API | 6.1.0 | Servlet build時のprovided API |
| Spring Framework | 7.0.9 | Build時のprovided API |
| SLF4J API | 2.0.18 | Build時のprovided API |
| Maven | 3.9系を導入手順で使用 | Source build |
| GitHub Actions | `ubuntu-latest`、Temurin 21 | Package buildとregression scripts |

runtimeへprovided APIの別copyを追加してはなりません。Shibboleth IdPとJettyが提供するAPIと重複する
JARを`edit-webapp/WEB-INF/lib`へ手作業で追加すると、class loading conflictの原因になります。

PluginとDashboardの`pom.xml`にあるfallback `revision`は`0.0.0-SNAPSHOT`です。正式なpackage
buildは`version.ini`の`VERSION`を`-Drevision`で渡し、生成された両JARの
`Implementation-Version`が一致することを自動検査します。引数なしの直接Maven buildはrelease
成果物ではありません。

## 9. 現在のバージョン確認

ソースツリーでは、次のコマンドで2FAS-KWの宣言値を確認します。

```bash
awk -F= '/^(VERSION|IDP_VERSION_MIN|IDP_VERSION_MAX|JAVA_VERSION_MIN|JETTY_VERSION_MIN)=/ { print }' \
  version.ini
```

IdPサーバでは、runtimeと導入済みPluginを確認します。

```bash
java -version

/opt/shibboleth-idp/bin/version.sh

sudo /opt/shibboleth-idp/bin/plugin.sh -l
```

JettyとPostgreSQLは、実環境の配置に合わせて確認します。

```bash
java -jar /opt/jetty-home/start.jar --list-config | grep 'jetty.version'

psql --version

sudo -u postgres psql -Atqc 'show server_version'
```

導入済み2FAS-KW JARのmanifestは次の例で確認できます。

```bash
unzip -p /opt/shibboleth-idp/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar \
  META-INF/MANIFEST.MF | grep '^Implementation-Version:'
```

複数versionの2FAS-KW JARが同時に表示される場合は正常な構成ではありません。
[UPGRADE.md](./UPGRADE.md)に従って停止、backup、旧JAR整理、WAR再構築および回帰試験を行って
ください。

## 10. 採用前の判定

本番環境は、少なくとも次を満たす場合に現行互換範囲内と判断します。

1. 2FAS-KW、Admin Tools、Dashboardを使用する場合はすべてv1.3.5で揃っている。
2. Shibboleth IdPが5.2.1以上5.2.4未満である。
3. Java 21とJetty 12を使用している。
4. 本番enrollment DBがPostgreSQLであり、schemaと保護済み保存方式を検証している。
5. TOTP/WebAuthnを使う場合は、対応Pluginを別途導入して登録・認証を確認している。
6. 利用するOS、LDAP、SP、browserおよび認証器で受入試験を完了している。
7. [SECURITY-CHECKLIST.md](./SECURITY-CHECKLIST.md)、
   [BACKUP-RESTORE.md](./BACKUP-RESTORE.md)、
   [OPERATIONS-RUNBOOK.md](./OPERATIONS-RUNBOOK.md)および[UPGRADE.md](./UPGRADE.md)の確認を
   完了している。

この範囲内であっても、可用性、性能、HA、組織固有のLDAP schema、SP属性要件および認証器の
組合せを保証するものではありません。

## 11. 更新ルール

次を変更するpull requestでは、この文書も同時に確認してください。

- `version.ini`のIdP、JavaまたはJetty範囲
- `pom.xml`または`dashboard/pom.xml`のruntime/build dependency
- Shibboleth TOTP/WebAuthn Pluginとの連携
- 対応OS、DB、LDAP、SP、browserまたは認証器
- Admin Tools、Dashboardまたはpackage構成
- CIのJava、OSまたは試験内容

`version.ini`、plugin metadata、Maven build基準、この文書およびリリースノートが矛盾する場合は、
公開前に値を統一してください。
