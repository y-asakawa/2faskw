## Shibboleth IdP 5.2.2 upgrade 5.2.3

公式Upgradingでも、既存インストール先の上に新バージョンを入れること、事前に idp.home をバックアップすること、同じインストール先を指定すること、最後に build.sh でWARを再構築することが示されています。5.2.3配布物は latest5 にあります。

## バックアップ
```
sudo mkdir -p /root/idp-backup
sudo tar czf /root/idp-backup/shibboleth-idp-$(date +%Y%m%d%H%M%S).tgz \
  -C /opt \
  shibboleth-idp
```
DBも念のため取得済みであることを確認します。GraphicalMatrix/TOTP/WebAuthnがDB利用なら、DBバックアップを推奨します。

## 5.2.3を取得
```
sudo curl -O https://shibboleth.net/downloads/identity-provider/latest5/shibboleth-identity-provider-5.2.3.tar.gz
sudo curl -O https://shibboleth.net/downloads/identity-provider/latest5/shibboleth-identity-provider-5.2.3.tar.gz.sha256
```
```
sha256sum -c shibboleth-identity-provider-5.2.3.tar.gz.sha256
```

## Jetty停止
```
sudo systemctl stop jetty-idp.service
sudo systemctl status jetty-idp.service
```

## 展開、インストーラー起動
```
sudo tar xzf shibboleth-identity-provider-5.2.3.tar.gz
cd shibboleth-identity-provider-5.2.3

sudo ./bin/install.sh
```

## WAR再構築
```
sudo /opt/shibboleth-idp/bin/build.sh
```

## Jetty起動
```
sudo systemctl start jetty-idp.service
sudo systemctl status jetty-idp.service
```

## ログ確認
```
sudo tail -n 200 /opt/shibboleth-idp/logs/idp-process.log
sudo journalctl -u jetty-idp.service -n 200 --no-pager
```

## プラグイン動作確認
```
sudo /opt/shibboleth-idp/bin/build.sh
sudo /opt/shibboleth-idp/bin/graphicalmatrix-db.sh list
```
