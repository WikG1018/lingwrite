<p align="center"><a href="CONTRIBUTING.md">简体中文</a> · <a href="CONTRIBUTING.en.md">English</a> · <b>繁體中文</b> · <a href="CONTRIBUTING.ja.md">日本語</a></p>

# 參與貢獻

謝謝你願意幫忙。以下約定一方面讓審查快一點，一方面也替專案日後的授權安排保留空間。

## 開始之前

- 修 bug 可以直接發 PR。新功能或較大的介面調整，請先開 Issue 說明想解決的問題，免得白忙一場。
- 靈寫的原則是「幫作者想，不替作者寫」。輔助模式下，不應該出現未經作者逐條確認就改動正文的路徑。
- 所有資料只存在手機上。不接受加入統計、廣告，或把資料送到作者沒選的伺服器的改動。

## 開發環境

```bash
cd android
./gradlew assembleDebug testDebugUnitTest -PshotDir=build/shots
```

需要 JDK 17 與 Android SDK（compileSdk 37）。第一次建置會下載 MiSans 字型，詳見 README。截圖輸出目錄由 `-PshotDir` 控制：Gradle 預設的 `/workspace/lingwrite-shots` 在一般本機通常無法寫入，本機建議用 `./gradlew testDebugUnitTest -PshotDir=build/shots`。

## 送出 PR

1. 從 `main` 開分支，名稱說明要做什麼，例如 `fix/outline-scroll`。
2. 一個 PR 只做一件事。提交訊息要和內容相符，不要有空提交。
3. 先在本機跑過 `./gradlew assembleDebug testDebugUnitTest -PshotDir=build/shots`，CI 也會再跑一次。
4. 改到介面的話，請附淺色、深色截圖。動效請遵守 [docs/motion-design.md](docs/motion-design.md)，卡片**不要用左側色條**。
5. 新檔案開頭加上 SPDX 標頭：
   ```kotlin
   // SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
   // SPDX-License-Identifier: AGPL-3.0-only
   ```
6. 千萬不要提交金鑰、keystore、API Key、APK 或字型檔。

## 貢獻者授權協議（CLA）

靈寫以 AGPL-3.0 開源，維護者保留日後提供其他授權（例如商業授權、應用程式商店付費版）的可能。因此，**第一次發 PR 時需要簽署 [CLA](CLA.md)**：

- 你保有自己貢獻的著作權；
- 你同意維護者以 AGPL-3.0 **及其他授權方式**散布你的貢獻；
- 你確認貢獻是你本人的作品，或你有權提交。

簽署方式：在 PR 說明中加上一行「我已閱讀並同意 CLA.md（版本 0.2）」，並確認提交所用的電子郵件與 GitHub 帳號一致。日後可能改用 CLA Assistant 機器人自動記錄。

> 為什麼不用 DCO？DCO 只能證明來源，無法授權維護者以 AGPL 以外的條款散布。要保留雙重授權，就需要 CLA。

## 翻譯

歡迎母語使用者協助翻譯文件和介面。請用自然的說法，不要逐字直譯；章綱、卷綱、伏筆等專有名詞請盡量沿用既有譯法。
