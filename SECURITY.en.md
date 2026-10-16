<p align="center"><a href="SECURITY.md">简体中文</a> · <b>English</b> · <a href="SECURITY.zh-TW.md">繁體中文</a> · <a href="SECURITY.ja.md">日本語</a></p>

# Security policy

## Supported versions

Security fixes are released only for the newest official release, the one marked Latest under [Releases](https://github.com/WikG1018/lingwrite/releases).

## Reporting a vulnerability

**Please don't report security problems in public issues.** Use GitHub's private vulnerability reporting instead:

👉 [https://github.com/WikG1018/lingwrite/security/advisories/new](https://github.com/WikG1018/lingwrite/security/advisories/new)

Please include as much of this as you can:

- The affected version, device and Android version
- Steps to reproduce, or proof-of-concept code
- The likely impact, for example API key exposure, a bypass of the update signature check, or tampering with backup files

The maintainer will reply within 7 days. Once the issue is confirmed, a fix will be released as soon as possible, and with your permission you'll be credited in the release notes.

## What we care about most

- How API keys are stored and sent (Android Keystore; sent only to the provider the user chose)
- The SHA-256 and signing-certificate checks for in-app updates (official certificate SHA-256: `f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52`)
- Path traversal or malicious zip files during backup import
- Any path that sends data to a server the user didn't choose

## Out of scope

- Issues that require a rooted or unlocked device
- How a user-chosen AI provider handles the data it receives
