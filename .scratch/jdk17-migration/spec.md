# jdk17-migration: 三个活跃插件的 JDK 17 兼容性升级

Status: done

## Problem Statement

应用生态正从 JDK 8 迁往 JDK 17,但三个活跃插件(`logfile-reporter-plugin`、`override-httpclient-4.x-plugin`、`override-hutool-http-5.x-plugin`)的构建、测试与验证基础设施全部锚定 JDK 8:

1. **构建**:父 pom 用 `<source>1.8</source><target>1.8</target>`,在 JDK 17 下编译产生过时告警,且不校验 API 边界——代码可能误用 JDK 17 API 却被编译进"字节码 8"产物,在 JDK 8 应用上运行时才炸。
2. **测试**:测试栈锚定旧版(Mockito 3.5.13、JUnit 4.12、Surefire 2.22.2 与 3.1.2 混用),从未在 JDK 17 下验证;CI 又用 `-DskipTests` 跳过测试,兼容性回归无守门。
3. **验证回路**:演示运行时约定为"仅 JDK 8"(`run-with-agent.ps1` 硬编码 jdk1.8* 优先),JDK 17 不是一等公民。
4. **CI**:现有 workflow 是残桩——引用不存在的 `mvnw`、触发路径只有 logfile 一个模块、跳过测试,从未真正守门。

插件运行时本身已审计为 JDK 17 天然兼容(主代码无 `sun.*`/JDK 内部模块反射、无已移除的 EE API),因此问题集中在基础设施层而非源码层。

## Solution

在不改插件源码的前提下,把三个活跃插件的支持范围扩到 JDK 17,同时保持 JDK 8 应用完全可用:

- **构建兼容**:用 JDK 17 工具链编译,产物经 `<release>8</release>` 保持字节码基线 8。
- **测试兼容**:现有测试套件在 JDK 17 下通过(已验证),并统一 Surefire 版本,由 CI 背书。
- **运行兼容**:验证回路演示运行时扩展为"JDK 8 默认 + JDK 17 显式支持",端到端证明 agent 9.4.0 + 三插件在 JDK 17 应用 JVM 下正常。
- **守门**:CI 重写为单个 JDK 17 任务,构建并测试三个活跃插件,去掉 `-DskipTests`。

## User Stories

1. 作为插件作者,我想在 JDK 17 的应用 JVM 下运行 agent + 三插件,以便升级到 17 的存量应用可用。
2. 作为插件作者,我想用 JDK 17 工具链编译插件(产物字节码仍为 8),以便开发机无需再装 JDK 8。
3. 作为插件作者,我想编译时不再看到 `-source 8 已过时` 告警且 API 边界被强制,以便产物在 JDK 8 应用上安全运行。
4. 作为插件作者,我想三个插件的测试套件在 JDK 17 下全部通过,以便测试兼容性有据可查。
5. 作为插件作者,我想用一条命令在 JDK 17 下跑通验证回路(构建→装 agent→启动→造数→断言),以便端到端验收 JDK 17 运行时。
6. 作为插件作者,我想验证回路保持 JDK 8 默认可用,以便 JDK 8 存量用户不受影响。
7. 作为维护者,我想 CI 在 JDK 17 下构建并测试三个活跃插件(不再跳过测试),以便兼容性不回归。
8. 作为维护者,我想 CI 在三个活跃模块或父 pom 变更时触发,以便覆盖范围与改动面一致。
9. 作为维护者,我想 demo-app 在 JDK 8 与 17 下都能启动并通过验证回路,以便"演示运行时"双版本成立。
10. 作为维护者,我想测试栈尽量少动(仅统一 Surefire),以便升级回归风险最小。
11. 作为维护者,我想本次升级的关键取舍有 ADR 可查,以便未来读者理解"为何升级 17 却保留字节码 8 / 旧测试栈"。
12. 作为插件使用方,我想文档明确插件支持 JDK 17,以便评估应用是否可迁移。

## Implementation Decisions

- **编译基线**:父 pom 的 `maven-compiler-plugin` 配置由 `source/target 1.8` 改为 `release 8`。消除过时告警并强制 API 边界;影响 agent reactor 全部模块(含 archived 模块,属纯安全升级,不改其源码)。
- **测试栈最小化**:不升级 Mockito/JUnit;在父 pom 以 pluginManagement 统一 `maven-surefire-plugin` 到 3.x,消除 2.22.2/3.1.2 混用;`logfile-reporter-plugin` 保留 `forkCount=0`(同进程运行)与 alert 测试 includes 语义。
- **demo-app 技术栈**:Spring Boot 2.5.4 → 2.7.x(最后一条兼容 Java 8 的 Boot 2.x 线),`java.version` 保持 1.8;Micrometer 由 Boot 管理升至 1.9,与 `apm-toolkit-micrometer-registry` 兼容。需回归 JDK 8 / JDK 17 双运行时。
- **验证回路**:`run-with-agent.ps1` 维持单脚本形态;演示运行时约定从"仅 JDK 8"改为"JDK 8 默认 + JDK 17 经 `-JavaHome` 显式支持";同步更新 README-compile.md 与 AGENTS.md 的措辞。插件版本 1.0.0 与脚本中 jar 名硬编码保持不变。
- **CI 重写**:现有工作流为残桩(引用不存在的 `mvnw`、仅 logfile 路径触发、`-DskipTests`)。重写为单个 JDK 17 任务,经 `agent/pom.xml` 对三个活跃插件 build + test(去 `-DskipTests`);触发路径扩展至三个活跃模块目录 + `agent/pom.xml`。
- **插件源码零改动**:运行时审计确认无 JDK 内部反射/已移除 API,73 例测试已在 JDK 17 下实跑全绿。
- **决策记录**:`docs/adr/adr-01-jdk17-migration.md` 已落盘;`CONTEXT.md` 已新增 Compatibility 分组词条(运行兼容/构建兼容/测试兼容/字节码基线/演示运行时)。

## Testing Decisions

- 好测试的定义:只测外部可见行为,不测实现细节——运行时兼容由验证回路的黑盒断言(HTTP 契约 + agent 日志)证明;构建/测试兼容由 CI 的 `mvn test` 证明。
- 接缝(三个,分层验证):
  - **Seam 1 · 验证回路(运行时验收线,最高接缝)**:`run-with-agent.ps1 -JavaHome <jdk17>` 端到端证明"JDK 17 应用 JVM + agent 9.4.0 + 三插件 + demo-app"整链正常(插件加载、内存模式报告、仪表盘、trace 告警)。
  - **Seam 2 · CI 门禁(自动化回归线)**:GitHub Actions 单 JDK 17 任务跑三插件 `mvn test`,把"构建+测试兼容"固化为守门。
  - **Seam 3 · 模块测试套件(细粒度接缝)**:现有 73 例测试在 JDK 17 下通过,作为 CI 内容载体,不新增用例。
- 被测试模块:三个活跃插件(现有单测)+ demo-app(经验证回路)。
- 先例:demo-app spec 的验证回路黑盒断言层;插件模块现有 `alert/*` JUnit 测试。

## Out of Scope

- 插件源码级改动(运行时审计表明无需)。
- 测试栈全面升级(Mockito 4/5、JUnit 5)——已验证 17 下全绿,升级留作后续。
- 移除 JDK 8 支持或提升字节码基线。
- 插件版本号提升与脚本 jar 名硬编码修复(Q10 决策保持现状)。
- archived 模块的 JDK 17 适配(仅共享父 pom 编译基线,不改其代码)。
- CI 升级为发布流水线(原"Push to Artifact Repo"仅为命名,本次只做 build+test 守门)。
- agent 发行包与 OAP 后端(agent 锁定 9.4.0,已支持 JDK 8–21)。

## Further Notes

- 术语见 `CONTEXT.md` 的 Compatibility 分组;决策见 `docs/adr/adr-01-jdk17-migration.md`。
- 现状事实:本机具备 JDK 17.0.8 + JDK 1.8.0_92 双环境;三插件 73 例测试已实跑 JDK 17 全绿。
- 运行时验证可能暴露非预期问题(如 agent 在 17 下的 `--add-opens`、Boot 2.7 行为差异),发现即就地修,属本 spec 范围。
- 实现阶段请对照 AGENTS.md 的模块维护状态与 Known TODOs。
