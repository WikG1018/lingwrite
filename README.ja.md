<p align="center"><a href="README.md">简体中文</a> · <a href="README.en.md">English</a> · <a href="README.zh-TW.md">繁體中文</a> · <b>日本語</b></p>

# 灵写 LingWrite（リンシエ）

Web 小説を書くための Android アプリです。章エディタ、設定資料集、三階層のプロット、伏線管理、整合性チェックを備えています。さらに「考えるのを手伝うけれど、代わりには書かない」AI アシスタントも付いています。

- **データは端末の中だけ**：サーバーもアカウントもなく、利用統計も一切集めません。
- **モデルは自分で選ぶ**：DeepSeek、Zhipu GLM、Kimi、Anthropic 互換 API、OpenAI 互換 API に対応しています。API キーは選んだプロバイダーにだけ送られます。
- **二つの書き方**：アシストモードでは、AI は方向性の提案や質問、問題点の指摘だけを行い、本文はすべてあなたが書きます。生成モードでは下書きを任せられ、AI が書いた段落には印が付きます。

<p>
<img src="docs/screenshots/01-shelf-light.png" width="200" alt="本棚">
<img src="docs/screenshots/03-editor-light.png" width="200" alt="エディタ">
<img src="docs/screenshots/31-editor-generate-light.png" width="200" alt="候補">
<img src="docs/screenshots/04-lore-light.png" width="200" alt="設定カード">
</p>

> 現在、アプリの画面表示は中国語（簡体字）のみです。日本語・英語・繁体字への対応を進めています。

## 主な機能

| | |
|---|---|
| 執筆 | 字下げ、自動保存、文字数カウント、元に戻す、版の履歴と段落単位の比較、章メモ |
| 設定 | 人物・場所・勢力など 6 種類のカード、別名、章ごとの状態、相関図、地図への書き込み |
| プロット | 全体プロット → 巻 → 章、複数ラインの表示、冒頭三章チェック、テンプレート |
| AI | 続き・書き直し・膨らませる・推敲・AI っぽさ除去、複数候補、スランプ救済、ちょっと質問、読者シミュレーション、構成分析 |
| 長編コンテキスト | 章・巻の要約、トークン予算に応じた取捨、全文検索、任意でセマンティック検索 |
| チェック | 整合性チェック（人物像・実力・時系列・関係）、伏線の回収期限リマインダー、AI っぽさチェック |
| その他 | 統計、TXT／Markdown 書き出し、全体バックアップ、アプリ内アップデート（SHA-256 と署名を検証） |

## ダウンロード

[Releases](https://github.com/WikG1018/lingwrite/releases) から最新の `lingwrite-vX.Y.Z.apk` を入手してください。各リリースノートに `SHA-256:` を記載し、同じ場所に `.sha256` ファイルも置いているので、ご自身で照合できます。

Android 12（API 31）以降が必要です。公式署名証明書の SHA-256：

```
f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52
```

## ソースからビルド

JDK 17 と Android SDK（compileSdk 37）が必要です。

```bash
git clone https://github.com/WikG1018/lingwrite.git
cd lingwrite/android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew assembleRelease        # 署名設定がなければ debug キーで署名
```

- **フォント**：MiSans はリポジトリに同梱していません（理由は [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md) を参照）。初回ビルド時に Xiaomi 公式サイトからフォントパッケージ（約 220 MB）をダウンロードして `~/.gradle/caches/lingwrite-misans/` にキャッシュし、ファイルごとに SHA-256 を照合します。オフラインのときや `-Plingwrite.misans=off` を指定したときはシステムフォントを使い、ビルドはそのまま通ります。
- **署名**：リポジトリに署名鍵は含まれていません。自分でビルドした release 版は debug キーで署名されるため、公式版に上書きインストールはできません。

## プライバシー

作品、設定、プロット、バックアップは端末内にだけ保存されます。API キーは Android Keystore で暗号化され、バックアップには含まれません。AI 機能を使うと、関係する本文と設定が、あなたが設定したプロバイダーにだけ送られます。アップデートの確認で通信するのは GitHub か、選んだミラーだけです。詳しくは [PRIVACY.ja.md](PRIVACY.ja.md) をご覧ください。

## コントリビュート

Issue や Pull Request を歓迎します。まず [CONTRIBUTING.ja.md](CONTRIBUTING.ja.md) をお読みください。デュアルライセンスの余地を残すため、初めて貢献する方には [CLA](CLA.md) への署名をお願いしています。[行動規範](CODE_OF_CONDUCT.md) を守ってください。セキュリティ上の問題は [SECURITY.ja.md](SECURITY.ja.md) の手順で非公開にご報告ください。

## ライセンス

ソースコードは [GNU AGPL-3.0](LICENSE)（`SPDX-License-Identifier: AGPL-3.0-only`）で提供しています。サードパーティ製コンポーネントにはそれぞれのライセンスが適用されます。詳しくは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) をご覧ください。**MiSans フォントには AGPL は適用されず**、Xiaomi の「[MiSans フォント知的財産権使用許諾契約](https://hyperos.mi.com/font/en/download)」（© Xiaomi Inc.）に従います。

## 商標

「灵写」「LingWrite」の名称とアプリアイコンは AGPL の許諾対象に含まれません。改変版を配布する場合は、名称とアイコンを変え、パッケージ名 `com.lingwrite.app` も変更してください。詳しくは [TRADEMARK.ja.md](TRADEMARK.ja.md) をご覧ください。
