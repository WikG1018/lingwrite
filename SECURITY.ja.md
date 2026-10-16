<p align="center"><a href="SECURITY.md">简体中文</a> · <a href="SECURITY.en.md">English</a> · <a href="SECURITY.zh-TW.md">繁體中文</a> · <b>日本語</b></p>

# セキュリティポリシー

## サポート対象のバージョン

セキュリティ修正は、[Releases](https://github.com/WikG1018/lingwrite/releases) で Latest と表示されている最新の正式版にのみ提供します。

## 脆弱性の報告

**公開の Issue にはセキュリティ上の問題を書かないでください。** GitHub の非公開の脆弱性報告機能をご利用ください。

👉 [https://github.com/WikG1018/lingwrite/security/advisories/new](https://github.com/WikG1018/lingwrite/security/advisories/new)

できるだけ次の情報を添えてください。

- 影響を受けるバージョン、端末、Android のバージョン
- 再現手順、または概念実証（PoC）のコード
- 想定される影響（API キーの漏えい、アップデートの署名検証の回避、バックアップファイルの改ざんなど）

メンテナーが 7 日以内に返信します。問題を確認したら、できるだけ早く修正版を公開します。ご了承いただければ、リリースノートでお名前を挙げて感謝を記します。

## 特に重視している点

- API キーの保存と送信（Android Keystore を使い、ユーザーが選んだプロバイダーにだけ送信）
- アプリ内アップデートでの SHA-256 と署名証明書の検証（公式証明書の SHA-256：`f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52`）
- バックアップ読み込み時のパストラバーサルや悪意のある zip
- ユーザーが選んでいないサーバーにデータを送ってしまう経路

## 対象外

- root 化やブートローダーのアンロックが必要な問題
- ユーザー自身が選んだ AI プロバイダーによるデータの取り扱い
