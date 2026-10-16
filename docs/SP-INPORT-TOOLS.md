# SP Import Tools [beta]操作マニュアル

## 0. このツールはβ版です。

サンプル数が極端に少ないので、ベータ版としてリリースしています。

## 1. 目的と配置

一般的なShibboleth IdP、または既存2FAS-KW IdPのSP登録情報を解析し、別の2FAS-KW導入済みIdPへ移行するための
一度限りの支援ツールである。ファイル名は`SP-INPORT-TOOLS.md`、製品・コマンド名は
`SP Import Tools` / `graphicalmatrix-sp-import.sh`とする。

元IdPのXML・設定・metadataは読み取り専用である。新IdPへの書込は既存の
`graphicalmatrix-sp.sh`だけで行い、registry、属性filter、MFA方針を直接編集しない。
DB、LDAP、TOTP、WebAuthn、認証フロー、元IdPの秘密鍵は移行しない。

固定名`2faskw-sp-import-tools.zip`とversion付きZIPは同じ内容である。
署名・checksumを信頼したrelease公開鍵で検証してからホームへ展開する。
Shibbolethの`plugin.sh`でインストールする製品ではない。

```text
~/2faskw-sp-import-tools-1.4.0/
  graphicalmatrix-sp-import.sh
  lib/2faskw-sp-import-tools-1.4.0.jar
  lib/jackson-core-2.21.7.jar
  README.md
  package-metadata/
  sp-migration/                  # 初回解析時に生成。ZIPには含めない
```

入口scriptの実体directoryが既定保存先の基準である。別のcurrent directoryやsudoで起動しても、
既定はtool配下の`sp-migration/`となる。入口がsymlinkの場合もリンク先の実体directoryを使う。
パッケージdirectoryを削除すると結果も消えるため、更新・削除前に`sp-migration/`をbackupする。

## 2. 前提

- Bash、Java 21以上。利用時のPython、Maven、JDK/compilerは不要。
- bundleをZIPで移送する場合だけ、元hostに`zip`、新hostに`unzip`が必要。
- POSIX filesystemを使用する。管理者権限による出力・適用はSecureDirectoryStream対応のLinux filesystemが必要。
- macOS等でこのAPIがない場合は、権限昇格しない解析・dry-runのみを使用できる。
- 元IdP設定を読み取れる権限。権限を緩める処理はしない。
- 移行先に2FAS-KW 1.3.5または1.4.0のSP管理CLIが導入済みであること。
- 新IdPのSP管理を有効化・初期化し、必要な反映・再起動を完了していること。
- ACS host許可、必要な属性resolver/mapping、release承認が準備されていること。
- 適用時はsudo/rootと対話端末を使用する。非対話applyは許可しない。
- 移行中は新IdPの別のSP・属性・MFA管理変更を行わない。既存CLIとの共通ロックはない。

CLI対応版は出力形式の連携対象を意味し、その版の全構成・実SSOを検証済みという意味ではない。
未対応版、読めないversion情報、未知のCLI出力は適用を止める。
旧IdPが古いJavaの場合はtoolだけ別のJavaで起動するか、snapshotを別hostへコピーして解析する。
IdP serviceが利用するJavaを変更する必要はない。

```bash
# ツールに使用するJavaを指定する。IdP serviceのJava設定には影響しない。
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk

# 展開する。署名・checksum検証は展開前に完了しておく。
unzip "$HOME/install-media/2faskw-sp-import-tools.zip" -d "$HOME"

# 配布ツールのversionを確認する。
~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh --version
```

### 専用Java 21を別ディレクトリへ導入する（Linux）

標準の`java -version`が11などの場合でも、既存IdPのJavaを変更する必要はありません。
SP Import Toolsだけに別のJava 21を指定します。
`alternatives`、`/usr/bin/java`、systemdのIdP設定、全体の`JAVA_HOME`/`PATH`は変更しません。

すでに`/opt/2faskw-import-java21-complete/bin/java -version`が成功している場合は、
再インストールせず「専用Javaでツールを実行する」へ進んでください。

以下はLinuxのglibc版Amazon Corretto 21を、管理者所有の専用ディレクトリへ展開する例です。
`x86_64`は`x64`、`aarch64`は`aarch64`の配布物を使います。
古いOSでは最新版JDKの対応OS・必要ライブラリも事前確認してください。
この手順だけで古いOS全体のサポートを保証するものではありません。

[Amazon Corretto 21公式配布一覧](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html)
のHTTPS配布物とSHA-256を使用します。最新版URLは更新されるため、
取得の途中で版が変わりhashが一致しなければ停止します。再取得して両方を照合してください。
版を固定する場合は、公式のversion別配布物と対応するchecksumを選んでください。
組織で署名検証が必要な場合は、公式の署名・公開鍵も確認してから展開してください。

`curl`、`tar`、`gzip`、`sha256sum`とsudo権限が必要です。
下記のまとまりをBashで実行してください。途中で失敗した場合は後続の解析へ進みません。

```bash
(
  set -euo pipefail

  MEDIA="$HOME/install-media"
  JAVA_DIR='/opt/2faskw-import-java21-complete'

  case "$(uname -m)" in
    x86_64) ARCH='x64' ;;
    aarch64) ARCH='aarch64' ;;
    *) echo 'ERROR: unsupported architecture; select a supported JDK manually' >&2; exit 1 ;;
  esac

  mkdir -p "$MEDIA"
  FILE="amazon-corretto-21-${ARCH}-linux-jdk.tar.gz"

  # 標準Javaや、過去の不完全な展開先を上書きしない。
  if sudo test -e "$JAVA_DIR" || sudo test -L "$JAVA_DIR"; then
    echo "ERROR: Java directory already exists; verify it or select a NEW JAVA_DIR: $JAVA_DIR" >&2
    exit 1
  fi

  # 公式HTTPSから配布物とchecksumを取得する。-kは使用しない。
  curl --proto '=https' --tlsv1.2 -fSL \
    "https://corretto.aws/downloads/latest/${FILE}" \
    -o "$MEDIA/$FILE"
  curl --proto '=https' --tlsv1.2 -fSL \
    "https://corretto.aws/downloads/latest_sha256/${FILE}" \
    -o "$MEDIA/$FILE.sha256"

  EXPECTED="$(tr -d '[:space:]' < "$MEDIA/$FILE.sha256")"
  [[ "$EXPECTED" =~ ^[[:xdigit:]]{64}$ ]] || {
    echo 'ERROR: invalid SHA-256 response' >&2
    exit 1
  }
  ACTUAL="$(sha256sum "$MEDIA/$FILE" | awk '{print $1}')"
  [[ "${ACTUAL,,}" == "${EXPECTED,,}" ]] || {
    echo 'ERROR: JDK SHA-256 mismatch; do not extract' >&2
    exit 1
  }

  # binだけをコピーせず、検証済みJDK全体を展開する。
  sudo mkdir -m 0755 "$JAVA_DIR"
  sudo sh -c 'umask 022; exec tar --no-same-owner --no-same-permissions -xzf "$1" -C "$2" --strip-components=1' \
    sh "$MEDIA/$FILE" "$JAVA_DIR"

  # 公開配布JDKの専用ディレクトリだけを、一般ユーザーから読み取り・実行可能にする。
  sudo chmod -R a+rX "$JAVA_DIR"
  test -x "$JAVA_DIR/bin/java"
  test -r "$JAVA_DIR/lib/libjli.so"
  test -r "$JAVA_DIR/lib/server/libjvm.so"
  "$JAVA_DIR/bin/java" -version
  sudo "$JAVA_DIR/bin/java" -version
)
```

末尾の2回のversion確認が成功してから次へ進んでください。
この展開先を別名に変えた場合は、以後の`JAVA=...`も同じpathへ変更します。
既存ディレクトリで停止した場合は自動削除・上書きを行わず、その中身を確認するか、
未使用の`JAVA_DIR`へ完全なJDKを展開してください。展開失敗後の再実行も同様です。

### 専用Javaでツールを実行する

`sudo`は環境変数を引き継がない場合があるため、`export JAVA_HOME`だけに依存せず、
各起動で`sudo env JAVA=...`を明示します。この指定はそのコマンドだけに適用します。
以後のツール起動例にも同じ`env JAVA=...`を使用してください。
移行先もJava 21が必要で、専用pathはそのhostの実際の導入先に合わせます。

```bash
cd "$HOME/2faskw-sp-import-tools-1.4.0"

sudo env JAVA=/opt/2faskw-import-java21-complete/bin/java \
  ./graphicalmatrix-sp-import.sh --version

BUNDLE="$PWD/sp-migration-$(date +%Y%m%d-%H%M%S)"

sudo env JAVA=/opt/2faskw-import-java21-complete/bin/java \
  ./graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --source-type shibboleth \
  --extract-aggregate \
  --export-dir "$BUNDLE" \
  --non-interactive

RC=$?
printf '終了コード=%s\n保存先=%s\n' "$RC" "$BUNDLE"
```

上記は一般Shibboleth IdPで集合メタデータを解析する例です。
2FAS-KW管理設定も移行する場合は`--source-type 2faskw`とし、
`--extract-aggregate`は指定しません。`JAVA`の指定方法は共通です。

`BUNDLE`は現在のshellで設定されます。以後の転送・読込・レポート確認にも同じ値を使い、
途中で`sp-migration`や古い`sp-migration-recheck`へ戻さないでください。
終了コード`2`の場合は実行環境・引数のエラーです。直前の`ERROR:`を確認し、
manifestのない保存先を空のフォルダで補おうとしないでください。

### Java起動時のエラー

- `libjli.so: cannot open shared object file`：一般ユーザーの`lib/`へのアクセス権限、
  ライブラリの欠落、JDKの不完全なコピーを確認してください。
- `missing 'server' JVM`／`libjvm.so`欠落：`bin/`だけでなくJDK全体が必要です。
  正式な配布物を再取得し、未使用の専用ディレクトリへ完全に展開してください。
- `GLIBC_x.y not found`：JDKとOSの互換性を確認してください。稼働中サーバのglibcを
  手作業で置換せず、対応JDKか別の解析hostを使用します。
- `Java 21 or newer is required`：標準Javaではなく、専用Javaの絶対pathを
  `sudo env JAVA=...`へ指定したことを確認してください。

必要なら`ls -ld`で`JAVA_DIR`、`bin/`、`lib/`の通過権限を、
`ldd`で`bin/java`や`lib/server/libjvm.so`の依存ライブラリを確認します。
IdPのcredentialや設定ファイルへ上記のchmodを適用しないでください。

## 3. 元IdPを解析する

```bash
# Linuxで保護された標準構成を解析する。元IdPへの書込と外部URL取得は行わない。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --non-interactive

# 全体の一覧を確認する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/inventory.txt

# SPごとの具体的な確認項目と保留原因を確認する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/review-checklist.txt

# 設定根拠、属性条件、診断理由を確認する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/analysis.txt

# 必要な手作業と未対応条件を確認する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/manual-actions.txt

# SP登録とは別に、元のアクセス制御・RP設定の根拠を確認する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/access-control.txt
```

サービスresourceの静的list/ref、XML import、providerの参照をたどる。
管理registryがあると2FAS-KWモードになる。macOS等でSecureDirectoryStreamが使えない場合は、
読取権限のあるsnapshotを用意し、sudoなしで解析する。元設定の権限を緩めない。
directory内の全XMLを登録済みと扱わず、backupや未参照XMLは自動解析対象にしない。
標準resource beanが取得できない場合は標準名のXMLを参考解析し、fallbackだったことを表示する。
これは稼働中IdPの実効設定が確定したことを意味しない。管理者が出自・読込設定を確認する。

### 一覧と確認項目の読み方

一覧は検出数、確認候補数、保留数、アクセス制御未移行数と、原因コード別のSP件数を表示する。
同じSPに複数の原因があるため、原因件数の合計は保留SP数とは一致しない。
各SPには番号・管理名・entityID・元ファイル・アクセス制御状態を表示する。

「確認後に登録可能」は、確認が済んだという意味ではない。一覧と`review-checklist.txt`では
次の共通確認事項を先頭に一度だけ表示し、各SPには元ファイル・送信予定属性・管理意図・
自動更新の変更・解析した利用制限などの固有事項を表示する。両方の確認が必要である。
`d 番号`は、そのSPだけでも判断できるよう共通事項を含む確認手順と技術詳細を表示する。

1. 元ファイルの配信元とentityID、保存原本のpath/hash。hashだけでは真正性を保証しない。
2. 原本署名の信頼鍵/trust engine、親/entityの期限・cacheDurationとfilter順序・role。
   抽出したXMLへ元署名を転用しない。技術詳細に原本・証明書fingerprint・解析期限を残す。
3. 動的/URLメタデータの場合は、更新を自動継承しないため、期限前とSP鍵変更時の更新担当・手順。
4. 実際の送信予定属性一覧、各属性の送信承認、移行先resolver/SAML mappingと対象ユーザーのAACLI結果。
5. 新IdPのdry-runに表示された管理名/profile/MFA/ACS許可host。管理モードでは元のprofile/MFA/CIDRsと全体MFAも照合。
6. 利用制限は`access-control.txt`と詳細を確認し、隔離した新IdPで再設定・許可/拒否試験。
   一般モードでは認可条件はコピーしない。管理モードでは移行先policy照合と試験を行う。
7. 公開前のNameID/暗号化条件、SP側の新IdPへの信頼設定、ブラウザSSO・送信属性・MFA試験。

保留SPは主な原因・設定箇所・対処を先に表示し、派生した抽出エラーなどは関連コードへまとめる。
詳細にはすべての原因と英語の元診断を残す。期限関連コードは不正な日時や未対応cacheDurationも
含むため、期限切れと断定しない。期限を手作業で延長して回避してはならない。

新しく解析した結果では、一覧にも保留の判定根拠を表示する。期限関連の根拠には、
`reason`（例：`VALID_UNTIL_EXPIRED`、`VALID_UNTIL_MALFORMED`、`CACHE_DURATION_UNSUPPORTED`）、
実際の`validUntil`/`cacheDuration`、UTCの`checked_at`、該当するentity/親groupとファイル位置を記録する。
`RequiredValidUntil`の拒否にはfilter位置・`maxValidityInterval`、許容上限超過なら`allowed_until`も記録する。
期限の欠落、不正な日時、期限切れ、許容期間超過、未対応の期間値を区別し、元の検証を無効化して回避しない。
`analysis.txt`には抽出失敗時もentityと各親groupの期限・期間値と位置を残す。

属性送信条件の保留にはpolicy名・属性ID・条件の種類/入れ子/値/参照を記録する。
条件は実行せず、スクリプト本文・式の本文・未知の属性値は出力しない。
長大な条件は`truncated; inspect source XML`と表示するため、該当XMLを管理者が確認する。
未知の条件を`ANY`へ置き換えたり、deny条件を削除したりして登録を進めてはならない。
旧bundleにない診断値を読込時に推測・補完しない。詳しい根拠が必要なら、元IdPを新しい保存先へ再解析する。

移行先の対話実行では`held`（保留）、`ready`（確認候補）、`all`（全件）、`d 番号`（詳細）を使用できる。
表示を絞っても元の番号を維持し、`--entity-id`の選択制約やHELD判定は変わらない。
全件保留でも詳細確認と終了は可能で、登録はできない。非対話時は操作入力を受け付けない。
`--lang en`で英語ガイダンスに切り替える。原本の診断コード・根拠は変更しない。
ガイダンスは`review-checklist.txt`にも保存するが、確認済みの証拠・承認ファイルではない。
登録前の原本確認・設定照合・認可移行計画と、登録後に隔離を維持した状態で行う公開前の
許可/拒否・SSO試験を区別する。未署名原本は元の許容条件と別経路での真正性確認が必要である。
旧bundleを読む場合も一覧は再生成する。新しい確認TXTがないだけで旧bundleを拒否しない。

### 原因の分類とSP別の移行作業

一覧の「作業分類」は、原因コードだけでは分かりにくい次の作業を分類する。
各SPに対象ファイル位置・診断コード・`reason`と作業順を表示し、
`d 番号`と`migration-actions.txt`にはpolicy・属性ID・条件値などの根拠と完了確認も示す。

```bash
less "$BUNDLE/migration-actions.txt"
```

| 分類 | 対応 |
|---|---|
| 元ファイル・設定要確認 | 属性ID欠落・空白・ツールで扱えない形式、参照先等を元resolver/設定と照合。意図確認・バックアップ後に修正して再解析。 |
| 元メタデータ更新 | 実際の期限切れ。時計・元providerの更新失敗を確認し、信頼する最新版の署名/期限を検証して再解析。 |
| 元メタデータ・filter要確認 | 欠落・不正日時・許容上限超過等を実値と元filter/trust設定で確認。検証を無効化しない。 |
| 属性送信条件の手動移行 | Value/Regex/deny等の条件を新IdPで同等設定する個別移行を計画。全値送信profileへ自動変換しない。 |
| 追加調査・個別判断 | 独自条件・ref・script・未対応設定等を元IdP担当者と調査。設定ミスと断定せず、実行・推測・ANY置換しない。 |
| 抽出・配布方式要確認 | 他のmetadata保留を先に解消。抽出が引き続き困難なら正式単体metadataや別管理providerを検討。 |
| 管理設定の整合性確認 | 元registryとprofile/MFA/accessの差分を既存CLIで照合し、承認した意図へ整合させる。 |
| アクセス制御の別途移行・確認 | SP固有の認可条件を再設定し、隔離した移行先で許可・拒否を試験。 |
| 移行先照合・公開前試験 | 前工程の後にdry-runの属性/MFA/ACS、SP信頼・NameID/暗号化・実SSOを試験。承認まで隔離。 |

`ATTRIBUTE_CONDITION_UNSUPPORTED`は維持し、`reason`で属性ID欠落・空白・形式、
既知の値限定、deny、追加調査が必要な条件を区別する。例えば`attributeID="uid "`は
末尾空白を可視化するが、`uid`へ自動変更しない。元resolverの正式IDと管理意図を確認する。
値限定を識別できても条件の正しさや実行時の同等性を保証しない。

値限定/denyの手動移行では、表示された対象entityIDとpolicy・属性ID・条件値を基に、
新IdPの`conf/attribute-filter.xml`または設定されたresourceへ同等条件を設計する。
共通policy・管理profileとの組合せで送信範囲が広がらないことを確認する。
filtered AACLIとSP受信属性で一致値・不一致値・空値・複数値混在を試験し、
必要な値だけが送信されることを確認する。ログインの許可/拒否試験とは区別する。

本ツールでは手動条件を保留したままとする。移行先への手動設定だけでは解除しない。
必要なら信頼済み単体metadata・適切なprofile・手動filterを用いる既存CLIの別手順を計画し、
元条件を削除したり`ANY`/`permitAny=true`へ置換して解析を通過させない。
共通の親metadataを更新しても、値限定や独自条件等の他の保留は残る場合がある。

分類件数は同じSP内で重複を除き、分類間は重複する。作業順は説明であって自動実行計画・
承認・検証結果ではなく、選択可否・隔離・終了コードを変えない。
`migration-actions.txt`は任意資料であり、なくても旧bundleを読める。TXT編集で保留は解除できない。
旧診断に理由がない場合は追加調査とし、必要なら新版で元IdPを未使用の保存先へ再解析する。

既定保存先が既に存在する場合は上書きしない。継続は`--bundle-dir`、再解析は新しい
`--export-dir`を指定する。以前のmetadataと新しい結果を混在させない。

```bash
# 修正済み設定を別directoryへ再解析する。前回結果は保持する。
~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --export-dir "$HOME/2faskw-sp-import-tools-1.4.0/sp-migration-recheck" \
  --analysis-only
```

## 4. 非標準パス・snapshot

```bash
# 別場所のprovider設定を追加候補として解析する。
~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --metadata-config /srv/saml/providers.xml

# 単一SP metadataを明示する。これだけで元IdP登録済みと断定しない。
~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --metadata-file /srv/saml/library.xml

# snapshot内の旧絶対pathを明示的に対応付ける。
~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --source-idp-home "$HOME/old-idp-snapshot" \
  --path-map "/srv/old-idp=$HOME/old-idp-snapshot" \
  --property-source "$HOME/old-idp-snapshot/migration.properties"
```

`--metadata-config`は設定XML、`--metadata-file`はSP metadataであり、複数指定できる。
path-mapはpath component境界を比較する。単純な文字列prefix置換ではない。
propertiesはUTF-8のJava properties構文で読み、escape・継続行・`%{property:default}`を扱う。
値が衝突し実効優先順を確定できない場合は保留する。SpEL、bean、scriptは実行しない。
秘密値はレポートへ出力しない。snapshotをコピーする際は秘密鍵やcredentials全体を不用意に移送しない。

## 5. 対応範囲と移行保留

| 元設定 | 初期版の動作 |
|---|---|
| ローカル単一EntityDescriptor | 元bytesを保持して解析・exportする |
| ChainingMetadataProvider | 子providerとfilter順序を解析。対応する検証filterは要確認、未知・加工filterは保留 |
| FileBackedHTTPMetadataProvider | backing fileのみ解析。更新方式の変更を必ず表示 |
| LocalDynamicMetadataProvider | sourceDirectory内の既知fileを解析。snapshot更新運用を確認 |
| DynamicHTTPMetadataProvider / MDQ | 全SP列挙不能を表示。外部URLは取得しない |
| ファイルのaggregate | 既定は一覧解析・保留。一般モードの`--extract-aggregate`で条件付きの未署名snapshotを抽出 |
| InlineMetadataProvider | 一覧解析のみ。独立metadata抽出は未対応で保留 |
| SignatureValidation / RequiredValidUntil | 既知の静的設定を要確認として記録。署名検証の実行・版ごとの既定値推測はしない |
| EntityRole / EntityRoleWhiteList | 明示抽出時のSPSSODescriptorのみ保持する静的設定に限定。その他は保留 |
| 直接Requester / ANY / 単純AND・OR条件、PermitValueRule ANY、子要素なしのpermitAny="true" | 条件を評価し属性集合を候補化 |
| EntityAttributeExactMatch、上記の全値許可条件のAND・OR | 対象SPの静的なentity-levelタグを照合し、全値許可／許可なしを判定 |
| Attribute Filterのdeny、利用者属性の値条件、ref、script、group、未対応tag条件 | 単純profileへ置き換えず登録保留 |
| 一般IdPのRelyingPartyOverrides | literal RelyingPartyByNameは対象SPへ限定して別途報告。未知selectorはアクセス制御未解析 |
| 一般IdPのContextCheck | 対応するAND/OR/NOT・属性map・regexを静的に記述。自動適用はしない |
| 独自provider、動的resource、外部schema等 | 未解決理由と手作業を表示 |

元IdPの登録・設定と新IdPの登録は別である。SHA-256はbytesの照合であり、署名検証・真正性保証ではない。
新IdPへsnapshot登録しても、元のURLからmetadataが自動更新されるわけではない。
元filter・RP設定の必要な条件が未解決の場合、単独metadataを渡すだけでその条件を消してはならない。

### 5.1 SP登録とアクセス制御を別に扱う

一般IdPモードでは、一覧・詳細に`registration_state`（登録判定）と
`access_control_state`（アクセス制御の調査状態）を別々に出力する。
一覧の`registration=REVIEW`は登録候補、`registration=HELD`は選択不可である。
アクセス制御の未解析だけで無関係なSPも登録保留にする従来の一括判定は行わない。
ただし、アクセス制御を未移行のまま本番へ公開してよいという意味ではない。

| access_control_state | 意味 |
|---|---|
| NONE_OBSERVED | 対応する静的解析で対象依存を検出しなかった。稼働中に制限がない保証ではない |
| MANUAL_REQUIRED | 対象SPのRP設定・ContextCheck等を検出した。別途再現と試験が必要 |
| UNRESOLVED | selector、Function、ref、動的式、設定欠落等のため対象条件を確定できない |
| LEGACY_UNASSESSED | 旧一般bundle v1。アクセス制御の根拠がないため再解析を推奨 |

`access-control.txt`はSP別の根拠・位置・条件式を記録する。
literalな`RelyingPartyByName`で一致しないSPのoverrideは除外するが、動的selectorやrefは推測しない。
標準の`conf/intercept/context-check-intercept-config.xml`を必要時に読み、
自己完結した非標準snapshotは解析時に`--context-check-config /srv/saml/context-check.xml`を指定できる。
ContextCheckのAND/OR/NOT、literalなSP selector、SimpleAttributePredicateの属性map、
RegexAttributePredicateを表示し、元のregex、NOT、`useUnfilteredAttributes`を保持する。
未指定の属性参照方式は`UNSPECIFIED`として元IdP版の既定確認を要求する。
script・Spring bean・SpELは実行しない。importや未知の構造は未解析として表示する。
この記述はCLIのallow/denyへの自動変換ではない。属性欠落時のNOT判定、scoped値、wildcard等の
意味を維持する必要があるため、単純なdenyやメールドメイン条件へ置き換えない。

元の認可条件だけでなく、NameID・暗号化・認証・独自intercept等のRP設定も管理者が別途確認する。
明示的なaggregate抽出は後述の第5.3節に限定する。任意のprovider filterの実行、
Attribute Filterの値限定条件や未知resourceによる`HELD`は解除できない。
2FAS-KW管理モードのaccess policy移行・drift検査・隔離要件は従来どおりである。
自動移行できないケースは[INSTALL_NEW_SP.md](./INSTALL_NEW_SP.md)に従って個別に検討する。

### 5.2 IdP標準の属性フィルタを解析する

子要素のない`<AttributeRule attributeID="schacHomeOrganization" permitAny="true"/>`は、
`PermitValueRule ANY`と同じ全値許可として解析する。XML booleanの`1`も対応する。
`permitAny="true"`と子ルールの併記、不正なboolean、denyとの組合せは保留する。

`EntityAttributeExactMatch`はLDAP属性ではなく、対象SP metadataの
`EntityDescriptor/Extensions/mdattr:EntityAttributes/saml:Attribute`にあるタグ条件である。
`attributeName`、`attributeNameFormat`、文字列の`attributeValue`が一致するか確認する。
`PolicyRequirementRule`と`PermitValueRule`の両方、およびそのAND・ORに対応する。
例えば`releaseAllValues`タグによる`mail`送信や、subject-id要求タグの
`pairwise-id OR any`、`subject-id`条件を解析できる。
[`EntityAttributeExactMatch`公式仕様](https://shibboleth.atlassian.net/wiki/spaces/IDP5/pages/3199502013/EntityAttributeExactMatchConfiguration)を参照。

対応は`attributeNameFormat`を明示し、`ignoreUnmappedEntityAttributes`が未指定または`false`/`0`の
静的XML照合に限定する。NameFormat省略時のAttributeRegistryによる変換、mapped-only指定、
動的式、metadata内Assertion、複合値・nil・文字列以外の型は保留する。
タグの追加・削除・変換を行う未知の`MetadataFilter`は実効値を確定できず保留する。
署名・有効期限の既知検証filterはタグを変更するものではないが、独立した確認を必須とする。
aggregateの親Extensionsは推測・移植せず保留する。条件付き抽出は第5.3節を参照する。
SP role配下のタグや`RequestedAttribute`をentity-levelの送信承認として扱わない。

条件が一致すれば全値許可、不一致なら送信候補へ追加しない。判定不能を無条件許可にはしない。
判定根拠はSP詳細TXTの`entity_attribute_match=YES/NO/UNKNOWN`と、UNKNOWNの場合の理由・位置へ記録する。
移行後は静的snapshotとなるため、将来のタグ変更に伴う送信方針の再確認も必要である。

修正版で再解析する場合、既存bundleは上書きしない。新しい出力先を指定する。

```bash
# 元IdPの設定を読み取り専用で再解析し、以前とは別のbundleへ保存する。
cd "$HOME/2faskw-sp-import-tools-1.4.0"
BUNDLE="$PWD/sp-migration-recheck"
sudo ./graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --export-dir "$BUNDLE" \
  --non-interactive
```

出力先が既に存在する場合は別名にする。`--bundle-dir`には解析に成功して生成されたbundleを指定し、
古いHELDのbundleを指定して修正版の再解析が済んだと考えない。

### 5.3 一般IdPの集合メタデータを明示的に抽出する

`--extract-aggregate`は一般Shibbolethモードの解析時だけ使用できる。
2FAS-KW管理モードには使用できず、そのpolicy・drift検査は変更しない。

```bash
cd "$HOME/2faskw-sp-import-tools-1.4.0"
BUNDLE="$PWD/sp-migration-recheck"
sudo env JAVA=/opt/2faskw-import-java21-complete/bin/java \
  ./graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --source-type shibboleth \
  --extract-aggregate \
  --export-dir "$BUNDLE" \
  --non-interactive
```

`JAVA`はツール専用Javaを導入した場合の例であり、Java 21が既定なら`env JAVA=...`は不要。
既存出力は上書きしない。解析後は`inventory.txt`、`analysis.txt`、`manual-actions.txt`、
`access-control.txt`、`review-checklist.txt`、`migration-actions.txt`を確認し、bundle全体を転送する。

- 継承namespace、XML言語指定、親group名の根拠を保持する。
- entityと親groupの`validUntil`は最も早い期限を採用する。`cacheDuration`は最小の固定durationを
  引き継ぎ、解析時刻+durationでもsnapshot期限を制限する。月・年等の可変durationは保留する。
- 元XMLは`evidence/metadata-sources/SHA256.xml`へ改変せず一度だけ保存する。
  manifestで原本hash、抽出候補との参照、entityIDを照合する。原本なし・改変・参照不整合では停止する。
- 抽出snapshotへ元の署名を転用しない。原本の署名、真正性、group条件、filter順序は独立して確認する。
- 期限切れ、親Extensions、`xml:base`、未知の親属性/要素、加工filterは保留を維持する。
- 既知のSP-only role whitelistはsnapshotの他roleを削除できるが、未知のrole指定やfilter設定は保留する。

`SignatureValidation`は検証済みという意味ではなく`METADATA_SIGNATURE_VALIDATION_REQUIRED`になる。
署名必須が明示されているのにroot署名がない場合や、`RequiredValidUntil`の欠落・期限切れ・
明示window超過は保留する。設定を削除して回避しない。
省略された有効期間の既定はIdP版によって異なるため、元環境で確認する。
公式仕様は[SignatureValidationを含むprovider解説](https://shibboleth.atlassian.net/wiki/spaces/SHIB2/pages/2577072382)、
[RequiredValidUntil](https://shibboleth.atlassian.net/wiki/pages/viewpage.action?pageId=1299611686)、
[EntityRole](https://shibboleth.atlassian.net/wiki/spaces/IDP5/pages/3199507218/EntityRoleFilter)を参照する。

一般bundle v4は原本のRレコードと候補参照Xレコードを含む。旧一般v1/v3と管理v2は読込可能だが、
古いHELD結果を読むだけでは新しい抽出は行われない。元/新host双方で同じ修正版を使用して再解析する。

### 5.4 標準IdPの内部beanとアクセス制御

標準`system/conf/relying-party-system.xml`、`relying-party-mddriven.xml`、
`metadata-providers-system.xml`にあるframeworkクラスは、resource単位の
`standard_framework_review`へ集約する。これは標準ファイルの真正性や設定未変更の保証ではない。
実行時・metadata-driven既定値の確認は`MANUAL_REQUIRED`として残し、「制限なし」とはしない。
単純なString定数やX509 credential定義は認可predicateと区別する。
独自class、偽の同名path、factory/動的String定義、未知selector/ref/Functionは`UNRESOLVED`のままである。
実際のSP別ContextCheck、属性map、regex、NOTと参照方式の根拠は引き続き記録する。

## 6. 新IdPで登録する

以下は一般IdPモードの手順である。2FAS-KWモードのbundleでは第10節の隔離確認と
アクセス制限を含む手順を使用する。解析時に`--source-type shibboleth`を指定すると
元の管理名・SP別MFA・アクセス制限を移行しないため、管理情報の欠落を理解せず指定してはならない。

元hostで生成したbundleだけを新hostへ移送する。元IdPの署名秘密鍵やDBは移送しない。
新hostでは同じ修正版toolもホームへ展開し、移送後のbundleをtool配下へ配置する。
既定名は`sp-migration/`だが、`--export-dir`で変更した場合は実際に生成された名前を使う。
転送完了前のbundleを読み始めない。移送元の信頼とSHA-256を別途確認する。

### 6.1 bundle全体を転送する

ディレクトリの直接SCP、またはZIP・SCP・展開のどちらか一方を使う。
配布ツールのZIPと解析結果のZIPは別である。配布ZIPだけを転送しても、解析bundleは移送されない。
`manifest.tsv`、TXT、metadata、evidence、`runs/`を含むディレクトリ全体を移す。
XML単体や空ディレクトリでは移行できない。元IdPへ戻して適用する手順ではない。
転送先に同名ZIP/bundleがある場合は、別名を選び、既存の結果や実行履歴へ上書き・混在させない。

標準手順の保存先は`BUNDLE="$PWD/sp-migration"`、転送ZIPは`sp-migration.zip`に統一する。
再解析で`sp-migration-recheck`等を指定した場合だけ、元/新hostのすべてのbundle/ZIPパスを
その実際の名前へ読み替える。既存の解析結果の名称を自動変更するものではない。
SSH利用者・host名は新IdPのものへ置き換える。

**方法A: ディレクトリを直接SCPする**

```bash
# 元hostで実行する。新hostのtoolは展開済み、同名bundleがないことを先に確認する。
cd "$HOME/2faskw-sp-import-tools-1.4.0"
BUNDLE="$PWD/sp-migration"
TARGET='operator@new-idp.example.org'
scp -pr "$BUNDLE" "${TARGET}:2faskw-sp-import-tools-1.4.0/"
```

**方法B: ZIPにしてSCPする**

```bash
# 元hostで実行する。ZIPをユーザーホームに作成し、新IdPのホームへ送る。
(
  set -euo pipefail
  cd "$HOME/2faskw-sp-import-tools-1.4.0"
  BUNDLE="$PWD/sp-migration"
  ARCHIVE="$HOME/sp-migration.zip"
  TARGET='operator@new-idp.example.org'

  test -f "$BUNDLE/manifest.tsv"
  if [[ -e "$ARCHIVE" || -L "$ARCHIVE" ]]; then
    echo "ERROR: archive already exists; choose a new ZIP filename: $ARCHIVE" >&2
    exit 1
  fi
  umask 077
  zip -r "$ARCHIVE" sp-migration
  scp -p "$ARCHIVE" "${TARGET}:"
)
```

新hostに同名ZIPがないことも転送前に確認する。再転送時はZIP名を両hostで読み替える。
展開は新hostの管理者ユーザーで実施し、`sudo unzip`は使用しない。

```bash
# 新hostで実行する。元hostと同じbundle/ZIP名を指定する。
(
  set -euo pipefail
  cd "$HOME/2faskw-sp-import-tools-1.4.0"
  BUNDLE="$PWD/sp-migration"
  ARCHIVE="$HOME/sp-migration.zip"

  if [[ -e "$BUNDLE" || -L "$BUNDLE" ]]; then
    echo "ERROR: bundle already exists; do not merge or overwrite: $BUNDLE" >&2
    exit 1
  fi
  chmod 0600 "$ARCHIVE"
  unzip -t "$ARCHIVE"
  umask 077
  unzip -n "$ARCHIVE"
  test -f "$BUNDLE/manifest.tsv"
  ls -l "$BUNDLE/manifest.tsv"
)
```

ZIPには属性・access policyの値等を含む。通常のZIPは暗号化されないため、
信頼したSSH接続で転送し、両hostで非公開・0600として保管する。
展開されたbundleの0700/0600を緩めない。`unzip -t`はarchiveの破損検査であり、
metadataの署名・真正性や元IdPの稼働状態を保証しない。

### 6.2 新IdPで事前確認して登録する

一般IdPから「まずSP登録、属性送信と認可は後で設定する」場合は、次の第6.3節を使用する。
以下は属性送信も同時に移す従来の方式であり、明示的な`--defer-attributes`指定なしに
属性条件の保留をnoneへ置き換えて進めることはない。

転送または展開が完了してから、新hostで実行する。以下の変数は新hostで改めて設定する。
前述の括弧内で設定した変数は外側に引き継がれない。

```bash
cd "$HOME/2faskw-sp-import-tools-1.4.0"
BUNDLE="$PWD/sp-migration"
ls -l "$BUNDLE/manifest.tsv"

# 新IdPの初期化済み状態・既存SP・属性profile・MFAを確認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh status

# 新hostで対話選択とCLI dry-runを行う。DBや新IdP設定へ書き込まない。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --bundle-dir "$BUNDLE" \
  --target-idp-home /opt/shibboleth-idp

# アクセス制御に未移行項目がない候補だけを、確認後1件ずつ適用する。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --bundle-dir "$BUNDLE" \
  --target-idp-home /opt/shibboleth-idp \
  --apply
```

番号選択、`d 番号`での詳細表示、`q`での取消に対応する。EOF・空Enterは登録承認としない。
各SPについて`REVIEW SOURCE`、管理名、属性profile、MFAを確認し、最後に`REGISTER 件数`を入力する。
抽出snapshot・検証filterの要確認候補には、独立した原本確認後の`--confirm-metadata-review`と
`VERIFY SOURCE METADATA`対話確認が追加で必要である。フラグは署名検証を代行しない。
未移行認可がある場合は以下の登録限定・隔離確認も必要であり、metadata確認だけでは進めない。
MFAのbypass/CIDR例外は追加確認が必要である。既存MFAの既定・全体方針は変更しない。

一般IdPモードでアクセス制御が未移行の候補を選ぶ場合、通常の`--apply`は書込前に停止する。
登録だけを先に進める必要がある場合は、新IdPを管理者のネットワーク制御で隔離してから実行する。

```bash
# 元の認可条件は移行しない。新IdPの隔離を先に実施してから登録だけを行う。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --bundle-dir "$BUNDLE" \
  --target-idp-home /opt/shibboleth-idp \
  --registration-only \
  --confirm-metadata-review \
  --confirm-isolated-target \
  --apply
```

追加で`REGISTER ONLY ISOLATED`を入力する。フラグはfirewall設定を変更せず、申告だけである。
登録後も終了コード10・`REGISTERED_ACCESS_PENDING`となり、アクセス制御の同等性は未確認である。
`access-control.txt`に基づく再構成と許可・拒否利用者のbrowser試験が終わるまで隔離を解除しない。
登録成功後に終了コード0へ変える目的でaddを繰り返さない。`verify`は認可の検証ではない。
管理モードのbundleへ`--registration-only`を指定すると拒否する。

元の送信属性集合と一致する既存profileを再利用できる。profileがなければ管理者が
classificationとpurposeを明示し、既存CLIで属性承認・profile作成を行う。
resolver不足、mapping不足、blocked属性はツールでは解消しない。
未作成profileに依存するdry-runでは、必要な前提作業を表示して止める。代わりにnoneを渡して成功扱いにしない。

適用用metadataはprivateな一時領域へ固定し、既存addのdigestと一致を確認してから適用する。
途中で失敗すると残作業を止め、成功した承認/profile/SPは保持する。
登録成功後は既存`verify`を行うが、SP側の信頼切替やbrowser SSOは別の確認である。

### 6.3 推奨：SP登録と属性送信設定を分ける（一般IdPモード）

一般Shibboleth bundleを対象に`--defer-attributes`を指定すると、SP管理名・entityID・
metadata・SP別MFA/CIDRsだけを既存CLIで登録する。attribute profileは`none`に固定し、
属性承認、classification/purposeの入力、profile作成、元の認可条件の適用を行わない。
resolver/mappingはこの登録段階では要求しない。後の属性設定段階で準備・確認する。

```bash
cd "$HOME/2faskw-sp-import-tools-1.4.0"
BUNDLE="$PWD/sp-migration"

# 属性は後で設定する前提で、登録予定を確認する。書込はしない。
sudo ./graphicalmatrix-sp-import.sh \
  --bundle-dir "$BUNDLE" \
  --target-idp-home /opt/shibboleth-idp \
  --defer-attributes

# 新IdPのネットワーク隔離・バックアップを別途行った後だけ実行する。
sudo ./graphicalmatrix-sp-import.sh \
  --bundle-dir "$BUNDLE" \
  --target-idp-home /opt/shibboleth-idp \
  --defer-attributes \
  --registration-only \
  --confirm-isolated-target \
  --apply
```

抽出snapshot・検証filterの確認が必要な候補には、原本を独立に確認した後で
`--confirm-metadata-review`を追加する。通常の`REVIEW SOURCE`、管理名、MFA、
`REGISTER 件数`の確認に加え、`DEFER ATTRIBUTES ISOLATED`を入力する。
元の認可条件も未移行なら`REGISTER ONLY ISOLATED`も必要である。
これらは確認の申告であり、firewallを変更せず、metadata署名も検証しない。

このモードではSP個別の`ATTRIBUTE_CONDITION_UNSUPPORTED`と`ATTRIBUTE_POLICY_UNRESOLVED`
だけを登録時のREVIEWへ変更する。元bundleのHELD・診断・原本は書き換えず、
条件を解決済み、または全値送信可能と判断しない。全体設定のエラー、metadata不備、期限切れ、
未知provider/filter等の他の保留は維持する。2FAS-KW管理bundleでは使用できない。

終了コードはdry-run・登録後とも`11`で、属性設定が未完了という意味である。
登録済みの場合は`REGISTERED_ATTRIBUTES_PENDING`、再開で照合済みなら
`VERIFIED_EXISTING_ATTRIBUTES_PENDING`を記録する。成功を0へ変える目的で再登録しない。
`runs/RUN_ID/attribute-migration-todo.txt`にSP名、元属性集合、条件の診断・根拠、
必要な後工程を残す。不明なpolicyがあれば、表示属性だけで全属性を把握したと扱わない。

**重要:** `none`は管理profileからの追加送信がないという意味であり、既存の共有
`attribute-filter.xml` policyによる送信を禁止しない。認可も自動再現しない。
後工程と検証が完了するまで移行先を公開せず、ネットワーク隔離を維持する。

後工程は既存SP管理CLIで行う。例えば、元でmailの全値送信が許可されているSPについて、
target resolver/mappingと共有policyを確認してから以下を実行する。NAMEはrunの実管理名へ置き換える。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes discover
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes show mail

# 既存承認を確認し、必要な場合だけ、管理者が決めた分類・用途で承認する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes approve mail \
  --usage release --classification personal --purpose 'Reviewed SP mail release'
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes approve mail \
  --usage release --classification personal --purpose 'Reviewed SP mail release' \
  --apply --confirm mail

# 既存profileが同じ意図なら再利用する。作成する場合は事前確認後に適用する。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile create migrated-mail \
  --attributes mail --description 'Reviewed mail release'
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh attributes profile create migrated-mail \
  --attributes mail --description 'Reviewed mail release' --apply --confirm migrated-mail
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-attributes NAME migrated-mail
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh set-attributes NAME migrated-mail --apply
```

元が値限定・deny・複合条件ならこの全値送信例を使用しない。条件を別途同等に再構成し、
filtered AACLIとSP受信属性について一致・不一致・属性なし・複数値混在を確認する。
`attributes discover --sp NAME --user USER`は解決属性の確認であり、送信filter検証の代わりではない。
認可の許可/拒否、NameID、暗号化、新IdP署名鍵のSP側信頼、実SSO、MFAも確認してから隔離を解除する。

期限は解析時だけでなく、移行先の準備開始、対話確認後の書込直前、各SPの登録直前にも再確認する。
`SOURCE_METADATA_EXPIRED`なら信頼できる最新原本を取得して新bundleを生成する。
期限を手で延長して回避しない。元のHTTP自動更新URLは引き継がれず、静的snapshotは自動更新されない。
`validUntil`未指定なら新期限を創作しないため、別途snapshot更新運用を決める。

## 7. 結果と再実行

```text
sp-migration/runs/<run-id>/
  target-check.txt
  plan.tsv
  commands.txt
  cli-output.txt
  execution-state.tsv
  result.txt
  access-control-status.txt
  attribute-migration-todo.txt  # --defer-attributes時だけ
  next-steps.txt
```

TXTが閲覧用であり、TSVは内部照合用である。手編集して計画を変える方式ではない。
ディレクトリは0700、ファイルは0600を基本とする。sudoで生成した利用者向け結果は
SUDO_USERへ所有者を戻す。rootで直接実行した場合はroot所有になる。

```bash
# 失敗したrunの理由と成功済み操作を確認する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/runs/RUN_ID/result.txt

# 計画とtarget実状態を再確認する。選択・管理名・profile・MFA・CIDRsを前回と同じにする。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --bundle-dir "$HOME/2faskw-sp-import-tools-1.4.0/sp-migration" \
  --target-idp-home /opt/shibboleth-idp \
  --resume RUN_ID \
  --apply
```

再実行は前回計画と同じ入力か照合し、存在するSPをverifyしてから残作業へ進む。
hash、entityID、属性profile、MFA/CIDRs、ACTIVE状態が一致しないものを無確認でupdateしない。
`mfa show`のmanaged policy driftも確認し、不一致では処理を止める。
成功済みSPの削除や全体rollbackを自動実行しない。target側に別名・手作業SPがある場合も既存CLIで個別確認する。
承認・profileだけ成功した場合は、残った設定をTXTで確認する。
登録限定runの再開では`--registration-only --confirm-isolated-target`も同じように指定し、
隔離確認を再度行う。新計画は移行先実パスと両フラグも固定する。
metadata確認が必要なrunは`--confirm-metadata-review`も維持し、原本確認を再度行う。
一般plan-v3はこの確認flagも固定する。従来の一般plan-v1/v2からの自動resumeは行わない。
成功済み操作を確認し、管理者が個別に継続を判断する。
複数SPで同じ新規profileを使う場合は、既存CLIで先にprofileを作成してから再利用する。

属性後設定runの再開には`--defer-attributes --registration-only --confirm-isolated-target`を維持する。
plan-v4はこのmode・移行先・選択・name・none・MFA/CIDRs・metadata hashを固定する。
従来plan-v3からmodeを変更してresumeできない。後工程で既にprofileを変更したSPもnoneへ戻さず停止する。

| 終了コード | 意味 |
|---|---|
| 0 | 指定mode完了。解析modeでは登録成功を意味しない |
| 2 | 引数・同一IdP・対話端末等の不備 |
| 3 | 解析で移行保留あり、または登録可能SPなし |
| 4 | 利用者による取消・EOF |
| 5 | 新IdP前提不足、未承認・未作成profile等 |
| 6 | bundle/入力/hash不整合・I/O失敗 |
| 7 | 既存CLI dry-run/apply失敗 |
| 8 | 登録済みSPのverify不一致・失敗 |
| 9 | 選択対象は完了したが他に保留が残る |
| 10 | 登録解析・確認は進んだが元のアクセス制御は未移行。全体移行完了ではない |
| 11 | 属性後設定modeの登録確認・登録段階完了。属性送信・認可・切替の完了ではない |

一覧の`REVIEW / 確認が必要`は移行を止めるエラーではなく、管理者が確認して選択できる候補である。
`HELD / 移行保留`は選択・適用できない。ただし第6.3節の明示的な属性後設定modeは、属性個別の保留だけを
未完了の後工程として登録viewのREVIEWへ変更する。解析modeの終了コード`0`は保留なしでbundleを生成したことを
意味し、新IdPへの登録や実SSOはまだ完了していない。
一般モードでは登録保留がなくてもアクセス制御が未移行なら解析終了コード10となる。
登録保留もある場合は3、前提不足・CLI失敗等がある場合は各失敗コードが優先する。
成功済み登録の詳細と未移行認可は`result.txt`・`access-control-status.txt`で確認する。
`METADATA_REFRESH_BEHAVIOR_CHANGED`は元の取得・更新設定をコピーせずsnapshotを移すための注意、
`SOURCE_TRUST_REVIEW_REQUIRED`は移送元の真正性、NameID・暗号化、新IdP証明書のSP側信頼を確認する注意である。

### manifestの容量超過

旧版の`BUNDLE_LIMIT_EXCEEDED: manifest exceeds 4 MiB`は、詳細な解析根拠を含む
内部manifestがツールの容量上限を超えたことを示す。元IdPのXMLが不正という意味ではない。
元IdP・新IdP双方のツールを修正版へ置き換え、解析を再実行する。
修正版は出力・再読込・実行前hash照合のmanifest上限を32 MiBへ統一している。
JSON入力と再開planの上限は別途4 MiBのままである。
共通の保留理由は一度だけ保存し、読込時に各候補へ戻す。根拠の削除や保留解除は行わない。

出力先が存在しなければ同じ解析コマンドで再実行できる。存在する場合は保存しておき、
新しい`--export-dir`を指定する。その後の確認・転送・登録にも同じ出力先を使う。
失敗した解析の代わりに空の`sp-migration`ディレクトリを作成してはならない。
32 MiBも超える場合は実byte数と上限が表示されるため、`--entity-id`で対象SPを絞り、
別々のbundleとして解析・確認する。

## 8. 最終確認

新IdPのmetadata、entityID、SSO endpoint、署名証明書を各SPへ設定し、実browserで
ログイン、属性、NameID、MFA、拒否条件を確認する。登録だけでSSO移行完了と判断しない。
元IdPの停止・SP切替・rollback判断は通常の変更管理手順で行う。

## 9. オプション

| 引数 | 内容 |
|---|---|
| `--source-type auto` | 既定。registryの存在から一般IdP/2FAS-KWを判別する |
| `--source-type shibboleth` | 一般IdP解析を明示。元の2FAS-KW管理情報は移行しない |
| `--source-type 2faskw` | 管理情報を照合して解析する。registry欠落・破損では停止する |
| `--context-check-config FILE` | 元の自己完結したContextCheck設定snapshot。元解析時だけ指定可 |
| `--registration-only` | 一般モードで未移行認可があるSPを登録だけ進める。認可は適用しない |
| `--defer-attributes` | 一般モードのtarget段階だけ。none固定で属性承認/profile作成を行わず後工程へ分離。applyは登録限定・隔離確認必須 |
| `--extract-aggregate` | 一般解析の明示的なファイルaggregate/SP-only role snapshot抽出。原本保存と条件確認を伴う |
| `--confirm-metadata-review` | 一般applyで原本署名・信頼・期限・group/filter確認済みを明示。対話確認も必要 |
| `--confirm-isolated-target` | 管理モードまたは未移行認可の登録限定applyに必須。隔離準備の申告 |
| `--analysis-only` / `--non-interactive` | 解析だけ、または非対話の読取処理。非対話applyは不可 |
| `--entity-id ENTITY_ID` | 解析対象を限定。複数指定可 |
| `--resume RUN_ID` | 同じbundle/選択/計画で実状態を照合し、未完了段階から継続する |

`--source-type`は元設定解析時だけ指定する。既存bundleのmodeを別modeへ変更する指定は拒否する。
その他のパス・出力引数は第3〜7節と`--help`を参照する。
新しい一般IdP bundleはmanifest v4で登録と認可の記録を分離し、抽出元XMLのhashと参照も保存する。
旧v3も読める。旧v1も読めるが
`LEGACY_UNASSESSED`であり、旧HELDは解除しない。新しい出力先へ再解析し、両hostで同じ修正版を使う。

## 10. 2FAS-KWで管理済みのSPを新IdPへ移す

### 10.1 移行対象と事前準備

対応する管理ファイル形式はregistry schema 1/2、catalog/access policy schema 1である。
1.3.5/1.4.0の形式・CLI出力を連携対象とするが、未知の項目・構成を推測して受け入れない。
元/新IdPの設定・metadata・必要な鍵は、通常のbackup手順でそれぞれ保護する。
移行ツールがIdP全体のbackupや秘密鍵のコピーを代行するわけではない。

移行するもの:

- ACTIVEの管理SPのentityID、metadata bytes、管理名。
- SP専用policyの属性集合、属性profile名・description、承認用途/classification/purpose。
- 他のpolicyによる追加送信属性の比較情報。policy自体はコピーしない。
- SP別MFA（inherit/force/required/bypass/sp-cidr-bypass）とCIDRs。
- allow/denyの属性IDと値、SP別access policyのenabled状態。

移行しないもの:

- registryのrevision/history/時刻/legacy XML、元SPの停止・削除。
- IdP全体MFA設定、LDAP/DBユーザー、MFA登録情報、秘密鍵、resolver/mappingの自動生成。
- DISABLED/REMOVED管理SP。元IdPの未管理SPは保留し、別の一般IdP解析で検討する。

同名の新規profileは属性集合とdescriptionが同じ場合だけ1回作成して共有する。
既存profileの属性集合が違えば停止するか別名を対話で指定する。更新・上書きはしない。
既存description、既存承認のclassification/purposeが違う場合は、明示確認後に新IdP側の値を保持する。
既存のrelease承認を内部判定承認へ格下げしない。承認を広げる場合も確認が必要である。

元のregistryだけではなく、参照されたmetadata/provider、実際の送信属性filter、catalog、
MFA設定、access policyを照合する。schema 1でaccess状態が台帳にない場合はpolicy実体から読む。
registryの`acsUrls`は、複数のACS要素が同じURLを使用すると重複する場合がある。
この配列だけは重複を許容し、読取時に整理してmetadata内のURL集合と比較する。
元のregistryやmetadata bytesは変更しない。URL集合が一致しなければ保留し、
JSONキー、管理名/entityID、その他の配列に対する重複検査は維持する。
送信属性は次の2種類を分けて記録する。`release_attributes`は両者の和集合である。

- `managed_profile_attributes`: `2faskw-管理名`のSP専用policyと一致する管理profileの属性。
- `additional_release_attributes`: その他の適用policyで全値許可され、管理profileには含まれない属性。

例えばprofileが`uid,mail`で、`alwaysRelease`が`schacHomeOrganization`を追加しているだけなら、
その追加を理由に元IdPを不整合とは判定しない。SP専用policyのRequesterと属性集合は引き続き厳密に照合する。
専用policyの欠落・重複・不正な条件・属性差分は`SOURCE_ATTRIBUTE_FILTER_DRIFT`で保留する。
profileが`none`の場合は専用policyがない構成も許容する。共通policyで不足属性が補われていても、
SP専用policyの不整合は解消したと扱わない。

共通属性を管理profileへ混入させたり、共通policy・追加属性の承認を自動作成したりしない。
新IdPの共通policyとresolver/mappingは管理者が別途準備する。未対応・動的・値限定・deny条件の保留は維持する。
全体accessが有効、またはschema 1の参照情報が不足している場合、policyファイルの欠落を空policyとして扱わない。
有効なaccess policyがあるのに全体accessが無効、孤立policy、hash/revision/属性/MFAの不一致は保留する。
accessを利用する元設定ではContextCheckの既知functionとSAML2.SSOへの接続も確認する。
非空RP overrides、動的bean、独自ContextCheck条件等は初期版では保留となる。

元の管理設定参照はIdPホーム内の標準パスが対象である。symlink、ホーム外の管理metadata、
不明なプロパティ優先順・継続行は保留する。MFA設定はCLIの文字列読込と認証時のJava properties読込が
一致しないescape/非ASCII等も保留する。稼働中の実効設定・署名の真正性は別途管理者が確認する。

### 10.2 元IdPで解析・転送する

```bash
# 元2FAS-KW管理設定を読み取り専用で解析する。管理変更を止め、整合したsnapshotで実行する。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --source-idp-home /opt/shibboleth-idp \
  --source-type 2faskw \
  --analysis-only

# 管理名・MFA・アクセス制限・診断理由を確認する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/analysis.txt

# 全体MFAの比較に使用する元設定の記録を確認する。秘密値は移行しない。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/managed-policy-diff.txt
```

解析後の転送は第6.1節の方法Aまたは方法Bを使う。一般IdP/2FAS-KWのどちらのbundleでも
ディレクトリ全体を移す。ZIPにする場合は元hostで作成・SCPし、新hostのtool配下へ展開する。
以下の手順は既定名`sp-migration`の例であり、別名のexportでは`--bundle-dir`を実際の展開先へ読み替える。

元設定を読めない場合は保護されたsnapshotを読み取れる環境で解析する。
credentials全体や登録ユーザーデータをbundleに入れない。内部manifest v2には移行に必要な
管理intent、追加送信属性の比較情報と全体MFA比較値だけを保存する。従来の一般IdP manifest v1も読み込める。
属性分離情報のない旧managed manifest v2は旧来の単一属性集合として読み込む。
今回の判定を利用するには、修正版ツールで元IdPを新しい`--export-dir`へ再解析する。
古いHELD bundleの診断は読込みだけで解除されない。新bundleは古いツールでは処理しない。
bundleは属性による許可・拒否値を含むため、公開場所へ置かず0700/0600で保護する。

### 10.3 新IdPで前提を確認・適用する

新IdPは公開前の隔離環境とする。edge/firewall等で利用者の接続を遮断し、管理者の確認経路だけを
許可しておく。本ツールはfirewallを設定せず、隔離の実効性を自動証明しない。
元/新IdPの全体MFA（default、policyOrder、bypassIPs、bypassCIDRs、useForwardedFor、
missingEnrollmentPolicy）が一致しない場合は停止する。必要な変更は別途確認して既存CLI等で行い、
新しい計画を作成する。SPインポートで全体方針を無確認に合わせない。

access policyを移す場合は、新IdPの`access init --apply`、build/restart、全体access有効化、
ContextCheck設定検査を先に完了する。単に設定ファイルが存在するだけで稼働済みとは判断しない。
必要なrelease/access属性のresolver、release属性のSAML mapping、ACS host許可も準備する。

新bundleでは、登録予定metadataを使って新IdPのfilterを読み取り専用で解析し、追加送信属性を照合する。
`GraphicalMatrixManagedSPMetadata`は標準ディレクトリを参照する既知のLocalDynamic providerであること、
親providerがある場合は既知のChaining providerであること、これらに未知のmetadata filterがないことが必要である。
全体policyをコピーせず、対象SPで得られる追加属性集合が同じであることを確認する。
共通属性が管理catalogの承認対象でなくても、`attributes discover`でresolverとmappingが確認でき、
blockedでないことを要求する。これは実ユーザーの値の存在や稼働中beanの有効性を証明するものではない。

| 停止理由 | 対応 |
|---|---|
| `TARGET_ADDITIONAL_RELEASE_DIFFER` | `missing`/`unexpected`を確認し、新IdPの共通policyを別途レビューする |
| `TARGET_ADDITIONAL_RELEASE_UNRESOLVED` | 未対応条件、provider/filter、参照先を確認する |
| `TARGET_ADDITIONAL_ATTRIBUTE_NOT_READY` | 新IdPの対象属性のresolverとSAML mappingを準備する |
| `TARGET_ATTRIBUTE_FILTER_DRIFT` | 新IdPのSP専用policyと管理profileの不整合を確認する |
| `TARGET_CHANGED_AFTER_REVIEW` | 他の管理変更を止め、新しい計画で確認し直す |

結果と差分は`runs/RUN_ID/additional-release-check.txt`へ保存する。確認時だけでなく各CLI書込みの前後にも
再照合する。追加属性の設定を無条件許可に変えて停止を回避しない。適用中に停止した場合は、
成功済み操作が残るため新IdPを隔離したまま調査する。

```bash
# 新IdPの既存管理状態と全体MFAのdriftを確認する。設定変更はしない。
sudo /opt/shibboleth-idp/bin/graphicalmatrix-sp.sh mfa show

# 移行先を読取確認する。未承認属性・未作成profileがある場合は依存作業を示し終了コード5になる。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --bundle-dir "$HOME/2faskw-sp-import-tools-1.4.0/sp-migration" \
  --target-idp-home /opt/shibboleth-idp

# 隔離・backup・排他的な管理作業を確認後、対話で選択したSPを段階的に適用する。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --bundle-dir "$HOME/2faskw-sp-import-tools-1.4.0/sp-migration" \
  --target-idp-home /opt/shibboleth-idp \
  --confirm-isolated-target \
  --apply
```

source確認、管理名/profile確認、必要な`APPROVE 属性ID 用途`、bypassの確認、
`ISOLATED TARGET`、最後に`REGISTER 件数`を要求する。確認なしの一括適用は行わない。
実行順は「属性承認 → 共通profile作成 → add → verify → access set → 必要ならaccess disable → 最終照合」。
元policyがdisabledでも`access set`は一旦enabledになるため、直後にdisableする。
その途中で失敗した状態も含め、新IdPを隔離したままにすることが必須である。
既存手作業SPのadopt、名前/entityID衝突の上書き、MFAの自動修復は行わない。

### 10.4 部分失敗から再開する

追加結果は`runs/RUN_ID/managed-result.txt`、段階記録は`managed-execution-state.tsv`へ保存する。
`plan-sha256.txt`と`plan.tsv`は計画照合用、`commands.txt`/`cli-output.txt`は実行根拠である。
`managed-policy-diff.txt`は元/新IdP全体MFAの比較結果となる。
`additional-release-check.txt`は追加送信属性の元/新IdP比較、解析根拠と再確認履歴となる。
TXT/TSVの手編集で実行済み判定を変更せず、実状態を優先して確認する。

```bash
# 成功済み操作と停止理由を確認する。新IdPは引き続き隔離する。
less ~/2faskw-sp-import-tools-1.4.0/sp-migration/runs/RUN_ID/managed-result.txt

# 同じSP選択・管理名/profile・承認判断を使い、未完了のaccess設定等から継続する。
sudo ~/2faskw-sp-import-tools-1.4.0/graphicalmatrix-sp-import.sh \
  --bundle-dir "$HOME/2faskw-sp-import-tools-1.4.0/sp-migration" \
  --target-idp-home /opt/shibboleth-idp \
  --resume RUN_ID \
  --confirm-isolated-target \
  --apply
```

再開前にentityID/hash/ACTIVE/profile/MFA/CIDRsと承認条件を照合する。
SP登録済みでもaccess未完了なら残作業を行う。disabled policyのsetだけ成功した場合はdisableへ進む。
新IdPの第三者変更、未知のpolicy差分、計画不一致では停止し、update/deleteで自動的に合わせない。
処理中も主要設定と管理metadataの変更を検知するが、既存CLIとの共通transaction/lockではない。
他管理者の同時変更を禁止し、途中失敗時のrollbackはbackupと既存CLIの通常手順で個別判断する。

### 10.5 切替判定

`CONFIG_VERIFIED`は登録・承認・profile・MFA・access policyの構成照合完了を意味する。
ツールの終了コード0だけではSPの信頼切替や認証成功を保証しない。結果には常に
`READY_FOR_CUTOVER=NOT_CONFIRMED`、`CUTOVER_VERIFIED=NOT_CONFIRMED`を記録する。

管理者は許可対象と拒否対象の実ユーザーによるSSO、deny優先、属性欠落時の拒否、
MFA/CIDR例外、送信属性、NameID、暗号化要求、SP側の新IdP署名証明書の信頼を確認する。
確認記録を残してから公開・SP切替を行う。未検証の対象は旧IdPで運用を継続する。

## 11. ビルド

```bash
# 新ツールだけをbuild・test・未署名ZIP生成する。
./scripts/build-sp-import-tools-package.sh

# Admin Tools等と同じ共通buildで全配布物とSHA256SUMSを生成する。
./scripts/build-plugin-package.sh

# リリース担当者がcommit済みソースと既存release鍵で全成果物を署名・検証する。
./scripts/build-plugin-package.sh --sign
```

固定名/version付きZIPは`target/sp-import-dist/`、共通checksumは`target/plugin-dist/SHA256SUMS`に生成する。
署名時は両ZIPと共通checksumに`.asc`を作成する。秘密鍵は配布しない。
未commitのローカル開発段階では署名せず、テストと未署名ZIPの検証を行う。
JSON読込用Jackson core、依存一覧、SHA-256付きCycloneDX SBOMも同梱する。
詳細な管理intentを含む移行bundleは配布ZIPへ含めない。
