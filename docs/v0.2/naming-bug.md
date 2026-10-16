# 卷名 / 章名编号不一致：原因、修复方案与全 App 命名规范

> 用户反馈：第一卷显示中文数字「一」，新建的卷显示阿拉伯数字「2」。

## 1. 确切原因
卷名是**创建时写死进 `VolumeEntity.title` 的字符串**，几处生成代码用了不同的数字写法：

| 位置（`android/app/src/main/java/com/lingwrite/app/`） | 代码 | 结果 |
|---|---|---|
| `data/BookRepository.kt:20`（`createBook`，新建作品） | `VolumeEntity(bookId = id, title = "第一卷", sort = 0)` | 第一卷（中文数字） |
| `data/BookRepository.kt:58`（`nextChapter`，没有卷时兜底） | `title = "第一卷"` | 第一卷 |
| `data/Wizard.kt:23`（`WizardData.volumeTitle` 默认值）→ `ui/routes/WizardRoute.kt:121–128` | `"第一卷"`，AI 生成时会被替换成任意格式 | 第一卷，或 AI 自由发挥（如"卷一：入门"） |
| **`ui/routes/WorkRoute.kt:75`（作品页 ⋮ → 新建分卷）** | `mutableStateOf("第${vols.size + 1}卷")` | **第2卷（阿拉伯数字，无空格）** ← bug 来源 |

用的是同一字体 MiSans。在 MiSans 里，"一"是一笔细横，"2"是比例宽度的拉丁数字，字形宽度和视觉重量差别很大，所以两卷标题看起来"字不一样"。

字重没有不同：作品页卷标题统一用 `Ling.type.label`（Medium，`ui/screens/WorkScreen.kt:92`）。

## 2. 同类不一致（一并修复）
| # | 位置 | 现状 | 问题 |
|---|---|---|---|
| A | 同一卷在不同页面的字重 | 作品页 `Ling.type.label`（Medium）；大纲页 `label.copy(fontWeight = SemiBold)`（`ui/routes/OutlineRoute.kt:134`） | 同一个卷名在两个 tab 粗细不同 |
| B | 新书第一章的默认标题 | `BookRepository.kt:21` 用 `title = "第一章"` | 编辑器顶栏显示成"第 1 章 第一章"，编号重复 |
| C | 后续章节的默认标题 | `BookRepository.kt:61` 用 `title.ifBlank { "第 $n 章" }` | 显示成"第 2 章 第 2 章"；而且章节被移动、重编号（`renumber`）后，标题里的数字不会跟着变 |
| D | 章节号的空格 | 界面大多写"第 ${n} 章"（有空格）；导出写 `"第${c.number}章 "`（`data/Backup.kt:206`）；通知写 `"第${c.number}章 检查完成"`（`ui/routes/AboutRoute.kt:68`） | 同一个概念有两种写法 |
| E | 卷编号随移动失效 | `moveVolumeIn` 只改 `sort`，标题里的"第一卷"不会变 | 上移以后，"第二卷"排在第一位 |
| F | 示例数据 | `BookRepository.seedSample` 用"第一卷 · 山门初入"（` · ` 分隔） | 用户新建的卷没有副标题分隔规范 |
| G | 向导 AI 生成的卷名 | 格式由模型决定 | 可能出现"卷一""第1卷：" |

## 3. 统一规范（v0.2 起）
**原则：编号由 App 根据位置计算，不存进标题；标题只存作者起的名字（可以为空）。**

### 3.1 卷
- 存储：`VolumeEntity.title` 只存卷名，例如"山门初入"，可以为空。
- 显示：`VolumeLabel.full(index, title)`。
  - 有卷名时显示"第一卷 · 山门初入"，没有时显示"第一卷"。
  - 卷序号用**中文数字**：一、二、……、十、十一、……、二十、二十一、……、九十九、一百。超过 999 卷时退回阿拉伯数字（实际不会出现）。
  - 分隔符固定为 ` · `（空格 + U+00B7 + 空格）。
- 卷序号 = 该作品按 `sort` 排序后的下标 + 1，在显示时计算。移动卷后自动更新。
- 简写：空间不够时（比如书架大卡副标题）只显示"第二卷"。

### 3.2 章
- 存储：`ChapterEntity.title` 只存章名，例如"擂台余波"，可以为空；`number` 是全书连续编号。
- 界面显示：`ChapterLabel.full(number, title)`。
  - 显示为"第 118 章 擂台余波"：**阿拉伯数字，"第"和"章"两边各留一个半角空格**；没有章名时显示"第 118 章"。
  - 章号保持阿拉伯数字的理由：网文章号常过百上千，中文数字太长（"第一千二百零三章"）。卷数通常在 20 以内，所以卷用中文数字。两者并存是有意的规范，不算不一致。
- 导出和通知：
  - TXT 导出的章标题默认是"第118章 擂台余波"（数字两侧不留空格，符合起点和番茄作家后台的常见写法），并且**在导出选项里提供"章节号格式"**：`第118章` / `第 118 章` / `第一百一十八章`。
  - 通知统一用界面格式"第 118 章"。
- 章内引用（伏笔、问题卡、设定状态等）统一用"第 N 章"（带空格），由 `ChapterLabel.short(n)` 生成。

### 3.3 字体与字重
| 场景 | token |
|---|---|
| 卷标题（作品页、大纲页） | 统一用 `Ling.type.label` + SemiBold + `primary`，新增 token `Ling.type.volumeHeader` |
| 章标题（行内） | `Ling.type.bodyLarge` |
| 章号（行首） | `Ling.type.label` + `onSurfaceVariant` + 等宽数字（`fontFeatureSettings = "tnum"`） |

## 4. 修复方案（任务卡 N1，属于里程碑 M1）
1. 新增 `data/Naming.kt`：
   - `object CnNum { fun of(n: Int): String }`，覆盖 1–999；
   - `object VolumeLabel { fun full(index: Int, title: String): String; fun short(index: Int): String }`；
   - `object ChapterLabel { fun full(n: Int, title: String); fun short(n: Int); fun export(n: Int, title: String, style: ExportNumStyle) }`。
2. **Room 迁移 1 → 2**（`data/db/LingDatabase.kt` 的 `MIGRATIONS`）：把旧标题里的编号前缀去掉。
   ```sql
   -- 在 Migration.migrate 里用 Kotlin 逐行处理（SQLite 没有正则）：
   -- 卷：去掉开头的 ^第[一二三四五六七八九十百零〇0-9]+卷\s*([·:：\-—]\s*)?
   -- 章：去掉开头的 ^第[一二三四五六七八九十百千零〇0-9]+章\s*，剩余为空则存 ''
   ```
   实现：`db.query("SELECT id,title FROM volumes")`，逐条计算新值后执行 `UPDATE`；chapters 同样处理。迁移测试见第 5 节。
3. 修改以下生成点，不再往标题里写编号：
   - `BookRepository.createBook`：`VolumeEntity(title = "")`、`ChapterEntity(title = "")`；
   - `nextChapter`：`title.ifBlank { "" }`；
   - `WorkRoute` 新建分卷对话框：输入框改成"卷名（可空）"，上方提示"将创建为 第${CnNum.of(n)}卷"；
   - `WizardData.volumeTitle` 默认值改为 `""`；向导提示词改成要求"卷名只写名字，不带'第几卷'"，入库前用迁移里的同一个正则清洗一次。
4. 替换所有显示点（`grep -rn '"第 \${\|"第\${' --include=*.kt`，共 20 多处），改用 `VolumeLabel` / `ChapterLabel`。重点：
   - `ui/screens/WorkScreen.kt:92`、`ui/routes/OutlineRoute.kt:134`（同时统一字重）；
   - `ui/routes/EditorRoute.kt:112`、`data/Mappers.kt:29/45`、`data/BookRepository.kt:41`；
   - `ui/routes/WorkRoute.kt:57`、`ui/routes/ContextRoute.kt:217`、`ui/routes/AboutRoute.kt:68`、`data/Backup.kt:206`（导出走 `ChapterLabel.export`）。
   - 示例数据 `seedSample` 改成存"山门初入""宗门大比"。
   - `FakeData` 只供截图使用，同步改成规范格式。
5. 测试：
   - `NamingTest`（JVM 单元测试）：`CnNum` 的 1/2/10/11/19/20/21/99/100/101/110/999；`VolumeLabel` 有无卷名两种情况；`ChapterLabel.export` 三种格式。
   - `MigrationTest`（Robolectric + `MigrationTestHelper`，需要在 `build.gradle.kts` 加 `testImplementation(libs.room.testing)`，并把 `app/schemas` 加进 `sourceSets.test.assets`）。用 v1 schema 插入"第一卷""第2卷 · 风起""第一章""第 2 章""第 3 章 夜雨"，迁移后断言为 `""`、`"风起"`、`""`、`""`、`"夜雨"`。
   - 截图：`02-work`、`09-outline` 里两卷标题字形一致，并且都显示为"第一卷 · …""第二卷 · …"。
6. 验收：新建作品 → 新建分卷 → 作品页和大纲页显示"第一卷""第二卷"，字重相同；把第二卷上移后自动变成"第一卷"；编辑器顶栏不再出现"第 1 章 第一章"；导出可以选章号格式。
