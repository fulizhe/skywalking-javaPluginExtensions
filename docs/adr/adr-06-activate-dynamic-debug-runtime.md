# 激活 dynamic-debug-runtime-9.x-plugin：活跃模块由三个扩为四个

> **状态**：accepted（2026-10-13）。**实现已落地，事后补记** —— 记录"活跃模块集合由三个扩为四个"这一治理决策及其边界，免得下次有人重新推一遍"该不该进 CI/发布资产""冻结线为什么少一个 jar"。

## 背景

1. 此前活跃模块是**三个**：`logfile-reporter-plugin`、`override-httpclient-4.x-plugin`、`override-hutool-http-5.x-plugin`（`docs/repo/module-status.md`）。三者各有一套容器化 verify 场景，且都在 CI 的 `build-and-test.yml` / `package.yml` 列表里。
2. `dynamic-debug-runtime`（运行时开/关 feign / jdbc / httpclient / springmvc / tomcat 的采集开关）是更早的产物，长期游离在 CI 之外，且属半成品：包 `...v8`、模块名 `8.x`、含死代码（`ReportStatusOfEnableMoreTraceDataInterceptor`）、**缺插件时会整段中断**（每个 toggle 都 `throw new RuntimeException`）、`handleMethodException` 在无 trace 上下文时解引用 `ContextManager.activeSpan()` 会 **NPE**、无单测。
3. 本轮把它修好并纳入维护：拆 `enableDebug()` / `disableDebug()`、缺插件改为只告警跳过、用 `ContextManager.isActive()` 修 NPE、删死代码、补单测；并改名 `dynamic-debug-runtime-9.x-plugin`、包 `...dynamicdebugruntime.v9`（**模块名表达所支持的 agent 版本**；本仓 master 与 `release/1.0.0` 两线都锚定 agent `9.4.0`，故 9.x 在两条线都成立）。
4. 同期，孪生模块 `dynamic-enable-runtime-8.x-plugin` 被判为**冻结**——它实现的是"平时关 OAP、按需启动 server 再打开 agent 上报开关"的旧思路，不符合当前场景——并从 `agent/pom.xml` 的 `<modules>` 摘出。

## 决策

1. **激活 `dynamic-debug-runtime-9.x-plugin` 为活跃模块。活跃模块集合由三个扩为四个**：`logfile-reporter-plugin`、`dynamic-debug-runtime-9.x-plugin`、`override-httpclient-4.x-plugin`、`override-hutool-http-5.x-plugin`。**以 `docs/repo/module-status.md` 为唯一准绳。**
2. "活跃" = 纳入 CI 的 `build-and-test`（测试门禁）与 `package`（发布资产）列表，并在 `docs/repo` 记为 Active。
3. **发布资产数量按线而异**：master（2.0.0 线）**四个**；冻结线（`release/1.0.0`）**三个** —— 本模块属新增，未回移（见 `version-lines.md` 的「改动范围」）。该差异已在 `version-lines.md` 如实记录，**不得**把"四个"当成两条线的共同口径。
4. **回移仅限代码**：修好后的插件模块（含改名 9.x/v9）可 cherry-pick 回 `release/1.0.0`（该线的同名模块此前同样坏）；CI 的 `build-and-test.yml`、`verify/**`、`demo-app` 等冻结线不存在的文件不回移。
5. 冻结的 `dynamic-enable-runtime-8.x-plugin` **不计入活跃数**：不接 CI、不做功能、不投维护精力。

## Considered Options

- **保持三个活跃、debug 模块继续游离**：拒绝。它已修好且有单测，游离意味着无 CI 门禁、无发布资产，回归无人守。
- **保留 `8.x` 命名（不改 9.x）**：拒绝。模块名用于提示挂载方"装进哪个版本的 agent"；两线都锚 `9.4.0`，留 `8.x` 会误导。
- **把它并入 logfile-reporter 或某个 override 模块**：拒绝。它是独立的 `PluginDefine` 与独立增强目标（`org.apache.skywalking.apm.toolkit.trace.SWDebugUtils`），与报告器/override 无耦合，合并只为凑数。
- **回移到冻结线一起发（冻结线也四个资产）**：拒绝。新增模块属「改动范围」外的功能，冻结线只收 bugfix；冻结线的发布形态由它自带的 `package.yml` 决定。
- **顺带激活孪生 enable 模块**：拒绝。其思路（按需起 OAP 再上报）不符合当前场景，已冻结。

## Consequences

- **CI**：`build-and-test.yml`（`.github` 及 `.gitee` 镜像）与 `package.yml` 的 `paths` / `MODULES` 加入第 4 个模块。
- **文档**：`module-status.md` 增 Active 项并新增 Frozen 项；`version-lines.md` 资产数改为"按线而异"；`known-todos.md` 里"`mvn test` 全量断裂（antrun 拷未提交的 `SWUtils.java`）"结项（该 antrun 与未被编译的模板源文件已删，拷贝模板只保留 `doc/SWDebugUtils.java` 一份）；"三个活跃插件"的**当前态**措辞 sweep 为"每个已接入 verify 的插件"。
- **demo-app**：新增宿主桩 `org.apache.skywalking.apm.toolkit.trace.SWDebugUtils` 与 `/sw/debug/enable`、`/sw/debug/disable` 端点。
- **可回移性**：1.0.0 线上的同名模块此前同样坏，本轮的修复（拆 enable/disable、容错、NPE、去死代码、单测）可 cherry-pick 过去。**注意**：提交里改名与修复在同一 commit，cherry-pick 会连带把冻结线的模块也改成 9.x/v9（该线亦锚 9.4.0，成立）。
- **未决**：冻结线是否也补一份 `build-and-test.yml`（否则该线只打包、不跑测试门禁）。

## 参考

- `docs/repo/module-status.md` —— active / frozen 现状（唯一准绳）
- `docs/repo/version-lines.md` —— 「改动范围」与"资产按线而异"
- `AGENTS.md` —— Task routing 与「版本线」一节
