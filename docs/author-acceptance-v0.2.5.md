# v0.2.5 作者流程验收

基线：`12a5241dbe08bcc884dfa6c70c4779501f2bb0ab`。只使用合成稿件，不使用真实模型、不调用付费服务。此记录按实际入口组织，不将某层测试通过解释为所有设备、输入法或真实模型全覆盖。

## 证据层级

- **D**：真实 Room/FTS 数据与产品函数；不是复制算法的模拟。
- **R**：产品实际请求链路，受控假 provider 或本地 MockWebServer；核对请求、响应、错误和落库。不证明文学质量或真实服务商兼容性。
- **U**：Robolectric Compose；注明静态渲染与真实 handler 的区别。
- **E**：任务专用 Android 35 x86_64 模拟器，WHPX，320×640。`AuthorDeviceTest` 的交互与注册 Route 渲染；不是实体手机。

最终统计：45 个 JVM/Compose 测试类、205 项用例，0 失败/错误/跳过；lint 0 错误、39 警告。设备 runner 13 项用例通过（手机 7 项、平板 6 项），包含 42 个实际注册入口截图；320×640、1.5 倍字号下复核了首次两模式选择、删除确认/取消/恢复、编辑撤销重做、章纲、AI 旧结果、引导跳过及全部入口渲染。正常字号下先前 6 项设备用例同样通过。上版正式包覆盖升级保留 8 章示例及正文，证书一致。

测试命令：`cd android && gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`。设备可用标准 runner 执行：`adb -s <专用设备> shell am instrument -w -e class com.lingwrite.app.AuthorDeviceTest com.lingwrite.app.debug.test/androidx.test.runner.AndroidJUnitRunner`。Gradle UTP 的一次依赖下载被连接重置阻碍，直接 runner 执行同一测试 APK，不跳过测试。

## 统一长篇与修订

`AuthorJourneyTest` 使用《雾港灯谱》3 卷 18 章，6 类设定、同名人物、另一同名作品 `B_ONLY`，第 4/10/18 章的 `FUTURE_C04/C10/C18` 正文/摘要/状态；第 3 章回写必须排除未来事实、未确认和“从不”设定。总纲中的 `PLANNED_C18` 是未来规划，允许带入。章纲备注 `KEEP_AUTHOR_NOTE` 必须保留。包含空白第 7/8 章、18100 字长章、人物关系按章变化、同名关系对象拒绝猜测、手工确认摘要与迟到自动摘要竞争。额外扩展到 12 卷并核验候选所属卷。

`AuthorSafetyTest` 覆盖离开后立即重进、并发正文/标题/状态、回收站与历史、旧备份、损坏备份、图片路径、问答关联、JSON 内候选卷与关系对象 ID、导出卷序、重排后的检索、混合单字查询、短更请求、校对失败和中文组合态/撤销重做。

## 全入口矩阵

“通过”只针对列明的路径。U 渲染、E Route 渲染不代表该页所有按钮及每种异常都经过真实设备交互。真实模型、真实输入法和系统特性边界见最后一节。

| # | 作者步骤与真实入口 | 已验证的路径与证据 | 结果/边界 |
|---|---|---|---|
| 1 | 首开、书架、新建、继续写 | RepositoryTest、AuthorSafetyTest；E 正式包示例建书/升级后继续写；E 320×640 小屏两种首次模式可达；Shelf Route | D/E 通过 |
| 2 | 8 步创作向导 | WizardShotTest；ChainAiE2ETest 真实请求/坏 JSON 重试/采纳共用链路；Wizard Route | R、U/E 渲染通过；未把 8 页逐按钮声称为 E 全交互 |
| 3 | 六类设定、同名卡 | LoreTest、AuthorJourneyTest 六类卡/同名 ID；六类 Lore Route | D、U/E 渲染通过 |
| 4 | AI 起名、补空字段、整理建议 | LoreSuggestTest、M4ShotTest；AuthorJourneyTest 实际整理请求、同名目标拒绝猜测 | D/R 通过；真实模型质量未测 |
| 5 | 按章状态、关系图、地图 | LoreTest、AuthorJourneyTest 第 3/5/10/12/18 章状态关系；graph/maps Route | D、U/E 渲染通过；地图触控绘制未逐设备穷举 |
| 6 | 总纲、工作台、上游变化 | ChainTest、ChainAiE2ETest、Chain Route ①–⑥ | D/R、U/E 渲染通过 |
| 7 | 卷纲生成、局部覆盖/追加 | AuthorSafetyTest 保留原候选下标；ChainAiE2ETest | D/R 通过 |
| 8 | 卷章重排、删除、恢复 | RepositoryTest；AuthorSafetyTest 保留正文/章纲/历史，重排索引；AuthorSafetyUiTest/AuthorDeviceTest 删除确认、取消、恢复 | D/U/E 交互通过；按数字定义的时间规则需作者重排后核对其故事语义 |
| 9 | 手工章纲、自定义字段、模板 | OutlineTest/OutlineShotTest；E ChapterPlanSheet 修改核心事件且保留 KEEP_AUTHOR_NOTE | D/E 交互通过 |
| 10 | AI 拆章纲、滚动规划、切卷 | ChainAiE2ETest；AuthorJourneyTest 实际请求/缓存原卷；切卷清候选且可达全部 12 卷 | D/R 通过，E 工作台渲染 |
| 11 | 多线视图、黄金三章、落实检查 | OutlineTest、RouteShotTest；threads/golden/outline Route | D、U/E 渲染通过；真实评估质量未测 |
| 12 | 节拍、便签、分段草稿 | ChainAiE2ETest、ModeAcceptTest；U 草稿结束可全部采纳、流中禁止；editor Route | D/R/U 交互通过，E 渲染 |
| 13 | 中文编辑、保存、撤销重做、切章 | AuthorSafetyTest 组合态/立即重进/并发；E 中文替换、撤销重做与等待保存 | D/E 交互通过；实体拼音输入法、前台瞬时强杀边界未测 |
| 14 | 伏笔登记、关联、临期、回收 | CheckShotTest、fsState；AuthorJourneyTest 前章/未来伏笔隔离；Work 伏笔/Editor 入口 | D、U/E 渲染通过；全套手动回收按钮未声称逐设备验证 |
| 15 | AI 回收方案、追加章纲、撤销 | ProofTest/CheckShotTest；AuthorSafetyTest 原子条件撤销保护后续人工备注；PayoffIdeas/CheckRoute | D/U 渲染、E 入口通过；非 E 全交互 |
| 16 | 两处“完成本章”与自动任务 | WorkRoute/EditorRoute 同一 onChapterDone；Context/Check 模块；AuthorJourneyTest 迟到摘要保护 | D/R 通过，E 入口渲染 |
| 17 | 摘要、上下文、回改前章 | BriefTest/ContextShotTest；AuthorJourneyTest 真实请求禁止未来哨兵、保留未来规划、作者确认优先 | D/R 通过 |
| 18 | 一致性问题、改法、修订闭环 | IssueFixTest；AuthorJourneyTest 实际抽取/检查请求；issues Route | D/R、U/E 渲染通过 |
| 19 | AI 选区、批注、校对、历史比较 | AuthorSafetyUiTest 迟到建议拒绝/重复句第二处；E 迟到结果拒绝；ProofTest/RepositoryTest/AuthorSafetyTest | D/U/E 交互通过 |
| 20 | 随手问、问设定、胶囊 | AskShotTest/BriefTest；AuthorSafetyTest 合并不串章；前章状态与上下文约束 | D/U、E 入口通过；胶囊的所有组合未逐设备穷举 |
| 21 | 本书/全局搜索、语义检索 | ContextShotTest/BriefTest；AuthorSafetyTest AND 单字条件、重排章号与索引失效；Search Route | D/U/E 渲染通过；外部向量服务不调用 |
| 22 | 文风卡、AI 味检查 | StyleShotTest、Style/Proof 数据函数；style Route | D/U、E 渲染通过；不证明真实模型文风质量 |
| 23 | 灵感、归类、转设定/伏笔/章纲 | M5ShotTest/ProofTest；修复扩展卡转化到真实保存入口；Ideas Route | D/U、E 入口通过；转化所有组合未逐设备穷举 |
| 24 | 连载、统计、短更 | SerialTest；AuthorSafetyTest 实际短更请求带已有后续章纲、完成非空才计存稿；Stats Route | D/R/U/E 渲染通过 |
| 25 | 书名、简介、章名、上架文案 | NamingTest、M5ShotTest、BookAi/ContextEngine 请求路径 | D/U、E 入口通过；真实模型命名质量未测 |
| 26 | 拆文、模板应用 | BreakdownTest 原文不落库/模板不覆盖已有计划；Breakdown Route；修正会发送样章的隐私告知 | D/U/E 渲染通过；样章不发真实服务 |
| 27 | 模拟读者与历史报告 | M6ShotTest/ReaderPrompts；reader Route | D/U/E 渲染通过；模拟结果不是实际读者反馈 |
| 28 | 服务商、写作/自动模型 | AdapterTest/AiServiceTest/ModeToolsTest；本地 HTTP、SSE、鉴权错误、模型槽位；Models/Provider Route | D/R/U/E 渲染通过；不配置真实 Key |
| 29 | 调用、采纳与成本统计 | AiServiceTest/CandidatesTest/StatsShotTest；部分候选失败保留原候选序号，空/失败不可采纳 | D/R/U/E 渲染通过；不产生真实费用 |
| 30 | TXT/MD 导出 | DataShotTest；AuthorSafetyTest 前卷插章导出顺序；Export Route | D/U/E 渲染通过；系统文件选取器全部云存储未测 |
| 31 | ZIP 合并/覆盖/旧版/图片/关系 ID | DataShotTest、AuthorSafetyTest 正文/历史/回收站/问答/JSON 关联/坏档保护 | D/U/E 入口通过；磁盘硬件故障不声称全覆盖 |
| 32 | 外观、通知、关于、更新、升级 | ReducedMotionTest/AboutShotTest/UpdateShotTest；E 设置/通知/关于/更新 Route；正式包 v0.2.4→0.2.5 同证书覆盖 | D/U/E 渲染及升级通过；私有仓库匿名更新渠道受阻 |
| 33 | 首次动画指导 | AuthorSafetyTest 旧用户默认关闭、偏好续接；U/E 跳过不造书不改稿；真实入口跳转/编辑器不遮正文/减少动画 | D/U/E 已验证路径通过；平板竖屏大字号实际打开设定库通过；实体设备组合未测 |
| 34 | 平板写作与列表—详情 | TabletDeviceTest 6 项：实际 MainActivity 连续调整窗口，同一稿件/选区/组合态/流式请求保留；真章纲保存、设定/伏笔/总纲表单、滚动位置与引导真实跳转 | E 交互通过；合成组合态不等于真实拼音输入法 |

## 平板的实际工作布局

以当前窗口可用宽度为准（dp）：小于 720 为手机单栏；720–1199 默认目录＋正文，打开辅助后为正文＋工具两栏；1200 及以上可同时显示目录、正文和可收起辅助栏。正文最大行宽 800dp，字体由用户偏好决定。辅助栏使用本章真实章纲表单、按当前章查看的真实设定卡、真实伏笔台账和原 AI 面板；目录提供实际切章、写下一章和章节管理入口。大纲、设定库与伏笔页面在宽窗采用列表—详情，窄窗显示当前列表或详情。

布局使用固定的 composition 槽位，调整窗口只改测量和放置，不重新挂载正文、表单或 AI 请求。隐藏栏从无障碍与焦点树移除。MainActivity 显式处理窗口/旋转/字号等配置变化，保留当前导航、编辑选区、撤销栈和内存会话；后台保存仍通过原生命周期路径兜底。Ctrl+Z、Ctrl+Shift+Z/Ctrl+Y、Ctrl+S 有实际处理；E 验证 Ctrl+Z/Ctrl+Y 和 Tab 焦点导航，Tab 不再覆盖选区为制表符，中文组合态期间不截获快捷键。

E 的窗口序列为 1400×900、800×1100、720×900、480×900，密度 160、字号 1.0/1.5。验证同一 Activity、未保存正文及章纲、TextRange 选区/合成组合态、撤销重做、真实流式请求只启动一次并继续完成、长稿滚动大于零的定位保留。真实总纲保存只更新 synopsis，保留同时改变的 idea；章纲字段合并保护其他字段并拒绝同字段冲突。新增 R 用例确认迟到落实检查拒绝旧结果并保留新 event/note。第 6 项 E 用真实 NavHost 和目录“写下一章”按钮验证章序、默认卷头、当前卷续章、后卷顺延，以及重排后已打开编辑器标题/章号刷新。异步建章之后显式切到主线程导航，失败显示错误提示。

设备命令可执行 `-e class com.lingwrite.app.TabletDeviceTest`。本测试会修改尺寸、密度、字号和系统动画，因此只应运行在专用合成数据设备，不能运行在用户真实手机。截图是实际应用和系统键盘，不是静态设计稿。窗口/字号变化后的章纲保存和引导入口在确认显示后调用实际按钮的语义 OnClick，避免系统 Surface 转换的坐标竞态；其余点击、键盘和目录新章导航包含实际坐标注入。不能将混合测试称为全部触控手势覆盖。合成 IME composition、尺寸变更和虚拟按键验证不代替实体平板、多家中文输入法和蓝牙键盘验收。

## 发现并修正

章节回收站与确认；按章串行/局部列保存及立即重进最新草稿；大段删除前自动历史；重做；选区/批注原文修订锚点；空/异常 AI 返回失败；完成草稿批量采纳；存稿口径/短更上下文；按当前章隔离前情、设定与伏笔；迟到摘要不覆盖作者确认；局部卷纲序号、章纲原卷绑定与全部卷可达；全文单字条件/索引失效；备份格式2、schema8 旧格式补默认值、图片暂存回滚、JSON 内关联重映射；灵感转化真实实体入口；两处完成章节统一回调；误导文案修正；新用户动画指导。

## 发布和边界

版本 `0.2.5` / versionCode `7`；数据库 `7→8` 显式迁移，全链 `1→8` 由 Room schema 校验。证书 SHA-256 必须为 `f31f5b4f455617f50337dc1bf1643023811d26464c3074e6cc706e4f1f3fce52`，与 v0.2.4 相同；正式构建由合并 commit 生成。

Android 35 模拟器验证不等于实体设备/所有 Android 版本验收。当前镜像无中文拼音输入法；组合态是 D 合成输入，E 是 Unicode 正文修改。前台尚未到自动保存窗口的瞬时强杀、实际低存储/断电、云文件提供者、真实通知送达和各服务商模型质量/兼容性仍未测。私有 GitHub 仓库的匿名应用内更新不可用；不公开仓库、不把凭据嵌入 APK，正式包通过授权 GitHub Release 下载。没有绕过分支保护或签名校验。
