# GraphicalMatrix管理CSVエクスポート

## 概要

現在の `graphicalmatrix_enrollment` から、`graphicalmatrix-db.sh csv` で再登録できる管理CSVを出力します。

CSV形式:

```text
action,user_id,mfa_method,force_sequence_change,initial_sequence,sequence
A,user001,GraphicalMatrix,off,,"img03,img07,img11,img14"
```

出力されるactionは、全ユーザーを新規登録できるように `A` です。

## ファイルへ出力

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export /secure/path/graphicalmatrix-users.csv
```

出力ファイルは権限 `0600` で作成されます。既存ファイルは上書きしません。

既存ファイルを明示的に上書きする場合:

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export /secure/path/graphicalmatrix-users.csv --force
```

## 標準出力

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv-export -
```

標準出力を使う場合、リダイレクト先の権限は実行者が管理してください。

## 出力対象

- `user_id`
- `mfa_method`
- `force_sequence_change`
- `initial_sequence`（復元可能な場合のみ出力する初期値。未設定は空欄）
- 復号した現在の `sequence`（復号できる保存方式の場合）

以下は管理CSVの対象外です。

- TOTP seed
- TOTP登録状態
- ロック状態
- 失敗回数
- 最終ログイン日時
- 作成日時 / 更新日時

## sequence保存方式

以下はportable CSVとして出力できます。

- `plaintext`
- `keyword`
- `aes-gcm`

`keyword` / `aes-gcm` の場合は、復号に必要なsecretファイルが利用できる必要があります。

`hash` は復号できないため、1件でもhash保存済みユーザーが存在する場合はエラー終了し、CSVファイルを作成しません。

## セキュリティ

出力CSVには、現在のGraphicalMatrix sequenceが復号可能な形式で含まれます。秘密情報として扱ってください。

- アクセス制限されたディレクトリへ保存する
- メールや共有フォルダへ平文で配置しない
- 作業完了後は安全に削除する
- バックアップ用途では、暗号化された保管領域を利用する

## 再インポート確認

エクスポートしたCSVは、別の空DBに対して以下で読み込めます。

```bash
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh csv /secure/path/graphicalmatrix-users.csv
```

本番DBにそのまま読み込むと、actionが `A` のため既存ユーザーエラーになります。
既存ユーザーを更新する場合は、対象行のactionを `M` に変更してください。


## v1.4.1の互換性

初期パスワードは再通知のため平文保存する。現在値がhashの場合は、初期値が読めてもportable CSV出力は失敗する。旧候補版のhash初期値も復元できないため出力を拒否し、再入力を必要とする。初期値を現在値の代用品として出力しない。マスク値や偽の空値で再import可能と表示しない。初期値が未設定の場合の空欄は正常であり、import時に現在画像列から自動補充しない。初期値と現在値を同じ値で登録するbootstrap CSVはforce_sequence_change=onを必要とする。CSV dry-runの表示は秘密をマスクする。転送用CSVは秘密を含むため既存の0600保管を維持する。
