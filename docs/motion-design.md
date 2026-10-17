# 灵写 · 前端界面动画设计要求

> 依据：v0.1.0 代码中实际的动效实现（`android/app/src/main/java/com/lingwrite/app/`，下文路径都相对这个目录）。
> 每条都标出 token 名和文件。代码里没有的内容，明确写为"未实现 / 要求"，不作为现状描述。

## 1. 设计原则
1. **安静、服务写作**：动效只用来说明层级和状态变化（页面进出、选中、出现和消失），不做装饰性循环动画。整个代码库没有 `rememberInfiniteTransition`。
2. **统一 token**：时长、曲线、弹簧全部来自 `ui/theme/Tokens.kt` 的 `object Motion`，组件里不写裸数字（例外见第 18 节检查清单）。
3. **进入用减速，退出用加速且更短**：进入用 `EmphasizedDecelerate` 或 `Emphasized`，退出用 `EmphasizedAccelerate` 或更短的 `Motion.Short`（见 dock 和 AI 面板）。
4. **位置类用弹簧，颜色类用 tween**：滑块、指示器、按压缩放用 `Motion.bouncy()` / `Motion.press()`；颜色渐变用 `tween(Motion.Medium)`。
5. **只动 graphicsLayer 属性**：缩放、透明度、位移都走 `graphicsLayer`（`ui/components/Motion.kt`），不触发重新布局。

## 2. 时长档位（`ui/theme/Tokens.kt` → `Motion`）
| token | 值 | 用途 |
|---|---|---|
| `Motion.Short` | 150 ms | 退出淡出：顶层页之间 `fadeOut`、dock 隐藏的 `fadeOut`、AI 面板退出、向导上一步淡出 |
| `Motion.Medium` | 280 ms | 颜色渐变（按钮、tab、dock 图标）、淡入、AI 面板退出位移、dock 隐藏位移 |
| `Motion.Long` | 420 ms | 页面横向滑动、dock 和 AI 面板滑入、列表上移 |
| `Motion.Long * 2` | 840 ms | 用量条、统计柱状图的生长（`ui/screens/ModelsScreen.kt` `UsageCard`，`ui/routes/StatsRoute.kt` `Bars`） |
| `Motion.StaggerStep` | 35 ms | 列表依次进入的间隔 |

## 3. 缓动曲线（`Motion`）
| token | CubicBezier | 用途 |
|---|---|---|
| `Motion.Emphasized` | (0.2, 0, 0, 1) | 页面转场、顶层页缩放、向导步骤切换 |
| `Motion.EmphasizedDecelerate` | (0.05, 0.7, 0.1, 1) | 进入：dock 出现、AI 面板滑入、列表上移、用量条和柱状图生长 |
| `Motion.EmphasizedAccelerate` | (0.3, 0, 0.8, 0.15) | 退出：dock 下滑隐藏 |
| `Motion.Standard` | **v0.2 改为 (0.2, 0, 0, 1)，作为标准曲线** | 列表淡入、颜色和透明度变化、页内小位移 |

**v0.2 区分 Standard 和 Emphasized（卡 MO1）**：
- `Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)`，保持不变，用于页内的普通变化。
- `Emphasized` 改为 M3 的 emphasized path 曲线：两段三次贝塞尔，(0,0)→(0.05,0)→(0.133,0.06)→(0.167,0.4)，再 →(0.208,0.82)→(0.25,1)→(1,1)。用 `PathEasing(path)` 实现，只用于页面转场、顶层页缩放、向导步骤和链路节点推进。
- 区分后，Emphasized 前段更慢、后段更快，转场能明显看出"有重量"，而列表等普通变化不受影响。所有调用处继续只引用 token，不直接写数值。
- 验收：在 `MotionTokensTest` 里断言 `Emphasized.transform(0.1f) < Standard.transform(0.1f)`；转场截图对比没有变化（只比较终态）。

未指定 easing 的 `tween(...)` 使用 Compose 默认的 `FastOutSlowInEasing`（比如颜色渐变、页面 `fadeIn`）。

## 4. 弹簧参数（`Motion`）
| token | 参数 | 用途 |
|---|---|---|
| `Motion.press()` | `dampingRatio = 0.6f`，`stiffness = Spring.StiffnessMedium` | 按压缩放（`pressScale`） |
| `Motion.bouncy()` | `dampingRatio = Spring.DampingRatioLowBouncy`，`stiffness = Spring.StiffnessMediumLow` | dock 选中胶囊、作品页 tab 指示器、首次使用页单选圆环（`ui/screens/FirstRunScreen.kt` 的 `ring`） |

## 5. 依次进入的间隔
- `Modifier.enterStagger(index, offsetDp = 12f)`，见 `ui/components/Motion.kt`。
- 第 `index` 项延迟 `index * Motion.StaggerStep`（35 ms）后开始：
  - 透明度 0 → 1，用 `tween(Motion.Medium, easing = Motion.Standard)`；
  - 同时从下方 12dp 上移到原位，用 `tween(Motion.Long, easing = Motion.EmphasizedDecelerate)`。
- 只在首次组合时播放（`LaunchedEffect(Unit)`），滚动回来不再重播。

## 6. 页面转场（`ui/LingWriteApp.kt` 的 `NavHost`）
- **顶层页之间**（`topLevel` = 书架 / 灵感 / 统计 / 设置，即点 dock 切换）：
  - 进入：`fadeIn(tween(Motion.Medium, 60))`，同时 `scaleIn(tween(Motion.Medium, 60, Motion.Emphasized), 0.98f)`。延迟 60 ms，让旧页先开始淡出。
  - 退出：`fadeOut(tween(Motion.Short))`。
  - 回到书架也走这套。`navigate(Shelf) { popUpTo(Shelf) }` 实际是返回，`popEnter` / `popExit` 在两端都是顶层页时与上面相同，不再用子页面的横滑。
- **进入子页面**：
  - 新页 `slideInHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { it / 6 }`，同时 `fadeIn(tween(Motion.Medium))`；
  - 旧页 `slideOutHorizontally { -it / 10 }`，同时 `fadeOut(tween(Motion.Medium))`。
  - 位移只取屏宽的 1/6 和 1/10，是轻推的感觉，不整屏滑动。
- **返回**：`popEnterTransition` 和 `popExitTransition` 与上面方向相反、参数相同。
- **向导步骤**（`ui/routes/WizardRoute.kt` 的 `AnimatedContent`）：
  - 新步骤 `slideInHorizontally(tween(Motion.Long, easing = Motion.Emphasized)) { it / 8 }`，同时 `fadeIn()`；
  - 旧步骤 `fadeOut(tween(Motion.Short))`。

## 7. dock 切换与选中胶囊滑动（`ui/components/Dock.kt` 的 `GlassDock` / `DockItem`）
- 选中胶囊：52×30dp、15dp 圆角、`primary` 16% 透明度。
  - 位置是 `animateDpAsState(itemW * selected.ordinal + (itemW - 52.dp) / 2, Motion.bouncy(), label = "dockIndicator")`，在各个入口之间弹性滑动。
- 图标和文字颜色：`animateColorAsState(..., tween(Motion.Medium), label = "dockTint")`。选中时图标换成填充版本（`selectedIcon`），这一步不做过渡。
- 按压：`pressScale(i, 0.88f)`，没有 ripple（`indication = null`）。
- 进入作品内部时，`DockHost` 用 `last` 记住上一个 tab，"书架"保持选中，胶囊不跳。

## 8. dock 在编辑器里隐藏和重新出现（`ui/components/Dock.kt` 的 `DockHost`）
- 可见性由 `dockTabFor(route)`（`ui/LingWriteApp.kt`）决定：编辑器、向导等路由返回 `null`，dock 隐藏。
- 隐藏：`fadeOut(tween(Motion.Short))`，同时 `slideOutVertically(tween(Motion.Medium, easing = Motion.EmphasizedAccelerate)) { it / 2 }`。
- 出现：`fadeIn(tween(Motion.Medium))`，同时 `slideInVertically(tween(Motion.Long, easing = Motion.EmphasizedDecelerate)) { it / 2 }`。
- 底部 96dp 的背景渐隐层和 dock 一起显隐。顶部用同一层、同一组透明度的反向渐隐，高度是状态栏内边距再加 16dp，只罩住状态栏和紧挨着的一小段，不跟着底部拉到 96dp。列表把状态栏留白放进滚动内容里，滚上去才会淡出；页面底色铺到状态栏后面。
- 玻璃模糊：`hazeChild`，`blurRadius = 22.dp`，`noiseFactor = 0.04f`，有 `fallbackTint`。`blurEnabled = false` 时降级为 92% 不透明的纯色。

## 9. 按压反馈（`Modifier.pressScale(interaction, pressed = 0.97f)`，见 `ui/components/Motion.kt`）
按下缩小到 `pressed`，松开后用 `Motion.press()` 弹回，通常和 ripple 同时出现。现有档位：

| 组件 | 缩放 | 文件 |
|---|---|---|
| 书架首页"继续写作"大卡 | 0.985 | `ui/screens/ShelfScreen.kt` `HeroCard` |
| 首次使用页模式选项 | 0.98 | `ui/screens/FirstRunScreen.kt` |
| `LingButton` | 0.96 | `ui/components/Components.kt` |
| `LingFab` | 0.95 | `ui/components/Components.kt` |
| 大卡里的白色"继续写"按钮、编辑器工具胶囊 | 0.94 | `ShelfScreen.kt`、`ui/screens/EditorScreen.kt` `ToolChips` |
| 编辑器 AI 主按钮 | 0.93 | `EditorScreen.kt` |
| `LingIconButton` | 0.90 | `Components.kt` |
| dock 入口 | 0.88 | `Dock.kt` |

规则：面积越大，缩放越轻（大卡 0.985，小图标 0.88–0.90）。列表行用 `Modifier.rowClick`（`Components.kt`）：只有 `primary` 色 ripple，不缩放。

## 10. 列表依次进入（`enterStagger` 的用法）
- 书架：大卡 0、"全部作品"标题 1、作品列表卡 2（`ShelfScreen.kt`）。
- 作品主页：头部 0、概览卡 1；每卷占 3 个序号，卷标题用 `2 + vi * 3`、卷卡片用 `3 + vi * 3`（`ui/screens/WorkScreen.kt`）。
- 设置类列表：每个分区的标题和卡片共用分区序号 `si`（`ui/screens/SettingsScreen.kt` 的 `SettingsListScreen`）。
- 设定卡详情 0–4（`ui/screens/LoreScreen.kt`）、模型设置 0–2（`ModelsScreen.kt`）、首次使用页 0–3（`FirstRunScreen.kt`）、占位页 0（`PlaceholderScreen.kt`）。
- **要求**：stagger 只用于"区块"，不给几十行的长列表逐行编号。序号上限建议不超过 8（约 280 ms 总延迟）。

## 11. tab 指示器（`ui/screens/WorkScreen.kt` 的作品页 tab 栏）
- 22×3dp、`primary` 色、2dp 圆角的短条。
- 横坐标：`animateDpAsState(centers[selected] + 20.dp - 11.dp, Motion.bouncy(), label = "tabInd")`。`centers` 由 `onGloballyPositioned` 测量各 tab 的中心点得到，所以文字宽度不同也能对齐中心。
- 文字颜色：`animateColorAsState(..., tween(Motion.Medium), label = "tab")`。字重在 SemiBold 和 Normal 之间直接切换。
- 分段控件 `Segmented`（`ui/routes/ModelsRoute.kt`）：背景色用 `animateColorAsState(label = "seg")`，默认 spring，没有滑块。

## 12. 光标与 AI 段落的视觉效果（`ui/screens/EditorScreen.kt`）
- 正文光标：`cursorBrush = SolidColor(c.primary)`，使用系统默认的闪烁。
- AI 段落：
  - 底色是 `SpanStyle(background = c.amber.copy(alpha = 0.16f))`（`gen`，AI 生成），或 alpha 0.08（AI 建议 + 人工修改）；
  - `gen` 段落在每行底部另画一条 1.5dp 的琥珀色虚线（`PathEffect.dashPathEffect(4dp, 3dp)`）。
  - 这些标记都是静态的，不做动画；编辑时由 `data/MarkTracker.kt` 同步移动位置。
- 流式生成中：建议卡 `SuggestionCard` 在文本末尾追加静态的 `" ▍"`。生成期间"换一个 / 改写 / 采纳"按钮禁用，按钮颜色由 `LingButton` 的 `animateColorAsState(tween(Motion.Medium))` 过渡。
- AI 面板（`ui/routes/EditorAi.kt`）：
  - 进入：`fadeIn(tween(Motion.Medium))`，同时 `slideInVertically(tween(Motion.Long, easing = Motion.EmphasizedDecelerate)) { it / 3 }`；
  - 退出：`fadeOut(tween(Motion.Short))`，同时 `slideOutVertically(tween(Motion.Medium)) { it / 3 }`。

## 13. 进度条与用量条
- 模型设置的"本月调用量"分段条：`Animatable(0f).animateTo(1f, tween(Motion.Long * 2, 120, Motion.EmphasizedDecelerate))`，延迟 120 ms。在 `drawBehind` 里按 `width * grow.value` 绘制（`ModelsScreen.kt` `UsageCard`）。
- 统计柱状图：`grow.animateTo(1f, tween(Motion.Long * 2, easing = Motion.EmphasizedDecelerate))`，`LaunchedEffect(values)` 在切换时间段时重播（`ui/routes/StatsRoute.kt` `Bars`）。
- 更新下载进度：`LinearProgressIndicator({ progress })`，6dp 高、`Radius.pill`、无断点（`gapSize = 0.dp`，`drawStopIndicator = {}`），进度值直接跟随下载，不额外加动画（`ui/routes/UpdateRoute.kt` `UpdateContent`）。
- 向导顶部的 7 段步骤条（`WizardRoute.kt`）：颜色直接切换，不做动画。

## 14. BottomSheet（`ui/components/Sheets.kt` 的 `LingSheet`）
- 基于 Material 3 `ModalBottomSheet`，出现、拖拽、收起都用 M3 默认动画，代码没有覆盖。
- 默认 `skipPartiallyExpanded = true`，直接展开到内容高度。
- 样式：28dp 顶角、36×4dp 拖拽条、`scrim` 色遮罩；内容加了 `imePadding()`，键盘弹出时跟着上推。
- 确认对话框 `LingConfirm` 用 M3 `AlertDialog` 的默认动画。

## 15. 骨架屏与加载状态
- **现状：没有骨架屏，也没有 shimmer**。加载状态用文字和禁用态表示：
  - 按钮文案变为"思考中…"（`ui/routes/IdeasRoute.kt`）或"生成中…"（`ui/routes/ContextRoute.kt`），并设 `enabled = false`；
  - AI 面板标题追加"· 生成中"（`EditorAi.kt`），建议卡末尾显示 `▍`。
- 数据来自 Room `Flow`，首帧通常已有数据，所以列表不需要占位。
- **v0.2 规格（卡 MO2，组件 `ui/components/Skeleton.kt`）**：
  - `Skeleton(widthFraction, height = 12.dp)`：`surfaceContainer` 色、6dp 圆角；`SkeletonBlock(lines)` 默认依次为 90% / 74% / 62% 宽，行间距 10dp。
  - 动画：用 `rememberInfiniteTransition` 让透明度在 0.6 和 1 之间往返，一次 `Motion.Long`（约 500ms）`Standard`、`RepeatMode.Reverse`。同一屏的多个骨架共用一个 transition，保证同步。**不做 shimmer 扫光**。
  - 用在这些地方：AI 结果首个 token 到来前（`AiThinking` 下方）；设定整理建议页、读者报告、拆文分析的首屏；Room 首帧 > 150ms 的页面（只限导入后的大书）。
  - 出现骨架前等 150ms，如果数据先到就不显示，避免闪一下；骨架换成内容时用 `Motion.Short` 淡入。
  - 减弱动画时显示静态色块（透明度 0.8），不循环。

## 16. 无障碍：系统关闭或减弱动画时的降级
- **现状**：代码里没有单独读取"移除动画"设置。所有动画都通过 Compose 动画 API（`tween`、`spring`、`AnimatedVisibility`、`Animatable`）实现，会跟随系统的动画时长缩放（`Settings.Global.ANIMATOR_DURATION_SCALE`）；缩放设为 0 时直接跳到终态。这是 Compose 的默认行为，**需要在真机上验证**。
- 已有的降级：dock 模糊可以关闭（`blurEnabled`），关闭后是纯色玻璃。
- **要求**：
  1. 新增动效必须用 Compose 动画 API，不要手写 `delay` 循环逐帧改值。`enterStagger` 里的 `delay(index * StaggerStep)` 是唯一例外，缩放为 0 时延迟仍然存在（最多约 280 ms），可以接受。
  2. 不允许无限循环动画；如果确实需要（比如骨架屏），必须在缩放为 0 时停下。
  3. 任何信息不能只靠动画传达。选中态同时有颜色、填充图标和字重变化；AI 段落同时有底色和虚线。
  4. 之后如果加"减弱动画"开关，入口统一放在 `Motion`（例如 `Motion.reduced`），由 `enterStagger`、`pressScale` 和转场统一读取。
- **v0.2 实现（卡 MO3）**：
  - 新增 `LocalReducedMotion: CompositionLocal<Boolean>`，在 `LingTheme` 里提供，值 = 设置项"减弱动画"（`Prefs.reduceMotion`，默认跟随系统）或 `ANIMATOR_DURATION_SCALE == 0f`。系统值通过 `ContentObserver` 监听，变化时实时更新。
  - 统一读取方：`Motion.reduced` 返回 `LocalReducedMotion.current`。降级规则如下：
    | 动效 | 正常 | 减弱时 |
    |---|---|---|
    | 页面转场 | 滑动 + 淡入淡出 | 只做 `Motion.Short` 淡入淡出 |
    | `enterStagger` | 位移 + 淡入，逐个错开 | 无延迟、无位移，直接显示 |
    | `pressScale` | 0.97 | 1.0（只保留 ripple） |
    | dock 显隐 | 滑动 | 淡入淡出 |
    | 骨架 | 透明度往返 | 静态 |
    | AI 思考三点 | 跳动 | 静态三点 +"思考中" |
    | 流式光标 | 闪烁 | 常亮 |
    | 候选切换 | Pager 滑动 | 保留手势跟手，松手后直接切换 |
    | 采纳高亮 | 1.2s 淡出 | 直接显示 16% 常规底色 |
  - 设置入口：设置 → 外观 →"减弱动画"（三档：跟随系统 / 开 / 关）。
  - 验收：`ReducedMotionTest` 用 Roborazzi 在 reduced=true 下截取 AI 状态页，确认没有正在进行的动画（`mainClock.autoAdvance=false` 时首帧就是终态）。

## 17. 性能要求
- 动画只改 `graphicsLayer`（alpha、scale、translation）或 `drawBehind` 里的绘制参数，不在动画过程中改变布局尺寸。现有的 `pressScale`、`enterStagger`、用量条和柱状图都遵守这一点。
- 用 `animate*AsState` 时必须写 `label`（现有代码：`press`、`dockIndicator`、`dockTint`、`tabInd`、`tab`、`btnBg`/`btnFg`、`optBorder`、`radio`、`seg` 等），方便 Layout Inspector 和动画预览。
- 模糊只用在 dock（Haze，`blurRadius = 22.dp`）。不要给大面积滚动内容加实时模糊。
- 长列表用 `LazyColumn`（如书架）；`enterStagger` 只给区块用。
- 目标：中端机 60 fps 不掉帧，转场期间没有重组风暴。可以用 Roborazzi 截图测试（`app/src/test/.../*ShotTest.kt`）检查终态是否正确；性能用 Macrobenchmark 或 Perfetto 抽查（v0.2 建立，见下）。
- **v0.2 性能基准（卡 MO4，新模块 `android/benchmark`，`com.android.test`）**：
  - Baseline Profile：`BaselineProfileGenerator` 覆盖启动 → 书架 → 作品 → 编辑器的路径；生成的 `baseline-prof.txt` 提交进 `app/src/main`。
  - Macrobenchmark 场景和阈值（Pixel 6a 级真机、release 包、`CompilationMode.Partial`）：
    | 场景 | 指标 | 阈值 |
    |---|---|---|
    | 冷启动 | `timeToInitialDisplay` P50 | ≤ 600ms |
    | 书架滚动（200 本） | `frameDurationCpuMs` P90 | ≤ 12ms，jank < 1% |
    | 打开 30 万字作品的编辑器 | TTID 到正文可编辑 | ≤ 800ms |
    | AI 流式输出 2000 字（模拟 provider） | 帧 P90 | ≤ 14ms |
    | 候选卡 Pager 连续滑动 | 帧 P95 | ≤ 16ms |
  - 流式输出：token 先按 16ms 窗口合并，每帧最多更新一次文本，避免每个 token 都触发重组。
  - Box 上没有可用的模拟器（KVM 不可用），所以基准只要求**能编译**（`./gradlew :benchmark:assemble`）；数据在真机上跑，结果记录在 [`docs/perf/v0.2.md`](perf/v0.2.md)（占位，待真机补充）。CI 不门禁。

## 18. 新增动效的写法规范和检查清单
**写法**
```kotlin
// 颜色：tween + Medium
val bg by animateColorAsState(target, tween(Motion.Medium), label = "xxxBg")
// 位置或滑块：bouncy 弹簧
val x by animateDpAsState(targetX, Motion.bouncy(), label = "xxxIndicator")
// 出现和消失：进入减速，退出更短
AnimatedVisibility(visible,
    enter = fadeIn(tween(Motion.Medium)) + slideInVertically(tween(Motion.Long, easing = Motion.EmphasizedDecelerate)) { it / 3 },
    exit = fadeOut(tween(Motion.Short)) + slideOutVertically(tween(Motion.Medium, easing = Motion.EmphasizedAccelerate)) { it / 3 })
// 可点击的卡片或按钮
Modifier.pressScale(interaction, 0.96f)
// 区块进入
Modifier.enterStagger(index)
```

**检查清单**
- [ ] 时长和曲线都来自 `Motion`；新档位先加进 `ui/theme/Tokens.kt`，再在这份文档里登记
- [ ] 进入用 Decelerate 或 Emphasized，退出更短且用 Accelerate 或 `Short`
- [ ] 位置类用 `Motion.bouncy()`，按压用 `pressScale`（缩放按面积选第 9 节的档位）
- [ ] `animate*AsState` 写了 `label`
- [ ] 只改 graphicsLayer 或绘制参数，不在动画中改布局
- [ ] 没有无限循环；系统动画缩放为 0 时能直接到终态
- [ ] 状态不只靠动画表达（颜色、图标、文字至少有一项同时变化）
- [ ] 浅色和深色都截图检查过（Roborazzi `shoot(name)`，输出到 `/workspace/lingwrite-shots`）
- [ ] dock 显隐的规则改了，就同步修改 `dockTabFor`（`ui/LingWriteApp.kt`）和 `ui-plan.md` 第 2 节

---
# v0.2 新增：AI 统一动效（第 19–22 节，组件见 `docs/v0.2/ui-spec.md`）
AI 相关动效全部集中在 `ui/ai/AiMotion.kt`，各功能不能自己另写。状态机：Idle → Thinking → Streaming → Ready →（Tweak）→ Adopted；任何状态都可以进入 Error。

## 19. 思考（Thinking）
- `AiThinking`：三个 6dp 圆点依次上下跳动，振幅 3dp，每个周期 900ms，相位依次错开 150ms，用 `Standard` 曲线；颜色 `amber`。
- 发起请求后，马上（< 1 帧）用 `Motion.Short` 淡入卡片外壳和思考行；下面的骨架行按第 15 节的 150ms 规则显示。
- 超过 8s 还没收到首个 token：文案变成"还在想…可以先去干别的"，同时显示"取消"按钮。
- 无障碍：卡片的 `liveRegion = Polite`，只播报一次"正在生成"。

## 20. 流式（Streaming）
- 新到的文字直接追加，**不给单个字做淡入**（避免闪烁和重组）。末尾显示 2dp × 1em 的 `amber` 光标，530ms 闪烁一次（与编辑器光标一致）。
- 从骨架切换到正文时，骨架用 `Motion.Short` 淡出，正文区高度用 `animateContentSize(spring(stiffness = MediumLow))` 增长，并且只在这个容器内部变化，不推动外部布局（候选卡固定最小高度 120dp）。
- 结束时光标用 `Motion.Short` 淡出，按钮行用 `EmphasizedDecelerate` 从下方 8dp 处淡入。
- 多候选并行生成：页码点按"未就绪（`outline`）→ 就绪（`tertiary`）"变色，每个用 `Motion.Medium`。

## 21. 候选切换
- `HorizontalPager` 手势跟手，松手后吸附用 `spring(dampingRatio = 0.9, stiffness = MediumLow)`；相邻卡片露出 12dp 并缩放到 0.96。
- 页码胶囊的宽度 6dp ↔ 16dp 用 `animateDpAsState(Motion.Medium, Standard)` 过渡。
- "换一批"：当前这批卡片向上 16dp 并淡出（`Short`、`EmphasizedAccelerate`），随后回到第 19 节的思考状态。
- 进入微调：卡片用 shared element（`SharedTransitionLayout`）展开成 `AiTweakSheet`，使用 `Emphasized`；减弱动画时直接淡入淡出。

## 22. 采纳高亮
- 采纳后，候选卡淡出（`Short`），同时插入正文的文字范围叠加 `amber` 30% 底色，在 1200ms 内用 `Standard` 淡到 AI 段落常规底色（gen 16% / edited 8%）。编辑器自动滚动，让这段文字可见。
- 非正文的采纳（设定建议、伏笔、章纲字段）：目标元素加 2dp `amber` 55% 描边，闪一次（0 → 1 → 0，用时 800ms），列表项随后折叠移出（`animateItem`）。
- 底部 Snackbar"已采纳 · 撤销"，停留 4s；撤销后反向播放高亮并恢复原状。
- 触感：采纳时 `HapticFeedbackType.Confirm`（API 30+），否则用 `LongPress`；减弱动画时保留。


## 禁用左侧色条（硬规则）

所有卡片、弹层、建议卡、提示卡**禁止**使用左侧竖向色条（accent bar / border-left / `:before` 竖条）。
需要表达类型或状态时，只用以下两种方式之一：

1. **浅底色 + 1dp 描边**：底色 = surface 向强调色插值 4.5%（深色 8%），描边 = 强调色 22%（深色 28%）。Compose 用 `ToneCard(tone = CardTone.X)`，HTML 用 `.note` / `.cand`。
2. **标题前小圆标或小胶囊**：`ToneIcon`（22dp 圆，强调色 14% 底）或 `Pill`。

评审时看到左侧色条一律打回。

