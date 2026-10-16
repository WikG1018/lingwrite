# 规划与设计文档索引（内部）

安卓 AI 写小说 App「灵写」，应用源码位于 `android/`，支持章节编辑、设定库、分层大纲、伏笔与 AI 辅助。规划资料与当前实现请结合 [`PROGRESS.md`](../../PROGRESS.md) 阅读。

## 文档索引
| 文件 | 内容 |
|---|---|
| [novel-app-plan.md](../../novel-app-plan.md) | 项目计划主文档：已决事项、MVP 17 项、创作流程、AI 能力、技术架构、里程碑 |
| [author-research.md](../../author-research.md) | 真实作者写作流程与同类工具调研（附来源） |
| [plan-review.md](../../plan-review.md) | 计划审查与待拍板 / 已决事项 |
| [ui-plan.md](../../ui-plan.md) | 界面规划：页面结构、悬浮玻璃 dock、各页细节 |
| [ui-hifi/](../../ui-hifi/) | 高保真界面稿（浅色 / 深色 PNG，412×915 @2x）及 HTML 源码 `src/`，`overview-light.png` 为总览 |

## 说明
- 手机弹层与导航约定：[docs/overlay-contract.md](../overlay-contract.md)；v0.2.6 实际交互验收：[docs/author-acceptance-v0.2.6.md](../author-acceptance-v0.2.6.md)。
- 界面动效规范：[docs/motion-design.md](../motion-design.md)（时长、曲线、弹簧 token，转场、dock、按压反馈，无障碍降级和检查清单）。
- AI 功能缺口清单：[docs/ai-gaps.md](../ai-gaps.md)。
- 配色：黛蓝 · 暖灯；字体：MiSans（© 小米，按其许可使用，未修改，不随仓库分发，见 [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md)）。
- 高保真稿 HTML 需要的字体放在 `ui-hifi/src/fonts/`（不入库），请自行从 [MiSans 官网](https://hyperos.mi.com/font/zh/download) 下载。
- 截图脚本：`ui-hifi/src/shot.sh`。

