# OMO agent 名在用户环境的实际映射（通用教程与真实环境的偏差）

用户第 1 课反馈「没有你说的 Prometheus / 方法二、三」。查本机 `oh-my-openagent/dist/index.js` 与 opencode 内置，确认用户 agent 下拉只有 4 项，各自主角不同：

- `Build` = opencode 内置 build（matt-skills 主体，对应「方法一直接打字」）
- `Plan` = opencode 内置 plan / OMO 的 prometheus（plan-mode-only，只能写 `.omo/*.md`，来自 `PLAN_AGENT_NAMES=["plan"]` 与 `DEFAULT_SKIP_AGENTS=["prometheus","compaction","plan"]`）
- `Sisyphus - Ultraworker` = OMO 的 sisyphus（即「方法二 ultrawork」，选中即全自动，`AGENT_DISPLAY_NAMES` 里 `sisyphus: "Sisyphus - ultraworker"`）
- `Atlas - Plan Executor` = OMO 的 atlas（「方法三」的执行端）

**Evidence**: 用户截图下拉只列 `Sisyphus - Ultraworker / Atlas - Plan Executor / Build / Plan`；dist 里 `AGENT_DISPLAY_NAMES` 与 `assemble*` 逻辑证实 `sisyphus_agent.disabled:true` 时 build/plan 完整保留、discipline agent 仍注册。

**Implications**: 教这个用户时，「三种用法」必须映射到他的 4 个 agent 名，不能用通用教程的 Prometheus/Hephaestus 名字——会把他搞懵。第 1、2 课已据此修正（用「左下角下拉选 agent」替代「背命令名」）。
