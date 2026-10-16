# AI 辅助编写总纲：从创意到第一章的完整链路

> 用户要求：创意 → 总纲 → 卷纲 → 章纲 → 第一章正文，每一步都有 AI 辅助生成，并与创作向导、现有大纲模块衔接。
> 路径都相对 `android/app/src/main/java/com/lingwrite/app/`。

## 1. 链路总览
```
① 创意    →  ② 核心设定  →  ③ 总纲      →  ④ 卷纲        →  ⑤ 章纲         →  ⑥ 第一章
一句话灵感    卖点/主角/世界   主线+阶段+结局   N 卷：目标/高潮    本卷前 N 章：      辅助：节拍清单+开篇示意
→3个梗概      （复用向导步骤）  （分段生成）     /章数              事件/钩子/悬念      生成：整章草稿候选
```
- **一条链路，两个入口**：
  - 新书：创作向导（`WizardRoute`）。7 步改成 6 段，与链路一一对应，见第 3 节。
  - 已有作品：大纲页顶部的"总纲工作台"（新增 `ui/routes/ChainRoute.kt`，路由 `chain/{bookId}`）。可以从任意一步开始，例如已经有总纲，就直接拆卷。
- **共同规则**：
  - 每一步都是 AI 给候选，作者微调后采纳；
  - 每一步都可以跳过，手写也能继续；
  - 采纳结果**写进现有的大纲数据结构**（`BookPlanEntity`、`VolumePlanEntity`、`ChapterPlanEntity`），大纲页能立刻看到；
  - 改了上游时，下游显示"上游已改，可重新生成"的提示，不自动覆盖。

## 2. 数据模型（迁移 3 → 4，里程碑 M3）
```sql
ALTER TABLE book_plans ADD COLUMN idea TEXT NOT NULL DEFAULT '';           -- ① 一句话创意
ALTER TABLE book_plans ADD COLUMN logline TEXT NOT NULL DEFAULT '';        -- ① 采纳的梗概
ALTER TABLE book_plans ADD COLUMN sellingPoints TEXT NOT NULL DEFAULT '[]';-- ② 卖点 JSON 数组
ALTER TABLE book_plans ADD COLUMN chainStep INTEGER NOT NULL DEFAULT 0;    -- 已完成到第几步（0–6）
ALTER TABLE book_plans ADD COLUMN chain TEXT NOT NULL DEFAULT '{}';        -- 各步未采纳的候选缓存 + 上游哈希
ALTER TABLE volume_plans ADD COLUMN goal TEXT NOT NULL DEFAULT '';         -- ④ 本卷目标
ALTER TABLE volume_plans ADD COLUMN climax TEXT NOT NULL DEFAULT '';       -- ④ 本卷高潮
ALTER TABLE volume_plans ADD COLUMN chapterCount INTEGER NOT NULL DEFAULT 0; -- ④ 预计章数
ALTER TABLE chapter_plans ADD COLUMN beats TEXT NOT NULL DEFAULT '[]';     -- ⑥ 场景节拍 [{text,done}]
ALTER TABLE chapter_plans ADD COLUMN titleIdeas TEXT NOT NULL DEFAULT '[]';
```
- `BookPlanEntity.wizard`（现有，向导的 JSON 状态）在迁移时把 `idea`、`pitches`、`synopsis` 拷进新列。读取时，新列优先。
- 写一个数据类 `ChainState`（存在 `chain` 列里），包含：
  - `cands: Map<Int, String>`：每一步未采纳的候选 JSON；
  - `upstream: Map<Int, Int>`：每一步采纳时上游内容的 hashCode，用来判断"上游已改"。
- 备份：`book_plans`、`volume_plans`、`chapter_plans` 本来就在备份表里，新加的列随整行导出，**不需要改备份代码**，但要补一个往返测试。

## 3. 各步规格
所有提示词放在新增的 `data/ChainPrompts.kt`。系统提示统一为：
```
你是资深网文主编，正在和作者一起从零搭一本长篇。你给出的都是候选，作者会挑选和修改。
贴合作者的题材和平台习惯，具体、有卖点，不写空话。严格只输出 JSON。
```
每一步都会带入"上游已采纳的内容"，记作 `{U}`：
- ① 之后：idea、logline
- ② 之后：再加 genre、platform、sellingPoints、主角卡、核心配角卡、世界观卡摘要
- ③ 之后：再加 synopsis
- ④ 之后：再加对应卷的 outline、goal、climax

### ① 创意 → 梗概
- 输入：一句话创意（必填），题材（可选），目标平台（起点 / 番茄 / 晋江 / 其他）。
- AI：给 3 个方向不同的梗概，每个包含：
  - `title`：暂定书名；
  - `logline`：60 字；
  - `hook`：最大卖点。
  - 作者可以选一个，也可以"组合"：勾选 2 个后点"融合"，再生成 1 个。
- 提示词：`user: 【创意】{idea}【题材】{genre}【平台】{platform} 输出：[{"title":"","logline":"≤60字","hook":"≤20字"}]×3，三者方向必须明显不同（如：爽文升级 / 悬疑反转 / 群像）。`
- 采纳后写入：`book_plans.idea`、`logline`；`books.genre`。
- 和现有代码的关系：与向导第 1 步（`WizardPrompts.pitches`）合并成同一个提示词，向导改为调用 `ChainPrompts.pitches`。

### ② 核心设定（卖点 / 主角 / 世界）
- 复用向导第 2–4 步（`StepStyle`、人物、世界观），和链路共用同一套组件。
- AI 依次产出：
  - 3–5 条卖点；
  - 主角卡和 2–4 个核心配角卡；
  - 世界观卡：力量体系、主要势力、关键地点。
- 采纳后写入：
  - `sellingPoints`；
  - 设定卡，`LoreCardEntity(pending = false)`；从总纲工作台进入时，先在列表里勾选，再统一写入。
- 提示词：沿用现有的 `WizardPrompts.characters` / `world`，把带入内容换成 `{U}`。

### ③ 总纲（分段生成）
- AI：分 3 段生成并分段展示，可以"逐段重写"：
  - 主线一句话：≤40 字；
  - 阶段：3–6 个阶段，每个包含目标、主要冲突、阶段结局；
  - 结局走向：≤120 字。
  - 三段合起来拼成 `synopsis` 文本，方便兼容现有代码。
- 提示词：`user: {U} 输出：{"spine":"≤40字","stages":[{"name":"≤8字","goal":"","conflict":"","end":""}],"ending":"≤120字"}`
- 微调：每段都是可编辑的文本框；"重写这段"会带上其他段作为约束重新生成。
- 采纳后写入：`book_plans.synopsis`，拼接格式为"主线：…\n阶段一 xx：…\n…\n结局：…"。这个格式由新增的 `SynopsisFormat` 生成和解析，后面的步骤会解析出 stages。

### ④ 卷纲
- 输入：卷数（默认等于阶段数，可以改为 1–12）、每卷预计章数（默认 60）。
- AI：每卷给出：
  - 卷名：不带"第几卷"，遵守 `naming-bug.md` 的规范；
  - 目标、高潮；
  - 150 字卷纲、预计章数。
- 提示词：启用现有的 `OutlinePrompts.volumes` 并改为：
  `user: {U} 【卷数】{n} 输出：[{"name":"卷名≤8字，不要写第几卷","goal":"","climax":"","outline":"≤150字","chapters":数字}]`
- 采纳：勾选要的卷，按顺序创建 `VolumeEntity(title = name)` 和 `VolumePlanEntity(outline, goal, climax, chapterCount)`。
  - 已有卷时，提供"覆盖同序号的卷纲（保留卷内章节）"或"追加在后面"两个选项。
- 和大纲页的关系：大纲页每张卷卡片显示 goal 和 climax 两行，点开卷纲 Sheet 可以编辑。

### ⑤ 章纲
- 范围：选一卷，再选"本卷前 N 章"（默认 10），或者"接着已有章纲往后 3 / 5 章"（滚动规划，即 ai-gaps 第 7 条）。
- AI：沿用现有的 `OutlinePrompts.chapters(...)`。字段固定为 event、hook、cliff，加上自定义字段（`BookPlanEntity.fields`）和线索（threads）。
  - 第一卷前 3 章自动套用"黄金三章"约束（`DEFAULT_GOLDEN`），生成后自动跑一次 `OutlinePrompts.golden` 检查，并在每章旁边显示 ✓ / 待改。
- 采纳：沿用 `OutlineRoute.kt` 现有的勾选采纳流程（"采纳 N 章"），章节不存在时用 `repo.addChapterTo` 创建空章节。

### ⑥ 第一章正文（按模式区分，见 `modes.md`）
- **辅助模式**：
  - 生成本章场景节拍 5–8 条，每条 ≤30 字，写进 `chapter_plans.beats`。
  - 再给 3 种开篇写法示意（每种 ≤80 字，并说明这种写法的效果），**只放进本章便签，不进正文**。
  - 进入编辑器后，节拍清单显示在顶部，可以折叠，写完一条勾一条。
- **生成模式**：
  - 先生成节拍，作者确认。
  - 然后"起草本章"：按节拍分 2–3 次请求，每次 1200–1500 字，流式写进**草稿区**（编辑器下方的独立区域，不是正文）。
  - 草稿区可以分段"采纳到正文"（标为 `gen`）或"微调后采纳"（标为 `edited`），也可以整章"换一版"。
- 提示词：
  ```
  节拍 user: {U} 【本章章纲】{event/hook/cliff} 【文风卡】{style} 输出：["≤30字"×5–8]，第一条必须在 300 字内抛出钩子。
  起草 system: Prompts.BASE + 文风 + "这是第一章，前 500 字必须出现钩子；不要大段交代背景，设定在冲突中带出。"
     user: 【本章节拍】{beats，标出本次要写的第 i–j 条}【已起草部分】{上一段结尾 800 字}【设定】ContextEngine.build(...)
  ```
- 采纳后：`chainStep = 6`，总纲工作台显示"链路完成"，并提供"打开第一章"按钮。

## 4. 与创作向导的衔接
| 向导现有步骤（`WizardRoute.Steps`） | v0.2 对应 |
|---|---|
| 1 灵感 | ① |
| 2 类型与文风 | ②（文风部分） |
| 3 主角和核心人物 | ② |
| 4 世界观 | ② |
| 5 总纲和第一卷 | ③ + ④（只生成第一卷，可勾选"同时规划全部卷"） |
| 6 书名和简介 | 保留，放在 ③ 之后；也可以在作品信息里用（ai-gaps 第 10 条） |
| 7 前三章章纲 | ⑤（第一卷前 3 章）+ 新增的 ⑥ |
- 向导的步骤数从 7 变成 8（新增"第一章"）。顶部进度条显示 8 段。
- 向导的"完成"按钮变成"去写第一章"。
- `WizardData` 继续作为向导的 UI 状态，采纳时同步写进 `book_plans` 的新列，避免两套数据分叉。
- 中途退出向导后，在总纲工作台里能看到同样的进度（共用 `chainStep`）。

## 5. 总纲工作台界面（`ChainRoute`，hifi 稿 `ui-hifi/src/10-chain.html`）
- 顶栏：标题"总纲工作台"，副标题是书名。
- 顶部：6 个步骤的纵向时间线，每个节点三种状态：已完成（✓，`primary`）、当前（`primary` 实心 + 呼吸光晕，减弱动画时为静态）、未开始（`outline`）。
- 当前步骤展开为卡片：输入区 →"AI 生成"按钮 → 候选区（`AiCandidateCard` / 多选列表）→ 底部"采纳并继续"。
- 已完成的步骤折叠成一行摘要，可以点开修改。修改后，下游节点显示琥珀色的"上游已改"。
- 底部固定条：显示"本链路已用 xx tokens"。

## 6. 任务卡（见 v0.2-plan.md 里程碑 M3）
| 卡 | 内容 | 主要文件 | 验收 |
|---|---|---|---|
| C1 | 迁移 3 → 4 + `ChainState` + `SynopsisFormat` | `data/db/PlanEntities.kt`、`LingDatabase.kt`、新增 `data/Chain.kt` | MigrationTest 通过；SynopsisFormat 能往返 |
| C2 | `ChainPrompts`（①–⑥）+ 解析 | 新增 `data/ChainPrompts.kt` | 单元测试：每个提示词都包含 `{U}` 的关键字段，mock 返回能正确解析 |
| C3 | 总纲工作台 UI（时间线、步骤卡、上游已改） | 新增 `ui/routes/ChainRoute.kt`、修改 `OutlineRoute.kt` 入口 | 截图 `56-chain-synopsis`、`57-chain-volumes` |
| C4 | ④ 拆卷 + 大纲页卷卡显示 goal / climax | `OutlineRoute.kt` | 拆 4 卷并采纳 3 卷，大纲页显示 3 卷 |
| C5 | ⑥ 第一章：节拍清单（辅助）/ 草稿区（生成） | `EditorRoute.kt`、`EditorAi.kt`、新增 `ui/routes/DraftPane.kt` | 截图 `58-first-chapter-assist`、`59-first-chapter-draft` |
| C6 | 向导改成 8 步，共用 Chain 组件 | `WizardRoute.kt`、`data/Wizard.kt` | `WizardShotTest` 更新；向导走完后 `chainStep = 6` |

## 7. 端到端验收（M3 完成标准）
用 MockWebServer 依次返回 ①–⑥ 的结果，在一个 Robolectric 测试（`ChainE2ETest`）里走完全程：
1. 新建作品 → 向导 ① 采纳第 2 个梗概 → ② 采纳主角和 2 个配角 → ③ 采纳总纲 → ④ 4 卷里采纳 3 卷 → ⑤ 第一卷前 10 章章纲全部采纳 → ⑥ 生成模式下起草并采纳第 1 段。
2. 断言：
   - `book_plans.logline`、`synopsis` 不为空；
   - 有 3 个卷，卷名不带编号；
   - 第一卷有 10 个 `chapter_plans`；
   - 第 1 章正文非空，并且带 `gen` 标记；
   - `chainStep = 6`；
   - `call_logs` 里依次出现 wizard、outline、draft 等任务。
3. 切换到辅助模式重跑第 ⑥ 步：正文仍然为空，`chapter_plans.beats` 有 5–8 条，`chapter_notes` 多 3 条开篇示意。
