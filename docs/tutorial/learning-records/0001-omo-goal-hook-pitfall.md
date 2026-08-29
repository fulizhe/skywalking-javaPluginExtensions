# OMO goal 钩子会拦下长消息导致「发送命令失败」

用户曾遇到 `opencode` GUI 弹「发送命令失败 / Unexpected server error」，`/teach` 长消息发不出去。追查 `log/opencode.log` 与 `oh-my-openagent/dist/index.js`，确认根因：OMO 的 `handleGoalMessage` 钩子把每条消息文本当「目标」存，而 `validateObjective` 硬上限 `MAX_OBJECTIVE_LENGTH = 2000`，超限即抛 `InvalidObjectiveError`，整条消息被打回。

**Evidence**: 日志多行 `message=failed ... error="InvalidObjectiveError: Objective exceeds maximum length of 2000 characters"`，调用栈指向 `handleGoalMessage → setGoal → validateObjective`（index.js:107147 的 `var MAX_OBJECTIVE_LENGTH = 2000`）。

**Implications**: 已通过 `.omo/omo.jsonc` 把 `goal.enabled` 设为 `false`（源码 line 134803 验证：`isHookEnabled("goal") && pluginConfig.goal?.enabled` 为 false 时 `hooks.goal` 变 undefined，`handleGoalMessage` 第一条 `if (!hooks.goal) return;` 短路）。改配置需**重启 opencode** 才生效。以后用户再遇到「发送命令失败」，应先去翻日志而非怀疑自己的代码——这是本教学空间的真实故障案例。
