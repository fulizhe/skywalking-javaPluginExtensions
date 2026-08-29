# Mission 明确为「matt-skills 主体 + OMO 补短板」的协同模型

用户补充了真实目标：不是单独学 OMO，而是让 **matt-skills 作为主体工作流**（spec/ticket/ADR 驱动），**OMO 只补短板**（后台多 agent、探索、跨文件深分析、计划续跑）。

**Evidence**: 用户原话「matt-skills 为主体工作流，omo 补短板」。项目 `.omo/omo.jsonc` 注释早已写明此定位（`sisyphus_agent.disabled: true`），`docs/agents/*` 与 `.scratch/` 是 matt-skills 的项目载体。

**Implications**: 后续教学不再只讲 OMO 用法，而是讲「分工边界 + 决策：何时用 matt-skills 哪个工具 / 何时调 OMO 哪块能力」。已更新 MISSION.md（Why / Success looks like），并新增第 2 课《matt-skills 与 OMO 协同》与说明 `reference/matt-omo-collab.md`。
