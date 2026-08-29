# 模块维护状态（module maintenance status）

本页列出仓库各模块的维护状态与容量边界。**动任何模块前先读本页**，避免越界修改归档模块。

- **Active**: `agent/logfile-reporter-plugin`, `agent/override-httpclient-4.x-plugin`, `agent/override-hutool-http-5.x-plugin` — ongoing iteration (bugfix & feature).
- **Sample & validation**: `agent/demo-app` — plugin capability demo + one-command validation loop for the active modules. Standalone pom, deliberately NOT in the agent reactor, so plugin builds (`-pl <plugin> -am`) and CI paths stay untouched.
- **Archived**: all other modules — critical bugfix only; feature work needs pre-approval.

> 调用工具链（to-tickets / triage / to-spec / domain-modeling）时，把计划上下文与 feature 限定在 active 模块。
