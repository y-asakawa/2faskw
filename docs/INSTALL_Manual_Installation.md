# Manual Installation Guide

この文書は、2FAS-KW pluginを手動でShibboleth IdPへ導入する管理者向けの手順です。

ここでは、以下の自動補助スクリプトを使わずに、同等の確認と配置を手動コマンドで行う方法を説明します。

```text
./bin/graphicalmatrix-plugin-check.sh
./bin/graphicalmatrix-plugin-config.sh
```

手動作業では、各コマンドの出力を確認しながら進めてください。
特に既存設定ファイルを直接上書きしないこと、`web.xml` 変更後にIdP WARを再構築することが重要です。

## 1. 前提

例では、IdP homeを `/opt/shibboleth-idp`、配布ZIPの展開先を `/tmp/2faskw-install` とします。

```bash
export IDP_HOME=/opt/shibboleth-idp
export WORK_DIR=/tmp/2faskw-install
export PACKAGE_DIR=$WORK_DIR/2faskw-idp-plugin-@VERSION@
export TS=$(date +%Y%m%d%H%M%S)
```

必要なコマンドを確認します。

```bash
command -v java
command -v unzip
command -v sha256sum || command -v shasum
command -v install
```

IdP homeを確認します。

```bash
test -d "$IDP_HOME"
test -x "$IDP_HOME/bin/build.sh"
test -d "$IDP_HOME/edit-webapp/WEB-INF"
```

`edit-webapp/WEB-INF/web.xml` がない場合は、IdP標準の `web.xml` をコピーします。

```bash
sudo install -d -m 0755 "$IDP_HOME/edit-webapp/WEB-INF"
sudo install -m 0644 -o root -g root \
  "$IDP_HOME/dist/webapp/WEB-INF/web.xml" \
  "$IDP_HOME/edit-webapp/WEB-INF/web.xml"
```

## 2. 配布ZIPを展開する

```bash
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"
unzip /path/to/2faskw-idp-plugin-@VERSION@.zip
cd "$PACKAGE_DIR"
```

展開後の主な構成を確認します。

```bash
find . -maxdepth 2 -type d | sort
```

期待する主なディレクトリ:

```text
./bin
./conf
./docs
./examples
./plugin-metadata
./webapp
```

## 3. 配布物のchecksumを手動確認する

Linux:

```bash
cd "$PACKAGE_DIR"
sha256sum -c plugin-metadata/PACKAGE-MANIFEST.sha256
```

macOSなど `sha256sum` がない環境:

```bash
cd "$PACKAGE_DIR"
shasum -a 256 -c plugin-metadata/PACKAGE-MANIFEST.sha256
```

`OK` 以外が出た場合は導入を中止します。

## 4. 配布物の必須ファイルを手動確認する

`graphicalmatrix-plugin-check.sh --package-only` を使わない場合は、最低限、次を確認します。

```bash
cd "$PACKAGE_DIR"

test -f bootstrap/plugin.properties
test -f plugin-metadata/graphicalmatrix-plugin.properties
test -f plugin-metadata/README.md
test -f plugin-metadata/PACKAGE-CONTENTS.txt
test -f plugin-metadata/PACKAGE-MANIFEST.sha256

test -f conf/graphicalmatrix/graphicalmatrix.properties.idpnew
test -f conf/graphicalmatrix/db.properties.idpnew
test -f conf/graphicalmatrix/ldap.properties.idpnew
test -f conf/graphicalmatrix/webauthn-ldap.properties.idpnew
test -f conf/graphicalmatrix/api.properties.idpnew
test -f conf/graphicalmatrix/mfa-policy.properties.idpnew
test -f conf/graphicalmatrix/postgresql-schema.sql

test -f conf/authn/webauthn.properties.idpnew
test -f conf/authn/webauthn-registration.properties.idpnew
test -f conf/authn/webauthn-metadata.properties.idpnew

test -f bin/graphicalmatrix-db.sh
test -f bin/graphicalmatrix-db-migration.sh
test -f bin/graphicalmatrix-api-token.sh
test -f bin/graphicalmatrix-security-upgrade.sh
test -f bin/graphicalmatrix-api-curl-test.sh

test -f examples/webauthn-ldap-storage-config.xml
test -f examples/logrotate/graphicalmatrix-audit

test -f docs/README.md
test -f docs/INSTALL.md
test -f docs/INSTALL_Manual_Installation.md
test -f docs/INSTALL_LDAP.md
test -f docs/SECURITY.md
test -f docs/SECURITY-CHECKLIST.md
test -f docs/API-TOKEN-ROTATION.md
test -f docs/API-CURL-TESTS.md
test -f docs/FAQ.md
test -f docs/UPGRADE.md
test -f docs/CSV-EXPORT.md
test -f docs/DB-MIGRATION.md
test -f docs/SEQUENCE-STORAGE-MIGRATION.md
test -f docs/LOGROTATE.md
test -f docs/openapi.yaml
```

JARを確認します。

```bash
ls -l webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar
ls -l webapp/WEB-INF/lib/core-*.jar
ls -l webapp/WEB-INF/lib/HikariCP-*.jar
ls -l webapp/WEB-INF/lib/postgresql-*.jar
```

管理APIテンプレートが既定で無効になっていることを確認します。

```bash
grep -E '^[[:space:]]*graphicalmatrix[.]api[.]enabled[[:space:]]*=[[:space:]]*false[[:space:]]*$' \
  conf/graphicalmatrix/api.properties.idpnew
```

## 5. 配布物のバージョンを手動確認する

配布ディレクトリ名からバージョンを確認します。

```bash
basename "$PACKAGE_DIR"
```

期待値:

```text
2faskw-idp-plugin-@VERSION@
```

bootstrap metadataのバージョンを確認します。

```bash
grep -E '^[[:space:]]*plugin.version[[:space:]]*=' \
  "$PACKAGE_DIR/bootstrap/plugin.properties"
```

plugin metadataのバージョンを確認します。

```bash
grep -E 'io.github.yasakawa.faskw.authn.graphicalmatrix.versions[[:space:]]*=' \
  "$PACKAGE_DIR/plugin-metadata/graphicalmatrix-plugin.properties"

grep -E 'baseName\\.@VERSION@[[:space:]]*=' \
  "$PACKAGE_DIR/plugin-metadata/graphicalmatrix-plugin.properties"
```

JAR manifestのバージョンを確認します。

```bash
PLUGIN_JAR="$(ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar | head -n 1)"
unzip -p "$PLUGIN_JAR" META-INF/MANIFEST.MF | grep -E 'Implementation-Version'
```

OpenAPIのバージョンを確認します。

```bash
grep -E '^[[:space:]]*version:[[:space:]]*@VERSION@[[:space:]]*$' \
  "$PACKAGE_DIR/docs/openapi.yaml"
```

## 6. IdP側の状態を手動確認する

`graphicalmatrix-plugin-check.sh --idp-only` を使わない場合は、最低限、次を確認します。

```bash
test -d "$IDP_HOME"
test -x "$IDP_HOME/bin/build.sh"
test -f "$IDP_HOME/edit-webapp/WEB-INF/web.xml"
```

IdP moduleを確認します。

```bash
"$IDP_HOME/bin/module.sh" -l | grep -E 'idp.authn.Password|idp.authn.MFA'
```

TOTP / WebAuthnを使う場合は、Shibboleth pluginのtruststoreディレクトリを確認します。

```bash
test -d "$IDP_HOME/credentials/net.shibboleth.idp.plugin.authn.totp"
test -d "$IDP_HOME/credentials/net.shibboleth.idp.plugin.authn.webauthn"
```

TOTP / WebAuthnを使わない場合、この2つは未存在でも構いません。

## 7. 設定ファイルを手動確認する

`graphicalmatrix-plugin-check.sh --config-only` を使わない場合でも、
設定検査の実体であるJavaクラスを直接呼び出すことはできます。

```bash
PLUGIN_JAR="$(ls "$IDP_HOME"/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar 2>/dev/null | head -n 1)"
if [ -z "$PLUGIN_JAR" ]; then
  PLUGIN_JAR="$(ls "$PACKAGE_DIR"/webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar | head -n 1)"
fi

java -cp "$PLUGIN_JAR" \
  io.github.yasakawa.faskw.GraphicalMatrixConfigCheckTool \
  --idp-home "$IDP_HOME"
```

`OK:` 以外に `FAIL:` が出た場合、認証開始前に設定を修正します。

Java設定検査も使わずに手動確認する場合は、最低限、参照ファイルの存在と読取権限を確認します。

```bash
test -f "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties"

grep -E '^[[:space:]]*graphicalmatrix[.]savedata[[:space:]]*=' \
  "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties"

grep -E '^[[:space:]]*graphicalmatrix[.]view[.]css[[:space:]]*=' \
  "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties"

grep -E '^[[:space:]]*graphicalmatrix[.]view[.]changeNewTemplate[[:space:]]*=' \
  "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties"
```

CSSとHTMLテンプレートの存在例:

```bash
test -r "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css"
test -r "$IDP_HOME/conf/graphicalmatrix/views/graphicalmatrix.html"
test -r "$IDP_HOME/conf/graphicalmatrix/views/change-start.html"
test -r "$IDP_HOME/conf/graphicalmatrix/views/change-current.html"
test -r "$IDP_HOME/conf/graphicalmatrix/views/change-menu.html"
test -r "$IDP_HOME/conf/graphicalmatrix/views/change-new.html"
test -r "$IDP_HOME/conf/graphicalmatrix/views/change-method.html"
test -r "$IDP_HOME/conf/graphicalmatrix/views/change-complete.html"
test -r "$IDP_HOME/conf/graphicalmatrix/views/totp-register.html"
```

## 8. 手動配置のdry-run相当確認

`graphicalmatrix-plugin-config.sh` を使わずに配置する前に、配置元と配置先を一覧化します。
この段階ではファイルを変更しません。

```bash
cd "$PACKAGE_DIR"

find webapp/WEB-INF/lib -maxdepth 1 -type f -name '*.jar' -print | sort
find conf/graphicalmatrix -maxdepth 2 -type f | sort
find bin -maxdepth 1 -type f | sort
```

IdP側の既存ファイルを確認します。

```bash
sudo find "$IDP_HOME/edit-webapp/WEB-INF/lib" -maxdepth 1 -type f \
  \( -name '2faskw-idp-plugin-*.jar' -o -name 'core-*.jar' -o -name 'HikariCP-*.jar' -o -name 'postgresql-*.jar' \) \
  -print | sort

sudo find "$IDP_HOME/conf/graphicalmatrix" -maxdepth 2 -type f -print | sort
```

既存ファイルがある場合は、次章の手順でbackupまたは `.idpnew` として扱います。

## 9. JARを手動配置する

JARは既存ファイルをbackupしてから配置します。

```bash
sudo install -d -m 0755 "$IDP_HOME/edit-webapp/WEB-INF/lib"

for src in "$PACKAGE_DIR"/webapp/WEB-INF/lib/*.jar; do
  dest="$IDP_HOME/edit-webapp/WEB-INF/lib/$(basename "$src")"
  if [ -f "$dest" ]; then
    sudo cp -a "$dest" "$dest.bak.$TS"
  fi
  sudo install -m 0644 -o root -g root "$src" "$dest"
done
```

旧バージョンJARが残っている場合は、意図しない二重読み込みを避けるため退避します。
現在導入する `@VERSION@` 以外の2FAS-KW JARを確認してください。

```bash
sudo find "$IDP_HOME/edit-webapp/WEB-INF/lib" -maxdepth 1 -type f \
  -name '2faskw-idp-plugin-*.jar' -print | sort
```

不要な旧JARを退避する例:

```bash
sudo mkdir -p "$IDP_HOME/edit-webapp/WEB-INF/lib/disabled-2faskw-$TS"
sudo mv "$IDP_HOME/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-OLD.jar" \
  "$IDP_HOME/edit-webapp/WEB-INF/lib/disabled-2faskw-$TS/"
```

## 10. 設定テンプレートを手動配置する

既存設定がない場合は通常名で配置します。
既存設定がある場合は上書きせず、`.idpnew.$TS` として配置します。

```bash
sudo install -d -m 0755 "$IDP_HOME/conf/graphicalmatrix"

for name in \
  graphicalmatrix.properties \
  db.properties \
  ldap.properties \
  webauthn-ldap.properties \
  api.properties \
  mfa-policy.properties
do
  src="$PACKAGE_DIR/conf/graphicalmatrix/$name.idpnew"
  dest="$IDP_HOME/conf/graphicalmatrix/$name"
  if [ -f "$dest" ]; then
    sudo install -m 0644 -o root -g root "$src" "$dest.idpnew.$TS"
  else
    sudo install -m 0644 -o root -g root "$src" "$dest"
  fi
done

sudo install -m 0644 -o root -g root \
  "$PACKAGE_DIR/conf/graphicalmatrix/postgresql-schema.sql" \
  "$IDP_HOME/conf/graphicalmatrix/postgresql-schema.sql"
```

`.idpnew` が作成された場合は、既存設定と差分を確認し、必要な設定だけ反映します。

```bash
sudo find "$IDP_HOME/conf/graphicalmatrix" -maxdepth 1 -name '*.idpnew.*' -print

sudo diff -u \
  "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties" \
  "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties.idpnew.$TS"
```

## 11. HTMLテンプレートとCSSを手動配置する

HTMLテンプレートも既存ファイルを直接上書きしません。

```bash
sudo install -d -m 0755 "$IDP_HOME/conf/graphicalmatrix/views"

for src in "$PACKAGE_DIR"/conf/graphicalmatrix/views/*.idpnew; do
  base="$(basename "$src" .idpnew)"
  dest="$IDP_HOME/conf/graphicalmatrix/views/$base"
  if [ -f "$dest" ]; then
    sudo install -m 0644 -o root -g root "$src" "$dest.idpnew.$TS"
  else
    sudo install -m 0644 -o root -g root "$src" "$dest"
  fi
done
```

CSS:

```bash
sudo install -d -m 0755 "$IDP_HOME/conf/graphicalmatrix/assets"

if [ -f "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css" ]; then
  sudo install -m 0644 -o root -g root \
    "$PACKAGE_DIR/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew" \
    "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew.$TS"
else
  sudo install -m 0644 -o root -g root \
    "$PACKAGE_DIR/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew" \
    "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css"
fi
```

## 12. GraphicalMatrix画像を手動配置する

```bash
sudo install -d -m 0755 "$IDP_HOME/edit-webapp/graphicalmatrix/graphicals"

for src in "$PACKAGE_DIR"/conf/graphicalmatrix/graphicals/*; do
  dest="$IDP_HOME/edit-webapp/graphicalmatrix/graphicals/$(basename "$src")"
  if [ -f "$dest" ]; then
    sudo cp -a "$dest" "$dest.bak.$TS"
  fi
  sudo install -m 0644 -o root -g root "$src" "$dest"
done
```

## 13. 管理スクリプトを手動配置する

```bash
sudo install -d -m 0755 "$IDP_HOME/bin"

for name in \
  graphicalmatrix-db.sh \
  graphicalmatrix-db-migration.sh \
  graphicalmatrix-api-token.sh \
  graphicalmatrix-security-upgrade.sh \
  graphicalmatrix-api-curl-test.sh
do
  src="$PACKAGE_DIR/bin/$name"
  dest="$IDP_HOME/bin/$name"
  if [ -f "$dest" ]; then
    sudo cp -a "$dest" "$dest.bak.$TS"
  fi
  sudo install -m 0755 -o root -g root "$src" "$dest"
done
```

## 14. 手動配置結果を記録する

自動スクリプトを使わない場合でも、後で追跡できるようmanifestを残すことを推奨します。

```bash
MANIFEST="$IDP_HOME/conf/graphicalmatrix/install-manifest-manual-$TS.tsv"
sudo sh -c "printf 'source\tdestination\n' > '$MANIFEST'"

find "$PACKAGE_DIR/webapp/WEB-INF/lib" -maxdepth 1 -type f -name '*.jar' | sort |
while read -r src; do
  dest="$IDP_HOME/edit-webapp/WEB-INF/lib/$(basename "$src")"
  printf '%s\t%s\n' "$src" "$dest"
done | sudo tee -a "$MANIFEST" >/dev/null

echo "$MANIFEST"
```

## 15. web.xmlを手動編集する

`graphicalmatrix-plugin-config.sh` 相当の手動配置では、`web.xml` は変更されません。
GraphicalMatrix servlet mappingを `edit-webapp/WEB-INF/web.xml` に追加します。

まずbackupを作成します。

```bash
sudo cp -a \
  "$IDP_HOME/edit-webapp/WEB-INF/web.xml" \
  "$IDP_HOME/edit-webapp/WEB-INF/web.xml.bak.$TS"
```

配布物のサンプルと既存 `web.xml` を比較します。

```bash
diff -u \
  "$IDP_HOME/edit-webapp/WEB-INF/web.xml" \
  "$PACKAGE_DIR/examples/web.xml.current-poc.xml" | less
```

既存 `web.xml` に、配布物サンプル内の以下のmarker blockを追加します。

```text
<!-- BEGIN 2FAS-KW Plugin servlet mappings -->
...
<!-- END 2FAS-KW Plugin servlet mappings -->

<!-- BEGIN 2FAS-KW Plugin security constraints -->
...
<!-- END 2FAS-KW Plugin security constraints -->
```

編集後にmarkerが存在することを確認します。

```bash
grep -n 'BEGIN 2FAS-KW Plugin servlet mappings\\|END 2FAS-KW Plugin servlet mappings\\|BEGIN 2FAS-KW Plugin security constraints\\|END 2FAS-KW Plugin security constraints' \
  "$IDP_HOME/edit-webapp/WEB-INF/web.xml"
```

XMLとして大きく壊れていないことを確認します。

```bash
sudo env IDP_HOME="$IDP_HOME" python3 - <<'PY'
import os
import xml.etree.ElementTree as ET
ET.parse(os.path.join(os.environ["IDP_HOME"], "edit-webapp", "WEB-INF", "web.xml"))
print("web.xml parse OK")
PY
```

## 16. IdP WARを再構築する

JARと `web.xml` を変更したため、IdP WARを再構築します。

```bash
sudo "$IDP_HOME/bin/build.sh"
```

## 17. Jettyを再起動する

systemd service名が `jetty-idp.service` の場合:

```bash
sudo systemctl restart jetty-idp.service
sudo systemctl status jetty-idp.service -l --no-pager
```

環境によりservice名が異なる場合は、実環境のJetty service名に置き換えてください。

## 18. 導入後の手動確認

JAR:

```bash
ls -l "$IDP_HOME/edit-webapp/WEB-INF/lib"/2faskw-idp-plugin-*.jar
```

設定:

```bash
test -f "$IDP_HOME/conf/graphicalmatrix/graphicalmatrix.properties"
test -f "$IDP_HOME/conf/graphicalmatrix/db.properties"
test -f "$IDP_HOME/conf/graphicalmatrix/api.properties"
test -f "$IDP_HOME/conf/graphicalmatrix/mfa-policy.properties"
```

画面テンプレート:

```bash
test -f "$IDP_HOME/conf/graphicalmatrix/views/graphicalmatrix.html"
test -f "$IDP_HOME/conf/graphicalmatrix/views/change-new.html"
test -f "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css"
```

Servlet mapping:

```bash
grep -n 'GraphicalMatrixStart\\|GraphicalMatrixVerify\\|GraphicalMatrixChange\\|GraphicalMatrixAsset' \
  "$IDP_HOME/edit-webapp/WEB-INF/web.xml"
```

Java設定検査:

```bash
PLUGIN_JAR="$(ls "$IDP_HOME"/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar | head -n 1)"
java -cp "$PLUGIN_JAR" \
  io.github.yasakawa.faskw.GraphicalMatrixConfigCheckTool \
  --idp-home "$IDP_HOME"
```

IdP status:

```bash
curl -I http://127.0.0.1:8080/idp/status
```

HTTPS構成の場合:

```bash
curl -k -I https://127.0.0.1:8443/idp/status
```

ログ確認:

```bash
sudo journalctl -u jetty-idp.service -n 200 --no-pager
sudo tail -n 200 "$IDP_HOME/logs/idp-process.log"
```

## 19. 最小コマンド例

新規導入時の最小コマンド列です。
実運用では、各章の確認コマンドと `.idpnew` 差分確認も実施してください。

```bash
export IDP_HOME=/opt/shibboleth-idp
export WORK_DIR=/tmp/2faskw-install
export PACKAGE_DIR=$WORK_DIR/2faskw-idp-plugin-@VERSION@
export TS=$(date +%Y%m%d%H%M%S)

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"
unzip /path/to/2faskw-idp-plugin-@VERSION@.zip
cd "$PACKAGE_DIR"

sha256sum -c plugin-metadata/PACKAGE-MANIFEST.sha256

test -f webapp/WEB-INF/lib/2faskw-idp-plugin-@VERSION@.jar
test -f conf/graphicalmatrix/graphicalmatrix.properties.idpnew
test -f conf/graphicalmatrix/db.properties.idpnew
test -f conf/graphicalmatrix/api.properties.idpnew

sudo install -d -m 0755 "$IDP_HOME/edit-webapp/WEB-INF/lib"
for src in "$PACKAGE_DIR"/webapp/WEB-INF/lib/*.jar; do
  dest="$IDP_HOME/edit-webapp/WEB-INF/lib/$(basename "$src")"
  [ -f "$dest" ] && sudo cp -a "$dest" "$dest.bak.$TS"
  sudo install -m 0644 -o root -g root "$src" "$dest"
done

sudo install -d -m 0755 "$IDP_HOME/conf/graphicalmatrix"
for name in graphicalmatrix.properties db.properties ldap.properties webauthn-ldap.properties api.properties mfa-policy.properties; do
  src="$PACKAGE_DIR/conf/graphicalmatrix/$name.idpnew"
  dest="$IDP_HOME/conf/graphicalmatrix/$name"
  if [ -f "$dest" ]; then
    sudo install -m 0644 -o root -g root "$src" "$dest.idpnew.$TS"
  else
    sudo install -m 0644 -o root -g root "$src" "$dest"
  fi
done

sudo install -m 0644 -o root -g root \
  "$PACKAGE_DIR/conf/graphicalmatrix/postgresql-schema.sql" \
  "$IDP_HOME/conf/graphicalmatrix/postgresql-schema.sql"

sudo install -d -m 0755 "$IDP_HOME/conf/graphicalmatrix/views"
for src in "$PACKAGE_DIR"/conf/graphicalmatrix/views/*.idpnew; do
  base="$(basename "$src" .idpnew)"
  dest="$IDP_HOME/conf/graphicalmatrix/views/$base"
  if [ -f "$dest" ]; then
    sudo install -m 0644 -o root -g root "$src" "$dest.idpnew.$TS"
  else
    sudo install -m 0644 -o root -g root "$src" "$dest"
  fi
done

sudo install -d -m 0755 "$IDP_HOME/conf/graphicalmatrix/assets"
if [ -f "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css" ]; then
  sudo install -m 0644 -o root -g root \
    "$PACKAGE_DIR/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew" \
    "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew.$TS"
else
  sudo install -m 0644 -o root -g root \
    "$PACKAGE_DIR/conf/graphicalmatrix/assets/graphicalmatrix.css.idpnew" \
    "$IDP_HOME/conf/graphicalmatrix/assets/graphicalmatrix.css"
fi

sudo install -d -m 0755 "$IDP_HOME/edit-webapp/graphicalmatrix/graphicals"
for src in "$PACKAGE_DIR"/conf/graphicalmatrix/graphicals/*; do
  dest="$IDP_HOME/edit-webapp/graphicalmatrix/graphicals/$(basename "$src")"
  [ -f "$dest" ] && sudo cp -a "$dest" "$dest.bak.$TS"
  sudo install -m 0644 -o root -g root "$src" "$dest"
done

sudo install -d -m 0755 "$IDP_HOME/bin"
for name in graphicalmatrix-db.sh graphicalmatrix-db-migration.sh graphicalmatrix-api-token.sh graphicalmatrix-security-upgrade.sh graphicalmatrix-api-curl-test.sh; do
  dest="$IDP_HOME/bin/$name"
  [ -f "$dest" ] && sudo cp -a "$dest" "$dest.bak.$TS"
  sudo install -m 0755 -o root -g root "$PACKAGE_DIR/bin/$name" "$dest"
done

sudo cp -a "$IDP_HOME/edit-webapp/WEB-INF/web.xml" "$IDP_HOME/edit-webapp/WEB-INF/web.xml.bak.$TS"
echo "Edit $IDP_HOME/edit-webapp/WEB-INF/web.xml and add 2FAS-KW marker blocks from examples/web.xml.current-poc.xml"

sudo "$IDP_HOME/bin/build.sh"
sudo systemctl restart jetty-idp.service

PLUGIN_JAR="$(ls "$IDP_HOME"/edit-webapp/WEB-INF/lib/2faskw-idp-plugin-*.jar | head -n 1)"
java -cp "$PLUGIN_JAR" \
  io.github.yasakawa.faskw.GraphicalMatrixConfigCheckTool \
  --idp-home "$IDP_HOME"
```
