# OhMyOpenCode (OMO) Resources

## Knowledge

- [OMO GitHub 仓库 — code-yeongyu/oh-my-openagent](https://github.com/code-yeongyu/oh-my-openagent)
  The source of truth. `docs/guide/*.md` 是官方手册。Use for: 任何「OMO/oh-my-openagent 是什么」的权威答案，以及 `docs/guide/installation.md`, `overview.md`, `orchestration.md` 三篇核心指南。
- [OMO 官方文档站 — ohmyopenagent.com/docs](https://ohmyopenagent.com/docs)
  网页版手册。Use for: 阅读体验更好的浏览版指南。
- [OpenCode CLI 文档 — opencode.ai/docs/cli](https://opencode.ai/docs/cli/)
  OpenCode 底层 CLI 的官方文档（opencode 命令、flag、非交互模式）。Use for: 命令行用法、`opencode run`、`models`、`auth` 等。
- [OpenCode TUI 文档 — opencode.ai/docs/tui](https://opencode.ai/docs/tui/)
  OpenCode 界面内的 slash commands（`/help`, `/new`, `/sessions`, `/compact`, `/undo` 等）。Use for: 界面里敲斜杠命令的完整清单和快捷键。
- [OpenCode Commands — opencode.ai/docs/commands](https://opencode.ai/docs/commands/)
  命令列表。Use for: 查具体命令参数。

## 本机的权威来源（比文档更准）

- `C:\Users\lqzkc\.cache\opencode\packages\oh-my-openagent@latest\node_modules\oh-my-openagent\dist\index.js`
  本机实际安装的 OMO 插件编译产物。**遇到「行为奇怪/报错」时直接在这里搜**，比任何文档都准。Use for: 复现/定位 bug（例如本次 goal 钩子 2000 字符上限就是在这里找到的）。
- `D:\gitRepository\skywalking-javaPluginExtensions\.omo\omo.jsonc`
  本项目当前的 OMO 配置（`sisyphus_agent.disabled`, `goal.enabled=false`, `agents`/`categories` 模型绑定）。Use for: 理解本项目 OMO 为什么这样跑。
- `D:\gitRepository\skywalking-javaPluginExtensions\docs\notes\2026-08-29-opencode-plugins-omo-setup.md`
  之前配 OMO 时沉淀的经验笔记。Use for: 插件名坑、配置文件分层、模型路由等背景。

## Wisdom (Communities)

- [oh-my-openagent GitHub Issues](https://github.com/code-yeongyu/oh-my-openagent/issues)
  Use for: 遇到 OMO 自身 bug（而不是你的代码 bug）时，多数已知问题已有人报过。
- 暂无本地社群。用户偏好：遇到问题先自己查日志和本机源码，不急于发帖。

## Gaps

- OMO 的官方中文教程较少，多数为英文。当前缺一份「面向完全新手的简体中文起步教程」——这正是本教学空间要填补的。
