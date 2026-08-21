# 2FAS-KW Log Rotation

この文書は2FAS-KWが出力するファイルログのlogrotate設定例です。

この設定例だけで2FAS-KW環境の全ログがローテーションされるわけではありません。
SP管理、SP別属性アクセス制御、CSVプロビジョニング、Shibboleth IdP標準ログ、
systemd journalも、それぞれの出力先と監査要件に応じて保持期間・圧縮・転送・削除を設定してください。
ログ種別と出力元の一覧は[LOG-REFERENCE.md](./LOG-REFERENCE.md)を参照してください。

GraphicalMatrix監査ログ:

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

監査ログには、ユーザーID、接続元IP、認証結果、challenge ID、操作結果が含まれます。
`sequence`、TOTP seed、API tokenは出力しない設計ですが、監査ログは認証ログとして保護してください。

## 対象範囲と保持設定

以下のログは出力元・実行ユーザー・保存先が異なるため、保持設定を個別に決める。
2FAS-KW固有のファイルログ用サンプルはすべて`examples/logrotate/`に収録する。

| ログ種別 | 既定パスまたは設定先 | サンプルまたは調整方法 |
| --- | --- | --- |
| GraphicalMatrix監査ログ | `/opt/shibboleth-idp/logs/graphicalmatrix-audit.log` | `examples/logrotate/graphicalmatrix-audit` |
| SP管理監査ログ | `/opt/shibboleth-idp/logs/graphicalmatrix-sp-management-audit.log` | `examples/logrotate/graphicalmatrix-sp-management-audit`。SP管理CLIを使用する場合に適用する。 |
| SP別アクセス制御監査ログ | `/opt/shibboleth-idp/logs/graphicalmatrix-access-audit.log` | `examples/logrotate/graphicalmatrix-access-audit`。アクセス制御とdecision監査を有効化する場合に適用する。 |
| CSVプロビジョニングログ | `graphicalmatrix.admin.csv.logFile`、既定 `/opt/graphicalmatrix-admin/logs/csv-import.log` | `examples/logrotate/graphicalmatrix-csv-import`。Admin Toolsを使用する場合に適用する。 |
| Shibboleth IdP標準ログ | 通常は`/opt/shibboleth-idp/logs/idp-process.log`、`idp-warn.log`、`idp-audit.log` | IdPのlogback設定および既存OS設定を確認し、2FAS-KW用設定で無条件に上書きしない。 |
| Jetty / Dashboardのjournal | `journalctl` / `journald` | logrotateではなく`journald.conf`、`journalctl --vacuum-*`、集中ログ転送の方針で保持期間を管理する。 |

すべてのファイルログを単一のワイルドカード設定へまとめてはならない。`jetty`、`root`、
Admin Tools実行ユーザーなどで所有者と書込み権限が異なるため、ログごとに対象パスと保存期間を
確認する。サンプルはroot実行のlogrotateを前提にしている。

## 推奨設定

配布物には以下のサンプルを含めます。必要なコンポーネントに対応するファイルだけを配置する。

```text
examples/logrotate/README.md
examples/logrotate/graphicalmatrix-audit
examples/logrotate/graphicalmatrix-sp-management-audit
examples/logrotate/graphicalmatrix-access-audit
examples/logrotate/graphicalmatrix-csv-import
```

内容:

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log {
    daily
    rotate 180
    missingok
    notifempty
    copytruncate
    compress
    delaycompress
    dateext
    dateformat -%Y%m%d
}
```

## 設定項目の意味

- `daily`: 1日ごとにローテーション
- `rotate 180`: 180世代を保持
- `missingok`: ファイルがなくてもエラーにしない
- `notifempty`: 空ファイルはローテーションしない
- `copytruncate`: ログを書き続けるプロセスを再起動せず、コピー後に元ファイルをtruncateする
- `compress`: 古いログをgzip圧縮する
- `delaycompress`: 直近1世代は圧縮を遅らせる
- `dateext`: rotated fileに日付を付ける
- `copytruncate`使用時は`create`を指定しても効果がない。元ファイルのinode、所有者、権限を維持したままtruncateする
- `su`を指定せず、OSのroot実行logrotateで処理する。親ログディレクトリの所有者を変更する必要はない

## 適用手順

サンプルを `/etc/logrotate.d/` へ配置します。SP管理、アクセス制御、CSVを使用していない場合は、
対応するファイルを配置しません。

```bash
sudo install -m 0644 examples/logrotate/graphicalmatrix-audit \
  /etc/logrotate.d/graphicalmatrix-audit

sudo install -m 0644 examples/logrotate/graphicalmatrix-sp-management-audit \
  /etc/logrotate.d/graphicalmatrix-sp-management-audit

sudo install -m 0644 examples/logrotate/graphicalmatrix-access-audit \
  /etc/logrotate.d/graphicalmatrix-access-audit

sudo install -m 0644 examples/logrotate/graphicalmatrix-csv-import \
  /etc/logrotate.d/graphicalmatrix-csv-import
```

設定確認:

```bash
sudo logrotate -d /etc/logrotate.d/graphicalmatrix-*
```

強制ローテーションテスト:

```bash
sudo logrotate -f /etc/logrotate.d/graphicalmatrix-*
```

確認:

```bash
sudo ls -l /opt/shibboleth-idp/logs/graphicalmatrix-audit.log*
sudo tail /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

`daily`は日次ローテーションの判定であり、スケジューラを有効化する設定ではない。
systemd timerを使用する環境では、少なくとも次を確認する。

```bash
sudo systemctl is-enabled logrotate.timer
sudo systemctl list-timers logrotate.timer
```

## copytruncateを使う理由

GraphicalMatrix監査ログはアプリケーション側がファイルへ追記します。
`copytruncate` を使うと、Jetty再起動やログファイル再オープン処理なしでローテーションできます。

注意:

- copyとtruncateのごく短い間に書かれたログは欠落する可能性があります
- 厳密な監査要件がある場合は、アプリケーション側のログ再オープン方式またはsyslog転送を検討してください
- PoC / 通常運用では `copytruncate` を標準例とします

## 権限

アクティブなGraphicalMatrix監査ログはJetty実行ユーザーが追記できる必要がある。
ファイルがまだ存在しない場合だけ、次のように作成する。

```bash
sudo install -m 0640 -o jetty -g jetty /dev/null \
  /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
sudo -u jetty test -w /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

`/opt/shibboleth-idp/logs` はIdP標準ログと共有するため、所有者・権限を2FAS-KWのためだけに
変更してはならない。SP管理監査ログは通常rootで実行するCLIが、CSVログは
`graphicalmatrix-admin`実行ユーザーが書き込む。各ログの書込み確認は、実際の実行ユーザーで行う。

## 保持期間

例:

- PoC: 30日から90日
- 本番: 180日以上
- 監査要件がある場合: 組織の規程に従う

`rotate 180` は1日1世代で約180日保持する設定です。

## 確認ポイント

- `logrotate -d` がエラーなしで完了する
- `logrotate -f` 後もアクティブな `graphicalmatrix-audit.log` が存在し、既存の所有者・権限が維持される
- 1回目のrotation後に日付付きファイルが作成される
- `delaycompress`を使うため、圧縮済みの`.gz`は2回目以降のrotation後に確認する
- GraphicalMatrixログイン後、truncateされた `graphicalmatrix-audit.log` に追記される
- 有効なSP管理、アクセス制御、CSVの各ログでも同じ確認を行う

## トラブル時

`permission denied` が出る場合:

- `/opt/shibboleth-idp/logs` の所有者と権限を確認する
- `graphicalmatrix-audit.log` の所有者と権限を確認する
- `sudo -u jetty test -w /opt/shibboleth-idp/logs/graphicalmatrix-audit.log` を確認する
- logrotateをrootで実行していることを確認する

ログが追記されない場合:

- Jettyが起動しているか確認する
- GraphicalMatrix認証を実行して監査イベントを発生させる
- `/opt/shibboleth-idp/logs/graphicalmatrix-audit.log` のパスが実装と一致しているか確認する

## 作業記録テンプレート

```text
作業日:
作業者:
対象IdP:
設定ファイル:
logrotate -d 結果:
logrotate -f 結果:
アクティブログ所有者:
アクティブログ権限:
ログ追記確認:
備考:
```
