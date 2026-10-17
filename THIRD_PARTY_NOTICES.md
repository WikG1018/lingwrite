# 第三方组件声明 / Third-Party Notices

灵写的源代码以 AGPL-3.0 授权（见 [LICENSE](LICENSE)）。下列第三方组件**不适用 AGPL**，分别受各自的协议约束。
LingWrite's own source code is AGPL-3.0. The third-party components below are **not** under the AGPL and keep their own licenses.

## 1. MiSans 字体（单独授权，不适用 AGPL）

| | |
|---|---|
| 名称 | MiSans（Regular / Medium / Semibold） |
| 版权 | © 小米科技有限责任公司 Xiaomi Inc. |
| 协议 | 《MiSans 字体知识产权许可协议》/ MiSans Fonts Intellectual Property License Agreement |
| 官方地址 | https://hyperos.mi.com/font/zh/download （中文协议全文在页面底部）· https://hyperos.mi.com/font/en/download （English） |
| 在灵写里的用途 | App 界面字体，**未作任何修改** |
| 本仓库是否包含字体文件 | **不包含** |

**单独声明：MiSans 字体文件不适用 AGPL-3.0。** 本仓库的 AGPL 授权不涉及 MiSans，你也不能依据 AGPL 取得 MiSans 的任何权利。

### 依据（协议原文摘录，2026-10-16 取自官方页面）

> 根据本协议的条款和条件，许可方在此授予您一份不可转让的、非独占的、免版税的、可撤销的、全球性的版权许可，使您依照本协议约定使用 MiSans 字体，前提是符合下列条件：
> 1. 您应在软件中特别注明使用了 MiSans 字体。
> 2. 您不得对 MiSans 字体或其任何单独组件进行改编或二次开发。
> 3. 您不得单独将 MiSans 字体或其组件对外租赁、再许可、给予、出借或进一步分发字体软件或其任何副本以及重新分发或售卖。此限制不适用于您使用 MiSans 字体创作的任何其他作品。如您使用 MiSans 字体创作宣传素材、logo、应用 App 等，您有权分发或出售该作品。

### 灵写的处理方式

- **嵌入 App 分发**：第 3 条明确允许分发「使用 MiSans 字体创作的应用 App」，所以官方 APK 内嵌字体。App「关于 → 开源许可」里注明使用了 MiSans 并署名小米，满足第 1 条。
- **随源码仓库分发**：把字体文件放进公开仓库，等于让任何人单独下载字体文件本身，属于第 3 条禁止的「单独……进一步分发字体软件或其任何副本」。因此**仓库里不放字体文件**（`.gitignore` 也已排除）。
- **构建时下载**：`android/app/build.gradle.kts` 在构建时从小米官方地址 `https://hyperos.mi.com/font-download/MiSans.zip` 下载字体包，并逐个核对下面的 SHA-256，确认是未修改的官方文件（满足第 2 条）。下载失败时退回系统字体。CI 也用同样的方式下载，并缓存在 runner 上，不进仓库，也不作为单独产物发布。

| 文件 | SHA-256 |
|---|---|
| MiSans-Regular.ttf | `9c120f0a849bc0aa5048daae2a3c0f6eecd828b5b33fce682a9622833f5feea6` |
| MiSans-Medium.ttf | `b03e98374e971594b0b7a9706d0704241f76e1b88556cdda79c5039ef8a638d1` |
| MiSans-Semibold.ttf | `77c23f31ae124867778344970155a0c8d34a89897dedaab81aeee82ff00a4ce6` |

> 如果你分发修改版：可以照样在构建时从官方地址下载 MiSans 并嵌入你的 App，但要遵守上述协议（尤其是第 1 条署名），也可以换成别的字体。

## 2. 开源库

| 组件 | 协议 |
|---|---|
| Jetpack Compose、AndroidX（Core、Lifecycle、Activity、Navigation、Room、DataStore、WorkManager、Browser、SplashScreen） | Apache License 2.0 |
| Kotlin、kotlinx.coroutines、kotlinx.serialization | Apache License 2.0 |
| OkHttp、Okio（Square） | Apache License 2.0 |
| Haze（Chris Banes） | Apache License 2.0 |
| Material Symbols / Material Icons（Google） | Apache License 2.0 |
| 仅用于测试：JUnit 4 | Eclipse Public License 1.0 |
| 仅用于测试：Robolectric、Roborazzi、MockWebServer | Apache License 2.0 |

Apache License 2.0 全文：https://www.apache.org/licenses/LICENSE-2.0

## 3. 许可全文随 App 分发

Release APK 内置以下许可全文，可在 App「关于 › 开源许可」中直接查看：

- `android/app/src/main/assets/licenses/Apache-2.0.txt` —— Apache License 2.0（覆盖 Compose、Kotlin、OkHttp 等组件）
- `android/app/src/main/assets/licenses/AGPL-3.0.txt` —— GNU AGPL-3.0（灵写自身）
