# 灵写 UI / 动效差距审查（相对 v0.1 高保真 + motion-design + ui-hifi/v0.2）

> 分支：`polish/ui-motion-v0.2.12` · 对照 main `ad732a2`（v0.2.11）
> 对照源：`ui-hifi/*`、`ui-hifi/v0.2/*`、`docs/motion-design.md`、`docs/v0.2/ui-spec.md`、`ui-plan.md`

## 总览

v0.2 功能补得很全，但多处共用组件被后来的路由用 Material3 默认控件或「能用就行」的写法替换，视觉和动效的一致性掉了。书架 / 作品 / 首次使用等早期页面仍接近原稿；AI 面板、候选切换、灵感 / 统计 / 链路 / Chip 选择器是主要短板。

---

## A. 设计 token 与共用组件

| # | 现象 | 文件 | 规格 | 优先级 |
|---|---|---|---|---|
| A1 | `FilterChip`（M3 默认）大量出现在设定库、向导、灵感、伏笔、关系图，圆角/字重/选中色与 `Pill`/`chip` 不一致 | `LoreRoute.kt`、`WizardRoute.kt`、`IdeasRoute.kt`、`CheckRoute.kt`、`ShelfRoute.kt`、`LoreVisual.kt`、`OutlineRoute.kt` | `ui-hifi/src/theme.css` `.pill` / `v2.css` `.chip`：22–32dp 高、pill 圆角、`primaryContainer` 选中 | P0 |
| A2 | `Segmented` 只有背景色 spring，没有滑动胶囊 | `ModelsRoute.kt` `Segmented` | motion §11：位置类用 `bouncy()` | P1 |
| A3 | `LingCard` 浅色阴影是单一 `shadow(3.dp)`，原稿是双层 `0 1 2 / 0 2 8` | `Components.kt` | `theme.css --shadow` | P1 |
| A4 | AI 便签 / 候选卡阴影规格是 `0 2 14 / 10%`，`LingCard` 默认更轻；`SuggestionCard` 又另写了一套 `shadow(14.dp)` | `AiComponents.kt` `BarCard`、`EditorScreen.kt` `SuggestionCard` | `v2.css` `.note`/`.cand` | P0 |
| A5 | `ToolChips` 只有文字，v0.2 规格要求 16dp 图标 | `EditorScreen.kt` `ToolChips` | ui-spec §2 | P1 |

## B. Dock / 边缘渐隐

| # | 现象 | 文件 | 规格 | 优先级 |
|---|---|---|---|---|
| B1 | 毛玻璃、选中胶囊弹簧、进出场基本到位 | `Dock.kt` | motion §7–8 | OK |
| B2 | 浅色下 fallback 偏实，Roborazzi 看不到模糊（已知限制） | `Dock.kt` | — | 文档说明 |
| B3 | 状态栏渐隐高度已按 inset+16dp 修过 | `Dock.kt` | PR #20 | OK |

## C. 页面转场 / 列表进入

| # | 现象 | 文件 | 规格 | 优先级 |
|---|---|---|---|---|
| C1 | `Emphasized` path 与 `Standard` 已区分，单测覆盖 | `Tokens.kt`、`MotionTokensTest` | MO1 | OK |
| C2 | 顶层 dock 切换 / 子页横滑 / 减弱动画分支正确 | `LingWriteApp.kt` | motion §6 / MO3 | OK |
| C3 | 灵感页、统计页区块没有 `enterStagger` | `IdeasRoute.kt` `IdeasScreen`、`StatsRoute.kt` `StatsScreen` | motion §10 | P1 |
| C4 | 大纲 / 链路 / 卡文等新页也几乎无进场 | `ChainRoute.kt`、`StuckRoute.kt` 等 | 同上 | P2 |

## D. AI 统一动效（最大差距）

| # | 现象 | 文件 | 规格 | 优先级 |
|---|---|---|---|---|
| D1 | `AiCandidateCard` 的 `HorizontalPager` **无** `contentPadding` / `pageSpacing`，相邻卡不露出 12dp、不缩放到 0.96 | `AiComponents.kt` | motion §21 | P0 |
| D2 | 松手吸附用默认 pager，不是 `spring(dampingRatio=0.9, stiffness=MediumLow)` | 同上 | motion §21 | P0 |
| D3 | 流式时正文末尾**没有** 2×1em amber 闪烁光标；`SuggestionCard` 用静态 `" ▍"` | `AiComponents.kt`、`EditorScreen.kt` | motion §20 | P0 |
| D4 | 候选区无 `heightIn(min=120.dp)` / `animateContentSize` | `AiComponents.kt` | motion §20 | P0 |
| D5 | 流式结束后按钮行没有从下方 8dp 淡入 | `AiComponents.kt` | motion §20 | P1 |
| D6 | `AiThinking` 跳动：`translationY = dy * density`，`dy` 来自 `animateFloat(-3f)`，实际是「−3×密度」像素，振幅偏大且语义错（应为 3dp） | `AiComponents.kt` L216–226 | motion §19 | P0 |
| D7 | 「换一批」没有向上 16dp + 淡出再回思考态 | `AiComponents.kt` / `EditorAi.kt` | motion §21 | P2 |
| D8 | 编辑器底栏传入 `today` 但**未显示**「今日 +N」（原稿底栏有） | `EditorScreen.kt` `BottomToolbar` | `ui-hifi/03-editor` | P0 |
| D9 | 底栏是纯色 `surface`，原稿有玻璃感分隔；今日字数被挪到正文上方，层级变乱 | `EditorScreen.kt` | 同上 | P1 |
| D10 | `SuggestionCard` 与 `AiCandidateCard`/`AiNoteCard` 两套并存，竖条/圆角/阴影不统一 | `EditorScreen.kt`、`EditorAi.kt` | ui-spec §1 | P0 |

## E. 链路 / 作品 / 其他页

| # | 现象 | 文件 | 规格 | 优先级 |
|---|---|---|---|---|
| E1 | 当前步骤节点缺少 6dp / 18% primary 光晕 | `ChainRoute.kt` `TimelineRow` | ui-spec §4、`v2.css` `.step .dot.cur` | P0 |
| E2 | 时间线轨道宽度 40dp（稿 24dp），节点无数字（当前用小圆点） | `ChainRoute.kt` | ui-spec §4 | P1 |
| E3 | 作品页 tab 指示器 / 卷头样式基本对齐稿 16 | `WorkScreen.kt` | OK | OK |
| E4 | 灵感空状态、统计卡无进场；忙态只用文字「思考中…」不用 `AiThinking` | `IdeasRoute.kt` | motion §15/19 | P1 |

## F. 骨架 / 减弱动画

| # | 现象 | 文件 | 规格 | 优先级 |
|---|---|---|---|---|
| F1 | `Skeleton` / `LocalReducedMotion` / `pressScale`/`enterStagger` 降级已有 | `Skeleton.kt`、`Theme.kt`、`Motion.kt` | MO2/MO3 | OK |
| F2 | 多数 AI 忙态仍用「生成中…」文字，未统一走 `AiThinking`+骨架 | 各 Route | motion §15 | P1 |

## G. 本次不改（功能边界）

- 不砍功能、不改提示词 / 数据层 / 签名。
- 不在本 PR 重做全部 Route 布局；只修共用组件与最高频页面的视觉/动效。
- Roborazzi 无法表现真实毛玻璃；真机验证。
- Macrobenchmark 数据仍依赖真机（MO4）。

## 修复顺序（本 PR）

1. P0：AI 候选/思考/流式光标、底栏今日字数、Chip 替换组件、链路节点光晕、SuggestionCard 对齐
2. P1：Segmented 滑动胶囊、卡片阴影、ToolChips 图标、灵感/统计 enterStagger、AiThinking 统一忙态（部分）
3. P2 留后续：换一批离场动画、全路由 stagger、底栏玻璃化


## 本 PR 已修（v0.2.12 polish）

- **A1** `LingChip` 替代 M3 `FilterChip`（设定库 / 向导 / 灵感 / 伏笔 / 书架新建 / 关系图等）
- **A2** `Segmented` 增加 `bouncy()` 滑动胶囊
- **A3/A4** `LingCard(elevated=)`；AI 卡与 `SuggestionCard` 统一 elevated + 左侧竖条
- **D1–D6** 候选 Pager peek 12dp + 0.96 缩放 + `Motion.snap()` 吸附；流式 `AiStreamCursor`；`heightIn(120)` + `animateContentSize`；思考点振幅改为 3dp
- **D5** 流式结束后按钮行淡入上移
- **D8/D9** 编辑器底栏恢复「今日 +N」，去掉正文上方重复字数
- **E1/E2** 链路当前节点 6dp/18% 光晕，未完成节点显示数字
- **C3** 灵感 / 统计页区块 `enterStagger`
- **Motion** 新增 `snap()` / `grow()` token

## 仍建议后续做

- ToolChips 加 16dp 图标（A5）
- 「换一批」离场动画（D7）
- 底栏真正毛玻璃化（真机）
- 大纲 / 卡文 / 随手问等新页补 stagger（C4）
- 忙态全面改走 `AiThinking`（F2 / E4）

---

## F. polish3 逐页复查（2026-10-16）

审查依据：polish2 全量截图（56 页 × 明/暗），检查间距、字体层级、圆角、阴影、空状态和图标六项。✅ 表示无需改动，🔧 表示本轮已修。

| 页面 | 结论 | 本轮改动 |
|---|---|---|
| 书架 / 作品 / 卷列表 01 02 62 | ✅ 层级清楚，卡片圆角和阴影统一 | — |
| 编辑器 03 30 31 49 | ✅ 底栏毛玻璃、ToolChips 图标、建议卡浅底色都已到位 | — |
| 设定卡 / 关系图 04 08 | ✅ | — |
| 模型 / 设置 / 关于 / 更新 / 通知 / 隐私 05 07 24–29 | ✅ 列表行图标统一为 36dp 圆角方块；隐私页许可列表保持平铺，和 sheet 一致 | — |
| 首次使用 / 向导 06 12 13 | ✅ | — |
| 大纲 09 | ✅ | 🔧 每卷章节卡片逐个错开入场 |
| 黄金三章 / 多线 / 搜索 / 上下文 / 伏笔 10 11 14 15 16 48 | ✅ | — |
| 一致性检查 17 47 | ✅ | 🔧 问题列表逐个错开入场 |
| 文风 / AI 味 / 统计 / 导出 / 备份 18–23 | ✅ | — |
| 随手问 40 41 | 🔧 空状态的 3 个示例问题是 sheet 底色上的裸蓝字，像链接，点击区域也只有文字本身 | 改为 surfaceContainer 小卡片，前置 ✦ 图标，整卡可点，正文色字；示例卡和历史记录逐个错开入场 |
| 设定建议 42 | ✅ | — |
| 多候选 43 / AI 状态 60 | 🔧 页码指示单独占一行，标题和正文之间空出约 28dp | 页码点和「n/N」并入标题行右侧 |
| 起名 44 / 书面简介 50 / 连载看板 51 | ✅ | — |
| 卡文急救 45 46 | ✅ | 🔧 卡点选项和短更预案逐个错开入场 |
| 拆文 52 | 🔧 章节卡宽窄不齐（截图测试自己拼的 UI 漏了 fillMaxWidth；App 本身没问题） | 测试里的拼装补上 fillMaxWidth，截图和 App 一致 |
| 读者试读 54 55 | 🔧 选中态是整块 primaryContainer 色块，又同时有勾选框和「已选」胶囊，信息重复，视觉偏重 | 抽出 `ReaderPersonaRow`：选中时浅主色底加 1dp 主色描边（和 ToneCard 一致），去掉「已选」胶囊，整卡可点；截图测试改用同一个组件；结果卡逐个错开入场 |
| 链路 56 57 59 61 | 🔧 卷候选卡片宽窄不齐（同样是截图测试的拼装问题） | 测试补上 fillMaxWidth |
| 本章便签抽屉 | ✅ | 🔧 便签逐个错开入场（NoteRow 新增 modifier 参数） |

**全局**：`enterStagger` 的延迟上限改为 8 档（8 × StaggerStep）。懒加载列表里后来滚进视野的项按自己的序号延迟，原来会等很久，现在最多等 8 档。
