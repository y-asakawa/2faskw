# PostgreSQL HA導入記録

## 1. 概要

GraphicalMatrix MFA / TOTP / WebAuthn credential保存用PostgreSQLを
DB 2台構成で構築した。

対象:

```text
DB1: 192.168.0.62 / db1.example.com
DB2: 192.168.0.63 / db2.example.com
DB VIP: 192.168.0.64
OS: Rocky Linux 10.2
CPU: aarch64
```

hostnameは識別しやすいように固定した。

```bash
sudo hostnamectl set-hostname db1.example.com  # DB1
sudo hostnamectl set-hostname db2.example.com  # DB2
```

構成:

```text
IdP -> DB VIP:5432 -> HAProxy -> local PostgreSQL Primary:5432

DB1: PostgreSQL Primary + HAProxy + Keepalived
DB2: PostgreSQL Standby + HAProxy + Keepalived
```

今回は3台目のDCSを置かないため、Patroniは導入していない。
Standbyの自動昇格は行わない。
Primary障害時は、DB2を手動昇格するとKeepalivedのPrimary判定が通り、
DB VIPがDB2へ移動する。

## 2. 設計判断

### 2.1 DB VIP

DB VIPは以下を使用した。

```text
192.168.0.64
```

事前確認:

```bash
sudo arping -D -I enp0s1 -c 3 192.168.0.64
```

確認結果:

```text
0 応答を受信
```

### 2.2 レプリケーション方式

PostgreSQL Streaming Replicationを使用した。

現在は可用性を優先し、async replicationとしている。

DB1確認結果:

```sql
SELECT application_name, client_addr, state, sync_state
FROM pg_stat_replication;
```

```text
application_name | walreceiver
client_addr      | 192.168.0.63
state            | streaming
sync_state       | async
```

同期レプリケーションへ変更する場合は、DB2停止時にDB1の書き込みが止まる可能性があるため、
本番運用要件を確認してから設定する。

### 2.3 自動昇格について

DB1/DB2のみで自動昇格を行うとsplit-brainリスクがある。
そのため今回は以下の安全側の動作とした。

- DB1 Primary時のみDB1がVIPを保持する
- DB2 Standby時はVIPを保持しない
- DB1障害時、DB2は自動昇格しない
- DB2を手動昇格すると、DB2がVIPを保持する

自動昇格が必要な場合は、Patroni + etcd/consul等のDCSを追加する。

## 3. パッケージ導入

DB1/DB2両方で実行。

```bash
sudo dnf install -y \
  https://download.postgresql.org/pub/repos/yum/reporpms/EL-10-aarch64/pgdg-redhat-repo-latest.noarch.rpm

sudo rpm --import /etc/pki/rpm-gpg/PGDG-RPM-GPG-KEY-AARCH64-RHEL
sudo dnf -y makecache

sudo dnf install -y \
  postgresql18-server \
  postgresql18-contrib \
  haproxy \
  keepalived \
  policycoreutils-python-utils \
  firewalld
```

導入結果:

```text
postgresql18-server-18.4-2PGDG.rhel10.2.aarch64
postgresql18-contrib-18.4-2PGDG.rhel10.2.aarch64
haproxy-3.0.5-6.el10_2.1.aarch64
keepalived-2.2.8-9.el10.aarch64
```

## 4. DB1 Primary構築

### 4.1 PostgreSQL初期化

DB1で実行。

```bash
sudo /usr/pgsql-18/bin/postgresql-18-setup initdb
```

### 4.2 postgresql.conf

DB1:

```conf
# 編集
sudo vi /var/lib/pgsql/18/data/postgresql.conf

listen_addresses = '127.0.0.1,192.168.0.62'
port = 5432
wal_level = replica
max_wal_senders = 10
max_replication_slots = 10
wal_keep_size = '512MB'
hot_standby = on
password_encryption = 'scram-sha-256'
```

DB2はbasebackup後に以下へ変更した。

```conf
listen_addresses = '127.0.0.1,192.168.0.63'
```

### 4.3 pg_hba.conf

DB1/DB2共通:

```conf
# 編集
sudo vi /var/lib/pgsql/18/data/pg_hba.conf

local   all          all                                 peer
host    all          all              127.0.0.1/32       scram-sha-256
host    all          all              ::1/128            scram-sha-256
host    replication  replicator       192.168.0.62/32   scram-sha-256
host    replication  replicator       192.168.0.63/32   scram-sha-256
```

### 4.4 DB / ロール作成

DB1で作成。

```sql
# 起動確認
sudo systemctl status postgresql-18 --no-pager

# 起動
sudo systemctl enable --now postgresql-18

# DBでpostgresユーザーとしてpsqlに入り実行
sudo -u postgres /usr/pgsql-18/bin/psql postgres

CREATE ROLE graphicalmatrix_app LOGIN PASSWORD '<GraphicalMatrix DB password>';
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '<replication password>';
CREATE DATABASE graphicalmatrix OWNER graphicalmatrix_app;

# 確認
\du
\l graphicalmatrix

# SQLを抜けて
sudo systemctl status postgresql-18 --no-pager
sudo -u postgres /usr/pgsql-18/bin/psql -d graphicalmatrix -c '\conninfo'
```

パスワードの実値は文書には記録しない。
`graphicalmatrix_app` のパスワードは既存IdPの
`/opt/shibboleth-idp/credentials/graphicalmatrix-db.password` と同じ値を使用した。

### 4.5 既存DB dump投入（データを他のSQLから移行しないのであれば不要）

既存IdPサーバ `192.168.0.60` からdumpを取得した。

```bash
sudo -u postgres pg_dump \
  -d graphicalmatrix \
  --no-owner \
  --no-privileges \
  --format=plain \
  > graphicalmatrix-current.sql
```

DB1へ投入。

```bash
sudo -u postgres psql -d graphicalmatrix -v ON_ERROR_STOP=1 -f /tmp/graphicalmatrix-current.sql
```

確認結果:

```text
graphicalmatrix_enrollment: 4
storagerecords: 1
```

`storagerecords` はShibboleth WebAuthn credential保存用。

## 5. DB2 Standby構築

DB2で実行。

```bash
sudo systemctl stop postgresql-18
sudo rm -rf /var/lib/pgsql/18/data
sudo mkdir -p /var/lib/pgsql/18/data
sudo chown postgres:postgres /var/lib/pgsql/18/data
sudo chmod 0700 /var/lib/pgsql/18/data
```

replication用 `.pgpass`:

```text
192.168.0.62:5432:*:replicator:<replication password>
192.168.0.63:5432:*:replicator:<replication password>
```

basebackup:

```bash
sudo -u postgres env PGPASSWORD='<replication password>' \
  /usr/pgsql-18/bin/pg_basebackup \
  -h 192.168.0.62 \
  -U replicator \
  -D /var/lib/pgsql/18/data \
  -Fp -Xs -P -R
```

起動:

```bash
sudo systemctl enable --now postgresql-18
```

確認結果:

```sql
SELECT pg_is_in_recovery();
```

```text
t
```

DB2データ件数:

```text
graphicalmatrix_enrollment: 4
storagerecords: 1
```

## 6. Firewall

DB1/DB2両方で実行。

```bash
sudo firewall-cmd --permanent --add-port=5432/tcp
sudo firewall-cmd --permanent --add-protocol=vrrp
sudo firewall-cmd --reload
```

確認結果:

```text
ports: 5432/tcp
protocols: vrrp
```

## 7. HAProxy

DB1/DB2両方に同じ設定を配置。

`sudo vi /etc/haproxy/haproxy.cfg`:

```conf
# 最小構成
global
    log         127.0.0.1 local2
    chroot      /var/lib/haproxy
    pidfile     /var/run/haproxy.pid
    maxconn     4000
    user        haproxy
    group       haproxy
    daemon
    stats socket /var/lib/haproxy/stats

defaults
    mode                    tcp
    log                     global
    option                  tcplog
    option                  dontlognull
    retries                 3
    timeout connect         10s
    timeout client          1m
    timeout server          1m
    timeout check           10s
    maxconn                 3000

listen postgres_write
    bind 192.168.0.64:5432
    mode tcp
    option tcp-check
    default-server inter 2s fall 3 rise 2 on-marked-down shutdown-sessions
    server local_pg 127.0.0.1:5432 check

listen stats
    bind 127.0.0.1:8404
    mode http
    stats enable
    stats uri /haproxy?stats
```

VIP未保持ノードでもHAProxyを起動できるよう、DB1/DB2両方で設定。

```bash
sudo tee /etc/sysctl.d/99-graphicalmatrix-db-ha.conf >/dev/null <<'EOF'
net.ipv4.ip_nonlocal_bind = 1
EOF

sudo sysctl --system
```

SELinux:

```bash
sudo setsebool -P haproxy_connect_any 1
```

確認:

```bash
sudo haproxy -c -f /etc/haproxy/haproxy.cfg
sudo systemctl enable --now haproxy
```

## 8. Keepalived

### 8.1 Primary判定

監視専用DBロールをDB1で作成した。

```bash
sudo -u postgres /usr/pgsql-18/bin/psql postgres
```

```sql
CREATE ROLE graphicalmatrix_ha_monitor LOGIN PASSWORD '<monitor password>';
```

Primary判定スクリプト:

`sudo vi /usr/local/sbin/check_pg_primary.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
. /etc/keepalived/pg_monitor.env
/usr/pgsql-18/bin/pg_isready -q -h 127.0.0.1 -p 5432
/usr/bin/pgrep -x haproxy >/dev/null
role="$(PGPASSWORD="$PG_MONITOR_PASSWORD" /usr/pgsql-18/bin/psql \
  -h 127.0.0.1 \
  -U graphicalmatrix_ha_monitor \
  -d postgres \
  -Atqc 'SELECT pg_is_in_recovery();' 2>/dev/null)"
[ "$role" = "f" ]
```

監視用パスワードはスクリプト本文へ直接書かず、rootのみ読めるenvファイルに分離した。

`sudo vi /etc/keepalived/pg_monitor.env`:

```bash
PG_MONITOR_PASSWORD='<monitor password>'
```

新規作成する場合:

```bash
sudo install -D -m 0600 -o root -g root /dev/null \
  /etc/keepalived/pg_monitor.env

sudo vi /etc/keepalived/pg_monitor.env

sudo install -D -m 0755 -o root -g root /dev/null \
  /usr/local/sbin/check_pg_primary.sh

sudo vi /usr/local/sbin/check_pg_primary.sh
```

作成済みファイルの権限を修正する場合:

```bash
sudo chown root:root /etc/keepalived/pg_monitor.env
sudo chmod 0600 /etc/keepalived/pg_monitor.env

sudo chown root:root /usr/local/sbin/check_pg_primary.sh
sudo chmod 0755 /usr/local/sbin/check_pg_primary.sh
```

SELinux:

```bash
sudo setsebool -P keepalived_connect_any 1
```

### 8.2 keepalived.conf

DB1:

```conf
＃編集
sudo vi /etc/keepalived/keepalived.conf
```
```
global_defs {
    enable_script_security
    script_user root
}

vrrp_script chk_pg_primary {
    script /usr/local/sbin/check_pg_primary.sh
    interval 2
    fall 2
    rise 2
}

vrrp_instance VI_GRAPHICALMATRIX_DB {
    state BACKUP
    interface enp0s1
    virtual_router_id 64
    priority 150
    advert_int 1
    nopreempt

    unicast_src_ip 192.168.0.62
    unicast_peer {
        192.168.0.63
    }

    authentication {
        auth_type PASS
        auth_pass ImgMx64
    }

    virtual_ipaddress {
        192.168.0.64/24 dev enp0s1
    }

    track_script {
        chk_pg_primary
    }
}
```

DB2は以下だけ変更。

```conf
priority 100
unicast_src_ip 192.168.0.63
unicast_peer {
    192.168.0.62
}
```

起動:

```bash
sudo keepalived -t -f /etc/keepalived/keepalived.conf
sudo systemctl enable --now keepalived
ip -br addr | grep 192.168.0.64
```

## 9. 確認結果

### 9.1 サービス状態

DB1/DB2:

```bash
sudo systemctl is-enabled postgresql-18 haproxy keepalived
sudo systemctl is-active postgresql-18 haproxy keepalived
```

確認結果:

```text
enabled
enabled
enabled
active
active
active
```

### 9.2 VIP

DB1:

```text
enp0s1 UP 192.168.0.62/24 192.168.0.64/24
```

DB2:

```text
enp0s1 UP 192.168.0.63/24
```

DB1がPrimaryであるため、VIPはDB1にある。

### 9.3 VIP経由接続

DB1、DB2、IdPサーバから確認。

```bash
＃DBがない場合
sudo dnf -y install postgresql18

PGPASSWORD='<GraphicalMatrix DB password>' \
  psql -h 192.168.0.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -Atqc 'SELECT 1;'
```

確認結果:

```text
1
```

### 9.4 Replication

DB1:

```sql
SELECT application_name, client_addr, state, sync_state
FROM pg_stat_replication;
```

確認結果:

```text
application_name | walreceiver
client_addr      | 192.168.0.63
state            | streaming
sync_state       | async
```

DB2:

```sql
SELECT pg_is_in_recovery(), status, sender_host
FROM pg_stat_wal_receiver;
```

確認結果:

```text
pg_is_in_recovery | t
status            | streaming
sender_host       | 192.168.0.62
```

## 10. IdP接続確認（切替）
[<mark>2FAS-KWを構築する：INSTALL.md</mark>](./INSTALL.md)　を参照




















### 10.1 WebAuthn StorageService切替

WebAuthn credential保存用のStorageServiceは、`db.properties` ではなく
`/opt/shibboleth-idp/conf/global.xml` の `GraphicalMatrixStorageDataSource` を参照する。
こちらもDB VIPへ切り替えた。

変更前:

```xml
p:url="jdbc:postgresql://127.0.0.1:5432/graphicalmatrix"
```

変更後:

```xml
p:url="jdbc:postgresql://192.168.0.64:5432/graphicalmatrix"
```

バックアップ:

```text
/opt/shibboleth-idp/conf/global.xml.webauthn-local-postgresql.bak.<timestamp>
```

対象bean:

```text
GraphicalMatrixStorageDataSource
GraphicalMatrixJDBCStorageService
WebAuthnJDBCAccelerator
```

確認コマンド:

```bash
sudo -u jetty /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-list

PGPASSWORD=$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password) \
  psql -h 192.168.0.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -Atqc "SELECT context, id, value IS NOT NULL FROM storagerecords ORDER BY context, id;"
```

確認結果:

```text
net.shibboleth.idp.plugin.authn.webauthn / user001@example.com のcredential 1件を確認。
```

IdPログにはWebAuthn pluginの以下のINFOが出る。

```text
Use of non-clustered storage service will result in per-node lockout behavior
```

このINFOはWebAuthn credentialのDB保存先が未設定であることを直接示すものではない。
credential本体は `storagerecords` に保存されており、今回の切替後はDB VIP経由で参照する。

### 10.2 TOTP DB参照

TOTPは、Shibboleth TOTP Plugin標準のStorageServiceではなく、
GraphicalMatrix側の `GraphicalMatrixTotpSeedSource` を `shibboleth.authn.TOTP.SeedSource` として使用している。

設定ファイル:

```text
/opt/shibboleth-idp/conf/authn/totp-authn-config.xml
```

設定:

```xml
<bean id="shibboleth.authn.TOTP.SeedSource"
      class="io.github.yasakawa.faskw.GraphicalMatrixTotpSeedSource" />
```

`GraphicalMatrixTotpSeedSource` は `/opt/shibboleth-idp/conf/graphicalmatrix/db.properties` を読み、
`graphicalmatrix_enrollment.totp_seed` を参照する。
そのため、`db.properties` をDB VIPへ切り替えた時点でTOTP seed参照先もDB VIPへ切替済み。

確認コマンド:

```bash
sudo -u jetty /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list

PGPASSWORD=$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password) \
  psql -h 192.168.0.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -Atqc "SELECT user_id, mfa_method, totp_status, totp_seed IS NOT NULL
         FROM graphicalmatrix_enrollment
         WHERE mfa_method = 'TOTP' OR totp_seed IS NOT NULL
         ORDER BY user_id;"
```

確認結果:

```text
user002 | TOTP | PENDING | t
```

つまり、TOTP seedはDB VIP上の `graphicalmatrix_enrollment.totp_seed` に保存され、
TOTP認証時も同じDB VIPを参照する。

## 11. 手動フェイルオーバー手順

DB1障害時、DB2をPrimaryへ昇格する。

DB2で実行:

```bash
sudo -u postgres /usr/pgsql-18/bin/pg_ctl \
  -D /var/lib/pgsql/18/data \
  promote
```

または:

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -c 'SELECT pg_promote();' postgres
```

確認:

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc 'SELECT pg_is_in_recovery();' postgres
ip -4 -br addr show enp0s1
```

期待値:

```text
pg_is_in_recovery = f
DB2に 192.168.0.64/24 が付与される
```

旧PrimaryのDB1は、そのままPrimaryとして戻さない。
DB2昇格後にDB1を復旧する場合は、DB1を停止した状態で
`pg_rewind` または `pg_basebackup` によりDB2配下のStandbyとして作り直す。

## 12. 監視項目

最低限、以下を監視する。

```bash
systemctl is-active postgresql-18
systemctl is-active haproxy
systemctl is-active keepalived
/usr/pgsql-18/bin/pg_isready -h 127.0.0.1 -p 5432
/usr/local/sbin/check_pg_primary.sh
ip -4 addr show dev enp0s1
```

SQL監視:

Primary:

```sql
SELECT application_name, client_addr, state, sync_state,
       write_lag, flush_lag, replay_lag
FROM pg_stat_replication;
```

Standby:

```sql
SELECT pg_is_in_recovery();
SELECT status, sender_host, sender_port
FROM pg_stat_wal_receiver;
```

ディスク監視:

```text
/var/lib/pgsql/18/data
/var/lib/pgsql/18/data/pg_wal
```

## 13. PostgreSQLログ

### 13.1 現状

DB1/DB2とも、PostgreSQLの `logging_collector` によりDBログをファイル取得している。

確認コマンド:

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc \
  "SHOW logging_collector;
   SHOW log_destination;
   SHOW log_directory;
   SHOW log_filename;
   SHOW log_rotation_age;
   SHOW log_rotation_size;
   SHOW log_line_prefix;" postgres
```

確認結果:

```text
logging_collector = on
log_destination = stderr
log_directory = log
log_filename = postgresql-%a.log
log_rotation_age = 1d
log_rotation_size = 0
```

ログファイル:

```text
/var/lib/pgsql/18/data/log/postgresql-Tue.log
```

`postgresql-%a.log` 形式のため、曜日ごとのログファイルになる。
`log_truncate_on_rotation = on` により、同じ曜日のログは次週に上書きされる。
つまり、この設定では概ね7日分をPostgreSQL側で保持する。
長期保管が必要な場合は、別途ログ転送またはバックアップ対象に含める。

### 13.2 運用向けログ設定

初期状態でもログ取得は有効だったが、接続/切断ログを明示的に取得し、
ログ行の形式を揃えるため、DB1/DB2へ以下の運用ログ設定を追加した。

`/var/lib/pgsql/18/data/postgresql.conf`:

```conf
# GraphicalMatrix DB operational logging
logging_collector = on
log_destination = 'stderr'
log_directory = 'log'
log_filename = 'postgresql-%a.log'
log_truncate_on_rotation = on
log_rotation_age = '1d'
log_rotation_size = 0
log_connections = on
log_disconnections = on
log_line_prefix = '[%t]%u %d %p[%l]'
log_checkpoints = on
log_lock_waits = on
log_min_duration_statement = '1000ms'
log_temp_files = '64MB'
log_statement = 'none'
```

バックアップ:

```text
DB1: /var/lib/pgsql/18/data/postgresql.conf.logging.bak.<timestamp>
DB2: /var/lib/pgsql/18/data/postgresql.conf.logging.bak.<timestamp>
```

追加で、指定形式へ合わせるため以下を末尾へ追記した。
同一パラメータが複数ある場合、PostgreSQLでは後に書かれた値が有効になる。

```conf
# GraphicalMatrix DB requested logging prefix
log_connections = on
log_disconnections = on
log_line_prefix = '[%t]%u %d %p[%l]'
```

バックアップ:

```text
DB1: /var/lib/pgsql/18/data/postgresql.conf.logprefix.bak.<timestamp>
DB2: /var/lib/pgsql/18/data/postgresql.conf.logprefix.bak.<timestamp>
```

反映コマンド:

```bash
sudo systemctl reload postgresql-18
```

確認コマンド:

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc \
  "SHOW log_line_prefix;
   SHOW log_connections;
   SHOW log_disconnections;
   SHOW log_lock_waits;
   SHOW log_min_duration_statement;
   SHOW log_temp_files;" postgres
```

確認結果:

```text
[%t]%u %d %p[%l]
on
on
on
1s
64MB
```

### 13.3 ログ確認コマンド

DB1/DB2で実行する。

```bash
sudo tail -n 100 /var/lib/pgsql/18/data/log/postgresql-$(LC_TIME=C date +%a).log
```

曜日ファイルを直接指定する場合:

```bash
sudo tail -n 100 /var/lib/pgsql/18/data/log/postgresql-Tue.log
```

リアルタイム監視:

```bash
sudo tail -f /var/lib/pgsql/18/data/log/postgresql-$(LC_TIME=C date +%a).log
```

systemd journal側の起動ログ:

```bash
sudo journalctl -u postgresql-18 -n 100 --no-pager
```

PostgreSQL起動後の詳細ログは `logging_collector` にリダイレクトされるため、
通常調査では `/var/lib/pgsql/18/data/log/` 配下を見る。

### 13.4 ログ出力例

接続ログ:

```text
<timestamp> [21970] user=graphicalmatrix_app db=graphicalmatrix app=psql client=127.0.0.1 LOG:  接続認証完了
<timestamp> [21970] user=graphicalmatrix_app db=graphicalmatrix app=psql client=127.0.0.1 LOG:  接続を切断
```

チェックポイントログ:

```text
LOG:  チェックポイント開始: time
LOG:  チェックポイント完了: ...
```

SQLエラー:

```text
ERROR:  ... 構文エラー
文:  SELECT ...
```

### 13.5 ログ取得方針

取得するもの:

- PostgreSQL起動/停止
- 接続/切断
- 認証完了
- ERROR以上のSQLエラー
- checkpoint
- lock wait
- 1秒以上の遅いSQL
- 64MB以上の一時ファイル

取得しないもの:

- 全SQL文の常時記録

理由:

- `sequence`、`totp_seed`、WebAuthn credentialなどの秘密情報をSQL文としてログへ出すリスクを避けるため。
- `log_statement = 'all'` は本番では使用しない。

### 13.6 保管と監視

現状はDBノード上のローカルファイルに保存している。

```text
/var/lib/pgsql/18/data/log/
```

本番では以下を推奨する。

- `/var/lib/pgsql/18/data/log/` を監視対象にする
- `ERROR` / `FATAL` / `PANIC` をアラート対象にする
- `replication` 切断、WAL receiver停止、checkpoint異常を監視する
- 長期保管が必要な場合はrsyslog、Fluent Bit、監視基盤などへ転送する
- ログファイルをバックアップ対象に含める場合は、TOTP seed等の秘密情報が出ていないか定期確認する

## 14. SQLによるDB確認手順

DB上のデータをSQLで確認する場合は、原則としてDB VIP `192.168.0.64` 経由で確認する。
これにより、IdPが実際に参照する経路と同じ経路で確認できる。

### 14.1 IdPサーバから確認する

IdPサーバ `192.168.0.60` で実行する。

```bash
PGPASSWORD=$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password) \
  psql \
  -h 192.168.0.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix
```

ワンライナーで確認する場合:

```bash
PGPASSWORD=$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password) \
  psql \
  -h 192.168.0.64 \
  -U graphicalmatrix_app \
  -d graphicalmatrix \
  -c 'SELECT current_database(), current_user, inet_server_addr(), inet_server_port();'
```

### 14.2 DB1 Primary上で確認する

DB1 `192.168.0.62` で実行する。

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -d graphicalmatrix
```

Primary状態確認:

```sql
SELECT pg_is_in_recovery();
```

期待値:

```text
f
```

### 14.3 DB2 Standby上で確認する

DB2 `192.168.0.63` で実行する。

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -d graphicalmatrix
```

Standby状態確認:

```sql
SELECT pg_is_in_recovery();
```

期待値:

```text
t
```

Standbyは読み取り確認用。
通常運用ではStandbyへ直接書き込まない。

### 14.4 テーブル一覧

```sql
\dt
```

SQLで確認する場合:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

現在の主要テーブル:

```text
graphicalmatrix_enrollment
storagerecords
```

### 14.5 カラム確認

GraphicalMatrix / TOTP管理テーブル:

```sql
\d graphicalmatrix_enrollment
```

WebAuthn credential保存テーブル:

```sql
\d storagerecords
```

SQLで確認する場合:

```sql
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'graphicalmatrix_enrollment'
ORDER BY ordinal_position;
```

```sql
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'storagerecords'
ORDER BY ordinal_position;
```

### 14.6 GraphicalMatrixユーザー一覧

```sql
SELECT
  user_id,
  mfa_method,
  force_sequence_change,
  initial_sequence,
  sequence,
  status,
  failed_count,
  locked_until,
  last_success_at,
  created_at,
  updated_at
FROM graphicalmatrix_enrollment
ORDER BY user_id;
```

日時をJST表示にする場合:

```sql
SELECT
  user_id,
  mfa_method,
  force_sequence_change,
  initial_sequence,
  sequence,
  status,
  failed_count,
  CASE
    WHEN locked_until = 0 THEN NULL
    ELSE to_char(to_timestamp(locked_until / 1000) AT TIME ZONE 'Asia/Tokyo',
                 'YYYY/MM/DD HH24:MI:SS')
  END AS locked_until_jst,
  CASE
    WHEN last_success_at = 0 THEN NULL
    ELSE to_char(to_timestamp(last_success_at / 1000) AT TIME ZONE 'Asia/Tokyo',
                 'YYYY/MM/DD HH24:MI:SS')
  END AS last_success_at_jst,
  to_char(to_timestamp(created_at / 1000) AT TIME ZONE 'Asia/Tokyo',
          'YYYY/MM/DD HH24:MI:SS') AS created_at_jst,
  to_char(to_timestamp(updated_at / 1000) AT TIME ZONE 'Asia/Tokyo',
          'YYYY/MM/DD HH24:MI:SS') AS updated_at_jst
FROM graphicalmatrix_enrollment
ORDER BY user_id;
```

### 14.7 特定ユーザー確認

```sql
SELECT *
FROM graphicalmatrix_enrollment
WHERE user_id = 'user001';
```

### 14.8 TOTP確認

TOTP方式のユーザー、またはTOTP seedを持つユーザーを確認する。

```sql
SELECT
  user_id,
  mfa_method,
  totp_status,
  CASE
    WHEN totp_seed IS NULL OR totp_seed = '' THEN 'NO'
    ELSE 'YES'
  END AS totp_seed_set,
  CASE
    WHEN totp_registered_at = 0 THEN NULL
    ELSE to_char(to_timestamp(totp_registered_at / 1000) AT TIME ZONE 'Asia/Tokyo',
                 'YYYY/MM/DD HH24:MI:SS')
  END AS totp_registered_at_jst
FROM graphicalmatrix_enrollment
WHERE mfa_method = 'TOTP'
   OR totp_seed IS NOT NULL
ORDER BY user_id;
```

注意:

- `totp_seed` は秘密情報。
- 通常確認では `totp_seed` 本体を表示しない。
- 必要な場合のみ、作業記録を残して限定的に確認する。

### 14.9 WebAuthn credential確認

WebAuthn credentialは `storagerecords` に保存される。

```sql
SELECT
  context,
  id AS storage_id,
  expires,
  version,
  length(value) AS value_length
FROM storagerecords
WHERE context = 'net.shibboleth.idp.plugin.authn.webauthn'
ORDER BY id;
```

credentialのJSON概要を確認する場合:

```sql
SELECT
  context,
  id AS storage_id,
  value::jsonb ->> 'username' AS username,
  value::jsonb ->> 'nickname' AS nickname,
  value::jsonb ->> 'credentialId' AS credential_id,
  value::jsonb ->> 'signatureCount' AS signature_count,
  value::jsonb ->> 'userVerified' AS user_verified,
  to_char(to_timestamp(((value::jsonb ->> 'registrationTime')::bigint) / 1000)
          AT TIME ZONE 'Asia/Tokyo',
          'YYYY/MM/DD HH24:MI:SS') AS registration_time_jst
FROM storagerecords
WHERE context = 'net.shibboleth.idp.plugin.authn.webauthn'
ORDER BY id;
```

注意:

- `value` にはWebAuthn credential情報が含まれる。
- 通常確認では `value` 全文を表示しない。

### 14.10 件数確認

```sql
SELECT count(*) FROM graphicalmatrix_enrollment;
SELECT count(*) FROM storagerecords;
```

方式別件数:

```sql
SELECT mfa_method, count(*)
FROM graphicalmatrix_enrollment
GROUP BY mfa_method
ORDER BY mfa_method;
```

### 14.11 ロック中ユーザー確認

```sql
SELECT
  user_id,
  failed_count,
  to_char(to_timestamp(locked_until / 1000) AT TIME ZONE 'Asia/Tokyo',
          'YYYY/MM/DD HH24:MI:SS') AS locked_until_jst
FROM graphicalmatrix_enrollment
WHERE locked_until > (extract(epoch FROM now()) * 1000)
ORDER BY locked_until DESC;
```

### 14.12 強制sequence変更対象

```sql
SELECT user_id, mfa_method, force_sequence_change, initial_sequence, sequence
FROM graphicalmatrix_enrollment
WHERE force_sequence_change = 1
ORDER BY user_id;
```

### 14.13 レプリケーション確認

Primary DB1で確認:

```sql
SELECT
  application_name,
  client_addr,
  state,
  sync_state,
  write_lag,
  flush_lag,
  replay_lag
FROM pg_stat_replication;
```

Standby DB2で確認:

```sql
SELECT pg_is_in_recovery();
```

```sql
SELECT
  status,
  sender_host,
  sender_port,
  latest_end_lsn,
  latest_end_time
FROM pg_stat_wal_receiver;
```

### 14.14 VIP経由でどのDBに接続したか確認

VIP経由接続で実行:

```sql
SELECT
  inet_server_addr() AS connected_server_ip,
  inet_server_port() AS connected_server_port,
  pg_is_in_recovery() AS connected_to_standby;
```

通常時の期待値:

```text
connected_server_ip   = 127.0.0.1 または 192.168.0.64 経由のHAProxy接続先
connected_server_port = 5432
connected_to_standby  = f
```

HAProxyはVIPを保持しているノード上のローカルPostgreSQLへ接続する。

## 15. 注意事項

- 現在はasync replication。
- DB2停止時もDB1は書き込みを継続できる。
- DB1障害直前のWALがDB2へ未反映の場合、少量のデータ損失リスクがある。
- データ損失を避ける場合は同期レプリケーションを検討する。
- ただし2台構成で同期レプリケーションにすると、Standby停止時にPrimaryの書き込みが停止する可能性がある。
- 自動昇格が必要な場合は、Patroni + DCSを追加する。
- 3台目を置かない構成では、split-brain防止のため自動昇格は行わない。

## 16. firewalld SSH接続元制限

DB1/DB2のSSH接続元を `192.168.0.0/24` のみに制限した。

対象:

```text
DB1: 192.168.0.62
DB2: 192.168.0.63
DB VIP: 192.168.0.64
```

DB VIP `192.168.0.64` はKeepalivedによりDB1またはDB2へ付与されるため、
SSH制限はDB1/DB2それぞれのfirewalldへ設定する。

作業前の確認:

```bash
echo "$SSH_CONNECTION"
sudo firewall-cmd --state
sudo firewall-cmd --get-active-zones
sudo firewall-cmd --list-all
```

作業前のバックアップ:

```bash
ts=$(date +%Y%m%d%H%M%S)
sudo sh -c "firewall-cmd --permanent --list-all > /root/firewalld-before-ssh-restrict-$ts.txt"
```

設定コマンド:

```bash
sudo firewall-cmd --permanent --zone=public \
  --add-rich-rule='rule family="ipv4" source address="192.168.0.0/24" service name="ssh" accept'

sudo firewall-cmd --permanent --zone=public --remove-service=ssh
sudo firewall-cmd --reload
```

確認コマンド:

```bash
sudo firewall-cmd --permanent --zone=public --query-service=ssh
sudo firewall-cmd --permanent --zone=public \
  --query-rich-rule='rule family="ipv4" source address="192.168.0.0/24" service name="ssh" accept'
sudo firewall-cmd --zone=public --list-all
```

確認結果:

```text
query-service=ssh: no
query-rich-rule: yes
```

DB1/DB2では、PostgreSQL用の `5432/tcp` とVRRP用の `vrrp` は変更していない。
この設定により、SSHのみ `192.168.0.0/24` からの接続に限定される。

## 17. PostgreSQL 5432/tcp SSL化手順

### 17.1 現状

DB1/DB2ともPostgreSQL SSLは無効。

確認コマンド:

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc \
  "SHOW ssl;
   SHOW ssl_cert_file;
   SHOW ssl_key_file;
   SHOW ssl_ca_file;" postgres
```

現状:

```text
ssl = off
```

IdP側JDBC URL:

```properties
graphicalmatrix.db.url=jdbc:postgresql://192.168.0.64:5432/graphicalmatrix
```

このため、現在の `IdP -> DB VIP:5432 -> HAProxy -> PostgreSQL` は平文接続。

### 17.2 方針

本番では以下を推奨する。

```text
DB VIP用FQDNを用意する
ローカルCAでDB VIP用サーバ証明書を発行する
DB1/DB2へ同じDB VIP用証明書と秘密鍵を配置する
PostgreSQL ssl=on
IdP/Admin ToolsのJDBC URLを sslmode=verify-full にする
IdP/Admin ToolsへローカルCA証明書を配置する
```

例:

```text
DB VIP:      192.168.0.64
DB VIP FQDN: db-graphicalmatrix.example.com
```

`verify-full` では、JDBC URLの接続先と証明書SANが一致する必要がある。

FQDNで接続する場合:

```text
JDBC接続先: db-graphicalmatrix.example.com
証明書SAN: DNS:db-graphicalmatrix.example.com
```

IPで接続する場合:

```text
JDBC接続先: 192.168.0.64
証明書SAN: IP Address:192.168.0.64
```

FQDNを用意できない場合は、IP SAN付き証明書でも `verify-full` は可能。
ただし、運用上はDB VIP用FQDNを推奨する。

### 17.3 HAProxy構成上の注意

現在のHAProxyはTCP proxyとして動作している。

```text
IdP -> DB VIP:5432 -> HAProxy -> 127.0.0.1:5432 PostgreSQL
```

この構成では、HAProxyでTLS終端しない。
TLS handshakeはIdP/PostgreSQL間で透過的に行われる。

そのため、証明書はHAProxyではなくPostgreSQL側へ設定する。

### 17.4 ローカルCA証明書の準備

ローカルCAでDB VIP用サーバ証明書を発行する。
CA秘密鍵はDBサーバやIdPサーバへ置かない。

必要ファイル:

```text
db-ca.crt          # IdP/Admin Toolsへ配布するCA証明書
db-vip.crt         # DB1/DB2へ配置するサーバ証明書
db-vip.key         # DB1/DB2へ配置するサーバ秘密鍵
```

証明書SAN例:

```text
DNS:db-graphicalmatrix.example.com
IP Address:192.168.0.64
```

FQDN運用だけならDNS SANだけでもよい。
IP接続で `verify-full` する可能性がある場合はIP SANも入れる。

### 17.5 DB1/DB2への証明書配置

DB1/DB2の両方で実行する。

```bash
sudo install -d -m 0700 -o postgres -g postgres /var/lib/pgsql/18/data/certs

sudo install -m 0644 -o postgres -g postgres db-ca.crt \
  /var/lib/pgsql/18/data/certs/db-ca.crt

sudo install -m 0644 -o postgres -g postgres db-vip.crt \
  /var/lib/pgsql/18/data/certs/db-vip.crt

sudo install -m 0600 -o postgres -g postgres db-vip.key \
  /var/lib/pgsql/18/data/certs/db-vip.key
```

秘密鍵権限確認:

```bash
sudo ls -l /var/lib/pgsql/18/data/certs/db-vip.key
```

期待値:

```text
-rw------- postgres postgres
```

### 17.6 postgresql.conf設定

DB1/DB2の両方でバックアップする。

```bash
TS=$(date +%Y%m%d%H%M%S)
sudo cp -a /var/lib/pgsql/18/data/postgresql.conf \
  /var/lib/pgsql/18/data/postgresql.conf.ssl.bak.$TS
```

`/var/lib/pgsql/18/data/postgresql.conf` へ追記する。

```conf
# GraphicalMatrix DB SSL - YYYY-MM-DD
ssl = on
ssl_cert_file = 'certs/db-vip.crt'
ssl_key_file = 'certs/db-vip.key'
ssl_ca_file = 'certs/db-ca.crt'
ssl_min_protocol_version = 'TLSv1.2'
```

設定反映はreloadではなくrestartを推奨する。

```bash
sudo systemctl restart postgresql-18
sudo systemctl status postgresql-18 --no-pager
```

確認:

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc \
  "SHOW ssl;
   SHOW ssl_cert_file;
   SHOW ssl_key_file;
   SHOW ssl_ca_file;
   SHOW ssl_min_protocol_version;" postgres
```

期待値:

```text
on
certs/db-vip.crt
certs/db-vip.key
certs/db-ca.crt
TLSv1.2
```

### 17.7 pg_hba.conf設定

まずはSSLを有効化し、IdP/Admin Tools側のSSL接続確認を行う。
確認後、本番では `host` ではなく `hostssl` を使ってSSL接続を強制する。

DB1/DB2の両方でバックアップする。

```bash
TS=$(date +%Y%m%d%H%M%S)
sudo cp -a /var/lib/pgsql/18/data/pg_hba.conf \
  /var/lib/pgsql/18/data/pg_hba.conf.ssl.bak.$TS
```

本番向け例:

```conf
# GraphicalMatrix IdP/Admin connections over SSL
hostssl graphicalmatrix graphicalmatrix_app   192.168.0.60/32 scram-sha-256
hostssl graphicalmatrix graphicalmatrix_admin 192.168.0.0/24  scram-sha-256

# HA monitor from local HAProxy/Keepalived scripts
host    postgres    graphicalmatrix_ha_monitor 127.0.0.1/32 scram-sha-256

# Replication. Use hostssl if replication SSL is configured.
hostssl replication replicator 192.168.0.62/32 scram-sha-256
hostssl replication replicator 192.168.0.63/32 scram-sha-256
```

注意:

- 既存の広い `host ... 0.0.0.0/0` がある場合は削除または狭める
- `hostssl` にすると、非SSL接続は拒否される
- 反映前にIdP/Admin ToolsのSSL接続テストを行う
- replicationを `hostssl` にする場合は、Standby側の `primary_conninfo` にSSL設定が必要

反映:

```bash
sudo systemctl reload postgresql-18
```

### 17.8 IdP側CA証明書配置

IdP `192.168.0.60` で実行する。

```bash
sudo install -d -m 0750 -o root -g jetty /opt/shibboleth-idp/credentials/db-ssl

sudo install -m 0640 -o root -g jetty db-ca.crt \
  /opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

確認:

```bash
sudo -u jetty test -r /opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

### 17.9 IdP db.properties変更

FQDN推奨:

```properties
graphicalmatrix.db.url=jdbc:postgresql://db-graphicalmatrix.example.com:5432/graphicalmatrix?sslmode=verify-full&sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

IP SAN証明書を使う場合:

```properties
graphicalmatrix.db.url=jdbc:postgresql://192.168.0.64:5432/graphicalmatrix?sslmode=verify-full&sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

変更前バックアップ:

```bash
TS=$(date +%Y%m%d%H%M%S)
sudo cp -a /opt/shibboleth-idp/conf/graphicalmatrix/db.properties \
  /opt/shibboleth-idp/conf/graphicalmatrix/db.properties.ssl.bak.$TS
```

変更後、権限確認:

```bash
sudo chown root:jetty /opt/shibboleth-idp/conf/graphicalmatrix/db.properties
sudo chmod 0640 /opt/shibboleth-idp/conf/graphicalmatrix/db.properties
```

### 17.10 WebAuthn StorageService global.xml変更

WebAuthn StorageServiceは `db.properties` ではなく、
`/opt/shibboleth-idp/conf/global.xml` の `GraphicalMatrixStorageDataSource` を参照する。

`global.xml` 側のJDBC URLもSSL付きに変更する。

FQDN推奨:

```xml
p:url="jdbc:postgresql://db-graphicalmatrix.example.com:5432/graphicalmatrix?sslmode=verify-full&amp;sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt"
```

XMLでは `&` を `&amp;` として書く。

変更前バックアップ:

```bash
TS=$(date +%Y%m%d%H%M%S)
sudo cp -a /opt/shibboleth-idp/conf/global.xml \
  /opt/shibboleth-idp/conf/global.xml.db-ssl.bak.$TS
```

変更後:

```bash
sudo systemctl restart jetty-idp
sudo systemctl status jetty-idp --no-pager
```

### 17.11 Admin Tools側CA証明書配置

Admin Toolsを別サーバへ入れる場合も、同じCA証明書が必要。

```bash
sudo install -d -m 0750 /opt/graphicalmatrix-admin/credentials/db-ssl

sudo install -m 0640 db-ca.crt \
  /opt/graphicalmatrix-admin/credentials/db-ssl/db-ca.crt
```

`/opt/graphicalmatrix-admin/conf/graphicalmatrix/db.properties`:

```properties
graphicalmatrix.db.url=jdbc:postgresql://db-graphicalmatrix.example.com:5432/graphicalmatrix?sslmode=verify-full&sslrootcert=/opt/graphicalmatrix-admin/credentials/db-ssl/db-ca.crt
```

### 17.12 replicationのSSL化

IdPからDBへの通信だけでなく、DB1/DB2間のreplicationも暗号化する場合は、
Standby側の `primary_conninfo` にSSL設定を追加する。

DB2 Standby例:

```bash
sudo -u postgres /usr/pgsql-18/bin/psql -Atqc \
  "ALTER SYSTEM SET primary_conninfo =
   'host=192.168.0.62 port=5432 user=replicator password=<replication password> application_name=db2 sslmode=verify-ca sslrootcert=/var/lib/pgsql/18/data/certs/db-ca.crt';" postgres
```

反映にはStandbyのrestartを推奨する。

```bash
sudo systemctl restart postgresql-18
```

確認:

```sql
SELECT status, sender_host, sender_port FROM pg_stat_wal_receiver;
```

`verify-full` をreplicationにも使う場合は、`host=` に書く接続先と証明書SANを一致させる。
物理DBホスト名で接続するなら、DB1/DB2それぞれのFQDNをSANへ含めた証明書設計が必要。

### 17.13 SSL接続確認

IdPサーバから確認する。

FQDN:

```bash
PGPASSWORD="$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password)" \
psql "host=db-graphicalmatrix.example.com port=5432 dbname=graphicalmatrix user=graphicalmatrix_app sslmode=verify-full sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt" \
  -c "SELECT current_user, current_database(), ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
```

IP SAN:

```bash
PGPASSWORD="$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password)" \
psql "host=192.168.0.64 port=5432 dbname=graphicalmatrix user=graphicalmatrix_app sslmode=verify-full sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt" \
  -c "SELECT current_user, current_database(), ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
```

期待値:

```text
ssl = t
```

管理CLI確認:

```bash
sudo -u jetty /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
```

WebAuthn確認:

```bash
sudo -u jetty /opt/shibboleth-idp/bin/graphicalmatrix-db.sh webauthn-list
```

### 17.14 hostssl強制後の確認

SSL接続確認後、`pg_hba.conf` を `hostssl` に絞った場合、
非SSL接続が拒否されることを確認する。

```bash
PGPASSWORD="$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password)" \
psql "host=192.168.0.64 port=5432 dbname=graphicalmatrix user=graphicalmatrix_app sslmode=disable" \
  -c "SELECT 1;"
```

期待値:

```text
接続失敗
```

SSL接続:

```bash
PGPASSWORD="$(sudo cat /opt/shibboleth-idp/credentials/graphicalmatrix-db.password)" \
psql "host=db-graphicalmatrix.example.com port=5432 dbname=graphicalmatrix user=graphicalmatrix_app sslmode=verify-full sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt" \
  -c "SELECT 1;"
```

期待値:

```text
接続成功
```

### 17.15 ロールバック

IdP側:

```bash
sudo cp -a /opt/shibboleth-idp/conf/graphicalmatrix/db.properties.ssl.bak.TIMESTAMP \
  /opt/shibboleth-idp/conf/graphicalmatrix/db.properties

sudo cp -a /opt/shibboleth-idp/conf/global.xml.db-ssl.bak.TIMESTAMP \
  /opt/shibboleth-idp/conf/global.xml

sudo systemctl restart jetty-idp
```

DB1/DB2側:

```bash
sudo cp -a /var/lib/pgsql/18/data/postgresql.conf.ssl.bak.TIMESTAMP \
  /var/lib/pgsql/18/data/postgresql.conf

sudo cp -a /var/lib/pgsql/18/data/pg_hba.conf.ssl.bak.TIMESTAMP \
  /var/lib/pgsql/18/data/pg_hba.conf

sudo systemctl restart postgresql-18
```

### 17.16 注意事項

- `sslmode=require` は暗号化するが、接続先名検証はしない。
- `sslmode=verify-ca` はCA検証を行うが、接続先名検証はしない。
- `sslmode=verify-full` はCA検証と接続先名検証を行うため本番推奨。
- `verify-full` ではJDBC接続先と証明書SANを一致させる。
- DB VIP構成では、DB1/DB2へ同じDB VIP用証明書と秘密鍵を配置する。
- ローカルCA利用は可能。ただしCA秘密鍵はDB/IdP/Admin Toolsサーバへ置かない。
- 証明書期限監視を追加する。
- 証明書更新はDB1/DB2の両方へ同時期に反映する。
- `hostssl` 強制前に必ずSSL接続成功を確認する。
- Admin Toolsを別サーバで使う場合もCA証明書とJDBC SSL設定を同じにする。

## 18. パフォーマンスチューニング設定例

### 18.1 前提

この章は、将来の本番投入前に検討する設定例である。
未適用。

想定:

```text
登録ユーザー数: 約50,000
同時接続ユーザー: 約300
IdP台数: 2台想定
DB台数: Primary/Standby 2台
DB接続先: DB VIP -> HAProxy -> PostgreSQL Primary
DB用途: GraphicalMatrix / TOTP seed / WebAuthn credential / 管理CLI
```

注意:

- 同時接続ユーザー300は、そのままDB接続300本を意味しない。
- DB接続数はIdP側の実装、接続プール、Jetty thread数、MFA画面操作頻度で決まる。
- まずDB直結の最大接続数を増やすより、IdP側またはPgBouncerで接続数を制御する。
- 以下の値は初期案であり、`pg_stat_activity`、`pg_stat_statements`、OSメトリクスを見て調整する。

### 18.2 推奨方針

本番では以下を推奨する。

```text
PostgreSQL max_connectionsをむやみに300以上にしない
IdP/管理ツール/APIからのDB接続をプールする
必要ならPgBouncerをDB VIP手前またはDBノード上に追加する
PostgreSQL本体はメモリ、WAL、autovacuum、監視ログを調整する
```

DB接続数の目安:

```text
IdP 1台あたり: 30 - 50接続上限
IdP 2台合計:   60 - 100接続上限
Admin Tools:   通常1 - 5接続
監視:          5 - 10接続
max_connections初期案: 150
```

同時300ユーザーでも、DB接続は100前後に抑える設計を優先する。

### 18.3 PostgreSQL設定例 8GB RAM

DBサーバのRAMが8GB程度の場合の初期案。

`/var/lib/pgsql/18/data/postgresql.conf`:

```conf
# GraphicalMatrix performance baseline - 8GB RAM example
max_connections = 150

shared_buffers = '2GB'
effective_cache_size = '6GB'
maintenance_work_mem = '512MB'
work_mem = '8MB'

wal_level = replica
max_wal_senders = 10
max_replication_slots = 10
wal_keep_size = '1GB'
checkpoint_timeout = '15min'
checkpoint_completion_target = 0.9
max_wal_size = '4GB'
min_wal_size = '1GB'

random_page_cost = 1.1
effective_io_concurrency = 200

autovacuum = on
autovacuum_max_workers = 4
autovacuum_naptime = '30s'
autovacuum_vacuum_scale_factor = 0.05
autovacuum_analyze_scale_factor = 0.02

track_io_timing = on
log_min_duration_statement = '1000ms'
log_lock_waits = on
```

### 18.4 PostgreSQL設定例 16GB RAM

DBサーバのRAMが16GB程度の場合の初期案。

```conf
# GraphicalMatrix performance baseline - 16GB RAM example
max_connections = 150

shared_buffers = '4GB'
effective_cache_size = '12GB'
maintenance_work_mem = '1GB'
work_mem = '16MB'

wal_level = replica
max_wal_senders = 10
max_replication_slots = 10
wal_keep_size = '2GB'
checkpoint_timeout = '15min'
checkpoint_completion_target = 0.9
max_wal_size = '8GB'
min_wal_size = '2GB'

random_page_cost = 1.1
effective_io_concurrency = 200

autovacuum = on
autovacuum_max_workers = 4
autovacuum_naptime = '30s'
autovacuum_vacuum_scale_factor = 0.05
autovacuum_analyze_scale_factor = 0.02

track_io_timing = on
log_min_duration_statement = '1000ms'
log_lock_waits = on
```

`work_mem` は接続ごと、かつソート/ハッシュ処理ごとに使われる可能性がある。
`max_connections` を大きくする場合は、`work_mem` を上げすぎない。

### 18.5 接続プール / PgBouncer

同時300ユーザーを想定する場合、接続プールを入れる方が安定する。

候補:

```text
IdPアプリ側のDataSource pool
PgBouncer
HAProxy + PgBouncer + PostgreSQL
```

PgBouncerを使う場合の接続グラフィカル:

```text
IdP -> DB VIP:6432 -> HAProxy -> PgBouncer -> PostgreSQL Primary:5432
```

またはDBノード上:

```text
IdP -> DB VIP:6432 -> HAProxy -> local PgBouncer:6432 -> local PostgreSQL:5432
```

PgBouncer初期案:

```ini
[databases]
graphicalmatrix = host=127.0.0.1 port=5432 dbname=graphicalmatrix

[pgbouncer]
listen_addr = 127.0.0.1
listen_port = 6432
pool_mode = transaction
max_client_conn = 500
default_pool_size = 80
min_pool_size = 10
reserve_pool_size = 20
reserve_pool_timeout = 5
server_idle_timeout = 600
server_lifetime = 3600
ignore_startup_parameters = extra_float_digits
```

注意:

- WebAuthn StorageServiceやTOTP seed参照が長いtransactionを持たない前提なら `transaction` poolが使いやすい。
- session stateや一時テーブルを使う処理がある場合は `session` poolを検討する。
- PgBouncerを入れる場合、JDBC URLのポートは `6432` に変更する。
- PostgreSQL SSLを使う場合は、PgBouncerでTLSをどこで終端するかを別途設計する。

### 18.6 HAProxy設定例

現在のHAProxyはDB VIPのTCP forward用途。
同時300ユーザー想定では、HAProxy側の接続上限も明示する。

例:

```haproxy
global
    maxconn 1000

defaults
    mode tcp
    timeout connect 5s
    timeout client  60s
    timeout server  60s

frontend pgsql
    bind 192.168.0.64:5432
    maxconn 500
    default_backend pgsql_primary

backend pgsql_primary
    option tcp-check
    tcp-check connect
    server local_pg 127.0.0.1:5432 check maxconn 200
```

PgBouncerを使う場合:

```haproxy
frontend pgbouncer
    bind 192.168.0.64:6432
    maxconn 500
    default_backend pgbouncer_primary

backend pgbouncer_primary
    option tcp-check
    tcp-check connect
    server local_pgbouncer 127.0.0.1:6432 check maxconn 500
```

### 18.7 DBスキーマ / index確認

50,000ユーザー規模では、現在の主キー検索中心なら大きな負荷にはなりにくい。
ただし、管理CLIやAPIで一覧・方式別検索を多用する場合はindexを追加する。

推奨index例:

```sql
CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_mfa_method
  ON graphicalmatrix_enrollment (mfa_method);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_status
  ON graphicalmatrix_enrollment (status);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_force_change
  ON graphicalmatrix_enrollment (force_sequence_change);

CREATE INDEX IF NOT EXISTS idx_graphicalmatrix_enrollment_updated_at
  ON graphicalmatrix_enrollment (updated_at);
```

WebAuthn credentialは `storagerecords` の構造に依存する。
JSON内検索を多用する場合は、実データとクエリを見てGIN indexを検討する。
ただし、Shibboleth標準StorageServiceの想定外index追加は、事前検証してから行う。

### 18.8 autovacuum / analyze

GraphicalMatrixの通常認証では、成功/失敗回数、lock、last_success_at更新が発生する。
更新頻度が高い場合、autovacuum/analyzeが遅れると性能が落ちる。

テーブル単位の設定例:

```sql
ALTER TABLE graphicalmatrix_enrollment SET (
  autovacuum_vacuum_scale_factor = 0.02,
  autovacuum_analyze_scale_factor = 0.01,
  autovacuum_vacuum_threshold = 100,
  autovacuum_analyze_threshold = 100
);
```

確認:

```sql
SELECT
  relname,
  n_live_tup,
  n_dead_tup,
  last_vacuum,
  last_autovacuum,
  last_analyze,
  last_autoanalyze
FROM pg_stat_user_tables
WHERE relname IN ('graphicalmatrix_enrollment', 'storagerecords');
```

### 18.9 OS設定例

Linux側の基本確認。

```bash
free -h
lsblk
df -h /var/lib/pgsql/18/data
ulimit -n
sysctl net.core.somaxconn
sysctl vm.swappiness
```

設定例:

```conf
# /etc/sysctl.d/90-graphicalmatrix-db.conf
vm.swappiness = 10
net.core.somaxconn = 1024
net.ipv4.tcp_keepalive_time = 300
net.ipv4.tcp_keepalive_intvl = 30
net.ipv4.tcp_keepalive_probes = 5
```

反映:

```bash
sudo sysctl --system
```

systemd open files確認:

```bash
systemctl show postgresql-18 -p LimitNOFILE
systemctl show haproxy -p LimitNOFILE
```

必要ならoverrideを検討する。

### 18.10 監視項目

同時300ユーザー想定では、以下を継続監視する。

DB接続数:

```sql
SELECT state, count(*)
FROM pg_stat_activity
GROUP BY state
ORDER BY state;
```

ユーザー別接続数:

```sql
SELECT usename, application_name, client_addr, state, count(*)
FROM pg_stat_activity
GROUP BY usename, application_name, client_addr, state
ORDER BY count(*) DESC;
```

遅いSQL:

```sql
SELECT pid, usename, client_addr, now() - query_start AS duration, query
FROM pg_stat_activity
WHERE state = 'active'
  AND now() - query_start > interval '5 seconds'
ORDER BY duration DESC;
```

dead tuple:

```sql
SELECT relname, n_live_tup, n_dead_tup
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC;
```

replication lag:

```sql
SELECT application_name, client_addr, state, sync_state,
       write_lag, flush_lag, replay_lag
FROM pg_stat_replication;
```

DBサイズ:

```sql
SELECT pg_size_pretty(pg_database_size('graphicalmatrix')) AS db_size;
SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC;
```

### 18.11 負荷試験

本番適用前に、以下の負荷試験を行う。

```text
1. 50,000ユーザー分のgraphicalmatrix_enrollmentを投入
2. list/show/set-method/reset/csv-exportの管理CLI性能を確認
3. IdP 2台からMFA認証相当のDB read/updateを並列実行
4. 同時300ユーザー相当のログイン試験
5. 失敗回数更新、lock、unlock、last_success_at更新のwrite負荷を確認
6. WebAuthn credentialありユーザーのログイン試験
7. DB2停止時、DB1継続動作を確認
8. DB1停止時、手動failover後の性能を確認
```

試験時に見る値:

```text
平均応答時間
95 percentile / 99 percentile
DB CPU
DB memory
disk I/O latency
active connection数
idle connection数
lock wait
dead tuple
replication lag
HAProxy current sessions
Jetty thread使用率
```

### 18.12 適用順序

推奨順序:

```text
1. 監視を先に入れる
2. PostgreSQL設定を控えめに変更
3. max_connectionsを150程度に設定
4. IdP/API/Admin Tools側の接続数を制御
5. 必要ならPgBouncerを追加
6. 負荷試験
7. 実測に合わせてwork_mem、autovacuum、WAL設定を調整
```

最初から大きな値にしない。
特に `max_connections` と `work_mem` はメモリ消費に直結するため、
同時に大きくしない。

## 19. PostgreSQL TLS実適用記録

`DB_test` 配下で発行した3種類の証明書をDB環境へ適用した。

対象:

```text
VIP: db.example.com  / 192.168.0.64
DB1: db1.example.com / 192.168.0.62
DB2: db2.example.com / 192.168.0.63
CA:  private-ca.example.com
```

証明書SAN:

```text
2faskwdb:  DNS:db.example.com,  IP Address:192.168.0.64
2faskwdb1: DNS:db1.example.com, IP Address:192.168.0.62
2faskwdb2: DNS:db2.example.com, IP Address:192.168.0.63
```

現在のHAProxyはTCP透過であり、TLS終端はPostgreSQL側で行う。
IdPはDB VIP `192.168.0.64` へ接続するため、PostgreSQLで実際に使用する
サーバ証明書はDB VIP用証明書とした。

DB1/DB2個別証明書は各ノードへ配置済みだが、直近のVIP経由接続では使用しない。
DB1/DB2へ直接 `verify-full` 接続する運用に変更する場合は、各ノード証明書へ切り替える。

DB1/DB2共通で配置:

```bash
sudo install -d -m 0700 -o postgres -g postgres /var/lib/pgsql/18/data/certs
sudo install -d -m 0700 -o postgres -g postgres /var/lib/pgsql/18/data/certs/node

sudo install -m 0644 -o postgres -g postgres /tmp/db-vip.crt \
  /var/lib/pgsql/18/data/certs/db-vip.crt
sudo install -m 0600 -o postgres -g postgres /tmp/db-vip.key \
  /var/lib/pgsql/18/data/certs/db-vip.key
sudo install -m 0644 -o postgres -g postgres /tmp/db-ca.crt \
  /var/lib/pgsql/18/data/certs/db-ca.crt
```

DB1個別証明書:

```bash
sudo install -m 0644 -o postgres -g postgres /tmp/db1.crt \
  /var/lib/pgsql/18/data/certs/node/db1.crt
sudo install -m 0600 -o postgres -g postgres /tmp/db1.key \
  /var/lib/pgsql/18/data/certs/node/db1.key
```

DB2個別証明書:

```bash
sudo install -m 0644 -o postgres -g postgres /tmp/db2.crt \
  /var/lib/pgsql/18/data/certs/node/db2.crt
sudo install -m 0600 -o postgres -g postgres /tmp/db2.key \
  /var/lib/pgsql/18/data/certs/node/db2.key
```

DB1/DB2の `/var/lib/pgsql/18/data/postgresql.conf` へ以下を設定した。

```conf
ssl = on
ssl_cert_file = 'certs/db-vip.crt'
ssl_key_file = 'certs/db-vip.key'
ssl_ca_file = 'certs/db-ca.crt'
ssl_min_protocol_version = 'TLSv1.2'
```

反映:

```bash
sudo systemctl reload postgresql-18
```

確認結果:

```text
SHOW ssl                   -> on
SHOW ssl_cert_file          -> certs/db-vip.crt
SHOW ssl_key_file           -> certs/db-vip.key
SHOW ssl_ca_file            -> certs/db-ca.crt
SHOW ssl_min_protocol_version -> TLSv1.2
```

IdPへCA証明書を配置した。

```bash
sudo install -d -m 0750 -o root -g jetty \
  /opt/shibboleth-idp/credentials/db-ssl
sudo install -m 0640 -o root -g jetty /tmp/db-ca.crt \
  /opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
sudo -u jetty test -r /opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

IdPからDB VIPへのSSL接続試験:

```bash
sudo -u jetty env PGPASSWORD="<graphicalmatrix_app password>" \
  /usr/pgsql-18/bin/psql \
  "host=192.168.0.64 port=5432 dbname=graphicalmatrix user=graphicalmatrix_app sslmode=verify-full sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt" \
  -Atqc "SELECT current_user, current_database(), ssl, version
         FROM pg_stat_ssl
         WHERE pid = pg_backend_pid();"
```

結果:

```text
graphicalmatrix_app|graphicalmatrix|t|TLSv1.3
```

IdPの `/opt/shibboleth-idp/conf/graphicalmatrix/db.properties` を更新した。

```properties
graphicalmatrix.db.url=jdbc:postgresql://192.168.0.64:5432/graphicalmatrix?sslmode=verify-full&sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt
```

WebAuthn StorageService用の `/opt/shibboleth-idp/conf/global.xml` も同じ接続先へ更新した。

```xml
p:url="jdbc:postgresql://192.168.0.64:5432/graphicalmatrix?sslmode=verify-full&amp;sslrootcert=/opt/shibboleth-idp/credentials/db-ssl/db-ca.crt"
```

IdP再起動:

```bash
sudo systemctl restart jetty-idp
sudo systemctl is-active jetty-idp
```

確認結果:

```text
active
/idp/status             HTTP 200
/idp/graphicalmatrix/change HTTP 200
graphicalmatrix-db.sh list  正常
```

DB1/DB2の `pg_hba.conf` はTCP接続を `hostssl` に変更し、非SSL接続を拒否するようにした。
HAProxyはDB VIPのTCP透過で `127.0.0.1:5432` へ接続するため、`127.0.0.1/32` も
`hostssl` にしている。

```conf
# TYPE     DATABASE     USER             ADDRESS            METHOD
local      all          all                                 peer
hostssl    all          all              127.0.0.1/32       scram-sha-256
hostssl    all          all              ::1/128            scram-sha-256
hostssl    replication  replicator       192.168.0.62/32   scram-sha-256
hostssl    replication  replicator       192.168.0.63/32   scram-sha-256
```

非SSL接続拒否確認:

```bash
sudo -u jetty env PGPASSWORD="<graphicalmatrix_app password>" \
  /usr/pgsql-18/bin/psql \
  "host=192.168.0.64 port=5432 dbname=graphicalmatrix user=graphicalmatrix_app sslmode=disable" \
  -Atqc "SELECT 1;"
```

結果:

```text
FATAL: pg_hba.conf にホスト"127.0.0.1"、ユーザー"graphicalmatrix_app"、
データベース"graphicalmatrix, 暗号化なし用のエントリがありません
```

DB2のreplication接続は `sslmode=require` に変更した。
パスワードは文書へ記録しない。

```bash
sudo cp -a /var/lib/pgsql/18/data/postgresql.auto.conf \
  /var/lib/pgsql/18/data/postgresql.auto.conf.sslmode.bak.$TS
sudo perl -0pi -e 's/sslmode=prefer/sslmode=require/g' \
  /var/lib/pgsql/18/data/postgresql.auto.conf
sudo systemctl restart postgresql-18
```

DB1でreplicationのSSL状態を確認した。

```sql
SELECT a.usename, a.client_addr, s.ssl, s.version, count(*)
FROM pg_stat_activity a
JOIN pg_stat_ssl s ON a.pid=s.pid
GROUP BY a.usename, a.client_addr, s.ssl, s.version
ORDER BY a.usename, a.client_addr;
```

結果:

```text
replicator | 192.168.0.63 | t | TLSv1.3 | 1
```

Keepalived確認:

```bash
sudo /usr/local/sbin/check_pg_primary.sh && echo keepalived_check_ok
sudo systemctl is-active postgresql-18 haproxy keepalived
```

DB1:

```text
keepalived_check_ok
active
active
active
```

DB2:

```text
pg_is_in_recovery() = t
keepalived_check_not_primary
active
active
active
```
