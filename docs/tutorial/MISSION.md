# Mission: OhMyOpenCode (OMO) 起步使用

## Why

我是一个在做 skywalking-javaPluginExtensions 项目的开发者(matt-skills 学习者)。我的终极目标是 :**让 matt-skills 作为主体工作流(以 spec/ticket/ADR 驱动),OMO 只补齐它缺的短板(后台多 agent、探索、跨文件深度分析、计划续跑)**——两者协同,而不是二选一。当初因 OMO 的 goal 钩子拦下 `/teach` 消息报「发送命令失败」而卡住,一度以为只能学其中一种,现在要把协同模型彻底搞懂。

## Success looks like

- 能说清 **matt-skills 与 OMO 的分工边界**:谁是主体、OMO 补哪几块短板
- 走一个真实任务时,能判断「该用 matt-skills 的哪个工具」还是「该用 OMO 的哪块能力」
- 不用查就能说出 OMO 的**三种使用方式**（直接打字 / `ultrawork` / Prometheus→`/ulw-execute`），并知道各自适合什么场景
- 能背出 5 个以上**常用 slash command**（`/agent`, `/new`, `/sessions`, `/compact`, `/exit` 等）并说出各自作用
- 能说出 OMO 的核心 agent（Sisyphus / Prometheus / Atlas / Oracle / Librarian / Explore）各管什么
- 遇到「消息发不出去 / Unexpected server error」能自己判断：大概率是工具配置问题，知道去哪查日志而不是乱改代码
- 能独立在 skywalking 项目里用 matt-skills 的 `/to-spec`→`/to-tickets` 走完一个 feature 的 spec/ticket 流,再用 OMO 补齐探索/实现环节

## Constraints

- 中文母语，偏好中文讲解，命令本身用英文原样
- Windows 环境（pwsh 7），`opencode` 跑在 GUI 里、不在 shell PATH
- 新手，不要一次塞太多概念；每次一小块，讲完就能上手
- 当前 project 配置：`sisyphus_agent.disabled=true`（不自动接管主控），`goal.enabled=false`（已关闭，防钩子拦消息），cert 只有 `opencode-go` 一家 provider

## Out of scope

- OMO 的团队模式（Team Mode）、ticket 工作流、`to-spec`/`to-tickets` 等 matt-skills 深度工作流
- OMO 12 个 agent 的完整模型路由/fallback 细节（只需要知道谁干嘛用）
- 改 OMO 源码或自定义 agent（当前阶段只需要会用，不需要会造）
