<p align="center"><a href="SECURITY.md">简体中文</a> · <a href="SECURITY.en.md">English</a> · <b>繁體中文</b> · <a href="SECURITY.ja.md">日本語</a></p>

# 安全政策

## 支援的版本

只有 [Releases](https://github.com/WikG1018/lingwrite/releases) 中標示為 Latest 的最新正式版會收到安全性修正。

## 回報漏洞

**請不要在公開的 Issue 回報安全問題。** 請改用 GitHub 的私密漏洞回報：

👉 [https://github.com/WikG1018/lingwrite/security/advisories/new](https://github.com/WikG1018/lingwrite/security/advisories/new)

請盡量提供：

- 受影響的版本、裝置與 Android 版本；
- 重現步驟，或概念驗證程式碼；
- 可能的影響，例如 API Key 外洩、繞過更新檔的簽章驗證、備份檔遭竄改等。

維護者會在 7 天內回覆，確認問題後會盡快發布修正版本，並在你同意的前提下於發布說明中致謝。

## 我們特別在意的問題

- API Key 的儲存與傳送（Android Keystore，只送往使用者選擇的服務商）；
- App 內更新的 SHA-256 與簽章憑證驗證（正式憑證 SHA-256：`f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52`）；
- 匯入備份時的路徑穿越、惡意 zip；
- 任何會把資料送到使用者沒選的伺服器的路徑。

## 不屬於安全問題

- 需要 root 或已解鎖裝置才能利用的問題；
- 使用者自選的 AI 服務商如何處理資料。
