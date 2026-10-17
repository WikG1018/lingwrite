<p align="center"><a href="CONTRIBUTING.md">简体中文</a> · <b>English</b> · <a href="CONTRIBUTING.zh-TW.md">繁體中文</a> · <a href="CONTRIBUTING.ja.md">日本語</a></p>

# Contributing

Thanks for wanting to help. These guidelines keep reviews quick and leave room for how the project may be licensed in the future.

## Before you start

- Bug fixes can go straight to a pull request. For new features or bigger UI changes, please open an issue first and describe the problem you want to solve, so nobody's work is wasted.
- LingWrite's rule of thumb is "help the author think, don't write for them". In Assist mode, nothing should rewrite the manuscript unless the author confirms each change.
- All data stays on the device. We won't accept analytics, ads, or anything that sends data to a server the author didn't choose.

## Development setup

```bash
cd android
./gradlew assembleDebug testDebugUnitTest -PshotDir=build/shots
```

You need JDK 17 and the Android SDK (compileSdk 37). The first build downloads the MiSans font; see the README. Screenshot output is controlled by `-PshotDir`: the Gradle default, `/workspace/lingwrite-shots`, usually isn't writable on a normal machine, so run `./gradlew testDebugUnitTest -PshotDir=build/shots` locally.

## Pull requests

1. Branch off `main` and use a descriptive name such as `fix/outline-scroll`.
2. Keep each PR to one change. Commit messages should describe what the commit actually does. No empty commits.
3. Run `./gradlew assembleDebug testDebugUnitTest -PshotDir=build/shots` locally. CI runs it again.
4. For UI changes, attach light and dark screenshots. Follow [docs/motion-design.md](docs/motion-design.md) for motion, and **don't use left-edge accent bars** on cards.
5. Start new source files with an SPDX header:
   ```kotlin
   // SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
   // SPDX-License-Identifier: AGPL-3.0-only
   ```
6. Never commit secrets, keystores, API keys, APKs or font files.

## Contributor License Agreement (CLA)

LingWrite is open source under the AGPL-3.0, and the maintainer keeps the option of offering other licenses later, such as a commercial license or a paid store edition. For that reason, **your first pull request needs a signed [CLA](CLA.md)**:

- You keep the copyright in your contribution.
- You allow the maintainer to distribute your contribution under the AGPL-3.0 **and under other licenses**.
- You confirm the work is your own, or that you have the right to submit it.

To sign, add the line "I have read and agree to CLA.md (version 0.2)" to your PR description, and make sure your commit email matches your GitHub account. We may switch to the CLA Assistant bot later.

> Why not a DCO? A DCO only certifies where the code came from. It doesn't give the maintainer permission to distribute it under anything other than the AGPL. Keeping dual licensing possible requires a CLA.

## Translations

Native speakers are very welcome to help with docs and UI translations. Please aim for natural phrasing rather than word-for-word translation, and reuse the existing terms for things like chapter outlines, volume outlines and foreshadowing.
