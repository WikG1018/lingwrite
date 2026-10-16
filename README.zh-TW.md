<p align="center"><a href="README.md">简体中文</a> · <a href="README.en.md">English</a> · <b>繁體中文</b> · <a href="README.ja.md">日本語</a></p>

# 靈寫 LingWrite

一款寫網路小說的 Android App。有章節編輯、設定庫、三層大綱、伏筆清單和一致性檢查，再加上一套「幫你想，不替你寫」的 AI 輔助。

- **資料全在手機裡**：沒有伺服器、不用註冊帳號，也不蒐集任何統計。
- **模型自己挑**：DeepSeek、智譜 GLM、Kimi、Anthropic 相容介面或任何 OpenAI 相容介面都能用，API Key 只會送到你選的服務商。
- **兩種寫法**：輔助模式只給方向、提問題、點出問題，正文每個字都由你來寫；生成模式可以幫你起草，AI 寫的段落會另外標示。

<p>
<img src="docs/screenshots/01-shelf-light.png" width="200" alt="書架">
<img src="docs/screenshots/03-editor-light.png" width="200" alt="編輯器">
<img src="docs/screenshots/31-editor-generate-light.png" width="200" alt="多候選">
<img src="docs/screenshots/04-lore-light.png" width="200" alt="設定卡">
</p>

> App 介面支援簡體中文、繁體中文、英文和日文，預設跟隨系統語言，可在「設定 › 外觀 › 介面語言」切換（Android 13 以上也可在系統的「應用程式語言」中設定）。AI 提示詞保持中文；「AI 輸出語言」預設跟隨作品語言。

## 功能

| | |
|---|---|
| 寫作 | 首行縮排、自動儲存、字數統計、復原、歷史版本與段落比對、本章便條 |
| 設定 | 人物、地點、勢力等六類卡片，別名，依章節生效的狀態，關係圖，地圖標註 |
| 大綱 | 總綱 → 卷綱 → 章綱，多線視圖，黃金三章檢查，網文範本，總綱工作台 |
| AI | 續寫、改寫、擴寫、潤飾、去 AI 腔，多個候選，卡文急救，隨手問，讀者試讀，拆文 |
| 長篇脈絡 | 章摘要與卷摘要、依 token 預算取捨、全文搜尋，可選語意搜尋 |
| 檢查 | 一致性檢查（人設、實力、時間線、關係），伏筆到期提醒，AI 味檢查 |
| 其他 | 統計、匯出 TXT／Markdown、整庫備份、App 內更新（會驗證 SHA-256 與簽章） |

## 下載

到 [Releases](https://github.com/WikG1018/lingwrite/releases) 下載最新的 `lingwrite-vX.Y.Z.apk`。每版的發布說明都附上 `SHA-256:`，同一處也有 `.sha256` 檔，可以自行核對。

需要 Android 12（API 31）以上。正式版簽章憑證的 SHA-256：

```
f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52
```

## 從原始碼建置

需要 JDK 17 和 Android SDK（compileSdk 37）。

```bash
git clone https://github.com/WikG1018/lingwrite.git
cd lingwrite/android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew assembleRelease        # 沒有簽章設定時改用 debug 金鑰
```

- **字型**：MiSans 不隨儲存庫散布（原因見 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)）。第一次建置時會從小米官方網址下載字型包（約 220 MB），快取在 `~/.gradle/caches/lingwrite-misans/`，並逐一比對 SHA-256。離線或加上 `-Plingwrite.misans=off` 時會改用系統字型，建置一樣能完成。
- **簽章**：儲存庫裡沒有任何簽章金鑰。自己建置的 release 版用 debug 金鑰簽章，無法覆蓋安裝官方版本。

## 隱私

作品、設定、大綱和備份都只存在你的手機上。API Key 以 Android Keystore 加密，不會放進備份。使用 AI 時，相關內文和設定只會送到你自己設定的服務商；檢查更新只會連到 GitHub 或你選的鏡像站。詳見 [PRIVACY.zh-TW.md](PRIVACY.zh-TW.md)。

## 參與貢獻

歡迎提出 Issue 和 PR。送出 PR 前請先閱讀 [CONTRIBUTING.zh-TW.md](CONTRIBUTING.zh-TW.md)：為了保留雙重授權的可能，第一次貢獻需要簽署 [CLA](CLA.md)。行為準則見 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)，安全問題請依 [SECURITY.zh-TW.md](SECURITY.zh-TW.md) 私下回報。

## 授權

原始碼採用 [GNU AGPL-3.0](LICENSE) 授權（`SPDX-License-Identifier: AGPL-3.0-only`）。第三方元件依各自的授權條款，見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。**MiSans 字型不適用 AGPL**，而是受小米《[MiSans 字體知識產權許可協議](https://hyperos.mi.com/font/zh/download)》規範（© 小米科技有限責任公司）。

## 商標

「灵写／靈寫」「LingWrite」名稱與 App 圖示不在 AGPL 授權範圍內。散布修改版時，請改名、換圖示，並更換套件名稱 `com.lingwrite.app`。詳見 [TRADEMARK.zh-TW.md](TRADEMARK.zh-TW.md)。
