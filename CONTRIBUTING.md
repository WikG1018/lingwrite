<p align="center"><b>简体中文</b> · <a href="CONTRIBUTING.en.md">English</a> · <a href="CONTRIBUTING.zh-TW.md">繁體中文</a> · <a href="CONTRIBUTING.ja.md">日本語</a></p>

# 参与贡献

谢谢你愿意帮忙。下面这些约定是为了让评审快一点，也为了项目以后的授权安排留出余地。

## 开始之前

- 修 bug 可以直接提 PR。新功能或较大的界面改动，请先开 Issue 说清楚想解决什么，避免白做。
- 灵写的原则是「帮作者想，不替作者写」。辅助模式下不应出现直接改写正文、而作者没有逐条确认的路径。
- 所有数据只存在本机。不接受加入统计、广告或任何把数据发往作者没选的服务器的改动。

## 开发环境

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
```

需要 JDK 17、Android SDK（compileSdk 37）。首次构建会下载 MiSans 字体，见 README。截图测试默认输出到 `/workspace/lingwrite-shots`，可以用 `-PshotDir=<目录>` 改。

## 提交 PR

1. 从 `main` 拉分支，名字写清楚做什么，比如 `fix/outline-scroll`。
2. 一个 PR 只做一件事。提交信息要和改动对得上，不要有空提交。
3. 本地跑过 `./gradlew assembleDebug testDebugUnitTest`。CI 会再跑一遍。
4. 动了界面的，附上浅色、深色截图。动效遵守 [docs/motion-design.md](docs/motion-design.md)，卡片**不要用左侧色条**。
5. 新文件开头加 SPDX 头：
   ```kotlin
   // SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
   // SPDX-License-Identifier: AGPL-3.0-only
   ```
6. 绝对不要提交密钥、keystore、API Key、APK 或字体文件。

## 贡献者许可协议（CLA）

灵写以 AGPL-3.0 开源。维护者保留以后提供其他授权（比如商业授权、应用商店付费版）的可能。为此，**第一次提交 PR 时需要签署 [CLA](CLA.md)**：

- 你保留自己贡献的版权；
- 你授权维护者以 AGPL-3.0 **以及其他授权方式**分发你的贡献；
- 你确认贡献是你自己的作品，或者你有权提交。

签署方法：在 PR 描述里写一行「我已阅读并同意 CLA.md（版本 0.1）」，并确保提交使用的邮箱和 GitHub 账号一致。以后可能换成 CLA Assistant 机器人自动记录。

> 为什么不用 DCO？DCO 只能证明来源，不能授权维护者用 AGPL 以外的协议分发。要保留双授权，需要 CLA。

## 翻译

文档和界面翻译欢迎母语者参与。请写自然的表达，不要逐字直译。专有名词（章纲、卷纲、伏笔等）尽量沿用已有译法。 界面文案的修改方式见 [docs/i18n.md](docs/i18n.md)。
