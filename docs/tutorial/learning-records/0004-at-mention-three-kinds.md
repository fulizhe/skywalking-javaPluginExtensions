# `@` 不只是引用文件——还有子 agent 提及与 MCP 资源（GUI 环境）

用户第 1 课只学了「`@文件名` 引用文件」，但实际 `@` 弹出三类东西（截图证实）：
- **① 文件** — 把文件内容加进对话
- **② Agent（子 agent 提及）** — `@explore`/`@oracle`/`@librarian`/`@general`/`@Metis`/`@Momus` 等，召唤专门 agent 另开子会话干活
- **③ MCP 工具/资源** — `@tools_list` 等

**Evidence**: 用户截图 `@` 菜单含 `@Metis - Plan Consultant`、`@Momus - Plan Critic`、`@explore`、`@general`、`@librarian`、`@multimodal-looker`、`@oracle`、`@code-simplifier:code-simplifier`、`@tools_list`。官方 agents 文档原文：「Subagents can be invoked by @ mentioning a subagent in your message」。用户环境是 **opencode 桌面 GUI**（`@opencode-aidesktop`），与终端同机制，只是鼠标点击而非键盘快捷键。

**Implications**: 教学中的「小技巧」从「@文件名」扩展为「@ 三类」。视觉/前端老师需明确：`@agent名` 是**派活给分身**（用户"派"），`@文件名` 是**让它看文件**（用户"看"）。已更新第 1 课 `lessons/0001-omo-startup.html`、速查表 `reference/omo-cheatsheet.html`（并删除过时的 `.md` 版）。
