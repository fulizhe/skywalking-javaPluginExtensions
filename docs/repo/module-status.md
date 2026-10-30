# 模块维护状态（module maintenance status）

本页列出仓库各模块的维护状态与容量边界。**动任何模块前先读本页**，避免越界修改归档模块。

- **Active**: `agent/logfile-reporter-plugin`, `agent/dynamic-debug-runtime-9.x-plugin`, `agent/override-httpclient-4.x-plugin`, `agent/override-httpclient-5.x-plugin`, `agent/override-hutool-http-5.x-plugin` — ongoing iteration (bugfix & feature).
- **Frozen（思路已废弃，仅留档）**: `agent/dynamic-enable-runtime-8.x-plugin` — 旧方案"平时关 OAP、按需启动 server 再打开 agent 上报开关"，不符合当前场景。不接 CI、不做功能、不投维护精力；如需历史脉络见该模块 README 的「背景说明」。
- **Sample & validation**: `agent/demo-app` — plugin capability demo + one-command validation loop for the active modules. Standalone pom, deliberately NOT in the agent reactor, so plugin builds (`-pl <plugin> -am`) and CI paths stay untouched.
- **Archived**: all other modules — critical bugfix only; feature work needs pre-approval.

> 调用工具链（to-tickets / triage / to-spec / domain-modeling）时，把计划上下文与 feature 限定在 active 模块。
