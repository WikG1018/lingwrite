<p align="center"><a href="README.md">简体中文</a> · <b>English</b> · <a href="README.zh-TW.md">繁體中文</a> · <a href="README.ja.md">日本語</a></p>

# LingWrite (灵写)

An Android app for writing serialized web fiction. It gives you a chapter editor, a lore library, three-level outlines, a foreshadowing ledger and consistency checks. On top of that sits an AI assistant that is built to help you think, not to write the book for you.

- **Your data stays on your phone.** There is no server, no account and no analytics.
- **Bring your own model.** Use DeepSeek, Zhipu GLM, Kimi, any Anthropic-compatible API or any OpenAI-compatible API. Your key is sent only to the provider you pick.
- **Two ways to write.** In *Assist* mode the AI only suggests directions, asks questions and points out problems, and every word of the manuscript is yours. In *Generate* mode it can draft for you, and AI-written paragraphs are marked as such.

<p>
<img src="docs/screenshots/01-shelf-light.png" width="200" alt="Bookshelf">
<img src="docs/screenshots/03-editor-light.png" width="200" alt="Editor">
<img src="docs/screenshots/31-editor-generate-light.png" width="200" alt="Candidates">
<img src="docs/screenshots/04-lore-light.png" width="200" alt="Lore card">
</p>

> The app UI is available in Simplified Chinese, English, Traditional Chinese and Japanese. It follows the system language by default; change it in Settings › Appearance › Display language (or the system’s per-app language page on Android 13+). AI prompts stay in Chinese, and “AI output language” in Settings follows the work’s language by default.

## Features

| | |
|---|---|
| Writing | First-line indent, autosave, word count, undo stack, version history with paragraph diff, chapter notes |
| Lore | Six card types (characters, places, factions…), aliases, per-chapter state, relationship graph, map pins |
| Outline | Synopsis → volume → chapter outlines, multi-thread view, "golden first three chapters" check, templates |
| AI | Continue / rewrite / expand / polish / de-AI, multiple candidates, writer's-block rescue, quick ask, beta-reader simulation, structure breakdown |
| Long-form context | Chapter and volume summaries, token-budgeted context, full-text search, optional semantic search |
| Checks | Consistency check (persona, power level, timeline, relationships), foreshadowing reminders, AI-flavor check |
| Misc | Stats, TXT/Markdown export, full backup, in-app updates (SHA-256 and signature verified) |

## Download

Get the latest `lingwrite-vX.Y.Z.apk` from [Releases](https://github.com/WikG1018/lingwrite/releases). Each release note includes a `SHA-256:` line, and a `.sha256` file sits next to the APK so you can check it yourself.

Requires Android 12 (API 31) or later. SHA-256 of the official signing certificate:

```
f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52
```

## Building from source

You need JDK 17 and the Android SDK (compileSdk 37).

```bash
git clone https://github.com/WikG1018/lingwrite.git
cd lingwrite/android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew assembleRelease        # falls back to the debug key if no signing config is found
```

- **Font.** MiSans is not distributed with this repository (see [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)). The first build downloads the official font package from Xiaomi, which is about 220 MB, and caches it in `~/.gradle/caches/lingwrite-misans/`. Each file is checked against its SHA-256. If you are offline, or you pass `-Plingwrite.misans=off`, the app uses the system font instead and the build still succeeds.
- **Signing.** The repository contains no signing keys. A release build you make yourself is signed with the debug key and cannot be installed over the official app.

## Privacy

Your books, lore, outlines and backups live only on your device. API keys are encrypted with the Android Keystore and are never included in backups. When you use AI features, the relevant text goes only to the provider you configured. Update checks talk only to GitHub or the mirror you chose. See [PRIVACY.en.md](PRIVACY.en.md).

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.en.md](CONTRIBUTING.en.md) first. To keep dual licensing possible, first-time contributors are asked to sign the [CLA](CLA.md). Please follow the [Code of Conduct](CODE_OF_CONDUCT.md), and report security issues privately as described in [SECURITY.en.md](SECURITY.en.md).

## License

The source code is licensed under the [GNU AGPL-3.0](LICENSE) (`SPDX-License-Identifier: AGPL-3.0-only`). Third-party components keep their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). **The MiSans font is not covered by the AGPL.** It is governed by Xiaomi's [MiSans Fonts Intellectual Property License Agreement](https://hyperos.mi.com/font/en/download) (© Xiaomi Inc.).

## Trademarks

The names "灵写" and "LingWrite" and the app icon are not licensed under the AGPL. If you distribute a modified version, give it a different name and icon and change the package name `com.lingwrite.app`. See [TRADEMARK.en.md](TRADEMARK.en.md).
