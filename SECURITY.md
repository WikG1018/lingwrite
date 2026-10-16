<p align="center"><b>简体中文</b> · <a href="SECURITY.en.md">English</a> · <a href="SECURITY.zh-TW.md">繁體中文</a> · <a href="SECURITY.ja.md">日本語</a></p>

# 安全策略

## 支持的版本

只有 [Releases](https://github.com/WikG1018/lingwrite/releases) 中标记为 Latest 的最新正式版会收到安全修复。

## 报告漏洞

**请不要在公开的 Issue 里报告安全问题。** 请通过 GitHub 的私密漏洞报告提交：

👉 [https://github.com/WikG1018/lingwrite/security/advisories/new](https://github.com/WikG1018/lingwrite/security/advisories/new)

请尽量写清楚：

- 受影响的版本和设备、系统版本；
- 复现步骤，或概念验证代码；
- 可能造成的影响，比如 API Key 泄露、绕过更新包签名校验、备份文件被篡改等。

维护者会在 7 天内回复，确认问题后尽快发布修复版本，并在你同意的前提下在发布说明里致谢。

## 我们特别关心的问题

- API Key 的存储与传输（Android Keystore、只发往用户选择的服务商）；
- 应用内更新的 SHA-256 与签名证书校验（正式证书 SHA-256：`f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52`）；
- 备份导入时的路径穿越、恶意 zip；
- 任何把数据发往用户没有选择的服务器的路径。

## 不属于安全问题

- 需要 root 或已经解锁的设备才能利用的问题；
- 用户自己选的 AI 服务商怎么处理数据。
