# Audit Logrotate

この文書は GraphicalMatrix 監査ログの logrotate 設定例です。

GraphicalMatrix監査ログ:

```text
/opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

監査ログには、ユーザーID、接続元IP、認証結果、challenge ID、操作結果が含まれます。
`sequence`、TOTP seed、API tokenは出力しない設計ですが、監査ログは認証ログとして保護してください。

## 推奨設定

配布物には以下のサンプルを含めます。

```text
examples/logrotate/graphicalmatrix-audit
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
    create 0640 jetty jetty
    su jetty jetty
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
- `create 0640 jetty jetty`: 新規ログファイルの権限と所有者
- `su jetty jetty`: logrotate処理時の実行ユーザー/グループ

## 適用手順

サンプルを `/etc/logrotate.d/` へ配置します。

```bash
sudo install -m 0644 examples/logrotate/graphicalmatrix-audit \
  /etc/logrotate.d/graphicalmatrix-audit
```

設定確認:

```bash
sudo logrotate -d /etc/logrotate.d/graphicalmatrix-audit
```

強制ローテーションテスト:

```bash
sudo logrotate -f /etc/logrotate.d/graphicalmatrix-audit
```

確認:

```bash
sudo ls -l /opt/shibboleth-idp/logs/graphicalmatrix-audit.log*
sudo tail /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

## copytruncateを使う理由

GraphicalMatrix監査ログはアプリケーション側がファイルへ追記します。
`copytruncate` を使うと、Jetty再起動やログファイル再オープン処理なしでローテーションできます。

注意:

- copyとtruncateのごく短い間に書かれたログは欠落する可能性があります
- 厳密な監査要件がある場合は、アプリケーション側のログ再オープン方式またはsyslog転送を検討してください
- PoC / 通常運用では `copytruncate` を標準例とします

## 権限

推奨:

```bash
sudo chown jetty:jetty /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
sudo chmod 0640 /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

ログディレクトリ:

```bash
sudo chown jetty:jetty /opt/shibboleth-idp/logs
sudo chmod 0750 /opt/shibboleth-idp/logs
```

既存のIdPログ運用と競合しないようにしてください。

## 保持期間

例:

- PoC: 30日から90日
- 本番: 180日以上
- 監査要件がある場合: 組織の規程に従う

`rotate 180` は1日1世代で約180日保持する設定です。

## 確認ポイント

- `logrotate -d` がエラーなしで完了する
- `logrotate -f` 後に新しい `graphicalmatrix-audit.log` が作成される
- 新しいログの所有者が `jetty:jetty` である
- 新しいログの権限が `0640` である
- rotated fileが圧縮される
- GraphicalMatrixログイン後、新しい `graphicalmatrix-audit.log` に追記される

## トラブル時

`permission denied` が出る場合:

- `/opt/shibboleth-idp/logs` の所有者と権限を確認する
- `graphicalmatrix-audit.log` の所有者と権限を確認する
- `su jetty jetty` が環境のユーザー/グループと一致しているか確認する

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
新ログ所有者:
新ログ権限:
ログ追記確認:
備考:
```
