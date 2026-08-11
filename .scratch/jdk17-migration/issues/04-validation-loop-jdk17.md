# 04 — 验证回路支持 JDK 17

**What to build:** `run-with-agent.ps1` 在 JDK 17 演示运行时下端到端跑通（构建插件→装入 agent→启动 demo→就绪→插件加载验证），证明"JDK 17 应用 JVM + agent 9.4.0 + 插件 + demo-app"整链正常（运行兼容）；JDK 8 默认路径不受影响；文档约定更新为"JDK 8 默认 + JDK 17 显式支持"。

**Blocked by:** 03 — demo-app 需先在 JDK 17 下能构建启动。

**Status:** done

- [x] `run-with-agent.ps1 -JavaHome <jdk17>` 全流程通过（validate.ps1 -JavaHome jdk-17.0.8 实测 exit 0）
- [x] 默认（JDK 8）路径仍通过，行为不变（validate.ps1 默认实测 exit 0）
- [x] 运行时暴露的问题就地修复：脚本新增 `-BuildJavaHome`，插件构建工具链与演示运行时解耦（release 8 需 JDK 9+ 编译器）
- [x] README-compile.md / README.md / AGENTS.md 的演示运行时约定更新为"8 默认 + 17 显式支持"
