# SP Management Internals

この文書は、`graphicalmatrix-sp.sh`が管理するSAML SP metadata、registry、属性release、
MFA方針、SP別access policyの内部設計を説明します。操作手順は
[INSTALL_NEW_SP.md](../INSTALL_NEW_SP.md)と
[COMMAND-REFERENCE.md](../COMMAND-REFERENCE.md)を参照してください。

## Command architecture

```text
graphicalmatrix-sp.sh
  -> Java classpath and IDP_HOME setup
  -> GraphicalMatrixSpManagementTool.main()
       |-- lifecycle commands
       |-- revision/rollback commands
       |-- set-mfa and mfa global commands
       `-- GraphicalMatrixSpGovernanceTool
            |-- attributes discover/approve/profile/resolver
            `-- access init/set/test/enable/disable
```

`GraphicalMatrixSpManagementTool.execute()`がtop-level commandを分配します。属性catalog、
resolver、access policyのnested commandは`GraphicalMatrixSpGovernanceTool`が担当します。

## Managed artifacts

| Artifact | Purpose |
| --- | --- |
| `conf/graphicalmatrix/sp-management.properties` | 機能有効化、host allowlist、reload、retention、runtime group |
| `conf/graphicalmatrix/sp-management-registry.json` | managed SPの正規状態 |
| `metadata/2faskw-managed-sp/` | entityIDごとのmanaged metadata |
| `conf/metadata-providers.xml` | `GraphicalMatrixManagedSPMetadata` provider |
| `conf/attribute-filter.xml` | managed属性release block |
| `conf/graphicalmatrix/mfa-policy.properties` | 全体MFA ruleとCLI管理SPの集合 |
| `conf/graphicalmatrix/attribute-catalog.json` | 属性承認とmanaged profile |
| `conf/graphicalmatrix/access-policy.json` | SP別属性access policy |
| revision/backup directories | rollback、legacy復元、障害調査用snapshot |

registryは表示用cacheではありません。managed metadata、属性profile、MFA profile、revision、
adoptしたlegacy provider情報を結ぶ管理上の正規状態です。

## Registry model

`GraphicalMatrixSpRegistry.Entry`の主要fieldは次のとおりです。

| Field | Meaning |
| --- | --- |
| `name` | CLIで使う一意なlocal management name |
| `entityId` | SAML SP entityID |
| `status` | `ACTIVE`またはdisabled状態 |
| `source` | metadataの取得元 |
| `metadataSha256` | 承認・drift検査用digest |
| `metadataFile` | managed directory内の相対path |
| `certificateFingerprints` | metadata内証明書の観測値 |
| `acsUrls` | metadata内ACS URL |
| `attributeProfile` | 適用する属性release profile |
| `mfaProfile`, `cidrs` | SP別MFA方針 |
| `currentRevision` | 現在のrevision |
| `legacy*` | adopt前の手作業状態を復元するためのsnapshot |
| `attributeProfileRevision` | profile適用revision |
| `accessPolicyEnabled`, `accessPolicyRevision` | SP別access policy状態 |

`name`はprovider IDではなく、`[a-z0-9][a-z0-9-]{0,62}`に一致する管理名です。
entityIDの重複は別名で登録できません。

## Dry-run and apply

変更commandは原則として最初の実行をdry-runにします。dry-runは入力、現在状態、metadata、
policy、予定差分を検証し、必要な確認値または次commandを表示します。

```text
dry-run
  -> load config and registry
  -> validate input and source
  -> build candidate state in memory
  -> render plan/digest
  -> no managed file mutation

apply
  -> repeat validation
  -> verify explicit confirmation/digest
  -> create backup/revision snapshot
  -> atomic writes
  -> reload/check when enabled
  -> commit registry revision
  -> prune old retained artifacts
```

`--apply`を付けたcommandも、確認値が必要な操作では確認なしに進みません。digestは別の入力・
別のplanを誤って適用しないためのbindingです。

## Initialization

`init`はmanaged provider、managed attribute-filter block、registry、directory権限の予定を
確認します。`init --apply`は必要なファイルを作成・更新します。

`metadata-providers.xml`を変更した時点では、実行中の
`shibboleth.MetadataResolverService`へ新providerが存在するとは限りません。初回初期化後は
IdPをrestartし、status endpointの準備完了を待ってからmetadata reloadを確認します。

`status`はdisk上の設定とmetadataを調査するcommandであり、実行中serviceへの登録済み状態を
単独では保証しません。

## Add and update

### Add

```text
metadata file or URL
  -> GraphicalMatrixSpMetadata.fromFile()/fromUrl()
  -> size and source validation
  -> secure XML parse
  -> entityID exact match
  -> ACS host allowlist
  -> metadata SHA-256 approval
  -> write normalized managed file
  -> create registry entry/revision
  -> render attribute/MFA managed state
  -> reload metadata and verify
```

URL取得元の`allowedHosts`と、metadata内ACS endpointの`allowedAcsHosts`は別の境界です。
前者はIdP管理CLIが接続してよいhost、後者は登録してよいSP endpoint hostを制限します。

### Update

updateは既存entryを読み、entityIDが変わらないことを確認し、新しいdigest、証明書、ACSを
比較します。差分をrevisionとして保存してからmanaged metadataを置き換えます。

metadata sourceが自動更新されることと、IdP署名証明書をSPが自動取得することは別機能です。
このCLIはSP metadataの管理を担当し、SP側のIdP metadata refresh方針を設定しません。

## Adopt and legacy restore

`adopt`は既存の`FilesystemMetadataProvider`で手作業管理されているentityIDをmanaged registryへ
移行します。

1. `status --entity-id`で既存providerと実metadata pathを特定する。
2. metadataをJetty実行groupが読めることを確認する。
3. `adopt NAME --entity-id ...`でplanを確認する。
4. apply時にprovider XML、metadata path、属性・MFA状態をlegacy snapshotへ保存する。
5. managed metadataへcopyし、元の手作業providerを管理対象から除く。

`restore-legacy`は保存した`legacy*`情報を使ってadopt前のprovider、metadata参照、属性・MFA
状態へ戻します。単なるmanaged entryの削除ではありません。rollback/restore時も現行fileを
backupしてからatomic updateします。

## Metadata validation

`GraphicalMatrixSpMetadata`は次を検査します。

- local fileが許可されたroot内にあり、symlinkやpath traversalで境界を越えないこと。
- remote URLのscheme、host allowlist、名前解決結果、redirect先が許可されること。
- connect/read timeoutと最大byte数。
- external entityを無効化したsecure XML parser。
- expected entityIDとの完全一致。
- ACS URLのscheme/hostと`allowedAcsHosts`。
- certificate情報とSHA-256 digest。

remote fetch制約を変更するとSSRF防止境界へ影響します。host文字列だけでなく、名前解決、
redirect、private addressの扱いを含めてtestしてください。

## Filesystem and atomicity

`GraphicalMatrixSpFiles.atomicWrite()`は同一directoryの一時fileへ書き、permissionを整え、
atomic moveで置き換えます。途中で失敗した不完全なJSON/XMLを正規fileとして残さないためです。

標準のownership modelは次のとおりです。

| Path type | Owner/group | Mode intent |
| --- | --- | --- |
| managed metadata directory/registry | `root:<runtime-group>` | directory `0750` |
| managed metadata/registry files | `root:<runtime-group>` | file `0640` |
| backup directory | `root:root` | directory `0700` |

Jettyはmanaged metadataを読み取るだけで、管理CLIが書き込みます。runtime groupは
`graphicalmatrix.sp.runtimeGroup`で環境に合わせます。

revisionとbackupの保持は別設定です。command成功後のpruneで、日数とSPごとの最大revision数を
適用します。OS timerが独立して削除する設計ではありません。

## Reload behavior

`graphicalmatrix.sp.reload.baseUrl`は、CLIがIdP admin endpointへ接続するbase URLです。
IdPが既定の`http://localhost/idp`で待ち受けない場合にloopback listenerへ合わせます。

reloadの代表的な失敗は次の境界で分類します。

- connection refused: IdP listenerが未起動、起動待ち、URL/port不一致。
- 404 metadata source not found: 実行中serviceへproviderが未登録。
- 500 MetadataResolverService unavailable: provider設定全体のload失敗。

`FilesystemMetadataProvider`が参照するfileをJettyが読めない場合、対象SPだけでなくmetadata
resolver service全体が利用不能になり得ます。providerに記載された実pathを確認し、runtime
userでread testを行ってからservice reload/restartを実施します。

## Attribute catalog and profiles

attribute discoveryは次の情報を統合します。

- `attribute-resolver.xml`の宣言。
- Shibboleth Attribute RegistryのSAML mapping。
- AACLIによる指定user/SPでのruntime解決結果。
- 2FAS-KW catalogのgovernance状態。
- managed profileからの参照。

`attributes approve`は属性を`release`または`access`用途として承認します。承認だけではSPへ
送信されません。release承認済み属性をmanaged profileへ含め、そのprofileをSPへ割り当てる
ことで`attribute-filter.xml`のmanaged blockへ反映します。

builtin profileは既定定義であり編集しません。組織固有の組合せはmanaged profileとして
作成します。

LDAPに存在してもresolverにない属性はAACLIへ現れません。`attributes resolver add`は既存の
`LDAPDirectory` DataConnectorへAttributeDefinitionを追加する計画を生成します。DataConnector
がない環境では、まずIdPのLDAP resolver接続自体を構成する必要があります。

## SP access policy

access policyはrelease policyとは独立しています。

```text
attributes approve --usage access
  -> attribute becomes eligible for internal authorization
access set SP --allow/--deny
  -> policy saved for one managed SP
access enable SP
  -> ContextCheck evaluates policy
```

`GraphicalMatrixSpAccessEvaluator`はdenyを先に評価し、allow条件をすべて満たしたときだけ許可
します。policy未設定またはdisabledのSPへ制限は掛かりません。

## SP MFA management

`set-mfa`は1件のmanaged SPに`inherit`、`force`、`bypass`、`required`、
`sp-cidr-bypass`を設定します。`mfa global set`はdefault、評価順、全体IP/CIDR例外などを
管理します。

CLI管理SPに対応する`forceSPs`、`bypassSPs`、`requiredSPs`、`bypassSpCidrs`を手作業で変更すると
registryとのdriftになります。`mfa show`と`mfa reconcile`はこの差分を検出・修復します。

## Tests

| Concern | Tests |
| --- | --- |
| lifecycle/dry-run/apply | `GraphicalMatrixSpManagementToolTest` |
| metadata parsing and source controls | `GraphicalMatrixSpMetadataTest` |
| registry schema and uniqueness | `GraphicalMatrixSpRegistryTest` |
| atomic file handling | `GraphicalMatrixSpFilesTest` |
| managed MFA rendering/drift | `GraphicalMatrixSpMfaConfigTest` |
| attribute catalog/discovery | `GraphicalMatrixAttributeCatalogTest`, `GraphicalMatrixAttributeDiscoveryTest` |
| access policy/evaluation | `GraphicalMatrixSpAccessPolicyTest`, `GraphicalMatrixSpAccessPolicyStoreTest` |
| ContextCheck XML | `GraphicalMatrixSpAccessXmlConfigTest` |

SP管理変更ではunit testに加え、`scripts/tests/package-regression.sh`で配布物にCLI、依存JAR、
template、documentationが含まれることを確認します。

