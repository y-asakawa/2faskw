# GraphicalMatrix MFA 負荷試験サーバ構築・試験手順

## 1. 目的

この手順書は、GraphicalMatrix MFA / Shibboleth IdP 構成に対して負荷試験を行うための、試験用サーバ構築から試験実行、結果確認までの設計手順をまとめる。

本手順は設計用であり、実サーバへの設定投入は別途実施する。

## 2. 推奨構成

負荷試験ツールは、IdP、SP、DBとは別サーバに導入する。

```text
Load Test Server
  - k6
  - JMeter optional
  - jq / curl / openssl / git

        |
        | HTTPS
        v

LB / SP / IdP
        |
        v
DB VIP / HAProxy / PostgreSQL Primary
```

IdPやDBと同じサーバで負荷試験ツールを動かすと、試験ツール自身のCPU、メモリ、ネットワークI/Oが混ざるため、性能劣化の原因を切り分けにくくなる。

## 3. 試験対象

最初からSAMLログイン全体を大量実行するのではなく、段階的に確認する。

```text
1. IdP / SP の単純HTTP応答
2. GraphicalMatrix静的リソース応答
3. GraphicalMatrix challenge作成相当のHTTP応答
4. GraphicalMatrix verify相当のHTTP/API応答
5. SPからIdPへのログイン開始フロー
6. Password + GraphicalMatrix のログインフロー
7. API有効時の管理API
8. DB VIP切替、DB1/DB2障害時の回復確認
```

WebAuthnとTOTPは、人間の実デバイス操作やワンタイムコードが絡むため、k6だけで完全な大量自動ログイン試験を行う対象にはしない。

## 4. ツール選定

### 4.1 k6

主用途:

```text
HTTP単体負荷
API負荷
静的ファイル配信負荷
段階的な同時接続試験
CI向けの再現可能な試験
```

今回の標準ツールとして扱う。

### 4.2 Apache JMeter

主用途:

```text
SAML Redirect / POST / Cookie / Sessionを含む複雑なログインフロー
GUIでのシナリオ作成
HTMLレポート作成
```

SP -> IdP -> MFA -> SP戻りのフロー全体を測る場合に利用する。

### 4.3 Locust / Gatling

PythonやJava/Kotlin/Scalaでシナリオをコード管理したい場合の候補。
PoC初期段階では必須ではない。

## 5. 負荷試験サーバ要件

想定規模:

```text
ユーザー数: 50,000
想定同時接続: 300
IdP: 2台
DB: 2台 + VIP
```

推奨スペック:

```text
CPU: 4 core以上
Memory: 8GB以上
Disk: 50GB以上
Network: IdP/SP/DBと同一ネットワークまたは低遅延ネットワーク
OS: Rocky Linux / AlmaLinux / RHEL系を推奨
```

大量試験では、試験サーバ側の上限も確認する。

```bash
ulimit -n
sysctl net.ipv4.ip_local_port_range
sysctl net.ipv4.tcp_tw_reuse
```

## 6. 事前確認

負荷試験前に、以下を確認する。

```text
1. 試験対象URL
2. 試験用ユーザー
3. 試験用ユーザーのMFA方式
4. 試験対象が本番ではなく検証環境であること
5. 試験時間帯
6. 停止基準
7. 監視方法
8. ログ保存先
```

例:

```text
IdP: https://idp.example.com/idp/
SP:  https://sp.example.com/
DB VIP: 192.168.81.64
```

## 7. k6 インストール手順

Rocky Linux / RHEL系の例:

```bash
sudo dnf install -y dnf-plugins-core

sudo dnf config-manager --add-repo https://dl.k6.io/rpm/repo.rpm

sudo dnf install -y k6

k6 version
```

補助ツール:

```bash
sudo dnf install -y curl jq git bind-utils openssl
```

## 8. JMeter インストール手順 optional

JMeterを利用する場合はJavaが必要。

```bash
sudo dnf install -y java-21-openjdk java-21-openjdk-devel unzip

java -version
```

JMeter本体はApache公式配布物を利用する。
導入時は、利用時点の最新版URLを確認して取得する。

配置例:

```bash
sudo mkdir -p /opt/jmeter
sudo chown user:user /opt/jmeter
```

## 9. ディレクトリ構成

負荷試験サーバ上に以下を作成する。

```text
/opt/graphicalmatrix-loadtest/
  scripts/
    smoke-idp.js
    average-idp.js
    spike-idp.js
    api-health.js
  data/
    users.csv
  results/
  logs/
```

作成例:

```bash
sudo mkdir -p /opt/graphicalmatrix-loadtest/{scripts,data,results,logs}
sudo chown -R user:user /opt/graphicalmatrix-loadtest
```

## 10. k6 基本スクリプト例

IdP status確認の例:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,
  duration: '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  const res = http.get('https://idp.example.com/idp/status');
  check(res, {
    'status is 200 or 403': (r) => r.status === 200 || r.status === 403,
  });
  sleep(1);
}
```

保存例:

```text
/opt/graphicalmatrix-loadtest/scripts/smoke-idp.js
```

実行例:

```bash
cd /opt/graphicalmatrix-loadtest
k6 run scripts/smoke-idp.js
```

## 11. 試験段階

### 11.1 Smoke test

目的:

```text
URL疎通
証明書
DNS
HTTPステータス
ログ出力
```

例:

```bash
k6 run --vus 1 --duration 1m scripts/smoke-idp.js
```

### 11.2 Average load test

目的:

```text
通常負荷での応答時間確認
```

例:

```bash
k6 run --vus 50 --duration 10m scripts/average-idp.js
```

### 11.3 Target load test

目的:

```text
想定同時接続300相当の確認
```

例:

```bash
k6 run --vus 300 --duration 15m scripts/target-idp.js
```

### 11.4 Stress test

目的:

```text
どこで性能限界に近づくか確認
```

例:

```bash
k6 run scripts/stress-idp.js
```

### 11.5 Soak test

目的:

```text
長時間稼働時のメモリリーク、DB接続増加、GC増加を確認
```

例:

```bash
k6 run --vus 100 --duration 2h scripts/soak-idp.js
```

## 12. 停止基準

以下に該当した場合は、試験を停止する。

```text
HTTP 5xx が継続して1%以上
IdPのCPUが継続して90%以上
DB PrimaryのCPUが継続して90%以上
DB接続数がmax_connectionsに接近
DB lock waitが継続発生
replication lagが継続増加
Jetty thread枯渇
JVM Full GCまたは長時間GC pauseが継続
LDAP応答遅延が継続
```

停止例:

```bash
Ctrl-C
```

バックグラウンド実行している場合:

```bash
pkill -f 'k6 run'
```

## 13. 監視項目

### 13.1 IdP

```bash
sudo journalctl -u jetty-idp -f
sudo tail -f /opt/shibboleth-idp/logs/idp-process.log
sudo tail -f /opt/shibboleth-idp/logs/idp-audit.log
sudo tail -f /opt/shibboleth-idp/logs/graphicalmatrix-audit.log
```

確認値:

```text
HTTP応答時間
HTTP 4xx / 5xx
Jetty thread
JVM heap
GC pause
LDAP認証時間
GraphicalMatrix認証成功/失敗件数
```

### 13.2 DB

```sql
SELECT state, count(*)
FROM pg_stat_activity
GROUP BY state
ORDER BY state;

SELECT pid, usename, datname, state, wait_event_type, wait_event, query
FROM pg_stat_activity
WHERE datname = 'graphicalmatrix'
ORDER BY pid;

SELECT application_name, state, sync_state, write_lag, flush_lag, replay_lag
FROM pg_stat_replication;
```

確認値:

```text
active connection
idle connection
lock wait
slow query
replication lag
dead tuple
disk I/O latency
```

### 13.3 HAProxy / VIP

```bash
sudo journalctl -u haproxy -f
sudo journalctl -u keepalived -f
```

確認値:

```text
DB VIPがどちらにいるか
HAProxy backendがPrimaryを向いているか
切替時にIdPが復旧するか
```

## 14. 結果保存

k6の標準出力を保存する。

```bash
mkdir -p /opt/graphicalmatrix-loadtest/results/$(date +%Y%m%d)

k6 run scripts/target-idp.js \
  2>&1 | tee /opt/graphicalmatrix-loadtest/results/$(date +%Y%m%d)/target-idp-$(date +%H%M%S).log
```

JSON形式で保存する場合:

```bash
k6 run --summary-export /opt/graphicalmatrix-loadtest/results/summary.json scripts/target-idp.js
```

保存する情報:

```text
実行日時
対象URL
シナリオ名
vus
duration
平均応答時間
p95 / p99
HTTP失敗率
IdP CPU / memory
DB CPU / memory
DB接続数
replication lag
エラー内容
```

## 15. テストデータ

負荷試験用ユーザーを用意する。

```text
loadtest00001
loadtest00002
...
```

GraphicalMatrixのみの負荷を確認する場合は、MFA方式をGraphicalMatrixに統一する。

```text
mfa_method = GraphicalMatrix
force_sequence_change = 0
sequence = 試験用固定値
```

WebAuthn / TOTPは大量自動試験に向かないため、機能確認を別枠で実施する。

## 16. API負荷試験

管理APIを試験する場合は、検証環境でのみ有効化する。

確認事項:

```text
graphicalmatrix.api.enabled = true
Bearer token
接続元IP制限
HTTPS
firewalld / LB制限
```

API負荷試験では、本番相当データを破壊しないように、試験用ユーザーだけを対象にする。

## 17. SAMLログインフロー全体

SP -> IdP -> Password -> MFA -> SP戻りのフロー全体は、k6だけではシナリオ作成が複雑になりやすい。

この試験ではApache JMeterを候補にする。

JMeterで確認するもの:

```text
Redirect
POST binding
Cookie
RelayState
CSRF token相当のhidden項目
Session維持
ログイン成功後のSP画面
```

## 18. レポート観点

試験結果は以下の観点で整理する。

```text
1. 目標300同時接続でHTTP 5xxが継続しないこと
2. p95応答時間が許容範囲内であること
3. DB接続数が設計上限内であること
4. IdPのJVM heap / GCが安定していること
5. LDAP応答がボトルネックになっていないこと
6. GraphicalMatrix認証のwrite負荷でlock waitが増えないこと
7. DB standbyのreplication lagが許容範囲内であること
8. DB VIP切替後にIdPが再接続できること
```

## 19. 実施順序

推奨順序:

```text
1. 負荷試験サーバ構築
2. k6導入
3. curlで疎通確認
4. k6 smoke test
5. k6 average load test
6. k6 target load test
7. IdP / DB / HAProxyログ確認
8. 必要に応じてJMeterでSAMLフロー試験
9. チューニング値を調整
10. 再試験
11. 結果をINSTALL_AP.md / INSTALL_DB.mdのチューニング章へ反映
```

## 20. 注意事項

```text
本番環境へ直接高負荷をかけない。
試験前にDBバックアップを取得する。
試験ユーザーと本番ユーザーを分ける。
API負荷試験では破壊的操作を避ける。
WebAuthn / TOTPは大量自動試験対象から外す。
障害試験は試験時間帯と復旧手順を決めてから行う。
```
