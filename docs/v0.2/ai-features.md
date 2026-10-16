# v0.2 AI 功能详细规格（对应 docs/ai-gaps.md 第 1–16 条）

> 读者是执行 agent。本文件自成一体：每条都写了入口、流程、状态、提示词、数据、文件、验收、工作量和依赖。
> 路径都相对 `android/app/src/main/java/com/lingwrite/app/`。
> 共用的界面和动效规格见 `docs/v0.2/ui-spec.md` 和 `docs/motion-design.md` 第 19–22 节；模式规则见 `docs/v0.2/modes.md`；迁移版本号见第 0.4 节。

## 0. 共用基础（任务卡 F1–F4，所有条目都依赖）

### 0.1 统一的 AI 结果状态机 `AiRun`（新增 `ui/ai/AiRun.kt`）
```kotlin
sealed interface AiRunState {
  data object Idle : AiRunState            // 空：显示入口按钮和一句说明
  data object Thinking : AiRunState        // 已发请求、首个 token 未到：思考中动效（motion 第 19 节）
  data class Streaming(val partial: String, val index: Int) : AiRunState      // 流式输出：逐字出现 + 光标
  data class Ready(val candidates: List<Candidate>, val selected: Int) : AiRunState  // 候选可切换、可微调
  data class Error(val message: String, val retryable: Boolean) : AiRunState  // 错误卡：重试 / 换模型 / 关闭
  data class Adopted(val target: AdoptTarget) : AiRunState  // 采纳后高亮 1.2s，然后回到 Idle
}
data class Candidate(val text: String, val edited: String? = null, val logId: Long?)
```
- 每次运行都写一条 `call_logs`。结果字段 `outcome` 的取值：`accepted` / `edited` / `rejected` / `noted`（新增，表示存进便签）。
- 共用组件（新增 `ui/ai/AiComponents.kt`）：
  - `AiCandidateCard`：生成模式下的候选卡，带分页和 `1/3` 页码；
  - `AiNoteCard`：辅助模式下的便签卡；
  - `AiThinking`：三点呼吸动画加一行文案，比如"正在读本章和设定…"；
  - `AiErrorCard`：错误卡；
  - `AiTweakSheet`：微调用的全屏编辑 Sheet，左边原文、右边可编辑的候选；
  - `AdoptHighlight`：采纳后的高亮修饰符。
- 文案规范：思考中用"正在 + 动作 + …"，例如"正在想 3 个方向…"；错误统一为"没拿到结果：{原因}"，下面两个按钮"重试""换个模型"。

### 0.2 统一的上下文构建
- 正文类任务一律用 `ContextEngine.build(bookId, chapterId, cursor, text)`（现有，见 `data/ContextEngine.kt`）。
- 非正文任务用新增的 `ContextEngine.brief(bookId, parts: Set<Part>)`，`Part` 包括：`Synopsis`、`VolumeOutline`、`ChapterPlan`、`PrevSummaries(n)`、`Characters`、`Lore(query)`、`OpenForeshadows`、`StyleCard`。每个部分有 token 预算，由 `fitBudget` 裁剪。
- 系统提示一律拼上 `Prompts.BASE`（正文类）或新增的 `Prompts.EDITOR`（编辑或分析类，不写正文），再加 `PromptHooks.style`。

### 0.3 统一的 JSON 解析
- 所有结构化输出都用 `extractJson(raw, array)`（`data/Outline.kt`）。解析失败时：用"只输出 JSON"的提示重试一次；仍然失败就转为 `Error(retryable = true)`。

### 0.4 Room 迁移版本号分配（`data/db/LingDatabase.kt` 的 `MIGRATIONS`）
| 版本 | 里程碑 | 内容 |
|---|---|---|
| 1 → 2 | M1 | 卷名和章名去掉编号前缀（`naming-bug.md`） |
| 2 → 3 | M2 | `books.writeMode`；新表 `chapter_notes`；`call_logs` 加 `candidates INTEGER NOT NULL DEFAULT 1`、`chosen INTEGER NOT NULL DEFAULT -1` |
| 3 → 4 | M3 | `book_plans` 加 `idea`、`logline`、`sellingPoints`、`chainStep`、`chain`；`volume_plans` 加 `goal`、`climax`、`chapterCount`；`chapter_plans` 加 `beats`、`titleIdeas` |
| 4 → 5 | M4 | 新表 `lore_suggestions`；`issues` 加 `fix`；`foreshadows` 加 `payoffIdeas` |
| 5 → 6 | M5 | `books` 加 `bufferTarget`；`ideas` 加 `expansions` |
| 6 → 7 | M6 | 新表 `breakdowns`、`reader_reports` |

每次迁移的要求：
- 导出新的 `app/schemas/.../N.json`；
- 在 `MigrationTest` 加一个 `migrateN_N+1()` 用例：先用旧 schema 插入数据，迁移后断言；
- 新加的列都写 `NOT NULL DEFAULT ...`，保证旧数据可用；
- 不用破坏性迁移。

---

## 1. 编辑器 · 随手问（价值第 1，M2，工作量 M）
- **入口**：
  - 编辑器底栏 AI 主按钮 → 菜单第一项"随手问"；
  - 工具条上的胶囊"随手问"（两种模式都有）；
  - 选中文字后，工具条上的"问问这段"。
- **流程**：
  1. 点击后，从底部弹出 60% 高的"随手问"面板，盖在键盘上方，正文仍可见。
  2. 输入框里预填上下文提示胶囊（可以删）：「本章」「设定库」「前情」；选中了文字时还有「选中：xxx…」。
  3. 发送后进入思考中状态，然后流式显示回答。回答下方有"复制 / 存为设定卡 / 存为灵感 / 存到本章便签 / 追问"。
  4. 面板保留本章的问答历史（最多 20 条），关闭后再打开仍然在。
  5. **回答绝不进正文**，两种模式都一样。
- **界面状态**：
  - 空：三条示例问题（"这个朝代的官职怎么称呼""沈砚现在是什么境界""这章节奏是不是太慢"），点击即填入；
  - 加载：`AiThinking`，文案"正在翻设定和前情…"；
  - 流式：回答气泡逐字出现；
  - 错误：`AiErrorCard`；
  - 候选微调：不适用，回答不是候选；"存为设定卡"会先打开 `LoreEditSheet` 预填，作者改完再保存；
  - 采纳：存卡或存灵感成功后，弹出 snackbar"已存进设定库"，并带"查看"链接。
- **提示词**（`data/ai/AskPrompts.kt`）：
  ```
  system: 你是作者的写作顾问兼资料员，正在帮作者写《{书名}》。回答简洁具体（200 字以内），能列点就列点。
    涉及本书设定时，只依据下面给出的资料，并在句末用【设定：卡名】或【第N章】标出处；资料里没有就直说"设定里没写"，
    不要编造。涉及现实知识（历史、地理、官职等）时正常回答，但不确定的要说明。不要写小说正文。
  user: 【本书资料】{brief: Characters + Lore(query=问题) + PrevSummaries(5) + ChapterPlan}
    【当前章节】第 {n} 章，光标前 1500 字：{text}
    {选中时：【作者选中】「{quote}」}
    【历史问答】{最近 3 轮}
    【问题】{q}
  ```
- **数据**：
  - 复用 `IdeaEntity(kind = "ask")`，新增 `chapterId` 列（迁移 2 → 3 里加 `ideas.chapterId INTEGER`，可空）。问答历史按 `chapterId` 查询。
  - "存到本章便签"写进 `chapter_notes(kind = "ask")`。
- **文件**：新增 `ui/routes/AskPanel.kt`；修改 `ui/routes/EditorAi.kt`（菜单和工具条）、`data/db/StatsEntities.kt`（`IdeaEntity.chapterId`）；`ui/routes/IdeasRoute.kt` 的现有随手问改成调用 `AskPrompts`。
- **验收**：
  1. 问"沈砚现在什么境界"时，回答里出现示例数据的状态值，并带【设定：沈砚】；
  2. 回答不进正文，`chapters.content` 不变；
  3. 存为设定卡 → 打开预填的 Sheet → 保存后设定库多一张卡；
  4. `call_logs` 里 task 是 `ask`。
- **测试**：
  - `AskShotTest` 用 `withMockModel` 截图 `40-ask-empty`、`41-ask-answer`；
  - 单元测试断言提示词里包含选中文字和设定卡名；
  - 测试问答历史持久化。
- **依赖**：F1、F2。

## 2. 设定库 · AI 帮整理（价值第 2，M4，工作量 M）
- **入口**：
  - 自动：写完一章（`EditorHooks.onChapterDone`）并且开着 `prefs.autoCheck` 时运行；
  - 手动：设定库顶部"AI 整理本章"按钮（选择章节），以及编辑器 ⋮ →"从本章整理设定"。
- **流程**：
  1. 复用 `Consistency.check` 第一步 `extract` 得到的 facts。如果当次没有运行检查，就单独调用。
  2. 新增第二步 `loreSuggest`，产出建议：
     - `new_card`：新人物或设定；
     - `state`：已有卡在第 N 章起某字段变成新值；
     - `alias`：新别名；
     - `relation`：关系变化。
  3. 设定库顶部出现琥珀色横幅"AI 从第 N 章整理出 5 条建议"，点开是建议列表。每条有原文引用，按钮为"采纳 / 微调 / 忽略"，另有"全部采纳"。
  4. 微调：打开对应的编辑 Sheet（新建卡用 `LoreEditSheet`，状态用 `StateSheet`），字段预填，保存即采纳。
- **界面状态**：
  - 空：没有建议时不显示横幅；
  - 加载：建议列表顶部用 `AiThinking` 的小号版本，文案"正在整理第 N 章…"；
  - 错误：横幅变成错误态，带"重试"；
  - 采纳：卡片用 `AdoptHighlight` 高亮后移出列表，计数减一。
- **提示词**（`data/LorePrompts.kt`）：
  ```
  system: 你是小说设定整理员。只根据给出的事实和原文判断，不臆测。严格只输出 JSON 数组。
  user: 【已有设定卡】{每张：id、类型、名字、别名、当前各字段值（按第 N 章生效）}
    【本章事实】{facts JSON}
    【本章原文节选】{按 facts 的引用定位，前后各 80 字}
    输出：[{"kind":"new_card|state|alias|relation","cardId":可空,"type":"character|faction|place|item|power|term",
     "name":"","field":"","value":"","target":"关系对象名","label":"关系","quote":"原文依据≤40字","why":"≤30字"}]
    只输出确有变化的条目，最多 12 条；已有卡的已知值不要重复建议。
  ```
- **数据**（迁移 4 → 5）：
  ```sql
  CREATE TABLE lore_suggestions(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL,
    chapterId INTEGER NOT NULL, number INTEGER NOT NULL, kind TEXT NOT NULL, cardId INTEGER, payload TEXT NOT NULL,
    quote TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'pending', createdAt INTEGER NOT NULL);
  CREATE INDEX index_lore_suggestions_bookId_status ON lore_suggestions(bookId, status);
  ```
  - 采纳时：`new_card` 插入 `LoreCardEntity(pending = false)`；`state` 插入 `LoreStateEntity`；`alias` 更新 `aliases`；`relation` 插入 `LoreRelationEntity`。
  - 现有的 `LoreCardEntity.pending` 字段保留，改作"建议卡"的兼容展示，新代码不再写它。
  - 备份和恢复（`data/Backup.kt` 的表定义）要加 `lore_suggestions`，外键重映射加 `bookId`、`chapterId`、`cardId`。
- **文件**：新增 `data/LoreSuggest.kt`、`data/LorePrompts.kt`、`ui/routes/LoreSuggestRoute.kt`；修改 `data/Consistency.kt`（对外暴露 facts）、`ui/routes/LoreRoute.kt`（横幅）、`ui/routes/CheckRoute.kt`（`onChapterDone` 的调用链）、`data/Backup.kt`、`data/db/LoreEntities.kt`。
- **验收**：
  1. mock 模型返回 1 条 `new_card`、1 条 `state` → 横幅显示"2 条建议" → 全部采纳后设定库多 1 张卡，人物卡的"按章状态"多 1 条；
  2. 忽略的条目不会再次出现；
  3. 关闭 `autoCheck` 后不自动运行。
- **测试**：`LoreSuggestTest`（逻辑和采纳的数据库效果）、截图 `42-lore-suggest`、备份往返测试。
- **依赖**：F1–F3、迁移 4 → 5。

## 3. 编辑器 · 多候选（价值第 3，M2，工作量 S–M）
- **入口**：生成模式下的续写、对话、描写、起草本章，以及选中文字后的改写、扩写。辅助模式下改写类只出 1 条，并排对比显示。
- **流程**：
  1. 一次请求生成 N 条（`prefs.candidates`，默认 3，可选 1–3，在设置 → 写作里调）。
  2. 实现方式：
     - 如果服务商支持 `n` 参数（OpenAI 兼容）：在请求里带 `n = N`，流式按 `choices[i].index` 分别填充；
     - 否则顺序发起 N 次流式请求：第 1 条流式显示，后面的在后台生成，页码点变实心表示就绪。
     - 两种方式由 `ProviderEntity` 的新能力字段 `supportsN` 决定（存在 `models` JSON 的元数据里，不需要迁移，默认 false）。
  3. 候选卡可以左右滑动或点页码点切换。"换一批"会重新生成 N 条；"微调"打开 `AiTweakSheet`；"采纳"写入正文。
  4. 采纳时把 `call_logs.chosen` 设为下标，`candidates` 设为 N。
- **界面状态**：
  - 加载：卡片里显示 `AiThinking`；
  - 流式：当前页逐字出现，其他页显示骨架行（motion 第 15 节）；
  - 部分失败：失败的那页显示小号错误态，带"重试这一条"；
  - 采纳：插入的段落用琥珀色底闪一次（`AdoptHighlight`）。
- **提示词**：不变（`Prompts.continueTask` 等）。顺序生成时，第 2 条及以后在提示词末尾追加"（请给出与前面不同的写法：{前一条的前 40 字}…）"，让候选之间有差异。
- **数据**：迁移 2 → 3，`call_logs` 加 `candidates`、`chosen`。统计页的"采纳率"按 `chosen >= 0` 计算。
- **文件**：修改 `data/ai/Llm.kt`（`n` 参数和多 choice 解析）、`data/ai/AiService.kt`（`streamMany`）、`ui/routes/EditorAi.kt`；使用新组件 `AiCandidateCard`；修改 `ui/routes/StatsRoute.kt`。
- **验收**：
  1. MockWebServer 返回含 3 个 choice 的 SSE → 卡片显示 1/3，滑动到 3/3，采纳第 3 条后正文出现第 3 条文本，`chosen = 2`；
  2. 服务商不支持 `n` 时顺序请求 3 次；
  3. 候选数设为 1 时不显示页码。
- **测试**：`CandidatesTest`（SSE 多 choice 解析）、截图 `43-candidates`。
- **依赖**：F1、迁移 2 → 3。

## 4. 设定卡 · AI 起名与补全字段（价值第 4，M4，工作量 S）
- **入口**：
  - `LoreEditSheet` 里，名字输入框右侧的 ✨ 按钮（"起名"）；
  - 字段区底部的"AI 补全空字段"；
  - 设定库"新建设定卡"Sheet 的类型选择下方"让 AI 起个头"。
- **流程**：
  - 起名：弹出小卡，显示 8 个名字胶囊，每个附 10 字以内的寓意。点一个即填入；"换一批"重新生成；上方可以输入要求，例如"姓沈、清冷、两个字"。
  - 补全：生成空字段的值，以浅琥珀色底的草稿态填进输入框，每个字段右侧有 ✓ / ✕。一次全部接受用"全部保留"。
- **界面状态**：
  - 空：只显示按钮；
  - 加载：名字区显示 8 个骨架胶囊；
  - 错误：小号错误行；
  - 微调：直接在输入框里改；
  - 采纳：保存卡片即生效。
- **提示词**：
  ```
  起名 system: 你是网文起名顾问。名字要符合题材和本书已有的命名风格，好记、不撞常见网文主角名。严格只输出 JSON。
  user: 【书名/类型/文风卡】… 【已有同类名字】{同类卡名，最多 30 个} 【这张卡】类型 {type}，定位 {role}，已知字段 {fields}
    【作者要求】{可空} 输出：[{"name":"","meaning":"≤10字"}] 共 8 个。
  补全 system: 你是设定助理。根据已有信息补全空字段，和已有设定保持一致，不确定的写"待定"。严格只输出 JSON 对象 {字段名: 值}。
  user: 【总纲】{synopsis 前 600 字} 【相关卡】{同名提及的卡} 【正文中的提及】{检索 3 段，每段 120 字} 【这张卡】{全部字段（含空）}
  ```
- **数据**：不需要迁移。字段的草稿态只存在内存里。
- **文件**：新增 `ui/routes/LoreAi.kt`；修改 `ui/routes/LoreRoute.kt`（`LoreEditSheet`）。
- **验收**：mock 返回 8 个名字 → 点第 3 个 → 名字框填入；补全时 3 个空字段出现草稿，✕ 掉 1 个后保存，卡片里只有 2 个新字段。
- **测试**：`LoreAiShotTest`，截图 `44-lore-naming`。
- **依赖**：F1、F2。

## 5. 卡文急救（价值第 5，M5，工作量 M）
- **入口**：
  - 编辑器工具条胶囊"卡文急救"（辅助模式常驻；生成模式在 AI 菜单里）；
  - 作品页 ⋮ →"卡文了"；
  - 编辑器里光标停留 3 分钟且字数没有增长时，底部弹出一次轻提示"卡住了？试试卡文急救"。可以在设置里关闭（`prefs.stuckHint`，默认开）。
- **流程**（底部 Sheet，3 步）：
  1. 选卡点：「不知道怎么过渡」「大纲走不通了」「没状态」「人物不知道该怎么反应」「其他（自己写一句）」。
  2. AI 先问 2–3 个澄清问题，用选项按钮回答，也可以跳过。
  3. AI 给 3 个方向卡，每个包含"做法、利、弊、需要改动的章纲"。每个方向可以：
     - "存到本章便签"；
     - "改进章纲"：生成章纲修改的 diff，进入章纲 Sheet 的待确认状态；
     - 仅生成模式下多一个"按这个方向起草一段"，结果进候选卡。
  4. 选"没状态"时，不调用 AI，直接给出本地建议：短更预案（见第 11 条）、跳写下一个场景、回看上一章摘要。
- **界面状态**：每一步都有加载和错误态；方向卡支持左右滑动；采纳便签时便签卡高亮。
- **提示词**：
  ```
  system: 你是陪作者过卡点的编辑。只提问题和方向，不写小说正文（引用原文除外）。每个方向要具体到"谁做什么、带来什么变化"。严格只输出 JSON。
  user(第2步): 【卡点类型】{type} 【作者补充】{text} 【章纲】{event/hook/cliff} 【本章已写】结尾 1200 字 【前情】PrevSummaries(3) 【未回收伏笔】OpenForeshadows(5)
    输出：{"questions":[{"q":"","options":["",""]}]} 2–3 个问题。
  user(第3步): 上述 + 【作者回答】… 输出：{"ways":[{"title":"≤12字","how":"≤80字","pro":"","con":"","outlineChange":"对本章/后续章纲的改动，没有则空"}]} 3 个。
  ```
- **数据**：结果写进 `chapter_notes(kind = "stuck")`，不需要单独的表。
- **文件**：新增 `ui/routes/StuckRoute.kt`、`data/StuckPrompts.kt`；修改 `ui/routes/EditorAi.kt`、`ui/routes/WorkRoute.kt`、`data/Prefs.kt`。
- **验收**：
  1. 完整走完 3 步，mock 返回问题和 3 个方向 → 存便签后 `chapter_notes` 多 1 条，正文不变；
  2. 辅助模式下没有"起草一段"按钮；
  3. 3 分钟提示只出现一次。
- **测试**：`StuckShotTest`，截图 `45-stuck-ask`、`46-stuck-ways`。
- **依赖**：F1、F2、MD1（`chapter_notes`）。

## 6. 一致性问题卡 · 给改法（价值第 6，M4，工作量 S）
- **入口**：`IssueCard`（`ui/routes/CheckRoute.kt`）的按钮行，在"去原文"左边加"给改法"。
- **流程**：
  1. 点击后，在卡片内部展开改法区，显示思考中。
  2. 返回 1–2 个改法，每个标明类型：「改正文」「改设定」「补一句解释」。
     - 「改正文」：显示 diff（删除线 + 新文字）。生成模式下按钮是"去原文应用"：跳到编辑器，以批注修改的方式出现，逐条接受（复用 `EditorAi` 的 annotate 流程）。辅助模式下按钮是"存到便签"，作者自己去改。
     - 「改设定」：按钮"去改设定"，打开对应卡的 `StateSheet` 并预填。
     - 「补一句」：生成模式按批注插入；辅助模式存便签。
  3. 采纳任意一种之后，问题状态变成 `fixed`（新增状态值），卡片淡出。
- **提示词**：
  ```
  system: 你是连载编辑，帮作者用最小改动消除前后矛盾。严格只输出 JSON。
  user: 【矛盾】类型 {kind}；原文「{quote}」；依据：{basis}；说明：{explain}
    【原文上下文】quote 前后各 300 字 【相关设定卡】{basis 涉及的卡}
    输出：[{"type":"text|lore|bridge","before":"要改的原文（text 时必填）","after":"","why":"≤30字"}] 1–2 条。
  ```
- **数据**：迁移 4 → 5，`issues` 加 `fix TEXT NOT NULL DEFAULT ''`（缓存 JSON）；`status` 新增取值 `fixed`。
- **文件**：修改 `ui/routes/CheckRoute.kt`、`data/Consistency.kt`、`ui/routes/EditorAi.kt`（让外部可以注入批注）。
- **验收**：mock 返回 text 类改法 → 生成模式下点"去原文应用" → 编辑器出现一条批注建议 → 接受后正文替换，并标为 `edited`，问题变成 `fixed`。
- **测试**：`IssueFixTest`、截图 `47-issue-fix`。
- **依赖**：F1、迁移 4 → 5、MD2（模式路由）。

## 7. 大纲 · 总纲拆卷纲 + 滚动规划（价值第 7，M3，工作量 S）
已并入完整链路，详见 `docs/v0.2/outline-chain.md` 第 3、4 步。这里只列独立入口：
- 大纲页顶部"总纲"卡片 →"AI 拆卷纲"。启用现有的 `OutlinePrompts.volumes(synopsis, n)`，并扩展字段为 `goal`、`climax`、`chapterCount`。
- 每一卷的卡片 →"AI 规划后续章纲"，Sheet 里预设三个选项"3 章 / 5 章 / 自定"，默认从这一卷最后一个有章纲的章节之后开始。
- 验收：在只有总纲的作品上拆出 4 卷候选 → 勾选 3 卷采纳 → 生成 3 个 `VolumeEntity` 和 3 个 `VolumePlanEntity`，卷名不带编号（遵守 `naming-bug.md` 的规范）。

## 8. 伏笔台账 · 回收建议（价值第 8，M4，工作量 S）
- **入口**：`ForeshadowSheet`，以及伏笔列表里临期、超期条目的"怎么收"按钮。
- **流程**：给出 3 种回收方式，每种包含"在哪章、怎么收、需要提前铺垫什么"。可以"写进第 N 章章纲备注"（追加到 `ChapterPlanEntity.note`，并显示一次 diff 让作者确认），也可以"改计划回收章"。
- **提示词**：
  ```
  system: 你是伏笔设计顾问。回收要合情理、有爽感，不能突兀。严格只输出 JSON。
  user: 【伏笔】{content}，第 {plant} 章埋下，原文「{quote}」，计划第 {payoff} 章回收，现在写到第 {cur} 章
    【当前卷纲】{outline} 【后续章纲】{接下来 8 章的 event} 【相关设定卡】{cardId 对应的卡}
    输出：[{"chapter":数字,"how":"≤60字","setup":"需要提前铺垫什么，≤30字"}] 3 条。
  ```
- **数据**：迁移 4 → 5，`foreshadows` 加 `payoffIdeas TEXT NOT NULL DEFAULT ''`（缓存）。
- **文件**：修改 `ui/routes/CheckRoute.kt`、`data/Consistency.kt`。
- **验收**：写进章纲后，对应 `chapter_plans.note` 末尾多出"【伏笔回收】…"，可以撤销。
- **测试**：`ForeshadowPayoffTest`、截图 `48-foreshadow-payoff`。
- **依赖**：F1、F2。

## 9. 编辑器 · 校对本章（价值第 9，M5，工作量 S–M）
- **入口**：工具条胶囊"校对"（两种模式都有）；编辑器 ⋮ →"校对本章"。
- **流程**：
  1. 分段（每段 1500 字）请求，结果在正文里以**红色波浪下划线**标出。
  2. 顶部出现校对条："发现 12 处 · 全部接受 · 逐条看 · 退出"。
  3. 点某一处，弹出气泡显示"原 → 改"和类别（错别字、的地得、标点、病句），按钮为"接受 / 忽略"。
  4. 接受后**不加 AI 段落标记**，因为只改错字和标点，属于校对；`call_logs.outcome` 仍然记录。
  5. 病句类改动超过 6 个字时，按"AI 建议 + 人工修改"标记（`edited`），保证透明。
- **提示词**：
  ```
  system: 你是严格的中文校对。只找错别字、的地得、标点误用、明显病句；不改风格、不改用词习惯、不改方言和口语、不改人物语言特色。严格只输出 JSON。
  user: 【段落】{seg} 输出：[{"quote":"原文最短唯一片段","fix":"改后","type":"typo|de|punct|grammar","why":"≤15字"}]，没有问题输出 []。
  ```
- **数据**：结果只存在内存里（`ProofState`），不需要迁移。
- **文件**：新增 `ui/routes/ProofRoute.kt`、`data/ai/ProofPrompts.kt`；修改 `ui/screens/EditorScreen.kt`（`drawBehind` 里画波浪线）、`ui/routes/EditorAi.kt`。
- **验收**：mock 返回 3 处 → 正文出现 3 条波浪线 → 全部接受后正文替换正确，AI 段落标记数量不变（除了病句类 > 6 字的情况）。
- **测试**：`ProofTest`（片段定位，同一片段出现多次时取最近的）、截图 `49-proof`。
- **依赖**：F1。

## 10. 作品页 · 书名、简介、卖点文案（价值第 10，M5，工作量 S）
- **入口**：作品信息 Sheet（`ui/routes/WorkRoute.kt` 的 `editBook`），书名和简介输入框右侧的 ✨；Sheet 底部"生成上架文案"。
- **流程**：
  - 书名：给 5 个候选胶囊。
  - 简介：给 3 版候选卡，可以左右切换和微调。
  - 上架文案：生成"一句话卖点、三条标签、120 字推荐语"，可以复制。
- **提示词**：沿用 `WizardPrompts.titles` 并扩展：
  ```
  user: 【总纲】【卷纲（已写到的卷）】【最近 10 章摘要】【当前书名/简介】【平台】{起点/番茄/其他（作者选）}
    输出：{"titles":[5],"blurbs":[3 版，每版≤150字，开头一句抓人],"pitch":{"oneLine":"≤25字","tags":[3],"recommend":"≤120字"}}
  ```
- **数据**：不需要迁移，采纳后写入 `BookEntity.title` 和 `blurb`。
- **文件**：新增 `ui/routes/BookAi.kt`；修改 `ui/routes/WorkRoute.kt`。
- **验收**：采纳第 2 版简介后，`books.blurb` 更新，书架卡片显示新简介。
- **测试**：截图 `50-book-blurb`。
- **依赖**：F1、F2。

## 11. 作品页 · 连载看板与短更预案（价值第 11，M5，工作量 M）
- **入口**：作品页头部下面新增"连载看板"卡片，显示今日字数和日更目标、存稿章数、存稿目标、连续更新天数、章纲余量。
  - 存稿数：`draft = false` 且排在"已发布到"之后的章节数。新增"标记已发布到第 N 章"，存在 `BookEntity.publishedUpTo`，迁移 5 → 6 和 `bufferTarget` 一起加。
- **流程**：
  - 当存稿少于目标，或章纲余量少于 3 章时，卡片变成琥珀色，并显示"短更预案"按钮。
  - 点击后 AI 给出：
    - 一个"拆章方案"：把下一章的章纲拆成两章，各自要有钩子；
    - 两个"过渡章思路"：只给提纲。
  - 采纳后写进章纲（需要确认 diff）。
- **提示词**：
  ```
  system: 你是连载节奏顾问。帮作者在不注水的前提下维持更新。只给章纲级提纲，不写正文。严格只输出 JSON。
  user: 【存稿】{n} 章，目标 {t} 【后续章纲】{接下来 5 章 event/hook/cliff} 【最近 3 章摘要】
    输出：{"split":{"from":章号,"a":{"event":"","hook":"","cliff":""},"b":{...}},"bridges":[{"event":"","why":"≤30字"}]}
  ```
- **数据**：迁移 5 → 6，`books` 加 `bufferTarget INTEGER NOT NULL DEFAULT 5`、`publishedUpTo INTEGER NOT NULL DEFAULT 0`。
- **文件**：新增 `ui/routes/SerialBoard.kt`；修改 `ui/screens/WorkScreen.kt`、`data/BookRepository.kt`、`data/Stats.kt`（连续更新天数）。
- **验收**：示例数据把 `publishedUpTo` 设为第 115 章、目标 5 → 看板显示"存稿 3 / 5"并变成琥珀色 → 采纳拆章方案后，章纲多出一章，后续章号用 `renumber` 顺延。
- **测试**：`SerialTest`（存稿计算、连续天数）、截图 `51-serial-board`。
- **依赖**：F1、F2、N1（编号规范，拆章时要重编号）。

## 12. 向导 · 扒榜拆文（价值第 12，M6，工作量 M）
- **入口**：
  - 向导第 2 步"类型与文风"→"从样章学结构"；
  - 大纲页"模板"Sheet →"拆一本书做模板"。
- **流程**：
  1. 粘贴或导入 txt（样章 1–10 章）。页面上显示**版权提示**："请只使用你有权使用的文本。灵写只保存分析结果，不保存原文。"
  2. 分析完成后得到：
     - 每章 50–100 字的结构摘要（核心事件、钩子、爽点、章尾悬念）；
     - 整体节奏曲线（每章的冲突强度 1–5，用折线图显示）；
     - 可以复用的"结构模板"：抽象成"第 1 章：主角困境 + 金手指暗示……"，**不含原文的人名和设定**。
  3. 可以"存为大纲模板"（写进 `OutlineTemplates`，`book_plans.templates`），也可以"套用到本书前 N 章章纲"（生成候选，确认后写入）。
- **提示词**：
  ```
  system: 你是网文结构分析师。只分析结构和节奏，输出时不得复述原文句子（引用≤10字），不得保留原作人名地名，用"主角/对手/师门"等角色代称。严格只输出 JSON。
  user: 【第{i}章】{text 前 6000 字} 输出：{"event":"","hook":"","cool":"爽点","cliff":"","intensity":1-5,"pattern":"抽象结构≤40字"}
  汇总: 【各章分析】… 输出：{"curve":[..],"template":[{"chapter":1,"pattern":""}...],"notes":["节奏要点≤30字"]}
  ```
- **数据**（迁移 6 → 7）：
  ```sql
  CREATE TABLE breakdowns(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER, name TEXT NOT NULL,
    chapters TEXT NOT NULL, curve TEXT NOT NULL, template TEXT NOT NULL, createdAt INTEGER NOT NULL);
  ```
  原文只在内存里处理，**不落库**。
- **文件**：新增 `ui/routes/BreakdownRoute.kt`、`data/Breakdown.kt`；修改 `ui/routes/WizardRoute.kt`、`ui/routes/OutlineRoute.kt`（模板 Sheet）、`data/Backup.kt`。
- **验收**：
  1. 导入 3 章示例文本 → 得到 3 条分析和曲线；
  2. 数据库里搜索原文中任意 20 字都找不到；
  3. 输出里不含原作人名（用单元测试检查，用户提供的人名列表不出现在模板里）。
- **测试**：`BreakdownTest`、截图 `52-breakdown`。
- **依赖**：F1、F2。

## 13. 编辑器 · 章节标题候选（价值第 13，M3，工作量 S）
- **入口**：
  - 自动：完成本章时，如果章名为空，摘要生成后**顺带**生成 3 个标题，存进 `chapter_plans.titleIdeas`；
  - 手动：重命名本章的对话框里点 ✨。
- **流程**：重命名对话框显示 3 个胶囊，点一个即填入；章名为空的章节在作品页显示琥珀色的"有标题建议"小点。
- **提示词**：
  ```
  user: 【本章摘要】{summary} 【本书已有章名风格】最近 10 个章名 输出 3 个章名（每个≤8字，不剧透关键反转）：["","",""]
  ```
- **数据**：迁移 3 → 4，`chapter_plans` 加 `titleIdeas TEXT NOT NULL DEFAULT '[]'`。任务名 `title`，走自动任务模型。
- **文件**：修改 `data/ContextEngine.kt`（`afterChapterDone`）、`ui/routes/EditorRoute.kt`、`ui/routes/WorkRoute.kt`。
- **验收**：完成一章无名章节后，重命名对话框出现 3 个胶囊；关闭 `autoCheck` 时不生成。
- **依赖**：F1、N1。

## 14. 灵感页 · 灵感扩展（价值第 14，M5，工作量 S）
- **入口**：灵感条目菜单 →"展开成 3 个方向""AI 帮我归类"。
- **流程**：
  - "展开"：在条目下面显示 3 个方向卡，每个可以"存为新灵感"或"转设定卡 / 伏笔"（预填）。
  - "归类"：AI 判断这条灵感适合转成设定卡、伏笔还是章纲，并给出预填内容，作者确认后才转换。
- **提示词**：
  ```
  展开 user: 【灵感】{text} 【所属作品总纲】{可空} 输出：[{"title":"≤10字","idea":"≤60字"}] 3 个，方向要彼此不同。
  归类 user: 【灵感】 【作品设定卡名列表】 输出：{"kind":"card|foreshadow|outline","card":{"type":"","name":"","fields":{}},"foreshadow":{"content":"","payoffIn":章数},"outline":{"chapter":章号,"note":""}}
  ```
- **数据**：迁移 5 → 6，`ideas` 加 `expansions TEXT NOT NULL DEFAULT '[]'`。
- **文件**：修改 `ui/routes/IdeasRoute.kt`。
- **测试**：截图 `53-idea-expand`。
- **依赖**：F1。

## 15. 设定库 · 问设定（价值第 15，M4，工作量 S）
- **实现**：复用第 1 条的 `AskPanel`，模式参数是 `scope = Lore`。
- **入口**：设定库搜索框右侧的"问"按钮；搜索页（`SearchRoute`）结果为空时显示"问问 AI"。
- **区别**：
  - 上下文换成"按章生效的全部状态 + 关系 + 检索结果"，在提示词里明确"以第 {当前章} 章时为准"；
  - 回答必须附出处；没有出处的句子显示成灰色斜体，并提示"推断"。
- **验收**：问"沈砚第 3 章是什么境界"时，回答"筑基【设定：沈砚 · 第 3 章起】"（示例数据，对应 `DataShotTest` 的种子数据）。
- **依赖**：第 1 条。

## 16. AI 读者反馈（价值第 16，M6，工作量 L）
- **定位**：模拟目标读者读本章，给出追读意愿和弃书点。**不评判 AI 率，也不做"降 AI 检测"**。
- **入口**：编辑器 ⋮ →"读者试读"；作品页 → 统计 tab →"读者反馈"列表。
- **流程**：
  1. 选读者画像，可以多选，最多 3 个：
     - 「老白（阅读量大、挑剔逻辑）」
     - 「小白（追爽点）」
     - 「女频读者（重情感）」
     - 「自定义」
  2. 选范围：本章，或第 N–M 章（最多 5 章）。
  3. 结果页包括：
     - 每个画像的追读意愿（1–5 颗星，配一句话）；
     - 弃书风险点：原文位置 + 原因，点击跳回原文；
     - 爽点和期待点；
     - "最想看到的下一章"；
     - 三条可执行的改进建议，可以存到便签或章纲。
  4. 历次报告可以对比：同一章改稿前后的星级变化。
- **界面状态**：多画像时并发请求，每个画像一张卡依次出现；单个画像失败可以单独重试。
- **提示词**：
  ```
  system: 你在扮演一位{画像描述}的网文读者，刚读完下面的章节。用读者口吻、诚实地反馈，不要客气，也不要替作者改写。严格只输出 JSON。
  user: 【作品简介】【前情摘要】PrevSummaries(3) 【章节】{text，超过 8000 字截取开头 3000+结尾 3000，并说明截取}
    输出：{"stars":1-5,"oneLine":"≤30字","drops":[{"quote":"≤20字","why":"≤30字"}],"hooks":[""],"wantNext":"≤40字","advice":["≤40字"×3]}
  ```
- **数据**（迁移 6 → 7）：
  ```sql
  CREATE TABLE reader_reports(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, fromChapter INTEGER NOT NULL,
    toChapter INTEGER NOT NULL, persona TEXT NOT NULL, result TEXT NOT NULL, contentHash TEXT NOT NULL, createdAt INTEGER NOT NULL);
  CREATE INDEX index_reader_reports_bookId ON reader_reports(bookId);
  ```
  `contentHash` 用来对比改稿前后。
- **文件**：新增 `ui/routes/ReaderRoute.kt`、`data/ReaderPrompts.kt`；修改 `ui/routes/EditorRoute.kt`、`ui/routes/StatsRoute.kt`（`BookStatsTab`）、`data/Backup.kt`。
- **验收**：
  1. 选 2 个画像 → 两张结果卡依次出现 → 点弃书点跳到正文对应位置并高亮 → 改稿后再测一次，列表显示星级变化箭头；
  2. 费用提示：开始前显示"预计消耗约 X tokens"，用 `fitBudget` 估算。
- **测试**：`ReaderTest`、截图 `54-reader-setup`、`55-reader-report`。
- **依赖**：F1、F2、迁移 6 → 7。
