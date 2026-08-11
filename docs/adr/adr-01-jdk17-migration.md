# JDK 17 兼容性升级：构建工具链 17 + 字节码基线 8 + 测试栈最小化

三个活跃插件（`logfile-reporter-plugin`、`override-httpclient-4.x-plugin`、`override-hutool-http-5.x-plugin`）需要支持应用 JVM 为 JDK 17 的场景。审计结论是插件运行时已天然兼容（主代码无 `sun.*`/JDK 内部模块反射、无已移除的 EE API，73 个测试在 JDK 17 下实跑全绿），因此本次升级不做插件源码改动，只动构建、测试与验证基础设施。

**决策**：构建工具链升级到 JDK 17，但产物用 `<release>8</release>` 保持字节码基线 8；测试栈最小化（不升 Mockito/JUnit，仅统一 Surefire 3.x）；demo-app 的 Spring Boot 升到 2.7.x；CI 重写为单个 JDK 17 任务并去掉 `-DskipTests`；验证回路演示运行时从"仅 JDK 8"扩展为"JDK 8 默认 + JDK 17 显式支持"。

## Considered Options

- **放弃 JDK 8 基线、字节码升到 11/17**：拒绝。SkyWalking agent 9.4.0 支持 JDK 8–21，存量 JDK 8 应用仍需插件挂载；"用 JDK 17 编译"与"字节码 8"不冲突，`<release>8</release>` 同时消除过时告警并强制 API 边界。
- **全量升级测试栈（Mockito 4/5、JUnit 5）**：拒绝。已实测三个模块 73 例测试在 JDK 17 下全绿，升级只引入回归风险，无本次收益。
- **demo-app 保持 2.5.4 或仅升 2.5.5**：拒绝。Boot 2.5 线已 EOL，官方声明 JDK 17 支持自 2.5.5；2.7.x 是最后一条兼容 Java 8 的 Boot 2.x 线。

## Consequences

- CI 必须改用 JDK 17 并运行测试，否则"构建+测试兼容"无守门；触发路径扩展到三个活跃模块与 `agent/pom.xml`。
- 验证回路（`run-with-agent.ps1`/`validate.ps1`/`setup.ps1`）维持单脚本形态。插件**构建工具链**与**演示运行时**解耦：构建默认用 JDK 17（`-BuildJavaHome` 显式指定或本机 `jdk-17*` 扫描，`release 8` 需 JDK 9+ 编译器，脚本以 `Test-Release8Capable` 快速失败并给出可读提示）；演示运行时 JDK 8 为默认、JDK 17 经 `-JavaHome` 显式指定。`README-compile.md`、`demo-app/README.md` 与 AGENTS.md 的"演示运行环境约定 JDK 8"措辞已更新。
- 插件版本保持 1.0.0、脚本中 jar 名硬编码保持不变（有意为之，见 Q10 决策）。
