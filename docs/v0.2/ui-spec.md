# v0.2 界面规格（可直接执行）

- 高保真稿在 `ui-hifi/v0.2/*-light.png` 和 `*-dark.png`，源码在 `ui-hifi/src/07–16-*.html`，新增样式在 `ui-hifi/src/v2.css`。
- 重新截图：`ui-hifi/src/shot2.sh <name>`。
- 尺寸单位是 dp（稿里 1px = 1dp），颜色都用 `ui/theme/Color.kt` 的 token，字体用 `Ling.type`。
- 稿和本文冲突时，**以本文为准**。

## 1. 新增共用组件（`ui/ai/AiComponents.kt`）
| 组件 | 规格 | 稿 |
|---|---|---|
| `ModeBadge(mode)` | 24dp 高，12dp 圆角，左内边距 7dp、右 9dp。图标 15dp，文字 `micro` 改 12sp、SemiBold。辅助模式：`primaryContainer` 底 / `onPrimaryContainer` 字，图标 `draw`；生成模式：`tertiaryContainer` 底 / `onTertiaryContainer` 字，图标 `auto_awesome`（实心） | 07、08 |
| `AiMainButton(mode)` | 38dp 高，19dp 圆角。辅助模式：1.5dp `primary` 描边、`primary` 字，图标 `lightbulb`，文字"灵感"；生成模式：`primary` 实心底 / `onPrimary` 字，图标 `auto_awesome`，文字"AI 起草" | 07、08 |
| `AiNoteCard` | `surface` 底，16dp 圆角，阴影 `0 2 14 / 10%`。左侧 3dp **`primary`** 竖条。标题行 12.5sp SemiBold `primary`，配 `lightbulb` 或 `sticky_note_2` 实心图标。正文是有序列表，14.5sp，行高 23sp。按钮：再来一次（Text）、复制（Outlined）、存到便签（Tonal，`primaryContainer`） | 07、15⑤ |
| `AiCandidateCard` | 外观同上，竖条改成 **`amber`**，标题行颜色 `tertiary`。右上是页码点：每个 6dp，当前页变成 16×6dp 的 `amber` 胶囊，已就绪的用 `tertiary`，未就绪的用 `outline`，旁边显示 `2/3`（12sp `onSurfaceVariant`）。正文 15sp，行高 26sp。按钮：换一批 / 微调 / 采纳。左右滑动切换（`HorizontalPager`，`beyondViewportPageCount = 1`） | 08、15② |
| `AiThinking(text)` | 三个点各 6dp，间距 4dp，`amber` 色；文字 13sp `onSurfaceVariant`。下面跟两条骨架行，12dp 高、6dp 圆角、`surfaceContainer` 色，宽 90% / 62%。动效见 motion 第 19 节 | 15① |
| `AiErrorCard` | 不画竖条。左边 `error` 图标，右边文字"没拿到结果：{原因}"，下面一行 caption 显示重试次数。按钮：换个模型（Text）、重试（Outlined） | 15③ |
| `AdoptHighlight` | 被采纳的文字范围加 `amber` 30% 透明度的底色，1.2s 内淡到 0，然后恢复成 AI 段落常规的 16% 底色（gen）或 8%（edited） | 15④ |
| `AiTweakSheet` | 全屏 Sheet，跳过半展开。顶部显示原文（只读，`surfaceContainer` 底，最多 4 行），下面是可编辑的候选（`LingTextField`，多行）。底部按钮"取消 / 用这个版本"。采纳后在 call_logs 记为 `edited` | — |
| `ContextChips` | 22dp 高的胶囊（`.pill`），可以点 ✕ 移除，用来表示"带入了哪些上下文"。这是随手问和卡文急救的通用组件 | 09 |
| `Skeleton(widthFraction)` | 见 motion 第 15 节 | 11、14 |

## 2. 编辑器（模式差异，见 `modes.md`）
- 顶栏：标题列的右边放 `ModeBadge`，间距 8dp，点击打开模式 Sheet。
- 工具条：
  - 辅助模式：给方向 / 随手问 / 卡文急救 / 校对；
  - 生成模式：续写 / 起草本章 / 对话 / 描写 / 随手问。
  - 每个胶囊 32dp 高，前面加 16dp 图标。
- 底栏：`AiMainButton`。底栏的"设定"图标换成"本章便签"（`sticky_note_2`），带未读角标。
- 本章便签：右侧抽屉（宽 320dp），按种类分组：方向、节拍、卡文、随手问、改法。每条可以"标为完成""删除""复制"。
- 节拍清单（链路第 ⑥ 步，辅助模式）：放在正文上方，可以折叠。每条 40dp 行高，左边 Checkbox。勾完全部后整块收起，显示"本章节拍已完成 ✓"。
- 草稿区（生成模式，起草本章）：正文下方的独立区域，`surfaceContainer` 底、16dp 圆角，顶部有"草稿 · 第 1/3 段"。每段末尾有"采纳到正文 / 微调 / 换一版"。草稿区的文字**不算进字数统计**。

## 3. 随手问面板（稿 09）
- `LingSheet(skipPartial = false)`，半展开高度为屏幕的 60%，可以上拉到全屏。
- 标题行：右边放 `Pill("回答不进正文", Neutral)`。
- 用户气泡：靠右，`primaryContainer` 底，圆角 16/16/4/16，最大宽度 78%。
- AI 气泡：靠左，`surfaceContainer` 底，圆角 16/16/16/4，最大宽度 88%。出处标记【设定：xx】用 12sp `primary`；"设定里没写"用斜体 `onSurfaceVariant`。
- 输入区固定在底部，并跟随 `imePadding`：上面一行是 `ContextChips`，下面是输入框，右边是 48dp 圆形发送按钮（`primary`）。

## 4. 总纲工作台（稿 10）
- 时间线：左边的轨道宽 24dp，节点 22dp。已完成的节点为 `primary` 实心并带 ✓；当前节点为 `primary` 实心，外加 6dp 18% 的光晕；未开始的节点为 2dp `outline` 描边。连线 2dp，已完成段用 `primary`，其余用 `outlineVariant`。
- 当前步骤卡片：`surface` 底，14dp 圆角。字段标签用 12.5sp `onSurfaceVariant`；按钮行为"重写这段 / 微调 / 采纳并继续"。
- "上游已改"：节点右侧加 `warnp` 文字（12sp `tertiary`），文案"上游已改，可重新生成"。
- 底部固定条：44dp 高，上边 1dp `outlineVariant` 分隔线，文字 12sp。

## 5. 设定整理建议页（稿 11，路由 `lore-suggest/{bookId}`）
- 顶栏副标题："来自第 N 章 · K 条"；右侧放 Text 按钮"全部采纳"。
- 建议卡：按种类用不同胶囊区分。新人物用 Amber + `person_add`；状态变化用 Primary + `trending_up`；新别名用 Neutral + `label`；关系用 Primary + `group`。
  - 状态变化写成"旧值（删除线）→ 新值（加粗）"，后面跟"第 N 章起"。
- 已采纳的卡：2dp `amber` 55% 的描边闪一次，按钮行换成 `good` 色的"✓ 已采纳"，0.8s 后折叠并移出列表（motion 第 22 节）。
- 设定库顶部横幅：`tertiaryContainer` 底，14dp 圆角，文案"AI 从第 N 章整理出 K 条建议"，右侧放 Chevron。

## 6. 卡文急救（稿 12）
- 3 步 Sheet，标题后面跟"· 第 i 步 / 3"。
- 第 1 步：5 个大号选项行（56dp，左边放图标）。
- 第 2 步：每个问题配一组 Segmented 选项，可以跳过。
- 第 3 步：方向卡横向滑动，每张宽 300dp，`surfaceContainer` 底，右上角放页码点。"利 / 弊 / 章纲"三行分别用 `good` / `error` / `primary` 色的标签。
- 底部说明文字："只给方向，不写正文 · 左右滑动看另外 2 个"。

## 7. 校对（稿 13）
- 顶部校对条：`surface` 底，14dp 圆角，左边是 `error` 色的 `spellcheck` 图标 +"发现 N 处 · 只改错字和标点"，右边是"逐条看（Text）/ 全部接受（Tonal）"。
- 正文标记：1.5dp `error` 色波浪线，在 `drawBehind` 里沿基线下方 4dp 画（实现：先按 `getPathForRange` 取范围，再画正弦 path，振幅 1.5dp，周期 6dp）。
- 气泡：宽 250dp，`surface` 底，14dp 圆角，阴影 `0 4 18 / 16%`。内容依次是类别 `Pill(Error)`、"原 → 改"（删除线、加粗）、"忽略 / 接受"。

## 8. 读者试读（稿 14）
- 设置 Sheet：画像多选（最多 3 个，用卡片形式的 Checkbox）、范围（本章 / 第 N–M 章）、费用预估。
- 结果卡：头部是画像 Tile、名字、一句话描述和星级（`amber` / `outline`）。
- 结果卡内容依次是：一句话评价、弃书风险点（`Pill(Error, "第 N 段")` + 原因，点击跳回原文）、最想看的下一章、改进建议。
- 页脚："模拟读者反馈，仅供参考 · 本次约 X tokens"。

## 9. 作品页与命名（稿 16）
- 卷头：`Ling.type.volumeHeader`（13.5sp SemiBold `primary`），显示 `VolumeLabel.full`，右侧是范围（caption）。
- 章行：章号列固定宽 64dp，13.5sp Medium `onSurfaceVariant`，等宽数字（tnum）；章名用 bodyLarge，没有章名时显示 `onSurfaceVariant` 色的"未命名"；有标题建议时，右侧显示 `Pill(Amber, "3 个标题建议")`。
- 新建分卷对话框：输入框标签"卷名（可空）"，上方 caption 写"将创建为 第三卷"。
- 连载看板卡（第 11 条）：放在作品头部下方，4 个数字（numeral 22sp）加标签；存稿不足时整卡改成 `tertiaryContainer` 底。

## 10. 其他新弹层（没有单独出稿，按共用组件拼装）
| 功能 | 组成 |
|---|---|
| 设定卡起名（第 4 条） | 名字框右边是 ✨ IconButton → 下面展开 8 个 `Pill` 名字（每个下方一行 10sp 的寓意）+ 要求输入框 + 换一批 |
| 补全字段 | 草稿态的输入框：`tertiaryContainer` 30% 底色，右侧放 ✓ / ✕ 两个 32dp IconButton |
| 问题卡给改法（第 6 条） | 卡片内展开区：`surfaceContainer` 底；类型 Pill（改正文 / 改设定 / 补一句）；diff 用删除线加 `good` 色新文字 |
| 伏笔回收建议（第 8 条） | `ForeshadowSheet` 里加一个分区"怎么收"：3 张小卡（第 N 章 · 怎么收 · 需要铺垫），按钮为"写进章纲 / 改计划回收章" |
| 书名 / 简介（第 10 条） | 输入框右边是 ✨ → 书名用胶囊行，简介用 `AiCandidateCard`（1/3） |
| 灵感扩展（第 14 条） | 条目下展开 3 张方向小卡，每张有"存为灵感 / 转设定卡 / 转伏笔" |
| 拆文（第 12 条） | 导入页（版权提示横幅用 `errorContainer`）→ 分析页：章节列表 + 用 Canvas 画的折线图（`primary`，2dp，点 5dp）→ 模板预览 |
| 模式切换 Sheet | 两张 `ModeOption` 卡（复用 `FirstRunScreen`）+"应用到所有作品"Checkbox |

## 11. 截图测试清单（每个都要浅色 + 深色）
`30-editor-assist`、`31-editor-generate`、`40-ask-empty`、`41-ask-answer`、`42-lore-suggest`、`43-candidates`、`44-lore-naming`、`45-stuck-ask`、`46-stuck-ways`、`47-issue-fix`、`48-foreshadow-payoff`、`49-proof`、`50-book-blurb`、`51-serial-board`、`52-breakdown`、`54-reader-setup`、`55-reader-report`、`56-chain-synopsis`、`57-chain-volumes`、`58-first-chapter-assist`、`59-first-chapter-draft`、`60-ai-states`、`61-skeleton`、`62-work-volumes`。

每个 agent 完成卡片后，要把截图和对应的 hifi 稿并排看一遍：间距误差 ≤ 4dp，颜色 token 一致。
